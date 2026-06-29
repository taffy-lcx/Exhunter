#!/usr/bin/env python3
"""ACRS LLM evaluation using Claude Haiku 4.5 as the judge.

For each true-positive sample, give the judge LLM three code blocks — ORIGINAL
(methodBefore), REFERENCE (human methodAfter), CANDIDATE (a method's methodResult) —
and ask 'good' or 'bad'. ACRS = good / (good + bad).

TP subset (default): each method's own detection-TP — label==1 and the method decided it
needs a fix (ours/direct: needLLM=='yes'; seeker: all label==1; kpc/fuzzycatch/nexgen/neurex:
nativeDetected). --common scores all methods on the intersection of their detection-TPs.

Usage:
  python data/acrs_eval.py <output.json>            # one output file
  python data/acrs_eval.py --all [--md-out out.md]  # every method in METHODS
  python data/acrs_eval.py --methods a,b,c          # a subset of METHODS
  python data/acrs_eval.py --all --summary-only     # tally cached verdicts, no LLM
"""
import argparse
import hashlib
import json
import os
import sys
import time
import urllib.request

API_URL = os.environ.get("ACRS_API_URL", "")
API_KEY = os.environ.get("ACRS_API_KEY", "")
MODEL   = os.environ.get("ACRS_MODEL", "claude-haiku-4-5-20251001")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RESULT_DIR = os.path.join(os.path.dirname(__file__), "acrs")

# method name -> output JSON path (relative to project root)
METHODS = {
    "ours-qwen-3stage":     "data/qwen3-max-3stage/output_0.2.json",
    "ours-deepseek-3stage": "data/deepseek-v4-flash-3stage/output_0.2.json",
    "ours-gpt-4o-3stage":   "data/gpt-4o-3stage/output_0.2.json",
    "seeker-gpt-4o": "baselines/seeker/seeker_gpt-4o_output.json",
    "neurex":        "baselines/Neurex/neurex_output.json",
    "kpc":           "baselines/KPC/kpc_gpt-3.5-turbo_output.json",
    "fuzzycatch":    "baselines/exassist_repo/exassist_output.json",
    "nexgen-orig":   "baselines/nexgen/nexgen_output.json",
    "direct-prompt": "data/zero-shot-prompt/output.json",
    "wo-triage":             "data/wo-triage/output_0.2.json",
    "wo-call-path":          "data/wo-call-path/output_0.2.json",
}

PROMPT = """You are evaluating an automatically-generated exception-handling fix for a Java method, \
using the human developer's fix as the reference standard.

You are given:
(1) ORIGINAL  - the method before any exception-handling fix.
(2) REFERENCE - the fix written by a human developer (the ground-truth standard).
(3) CANDIDATE - the fix produced by an automated tool, which you must judge.

The REFERENCE shows which statements the developer considered threatened and wrapped in a try \
block. Judge the region the CANDIDATE wraps against this. Two goals matter together: \
(1) COVERAGE — the CANDIDATE should enclose as much of the threatened code (the statements the \
developer wrapped) as possible; and (2) PRECISION — it should not pad the try block with redundant \
code the developer left outside it. The CANDIDATE is 'good' when its try block covers the \
developer-protected statements while including little or no unrelated code the developer did not \
wrap. It is 'bad' when it misses much of the threatened code (poor coverage), or balloons the try \
block with redundant statements the developer left untouched (poor precision). The exact exception \
type caught and the handler body do not matter here — judge only the wrapped code region.
Answer with a SINGLE word only: good or bad.

ORIGINAL:
```java
{before}
```

REFERENCE (human) FIX:
```java
{human}
```

CANDIDATE FIX:
```java
{model}
```

Output exactly one lowercase word: good or bad.
"""


def _resolve(p):
    return p if os.path.isabs(p) else os.path.join(ROOT, p)


def _key(it):
    return (it.get("repo_id"), it.get("patch"), it.get("methodName") or it.get("method_name"))


def call_llm(user):
    body = json.dumps({"model": MODEL, "messages": [{"role": "user", "content": user}],
                       "temperature": 0, "max_tokens": 5}).encode()
    last = None
    for attempt in range(3):
        try:
            req = urllib.request.Request(API_URL, data=body,
                headers={"Authorization": f"Bearer {API_KEY}", "Content-Type": "application/json"})
            r = json.load(urllib.request.urlopen(req, timeout=120))
            return r["choices"][0]["message"]["content"] or ""
        except Exception as e:
            last = e
            time.sleep(1.5 * (attempt + 1))
    raise last


def anchor_tp_keys(path):
    """Anchor TP subset: label==1 and needLLM=='yes' — the same keys across methods."""
    d = json.load(open(path))
    if not any((x.get("needLLM") or "").strip().lower() in ("yes", "no", "error") for x in d):
        raise SystemExit("anchor output has no needLLM field")
    return {_key(x) for x in d if x.get("label") == 1 and (x.get("needLLM") or "").strip().lower() == "yes"}


def detection_tp_keys(name, path):
    """A method's TP subset by its OWN detection signal (it decided the method needs
    a fix). A method that decided 'no fix needed' did not repair, regardless of whether
    the code text changed — so we use the detection decision, not methodResult != before.
      ours-* / direct-prompt : needLLM == 'yes'
      seeker                 : every label==1 (its handler attempts a fix on all)
      kpc/fuzzycatch/nexgen  : nativeDetected is True
    """
    d = json.load(open(path))
    out = set()
    for x in d:
        if x.get("label") != 1:
            continue
        if name.startswith("ours-") or name == "direct-prompt" or name.startswith("wo-"):
            if (x.get("needLLM") or "").strip().lower() == "yes":
                out.add(_key(x))
        elif name.startswith("seeker"):
            out.add(_key(x))
        elif x.get("nativeDetected") is True:
            out.add(_key(x))
    return out


def common_tp_keys(names):
    """Intersection of the named methods' detection-TP subsets — the same samples that
    every one of them decided to fix, so ACRS is compared on identical samples."""
    sets = [detection_tp_keys(n, _resolve(METHODS[n])) for n in names]
    return set.intersection(*sets) if sets else set()


def cache_path(name):
    return os.path.join(RESULT_DIR, f"{name}_acrs.jsonl")


def load_done(path):
    done = {}
    if os.path.exists(path):
        for line in open(path):
            try:
                r = json.loads(line)
                done[r["key"]] = r["verdict"]
            except Exception:
                pass
    return done


def eval_method(name, method_file, limit, summary_only, anchor_keys=None):
    try:
        data = json.load(open(method_file))
    except Exception as e:
        print(f"  [{name}] cannot open {method_file}: {e}")
        return None
    if anchor_keys is None:
        # own subset = the method's detection-TP (consistent with eval_all repair metrics
        # and the common subset): a method that decided "no fix needed" did not repair.
        anchor_keys = detection_tp_keys(name, method_file)
    tps = [it for it in data if _key(it) in anchor_keys]
    if limit:
        tps = tps[:limit]
    out_path = cache_path(name)
    done = load_done(out_path)

    good = bad = other = 0
    fout = None if summary_only else open(out_path, "a")
    try:
        for i, it in enumerate(tps, 1):
            k = hashlib.md5((str(it.get("repo_id")) + str(it.get("method_name"))
                             + (it.get("methodResult") or "")).encode()).hexdigest()
            if k in done:
                v = done[k]
            elif summary_only:
                continue
            else:
                try:
                    ans = call_llm(PROMPT.format(before=it.get("methodBefore") or "",
                                                 human=it.get("methodAfter") or "",
                                                 model=it.get("methodResult") or "")).strip().lower()
                    v = "good" if ans.startswith("good") else ("bad" if ans.startswith("bad") else "other:" + ans[:30])
                except Exception as e:
                    v = "ERR:" + str(e)[:100]
                fout.write(json.dumps({"key": k, "repo_id": it.get("repo_id"),
                                       "method": it.get("method_name"), "verdict": v}, ensure_ascii=False) + "\n")
                fout.flush()
            good += v == "good"
            bad += v == "bad"
            other += v not in ("good", "bad")
            if not summary_only and i % 25 == 0:
                print(f"    [{name}] {i}/{len(tps)}  good={good} bad={bad} other={other}")
    finally:
        if fout:
            fout.close()
    return good, bad, other, len(tps)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("method_file", nargs="?", help="one method's output.json")
    ap.add_argument("--all", action="store_true", help="every method in METHODS")
    ap.add_argument("--methods", default="", help="comma-separated subset of METHODS")
    ap.add_argument("--limit", type=int, default=0, help="only the first N TPs per method")
    ap.add_argument("--summary-only", action="store_true", help="tally cached verdicts, no LLM call")
    ap.add_argument("--md-out", default="", help="write the summary table to this markdown file")
    ap.add_argument("--anchor", default="", help="anchor method name; score all methods on its "
                    "label=1 needLLM=yes subset (same subset across methods)")
    ap.add_argument("--common", default="", help="comma-separated method names; score every "
                    "evaluated method on the INTERSECTION of these methods' TP subsets "
                    "(same samples that all of them flagged-and-fixed)")
    args = ap.parse_args()

    os.makedirs(RESULT_DIR, exist_ok=True)
    anchor_keys = None
    if args.common:
        names = [s.strip() for s in args.common.split(",") if s.strip()]
        for n in names:
            if n not in METHODS:
                sys.exit(f"--common method {n} not in METHODS")
        anchor_keys = common_tp_keys(names)
        print(f"common TP subset of [{', '.join(names)}]: {len(anchor_keys)} samples")
        tasks = [(n, _resolve(METHODS[n])) for n in names]  # evaluate exactly these methods
    elif args.all or args.methods:
        wanted = {s.strip() for s in args.methods.split(",") if s.strip()} if args.methods else set(METHODS)
        tasks = [(n, _resolve(METHODS[n])) for n in METHODS if n in wanted]
    elif args.method_file:
        tasks = [(os.path.splitext(os.path.basename(args.method_file))[0], args.method_file)]
    else:
        ap.error("give a method_file, or use --all / --methods / --common")

    if args.anchor and not args.common:
        if args.anchor not in METHODS:
            sys.exit(f"--anchor {args.anchor} not in METHODS")
        anchor_keys = anchor_tp_keys(_resolve(METHODS[args.anchor]))
        print(f"anchor {args.anchor} TP (label=1 and needLLM=yes): {len(anchor_keys)}")

    rows = []
    for name, path in tasks:
        print(f"== {name} ({path}) ==")
        r = eval_method(name, path, args.limit, args.summary_only, anchor_keys)
        if r is None:
            continue
        good, bad, other, tp = r
        acrs = good / (good + bad) if (good + bad) else 0.0
        rows.append((name, good, bad, other, tp, acrs))
        print(f"   good={good} bad={bad} other={other}  judged={good+bad+other}/{tp}  ACRS={acrs:.4f}")

    lines = ["| method | ACRS | good | bad | other | judged/TP |", "|---|---:|---:|---:|---:|---:|"]
    for name, good, bad, other, tp, acrs in sorted(rows, key=lambda x: -x[5]):
        lines.append(f"| {name} | {acrs:.4f} | {good} | {bad} | {other} | {good+bad}/{tp} |")
    table = "\n".join(lines)
    print("\nACRS = good / (good + bad); other (judge gave no good/bad, or API error) is excluded.")
    print(table)
    if args.md_out:
        with open(args.md_out, "w") as f:
            f.write(f"# ACRS ({MODEL})\n\nACRS = good / (good + bad).\n\n" + table + "\n")
        print(f"\nwrote {args.md_out}")


if __name__ == "__main__":
    main()
