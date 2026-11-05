# Pages handoff

Product commit: `6d8266ded45db75e658bf036a22d91bfd9414d8e`, pushed to `integ/launch-test-20261103`.
This is source and test progress. It is not a release or phone acceptance.

I waited from 07:10 to 07:35 UTC on November 5, fetching at five-minute intervals. The cloud result and `claude/wip-k9` were absent at the cap. The coordinator told me to continue. The cloud k9 commits and `ROUND12-K9-RESULT.md` then arrived during rebase. I read that report, kept its work, and resolved the overlap instead of redoing its LOW sweep. The cloud report still contains full-suite/CI/status placeholders; those are not proof of completion.

## What this worker added

| Item | Result | Files and proof | Still unverified |
| --- | --- | --- | --- |
| Theme template card | Template copies now appear under 模板 and are hidden from 主题. The original theme stays visible. A template opens the existing theme-template detail; merchant scope is preserved. | `ApiProjectController`, `CmsTopicMapper` Java/XML, `subpackageA/pages/myproject`. The existing owner-scoped project read gets one batch query of template IDs, with owner and deleted-row filters. No new table, endpoint, service change or per-card query. Node tests cover original versus copy, navigation, scope, permission loss and logout. Java controller tests were added/adjusted. | Real save-as-template through the current backend and browser/phone. The existing 200-project list cap still applies. |
| Participation detail | Kept the cloud's 链接已失效 / 返回 state. Added the missing `successStatusAbnormal` handler and shared failure handling for HTTP/business/network refusals. Transient failures remain retryable. Old-account, old-record and late callbacks remain ignored. | `scene-member-participation-detail/index.js`; tests actually invoke all three failure channels and stale responses. Cloud `r12-k9-pages` contracts also pass. | Current backend/phone walk. This worker viewed the cloud's before/after screenshots, but did not take new ones. |
| Pricing page role contract | Club/player pricing stays dark and merchant pricing stays light. The page now uses scoped light CSS and explicitly declares both root themes. The UI domain is shared, with a new test requiring both themes and scoped CSS. | `pages/topic/pricing/index.wxml`, `.wxss`, `ui-theme-unit-lint.js`, `merchant-theme-route-contract.test.js`. No pricing service or money calculation changed. | Current rig and phone appearance. |
| CI route matrix failure | Rebuilt the matrix from the actual merged app/scene scan. Its missing change was the map-search role becoming merchant. | `scripts/uiaudit/route-matrix.json`; `route-matrix-freshness.test.js` passes, including its stale/mutated inventory checks. | Final complete CI run. |
| Action ledger | Rebuilt on the merged source. Cloud k9 had 5,727 controls; the template card adds one real tap: 5,728. Local taps 2,511, local event contracts 2,774. Existing tests were updated only for these measured counts. | `build-action-ledger.js --check` and all 46 action-ledger tests pass. No evidence entries or gate waivers were fabricated. | Dynamic business evidence remains pending. |

## Validation

- 322 focused Node tests passed together after the cloud rebase: myproject, participation deep link, cloud k9 pages, route matrix, merchant theme roles, leaderboard and team detail.
- 46/46 action-ledger tests passed on the merged files. `build-action-ledger.js --check`: PASS, 5,728 controls.
- `ui-integrity.js`: UI-GATE-0:PASS. No baseline allowance was added or relaxed.
- The installed pre-commit hook compiled my staged backend snapshot on the test VPS successfully. No Java, Maven, Docker daemon, MySQL or Redis was started on the Mac. The hook compilation used `-DskipTests`; the new Java tests still need CI.
- Gitleaks scanned the reviewed public patch: no leaks. `git diff --check`: clean.
- Final release preflight on the product tree: main package 2.98 MB, below the 3,145,728-byte cap. It exits 1 for the single remaining release block: all 5,728 dynamic action-evidence entries are pending. Source checks and package size do not clear that block.
- CI started on the product commit. At handoff it was pending/in progress; no green claim is made. Runs include https://github.com/liboyang42-cpu/hengyin/actions/runs/41991090432 and https://github.com/liboyang42-cpu/hengyin/actions/runs/41991090428. The coordinator owns the final clean CI run.

## Cloud work retained

Cloud commits `969265a59`, `7fe672d98`, `9cdb9af4a`, `95910add7` cover the player LOW sweep, guest/role exits, coupon form/status, AI NPC status, wording, copy reuse and field labels. Earlier k9 commits `918c6380c`, `5a9a5975e`, `fe10e9cc5`, `8d05beed7`, `393e079f1` cover copied tickets, inactive-member counts, transfer feedback, join rejection and ended sessions. Read `ROUND12-K9-RESULT.md` for their per-item evidence. Those commits and their screenshots were preserved. Their old rig and jar are not a verification of this final source.

## Remaining

- NOT WALKED: this worker's new template classification, participation callback handling and pricing appearance. No verified current rig/tunnel was available. I did not use 8099/8132, start a second backend, open DevTools, or access production. No phone testing, QR service, camera, native font/safe-area or live payment acceptance is claimed.
- Player pages: monthly review misses completed themes under 路线; the home 玩家 chip is pinned by an earlier owner design; invoice-title classification is pinned and the feature is off. The new k9 report also leaves the ticket pill, another player's coupon and the buy-ticket invite path without a rig walk. Play-page banner/stop-sheet layout remains the play worker's scope.
- Organiser/merchant pages: p2 N-14 remaining invite/reply/sender/timing details, N-16 customer tags and N-17 remaining duplicates; p3 N18 editor preview overlap/sample; missing-param helper lines on club edit, join requests and group code. Data-filled game-node, cooperation settlement, batch detail and prediction pages still need the appropriate journey fixtures. Former-owner settlement access belongs with the coordinator's money/product decision.
- Content screens: j5 N5 status-bar link and blocked-text retry; N6 field-level error placement and summary covering the ticket button; N11 merchant publish deep link without scope; carried L2/L5/L6/L7/L8/L10. These are still listed by the cloud lane, not silently marked fixed here.
- j6: club settlement gate not re-walked; stale ticket inventory needs real show-lifecycle proof; stamp-camera without a camera feed remains unverified. Source currently starts a live camera with no image, so it must not be made an invalid-link page merely because the rig has no camera.
- Owner/write-contract decisions, unchanged as instructed: coupon amount/type/default validity; verifier identity storage; invite without `shareMode`; early redemption; moderation draft-hold semantics; hit-log transaction isolation; activity-bound-game safety recheck; notice/disclosure/consequence text and the long-press-card design. These are not presentation-only fixes.

Reserved service files and `danger-actions.js` were not changed by this worker. No push to master, force push, PR merge, deployment, migration or production access occurred.

STATUS: PARTIAL - source fixes and local checks are complete for the additions above; listed page leftovers, final CI and current-rig/phone acceptance remain.
