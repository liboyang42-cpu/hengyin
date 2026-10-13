#!/usr/bin/env bash
# Read-only migration ledger plan. This wrapper deliberately has no apply mode.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if [ "$#" -ne 2 ]; then
  echo "用法: $0 <migration-manifest.json> <read-only-history-snapshot.json>" >&2
  exit 2
fi

exec python3 "$ROOT/ci/migration_ledger.py" plan \
  --root "$ROOT" \
  --manifest "$1" \
  --history "$2"
