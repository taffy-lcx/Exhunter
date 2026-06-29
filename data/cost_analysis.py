#!/usr/bin/env python3
"""cost_analysis.py — cost + time analysis from data/llm_usage.jsonl.

Each line of llm_usage.jsonl is one LLM call:
  {"ts","stage","method","model","duration_ms","prompt_tokens","completion_tokens","total_tokens"}

Metric definitions:
  calls       = number of records matching the filter (all stages count)
  runs        = number of stage=main_question records (legacy 2-stage repair calls)
  input tok   = sum of prompt_tokens
  output tok  = sum of completion_tokens
  input cost  = input tok (in thousands) x in-price
  output cost = output tok (in thousands) x out-price
  total cost  = input cost + output cost

Time:
  total time  = sum of duration_ms (successful calls only)
  avg call ms = total time / calls

The jsonl is append-only and shared (multiple models/runs mixed), so slice it
with --model + --since/--until.

Usage:
  python3 data/cost_analysis.py --model qwen3-max --in-price 0.0024 --out-price 0.0096
  python3 data/cost_analysis.py --model gpt-4o-mini --since 2026-06-09 --until 2026-06-11 \\
      --in-price 0.00108 --out-price 0.00432 --md data/cost_qwen3max.md
  # prices are per 1000 tokens, in whatever currency you pass.
"""
import argparse
import json
from collections import defaultdict


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--log", default="data/llm_usage.jsonl")
    ap.add_argument("--model", default="", help="only this model (substring match); empty = all")
    ap.add_argument("--since", default="", help="ts >= this value (ISO prefix, e.g. 2026-06-11)")
    ap.add_argument("--until", default="", help="ts <= this value (ISO prefix)")
    ap.add_argument("--in-price", type=float, default=0.0024, help="input price per 1000 tokens")
    ap.add_argument("--out-price", type=float, default=0.0096, help="output price per 1000 tokens")
    ap.add_argument("--md", default="", help="write a markdown report to this path")
    args = ap.parse_args()

    calls = 0
    runs = 0
    in_tok = 0
    out_tok = 0
    dur_ms = 0
    by_stage = defaultdict(lambda: {"calls": 0, "in": 0, "out": 0, "dur": 0})

    with open(args.log) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                r = json.loads(line)
            except json.JSONDecodeError:
                continue
            if args.model and args.model not in (r.get("model") or ""):
                continue
            ts = r.get("ts", "")
            if args.since and ts < args.since:
                continue
            if args.until and ts[:len(args.until)] > args.until:
                continue

            calls += 1
            stage = r.get("stage", "?")
            pt = r.get("prompt_tokens", 0) or 0
            ct = r.get("completion_tokens", 0) or 0
            dm = r.get("duration_ms", 0) or 0
            in_tok += pt
            out_tok += ct
            dur_ms += dm
            if stage == "main_question":
                runs += 1
            s = by_stage[stage]
            s["calls"] += 1; s["in"] += pt; s["out"] += ct; s["dur"] += dm

    in_k = in_tok / 1000
    out_k = out_tok / 1000
    in_total = in_k * args.in_price
    out_total = out_k * args.out_price
    total = in_total + out_total
    avg_run = total / runs if runs else 0
    avg_call_ms = dur_ms / calls if calls else 0

    lines = []
    lines.append(f"# Cost / time analysis — model={args.model or 'all'}"
                 + (f" since={args.since}" if args.since else "")
                 + (f" until={args.until}" if args.until else ""))
    lines.append("")
    lines.append("| metric | value |")
    lines.append("|---|---:|")
    lines.append(f"| runs (main_question) | {runs} |")
    lines.append(f"| calls (all LLM calls) | {calls} |")
    lines.append(f"| input tokens | {in_k:.0f} k |")
    lines.append(f"| output tokens | {out_k:.0f} k |")
    lines.append(f"| input price | {args.in_price} per 1k |")
    lines.append(f"| output price | {args.out_price} per 1k |")
    lines.append(f"| input cost | {in_total:.2f} |")
    lines.append(f"| output cost | {out_total:.2f} |")
    lines.append(f"| **total cost** | **{total:.2f}** |")
    lines.append(f"| avg cost per run | {avg_run:.4f} |")
    lines.append(f"| total time | {dur_ms/1000:.0f} s ({dur_ms/3600000:.2f} h) |")
    lines.append(f"| avg time per call | {avg_call_ms:.0f} ms |")
    lines.append("")
    lines.append("## Breakdown by stage")
    lines.append("")
    lines.append("| stage | calls | input(k) | output(k) | time(h) | avg(ms) |")
    lines.append("|---|---:|---:|---:|---:|---:|")
    for st, s in sorted(by_stage.items(), key=lambda kv: -kv[1]["calls"]):
        avg = s["dur"] / s["calls"] if s["calls"] else 0
        lines.append(f"| {st} | {s['calls']} | {s['in']/1000:.0f} | {s['out']/1000:.0f} "
                     f"| {s['dur']/3600000:.2f} | {avg:.0f} |")

    out = "\n".join(lines) + "\n"
    print(out)
    if args.md:
        with open(args.md, "w") as f:
            f.write(out)
        print(f"markdown written to: {args.md}")


if __name__ == "__main__":
    main()
