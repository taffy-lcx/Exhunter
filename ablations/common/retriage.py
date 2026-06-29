#!/usr/bin/env python3
"""STRICT ablation re-triage: re-run the method-triage with one input subtracted,
then write a new intermediate so the downstream analyzer/repairer also lose it.

Modes (subtraction on the triage input, faithful to the system being a per-method
triage that scores enumerated candidates AND discovers implicit ones from source):

  no-call-path     : triage sees candidate exceptions (type / javadoc / throw
                     condition) but NO call path (target-method source only, no
                     callee sources, no path labels). Enumerated candidates are
                     re-scored; call-path fields are also stripped from the output
                     so the analyzer loses them too.

  no-api-knowledge : the full triage MINUS the API knowledge base only. The deduped
                     <source methods> (M0 target + M1.. callee sources, library leaf
                     excluded) and per-candidate path labels are reconstructed from
                     each candidate's callPathSerialized, so the call path is fully
                     retained. Local throws keep their statement text; API candidates
                     keep their exception TYPES but lose the `api javadoc:` line and
                     every `— throw condition:`. After re-scoring, the candidate set
                     and call-path fields are kept intact, but each API candidate's
                     javadoc and apiExceptionScore.condition are cleared so the
                     downstream analyzer is blinded to the API KB as well.

Prompts are read from PromptTemplate.json (method triage system/question) so they
stay in sync. LLM = deepseek-v4-flash via apiyi by default.

Usage:
  python data/ablation_common/retriage.py --mode no-api-knowledge \
      --input <intermediate.json> --output <intermediate.json> [--workers 5] [--resume] [--limit N]
"""
import argparse
import json
import os
import re
import threading
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROMPTS = json.load(open(ROOT / "java-scanner/src/main/java/org/LLMAdvisers/PromptTemplate.json"))
SYSTEM = PROMPTS["method triage system"]
QUESTION = PROMPTS["method triage question"]

API_URL = os.environ.get("LLM_API_URL", "")
API_KEY = os.environ.get("LLM_API_KEY", "")
MODEL = os.environ.get("LLM_MODEL_NAME", "")


def _collapse(s):
    return re.sub(r"\s+", " ", s or "").strip()


def _simple(t):
    return str(t).rsplit(".", 1)[-1].strip().lower()


def _parse_called(cps):
    """Pull the `// called: <qname>` source blocks out of a callPathSerialized
    string, in order, as (qname, code). The leading `// target method` block (M0)
    and the library leaf (already excluded by serializeCallPath for API) are not
    returned here."""
    lines = (cps or "").split("\n")
    out = []
    i = 0
    while i < len(lines):
        ln = lines[i]
        if ln.startswith("// called: "):
            q = ln[len("// called: "):].strip()
            i += 1
            if i < len(lines) and lines[i].strip() == "```":
                i += 1
                buf = []
                while i < len(lines) and lines[i].strip() != "```":
                    buf.append(lines[i])
                    i += 1
                out.append((q, "\n".join(buf)))
        i += 1
    return out


def _callpath_labels(rec):
    """Reconstruct the full triage's deduped <source methods> and per-candidate
    path labels from each candidate's callPathSerialized. M0 = target; callee
    methods get M1.. in first-appearance order across candidates (matching
    constructMethodTriageQuestionPrompt). Returns (src, routelab) where src is a
    list of (label, qname, code) for M1.. and routelab maps candidate index -> the
    `M0 -> M1 -> ...` path string."""
    cands = rec.get("candidates") or []
    label_of = {}
    src = []
    routelab = {}
    nxt = 1
    for ci, c in enumerate(cands, 1):
        if c.get("kind") == "implicit":
            continue
        seq = ["M0"]
        for q, code in _parse_called(c.get("callPathSerialized")):
            if q not in label_of:
                label_of[q] = f"M{nxt}"
                nxt += 1
                src.append((label_of[q], q, code))
            seq.append(label_of[q])
        routelab[ci] = " -> ".join(seq)
    return src, routelab


def build_user_prompt(rec, mode):
    sb = [QUESTION, "\n"]
    name = (rec.get("example") or {}).get("methodName") or "target"
    cands = rec.get("candidates") or []
    strip_api = (mode == "no-api-knowledge")

    sb.append("<source methods>\n")
    sb.append(f"// [M0] {name}\n```\n{rec.get('rootMethodCode','')}\n```\n")
    routelab = {}
    if strip_api:
        # no-api-knowledge: full call path retained — emit deduped callee sources too
        src, routelab = _callpath_labels(rec)
        for lab, q, code in src:
            sb.append(f"// [{lab}] {q}\n```\n{code}\n```\n")
    sb.append("<end>\n\n")

    sb.append("<candidates>\n")
    # no-call-path     : candidate exception info kept, call-path labels dropped.
    # no-api-knowledge : path labels kept, API javadoc + throw conditions dropped.
    for ci, c in enumerate(cands, 1):
        kind = c.get("kind")
        via = f" reached via [{routelab.get(ci, 'M0')}]" if strip_api else ""
        if kind == "api":
            sb.append(f"Candidate {ci}: API call `{c.get('simpleName')}`{via}.\n")
            if not strip_api:
                jd = _collapse(c.get("javadoc"))
                if jd:
                    if len(jd) > 800:
                        jd = jd[:800] + " ...[truncated]"
                    sb.append(f"  api javadoc: {jd}\n")
            sb.append("  documented runtime exceptions (score each by type):\n")
            for e in c.get("apiExceptionScores") or []:
                line = f"   - {e.get('name')}"
                if not strip_api:
                    cond = (e.get("condition") or "").strip()
                    if cond:
                        if len(cond) > 200:
                            cond = cond[:200] + "..."
                        line += f" — throw condition: {cond}"
                sb.append(line + "\n")
        elif kind == "throw":
            sb.append(f"Candidate {ci}: throw statement(s) in `{c.get('simpleName')}`{via} (score each by type):\n")
            for t in c.get("throwStatements") or []:
                sb.append(f"   - {t.get('exceptionType')}  ({_collapse(t.get('text'))})\n")
        elif kind == "implicit":
            typ = (c.get("apiExceptionScores") or [{}])[0].get("name") or c.get("simpleName")
            sb.append(f"Candidate {ci}: implicit runtime exception {typ} (score by type).\n")
    sb.append("<end>\n\n")
    return "".join(sb)


def call_llm(user):
    body = json.dumps({
        "model": MODEL,
        "messages": [{"role": "system", "content": SYSTEM}, {"role": "user", "content": user}],
        "temperature": 0, "response_format": {"type": "json_object"},
    }).encode()
    last = None
    for attempt in range(4):
        try:
            req = urllib.request.Request(API_URL, data=body,
                headers={"Authorization": f"Bearer {API_KEY}", "Content-Type": "application/json"})
            r = json.load(urllib.request.urlopen(req, timeout=180))
            return r["choices"][0]["message"]["content"] or ""
        except Exception as e:
            last = e
            time.sleep(1.5 * (attempt + 1))
    raise last


def parse_response(raw):
    body = raw.strip()
    if body.startswith("```"):
        body = re.sub(r"^```(?:json)?\s*|\s*```$", "", body, flags=re.DOTALL)
    enum, discovered = {}, []
    try:
        obj = json.loads(body)
    except Exception:
        return enum, discovered
    for el in (obj.get("candidates") or []):
        if not isinstance(el, dict):
            continue
        if isinstance(el.get("candidate"), (int, float)) and "scores" in el:
            by_type = {}
            for se in el.get("scores") or []:
                if isinstance(se, dict) and se.get("type") is not None:
                    try:
                        by_type[_simple(se["type"])] = float(se.get("score", 0.0))
                    except Exception:
                        pass
            enum[int(el["candidate"])] = by_type
        elif el.get("type"):
            try:
                discovered.append((el["type"].strip(), float(el.get("score", 0.0)), el.get("remark", "")))
            except Exception:
                pass
    return enum, discovered


def make_implicit(typ, sc, reason):
    return {"kind": "implicit", "simpleName": typ, "routeDepth": 0, "callPathSerialized": "",
            "hasCallPath": False, "description": reason,
            "apiExceptionScores": [{"name": typ, "score": sc, "condition": ""}]}


def apply(rec, raw, mode):
    enum, discovered = parse_response(raw)
    cands = rec.get("candidates") or []
    strip_api = (mode == "no-api-knowledge")
    # Both modes: re-score enumerated candidates by type and refresh implicits.
    #   no-call-path     : strip call-path fields so the analyzer loses them too.
    #   no-api-knowledge : keep the candidate set and call-path fields, but clear
    #                      javadoc/condition so the analyzer is blinded to the API KB.
    kept = []
    for i, c in enumerate(cands, 1):
        if c.get("kind") == "implicit":
            continue
        by_type = enum.get(i, {})
        for e in c.get("apiExceptionScores") or []:
            if _simple(e.get("name")) in by_type:
                e["score"] = by_type[_simple(e["name"])]
        for t in c.get("throwStatements") or []:
            if _simple(t.get("exceptionType")) in by_type:
                t["score"] = by_type[_simple(t["exceptionType"])]
        if strip_api:
            if c.get("kind") == "api":
                c["javadoc"] = ""
                for e in c.get("apiExceptionScores") or []:
                    e["condition"] = ""
        else:
            c["callPathSerialized"] = ""
            c["hasCallPath"] = False
            c["routeDepth"] = 0
            c["parentSimpleName"] = None
        kept.append(c)
    for t, s, r in discovered:
        kept.append(make_implicit(t, s, r))
    rec["candidates"] = kept
    rec["ablationNote"] = (
        "w/o API knowledge STRICT: triage re-scored with the full call path retained "
        "but API javadoc and throw conditions removed; javadoc/condition fields cleared "
        "so the analyzer loses the API KB too."
        if strip_api else
        "w/o call-path STRICT: triage re-scored with target-method source only, no call "
        "path; call-path fields stripped.")


def key(rec):
    ex = rec.get("example") or {}
    return (ex.get("repo_id"), ex.get("patch"), ex.get("methodName") or ex.get("method_name"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", required=True, choices=["no-call-path", "no-api-knowledge"])
    ap.add_argument("--input", required=True)
    ap.add_argument("--output", required=True)
    ap.add_argument("--workers", type=int, default=int(os.environ.get("WORKERS", "5")))
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--resume", action="store_true")
    args = ap.parse_args()

    if not API_URL or not API_KEY or not MODEL:
        ap.error("LLM_API_URL, LLM_API_KEY, and LLM_MODEL_NAME are required")

    data = json.loads(Path(args.input).read_text(encoding="utf-8"))
    done = {}
    if args.resume and Path(args.output).exists():
        try:
            for r in json.loads(Path(args.output).read_text(encoding="utf-8")):
                if r.get("_retriaged"):
                    done[key(r)] = r
        except Exception:
            pass
    todo = [r for r in data if key(r) not in done]
    if args.limit:
        todo = todo[:args.limit]
    print(f"mode={args.mode} input {len(data)} done {len(done)} todo {len(todo)} workers={args.workers}", flush=True)

    lock = threading.Lock()
    n = [0]
    out_by_key = dict(done)
    errors = []

    def work(rec):
        try:
            apply(rec, call_llm(build_user_prompt(rec, args.mode)), args.mode)
        except Exception as e:
            with lock:
                errors.append((key(rec), str(e)))
            print(f"  FAIL {key(rec)}: {e}", flush=True)
            return
        rec["_retriaged"] = True
        with lock:
            out_by_key[key(rec)] = rec
            n[0] += 1
            if n[0] % 25 == 0:
                merged = [out_by_key.get(key(r), r) for r in data]
                Path(args.output).write_text(json.dumps(merged, ensure_ascii=False, indent=2), encoding="utf-8")
                print(f"  [{n[0]}/{len(todo)}] checkpoint", flush=True)

    with ThreadPoolExecutor(max_workers=args.workers) as ex:
        list(ex.map(work, todo))
    merged = [out_by_key.get(key(r), r) for r in data]
    Path(args.output).write_text(json.dumps(merged, ensure_ascii=False, indent=2), encoding="utf-8")
    if errors:
        print(f"failed records: {len(errors)}; rerun with --resume after fixing the API error", flush=True)
        raise SystemExit(1)
    print(f"done. wrote {len(merged)} -> {args.output}", flush=True)


if __name__ == "__main__":
    main()
