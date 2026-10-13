#!/usr/bin/env bash
# 把已验证的 jar 送上生产,并向生产回读「你现在到底在跑什么」。
#
# 抽自 .github/workflows/deploy.yml 的「Deploy exact verified SHA and read it back」(2026-08-26)。
#
# 链路顺序是有讲究的,别调换:
#   1) preflight-h8-schema.sh  换 jar 之前先问生产库,迁移落库了没有
#   2) rsync 上传到 staging    带 SHA 后缀,重试不会把不同构建的半包拼进来
#   3) deploy-restart.sh       原子替换 + 重启
#   4) verify-deployed.sh      回读三条:指纹 / 进程唯一 / 启动时间晚于本次部署
#   5) deployed-sha            存档(是档案,不是证据)
#
# 用法:bash ci/deploy-push.sh <要部署的 commit sha>
# 环境变量:ECS_SSH_HOST / ECS_SSH_USER / ECS_SSH_PRIVATE_KEY / ECS_SSH_KNOWN_HOSTS
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1
. ci/deploy-lib.sh

DEPLOY_SHA="${1:?缺少要部署的 commit sha}"
JAR=chengyinhub-admin/target/chengyinhub-admin.jar

# secret 必须当场核,别让「没配 / 改名了」伪装成「问不到生产」——后者会走兜底
# should_deploy=true,把一个明确的配置故障说成一次可疑的部署。
#
# ⚠️ 不能用 ${VAR:?}:那是参数展开错误,实测会被下面的 trap cleanup EXIT 吞成 exit 0
#   (trap handler 最后一条 rm 成功返回 0),脚本静默结束且不输出 should_deploy,
#   调用方拿到空值 ⇒ if 判假 ⇒ 静默跳过部署。故显式检查,并排在 trap 之前。
for _v in ECS_SSH_HOST ECS_SSH_USER ECS_SSH_PRIVATE_KEY ECS_SSH_KNOWN_HOSTS; do
  eval "_val=\${$_v:-}"
  [ -n "$_val" ] || die "$_v 未设置或为空 —— secret 没配或改名了"
done

umask 077
key_file=$(mktemp)
known_hosts=$(mktemp)
# sha_file 在最后一步才建;先声明并纳入 cleanup,否则那步一失败 set -e 就跳过 rm。
sha_file=""
cleanup() { rm -f "$key_file" "$known_hosts" "$sha_file"; }
trap cleanup EXIT

printf '%s\n' "$ECS_SSH_PRIVATE_KEY" > "$key_file"
printf '%s\n' "$ECS_SSH_KNOWN_HOSTS" > "$known_hosts"
chmod 600 "$key_file" "$known_hosts"

target="$ECS_SSH_USER@$ECS_SSH_HOST"
ssh_pin_identity "$key_file" "$known_hosts"

# rsync 的 -e 只接收一个 shell 命令字符串。由 ssh_opts 生成，确保身份、known-hosts
# 与 Session Manager ProxyCommand 都和下面的 ssh 调用完全相同，不另留公网 22 旁路。
ssh_opts+=(-o ServerAliveInterval=15 -o ServerAliveCountMax=3)
printf -v rsync_ssh '%q ' ssh "${ssh_opts[@]}"
rsync_ssh="${rsync_ssh% }"

# scp/SFTP 在大 jar 传输中曾经保持 ESTABLISHED 却不再产生字节进度,ConnectTimeout
# 管不到这种已连上的空转。rsync 的 I/O timeout 能让它退出,--append 只复用同 SHA
# staging 文件的完整前缀,重试不会把不同构建的半包拼进来。
retry_jar_upload() {
  local stage=$1
  local n=0 rc
  while :; do
    if rsync --partial --append --timeout=60 -e "$rsync_ssh" "$JAR" "$target:$stage"; then
      return 0
    else
      rc=$?
    fi
    if [ "$rc" -ne 30 ] && [ "$rc" -ne 255 ]; then return "$rc"; fi
    n=$((n + 1))
    if [ "$n" -ge 3 ]; then
      warn "jar 传输连续 3 次超时/连接失败(exit $rc),未替换生产 jar"
      return "$rc"
    fi
    log "jar 传输无 I/O 进度或连接失败(exit $rc),第 ${n}/3 次重试"
    sleep $((n * 10))
  done
}

# ★ 换 jar 之前先问生产库:H8 两份迁移落库了没有。
# 「合并即部署 jar」是自动的,而 chengyinhub-admin/sql/** 落库是人工步骤 ——
# 顺序一反,活动线支付回调会因 creator_rate 仍非零被写侧闸整笔回滚(微信已扣款、
# 订单没成),H8 专用积分语句则 ERROR 1054。文件分类不知道迁移跑没跑,
# 此前这条链路 100% 靠人。本步是它唯一的机器观测点。
# ⚠️ 必须排在上传/重启之前:不过就在这里停,生产 jar 一个字节都没被动过,旧进程继续服务。
#   契约见 ci/test_preflight_h8_schema.sh。
retry_ssh_stdin ci/preflight-h8-schema.sh ssh "${ssh_opts[@]}" "$target" 'bash -s'

# 部署起点取服务器时钟,避免构建机与 ECS 的时钟偏移把"进程是新起的"判错。
deploy_start=$(retry_ssh ssh "${ssh_opts[@]}" "$target" 'date +%s')
digest=$(sha256_of "$JAR")

stage_jar="/www/wwwroot/bin/chengyinhub-admin.jar.new.${DEPLOY_SHA}"
retry_jar_upload "$stage_jar"
retry_ssh_stdin ci/deploy-restart.sh ssh "${ssh_opts[@]}" "$target" \
  "bash -s -- '$stage_jar'"

# 观测:向生产回读"你在跑什么",而不是回读我们自己刚写进去的值。
# 指纹对不上、进程不唯一、或进程比本次部署还老 —— 任一条都会让这里变红。
retry_ssh_stdin ci/verify-deployed.sh ssh "${ssh_opts[@]}" "$target" \
  "bash -s -- /www/wwwroot/bin/chengyinhub-admin.jar '$deploy_start' '$digest'"

# 存档:人工排查时能一眼看到"这个 jar 是哪个 commit"。
# 它是档案,不是证据 —— 真凭据是 jar 里 git-commit-id 打进去的 git.properties。
sha_file=$(mktemp)
printf '%s\n' "$DEPLOY_SHA" > "$sha_file"
retry_ssh_stdin "$sha_file" ssh "${ssh_opts[@]}" "$target" \
  'cat > /www/wwwroot/bin/chengyinhub-admin.deployed-sha'

log "Production verified: $DEPLOY_SHA (jar sha256 $digest)"
