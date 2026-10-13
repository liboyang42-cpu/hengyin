#!/usr/bin/env bash
# 生产机部署后核对:8080 上跑的,确实是本次构建的那个 jar。
#
# 为什么需要这个:deploy-restart.sh 的健康检查只是 curl 了一下 8080,而 curl 不带 -f 时
# 任何 HTTP 响应(含 Spring Security 的 401)都算通过。于是当 pgrep 模式失配、旧进程没被
# 杀掉时,新 jar 会因端口被占而退出,旧进程照样答话 —— "部署成功"是假的,且零告警。
# 本脚本是这条链路末端"能让绿变红"的那层观测。
#
# 用法:bash ci/verify-deployed.sh <jar路径> <部署起点epoch> <期望sha256>
set -euo pipefail

JAR="${1:?缺少 jar 路径}"
DEPLOY_START="${2:?缺少部署起点 epoch}"
WANT_DIGEST="${3:?缺少期望 sha256}"

fmt_time() { date -d "@$1" 2>/dev/null || echo "epoch $1"; }

echo "== 1/3 磁盘上的 jar 是本次构建产物 =="
got_digest="$(sha256sum "$JAR" | awk '{print $1}')"
if [ "$got_digest" != "$WANT_DIGEST" ]; then
  echo "❌ 指纹不符:生产=$got_digest 期望=$WANT_DIGEST"
  exit 1
fi
echo "   ✅ $got_digest"

echo "== 2/3 恰好一个进程在跑它 =="
pids="$(pgrep -f '[-]jar .*chengyinhub-admin\.jar' || true)"
count="$(printf '%s' "$pids" | grep -c . || true)"
if [ "$count" -ne 1 ]; then
  echo "❌ 期望恰好 1 个进程,实际 $count:${pids:-无}"
  echo "   0 个 = 新 jar 没起来,或 pgrep 模式失配(此时旧进程可能仍在 8080 上答话)"
  echo "   >1 个 = 旧进程没杀干净,正在和新进程抢 8080"
  exit 1
fi
pid="$pids"
echo "   ✅ PID $pid"

echo "== 3/3 该进程是本次部署之后才起来的 =="
etimes="$(ps -o etimes= -p "$pid" | tr -d ' ')"
started="$(( $(date +%s) - etimes ))"
if [ "$started" -lt "$DEPLOY_START" ]; then
  echo "❌ PID $pid 启动于 $(fmt_time "$started"),早于本次部署起点 $(fmt_time "$DEPLOY_START")"
  echo "   → 新 jar 从未接管,生产跑的仍是旧进程。deploy-restart.sh 的 curl 检查看不出这个。"
  exit 1
fi
echo "   ✅ 启动于 $(fmt_time "$started"),晚于部署起点 $(fmt_time "$DEPLOY_START")"

echo "✅ 已核实:生产跑的就是本次构建的 jar(PID $pid,指纹 $got_digest)"
