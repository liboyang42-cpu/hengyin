# integ-master (M2): master #830–#852 merged into roadmap/be-integ

Branch `feature/r-integ-master`, based on `origin/roadmap/be-integ` 0ea98d97e, merging `origin/master` 65498f6da
(18 master commits: #830, #831, #835, #838–#852). The merge commit is 0ae5bdc41. Three small fix commits follow it, then this receipt.

## 1. Conflicts and how they were resolved

The merge stopped on 11 files, as the trial merge did. For each one I read both sides' commits (`git log -p` in each direction) before editing.

| File | Master side | be-integ side | Resolution (both survive) |
|---|---|---|---|
| `ApiCityNodeController` `/nodes` | #830 A5.4/A5.5: new `limit` param, NaN/out-of-range coordinate rejection, tag character check | typed refusal `refuse(LOCATION_REQUIRED)` | Master's signature and both checks. be-integ's typed refusal for missing location. Master's new checks keep `error(...)` as master wrote them. |
| `ApiCouponController` | #850: full-width punctuation in the stop-issuing messages | stop-issuing moved into the receipt-backed `stopIssuing`/`CouponCommandReceipts.Outcome`; `currentAppMember()` helper | be-integ's structure. Master's punctuation applied to the same three messages inside the receipt outcomes. |
| `ApiGrowthController` | #842: new `GET /api/growth/leaderboard` public board for guests | new `POST /api/growth/leaderboard/anonymous` | Two different routes, so both methods are kept. |
| `ApiPricingController` | #838/#843: `requireOwner(topicId, memberId, scope)`, where merchant scope resolves the shop owner through `PROJECT_MANAGE` | typed refusals (`BAD_REQUEST`, `TOPIC_NOT_FOUND`, `FORBIDDEN`) | Master's scope-aware owner resolution with be-integ's typed refusals. |
| `ApiRoamController` `/pois` | #830 A5.1–A5.3: fetches `limit+1` rows to set `truncated`; gallery preview of 3; guest projection `publicPoi`; #842 guest read | R1 MKT-08 keyword/category/tag filters through `selectPoisNearFiltered` | Both query branches pass `POIS_NEAR_LIMIT + 1`. Master's post-processing (truncate, gallery head, guest projection, `truncated` field) applies to both. |
| `ApiRoamController` `/entry/nearby` | #842: dropped the login gate so guests can browse | typed refusals | Guest browsing kept (no login check). Typed refusals for location. |
| `ApiRoamController` speed checks (2 places) | #850: `移动过快，疑似伪造定位` | `refuse(ROAM_MOVING_TOO_FAST, …)` | Typed refusal with the full-width message. |
| `ApiRoamController` rate limiters (auto-merged, would not compile) | #830 A5.7: `@RateLimiter(60/min)` on `/poi/discover`, `/shop/visit`, `/checkin` | location-trust: `@RateLimiter` on the same methods (60 / 30 / 30) | One annotation per method; `@RateLimiter` is not repeatable. I kept be-integ's limits, which are the same or stricter, so both sides keep a per-member write limit. `/shop/visit` and `/checkin` are 30/min, not master's 60. See section 6. |
| `ApiRoamHangoutController` `/nearby` | #842: no login gate; `Double.isFinite` check | typed refusal | Guest read and the isFinite check, with the typed location refusal. |
| `CmsActivityServiceImpl.settleActivity` | #839 D-29/D-30: subtract `ledger.isolatedGross`; `platformOverride` from the free-explore ledger plus the retained share | S2: subtract `collectionSplitSettlement.splitSalesForActivity` (0 while the model and rails are off) | Both subtractions are kept, in sequence, and master's `platformOverride` is passed on. |
| `RoamMapper.xml` | #830: `selectPoisNear` rewritten: an inner query applies the box filter, orders by `node_level desc, near_dist`, then limits; light columns (gallery, quotes, photo credit, badge) | statement renamed to `selectPoisNearFiltered` (`selectPoisNear` is now a default method that delegates to it), with keyword/category/tag filters | Master's whole statement now lives in `selectPoisNearFiltered`. be-integ's filters sit in the inner query **before** the inner `limit`, so the query filters first and truncates second. Tag matching stays be-integ's substring match (master's quoted whole-tag match applies to `/city/nodes` only). |
| `chengyinhub-xcx/scripts/uiaudit/action-ledger.json`, `route-matrix.json` | #841 deleted them: they are generated in CI and gitignored | edited | Deleted, following master. They are regenerated from source with the repo's own `node ci/prepare-mini-artifacts.js` (`npm run test:unit` and `lint` run it automatically). be-integ's xcx source changes are all in the tree, so the generated output includes them. |
| `ci/migration-dependencies.json` | `20261114_missing_topic_pricing_rule`, `20261114_roam_poi_tags_json` | about 45 roadmap entries | Edited as text: every entry from both sides kept, in the existing format with lowercase ids (514 entries). |

## 2. Integration fixes (after the merge commit)

| Commit | File(s) | Reason |
|---|---|---|
| `fix(r-integ-master): defer club_reach / growth_loops …` | `ci/migration_ledger.py`, `ci/test_migration_ledger.py` | The deploy-order rule; see section 4. |
| `fix(r-integ-master): full-width punctuation …` | `ClaimEvidenceService`, `ClubOpsService`, `DistrictDensityService`, `PointsGovernanceService`, `ApiRoamGuestWalkController`, `ApiCityCoinController`, `ApiLocationTrustController` | Master #850 added the ratchet `PlayerMessagePunctuationTest`. 12 be-integ player messages used ASCII `,` or `:` next to Chinese text. Only the punctuation changed; no test or client matches these strings. |
| `test(r-integ-master): ApiRoamPoiFilterTest expects master's limit+1 …` | `ApiRoamPoiFilterTest` | The only Java test that failed on the merge but passed on both parents. It pinned the old limit. Master's `ApiRoamCoordinateBoundaryTest` pins `POIS_NEAR_LIMIT + 1`, which is the merged behaviour. |

## 3. Gates

Every command ran with `LANG=C.UTF-8 LC_ALL=C.UTF-8`.

| Gate | be-integ | master | merge |
|---|---|---|---|
| `bash ci/migration-safety-check.sh` | 迁移安全门禁:通过 | 迁移安全门禁:通过 | **迁移安全门禁:通过** |
| `bash ci/migration-layout-check.sh` | 通过 | 通过 | **迁移布局门禁:通过** |
| `rollback_*.sql` in `chengyinhub-admin/sql` | none | none | **none** |
| `node --test ci/test_*.js` (4 files, incl. #841's `test_mini_artifacts`, `test_test_impact`) | 2 of 4 run* | 4/4 pass | **4/4 pass** |
| `bash ci/test_*.sh` (10 files) | 10/10 | 10/10 | **10/10** |
| `pytest ci/test_*.py`, one file at a time, no `*_mysql_integration` | 15 failing IDs | 3 failing IDs | **15 failing IDs, the same set as be-integ; none merge-only** |
| xcx `node ../ci/prepare-mini-artifacts.js` + `node --test tests/unit/**` | 17569 tests, 29 fail (prepare script absent) | 17826 tests, 2 fail | 17848 tests, 31 fail |
| xcx `node tests/lint/ui-integrity.js --warn-as-error` | FAIL (U1, U5 money-payout.js) | PASS | FAIL (same U1/U5 as be-integ) |
| ui `node --test tests/unit/**` | 573 tests, 30 fail | 555 tests, 15 fail | 573 tests, 30 fail (same IDs as be-integ) |
| ui `node --test tests/e2e/contracts/*` | 36/36 | 36/36 | 36/36 |

\* be-integ does not have #841's two new JS test files.

The Python failures on merge and be-integ are `test_assert_it_ran` (collection KeyError `GATE`, which needs CI env), `test_funds_it_gate` (`ShopDayOneTicketMySqlIT EXPECTED=6`, but the source has 5), `test_http_e2e_entry`, `test_migration_runner_ci_contract` (be-integ's `20261115_external_game_monetization_menus` and `20261121_admin_review_queue_menus`), `test_prelaunch_business_seeds` (2), `test_questo_collation_alignment` (2), `test_real_storage_it` (7) and `test_release_migration_rehearsal` (the 20261029 pack is stale). Master fails only `test_assert_it_ran`, `test_http_e2e_entry` and `test_release_migration_rehearsal`. All of these are inherited from a parent.

**xcx failures on the merge that are not in either parent's list (4). None is merge-made:**
- `后端权限真源：公开目录仅精确 GET 匿名…` and `boot4/legacy DSL 正例保留全部 19 条权限规则`: an exact snapshot of `SecurityConfig` permitAll rules. The merged config has be-integ's additions (`/api/user/sms/send`, Apple challenge/exchange, `/api/merchant/poi/detail`, `GET /api/user/deregister/result`, `POST /api/telemetry/anon/events|metrickit`). be-integ already fails its own copy of the same tests ("17 条" and "official events/roam share…") for the same reason. The test only changed name and count on master.
- `按钮台账不把定位回调的 reveal 或 GET ending 冒充当前点击的第二次写入` (master test, also present on be-integ): 4 controls in be-integ's new payout pages (`subpackageMember/payout-request` load/cancel/submit, `payout-bind` claim) list both `/api/payout/request/cancel` and `/submit` without `requiredRequests`, because they share one `utils/money-payout.js` `call()` helper. On be-integ the ledger cannot be rebuilt at all (`pages/shezhi/app/index: 未进入当前 route-matrix`), so this test errors there as well.
- `V-02 7 天内到期…` also fails on master. It depends on the time of day (the run was near 21:00 UTC).

## 4. Backend suite on three trees

Recipe on each tree: `mvn -B -q -DskipTests install`, then `mvn -B -pl chengyinhub-admin -am test -Dmaven.test.failure.ignore=true "-DargLine=-Xmx6g -Dspring.test.context.cache.maxSize=4"`. The parents ran in separate worktrees. Counts come from every surefire XML in the reactor.

| Tree | Head | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|---|
| origin/roadmap/be-integ | 0ea98d97e | 20281 | 0 | 34 | 713 |
| origin/master | 65498f6da | 19117 | 0 | 34 | 677 |
| merge, first run (after the punctuation fix) | 50343581a | 20363 | **2** | 34 | 713 |
| merge, final | 10854c650 | 20363 | **0** | 34 | 713 |

- The 34 errors on every tree are the `*MyBatisTest` classes (`Communications link failure`, no MySQL), and they are the identical class set on all three. They are left out of the comparison.
- First merge run: `ApiRoamPoiFilterTest#noFilterKeepsTheOriginalQuery` and `#filtersAreTrimmedBoundedAndPassedThrough` failed on the merge and passed on both parents. They are fixed (section 2).
- `PlayerMessagePunctuationTest` (master) failed on the merge before the punctuation fix. It does not exist on be-integ, and the fix landed before the first full merge run.
- Final run: no test fails on the merge. The ratchets pass: `SysJobStaggerPlanTest` (3/3), `TwoNodeSchedulingTest` (4/4), `PlayerMessagePunctuationTest`, `GuestPublicCatalogSecurityTest` (7/7) and `ApiRoamCoordinateBoundaryTest` (13/13). The merge adds no new sys_job or `@Scheduled` method, so no stagger migration was needed.

## 5. Deploy order (POST_DEPLOY)

What the old jar does: `SysJobServiceImpl.init()` calls `ensureUniqueDefinitions` and then registers `refreshPersistedSchedule`. Both call `CanonicalJobTarget.canonical(invokeTarget)` for **every** `sys_job` row, paused rows included. `canonical` resolves the bean with `SpringUtils.getBean`. For an unknown bean it throws `ServiceException("任务调用目标或参数无效")`. On master's jar a row naming a new bean therefore blocks startup, and on a running node it makes the schedule refresh throw. That is the rule the `POST_DEPLOY_MIGRATIONS` comments (qx-c2, ROUND10 r1) describe.

be-integ migrations that are not on master and touch `sys_job`:

| Migration | sys_job write | Bean on master's jar? | Verdict |
|---|---|---|---|
| `20261115_club_reach` | INSERT `clubChannelDefaultsTask.backfill(200)` (paused) | **no**: `ClubChannelDefaultsTask` exists only on be-integ | **POST_DEPLOY**. The file's own header already says "run it in the post-deploy round". |
| `20261115_growth_loops` | INSERT `growthWinbackTask.run()` (status 0), plus tables, sys_config and badges | **no**: `GrowthWinbackTask` exists only on be-integ | **POST_DEPLOY**. The file header says the new jar tolerates missing tables (the winback round skips and invite rewards only log). |
| `20261115_stagger_club_growth_jobs` | UPDATE cron of the two rows above only | n/a (UPDATE only) | **POST_DEPLOY**. Run before them, it updates 0 rows, and the later INSERTs would land on second 0. That breaks the stagger plan in the database even though the unit ratchet stays green. Deferral keeps its topological order after both. |
| `20261115_stagger_all_sys_jobs` | UPDATE cron only, guarded by the old cron value | n/a | Pre-deploy is fine: it changes no `invoke_target`, so the old jar resolves the same beans. |

Done: the three ids are added to `POST_DEPLOY_MIGRATIONS` in `ci/migration_ledger.py`. `ci/test_migration_ledger.py` gets a new test, `test_club_reach_and_growth_loop_jobs_are_post_deploy_with_their_stagger_last`, modelled on the qx-c2 test. It checks the phases, `DEFERRED_POST_DEPLOY`, that the stagger is ordered after both, and the bean targets in the files. `test_migration_ledger.py` reports 58 passed, and the safety gate still passes.

## 6. Left for the owner

1. **Rate limits on `/api/roam/shop/visit` and `/api/roam/checkin`:** master #830 chose 60/min per member and be-integ's location-trust chose 30/min. Only one annotation is allowed, and I kept 30. If the mini-program team relies on 60, change the two annotations in `ApiRoamController`.
2. **growth_loops split (optional, cleaner):** with the whole file deferred, its tables are created after the new jar starts (the jar tolerates this). The precedent (content_safety, marketing) is a separate POST_DEPLOY file holding only the `INSERT INTO sys_job`, so the schema can still run pre-deploy. Doing that means editing an unreleased be-integ migration, so I left it to the coordinator.
3. **Money path check:** `settleActivity` now subtracts S2 split sales and D-30 isolated gross one after the other. They should be disjoint: S2 is zero while the collection model is off, and the D-30 rows have no split rows. Re-check this before S2 is switched on.
4. **Inherited be-integ CI debt** that the merge carries unchanged (none merge-made):
   - the xcx `get-auth-matrix-contract` snapshot is missing be-integ's new permitAll rules. Updating it is a security sign-off, so it is not done here.
   - `utils/money-payout.js` has 2 silentError calls with no recorded reason (U5), and the shared `call()` helper makes 4 payout controls list two write APIs.
   - the Python failures in section 3.
5. Tag filtering on `/api/roam/pois` stays a substring match. Master switched `/city/nodes` to quoted whole-tag matching (A5.5), and `/pois` could follow if the app always sends whole tags.

Not verified: anything that needs MySQL (the `*MyBatisTest` classes, the `*_mysql_integration.py` tests), a real deploy or device runs.
