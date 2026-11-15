# P8 location-trust: backend receipt (hengyin)

Branch `feature/r-location-trust` from `fix/r5-be-infra-stubs`. The app side is on chengyin-app `cloud/r-location-trust` and has its own receipt.

## Findings

| ID | Status | How |
|---|---|---|
| SEC-04 (P1) roam writes not rate-limited | closed | `@RateLimiter(limitType=MEMBER)` on `/api/roam` reveal 60/min, check-in 30/min, discover 60/min, shop visit 30/min, presence 20/min, finish 10/min, session/records 20/min. `@RepeatSubmit` was not added: these writes are idempotent, and the interceptor would reject honest retries from the offline queue. |
| SEC-05 (P0) no simulated flag or attestation (server side) | closed for the server | Every location write accepts optional `locAccuracy`, `locTimestamp`, `locSimulated` and `locAccessory`. `LocationSignals` reads them from the request, so no route signature or request fingerprint changed. App Attest verification on the server is still open (see below). |
| SEC-06 (P1) check-in trusts client coordinates | closed | The fix is scored before XP. Accuracy worse than 200 m or a fix time in the future gets a retryable refusal. A simulated fix or an impossible speed is answered `success` with `uncredited:true`, `xp:0` and `trustCode`. The visit is NOT recorded, so a later honest check-in at the same place still earns the first-visit XP. |
| SEC-06 (P1), City node check-in | closed | `POST /api/city/nodes/{id}/complete` is the check-in the app actually uses. It is scored the same way (CHECKIN kind) and rate-limited at 30/min. A refused or withheld fix returns a retryable error and nothing is written, so an honest retry later still completes the node. |
| SEC-07 (P1) fog tiles without adjacency or track proof | closed | `/api/roam/reveal` takes an optional current fix (`lat`, `lng` plus the loc* fields). With a fix, only tiles within 600 m of the session's accepted reveal fixes are credited. The session budget now uses 15 m/s, so an idle hour gives 740 tiles instead of 1,460. A rolling cap allows 390 new tiles per 10 minutes across all sessions. A simulated fix credits no tiles. Old clients send no fix and fall back to the budget and the rolling cap. Dropped tiles are logged and reported as `uncreditedTiles`; they are never an error, so the upload queue still drains. |
| SEC-08 (P1) impossible-speed shop visits credited | closed | A withheld fix answers `recorded:false, uncredited:true`. Neither the visit nor the anonymous visitor count is written. The old D7 audit log line is kept. |
| SEC-09 (P1) journeys check location only at start | closed | `PlayBasicCommandApplication` scores the fix at the start and at every later station that sends one. A simulated or impossible fix gets a retryable `ARRIVE_LOCATION_UNTRUSTED` (`appealable:false`). The owner decision of 2026-11-13 still holds: after the start a missing fix is accepted, unless `location.trust.journeyRequireFixAfterStart=true`. Journeys use the 35 m/s vehicle limit because riding between stations is allowed. |
| SEC-10 (P0) CITY claim evidence | server API closed; claim flow is the coordinator's | New route `POST /api/location-trust/claim-evidence` (multipart). It checks: camera source only; photo taken within 15 min; photo GPS (when present) within 300 m of the place; the sent fix scored and within 300 m, or else a clean server fix within 300 m in the last 15 min; duplicates by SHA-256 and by 64-bit dHash (distance ≤3 rejects, ≤8 goes to review). Verdicts are ACCEPT, REVIEW or REJECT. `ClaimEvidenceService.consumeForClaim(memberId, evidenceId, placeRef, claimRef)` makes one ACCEPT evidence back exactly one claim. `GET /api/location-trust/claim-evidence?evidenceId=` reads a result without using it up. |

## Scoring (`LocationTrustService` / `LocationTrustPolicy`)

There are three outcomes: ACCEPT, ACCEPT_NO_REWARD (kept on the review trail, nothing credited) and REJECT (a retryable refusal). Defaults only stop clearly impossible input:

- **Speed:** above 15 m/s on foot when the last clean fix is at most 5 min old. Over a longer gap, and always for journeys and presence, the limit is 35 m/s. Moves under 300 m count as GPS jitter. The time gap is the larger of the server gap and the device-clock gap; the device gap is only trusted within bounds, so offline-queued writes are not punished.
- **Speed baseline:** only fixes scored ACCEPT and not simulated. A teleport therefore never becomes the next baseline, and an honest player recovers as time passes.
- **Hard refusals:** accuracy worse than 200 m on point rewards (check-in, discover, shop visit, claim), a fix time more than 120 s in the future, and invalid numbers.
- **Flag only by default:** a stale fix (over 30 min old), an accessory fix (car or GPS puck), and the same fix repeated 6 times in an hour.
- **Simulated fix:** NO_REWARD by default.
- **Missing signals:** add nothing. The mini-program and old builds score exactly as before.
- **Configuration:** every threshold lives in `sys_config` under `location.trust.*`. Bad values fall back to the defaults and are clamped to a sane range. `location.trust.mode` is ENFORCE (default), SHADOW (score and record, never block) or OFF.
- **Review trail:** every scored write gets a row in `location_trust_fix`, written in its own transaction (REQUIRES_NEW) so refused writes are recorded too. Anything not clean also writes a structured line on logger `com.chengyinhub.locationtrust.review`. Operators can read it at `GET /business/locationTrust/flagged` (permission `business:locationTrust:list`).
- **Fail-open:** if the config or the tables are unavailable (for example, the migration is not applied yet), the write is scored ACCEPT and behaves exactly as before, and an error is logged.

## Files

- `chengyinhub-system/.../business/locationtrust/`: `LocationFix`, `LocationTrustKind`, `LocationTrustDecision`, `LocationTrustAction`, `LocationTrustConfig`, `LocationTrustPolicy`, `Geohash`, `LocationSignals`, `LocationTrustService`, `LocationTrustMessages`, `LocationTrustFixRow`, `ClaimEvidenceRow`, `ClaimEvidencePolicy`, `ClaimEvidenceService`, `PerceptualHash` (all new).
- `chengyinhub-system/.../mapper/LocationTrustMapper.java` and `resources/mapper/business/LocationTrustMapper.xml` (new).
- `chengyinhub-system/.../service/impl/DataRetentionServiceImpl.java`: the 30-day cleanup now deletes trail rows and blanks evidence photo coordinates. It is guarded, so the existing roam cleanup still runs before the migration.
- `chengyinhub-admin/.../controller/api/ApiRoamController.java`: trust scoring and rate limits; the new reveal overload adds `lat`/`lng`; the old 2- and 3-argument `reveal` methods are kept.
- `chengyinhub-admin/.../service/play/PlayBasicCommandApplication.java`: SEC-09.
- `chengyinhub-admin/.../controller/api/PlayCommandErrorContract.java`: new code `ARRIVE_LOCATION_UNTRUSTED`; no existing code changed.
- `chengyinhub-admin/.../controller/api/ApiCityNodeController.java`: trust scoring and a rate limit on node complete.
- `chengyinhub-admin/.../controller/api/ApiLocationTrustController.java` (new) and `controller/business/LocationTrustReviewController.java` (new).
- `chengyinhub-admin/sql/migration_20261115_location_trust.sql`: two tables plus 8 config seed rows, inserted only when missing. The rollback note is at the top.
- Tests (new): `LocationTrustPolicyTest` (24), `ClaimEvidenceTest` (12), `LocationTrustServiceTest` (8), `LocationTrustMapperMyBatisTest` (3, H2 MySQL mode), `ApiRoamLocationTrustTest` (6).

No existing route or field was removed or renamed. The new response fields are `uncredited`, `trustCode`, `trustMessage` and `uncreditedTiles`.

## Verification

All commands were run with `LANG=C.UTF-8 LC_ALL=C.UTF-8`.

- `mvn -B -q -DskipTests compile`: passed.
- `mvn -B -q -pl chengyinhub-admin -am test -Dtest='LocationTrust*Test,ClaimEvidenceTest,ApiRoamLocationTrustTest' -Dsurefire.failIfNoSpecifiedTests=false`: 53 tests, 0 failures.
- `mvn -B -q -pl chengyinhub-admin -am test -Dtest='Roam*Test,ApiRoam*Test' -Dsurefire.failIfNoSpecifiedTests=false`: 274 run, 0 failures, 19 skipped (MySQL integration tests). This includes the existing reveal-budget, check-in, presence and shop-visit tests, which are unchanged.
- Full `mvn -B -pl chengyinhub-admin -am test`: 12,610 run, 1 failure, 34 errors, 559 skipped.
  - The 34 errors are in 14 `com.chengyinhub.business.it.*MyBatisTest` classes. They need a live MySQL ("Communications link failure"), and no MySQL is available in this environment.
  - The 1 failure, `ChapterFlowWriteServiceTest.openBundleReviewCannotBeBypassedBySwitchingToSimpleMode`, fails the same way on the untouched base `origin/fix/r5-be-infra-stubs` (44 run, 1 failure). It is not caused by this package.

## Not verified

- Against a real MySQL. Three statements are MySQL-only and were only checked for rendering: `bit_count(... ^ ...)` and `<=>` in `selectNearestPhash`, and `DELETE/UPDATE … ORDER BY … LIMIT` for retention. The migration was not run on any database.
- The rate limiters run through the existing Redis aspect; this needs Redis to verify.
- dHash on real iPhone photos. HEIC is not decodable by ImageIO, so the app must upload JPEG; an unreadable photo goes to REVIEW, not REJECT.
- The near-duplicate search scans up to 365 days of evidence with `bit_count`. That is fine at launch volume; at scale it needs a BK-tree or a hash-prefix index.

## Owner and coordinator actions, open questions

1. **Apply the migration.** `migration_20261115_location_trust.sql`, through the usual read-only preflight.
2. **Claim flow (coordinator).** The app calls claim-evidence after the camera shot and sends `evidenceId` with the claim. The claim service calls `consumeForClaim` in its transaction. The coordinator decides what REVIEW evidence may do; the conservative choice is to hold the claim until a person checks it.
3. **Owner decisions this package overrides (please confirm).**
   - SEC-08: shop visits at impossible speed are now not credited. This replaces D7 (2026-10-23, log only).
   - SEC-09: a fix sent at a later journey station is now scored. A missing fix after the start is still accepted, per the owner decision of 2026-11-13. `journeyRequireFixAfterStart` stays off until the owner decides.
4. **App Attest on the server.** The app can attach `attestAssertion`/`attestKeyId` to check-in and claim writes (switch default off). The server does not verify them yet. `NativeStepAssertionVerifier.verifyAndConsume` must run inside the write transaction, and a failure would mark that transaction rollback-only. It should only be wired once the verifier is production-ready, as a positive signal and never a block.
5. **Review UI.** There is only a JSON list for now. A menu entry and permission row (`business:locationTrust:list`) can be added with the admin redesign.
6. **CITY territory claim and check-in (other backend branch).** These routes are not in this base. The app now sends `locAccuracy`, `locTimestamp`, `locSimulated` and `locAccessory` as optional JSON string fields on those bodies. Whoever owns those routes should pass them to `LocationTrustService.assess(…, LocationTrustKind.CHECKIN / CLAIM_EVIDENCE, …)` through `LocationSignals.parse(...)`, and accept unknown JSON fields; the project ObjectMapper must not fail on them.
7. **Privacy text.** The 30-day trail of location fixes and the photo hashes should be mentioned in the location privacy notice (legal).
