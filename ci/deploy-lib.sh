#!/usr/bin/env bash
# 部署链路三个脚本(deploy-classify / verify-built-jar / deploy-push)的共用底座。
#
# 为什么要有这个文件:2026-08-26 把 deploy.yml 的内联 shell 抽成脚本,目的是让
# GitHub Actions 与云效 Flow **调同一份逻辑**。双跑对拍期间各持一份的话必定漂移,
# 而漂移的方向永远是「一边绿一边红,然后按住红的那边不管」。
#
# ⚠️ 抽出来才暴露的跨平台问题:原 deploy.yml 跑在自托管 **macOS** runner 上,
#    云效构建机是 **Linux**。两处的 sha256 工具名不同 —— 直接照搬 `shasum -a 256`
#    到 Flow 上会 command not found,而那一步正是「构建产物 ↔ 生产 jar」的钉死点,
#    它挂掉等于指纹比对整条失效。故在这里统一。
#
# 用法:source ci/deploy-lib.sh

# 诊断信息一律走 stderr —— deploy-classify.sh 的 stdout 是给调用方 eval 的 key=value,
# 混进一行人话就会污染 $GITHUB_OUTPUT。
log()  { printf '%s\n' "$*" >&2; }

# 警告要在 GitHub 上呈现为注解、在别处退化成普通输出。判据用 GITHUB_ACTIONS 而非
# 「有没有 GITHUB_OUTPUT」—— 后者在 workflow_dispatch 之外的某些上下文里也可能缺。
warn() {
  if [ "${GITHUB_ACTIONS:-}" = "true" ]; then
    printf '::warning::%s\n' "$*" >&2
  else
    printf 'WARNING: %s\n' "$*" >&2
  fi
}

# 只打印 error 级别、不退出 —— 给 retry 耗尽这类「调用方还要拿返回码」的场景用。
# 原 deploy.yml 里这几处就是 ::error::,走 warn() 会把排障线索降级成警告。
err() {
  if [ "${GITHUB_ACTIONS:-}" = "true" ]; then
    printf '::error::%s\n' "$*" >&2
  else
    printf 'ERROR: %s\n' "$*" >&2
  fi
}

die() {
  if [ "${GITHUB_ACTIONS:-}" = "true" ]; then
    printf '::error::%s\n' "$*" >&2
  else
    printf 'ERROR: %s\n' "$*" >&2
  fi
  exit 1
}

# macOS 只有 shasum,Linux 只有 sha256sum(coreutils)。两边都可能缺另一个。
sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    die "既没有 sha256sum 也没有 shasum,无法计算构建产物指纹 —— 指纹比对是部署链路的钉死点,不允许跳过"
  fi
}

# 从 jar 里读 git-commit-id-plugin 打进去的 commit。读不到返回空串,由调用方决定怎么办。
# ★ 这是「制品自报出身」的唯一来源:观测要问生产,不能问我们自己刚写进去的值。
jar_commit_id() {
  unzip -p "$1" BOOT-INF/classes/git.properties 2>/dev/null \
    | sed -n 's/^git\.commit\.id=//p' | tr -d '\r'
}

# 把 ssh 身份钉死成「只有这一把部署密钥」。
#
# ★ 为什么必须钉死(2026-07-16 实证):自托管 runner 以桌面用户身份跑,会读到用户的
#   ~/.ssh/config(`Host * / UseKeychain yes / IdentityFile ~/.ssh/id_ed25519`),
#   于是 ssh 顺手提供个人密钥。当时部署密钥其实从没装到服务器上(ssh-copy-id 报
#   「already exist」是假阳性),而部署照样绿 —— 走的是 Keychain 解锁的个人密钥。
#   Keychain 一锁就 Permission denied。即:成功靠一把不该参与的钥匙撑着,
#   而它什么时候撤走由屏保决定。这种「意外成功」比失败难查得多。
#
#   -F /dev/null       不读用户的 ~/.ssh/config
#   IdentityAgent=none 不借用户 agent / Keychain 里的任何钥匙
#   IdentitiesOnly=yes 只用 -i 指定的这一把
#   ⇒ 要么用部署密钥成功,要么失败。不再有第三种。
#
# 用法:ssh_pin_identity <key_file> <known_hosts>  → 结果写进全局数组 ssh_opts
#
# ⚠️ 数组名必须叫 ssh_opts:ci/test_preflight_h8_schema.sh 的 PREFLIGHT_CALL 把整行
#   字面锚死(含 "${ssh_opts[@]}")。改名会让那道 12 条负控的闸静默失去目标。
ssh_pin_identity() {
  ssh_opts=(-F /dev/null -i "$1"
            -o IdentitiesOnly=yes -o IdentityAgent=none
            -o BatchMode=yes -o StrictHostKeyChecking=yes
            -o UserKnownHostsFile="$2" -o ConnectTimeout=20)

  case "${ECS_SSH_TRANSPORT:-direct}" in
    direct) ;;
    session-manager)
      local proxy_script
      proxy_script="$(git rev-parse --show-toplevel)/ci/ecs-session-proxy.sh"
      [ -x "$proxy_script" ] || die "Session Manager ProxyCommand 不可执行：$proxy_script"
      case "$proxy_script" in
        *[[:space:]]*) die "ProxyCommand 路径含空白，ssh 无法安全解析：$proxy_script" ;;
      esac
      ssh_opts+=(-o "ProxyCommand=$proxy_script")
      ;;
    *) die "不支持的 ECS_SSH_TRANSPORT=${ECS_SSH_TRANSPORT}" ;;
  esac
}

# ── ssh 重试:只对 ssh 自身的连接/认证失败(exit 255)重试 ────────────────────
# 冲的是「生产 22 端口长期挨扫」:2026-07-16 实测当天 3425 次认证失败、峰值一分钟 35 次
# 连接,而 sshd MaxStartups=10:30:100 ⇒ 未认证连接超 10 个时**随机拒 30%**。
# 身份钉死之后认证不再看运气,但连接槽被占满仍会偶发被拒 —— 那不是配置错,是基建在挨打。
#
# ⚠️ 只重试 255。远端脚本失败(其它 exit code)必须原样抛出:重试它会重复执行
#    (比如再重启一次服务),还会把真实错误掩盖成偶发。
retry_ssh() {
  local n=0 rc
  while :; do
    # 必须用 else 取 $?:`if cmd; then ...; fi` 在条件为假且无 else 时整个 if 返回 0,
    # 写成 `if cmd; then return 0; fi; rc=$?` 会让 rc 恒为 0 —— 把失败当成功吞掉。
    if "$@"; then return 0; else rc=$?; fi
    if [ "$rc" -ne 255 ]; then return "$rc"; fi
    n=$((n + 1))
    if [ "$n" -ge 3 ]; then
      err "ssh 连接/认证连续 3 次失败(exit 255)。若是 Permission denied,先查部署密钥在不在服务器 authorized_keys 里,再怀疑 22 端口被扫满"
      return 255
    fi
    log "ssh 连接失败(exit 255),第 ${n}/3 次重试"
    sleep $((n * 10))
  done
}

# 带 stdin 的 ssh 不能直接套 retry_ssh:重试时 stdin 已被消费尽,
# 第二次会把空脚本喂给 bash -s(静默什么都不做)。故每次重试都从文件重开。
retry_ssh_stdin() {
  local input=$1; shift
  local n=0 rc
  while :; do
    if "$@" < "$input"; then return 0; else rc=$?; fi
    if [ "$rc" -ne 255 ]; then return "$rc"; fi
    n=$((n + 1))
    if [ "$n" -ge 3 ]; then
      err "ssh 连接/认证连续 3 次失败(exit 255)"
      return 255
    fi
    log "ssh 连接失败(exit 255),第 ${n}/3 次重试"
    sleep $((n * 10))
  done
}
