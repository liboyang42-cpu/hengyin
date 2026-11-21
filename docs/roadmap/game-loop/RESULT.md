# G1 game-loop: backend receipt (hengyin)

Branch `feature/r-game-loop` from `origin/roadmap/be-integ` (5db6d6e37). Resumed run: the code commit `3cc8d9386` came from the
earlier run (stopped on a usage limit); this run re-verified it and added this receipt. The base has since moved
(in-game channels, 7de694525/81b65553d); nothing in this package needs that work, so it was not merged in.
App side: chengyin-app `cloud/r-game-loop` (see its `docs/roadmap/game-loop/RESULT.md`).

## Findings

| ID | Status | Notes |
|---|---|---|
| IMM-14 no server clock for play | **Closed (server half)** | The advanced play view (`/api/play/advanced` session view) now carries `serverNow` (ms) from the same clock as `startedAt`/`deadlineAt`. |
| GRO-15 no friend or district scope | **Closed (server half)** | `POST /api/growth/leaderboard` takes an optional `scope` (`friends` / `district`); no or unknown scope = the original global board, unchanged. |

IMM-17, IMM-18/POS-05, IMM-11, GRO-17 are app-only (existing narrative, weather and drop data).

## What changed

| File | Change |
|---|---|
| `chengyinhub-system/.../AdvancedGameRuntimeServiceImpl.java` | `view.put("serverNow", clock.now().getTime())` beside `deadlineAt`. |
| `chengyinhub-system/.../growthboard/GrowthScopedLeaderboard.java` (new) | Friends = people I follow + members of my clubs; district = same `ums_member.area_id`. Others appear only if they opted in (`growth_board_visibility.visible = 1`) and never if `member_progress_privacy.leaderboard_scope = 'OUT'`. The viewer always sees their own row. Same ranking rule as the global board (score desc, then join time, then id; zero scores unranked). No area set → `state: "noArea"`, empty list. Candidate cap 2000. Response adds `scope` and `visible`. |
| `chengyinhub-system/.../mapper/GrowthScopedBoardMapper.java` + `mapper/business/GrowthScopedBoardMapper.xml` (new) | Candidate, score, area and opt-in queries. |
| `chengyinhub-system/.../domain/dto/LeaderboardQueryDTO.java` | Optional `scope` field. |
| `chengyinhub-admin/.../api/ApiGrowthController.java` | Scoped branch in `/leaderboard`; new `GET` and `POST /api/growth/leaderboard/visibility` (signed-in only; `visible` required). Existing routes and fields unchanged. |
| `chengyinhub-admin/sql/migration_20261115_game_loop.sql` (new) | `CREATE TABLE IF NOT EXISTS growth_board_visibility` (opt-in, default off). Rollback note at the top (drop table by hand). |
| `ci/migration-dependencies.json` | Ledger entry `20261115_game_loop: []`. |
| Tests | `GrowthScopedLeaderboardTest` (new, 10 cases incl. the XML mapper parse); `AdvancedGameRuntimeServiceImplTest` asserts `serverNow`. |

## Verification (this run)

```
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -DskipTests compile                                  → exit 0
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -pl chengyinhub-admin -am test \
  -Dtest='GrowthScopedLeaderboardTest,AdvancedGameRuntimeServiceImplTest' -Dsurefire.failIfNoSpecifiedTests=false
  → GrowthScopedLeaderboardTest 10 run, 0 failures; AdvancedGameRuntimeServiceImplTest 234 run, 0 failures
bash ci/migration-safety-check.sh                                                           → 迁移安全门禁:通过
ls chengyinhub-admin/sql | grep -c '^rollback_'                                             → 0
```

Not verified: queries against a real MySQL (the XML is parsed and the service tested with a mocked mapper); load on large follow/club graphs (capped at 2000 candidates).

## Owner actions and open questions

- Deploy and run migration `20261115_game_loop` before turning on the app's `GrowthScopedBoardRelease.productionApproved`.
- Privacy text: the friends/area boards and the opt-in should be named in the privacy policy (default off).
- Question: should "friends" also include mutual friends from the native friend list (`/api/native/friend`)? Today it is follows + club mates, as the brief says.
- No money, no production switch, no secrets touched.
