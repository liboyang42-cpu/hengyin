# P3 fog-map: backend receipt (hengyin `feature/r-fog-map`)

Base: `fix/r5-be-infra-stubs`. App side: chengyin-app `cloud/r-fog-map` (see its `docs/roadmap/fog-map/RESULT.md`).

## Findings

| ID | State | Note |
|---|---|---|
| BE-07 (P1) | Closed | Revealed cells are kept per member in `roam_member_fog_cell` and read by bounding box (`GET /api/roam/fog/cells`) without the 30-day session window. `/api/roam/tiles` also reads the kept cells while keeping is on. |
| BE-07, `/api/roam/tiles/page` | Left as is, on purpose | Its cursor is `roam_fog_tile.id`. Moving it to another table would make saved cursors (mini-program, app ledger) skip or repeat cells. It keeps the 30-day window; the bounding-box route is the kept read. |

## What changed

- `chengyinhub-admin/sql/migration_20261115_fog-map.sql`: new table `roam_member_fog_cell` (member, geohash-7 cell, `cell_prefix5`, first and latest reveal time; PK member+cell, index member+prefix, index latest time). Idempotent backfill from `roam_fog_tile` rows that still hold a cell (older ones were already cleared to NULL by the 30-day job). Rollback note at the top. Nothing existing is altered.
- `chengyinhub-system/.../support/RoamGeohashCover.java`: geohash encode/decode, box validation, precision-5 prefix cover of a box (refuses more than 120 prefixes, never truncates).
- `chengyinhub-system/.../mapper/RoamFogCellMapper.java` + `mapper/business/RoamFogCellMapper.xml`: upsert (keeps first time, moves latest time forward), kept read by prefixes, 30-day read by prefixes (same window as the legacy readers), kept list for `/tiles`, delete-not-revealed-since (used only while keeping is off).
- `chengyinhub-system/.../service/impl/RoamFogCellService.java`: owns `roam.fog.retainRevealedCells` (default true). Record, box read (exact box filter after the prefix lookup, max 20 000 cells, `truncated` flag), legacy list, purge.
- `ApiRoamController`:
  - `POST /api/roam/reveal`: after the existing consent check and tile insert, in the same transaction, records the batch in the kept table (no session id, no order).
  - `GET /api/roam/fog/cells?south&west&north&east`: owner-only (`uid()`), validates the box, returns `{cells, retained, truncated}`; "范围过大" for boxes over the cap.
  - `GET /api/roam/tiles`: kept cells (latest first) while keeping is on; the old 30-day read otherwise.
  - New field is `@Autowired(required = false)`; existing slice tests that build the controller by hand keep working.
- `DataRetentionServiceImpl.cleanRoamLocations`: while keeping is off, also deletes kept cells not revealed again within 30 days (same 30-day cutoff the job already uses).
- `application.yml`: `roam.fog.retainRevealedCells: ${CHENGYIN_ROAM_FOG_RETAIN_REVEALED_CELLS:true}` with a note.
- Tests: `RoamFogCellServiceTest` (11), `RoamFogCellsRouteTest` (3).

Switch off (`false`) restores today's behaviour: nothing new is kept, every read falls back to the 30-day session window, and the retention job removes kept cells not revealed again within 30 days. Raw tracks and `roam_fog_tile` keep their 30-day retention in both states.

Consent: the existing `roam_location` AGREE check guards every write (unchanged). The app's existing first-GO consent card now carries the "kept" sentence; no new screen.

## Verification

```
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -DskipTests compile                      # OK
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -pl chengyinhub-admin -am test \
  -Dtest='RoamFogCellServiceTest,RoamFogCellsRouteTest,RoamTileMemoryPageTest,RoamRevealFinishBoundsTest,RoamSessionResumeAcceptanceTest,DataRetention*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false
# RoamFogCellServiceTest 11/11, RoamFogCellsRouteTest 3/3, RoamTileMemoryPageTest 4/4,
# RoamRevealFinishBoundsTest 4/4, RoamSessionResumeAcceptanceTest 1/1,
# DataRetentionServiceImplTest 11/11, DataRetentionCleanupTaskFailureTest 4/4 (its ERROR log line is expected)
# RoamFogCellServiceTest is 12/12 after the "failed kept write never fails the reveal" test was added.

LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -pl chengyinhub-admin -am test        # whole admin suite
# 12 622 tests, 1 failure, 34 errors in 15 classes, all outside this package:
# 14 *MyBatisTest classes under business.it (need a database) and
# ChapterFlowWriteServiceTest.openBundleReviewCannotBeBypassedBySwitchingToSimpleMode.
# Checked on the untouched base fix/r5-be-infra-stubs: ChapterFlowWriteServiceTest (1 failure) and
# PublicVisibilityMyBatisTest (5 errors) fail there the same way.
```

## Not verified

- The migration and the mapper SQL against a real MySQL (no database here). The SQL uses `INSERT ... ON DUPLICATE KEY UPDATE ... VALUES()` (MySQL 8 accepts it with a deprecation warning) and `DELETE ... ORDER BY ... LIMIT`.
- `RoamLocationRetentionMySqlIT` was not run (needs MySQL).

## Owner actions and open questions

1. Legal: confirm that kept revealed cells are covered by the privacy policy. If the policy text changes, bump `compliance.doc-versions.privacy_policy`; the existing consent check then asks again. Until confirmed, `CHENGYIN_ROAM_FOG_RETAIN_REVEALED_CELLS=false` restores the 30-day behaviour.
2. The backfill copies the last 30 days of cells that were revealed under the earlier consent text. If legal says those need the new consent first, skip the `INSERT` block of the migration (the table alone is safe).
3. Withdrawing roam location consent stops new writes (unchanged) but does not delete kept cells; account deletion does not touch roam tables today (it did not before either). Decide whether withdrawal or deletion should clear `roam_member_fog_cell`.
4. Run the migration on each environment before deploying. Without it the kept-cell write logs an error and is skipped (the reveal still succeeds), and the box read returns an error until the table exists.
