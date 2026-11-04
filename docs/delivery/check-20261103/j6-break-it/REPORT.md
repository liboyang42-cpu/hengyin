# j6-break-it: first pass

Commit tested: `8b78f5ca3c06f486631e827827e973cbb47e3094` (on `integ/launch-test-20261103`). Branch `claude/check-1104-j6-break-it`. Run 2026-11-04.

**This is not a phone.** Everything ran in the cloud walk rig: the real jar, real MySQL 8 and Redis 7, and the real mini-program code in a headless Chrome at 390×844, 2× screenshots. WeChat login, payment, the map and built-in `<icon>` are rig stand-ins.

## Setup

- Rig: `claude/cloud-walk-20261101-rig` + the club lane's `tools/harness-patches.diff`, `up.sh` pinned to the commit above, `LANG=C.UTF-8`, Docker mirror `mirror.gcr.io`.
- Two rig changes of my own, both in `tools/rig-changes.diff`, neither in product code:
  1. `up.sh` pinned to this commit.
  2. **HARNESS:** `app.py` now passes dummy `CHENGYIN_OSS_*` values. Without them every list holding an image URL failed with the raw text 「Could not write JSON: 对象存储配置缺失: CHENGYIN_OSS_BUCKET_NAME」 (home, 票夹, order info). With them, image URLs point at an unreachable host, so covers are empty in the shots (HARNESS).
- Data: `chains.py player` (all four chains PASS) gave topic 990014, club session activity 13 and ticket 80 at ¥69. Players `walk-player-1..3` (990013–990015) were made by real login.
- **Fixture writes**, all to the test database only:
  - I moved activity 13 and ticket 80 between "tomorrow 12:00" and "started" so tickets were on sale or play could start.
  - Because of that move, the refund tier the order detail shows after the session has "started" is a fixture artefact. I did not report it.
- Tools and step scripts: `tools/` (`drv.mjs`, `run.mjs`, `guest.mjs`, `guest2.mjs`, `cross.mjs`, `idor.py`, `expire.sh`, `sc/*.json`).
- Read-backs: `proof/`.

## 1. Steps

| # | Step | Result | Shot | Read-back |
|---|---|---|---|---|
| G1 | Every one of the 157 pages in `app.json` opened as a guest (privacy agreed, no id params) | FAIL: see F1, F3 | `shots/guest/NNN-*.jpg`, `shots/guest-blank-contact-sheet.jpg` | `proof/guest-sweep.json` (route, text, every request) |
| G2 | Guest opens a merchant link (game-node without id) | FAIL F1: account created silently | 04, 05, 06 | member 990011 created by `/api/login/code`; `proof/db-readback.txt` lists 12 such `walk-guest-*` accounts from this run |
| G3 | Guest opens game-node with an id | PASS: shows 「登录后查看」 | 02, 03 | 401 on `/api/game/session/view`, no login |
| G4 | Guest → activity 13 → 去加入俱乐部 → 加入 → login prompt → 微信登录 | PASS: after login the join completes on the same page | 70, 72, 73, 74 | `club/join` 200 once, after `login/code` |
| G5 | …then back to the activity | FAIL F4: page still says 「加入后查看」 | 75 | |
| G6 | Guest views the club page | FAIL F7: members and leaderboard empty under 「成员 · 2」 | 72 | 401 on `club/members`, `topics`, `post/list`, `leaderboard` |
| B1 | Player 1: triple tap 加入 on the club | PASS: one request, one row | 22 | `club_member` id 3 only |
| B2 | Back to the activity after joining | FAIL F4: stale gate, needs a reopen | 23, 24 | |
| P1 | Checkout with the saved participant auto-selected, tap 付款 | FAIL F5: 「参与人信息需要包含姓名」 although 测试甲 is shown | 29 | page data: `selectedAddress.fullName=测试甲`, `userInfo` has no `realName` |
| P2 | Pick 测试甲 again in the sheet, 付款 → 「取消只退 50%」 → 继续下单 → close the WeChat pay sheet | FAIL F2 | 30, 31, 32 | order 75 created; client calls `/api/registration/cancel` → 「该报名状态已变化,无法取消」; order stays `payment_status=1` |
| P3 | Reopen checkout; triple tap 付款, double tap 继续下单, pay | PASS: one create, one payment | 33, 34, 35 | order 76 `payment_status=2`, `RIG-TXN-76`, one create in the network log |
| P4 | 我的订单 shows the cancelled-sheet order | FAIL F2 / F9 | 36 | order 75 shown as 待支付 with 「实付款：¥69」 |
| P5 | Cancel order 75 from 我的订单 (double tap the confirm) | FAIL F2: refused, the user is dropped on the detail page with no message | 37, 38 | `cancel id=75` → 「该报名状态已变化,无法取消」; row unchanged |
| P6 | Order 75 detail: payment deadline | FAIL F6: 「请在 2026-11-04 00:00 前完成支付」, real deadline 13:15 | 38 | `pay_expire_time 2026-11-04 13:15:29`; API sends `"payExpireTime":"2026-11-04"` |
| P7 | Triple tap 取消并退款 on order 76 | PASS: one request, refund 50% | 52 | `cancel-refund` once, `cashRefundAmount 34.50`, row `4/3`, ticket released |
| P8 | Offline, then 付款 | PASS: no order; inline 「这次报名还没完成 / 同意记录未保存，请检查网络后重试 / 重新提交」 | 80, 81 | no row until back online |
| P9 | Back online, pay again | PASS: one order | 82 | order 77 paid, one create |
| P10 | 4 s latency, then triple tap 付款 and 继续下单 | PASS: 「提交中...」, button locked, one order | 83, 84, 85 | order 78 paid, one create, one pay |
| P11 | 5 s latency, then press back while the order is being submitted | PASS: no order left behind | 86, 87, 88 | no row for 990018 |
| S1 | Session expired on the server (Redis token deleted), then open an order detail | PASS: one 401, silent re-login, retried once, detail shown | 53 | `registration/info` 401 → `login/code` → 200 |
| S2 | Session expired, then a write (退出俱乐部) | PASS: one 401, re-login, one retry, done | 55, 56 | `club/quit` 401 → `login/code` → `club/quit` 200, row gone |
| S3 | Session expired, then a refund | NOT VERIFIED: my first expiry script did not delete the tokens (quoting bug), so P7 ran with a live session | — | fixed in `tools/expire.sh` and used for S1/S2 |
| W1 | 票夹 opened while offline | FAIL F3: black screen, no message, no retry; stays black after the network returns | 14, 15 | page data `walletState:"error"` |
| A1 | Account A (9009) logs out from 设置 → 退出账号 → 退出登录 | PASS | 60, 62, 63 | storage after: only `cy_usage_pending_v1:9009` (F10) |
| A2 | Guest member tab after logout | PASS: 「登录后查看我的主页」 | 64 | |
| A3 | Account B (990014) logs in on the same device: home, 票夹, orders, checkout | PASS: nothing of A shows | 65–68 | B's own storage only; no orders, no participants |
| X1 | Player 9004 calls 9009's order 74 (info, pay, cancel, cancel-refund, cancel-by-owner, dyncode, seat receipt) | PASS: all refused | — | `proof/idor-orders.log` |
| X2 | Club lead 9008 and player 9004 against club 7001: settlement, withdraw, roles, ban, owner transfer, finance, membership | PASS: all refused | — | `proof/idor-club-merchant.log` |
| X3 | Merchant 9005 against merchant 8001: aftercare detail, desk products | PASS: refused or feature off | — | same log |
| X4 | Client: player 2 opens player 1's order 76 by URL | PASS: refused, lands on own empty order list | 40 | `registration/info` 「无权查看该报名信息」 |
| X5 | Client: member opens club 7002 edit, settlement, roles, customers, workbench; merchant 9005 opens a ledger order; player opens the merchant ledger; lead 9008 opens club 7001 edit | PASS for data: nothing leaked. FAIL for screens: F8 | 41–49 | `proof/client-id-swap.json` |
| R1 | Start game from 票夹, leave to home, come back, start again | PASS (shallow): same server progress 0/4 | 92–96 | `play/run-session` empty, `game/session/view` 「本场游戏尚未准备」 |
| T1 | Team pages, other users' teams by id | NOT VERIFIED: no team existed in the rig data; j4 builds teams | — | |

## 2. Findings

### F1 BLOCKER: a guest who opens a merchant page gets a WeChat account created silently, then lands on 「我的」 as 「城瘾用户」

- Repro: fresh install, agree to privacy, then open `pages/merchant/game-node/index` with no id (an old or broken share link). The page shows 「链接已失效」 and auto-backs to `pages/merchant/index/index`. There `onShow → enterConsole → ensureSession` calls `wx.login` and `/api/login/code`. A member is created, and the app redirects to the member tab, now logged in with 50 points.
- The guest sweep found eight guest entry points that do this:
  - `pages/merchant/index`, `merchant/relation`, `merchant/game-node`, `merchant/verify-records`, `merchant/crm-campaigns`, `merchant/team` and `subpackageMember/tixian` (via `exitPage` → `reLaunch('/pages/merchant/index/index')`, `tixian.js:69`), all ending on 「我的」 logged in.
  - `merchant/marketing` logs in and stays on its page.
- This breaks the owner's 2026-10-24 rule written in `app.js:142-144`: a guest's account is created only in the write-action login prompt.
- Code: `pages/merchant/index/index.js:732-757`, whose comment still cites the older 2026-09-16 ruling 「未登录先静默登录」.
- Severity: account creation without the user asking is a consent problem, and the user is misled into a logged-in state.
- Read-back: 12 `walk-guest-*` members in `proof/db-readback.txt` came from this alone.
- Shots: 04, 05, 06; guest sweep 061, 070, 073, 083, 085–087, 136.

### F2 HIGH: closing the WeChat pay sheet leaves an order the user can neither cancel nor see as unpaid

- Repro, as player 1:
  1. Checkout → 付款 → 继续下单.
  2. Cancel the pay sheet.
  3. The client calls `/api/registration/cancel`. The backend refuses with 「该报名状态已变化,无法取消」, because `beginPaymentAttempt` set `payment_status=1` and `cancelUnpaidIfActive` only accepts 0 or 3 (`CmsRegistrationMapper.xml:1045-1055`).
- What follows:
  - The order stays in 我的订单 as 待支付 and holds a ticket (inventory 20 → 16 with 3 paid and this one).
  - 取消订单 fails the same way, and the user is dropped on the detail page with no message (37, 38).
  - Buying again makes a second order (76), so the user now has a stray 待支付 order beside a paid ticket. Paying it would buy a second ticket.
- Shots: 31, 32, 36, 37, 38. Read-back: order 75, `proof/db-readback.txt`.
- Not checked: whether the expiry job later closes it (quartz jobs are paused in the rig).

### F3 HIGH: 票夹 is a black screen when its list fails, with no message and no retry

- Repro:
  1. Player 9009 → 我的 → 票夹 with the network off. The page data is `walletState:"error"`, but nothing is drawn under the title.
  2. Turn the network back on: still black, and no 重试 exists.
- The same black screen showed when the backend returned an error (the OSS gap above).
- Shots: 11, 14, 15.

**Related, guest blanks:** about 30 pages show only a title, or nothing, to a guest. They have no login prompt and no error, even after 8 s. Their read requests get 401 and the page draws nothing.

- The pages are:
  - Activity pages: `activity/official-detail`, `official-inbox`, `official-mine`.
  - Club pages: `club/create`, `customers`, `customer-detail`, `detail` (no id), `enroll`, `checkin-detail`, `event-ops`, `governance`, `notify`, `roles`, `settlement`, `topic-detail`.
  - Merchant pages: `merchant/aftercare`, `citynode`, `customer/detail`, `play/merchant`.
  - Template pages: `coop/invite-detail`, `templatedetail`, `templatedetail/topic-detail`.
  - Member pages: `address`, `shezhi/marketing`, `gerenziliao`, `subpackageMember/coupon`, `report-mine`, `appeal-mine`.
  - Roam pages: `subpackageRoam/history`, `citystamp`.
- Contact sheet: `shots/guest-blank-contact-sheet.jpg`.
- Layout fix that removes the need for any caption: the 「登录后查看…  登录」 empty state most pages already use.

### F4 HIGH: activity page stays on 「加入后查看 / 去加入俱乐部」 after the user joins

- Repro: activity 13 → 去加入俱乐部 → 加入 (succeeds) → back. The page still gates and says 「去加入俱乐部」, with or without a login on the way. Only reopening shows the session and 立即报名.
- Users will think the join failed and tap again.
- Shots: 23, 24, 75.

### F5 HIGH: first 付款 with an auto-selected participant always fails with 「参与人信息需要包含姓名」

- Repro: a player with one saved participant opens checkout. The participant row shows 测试甲 13700000001; tap 付款 and the error appears.
- Cause:
  - `getUserInfo` (`baoming.js:336-352`) stores the `/api/user/info` profile in `userInfo`. The profile has `name`, but no `realName`.
  - The auto-selection sets `selectedAddress`, but its name never reaches `userInfo`. Only picking it in the sheet calls `updateUserInfoFromAddress` (`baoming.js:508`).
  - The check at `baoming.js:1576` reads `userInfo.realName`.
- The user has to reselect the same person in the sheet. The message is false.
- Also, per ROUND8, the error shows as a pink block with an icon, not red text under the field.
- Shots: 29, 30.

### F6 HIGH: unpaid order says 「请在 2026-11-04 00:00 前完成支付」 when the real deadline is 13:15

- Cause: `CmsRegistration.java:162` has `@JsonFormat(pattern = "yyyy-MM-dd")` on `payExpireTime`. The order detail (`scene-member-order-detail/index.js:971-973`) shows the date at midnight, a time already passed.
- Shot: 38. Read-back: `pay_expire_time 2026-11-04 13:15:29` against API `"payExpireTime":"2026-11-04"`.

### F7 HIGH: a guest's club page shows 「成员 · 2」 with an empty member row and an empty leaderboard

- `club/members`, `club/topics`, `club/post/list` and `club/leaderboard` return 401 for a guest, so sections render empty under real counts.
- None of these is on the approved guest-read list. Ask the owner whether they should open, or the sections should show the guest login state.
- Shot: 72.

### F8 LOW: no-permission screens

- `pages/club/roles` for a plain member is title-only and blank (43). This is the same blank as F3.
- `pages/club/edit` for a non-manager shows two stacked denials: the sheet 「暂时不能查看 / 当前账号无权进入该俱乐部」 and, behind it, 「暂无编辑权限」 plus the helper line 「只有该俱乐部主理人或管理员可以编辑资料，请返回俱乐部页。」 (41, 49).
- Layout fix: one state, the access-gate sheet only, whose 「返回」 makes the helper line unnecessary.
- **Explains the ROUND8 lead 「pages/club/edit … for club lead 9003」:**
  - In this fixture, both club leads have `ums_member.role = player`.
  - `ClubAccessServiceImpl.resolve` grants `CLUB_OWNER` only when `role = club` (`ClubAccessServiceImpl.java:63-65`), so 9003 and 9008 get 403 on their own clubs. Settlement on their own club is refused too (`proof/club-owner-fixture-role.log`).
  - That is a fixture gap (HARNESS) unless production has club owners whose role is not `club`. The owner should check that with a read-only query.

### F9 LOW: 我的订单 labels an unpaid order 「实付款：¥69」

Shot: 36.

### F10 LOW: after logout, `cy_usage_pending_v1:9009` (the old member id) stays in storage

- Nothing else of account A remains.
- Proof: STORAGE lines in the A1 run (shots 63, 65).

### F11 LOW: captions under the owner's rule (seen on this journey only)

The layout change named with each item would make the caption unnecessary.

- Activity page 「首次真实评价的积分以平台当前规则为准」 (32): drop it, since the rule lives in 积分明细.
- Cancel-order dialog lines 「订单关闭,不再保留名额 / 此操作不可撤销,需要时请重新下单」 (37), and the refund dialog's four lines (51): keep only the amount line; the title already says what happens.
- Logout dialog 「本机登录态清除… / 已购通行证、订单和券都保留在账号里,不会丢」 (62).
- Guest 设置 「点击登录，协议与隐私说明仍可查看」 (guest 105).
- 投诉与建议 has two helper lines (guest 120).
- 「拖动可旋转查看」 (guest 027).

Legal and consent text (「同意将姓名、手机号提供给主办方，用于报名核验」, agreement pages) is not counted.

### F12 LOW: guest copy says 「登录状态已失效，请重新登录」 although the guest never logged in

- Seen on `coop/nearby` (guest 091). It is the raw backend 401 message.

### F13 LOW: club 退出 ignored taps while the join welcome sheet was pending

- Seen in the rig: tapping 退出 within about 4 s of opening did nothing. After 知道了 it worked.
- Calling `dc.open` directly showed the dialog, so the tap is swallowed while the sheet loads. Rig only, NOT VERIFIED on a phone.
- Shots: 55, 55c.

### HARNESS (not product)

- OSS config missing in the rig (fixed in `app.py` as above).
- Cover images unreachable.
- The consent tick (`<icon type="success|circle">`) is not drawn, so the checkbox looks absent in shots 26–33.
- 关于 shows 「版本 开发版本」 because the rig has no version.
- Storage is lost when the rig browser closes, so "kill the app" was simulated by `reLaunch` inside one session (R1).

## 3. Not covered

- Real phone behaviour of all of the above, the native WeChat pay sheet, background/foreground (`onHide`/`onShow` from the OS), real weak radio, the native map.
- Session expiry during payment and during refund (S3); refund on the WeChat side (`pay.py` seam only).
- Mid-game close and resume for story, puzzle and orienteering games: only a shop-stamp day existed, so R1 is shallow. The live 990068/990069 configs are j3's and are not in this rig.
- Teams (create/join/kick by id as another user), IM conversation ids, game-session `perspective` swapping, merchant desk per-resource ids (feature off), invoices (feature off).
- Merchant and club write endpoints with ids belonging to another merchant/club beyond the ones in `proof/idor-*.log`.
- Clerk and co-host roles (fixture leads lack `role=club`, F8).
- Login return path for every guest write; only the club join (G4) was walked.
- The three approved guest reads (`/api/preset/route/list`, `/info`, `/api/city/nodes/{id}`) were not reported, as instructed. Map and route pages were skipped.

STATUS: PARTIAL - first pass; S3, T1, deep game resume and the remaining id-swap targets listed above are not done
