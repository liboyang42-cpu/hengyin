# integ-security-r3 (M3): merge `fix/security-r3` into the roadmap backend base

- Branch: `feature/r-integ-security-r3`, based on `0ea98d97e` exactly (roadmap/be-integ at brief time).
- Merged: `origin/fix/security-r3` at `de7d43c70`. That is newer than `c67a89521`, because it adds `ec0425533` (HIGH-A1/A2, MED-A1 admin fixes) and `de7d43c70` (MED-S4/MED-S8).
- Commits on the branch:
  1. `3d0f75fd6`: merge with the conflict resolutions, plus the reward-drop permission union.
  2. `abbf2885e`: security carry-over, in-game channel push.
  3. `4120b73fb`: integration fixes found by the test run.
  4. This receipt (draft, then final).
- `feature/r-integ-master` (M2) was not touched.

## 1. Conflicts and resolutions

| File | Resolution |
|---|---|
| `chengyinhub-admin/sql/rollback_20261114_weather_event_rule.sql` (rename/delete) | Kept the coordinator's `bak_weather_event_rule_20261114_rollback.sql`. Also kept the roadmap's `docs/delivery/sys-weather-20261114/ROLLBACK.md`. |
| `rollback_20261115_external_game_monetization.sql` (rename/rename) | Kept `bak_external_game_monetization_20261115_rollback.sql` with the coordinator's SQL content. Git had put the Markdown text into it, so I restored it from `fix/security-r3`. Kept `docs/delivery/external-game-monetization-20261115/ROLLBACK.md` from `0ea98d97e`. Deleted the old `rollback_` file. |
| `ApiRoamController` `/checkin` `@RateLimiter` | Took the security value: 20 per 60 s, MEMBER. |
| `PlayNodeQueryApplication` (two optional fields) | The two sides are equivalent, so I kept the roadmap order. |
| `ChapterFlowWriteServiceTest` `assertThrows` | The two sides are equivalent, so I kept the roadmap form with its comment. |
| `RoamTerritoryService` | Kept both sides: the roadmap `CityCoinService` yield, `ICityEventNotifyService`, `REVENUE_MODE` and setters, and the coordinator `CityMonopolyService monopoly` with its setter and uses. |
| `DataRetentionServiceImpl` | Union. The roadmap fog-cell purge (when retention is switched off) and location-trust cleanup run first, then the coordinator territory check-in, audit and claim-evidence coordinate retention. |
| `RewardDropRuleMapper.java` / `.xml` | Union of both statement sets. Roadmap: `selectAdminList`, `selectById`, `insertRule`, `updateRule`, `updateStatus`. Coordinator: `selectRewardDropRuleList`, `selectRewardDropRuleById`, `insertRewardDropRule`, `updateRewardDropRule`, `deleteRewardDropRuleById`. No ids clash. |
| `chengyinhub-ui/src/api/business/rewardDrop.js`, `views/business/rewarddrop/index.vue` | One page; see section 2. |
| `ci/migration-dependencies.json` | Union, edited as text with one line per migration. The duplicate `20261123_sys_citygame` line that the union produced was removed. `20261115_external_game_monetization_menus` now lists both sides' dependencies: `20261115_external_game_monetization`, `20261012_missing_business_menu`, `web_admin_nine_domains_20260719`. Added `20261115_sys_ainpc`, `20261123_sys_random`, `20261125_admin_r3_perms`, `20261124_city_numbers_attr` and the new `20261126_reward_drop_perm_union`. The four duplicate keys that predate this merge, present on both parents, were left as they are. |

## 2. Reward-drop decision

- **Backends: both kept.**
  - Roadmap R10 `RewardDropRuleAdminController` (`/business/rewardDropRule`, `RewardDropRuleAdminService`, audited, new rules start disabled).
  - Coordinator `RewardDropAdminController` (`/business/reward-drop`, `RewardDropRuleServiceImpl`, base probability, preview, audit list).
  - Routes and bean names do not clash.
- **One page.** `views/business/rewarddrop/index.vue` and `api/business/rewardDrop.js` are the coordinator's, plus two roadmap features that used the existing roadmap endpoints:
  - A per-row **启用/停用** button (with a confirm step) calls `PUT /business/rewardDropRule/{id}/status/{s}`. It is guarded by `business:rewardDrop:edit`. On enable, the roadmap service re-validates the rule, so a bad old row cannot be switched on directly.
  - A **检查规则** button in the editor dialog calls `POST /business/rewardDropRule/validate`. It is guarded by `business:rewardDrop:list` and shows the normalized parameters or the reason the rule is rejected.
- **Permissions.**
  - `sys_menu.perms` is `utf8mb4_general_ci`, so it ignores case, while `@ss.hasPermi` compares exact Java strings.
  - Because of that, `admin_consoles` (`business:rewardDrop:*`) and `admin_r3_perms` (`business:rewarddrop:*`) each skip the other family's rows in their `NOT EXISTS` checks. In ledger order, non-super-admin roles would end up with only part of the permissions.
  - Fixes:
    - New `migration_20261126_reward_drop_perm_union.sql` adds the missing F rows under the single page row, compared with `COLLATE utf8mb4_bin`. It grants the page and all F rows to `role_id = 1` and to every role that already holds the page, and ends with a readback assertion. It only adds rows and is idempotent. The rollback is in the file header.
    - `migration_20261115_admin_consoles.sql` now skips creating its page row if `component = 'business/rewarddrop/index'` already exists, and finds the page by perms or component.
  - Ledger order, computed with `ci/migration_ledger.py`: admin_consoles (#113) → sys_random (#648) → admin_r3_perms (#649) → perm_union (#650).
  - I modelled this in SQLite with a case-insensitive `perms` column, in four orders: fresh ledger order; a role granted the page before r3; TEST order (sys_random and r3 already applied, roadmap later); and sys_random first, the rest later. In all four there is exactly one page row and role 1 holds all 8 permissions the page's endpoints check. A role that held the page before the union migration also gets all 8. The script is not committed. I did not run it against real MySQL: no server is available, and installing MariaDB was refused in this environment.
- **Tests.** `chengyinhub-ui/tests/unit/sys-random-admin-r3-security.test.js` (HIGH-A2) required every permission on the page to start with `business:rewarddrop:`. I updated it:
  - The roadmap `business:rewardDrop:*` permissions are allowed on that page only.
  - Every permission on the page and in both controllers must be registered exactly by the union migration.
  - Every `business:rewardDrop:*` permission used on the page must be one that `RewardDropRuleAdminController` checks.
  - No roadmap test pinned the replaced page.

## 3. Gates

`export LANG=C.UTF-8 LC_ALL=C.UTF-8`, on the branch head:

- `bash ci/migration-safety-check.sh` printed **迁移安全门禁:通过**. Batch dependency gate: 64 new migrations are all covered by the ledger, and the dependency declarations relative to master only grew.
- `bash ci/migration-layout-check.sh` printed **迁移布局门禁:通过** (720 `migration_*.sql` files at the first level; prefixes valid).

chengyinhub-ui unit tests: `node --test tests/unit/*.test.js`. `node_modules` is not installed, so `vue@2.7.16`, `vue-template-compiler@2.7.16` and `vue-router` were installed in a scratch folder and symlinked into each tree.

| Tree | Tests | Pass | Fail |
|---|---|---|---|
| `0ea98d97e` | 638 | 612 | 26 |
| `origin/fix/security-r3` | 627 | 601 | 26 |
| merge | 642 | 616 | 26 |

The 26 failures have the same test names on all three trees: environment-dependent tests that need the full UI dependency set. No test fails only on the merge.

## 4. Backend suite, three trees

Each tree ran in its own worktree:

1. `mvn -B -q -DskipTests install`
2. `mvn -B -pl chengyinhub-admin -am test -Dmaven.test.failure.ignore=true "-DargLine=-Xmx6g -Dspring.test.context.cache.maxSize=4"`

The suites ran one after another; 16 GB RAM was not enough to run them in parallel.

| Tree | Maven totals (tests / failures / errors / skipped) | Excluding `*MyBatisTest` classes (tests / failures / errors) |
|---|---|---|
| merge `3d0f75fd6` | 20442 / 2 / 34 / 713 | 20184 / **2** / 0 |
| `0ea98d97e` | 20281 / 0 / 34 / 713 | 20024 / 0 / 0 |
| `origin/fix/security-r3` `de7d43c70` | 19851 / 0 / 34 / 713 | 19613 / 0 / 0 |
| final head `4120b73fb` (after fixes) | 20444 / 0 / 34 / 713 | 20186 / **0** / 0 |

- **Errors.** The 34 errors are the same 14 `*MyBatisTest` classes on all three trees (`Communications link failure`, no MySQL). No other class has an error on any tree.
- **Failing only on the merge.** Both failed on the merge and passed on both parents, so both are integration defects:
  1. `CityCoinNoConversionGuardTest#onlyReviewedFilesMentionCityCoin`
  2. `E2eTestProvidersProfileTest#productionModulesCarryNoE2eProviders`
- **Ratchets passing on the merge:**
  - `SysJobStaggerPlanTest`: the coordinator added no new `sys_job` rows that conflict.
  - `TwoNodeSchedulingTest`.
  - The coordinator security tests: `InGameChannelSecurityR3Test`, `RoamCheckinChannelEventSecurityTest`, `CityDiceReceiptTest`, `AccountDeregistrationR3ErasureTest`, `CityLocationConsentGateTest`, `CityR3RateLimitContractTest`, `RoamTerritoryClaimTargetTest`, `ExternalGameSecurityR3Test`, `SpringExternalGameNodeBridgeSecurityTest`.
  - The roadmap erasure and privacy tests.

### 4a. Final head

The same commands ran on `4120b73fb` (all code fixes; only this receipt follows) in its own worktree.

- Totals: 20444 tests, 0 failures, 34 errors, 713 skipped.
- The errors are the same 14 `*MyBatisTest` classes as on both parents.
- Excluding those classes: 0 failures and 0 errors.
- **No test fails on the integration branch that passes on both parents.**
- The two new `PushFanoutServiceTest` cases account for the +2 tests.
- After the fixes, both gates still pass and the UI tests still show 642 tests, 616 passing and the same 26 failures.

## 5. Integration fixes

| Change | File(s) | Reason |
|---|---|---|
| Restored `creditMemberPoints` / `insertPointsEarnDetail` (statements identical to the coordinator's) | `chengyinhub-system/.../mapper/CityGameMapper.java`, `mapper/business/CityGameMapper.xml` | The merge did not compile. M1 removed these when it moved the classic territory yield to the city coin. The coordinator's `CityMonopolyService.applyCredit` still uses them for its own 积分 economy, which is booked in `city_points_flow` and season debt. The comment says they are for Monopoly only. See the open question in section 7. |
| Comment wording that does not name the city coin service | same two files | Restoring the statements with a comment that named the city coin made `CityCoinNoConversionGuardTest` flag `CityGameMapper` as an unreviewed city-coin file. The wording changed; the allowlist did not. |
| `E2eTestNpcDialogueResponder` moved to `chengyinhub-e2e` (same package); new interface `chengyinhub-system/.../service/NpcDialogueTestResponder.java`; `NpcDialogueServiceImpl` injects the interface (still optional) | `chengyinhub-e2e/...`, `chengyinhub-system/...` | Roadmap R9 BE-16 keeps E2E providers out of production modules, and `E2eTestProvidersProfileTest` enforces it. The coordinator's sys-ainpc had added the class to `chengyinhub-system`. The profile and property gates are unchanged. TEST builds that need it must use `-Pe2e-test-providers`, like the other providers. |
| Reward-drop permission union migration, admin_consoles page guard, ledger entry | `chengyinhub-admin/sql/migration_20261126_reward_drop_perm_union.sql`, `migration_20261115_admin_consoles.sql`, `ci/migration-dependencies.json` | See section 2. |
| In-game channel chat push needs live access | `chengyinhub-system/.../push/PushFanoutService.java`, test `PushFanoutServiceTest` (+2 cases) | See section 6. |

## 6. Security carry-over

| Coordinator check | Roadmap code on `0ea98d97e` | Result |
|---|---|---|
| HIGH-S3, MED-S6, HIGH-S9 in `InGameChannelService` (paid registration or owner gate, non-member summary, masked roam events, presence opt-in, no real-name fallback) | M1 merged the same `feature/ingame-channels`. After the merge, `InGameChannelService` is byte-identical to `fix/security-r3`. | Covered |
| The same in-game access rule (`ImServiceImpl` → `InGameChannelService.hasActiveAccess`) on every read and send | **Roadmap APNs chat fan-out** (`PushFanoutService.fanOutChat`). Recipients came from `im_conversation_member` rows only. For in-game channels that bypassed `hasActiveAccess`: a member who left a CITY team still had a row and still got message previews. | **Fixed:** in-game channel recipients must pass `hasActiveAccess`, failing closed when the service is missing or throws. Tests: `inGameChannelPushesOnlyToMembersWithLiveAccess`, `inGameChannelIsNotPushedWhenAccessCannotBeChecked`. |
| HIGH-S9, no real names in channels | The push sender name uses `ums_member.nickname` only. Nicknames added elsewhere by the roadmap appear only in the owner's own lists (invites) or in admin screens. | Covered |
| HIGH-S4, roam check-in feed (server node title, one event per day, rate limit) | The roadmap adds no new `postRoamEvent` call. Its `ApiRoamController` additions (fog cells, location trust and others) do not post to channels. | Covered |
| Club channels (roadmap club-reach `ensureClubDefaults`, backfill) and channel moderation admin | They go through `ChannelGuildAccess` club membership, platform channel `requireRead` (push included), and admin permissions. | No bypass found |
| Paginated conversation list (`/api/im/conversations/page`, roadmap R9 BE-17) | It uses the same `projectConversations` guards as the existing list. | Covered |
| HIGH-S6 erasure | `executeOne` runs the roadmap `MemberPrivacyErasureService.erase` (fog cells, tracks, location trust, install link, push devices, territory unlink) and then the coordinator's `eraseR3PersonalData` (r3 tables). Both are in the merged path, and the overlapping steps are safe to run twice. | Covered (the erasure tests from both sides pass) |
| HIGH-S7 retention | Union in `DataRetentionServiceImpl` (section 1). | Covered |

## 7. Left for the owner or the coordinator

1. **Territory economy conflict.** The owner decision says "territory yield is a non-cashable city coin". Two paths now disagree:
   - The classic territory path pays in city coin.
   - The coordinator's City Monopoly, which runs when `city.monopoly.enabled` is set (seeded `true` by `migration_20261124_city_numbers_attr.sql`, already on TEST), debits and credits **member points** and returns `revenueMode=POINTS`.
   I kept both, because the build needed it. The coordinator or owner should decide whether Monopoly income must move to the city coin.
2. MySQL was not available, so the SQL was checked by the gates and the SQLite model only. A readback on TEST after `migration_20261126_reward_drop_perm_union.sql` should show one row for `business/rewarddrop/index` and the 8 permissions held by role 1.
3. The coordinator's open items are unchanged: HIGH-S10 (18+ gate), MED-S1/S2/S3/S5/S7.
4. TEST builds that rely on the NPC TEST responder must build with `-Pe2e-test-providers`. Other E2E providers already need this.
