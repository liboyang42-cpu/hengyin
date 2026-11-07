# k4 cs: club session fixes N-01 to N-04, N-09 and taps on the theme page (rig, 2026-11-05)

This ran in the local rig, not on a phone and not in production: candidate `0aafbdf6` (includes `90b6d42d` N-01..N-05 and `0aafbdf6` N-09). Backend is 127.0.0.1:8099, the mini-program bundle runs in headless Chrome at 390x844 with 2x JPEG captures. All data is fictional.
Scripts are in `/tmp/claude-0/-home-user-hengyin/29eb85d1-8404-5db4-8c29-72e4a4fe2172/scratchpad/k4cs/`, adapted from j2. Setup shots are in `setup/`.

## Own fixtures (no rows of the other walker were touched)
- Lead `k4cs-lead` (member 990005) became 主理人 through the UI and created club **990004 静安夜行社**. Members `k4cs-m1/m2/m3` (990006/7/8) joined through the UI.
- Merchant: fixture **9006 / 探店日样板花店 (8003)**.
- Templates 6005-6007 (API, editor endpoint). Theme **990015 「夜行定向 · 广场报时」** was created through `/api/topic/v2/create` with the editor body (API leg, same as j2 c_create.py). Cover = a rig screenshot uploaded through the real upload path, so it shows 「俱乐部已创建」 on cover thumbnails. That is a fixture artifact, not a product bug.
- Admin approvals came from the real `/business/audit/review`, restricted to my own task ids (1,2,3 TEMPLATE; 4,5 TOPIC_PROJECT_REVIEW/TOPIC 990015; 8 TOPIC_SALE_REVIEW 990015).
- The merchant applied for station 71 人民广场音乐喷泉 through the UI (registration 14).
- **TIME SHIFT 1:** `cms_topic 990015.recruit_deadline` was moved into the past, then the real job 149 (topicRecruitDeadlineTask) was run through `/monitor/job/run`, which gave lifecycle 2. The job is global, but it only acts on themes whose deadline has passed.
- Session: activity **9** at 2026-11-05 20:00 from 开一场. The time wheel value was injected through the page's own `onStartTimeChange` (HARNESS seam). Payment used the rig pay seam, giving registrations 74/75/76 with payment_status 2 and ¥39 each.
- Team 1 (2 people, m1 + m2) was created through the UI, which satisfies the readiness rule 「尚无有效报名队伍」.
- The merchant station was prepared through the UI. Service date and time were set through the page's own `onServiceDateChange` / `onServiceTimeConfirm` (HARNESS seam) to 11-05 20:00-23:00.
- **TIME SHIFT 2:** `seed-walk.py shift-session 9` moved the start to 14:21:26 for the station-window refusal. **Reverted** afterwards to 20:00:00..23:59:59 with a direct UPDATE.

## 1. N-01 招商: confirm, then leave without sending: **PASS**
- `cs-01` shows the application in 收到的申请. `cs-02` is the 确认候选 sheet. After 确认, the page lands on `pages/coop/invite` (`cs-03`) and I left it with navigateBack without sending.
- (a) `cs-04`: the 已确认 row reads 「探店日样… 未发邀约 · 人民广场音乐喷泉」 with a 「发邀约」 pill. `cs-09`: the pill opens `pages/coop/invite` with `toId=9006&toName=探店日样板花店&topicId=990015`, and the merchant is locked in as 已选 1.
- (b) `cs-05`: 我的合作 › 收到的 shows a card with the badge 「未发邀约」, the theme, the merchant and the station, plus a 「发邀约」 button. `cs-06` shows that button opening the invite page prefilled.
- (c) `cs-07`: 管理 › 现在要做 reads **发邀约** with the hint 「人民广场音乐喷泉 还没有合作单，不能开售」. `cs-08`: its button opens `merchantinfo?topicId=990015&scope=CLUB&open=merchant`, which is the 招商 layer.
- Send through the pill: `cs-10` terms sheet, then `cs-11` the page lands on 协作邀请 › 我发出的 with 待确认. `cs-12`: the layer row now reads **邀约待接受** and the pill is gone.
- Merchant accepts: `cs-13` is the inbox (合作中心 bell), `cs-14` and `cs-14b` are 协作详情 and the 接受合作 confirmation, `cs-15` shows 已接受. `cs-16`: the layer row reads **已中标**. `cs-17`: 现在要做 now reads 「去招商」 (two stations remain unclaimed, recruiting stage), no longer 发邀约.
- DB: `coop_invite 990024 status=1 to_id=9006 topic_id=990015 handle_time 14:08:46, supply_station_id 14, expire_time 2026-11-05 23:59:59`. `coop_order 1 invite_id=990024 from 990005 to 9006 merchant, SUPPLY/TOPIC 990015, GUIDED_TICKET, REGISTRATION_SCAN, status 0`.
- Cosmetic: in `cs-04` the tag squeezes the merchant name to 「探店日样…」.

## 2. N-02 merchant home with an INVITED station: **FAIL (carousel part)**; messages part PASS
- After 开始准备: `game_station_run 1 (node 71, merchant 9006) status=INVITED`. The server returns `GET /api/game/session/merchant/entries` → `[{activityId:9, …, startAt:"2026-11-05 20:00:00", stationCount:1, invitedCount:1}]`.
- `cs-25`: the first carousel card is the theme card 「进行中 · 城市定向 · 今天 20:00 · 3 人已报名」. The session station card 「活动 · 2026-11-05 20:00」 (time correctly without seconds) is **second and has no 「待接受邀请」 status**. Page data confirmed `projectCards[1] = {key:'game-station-9', meta:'2026-11-05 20:00', statusLabel:'', gameEntries:[{invitedCount:0}]}`.
- **Root cause:** `chengyinhub-xcx/utils/game-session-client.js:169-184` `normalizeMerchantEntry` rebuilds each entry from a whitelist (activityId, topicId, activityName, startAt, endAt, stationCount) and **drops `invitedCount`**. As a result `attachMerchantGameEntries` (`pages/merchant/utils/game-session-merchant.js:321,368-386`) always sees 0, so the label never shows and the card is never moved to the front. The same code is in `/home/user/hengyin` HEAD.
- 我的消息 (`cs-25b`, `cs-26`): 「站点邀请 · 「夜行定向 · 广场报时」有 1 个站点待接受邀请 · 11-05 20:00」. The first tap expands the stack, the second opens `pages/merchant/game-node/index?activityId=9` (`cs-27`, 待接受邀请 with 接受邀请 and 拒绝邀请). Cosmetic: on screen the one-line preview is cut to 「…有 1 个站…」, so the words 待接受邀请 themselves are not visible.
- 接受邀请 (`cs-28`, `cs-29` 已接受 · 待准备; STATION_ACCEPT 200). The home page afterwards (`cs-30`, `cs-30b`): the 站点邀请 message is gone. DB: station run 1 `ACCEPTED`, then `READY` (capacity 6, 20:00-23:00) after the merchant prepared it.
- Possibly a rig artifact (not verified): the product tab bar on the merchant home has no backdrop, so its icons sit over the carousel and message rows (`cs-25`, `cs-26`).

## 3. N-03 报名名册: **PARTIAL: 3 of 4 checks PASS, names FAIL**
- `cs-23` collapsed and `cs-23b` expanded. The summary reads 「已支付 3 人 · 可退款 3 人」 with **no 募集中**. Only the real session block 「11月5日 20:00 · 领队场 3 / 20」 is listed. The empty theme template ticket (oms_ticket 75, owner_type 1, 0 sign-ups) is **not shown**. Money line: 实收 ¥117.00 · 已退 ¥0.00 · 净额 ¥117.00.
- Row tap (`cs-24`) opens `pages/club/checkin-detail/index?clubId=990004&registrationId=74` (核销详情 · 周晚晴 · ¥39.00 · 清退并退款), not the profile. **PASS.**
- **FAIL: the rows read 「城瘾用户」 ×3** and not the sign-up names 周晚晴, 陆知行 and 顾南星. The default nickname does not even carry the time suffix. `/api/club/topic-registrations` returns `nickname:"城瘾用户"` for 74/75/76.
  - **Root cause:** the controller (`ApiClubController.java:1817-1819`) calls `OrganiserPersonName.pick(decryptSignupName(registration.getRealName()), nickname, memberId, registration.getCreateTime(), "报名")`. But the rows come from `viewRegistrationService.selectRegistrationSummaries`, whose SQL and result map (`chengyinhub-system/src/main/resources/mapper/business/ViewRegistrationMapper.xml:57-64,113-116`) select only `id, member_id, avatar, nickname, payment_status, verification_status`. So `realName` and `createTime` are always null.
  - Fix: add `real_name` and `create_time` to that select and result map. This is unchanged at `/home/user/hengyin` HEAD. The "fixed by another lane" claim (k3, b9df1ecd) does not hold for this endpoint.

## 4. N-04 开始活动 refusal on the director page: **PASS**
- Before the window: `cs-31a` 确认开局, then `cs-31` (START → 200 with a refusal). Red text sits directly above the bottom 开始活动 button, visible without scrolling: 「没能开始：当前不在活动服务时段（本场 11-05 20:00 至 11-05 23:59）」. Cosmetic: the line wraps inside 「11-|05」.
- Station window, after TIME SHIFT 2 (session start 14:21): `cs-32a` and `cs-32`: 「没能开始：开局条件未满足：人民广场音乐喷泉（站点服务时段：11-05 20:00 至 11-05 23:00）；上海博物馆（…11-05 20:00 至 11-05 23:59）；大世界（…）」. All times are MM-DD HH:mm with no seconds, and the text is above the button.
- Note: before teams and the merchant were ready, the tap only showed a transient toast 「还不能开始：人民广场音乐喷泉（准备项未完成）；尚无有效报名队伍」 (`director.js:1464-1468`). That is client-side readiness and not the N-04 path, so it was not captured.
- DB: `game_session 1 PREPARING revision 2`. The 20:00 session time was reverted after the test.

## 5. N-09 on sale with the merchant accepted: **PASS**
- After `TOPIC_SALE_REVIEW` was approved: `cms_topic 990015 lifecycle=3`. `cs-18`: 现在要做 reads **开一场** with a 开一场 button. Earlier stages showed 发邀约 (`cs-07`), then 去招商 (`cs-17`), then 开售审核中 at lifecycle 2 (setup).
- Observation: after the session was opened and three people had paid, 现在要做 still reads 开一场. This is not a bug claim, just a note.

## 6. Plain taps on pages/topic/index/index?id=990015&via=session: **PASS (taps fire)**
- As k4cs-m1, m2 and m3, with no handler calls. `cs-19` hero, then a tap on 查看详情与报名 (`cs-19b`), then a tap on 立即报名, which opened the ticket sheet with 「2026年11月5日(周四) · 20:00 集合 · 领队场 ¥39」 (`cs-20`). Then tap 领队场 (`cs-21`), then tap 「下一步 · 去结算」, which navigated to `pages/activity/baoming/baoming` (`cs-22`).
- All three members went through this way (TAP1_REACHED true, TAP2 navigated, 3 of 3) and paid: registrations 74-76 with payment_status 2 and registration_status 2.
