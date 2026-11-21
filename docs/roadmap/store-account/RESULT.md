# P1a account-and-moderation (store-account) — backend receipt

Branch `feature/r-store-account` (base `fix/r5-be-infra-stubs` @ 32b425c33). App side: `chengyin-app` branch `cloud/r-store-account`.

## Findings

| ID | Status | Notes |
|---|---|---|
| FLOW-03 (P0) Apple-only account cannot delete itself | **Closed (backend)** | `/api/user/deregister/apply` accepts a fresh Sign in with Apple for accounts with no phone and no WeChat openid. Apple tokens are revoked after erasure. |
| SEC-12 (P0) moderation needs a WeChat openid | **Closed for gate-based publishing; partly open for direct callers** | See "Left open". |

## What changed

### FLOW-03 — Apple re-confirmation and token revocation
- `chengyinhub-system/.../business/account/apple/AppleIdentityReconfirmationService.java` (new): verifies the identity token: RS256 against Apple's published key for its `kid` (reuses `UsAppleKeySource`: cached JWKS, refresh on unknown `kid`), `iss = https://appleid.apple.com`, `aud =` the bundle id in `sys_config apple.clientId` (the value the Apple login already uses), not expired, `iat` no older than 10 minutes, `sub` equal to the member's stored `apple_id` (constant-time). Single use (Redis `setIfAbsent` on the token digest, 15 min), 10 tries/min per member and per IP. Results: `CONFIRMED`, `INVALID`, `UNAVAILABLE` (missing client id, JWKS or Redis down).
- `.../account/apple/AppleTokenRevocationService.java` (new):
  - `prepare` (at apply time): exchanges the authorization code at `/auth/token` for a refresh token (the code lives 5 minutes) and stores it **encrypted** with `PiiCryptoService` (`chengyin.pii.key`). Without that key the token is not stored.
  - `revokeAfterDeletion` (after erasure commits): `POST /auth/revoke` with `token_type_hint=refresh_token`, then wipes the ciphertext.
  - `discard` (owner cancels): wipes the ciphertext.
  - The client secret is an ES256 JWT (5 min) built from `APPLE_SIGNIN_KEY_ID`, `APPLE_SIGNIN_TEAM_ID`, `APPLE_SIGNIN_PRIVATE_KEY` (PKCS#8 PEM, literal `\n` accepted). If they are missing, it logs a warning and records `PENDING_CONFIG`. **It never throws into the deletion and never fails it.**
- `chengyinhub-admin/sql/migration_20261115_store_account.sql` (new): `ums_apple_token_revocation` (`CREATE TABLE IF NOT EXISTS`, rollback note at the top). It stores error codes only, never token text or response bodies.
- `ApiAccountDeregistrationController.java`: `apply` is now the HTTP-mapped 6-argument method (`smscode, wxcode, appleIdentityToken, appleAuthorizationCode, requestId`).
  - Order: a bound phone → SMS. Else an Apple-only account (no openid, has `apple_id`) → Apple. Else WeChat.
  - An Apple token cannot bypass SMS or WeChat.
  - New errors: `Apple 身份确认失败，请重新确认本人` and `Apple 身份确认暂时不可用，请稍后重试`.
  - The old 3- and 4-argument Java signatures are kept as delegates. `cancel` wipes a stored Apple token.
  - Route and existing field names are unchanged.
- `AccountDeregistrationServiceImpl.java`: precheck reports `verificationMethod = "APPLE"` for Apple-only accounts (before this it reported `UNAVAILABLE`). `executeDue` calls `revokeAfterDeletion` after each committed erasure, inside a try/catch.

### SEC-12 — provider routing behind `ContentSafetyGate`
- New package `chengyinhub-system/.../business/moderation/`:
  - `ContentModerationProvider` (interface) and `ModerationVerdict` (PASS / RISK / REVIEW / UNAVAILABLE).
  - `WechatModerationProvider`: the existing msgSecCheck/mediaCheckAsync, used only for authors with an openid.
  - `TencentCloudModerationProvider`: TMS `TextModeration` and IMS `ImageModeration`, version 2020-12-29. Requests are TC3-HMAC-SHA256 signed and synchronous. Text is sent in 4000-code-point chunks, and images only as public http(s) URLs. Errors and unknown answers count as UNAVAILABLE, never PASS. Keys and content are never logged.
  - `ModerationRouter`: an openid → WeChat (unchanged). No openid → Tencent if configured. Otherwise none.
- `ContentSafetyGateImpl.java`: the local keyword pre-filter still runs first. For authors without an openid and with Tencent configured, Tencent judges the text and the images:
  - Text: Block → BLOCK `TEXT_RISK`. Review → HOLD `TEXT_REVIEW`. Outage → HOLD `DETECT_ERROR`.
  - Images in post-review mode: Block → BLOCK `IMAGE_RISK`. Anything else that is not a pass → HOLD `MEDIA_UNCHECKED`.
  - Structured or pre-review media rules are unchanged.
  - With no provider, the existing HOLD path applies: author-only, plus an audit task and the recheck queue. It never refuses.
- `WxContentSecurityServiceImpl.checkText`: a blank openid with Tencent configured is judged by Tencent, and the result is mapped onto the existing string contract (null / violation text / `REVIEW_REQUIRED` / `MSG_BUSY`). This fixes the direct callers (chat, comments, AI-character input/output, location cards…) for app accounts. Authors with an openid are unchanged.
- `ContentSafetyRecheckServiceImpl.java`: when the author has no openid, the recheck uses Tencent for text and images. If no provider can judge, or Tencent says Review, the row ends in a new state `5` (`HUMAN_REVIEW`) and the audit task stays open for a person. Before this, such rows retried until they were exhausted and were then auto-rejected.
- `application.yml`: documents `chengyin.moderation.tencent.*`, read from environment variables only:

| Key | Env | Default |
|---|---|---|
| `chengyin.moderation.tencent.enabled` | `CHENGYIN_MODERATION_TENCENT_ENABLED` | `true` (kill switch; keys still required) |
| `chengyin.moderation.tencent.secret-id` | `TENCENTCLOUD_SECRET_ID` | empty → provider off |
| `chengyin.moderation.tencent.secret-key` | `TENCENTCLOUD_SECRET_KEY` | empty → provider off |
| `chengyin.moderation.tencent.region` | `CHENGYIN_MODERATION_TENCENT_REGION` | `ap-guangzhou` |
| `chengyin.moderation.tencent.text-biz-type` / `image-biz-type` | `CHENGYIN_MODERATION_TENCENT_TEXT_BIZ_TYPE` / `_IMAGE_BIZ_TYPE` | empty (console default policy) |
| Apple revocation | `APPLE_SIGNIN_KEY_ID`, `APPLE_SIGNIN_TEAM_ID`, `APPLE_SIGNIN_PRIVATE_KEY` | empty → revocation recorded as pending |

### Tests (new or extended)
- `AppleIdentityReconfirmationServiceTest` (11 tests). It checks a valid token, then rejects: wrong aud, wrong iss, expired, stale iat, another account's `sub`, forged signature, unknown `kid` or malformed token, replay, and rate limit. A JWKS outage or a missing bundle id gives UNAVAILABLE.
- `AppleTokenRevocationServiceTest` (6, H2): the full exchange → encrypted store → revoke path, with the ES256 client secret checked (kid/iss/sub/aud/alg). Also: no key gives pending with no calls; a key removed later keeps the ciphertext; Apple errors are recorded and never thrown, even with the table missing; cancel wipes the token; with no PII key nothing is stored.
- `ApiAccountDeregistrationControllerTest` (+5): an Apple-only account deletes and prepares revocation. An invalid or missing token, or an unavailable check, does not delete. An Apple token cannot bypass SMS or WeChat. Cancel wipes the token.
- `AccountDeregistrationServiceImplTest` (+2): precheck gives APPLE/WECHAT/SMS/UNAVAILABLE. Revocation runs after `complete`, and a revocation exception does not fail the deletion.
- `TencentCloudModerationProviderTest` (6) and `ModerationRoutingTest` (9). They cover routing with and without an openid (gate, legacy `checkText`, recheck queue), that the keyword pre-filter runs first, and the human-review handoff.

## Verification (commands run, results)
```
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -DskipTests compile                      # OK
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -pl chengyinhub-admin -am test \
  -Dtest='AppleIdentityReconfirmationServiceTest,AppleTokenRevocationServiceTest,ApiAccountDeregistrationControllerTest,AccountDeregistrationServiceImplTest,TencentCloudModerationProviderTest,ModerationRoutingTest,ContentSafetyGateImplTest,ContentSafetyRecheckServiceImplTest,WxContentSecurityServiceImplTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
  # 11 + 6 + 15 + 18 + 6 + 9 + 24 + 13 + 52 tests, 0 failures
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -pl chengyinhub-admin -am test               # full module
  # Tests run: 12600, Failures: 1, Errors: 34, Skipped: 559
```
The full-module failures are **all pre-existing and unrelated**:
- 34 errors in 14 `com.chengyinhub.business.it.*MyBatisTest` classes: "Communications link failure", because there is no MySQL in this container.
- 1 failure, `ChapterFlowWriteServiceTest.openBundleReviewCannotBeBypassedBySwitchingToSimpleMode`. It also fails on the untouched base `origin/fix/r5-be-infra-stubs` (checked in a separate worktree).

## Not verified here
- Real calls to Apple (`/auth/keys`, `/auth/token`, `/auth/revoke`) and to Tencent Cloud TMS/IMS. Both need real keys and network. The HTTP layer is covered by test doubles only; the TC3 signing is checked for shape and determinism, not against a live endpoint.
- The migration against MySQL (it uses only `CREATE TABLE IF NOT EXISTS`, and H2 MODE=MySQL accepts the same columns).

## Left open
- **Direct `checkText` callers with no provider at all.** Chat, comments, AI-character turns and similar callers have no "author-only" state. For an app account with Tencent **not** configured, they still refuse with the honest `内容审核暂时不可用，请联系平台` instead of holding. Gate-based publishing (community posts, topics, activities, official events, club sessions) holds for the author and queues human review as required. Configuring Tencent closes this gap.
- **Chat images (`ImServiceImpl` MSG_IMAGE) and other async `submitMediaCheck*` image paths for app accounts** still depend on the WeChat async check. Without an openid they are not sent and behave as before. A synchronous Tencent image check at those call sites is a follow-up.
- An Apple account that later binds a phone deletes by SMS, so no Apple re-auth runs and no token is revoked. See the questions below.

## Owner actions
1. **Sign in with Apple key for revocation:** create a key with "Sign in with Apple" enabled in the Apple Developer account (Certificates, IDs & Profiles → Keys). Provide `APPLE_SIGNIN_KEY_ID`, `APPLE_SIGNIN_TEAM_ID` and `APPLE_SIGNIN_PRIVATE_KEY` (the .p8 contents) as server environment variables. Make sure `sys_config apple.clientId` holds the app's bundle id.
2. **`chengyin.pii.key` must be set** on servers that should store refresh tokens (it is already required for registration PII).
3. **Tencent Cloud content moderation:** open TMS (text) and IMS (image) on a Tencent Cloud account, create a CAM sub-user limited to `tms:TextModeration` and `ims:ImageModeration`, and provide `TENCENTCLOUD_SECRET_ID` / `TENCENTCLOUD_SECRET_KEY`. Optionally set up BizType policies in the console.
4. Run `chengyinhub-admin/sql/migration_20261115_store_account.sql`.
5. Rows with `status IN ('PENDING_CONFIG','PENDING_NO_TOKEN','REVOKE_FAILED')` in `ums_apple_token_revocation` need manual follow-up. The member can also revoke from iOS Settings → Apple ID → Sign in with Apple.

## Questions for the owner
- Should an Apple account that also has a phone also be asked for Apple re-auth at deletion, so its token can be revoked? Today the conservative choice is SMS only, with the existing behaviour unchanged.
- When Tencent is unavailable, should chat, comments and AI turns from app accounts get a held-for-review state, or keep the honest refusal? Holding would need a new hidden-message state per surface.

## Hard-coded secrets
None found in the files touched.

## Dependencies on in-flight branches
None.
