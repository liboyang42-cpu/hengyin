# measure-player: rendered spacing / inset / radius measurements (player side)

Lane: measure-player. Report only, no product code changed. Branch `claude/page-polish-20261102-measure-player`.

## How this was measured (rendered pages, not code)

- Rig from `claude/cloud-walk-20261101-rig`: backend a1d30fb on real MySQL/Redis. The browser bundle was rebuilt from **this
  base branch** (`integrate/page-polish-20261102`, so the shared kit is included) with `CAND=<repo> mpweb/build.sh`.
  Phone is 390×844. **1 px = 1.923 rpx** (750/390).
- Seed: `chains.py player` (the app's real platform → merchant_club → sale_close → player flow, all PASS). This gave topic
  990014 「E2E 探店日一期」, activity 13, club 7002 and one paid registration. All pages were opened as **e2e-openid-9009**
  (「玩家二号」, player, a club 7002 member who holds that registration). The privacy gate and the 「首页城市定位」 dialog were
  dismissed the way a user would.
- In the browser, `tools/measure.js` reads `getBoundingClientRect` and `getComputedStyle` on every element (piercing shadow
  roots):
  - **visual edges**: an element's painted box, or for unpainted wrappers the union of its text and painted descendants. Gaps
    are measured edge to edge the way the eye sees them, like in the videos;
  - gaps between consecutive siblings that stack vertically;
  - left and right inset, radius and padding of every painted rounded card, grouped by class (same role);
  - title → content gap (title/head/label classes to the next sibling).
- `tools/annotate.py` stitches the scrolled screens into one PNG per page and labels them. Gap bars sit on the right; `rNN` is a
  card corner radius; insets are horizontal ticks at the card edge; `tNN` is a title → content gap. **Green** means the value
  matches the other same-role items on that page within 2 px; **red** means it disagrees. The labels are machine-placed: a red
  label is a lead, not a verdict. The tables below are the verdict. Raw numbers are in `data/<page>.json`.
- Thresholds: a difference under 2 px is noise unless the same-role items sit side by side.
- Harness assumption: WeChat's base CSS gives `scroll-view` `position: relative`, which the rig does not. Without that, the
  fixed `cy-starfield` (z-index 0) painted over the home sections in the rig. I injected `wx-scroll-view{position:relative}`
  before measuring. This changes no geometry. On a real device, check that home 「编辑精选」/「附近活动」 are visible (they should
  be).

Shots: `shots/01-home.png` … `shots/16-my-teams.png` (one per page, in the requested order).

## Group 1: real unevenness worth fixing (smallest edits)

| # | Page | Same-role items that disagree | Measured px @390 (rpx) | Cause (file:line) | Smallest edit |
|---|---|---|---|---|---|
| 1 | search2/result | Search box vs everything under it (chip tabs 「全部」「俱乐部 0」…, inline error card, 「刷新搜索结果」 button, and the result list) | search box inset **16.6** (32) vs content **29.1** (56), both sides. The column steps in by 12.5 px under the search field | `pages/search2/result/index.wxss:9`: `.search2-result .wp` inherits the global `.wp { padding: 0 var(--cy-space-3) }` (`style/lib.wxss:80`), and `.main` already pads by `--cy-page-x` (:8). Double inset | `index.wxss:9` add `padding: 0;` to `.search2-result .wp` |
| 2 | club/detail (概览) | Two section heads on the same page: 「成员 · 2」 and 「本团贡献榜 · 本周」 | head → content **20.3** vs **29.8**; previous block → head **19.6** vs **32.2** (39 vs 57, 38 vs 62 rpx) | The second head carries the sort link 「综合」. `.ov-head-link` has `min-height: var(--cy-btn-h)` (88rpx) at `pages/club/detail/index.wxss:1112`, so that row is 44 px tall and the title floats in the middle. The first head has no link for non-admins, so it is only text height. For owners/admins, 「邀请成员」 inflates the first head as well, so the rhythm also changes with viewer role | Keep the 44 px hit area but stop it from adding height: `index.wxss:1120` `margin: 0;` → `margin: -24rpx 0;`. Alternatively, give every `.ov-head` (:1102) `min-height: var(--cy-btn-h)` so all heads are equally tall (looser rhythm). Check `a11y-tap-target-lint` after the change |
| 3 | mycanyu (我的参与) | Card edge vs page title and tabs above it | card inset **8.3** (16) vs title/tabs **16.6** (32). Confirms audit PM-04 | `subpackageMember/mycanyu/mycanyu.wxss:12` `.canyubox_li` margin uses `--cy-space-2` | `:12` `margin: 0 var(--cy-page-x) var(--cy-space-4)`. The same `--cy-space-2` side margin is on `.canyu-refreshing` (:9) and `.canyubox cy-inline-error` (:10); change them together |
| 4 | my-teams | Section head 「附近在招募」 vs stats row, segment control and page title in the same column | head text at **19.8** (38) vs **16.1** (31) for stats/seg, and 16.6 for the title. Off by 3.7 px | `subpackageRoam/my-teams/index.wxss:13` `.mt-sec` padding `50rpx 38rpx 19rpx` vs `.mt-stats` :6 and `.mt-seg` :10 at 31rpx | `:13` `38rpx` → `31rpx` (horizontal only). Optional: `.mt-state` :30 also uses 38rpx, but it is centred text, so nothing visible changes. If 38rpx is a prototype 20 px value, the owner decides; I found no `ds-ok` on it |
| 5 | home (index) | Theme feed card vs section titles and the 「主题/活动」 tabs above it | card inset **18.7** (36) vs titles/tabs **16.6** (32). 2.1 px, stacked in one column | `pages/index/index.wxss:583` `.v2-feed { padding: 0 var(--cy-legacy-36) }` | `:583` → `padding: 0 var(--cy-page-x)`. This is just above the noise line, so it is low priority |

Checked, and even: on mycanyu, the card radius (32rpx) matches orders. The club detail facts grid, badge row and leaderboard all
sit at 16.6 with a 16.6 px radius.

## Group 2: owner-approved screens (listed so the owner can choose; NOT proposed as automatic changes)

| Page | What disagrees | Measured | Note |
|---|---|---|---|
| roam home (v13) `05-roam` | Side-by-side carousel cards: 「开始漫游」 intro card vs 「NO.02」 entry card | radius **12** (23rpx) vs **14** (27rpx), side by side; insets 23.9 (46rpx) vs page 16.6 elsewhere | `pages/roam/index.wxss:899` and `:904`, both `ds-ok` owner rulings. Matches audit R-05 |
| theme detail (v13) `06-topic-detail` | Gaps between detail blocks (`.tabcont`: det3 → det4 → det3 → lineup → det6) | **27.6 / 49.8 / 40.3 / 24.8** px | Block rhythm varies by about 25 px. Inside: 「场次」 card `.sess` inset 29.1 and radius 16.6, vs the review empty card `.det6-empty` inset 16.1 and radius 14 (27rpx, `topic/index.wxss:2014`, ds-ok). The cover 「查看详情与报名」 tap did not switch views in the rig before the measure, so the shot shows the cover stitched above the detail |
| checkout `08-baoming` | Section title → content: 「活动信息」 vs 「费用明细」 | **16.4** vs **30.9** | Both titles have margin-bottom space-4 (`baoming.wxss:158`, `:177`); 「费用明细」 rows (`.setly_li`) carry their own top padding. Fee rows: 30.2 / 30.2 / **34.4** (the total row is set apart; looks intentional). Shot state: 「报名已截止」 (the seeded activity has ended) |

Not reached on this run (approved anyway): buy sheet, reveal, wallet, medal.

## Group 3: other intentional differences (cross-page)

- **Page inset.** 16.6 px (32rpx, `--cy-page-x`) on home sections, talent list, member, club detail, checkout, orders and page
  titles. Outliers are rows 1, 3, 4 and 5 above, plus approved roam (23.9) and the play banner (14.5, a game screen).
- **Card radius has three tiers.** These are consistent within each page and look deliberate:
  - media cards 22.9 px (44rpx, `--cy-radius-2xl`): home feed card, talent club cards;
  - list cards 16.6 px (32rpx, `-lg`): orders, mycanyu, club leaderboard/facts/badge;
  - info/inline cards 12.5 px (24rpx, `-md`): member 「账户余额」, checkout 「你已报名这场活动」, `cy-inline-error`, my-teams
    segment.

  Owner's choice: the member asset card (24rpx) and the club leaderboard card (32rpx) play a similar "info panel" role on dark
  pages.
- **Page title → first content.** Every page uses `cy-page-title` margin-bottom `--cy-space-2` (`components/cy/page-title/index.wxss:7`).
  The visible gap still differs by the tab style underneath: orders chips **9 px**; mycanyu fill tabs **22.3 px**; my-teams
  stats **21.9 px**. That comes from the tab component's own height, not from a page rule. If the owner wants one value, it is a
  `cy-tabs` variant decision, not a per-page edit.
- **Section gap.** Within each page, sections step by `--cy-space-6` or `--cy-space-7` (home `.v3-sec` 64rpx, checkout
  `.sec-title` 80rpx, club `--cy-space-4` between blocks). That is consistent per page, but the scale differs across pages.

## Group 4: too little seeded data to judge

| Page | What rendered | Why |
|---|---|---|
| template | 「还没有可复用的主题模板」 empty state only | no published reusable templates in the fresh DB |
| activity/list | 「暂无已结束的活动」 (also 进行中 0) | the chain's activities are not official activities |
| square/detail `?id=13` | blank with 「返回上一页」 | detail is a creative-square post; posting needs a finished roam session (`/api/creativesquare/action`, `data_type 3`), which is not cheap to seed |
| play `?activityId=13` | 「队伍集合中 0/2」 gather screen only | a run needs a self-play pass or a full team (`?topicId=` showed 「请先购买自玩通行证」) |
| search2/result `?keyword=E2E` | tabs + 「内容搜索暂未开放，可稍后重试」 | content search is not available in the rig. Row 1 above is still real: the inset is page CSS, not data |
| points history | 「还没有积分记录」 | no points rows |
| my-teams | empty 「还没有队伍」 + 「附近暂时没有在招募的队伍」 | no teams |
| home 「编辑精选」/「附近活动」 | empty states | the featured flag and nearby nodes are not seeded. The visible gaps 60.5 / 74.7 px are empty-state padding, not section rhythm |

## Pages that are even

- **talent/list (俱乐部 tab):** cards 16.6 / 16.6 inset, 22.9 radius, 12.5 gap between cards. Even.
- **orders:** card inset 16.6, padding 12.5 all round, radius 16.6. Even. Only one order exists, so the gap between orders was not
  measured.
- **member (我的):** asset head and card at 16.6, head → card 19.2. Even. The cover background renders as a grey/blue texture in
  the rig (asset/OSS); not judged.

## Verification against the code audits

- PM-04 (mycanyu side margin): **confirmed** on screen (8.3 vs 16.6 px).
- R-05 (roam radii): confirmed, 12 vs 14 px side by side. Approved values, owner's call.
- P-T13 (topic teams card radius), MT-06 (my-teams error radius), PL16/PL17 (play pills/cards), A22 (baoming radio), S16 (square
  gaps): **not rendered** with this seed, so not verified.
- New and not in the audits: rows 1 (search double inset), 2 (club head height), 4 (my-teams head inset) and 5 (home feed inset).

## Requests / notes

- No kit file changes requested.
- Rig gap: `scroll-view` lacks WeChat's `position: relative`, so fixed backgrounds cover content in the rig. Worth adding to the
  rig shell CSS.

STATUS: PARTIAL. Remaining: square/detail, activity list, template, points history, my-teams and the play run screen need data
the rig does not seed cheaply. The topic detail tab view and the approved buy sheet, reveal, wallet and medal were not
re-measured.
