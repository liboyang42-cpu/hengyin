# Round 7 result: launch fixes on `integ/launch-test-20261103`

Started after Round 6's `RESULT.md` showed `STATUS: COMPLETE` (11:14 UTC). All work is on `integ/launch-test-20261103`, pushed after each item. Draft PR #723 runs CI. Nothing was pushed to master, no PR was merged, production was not touched. No secrets or word list were added.

Commits, in order:

| Commit | Item |
|---|---|
| `3cf453808` | 1: merge `claude/arch-owner-l8-20261103` (real merge) |
| `59124be29` | 1: merge #666 at `984b00de3` (real merge) |
| `a3c9e8fbd` | 3: map text follows the phone's font size |
| `a31923e3a` | 4: category pick clears the red error |
| `cef12deee` | 4: chat rows get the screen-reader label |
| `d3c115dc4` | 5: city list (11 cities, 33 places) and map categories |
| `0d3b01f99` | 2: main package slimming |
| `2dbc98fd0` | 5 follow-up: three catalog notes reworded for the roam redline gate (CI found it) |
| `892ee3c89` | 5 follow-up: city-list button moved off the WeChat capsule (seen in the rig) |
| `a17607680` | 5 follow-up: place-card reason/sources drawn by the page's own `.pc-body` lines (were grey on the light card in the rig) |
| `de56cfa56` | 2 follow-up (CI static gates): four utils with write endpoints back to main; same-package placeholders removed; unit fallback; `ds-ok` note |

## 1. Merges Round 6 left out

- **arch-owner-l8**: `git merge --no-ff`, no conflicts. It changes only `ci/architecture/*` and the two verifier pins in `ci.yml`. `f12852845` is an ancestor of the branch, so the pinned verifier stays reachable.
  - Local proof, on the merged tree, with Round 6's own script (`integ/proof/arch-gate-run.sh`: simulated `pull_request` event, base master `c6eac3b8c`, verifier checked out at `f12852845`):
    - verifier selftests: `Ran 325 tests ... OK`
    - gate: `verdict PASS_WITH_REVIEW_ITEMS`, `findings 0`
  - CI on `a3c9e8fbd` (merges + item 3) was all green, including Fast gates ("Check architecture changes against trusted CI base") and Backend tests, which holds the "Check private Mapper compiled references" (`compiled-ports`) and compiled-controls steps. Round 6's session recorded this in `RESULT.md` (run 41628910753).
- **#666**: `git merge --no-ff 984b00de3`, no conflicts. It adds the login prompt before the publish sheet in `components/tabBar/index.js`. Its 14 tests in `publish-quick-menu.test.js` pass; ledger check and `UI-GATE-0` pass on the merged tree.

## 2. Main package

Measured with the same method as `scripts/preflight-release.js` (all files outside `packOptions.ignore` and outside subpackage roots).

| | Source bytes | Estimate (×0.633) |
|---|---|---|
| Round 6 end (`36fe0270`) | 3,585,891 | ~2217 KiB |
| Final tree | **3,090,788** | **~1911 KiB** |

Target was under ~3,150,000 bytes / 1950 KiB. Round 6's per-file deflate estimate script gives 1.27 MB now (Round 6: 1.44 MB). These are estimates. **A real WeChat reading is not possible in this container (no DevTools). The root must read the main-package size in DevTools before upload.**

What changed (#668's intent, redone on the merged tree, not its stale tree):
- 5 non-tab pages each became their own subpackage with the same path: `pages/privacy`, `pages/agreement`, `pages/deregister`, `pages/team` (detail + join). The 5 tab pages stay in the main package. 157 routes, all unique; every tab entry is still in `app.pages` (test `subpackage-boundary-integrity.test.js`, taken from #668).
- Files that only one subpackage uses moved into it, in two passes (the second pass caught files freed by the first).
- 13 `scene-*` components moved to the subpackage of their main host. Other hosts reach them through `componentPlaceholder` (the async pattern the roam page and merchant home already use for `subpackageMember` scenes). Async edges added: `pages/merchant/index` and `pages/shezhi` → `scene-route-content`; `pages/coop/finance` → `scene-merchant-profit`; `subpackageA` earnings → `scene-member-withdraw-history`; `subpackageMember/myinvite` → `scene-member-invite-history`; `subpackageRoam/poi-detail` → `scene-deep-link`; `scene-deep-link` → `scene-qr-ticket`, `scene-roam-discover`, `scene-asset-earnings`, `scene-asset-income-detail`; `scene-route-content` → `scene-club-edit`, `scene-settings-deregister`. Where these pages call `selectComponent` on such a component it is on a user action and already guarded with a type check.
- `utils/topic-route-preview.js` and `images/icon_map4.png` stayed in main: an unused square component still `require`s the preview synchronously.
- `utils/deregister-flow.js`, `utils/user-invoice.js`, `utils/features/capsule-client.js` and `utils/features/exploration-goals.js` stayed in main. The danger-action gate scans `pages/`, `components/` and subpackage roots, not `utils/`. In a subpackage these four files exposed 5 write endpoints (deregister cancel, invoice title delete, invoice cancel, capsule delete, exploration-goal cancel) that were never registered, and CI's static gate went red. Registering them needs a review of their confirmations, which is outside this item, so they stay where they were. **Owner question:** the gate's blind spot for `utils/` is pre-existing; worth a separate look.
- No asset or feature was dropped.
- Ledger rebuilt: `scripts/baselines/mainpkg-leaves.json` gained 128 file entries (161 total), each with its source blob. The leaves contract now accepts a cross-package component reference only when that JSON declares a placeholder for it; synchronous `require`/`import` across packages still fails (negative controls added).
- Also kept in step: `ci/silent-error-allowlist.txt` paths (11), `action-ledger.json`, `route-matrix.json`, UI baseline keys and digests, the page table in `docs/design/miniprogram-page-cleanup-1009.md`, and test paths. Two tests that pinned privacy/deregister/team to `app.pages` now check the route is registered.
- Preflight ceiling left at 3.00 MB: the tree is under it now, so it is a real ratchet again.
- Preflight BLOCKs now:
  1. The size BLOCK is gone: 「主包未压缩 2.95MB(棘轮 3.00MB)」 is INFO.
  2. Still a BLOCK: 「Action Ledger 动态证据未完成:pending=5635,nonPassed=0」 (needs the dynamic evidence run; unchanged from Round 6).

Every moved path, old → new:

| From (main) | To |
|---|---|
| `behaviors/replay-motion.js` | `pages/play/behaviors/replay-motion.js` |
| `components/cy/chapter-gate` | `pages/publish/components/chapter-gate` |
| `components/cy/chips` | `pages/publish/components/chips` |
| `components/cy/choice-sheet` | `pages/publish/components/choice-sheet` |
| `components/cy/club-pool-cards` | `pages/merchant/components/cy/club-pool-cards` |
| `components/cy/earnings-overview` | `subpackageA/components/earnings-overview` |
| `components/cy/event-attendance` | `pages/activity/components/event-attendance` |
| `components/cy/list-group` | `pages/publish/components/list-group` |
| `components/cy/list-row` | `pages/publish/components/list-row` |
| `components/cy/media-model-preview` | `pages/privacy/components/media-model-preview` |
| `components/cy/scene-asset-earnings` | `subpackageA/components/scene-asset-earnings` |
| `components/cy/scene-asset-income-detail` | `subpackageA/components/scene-asset-income-detail` |
| `components/cy/scene-club-edit` | `pages/club/components/cy/scene-club-edit` |
| `components/cy/scene-deep-link` | `subpackageMember/components/cy/scene-deep-link` |
| `components/cy/scene-member-invite-history` | `subpackageA/components/scene-member-invite-history` |
| `components/cy/scene-member-withdraw-history` | `subpackageMember/components/cy/scene-member-withdraw-history` |
| `components/cy/scene-merchant-profit` | `subpackageA/components/scene-merchant-profit` |
| `components/cy/scene-qr-ticket` | `pages/play/components/scene-qr-ticket` |
| `components/cy/scene-roam-discover` | `pages/play/components/scene-roam-discover` |
| `components/cy/scene-route-content` | `subpackageMember/components/cy/scene-route-content` |
| `components/cy/scene-settings-deregister` | `pages/deregister/components/scene-settings-deregister` |
| `components/cy/scene-share-invite` | `subpackageMember/components/cy/scene-share-invite` |
| `components/cy/segmented` | `pages/publish/components/segmented` |
| `components/cy/sheet-nav` | `pages/publish/components/sheet-nav` |
| `components/cy/stepper` | `pages/publish/components/stepper` |
| `components/cy/swipe-row` | `pages/publish/components/swipe-row` |
| `components/roleBadge` | `subpackageMember/components/roleBadge` |
| `images/del.png` | `pages/gerenziliao/images/del.png` |
| `images/figma-invite-qr.png` | `subpackageMember/images/figma-invite-qr.png` |
| `images/icon_pen.svg` | `subpackageMember/images/icon_pen.svg` |
| `images/icon_qa_person.svg` | `subpackageMember/images/icon_qa_person.svg` |
| `images/icon_search.svg` | `pages/templatedetail/images/icon_search.svg` |
| `images/icon_star.png` | `subpackageMember/images/icon_star.png` |
| `images/nav_msg.svg` | `pages/merchant/images/nav_msg.svg` |
| `images/tpl-tab-game.svg` | `pages/templatedetail/images/tpl-tab-game.svg` |
| `images/tpl-tab-topic.svg` | `pages/templatedetail/images/tpl-tab-topic.svg` |
| `style/club-apply-form.wxss` | `pages/club/style/club-apply-form.wxss` |
| `utils/ai-theme-draft-journal.js` | `pages/publish/utils/ai-theme-draft-journal.js` |
| `utils/club-edit-draft.js` | `pages/club/utils/club-edit-draft.js` |
| `utils/club-phone-call.js` | `pages/club/utils/club-phone-call.js` |
| `utils/content-report.js` | `pages/topic/utils/content-report.js` |
| `utils/earnings-view.js` | `subpackageA/utils/earnings-view.js` |
| `utils/free-explore-bundle.js` | `subpackageMember/utils/free-explore-bundle.js` |
| `utils/player-code.js` | `subpackageMember/utils/player-code.js` |
| `utils/predict-entry.js` | `subpackageB/utils/predict-entry.js` |
| `utils/profit-split.js` | `pages/topic/utils/profit-split.js` |
| `utils/project-progress.js` | `pages/topic/utils/project-progress.js` |
| `utils/refund-rule.js` | `pages/topic/utils/refund-rule.js` |
| `utils/roam-team-operation.js` | `subpackageRoam/utils/roam-team-operation.js` |
| `utils/roam-team-recovery.js` | `subpackageRoam/utils/roam-team-recovery.js` |

Plus the 5 page directories above, which did not move on disk; they only left `app.pages`. The per-file list is in `mainpkg-leaves.json`.

Not verified: the async placeholder loads on a real phone. The rig renders cross-package components synchronously (it ignores `componentPlaceholder`), so it proves the paths resolve, not the loading order.

## 3. Map text follows the phone's font setting

- New `mapFontSize(base, scale)` in `utils/font-scale.js`: `Math.round(base * scale)`, using the existing scale (1 to 1.3, never smaller).
- Used at every map callout or label with a fixed size:
  - `pages/roam/index.js` (5 callouts, one scale read per marker sync)
  - `components/cy/free-map/index.js`
  - `utils/roam-hangout.js`
  - `utils/topic-route-preview.js`
  - found in the sweep: `pages/publish/utils/publish/route-map-view.js` (4 labels: 9/10/11/11) and `pages/searchmap/index.js` (12).
- At the largest setting, 11 becomes 14 px and 9 becomes 12 px.
- Test: `tests/unit/map-callout-font-scale.test.js`. It fails 5/5 on the old code and passes 5/5 now. It checks real marker output at settings 16 and 20.8 for the hangout, route-preview and publish map builders, and that no listed file keeps a fixed number.
- Two callouts had no `fontSize` before and still have none: the roam city-node callout and the searchmap merchant-node callout. They use WeChat's own default. I did not add a size, so their look does not change at normal size.
- Clipping at 1.3: native callouts size themselves to their text, and padding is unchanged.

Not verified: how this looks on a phone. The rig's map stand-in always draws callouts at 11 px, so rig shots cannot show the change. Also, platform POIs in the "passed" state have no callout, so the rig run had no live callout to read back. Proof is the unit test only.

## 4. Two small fixes

- `pages/publish/temp/index.js` `updateCategorySelection`: when at least one category is picked, it also sets `errors.categoryIds` to `''` (same pattern as the title and description inputs). Clearing the selection leaves the error alone.
  - Test `publish-temp-category-error-clear.test.js` drives the real page object. Fails on the old code, passes now.
- Chat list rows (`subpackageB/pages/im/list/index.wxml`): the row now has `aria-role="button" aria-label="打开{{nickname}}的会话"`, like the invite rows above it.
  - Test `im-list-row-aria.test.js`. Fails on the old code, passes now.

## 5. City list and map points

Ported by hand from `round7/pr1273.patch`, keeping its rules:
- Display categories come from the `roam:*` tags only, never from names.
- `type=1` stays a platform place. `type=2` keeps the merchant chain, and shows 「合作商家」 only with a valid `merchantId`.
- Recommended shops are `type=1`, so they never enter merchant check-in, stamps or coupons.
- No coordinates: all 33 places have `coordinate: null`.
- The import tool compiles SQL offline only. It refuses rows that are unconfirmed, cross-city, expired, future-dated, wrong-datum or duplicates. Generated rows are `status=0`, `type=1`, with no merchant, template or coupon.
- No new tables or columns, no backend change. `/api/roam/pois` already returns `address` and `tags`.

Changes from the patch:
- **Nothing new in the main package except a 1 KB helper** (`utils/roam-poi-presentation.js`, which the roam tab needs to classify markers). The 712-line catalog (now a JS module, `subpackageRoam/data/roam-city-catalog.js`, since the mini program cannot `require` JSON), its helper and the guide component live in `subpackageRoam`. The roam page uses them through `componentPlaceholder`. A test checks the main package does not `require` the catalog.
- Place card (the existing `paceCard`): adds the address, the reviewed reason and the source hosts as plain `.pc-body` lines, with no new captions.
  - Reason and sources come from the catalog, matched by the importer's seed tag `roam:seed:v1:<id>`. Without a seed tag the card shows nothing extra.
  - The DB `description` of platform places is still not shown (the patch's rule).
  - A small `subpackageRoam` component looks the note up and hands the text to the page. The page ignores a late answer that arrives after the card changed.
- Entry: an icon button (`discover-shop`, label 「按城市浏览街道、推荐店铺和公园」) next to the avatar on the roam home. No visible caption. I dropped the patch's intro line 「从一段街巷、一家店、一片绿地开始。」 (no helper captions), the extra category line on the card, and the native `<picker>` (replaced by `cy-dropdown`, as the repo's picker rule requires).
- Three catalog `visitNote` lines contained 「推荐」, which fails the roam redline R1 for files in `subpackageRoam`. They were reworded with the same meaning (`2dbc98fd0`).
- Tests: the patch's `roam-city-catalog.test.js`, ported to the new paths, plus 3 new tests. **13/13 pass.** No SQL was touched, so no migration safety run was needed. No production SQL was generated.

Rig proof (throwaway DB only, shots in `round7/shots/R5-*`):
- Two fictional places were inserted directly into the rig's `roam_poi`:
  - 「测试绿地甲（虚构）」 with `["roam:park","roam:seed:v1:sh-shanyin-road"]`
  - 「测试小店乙（虚构）」 with `["roam:shop","roam:seed:v1:bj-zhengyang-books"]`
  - Both use test coordinates next to the rig's default GPS. **No coordinates were made up for the real 33.**
  - To make the reason and sources show, their seed tags point at two real catalog entries. So the card text under these fictional names is that real entry's reason and source hosts.
  - They are `status=1` so they are live in the rig. The importer itself writes `status=0`.
- The roam page loads them as ordinary platform POIs:
  - `cat: park` with label 公园绿地
  - `cat: landmark` with label 推荐店铺
- Walking near (the page's own `_checkNear`) opens the existing place card with the address, reason and source hosts (`R5-card-*`).
- Platform POIs stay under fog until found (existing behaviour). I inserted `roam_poi_found` rows for player 9004 so they load as found. With fog switched off, the rig map stand-in draws both markers (`R5-map-two-pois.jpg`). Tapping a marker opens the same card (`R5-card-from-marker.jpg`).
- Guide: list, park filter and detail with copy actions (`R5-guide-*`). Entry button next to the avatar (`R5-roam-entry.jpg`).

Not verified:
- 「确认到点」 over HTTP. The rig's GPS seam did not build a server roam session, so the card answered 「先移动一小段，建立本次漫游记录后再确认地点。」.
- The real native map and callouts, and the place card at the largest font.

## 6. Verify

- `node chengyinhub-xcx/tests/lint/ui-integrity.js`: `UI-GATE-0:PASS` on the final tree.
- `build-action-ledger.js --check`: PASS, 5635 controls. Each control change was rebuilt and its count set in `action-ledger.test.js`.
- **Full mini unit suite on the final tree (`de56cfa56`): 15832 tests, 15832 pass, 0 fail.**
- Every check that `ci/xcx-check.sh` runs, run one by one without the self-tests that write into the repo (the brief forbids running that script): ledger `--check`, route-matrix `--check`, `ui-integrity --warn-as-error`, component-path-resolve, ui-theme units and theme, danger-confirm, roam redline, `ds-hardcode-gate.sh --ci` (gate only, not its selftest), ui-validity, component-attr, wxml-handler, setdata-dead-field, both a11y lints, undefined-token, component-wxss-selector, js-scope, silentError, API existence, forbidden hosts. **All PASS** on the final tree. The danger-confirm `--selftest` (which writes temp files) was run in a throwaway worktree: 自证通过.
- Also run locally and passing: `roam-redline-gate.js` (with selftest), `forbidden_hosts_gate.py` (with test), `silent-error-gate.js`, `api-existence-gate.js`.
- Backend: no Java, mapper or SQL changes in this round. The merge brings only CI architecture files. Backend tests run in CI.
- CI: see the CI section below.

### Rig

- Stood up from `claude/cloud-walk-20261101-rig` plus the club lane's `harness-patches.diff`, with `up.sh` pointed at this branch (`0d3b01f99`). The mini bundle was rebuilt for the roam shots (`a17607680`) and again for the logins and moved-group pages on the final `de56cfa56`.
- Schema plus seed passed (457 tables). The jar booted in 26 s.
- Logins, 0 page errors each (`round7/shots/L-*`):
  - player 9004 → home
  - merchant 9002 → 商家工作台 「探店日样板咖啡」
  - club lead 9003 → club 7001 「城瘾跑团」 (needs `?id=7001`)
- One page from each moved group, all with 0 page errors (`round7/shots/M-*`):

  | Group | Page |
  |---|---|
  | agreement | `pages/agreement` |
  | deregister | `pages/deregister` (deregister scene) |
  | team | `pages/team/detail` |
  | scene-route-content | `pages/shezhi` with the profile scene open |
  | subpackageA scenes | earnings |
  | invite-history | `subpackageMember/myinvite` |
  | merchant-profit | `pages/coop/finance` |
  | scene-club-edit | `pages/club/edit` (renders its form; the page also shows 「当前账号无权进入该俱乐部」 for 9003 with `?id=7001`, which I did not compare against the old tree) |
  | publish components | `pages/publish/temp` |
  | images | `pages/gerenziliao` |
  | deep link | `subpackageRoam/poi-detail` |

- `pages/privacy/index` opened as a launch path jumps to home with one rig page error. The Round 6 tree (`36fe0270`, before any move) behaves the same in this rig, so it is not caused by this round. I did not investigate it further.
- Only rig harness 404s (favicon, banner gifs) in the console.

## CI state

**All CI is green on `de56cfa56`** (the final code commit), PR #723:
- GitHub CI run 41654907060: all 31 jobs pass. That covers Detect changed areas (incl. the roam redline gate), gitleaks, Fast gates (incl. the architecture gate against the trusted base), Backend tests (incl. compiled-ports / private Mapper compiled references), Migration layout, Migration safety, Admin UI build and E2E contract, SBOM + dependency scan, Mini program static gates and static checks, and all 20 mini unit shards.
- Also green on that SHA: Funds MySQL IT (41654906990), Mini followup focused regression, Isolated authenticated Redis recovery.

Red along the way, all fixed before the final run:
- `d3c115dc4`: roam redline R1 on three catalog notes (fixed in `2dbc98fd0`).
- `a17607680`: the danger-action gate selftest baseline (four utils, fixed in `de56cfa56`).

This result file is pushed after that run, as a docs-only commit.

## Needs the root

- Read the real main-package size in WeChat DevTools before upload. Estimate: ~1911 KiB.
- On a phone: the async-loaded scene components (first open of settings, merchant home scenes, coop finance, deep-link scenes), the map text at the largest font, and the place card and city list.
- Coordinates for the real 33 places, through the import tool and the normal migration review. Nothing was generated here.

STATUS: COMPLETE
