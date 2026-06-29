#!/usr/bin/env python3
import argparse
import json
from pathlib import Path


def set_scores(candidate, value):
    for item in candidate.get("apiExceptionScores") or []:
        item["score"] = value
    for item in candidate.get("throwStatements") or []:
        item["score"] = value


def scrub_triage_text(candidate):
    candidate["description"] = None
    for item in candidate.get("throwStatements") or []:
        item["description"] = None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", required=True,
                    choices=["copy", "no-triage", "no-call-path-fast"])
    ap.add_argument("--input", required=True)
    ap.add_argument("--output", required=True)
    args = ap.parse_args()

    data = json.loads(Path(args.input).read_text(encoding="utf-8"))

    for rec in data:
        candidates = rec.get("candidates") or []

        if args.mode == "copy":
            continue

        if args.mode == "no-triage":
            kept = []
            for c in candidates:
                if c.get("kind") == "implicit":
                    continue
                set_scores(c, 1.0)
                scrub_triage_text(c)
                kept.append(c)
            rec["candidates"] = kept
            rec["ablationNote"] = "A4 no-triage fast: all static API/local-throw candidates kept with score=1.0; triage-discovered implicit candidates removed."

        elif args.mode == "no-call-path-fast":
            for c in candidates:
                c["callPathSerialized"] = ""
                c["hasCallPath"] = False
                c["routeDepth"] = 0
                c["parentSimpleName"] = None
            rec["ablationNote"] = "A6 no-call-path fast: call-path fields removed after triage. This is not strict because triage scores were already produced with call-path context."

    Path(args.output).write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
