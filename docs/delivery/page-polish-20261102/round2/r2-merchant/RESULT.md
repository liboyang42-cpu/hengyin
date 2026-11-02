# r2-merchant: result (2026-11-02)

Branch `claude/page-polish-r2-merchant`, from `integrate/page-polish-r2-20261102` (2f7bdab0). Scope: `chengyinhub-xcx/pages/merchant`,
`pages/coop`, `pages/topic/merchantinfo`, `pages/topic/components/project-host`, `project-join` and `project-drawer`,
`pages/topic/merchantapply` and `pages/topic/pricing`. Audit lane: merchant. Round 1 lane: impl-merchant.

## How this was measured

- Rig from `claude/cloud-walk-20261101-rig` with `SHA` set to the base (2f7bdab0) in a local copy of `up.sh`. The backend was the base
  jar on real MySQL and Redis. `chains.py finance` passed all five stages (platform, merchant_club, sale_close, player, finance) and
  created topic 990014 「E2E 探店日一期」. The phone is 390×844 and every value below is in px at that width (1 rpx = 0.52 px).
- The browser bundle was built from the base for "before" and from this branch for "after" (`CAND=<repo> mpweb/build.sh`).
  Positions come from `getBoundingClientRect` on glyph ranges and boxes, piercing shadow roots. The page-wide annotated passes used
  `measure-merchant/tools/run.mjs`.
- **Rig fixtures (rig DB only, all dummy):**
  - Theme 990013 (the spare seed copy) was set to `member_id=9002, club_id=NULL`. This makes it a merchant-hosted theme for 商家老板.
  - Theme 990014 was switched to merchant-hosted the same way while the host view, the 客户 drawer and the broadcast sheet were
    shot, then switched back to club 7001.
- **Page data / response fixtures (in the browser only, all dummy):**
  - Money landing: `fetch` was wrapped so the finance endpoints return ¥1234.56 (`/merchant/finance/overview`,
    `redemption-detail`, `public-transfer-batch-detail`, `/coop/finance`). The page's own first-arrival code then ran the count.
  - game-node: the shot-matrix F18 fixture was applied with `setData`, switched to `PAUSED`, `playable:false`, `canResume:true`.
    The rig's session view returns `GAME_SESSION_NOT_PREPARED` for activity 13, so the page cannot be reached with real data.
  - reviews, aftercare and customers: rows were set with `setData` using dummy players 「测试玩家甲/乙/丙/丁」. The app cannot seed
    reviews, refunds or CRM rows (see measure-merchant §C).
  - Broadcast ready state: rows were fed through the page's own `setBroadcast` → `broadcastSheet.derive`.

## Regressions

None found. Every after-shot was compared with its before-shot. The full unit suite and all required gates are green apart from
the items listed under "Ledger and baseline drift" (none remain).

## Extra items from the lane prompt

| Item | Before | After | Change | Shot |
|---|---|---|---|---|
| merchantinfo: quick actions → 「我的节点」 vs node card → 「我的章节供给」 | 23.8 vs 16.7 | **15.5** vs 16.6 | `project-join/index.wxss` `.qa`: bottom padding `space-1` → 0, plus `margin-bottom: calc(-1 * var(--cy-space-1))`, which absorbs the `qa-label` line-height leading | `01` |
| merchantinfo: 「节点NPC」 label x vs card text x | 45.7 vs 29.1 | **29.1** | The ghost `cy-btn` keeps its variant, but its glass background is invisible on the white card, so only the text shows. New `.chapter-project-actions--btn` (only on rows that hold buttons) shifts the row left by the button's own padding (`space-4`). | `01` |
| merchantinfo: space under 「节点NPC」 vs above the first line | 28.3 vs 16.6 | **15.9** vs 16.6 | `.chapter-project-actions--btn:last-child { margin-bottom: calc(-1 * var(--cy-space-3)) }` | `01` |
| Merchant host view (first render of this view): quick actions → first block | 23.8 (→「售卖」), 19.6 (→ gate card) | **15.4** / 15.4 | The same `.qa` rule in `project-host/index.wxss` (same component family, so both stay identical) | `02` |
| Merchant host view: 「主题」 card → 「进度」 card | **33.3** (`.page-pad` 8.3 + `.pg` margin 25) vs 16.6 between the other blocks | **16.6** | `.pg` margin-top `space-5` → `space-2` (`.page-pad` stays) | `03` |
| Host 客户 drawer (`project-drawer` content in project-host): filter pills → 「这条路线由俱乐部带团…」 caption | **2.0** (touching) | **14.4** | `.dfil + .dg-sub { margin-top: var(--cy-space-3) }` in project-host and project-join (the same markup exists in both) | `04` |
| Broadcast sheet (定向广播), empty and ready states | 「发给」 row → avatar strip → pills 14.6 → field 14.5; both edges 16.6 | unchanged | Even. Owner-finalized 2026-11-01 (`broadcast-sheet.js` header), so it was measured only | `11` |
| game-node, not playable: 「暂停期间如需退款请联系平台客服。」 → 「恢复接待」 | **2.3** | **10.6** | `.gn-muted--above-btn { margin-bottom: var(--cy-space-2) }` on that caption. This is the same space-2 gap the page's other stacked buttons use (`.gn-verify-actions`). It sits on the text and not on the `cy-btn`, so the action ledger does not drift | `05` |
| ledger row: chip 「权益已履约 · 无现金结算」 x vs title / sub line x | 55.1 vs 50.9 | **50.9** | `merchant-finance.wxss` `.fin-row__chip` margin-left `space-1` → 0. When inline, the row's `gap` (space-1) still separates it from the preceding text | `07` |
| Money landing on 5 pages (count ¥0 → ¥1234.56) | — | ledger hero, order-detail, batch-detail, coop finance 「已入账分润」, settlement-detail: each count starts about 275 ms after the page opens and runs **1200 ms**. At 25 / 50 / 75 % of the time it shows **9 / 54 / 93 %** of the final amount (ledger: ¥111 → ¥663 → ¥1152), so it is slow-fast-slow (`easeInOutCubic`). The last frame is the real value | no change needed (round 1 X-1 works) | `12`, `13` |
| Rows never measured: reviews | 3 cards, card → card 12.5 / 12.5, segments → first card 12.5, grid → segments 14.5 | — | even, no change | `14` |
| Rows never measured: aftercare | search → first date group **28.0** vs card → next date group 23.8 | **23.8 / 23.8** | `.acl-search` margin-bottom `space-5` → `40rpx` (`ds-ok`: the same 20 px `.fin-group` gap) | `10` |
| Rows never measured: customers | rows have no vertical padding: name **2** px under the divider; text → next divider **25.2 / 4.6 / 27.5** | divider → name **14.5 / 14.5**; text → divider **17.1 / 17.1 / 15.0**; search → first name 18.6 (unchanged) | `.cu-row` padding `0 40rpx` → `var(--cy-space-3) 40rpx`; the first row (no divider above it) keeps `padding-top: 0` and a min-height reduced by the same amount | `09` |
| Rows never measured: batch-detail | hero card **0** px under the nav bar (this is the only ledger detail with a nav-only title, no `cy-page-title`); earning rows 47.4 apart, even | hero **12.5** under the nav | `batch-detail/index.wxss` `.order-detail__spacer { margin-bottom: var(--cy-space-3) }` | `08`, `12` |

## Scope item 3: pages nobody had measured

| Page | Finding | Fix | Shot |
|---|---|---|---|
| `topic/pricing` (merchant-hosted 990013) | page title → chip pills **16.3** (same chip hit-band cause as measure-merchant A7); pills → first card **19.7** vs card → card 10.4; 「合作阵容锁定后可定价」 → submit button **2.3** | `.pg-tabs { display:block; margin: -14rpx 0 6rpx }` (`ds-ok`, the same offset the owner already has on verify-records and aftercare) → **9.0 / 10.4**; `.pg-actions .pg-note { margin-bottom: space-2 }` → **10.6** | `15` |
| `topic/pricing/partner` | renders only its empty state for 9002 (no partner pricing) | not judged | — |
| `topic/merchantapply` | the store card repeated its own section title: 「你的门店」 above the card **and** again inside it, at title size (a #539 merge leftover). Label → field 9.2 and field → next label 14.5 everywhere | The in-card copy is removed (card 82 px instead of ~150). The three lines are kept as a comment so the line-keyed action-ledger ids in the rest of the file do not move | `16` |
| `topic/merchantinfo` host (project-host, project-drawer) | see Extra items above | — | `02`–`04`, `11` |

Also found while seeding M-09 (ledger settlement): while the overview loads, the placeholder was 77 px tall and the hero lands at
205 px, so 「去我的资产」 and the alert row **jumped 136 px** (owner rule 5). Now a `ledger-inline-state--hold` box keeps
394rpx plus the hero's 20 px gap while there is no overview yet. The button sits at 386.7 while loading and 386.8 when ready. Shot `17`.

## Row accounting (audit `mini-polish-audit-20261102-merchant`)

| ID | Result | Evidence / reason |
|---|---|---|
| M-01, M-02, M-03, M-04 | already fixed (round 1) | in base: `index.wxss` alert `--pm-w` rule, `--pmi` sections, no `rvK`/`revBars` |
| M-05 | needs owner | No coupon or ticket icon exists in the merchant icon set (`pages/merchant/images`: green 1.5-stroke 24 px). `images/cards/coupon.svg` is 16 px white-stroke card art. Swapping one in, or drawing one, is new artwork (rule 5) |
| M-06 | **done** | `pages/merchant/index/index.js:990`, same line: while `pmPage` is non-zero, `countRevenueTo` waits 150 ms (the same hold as `countIn`), so the slow start is no longer spent while the hero is still invisible. Motion only, so there is no still-shot difference |
| M-07, M-12, M-13, M-16 | already fixed (round 1), **verified** | money landing table above |
| M-08 | needs owner | Adds an entrance animation to the redemptions view, which has none today. That is new motion, not a timing fix (rule 5) |
| M-09 | **done** | `ledger/index.wxml:96`, `ledger/index.wxss:12`; shot `17` |
| M-10, M-11, M-25, M-28 | already fixed (round 1) | in base |
| M-14 | shared request | The duplicated status text is in `components/cy/scene-merchant-profit` (also mounted by `subpackageA/pages/assetcenter/earnings`), so only r2-shared may edit it. Edit: `index.wxml:31` add `wx:if="{{item.settled}}"` to `.fc-arrived-date` |
| M-15 | needs owner | Rule 3 says remove 「已结算：金额已核算 · 已入账：已到账」, but `.fc-explainer` is pinned by reserved `scripts/shot-matrix.js` C60 and `scripts/selectors*.json` (`screenshot-fixture-completeness.test.js:590`), and it lives in the shared component. Owner decision plus integration edit |
| M-18 | needs owner | `p1-feedback-wiring-contract.test.js:171` pins `class="cu-scroll cy-fade-in"` as a required state fade, and rule 5 says fix timing rather than delete. The double entrance (fade plus row stagger) stays until the owner rules |
| M-19 | false positive | The checked state is a `cy-icon` mounted by `wx:if`, and the dot itself turns transparent, so a colour transition has nothing visible to animate |
| M-20 | already fixed (round 1) | — |
| M-21 | needs owner | Making 「承接设置」 heading non-tappable changes behaviour; the audit itself left the direction to the owner |
| M-22 | already fixed (base) | `game-node/index.wxml:55-56`: when playable, 「本站运行」 is rendered before the briefing |
| M-23, M-30 | needs owner | New entrance animations on pages that have none (reviews, decor views); see M-08 |
| M-24 | needs owner | Tied to B-2 (label vs field) |
| M-26, M-27 | no change (audit proposed none) | — |
| M-29 | Owner's choice | decor home cell values (「名称 · 地址 · 营业时间」…) are static captions, but the audit traces them to Figma 31:147 (rule 7) |
| M-31 | **done** | `game-node/index.wxss`: `.gn-check` transitions background and border over `--cy-motion-check-fill` (120 ms) |
| M-32 | **done** | game-node 「商家须知」 and 「接受后可填写准备清单」 removed (rule 3). `.gn-section-sub` CSS removed; grep shows no other user. Shot `06` |
| M-33 | already fixed (X-2 in base) | — |
| M-34 | **done** | coop-center official-event cards use the same `{{flyClass}}` and index delay as the plaza cards (`coop-center/index.wxml:63`). Motion only |
| X-1 … X-5 | already fixed (round 1) | X-1 verified on 5 pages above |
| B-1, B-3, B-4 | already fixed (round 1) | — |
| B-2 | needs owner / backend | Round 1 established that `averageRating` is all-time and the 「近 30 天均分」 label is wrong. A label change or a new backend field are both owner calls |
| B-5 | false positive | On the rendered page only the `cy-page-title` 「提现记录」 shows (x 16.6, y 116). The nav-bar title is not drawn at scrollTop 0 |
| B-6 | **done** | `ledger/index.wxml:116`: `aria-role="switch"` added. `role="switch"` is kept because `ui-final-gap-zero-0830-contract.test.js:15` pins it |
| §5 sparkline, decor upgrade dropdown, game-node two modes, search dimming | too big | redesigns, as the audit says |
| measure-merchant A1–A12 | already fixed (base) | checked in code: A1 `.rv-section-head` min-height, A2 `wx:if` on the inline state, A3 40rpx, A4/A5 finance padding and `.funds`, A6 `.cc-tabs` negative margin, A7/A8 `-14rpx` and `space-3`, A9 `.pc-merchant .pc-tags`, A10 `.card--targets .card-tit`, A11 marketing bottom padding, A12 `.cu-segs` 40rpx |

## Owner's choice (measured, not edited)

| Item | Measured | Why it stays |
|---|---|---|
| Customer drawer (host and join) content inset | 20.8 px, while its bottom 「定向广播」 button is at 16.6 | `project-drawer/index.wxss:54,75` are `ds-ok` 走查 v2 R07 定稿 (x=20) |
| Host 「进度」 card radius | 20.8 (xl), next to the 「主题」 card at 16.6 (lg) | 走查 v2 R04 (owner 2026-10-30): the progress card copies the player progress card's style |
| Host quick-action gate card 「邀请一个俱乐部承办后才能开始售票」 radius | 20.8 (xl), next to the 16.6 (lg) cards | follows the progress-card family; no ds-ok note, but it was finalized with R04/R16 |
| Broadcast sheet | even (see above) | owner-finalized 2026-11-01 |
| Negative money format 「¥-15.44」 (ledger list and batch-detail entries) next to 「+¥1000.00」 | — | It is the same rule in `ledger/index.js:496-499` and `batch-detail/index.js:203`. Changing it to 「−¥15.44」 is a family-wide money-format decision, not one page |
| Decor home captions (M-29) | — | Figma 31:147 copy |

## Shared requests (for r2-shared)

1. M-14: `components/cy/scene-merchant-profit/index.wxml:31`: `<text class="fc-arrived-date" wx:if="{{item.settled}}">`. When it is
   unsettled, the badge already prints the same `statusText`.
2. M-15 (with owner approval): delete `.fc-explainer` from the same component and drop it from `scripts/shot-matrix.js` C60 and
   `scripts/selectors*.json` at integration.
3. Note for the token owner: the ghost `cy-btn` uses `--cy-bg-glass`, which is `rgba(255,255,255,.88)` on the merchant light theme.
   On a white card it is invisible, so a ghost button reads as bare text. I aligned it locally (`.chapter-project-actions--btn`).
   A shared fix (for example a ghost background that differs from the card) would change every light-theme ghost button and needs
   an owner decision.

## Ledger and baseline drift

- **None.** `inventoryMatchesCurrent(action-ledger.json)` returns `true` on the final tree. Two drift sources were avoided on purpose:
  - The game-node spacing sits on the caption text, not on the `cy-btn` (a class on the button would have changed 2 ledger entries).
  - merchantapply keeps its line count (3 comment lines), so its line-keyed control ids do not move.
- `tests/lint/ui-integrity-baseline.js`: no change needed. No touched file has line-keyed anchors.
- Gates, run from `chengyinhub-xcx/` after fetching origin master: `ds-hardcode-gate.sh --ci` 0, `ui-integrity.js --warn-as-error` 0,
  `state-visibility-gate.js` 0, `ui-validity-gates.js` 0, `ui-theme-unit-lint.js --theme` 0 and `--units` 0,
  `setdata-dead-field-lint.js` 0, `a11y-tap-target-lint.js` 0 (0 new).
- Unit tests: `node --test "tests/unit/**/*.test.js"` → **15553 / 15553 pass, 0 fail** (run on the tree before the last M-09 edit). After M-09: `action-ledger.test.js` 46/46, `uiux-merchant-secondary-state-contract`, `merchant-ledger-*` and `ui-final-gap-zero-0830-contract` 67/67, and the gates re-run green.

## Overlap with other open work

- walk-fix l4-counter changes `pages/merchant/index/index.js` (hunks at about lines 1382 and 1447). My M-06 edit is a single line at
  990, so there is no shared hunk.
- walk-fix l5-onboard changes `pages/merchant/coop-center/index.wxml` (the route cover class and settle text). My M-34 edit is the
  `.cc-card` line, so again there is no shared hunk.
- walk-fix l3-sell changes `pages/topic/pricing/index.js` only. My pricing edits are WXSS only.
- None of the walk-fix lanes fixes any item in this file.

## Not exercised

- **Real data for:** game-node (the rig returns `GAME_SESSION_NOT_PREPARED`), reviews, aftercare, customer rows, batch-detail and the
  broadcast recipients. All of these were reached with dummy page data or response fixtures, as described at the top of this file.
- **Motion-only changes** (M-06 hold, M-31 fill, M-34 fly-in): the code was checked, but still shots cannot show them. The money
  counts were sampled every 25 ms, so they are measured, not inferred.
- **pricing/partner:** only the empty state was rendered.
- **B-1** (a non-active merchant without profile-write) still has no fixture.
- **Real-device fonts** (PingFang vs the rig's Noto CJK): about 1 px differences are possible.

STATUS: COMPLETE
