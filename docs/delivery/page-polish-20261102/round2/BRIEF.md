# Page polish round 2: shared brief (2026-11-02)

Base branch: `integrate/page-polish-r2-20261102` (master 2f62e5a4, which includes round 1, PR #711). Mini program: `chengyinhub-xcx/`.
The owner reviewed round 1 and said: finish all the remaining items, then review everything at the end. "Most of these mini-programs
should share a consistent visual style, one that aligns with the brand language."

## Hard limits
- Push only to `claude/page-polish-r2-<lane>`, created from the base branch. No PRs, no merges, no master. An integration step follows.
- Public repo: no secrets, production hosts or real user data. Screenshots use the rig's dummy data only.
- Never contact production, the VPS, databases other than your own rig, or payment services. Backend Java and SQL are out of scope; report if a fix needs them.
- Push early and often. Do not ask questions; decide and note assumptions.

## The standard: brand language
Read before changing anything: `docs/城瘾小程序_设计系统权威基准_20260801.md` (sections 3, 4, 6, 7, 8, 9.2, 10), `chengyinhub-xcx/docs/DESIGN.md`,
`chengyinhub-xcx/docs/DS改页自查清单.md`, `chengyinhub-xcx/style/TOKEN-MAPPING.md`, `chengyinhub-xcx/style/tokens.wxss`.
Consistency means existing tokens and `cy-*` components are applied uniformly. It does NOT mean a new look.
Measured conventions so far (use them, and correct them only with evidence):
- Page inset `--cy-page-x` (32rpx).
- Card radius tiers: media cards `--cy-radius-2xl`; list cards `--cy-radius-lg`; info and inline cards `--cy-radius-md`; pills `--cy-radius-pill`.
- Page title to first block about 9 px wherever `cy-page-title` meets a block directly. Inside one page, same-role gaps are equal.
- Press feedback `cy-pressed` (buttons and cards) or `cy-pressed-bg` (rows). Scrims use `hover-class="none"`.
- Semantic colour tokens only; one visual primary per screen; states are loading, empty, error, ready.
Differences under 2 px are noise unless same-role items sit side by side.

## Owner rules
1. Smallest in-place edit. No new page designs, parallel components, new variants or new layouts. Main titles (`cy-nav-bar` + `cy-page-title`) stay.
2. Round 1 lesson: a lane switched filters from pills to `shared-slab` and the owner would have seen a restyle. Never change a component variant or an element's visual form unless a finding names exactly that. Every visible change needs a before and after shot.
3. No secondary labels or helper captions unless the second line carries real data.
4. Merchant side always light; player side always dark. Standalone merchant-provided features (official event signup pages) stay light for everyone. My Projects: dark for player and club, light with `scope=MERCHANT`.
5. Fix an animation's timing rather than deleting it. Simple, high quality, a little fast. Money numbers land slow-fast-slow. Page shape never changes while loading. No bounce on popups. No new artwork or mascots.
6. No-data shows "—", not a fake 0 (marketing keeps 0). Do not reword text that mirrors backend messages.
7. Approved work is NOT changed automatically: overlay spec (sheet levels, exit handoff, toast.undo), v13 motion screens (roam home, map, theme cover/detail/tabs, buy sheet, checkout, reveal, wallet, medal), store-advisor sheet morph, enamel badges, chapter-finish page, and any value marked `ds-ok` with an owner ruling or Figma/prototype source. Where these disagree with the standard, list them under "Owner's choice" with measured values. Do not edit them.

## Decided: do not reopen
Home tab bar stays. Official signup pages stay light. Team join receipt stays until 「确认结果」. Per-station award fade is done. N-02 overshoot on v13 map motion stays.

## Scope for every lane
1. Every remaining row in your audit report that round 1 did not do. Read the report with
   `git fetch origin claude/mini-polish-audit-20261102-<audit-lane> && git show origin/claude/mini-polish-audit-20261102-<audit-lane>:docs/delivery/mini-polish-audit-20261102/<audit-lane>/REPORT.md`
   and round 1's result with `git fetch origin claude/page-polish-20261102-<impl-lane> && git show origin/claude/page-polish-20261102-<impl-lane>:docs/delivery/page-polish-20261102/<impl-lane>/RESULT.md`.
   These reports came from reading code and have had false positives: verify each one first, and account for EVERY row in your result as done, false positive, already fixed, needs owner, or too big with the reason.
2. The extra items named in your lane prompt.
3. Pages in your scope that nobody has measured yet (`claude/page-polish-20261102-measure-merchant` and `-measure-player` RESULT.md say which). Seed data or set page data to reach real content, measure with their `tools/`, and fix real unevenness.
4. Brand consistency inside your scope: raw values that duplicate a token become the token with no visual change; same-role elements that disagree get the convention above.
Dead CSS and dead code rows: remove only when grep proves unused.
A row that would need a redesign stays undone; report it.

## Shared files
Only lane r2-shared edits `style/*`, `components/cy/*` used by more than one area, `utils/page-motion.js`, `utils/motion.js` and `utils/wxs/*`. Others record requests in RESULT.md.
Other work is open on the same pages: `claude/walk-fix-1102-*` (PRs #712, #713 and more), #709, #710, #702. Do not duplicate them. Before editing a file, check `git log origin/claude/walk-fix-1102-<lane>` for overlap; if a walk-fix lane already fixes the item, record that and skip it.

## Code rules (round 1 CI failed on these)
- Reserved, do NOT edit: `app.js`, `app.json`, `scripts/fixtures.json`, `scripts/shot-matrix.js`, `scripts/uiaudit/*.json`, `tests/unit/action-ledger.test.js`, contract tests `all-page-back-stack`, `get-auth-matrix`, `nav-page-title-spacing`, `route-matrix-freshness`, `secondary-nav-consistency`, `title-system-owner`, `unused-component-registry`, `subpackageMember/components/cy/scene-member-order-detail/index.js`, `utils/danger-actions.js`, `utils/transport/request-client.js`. Do not commit a regenerated ledger; report drift. `tests/lint/ui-integrity-baseline.js` too: report needed changes; integration applies them once.
- Line-keyed baselines: keep JS line counts stable above existing anchors. Put additions on existing lines or at the end of the Page object. Moving a registered line breaks U1/U4/U5.
- No computed keys in setData (`{ [flag]: true }`). Use literal objects.
- A state-conditioned element needs visible content: put `wx:if` on the `cy-*` component itself, or name a placeholder class (`*-placeholder`). Never leave a bare state-conditioned view.
- Never put "PM" in a variable name. WXSS rejects a bare `> :nth-child()`. Tag intentional literals `/* ds-ok: reason */`. Do not weaken a test or gate to pass; change a test only when its pinned structure truly changed, and say so.
- Commits: Chinese, `fix:` / `feat:` / `chore:` prefix.

## Required checks, from `chengyinhub-xcx/` (fetch origin master first)
`node --test "tests/unit/**/*.test.js"`, `bash scripts/ds-hardcode-gate.sh --ci`, `node tests/lint/ui-integrity.js --warn-as-error`, `node scripts/state-visibility-gate.js`, `node scripts/ui-validity-gates.js`, `node scripts/ui-theme-unit-lint.js --theme` and `--units`, `node scripts/setdata-dead-field-lint.js`, `node scripts/a11y-tap-target-lint.js`. NEVER run `ci/xcx-check.sh`.
Ledger drift and baseline line shifts are the only acceptable reds; list each one exactly.

## Seeing pages
Rig on `claude/cloud-walk-20261101-rig` (`docs/delivery/cloud-walk-20261101/rig/RIG.md`). Worktree it, set `SHA` to your base in a local copy of `up.sh`, run `chains.py finance`. Build before and after bundles with `CAND=<worktree> mpweb/build.sh`. If Docker Hub returns 429, use `mirror.gcr.io` as a registry mirror. Never claim a rendering you did not capture.

## Result
`docs/delivery/page-polish-20261102/round2/<lane>/RESULT.md` plus `shots/*.png` (before | after, under 300 KB each, up to 40). English, UI strings verbatim. Sections: regressions; row accounting table; Owner's choice; shared requests; ledger and baseline drift; not exercised. Last line exactly `STATUS: COMPLETE` or `STATUS: PARTIAL` plus what remains; do not write a placeholder STATUS line early.
