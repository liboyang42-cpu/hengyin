#!/usr/bin/env bash
# 取「版本钉死 + 内容钉死」的外部 CI 工具二进制。
#
# ★ 为什么要有这个(2026-08-29,云效 run 35 实测照出来的两个问题)
#
#   云效的「密钥文件门禁」「SBOM + 依赖扫描」原本各自内联一段 curl,
#   直接从 GitHub Releases 拉 tar.gz、解包、执行。两个毛病:
#
#   ① 【下载即执行,零完整性校验】
#      版本钉死了(GL_VER / GRYPE_VER),但**内容没钉** —— 拿到什么就执行什么。
#      而执行的还是「要扫遍整个仓库、看得见所有密钥」的安全工具。
#      这是典型的供应链敞口:上游被投毒、传输被篡改、CDN 串包,门禁都照跑不误。
#
#   ② 【云效构建集群拉 GitHub Releases 拉不动】
#      run 35 实测:gitleaks 3MB 传了 1.7MB 超时、grype 23MB 传了 2.6MB 超时,
#      各重试 3 次全挂,两个门禁一起红(约 15KB/s)。
#      ⚠️ 门禁本身的行为是**对的** —— 它拒绝静默跳过。病灶在取件路径。
#
#   ⇒ 本脚本把这件事从流水线内联搬进仓库,一次解决两条:
#      · **sha256 钉死**:不管从哪拿,对不上就红,没有回退、没有重试补救。
#      · **来源可切换**:CI_TOOL_MIRROR 指向内网镜像(如 OSS 内网 endpoint)时
#        走镜像,不设则走官方地址。因为有 ① 兜底,换源不降低安全性。
#
# 用法:
#   bash ci/fetch-pinned-tool.sh <工具名> <目标目录>
#   CI_TOOL_MIRROR=https://<bucket>.oss-cn-hangzhou-internal.aliyuncs.com/ci-tools \
#     bash ci/fetch-pinned-tool.sh grype /tmp/bin
#
# 负控自测(必须能红):
#   bash ci/fetch-pinned-tool.sh --selftest
#
# 退出码:0=取到且校验通过   1=任何一步失败(fail-closed)
set -euo pipefail

# ── 清单:工具名|版本|官方下载地址|文件名|sha256|解包出来的成员名 ─────────────
#
# sha256 来源:2026-08-29 从官方 releases 下载,并**逐个对上官方 checksums.txt**
# 才记进来的(不是我自己算完就算数 —— 那样等于给自己下载到的任何东西背书)。
_MANIFEST='
gitleaks|8.21.2|https://github.com/gitleaks/gitleaks/releases/download/v8.21.2/gitleaks_8.21.2_linux_x64.tar.gz|gitleaks_8.21.2_linux_x64.tar.gz|5bc41815076e6ed6ef8fbecc9d9b75bcae31f39029ceb55da08086315316e3ba|gitleaks
grype|0.87.0|https://github.com/anchore/grype/releases/download/v0.87.0/grype_0.87.0_linux_amd64.tar.gz|grype_0.87.0_linux_amd64.tar.gz|be710d15f5477e5c77ce03d14e480263415d7ab135e04b8483663f688823087d|grype
'

_sha256() {
  # macOS 没有 sha256sum,Linux 没有 shasum —— 两边都要能跑(本地自测 + CI)。
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{print $1}'
  else echo "::找不到 sha256sum / shasum —— 无法校验,不放行" >&2; return 1; fi
}

# ── 核心:下载 + 校验 + 解包。selftest 直接打这个函数,不复制判据。 ───────────
fetch_and_verify() {
  local url="$1" want="$2" member="$3" destdir="$4"
  local tmp; tmp=$(mktemp -d)
  # shellcheck disable=SC2064
  trap "rm -rf '$tmp'" RETURN

  local i ok=0
  for i in 1 2 3; do
    # -C - 断点续传:云效那条链是【慢】不是【断】,续传比从头重来有意义。
    if curl -fsSL -C - --max-time 300 -o "$tmp/pkg" "$url" 2>/dev/null; then ok=1; break; fi
    echo "  下载第 $i/3 次失败,重试:$url" >&2
    sleep $((i * 5))
  done
  if [ "$ok" != "1" ]; then
    echo "::下载连续 3 次失败 —— 不静默跳过:$url" >&2
    return 1
  fi

  local got; got=$(_sha256 "$tmp/pkg") || return 1
  if [ "$got" != "$want" ]; then
    # ★ 这里绝不重试、绝不回退到别的源。校验失败不是「网络抖」,
    #   是「拿到的东西不是我要的那个」—— 再拿一次也不该信。
    echo "::sha256 不匹配 —— 拒绝执行" >&2
    echo "   期望 $want" >&2
    echo "   实得 $got" >&2
    echo "   来源 $url" >&2
    return 1
  fi

  mkdir -p "$destdir"
  tar xzf "$tmp/pkg" -C "$destdir" "$member" || {
    echo "::解包失败,取不到成员 $member" >&2; return 1; }
  chmod +x "$destdir/$member"
  echo "✅ $member 已就位($destdir/$member),sha256 已核对" >&2
}

lookup() {
  # 只认清单里有的工具名。未知名字必须红 —— 静默返回空会让调用方拿着空 URL 去 curl,
  # 那就退化成「下载失败」,把「清单写错了」伪装成「网络问题」。
  local name="$1" line
  line=$(printf '%s\n' "$_MANIFEST" | grep "^${name}|" || true)
  if [ -z "$line" ]; then
    echo "::清单里没有工具 '$name' —— 拒绝猜" >&2
    return 1
  fi
  printf '%s' "$line"
}

main() {
  local name="${1:?用法: fetch-pinned-tool.sh <工具名> <目标目录>}"
  local destdir="${2:?用法: fetch-pinned-tool.sh <工具名> <目标目录>}"
  local line; line=$(lookup "$name") || return 1

  local ver url file sha member
  IFS='|' read -r _ ver url file sha member <<<"$line"

  # 镜像优先。⚠️ 换源不放宽校验 —— sha256 一样要过,所以镜像不必被信任。
  if [ -n "${CI_TOOL_MIRROR:-}" ]; then
    url="${CI_TOOL_MIRROR%/}/$file"
    echo "使用镜像源:$url" >&2
  fi
  echo "取 $name v$ver" >&2
  fetch_and_verify "$url" "$sha" "$member" "$destdir"
}

# ── 负控自测:全部走真函数,不内联复制判据 ────────────────────────────────
_SELFTEST_WORK=""
_selftest_cleanup() { [ -n "$_SELFTEST_WORK" ] && rm -rf "$_SELFTEST_WORK"; }

selftest() {
  local fail=0 work
  # ⚠️ EXIT trap 跑在函数作用域之外,不能引用 local 变量(会 unbound variable)。
  work=$(mktemp -d); _SELFTEST_WORK="$work"
  trap _selftest_cleanup EXIT

  # 造一个假 tar.gz 当「上游」,用 file:// 喂进真的 fetch_and_verify。
  mkdir -p "$work/src"
  printf '#!/bin/sh\necho faketool 1.2.3\n' > "$work/src/faketool"
  tar czf "$work/faketool.tar.gz" -C "$work/src" faketool
  local good; good=$(_sha256 "$work/faketool.tar.gz")

  t() {  # t <期望退出码> <说明> <命令...>
    local want="$1" desc="$2"; shift 2
    local got; "$@" >/dev/null 2>&1 && got=0 || got=$?
    if [ "$got" = "$want" ]; then echo "  ✅ $desc(退出码 $got)"
    else echo "  ❌ $desc —— 期望 $want,实得 $got"; fail=1; fi
  }

  echo "== 正控:sha256 对得上 → 取到 =="
  t 0 "正确 sha256 → 成功" \
    fetch_and_verify "file://$work/faketool.tar.gz" "$good" faketool "$work/out"
  if [ -x "$work/out/faketool" ]; then echo "  ✅ 成员真的落到目标目录且可执行"
  else echo "  ❌ 成员没落地"; fail=1; fi

  echo "== 负控 1:sha256 改一位 → 必须红(这条不红,整个脚本没有意义)=="
  # ⚠️ 改末位而不是等长随便换 —— 要的是「确实不同」,并且下面还断言了它真的没落地。
  local bad="${good%?}$( [ "${good: -1}" = "0" ] && echo 1 || echo 0 )"
  t 1 "错误 sha256 → 判红" \
    fetch_and_verify "file://$work/faketool.tar.gz" "$bad" faketool "$work/bad"
  if [ -e "$work/bad/faketool" ]; then
    echo "  ❌ 校验失败了却还是把文件落了地 —— fail-open"; fail=1
  else echo "  ✅ 校验失败时没有落地任何东西"; fi

  echo "== 负控 2:下载不到 → 必须红,不能当成「没问题」 =="
  t 1 "不存在的 URL → 判红" \
    fetch_and_verify "file://$work/不存在.tar.gz" "$good" faketool "$work/nope"

  echo "== 负控 3:包里没有那个成员 → 必须红 =="
  t 1 "成员名不对 → 判红" \
    fetch_and_verify "file://$work/faketool.tar.gz" "$good" 另一个名字 "$work/nomem"

  echo "== 负控 4:清单里没有的工具名 → 必须红,不能猜 =="
  t 1 "未知工具名 → 判红" lookup 根本不存在的工具

  echo "== 结构:清单里每个工具的 sha256 必须是 64 位十六进制 =="
  # 防的是「占位符没换掉」这类事故 —— 那会让校验永远失败或永远通过。
  local n=0 bad_sha=0 line
  while IFS= read -r line; do
    [ -z "$line" ] && continue
    n=$((n + 1))
    local s; s=$(printf '%s' "$line" | cut -d'|' -f5)
    printf '%s' "$s" | grep -qE '^[0-9a-f]{64}$' || { echo "  ❌ sha256 不合法:$line"; bad_sha=1; }
  done <<<"$(printf '%s\n' "$_MANIFEST" | grep '|')"
  if [ "$n" -lt 2 ]; then echo "  ❌ 清单里工具少于 2 个,清单是不是被改坏了"; fail=1
  elif [ "$bad_sha" = "0" ]; then echo "  ✅ $n 个工具的 sha256 都是 64 位十六进制"
  else fail=1; fi

  echo "== 镜像:设了 CI_TOOL_MIRROR 就该去镜像取(且照样校验)=="
  # 用一个必定取不到的镜像地址:若 main 真去了镜像,会红;若它忽略镜像去了 GitHub,
  # 在无网环境同样会红 —— 所以只断言「它打印了镜像地址」这一可观测事实。
  local out
  out=$(CI_TOOL_MIRROR="file://$work/mirror" main gitleaks "$work/m" 2>&1 || true)
  if printf '%s' "$out" | grep -q "使用镜像源:file://$work/mirror/gitleaks_8.21.2_linux_x64.tar.gz"; then
    echo "  ✅ 镜像地址拼接正确且真的走了镜像"
  else
    echo "  ❌ 没走镜像,实际输出:$(printf '%s' "$out" | head -2)"; fail=1
  fi

  [ "$fail" = "0" ] || { echo "自测未通过"; return 1; }
  echo "自测通过"
}

if [ "${1:-}" = "--selftest" ]; then selftest; exit $?; fi
main "$@"
