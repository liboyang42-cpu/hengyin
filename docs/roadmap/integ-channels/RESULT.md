# Integration: feature/ingame-channels → backend integration

Branch `feature/r-integ-channels` = `origin/roadmap/be-integ` (5db6d6e37) + `--no-ff` merge of
`origin/feature/ingame-channels` (280fcf241: in-game channels, CITY board authoring, merged roam territory).

## Conflicts and resolutions

| File | Resolution |
|---|---|
| `chengyinhub-system/.../mapper/business/ImConversationMemberMapper.xml` | Kept BE-17's keyset-paginated `selectMyConversationSummaryPage`. The derived table `m` now also selects `pc.remark as conv_remark`, and the outer select reads `m.conv_remark`. `ConversationSummaryResult` keeps both `lastMsgAt` and `remark`. The old statement was not brought back: `selectMyConversationSummaries` is already a Java `default` method on the mapper that calls the paginated statement (capped at `LEGACY_LIST_CAP`), and `ImServiceImpl.listConversations` and every test use it, so the service, the mapper interface and the tests already agree. The channels tests never call the old statement. `im_conversation.remark` exists in `migration_im.sql`. |
| `chengyinhub-system/.../service/impl/ImServiceImpl.java` | Both sides kept: the `IPushFanoutService pushFanout` field (links-push) and the `InGameChannelService` field. The channels branch's in-game branches in `counterpartyOf`, the read/send refusals and `hasActiveGroupAccess` merged automatically alongside the error-contract and hygiene changes. |
| `chengyinhub-admin/.../controller/api/ApiRoamController.java` | Both sides kept: the fog-cell, daily-loop, invite-reward and location-trust fields and helpers, plus the `RoamTerritoryService` field. The territory reward in `/checkin` runs only after the location-trust check (rejected or withheld fixes return first). |
| `ci/migration-dependencies.json` | Took the integration ledger, because the channels branch had reformatted the whole file and only added one key. Added the one new entry (see below). |

## Migration entries added

```
"20261123_sys_citygame": ["20261113_gaps_a_city_game", "20261114_gaps_a_city_r491", "20261121_admin_review_queue_menus"]
```
The channels branch adds only this one migration. All three dependencies are in the ledger. `bash ci/migration-safety-check.sh` ends with **迁移安全门禁:通过**, and `bash ci/migration-layout-check.sh` passes with **迁移布局门禁:通过**.

## Integration rules applied

- **Rollback SQL**: the branch adds no `rollback_*.sql`. Its rollback statements are SQL comments at the top of the migration, which was left unchanged. Note: the migration's column comment still reads `'POINTS only'` and the default is `'POINTS'`. The code now always writes `'CITY_COIN'` (see below), and `varchar(16)` holds it.
- **E2E providers**: the branch adds none.
- **sys_job / @Scheduled**: the branch adds none.
- **CITY territory yield**: the branch credited the territory owner in **member points** directly (`CityGameMapper.creditMemberPoints` / `insertPointsEarnDetail`, bypassing points governance). That contradicts MKT-12 / MON-10. It is now routed through `CityCoinService.creditHolderAfterCommit(ownerId, "terr:" + territoryId, ref)` in `RoamTerritoryService.creditOwnerYield`, with refs `TERR_REV:<requestId>` / `EXT_REV:<sourceKey>`, hashed if longer than 80 characters. The two direct points-credit mapper statements were removed. `revenueMode` is `CITY_COIN`. `owner_revenue_points` / `revenue_total_points` remain a yield **score** for the leaderboard; the coin amount comes from `city.coin.*` config. `RoamTerritoryService` was added to `CityCoinNoConversionGuardTest`'s allowlist with a review note. The in-game channel text changed from "过路收益 +N 积分" to "领主获得城市币收益".
- **CITY events**: on a member takeover in `RoamTerritoryService.claim`, the former holder now gets `ICityEventNotifyService.lost(...)`, which pushes after commit. Board-level team events (claim, takeover and finish in `CityGameActionService`) are in-game **channel messages** with team holders, not member pushes. They were left as channel posts; wiring member pushes there is still coordinator work.
- **Privacy erasure**: `MemberPrivacyErasureService` now handles the new tables:
  - The member's own `roam_territory_checkin` rows are deleted.
  - Other players' check-ins at the member's territories are kept, with the owner unlinked (`owner_member_id = 0`).
  - Territories the member held are released: `REVOKED`, owner 0, claim photo dropped, and `claim_request_id` set to `territory_id`.
  - `roam_territory_audit` rows are kept but unlinked (`member_id` 0), with coordinates and detail removed.
  - `city_game_board_audit` PLAYER rows are unlinked, and claim detail (which holds the photo URL) is dropped. ADMIN rows are not touched.
  - Covered in `MemberPrivacyErasureServiceTest`, including the idempotency test.
- `SysCityGameMysqlIntegrationTest` (env-gated) was updated to assert the city coin yield and that P1's points are unchanged.

### Open product points for the owner

- The visitor's **points toll** (`checkin_toll_points`, default 5) is still charged, but it no longer goes to the owner. It is now a points sink. Decide whether to keep it, zero it, or drop it.

## Verification

`LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -DskipTests install`: passed.

Test set `*Im*,*Channel*,*Club*,*Roam*,*City*,*Erasure*,*ErrorContract*,SysJobStaggerPlanTest,TwoNodeSchedulingTest,CityCoinNoConversionGuardTest`
(`-Dmaven.test.failure.ignore=true -DargLine="-Xmx6g -Dspring.test.context.cache.maxSize=4"`):

| | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|
| base `origin/roadmap/be-integ` | 4992 | 4 | 43 | 230 |
| this branch | 5000 | 4 | 44 | 231 |

Comparing the failing test cases, the branch has exactly one more failure: `InGameChannelMySqlIT`. It is the channels branch's own new MySQL IT and fails on `Missing env CHENGYIN_MYBATIS_IT_PORT` because there is no MySQL in this environment. Every other failure also fails on base:

- MySQL ITs: missing MySQL env or bindings, `Communications link failure`, or a Spring context that needs MySQL.
- `ErrorContractCompatibilityTest`: 2 failures, an `errorKind` key in legacy bodies.
- `SysJobStaggerPlanTest`: `clubChannelDefaultsTask`, `growthWinbackTask`.
- `TwoNodeSchedulingTest`: `MerchantVisitBillingJob`, `ExternalGameTrafficSettlementJob`, `CreatorEarningsJob`.

These pre-existing failures are not introduced here and are left for their owners.

Passing on the branch: `InGameChannelServiceTest` 4, `CityBoardAndTerritoryUnitTest` 2, `MemberPrivacyErasureServiceTest` 4, `CityCoinNoConversionGuardTest` 4, `ImServiceImplTest` 53, `ImConversationPageTest` 5, `ImConversationBatchReadTest` 9.
