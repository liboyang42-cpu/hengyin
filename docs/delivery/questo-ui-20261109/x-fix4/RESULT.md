# x-fix4 验收第二轮问题收口

分支 `launch/questo-20261110`。依据：`ACCEPT-ISSUES-R2-20261111.md`。统一单测：`chengyinhub-xcx/tests/unit/x-fix4-accept-r2-20261111.test.js`。

## 一、已修

| 编号 | 原因 | 修法 | 文件 | 测试 |
|---|---|---|---|---|
| N-1 继续探索压 Home 条 | 故事流底垫与收尾区不含 `safe-area-inset-bottom` | 底垫、`.chfull__end` 底边距、导航态底垫都加安全区 | pages/play/index.wxss、play-topbar.wxss | x-fix4 N-1 |
| N-6 骰子白矩形 | `.dz/.dzi` 底色、墨色写死浅色道具色，方角 | 底/墨/按钮读 `--pk-embed-*`，内嵌块圆角取 `--pk-qt-radius`；骰面与点数仍是道具色 | components/playkit-diceroll/index.wxss | x-fix4 N-6 |
| N-7 抽卡屏亮蓝/浅灰 | 皮肤层 `.skin-*` 与抽卡组件写死色；整屏/弹层里的玩法没有 `--pk-embed-*`（只有故事流内嵌块给） | `look--*` 的玩家页根统一提供 `--pk-embed-*`（取 `--lk-*`）；抽卡底/卡面/详情/标签、九宫格、碎片弹层、停表玻璃、倒数遮罩改读外观 token。相册查看器按业主「白色相册空间」保留 | pages/play/look-tokens.wxss（与 publish/fabu 副本一致）、style/play-surface.wxss、playkit-random/bingo/stopwatch、fragment | x-fix4 N-7 |
| N-9 结算标题「本局」 | 直链/战报进入未带 `topicName` | 战报就绪后按主题 id 读 `/api/topic/info-to-user` 补主题名与分享卡 | subpackageJourney/pages/settle/index.js | x-fix4 N-9（沙箱执行） |
| N-11 通关卡灰屏、边缘残字、标志压卡 | finale 固定灰底；文案区贴标志；解码乱码字外溢 | finale 底/字/按钮跟 `--lk-*`；文案下移 24rpx 并裁切 | components/chapter-flip/index.wxss；chapter-flip.test.js 断言放宽 | x-fix4 N-11 |
| N-13 就标这些压 Home 条 | 内嵌玩法块底部无安全区 | `.chfull__kit` 底内边距加安全区（与 N-1 底垫合力）。「右侧」是作者题面文案、节点未计数属后端/数据，不改 | pages/play/index.wxss | x-fix4 N-13 |
| N-14 夜城无青粉 | `look-tokens` 的 `look-neon--*` 从未写 `--lk-a/--lk-b`（旧规则只改 `--look-*`） | 补青粉/红青规则，置于配色规则前；默认配色与默认章节配色另补同级覆盖 | pages/play/look-tokens.wxss、publish/fabu 副本 | x-fix4 N-14 |
| L2-2 / N-16 通行证页「暂无在售」 | 通行证页只读旧商品目录，平台通行证方案不在其内 | 平台通行证开放时不再写空态；入口上列方案价格 | subpackageMember/market/passes/index.{js,wxml} | x-fix4 L2-2 |
| P-11 / L2-1 首页定位卡盖 feed | 文案偏长；居中模态点空白不可收 | 文案缩短（保留精确位置/发送给城瘾/不用于附近排序）；`modal-host` 新增 `maskClosable` 透传，首页点空白 = 暂不定位。未改成新版式（需业主定稿） | pages/index/index.js、pages/privacy/index.js、components/cy/modal-host | x-fix4 P-11/L2-1 |
| A-3 编辑器长标题截断、票务底栏压行 | 标题输入与保存胶囊抢一行；尾部净空不足 | 输入框独占一行、胶囊换行；尾部净空 100→160rpx | pages/publish/fabu/index.wxss | x-fix4 A-3 |
| A-4 模板介绍空黑区 | 无示例时画「暂无精选示例」 | 不画空态句 | pages/publish/template-intro/index.wxml | x-fix4 A-4 |
| ME-4 底栏无底 | 后段覆盖规则把渐变顶端设为全透明，图标行半透 | 实底提前（浅 38%、深 30%） | components/tabBar/index.wxss | x-fix4 ME-4 |
| P-15 申诉复用气泡图标 | 与 AI 对话记录同族 | 新增 shield-alert 图标 | pages/shezhi/shezhi.wxml、images/icon_lc_shield-alert.svg | x-fix4 P-15 |
| L-5 口碑空态「重新加载」 | 空列表挂了重载钮 | 去掉 | pages/merchant/reviews/index.wxml | x-fix4 L-5 |

## 二、核查后不改（非页面缺陷或需业主/数据）

- **N-10 主题库缺官方免费主题**：不是筛选 bug。后端 `officialSelfPlayTopic` 要求 `self_play=1`、`lifecycle IN (3,4)`，且存在 `entity_type='PLATFORM'` 的有效 `CONTENT` 角色；种子主题 9912702..4 需满足这三条才会列出（测试数据）。前端 `passQuery` 全部不带类型过滤。
- **P-13**：round 2 已确认筛选条只在开放时出现，缺主题归 N-10。
- **C-3**：玩家视角读未准备的会话，后端按规则 500；play 页已把 `GAME_SESSION_NOT_PREPARED` 当「无会话」处理。开场/准备控件在俱乐部详情 → 导演台（`goGameDirector`，CLUB 视角返回 `NOT_PREPARED + PREPARE`）。无页面缺陷。
- N-8 弹球无外观（需传感器）、N-12 地图搜索条（DevTools 无地图）未动。

## 三、门禁

- 动作台账：L-5 去掉「重新加载」钮，6027 → 6026（事件合同 2997 → 2996，local 2898 → 2897），`build-action-ledger.js --write` 重建，同步 `action-ledger.test.js` 三处分母；`--check` PASS 6026。
- 路由矩阵：结算页新增主题名读取使 `needsLogin` 判定翻 true，`build-route-matrix.js --json` 重建，`--check` PASS 171 路由 / 27 场景。
- 全量单测（仓库根 `node --test chengyinhub-xcx/tests/unit/*.test.js`）：17337 项；首轮 3 失败均为上两项生成物/分母（台账 variant、inventory、route-matrix），重建后相关文件重跑 0 失败（action-ledger 48/48、route-matrix、inventory、x-fix4 全绿）。
- danger-confirm：PASS（53 个调用点）；selftest 通过。
- ui-integrity：`UI-GATE-0:PASS`，无新增基线条目。
- keyframes：本轮 diff 无新增 `@keyframes`。
- WXS 语法门禁：PASS（14 个 .wxs，4 段内联）。
- 其余本轮涉及 wxss 的门禁：undefined-token、wxss-selector、component-wxss-selector、sticky-bottom-bar、ui-theme-unit 均通过。
- 主包：源码侧 `verify:wechat-release` 通过；压缩后体积需 CLI preview 取证（WAIT-EXTERNAL），本轮净增仅数百字节 CSS，无新增依赖/资源（仅一个 <1KB SVG 在 subpackage 外的 pages/shezhi 内）。
- 未跑 `ci/xcx-check.sh`、`ds-hardcode-gate-selftest.sh`（按指令）。

STATUS: DONE（PR #779 CI 待观察，未标 ready，未合并）
