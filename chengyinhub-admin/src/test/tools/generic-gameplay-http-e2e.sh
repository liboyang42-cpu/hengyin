#!/usr/bin/env bash
set -euo pipefail
umask 077

PROFILE=${CHENGYIN_HTTP_E2E_PROFILE:-vps}
if [[ "$(uname -s)" == Darwin ]]; then
  echo 'Refusing Java/Redis/backend tests on macOS; run this on the dedicated test VPS or GitHub-hosted Linux.' >&2
  exit 2
fi
if [[ "$(uname -s)" != Linux ]]; then echo 'Linux execution is required.' >&2; exit 2; fi
case "$PROFILE" in
  vps)
    [[ "${GITHUB_ACTIONS:-}" != true ]] || { echo 'GitHub Actions must use the honest hosted profile, never the VPS flag.' >&2; exit 2; }
    [[ "${CHENGYIN_VPS_TEST_RUN:-}" == 1 ]] || { echo 'Explicit dedicated test VPS execution is required (CHENGYIN_VPS_TEST_RUN=1).' >&2; exit 2; } ;;
  github-hosted)
    if [[ "${GITHUB_ACTIONS:-}" != true || "${GITHUB_REPOSITORY:-}" != liboyang42-cpu/hengyin || "${RUNNER_ENVIRONMENT:-}" != github-hosted || -n "${CHENGYIN_VPS_TEST_RUN:-}" ]]; then
      echo 'Explicit public-repository GitHub-hosted execution required; never impersonate the VPS.' >&2; exit 2
    fi ;;
  *) echo 'Unknown HTTP execution profile.' >&2; exit 2 ;;
esac
export CHENGYIN_HTTP_E2E_TESTS=${CHENGYIN_HTTP_E2E_TESTS:-GenericGameplayHttpE2eIT}
export CHENGYIN_HTTP_E2E_REDIS_PORT=${CHENGYIN_HTTP_E2E_REDIS_PORT:-6387}
[[ "$CHENGYIN_HTTP_E2E_TESTS" != ,* && "$CHENGYIN_HTTP_E2E_TESTS" != *, && "$CHENGYIN_HTTP_E2E_TESTS" != *,,* ]] || { echo 'Malformed HTTP test selection.' >&2; exit 2; }
IFS=',' read -r -a SELECTED <<< "$CHENGYIN_HTTP_E2E_TESTS"
[[ ${#SELECTED[@]} -gt 0 ]] || exit 2
SEEN=','
for CLASS in "${SELECTED[@]}"; do
  case "$CLASS" in GenericGameplayHttpE2eIT|FreezeGameHttpE2eIT|FreezeReuseThemeHttpE2eIT|CrossDomainCombinedLoadMySqlIT) ;; *) echo 'Unreviewed HTTP test selection.' >&2; exit 2 ;; esac
  [[ "$SEEN" != *",$CLASS,"* ]] || { echo 'Duplicate HTTP test selection.' >&2; exit 2; }
  SEEN="$SEEN$CLASS,"
done
if [[ "$SEEN" == *",CrossDomainCombinedLoadMySqlIT,"* ]]; then
  [[ "$CHENGYIN_HTTP_E2E_TESTS" == CrossDomainCombinedLoadMySqlIT && "${HTTP_SCOPE:-}" == controlled-combination && "$PROFILE" == github-hosted && "${GITHUB_EVENT_NAME:-}" == workflow_dispatch ]] || { echo 'Controlled combination requires its exact manual hosted single-class partial scope.' >&2; exit 2; }
  # Same task's Redis process/database, separate original economic schema and gate.
  export CHENGYIN_AI_REDIS_IT_PORT="$CHENGYIN_HTTP_E2E_REDIS_PORT"
  export CHENGYIN_AI_REDIS_IT_DATABASE=15
  export B_PAYMENT_FACT_DDL_APPROVED=1
fi
if [[ "$PROFILE" == github-hosted && ${#SELECTED[@]} != 1 ]]; then echo 'Each hosted job must run one class with its own schema and Redis.' >&2; exit 2; fi
if [[ "${1:-}" == --check-profile && $# == 1 ]]; then exit 0; fi
[[ $# == 0 ]] || { echo 'Unknown HTTP runner argument.' >&2; exit 2; }
if [[ "${CHENGYIN_MYBATIS_IT_HOST:-}" != 127.0.0.1 || ! "${CHENGYIN_MYBATIS_IT_PORT:-}" =~ ^[0-9]{1,5}$ ]]; then
  echo 'Explicit loopback test MySQL host and port are required.' >&2; exit 2
fi
if (( 10#$CHENGYIN_MYBATIS_IT_PORT < 1 || 10#$CHENGYIN_MYBATIS_IT_PORT > 65535 )); then echo 'Invalid loopback MySQL port.' >&2; exit 2; fi
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)
cd "$ROOT"
if [[ "$PROFILE" == github-hosted ]]; then
  [[ "${SOURCE_SHA:-}" =~ ^[0-9a-f]{40}$ && "$(git rev-parse HEAD)" == "$SOURCE_SHA" ]] || { echo 'Exact checked-out candidate SHA required.' >&2; exit 2; }
else
  export SOURCE_SHA=${SOURCE_SHA:-$(git rev-parse HEAD)}
fi
for COMMAND in mvn java mysql redis-server redis-cli python3 rg; do
  command -v "$COMMAND" >/dev/null || { echo "Missing tool: $COMMAND" >&2; exit 2; }
done
JAVA_VERSION=$(java -version 2>&1 | head -1)
JAVA_MAJOR=$(printf '%s' "$JAVA_VERSION" | sed -E 's/.*version "([0-9]+).*/\1/')
[[ "$JAVA_MAJOR" == 21 ]] || { echo 'This HTTP fixture requires JDK21.' >&2; exit 2; }
JVM_FLAGS='-Xmx1024m -Djava.security.manager=allow --add-opens=java.base/java.lang=ALL-UNNAMED'
export CHENGYIN_MYBATIS_IT_HOST=127.0.0.1
export CHENGYIN_HTTP_E2E_DATABASE=cy_e2e_w1_http_$(date +%Y%m%d%H%M%S)_$$_${RANDOM}
OUT="$ROOT/chengyinhub-admin/target/http-e2e"
PUBLIC="$OUT/public"
mkdir -p "$PUBLIC"
# Only this task's public receipt directory is reset; raw logs/configuration stay private.
rm -f "$PUBLIC/receipt.json"
for CLASS in "${SELECTED[@]}"; do
  rm -f "$PUBLIC/TEST-com.chengyinhub.business.it.$CLASS.xml" "chengyinhub-admin/target/surefire-reports/TEST-com.chengyinhub.business.it.$CLASS.xml"
done
export CHENGYIN_HTTP_E2E_SCHEMA_REPORT="$OUT/fresh-schema.json"
rm -f "$CHENGYIN_HTTP_E2E_SCHEMA_REPORT"
python3 - <<'PY'
import os, shutil, socket
if shutil.disk_usage('.').free < 3.5 * 1024**3:
    raise SystemExit('At least3.5GiB free disk is required before fixture creation')
port = os.environ['CHENGYIN_HTTP_E2E_REDIS_PORT']
if not port.isdigit() or not 1 <= int(port) <= 65535:
    raise SystemExit('Invalid private Redis loopback port')
with socket.socket() as probe:
    try: probe.bind(('127.0.0.1', int(port)))
    except OSError: raise SystemExit('Redis port occupied; never reuse/stop another process')
PY
if [[ "$CHENGYIN_HTTP_E2E_TESTS" == CrossDomainCombinedLoadMySqlIT ]]; then
  TEMP_DIR=$(mktemp -d /tmp/cy-http-budget.XXXXXXXX)
else
  TEMP_DIR=$(mktemp -d)
fi
REDIS_PID=''
JAVA_STATUS=98
export CHENGYIN_HTTP_E2E_STAGE=schema
mysql_owned() {
  MYSQL_PWD="${CHENGYIN_MYBATIS_IT_PASSWORD:-}" mysql --no-defaults --protocol=TCP --host=127.0.0.1 \
    --port="$CHENGYIN_MYBATIS_IT_PORT" --user="${CHENGYIN_MYBATIS_IT_USER:-root}" --batch --skip-column-names "$@"
}
cleanup() {
  ORIGINAL=$?
  trap - EXIT
  set +e
  MYSQL_CLEAN=false
  REDIS_CLEAN=false
  BUDGET_VERIFIED=0
  if [[ "$CHENGYIN_HTTP_E2E_TESTS" == CrossDomainCombinedLoadMySqlIT ]]; then
    python3 -I ci/combined_http_budget.py finalize "$TEMP_DIR" "$ORIGINAL" || BUDGET_VERIFIED=$?
  fi
  PRESENT=$(mysql_owned --execute="SELECT SCHEMA_NAME FROM information_schema.schemata WHERE SCHEMA_NAME='$CHENGYIN_HTTP_E2E_DATABASE'")
  QUERY_STATUS=$?
  if [[ "$QUERY_STATUS" == 0 && -z "$PRESENT" ]]; then
    MYSQL_CLEAN=true
  elif [[ "$QUERY_STATUS" == 0 && "$PRESENT" == "$CHENGYIN_HTTP_E2E_DATABASE" ]] && python3 - <<'PY'
import json, os, pathlib
p=pathlib.Path(os.environ['CHENGYIN_HTTP_E2E_SCHEMA_REPORT'])
try:
    r=json.loads(p.read_text())
    owned=(r.get('database')==os.environ['CHENGYIN_HTTP_E2E_DATABASE'] and
           r.get('mode') in ('EXECUTING','FAILED','SUCCEEDED') and
           isinstance(r.get('files'),list) and len(r['files'])>100 and
           isinstance(r.get('completed'),list))
except (ValueError,OSError,TypeError): owned=False
raise SystemExit(0 if owned else 1)
PY
  then
    if mysql_owned --execute="DROP DATABASE \`$CHENGYIN_HTTP_E2E_DATABASE\`"; then
      PRESENT=$(mysql_owned --execute="SELECT SCHEMA_NAME FROM information_schema.schemata WHERE SCHEMA_NAME='$CHENGYIN_HTTP_E2E_DATABASE'")
      [[ $? == 0 && -z "$PRESENT" ]] && MYSQL_CLEAN=true
    fi
  fi
  if [[ -n "$REDIS_PID" ]]; then kill "$REDIS_PID" 2>/dev/null; wait "$REDIS_PID" 2>/dev/null; fi
  if [[ "$CHENGYIN_HTTP_E2E_TESTS" == CrossDomainCombinedLoadMySqlIT ]]; then
    if python3 -I - <<'PY'
import importlib.util, pathlib
spec=importlib.util.spec_from_file_location('fixed_budget_cleanup',pathlib.Path.cwd()/'ci/combined_http_budget.py')
owner=importlib.util.module_from_spec(spec);spec.loader.exec_module(owner)
owner.read_redis_cleanup_proof()
PY
    then REDIS_CLEAN=true; fi
  elif python3 - <<'PY'
import os,socket
with socket.socket() as probe:
    probe.bind(('127.0.0.1',int(os.environ['CHENGYIN_HTTP_E2E_REDIS_PORT'])))
PY
  then REDIS_CLEAN=true; fi
  if [[ "$CHENGYIN_HTTP_E2E_TESTS" == CrossDomainCombinedLoadMySqlIT ]]; then
    if python3 -I - <<'PY'
import importlib.util, pathlib
spec=importlib.util.spec_from_file_location('fixed_budget_private_cleanup',pathlib.Path.cwd()/'ci/combined_http_budget.py')
owner=importlib.util.module_from_spec(spec);spec.loader.exec_module(owner)
owner.read_private_cleanup_proof()
PY
    then rm -rf "$TEMP_DIR"
    else echo 'Private budget journal retained: exact container removal is unconfirmed.' >&2; fi
  else
    rm -rf "$TEMP_DIR"
  fi
  if [[ "$ORIGINAL" == 0 && "$BUDGET_VERIFIED" != 0 ]]; then ORIGINAL=2; CHENGYIN_HTTP_E2E_STAGE=cleanup; fi
  if [[ "$ORIGINAL" == 0 && ( "$MYSQL_CLEAN" != true || "$REDIS_CLEAN" != true ) ]]; then CHENGYIN_HTTP_E2E_STAGE=cleanup; fi
  CHENGYIN_HTTP_E2E_ORIGINAL_EXIT="$ORIGINAL" python3 ci/test_http_e2e_entry.py finalize --output "$PUBLIC" --java-exit "$JAVA_STATUS" --mysql-clean "$MYSQL_CLEAN" --redis-clean "$REDIS_CLEAN"
  VERIFIED=$?
  if [[ "$ORIGINAL" != 0 || "$VERIFIED" != 0 ]]; then exit 2; fi
  exit 0
}
trap cleanup EXIT
trap 'exit 130' INT TERM
python3 chengyinhub-admin/src/test/tools/generic-gameplay-fresh-schema.py \
  --profile "$PROFILE" --execute --port "$CHENGYIN_MYBATIS_IT_PORT" --user "${CHENGYIN_MYBATIS_IT_USER:-root}" \
  --database "$CHENGYIN_HTTP_E2E_DATABASE" --report "$CHENGYIN_HTTP_E2E_SCHEMA_REPORT" > "$OUT/fresh-schema.log" 2>&1
CHENGYIN_HTTP_E2E_STAGE=redis-start
if [[ "$CHENGYIN_HTTP_E2E_TESTS" == CrossDomainCombinedLoadMySqlIT ]]; then
  python3 -I ci/combined_http_budget.py prepare "$TEMP_DIR"
  CHENGYIN_HTTP_E2E_STAGE=redis-ready
else
redis-server --bind 127.0.0.1 --port "$CHENGYIN_HTTP_E2E_REDIS_PORT" --protected-mode yes \
  --save '' --appendonly no --dir "$TEMP_DIR" > "$TEMP_DIR/redis.log" 2>&1 &
REDIS_PID=$!
CHENGYIN_HTTP_E2E_STAGE=redis-ready
READY=0
for ATTEMPT in {1..50}; do
  kill -0 "$REDIS_PID" 2>/dev/null || { echo 'Private Redis process failed.' >&2; exit 2; }
  if redis-cli -h 127.0.0.1 -p "$CHENGYIN_HTTP_E2E_REDIS_PORT" -n 15 ping 2>/dev/null | rg -q '^PONG'; then READY=1; break; fi
  sleep 0.1
done
[[ "$READY" == 1 ]] || { echo 'Private Redis not ready.' >&2; exit 2; }
fi
CHENGYIN_HTTP_E2E_STAGE=java-tmp
mkdir -p "${TMPDIR:-/tmp}/javatmp"
CHENGYIN_HTTP_E2E_STAGE=java-tests
JAVA_STATUS=0
MAVEN_MODE=(-o)
if [[ "$PROFILE" == github-hosted ]]; then MAVEN_MODE=(-B); fi
# pom appends its existing Mockito startup javaagent to argLine.
if [[ "$CHENGYIN_HTTP_E2E_TESTS" == CrossDomainCombinedLoadMySqlIT ]]; then
  python3 -I ci/combined_http_budget.py run "$TEMP_DIR" > "$OUT/budget-run.log" 2>&1 || JAVA_STATUS=$?
else
SERVER_ADDRESS=127.0.0.1 mvn "${MAVEN_MODE[@]}" -q -pl chengyinhub-admin -am test "-DargLine=$JVM_FLAGS -Djava.io.tmpdir=${TMPDIR:-/tmp}/javatmp -Djava.awt.headless=true" "-Dtest=$CHENGYIN_HTTP_E2E_TESTS" \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false > "$OUT/run.log" 2>&1 || JAVA_STATUS=$?
fi
CHENGYIN_HTTP_E2E_STAGE=xml-verify
python3 ci/test_http_e2e_entry.py verify --classes "$CHENGYIN_HTTP_E2E_TESTS" --reports chengyinhub-admin/target/surefire-reports --output "$PUBLIC"
[[ "$JAVA_STATUS" == 0 ]]
