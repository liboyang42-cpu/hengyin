# Round 13 · lane m5-player-journey-walk

Branch base: `integ/launch-test-20261103` at `0d82371f1`. Walked on the cloud rig (real jar, real MySQL and Redis, the real mini-program code in a headless browser). **This is not a phone.** Screenshots I looked at are in `integ/fix-shots/m5-*`.

Rig start: Docker started in this container without trouble. The rig had to be built from the branch tip, which needed one fix first (bug 0 below).

## Rig fixtures I had to add (rig only, nothing in the product)

- `settlement_rule` had no platform rows, so the `merchant_club` chain failed at 招募到期. I added the three 3% rows (activity, topic, topic_club), like the production migrations.
- `cms_points_setting` id 1 was empty, so a new player got 0 welcome points. I added 50, like `migration_20261105_welcome_points_fifty.sql`.
- The topic cover pointed at an OSS key, so `/api/topic/list` failed. I switched it to a local file and set the media base URL (same trick as the m2 notes).
- A free ticket (price 0) copied from ticket 78 so the free path could be walked. Its name shows as garbled text on screen only because my SQL client used the wrong charset.

## Journeys

| Journey | Status | Notes |
|---|---|---|
| Guest opens app, views home, topics, discover, club, club posts | WALKED | Nothing private shown. Home, topic page, club page and post list all open. |
| Guest write → login pop-up | WALKED | 加入 俱乐部 shows 「登录后继续 / 登录后即可加入俱乐部」. Comment button was broken (bug 3, fixed). |
| New player sign-up, welcome points | WALKED | Login through the pop-up joined the club. 积分明细 shows 「注册赠送 +50, 余额 50」 (after rig fixture). |
| Home, discover, topics, clubs, posts | WALKED | Home, 发现 (帖文 and 俱乐部), 主题, 模板 page, club overview and 帖文 all open with plain text. |
| Paid ticket (¥69) | WALKED | Picker → 结算 → 参与人信息 → consent → 付款 → order detail ¥69.00. |
| Free ticket | WALKED | 确认报名 → 「免费报名成功」, ¥0.00. |
| 参与人信息 | WALKED | Empty save showed pink blocks (bug 1, fixed). Filled save works; phone shows masked on the order. |
| Ticket wallet | WALKED | Card with name, date, place, 开始游戏. |
| Teams | WALKED (second pass, below) | See "Second pass". |
| Reopen | WALKED (second pass, below) | See "Second pass". |
| Refund / leave | WALKED | 取消并退款 dialog states ¥69.00 and the free-cancel deadline. After confirming: 「报名已取消，现金退款正在派发」. Money read-back only; settlement untouched. |
| Messages inbox and outbox | WALKED | Inbox shows 系统通知 「报名成功」 and opens as a card. Outbox: 「还没有发出的邀请」. Guest view had two messages at once (bug 4, fixed). |
| Profile, badges, stats, monthly review | WALKED | 主页, 成就 (50 points, 排名), 帖文, 关于, 勋章墙 empty with 「去探索」, 月度回顾 for a new player is empty without fake numbers. |
| Publish a post through review | PARTLY | A club post by a member was published and showed at once, also for a guest. The rig's content check is a stub that approves, so a real review hold was NOT seen. |
| Deregistration | WALKED | With a refund pending: 「暂时无法注销 · 存在处理中的退款」. Clean account: the consent screen opens; the title was glued to the left edge (bug 5, fixed). The final confirm step needs WeChat and was NOT walked. |
| Every page as a guest (profile, messages, points, orders, recap, deregister, my teams) | WALKED | Each asks for login; none leaks another person's order. |

## Bugs fixed

0. **Test build broken (all CI).** `ThemeRuntimeMySqlIT.java` used `new Date()` with `java.sql.*` and `java.util.*` both imported, so `mvn package` failed to compile tests. Added `import java.util.Date;`. File: `chengyinhub-admin/src/test/java/com/chengyinhub/business/it/ThemeRuntimeMySqlIT.java`. Proof: `mvn -DskipTests package` passes after it, before it failed with "reference to Date is ambiguous". Commit `abfa99068`.
1. **参与人信息 errors were pink blocks.** The owner's rule is red text under the field. Changed the two errors to `kind="field"`. Files: `pages/addressinfo/addressinfo.wxml`, test `tests/unit/r4-input-error-under-field-sweep.test.js` (updated to the owner's rule, not loosened). Proof: `m5-addressinfo-before.jpg` / `-after.jpg`; the test passes.
3. **Guest opening a post's comments saw 「评论加载失败 · 网络异常，请重试」.** The server gives guests no comments (owner decision), so it returned 401 and the page called that a network fault. Now the comment button asks a guest to log in first. File: `pages/club/detail/index.js`. Proof: `m5-guest-comments-before.jpg` / `-after.jpg`; 30 related tests pass.
4. **Guest messages page showed 「邀请暂时没读到 · 重试」 above 「登录后查看消息」.** Banner now hidden when login is needed. File: `subpackageB/pages/im/list/index.wxml`. Proof: `m5-guest-messages-before.jpg` / `-after.jpg`; 219 related tests pass.
5. **注销账号 title stuck to the screen edge.** `flush` was wrongly passed; removed it and removed the page from the test's frozen "legacy" list as that test itself instructs. Files: `pages/deregister/index.wxml`, `tests/unit/page-title-flush-contract.test.js`. Proof: `m5-deregister-before.jpg` / `-after.jpg`; test passes.
6. **Gate housekeeping.** The action ledger had drifted because my club-page change moved lines (controls unchanged: 5,741); rebuilt. `ui-integrity-baseline.js` had one waiver pointing at `pages/merchant/apply/index.js:359` after another lane moved the code to line 360; re-pointed it (no new waiver).

## Checks run

- `node scripts/uiaudit/build-action-ledger.js --check`: PASS, 5,741 controls.
- `node tests/lint/ui-integrity.js`: UI-GATE-0:PASS (after re-pointing one waiver line and recomputing the u4Dynamic digest).
- Unit tests that touch the changed files: pass (listed above).

## For the lead (not changed by me)

- **Time zone on the refund timeline (possible money-screen bug, needs a decision).** After a refund the order timeline shows 「订单已创建 17:37」, 「付款成功 17:37」, 「正在退款 09:40」. In the rig the app runs in GMT+8 but MySQL is UTC, and the refund time comes from a MySQL clock while the created time comes from Java. If production MySQL is also not on GMT+8, players would see a refund time 8 hours early. I did not touch it. **Update from the lead:** production's database is on China time (checked read-only), so this is a rig artifact. No action needed.
- Club page, guest view: the 「加入」 button floats over the score of the last leaderboard row in the rig (the gutter uses a measured width the rig can't measure). Probably rig only; check on a phone.
- Club contribution board lists the 主理人 with 「0 场 · 0.0 km」 but 「5.0 分」 (score comes from sessions hosted). Looks intentional.

## Not verified

- Anything on a real phone: payment, camera, WeChat share, content-review hold, map.
- Team create / join / leave / kick; reopen; the final 微信确认 step of deregistration; the guest 发现 feed with posts from other people; the monthly review with a completed theme (needs a finished run).
- The home page 「编辑精选」 and banner images are empty in the rig (no fixtures), so their look was not judged.
- On the rig, a real mouse tap on 「立即报名」 of the topic page did not fire its handler, while calling the same handler directly opened the session picker. I traced it to an overlay on first visit in the club page but not for the topic page; I could not reproduce it as a product fault, so I report it as unexplained.


## Second pass (team actions and reopen)

The rig had stopped between sessions, so I rebuilt it (fresh database, same fixtures, seed chains). Topic 990014 was set to team mode with a cap of 3 (rig fixture). Four players bought the ¥69 ticket for the same session through the real screens.

| Step | Result |
|---|---|
| Create team (order page → 叫上队友 → size sheet → 3 人队伍) | WALKED. Team page opens: 招募中, 1 / 3, 邀请队友, 公开招募 switch. The size sheet only offers 2 and 3 (the topic's cap). |
| Join with a real invitation link (invite code) | WALKED. Players 2 and 3 joined: 2 / 3, then 3 / 3, button changes to 查看队伍. |
| Full team | WALKED. The fourth player sees 「队伍已满」 and no join button. Leader page shows 已满员. |
| Kick | WALKED. Confirm dialog explains the consequences in plain words; after 移出队员 the DB shows the member removed and the team back to 招募中 2 / 3. The kicked player re-opening the link and tapping join gets 「暂时无法加入该队伍」 (the server's deliberate wording, nothing joined). |
| Leave | WALKED. A member leaves with 退出队伍, lands on 我的队伍, the team is back to 1 / 3 and appears under 附近招募. |
| Leader leaves | WALKED. Two players rejoined; the leader left with 退出并移交队长; leadership went to the earliest member (DB checked). |
| Disband | WALKED. New leader disbands; everyone is out, 我的队伍 shows no team, and an old member opening the team link sees 「无权查看该队伍」. |
| Reopen after finishing | WALKED as player 9009 (finished 990014 with all 4 stops in the chain). Order detail shows 已完成 / 本次订单已核销; opening the play page for that order shows 「本轮探索已完成 · 看看这一天的收获」; tapping it opens the finish card (探店完成, 用时, 公里, 4/4, 邮票 4), 查看记录 shows the route card, 继续 goes back to the order. Badge count stayed at 1, points and balance unchanged: nothing re-awarded, nothing broken. |

### Bug fixed in the second pass

7. **A finished order still offered 「和队友一起出发」.** After the run was completed and verified, the order page still showed the team-up card. It is now hidden once the order is 已完成. File: `subpackageMember/components/cy/scene-member-order-detail/index.js`. Proof: `m5-finished-order-before.jpg` / `-after.jpg`; 89 related tests pass; ledger rebuilt: 5,743 controls, unchanged on disk.

### Rig gaps found (not product bugs)

- The rig's runtime had no `wx.getRandomValues`, so every team command (join, kick, leave) hung on 「加入中…」. I added it to the rig runtime only. On a phone it exists; not verified on a phone.
- Team page shows 「场次开始 11月15日 02:00」 in the rig for a 10:00 session: the server sends `+08:00` and the rig's browser is on UTC. On a phone in China it shows 10:00. Not confirmed on a phone.

### Seen, not changed

- A new player's default name is 「城瘾用户」, so a team of three players shows three identical names to the leader (hard to tell whom to kick). It is the default nickname, not a team page fault.
- A few player-facing lines still say 「核销」 (disband dialog: 「需要自己去核销」; order page: 「已核销 0/4 家」). I left them as the existing wording.

### Still not walked

- The final 微信确认 step of deregistration (needs WeChat). Not walked, as you said.
- A free theme played to the end. The only finished run in the rig is the ¥69 ticket theme from the chain; reopen behaves the same on its order, but I did not build and play a free theme from scratch.
- Everything on a real phone, and a real content-review hold on posts.


## Third pass (taking over the pages leftovers)

Pulled the head, read `CODEX-PAGES-RESULT.md` and `ROUND12-K9-RESULT.md`, and skipped what the log shows as done. The rig had stopped again; I rebuilt it.

| Item | Result |
|---|---|
| Missing-param pages: club 编辑资料, 入会申请, 团核销码 opened with no id as a club lead | WALKED. 编辑资料 「没有指定俱乐部 · 返回俱乐部」 and 入会申请 「无法打开入会申请 · 返回俱乐部」 are plain with a button and no helper line. 团核销码 had a second line 「当前账号不能出示这一场的团码」 on its no-permission screen, repeating the error under it; removed (`pages/club/group-code/index.wxml`, pushed). |
| Publish page for a club lead (activity publish) | NOT WALKED. In the rig the club lead account has no 主理人 role rows, so the page refuses with 「发布单场活动需要俱乐部主理人身份」; I did not build role fixtures. So the content-screen leftovers (j5 N5, N6 field-level error placement, summary over the ticket button, N11) are NOT done. |
| Organiser/merchant details (p2 N-14 rest, N-16 tags, N-17 rest), editor preview overlap (p3 N18), data-filled pages (game node, coop settlement, batch detail, prediction), carried L2/L5-L8/L10 | NOT done and NOT walked. They need journey fixtures I did not have time to build. |
| Owner and write-contract decisions | Not touched. One plain line each, with a launch recommendation, is in `ROUND13-OWNER-DECISIONS.md`. |


## Fourth pass (fixtures and the remaining leftovers)

Rig rebuilt again (it stops between sessions). The seed chains pass once the platform fee-rule rows are in. For the publish pages I set `ums_member.role = 'club'` for 9003 and 9008 in the rig database (rig fixture only), so a club lead could open them.

| Item | Result |
|---|---|
| Publish activity, club lead, step 1 with nothing filled | WALKED. Four red lines under their fields. |
| Publish activity, step 2: cover error | **Bug fixed (8).** 「请上传活动封面」 was drawn inside the red cover box, on its bottom edge. Moved under the box like every other field. `pages/publish/activity/index.wxml`; `m5-publish-cover-before/after.jpg`; 185 related tests pass. |
| Publish activity, step 3: summary over 添加票单 | WALKED, not reproduced. With ticket errors showing, the 「发布前摘要」 sits above 添加票单 and covers nothing; errors are red text under each field. |
| Publish deep link without `scope` (j5 N11) | **Bug fixed (9).** A merchant opening 发布活动 without `scope=MERCHANT` was told 「发布单场活动需要俱乐部主理人身份」, a dead end for a merchant who is allowed to publish. A merchant account now goes through merchant hosting even without the parameter. Club lead and player behaviour re-checked (lead opens the form, player is still refused). `m5-merchant-publish-noscope-before/after.jpg`. |
| Batch detail (对公结算) | WALKED with one fixture row I inserted (¥128.50, status 待对账). Header, 待对账 / 未开票 chips, 「本批次没有可列明的条目」 and the 打款进度 steps read plainly. The empty entry list beside a ¥128.50 total is my fixture, not a product number. With no batch id the page says 「缺少结算批次信息」 and 返回上一页. |
| Coop settlement detail | NOT walked with data. This rig's only coop order is 引流 (pays nothing), so no real settlement exists; the settlement jobs ran and made none. A row I inserted by hand was refused with 「结算记录不可见」, which is the right behaviour for a made-up row. With no id: 「缺少结算记录信息」. |
| Game node (merchant station page) | NOT walked with data. It opens from a club director session's 「站点邀请」. This rig's seed theme is a 自由探索 theme, and `/api/club/lead/start` answers 「该场次不是带领场」; there is no game session in the database. |
| Prediction (预测待答) | NOT walked with data. It needs a prediction node with player answers; the rig has none. Empty state reads 「没有等你给答案的预测」. |
| Organiser invite wording (p2 N-14 rest), customer tags (N-16), N-17 rest, p3 N18 editor preview overlap | NOT done. I opened the coop list as the receiving club lead (card reads 「已接受 · 来自 城瘾跑团 · 接收俱乐部：执行俱乐部 · 引流型 · 无分成」) and found nothing I could name as wrong against the notes, but the notes do not say which lines they meant. |


## Fifth pass (real flows from the m4 scripts)

Rig rebuilt. I ran `proof/k5-walk/s0`..`s7` (with `PYTHONPATH` pointing at the rig's `pay.py`) on this rig: a club (外滩夜跑社) with a city-orienteering theme, two merchant stations (one ¥8 per verified person, one 引流), two sessions, five paid players, a 50% refund, the 14-day wait and the real settlement job. I also gave the rig the dummy OSS settings (rig `app.py` only), without which the coop list showed a blank page. I only read money; no settlement code or rule was touched.

| Item | Result |
|---|---|
| Club settlement (俱乐部结算), read only | WALKED as the club lead. Two session rows: ¥59.66 (销售额 78.00, 平台服务费 3% 2.34, 商家核销费 16.00) and ¥78.57 (97.50, 2.93, 16.00), 可提现 ¥138.23. 78.00 − 2.34 − 16.00 = 59.66 and 97.50 − 2.93 − 16.00 = 78.57, so the screen adds up. Same figures as m4. |
| Coop settlement detail (per-theme) | NOT walkable. The page opens from 「各主题结算」 on 俱乐部结算, and that section is empty: the settlement job wrote per-registration lines (platform, merchant, coop_merchant) but no per-theme `coop_settlement` row for a ¥8-per-head terms order, so there is nothing to open. Step: run job 145 and 146 after the s0–s7 scripts; result `coop_settlement` has 0 rows. With no id the page says 「缺少结算记录信息」; with a made-up id 「结算记录不可见」. This is the same gap as m4's M1 (not mine to change). |
| Game node (merchant station page) | WALKED with data. Steps: as the lead, `/api/game/session/command` `PREPARE` on session 14 (script `/tmp`-style helper, same call the director page makes); the merchant's home then lists a 「待接受邀请」 card, and the station page shows 「待接受邀请 · 拒绝邀请 / 接受邀请」. Accepted as the merchant: 「已接受 · 待准备」 with the preparation form, 最近操作 row 「接受邀请 · 已应用」. |
| Game node: 「第 1 版」 under every 最近操作 row | **Bug fixed (10).** A version number meant nothing to a shop. Removed; only 「已处理过」 shows when a command was a repeat. `pages/merchant/game-node/index.wxml`. |
| Game node: confirming 准备完成 with the form empty | **Bug fixed (11).** Nothing visible happened (a toast that is gone quickly). Errors now show as red text under 接待容量, 服务日期, 服务时段 (and under the checklist when unticked). `index.js`, `index.wxml`, `index.json`; `m5-gamenode-ready-errors-after.jpg`; 66 related tests pass. |
| Prediction (预测待答) | WALKED with data. The rig has no prediction node, so I fixed the data in the rig database: station template 6005 got a prediction question with two options, node 75 was linked to merchant 9002, and one open round with two player answers was inserted (rows written directly, labelled as fixture). The inbox shows 「共 1 轮等你给答案 · 外滩钟楼 · 还剩 1 天 · 2 个人选了这一轮」; 给答案 opens the options, 就是这个 asks 「公布答案「少于 20 杯」？ … 只能公布一次」, 公布并结算 empties the inbox. I did not read back points paid. |
| Prediction confirm title | **Bug fixed (12).** It ended with a half-width 「?」; now 「？」. `pages/merchant/predict/index.wxml`. |
| Coop invite wording (p2 N-14) | **Bug fixed (13).** The accepted invite said 「已接受，归属已冻结」 (and 「接受后归属冻结」 before accepting). Now 「已接受，据点不能再换」 / 「接受前请确认，接受后据点不能再换」. `utils/coop-invite-view.js`; 65 related tests pass; `m5-coop-invite-detail-after.jpg`. Not touched: 评价 timing (owner), and the 联系方式 row shows only the sender's name when no phone is released (the page's own rule for an accepted invite). |
| Customer tags (p2 N-16) | WALKED, nothing wrong seen. 客户 for the shop shows one customer with 「新客 · 最近已到店 · 今天」, 「1 位 · 本月新增 1」. The k5 players are group scans, so the shop's CRM has no refund customers to show; the refund tag rule itself remains covered by k9's tests only. |
| Duplicate quick actions (p2 N-17) | NOT re-checked; needs a shop that only accepted an invite and has a project open on merchantinfo. |
| Editor preview overlap (p3 N18) | NOT reproduced. In the topic editor the story-flow sheet has its own 「下一步 →」 button at the bottom by design; the main screen's buttons are hidden under the sheet. I could not find another preview sheet that overlapped, and the notes do not say which one. |

Rig tap note: on three screens (settlement rows, prediction card, topic CTA) a real mouse tap did not run the page's handler in the rig while calling the same handler did. I used the handler and say so above; I could not prove it is rig-only.

STATUS: PARTIAL - game node and prediction walked with real data and 4 more bugs fixed (10–13); coop settlement detail has no row to open on this scenario (exact step above); p2 N-17 duplicate actions and p3 N18 not confirmed
