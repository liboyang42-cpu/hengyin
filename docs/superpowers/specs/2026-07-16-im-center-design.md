# 流 5 · IM 消息中心 — 设计方案

- 日期:2026-07-16
- 分支:`feat/s0716-1307`
- 状态:设计已确认,待出施工计划

## 1. 背景与任务简报校准

原始任务简报有两处与代码实况不符,已按「按代码实况打勾,不按记忆」核过码并更正:

| 简报说法 | 代码实况 | 影响 |
|---|---|---|
| 「全仓无 mute 字段,需要新建」 | `im_conversation_member.muted` **已存在**:`chengyinhub-admin/sql/migration_im.sql:32`、`ImConversationMember.java:32` + getter/setter、`ImConversationMemberMapper.xml:26,32,40` 三条 select 都已查出 | **零迁移**。缺口在上层没接线,不在建表 |
| 「IM 现在只有单一列表」(隐含三 tab 要从零建) | `ImConversation` 已定义四类:`TYPE_SINGLE=1` / `TYPE_SYSTEM=2` / `TYPE_MERCHANT=3` / `TYPE_GROUP=4`,且 `listConversations` 已返回 `type` | 分类数据基础已在,前端纯消费 |

另外扫出一个**既有安全缺陷**(非本次引入),见 §3。

## 2. 已确认的决策

| # | 决策点 | 结论 |
|---|---|---|
| 1 | 免打扰落法 | **后端持久化**(碰 `ImServiceImpl` / `ApiImController` / `ImConversationMemberMapper.xml`);零迁移,只补接线 |
| 2 | 四类会话塞三 tab | **私信 = 1 + 3(官方客服)** / 俱乐部 = 4 / 系统 = 2。依据:分 tab 的真轴是「能不能回」——客服双向可回,系统单向广播 |
| 3 | 群聊主题上下文 | **身份 + 聊天页顶栏**。后端 `counterpartyOf` join `club` 表填真名/logo/人数 |
| 4 | 富消息卡范围 | **渲染 + 用户发位置/路线 + 封洞** |
| 5 | tab 角标 | **扩 `cy-tabs` 加可选 `badge`**(越出 `im/**` 域,已授权) |
| 6 | mute 语义 | **仍累未读,不计全局红点**(微信同款):`addUnread` 照旧,`sumUnread` 加 `and muted = 0` |
| 7 | 路线实体 | **`cms_topic`**(主题/活动),非 `preset_route`(后台编辑器模板素材) |
| 8 | route 卡新鲜度 | **现拉,永远最新**(非快照)。连带代价见 §4.2 |

### 禁区说明

任务简报提到「禁区那 8 个文件」,但全仓 grep 零命中(无任何 md 记录),该约定仅存在于用户的并行流分工中。用户在知悉「本方案会碰 `ImServiceImpl` / `ApiImController` / `ImConversationMemberMapper.xml` 三个后端文件」的前提下选择了后端持久化方案,据此认定这三个不在禁区。**若实际在禁区,需回退到前端本地存储方案(代价:换设备即丢,且服务端仍累未读、红点照亮)。**

## 3. 承重墙:卡片协议与封洞

### 3.1 既有安全缺陷(非本次引入)

`ImServiceImpl.send()` 对 `msgType=3`(系统卡片)完全没设防:

- 内容安全只有两个分支:`if (type == MSG_TEXT)` 走文本检测、`else if (type == MSG_IMAGE)` 走图片异步检测。`type == 3` **两个都不进**,直接 `checkStatus = 1`(判定「已通过」),连微信内容安全都不调
- 没有任何「只有系统能发 `msgType=3`」的校验,`extra_json` 原样落库
- `ApiImController.send` 是普通用户端点,`msg_type` 与 `extra_json` 均由客户端传

**后果**:任何登录用户可发一张伪造的「官方」卡片——`title` / `sub` / 按钮文案 / `action` 跳转 URL 全可控,且绕过内容安全。伪造「报名成功,点此领奖」+ 任意跳转 = 站内钓鱼。

一旦开放用户发 `msg_type=3`(决策 4),此洞就从「理论可能」变成「有现成入口」,故封洞与本功能强绑定,留在本 PR。

### 3.2 协议

`extra_json` 增加 `cardType` 判别字段。**老卡片没有 `cardType` → 归 `generic`,走现有渲染路径不变**(向后兼容)。

⚠️ **更正(2026-07-16,Task 2 复审时核码发现)**:早期草稿写「全仓唯一的卡片生产者是 `AuditWorkbenchController.java:102`」——**错的,实为 4 处**:

| 生产者 | 发什么 |
|---|---|
| `AuditWorkbenchController.java:102` | 审核结果 |
| `ApiRegistrationController.java:308` | **报名成功** |
| `ApiRegistrationController.java:388` | **报名成功** |
| `OfficialBroadcastServiceImpl.java:112` | 官方广播(带 `bcId`) |

漏掉的原因:当初那条 grep 写了 `grep -v "ImMessage"`,而这几行恰好都含 `ImMessage.MSG_CARD` → **被自己的过滤器藏了**。结论方向不变(向后兼容仍成立),但**生产者越多,`generic` 渲染路径不可回归就越要紧**。

**连带更正**:「报名卡」**早就存在**,只是发的是一张光秃秃的通用卡 —— `"{\"title\":\"报名成功\",\"sub\":\"你的报名已提交，可在此查看进度\"}"`,**无 `action`、点不动、不链到任何地方**。这正是原始需求所指的「系统卡片只有通用 title/sub/meta/buttons」。用户 2026-07-16 拍板:**做肥**(见 `signup` 行)。

| cardType | 谁能发 | extra_json 内容 |
|---|---|---|
| `location` | 用户 | `{cardType, name, address, lat, lng}` |
| `route` | 用户 | `{cardType, topicId}` — **仅此两字段** |
| `signup` | 仅 `sendSystem` | `{cardType, topicId}` — 与 `route` 同构,**复用同一套现拉+渲染+降级机制**,仅标题文案不同 |
| `generic` | 仅 `sendSystem` / `sendFromAdmin` | 现状 `{title, sub, meta, buttons, action}` |

`signup` **不进用户白名单**(`USER_CARD_TYPES` 仍只有 `location`/`route`)—— 用户不得伪造报名卡。

### 3.3 封洞原则:用户只能传 id,展示字段一律服务端重建

在 `ImServiceImpl.send()` 中,`type == MSG_CARD` 时走 `sanitizeUserCard()`:

- **白名单**:`cardType` 不在 `{location, route}` → `throw`。用户永远发不出 `generic`
- **`route`**:只取 `topicId`,查 `cms_topic`;不存在 / 未上架 → `throw`。落库的 `extra_json` 只有 `{cardType, topicId}`,客户端传的一切展示字段丢弃
- **`location`**:`lat` / `lng` / `name` / `address` 是 `wx.chooseLocation` 的产物,服务端无从「重建」,故走净化:
  - 字段白名单(只保留这四个)
  - `lat` ∈ [-90, 90]、`lng` ∈ [-180, 180],非数值或越界 → `throw`
  - `name` / `address` 补进 `checkText` 内容安全 —— **这正是现在 `type==3` 绕过的那条**
- **重建后的 JSON 绝不含 `action` / `buttons` / `bcId`**(钓鱼三载体)。渲染层对 location / route 走**写死的跳转**:route → 主题详情 `?id=topicId`,location → `wx.openLocation`。**不读 extra_json 里的 action**
- **`content` 由服务端重建**(`"[位置] " + name` / `"[路线] " + topic.getName()`)。

  ⚠️ **更正(2026-07-16,写施工计划时核码发现)**:本条**不是安全需求**。`ImServiceImpl.java:366` 现有代码
  `String summary = type == MSG_TEXT ? content : (type == MSG_IMAGE ? "[图片]" : "[消息]");`
  已经把卡片的列表预览**硬编码成 `"[消息]"`**,不走客户端 `content` —— 早期草稿里「用户可在别人的列表预览伪造 `"[系统] 你中奖了"`」的判断**是错的**(没读完函数尾巴就下结论)。

  重建 `content` 的真实理由是**预览可用性**:现状所有卡片预览都显示 `"[消息]"`,重建后显示 `"[位置] XX公园东门"` / `"[路线] XX主题"`。
  故本条实现时需**同步改 `:366` 的三元表达式**,给 `MSG_CARD` 加一个分支用重建后的 `content`,否则重建了也传不到列表。

### 3.4 为什么白名单加在 `send()` 就够

**`sendSystem` 不走 `send()`** —— 它自己组装 `ImMessage` 并 `insertImMessage`(`ImServiceImpl` 内独立一段,`setCheckStatus(1)` 后直接插)。故 `send()` 的白名单对官方卡零影响。

## 4. 数据流

### 4.1 三 tab

纯前端分类(后端已返回 `type`):`type 1||3 → 私信` / `4 → 俱乐部` / `2 → 系统`。

「私信」tab 内,`type == 3`(官方客服)行加「官方」标签以与真人私信区分(决策 2 所选方案含此项)。

**角标口径必须与全局红点一致**:tab badge = 该类下**未静音**会话的未读之和。否则会出现「tab 上有 3、全局红点不亮」的自相矛盾。

### 4.2 route 卡现拉(决策 8)与其代价

`chat/index.js` 的 `decorate()` 收集本批消息中所有 `route` 卡的 `topicId` → **去重** → 并发拉 `/api/topic/info-to-user` → 存入页面级 `Map` 缓存。

一屏 30 条消息里路线卡通常 0–2 张,不值得为此新增批量端点(真的多了再说)。

**不可避免的例外:`content` 仍存快照。** `content` 会进 `updateLastMessage` → 成为会话列表预览文本,而列表页不可能为每行现拉主题。故服务端发送时写死 `content = "[路线] " + topic.getName()`。

**净结果**:卡片正文永远最新,列表预览是发送时刻的快照。主题改名后,列表显示旧名、点进去是新名。这是选「现拉」买单的地方,已确认接受。

### 4.3 群聊上下文

`counterpartyOf` 的 `TYPE_GROUP` 分支从 `bizKey`(`club_<id>`)解析 clubId → `ClubMapper` 查 `name` / `logo` / `member_count`,填入 `counterparty`。

这干掉了 `ImServiceImpl.java:119` 那条注释(「前端据 `club_<id>` 取俱乐部名/头像」)所描述的 **前端 N 次额外 HTTP 请求** —— 前端现在从一次 `listConversations` 响应里拿到全部俱乐部身份,零额外请求。

⚠️ **措辞更正(2026-07-16,Task 3 审查指出)**:早期草稿写「后端**一次 join**」——**不准确**。实现是在循环里每个群会话调一次 `clubMapper.selectClubById`,**服务端仍是 N 次查询**,没有 SQL JOIN 也没有批量。

准确的说法是:**把 N+1 从「客户端 N 次 HTTP」降到「服务端 N 次 DB 查询」**。这与 `listConversations` 既有的每会话一次 `selectImConversationById` 同构(该方法本来就是 1 + N 次查询),故未引入新的量级问题,但**也没有消灭 N**。真要批量化需新增 `ClubMapper.selectClubsByIds(List<Long>)`,超出本轮文件域 —— 会话数量级(几十)下不值得,**若将来列表变慢,这里是第一个该看的地方**。

聊天页顶栏:`type == 4` 时显示「XX俱乐部 · N人」。

⚠️ **`cy-nav-bar` 没有 `title` slot**(只有 `actions`,`title` 是 String property)—— 早期草稿的 `<view slot="title">` 是臆造的。故副标题退化为**单行 title**(`{{name}} · {{memberCount}}人`),「查看俱乐部」挂进已有的 `onMore` actionSheet。

⚠️ **前端会吞掉后端字段(T8 实施者发现,不做则本节静默失效)**:`list/index.js` 的 `loadConversations` 把 `counterparty` 重建成只有 `{nickname, avatar}` 的字面量 → §4.3 后端辛苦 join 出来的 `clubId`/`memberCount` **被丢在地上**(连 `id`/`bizKey` 一起),`data-clubid` 恒为 0、顶栏永远不显示人数,**零报错、门禁全绿**。这正是「回执 ≠ 观测」:后端 put 了 ≠ 前端拿到了。

修法是把那个字面量改成 `Object.assign({}, cp, {nickname: ..., avatar: ...})`,保留后端全部字段(含将来新增的)。**已排查 `chat/index.js` 全程用 `Object.assign` 保留字段,无同类问题。**

顺带:群聊菜单去掉「拉黑」—— `doBlock` 从最近一条对方消息取 `senderId`,群里等于拉黑随机某个成员,本就无意义。

### 4.4 路线选择器

`+` → 路线 → `cy-sheet` 弹层 + `cy-search`,数据走 `/api/topic/list`(已支持 `is_my` / `keyword`)。**零新端点。**

## 5. 文件清单(共 17 个,零迁移)

### 前端 `chengyinhub-xcx/subpackageB/pages/im/**` — 8 个(域内)

| 文件 | 改什么 |
|---|---|
| `list/index.wxml` | 加 `cy-tabs`;行内静音图标;未读角标灰化;左滑第三个按钮 |
| `list/index.js` | tab 分类与切换;`badge` 计算;mute 调用;`OPEN` 240→360(三按钮) |
| `list/index.wxss` | 静音图标、灰角标、三按钮宽度 |
| `list/index.json` | 引 `cy-tabs` |
| `chat/index.wxml` | 群聊顶栏;`cardType` 分支渲染;`+` 菜单 |
| `chat/index.js` | 卡片解析;位置/路线发送;主题现拉+缓存;群聊 club 信息 |
| `chat/index.wxss` | location / route 卡样式 |
| `chat/index.json` | 引 `cy-sheet` / `cy-search` |

### 越界前端 — 3 个(已授权)

`components/cy/tabs/index.{js,wxml,wxss,json}`:加**可选** `badge` 字段(`tabs=[{key,label,badge}]`),内嵌既有 `cy-badge` 组件渲染,不传不渲染 → 现有使用页零影响。

⚠️ **数字更正 + 方案更正(2026-07-16,Task 4 实施)**:
- 早期草稿写「现有 18 个使用页」——**实测是 8 个**(`usingComponents` 注册 cy-tabs 的页)。「18」是文件命中数的重复计数(8 json + 10 wxml,每页 json+wxml 各算一次)外加 2 个裸 CSS 类名假阳性。
- 早期草稿准备**手写** badge 样式,且引用了**全仓不存在的** `--cy-font-micro`(死引用,会静默失效)。已改为**复用既有 `cy-badge`**(`type=count`/`variant=danger`/`max=99`/负数小数防护齐全),不制造第 2 套角标 —— 手写正中 memory `chengyin-ui-audit-20260702` 记的病根「组件层零采纳」。故 T4 文件多了 `index.json`(引 cy-badge)。

选它而非「im 页自建局部 tab」的依据:memory「城瘾 UI真机审计 7-02」记载病根是**组件层零采纳(5 种 tab 各写各的)**,再造一个即重蹈覆辙。

### 后端 — 5 个

| 文件 | 改什么 |
|---|---|
| `IImService.java` | `setMuted` 声明 |
| `ImServiceImpl.java` | 四处:`listConversations` 补 `muted`;`counterpartyOf` join club;`send()` 加 `sanitizeUserCard`;`setMuted` 实现 |
| `ApiImController.java` | `/api/im/mute` 端点 |
| `ImConversationMemberMapper.java` | `updateMuted` 声明 |
| `ImConversationMemberMapper.xml` | `updateMuted` 语句;`sumUnread` 加 `and muted = 0` |

### 测试 — 1 个

`ImServiceImplTest.java`(已存在)加用例,见 §7。

### 迁移:零

`muted` 字段已在库里。**本 PR 不含任何 `chengyinhub-admin/sql/` 改动,无需 `chengyin-db-migrate` 前置。**

## 6. 错误处理(全是降级,不是抛错)

| 场景 | 行为 |
|---|---|
| `club` 查不到(俱乐部已删) | 退回死值 `"群聊"`,**不 NPE** |
| `topic` 拉不到 / 已下架 | 卡片渲染**降级态「该路线已下架」**,不是空白卡。这是「现拉」的必付代价,必须有此态 |
| mute 请求失败 | toast + UI 回滚(不留假状态) |
| location 的 `name`/`address` 内容安全不过 | `throw` 中文提示(沿用 `IImService` 既定 pattern:业务校验失败抛 `RuntimeException`,控制器转 `AjaxResult.error`) |

## 7. 测试:每条都带负控

memory「假绿测试的 4 种模式」记载一天栽 4 次,故**每条断言都要能让绿变红**(注入 → 红 → 撤销 → 绿)。

| 用例 | 负控(撤掉修复后必须变红) |
|---|---|
| 用户发 `generic` 卡 → throw | 去掉白名单 → 必红 |
| route 传伪造 `name` → 落库 `extra_json` 只有 `{cardType, topicId}` | 去掉重建 → 必红 |
| location 的 `name` 含违规词 → throw | 去掉 `checkText` → 必红(证明它真被调用) |
| 发 location 卡 → `lastMsgText` == `"[位置] XX公园"`(非 `"[消息]"`) | 去掉 `:366` 的 MSG_CARD 分支 → 必红。**注意这是预览可用性,非安全**(见 §3.3 更正) |
| muted 会话不计入 `sumUnread` | 去掉 `and muted = 0` → 必红 |

最后一条尤其关键——它是**唯一**能证明免打扰真的起作用的断言。

## 8. 部署影响分类

按全局铁律「五类分类」:

- **①后端运行时**:`IImService.java`、`ImServiceImpl.java`、`ApiImController.java`、`ImConversationMemberMapper.java`、`ImConversationMemberMapper.xml` → Flow 真部署
- **②小程序发布**:`subpackageB/pages/im/**`(8)、`components/cy/tabs/**`(3) → ECS no-op,**另走微信上传**
- **③数据库迁移**:**无**
- **④CI/CD 控制面**:无
- **⑤无 ECS 运行时**:本设计文档

**无新增 `@Component("xxxTask")` 定时任务,故无需 `insert into sys_job` 注册 SQL。**

## 9. UI 验收闸门

memory「UI 改动截图闸门」:UI 做完必开 DevTools 截图自审 + 给用户看图,用户点头才 ship。本流是 UI 重头戏(三 tab / 群聊顶栏 / 两种新卡 / 静音态),**截图自审是 ship 的前置**,走 `wechat-miniprogram-verify` skill(automator 自证,不肉眼截图)。

需覆盖的态:三 tab 各自 + 空态 + 静音行 + 群聊顶栏 + location 卡 + route 卡 + route 降级态。

## 10. 视觉设计

> 真源:`style/tokens.wxss`(唯一视觉变量)、`style/components.wxss`、DS 文档 `~/Documents/城瘾/小程序/DS设计文档_救出_20260714/design-system.md`。
> 本节补齐 §1–§9 缺失的视觉层。**没有本节,照 §1–§9 施工会做出功能正确但视觉平庸、且仍带 P0 的页面。**

### 10.1 主题事实(核过码)

- `tokens.wxss:18` 的 `page{}` = **深色基础层**;`:293` 的 `page.theme-light, .theme-light, .theme-merchant` = 浅色 scope,**由页面自己在 wxml 根节点挂**。
- `utils/theme.js` 的 `applyTheme()` **不挂 CSS class**,只调 `wx.setNavigationBarColor` 管原生导航栏。DS 文档「经 `utils/theme.js` 在根节点挂 `class="theme-light"`」一句**与代码不符**,以代码为准。
- **IM 两页均未挂 `theme-light` → 深色页。** 所有配色按深色值验算。

### 10.2 P0:对方气泡不可读(既有缺陷,本 PR 修)

`chat/index.wxss:26`:`.bubble.other { background: #ECECEE; color: var(--cy-text-title); }`

深色主题下 `--cy-text-title` → `--cy-color-text-primary` = `#F6F7FA`。即 **`#ECECEE` 浅灰底 + `#F6F7FA` 近白字 = 对比度 1.10:1**(WCAG AA 正文需 4.5:1)。**对方发来的每条文字消息基本不可见。**

两重违规:①硬编码色值(DS 禁止)②深色页里塞浅色底。未被发现是因为小程序尚未投入使用(memory `chengyin-no-real-users-yet`)。

### 10.3 颜色叙事:让紫回到它该在的地方

DS §2.1(2026-07-14 用户定)强约束:主 CTA = **黑/白反色中性,不是紫**;紫**只给「选中 tab / 进度条 / 稀有徽章·等级」三类,不铺按钮底、不做大面积**;**蓝 = 信息提示 / 链接 / 系统消息**。

现状恰好反着:`.bubble.me` 是大面积紫(违反「不做大面积」),而 `cy-tabs` 选中态才是紫的正当用途,却全站仅 1/98 页采纳(DS §4 记为「全局一致性最大缺口」)。

| 元素 | 现状 | 改成 | 实测对比度 | 依据 |
|---|---|---|---|---|
| 自己气泡 | 紫 `#7A5CFF` 大面积 | `--cy-color-action-primary-bg/fg`(星白底 `#F6F7FA` + 墨字 `#09080C`) | **18.64:1** | §2.1 主 CTA=黑/白 |
| 对方气泡 | `#ECECEE` 硬编码 | `--cy-color-bg-elevated`(`#18151F`)+ `--cy-color-text-primary` | **16.81:1** | 修 P0 §10.2 |
| 对方气泡内次要文字 | — | `--cy-color-text-secondary`(`#B2B4BC`) | **8.70:1** | — |
| 选中 tab | — | 紫 `--cy-color-selection` | — | §2.1 紫的正当用途 |
| 系统卡强调色 | 紫 `--cy-accent` | 蓝 `--cy-color-status-info` | — | §2.1「蓝 = 系统消息」 |

**净效果**:中性底 + 黑白气泡,紫只在选中 tab 那一下亮起——正是 §2.1 点名的 Threads / Linear 那套克制。

### 10.4 Token 纪律

- **新写的样式一律用 `--cy-color-*` 语义 token**(DS 第 5 行:「新组件/迁移页只读 `--cy-color-*`,禁止硬编码色值与旧名」)。
- **不动 im 页既有的旧 alias**(`--cy-bg-card` 等)——避免把外科手术变成整页 token 迁移。例外:§10.2/§10.3 明确要改的几行。
- 遵循 DS §3:字阶 `card-title 32 / body 28 / label 24 / caption 22 / micro 20`(rpx,**micro 20 为下限**);间距 8rpx 网格,页边距 `--cy-page-x = 32rpx`;圆角 `sm 16 / md 24 / lg 32 / pill 999`;**暗底阴影 `none`**(现 `.badge` 的 `box-shadow: rgba(237,0,6,.22)` 属违规,顺手去掉)。
- 遵循 DS §7 动效:tab 切换 `motion-fast 140ms`,缓动 `cubic-bezier(.4,0,.2,1)`;`prefers-reduced-motion` 已全局接入。

### 10.5 新元素视觉规格

**location 卡**
- 结构:定位针图标(CSS 手绘,复用 list 页 `.bell` 的手绘路数)+ `name`(card-title 32rpx / text-primary)+ `address`(label 24rpx / text-tertiary)+ 「导航」行动区
- 底 `bg-elevated`,圆角 `md 24rpx`,无阴影(暗底)
- **不做地图缩略图**:微信静态图 API 需额外 key,且深色地图样式另需调校 —— YAGNI,先用图标 + 地名 + 地址
- 点击 → `wx.openLocation`(**写死,不读 extra_json 的 action**,见 §3.3)

**route 卡**
- 结构:主题图(`img_url`,16:9,圆角 `sm 16rpx`)+ `name`(card-title)+ 「XX km · N 个点」(label / text-tertiary)
- 底 `bg-elevated`,圆角 `md 24rpx`
- 点击 → 主题详情 `?id=topicId`(**写死**)
- **三态**(⚠️ 早期草稿只写了「成功/降级」两态,漏了 loading —— T9 实施者指出):
  - `topicCache[id]` 为对象 → 正常卡
  - `=== null`(确认拉不到/已下架)→ 中性占位块 +「该路线已下架」(text-tertiary)。这是 §4.2「现拉」的必付代价
  - `undefined`(还没拉完)→ 占位块 +「加载中…」。**不能与 null 混为一谈** —— 两者都是 falsy,若共用 `wx:else` 则每张好卡片首次渲染都会闪一下「已下架」,是误导。`onRouteTap` 同理:只在 `=== null` 时拦,加载中放行(跳转只需 topicId)

**静音图标**(会话行)
- 复用 list 页已有的 CSS 手绘 `.bell`,叠加一条 45° 斜杠(`::before`,2rpx,`text-tertiary`)
- 色 `--cy-color-text-tertiary`,尺寸 micro 级

**未读角标灰化**(静音会话)
- `.badge` 背景 `--cy-color-status-danger` → `--cy-color-text-tertiary`,并去掉 `box-shadow`(DS §3:暗底阴影 none)
- 这是 mute「仍累未读但不喧哗」语义(决策 6)的视觉落点

**cy-tabs badge**
- 红底(`--cy-color-status-danger`)+ 白字,字阶 `micro 20rpx`(DS 下限),圆角 `pill`
- 内嵌既有 `cy-badge`(`type=count`),随可选 `badge` 字段渲染;`wx:if` 套外层 wrapper,不传即不创建实例 → 现有 8 个使用页零影响

**「官方」标签**(私信 tab 内 `type == 3` 行)
- `cy-tag` 组件(DS §4 已有),蓝 `status-info`(§2.1「蓝 = 系统消息」),字阶 `caption 22rpx`

### 10.6 本节带来的文件清单增量

§5 的 17 个文件不变——§10 的改动全部落在**已列入的** `chat/index.wxss`、`list/index.wxss`、`components/cy/tabs/index.wxss` 之内。新增 `chat/index.json` 可能需引 `cy-tag`(若「官方」标签落在 chat 页顶栏)。

**验收:§9 截图自审必须逐态比对本节的对比度数字**,不能只看"感觉还行"。

### 10.7 全量对比度审计(2026-07-16,T7 撞坑后系统性排查)

**病根**:「深底配反色字」。`--cy-color-text-inverse`(`#09080C` 近黑)是给**亮底**(紫/红/白)用的;一旦背景换成中性深色(`bg-elevated #18151F`)还沿用它,就是 1.1:1 级的不可见。

**已犯两次**:①`.bubble.other`(既有 P0,§10.2)②`.act-mute`(**本轮我自己新写的**,T7 实施者拦下 —— 我照着亮底的 `.act-read`/`.act-del` 类推,漏看了背景性质已变)。

故对本轮所有新增 CSS 做了全量验算(深色主题,soft 底按 alpha 合成到实际父底):

| 元素 | 背景 | 前景 | 对比度 | 判 |
|---|---|---|---|---|
| `.bubble.other`(T5) | `bg-elevated` | `text-primary` | **16.81:1** | ✓ |
| `.bubble.me`(T5) | `action-primary-bg`(亮) | `action-primary-fg` | **18.64:1** | ✓ |
| `.act-mute`(T7·修前) | `bg-elevated` | 继承 `text-inverse` | **1.11:1** | ✗ **已修** |
| `.act-mute`(T7·修后) | `bg-elevated` | `text-primary` | **16.81:1** | ✓ |
| `.act-read`(既有) | `brand`(亮紫) | `text-inverse` | 4.57:1 | ✓ 亮底配反色字成立 |
| `.act-del`(既有) | `danger`(亮红) | `text-inverse` | 4.00:1 | ✓ 同上 |
| `.official-tag`(T6) | `info-soft` 合成于 `bg-surface` | `status-info` | 5.06:1 | ✓ |
| `.badge.is-muted`(T7) | `text-tertiary` | 继承 `text-inverse` | 6.03:1 | ✓ |
| `.rcard-flag`(T9) | `success-soft` 合成于 `bg-elevated` | `status-success` | 5.73:1 | ✓ |
| `.rcard-title`(T9) | `bg-elevated` | `text-primary` | 16.81:1 | ✓ |
| `.rcard-sub`(T9) | `bg-elevated` | `text-tertiary` | 5.44:1 | ✓ |
| `.rcard-foot`(T9) | `bg-elevated` | `status-info` | 5.28:1 | ✓ |

门槛:正文 4.5:1 / 大字·图形·组件 3:1。**`.act-mute` 是唯一一处,T9/T10 无同类地雷。**

**防复发**:`.act-mute` 的铃铛图标改用 `currentColor` 跟随父级色 —— 以后谁改 `.act-mute` 的 `color`,图标不会与文字脱节。**新写深底元素时,先问「这个 `text-inverse` 是给亮底用的吗?」**
