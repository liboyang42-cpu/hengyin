# Mini-program polish audit: shared brief (2026-11-02)

Base: `master` at a1d30fb. Mini program: `chengyinhub-xcx/`. Every lane reads this file first.

## Goal
The owner wants to refine the merchant and player sides of the mini program. They said: "the overall page
layouts don't need a major overhaul; focus on refining the details, specifically the animations and the
structural organization of the pages." This stage is ANALYSIS ONLY. The owner reviews a plan before any change.

## Hard limits
- Change nothing except your own report. No code edits, PRs, merges, or pushes to master.
- Push only `docs/delivery/mini-polish-audit-20261102/<lane>/REPORT.md` to branch
  `claude/mini-polish-audit-20261102-<lane>`, created from this brief's branch.
- This is a public repo: no secrets, production hosts, real user data, or third-party video frames.
- Never connect to any production or VPS service, database, or payment service.
- Static analysis of the code. Do not claim you saw something render. Mark each finding's evidence as
  `code` (read directly) or `inferred`.

## Owner rules a proposal must respect
1. Existing UI is the baseline. Propose the smallest in-place edit. No new page designs, no parallel components,
   no "unify everything" restyle, and do not change main titles (`cy-nav-bar` + `cy-page-title`).
2. A reference restyles one named element only, not the page around it.
3. No secondary labels or helper captions. A second line must carry real data (a time, a count, a status).
4. Merchant side is always light; player side is always dark. Never follow system dark mode.
5. Fix the timing of an existing animation rather than removing it. Do not re-open approved work: the overlay
   spec (sheet peek/half/full, exit handoff, toast.undo), the v13 motion screens (roam, theme cover/detail, buy,
   reveal, wallet, medal), the store-advisor sheet morph, enamel badges.
6. Motion language so far: simple but high quality, "a little faster", slow-fast-slow for money numbers, the page
   shape never changes while loading, no bounce on popups, no invented artwork or mascots.
7. No-data shows "—", not a fake 0 (marketing pages keep 0). Do not reword text that mirrors backend messages.

## Technical facts
- Pages run on webview (glass-easel, no Skyline), so no worklet springs. Native map markers cannot animate smoothly.
- Motion kit: `utils/page-motion.js` (`enter`, `countIn`), `utils/motion.js`, `utils/wxs/count.wxs`,
  `style/motion-entry.wxss`, `style/fade-in.wxss`, `style/reduced-motion.wxss`. Tokens: `style/tokens.wxss`
  (`--cy-motion-*`, `--cy-ease-*`, `--cy-space-*`, `--cy-radius-*`).
- Measured on a1d30fb: 21 of 157 pages call `pageMotion.enter` (merchant + coop 7 of 35; other 14 of 122).
  Absence does NOT prove a page has no motion: roam, topic and play have their own. Check before reporting a gap.
- `pages/roam/index.wxss` has 32 distinct border-radius values and 115 distinct spacing values, many raw rpx.
  `pages/merchant/index/index.wxss` has 5 radius values, nearly all tokens. Treat these as starting points.
- Tap-feedback gaps seen: `merchant/citynode/create` 7 taps / 0 hover-class, `merchant/decor/perks` 5 / 1,
  `merchant/decor/coop-setting` 8 / 1, `merchant/decor/ai-npc` 31 / 6.

## What to learn from the references (frame-by-frame notes)
A. Staged entrance (crypto-wallet app, 2.4 s clip). Tab bar is present and still from frame 1, so chrome never
   animates. Content reveals strictly top to bottom over about 1.3 s: header, search, label, then the balance
   digit by digit (each digit blurs and fades in), then delta, action row left to right, section title, chips,
   list rows, each with a small fade/blur/rise. Hierarchy is taught by order. No overshoot.
B. Chart draws left to right after the number lands. Selected tab is a pill with a label; others are icon only.
C. De-slop method (3 clips: fitness, skincare, social). Measure inconsistent gaps (e.g. 16/20/16/17/21 becomes one
   24; radii 9/12/12/10 become one 23), then make 3-4 named structural moves only: trim a heavy header, group loose
   stats into a 2x2, make one card dominant, remove a redundant entry row, float the nav. Layout otherwise stays.
   Also fewer accent colours: one action colour plus one status colour.
D. Delight on state (notes app). Tapping a checklist item fills its dot and strikes the text across. Focusing search
   dims non-matching cards rather than removing them, then restores them. A pressed FAB gets a ripple. Sections
   reveal top to bottom, the primary FAB last.

Translate these into OUR pages only where they solve a real problem. Do not copy their look. Say so when a page
is already good; a short honest report beats padding.

## Report format
`REPORT.md`, in English, quoting UI strings verbatim:
1. Summary: at most 10 lines, the top 5 items by user impact.
2. Per page, in order of how often users hit it: one line of what is already good, then findings.
3. Finding row: `ID | page | file:line | element | now | problem | smallest edit | type | effort S/M/L | risk |
   priority P0/P1/P2 | evidence`. Type is one of: motion-entry, motion-transition, motion-feedback, structure,
   spacing, radius, loading-state, empty-state, tap-feedback.
4. Separate section "Real bugs noticed" (out of scope to fix, still report).
5. Separate section "Would need a redesign, not proposing" for anything that exceeds a small edit.
6. Cross-page patterns: what one shared fix would solve across several pages.
7. Last line exactly `STATUS: COMPLETE` or `STATUS: PARTIAL` with what remains.
Push the report early and update it as you go so a usage-limit stop loses little.
