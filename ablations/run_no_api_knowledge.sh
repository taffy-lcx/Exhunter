#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BASE_INTERMEDIATE="${BASE_INTERMEDIATE:-$ROOT/data/deepseek-v4-flash-3stage/intermediate.json}"
TRIAGE_WORKERS="${TRIAGE_WORKERS:-5}"
OUT_DIR="$ROOT/data/wo-api-knowledge"

mkdir -p "$OUT_DIR"
python3 "$SCRIPT_DIR/common/retriage.py" \
  --mode no-api-knowledge \
  --input "$BASE_INTERMEDIATE" \
  --output "$OUT_DIR/intermediate.json" \
  --workers "$TRIAGE_WORKERS"

bash "$SCRIPT_DIR/common/run_analyzer_repairer.sh" wo-api-knowledge "$OUT_DIR/intermediate.json"
