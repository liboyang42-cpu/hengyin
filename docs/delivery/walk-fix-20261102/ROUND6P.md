# Round 6P: render 积分明细 before and after

Commit `dca92673613e` on `claude/page-polish-points-20261103` restyles `subpackageMember/points-history/` on top of `a7c3624cf` (#719). Nobody has seen it rendered. The owner said "The UI for the points breakdown needs optimization", and the page must be judged from real screenshots.

Never push to master, merge a PR or touch production. Don't run `ci/xcx-check.sh` or `ds-hardcode-gate-selftest.sh`. No secrets or private data in the repo.

## Do

1. Stand up the walkthrough rig from `claude/cloud-walk-20261101-rig` with the club lane's `harness-patches.diff`, once with `a7c3624cf` (before) and once with `dca92673613e` (after). Use a real player login and the real `/api/points/list`.
2. On the throwaway rig DB, give the player these 9 clearly fictional points rows: 11-02 每日签到 +20, 11-01 完成主题《海上方舟》 +150, 11-01 解锁提示 -50, 11-01 每日签到 +20, 10-28 兑换优惠券 -300, 10-21 邀请好友加入俱乐部 +200, 10-12 发布帖文获得点赞奖励和俱乐部活跃度加成 +80, 09-30 每日签到 +20, 09-18 活动奖励 +1145, with matching running balances.
3. Take 390x844, 2x shots, before and after, of: all rows at the top of the page; scrolled to the bottom; the 获得 filter; the 使用 filter; the whole-list empty state (a player with no rows); a filter with no matches; a player with over 20 rows, to show 加载更多 and 已经到底了; and one row with a very long reason, to show the wrapping.
4. Look at every shot yourself. Report clipped or overlapping text, misaligned numbers, unreadable contrast on the dark theme, a card edge touching the page edge, a wrong last-row divider, and any mismatch with the other player list pages (我的参与, 优惠券, 发票). Don't fix anything in this round. Only report it.
5. Run on that branch: `node chengyinhub-xcx/tests/lint/ui-integrity.js`, `build-action-ledger.js --check`, and the full mini unit tests.

## Output

Commit the shots as `docs/delivery/walk-fix-20261102/points/<state>-before.jpg` and `<state>-after.jpg` (JPEG under 200 KB each), and `docs/delivery/walk-fix-20261102/points/RESULT.md`, on branch `claude/page-polish-points-20261103`. Push when each state is done. Say plainly what comes from the rig and not a phone. End with `STATUS: COMPLETE` or `STATUS: PARTIAL - <reason>`.
