# Round 6P: 积分明细 before / after (rig render)

- Before: `a7c3624cf` (#719). After: `dca92673613e` (this branch's restyle commit). Nothing in product code was changed in this round.
- **Every screenshot comes from the cloud walkthrough rig, not a phone.** Real parts: the mini-program's own page/component code, WXML/WXSS (rpx at 390 wide), a real login for each player (`/api/login/code` through the rig's jscode2session seam), and the real `POST /api/points/list` on a real backend jar with MySQL 8 and Redis. The rig renders in headless Chrome at 390×844 CSS px, 2× (780×1688 JPEG, q70). It is not WeChat. Real fonts (the rig falls back to Noto Sans CJK, not PingFang), safe areas and native scrolling are **not verified on a phone**.

## How the rig was run

- Rig: `claude/cloud-walk-20261101-rig` plus the club lane's `tools/harness-patches.diff` (nested `<template>` and `wx:for` on `<template is>`). Rig-only local changes, none committed: `up.sh` takes the commit from `RIG_SHA`; `walk.mjs` takes `RIG_DPR=2`; dockerd uses `mirror.gcr.io` as a registry mirror because Docker Hub answered 429. The pinned image digests are unchanged.
- Backend: one jar built from `dca92673`. `git diff a7c3624c dca92673 -- . ':!chengyinhub-xcx'` is empty, so the backend is byte-for-byte the same source for both. The browser bundle was built twice: from an `a7c3624c` worktree (before) and from `dca92673` (after).
- Throwaway rig DB `cy_e2e_51` (fresh container). Fictional rows were inserted directly into `ums_member_points_detail` (`create_by='rig-walk-fixture'`, `event_type=99`), oldest first, with running `current_points`/`after_points`:
  - Player 9004 「玩家小城」: the 9 rows in ROUND6P (balance 1145 → 1285).
  - Player 9009 「玩家二号」: no rows (whole-list empty).
  - `walk-player-1` (990005, created by a real login): 27 rows, for 加载更多 and 已经到底了.
  - `walk-player-2` (990006, created by a real login): 3 rows, one with a 62-character reason; all of them are income, so its 使用 filter is the no-match case.
  - The real login for the two walk players also wrote its own 「注册赠送 0」 row (today, 2026-11-03 17:53), with a lower id than the seeded rows. That row is product behaviour, not something I seeded. It exposes finding F-1.
- Paged state: in both builds, scrolling to the bottom fires `onReachBottom`, which loads the next 20 rows at once. To capture the 加载更多 button, the walk replaced the page's `onReachBottom` with a no-op for that run (rig eval), then pressed the real 加载更多 button. On a phone the button shows only until the user is within 50px of the bottom.

## Shots (`<state>-before.jpg` / `<state>-after.jpg`)

| State | Player | Notes |
|---|---|---|
| `top` | 9004 | all rows, top of page |
| `bottom` | 9004 | scrolled to the end |
| `filter-income` | 9004 | 获得 |
| `filter-expense` | 9004 | 使用 |
| `empty` | 9009 | whole list empty; same in both builds |
| `filter-nomatch` | 990006 | 使用 with no expense rows; same in both builds |
| `paged-more` | 990005 | first 20 rows, 加载更多 visible (auto-load suppressed, see above) |
| `paged-end` | 990005 | after 加载更多, scrolled to the end: 已经到底了 |
| `long-reason` | 990006 | 62-character reason wrapping |

Comparison shots of sibling pages for the same player, unchanged by this commit: `compare-mycanyu.jpg` (我的参与), `compare-coupon-wallet.jpg` (优惠券), `compare-invoice.jpg` (发票). All three are empty for a fresh fixture player, and 发票 shows 「发票功能暂未开放」 (feature switch off in the fixture DB). So the comparison covers page title, filter tabs and empty state only, plus their WXSS. Their row layouts were not rendered with data.

## What I saw (report only, nothing fixed)

Checked on every shot: clipped or overlapping text, misaligned numbers, dark-theme contrast, card edge against the page edge, last-row divider, and match with the sibling pages.

| # | Build | Finding | Shots |
|---|---|---|---|
| F-1 | both | **Month groups repeat when ids are not in time order.** The API sorts by `id desc` and `groupByMonth` groups adjacent rows, so a row with a lower id but a later time gets its own second 「2026年11月」 group at the bottom, below 9月. Here it comes from the real 「注册赠送」 row written at login, which is older by id than back-dated seed rows. In production this needs rows whose `create_time` order differs from their id order (back-dated or compensating rows). That is plausible but not proven. | `long-reason-*`, `paged-end-*` |
| F-2 | both | 「注册赠送」 with change 0 shows a bare white `0` and 余额 0. It is neither a gain nor a use, and it appears under 全部 only. | `long-reason-*`, `paged-end-*` |
| F-3 | both | Gains and uses are the same colour. `--cy-color-brand` renders as rgb(248,248,248) on the dark theme, the same as `text-primary`, so only the `+`/`-` sign tells them apart. Contrast is fine; the distinction is weak. | `top-*`, `filter-*` |
| F-4 | after | The minus sign is set apart from the digits (`- 50`, `- 300`, `- 10`) in the after shots but not in the before shots. Computed styles in the after build are the same as before for the delta (14.56px, tabular-nums, normal letter-spacing). The cause is not established; it may be the rig's fallback font in a right-aligned column. **Check on a phone.** | `top-after`, `filter-expense-after`, `paged-*-after` |
| F-5 | after | With 获得/使用 filters, the 余额 under each row is the real running balance, so it jumps between visible rows (获得: 1285 → 1265 → 1165). It is correct, but more visible now that 余额 sits under the number. The before build showed the same numbers in the sub-line. | `filter-income-*` |
| F-6 | after | 加载更多 and 已经到底了 have identical styling (secondary-colour centred text, no button shape), so the button does not read as tappable. The same is true in before. It is mostly hidden anyway because reaching the bottom auto-loads. | `paged-more-after`, `paged-end-after` |
| F-7 | before | The last row of each month group keeps its bottom divider in the rig render (before: a line under 「每日签到 11-01」, 「发布帖文…」, 「活动奖励」 and above 已经到底了), even though `.ph-row:last-child` removes it. **Fixed in after**: cards have no divider on the last row. | `top-before`, `bottom-before` |
| F-8 | before | The short 9-row list showed 已经到底了 although there was no paging. **Fixed in after** (`paged` flag): `bottom-after` ends with the card and no footer. | `bottom-*`, `filter-expense-*` |
| F-9 | after | Long reason: it wraps over 4 lines within the left column, with no clipping or overlap. The right column (`+350`/余额 370) stays top-aligned. The wrap column is about 60% of the card width because the side column is reserved. Before wrapped over 3 lines and centred the delta vertically. | `long-reason-*` |
| F-10 | after | Cards have the page gutter on both sides (card edges about 17px from the screen edges at 390), so no card touches the page edge. Numbers are right-aligned in a column with tabular-nums: `+20`, `+150` and `+1145` line up on the right. No clipped or overlapping text in any shot. Scrolled content passes under the nav bar, and the nav area stays opaque black. | all after |
| F-11 | both | **Mismatch with sibling pages: filter tabs.** 积分明细 uses `cy-tabs variant="chip"` (left-aligned pills). 我的参与 uses the underline tab style and 优惠券 uses full-width segmented pills. 发票 also uses `chip`, per its WXML; it was not visible because the feature is off. | `compare-*`, `top-after` |
| F-12 | both | **Mismatch with sibling pages: filter-empty wording.** 积分明细 says 「还没有使用积分的记录」. 我的参与 and 发票 use 「当前筛选暂无…」 for a filter with no matches (from their WXML; not rendered here). | `filter-nomatch-*` |
| F-13 | after | Card style: 我的参与 cards use `bg-surface` with no border (`canyubox_li`). 发票 cards use `--cy-bg-card` with a 2rpx border and a card shadow (`orderbox_li`). The new 积分 card uses `bg-surface` with a 1rpx `border-subtle` border. So it is closest to 我的参与 but adds a hairline border. This comes from the WXSS; the sibling rows were not rendered with data. | — |
| F-14 | both | The empty state (`empty-*`) and filter-no-match state are byte-identical between builds. The illustration and title are centred in the remaining space below the title, matching 我的参与. 优惠券's empty illustration sits higher on the page. | `empty-*`, `compare-*` |

## Gates on `claude/page-polish-points-20261103` (at `dca92673`)

- `node chengyinhub-xcx/tests/lint/ui-integrity.js` → `UI-GATE-0:PASS` (A11Y PASS, Legacy PASS error 0 warn 0).
- `node chengyinhub-xcx/scripts/uiaudit/build-action-ledger.js --check` → `ACTION-LEDGER PASS: 5598 controls, 0 evidence results`.
- Full mini unit tests, `node --test "tests/unit/**/*.test.js"` in `chengyinhub-xcx` → `tests 15561, pass 15561, fail 0` (398 s).
- `ci/xcx-check.sh` and `ds-hardcode-gate-selftest.sh` were not run, as instructed.

## Not verified

On a real phone: fonts (PingFang), the minus-sign gap (F-4), safe-area bottom padding, the native pull-down refresh, and the real `onReachBottom` threshold. 发票 rows (the feature is off in the fixture DB). 我的参与 and 优惠券 rows with data.

STATUS: COMPLETE
