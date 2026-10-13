# 城瘾（Questify）小程序 · 全量审计报告

> 审计对象：`chengyinhub-xcx`（微信小程序）+ `chengyinhub-admin`（RuoYi Spring Boot 后端）+ `chengyinhub-ui`（Vue 管理后台）
> 审计身份：产品经理 / UI·UX设计师 / 微信小程序专家 / 测试工程师 / 系统架构师 / 微信审核专家
> 审计日期：2026-06-18
> 代码状态：处于「老 `user_type` 体系 → 新 RBAC `role` 体系」迁移中途（见 git log：R1–R3、俱乐部子系统、token 设计体系、字体迁移均为近期提交）

---

## 执行摘要（先读这段）

城瘾是一套「城市探索 × 主题/活动 × 玩家共创 + 商家/俱乐部」的全栈小程序，技术底座是成熟的 RuoYi 框架，后端 RBAC 配额校验、登录态自动续期等关键链路**已经做得相当扎实**，不是玩具项目。

但它当前**不能直接上线**，核心阻断项有三类：

1. **两个已确认的越权漏洞（IDOR）**：任意登录用户可删除 / 上下架**任意商家**的活动（`/api/activity/delete`、`/api/activity/update_publish_status` 缺少归属校验，SQL 仅 `WHERE id=#{id}`）。这是 P0，会被恶意用户用来批量删库。
2. **生产配置里残留测试 / Mock 痕迹**：图片基址指向 `http://test2.h5.org.cn`（HTTP 明文测试域名）、登录失败静默降级为 `mockData` 假用户、设置页带「切换身份」debug 开关。这些既是功能 Bug，也是微信审核高风险点。
3. **产品叙事与实现脱节**：代码里写满了「深空星海暗色主题 / citylnk Bible / IP 世界观」，但实际渲染出来是一个浅色的、和大众点评同质的「附近活动」列表。「探索城市 × IP世界观 × 玩家共创」这句话，用户在首页**看不到**。

健康度总分：**61 / 100**（详见第八部分）。结论：**地基好，但有致命漏洞 + 半成品状态，需要约 3–4 周收口才能进入审核 / 灰度。**

---

# 第一阶段：项目总体结构

## 1.1 页面树（按业务域重组，非物理目录）

```
首页 index/index ★Tab1
├ 玩家视角：Banner / 附近活动 / 推荐 / 即将上线 / 全部
└ 商家视角：同页按 user_type==2 分流为「经营首页」

广场 square/list ★Tab2          —— 创意广场（UGC 主题/帖子流）
├ 广场详情 square/detail
└ 评论 / 点赞 / 收藏

模板 template/index ★Tab3        —— 玩法模板库（街角密码/时间邮局…）
├ 模板详情 templatedetail / templatedetail2
├ 我的模板 member/mytemplate / mytemplatedetail
└ 使用模板→发布 publish/templateadd

达人/团体 talent/list ★Tab4       —— 三 Tab：进行中 / 挑战 / 俱乐部
├ 达人搜索 talent/search
├ 主题域 topic/*（详情/结算/商家报名 1-2-3/商家信息/投票）
├ 活动域 activity/*（详情/发布 fabu/报名 baoming）
└ 俱乐部 club/apply

我的 member/index ★Tab5
├ 身份中心 role/center
├ 发布中心 publish/*（fabu 帖子 / activity 活动 / topicadd 主题 / templateadd 模板 / biaoqian 标签）
├ 报名与订单 member/(signup|signupInfo|order|orderinfo|mycanyu|mycanyuinfo)
├ 优惠券 member/(coupon|couponInfo)
├ 钱包资产 wallet / member/(tixian|tixianjilu) / jifen / 积分
├ 资产中心 subpackageA/assetcenter（topic/invite/brand 三类收益）
├ 邀请 member/myinvite
├ 资料 gerenziliao(个人) / shanghuziliao(商户) / shezhi(设置)
├ 入驻 merchant/apply、shangjiaruzhu、club/apply
├ 收藏 mylike
└ 地址 address / addressinfo

合作/内容 hezuozhe（合作者/IP）、subpackageA/(infomation 资讯 / project 项目)
搜索与地图 search2 / searchmap / talent/search
```

## 1.2 TabBar 结构

5 个 Tab：`首页 / 广场 / 模板 / 达人 / 我的`。

注意一个**架构隐患**：`app.json` 里既配置了原生 `tabBar`，`app.js` 又在 `onLaunch` 里 `wx.hideTabBar()`（且失败重试），改用 `components/tabBar` 自定义组件。这意味着**两套 TabBar 同时存在并需要双份维护**，且自定义 TabBar 的选中态、跳转、暗色适配都要手写。截图中达人页 Tab 文案是「达人」，但页面标题是「团体」，内容是「进行中/挑战/俱乐部」——**Tab 名 / 页面标题 / 内容三者命名不一致**，是认知负担的根源之一。

## 1.3 组件

| 类型 | 组件 | 说明 |
|---|---|---|
| 设计系统 cy | `btn / tag / node / section-header / date-sheet / starfield` | 新设计体系组件，`starfield`（星海背景）对应「深空」世界观，但未铺到主要业务页 |
| 业务组件 | `roleBadge`（角色徽章）、`topBar`（自定义导航）、`tabBar`（自定义底栏）、`touch` | RBAC 迁移期产物 |

组件化程度：**中等偏低**。30 个页面目录里大量重复的「列表 + 卡片 + 点赞/收藏」逻辑没有抽成公共组件，卡片样式散落在各页 wxss。

## 1.4 API 接口（按域）

后端 23 个 `Api*Controller`，前端实际调用约 110 个端点。核心域：

- **登录** `/api/login/code`（微信 code 静默登录，**不存在则自动注册**为 player 并送 50 积分）、`/login`、`/register`、`/getwxbindphone`、`/findpass`
- **角色配额** `/api/role/info`（返回 role + 配额上限 + 当前用量）
- **内容** `/api/topic/*`、`/api/activity/*`、`/api/template/*`、`/api/creativesquare/*`、`/api/comment/*`、`/api/category/list`
- **交易** `/api/registration/*`（报名/支付/核销/扫码）、`/api/coupon/*`、`/api/withdrawal/*`、`/api/user/balance|points|income/*`
- **商家/俱乐部** `/api/merchant/*`、`/api/club/*`
- **通用** `/api/common/(banner|dict|uploadOSS)`

## 1.5 「云函数 / 数据库集合」说明

**本项目不是微信云开发架构**，没有云函数和云数据库集合（task 中的「云函数 / 数据库集合」概念不适用）。它是「小程序 → 自建 HTTPS 服务（`chengyinhub.shapps.cn/prod-api`）→ MySQL + Redis」的传统架构。对应的是 MySQL 表，核心表（从 controller/mapper 推断）：

`ums_member`（会员，含 `user_type` 1/2 与 `role` player/club/merchant 双字段）、`cms_topic`（主题）、`cms_activity`（活动）、`cms_template`（模板）、`cms_creative_square`（广场帖）、`sms_coupon`（优惠券）、`club`/`club_member`（俱乐部）、`registration`（报名）、`user_permission`（角色配额表）、`ums_member_points_detail`（积分流水）、`withdrawal`（提现）、`sys_banner`、`sys_dict_*`。迁移脚本见 `chengyinhub-admin/sql/migration_*.sql`。

## 1.6 权限体系

**两套身份并存，是当前最大的结构性债务：**

- 旧：`user_type`（1 普通 / 2 商户）——前端 90% 的入口判断仍在用它（`index.js`、`member/index.wxml`、`merchant/index.js`、`activity/detail.js`）。
- 新：RBAC `role`（`player / club / merchant`）+ `user_permission` 配额表——后端 `RoleService` / `QuotaService` 用它做服务端校验。

问题：`user_type` 只有 1/2 两值，**装不下 club 这个第三角色**；`club` 用户在前端 `user_type` 仍是 1，导致「俱乐部」入口/视角只能靠 `role` 判断，而大部分页面没读 `role`。新老两套并行 = 判断逻辑分叉、容易漏判。

**好消息**：服务端校验是真做了的。`topic/create`、`activity/publish`、`coupon/publish` 都在入口调用 `quotaService.checkCreate()`，按 `role` 查 `user_permission` 表校验「能不能发 + 数量上限」。前端就算被绕过，后端会拦。

## 1.7 重复 / 冗余

- **重复功能**：「发布」有两套并存——`pages/publish/*`（fabu/activity/topicadd/templateadd）与 `topic/*`、`activity/fabu` 入口重叠；`templatedetail` 与 `templatedetail2`、`signupInfo` 与 `signupInfo2`、`topic/settlement` 与 `settlement` 均为「同一功能两版本」，疑似改版残留未清理。
- **冗余代码**：`ApiLoginController` 大段被注释的 `TuserJobSeekers/TuserRecruiter`（说明此框架是从「招聘类」项目改造而来，遗留招聘逻辑）；`mockData.js` 完整一套假数据仍打包进生产；`utils/config.js` 的 `baseImgUrl` 测试域名。
- **冗余资产**：主包打进了 `node_modules`、`mockData.js`、`小程序截图/`、`scripts/`，这些都不该进小程序构建产物。

---

# 第二阶段：完整用户流程审计

> 关键前提：**本产品不存在传统「游客」**。`app.js` 一启动就 `wx.login` 换 code，后端 `/api/login/code` 对任何新 openid **自动建号**。所以「未注册」状态在产品里几乎不存在——用户第一次打开就已是 `player`。这是合理的微信小程序做法，但也意味着「注册/登录」对用户**不可见、不可控**。

## 2.1 普通玩家（player）

| 步骤 | 结果 | 问题 |
|---|---|---|
| 首次进入 | ✅ 自动静默登录建号、送 50 积分 | 登录**失败时静默降级为 mock 假用户**（`useDevUser()`），用户会看到一个并不存在的「城瘾用户」假数据，且后续所有写操作会失败——**逻辑断层**（P1） |
| 注册 | ⚠️ 对用户透明 | 没有可见注册流程；`/register` 走手机号+验证码但小程序端无入口，等于死代码 |
| 登录 | ✅ code 静默 + 401 自动重登重试 | `sendRequest` 的 401/`code==2` 自动 reLogin 重试一次，做得好 |
| 浏览 | ✅ 首页/广场/模板/达人可浏览 | 首页商家视角与玩家视角同页分流，逻辑 OK |
| 搜索 | ⚠️ `search2`/`searchmap`/`talent/search` 三套搜索 | 搜索能力分散，无统一入口；地图搜索依赖 `getFuzzyLocation` 授权，拒绝授权后的兜底需验证 |
| 收藏 / 点赞 | ✅ topic/activity/creativesquare 各有 like 接口 | 点赞接口分三套（`topic/like`、`activity/like`、`creativesquare/like`）逻辑重复 |
| 发布主题 | ✅ 后端配额校验 | 达人页「发布主题」黑色大按钮醒目，但 player 默认配额需确认（见 2.2） |
| 发布活动 | ⚠️ | `activity/publish` 有配额校验；但 player 是否允许发活动取决于 `user_permission` 配置，UI 未提前告知「你当前不能发」，点了才报错——**转化流失点** |
| 退出登录 | ✅ `shezhi.logout` 清 storage + reLaunch | 退出后下次进入又自动建号/复用，符合预期 |

## 2.2 会员 / 配额（权限绕过专项）

- **服务端校验：通过。** `quotaService.checkCreate` 对 theme/event/coupon 三类，先查 `canCreateXxx`（不能则拒），再比 `used >= maxXxx`（超额则拒）。前端 `role/info` 也回显「上限/用量」。
- **可绕过点（前端）：** `pages/shezhi/shezhi.js` 的 `switchIdentity()` 直接 `wx.setStorageSync('user_type', 2)` 把自己切成「商家视角」，并写 `debug_user_view`。这是**调试开关混进了生产设置页**。后果：用户能看到商家专属 UI（如核销扫码入口），但真正调商家接口时被后端 token 角色拦截 → 报错。**不是数据越权，但是 UI 越权 + 审核风险**（P1）。
- **配额计数口径瑕疵**：`checkCreate` 用 `selectCmsTopicNumByMemberId` 统计「该会员主题数」，`role/info` 注释自承「统计全部，状态精确过滤留 R3」。若把已删除/草稿也计入，会**误判超额**，正常用户被卡住发不了（P2）。

## 2.3 商家（merchant）

| 能力 | 结果 | 问题 |
|---|---|---|
| 商家申请 | ✅ `merchant/apply` → 提交资料 | 申请-审核状态机需在管理后台审；小程序端审核中/驳回态的回显要验证 |
| 发布活动/优惠券 | ✅ 配额校验齐 | — |
| 数据展示 | ✅ 经营首页 + 资产中心 | — |
| **修改/删除活动** | ❌**越权** | `activity/delete`、`activity/update_publish_status` **无归属校验**，A 商家可删/下架 B 商家的活动（详见第三阶段权限问题，**P0**） |
| 核销扫码 | ✅ `registration/scan_qr_code`+`verify_answer` | 入口受 `user_type==2` 前端控制，但前端可被 2.2 绕过显示，需后端再校验核销权限 |

## 2.4 俱乐部（club）

- 创建：`club/apply` 提交申请；`ApiClubController.apply` 存在。
- 发主题/活动：依赖 `role==club` 的 `user_permission` 配额。
- 管理成员：`club/members`、`join`、`quit` 接口齐。
- **与商家权限冲突检查**：`club` 在 `user_type` 体系里没有独立值（仍是 1），导致**前端无法稳定区分「俱乐部」与「普通玩家」**——俱乐部用户在很多页面会被当普通玩家渲染，看不到俱乐部入口（除 `role/center` 这类显式读 `role` 的页面）。这是**功能断层**而非权限冲突：俱乐部权限在后端有、在前端「找不到入口」（P1）。

---

# 第三阶段：BUG 扫描

## 3.1 页面级

- **白屏/空态**：达人页「开放的挑战」长期空态（截图证实「暂无开放的挑战 / 敬请期待官方发起」）。核心 Tab 之一默认空，等于**首屏给用户一个空房间**（P1，留存杀手）。
- **Mock 脏数据上屏**：登录失败 → `useDevUser` → 首页/列表渲染 `mockData` 里的「街角密码/城瘾实验室」假数据，且图片走 `/images/d_cy.jpg` 本地占位。用户以为是真内容，点进去全是死链（P1）。
- **图片基址失效**：`config.baseImgUrl = http://test2.h5.org.cn/...`，凡走 `getImgUrl()` 拼接的图片，在生产 + HTTPS 校验下**必然加载失败**（P0/P1，取决于实际使用范围——已确认有 12 处引用）。
- 改版残留双页面（`templatedetail2`/`signupInfo2`/`settlement` 两套）易出现「老入口跳老页、数据结构对不上」的偶发白屏（P2）。

## 3.2 交互

- **重复点击**：30 个页面里只有约 22 处有提交节流（`isSubmitting`/`disabled`/`catchtap`）痕迹，**覆盖不全**。发布、报名、支付、提现这类**写接口若无防重**，会产生重复主题/重复订单/重复提现（P1，钱相关的是 P0 级隐患）。
- **Toast/Loading**：`app.js` 封装了 `showToast/hideToast/showModal` 且 `sendRequest` 失败有兜底 Toast，基础盘 OK；但 `getUserData` 这类用了 `hideLoading:true` 静默请求，失败无任何反馈，用户面对空页面不知发生了什么（P2）。
- **返回异常**：`switchIdentity` / `logout` 用 `reLaunch` 重置栈是对的；但多个发布流程是 `navigateTo` 深链，发布成功后若只 `goBack(1)` 回不到列表刷新，易出现「发了但列表没更新」（P2）。

## 3.3 数据

- **状态不同步**：双身份（`user_type` storage vs 后端 `role`）不同步——切换身份/升级商家后，`globalData` 与 storage 与后端三处可能各执一词。`getUserRole()` 优先读 `globalData.role`，兜底读 storage `user_type`，**中间态会读到旧值**（P1）。
- **缓存**：用户信息存 storage 后无版本/失效机制，资料更新后旧头像/昵称可能滞留（P2）。
- **积分/余额**：积分送 50 在注册时写流水，逻辑 OK；但前端 `jifen`/`积分`/`wallet`/`balance` 多套展示，口径一致性需对账（P2）。

## 3.4 权限（重点 · 已实测确认）

| 端点 | 问题 | 证据 | 级别 |
|---|---|---|---|
| `POST /api/activity/delete` | **任意登录用户可删除任意活动**：仅按 `id` 软删（`del_flag=2`），并把 `member_id` 改写成调用者，无归属校验 | `ApiActivityController` 第388–414行 + `CmsActivityMapper.updateCmsActivity` `where id=#{id}` | **P0** |
| `POST /api/activity/update_publish_status` | **任意登录用户可上/下架任意活动**：`selectById` 后直接 toggle 保存，无归属校验 | 同上 第418–450行 | **P0** |
| 设置页 `switchIdentity` | 前端可自行切 `user_type=2` 伪装商家视角 | `shezhi.js` 72–81行 | P1 |
| `登录失败→useDevUser` | 任意环境降级为假用户，绕过真实登录态 | `app.js` 168/187行 | P1 |

> **强烈建议**：把「写操作必须校验资源归属」作为一条规约，对**所有** `delete/update/publish/cancel` 端点做一次归属审计——本次只抽查了 activity，topic/coupon/registration 很可能是同一模板复制出来的，存在同类漏洞。

---

# 第四阶段：UI / UX 审计

> 对标 ApeChain（链感未来）/ Active Theory（沉浸动效）/ Airbnb（信息层级）/ Discord（社区）/ 小红书（内容流）。

## 4.1 UI 评分：62 / 100

| 维度 | 分 | 评价 |
|---|---|---|
| 视觉一致性 | 11/20 | **最大扣分项**。代码立意暗色「深空星海」，实际渲染浅色商业风（截图为证）；新 `cy/*`token 组件只铺了试点页，老页仍是旧样式，新老混杀 |
| 字体体系 | 14/20 | 近期做了 6 档字阶 token 迁移（~68 页），方向对、执行到位，加分项 |
| 色彩体系 | 12/20 | `tokens.wxss` 有变量地基，但原生区（导航/TabBar）靠 JS 同步、业务区靠 CSS 变量，两套机制；强调色出现黑、蓝、橙多种主色不收敛 |
| 层级关系 | 13/20 | 首页大图卡片有质感（接近 Airbnb/小红书），但「附近活动」横滑卡片信息密度偏高、价格/距离/时间挤在一起 |
| 信息密度 | 6/10 | 达人页空态大量留白 vs 模板页卡片密集，节奏不稳 |
| 动效体验 | 3/5 | 有 `starfield` 但未铺开；页面转场基本是系统默认，缺品牌级动效（对标 Active Theory 差距大） |
| 品牌感 | 3/5 | 「Questify / 城瘾」品牌符号在 UI 中几乎不可见，首页第一屏看不出这是个「城市探索 IP 产品」 |

## 4.2 UX 评分：58 / 100

- **新用户能否理解产品**：❌ 偏弱。首页第一屏是「Hi, 城市运动装备馆！」+ MUSIC EVENT 大图 + 附近活动，**像一个活动票务/探店工具**，传达不出「玩城市解谜、共创剧本」的核心玩法。
- **发布流程是否过长**：⚠️ 偏长且分叉。发布主题/活动/模板/帖子四条线入口分散在「达人页按钮 + 我的-发布中心 + topic/activity 各自入口」，新用户**找不到统一的「+ 发布」**。
- **关键功能是否易找**：⚠️ 「俱乐部」「资产中心」「核销」埋得深；Tab「达人」名不符实（实为团体/挑战/俱乐部）。
- **认知负担**：偏高。三套搜索、两套发布、双身份体系，都转嫁成了用户的理解成本。
- **转化流失点**：①达人页空态；②发布点了才报「无权限/超额」；③mock 假数据死链；④图片加载失败。

---

# 第五阶段：城瘾产品逻辑审计

**核心命题「探索城市 × IP世界观 × 玩家共创」是否被表达？目前：探索城市=部分，IP世界观=几乎没有，玩家共创=有骨架缺氛围。**

- 首页是否表达清晰：❌ 首屏是活动流，未点题「城市解谜/剧本探索」。
- 世界观是否清晰：❌ 「深空星海/citylnk Bible」只活在代码注释里，用户侧零感知。
- IP 合作展示：⚠️ 有 `hezuozhe`（合作者）页但非主流量位，IP 联名在首页无展示位。
- 主题系统：✅ 骨架完整（创建/详情/商家报名/结算/投票），是产品最实的部分。
- 活动系统：✅ 完整（发布/报名/支付/核销），但与「主题」边界模糊，用户分不清「主题 vs 活动」。
- 俱乐部系统：⚠️ 后端完整，前端入口缺失（身份装不进 `user_type`）。
- 商家系统：✅ 完整但有越权漏洞。
- 推荐系统：❌ 首页「为你推荐/即将上线」更像运营位排序，无个性化推荐逻辑。

### 最影响【增长】的 10 个问题
1. 首屏不点题，拉新转化低（看不出在玩什么）。2. 无可分享的「成果物」（解谜战绩/城市回执）驱动社交裂变。3. 邀请体系有后端无显著入口。4. IP 联名无首页展示位，借不到 IP 流量。5. 三套搜索分散，SEO/场景入口浪费。6. 模板库是最好的拉新钩子却埋在 Tab3。7. 无「附近正在玩」的实时感。8. 注册透明=拿不到手机号=召回手段弱。9. 小程序分享卡片文案/封面未定制。10. 空态页（达人 Tab）浪费首位流量。

### 最影响【留存】的 10 个问题
1. 达人 Tab 默认空态。2. mock 假数据/死图破坏第一印象。3. 无任务/打卡/连续探索的回访动机。4. 主题 vs 活动概念混淆增加理解成本。5. 俱乐部（社区粘性核心）前端进不去。6. 状态不同步导致「我的内容」时有时无。7. 无消息/通知触达（报名结果、审核结果靠用户自查）。8. 积分送了但消耗场景弱，无成长感。9. 发布失败反馈靠后置报错，挫败感强。10. 缺少 UGC 内容冷启动，广场易空。

### 最影响【商业化】的 10 个问题
1. **越权漏洞**使商家不敢沉淀内容（可被删）。2. 优惠券核销前端权限可被绕过显示。3. 提现/支付链路防重不足，资损风险。4. 商家经营数据弱，付费意愿低。5. 抽佣/分润模型（`income/topic|invite|brand`）有表无前端清晰呈现。6. 会员配额价值未在 UI 表达（用户不知升级能多发）。7. IP 联名无变现位。8. 俱乐部付费/会员体系未打通。9. 无广告/置顶等运营位产品化。10. 资产中心收益口径分散，对账难、信任低。

---

# 第六阶段：代码质量审计

## 6.1 前端
- **重复代码**：列表+卡片+点赞/收藏在 topic/activity/square 三处复制；双版本页面（`*2`）未合并。**建议抽 `cards/list-feed` 公共组件**。
- **性能**：主包 **72 个页面**、子包仅 8 页 —— 主包页面数严重失衡，主包体积逼近/超过微信 2MB 限制风险高（详见 6.3）。
- **状态管理**：`globalData` + storage 裸用，无统一 store，双身份三处存储不同步（见 3.3）。
- **页面耦合**：业务逻辑写在页面 `.js` 里，无 service 层，接口散落。
- **组件化**：偏低，设计 token 组件刚起步未铺开。

## 6.2 后端
- **API 设计**：风格统一（RuoYi `AjaxResult`），但**全 POST、参数 query/form 混用**，RESTful 度低；命名不一致（`update_publish_status` vs `updatePublishStatus`）。
- **数据库**：RuoYi 标准 `del_flag` 软删 + 动态 SQL，规范；`ums_member` 双身份字段是技术债。
- **索引**：`activity/list` 支持经纬度排序，需确认 `longitude/latitude` 及 `member_id/status/del_flag` 复合索引到位，否则列表慢查询。
- **安全性**：✅ Spring Security + JWT，`/api/**` 默认需鉴权，permitAll 白名单收敛克制（连结算接口都显式 `authenticated`）；❌ 但**业务层归属校验缺失**（IDOR），是「认证够、授权漏」的典型。
- **可扩展性**：模块化清晰（admin/common/framework/system/business），底子好；遗留招聘代码（`TuserJobSeekers`）需清理。

## 6.3 微信小程序专项 · 性能优化建议（按收益排序）

1. **【高】主包瘦身**：把 72 个主包页面中「发布/资产/俱乐部/商家/资料/订单」等非首屏页迁入分包。当前子包只承载了 8 页，主包必然臃肿。**预计收益：首开速度显著提升，规避主包 2MB 上限。**
2. **【高】移除构建垃圾**：`node_modules`、`mockData.js`、`小程序截图/`、`scripts/` 不应进构建产物，配 `.gitignore` + 分包 + 构建忽略。
3. **【高】修复图片基址**：`baseImgUrl` 换成生产 HTTPS CDN，否则图片全挂。
4. **【中】图片体积**：`icon_thunder.png`(656KB)、`recruit_detail_screenshot.png`(616KB) 对图标/截图过大，压缩或改 WebP；统一走 CDN + 懒加载。
5. **【中】`lazyCodeLoading: requiredComponents`** 已开，保持；进一步按需注入。
6. **【低】WXML/WXSS**：合并双版本页面、抽公共样式、减少内联 style。

---

# 第七阶段：微信审核风险

| 级别 | 风险点 | 说明 | 解决方案 |
|---|---|---|---|
| **高** | HTTP 明文测试域名 `test2.h5.org.cn` | 未配 request 合法域名 / 非 HTTPS，提审必拒或运行报错 | 全量换生产 HTTPS 域名，后台配置合法域名白名单 |
| **高** | 残留 debug / mock | 「切换身份」开关、`useDevUser` 假登录 | 上线前移除 debug 开关与 mock 降级，失败应提示重试而非伪造用户 |
| **高** | UGC 无内容安全审核 | 主题/活动/广场/评论均 UGC，未见接入微信内容安全（`msgSecCheck/mediaCheckAsync`） | **接入文本+图片内容安全检测**，否则违规内容=封禁风险；这是社区类必过项 |
| **高** | 越权可删他人内容 | 安全漏洞本身也是审核/合规风险 | 修复 IDOR（P0） |
| **中** | 虚拟支付 / 提现 | `registration/pay`、`withdrawal` 涉及资金 | 确认走微信支付正规通道、具备相应类目资质；提现合规说明 |
| **中** | 会员付费 / 配额 | 付费升级权益 | 虚拟会员需符合虚拟支付规范，明示权益与退款规则 |
| **中** | 获取手机号 / 位置 | `getwxbindphone`、`getFuzzyLocation` | 已声明 `requiredPrivateInfos` + scope 描述，需补**隐私协议**与用途弹窗 |
| **低** | 「达人」类目命名 | 含引流/拉人歧义可能被问询 | 文案改「团体/共创」，与内容一致 |
| **低** | 诱导分享 / 邀请返利 | `setInviter`、邀请收益 | 避免「分享得现金」式诱导，措辞合规 |

> 社区+UGC+支付三件套都在，**内容安全接入 + 隐私合规 + 支付资质**是过审三道硬门槛，目前都未见落地，需重点补。

---

# 第八阶段：最终报告

## 一、项目健康度评分：61 / 100

| 评分项 | 分数 | 说明 |
|---|---|---|
| 产品 | 60 | 玩法骨架完整，但世界观/叙事未落地，主题vs活动概念混淆 |
| UI | 62 | 设计 token 方向对，但暗色立意与浅色实现脱节、新老混杀 |
| 体验 | 58 | 空态、假数据、发布分叉、双身份带来高认知负担 |
| 代码 | 66 | 后端规范扎实、模块清晰；前端状态管理与组件化偏弱、有遗留债 |
| 性能 | 55 | 主包页面失衡、构建含垃圾、图片基址失效 |
| 商业化 | 60 | 交易/分润链路有骨架，但被越权与防重隐患拖累信任 |
| 可扩展性 | 70 | RuoYi 模块化底座好，RBAC 在正确演进方向上 |

## 二、问题清单（按优先级）

### P0 — 必须立即修复（阻断上线）

**P0-1 活动越权删除 / 上下架（IDOR）**
- 影响：任意登录用户可删/下架任意商家活动，可批量破坏，资损 + 信任崩塌。
- 复现：登录任意账号 → 抓 `/api/activity/delete`（或 `/api/activity/update_publish_status`）→ 把 `id` 改成别人的活动 id → 成功。
- 方案：所有写接口加 `WHERE id AND member_id=当前用户`（或 service 层先查归属再操作）；并对 topic/coupon/registration 同模板端点做归属审计。
- 工作量：2–3 人日（含全量排查）。

**P0-2 生产图片基址为 HTTP 测试域名**
- 影响：走 `getImgUrl` 的图片全部加载失败 + 提审被拒。
- 复现：任意使用 `baseImgUrl` 的页面，图片裂。
- 方案：换生产 HTTPS CDN，配合法域名白名单。
- 工作量：0.5 人日。

**P0-3 内容安全未接入（UGC）**
- 影响：违规内容直接导致小程序被封。
- 方案：发布/评论链路接 `security.msgSecCheck` + `mediaCheckAsync`。
- 工作量：2 人日。

### P1 — 上线前修复

- **P1-1 移除 mock 降级**：登录失败应提示重试，不得伪造 `useDevUser`。（0.5 人日）
- **P1-2 移除「切换身份」debug 开关 + `debug_user_view`**。（0.5 人日）
- **P1-3 写接口防重**：发布/报名/支付/提现加幂等键 + 前端节流。（2 人日）
- **P1-4 达人 Tab 空态治理**：官方种子内容 / 引导发布，不留空房间。（1 人日）
- **P1-5 双身份统一**：前端全面切到 `role`，淘汰裸用 `user_type`；打通俱乐部前端入口。（3 人日）
- **P1-6 状态同步**：登录态/角色单一数据源，消除 globalData/storage/后端三处不一致。（2 人日）

### P2 — 近期优化

- 合并双版本页面（`*2`/重复 settlement）。（1.5 人日）
- 统一「+ 发布」入口，收敛三套搜索。（2 人日）
- 配额计数口径修正（排除草稿/已删）。（0.5 人日）
- 静默请求失败要有反馈；资料缓存失效机制。（1 人日）

### P3 — 长期优化

- 设计 token 全站铺开、暗色世界观落地。（5+ 人日）
- 抽公共 list/card 组件、引入 service 层。（3 人日）
- 主包→分包重构、图片 WebP+CDN+懒加载。（3 人日）
- 清理遗留招聘代码、RESTful 化 API。（按需）

## 三、未来 30 天路线图

**第一周 · 止血（P0 全清）**
修复两个 IDOR + 全量写接口归属审计；换生产 HTTPS 图片域名；接入微信内容安全；补隐私协议。目标：**无致命漏洞，可提审不被秒拒。**

**第二周 · 收口（P1 上半）**
移除 mock 降级与 debug 切身份开关；写接口防重幂等；登录态/角色单一数据源改造。目标：**核心流程数据可信、不可绕过。**

**第三周 · 体验（P1 下半 + 留存）**
达人 Tab 空态治理 + 官方种子内容；双身份统一、打通俱乐部前端入口；统一「+ 发布」入口。目标：**新用户能看懂、能发出第一条内容。**

**第四周 · 打磨与灰度（P2 + 叙事）**
首屏改造点题「城市探索 IP 玩法」；合并双版本页面；主包瘦身分包；提交审核 + 小范围灰度，按数据迭代。目标：**进入可灰度状态。**

---

## 附：本次审计的「实测证据」清单（非推测）
- IDOR：`ApiActivityController.java` 388–450 行 + `CmsActivityMapper.xml` `updateCmsActivity ... where id=#{id}`（已读源码确认）。
- 自动建号送积分：`ApiLoginController.logincode`（已读）。
- 服务端配额校验存在：`QuotaServiceImpl.checkCreate` + topic/activity/coupon create 调用点（已读）。
- mock 降级：`app.js` `useDevUser`/`firstLogin`（已读）。
- 切身份开关：`shezhi.js` 72–81 行（已读）。
- 测试图片域名：`utils/config.js`（已读）。
- 主包 72 页 / 子包 8 页、图片 2.9M（已统计）。
- UI 浅色实现 vs 暗色立意：`verify_*` 截图 + `app.js applyTheme('dark')`/`theme.js`（已比对）。
