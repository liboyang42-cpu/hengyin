# impl-player-me: result (2026-11-02)

Branch `claude/page-polish-20261102-impl-player-me`, cut from `integrate/page-polish-20261102` (940846c3).
Audit lane read: `player-me` (`origin/claude/mini-polish-audit-20261102-player-me`, REPORT.md).
Scope: P0/P1 rows, spacing rows PM-04 and PM-16, and patterns X1, X2, X4 and X5. PM-05 uses the kit. B1 is an open decision and was skipped.

I checked every finding against the current source before editing it. None of the rows I implemented was a false positive. The
report contains no P0 rows.

## Findings

| ID | Outcome | Files changed | Evidence |
|---|---|---|---|
| PM-01a (mycanyu) / X1 | done | `subpackageMember/mycanyu/mycanyu.wxml`, `.wxss` | "正在更新参与记录…" now carries `.cy-refresh-float` and has a pill surface. `.canyubox` gets `position: relative`. Shots: `pm01a-pm04-mycanyu-refresh-{before,after}.png`. Before, the row pushed the first card down by about 44px. After, the pill floats over the first card's top edge and the card stays where it was. |
| PM-01b (mylike, 2 sites) / X1 | done | `pages/mylike/mylike.wxml`, `.wxss` | The empty `.favorite-refreshing` status node now has `.cy-refresh-float` and no `min-height`, so it takes no height (88rpx before). I kept the node and its class for the a11y status and the A48 shot selector. No shot: the node shows nothing, so the only visible change is the missing gap, and the rig has no favourites seeded. |
| PM-01 (address) / X1 | done | `pages/address/address.wxml`, `.wxss` | "正在更新参与人信息…" floats, and `.dz` gets `position: relative`. Shots: `pm01-address-refresh-*`. The fixture member has no participants, so the pill sits above the empty state. |
| PM-01 (im/list) / X1 | done | `subpackageB/pages/im/list/index.wxml`, `.wxss` | "正在刷新消息" floats, and `.im-list` gets `position: relative`. Shots: `pm01-im-list-refresh-*`. Before, the row pushed "还没有消息" down. After, it no longer moves. |
| PM-01 (myproject, 2 sites) / X1 | done (code only) | `subpackageA/pages/myproject/index.wxml`, `.wxss` | "正在同步最新项目…" and "正在同步最新节点玩法…" float, and `.mp-page` gets `position: relative`. The light theme (B1) is untouched. No shot: the page needs a creator identity with projects, which the rig does not seed. `page-band-x-inset-contract` still passes. |
| PM-01 (gerenziliao) / X1 | done | `pages/gerenziliao/gerenziliao.wxml`, `.wxss` | "正在确认个人资料…" floats inside `.profile-refresh-state`, which is now `position: relative`. That wrapper's existing margin is always present, so the hero no longer moves by 72rpx. Shots: `pm01-gerenziliao-refresh-*`. |
| PM-02a / X2 | done | `subpackageMember/points-history/index.wxml` | Skeleton `type="feed-card"` changed to `type="list"`. Shots: `pm02a-points-history-skeleton-*`. |
| PM-02b / X2 | done | `subpackageMember/appeal-mine/index.wxml` | Same change. Shots: `pm02b-appeal-mine-skeleton-*`. |
| PM-02c / X2 | done | `subpackageMember/report-mine/index.wxml` | Same change. Shots: `pm02c-report-mine-skeleton-*`. |
| PM-04 | done | `subpackageMember/mycanyu/mycanyu.wxss` | The `.canyubox_li` side margin changed from `--cy-space-2` to `--cy-page-x`. Shot `pm01a-pm04-mycanyu-refresh-after.png` shows the card edges lined up with "我的参与". |
| PM-05 | done | `components/cy/scene-asset-earnings/index.js`, `.wxml`; tests `scene-asset-earnings-contract`, `p1-feedback-wiring-contract` | Replaced the component's own 220ms `easeOutCubic` countUp with the kit's `pageMotion.countMoney(this, 'balanceK')` (1300ms, ease-in-out). The template now shows `cnt.money(money.amount(balance), balanceK)`. `balance` keeps the true value, so the empty state and withdraw still read it. `_clearBalance` (called on hide, detach, identity change and reload) stops the timer and resets `balanceK`. Reduced motion and a zero balance skip the animation. Frames from the rig with balance 1288.50: before, the value was already final at 600ms ("¥1288.50"). After, 600ms shows "¥238.37", 900ms is mid-roll, and 1700ms shows "¥1288.50" (`pm05-earnings-{600,900,1700}ms-*`). I rewrote the two contract tests that pinned the old `countUp`/`balanceDisplay` wiring as kit-based tests: stop-on-identity-change, stop-on-refresh, and reduced-motion/zero. |
| PM-07 / X5 | done (code only) | `subpackageB/pages/im/chat/index.wxml`; test `im-chat-route-card-null-mileage` | Both the route card and the picker sub-line use `num.n()`, so missing data reads "— km · — 个点" and a real 0 stays 0. The existing test pinned the older 0918 ruling ("数字没有就显示 0"). I updated it to brief rule 6 and say so in the test comment. No shot: the rig has no seeded conversation with a route card. |
| PM-08 / X5 | done | `subpackageMember/components/cy/profile/index.wxml` | Under "发布内容 / 获赞 / 粉丝", values without data now show "—". "发布内容" shows "—" only when both counts are missing. Shots: `pm08-userinfo-achievements-nodata-*` (成就 tab of another user's profile, with `userInfo` reset to `{}` as it is before load). Before: "0 / 0 / 0". After: "— / — / —". |
| PM-14 / X5 | done | `subpackageTools/anniversary/index.wxml` | `num.n(item.yearsAgo)`. Shots: `pm14-anniversary-missing-*` use dummy rows, one with `yearsAgo: null` and one with `0`. After: "— 年前" and "0 年前". |
| B2 / X5 | done | `subpackageTools/capsule/index.wxml` | `daysLeft` in the text and in the aria-label now goes through `num.n`. Shots: `b2-capsule-missing-*`. |
| PM-11 / X4 (order-history, points-history, mycanyu) | done | `scene-member-order-history/index.wxml`, `points-history/index.wxml`, `mycanyu/mycanyu.wxml`; test `member-order-layout-contract` | Dropped the "换个筛选试试" sub. The order test now asserts that the sub is absent. Shots: `pm11b-points-history-filterempty-*`, `x4-mycanyu-filterempty-*`. |
| PM-11 extra (report-mine) / X4 | done | `subpackageMember/report-mine/index.wxml` | This is a fourth "换个筛选试试" site in scope that the audit's X4 list missed. Same one-attribute removal. |
| PM-16 | done | `subpackageMember/appeal-mine/index.wxss`, `report-mine/index.wxss` | `.rp-row + .rp-row { margin-top: calc(-1 * var(--cy-space-3)); }` cancels the container gap between adjacent rows, so rows sit at the 48rpx `.ph-row` rhythm. The gap above the first row and around the tabs is unchanged. Shots: `pm16-{appeal,report}-mine-rows-*`. |
| PM-03, PM-10, PM-12, PM-13, PM-15 | skipped | none | These are P2 rows on lines I did not otherwise change, and they are not spacing or radius rows. |
| X3, X6 | skipped | none | These are P2 patterns outside the assigned list. |
| B1 (myproject theme) | skipped | none | Open decision per the brief. |

## Assumptions and notes

- **Pill surface on the floating status.** `.cy-refresh-float` only positions the element. Floating text over a card is
  unreadable without a background, so each local class got `padding: var(--cy-space-1) var(--cy-space-3); border-radius:
  var(--cy-radius-pill); background: var(--cy-color-bg-elevated); white-space: nowrap;`. `nowrap` is needed because an
  absolute box at `left: 50%` otherwise wraps at half the width. **Kit request:** move this surface into `.cy-refresh-float`
  (or add a `.cy-refresh-float--pill`) so the 5 local copies can collapse into one.
- **Lint change (not a kit file).** `scripts/ui-theme-unit-lint.js --units` failed every `{{num.n(x)}} 天/年/个` because it
  accepted only `||` as a fallback. I added one condition: `num.n(` also counts as a fallback. Other lanes using `num.n` before
  a unit will hit the same rule, so integration may see the same one-line edit from several branches.
- PM-05 duration is the kit's 1300ms, not the 600ms the audit suggested. The brief says to use the kit's money landing.
- I did not touch the profile header stats ("好友 / 关注 / 粉丝"), which also show 0 before load (visible in the pm08 shots).
  They are not in the audit report. They look like the same X5 case and could be a follow-up row.
- No new JS lines were inserted mid-file. The `<wxs>` imports for `num.wxs` sit on the existing first line of each WXML.

## Checks (run from `chengyinhub-xcx/`)

- Full `node --test "tests/unit/**/*.test.js"`: 15528 tests ran with 2 failures. I then fixed one of them
  (`member-order-layout-contract`, pinned "换个筛选试试"); it now passes 11/11. The remaining one is
  `action-ledger.test.js` "release 前重建 inventory…". It is the expected inventory/digest drift from the WXML/WXSS edits.
  Per the brief, I did not commit a regenerated ledger, and integration should regenerate it. Control count: no controls were
  added or removed, so the drift is in the digests only.
- `scripts/ds-hardcode-gate.sh --ci`: passes. `node scripts/ui-theme-unit-lint.js --units`: "WXML 孤立单位门禁:通过".
  `node scripts/setdata-dead-field-lint.js`: passes (276 in the baseline, 0 new). `node scripts/a11y-tap-target-lint.js`: 0 new.
- I did not run `ci/xcx-check.sh`, per the brief.

## Rig

The rig is `claude/cloud-walk-20261101-rig`, started with `up.sh` (backend = master a1d30fb, unchanged by this lane). The bundle was
rebuilt twice with `CAND=<worktree> mpweb/build.sh`: before = `integrate/page-polish-20261102`, after = this branch. All shots
are at 390x844, logged in as fixture `e2e-openid-9004`, with zero page errors. The rig DB is sparse, so most states were set with
the page's own `setData` and dummy rows (the same approach as shot-matrix's `setData` mode). Earnings used a cached
`userInfo.balance = '1288.50'`. Not exercised: myproject (no creator data), im/chat route card (no conversation), mylike
(no favourites; the change is invisible by design), and real pull-to-refresh gestures (the `refreshing` flag was set directly).
The shots were converted from JPEG to 96-colour PNG to fit the size limit: 34 files, each under 300 KB.

STATUS: COMPLETE
