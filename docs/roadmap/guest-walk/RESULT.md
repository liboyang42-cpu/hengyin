# R4 guest-walk: backend receipt (hengyin)

Branch `feature/r-guest-walk` from `origin/roadmap/be-integ` (5eb0ae1d7, which already holds the backend fog map and location trust). App side: chengyin-app `cloud/r-guest-walk` (see its `docs/roadmap/guest-walk/RESULT.md`).

## Findings

| ID | Status | Notes |
|---|---|---|
| GRO-12 (P2) a guest cannot earn anything | **Closed on the backend** | `POST /api/roam/guest-walk` accepts the one walk a player made before sign-in, once, and credits the revealed cells plus the first-walk reward only. |

## The route

`POST /api/roam/guest-walk` (signed in; not on the anonymous list; rate limit 6/min per member). JSON body, these keys only:

```
{ installId: uuid, walkId: uuid, startedAt: ms, finishedAt: ms, distanceM: int,
  cells: [geohash-7, ≤ 400], fixes: [{lat, lng, accuracy, timestamp: ms, simulated: 0|1, accessory: 0|1}, ≤ 1500] }
```

Rules (`GuestWalkPolicy`, pure):
- **Strict contract**: unknown keys, bad UUIDs, non-geohash-7 cells, over 400 cells, over 1,500 fixes or over 256 KB reject the request with a fixed message (`reason: INVALID`).
- **Time window**: the walk started at most 24 h before the upload, lasted at most 4 h, and no fix is more than 5 min in the future. The app uploads right after sign-in, so the upload time stands in for the sign-in time (the token's `loginTime` is refreshed on every token refresh and cannot be used). Outside the window the walk is recorded but credits nothing (`trustCode: OUT_OF_WINDOW`).
- **Location trust (P8)**: each fix is scored with `LocationTrustPolicy.evaluate` (new kind `GUEST_WALK`) against the previous accepted fix of the same track, on the device clock (the walk is history; the server clock would call every fix stale). Rejected fixes, impossible-speed fixes and fixes worse than the point-accuracy limit (200 m) leave the track. A simulated fix withholds the whole walk under the configured simulated action. SHADOW mode scores but drops nothing; OFF skips scoring. One trail row (`location_trust_fix`, kind `GUEST_WALK`, the last fix, summary detail) is written through the new `LocationTrustService.recordTrack`.
- **Cells**: only cells within `revealTrackRadiusMeters` (600 m) of an accepted fix, then within the walking-speed budget for the walk's duration (30 base cells + duration × 15 m/s / 100 m), then the 400 cap.
- **Credit** (`GuestWalkService`, one transaction): a finished `roam_session` (started/finished at the walk's times, distance clamped to the accepted track and to the client's figure, finish result in the same shape as `/api/roam/finish` with `guestWalk: true`), the cells in `roam_fog_tile` and the kept fog map (`roam_member_fog_cell`) dated at the walk's finish time (so the player's next live walk keeps its full rolling reveal cap), the simplified display track, and the first-walk XP: 1 XP per newly revealed cell, at most 200, through `IGrowthService.addExp`. No POI, check-in, shop, medal, presence or daily-loop credit.
- **Consent**: `RoamLocationConsentService.requireCurrentAgreement`, the same gate as `/api/roam/reveal`. Missing consent answers `reason: CONSENT_REQUIRED, retryable: true` (the app records it through its existing hook and retries).
- **Idempotency**: one walk per install and one per member (`roam_guest_walk`, unique `install_id` and `member_id`). The same install, member and walk id replays the first response with `duplicate: true`. Another walk from the same install answers `GUEST_WALK_INSTALL_USED`; a member who already saved one answers `GUEST_WALK_MEMBER_USED` (both `retryable: false`). A race on the unique keys rolls back and answers `IN_PROGRESS, retryable: true`.
- **Kill switch**: `roam.guestWalk.enabled` (`CHENGYIN_ROAM_GUEST_WALK_ENABLED`, default `true`). Off answers `GUEST_WALK_DISABLED` and stores nothing.

Response `data`: `sessionId`, `cellsSent`, `acceptedCells`, `newlyRevealed`, `uncreditedCells`, `xp`, `distanceM`, `finishedAt`, `duplicate`, and when nothing was credited `uncredited: true`, `trustCode`, `trustMessage` (plain words).

## Measurement

`InstallEventSchema` allows four new anonymous events, closed props only: `guest_walk_start`, `guest_walk_finish` (`cells`: `0`, `1_9`, `10_49`, `50_plus`), `guest_walk_save_prompt` (`phase`: `during`, `after`), `guest_walk_saved` (`result`: `credited`, `uncredited`, `duplicate`). They join the admin pre-sign-in funnel automatically (`FUNNEL_ORDER`).

## What changed

| File | Change |
|---|---|
| `chengyinhub-system/.../business/guestwalk/GuestWalkPolicy.java` | New. Contract parsing and scoring (above). |
| `chengyinhub-system/.../business/guestwalk/GuestWalkService.java` | New. Transactional save, replay, refusals, kill switch. |
| `chengyinhub-system/.../business/guestwalk/mapper/GuestWalkMapper.java` | New annotation mapper for `roam_guest_walk`. |
| `chengyinhub-system/.../business/locationtrust/LocationTrustKind.java` | New kind `GUEST_WALK(false)`. |
| `chengyinhub-system/.../business/locationtrust/LocationTrustService.java` | New `recordTrack(...)`: one trail row for a track scored as a whole. Existing methods unchanged. |
| `chengyinhub-system/.../business/telemetry/InstallEventSchema.java` | Four `guest_walk_*` events. |
| `chengyinhub-admin/.../controller/api/ApiRoamGuestWalkController.java` | New route; refusals carry `reason` and `retryable`. |
| `chengyinhub-admin/src/main/resources/application.yml` | `roam.guestWalk.enabled`. |
| `chengyinhub-admin/sql/migration_20261115_guest-walk.sql` | New table `roam_guest_walk` (`CREATE TABLE IF NOT EXISTS`), rollback note at the top. |
| Tests | `GuestWalkPolicyTest` (10), `GuestWalkServiceTest` (7), `ApiRoamGuestWalkControllerTest` (3). |

No existing route or field was removed or renamed.

## Verification

All Maven runs with `LANG=C.UTF-8 LC_ALL=C.UTF-8`.

```
mvn -B -q -DskipTests compile                                                        → OK
mvn -B -q -pl chengyinhub-admin -am test -Dtest='GuestWalk*Test,ApiRoamGuestWalkControllerTest' \
    -Dsurefire.failIfNoSpecifiedTests=false                                          → 20 run, 0 failures
mvn -B -q -pl chengyinhub-admin -am test -Dtest='GuestWalk*Test,ApiRoamGuestWalkControllerTest,LocationTrust*Test,\
    ClaimEvidenceTest,ApiRoam*Test,Roam*Test,InstallTelemetryServiceTest,AnalyticsControllerPreSignInTest,\
    PublicReadEndpointsSecurityChainTest' -Dsurefire.failIfNoSpecifiedTests=false    → 390 run, 0 failures, 19 skipped (MySQL ITs)
```

The full module suite was not rerun; the earlier packages' receipts list its known pre-existing failures (MySQL-only integration tests, `ChapterFlowWriteServiceTest`).

## Not verified

- The migration and the `roam_guest_walk` statements against a real MySQL (no database here). The service was tested with mocked mappers.
- The rate limiter (needs Redis).
- An end-to-end save from the app against a running server.

## Owner actions and open questions

1. **Migrate and deploy**: `migration_20261115_guest-walk.sql` on TEST, then production. The app does not offer the guest walk on the production host until `GuestWalkRelease.productionSaveApproved` is turned on in the app after this is live there.
2. **Sign-in time**: the 24-hour window is measured to the upload, which the app sends right after sign-in (and retries when offline). If the owner wants the window measured to the exact sign-in, the login service would need to record a sign-in timestamp that token refresh does not move.
3. **First-walk reward**: defined here as 1 XP per newly revealed cell, at most 200 (the same tile XP a normal walk earns). No medal is granted. Confirm, or name a different reward.
4. **One per member**: a member who already saved a guest walk (for example from another phone) cannot save a second one. Confirm this is wanted.
5. **Privacy text**: the pre-sign-in walk is kept on the device and sent at sign-in; its simplified track and one trail row follow the existing 30-day location retention. The privacy policy should say so (legal).
