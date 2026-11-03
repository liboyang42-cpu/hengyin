# Round 7B: approved page polish on the test branch

Work on `integ/launch-test-20261103`. Round 7 is working on this branch first, so wait until `docs/delivery/walk-fix-20261102/integ/ROUND7-RESULT.md` on that branch has a `STATUS:` line. Poll with `git fetch` every few minutes using a background wait; don't push before then.

Never push to master. Never merge a PR. Never change production. Don't run `ci/xcx-check.sh` or `ds-hardcode-gate-selftest.sh`. Follow root `AGENTS.md` and `chengyinhub-xcx/AGENTS.md`. These are small edits to existing UI: no new design, no new captions, no new animation, no keyframes. Input errors stay red text under the field. Commit each item separately and push after each.

The main package must stay under the Round 7 target (about 3,150,000 source bytes). Report the final number.

## 1. 积分明细

Merge `claude/page-polish-points-20261103` (the page commit is `dca92673613e`, shots and report at `docs/delivery/walk-fix-20261102/points/`). Then fix:
- The same month must not appear twice. Group by the row's time, whatever the id order, and keep rows inside a month newest first. Test that fails on the old code, using a row with a lower id and a later time.
- Hide rows whose points change is 0 (the 「注册赠送 0」 row), everywhere in the page, including the empty-state and paging decisions. If every row is hidden, the page shows its empty state. Test.

The empty state stays at its current size. Gains and uses keep their colour.

Re-shoot `top`, `bottom`, `long-reason` and a paged state after the fixes, into `docs/delivery/walk-fix-20261102/points/final/`.

## 2. Polish round 3

The findings are in `docs/delivery/page-polish-20261102/round2/final/RESULT.md` on `origin/claude/page-polish-r2-final` (with `ROWS.md` beside it). Find each row there, and apply exactly:
- Merchant press values on ledger, marketing, pricing and merchantapply.
- The `publish/temp` grid (13.5 inset and 14.6 radius findings).
- Merchant customer page's two left edges (title 20.8 against search 12.5).

Do not take edits from the `claude/page-polish-r2-{shared,merchant,player-home,player-theme,player-me,club-publish}` lane branches wholesale. They hold edits that overlap walk-fix.

## 3. Section 4 of that RESULT.md, as recommended

Apply the recommendation written on each item:
- 2 exempt scrim classes in the press test, then N-03 and `.rolemask`
- 3 club title gap
- 6 `.cy-refresh-float--pill`
- 7 `cy-card` media variant at `2xl`. Apply it to activity-list cover cards only. Do not sweep other cards.
- 9 「−¥15.44」 through one money-format helper. Check every caller, including ledgers, batch detail and refund lines.
- 10 buy-sheet underline easing
- 11 `scrollToFirst` 350 ms
- 12 the two .88 press changes only
- 13 M-14 and M-15, updating shot-matrix C60
- 14 light-theme ghost button surface on merchant cards. Shoot merchant pages after this; it's a shared token, so check nothing else changed.
- 20 「—」 before load on counters, and money always with 2 decimals

Leave unchanged, as recommended: 1, 4, 8, 15, 16, 17, 19. Item 18: add nothing. Item 5: later, not now.

If an item conflicts with a lane's approved result on this branch, keep the lane's result and report it. Lane results are the owner's approved work.

## 4. Verify

All CI jobs, `ui-integrity.js`, `build-action-ledger.js --check`, full mini unit tests. Rig before/after shots for items 3, 6, 7, 9, 13, 14, 20 and the three round-3 items. Look at each shot yourself and report anything odd.

## Result

Write `docs/delivery/walk-fix-20261102/integ/ROUND7B-RESULT.md`: per item status, files, proof, conflicts with lane work, what's unverified. Shots in `docs/delivery/walk-fix-20261102/integ/polish-shots/` (JPEG under 200 KB). Plain words. End with `STATUS: COMPLETE` or `STATUS: PARTIAL - <reason>`.
