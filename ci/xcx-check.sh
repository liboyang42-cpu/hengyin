#!/usr/bin/env bash
# 小程序前端静态门:所有 .js 语法 + 所有 .json 可解析。CI(云效 Flow)与本地共用。
# 用法:bash ci/xcx-check.sh   (从任何目录执行皆可,脚本自己回到仓库根)
set -euo pipefail

# 全脚本的路径都是仓库根相对路径。不回根的话,从子目录跑会停在下面那句
# 「找不到 chengyinhub-xcx」exit 1 —— 红是红,但调用方每次都得记得先 cd。
# 回执见 ci/test_cwd_independence.sh(负控:根/子目录必须给同一个 exit code)。
cd "$(git rev-parse --show-toplevel)" || exit 1

XCX_DIR="chengyinhub-xcx"
[ -d "$XCX_DIR" ] || { echo "找不到 $XCX_DIR"; exit 1; }

fail=0

# 失败点名:笼统一句 exit 1 会逼着人回头翻上百行日志找是哪条红的。section() 记下当前子门禁名,
# mark_fail() 把它记进名册,结尾一次性列出来 —— 「哪一条红的」不该靠人去翻。
# 逐条 file:line 明细仍然由各子门禁自己打印(U2/U3/U4 都带 file:line + 原因)。
CURRENT_SECTION="(启动)"
FAILED_GATES=""
section() { CURRENT_SECTION="$1"; echo "== $1 =="; }
mark_fail() { FAILED_GATES="${FAILED_GATES}${CURRENT_SECTION}${1:+ · $1}
"; fail=1; }

section "unit test glob/哨兵文件盘点"
node ci/xcx-unit-manifest.js --selftest || mark_fail 自证

section "JS 语法检查(node --check)"
while IFS= read -r f; do
  if ! node --check "$f" 2>/tmp/xcx_js_err; then
    echo "FAIL(JS): $f"; sed 's/^/    /' /tmp/xcx_js_err; mark_fail
  fi
done < <(find "$XCX_DIR" -type f -name '*.js' \
          -not -path '*/node_modules/*' -not -path '*/miniprogram_npm/*')

section "JSON 可解析检查"
while IFS= read -r f; do
  if ! node -e "JSON.parse(require('fs').readFileSync('$f','utf8'))" 2>/tmp/xcx_json_err; then
    echo "FAIL(JSON): $f"; sed 's/^/    /' /tmp/xcx_json_err; mark_fail
  fi
done < <(find "$XCX_DIR" -type f -name '*.json' \
          -not -path '*/node_modules/*' -not -path '*/miniprogram_npm/*')

section "JS 作用域/解构导出门禁(运行时 ReferenceError 前置阻断)"
node --expose-internals "$XCX_DIR/scripts/js-scope-lint.js" --selftest || mark_fail 自证
if ! node --expose-internals "$XCX_DIR/scripts/js-scope-lint.js"; then
  mark_fail
fi

section "app.json 主包页面约束检查"
if ! node <<'NODE'
const fs = require('fs');
const app = JSON.parse(fs.readFileSync('chengyinhub-xcx/app.json', 'utf8'));
const mainPages = new Set(app.pages || []);
const packageRoots = (app.subPackages || []).map(pkg => pkg.root);
const tabPages = ((app.tabBar || {}).list || []).map(item => item.pagePath);
const tabOutsideMain = tabPages.filter(page => !mainPages.has(page));
const mainInsideSubPackage = [...mainPages].filter(page => packageRoots.some(root => page === root || page.startsWith(`${root}/`)));

if (tabOutsideMain.length || mainInsideSubPackage.length) {
  if (tabOutsideMain.length) console.error(`tabBar 页面必须属于主包: ${tabOutsideMain.join(', ')}`);
  if (mainInsideSubPackage.length) console.error(`主包页面不能同时属于分包: ${mainInsideSubPackage.join(', ')}`);
  process.exit(1);
}
NODE
then
  mark_fail
fi

section "微信官方隐私接口声明与授权链源码检查"
if ! npm --prefix "$XCX_DIR" run --silent verify:wechat-release; then
  mark_fail
fi

section "模板污染检查"
if rg -n '</?(content|invoke)>' "$XCX_DIR" --glob '*.{wxml,wxss,js,json}'; then
  echo "FAIL:发现工具协议标签混入小程序源码"
  mark_fail
fi

section "DS 硬编码防回潮门(比对 GitHub master 以来的 wxss 新增行)"
bash "$XCX_DIR/scripts/ds-hardcode-gate-selftest.sh" || mark_fail 自证   # 先负控:检查器能判红也能判绿
if ! bash "$XCX_DIR/scripts/ds-hardcode-gate.sh" --ci; then
  mark_fail
fi

section "未定义 token 门禁(var(--cy-x) 取不到值时整条声明静默作废)"
# ⚠️ 不接管道:`cmd | head` 拿到的是 head 的退出码,门禁会恒绿。
node "$XCX_DIR/scripts/undefined-token-gate.js" --selftest || mark_fail 自证   # 先负控:检查器能判红也能判绿
if ! node "$XCX_DIR/scripts/undefined-token-gate.js"; then
  mark_fail
fi

section "WXSS 选择器门禁(拦通配符 * —— 整包编译阻断,wcsc 上不了 ubuntu 故静态扫)"
node "$XCX_DIR/scripts/wxss-selector-lint.js" --selftest || mark_fail 自证   # 先负控:检查器能判红也能判绿
if ! node "$XCX_DIR/scripts/wxss-selector-lint.js"; then
  mark_fail
fi

section "组件 WXSS 选择器门禁(禁止 tag/ID/attribute selector)"
node "$XCX_DIR/scripts/component-wxss-selector-lint.js" --selftest || mark_fail 自证
if ! node "$XCX_DIR/scripts/component-wxss-selector-lint.js"; then
  mark_fail
fi

section "button reset 特异度门禁(全局 button 复位不许高过单类,否则页面/组件里的背景被静默吃掉)"
node "$XCX_DIR/scripts/button-reset-specificity-lint.js" --selftest || mark_fail 自证
if ! node "$XCX_DIR/scripts/button-reset-specificity-lint.js"; then
  mark_fail
fi

section "WXML 条件链门禁(wx:elif / wx:else 必须紧随同层条件分支)"
node "$XCX_DIR/scripts/wxml-conditional-lint.js" --selftest || mark_fail 自证
if ! node "$XCX_DIR/scripts/wxml-conditional-lint.js"; then
  mark_fail
fi

section "页面根主题门禁(玩家 dark / 商家 merchant / 共享页显式双主题)"
node "$XCX_DIR/scripts/ui-theme-unit-lint.js" --selftest || mark_fail 自证
if ! node "$XCX_DIR/scripts/ui-theme-unit-lint.js" --theme; then
  mark_fail
fi

section "WXML 孤立单位门禁(单位前插值必须有 || 兜底)"
if ! node "$XCX_DIR/scripts/ui-theme-unit-lint.js" --units; then
  mark_fail
fi

section "cy-sheet 属性契约门禁(全站调用点传的属性,cy-sheet 必须真的声明过)"
node "$XCX_DIR/scripts/cy-sheet-attr-lint.js" --selftest || mark_fail 自证
if ! node "$XCX_DIR/scripts/cy-sheet-attr-lint.js"; then
  mark_fail
fi

section "全组件属性契约门禁(全部 98 个 cy 组件:调用点传的属性必须真的声明过)"
node "$XCX_DIR/scripts/component-attr-lint.js" --selftest || mark_fail 自证
if ! node "$XCX_DIR/scripts/component-attr-lint.js"; then
  mark_fail
fi

section "组件/资源路径门禁(usingComponents 要能解析;跨包组件须合法异步占位;主包不许引分包资源;分包之间不许互引资源)"
# 2026-09-04 加:把 11 个 club-* 组件从主包下沉到分包时,组件内部那些 ../scene-sheet/index
# 相对路径全部指错了,而**所有既有门禁都是绿的** —— 只有微信编译器在 upload 时才报
# 「路径下未找到组件」,而 upload 本身还 exit 0。这道门把它提前到本地。
# 2026-09-16 加资源引用判据:wxml src/url、wxss url()/@import、js require 与资源字符串、
# tabBar 图标 —— 只覆盖过 usingComponents,post-card 引分包图片就是这么漏到真机的。
# 2026-09-16 同轮补:分包之间也不许互引资源(merchant 引 topic 图标),基线 0 条,新增即红。
node "$XCX_DIR/scripts/component-path-resolve-lint.js" --selftest || mark_fail 自证
if ! node "$XCX_DIR/scripts/component-path-resolve-lint.js"; then
  mark_fail
fi

section "cy-page-title 双重缩进门禁(容器已有横向 padding 时必须传 flush)"
(cd "$XCX_DIR" && python3 scripts/check-page-title-flush.py --selftest) || mark_fail 自证   # 先负控
title_flush_out="$(cd "$XCX_DIR" && python3 scripts/check-page-title-flush.py)"
echo "$title_flush_out"
if ! echo "$title_flush_out" | grep -q '^共 0 页'; then
  echo "FAIL:存在标题双重缩进,给对应 cy-page-title 加 flush=\"{{true}}\""
  mark_fail
fi

section "死链按钮门禁(wxml 的 bind*/catch* 绑定必须在同目录 js 里真有对应方法)"
node "$XCX_DIR/scripts/wxml-handler-lint.js" --selftest || mark_fail 自证
if ! node "$XCX_DIR/scripts/wxml-handler-lint.js"; then
  mark_fail
fi

section "孤儿 handler 门禁(js 里 on*/go*/tap*/handle* 方法必须真有人调:wxml 绑定或 js 调用)"
node "$XCX_DIR/scripts/js-orphan-handler-lint.js" --selftest || mark_fail 自证   # 先负控:能判红也能判绿
if ! node "$XCX_DIR/scripts/js-orphan-handler-lint.js"; then
  mark_fail
fi

section "死数据字段门禁(setData 的顶层字段必须真被 wxml 渲染,只进无障碍属性不算)"
node "$XCX_DIR/scripts/setdata-dead-field-lint.js" --selftest || mark_fail 自证   # 先负控:五类判据各有红绿
if ! node "$XCX_DIR/scripts/setdata-dead-field-lint.js"; then
  mark_fail
fi

section "无障碍:可点击元素必须有可读名称(读屏器念得出来)"
# 判据不是「有没有 aria-label」,而是读屏器能否说出这是什么:
# aria-label 非空 / 子树有文字 / 自定义组件的 title|label|cta|text 非空,三者有其一即可。
# ⚠️ 只 grep aria-label 会大量过报(255 个 aria-role=button 里多数旁边本就有文字)。
node "$XCX_DIR/scripts/a11y-tappable-name-lint.js" --selftest || mark_fail 自证   # 先负控:三条假保证陷阱
if ! node "$XCX_DIR/scripts/a11y-tappable-name-lint.js"; then
  mark_fail
fi

section "无障碍:可点击元素的命中区不得小于 44px(X12)"
# 现有 DS 闸(ds-hardcode-gate.sh / ui-spec-audit.py / _audit_spec_compliance.js)
# 管的全是颜色与字号 token,没有一条管 width/height —— 于是 17 个绑了 tap 的元素
# 声明尺寸小于 88rpx,最小的 .del 只有 24×24rpx(12×12px),其中好几个是删除类操作。
# 判据刻意保守:只在同一条 class 规则里同时写了 width 和 height、且没有
# padding/min-* 时才判(写了 padding 就静态算不准,算了就是猜)。例外走 ds-ok="理由"。
node "$XCX_DIR/scripts/a11y-tap-target-lint.js" --selftest || mark_fail 自证   # 先负控:padding/单维/百分比/后代选择器/空 ds-ok 五类假保证陷阱
if ! node "$XCX_DIR/scripts/a11y-tap-target-lint.js"; then
  mark_fail
fi

section "下拉刷新必须真能触发(抓「声称有、实际死」)"
# 三类:A 页面主体是整屏 scroll-view ⇒ 页面级下拉刷新永不触发(实测 merchant/index 就是这样,
# 声明了却从来没生效过);B 开了却没 handler;C 有 handler 但从不 stopPullDownRefresh ⇒ 转圈不停。
node "$XCX_DIR/scripts/pulldown-refresh-lint.js" --selftest || mark_fail 自证   # 先负控:含横滑/非满屏两条误报陷阱
if ! node "$XCX_DIR/scripts/pulldown-refresh-lint.js"; then
  mark_fail
fi

section "固定底栏必须自带背景(内容不许从底栏底下透出来)"
# 2026-09-19 端到端审查 #28 接电:这条门禁写完一直没通电,五处历史告警逐条读过 ——
# 全是「整屏层」被 fixed+bottom:0 的尺子扫进来,已按本仓口径进显式白名单(理由写在脚本里)。
# 从此它常驻绿,新增的真缺背景底栏才会红。
node "$XCX_DIR/scripts/sticky-bottom-bar-gate.js" --selftest || mark_fail 自证   # 先负控:sticky/fixed 无背景各一条
if ! node "$XCX_DIR/scripts/sticky-bottom-bar-gate.js"; then
  mark_fail
fi

section "截图矩阵体检(双向:矩阵→页面 与 页面→矩阵,外加 blocked 棘轮)"
# ⚠️ 2026-08-18 接进来的。此前 scripts/shot-matrix.js 的自检**零 CI 引用**,
#    而它在 master 上就是红的(STATS 写 247/17/230,实际 245/19/226)——
#    于是里面那个 2026-08-08 抓到过 7 条退役页的死路由体检等于没通电。
#    「测试存在但从没被跑过」在本仓是有案底的血案:放进仓库不算数,被点名跑才算。
if ! node "$XCX_DIR/scripts/shot-matrix.js" > /tmp/xcx_shot_matrix.out 2>&1; then
  tail -25 /tmp/xcx_shot_matrix.out
  mark_fail
fi

section "截图覆盖率门禁(app.json 每条路由都要有非 blocked 的矩阵行,或带替代验证的显式豁免)"
node "$XCX_DIR/scripts/uiaudit/coverage-gate.js" --selftest || mark_fail 自证   # 先负控:两个方向各有一条
if ! node "$XCX_DIR/scripts/uiaudit/coverage-gate.js"; then
  mark_fail
fi

section "全量可点击控件动作台账(控件/handler/动作类型/对端回读不可漂移)"
node "$XCX_DIR/scripts/uiaudit/build-action-ledger.js" --selftest || mark_fail 自证
if ! node "$XCX_DIR/scripts/uiaudit/build-action-ledger.js" --check; then
  mark_fail
fi

section "UI 走查辅助脚本自证(路由清单 / 重拍分档 / 批次判决)"
# 这三个不产门禁结论,但它们是复核的判据来源;判据源自己坏了,后面全是错的。
for _s in build-route-matrix impact-map verify-batch; do
  node "$XCX_DIR/scripts/uiaudit/$_s.js" --selftest || mark_fail "自证 $_s"
done
if ! node "$XCX_DIR/scripts/uiaudit/build-route-matrix.js" --check; then
  mark_fail "route-matrix freshness"
fi

section "UI-GATE-0 自证(U1-U5 逐条变异红→撤销→绿)"
node "$XCX_DIR/tests/lint/ui-integrity.js" --selftest --warn-as-error || mark_fail 自证

section "UI-GATE-0 全仓扫描(U1-U5)"
if ! node "$XCX_DIR/tests/lint/ui-integrity.js" --warn-as-error; then
  mark_fail
fi

section "UI 有效性门禁(U1/U3/U4/U5;U2 复用上面的死链按钮门禁)"
node --test ci/test_ui_validity_u1_mapping.js || mark_fail "U1 路由回归"
node "$XCX_DIR/scripts/ui-validity-gates.js" --selftest || mark_fail 自证
if ! node "$XCX_DIR/scripts/ui-validity-gates.js"; then
  mark_fail
fi

# 跨栈:除小程序外还读 ApiRoamController/RoamMapper/ApiPlayProgressController/RoamRouteStoryPolicy,
# 所以 ci.yml 的 xcx path filter 必须把这四个后端文件一起点名(只挂 chengyinhub-xcx/** 会假绿,
# 见审计 5 §六.6)。H11 那一族还要覆盖后端与后台 UI,由 changes job 常跑那一份兜住。
section "探店日/漫游红线门禁(R1-R7 漫游免费层 + H11 三栈零回归)"
node "$XCX_DIR/scripts/roam-redline-gate.js" --selftest || mark_fail 自证
if ! node "$XCX_DIR/scripts/roam-redline-gate.js"; then
  mark_fail
fi

if [ "$fail" -ne 0 ]; then
  echo "小程序静态门:未通过。红的子门禁:"
  printf '%s' "$FAILED_GATES" | awk 'NF && !seen[$0]++ { print "  - " $0 }'
  echo "(逐条 file:line 明细见上面各子门禁自己的输出)"
  exit 1
fi
echo "小程序静态门:通过"
