# r2-player-me: result (2026-11-02)

Branch `claude/page-polish-r2-player-me`, cut from `integrate/page-polish-r2-20261102` (2f7bdab0).
Audit lane `player-me` (REPORT.md on `claude/mini-polish-audit-20261102-player-me`); round 1 lane `impl-player-me`.
Overlap checked first: `claude/walk-fix-1102-l2-explore` touches `profile/index.js` (rank sentinel, line 1470),
`profile/index.wxss` (album card), `order-preview.js`, `signup/*` and the reserved order-detail. None of those lines or
items is edited here. The order list component (`scene-member-order-history`) and the profile header stats are not in its diff.

## How this was seen

Rig `claude/cloud-walk-20261101-rig`, `up.sh` with `SHA` set to the base. Docker Hub returned 429 for the pinned MySQL image,
so the images were pulled from `mirror.gcr.io` (same sha256 digests) and the throwaway candidate worktree's
`ci/real-storage-it-registry.json` was pointed at `mirror.gcr.io/library/...` locally (never committed). `chains.py finance`
(platform → merchant_club → sale_close → player → finance, all PASS). All shots are 390×844 as fixture `e2e-openid-9009`
(「玩家二号」, holds one paid registration, order `A2026110229C07733`, one system-notice conversation).
"Before" = bundle built from the base candidate; "after" = `CAND=<this worktree> mpweb/build.sh`. Where the rig had too
little data, rows were set with the page's own `setData` (dummy data; e.g. three copies of the real order/participation row,
five points rows, three favourite topics). Measurements are `getBoundingClientRect` in CSS px (1 px = 1.923 rpx).
Zero page errors in every run.

Shots (`shots/`, left = before, right = after unless noted):
`01-mycanyu-voucher-clip`, `02-mycanyu-entrance` (before 90 ms | after 90 ms | after 800 ms), `03-mycanyu-rows`,
`04-order-rows`, `05-points-history-rows`, `06-mycanyu-empty`, `07-order-empty`, `08-points-history-empty`,
`09-profile-stats-preload`, `10-anniversary-loading`, `11-anniversary-rows`, `12-mylike-card-radius`,
`13-measured-im-list-chat` (after only; nothing changed there), `14-order-rows-scrolled`.

## Regressions

None seen. Every changed page was rendered after the change with zero page errors. Notes on deliberate side effects:
- 我的参与 cards are now 228.5 px tall instead of 174.7 px when the voucher button is present, because the card now grows to
  hold its text block. The cover image fills the taller card (`aspectFill`). Cards without enough text keep the 336rpx minimum.
- The 我的订单 list (also used inside the scene deep-link sheet) moves its first card down 4.2 px (tabs gap 24 → 32rpx).

## Row accounting

| Row | Outcome | Evidence |
|---|---|---|
| PM-01 (X1, 6 pages) | already fixed (round 1) | round 1 RESULT; `.cy-refresh-float` present on all 8 sites |
| PM-02a/b/c (X2) | already fixed (round 1) | skeletons are `type="list"` |
| PM-03 mycanyu entrance | **done** | `mycanyu.js:4` requires `page-motion`; `:180` calls `pageMotion.enter(that,'pmList')` on first load only (no snapshot); `.canyubox` carries the `--pm-*` style and rows `--pmi`; the one transition rule is copied from order-history (`min(--pmi,8)*30ms`). Shot 02: at 90 ms the card is faded and offset, at 800 ms settled; before, it appeared at once. Reduced motion skips it (inside `enter`). |
| PM-04 mycanyu inset | already fixed (round 1) | measured 16.6 px, same as title/order/points |
| PM-05 earnings count | already fixed (round 1) | `countMoney` |
| PM-07, PM-08, PM-14, B2 (X5) | already fixed (round 1) | `num.n` in place |
| PM-10 order empty illustration | **done** | `scene-member-order-history/index.wxml:22` `icon=d_yhqon.png` → `kind="empty"`; title and CTA kept. Shot 07. |
| PM-11 (X4) | already fixed (round 1) | no 「换个筛选试试」 left in scope |
| PM-12 shezhi 我的收藏 caption | **needs owner** | The two-way caption 「收藏的帖文与主题」/「收藏的主题」 was added on purpose by review fix PR10 F-L5C-06 and is pinned by `tests/unit/pr10-review-ui-fixes.test.js:339` (it tracks the community switch). Rule 3 says drop helper captions; the earlier fix says keep it. Not changed. Removing it also makes `communityPostRead` and `syncCommunityEntry` dead in `shezhi.js`. |
| PM-13 / X6 uncapped stagger | **done** | anniversary, monthly-recap, exploration-goals, capsule (2 sites): `{{index < 6 ? index * 60 : 360}}ms`, the profile's cap. Code only (timing; not shot). |
| PM-15 anniversary first load | **done** | `anniversary/index.wxml:13` text line 「正在翻往年记录…」 → `<cy-skeleton type="feed-card" count="2">` (cover + copy, the shape of `.an-card`); `cy-skeleton` registered in `index.json`. Shots 10, 11. |
| PM-15 monthly-recap first load | **too big** | The page is a hero number plus rows; no existing `cy-skeleton` type has that shape, and a new variant is a shared-component change (r2-shared) and a new variant (rule 1). Left as the text line. |
| PM-16 rp rows | already fixed (round 1) | |
| X3 hand-written stagger ladders | **shared request** | needs a rule in `style/fade-in.wxss` (r2-shared). Not done here; mycanyu copies the existing order-history rule instead. |
| B1 myproject theme | already fixed | commit 50bb3cdc on base: `operationScope ? 'theme-merchant' : 'theme-dark'` (rule 4). |
| §4 free-explore / orient-score button walls; im/chat radii; mycanyu vs order card surfaces | **too big** | redesign / de-slop pass, as the audit says. |
| Extra: mycanyu clips 「查看报名凭证」 | **done** | Cause: the text block was `position:absolute` and the card height came only from the 336rpx cover, with `overflow:hidden`. Now `.canyubox_li_tgb` is a flex row with `min-height:336rpx`, the cover is absolute behind it (`top/bottom:0`, image `height:100%`), and the text block is in flow with the same 36rpx right offset and a space-4 top and bottom margin. Measured: before card bottom 398.1 px, button bottom 422.8 px (clipped by 24.7 px); after card bottom 451.9 px, text block bottom 435.3 px (16.6 px inside). Shot 01. |
| Extra: profile 「好友 / 关注 / 粉丝」 0 before load | **done** | `profile/index.wxml:82-86` use `num.n()`; initial data and `_clearSelfReadState` set the three to `null` (same lines, `index.js:468-470, 617`). After the API answers, a missing field still lands on 0 (UI-04 ruling, test `profile-first-run-state-contract:534`); see Owner's choice. Shot 09: before 「0 / 0 / 0」, after 「— / — / —」. |
| Extra: sibling lists as one family | **done** | see the table below. |
| Extra: unmeasured pages | **done** | see "Measured pages". |

### Sibling lists: 我的参与 / 我的订单 / 积分明细 (px @390)

| Property | 我的参与 before → after | 我的订单 before → after | 积分明细 before → after |
|---|---|---|---|
| Entrance | none → page-motion row stagger | row stagger (unchanged) | group stagger (unchanged; same timing curve) |
| Content inset | 16.6 | 16.6 | 16.6 |
| Title → filter top | 0 (fill tabs) | 0 (chips) | **12.5 → 0** (chips now at 161, same as orders) |
| Filter → first row | 16.6 | **12.4 → 16.6** | 16.7 → 16.6 |
| Card gap | **16.7 → 25.0** | 25.0 (user-requested space-5, pinned by `r1-c04-order-ticket-contract`) | hairline rows, 71.3 rhythm (unchanged) |
| Empty state | `d_yhqon.png` icon, fill, lg → **`kind="empty"`**, fill, lg | `d_yhqon.png` (none) / `kind="empty"` (filter) → **`kind="empty"` both** | `kind="empty"`, not fill, default size → **fill, lg** (centered in the space under the title) |

Files: `mycanyu.wxss` (card margin space-4 → space-5, comment), `scene-member-order-history/index.wxss:11`
(`.order-tabs-wrap` padding-bottom space-3 → space-4), `points-history/index.wxml` (`ph__frame` class on the existing wrapper,
`fill class="cy-fill-state" size="lg"` on both empties) and `index.wxss` (`.ph`/`.ph__frame`/`.ph__body` flex column so `fill` works;
`.ph__body` top padding space-3 → 0). The filter style (fill tabs on 我的参与 vs chips on the other two) is a component variant
and was not changed (rule 2); see Owner's choice.

### Measured pages (not measured before)

| Page | State reached | Measured | Verdict |
|---|---|---|---|
| 积分明细 with rows | 5 rows in 2 month groups (setData) | inset 16.6; rows 71.3 each; group title → row 0; group gap 16.6 | even after the title gap fix above |
| 我的订单 with several orders | 3 orders (copies of the real one) | inset 16.6; card 277.6; gap 25.0 | even |
| 消息 (im/list) | real system-notice conversation | row full-bleed, tabs at inset 16.6 | even; nothing to fix |
| 聊天 (im/chat) | real system notice with route card 「4.38 km · 4 个点」 | one card bubble; nothing uneven within the one screen | radii pass is too big (audit §4) |
| 我的收藏 (主题) | 3 favourites (setData) | inset 16.6; card gap 12.4; card 200.7 tall; radius **16.6 (lg)** | **fixed**: these are cover media cards (comment says 「与首页 feed 长方卡同源」); the media-card tier is `--cy-radius-2xl` (home feed measures 22.9). `mylike.wxss:31` lg → 2xl. Shot 12. |
| 我的收藏 (帖文), other collection pages (stamp-album, object-cards, badge-wall) | not reached | — | see Not exercised |

## Owner's choice

- **Profile stats after load.** Before load now shows 「—」. When `/api/user/info` answers but omits `friendNum`/`followNum`/`fansNum`,
  the page still shows 0, per the 2026-09-18 UI-04 walk ruling (「数字统计没取到就显示 0」, pinned in
  `profile-first-run-state-contract.test.js:534`). Brief rule 6 would show 「—」 there too. Owner to pick.
- **PM-12** shezhi 我的收藏 caption: rule 3 vs review fix F-L5C-06 (above).
- **Filter style in the sibling family:** 我的参与 uses `cy-tabs variant="fill"` (underline, full width), 我的订单 and 积分明细 use
  `variant="chip"`. Same role, different variant. Not changed (rule 2).
- **Card surfaces:** 我的参与 cards `bg-surface`, no border; 我的订单 cards `bg-card` + border + shadow. Unifying is a restyle.
- **那年今日 `.an-card`** margin `0 31rpx 23rpx` (16.1 px inset, prototype values with `ds-ok` on the cover) vs page-x 16.6 px: under 2 px, left.

## Shared requests (r2-shared)

- X3: one `.cy-pm-row` rule in `style/fade-in.wxss` (driven by `--pmi`/`--pm-l`/`--pm-l-d`/`--pm-l-s`) would replace the copies in
  order-history, mycanyu (new copy this round) and the `nth-child` ladders in points-history, growthcenter and stamp-album.
- Round 1's request stands: a pill surface on `.cy-refresh-float`.
- A `cy-skeleton` shape for "hero number + rows" if monthly-recap should get a loading skeleton.

## Ledger and baseline drift

- `tests/unit/action-ledger.test.js` "release 前重建 inventory，漏掉新增/修改控件必须变红": expected digest drift from the WXML edits
  (mycanyu row style attribute, order empty, points empties, anniversary). No controls added or removed. Not regenerated (reserved).
- No JS line moved: `mycanyu.js` additions sit on existing lines 4 and 180; `profile/index.js` edits replace lines 464-470 and 617 in place.
- `tests/lint/ui-integrity-baseline.js`: no change needed (`ui-integrity --warn-as-error` passes).

## Checks (from `chengyinhub-xcx/`)

Full `node --test "tests/unit/**/*.test.js"`: 15553 tests, 15552 pass, 1 fail. The one failure is `action-ledger.test.js` "release 前重建 inventory，漏掉新增/修改控件必须变红" (expected digest drift, listed above).
`ds-hardcode-gate.sh --ci` pass; `ui-integrity.js --warn-as-error` pass; `state-visibility-gate.js` pass; `ui-validity-gates.js` pass
(U5, U6); `ui-theme-unit-lint.js --theme` and `--units` pass; `setdata-dead-field-lint.js` pass (no new); `a11y-tap-target-lint.js`
0 new. `ci/xcx-check.sh` not run.

## Not exercised

- Real pull-to-refresh and real slow network (states were set with `setData`).
- Capsule / exploration-goals / monthly-recap stagger caps: timing only, not shot.
- 我的收藏 帖文 tab, stamp-album, object-cards and badge-wall with content: no favourites/collectibles seeded; badge-wall (enamel
  badges) is approved work and was not touched.
- im/chat with a long thread, pickers and a peer chat (only the system notice exists in the rig).
- Real device fonts and safe areas (rig limitation).

STATUS: COMPLETE
