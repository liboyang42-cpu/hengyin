# Walkthrough fix wave, 2026-11-02: shared brief (binding)

The owner reviewed the 2026-11-01 walkthrough findings and asked for fixes through cloud agents, with UI changes shown for their review.

Finding details and screenshots, by ID prefix (P- = player, M- = merchant, C- = club):
- `docs/delivery/cloud-walk-20261101/<player|merchant|club>/REPORT.md`, on branches `claude/cloud-walk-20261101-<player|merchant|club>`.

## Hard limits

- No production, VPS or private repository access. No deploys. Write migrations as new `chengyinhub-admin/sql/migration_20261102_*.sql` files, register them in `ci/migration-dependencies.json`, and never run them against anything but your own throwaway database. Never edit an existing migration.
- Work on your own branch from `origin/master`. Open one **draft** PR to master. Never merge, never mark ready. The owner reviews results first.
- Do not change the online-payment gate `assertCollectible` (`CmsRegistrationServiceImpl`). It is a funds and payment-qualification rule; the owner has not decided to open it.
- Never set `payment_status` directly, and do not weaken refund, ownership or scope checks. Every new write endpoint checks owner and scope.
- Do not run `ci/xcx-check.sh` or `ds-hardcode-gate-selftest.sh`: their selftests write into the repository.
- No secrets, no real user data. The repository is public. Grep the diff for keys and tokens before each push; use gitleaks if you can get it.
- Do not reword messages mirrored from the backend unless your item is that message. Do not stray outside your lane: if you need something from another lane, say so in the PR.

## How to read the owner's verdicts

- `通过`: do the proposed fix.
- `要改` **with no note**: the owner marked long runs of small items this way. Read it as "yes, change this": do the proposed fix.
- `要改` **with a note**, or any note: the owner's words override the proposal. Quoted below verbatim.
- Before fixing anything, reproduce or confirm it in current code. The reports tested `a1d30fb2`; #701, #703, #704, #707, #708 merged later, so some may already be fixed. If so, say that in the PR with the evidence, and do not change it.
- The reports' `Likely code location` entries are agents' guesses. Treat them as leads.

## UI rules (owner's standing rules)

- The existing UI is the baseline. Make small edits; do not restyle or redesign beyond what an item names. Where the owner said a page or card needs design work, fix that named element, reuse the existing kit (`--cy-*` tokens, existing components and row styles), and keep the rest as it is.
- Player views are dark, merchant views are light.
- No sub-descriptions or helper captions unless the item asks for one. Terms: 帖文, 节点, 管理员, 商家. Plain Chinese.
- No screen shake, explosions, loud colours or sound effects. Respect `prefers-reduced-motion`. Prefer transitions to new `@keyframes`.
- Sheets use the peek/half/full sizes. Tap targets at least 88rpx.

## Review evidence (required for every visible change)

The owner judges what they see, not the code. For each UI item, commit to your branch under `docs/delivery/walk-fix-20261102/<lane>/`:
- `shots/<ID>-before.jpg` and `shots/<ID>-after.jpg`: 390 wide, JPEG quality about 70, the same page and state on both. Look at each one yourself: empty headings, overlaps, clipped text, contradictory numbers.
- `RESULT.md`: one row per item: ID, status (`fixed` / `already fixed` / `needs owner` / `not done`), what changed, file, how verified. Say what stays unverified on a real phone. Last line `STATUS: COMPLETE` or `STATUS: PARTIAL - <what is missing>`.

The real pages run in the walkthrough rig: `docs/delivery/cloud-walk-20261101/rig/RIG.md` on branch `claude/cloud-walk-20261101-rig` (its `up.sh` pins a candidate commit: point it at your branch). The club lane fixed two runtime bugs in `rig/mpweb`; take them from `tools/harness-patches.diff` on `claude/cloud-walk-20261101-club`. Keep rig changes out of product code. Say honestly when a screenshot is from the rig rather than a phone. If the rig can't reach a page, say so; never redraw a page as a substitute.

## Verification and gates

- Every fix gets a test that fails on the old code where practical: `node --test chengyinhub-xcx/tests/unit/<x>.test.js`; Java `mvn -q -pl <module> -Dtest=... test` (with `@Nested`, also select `Outer$Nested`). The cloud environment can run Maven.
- If a backend behaviour crosses roles, prove it over real HTTP in the rig with a database read-back, not only with a unit test. Mapper SQL must also pass the app's Druid wall: avoid `BINARY a = BINARY b` and always-false fragments like `1 = 0`.
- If `chengyinhub-xcx/scripts/uiaudit/action-ledger.json` drifts, rebuild it with `node chengyinhub-xcx/scripts/uiaudit/build-action-ledger.js --write` and review the diff. `node chengyinhub-xcx/tests/lint/ui-integrity.js` must print `UI-GATE-0:PASS`. Do not add ui-integrity baseline entries; change the code instead.
- PR body needs a `## 影响分类` section. Commit messages are Chinese with a type prefix. Push after each item so a usage limit loses little.
- Finish without waiting for answers. Put anything that needs the owner under `needs owner` in `RESULT.md`, with a default you recommend.

## What is already confirmed in code (commit 014fc0d83)

- **C-03:** `ClubGatherArrivalPolicy.validate` needs `cms_activity.latitude/longitude`, and neither `ClubEventOpsServiceImpl` nor `ApiClubController.openSession` sets them.
- **C-04:** `ApiClubLeadController:226` deliberately refuses the old lead entry when the director runtime owns the session. The director workbench (`pages/club/topic-detail/director.js:795`) does have `UNLOCK_CHAPTER`, shown only when `!_genericControl` and `chapterOptions` is non-empty. The walker saw it empty. Find out why before changing anything; the play-page item points at a dead path for these sessions.
- **P-04:** `/api/play/journey/ending` requires a server session (`PlayJourneyStoryServiceImpl.requireSession`, 「本局尚未开始」), and `_loadLifeEnding` silently finishes without one. Not confirmed: why no session existed, or how a 尾声 ending chapter is meant to play. Establish that first; the walker's "only the menu hold starts it" may be incomplete.
- **P-01 has two gates:** `ProductRoleServiceImpl:118` (`SALE_ORGANIZER_ONLY_FREE_EXPLORE`) stops the sale review; then `assertCollectible` refuses payment unless the theme is platform-owned. Only the first is in scope.
- **M-05:** the owner remembers a green-tick bottom sheet for redemption success. `pages/merchant/index` has no icon in `.verify-result` at this commit; check git history before building one.
