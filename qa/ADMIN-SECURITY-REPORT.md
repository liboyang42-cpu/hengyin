# Admin console and security/privacy acceptance review: backend-app-r3

- **Branch reviewed:** `origin/integration/backend-app-r3` @ `81db27155`, reviewed on `qa/admin-security-r3`.
- **r3 range:** `c879749c8..81db27155` (197 files, 7 new migrations).
- **App:** `chengyin-app` @ `ff1bd92` (Flutter, read-only).
- **Date:** 2026-11-15.
- **Method:** static review plus the admin UI build and unit tests. No running backend was exercised. Nothing was fixed; this report only records findings.

## Verdict

**NOT ACCEPTED.** The admin console builds and its tests are no worse than the pre-r3 base. But the review found 2 Critical and 9 High security/privacy findings, and 2 High admin-console defects. The Critical and High items below must be fixed before r3 goes past TEST.

| Severity | Count |
|---|---|
| Critical | 2 |
| High | 11 (9 security/privacy, 2 admin) |
| Medium | 12 |
| Low | 20+ |

---

## A. Admin console acceptance

### A.1 Build and test status (chengyinhub-ui)

| Step | Result |
|---|---|
| `npm ci` | **Fails as committed in this sandbox.** `package-lock.json` resolves 1,501 packages from `registry.npmmirror.com`, which is unreachable here. It passes with `--registry=https://registry.npmjs.org/ --replace-registry-host=registry.npmmirror.com` (1,500 packages, integrity hashes unchanged). CI and agents outside China need that override or a neutral lockfile. |
| `npm run build:prod` | **PASS.** 14 warnings, all asset or entrypoint size limits; no compile errors. |
| `npm run test:unit` | **599 / 624 pass, 25 fail.** The pre-r3 base `c879749c8` has the identical 25 failures (598/623, same test names, diffed), so r3 introduced no new unit failures. The one new r3 test (`sys-random-admin-contract.test.js`) passes. The pre-existing failures are in topic workspace, ticket, chapter and shop-day contracts, e.g. `shop-day-node-and-risk-admin-contract.test.js:62` → `PlayHelpReadonlyPanel is not defined`. |
| `npm run test:e2e:contract` | **PASS**, 36/36. |

### A.2 Page-by-page

| Page (view → api) | Endpoint match on r3 | Permission (sys_menu / perms) | Empty / error states | Build/test | Issues |
|---|---|---|---|---|---|
| **City Monopoly board editor**<br>`citygame/index.vue` → `citygame.js` | ✅ All 15 calls match `CityBoardAdminController` (`/business/city-game`, L21–141). Paths, methods and params line up. | ✅ `business:citygame:list` (menu C) and `:edit` (F) are in `migration_20261123_sys_citygame.sql:139-149`, NOT EXISTS, under the `roam` parent. All `v-hasPermi` values are registered. | ⚠️ No `empty-text`. Board-list and queue loading reset in `finally` (L482, L583). Several calls have no `.catch` (L497, 548, 588, 600-616). The territories table has no `v-loading` (L328). | builds | `CityMonopolyAdminController` (`/business/city-game/monopoly/*`) has no UI caller. `adminUid()` falls back to uid 1 (`CityBoardAdminController.java:31-35`). |
| **Drop rules**<br>`rewarddrop/index.vue` → `rewardDrop.js` | ✅ Matches `RewardDropAdminController` (`/business/reward-drop`) L34–116. | ❌ The list, query and preview handlers require `business:topicnode:list` / `:query`. Those perms are **registered nowhere**: `migration_20260817_tandianri_batch5_admin_menus.sql:18` leaves them out on purpose. The r3 menu row (`migration_20261123_sys_random.sql:71-75`) has empty perms. | ⚠️ `v-loading` and `finally` are present; no `empty-text`. | builds | **HIGH-A1, HIGH-A2** (below). Search doesn't reset `pageNum` (L82). |
| **Task pool**<br>`nodetaskpool/index.vue` → `taskPool.js` | ✅ Matches `NodeTaskPoolController` (`/business/node-task-pool`) L31–108. | ❌ Same gap as drop rules: the menu at `migration_20261123_sys_random.sql:77-81` has empty perms, and the handlers need the unregistered `topicnode:list/query`. | ⚠️ Items table has `empty-text` (L60); pool table doesn't. The items list has no loading flag. | builds | **HIGH-A2.** Client can set `createBy` (`NodeTaskPoolController.java:55-57`). Search doesn't reset `pageNum` (L17). |
| **Weather conditions / environment rules**<br>`topic/components/TopicEnvironmentRulesPanel.vue` (+ `detail.vue`, `workspace.vue`) → `topic.js` | ✅ GET/PUT `/business/topic/{id}/environment` match `CmsTopicController:369/394`. The body matches `TopicWeatherEventService.SaveRequest` (L608-641). Topic scope is checked. | ✅ Reuses the existing `business:topic:query/edit` (`migration_20261012_missing_business_menu.sql:410/416`). The table migration uses IF NOT EXISTS. | ✅ `empty-text` (L53), `v-loading`, inline `el-alert` on error (L288, L338), `finally` (L291, L342). Edit is gated by `canEdit`. | builds | `migration_20261114_weather_event_rule.sql:2` names a rollback file that doesn't exist; the real file is `bak_weather_event_rule_20261114_rollback.sql`. |
| **NPC character table**<br>`themenpc/index.vue` → `themeNpc.js` | ✅ Matches `CmsThemeNpcController` (`/business/themeNpc`) L33–121. Writes are field-whitelisted (`ThemeNpcAdminService:137-196`). | ✅ `business:themeNpc:list/query/add/edit/remove` are in `migration_20261115_sys_ainpc.sql:27-55`, NOT EXISTS. ⚠️ The parent is found by `menu_name` and falls back to root (L27). | ⚠️ `v-loading` and try/finally are present; no `empty-text`. No catch at L215, L230 or L278. | builds | — |
| **External-game monetization review**<br>`externalgame/monetization.vue` → `reviewQueues.js` | ✅ Matches `ExternalGameMonetizationAdminController` (`/business/external-game/monetization`) L32–65. The `PUT {gameId}/config` (L48) has no UI. | ✅ Uses `business:audit:list/review`. The menu is in `migration_20261115_external_game_monetization_menus.sql:9-11`. ⚠️ Its guard at L7 **fails** on re-run instead of skipping. | ❌ `empty-text` is present (L27, L101), but the calls use `rawBusinessError` (no global toast) together with `.catch(()=>{})` at L246/287/303/314, so **every error is silent**. | builds | **MED-A1.** Fixed `pageSize: 50` with `total` ignored, so there's no pagination (L268, L296). |
| **External-game review / reports** (r2; r3 adds a button at `index.vue:15`) | ✅ Matches `ExternalGameReviewController` L31–86. | ✅ `audit:*`, in `migration_20261121_admin_review_queue_menus.sql:15-19`. | ✅ `empty-text`, inline error, `finally`. | builds | — |
| **Workshop professional review** (r2) | ✅ Matches `WorkshopProfessionalReviewController` L29–49. | ✅ | ✅ | builds | — |
| **Channels**<br>`channel/index.vue` → `channelVoucher.js` (r2) | ✅ This is the marketing channel-voucher page. | ✅ `business:marketing:channel:*` are in `migration_20261109_questo_admin_menus.sql`. | ✅ | builds | It is **not** an admin page for the r3 in-game IM channels; see below. |
| **In-game IM channels** (`ApiInGameChannelController`) | ❌ **No admin page.** | — | — | — | There is no moderation or monitoring UI for system-posted channel events (see HIGH-S4/S8). |
| **Journeys** (`ApiPlayerJourneyController`) | ❌ **No admin page.** | — | — | — | Only the app-facing API exists. |

**CI migration graph:** `ci/migration-dependencies.json` lists all 7 r3 migrations. `20261123_sys_random` doesn't declare `20260817_tandianri_batch5_admin_menus`, although its pages rely on the `topicnode:add/edit/remove` perms that migration creates.

### A.3 Admin defects

**HIGH-A1: the drop-type selector has no options.**
- `chengyinhub-ui/src/views/business/rewarddrop/index.vue:133-135` has labels such as `label="1 - XP倍率 ({\"mult\":2})"`.
- Backslash escapes don't work in HTML attributes. The attribute ends at the first `\"`, so the compiled render produces no `<el-option>` children.
- Result: an admin can't choose a drop type, including badge fragments and merchant coupons.
- **Fix:** use single-quoted attributes (`label='1 - XP倍率 ({"mult":2})'`) or `:label="..."` bound to a constant. Add a unit test that mounts or compiles the template and asserts there are three options.

**HIGH-A2: the drop-rules and task-pool pages return 403 for every role except super-admin.**
- `RewardDropAdminController.java:33,41,76,108,115` and `NodeTaskPoolController.java:30,38,83,107` require `business:topicnode:list` / `:query`, which are never inserted into `sys_menu`.
- **Fix:** add dedicated `business:rewarddrop:{list,query,add,edit,remove}` and `business:taskpool:*` perms as F rows under the r3 menus in an additive migration (NOT EXISTS), and switch the `@PreAuthorize` annotations to them. This also resolves LOW-S5: drop odds would no longer be editable by anyone who has node-edit rights.

**MED-A1: the monetization page swallows every error.**
- `externalgame/monetization.vue:246,287,303,314` pair `rawBusinessError` requests with `.catch(()=>{})`.
- **Fix:** show `this.$modal.msgError(err.message)` in the catch, or drop `rawBusinessError`.

**Low (admin):**
- **Unhandled rejections:** `citygame/index.vue` L497/548/588/600-616 and `themenpc/index.vue` L215/230/278. Add a `.catch` to each.
- **Search doesn't reset paging:** `rewarddrop/index.vue:82` and `nodetaskpool/index.vue:17`. Set `pageNum=1` before searching.
- **Wrong audit attribution:** `CityBoardAdminController.java:31-35` attributes audits to uid 1 when the user can't be resolved. It should fail closed.
- **Client-supplied `createBy`:** `NodeTaskPoolController.java:55-57`. Always use `getUsername()`.
- **Non-idempotent menu migration:** `migration_20261115_external_game_monetization_menus.sql:7` aborts on re-run. Use INSERT … WHERE NOT EXISTS.
- **Incomplete rollback:** `migration_20261123_sys_citygame.sql:6` only deletes the C menu and leaves the `citygame:edit` F row orphaned.
- **Weak contract test:** `tests/unit/sys-random-admin-contract.test.js` only checks that strings are present, which is why it missed HIGH-A1 and HIGH-A2.

---

## B. Security and privacy acceptance

`/api/**` authentication: `SecurityConfig.java:113-177` ends in `.anyRequest().authenticated()`. r3 adds no permitAll or `@Anonymous` entries. Every new player endpoint takes the member from the token (`getAppUserId()`), and every new admin handler has `@PreAuthorize`. No new mapper XML uses `${}`.

### Critical

**CRIT-S1: the external H5 game result "signature" uses a key the client already has, so any result can be forged.**
- **Where:**
  - `chengyinhub-system/.../externalgame/ExternalGameMonetizationService.java:581,604`: `startSession` generates `nonce` and returns it to the player.
  - `:643-646`: `submitResult` accepts `HMAC-SHA256(key = nonce, canonicalResultPayload)`.
- The compare is constant-time (L1211) and the session is bound to the member (L634). But the client knows the key, so it can sign `passed=true` with any `score` and `durationMs`.
- There is also no session TTL or EXPIRED transition, and `durationMs` is client-reported.
- **Exploit:** call `POST /api/external-game/sessions/start`, compute the HMAC locally, then `POST /sessions/{id}/result` with `passed=true`.
- **Fix:**
  - Treat H5 results as untrusted. Remove the nonce-keyed HMAC as an integrity claim.
  - Measure duration on the server (`completed_at - started_at`) and expire sessions after about 2h.
  - Grant only capped rewards from this path (see CRIT-S2/HIGH-S1).
  - If integrity is required, results must be attested by a server-side game authority that holds a per-game secret the client never sees.

**CRIT-S2: a forged result completes a node with FULL rewards and skips the play gates.**
- **Where:** `chengyinhub-admin/.../web/service/play/SpringExternalGameNodeBridge.java:66-69` calls `advanceProgress(..., RewardPolicy.FULL, ...)`. `activityId` comes from the client (stored unvalidated at session start).
- Other completion commands, e.g. `PlayPuzzleCommandApplication.java:69-77`, first run `resolveCtx`, `leadGateError` and `routeAccessError`. This path doesn't.
- **Exploit:** an unregistered user with CRIT-S1 gets node completion, points, coupons, drops and a top-3 medal rank.
- **Fix:**
  - Resolve the play context and run the registration, activity-window and lead/route gates as the other commands do.
  - Ignore client `topicId`/`activityId` unless they are validated.
  - Use `RewardPolicy.XP_BADGE_ONLY`, consistent with the C1 sensor rule for self-reported results.

### High

**HIGH-S1: standalone external-game passes allow unlimited XP farming.**
- **Where:** `SpringExternalGameNodeBridge.java:74-76` grants 10 XP per passed session; `GrowthServiceImpl.addExp` (L333) has no daily cap or idempotency key.
- Combined with CRIT-S1, that is about 600 XP/min at the 60/min rate limit.
- **Fix:** idempotency key = sessionId, XP only for the first pass per game per day, and a daily cap.

**HIGH-S2: money path. TEST_WALLET and confirm-test credit the seller's real `ums_member.balance` with no buyer debit, gated only by the production money switch.**
- **Where:**
  - `ExternalGameMonetizationService.java:336-359` (`provider=TEST_WALLET`) and `:366-397` (`POST /orders/{no}/confirm-test`) both call `walletBridge.creditBalanceFen(seller, …)`.
  - `SpringExternalGameWalletBridge.java:64-85` performs that credit.
  - The only gate is `paymentsOpen()` = `chengyin.release.money-payments` (`MiscMoneySwitch.java:19-29`).
- The switch defaults to **false** and no committed yml enables it, so this is latent today. It goes live the moment real WeChat payments are switched on, so the TEST-only money paths are not TEST-only.
- **Fix:**
  - Gate TEST_WALLET and confirm-test behind a separate TEST-only flag or `@Profile("test")`, checked in the service, and reject them in prod.
  - Credit balance only from a verified payment callback, and also require `payoutOpen()`.
  - Make `creditBalanceFen` idempotent on `businessKey`.

**HIGH-S3: any logged-in user can join and read any topic (session) IM channel.**
- **Where:** `chengyinhub-system/.../service/impl/InGameChannelService.java:669-686`. The `topic`/`session` branch of `resolveTarget` does no participant check, unlike the `activity` branch at L662-664.
- `resolveChannel` then runs `ensureMember` for the caller (L153), and the resulting membership row satisfies the `ImServiceImpl` read and send checks.
- **Exploit:** `POST /api/im/ingame/resolve?scope=topic&topic_id=<any>`, then read and post into a paid participants' chat.
- **Fix:** require topic owner OR `hasPaidRegistration(memberId, topicId)`, otherwise return 403.

**HIGH-S4: unmoderated, unbounded client text is posted as a pre-approved system message in public roam channels.**
- **Where:** `chengyinhub-admin/.../api/ApiRoamController.java:975-978` passes the client `name` straight to `postRoamEvent`, which inserts with `senderId=0, checkStatus=1`.
- The non-participant branch right below (L982-988) caps at 120 chars and runs `submissionSafety.textVerdict`; this branch does neither. There is no `@RateLimiter`.
- **Exploit:** loop `/api/roam/checkin` with phishing text as `name`.
- **Fix:**
  - Use the server-side node or POI title, or apply the same moderation and cap.
  - Post at most once per member, node and day.
  - Add `@RateLimiter(limitType=MEMBER)`.

**HIGH-S5: city dice outcomes can be replayed because the receipt is cut and global.**
- **Where:**
  - `chengyinhub-system/.../citygame/CityGameActionService.java:71-73` builds the receipt `"city:" + requestId + ":" + actionTag`.
  - `service/impl/GameRandom.java:86-97` cuts the receipt to 64 chars and returns the stored ticket for any receipt seen before. The lookup is by `(domain, receipt)` only, not by member.
  - `requestId` may be up to 96 chars.
- **Exploit:** with a requestId of 59 or more chars, the suffix and action tag are cut off. Varying the tail passes the per-member duplicate check but replays the same face. A good receipt can also be reused across accounts; teammates' requestIds are visible via `/dice/audit`. CHALLENGE attack and defence draws collapse to the same receipt, so every challenge ties.
- **Fix:**
  - Build the receipt as `city:{boardId}:{memberId}:{sha256(requestId)}:{tag}`.
  - Reject receipts over 64 chars instead of cutting them.
  - On replay, check that the stored row's member and ref match the caller.
- *Static finding; confirm with a TEST 8120 repro.*

**HIGH-S6 (privacy): account deletion leaves every r3 per-user table untouched.**
- **Where:** `AccountDeregistrationServiceImpl.java:290-357` (`executeOne`) and `AccountDeregistrationMapper.xml:67-116`, both unchanged in r3. No r3 code has a deregistration hook.
- **Uncovered tables:**
  - **Territory** (`migration_20261123_sys_citygame.sql`): `roam_territory` :57 (owner, lat/lng, claim photo; stays ACTIVE and public), `roam_territory_checkin` :94 (raw lat/lng and photo), `roam_territory_audit` :119.
  - **Attributes and City numbers** (`migration_20261124_city_numbers_attr.sql`): `member_attribute` :268, `member_attribute_ledger` :282, `member_title` :297, `member_archetype` :309, `city_claim_evidence` :162 (raw GPS and photo), `city_device_account` :259 (IDFV hash or unionid), `city_monopoly_quote` :72, `city_monopoly_receipt` :89, `city_points_flow` :99, `city_member_season` :117, `city_yield_event` :135, `city_challenge` :192, `city_season_score` :230, `city_abuse_signal` :243.
  - **Channels:** `im_conversation_member` rows auto-created by `InGameChannelService.java:541-549`.
  - **Extgame** (`migration_20261115_external_game_monetization.sql`): `external_game_order` :42, `external_game_entitlement` :77, `external_game_play_session` :106 (`client_ip_hash`), plus creator-side config and settlement :6/:139.
  - **Random** (`migration_20261123_sys_random.sql`): `game_random_audit` :8, `node_task_dispatch` :54.
- **Fix:**
  - Extend `executeOne` to delete the location, evidence, device, attribute and channel-membership rows.
  - Set the member's `roam_territory` rows to REVOKED and null `claim_photo_url`.
  - Tombstone the member id in the ledgers and audits that must be kept; null `client_ip_hash`.
  - Add an integration test that seeds every table above and asserts it is erased.

**HIGH-S7 (privacy): raw GPS in the new territory and Monopoly tables is never expired.**
- **Where:** the 30-day job `DataRetentionCleanupTask.java:87-89` → `DataRetentionServiceImpl.cleanRoamLocations` (L54-73) covers only the older roam tables, including `roam_fog_tile`, whose geohash is nulled.
- It doesn't touch `roam_territory_checkin`, `roam_territory_audit` (including `prevLat/prevLng` in `detail_json`) or `city_claim_evidence`.
- **Fix:** add these tables to `cleanRoamLocations`. After 30 days, null or coarsen lat/lng and accuracy, and keep only distance and verdict.
- **Fog cells:** OK. They are written only by the consent-gated roam session flow, and r3 only reads them.

**HIGH-S8 (privacy): the r3 territory and Monopoly endpoints store raw GPS without the roam location-consent check.**
- The older flows call `locationConsent.requireCurrentAgreement` (`ApiRoamController.java:284,579,654,703,949`).
- `ApiRoamTerritoryController.java:34-64` and `ApiCityMonopolyController.java:34-121` don't, and neither does `RoamTerritoryService`. Yet both write raw fixes, including blocked attempts (`RoamTerritoryService.java:154-205`, `CityMonopolyService.java:474-479`).
- **Fix:** call `requireCurrentAgreement(memberId)` on every command that accepts lat/lng.

**HIGH-S9 (privacy): channel events broadcast a named player's live location, falling back to their real name.**
- **Where:** `RoamTerritoryService.java:466,617` → `InGameChannelService.postRoamEvent` posts "{actor} 打卡/占领 「place」" to `roam_<city>` and `roam_nearby`. Anyone can join those channels.
- `actorLabel` (`InGameChannelService.java:840-861`) falls back to `ums_member.name`, the 真实姓名 (real name) field.
- This bypasses the `roam-presence-v1` presence consent.
- **Fix:** never use `name`; use the masked form from `CityMonopolyService.mask`. Post only when the member has opted into presence. Don't auto-join the actor.

**HIGH-S10 (privacy): there is no 18+ gate at sign-up.**
- The legal text (`chengyin-app/lib/feature/legal/legal_docs.dart:98-99`) says under-18s must not register.
- The login page (`lib/feature/auth/login_page.dart:117`) shows only the passive line "登录即同意…" (by logging in you agree…), with no age declaration or birthday field.
- `ApiLoginController.java` (r3 only changed status codes) has no age logic. The server instead has a minor mode keyed on an optional self-entered `birthday` (`service/minor/AgeBand.java:14-29`).
- **Fix:** decide on the policy. If adult-only: require an affirmative 18+ declaration or birthday at first registration, store it with a consent record, and reject account creation server-side without it.

### Medium

**MED-S1: territory `nearby`/`leaderboard` expose owner identity and claim photos, and bypass fog.**
- **Where:**
  - `RoamTerritoryService.java:212` allows a 50 km radius with no LIMIT on the board-points query (L228-247).
  - `:289-306` returns `ownerMemberId`, `ownerNickname` and `claimPhotoUrl`; `:770-781` returns the leaderboard unmasked.
  - `CityMonopolyService.property` (L1521-1531) returns lat/lng and `deedPhoto` without the fog check used by `map()` (L1503-1507).
- This enables a stalking scan.
- **Fix:** reuse `mask()` and the fog rule, drop `ownerMemberId` and `claimPhotoUrl` from public views, cap the radius at a few km, and add a LIMIT and a rate limit.

**MED-S2: the alt-account device check trusts a device key the client chooses.**
- **Where:** `CityMonopolyService.java:779`. The client `deviceKey` (from `ApiRoamController.java:942` and the territory checkin body) overrides the server-known identity.
- **Fix:** derive device identity on the server; treat the client key only as an extra signal.

**MED-S3: the legacy client-priced territory claim can be reached with a client-chosen boardId.**
- **Where:**
  - `RoamTerritoryService.java:321` evaluates `monopolyActive(body.boardId)`.
  - L362-363: the client sets toll (0-500) and owner revenue (1-500).
  - L397-401: with no POI or board space, the target becomes the claimer's own GPS fix (possibly their home; see also privacy).
  - L579-591: revenue is newly minted points, not paid from the toll.
- **Fix:** gate on the global flag only, use server-side prices, require a POI or board target, and pay revenue from the toll.

**MED-S4: no rate limits on claim, check-in, rent or dice.**
- No `@RateLimiter` on `ApiCityMonopolyController`, `ApiRoamTerritoryController`, `ApiCityGameController` (dice roll and audit), `ApiMemberAttributeController`, `ApiPlayEnvironmentController` or `ApiRoamController /checkin`.
- `/api/roam/checkin` mints a fresh UUID source key per call (L969, L1007) and runs about 15 COUNT queries.
- Monopoly `chance` ROLL (`CityMonopolyService.java:1396-1403`) can be called without limit, which allows rerolling for the preferred task.
- Rent and claim are protected by idempotency receipts, daily, pair and owner caps, and unique keys, but not by request-rate limits.
- **Fix:** add member-scoped `@RateLimiter` to each, and count chance rolls toward the daily cap.

**MED-S5: external-game POINTS traffic rewards can be farmed.**
- **Where:** `ExternalGameMonetizationService.java:831-840` credits creator points with no switch. Eligibility (L720-760) excludes only self-play, and `passed=true` skips the duration check.
- **Fix:** use server-measured duration plus device, IP and account-age heuristics (`client_ip_hash` is stored but unused).

**MED-S6: the in-game channel `summary` leaks the last message of chats the caller isn't in.**
- **Where:** `InGameChannelService.java:166-211` returns `conversationId` and `lastMsgText` for non-members, including `team_id` (L622).
- **Fix:** return only `exists`/`unread=0` unless `hasActiveAccess`.

**MED-S7: cancelling a registration doesn't revoke channel access.**
- Access accepts any member row, including `deleted=1` (L304, 316, 655-659), and resolve re-activates it (`ImConversationMemberMapper.xml:169-173`).
- **Fix:** base access on the current paid registration, and remove membership on cancel or refund.

**MED-S8 (privacy): a legacy claim can store the claimer's own GPS fix as the territory location.**
- **Where:** `RoamTerritoryService.java:398-401`. The fix is then returned by `myTerritories` and to admins.
- **Fix:** require a resolved target, or store only a coarse cell.

### Low

- **L-S1 Unverified merchant QR:** `CityMonopolyService.java:447` accepts any `merchantQr` string, which relaxes the GPS accuracy limit to 65 m (`CityMonopolyRules.java:267`). Verify the QR on the server.
- **L-S2 Draft weather rules readable:** `ApiPlayEnvironmentController.java:36-60` returns the weather rules (unlock nodes, task labels) of draft or unpublished topics to any user. Check topic visibility.
- **L-S3 NPC prompt injection:** the player's utterance can steer `effects` (`NpcDialogueServiceImpl.java:110-115`). Effects are whitelisted and capped at ±2 ×4 per call at 30 calls/min. Add a per-session or per-day effect budget.
- **L-S4 Repeatable XP:** `NEW_POI` attribute XP is keyed on client coordinates (`ApiRoamController.java:1000-1003`), so it repeats with spoofed GPS, bounded by the soft cap.
- **L-S5 Shared admin perms:** reward-drop odds are editable through the generic `topicnode:*` perms (see HIGH-A2).
- **L-S6 Spoofable system card:** `/api/im/send` stores `extra_json` unsanitized for text (`ImServiceImpl.java:1047`), so a user can mimic `cardType: game_event`. Whitelist `extra_json`.
- **L-S7 Unbounded channel rows:** no rate limit on `ApiInGameChannelController` resolve/summary. Any `district` string creates a new `im_conversation` row with an attacker-chosen title.
- **L-S8 Unbounded admin config:** `validateConfigNumbers` (`ExternalGameMonetizationService.java:1116`) only rejects negatives. `rewardPerPlayFen` and the daily caps are unbounded.
- **L-S9 Settle date unchecked:** admin `/settle` accepts today's date (later plays that day are lost), and a bad date throws an unhandled `DateTimeParseException`.
- **L-S10 Weak IP hash:** `client_ip_hash` is an unsalted SHA-256 (`ExternalGameMonetizationService.java:586`), reversible for IPv4, with no TTL. Use a keyed HMAC and a TTL.
- **L-S11 Device key retention:** `city_device_account.device_key` may hold a unionid, with no retention.

### External H5 game sandbox

- **Server side:** `ExternalGamePackageValidator` enforces an `allowedDomains` list (max 10), a permission allowlist (`storage/haptics/audio`), payment-token blocking and path-traversal checks. The manifest is Ed25519-signed.
- **App side:** **not implemented.** `chengyin-app` has no WebView dependency, JS channel or external-game code, so no sandbox exists yet to accept and the app never hands its auth token to H5.
- **Requirement for when it lands:**
  - Verify the manifest signature in the app.
  - Enforce `allowedDomains` as the navigation allowlist.
  - Disable file access.
  - Never pass the main bearer token; use a session-scoped token only.
- **Result callback signature:** see CRIT-S1. Verification is constant-time but cryptographically meaningless, because the key is client-known.

### Checked and OK

- **Secrets not in git:**
  - JWT secret, DB password, WeChat Pay v3 key, mini-program secret, Tencent map/weather key, AI keys and Aliyun AK are all `${ENV}` placeholders in `application*.yml`. The squashed root history has none either.
  - `cert/` holds only a README. `.env.*` files have only titles and paths.
  - App: only a placeholder WeChat appid; no keys in `lib/` or `ios/`.
  - Committed identifiers (appid, merchant id, cert paths) are not secrets. The empty Redis password is a dev default.
- **Money switch:** `chengyin.release.money-payments` and `payout` both default to false. Purchase and confirm-test are gated in the controller and the service. TEST_MONEY settlement stays DEFERRED while payout is closed. (See HIGH-S2 for why this switch alone is not enough.)
- **IDOR:** every new player endpoint scopes to the token member, including revenue, receipts, points, my territories, attribute ledger, title equip, shield, dice audit (own team), random dispatch, NPC session, extgame order view (buyer or seller) and session submit.
- **RNG:** server-side SecureRandom / `GameRandom`; no client seeds (apart from the receipt reuse in HIGH-S5).
- **Idempotency:** Monopoly receipts check the body hash, quotes are single-use `FOR UPDATE`, point debits are guarded in SQL, `onVisit` has unique keys and daily, pair and owner caps, extgame has `uk_ego_request` / `uk_egps_nonce` / `uk_egds_date_game`.
- **SQL:** no `${}` in new or changed mapper XML; JdbcTemplate queries are parameterized.
- **Input validation:** bbox ≤ 0.5°, list sizes ≤ 100/200, utterance ≤ 500, coordinate validity checks. All validation is manual on `Map` bodies, with no `@Valid` DTOs.
- **Weather provider:** sends only city-centre coordinates (4 dp), never user GPS. Logs carry no lat/lng.
- **Monopoly path:** holders, visitors and leaderboard are masked; claim, upgrade and challenge are currently always refused (`require_app_attest=true`, and no `CityAttestVerifier` or `CityTaskVerifier` implementation exists). Re-review the client evidence fields (`simulated`, `inAppCamera`, `phash`, `recaptureScore`, `capturedAt`) once verifiers land.

## Recommended order of fixes

1. CRIT-S1, CRIT-S2, HIGH-S1: external-game result trust and reward policy.
2. HIGH-S3, MED-S6, MED-S7: channel authorization.
3. HIGH-S6, HIGH-S7, HIGH-S8, HIGH-S9: deletion, retention, consent, real-name leak.
4. HIGH-S2: a TEST-only money gate separate from the prod switch, before any payments go live.
5. HIGH-S4, HIGH-S5, MED-S4: abuse controls.
6. HIGH-A1, HIGH-A2: admin pages usable by non-super-admins.
7. HIGH-S10: an 18+ policy decision.
