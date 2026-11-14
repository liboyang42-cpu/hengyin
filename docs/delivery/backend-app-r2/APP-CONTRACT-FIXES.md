# App-Side Contract Fixes (API Smoke Non-500 Triage)

This document lists every app-side contract mismatch identified during triage of the non-500 `BACKEND-ERROR` rows from `/data/workspace/api-smoke/results.json` against `hengyin` (`origin/integration/backend-app-r2` at `60717f583`) and the iOS `capgraph` snapshot (`app-uimain-3ac970ec2`, cross-checked against `DES-ag8`).

## 1. Encoding Mismatch: `multipart/form-data` vs `@RequestBody` JSON (5 endpoints)

`MerchantContentService.makeFormRequest` (`Core/MerchantContentService.swift:150`) delegates to `AuthRequestBuilder.makeFormRequest` (`Core/AuthRequestBuilder.swift:22-48`), which always sets `Content-Type: multipart/form-data; boundary=...` (line 39). On `origin/integration/backend-app-r2`, `ApiChapterMerchantNodeController` binds these five POST handlers with `@RequestBody NodeNpcRequest body` (`ApiChapterMerchantNodeController.java:260-357, 475-507`), which strictly requires `application/json`.

> Note: Sibling branch `fix/api-smoke-500` added `consumes = "!application/json"` `@ModelAttribute NodeNpcRequest` form compatibility bridges on `ApiChapterMerchantNodeController`; the App should still switch these 5 calls to `application/json` to match the canonical controller contract.

| Method & Path | Swift Site (`app-uimain-3ac970ec2` / `DES-ag8`) | Backend Expected Contract (`ApiChapterMerchantNodeController.java`) |
|---|---|---|
| `POST /api/merchant/chapter-node/npc/detail` | `Core/MerchantContentService.swift:299` (`.form(["nodeId": String(id)])`) | `@PostMapping("/npc/detail")` (`:260-275`), `Content-Type: application/json`, `{"nodeId": <Long>}` (`nodeId > 0` required) |
| `POST /api/merchant/chapter-node/npc/voice/status` | `Core/MerchantContentService.swift:300` (`.form(["nodeId": String(id)])`) | `@PostMapping("/npc/voice/status")` (`:312-339`), `Content-Type: application/json`, `{"nodeId": <Long>, "voiceRequestId": <String?>}` |
| `POST /api/merchant/chapter-node/npc/save` | `Core/MerchantContentCommands.swift:99` (`form("api/merchant/chapter-node/npc/save", ...)`) | `@PostMapping("/npc/save")` (`:277-292`), `Content-Type: application/json`, `{"nodeId": <Long>, "name": <String?>, "avatar": <String?>, "greeting": <String?>}` |
| `POST /api/merchant/chapter-node/npc/voice/enroll` | `Core/MerchantContentCommands.swift:102` (`form("api/merchant/chapter-node/npc/voice/enroll", ...)`) | `@PostMapping("/npc/voice/enroll")` (`:294-310`), `Content-Type: application/json`, `{"nodeId": <Long>, "voiceSample": <String?>, "voiceRequestId": <String?>, "voicePreviousIntentId": <Long?>, "voiceDuplicateChargeConsent": <Boolean?>}` |
| `POST /api/merchant/chapter-node/npc/voice/reset` | `Core/MerchantContentCommands.swift:103` (`form("api/merchant/chapter-node/npc/voice/reset", ...)`) | `@PostMapping("/npc/voice/reset")` (`:344-360`), `Content-Type: application/json`, `{"nodeId": <Long>}` |

`NodeNpcRequest` DTO definition: `ApiChapterMerchantNodeController.java:475-507`.

---

## 2. Nonexistent Route Called by App (`404`)

| Method & Path | Swift Site (`app-uimain-3ac970ec2` / `DES-ag8`) | Backend Expected Contract |
|---|---|---|
| `POST /api/merchant/payout-account/save` | `Core/OwnerDecisionGuards.swift:226` (`saveAccount()` calls `post("api/merchant/payout-account/save", body: ["mutate": true])`); allowlists in `App/ReviewedPlayerWriteLeases.swift:13` and `Core/ChinaReviewedRouteCapability.swift:538,2592` | `ApiMerchantPayoutAccountController.java:20-27` only exposes read-only `POST /api/merchant/payout-account` (current merchant payout account status). No `/payout-account/save` endpoint exists on backend (`404`). Remove/disable `saveAccount()` and drop the dead route literal from allowlists. |

---

## 3. Retired Bare Route Literal (`404`) / V1 Purchased Path Cleanup

| Method & Path | Swift Site | Backend Expected Contract (`ApiWorkshopController.java`) |
|---|---|---|
| `GET /api/workshop/purchased` | `App/ReviewedPlayerWriteLeases.swift:1464`, `Core/RePoint10Routes.swift:32`, `Core/ChinaReviewedRouteCapability.swift:1718-1719` (`app-uimain-3ac970ec2` and `DES-ag8`) | No handler exists for bare `/api/workshop/purchased` (`404`). Paid purchased library/install routes live under `POST /api/workshop/v2/purchased/{list,detail,revoke,install/targets,install/submit,install/status,install/history,installed-text/detail}` (`ApiWorkshopController.java:70-96`), while legacy v1 subpaths return `409 WORKSHOP_CONTRACT_UPGRADE_REQUIRED` (`:97-100`). Note: `DES-ag8` callers (`Core/WorkshopPurchasedService.swift:85`, `Core/WorkshopPaidInstallService.swift:129`, `Core/WorkshopPaidInstalledTextService.swift:101`, `App/WorkshopPurchasedReadRoute.swift:16,23`) already target `/api/workshop/v2/purchased/*`; remove the stale bare `/api/workshop/purchased` entry from allowlists/gap tables. (By contrast, `/api/workshop/purchased/professional-targets/*` and `/professional-operations/*` are live under `ApiWorkshopProfessionalController.java:19-60` with `POST` + JSON body.) |

---

## 4. Prefix-Only Router / Allowlist Literals Missing Path Parameters (`404`)

These Swift path prefixes omit `{id}` / child segments (or construct URLs via string concatenation), causing bare-prefix calls or static catalog extraction to hit `404` or wildcard catch-all `@GetMapping("/{id}")` handlers:

| Bare Literal | Swift Sites (`app-uimain-3ac970ec2` / `DES-ag8`) | Why It 404s & Backend Expected Contract |
|---|---|---|
| `/api/reward-pledge/versions` | `App/ReviewedPlayerWriteLeases.swift:791`, `Core/RewardPledge.swift:121-122` (matcher prefixes; real calls at `:426,429`), `Core/ChinaReviewedRouteCapability.swift:1530-1531` | Bare `GET /api/reward-pledge/versions` matches `@GetMapping("/{pledgeId}")` (`ApiRewardPledgeController.java:102-105`) with `pledgeId="versions"`, returning `404 NOT_FOUND`. Actual routes: `POST /api/reward-pledge/versions/{versionId}/withdraw` (`:68-71`) and `POST /api/reward-pledge/versions/{versionId}/hold` (`:72-75`). Use explicit templated route constants instead of the bare prefix. |
| `/api/rewards/noncash/subjects` | `App/ReviewedPlayerWriteLeases.swift:793`, `Core/RewardPledge.swift:96` (matcher; real call at `:274`), `Core/ChinaReviewedRouteCapability.swift:1537-1538` | Bare `GET` matches `ApiNonCashRewardController.detail(@PathVariable awardId)` (`ApiNonCashRewardController.java:56-68`) -> `404 NOT_FOUND`. Actual route: `GET /api/rewards/noncash/subjects/{subjectId}` (`:69-74`). |
| `/api/rewards/noncash/versions` | `App/ReviewedPlayerWriteLeases.swift:793`, `Core/RewardPledge.swift:50` (`offerPrefix = "api/rewards/noncash/versions/"`), `Core/ChinaReviewedRouteCapability.swift:1539` | Bare `GET` matches `ApiNonCashRewardController.detail("/{awardId}")` (`:56-68`) -> `404 NOT_FOUND`. Actual route: `GET /api/rewards/noncash/versions/{versionId}` (`:75-82`, optional query `branch`, `subjectId`). |
| `/api/channels/member-state-requests` | `Core/BusinessRuntimeConfiguration.swift:171`, `Core/NativeChannelPersonalStateFlow.swift:167` (`"api/channels/member-state-requests/"+id`), `Core/ChinaReviewedRouteCapability.swift:802` | String concatenation emits bare prefix in route/capability tables, hitting channel wildcard `404 CHANNEL_NOT_FOUND`. Actual route: `GET /api/channels/member-state-requests/{requestId}` (`ApiChannelGovernanceController.java:147-151`). |
| `/api/friend/channel-invite` | `Core/NativeFriendChannelInviteService.swift:11` (`prefix = "api/friend/channel-invite/"`), `Core/ChinaReviewedRouteCapability.swift:1037` | Bare prefix is not mapped (`404`). Concrete endpoints (`ApiFriendController.java:181-222`): `GET /api/friend/channel-invite/picker` (`channel_id`, `section`, optional `cursor`), `POST /api/friend/channel-invite/command` (JSON), `GET /api/friend/channel-invite/batch` (`channel_id`, `request_id`), `GET /api/friend/channel-invite/notice` (`invite_id`), `GET /api/friend/channel-invite/landing` (`invite_id`), `GET /api/friend/channel-invite/mine` (`before`), `GET /api/friend/channel-invite/receipt` (`request_id`). |
| `/api/friend/team-invite` | `Core/NativeFriendTeamInviteService.swift:7` (`prefix = "api/friend/team-invite/"`), `Core/ChinaReviewedRouteCapability.swift:1057` | Bare prefix is not mapped (`404`). Concrete endpoints (`ApiFriendController.java:225-245`): `GET /api/friend/team-invite/picker` (`team_id`, `section`, optional `cursor`), `GET /api/friend/team-invite/landing` (`offer_id`), `GET /api/friend/team-invite/mine` (`before`), and `POST /api/team/original-request` (`:244`). |

---

## 5. JSON Key Name Mismatch (`POST /api/merchant/chapter-application/audit`)

| Method & Path | Swift Site (`DES-ag5` / `DES-ag8`) | Backend Expected Contract (`ApiMerchantChapterApplicationController.java`) |
|---|---|---|
| `POST /api/merchant/chapter-application/audit` | `Core/MerchantContentCommands.swift:88` (`auditApplication(id:approve:reason:)`) sends JSON body `{"id": id, "approve": approve, "reason": reason}` | `ApiMerchantChapterApplicationController.java:176-184` reads `applicationId`, `approved`, and `auditRemark`, returning code `500` (`"缺少审核结果"`) when `approved` is missing. Update the App payload keys to `{"applicationId": id, "approved": approve, "auditRemark": reason}` (without changing backend contract behavior that existing tests depend on). |
