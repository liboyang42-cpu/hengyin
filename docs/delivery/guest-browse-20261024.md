# 游客可浏览，写入时弹窗登录（owner 2026-10-24）

> 业主原话：「没登录时，报名活动、加入俱乐部、发帖会弹窗；写入数据库时才需要注册，其他情况不登录也能看。」
> 同时满足微信审核规则：游客必须先能浏览，再谈登录。

分支 `feat/guest-browse-login-on-write-20261024`。push ≠ 部署：后端 jar 与小程序上传均需业主操作。

## 0. 改动前的实际状态（已核实）

- `app.js onLaunch → appInitial → firstLogin() → session-manager.ensureSession()`：没有缓存会话时立即
  `wx.login → POST /api/login/code → UmsMemberService.getOrCreateWechatMember(openid)`，即**每个访客一打开就被静默建号**。
- `utils/compliance/usage-consent.js agree()`：勾选隐私协议后同样调 `firstLogin()` 建号。
- `utils/transport/request-client.js`：任何请求 401（含游客读请求）都会静默 `reLogin()`，即游客浏览时也会被建号。
- `pages/roam/index.js goStart()`：游客点「开始漫游」静默 `firstLogin()`。

## 改动后

| 场景 | 行为 |
|---|---|
| 冷启动，有缓存会话 | 照旧恢复，老用户保持登录（`ensureSession` 命中缓存，不发 `wx.login`） |
| 冷启动，无缓存会话 | 保持游客：不 `wx.login`、不 `/api/login/code`、不建会员行；功能开关照常拉取 |
| 未同意隐私 | 隐私闸行为不变（首个数据处理接口前弹出）；同意后**不建号**，同意留痕在首次登录成功后由 `syncPending` 补传 |
| 游客读请求 401 | 不弹窗、不建号、不 toast，按 401 交给页面 `fail`（页面显示游客态/登录入口态） |
| 游客写请求 401 | 弹同一个「登录后继续」，登录成功用新会话重发恰好一次；取消则按 401 失败 |
| 游客点写入口 | 先弹「登录后继续」（cy-modal），「微信登录」= `app.firstLogin()`（此刻才建号），成功后**自动恢复原动作一次**；「暂不登录」页面不变 |

唯一实现：`chengyinhub-xcx/utils/auth/require-login.js`（`requireLogin({ reason }) → Promise<boolean>`、`withLogin`）。
App 上暴露 `app.requireLogin(options)` 与同步用法 `app.ensureLoginFor(reason, resume)`（已登录同步返回 true；游客返回 false 并在登录后 `resume()`）。
弹窗复用 `utils/modal.js → cy-modal-host → cy-modal`，无新视觉：标题「登录后继续」，正文一句话（按动作），按钮「暂不登录」/「微信登录」。

## 1. 对游客开放的读接口（全部用方法级 `@Anonymous`，**未改 SecurityConfig、未改 JWT/token 校验**）

| 接口 | 页面 | 为什么可以公开 | 游客看到什么 |
|---|---|---|---|
| `POST /api/topic/info-to-user` | 主题详情/封面（pages/topic/index、official-detail、roam 主题半屏、square 路线卡） | 玩家视角公开投影：票走 `PublicTicketVO` 脱敏、模板走 `TemplateSecrets.strip`、发布者走 `PublicMemberSummaryVO`、分支图只给 teaser | `memberId=0`（新增 `optionalAppUserId()`）：可见性只走公开闸（`status=1 and user_status=1` + 圈子公开闸，命不中「本人草稿」）；`isSignUp/isRegistrationMerchant/isOwner=0`、`canResubmitSupplyReview=false`、不下发驳回原因、多章主题只解锁首章、不放行承接站 |
| `POST /api/club/detail` | 俱乐部详情 | 方法本就对未登录降级（try/catch），出参 `PublicClubVO` 白名单 | 非公开团仍「只对成员开放」；`isOwner/isJoined/viewerIsAdmin=false`、`myJoinReviewReason=""`、待审数 0；不出手机号 |
| `POST /api/club/list` | 俱乐部 tab（游客目录） | 已审核团目录，`canViewClub` 过滤非公开团，出参 `PublicClubVO` | 只有公开可见团，不打 `isJoined/isOwner` |
| `POST /api/creativesquare/info` | 广场动态详情 | 与早已匿名开放的 `/api/creativesquare/list` 同一投影（`enrichCreativeSquareList`） | 只给已审核未删内容；非作者剥定位；`isLiked/isBookmarked/isFollowTheUser=0` |

不需要改动、本就匿名可读（既有白名单 / `@Anonymous`）：`/api/activity/list`、`/api/topic/list`、`/api/creativesquare/list`、
`/api/template/homeData|list|info`、`/api/template/topic-template/list|info`、`/api/category/list`、`GET /api/official/events(/*)`、
`/api/merchant/public-detail|public-home|list`、`/api/user/public-info`、`/api/comment/list`、`GET /api/roam/share/snapshot`、
`/api/config/features`、`/api/v1/community/{capabilities,feeds/*,posts/*}`（读）、`/api/agreement/*`（协议页）、`/api/merchant/reviews/public`。

证据：
- `GuestBrowseAnonymousReadTest`（10 例）：四条接口游客 → 200 + 公开字段、无本人态/私有字段；非公开团/未审内容不出；
  写兄弟 `club/join`、`club/home`、`club/members`、`topic/like`、`creativesquare/like` 游客仍「请先登录」/401；
  并钉死这三个控制器里**只有这四个方法**带 `@Anonymous`、且这四条路径各只对应一个处理方法（`@Anonymous` 放行不分 HTTP 方法，同路径若挂写方法会被顺带放行）。
- `PublicReadEndpointsSecurityChainTest#guestBrowseReadsAreAnonymousButTheirWriteAndPersonalSiblingsAreNot`：真实 `SecurityConfig` + `PermitAllUrlProperties` 过滤链下，
  四条匿名 200；`club/join|quit|home|members`、`club/post/create|like`、`topic/like|info-to-merchant`、`creativesquare/like|action`、`comment/add`、`registration/create`、
  前缀扩展 `/api/club/detail/extra`、`/api/topic/info-to-user/extra` 全部 401。

## 2. 已加写前登录闸的写入口

请求层兜底：`request-client` 对游客写请求的 401 统一弹同一窗口（写/读判定：`param.write` 显式优先；GET 恒读；PUT/PATCH/DELETE 恒写；POST 按路径末段动词）。
显式入口（函数体第一行 `app.ensureLoginFor(reason, () => this.<同一函数>(...))`，契约测试逐条钉住）：

| 动作 | 入口 |
|---|---|
| 报名 / 购票 / 支付 | `activity/baoming` `handlePayment`、`joinWaitlist`；`topic/index` `bmClick`、`selfPlayBuy`、`onSelfPlayGoCheckout`；`activity/official-detail` `doSignup` |
| 加入 / 申请俱乐部 | `club/detail` `onJoin`（公开团直进与审批团申请同一入口）；`talent/list` `onClubJoin` |
| 发帖 / 评论 / 回复 | `club/detail` `onPostSubmit`、`onCommentSubmit`；`square/list` `submitFormData`；`square/detail` `addComment`、`addReplyComment`；`square/components/cy/post-compose` `onPublish`；`topic/index` `submitComment` |
| 点赞 / 收藏 / 关注 | `club/detail` `onLikePost`；`square/list` `lickClick`、`favoriteClick`、`followClick`；`square/detail` `lickClick`、`favoriteClick`、`followClick`、`commentLikeClick`、`commentFollowClick`；`topic/index` `toggleFav` |
| 建队 / 入队 | `team/join` `join`；`subpackageMember/utils/team-up.js createActivityTeam` |
| 扫门口码开始游玩 | `pages/index` `consumeDoorScene` 的 `/api/play/scan-entry` 声明 `write: true, loginReason: 'play'`（请求层弹窗、登录后重发；取消不报「码无法打开」） |
| 开始漫游（写足迹进度） | `roam/index` `goStart`（原静默 `firstLogin` 改为 `app.requireLogin({ reason: 'roam' })`，取消不报错） |
| 发布主题 / 模板 / 活动 | `publish/fabu` `submitForm`、`confirmPublishCheck`；`publish/temp` `publishGame`、`saveDraft`；`publish/activity` `submitForm`；`template/index` `useTt`、`goTopicConfig` |
| 举报 | `club/detail` `onReportPost`、`onReportComment`、`reportClubMember`、`reportClub`、`reportTopicActivity`；`square/list` `reportSquare`；`square/detail` `reportSquare`、`reportSquareFromActions`、`reportComment`；`topic/index` `reportTopic`；`utils/content-report.js run()`（主题/模板/个人模板举报）；`subpackageMember/components/cy/report-sheet` `submit` |

游玩中的写（`play/arrive`、`play/checkin`、谜题揭晓等）只在已购票的本人会话里出现；若会话失效由请求层同一弹窗兜底。
手机号绑定（如支付前）保持原流程不变。

## 3. 游客看到的登录入口态（不报错）

- **邀请卡冷启动（A-01）**：被邀请人打开时不再建号，`setInviter` 待发邀请保留到首次登录后，回到首页 `onShow` 补发一次。

- **俱乐部 tab**：游客改读公开目录（可点进详情）；目录读失败才退回原「登录后查看我的俱乐部」入口。
- 我的（`pages/member/index` → profile 组件）、票夹/订单（`scene-member-order-history`）、优惠券钱包、成长中心/排行、勋章墙、
  资产收益/收入明细/邀请/提现记录、投诉、我的模板、注销、我的官方活动（`official-mine` / 列表「我的」tab）、店铺参谋、商家报名、
  消息（IM 列表）、发布中心（`publish/fabu` 登录入口）：均为既有的「登录后查看」入口态（既有契约
  `private-read-login-entry`、`w1-named-private-entry`、`official-activity-mine-login`、`coupon-wallet-login-recovery`、
  `publish-private-login-entry`、`merchant-private-login-entry` 等覆盖），本次未改它们，游客 401 不再静默建号后仍停在入口态。
- 首页 `pages/index`：私有块（用户信息、继续游戏、我的报名）在无会员时本就不发请求。
- 主题详情：收藏状态、角色卡等私有读在游客下不发（`_loggedIn()` 门控）。

## 4. 仍存在的缺口

1. **漫游地图的据点/附近读接口仍需登录**（`GET /api/roam/pois`、`/api/roam/entry/nearby`、`/api/team/nearby`、`/api/ai/npc/profile`、
   `/api/official/active-for-roam`、`/api/roam/nearby-exploreday`、`/api/city/nodes`）：它们带按 viewer 计算的个人态，
   且既有契约明确要求 `city/nodes` 必须鉴权；游客打开漫游页只看到底图与公开主题半屏（主题半屏走已开放的 `info-to-user`），
   不出据点层。如需游客也看据点，需要逐条做游客投影并另行授权。
2. 俱乐部详情里的**成员名单、帖子流、主题列表、排行榜**（`/api/club/members|post/list|topics|leaderboard`）仍需登录；
   游客只看公开资料卡，这几块静默为空。成员名单涉及他人身份，不建议直接开放；帖子流/主题列表可在后续逐条做公开投影。
3. 私有页在游客下读到 401 时，部分商家/俱乐部财务页（如 `coop/settlement-detail`、俱乐部结算）显示的是通用失败态而非登录入口；
   游客正常导航到不了这些页（入口在商家工作台/俱乐部工作台，二者已有登录入口态）。
4. 请求层的写/读判定是按路径动词的启发式；未被识别为写的 POST 在游客 401 时按读处理（静默失败、不弹窗）。
   所有业主点名的写入口都已有显式闸，不依赖这条启发式；新增写入口应调用 `app.ensureLoginFor` 或在请求上传 `write: true`。
5. 后端单测为控制器级 + 过滤链探针；未在测试 VPS 上对真库做游客 HTTP 回测（本仓禁止连生产库，测试库回测需业主在 VPS 执行）。
6. 小程序模拟器截图未做（云端会话无微信开发者工具）；需业主在 Mac 上用开发者工具清缓存冷启动验收：
   首页/漫游/主题/模板/俱乐部/广场/搜索/活动/商家公开页/关于与协议可浏览；点报名/加入/发帖/点赞弹「登录后继续」，「微信登录」后动作自动继续。
