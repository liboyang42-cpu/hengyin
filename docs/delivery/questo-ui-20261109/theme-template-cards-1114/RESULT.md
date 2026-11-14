# 主题模板详情卡片化整理 2026-11-14

分支 `fix/theme-template-cards-1114`(自 origin/master 切出)。页面 `chengyinhub-xcx/pages/templatedetail/topic-detail/`(index.wxml / .wxss / .js)。
业主 2026-11-14:「主题模板太复杂,UI 没有排版,尤其是能力那块,要卡片和样式。」只在既有版式上整理,不重设计页面;无说明性小字;
颜色 / 字号 / 圆角 / 间距全走 `--cy-*` token,卡面沿用同页 `xb-stat-card` / `xb-rule-card` 的 `--cy-color-bg-surface-subtle`,
徽标与药丸沿用 `xb-rule-mark` 的 `--cy-color-bg-elevated`;玩家暗色(theme-dark)与商家浅色(theme-merchant)两套主题由 token 自动切换,页面未写任何主题分支。

## 逐节前后

### 「能力」tab
- 概要(改前):`summary` 是一排 `xb-chip`(「城市定向 / 3 个玩法 / 8 站 / 约 1 小时」)。
  改后:同样的事实变成 `xb-stat-card` 数字卡(值大字 + 标签小字),一行三格、可换行:模式 / 时间(自由探索才有「可以跨天」)/ 章节 / 站点 / 玩法 / 总时长;缺值的格子整格不出。
- 站点(改前):一张长卡里平铺 `.cap-stop` 行(序号 / 站名小字 / 玩法名 / 做什么 / 时长),没有章节归属。
  改后:按章分组。章头 = 编号徽标 + 章名(章名自带「第…章」时不重复编号;只有一组且没有章名时不出章头);
  每站一张 `.cap-card`:左侧序号徽标(跨章连续),右侧站名一行省略、玩法药丸(货架 `art.gameArt` 同一张道具图 + 玩法名;表外玩法用线性 `play` 图标)、
  玩家做什么最多两行省略(`ep2`)、时长 chip 带 `clock` 图标。统一 `--cy-space-3` 内距、`--cy-radius-md` 圆角、`--cy-space-2` 卡距;不可点、无按压态。
- 采用条件(改前):`xb-caps` 容器里 `xb-caprow` 行,标签固定宽 + 值一句事实,分节标题与键值行各自为政。
  改后:`xb-block` 分节标题 + `xb-kv-card` 分组键值卡(圆角容器、标签左 / 值右、细分隔线),值可换行,与本页家族「怎么验证」卡同一套。
- 空态:`暂无玩法安排` 的 `cy-empty` 不变。

### 「详情」tab
- 开场白(改前):全文直出。改后:默认收 4 行(`.td-opening--clamp`,与 `.xb-body` 44rpx 行高同口径),用 `createSelectorQuery` 量实际高度 > 88px 才出「展开」,点后切「收起」;短文不出假按钮,单测环境没有量尺时不报错。
- 章节(改前):每章一段(编号 + 章名 + 整段描述)并挂该章站点路径 chips(`.st-ch__path / .st-ch__stop`)。
  改后:每章一张紧凑卡 `.st-ch`(44rpx 编号徽标 + 章名一行 + 一句话一行省略),不再列站点路径,站点只在「能力」里按章出卡。
- 规模数字卡(`xb-stat-grid`)、相册 / 节点横滑卡(`xb-node-card`)、通关勋章卡:原样保留。

### JS(只加最小必要)
- `buildChapters` 不再产 `stops`;新增 `buildStopGroups`(按章分组,配 `gameIcon`)与 `buildStops(groups)` 拍平;`buildSummary` 产 `{value,label}`。
- 新增 `openingExpanded / openingNeedMore / stopGroups` 三个字段、`_measureOpening(seq)`(按 `_seq` 丢弃迟到回包)与 `toggleOpening` handler。
- 接口、采用流程、署名加载、分享、权限闸(`ensureLoginFor` / readonly)全部不动。

## 验证
- 新增 `tests/unit/theme-template-cards-1114.test.js`(7 条:分组 / 概要 / 单组无章名 / 章节卡 / 展开收起 / 能力 wxml 契约 / wxss 只用 token)。
- 相关既有单测通过:template-shelf-redesign-20261028、qx-b4-topic-adoption、r12-k9-pages、r10-f3-business-refusal、guest-browse-login-on-write、get-auth-matrix-contract、nav-page-title-spacing-contract、devfix-l04-home-template-20261027、merchant-operator-ui-contract。
- 静态门禁本地全绿:undefined-token / wxss-selector / component-wxss-selector / button-reset / wxml-conditional / cy-sheet-attr / component-attr / component-path-resolve / wxml-handler / js-orphan-handler / setdata-dead-field / a11y-tappable-name / a11y-tap-target / pulldown-refresh / sticky-bottom-bar / ui-theme-unit(theme、units)/ ds-hardcode-gate --ci / js-scope-lint / ui-integrity --warn-as-error / coverage-gate / shot-matrix / ui-validity-gates / verify:wechat-release(分包体积在限内)。
- action-ledger 用 `build-action-ledger.js --write` 重新生成(+1 控件:`toggleOpening`,6094 → 6095),route-matrix `--json` 重生成后无变化;`tests/unit/action-ledger.test.js` 的 tap 3078 → 3079、本地 tap 2684 → 2685、controlCount 6094 → 6095 按项目惯例加日期注释;ui-integrity 基线未加条目。
- 未跑 `ci/xcx-check.sh` 自证;未改 `ci/architecture/*` 与 workflows。
- 云端会话没有微信开发者工具,未能截图模拟器;两套主题的视觉以 token 定义与 CI 为准,请业主在 DevTools 打开 `pages/templatedetail/topic-detail/index?id=<主题模板id>`(玩家)与 `…&scope=MERCHANT`(商家)核对。

STATUS: DONE
