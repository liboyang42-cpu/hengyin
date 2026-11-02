# measure-merchant: merchant pages measured on the rendered screen

Lane: measure-merchant (report only, no product code changed). Base: `integrate/page-polish-20261102`. Rig: `claude/cloud-walk-20261101-rig`
(390×844 browser phone, real backend at master a1d30fb). The merchant pages on the base branch are byte-identical to a1d30fb.
Only the kit files differ, so every file:line below refers to the base branch. Identity: fixture merchant `e2e-openid-9002`
(「探店日样板咖啡」, merchant row 8001).

## How this was measured (and why it differs from the earlier audit)

The earlier merchant audit (`mini-polish-audit-20261102-merchant/REPORT.md`) read WXSS. It concluded that "spacing and radius are almost all tokens"
and that "a de-slop pass is **not** needed on the merchant side". Every value it checked was a token, but tokens only tell you which values
were typed. They don't show what the finished screen looks like. Same-role elements still end up uneven when a token is applied on one
sibling and not on the other, when margins stack, or when a component's padding is added on top of the page's. This pass measures the
rendered pixels, the way the de-slop videos do.

- `tools/measure.js` runs inside the rig page. It walks the rendered tree through shadow roots and slots. Each page section is reduced to
  its **ink box**, meaning the union of text glyph boxes, images and boxes that have a background, border or shadow. Empty wrapper padding is
  not counted. Gaps are measured ink to ink with `getBoundingClientRect` and `Range.getBoundingClientRect`. For every card it reads
  `getComputedStyle` (left/right inset, radius, padding).
- `tools/run.mjs` takes each measurement at scrollTop 0. It labels every vertical gap: the number on the left is a section gap, and `T n`
  near the centre is a title→content gap. Each label is **green** if it is within 2 px of that page's most common value of the same kind,
  and **red** if not. It also labels each card's left/right inset, draws each card with a dashed outline and tags it `r<radius>
  p<top/right/bottom/left>`. Nested cards of one class are red when they disagree. Finally it scrolls through the page and stitches one tall
  PNG (`tools/stitch.py`).
- `after-*.png` are **previews, not changes**. The proposed edit is injected as CSS into the live page or component shadow root (rpx
  written as px at 390 width), and the page is measured again. No product file was edited.
- Units: values are px at 390 width. 1 rpx = 0.52 px, so rpx = px ÷ 0.52. Tokens: `--cy-page-x` 32rpx = 16.6 px, space-2 16rpx = 8.3,
  space-3 24rpx = 12.5, space-4 32rpx = 16.6, space-5 48rpx = 25, radius-md 24rpx = 12.5, radius-lg 32rpx = 16.6, radius-xl 40rpx = 20.8.
- Red/green is computed per page. A red label is a candidate, not a verdict. Each one was checked against the code before it was placed in
  a group below. Differences under 2 px are treated as noise unless the two items sit side by side.

Data: the rig DB was seeded through the app's own chains (`chains.py finance`: platform → merchant_club → sale_close → player → finance,
**all PASS**, topic 990014 「E2E 探店日一期」). Merchant 9002 therefore has a real project card, one redemption in the ledger and two unverified
records.

## Summary

| # | Page | Verdict | Annotated shot |
|---|---|---|---|
| 1 | workbench `pages/merchant/index/index` | **uneven**: 「开通前置检查」 title→card 20.6 vs 31, section gap 27 vs 37.4 | `shots/01-workbench.png`, after `shots/after-01-workbench.png` |
| 2 | ledger, redemptions view | even | `shots/02-ledger.png` |
| 2b | ledger, settlement view `?view=settlement` | **uneven**: title→hero 21.6 vs 9.1; card gaps 20.8 / 12.5 / 27 | `shots/02b-ledger-settlement.png`, after `shots/after-02b-settlement.png` |
| 3 | ledger order-detail (tapped from ledger) | even; sparse (no settlement timeline) | `shots/03-order-detail.png` |
| 4 | ledger batch-detail | **no data**: only the 「结算批次不可见」 state | `shots/04-batch-detail.png` |
| 5 | reviews `?mode=manage` | **no data**: 「还没有真实到店评价」 | `shots/05-reviews.png` |
| 6 | customer | **uneven**: three left edges 20.8 / 16.6 / 12.5 within 120 px (two are approved); list empty | `shots/06-customer.png`, after `shots/after-06-customer.png` |
| 7 | verify-records (「未核销 2」 tab) | **uneven vs siblings**: title→chips 16.4 (others 9.1), chips→list 35.2 (aftercare's rule gives 19.8) | `shots/07-verify-records.png`, after `shots/after-07-verify.png` |
| 8 | aftercare | header measured (title→chips 16.4, same as #7); list **no data** | `shots/08-aftercare.png` |
| 9 | marketing | even on top; banner tucks **8.3 px under the tab bar** at full scroll; coupons empty | `shots/09-marketing.png` |
| 10 | decor | even (20/20/20, approved Figma inset and radius) | `shots/10-decor.png` |
| 11 | coop-center | cards even; **first tab label at 37.4 px vs cards at 12.5** | `shots/11-coop-center.png`, after `shots/after-11-coop-center.png` |
| 12 | coop finance `pages/coop/finance/index` | **uneven**: cards at 33.3 px inset (double padding) vs title at 16.6; 「可提现」 row touches the button (**0 px**) | `shots/12-coop-finance.png`, after `shots/after-12-coop-finance.png` |
| 13 | coop invite | mostly even; step ③ title off by 4 px and nearly equidistant (29 / 23) | `shots/13-coop-invite.png`, after `shots/after-13-invite.png` |
| 14 | profile `pages/merchant/profile/index?id=8001` → redirects to `pages/userinfo/userinfo` (cy-profile, merchant view) | **uneven**: 「品类」 title→tag 12.3 vs 27; tag→「营业信息」 17.5 vs 32.7 | `shots/14-profile.png`, after `shots/after-14-profile.png` |

## A. Real unevenness worth fixing

Values are before → after. "After" values were measured on the CSS-injected preview, except where marked "computed".

| # | Page | Same-role items that disagree | Measured px (rpx) | Rule to change (file:line) | Smallest edit | After |
|---|---|---|---|---|---|---|
| A1 | workbench | Section title → its card: 「项目」 31, 「开通前置检查」 **20.6**, 「我的消息」 31. Card → next section title: **27** vs 37.4 | 31 (60) vs 20.6 (40); 27 (52) vs 37.4 (72) | `chengyinhub-xcx/pages/merchant/index/index.wxss:402-408` `.rv-section-head` has no height. Only heads with a 「全部」 link get 44 px, from `.rv-section-more { min-height: 88rpx }` at `:416-417` (wxml `:144`, `:251`). The 「开通前置检查」 head (wxml `:226-228`) has no link, so it is about 21 px tall. | add `min-height: 88rpx;` to `.rv-section-head` | 31 / 31 / 31 and 37.4 / 37.4 (verified) |
| A2 | ledger settlement | Page title → first card: settlement **21.6** vs redemptions 9.1 (same page, other view) and order-detail 9.1 | 21.6 (41.5) vs 9.1 (17.5) | `pages/merchant/ledger/index.wxml:95` renders an empty `<view class="ledger-inline-state">` even when nothing is loading. `pages/merchant/ledger/index.wxss:11` gives it `margin-bottom: var(--cy-space-3)` (12.5 px). | wxml:95 add `wx:if="{{settlementLoading.overview \|\| settlementErrors.overview}}"` (both children already use only these flags) | 9.1 (verified with `:empty`) |
| A3 | ledger settlement | Three stacked cards: hero → 「去我的资产」 **20.8**, button → 「结算到账提醒」 **12.5**, alert → 「收入明细」 **27**. The rest of the finance family uses 40rpx ("分组间 20px", `pages/merchant/style/merchant-finance.wxss:10,18,47`) | 20.8 / 12.5 / 27 (40 / 24 / 52) | `pages/merchant/ledger/index.wxss:59` `.fin-assets-cta { margin: 0 0 var(--cy-space-3) }`; `:60` `.fin-alert-row { … margin-bottom: var(--cy-space-5) … }` | `:59` → `margin: 0 0 40rpx`; `:60` `margin-bottom` → `40rpx` (same literal as merchant-finance.wxss:18; add `/* ds-ok: 资金域分组间 20px */` if the gate asks) | 20.8 / 20.8 / 22.8, which equals redemptions summary → group 22.8 (verified) |
| A4 | coop finance | Page inset: title 「分润明细」 at 16.6, every card at **33.3** (page padding + the component's own `.mp` padding) | 33.3 (64) vs 16.6 (32) | `pages/coop/finance/index.wxss:5` `.fin-shell{ … padding:0 var(--cy-page-x); … }` and `index.wxml:9` `flush="{{true}}"`. The component already pads: `components/cy/scene-merchant-profit/index.wxss:9`. | wxss:5 `padding:0`; wxml:9 drop `flush="{{true}}"` so the title keeps its own 32rpx | cards 16.6, title 16.6 (verified) |
| A5 | coop finance (and every user of `cy-scene-merchant-profit`) | Stat card → 「可提现」 row 12.5, 「可提现」 row → 「联系平台客服提现」 button **0** | 12.5 vs **0** | `components/cy/scene-merchant-profit/index.wxml:10` `<cy-funds-stages wx:if="{{isOwner}}" />` has no spacing; `.withdraw-entry` (`index.wxss:20`) has no top margin | wxml:10 add `class="funds"`; wxss add `.funds { display: block; margin-bottom: var(--cy-space-3); }` | 12.5 / 12.5 (verified). Optional in the same rule: `--cy-radius-md: var(--cy-radius-lg);` so this row's 12.5 corners match the stat card's 16.6 directly above it, the way `.stat-grid` already does at `:17` |
| A6 | coop-center | Left edge: first tab label 「官方活动」 at **37.4**, route cards at 12.5. Three paddings stack: `.cc-tabbar` 24rpx, `.cc-tabs` 24rpx, item 24rpx | 37.4 (72) vs 12.5 (24) | `pages/merchant/coop-center/index.wxss:12-14` `.cc-tabs { padding: 0 var(--cy-space-3); }` | replace with `.cc-tabs { margin-left: calc(-1 * var(--cy-space-3)); }` | label 12.5 = card edge (verified) |
| A7 | verify-records vs aftercare vs ledger | Page title → filter pills: verify-records **16.4**, aftercare **16.4**, ledger 9.1. The chip variant reserves a 14rpx transparent hit band above the pills (`components/cy/tabs/index.wxss:162`). | 16.4 (31.5) vs 9.1 (17.5) | `pages/merchant/verify-records/index.wxss:9` `.vrl-filter`; `pages/merchant/aftercare/index.wxss:8` `.acl-filter` | add `margin-top: -14rpx; /* ds-ok: 抵消 chip 热区外扩留白 */` to both (the hit area is kept) | 9.1 (verified on verify-records) |
| A8 | verify-records vs aftercare | Filter → first list block. verify-records was built "照 pages/merchant/aftercare" (wxml:5), but its rule is 48rpx while aftercare's is 24rpx | 35.2 (68) on verify-records; aftercare computed 19.8 (no rows to render) | `pages/merchant/verify-records/index.wxss:9` `margin-bottom: var(--cy-space-5)` | → `var(--cy-space-3)` (same as `aftercare/index.wxss:8`) | 22.7 (verified, together with A7) |
| A9 | profile (merchant view of cy-profile) | Section title → first content: 「品牌故事」 26.7, **「品类」 12.3**, 「营业信息」 27.1, 「承接能力」 27.1. Previous block → section title: 32.6, **17.5** (after the tag row), 32.9 | 12.3 (24) vs ~27 (52); 17.5 (34) vs ~32.7 (63) | `subpackageMember/components/cy/profile/index.wxss:664` `.pc-tags { … margin-bottom: var(--cy-space-2); padding: 0; }`. The other blocks bring their own vertical padding (about card, rows); the tag row brings none | `padding: 0` → `padding: var(--cy-space-3-5) 0 var(--cy-space-4)` | 26.7 / 26.8 / 27.1 / 27.1 and 32.6 / 34.2 / 32.9 (verified). **Shared component:** the player-side (dark) profile also uses `.pc-tags`; check that view before landing |
| A10 | coop invite | Step titles ①② sit inside cards at x 31.2. Step ③ is deliberately card-less (`index.wxss:34`), but its title is nudged to x **20.8**, so it lines up with neither the rows (16.6) nor ①②. It sits 29 below card ② and 23 above its own list, nearly equidistant, so it doesn't clearly belong to the list | x 20.8 vs rows 16.6; 29 / 23 | `pages/coop/invite/index.wxss:35` `.card--targets .card-tit { padding: 0 var(--cy-space-1); }` | → `{ padding: 0; margin-bottom: 0; }` | x 16.6 (aligned with rows); 29 above / 13.6 below, so it groups with its list (verified) |
| A11 | marketing | Bottom clearance vs the glass tab bar: the workbench reserves 96.4 px (`index.wxss:464-467`, `120rpx + env`), marketing reserves 88.1 px, while the bar is 96.4 px tall. At full scroll the last **8.3 px** of 「进入合作中心」 sits under the bar | 88.1 vs 96.4 | `pages/merchant/marketing/index.wxss:32` `padding: 0 var(--cy-page-x) var(--cy-tabbar-h);` (`--cy-tabbar-h` = `104rpx + env`, `style/tokens.wxss:1001`) | → `padding: 0 var(--cy-page-x) calc(120rpx + env(safe-area-inset-bottom));` (same as the workbench) | banner ends at the bar top (computed) |
| A12 | customer | Three left edges within 120 px: title/summary 20.8 (`ds-ok`, Figma x=20), chips 「筛选」「管理」 **16.6**, search 12.5 (`ds-ok`, Figma x=12). The chips have no Figma note and match neither neighbour | 20.8 / 16.6 / 12.5 (40 / 32 / 24) | `pages/merchant/customer/index.wxss:98` `.cu-segs { … padding: var(--cy-space-3) var(--cy-page-x); }` | → `padding: var(--cy-space-3) 40rpx; /* ds-ok: 与标题 Figma x=20 对齐 */` | chips 20.8, aligned with the title (verified). Owner call: the two approved edges stay different by design |

Kit/token request (not in this lane): `--cy-tabbar-h` (`style/tokens.wxss:1001`, 104rpx + env) is 8.3 px shorter than the rendered tab bar
(16rpx + item + 16rpx + env). Any page that reserves exactly `--cy-tabbar-h` will tuck 8.3 px under the bar. A11 fixes marketing locally.

## B. Intentional or approved differences (leave)

| Page | Difference | Why it stays |
|---|---|---|
| decor | Page inset 20 px and card radius 20 px (others 16.6 / 16.6) | `pages/merchant/decor/design.wxss:1` `/* ds-ok: final 390pt Figma frames use 20pt outer / 16pt inner / 54pt rows */`. Internally even: 20 / 20 / 20 between cards. |
| coop-center | Page inset 12.5 px, card radius 20.8 px | `index.wxss:47` "稿 234:276 卡间距 12"; `:137` `border-radius: 40rpx; /* ds-ok: 参考图大圆角 */`. Internally even: 12.5 between cards. |
| marketing | Every tile and the banner use radius 20.8 (xl), the workbench uses 16.6 (lg) | `pages/merchant/marketing/index.wxss:5` "磁贴圆角走 --cy-radius-xl(20px),不是 lg(16px)". Internally even: section gaps 28 / 28, title→content 14 / 14. |
| customer | Title 20.8 and search 12.5 insets | `customer/index.wxss:15` and `:31` `ds-ok` Figma 25:124 (only the chips are flagged, A12) |
| workbench | Hero gaps 43.6 / 94.5 / 64.6 (avatar row → 「累计收入」 → 「¥0.00」 → shortcut circles → 「项目」) | Hero composition: `.rv-revenue-feedback` (`index.wxss:162-172`) reserves 112rpx under the amount so the page shape does not change when an error appears; the shortcut row uses the space-8 hero gap (`:178`). |
| workbench | 「我的消息」 second card inset 27.3 and 14.6 padding | The stacked-deck message card (`index.wxss:674-683`), a deliberate visual. |
| ledger redemptions | Summary card → date label 22.8, date label → card 11.3 | The group label sits closer to its own card (proximity), the same as `fin-group` everywhere. |
| ledger / verify / coop-finance / customer | Buttons and pills: 12.5 (cy-btn) and pill radius on chips/search | Control radii from the shared components, not card radii. |
| coop invite | 「查找原邀请请求（含其他设备）」 row radius 12.5 next to 16.6 cards | A utility row control (`send-receipt`), not a content card. Note: owner may still want it at 16.6; it sits 12.5 px above card ①. |
| verify-records | List card → 「已经到底了」 39.4 | End-of-list footer padding (`index.wxss:18`). |

## C. Too little seeded data to judge

| Page | What was rendered | What could not be measured | Seeding |
|---|---|---|---|
| batch-detail | 「结算批次不可见」 state (opened with `batchId=1`) | Everything | `merchant_payable_batch` and `merchant_payable` are empty. The finance chain only creates club payables (`club_payable_batch`). Merchant public-transfer batches need the backend settlement job, which is out of scope and not cheap. |
| reviews (`?mode=manage`) | 「还没有真实到店评价」 | Review cards, reply blocks | The mini program has no player-side review submission call (searched `pages`, `components`, `subpackage*`). Not seedable through the app. |
| aftercare | Title and chips (title→chips 16.4, same as A7) | Rows, group heads | Needs a refund request on a paid, unverified order. The only paid order is already verified by the chain. Not cheap. |
| customer | Title, chips, search, empty state | Customer rows | The CRM list is empty after the chain (no CRM rows are created by verification). Not cheap. |
| order-detail | Hero card with 「核销门店」 and 「核销时间」 | 「结算进度」 timeline card (needs a cash-settled redemption) | The chain redemption is 「权益已履约 · 无现金结算」. |
| ledger settlement | Hero, button, alert row | 「收入明细」 and 「对公结算」 rows (both show empty art) | Same as batch-detail. |
| marketing | Hero, tiles, counters, banner | Coupon list (empty) | Seedable via 发券 but it was not needed for any finding. |

## Cross-page comparison

| Page | Content inset L/R | Card radius | Page title → first block | Section/card gap |
|---|---|---|---|---|
| workbench | 16.6 / 16.6 | 16.6 | — (hero) | 27 / 37.4 → A1 makes it 37.4 |
| ledger redemptions | 16.6 | 16.6 | 9.1 | 16.6 tabs→summary, 22.8 summary→group |
| ledger settlement | 16.6 | 16.6 | **21.6** → A2 | **20.8 / 12.5 / 27** → A3 |
| order-detail | 16.6 | 16.6 | 9.1 | — (one card) |
| customer | **20.8 / 16.6 / 12.5** → A12 | pill | — (hero) | 12.5 chips→search |
| verify-records | 16.6 | 16.6 | **16.4** → A7 | **35.2** → A8 |
| aftercare | 16.6 | — | **16.4** → A7 | (no rows) |
| marketing | 16.6 | 20.8 (approved) | — | 28 / 28 |
| decor | 20 (approved) | 20 (approved) | — | 20 / 20 |
| coop-center | 12.5 (approved), tabs **37.4** → A6 | 20.8 (approved) | — | 12.5 |
| coop finance | **33.3** → A4 | 16.6 + 12.5 row → A5 | 9.1 | 12.5 / **0** → A5 |
| coop invite | 16.6 | 16.6 | 9.1 | 12.5 / 12.5 |
| profile (cy-profile) | 16.6 | 16.6 | — | ~31–33, **17.5** → A9 |

Conclusion: the page inset is 16.6 px (32rpx) and the card radius is 16.6 px (radius-lg) on every merchant page except the three approved
Figma pages (decor, coop-center, marketing radius), plus A4 (double padding) and A12/A6 (edges that match no neighbour). Page title → first
block is 9.1 px wherever `cy-page-title` meets a block directly. The two exceptions come from a hidden empty block (A2) and the chip
hit-band (A7).

## Assumptions and limits

- The rig compiles `100vh` to a fixed 844 px, so long pages were captured by scrolling and stitching. Measurements were taken at scrollTop
  0 in content coordinates, not from the stitched image. A fixed header is cut at its bottom edge. Sticky backgrounds (profile hero image)
  repeat in the stitched profile shot; that is a capture artifact, not page content.
- Fonts are the rig's Noto CJK, not PingFang. Ink boxes can differ by about 1 px on a device, which is below the 2 px threshold.
- `getPhoneNumber`, native map and safe-area values are rig seams. Marketing's 8.3 px uses the rig's 34 px bottom inset, the same as an
  iPhone with a home indicator.
- A5, A9 and A7's tab band touch shared components or are visible on other pages. A5 also affects the assets-earnings sheets
  (`components/cy/scene-asset-income-detail`, `subpackageA/pages/assetcenter/earnings`), which were not rendered here. A9 also affects the
  player-side profile.
- Owner rule 2 (no helper captions) and motion were out of scope for this lane; no text was reviewed.
- Reproduce: start the rig, run `chains.py finance`, then `node tools/run.mjs <name> <path> e2e-openid-9002 '<steps>' '<opts>'` (needs
  `$HOME/rig` worktree and Pillow in the rig venv). Per-page numbers are in `data/*.json`.

STATUS: COMPLETE
