#!/usr/bin/env python3
import argparse
from pathlib import Path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--mark", required=True)
    ap.add_argument("--target", required=True)
    ap.add_argument("--threshold", default="0.2")
    args = ap.parse_args()

    base = Path(args.base)
    props = {}
    order = []
    for line in base.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.lstrip().startswith("#") or "=" not in line:
            order.append((None, line))
            continue
        k, v = line.split("=", 1)
        key = k.strip()
        props[key] = v.strip()
        order.append((key, None))

    updates = {
        "EXPERIMENT_MARK": args.mark,
        "EXPERIMENT_TARGET": args.target,
        "TRIAGE_API_THRESHOLD": args.threshold,
        "TRIAGE_THROW_THRESHOLD": args.threshold,
    }
    props.update(updates)

    seen = set()
    out_lines = []
    for key, raw in order:
        if key is None:
            out_lines.append(raw)
        elif key in props:
            out_lines.append(f"{key}={props[key]}")
            seen.add(key)
    for key, value in updates.items():
        if key not in seen:
            out_lines.append(f"{key}={value}")

    Path(args.out).write_text("\n".join(out_lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
