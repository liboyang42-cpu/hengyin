# Round 7: launch fixes on top of the merged test branch

Work on `integ/launch-test-20261103`. The Round 6 integrator is still merging, so first wait until `docs/delivery/walk-fix-20261102/integ/RESULT.md` on that branch has a `STATUS:` line. Poll with `git fetch` every few minutes using a background wait; don't push before then.

Never push to master. Never merge a PR. Never change production. No secrets or word list in the repo. Follow root `AGENTS.md` and `chengyinhub-xcx/AGENTS.md`. Don't run `ci/xcx-check.sh` or `ds-hardcode-gate-selftest.sh`. Commit each item separately and push after each one. Keep existing UI as the baseline: no new captions, no restyling beyond what's named.

## 1. Merge what Round 6 left out

- Merge `claude/arch-owner-l8-20261103` (real merge, no squash: `ci.yml` pins verifier `f12852845`, which must stay reachable). On its own this branch fails `Backend tests`, because it registers classes that only exist with #721. On the merged tree the architecture gate and `compiled-ports` must both pass. Report the output.
- Merge #666 `feature/guest-publish-login-20261028` at `984b00de3`: a guest tapping publish gets a login prompt, not 「发布信息尚未就绪」.

## 2. Main package must fit WeChat's limit (launch blocker)

WeChat's main-package limit is 2048 KiB. Our own preflight "zip" number is NOT what WeChat measures. Real results: source bytes 3,428,372 were rejected at 2137 KB, and 3,220,509 were accepted at 1990 KiB, so WeChat's size is about 0.633 x uncompressed main-package source bytes. Before this round the branch was about 3,571,303 bytes, or about 2207 KiB.

Target: under 1950 KiB by that ratio, about 3,150,000 source bytes, measured with the method `chengyinhub-xcx/scripts/preflight-release.js` uses, after every other item in this round is in. Start from #668 `feature/mini-subpackage-budget-2026102` at `6a9a82b3b`, which conflicts with master, so apply its intent rather than its stale tree. All 157 page paths, tab entries, behaviour and permissions stay the same. Non-tab pages, components only one subpackage uses, and page-specific images and tools move out; the 5 tab entries stay. Don't drop assets or features to make room. Rebuild the ledger. Update the preflight ceiling only if it still reflects a real ratchet. State the final bytes and estimate, and the two preflight BLOCKs as they then stand.

Also try a real size reading if the rig can get one; otherwise say the root must read it in DevTools.

## 3. Map text follows the phone's font setting

Everything else follows the phone's font size through `utils/font-scale.js` (scale 1 to 1.3, never smaller). Map bubbles are native and don't read `--cy-type-scale`; their size is a fixed `fontSize: 11` in JS, so they never change. Sites: `pages/roam/index.js` (5), `components/cy/free-map/index.js`, `utils/roam-hangout.js`, `utils/topic-route-preview.js`. Sweep for other map `callout` or `label` text. Use the existing scale: `Math.round(11 * scale)`. Keep bubbles from clipping at 1.3. Test that fails on the old code. Rig shots at normal and largest settings if the rig can set `fontSizeSetting`.

## 4. Two small fixes

- `pages/publish/temp/index.js` `updateCategorySelection`: picking a category must clear the 「请选择至少一个玩法类别」 red error (`errors.categoryIds`). Test that fails on the old code.
- Chat list rows: add the missing 「打开…会话」 screen-reader label (same pattern as nearby rows).

## 5. City list and map points (11 cities, 33 places)

The owner wants these places on the map. The reviewed patch from a private draft is `docs/delivery/walk-fix-20261102/round7/pr1273.patch` on `origin/claude/walk-fix-20261102-brief`. Its base is old, so port by hand. Read its `docs/roam-city-catalog-20261023.md` first.

Keep its rules exactly: display categories come from `roam:*` tags; type=1 stays a platform place; recommended shops never enter merchant check-in, stamps or coupons; no guessed coordinates; the import tool only writes SQL offline and refuses unconfirmed, cross-city, expired or wrong-datum coordinates; generated rows start `status=0`.

Changes from the patch:
- `pages/roam` is a tab page in the main package. The 712-line catalog data, the guide component and its helper must NOT add to the main package. Load them from a subpackage. Item 2's budget includes this.
- Places must be able to appear on the existing roam map as ordinary platform POIs once rows exist, and open the existing place card, showing address, reason and sources without new captions. Prove this in the rig with 2 fictional places inserted in the throwaway DB, with `roam:park` and `roam:shop` tags and a map screenshot. Don't invent coordinates for the real 33.
- Do not generate production SQL. Coordinates for the real 33 come later from the root.

Run the patch's unit tests after porting, plus migration safety if anything touches SQL.

## 6. Verify

All CI jobs, `ui-integrity.js`, `build-action-ledger.js --check`, full mini unit tests, backend tests for changed modules. Rig: boot from the final tree, log in as player, merchant and club lead once. Open one page from each moved group to prove moved pages still load.

## Result

Write `docs/delivery/walk-fix-20261102/integ/ROUND7-RESULT.md`: per item what changed, proof, and what's unverified; the main package numbers; every path moved; CI state. Plain words. End with `STATUS: COMPLETE` or `STATUS: PARTIAL - <reason>`.
