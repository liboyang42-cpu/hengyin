# qx-s0 staging integrator: RESULT (2026-11-10)

Branch `staging/questo-review-20261110`. This is a preview build for the owner's review, not a merge of any PR. No PR was merged, closed or marked ready. Nothing was pushed to `master` or `integrate/questo-ui-20261110`. No production, VPS or private-repo access was used.

## 1. Merge log

The base is `origin/integrate/questo-ui-20261110`, starting at `a0824a43`. Every lane was merged with `git merge --no-ff`, in the order LANES.md gives.

c1 had no STATUS line at 22:40 UTC. It was polled every 10 minutes, and its RESULT.md appeared with a STATUS line at 22:53 UTC (`STATUS: PARTIAL`, item 5b). So c1 went first as specified, and no lane was skipped.

| # | Lane head merged | Merge commit | Conflicts | How they were combined |
|---|---|---|---|---|
| 1 | c1 `76a710ce` | `127c52b4` | none | — |
| 2 | b1 `46dc0d8b` | `a1ee3031` | none | — |
| 3 | b2 `ca5d3fb8` | `37546325` | `action-ledger.json`, `action-ledger.test.js`, `advanced-game-config-slowtask.test.js` (comment only) | Ledger rebuilt. Count assertions keep both lanes' comments, and the numbers are re-measured from the rebuilt ledger: b1 +6 tap and b2 +24 tap gives 2906 (see §2). The slowtask comments from both sides are kept. |
| 4 | b3 `e2a0207d` | `fa63ccdb` | ledger | Rebuilt and re-measured |
| 5 | b4 `2f7146e3` | `822f00fe` | **`pages/play/index.js`**, `route-matrix.json`, ledger | **Play page:** b1's hint-ladder methods (`playHelpPanel.methods`, `onHelpSkipped`) and b4's `...coopHelp.methods(req)` are both kept on the same line as `...themeBackpack.methods(req)`. `route-matrix.json` was regenerated with `build-route-matrix.js --json` (+ `pages/publish/preset/index`). |
| 6 | b5 `6460e5fb` | `ac31adf2` | `route-matrix.json`, ledger, `action-ledger.test.js` | Regenerated, rebuilt and re-measured |
| 7 | b6 `efba50ea` | `00ed7286` | none | — |
| 8 | b8 `b9a25b68` | `6ca61d44` | ledger, route matrix, `ui-integrity-baseline.js`, `get-auth-matrix-contract`, `route-matrix-freshness`, `title-system-owner-contract`, `action-ledger.test.js` | Count tests: both lanes' comments are kept, with merged-tree counts (routes 168, custom 167, GET wrapper 46 + b1 2 + b8 1 = 49). The title inventory gained the b3/b4 pages that those lanes had left out (see §3). `ui-integrity-baseline.js`: both lanes' header notes are kept. The play `U4-DYNAMIC` anchor 1866/1849 moved to 1868 and the `U5 loadMilestone` anchor 8861/8831 to 8883. These are line moves only, with the same call line. `keyDigest` was recomputed with the gate's own `baselineKeyDigest`, and no entry was added. |
| 9 | a2 `0e2c763c` | `70977f44` | none | a2's tokens and switches came in unchanged, so a2 wins on colour by default |
| 10 | a1 `a35d77dc` | `184ffa9c` | **`pages/play/index.js`**, **`theme-backpack.wxss`**, ledger, `action-ledger.test.js` | **Play page data:** `...orientLifecycle.data, ...playTopbar.data, ...playHelpPanel.data, ...kitCard.data` (b1 + a1). **Backpack wxss:** b4's co-op help rules (`.ch-*`) and a1's V3-16 slot grid (`.tb-slots`, `.tb-items-empty*`) are both kept. They are disjoint selectors. The ledger-test control ids for `openStackOrChaptersFromTools` / `onPickChapter` were updated to the merged WXML lines (321/525). |
| 11 | a3 `6714e30f` | `d611c410` | ledger, `action-ledger.test.js` | Rebuilt and re-measured, both notes kept |
| 12 | a4 `24cff2bd` | `96fe7b84` | ledger, route matrix, baseline, `get-auth-matrix`, `nav-page-title-spacing`, `title-system-owner`, `route-matrix-freshness` | a4 removes the time-capsule page. Counts: routes 167, custom 166, cy-page-title pages 89 (b4 +1, a4 −1), GET wrapper 50 (a4 +1). Baseline: the u4Dynamic/u5Silent key union, with the digests recomputed. |
| 13 | a5 `c9ce8304` | `1bbe8a0d` | ledger, baseline | The 11 `publish/fabu` U4-DYNAMIC anchors were re-anchored to merged-tree lines by pairing stale and unregistered call sites in line order (counts matched exactly). The same call line was checked, e.g. 9356 is the route-map redraw. Digest recomputed. |
| 14 | a5d `d9f803d9` | `6e03c044` | ledger, baseline | The same 11 fabu anchors were re-anchored after a5d's +203 lines (1200, 3111, … 9559). Digest recomputed. |
| 15 | a6 `7233093e` | `89d79ea2` | **`components/tabBar/index.js`**, ledger | **Merchant tab bar:** a2's outline/filled icon pair (`icon_tab_*_on.svg` as `selectedIconPath`) is kept, and a6's label change for the fourth tab, 「合作」 → 「发现」, is applied. a2 wins on the icons; a6's copy is kept. |
| 16 | a7 `ae6278c0` | `98f838a0` | none | — |
| 17 | `origin/integrate` moved (`24f5021a`, GET-auth contract test fix) | `ae79c7cd` | `get-auth-matrix-contract.test.js` (contract segments) | The mainline version of the contract segments was taken. b1's two `/api/play/help` matrix rows are kept. This is the same resolution b1 used in its own `c9f4a5de`. |
| 18 | b1 moved (`c9f4a5de`) | `1b086946` | `advanced-game-config-slowtask.test.js` | `ROOT_TOOL_SECTIONS` stays `['backgroundMusic', 'hintLadder']`. b2 wires `hintLadder` in the editor; b1's sync had taken the base list. |

Staging-only commits on top of the merges:

- `d825ac7a`: qx-b3 had committed `docs/.../qx-b3/rig/seed-b3.sql`. The migration ledger (`ci/migration_ledger.py audit_inventory`) refuses any `.sql` outside the managed directory (`UNMANAGED_SQL_LOCATION`), and that broke `build_schema.py`, the same gate CI's migration checks use. It is renamed to `seed-b3.sql.txt` and qx-b3's RESULT.md references are updated. No content changed.
- `28f3c5bf`: Combined-tree test fixes:
  - `nav-route-exists`: qx-b3's share landing page `subpackageJourney/pages/landing` is entered only from the `k=r` / `k=s` share links. It is added to the existing deep-link exemption list, with the same kind of reason as qx-b5's gift page.
  - `settings-action-locator`: the settings cell count is now 13 + b3 「邀请与收益」 + a3 「投诉建议」 = 15.
- `03a8a417`, `4ad24b64`: the rig fixture and the new screenshots (docs only).

## 2. How generated files and counters were handled

- **`action-ledger.json`** was rebuilt after every merge with `build-action-ledger.js --write`. The final count is 5874 controls.
- **Ledger denominators** in `action-ledger.test.js`: the expressions are re-evaluated against the rebuilt ledger, and only the numbers are rewritten. The lane comment from each side is kept on the assertion.
- **`route-matrix.json`** was regenerated with `build-route-matrix.js --json`. It has 167 routes.
- **UI-integrity baseline:**
  - No new entry was registered.
  - The only key changes are line moves of an identical call line.
  - Digests use the gate's own formula, sha256 over the sorted key list.
- Scratch tools used for this are not committed. They are `resolve_counts.py`, `fix_ledger_counts.js` and `remap_baseline.js`, and their logic is described above.

## 3. Gate results

**After each merge** (the commit list is in §1): the mini-program unit tests touched by that lane, plus `ui-integrity.js`. Each lane's test files were run with `node --test` at its merge commit, in a scratch worktree.

| Lane (merge) | Lane test files | pass / fail | UI-GATE-0 | Notes on failures |
|---|---|---|---|---|
| c1 `127c52b4` | 0 (backend only) | — | PASS | — |
| b1 `a1ee3031` | 12 | 255 / 0 | PASS | — |
| b2 `37546325` | 10 | 233 / 0 | PASS | — |
| b3 `fa63ccdb` | 2 | 58 / 0 | PASS | — |
| b4 `822f00fe` | 10 | 202 / 0 | PASS | — |
| b5 `ac31adf2` | 12 | 121 / 7 | PASS | `nav-route-exists` ×4: b3's landing page, fixed in `28f3c5bf`. `route-matrix-freshness` and `title-system-owner` ×2: stale counts, fixed at merge 8. |
| b6 `00ed7286` | 0 (admin only) | — | PASS | — |
| b8 `6ca61d44` | 11 | 177 / 7 | PASS | `nav-route-exists` ×4 (fixed later). `get-auth-matrix` ×1 and `nav-page-title-spacing` ×2: lane-owned, Cross-lane 1–2. |
| a2 `70977f44` | 7 | 87 / 0 | PASS | — |
| a1 `184ffa9c` | 8 | 185 / 0 | PASS | — |
| a3 `d611c410` | 7 | 87 / 1 | PASS | `settings-action-locator`: b3 + a3 count, fixed in `28f3c5bf` |
| a4 `96fe7b84` | 13 | 223 / 3 | PASS | `get-auth-matrix` ×1 and `nav-page-title-spacing` ×2 (Cross-lane 1–2) |
| a5 `1bbe8a0d` | 5 | 101 / 4 | PASS | `nav-route-exists` ×4 (fixed later) |
| a5d `6e03c044` | 5 | 133 / 0 | PASS | — |
| a6 `89d79ea2` | 6 | 94 / 0 | PASS | — |
| a7 `98f838a0` | 3 | 58 / 0 | PASS | — |

**At the end** (HEAD of this branch):

- **Action ledger:** `build-action-ledger.js --write` gives 5874 controls with no diff. `build-route-matrix.js --json` gives 167 routes with no diff.
- **`ui-integrity.js`:** `UI-GATE-0:PASS`. U1–U5, A11Y and Legacy all PASS.
- **Full mini-program suite** (`node --test chengyinhub-xcx/tests/unit/*.test.js`, HEAD `1b086946`): run in progress when this was committed (a container restart killed the first run); result is posted on the PR.
- **`mvn -q -DskipTests package`:** PASS. Run at `98f838a0` and rebuilt at `d825ac7a` for the rig. No Java, XML or SQL changed after that.
- **Java tests for classes touched by lanes:**
  - Command: `mvn -pl chengyinhub-admin -am -Dtest=<20 classes> test`.
  - The 20 classes are every changed `*Test`, plus each `<ChangedMainClass>*Test`:
    - `AdvancedGameRuntimeServiceImplTest`, `ApiTopicTemplateControllerTest`, `ApiTopicTemplateUseIdempotencyTest`, `AppMediaReferenceGuardTest`
    - `AuthorWriteReceiptServiceTest`, `BusinessAuditDomainTypeLengthTest`, `ContentLandingTranslationReviewAuthorityTest`, `DruidWallForUpdateOfContractTest`
    - `GameplayPresetCatalogContractTest`, `PlayHelpApplicationTest`, `PlayHelpAuditControllerTest`, `PlayHelpFrozenNodeConfigTest`
    - `PlayRunFeedbackServiceTest`, `RuntimePlayMediaProjectionTest`, `ThemeNodeArrivalsTest`, `TopicContentCopyCityAdoptionTest`
    - `TopicLocationBindingServiceTest`, `TopicWorkspaceServiceImplTest`, `VideoBountyGroupVerificationTest`, `VideoBountyServiceTest`
  - Result: **485 tests, 0 failures, 0 errors**, BUILD SUCCESS.
  - `WorkshopPurchaseMyBatisIT` needs MySQL on 3306 and was not run.
- **`chengyinhub-ui` `npm run build:prod`:** **PASS**: 「Build complete」 with 13 webpack warnings and 0 errors.
  - The lockfile resolves tarballs from `registry.npmmirror.com`, which this cloud container's proxy refuses (403).
  - `npm ci` was therefore run with `--registry=https://registry.npmjs.org/ --replace-registry-host=registry.npmmirror.com`, so the same tarballs pass the same integrity hashes. The lockfile is untouched.
  - CI's GitHub runners use the lockfile as is.
- **Schema:** the rig's `build_schema.py` (the candidate's own migration ledger and replay) passes on this branch, after the `seed-b3.sql` fix: 501 tables. The new `play_run_feedback` table from `migration_20261109_play_run_feedback.sql` is present. The e2e DB keeps no `cy_migration_history` rows, so the other three new migrations were not read back one by one.

## 4. Main-package size

`node chengyinhub-xcx/scripts/mainpkg-size-report.js --json` reports `mainTotal`, the main-package source bytes after `packOptions.ignore`.

| Tree | Source bytes | × 0.633 | Estimate |
|---|---|---|---|
| base `integrate` `a0824a43` | 3,210,001 | 2,031,931 B | **1984.3 KiB** |
| staging `98f838a0` (all lanes) | 3,233,159 | 2,046,590 B | **1998.6 KiB** |

That is under 2048 KiB, with about 49 KiB left, so no page had to move. The margin is thin. The report lists 10 zero-reference main-package components (59 KB) and one component that can move into a subpackage (`club-direct-ledger` → `pages/club`, 10 KB) as the first candidates if the next wave needs room. This is an estimate from source bytes. The authoritative number is the DevTools upload size (`wechat-release-contract.js`), which only a Mac can produce.

## 5. Rig fixture: a ticketed play state on this base

These files are in `staging/rig/`. Nothing is in product code.

- **`free_orient_theme.py`** builds a sellable, self-run 城市定向 theme (`productType=1`, ordered stations, club 7001) with one free ticket, a ¥0 self-play pass. It then takes fixture player 9004 to a live run.
- **Steps, all real endpoints unless marked:**
  1. Publisher real-name: `/api/publisher/identity`.
  2. A check-in node template, then a classic text-question template (vm=1) with a root `hintLadder` and `hintFreeUsesPerRun`: `/api/template/publish`. Each is approved through `/business/audit/review` with `expectedVersion`.
  3. `/api/topic/v2/create`: one chapter, two ordered stations, a `divider` block, and `themeRuntimeConfig.scenes[].nodeArrivals` with an `arrivalReading` per station (qx-c1 #5b write path).
  4. Content review.
  5. **SQL, the only gap:** `cms_topic.self_play=1, self_play_price=0`. Create and update force `self_play=0` unless the price is above 0, so no endpoint can store a ¥0 self-play pass.
  6. Player consent, then participant row, then `/api/registration/quote` (payAmount 0), then `/api/registration/create`. Zero-amount completion gives `registration_status=2, payment_status=2`, with no payment callback and nothing written to `payment_status` by the fixture.
  7. `GET /api/play/nodes` returns 200. It used to return 402 「请先购买自玩通行证」.
- **Why the old chain failed:**
  - `sale_close` runs as exec club 7002 on a `productType=2` (自由探索) topic, which trips `ExploreSaleSnapshotGate.assertSessionClub`.
  - As the owning club, `finalizeForSale` refuses the 四店固定补偿 canonical product.
  - A `productType=1` topic with no priced facts stays `lifecycle=0` and counts as "free". It never needs the sale review.
- **Rig-only seams, as diffs in `staging/rig/`:**
  - `rig-up-sha-param.diff`: `up.sh` takes `SHA` and `CAND` from the environment.
  - `rig-app-oss-seam.diff`: `app.py` passes placeholder `CHENGYIN_OSS_*` values so media URLs can be signed and serialised. Without them, `/api/play/nodes` returns 500 「对象存储配置缺失」. They are not credentials, and nothing reaches a bucket.
  - qx-c1's `rig-app-media-seam.diff` was also applied.
  - The rig's `harness-patches.diff` came from the club branch.
  - Docker's registry mirror was set to `mirror.gcr.io`, because Docker Hub returned 429 for the pinned redis digest.
- **Proof:**
  - `rig/fixture-run.txt` is the last run (topic 990025): every request, response and SQL read-back.
  - `rig/play-driver.mjs` and `rig/play-steps-to-station1.json` are the browser steps used for the shots.
  - Note: the play page's privacy gate is stacked under its location prompt in the rig, so the driver agrees on the home page first and then opens the play page.
- **Side result for qx-c1 item 5b:** on topic 990023/990025, the published `published_route_graph_json.routeStations[].arrivalReading` holds the authored readings for both stations. That is the publish freeze qx-c1 had proven only by unit tests, now shown over real HTTP and MySQL.

## 6. Missing screenshots: re-shot

These are in `staging/shots/`, 390 × 844 JPEG at q70. They were taken on the staging jar and bundle at `d825ac7a` (later commits change only tests and docs). The run is real: fixture player 9004, GPS on the authored station coordinates, real `/api/play/*` responses.

| Shot | Lane / item | What it shows | Checked |
|---|---|---|---|
| `b1-U04-01-after.jpg` | b1 U04-01 | The `divider` block renders as a thin centred line between two paragraphs in the live chapter flow | No overlap or clipping |
| `b1-U05-03-after.jpg` | b1 U05-03 / §27.3 | After 「我已到达此站」 at station 1, the server's `arrivalReading` 「广场的鸽子一齐飞起」 (level 2) appears right after that station's note and before the next paragraph. `chapterParas` read-back: `["text","广场的鸽子一齐飞起",2]` | The distance chip changes 0 m → 1.3 km to station 2, as expected |
| `b8-U08-01-after.jpg` | b8 U08-01 | Finish screen after a real completion: 「已通关」 comes from `GET /api/play/player-journey/report`. Page data: `finishResult {label:"已通关", source:"report", kind:"LEGITIMATE_COMPLETION"}` | The pass card above it shows 1.3 km, 02:26, 2 stations, consistent with the run |
| `b8-U08-01-passcard-after.jpg` | b8 U08-01 | 「全部完成」 pass card for the same run: 2 坐标, 02:26 用时, 100 解谜分 | The numbers agree with the finish screen |
| `b1-run-ending-after.jpg` | b1 / play core | 「本局完成」 ending reached by a real run: question answered, arrival, completion | — |
| `s0-fixture-play-entry-after.jpg` | fixture | The play page of the fixture theme after 开始游戏 | — |

**Not re-shot, each tried:**

- **b1 UG-05/06/07 (hint ladder sheet, counter, score detail).**
  - Reached the classic task screen (vm=1, 「提交」) on a run with route session 2, and the client did call `GET /api/play/help`.
  - The server answers 400 「原局身份不可用，请重新进入本局」 because the run's `play_instance_id` is `default`. `PlayHelpApplication.frozenResolved` refuses `default`, so the ladder needs a ticketed activity-session run.
  - I also switched on `feature.flag.orientSession` (rig DB only, via `/system/config`, deleted afterwards) to try the orient prepare path. It returned the same `default` play instance for a self-play pass.
  - The ladder therefore needs a club-opened activity session, which this fixture does not build (see Cross-lane 3). The client correctly kept the legacy hint row, because `enabled=false`.
- **b1 U03-02 (editor publish result sheet).** Not re-tried. b1's blocker, that the editor re-submits presigned URLs with dummy OSS, still applies.
- **a1 V3-11 (album).**
  - Tried in a phone-sized viewport (390 × 844) with qx-a1's own preview harness (`qx-a1/rig/gen.js album`), once with `prefers-reduced-motion: reduce` and once without.
  - In both, once the album/game paragraph scrolls into view, the page's main thread stops answering: eval and screenshot both time out.
  - A `Debugger.pause` taken during the hang lands in the rig runtime's DOM patcher (`patchChildren → updateEl → … → Instance.__render`), so it is a re-render loop inside the headless runtime.
  - Evidence: `staging/rig/album-hang.txt` and `album-hang-probe.mjs`. I accept this as a headless limitation. It is not proven to be a product bug, and it needs a phone check.
- **b4 §26.1 co-op help panel, b5 pass purchase success / host accept→pay / accrual rows, b6 §20.2 / §19 admin states.** These need a TEAM co-op run, a priced marketing product with a payment callback, and a provider with marketing orders. None of them is built by this fixture, and they were not attempted in this window.

## 7. Cross-lane (found while integrating)

1. **qx-b4 and qx-b5: 9 GET call sites with no permission-matrix entry** (`get-auth-matrix-contract` stays red, 1 test).
   - The files:
     - `pages/activity/baoming/baoming.js`
     - `pages/publish/preset/index.js`
     - `pages/templatedetail/topic-detail/index.js`
     - `subpackageA/pages/assetcenter/income-detail/income-detail.js`
     - `subpackageA/pages/video-bounty/index.js`
     - `subpackageMember/market/pass/index.js` ×2
     - `subpackageMember/market/passes/index.js` ×2
   - This test was already red on both lane branches.
   - I did not invent auth, role or scope classifications. Each lane owner should add the matrix rows from the server guard. `directCount` is set to the measured 122.
2. **qx-b5: `nav-page-title-spacing-contract` is red (2 tests).**
   - b5's new pages, for example `subpackageMember/market/gift/index.wxml`, are not classified in the spacing inventory.
   - The cy-page-title count is 92 against the merge arithmetic of 89.
   - It was already red on the b5 branch. The fix is a spacing classification, which is a design decision for b5.
3. **The hint ladder (UG-05..07) cannot be reached on a self-play pass.**
   - The server refuses `play_instance_id='default'`.
   - Either the owner confirms the ladder is meant only for activity-session runs, or qx-c1/b1 make self-play runs carry a real play instance.
   - The rig would then need a club-opened session on a `productType=1` topic. `open-session` requires an active cooperation contract.
4. **qx-b3 and qx-b4 left `title-system-owner-contract` red on their branches**, because their new pages were missing from the title inventory. Fixed in staging at merge 8. These are plain inventory rows, with no judgement involved.
5. **qx-b3 committed a `.sql` under docs**, which breaks the migration-ledger gate. Renamed in staging, see §1. The b3 branch itself still carries the `.sql`.

## 8. Needs owner

- **The thin main-package margin (about 49 KiB).**
  - Default: nothing moved.
  - Next candidates: drop the zero-reference components, and move `club-direct-ledger` into `pages/club`.
- **Hint ladder scope (Cross-lane 3).**
  - Default: nothing changed. The client shows the legacy hint row when the server says `enabled=false`.

STATUS: PARTIAL - all 16 lanes merged and gates run (see §3a); rig fixture works and b1 U04-01/U05-03 and b8 U08-01 are now shot on a real run, but b1 hint ladder (server refuses self-play runs), b1 U03-02, a1 album (headless render loop), b4 §26.1, b5 payment states and b6 admin states remain unshot; get-auth-matrix (9 unregistered GETs from b4/b5) and nav-page-title-spacing (b5) stay red pending the lane owners
