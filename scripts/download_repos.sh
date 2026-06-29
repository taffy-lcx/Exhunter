#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
MANIFEST="$SCRIPT_DIR/repo_manifest.tsv"
REPOS_DIR="$ROOT/repos"

mkdir -p "$REPOS_DIR"

while IFS=$'\t' read -r repo_id folder clone_url; do
  [ -n "$repo_id" ] || continue
  target="$REPOS_DIR/$folder"
  if [ -d "$target/.git" ]; then
    echo "skip existing: $repo_id"
    continue
  fi
  echo "clone: $repo_id -> repos/$folder"
  git clone "$clone_url" "$target"
done < "$MANIFEST"
