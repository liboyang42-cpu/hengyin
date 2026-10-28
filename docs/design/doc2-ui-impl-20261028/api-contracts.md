# 文档二 UI 实施 · 后端契约（L8，2026-10-28）

本文件是前端各车道（L1–L7）对接后端的唯一依据。来源：原型 A（b4a751ae）、B（851f1fd3）、C（a2835f24）的 README、index.html 里每一条「需后端」和联动面板。

**通用约定**
- 返回统一是 RuoYi `AjaxResult`：`{code, msg, data}`。`code=200` 成功；业务拒绝 `code=500`（或下面写明的 400 / 404 / 409），`msg` 是一句中文。
- 表里「后端原话」一栏的字符串，界面按原样显示，**不改字**。没列的拒绝文案界面统一写「没有保存成功，请再试一次。」或各自的出错句。
- 创作者接口（`/api/topic/...`）的归属闸和现有状态键接口完全一致：登录 → 可选 `scope=MERCHANT`（商家 Owner 代管，需 `PROJECT_MANAGE`）→ 主题本人。拒绝原话：「请先登录」「主题不存在」「无权操作该主题」。
- 创作者写接口还有编辑窗口闸（与 `/api/topic/update` 同口径）：主题进入售卖冻结后拒绝，原话「这个主题现在不能修改。」，`errorCode=TOPIC_FROZEN`。
- 键名（`clue.*` / `tag.*` / `counter.*`）、`preset`、`kind`、`mode` 等是机器值，**界面不显示**；只显示 `label` / `name`。
- 标「已有」的是 master / #625 已有的接口，本 PR 不改；标「新」的是本 PR 新增。

---

## 1. 可玩时段 · 开局前准备（原型 A `e-time` / `e-prep`，原型 B `t-safe`）

### 1.1 读 · 创作者　`GET /api/topic/play-setup?topicId=&scope=`　新

```json
{ "code": 200, "data": {
  "clubLed": false,
  "playWindow": { "mode": "night", "start": "20:00", "end": "06:00" },
  "prepItems": ["手机电量充足", "带手电筒"]
} }
```
- `clubLed`：主题有俱乐部（`cms_topic.club_id` 非空，E2）。为 `true` 时编辑器整行「可玩时段」不出现；后端也不执行时段。
- `playWindow.mode`：`any` 不限 / `day` 只在白天 06:00–20:00 / `night` 只在夜间 20:00–06:00 / `custom` 自定义。没配过 = `{mode:"any"}`，`start`/`end` 只在 `custom` 时有值（`day`/`night` 也回固定值，方便显示）。
- `prepItems`：最多 6 条，按作者顺序；没配过 = `[]`。

### 1.2 写 · 创作者　`PUT /api/topic/play-setup`　新

请求（JSON body，整体覆盖，两部分都要带）：
```json
{ "topicId": 123, "scope": null,
  "playWindow": { "mode": "custom", "start": "21:30", "end": "05:00" },
  "prepItems": ["手机电量充足", "带手电筒"] }
```
成功返回与 1.1 相同的 `data`（服务端规范化后）。

| 校验 | 后端原话（`code=400`，`errorCode=PLAY_SETUP_INVALID`） |
|---|---|
| `mode` 不是四种之一 | 请选择可玩时段。 |
| `custom` 缺开始 / 结束，或不是 `HH:mm`（00:00–23:59，分钟任意） | 请选择开始和结束时间。 |
| `custom` 开始 = 结束 | 开始和结束不能相同。 |
| 准备项超过 6 条 | 最多 6 项。 |
| 某条去掉首尾空白后为空，或超过 20 字 | 每项 1–20 字。 |
| 两条相同 | 已有相同的准备项。 |
| 内容安全不通过 | 现有内容安全闸原话（与状态键 label 同一道） |

`day`/`night`/`any` 时请求里的 `start`/`end` 被忽略。

### 1.3 读 · 玩家（主题详情「开局前准备」一行与半屏）　`GET /api/play/setup?topicId=`　新

不需要登录（游客也看得到）。只读已发布、未删除的主题；否则 `msg=主题不存在`。
```json
{ "code": 200, "data": {
  "playWindow": { "mode": "night", "start": "20:00", "end": "06:00" },
  "openNow": false,
  "prepItems": ["手机电量充足", "带手电筒"]
} }
```
- `playWindow` 只在 **非俱乐部主题且 `mode≠any`** 时出现，否则为 `null`（行不出现）。
- `openNow`：按服务器北京时间（Asia/Shanghai）现在是否在时段内；`playWindow=null` 时恒为 `true`。`false` 时半屏「可玩时段 · 当前未开放」、「开始游玩」置灰（界面文案归 L6）。
- 时段跨午夜（start > end）按「start 到次日 end」算；含 start、不含 end。
- B1：`playWindow=null` 且 `prepItems=[]` 时，主题详情整行不出。

### 1.4 服务端执行（开局时）　新

- 在**新建游玩局**的那一刻检查（`TopicRouteRuntimeServiceImpl` 分支局与线性旅程局两个建局点）；已有的局续玩不拦。
- 只对 **自由探索 / 个人票**（`activityId=0`，主题没有俱乐部）生效；俱乐部主题、赛事局一律不看时段（E2）。
- 拒绝时现有游玩接口照常返回错误，原话：**「当前不在可玩时段，暂时不能开局。」**
- 准备项勾选只在前端，后端不记录。未成年夜间规则仍只在 `POST /api/orient/safety/check`（已有，不改）。

---

## 2. d20 与角色设定（原型 A `e-d20` / `e-d20-more`，E1 / A7）

仍存在主题的 `journeyRules`（现有 `/api/topic/update` 保存路径与发布冻结，不新增接口），只加两个字段。

### 2.1 规则难度　`journeyRules.difficulty`　新

| 值 | 界面 | 难度线 easy/medium/hard | 情境加值上限 `modCap` | 动量 max / init / gainOnFail / maxSpend（只在动量开着时） |
|---|---|---|---|---|
| `easy` | 轻松 | 8 / 11 / 14 | 8 | 8 / 3 / 2 / 4 |
| `standard` | 标准 | 10 / 13 / 16 | 6 | 6 / 2 / 1 / 3 |
| `hard` | 硬核 | 12 / 15 / 18 | 4 | 4 / 0 / 1 / 2 |

- 前端只发 `difficulty`；服务端规范化时**按档覆盖** `tiers.*.dc`、`modCap`、`rpg.momentum` 的这四个数（其余字段如失败伤害照旧）。作者看不到数字。
- 魔鬼交易加值（轻松 +3 / 标准 +2 / 硬核 +1）和进度钟默认格数（8 / 6 / 4）是**新建时的默认值**，由前端在新建交易 / 进度钟时带上；后端不改已存的值。
- 没有 `difficulty` 的老主题：规范化结果一字不变。读的时候界面显示规则：`tiers` 正好等于某一档的三个数 → 那一档；否则显示「标准」（只是显示，保存时才写）。2.4 的 `GET /api/topic/attributes` 直接返回推断后的 `difficulty`。
- 非法值原话：「规则难度只能是轻松、标准或硬核」（`code=500`，走 `/api/topic/update` 的现有报错通道）。

### 2.2 三个开关　已有

| 界面 | 字段 |
|---|---|
| 天然 20 和天然 1 | `journeyRules.natAuto`（缺省 = 开） |
| 魔鬼交易 | `journeyRules.rpg.bargain.enabled` |
| 动量 | `journeyRules.rpg.momentum.enabled` |

### 2.3 进度钟 · 特质　`journeyRules.rpg.clocks` / `rpg.aspects`

- 进度钟（已有）：`{var, label, kind, segments, visible}`；`kind`：`danger` 坏事逼近 / `progress` 事情进展；界面只给 4 / 6 / 8 格（后端仍接受 2–12，老主题不受影响）；最多 6 个；`label` 1–20 字。`var` 由系统生成：先用 3.1 `POST /api/topic/state-keys/named`（`kind=counter`）拿到键，再写进 `var`。
- 特质（已有 + 新字段）：`{key, label, kind:"aspect", desc, atStart}`。**新** `atStart: true` = 开局自带：新建游玩局时直接挂上（与 `facet` 一样进 `statusConditions`），之后可被效果移除。缺省 `false`，不输出。最多 12 个；`label` 1–20 字，`desc` ≤ 60 字。`key` 由前端按预设生成（`[a-z][a-z0-9_]{0,31}`，如 `night_walker`），不显示。
- 预设特质（名称 → 说明，前端常量）：夜行者 夜里行动更顺手 / 受了伤 行动吃力，直到治好 / 眼尖 更容易发现线索 / 胆大 面对危险更镇定 / 手巧 开锁和修理更顺手 / 记性好 记得住看过的线索 / 被盯上了 巡夜人更容易注意到你。
- 冻结：`journeyRules` 整份随开局快照冻结（已有），新字段一起冻结。

### 2.4 属性（A1 / A2 / A3 / X4）　新

属性存在状态键注册表 `theme_state_key`，新列 `is_attribute=1`（A2）和 `attribute_preset`。每个主题最多 6 项。

**读**　`GET /api/topic/attributes?topicId=&scope=`
```json
{ "code": 200, "data": {
  "difficulty": "standard",
  "attributes": [
    { "key": "counter.observe", "label": "观察", "preset": "observe",
      "min": 0, "max": 5, "initial": 2,
      "displayType": "stars", "color": "#5B8DEF", "icon": "attr-observe", "sortOrder": 1 }
  ],
  "presetsLeft": ["nerve", "body", "insight", "agility", "charm", "fortune"],
  "max": 6,
  "binding": { "schemaVersion": 1, "registryVersion": "7", "enabledKeys": ["counter.observe"] },
  "projection": [ { "key": "counter.observe", "label": "观察", "initial": 2, "min": 0, "max": 5, "visible": true } ]
} }
```
- `attributes` 按 `sortOrder` 排（状态栏第二行取前 3，A4）。
- `binding` + `projection`：保存主题时原样放进 `journeyRules.attributeBinding` 和 `journeyRules.attributes`（属性部分），并带 `stateEnabled: true`。这是现有发布冻结（registry → journeyRules，C-1）的入口，前端不用再做「我确认采用」。每次属性写接口成功后都要重新读一次拿新的 `registryVersion`。
- **A1 旧值自动迁移**：老主题 `journeyRules.attributes` 里的 `counter.*` 行（「自定义整数值」，进度钟用的键除外）由迁移 `migration_20261028_d20_attribute_registry.sql` 一次性写进注册表并标成属性；迁移后才出现的同类行，第一次 GET 时服务端自动补登记（同一套规则，幂等）。补登记后的 `preset=custom`。

**预设（A3，作者看不到显示方式）**

| `preset` | 名称 | 范围 / 开局 | 显示 | 色值 | 图标键 |
|---|---|---|---|---|---|
| `observe` | 观察 | 0–5 / 2 | `stars` | #5B8DEF | `attr-observe` |
| `nerve` | 胆量 | 0–10 / 6 | `bar` | #E5484D | `attr-nerve` |
| `body` | 体魄 | 0–8 / 4 | `bar` | #30A46C | `attr-body` |
| `insight` | 灵感 | 0–5 / 1 | `number` | #8E4EC6 | `attr-insight` |
| `agility` | 敏捷 | 0–5 / 2 | `number` | #F76B15 | `attr-agility` |
| `charm` | 魅力 | 0–5 / 2 | `stars` | #D6409F | `attr-charm` |
| `fortune` | 运气 | 0–3 / 1 | `stars` | #FFB224 | `attr-fortune` |
| `custom` | （作者写） | 0–5 / 0 | `number` | #8B8D98 | `attr-custom` |

键名系统生成：预设 = `counter.<preset>`（被占用时 `counter.<preset>_2`、`_3`…）；自定义 = `counter.attr_1`、`counter.attr_2`…。全部 `visible=true`、`persistScope=run`。

**添加**　`POST /api/topic/attributes`　body `{topicId, scope, preset, label}`（`label` 只在 `preset=custom` 时用，其余按预设名）→ 返回新属性一行（同上 `attributes[]` 的形状）。

**修改范围**　`PUT /api/topic/attributes`　body `{topicId, scope, key, min, max, initial}` → 返回改后一行。改名不走这里（预设名不可改；自定义改名走 3.2）。

**排序**　`PUT /api/topic/attributes/order`　body `{topicId, scope, keys:[...]}`（必须正好是当前全部属性键）→ 返回 1. 的 `attributes`。

**删除**　`DELETE /api/topic/attributes?topicId=&key=&scope=` → 被用到时拒绝，见 3.4 的统一形状。

| 校验 | 后端原话 |
|---|---|
| 已满 6 项 | 最多 6 项属性。 |
| 预设已加过 / 自定义重名（去空白后与任一属性名相同） | 已有同名的属性。 |
| 自定义名称为空 | 请填写名称。 |
| 名称超过 12 字 | 名称最多 12 字。 |
| `preset` 不认识 | 请选择属性。 |
| `min ≥ max` | 最小值要小于最大值。 |
| `initial` 不在 `[min, max]` | 开局值要在最小值和最大值之间。 |
| `min`/`max` 超出 0–20 | 范围只能在 0 到 20 之间。 |
| 排序的键与现有属性不一致 | 属性已变化，请重新读取。（`code=409`） |
| 键不存在 | 属性不存在。（`code=404`） |

前端步进器本来挡住了这些，保存失败界面仍写「没有保存成功，请再试一次。」；重名、满 6 项两句界面原样显示。

---

## 3. 线索与物品 · 键名系统生成（原型 A `e-keys` / `e-init` / `e-route`）

作者只写名字，永远不打键名。

### 3.1 新建　`POST /api/topic/state-keys/named`　新

body `{topicId, scope, kind, label}`，`kind`：`clue` 线索 / `tag` 标签 / `counter` 数值（进度钟的 `var` 也用 `counter`）。

返回 `{key, kind, label}`。键名：`clue.k1`、`clue.k2`…（`tag.k*`、`counter.v*` 同理，按主题内递增，不复用）。

| 校验 | 后端原话 |
|---|---|
| 名称为空 | 请填写名称。 |
| 超过 20 字 | 名称最多 20 字。 |
| 同一主题同一种类已有同名 | 已有同名的线索。 / 已有同名的标签。 / 已有同名的数值。 |
| `kind` 不认识 | 请选择种类。 |
| 内容安全 | 现有内容安全闸原话 |

物品：现在没有主题级物品表（`ADD_ITEM` 只在玩法配置里），本 PR 不新增；见文末「未做」。

### 3.2 改名　`PUT /api/topic/state-keys/label`　新

body `{topicId, scope, key, label}` → 返回 `{key, kind, label}`。只改 `label`，键名不变；重名规则同 3.1（与自己同名不算重名）。属性的自定义名称也走这里（重名原话「已有同名的属性。」）。

### 3.3 列表 + 用在　`GET /api/topic/state-keys/usage?topicId=&scope=`　新

```json
{ "code": 200, "data": { "items": [
  { "key": "clue.k1", "kind": "clue", "label": "半张货单", "sortOrder": 1,
    "usedIn": ["节点「仓库侧门」", "初始状态"] }
] } }
```
- `kind`：`clue` / `tag` / `counter`（不含属性，属性只在 2.4）；进度钟用的 `counter` 也不在这里。
- `usedIn` 的每一项是作者可读的位置：`节点「名字」`、`章节故事流`、`主题剧情配置`、`主题数值规则`、`初始状态`、`进度钟`。空数组 = 没被用到，可以删。
- 主题里有读不懂的旧配置时，`usedIn` 不可信，整条接口返回 `msg=主题里有无法读取的配置，暂时不能查看用在哪里。`（界面用自己的出错句）。

### 3.4 删除　`DELETE /api/topic/state-keys/named?topicId=&key=&scope=`　新

- 没被用到：`{code:200, msg:"删除成功"}`。
- 被用到：`code=409`，`msg="不能删除「半张货单」"`，`data.usedIn` 同 3.3。界面弹窗标题用 `msg` 原话，正文列出 `usedIn`。
- 已有的 `DELETE /api/topic/state-keys`（返回带键名的一句话）保留不动，新界面不再调用。
- 属性删除（2.4）返回同一形状。

### 3.5 初始状态 · 开局带着　已有

`journeyRules.attributes` 里 `clue.*` / `tag.*` 且 `initial=1` 的行，开局写进背包线索 / 标签（`JourneyInitialState.initialize`）。行的 `label` 用 3.1 返回的 `label`。开局物品：未做（待业主决定 2）。

---

## 4. 游玩菜单 · 联系主办 / 联系商家（原型 B T-37，B6 / B7 / B9）

`GET /api/play/contact?topicId=&nodeId=`（主题局）或 `GET /api/play/contact?activityId=`（赛事）　新

需要登录（`msg=请先登录`）。
```json
{ "code": 200, "data": { "kind": "club", "name": "城南徒步俱乐部", "phone": "13800000000" } }
```
- 俱乐部主题（`cms_topic.club_id`）和赛事（`cms_activity.club_id`）→ `kind=club`，俱乐部 `name` / `phone`（`Club.phone`）。菜单行写「联系主办」。
- 自由探索（主题没有俱乐部）→ 当前站 `nodeId` 的商家：节点 `merchant_member_id` → `MmsMerchant.name` / `phone`，`kind=merchant`。菜单行写「联系商家」。`nodeId` 必须属于该主题。
- 没有号码（没俱乐部 / 节点没商家 / 电话为空 / 俱乐部或商家已停用）→ `data=null`，整行不出。
- 不提供值班电话；号码只用于拨号确认框后 `wx.makePhoneCall`，界面不显示号码（B9）。拨号框正文「拨打「城南徒步俱乐部」的电话？」用 `name`。

---

## 5. 我的申诉 · 账号信誉（原型 B T-33 / T-34，原型 C，X7 / X8 / C1）

### 5.1 我的申诉列表（跨局）　`GET /api/orient/scoring/mine?status=&pageNum=&pageSize=`　新

需要登录。只返回本人的**计分申诉**和**成绩争议**（C1），新的在前。`status` 可选：`open` 处理中 / `closed` 已有结论；不传 = 全部。`pageSize` 缺省 20，最大 50。
```json
{ "code": 200, "data": { "rows": [
  { "kind": "dispute", "id": 12, "status": "open", "sessionId": 88001,
    "topicId": 5, "topicName": "城南旧事 · 夜行定向", "nodeId": null,
    "createTime": "2026-10-28 21:10:00", "resolutionNote": null }
], "total": 1 } }
```
- `kind`：`appeal` 计分申诉 / `dispute` 成绩争议。
- `status`：`open` 处理中 / `accepted` 已采纳 / `rejected` 已驳回（后两者合称「已有结论」）。
- `resolutionNote` 是后台填写的结论理由（后端原话，不改字）；列表行不显示，点开落到那一局的本局成绩全屏层（原型 A）。
- `nodeId`：计分申诉针对的节点（界面写「第 N 个节点」由前端按局内顺序算）；成绩争议为 `null`。

### 5.2 提交（已有，X8 已满足）

| 接口 | 理由校验原话 |
|---|---|
| `POST /api/orient/scoring/appeal`（计分申诉） | 请填写 1-500 字的理由 |
| `POST /api/orient/scoring/dispute`（成绩争议） | 请填写 1-500 字的理由 |
| `POST /api/audit/guard/appeal`（到达申诉） | 申诉理由不能为空 / 申诉理由不能超过 500 字 |

三处都在服务端去首尾空白后校验 1–500 字。本 PR 不改这三条接口。

### 5.3 账号信誉　`GET /api/member/reputation`　新

需要登录。
```json
{ "code": 200, "data": { "status": "normal" } }
```
- `status`：`normal` 正常 / `restricted` 受限。不返回分数、门槛或原因（X7）。
- 判定（现有数据里唯一的账号级限制）：本人有任何一条**仍有效**的俱乐部治理封禁（`club_governance_ban` 状态 ACTIVE 且未过期，来源俱乐部或平台）→ `restricted`；否则 `normal`。
- 半屏两行文案归 L7（「正常 · 可以组队和报名赛事」「受限 · 暂时不能申请公开招募队伍和报名赛事」）。联系主办 / 求助不受影响。

---

## 6. 发票（原型 C，C2 / C3 / C4）

#625 的发票接口（`/api/invoice/*`）不改，D1–D6 行为不变。C2（后台的待开具 / 已认领 / 处理中 / 结果待确认对玩家统一叫「开具中」）、C3（只显示发票号、不给下载和申请号）、C4（全选本单位）都是前端映射，不需要后端改动。

订单详情的单笔开票状态：前端先用现有 `POST /api/invoice/list` 按订单号比对（原型 C「需后端」第 2 条）。本 PR 不新增接口，列在「未做」。

---

## 7. 未做 / 待业主决定（前端不要等这些）

- 物品合并进「线索与物品」：没有主题级物品表，需另立表或从玩法配置里汇总，范围大，本 PR 不做。前端暂只列线索 / 标签。
- 「用在」不包括玩法模板里的物品引用（同上）。
- 开局物品（待业主决定 2）、角色名 / 称号（待业主决定 1）：业主未定，不做。
- publish-check 问题带作者可读位置和种类：本 PR 不改 publish-check；前端按 `nodeId` 显示节点名（原型 A 已这样画）。
- 冻结属性的范围和显示方式进开局快照：已由 #625 的 `attributeDefinition`（`freezeDefinition` 输出 `displayType` / `color` / `icon` / `sortOrder` / `min` / `max`）覆盖，前提是保存时带了 2.4 的 `binding`。
- 赛事榜单、并列名次、安全须知版本号、到达申诉进跨局列表、订单单笔开票状态：原型 B / C 标了「需后端」，不在本 lane 的已知清单里，留待后续。
- 打卡结果不明（`/api/play/checkin` 没有 UNKNOWN）：按原型由前端判定超时。

## 8. 迁移

- `chengyinhub-admin/sql/migration_20261028_d20_attribute_registry.sql`：`theme_state_key` 加 `is_attribute`、`attribute_preset`；把老主题 `journeyRules.attributes` 的 `counter.*`（进度钟键除外）登记进注册表并标成属性（A1）。
- `chengyinhub-admin/sql/migration_20261028_topic_play_setup.sql`：新表 `cms_topic_play_setup`（可玩时段 + 开局前准备，一主题一行）。
- 先落库再部署 jar（chengyin-db-migrate 只读预检 + 用户确认）。
