# 阶段4-B · 玩法域：双模式状态机 / playkit 三方对账 / advanced 四端点

方法：`pages/play/index.js`(5362 行) + `utils/playkit-view.js` + `utils/publish/advanced-game-config.js`
+ 后端 `AdvancedGameRuntimeServiceImpl.java` 四方逐行对。代理先扫一遍，**下面每条都由我重新打开文件核实行号**；
代理说过头的两条我做了降级、没写进缺陷（见文末「已降级」）。事件契约那批另见 `04-playkit-event-contract.md`（F-PK-1..5）。

---

## F-PB-1 · P1 · 「沉默点单」的「放弃」按钮按下去什么都不发生

- 组件把放弃当不可逆动作处理（给 medium 触感）：`pages/play/components/playkit-silentorder/index.js:51-55` → `triggerEvent('giveup', {elapsedSeconds})`
- 分发器接了并往上收口：`pages/play/components/playkit/index.wxml:16 bind:giveup="onGiveUp"` → `playkit/index.js onGiveUp` → `kitaction{action:'giveup'}`
- 页面走默认分支：`pages/play/index.js:4601-4603` → `_submitPlayKitAction('giveup', …)` → `:4614 serverAction('silentorder','giveup')`
- **动作表里没有任何 `silentorder:` 条目**：`utils/playkit-view.js:435-478`（`TYPE_OF:42` 有 silentOrder，ACTION_OF 零条）
- 于是命中 `pages/play/index.js:4615` `if (!name) return;` —— **无请求、无 toast、无日志**（这一条正是 ACTION_OF 表头注释里自己写的静默形状：`utils/playkit-view.js:446-448`）
- 后端也没有对应动作：`chengyinhub-system/.../AdvancedGameRuntimeServiceImpl.java:209-279` 的 24 个 case 里没有 silentOrder/giveup
- 玩家看到的结果：计时停住（组件自己 `_stop()` 了）、面板还开着、既没结束也没提示。判定确实由店员扫码产生
  （`playkit-silentorder/index.js:2-4` 注释），所以**这不是「玩法没做」而是「放弃这个动作没有出口」**。

## F-PB-2 · P1（有前置条件）· 探店日无坐标站点：按钮写着「去这家」，点下去直接把这一站演成已通关

- 半屏三段互斥链：`pages/play/index.wxml:901`(done) → `:909`(hasGame，只有「开始任务」) → `:921-922`(else，按钮文案 `mode==2 ? '去这家' : …`)
- `pages/play/index.js:3473` `onSheetGoTap`：**只有 `node.lat && node.lng` 时**才截胡成 `startNav`；无坐标就落到后面的 `onArriveTap` → `arrive()`
- `pages/play/index.js:3643-3651` `arrive()` 成功即 `that.onComplete(nodeId, r.data)`，**没有 mode2 判断**
- 同文件对扫码那条却专门加了闸：`checkin()` `:3676-3678`
  「探店日(mode2):静态码只记『进店』…本地绝不能走 onComplete,它会置 done+发庆祝,把『进店』演成『已核销』」→ `onShopDayArrived`
- 结论：**同一个「mode2 不许本地置完成」的不变量，checkin 有、arrive 没有**。前置条件＝mode2 存在无坐标节点，
  这条我只从 `normNode:2816` 的坐标兜底推出，没拿到线上数据（后端 latitude 能否为空待后端确认）。

## F-PB-3 · P1 · 跨日任务（slowTask）整条玩法线在任何创作入口都配不出来

- 玩家端全链齐活：`utils/playkit-view.js:27`(KIT_PRIORITY)、`:50 TYPE_OF`、`:152`、`:323`，动作在册 `:443-444`（START_SLOW_TASK / CLAIM_SLOW_TASK）
- 后端也认：`AdvancedGameRuntimeServiceImpl.java:234`、`:237`、`:892-909`，并且 `:652-653` 会因 `slowTask.claimed` 为假把 `readyForBase` 判 false
- 配置侧根本没有这一段：`utils/publish/advanced-game-config.js:4,9-10,14,22-23` 四组名单（CORE5+KIT7+PLAY6+DECIDE7）**不含 slowTask**，
  `pages/publish/*`、`utils/publish/*` 对它零命中；后台 `chengyinhub-ui/src` 也零命中
- 后果：这一屏在生产里永远产不出来（静默），而文件头自己写的纪律是「这份名单必须与服务端段名一一对应，少一个那个玩法读不出来也存不回去」
- 裁决口径：要么补配置口（跨日任务是产品意图），要么把玩家端+后端这串删掉——**属「一整条玩法线的去留」，需要用户拍**。

## F-PB-4 · P2 · 排行榜拉取失败被说成「还没有排名数据」，并且抛未处理 rejection

- `pages/play/components/advanced-game/index.js:249-274` `loadLeaderboard()`：`this.request(...).then(...).finally(...)` **没有 `.catch`**
- 同文件 `:43-52` 的 `request()` 在 `fail` 时 `reject` —— 所以网络失败会冒成未处理拒绝；对照同文件另外两条都规矩：
  `start()` `:77` `.catch(() => setData({error:'网络错误，请重试'}))`、`refreshState()` `:293` `.catch(() => {})`
- 非 200 也不落 error（`:265` 只在 200 时 setData），旧数组原样留着（`:271`）
- 界面把这层沉默翻译成了事实：`pages/play/components/advanced-game/index.wxml:50`
  `<cy-empty wx:if="{{!leaderboard.length}}" title="还没有排名数据" />` —— 拉取失败与真的没人上榜，玩家看到的是一句话。

## F-PB-5 · P2 · 音乐角「听完」那次提交是双死代码，注释与真源互相打脸

- `pages/play/index.js:4696-4699` `onEnded` 里 `_submitPlayKitAction('finished', {})`，注释写「听完才是这一站的完成条件,交给服务端判」
- ACTION_OF 无 `musiccorner:finished`（`utils/playkit-view.js:435-478`），后端 switch 也没有音乐类动作
  → 命中 `:4615` 静默 return
- 而 `utils/playkit-view.js:318-320` 明写「音乐角明确不判定通关(2026-08-26 拍板)」
- 组件本身只发 `toggle/close`（`playkit-musiccorner/index.js:62,64`），所以这行既到不了服务端也没有发射方。
- 附带：`ACTION_OF:440` 只登记 `walk:sync/claim`，`pages/play/index.js:4620` 那句 `action === 'refresh'` 的兼容分支永远走不到（旧 steps 屏已下线）。

## F-PB-6 · P2（潜在）· 关高级面板不收 playKit 弹层，两层 sheet 可叠

- `pages/play/index.js:3841-3844 onAdvancedClose()` 只置 `advancedPlay.show:false` 并重开节点半屏，**没调 `closePlayKit()`**（该方法在 `:4536`，
  `:4579` 的 kitaction close 分支才走它）
- `cy-playkit` 独立渲染在页面顶层：`pages/play/index.wxml:1613`，`show` 仍为真；此时 `selectComponent('#advancedGame')`（`:4624`）也还提交得出去
- 可达性存疑（kit 弹层盖在面板之上，面板自己的关闭手势可能被遮），所以定 P2 不当 P1。

---

## 三方对账矩阵（本轮实测数）

| 侧 | 真源 | 数量 |
|---|---|---|
| 配置（创作端） | `utils/publish/advanced-game-config.js:4,9-10,14,22-23` | 25 段（CORE5 / KIT7 / PLAY6 / DECIDE7） |
| 玩家侧可产出 | `utils/playkit-view.js:31-58 TYPE_OF` | 23 个 kit type |
| 玩家侧分发器分支 | `pages/play/components/playkit/index.wxml` | 27 支，其中 4 支登记为孤儿（`tests/unit/playkit-view-contract.test.js:414-424`：bingo/steps/stickerbook/gametimer） |
| 商家侧操作面 | `pages/merchant/**`、`pages/club/**` 对 kit 段名 grep | **只有 predict 一个**（`pages/merchant/predict/index.js:107,163`）；`game-node` 只认 STATION_*/VERIFY_SUBMISSION |
| 后端动作 | `AdvancedGameRuntimeServiceImpl.java:209-279` | 24 个 case；`default: throw "高级玩法动作不支持"` |

- 动作名集合：**前端 ⊆ 后端，无前端孤儿**（22 条 ACTION_OF 全部落在后端 24 case 内；`ASSIGN_ROLE`/`COMPLETE_UNIT` 由面板自身按钮绕过 ACTION_OF 直达，`advanced-game/index.js:247-257`，不算断线）。
- 差集就是上面三条：`silentorder`（前端有屏无动作，F-PB-1）、`musiccorner:finished`（F-PB-5）、`slowTask`（后端有、玩家有、配置无，F-PB-3）。
- `CHALLENGE_GAME` 五个驼峰值与后端 `:1372-1374` 白名单逐字一致（这条是好的）。

## 断点续玩（advanced 四端点）核对结论

- 续局的正路是 `/api/play/advanced/start` 的幂等复用：后端 `AdvancedGameRuntimeServiceImpl.java:120-130` 对 RUNNING 局直接 `toView(existing)`，
  仅 FAILED/EXPIRED 才 restart（`:158`）→ **冷重进能接上，这条没问题**。
- `onShow` 不主动 `refreshState`：`pages/play/index.js:477-481` 的 `loadData(false)` 不触碰 `advancedPlay`/`playKit`，
  恢复靠组件 observer（`advanced-game/index.js:35-38`，nodeId 未变不重跑）→ 回前台后限时条读数可能停留在离开时的值（P2）。
- `onHide`（`pages/play/index.js:639`）不清高级会话，`advanced-game/index.js:41 pageLifetimes.hide` 只停音频；
  `:286` 的 1s `setInterval` 后台照跑，并在归零时在后台发 `refreshState`（`:283`）→ 与项目「定时器必须有清理」的既有纪律相比是**弱侧**，但不会崩。

## 已降级（代理报得过头，记录以免修复批照抄）

1. ~~「mode2 有玩法的节点导航整个不可达」~~ → 半屏 `:909` 那支确实只有「开始任务」，但导航在别处有口：
   `pages/play/index.js:2651`（POI 卡 → `startNav`）、`pages/play/index.wxml:447-450`（店铺卡「导航」）、`:178`（列表「导航去这里」）。
   改判为 **P2：同一个「去这家」动作在同页有 4 个入口、三种措辞，半屏里对玩法节点缺席**（不是不可达）。
2. ~~「mode2 揭示答案不扣分解」是缺陷~~ → `revealAnswer()` 的 `this.data.mode !== 1` 早退（`pages/play/index.js:4147-4150`）
   与 `checkin` 那条「mode2 完成由商家核销」的口径（`:3676-3678`）是自洽的：本地摊牌、不置完成**符合设计**。
   唯一残留问题归进待裁决：**`normNode:2856` 没有像 `:2864-2865` 剥 medal/coupon 那样按 shopDay 剥 `puzzleScoring`**，
   所以 mode2 的站点仍会显示「实在解不出，揭示答案」并走本地摊牌 —— 要不要按探店日口径藏掉，请用户定。
3. ~~`utils/withdrawal-preflight.js` 415~~ 与 ~~`subpackageMember/myinvite/myinvite.js:133`~~ 两条已否决，见 `01-unknown-state.md` 末尾。
