# W7 x-rewards-evidence: Rewards follow evidence (hengyin)

Branch `feature/r-x-rewards-evidence` from `origin/roadmap/be-integ` @ `cbbf7e3d0`. App side: chengyin-app `cloud/r-x-rewards-evidence`.
Owner decision 2 (2026-11-16): "progress is free, prizes need presence", 80 m. Every new switch defaults to the safe value.

## Issues

| ID | Status | Notes |
|---|---|---|
| GAME-2 | Fixed | Completion is never refused after the start; points, coupons, drops and podium medals need a fix within max(station radius, 80 m) + min(accuracy, 50 m). The start bypass is closed with `start_verified`. |
| GAME-3 | Fixed (adapted) | Photo question, scratch card, timed typing and scored silent order are capped. Step 2 (`stationGameProofKind`) is not needed: `advanceProgress` already re-reads the same session through `rewardCappedToXpBadge`, so a photo-question proof that completes a photo/scan stop through arrive is capped there. Photo completions no longer roll drops. Jigsaw index removed in the app. |
| GAME-4 | Fixed | Mini-program sends a real fix; `/api/city/nodes/{id}/complete` is scored; jitter rule and 10-minute window; first-visit XP capped at 200 a day; `checkinRequiresFix` switch (default off). |
| GAME-11 | Fixed | Duels count only runs started after acceptance and without a remote stop; the view says why a run did not count. |

## Changes, file by file

GAME-2
- `chengyinhub-system/.../service/support/RewardPresencePolicy.java` (new): switches `play.arrive.remote_reward_policy` (`XP_BADGE_ONLY` default, `FULL` = previous behaviour) and `play.arrive.reward_radius_m` (80, clamped 30–1000); `within(...)`, `policyFor(present)`.
- `chengyinhub-admin/.../web/service/play/PlayBasicCommandApplication.java`:
  - `arriveOnce`: after the existing fences and location-trust scoring, computes presence and passes `XP_BADGE_ONLY` + remote flag when absent. Marks `start_verified` when the 300 m start fence passed (legacy completion, runtime arrival, pending compass).
  - `answerOnce` (legacy linear path, located stops): same presence rule on the fix sent with the answer; marks `start_verified` when the start fence passed.
  - `checkinOnce`: a successful on-site scan marks `start_verified`.
  - Reads switches through `ISysConfigService` (optional; absent = safe defaults).
- `.../PlayNodeCompletionApplication.java`: new `advanceProgress(..., boolean remoteCompletion)` overload; podium medal only under `FULL` (shop-day unchanged); writes `remote_completion`; journey completion award capped when any stop of the run is remote; returns `rewardCeiling` + `rewardCeilingReason: "REMOTE"`.
- `ShopDayProofServiceImpl.runStarted` counts only `start_verified = 1` rows; `markStartVerified` added (`IShopDayProofService` default method).
- `PlayerNodeProgressMapper.java/.xml`: `countStartVerified`, `markStartVerified`, `markRemoteCompletion`, `countRemoteCompletions`; `IPlayerNodeProgressService` default methods + impl.
- `sql/migration_20261116_journey_presence.sql`: guarded `ADD COLUMN start_verified ... DEFAULT 1` then `SET DEFAULT 0` (existing rows back-filled to 1 without a table UPDATE), `remote_completion`, sys_config seeds; rollback note. `ci/migration-dependencies.json` entry.

GAME-3
- `AdvancedGameRuntimeServiceImpl.untrustedCompletionEvidence`: `qa:PHOTO_UNJUDGED`, `scratchCard:SELF_REPORTED`, `typeIn:DEVICE_TIME` (only with a time limit), `silentOrder:HONOR` (only scored).
- `PlayNodeCompletionApplication`: no `rollDrop` when `picUrl` is set.

GAME-4
- `LocationTrustPolicy`: jitter allowance = clamped accuracies (20–150 m, missing = 150) + 50 m, never wider than `minMoveMeters` (so clients without accuracy keep today's rule); rolling `windowMinutes` displacement check against the vehicle speed. `Baseline` carries accuracy (4-arg constructor kept).
- `LocationTrustConfig`: `jitterMode` (ACCURACY default, FLAT = old), `windowMinutes` (10), `checkinRequiresFix` (false).
- `LocationTrustService` + `LocationTrustMapper.java/.xml`: baseline reads `accuracy_m`; new `selectFirstCleanFix`.
- `LocationTrustKind.CITY_NODE` (point reward).
- `ApiCityNodeController.complete`: scored before the radius check; rejected = retryable refusal with `trustCode`; withheld = `success` with `uncredited:true` and no reward log; `@RateLimiter(60 s, 30, MEMBER)`.
- `ApiRoamController.checkin`: `checkinRequiresFix` switch answers `uncredited:true, trustCode:FIX_MISSING`; first-visit XP capped at `ROAM_VISIT_XP_DAILY_LIMIT = 200` a day (`capped:true`). `RoamMapper.sumVisitXpToday`.
- Mini-program `pages/roam/index.js`: `onPoiTap` uses the walk's fix (< 30 s) or a fresh `wx.getLocation({type:'gcj02', isHighAccuracy:true})`, sends `locAccuracy`/`locTimestamp`, never falls back to the map centre or the place; toasts for no fix, capped and not counted.
- `subpackageMember/components/cy/scene-roam-poi-detail/index.js`: trust refusals and `uncredited` answers are treated as "not written" with a plain message, not as "result unknown".
- Seeds for the three new `location.trust.*` keys in the same migration.

GAME-11
- `GameMechanicsMapper.selectFirstCompletionSince`: `r.create_time >= since` and no remote stop in the run; new `countRemoteRunsSince`.
- `DuelService`: time = the run's own duration; view adds `myRemoteRun`.

Tests (new or updated): `ApiPlayGpsGeofenceValidationTest` (4 new GAME-2 cases, 2 updated to the new rule), `ShopDayGameThenPhotoH2Test` (`runStartedRequiresAVerifiedStart`, `remoteCompletionsAreCountedPerRun`), `RewardPresencePolicyTest`, `HonorSectionRewardCeilingTest`, `GameMechanicsH2Test` (2 duel cases), `LocationTrustPolicyTest` (4 cases), `ApiCityNodeLocationTrustTest`, `ApiRoamCheckinEvidenceTest`; mini `roam-checkin-real-fix.test.js`, `roam-native-poi-first-visit-toast.test.js` (4 new cases, fixture gives a device fix).

## Verification

All with `LANG=C.UTF-8 LC_ALL=C.UTF-8`.
- `mvn -B -pl chengyinhub-admin -am test` (after GAME-2/3): 10506 tests, 0 failures, 34 errors. Every error is a `*MyBatisTest`/`*MyBatisIT` that needs a live MySQL (`Communications link failure`); none is in an area this package touched.
- Targeted after each step: `ApiPlayGpsGeofenceValidationTest` 47/47, `ApiPlayProgressCompletionTruthTest` 37/37, `ShopDayGameThenPhotoH2Test` 12/12, `AdvancedGameRuntimeServiceImplTest` 236/236, `HonorSectionRewardCeilingTest` 6/6, `RewardPresencePolicyTest` 5/5, `GameMechanicsH2Test` 7/7, `LocationTrustPolicyTest` 28/28, `ApiCityNodeLocationTrustTest` 3/3, `ApiRoamCheckinEvidenceTest` 4/4; `-Dtest='*LocationTrust*,Roam*,ApiRoam*,ApiCityNode*,PlayerMessagePunctuationTest,GuestWalk*'` 451 run, 0 failures, 4 errors (two MySQL ITs).
- `bash ci/migration-safety-check.sh`: 迁移安全门禁:通过. `bash ci/migration-layout-check.sh`: 迁移布局门禁:通过.
- Mini-program: `node ../ci/prepare-mini-artifacts.js`, then `node --test "tests/unit/**/*.test.js"`: base 17910 tests / 30 failing; branch 17918 / the same 30 failing (`diff` of the failing names is empty). The 3 that broke on the first pass were the onPoiTap toast fixture, now updated to give a device fix.
- `node tests/lint/ui-integrity.js --warn-as-error`: exit 1 on base and branch (pre-existing U5/LEGACY items). The only difference is the content hash of the existing `[U1 DYNAMIC] scene-roam-poi-detail … /api/city/nodes/:dynamic/complete` item, whose surrounding lines changed; no new item, no new toast over 16 characters, no placeholder words.

## Not verified here

- No MySQL: the migration was not run; the `start_verified` DEFAULT 1 → 0 sequence and the `remote_completion` queries are checked on H2 only.
- Mini-program on a device (WeChat location permission prompt on a POI tap when no walk is running).

## Owner and coordinator actions

1. Apply `migration_20261116_journey_presence.sql` **before** deploying this backend (the code reads the new columns).
2. Turn `location.trust.checkinRequiresFix` to `true` only after the fixed mini-program is live (owner order in the review: mini-program first, then the switch).
3. Confirm the 10-new-places-a-day XP cap (GAME-4) and that a capped photo question / scratch card / timed typing / scored silent order no longer pays points or coupons (GAME-3, consistent with C1/C5). Merchants who want a photo coupon use the AI photo check or a counter scan.
4. Podium medals now follow the same gate as points (no medal for remote or C1-capped completions; shop-day unchanged). Confirm.
5. Ships together with x-journey-app: do not ship the app's GAME-1 grant change before this backend and migration are deployed.

## Left open / notes

- The generic runtime ("通用个人任务") and shared-team paths settle tasks elsewhere and do not pay node points; they record arrival only, and `start_verified` is set on the runtime arrival when the start fence passed. Not changed further.
- The answer path does not call location trust (it never did); presence there is distance-only on the fix sent with the answer.
- Out of scope, seen while working: the backend full run has 34 MySQL-only IT errors in this environment.

## Overlap with the UI agent

- None of the UI agent's acceptance defects were fixed here.
- Shared file: `chengyinhub-admin/.../web/service/play/PlayBasicCommandApplication.java` (the stop-answer path). `origin/fix/registration-list-r3` changes its answer validation (B5, commit `fdf15c0ff`); this package changes reward presence in other hunks.
- Not cherry-picked: `fdf15c0ff` is one 35-file commit (B1–B8: withdrawal, clubs, IM, login), so taking it would bring unrelated defects into this package. `origin/fix/registration-list-r3` is built on `integration/backend-app-r3` and already conflicts with `roadmap/be-integ` in 20+ files; `git merge-tree` shows exactly the same conflict list against this branch as against the base, so this package adds no new conflict.
