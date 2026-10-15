#!/usr/bin/env bash
# Real packaged application, disposable stores, no published ports or provider credentials.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
FIXTURE="$ROOT/ci/upgrade-startup"
JAR="$ROOT/chengyinhub-admin/target/chengyinhub-admin.jar"
test -f "$JAR"
command -v docker >/dev/null
command -v strace >/dev/null
command -v python3 >/dev/null
# Bind mounts are local to this Linux daemon; never inherit a Mac Docker context.
DOCKER_ENDPOINT=$(python3 -I "$ROOT/ci/upgrade_startup_network.py" --docker-endpoint)
DOCKER=(docker --host "$DOCKER_ENDPOINT")
ACTUAL_DOCKER_ID=$("${DOCKER[@]}" info --format '{{.ID}}')
if [ "${CHENGYIN_STARTUP_TARGET:-}" = test-vps ]; then
  test -n "${CHENGYIN_STARTUP_DOCKER_ID:-}"
  test "$ACTUAL_DOCKER_ID" = "$CHENGYIN_STARTUP_DOCKER_ID"
fi
RUN_NAME="cy-startup-$$-$RANDOM"
OUT=$(mktemp -d "${TMPDIR:-/tmp}/cy-startup.XXXXXXXX")
chmod 755 "$OUT"
mkdir "$OUT/trace-libs"
STRACE=$(command -v strace)
# Host strace and its non-libc dependencies; never mount host credentials or config.
while read -r library; do cp -L "$library" "$OUT/trace-libs/"; done < <(
  ldd "$STRACE" | awk '/=> \// && $1 != "libc.so.6" {print $3}')
cleanup() {
  local result=$?
  trap - EXIT
  "${DOCKER[@]}" logs "$RUN_NAME-app" > "$OUT/application.log" 2>&1 || true
  "${DOCKER[@]}" rm -f "$RUN_NAME-app" "$RUN_NAME-mysql" "$RUN_NAME-redis" >/dev/null 2>&1 || true
  "${DOCKER[@]}" network rm "$RUN_NAME" >/dev/null 2>&1 || true
  echo "Startup evidence: $OUT (exit=$result)"
  if [ "$result" -ne 0 ]; then
    if [ -f "$OUT/health.log" ]; then cat "$OUT/health.log"; fi
    tail -100 "$OUT/application.log"
  fi
  exit "$result"
}
trap cleanup EXIT
JDK_IMAGE=maven:3.9.11-eclipse-temurin-21@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237
MYSQL_IMAGE=mysql:8.0.45@sha256:4af1f8815716546f5b12410f7621f37f93db8dd11a184706ef59111930b8c2ff
REDIS_IMAGE=redis:7.4-alpine@sha256:858f009f9709ce576febc734aa78b8f6d624b82571f9ddb6bda4377c833b3499
for image in "$JDK_IMAGE" "$MYSQL_IMAGE" "$REDIS_IMAGE"; do "${DOCKER[@]}" image inspect "$image" >/dev/null 2>&1 || "${DOCKER[@]}" pull "$image"; done
"${DOCKER[@]}" network create --internal "$RUN_NAME" >/dev/null
test "$("${DOCKER[@]}" network inspect "$RUN_NAME" --format '{{.Internal}}')" = true
"${DOCKER[@]}" run -d --name "$RUN_NAME-mysql" --network "$RUN_NAME" --tmpfs /var/lib/mysql:rw,size=768m \
  -e MYSQL_ALLOW_EMPTY_PASSWORD=yes -e MYSQL_DATABASE=upgrade_startup_probe \
  -e MYSQL_USER=startup_probe -e MYSQL_PASSWORD=synthetic-local-only \
  "$MYSQL_IMAGE" --skip-log-bin --default-time-zone=+00:00 >/dev/null
"${DOCKER[@]}" run -d --name "$RUN_NAME-redis" --network "$RUN_NAME" --tmpfs /data:rw,size=64m \
  "$REDIS_IMAGE" redis-server --save '' --appendonly no >/dev/null
ready=false
for attempt in $(seq 1 90); do
  if "${DOCKER[@]}" exec -e MYSQL_PWD=synthetic-local-only "$RUN_NAME-mysql" mysql -h127.0.0.1 -ustartup_probe \
    -Dupgrade_startup_probe -N -e 'SELECT VERSION()' > "$OUT/mysql-version" 2>/dev/null; then ready=true; break; fi
  sleep 1
done
test "$ready" = true
grep -qx '8.0.45' "$OUT/mysql-version"
test "$("${DOCKER[@]}" exec "$RUN_NAME-mysql" mysql -uroot -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='upgrade_startup_probe'")" = 0
test "$("${DOCKER[@]}" exec "$RUN_NAME-redis" redis-cli DBSIZE)" = 0
"${DOCKER[@]}" exec -i "$RUN_NAME-mysql" mysql -uroot upgrade_startup_probe < "$FIXTURE/schema.sql"
MYSQL_IP=$("${DOCKER[@]}" inspect "$RUN_NAME-mysql" --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}')
REDIS_IP=$("${DOCKER[@]}" inspect "$RUN_NAME-redis" --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}')
"${DOCKER[@]}" run -d --name "$RUN_NAME-app" --network "$RUN_NAME" --dns 127.0.0.1 \
  --read-only --tmpfs /tmp:rw,size=256m --cap-drop ALL --cap-add SYS_PTRACE \
  --user "$(id -u):$(id -g)" -e LD_LIBRARY_PATH=/trace-libs \
  -v "$STRACE:/trace/strace:ro" -v "$OUT/trace-libs:/trace-libs:ro" \
  -v "$JAR:/application.jar:ro" -v "$FIXTURE:/fixture:ro" -v "$OUT:/evidence" \
  --entrypoint /trace/strace "$JDK_IMAGE" -f -s 0 -e trace=connect,sendto,sendmsg -o /evidence/network.trace \
  java -Xmx512m -Djava.rmi.server.hostname=127.0.0.1 \
  -Dlogging.config=/fixture/logback.xml -Dlogback.configurationFile=/fixture/logback.xml \
  -jar /application.jar --spring.config.location=file:/fixture/application.properties \
  "--spring.datasource.druid.master.url=jdbc:mysql://$MYSQL_IP:3306/upgrade_startup_probe?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC" \
  "--spring.data.redis.host=$REDIS_IP" >/dev/null
ready=false
for attempt in $(seq 1 120); do
  "${DOCKER[@]}" logs "$RUN_NAME-app" > "$OUT/application.log" 2>&1
  if grep -q '城瘾API启动成功' "$OUT/application.log"; then ready=true; break; fi
  test "$("${DOCKER[@]}" inspect "$RUN_NAME-app" --format '{{.State.Running}}')" = true || break
  sleep 1
done
test "$ready" = true
# Runner UID need not have an /etc/passwd entry in the image; jcmd -l then misses it.
APP_PID=$("${DOCKER[@]}" exec "$RUN_NAME-app" sh -c '
  for entry in /proc/[0-9]*/cmdline; do
    case "$(readlink "${entry%/cmdline}/exe" 2>/dev/null)" in */java) ;; *) continue ;; esac
    if tr "\000" "\n" < "$entry" 2>/dev/null | grep -qxF "/application.jar"; then
      pid=${entry#/proc/}
      printf "%s\n" "${pid%/cmdline}"
    fi
  done')
case "$APP_PID" in
  ''|*[!0-9]*) echo "Expected exactly one application Java PID, got: $APP_PID" >&2; exit 1 ;;
esac
"${DOCKER[@]}" exec "$RUN_NAME-app" java /fixture/HealthProbe.java "$APP_PID" > "$OUT/health.log"
cat "$OUT/health.log"
test "$("${DOCKER[@]}" exec "$RUN_NAME-app" curl --noproxy '*' -s -o /tmp/auth-response -w '%{http_code}' http://127.0.0.1:8080/api/city/nodes)" = 401
test "$("${DOCKER[@]}" exec "$RUN_NAME-app" curl --noproxy '*' -s -X POST -o /tmp/auth-response -w '%{http_code}' http://127.0.0.1:8080/api/official/events)" = 401
"${DOCKER[@]}" exec "$RUN_NAME-redis" redis-cli --raw GET sys_config:startup.synthetic.probe > "$OUT/config-cache"
grep -q 'cache-roundtrip' "$OUT/config-cache"
test "$("${DOCKER[@]}" exec "$RUN_NAME-redis" redis-cli EXISTS sys_dict:startup_synthetic)" = 1
# Observe beyond the previous heartbeat/export first-run delays; no status publisher or business schema exists.
sleep 6
"${DOCKER[@]}" exec "$RUN_NAME-app" test ! -e /tmp/startup-status.json
"${DOCKER[@]}" logs "$RUN_NAME-app" > "$OUT/application.log" 2>&1
if grep -q 'Scheduler .* started\.' "$OUT/application.log"; then
  echo 'Quartz started while jobs were disabled' >&2
  exit 1
fi
python3 -I "$ROOT/ci/upgrade_startup_network.py" "$OUT/network.trace" "$MYSQL_IP" "$REDIS_IP"

"${DOCKER[@]}" inspect "$RUN_NAME-app" "$RUN_NAME-mysql" "$RUN_NAME-redis" \
  --format '{{.Name}} ports={{json .HostConfig.PortBindings}} networks={{range $key, $value := .NetworkSettings.Networks}}{{$key}} {{end}}' > "$OUT/isolation.log"
cat "$OUT/isolation.log"
echo 'PASS real packaged application ready, isolated startup health UP (disabled payment DOWN), MySQL/Redis cache roundtrip, protected HTTP 401, jobs disabled'
