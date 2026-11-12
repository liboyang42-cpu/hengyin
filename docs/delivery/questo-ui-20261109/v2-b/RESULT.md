# v2-b：卡包（时刻 2）+「⋯」菜单与入场票根（时刻 7）

分支 `launch/questo-20261110`，规格取自 `claude/questo-ui-brief-20261109` 的 `proto/motion-v2/index.html`（原型里没有 `?shot=1`，截图直接用 `?m=2` / `?m=7` + `&look=`）。

## 做了什么

### 时刻 2：卡包（自由探索首屏）
- 包上方一行标题（主题名）+「本章 N 张」，包下方「轻点开启」。包是 400×560rpx（原型 200×280），镭射渐变 + 顶部齿纹 + 菱形徽记 + 「城瘾 / 主题名 / N 张」。WXSS 渲染层不认 `conic-gradient`，所以底色用线性渐变近似。
- 待机：包慢浮动（3.2s）+ 一道慢流光（2.6s），都是 `this.animate` 链式循环。循环有「回调过快即停」的保护（节点不在时 animate 会立刻回调，否则会同步递归）。
- 点开：短抖 260ms → 包下沉淡出 420ms，同时 36 片外观色彩纸（`--lk-a` / `--lk-b` + 三枚固定亮色）从开口散开，每片一次 `this.animate`。
- 发牌：逐张弹出间隔 90ms，弹簧 `cubic-bezier(.34,1.56,.64,1)`，扇形照原型（相邻 62px、角度差 12°、中心上方 30px，弹出缩放按原型卡宽 136px 折算）；停留后逐张落格，间隔 60ms；通行证头等第一张落格再出来，不再压在扇面上（旧版的重叠问题）。落格后每张卡一道慢流光（3.4s）。最后出现「收进卡册」。
- 减弱动态：直接落定，不起循环、不播动画。
- 删除：旧的拖拽撕包、爆炸光条 / 碎片 / 压暗、`fxShake / fxRip / fxMouth / fxFlapL / fxFlapR / fxSink / fxBloom / fxRay / fxShard` 共 9 组 `@keyframes`。keyframes 棘轮 146 → 137，布局属性动画棘轮 9 → 8。没有新增 `@keyframes`。

### 时刻 7：「⋯」菜单与入场票根
- 菜单从右上角按钮处按角点缩放长出（`transform-origin:92% 0`，`scale(.2)` → 1，.42s `--ease`），行错峰 30ms（`:nth-child` 的 `transition-delay`），玻璃底 + 24px 圆角，页面压暗。全用 CSS transition，减弱动态为终态。
- 前五行照原型次序：入场码 / 切换章节 / 旅程手记 / 同行者 / 声音与动效；紧急求助红色压最后。行沿用原来的行与处理函数，只换位置和名字（同行者榜 → 同行者，动态效果 → 声音与动效）；mode 2 / 城市定向两份重复的入场码、旅程手记行合并成一份。
- 入场码半屏：浅色票根、两侧缺口、196px 码（`cy-dot-qr` 360rpx）、虚线齿孔、主题名、使用人，下面「完成」。玩页主面没有大二维码（D1 已去掉浮钮，本轮核过，仅剩站点半屏里一个小图标）。

## 复用与调用者
- 菜单行、`toggleSessionTools / openTicketFromTools / openStackOrChaptersFromTools / openJournalFromTools / openBoardFromTools / toggleReducedMotion / openSos` 全部是原处理函数；入场码仍走 `openEntryQr` → `qr-ticket` 场景 → `scene-qr-ticket` 组件（二维码、续签、核销监听一字未改）。
- 发牌仍用原来的 `_dealTiles / _markTile / _measureDeal` 两段 transition 机制，只换了时序、弹簧曲线和扇形几何。

## 验证
- 走查 rig（`claude/cloud-walk-20261101-rig` 的 mpweb）与原型，在 stardust / paper / survival / cyber 四个外观下，按相同时间点截帧：卡包 idle、idle 再过 1.5s、点开后 130 / 400 / 700 / 1000 / 1400 / 1900 / 2500 / 3400ms；菜单 60 / 160 / 300 / 520 / 900ms；票根 150 / 400 / 800 / 1600ms。左原型右实现，共 80 张并排图 + 24 张总览，在 `compare/`；脚本在 `rig/`（`cap.mjs`、`proto-cap.mjs`、`pairs.sh`、`fake-backend.mjs`）。
- rig 的 `animate` 只会把帧原样交给 Web Animations，小程序的 `rotate / scale / translateY` 数值写法不认，所以验证时在**临时目录**里给 rig 运行时补了一层转换（没有提交）；真机的 `this.animate` 行为只能由开发者工具 / 真机最终确认，这里没有这一项的证据。
- 逐对看过全部时间点。idle、抖、下沉、彩纸、扇形位置与间隔、菜单长出与错峰、票根版式都与原型一致。以下是**仍然可见的差异**，见下节。
- 单测：新增 `v2b-pack-menu-ticket.test.js`（8 条）；删除两个只锁旧撕包机制的测试（`play-pack-drag-tear-lock`、`play-pack-timing-lock`，机制本身已按规格删除）；改写 `x-design1-d16-pack-deal`、`play-free-explore-shop-card-g21` 里的旧断言；棘轮常量按实测收紧。
- 全量单测（仓库根 `node --test chengyinhub-xcx/tests/unit/*.test.js`，`--test-timeout=180000`）：17353 条，17352 通过、0 失败；唯一一条是 `action-ledger.test.js` 文件级的 180s 超时（它单独跑要 6m52s，不是断言失败），单独不限时重跑：48 条 0 失败。
- 动作台账 `build-action-ledger.js --write`：PASS 6021 控件（6025 − 4 个被删的拖拽撕包 touch 事件合同；tap 仍 3029、local tap 仍 2650）；路由矩阵 `--check`：PASS 171 路由 / 27 场景。
- ui-integrity：**UI-GATE-0:PASS**（play/index.js 两条既有锚点因删旧撕包只是行号位移 1868→1794、8909→8835，调用行逐字相同，不新增登记，两个 keyDigest 按门禁算法重算）。
- danger-confirm：PASS（53 个调用点）。WXS 语法门禁 PASS（14 个 .wxs）；WXSS 注释入值、选择器、组件选择器、未定义 token、WXML 条件链门禁均 PASS。
- keyframes 棘轮 146 → 137（只减不增）；布局属性动画棘轮 9 → 8。
- 单色契约（`play-monochrome-contract`）：旧爆炸层彩色豁免整组删除，换成「包面镭射渐变 + 紧急求助红」按值豁免（只在卡包 / 菜单行），负控改锚到新值，仍判红。
- 主包：源码侧 `mainpkg-size-report` 合计 3234KB，×0.633 ≈ 2047 KiB，< 2048 KiB（与 x-fix7 / x-design1 相同，富余仍约 1 KiB）。

## 与原型仍有的可见差异（没有做到「无差异」）
1. **卡面内容**：原型的卡是糖果渐变的奖励卡；自由探索首屏落下来的是真实的商家卡（封面图 + 章节标签 + 名字），扇形阶段是这些卡。位置、间隔、缓动、旋转与原型一致，卡面不同是内容本身决定的。
2. **落点**：原型落成一排缩小的小卡；这里落进下面真实的两列商家格（点它们要进商家页）。因此没有做「缩成一排」那一步。
3. **「收进卡册」**：现有页面没有卡册目的地，也没有「章节奖励卡」这一页（代码里查不到）。按规格做了最后出现的按钮，点一下只是把它收起，**没有任何别的动作**——需要产品决定它该去哪。
4. **菜单多出几行**：原型菜单正好六行；真实菜单里还有「各站奖励 / 我的权益 / 自动记录足迹」等有功能的行（队长的几行只给队长）。我没有删掉它们，排在「声音与动效」之后、「紧急求助」之前；「同行者」在自由探索里没有对应动作（只有城市定向有同行者榜 / 队伍位置），所以自由探索看不到这一行。
5. **集合时间**：票根的「集合」行需要集合时间，目前玩页没有这个数据源，组件有 `gather` 属性但宿主没传，所以这一行不画（有效期那行照旧）。
6. **背景与卡包底图**：外观色板用的是仓库里真实的 `--lk-*`（stardust 是 `#0A0A0A` 不是原型的 `#06070c`），所以底色与原型有细微色差，没有去改外观目录。包身是线性渐变近似，没有 conic 的色环。
7. **标题**：x-fix7 P-6（业主验收「包上只印主题名」）与本规格「标题在包上方」冲突，按本规格执行：标题在包上方，包面是「城瘾 / 主题名 / N 张」。

## 其它
- 未改 `ci/architecture/*`、`.github/workflows/ci.yml`；未跑 `ci/xcx-check.sh`、`ds-hardcode-gate-selftest.sh`；未合并、未标 ready；无生产、无密钥。

STATUS: PARTIAL - 卡包与菜单 / 票根按原型做完并通过全部门禁，四个外观逐帧比对过；但与原型仍有可见差异（卡面是真实商家卡、落进真实两列格而非一排小卡、菜单多出有功能的行、「收进卡册」无目的地、集合时间无数据源），且 this.animate 只在 rig 的临时转换层验证过，真机待确认
