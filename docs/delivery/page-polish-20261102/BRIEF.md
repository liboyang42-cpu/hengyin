# Page polish: shared brief (2026-11-02)

Base branch: `integrate/page-polish-20261102` (master a1d30fb plus the shared kit). Mini program: `chengyinhub-xcx/`.
The owner wants details refined, not layouts overhauled: motion, page structure, corners and spacing.
The owner reviews every lane's result before anything is merged.

## Hard limits (all lanes)
- Push only to your own branch `claude/page-polish-20261102-<lane>`, created from the base branch. No PRs, no merges, no master.
- Public repo: no secrets, production hosts or real user data. Screenshots use the rig's dummy data only.
- Never contact production, the VPS, databases other than your own rig, or payment services.
- Backend Java and SQL are out of scope. If a fix needs them, report it instead.
- Push early and often so a usage-limit stop loses little.
- Do not ask questions. Decide, and note assumptions in your result.

## Owner rules
1. Existing UI is the baseline. Smallest in-place edit. No new page designs, parallel components or restyle passes. Main titles
   (`cy-nav-bar` + `cy-page-title`) stay.
2. No secondary labels or helper captions unless the second line carries real data.
3. Merchant side always light, player side always dark.
4. Fix an animation's timing rather than deleting it. Do not change approved work: overlay spec (sheet levels, exit handoff,
   toast.undo), v13 motion screens (roam home, map, theme cover/detail/tabs, buy sheet, checkout, reveal, wallet, medal),
   store-advisor sheet morph, enamel badges, values marked `ds-ok` with an owner ruling.
5. Motion: simple, high quality, a little fast. Money numbers land slow-fast-slow. Page shape never changes while loading. No
   bounce on popups. No new artwork or mascots.
6. No-data shows "—", not a fake 0 (marketing pages keep 0). Do not reword text that mirrors backend messages.

## Decisions still open: do NOT implement
T-01 home tab bar visibility; A5 official-detail theme; B1 myproject theme; M-22 game-node run card order; PL5 per-station award.

## Shared kit already on the base branch (use it, do not re-create it)
- `pageMotion.enter(page, key, { once: true })`: entrance plays once per key per page instance.
- `pageMotion.countMoney(page, key)` + `count.wxs` `money(text, key)`: slow-fast-slow money landing.
- `utils/wxs/num.wxs` `n(v)`: "—" when there is no value; a real 0 stays 0.
- `.cy-refresh-float` (`style/fade-in.wxss`): refresh status that floats and takes no height. Parent needs `position: relative`.
- `cy-fly-in` no longer holds `transform`, so `.cy-pressed` scales again (X-2 is done).
Any change to these kit files goes in your RESULT as a request, not in your branch.

## Findings
The audit reports came from reading code, and one of their claims has already proved false. Verify each finding before changing
anything, and record false positives. Read a report with:
`git fetch origin claude/mini-polish-audit-20261102-<audit-lane> && git show origin/claude/mini-polish-audit-20261102-<audit-lane>:docs/delivery/mini-polish-audit-20261102/<audit-lane>/REPORT.md`
Do all P0 and P1 rows in your scope, plus rows of type `spacing` or `radius`. Other P2 rows only when they are S effort and low
risk and sit on a line you are already changing.

## Code rules
- Reserved, do NOT edit: `app.js`, `app.json`, `scripts/fixtures.json`, `scripts/shot-matrix.js`, `scripts/uiaudit/*.json`,
  `tests/lint/ui-integrity-baseline.js`, `tests/unit/action-ledger.test.js`, contract tests `all-page-back-stack`,
  `get-auth-matrix`, `nav-page-title-spacing`, `route-matrix-freshness`, `secondary-nav-consistency`, `title-system-owner`,
  `unused-component-registry`, `subpackageMember/components/cy/scene-member-order-detail/index.js`, `utils/danger-actions.js`,
  `utils/transport/request-client.js`. Do not commit a regenerated action ledger. Report count drift; the integration step
  regenerates it once.
- Line-keyed baselines: put added JS on the existing line or at the end of the Page object where you can.
- Never put "PM" in a variable name (a test rejects "AM|PM"). WXSS rejects a bare `> :nth-child()`.
- Run from `chengyinhub-xcx/`: relevant `tests/unit`, `scripts/ds-hardcode-gate.sh --ci`, `node scripts/ui-theme-unit-lint.js --units`,
  `node scripts/setdata-dead-field-lint.js`, `node scripts/a11y-tap-target-lint.js`. NEVER run `ci/xcx-check.sh` (its selftest
  writes fixtures and commits into the repo). Tag intentional literals `/* ds-ok: reason */`.
- Commits: Chinese with a `fix:` or `feat:` prefix.

## Seeing real pages
Rig on branch `claude/cloud-walk-20261101-rig`: read `docs/delivery/cloud-walk-20261101/rig/RIG.md`. It runs all 158 real pages in a
390x844 browser phone against a real seeded backend, about 6-7 min cold. Use `git worktree add $HOME/rig origin/claude/cloud-walk-20261101-rig`
and run `up.sh` from there. Rig limits: native map is a canvas stand-in, seeded data is sparse, some pages are untested. Say what
you could not exercise; never claim a rendering you did not capture.

## Result
Write `docs/delivery/page-polish-20261102/<lane>/RESULT.md`, plus `<lane>/shots/*.png` (under 300 KB each, at most 40 files). English,
UI strings quoted verbatim. Last line exactly `STATUS: COMPLETE` or `STATUS: PARTIAL` plus what remains.
