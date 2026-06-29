#!/usr/bin/env python3
import argparse
import json
from pathlib import Path


def has_survivor(candidate, threshold):
    for item in candidate.get("apiExceptionScores") or []:
        if float(item.get("score") or 0.0) >= threshold:
            return True
    for item in candidate.get("throwStatements") or []:
        if float(item.get("score") or 0.0) >= threshold:
            return True
    return False


def method_code_from_example(ex, fallback=""):
    return ex.get("methodBefore") or fallback or ""


def from_intermediate(records, threshold):
    out = []
    for rec in records:
        ex = dict(rec.get("example") or {})
        method = method_code_from_example(ex, rec.get("rootMethodCode") or "")
        if rec.get("staticError"):
            if ex.get("label") == 0:
                ex["label"] = -1
            ex["needLLM"] = "error"
        else:
            ex["needLLM"] = "yes" if any(has_survivor(c, threshold) for c in rec.get("candidates") or []) else "no"
        ex["methodResult"] = method
        ex["changed"] = 0
        out.append(ex)
    return out


def from_analyzed(records):
    out = []
    for rec in records:
        ex = dict(rec.get("example") or {})
        method = method_code_from_example(ex, rec.get("methodCode") or "")
        need = str(rec.get("need") or "no").strip().lower()
        ex["needLLM"] = "yes" if "yes" in need else "no"
        if rec.get("staticError"):
            if ex.get("label") == 0:
                ex["label"] = -1
            ex["needLLM"] = "error"
        ex["methodResult"] = method
        ex["changed"] = 0
        ex["survivingCandidates"] = rec.get("survivingCandidates", 0)
        out.append(ex)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", required=True, choices=["intermediate", "analyzed"])
    ap.add_argument("--input", required=True)
    ap.add_argument("--output", required=True)
    ap.add_argument("--threshold", type=float, default=0.2)
    args = ap.parse_args()

    records = json.loads(Path(args.input).read_text(encoding="utf-8"))
    if args.mode == "intermediate":
        out = from_intermediate(records, args.threshold)
    else:
        out = from_analyzed(records)
    Path(args.output).write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    yes = sum(1 for x in out if x.get("needLLM") == "yes")
    print(f"wrote {len(out)} records -> {args.output} (need=yes: {yes})")


if __name__ == "__main__":
    main()
