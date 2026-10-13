#!/usr/bin/env bash
# 生产机部署后核对:8080 上跑的,确实是本次构建的那个 jar。
#
# 为什么需要这个:deploy-restart.sh 的健康检查只是 curl 了一下 8080,原先只认 HTTP 响应，无法确认数据库/Redis。进程曾按 jar 文件名匹配，旧进程没被
# 杀掉时,新 jar 会因端口被占而退出,旧进程照样答话 —— "部署成功"是假的,且零告警。
# 本脚本是这条链路末端"能让绿变红"的那层观测。
#
# 用法:bash ci/verify-deployed.sh <jar路径> <部署起点epoch> <期望sha256> <检查器路径> <私有token文件路径>
set -euo pipefail

# Same NUL-argv/cwd resolution used by run-chengyinhub-admin.sh; no basename matching.
jar_process_matches() {
  local proc="$1" wanted="$2" executable arg after_jar=false
  executable=$(readlink -f -- "$proc/exe" 2>/dev/null) || return 1
  [[ "${executable##*/}" == java ]] || return 1
  [[ -r "$proc/cmdline" ]] || return 1
  while IFS= read -r -d '' arg; do
    if $after_jar; then
      if [[ "$arg" != /* ]]; then
        arg="$(readlink "$proc/cwd" 2>/dev/null || true)/$arg"
      fi
      [[ "$(readlink -f -- "$arg" 2>/dev/null || true)" == "$wanted" ]]
      return
    fi
    [[ "$arg" != '-jar' ]] || after_jar=true
  done < "$proc/cmdline"
  return 1
}

exact_jar_pids() {
  local wanted="$1" proc_root="${2:-/proc}" proc
  [[ -d "$proc_root" && -r "$proc_root" ]] || return 1
  for proc in "$proc_root"/[0-9]*; do
    [[ -d "$proc" ]] || continue
    if jar_process_matches "$proc" "$wanted"; then printf '%s\n' "${proc##*/}"; fi
  done
}

jar_start_ticks() {
  local line
  local -a fields
  IFS= read -r line < "$1/stat" || return 1
  # comm can contain spaces and parentheses; fields after its last ') ' start at field 3.
  read -r -a fields <<< "${line##*) }"
  [[ "${fields[19]:-}" =~ ^[0-9]+$ ]] || return 1
  printf '%s\n' "${fields[19]}"
}

verify_deployed_main() {
JAR="${1:?缺少 jar 路径}"
DEPLOY_START="${2:?缺少部署起点 epoch}"
WANT_DIGEST="${3:?缺少期望 sha256}"
HEALTH_CHECKER="${4:?缺少部署健康检查器}"
HEALTH_TOKEN_FILE="${5:?缺少受保护健康接口授权文件路径}"

fmt_time() { date -d "@$1" 2>/dev/null || echo "epoch $1"; }

echo "== 1/4 磁盘上的 jar 是本次构建产物 =="
got_digest="$(sha256sum "$JAR" | awk '{print $1}')"
if [ "$got_digest" != "$WANT_DIGEST" ]; then
  echo "❌ 指纹不符:生产=$got_digest 期望=$WANT_DIGEST"
  exit 1
fi
echo "   ✅ $got_digest"

echo "== 2/4 恰好一个进程在跑它 =="
wanted_jar=$(readlink -f -- "$JAR")
[[ -f "$wanted_jar" ]] || { echo '❌ jar 路径不可用'; return 1; }
pids="$(exact_jar_pids "$wanted_jar")" || { echo '❌ Linux 进程目录不可读'; return 1; }
count="$(printf '%s' "$pids" | grep -c . || true)"
if [ "$count" -ne 1 ]; then
  echo "❌ 期望恰好 1 个进程,实际 $count:${pids:-无}"
  echo "   0 个 = 没有真实 Java 进程使用本次 jar 的确切路径"
  echo "   >1 个 = 旧进程没杀干净,正在和新进程抢 8080"
  exit 1
fi
pid="$pids"
start_ticks=$(jar_start_ticks "/proc/$pid") || { echo '❌ PID 启动标识不可读'; return 1; }
echo "   ✅ PID $pid"

echo "== 3/4 该进程是本次部署之后才起来的 =="
etimes="$(ps -o etimes= -p "$pid" | tr -d ' ')"
[[ "$etimes" =~ ^[0-9]+$ ]] || { echo '❌ PID 已退出或启动时间不可读'; return 1; }
[[ "$DEPLOY_START" =~ ^[0-9]+$ ]] || { echo '❌ 部署起点不合法'; return 1; }
started="$(( $(date +%s) - etimes ))"
if [ "$started" -lt "$DEPLOY_START" ]; then
  echo "❌ PID $pid 启动于 $(fmt_time "$started"),早于本次部署起点 $(fmt_time "$DEPLOY_START")"
  echo "   → 新 jar 从未接管,生产跑的仍是旧进程。deploy-restart.sh 的 curl 检查看不出这个。"
  exit 1
fi
echo "   ✅ 启动于 $(fmt_time "$started"),晚于部署起点 $(fmt_time "$DEPLOY_START")"

echo "== 4/4 受保护健康接口确认应用、数据库和 Redis 就绪 =="
python3 "$HEALTH_CHECKER" "$HEALTH_TOKEN_FILE"
# Health may take several seconds; a reused PID or a second instance must not inherit the result.
[[ "$(jar_start_ticks "/proc/$pid" 2>/dev/null || true)" == "$start_ticks" ]] \
  && jar_process_matches "/proc/$pid" "$wanted_jar" \
  && [[ "$(exact_jar_pids "$wanted_jar" 2>/dev/null || true)" == "$pid" ]] \
  || { echo '❌ 健康检查后进程身份发生变化'; return 1; }

echo "✅ 已核实:生产跑的就是本次构建的 jar(PID $pid,指纹 $got_digest)"
}

if [[ "${VERIFY_DEPLOYED_SOURCED:-}" != 1 ]]; then
  verify_deployed_main "$@"
fi
