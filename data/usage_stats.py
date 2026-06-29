#!/usr/bin/env python3
"""Summarize data/llm_usage.jsonl by stage: tokens / time / cost.

The log is written by LLMApiCaller.appendUsageLog, one JSON object per line:
  {ts, stage, method, model, duration_ms, prompt_tokens, completion_tokens, total_tokens}

stage values (3-stage pipeline): method_triage / analyzer / repairer.

Reference prices (DeepSeek list price, standard tier, USD per 1M tokens):
  - input (cache miss): 0.27
  - input (cache hit):  0.07   (cached_tokens not logged; estimated at 0% / 80%)
  - output:             1.10

Usage:
  python data/usage_stats.py                          # default log: data/llm_usage.jsonl
  python data/usage_stats.py --log path/to/log.jsonl
  python data/usage_stats.py --since 2026-06-02       # only entries on/after this date
  python data/usage_stats.py --md data/usage.md       # also write markdown
"""
import argparse
import json
import sys
from collections import defaultdict
from datetime import datetime
from pathlib import Path

# DeepSeek list price (USD per 1M tokens)
PRICE_IN_MISS = 0.27
PRICE_IN_HIT  = 0.07
PRICE_OUT     = 1.10


def parse_args():
    ap = argparse.ArgumentParser()
    ap.add_argument("--log", default="data/llm_usage.jsonl")
    ap.add_argument("--since", default="", help="only entries with ts >= this date (YYYY-MM-DD)")
    ap.add_argument("--md", default="", help="also write markdown")
    return ap.parse_args()


def load_lines(path, since_str):
    since = None
    if since_str:
        since = datetime.fromisoformat(since_str).timestamp()
    rows = []
    if not Path(path).exists():
        raise SystemExit(f"log file not found: {path} — has the run made any LLM calls yet?")
    for line in open(path):
        line = line.strip()
        if not line: continue
        try:
            r = json.loads(line)
        except Exception:
            continue
        if since is not None:
            try:
                ts = datetime.fromisoformat(r["ts"].rstrip("Z")).timestamp()
                if ts < since: continue
            except Exception:
                pass
        rows.append(r)
    return rows


def aggregate(rows):
    """Aggregate by stage."""
    agg = defaultdict(lambda: dict(n=0, in_tok=0, out_tok=0, total_tok=0, dur_ms=0))
    for r in rows:
        s = agg[r.get("stage", "unknown")]
        s["n"]        += 1
        s["in_tok"]   += int(r.get("prompt_tokens", 0))
        s["out_tok"]  += int(r.get("completion_tokens", 0))
        s["total_tok"] += int(r.get("total_tokens", 0))
        s["dur_ms"]   += int(r.get("duration_ms", 0))
    return agg


def cost_of(in_tok, out_tok, hit_rate=0.0):
    """USD; hit_rate = fraction of input tokens billed at the cache-hit price (0.0 = all miss)."""
    in_miss = in_tok * (1 - hit_rate)
    in_hit  = in_tok * hit_rate
    return (in_miss * PRICE_IN_MISS + in_hit * PRICE_IN_HIT + out_tok * PRICE_OUT) / 1_000_000


def render(agg, total_lines):
    lines = []
    lines.append("# LLM token / time / cost report\n")
    lines.append(f"total records: {total_lines}")
    lines.append("")
    stages_order = ["method_triage", "analyzer", "repairer"]
    stages = [s for s in stages_order if s in agg] + [s for s in agg if s not in stages_order]
    lines.append("## Per-stage summary\n")
    lines.append(f"{'stage':14s} {'n':>6s} {'in_tok':>11s} {'out_tok':>10s} {'total':>11s} "
                 f"{'avg_in':>7s} {'avg_out':>8s} {'dur_min':>8s} {'cost@0%':>9s} {'cost@80%':>10s}")
    lines.append("-" * 110)
    grand = dict(n=0, in_tok=0, out_tok=0, total_tok=0, dur_ms=0)
    for st in stages:
        s = agg[st]
        avg_in = s["in_tok"] / max(s["n"], 1)
        avg_out = s["out_tok"] / max(s["n"], 1)
        dur_min = s["dur_ms"] / 60_000
        c0  = cost_of(s["in_tok"], s["out_tok"], hit_rate=0.0)
        c80 = cost_of(s["in_tok"], s["out_tok"], hit_rate=0.8)
        lines.append(f"{st:14s} {s['n']:>6d} {s['in_tok']:>11d} {s['out_tok']:>10d} {s['total_tok']:>11d} "
                     f"{avg_in:>7.0f} {avg_out:>8.0f} {dur_min:>8.1f} ${c0:>8.3f} ${c80:>9.3f}")
        for k in grand: grand[k] += s[k]
    lines.append("-" * 110)
    avg_in = grand["in_tok"] / max(grand["n"], 1)
    avg_out = grand["out_tok"] / max(grand["n"], 1)
    dur_min = grand["dur_ms"] / 60_000
    c0  = cost_of(grand["in_tok"], grand["out_tok"], hit_rate=0.0)
    c80 = cost_of(grand["in_tok"], grand["out_tok"], hit_rate=0.8)
    lines.append(f"{'TOTAL':14s} {grand['n']:>6d} {grand['in_tok']:>11d} {grand['out_tok']:>10d} {grand['total_tok']:>11d} "
                 f"{avg_in:>7.0f} {avg_out:>8.0f} {dur_min:>8.1f} ${c0:>8.3f} ${c80:>9.3f}")
    lines.append("")
    lines.append("Notes:")
    lines.append("- cost@0%  = all input billed at the miss price ($0.27/M); upper-bound estimate.")
    lines.append("- cost@80% = 80% of input billed at the cache-hit price ($0.07/M).")
    lines.append("- output billed at $1.10/M (no discount).")
    lines.append("- dur_min = cumulative HTTP call time; excludes local cache hits.")
    return "\n".join(lines) + "\n"


def main():
    args = parse_args()
    rows = load_lines(args.log, args.since)
    if not rows:
        print("no records")
        return
    agg = aggregate(rows)
    txt = render(agg, len(rows))
    print(txt)
    if args.md:
        Path(args.md).write_text(txt, encoding="utf-8")
        print(f"\nmarkdown written to: {args.md}")


if __name__ == "__main__":
    main()
