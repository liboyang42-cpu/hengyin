# Backend Authorization (IDOR) Audit Report — `integration/backend-app-r2`

## 1. Executive Summary

- **Base Branch**: `origin/integration/backend-app-r2` (`73f929b30`)
- **Audit Branch / Commit**: `audit/idor-r2` (`716ca8b87`, rebased cleanly on `73f929b30`)
- **Scope**: `1335` capgraph rows (`1238` non-registry, non-fixture player app routes), `1521` `/api/**` controller mappings across `191` API controllers, `53` `SecurityConfig` `permitAll`/pattern references, and `125` business/monitor/tool/system admin controllers (`758` mappings).
- **Findings Count**: **2 confirmed authorization/privacy gaps**, both **fixed** and covered by regression unit tests in `ApiClubMerchantsAndBadgeAuthzTest`. Zero remaining unguarded cross-user IDORs, merchant/club-host role bypasses, or missing admin `@PreAuthorize` guards identified.

## 2. Confirmed Findings & Remediations

### Finding 1 — `POST /api/club/merchants`: Missing Caller Check, Dynamic SQL Filter Oracle & Raw Entity PII Exposure
- **Location**: `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiClubController.java` (`merchants`, lines `1469-1495`)
- **Issue**: Accepted an arbitrary `@RequestBody MmsMerchant`, did not call `currentMemberId()`, omitted `accountStatus = 1L`, and passed raw entity fields (`memberId`, `phone`, `wechat`, `reson`, `address`, etc.) straight to `selectMmsMerchantList` and returned raw `MmsMerchant` entities (including `wechat`, `wechatQrcode`, `createBy`, `updateBy`, `remark`). Any authenticated player could oracle `memberId`/PII attributes and extract private merchant fields.
- **Live Probe Confirmation (pre-fix on TEST `8120`)**: Authenticated non-merchant player `9004` sent `POST /api/club/merchants` with `{"memberId": 9002}` -> received HTTP `200`, `code=200`, filtered solely to merchant owned by `9002` with raw `MmsMerchant` entity fields (`wechat`, `wechatQrcode`, `createBy`, `updateBy`, `remark`).
- **Fix**: Enforced `currentMemberId()` at entry, built a fresh `MmsMerchant` query object whitelisting only `{name, categoryId, tags, cityRole}`, forced `coopOpen = 1`, `status = 1L`, `accountStatus = 1L`, `delFlag = 0`, and projected results through `PublicMerchantDiscoveryVO.from(m)`.
- **Regression Tests**: `ApiClubMerchantsAndBadgeAuthzTest.merchantsRequiresAuthentication`, `ApiClubMerchantsAndBadgeAuthzTest.merchantsStripsPrivateQueryCriteriaAndSensitiveFields`.

### Finding 2 — `POST /api/club/badge/list`: Missing Private & Unapproved Club Visibility Gate
- **Location**: `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiClubController.java` (`badgeList`, lines `1578-1591`)
- **Issue**: Loaded club by `clubId` and listed badges after checking `club != null` without calling `canViewClub(club)` or verifying `status == 1L` / owner membership, allowing any authenticated caller with a guessed `clubId` to enumerate badges of private (`publicVisible = 0`) or unapproved (`status != 1`) clubs.
- **Fix**: Enforced `currentMemberId()`, added null-body handling, `!canViewClub(club)` gate (`"这个俱乐部只对成员开放"`), and `!Long.valueOf(1L).equals(club.getStatus()) && !OwnershipUtils.isOwner(club.getMemberId(), viewer)` gate (`"俱乐部不存在"`).
- **Regression Tests**: `ApiClubMerchantsAndBadgeAuthzTest.badgeListRejectsUnauthenticatedAndNonMemberOfPrivateClub`, `ApiClubMerchantsAndBadgeAuthzTest.badgeListRejectsUnapprovedClubForNonOwner`, `ApiClubMerchantsAndBadgeAuthzTest.badgeListAllowsActiveMemberOfPrivateClub`.

## 3. Role, Admin & Public Route Controls

### 3.1 Player, Merchant, Club-Host & Channel Controls
- **Player resource ownership**: Player-scoped mutations and private reads derive caller identity from `currentMemberId()` / `SecurityUtils` and validate resource ownership via `OwnershipUtils`, SQL `member_id = #{memberId}`, or domain guards (`ApiRegistrationController`, `ApiTopicController`, `ApiTemplateController`, `ApiUserInvoiceController`, `ApiWithdrawalController`, `ApiWorkshopController`, `ApiExternalGameController`, `LocationShareService`, `TopicCoverService`, `AttributeAuthorService`, `ApprovedTopicReleases`, `SupportTicketReads`).
- **Merchant role**: Merchant management endpoints (`ApiMerchantController`, `ApiMerchantAuthoringController`, `ApiMerchantCrmController`, `ApiMerchantDeskController`, `ApiMerchantOperatorController`, `ApiMerchantNodeController`, `ApiMerchantAftercareController`, `ApiMerchantChapterApplicationController`, `ApiMerchantGapController`, `ApiMerchantPayoutAccountController`) gate through `MerchantAccessService` (operator/owner permission matrix) and scope records to `access.getMerchantId()`.
- **Club host / officer / member role**: Club endpoints (`ApiClubController`, `ApiClubEventOpsController`, `ApiClubCrmController`, `ApiClubRoleController`, `ApiChannelPlatformController`, `ApiChannelMessageController`) enforce `ClubAccessService`, `ChannelGuildAccess.assertGuildAccess(...)`, and `canViewClub(...)`.
- **Workshop & Rewards (`r2`)**: `WorkshopService` enforces `owner` checks on author submissions/licenses/withdrawals/installs and `WorkshopVersionReview.requireApproved` on public offers. Non-cash reward claims/pledges (`ApiRewardPledgeController`, `ApiNonCashRewardController`) validate caller/recipient ownership and host role.

### 3.2 Admin `@PreAuthorize` Audit (`125` Admin Controllers)
- Audited `125` controllers across `business/`, `monitor/`, `tool/`, and `system/` (`758` total mappings).
- **`749` / `758`** mappings enforce explicit `@PreAuthorize("@ss.hasPermi(...)")`.
- The remaining **`9`** mappings are exclusively RuoYi framework auth/profile/index endpoints: `system/SysIndexController.java:24, system/SysLoginController.java:56, system/SysLoginController.java:72, system/SysLoginController.java:100, system/SysProfileController.java:47, system/SysProfileController.java:67, system/SysProfileController.java:97, system/SysProfileController.java:129, system/SysRegisterController.java:28`.
- Live probe of App JWT (`cy_e2e_r2` player token) against `/business/**`, `/monitor/**`, and `/system/**` admin endpoints rejected cleanly with HTTP `401` (Spring Security context separation).

### 3.3 Public / `@Anonymous` / `SecurityConfig.permitAll()` Audit
- Inspected `SecurityConfig.java` (`permitAll()` / anonymous matchers) and all `@Anonymous` annotations:
  - Public routes are restricted to login/auth (`ApiLoginController`, `ApiUsAppleAuthController`), static public catalog/dict/config/preset/product/referral-landing (`ApiPublicContentCatalogController`, `ApiProductController`, `ApiReferralLandingController`, `ApiPresetController`), community capabilities/guidelines, public weather/service-status, and deregister/share snapshot tokens.
  - Confirmed `/api/common/upload*` explicitly requires `.authenticated()`.
  - Unauthenticated sweep across `1102` testable app endpoints returned `401` on `1048` routes; the `13` `(200, 200)` responses on non-`@Anonymous` paths were verified to be intentional `permitAll()` catalog/config/referral-landing/community-guidelines endpoints (and pre-fix `Finding 1` was blocked by Spring Security `401` when unauthenticated).

## 4. Live TEST (`http://127.0.0.1:8120`, `cy_e2e_r2`) Cross-User Probe Summary

- **Sweep**: `1102` routes probed unauthenticated -> `1048` HTTP `401`, `13` public `200/200`, `41` `400/401/410/500` parameter-rejected.
- **Targeted cross-user & role-matrix checks**: Executed `55 + 48 + 17` adversarial cases using seeded players (`9001`/`9004` vs `9009`), merchants (`9002`, `9005`), and club hosts (`9003`, `9008`):
  - Confirmed cross-player denial on registration (`info`, `cancel` -> `500`), IM (`messages` -> `403`), club channels/settings (`403`), club ops (`roles/list`, `join-requests`, `badge/create`, `topic-registrations`, `crm`, `event-ops` -> `403`/`500`), merchant ops (`403`; `merchant/update` uses caller's own `access.getMerchantId()`), topic/template editing & delete (`403`/`500`), community post edit (`403`), external-game submission (`404 SUBMISSION_NOT_FOUND` for non-owner `9009`), and admin routes (`401` with App JWT).
  - Reproduced Finding 1 (`POST /api/club/merchants` `memberId=9002` oracle + raw `MmsMerchant` PII) live on `8120` before applying the fix.

## 5. Verification Results (Post-Fix & Post-Rebase `716ca8b87`)

- **Regression Authz Unit Test (`ApiClubMerchantsAndBadgeAuthzTest`)**: `PASS` (`BUILD SUCCESS`) — Tests run: `5`, Failures: `0`, Errors: `0`, Skipped: `0`
- **Full Surefire Suite (without MyBatis port env, `chengyinhub-admin` / reactor)**: Tests run: `19647`, Failures: `0`, Errors: `34` (all `34` errors exclusively in the `14` `*MyBatisTest` MySQL classes when `CHENGYIN_MYBATIS_IT_PORT=3330` is omitted; `19,613` non-MyBatis unit tests passed with `0` failures / `0` errors), Skipped: `709`
- **MyBatis SQL Integration Suite (`14` `*MyBatisTest` classes, MySQL `:3330` `_r6`)**: `PASS` (`BUILD SUCCESS`) — Tests run: `39`, Failures: `0`, Errors: `0`, Skipped: `0`
- **Real-MySQL Integration Suite (`28` IT classes, `cy_gaps_b_r2`)**: `PASS` (`BUILD SUCCESS`) — Tests run: `115`, Failures: `0`, Errors: `0`, Skipped: `0`
- **MapPublicLayers Integration Suite (`MapPublicLayersMySqlIT`, `cy_gaps_b_r2`)**: `PASS` (`BUILD SUCCESS`) — Tests run: `1`, Failures: `0`, Errors: `0`, Skipped: `0`
- **CI Migration Gates (`ci/migration-layout-check.sh`, `ci/migration-safety-check.sh`)**: `PASS` (exit `0`)

## 6. Route Inventory (`route -> check -> probe result`)

### 6.1 Capgraph Non-Registry / Non-Fixture App Routes (`1238` routes)

| # | Route | Verbs | Static Authz / Role Check | Live Probe (`TEST 8120`) |
|---|---|---|---|---|
| 1 | `/api/activity/cancel` | `DELETE` | `ApiActivityController.activityCancel()` (L585): MerchantAccessService | Anon sweep: 401 |
| 2 | `/api/activity/cancel_preview` | `-` | `ApiActivityController.activityCancelPreview()` (L652): MerchantAccessService | Anon sweep: 401 |
| 3 | `/api/activity/create-requests/read` | `-` | `appgaps/ApiAppGapsOriginalRequestController.activityRead()` (L67): JWT authenticated | Anon sweep: 401 |
| 4 | `/api/activity/create-requests/resume-post-processing` | `-` | `appgaps/ApiAppGapsOriginalRequestController.activityRead()` (L67): JWT authenticated | Anon sweep: 401 |
| 5 | `/api/activity/create-requests/submit` | `-` | `appgaps/ApiAppGapsOriginalRequestController.activitySubmit()` (L53): JWT authenticated | Anon sweep: 401 |
| 6 | `/api/activity/delete` | `DELETE` | `ApiActivityController.activityDelete()` (L508): OwnershipUtils/owner check + JWT currentMemberId() scope | Targeted: pA->200/500 (仅俱乐部主理人或商家可删除活动) / Anon sweep: 401 |
| 7 | `/api/activity/info` | `POST` | `ApiActivityController.activityInfo()` (L1320): OwnershipUtils/owner check + JWT currentMemberId() scope | Anon sweep: 200/500 (参数不合规) |
| 8 | `/api/activity/list` | `-` | `ApiActivityController.activityList()` (L191): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 200/500 (Request method 'GET' is not support) |
| 9 | `/api/activity/publish` | `-` | `ApiActivityController.publish()` (L853): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 10 | `/api/activity/publish_precheck` | `POST` | `ApiActivityController.publishPrecheck()` (L819): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 11 | `/api/activity/update` | `POST` | `ApiActivityController.update()` (L957): OwnershipUtils/owner check + JWT currentMemberId() scope | Anon sweep: 401 |
| 12 | `/api/activity/update_publish_status` | `-` | `ApiActivityController.activityUpdatePublishStatus()` (L689): OwnershipUtils/owner check + JWT currentMemberId() scope | Anon sweep: 401 |
| 13 | `/api/agreement/get` | `-` | `ApiAgreementController.get()` (L39): @Anonymous (public endpoint; parameter/entity scoped where applicable) | Anon sweep: 200/500 (协议类型不合法) |
| 14 | `/api/ai/club/design` | `-` | `ApiAiController.clubDesign()` (L461): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 15 | `/api/ai/merchant/insight` | `-` | `ApiAiController.merchantInsight()` (L425): MerchantAccessService | Anon sweep: 401 |
| 16 | `/api/ai/npc/chat` | `POST` | `ApiAiNpcController.chat()` (L183): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 17 | `/api/ai/npc/chat/data/delete` | `-` | `ApiAiNpcController.deleteChatData()` (L481): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 18 | `/api/ai/npc/chat/data/export` | `-` | `ApiAiNpcController.exportChatData()` (L470): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 19 | `/api/ai/npc/chat/request` | `GET` | `ApiAiNpcController.chatRequest()` (L210): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 20 | `/api/ai/npc/event` | `GET` | `ApiAiNpcController.event()` (L145): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 21 | `/api/ai/npc/merchant-chat` | `POST` | `ApiAiNpcController.merchantChat()` (L446): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 22 | `/api/ai/npc/profile` | `GET` | `ApiAiNpcController.profile()` (L98): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 23 | `/api/ai/npc/shop-chat` | `POST` | `ApiAiNpcController.shopChat()` (L240): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 24 | `/api/ai/npc/voice-chat` | `POST` | `ApiAiNpcController.voiceChat()` (L312): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 25 | `/api/ai/safety/precheck` | `-` | `ApiAiController.safetyPrecheck()` (L385): MerchantAccessService | Anon sweep: 401 |
| 26 | `/api/ai/template/fill` | `-` | `ApiAiController.templateFill()` (L325): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 27 | `/api/ai/theme/draft` | `GET,POST` | `ApiAiController.themeDraft()` (L164): JWT authenticated | Anon sweep: 401 |
| 28 | `/api/ai/theme/draft/execute` | `POST` | `ApiAiController.themeDraftExecute()` (L189): JWT authenticated | Anon sweep: 401 |
| 29 | `/api/ai/theme/draft/prepare` | `POST` | `ApiAiController.themeDraftPrepare()` (L173): JWT authenticated | Anon sweep: 401 |
| 30 | `/api/ai/theme/draft/quota` | `POST` | `ApiAiController.themeDraftQuota()` (L286): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 31 | `/api/ai/theme/draft/recovery` | `GET` | `ApiAiController.themeDraftRecovery()` (L238): JWT authenticated | Anon sweep: 401 |
| 32 | `/api/ai/theme/draft/request` | `GET` | `ApiAiController.themeDraftRequest()` (L217): JWT authenticated | Anon sweep: 401 |
| 33 | `/api/analytics/events/batch` | `POST` | `ApiAnalyticsController.recordBatch()` (L128): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 34 | `/api/approved-topic-release/v1/prepare` | `POST` | `appgaps/ApiAppGapsApprovedTopicReleaseController.prepare()` (L54): JWT authenticated | Anon sweep: 401 |
| 35 | `/api/approved-topic-release/v1/publish` | `POST` | `appgaps/ApiAppGapsApprovedTopicReleaseController.publish()` (L59): JWT authenticated | Anon sweep: 401 |
| 36 | `/api/approved-topic-release/v1/review/current` | `-` | `appgaps/ApiAppGapsApprovedTopicReleaseController.reviewCurrent()` (L49): JWT authenticated | Anon sweep: 401 |
| 37 | `/api/approved-topic-release/v1/review/prepare` | `-` | `appgaps/ApiAppGapsApprovedTopicReleaseController.reviewPrepare()` (L34): JWT authenticated | Anon sweep: 401 |
| 38 | `/api/approved-topic-release/v1/review/sources` | `-` | `appgaps/ApiAppGapsApprovedTopicReleaseController.reviewSources()` (L29): JWT authenticated | Anon sweep: 401 |
| 39 | `/api/approved-topic-release/v1/review/status` | `-` | `appgaps/ApiAppGapsApprovedTopicReleaseController.reviewStatus()` (L44): JWT authenticated | Anon sweep: 401 |
| 40 | `/api/approved-topic-release/v1/review/submit` | `-` | `appgaps/ApiAppGapsApprovedTopicReleaseController.reviewSubmit()` (L39): JWT authenticated | Anon sweep: 401 |
| 41 | `/api/approved-topic-release/v1/status` | `POST` | `appgaps/ApiAppGapsApprovedTopicReleaseController.status()` (L64): JWT authenticated | Anon sweep: 401 |
| 42 | `/api/asset/withdraw` | `-` | `ApiMiscGapController.assetWithdraw()` (L112): JWT authenticated | Anon sweep: 401 |
| 43 | `/api/audit/appeal` | `GET,POST` | `ApiAuditController.appeal()` (L52): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 44 | `/api/audit/appeal/receipt` | `GET` | `ApiAuditController.appealReceipt()` (L82): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 45 | `/api/audit/guard/appeal` | `GET,POST` | `ApiAuditController.guardAppeal()` (L106): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 46 | `/api/audit/guard/appeal/receipt` | `GET` | `ApiAuditController.guardAppealReceipt()` (L119): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 47 | `/api/audit/guard/reference` | `GET` | `ApiAuditController.guardReference()` (L95): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 48 | `/api/audit/timeline` | `GET` | `ApiAuditController.timeline()` (L130): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 49 | `/api/audit/{}/detail` | `-` | `ApiAuditController.detail()` (L31): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 50 | `/api/author-write/receipt` | `GET` | `ApiAuthorWriteReceiptController.receipt()` (L16): JWT authenticated | Anon sweep: 401 |
| 51 | `/api/badge/wall-v2` | `-` | `ApiBadgeController.wallV2()` (L27): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 52 | `/api/balance/list` | `POST` | `ApiBalanceController.myBalanceList()` (L36): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 53 | `/api/capsules/create` | `-` | `CapsuleController.create()` (L14): JWT authenticated | Anon sweep: 401 |
| 54 | `/api/capsules/delete` | `-` | `CapsuleController.delete()` (L26): JWT authenticated | Anon sweep: 401 |
| 55 | `/api/capsules/detail` | `-` | `CapsuleController.detail()` (L25): JWT authenticated | Anon sweep: 401 |
| 56 | `/api/capsules/list` | `-` | `CapsuleController.list()` (L23): JWT authenticated | Anon sweep: 401 |
| 57 | `/api/capsules/receipt` | `-` | `CapsuleController.receipt()` (L24): JWT authenticated | Anon sweep: 401 |
| 58 | `/api/cart/cart/add` | `-` | `ApiCartController.cartAdd()` (L75): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 59 | `/api/cart/delete` | `-` | `ApiCartController.cartDelete()` (L60): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 60 | `/api/cart/list` | `POST` | `ApiCartController.getCartList()` (L39): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 61 | `/api/cart/order/settlement` | `-` | `ApiCartController.orderSettlement()` (L124): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 62 | `/api/cart/settlement` | `POST` | `ApiCartController.cartSettlement()` (L108): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 63 | `/api/cart/update` | `-` | `ApiCartController.cartUpdate()` (L92): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 64 | `/api/category/list` | `POST` | `ApiCategoryController.categoryList()` (L36): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 200/200 (操作成功) |
| 65 | `/api/channel-platform/admission/requests` | `GET` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 66 | `/api/channel-platform/admission/requests/{}` | `GET` | `ApiChannelAdmissionController.receipt()` (L78): JWT authenticated | Covered by controller/JWT default deny + static review |
| 67 | `/api/channel-platform/governance/moderation/requests` | `GET` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 68 | `/api/channel-platform/governance/moderation/requests/{}` | `GET` | `ApiChannelGovernanceController.moderationReceipt()` (L71): JWT authenticated | Covered by controller/JWT default deny + static review |
| 69 | `/api/channel-platform/governance/reports/requests` | `GET` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 70 | `/api/channel-platform/governance/reports/requests/{}` | `GET` | `ApiChannelGovernanceController.reportReceipt()` (L132): JWT authenticated | Covered by controller/JWT default deny + static review |
| 71 | `/api/channel-platform/governance/requests` | `GET` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 72 | `/api/channel-platform/governance/requests/{}` | `GET` | `ApiChannelPlatformController.governanceReceipt()` (L176): JWT authenticated | Covered by controller/JWT default deny + static review |
| 73 | `/api/channel-platform/requests` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 74 | `/api/channel-platform/requests/{}` | `GET` | `ApiChannelPlatformController.metadataReceipt()` (L169): JWT authenticated | Covered by controller/JWT default deny + static review |
| 75 | `/api/channel/redeem` | `POST` | `ApiChannelRedemptionController.redeem()` (L31): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 76 | `/api/channel/{}/board` | `GET` | `ApiChannelProgressController.boards()` (L31): JWT authenticated; `ApiChannelProgressController.bind()` (L38): JWT authenticated | Client prefix literal (covered by child routes) |
| 77 | `/api/channel/{}/board/{}` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Covered by controller/JWT default deny + static review |
| 78 | `/api/channel/{}/board/{}/version` | `GET` | `ApiChannelProgressController.version()` (L98): JWT authenticated | Covered by controller/JWT default deny + static review |
| 79 | `/api/channel/{}/progress/overview` | `-` | `ApiChannelProgressController.overview()` (L46): JWT authenticated | Covered by controller/JWT default deny + static review |
| 80 | `/api/channel/{}/progress/privacy` | `-` | `ApiProgressPrivacyController.channel()` (L49): JWT authenticated; `ApiProgressPrivacyController.saveChannel()` (L56): JWT authenticated | Covered by controller/JWT default deny + static review |
| 81 | `/api/channels` | `GET` | `ApiChannelGapController.channels()` (L54): JWT authenticated | Anon sweep: 401 |
| 82 | `/api/channels/member-state-requests` | `GET` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 83 | `/api/channels/{}` | `DELETE,PATCH` | `ApiChannelPlatformController.detail()` (L67): JWT authenticated; `ApiChannelPlatformController.edit()` (L74): JWT authenticated (+1 more mappings) | Client prefix literal (covered by child routes) |
| 84 | `/api/channels/{}/archive` | `POST` | `ApiChannelPlatformController.archive()` (L90): JWT authenticated | Covered by controller/JWT default deny + static review |
| 85 | `/api/channels/{}/effective/{}` | `-` | `ApiChannelPlatformController.effective()` (L133): JWT authenticated | Covered by controller/JWT default deny + static review |
| 86 | `/api/channels/{}/members` | `-` | `ApiChannelPlatformController.members()` (L151): JWT authenticated | Covered by controller/JWT default deny + static review |
| 87 | `/api/channels/{}/op-log` | `GET` | `ApiChannelPlatformController.opLog()` (L141): JWT authenticated | Covered by controller/JWT default deny + static review |
| 88 | `/api/channels/{}/permissions` | `-` | `ApiChannelPlatformController.permissions()` (L106): JWT authenticated | Covered by controller/JWT default deny + static review |
| 89 | `/api/channels/{}/permissions/{}/{}` | `PUT` | `ApiChannelPlatformController.putOverwrite()` (L113): JWT authenticated; `ApiChannelPlatformController.deleteOverwrite()` (L123): JWT authenticated | Covered by controller/JWT default deny + static review |
| 90 | `/api/channels/{}/restore` | `POST` | `ApiChannelPlatformController.restore()` (L98): JWT authenticated | Covered by controller/JWT default deny + static review |
| 91 | `/api/circle-theme/instance/configure` | `POST` | `ApiCircleThemeController.configure()` (L129): MerchantAccessService + JWT currentMemberId() scope | Anon sweep: 401 |
| 92 | `/api/circle-theme/instance/review` | `-` | `ApiCircleThemeController.review()` (L152): MerchantAccessService + JWT currentMemberId() scope | Anon sweep: 401 |
| 93 | `/api/circle-theme/instance/{}/offers` | `-` | `ApiCircleThemeController.offers()` (L40): MerchantAccessService + JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 94 | `/api/circle-theme/session` | `-` | `ApiCircleThemeController.createSession()` (L53): JWT currentMemberId() scope | Anon sweep: 401 |
| 95 | `/api/circle-theme/session/answer` | `-` | `ApiCircleThemeController.answer()` (L94): JWT currentMemberId() scope | Anon sweep: 401 |
| 96 | `/api/circle-theme/session/join` | `-` | `ApiCircleThemeController.joinSession()` (L66): JWT currentMemberId() scope | Anon sweep: 401 |
| 97 | `/api/circle-theme/session/record` | `-` | `ApiCircleThemeController.record()` (L79): JWT currentMemberId() scope | Anon sweep: 401 |
| 98 | `/api/circle-theme/session/{}/card` | `-` | `ApiCircleThemeController.card()` (L109): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 99 | `/api/city-game` | `GET` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 100 | `/api/city-game/acquisition` | `GET` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 101 | `/api/city-game/acquisition/command` | `-` | `ApiCityGameR491Controller.acquisitionCommand()` (L72): JWT authenticated | Anon sweep: 401 |
| 102 | `/api/city-game/acquisition/quote/{}` | `-` | `ApiCityGameR491Controller.acquisitionQuote()` (L69): JWT authenticated | Covered by controller/JWT default deny + static review |
| 103 | `/api/city-game/acquisition/request/{}` | `-` | `ApiCityGameR491Controller.acquisitionReceipt()` (L77): JWT authenticated | Covered by controller/JWT default deny + static review |
| 104 | `/api/city-game/acquisition/state` | `-` | `ApiCityGameR491Controller.acquisitionState()` (L63): JWT authenticated | Anon sweep: 401 |
| 105 | `/api/city-game/acquisition/titles` | `-` | `ApiCityGameR491Controller.acquisitionTitles()` (L66): JWT authenticated | Anon sweep: 401 |
| 106 | `/api/city-game/action-budget` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 107 | `/api/city-game/action-budget/command` | `POST` | `ApiCityGameR491Controller.budgetCommand()` (L55): JWT authenticated | Anon sweep: 401 |
| 108 | `/api/city-game/action-budget/request/{}` | `-` | `ApiCityGameR491Controller.budgetReceipt()` (L58): JWT authenticated | Covered by controller/JWT default deny + static review |
| 109 | `/api/city-game/action-budget/state` | `-` | `ApiCityGameR491Controller.budgetState()` (L52): JWT authenticated | Anon sweep: 401 |
| 110 | `/api/city-game/arrival-evidence` | `GET` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 111 | `/api/city-game/arrival-evidence/command` | `-` | `ApiCityGameR491Controller.arrivalCommand()` (L90): JWT authenticated | Anon sweep: 401 |
| 112 | `/api/city-game/arrival-evidence/evidence/{}` | `-` | `ApiCityGameR491Controller.arrivalEvidence()` (L96): JWT authenticated | Covered by controller/JWT default deny + static review |
| 113 | `/api/city-game/arrival-evidence/merchant` | `-` | `ApiCityGameR491Controller.arrivalMerchant()` (L87): JWT authenticated | Anon sweep: 401 |
| 114 | `/api/city-game/arrival-evidence/request/{}` | `-` | `ApiCityGameR491Controller.arrivalReceipt()` (L93): JWT authenticated | Covered by controller/JWT default deny + static review |
| 115 | `/api/city-game/arrival-evidence/state` | `-` | `ApiCityGameR491Controller.arrivalState()` (L84): JWT authenticated | Anon sweep: 401 |
| 116 | `/api/city-game/current` | `-` | `ApiCityGameController.current()` (L51): JWT authenticated | Anon sweep: 401 |
| 117 | `/api/city-game/fairness/outing` | `-` | `ApiCityGameController.fairnessOuting()` (L131): JWT authenticated | Anon sweep: 401 |
| 118 | `/api/city-game/fairness/report` | `-` | `ApiCityGameController.fairnessReport()` (L126): JWT authenticated | Anon sweep: 401 |
| 119 | `/api/city-game/game/action` | `-` | `ApiCityGameController.action()` (L118): JWT authenticated | Anon sweep: 401 |
| 120 | `/api/city-game/game/action/{}` | `-` | `ApiCityGameController.actionReceipt()` (L121): JWT authenticated | Covered by controller/JWT default deny + static review |
| 121 | `/api/city-game/game/deltas` | `-` | `ApiCityGameController.gameDeltas()` (L110): JWT authenticated | Anon sweep: 401 |
| 122 | `/api/city-game/game/quote` | `-` | `ApiCityGameController.quote()` (L115): JWT authenticated | Anon sweep: 401 |
| 123 | `/api/city-game/game/snapshot` | `-` | `ApiCityGameController.gameSnapshot()` (L107): JWT authenticated | Anon sweep: 401 |
| 124 | `/api/city-game/merchant/operations` | `GET` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 125 | `/api/city-game/merchant/operations/availability` | `-` | `ApiCityGameR491Controller.merchantAvailability()` (L147): JWT authenticated | Anon sweep: 401 |
| 126 | `/api/city-game/merchant/operations/intent` | `-` | `ApiCityGameR491Controller.merchantIntent()` (L150): JWT authenticated | Anon sweep: 401 |
| 127 | `/api/city-game/merchant/operations/request` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 128 | `/api/city-game/merchant/operations/request/{}` | `-` | `ApiCityGameR491Controller.merchantReceipt()` (L155): JWT authenticated | Covered by controller/JWT default deny + static review |
| 129 | `/api/city-game/neutral-tasks` | `GET` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 130 | `/api/city-game/neutral-tasks/command` | `-` | `ApiCityGameR491Controller.neutralCommand()` (L104): JWT authenticated | Anon sweep: 401 |
| 131 | `/api/city-game/neutral-tasks/request/{}` | `-` | `ApiCityGameR491Controller.neutralReceipt()` (L109): JWT authenticated | Covered by controller/JWT default deny + static review |
| 132 | `/api/city-game/neutral-tasks/state` | `-` | `ApiCityGameR491Controller.neutralState()` (L101): JWT authenticated | Anon sweep: 401 |
| 133 | `/api/city-game/onsite-tasks` | `GET` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 134 | `/api/city-game/onsite-tasks/command` | `-` | `ApiCityGameR491Controller.onsiteCommand()` (L117): JWT authenticated | Anon sweep: 401 |
| 135 | `/api/city-game/onsite-tasks/request/{}` | `-` | `ApiCityGameR491Controller.onsiteReceipt()` (L122): JWT authenticated | Covered by controller/JWT default deny + static review |
| 136 | `/api/city-game/onsite-tasks/result/{}` | `-` | `ApiCityGameR491Controller.onsiteResult()` (L127): JWT authenticated | Covered by controller/JWT default deny + static review |
| 137 | `/api/city-game/onsite-tasks/state` | `-` | `ApiCityGameR491Controller.onsiteState()` (L114): JWT authenticated | Anon sweep: 401 |
| 138 | `/api/city-game/participation` | `-` | `ApiCityGameController.participation()` (L54): JWT authenticated | Anon sweep: 401 |
| 139 | `/api/city-game/player-rules` | `GET` | `ApiCityGameController.playerRules()` (L76): JWT authenticated | Anon sweep: 401 |
| 140 | `/api/city-game/points` | `-` | `ApiCityGameController.points()` (L61): JWT authenticated | Anon sweep: 401 |
| 141 | `/api/city-game/state/deltas` | `GET` | `ApiCityGameController.stateDeltas()` (L73): JWT authenticated | Anon sweep: 401 |
| 142 | `/api/city-game/state/snapshot` | `GET` | `ApiCityGameController.stateSnapshot()` (L67): JWT authenticated | Anon sweep: 401 |
| 143 | `/api/city-game/task-history` | `GET` | `ApiCityGameR491Controller.history()` (L130): JWT authenticated | Anon sweep: 401 |
| 144 | `/api/city-game/team-participation` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 145 | `/api/city-game/team-participation/command` | `POST` | `ApiCityGameController.command()` (L87): JWT authenticated | Anon sweep: 401 |
| 146 | `/api/city-game/team-participation/invite` | `GET` | `ApiCityGameController.invite()` (L82): JWT authenticated | Anon sweep: 401 |
| 147 | `/api/city-game/team-participation/request/{}` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Covered by controller/JWT default deny + static review |
| 148 | `/api/city-game/team-participation/state` | `-` | `ApiCityGameController.teamState()` (L79): JWT authenticated | Anon sweep: 401 |
| 149 | `/api/city-game/{}/request/{}` | `GET` | `ApiCityGameController.originalReceipt()` (L93): JWT authenticated | Covered by controller/JWT default deny + static review |
| 150 | `/api/city-value/visits` | `POST` | `ApiCityGameR491Controller.valueBegin()` (L165): JWT authenticated | Anon sweep: 401 |
| 151 | `/api/city-value/visits/feedback` | `-` | `ApiCityGameR491Controller.valueFeedback()` (L168): JWT authenticated | Anon sweep: 401 |
| 152 | `/api/city-value/visits/services` | `GET` | `ApiCityGameR491Controller.valueServices()` (L162): JWT authenticated | Anon sweep: 401 |
| 153 | `/api/city/nodes` | `GET` | `ApiCityNodeController.nodes()` (L72): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 154 | `/api/city/nodes/complete` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 155 | `/api/city/nodes/favorite` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 156 | `/api/city/nodes/favorites` | `-` | `ApiCityNodeController.favorites()` (L113): JWT authenticated | Anon sweep: 401 |
| 157 | `/api/city/nodes/merchant/{}` | `-` | `ApiCityNodeController.merchantNode()` (L103): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 158 | `/api/city/nodes/{}` | `GET` | `ApiCityNodeController.detail()` (L87): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 159 | `/api/city/nodes/{}/complete` | `GET` | `ApiCityNodeController.complete()` (L171): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 160 | `/api/city/nodes/{}/favorite` | `GET` | `ApiCityNodeController.favorite()` (L123): JWT authenticated | Covered by controller/JWT default deny + static review |
| 161 | `/api/city/walking/target` | `GET` | `ApiCityGameR491Controller.walkingTarget()` (L176): JWT authenticated | Anon sweep: 401 |
| 162 | `/api/club-compensation/editions` | `-` | `ApiClubCompensationController.editions()` (L87): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 163 | `/api/club-compensation/editions/empty-reason` | `-` | `ApiClubCompensationController.editionsEmptyReason()` (L107): JWT currentMemberId() scope | Anon sweep: 401 |
| 164 | `/api/club-compensation/hours/report` | `-` | `ApiClubCompensationController.reportHours()` (L66): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 165 | `/api/club-compensation/quality/evidence` | `-` | `ApiClubCompensationController.qualityEvidence()` (L135): JWT currentMemberId() scope | Anon sweep: 401 |
| 166 | `/api/club-direct-merchant/club` | `-` | `ApiClubDirectMerchantReceivableController.club()` (L30): JWT authenticated | Anon sweep: 401 |
| 167 | `/api/club-direct-merchant/merchant` | `-` | `ApiClubDirectMerchantReceivableController.merchant()` (L21): MerchantAccessService | Anon sweep: 401 |
| 168 | `/api/club/access/me` | `POST` | `ApiClubRoleController.accessMe()` (L53): ClubAccessService/ChannelGuildAccess/canViewClub | Anon sweep: 401 |
| 169 | `/api/club/activity/cancel` | `-` | `ApiClubGapController.activityCancel()` (L95): JWT authenticated | Anon sweep: 401 |
| 170 | `/api/club/activity/detail` | `-` | `ApiClubGapController.activityDetail()` (L104): JWT authenticated | Anon sweep: 401 |
| 171 | `/api/club/activity/join` | `-` | `ApiClubGapController.activityJoin()` (L86): JWT authenticated | Anon sweep: 401 |
| 172 | `/api/club/admission/receipt` | `-` | `ApiClubController.admissionReceipt()` (L1314): OwnershipUtils/owner check + JWT currentMemberId() scope | Anon sweep: 401 |
| 173 | `/api/club/badge/create` | `-` | `ApiClubController.badgeCreate()` (L1521): OwnershipUtils/owner check + JWT currentMemberId() scope | Targeted: pA->200/500 (只有主理人可创建勋章); pA->200/500 (只有主理人可创建勋章) / Anon sweep: 401 |
| 174 | `/api/club/badge/grant` | `-` | `ApiClubController.badgeGrant()` (L1594): ClubAccessService/ChannelGuildAccess/canViewClub + OwnershipUtils/owner check + JWT currentMemberId() scope | Anon sweep: 401 |
| 175 | `/api/club/badge/list` | `-` | FIXED: `ApiClubController.badgeList` enforces `currentMemberId()`, `canViewClub(club)`, and `status==1` or owner check | Targeted: ANON->401/401 (登录状态已失效，请重新登录); ->200/200 (操作成功) / Anon sweep: 401 |
| 176 | `/api/club/badge/original-receipt` | `-` | `ApiClubController.badgeOriginalReceipt()` (L1534): OwnershipUtils/owner check + JWT currentMemberId() scope | Anon sweep: 401 |
| 177 | `/api/club/become-leader` | `-` | `ApiClubController.becomeLeader()` (L469): JWT currentMemberId() scope | Anon sweep: 401 |
| 178 | `/api/club/calendar/sessions` | `-` | `ApiClubCalendarController.sessions()` (L50): ClubAccessService/ChannelGuildAccess/canViewClub + OwnershipUtils/owner check + JWT currentMemberId() scope | Anon sweep: 401 |
| 179 | `/api/club/channels` | `-` | `ApiClubGapController.clubChannels()` (L170): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 180 | `/api/club/chat` | `GET,POST` | `ApiClubController.chat()` (L1498): OwnershipUtils/owner check + JWT currentMemberId() scope | Anon sweep: 401 |
| 181 | `/api/club/create` | `-` | `ApiClubController.create()` (L507): JWT currentMemberId() scope | Anon sweep: 401 |
| 182 | `/api/club/crm/checkin/detail` | `-` | `ApiClubCrmController.checkinDetail()` (L105): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 183 | `/api/club/crm/customers/count` | `-` | `ApiClubCrmController.customerCount()` (L56): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 184 | `/api/club/crm/customers/detail` | `-` | `ApiClubCrmController.customerDetail()` (L71): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 185 | `/api/club/crm/customers/list` | `-` | `ApiClubCrmController.customers()` (L40): JWT authenticated + service-layer caller scope | Targeted: pA->200/500 (当前岗位没有客户查看权限); pA->200/500 (当前岗位没有客户查看权限) / Anon sweep: 401 |
| 186 | `/api/club/crm/customers/tag-remark` | `-` | `ApiClubCrmController.saveTagRemark()` (L86): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 187 | `/api/club/crm/topic-customers` | `-` | `ApiClubCrmController.topicCustomers()` (L120): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 188 | `/api/club/crm/topic-manage-stats` | `-` | `ApiClubCrmController.topicManageStats()` (L136): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 189 | `/api/club/detail` | `POST` | `ApiClubController.detail()` (L659): ClubAccessService/ChannelGuildAccess/canViewClub + OwnershipUtils/owner check + JWT currentMemberId() scope | Targeted: ANON->200/200 (操作成功) / Anon sweep: 200/500 (缺少俱乐部ID) |
| 190 | `/api/club/dissolution-blockers` | `-` | `ApiClubController.dissolutionBlockers()` (L1267): JWT currentMemberId() scope | Anon sweep: 401 |
| 191 | `/api/club/dissolve` | `-` | `ApiClubController.dissolve()` (L1202): JWT currentMemberId() scope | Anon sweep: 401 |
| 192 | `/api/club/event-notification/audience-counts` | `-` | `ApiClubEventNotificationController.audienceCounts()` (L34): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 193 | `/api/club/event-notification/preview` | `-` | `ApiClubEventNotificationController.preview()` (L23): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 194 | `/api/club/event-notification/retry` | `-` | `ApiClubEventNotificationController.retry()` (L64): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 195 | `/api/club/event-notification/send` | `-` | `ApiClubEventNotificationController.send()` (L28): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 196 | `/api/club/event-notification/status` | `-` | `ApiClubEventNotificationController.status()` (L52): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 197 | `/api/club/event-ops/attendance/correct` | `-` | `ApiClubEventOpsController.correctAttendance()` (L252): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 198 | `/api/club/event-ops/cancel` | `-` | `ApiClubEventOpsController.cancelOccurrence()` (L148): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 199 | `/api/club/event-ops/host/request` | `-` | `ApiClubEventOpsController.requestHost()` (L188): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 200 | `/api/club/event-ops/occurrence/status` | `-` | `ApiClubEventOpsController.occurrenceStatus()` (L163): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 201 | `/api/club/event-ops/roster` | `-` | `ApiClubEventOpsController.roster()` (L240): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 202 | `/api/club/event-ops/series/create` | `-` | `ApiClubEventOpsController.createSeries()` (L55): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 203 | `/api/club/event-ops/series/detail` | `-` | `ApiClubEventOpsController.seriesDetail()` (L92): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 204 | `/api/club/event-ops/series/list` | `-` | `ApiClubEventOpsController.listSeries()` (L79): JWT authenticated + service-layer caller scope | Targeted: pA->200/500 (Request method 'GET' is not supported); pA->200/403 (俱乐部身份无效或权限不足) / Anon sweep: 401 |
| 205 | `/api/club/event-ops/series/occurrences` | `-` | `ApiClubEventOpsController.seriesOccurrences()` (L108): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 206 | `/api/club/event-ops/series/original-result` | `-` | `ApiClubEventOpsController.seriesOriginalResult()` (L71): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 207 | `/api/club/event-ops/series/update-future` | `-` | `ApiClubEventOpsController.updateFuture()` (L123): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 208 | `/api/club/event-ops/topics` | `-` | `ApiClubEventOpsController.topics()` (L42): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 209 | `/api/club/event-ops/waitlist/cancel` | `-` | `ApiClubEventOpsController.cancelWaitlist()` (L227): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 210 | `/api/club/event-ops/waitlist/join` | `-` | `ApiClubEventOpsController.joinWaitlist()` (L176): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 211 | `/api/club/event-ops/waitlist/status` | `-` | `ApiClubEventOpsController.waitlistStatus()` (L215): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 212 | `/api/club/event-ops/waitlist/{}` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Covered by controller/JWT default deny + static review |
| 213 | `/api/club/events` | `-` | `ApiClubGapController.events()` (L112): JWT authenticated | Anon sweep: 401 |
| 214 | `/api/club/events/Checkin` | `-` | `ApiClubGapController.eventsCheckin()` (L124): JWT authenticated | Anon sweep: 401 |
| 215 | `/api/club/events/proxy-enroll` | `-` | `ApiClubGapController.proxyEnroll()` (L130): JWT authenticated | Anon sweep: 401 |
| 216 | `/api/club/events/save` | `-` | `ApiClubGapController.eventsSave()` (L118): JWT authenticated | Anon sweep: 401 |
| 217 | `/api/club/governance/ban` | `-` | `ApiClubGovernanceController.ban()` (L55): JWT currentMemberId() scope | Anon sweep: 401 |
| 218 | `/api/club/governance/cases/appeal` | `-` | `ApiClubGovernanceController.appeal()` (L112): JWT currentMemberId() scope | Anon sweep: 401 |
| 219 | `/api/club/governance/cases/mine` | `-` | `ApiClubGovernanceController.myCases()` (L125): JWT currentMemberId() scope | Anon sweep: 401 |
| 220 | `/api/club/governance/cases/report` | `-` | `ApiClubGovernanceController.report()` (L98): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 221 | `/api/club/governance/list` | `-` | `ApiClubGovernanceController.list()` (L37): JWT currentMemberId() scope | Anon sweep: 401 |
| 222 | `/api/club/governance/owner/transfer` | `-` | `ApiClubGovernanceController.transferOwner()` (L84): JWT currentMemberId() scope | Anon sweep: 401 |
| 223 | `/api/club/governance/unban` | `-` | `ApiClubGovernanceController.unban()` (L70): JWT currentMemberId() scope | Anon sweep: 401 |
| 224 | `/api/club/group-code` | `-` | `ApiClubGapController.groupCode()` (L150): JWT authenticated | Anon sweep: 401 |
| 225 | `/api/club/group-code/save` | `-` | `ApiClubGapController.groupCodeSave()` (L156): JWT authenticated | Anon sweep: 401 |
| 226 | `/api/club/home` | `-` | `ApiClubController.home()` (L1382): ClubAccessService/ChannelGuildAccess/canViewClub + JWT currentMemberId() scope | Anon sweep: 401 |
| 227 | `/api/club/join` | `POST` | `ApiClubController.join()` (L941): JWT currentMemberId() scope | Anon sweep: 401 |
| 228 | `/api/club/join-request/approve` | `POST` | `ApiClubController.approveJoinRequest()` (L1172): JWT currentMemberId() scope | Anon sweep: 401 |
| 229 | `/api/club/join-request/reject` | `POST` | `ApiClubController.rejectJoinRequest()` (L1178): JWT currentMemberId() scope | Anon sweep: 401 |
| 230 | `/api/club/join-requests` | `-` | `ApiClubController.joinRequests()` (L1156): JWT currentMemberId() scope | Targeted: pA->200/500 (只有主理人或管理员可查看入会申请); pA->200/500 (只有主理人或管理员可查看入会申请) / Anon sweep: 401 |
| 231 | `/api/club/join-requests/apply` | `-` | `ApiClubGapController.apply()` (L164): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 232 | `/api/club/lead` | `POST` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 233 | `/api/club/lead/arrive` | `-` | `ApiClubLeadController.arrive()` (L162): JWT authenticated | Anon sweep: 401 |
| 234 | `/api/club/lead/broadcast` | `-` | `ApiClubLeadController.broadcast()` (L368): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 235 | `/api/club/lead/edit-ops` | `-` | `ApiClubLeadController.editOps()` (L307): JWT currentMemberId() scope | Anon sweep: 401 |
| 236 | `/api/club/lead/release-station` | `POST` | `ApiClubLeadController.releaseStation()` (L532): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 237 | `/api/club/lead/team-progress` | `GET` | `ApiClubLeadController.teamProgress()` (L390): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 238 | `/api/club/lead/unlock-chapter` | `GET` | `ApiClubLeadController.unlockChapter()` (L213): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 239 | `/api/club/lead/unlock-chapter-receipt` | `POST` | `ApiClubLeadController.unlockChapterReceipt()` (L275): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 240 | `/api/club/leaderboard` | `-` | `ApiClubController.leaderboard()` (L2790): OwnershipUtils/owner check + JWT currentMemberId() scope | Anon sweep: 200/500 (Request method 'GET' is not support) |
| 241 | `/api/club/list` | `-` | `ApiClubController.list()` (L630): ClubAccessService/ChannelGuildAccess/canViewClub + OwnershipUtils/owner check + JWT currentMemberId() scope | Anon sweep: 200/500 (Request method 'GET' is not support) |
| 242 | `/api/club/members` | `-` | `ApiClubController.members()` (L719): OwnershipUtils/owner check + JWT currentMemberId() scope | Targeted: pA->200/200 (操作成功) / Anon sweep: 200/500 (Request method 'GET' is not support) |
| 243 | `/api/club/merchants` | `-` | FIXED: `ApiClubController.merchants` enforces `currentMemberId()`, whitelists `{name, categoryId, tags, cityRole}`, forces `coopOpen=1, status=1, accountStatus=1, delFlag=0`, projects `PublicMerchantDiscoveryVO` | Targeted: ANON->401/401 (登录状态已失效，请重新登录) [pre-fix repro: 200 PII/oracle -> fixed & unit-tested]; pA->200/200 (操作成功) [pre-fix repro: 200 PII/oracle -> fixed & unit-tested]; pA->200/200 (操作成功) [pre-fix repro: 200 PII/oracle -> fixed & unit-tested] / Anon sweep: 401 |
| 244 | `/api/club/my` | `-` | `ApiClubController.my()` (L1349): JWT currentMemberId() scope | Anon sweep: 401 |
| 245 | `/api/club/name-check` | `-` | `ApiClubController.nameCheck()` (L520): OwnershipUtils/owner check + JWT currentMemberId() scope | Anon sweep: 401 |
| 246 | `/api/club/notices` | `-` | `ApiClubGapController.noticeAudience()` (L178): OwnershipUtils/owner check + JWT currentMemberId() scope | Anon sweep: 401 |
| 247 | `/api/club/notices/send` | `-` | `ApiClubGapController.noticeSend()` (L184): OwnershipUtils/owner check + JWT currentMemberId() scope | Anon sweep: 401 |
| 248 | `/api/club/open-settings/member-post` | `-` | `ApiClubController.updateMemberPostAllowed()` (L989): JWT currentMemberId() scope | Anon sweep: 401 |
| 249 | `/api/club/open-settings/merchant-coop` | `-` | `ApiClubController.updateMerchantUndertakeOpen()` (L1137): JWT currentMemberId() scope | Anon sweep: 401 |
| 250 | `/api/club/open-settings/public-visible` | `-` | `ApiClubController.updatePublicVisible()` (L1118): JWT currentMemberId() scope | Anon sweep: 401 |
| 251 | `/api/club/open-settings/welcome-note` | `POST` | `ApiClubController.updateWelcomeNote()` (L1011): JWT currentMemberId() scope | Anon sweep: 401 |
| 252 | `/api/club/open-settings/welcome-note-receipt` | `-` | `ApiClubController.welcomeNoteReceipt()` (L1025): JWT currentMemberId() scope | Anon sweep: 401 |
| 253 | `/api/club/personal-receiver/bind` | `-` | `ApiClubController.bindPersonalReceiver()` (L2840): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 254 | `/api/club/personal-receiver/status` | `GET` | `ApiClubController.personalReceiverStatus()` (L2850): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 255 | `/api/club/personal-receiver/unbind` | `-` | `ApiClubController.unbindPersonalReceiver()` (L2845): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 256 | `/api/club/post` | `POST` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 257 | `/api/club/post/comment/create` | `-` | `ApiClubController.commentCreate()` (L2678): OwnershipUtils/owner check + JWT currentMemberId() scope | Anon sweep: 401 |
| 258 | `/api/club/post/comment/delete` | `-` | `ApiClubController.commentDelete()` (L2727): JWT currentMemberId() scope | Anon sweep: 401 |
| 259 | `/api/club/post/comment/list` | `-` | `ApiClubController.commentList()` (L2664): ClubAccessService/ChannelGuildAccess/canViewClub + OwnershipUtils/owner check + JWT currentMemberId() scope | Anon sweep: 401 |
| 260 | `/api/club/post/comment/report` | `-` | `ApiClubController.commentReport()` (L2761): OwnershipUtils/owner check + JWT currentMemberId() scope | Anon sweep: 401 |
| 261 | `/api/club/post/create` | `-` | `ApiClubController.postCreate()` (L2510): JWT currentMemberId() scope | Anon sweep: 401 |
| 262 | `/api/club/post/delete` | `-` | `ApiClubController.postDelete()` (L2714): JWT currentMemberId() scope | Anon sweep: 401 |
| 263 | `/api/club/post/feed` | `-` | `ApiClubController.postFeed()` (L2265): JWT currentMemberId() scope | Anon sweep: 401 |
| 264 | `/api/club/post/history` | `-` | `ApiClubController.postHistory()` (L2648): ClubAccessService/ChannelGuildAccess/canViewClub + JWT currentMemberId() scope | Anon sweep: 401 |
| 265 | `/api/club/post/like` | `-` | `ApiClubController.postLike()` (L2371): ClubAccessService/ChannelGuildAccess/canViewClub + JWT currentMemberId() scope | Anon sweep: 401 |
| 266 | `/api/club/post/like-state` | `-` | `ApiClubController.postLikeState()` (L2349): ClubAccessService/ChannelGuildAccess/canViewClub + JWT currentMemberId() scope | Anon sweep: 401 |
| 267 | `/api/club/post/list` | `-` | `ApiClubController.postList()` (L2052): ClubAccessService/ChannelGuildAccess/canViewClub + JWT currentMemberId() scope | Anon sweep: 200/500 (Request method 'GET' is not support) |
| 268 | `/api/club/post/operation` | `POST` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 269 | `/api/club/post/operation/comment` | `POST` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 270 | `/api/club/post/operation/receipt` | `POST` | `ApiClubController.postOperation()` (L2603): JWT currentMemberId() scope | Anon sweep: 401 |
| 271 | `/api/club/post/report` | `-` | `ApiClubController.postReport()` (L2744): JWT currentMemberId() scope | Anon sweep: 401 |
| 272 | `/api/club/post/repost` | `POST` | `ApiClubController.postRepost()` (L2410): ClubAccessService/ChannelGuildAccess/canViewClub + JWT currentMemberId() scope | Anon sweep: 401 |
| 273 | `/api/club/post/repost/undo` | `-` | `ApiClubController.postRepostUndo()` (L2417): ClubAccessService/ChannelGuildAccess/canViewClub + JWT currentMemberId() scope | Anon sweep: 401 |
| 274 | `/api/club/post/update` | `-` | `ApiClubController.postUpdate()` (L2582): JWT currentMemberId() scope | Anon sweep: 401 |
| 275 | `/api/club/quit` | `POST` | `ApiClubController.quit()` (L1299): OwnershipUtils/owner check + JWT currentMemberId() scope | Anon sweep: 401 |
| 276 | `/api/club/recruit/overview` | `-` | `ApiClubRecruitController.overview()` (L24): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 277 | `/api/club/remove-member` | `-` | `ApiClubController.removeMember()` (L895): OwnershipUtils/owner check + JWT currentMemberId() scope | Anon sweep: 401 |
| 278 | `/api/club/roles` | `POST` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 279 | `/api/club/roles/assign` | `POST` | `ApiClubRoleController.assign()` (L131): JWT currentMemberId() scope | Anon sweep: 401 |
| 280 | `/api/club/roles/assign-receipt` | `-` | `ApiClubRoleController.assignReceipt()` (L151): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 281 | `/api/club/roles/list` | `-` | `ApiClubRoleController.list()` (L112): JWT currentMemberId() scope | Targeted: pA->200/500 (Request method 'GET' is not supported); pA->200/403 (俱乐部身份无效或权限不足); h1->200/403 (俱乐部身份无效或权限不足) / Anon sweep: 401 |
| 282 | `/api/club/roles/revoke` | `-` | `ApiClubRoleController.revoke()` (L168): JWT currentMemberId() scope | Anon sweep: 401 |
| 283 | `/api/club/roles/revoke-receipt` | `-` | `ApiClubRoleController.revokeReceipt()` (L159): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 284 | `/api/club/set-member-role` | `-` | `ApiClubController.setMemberRole()` (L2456): JWT currentMemberId() scope | Anon sweep: 401 |
| 285 | `/api/club/settings/notify-level` | `-` | `ApiChannelNotifyController.clubDefault()` (L64): JWT authenticated; `ApiChannelNotifyController.saveClubDefault()` (L71): JWT authenticated | Targeted: pA->200/200 (操作成功); pA->400/400 (body); p0->403/403 (加入后才能查看频道) / Anon sweep: 401 |
| 286 | `/api/club/settlement/mine` | `-` | `ApiClubSettlementController.mine()` (L71): JWT authenticated | Anon sweep: 401 |
| 287 | `/api/club/settlement/summary` | `POST` | `ApiClubSettlementController.summary()` (L80): JWT authenticated | Anon sweep: 401 |
| 288 | `/api/club/settlements` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 289 | `/api/club/settlements/export` | `-` | `ApiClubGapController.settlementsExport()` (L231): JWT authenticated | Anon sweep: 401 |
| 290 | `/api/club/topic-node-answer` | `-` | `ApiClubController.topicNodeAnswer()` (L1824): JWT currentMemberId() scope | Anon sweep: 401 |
| 291 | `/api/club/topic-registrations` | `-` | `ApiClubController.topicRegistrations()` (L1866): JWT currentMemberId() scope | Targeted: pA->200/500 (缺少俱乐部或团ID); pA->200/500 (缺少俱乐部或团ID); ->200/500 (仅主理人或管理员可查看报名名册) / Anon sweep: 401 |
| 292 | `/api/club/topic-setting/detail` | `-` | `ApiClubTopicSettingController.detail()` (L24): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 293 | `/api/club/topic-setting/end` | `-` | `ApiClubTopicSettingController.end()` (L53): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 294 | `/api/club/topic-setting/save` | `-` | `ApiClubTopicSettingController.save()` (L38): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 295 | `/api/club/topics` | `-` | `ApiClubController.topics()` (L1636): ClubAccessService/ChannelGuildAccess/canViewClub + JWT currentMemberId() scope | Anon sweep: 200/500 (Request method 'GET' is not support) |
| 296 | `/api/club/update` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Targeted: pA->404/404 (请求路径不存在); h1->404/404 (请求路径不存在) / Anon sweep: 401 |
| 297 | `/api/club/update-mine` | `POST` | `ApiClubController.updateMine()` (L541): OwnershipUtils/owner check + JWT currentMemberId() scope | Anon sweep: 401 |
| 298 | `/api/club/workbench` | `-` | `ApiClubGapController.workbench()` (L192): OwnershipUtils/owner check + JWT currentMemberId() scope | Anon sweep: 401 |
| 299 | `/api/comment/add` | `POST` | `ApiCommentController.addComment()` (L104): JWT authenticated | Anon sweep: 401 |
| 300 | `/api/comment/delete` | `POST` | `ApiCommentController.deleteComment()` (L289): OwnershipUtils/owner check + JWT currentMemberId() scope | Anon sweep: 401 |
| 301 | `/api/comment/like` | `POST` | `ApiCommentController.commentLike()` (L361): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 302 | `/api/comment/list` | `POST` | `ApiCommentController.commentList()` (L193): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 200/500 (参数不合规) |
| 303 | `/api/comment/report` | `-` | `ApiCommentController.reportComment()` (L328): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 304 | `/api/common/banner` | `-` | `ApiCommonController.getBanner()` (L286): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 200/500 (Request method 'GET' is not support) |
| 305 | `/api/common/dict` | `POST` | `ApiCommonController.dictType()` (L85): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 200/200 (操作成功) |
| 306 | `/api/common/infomation_detail` | `-` | `ApiCommonController.infomationDetail()` (L318): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 200/500 (Request method 'GET' is not support) |
| 307 | `/api/common/infomation_list` | `POST` | `ApiCommonController.infomationList()` (L309): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 200/200 (操作成功) |
| 308 | `/api/common/uploadOSS` | `GET,POST` | `ApiCommonController.uploadOSS()` (L105): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 309 | `/api/common/uploadOSS/receipt` | `GET` | `ApiCommonController.uploadOSSReceipt()` (L128): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 200/401 (请先登录) |
| 310 | `/api/community-trust/contributions/submit` | `-` | `ApiCommunityTrustController.submitContribution()` (L107): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 311 | `/api/community-trust/contributions/taxonomy` | `GET` | `ApiCommunityTrustController.contributionTaxonomy()` (L121): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 312 | `/api/community-trust/reports/appeal` | `-` | `ApiCommunityTrustController.appealReport()` (L94): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 313 | `/api/community-trust/reports/mine` | `-` | `ApiCommunityTrustController.myReports()` (L60): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 314 | `/api/community-trust/reports/submit` | `-` | `ApiCommunityTrustController.submitReport()` (L38): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 315 | `/api/community-trust/reports/taxonomy` | `-` | `ApiCommunityTrustController.reportTaxonomy()` (L55): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 316 | `/api/community-trust/reports/{}` | `-` | `ApiCommunityTrustController.reportDetail()` (L72): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 317 | `/api/community-trust/safety/merchants` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 318 | `/api/community-trust/safety/merchants/{}` | `GET` | `ApiCommunityTrustController.safetySummary()` (L181): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 319 | `/api/community-trust/safety/ratings` | `-` | `ApiCommunityTrustController.submitSafetyRating()` (L162): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 320 | `/api/community-trust/safety/taxonomy` | `GET` | `ApiCommunityTrustController.safetyTaxonomy()` (L176): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 321 | `/api/compliance/consents` | `POST` | `ApiComplianceController.record()` (L64): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 322 | `/api/compliance/consents/latest` | `POST` | `ApiComplianceController.latest()` (L125): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 323 | `/api/compliance/usage-records` | `-` | `appgaps/ApiAppGapsMapController.usageRecords()` (L26): JWT authenticated | Anon sweep: 401 |
| 324 | `/api/config/features` | `POST` | `ApiConfigController.features()` (L66): @Anonymous (public endpoint; parameter/entity scoped where applicable) | Anon sweep: 200/200 (操作成功) |
| 325 | `/api/content-draft` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 326 | `/api/content-landing/info` | `-` | `ApiContentLandingMetadataController.info()` (L38): JWT currentMemberId() scope | Anon sweep: 401 |
| 327 | `/api/content-landing/save` | `-` | `ApiContentLandingMetadataController.save()` (L48): JWT currentMemberId() scope | Anon sweep: 401 |
| 328 | `/api/content-landing/translation/save` | `-` | `ApiContentLandingMetadataController.saveTranslation()` (L58): JWT currentMemberId() scope | Anon sweep: 401 |
| 329 | `/api/coop` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 330 | `/api/coop/candidates` | `-` | `ApiCoopController.candidates()` (L1249): OwnershipUtils/owner check + JWT currentMemberId() scope | Targeted: pA->200/500 (Request method 'GET' is not supported); pA->200/500 (缺少主题); ->200/500 (仅主题发布者可查看候选池) / Anon sweep: 401 |
| 331 | `/api/coop/candidates/received` | `-` | `ApiCoopController.candidatesReceived()` (L1370): JWT currentMemberId() scope | Anon sweep: 401 |
| 332 | `/api/coop/complaint/mine` | `-` | `ApiCoopController.complaintMine()` (L1081): JWT currentMemberId() scope | Anon sweep: 401 |
| 333 | `/api/coop/complaint/report` | `POST` | `ApiCoopController.complaintReport()` (L1091): JWT currentMemberId() scope | Anon sweep: 401 |
| 334 | `/api/coop/complaint/topics` | `-` | `ApiCoopController.complaintTopics()` (L1064): JWT currentMemberId() scope | Anon sweep: 401 |
| 335 | `/api/coop/credit` | `-` | `ApiCoopController.credit()` (L1236): OwnershipUtils/owner check + JWT currentMemberId() scope | Anon sweep: 401 |
| 336 | `/api/coop/deposit/create/app` | `-` | `ApiCoopController.depositCreateApp()` (L211): JWT currentMemberId() scope | Anon sweep: 401 |
| 337 | `/api/coop/deposit/refund/retry` | `-` | `ApiCoopController.retryDepositRefund()` (L685): JWT currentMemberId() scope | Anon sweep: 401 |
| 338 | `/api/coop/deposit/status` | `-` | `ApiCoopController.depositStatus()` (L159): JWT currentMemberId() scope | Anon sweep: 401 |
| 339 | `/api/coop/finance` | `-` | `ApiCoopController.finance()` (L1164): MerchantAccessService + OwnershipUtils/owner check + JWT currentMemberId() scope | Targeted: pA->200/200 (操作成功) / Anon sweep: 401 |
| 340 | `/api/coop/invite` | `-` | `ApiCoopController.invite()` (L272): MerchantAccessService + JWT currentMemberId() scope | Anon sweep: 401 |
| 341 | `/api/coop/invite/read` | `-` | `ApiCoopController.markInviteRead()` (L534): JWT currentMemberId() scope | Anon sweep: 401 |
| 342 | `/api/coop/invite/request` | `-` | `ApiCoopController.inviteRequest()` (L469): MerchantAccessService + OwnershipUtils/owner check + JWT currentMemberId() scope | Anon sweep: 401 |
| 343 | `/api/coop/invite/requests` | `-` | `ApiCoopController.inviteRequests()` (L479): MerchantAccessService + OwnershipUtils/owner check + JWT currentMemberId() scope | Anon sweep: 401 |
| 344 | `/api/coop/list` | `-` | `ApiCoopController.list()` (L1578): MerchantAccessService + JWT currentMemberId() scope | Anon sweep: 401 |
| 345 | `/api/coop/mybiz` | `-` | `ApiCoopController.mybiz()` (L1045): MerchantAccessService + JWT currentMemberId() scope | Anon sweep: 401 |
| 346 | `/api/coop/offer/withdraw` | `-` | `ApiCoopController.offerWithdraw()` (L859): MerchantAccessService + JWT currentMemberId() scope | Anon sweep: 401 |
| 347 | `/api/coop/perk-template/list` | `-` | `ApiCoopController.perkTemplateList()` (L753): MerchantAccessService + JWT currentMemberId() scope | Anon sweep: 401 |
| 348 | `/api/coop/perks/list` | `-` | `ApiCoopController.perksList()` (L989): JWT currentMemberId() scope | Anon sweep: 401 |
| 349 | `/api/coop/pool/list` | `-` | `ApiCoopPoolController.list()` (L83): OwnershipUtils/owner check + JWT currentMemberId() scope | Anon sweep: 401 |
| 350 | `/api/coop/pool/mine` | `-` | `ApiCoopPoolController.mine()` (L312): JWT currentMemberId() scope | Anon sweep: 401 |
| 351 | `/api/coop/pool/received` | `-` | `ApiCoopPoolController.received()` (L280): MerchantAccessService | Anon sweep: 401 |
| 352 | `/api/coop/review/summary` | `-` | `ApiCoopController.reviewSummary()` (L1033): MerchantAccessService + JWT currentMemberId() scope | Anon sweep: 401 |
| 353 | `/api/coop/supply-stations` | `GET` | `ApiCoopController.supplyStations()` (L489): MerchantAccessService + OwnershipUtils/owner check + JWT currentMemberId() scope | Anon sweep: 401 |
| 354 | `/api/coupon` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 355 | `/api/coupon/command-receipt` | `-` | `ApiCouponController.commandReceipt()` (L390): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 356 | `/api/coupon/mypublishlist` | `POST` | `ApiCouponController.myPublishCouponList()` (L107): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 357 | `/api/coupon/myrecvlist` | `-` | `ApiCouponController.myPublishCouponList()` (L418): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 358 | `/api/coupon/publish` | `-` | `ApiCouponController.publish()` (L160): JWT authenticated | Anon sweep: 401 |
| 359 | `/api/coupon/qr-token` | `-` | `ApiCouponController.qrToken()` (L517): OwnershipUtils/owner check + JWT currentMemberId() scope | Anon sweep: 401 |
| 360 | `/api/coupon/status` | `-` | `ApiCouponController.couponStatus()` (L565): OwnershipUtils/owner check + JWT currentMemberId() scope | Anon sweep: 401 |
| 361 | `/api/coupon/stop` | `-` | `ApiCouponController.stopIssuing()` (L301): JWT authenticated | Anon sweep: 401 |
| 362 | `/api/coupon/usability` | `-` | `ApiCouponController.couponUsability()` (L692): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 363 | `/api/coupon/verification` | `-` | `ApiCouponController.couponVerification()` (L608): OwnershipUtils/owner check + JWT currentMemberId() scope | Anon sweep: 401 |
| 364 | `/api/creativesquare` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 365 | `/api/creativesquare/action` | `-` | `ApiCreativeSquareController.retiredPostWrites()` (L97): @Anonymous (public endpoint; parameter/entity scoped where applicable) | Anon sweep: 401 |
| 366 | `/api/creativesquare/bookmark` | `-` | `ApiCreativeSquareController.retiredPostWrites()` (L97): @Anonymous (public endpoint; parameter/entity scoped where applicable) | Anon sweep: 401 |
| 367 | `/api/creativesquare/delete` | `POST` | `ApiCreativeSquareController.retiredPostWrites()` (L97): @Anonymous (public endpoint; parameter/entity scoped where applicable) | Anon sweep: 401 |
| 368 | `/api/creativesquare/info` | `GET,POST` | `ApiCreativeSquareController.retiredPostReads()` (L90): JWT authenticated | Anon sweep: 200/500 (Request method 'GET' is not support) |
| 369 | `/api/creativesquare/like` | `-` | `ApiCreativeSquareController.retiredPostWrites()` (L97): @Anonymous (public endpoint; parameter/entity scoped where applicable) | Anon sweep: 401 |
| 370 | `/api/creativesquare/list` | `POST` | `ApiCreativeSquareController.retiredPostReads()` (L90): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 410/410 (旧版广场接口已停用，请更新客户端后使用社区帖文) |
| 371 | `/api/creativesquare/report` | `-` | `ApiCreativeSquareController.retiredPostWrites()` (L97): @Anonymous (public endpoint; parameter/entity scoped where applicable) | Anon sweep: 401 |
| 372 | `/api/creator/analytics/post-game-outcome` | `-` | `ApiCreatorAnalyticsController.postGameOutcome()` (L20): JWT authenticated | Anon sweep: 401 |
| 373 | `/api/creator/apply` | `-` | `ApiCreatorCenterController.apply()` (L38): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 374 | `/api/creator/center` | `-` | `ApiCreatorCenterController.center()` (L26): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 375 | `/api/creator/feedback` | `-` | `ApiCreatorMaintenanceController.feedback()` (L23): JWT authenticated | Anon sweep: 401 |
| 376 | `/api/creator/maintenance/requests` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 377 | `/api/creator/maintenance/requests/{}` | `-` | `ApiCreatorMaintenanceController.openRequest()` (L49): JWT authenticated | Covered by controller/JWT default deny + static review |
| 378 | `/api/creator/maintenance/tickets` | `POST` | `ApiCreatorMaintenanceController.tickets()` (L30): JWT authenticated; `ApiCreatorMaintenanceController.open()` (L43): JWT authenticated | Anon sweep: 401 |
| 379 | `/api/creator/maintenance/tickets/{}` | `-` | `ApiCreatorMaintenanceController.ticket()` (L37): JWT authenticated | Covered by controller/JWT default deny + static review |
| 380 | `/api/creator/maintenance/tickets/{}/close` | `POST` | `ApiCreatorMaintenanceController.close()` (L55): JWT authenticated | Covered by controller/JWT default deny + static review |
| 381 | `/api/exploration-anniversary` | `-` | `ExplorationAnniversaryController.read()` (L12): JWT authenticated | Anon sweep: 401 |
| 382 | `/api/exploration-goals/cancel` | `-` | `PersonalExplorationGoalController.cancel()` (L37): JWT authenticated | Anon sweep: 401 |
| 383 | `/api/exploration-goals/create` | `-` | `PersonalExplorationGoalController.create()` (L31): JWT authenticated | Anon sweep: 401 |
| 384 | `/api/exploration-goals/detail` | `-` | `PersonalExplorationGoalController.detail()` (L35): JWT authenticated | Anon sweep: 401 |
| 385 | `/api/exploration-goals/list` | `-` | `PersonalExplorationGoalController.list()` (L36): JWT authenticated | Anon sweep: 401 |
| 386 | `/api/exploration-goals/receipt` | `-` | `PersonalExplorationGoalController.receipt()` (L34): JWT authenticated | Anon sweep: 401 |
| 387 | `/api/external-game` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 388 | `/api/external-game/catalog` | `-` | `ApiExternalGameController.catalog()` (L79): @Anonymous (public endpoint; parameter/entity scoped where applicable) | Anon sweep: 200/200 (操作成功) |
| 389 | `/api/external-game/catalog/{}` | `-` | `ApiExternalGameController.catalogDetail()` (L87): @Anonymous (public endpoint; parameter/entity scoped where applicable) | Covered by controller/JWT default deny + static review |
| 390 | `/api/external-game/catalog/{}/package` | `-` | `ApiExternalGameController.download()` (L95): @Anonymous (public endpoint; parameter/entity scoped where applicable) | Covered by controller/JWT default deny + static review |
| 391 | `/api/external-game/reports` | `POST` | `ApiExternalGameController.report()` (L115): JWT authenticated | Anon sweep: 401 |
| 392 | `/api/external-game/signing-key` | `-` | `ApiExternalGameController.signingKey()` (L109): @Anonymous (public endpoint; parameter/entity scoped where applicable) | Anon sweep: 200/200 (操作成功) |
| 393 | `/api/external-game/submissions` | `-` | `ApiExternalGameController.submit()` (L34): JWT authenticated | Anon sweep: 401 |
| 394 | `/api/external-game/submissions/mine` | `-` | `ApiExternalGameController.mine()` (L58): JWT authenticated | Anon sweep: 401 |
| 395 | `/api/external-game/submissions/{}` | `-` | `ApiExternalGameController.mineDetail()` (L65): JWT authenticated | Targeted: ->200/200 (操作成功) |
| 396 | `/api/external-game/submissions/{}/cancel` | `-` | `ApiExternalGameController.cancel()` (L71): JWT authenticated | Covered by controller/JWT default deny + static review |
| 397 | `/api/external-game/submissions/{}/resubmit` | `-` | `ApiExternalGameController.resubmit()` (L45): JWT authenticated | Covered by controller/JWT default deny + static review |
| 398 | `/api/free-explore/bundle` | `GET,POST` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 399 | `/api/free-explore/bundle/catalog` | `-` | `ApiFreeExploreBundleController.catalog()` (L44): JWT currentMemberId() scope | Anon sweep: 401 |
| 400 | `/api/free-explore/bundle/exit-decision` | `-` | `ApiFreeExploreBundleController.exitDecision()` (L104): MerchantAccessService + JWT currentMemberId() scope | Anon sweep: 401 |
| 401 | `/api/free-explore/bundle/exit-options` | `-` | `ApiFreeExploreBundleController.exitOptions()` (L92): MerchantAccessService + JWT currentMemberId() scope | Anon sweep: 401 |
| 402 | `/api/free-explore/bundle/order` | `-` | `ApiFreeExploreBundleController.order()` (L49): JWT currentMemberId() scope | Anon sweep: 401 |
| 403 | `/api/free-explore/bundle/order/receipt` | `-` | `ApiFreeExploreBundleController.orderReceipt()` (L55): JWT currentMemberId() scope | Anon sweep: 401 |
| 404 | `/api/free-explore/bundle/order/refund` | `-` | `ApiFreeExploreBundleExtrasController.refundProgress()` (L75): JWT authenticated + service-layer caller scope; `ApiFreeExploreBundleExtrasController.refund()` (L78): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 405 | `/api/free-explore/bundle/order/refund-quote` | `-` | `ApiFreeExploreBundleController.refundQuote()` (L60): JWT currentMemberId() scope | Anon sweep: 401 |
| 406 | `/api/free-explore/bundle/renewal-intent` | `-` | `ApiFreeExploreBundleExtrasController.renewal()` (L58): JWT authenticated + service-layer caller scope; `ApiFreeExploreBundleExtrasController.latestRenewal()` (L61): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 407 | `/api/free-explore/bundle/reservation` | `GET,POST` | `ApiFreeExploreBundleController.reserve()` (L65): JWT currentMemberId() scope | Anon sweep: 401 |
| 408 | `/api/free-explore/bundle/reservation/{}/cancel-quote` | `-` | `ApiFreeExploreBundleController.cancellationQuote()` (L86): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 409 | `/api/free-explore/bundle/review` | `-` | `ApiFreeExploreBundleExtrasController.review()` (L47): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 410 | `/api/free-explore/bundle/shelf` | `-` | `ApiFreeExploreBundleExtrasController.shelf()` (L27): JWT authenticated | Anon sweep: 401 |
| 411 | `/api/free-explore/bundle/transfer` | `-` | `ApiFreeExploreBundleExtrasController.transfers()` (L87): JWT authenticated + service-layer caller scope; `ApiFreeExploreBundleExtrasController.transferWrite()` (L92): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 412 | `/api/free-explore/bundle/voucher` | `-` | `ApiFreeExploreBundleExtrasController.voucher()` (L33): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 413 | `/api/free-explore/bundle/voucher/appeal` | `-` | `ApiFreeExploreBundleExtrasController.appeal()` (L64): JWT authenticated + service-layer caller scope; `ApiFreeExploreBundleExtrasController.appeals()` (L70): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 414 | `/api/friend` | `GET` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 415 | `/api/friend/accept` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 416 | `/api/friend/cancel` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 417 | `/api/friend/channel-invite` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 418 | `/api/friend/directory` | `-` | `ApiFriendController.directory()` (L156): JWT authenticated | Anon sweep: 401 |
| 419 | `/api/friend/directory/detail` | `-` | `ApiFriendController.directoryDetail()` (L165): JWT authenticated | Anon sweep: 401 |
| 420 | `/api/friend/group/delete` | `-` | `ApiFriendController.groupDelete()` (L126): JWT authenticated | Anon sweep: 401 |
| 421 | `/api/friend/group/list` | `GET,POST` | `ApiFriendController.groups()` (L133): JWT authenticated | Anon sweep: 401 |
| 422 | `/api/friend/group/save` | `-` | `ApiFriendController.groupSave()` (L119): JWT authenticated | Anon sweep: 401 |
| 423 | `/api/friend/list` | `-` | `ApiFriendController.list()` (L45): JWT authenticated | Anon sweep: 401 |
| 424 | `/api/friend/metadata` | `-` | `ApiFriendController.metadata()` (L105): JWT authenticated | Anon sweep: 401 |
| 425 | `/api/friend/metadata/receipt` | `-` | `ApiFriendController.metadataReceipt()` (L140): JWT authenticated | Anon sweep: 401 |
| 426 | `/api/friend/metadata/settle` | `-` | `ApiFriendController.metadataSettle()` (L148): JWT authenticated | Anon sweep: 401 |
| 427 | `/api/friend/progress` | `-` | `ApiFriendController.progress()` (L172): JWT authenticated | Anon sweep: 401 |
| 428 | `/api/friend/receipt` | `-` | `ApiFriendController.receipt()` (L74): JWT authenticated | Anon sweep: 401 |
| 429 | `/api/friend/reject` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 430 | `/api/friend/relationship` | `POST` | `ApiFriendController.relationship()` (L60): JWT authenticated | Anon sweep: 401 |
| 431 | `/api/friend/remark` | `-` | `ApiFriendController.remark()` (L112): JWT authenticated | Anon sweep: 401 |
| 432 | `/api/friend/removal/receipt` | `-` | `ApiFriendController.removalReceipt()` (L89): JWT authenticated | Anon sweep: 401 |
| 433 | `/api/friend/removal/settle` | `-` | `ApiFriendController.removalSettle()` (L97): JWT authenticated | Anon sweep: 401 |
| 434 | `/api/friend/remove` | `-` | `ApiFriendController.remove()` (L82): JWT authenticated | Anon sweep: 401 |
| 435 | `/api/friend/request` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 436 | `/api/friend/requests` | `-` | `ApiFriendController.requests()` (L52): JWT authenticated | Anon sweep: 401 |
| 437 | `/api/friend/team-invite` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 438 | `/api/fund/preflight/bank-withdrawal` | `-` | `ApiFundActionPreflightController.prepareBankWithdrawal()` (L29): JWT currentMemberId() scope | Anon sweep: 401 |
| 439 | `/api/fund/preflight/{}` | `POST` | Client prefix / alias or unmapped route (not a standalone controller handler) | Client prefix literal (covered by child routes) |
| 440 | `/api/fund/preflight/{}/confirm` | `-` | `ApiFundActionPreflightController.confirm()` (L40): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 441 | `/api/fund/preflight/{}/reject` | `-` | `ApiFundActionPreflightController.reject()` (L50): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 442 | `/api/game/session` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 443 | `/api/game/session/command` | `-` | `ApiGameSessionController.command()` (L97): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 444 | `/api/game/session/merchant/entries` | `-` | `ApiGameSessionController.merchantEntries()` (L71): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 445 | `/api/game/session/recap/export` | `-` | `ApiGameSessionController.exportRecap()` (L83): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 446 | `/api/game/session/receipt` | `-` | `ApiGameSessionController.receipt()` (L134): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 447 | `/api/game/session/shared/control` | `GET` | `ApiGameSessionController.sharedControl()` (L159): MerchantAccessService | Anon sweep: 401 |
| 448 | `/api/game/session/shared/export` | `GET` | `ApiGameSessionController.sharedExport()` (L172): MerchantAccessService | Anon sweep: 401 |
| 449 | `/api/game/session/shared/export/anchor` | `POST` | `ApiGameSessionController.sharedExportAnchor()` (L182): MerchantAccessService | Anon sweep: 401 |
| 450 | `/api/game/session/shared/preview` | `-` | `ApiGameSessionController.sharedPreview()` (L149): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 451 | `/api/game/session/view` | `GET,POST` | `ApiGameSessionController.view()` (L53): MerchantAccessService | Anon sweep: 401 |
| 452 | `/api/growth/center` | `-` | `ApiGrowthController.center()` (L27): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 453 | `/api/growth/leaderboard` | `-` | `ApiGrowthController.leaderboard()` (L39): JWT authenticated + service-layer caller scope | Targeted: ->200/500 (Request method 'GET' is not supported) / Anon sweep: 401 |
| 454 | `/api/growth/leaderboard/anonymous` | `-` | `ApiGrowthController.anonymousLeaderboard()` (L54): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 455 | `/api/guilds/{}/{}/channels` | `GET,PATCH` | `ApiChannelPlatformController.directory()` (L40): JWT authenticated; `ApiChannelPlatformController.create()` (L58): JWT authenticated | Targeted: pA->200/200 (操作成功); p0->403/403 (加入后才能查看频道) |
| 456 | `/api/guilds/{}/{}/channels/manage` | `-` | `ApiChannelPlatformController.manage()` (L50): JWT authenticated | Covered by controller/JWT default deny + static review |
| 457 | `/api/host-tip/config` | `POST` | `ApiHostTipController.upsertConfig()` (L45): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 458 | `/api/host-tip/config/mine` | `GET` | `ApiHostTipController.myConfigs()` (L51): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 459 | `/api/host-tip/intent` | `POST` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 460 | `/api/host-tip/intent/mine` | `GET` | `ApiHostTipController.listMine()` (L69): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 461 | `/api/host-tip/intent/{}/cancel` | `POST` | `ApiHostTipController.cancel()` (L63): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 462 | `/api/host-tip/prepare` | `POST` | `ApiHostTipController.prepare()` (L57): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 463 | `/api/im/appeals` | `GET,POST` | `ApiChannelGovernanceController.submitAppeal()` (L80): JWT authenticated; `ApiChannelGovernanceController.appeals()` (L87): JWT authenticated | Anon sweep: 401 |
| 464 | `/api/im/appeals/{}/evidence` | `POST` | `ApiChannelGovernanceController.evidence()` (L101): JWT authenticated | Covered by controller/JWT default deny + static review |
| 465 | `/api/im/appeals/{}/withdraw` | `POST` | `ApiChannelGovernanceController.withdraw()` (L108): JWT authenticated | Covered by controller/JWT default deny + static review |
| 466 | `/api/im/audit-log` | `GET` | `ApiChannelGapController.auditLog()` (L63): JWT authenticated | Anon sweep: 401 |
| 467 | `/api/im/block` | `POST` | `ApiImController.block()` (L294): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 468 | `/api/im/chain` | `POST` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 469 | `/api/im/chain/close` | `POST` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 470 | `/api/im/chain/create` | `GET,POST` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 471 | `/api/im/chain/info` | `GET` | `ApiChannelInteractionController.info()` (L40): JWT authenticated | Anon sweep: 401 |
| 472 | `/api/im/chain/join` | `GET,POST` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 473 | `/api/im/chain/withdraw` | `POST` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 474 | `/api/im/channel` | `GET` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 475 | `/api/im/channel/checkin` | `POST` | `ApiChannelInteractionController.checkin()` (L59): JWT authenticated | Anon sweep: 401 |
| 476 | `/api/im/channel/checkin-rank` | `GET` | `ApiChannelInteractionController.rank()` (L73): JWT authenticated | Anon sweep: 401 |
| 477 | `/api/im/channel/checkin-request` | `GET` | `ApiChannelInteractionController.checkinReceipt()` (L81): JWT authenticated | Anon sweep: 401 |
| 478 | `/api/im/channel/checkin-status` | `GET` | `ApiChannelInteractionController.status()` (L66): JWT authenticated | Anon sweep: 401 |
| 479 | `/api/im/conversations` | `-` | `ApiImController.conversations()` (L44): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 480 | `/api/im/delete` | `POST` | `ApiImController.delete()` (L202): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 481 | `/api/im/device/attempts` | `-` | `ApiChannelNotifyController.pushAttempts()` (L100): JWT authenticated | Anon sweep: 401 |
| 482 | `/api/im/file/message` | `GET` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 483 | `/api/im/file/message/{}/authorization` | `GET` | Client prefix / alias or unmapped route (not a standalone controller handler) | Covered by controller/JWT default deny + static review |
| 484 | `/api/im/file/message/{}/preview` | `GET` | Client prefix / alias or unmapped route (not a standalone controller handler) | Covered by controller/JWT default deny + static review |
| 485 | `/api/im/file/receipt` | `GET` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 486 | `/api/im/file/scan` | `GET,POST` | `ApiImMediaController.scan()` (L64): JWT authenticated | Anon sweep: 401 |
| 487 | `/api/im/file/upload` | `GET,POST` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 488 | `/api/im/interaction/request` | `GET` | `ApiChannelInteractionController.chainReceipt()` (L50): JWT authenticated | Anon sweep: 401 |
| 489 | `/api/im/members/{}/roles` | `POST` | `ApiChannelGovernanceController.memberRoles()` (L178): JWT authenticated | Covered by controller/JWT default deny + static review |
| 490 | `/api/im/message` | `GET` | `ApiImMediaController.message()` (L115): JWT authenticated | Anon sweep: 401 |
| 491 | `/api/im/message/context` | `-` | `ApiChannelMessageController.context()` (L67): JWT authenticated | Anon sweep: 401 |
| 492 | `/api/im/message/edit` | `-` | `ApiChannelMessageController.edit()` (L37): JWT authenticated | Anon sweep: 401 |
| 493 | `/api/im/message/mutations` | `-` | `ApiChannelMessageController.mutations()` (L58): JWT authenticated | Anon sweep: 401 |
| 494 | `/api/im/message/recall` | `-` | `ApiChannelMessageController.recall()` (L44): JWT authenticated | Anon sweep: 401 |
| 495 | `/api/im/message/request` | `-` | `ApiChannelMessageController.request()` (L51): JWT authenticated | Anon sweep: 401 |
| 496 | `/api/im/message/{}/attachment` | `GET` | `ApiChannelSurfaceController.attachment()` (L30): JWT authenticated | Covered by controller/JWT default deny + static review |
| 497 | `/api/im/message/{}/revisions` | `-` | `ApiChannelMessageController.revisions()` (L76): JWT authenticated | Covered by controller/JWT default deny + static review |
| 498 | `/api/im/messages` | `POST` | `ApiImController.messages()` (L64): JWT authenticated + service-layer caller scope | Targeted: pA->200/500 (Request method 'GET' is not supported); pA->200/403 (无权查看该会话) / Anon sweep: 401 |
| 499 | `/api/im/moderation` | `POST` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 500 | `/api/im/moderation/actions` | `GET` | `ApiChannelGovernanceController.actions()` (L56): JWT authenticated | Anon sweep: 401 |
| 501 | `/api/im/moderation/ban` | `POST` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 502 | `/api/im/moderation/kick` | `POST` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 503 | `/api/im/moderation/me` | `GET` | `ApiChannelGovernanceController.mine()` (L64): JWT authenticated | Anon sweep: 401 |
| 504 | `/api/im/moderation/timeout` | `POST` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 505 | `/api/im/moderation/{}/revoke` | `POST` | `ApiChannelGovernanceController.revoke()` (L49): JWT authenticated | Covered by controller/JWT default deny + static review |
| 506 | `/api/im/mute` | `POST` | `ApiChannelNotifyController.mute()` (L50): JWT authenticated; `ApiImController.mute()` (L227): JWT authenticated + service-layer caller scope | Targeted: pA->400/400 (body); pA->400/400 (body); ->400/400 (body) / Anon sweep: 401 |
| 507 | `/api/im/mute-receipt` | `GET` | `ApiChannelNotifyController.muteReceipt()` (L132): JWT authenticated | Anon sweep: 401 |
| 508 | `/api/im/mute-state` | `GET` | `ApiChannelNotifyController.muteState()` (L125): JWT authenticated | Anon sweep: 401 |
| 509 | `/api/im/notification-requests` | `POST` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 510 | `/api/im/notification-requests/{}` | `GET` | `ApiChannelNotifyController.receipt()` (L78): JWT authenticated | Covered by controller/JWT default deny + static review |
| 511 | `/api/im/notify-log` | `-` | `ApiChannelNotifyController.deliveryLog()` (L92): JWT authenticated | Anon sweep: 401 |
| 512 | `/api/im/notify-pref` | `GET` | `ApiChannelNotifyController.preferences()` (L35): JWT authenticated; `ApiChannelNotifyController.save()` (L43): JWT authenticated | Anon sweep: 401 |
| 513 | `/api/im/pin` | `-` | `ApiChannelMessageController.pin()` (L93): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 514 | `/api/im/pins` | `-` | `ApiChannelMessageController.pins()` (L86): JWT authenticated | Anon sweep: 401 |
| 515 | `/api/im/pins/sort` | `-` | `ApiChannelMessageController.sortPins()` (L107): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 516 | `/api/im/poll/close` | `-` | `ApiImPollController.close()` (L32): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 517 | `/api/im/poll/create` | `GET` | `ApiImPollController.create()` (L22): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 518 | `/api/im/poll/create-receipt` | `GET` | `ApiImPollController.createReceipt()` (L37): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 519 | `/api/im/poll/result` | `GET` | `ApiImPollController.result()` (L44): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 520 | `/api/im/poll/vote` | `GET` | `ApiImPollController.vote()` (L27): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 521 | `/api/im/read` | `POST` | `ApiChannelNotifyController.ack()` (L57): JWT authenticated; `ApiImController.read()` (L172): JWT authenticated + service-layer caller scope | Targeted: pA->400/400 (body); pA->400/400 (body); ->400/400 (body) / Anon sweep: 401 |
| 522 | `/api/im/read-receipt` | `GET` | `ApiChannelNotifyController.readReceipt()` (L116): JWT authenticated | Anon sweep: 401 |
| 523 | `/api/im/read-state` | `GET` | `ApiChannelNotifyController.readState()` (L109): JWT authenticated | Anon sweep: 401 |
| 524 | `/api/im/report` | `POST` | `ApiChannelGovernanceController.report()` (L118): JWT authenticated; `ApiImController.report()` (L367): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 525 | `/api/im/roles` | `GET,POST` | `ApiChannelGovernanceController.createRole()` (L157): JWT authenticated; `ApiChannelPlatformController.roles()` (L160): JWT authenticated | Anon sweep: 401 |
| 526 | `/api/im/roles/{}/members` | `POST` | `ApiChannelGovernanceController.roleMembers()` (L185): JWT authenticated | Covered by controller/JWT default deny + static review |
| 527 | `/api/im/route-share-source` | `POST` | `ApiImController.routeShareSource()` (L128): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 528 | `/api/im/search` | `-` | `ApiChannelMessageController.search()` (L114): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 529 | `/api/im/send` | `GET,POST` | `ApiImController.send()` (L98): JWT authenticated + service-layer caller scope | Targeted: pA->200/500 (内容不能为空); pA->200/500 (内容不能为空); ->200/500 (内容不能为空) / Anon sweep: 401 |
| 530 | `/api/im/send-receipt` | `GET,POST` | `ApiImController.sendReceipt()` (L147): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 531 | `/api/im/start` | `POST` | `ApiImController.start()` (L267): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 532 | `/api/im/unblock` | `POST` | `ApiImController.unblock()` (L322): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 533 | `/api/im/unpin` | `-` | `ApiChannelMessageController.unpin()` (L100): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 534 | `/api/im/unread-summary` | `GET` | `ApiChannelNotifyController.unreadSummary()` (L85): JWT authenticated | Anon sweep: 401 |
| 535 | `/api/im/unread-total` | `POST` | `ApiImController.unreadTotal()` (L252): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 536 | `/api/im/video/message` | `GET` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 537 | `/api/im/video/message/{}/authorization` | `GET` | Client prefix / alias or unmapped route (not a standalone controller handler) | Covered by controller/JWT default deny + static review |
| 538 | `/api/im/video/message/{}/preview` | `GET` | Client prefix / alias or unmapped route (not a standalone controller handler) | Covered by controller/JWT default deny + static review |
| 539 | `/api/im/video/policy` | `GET` | `ApiImMediaController.videoPolicy()` (L80): JWT authenticated | Anon sweep: 401 |
| 540 | `/api/im/video/receipt` | `GET` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 541 | `/api/im/video/upload` | `POST` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 542 | `/api/im/voice/message` | `GET` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 543 | `/api/im/voice/message/{}` | `GET` | `ApiImMediaController.voice()` (L101): JWT authenticated | Covered by controller/JWT default deny + static review |
| 544 | `/api/im/voice/message/{}/authorization` | `GET` | Client prefix / alias or unmapped route (not a standalone controller handler) | Covered by controller/JWT default deny + static review |
| 545 | `/api/im/voice/receipt` | `GET` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 546 | `/api/im/voice/upload` | `POST` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 547 | `/api/im/webhook` | `GET` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 548 | `/api/im/webhook/context` | `GET` | `ApiChannelGapController.webhookContext()` (L78): JWT authenticated | Anon sweep: 401 |
| 549 | `/api/im/webhook/subscriptions` | `POST` | `ApiChannelGapController.subscriptions()` (L87): JWT authenticated; `ApiChannelGapController.createSubscription()` (L96): JWT authenticated | Anon sweep: 401 |
| 550 | `/api/im/{}` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Covered by controller/JWT default deny + static review |
| 551 | `/api/invite/revoke` | `-` | `ApiChannelAdmissionController.revoke()` (L71): JWT authenticated | Anon sweep: 401 |
| 552 | `/api/invoice` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 553 | `/api/invoice/apply` | `GET` | `ApiUserInvoiceController.apply()` (L120): JWT currentMemberId() scope | Anon sweep: 401 |
| 554 | `/api/invoice/cancel` | `GET` | `ApiUserInvoiceController.cancel()` (L142): JWT currentMemberId() scope | Anon sweep: 401 |
| 555 | `/api/invoice/detail` | `-` | `ApiUserInvoiceController.detail()` (L135): JWT currentMemberId() scope | Targeted: pA->200/500 (发票申请不存在) / Anon sweep: 401 |
| 556 | `/api/invoice/list` | `-` | `ApiUserInvoiceController.list()` (L129): JWT currentMemberId() scope | Targeted: pA->200/200 (操作成功) / Anon sweep: 401 |
| 557 | `/api/invoice/orders/eligible` | `-` | `ApiUserInvoiceController.eligibleOrders()` (L114): JWT currentMemberId() scope | Anon sweep: 401 |
| 558 | `/api/invoice/request` | `GET` | `ApiUserInvoiceController.originalRequest()` (L149): JWT currentMemberId() scope | Anon sweep: 401 |
| 559 | `/api/invoice/titles` | `-` | `ApiUserInvoiceController.titles()` (L40): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 560 | `/api/invoice/titles/delete` | `GET` | `ApiUserInvoiceController.deleteTitle()` (L104): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 561 | `/api/invoice/titles/request` | `GET` | `ApiUserInvoiceController.titleSaveReceipt()` (L79): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 562 | `/api/invoice/titles/save` | `GET` | `ApiUserInvoiceController.saveTitle()` (L46): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 563 | `/api/invoice/titles/save-request` | `GET` | `ApiUserInvoiceController.saveTitleRequest()` (L57): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 564 | `/api/join-request/approve` | `-` | `ApiChannelAdmissionController.approve()` (L43): JWT authenticated | Anon sweep: 401 |
| 565 | `/api/join-request/cancel` | `-` | `ApiChannelAdmissionController.cancel()` (L36): JWT authenticated | Anon sweep: 401 |
| 566 | `/api/join-request/reject` | `-` | `ApiChannelAdmissionController.reject()` (L50): JWT authenticated | Anon sweep: 401 |
| 567 | `/api/join-request/submit` | `-` | `ApiChannelAdmissionController.submit()` (L29): JWT authenticated | Anon sweep: 401 |
| 568 | `/api/location-share/personal` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 569 | `/api/login` | `-` | `ApiLoginController.login()` (L123): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 200/500 (Request method 'GET' is not support) |
| 570 | `/api/login/apple` | `-` | `ApiLoginController.loginApple()` (L742): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 200/500 (Request method 'GET' is not support) |
| 571 | `/api/login/phone` | `-` | `ApiLoginController.loginPhone()` (L526): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 200/500 (Request method 'GET' is not support) |
| 572 | `/api/login/wechat/app` | `-` | `ApiLoginController.loginWechatApp()` (L602): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 200/500 (Request method 'GET' is not support) |
| 573 | `/api/logout` | `-` | `ApiLoginController.logout()` (L179): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 574 | `/api/map/geocode` | `POST` | `ApiMapController.geocode()` (L161): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 575 | `/api/map/nearby` | `POST` | `ApiMapController.nearby()` (L59): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 576 | `/api/map/place-search` | `POST` | `ApiMapController.placeSearch()` (L129): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 577 | `/api/map/public-layers` | `POST` | `appgaps/ApiAppGapsMapController.publicLayers()` (L46): JWT authenticated | Anon sweep: 401 |
| 578 | `/api/map/reverse-geocode` | `-` | `ApiMapController.reverseGeocode()` (L103): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 579 | `/api/map/weather` | `-` | `ApiMapController.weather()` (L148): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 200/500 (经纬度格式不正确) |
| 580 | `/api/marketing/affiliate/summary` | `GET` | `ApiPlayerAffiliateController.summary()` (L36): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 581 | `/api/marketing/attribution/claim` | `-` | `ApiMarketingAttributionController.claim()` (L47): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 582 | `/api/marketing/group-checkout/create` | `-` | `ApiMarketingGroupCheckoutController.create()` (L45): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 583 | `/api/marketing/group-checkout/quote` | `POST` | `ApiMarketingGroupCheckoutController.quote()` (L39): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 584 | `/api/marketing/product` | `GET` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 585 | `/api/marketing/product/activate` | `-` | `ApiMarketingProductController.activate()` (L94): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 586 | `/api/marketing/product/gifts/bought` | `-` | `ApiMarketingProductController.giftsBought()` (L106): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 587 | `/api/marketing/product/gifts/claim` | `POST` | `ApiMarketingProductController.claimGift()` (L100): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 588 | `/api/marketing/product/gifts/received` | `-` | `ApiMarketingProductController.giftsReceived()` (L112): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 589 | `/api/marketing/product/list` | `GET` | `ApiMarketingProductController.list()` (L49): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 590 | `/api/marketing/product/order` | `POST` | `ApiMarketingProductController.order()` (L69): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 591 | `/api/marketing/product/orders` | `-` | `ApiMarketingProductController.myOrders()` (L75): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 592 | `/api/marketing/product/quote` | `POST` | `ApiMarketingProductController.quote()` (L63): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 593 | `/api/marketing/product/{}` | `GET` | `ApiMarketingProductController.detail()` (L57): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 594 | `/api/marketing/promotions/catalog` | `GET` | `ApiMarketingPromotionController.catalog()` (L47): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 595 | `/api/marketing/referral/token` | `POST` | `ApiMarketingAttributionController.issueToken()` (L41): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 596 | `/api/marketing/revenue/author/mine` | `GET` | `ApiMarketingRevenueController.myAuthorAllocations()` (L44): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 597 | `/api/medal/wall` | `-` | `ApiMedalController.wall()` (L51): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 598 | `/api/media/asset/attribution` | `GET` | `ApiMediaAssetController.attributionByReference()` (L31): JWT authenticated | Anon sweep: 401 |
| 599 | `/api/media/asset/list` | `GET` | `ApiMediaAssetController.list()` (L14): JWT authenticated | Anon sweep: 401 |
| 600 | `/api/media/asset/{}` | `GET` | `ApiMediaAssetController.status()` (L15): JWT authenticated | Client prefix literal (covered by child routes) |
| 601 | `/api/member/reputation` | `-` | `ApiMemberAccountController.reputation()` (L55): JWT currentMemberId() scope | Anon sweep: 401 |
| 602 | `/api/merchant` | `POST` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 603 | `/api/merchant/access/me` | `POST` | `ApiMerchantOperatorController.accessMe()` (L41): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 604 | `/api/merchant/aftercare/detail` | `-` | `ApiMerchantAftercareController.detail()` (L38): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 605 | `/api/merchant/aftercare/list` | `-` | `ApiMerchantAftercareController.list()` (L24): SecurityConfig.permitAll (public config/health/landing) | Targeted: pA->200/500 (Request method 'GET' is not supported); pA->200/403 (商家身份无效或权限不足) / Anon sweep: 401 |
| 606 | `/api/merchant/aftercare/respond` | `-` | `ApiMerchantAftercareController.respond()` (L62): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 607 | `/api/merchant/authoring/draft-selection/list` | `-` | `ApiMerchantAuthoringController.draftSelectionList()` (L208): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 608 | `/api/merchant/authoring/draft-selection/resolve` | `-` | `ApiMerchantAuthoringController.draftSelectionResolve()` (L213): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 609 | `/api/merchant/authoring/drafts` | `-` | `ApiMerchantAuthoringController.drafts()` (L126): SecurityConfig.permitAll (public config/health/landing); `ApiMerchantAuthoringController.create()` (L139): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 610 | `/api/merchant/authoring/drafts/{}` | `-` | `ApiMerchantAuthoringController.draft()` (L144): SecurityConfig.permitAll (public config/health/landing); `ApiMerchantAuthoringController.save()` (L149): SecurityConfig.permitAll (public config/health/landing) | Client prefix literal (covered by child routes) |
| 611 | `/api/merchant/authoring/environment` | `-` | `ApiMerchantAuthoringController.environment()` (L194): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 612 | `/api/merchant/authoring/jobs` | `-` | `ApiMerchantAuthoringController.generate()` (L111): SecurityConfig.permitAll (public config/health/landing); `ApiMerchantAuthoringController.job()` (L116): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 613 | `/api/merchant/authoring/jobs/cancel` | `-` | `ApiMerchantAuthoringController.cancel()` (L121): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 614 | `/api/merchant/authoring/main-line` | `-` | `ApiMerchantAuthoringController.mainLine()` (L199): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 615 | `/api/merchant/authoring/player` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 616 | `/api/merchant/authoring/player/{}/game` | `-` | `ApiMerchantAuthoringController.playerGame()` (L220): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 617 | `/api/merchant/authoring/player/{}/requests/{}` | `-` | `ApiMerchantAuthoringController.playerRequest()` (L230): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 618 | `/api/merchant/authoring/public` | `-` | `ApiMerchantAuthoringController.published()` (L186): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 619 | `/api/merchant/authoring/releases` | `-` | `ApiMerchantAuthoringController.publish()` (L170): SecurityConfig.permitAll (public config/health/landing); `ApiMerchantAuthoringController.release()` (L181): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 620 | `/api/merchant/authoring/releases/{}/withdraw` | `-` | `ApiMerchantAuthoringController.withdraw()` (L176): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 621 | `/api/merchant/business-status` | `-` | `ApiMerchantController.businessStatus()` (L342): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 622 | `/api/merchant/business-status/update` | `-` | `ApiMerchantController.businessStatusUpdate()` (L359): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 623 | `/api/merchant/chapter-application/apply` | `-` | `ApiMerchantChapterApplicationController.apply()` (L50): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 624 | `/api/merchant/chapter-application/audit` | `-` | `ApiMerchantChapterApplicationController.audit()` (L175): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 625 | `/api/merchant/chapter-application/invitable` | `-` | `ApiMerchantChapterApplicationController.invitable()` (L98): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 626 | `/api/merchant/chapter-application/invite` | `-` | `ApiMerchantChapterApplicationController.invite()` (L117): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 627 | `/api/merchant/chapter-application/invite/respond` | `-` | `ApiMerchantChapterApplicationController.respondInvite()` (L141): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 628 | `/api/merchant/chapter-application/invite/revoke` | `-` | `ApiMerchantChapterApplicationController.revokeInvite()` (L158): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 629 | `/api/merchant/chapter-application/mine` | `-` | `ApiMerchantChapterApplicationController.mine()` (L231): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 630 | `/api/merchant/chapter-application/owner-list` | `-` | `ApiMerchantChapterApplicationController.ownerList()` (L198): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 631 | `/api/merchant/chapter-application/owner-mine` | `-` | `ApiMerchantChapterApplicationController.ownerMine()` (L216): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 632 | `/api/merchant/chapter-application/withdraw` | `-` | `ApiMerchantChapterApplicationController.withdraw()` (L76): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 633 | `/api/merchant/chapter-node/audit` | `-` | `ApiChapterMerchantNodeController.audit()` (L238): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 634 | `/api/merchant/chapter-node/live-checkin-code` | `-` | `ApiChapterMerchantNodeController.liveCheckinCode()` (L214): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 635 | `/api/merchant/chapter-node/mine` | `-` | `ApiChapterMerchantNodeController.mine()` (L122): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 636 | `/api/merchant/chapter-node/npc/detail` | `-` | `ApiChapterMerchantNodeController.npcDetail()` (L261): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 637 | `/api/merchant/chapter-node/npc/save` | `-` | `ApiChapterMerchantNodeController.npcSave()` (L279): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 638 | `/api/merchant/chapter-node/npc/voice/enroll` | `-` | `ApiChapterMerchantNodeController.npcVoiceEnroll()` (L297): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 639 | `/api/merchant/chapter-node/npc/voice/reset` | `-` | `ApiChapterMerchantNodeController.npcVoiceReset()` (L346): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 640 | `/api/merchant/chapter-node/npc/voice/status` | `-` | `ApiChapterMerchantNodeController.npcVoiceStatus()` (L316): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 641 | `/api/merchant/chapter-node/pending` | `-` | `ApiChapterMerchantNodeController.pending()` (L136): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 642 | `/api/merchant/chapter-node/poster-code` | `-` | `ApiChapterMerchantNodeController.posterCode()` (L175): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 643 | `/api/merchant/chapter-node/submit` | `-` | `ApiChapterMerchantNodeController.submit()` (L92): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 644 | `/api/merchant/city-node/claim` | `-` | `ApiMerchantNodeController.claim()` (L333): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 645 | `/api/merchant/city-node/claim/cancel` | `-` | `ApiMerchantNodeController.cancelClaim()` (L353): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 646 | `/api/merchant/city-node/claimable` | `-` | `ApiMerchantNodeController.claimable()` (L320): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 647 | `/api/merchant/city-node/list` | `-` | `ApiMerchantNodeController.list()` (L300): SecurityConfig.permitAll (public config/health/landing) | Targeted: pA->200/500 (Request method 'GET' is not supported); pA->200/403 (商家身份无效或权限不足) / Anon sweep: 401 |
| 648 | `/api/merchant/city-node/offline` | `-` | `ApiMerchantNodeController.offline()` (L414): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 649 | `/api/merchant/city-node/poster-code` | `-` | `ApiMerchantNodeController.posterCode()` (L387): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 650 | `/api/merchant/city-node/save` | `-` | `ApiMerchantNodeController.save()` (L238): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 651 | `/api/merchant/city-node/template/submit` | `-` | `ApiMerchantNodeController.templateSubmit()` (L173): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 652 | `/api/merchant/city-value/event/{}/benefit` | `-` | `ApiMerchantGapController.cityValueBenefit()` (L144): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 653 | `/api/merchant/city-value/events` | `GET` | `ApiMerchantGapController.cityValueEvents()` (L139): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 654 | `/api/merchant/city-value/report` | `GET` | `ApiMerchantGapController.cityValueReport()` (L129): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 655 | `/api/merchant/city-value/result/{}` | `-` | `ApiMerchantGapController.cityValueResult()` (L134): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 656 | `/api/merchant/claims/status` | `-` | `ApiMerchantClaimController.status()` (L29): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 657 | `/api/merchant/claims/submit` | `-` | `ApiMerchantClaimController.submit()` (L20): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 658 | `/api/merchant/clubs` | `-` | `ApiMerchantController.clubs()` (L1143): SecurityConfig.permitAll (public config/health/landing) | Targeted: pA->200/403 (商家身份无效或权限不足) / Anon sweep: 401 |
| 659 | `/api/merchant/commerce/capabilities` | `-` | `ApiMerchantCommerceController.capabilities()` (L31): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 660 | `/api/merchant/commerce/order` | `-` | `ApiMerchantCommerceController.createOrder()` (L50): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 661 | `/api/merchant/commerce/order/status` | `-` | `ApiMerchantCommerceController.orderStatus()` (L72): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 662 | `/api/merchant/coop-profile` | `-` | `ApiMerchantController.coopProfile()` (L617): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 663 | `/api/merchant/coop-profile/save` | `-` | `ApiMerchantController.saveCoopProfile()` (L709): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 664 | `/api/merchant/coupon-summary` | `-` | `ApiMerchantController.couponSummary()` (L1641): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 665 | `/api/merchant/crm/broadcast` | `GET,POST` | `ApiMerchantCrmController.broadcast()` (L146): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 666 | `/api/merchant/crm/broadcast/preview` | `GET,POST` | `ApiMerchantCrmController.broadcastPreview()` (L137): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 667 | `/api/merchant/crm/campaigns` | `GET,POST` | `ApiMerchantCrmController.createCampaign()` (L92): SecurityConfig.permitAll (public config/health/landing); `ApiMerchantCrmController.campaigns()` (L119): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 668 | `/api/merchant/crm/campaigns/coupons` | `GET` | `ApiMerchantCrmController.campaignCoupons()` (L74): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 669 | `/api/merchant/crm/campaigns/preview` | `GET,POST` | `ApiMerchantCrmController.campaignPreview()` (L83): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 670 | `/api/merchant/crm/campaigns/{}` | `GET` | `ApiMerchantCrmController.campaignDetail()` (L128): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 671 | `/api/merchant/crm/campaigns/{}/{}` | `POST` | Client prefix / alias or unmapped route (not a standalone controller handler) | Covered by controller/JWT default deny + static review |
| 672 | `/api/merchant/crm/customers/list` | `-` | `ApiMerchantCrmController.customers()` (L155): SecurityConfig.permitAll (public config/health/landing) | Targeted: pA->200/500 (Request method 'GET' is not supported); pA->200/403 (商家身份无效或权限不足) / Anon sweep: 401 |
| 673 | `/api/merchant/crm/customers/tags/batch` | `-` | `ApiMerchantCrmController.batchAssignTag()` (L229): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 674 | `/api/merchant/crm/customers/{}/contact` | `POST` | `ApiMerchantCrmController.revealContact()` (L182): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 675 | `/api/merchant/crm/customers/{}/detail` | `POST` | `ApiMerchantCrmController.detail()` (L173): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 676 | `/api/merchant/crm/customers/{}/notes` | `-` | `ApiMerchantCrmController.addNote()` (L194): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 677 | `/api/merchant/crm/customers/{}/notes/hide` | `-` | `ApiMerchantCrmController.hideNote()` (L204): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 678 | `/api/merchant/crm/customers/{}/tags` | `-` | `ApiMerchantCrmController.assignTag()` (L219): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 679 | `/api/merchant/crm/customers/{}/tags/remove` | `-` | `ApiMerchantCrmController.removeTag()` (L238): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 680 | `/api/merchant/crm/exports` | `GET,POST` | `ApiMerchantCrmController.createExport()` (L270): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 681 | `/api/merchant/crm/exports/{}/download` | `GET` | `ApiMerchantCrmController.downloadExport()` (L288): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 682 | `/api/merchant/crm/exports/{}/status` | `POST` | `ApiMerchantCrmController.exportStatus()` (L279): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 683 | `/api/merchant/crm/marketing-consents` | `GET` | `ApiMerchantCrmController.marketingConsents()` (L56): SecurityConfig.permitAll (public config/health/landing); `ApiMerchantCrmController.updateMarketingConsent()` (L65): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 684 | `/api/merchant/crm/marketing-consents/result` | `GET` | `ApiMerchantGapController.consentResult()` (L120): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 685 | `/api/merchant/crm/segments` | `GET,POST` | `ApiMerchantCrmController.saveSegment()` (L252): SecurityConfig.permitAll (public config/health/landing); `ApiMerchantCrmController.segments()` (L261): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 686 | `/api/merchant/dashboard` | `-` | `ApiMerchantController.getDashboard()` (L1332): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 687 | `/api/merchant/decor/save` | `-` | `ApiMerchantController.saveDecor()` (L746): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 688 | `/api/merchant/desk/anchor` | `-` | `ApiMerchantDeskController.anchor()` (L56): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 689 | `/api/merchant/desk/anchor/save` | `-` | `ApiMerchantDeskController.saveAnchor()` (L62): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 690 | `/api/merchant/desk/products` | `-` | `ApiMerchantDeskController.products()` (L74): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 691 | `/api/merchant/desk/products/detail` | `-` | `ApiMerchantDeskController.productDetail()` (L80): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 692 | `/api/merchant/desk/products/sku/save` | `-` | `ApiMerchantDeskController.saveSku()` (L99): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 693 | `/api/merchant/desk/receipt` | `-` | `ApiMerchantDeskController.receipt()` (L44): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 694 | `/api/merchant/desk/stores` | `-` | `ApiMerchantDeskController.stores()` (L50): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 695 | `/api/merchant/events` | `-` | `ApiMerchantController.events()` (L1673): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 696 | `/api/merchant/finance/overview` | `-` | `ApiMerchantController.financeOverview()` (L1954): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 697 | `/api/merchant/finance/public-transfer-batch-detail` | `-` | `ApiMerchantController.financePublicTransferBatchDetail()` (L2014): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 698 | `/api/merchant/finance/public-transfer-batches` | `-` | `ApiMerchantController.financePublicTransferBatches()` (L2001): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 699 | `/api/merchant/finance/redemption-detail` | `-` | `ApiMerchantController.financeRedemptionDetail()` (L1984): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 700 | `/api/merchant/finance/redemptions` | `-` | `ApiMerchantController.financeRedemptions()` (L1964): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 701 | `/api/merchant/finance/settlement-entries` | `-` | `ApiMerchantController.financeSettlementEntries()` (L2027): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 702 | `/api/merchant/info` | `-` | `ApiMerchantController.getUserInfo()` (L248): SecurityConfig.permitAll (public config/health/landing) | Targeted: pA->200/200 (操作成功) / Anon sweep: 401 |
| 703 | `/api/merchant/insight` | `-` | `ApiMerchantLegacyAliasController.insight()` (L19): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 704 | `/api/merchant/list` | `-` | `ApiMerchantController.merchantList()` (L524): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 705 | `/api/merchant/marketing-home` | `POST` | `ApiMerchantController.marketingHome()` (L1203): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 706 | `/api/merchant/merchant_registration` | `-` | `ApiMerchantController.merchantRegistration()` (L2089): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 707 | `/api/merchant/nearby` | `POST` | `ApiMerchantController.nearby()` (L302): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 708 | `/api/merchant/npc/avatar/generate` | `-` | `ApiMerchantGapController.avatarGenerate()` (L80): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 709 | `/api/merchant/npc/avatar/status` | `-` | `ApiMerchantGapController.avatarStatus()` (L85): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 710 | `/api/merchant/npc/knowledge-review` | `POST` | `ApiMerchantGapController.knowledgeReview()` (L90): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 711 | `/api/merchant/npc/knowledge-review/request` | `POST` | `ApiMerchantGapController.knowledgeRequest()` (L95): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 712 | `/api/merchant/npc/profile` | `POST` | `ApiMerchantController.npcProfile()` (L837): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 713 | `/api/merchant/npc/review` | `-` | `ApiMerchantController.npcReview()` (L956): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 714 | `/api/merchant/npc/save` | `-` | `ApiMerchantController.npcSave()` (L854): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 715 | `/api/merchant/npc/voice/enroll` | `-` | `ApiMerchantController.npcVoiceEnroll()` (L986): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 716 | `/api/merchant/npc/voice/reset` | `-` | `ApiMerchantController.npcVoiceReset()` (L1061): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 717 | `/api/merchant/npc/voice/revoke` | `-` | `ApiMerchantGapController.voiceRevoke()` (L75): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 718 | `/api/merchant/npc/voice/script` | `-` | `ApiMerchantGapController.voiceScript()` (L67): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 719 | `/api/merchant/npc/voice/status` | `-` | `ApiMerchantController.npcVoiceStatus()` (L1032): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 720 | `/api/merchant/offer-withdrawal/request` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 721 | `/api/merchant/operators/invite` | `-` | `ApiMerchantOperatorController.invite()` (L140): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 722 | `/api/merchant/operators/invite/accept` | `POST` | `ApiMerchantOperatorController.accept()` (L167): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 723 | `/api/merchant/operators/invite/preview` | `-` | `ApiMerchantOperatorController.invitePreview()` (L152): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 724 | `/api/merchant/operators/invite/revoke` | `-` | `ApiMerchantOperatorController.revokeInvite()` (L204): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 725 | `/api/merchant/operators/list` | `-` | `ApiMerchantOperatorController.team()` (L132): SecurityConfig.permitAll (public config/health/landing) | Targeted: pA->200/403 (商家身份无效或权限不足) / Anon sweep: 401 |
| 726 | `/api/merchant/operators/remove` | `-` | `ApiMerchantOperatorController.remove()` (L191): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 727 | `/api/merchant/operators/role` | `-` | `ApiMerchantOperatorController.updateRole()` (L178): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 728 | `/api/merchant/operators/roles` | `-` | `ApiMerchantOperatorController.roles()` (L112): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 729 | `/api/merchant/orders` | `-` | `ApiMerchantController.orders()` (L1814): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 730 | `/api/merchant/payout-account` | `POST` | `ApiMerchantPayoutAccountController.payoutAccount()` (L26): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 731 | `/api/merchant/payout-account/save` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 732 | `/api/merchant/poi/detail` | `-` | `ApiMerchantLegacyAliasController.poiDetail()` (L23): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 200/500 (Request method 'GET' is not support) |
| 733 | `/api/merchant/predict/inbox` | `-` | `ApiPredictSettleController.inbox()` (L139): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 734 | `/api/merchant/predict/settle` | `POST` | `ApiPredictSettleController.settle()` (L52): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 735 | `/api/merchant/public-detail` | `POST` | `ApiMerchantController.publicDetail()` (L478): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 200/500 (缺少商家ID) |
| 736 | `/api/merchant/public-home` | `POST` | `ApiMerchantController.publicHome()` (L388): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 200/500 (请提供商家ID或会员ID其中之一) |
| 737 | `/api/merchant/relation-home` | `-` | `ApiMerchantController.relationHome()` (L1175): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 738 | `/api/merchant/reviews/create` | `-` | `ApiMerchantReviewController.create()` (L56): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 739 | `/api/merchant/reviews/manage` | `-` | `ApiMerchantReviewController.manageList()` (L42): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 740 | `/api/merchant/reviews/public` | `POST` | `ApiMerchantReviewController.publicList()` (L29): @Anonymous + SecurityConfig.permitAll (public catalog/auth/share) | Anon sweep: 200/500 (Required request parameter 'merchan) |
| 741 | `/api/merchant/reviews/reply/receipt` | `-` | `ApiMerchantReviewReplyReceiptController.receipt()` (L17): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 742 | `/api/merchant/reviews/report` | `-` | `ApiMerchantReviewController.report()` (L104): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 743 | `/api/merchant/reviews/{}` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Covered by controller/JWT default deny + static review |
| 744 | `/api/merchant/share-code` | `-` | `ApiMerchantController.shareCode()` (L427): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 745 | `/api/merchant/shop/apply` | `-` | `ApiMerchantLegacyAliasController.shopApply()` (L26): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 746 | `/api/merchant/subscription` | `-` | `ApiMerchantController.mySubscription()` (L603): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 747 | `/api/merchant/todo-summary` | `-` | `ApiMerchantController.todoSummary()` (L1417): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 748 | `/api/merchant/upcoming-runs` | `GET` | `ApiMerchantController.upcomingRuns()` (L1624): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 749 | `/api/merchant/update` | `GET` | `ApiMerchantController.updateUserInfo()` (L1240): SecurityConfig.permitAll (public config/health/landing) | Targeted: pA->200/403 (商家身份无效或权限不足); m1->200/200 (更新成功); pA->200/403 (商家身份无效或权限不足) / Anon sweep: 401 |
| 750 | `/api/merchant/verification-records` | `GET` | `ApiMerchantController.verificationRecords()` (L1796): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 751 | `/api/mod-commerce` | `GET,POST` | `appgaps/ApiAppGapsWorkshopController.commerceOff()` (L52): JWT authenticated | Anon sweep: 401 |
| 752 | `/api/mod-installation` | `GET,POST` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 753 | `/api/mod-owned-report` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 754 | `/api/mod-owned-report/licenses` | `GET` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 755 | `/api/mod-settlement/mine` | `GET` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 756 | `/api/mod-settlement/mine/income` | `GET` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 757 | `/api/mod-upgrade` | `GET` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 758 | `/api/monthly-recap` | `GET` | `MonthlyRecapController.read()` (L12): JWT authenticated | Anon sweep: 401 |
| 759 | `/api/native/blacklist/current` | `GET` | `ApiNativeBlacklistController.current()` (L41): JWT authenticated | Anon sweep: 401 |
| 760 | `/api/native/blacklist/list` | `GET` | `ApiNativeBlacklistController.list()` (L34): JWT authenticated | Anon sweep: 401 |
| 761 | `/api/native/blacklist/receipt` | `-` | `ApiNativeBlacklistController.receipt()` (L55): JWT authenticated | Anon sweep: 401 |
| 762 | `/api/native/blacklist/settle` | `-` | `ApiNativeBlacklistController.settle()` (L63): JWT authenticated | Anon sweep: 401 |
| 763 | `/api/native/blacklist/unblock` | `-` | `ApiNativeBlacklistController.unblock()` (L48): JWT authenticated | Anon sweep: 401 |
| 764 | `/api/native/channels/read-through` | `-` | `ApiChannelMessageController.readThrough()` (L127): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 765 | `/api/native/device/enroll` | `-` | `ApiNativeDeviceController.enroll()` (L25): JWT authenticated | Anon sweep: 401 |
| 766 | `/api/native/device/enrollment-challenge` | `-` | `ApiNativeDeviceController.challenge()` (L21): JWT authenticated | Anon sweep: 401 |
| 767 | `/api/native/device/revoke` | `-` | `ApiNativeDeviceController.revoke()` (L29): JWT authenticated | Anon sweep: 401 |
| 768 | `/api/native/device/status` | `-` | `ApiNativeDeviceController.status()` (L17): JWT authenticated | Anon sweep: 401 |
| 769 | `/api/native/home` | `-` | `ApiNativeHomeController.home()` (L41): JWT authenticated; `ApiNativeHomeController.setHome()` (L47): JWT authenticated (+1 more mappings) | Anon sweep: 401 |
| 770 | `/api/native/home/return` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 771 | `/api/npc/chat/delete` | `DELETE` | `ApiMiscGapController.npcChatDelete()` (L68): JWT authenticated | Anon sweep: 401 |
| 772 | `/api/object-card/list` | `POST` | `ApiObjectCardController.list()` (L54): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 773 | `/api/object-card/rename` | `-` | `ApiObjectCardController.rename()` (L78): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 774 | `/api/official/active-for-roam` | `GET` | `ApiOfficialEventController.activeForRoam()` (L210): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 775 | `/api/official/broadcast` | `-` | `ApiOfficialEventController.broadcast()` (L432): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 776 | `/api/official/broadcast/{}/click` | `POST` | `ApiOfficialEventController.click()` (L262): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 777 | `/api/official/broadcast/{}/stats` | `-` | `ApiOfficialEventController.broadcastStats()` (L464): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 778 | `/api/official/can-publish` | `-` | `ApiOfficialEventController.canPublishInfo()` (L496): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 779 | `/api/official/command` | `POST` | `ApiOfficialEventController.command()` (L74): JWT authenticated | Anon sweep: 401 |
| 780 | `/api/official/command/cancel` | `POST` | `ApiOfficialEventController.commandCancel()` (L86): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 781 | `/api/official/command/query` | `POST` | `ApiOfficialEventController.commandQuery()` (L81): JWT authenticated | Anon sweep: 401 |
| 782 | `/api/official/events` | `GET` | `ApiOfficialEventController.events()` (L142): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 200/200 (操作成功) |
| 783 | `/api/official/events/{}` | `GET` | `ApiOfficialEventController.detail()` (L170): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 784 | `/api/official/events/{}/arrivals` | `-` | `ApiOfficialEventController.verifyArrival()` (L216): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 785 | `/api/official/events/{}/complete` | `-` | `ApiOfficialEventController.complete()` (L187): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 786 | `/api/official/events/{}/signup` | `-` | `ApiOfficialEventController.signup()` (L178): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 787 | `/api/official/invites` | `GET` | `ApiOfficialEventController.inviteMerchants()` (L273): JWT authenticated + service-layer caller scope; `ApiOfficialEventController.officialInvites()` (L292): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 788 | `/api/official/merchant-invites` | `-` | `ApiOfficialEventController.merchantInvites()` (L305): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 789 | `/api/official/my-events` | `-` | `ApiOfficialEventController.myEvents()` (L202): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 790 | `/api/official/my-published` | `GET` | `ApiOfficialEventController.myPublished()` (L485): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 791 | `/api/official/publish` | `-` | `ApiOfficialEventController.publish()` (L322): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 792 | `/api/official/signup-command` | `-` | `ApiOfficialEventController.signupCommand()` (L92): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 793 | `/api/official/signup-command/cancel` | `-` | `ApiOfficialEventController.signupCommandCancel()` (L102): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 794 | `/api/official/signup-command/query` | `-` | `ApiOfficialEventController.signupCommandQuery()` (L97): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 795 | `/api/official/v2/events` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 796 | `/api/official/v2/events/{}/publish-check` | `GET` | `ApiOfficialEventController.v2PublishCheck()` (L363): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 797 | `/api/official/v2/organizer-invites/{}/{}` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Covered by controller/JWT default deny + static review |
| 798 | `/api/official/v2/parties/{}/{}` | `-` | `ApiOfficialEventController.respondParty()` (L224): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 799 | `/api/official/v2/party-inbox` | `-` | `ApiOfficialEventController.partyInbox()` (L254): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 800 | `/api/onboarding/get` | `-` | `ApiChannelSurfaceController.onboardingJson()` (L44): JWT authenticated; `ApiChannelSurfaceController.onboarding()` (L51): JWT authenticated | Anon sweep: 401 |
| 801 | `/api/onboarding/submit` | `-` | `ApiChannelSurfaceController.submit()` (L58): JWT authenticated | Anon sweep: 401 |
| 802 | `/api/order-center/detail` | `-` | `ApiOrderCenterController.detail()` (L17): JWT authenticated | Anon sweep: 401 |
| 803 | `/api/orient/safety/check` | `POST` | `ApiOrientController.safetyCheck()` (L155): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 804 | `/api/orient/scoring/appeal` | `-` | `ApiOrientScoringController.appeal()` (L74): JWT currentMemberId() scope | Anon sweep: 401 |
| 805 | `/api/orient/scoring/dispute` | `-` | `ApiOrientScoringController.dispute()` (L83): JWT currentMemberId() scope | Anon sweep: 401 |
| 806 | `/api/orient/scoring/mine` | `-` | `ApiMemberAccountController.myAppeals()` (L41): JWT currentMemberId() scope | Anon sweep: 401 |
| 807 | `/api/orient/scoring/receipt` | `-` | `ApiOrientScoringController.receipt()` (L92): JWT currentMemberId() scope | Anon sweep: 401 |
| 808 | `/api/orient/scoring/session` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 809 | `/api/orient/scoring/session/{}` | `-` | `ApiOrientScoringController.breakdown()` (L34): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 810 | `/api/orient/scoring/session/{}/leaderboard` | `-` | `ApiOrientScoringController.leaderboard()` (L50): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 811 | `/api/orient/scoring/session/{}/settle` | `-` | `ApiOrientScoringController.settle()` (L66): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 812 | `/api/orient/scoring/session/{}/sync` | `POST` | `ApiOrientScoringController.sync()` (L58): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 813 | `/api/orient/task/alternative` | `POST` | `ApiOrientController.configureAlternative()` (L56): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 814 | `/api/orient/task/alternative/receipt` | `-` | `ApiOrientTaskReceiptController.receipt()` (L18): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 815 | `/api/orient/task/alternative/submit` | `-` | `ApiOrientController.submitAlternative()` (L81): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 816 | `/api/orient/task/state` | `-` | `ApiOrientController.taskState()` (L70): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 817 | `/api/orient/team/detail` | `GET` | `ApiOrientController.teamDetail()` (L135): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 818 | `/api/orient/team/lobby` | `-` | `ApiOrientController.teamLobby()` (L125): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 819 | `/api/orient/team/ranking` | `GET` | `ApiOrientController.teamRanking()` (L145): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 820 | `/api/pay/create` | `-` | `ApiMiscGapController.payCreate()` (L99): JWT authenticated | Anon sweep: 401 |
| 821 | `/api/personal-exploration-export/page` | `-` | `PersonalExplorationExportController.page()` (L34): JWT authenticated | Anon sweep: 401 |
| 822 | `/api/personal-exploration-export/prepare` | `-` | `PersonalExplorationExportController.prepare()` (L33): JWT authenticated | Anon sweep: 401 |
| 823 | `/api/platform-pass` | `-` | `ApiPlatformPassController.overview()` (L38): OwnershipUtils/owner check | Anon sweep: 401 |
| 824 | `/api/platform-pass/library` | `-` | `ApiPlatformPassController.library()` (L44): OwnershipUtils/owner check | Anon sweep: 401 |
| 825 | `/api/platform-pass/orders` | `-` | `ApiPlatformPassController.create()` (L51): OwnershipUtils/owner check; `ApiPlatformPassController.orders()` (L59): OwnershipUtils/owner check | Anon sweep: 401 |
| 826 | `/api/platform-pass/orders/{}` | `-` | `ApiPlatformPassController.order()` (L65): OwnershipUtils/owner check | Covered by controller/JWT default deny + static review |
| 827 | `/api/play` | `GET,POST` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 828 | `/api/play/action-context` | `GET` | `ApiPlayProgressController.basicActionContext()` (L114): JWT currentMemberId() scope | Anon sweep: 401 |
| 829 | `/api/play/action-receipt` | `-` | `ApiPlayProgressController.basicActionReceipt()` (L119): JWT currentMemberId() scope | Anon sweep: 401 |
| 830 | `/api/play/advanced/action` | `POST` | `ApiAdvancedPlayController.action()` (L80): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 831 | `/api/play/advanced/leaderboard` | `GET` | `ApiAdvancedPlayController.leaderboard()` (L147): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 832 | `/api/play/advanced/receipt` | `GET` | `ApiAdvancedPlayController.receipt()` (L131): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 833 | `/api/play/advanced/start` | `POST` | `ApiAdvancedPlayController.start()` (L43): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 834 | `/api/play/advanced/state` | `POST` | `ApiAdvancedPlayController.state()` (L137): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 835 | `/api/play/advanced/time-window` | `-` | `ApiAdvancedPlayController.timeWindow()` (L115): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 836 | `/api/play/answer` | `POST` | `ApiPlayProgressController.answer()` (L159): JWT currentMemberId() scope | Anon sweep: 401 |
| 837 | `/api/play/arrive` | `-` | `ApiPlayProgressController.arrive()` (L271): JWT currentMemberId() scope | Anon sweep: 401 |
| 838 | `/api/play/character` | `-` | `ApiPlayCharacterController.character()` (L30): JWT currentMemberId() scope | Anon sweep: 401 |
| 839 | `/api/play/check` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 840 | `/api/play/check/receipt` | `GET` | `ApiPlayCheckController.receipt()` (L145): JWT currentMemberId() scope | Anon sweep: 401 |
| 841 | `/api/play/check/reroll` | `-` | `ApiPlayCheckController.reroll()` (L62): JWT currentMemberId() scope | Anon sweep: 401 |
| 842 | `/api/play/check/roll` | `-` | `ApiPlayCheckController.roll()` (L45): JWT currentMemberId() scope | Anon sweep: 401 |
| 843 | `/api/play/check/settle` | `-` | `ApiPlayCheckController.settle()` (L79): JWT currentMemberId() scope | Anon sweep: 401 |
| 844 | `/api/play/checkin` | `-` | `ApiPlayProgressController.checkin()` (L126): JWT currentMemberId() scope | Anon sweep: 401 |
| 845 | `/api/play/city-orientation` | `GET` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 846 | `/api/play/city-orientation/answer` | `POST` | `ApiAppGapsPlayerController.orientationAnswer()` (L26): JWT authenticated | Anon sweep: 401 |
| 847 | `/api/play/city-orientation/receipt` | `GET` | `ApiAppGapsPlayerController.orientationReceipt()` (L31): JWT authenticated | Anon sweep: 401 |
| 848 | `/api/play/companionLine` | `-` | `ApiPlayProgressController.companionLine()` (L108): JWT currentMemberId() scope | Anon sweep: 401 |
| 849 | `/api/play/contact` | `GET` | `ApiMemberAccountController.contact()` (L29): JWT currentMemberId() scope | Anon sweep: 401 |
| 850 | `/api/play/content-manifest` | `-` | `ApiPlayProgressController.contentManifest()` (L60): JWT currentMemberId() scope | Anon sweep: 401 |
| 851 | `/api/play/egg/collect` | `-` | `ApiPlayerGapsController.collectEgg()` (L44): JWT authenticated | Anon sweep: 401 |
| 852 | `/api/play/encounter` | `GET` | `ApiPlayEncounterController.encounter()` (L27): JWT currentMemberId() scope | Anon sweep: 401 |
| 853 | `/api/play/ending` | `-` | `ApiPlayProgressController.ending()` (L92): JWT currentMemberId() scope | Anon sweep: 401 |
| 854 | `/api/play/feedback/submit` | `GET,POST` | `ApiPlayFeedbackController.submit()` (L36): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 855 | `/api/play/growth` | `-` | `ApiPlayProgressController.growth()` (L362): JWT currentMemberId() scope | Anon sweep: 401 |
| 856 | `/api/play/help` | `-` | `ApiPlayHelpController.view()` (L64): JWT currentMemberId() scope | Anon sweep: 401 |
| 857 | `/api/play/help/advanced/receipt` | `-` | `ApiPlayHelpController.receiptAdvanced()` (L120): JWT currentMemberId() scope | Anon sweep: 401 |
| 858 | `/api/play/help/receipt` | `-` | `ApiPlayHelpController.receipt()` (L109): JWT currentMemberId() scope | Anon sweep: 401 |
| 859 | `/api/play/help/shared` | `POST` | `ApiPlayHelpController.shared()` (L46): JWT currentMemberId() scope | Anon sweep: 401 |
| 860 | `/api/play/help/skip` | `-` | `ApiPlayHelpController.skip()` (L95): JWT currentMemberId() scope | Anon sweep: 401 |
| 861 | `/api/play/help/use` | `-` | `ApiPlayHelpController.use()` (L75): JWT currentMemberId() scope | Anon sweep: 401 |
| 862 | `/api/play/hint/unlock` | `-` | `ApiPlayProgressController.unlockHint()` (L376): JWT currentMemberId() scope | Anon sweep: 401 |
| 863 | `/api/play/history` | `GET` | `ApiPlayProgressController.history()` (L100): JWT currentMemberId() scope | Anon sweep: 401 |
| 864 | `/api/play/journey/ask` | `-` | `ApiPlayJourneyActionController.ask()` (L46): JWT currentMemberId() scope | Anon sweep: 401 |
| 865 | `/api/play/journey/backpack` | `-` | `ApiPlayJourneyReadController.backpack()` (L44): JWT currentMemberId() scope | Anon sweep: 401 |
| 866 | `/api/play/journey/casebook` | `-` | `ApiPlayJourneyReadController.casebook()` (L31): JWT currentMemberId() scope | Anon sweep: 401 |
| 867 | `/api/play/journey/ending` | `-` | `ApiPlayJourneyController.ending()` (L40): JWT currentMemberId() scope | Anon sweep: 401 |
| 868 | `/api/play/journey/opening/complete` | `-` | `ApiPlayJourneyActionController.completeOpening()` (L168): JWT currentMemberId() scope | Anon sweep: 401 |
| 869 | `/api/play/journey/stage-end` | `-` | `ApiPlayJourneyController.stageEnd()` (L27): JWT currentMemberId() scope | Anon sweep: 401 |
| 870 | `/api/play/journey/thought/sync` | `-` | `ApiPlayJourneyActionController.syncThoughts()` (L101): JWT currentMemberId() scope | Anon sweep: 401 |
| 871 | `/api/play/leaderboard` | `-` | `ApiPlayProgressController.leaderboard()` (L355): JWT currentMemberId() scope | Anon sweep: 401 |
| 872 | `/api/play/my-completed` | `GET` | `ApiPlayProgressController.myCompleted()` (L342): JWT currentMemberId() scope | Targeted: ->200/500 (Request method 'GET' is not supported) / Anon sweep: 401 |
| 873 | `/api/play/nodes` | `GET,POST` | `ApiPlayProgressController.nodes()` (L47): JWT currentMemberId() scope | Anon sweep: 401 |
| 874 | `/api/play/orient/session` | `-` | `ApiOrientSessionController.view()` (L112): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 875 | `/api/play/orient/session/abandon` | `-` | `ApiOrientSessionController.abandon()` (L92): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 876 | `/api/play/orient/session/current` | `-` | `ApiOrientSessionController.current()` (L106): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 877 | `/api/play/orient/session/prepare` | `-` | `ApiOrientSessionController.prepare()` (L46): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 878 | `/api/play/orient/session/receipt` | `-` | `ApiOrientSessionController.receipt()` (L118): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 879 | `/api/play/orient/session/start` | `-` | `ApiOrientSessionController.start()` (L61): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 880 | `/api/play/orient/session/task/{}/result` | `-` | `ApiOrientSessionController.taskResult()` (L98): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 881 | `/api/play/os/{}` | `-` | `ApiPlayProgressController.newLifeOperatingSystem()` (L254): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 882 | `/api/play/photo` | `-` | `ApiPlayProgressController.photo()` (L323): JWT currentMemberId() scope | Anon sweep: 401 |
| 883 | `/api/play/player-journey/choices` | `GET` | `ApiPlayerJourneyController.choices()` (L43): JWT currentMemberId() scope | Anon sweep: 401 |
| 884 | `/api/play/player-journey/memory` | `GET` | `ApiPlayerJourneyController.memory()` (L55): JWT currentMemberId() scope | Anon sweep: 401 |
| 885 | `/api/play/player-journey/report` | `GET` | `ApiPlayerJourneyController.report()` (L37): JWT currentMemberId() scope | Anon sweep: 401 |
| 886 | `/api/play/player-journey/seats` | `GET` | `ApiPlayerJourneyController.seats()` (L49): JWT currentMemberId() scope | Anon sweep: 401 |
| 887 | `/api/play/preference/{}` | `-` | `ApiPlayProgressController.preference()` (L214): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 888 | `/api/play/preference/{}/submit` | `-` | `ApiPlayProgressController.submitPreference()` (L222): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 889 | `/api/play/puzzle/hint` | `-` | `ApiPlayProgressController.puzzleHint()` (L207): JWT currentMemberId() scope | Anon sweep: 401 |
| 890 | `/api/play/puzzle/reveal` | `-` | `ApiPlayProgressController.revealPuzzle()` (L187): JWT currentMemberId() scope | Anon sweep: 401 |
| 891 | `/api/play/route-action` | `GET` | `ApiPlayerGapsController.routeAction()` (L37): JWT authenticated | Anon sweep: 401 |
| 892 | `/api/play/route-state` | `GET` | `ApiPlayProgressController.routeState()` (L79): JWT currentMemberId() scope | Anon sweep: 401 |
| 893 | `/api/play/rpg/status` | `-` | `ApiPlayRpgController.status()` (L31): JWT currentMemberId() scope | Anon sweep: 401 |
| 894 | `/api/play/run-session` | `-` | `ApiPlayRunSessionController.read()` (L78): JWT authenticated | Anon sweep: 401 |
| 895 | `/api/play/run-session/clear` | `-` | `ApiPlayRunSessionController.clear()` (L87): JWT authenticated | Anon sweep: 401 |
| 896 | `/api/play/run-session/list` | `-` | `ApiPlayRunSessionController.list()` (L99): JWT authenticated | Anon sweep: 401 |
| 897 | `/api/play/run-session/save` | `-` | `ApiPlayRunSessionController.save()` (L54): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 898 | `/api/play/scan-entry` | `-` | `ApiPlayProgressController.scanEntry()` (L151): JWT currentMemberId() scope | Anon sweep: 401 |
| 899 | `/api/play/seat-invitations` | `-` | `ApiRegistrationSeatInvitationController.list()` (L44): OwnershipUtils/owner check | Anon sweep: 401 |
| 900 | `/api/play/seat-invitations/claim` | `POST` | `ApiRegistrationSeatInvitationController.claim()` (L56): OwnershipUtils/owner check | Anon sweep: 401 |
| 901 | `/api/play/seat-invitations/invite` | `POST` | `ApiRegistrationSeatInvitationController.invite()` (L38): OwnershipUtils/owner check | Anon sweep: 401 |
| 902 | `/api/play/seat-invitations/{}` | `GET` | `ApiRegistrationSeatInvitationController.status()` (L50): OwnershipUtils/owner check | Covered by controller/JWT default deny + static review |
| 903 | `/api/play/seat-invitations/{}/cancel` | `POST` | `ApiRegistrationSeatInvitationController.cancel()` (L62): OwnershipUtils/owner check | Covered by controller/JWT default deny + static review |
| 904 | `/api/play/sensor-result` | `-` | `ApiPlayProgressController.sensorResult()` (L262): JWT currentMemberId() scope | Anon sweep: 401 |
| 905 | `/api/play/setup` | `GET` | `ApiTopicPlaySetupController.playerView()` (L80): JWT currentMemberId() scope | Anon sweep: 401 |
| 906 | `/api/play/tag/{}/confirm` | `-` | `ApiPlayProgressController.confirmPreferenceTag()` (L230): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 907 | `/api/play/tag/{}/correct` | `-` | `ApiPlayProgressController.correctPreferenceTag()` (L246): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 908 | `/api/play/tag/{}/revoke` | `-` | `ApiPlayProgressController.revokePreferenceTag()` (L238): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 909 | `/api/play/theme` | `GET` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 910 | `/api/play/theme/action` | `-` | `ApiThemeRuntimeController.action()` (L27): JWT authenticated | Anon sweep: 401 |
| 911 | `/api/play/theme/card` | `-` | `ApiThemeRuntimeController.card()` (L58): JWT authenticated | Anon sweep: 401 |
| 912 | `/api/play/theme/gameplay-action` | `POST` | `ApiThemeRuntimeController.gameplayAction()` (L44): JWT authenticated | Anon sweep: 401 |
| 913 | `/api/play/theme/gameplay-preview` | `-` | `ApiThemeRuntimeController.gameplayPreview()` (L36): JWT authenticated | Anon sweep: 401 |
| 914 | `/api/play/theme/history` | `-` | `ApiThemeRuntimeController.historyPage()` (L60): JWT authenticated | Anon sweep: 401 |
| 915 | `/api/play/theme/replay` | `POST` | `ApiThemeRuntimeController.replay()` (L56): JWT authenticated | Anon sweep: 401 |
| 916 | `/api/play/theme/state` | `GET` | `ApiThemeRuntimeController.state()` (L22): JWT authenticated | Anon sweep: 401 |
| 917 | `/api/play/theme/support-matrix` | `-` | `ApiThemeRuntimeController.supportMatrix()` (L17): JWT authenticated | Anon sweep: 401 |
| 918 | `/api/points/list` | `POST` | `ApiPointsController.myPointsList()` (L70): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 919 | `/api/points/result_list` | `POST` | `ApiPointsController.resultList()` (L40): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 920 | `/api/preset/gameplay/draft` | `-` | `ApiPresetController.createGameplayDraft()` (L131): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 921 | `/api/preset/gameplay/info` | `POST` | `ApiPresetController.gameplayInfo()` (L110): JWT authenticated | Anon sweep: 401 |
| 922 | `/api/preset/gameplay/list` | `POST` | `ApiPresetController.gameplayList()` (L98): JWT authenticated | Anon sweep: 401 |
| 923 | `/api/preset/route/info` | `-` | `ApiPresetController.routeInfo()` (L67): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 200/500 (Required request parameter 'id' for) |
| 924 | `/api/preset/route/list` | `-` | `ApiPresetController.routeList()` (L59): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 200/200 (操作成功) |
| 925 | `/api/privacy/progress` | `GET,POST` | `ApiProgressPrivacyController.read()` (L33): JWT authenticated; `ApiProgressPrivacyController.save()` (L41): JWT authenticated | Anon sweep: 401 |
| 926 | `/api/product/info` | `POST` | `ApiProductController.producInfo()` (L87): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 927 | `/api/product/list` | `POST` | `ApiProductController.productList()` (L55): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 200/200 (操作成功) |
| 928 | `/api/progress/meter` | `-` | `ApiChannelProgressController.meter()` (L105): JWT authenticated | Anon sweep: 401 |
| 929 | `/api/progress/requests` | `-` | `ApiProgressPrivacyController.settingsReceipt()` (L63): JWT authenticated | Anon sweep: 401 |
| 930 | `/api/progress/requests/{}` | `-` | `ApiProgressPrivacyController.progressReceipt()` (L70): JWT authenticated | Covered by controller/JWT default deny + static review |
| 931 | `/api/project/home` | `POST` | `ApiProjectController.home()` (L91): MerchantAccessService | Anon sweep: 401 |
| 932 | `/api/project/my` | `POST` | `ApiProjectController.my()` (L201): MerchantAccessService | Anon sweep: 401 |
| 933 | `/api/project/players` | `-` | `ApiProjectController.players()` (L149): MerchantAccessService + JWT currentMemberId() scope | Anon sweep: 401 |
| 934 | `/api/project/state-requests/context` | `-` | `appgaps/ApiAppGapsOriginalRequestController.stateContext()` (L100): JWT authenticated | Anon sweep: 401 |
| 935 | `/api/project/state-requests/read` | `-` | `appgaps/ApiAppGapsOriginalRequestController.stateRead()` (L115): JWT authenticated | Anon sweep: 401 |
| 936 | `/api/project/state-requests/submit` | `-` | `appgaps/ApiAppGapsOriginalRequestController.stateSubmit()` (L107): JWT authenticated | Anon sweep: 401 |
| 937 | `/api/public-catalog/city/list` | `-` | `ApiPublicContentCatalogController.cityList()` (L62): @Anonymous (public endpoint; parameter/entity scoped where applicable) | Anon sweep: 200/500 (Request method 'GET' is not support) |
| 938 | `/api/public-catalog/filters` | `-` | `ApiPublicContentCatalogController.filters()` (L192): @Anonymous (public endpoint; parameter/entity scoped where applicable) | Anon sweep: 200/500 (Request method 'GET' is not support) |
| 939 | `/api/public-catalog/ids` | `-` | `ApiPublicContentCatalogController.byIds()` (L152): @Anonymous (public endpoint; parameter/entity scoped where applicable) | Anon sweep: 200/500 (Request method 'GET' is not support) |
| 940 | `/api/public-catalog/info` | `-` | `ApiPublicContentCatalogController.info()` (L180): @Anonymous (public endpoint; parameter/entity scoped where applicable) | Anon sweep: 200/500 (Request method 'GET' is not support) |
| 941 | `/api/public-catalog/list` | `-` | `ApiPublicContentCatalogController.list()` (L95): JWT authenticated + service-layer caller scope | Anon sweep: 200/500 (Request method 'GET' is not support) |
| 942 | `/api/public-catalog/map` | `-` | `ApiPublicContentCatalogController.map()` (L128): @Anonymous (public endpoint; parameter/entity scoped where applicable) | Anon sweep: 200/500 (Request method 'GET' is not support) |
| 943 | `/api/publish/home` | `POST` | `ApiPublishController.home()` (L60): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 944 | `/api/publisher/identity` | `POST` | `ApiMemberIdentityController.register()` (L42): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 945 | `/api/publisher/identity/status` | `POST` | `ApiMemberIdentityController.status()` (L63): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 946 | `/api/rank/list` | `GET` | `ApiScopedLeaderboardController.list()` (L33): JWT authenticated | Anon sweep: 401 |
| 947 | `/api/referral/landing/gift` | `POST` | `ApiReferralLandingController.gift()` (L49): @Anonymous (public endpoint; parameter/entity scoped where applicable) | Anon sweep: 200/200 (操作成功) |
| 948 | `/api/referral/landing/referral` | `POST` | `ApiReferralLandingController.referral()` (L38): @Anonymous (public endpoint; parameter/entity scoped where applicable) | Anon sweep: 200/200 (操作成功) |
| 949 | `/api/registration/cancel` | `-` | `ApiRegistrationController.cancelRegistration()` (L299): JWT authenticated + service-layer caller scope | Targeted: pA->200/500 (无权取消他人报名); pA->200/500 (无权取消他人报名) / Anon sweep: 401 |
| 950 | `/api/registration/cancel-by-owner` | `-` | `ApiRegistrationController.cancelByOwner()` (L361): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 951 | `/api/registration/cancel-refund` | `-` | `ApiRegistrationController.cancelRefund()` (L332): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 952 | `/api/registration/count_by_date` | `-` | `ApiRegistrationController.countByDate()` (L449): JWT authenticated | Anon sweep: 401 |
| 953 | `/api/registration/create` | `POST` | `ApiRegistrationController.createRegistration()` (L138): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 954 | `/api/registration/explore-completion` | `POST` | `ApiRegistrationController.exploreCompletion()` (L405): JWT authenticated | Anon sweep: 401 |
| 955 | `/api/registration/info` | `POST` | `ApiRegistrationController.getRegistrationDetail()` (L385): JWT authenticated | Targeted: pA->200/500 (无权查看该报名信息); pA->200/500 (无权查看该报名信息) / Anon sweep: 401 |
| 956 | `/api/registration/join_info` | `-` | `ApiRegistrationController.getJoinRegistrationDetail()` (L419): JWT authenticated | Anon sweep: 401 |
| 957 | `/api/registration/list` | `POST` | `ApiRegistrationController.myRegistrationList()` (L461): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 958 | `/api/registration/merchant/cancel` | `-` | `ApiRegistrationMerchantController.cancelRegistration()` (L83): MerchantAccessService | Anon sweep: 401 |
| 959 | `/api/registration/merchant/create` | `-` | `ApiRegistrationMerchantController.createRegistration()` (L249): MerchantAccessService + OwnershipUtils/owner check + JWT currentMemberId() scope | Anon sweep: 401 |
| 960 | `/api/registration/merchant/info` | `-` | `ApiRegistrationMerchantController.getRegistrationDetail()` (L181): MerchantAccessService | Anon sweep: 401 |
| 961 | `/api/registration/merchant/list` | `-` | `ApiRegistrationMerchantController.myRegistrationList()` (L120): MerchantAccessService | Anon sweep: 401 |
| 962 | `/api/registration/merchant/select` | `-` | `ApiRegistrationMerchantController.select()` (L209): MerchantAccessService | Anon sweep: 401 |
| 963 | `/api/registration/merchant/update` | `-` | `ApiRegistrationMerchantController.updateRegistration()` (L156): MerchantAccessService | Anon sweep: 401 |
| 964 | `/api/registration/my-joined` | `-` | `ApiRegistrationController.myJoined()` (L532): JWT authenticated | Anon sweep: 401 |
| 965 | `/api/registration/original-event` | `-` | `ApiMiscGapController.originalEvent()` (L92): JWT authenticated | Anon sweep: 401 |
| 966 | `/api/registration/pay` | `POST` | `ApiRegistrationController.pay()` (L181): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 967 | `/api/registration/pay/app` | `POST` | `ApiRegistrationController.payApp()` (L245): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 968 | `/api/registration/payment-readiness` | `-` | `ApiRegistrationController.paymentReadiness()` (L125): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 969 | `/api/registration/quote` | `-` | `ApiRegistrationController.quote()` (L103): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 970 | `/api/registration/scan_dynamic_code` | `-` | `ApiRegistrationController.scanDynamicCode()` (L560): JWT authenticated | Anon sweep: 401 |
| 971 | `/api/registration/scan_group_member_ticket` | `POST` | `ApiRegistrationController.scanGroupMemberTicket()` (L567): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 972 | `/api/registration/scan_qr_code` | `-` | `ApiRegistrationController.scanQrCode()` (L577): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 973 | `/api/registration/scan_qr_code_chapter` | `-` | `ApiRegistrationController.scanQrCodeChapter()` (L540): JWT authenticated | Anon sweep: 401 |
| 974 | `/api/registration/scan_qr_code_station` | `-` | `ApiRegistrationController.scanQrCodeStation()` (L550): JWT authenticated | Anon sweep: 401 |
| 975 | `/api/reward-pledge` | `GET,POST` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 976 | `/api/reward-pledge/awards` | `POST` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 977 | `/api/reward-pledge/drafts` | `POST` | `ApiRewardPledgeController.draft()` (L32): JWT authenticated | Anon sweep: 401 |
| 978 | `/api/reward-pledge/fulfilment/awards` | `POST` | `ApiRewardPledgeController.storeAwards()` (L132): MerchantAccessService | Anon sweep: 401 |
| 979 | `/api/reward-pledge/fulfilment/expire` | `POST` | `ApiRewardPledgeController.expire()` (L154): MerchantAccessService | Anon sweep: 401 |
| 980 | `/api/reward-pledge/fulfilment/preview` | `-` | `ApiRewardPledgeController.preview()` (L137): MerchantAccessService | Anon sweep: 401 |
| 981 | `/api/reward-pledge/fulfilment/redeem` | `-` | `ApiRewardPledgeController.redeem()` (L147): MerchantAccessService | Anon sweep: 401 |
| 982 | `/api/reward-pledge/fulfilment/resolve` | `-` | `ApiRewardPledgeController.resolve()` (L142): MerchantAccessService | Anon sweep: 401 |
| 983 | `/api/reward-pledge/ops` | `GET` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 984 | `/api/reward-pledge/pledges` | `GET` | `ApiRewardPledgeController.owned()` (L80): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 985 | `/api/reward-pledge/review` | `GET` | `ApiRewardPledgeController.reviewQueue()` (L85): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 986 | `/api/reward-pledge/versions` | `GET,POST` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 987 | `/api/rewards/noncash` | `GET` | `ApiNonCashRewardController.list()` (L48): JWT authenticated | Anon sweep: 401 |
| 988 | `/api/rewards/noncash/cases` | `-` | `ApiNonCashRewardController.openCase()` (L88): JWT authenticated | Anon sweep: 401 |
| 989 | `/api/rewards/noncash/claims` | `-` | `ApiNonCashRewardController.claim()` (L32): JWT authenticated | Anon sweep: 401 |
| 990 | `/api/rewards/noncash/claims/recover` | `-` | `ApiNonCashRewardController.recover()` (L41): JWT authenticated | Anon sweep: 401 |
| 991 | `/api/rewards/noncash/subjects` | `GET` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 992 | `/api/rewards/noncash/versions` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 993 | `/api/roam/badge/shop-streak` | `-` | `ApiRoamController.shopStreakConfig()` (L480): JWT authenticated | Anon sweep: 401 |
| 994 | `/api/roam/checkin` | `GET` | `ApiRoamController.checkin()` (L922): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 995 | `/api/roam/entry/nearby` | `-` | `ApiRoamController.entryNearby()` (L147): JWT currentMemberId() scope | Anon sweep: 401 |
| 996 | `/api/roam/finish` | `GET,POST` | `ApiRoamController.finish()` (L815): JWT currentMemberId() scope | Anon sweep: 401 |
| 997 | `/api/roam/hangout/nearby` | `-` | `ApiRoamHangoutController.nearby()` (L47): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 998 | `/api/roam/nearby-exploreday` | `-` | `ApiRoamController.nearbyExploreDay()` (L139): JWT currentMemberId() scope | Anon sweep: 401 |
| 999 | `/api/roam/nearby-runners` | `-` | `ApiRoamController.nearbyRunners()` (L751): JWT currentMemberId() scope | Anon sweep: 401 |
| 1000 | `/api/roam/passport` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 1001 | `/api/roam/passport/history` | `-` | `ApiRoamPassportController.history()` (L19): JWT authenticated | Anon sweep: 401 |
| 1002 | `/api/roam/passport/record` | `GET` | `ApiRoamPassportController.record()` (L23): JWT authenticated | Anon sweep: 401 |
| 1003 | `/api/roam/poi/discover` | `GET,POST` | `ApiRoamController.discoverPoi()` (L171): JWT currentMemberId() scope | Anon sweep: 401 |
| 1004 | `/api/roam/pois` | `GET` | `ApiRoamController.pois()` (L125): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 1005 | `/api/roam/presence` | `GET` | `ApiRoamController.presence()` (L635): JWT currentMemberId() scope | Anon sweep: 401 |
| 1006 | `/api/roam/presence-visibility` | `GET` | `ApiRoamController.presenceVisibility()` (L675): JWT currentMemberId() scope; `ApiRoamController.changePresenceVisibility()` (L686): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 1007 | `/api/roam/reveal` | `GET,POST` | `ApiRoamController.reveal()` (L561): JWT currentMemberId() scope | Anon sweep: 401 |
| 1008 | `/api/roam/session` | `GET` | `ApiRoamController.session()` (L537): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 1009 | `/api/roam/session/records` | `POST` | `ApiRoamController.archiveRecords()` (L544): JWT currentMemberId() scope | Anon sweep: 401 |
| 1010 | `/api/roam/share/revoke` | `POST` | `ApiRoamShareController.revoke()` (L111): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 1011 | `/api/roam/share/snapshot` | `-` | `ApiRoamShareController.publish()` (L57): SecurityConfig.permitAll (public config/health/landing); `ApiRoamShareController.read()` (L136): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 200/500 (这条足迹已不再公开) |
| 1012 | `/api/roam/shop/visit` | `GET,POST` | `ApiRoamController.shopVisit()` (L270): JWT currentMemberId() scope | Anon sweep: 401 |
| 1013 | `/api/roam/shop/visitors` | `-` | `ApiRoamController.shopVisitors()` (L380): JWT authenticated | Anon sweep: 401 |
| 1014 | `/api/roam/stamp/create` | `GET` | `ApiRoamStampController.create()` (L99): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 1015 | `/api/roam/stamp/exchange` | `GET` | `ApiRoamStampController.exchange()` (L177): JWT currentMemberId() scope | Anon sweep: 401 |
| 1016 | `/api/roam/stamp/list` | `-` | `ApiRoamStampController.list()` (L266): JWT authenticated | Anon sweep: 401 |
| 1017 | `/api/roam/stamp/received` | `-` | `ApiRoamStampController.received()` (L246): JWT authenticated | Anon sweep: 401 |
| 1018 | `/api/roam/tiles` | `-` | `ApiRoamController.tiles()` (L494): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 1019 | `/api/roam/tiles/page` | `-` | `ApiRoamController.tilePage()` (L503): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 1020 | `/api/role/info` | `-` | `ApiRoleController.roleInfo()` (L75): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 1021 | `/api/search/unified` | `-` | `ApiContentSearchController.unified()` (L39): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 1022 | `/api/sms/send` | `POST` | `ApiLoginController.smsSend()` (L515): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 200/500 (手机号格式不正确) |
| 1023 | `/api/space/join-pause` | `-` | `ApiChannelAdmissionController.pause()` (L64): JWT authenticated | Anon sweep: 401 |
| 1024 | `/api/space/leave` | `-` | `ApiChannelAdmissionController.leave()` (L57): JWT authenticated | Anon sweep: 401 |
| 1025 | `/api/stats/club` | `-` | `ApiStatsController.club()` (L68): JWT currentMemberId() scope | Anon sweep: 401 |
| 1026 | `/api/support/tickets` | `POST` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 1027 | `/api/team/applications` | `GET,POST` | `ApiPlayTeamController.applications()` (L177): JWT currentMemberId() scope | Anon sweep: 401 |
| 1028 | `/api/team/apply` | `POST` | `ApiPlayTeamController.apply()` (L152): JWT currentMemberId() scope | Anon sweep: 401 |
| 1029 | `/api/team/command` | `POST` | `ApiPlayTeamController.command()` (L38): JWT currentMemberId() scope | Anon sweep: 401 |
| 1030 | `/api/team/command/cancel` | `POST` | `ApiPlayTeamController.commandCancel()` (L50): JWT currentMemberId() scope | Anon sweep: 401 |
| 1031 | `/api/team/command/query` | `POST` | `ApiPlayTeamController.commandQuery()` (L45): JWT currentMemberId() scope | Anon sweep: 401 |
| 1032 | `/api/team/create` | `POST` | `ApiPlayTeamController.create()` (L66): JWT currentMemberId() scope | Anon sweep: 401 |
| 1033 | `/api/team/disband` | `-` | `ApiPlayTeamController.disband()` (L245): JWT currentMemberId() scope | Anon sweep: 401 |
| 1034 | `/api/team/handle` | `POST` | `ApiPlayTeamController.handle()` (L210): JWT currentMemberId() scope | Anon sweep: 401 |
| 1035 | `/api/team/info` | `GET` | `ApiPlayTeamController.info()` (L100): @Anonymous (public endpoint; parameter/entity scoped where applicable) | Anon sweep: 200/500 (Request method 'GET' is not support) |
| 1036 | `/api/team/invite-qrcode` | `-` | `ApiPlayTeamController.inviteQrcode()` (L261): JWT currentMemberId() scope | Anon sweep: 401 |
| 1037 | `/api/team/join` | `-` | `ApiPlayTeamController.join()` (L130): JWT currentMemberId() scope | Anon sweep: 401 |
| 1038 | `/api/team/join-mode` | `-` | `ApiPlayTeamController.updateJoinMode()` (L85): JWT currentMemberId() scope | Anon sweep: 401 |
| 1039 | `/api/team/kick` | `-` | `ApiPlayTeamController.kick()` (L233): JWT currentMemberId() scope | Anon sweep: 401 |
| 1040 | `/api/team/my` | `GET` | `ApiPlayTeamController.my()` (L273): JWT currentMemberId() scope | Anon sweep: 401 |
| 1041 | `/api/team/my-applications` | `GET,POST` | `ApiPlayTeamController.myApplications()` (L187): JWT currentMemberId() scope | Anon sweep: 401 |
| 1042 | `/api/team/nearby` | `GET` | `ApiPlayTeamController.nearby()` (L141): JWT currentMemberId() scope | Anon sweep: 401 |
| 1043 | `/api/team/original-request` | `-` | `ApiFriendController.teamCommand()` (L256): JWT authenticated; `ApiFriendController.teamReceipt()` (L263): JWT authenticated | Anon sweep: 401 |
| 1044 | `/api/team/pool` | `GET` | `ApiTeamResourcePoolController.get()` (L30): JWT authenticated | Anon sweep: 401 |
| 1045 | `/api/team/pool/hint` | `-` | `ApiTeamResourcePoolController.hint()` (L37): JWT authenticated | Anon sweep: 401 |
| 1046 | `/api/team/pool/requests/{}` | `GET` | `ApiTeamResourcePoolController.receipt()` (L58): JWT authenticated | Covered by controller/JWT default deny + static review |
| 1047 | `/api/team/pool/skip` | `-` | `ApiTeamResourcePoolController.skip()` (L44): JWT authenticated | Anon sweep: 401 |
| 1048 | `/api/team/pool/timer/start` | `POST` | `ApiTeamResourcePoolController.startTimer()` (L51): JWT authenticated | Anon sweep: 401 |
| 1049 | `/api/team/quit` | `-` | `ApiPlayTeamController.quit()` (L222): JWT currentMemberId() scope | Anon sweep: 401 |
| 1050 | `/api/team/report` | `POST` | `ApiPlayTeamController.report()` (L197): JWT currentMemberId() scope | Anon sweep: 401 |
| 1051 | `/api/team/trail/consent` | `-` | `ApiPlayTeamController.trailConsent()` (L296): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 1052 | `/api/team/trail/state` | `-` | `ApiPlayTeamController.trailState()` (L288): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 1053 | `/api/team/trail/sync` | `-` | `ApiPlayTeamController.trailSync()` (L306): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 1054 | `/api/team/withdraw` | `POST` | `ApiPlayTeamController.withdraw()` (L166): JWT currentMemberId() scope | Anon sweep: 401 |
| 1055 | `/api/team/{}` | `POST` | Client prefix / alias or unmapped route (not a standalone controller handler) | Covered by controller/JWT default deny + static review |
| 1056 | `/api/template/adopt-draft` | `-` | `ApiTemplateController.adoptDraft()` (L222): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1057 | `/api/template/adoption-detail` | `-` | `ApiTemplateController.adoptionDetail()` (L251): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1058 | `/api/template/adoption-receipt` | `GET` | `ApiTemplateController.adoptionReceipt()` (L242): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1059 | `/api/template/adoption-source` | `GET` | `ApiTemplateController.adoptionSource()` (L233): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1060 | `/api/template/author-info` | `-` | `ApiTemplateController.authorInfo()` (L266): SecurityConfig.permitAll (public config/health/landing) | Targeted: pA->200/500 (模板不存在); pA->200/500 (模板不存在) / Anon sweep: 401 |
| 1061 | `/api/template/author-myinfo` | `-` | `ApiTemplateController.authorMyInfo()` (L274): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1062 | `/api/template/author-requests/read` | `-` | `appgaps/ApiAppGapsOriginalRequestController.templateRead()` (L91): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1063 | `/api/template/author-requests/submit` | `-` | `appgaps/ApiAppGapsOriginalRequestController.templateSubmit()` (L75): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1064 | `/api/template/delete` | `DELETE,POST` | `ApiTemplateController.deleteTemplate()` (L317): SecurityConfig.permitAll (public config/health/landing) | Targeted: pA->200/500 (系统繁忙，请稍后重试); pA->200/500 (系统繁忙，请稍后重试) / Anon sweep: 401 |
| 1065 | `/api/template/draft` | `POST` | `ApiTemplateController.draft()` (L166): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1066 | `/api/template/homeData` | `-` | `ApiTemplateController.getHomeData()` (L654): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1067 | `/api/template/info` | `-` | `ApiTemplateController.producInfo()` (L665): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1068 | `/api/template/list` | `-` | `ApiTemplateController.commonTemplateList()` (L576): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1069 | `/api/template/my-list` | `POST` | `ApiTemplateController.myTemplateList()` (L400): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1070 | `/api/template/myinfo` | `-` | `ApiTemplateController.myInfo()` (L799): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1071 | `/api/template/personal-report` | `-` | `ApiTemplateController.personalTemplateReport()` (L757): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1072 | `/api/template/publish` | `POST` | `ApiTemplateController.publish()` (L98): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1073 | `/api/template/report` | `-` | `ApiTemplateController.libraryReport()` (L737): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1074 | `/api/template/topic-template/binding` | `GET` | `ApiTopicTemplateController.bindLocation()` (L292): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1075 | `/api/template/topic-template/binding-status` | `GET` | `ApiTopicTemplateController.bindingStatus()` (L361): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1076 | `/api/template/topic-template/info` | `-` | `ApiTopicTemplateController.info()` (L87): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1077 | `/api/template/topic-template/list` | `-` | `ApiTopicTemplateController.list()` (L73): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1078 | `/api/template/topic-template/save-as` | `-` | `ApiTopicTemplateController.saveAs()` (L417): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1079 | `/api/template/topic-template/use` | `GET` | `ApiTopicTemplateController.use()` (L180): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1080 | `/api/template/updateLibraryStatus` | `POST` | `ApiTemplateController.updateTemplateLibraryStatus()` (L351): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1081 | `/api/template/{}/market-submit` | `-` | `ApiTemplateController.submitTemplateToMarket()` (L293): SecurityConfig.permitAll (public config/health/landing) | Targeted: pA->200/400 (模板不存在或无权提交); pA->200/400 (模板不存在或无权提交) |
| 1082 | `/api/topic/attribute-author` | `GET` | `ApiAppGapsTopicController.attributeList()` (L80): JWT authenticated + service-layer caller scope; `ApiAppGapsTopicController.attributeSave()` (L95): JWT authenticated + service-layer caller scope (+1 more mappings) | Targeted: pA->200/500 (Required request parameter 'scope' for m); pA->403/403 (无权操作该主题) / Anon sweep: 401 |
| 1083 | `/api/topic/attribute-author/references` | `DELETE,GET` | `ApiAppGapsTopicController.attributeReferences()` (L86): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 1084 | `/api/topic/attribute-author/request` | `-` | `ApiAppGapsTopicController.attributeRequest()` (L116): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 1085 | `/api/topic/attribute-author/validate` | `GET` | `ApiAppGapsTopicController.attributeValidate()` (L92): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 1086 | `/api/topic/attribute-binding` | `GET` | `ApiTopicAttributeBindingController.read()` (L22): MerchantAccessService | Anon sweep: 401 |
| 1087 | `/api/topic/author-edit-detail` | `PUT` | `ApiTopicController.authorEditDetail()` (L1017): JWT currentMemberId() scope | Targeted: pA->200/500 (参数不合规); pA->200/500 (无权编辑该主题) / Anon sweep: 401 |
| 1088 | `/api/topic/author-saved-detail` | `POST` | `ApiTopicController.authorSavedDetail()` (L1024): JWT currentMemberId() scope | Anon sweep: 401 |
| 1089 | `/api/topic/beta/graduate` | `-` | `ApiTopicController.betaGraduate()` (L1786): OwnershipUtils/owner check + JWT currentMemberId() scope | Anon sweep: 401 |
| 1090 | `/api/topic/cancel` | `-` | `ApiTopicController.topicCancel()` (L1353): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 1091 | `/api/topic/cancel_preview` | `-` | `ApiTopicController.topicCancelPreview()` (L1411): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 1092 | `/api/topic/chapter/finish` | `-` | `ApiTopicController.finishChapter()` (L2066): OwnershipUtils/owner check + JWT currentMemberId() scope | Anon sweep: 401 |
| 1093 | `/api/topic/chapter/recruit` | `-` | `ApiTopicController.setChapterRecruit()` (L1935): OwnershipUtils/owner check + JWT currentMemberId() scope | Anon sweep: 401 |
| 1094 | `/api/topic/chapter/supply-terms` | `POST` | `ApiTopicController.setChapterSupplyTerms()` (L2006): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 1095 | `/api/topic/cover` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 1096 | `/api/topic/cover/selection` | `-` | `ApiAppGapsTopicController.coverSelect()` (L53): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 1097 | `/api/topic/cover/selection/current` | `-` | `ApiAppGapsTopicController.coverCurrent()` (L37): JWT authenticated | Targeted: pA->403/403 (无权操作该主题); pA->403/403 (无权操作该主题) / Anon sweep: 401 |
| 1098 | `/api/topic/cover/selection/status` | `-` | `ApiAppGapsTopicController.coverStatus()` (L56): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 1099 | `/api/topic/cover/upload` | `-` | `ApiAppGapsTopicController.coverUpload()` (L40): JWT authenticated | Anon sweep: 401 |
| 1100 | `/api/topic/cover/{}/content` | `-` | `ApiAppGapsTopicController.coverContent()` (L59): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 1101 | `/api/topic/create` | `POST` | `ApiTopicController.createTopic()` (L377): JWT authenticated | Anon sweep: 401 |
| 1102 | `/api/topic/delete` | `DELETE` | `ApiTopicController.topicDelete()` (L1314): JWT authenticated + service-layer caller scope | Targeted: pA->200/500 (无权操作该主题); pA->200/500 (无权操作该主题) / Anon sweep: 401 |
| 1103 | `/api/topic/detail` | `POST` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 1104 | `/api/topic/edit-detail` | `POST` | `ApiTopicController.topicEditDetail()` (L1056): OwnershipUtils/owner check + JWT currentMemberId() scope | Anon sweep: 401 |
| 1105 | `/api/topic/edit-requests/read` | `POST` | `ApiAppGapsTopicController.editRead()` (L71): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 1106 | `/api/topic/edit-requests/resume-post-processing` | `POST` | `ApiAppGapsTopicController.editResume()` (L75): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 1107 | `/api/topic/edit-requests/submit` | `POST` | `ApiAppGapsTopicController.editSubmit()` (L68): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 1108 | `/api/topic/host-node-answer` | `-` | `ApiTopicController.hostNodeAnswer()` (L906): OwnershipUtils/owner check + JWT currentMemberId() scope | Anon sweep: 401 |
| 1109 | `/api/topic/host-qrcode` | `PUT` | `ApiTopicController.hostQrcode()` (L870): OwnershipUtils/owner check + JWT currentMemberId() scope | Targeted: pA->200/500 (参数不合规); pA->200/500 (参数不合规) / Anon sweep: 401 |
| 1110 | `/api/topic/info-to-merchant` | `-` | `ApiTopicController.topicInfoToMerchant()` (L3073): MerchantAccessService | Anon sweep: 401 |
| 1111 | `/api/topic/info-to-user` | `POST` | `ApiTopicController.topicInfo()` (L2156): OwnershipUtils/owner check | Anon sweep: 200/400 (参数不合规) |
| 1112 | `/api/topic/invite-chapters` | `-` | `ApiTopicController.inviteChapters()` (L978): OwnershipUtils/owner check + JWT currentMemberId() scope | Anon sweep: 401 |
| 1113 | `/api/topic/like` | `POST` | `ApiTopicController.topicLike()` (L1826): MerchantAccessService | Anon sweep: 401 |
| 1114 | `/api/topic/like_list` | `POST` | `ApiTopicController.likeList()` (L2739): JWT currentMemberId() scope | Anon sweep: 401 |
| 1115 | `/api/topic/list` | `-` | `ApiTopicController.topicList()` (L2512): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 200/500 (Request method 'GET' is not support) |
| 1116 | `/api/topic/merchant-recruitment-chapters` | `-` | `ApiTopicController.merchantRecruitmentChapters()` (L1853): MerchantAccessService | Anon sweep: 401 |
| 1117 | `/api/topic/module-preflight` | `GET,POST` | `ApiAppGapsTopicController.modulePreflight()` (L119): JWT authenticated | Anon sweep: 401 |
| 1118 | `/api/topic/play-setup` | `GET,PUT` | `ApiTopicPlaySetupController.authorView()` (L41): JWT currentMemberId() scope; `ApiTopicPlaySetupController.save()` (L52): JWT currentMemberId() scope | Anon sweep: 401 |
| 1119 | `/api/topic/pricing/confirm` | `-` | `ApiPricingController.confirm()` (L61): OwnershipUtils/owner check | Anon sweep: 401 |
| 1120 | `/api/topic/pricing/preview` | `-` | `ApiPricingController.preview()` (L42): OwnershipUtils/owner check | Anon sweep: 401 |
| 1121 | `/api/topic/publish-check` | `GET` | `ApiTopicStateKeyController.publishCheck()` (L247): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 1122 | `/api/topic/publish-draft` | `-` | `ApiTopicController.publishDraft()` (L1660): OwnershipUtils/owner check + JWT currentMemberId() scope | Anon sweep: 401 |
| 1123 | `/api/topic/publish-status` | `GET` | `ApiTopicStateKeyController.publishStatus()` (L268): MerchantAccessService + OwnershipUtils/owner check + JWT currentMemberId() scope | Anon sweep: 401 |
| 1124 | `/api/topic/report` | `-` | `ApiTopicController.topicReport()` (L1536): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 1125 | `/api/topic/share-code` | `-` | `ApiTopicController.shareCode()` (L954): OwnershipUtils/owner check + JWT currentMemberId() scope | Anon sweep: 401 |
| 1126 | `/api/topic/state-keys` | `GET,PUT` | `ApiTopicStateKeyController.listStateKeys()` (L78): JWT authenticated + service-layer caller scope; `ApiTopicStateKeyController.registerStateKeys()` (L92): JWT authenticated + service-layer caller scope (+1 more mappings) | Anon sweep: 401 |
| 1127 | `/api/topic/state-keys/named` | `-` | `ApiTopicAttributeController.createNamed()` (L90): JWT currentMemberId() scope; `ApiTopicAttributeController.deleteNamed()` (L117): JWT currentMemberId() scope | Anon sweep: 401 |
| 1128 | `/api/topic/state-keys/{}` | `-` | `ApiTopicStateKeyController.updateStateKey()` (L126): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 1129 | `/api/topic/transfer-to-club` | `-` | `ApiTopicController.transferToClub()` (L1577): OwnershipUtils/owner check + JWT currentMemberId() scope | Anon sweep: 401 |
| 1130 | `/api/topic/update` | `POST` | `ApiTopicController.updateTopic()` (L740): JWT currentMemberId() scope | Anon sweep: 401 |
| 1131 | `/api/topic/update_user_status` | `-` | `ApiTopicController.topicUpdateUserStatus()` (L1450): OwnershipUtils/owner check + JWT currentMemberId() scope | Anon sweep: 401 |
| 1132 | `/api/topic/v2/create` | `POST` | `ApiTopicController.createTopicV2()` (L384): JWT authenticated | Anon sweep: 401 |
| 1133 | `/api/topic/v2/update` | `POST` | `ApiTopicController.updateTopicV2()` (L747): JWT currentMemberId() scope | Anon sweep: 401 |
| 1134 | `/api/topic/xp-budget` | `-` | `ApiTopicController.xpBudget()` (L2501): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 1135 | `/api/ugc/block` | `POST` | `ApiMiscGapController.ugcBlock()` (L75): JWT authenticated | Anon sweep: 401 |
| 1136 | `/api/us/auth/apple/challenges` | `-` | `ApiUsAppleAuthController.challenges()` (L46): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 200/500 (Request method 'GET' is not support) |
| 1137 | `/api/us/auth/apple/exchange` | `POST` | `ApiUsAppleAuthController.exchange()` (L54): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 400/400 (US_APPLE_INVALID_REQUEST) |
| 1138 | `/api/us/auth/session` | `GET` | `ApiUsAppleAuthController.session()` (L67): JWT authenticated | Anon sweep: 401 |
| 1139 | `/api/user/address` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 1140 | `/api/user/address/action` | `-` | `ApiUmsMemberController.userAddressActoin()` (L827): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1141 | `/api/user/address/delete` | `-` | `ApiUmsMemberController.deleteUserAddress()` (L816): SecurityConfig.permitAll (public config/health/landing) | Targeted: pA->200/500 (地址不存在或不可用); pA->200/500 (地址不存在或不可用) / Anon sweep: 401 |
| 1142 | `/api/user/address/info` | `-` | `ApiUmsMemberController.getUserAddressInfo()` (L805): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1143 | `/api/user/address/list` | `POST` | `ApiUmsMemberController.getUserAddressList()` (L796): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1144 | `/api/user/address/setDefault` | `-` | `ApiUmsMemberController.setDefaultUserAddress()` (L891): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1145 | `/api/user/balance/list` | `POST` | `ApiUmsMemberController.getBalanceList()` (L251): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1146 | `/api/user/deregister` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 1147 | `/api/user/deregister/apply` | `POST` | `ApiAccountDeregistrationController.apply()` (L58): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1148 | `/api/user/deregister/cancel` | `POST` | `ApiAccountDeregistrationController.cancel()` (L102): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1149 | `/api/user/deregister/precheck` | `POST` | `ApiAccountDeregistrationController.precheck()` (L50): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1150 | `/api/user/deregister/result` | `-` | `ApiAccountDeregistrationController.?()` (L115): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1151 | `/api/user/deregister/status` | `GET` | `ApiAccountDeregistrationController.status()` (L127): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1152 | `/api/user/follow-list` | `-` | `ApiPlayerGapsController.followList()` (L32): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1153 | `/api/user/follow/action` | `-` | `ApiUmsMemberController.userFollowActoin()` (L912): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1154 | `/api/user/info` | `POST` | `ApiUmsMemberController.getUserInfo()` (L531): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1155 | `/api/user/invite_list` | `-` | `ApiUmsMemberController.inviteList()` (L480): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1156 | `/api/user/list` | `POST` | `ApiUmsMemberController.userList()` (L466): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1157 | `/api/user/nickname/check` | `-` | `ApiUmsMemberController.checkNickname()` (L125): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1158 | `/api/user/nickname/suggest` | `-` | `ApiUmsMemberController.suggestNickname()` (L131): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1159 | `/api/user/player-code` | `POST` | `ApiUmsMemberController.getPlayerWxacode()` (L144): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1160 | `/api/user/points/list` | `POST` | `ApiUmsMemberController.getPointsList()` (L215): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1161 | `/api/user/points/statistics` | `POST` | `ApiUmsMemberController.getPointsStatistics()` (L168): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1162 | `/api/user/profile/review` | `-` | `ApiUmsMemberController.profileReview()` (L756): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1163 | `/api/user/public-info` | `-` | `ApiUmsMemberController.getPublicUserInfo()` (L505): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 200/500 (Request method 'GET' is not support) |
| 1164 | `/api/user/setInviter` | `-` | `ApiUmsMemberController.setInviter()` (L775): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1165 | `/api/user/sms/send` | `POST` | `ApiMiscGapController.userSmsSend()` (L61): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 200/500 (手机号格式不正确) |
| 1166 | `/api/user/update` | `POST` | `ApiUmsMemberController.updateUserInfo()` (L650): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 401 |
| 1167 | `/api/userInfo` | `POST` | `ApiLoginController.userInfo()` (L265): SecurityConfig.permitAll (public config/health/landing) | Anon sweep: 200/401 (获取APP用户信息异常) |
| 1168 | `/api/v1/community` | `GET,PATCH` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 1169 | `/api/v1/community/appeals` | `POST` | `ApiCommunityPostController.appeal()` (L215): JWT authenticated | Anon sweep: 401 |
| 1170 | `/api/v1/community/capabilities` | `-` | `ApiCommunityPostController.capabilities()` (L35): JWT authenticated | Anon sweep: 200/200 (操作成功) |
| 1171 | `/api/v1/community/comments/{}/actions` | `POST` | `ApiCommunityPostController.likeComment()` (L174): JWT authenticated | Client prefix literal (covered by child routes) |
| 1172 | `/api/v1/community/drafts` | `GET` | `ApiCommunityPostController.drafts()` (L78): JWT authenticated | Anon sweep: 401 |
| 1173 | `/api/v1/community/feeds` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 1174 | `/api/v1/community/feeds/{}` | `-` | `ApiCommunityPostController.feed()` (L107): JWT authenticated | Covered by controller/JWT default deny + static review |
| 1175 | `/api/v1/community/guidelines/ack` | `POST` | `ApiCommunityPostController.acknowledge()` (L205): @Anonymous (public endpoint; parameter/entity scoped where applicable) | Anon sweep: 401 |
| 1176 | `/api/v1/community/guidelines/active` | `GET` | `ApiCommunityPostController.guideline()` (L198): JWT authenticated | Anon sweep: 200/200 (操作成功) |
| 1177 | `/api/v1/community/me/enforcements` | `-` | `ApiCommunityPostController.memberEnforcements()` (L220): JWT authenticated | Anon sweep: 401 |
| 1178 | `/api/v1/community/me/{}` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Covered by controller/JWT default deny + static review |
| 1179 | `/api/v1/community/media/register` | `POST` | `ApiCommunityPostController.registerMedia()` (L39): @Anonymous (public endpoint; parameter/entity scoped where applicable) | Anon sweep: 401 |
| 1180 | `/api/v1/community/notification-preferences` | `PATCH,POST` | `ApiCommunityPostController.notificationPreferences()` (L232): JWT authenticated; `ApiCommunityPostController.updateNotificationPreferences()` (L237): JWT authenticated | Anon sweep: 401 |
| 1181 | `/api/v1/community/notifications` | `-` | `ApiCommunityPostController.notifications()` (L226): JWT authenticated | Anon sweep: 401 |
| 1182 | `/api/v1/community/notifications/{}/read` | `POST` | `ApiCommunityPostController.readNotification()` (L243): JWT authenticated | Covered by controller/JWT default deny + static review |
| 1183 | `/api/v1/community/posts` | `POST` | `ApiCommunityPostController.create()` (L44): JWT authenticated | Anon sweep: 401 |
| 1184 | `/api/v1/community/posts/{}` | `GET,POST` | `ApiCommunityPostController.edit()` (L49): JWT authenticated; `ApiCommunityPostController.delete()` (L59): JWT authenticated (+1 more mappings) | Targeted: pB->200/404 (帖文不存在); pB->200/403 (无权操作该帖文) |
| 1185 | `/api/v1/community/posts/{}/actions` | `POST` | `ApiCommunityPostController.act()` (L128): JWT authenticated | Client prefix literal (covered by child routes) |
| 1186 | `/api/v1/community/posts/{}/comments` | `POST` | `ApiCommunityPostController.comment()` (L140): JWT authenticated; `ApiCommunityPostController.comments()` (L145): JWT authenticated | Covered by controller/JWT default deny + static review |
| 1187 | `/api/v1/community/posts/{}/comments/{}` | `DELETE,POST` | `ApiCommunityPostController.deleteComment()` (L160): JWT authenticated | Covered by controller/JWT default deny + static review |
| 1188 | `/api/v1/community/posts/{}/comments/{}/approve` | `POST` | `ApiCommunityPostController.approveComment()` (L154): JWT authenticated | Covered by controller/JWT default deny + static review |
| 1189 | `/api/v1/community/posts/{}/location` | `DELETE` | `ApiCommunityPostController.withdrawLocation()` (L64): JWT authenticated | Covered by controller/JWT default deny + static review |
| 1190 | `/api/v1/community/posts/{}/publish` | `POST` | `ApiCommunityPostController.publish()` (L54): JWT authenticated | Client prefix literal (covered by child routes) |
| 1191 | `/api/v1/community/posts/{}/revisions` | `GET` | `ApiCommunityPostController.revisions()` (L96): JWT authenticated | Targeted: pB->200/404 (帖文不存在); pB->200/403 (仅作者可查看编辑历史) |
| 1192 | `/api/v1/community/reference-options/{}` | `GET` | `ApiCommunityPostController.referenceOptions()` (L101): JWT authenticated | Client prefix literal (covered by child routes) |
| 1193 | `/api/v1/community/reports` | `POST` | `ApiCommunityPostController.report()` (L210): JWT authenticated | Anon sweep: 401 |
| 1194 | `/api/verify/citynode/issue` | `GET` | `ApiVerifyController.issueCityNodeCode()` (L59): MerchantAccessService | Anon sweep: 401 |
| 1195 | `/api/verify/citynode/receipt` | `-` | `ApiVerifyController.cityNodeReceipt()` (L71): MerchantAccessService | Anon sweep: 401 |
| 1196 | `/api/verify/citynode/redeem` | `-` | `ApiVerifyController.redeemCityNodeCode()` (L65): MerchantAccessService | Anon sweep: 401 |
| 1197 | `/api/verify/dyncode/issue` | `POST` | `ApiVerifyController.issueDynCode()` (L44): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 1198 | `/api/verify/groupcode/issue` | `-` | `ApiVerifyController.issueGroupCode()` (L77): MerchantAccessService | Anon sweep: 401 |
| 1199 | `/api/verify/groupcode/redeem` | `-` | `ApiVerifyController.redeemGroupCode()` (L92): MerchantAccessService | Anon sweep: 401 |
| 1200 | `/api/verify/seat/receipt` | `POST` | `ApiVerifyController.seatReceipt()` (L53): MerchantAccessService | Anon sweep: 401 |
| 1201 | `/api/video-bounty/accrual/mine` | `-` | `ApiVideoBountyController.listMyAccruals()` (L90): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 1202 | `/api/video-bounty/campaign` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 1203 | `/api/video-bounty/campaign/list` | `-` | `ApiVideoBountyController.listOpenCampaigns()` (L53): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 1204 | `/api/video-bounty/campaign/{}/attribution` | `-` | `ApiVideoBountyController.campaignAttribution()` (L116): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 1205 | `/api/video-bounty/submission` | `-` | `ApiVideoBountyController.submit()` (L60): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 1206 | `/api/video-bounty/submission/mine` | `-` | `ApiVideoBountyController.listMine()` (L66): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 1207 | `/api/video-bounty/submission/{}/appeal` | `-` | `ApiVideoBountyController.appeal()` (L72): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 1208 | `/api/video-bounty/submission/{}/attribution` | `-` | `ApiVideoBountyController.submissionAttribution()` (L104): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 1209 | `/api/video-bounty/submission/{}/cancel` | `-` | `ApiVideoBountyController.cancel()` (L80): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 1210 | `/api/wallet/stages` | `POST` | `ApiWalletController.stages()` (L60): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 1211 | `/api/welcome-screen/get` | `GET` | `ApiChannelSurfaceController.welcome()` (L37): JWT authenticated | Anon sweep: 401 |
| 1212 | `/api/withdrawal/create` | `GET` | `ApiWithdrawalController.request()` (L77): JWT authenticated + service-layer caller scope | Anon sweep: 401 |
| 1213 | `/api/withdrawal/list` | `GET,POST` | `ApiWithdrawalController.myPointsList()` (L52): JWT authenticated + service-layer caller scope | Targeted: pA->200/200 (操作成功) / Anon sweep: 401 |
| 1214 | `/api/workshop/creator/pending-packages` | `GET` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 1215 | `/api/workshop/creator/public-theme-use` | `GET` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 1216 | `/api/workshop/owned` | `GET` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 1217 | `/api/workshop/owned/detail` | `GET` | `appgaps/ApiAppGapsWorkshopController.ownedDetail()` (L39): JWT authenticated | Anon sweep: 401 |
| 1218 | `/api/workshop/owned/list` | `-` | `appgaps/ApiAppGapsWorkshopController.ownedList()` (L33): JWT authenticated | Anon sweep: 401 |
| 1219 | `/api/workshop/owned/package` | `-` | `appgaps/ApiAppGapsWorkshopController.ownedPackage()` (L45): JWT authenticated | Anon sweep: 401 |
| 1220 | `/api/workshop/purchased` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 1221 | `/api/workshop/purchased/detail` | `-` | `ApiWorkshopController.legacy()` (L104): JWT authenticated | Targeted: pA->200/409 (WORKSHOP_CONTRACT_UPGRADE_REQUIRED) / Anon sweep: 401 |
| 1222 | `/api/workshop/purchased/install` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 1223 | `/api/workshop/purchased/installed-text/detail` | `-` | `ApiWorkshopController.legacy()` (L104): JWT authenticated | Anon sweep: 401 |
| 1224 | `/api/workshop/purchased/list` | `-` | `ApiWorkshopController.legacy()` (L104): JWT authenticated | Targeted: pA->200/409 (WORKSHOP_CONTRACT_UPGRADE_REQUIRED) / Anon sweep: 401 |
| 1225 | `/api/workshop/purchased/professional-operations` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 1226 | `/api/workshop/purchased/professional-operations/cancel` | `-` | `ApiWorkshopProfessionalController.cancel()` (L51): JWT authenticated | Anon sweep: 401 |
| 1227 | `/api/workshop/purchased/professional-operations/history` | `-` | `ApiWorkshopProfessionalController.history()` (L57): JWT authenticated | Anon sweep: 401 |
| 1228 | `/api/workshop/purchased/professional-operations/status` | `-` | `ApiWorkshopProfessionalController.status()` (L45): JWT authenticated | Anon sweep: 401 |
| 1229 | `/api/workshop/purchased/professional-operations/submit` | `-` | `ApiWorkshopProfessionalController.submit()` (L38): JWT authenticated | Anon sweep: 401 |
| 1230 | `/api/workshop/purchased/professional-targets` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 1231 | `/api/workshop/purchased/professional-targets/detail` | `-` | `ApiWorkshopProfessionalController.target()` (L32): JWT authenticated | Anon sweep: 401 |
| 1232 | `/api/workshop/purchased/professional-targets/list` | `-` | `ApiWorkshopProfessionalController.targets()` (L26): JWT authenticated | Anon sweep: 401 |
| 1233 | `/api/workshop/v2/purchased` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 1234 | `/api/workshop/v2/purchased/detail` | `-` | `ApiWorkshopController.license()` (L75): JWT authenticated | Targeted: ->200/500 (Required request parameter 'license_id' ) / Anon sweep: 401 |
| 1235 | `/api/workshop/v2/purchased/install` | `-` | Client prefix / alias or unmapped route (not a standalone controller handler) | Anon sweep: 401 |
| 1236 | `/api/workshop/v2/purchased/installed-text/detail` | `-` | `ApiWorkshopController.text()` (L98): JWT authenticated | Anon sweep: 401 |
| 1237 | `/api/workshop/v2/purchased/list` | `-` | `ApiWorkshopController.licenses()` (L71): JWT authenticated | Targeted: ->200/200 (操作成功) / Anon sweep: 401 |
| 1238 | `/api/{}/cancel` | `POST` | Client prefix / alias or unmapped route (not a standalone controller handler) | Covered by controller/JWT default deny + static review |

### 6.2 Additional Controller `/api/**` Mappings Not Directly Named in Capgraph (`360` mappings)

| # | Route | HTTP | Static Authz / Role Check | Live Probe (`TEST 8120`) |
|---|---|---|---|---|
| 1 | `/api/activity/like` | `POST` | `ApiActivityController.activityLike()` (L765): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 2 | `/api/activity/like_list` | `POST` | `ApiActivityController.likeList()` (L388): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 3 | `/api/agreement/consent` | `POST` | `ApiAgreementController.consent()` (L59): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 4 | `/api/agreement/versions` | `GET` | `ApiAgreementController.versions()` (L53): @Anonymous (public endpoint; parameter/entity scoped where applicable) | Covered by controller/JWT default deny + static review |
| 5 | `/api/ai/chapter/overview` | `POST` | `ApiAiController.chapterOverview()` (L365): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 6 | `/api/ai/merchant/task-suggest` | `POST` | `ApiAiController.merchantTaskSuggest()` (L405): MerchantAccessService | Covered by controller/JWT default deny + static review |
| 7 | `/api/ai/node/generate` | `POST` | `ApiAiController.nodeGenerate()` (L305): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 8 | `/api/ai/theme/merchant-plan` | `POST` | `ApiAiController.merchantPlan()` (L144): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 9 | `/api/betagame/info` | `POST` | `ApiBetaGameController.betaGameInfo()` (L68): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 10 | `/api/betagame/list` | `POST` | `ApiBetaGameController.getList()` (L56): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 11 | `/api/betagame/vote` | `POST` | `ApiBetaGameController.addVote()` (L112): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 12 | `/api/channel/receipt` | `POST` | `ApiChannelRedemptionController.receipt()` (L36): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 13 | `/api/channel/{channelId}/board/{boardKey}/detail` | `GET` | `ApiChannelProgressController.detail()` (L63): JWT authenticated | Covered by controller/JWT default deny + static review |
| 14 | `/api/channel/{channelId}/board/{boardKey}/me/best` | `GET` | `ApiChannelProgressController.best()` (L79): JWT authenticated | Covered by controller/JWT default deny + static review |
| 15 | `/api/channel/{channelId}/board/{boardKey}/me/recent` | `GET` | `ApiChannelProgressController.recent()` (L88): JWT authenticated | Covered by controller/JWT default deny + static review |
| 16 | `/api/channel/{channelId}/board/{boardKey}/top` | `GET` | `ApiChannelProgressController.top()` (L53): JWT authenticated | Covered by controller/JWT default deny + static review |
| 17 | `/api/channels/member-state-requests/{requestId}` | `GET` | `ApiChannelGovernanceController.memberStateReceipt()` (L148): JWT authenticated | Covered by controller/JWT default deny + static review |
| 18 | `/api/channels/{id}/member-state` | `POST` | `ApiChannelGovernanceController.memberState()` (L141): JWT authenticated | Covered by controller/JWT default deny + static review |
| 19 | `/api/city-game/task-history/instance/{instanceId}` | `GET` | `ApiCityGameR491Controller.historyDetail()` (L135): JWT authenticated | Covered by controller/JWT default deny + static review |
| 20 | `/api/city-game/task-history/instance/{instanceId}/request/{requestId}` | `GET` | `ApiCityGameR491Controller.historyReceipt()` (L140): JWT authenticated | Covered by controller/JWT default deny + static review |
| 21 | `/api/city-value/visits/{visitId}` | `GET` | `ApiCityGameR491Controller.valueVisit()` (L171): JWT authenticated | Covered by controller/JWT default deny + static review |
| 22 | `/api/club/apply` | `POST` | `ApiClubController.apply()` (L460): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 23 | `/api/club/event-ops/host/decide` | `POST` | `ApiClubEventOpsController.decideHost()` (L200): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 24 | `/api/club/event-ops/series/close` | `POST` | `ApiClubEventOpsController.closeSeries()` (L136): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 25 | `/api/club/events/proxy-enroll/mine` | `POST` | `ApiClubGapController.proxyEnrollMine()` (L142): JWT authenticated | Covered by controller/JWT default deny + static review |
| 26 | `/api/club/lead/settle` | `POST` | `ApiClubLeadController.settle()` (L572): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 27 | `/api/club/lead/start` | `POST` | `ApiClubLeadController.start()` (L194): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 28 | `/api/club/membership/finance/invoices` | `POST` | `ApiClubMembershipController.financeInvoices()` (L133): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 29 | `/api/club/membership/invoice` | `POST` | `ApiClubMembershipController.invoice()` (L124): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 30 | `/api/club/membership/invoice/close` | `POST` | `ApiClubMembershipController.closeInvoice()` (L115): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 31 | `/api/club/membership/invoice/resume` | `POST` | `ApiClubMembershipController.resumeInvoice()` (L106): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 32 | `/api/club/membership/overview` | `POST` | `ApiClubMembershipController.overview()` (L35): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 33 | `/api/club/membership/plan/save` | `POST` | `ApiClubMembershipController.savePlan()` (L80): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 34 | `/api/club/membership/plans` | `POST` | `ApiClubMembershipController.plans()` (L62): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 35 | `/api/club/membership/plans/manage` | `POST` | `ApiClubMembershipController.managePlans()` (L71): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 36 | `/api/club/membership/refund/review` | `POST` | `ApiClubMembershipController.requestRefundReview()` (L153): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 37 | `/api/club/membership/renew` | `POST` | `ApiClubMembershipController.renew()` (L97): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 38 | `/api/club/membership/setting` | `POST` | `ApiClubMembershipController.setting()` (L44): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 39 | `/api/club/membership/setting/update` | `POST` | `ApiClubMembershipController.updateSetting()` (L53): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 40 | `/api/club/membership/term/cancel` | `POST` | `ApiClubMembershipController.cancelTerm()` (L142): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 41 | `/api/club/my-apply` | `POST` | `ApiClubController.myApply()` (L532): OwnershipUtils/owner check + JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 42 | `/api/club/open-session` | `POST` | `ApiClubController.openSession()` (L1786): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 43 | `/api/club/open-session-topics` | `POST` | `ApiClubController.openSessionTopics()` (L1769): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 44 | `/api/club/post/operation/comment/create` | `POST` | `ApiClubController.postOperation()` (L2603): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 45 | `/api/club/post/operation/comment/receipt` | `POST` | `ApiClubController.postOperation()` (L2603): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 46 | `/api/club/post/operation/create` | `POST` | `ApiClubController.postOperation()` (L2603): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 47 | `/api/club/post/operation/update` | `POST` | `ApiClubController.postOperation()` (L2603): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 48 | `/api/club/post/pin` | `POST` | `ApiClubController.postPin()` (L2633): ClubAccessService/ChannelGuildAccess/canViewClub + JWT currentMemberId() scope | Targeted: pA->200/400 (缺少有效版本号); ->200/500 (缺少参数) |
| 49 | `/api/club/settlement/withdraw` | `POST` | `ApiClubSettlementController.withdraw()` (L210): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 50 | `/api/common/channel/callback/{providerKey}` | `POST` | `ApiChannelVerificationCallbackController.callback()` (L35): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 51 | `/api/common/upload` | `POST` | `ApiCommonController.uploadFile()` (L210): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 52 | `/api/common/uploads` | `POST` | `ApiCommonController.uploadFiles()` (L237): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 53 | `/api/community-trust/contributions/mine` | `GET` | `ApiCommunityTrustController.myContributions()` (L126): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 54 | `/api/community-trust/contributions/supplement` | `POST` | `ApiCommunityTrustController.supplementContribution()` (L137): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 55 | `/api/community-trust/contributions/{contributionId}` | `GET` | `ApiCommunityTrustController.contributionDetail()` (L151): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 56 | `/api/community-trust/reports/{reportId}/subject` | `GET` | `ApiCommunityTrustController.subjectReport()` (L83): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 57 | `/api/compliance/usage-records/request` | `GET` | `appgaps/ApiAppGapsMapController.usageRecord()` (L34): JWT authenticated | Covered by controller/JWT default deny + static review |
| 58 | `/api/content-draft/delete` | `POST` | `ApiContentDraftController.delete()` (L41): MerchantAccessService | Covered by controller/JWT default deny + static review |
| 59 | `/api/content-draft/list` | `POST` | `ApiContentDraftController.list()` (L36): MerchantAccessService | Covered by controller/JWT default deny + static review |
| 60 | `/api/content-draft/publish` | `POST` | `ApiContentDraftController.publish()` (L47): MerchantAccessService | Covered by controller/JWT default deny + static review |
| 61 | `/api/content-draft/restore` | `POST` | `ApiContentDraftController.restore()` (L30): MerchantAccessService | Covered by controller/JWT default deny + static review |
| 62 | `/api/content-draft/save` | `POST` | `ApiContentDraftController.save()` (L25): MerchantAccessService | Covered by controller/JWT default deny + static review |
| 63 | `/api/content-sec/callback` | `GET` | `ApiContentSecurityController.verify()` (L51): @Anonymous (public endpoint; parameter/entity scoped where applicable) | Covered by controller/JWT default deny + static review |
| 64 | `/api/content-sec/callback` | `POST` | `ApiContentSecurityController.onResult()` (L77): @Anonymous (public endpoint; parameter/entity scoped where applicable) | Covered by controller/JWT default deny + static review |
| 65 | `/api/coop/candidates/confirm` | `POST` | `ApiCoopController.confirmCandidate()` (L1317): OwnershipUtils/owner check + JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 66 | `/api/coop/contact` | `POST` | `ApiCoopController.contact()` (L1126): OwnershipUtils/owner check + JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 67 | `/api/coop/deposit/create` | `POST` | `ApiCoopController.depositCreate()` (L134): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 68 | `/api/coop/handle` | `POST` | `ApiCoopController.handle()` (L553): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 69 | `/api/coop/offer/circle-supply/pause` | `POST` | `ApiCoopController.pauseOwnCircleSupply()` (L934): MerchantAccessService + JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 70 | `/api/coop/offer/circle-supply/reconfirm` | `POST` | `ApiCoopController.reconfirmCircleSupply()` (L889): MerchantAccessService + JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 71 | `/api/coop/offer/circle-supply/reconfirm-current` | `POST` | `ApiCoopController.reconfirmCurrentCircleSupply()` (L912): MerchantAccessService + JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 72 | `/api/coop/offer/enroll` | `POST` | `ApiCoopController.offerEnroll()` (L830): MerchantAccessService + JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 73 | `/api/coop/perk-template/delete` | `POST` | `ApiCoopController.perkTemplateDelete()` (L802): MerchantAccessService + JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 74 | `/api/coop/perk-template/save` | `POST` | `ApiCoopController.perkTemplateSave()` (L768): MerchantAccessService + JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 75 | `/api/coop/perks/attach` | `POST` | `ApiCoopController.perksAttach()` (L956): MerchantAccessService + JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 76 | `/api/coop/pool/apply` | `POST` | `ApiCoopPoolController.apply()` (L186): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 77 | `/api/coop/pool/decline` | `POST` | `ApiCoopPoolController.decline()` (L237): MerchantAccessService + JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 78 | `/api/coop/pool/withdraw` | `POST` | `ApiCoopPoolController.withdraw()` (L222): MerchantAccessService + JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 79 | `/api/coop/review/save` | `POST` | `ApiCoopController.reviewSave()` (L1013): MerchantAccessService + JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 80 | `/api/creativesquare/summary` | `POST` | `ApiCreativeSquareController.creativeSquareSummary()` (L1196): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 81 | `/api/discovery-filter/schema` | `POST` | `ApiDiscoveryFilterController.schema()` (L14): JWT authenticated | Covered by controller/JWT default deny + static review |
| 82 | `/api/findpass` | `POST` | `ApiLoginController.findpassword()` (L859): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 83 | `/api/free-explore/bundle/merchant/reservation/{reservationId}/no-show` | `POST` | `ApiFreeExploreBundleController.noShow()` (L125): MerchantAccessService + JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 84 | `/api/free-explore/bundle/merchant/reservations` | `GET` | `ApiFreeExploreBundleController.merchantReservations()` (L118): MerchantAccessService + JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 85 | `/api/free-explore/bundle/redemption/result` | `GET` | `ApiFreeExploreBundleExtrasController.redemptionResult()` (L42): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 86 | `/api/free-explore/bundle/reservation/receipt` | `GET` | `ApiFreeExploreBundleController.reservationReceipt()` (L81): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 87 | `/api/free-explore/bundle/reservation/{reservationId}/cancel` | `POST` | `ApiFreeExploreBundleController.cancel()` (L97): MerchantAccessService + JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 88 | `/api/free-explore/bundle/review/list` | `GET` | `ApiFreeExploreBundleExtrasController.reviews()` (L53): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 89 | `/api/free-explore/bundle/review/mine` | `GET` | `ApiFreeExploreBundleExtrasController.myReview()` (L50): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 90 | `/api/free-explore/bundle/transfer/claim` | `POST` | `ApiFreeExploreBundleExtrasController.transferWrite()` (L92): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 91 | `/api/free-explore/bundle/transfer/preview` | `GET` | `ApiFreeExploreBundleExtrasController.transferPreview()` (L81): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 92 | `/api/free-explore/bundle/transfer/receipt` | `GET` | `ApiFreeExploreBundleExtrasController.transferReceipt()` (L84): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 93 | `/api/free-explore/bundle/transfer/{transferId}/revoke` | `POST` | `ApiFreeExploreBundleExtrasController.transferWrite()` (L92): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 94 | `/api/free-explore/bundle/voucher/appeal/receipt` | `GET` | `ApiFreeExploreBundleExtrasController.appealReceipt()` (L67): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 95 | `/api/free-explore/bundle/voucher/version` | `GET` | `ApiFreeExploreBundleExtrasController.voucherVersion()` (L36): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 96 | `/api/free-explore/bundle/waiver` | `GET` | `ApiFreeExploreBundleExtrasController.waiver()` (L39): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 97 | `/api/free-explore/bundle/wallet` | `GET` | `ApiFreeExploreBundleExtrasController.wallet()` (L30): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 98 | `/api/friend/channel-invite/batch` | `GET` | `ApiFriendController.channelBatch()` (L197): JWT authenticated | Covered by controller/JWT default deny + static review |
| 99 | `/api/friend/channel-invite/command` | `POST` | `ApiFriendController.channelCommand()` (L190): JWT authenticated | Covered by controller/JWT default deny + static review |
| 100 | `/api/friend/channel-invite/landing` | `GET` | `ApiFriendController.channelLanding()` (L211): JWT authenticated | Covered by controller/JWT default deny + static review |
| 101 | `/api/friend/channel-invite/mine` | `GET` | `ApiFriendController.channelMine()` (L218): JWT authenticated | Covered by controller/JWT default deny + static review |
| 102 | `/api/friend/channel-invite/notice` | `GET` | `ApiFriendController.channelNotice()` (L204): JWT authenticated | Covered by controller/JWT default deny + static review |
| 103 | `/api/friend/channel-invite/picker` | `GET` | `ApiFriendController.channelPicker()` (L182): JWT authenticated | Covered by controller/JWT default deny + static review |
| 104 | `/api/friend/channel-invite/receipt` | `GET` | `ApiFriendController.channelReceipt()` (L225): JWT authenticated | Covered by controller/JWT default deny + static review |
| 105 | `/api/friend/team-invite/landing` | `GET` | `ApiFriendController.teamLanding()` (L242): JWT authenticated | Covered by controller/JWT default deny + static review |
| 106 | `/api/friend/team-invite/mine` | `GET` | `ApiFriendController.teamMine()` (L249): JWT authenticated | Covered by controller/JWT default deny + static review |
| 107 | `/api/friend/team-invite/picker` | `GET` | `ApiFriendController.teamPicker()` (L234): JWT authenticated | Covered by controller/JWT default deny + static review |
| 108 | `/api/friend/{action:request|accept|reject|cancel}` | `POST` | `ApiFriendController.write()` (L67): JWT authenticated | Covered by controller/JWT default deny + static review |
| 109 | `/api/getwxbindphone` | `POST` | `ApiLoginController.getPhoneNumber()` (L322): JWT authenticated | Covered by controller/JWT default deny + static review |
| 110 | `/api/gift/give` | `POST` | `ApiCrowdfundingController.giftGive()` (L60): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 111 | `/api/gift/list` | `POST` | `ApiGiftController.giftList()` (L36): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 112 | `/api/growth/mission/complete` | `POST` | `ApiGrowthController.complete()` (L66): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 113 | `/api/host-tip/intent/received` | `GET` | `ApiHostTipController.listReceived()` (L75): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 114 | `/api/im/appeals/{id:[0-9]+}` | `GET` | `ApiChannelGovernanceController.appeal()` (L94): JWT authenticated | Covered by controller/JWT default deny + static review |
| 115 | `/api/im/chain/{operation:create|join|withdraw|close}` | `POST` | `ApiChannelInteractionController.chain()` (L33): JWT authenticated | Covered by controller/JWT default deny + static review |
| 116 | `/api/im/moderation/{operation:timeout|kick|ban}` | `POST` | `ApiChannelGovernanceController.moderate()` (L42): JWT authenticated | Covered by controller/JWT default deny + static review |
| 117 | `/api/im/roles/{id:[0-9]+}` | `DELETE` | `ApiChannelGovernanceController.deleteRole()` (L171): JWT authenticated | Covered by controller/JWT default deny + static review |
| 118 | `/api/im/roles/{id:[0-9]+}` | `PATCH` | `ApiChannelGovernanceController.editRole()` (L164): JWT authenticated | Covered by controller/JWT default deny + static review |
| 119 | `/api/im/webhook/endpoints` | `GET` | `ApiChannelGapController.endpoints()` (L157): JWT authenticated | Covered by controller/JWT default deny + static review |
| 120 | `/api/im/webhook/endpoints` | `POST` | `ApiChannelGapController.createEndpoint()` (L166): JWT authenticated | Covered by controller/JWT default deny + static review |
| 121 | `/api/im/webhook/endpoints/{id}` | `DELETE` | `ApiChannelGapController.deleteEndpoint()` (L187): JWT authenticated | Covered by controller/JWT default deny + static review |
| 122 | `/api/im/webhook/endpoints/{id}` | `GET` | `ApiChannelGapController.endpoint()` (L173): JWT authenticated | Covered by controller/JWT default deny + static review |
| 123 | `/api/im/webhook/endpoints/{id}` | `PUT` | `ApiChannelGapController.updateEndpoint()` (L180): JWT authenticated | Covered by controller/JWT default deny + static review |
| 124 | `/api/im/webhook/endpoints/{id}/logs` | `GET` | `ApiChannelGapController.endpointLogs()` (L252): JWT authenticated | Covered by controller/JWT default deny + static review |
| 125 | `/api/im/webhook/endpoints/{id}/rotate-token` | `POST` | `ApiChannelGapController.rotateToken()` (L194): JWT authenticated | Covered by controller/JWT default deny + static review |
| 126 | `/api/im/webhook/inbound/{id}` | `POST` | `ApiChannelGapController.inbound()` (L207): @Anonymous (public endpoint; parameter/entity scoped where applicable) | Covered by controller/JWT default deny + static review |
| 127 | `/api/im/webhook/subscriptions/{id}` | `DELETE` | `ApiChannelGapController.deleteSubscription()` (L117): JWT authenticated | Covered by controller/JWT default deny + static review |
| 128 | `/api/im/webhook/subscriptions/{id}` | `GET` | `ApiChannelGapController.subscription()` (L103): JWT authenticated | Covered by controller/JWT default deny + static review |
| 129 | `/api/im/webhook/subscriptions/{id}` | `PUT` | `ApiChannelGapController.updateSubscription()` (L110): JWT authenticated | Covered by controller/JWT default deny + static review |
| 130 | `/api/im/webhook/subscriptions/{id}/deliveries` | `GET` | `ApiChannelGapController.deliveries()` (L131): JWT authenticated | Covered by controller/JWT default deny + static review |
| 131 | `/api/im/webhook/subscriptions/{id}/deliveries/{deliveryId}/retry` | `POST` | `ApiChannelGapController.retry()` (L140): JWT authenticated | Covered by controller/JWT default deny + static review |
| 132 | `/api/im/webhook/subscriptions/{id}/rotate-secret` | `POST` | `ApiChannelGapController.rotateSecret()` (L124): JWT authenticated | Covered by controller/JWT default deny + static review |
| 133 | `/api/im/webhook/subscriptions/{id}/test` | `POST` | `ApiChannelGapController.test()` (L148): JWT authenticated | Covered by controller/JWT default deny + static review |
| 134 | `/api/im/{kind:file|video|voice}/delete` | `POST` | `ApiImMediaController.delete()` (L72): JWT authenticated | Covered by controller/JWT default deny + static review |
| 135 | `/api/im/{kind:file|video|voice}/message/{id:[0-9]+}/authorization` | `GET` | `ApiImMediaController.authorization()` (L87): JWT authenticated | Covered by controller/JWT default deny + static review |
| 136 | `/api/im/{kind:file|video|voice}/receipt` | `GET` | `ApiImMediaController.receipt()` (L56): JWT authenticated | Covered by controller/JWT default deny + static review |
| 137 | `/api/im/{kind:file|video|voice}/upload` | `POST` | `ApiImMediaController.upload()` (L38): JWT authenticated | Covered by controller/JWT default deny + static review |
| 138 | `/api/im/{kind:file|video}/message/{id:[0-9]+}/preview` | `GET` | `ApiImMediaController.preview()` (L94): JWT authenticated | Covered by controller/JWT default deny + static review |
| 139 | `/api/invoice/cancel-request` | `POST` | `ApiUserInvoiceController.cancelRequest()` (L92): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 140 | `/api/invoice/commands/request` | `POST` | `ApiUserInvoiceController.commandReceipt()` (L98): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 141 | `/api/invoice/titles/delete-request` | `POST` | `ApiUserInvoiceController.deleteTitleRequest()` (L86): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 142 | `/api/location-share/personal/context` | `GET` | `ApiAppGapsPlayerController.shareContext()` (L38): JWT authenticated | Covered by controller/JWT default deny + static review |
| 143 | `/api/location-share/personal/grants` | `POST` | `ApiAppGapsPlayerController.shareCreate()` (L41): JWT authenticated | Targeted: ->400/400 (共享请求无效) |
| 144 | `/api/location-share/personal/grants/{grantId}/observations` | `POST` | `ApiAppGapsPlayerController.sharePublish()` (L59): JWT authenticated | Covered by controller/JWT default deny + static review |
| 145 | `/api/location-share/personal/owned` | `GET` | `ApiAppGapsPlayerController.shareOwned()` (L50): JWT authenticated | Covered by controller/JWT default deny + static review |
| 146 | `/api/location-share/personal/received` | `GET` | `ApiAppGapsPlayerController.shareReceived()` (L53): JWT authenticated | Covered by controller/JWT default deny + static review |
| 147 | `/api/location-share/personal/received/events` | `GET` | `ApiAppGapsPlayerController.shareEvents()` (L56): JWT authenticated | Covered by controller/JWT default deny + static review |
| 148 | `/api/location-share/personal/requests/{requestKey}` | `GET` | `ApiAppGapsPlayerController.shareRequest()` (L44): JWT authenticated | Covered by controller/JWT default deny + static review |
| 149 | `/api/location-share/personal/requests/{requestKey}/revoke` | `POST` | `ApiAppGapsPlayerController.shareRevoke()` (L47): JWT authenticated | Covered by controller/JWT default deny + static review |
| 150 | `/api/login/code` | `POST` | `ApiLoginController.logincode()` (L186): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 151 | `/api/marketing/extra-seat/orders` | `GET` | `ApiMarketingExtraSeatController.myExtraSeats()` (L48): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 152 | `/api/marketing/extra-seat/quote` | `POST` | `ApiMarketingExtraSeatController.quote()` (L42): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 153 | `/api/marketing/product/orders/{orderNo}/cancel` | `POST` | `ApiMarketingProductController.cancel()` (L88): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 154 | `/api/marketing/product/orders/{orderNo}/pay` | `POST` | `ApiMarketingProductController.pay()` (L81): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 155 | `/api/marketing/revenue/order` | `GET` | `ApiMarketingRevenueController.orderAllocation()` (L56): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 156 | `/api/media/asset/{id}/preview` | `GET` | `ApiMediaAssetController.preview()` (L20): JWT authenticated | Covered by controller/JWT default deny + static review |
| 157 | `/api/media/asset/{id}/provenance` | `PUT` | `ApiMediaAssetController.provenance()` (L27): JWT authenticated | Covered by controller/JWT default deny + static review |
| 158 | `/api/media/asset/{id}/retry` | `POST` | `ApiMediaAssetController.retry()` (L23): JWT authenticated | Covered by controller/JWT default deny + static review |
| 159 | `/api/merchant/aftercare/manual-case-detail` | `POST` | `ApiMerchantAftercareController.manualCaseDetail()` (L50): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 160 | `/api/merchant/authoring/catalog` | `POST` | `ApiMerchantAuthoringController.catalog()` (L63): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 161 | `/api/merchant/authoring/draft` | `POST` | `ApiMerchantAuthoringController.confirmDraft()` (L101): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 162 | `/api/merchant/authoring/drafts/{draftId}/content` | `POST` | `ApiMerchantAuthoringController.content()` (L160): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 163 | `/api/merchant/authoring/drafts/{draftId}/facts` | `POST` | `ApiMerchantAuthoringController.facts()` (L155): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 164 | `/api/merchant/authoring/drafts/{draftId}/review` | `GET` | `ApiMerchantAuthoringController.review()` (L165): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 165 | `/api/merchant/authoring/facts` | `POST` | `ApiMerchantAuthoringController.storeFacts()` (L89): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 166 | `/api/merchant/authoring/product` | `POST` | `ApiMerchantAuthoringController.product()` (L78): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 167 | `/api/merchant/commerce/order/refund` | `POST` | `ApiMerchantCommerceController.refundOrder()` (L89): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 168 | `/api/merchant/crm/campaigns/{campaignId}/dispatch` | `POST` | `ApiMerchantCrmController.dispatchCampaign()` (L101): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 169 | `/api/merchant/crm/campaigns/{campaignId}/retry` | `POST` | `ApiMerchantCrmController.retryCampaign()` (L110): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 170 | `/api/merchant/crm/profiles/reconcile` | `POST` | `ApiMerchantCrmController.reconcileProfiles()` (L164): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 171 | `/api/merchant/customers` | `POST` | `ApiMerchantController.customers()` (L1908): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 172 | `/api/merchant/desk/anchor/check` | `POST` | `ApiMerchantDeskController.checkAnchor()` (L68): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 173 | `/api/merchant/desk/arrivals/today` | `POST` | `ApiMerchantDeskController.arrivalsToday()` (L120): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 174 | `/api/merchant/desk/frozen` | `POST` | `ApiMerchantDeskController.frozen()` (L175): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 175 | `/api/merchant/desk/frozen/page` | `POST` | `ApiMerchantDeskController.frozenPage()` (L181): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 176 | `/api/merchant/desk/metrics/dictionary` | `POST` | `ApiMerchantDeskController.metricDictionary()` (L188): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 177 | `/api/merchant/desk/products/delist` | `POST` | `ApiMerchantDeskController.delistProduct()` (L93): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 178 | `/api/merchant/desk/products/save` | `POST` | `ApiMerchantDeskController.saveProduct()` (L87): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 179 | `/api/merchant/desk/products/sku/slots` | `POST` | `ApiMerchantDeskController.skuSlots()` (L105): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 180 | `/api/merchant/desk/products/sku/slots/page` | `POST` | `ApiMerchantDeskController.skuSlotPage()` (L112): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 181 | `/api/merchant/desk/reconciliation` | `POST` | `ApiMerchantDeskController.reconciliation()` (L162): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 182 | `/api/merchant/desk/reconciliation/claims` | `POST` | `ApiMerchantDeskController.reconciliationClaims()` (L168): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 183 | `/api/merchant/desk/redeem/ocr-candidates` | `POST` | `ApiMerchantDeskController.ocrCandidates()` (L136): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 184 | `/api/merchant/desk/redeem/resolve` | `POST` | `ApiMerchantDeskController.resolveCode()` (L126): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 185 | `/api/merchant/desk/verdicts` | `POST` | `ApiMerchantDeskController.verdicts()` (L142): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 186 | `/api/merchant/desk/verdicts/detail` | `POST` | `ApiMerchantDeskController.verdictDetail()` (L149): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 187 | `/api/merchant/desk/verdicts/evidence` | `POST` | `ApiMerchantDeskController.submitEvidence()` (L156): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 188 | `/api/merchant/funnel` | `POST` | `ApiMerchantController.funnel()` (L1782): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 189 | `/api/merchant/offer-withdrawal/request/current` | `GET` | `ApiMerchantGapController.offerCurrent()` (L102): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 190 | `/api/merchant/offer-withdrawal/request/result` | `GET` | `ApiMerchantGapController.offerResult()` (L107): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 191 | `/api/merchant/offer-withdrawal/request/withdraw` | `POST` | `ApiMerchantGapController.offerWithdraw()` (L113): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 192 | `/api/merchant/ops/prerequisites` | `GET` | `ApiMerchantOpsController.prerequisites()` (L26): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 193 | `/api/merchant/ops/public-detail` | `GET` | `ApiMerchantOpsController.publicDetail()` (L36): @Anonymous + SecurityConfig.permitAll (public catalog/auth/share) | Covered by controller/JWT default deny + static review |
| 194 | `/api/merchant/orders/ledger` | `POST` | `ApiMerchantController.ledgerOrders()` (L1836): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 195 | `/api/merchant/predict/pending` | `POST` | `ApiPredictSettleController.pending()` (L103): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 196 | `/api/merchant/reviews/manage/report` | `POST` | `ApiMerchantReviewController.reportAsMerchant()` (L116): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 197 | `/api/merchant/reviews/reply` | `POST` | `ApiMerchantReviewController.reply()` (L68): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 198 | `/api/merchant/reviews/reply/delete` | `POST` | `ApiMerchantReviewController.deleteReply()` (L92): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 199 | `/api/merchant/reviews/reply/update` | `POST` | `ApiMerchantReviewController.updateReply()` (L80): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 200 | `/api/merchant/settlements/ledger` | `POST` | `ApiMerchantController.settlementLedger()` (L1923): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 201 | `/api/minor/guardian/accept` | `POST` | `ApiMinorGuardianController.accept()` (L74): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 202 | `/api/minor/guardian/invite` | `POST` | `ApiMinorGuardianController.invite()` (L62): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 203 | `/api/minor/guardian/list` | `POST` | `ApiMinorGuardianController.list()` (L89): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 204 | `/api/minor/guardian/revoke` | `POST` | `ApiMinorGuardianController.revoke()` (L82): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 205 | `/api/minor/policy` | `POST` | `ApiMinorGuardianController.policy()` (L43): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 206 | `/api/minor/status` | `POST` | `ApiMinorGuardianController.status()` (L49): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 207 | `/api/mod-commerce/**` | `REQUEST` | `appgaps/ApiAppGapsWorkshopController.commerceOff()` (L52): JWT authenticated | Covered by controller/JWT default deny + static review |
| 208 | `/api/mod-installation/**` | `REQUEST` | `appgaps/ApiAppGapsWorkshopController.notEnabled()` (L60): JWT authenticated | Covered by controller/JWT default deny + static review |
| 209 | `/api/mod-owned-report/**` | `REQUEST` | `appgaps/ApiAppGapsWorkshopController.notEnabled()` (L60): JWT authenticated | Covered by controller/JWT default deny + static review |
| 210 | `/api/mod-settlement/**` | `REQUEST` | `appgaps/ApiAppGapsWorkshopController.notEnabled()` (L60): JWT authenticated | Covered by controller/JWT default deny + static review |
| 211 | `/api/mod-upgrade/**` | `REQUEST` | `appgaps/ApiAppGapsWorkshopController.notEnabled()` (L60): JWT authenticated | Covered by controller/JWT default deny + static review |
| 212 | `/api/native/home/return/account` | `GET` | `ApiNativeHomeController.account()` (L72): JWT authenticated | Covered by controller/JWT default deny + static review |
| 213 | `/api/native/home/return/policy` | `GET` | `ApiNativeHomeController.policy()` (L59): JWT authenticated | Covered by controller/JWT default deny + static review |
| 214 | `/api/native/home/return/receipt` | `GET` | `ApiNativeHomeController.receipt()` (L90): JWT authenticated | Covered by controller/JWT default deny + static review |
| 215 | `/api/native/home/return/restore` | `POST` | `ApiNativeHomeController.restore()` (L84): JWT authenticated | Covered by controller/JWT default deny + static review |
| 216 | `/api/native/home/return/runs` | `GET` | `ApiNativeHomeController.runs()` (L66): JWT authenticated | Covered by controller/JWT default deny + static review |
| 217 | `/api/native/home/return/verify` | `POST` | `ApiNativeHomeController.verify()` (L78): JWT authenticated | Covered by controller/JWT default deny + static review |
| 218 | `/api/official/broadcast-request` | `GET` | `ApiOfficialEventController.broadcastRequest()` (L425): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 219 | `/api/official/broadcast-request/cancel` | `POST` | `ApiOfficialEventController.cancelBroadcastCreation()` (L420): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 220 | `/api/official/broadcast/{id}/recover` | `POST` | `ApiOfficialEventController.recoverBroadcast()` (L457): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 221 | `/api/official/events/{id}/attendance` | `GET` | `ApiOfficialEventController.attendance()` (L62): JWT authenticated | Covered by controller/JWT default deny + static review |
| 222 | `/api/official/events/{id}/attendance/manage` | `GET` | `ApiOfficialEventController.attendanceManage()` (L65): JWT authenticated | Covered by controller/JWT default deny + static review |
| 223 | `/api/official/events/{id}/progress` | `GET` | `ApiOfficialEventController.progress()` (L196): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 224 | `/api/official/events/{id}/share-code` | `POST` | `ApiOfficialEventController.shareCode()` (L155): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 225 | `/api/official/v2/drafts` | `POST` | `ApiOfficialEventController.saveV2Draft()` (L351): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 226 | `/api/official/v2/events/{id}/organizer-invites` | `POST` | `ApiOfficialEventController.inviteOrganizer()` (L380): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 227 | `/api/official/v2/events/{id}/organizer-transfer` | `POST` | `ApiOfficialEventController.transferOrganizer()` (L390): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 228 | `/api/official/v2/events/{id}/pause` | `POST` | `ApiOfficialEventController.pauseV2()` (L400): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 229 | `/api/official/v2/events/{id}/resume` | `POST` | `ApiOfficialEventController.resumeV2()` (L411): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 230 | `/api/official/v2/events/{id}/schedule` | `POST` | `ApiOfficialEventController.scheduleV2()` (L371): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 231 | `/api/official/v2/organizer-invites/{partyId}/accept` | `POST` | `ApiOfficialEventController.acceptOrganizerInvitation()` (L234): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 232 | `/api/official/v2/organizer-invites/{partyId}/decline` | `POST` | `ApiOfficialEventController.declineOrganizerInvitation()` (L244): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 233 | `/api/orient/geo/normalize` | `POST` | `ApiOrientController.normalizeGeo()` (L44): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 234 | `/api/orient/safety/rule` | `POST` | `ApiOrientController.saveSafetyRule()` (L166): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 235 | `/api/orient/scoring/session/{sessionId}/recovery` | `POST` | `ApiOrientScoringController.recovery()` (L42): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 236 | `/api/orient/task/alternative/review` | `POST` | `ApiOrientController.reviewAlternative()` (L114): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 237 | `/api/platform-pass/orders/{orderNo}/cancel` | `POST` | `ApiPlatformPassController.cancel()` (L89): OwnershipUtils/owner check | Covered by controller/JWT default deny + static review |
| 238 | `/api/platform-pass/orders/{orderNo}/confirm` | `POST` | `ApiPlatformPassController.confirm()` (L77): OwnershipUtils/owner check | Covered by controller/JWT default deny + static review |
| 239 | `/api/platform-pass/orders/{orderNo}/pay` | `POST` | `ApiPlatformPassController.pay()` (L71): OwnershipUtils/owner check | Covered by controller/JWT default deny + static review |
| 240 | `/api/platform-pass/orders/{orderNo}/refund-apply` | `POST` | `ApiPlatformPassController.refundApply()` (L83): OwnershipUtils/owner check | Covered by controller/JWT default deny + static review |
| 241 | `/api/play/achievements` | `POST` | `ApiPlayProgressController.achievements()` (L368): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 242 | `/api/play/check/bargain` | `POST` | `ApiPlayCheckController.bargain()` (L110): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 243 | `/api/play/check/momentum` | `POST` | `ApiPlayCheckController.momentum()` (L128): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 244 | `/api/play/check/rest` | `POST` | `ApiPlayCheckController.rest()` (L96): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 245 | `/api/play/help/advanced` | `GET` | `ApiPlayHelpController.viewAdvanced()` (L131): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 246 | `/api/play/help/advanced/skip` | `POST` | `ApiPlayHelpController.skipAdvanced()` (L162): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 247 | `/api/play/help/advanced/use` | `POST` | `ApiPlayHelpController.useAdvanced()` (L142): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 248 | `/api/play/journey/call` | `POST` | `ApiPlayJourneyActionController.call()` (L61): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 249 | `/api/play/seat-invitations/{invitationId}/release` | `POST` | `ApiRegistrationSeatInvitationController.release()` (L71): OwnershipUtils/owner check | Covered by controller/JWT default deny + static review |
| 250 | `/api/preset/style/list` | `GET` | `ApiPresetController.styleList()` (L53): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 251 | `/api/product/like` | `POST` | `ApiProductController.productLike()` (L156): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 252 | `/api/product/mylikelist` | `POST` | `ApiProductController.myLikeList()` (L202): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 253 | `/api/recommendation/list` | `POST` | `ApiRecommendationController.list()` (L27): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 254 | `/api/register` | `POST` | `ApiLoginController.register()` (L441): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 255 | `/api/registration/callback` | `POST` | `ApiRegistrationController.payCallback()` (L605): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 256 | `/api/registration/ecommerce-callback` | `POST` | `ApiRegistrationController.ecommercePayCallback()` (L640): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 257 | `/api/registration/ecommerce-combine-callback` | `POST` | `ApiRegistrationController.ecommerceCombinePayCallback()` (L658): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 258 | `/api/registration/ecommerce-refund-callback` | `POST` | `ApiRegistrationController.ecommerceRefundCallback()` (L649): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 259 | `/api/registration/refund-callback` | `POST` | `ApiRegistrationController.refundCallback()` (L628): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 260 | `/api/registration/verify_answer` | `POST` | `ApiRegistrationController.VerifyAnswer()` (L440): JWT authenticated | Covered by controller/JWT default deny + static review |
| 261 | `/api/registration/xpay/confirm` | `POST` | `ApiRegistrationController.xpayConfirm()` (L218): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 262 | `/api/reward-pledge/awards/{awardId}/return-to-pool` | `POST` | `ApiRewardPledgeController.returnToPool()` (L115): MerchantAccessService | Covered by controller/JWT default deny + static review |
| 263 | `/api/reward-pledge/compensations` | `POST` | `ApiRewardPledgeController.compensate()` (L121): MerchantAccessService | Covered by controller/JWT default deny + static review |
| 264 | `/api/reward-pledge/ops/{op}/{key}` | `GET` | `ApiRewardPledgeController.readOp()` (L99): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 265 | `/api/reward-pledge/review/{versionId}` | `GET` | `ApiRewardPledgeController.reviewVersion()` (L92): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 266 | `/api/reward-pledge/versions/{versionId}/hold` | `POST` | `ApiRewardPledgeController.hold()` (L75): JWT authenticated | Covered by controller/JWT default deny + static review |
| 267 | `/api/reward-pledge/versions/{versionId}/withdraw` | `POST` | `ApiRewardPledgeController.withdraw()` (L70): JWT authenticated | Covered by controller/JWT default deny + static review |
| 268 | `/api/reward-pledge/{pledgeId}` | `GET` | `ApiRewardPledgeController.read()` (L105): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 269 | `/api/reward-pledge/{pledgeId}/confirm` | `POST` | `ApiRewardPledgeController.confirm()` (L48): JWT authenticated | Covered by controller/JWT default deny + static review |
| 270 | `/api/reward-pledge/{pledgeId}/freeze` | `POST` | `ApiRewardPledgeController.freeze()` (L63): JWT authenticated | Covered by controller/JWT default deny + static review |
| 271 | `/api/reward-pledge/{pledgeId}/revise` | `POST` | `ApiRewardPledgeController.revise()` (L53): JWT authenticated | Covered by controller/JWT default deny + static review |
| 272 | `/api/reward-pledge/{pledgeId}/stats` | `GET` | `ApiRewardPledgeController.stats()` (L110): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 273 | `/api/reward-pledge/{pledgeId}/submit` | `POST` | `ApiRewardPledgeController.submit()` (L43): JWT authenticated | Covered by controller/JWT default deny + static review |
| 274 | `/api/rewards/noncash/cases/{caseId}` | `GET` | `ApiNonCashRewardController.readCase()` (L96): JWT authenticated | Covered by controller/JWT default deny + static review |
| 275 | `/api/rewards/noncash/subjects/{subjectId}` | `GET` | `ApiNonCashRewardController.catalog()` (L72): JWT authenticated | Covered by controller/JWT default deny + static review |
| 276 | `/api/rewards/noncash/versions/{versionId}` | `GET` | `ApiNonCashRewardController.offer()` (L79): JWT authenticated | Covered by controller/JWT default deny + static review |
| 277 | `/api/rewards/noncash/{awardId}` | `GET` | `ApiNonCashRewardController.detail()` (L58): JWT authenticated | Covered by controller/JWT default deny + static review |
| 278 | `/api/roam/hangout/close` | `POST` | `ApiRoamHangoutController.close()` (L99): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 279 | `/api/roam/hangout/create` | `POST` | `ApiRoamHangoutController.create()` (L57): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 280 | `/api/roam/hangout/detail` | `GET` | `ApiRoamHangoutController.detail()` (L66): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 281 | `/api/roam/hangout/join` | `POST` | `ApiRoamHangoutController.join()` (L79): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 282 | `/api/roam/hangout/leave` | `POST` | `ApiRoamHangoutController.leave()` (L85): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 283 | `/api/roam/hangout/report` | `POST` | `ApiRoamHangoutController.report()` (L113): JWT authenticated | Covered by controller/JWT default deny + static review |
| 284 | `/api/roam/passport/summary` | `GET` | `ApiRoamPassportController.summary()` (L25): JWT authenticated | Covered by controller/JWT default deny + static review |
| 285 | `/api/roam/share/revoke-token` | `POST` | `ApiRoamShareController.revokeToken()` (L123): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 286 | `/api/spoiler/preference` | `POST` | `ApiSpoilerController.preference()` (L39): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 287 | `/api/spoiler/reveal` | `POST` | `ApiSpoilerController.reveal()` (L50): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 288 | `/api/spoiler/state` | `POST` | `ApiSpoilerController.state()` (L26): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 289 | `/api/status` | `GET` | `ApiServiceStatusController.get()` (L19): @Anonymous (public endpoint; parameter/entity scoped where applicable) | Covered by controller/JWT default deny + static review |
| 290 | `/api/support/tickets/detail` | `POST` | `appgaps/ApiAppGapsSupportController.detail()` (L32): JWT authenticated | Covered by controller/JWT default deny + static review |
| 291 | `/api/support/tickets/list` | `POST` | `appgaps/ApiAppGapsSupportController.list()` (L24): JWT authenticated | Covered by controller/JWT default deny + static review |
| 292 | `/api/team/invite-code/rotate` | `POST` | `ApiPlayTeamController.rotateInviteCode()` (L118): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 293 | `/api/template/adoption-home` | `POST` | `ApiTemplateController.adoptionHome()` (L282): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 294 | `/api/template/groupByCategory` | `POST` | `ApiTemplateController.getTemplatesGroupByCategory()` (L642): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 295 | `/api/template/topic-template/club-interest` | `POST` | `ApiTopicTemplateController.clubInterest()` (L142): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 296 | `/api/template/topic-template/place-country` | `POST` | `ApiTopicTemplateController.placeCountry()` (L333): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 297 | `/api/topic/attributes` | `DELETE` | `ApiTopicAttributeController.deleteAttribute()` (L79): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 298 | `/api/topic/attributes` | `GET` | `ApiTopicAttributeController.attributes()` (L53): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 299 | `/api/topic/attributes` | `POST` | `ApiTopicAttributeController.addAttribute()` (L59): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 300 | `/api/topic/attributes` | `PUT` | `ApiTopicAttributeController.updateAttribute()` (L66): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 301 | `/api/topic/attributes/order` | `PUT` | `ApiTopicAttributeController.orderAttributes()` (L73): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 302 | `/api/topic/list-to-merchant` | `POST` | `ApiTopicController.topicListToMerchant()` (L2863): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 303 | `/api/topic/state-keys/label` | `PUT` | `ApiTopicAttributeController.rename()` (L97): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 304 | `/api/topic/state-keys/start` | `PUT` | `ApiTopicAttributeController.startWith()` (L104): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 305 | `/api/topic/state-keys/usage` | `GET` | `ApiTopicAttributeController.usage()` (L111): JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 306 | `/api/topic/state-keys/validate` | `POST` | `ApiTopicStateKeyController.validateStateKeys()` (L157): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 307 | `/api/track/delete` | `POST` | `ApiTrackController.delete()` (L170): OwnershipUtils/owner check + JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 308 | `/api/track/draft` | `POST` | `ApiTrackController.saveDraft()` (L60): OwnershipUtils/owner check + JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 309 | `/api/track/drafts` | `POST` | `ApiTrackController.myDrafts()` (L88): OwnershipUtils/owner check + JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 310 | `/api/track/to-topic` | `POST` | `ApiTrackController.toTopic()` (L102): OwnershipUtils/owner check + JWT currentMemberId() scope | Covered by controller/JWT default deny + static review |
| 311 | `/api/user/balance/statistics` | `POST` | `ApiUmsMemberController.getBalanceStatistics()` (L192): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 312 | `/api/user/income/brand-list` | `POST` | `ApiUmsMemberController.getBrandIncomeList()` (L431): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 313 | `/api/user/income/brand-stats` | `POST` | `ApiUmsMemberController.getBrandIncomeStats()` (L380): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 314 | `/api/user/income/breakdown` | `POST` | `ApiUmsMemberController.getIncomeBreakdown()` (L317): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 315 | `/api/user/income/invite-stats` | `POST` | `ApiUmsMemberController.getInviteIncomeStats()` (L359): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 316 | `/api/user/income/topic-list` | `POST` | `ApiUmsMemberController.getTopicIncomeList()` (L401): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 317 | `/api/user/income/topic-stats` | `POST` | `ApiUmsMemberController.getTopicIncomeStats()` (L338): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 318 | `/api/user/statistics_chat` | `POST` | `ApiUmsMemberController.statisticsChat()` (L290): SecurityConfig.permitAll (public config/health/landing) | Covered by controller/JWT default deny + static review |
| 319 | `/api/v1/community/comments/{id}/actions/LIKE` | `DELETE` | `ApiCommunityPostController.unlikeComment()` (L179): JWT authenticated | Covered by controller/JWT default deny + static review |
| 320 | `/api/v1/community/me/bookmarks` | `GET` | `ApiCommunityPostController.bookmarks()` (L84): JWT authenticated | Covered by controller/JWT default deny + static review |
| 321 | `/api/v1/community/me/posts` | `GET` | `ApiCommunityPostController.ownPosts()` (L90): JWT authenticated | Covered by controller/JWT default deny + static review |
| 322 | `/api/v1/community/members/{memberId}/mute` | `DELETE` | `ApiCommunityPostController.unmute()` (L191): JWT authenticated | Covered by controller/JWT default deny + static review |
| 323 | `/api/v1/community/members/{memberId}/mute` | `POST` | `ApiCommunityPostController.mute()` (L186): JWT authenticated | Covered by controller/JWT default deny + static review |
| 324 | `/api/v1/community/posts/{id}/actions/{type}` | `DELETE` | `ApiCommunityPostController.unact()` (L133): JWT authenticated | Covered by controller/JWT default deny + static review |
| 325 | `/api/v1/community/posts/{id}/shares` | `POST` | `ApiCommunityPostController.share()` (L166): JWT authenticated | Covered by controller/JWT default deny + static review |
| 326 | `/api/wallet/balance` | `POST` | `ApiWalletController.balance()` (L46): JWT authenticated + service-layer caller scope | Targeted: pA->200/200 (操作成功) |
| 327 | `/api/wallet/withdraw` | `POST` | `ApiWalletController.withdraw()` (L72): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 328 | `/api/wallet/withdraws` | `POST` | `ApiWalletController.withdraws()` (L89): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 329 | `/api/withdrawal/delete` | `POST` | `ApiWithdrawalController.withdrawalDelete()` (L111): JWT authenticated + service-layer caller scope | Targeted: pA->200/500 (仅本人待审核的提现申请可撤回) |
| 330 | `/api/workshop/capabilities` | `GET` | `ApiWorkshopController.capabilities()` (L17): JWT authenticated | Covered by controller/JWT default deny + static review |
| 331 | `/api/workshop/creator/pending-packages/**` | `REQUEST` | `appgaps/ApiAppGapsWorkshopController.notEnabled()` (L60): JWT authenticated | Covered by controller/JWT default deny + static review |
| 332 | `/api/workshop/creator/public-theme-use/**` | `REQUEST` | `appgaps/ApiAppGapsWorkshopController.notEnabled()` (L60): JWT authenticated | Covered by controller/JWT default deny + static review |
| 333 | `/api/workshop/purchased/install/history` | `POST` | `ApiWorkshopController.legacy()` (L104): JWT authenticated | Covered by controller/JWT default deny + static review |
| 334 | `/api/workshop/purchased/install/status` | `POST` | `ApiWorkshopController.legacy()` (L104): JWT authenticated | Covered by controller/JWT default deny + static review |
| 335 | `/api/workshop/purchased/install/submit` | `POST` | `ApiWorkshopController.legacy()` (L104): JWT authenticated | Covered by controller/JWT default deny + static review |
| 336 | `/api/workshop/purchased/install/targets` | `POST` | `ApiWorkshopController.legacy()` (L104): JWT authenticated | Covered by controller/JWT default deny + static review |
| 337 | `/api/workshop/v2/apple-iap/confirm` | `POST` | `ApiWorkshopAppleIapController.confirm()` (L21): JWT authenticated | Covered by controller/JWT default deny + static review |
| 338 | `/api/workshop/v2/apple-iap/notifications` | `POST` | `ApiWorkshopAppleIapController.notification()` (L37): JWT authenticated | Covered by controller/JWT default deny + static review |
| 339 | `/api/workshop/v2/apple-iap/reconcile` | `POST` | `ApiWorkshopAppleIapController.reconcile()` (L31): JWT authenticated | Covered by controller/JWT default deny + static review |
| 340 | `/api/workshop/v2/apple-iap/restore` | `POST` | `ApiWorkshopAppleIapController.restore()` (L26): JWT authenticated | Covered by controller/JWT default deny + static review |
| 341 | `/api/workshop/v2/author/source` | `POST` | `ApiWorkshopController.authorSource()` (L20): JWT authenticated | Covered by controller/JWT default deny + static review |
| 342 | `/api/workshop/v2/author/versions/detail` | `POST` | `ApiWorkshopController.authorVersion()` (L32): JWT authenticated | Targeted: ->200/500 (Required request parameter 'purchased_ve) |
| 343 | `/api/workshop/v2/author/versions/freeze` | `POST` | `ApiWorkshopController.freeze()` (L24): JWT authenticated | Covered by controller/JWT default deny + static review |
| 344 | `/api/workshop/v2/author/versions/submit` | `POST` | `ApiWorkshopController.submitVersion()` (L28): JWT authenticated | Covered by controller/JWT default deny + static review |
| 345 | `/api/workshop/v2/author/versions/withdraw` | `POST` | `ApiWorkshopController.withdrawVersion()` (L36): JWT authenticated | Covered by controller/JWT default deny + static review |
| 346 | `/api/workshop/v2/offer/detail` | `POST` | `ApiWorkshopController.offer()` (L45): JWT authenticated | Covered by controller/JWT default deny + static review |
| 347 | `/api/workshop/v2/offer/list` | `POST` | `ApiWorkshopController.shelf()` (L40): JWT authenticated | Covered by controller/JWT default deny + static review |
| 348 | `/api/workshop/v2/purchase/create` | `POST` | `ApiWorkshopController.purchase()` (L48): JWT authenticated | Covered by controller/JWT default deny + static review |
| 349 | `/api/workshop/v2/purchase/pay` | `POST` | `ApiWorkshopController.pay()` (L52): JWT authenticated | Covered by controller/JWT default deny + static review |
| 350 | `/api/workshop/v2/purchase/reconcile` | `POST` | `ApiWorkshopController.reconcile()` (L59): JWT authenticated | Covered by controller/JWT default deny + static review |
| 351 | `/api/workshop/v2/purchase/refund` | `POST` | `ApiWorkshopController.refund()` (L63): JWT authenticated | Covered by controller/JWT default deny + static review |
| 352 | `/api/workshop/v2/purchase/refund/reconcile` | `POST` | `ApiWorkshopController.refundStatus()` (L67): JWT authenticated | Covered by controller/JWT default deny + static review |
| 353 | `/api/workshop/v2/purchase/status` | `POST` | `ApiWorkshopController.order()` (L56): JWT authenticated | Covered by controller/JWT default deny + static review |
| 354 | `/api/workshop/v2/purchased/install/history` | `POST` | `ApiWorkshopController.history()` (L93): JWT authenticated | Covered by controller/JWT default deny + static review |
| 355 | `/api/workshop/v2/purchased/install/status` | `POST` | `ApiWorkshopController.installStatus()` (L90): JWT authenticated | Covered by controller/JWT default deny + static review |
| 356 | `/api/workshop/v2/purchased/install/submit` | `POST` | `ApiWorkshopController.install()` (L86): JWT authenticated | Covered by controller/JWT default deny + static review |
| 357 | `/api/workshop/v2/purchased/install/targets` | `POST` | `ApiWorkshopController.targets()` (L81): JWT authenticated | Covered by controller/JWT default deny + static review |
| 358 | `/api/workshop/v2/purchased/revoke` | `POST` | `ApiWorkshopController.revoke()` (L78): JWT authenticated | Covered by controller/JWT default deny + static review |
| 359 | `/api/world/home` | `POST` | `ApiIpWorldController.home()` (L26): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
| 360 | `/api/world/quests` | `POST` | `ApiIpWorldController.quests()` (L35): JWT authenticated + service-layer caller scope | Covered by controller/JWT default deny + static review |
