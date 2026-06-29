#!/usr/bin/env python3
"""phase_timing_stats.py — per-phase execution-time statistics from phase_timing.jsonl.

Each line of phase_timing.jsonl: {"sample_key","phase","ms"}
  phase in {static, triage, main_q}
    static = static analysis (ProjectHandler checkout + JDT parse + candidate enumeration)
    triage = exception-throw check (sum of all triage calls for one sample)
    main_q = exception-catch decision (main_question)

Aggregated by sample_key -> one row per sample (static_s, triage_s, main_q_s, total_s),
then mean / median / min / max / Q1 / Q3 per column (unit: seconds).

Usage:
  python3 data/phase_timing_stats.py --mark deepseek-v4-flash-3stage
  python3 data/phase_timing_stats.py --timing data/<mark>/phase_timing.jsonl --md out.md
"""
import argparse
import json
import statistics
from collections import defaultdict


def q1(xs):
    return statistics.quantiles(xs, n=4)[0] if len(xs) >= 2 else (xs[0] if xs else 0)


def q3(xs):
    return statistics.quantiles(xs, n=4)[2] if len(xs) >= 2 else (xs[0] if xs else 0)


def fmt(v):
    """seconds; < 0.5 shows ~0, otherwise rounded integer."""
    return "~0" if v < 0.5 else f"{round(v):d}"


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--mark", default="", help="EXPERIMENT_MARK (locates the phase_timing file)")
    ap.add_argument("--timing", default="", help="explicit timing jsonl path (overrides --mark)")
    ap.add_argument("--md", default="", help="write markdown to this path")
    args = ap.parse_args()

    path = args.timing or f"data/{args.mark}/phase_timing.jsonl"

    # sample_key -> {phase -> accumulated ms}
    agg = defaultdict(lambda: defaultdict(float))
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                r = json.loads(line)
            except json.JSONDecodeError:
                continue
            agg[r["sample_key"]][r["phase"]] += r.get("ms", 0)

    # 4 columns per sample (seconds). Only samples with all three phases count toward total.
    static_l, triage_l, mainq_l, total_l = [], [], [], []
    n_full = 0
    for sk, ph in agg.items():
        s = ph.get("static", 0) / 1000
        t = ph.get("triage", 0) / 1000
        m = ph.get("main_q", 0) / 1000
        has_static = "static" in ph
        has_triage = "triage" in ph
        has_mainq = "main_q" in ph
        if has_static:
            static_l.append(s)
        if has_triage:
            triage_l.append(t)
        if has_mainq:
            mainq_l.append(m)
        if has_static and has_triage and has_mainq:
            total_l.append(s + t + m)
            n_full += 1

    def stats_row(name, xs):
        if not xs:
            return f"| {name} | - | - | - | - | - | - |"
        return (f"| {name} "
                f"| {fmt(statistics.mean(xs))} "
                f"| {fmt(statistics.median(xs))} "
                f"| {fmt(min(xs))} "
                f"| {fmt(max(xs))} "
                f"| {fmt(q1(xs))} "
                f"| {fmt(q3(xs))} |")

    lines = []
    lines.append(f"# Per-phase execution time (mark={args.mark or path})\n")
    lines.append(f"samples: static={len(static_l)}, triage={len(triage_l)}, "
                 f"main_q={len(mainq_l)}, all-three (counted in total)={n_full}\n")
    lines.append("unit: seconds\n")
    lines.append("| phase | mean | median | min | max | Q1 | Q3 |")
    lines.append("|---|---:|---:|---:|---:|---:|---:|")
    lines.append(stats_row("static analysis", static_l))
    lines.append(stats_row("exception-throw check", triage_l))
    lines.append(stats_row("exception-catch decision", mainq_l))
    lines.append(stats_row("total", total_l))

    out = "\n".join(lines) + "\n"
    print(out)
    if args.md:
        with open(args.md, "w") as f:
            f.write(out)
        print(f"markdown written to: {args.md}")


if __name__ == "__main__":
    main()
