# App gaps B — 路由映射（给 App 侧）

来源：App 分支 `ui/capgraph`（c2202dbe3）`docs/release/capability-graph.md` 第 (2) 节 APP-NOT-IN-BACKEND 与 `capability-graph.csv`。
后端基线：hengyin `integration/backend-app-20261112`（c96d345ff），本分支 `feature/app-gaps-b`。

本文只列**后端已有等价路由、不应重复实现**的条目：App 侧应改调右列路由（或已经在调，CSV 只是把前缀/文档名误判成了缺口）。
本分支新实现的路由见 `RESULT.md`。

## 1. 前缀误判（App 拼接子路径，子路径后端都在）

| App 路径（CSV） | 实际调用 | 后端路由 |
|---|---|---|
| `/api/club/lead` | `api/club/lead/` + `start/arrive/broadcast/release-station/settle/unlock-chapter`、`edit-ops`、`team-progress` | `ApiClubLeadController` 同名子路由 |
| `/api/club/post` (POST) | `api/club/post/` + `create/update/delete/list/feed/like/pin/report/repost/history/comment/*` | `ApiClubController` `/api/club/post/*` |
| `/api/club/roles` (POST) | `api/club/roles/list|assign|revoke` | `ApiClubRoleController` |
| `/api/v1/community` (GET/PATCH) | `api/v1/community/` 下全部子路由 | `ApiCommunityPostController` |
| `/api/coupon` | `api/coupon/publish|stop|status|...` | `ApiCouponController`；`/api/coupon/command-receipt` 见 RESULT |
| `/api/invoice` | `api/invoice/titles|titles/save|titles/delete|orders/eligible|apply|list|detail|cancel|request|titles/save-request|titles/request` | `ApiUserInvoiceController`（全部已在） |
| `/api/marketing/product` | `api/marketing/product/{id}`（GET）、`/orders`、`/list` | `ApiMarketingProductController` |
| `/api/workshop/owned` | `api/workshop/owned/list|detail` | 本分支新增（RESULT） |
| `/api/workshop/purchased` | `api/workshop/purchased/list|detail` | 见第 2 节 |

## 2. 等价路由在另一路径

| App 路径 | 后端等价 | 说明 |
|---|---|---|
| `/api/ugc/block` | `POST /api/im/block`（form `target_member_id`） | App `RePoint10Routes` 已重定向 |
| `/api/workshop/purchased/list|detail` | `POST /api/workshop/v2/purchased/list|detail` | 后端对 v1 路径刻意回 `409 WORKSHOP_CONTRACT_UPGRADE_REQUIRED`；App 须迁到 v2 线格式（`license_id`、`before_order_line_id`） |
| `/api/workshop/purchased/install`（targets/submit/status/history）、`installed-text/detail` | `POST /api/workshop/v2/purchased/install/targets|submit|status|history`、`/v2/purchased/installed-text/detail` | 同上 |
| `/api/mod-commerce/licenses*`（只读部分） | `POST /api/workshop/v2/purchased/list|detail` | `mod-commerce` 族其余全部是购买/支付/退款，按策略关闭（RESULT：503） |

## 3. 俱乐部：小程序页面名（App 只做文档、不发请求）

`Core/ChannelDiscordMessaging.swift` 的 `ClubTabAndAdminParityCatalog.nativeOnlyEndpoints` 是“小程序页面里出现、后端没有的名字”，注释写明 *nothing may dispatch these*。等价能力如下：

| 小程序名 | 后端等价 |
|---|---|
| `/api/club/join-requests/apply` | `POST /api/club/join`（`{id, joinMessage}`；需审核团返回 `state=pending`）；带原始回执头时见 RESULT `admission/receipt` |
| `/api/club/activity/join` / `cancel` | `POST /api/club/event-ops/waitlist/join|cancel`（候补）、报名走 `/api/registration/*` |
| `/api/club/activity/detail` | `POST /api/club/event-ops/occurrence/status`、`/api/activity/info` |
| `/api/club/events` | `POST /api/club/event-ops/series/list`、`/series/occurrences` |
| `/api/club/events/save` | `POST /api/club/event-ops/series/create`、`/series/update-future` |
| `/api/club/events/Checkin` | `POST /api/club/event-ops/attendance/correct`、核销 `/api/verify/groupcode/issue|redeem` |
| `/api/club/notices` / `notices/send` | 公告=置顶动态：`/api/club/post/list` + `/api/club/post/pin`；活动通知：`/api/club/event-notification/preview|send|status` |
| `/api/club/workbench` | `POST /api/club/detail` + `/api/club/recruit/overview` + `/api/club/crm/customers/count` |
| `/api/club/settlements/export` | 只读对账 `POST /api/club/settlement/mine|summary`（主理人资金导出不开，见 RESULT） |
| `/api/join-request/approve|reject`（俱乐部类空间） | `POST /api/club/join-request/approve|reject` |
