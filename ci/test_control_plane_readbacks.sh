#!/usr/bin/env bash
# 高价值控制面负控：每个子脚本必须对指定坏状态非零，再对好状态置零。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STUB_DIR="$(mktemp -d)"
LOG_DIR="$(mktemp -d)"
cleanup() { rm -rf "$STUB_DIR" "$LOG_DIR"; }
trap cleanup EXIT

fail() { echo "FAIL: $*"; exit 1; }

run_expect() {
  local label="$1" expected="$2" pattern="$3"
  shift 3
  local log="$LOG_DIR/${label//[^A-Za-z0-9_.-]/_}.log"
  set +e
  "$@" >"$log" 2>&1
  local status=$?
  set -e
  if [ "$expected" = red ]; then
    [ "$status" -ne 0 ] || fail "$label 应该非零，却通过了"
  else
    [ "$status" -eq 0 ] || { tail -c 2000 "$log"; fail "$label 应该通过，实际 exit=$status"; }
  fi
  grep -qE "$pattern" "$log" || { tail -c 2000 "$log"; fail "$label 没有命中期望观测: $pattern"; }
  echo "PASS[$expected]: $label (exit=$status)"
}

write_stub() {
  local name="$1"
  shift
  printf '%s\n' '#!/usr/bin/env bash' "$@" >"$STUB_DIR/$name"
  chmod +x "$STUB_DIR/$name"
}

echo '== backend jar 部署回读: digest / 精确 Java argv路径 / 启动时间 / 健康与PID复核 =='
bash "$ROOT/ci/test_verify_deployed_process_contract.sh"

echo '== 生产配置来源与时区: 环境合同 / Linux 真实进程正负控 =='
bash "$ROOT/scripts/test-production-preflight.sh"
python3 "$ROOT/scripts/test-production-preflight-service.py"
python3 "$ROOT/ci/test_check_deploy_health.py"
python3 "$ROOT/ci/test_db_backup_restore.py"
bash "$ROOT/ci/test_deploy_sh_backup_rotate.sh"

echo '== Admin UI 公网回读: version / index assets / asset HTTP =='
write_stub curl '
url=""
for arg in "$@"; do url="$arg"; done
case "$url" in
  *version.json*) printf "%s" "{\"commit\":\"${STUB_LIVE_COMMIT:-abc123}\"}";;
  *index.html*)
    if [ -n "${STUB_INDEX+x}" ]; then printf "%s" "$STUB_INDEX"; else printf "%s" "<html><script src=\"/static/app.js\"></script></html>"; fi;;
  */static/*) printf "%s" "${STUB_ASSET_CODE:-200}";;
  *) exit 22;;
esac'
UI_VERIFY="$ROOT/ci/verify-ui-deployed.sh"
run_expect ui-version-mismatch red '线上自报 commit=def456 期望=abc123' env PATH="$STUB_DIR:$PATH" STUB_LIVE_COMMIT=def456 bash "$UI_VERIFY" https://fixture.invalid abc123
run_expect ui-no-assets red '一个 /static 资源都没引用' env PATH="$STUB_DIR:$PATH" STUB_INDEX='<html></html>' bash "$UI_VERIFY" https://fixture.invalid abc123
run_expect ui-asset-404 red 'HTTP 404' env PATH="$STUB_DIR:$PATH" STUB_ASSET_CODE=404 bash "$UI_VERIFY" https://fixture.invalid abc123
run_expect ui-green green '个资源全部 200' env PATH="$STUB_DIR:$PATH" bash "$UI_VERIFY" https://fixture.invalid abc123

echo '== Codeup 镜像 ref 回读 =='
write_stub git '
case "${STUB_LS_REMOTE:-match}" in
  fail) exit 2;;
  mismatch) printf "bad\\trefs/heads/master\\n";;
  *) printf "%s\\trefs/heads/master\\n" "${STUB_EXPECTED:-good}";;
esac'
MIRROR_VERIFY="$ROOT/ci/verify-codeup-mirror.sh"
run_expect mirror-mismatch red 'Codeup master = bad,预期 good' env PATH="$STUB_DIR:$PATH" STUB_LS_REMOTE=mismatch bash "$MIRROR_VERIFY" good fixture-remote
run_expect mirror-readback-failure red 'Codeup master 回读失败' env PATH="$STUB_DIR:$PATH" STUB_LS_REMOTE=fail bash "$MIRROR_VERIFY" good fixture-remote
run_expect mirror-green green 'Codeup master verified at good' env PATH="$STUB_DIR:$PATH" bash "$MIRROR_VERIFY" good fixture-remote
echo '== Public 仓库生产工作流拒绝执行: 实际 job 守卫及移除守卫负控 =='
python3 "$ROOT/ci/test_verify_deploy_ci.py"

echo '== 自动补部署关闭: 不调用gh + 双轴只读告警 =='
write_stub gh 'echo called > "$STUB_GH_CALL_FILE"; exit 1'
TRIGGER="$ROOT/ci/trigger-redeploy.sh"
run_expect redeploy-backend-disabled red '自动补部署已关闭' env PATH="$STUB_DIR:$PATH" STUB_GH_CALL_FILE="$LOG_DIR/gh-called" bash "$TRIGGER" deploy.yml
run_expect redeploy-ui-disabled red '自动补部署已关闭' env PATH="$STUB_DIR:$PATH" STUB_GH_CALL_FILE="$LOG_DIR/gh-called" bash "$TRIGGER" deploy-ui.yml
[ ! -e "$LOG_DIR/gh-called" ] || fail '自动部署禁止路径仍调用gh'

echo '控制面回读负控:通过；public 仓库生产任务仍禁止执行'
