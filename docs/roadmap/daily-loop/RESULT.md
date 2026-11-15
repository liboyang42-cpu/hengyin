# P7 daily-loop — backend receipt (hengyin `feature/r-daily-loop`)

Base: `fix/r5-be-infra-stubs` @ `32b425c33`. App side: chengyin-app `cloud/r-daily-loop`.

## Findings

| ID | Status | How |
|---|---|---|
| GAM-04 (streak is a bare counter, only STREAK_3/7) | **Closed** | Streak freezes (earned, capped, auto-used), ladder 3/7/14/30, today's mission board. |
| GRO-06 (daily missions built but hidden) | **Closed (backend)** | Four concrete daily missions, auto-tracked from real events, returned as `daily` on `/api/growth/center`. App shows them (see app receipt). |
| GRO-07 (streak ignores walking, no freeze field) | **Closed** | A finished walk over the configured threshold counts as an active day; `player_growth.streak_freezes` / `streak_freezes_used`. |

## What changed

**Rules**
- `chengyinhub-system/.../service/support/StreakLedger.java` (new): pure streak rules. Same day = no change; next day = +1; N missed days are covered automatically when the player holds ≥ N freezes (streak continues, N freezes used), otherwise the streak restarts at 1 and freezes are kept. One freeze is earned each time the streak reaches a multiple of `freezeEveryDays`, up to `freezeMax`. `view()` gives the read: current streak, active today, at risk, freezes available after pending cover, broken.
- `.../service/support/DailyLoopSettings.java` (new): `sys_config` keys `growth.streak.walkMinMeters` (500), `growth.streak.walkMinCells` (10), `growth.streak.freezeEveryDays` (7), `growth.streak.freezeMax` (2, compiled ceiling 5). Missing/invalid values fall back to defaults. A walk qualifies on distance **or** new cells.

**Streak**
- `PlayerProgressionServiceImpl`: `onNodeDone` uses the shared ledger (node completions still count check-ins and level as before). New `onActiveDay(memberId)` advances only the streak/freezes (no check-in, no level) and is a no-op on a second call the same day. Badge ladder `STREAK_30/14/7/3` via the existing data-driven `awardByThreshold`. New read `streakSummary(memberId)`.
- `IPlayerProgressionService`: `onActiveDay`, `streakSummary` added.
- `PlayerGrowth` + `PlayerGrowthMapper.xml`: `streakFreezes`, `streakFreezesUsed` columns read and written.

**Daily missions**
- `DailyLoopMapper` (+ XML, new): today's counts from fact tables only — qualifying finished `roam_session`s, `roam_fog_tile` reveals, `player_node_progress` completions, distinct `roam_shop_visit` shops, and today's completed mission codes.
- `DailyMissionBoardReader` (new): which missions are auto-tracked (`trigger_event` in `daily_walk`, `daily_reveal`, `daily_play`, `daily_shop`), today's counts, and the board (target, progress capped at target, completed, configured `exp_reward`).
- `DailyLoopRecorder` (new): hooks run **after the caller's transaction commits**, in their own `REQUIRES_NEW` transaction; failures are logged (`event=DAILY_LOOP_SKIPPED`) and never roll back a walk settlement or node completion. Already-completed missions are skipped before counting (reveal batches are frequent).
- `GrowthServiceImpl`: `completeDailyMission` (only `daily_*` missions; same day key as the existing self-report path, so one reward per mission per day; reward = `growth_mission.exp_reward`, nothing else). Self-report `POST /api/growth/mission/complete` now refuses `daily_*` missions ("该任务完成后会自动记录"), as it already did for `store_verify`. `getCenter` adds `daily` (null on read failure).
- `GrowthMission` + `GrowthMissionMapper.xml`: `targetCount` (`target_count`) in all selects, insert (`ifnull(..., 1)`) and update.
- `MemberGrowthCenterVO.daily`, `DailyMissionBoardVO` (new).

**Hooks (controllers / applications)**
- `ApiRoamController.finish`: after settlement, `afterWalkFinished(distance, sessionTiles)`; the receipt gains `dailyLoop` {qualified, newBadges, missionsCompleted, streak fields} once committed.
- `ApiRoamController.reveal`: `afterCellsRevealed(newly)`. `shopVisit`: `afterShopVisited(inserted > 0)`.
- `PlayNodeCompletionApplication`: `afterNodePlayed` after the node XP grant. This covers the merged random task dispatch (`dispatchedTask` mystery nodes are ordinary node completions), so there is no second mission system.
- `PlayPlayerQueryApplication.growth` (`POST /api/play/growth`): adds `currentStreak, streakActiveToday, streakAtRisk, streakBroken, streakFreezes, streakFreezesPending, streakFreezesUsed, streakFreezeMax, streakFreezeEveryDays, streakLadder, nextStreakBadgeDays, walkMinMeters, walkMinCells`. `streakDays` keeps its old meaning (stored count). No route or field removed.

**Admin console**
- `chengyinhub-ui/src/views/business/growth/index.vue`: "每日目标" (target count) column and form field.

**SQL** — `chengyinhub-admin/sql/migration_20261115_daily_loop.sql` (idempotent, rollback note at top): guarded `ALTER`s for the three columns; `STREAK_14` / `STREAK_30` badge definitions; four missions `DAILY_WALK` (1), `DAILY_REVEAL` (20 cells), `DAILY_PLAY` (1), `DAILY_SHOP` (1) with **`exp_reward = 0`** via `INSERT IGNORE`; four `sys_config` rows via `WHERE NOT EXISTS`.

**Tests (new, `chengyinhub-admin/src/test/java/com/chengyinhub/business/dailyloop/`)**: `StreakLedgerTest` (10), `DailyLoopSettingsTest` (2), `DailyMissionBoardReaderTest` (3), `DailyLoopRecorderTest` (7), `GrowthDailyMissionTest` (4), `ProgressionStreakFreezeTest` (6).

## Verification

```
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -DskipTests compile                       → OK
mvn -B -q -pl chengyinhub-admin -am test -Dtest='StreakLedgerTest,DailyLoopSettingsTest,DailyMissionBoardReaderTest,DailyLoopRecorderTest,GrowthDailyMissionTest,ProgressionStreakFreezeTest'
                                                                                  → 32 run, 0 failures
mvn ... -Dtest='PlayerProgressionServiceImplTest,PlayGrowthCheckinPlacesTest,Growth*Test,ApiRoam*Test,Roam*Test'
                                                                                  → 326 run, 0 failures
mvn -B -pl chengyinhub-admin -am test (full)                                      → 12693 run, 1 failure, 34 errors
node --test chengyinhub-ui/tests/unit/growth-page-migration-contract.test.js     → 2 pass
```
Full-suite residue is not from this package: the 34 errors are MySQL integration tests (`Communications link failure`, no database in this container), and `ChapterFlowWriteServiceTest.openBundleReviewCannotBeBypassedBySwitchingToSimpleMode` fails identically on the untouched base (checked in a separate worktree of `fix/r5-be-infra-stubs`).

## Not verified
- The migration and the new mapper SQL against a real MySQL (no database here). `DailyLoopMapper.xml` uses only existing columns and indexes (`roam_fog_tile.idx_member_day`, `roam_shop_visit.idx_member_time`).
- The after-commit `REQUIRES_NEW` hooks inside a real Spring transaction (unit tests run them without a transaction manager).

## Owner actions and open questions
1. **Deploy order:** run `migration_20261115_daily_loop.sql` **before** the jar (the new jar selects `streak_freezes`, `streak_freezes_used`, `target_count`).
2. **Mission rewards:** seeded at 0 XP so no new grant is invented. Set the XP per mission in 后台「成长任务」 if wanted; until then the app says "No reward set for this one yet".
3. **Thresholds:** walk counts at 500 m or 10 new cells; one freeze per 7-day streak, at most 2. Change in `sys_config` if the owner wants other values.
4. **Missed days without enough freezes:** the streak restarts at 1 and the remaining freezes are kept (conservative: never silently spent on a lost cause). Confirm.
5. "Visit a shop" counts roam shop check-ins (`/api/roam/shop/visit`). Store verification (核销) is not counted; say if it should be.
6. The mini-program does not show the daily board yet; the fields are available on the same routes if wanted.
7. No hard-coded secrets found in the touched files.
