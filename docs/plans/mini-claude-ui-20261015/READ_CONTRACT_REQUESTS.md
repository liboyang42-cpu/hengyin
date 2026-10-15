# 小程序 → 后端读合同申请汇总（BL-60，2026-10-15）

> 给 Codex / 协调者。本文只整理 Claude 线已提出、尚未落地的**后端读合同**（新只读接口或既有响应补字段），不写后端代码、不改表。
> 原则：只读；归属校验沿用现有服务方法；新增字段可空、旧前端忽略；不下发内部字段。
> 获批并标为 CONTRACT_FROZEN 前，对应小程序 PR 保持 BLOCKED/草稿。

## 总览

| # | 来源 | 需求 | 形式 | 阻塞的 PR |
|---|---|---|---|---|
| R1 | CL6 #35、BL-40 #81/#82 | 官方活动参与方（主办/协办/履约）列表与状态 | 既有详情补字段 **或** 新只读接口（二选一，建议合并） | #35、#81、#82 |
| R2 | BL-38 #79 | 读取本人 V2 草稿完整结构（含 parties） | 新只读接口 | #79 |
| R3 | BL-07 #41 | 勋章墙条目区分俱乐部勋章 | 既有响应补字段 | #41 |
| R4 | BL-46 #87 | 某枚俱乐部勋章已颁发给谁 | 新只读接口 | #87 |
| R5 | BL-50 #92 | 官方活动参与方收件箱分页 | 既有接口补入参/出参 | #92 |

---

## R1 官方活动参与方列表与状态（合并 CL6 + BL-40）

**现状**：`GET /api/official/events/{id}`（`OfficialEventV2ServiceImpl.detail` → `eventBase`）不返回 parties。带 `parties` 的 `draftInternal` / `activity360Internal` 只挂在后台 `BusinessOfficialEventController` 的 `/v2/{id}/draft`、`/v2/{id}/360`（`@PreAuthorize`）。`/api/official/v2/party-inbox` 只列出受邀方本人的邀请，`publish-check` 只有 blockers/risks。`OfficialEventParty` 行本身没有名称/头像。

两条 PR 提出了两种形式，**字段命名不一致**，请 Codex 定一种：

**方案 A（#35）**：在 `GET /api/official/events/{id}` 的 `data` 里补字段
- `parties: [{ id, partyType, partyId, role, status, statusAt, invitedAt, displayName, avatar }]`
  - `id` 是 `official_event_party` 行主键；`partyId` 是实体 `OfficialEventParty.partyId`，即参与方本身的引用 id（按 `partyType` 指向会员/商家/俱乐部），两者不要混用
- `viewerCanManage: boolean` = `canManage(eventId, memberId)`
- 可见性：非管理者只返回 `status ∈ {ACCEPTED, ACTIVE, COMPLETED}` 的行；管理者（`canManage` 为真，或 R9-24 规定的 COMPLETED 状态下只读的主办/协办）返回全部行
- 不下发：`responsibilitySummary / contactRole / snapshotJson / statusReason / organizerAuthority`
- `displayName/avatar` 来源：OFFICIAL 取 `ums_member` 昵称/头像，MERCHANT 取 `mms_merchant` 名称/logo，CLUB 取 `club` 名称/logo（关联方式参照 `selectPartyInboxForMember`）；取不到返回 null
- 附带建议：增加 `acceptedAt`（只在 ACCEPTED 时写入）。原因是 `activateAcceptedParties` 开播时会把 ACCEPTED 改成 ACTIVE 并刷新 `status_at`，按接受先后排序会失效
- 建议 fixture：event7 有主办 101 ACTIVE、协办 102 ACCEPTED、103 INVITED、104 DECLINED。普通用户只看到 101、102；member101 看到 4 行，且 `viewerCanManage=true`

**方案 B（#81/#82）**：新增 `GET /api/official/v2/events/{id}/parties`
- 鉴权：`canReadControlledEvent(id, uid)`，与 `publish-check` 一致
- 返回：`[{ partyId, role, status, partyType, partyRefId, displayName, invitedAt, respondedAt }]`（#82 版本另含 `statusAt, statusReason`）
- ⚠️ #81 中的 `partyRefId / respondedAt` 在 `OfficialEventParty` 实体里不存在（实体字段为 `id / partyId / status / statusAt / statusReason / invitedAt`）。BL-40 以 #82 为准（#81 是重复项，按监督意见关闭）；若采用方案 B，请用实体真名 `partyId / statusAt`

**建议**：采用方案 A 的字段名（`id / partyId / statusAt`），因为前端视图模型已经在 #35 写好。主办面板需要看到 INVITED/DECLINED，用 `viewerCanManage` 分级即可，不需要第二个接口。若出于缓存或公开详情性能考虑要拆接口，则用方案 B 的路径加方案 A 的字段名。`statusReason` 只对管理者下发。

## R2 V2 草稿读取（BL-38）

**现状**：`POST /api/official/v2/drafts`（`ApiOfficialEventController.java:260`，`OfficialEventV2DraftRequest`）是**全量覆盖**，小程序侧没有 V2 草稿读接口，只有后台 `/business/officialevent/v2/{id}/draft`。前端如果只提交 parties，bindings、missions 等其余列表会被清空。

**申请**：`GET /api/official/v2/drafts/{id}`（路径由 Codex 定）
- 鉴权：`canReadControlledEvent(id, uid)` / `draftForOperator` 的同一校验
- 返回：与 `draftInternal` 相同，即 `event, bindings, missions, parties, fulfillmentRequirements`；parties 不下发内部字段的规则同 R1
- 备选（属于写合同，不在本文范围）：「仅增删 parties」的专属写接口

## R3 勋章墙俱乐部字段（BL-07）

**现状**：`/api/badge/wall-v2`（`BadgeReadModelServiceImpl.buildWallV2`）返回的 `BadgeWallItemVO` 没有俱乐部字段。`selectActiveClubByCodes` 查出的俱乐部勋章按 `asset_type` 混在各层里，按 badgeCode 前缀（`CLUB{id}_`）去猜就等于编造字段。

**申请**：`BadgeWallItemVO` 增加 `clubId`（Long，可空，取自 `GrowthBadge.clubId`）和 `clubName`（String，可空，关联 `club`）。非俱乐部勋章两项都为 null。

## R4 俱乐部勋章持有人（BL-46）

**现状**：`player_badge` 只能按会员读取（`selectListByMember` / `selectOne`）。`ApiClubController` `/badge/grant` 只调 `countByMemberAndCode` 去重，没有按 `badgeCode` 列出持有人的接口。

**申请**：`POST /api/club/badge/holders`，body `{ clubId, badgeCode }`
- 鉴权：与 `/badge/grant` 一致（`OwnershipUtils.notOwner` 判主理人），并校验勋章属于本俱乐部
- 返回：`[{ memberId, nickname, avatar, grantTime }]`，按颁发时间倒序

## R5 参与方收件箱分页（BL-50）

**现状**：`GET /api/official/v2/party-inbox`（`ApiOfficialEventController.java:174`）没有入参，`OfficialEventV2ServiceImpl.partyInbox` 经 `selectPartyInboxForMember` 一次返回本人全部邀请。小程序无法分页，也无法显示「到底了」。

**申请**：同一路径补分页
- 入参：`pageNum / pageSize`（或 `cursor / limit`，二选一，由 Codex 定）；不传时保持现状全量返回，兼容旧前端
- 出参：在列表外包一层 `{ items, total }`（或 `{ items, nextCursor, hasMore }`）；若担心破坏旧前端，可以用新增参数触发新形状
- 鉴权不变：仍只返回当前登录会员本人的邀请
- 阻塞：#92（BL-50）

---

## 不属于读合同、需要用户/协调者决定的事项（仅列出）
- **BL-25 #61**：俱乐部「无权限」态与 `cy-state-shell` 的 `AUTO_BACK_KINDS` 冲突，需二选一：停在空态页，或自动返回（要改共享组件）
- **BL-31 #71 / BL-35 #76 / BL-44 #85**：分别等 CL2 #25、CL4 #27 合并，没有接口需求
- **BL-42 #84**：「收藏的据点」页的入口放在哪里（现有 UI 不动的前提下），以及 `app.json` 注册
- **CL4 #27「新增协办方」（已排期活动）**：需要 Claude 专属写接口，合同申请尚未提交（写合同，不在本文范围）
- **CL5 门店认领材料**：依赖 claims 的 `proofType` / `assetId`（CLAIM-MATERIAL 仍是 DRAFT）
- **#35**：首屏「城瘾官方 · 发起」是否换成组件的 `mode="row"`；可选的独立「联合主办」页要改 `app.json`
