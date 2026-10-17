#!/usr/bin/env bash
set -euo pipefail

# Backend execution is authorized only on the dedicated development/test VPS.
if [[ "$(uname -s)" == Darwin ]]; then
  echo 'Refusing Java/Redis/backend tests on macOS; run this on the dedicated test VPS.' >&2
  exit 2
fi
if [[ "$(uname -s)" != Linux || "${CHENGYIN_VPS_TEST_RUN:-}" != 1 ]]; then
  echo 'Explicit dedicated test VPS execution is required (CHENGYIN_VPS_TEST_RUN=1).' >&2
  exit 2
fi
if [[ "${CHENGYIN_MYBATIS_IT_HOST:-}" != 127.0.0.1 || ! "${CHENGYIN_MYBATIS_IT_PORT:-}" =~ ^[0-9]{1,5}$ ]]; then
  echo 'Explicit loopback test MySQL host and port are required.' >&2
  exit 2
fi
if (( 10#$CHENGYIN_MYBATIS_IT_PORT < 1 || 10#$CHENGYIN_MYBATIS_IT_PORT > 65535 )); then
  echo 'Invalid loopback MySQL port.' >&2
  exit 2
fi

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)
cd "$ROOT"
if [[ "${CHENGYIN_MYBATIS_IT_HOST:-127.0.0.1}" != 127.0.0.1 ]]; then
  echo '拒绝非 loopback MySQL；此脚本只能连接本地隔离测试库。' >&2
  exit 2
fi
for COMMAND in mvn java mysql redis-server redis-cli python3 rg; do
  command -v "$COMMAND" >/dev/null || { echo "缺少本地工具：$COMMAND" >&2; exit 2; }
done
JAVA_VERSION=$(java -version 2>&1 | head -1)
JAVA_MAJOR=$(printf '%s' "$JAVA_VERSION" | sed -E 's/.*version "([0-9]+).*/\1/')
if [[ "$JAVA_MAJOR" != 21 ]]; then
  echo 'This public candidate requires the verified VPS JDK 21; refusing another Java version.' >&2
  exit 2
fi
JVM_FLAGS='-Djava.security.manager=allow --add-opens=java.base/java.lang=ALL-UNNAMED'
export CHENGYIN_MYBATIS_IT_HOST=127.0.0.1
export CHENGYIN_MYBATIS_IT_PORT=${CHENGYIN_MYBATIS_IT_PORT:-33080}
export CHENGYIN_HTTP_E2E_REDIS_PORT=${CHENGYIN_HTTP_E2E_REDIS_PORT:-6387}
export CHENGYIN_HTTP_E2E_DATABASE=cy_e2e_w1_http_$(date +%Y%m%d%H%M%S)_$$
mkdir -p chengyinhub-admin/target/http-e2e
export CHENGYIN_HTTP_E2E_SCHEMA_REPORT="$ROOT/chengyinhub-admin/target/http-e2e/fresh-schema.json"
python3 - <<'PY'
import shutil
if shutil.disk_usage('.').free < 3.5 * 1024**3:
    raise SystemExit('可用磁盘必须至少3.5 GiB；拒绝开始建库。')
PY
python3 - <<'PY'
import socket
with socket.socket() as probe:
    try:
        probe.bind(('127.0.0.1', int(__import__('os').environ['CHENGYIN_HTTP_E2E_REDIS_PORT'])))
    except OSError:
        raise SystemExit('测试 Redis 端口已使用；拒绝复用或关闭其他进程。')
PY
TEMP_DIR=$(mktemp -d)
REDIS_PID=''
cleanup() {
  if [[ -n "$REDIS_PID" ]]; then kill "$REDIS_PID" 2>/dev/null || true; wait "$REDIS_PID" 2>/dev/null || true; fi
  rm -rf "$TEMP_DIR"
  if python3 - <<'PY'
import json, os, pathlib
report = pathlib.Path(os.environ['CHENGYIN_HTTP_E2E_SCHEMA_REPORT'])
owned = report.exists() and json.loads(report.read_text()).get('database') == os.environ['CHENGYIN_HTTP_E2E_DATABASE']
raise SystemExit(0 if owned else 1)
PY
  then
    MYSQL_PWD="${CHENGYIN_MYBATIS_IT_PASSWORD:-}" mysql --no-defaults --protocol=TCP --host=127.0.0.1 \
    --port="$CHENGYIN_MYBATIS_IT_PORT" --user="${CHENGYIN_MYBATIS_IT_USER:-root}" \
    --execute="DROP DATABASE IF EXISTS \`$CHENGYIN_HTTP_E2E_DATABASE\`" || {
      echo "本地测试库清理失败：$CHENGYIN_HTTP_E2E_DATABASE；请只清理此库。" >&2
      exit 2
    }
  fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM
python3 chengyinhub-admin/src/test/tools/generic-gameplay-fresh-schema.py \
  --execute --port "$CHENGYIN_MYBATIS_IT_PORT" --user "${CHENGYIN_MYBATIS_IT_USER:-root}" \
  --database "$CHENGYIN_HTTP_E2E_DATABASE" --report "$CHENGYIN_HTTP_E2E_SCHEMA_REPORT" \
  > chengyinhub-admin/target/http-e2e/fresh-schema.log 2>&1
redis-server --bind 127.0.0.1 --port "$CHENGYIN_HTTP_E2E_REDIS_PORT" --protected-mode yes \
  --save '' --appendonly no --dir "$TEMP_DIR" > "$TEMP_DIR/redis.log" 2>&1 &
REDIS_PID=$!
READY=0
for ATTEMPT in {1..50}; do
  kill -0 "$REDIS_PID" 2>/dev/null || { echo '临时 Redis 启动失败。' >&2; exit 2; }
  if redis-cli -h 127.0.0.1 -p "$CHENGYIN_HTTP_E2E_REDIS_PORT" ping 2>/dev/null | rg -q '^PONG'; then READY=1; break; fi
  sleep 0.1
done
[[ "$READY" == 1 ]] || { echo '本地 Redis 未就绪。' >&2; exit 2; }
mkdir -p chengyinhub-admin/target/http-e2e
rm -f chengyinhub-admin/target/http-e2e/transcript.json \
  chengyinhub-admin/target/surefire-reports/TEST-com.chengyinhub.business.it.GenericGameplayHttpE2eIT.xml
LOG=chengyinhub-admin/target/http-e2e/run.log
STATUS=0
mkdir -p "${TMPDIR:-/tmp}/javatmp"
SERVER_ADDRESS=127.0.0.1 mvn -o -q -pl chengyinhub-admin -am test "-DargLine=$JVM_FLAGS -Djava.io.tmpdir=${TMPDIR:-/tmp}/javatmp -Djava.awt.headless=true" "-Dtest=${CHENGYIN_HTTP_E2E_TESTS:-GenericGameplayHttpE2eIT}" \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false > "$LOG" 2>&1 || STATUS=$?
python3 - <<'PY'
import json
import pathlib
import xml.etree.ElementTree as xml
report = pathlib.Path('chengyinhub-admin/target/surefire-reports/TEST-com.chengyinhub.business.it.GenericGameplayHttpE2eIT.xml')
if report.exists():
    result = xml.parse(report).getroot()
    print({key: result.get(key) for key in ('tests', 'failures', 'errors', 'skipped')})
transcript = pathlib.Path('chengyinhub-admin/target/http-e2e/transcript.json')
if transcript.exists():
    for row in json.loads(transcript.read_text()):
        print(row['method'], row['path'], 'HTTP', row['httpStatus'], 'code', row['response'].get('code'))
PY
echo "原始本地日志：${LOG}（不上传；可能包含临时配置）。脱敏请求回读：chengyinhub-admin/target/http-e2e/transcript.json"
exit "$STATUS"
