# j6-break-it: second pass

Commit tested: `573c463b3b457b77544e63be4271a07d5d565e09`. Branch `claude/check-1105-j6-break-it`, cut from that commit. Run 2026-11-05.

**This is not a phone.** Everything ran in the cloud walk rig:
- the real jar, with real MySQL 8 and Redis 7;
- the real mini-program code in headless Chrome at 390×844, with 2× screenshots.

WeChat login, payment, the map, the camera and the WeChat payment query are rig stand-ins.

## Setup

- **Rig.** `claude/cloud-walk-20261101-rig`, plus j3's `tools/rig-changes.diff`. That diff carries the club lane's harness patches, the touch fix and the OSS passthrough.
  - `up.sh` is pinned to the commit above.
  - Dummy `CHENGYIN_OSS_*` values are set, so cover URLs sign. Covers are still empty because the host is unreachable (HARNESS).
  - `LANG=C.UTF-8`, Docker mirror `mirror.gcr.io`.
  - Diff: `tools/rig-changes.diff`.
- **Data.** `chains.py player` passed all four chains: topic 990014, club 7002 (lead 9008), sessions 9–13. Players `walk-player-1..4` (990005–990008) came from real logins, and guests 990009–990013 from the real login prompt.
- **Fixture writes**, test database only, listed in `proof/fixture-writes.txt`:
  - `cms_topic 990014.team_mode = 1`, so teams can be built;
  - `ums_member.role = 'club'` for 9003 and 9008, the same fixture gap ROUND10-F3 fixed (first-pass F8);
  - one `club_member` row removed, to replay the join.
- **Setup over the API.** Club joins, one consent and orders for walk-player-3/4 went through the real API (`tools/lib.py`, `buy()`). Payment success always goes through `pay.py`, which uses the real callback.
- **Tools.** `tools/`: `run.mjs`, `drv.mjs`, `guest3.mjs`, `g3one.mjs`, `gwrite.mjs`, `lib.py`, `expire.sh`, and the step files `s*.json`.
- **Read-backs.** `proof/`. Tokens, invite codes and JWTs are redacted.

## 0. First-pass findings: are they really gone?

| First pass | Now | Evidence |
|---|---|---|
| **F1** guest gets a silent account | **Gone.** All 157 `app.json` pages opened as a fresh guest, one browser per page. No page called `wx.login` or any `/api/login*`, and no page ended logged in. The sweep flagged 6 pages with a new member, but my guest-write walk was creating accounts at the same moments. Rerun alone, all 6 created 0. Accounts are now created only after the user taps 微信登录 or 登录 (§1 G-rows). | `proof/guest-sweep.json`, `shots/guest/*`, rerun `shots/guest/*-rerun-*` |
| **F2** closed pay sheet leaves an order you can't cancel | **Partly.** Cancel no longer fails silently: 取消订单 on the paying order shows 「支付结果确认中，请稍后再试」 (13). In the rig it always refuses, because the payment stub answers the WeChat query with nothing (HARNESS), so the NOTPAY → close path is NOT VERIFIED. Still open: after the sheet closes, the same player can buy the same session again, and 去支付 on the stuck order still gets pay parameters. See **N1**. | 06–16, `proof/proof-*.txt` |
| **F3** 票夹 black on error | **Fixed.** Offline shows 「票夹没能打开 / 网络异常，请稍后重试 / 重试」. Back online, 重试 loads the tickets. My first tap missed the button; a tap on the button itself works. | 26, 27, 27b |
| F3 guest blank pages (about 30) | **Mostly fixed.** Pages now show 「登录后查看 · 登录」 or 链接已失效 with 返回. One is still blank: 我的参与 (**N3**). `stamp-camera` without params shows only 「双指缩放裁切 57%」 (LOW). `citystamp` is a camera viewfinder, which the rig can't feed (HARNESS). | guest 125, 024, 143 |
| **F4** activity stays 「去加入俱乐部」 after joining | **NOT VERIFIED.** The fix re-reads the activity in the component's `pageLifetimes.show`. On back, the rig fires only the page's `onShow`, not component `pageLifetimes.show` (`rig/mpweb/src/nav.js:218`), so the rig can't show the fix working. In the rig the page still gates, as player and as guest (23, 25). Needs a phone. | 21–25 |
| **F5** first 付款 with the saved participant fails | **Fixed.** The saved participant 测试乙 was auto-selected, and the first 付款 created order 78 and paid it. | 07, 15, 16 |
| **F6** deadline shows 00:00 | **Fixed.** 「请在 2026-11-05 10:51 前完成支付」, DB `pay_expire_time 10:51:55`. | 10 |
| **F7** guest club shows empty members under a count | **Fixed** as the owner decided: one 「登录后查看 · 登录」 row. A new problem appears after login, see **N2**. | g03, g04 |
| **F8** no-permission screens | **Partly.** `club/edit` now shows a single state, but keeps the helper line 「只有该俱乐部主理人或管理员可以编辑资料，请返回俱乐部页。」. `club/roles` for a plain member is still only a title (31b). New: `club/settlement` sends a plain member to home with no message. | 30, 31a, 31b, 32a |
| **F9** unpaid order says 实付款 | **Still there**, reworded: the unpaid order shows 「微信实付 ¥69.00」. | 10 |
| **F10** old member id left in storage after logout | **Still there.** `cy_usage_pending_v1:990006` stays after 退出登录, also once account B is logged in. Nothing else of A shows: B's 票夹 has only B's ticket and team. | 28, 29, STORAGE lines in the run |
| **F11** captions | **Partly.** Gone: guest 设置 caption. Still there: cancel-order dialog 「订单关闭,不再保留名额 / 此操作不可撤销,需要时请重新下单」 (12); refund dialog's extra lines (18); logout dialog's two lines (28); 投诉与建议's two lines (guest 120); club/edit helper line (30). | 12, 18, 28, 30 |
| **F12** guest sees 「登录状态已失效」 | **Fixed** on `coop/nearby`: 「附近商家 / 登录后查看 · 登录」. | guest 091 |
| F13 退出 swallowed during the welcome sheet | Not retested (rig-only before). | — |

## 1. Steps

| # | Step | Result | Shot | Read-back |
|---|---|---|---|---|
| G0 | 157 pages as a fresh guest, one browser each | PASS (F1 gone). 0 page errors, no raw text. 1 blank page (N3). | `shots/guest/` | `proof/guest-sweep.json` |
| G1 | Guest → activity 12 → 去加入俱乐部 → 加入 → 「登录后继续」 → 微信登录 | PASS for the write: one `login/code`, then one `club/join`, same page, welcome sheet. FAIL N2: still 「登录后查看」 after login. | g02-*, 21, 22 | 990010 joined |
| G2 | Guest → club 7002 → 加入 → 微信登录 | Same as G1: PASS, plus N2 | g03-* | 990011 joined |
| G3 | Guest → club 「登录」 row | PASS: logs in at the tap; members and leaderboard load | g04-* | |
| G4 | Guest → team invite 接受邀请并加入 → 微信登录 | PASS: prompt 「登录后即可创建或加入队伍」, one `team/command`, then the refusal 「持有这一场的票才能加入」 under the button, on the same page | g05-* | no member row |
| G5 | Guest → clerk invite link `merchant/team?invite=` → 登录 | PASS: logs in at the tap, then the preview 「探店日样板咖啡 · 核销员 · 接受邀请」 | g07-* | |
| G6 | Guest → theme 990014 / talent list / home 发布 | NOT VERIFIED: my taps did not reach a write. The theme's 立即支付 is inside a sheet I didn't open, and talent's 免费加入 navigates to the club page. | g01, g06, g08 | |
| P1 | New participant added in the sheet, 付款 (more than 24 h ahead) | PASS: one create, paid | 03, 04, 05 | order 76 `2/2` |
| P2 | Saved participant auto-selected, pay sheet closed | Closing the sheet returns to the activity page with no message, and the client's auto-cancel is refused (stub). Order 77 stays paying. | 07, 08, 09 | 77 `1/1`; ticket 78 stock 20→19 |
| P3 | Order 77 detail → 取消订单 → confirm | Message shown 「支付结果确认中，请稍后再试」; order unchanged. Server path NOT VERIFIED (stub). | 10–14 | 77 `1/1` |
| P4 | Buy the same session again | FAIL N1: second order 78 paid, 77 still paying, 19→18 | 15, 16 | 77 `1/1`, 78 `2/2` |
| P5 | `/api/registration/pay id=77` after 78 is paid | FAIL N1: pay parameters returned | — | `proof-*.txt` |
| S3 | Session expired on the server, then 取消并退款 (double tap) | PASS: one 401, silent `login/code`, one retry, one refund of ¥69, order 「正在退款」, stock released | 17–20 | 76 refunded, ticket 79 back to 19 |
| S4 | Session expired, then 我的 tab | PASS: 13 parallel 401s → one `login/code` → all retried. One call (`topic/list is_my=1`) answers 「请先登录」 with code 500 and is not retried (LOW). | 36 | |
| W1 | 票夹 offline, then online + 重试 | PASS (F3) | 26, 27b | |
| A1 | Logout from 设置, then account B logs in | PASS for data. F10 still there. | 28, 29 | |
| R1 | Real-game close and resume: 9009 on activity 13 | NOT VERIFIED: 9009 had already finished it in the chain run (medal, 4/4), so 票夹 is empty. | 33–36 | |
| R2 | walk-player-3 starts a game 12 days early | Play page opens with 「活动未开始」, 0/4. No trail sync from the screen. Close and resume on a game with real progress: NOT VERIFIED (only the 自由探索 shop-stamp game exists in this rig). | 37–39 | `run-session/clear` |
| X1 | Teams: outsider, other leader and unpaid member against team 1 (info, join, applications, join-mode, kick, disband, quit, handle) | PASS: all refused. A guest or outsider using an invite code sees only title, counts and names. | — | `proof/proof-team.txt` |
| X2 | Private messages: outsider 9004 on conversation 13 (messages, send, read, delete, mute, receipt, report) | PASS for data: nothing read or changed. LOW N6: `read` and `delete` answer 「操作成功」. | — | `proof/proof-im.txt`, rows unchanged |
| X3 | Game session 13: 5 accounts × 5 perspectives; shared control and export; staff commands with the real revision | PASS: only lead 9008 gets the CLUB view; every staff command from others is refused 「仅本场获授权工作人员可操作」. | — | `proof/proof-gamesession.txt` |
| X4 | Merchant 9005 (8002) on 8001's customers, export task and notes | PASS: 404 for members with no ticket touching 8002, and export 1 refused. 8002 can read and annotate 9009, because 9009's ticket covers 8002's station (by design). | — | `proof/proof-merchant-idswap.txt` |
| X5 | Clerk 9004 (MERCHANT_CHECKIN at 8001) on 15 owner actions | PASS: 403 on all except `verification-records`, which a clerk needs | — | same |
| X6 | Club co-host walk-player-2 (CLUB_OPERATOR) and session check-in walk-player-3 (EVENT_CHECKIN, session 13) | PASS: settlement, withdraw, transfer, dissolve, ban, roles, invoices and the other club's roster are all refused. Check-in staff reads only its own session's roster. | — | `proof/proof-club-roles.txt` |
| N-a | `publish_precheck` for 9 accounts × 7 scopes | PASS: allowed only for merchants with `scope=MERCHANT` and club lead 9003 with no scope or CLUB. Clerk refused, guest 401. Note N7 (lead 9008 at quota). | — | `proof/proof-publish-precheck.txt` |
| N-b | `operators/invite/preview` | PASS: needs login and a live token; reveals only shop name and role; dead after accept (409); short or forged tokens refused | g07 | `proof/proof-merchant-roles.txt` |
| N-c | `team/trail/state`, `consent`, `sync` | PASS for access: guest 401; no paid ticket or other session → `available:false`; opting out deletes the points; a refund stops access; your own team is not returned. LOW N5. | — | `proof/proof-trail.txt` |
| N-d | Cancel of a paying order (`payment_status = 1`) | Other member → 「无权取消他人报名」. Owner → 「支付结果确认中」 (stub cannot answer the query). Close path NOT VERIFIED (HARNESS). | 13 | 75, 77 unchanged |
| N-e | The three guest GETs (`preset/route/list`, `/info`, `city/nodes/{id}`) | NOT COVERED, see §3 | — | — |

## 2. Findings

### N1 HIGH: after the pay sheet closes, the same player can hold two orders for one session, and the stuck one can still be paid

Repro, as walk-player-2 on activity 11:
1. Checkout → 付款 → close the WeChat pay sheet. The client calls `/api/registration/cancel`, which is refused. In the rig this is because the stub can't answer the WeChat query. On a phone it happens whenever the query fails or is slow.
2. The user is back on the activity page with no message, and 立即报名 is live.
3. Buy again → order 78 paid.
4. Order 77 is still 待支付, holds a second ticket (stock 20 → 18), and its 去支付 gets fresh pay parameters (`/api/registration/pay id=77` → 「支付参数获取成功」).

Effect: one participant, 测试乙, can be charged twice for one session. Nothing on the server stops a second order while a paying one exists for the same member and session.

Severity is HIGH, not BLOCKER, because on a phone WeChat usually answers NOTPAY and the auto-cancel then closes 77. That close path is NOT VERIFIED here.

Shots 06–16. Read-back: 77 `1/1`, 78 `2/2`.

### N2 HIGH: a guest who logs in through a write prompt still sees 「登录后查看 · 登录」 on the club page

Repro: as a guest, open club 7002 → 加入 → 微信登录. The join succeeds and the welcome sheet shows. Close it: the members and leaderboard block still says 「登录后查看 · 登录」, although the user is now a logged-in member.

Cause:
- `myMemberId`, which gates that block (`pages/club/detail/index.wxml:250`), is set only on load (`index.js:765`) and on the 「登录」 row's retry (`index.js:846`).
- The `ensureLoginFor('club', …)` resume path never updates it. Self-detection (`isMe` on the leaderboard, post authorship at `index.js:1976`) also stays guest until the page reloads.

Shots g02-c, g03-c, 22.

### N3 HIGH: 我的参与 is a blank page for a guest

`subpackageMember/mycanyu/mycanyu`: the guest sees the title and four tabs over black. `/api/registration/my-joined` returns 401 and nothing is drawn: no 「登录后查看 · 登录」, no retry.

This is the same family as first-pass F3, and every other page of that family now uses the login state.

Shot `guest/125-rerun-*`.

### N4 LOW: plain-member club pages

- `pages/club/roles/index?id=7002`: title only, blank body (31b). This is first-pass F8 still open.
- `pages/club/settlement/index?id=7002`: the member lands on home with no message (32a).

Layout fix: the same single access-gate sheet `club/edit` now uses, without its helper line.

### N5 LOW: team trail is accepted and served before the session starts, and progress is client-reported

- Activity 11 starts 2026-11-17. On 11-05, walk-player-3's points posted by `trail/sync` were returned to walk-player-2 at once. `TeamTrailServiceImpl.eligibleTtl` checks payment, the author's switch and the end time, but not the start time.
- `done: 999` from a client is shown to other teams as their progress (`appendPoints` stores `d` as sent, clamped to 999).
- On screen, the 自由探索 play page showed 「活动未开始」 and sent no trail sync (38, 39), so no user-facing leak was seen. This is server hardening.
- Suggestion: refuse sync before the session's start minus the gather window, and derive `done` from server progress.

### N6 LOW: an outsider's 「已读」 and 「删除」 on someone else's conversation answer 「操作成功」

`/api/im/read` and `/api/im/delete` from 9004 on conversation 13 return 200 「操作成功」, although no row changes (`proof/proof-im.txt`, member rows identical). `messages` and `send-receipt` on the same conversation answer 403 「无权查看该会话」. They should answer the same way.

### N7 LOW (needs owner): sessions a club lead opens count toward its 「活动」 publish quota

After 5 sessions opened through `open-session`, lead 9008's `publish_precheck` answers 「已达到当前身份活动数量上限（3）」. The count is `selectActivityQuotaUsedByMemberId` (`QuotaServiceImpl.java:74`).

If that is not intended, a lead who runs three sessions can no longer publish an event.

### N8 LOW: other polish seen on these journeys

- The activity page showed 「剩余 20 张」 right after a paying order took one. DB `remaining_inventory` was 19 (08).
- The unpaid order shows 「微信实付 ¥69.00」. This is first-pass F9, reworded.
- After logout, `cy_usage_pending_v1:<old id>` stays in storage. This is first-pass F10.
- Captions left, each with the layout fix that removes it:
  - cancel-order dialog: keep the title only (12);
  - refund dialog: keep the amount line (18);
  - logout dialog: title only (28);
  - 投诉与建议: the 「登录」 state alone (guest 120);
  - club/edit: the gate's 返回 makes the line unnecessary (30).
- `stamp-camera` opened with no image shows only 「双指缩放裁切 57%」 (guest 024). It needs the 链接已失效 state.
- After a session expiry, `/api/topic/list is_my=1` answers 「请先登录」 as HTTP 200 / code 500. The request layer doesn't treat it as a 401, so it isn't retried after the silent re-login, and the block stays empty until the next visit.

### N9 LOW (likely HARNESS): refund timeline mixes UTC and local time

The refund step shows 「正在退款 2026-11-05 02:24」 under 「付款成功 10:21」 (20). Rows stamped by MySQL `now()` (`refundApplication.createTime`, entitlement `updateTime`, CRM note `createTime`) come out in UTC, while app-written times are UTC+8.

The rig's MySQL runs in `+00:00`. If production MySQL runs in `+08:00`, this does not happen. The owner should confirm `SELECT @@global.time_zone` on production (read-only).

### HARNESS

- The WeChat payment query and close are stubbed to answer nothing, so cancelling a paying order is always 「支付结果确认中」.
- The rig does not fire component `pageLifetimes.show` on back, so F4 can't be proven.
- No camera feed (`citystamp`, `stamp-camera`).
- Covers are unreachable (OSS).
- The rig MySQL time zone is UTC (N9).

## 3. Not covered

- **The three guest GETs (`/api/preset/route/list`, `/info`, `/api/city/nodes/{id}`).**
  - My sweep of node ids as a guest was stopped while running, and I did not repeat it.
  - Not checked: unpublished or offline ids, field leaks against the guest projection, the path variants of `/nodes/{id}` (`/favorite`, `/complete`).
  - The guest pages that call them opened without errors in G0, but without ids.
- **Mid-game close and resume on story, puzzle and orienteering games.** Only the 自由探索 shop-stamp game exists in this rig, j3's fixtures hard-code its own template ids, and 9009's game was already finished. A real resume mid-game is NOT VERIFIED.
- **Session expiry during payment itself**, between create and the pay sheet. Expiry was tested during refund (S3) and on the 我的 tab (S4), not between 付款 and 继续下单.
- The NOTPAY → close happy path for a paying order (stub).
- F4 and F13 on a phone.
- Guest writes on posts. There is no post data, and the old post endpoint answers 426 「请更新客户端」. Guest writes on the theme sheet's 立即支付 and the 发布 tab (G6).
- Merchant desk per-resource ids (feature off), invoices, coupons and CRM campaigns (no data).
- Real phone behaviour: background/foreground from the OS, real weak radio, the native pay sheet.

STATUS: PARTIAL - guest GET id sweep not done (stopped mid-run), real-game mid-play resume and expiry between create and pay not verified, F4 needs a phone
