#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 2 ]; then
  echo "usage: $0 <mark> <intermediate.json>" >&2
  exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
MARK="$1"
INTERMEDIATE="$2"
THRESHOLD="${THRESHOLD:-0.2}"
ANALYZER_WORKERS="${ANALYZER_WORKERS:-3}"
JAVA_BIN="${JAVA_BIN:-java}"
BASE_CONFIG="${BASE_CONFIG:-$ROOT/java-scanner/config.properties}"
OUT_DIR="$ROOT/data/$MARK"
CONFIG="$OUT_DIR/config.properties"
CLASSPATH='target/classes:target/dependency/*'
TMP_RUN="$(mktemp -d "${TMPDIR:-/tmp}/${MARK}.XXXXXX")"

cleanup() {
  if [ "${KEEP_TMP:-0}" != "1" ]; then
    rm -rf "$TMP_RUN"
  fi
}
trap cleanup EXIT

if [ ! -s "$INTERMEDIATE" ]; then
  echo "missing intermediate: $INTERMEDIATE" >&2
  exit 1
fi
if [ ! -f "$BASE_CONFIG" ]; then
  echo "missing config: $BASE_CONFIG" >&2
  exit 1
fi

mkdir -p "$OUT_DIR"
python3 "$SCRIPT_DIR/make_config.py" \
  --base "$BASE_CONFIG" \
  --out "$CONFIG" \
  --mark "$MARK" \
  --target "$OUT_DIR/output_${THRESHOLD}.json" \
  --threshold "$THRESHOLD"

python3 - "$INTERMEDIATE" "$ANALYZER_WORKERS" "$TMP_RUN" <<'PY'
import json
import sys
from pathlib import Path

records = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
workers = int(sys.argv[2])
tmp = Path(sys.argv[3])
for index in range(workers):
    shard = records[index::workers]
    (tmp / f"intermediate-{index}.json").write_text(
        json.dumps(shard, ensure_ascii=False), encoding="utf-8")
PY

rm -f "$OUT_DIR/analyzed_${THRESHOLD}.json" "$OUT_DIR/output_${THRESHOLD}.json"

cd "$ROOT/java-scanner"
pids=()
for index in $(seq 0 $((ANALYZER_WORKERS - 1))); do
  CONFIG_FILE="$CONFIG" "$JAVA_BIN" -cp "$CLASSPATH" org.ExperimentExecutor.RunAnalyzer \
    "$TMP_RUN/intermediate-$index.json" "$TMP_RUN/analyzed-$index.json" \
    > "$TMP_RUN/analyzer-$index.log" 2>&1 &
  pids+=("$!")
done

failed=0
for pid in "${pids[@]}"; do
  if ! wait "$pid"; then
    failed=1
  fi
done
if [ "$failed" -ne 0 ]; then
  echo "one or more analyzer shards failed; logs: $TMP_RUN" >&2
  KEEP_TMP=1
  exit 1
fi

python3 - "$ANALYZER_WORKERS" "$TMP_RUN" "$OUT_DIR/analyzed_${THRESHOLD}.json" <<'PY'
import json
import sys
from pathlib import Path

workers = int(sys.argv[1])
tmp = Path(sys.argv[2])
output = Path(sys.argv[3])
records = []
for index in range(workers):
    records.extend(json.loads((tmp / f"analyzed-{index}.json").read_text(encoding="utf-8")))
output.write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding="utf-8")
print(f"merged {len(records)} analyzed records")
PY

CONFIG_FILE="$CONFIG" "$JAVA_BIN" -cp "$CLASSPATH" org.ExperimentExecutor.RunRepairer \
  > "$OUT_DIR/repairer.log" 2>&1

python3 - "$OUT_DIR/output_${THRESHOLD}.json" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
records = json.loads(path.read_text(encoding="utf-8"))
if not records:
    raise SystemExit(f"empty output: {path}")
print(f"wrote {len(records)} records to {path}")
PY
