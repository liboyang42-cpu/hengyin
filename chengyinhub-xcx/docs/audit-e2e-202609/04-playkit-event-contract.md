# 阶段4 · playkit / 组件事件契约(接缝重灾区)

探针已重建并把原始输出留在磁盘:`docs/audit-e2e-202609/event-contract.js` → `logs/event-contract.log`。
**跑量:扫 317 个 wxml / 495 个 js,`<component is>` 动态引用 0 个,「组件抛了但没有任何宿主监听」的事件 23 条。**
上一轮那个 A1=24 / A2=38 / B=0 的口径本轮无法复现,不再引用;下面每一条都同时给得出探针行与磁盘文件:行号。

探针本身踩过两个坑,记下来给修复批(它们会让尺子虚报,不会漏报成假绿):

1. **`bind:tap` 与 `bindtap` 在 WXML 里完全等价**,正则只认带冒号的写法,就会把 `catchtap="goMemberProfile"`(`pages/club/detail/index.wxml:330`)判成没接 —— 第一版因此多报 1 条 `avatar › tap`,23 才是真值。
2. **属性串不能撞到第一个 `>` 就截断**:`wx:if="{{count > 0}}"` 的 `>` 在 `{{}}` 里,非贪婪匹配会把后面的 `bind:` 整段丢掉。现在按花括号深度找闭合。

## F-PK-1 [P0] 三个挑战玩法永远开不了服务端秒表,提交必被判「还没开始」

**契约要求**:五个挑战类玩法都要先 `start` 换服务端计时起点 —— `utils/playkit-view.js:455-461`

```js
/* 五个挑战类都要先开表:服务端拿 START_CHALLENGE 那一刻的**服务器时间**复核成绩, ... */
'reaction:start': 'START_CHALLENGE',
'ballshake:start': 'START_CHALLENGE',
'quiethold:start': 'START_CHALLENGE',
'countdown:start': 'START_CHALLENGE',
'stopwatch:start': 'START_CHALLENGE',
```
（`CHALLENGE_GAME` 见 `:487-494`,五个驼峰名与后端一致。）

**组件侧确实在发** `start`:
`pages/play/components/playkit-reaction/index.js:87` `if (!this._armed) { this._armed = true; this.triggerEvent('start'); }`
`pages/play/components/playkit-ballshake/index.js:110` `this.triggerEvent('start');`
`pages/play/components/playkit-quiethold/index.js:135` `this.triggerEvent('start');`

**分发器漏接这三家**(`pages/play/components/playkit/index.wxml`):

| kit | 行 | 绑定 |
|---|---|---|
| slowtask | `:56` | `bind:start="onSlowStart"` ✓ |
| countdown | `:90` | `bind:start="onStart"` ✓ |
| stopwatch | `:95` | `bind:start="onStart"` ✓ |
| **reaction** | `:75-77` | 只有 `bind:submit / bind:verdict / bind:close` ✗ |
| **ballshake** | `:79-82` | 同上 ✗ |
| **quiethold** | `:84-87` | 同上(多一个 `bind:micdenied`)✗ |

中继本身是齐的:`pages/play/components/playkit/index.js:71` `onStart() { this._emit('start'); }` → `:29-31` 统一收成 `kitaction` → 页面 `onPlayKitAction` → `_submitPlayKitAction`。

**后端后果**(`chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/AdvancedGameRuntimeServiceImpl.java`):

```java
:1382  private long serverElapsedMs(ObjectNode node, String label) {
:1384      if (startedAt <= 0L) throw new IllegalStateException(label + "还没开始");
```
调用点:`:1395 "变色就点"`(reaction)、`:1429 "弹球"`(ballshake)、`:1452 "安静挑战"`(quiethold)。

**链路结论**:这三种玩法玩家侧可以正常玩到出成绩、点提交,但 `START_CHALLENGE` 从未发出 → 秒表起点恒 0 → 后端每次必抛 → 成绩永远不成立。`startChallenge` 只在收到 START_CHALLENGE 时才写起点(`:259`、`:1361-1370`)。

**为什么门禁没抓到**:`tests/unit/` 里那条接线断言只核 `ACTION_OF` 表(表是齐的),视图层少一个 `bind:` 不在任何 lint 判据里 —— 这正是上轮结论里「事件契约无 lint」这条接缝的实锤样本。

**复现**:配一个「变色就点」玩法(`publish/fabu` 高级玩法段)→ 玩家进游戏 → 完成点击 → 提交 → 报「变色就点还没开始」。

## F-PK-2 [P1] 角色分配弹层的「选择成员」点了没反应,确认会写给原来那个人

组件把成员行点击如实抛出来:`pages/club/components/club-director-role-sheet/index.js:26`
```js
onMemberTap(e) { this.triggerEvent('selectmember', e.detail) },
```
且这一列是**可点的**:`index.wxml:4`
```xml
<cy-club-director-row-list rows="{{members}}" surface="raised" selectable bind:rowtap="onMemberTap" />
```

宿主接了另外四个、独独没接这个:`pages/club/topic-detail/index.wxml:262-268`
```xml
member-section-title="选择成员" members="{{teams}}"
...
bind:close="closeRoleAssignment" bind:confirm="confirmRoleAssignment"
bind:selectrole="onRoleOptionChange" bind:takeovertap="openTakeoverRole">
```

全仓 `grep "selectmember"` 在任何 wxml 里 **0 命中** —— 这条事件没有任何监听者。

后果不是「只是不灵」:`pages/club/topic-detail/director.js` 的 `openRoleAssignment()` 在**用户点某一行成员时**就把 `roleDraft.teamId/memberId` 定死了,`confirmRoleAssignment()` 直接拿草稿提交:
```js
this.executeAction('ASSIGN_ROLES', {
  teamId: draft.teamId,
  assignments: [{ memberId: draft.memberId, roleCode: draft.roleCode }],
})
```
所以「在弹层里改选另一个人再确认」这个动作会把角色写到**最初点进来的那名成员**身上,而且界面没有任何提示说没换成功。权限域的错误写入,定 P1。

## F-PK-3 [P2] `cy-upload` 的失败重试能力两个宿主都没接,「禁静默丢图」形同虚设

组件契约写明了四个事件(`components/cy/upload/index.js:3`「组件纯展示 + 事件(add/remove/retry/preview)」),失败位可点:
```xml
<!-- components/cy/upload/index.wxml:12-14 -->
<view wx:elif="{{item.error}}" class="upload__mask upload__mask--error" catchtap="onRetry" ...>
  <text class="upload__err-txt">上传失败</text>
  <text class="upload__retry">点此重试</text>
</view>
```
但两个宿主都既没传 `errorIndexes`、也没绑 `bind:retry`:
- `pages/publish/activity/index.wxml:101-109`(封面图)
- `pages/club/apply/index.wxml:154`(资质图,`max="9"`)

`errorIndexes` 是失败态的唯一入口(`components/cy/upload/index.js:13,30-34`,注释还强调「error 优先于 uploading:失败是终态,不许被整批转圈压制(§3.13 禁静默丢图)」)。宿主不传 ⇒ 该遮罩永不渲染 ⇒ `retry` 也永不触发。

诚实定级:因为渲染条件进不去,这**不是**一条活的坏交互,而是**整块能力空挂**(组件承诺的 §3.13 在两处都没落地)。裁决点是:要么两宿主接上 `errorIndexes` + `retry`(把承诺兑现),要么删掉这一段(别留一份看着像有的假保证)。

## F-PK-4 [P2] `cy-calendar` 的 maxRange/overrange 全仓无使用者

- 发射端:`components/cy/calendar/index.js:127-134` —— 越界时**既不落值也不提示**,只 `triggerEvent('overrange', ...)` 后 `return`。
- 中继:`components/cy/date-range-sheet/index.js:86` `this.triggerEvent('overrange', e.detail)`,其内部 `index.wxml:33` 有 `bind:overrange="onOverrange"`。
- 终点:全仓 `bind:overrange` 只有那一处内部绑定,**没有任何页面监听 sheet 的 overrange**;而 `maxRange` 在 `components/cy/calendar` 与 `cy/date-range-sheet` 之外的文件里**零引用**。

⇒ 今天无玩家可走通的路径能触发它。但这条设计是「越界即静默吞掉,由调用方负责提示」,一旦有人开始配 `maxRange` 而忘了听 `overrange`,表现就是「选了没反应」。记为潜在项,建议要么在日历内部给一句默认提示,要么在 lint 里把「配了 maxRange 却没绑 overrange」判红。

## 23 条全量分诊(逐条给结论,不留「剩下待看」)

探针的 23 条按「宿主不接会不会有人受到坏影响」分四类。**A 类才是缺陷,B/C/D 判无害**,但 C 类余量留不留是修复批的裁决项。

### A · 真缺陷(6 条 = 上文四条发现)

| 事件 | 归属 | 为什么算缺陷 |
|---|---|---|
| `playkit-reaction › start` | F-PK-1 | 后端秒表起点永不写 |
| `playkit-ballshake › start` | F-PK-1 | 同上 |
| `playkit-quiethold › start` | F-PK-1 | 同上 |
| `club-director-role-sheet › selectmember` | F-PK-2 | 改选他人无效,还会写给原来的人 |
| `upload › retry` | F-PK-3 | 组件承诺的失败重试两处宿主都没落地 |
| `date-range-sheet › overrange` | F-PK-4 | 越界被静默吞掉,没人提示 |

### B · 组件已自行消化,事件只是「顺便告诉你一声」(7 条,判无害)

| 事件 | 消化证据 |
|---|---|
| `calendar › pick` | 同一手势已双发 `change`,唯一宿主 `components/cy/date-range-sheet/index.wxml:32` 听的就是 `change` |
| `info-pop › open` / `› close` | 开关态由组件自己 setData(`components/cy/info-pop/index.js:53-62`),弹层内容也全在内部 |
| `search › clear` | 组件注释把契约写死了:「clear 时同步补发 input(value:''),受控父不用单独监听 clear」(`components/cy/search/index.js:5`),10 个宿主全走 `bind:input` |
| `scene-deep-link › close` | `closeRoot()` 自己 `navigateBack` + `reLaunch` 兜底(`components/cy/scene-deep-link/index.js:167-173`),宿主无事可做 |
| `cover-fallback › covererror` | `setData({ failed: true })` 已切到文字封面(`pages/publish/components/cover-fallback/index.js:28-31`),兜底就是它的本职 |
| `club-game-code-sheet › saved` | 组件自己 `toast('已存到相册，可打印后贴在站点')`(`pages/club/components/club-game-code-sheet/index.js:48-50`),页面无需二次反馈 |

### C · 渲染条件根本进不去的死能力(8 条,裁决点=保留还是删)

发射端全被一个可选属性闸住,而**全仓没有任何宿主传过那个属性** ⇒ 那颗按钮今天不可能出现在屏幕上:

| 事件 | 闸门 | 全仓取证 |
|---|---|---|
| `footer-bar › primarytap` / `secondarytap` / `primarydisabledtap` | `wx:if="{{primary}}"` 属性槽(`components/cy/footer-bar/index.wxml:2`),不传 primary 就走 `<slot>` | 5 个宿主逐个读过,全是 slot 形态:`pages/address/address.wxml:41`、`pages/addressinfo/addressinfo.wxml:49`、`pages/gerenziliao/gerenziliao.wxml:107`、`pages/merchant/customer/index.wxml:227`、`subpackageMember/coupon/coupon.wxml:48` |
| `result-sheet › primary` / `secondary` | 按钮要 `primaryText` 才画 | 8 个宿主 wxml 里 `primary-text` / `primaryText` **零命中** |
| `free-map › exit` | `wx:if="{{showExit}}"`,默认 false(`components/cy/free-map/index.js:72`) | 唯一显式传值处传的是 `show-exit="{{false}}"`(`pages/play/index.wxml:31`),另有单测钉住这条(`tests/unit/play-special-case-contract.test.js:14`) |
| `merchant-card › service` | `wx:if="{{serviceText …}}"`(`components/cy/merchant-card/index.wxml:42`) | 全仓 `service-text` 传值**零命中** |
| `timeline › action` | `wx:if="{{item.action}}"`(`components/cy/timeline/index.wxml:29`) | 两个宿主喂的 nodes 数据里没有 `action` 字段 |

⇒ 这 8 条不是坏交互,是**「看着像有、其实没人能开」的接口余量**,裁决口径与 F-PK-3 同源:要么补宿主用法把承诺兑现,要么把这段能力从组件里删掉。挂门禁前必须先裁这批,否则 lint 一上线就常驻 8 条红。

### D · 无害中继(2 条,补记,别误判成缺陷)

| 事件 | 结论 |
|---|---|
| `playkit-bingo › cell` | 点开的是**组件自己的说明弹层**(前一行已 `setData({panel})`,`pages/play/components/playkit-bingo/index.js:100-112`);bingo 不在 START_CHALLENGE 名单,页面无需知道 |
| `playkit-qa › run` | 把 `cy-play-stage` 的 `run` 原样上抛(`pages/play/components/playkit-qa/index.js:129`),但 qa 的 ACTION 只有 `qa:submit`(`utils/playkit-view.js:473`),不需要计时起点 —— 与 F-PK-1 三家的区别正在这里 |

顺带把 F-PK-1 的机制补精确:`cy-play-stage.begin()` 抛的是 `run`(`pages/play/components/play-stage/index.js:161`),`ballshake` / `quiethold` / `stopwatch` 三家都在自己 wxml 里 `bind:run="onRun"` 再转成 `start`,组件侧链条是完整的;**断点只在分发器那张 wxml 没给 `bind:start`**。`reaction` 不走 stage 的 run,自己在首次点击时 `_armed` 后抛 `start`(`pages/play/components/playkit-reaction/index.js:87`),同样断在分发器。

## 挂线建议(给修复批)

这条接缝本轮从「无尺子」变成「有尺子且能复现」。**先修 A 类 6 条、裁掉 C 类 8 条的口径,再把 `event-contract.js` 挂进 `ci/xcx-check.sh`** —— 现在挂就是 23 条常驻红。基线建档建议按「组件 + 事件」对(与 U4 的 `file::field::class` 同形),只拦新增。
