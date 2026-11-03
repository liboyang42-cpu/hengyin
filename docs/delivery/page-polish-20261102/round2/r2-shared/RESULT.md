# Round 2 · r2-shared · result (2026-11-02)

Branch `claude/page-polish-r2-shared`, from `integrate/page-polish-r2-20261102` (2f7bdab0).
Scope: `chengyinhub-xcx/style/*`, `components/cy/*` used by more than one area, `components/tabBar`,
`utils/page-motion.js`, `utils/motion.js`, `utils/wxs/*`. No JS, WXML, app.js/app.json, reserved test or baseline was edited.

Rig: `claude/cloud-walk-20261101-rig` with `SHA` set to the base in a local `up-local.sh`. Docker Hub refused the
MySQL digest, so dockerd used `mirror.gcr.io` as a registry mirror. `chains.py finance` ran first: platform, merchant_club,
sale_close, player and finance all PASS (topic 990014). The before bundle came from the base worktree. The after bundle
was built with `CAND=<this repo> mpweb/build.sh`. Identities: 9004 (player), 9002 (merchant).

## What changed

| # | File | Change | Visible? |
|---|---|---|---|
| 1 | `style/fade-in.wxss` | New `.cy-refresh-float--pill`: `padding: var(--cy-space-1) var(--cy-space-3); border-radius: var(--cy-radius-pill); background: var(--cy-color-bg-elevated); white-space: nowrap`. These are the four properties the 8 local copies share. Font size and colour stay with each page because the copies use two sizes, caption and label. | No. It has no consumers yet. |
| 2 | `style/tokens.wxss` | New `--cy-ease-page-rise: cubic-bezier(.2, .82, .2, 1)` in `page {}` next to `--cy-ease-standard`. | No. Same value. |
| 3 | `components/cy/scene-merchant-profit/index.wxss:54`, `components/cy/scene-roam-history/index.wxss:41` | The literal `cubic-bezier(.2,.82,.2,1)` became `var(--cy-ease-page-rise)`. These are the two shared-component copies. The other copies are in page files, which belong to page lanes. | No. The rig resolves the token inside an isolated component shadow root to `cubic-bezier(0.2, 0.82, 0.2, 1)` on both the merchant workbench and roam. |
| 4 | `components/cy/post-card/index.wxss:265` | The local `.cy-pressed` went from `opacity .78; scale(.985)` to `.88; scale(.98)`, the same as the global `.cy-pressed`. The class name is the same as the global class but the value differed, so this is a same-role disagreement. | Yes, only while pressed. Shots below. |
| 5 | `components/cy/event-attendance/index.wxss:9` | The local `.cy-pressed` went from `opacity .82` to `.88`, the same value the other isolated components use (opacity only). | Yes, only while pressed. Not shot (see "Not exercised"). |
| 6 | `components/tabBar/index.wxss:86` | Raise and lower: `transform 0.32s ease, opacity 0.32s ease` became `var(--cy-motion-slow) var(--cy-ease-out)` (350 ms, `cubic-bezier(.22,.61,.36,1)`). This is audit T-02. | Motion only, 30 ms longer with an ease-out curve. A still frame cannot show it. The rig's computed `transition` before and after is listed below. |
| 7 | `components/cy/tabs/index.wxss:262` | `.cy-tabs__badge` `margin-left: 8rpx` became `var(--cy-space-1)` (8rpx). | No. |

### Pages affected by each visible change
- **post-card press (4).** Player: `pages/talent/list` (发现 · 帖文), `pages/club/detail`, `pages/mylike`, and the profile hub (`subpackageMember/components/cy/profile`, used by `pages/member/index` and the user pages). Merchant: `pages/merchant/relation` (合作 · 帖子).
- **event-attendance press (5).** `pages/activity/official-detail` and `pages/activity/official-organizer`. These are official signup pages, which stay light. The component has no other users.
- **tab bar motion (6).** Every page that mounts `components/tabBar`: the five player tabs and the merchant tabs. The bar only animates on pages that toggle `raised`, which are home (`pages/index`, scroll > 120) and 发布 (`pages/template`, hides on scroll-down). On the other tabs it never moves, so they show no difference.

### Evidence
- `shots/postcard-talent-pressed.png`, player (dark), and `shots/postcard-relation-pressed.png`, merchant (light). Each is before | after with `cy-pressed` applied to the first card's `.post-card__body`. Computed before: `opacity 0.78, matrix(0.985…)`. Computed after: `opacity 0.88, matrix(0.98…)`. Two dummy posts were put into page data (`feed`), because the fresh database has no posts. The rest-state shots are pixel-identical before and after (`compare -metric AE` = 0 on both pages).
- Tab bar on `pages/merchant/index/index` and `pages/roam/index`. Computed before: `transform 0.32s, opacity 0.32s` (plain `ease`). Computed after: `transform 0.35s cubic-bezier(0.22, 0.61, 0.36, 1), opacity 0.35s cubic-bezier(…)`.
- `shots/skeleton-dark-topic-baoming.png`: item 4, below.

## Item 4 · Skeletons "invisible on dark pages" is a rig artifact, not a contrast problem
`cy-skeleton` has `delay: 400`: it draws nothing until a load has run for 400 ms (`components/cy/skeleton/index.js`, "快加载什么都不闪").
On the rig the backend is local, so the topic and baoming loads finish in under 400 ms and the skeleton never draws. Round 1's
"bars invisible both before and after" shots were of this empty wait, which is not a rendering bug.
Proof: I forced each page into its loading state (topic `topicLoaded:false`; baoming `pageState:'loading'`) and probed the DOM at
100 ms and 800 ms. At 100 ms there are no `.sk-bar` nodes. At 800 ms the bars are present with
`linear-gradient(#16141C → #211E29 → #16141C)` (the `.theme-dark` skeleton tokens) over the `#000` page, and they are clearly visible in the
shot: topic shows a hero block with 5 lines, and baoming shows 3 cards. Baoming's `count="{{skeletonItems.length}}"` gives 3 (`skeletonItems: [1,2,3]`),
so round 1's "renders empty" note for baoming has the same cause. The contrast is low on purpose (DS R1: the skeleton is a
separate token, one step above the page, never the card colour) and readable. **No change.**
For future rig shots of loading states: force the state, then wait more than 400 ms.

## Regressions
None found. The rest-state pages compared before and after are pixel-identical. The press and motion changes are the only visible deltas, as listed above.

## Row accounting (shared-component rows only, from all five audits)

| Row | Source | Status | Notes |
|---|---|---|---|
| T-02 tab bar raise/lower curve | player-home | **done** | Change 6. |
| T-01 tab bar visibility, one rule | player-home | needs owner / decided | The brief's "Decided: Home tab bar stays". Not reopened. |
| T-03 selected pill with label | player-home | not proposed by the audit | It would be a restyle. |
| §5.1 `pageMotion.enter` replays (player-home), X-1 (club-publish), "enter only on first load" (player-theme) | 3 audits | already fixed | `utils/page-motion.js` `enter(page, key, { once: true })` exists on the base (round 1). Callers opt in. |
| §5.2 refresh row pushes content / X1 (player-me) / X-2 (club-publish) | 3 audits | already fixed + **done** | `.cy-refresh-float` exists (round 1). The pill surface that round 1 requested is change 1. |
| X-1 money count helper, M-13 `cy-stat-card` `countK` | merchant | already fixed | `pageMotion.countMoney`, `count.wxs money()`, and `cy-stat-card` `countK` are on the base. |
| X-2 `cy-fly-in` without `transform` | merchant | already fixed | `style/motion-entry.wxss` keyframes use separate `translate` and `scale`. |
| `--cy-ease-page-rise` token (merchant §3 note) | merchant | **done** | Change 2. The two component copies were converted (change 3). The ~45 page-file copies are for page lanes, as a no-visual-change token swap. |
| PM-05 earnings count-up timing | player-me | already fixed | `scene-asset-earnings` uses `pageMotion.countMoney` (round 1). |
| X3 `.cy-pm-row` shared stagger rule | player-me | not done | The audit marks it optional ("existing ladders can stay"). The ladders were converted per page to `--pmi` in round 1, so a global class would ship with no consumers. A page lane can ask for it if it needs one. |
| X5 `utils/wxs/num.wxs` "—" helper; "— for null counts" (player-theme) | 2 audits | already fixed | `utils/wxs/num.wxs` exists. |
| Late press under entrance transitions, a global `.cy-pressed{transition-delay:0s}` | player-theme §5 | false positive for the kit | page-motion delays are `calc(var(--pm-*-s) * …)` and `--pm-*-s` is 0 at rest, so a press at rest is not delayed. The remaining cases (topic P-T1/P-T2) were fixed locally in round 1. A global rule would also lose on specificity to the page rules, which load later. |
| "Pressed styles differ" (`.team-action--pressed` .72, `.om-card--pressed` .78) | player-theme §5 | reported for page lanes | Page files. See the press table below. |
| P-01 template placeholder → `cy-skeleton` | player-home | already fixed | Round 1. |
| P-02, D6, S21, H-04, MT-04, S-01, PM-15, PL10, T12, CP-03: skeleton type or skeleton usage | several | page lanes | These change which `type` a page passes or add a `cy-skeleton` to a page. The component needs nothing new: every requested shape already exists. |
| M-19 `.cu-check` fill using `--cy-motion-check-fill` | merchant | page lane | Page file. The token already exists. |
| Measure A5 (`scene-merchant-profit` funds-stages → withdraw gap 0) | measure-merchant | needs owner | It is in a shared component, but none of the five audit reports names it, so it is outside my rows. A 0 → `--cy-space-3` gap would be a visible change on coop/finance and the asset-earnings sheets. Listed for integration. |
| Measure A7 (chip `cy-tabs` 14rpx hit band above pills) | measure-merchant | needs owner | It is a component variant decision (measure-player says the same). Changing it moves every chip-tab page. |
| Measure A9 (`cy-profile` 「品类」 gap) | measure-merchant | not my scope | `subpackageMember/components/cy/profile` is a subpackage component, so it belongs to a page lane. |

## Shared-component audit (brief item 1)

**Press states inside `components/cy/*` and `components/tabBar`.** The standard is `cy-pressed`: opacity .88 + scale .98 globally, and
opacity .88 only inside isolated components.

| Component | Class | Value | Role | Action |
|---|---|---|---|---|
| post-card | `.cy-pressed` | .78 / .985 | card body, avatar, actions | **fixed** → .88 / .98 |
| event-attendance | `.cy-pressed` | .82 | task rows / link | **fixed** → .88 |
| proto-cam | `.cy-pressed` | .72 / .96 | camera overlay controls over a live feed | Owner's choice. A deeper press may be deliberate over video. Not changed. |
| scene-roam-task-list | `.task-card--press` .82, `.task-tab--press` .62 | card / tab | roam (v13 family) | Owner's choice. Not changed. |
| scene-roam-stamp-album | `.album-fab--press` .72 | FAB | roam album | Owner's choice. Not changed. |
| avatar | `.av--press` .82 | avatar tap | — | Reported. It is a component-specific class. Converging it to .88 is a 1-line follow-up if the owner wants it. |
| error | `.cy-error-retry--press` .85 / .98 | retry button | — | Reported. It is close to `cy-btn` `.btn--press` (.92 / .985 + 2rpx). |
| field / date-field / consent-check | `--press` .7 | form triggers | same value across all three | Consistent among themselves. Left alone. |
| tabs | `.cy-tabs__item--press` .62 | tab item | — | A tab is not a button or card. Left alone. |
| nav-bar | `.nav__back--press` .5 | back chevron | — | Left alone. It is a glyph, not a surface. |
| toast | `.ct__action--pressed` .6 | toast.undo | approved overlay spec | Owner's choice (approved). Not changed. |
| datetime-range, theme-records | `.cy-pressed` .88 + scale .98 | — | matches global | OK. |
| ~45 other isolated components | `.cy-pressed { opacity: .88 }` | — | matches | OK. |

**Page-lane press values (reported, not edited):** `.team-action--pressed` .72 (no transition) and `.om-card--pressed` .78, compared with `.cy-pressed` .88 / .98.
These are in page files.

**Empty / error.** `cy-empty` and `cy-error` use the same illustration ladder (160 / 240 / 360 / 520 rpx) and the same tokens. No disagreement.
**Skeleton.** The variants share one bar style and one token pair per theme. Merchant view is forced light (`sk--light`, G-10). No disagreement. See item 4.
**Stat card.** Round 1's `countK` is present. The three-step size ladder (64 / 56 / 36) is documented. No disagreement.
**Tabs / buttons.** The raw `88rpx` values in `btn` and `tabs` are touch-target minimums and tab-row heights, not `--cy-btn-h` by role. They were left as they are so they are not tied to a button-height token.

## Owner's choice (measured, not edited)
- `--cy-tabbar-h` is `calc(104rpx + env(safe-area-inset-bottom))`. The rendered bar is `16rpx + 88rpx item + 16rpx + env`, which is 120rpx + env.
  The rig measures the bar at **96.4 px** (top 747.6) against **88.1 px** for the token, a gap of **8.3 px**. The token was **not changed**, because pages and components
  depend on the current value. Raising it by 16rpx would move all of these: the tab-bar publish FAB and `cy-publish-sheet` anchor
  (`components/tabBar/index.wxss:176`, `components/cy/publish-sheet/index.wxss:20`), the v13 roam home (`pages/roam/index.wxss:136,145`,
  approved motion screen), the 发布 FAB and scroll pad (`pages/template/index.wxss:74,88`), the home scroll pad (`pages/index/index.wxss:87`), fabu
  (`pages/publish/fabu/index.wxss:3846`), merchant relation and marketing (`pages/merchant/relation/index.wxss:4`, `marketing/index.wxss:32`, which already
  adds `space-2` locally), and the profile hub (`subpackageMember/components/cy/profile/index.wxss:109,989`). Tests pin the token by name
  (`nav-safe-area-contract`, `devfix-l04-home-template-20261027`). **Recommendation:** keep the token, which is the anchor for floating controls, and have the
  pages that must clear the whole bar add `+ var(--cy-space-2)` locally, as marketing already does.
- proto-cam, roam task list, roam stamp-album and toast press values: see the table above.

## Shared requests (for page lanes, after integration)
- Replace the local pill copies with `class="… cy-refresh-float cy-refresh-float--pill"` and keep only font size and colour locally:
  `subpackageA/pages/myproject` `.mp-refreshing`, `subpackageMember/mycanyu` `.canyu-refreshing`, `subpackageB/pages/im/list` `.refresh-status`,
  `pages/club/badges` `.badge-refreshing`, `pages/club/join-requests` `.refreshing`, `pages/club/enroll` `.refreshing`,
  `pages/gerenziliao` `.profile-refreshing`, `pages/address` `.dz-refreshing`. Note that `--cy-bg-elevated` and `--cy-color-bg-elevated` are aliases, so this is a no-visual-change swap.
  Do not add the pill to `pages/mylike` (its status node is empty) or to `pages/talent/list` (`cy-progress-status` host).
- Replace page-file `cubic-bezier(.2,.82,.2,1)` with `var(--cy-ease-page-rise)` (about 45 lines, same value). Leave the `ds-ok` prototype copies alone
  (`pages/roam/index.wxss:196`, topic, play and medal-screen v13 values).

## Ledger and baseline drift
None. The full `node --test "tests/unit/**/*.test.js"` run gives **15553 pass, 0 fail**, including `action-ledger.test.js`, because no WXML or tap changed.
`ds-hardcode-gate.sh --ci` PASS; `ui-integrity.js --warn-as-error` error 0, warn 0; `state-visibility-gate` PASS; `ui-validity-gates` U6 PASS;
`ui-theme-unit-lint --theme` / `--units` PASS; `setdata-dead-field-lint` PASS (274 existing entries are in the baseline); `a11y-tap-target-lint` 0.
The theme lint notes that 7 baseline entries no longer exist. That is existing slack, not from this lane, and I did not run `--update-baseline`.

## Not exercised
- Event-attendance press (change 5): the seed has no official event with a player task row. Covered by code only.
- Tab bar motion (change 6): computed values only. A still cannot show a timing change.
- The `.cy-refresh-float--pill` class has no consumers, so there is nothing to render.
- Rendering on a real device or in WeChat DevTools.

STATUS: COMPLETE
