#!/usr/bin/env bash
# 门禁脚本必须与 CWD 无关:从仓库根跑和从子目录跑,必须给出【同一个结论】。
#
# 起因(2026-08-10 实测,顺手把传闻订正掉):
#   传闻说 ci/xcx-check.sh 从 chengyinhub-xcx/ 跑会「静默假绿」。实测不是——
#     cd chengyinhub-xcx && bash ci/xcx-check.sh     → exit 127(bash 找不到脚本)
#     cd chengyinhub-xcx && bash ../ci/xcx-check.sh  → exit 1「找不到 chengyinhub-xcx」
#   两条都是硬红,没有假绿。真毛病是「调用方每次都得记得先 cd 仓库根」,忘了就得到一个
#   与代码质量无关的红 —— 噪音红看多了就会被当成环境问题挥手放过,这才是它的危害路径。
#   所以修法是让脚本自己回根(cd "$(git rev-parse --show-toplevel)"),而不是加一句警告。
#
# 这一条守的是那个 cd 不被人删掉、也不被新门禁漏掉。绿是廉价的,能变红才是价值:
#   bash ci/test_cwd_independence.sh --selftest   # 负控:证明它真能判红
#   bash ci/test_cwd_independence.sh              # 正式:扫全部门禁
set -uo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1
ROOT="$PWD"
SUB="$ROOT/chengyinhub-xcx"   # 随便挑一个真实存在的子目录当「不在根」的现场

fail=0

# 门禁不止 bash 一种:U1/U5 两道闸是 node 写的(纯 node、零三方依赖)。
# 按扩展名选解释器,别把 .js 丢给 bash —— 那会得到一个与 CWD 无关的 exit 2,
# 两处「一致地失败」⇒ 恒真通过 ⇒ 比不检查更坏。
run_gate() {
  case "$1" in
    *.js) node "$1" ;;
    *.py) python3 "$1" ;;
    *)    bash "$1" ;;
  esac
}

# 行为核:同一个脚本在两处各跑一次,exit code 必须相同。
# 这是唯一能证伪的观测 —— 只看源码有没有那行 cd,注释掉一行就骗过去了。
check_behaviour() {
  local abs=$1 label=$2 a b
  ( cd "$ROOT" && run_gate "$abs" >/dev/null 2>&1 ); a=$?
  ( cd "$SUB"  && run_gate "$abs" >/dev/null 2>&1 ); b=$?
  if [ "$a" -ne "$b" ]; then
    echo "FAIL(行为):$label —— 仓库根 exit=$a,子目录 exit=$b,结论随 CWD 变"
    return 1
  fi
  echo "  ok(行为)$label(两处同为 exit=$a)"
  return 0
}

# 静态核:只断言「回根那一行还在」,弱于行为核,能进的仅限两类 ——
#   ① 跑一次要几分钟 / 要 MySQL / 要 npm build,不适合在门禁里真跑两遍;
#   ② 要 ssh 生产或要构建产物,在门禁环境里两处会【一致地失败】⇒ exit code 相同
#     ⇒ 行为核恒真通过,比不检查更坏(deploy-* 三个属这类)。
# 除这两类外别往里加 —— 图省事把能跑的门禁挪进静态核,等于自愿把行为核换成 grep。
#
# ⚠️ 静态核本身必须有负控,否则「弱」会一路弱到恒真:见 --selftest 的负控 5~7。
check_static() {
  local rel=$1
  if ! grep -q 'cd "$(git rev-parse --show-toplevel)"' "$ROOT/$rel"; then
    echo "FAIL(静态):$rel 缺回仓库根那一行,从子目录跑会得到不同结论"
    return 1
  fi
  echo "  ok(静态)$rel 带回根语句"
  return 0
}

if [ "${1:-}" = "--selftest" ]; then
  # 负控:造一个真的 CWD 依赖脚本,check_behaviour 必须判红;补上回根语句后必须转绿。
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT
  printf '#!/usr/bin/env bash\nset -euo pipefail\n[ -d chengyinhub-xcx ] || { echo 找不到; exit 1; }\n' > "$tmp/bad.sh"
  printf '#!/usr/bin/env bash\nset -euo pipefail\ncd "$(git rev-parse --show-toplevel)" || exit 1\n[ -d chengyinhub-xcx ] || { echo 找不到; exit 1; }\n' > "$tmp/good.sh"

  echo "== 负控 1:CWD 依赖的脚本必须被判红 =="
  if check_behaviour "$tmp/bad.sh" "变异体 bad.sh" >/dev/null 2>&1; then
    echo "FAIL:变异体没被判红 —— 这个检查器是恒真断言,修它之前别信它"
    exit 1
  fi
  echo "  ok 变异体被判红"

  echo "== 负控 2:补上回根语句后必须转绿 =="
  if ! check_behaviour "$tmp/good.sh" "变异体 good.sh"; then
    echo "FAIL:修好的脚本被误判红 —— 检查器会制造假故障"
    exit 1
  fi

  echo "== 负控 3(node):真的依赖 CWD 的 node 门禁必须被判红 =="
  # U1/U5 两道闸是 node 写的。只加 bash 变异体的话,「按扩展名选解释器」这段本身没有负控:
  # 写错了会把 .js 丢给 bash,两处一致地 exit 2 ⇒ 恒真通过。
  printf '#!/usr/bin/env node\nif (!require("fs").existsSync("chengyinhub-xcx")) process.exit(1);\n' > "$tmp/bad.js"
  printf '#!/usr/bin/env node\nconst {execFileSync}=require("child_process");\nprocess.chdir(execFileSync("git",["rev-parse","--show-toplevel"],{encoding:"utf8"}).trim());\nif (!require("fs").existsSync("chengyinhub-xcx")) process.exit(1);\n' > "$tmp/good.js"

  if check_behaviour "$tmp/bad.js" "变异体 bad.js" >/dev/null 2>&1; then
    echo "FAIL:node 变异体没被判红 —— 要么 run_gate 没按扩展名分流(.js 被丢给 bash,两处一致失败=恒真通过)"
    exit 1
  fi
  echo "  ok node 变异体被判红"

  echo "== 负控 4(node):补上回根语句后必须转绿 =="
  if ! check_behaviour "$tmp/good.js" "变异体 good.js"; then
    echo "FAIL:修好的 node 脚本被误判红 —— 检查器会制造假故障"
    exit 1
  fi

  # 负控 6/7 造在仓库根下:check_static 用的是 "$ROOT/$rel",只吃相对路径。
  static_box="$ROOT/.cwd-gate-selftest.$$"
  mkdir -p "$static_box"
  trap 'rm -rf "$tmp" "$static_box"' EXIT

  echo "== 负控 6:静态核必须对「真存在但缺回根语句」的文件判红 =="
  # 负控 5 只证明「读不到的文件不报绿」,证明不了「读得到但缺那行时会红」——
  # 后者才是静态核的本职。清单从 4 涨到 7 之后,这条尤其不能少。
  printf '#!/usr/bin/env bash\nset -euo pipefail\necho no-chdir-here\n' > "$static_box/bad_static.sh"
  if check_static "${static_box#"$ROOT/"}/bad_static.sh" >/dev/null 2>&1; then
    echo "FAIL:静态核对缺回根语句的文件报绿 —— 它是恒真断言,修它之前别信它"
    exit 1
  fi
  echo "  ok 缺回根语句被判红"

  echo "== 负控 7:补上回根语句后静态核必须转绿 =="
  printf '#!/usr/bin/env bash\nset -euo pipefail\ncd "$(git rev-parse --show-toplevel)" || exit 1\n' > "$static_box/good_static.sh"
  if ! check_static "${static_box#"$ROOT/"}/good_static.sh"; then
    echo "FAIL:静态核对带回根语句的文件误判红 —— 会制造假故障"
    exit 1
  fi

  echo "== 负控 5:静态核必须认出缺失 =="
  if check_static "ci/../$(basename "$tmp")/nonexistent" >/dev/null 2>&1; then
    echo "FAIL:静态核对不存在的文件也报绿"
    exit 1
  fi
  echo "  ok 静态核不会对读不到的文件报绿"

  echo "CWD 无关性门禁 --selftest:通过(bash 与 node 两种门禁都能判红也能判绿)"
  exit 0
fi

echo "== 行为核:根 / 子目录 exit code 必须一致 =="
for rel in ci/migration-layout-check.sh ci/migration-safety-check.sh ci/ci-config-guard.sh \
           ci/api-existence-gate.js ci/silent-error-gate.js; do
  check_behaviour "$ROOT/$rel" "$rel" || fail=1
done

echo "== 静态核:跑一次太贵的门禁,只验回根语句还在 =="
# deploy-* 三个脚本走静态核而非行为核:它们要 ssh 生产 / 要构建产物,在门禁环境里
# 两处会「一致地失败」⇒ exit code 相同 ⇒ 恒真通过,比不检查更坏。
for rel in ci/xcx-check.sh ci/backend-test.sh ci/funds-mysql-it-gate.sh ci/ui-build.sh \
           ci/deploy-classify.sh ci/verify-built-jar.sh ci/deploy-push.sh; do
  check_static "$rel" || fail=1
done

if [ "$fail" -ne 0 ]; then echo "CWD 无关性门禁:未通过"; exit 1; fi
echo "CWD 无关性门禁:通过"
