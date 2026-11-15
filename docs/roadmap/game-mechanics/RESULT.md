# G2 game-mechanics — backend receipt (hengyin `feature/r-game-mechanics`)

Base: `origin/roadmap/be-integ` @ `81b65553d`. App: chengyin-app `cloud/r-game-mechanics` (own receipt).

## Findings

| ID | Status | How |
|---|---|---|
| GAM-07 | **Closed** | Silent order, music corner and time window get server actions, a server verdict and an optional reward. Opt-in per kit with `scored` (default false), so every existing template behaves exactly as before (the #857 / 2026-08-26 "no verdict" decision still holds for unscored kits). |
| GAM-06 | **Closed (treasure hunt, async duel)**; co-op raid not built | Treasure hunts with location-trusted finds, progress and one final points reward; creator flow (app) and admin console authoring/review. Asynchronous duel between friends or club mates on one journey; server reads both completions; in-app notice to both; no stake. A co-op raid was not in the brief's build list and is left open. |
| GAM-13 | **Closed** | Desk rehearsal and on-site test are stored per journey; publish check shows the gate; `creator.walktest.gate` OFF / ADVISE (default) / ENFORCE (refuses publishing); operators waive with a required note. |
| GAM-14 | **Closed** | Mini-program node editor now renders the registered `cy-ai-assist` and calls the existing `/api/ai/node/generate`; the app node editor has the same (see app receipt). AI output is labelled and fills only empty fields. |
| GAM-12 | **Closed behind a flag (owner question)** | `creator.player.verified_unlock` (default `false` = today's player defaults). When `true`, players holding the new `member_capability.verified_creator` get branching + game mechanics. Operators set the mark with `PUT /business/creatorVerify/{memberId}/{on|off}`. |
| GAM-08 | **Closed** | `/api/betagame` is a real feature (admin-authored beta game surveys, rating + comment, points rule 4): the app now has a client. `/api/world` holds no real feature (read-only published worlds/chapters/characters/quests; admin can only save a world row, no UI, no quest progress or reward) → documented, routes left for compatibility, no client built. |
| GAM-11 (remainder) | **Left open (owner)** | `ApiAiController.gateRole` still refuses the player role for node generation; players get the server's honest message in both clients. One switch could reuse `verified_unlock` — owner decision. |

## What changed, file by file

- `chengyinhub-system/.../service/impl/AmbientKitJudgement.java` (new): START/FINISH_SILENT_ORDER (server clock; within `limitSeconds` +1.5 s passes, late is recorded and can restart; no limit needs ≥10 s), START/FINISH_MUSIC_CORNER (≥80 % of `durationSeconds`, 30 s default), CHECK_IN_TIME_WINDOW (open-window check by the existing `requireWithinTimeWindow`). XP once, only when `scored`. Progress projection carries no judge inputs.
- `AdvancedGameRuntimeServiceImpl.java`: five new `case`s; playKit projection adds progress fields. `AdvancedActionJudgement.mechanicsReady`: one line, scored kits must be done. `AdvancedGameConfigValidator`: `scored` must be boolean, `xp` 0–1000. `AdvancedGamePublicProjection`: `scored`/`xp` public.
- `chengyinhub-admin/sql/migration_20261115_game_mechanics.sql` (new, idempotent, rollback note at top) + ledger entry in `ci/migration-dependencies.json` (`20261115_game_mechanics` → topic_route_graph, play_progress_points, location_trust): tables `treasure_hunt`, `treasure_hunt_item`, `treasure_hunt_find`, `treasure_hunt_progress`, `game_duel`, `topic_walk_test`, `topic_walk_test_waiver`; configs `creator.walktest.gate=ADVISE`, `creator.player.verified_unlock=false`; points rule id/event 41 寻宝完成 = 30; menus 寻宝管理 / 试走记录 with 4 perms (granted to no role). No `sys_job`, no `@Scheduled`.
- `chengyinhub-system/.../gamemech/`: `GameMechanicsMapper` (annotation SQL), `GeoDistance`, `TreasureHuntService`, `DuelService`, `WalkTestService`, `CreatorVerificationService` (all new).
  - Treasure: players never receive item coordinates (only a ~500 m rounded area); a find is scored by `LocationTrustService` (new kind `TREASURE_FIND`); REJECT → retryable refusal, withheld → `uncredited` answer, no find recorded; radius + min(accuracy, 50 m); unique find per item/member; completion pays `IPointsAwardService.award(member, 41, huntId)` once (governed by points governance when installed).
  - Duel: friend (`FriendService.relationship` state 1) or shared active club; one open duel per pair/journey; 20 per day; 7-day expiry computed on read; result = first `topic_route_session` completion after acceptance, time from max(acceptance, run start); peer time hidden until decided; notices through new `INotifyService.notifyGameDuel` (default method) → `NotifyEvent.GAME_DUEL` → in-app card.
  - Walk test: DESK record (author only, nodes checked ≤ enabled nodes); ONSITE needs a fix ≤300 m from an enabled node, scored with new kind `WALK_TEST_ONSITE`; only `ACCEPT` counts. Fails open (gate reads OFF) if config/tables are unreadable.
- `LocationTrustKind.java`: two kinds added. `NotifyEvent`, `INotifyService`, `NotifyServiceImpl`, `InAppNotifyChannel`: GAME_DUEL (small, local).
- `TopicPublicationService.publishDraftInternal`: optional `WalkTestService.assertPublishable` right after `assertPublishReady` (ordinary path). `TopicPublishCheckService.check`: `WALK_TEST_MISSING` as error (ENFORCE) or warn (ADVISE).
- `RoleServiceImpl.getPermission`: verified unlock (flag + capability); constants `CAP_VERIFIED_CREATOR`, `CONFIG_VERIFIED_UNLOCK`.
- `chengyinhub-admin/.../api/ApiGameMechanicsController.java` (new): `/api/treasure/list|{id}|{id}/find|creator/mine|creator/{id}|creator|creator/{id}/submit`, `/api/duel`, `/api/duel/{id}/accept|decline`, `/api/duel/mine`, `/api/duel/{id}`, `/api/creator/walk-test`, `/desk`, `/onsite`. Rate limits on writes. Storage errors answer "not available yet".
- `chengyinhub-admin/.../business/GameMechanicsAdminController.java` (new): `/business/treasureHunt/*`, `/business/walkTest/*`, `/business/creatorVerify/*` (`@PreAuthorize`, `@Log`).
- Admin console: `src/api/business/gameMechanics.js`, `src/views/business/treasurehunt/index.vue` (list, editor with item table, approve / send back with reason / end, AI-drafted tag), `src/views/business/walktest/index.vue` (mode banner, records, waive with note / unwaive). Existing Element/RuoYi components only.
- Mini-program: `pages/publish/temp/ai-node-assist.js` (new), `index.js` (+require, 4 data fields, 2 handlers), `index.wxml` (renders `cy-ai-assist` at the top of 玩法规则).
- Tests (new): `AmbientKitJudgementTest` (8), `gamemech/GameMechanicsH2Test` (5, runs the migration's CREATE TABLEs and every mapper statement on H2 MySQL mode through the real services), `gamemech/VerifiedCreatorUnlockTest` (2); UI `tests/unit/g2-game-mechanics-admin-contract.test.js` (3); mini-program `tests/unit/g2-ai-node-assist.test.js` (4).

No route or field was removed or renamed. `/api/betagame/*` and `/api/world/*` are unchanged.

## Verification (all Maven with `LANG=C.UTF-8 LC_ALL=C.UTF-8`)

```
mvn -B -q -DskipTests compile                                               → OK
mvn -B -q -pl chengyinhub-admin -am test -Dtest='*Notify*Test,*InApp*Test,TopicPublication*Test,TopicPublishCheck*Test,Role*Test,
  AdvancedGame*Test,*LocationTrust*Test,AmbientKitJudgementTest,GameMechanicsH2Test,VerifiedCreatorUnlockTest,PlayKit*Test,SlowTask*Test,TimeWindow*Test'
                                                                             → 894 run, 0 failures, 0 errors
bash ci/migration-safety-check.sh                                           → 迁移安全门禁:通过 (no rollback_*.sql added)
chengyinhub-ui: node --test tests/unit/g2-game-mechanics-admin-contract.test.js admin-plain-language-contract design-token-contract dead-page-contract → 12 pass
chengyinhub-xcx: node --test tests/unit/g2-ai-node-assist.test.js → 4 pass;
  first 40 test files touching pages/publish/temp/index: base 536 pass / 2 fail, branch 540 pass / 2 fail, same failing files (pre-existing)
```

## Not verified

- No MySQL: SQL ran on H2 (MySQL mode). `insert ignore`, `on duplicate key update … values()`, `now(3)` and `for update` should be re-checked on the test VPS MySQL.
- No Redis: rate limiters not exercised. No live AI call. No browser run of the two admin pages or of the mini-program editor.
- The full mini-program suite was not completed (a run of all 91 files touching the editor hung past 10 min); the 40-file subset above was compared base vs branch.

## Owner actions and open questions

1. Apply `migration_20261115_game_mechanics.sql` (test, then production). Grant the four new perms to the operator role(s) that should use them.
2. **Walk-test gate mode**: default ADVISE (shown, not blocking). Set `creator.walktest.gate=ENFORCE` to require both records before publishing. Should official/club topics be exempt? (Operators can waive per topic today.)
3. **GAM-12**: should verified player creators unlock branching and mechanics? Flip `creator.player.verified_unlock=true`, then mark creators verified. Who verifies, and on what evidence?
4. **GAM-11 remainder**: allow players to use the AI node assistant (`ApiAiController.gateRole`)? Not changed.
5. Treasure reward is 30 points (rule 41, editable in 积分规则). If points governance is installed, add a `points_award_cap` row for event 41 (not seeded here to avoid depending on that migration).
6. Duel rules (7 days, time from acceptance, faster wins) are code constants; say if they should become configuration. Duels never involve money or stakes.
7. `/api/world` has no authoring UI and no quest progress; build it only if the IP-world product is revived.
8. Coordinator: the app's G2 calls are admitted through `ReviewedProductionFormMutationRoute.acceptsPlayerWrite` (player writes on). Note that the merged city-coin, growth-loops and creator-earnings reads rely on the same composition transport; city-coin and growth-loops have no admission branch in `AppCompositionRoot` as far as I could see (worth a check).

No hard-coded secrets in the files touched.
