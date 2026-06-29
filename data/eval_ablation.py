#!/usr/bin/env python3
"""Ablation evaluation: score every ablation variant against the full 3-stage
method on Detection / Catch-type / Repair-localization.

Difference from eval_all.py: there is NO common-subset (overlap) section. Every
variant here is the SAME pipeline with one component removed, so intersecting
true-positive sets across "methods" is not meaningful. Each variant is scored on
its OWN true-positive subset (label=1 and the variant flagged it as needing a
fix, i.e. needLLM == 'yes'). The metric math is imported from eval_all so the two
scripts can never drift.

Variants (all deepseek-v4-flash, threshold 0.2):
  full   triage -> analyzer -> repairer            (reference)
  a1     drop static/triage/analyzer, one end-to-end LLM call (zero-shot)
  a3     drop the API exception knowledge-base lookup results
  a4     drop triage (all static candidates go straight to the analyzer)
  a5     drop analyzer (triage survivors go straight to the repairer)
  a6     drop call path (triage/analyzer inputs carry no call-path context)

Usage:
  python data/eval_ablation.py                 # report on whatever is finished
  python data/eval_ablation.py --md out.md     # also write Markdown
  python data/eval_ablation.py --full <path>   # override the reference output
"""
import argparse
import json
from pathlib import Path

from eval_all import eval_detection, eval_repair, _sample_key

HERE = Path(__file__).resolve().parent

# variant -> output path. Missing / empty files are skipped with a note.
ABLATIONS = [
    ("full",                   HERE / "deepseek-v4-flash-3stage/output_0.2.json"),
    ("w/o triage",             HERE / "wo-triage/output_0.2.json"),
    ("w/o analyzer",           HERE / "wo-analyzer/output_0.2.json"),
    ("w/o call-path",          HERE / "wo-call-path/output_0.2.json"),
    ("w/o API knowledge",      HERE / "wo-api-knowledge/output_0.2.json"),
]
# direct-prompt (former zero-shot) is now a baseline, evaluated in eval_all.py.


def tp_keys(data):
    """Ablation TP subset: label=1 and the variant flagged need (needLLM=='yes')."""
    return {_sample_key(x) for x in data
            if x.get("label") == 1 and (x.get("needLLM") or "").strip().lower() == "yes"}


def load(p):
    if not p or not Path(p).exists():
        return None
    try:
        d = json.load(open(p))
    except Exception:
        return None
    return d if d else None


def has_repair_fields(data):
    """True if at least one record carries the fingerprint fields eval_repair needs."""
    return any(x.get("afterTargetTryBodyFps") is not None
               or x.get("resultRawTryBodyFps") is not None for x in data)


def has_type_fields(data):
    return any(x.get("resultExceptionTypesAll") is not None
               or x.get("exceptionTypesAll") is not None for x in data)


# `w/o analyzer` is reported on DETECTION only. The analyzer is the only stage that
# produces the repair plan — the other variants keep the analyzer and merely change its
# inputs, whereas removing it leaves the repairer with no plan and collapses the need
# decision to "every triage survivor". Repair quality would therefore be measured on a
# different, much noisier TP subset (not a like-for-like comparison), so its repair
# columns are left blank, consistent with excluding it from ACRS.
REPAIR_EXCLUDE = {"w/o analyzer"}


def render(rows, full_f1):
    """rows: list of dict(name, note, det, rep, tp_n, repair_ok, type_ok)."""
    o = []
    o.append("# Ablation evaluation\n")
    o.append("Each variant is the full pipeline with one component removed. Detection is over all "
             "samples; Catch-type and Repair-loc are on each variant's own true-positive subset "
             "(TP_n = label=1 ∧ needLLM='yes'). `w/o analyzer` is reported on detection only: it is "
             "the one stage that produces the repair plan, and removing it collapses the need "
             "decision to 'every triage survivor' — its effect is a precision filter (recall rises, "
             "precision/accuracy fall), so repair quality is not a like-for-like comparison.\n")
    o.append("- **Detection** (micro): needLLM vs label — P / Recall / F1 / Acc.")
    o.append("- **Catch-type** (macro, TP subset): caught exception types vs developer's — P / Recall / F1.")
    o.append("- **Repair-loc** (macro, TP subset): try-body fingerprint overlap vs developer's — "
             "P / Coverage / F1 / IoU.\n")

    o.append("| variant | Det-P | Det-R | Det-F1 | Det-Acc | TP_n | "
             "Catch-P | Catch-R | Catch-F1 | Rep-P | Rep-Cov | Rep-F1 | Rep-IoU |")
    o.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    ordered = sorted(rows, key=lambda r: (r["name"] != "full", -r["det"]["F1"]))
    for r in ordered:
        d = r["det"]; m = r["rep"]
        det = f"{d['P']:.4f} | {d['R']:.4f} | {d['F1']:.4f} | {d['Acc']:.4f}"
        if r["name"] in REPAIR_EXCLUDE or not r["repair_ok"]:
            rep = "— | — | — | — | — | — | —"
        else:
            rep = (f"{m['P_t']:.4f} | {m['R_t']:.4f} | {m['F1_t']:.4f} | "
                   f"{m['raw_prec']:.4f} | {m['raw_cov']:.4f} | {m['raw_f1']:.4f} | {m['raw_iou']:.4f}")
        o.append(f"| `{r['name']}` | {det} | {r['tp_n']} | {rep} |")
    return "\n".join(o) + "\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--full", default="", help="override the full reference output path")
    ap.add_argument("--md", default="")
    args = ap.parse_args()

    rows = []
    pending = []
    full_f1 = 0.0
    for name, path in ABLATIONS:
        note = ""
        if name == "full" and args.full:
            path = Path(args.full)
        data = load(path)
        if data is None:
            pending.append(name)
            continue
        det = eval_detection(data)
        keys = tp_keys(data)
        rep = eval_repair(data, keys)
        repair_ok = has_repair_fields(data)
        type_ok = has_type_fields(data)
        rows.append(dict(name=name, note=note, det=det, rep=rep,
                         tp_n=len(keys), repair_ok=repair_ok, type_ok=type_ok))
        if name == "full":
            full_f1 = det["F1"]

    if not any(r["name"] == "full" for r in rows):
        print("WARNING: no full reference available; ΔF1 will be against 0.")
    txt = render(rows, full_f1)
    print(txt)
    if pending:
        print("pending (no output yet): " + ", ".join(pending))
    if args.md:
        Path(args.md).write_text(txt, encoding="utf-8")
        print(f"markdown written: {args.md}")


if __name__ == "__main__":
    main()
