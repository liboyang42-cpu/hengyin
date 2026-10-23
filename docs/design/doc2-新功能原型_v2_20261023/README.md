# Doc 2 新功能 · 交互原型 v2（2026-10-23）

v1（`feature/doc2-proto-20261023` 分支的 `docs/design/doc2-新功能原型_20261023/`）被否掉了，原话是：「完全不顾我们现在的页面风格；很多想法只是小功能，却把整页都改了。」

v2 换了做法：**先找到每个新功能在小程序里本来就该出现的那一页，按那一页的真实 wxml / wxss 1:1 复刻，然后只把这个功能需要的那一行、一个按钮、一个弹层或一张小卡加上去。** v1 只当「要展示什么」的清单用（功能、接口、错误码、待决问题都沿用），视觉全部丢掉。

**本目录只有原型文件，没有改动任何小程序、后台或后端代码。**

## 2026-10-23 owner 审阅 · 已落实

同一个文件、同一套做法（每屏按真实 wxml / wxss 复刻，只加新元素，粉色虚线框 + NEW + 「显示改动」开关）。逐条：

1. **f1-topic 开局安全检查**：保留。打磨：改成和上面「我的游玩记录」完全同一行形（图标 + 一行字 + 箭头），说明收进半屏。
2. **f1-ticket**：去掉「开局前确认」半屏。点票卡直接进游玩页，prepare + start 在后台静默完成，规则版本自动确认（start 回传 prepare 给的 ackConfigVersion）。这一屏现在就是没改过的票夹（标「无新元素」，只保留为点击路径）。
3. **f1-play HUD**：顶栏不加任何新东西，HUD 槽只放现有的生命值 / 背包。本局用时（副标题 = 阶段）、当前得分（点开 = 计分明细）进右上「…」游玩工具菜单，行形照菜单现有的「本局读数」和「队伍位置」。
4. **f1-guard / f1-task**：各只留一句话 + 一个动作。到站被拦 =「本次到达未记录 · 申诉」，点申诉是微信原生确认框，不再开半屏；替代完成 =「找不到？换个方式：扫码 · 拍照 · 答题」一行紧凑列表；玩家看不到错误码、守卫编号和判定解释。
5. **f1-team、f1-score、f1-result**：保留。打磨：计分明细去掉 TASK / PENALTY 等代码和「只增不改」，结算卡去掉回执编号，队伍进度行距对齐成员行。
6. **f1-appeals「我的申诉」**：入口是设置（pages/shezhi）里「帖子处置与申诉」下面的一行，同款双行 cy-cell；f7-settings / mn-home / mn-invite 三个设置屏同步更新。
7. **F2 属性是什么 / 在哪配**：新增两屏作者端。**f2-attr**：主题详情弹窗（`pages/publish/fabu/topic-detail-sheet.wxml`）「值域」卡下面加「角色属性」卡（名称、范围、显示为 数字 / 条 / 星、跨局保留），编辑弹层照抄同页「编辑数值」（valueEditor）。**f2-checkcfg**：编辑玩法模板（`pages/publish/temp`）D20 检定段「判定什么技能」下面加「用哪个角色属性加值」下拉。右栏各有一句「角色属性是什么」。
8. **背包里的属性显示（星 / 条）**：保留（f2-card）。
9. **RPG 状态栏**：只用现有那一条 theme-story-hud——危险钟、动量、幸运值、天气 · 时段接在生命值 / 背包后面，同样是 .theme-hp，放不下时这一条横向滑动，不换第二行。删掉「本局状态」半屏和所有新增点击弹层；**f6-clocks 已删除**。
10. **检定里的魔鬼交易**：保留（f6-check）。打磨：「接受交易」按钮改成和同组件「重掷」同尺寸同色。
11. **章节结算加 RPG 小结**：新屏 **f6-finish**，在现有 .finsheet 里三格读数下面再一行三格（危险钟、进展钟、动量）+ 一块「本章获得」（特质 / 状态），全用 finsheet 现有的 grid 和 game-ending 样式。
12. **F3**：目录、结算、订单、订单详情保留。票夹去掉「套票权益」行和它的弹层，恢复现状；券码、预约到店、取消预约挪进**订单详情**的「套票余额」：点还能用的章节行 → 现有 cy-option-sheet 动作菜单（标题写券码）。
13. **入场码、发票、未成年模式**：保留。打磨：去掉授权用途 / 关系后面的英文代码、政策版本号改成「2026-10-22 版」、拦截半屏不再显示错误码。
14. **F4 工作台**：去掉「今日到店」区块；改成「项目」里每张项目卡第一行「进行中」旁边的标签「今日到店 6/10」（已到 / 应到），用卡上现成的待办标签样式（没到齐 = 琥珀色「待扫码」档，到齐 = 绿色「待核销」档）。
15. **样式打磨**：逐屏检查了所有新元素，统一到所在页面的字号、颜色、间距、圆角和图标尺寸；去掉页面上原来没有的颜色（订单卡余次的绿色、入场码的加粗黑字、结算格的危险色）；玩家端和商家端可见文案里的错误码全部去掉（F4 各错误提示、锚点自检、发票关闭页等），只在右栏留给开发对照。现有元素没动。

## 文件

| 文件 | 说明 |
|---|---|
| `index.html` | 原型本体。单文件、无外部依赖，约 2.4 MB，直接双击打开 |
| `README.md` | 本说明 |
| `shots-20261023-review/` | 10-23 审阅改过的每一屏的截图（「显示改动」开，文件名前的编号对应下面 1–15 条） |

## 怎么看

- **左栏**：按功能分组的页面目录。每一项下面那行小字是它复刻的现有页面路径；标「新页」的是新建页，小字写的是它照抄的现有页面。
- **中间**：手机按 390×844 显示，带状态栏、灵动岛和微信胶囊，屏幕小时整体缩放。后台页面用 1366 宽的桌面框。
  - 页面都能点：跳转、返回、半屏弹层、确认框、toast。
- **顶部「显示改动」开关**：
  - 打开时，新加的元素带**粉色虚线框 + NEW 角标**，虚线框以外的部分都是现状；
  - 关掉以后，看到的就是改完以后的干净效果。
- **右栏**：
  - 本屏复刻的现有文件；
  - 新加了什么；
  - 页面状态切换（空、出错、各种错误码等）；
  - 演示开关，用的是后端真实开关名，关掉后显示后端真实行为；
  - 本屏接口，虚线框表示后端还没有、属于提议；
  - 待 owner 决定的问题；
  - 对照用的真机截图，取自仓库里已有的 e2e 截图，点开可放大。
- 可以用 `#页面id` 直接打开某一页，例如 `index.html#f1-guard`、`index.html#adm-guard`。

## 视觉怎么来的

- 设计 token 是把 `style/tokens.wxss` 原样换算过来的：1rpx = 0.52px，`page{}` 就是玩家深色，`.theme-light / .theme-merchant` 就是商家浅色。`lib / common / components.wxss` 也一样。
- 图标用的是 `components/cy/icon/icons.wxss` 那一套真实 cy-icon。
- 每一页的样式都是用该页自己的 wxss 和它用到的组件 wxss 转换出来的，没有新的视觉语言。
- 玩家页深色、商家页浅色，跟真机一致。
- 后台页面用 RuoYi-UI 真实的 `src/layout`（侧栏、导航栏、标签页），配色取项目的 `element-variables.scss`（主色 #7A5CFF、主按钮墨色），表格、表单、抽屉都复刻自 `src/views/business/...` 的现有页面。
- 演示数据都是虚构的；没有真实域名和密钥。

## 功能 → 复刻的现有页面 → 新增内容 → 新页面

| 功能 | 复刻的现有页面 | 只新增了什么 | 新页面（照抄谁 · 为什么） |
|---|---|---|---|
| **F1 城市定向** | `pages/topic/index/index` 主题详情；`subpackageMember/signup` 票夹；`pages/play/index` 故事流（`.chfull`、`theme-story-hud`、右上角游玩工具、节点卡 `.sheet--story`、旅程手记、章节结算 `.finsheet`）；`pages/team/detail` 队伍详情 | 主题页一行「开局安全检查」+ 半屏结果；票夹点票直接进游玩（prepare + start 静默完成，无新元素）；「…」工具菜单加「本局用时（阶段）」「当前得分」「队伍看板」三行，顶栏不加东西；长按结束那一行加一句「未走完结束 = 放弃本局」；到站被拦只在按钮下加一行「本次到达未记录 · 申诉」（原生确认框）；节点卡加一行「找不到？换个方式：扫码 · 拍照 · 答题」；队伍详情加「队伍进度」段；旅程手记加追加式「计分明细」段；结算卡加终局成绩、结算回执和「提出争议」 | **我的申诉**，照抄 `subpackageMember/report-mine`「我的举报」，入口在设置。现在没有任何地方能看到申诉 / 争议的进度 |
| **F2 角色卡与检定** | `pages/play/index` 故事流 + `theme-backpack` 背包半屏；`pages/play/components/playkit-journey-check`（现有 D20 检定）；作者端 `pages/publish/fabu/topic-detail-sheet.wxml` 主题详情「值域」卡 + valueEditor、`pages/publish/temp` 编辑玩法模板 D20 检定段 | 作者端：主题详情加「角色属性」卡 + 编辑属性弹层（名称 / 范围 / 显示为 / 跨局保留），检定段加「用哪个角色属性加值」下拉；玩家端：背包摘要卡加属性行（数字 / 条 / 星，按 displayType）；检定结果加计算明细一行、「天然 20 / 天然 1」标签（沿用优势 / 劣势标签样式）；力竭时加「原地休整」键（与现有「重掷 + 结算」并排） | 无 |
| **F6 状态与交易** | 故事流顶栏 `.fx-story__hd` / `theme-story-hud`；同一个 `playkit-journey-check`；章节完成卡 `.finsheet` | 危险钟、动量、幸运、天气 · 时段并进现有那一条状态栏（同 .theme-hp，放不下横向滑动，不加第二行，不可点）；检定题面加「魔鬼交易」块（只给标签和加值，接受 = 交易，拒绝 = 照常掷骰）；失败后结算前加「花动量补差」块；结算后显示「进度钟 +1」；章节完成卡加一行三格（两颗进度钟 + 动量）和「本章获得」 | 无 |
| **F3 自由探索套票** | `pages/topic/index` 主题页 + `session-picker` 选票半屏；`pages/activity/baoming` 结算页 + 成功弹层；`subpackageMember/order` 我的订单；`subpackageMember/orderinfo` 订单详情；`subpackageMember/signup` 票夹；`components/cy/scene-qr-ticket` 入场码 | 主题页加「套票包含」块（章节 × 门店、每章 1 次、有效期、取消规则），选票卡加一行「含 3 章 · 每章 1 次」，未开售时整块置灰；结算页费用明细加一行「套票内容」+ 半屏，付款结果未知时用现有内联错误位「查询结果」；订单卡加「套票余 2 / 3 次」；订单详情加「套票余额」段（可用章节行可点 → 现有动作菜单：券码 / 出示核销码 / 预约到店 / 取消预约，12 小时内可用豁免）、「套票退款试算」行（只读半屏）、门店退出卡（换店 / 退回这一次）；票夹不变；入场码加一行「扣套票：第 2 章 1 次」 | 无 |
| **F7 发票** | `pages/shezhi/shezhi` 设置；`subpackageMember/order` 我的订单；`subpackageMember/orderinfo` + `scene-member-order-detail` 订单详情 | 设置加「发票」一行（同一屏还有 F1 的「我的申诉」一行）；已付款且已过退款期的订单卡加「申请开票」按钮；订单详情加「发票」段（未申请 / 审核中 / 已开票） | **我的发票**，照抄我的订单；**发票详情**，照抄订单详情；**申请开票**，照抄 `baoming` 结算页（选订单用它的单选半屏）；**发票抬头**，照抄 `pages/address` 参与人列表；**新增 / 编辑抬头**，照抄 `pages/addressinfo` 参与人表单。现在小程序里没有任何发票页面 |
| **F7 未成年模式** | `pages/shezhi/shezhi` 设置；`pages/gerenziliao` 个人资料；`pages/activity/baoming` 结算页 | 设置加「未成年模式」一行 → 半屏（照抄设置里「帖子处置与申诉」的半屏），里面是年龄段、规则摘要、授权列表，撤销走现有 danger-confirm；同一处加「发起监护授权」半屏，生成邀请时用 `scene-share-invite` 的分享卡；个人资料加「出生日期」一行；结算页底栏加一行拦截提示（与「商家暂停营业」同位置同样式）+「需要监护人授权」半屏 | **确认监护授权**（孩子打开分享卡后落地的页），照抄 `pages/addressinfo` 骨架 + 订单详情的信息行。现在没有「确认别人发来的邀请」这种页面 |
| **F4 商家台**（浅色） | `pages/merchant/index` 工作台；`pages/merchant/decor` 品牌中心（门店信息、合作经营两个视图）；`pages/merchant/ledger?view=settlement` 结算 | 工作台每张项目卡加「今日到店 已到 / 应到」标签（卡上现成的待办标签样式）；点「扫码」打开「核销」半屏（扫码 / 手输券码 / 拍照识别，只识别、不核销，识别后交给现有核销流程）；「更多」里加「核销复核」；门店信息加「位置已核验」一行和「到店锚点」卡 → 锚点半屏（半径 30–500 米、Wi-Fi、自检）；合作经营加「服务商品」一行；结算页加只读「对账」组（四种结算口径）、「不在四个口径里」组、「冻结中的批次」组 | **服务商品** / **商品 · 规格容量**，照抄 `decor/perks` 常备权益（列表 + 编辑表单）；**时段容量**（只读），照抄 `verify-records` 核销记录；**核销复核列表** / **复核详情 · 补充材料**，照抄 `aftercare` 退款售后列表和详情（它本来就有「补充凭证」两步面板）；**结算明细**，照抄 `ledger` 账本页。商家端现在完全看不到核销复核和结算单 |
| **后台**（RuoYi-UI） | `src/layout`（侧栏 / 导航栏 / 标签页）；`views/business/audit/index.vue` 统一审核台；`views/business/verificationReview/index.vue` 核销异常复核 | 审核台：业务类型加「到达守卫申诉」选项、行内加「认领」、证据抽屉里加守卫日志字段和说明、审核弹窗加原因码和「逐项问题」（受开关控制）、证据抽屉加「完整留痕」按钮 → 抽屉（统一留痕不另开页）；核销复核：加「商家补充材料」列 + 抽屉（接口为提议） | **成绩申诉**，照抄 `finance/refund/index.vue` 退款处理台；**对局流水**，照抄 `finance/order/detail.vue` 订单详情；**用户发票**，照抄 `finance/refund/index.vue`。后台没有城市定向计分页面，也没有用户发票页面和接口 |

## 逐屏清单

`#id` 可直接打开该屏。复刻 / 照抄列是主文件；每屏完整的文件列表、接口和状态在原型右栏。


### F1 · 城市定向

| 页面 id | 页面 | 复刻的现有页面（新页 = 照抄） | 新增 | 接口 |
|---|---|---|---|---|
| `f1-topic` | 主题详情 · 开局安全检查 | `pages/topic/index/index.wxml` | 详情 tab 里多一条「开局安全检查 · 可以参加」行（与上面「我的游玩记录」完全同一行形），点开是 cy-sheet 明细；被拦时底栏上方出现本页已有的 bm-closed 告警条，文案取后端 blockers[0].message | `POST /api/orient/safety/check` |
| `f1-ticket` | 票夹 · 点票直接进游玩（无新元素） | `subpackageMember/signup/index.wxml` | 无新元素。点票卡 = 进游玩页；prepare + start 在后台静默完成，规则版本自动确认（owner #2 去掉了「开局前确认」半屏） | `POST /api/play/orient/session/prepare`<br>`POST /api/play/orient/session/start`<br>`GET /api/play/orient/session/receipt` |
| `f1-play` | 游玩中 · 本局用时 / 得分（在「…」菜单里） | `pages/play/index.wxml` | 顶栏 HUD 槽不加东西（只放现有生命值 / 背包）；右上「…」游玩工具菜单紧跟「本局读数」多两行：本局用时（副标题 = 阶段）、当前得分（点开 = 计分明细），再加「队伍看板」（看板开关开 + 组队局才出）；「暂停这一局 / 长按结束」那一行多一句「未走完结束 = 放弃本局」 | `GET /api/play/orient/session`<br>`GET /api/orient/scoring/session/{sessionId}`<br>`POST /api/play/orient/session/abandon` |
| `f1-guard` | 到站被拦 · 申诉 | `pages/play/index.wxml` | 被位置守卫拦下且 appealable=true 时，「我已到达此站」下多一行「本次到达未记录 · 申诉」（sheet-note 行形）；点「申诉」= 微信原生确认框一句话，确认后那一行变成「已申诉 · 查看进度」；不给玩家看编号和错误码 | `POST /api/play/arrive`<br>`⚠️提议 POST （audit_appeal 提交入口）` |
| `f1-task` | 站点任务 · 替代完成 | `pages/play/index.wxml` | 「开始任务」下一行「找不到？换个方式：扫码 · 拍照 · 答题」（只列后台为该站开放的方式）；拍照后通关行写「照片复核中」；替代完成后一句「扫码完成 / 答题完成」 | `GET /api/orient/task/state`<br>`POST /api/orient/task/alternative/submit`<br>`POST /api/play/orient/session/task/{taskId}/result` |
| `f1-team` | 队伍详情 · 队伍看板 | `pages/team/detail/index.wxml` | 在「队伍成员」上方加一段「队伍进度」：全队完成站数、本场排名（本页现成的 team-meta 两格）；下面每站一行（team-card / team-member 行形）：几人完成、达标线、是否达标 | `GET /api/orient/team/detail`<br>`GET /api/orient/team/ranking` |
| `f1-score` | 旅程手记 · 计分流水 | `pages/play/index.wxml` | 旅程手记半屏里，在站点行后面追加「计分明细 · 合计 N 分」一段：每一笔一行（时间 / 来源 / 累计 / 分值），只追加不修改；冲正（申诉通过）以新的一行出现，不改原来那行；点某一笔 → 计分申诉半屏（游玩页原型半屏） | `GET /api/orient/scoring/session/{sessionId}`<br>`POST /api/orient/scoring/session/{id}/sync`<br>`POST /api/orient/scoring/appeal` |
| `f1-result` | 章节结算 · 终局成绩 | `pages/play/index.wxml` | 结算半屏三格读数下面，用本页现有的「本局结局」块（finsheet__game-ending）写终局成绩与结算回执；下面一行「对成绩有异议？… 提出争议」，沿用本页 finsheet__retry 的行形；点开是争议半屏；放弃（abandon）的局：结果签写「已放弃」，成绩块写不再计分，不给争议入口 | `POST /api/orient/scoring/session/{id}/settle`<br>`GET /api/orient/scoring/session/{sessionId}`<br>`POST /api/orient/scoring/dispute`<br>`POST /api/play/orient/session/abandon` |
| `f1-appeals` | 我的申诉（新页） | **新页**，照抄 `chengyinhub-xcx/subpackageMember/report-mine/index.wxml` | 整页新增：导航、标题、三个 chip（全部 / 处理中 / 已出结论）、记录行（标题 + 状态签 + 时间 + 进度一句）全部照「我的举报」；常驻入口在设置「帖子处置与申诉」下面 | `GET /api/orient/scoring/session/{sessionId}`<br>`GET /api/orient/scoring/receipt`<br>`⚠️提议 GET （我的申诉列表：跨局 + 到达申诉）` |

### F2 · 角色卡与检定

| 页面 id | 页面 | 复刻的现有页面（新页 = 照抄） | 新增 | 接口 |
|---|---|---|---|---|
| `f2-card` | 角色卡（背包里的属性） | `pages/play/index.wxml` | 背包摘要里的属性行改读 GET /api/play/character：displayType=stars 画星、bar 画进度条、number 保持数字；persistScope=TOPIC 的属性多一行小字「跨局保留」 | `GET /api/play/character` |
| `f2-check` | 属性检定（服务端掷骰） | `pages/play/components/playkit-journey-check/index.wxml` | 结果屏多一行掷骰明细：骰面 + 属性 + 条件修正 = 达成值（读回执 breakdown）；天然 20 / 天然 1 小标签（回执 nat=CRIT/FUMBLE，用题面已有的优势/劣势标签样式）；力竭时结算屏多一颗「原地休整」（POST /api/play/check/rest），布局同结果屏的「重掷 + 结算」 | `POST /api/play/check/roll`<br>`POST /api/play/check/reroll`<br>`POST /api/play/check/settle`<br>`POST /api/play/check/rest` |
| `f2-attr` | 主题详情 · 角色属性（作者配置） | `pages/publish/fabu/topic-detail-sheet.wxml` + `fabu/index.wxml` valueEditor | 「值域」卡下面一张「角色属性」卡：每个属性一行（名称 / 范围 · 显示方式 · 跨局保留）+「编辑」，卡头「添加」；编辑弹层照抄同页「编辑数值」：名称、下限 / 上限、在角色卡上显示为（数字 / 条 / 星）、跨局保留开关、哪些检定用它（只读）、删除属性 | `GET /api/play/character`<br>`⚠️提议 （主题保存 · journeyRules.attributes[]）` |
| `f2-checkcfg` | 玩法模板 · D20 检定绑定属性（作者配置） | `pages/publish/temp/index.wxml`（R14 检定段） | 「判定什么技能」下面多「用哪个角色属性加值」下拉（选项 = 主题角色属性 + 不加属性）+ 一行提示；主题没配属性时下拉置灰并提示先去主题里添加 | `⚠️提议 （模板保存 · advanced.check.statKey）`<br>`POST /api/play/check/roll` |

### F6 · 状态与交易

| 页面 id | 页面 | 复刻的现有页面（新页 = 照抄） | 新增 | 接口 |
|---|---|---|---|---|
| `f6-hud` | 玩法页状态栏（并入现有一条） | `pages/play/index.wxml` | 危险进度钟、动量、幸运值、天气 · 时段直接接在现有 theme-story-hud 的生命值 / 背包后面，每项一颗 .theme-hp；放不下时这一条横向滑动（右端淡出），不加第二行；都不可点，没有弹层 | `GET /api/play/rpg/status` |
| `f6-check` | 检定 + 魔鬼交易 + 动量 | `pages/play/components/playkit-journey-check/index.wxml + index.wxss` | 题面多一块「交易」：只给 label 与加值，写「代价会在结算时揭晓」，按钮「接受交易」；不接受就直接点原来的「掷骰」；没过线时多一块「动量」：差几点、现有多少，够就给「花 N 点动量补足」；差距 > 单次上限 / 大失败 / 动量不足时只给原因；结算屏生命 / 幸运旁多「动量」读数；接受过交易时多一行进度钟 +1（交易的代价） | `GET /api/play/nodes`<br>`POST /api/play/check/bargain`<br>`POST /api/play/check/roll`<br>`POST /api/play/check/momentum`<br>`POST /api/play/check/settle`<br>`GET /api/play/rpg/status` |
| `f6-finish` | 章节结算 · 本章状态小结 | `pages/play/index.wxml`（.finsheet） | 三格读数下面再一行三格（危险钟、进展钟、动量，同 finsheet__grid / .fst）+「本章获得」块（同「这一章」那块 finsheet__game-ending，列本章新得到的特质 / 状态） | `GET /api/play/rpg/status` |

### F3 · 自由探索套票

| 页面 id | 页面 | 复刻的现有页面（新页 = 照抄） | 新增 | 接口 |
|---|---|---|---|---|
| `f3-topic` | 主题页 · 套票目录 | `pages/topic/index/index.wxml` | 探店日「场次」下面加一块「套票包含」：3 章 × 可选门店、每章 1 次、有效期、预约取消规则（沿用同页场次卡样式）；选场次弹层的票种卡里加一行「含 3 章 · 每章 1 次」；未开售（saleEnabled=false）时：目录置灰、票种卡显示「暂未开售」、底部按钮换成「套票 · 暂未开售」 | `GET /api/free-explore/bundle/catalog` |
| `f3-checkout` | 结算 · 套票内容 | `pages/activity/baoming/baoming.wxml` | 费用明细里加一行「套票内容 3 章 · 每章 1 次 >」，点开是套票内容与退改规则弹层；下单结果未知时，在现有提交错误位置提示「付款结果还没确认」，用同一请求号查回结果 | `POST /api/registration`<br>`GET /api/free-explore/bundle/order/receipt` |
| `f3-orders` | 我的订单 | `subpackageMember/order/order.wxml` | 自由探索套票订单卡多一行「套票余 2 / 3 次」 | `GET /api/free-explore/bundle/order` |
| `f3-order` | 订单详情 · 余额 / 券码预约 / 退款 / 退出 | `subpackageMember/orderinfo/orderinfo.wxml` | 支付明细下加「套票余额」：每章已用 / 可用 / 已预约 + 有效期，还能用的章节行右侧带本页现有的 link-chevron，点开 = 现有 cy-option-sheet 动作菜单（标题写这一章的券码；出示核销码 / 预约到店 / 取消预约 / 复制券码）；预约弹层选门店 / 日期 / 时段；不足 12 小时取消时同一菜单二选一：用本月豁免（WAIVED）或直接取消（LATE_CANCELLED）；「退款与客服」卡里加「套票退款试算 >」只读弹层；门店退出卡（换店 / 退回这一次） | `GET /api/free-explore/bundle/order`<br>`GET /api/free-explore/bundle/order/refund-quote`<br>`POST /api/free-explore/bundle/exit-decision`<br>`POST /api/free-explore/bundle/reservation`<br>`GET /api/free-explore/bundle/reservation/receipt`<br>`POST /api/free-explore/bundle/reservation/{reservationId}/cancel` |
| `f3-wallet` | 票夹（无新元素） | `subpackageMember/signup/index.wxml` | 无新元素：去掉了「套票权益」行和弹层（owner #12），票夹与现状一致 | — |
| `f3-qr` | 入场码 · 扣哪一次 | `pages/play/index.wxml` | 票面说明下面加一行（同一说明字样）：「本次核销扣套票第 2 章 1 次」 | `POST /scan_qr_code_chapter（现有，商家扫）` |

### F7 · 发票

| 页面 id | 页面 | 复刻的现有页面（新页 = 照抄） | 新增 | 接口 |
|---|---|---|---|---|
| `f7-settings` | 设置 · 发票 / 未成年 / 我的申诉入口 | `pages/shezhi/shezhi.wxml` | 「发票」一行（cy-cell，图标+标题+说明+箭头，与「帖子处置与申诉」同款），点进「我的发票」；「我的申诉」一行（F1，紧跟「帖子处置与申诉」，同款双行），点进「我的申诉」；「未成年模式」一行，点开设置页内的半屏（与隐私、营销同样的半屏范式） | — |
| `f7-orders` | 我的订单 · 申请开票 | `subpackageMember/order/order.wxml` | 已支付、不可再退款的订单卡底部多一颗次级键「申请开票」（沿用 .orderbut1 样式），点进申请页 | `POST /api/registration/list`<br>`POST /api/invoice/orders/eligible` |
| `f7-order-detail` | 订单详情 · 发票 | `subpackageMember/orderinfo/orderinfo.wxml` | 「支付信息」之后新增一段「发票」（段标题 + .ocard 两行：开票状态 / 申请开票 或 查看申请） | `POST /api/registration/info`<br>`POST /api/invoice/list` |
| `f7-inv` | 我的发票 | **新页**，照抄 `chengyinhub-xcx/subpackageMember/order/order.wxml` | 申请卡：申请号 / 状态 chip（审核中·已开票·已驳回·已撤回）/ 抬头类型标签 + 税号掩码 / 抬头名 / 申请时间 / 合并笔数 / 开票金额；底栏两颗：发票抬头（次）+ 申请开票（主） | `POST /api/invoice/list` |
| `f7-inv-detail` | 发票详情 | **新页**，照抄 `chengyinhub-xcx/subpackageMember/components/cy/scene-member-order-detail/index.wxml` | 撤回申请（只对审核中）：走 cy-danger-confirm 三段式；后台已处理时返回 INVOICE_STATE_CONFLICT「该申请已处理，不能撤回」，留在原地 | `POST /api/invoice/detail`<br>`POST /api/invoice/cancel` |
| `f7-apply` | 申请开票 | **新页**，照抄 `chengyinhub-xcx/pages/activity/baoming/baoming.wxml` | 发票抬头段（点开「选择发票抬头」半屏，底部「管理发票抬头」）；选择订单：可多选，右上「全选」；一次最多 50 笔（多于 50 笔时全选只取前 50 并提示）；已选笔数 + 开票金额（沿用总计行样式）；提交失败用页内 cy-inline-error（与结算页 submitError 同位） | `POST /api/invoice/orders/eligible`<br>`POST /api/invoice/titles`<br>`POST /api/invoice/apply` |
| `f7-titles` | 发票抬头 | **新页**，照抄 `chengyinhub-xcx/pages/address/address.wxml` | 每行：抬头名 + 类型、税号掩码、默认标记；删除走 cy-danger-confirm | `POST /api/invoice/titles`<br>`POST /api/invoice/titles/delete` |
| `f7-title-edit` | 新增 / 编辑发票抬头 | **新页**，照抄 `chengyinhub-xcx/pages/addressinfo/addressinfo.wxml` | 抬头类型（个人 / 单位）；抬头名称；税号（仅单位；个人抬头不收税号）；设为默认 | `POST /api/invoice/titles/save` |

### F7 · 未成年模式

| 页面 id | 页面 | 复刻的现有页面（新页 = 照抄） | 新增 | 接口 |
|---|---|---|---|---|
| `mn-home` | 未成年模式 | `pages/shezhi/shezhi.wxml` | 设置页里的「未成年模式」半屏：说明、我的年龄段、规则摘要、我的监护授权列表（撤回走 cy-danger-confirm）；成年人多一颗「发起监护授权」 | `POST /api/minor/status`<br>`POST /api/minor/policy`<br>`POST /api/minor/guardian/list`<br>`POST /api/minor/guardian/revoke` |
| `mn-invite` | 发起监护授权（监护人） | `pages/shezhi/shezhi.wxml` | 设置页里的「发起监护授权」半屏：授权用途 / 关系 / 有效期三组单选（单选行照抄结算页「选择参与人信息」半屏）；生成后同一半屏换成分享卡（照抄 cy-scene-share-invite：大框 + 标题 + 说明 + 描边分享键），用一次性邀请码代替二维码 | `POST /api/minor/guardian/invite` |
| `mn-accept` | 确认监护授权（孩子） | **新页**，照抄 `chengyinhub-xcx/pages/addressinfo/addressinfo.wxml` | 邀请人、关系、用途、有效期、证明方式（双方账号互相确认）、政策版本；底部：暂不确认（次）/ 确认授权（主） | `POST /api/minor/guardian/accept` |
| `mn-profile` | 个人资料 · 出生日期 | `pages/gerenziliao/gerenziliao.wxml` | 「出生日期」一行（与昵称/城市签名同款：名称 + 说明 + 值 + 编辑笔）；点开「出生日期」半屏（与昵称/城市签名同款 cy-sheet + 底部「完成」），日期滚轮代表微信原生 picker | `POST /api/user/info`<br>`⚠️提议 POST /api/user/update` |
| `mn-gate` | 结算 · 未成年支付拦截 | `pages/activity/baoming/baoming.wxml` | 底部栏一条提示（与「商家暂停营业」.bm-closed 同位同款）：进页时调 /api/minor/status 预判；点付款被后端 403 拦下时，弹「需要监护人授权」半屏说明原因与下一步（取代现在只有一句「重新提交」的页内错误） | `POST /api/registration/create`<br>`POST /api/minor/status` |

### F4 · 商家台

| 页面 id | 页面 | 复刻的现有页面（新页 = 照抄） | 新增 | 接口 |
|---|---|---|---|---|
| `f4-home` | 工作台 · 项目卡到店标签 / 核销台 | `pages/merchant/index/index.wxml` | 不加区块：「项目」里每张卡第一行「进行中」旁边多一个「今日到店 6/10」标签（已到 / 应到，卡上现成的待办标签样式：没到齐琥珀、到齐绿色；只到项目 / 章节粒度，不到人）；点「扫码」先弹「核销」半屏：扫码 / 手输券码 / 拍照识别，识别出码的类型后再走原来的核销接口（识别本身不核销）；旧版 VER 票码要商家选「活动票 / 主题票」；据点码给出原来的「核销入口不对」弹窗；据点张贴码直接拒绝；拍照识别最多列 5 个候选码（打码显示）；「更多」半屏里加一行「核销复核」 | `POST /api/merchant/desk/arrivals/today`<br>`POST /api/merchant/desk/redeem/resolve`<br>`POST /api/merchant/desk/redeem/ocr-candidates`<br>`POST /api/merchant/desk/stores`<br>`POST /api/coupon/verification 等现有核销端点` |
| `f4-brand` | 品牌中心（入口） | `pages/merchant/decor/index.wxml` | （无，导航路径） | `POST /api/merchant/info` |
| `f4-store` | 门店信息 · 到店锚点 | `pages/merchant/decor/index.wxml` | 「店铺地址」下加一行小字「位置已核验」（来自 /stores 的 locationVerified）；新加一张卡「到店锚点」，点开是全屏面板：地图点、判定半径 30–500 米、店内 Wi-Fi（最多 8 个，只存摘要）、到店自检（IN_RANGE / OUT_OF_RANGE、WIFI_MATCH / WIFI_MISMATCH）；保存失败给页内错误：离门店超过 1 公里、被别人改过（版本冲突） | `POST /api/merchant/desk/stores`<br>`POST /api/merchant/desk/anchor`<br>`POST /api/merchant/desk/anchor/save`<br>`POST /api/merchant/desk/anchor/check` |
| `f4-coop` | 合作经营 · 服务商品入口 | `pages/merchant/decor/index.wxml` | 「常备权益」下面加一行「服务商品」，显示个数和待审核数 | `POST /api/merchant/desk/products` |
| `f4-products` | 服务商品（新页） | **新页**，照抄 `chengyinhub-xcx/pages/merchant/decor/perks/index.wxml` | 商品卡：名称 + 状态（待审核 / 已上架 / 已下架）+ 价格 / 库存 / 规格数；已上架的卡右侧「下架」（危险确认）；底部「添加服务商品」，新建或修改后回到待审核 | `POST /api/merchant/desk/products`<br>`POST /api/merchant/desk/products/save`<br>`POST /api/merchant/desk/products/delist` |
| `f4-product` | 商品 · 规格容量（新页） | **新页**，照抄 `chengyinhub-xcx/pages/merchant/decor/perks/index.wxml` | 商品头卡 + 规格列表（可预约、每档时长、每档容量）；上架中整页锁定（F4_PRODUCT_LIVE_LOCKED），底部按钮是「下架商品」；待审核 / 已下架时点规格弹「编辑规格」面板：时长 15 的倍数（15–480）、容量 0–1000，保存后回到待审核；规格右侧「时段」进只读时段页 | `POST /api/merchant/desk/products/detail`<br>`POST /api/merchant/desk/products/sku/save`<br>`POST /api/merchant/desk/products/delist` |
| `f4-slots` | 时段容量（新页·只读） | **新页**，照抄 `chengyinhub-xcx/pages/merchant/verify-records/index.wxml` | 按日期分组的时段行：时间段、已约 / 总容量、状态；近 3 / 7 / 14 天切换（最多 14 天）；空态：还没有生成时段 | `POST /api/merchant/desk/products/sku/slots` |
| `f4-verdicts` | 核销复核列表（新页） | **新页**，照抄 `chengyinhub-xcx/pages/merchant/aftercare/index.wxml` | 三段：待复核 / 确认正常 / 确认异常（status 0/1/2）；每行：主题名、发起时间、结论、核销率 / 1 分钟内笔数 / 已补充次数 | `POST /api/merchant/desk/verdicts` |
| `f4-verdict` | 复核详情 · 补充材料（新页） | **新页**，照抄 `chengyinhub-xcx/pages/merchant/aftercare/detail/index.wxml` | 键值卡：结论、触发原因、核销率、1 分钟内笔数、复核单号；「已补充材料」列表（x / 10）；「补充材料」两步面板：说明 ≤500 字 + 图片（单次 ≤6 张）→ 确认 → 提交；已结束的单只读（F4_REVIEW_CLOSED）；满 10 次给提示（F4_EVIDENCE_LIMIT） | `POST /api/merchant/desk/verdicts/detail`<br>`POST /api/merchant/desk/verdicts/evidence`<br>`POST /api/merchant/desk/receipt` |
| `f4-recon` | 结算 · 对账 / 冻结（只读） | `pages/merchant/ledger/index.wxml` | 「结算到账提醒」下面加「对账（只读）」：四个口径（章节承接 / 合作订单 / 报名分账 / 不结现金）的结算单数和状态，点进结算明细；「不在四个口径里」：俱乐部直收（CLUB_DIRECT）单列；章节承接里没挂结算单的应付行和金额单列；「对公结算」下面加「冻结中的批次」：金额、运营填的冻结原因、冻结影响 | `POST /api/merchant/desk/reconciliation`<br>`POST /api/merchant/desk/frozen` |
| `f4-claims` | 结算明细（新页·只读） | **新页**，照抄 `chengyinhub-xcx/pages/merchant/ledger/index.wxml` | 按口径切换（四个口径 + 俱乐部直收）；每行：核销 / 订单号、时间、状态、服务单位；只读，分页每页 ≤50 | `POST /api/merchant/desk/reconciliation/claims` |

### 后台

| 页面 id | 页面 | 复刻的现有页面（新页 = 照抄） | 新增 | 接口 |
|---|---|---|---|---|
| `adm-appeals` | 成绩申诉 | **新页**，照抄 `chengyinhub-ui/src/views/business/finance/refund/index.vue` | 「审核与风控」菜单下新增「成绩申诉」菜单项与页签；整页为新页面：单条申诉 / 成绩争议两类队列（申诉类型筛选切换）；处理弹窗：通过时可填调整分（adjustCenti），驳回只写说明；详情抽屉：申诉理由、处理说明、调整流水编号，「查看对局流水」进入对局流水页 | `GET /business/orient/scoring/appeals`<br>`GET /business/orient/scoring/disputes`<br>`POST /business/orient/scoring/appeals/{id}/resolve`<br>`POST /business/orient/scoring/disputes/{id}/resolve` |
| `adm-session` | 对局流水 | **新页**，照抄 `chengyinhub-ui/src/views/business/finance/order/detail.vue` | 整页为新页面：流水合计 / 结算投影 / 结算后变动三张汇总卡；对局事实、分项合计、一致性核对（verify 接口）三个事实网格；计分流水表：每行可「冲正」，冲正只追加反向条目，并互相标注 | `GET /business/orient/scoring/session/{id}`<br>`GET /business/orient/scoring/session/{id}/verify`<br>`POST /business/orient/scoring/entries/{id}/reverse` |
| `adm-guard` | 到达申诉 | `ui/src/views/business/audit/index.vue` | 业务类型下拉新增「到达守卫申诉」（bizType = play_arrive_guard），已选条件里可见；处理列新增「认领」按钮（接 /business/audit/{id}/claim），认领后显示「已认领」；证据链抽屉：到达申诉说明条；内容快照里新增「到达守卫记录」字段（判定、原因码、站点、精度、速度/位移、判定时间、申诉陈述）；审核处理弹窗：新增「原因码」下拉（reasonCode）；开启逐项问题开关后，驳回时出现「逐项问题」区（到达申诉不支持，会如实提示）；抽屉头部新增「完整留痕」按钮（O5c 开关） | `GET /business/audit/list`<br>`POST /business/audit/{id}/claim`<br>`GET /business/audit/{id}/detail`<br>`POST /business/audit/review`<br>`⚠️提议 GET （到达守卫记录读取：play_arrive_guard_log 进审核快照）` |
| `adm-verify` | 核销异常复核 | `ui/src/views/business/verificationReview/index.vue` | 表格新增「商家补充材料」列（N 份 · 查看）；新增「商家补充材料」抽屉：逐份显示提交人、时间、文字说明与图片（复用核销证据页的证据面板样式） | `GET /business/verificationReview/list`<br>`POST /business/verificationReview/handle`<br>`⚠️提议 GET （商家补充材料：/business/verificationReview/{id}/evidence）` |
| `adm-invoice` | 用户发票 | **新页**，照抄 `chengyinhub-ui/src/views/business/finance/refund/index.vue` | 「资金与交易」菜单下新增「用户发票」菜单项与页签；整页为新页面：开票队列、开具（回填发票号）与驳回（写原因）弹窗、申请详情抽屉（订单明细） | `⚠️提议 GET （/business/userInvoice/list）`<br>`⚠️提议 POST （/business/userInvoice/{id}/issue）`<br>`⚠️提议 POST （/business/userInvoice/{id}/reject）` |
| `adm-trail` | 统一留痕 | `ui/src/views/business/audit/index.vue` | 审核证据链抽屉头部新增「完整留痕」按钮；新增「完整留痕」抽屉：合并 5 个来源按时间排序，每条显示动作、原因码、操作方与来源（复用页面现有的处置轨迹时间线样式） | `GET /business/audit-trail` |

## 复刻时发现的与旧截图不一致处（按现行代码画）

部分仓库截图比现行 wxml 旧。遇到不一致时，一律按**现在的代码**画，并在该屏的右栏写明原因：

- **玩法页没有底部「核销 / 暂停 / 扫码」栏了。**这条栏 2026-09-22 起已去掉，城市定向在全屏故事流（`.chfull`）里玩，开始、暂停、长按结束都在右上角「…」游玩工具里。截图 044–053 还是旧版。
- **主题页不直接进入游玩。**已购票的玩家在主题页看到的是「去票夹 · 查看我的票」（H3=B），从票夹进入游玩。主题页只加安全检查；票夹点票后 prepare + start 静默完成（10-23 起不再有「开局前确认」）。
- **商家工作台底栏现在是透明到白的渐变，不是白色实底**（2026-09-19 改）。另外，工作台写的是「累计收入」，「更多」半屏没有箭头。
- 设置、个人资料、订单详情的几张旧截图也与现行 wxml 有出入，都按现行 wxml 画。

## 没有真机截图可对照的页面（只按 wxml / wxss 复刻）

- **小程序**：
  - 检定（`playkit-journey-check`）；
  - 背包半屏；
  - 主题详情弹窗的现行版本（仓里截图是 10-03 版，右上还是 ×）、「编辑数值」弹层、编辑玩法模板的 D20 检定段；
  - 游玩工具菜单的现行版本；
  - 队伍详情；
  - 入场码票面；
  - 订单详情场景组件的有数据状态；
  - 参与人列表 / 表单；
  - 商家结算页的有数据状态；
  - 售后列表 / 详情。
- **后台**：仓库里没有任何截图，只按源码复刻。容器里没有苹方字体，所以截图里的个别日期单元格会折行。

## 现有代码里照原样画出的小问题（没有顺手改）

- 深色游玩工具菜单里「扫码到达」的图标描边接近黑色，真机上也看不清。
- 后台：
  - 侧栏 logo 是白色图片放在白色侧栏上，看不见；
  - 「审核与风控」「商家」两个菜单没有图标；
  - 核销复核页没有给 `.cy-panel` 写样式；
  - 核销复核表格比页面宽，需要横向滚动。

## 与 v1 清单的出入（以后端为准）

- **补充材料**：后端（`MerchantDeskServiceImpl`）允许每单补充 10 次、每次最多 6 张图。
- **到店锚点**：最多 8 个 Wi-Fi。
- **交易代价揭晓时机**：魔鬼交易的代价在玩家「接受」时就已生效，结算回执里没有代价字段。所以「代价会在结算时揭晓」只能是前台文案，见 #23。
- **枚举值**：天气 / 时段后端下发的是代码，需要前台映射；属性的 displayType 取值是 `stars`。
- **逐项问题**：后端拒绝在到达申诉上附逐项问题（problems[]），这个只支持内容类驳回。审核弹窗对到达申诉行会写明。
- **生日字段**：`/api/user/update` 目前没有生日字段，个人资料里的「出生日期」接口标为提议，见 #37。
- **定向拦截**：`enforceOrienteeringIfEnabled` 没有调用方（见 #38），只在规则摘要里提到。

## 待 owner 决定的问题

编号沿用上一版；10-23 审阅已经答掉的两条已删：#1（PREPARED 状态要不要拒绝推进——票夹改成 prepare + start 一步完成，不再停在 PREPARED）、#16（displayType 谁选——作者在主题「角色属性」里选）。

### F1 · 城市定向

2. **F1a**：没有「完成本局」接口：FINISHED 由现有推进流程走到最后一站产生。最后一站完成后是否自动结算，还是让玩家点「结算成绩」？原型按自动结算画。
3. **F1a**：ORIENT_TEAM_UNSUPPORTED：会话目前不支持组队局。队伍看板（F1d）与会话（F1a）如何合并？
4. **F1b**：存量 product_type=1 主题是否随全局开关一起开始计分？
5. **F1b**：分数单位：后端以 centi（1/100 分）记账，前台显示整数分还是一位小数？原型显示整数，冲正后可能出现小数。
6. **F1b**：后台 business:orientScoring:* 权限给哪些角色？现在只有超管。
7. **F1c**：require_accuracy 要等最低小程序版本定下来才能开；版本号是多少？
8. **F1c**：到达被拦后的申诉走哪个入口（audit_appeal 已有表，但没有新控制器）？
9. **F1d**：开 safety 后没有未成年监护数据源会直接拦未成年人（与 F7 监护授权如何打通）。
10. **F1d**：照片替代任务进入人工复核（PENDING_REVIEW）期间，计时是否暂停、能否继续下一站？

### F2 · RPG P0 · 角色卡与检定

11. **F2**：属性范围的唯一真源：theme_state_key.min/max 与 journeyRules.attributes[].min/max 现在取交集，以哪个为准？（原型里作者在「角色属性」里填的范围按 journeyRules.attributes[] 画。）
12. **C4-1**：技能绑定属性的命名空间：counter.* 还是 stat.*？原型里的「跨局保留」开关需要属性能存 theme_character（跨局），目前只实现了 run——要不要做？检定绑定字段（advanced.check.statKey）也需要后端新增。
13. **C4-2**：情境修正 modCap 只有上限没有下限，是否改成 [-cap, cap]？
14. **C4-3**：两个掷骰入口（roll 与 rollAttempt）是否合并？
15. **F2**：结果只有成功 / 失败 + 天然 20/1，没有「部分成功」档；文案是否需要第三档？

### F6 · RPG P1 · 状态与交易

17. **C-F6-1**：进度钟段数有两个来源（clock 配置 segments 与变量定义），以哪个为准？
18. **C-F6-2**：ENV_IN（天气 / 时段条件）用在路线边条件上会被发布闸拒绝（fail-closed）。要不要放开？
19. **C-F6-3**：交易 / 动量没有接到 attempt 路径（只在 check 路径），玩法屏里的检定要不要也支持？
20. **C-F6-4**：动量规则是简化版（失败 +1、补差最多 3）。是否需要更完整的规则？
21. **C-F6-5**：天气城市来源用的是 address_name，应在 O-14 之后改为 city_code；地址外发（N533-5）也属这一项。
22. **F6**：没有本局规则上下文的投影（模板库、商家 / 报名视角、info-to-user）一律不下发 check.bargain，预览里看不到交易，是否接受？
23. **F6**：交易代价对玩家完全隐藏（只给 label），还是给一句模糊提示？原型写「代价会在结算时揭晓」。

### F3 · 自由探索套票

24. **K1**：每章只给 1 张券（UNITS_PER_CHAPTER=1）。要不要一章多张？需要改核销唯一键。
25. **K2**：部分退款退到「平台余额」还没有资金路由，所以现在只能试算、不能提交。
26. **K3**：最关键：罚没（FORFEITED）不会置位 verification_status，整单原路全退可以绕过罚没。K3 解决前不要开 reservation-enabled。
27. **K4**：单券价值怎么算：现在是实付平均分、尾差给最后一张；还是按章节加权？
28. **K5**：有效期来源：现在取活动 end_date（没有就拒绝冻结）；还是单独配置？
29. **K6**：商家退出后由持券人发起改换 / 退款；还是在商家退出流程里自动开单？
30. **K7**：settlement_claim 的按券维度推迟到 Phase 2，需要第二次资金审批。
31. **K8**：过期不会自动改状态，只在读的时候算 expired；要不要加过期任务？
32. **F3**：开售关闭时购票不报错、只是不建台账（没有 BUNDLE_SALE_OFF 码）。前台是否需要明确的「暂未开售」拦截？原型按目录页 saleEnabled=false 直接置灰。

### F7 · 发票与未成年模式

33. **F7**：开 minorMode 之前必须改用户协议第十条（现在写「暂不面向未成年人…未满十八周岁请勿注册」），release-legal-gate.js 也要同步改。
34. **C7-1**：未成年策略：A 维持「不面向未成年」+ 注册时 18 岁确认；B 按本原型开放未成年模式。
35. **C7-2**：是否接电子发票服务？现在 provider=MANUAL：后台手工开、只回填发票号，没有可下载的文件。
36. **F7**：后台没有处理用户发票的接口（markIssued / markRejected 存在但没有控制器调用），后台页面是提议。
37. **F7**：年龄段来自出生日期：个人资料里还没有出生日期填写入口（MINOR_AGE_UNKNOWN 会让用户去填）。入口放哪？
38. **F7**：定向拦截 enforceOrienteeringIfEnabled 没有调用方；F1d 的 safety/check 是否改为调用它？
39. **F7**：监护关系只用「双方账号互相确认」（MUTUAL_ACCOUNT_CONFIRM）作证据，是否足够？

### F4 · 商家台

40. **C-F4-1**：一个商家多门店？现在只返回 1 个门店；方案 B 是新建 mms_store 表。
41. **C-F4-2**：服务商品复用积分商城表 pms_product，还是另建服务商品表？
42. **C-F4-3**：到店统计只到章节粒度，等预约模型（C5-1）定了再细化。
43. **C-F4-4**：CLUB_DIRECT（俱乐部直收）要不要成为第五种结算基础？
44. **C-F4-5**：没有 claim 的应付行怎么展示 / 处理？
45. **C-F4-6**：到店锚点现在只是配置（enforced=false）。要不要接到核销拦截（先 shadow 再 enforce）？
46. **F4**：还没做：预约占用 / 释放、时段自动生成、打款与应付计提、发票上传、CSV 导出。时段页只读、可能为空。
47. **F4**：核销台只「识别」不「核销」：识别后跳到现有核销页面（redirectPath）。是否要在同一页完成？

### 后台 · 管理后台

48. **后台**：F1 成绩申诉、用户发票、统一留痕都没有现成的 Vue 页面；本原型按 RuoYi 列表 + 弹窗画，是否放在「业务管理」菜单下？
49. **F1b**：business:orientScoring:list / review 分给哪些角色？
50. **F4**：后台看不到商家补充的复核材料（只有商家端读写 verification_review_evidence），是否新增后台读取接口？
51. **F7**：用户发票后台处理接口需要新增（markIssued / markRejected 已有服务方法）。
52. **O5c**：「完整留痕」做成审核详情里的抽屉（原型做法），还是单独页面？
53. **O5b**：审核状态的唯一真源用哪张表；审核报表读 A 还是 B；P2 开关粒度；D-7 下架原因码取值。
54. **O5a**：beta_flag 由谁设置：A 首次发布即进 Beta / B 首次人工过审进 Beta / C 退役 Beta。

## 验证

- 用 Playwright（预装 Chromium）逐一打开全部 52 屏（46 个手机屏、6 个后台屏），每屏把所有页面状态切一遍，「显示改动」开和关各截一遍：控制台 0 报错、0 警告。
- 本轮改过的每一屏都单独截图看过（f1-topic、f1-ticket、f1-play、f1-guard、f1-task、f1-team、f1-score、f1-result、f1-appeals、f2-attr、f2-checkcfg、f6-hud、f6-check、f6-finish、f3-orders、f3-order、f3-wallet、f3-qr、f7-settings、mn-home、mn-invite、mn-accept、mn-gate、f4-home、f4-store、f4-product、f4-verdict），并和仓库里同一页的真机截图对照。
- 用脚本走通了这些点击流程：
  - F1：主题页 → 票夹 → 点票直接进游玩 → 「…」菜单看用时 / 得分 → 到站被拦 → 原生确认框申诉 → 替代任务 → 计分 → 结算 → 争议；设置 → 我的申诉；
  - F2：主题详情添加 / 编辑角色属性 → 玩法模板检定绑定属性；背包看属性；
  - F2 / F6：交易 → 掷骰 → 动量 → 结算 → 休整；状态栏横向滑动；章节结算看本章状态；
  - F3：购买 → 订单详情点章节行 → 预约 → 取消（豁免 / 作废）→ 出示核销码；
  - F7：申请开票 → 抬头校验 → 撤回；邀请监护人 → 确认 → 支付拦截；
  - F4：项目卡到店标签（未到齐 / 到齐）→ 核销识别 → 锚点自检 → 复核补充材料；
  - 后台：认领 → 审核 → 完整留痕、申诉调整 → 冲正 → 一致性核对。
- 文件里没有外部资源、真实域名或密钥（`example.invalid` 仅作占位）。
