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
| Teams | PARTLY | 我的队伍 and 附近招募 empty states open; 加入队伍 with a broken link shows plain 「邀请链接不完整 / 返回上一页」. Creating, joining, leaving and kicking were NOT walked. |
| Reopen | NOT WALKED | No finished run was available in this rig. |
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

- **Time zone on the refund timeline (possible money-screen bug, needs a decision).** After a refund the order timeline shows 「订单已创建 17:37」, 「付款成功 17:37」, 「正在退款 09:40」. In the rig the app runs in GMT+8 but MySQL is UTC, and the refund time comes from a MySQL clock while the created time comes from Java. If production MySQL is also not on GMT+8, players would see a refund time 8 hours early. I did not touch it. Please check production's `@@global.time_zone`.
- Club page, guest view: the 「加入」 button floats over the score of the last leaderboard row in the rig (the gutter uses a measured width the rig can't measure). Probably rig only; check on a phone.
- Club contribution board lists the 主理人 with 「0 场 · 0.0 km」 but 「5.0 分」 (score comes from sessions hosted). Looks intentional.

## Not verified

- Anything on a real phone: payment, camera, WeChat share, content-review hold, map.
- Team create / join / leave / kick; reopen; the final 微信确认 step of deregistration; the guest 发现 feed with posts from other people; the monthly review with a completed theme (needs a finished run).
- The home page 「编辑精选」 and banner images are empty in the rig (no fixtures), so their look was not judged.
- On the rig, a real mouse tap on 「立即报名」 of the topic page did not fire its handler, while calling the same handler directly opened the session picker. I traced it to an overlay on first visit in the club page but not for the topic page; I could not reproduce it as a product fault, so I report it as unexplained.

STATUS: PARTIAL - guest, sign-up, ticket, refund, messages, profile, post and deregistration walked and 5 bugs fixed; team actions, reopen and the final deregistration step not walked
