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
| `/api/club/group-code`、`/group-code/save` | DEFERRED | 后端无入群码模型，App 只有页面名、无线格式 |
| `/api/club/event-ops/series/original-result` | DEFERRED | 需要 series/create 生产者落 requestFingerprint/receiptHash 与完整初始结果，现有 createSeries 不产生 |
| `/api/club/settings/notify-level` | DEFERRED | 依赖 IM 通知偏好与 notification-requests 回执（messaging 工作线） |
| `/api/club-compensation/quality/evidence` | OFF | 俱乐部主理人报酬链路（club-host money）按要求不开 |
| `/api/join-request/cancel`、`/api/space/join-pause` | DEFERRED | 频道平台入会治理族，无后端领域（MAPPING §4） |
| `/api/club/channels`、`/api/guilds/{}/{}/channels`、`/channels/manage` | N/A | `feature/im-channels` 承接 |

### workshop/mods（20）
| 路径 | 状态 | 说明 |
|---|---|---|
| `/api/workshop/owned`、`/owned/list`、`/owned/detail`、`/owned/package` | SERVED | 现网无免费领取事实源，如实 `NOT_ENABLED` + 空集（不把付费许可冒充免费领取） |
| `/api/mod-commerce` | OFF | 购买/支付/退款族一律 503 `WORKSHOP_COMMERCE_DISABLED`，不读请求体、不建单 |
| `/api/workshop/purchased`、`/purchased/install` | MAPPED | → `/api/workshop/v2/purchased/*`（后端对 v1 刻意 409 升级） |
| `/api/mod-installation`、`/api/mod-upgrade`、`/api/mod-owned-report/licenses`、`/api/mod-settlement/mine`、`/mine/income` | DEFERRED（显式 503） | 后端无对应领域；返回 503 `WORKSHOP_FEATURE_NOT_ENABLED`，App 落 disabled |
| `/api/workshop/creator/pending-packages`、`/public-theme-use` | DEFERRED（显式 503） | 同上 |
| `/api/workshop/purchased/professional-operations/*`(4)、`/professional-targets/*`(2) | DEFERRED | 依赖付费许可（购买关闭）与专业运营领域 |

### creator/publishing（12）
| `/api/project/state-requests/context|submit|read` | SERVED | 基线摘要来自 `/api/project/my` 同一行；submit 前重算基线，变化即拒绝；模板上下架把 App `publish_status` 映射为现有 `status`(1/3) |
| `/api/content-draft` | MAPPED | 前缀 |
| `/api/approved-topic-release/v1/*`(8) | DEFERRED | 需要「已审快照→发布分配」新领域（manifest/审核快照哈希），后端无生产者 |

### activities（3） / templates（3）
| `/api/activity/create-requests/submit|read|resume-post-processing` | SERVED | original-activity-create-request-v1，原写 `/api/activity/publish`；无后置异步处理，resume=read |
| `/api/template/author-requests/submit|read` | SERVED | original-template-author-request-v1，原写 `/api/template/draft|publish` |
| `/api/template/report-receipt` | DEFERRED | App 只有常量、无响应线格式 |

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
| `/api/coupon/command-receipt` | DEFERRED | 需在 `/api/coupon/publish|stop` 上实现 CouponCommandService v1 生产者（二进制规范哈希 + QUOTA_REJECTED 等原因码 + 执行时券状态），本轮未做 |
| `/api/invoice` | MAPPED | 前缀，全部子路由已在 |
| `/api/pay/create` | OFF | 虚拟商品支付按策略关闭（App 侧恒阻断），后端不开 |
| `/api/asset/withdraw` | OFF | 钱包提现关闭（wallet read 已有 `/api/user/income*`） |
| `/api/registration/original-event` | DEFERRED | 无任何写入 ORDER/RUN 目标的审计事件生产者 |
| `/api/team/original-request` | DEFERRED | 依赖好友组队邀请（social/friends） |
| `/api/invite/revoke` | DEFERRED | 频道平台邀请治理族 |
| `/api/privacy/progress` | DEFERRED | 频道进度榜隐私开关（feature/im-channels 领域） |
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

## 剩余（交接）
- DEFERRED 项按上表原因；最值得接着做的是 `/api/coupon/command-receipt`（生产者规格在 App `Core/CouponCommandReceipts.swift`）与
  `/api/club/event-ops/series/original-result`（需改 createSeries 落原始结果）。
- 客服工单目前只有只读面；后台建单/回复写入与工单号生成需客服后台工作线补。
