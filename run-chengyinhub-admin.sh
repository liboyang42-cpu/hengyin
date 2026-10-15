#!/usr/bin/env bash
set -euo pipefail

case "$(uname -s)" in
  Linux) ;;
  *) echo 'Backend restart requires the Linux test/deployment host; Mac Java execution is forbidden' >&2; exit 1 ;;
esac

cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
jar=$(readlink -f -- chengyinhub-admin.jar)
test -f "$jar" || { echo 'chengyinhub-admin.jar is missing' >&2; exit 1; }
exec 9>.chengyinhub-admin-restart.lock
flock -n 9 || { echo 'Another restart is running' >&2; exit 1; }

pids=()
for proc in /proc/[0-9]*; do
  test "$(stat -c %u "$proc" 2>/dev/null || true)" = "$(id -u)" || continue
  after_jar=false
  while IFS= read -r -d '' arg; do
    if $after_jar; then
      if [[ "$arg" != /* ]]; then
        arg="$(readlink "$proc/cwd" 2>/dev/null || true)/$arg"
      fi
      if test "$(readlink -f -- "$arg" 2>/dev/null || true)" = "$jar"; then
        pids+=("${proc##*/}")
      fi
      break
    fi
    test "$arg" != '-jar' || after_jar=true
  done < "$proc/cmdline" 2>/dev/null || true
done

for pid in "${pids[@]}"; do
  kill -TERM "$pid" 2>/dev/null || true
done
remaining=()
for ((attempt=0; attempt<60; attempt++)); do
  remaining=()
  for pid in "${pids[@]}"; do
    state=$(ps -o stat= -p "$pid" 2>/dev/null || true)
    test -z "$state" || [[ "$state" == Z* ]] || remaining+=("$pid")
  done
  test "${#remaining[@]}" -gt 0 || break
  sleep 1
done
if test "${#remaining[@]}" -gt 0; then
  echo 'Graceful shutdown timed out; no second backend was started' >&2
  exit 1
fi

nohup java -Duser.timezone=Asia/Shanghai -jar "$jar" >catalina_chengyinhub-admin.out 2>&1 &
flock -u 9
exec tail -n 100 -F catalina_chengyinhub-admin.out
