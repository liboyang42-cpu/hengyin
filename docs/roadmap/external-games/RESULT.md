# G3 external-games: backend and admin console receipt (hengyin `feature/r-external-games`)

Base: `origin/roadmap/be-integ` @ `7bdf604f1`, which already contains the coordinator's `feature/extgame-money` (`c71445b6b`): monetization, node binding by creators, play tickets, the result callback and traffic settlement. App side: chengyin-app `cloud/r-external-games` (its own `docs/roadmap/external-games/RESULT.md`).

Security line: `fix/security-r3` (`c7d6ab62b`, CRIT-S1/CRIT-S2/HIGH-S1/HIGH-S2) is **not** in this base; another agent merges it. This branch does not re-solve it and touches none of its hunks. Its rules are assumed: a result counts only when the game's own server signs it (per-game result key or platform Ed25519), and node completion needs an active run. A trial merge of `origin/fix/security-r3` into this branch has **no conflict in any file this branch changed**. The conflicts it reports (ApiRoamController, RewardDrop*, rollback renames, `ci/migration-dependencies.json`) are already there between `be-integ` and `fix/security-r3`.

## Findings

| ID | Status | How |
|---|---|---|
| GAM-15 / BE-08 (result callback) | **Server side already built** by extgame-money and hardened by security-r3; this branch adds the caps | Creator traffic points now go through `PointsGovernanceService.admit` (event_type 99, PLAY programme, 500/day). A refused day settles as `SKIPPED` with note `POINTS_GOVERNANCE_<outcome>` and writes nothing. XP and node completion keep the security-r3 caps (standalone XP: first pass per game per day, 30/day). |
| GAM-16 (node binding) | **Closed for operators** | Operators bind or unbind any listed game to any topic node in the console, with an audit row. The binding owner stays the game's creator. `GET /api/external-game/nodes/{id}/binding` now also returns `listed`. |
| GAM-18 (universal links) | **Closed in source** | AASA gains `{"/": "/game"}` (`/game?id=<gameId>[&node=<id>]`). The id is in the query because catalogue ids contain dots, which the app's link parser refuses in paths. The backend resource and the UI public copy stay byte-identical (`WellKnownControllerTest`). |
| FLOW-14 | **Closed (server part)** | `GET /api/external-game/catalog/{gameId}/availability` → `LISTED / TAKEN_DOWN / NOT_LISTED / UNKNOWN_GAME`, anonymous, rate-limited, never returns the operator's reason. The app uses it to explain closed games. Production switch values are unchanged. |
| FLOW-20 (console report and takedown) | **Closed** | Report queue with resolve / dismiss, takedown and restore, all audited; new permissions granted to no role. |

## What changed

- `chengyinhub-admin/sql/migration_20261115_external_games_ops.sql` (new, idempotent, rollback note at the top):
  - `external_game_takedown`: one row per game, ACTIVE/LIFTED, the version taken down, reason code and internal reason, who and when.
  - `external_game_report_resolution`: ACTIONED / DISMISSED, note, resolver.
  - `external_game_moderation_event`: append-only audit (TAKEDOWN, RESTORE, REPORT_ACTIONED, REPORT_DISMISSED, NODE_BIND, NODE_UNBIND) with the sys_user id.
  - `points_award_cap` row for event_type 99 (外部游戏流量激励, PLAY, 500/day), inserted only when missing.
- `chengyinhub-admin/sql/migration_20261115_external_games_ops_menus.sql` (new): page 外部游戏节点绑定 (`business/externalgame/bindings`) under 审核与风控, and buttons `business:externalgame:report`, `business:externalgame:takedown`, `business:externalgame:bind`. No `sys_role_menu` rows. Read-back guards abort on duplicates.
- `ci/migration-dependencies.json`: entries for both migrations (after `20261116_external_game_review`, `20261115_external_game_monetization`, `20261115_points_governance`, `20261121_admin_review_queue_menus`).
- `chengyinhub-system/.../externalgame/ExternalGameOpsService.java` (new):
  - `takedown`: locks the game; clears `external_game.approved_submission_id` (catalogue, signed download, binding view and `sessions/start` all refuse a game without an approved version); expires its STARTED play sessions; optionally resolves its open reports as ACTIONED; audits.
  - `restore`: puts back the newest APPROVED version; refuses with `NOTHING_TO_RESTORE` when none is left.
  - `resolveReport` (dismiss needs a note), `enrichReports`, `adminBind` / `adminUnbind`, `listBindings`, `moderationEvents`, `availability`.
- `ExternalGameReviewService.java`: approving a new version of a taken-down game no longer re-lists it; restore does. The check fails open only while the takedown table does not exist yet.
- `ExternalGameWalletBridge.java`: default method `creditPointsGoverned` + `PointsCredit` record. `SpringExternalGameWalletBridge.java`: optional `PointsGovernanceService` field (same pattern as `CommentPublicationService`), admit under the member row lock.
- `ExternalGameMonetizationService.java`: the settlement calls `creditPointsGoverned`; `nodeBindingView` adds `listed`.
- `chengyinhub-admin/.../controller/business/ExternalGameOpsAdminController.java` (new, `/business/external-game/ops`): `GET reports`, `POST reports/{id}/resolve`, `POST games/{gameId}/takedown`, `POST games/{gameId}/restore`, `GET bindings`, `PUT nodes/{id}/binding`, `POST nodes/{id}/unbind`, `GET events`. Writes have `@PreAuthorize` with the new permissions and `@Log`.
- `chengyinhub-admin/.../controller/api/ApiExternalGameAvailabilityController.java` (new).
- `chengyinhub-admin/src/main/resources/well-known/apple-app-site-association`, `chengyinhub-ui/public/.well-known/apple-app-site-association`: `/game`.
- Console: `chengyinhub-ui/src/api/business/externalGameOps.js` (new); `views/business/externalgame/reports.vue` (actions, resolution and paused state, dialogs with reason codes); `views/business/externalgame/bindings.vue` (new). Existing Element components only.
- Tests (new): `ExternalGameOpsServiceTest` (H2 MySQL mode, 8 cases: takedown side effects and audit, restore newest version, nothing to restore, validation, dismiss-needs-note and once-only, operator bind/unbind and paused game, availability states, fail-open only without the table), `ExternalGameTrafficPointsGovernanceTest` (refused writes nothing, admitted credits once, no governance bean behaves as before).

## Verification

```
cd /home/user/hengyin
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -DskipTests compile                                   → OK
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -pl chengyinhub-admin -am test \
  -Dtest='ExternalGame*,SpringExternalGame*,WellKnownControllerTest,PointsGovernanceMyBatisTest,PointsAwardGovernanceWiringTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
  → ExternalGameOpsServiceTest 8, ExternalGameTrafficPointsGovernanceTest 3, ExternalGameMonetizationServiceTest 2,
    ExternalGamePackageValidatorTest 9, WellKnownControllerTest 2, PointsGovernanceMyBatisTest 6,
    PointsAwardGovernanceWiringTest 2: all passed. The two *MysqlIntegrationTest classes skip without MySQL (as on base).
bash ci/migration-safety-check.sh   → 迁移安全门禁:通过
bash ci/migration-layout-check.sh   → 迁移布局门禁:通过
python3 -m pytest -q ci/test_ci_cost_contract.py ci/test_migration_gates.py ci/test_migration_ledger.py \
  ci/test_prelaunch_business_seeds.py ci/test_questo_collation_alignment.py ci/test_release_manifest.py \
  ci/test_release_migration_rehearsal.py
  → branch 5 failed / 246 passed; base be-integ the same 5 failed / 246 passed (same test ids; none of the
    failure messages names a file or table from this branch)
cd chengyinhub-ui && node --test "tests/unit/**/*.test.js"   → 547 pass / 30 fail on base and branch, identical failing set
                     node --test tests/e2e/contracts/*.test.js → 36 pass, 0 fail
git merge --no-commit origin/fix/security-r3 (trial, aborted) → no conflict in files changed here
```

No `rollback_*.sql` was added to `chengyinhub-admin/sql/`.

## Not verified

- The migrations on real MySQL (H2 covers the service SQL, not the DDL, CHECK constraints or the menu inserts).
- The console pages in a browser (no `node_modules`, no backend running).
- The combined behaviour with `fix/security-r3` merged (trial merge is clean; tests were run on this base only).

## Owner actions and open questions

1. Run both migrations through the ledger (read-only preflight first). Grant `business:externalgame:report`, `business:externalgame:takedown` and `business:externalgame:bind` to the operations role when wanted; no role has them now.
2. Host the updated AASA (`/game`) with the rest of the file (see links-push RESULT for the nginx options).
3. **Game result keys (security-r3):** a game counts for a place only when its own server signs the result. Each game server needs its per-game result key (`gameResultKey(chengyin.external-game.result-secret, gameId)`), handed out of band. Who provisions and rotates these keys, and is there a console action for it? Not built here.
4. **FLOW-14 question:** imported games stay closed on production as you set them (the app explains why). Do you want a server flag under a compiled ceiling, like the money switch, so you can open them later without a release?
5. Confirm the 500 points/day cap for creator traffic points (event_type 99) and whether a capped day should retry the next day (today it settles once as SKIPPED).
6. No hard-coded secrets found in the touched files.
