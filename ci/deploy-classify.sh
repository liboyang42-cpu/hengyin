#!/usr/bin/env bash
# 判定「这次到底该不该部署」,并给出要部署的 SHA。
#
# 抽自 .github/workflows/deploy.yml 的 prepare/classify(2026-08-26),目的是让
# GitHub Actions 与云效 Flow 调同一份判据 —— 见 ci/deploy-lib.sh 顶部说明。
#
# 输出(stdout,key=value,供调用方写进 $GITHUB_OUTPUT 或 source):
#   sha=<要部署的 commit>
#   should_deploy=<true|false>
# 诊断一律走 stderr,别污染上面两行。
#
# 环境变量:
#   EVENT_NAME    workflow_dispatch 时走强制分支;其余留空即可
#   FORCE_DEPLOY  配合 EVENT_NAME=workflow_dispatch,true 表示忽略文件分类
#   ECS_SSH_HOST / ECS_SSH_USER / ECS_SSH_PRIVATE_KEY / ECS_SSH_KNOWN_HOSTS
#
# 用法:bash ci/deploy-classify.sh
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1
. ci/deploy-lib.sh

sha=$(git rev-parse HEAD)
printf 'sha=%s\n' "$sha"

if [ "${EVENT_NAME:-}" = "workflow_dispatch" ] && [ "${FORCE_DEPLOY:-}" = "true" ]; then
  log "workflow_dispatch 且 force_deploy=true,跳过文件分类直接部署"
  printf 'should_deploy=true\n'
  exit 0
fi

# 判据(什么算运行时改动)共用唯一真源 —— drift-audit.yml 的对账也 source 它。
# 各写一份正则的话,两者会在「该不该部署」上给出不同答案,兵底就成了噪音源。
. ci/classify-runtime.sh

# 快路径:本 commit 自身就碰了运行时 ⇒ 必部署,不必问生产,省一次 ssh。
quick_base=$(git rev-parse "$sha^" 2>/dev/null || true)
if [ -n "$quick_base" ] && runtime_changed_between "$quick_base" "$sha"; then
  log "本 commit 自身即含运行时改动,直接部署(无需问生产)"
  printf 'should_deploy=true\n'
  exit 0
fi

# ── 慢路径:base 取「生产 jar 自报的 commit」,而不是 $sha^ ──────────────────
# 为什么(2026-07-16 实测的静默丢部署):concurrency(cancel-in-progress:false)下
# pending 只排得下一个,连着合 PR 时第三个会把第二个挤成 cancelled。若分类只看
# 单 commit 的 diff($sha^),被挤掉那次所带的改动就**永久轮不到部署** ——
# 后续 commit 的 diff 里根本不含它们。当天 #81(安全修复)与 #75 的后端代码
# 因此静默没上生产,而 workflow 一片绿:#62 只改 .gitignore,跳过部署也算 success。
# ★ 即 `success` ≠ 部署了 —— 光看 run 状态发现不了,只有问 jar 自报的 commit 才行。
# ⇒ 用生产实际在跑的 commit 当 base,diff 覆盖「生产落后的全部改动」,
#   中间丢多少次都能在下一次自愈。它也是唯一真相:有人手工换过 jar 它也认。
# 单 commit 看不出运行时改动时才走到这 —— 而那正是丢部署藏身的地方。
#
# ⚠️ 迁云效 Flow 时这段更不能省:Flow 的并发控制行为与 GitHub concurrency 不同(待验证),
#   丢部署的可能性只多不少,而这里是唯一能自愈的地方。
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
cleanup() { rm -f "$key_file" "$known_hosts"; }
trap cleanup EXIT

printf '%s\n' "$ECS_SSH_PRIVATE_KEY" > "$key_file"
printf '%s\n' "$ECS_SSH_KNOWN_HOSTS" > "$known_hosts"
chmod 600 "$key_file" "$known_hosts"

ssh_pin_identity "$key_file" "$known_hosts"

deployed=$(ssh "${ssh_opts[@]}" "$ECS_SSH_USER@$ECS_SSH_HOST" \
  'unzip -p /www/wwwroot/bin/chengyinhub-admin.jar BOOT-INF/classes/git.properties 2>/dev/null | sed -n "s/^git\.commit\.id=//p" | tr -d "\r"' \
  2>/dev/null) || deployed=""

# 兜底方向必须是「拿不到就部署」—— 宁可空跑一次,不可静默漏一次。
if [ -z "$deployed" ]; then
  warn "拿不到生产自报的 commit(ssh 失败?jar 无 git.properties?),保守起见强制部署"
  printf 'should_deploy=true\n'
  exit 0
fi
if ! git cat-file -e "${deployed}^{commit}" 2>/dev/null; then
  warn "生产自报 commit=$deployed 不在本仓历史(force-push?手工换过 jar?),保守起见强制部署"
  printf 'should_deploy=true\n'
  exit 0
fi
if [ "$deployed" = "$sha" ]; then
  log "生产已在跑 $sha,无需重复部署"
  printf 'should_deploy=false\n'
  exit 0
fi

log "base(生产自报)=$deployed"
log "head(本次)     =$sha"
git diff --name-only "$deployed" "$sha" >&2

# 判据同上,来自 ci/classify-runtime.sh(与 drift-audit.yml 的对账同源)。
if runtime_changed_between "$deployed" "$sha"; then
  printf 'should_deploy=true\n'
else
  printf 'should_deploy=false\n'
fi
