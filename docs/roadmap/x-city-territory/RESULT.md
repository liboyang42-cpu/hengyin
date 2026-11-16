# x-city-territory — backend receipt (W7, wave 3)

Branch `feature/r-x-city-territory` from `origin/roadmap/be-integ` @ `f862f491d`. Review: `review/opus-w7:docs/review/opus/city/ISSUES.md`.
App part: chengyin-app `cloud/r-x-city-territory`, receipt `docs/roadmap/x-city-territory/RESULT.md`.

## Issues

| ID | State | Notes |
|---|---|---|
| CITY-11 | Fixed (backend); app deep link left open | Pushes for takeover, defence, decay reminder, decay release and season result. See "Open" for the app focus link. |
| CITY-12 | Fixed | `/territory/nearby` masks other holders and caps the radius at 5 km; the player leaderboard masks everyone but the viewer. |
| CITY-14 | App only | See the app receipt. No backend change was needed. |
| CITY-16 | Fixed | Map and claim lit check read only the cells in view, from the kept cells. |
| CITY-23 | Not mine | Moved to `x-shop-game-runner`. Not touched. |

## Changes, file by file

- `chengyinhub-system/.../growthloops/ICityEventNotifyService.java`: five new default methods (`monopolyLost`, `defended`, `decaying`, `released`, `seasonResult`). Defaults are no-ops, so other implementations keep compiling.
- `chengyinhub-system/.../growthloops/CityEventNotifier.java`: implements them with the review's zh-Hans and en copy, full-width punctuation next to Chinese text, `KIND_CITY`, one thread per place (`city-terr:<territoryId>`), sent after commit through the existing fan-out. The revenge line names the real window (`challenge.revenge_discount.window_h`) and says "half" only when the rate is 0.5.
- `chengyinhub-system/.../citygame/CityMonopolyNotices.java` (new): never notifies the actor; at most one push per member and place every 6 h (in memory, per node); a missing or failing notifier never affects the caller.
- `chengyinhub-system/.../citygame/CityMonopolyService.java`:
  - `setCityNotify` (optional bean).
  - Challenge WIN: the defender gets `monopolyLost`. Challenge LOSS: the defender gets `defended(total, dc)`.
  - `nightly()`: each reminder day sends `decaying(days)` (skipped on the day the place is released); a decay release that changed a row sends `released`.
  - `settleSeason()`: `seasonResult(rank, participants, xp)`, inside the existing `rewarded_at` guard, so once per member and season.
  - CITY-16: `map()` reads the territories in view first (≤ 500), then `litCells(member, cells)`: `roam_member_fog_cell` by `cell_key IN (…)` while `roam.fog.retainRevealedCells` is on, else `roam_fog_tile` (30-day window), chunked by 200. The claim gate (`FOG_NOT_LIT`) uses the same lookup. Optional `RoamFogCellService` setter.
- `chengyinhub-system/.../citygame/RoamTerritoryService.java`:
  - `nearby`: radius capped at `MAX_NEARBY_RADIUS_M` = 5000 (was 50 000). For rows not held by the viewer, `ownerMemberId`, `ownerNickname` and `claimPhotoUrl` are null (keys kept); new `holderMasked` (same mask as City Monopoly, "玩家1***9") and `isMine`.
  - New `leaderboard(viewer, …)` for players: other holders get `ownerMemberId` = their rank (leaders) or 0 (top places) and `ownerNickname` = the masked name, plus `holderMasked` and `isMine`. The ids stay numbers because released app builds decode `ownerMemberId` as required. The admin console keeps the unmasked `leaderboard(…)`.
- `chengyinhub-admin/.../api/ApiRoamTerritoryController.java`: the player leaderboard passes the viewer; it stays readable without sign-in (then nobody is "mine").
- Tests: `CityMonopolyNotifyWiringTest` (new, 10 tests), `CityEventNotifierTest` (+1 test for the new copy).

No migration, no new `sys_job`, no `@Scheduled`, no new table. The existing index (PK `member_id, cell_key`) serves the kept-cell lookup.

## Verification

All with `LANG=C.UTF-8 LC_ALL=C.UTF-8`.

- `mvn -B -q -DskipTests compile`: passes.
- `mvn -B -q -pl chengyinhub-admin -am test -Dtest='CityMonopolyNotifyWiringTest,CityEventNotifierTest,CityMonopolyEndToEndFixTest,CityMonopolyEconomyTest,CityMonopolyServiceUnitTest,RoamTerritoryClaimTargetTest,CityBoardAndTerritoryUnitTest' -Dsurefire.failIfNoSpecifiedTests=false`: 63 tests, 0 failures, 0 errors (10 + 4 + 20 + 19 + 7 + 1 + 2).
- Ratchets: `PlayerMessagePunctuationTest` 2/2, `TwoNodeSchedulingTest` 4/4, `MemberPrivacyErasureServiceTest` 4/4, `ShopGamesTest` 22/22 (City Monopoly rules untouched by shop games).
- `SysCityGameMysqlIntegrationTest`: skipped (no MySQL here).
- Migration checks: not run, because no migration was added.

## Not verified

- Real pushes on a device (APNs is not configured here). The throttle is per node and resets on restart; with two nodes a holder could get up to two pushes for one place in 6 h.
- The MySQL `IN (…)` lookups ran against mocks only.

## Open / owner questions

1. **CITY-11 step 5 (push opens the place).** The pushes use the existing page `/pages/roam/index`, so a tap opens the roam home, one tap from the City Monopoly screen. The app's push router accepts only mini-program page paths, the mini-program has no territory page, and `pages/roam/index` rejects any query, so a `territoryId` in the link would drop the whole tap. A focused deep link needs a native-only route (`chengyin://city/territory/{id}` or a new page key) in `MiniProgramPageLink`/`MPNotificationTapRouter` and the redesign's new CITY home. That is left for the coordinator or the redesign owner.
2. **Deed photos** (review's open question): `property()` still returns `deedPhoto` to anyone. Not changed. Owner decision needed.
3. **Check-in answer** still returns the holder's `ownerMemberId` to the visitor who checked in (`RoamTerritoryService.checkin`, both branches and replay). The app decodes it as optional, so it can be masked too; not done because it is outside the review's CITY-12 list.
4. The season-result push says all places are open again; this matches `settleSeason`, which releases every City Monopoly place.

## Overlap with the UI agent

None in the backend. No file here is on `fix/registration-list-r3`.
