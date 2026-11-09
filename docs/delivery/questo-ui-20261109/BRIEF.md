# Questo UI + walkthrough wave, 2026-11-09: shared brief (binding)

The owner asked for every remaining code gap and UI change to be implemented, shown to them as an HTML review page before anything merges. Claude (local session) is the lead and integrator. You are one cloud lane. Your lane's scope is in `LANES.md` (this folder). Read this brief, `LANES.md`, root `AGENTS.md` and `chengyinhub-xcx/AGENTS.md` before starting.

## Bases

- **A lanes (walkthrough):** branch from `origin/master` at `2bb811437` or later. Master now contains the former launch-test integration (#723).
- **B lanes (Questo UI):** branch from `origin/integrate/questo-ui-20261110`. It is `feature/questo-backend-20261107` (#731, the Questo backend) merged with master. If that branch does not exist yet when you start, poll `git ls-remote origin integrate/questo-ui-20261110` every 5 minutes for up to 3 hours. Meanwhile read the spec and design your screens. Never base B work on plain master.
- **The UI spec for B lanes:** `docs/questo-teardown/UI优化施工清单.md` on that base.
  - It is the only contract source for fields, states, error codes and §12 "never show as usable" rules.
  - Read §二 (rules), §十二 (forbidden entries) and §十三 (API compatibility) in full before touching code.
  - Where the doc names a wrong path:
    - `pages/play/theme-backpack.js` is really `pages/play/utils/theme-backpack.js`.
    - The node game catalog is `pages/publish/utils/publish/node-game-catalog.js`.
    - The support matrix is `pages/publish/utils/publish/gameplay-support-matrix.js`.
    - Admin finance pages live under `views/business/finance/`; audit is under `views/business/audit/`.

## Hard limits

- **No production access.** No production, VPS or private-repo access, and no deploys.
- **Migrations.** New migrations go in new `chengyinhub-admin/sql/migration_20261109_*.sql` files, registered in `ci/migration-dependencies.json`. Never edit an existing migration.
- **PRs.** Work on your own branch `claude/qx-<lane>-20261109`. Open one **draft** PR into your base branch (A lanes into `master`, B lanes into `integrate/questo-ui-20261110`). Never merge it and never mark it ready; the owner reviews results first.
- **Money and permissions.**
  - Never set payment, refund or settlement state directly.
  - Never weaken ownership, scope, permission, identity or refund checks. Every new write endpoint checks owner and scope.
  - Leave these gates and switches untouched: `assertCollectible`, `cash-enabled`, `apple-iap.enabled`, `workshop.wechat-app-enabled` and channel providers.
- **Forbidden scripts.** Do not run `ci/xcx-check.sh` or `ds-hardcode-gate-selftest.sh`; their selftests write into the repo.
- **The repo is public.** No secrets and no real user data. Grep your diff for keys and tokens before each push.
- **Shared files.** The C00-reserved mini-program files (`app.js`, `app.json` subpackage lists, `scripts/uiaudit/action-ledger.json`, ui-integrity baselines) are shared. Change them only when your item needs it, keep the edit minimal, and say so in the PR.
- **Stay in your lane.** Do not touch other lanes' files. If you need something from another lane, write it under "Cross-lane" in your RESULT.md.

## UI rules (owner's standing rules: follow them exactly)

- **Baseline and scope.** The existing UI is the baseline. Change only the element an item names, and keep the rest pixel-identical. New screens copy the structure of the nearest existing page and the kit: `--cy-*` tokens, `components/cy/*`, existing row, card and sheet classes.
- **Domain colours.** Player screens are dark, merchant screens are light, and the theme domain keeps its forced scheme.
- **Copy.**
  - No subtitles, helper captions, taglines or explanatory banners; if a screen needs one, fix the layout.
  - Use plain Chinese with these terms: 帖文, 节点, 管理员, 商家.
  - Never reword messages mirrored from the backend.
- **Server facts drive controls.**
  - A control appears only when the server sends the fact that enables it.
  - Unknown is never shown as 0. Recorded or accrued is never shown as paid or withdrawable. SDK success is never shown as paid.
  - Anything §十二 forbids shows only an unavailable reason, or nothing.
- **Sheets and pages.** Sheets use the peek/half/full sizes (max 30/60/80% height, fixed when content changes). Full-page flows the owner called "pages" must be real pages, not sheets. Destructive rows are red. Input errors show as red text under the field, never a pop-up.
- **Switches** are green, using the `cy-switch` component (#0E9E73).
- **Motion.** Respect `prefers-reduced-motion`. Prefer transitions to new keyframes. No shake, explosions or loud colour.
- **Tap targets** are at least 88rpx.
- **Main package size.** The mini-program main package must stay under 2048 KiB; WeChat's count is about 0.633 × uncompressed source bytes. Put new pages in subpackages.

## Evidence (required)

The owner judges screenshots, not code. For every visible item, commit the following to your branch under `docs/delivery/questo-ui-20261109/<lane>/`:

- **Screenshots.** `shots/<ID>-before.jpg` and `shots/<ID>-after.jpg`: 390 px wide, JPEG quality about 70, the same page and state in both. New screens get `-after` only, plus each state the spec requires: normal, empty, unauthorized, expired or unavailable, and unknown result. Inspect every shot yourself for empty headings, overlaps, clipped text and contradictory numbers.
- **The rig.** Real pages run in the walkthrough rig: `docs/delivery/cloud-walk-20261101/rig/RIG.md` on branch `claude/cloud-walk-20261101-rig`. Run its `up.sh` from a separate worktree and point the candidate at your branch. Take `tools/harness-patches.diff` from `claude/cloud-walk-20261101-club`. B lanes run the backend from their base, so new endpoints answer for real. Keep rig changes out of product code. If the rig cannot reach a page, say so. Never redraw a page as a substitute.
- **RESULT.md.**
  - One row per item: ID, status (`fixed` / `already fixed` / `needs owner` / `not done`), what changed, files, how it was verified, and what stays unverified on a real phone.
  - Last line: `STATUS: COMPLETE` or `STATUS: PARTIAL - <what is missing>`.
  - Finish without waiting for answers. Put owner questions under `needs owner`, each with the default you implemented.

## Verification gates

- **Tests.** Every fix gets a test where practical:
  - Mini-program: `node --test chengyinhub-xcx/tests/unit/<x>.test.js`.
  - Java: `mvn -q -pl <module> -Dtest=... test` (for `@Nested`, also select `Outer$Nested`).
  - Admin: `cd chengyinhub-ui && npm run build:prod` must pass.
- **Ledger and UI gate.**
  - Rebuild a drifting action ledger with `node chengyinhub-xcx/scripts/uiaudit/build-action-ledger.js --write`.
  - `node chengyinhub-xcx/tests/lint/ui-integrity.js` must print `UI-GATE-0:PASS`.
  - Do not add baseline entries; fix the code instead.
- **SQL.** Mapper SQL must pass the Druid wall: use `CAST(x AS BINARY)`, never `BINARY a = BINARY b`, and no always-false fragments.
- **Commits and pushes.** Commit messages are Chinese with a type prefix. Push after each item, so a usage limit loses little.
- **PR body** needs a `## 影响分类` section (`- ③ 迁移：…` if you add a migration).
