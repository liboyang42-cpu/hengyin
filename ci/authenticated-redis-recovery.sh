#!/usr/bin/env bash
# A single disposable hosted fixture, not the live VPS/UAT or a cash-payment test.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
python3 -I "$ROOT/ci/authenticated_redis_recovery_probe.py" --check-execution-only
test "$(git -C "$ROOT" rev-parse HEAD)" = "$GITHUB_SHA"
test "$(timeout 20s docker --context default context inspect --format '{{.Endpoints.docker.Host}}')" = unix:///var/run/docker.sock
python3 -c 'import crypt; assert crypt.METHOD_BLOWFISH in crypt.methods'
command -v strace >/dev/null
JAR="$ROOT/chengyinhub-admin/target/chengyinhub-admin.jar"
test -f "$JAR"
RUN_NAME="cy-auth-$(python3 -c 'import uuid; print(uuid.uuid4().hex)')"
SCHEMA="cy_e2e_auth_${RUN_NAME#cy-auth-}"
mkdir -p "${TMPDIR:?}"
OUT=$(mktemp -d "$TMPDIR/cy-auth.XXXXXXXX")
chmod 755 "$OUT"
PRIVATE=$(mktemp -d)
chmod 700 "$PRIVATE"
export CHENGYIN_AUTH_FIXTURE_DB_PASSWORD
CHENGYIN_AUTH_FIXTURE_DB_PASSWORD=$(python3 -c 'import secrets; print(secrets.token_urlsafe(24))')
export MYSQL_ROOT_PASSWORD="$CHENGYIN_AUTH_FIXTURE_DB_PASSWORD"
docker() { timeout 120s docker --context default "$@"; }
cleanup() {
  local result=$? unknown=0
  trap - EXIT
  if ! python3 -B "$ROOT/ci/authenticated_redis_recovery_probe.py" cleanup "$RUN_NAME" "$SCHEMA" "$OUT" "$PRIVATE/credentials.json" "${PORT:-0}"; then unknown=1; fi
  rm -f "$PRIVATE/credentials.json"
  rm -f "$PRIVATE/mysql-version"
  rm -f "$PRIVATE/startup.log"
  rmdir "$PRIVATE" || unknown=1
  if [ "$unknown" -ne 0 ] && [ "$result" -eq 0 ]; then result=1; fi
  if [ "${CHENGYIN_CI_RESTORE_HTTP_STARTUP:-false}" = true ]; then
    if ! python3 -I "$ROOT/ci/restore_application_http_probe.py" finalize "$RUN_NAME" "$SCHEMA" "$OUT" "$result"; then
      unknown=1
      if [ "$result" -eq 0 ]; then result=1; fi
    fi
  elif [ "${CHENGYIN_CI_PACKAGED_GAMEPLAY_CAPACITY:-false}" = true ]; then
    if ! python3 -I "$ROOT/ci/gameplay_process_recovery_probe.py" packaged-finalize "$RUN_NAME" "$SCHEMA" "$OUT" "$result"; then
      unknown=1
      if [ "$result" -eq 0 ]; then result=1; fi
    fi
  elif [ "${CHENGYIN_CI_GAMEPLAY_PROCESS_RECOVERY:-false}" = true ]; then
    if ! python3 -I "$ROOT/ci/gameplay_process_recovery_probe.py" finalize "$RUN_NAME" "$SCHEMA" "$OUT" "$result"; then
      unknown=1
      if [ "$result" -eq 0 ]; then result=1; fi
    fi
  elif [ "${CHENGYIN_CI_CAPACITY_MIXED:-false}" = true ]; then
    if ! python3 -B "$ROOT/ci/capacity_mixed_probe.py" finalize "$RUN_NAME" "$SCHEMA" "$OUT" "$PRIVATE/credentials.json" "$result"; then
      unknown=1
      if [ "$result" -eq 0 ]; then result=1; fi
    fi
  fi
  printf 'Fixture terminal exit=%s cleanupUnknown=%s evidence=%s\n' "$result" "$unknown" "$OUT"
  exit "$result"
}
trap cleanup EXIT
trap 'exit 143' TERM HUP INT
JDK_IMAGE=maven:3.9.11-eclipse-temurin-21@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237
MYSQL_IMAGE=mysql:8.0.45@sha256:4af1f8815716546f5b12410f7621f37f93db8dd11a184706ef59111930b8c2ff
REDIS_IMAGE=redis:7.4-alpine@sha256:858f009f9709ce576febc734aa78b8f6d624b82571f9ddb6bda4377c833b3499
for image in "$JDK_IMAGE" "$MYSQL_IMAGE" "$REDIS_IMAGE"; do docker image inspect "$image" >/dev/null 2>&1 || docker pull "$image"; done
docker network create --internal --label "chengyin.auth-recovery=$RUN_NAME" "$RUN_NAME" >/dev/null
test "$(docker network inspect "$RUN_NAME" --format '{{.Internal}}')" = true
docker run -d --name "$RUN_NAME-mysql" --label "chengyin.auth-recovery=$RUN_NAME" --network "$RUN_NAME" \
  --memory=1g --memory-swap=1g --cpus=1 --tmpfs /var/lib/mysql:rw,size=768m \
  -e MYSQL_ROOT_PASSWORD -e MYSQL_ROOT_HOST=% "$MYSQL_IMAGE" --skip-log-bin --performance-schema=OFF --event-scheduler=OFF >/dev/null
MYSQL_CID=$(docker inspect "$RUN_NAME-mysql" --format '{{.Id}}')
docker run -d --name "$RUN_NAME-redis" --label "chengyin.auth-recovery=$RUN_NAME" --network "$RUN_NAME" --memory=64m --memory-swap=64m \
  --tmpfs /data:rw,size=32m "$REDIS_IMAGE" redis-server --save '' --appendonly no >/dev/null
REDIS_CID=$(docker inspect "$RUN_NAME-redis" --format '{{.Id}}')
python3 -B "$ROOT/ci/authenticated_redis_recovery_probe.py" mysql-ready "$RUN_NAME" "$SCHEMA" "$OUT" "$PRIVATE/credentials.json" 0
PORT=0 # Each Python phase holds its own 127.0.0.1 ephemeral relay socket.
python3 -B "$ROOT/ci/authenticated_redis_recovery_probe.py" prepare "$RUN_NAME" "$SCHEMA" "$OUT" "$PRIVATE/credentials.json" "$PORT"
mkdir "$OUT/trace-libs"
STRACE=$(command -v strace)
while read -r library; do cp -L "$library" "$OUT/trace-libs/"; done < <(ldd "$STRACE" | awk '/=> \// && $1 != "libc.so.6" {print $3}')
MYSQL_IP=$(docker inspect "$MYSQL_CID" --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}')
REDIS_IP=$(docker inspect "$REDIS_CID" --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}')
if [ "${CHENGYIN_CI_RESTORE_HTTP_STARTUP:-false}" = true ]; then
  # C reuses only this app slot; the original owner creates/removes all four fixed slots.
  test "$GITHUB_EVENT_NAME" = workflow_dispatch
  docker run -d --name "$RUN_NAME-restore-mysql" --label "chengyin.auth-recovery=$RUN_NAME" --network "$RUN_NAME" \
    --memory=1g --memory-swap=1g --cpus=1 --tmpfs /var/lib/mysql:rw,size=768m \
    -e MYSQL_ROOT_PASSWORD -e MYSQL_ROOT_HOST=% "$MYSQL_IMAGE" --skip-log-bin --performance-schema=OFF --event-scheduler=OFF >/dev/null
  python3 -B "$ROOT/ci/authenticated_redis_recovery_probe.py" restore-mysql-ready "$RUN_NAME" "$SCHEMA" "$OUT" "$PRIVATE/credentials.json" 0
  timeout --signal=TERM --kill-after=20s 1550s python3 -I "$ROOT/ci/restore_application_http_probe.py" run "$RUN_NAME" "$SCHEMA" "$OUT" "$PRIVATE/credentials.json"
  exit 0
fi
if [ "${CHENGYIN_CI_PACKAGED_GAMEPLAY_CAPACITY:-false}" = true ]; then
  test "$GITHUB_EVENT_NAME" = workflow_dispatch
  timeout --signal=TERM --kill-after=20s 1220s python3 -I "$ROOT/ci/gameplay_process_recovery_probe.py" packaged-run "$RUN_NAME" "$SCHEMA" "$OUT" "$PRIVATE/credentials.json"
  exit 0
fi
if [ "${CHENGYIN_CI_GAMEPLAY_PROCESS_RECOVERY:-false}" = true ]; then
  # The test-classpath child replaces only this fixture's application launch.
  python3 -I "$ROOT/ci/gameplay_process_recovery_probe.py" --check-host-only
  timeout --signal=TERM --kill-after=20s 920s python3 -I "$ROOT/ci/gameplay_process_recovery_probe.py" run "$RUN_NAME" "$SCHEMA" "$OUT" "$PRIVATE/credentials.json"
  exit 0
fi
docker run -d --name "$RUN_NAME-app" --label "chengyin.auth-recovery=$RUN_NAME" --network "$RUN_NAME" --dns 127.0.0.1 \
  --memory=1g --memory-swap=1g --cpus=1 --read-only --tmpfs /tmp:rw,size=256m --cap-drop ALL --cap-add SYS_PTRACE \
  --user "$(id -u):$(id -g)" -e LD_LIBRARY_PATH=/trace-libs \
  -v "$STRACE:/trace/strace:ro" -v "$OUT/trace-libs:/trace-libs:ro" \
  -v "$JAR:/application.jar:ro" -v "$ROOT/ci/upgrade-startup:/fixture:ro" -v "$OUT:/evidence" \
  --entrypoint /trace/strace "$JDK_IMAGE" -f -s 0 -e trace=connect,sendto,sendmsg -o /evidence/network.trace \
  java -Xmx512m -Dlogging.config=/fixture/logback.xml -Dlogback.configurationFile=/fixture/logback.xml \
  -jar /application.jar --spring.config.location=file:/fixture/application.properties \
  --spring.messages.basename=i18n/messages \
  "--spring.datasource.druid.master.url=jdbc:mysql://$MYSQL_IP:3306/$SCHEMA?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC" \
  "--spring.data.redis.host=$REDIS_IP" >/dev/null
APP_CID=$(docker inspect "$RUN_NAME-app" --format '{{.Id}}')
ready=false
for attempt in $(seq 1 150); do
  docker logs "$APP_CID" > "$PRIVATE/startup.log" 2>&1
  if grep -q '城瘾API启动成功' "$PRIVATE/startup.log"; then ready=true; break; fi
  test "$(docker inspect "$APP_CID" --format '{{.State.Running}}')" = true || break
  sleep 1
done
test "$ready" = true
rm "$PRIVATE/startup.log"
if [ "${CHENGYIN_CI_CAPACITY_MIXED:-false}" = true ]; then
  python3 -B "$ROOT/ci/capacity_mixed_probe.py" observe "$RUN_NAME" "$SCHEMA" "$OUT" "$PRIVATE/credentials.json" "$PORT"
else
  python3 -B "$ROOT/ci/authenticated_redis_recovery_probe.py" exercise "$RUN_NAME" "$SCHEMA" "$OUT" "$PRIVATE/credentials.json" "$PORT"
fi
python3 -I "$ROOT/ci/upgrade_startup_network.py" "$OUT/network.trace" "$MYSQL_IP" "$REDIS_IP"
docker inspect "$APP_CID" "$MYSQL_CID" "$REDIS_CID" \
  --format '{{.Id}} {{.Name}} networks={{range $key, $value := .NetworkSettings.Networks}}{{$key}} {{end}}' > "$OUT/isolation.log"
echo 'Isolated workload assertions finished; final acceptance still requires EXIT cleanup evidence'
