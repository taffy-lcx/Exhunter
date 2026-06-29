#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BASE_INTERMEDIATE="${BASE_INTERMEDIATE:-$ROOT/data/deepseek-v4-flash-3stage/intermediate.json}"
OUT_DIR="$ROOT/data/wo-triage"

mkdir -p "$OUT_DIR"
python3 "$SCRIPT_DIR/common/prepare_intermediate.py" \
  --mode no-triage \
  --input "$BASE_INTERMEDIATE" \
  --output "$OUT_DIR/intermediate.json"

bash "$SCRIPT_DIR/common/run_analyzer_repairer.sh" wo-triage "$OUT_DIR/intermediate.json"
