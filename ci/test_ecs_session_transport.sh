#!/usr/bin/env bash
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

proxy=ci/ecs-session-proxy.sh
[ -x "$proxy" ] || fail "$proxy 不存在或不可执行"

tmp_dir=$(mktemp -d)
trap 'find "$tmp_dir" -depth -delete 2>/dev/null || true' EXIT
fake_cli="$tmp_dir/ali-instance-cli"
fake_config="$tmp_dir/config.json"
cat > "$fake_cli" <<'SH'
#!/usr/bin/env bash
printf '%s\n' "$@"
SH
chmod 0700 "$fake_cli"
printf '{}\n' > "$fake_config"

actual=$(ALI_INSTANCE_CLI="$fake_cli" ALIYUN_CONFIG_PATH="$fake_config" \
  ECS_INSTANCE_ID=i-test ECS_REGION=cn-test bash "$proxy")
expected=$'ssh\n--instance\ni-test\n--port\n22\n--region\ncn-test\n--config-path\n'"$fake_config"
[ "$actual" = "$expected" ] || fail "Session Manager ProxyCommand 参数漂移"

# deploy-lib 必须只在显式选择 session-manager 时追加 ProxyCommand；直接模式仍保持原契约。
source ci/deploy-lib.sh
ECS_SSH_TRANSPORT=direct ssh_pin_identity /tmp/key /tmp/known-hosts
[[ " ${ssh_opts[*]} " != *" ProxyCommand="* ]] || fail "direct 模式意外启用 ProxyCommand"
[[ " ${ssh_opts[*]} " == *" -F /dev/null "* ]] \
  && [[ " ${ssh_opts[*]} " == *" IdentitiesOnly=yes "* ]] \
  && [[ " ${ssh_opts[*]} " == *" IdentityAgent=none "* ]] \
  && [[ " ${ssh_opts[*]} " == *" StrictHostKeyChecking=yes "* ]] \
  && [[ " ${ssh_opts[*]} " == *" UserKnownHostsFile=/tmp/known-hosts "* ]] \
  || fail "direct 模式没有继续钉死部署身份与 known_hosts"
ECS_SSH_TRANSPORT=session-manager ssh_pin_identity /tmp/key /tmp/known-hosts
[[ " ${ssh_opts[*]} " == *" ProxyCommand="*"ci/ecs-session-proxy.sh"* ]] \
  || fail "session-manager 模式没有追加受控 ProxyCommand"

# rsync 传 jar 也必须复用 ssh_opts，不能另起一条偷偷直连公网 22 的旁路。
grep -q "printf -v rsync_ssh.*ssh.*ssh_opts" ci/deploy-push.sh \
  || fail "deploy-push 的 rsync 没有从 ssh_opts 生成传输命令"

backend_deploy_step_transport() {
  awk '
    $0 == "      - name: Deploy exact verified SHA and read it back" {
      in_step = 1
      next
    }
    in_step && (/^      - / || /^  [[:alnum:]_-]+:$/) { exit }
    in_step && /^[[:space:]]+ECS_SSH_TRANSPORT:/ {
      count += 1
      value = $2
    }
    END {
      if (count == 1) print value
      else if (count == 0) print "missing"
      else print "duplicate"
    }
  ' "$1"
}

backend_deploy_transport=$(backend_deploy_step_transport .github/workflows/deploy.yml)
[ "$backend_deploy_transport" = direct ] \
  || fail ".github/workflows/deploy.yml 的 backend deploy step 必须恰好一个 direct（当前：$backend_deploy_transport），禁止大 jar 静默回落到 Session Manager"

# 负控：另一个 step 即使写了 direct，也不能替代真正发布 step 的传输合同。
miswired_workflow="$tmp_dir/deploy-miswired.yml"
cat > "$miswired_workflow" <<'YAML'
jobs:
  deploy:
    steps:
      - name: Build deployable jar
        env:
          ECS_SSH_TRANSPORT: direct
      - name: Deploy exact verified SHA and read it back
        run: bash ci/deploy-push.sh "$DEPLOY_SHA"
      - id: diagnostics
        env:
          ECS_SSH_TRANSPORT: direct
        run: echo diagnostics
YAML
[ "$(backend_deploy_step_transport "$miswired_workflow")" = missing ] \
  || fail "非发布 step 的 direct 被误认成 backend 传输合同"

for workflow in \
  .github/workflows/deploy-ui.yml \
  .github/workflows/upload-template-cover-preview.yml
do
  grep -q 'ECS_SSH_TRANSPORT: session-manager' "$workflow" \
    || fail "$workflow 未显式启用 Session Manager"
done

deploy_timeout=$(awk '
  /^  deploy:$/ { in_deploy = 1; next }
  in_deploy && /^  [[:alnum:]_-]+:$/ { exit }
  in_deploy && /^[[:space:]]+timeout-minutes:/ { print $2; exit }
' .github/workflows/deploy.yml)
[ "$deploy_timeout" = 90 ] \
  || fail "deploy job timeout 必须为 90 分钟，覆盖构建、传输、重启与回读（当前：${deploy_timeout:-missing}）"

grep -q 'bash ci/test_ecs_session_transport.sh' .github/workflows/ci.yml \
  || fail "GitHub CI 没有执行 ECS 传输契约"

echo "ECS transport contract: PASS"
