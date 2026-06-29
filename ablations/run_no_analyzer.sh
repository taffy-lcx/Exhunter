#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BASE_INTERMEDIATE="${BASE_INTERMEDIATE:-$ROOT/data/deepseek-v4-flash-3stage/intermediate.json}"
THRESHOLD="${THRESHOLD:-0.2}"
OUT_DIR="$ROOT/data/wo-analyzer"

mkdir -p "$OUT_DIR"
python3 "$SCRIPT_DIR/common/prepare_intermediate.py" \
  --mode copy \
  --input "$BASE_INTERMEDIATE" \
  --output "$OUT_DIR/intermediate.json"
python3 "$SCRIPT_DIR/common/detection_output.py" \
  --mode intermediate \
  --input "$OUT_DIR/intermediate.json" \
  --output "$OUT_DIR/output_${THRESHOLD}.json" \
  --threshold "$THRESHOLD"
