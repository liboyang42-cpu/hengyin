#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export E2E_DB_NAME="${E2E_DB_NAME:-cy_e2e_r2}"
export E2E_MYSQL_CONTAINER="${E2E_MYSQL_CONTAINER:-be-int-mysql}"
export E2E_REDIS_CONTAINER="${E2E_REDIS_CONTAINER:-be-int-redis}"
export E2E_BASE_URL="${E2E_BASE_URL:-http://127.0.0.1:8120}"

case "${E2E_DB_NAME}" in
  cy_e2e_r2|cy_e2e_beint) ;;
  *)
    echo "REFUSING TO RUN: E2E_DB_NAME=${E2E_DB_NAME} is not an allowed TEST database" >&2
    exit 1
    ;;
esac

echo "[1/2] Applying idempotent SQL seed to ${E2E_DB_NAME}..."
docker exec -i "${E2E_MYSQL_CONTAINER}" mysql -uroot --default-character-set=utf8mb4 "${E2E_DB_NAME}" < "${SCRIPT_DIR}/seed-e2e-v2.sql"

echo "[2/2] Running API seed + curl verification against ${E2E_BASE_URL}..."
python3 "${SCRIPT_DIR}/seed_e2e_v2.py" "$@"
