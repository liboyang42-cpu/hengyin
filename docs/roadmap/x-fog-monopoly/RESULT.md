# x-fog-monopoly — backend receipt (W7)

Branch `feature/r-x-fog-monopoly` from `origin/roadmap/be-integ` @ `9997b038b` (already contains `x-city-monopoly`,
`x-city-economy`, `x-city-territory`, `x-shop-game-runner`; `x-orienteering-run` has no backend branch).
Review: chengyin-app `cloud/r-x-fog-monopoly`, `docs/roadmap/x-fog-monopoly/REVIEW.md`. App receipt: same folder, `RESULT.md`.

## Fixes (numbers from the review's ranked list)

| # | Fix | State |
|---|---|---|
| 2 | Map read says each place's state, its rent per visit, and (asked for) the free places, under the fog rule | Done |
| 6 | Check-in answers mask the holder (carried over from x-city-territory) | Done |
| 7 | Deed sends its rent multipliers and their order | Done |
| 9 | Season read sends this week's rent event | Done |
| — | Deed photos returned to anyone (`property()` → `deedPhoto`) | Owner decision, left as is |

## Changes, file by file

- `chengyinhub-system/.../citygame/CityMonopolyService.java`
  - `map(member, bbox…)` keeps its signature and calls the new `map(…, includeFree)`.
  - Every lit row adds `state` (`MINE` / `HELD` / `PROTECTED`), `fogged: false` and `rentPerVisit` (tier base × level
    multiplier, × `decay.weathered.yield` while weathered, rounded). Unlit rows (`foggedRow`) keep only `districtId`,
    `tier`, `geohash7` and add `state: FOGGED`, `fogged: true`; no id, title, holder or rent.
  - `includeFree=true`: `freeInView` lists up to 300 `city_game_point` rows in the box that are available, not deleted,
    on a board where City Monopoly is LIVE (`liveBoard`, cached per read) and have no ACTIVE `roam_territory`. Their
    cells join the same `litCells` lookup (kept cells, chunks of 200). Lit: `pointId`, `boardId`, `poiId`, `title`,
    `tier`, `districtId`, `geohash7`, `lat`, `lng`, `state: FREE`, `rentPerVisit` (tier base). Unlit: the fogged row.
    A point without a stored cell gets one computed (`RoamTerritoryService.encodeGeohash7`). Returned under `free`, so
    older apps (which read `items`) see nothing new.
  - `property()`: keeps `estimatedYield`; adds `multipliers` (key → factor, two decimals, factors of exactly 1 left out)
    and `multiplierKeys` (order: level, set, time, env, launch, team, charm, weathered, concentration, then any new key).
  - `seasonCurrent()`: adds `eventMultiplier` (`yield.event_multiplier` clamped to `event_multiplier_max`, as rent uses it).
- `chengyinhub-system/.../citygame/RoamTerritoryService.java`: new `putHolder(out, owner, viewer)`: `ownerMemberId` is the
  id only for the viewer's own place (key kept, null otherwise), plus `holderMasked` (`CityMonopolyService.mask`) and
  `isMine`. Used by `checkin` (City Monopoly and legacy branches), `checkinReplay`, and `rewardExternalCheckin`'s legacy
  answer (which reaches `/api/roam/checkin` as `territoryReward`).
- `chengyinhub-admin/.../api/ApiCityMonopolyController.java`: `GET /api/city-game/territory/map` takes optional
  `includeFree` (default false). Rate limit unchanged (60 per minute per member).
- `chengyinhub-admin/src/test/.../citygame/CityMonopolyFogMapTest.java` (new, 8 tests): states and rent per visit; the
  fogged row carries no id, title, holder or rent; free places only when asked and under the fog rule; boards not LIVE
  are left out; a missing cell is computed; multiplier order without ×1 factors; masked check-in (helper and replay);
  the rent event clamp.

No migration, no new table, no `sys_job`, no `@Scheduled`. No route or field was removed or renamed.

## Verification

All with `LANG=C.UTF-8 LC_ALL=C.UTF-8`.

- `mvn -B -q -DskipTests compile`: passes.
- `mvn -B -q -pl chengyinhub-admin -am test -Dtest='CityMonopolyFogMapTest,CityMonopolyServiceUnitTest,CityMonopolyEconomyTest,RoamTerritoryClaimTargetTest,CityBoardAndTerritoryUnitTest,PlayerMessagePunctuationTest,TwoNodeSchedulingTest,MemberPrivacyErasureServiceTest' -Dsurefire.failIfNoSpecifiedTests=false`
  and the same with `CityMonopolyNotifyWiringTest,CityMonopolyEndToEndFixTest`: all pass —
  CityMonopolyFogMapTest 8/8, CityMonopolyNotifyWiringTest 10/10, CityMonopolyEndToEndFixTest 20/20,
  CityMonopolyServiceUnitTest 7/7, CityMonopolyEconomyTest 19/19, RoamTerritoryClaimTargetTest 1/1,
  CityBoardAndTerritoryUnitTest 2/2, PlayerMessagePunctuationTest 2/2, TwoNodeSchedulingTest 4/4,
  MemberPrivacyErasureServiceTest 4/4 (no new table, so no erasure change).
- Migration checks: not run; no migration was added.
- `SysCityGameMysqlIntegrationTest`: not run (no MySQL here). It calls `rewardExternalCheckin` and asserts `credited`
  only, which is unchanged.

## Not verified

- The `city_game_point` query (`NOT EXISTS` on `roam_territory`, `LIMIT 300`) ran against mocks only. On a large
  board it uses `PRIMARY KEY (board_id, point_id)` for the subquery; the latitude range has no index. Add
  `idx_city_game_point_lat_lng (latitude, longitude)` if the console shows slow reads.

## Owner actions and open questions

1. **Deed photos** are still returned to any member on the deed card (`property()` → `deedPhoto`). Owner decision.
2. Fog hints give a place's ~150 m cell (`geohash7`) but never its name or holder. If the owner wants the fog to hide
   even that, drop `geohash7` from `foggedRow`; the app then shows a count only ("迷雾里还有 N 处地盘").
3. **Push opens the place** (CITY-11 step 5) still needs the app route into the redesign's CITY home; see the app
   receipt. The pushes could carry `territoryId` as an extra payload key once the app reads it; not added, so older
   builds keep parsing the same page path.

## Overlap with the UI agent

None in the backend. No file here is on `fix/registration-list-r3`.
