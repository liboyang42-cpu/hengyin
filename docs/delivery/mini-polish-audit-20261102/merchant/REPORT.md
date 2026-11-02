# Merchant lane — polish audit (2026-11-02)

Base: `claude/mini-polish-audit-20261102` (brief at a1d30fb). Scope: `chengyinhub-xcx/pages/merchant` (27 pages),
`pages/coop` (8 pages), and the shared components they mount. Static reading only; nothing was rendered.
Evidence `code` = read directly in the file; `inferred` = follows from code + CSS/WeChat semantics but not seen on a device.

Assumptions (taken without asking, per the task):
- "Frequency of use" order is inferred from the merchant tab bar and the home quick actions: home → ledger/finance →
  customers → reviews/verify/aftercare → marketing → decor/game-node → coop pages → rare pages (apply, citynode, team…).
- Owner rule 7 ("no-data shows —") is taken to cover the money pages; marketing keeps its owner-approved 0.
- `cy-fly-in`'s small overshoot is owner-approved (2026-10-22) and is not re-opened; only a property clash is reported.

## 1. Summary (top 5 by user impact)

1. **Money numbers outside the home land instantly.** Only the home "累计收入" counts up (slow-fast-slow, 1.3 s). The ledger hero, order/batch detail heroes, profit "已入账分润" and settlement-detail amount all snap in. One shared change (count the numeric part with the existing `countIn` + `easeInOutCubic`) covers 5 pages. (M-02, X-1)
2. **Aftercare tabs vanish while a tab loads.** Switching a chip sets `pageState: 'loading'`, so the whole page, tabs included, becomes a skeleton and then mounts again. Verify-records and aftercare also mount a new chip row whenever the list switches between empty and ready. This breaks "the page shape never changes while loading". (M-10, M-11)
3. **Merchant home entrance order is decided by `nth-child`, so the hierarchy is wrong when alerts show.** The "有 N 笔售后待回应" and "竞猜待给答案" alerts have no entrance rule, so they appear first while the hero and actions are still hidden. Section delays also shift with whichever siblings render. A duplicate count driver (`rvK`) also runs about 36 setData calls for nothing. (M-01, M-03)
4. **Press feedback is missing on cards that fly in.** `cy-fly-in` animates `transform` with `fill-mode: both`, which overrides the `scale(.98)` from `cy-pressed`/`rv-tap`. The coop-center plaza cards and the opened home message stack lose their press scale. Switching the keyframes to the individual `translate`/`scale` properties fixes every user. (X-2)
5. **Structure: the decor "合作经营" view splits perks across cards, and game-node puts the daily run actions below the long briefing.** These are small regroupings, with no layout change. (M-20, M-22)

The brief's tap-feedback gap numbers (`citynode/create` 7/0, `perks` 5/1, `coop-setting` 8/1, `ai-npc` 31/6) are almost all
false positives. Those taps are on `cy-btn` / `cy-cell`, which carry their own `hover-class` (`btn--press`, `cell--press`).
After excluding components, only native `<button>`s remain (see X-4). No bare `view`/`text` tap without `hover-class` was found in the 42 WXML files of this lane.

## 2. Per page

Row format: `ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence`

### merchant/index (workbench, tab root)
Already good: chrome (nav, tabBar) does not animate; revenue is always in place at "¥0.00" and counts with `easeInOutCubic` over 1300 ms; spacing and radius are almost all tokens; every tap has press feedback.

| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| M-01 | merchant/index | pages/merchant/index/index.wxss:740-748; index.wxml:114-133 | `.rv-aftercare-alert` ("有 N 笔售后待回应", "有 N 轮竞猜待给答案") | no `--pm-w` rule, so it is visible from frame 1 | the alert shows before the hero/actions above it (they are hidden for 34 ms and then rise over 0.32 s), so the order runs bottom-first | give `.rv-aftercare-alert` the same `--pm-w` filter/translate rule with a delay between actions (40 ms) and the first section (80 ms), e.g. 60 ms | motion-entry | S | low | P1 | code |
| M-02 | merchant/index | index.wxss:744-748 | `.rv-section:nth-child(2..n+5)` delays | `nth-child` counts all scroll-view siblings (error, apply-state, hero, actions, 0-2 alerts) | the projects section gets 120, 160 or 200 ms depending on which alerts exist, and `:nth-child(2)` never matches a section | replace the nth-child ladder with an explicit `style="--pmi:N"` on the three `.rv-section`s (pattern already used in `scene-merchant-profit/index.wxss:54`) | motion-entry | S | low | P2 | code |
| M-03 | merchant/index | index.js:982 | `pageMotion.countIn(that, 'rvK')` | runs a 0→1 counter (600 ms, about 36 setData calls) | the WXML never reads `rvK`; the visible number is driven by `countRevenueTo` (index.js:1006). The two drivers run together and the extra setData calls compete with the real count | delete the `countIn('rvK')` call (keep `countRevenueTo`) | motion-entry | S | low | P1 | code |
| M-04 | merchant/index | index.js:978-988 | `revBars` (7-day revenue) | computed and setData'd on every dashboard load | not rendered anywhere in index.wxml. Reference B (chart draws after the number lands) has no target here. Showing a chart would be a new element (see §5) | either drop the `revBars` setData or keep it for a later approved chart; no UI change proposed | structure | S | low | P2 | code |
| M-05 | merchant/index | index.wxml:301-303, 292-294 | more-menu "管理优惠券" / "退款售后" | both use `icon_wallet.svg` | two rows in one 5-row sheet share an icon, so they are harder to tell apart at a glance | point "管理优惠券" at an existing coupon/ticket icon if one exists in `/images` (no new artwork) | structure | S | low | P2 | code |
| M-06 | merchant/index | index.js:1006-1020 | revenue count start | starts as soon as data arrives, even when the hero is still in its 0.32 s rise | on a warm cache, part of the slow start happens while the number is still faded | start `countRevenueTo` after the hero has risen: wait ~150 ms when `pmPage` is non-zero, the same hold `countIn` already uses (page-motion.js:43) | motion-entry | S | low | P2 | inferred |

### merchant/ledger (财务 / 核销与退款 / 结算)
Already good: per-section skeleton, inline "正在更新…" status, and tab shell (`shared-slab`) that is not unmounted while switching; one hero number per page; no-data money shows "待定" rather than a fake 0.

| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| M-07 | ledger | pages/merchant/ledger/index.wxml:105 | `.fin-hero__amt` "本月个人账户{{netLabel}}" | text snaps in when `overview` arrives | the main money number of the money page has no slow-fast-slow landing, unlike the home | on first `overview` arrival call `pageMotion.countIn(this,'lgK',{duration:1300, ease: motion.easeInOutCubic})` and render the numeric part through `cnt.f` (see X-1 for the "¥" prefix) | motion-entry | M | low | P1 | code |
| M-08 | ledger | ledger/index.wxml:29-33 | `.fin-summary` "本月核销 / 待结算 / 已入账" | three numbers snap in, with no entrance | the redemptions view has no entrance at all (no `pageMotion.enter`), while the sibling verify-records and aftercare views rise in | add `pageMotion.enter(this,'pmList')` before the first `redemptionGroups` setData and the `--pm-l` rule on `.fin-group` (copy of verify-records/index.wxss:22-30) | motion-entry | S | low | P2 | code |
| M-09 | ledger | ledger/index.wxml:96, 103 | overview skeleton `type="amount"` → `.fin-hero` | skeleton is in `.ledger-inline-state`, hero is a sibling below it | if the skeleton height ≠ hero height (hero has 3 kv rows), the CTA and switch below jump when data lands | give `.ledger-inline-state` a min-height equal to the hero while `settlementLoading.overview && !overview` (same "hold" idea as marketing `.mk-hold--hero`) | loading-state | S | low | P2 | inferred |

### merchant/ledger/order-detail, ledger/batch-detail
Already good: amount skeleton matches the hero; "待定" fallback rendered as muted.

| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| M-12 | order-detail / batch-detail | ledger/order-detail/index.wxml:20; batch-detail/index.wxml:9 | `.fin-hero__amt` | snaps in | same as M-07 | same `countIn` treatment, through the shared helper from X-1 | motion-entry | S (after X-1) | low | P2 | code |

### coop/finance (分润明细) → `components/cy/scene-merchant-profit`
Already good: list stagger uses `--pmi` index (robust); null money stays `null` → "—"; `fc-income` shows "—" when unsettled.

| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| M-13 | coop/finance | components/cy/scene-merchant-profit/index.wxml:8; index.js:27-41 | `cy-stat-card` "已入账分润" | value is the string `'¥'+x` and snaps in | it is the hero money number on this page and does not count. `cy-stat-card` has no count support, and `cnt.f` returns non-numeric strings unchanged | pass the count progress into `cy-stat-card` through an optional `countK` prop that applies `cnt.f` to the numeric part only (see X-1) | motion-entry | M | low | P1 | code |
| M-14 | coop/finance | scene-merchant-profit/index.wxml:31,34 | topic card bottom row | `fc-arrived-date` text = `statusText` and badge label = `statusText` when not settled | the same status string is printed twice on one card | when `!item.settled`, hide `.fc-arrived-date` (the badge already says it) | structure | S | low | P2 | code |
| M-15 | coop/finance | scene-merchant-profit/index.wxml:40 | `.fc-explainer` "已结算：金额已核算 · 已入账：已到账" | a permanent helper caption under the list | it conflicts with owner rule 3 (no helper captions); the badges already carry the state | owner call: remove it, or keep it as an approved exception | structure | S | low | P2 | code |

### coop/settlement-detail, coop/withdraw, coop/withdraw/records
Already good: settlement-detail keeps the old detail during refresh, with a status strip on top; withdraw is a single honest neutral state with a single action.

| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| M-16 | settlement-detail | pages/coop/components/settlement-detail/index.wxml:11 | `.sd__amount` | snaps in | hero money number, same as M-07 | X-1 helper | motion-entry | S (after X-1) | low | P2 | code |

### merchant/customer (客户) and customer/detail
Already good: filter chip shows a live count ("筛选 · N"); the list rises with a stagger; refreshes keep old rows and show "正在更新客户名单…"; phone and copy taps have feedback.

| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| M-18 | customer | pages/merchant/customer/index.wxml:73 (`cu-scroll cy-fade-in`) + :75 (`--pm-l` list) | list entrance | the whole scroll-view fades in (220 ms) **and** the rows rise in (0.32 s staggered) | two entrance animations on the same content, so the rows look softer and later than on verify-records/crm | drop `cy-fade-in` from `.cu-scroll`, keep the row stagger | motion-entry | S | low | P2 | code |
| M-19 | customer | customer/index.wxss:141-155 | `.cu-check` batch-select dot | the fill swaps instantly | reference D (state delight): the select dot could fill instead of popping | add `transition: background-color var(--cy-motion-check-fill) var(--cy-ease-out), color var(--cy-motion-check-fill) var(--cy-ease-out)` (the token exists, tokens.wxss:442) | motion-feedback | S | low | P2 | code |
| — | customer | customer/index.wxml:93 | "共 {{castCount \|\| 0}} 位客户 · 已全部显示" | `castCount` initial 0, set from server total | only rendered after a successful load (`!hasMore`), so the 0 is real; no change | — | empty-state | — | — | — | code |

### merchant/reviews (口碑, manage mode)
Already good: server-side filter counts are not shown when missing ("绝不拿已加载页去数"); "—" on reply rate; segments have press feedback.

| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| M-23 | reviews | pages/merchant/reviews/index.wxml:36-81 | summary + 2×2 stat grid | skeleton → content snaps in; no entrance | the most stat-dense daily page has no staged reveal (reference A: header → number → grid → segments → list) | `pageMotion.enter(this,'pmPage')` on first ready + `--pm-*` rules on `.review-summary`, `.review-stats-grid`, `.review-segments`, list | motion-entry | S | low | P2 | code |
| M-24 | reviews | reviews/index.wxml:40 and :76-81 | `averageRating` shown twice | summary line shows `averageRating`; stat card "近 30 天均分" also shows `averageRating` | the same number is printed twice, and one copy carries a 30-day label the data does not support (see §4 B-2) | after the label is fixed (§4), consider showing the rating once only | structure | S | low | P2 | code |

### merchant/verify-records (核销记录) and merchant/aftercare (退款售后)
Already good: date-grouped rows, a group count on each header, `--pm-l` stagger, client-side bucket switching on verify-records.

| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| M-10 | aftercare | pages/merchant/aftercare/index.js:83-88, 104; index.wxml:8,16-19,26-28 | chip tabs during tab switch | `switchBucket` → `loadList(true)` → `pageState:'loading'` → the skeleton replaces the **whole** page incl. `cy-tabs` | the tabs disappear while loading and mount again, so the page shape changes and the chip pill restarts. Ledger already solves this with a shared shell | move `cy-tabs` (and the search) above the `pageState` chain, rendered for loading/empty/ready after the first load, and use the skeleton only for the list area. Give `cy-tabs` `shared-slab` like ledger:18 | loading-state | M | med | P1 | code |
| M-11 | verify-records | pages/merchant/verify-records/index.wxml:18-28 | `cy-tabs` in two branches | the empty branch and the ready branch each mount their own `cy-tabs` | switching to a bucket with no rows mounts a new chip row, so the pill jumps instead of sliding (same in aftercare :16-28) | hoist one `cy-tabs` above the `empty`/`ready` branches | motion-transition | S | low | P1 | code |
| M-25 | verify-records | verify-records/index.wxss:22-30 | `.vrl-group:nth-child(n)` | first group is `nth-child(2)` because `cy-tabs` is child 1 | the first group waits 30 ms for no reason; harmless but fragile | switch to `--pmi` index (X-3) | motion-entry | S | low | P2 | code |

### merchant/marketing (营销, tab root) and marketing/ai-insight
Already good: the hero skeleton holds a fixed height (`.mk-hold--hero`) so the page does not jump; the hero counts from 0 on first arrival; tiles have press feedback; owner's 0 rule respected.

| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| M-26 | marketing | pages/merchant/marketing/index.wxml:49; index.js:307 | hero count | `countIn` default: 600 ms, `easeOutCubic` (fast-slow) | not a money number, so the fast-slow ease is acceptable. Note it only so X-1 does not change marketing by accident | none; keep the default for non-money numbers | motion-entry | — | — | — | code |
| M-27 | marketing | marketing/index.wxml "我的内容" 3-up tiles (after :62) | content count tiles | the numbers snap in, while the hero above counts | the hierarchy is fine (the hero is the only number that counts). No change proposed | — | motion-entry | — | — | — | code |

### merchant/crm-campaigns, relation, coop/list
Already good: all three use `pageMotion.enter` with a list stagger; refreshes are inline.

| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| M-28 | coop/list | pages/coop/list/index.wxss:152-156; index.wxml:56,130,150,191,235 | `.inv:nth-child(n)` stagger | `.inv` rows are siblings of 0-4 `.inline-state` status rows | the stagger offset depends on how many status strips render (X-3). The `--pm-l` style is also repeated on every row instead of once on the container | move the `--pm-l` style to the list container and use `--pmi:{{index}}` | motion-entry | S | low | P2 | code |

### merchant/decor (品牌中心, 5 views: home / brand / coop / basic / qualification)
Already good: views are already grouped into white cards with cells; returning refreshes silently; the save receipt is aria-live only and does not push content down; skeleton has a fixed map height.

| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| M-20 | decor (coop view) | pages/merchant/decor/index.wxml:206-276, 337-345 | cards in the "合作经营" view | card 2 = 招牌主推 / 口碑 / 常备权益; card 3 = 升级权益 / 成为城市节点; card 4 = 衍生权益 (alone) | perks are split: "常备权益" and "衍生权益" are the same kind of item but sit two cards apart, and the last card holds a single cell | move the "衍生权益" cell into card 2, directly under "常备权益" (move its `hairline="{{false}}"`), and delete card 4. The layout stays the same | structure | S | low | P1 | code |
| M-21 | decor (coop view) | decor/index.wxml:192-205 | "承接设置" heading + "合作诉求" cell | both `bindtap="goCoopSetting"` | two stacked tap targets in one card open the same page (a redundant entry, reference C) | make the heading non-tappable (drop `bindtap`/`hover-class`/`aria-role`) and keep the cell as the single entry; or the reverse if the owner prefers | structure | S | low | P2 | code |
| M-29 | decor (home view) | decor/index.wxml:106-117 | cell values "名称 · 地址 · 营业时间", "故事 · 特色", "形象 · 语音", "承接 · 主推 · 权益", "执照 · 品牌材料" | static helper captions in the value slot | rule 3: only "{{gallery.length}} 张照片" carries real data. Owner call; these may be approved Figma copy (31:147) | if the owner agrees, set `value=""` on the static ones and keep the photo count | structure | S | low | P2 | code |
| M-30 | decor | decor/index.wxml:53-54 | view switch (home → brand/coop/basic) | no entrance; content swaps in place | the long coop/basic views appear all at once; a staged rise by card helps scanning | `pageMotion.enter(this,'pmPage')` on `goSection` + `--pm-*` rule on `.dc-card` with `--pmi` | motion-entry | S | low | P2 | code |

### merchant/game-node (本站准备与运行)
Already good: one card per concern, ordered as in the design source (稿 469:1002); "—" for unknown capacity; checklist rows have press feedback and aria-checked.

| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| M-22 | game-node | pages/merchant/game-node/index.wxml:56-83 vs :141-149 | "这一站怎么接待" runbook card vs "本站运行" card | the runbook (long) always comes first; "本站运行" (出示打卡码 / 扫码核验 / 暂停接待) is 3-4 cards down | once the station is live, the daily actions are below the fold every visit; the runbook is reference material at that point | when `station.playable`, render the "本站运行" card directly after the hero (duplicate the block behind `wx:if` or wrap the two in an order flag). The layout of each card is unchanged | structure | M | med | P1 | code |
| M-31 | game-node | game-node/index.wxss:149-160 | `.gn-check` checklist box | the fill swaps instantly | reference D: the checklist box is the textbook case for a fill | add `transition: background-color var(--cy-motion-check-fill) var(--cy-ease-out), border-color var(--cy-motion-check-fill) var(--cy-ease-out)` | motion-feedback | S | low | P2 | code |
| M-32 | game-node | game-node/index.wxml:60, 87 | `.gn-section-sub` "商家须知", "接受后可填写准备清单" | helper captions next to the section titles | rule 3 | owner call: remove them | structure | S | low | P2 | code |

### merchant/coop-center (合作中心)
Already good: tab row and bell share one line; "正在刷新…" keeps the old cards; the plaza cards replay their fly-in on every show and hide before leaving (no stale flash).

| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| M-33 | coop-center | pages/merchant/coop-center/index.wxml:27-28 | `.cc-route` with `cy-fly-in` + `hover-class="cy-pressed"` | the fly-in keyframes write `transform` with `animation-fill-mode: both` | the animation's held `transform` overrides `.cy-pressed { transform: scale(.98) }`, so plaza cards only dim on press and never scale (fix in X-2) | X-2 | tap-feedback | S | low | P1 | inferred |
| M-34 | coop-center | coop-center/index.wxml:63 | 官方活动 tab cards | no entrance at all, while the plaza tab flies in | the two tabs of one page enter differently | apply the same `flyClass` + index delay to `.cc-card` | motion-entry | S | low | P2 | code |

### Rarely visited pages (one line each)
- **apply** (入驻申请): already uses `cy-rise-in` (6 places), and all taps have feedback. Nothing to propose.
- **citynode / citynode/create**: list has motion and skeleton; the create page's 7 taps are all `cy-btn` (they have feedback). Nothing to propose.
- **decor/perks, decor/coop-setting, decor/gallery, decor/ai-npc**: the brief's tap-gap counts are `cy-btn`/`cy-cell` (feedback built in). ai-npc's two native `<button>`s are covered by X-4. Nothing else.
- **team**: separate skeletons for access and the member list keep the shape. Nothing to propose.
- **profile, predict, relation, crm-campaigns, aftercare/detail, customer/detail**: consistent shells, inline refresh states, press feedback present. predict "共 {{items.length}} 轮等你给答案" is real data. Nothing to propose.
- **coop/invite, coop/invite-detail, coop/nearby**: per-block skeletons and inline status rows; every view tap has hover. nearby's 2 untagged taps are a `cy-merchant-card` and a `cy-btn` (assumed to carry their own feedback; `cy-merchant-card` not verified). Nothing to propose.

## 3. Cross-page patterns (one shared fix, many pages)

| ID | fix | solves | effort | risk | evidence |
|---|---|---|---|---|---|
| X-1 | **Money count helper.** Add `pageMotion.countMoney(page, key)` = `countIn` with `{duration:1300, ease: motion.easeInOutCubic}` (the values the home already uses, index.js:59, 1017), and a `count.wxs` `money(text, k)` that scales only the numeric run inside "¥1,234.00" / "−¥5.00" and leaves "待定"/"—" untouched. `cy-stat-card` gets an optional `count-k` prop. | M-07, M-12, M-13, M-16 (ledger, order-detail, batch-detail, coop/finance, settlement-detail) | M | low (display-only; the real value is never changed, the same contract as `count.wxs`) | code |
| X-2 | **`cy-fly-in` without `transform`.** In `style/motion-entry.wxss:19-23` animate the individual `translate` and `scale` properties instead of `transform` (page-motion already relies on `translate` working on this webview). The timing and the approved overshoot stay exactly the same. | M-33 and the opened message stack on merchant/index (index.wxml:255, `rv-tap` + `cy-fly-in`), and any player page using `cy-fly-in` on a pressable element | S | low | code (keyframes) + inferred (render) |
| X-3 | **Index-driven stagger.** Replace the copied `:nth-child(2..9)` delay ladders with `--pmi:{{index}}` + `min(var(--pmi),8)*30ms`, already in `scene-merchant-profit/index.wxss:54`. | M-02, M-25, M-28, plus crm-campaigns (`.ccl-row`), customer, relation | S per page | low | code |
| X-4 | **Native `<button>` press feedback.** customer (`cu-inline-button` ×4), reviews (2 unstyled buttons), ai-npc (`npc-voice-hold` ×3) rely on WeChat's default `button-hover` (grey wash) instead of `cy-pressed`. Add `hover-class="cy-pressed" hover-stay-time="80"` to each. | customer, reviews, decor/ai-npc | S | low | code (attributes) + inferred (default look) |
| X-5 | **Chip tabs outside the state chain.** Render filter chips once, above loading/empty/ready, with `shared-slab` (ledger:18 is the reference). | M-10, M-11 (aftercare, verify-records) | S–M | low–med | code |

Radius/spacing: in this lane the WXSS reads tokens almost everywhere (`--cy-radius-*`, `--cy-space-*`, `--cy-legacy-*`). The home's raw `gap: 24rpx / 10rpx / 12rpx / 6rpx` (index.wxss:253, 278, 281, 317, 352) and `margin-top: 4rpx`-type values are inside project cards and do not create visible inconsistencies. A de-slop pass like reference C is **not** needed on the merchant side.

The page-motion rules hard-code `cubic-bezier(.2,.82,.2,1)` and `.32s` (copied into ~10 WXSS files). This is not a visible problem, but naming it as a token (`--cy-ease-page-rise`) would make the "a little faster" tuning a one-line change later. P2, S.

## 4. Real bugs noticed (out of scope to fix, reported)

- **B-1 decor: empty white card for staff without profile-write.** `pages/merchant/decor/index.wxml:116-118`: the card is shown when `!merchantAccess.active`, but its only cell needs `canWriteProfile`. A not-yet-active operator with `canManageCoop` only sees an empty `.dc-card`. Move the `canWriteProfile` condition onto the card. (code)
- **B-2 reviews: the "近 30 天均分" card shows the all-time average.** `pages/merchant/reviews/index.wxml:80-81` binds `averageRating`, the same field as the summary line (:40); `view-model.js:102` has no 30-day field. Either the label or the field is wrong; the backend contract decides which. (code)
- **B-3 reviews: the reply-pending recovery block is unstyled.** `reviews/index.wxml:2-8`: a bare `<view aria-role="alert">` with two default `<button>`s sits before the toast/skeleton, without a class. On device this will likely render as full-width grey WeChat buttons at the top of the page. (code; look inferred)
- **B-4 merchant/index: the dead `rvK` counter** (M-03) and the **unrendered `revBars`** (M-04) cost setData on every load. (code)
- **B-5 coop/withdraw/records**: `cy-nav-bar title="提现记录"` and `cy-page-title title="提现记录"` both set (index.wxml:7-8); other pages leave the nav title empty when a page title is present (e.g. withdraw/index.wxml:7). The title may show twice. Not proposing a title change (rule 1); flag only. (code; display inferred)
- **B-6 ledger**: `role="switch"` on `.fin-alert-row` (ledger/index.wxml:115) is not a WeChat accessibility attribute (`aria-role` is), so screen readers ignore it. (code)

## 5. Would need a redesign, not proposing

- **A 7-day revenue sparkline on the home** (reference B, "chart draws after the number lands"). The data (`revBars`) already exists, but there is no element to animate; adding one is a new UI element.
- **The decor "升级权益" dropdown** (decor/index.wxml:279-327) holds up to 10 status lines, granted records, tiers and a contact cell inside a 260 px panel. It needs to become a sheet or its own view, which is more than a small edit.
- **Game-node as two modes (setup vs. live)** instead of one long page. M-22 is the minimal version; a real split is a redesign.
- **Search dimming instead of refetching** (reference D) on customers/aftercare: customer search is server-side and aftercare searches only loaded rows ("只在已加载的 N 笔中搜索"). Dimming would change the search model, not polish it.

STATUS: COMPLETE
