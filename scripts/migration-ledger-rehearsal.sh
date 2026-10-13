#!/usr/bin/env bash
# Local negative controls for P1-3D. Never reads config, credentials, SSH, or a database.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOCAL_ONLY=false
if [ "${1:-}" = "--local-only" ]; then
  LOCAL_ONLY=true
elif [ "$#" -ne 0 ]; then
  echo "用法: $0 [--local-only]" >&2
  exit 2
fi

python3 "$ROOT/ci/test_migration_ledger.py"
python3 "$ROOT/ci/test_migration_gates.py"

echo "LOCAL_REHEARSAL_OK: bootstrap/pending/applied/idempotent/drift/interruption/missing/dependency"
echo "LOCAL_SQL_NEGATIVE_CONTROLS_OK: quoted-semicolon accepted; valid DELIMITER parsed; malformed/bare procedure rejected"

# Target production compatibility is MySQL 8. Docker is absent on some developer Macs.
# A different local major version must not be promoted to MySQL 8 evidence.
mysql8_available=false
if command -v mysqld >/dev/null 2>&1 && mysqld --version 2>/dev/null | grep -Eq 'Ver 8\.'; then
  mysql8_available=true
fi
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  echo "MYSQL8_CONTAINER_RUNTIME_AVAILABLE: a separate authorized MySQL 8 rehearsal is still required"
  mysql8_available=true
fi

if [ "$mysql8_available" = true ]; then
  echo "MYSQL8_UNVERIFIED: this script only proves the pure-local state machine; it did not claim DB acceptance" >&2
else
  echo "MYSQL8_UNVERIFIED: no local MySQL 8 or Docker runtime; this is an open acceptance item" >&2
fi

if [ "$LOCAL_ONLY" = true ]; then
  exit 0
fi
exit 2
