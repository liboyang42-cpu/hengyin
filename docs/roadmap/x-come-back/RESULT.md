# x-come-back (W7 game-core package 4, "Reasons to come back"): backend receipt

Branch `feature/r-x-come-back` from `origin/roadmap/be-integ` @ `87aaf3dd2`. App side: chengyin-app `cloud/r-x-come-back`.

## Issues

| ID | State | Notes |
|---|---|---|
| GAME-12 | Fixed on the server, **behind a default-off switch**; app send path open | Place stamps (kind 1) need the place, a trusted fix and the player within the place radius + 50 m; one per member and place. `chengyin.roam.place-stamp.enabled` defaults to `false` (owner decision pending: "stamps are place-bound collectibles, photos stay postcards"). While off, nothing changes. |
| GAME-13 | Fixed | Players set their home area ("use my current district") or clear it from the area board. Only the 6-digit district code is stored; the fix is used once and not kept. |
| GAME-14 | No backend change | Step 1 is an owner/console action (set `exp_reward` in 成长任务; suggested 10/10/15/10 XP). App side fixed. |
| GAME-18 | No backend change | App only. |
| GAME-19 | Fixed | Time-window refusals say "not open yet" vs "closed for today", with `reasonCode`, `opensAt`, `closedAt`. |

## Changes, file by file

GAME-19
- `chengyinhub-system/.../service/impl/TimeWindowRefusal.java` (new): extends `IllegalStateException` (existing catch blocks unchanged). `judge(from, to, now)` returns `TIME_WINDOW_NOT_YET` (next opening later today, including the daytime gap of an overnight window) or `TIME_WINDOW_CLOSED` (a normal window after it closed; come back tomorrow). Messages: 「这一站 HH:mm 开放，到时再来」/「这一站今天 HH:mm 已关闭，明天 HH:mm 再来」.
- `AdvancedGameRuntimeServiceImpl.requireWithinTimeWindow`: uses `judge`; the invalid-config branch is unchanged.
- `ApiAdvancedPlayController`: `/start` and `/action` add `reasonCode`, `opensAt`, `closedAt` to a time-window refusal (`withTimeWindowReason`). Same HTTP code and `msg` key as before.
- Tests: `AmbientKitJudgementTest.timeWindowBeforeInsideAfterAndOvernight`; `PlayKitV51RuntimeTest` now asserts the new text and code.

GAME-12
- `RoamStamp` + `RoamStampMapper(.java/.xml)`: `poi_id`; `countPlacesByMember`, `selectByMemberAndPoi`; insert writes `poi_id`.
- `ApiRoamStampController.create(picUrl, caption, idempotencyKey, poiId, lat, lng)`: the old three-field call is kept as an overload. With the switch on and a `poiId`: place must be live with coordinates; fix required; `LocationTrustKind.CITY_NODE` scoring (rejected = retryable refusal `STAMP_LOCATION_UNTRUSTED`; doubtful = saved as a postcard with `placeStamped:false`); outside radius + 50 m = retryable `STAMP_TOO_FAR`; a second stamp for the same place returns the first (`alreadyStamped:true`, also under a concurrent duplicate-key race). Only the place's own coordinates are stored, never the player's fix. Reply `{id, kind, placeStamped, alreadyStamped, placeName, placesStamped}`. `/list` adds `placesStamped` only while the switch is on.
- Adapted from the review: dedupe is by `uk_member_poi (member_id, poi_id)`, not by `geohash7`, because two places can share one geohash-7 cell; `geohash7` stays NULL. The review named `/api/roam/passport/history`; the app's passport count reads `/api/roam/stamp/list`, so `placesStamped` is there.
- `sql/migration_20261116_x_come_back_place_stamp.sql` (new, idempotent, guarded): `roam_stamp.poi_id` + `uk_member_poi`. Must land **before** the new jar (the insert writes `poi_id`). Entry in `ci/migration-dependencies.json` (depends on `p6_roam_stamp`).
- Test: `RoamStampPlaceTest` (at the place; same place twice is idempotent; far fix refused and retryable; untrusted refused and doubtful kept as a postcard; switch off = old behaviour).

GAME-13
- `GrowthScopedBoardMapper(.java/.xml)`: `updateAreaId` (player's own explicit save or clear).
- `GrowthScopedLeaderboard`: `hasHomeArea`, `setHomeArea` (6-digit code or null), `validAreaCode`.
- `IMapGeocodeService.resolveDistrict` (default null) + `MapGeocodeServiceImpl.resolveDistrict/parseDistrict` (Tencent reverse geocode `ad_info.adcode` + district name; not cached, coordinates not stored).
- `ApiGrowthController`: `GET /api/growth/leaderboard/visibility` adds `homeAreaSet`; new `POST /api/growth/leaderboard/home-area` (`lat,lng` or `clear=true`, 10/min per member).
- Adapted from the review: the review asked for `areaCode` on `/api/member/update` plus a searchable list in the profile editor. The app has no district list, and the profile editor is a reviewed draft pipeline; the area board is the only reader of `area_id`. So the home area is set where it is needed (the area board), in one step the player taps. `/api/user/update` and the mini-program `pages/gerenziliao` field are left for a follow-up.
- Tests: `GrowthScopedLeaderboardTest.districtAfterAreaSet`, `homeAreaIsASixDigitCodeAndCanBeCleared`; `MapGeocodeServiceImplTest.districtComesFromAdcodeAndDistrictName`.

## Commands and results

- `LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -DskipTests compile`: OK.
- `mvn -B -q -pl chengyinhub-admin -am test -Dtest='*AdvancedPlay*,*AdvancedGameRuntime*,*AmbientKit*,*PlayKit*,*RoamStamp*,*Growth*,*MapGeocode*,PlayerMessagePunctuationTest,SysJobStaggerPlanTest,TwoNodeSchedulingTest,MemberPrivacyErasureServiceTest,E2eTestProvidersProfileTest,*TimeWindow*'`: 601 run, 0 failures, 4 errors. The 4 errors are `AdvancedGameRuntimeMySqlIT` ("Communications link failure": no MySQL in this container), not this change.
- `bash ci/migration-safety-check.sh`: 迁移安全门禁:通过. `bash ci/migration-layout-check.sh`: 迁移布局门禁:通过.
- No new `@Scheduled`, sys_job or table; no `rollback_*.sql`.

## Needs the owner or a device

- GAME-12: confirm place stamps (then set `chengyin.roam.place-stamp.enabled=true` on TEST first). The app does not send `poiId` + fix yet (see the app receipt).
- GAME-13: approve the explicit home-area field and add the home area and area board to the privacy text. The scoped boards are still off on the China production host (`GrowthScopedBoardRelease.productionApproved = false`).
- GAME-14: set `exp_reward` for the four daily missions in the console.
- Apply `migration_20261116_x_come_back_place_stamp.sql` before deploying the jar.

## Related problems found, not fixed (outside this package)

- `roam_stamp` is not in `MemberPrivacyErasureService` (the location-history erasure clears fog, sessions, check-ins, territory; not stamps). With GAME-12 a place stamp records that the player was at a place, so it should join that erasure (or at least have `poi_id` cleared).
- `/api/user/update` still has no home-area field; the mini-program profile page has none either.

## Overlap with the UI agent

None. `fix/registration-list-r3` touches none of these files.
