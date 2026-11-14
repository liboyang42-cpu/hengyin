# be-gap-misc — BACKEND GAP PACKAGE MISC (15 rows of the true-missing list)

Branch `feature/be-gap-misc`, cut from `integration/backend-app-r2` **`aa3dcc89d`** (R4: be-gap-club + be-gap-rewards-workshop merged; pushed
as `3151f0d4d` with docs only on top). Worktree `/data/workspace/be-gap-misc` on VPS `work`, curl on **8126 / DB `cy_gap_misc` / Redis db 15**
(stopped afterwards). Production, 8120 and hengyin `master` were not touched.

Rows: app repo `docs/backend-gaps` `9e40fe69a`, `docs/release/backend-true-missing-85.tsv`, areas `native-only (no backend)` (6),
`US market (no backend)` (3), and the single rows `registration/tickets`, `profile`, `npc/ai`, `community square`, `wallet/funds`,
`orders/payment`. Client contracts read from app `origin/ui/main` (`421bd5e25`). Before starting: no `feature/be-gap-misc` on hengyin.

## Switch semantics

| Switch (server config) | Default | Covers | Closed answer |
|---|---|---|---|
| `chengyin.release.money-payments` | off | `/api/pay/create` | **`code 403`, `errorCode FEATURE_CLOSED`** (the `CashPayoutSwitch` constant from be-gap-rewards-workshop), before the body is read |
| `chengyin.release.money-payments` **and** `chengyin.release.payout` | off | `/api/asset/withdraw` | same |
| `chengyin.us.apple.enabled` (+ `realm`, `client-id`, optional `jwks-url`) | off | the 3 US routes | **HTTP 503 = `code 503`, `errorCode US_APPLE_UNAVAILABLE`** — the app (`USAppleService`) only accepts its five documented `errorCode`/status pairs, so `FEATURE_CLOSED` would read as an invalid response. US routes write no money. |
| `chengyin.native-steps.app-attest.enabled` (+ `app-id`, `distribution-categories` ⊆ {2,4,5}, `bundle-versions`, the two Apple root DER paths) | off | `/api/native/device/*` | `status` answers `enabled:false, status:DISABLED, appId:null` (the app's disabled shape); writes answer `code 409` with `data.reasonCode APP_ATTEST_NOT_CONFIGURED` |
| `chengyin.native.home.enabled` | off | `/api/native/home` | `code 403 PRIVATE_HOME_NOT_APPROVED` |
| `chengyin.native.home.return.enabled` (needs the home switch) | off | `/api/native/home/return/*` | `code 403 HOME_RETURN_NOT_APPROVED` (the app maps 403 to "disabled") |

On TEST (the 8126 overlay) every switch is on. The 8120 overlay already has the two money switches on (R4).

## Path → implementation → test → curl (8126 / cy_gap_misc)

Players 9009 (registration 74), 9004 (registration 75). Transcript `curl-misc.txt`, script `smoke_misc.py`.

| # | Path (app client) | Implementation | Unit / IT test | curl |
|---|---|---|---|---|
| 1 | `POST /api/user/sms/send` (`RegionalEndpointAllowlistAudit`; `RePoint10Routes` form `phone`) | Alias of `/api/sms/send` (`ApiLoginController.smsSend`): anonymous, same rate limits. Form `phone`, or a JSON `{phone}`. Added to the anonymous matcher next to `/api/sms/send`. | `ApiMiscGapControllerTest.userSmsSendIsTheAnonymousSmsSendWithFormOrJsonPhone` | signed out 200 `验证码已发送`; bad phone refused by the long path |
| 2 | `DELETE /api/npc/chat/delete` | Alias of `POST /api/ai/npc/chat/data/delete` (optional `{requestId}` → `AuthorWriteReceiptService` NPC_CHAT_DELETE idempotency). | `npcChatDeleteIsTheChatDataDeleteWithTheOptionalRequestId` | signed out 401; 200 `{deleted:0}` + write receipt |
| 3 | `POST /api/ugc/block` (`RePoint10Routes` → form `target_member_id`) | Alias of `/api/im/block` (form `target_member_id` / `peer_id`, or JSON `target_member_id` / `targetMemberId` / `peer_id`). | `ugcBlockIsTheImBlockForFormOrJsonTargets` | 9009 blocks 9004: 200 `已拉黑`; self refused by the long path |
| 4 | `POST /api/registration/original-event` (`ProfileOrderEventHost`, `OwnedOrderRead`: multipart `id`) | **New read** `RegistrationOriginalEventService`: owner check on `cms_registration.member_id`, then `business_audit_event` rows with `target_type ORDER`, `target_id = id`, oldest first, max 50, as `{requestId, targetType, targetId, action, afterJson, createTime}`. Nothing is derived from the order's own fields (the app refuses that too). Someone else's / unknown order → `code 404 REGISTRATION_NOT_FOUND`; malformed id → 400. | `originalEventReturnsOnlyTheOwnersOrderAuditRowsOldestFirst` | own order 74: 200, 1 row (a TEST fixture row inserted by the script, action `TEST_FIXTURE_ORDER_EVENT`); 9004 on 74: 404; 9004 on 75: 200 `[]` |
| 5 | `POST /api/pay/create` (`RegionalEndpointAllowlistAudit`, forced-off in the app) | Money switch first. When open: the caller's own order `id` (form or JSON `id` / `registrationId`) → `ApiRegistrationController.payApp` (APP trade type, the same consent gate and expiry 410). | `payCreateIsFeatureClosedUntilThePaymentsSwitchIsOn` | signed out 401; switch on → the long path's answer (TEST has no WeChat merchant certificate) |
| 6 | `POST /api/asset/withdraw` (`RegionalEndpointAllowlistAudit`, forced-off) | Both switches first. When open: JSON `WithdrawalDTO` with the same bean constraints, then `ApiWithdrawalController.request` (identity gate, `bankWithdrawalCommandService` with its fund preflight). | `assetWithdrawNeedsBothSwitchesAndTheBeanConstraints` | switch on: empty body 400 `银行账号不能为空`; full body 409 `WITHDRAWAL_CONSENT_REQUIRED` (the preflight; no money moves) |
| 7–10 | `GET /api/native/device/status`, `POST …/enrollment-challenge`, `…/enroll`, `…/revoke` (`NativeEnrollmentService`) | **Ported** from the never-merged questify-be packet (`origin/stranded/questify-be-v2-20261020` `0127e3dc1`): `ApiNativeDeviceController`, `NativeAppAttestService`, the strict App Attest verifier (`service/support/appattest/*`: bounded CBOR/DER parsing, the Apple App Attestation root and the receipt root G3 pinned by SHA-256, offline PKIX, App ID / distribution category / bundle version allowlists), MyBatis mapper. Wire exactly as the app reads it: `protocolVersion 1`, provider `IOS_CMPEDOMETER_V1`, `rewardEnabled:false`, `hardwareVerified:false`, account from the token only; challenge 32-byte nonce, 64-hex id, TTL 300 s, 30 s interval and 20/day per account, max 5 active keys; exact replays idempotent; revoked keys never re-enrolled. Refusals are `code 409` + `data {reasonCode APP_ATTEST_*, provider, rewardEnabled:false}`; verifier codes are now namespaced `APP_ATTEST_*` so the app classifies them as rejections. Changes vs the packet: `javax`→`jakarta`, the step-submission half (`NativeStepProtocol` canonical step proof bound to game sessions) is not carried (only the provider constant), `TimeWindowSchedule.sha256` → a local helper, `jackson-dataformat-cbor` added (Jackson BOM version). | `NativeAppAttestServiceTest` 12, `AppAttestVerifierTest` 13, `AppAttestResourceBoundsTest` 9, `AppAttestCmsSchemaTest` 7, `AppAttestCryptoSchemaTest` 7, `AliyunCryptoCompatibilityTest` 3 | TEST app id `ABCDE12345.com.chengyinhub.test` with Apple's public roots: status `UNENROLLED enabled:true`; challenge 200 `CHALLENGE`; enroll with a bogus attestation 409 `APP_ATTEST_CBOR_*`; extra body field 409 `APP_ATTEST_REQUEST_INVALID`; revoke unknown key 409 `APP_ATTEST_KEY_UNAVAILABLE` |
| 11 | `GET/PUT/DELETE /api/native/home` (`PrivateHomeService`, `PrivateHomeContracts`) | **New** `NativeHomeService` (the packet's version depended on an "authority" bean that never had an implementation, so it is rewritten to the app's contract). Owner = token only; WGS84, ≤6 decimals (scale checked on the raw JSON number), label ≤40 UTF-16 units without control/surrogates; exact key sets (unknown keys → 400). Decisions `SAVED`/`DELETED` (version+1), `VERSION_CONFLICT` (current version), `HOME_CHANGE_COOLDOWN` (a save within the change cooldown of the last save). Every decision is stored per (owner, requestId); same body replays, another body → 409. Delete clears every coordinate; the cooldown fence (`last_set_at`) stays. `no-store` headers. | `NativeHomeServicesTest` (5 home cases + controller) | GET `DELETED v0`; PUT `SAVED v1`; same requestId replays; stale version `VERSION_CONFLICT`; unknown key 400; GET `ACTIVE`; 9004 sees its own (none); DELETE `DELETED v2` |
| 12 | `/api/native/home/return/{policy,runs,account,verify,restore,receipt}` (`NativeHomeReturnService`) | **New** `NativeHomeReturnService`. Resource = the run's frozen HP (`sys.hp` of an in-progress `topic_route_session`, max from the run's frozen journey rules), version = the session row version. Two hooks on `TopicRouteRuntimeServiceImpl`: `homeReturnHp` (read) and `restoreHomeReturnHp` (sets `sys.hp` = max through the runtime's own state CAS, version+1). `verify` decides on the server: home active & current version, accuracy, fix age (and not >5 s in the future), haversine distance ≤ radius, the run's restore cooldown, optional dwell (`DWELL_REQUIRED` + `challengeId`, then a second check after the dwell). Only `VERIFIED` carries a single-use proof (32 hex, TTL). The device fix is used for the decision only and never stored. `restore` decisions: `RESTORED` (current=max, version+1), `ALREADY_AT_MAX`, `PROOF_CONSUMED/EXPIRED/UNAVAILABLE`, `STALE_HOME`, `COOLDOWN`, `VERSION_CONFLICT`, `RUN_UNAVAILABLE`. Verify/restore are stored per (owner, requestId) and replay; `receipt` reads a stored restore (absent → success without data). Refusal codes are the app's fixed strings (`HOME_RUN_NOT_FOUND`, `HOME_RUN_UNAVAILABLE`, `HOME_RETURN_VERSION_CONFLICT`, `HOME_RETURN_CHALLENGE_EXPIRED`, `HOME_RETURN_POLICY_CHANGED`, `HOME_RETURN_NOT_APPROVED`, `INVALID_HOME_*`). Policy numbers are configuration (`chengyin.native.home.*`, defaults: radius 150 m, accuracy 100 m, fix age 120 s, dwell 0, proof TTL 120 s, restore cooldown 6 h, change cooldown 24 h). | `NativeHomeServicesTest` (7 return cases), `BeGapMiscMysqlIntegrationTest.homeAndReturnRoundTripOnMysql` | policy 200; runs 200 `[]` (TEST has no in-progress HP run); account of a completed run 409 `HOME_RUN_UNAVAILABLE`; verify → `RUN_UNAVAILABLE`; restore with an unknown proof → `PROOF_UNAVAILABLE`; receipt unknown → 200 without data; signed out 401 |
| 13–15 | `POST /api/us/auth/apple/challenges` (body `{}`), `POST /api/us/auth/apple/exchange` (`{challengeId, state, identityToken}`), `GET /api/us/auth/session` (Bearer) (`USAppleService`, `docs/us-apple-client.md`) | **New** `UsAppleAuthService` + `ApiUsAppleAuthController`. HTTP status always equals `code`. Challenge: three distinct 43-char base64url 32-byte values, `expiresIn 300`, `market US`, `provider apple`; only SHA-256(rawNonce) and the state are stored. Exchange: the challenge is spent first (single use), then the identity token is verified — RS256 against Apple's JWKS key by `kid` (`UsAppleKeySource`, cached 1 h, refresh on unknown kid, no redirects), `iss`, `aud` = the US client id, `exp`, `nonce` = the stored digest. One account per (realm, sub) in `us_apple_account`; a new sub gets a new player account; CN identities (phone, WeChat, `ums_member.apple_id`) are never matched. The session token comes from the new `AppLoginService.loginByUsAppleLink`. Response `{code, token, market:"US", data:{id, userType, avatar|null, nickname, role}}`. Session: only an account linked in this realm → `{market, realm, account}`; anything else 401 `US_SESSION_INVALID`. Errors: 400 `US_APPLE_INVALID_REQUEST`, 401 `US_APPLE_INVALID_CHALLENGE` / `US_APPLE_INVALID_IDENTITY`, 429 `US_APPLE_RATE_LIMITED` (open-challenge cap per realm), 503 `US_APPLE_UNAVAILABLE` (off, or the JWKS cannot be read). | `UsAppleAuthServiceTest` 5 (a locally signed RS256 token: success, same sub = same account, single use, wrong nonce/aud/key/expired, shape errors, off, controller status = code), IT `usAppleChallengeAndExchangeOnMysql` | challenge 200 (values redacted); non-empty body 400; exchange with a token whose kid Apple does not publish 401 `US_APPLE_INVALID_IDENTITY` (Apple's JWKS was fetched live); reused challenge 401 `US_APPLE_INVALID_CHALLENGE`; session with a CN token 401 `US_SESSION_INVALID`; signed out 401 |

## Migration

`chengyinhub-admin/sql/migration_20261119_be_gap_misc.sql`: 9 new tables, `CREATE TABLE IF NOT EXISTS` (rerunnable), no FKs, no
backfill, no change to existing rows: `native_app_attest_{guard,key,challenge}` (the packet's DDL unchanged), `native_private_home`
(+ CHECK: ACTIVE has every field, DELETED has no point), `native_private_home_mutation`, `native_home_return_check`
(UNIQUE `proof_id`), `native_home_restore_receipt`, `us_apple_challenge`, `us_apple_account` (UNIQUE `(realm, apple_sub)`). The only
ledger change is `"20261119_be_gap_misc": []` in `ci/migration-dependencies.json`. Other non-SQL changes outside the package:
`SecurityConfig` (anonymous `/api/user/sms/send`, `/api/us/auth/apple/challenges`, `/api/us/auth/apple/exchange`),
`AppLoginService.loginByUsAppleLink`, `TopicRouteRuntimeServiceImpl.homeReturnHp` / `restoreHomeReturnHp`, `chengyinhub-system/pom.xml`
(`jackson-dataformat-cbor`).

## Tests (VPS, `flock /data/workspace/.mvn.lock`, `-T 1`, `MAVEN_OPTS=-Xmx1536m`)

Code head `110367725` (this file follows it and changes only `docs/`).

* **Gates:** `migration_ledger.py audit` problems=[], `manifest --target-commit 110367725…` OK (the entry is in the manifest),
  `migration-layout-check.sh` pass, `migration-safety-check.sh` 5/5 pass, `dup_create_table_gate.py` pass.
* **Build:** `mvn package -DskipTests` BUILD SUCCESS; the jar starts on 8126 without ambiguous mappings.
* **Full unit suite** (`-pl chengyinhub-framework,chengyinhub-system,chengyinhub-admin -am`, surefire `-Xmx1536m`): **19,487 run,
  0 failures**, 34 errors = the same 14 `*MyBatisTest` classes that need MySQL on 3306 (as on `integration/backend-app-r2`); those
  14 classes against be-int-mysql 3330: **39/39**.
* **Package tests: 77/77** (`-Duser.timezone=Asia/Shanghai`): `ApiMiscGapControllerTest` 6, `NativeHomeServicesTest` 12,
  `UsAppleAuthServiceTest` 5, `NativeAppAttestServiceTest` 12, `AppAttestVerifierTest` 13, `AppAttestResourceBoundsTest` 9,
  `AppAttestCmsSchemaTest` 7, `AppAttestCryptoSchemaTest` 7, `AliyunCryptoCompatibilityTest` 3, and **`BeGapMiscMysqlIntegrationTest` 3**
  (real MySQL 8 on be-int-mysql, `CHENGYIN_R2_MYSQL_IT=1`: migration applied twice, 9 InnoDB tables, the CHECKs and unique keys
  enforced, the home + verify + restore + replay cycle and the US challenge + exchange on the real tables).
* **curl: 40/40 OK** (`smoke_misc.py` → `curl-misc.txt`) on 8126 / `cy_gap_misc` = a copy of the R4 TEST DB `cy_e2e_r2` with the
  migration applied twice (634 → 643 tables). Tokens, nonces, challenge and proof ids are redacted. TEST-only data written: one
  `business_audit_event` row (`misc-test-order-74`), 9009 blocks 9004, the home rows, one attest challenge, one US challenge.
  8126 was stopped afterwards. Logs: VPS `/data/workspace/be-gap-misc-out/` (`gates.log`, `build.log`, `test-unit.log`,
  `test-misc.log`, `mybatis-rerun.log`, `curl-misc.txt`, `run/` with the 8126 overlay).

## Not done / for the owner

* **Home return on TEST could not be shown restoring:** TEST has no in-progress run with HP enabled (session 1 for 9009 is completed),
  so curl shows the refusals only. The full verify → restore → replay → cooldown cycle is covered by `NativeHomeServicesTest` and by the
  MySQL IT. The policy numbers (radius, accuracy, freshness, dwell, cooldowns, TTL) are placeholders for an owner decision.
* **App Attest** is verified against Apple's real roots, but no genuine device attestation was available, so `enroll` was only shown
  refusing. Production needs the approved App ID, distribution categories and bundle versions (the packet's own activation list).
  The step-assertion half of the packet (native step proofs) is not carried.
* **US Apple** needs an approved US realm, Apple client id, deployment and legal flow before it is switched on. A real Sign in with
  Apple token could not be produced on TEST; the success path is covered by tests with a locally signed token.
* **`original-event`** has no producer of `ORDER` audit events yet (as app-gaps-b noted), so real orders answer `[]` until one exists.
* **`pay/create` and `asset/withdraw`** stay closed in production (switch off). They reuse the existing registration APP-pay and bank
  withdrawal paths; the app keeps both forced off.
* The app still lists these paths as missing in `ChinaReviewedRouteCapability`; turning them on is an app-side change.
