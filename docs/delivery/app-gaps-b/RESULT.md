# App gaps B — 交付结果（feature/app-gaps-b）

基线 hengyin `integration/backend-app-20261112`（c96d345ff）。需求来源：App `ui/capgraph`（c2202dbe3）
`docs/release/capability-graph.md` (2) APP-NOT-IN-BACKEND 与 `capability-graph.csv`，本工作线负责 95 条路径。
全部构建、单测、真库 IT 均在测试 VPS 上执行；真库 = be-int MySQL 8.0.45 实例里的 **cy_gaps_b**（cy_e2e_beint 的副本），
从未连接共享库 cy_e2e_beint 或生产。

状态：**SERVED** = 本分支新增实现；**MAPPED** = 后端已有等价路由（见 MAPPING.md，不重复实现）；
**OFF** = 按策略关闭并显式拒绝；**DEFERRED** = 未实现（原因列出）；**N/A** = 美国市场/原生-only/其他工作线承接。

## 共用底座

- `app_original_request` 台账（`chengyinhub-admin/sql/migration_20261113_appgaps_original_request.sql`，台账依赖已登记）：
  同一 (protocol, actor, requestId) 只执行一次原写；COMMITTED 行与业务写同一事务；业务拒绝回滚后另起事务记 REJECTED；
  未知异常不留行（同 ID 可安全重试）；并发同键只执行一次（真库 IT 覆盖：6 线程并发、InnoDB 死锁回退重试）。
- 所有新协议的摘要都由服务端从原字节/原字段按 App 同一公式重算，不信任客户端摘要。

## 按区域

### club（35）
| 路径 | 状态 | 说明 |
|---|---|---|
| `/api/club/admission/receipt` | SERVED | `/api/club/join|quit` 接受 `X-Club-Admission-*` 头走台账；读回 APPLIED/UNKNOWN 或原拒绝 |
| `/api/club/badge/original-receipt` | SERVED | `/badge/create|grant` 接受 `X-Club-Badge-*` 头；`CREATED/GRANTED/ALREADY_GRANTED`，auditId=台账行 |
| `/api/club/open-settings/welcome-note-receipt` | SERVED | 欢迎语写接受 `X-Club-Welcome-*` 头；APPLIED/REJECTED/UNKNOWN 精确键集 |
| `/api/club/roles/assign-receipt`、`/revoke-receipt` | SERVED | 只读既有 `business_audit_event` 审计事实（无新表），非原发起人 403 |
| `/api/club/post/operation`、`/operation/comment`、`/operation/receipt` | SERVED | club-post-v1：create/update/receipt、comment/create|receipt；查不到即 PENDING |
| `/api/club/lead`、`/api/club/post`、`/api/club/roles` | MAPPED | 前缀误判 |
| `/api/club/activity/*`(3)、`/events*`(3 of 4)、`/join-requests/apply`、`/notices`、`/notices/send`、`/workbench`、`/settlements/export`、`/api/join-request/approve|reject` | MAPPED | 小程序页面名，App 注明不发请求；等价路由见 MAPPING §3 |
| `/api/club/events/proxy-enroll` | DEFERRED | 后端无代报名能力；App 无线格式 |
| `/api/club/group-code`、`/group-code/save` | MAPPED（pass 2） | App `Core/Adapt3GroupCodeIssueAdapter.swift` 已把 B1 `api/club/group-code` 落到既有 `POST /api/verify/groupcode/issue`（表单参数 `activityId`）；`/save` 无 App 线格式、无独立后端语义，不建 |
| `/api/club/event-ops/series/original-result` | SERVED（pass 2） | createSeries 首次插入同事务冻结完整初始结果（线格式 15 键、系列行、场次、生效日期）+ receiptHash 到 `club_event_series_original_result`；读回只认本人 (club, trim(requestId))，线格式不一致 409，无快照（含上线前系列）UNKNOWN |
| `/api/club/settings/notify-level` | N/A → channels 工作线（pass 3 复核） | `feature/im-channels`（bb4ee788a）现已提供 `/api/im/notify-pref` 与 `/api/im/notification-requests/{id}`（`ApiChannelNotifyController` / `ChannelNotifyService`，NOTIFY 回执族），但没有 `CLUB_DEFAULT` 操作与 notify-level 路由；App 的俱乐部默认级别保存回执必须由该回执族写出（同一 `/notification-requests/{id}` 读回，REJECTED 只允许 `CLUB_DEFAULT_VERSION_CONFLICT|EXHAUSTED`），且该分支不以本基线为祖先。在本分支另起生产者会产生两套回执，故交 channels 工作线（规格见 MAPPING §4） |
| `/api/club-compensation/quality/evidence` | OFF | 俱乐部主理人报酬链路（club-host money）按要求不开 |
| `/api/join-request/cancel`、`/api/space/join-pause` | MAPPED（pass 2） | `feature/im-channels`（6b6895f61）`ApiChannelAdmissionController` 已实现，本分支不重复 |
| `/api/club/channels`、`/api/guilds/{}/{}/channels`、`/channels/manage` | N/A | `feature/im-channels` 承接 |

### workshop/mods（20）
| 路径 | 状态 | 说明 |
|---|---|---|
| `/api/workshop/owned`、`/owned/list`、`/owned/detail`、`/owned/package` | SERVED | 现网无免费领取事实源，如实 `NOT_ENABLED` + 空集（不把付费许可冒充免费领取） |
| `/api/mod-commerce` | OFF | 购买/支付/退款族一律拒绝：未登录 401，已登录 503 + errorCode `WORKSHOP_COMMERCE_DISABLED`（r2 对齐 App 只认 code 的处理）；不读请求体、不建单 |
| `/api/workshop/purchased`、`/purchased/install` | MAPPED | → `/api/workshop/v2/purchased/*`（后端对 v1 刻意 409 升级） |
| `/api/mod-installation`、`/api/mod-upgrade` | N/A-v1（owner 决定，显式 503） | App 契约（`Core/ModInstallation*`、`ModUpgrade*`）是「模组发行版 + 商家资源绑定（SITE/POI/SKU/NPC/ROUTE_ENTRY）」安装域：options/compatibility/requests/receipt/history/qa-editor/template-editor，需要模组目录、发行版与 requiredBindings 事实源；后端只有 v2 工坊订单许可（`WKO_*`）+ 私有草稿安装，不存在模组/发行版/绑定模型，也没有免费模组来源。不能用 v2 安装冒充（线格式与语义都不同）。需先建模组目录领域 |
| `/api/mod-owned-report/licenses` | N/A-v1（owner 决定，显式 503） | 依赖同一模组许可域（`ModOwnedContentReport` 按模组 license 读报告） |
| `/api/mod-settlement/mine`、`/mine/income` | OFF | 创作者结算/收入属于资金读写面，按 owner 决定不开 |
| `/api/workshop/creator/pending-packages`、`/public-theme-use` | OFF（owner 决定，显式 503） | App 契约 `WorkshopCreatorPendingCommand` 每条都带 `priceMinor/currency/acquisition/buyerKinds` 并生成待上架 `proposedOffer`——这是虚拟商品售卖配置写，按 owner 决定关闭；`public-theme-use` 同意是该待上架包的条款同意前置，脱离售卖配置无独立语义。若要只开免费公开使用，需要 App 侧给出不含售价的新协议 |
| `/api/workshop/purchased/professional-operations/*`(4)、`/professional-targets/*`(2) | DEFERRED（App 任务：先迁 v2 已购线格式） | 只作用于已购许可、本身不动钱，但 App 命令/回执把 `licenseId` 校验为 `w18-paid-<n>`、并携带 App W18 付费安装流的 `installationRequestId`/`componentHash`；后端实际许可是 v2 `WKO_<32hex>`、安装命令无 componentHash。按 v2 数据构造的任何响应都会被 App 自身校验拒绝。前置：App 先迁到 v2 已购线格式（MAPPING §2），再在 `WorkshopService` 上补「已装私有草稿→目标主题受保护模板」物化（revision/payloadHash 复用 v2 targets 公式） |

### creator/publishing（12）
| `/api/project/state-requests/context|submit|read` | SERVED | 基线摘要来自 `/api/project/my` 同一行；submit 前重算基线，变化即拒绝；模板上下架把 App `publish_status` 映射为现有 `status`(1/3) |
| `/api/content-draft` | MAPPED | 前缀 |
| `/api/approved-topic-release/v1/review/sources|prepare|submit|status|current`、`/prepare`、`/publish`、`/status`(8) | SERVED（pass 3） | 见下「Pass 3」。送审走既有 BIZ_TOPIC 组合审核（`TopicPublicationService.submitReviewBundle`，不触发发布前公开）；发行=已批准快照的不可变分配（`approved_topic_release`），不改公开可见性；商家来源/封面绑定无事实源，如实空集/不出 |

### activities（3） / templates（3）
| `/api/activity/create-requests/submit|read|resume-post-processing` | SERVED | original-activity-create-request-v1，原写 `/api/activity/publish`；无后置异步处理，resume=read |
| `/api/template/author-requests/submit|read` | SERVED | original-template-author-request-v1，原写 `/api/template/draft|publish` |
| `/api/template/report-receipt` | N/A（owner 决定：App 无线格式） | `Core/TemplateContentReportContracts.swift` 仍只有路径常量与摘要函数，无请求/响应线格式 |

### 单路径区域
| 路径 | 状态 | 说明 |
|---|---|---|
| `/api/growth/leaderboard/anonymous` | SERVED | 同一排名，他人固定「匿名玩家」、无头像/会员号 |
| `/api/common/uploadOSS/receipt` | SERVED | `/api/common/uploadOSS` 接受 `X-Upload-Request-ID`；original-upload-request-v1，未过审只回 PENDING |
| `/api/map/public-layers` | SERVED | store/activity 两层实时投影公开表；roam_poi/theme/story_node 如实 UNAVAILABLE；id 水位游标 |
| `/api/compliance/usage-records`（含 `/request`） | SERVED | 本人 app_launch 留痕 + 精确版本协议原文（MISSING/UNPUBLISHED/INCOMPLETE/SOURCE_AVAILABLE） |
| `/api/support/tickets`（list/detail/timeline） | SERVED | 新表 `support_ticket*`（migration_20261113_appgaps_support_ticket），只读本人、内部备注不出；写入侧（客服后台）不在本范围 |
| `/api/npc/chat/delete` | MAPPED | → `POST /api/ai/npc/chat/data/delete` |
| `/api/official/v2/events` | MAPPED | 前缀（publish-check） |
| `/api/marketing/product` | MAPPED | 前缀 |
| `/api/ugc/block`、`/api/v1/community` | MAPPED | community square |
| `/api/coupon` | MAPPED | 前缀 |
| `/api/coupon/command-receipt` | SERVED（pass 2） | `/api/coupon/publish|stop` 带 `X-Coupon-Command-Id`+`X-Coupon-Merchant-Id` 走 coupon-command-v1 台账：服务端按 App 二进制规范重算 payloadHash；成功与预检拒绝连同执行时券状态写成不可变回执；读回只限本人本商家 |
| `/api/invoice` | MAPPED | 前缀，全部子路由已在 |
| `/api/pay/create` | OFF | 虚拟商品支付按策略关闭（App 侧恒阻断），后端不开 |
| `/api/asset/withdraw` | OFF | 钱包提现关闭（wallet read 已有 `/api/user/income*`） |
| `/api/registration/original-event` | DEFERRED | 无任何写入 ORDER/RUN 目标的审计事件生产者 |
| `/api/team/original-request` | DEFERRED | 依赖好友组队邀请（social/friends） |
| `/api/invite/revoke` | MAPPED（pass 2） | `feature/im-channels` `ApiChannelAdmissionController` |
| `/api/privacy/progress` | MAPPED（pass 2） | `feature/im-channels` `ApiProgressPrivacyController` |
| `/api/us/auth/apple/challenges|exchange`、`/api/us/auth/session` | N/A | 美国市场 |

## 验证

- 单测：`com.chengyinhub.business.appgaps.*Test`、`web.controller.api.appgaps.*Test` 以及受影响的既有控制器测试
  （ApiClub*/ApiCommon*/ApiActivity*/ApiTemplate*/ApiProject*/ApiGrowth*）。
- 真库 IT（`APPGAPS_MYSQL_IT=1`，`AppGapsMySql` 只允许 `cy_gaps_b*`）：台账、俱乐部回执、原始请求信封、地图图层 SQL、同意留痕、客服工单。
  这些 IT 依赖 TEST 副本里的既有表（business_audit_event、mms_merchant 等），不在 ci/real-storage-it-registry 的空库容器批次里登记。
- 迁移台账：`python3 ci/migration_ledger.py audit` problems=[]。
- 启动冒烟：本分支打包后以独立端口 8131 连 cy_gaps_b（Redis db 11、每次随机密钥）启动成功（微信支付证书缺失按预期降级），
  用夹具玩家 13900000001 真实登录后逐条调用本分支新路由：全部 code=200（项目 context 对不存在项目 404、mod-commerce 503），
  未登录 401。冒烟后已停止该进程。

## Pass 2（2026-11-13）

新增 SERVED：`/api/coupon/command-receipt`（含 publish/stop 生产者）、`/api/club/event-ops/series/original-result`。
复核改 MAPPED：频道准入族与 `/api/privacy/progress`（`feature/im-channels` 6b6895f61 已实现）、`/api/club/group-code`。
仍未建：approved-topic-release(8)、mod-installation/upgrade、creator pending/consent、professional-*(6) 及上表 DEFERRED 行，原因逐行写明。
新迁移：`migration_20261113_appgaps_series_original_result.sql`（台账已登记，audit problems=[]）。

验证：单测（CouponCommandReceiptsTest、ClubSeriesOriginalResultsTest + 既有 ApiCouponController*/CouponPublishReviewMode/
ClubEventOpsServiceImpl/ApiClubEventOpsController/E01_* 回归，全绿）；真库 IT（cy_gaps_b：CouponCommandReceiptsMySqlIT 用真
SmsCouponMapper 插入/停发 CAS，ClubSeriesOriginalResultsMySqlIT 唯一键与回滚，全绿）；启动冒烟（8131 连 cy_gaps_b，夹具商家
13900000002 / 俱乐部主 13900000008）：错商家头 403、发布回执 SUCCEEDED 且同 ID 重放同一回执不重复建券、回执读回、未知 ID 空 data、
停发终态回执、非商家 403、无头旧线格式不变、系列原始结果 UNKNOWN、未登录 401。冒烟后已停进程。
TEST 环境事实：无微信内容安全凭据时门禁一律 HOLD，券以 status=5（待审）落库，回执如实写 `couponStatusAtExecution=5`——App 对
PUBLISHED 要求 1，会按「未知」处理而不是误判已上架；待审券再停发回 `COUPON_ENDED`(5)（沿用旧写「已结束」文案），App 同样视为未知。
系列创建写路径冒烟未覆盖（夹具俱乐部无可开主题），由真库 IT 覆盖快照写入。

## Pass 3（2026-11-13）

新增 SERVED：`/api/approved-topic-release/v1/*` 8 条（App `Core/ApprovedTopicRelease*.swift`、`ApprovedTopicReview*.swift`、
`ApprovedMerchantReviewSources.swift`、`ApprovedTopicSelectedCover.swift`、`ApprovedTopicReviewObservation.swift`，origin/ui/main）。

| 路由 | 行为 |
|---|---|
| `review/sources` | `merchant-source-choices.v1` 精确 11 键；本后端没有 `MERCHANT_AI_TEMPLATE_SOURCE_V1`/`MERCHANT_STORE_FACTS_CONFIRMATION_V1` 事实源，`sources=[]` |
| `review/prepare` | `review-preparation.v1` 捕获：城市版（productType=1）pro 主题、章节块只认 text/node（旧章无块=按节点顺序），节点带模板公开字段与 `templateContentHash=sha256:`（不含答案/揭晓/商家指引），答案只给 `answerPresent`；`snapshotHash`=排序键规范 JSON 的 sha256；不出 `selectedCover`；带 `sourceSelections` 一律 409 |
| `review/submit` | (member, requestId) 幂等；锁主题与观察任务，重算捕获与任务版本，不一致 409；另有开放单 409；走 `submitReviewBundle`（同 submitTopicBundle 的冻结包 + `submitOrRefreshTopicBundle`）；回执不变量（同任务 version+1 且 0/3，或新任务 version 0/0）不成立整体回滚；同事务冻结捕获与送审包 sha256 |
| `review/status` / `review/current` | 原回执读回（选择器不一致 409、未知 404）；观察=任务当前版本/状态 + 当前内容 hash 是否仍等于送审捕获 |
| `prepare` | `text-city.v1`：任务 status=1、任务快照 sha256 仍是本次送审包、当前内容仍等于送审捕获、有 APPROVE 记录才返回；每章≥1 节点且节点都有答案（否则 422），节点图片不进发行；`manifestHash` 覆盖发行内容，`headRevision`=本主题已分配发行数 |
| `publish` / `status` | (topic, requestId) 幂等；校验 auditTaskVersion/auditSnapshotHash/expectedManifestHash/expectedHeadRevision，发行头 CAS（revision=head+1），同一已批准快照唯一；回执 10 键，`currentlyApproved` 实时（任务仍 status=1 且版本未变） |

请求体按原始字节严格校验（selector 4 KiB、带来源的 prepare/submit 16 KiB、重复键/未知键/非整数 400）；错误码同时写成 HTTP 状态
（App 以 HTTP 401/403/409 分流：403 他人主题、409 审核或内容已变化、422 不属于文本城市发行、404 未知请求）。
新迁移：`migration_20261113_appgaps_approved_topic_release.sql`（`approved_topic_review_submission`、`approved_topic_release`；
台账依赖 `p3_platform_foundation`，`migration_ledger.py audit` problems=[]，迁移安全门/布局门通过）。

验证：单测 `ApprovedTopicReleasesTest`（9）+ `TopicPublication*Test` 回归，117 全绿；真库 IT `ApprovedTopicReleasesMySqlIT`（cy_gaps_b，
送审用真 `AuditTaskMapper` refresh/insert SQL）3 条全绿：开放单送审 version+1 → 重放同回执 → 通过后 prepare/publish/重放/status →
发行头推进后旧准备 409 → 审后改内容 current 不匹配且 prepare 409 → 任务改判后回执 currentlyApproved=false；已关闭任务送审新建 version 0；
内容变化与回执不变量破坏时整体回滚不留行。启动冒烟见下。
启动冒烟（本分支打包，8131 连 cy_gaps_b，Redis db 11，每次随机密钥；脚本 `/data/workspace/be-gaps-b/smoke3.sh` 自建自删
夹具玩家 13900000001 名下的城市版 pro 主题/章/节点/模板与一张开放 TOPIC 任务）：review/sources 空来源；review/prepare 捕获不含答案原文；
review/submit 走**真** `submitReviewBundle`——前两次被真实送审闸拒绝（节点缺地址、模板不是可用版本），409 且任务版本不变（回滚），
补齐夹具后 200：任务 version 0→1、写入 5042 字节 topic_bundle_v2 送审包；重放同回执；status/current 一致（matchesSubmittedCapture=true）；
未通过 prepare 409；通过后 prepare 200 → publish 200（releaseId、auditRecordId、currentlyApproved=true）→ 重放同回执 → status 同回执；
他人 403、重复键请求体 400、未登录 401。冒烟后已停进程并清除夹具行。

复核：notify-level 交 channels 工作线（上表）；其余按原决定不变——professional-* 需 App 先迁 v2 已购线格式（App 任务），
mod-installation/upgrade 为 N/A-v1（无模组目录、无免费模组），creator pending/consent 与 mod-settlement 保持 OFF（售卖/资金），
template/report-receipt N/A（App 无线格式）。

## 剩余（交接）
- channels 工作线：`/api/club/settings/notify-level`（规格见 MAPPING §4）。
- App 任务：professional-* 迁 v2 已购线格式后再补后端物化。
- 已批准发行目前只做不可变分配与回执；把发行绑定到玩家固定版本运行（`PLAYER_PINNED_RUN_ONLY`）与作者封面（`OWNED_TOPIC_COVER_V1`）、
  商家来源确认需要各自的事实源，未建。
- 客服工单目前只有只读面；后台建单/回复写入与工单号生成需客服后台工作线补。
