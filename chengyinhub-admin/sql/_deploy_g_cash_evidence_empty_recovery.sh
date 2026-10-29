#!/usr/bin/env bash
# Approved CI only; Java entry requires an explicit local G IT UUID schema, never a production target.
set -euo pipefail
: "${G_CASH_RECOVERY_CLASSPATH:?set the approved CI compiled test/runtime classpath}"
: "${G_CASH_RECOVERY_REPOSITORY:?set the frozen public repository root}"
exec java -cp "$G_CASH_RECOVERY_CLASSPATH" \
  com.chengyinhub.business.it.GCashEvidenceEmptyRecovery "$G_CASH_RECOVERY_REPOSITORY"
