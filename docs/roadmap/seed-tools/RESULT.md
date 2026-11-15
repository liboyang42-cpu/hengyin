# Q3 seed-tools — RESULT

Branch: `feature/r-seed-tools` (hengyin), based on `fix/r5-be-infra-stubs`. No chengyin-app changes.

Goal: operations can fill one launch district with about 260 playable things in days. Everything this package
writes is a **draft** that still goes through the existing review; nothing here publishes content or touches money.

## Findings

| ID | State | Notes |
|---|---|---|
| GAM-09 (P0) no bulk seeding | **Closed** | CSV/XLSX bulk import for official places (preview with per-row errors, dry run, one transaction, dedupe by name + distance, export in the same format, template with two example rows) and for routes (ordered place ids, title, difficulty). |
| GAM-11 (P1) AI node generation has no caller | **Partly closed** | The admin console now calls node generation (`POST /business/seed/ai-node/{placeId}`, same `IAiCreatorService.nodeGenerate` as `/api/ai/node/generate`) from the official place editor: pick a place → generate → edit → "保存并提交审核" (draft for review). Left open: the app/mini-program player role is still refused by `ApiAiController.gateRole` and template fill stays app-only — changing who may use AI in the player apps is an owner decision, not a seeding tool. |
| POS-09 (P0) content density cannot be shown | **Closed** | District density report in the admin console for a CITY board area, a box or a drawn polygon. |

## What changed, file by file

Backend
- `chengyinhub-admin/sql/migration_20261115_seed_tools.sql` — idempotent guarded `ALTER`s adding nullable `roam_poi.category` (40) and `roam_poi.opening_hours` (120). Rollback note at the top.
- `chengyinhub-system/.../mapper/SeedContentMapper.java` + `resources/mapper/business/SeedContentMapper.xml` — admin-only reads/writes: places in a box, insert imported place, export, places by id, existing route titles, route draft + nodes, density reads (route nodes, live shops, CITY board points, daily missions, opening events, board areas). `RoamMapper.xml` is untouched (its no-`correct_answer` contract stays intact).
- `.../service/SeedTableReader.java` — UTF-8 CSV (BOM, RFC 4180 quotes/newlines; rejects non-UTF-8) or first XLSX sheet; 5 MB / 2000-row caps; export cells quoted and formula-guarded, guard undone on re-import.
- `.../service/SeedPlaceImportService.java` — columns `name, category, lat, lng, address, cover_image_url, tags, opening_hours, description, city_code` (Chinese header aliases accepted). Validates every row (required fields, lengths, numeric GCJ-02 coords inside China, swapped lat/lng, city code from row or form). Dedupe: same normalised name within 10–1000 m (default 100) of an existing place or an earlier row → skipped; a different name within 15 m → warning. Cover must be a platform-uploaded image (existing `OfficialRoamPoiService.asset` rule); otherwise the row imports without a cover and says so. Commit requires an authorised official operator, refuses if any row has an error, writes all new rows in one transaction. Imported rows: `type=1, status=0, audit_status=3, application_type=3` — hidden, outside the pending queue, and blocked from the plain 上架 switch (`updatePoiStatus` only allows audit 1 / null). Template and export share the format.
- `.../service/SeedRouteImportService.java` — columns `title, difficulty (轻松/标准/硬核 or easy/standard/hard), place_ids (ordered; ; , | → separators), description`. 2–30 stops, ids must be existing official places, no repeats; warnings for stops without a game or not yet live; duplicate titles (existing official routes or earlier rows) skipped. Commit writes `cms_topic` as a draft (`topic_type=1, product_type=1, status=0, user_status=0, lifecycle=0, is_draft=1`), difficulty through the existing `JourneyRulesValidator`/`journeyRules.difficulty`, mileage from stop distances, and ordered `cms_topic_node` rows copying each place's name/address/coords/game template.
- `.../service/DistrictDensityService.java` + `domain/dto/seed/*` — counts inside the area against the target: live official places (200), playable nodes (260: live places with a game + game nodes on live routes), live routes (5), live shops (30) and shops with a live game (30), CITY board places (20–30, boards not closed/archived); city-wide daily missions (3) and opening events (1, optional city keyword). Box sides ≤ 0.5°. A source that cannot be read (for example a table not migrated yet) is reported as "读不到", never as 0.
- `chengyinhub-admin/.../controller/business/SeedContentController.java` — `/business/seed/*`: templates, export, place/route import (`dryRun` defaults to true), density areas/report, AI node draft. Permissions reuse `business:roamPoi:list` / `business:roamPoi:edit`; imports are `@Log`ged; AI draft is rate-limited and runs as the authorised official operator member (its AI quota), official places only, honest error when the AI returns nothing usable.

Admin console (`chengyinhub-ui`)
- `src/api/business/seedContent.js` — API module.
- `src/views/business/roampoi/seed-import-dialog.vue` — file pick → 预检 → preview table (row, outcome, errors, duplicates, warnings) → 确认导入 (enabled only for an error-free preview of the same file with new rows) → result with created ids. Template download.
- `src/views/business/roampoi/density-report-dialog.vue` — area by CITY board, box or polygon text; metrics table with target, state and detail.
- `src/views/business/roampoi/official-editor.vue` — "按地点生成题目草稿" button for a saved place; fills title/description/question/options/answer, asks before overwriting, never saves by itself.
- `src/views/business/roampoi/index.vue` — header buttons 批量导入地点 / 批量导入路线 / 导出官方地点 / 片区密度; list marks imported drafts "导入草稿，待配置游戏". Existing components and `--cy-*` tokens only; no restyling.
- Tests: `tests/unit/seed-tools-dialogs.test.js` (new); `official-poi-editor.test.js` and `roam-poi-migration-contract.test.js` stub the new imports (no assertion relaxed) and the editor test adds two AI-draft cases.

Ops workflow this enables: import ~200 places (preview, fix, commit) → for each, 配置地点游戏 → AI 起草 → edit → 保存并提交审核 → 通过 → import 5 routes over approved places → finish them in the topic editor → check 片区密度.

## Verification

```
cd /home/user/hengyin
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -DskipTests compile                      # OK
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -pl chengyinhub-admin -am test \
  -Dtest='SeedTableReaderTest,SeedPlaceImportServiceTest,SeedRouteImportServiceTest,DistrictDensityServiceTest,SeedContentMapperH2Test,SeedContentControllerTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
#   SeedTableReaderTest 5/5, SeedPlaceImportServiceTest 8/8, SeedRouteImportServiceTest 4/4,
#   DistrictDensityServiceTest 5/5, SeedContentMapperH2Test 2/2 (runs the new SQL on H2 MySQL mode),
#   SeedContentControllerTest 3/3 — 27 tests, 0 failures
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -pl chengyinhub-admin -am test -Dtest='NodePublicVisibilityPredicateContractTest,DruidWallForUpdateOfContractTest,DruidWallSqlCommentContractTest,DeadlockRetryEntryPointContractTest,CombinedContextConfigurationTest,OrganizerActorMutationWiringTest,BusinessAuditDomainTypeLengthTest,RoamPoiApplicationMigrationContractTest,ConsoleReadonlySqlTest,OfficialRoamPoiServiceTest,CreatorIncomeDetailWriteGuardTest' -Dsurefire.failIfNoSpecifiedTests=false
#   all pass (existing contracts that scan mappers/controllers/SQL)

cd chengyinhub-ui
node --test tests/unit/official-poi-editor.test.js tests/unit/seed-tools-dialogs.test.js tests/unit/roam-poi-migration-contract.test.js \
  tests/unit/admin-plain-language-contract.test.js tests/unit/design-token-contract.test.js tests/unit/dead-page-contract.test.js tests/unit/download-plugin-contract.test.js
#   28 pass, 0 fail
node --test tests/unit/*.test.js
#   30 failing entries, identical set with and without this package (compared via git stash);
#   they need node_modules (vue-template-compiler, vue-router) or belong to other pages.
```

Admin console build: **not run**. `npm ci --ignore-scripts` in `chengyinhub-ui` stalled for over 35 minutes in this environment (only 926 of the packages arrived, with no `vue` or `vue-cli-service`), so I stopped it and removed the partial `node_modules`. The SFC template-compile contract (`admin-sfc-template-compile-contract.test.js`) and `npm run build:prod` therefore still need a run on a machine that has the console dependencies.

## Not verified

- No MySQL: the SQL ran on H2 (MySQL mode) with the touched columns only; `regexp`, `cast`, `exists` and the board joins should be re-checked on the test VPS MySQL.
- No live AI call: the AI draft path is tested with a mocked `IAiCreatorService`; quota and model behaviour for the official operator member are as in production code.
- No browser run of the dialogs (unit-level only), and no console build or SFC template compile (see above).
- XLSX import was tested with a POI-generated workbook, not with files saved by Excel/WPS/Numbers.

## Owner actions and open questions

1. Run `migration_20261115_seed_tools.sql` on test, then production (two nullable columns).
2. The account doing imports and AI drafts must be an authorised official operator (`official_event_operator_authorization`), the same rule as the existing official place editor.
3. GAM-11 remainder: should players (not only club/merchant) get AI node generation / template fill in the apps? Not changed here.
4. Targets in the density report are the brief's launch numbers, hard-coded in `DistrictDensityService`; say if they should become configuration.
5. Daily missions and opening events have no geography in the schema, so they are counted city-wide (events filtered by an optional city keyword).
6. Cover images in import files must already be platform uploads; external photo URLs are not imported (they would be blanked by media review). Bulk photo upload is not part of this package.
7. Board editing is not built here (coordinator's `feature/ingame-channels`). The density report reads `city_game_point`/`city_game_board`, which exist in this base via `migration_20261113_gaps_a_city_game.sql`; if a later board migration changes those tables the report will show "读不到" rather than wrong numbers.
8. Imported drafts use `audit_status=3` with `application_type=3`. The review queue page asks for `audit_status=0`, so they stay out of it; a caller listing applications without a status filter would show them as "处理中".

No hard-coded secrets were found in the files touched.
