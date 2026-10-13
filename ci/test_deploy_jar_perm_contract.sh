#!/usr/bin/env bash
# deploy-restart.sh 的 jar 可读性契约测试。
# Source 真实脚本的 ensure_jar_readable 函数(非复制逻辑),测试即 seam 绑定。
#
# 背景:2026-07-31 生产事故 —— scp 落地的 jar 是 0600 root:root,服务 User=www 读不到,
# java 报 `Unable to access jarfile`,systemd 重启 5 次后放弃,挂了 31 小时且零告警。
#
# 验证:①0600 的 jar 被置成 0644;②服务用户读不到时非零退出(不静默放过);
#      ③systemctl 取不到 User 时回落 root。
# 本地/CI 非 root,故 shim systemctl 与 su —— shell 函数优先于外部命令。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_RESTART_SOURCED=1
source "$SCRIPT_DIR/deploy-restart.sh"

TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

get_perms() { stat -c "%a" "$1" 2>/dev/null || stat -f "%Lp" "$1" 2>/dev/null || echo error; }

# shim:被测函数只该问 systemctl 要 User,以及用 su 真读一次。
SHIM_USER="www"      # systemctl show -p User --value 的返回
SHIM_SU_RC=0         # su 读文件的退出码
SHIM_SU_USER=""      # 记录被测函数实际用了哪个用户
systemctl() { printf '%s\n' "$SHIM_USER"; }
su() { SHIM_SU_USER="${*: -1}"; return "$SHIM_SU_RC"; }

pass=0; fail=0
check() { # check <名字> <错误串>
  if [ -n "$2" ]; then echo "FAIL $1:$2"; fail=$((fail + 1)); else pass=$((pass + 1)); fi
}

# 测试 1:0600 的 jar → 0644,且以 systemd 里的 User 去读
t1="$TEMP_DIR/t1.jar"; : > "$t1"; chmod 600 "$t1"
SHIM_USER="www"; SHIM_SU_RC=0
ensure_jar_readable "$t1" fake-service >/dev/null
err=""
[ "$(get_perms "$t1")" = "644" ] || err="$err perms=$(get_perms "$t1") 期望 644"
[ "$SHIM_SU_USER" = "www" ] || err="$err 读取用户=$SHIM_SU_USER 期望 www"
check t1_chmod_and_user "$err"

# 测试 2:服务用户读不到 → 非零退出(子 shell 隔离 exit)
t2="$TEMP_DIR/t2.jar"; : > "$t2"
SHIM_USER="www"; SHIM_SU_RC=1
err=""
if ( ensure_jar_readable "$t2" fake-service >/dev/null 2>&1 ); then
  err=" 读不到却退出 0 —— 静默放过,正是 7-31 事故的形态"
fi
check t2_fail_closed "$err"

# 测试 3:systemctl 取不到 User(空)→ 回落 root,不是空串
t3="$TEMP_DIR/t3.jar"; : > "$t3"
SHIM_USER=""; SHIM_SU_RC=0; SHIM_SU_USER=""
ensure_jar_readable "$t3" fake-service >/dev/null
err=""
[ "$SHIM_SU_USER" = "root" ] || err=" 回落用户=$SHIM_SU_USER 期望 root"
check t3_user_fallback "$err"

# 负控:确保 deploy-restart.sh 的主流程里仍有 ensure_jar_readable 调用行。
# 若有人只删调用、保留函数定义,上面三项照样全绿 —— 而那正是 7-31 事故的形态
# (部署路径不碰权限)。这道检查让它红。与 test_deploy_env_contract.sh 同构。
call_count=$(grep -c '^[[:space:]]*ensure_jar_readable "\$JAR" "\$SERVICE"$' "$SCRIPT_DIR/deploy-restart.sh" || true)
err=""
[ "$call_count" -ge 1 ] || err=" deploy-restart.sh 主流程缺少 ensure_jar_readable 调用行"
check mutation_call_present "$err"

echo "通过 $pass,失败 $fail"
[ "$fail" -eq 0 ]
