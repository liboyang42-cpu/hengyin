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

echo '== backend jar 部署回读: digest / 进程数 / 启动时间 =='
write_stub sha256sum 'printf "%s  %s\\n" "${STUB_DIGEST:-good}" "$1"'
write_stub pgrep 'case "${STUB_PIDS:-one}" in zero) exit 1;; two) printf "101\\n202\\n";; *) printf "101\\n";; esac'
write_stub ps 'printf " %s\\n" "${STUB_ETIMES:-10}"'
write_stub date 'case "${1:-}" in +%s) printf "%s\\n" "${STUB_NOW:-200}";; -d) printf "%s\\n" "${2:-epoch}";; *) /bin/date "$@";; esac'
JAR_FIXTURE="$LOG_DIR/chengyinhub-admin.jar"
printf 'fixture\n' >"$JAR_FIXTURE"
JAR_VERIFY="$ROOT/ci/verify-deployed.sh"
run_expect jar-digest-mismatch red '指纹不符' env PATH="$STUB_DIR:$PATH" STUB_DIGEST=bad bash "$JAR_VERIFY" "$JAR_FIXTURE" 100 good
run_expect jar-zero-process red '实际 0' env PATH="$STUB_DIR:$PATH" STUB_PIDS=zero bash "$JAR_VERIFY" "$JAR_FIXTURE" 100 good
run_expect jar-two-processes red '实际 2' env PATH="$STUB_DIR:$PATH" STUB_PIDS=two bash "$JAR_VERIFY" "$JAR_FIXTURE" 100 good
run_expect jar-old-process red '早于本次部署起点' env PATH="$STUB_DIR:$PATH" STUB_ETIMES=150 bash "$JAR_VERIFY" "$JAR_FIXTURE" 100 good
run_expect jar-green green '已核实:生产跑的就是本次构建的 jar' env PATH="$STUB_DIR:$PATH" bash "$JAR_VERIFY" "$JAR_FIXTURE" 100 good

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
grep -qF 'bash ci/verify-codeup-mirror.sh "$expected" "$codeup_url"' "$ROOT/.github/workflows/codeup-mirror.yml" \
  || fail 'Codeup workflow 没有调用可测的 ref 回读脚本'
grep -qF 'runs-on: [self-hosted, macOS, ARM64, chengyin]' "$ROOT/.github/workflows/codeup-mirror.yml" \
  || fail 'Codeup mirror 没有使用可用的本机免费 runner'
grep -qF 'CODEUP_MIRROR_SSH_KEY: ${{ secrets.CODEUP_MIRROR_SSH_KEY }}' "$ROOT/.github/workflows/codeup-mirror.yml" \
  || fail 'Codeup mirror 没有使用受控 SSH key'
grep -qF 'ECS_SSH_PRIVATE_KEY: ${{ secrets.ECS_SSH_PRIVATE_KEY }}' "$ROOT/.github/workflows/codeup-mirror.yml" \
  || fail 'Codeup mirror 没有使用受控 ECS 跳板身份'
grep -qF 'ProxyCommand $proxy_script' "$ROOT/.github/workflows/codeup-mirror.yml" \
  || fail 'Codeup mirror 没有通过 Session Manager 进入 ECS'
grep -qF 'ProxyJump chengyin-ecs-jump' "$ROOT/.github/workflows/codeup-mirror.yml" \
  || fail 'Codeup mirror 没有经 ECS 访问 Codeup SSH/22'
if grep -qF 'runs-on: ubuntu-latest' "$ROOT/.github/workflows/codeup-mirror.yml"; then
  fail 'Codeup mirror 仍依赖当前不可用的 GitHub 付费 runner'
fi

echo '== drift audit 自愈触发失败传播 + 双轴结构 =='
write_stub gh 'if [ "${STUB_GH_RESULT:-ok}" = fail ]; then exit 1; fi'
TRIGGER="$ROOT/ci/trigger-redeploy.sh"
run_expect redeploy-trigger-failure red '自愈没发生' env PATH="$STUB_DIR:$PATH" STUB_GH_RESULT=fail bash "$TRIGGER" deploy.yml
run_expect redeploy-trigger-green green '回执不是部署观测' env PATH="$STUB_DIR:$PATH" bash "$TRIGGER" deploy.yml

DRIFT="$ROOT/.github/workflows/drift-audit.yml"
grep -qF 'audit_jar || fail=1' "$DRIFT" || fail 'drift audit 缺后端轴的失败聚合'
grep -qF 'audit_ui || fail=1' "$DRIFT" || fail 'drift audit 缺 UI 轴的失败聚合'
grep -qF 'bash ci/trigger-redeploy.sh "$1"' "$DRIFT" || fail 'drift audit 没有调用可测的非零传播脚本'
echo 'PASS[green]: drift audit 两条轴都会执行并聚合失败'

echo '== 部署失败即刻对账: 触发器 / 防环守卫 / 结论正列 / 不拖 Mac =='
# ★ workflow 名字从 deploy*.yml 的 `name:` 现读,不在这里抄字面量 ——
#   抄了就等于把判据复制一份(ci/classify-runtime.sh 抬头那条铁律):
#   任何一边改名 ⇒ 触发器静默失效,而门禁照样绿。
DEPLOY_NAME=$(sed -n 's/^name: //p' "$ROOT/.github/workflows/deploy.yml" | head -1)
DEPLOY_UI_NAME=$(sed -n 's/^name: //p' "$ROOT/.github/workflows/deploy-ui.yml" | head -1)
[ -n "$DEPLOY_NAME" ] && [ -n "$DEPLOY_UI_NAME" ] || fail '读不出两条部署链路的 workflow name'

# 块的终止条件是「下一个同缩进的 job」,不是某个具体 job 名 —— 写死名字的话,中间插进一个
# 新 job 就会把 audit 的守卫漏到块外,而门禁照样绿(参见下面 nc-guard-on-wrong-job)。
drift_block() { awk -v j="$2" '$0=="  "j":"{f=1;next} /^  [A-Za-z0-9_-]+:$/{f=0} f' "$1"; }
drift_on_block() { awk '/^on:$/{f=1;next} /^[a-z]/{f=0} f' "$1"; }

# 一处结构一条断言;返回非零并打印原因,好让负控能对上。
check_fast_path() {
  local f="$1" on audit mig c
  on=$(drift_on_block "$f")
  audit=$(drift_block "$f" audit)
  mig=$(drift_block "$f" migration-drift)

  grep -qF "workflows: [$DEPLOY_NAME, $DEPLOY_UI_NAME]" <<< "$on" \
    || { echo '缺部署链路失败触发器,或 workflow 名与 deploy*.yml 的 name: 不一致'; return 1; }
  grep -qF "github.event.workflow_run.event != 'workflow_dispatch'" <<< "$audit" \
    || { echo 'audit job 缺防环守卫:补部署自己失败会无限触发对账'; return 1; }
  for c in failure cancelled timed_out; do
    grep -qF "github.event.workflow_run.conclusion == '$c'" <<< "$audit" \
      || { echo "audit job 没把 $c 算作「部署没跑成」"; return 1; }
  done
  if grep -qF "conclusion == 'skipped'" <<< "$audit"; then
    echo 'audit job 把 skipped 也当失败:CI 红时部署 run 整体 skipped,会白烧云端额度'
    return 1
  fi
  grep -qF "if: github.event_name != 'workflow_run'" <<< "$mig" \
    || { echo 'migration-drift 没在快速对账里跳过 —— Mac 睡着会把 run 拖成 pending,快速判决就没了'; return 1; }
  return 0
}

# expect_fast_path <label> <green|red> <生成变异 yml 的命令…(输出到 stdout)>
expect_fast_path() {
  local label="$1" expected="$2"; shift 2
  local tmp="$LOG_DIR/${label}.yml" reason status
  "$@" >"$tmp"
  cmp -s "$tmp" "$DRIFT" && [ "$expected" = red ] && fail "$label 没改动文件,负控是空转"
  set +e
  reason=$(check_fast_path "$tmp"); status=$?
  set -e
  if [ "$expected" = red ]; then
    [ "$status" -ne 0 ] || fail "$label 应该变红,却通过了"
    echo "PASS[red]: $label → $reason"
  else
    [ "$status" -eq 0 ] || fail "$label 应该通过,实际红了: $reason"
    echo "PASS[green]: $label"
  fi
}

check_fast_path "$DRIFT" >"$LOG_DIR/fast-path.log" \
  || fail "部署失败即刻对账的结构不成立: $(cat "$LOG_DIR/fast-path.log")"
echo 'PASS[green]: 部署失败会立刻触发一次对账,且不成环、不依赖 Mac 醒着'

# 负控:每一处被破坏都必须让上面那条红。绿是廉价的。
expect_fast_path nc-no-trigger red \
  grep -vF "    workflows: [$DEPLOY_NAME, $DEPLOY_UI_NAME]" "$DRIFT"
expect_fast_path nc-no-loop-guard red \
  grep -vF "github.event.workflow_run.event != 'workflow_dispatch'" "$DRIFT"
expect_fast_path nc-cancelled-not-covered red \
  grep -vF "github.event.workflow_run.conclusion == 'cancelled'" "$DRIFT"
expect_fast_path nc-skipped-counted-as-failure red \
  awk '{print} $0=="  audit:"{print "    # github.event.workflow_run.conclusion == '\''skipped'\''"}' "$DRIFT"
expect_fast_path nc-migration-drift-not-skipped red \
  grep -vF "    if: github.event_name != 'workflow_run'" "$DRIFT"
# 反向负控:同样的字样只出现在 migration-drift 块里时必须仍然红 / 仍然绿 ——
# 证明上面各条确实是按 job 块判的,不是整文件 grep 到哪算哪。
mut_guard_to_wrong_job() {
  grep -vF "github.event.workflow_run.event != 'workflow_dispatch'" "$DRIFT" \
    | awk '{print} $0=="  migration-drift:"{print "    # github.event.workflow_run.event != '"'"'workflow_dispatch'"'"'"}'
}
expect_fast_path nc-guard-on-wrong-job red mut_guard_to_wrong_job
expect_fast_path nc-skipped-mention-in-other-job green \
  awk '{print} $0=="  migration-drift:"{print "    # conclusion == '\''skipped'\'' 只是别处的注释,不该影响 audit 的判定"}' "$DRIFT"

echo '控制面回读负控:通过'
