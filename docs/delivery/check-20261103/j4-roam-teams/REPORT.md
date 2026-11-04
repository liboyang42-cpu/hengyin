# j4-roam-teams: first pass

I tested commit `ecef2f078ae3cb53fa41621c71d5fea3cf9124e4` on branch `claude/check-1104-j4-roam-teams`, on 2026-11-04.

**These results come from the rig, not a phone.** The rig runs the real jar, MySQL 8 and Redis 7, and the real mini-program code in headless Chrome at 390×844 with 2× screenshots. WeChat login, payment, share sheets, Tencent's map (a canvas stand-in draws the markers, lines and callouts) and the Tencent key are rig stand-ins. As asked, the stand-in map and the unbound map styles are not reported.

## Setup

- **Rig.** I used `claude/cloud-walk-20261101-rig`, plus m2's `m2-rig-standin.diff` (which includes the club-lane template patch) and j6's dummy OSS values. `up.sh` was pinned to `ecef2f07`, with `LANG=C.UTF-8` and the Docker mirror `mirror.gcr.io`. Full diff: `tools/rig-changes.diff`.
- **My own rig changes** (all HARNESS, none in product code):
  - 2× shots under 200 KB.
  - A `font` option that sets `fontSizeSetting`, and a `locdeny` option that makes location fail.
  - `wx.getRandomValues`, which the rig lacked. Without it every team command hung at 「加入中…」 with no request sent.
  - The browser runs in `TZ=Asia/Shanghai`, as a phone in China would.
- **Data.** `chains.py player` passed all 4 chains (topic 990014, activities 9–13).
  - Players `walk-player-1..4` (990005–990008) were made by real login.
  - 9004 and walk-players 1–3 joined club 7002 through the real API. Each signed up for session 9 with real quote and create, then paid through the rig pay seam, which calls the real callback. walk-player-4 (990008) signed up but did not pay.
- **Fixture writes (test DB only):**
  - `cms_topic` 990014 `team_mode` 0→1. Without it, `/api/team/create` answers 「该主题未开放组队」. An author sets this in editor step 3.
  - `product_type` 2→1, and the 4 stations moved near People's Square in `cms_topic_node`, `published_route_graph_json` and `cms_registration_scan`. This is the same as m2 did: shop-day mode has no story map.
  - Session 9 moved to "started" with `seed-walk.py shift-session` before play.
  - 30 places (`roam_poi`, `description='j4-fixture'`), 13 with covers from repo art plus approved `app_media_asset` rows. Three are partner-shop nodes (merchants 8001–8003). See `tools/poi.sql` and `tools/move.sql`.
  - The app was restarted with `APP_MEDIA_PUBLIC_BASE_URL=https://www.chengyinhub.com/prod-api`, as m2 did. With a `127.0.0.1` base, `getImgUrl` drops untrusted hosts and every photo pin turned into an icon (HARNESS).
- **Map seams** (the same as m2 and m4):
  - A pinch is simulated by calling `onRegionChange({type:'end', causedBy:'scale', …})`.
  - For the photo-pin shots, places still under fog were set to `seen`. Walking reveals only places within 55 m.
  - A marker tap is `onMarkerTap({markerId})`.
  - `<page-meta page-style>` is not implemented in the rig. For the largest-font shots I applied the page's own `fontScaleStyle` to the page host.
- **People.** A = 9004 「玩家小城」, leader of team 1. B = walk-player-1 (990005). C = walk-player-2 (990006), leader of team 2. D = walk-player-3 (990007). Leader 9008 runs the club session.

## 1. Steps

| # | Step | Result | Shots | Read-back |
|---|---|---|---|---|
| T1 | A: 我的订单 → 查看详情 → 和队友一起出发 → 叫上队友 → 2 人队伍 → 确定 | PASS (see F7 and F9 for text) | 01–05 | `play_team` 1: leader 9004, max 2, public, status 0 |
| T2 | A's share payload | PASS: `/pages/team/join/index?code=…` | — | `onShareAppMessage` output |
| T3 | B opens the invite and taps 接受邀请并加入 | PASS, but see F1 and F6 | 06, 07 | `play_team_member` 990005 status 1; team 2/2, status 1 |
| T4 | Full team: C opens the same invite | PASS: 「队伍已满」 button, disabled | 08 | — |
| T5 | Wrong code: C opens `?code=WRONG…` | FAIL F2: blank page | 09b | `/api/team/info` → 「邀请已失效」, never shown |
| T6 | Other user's team by id: C opens `team/detail?teamId=1` | Data: PASS. Screen: FAIL F2 (blank) | 10 | 「无权查看该队伍」 |
| T7 | B leaves: 退出队伍 → confirm | Data: PASS. Screen: FAIL F3 | 11–13 | member status 2; team 1/2, status 0 |
| T8 | B rejoins with the old link | PASS, but the quit dialog said this needs a new invite (F8) | — | status back to 1 |
| T9 | A removes B: 移出 → 移出队员 | PASS | 14–16 | member status 3; team 1/2 |
| T10 | Removed B tries the link again | Data: PASS (refused). Screen: FAIL F1 (jargon and raw id) | 17 | rejected command, no row change |
| T11 | Backend id-swap matrix as C, A, guest, other-session 9009 and unpaid 990008 (info, applications, kick, disband, join-mode, quit, handle, all five through `/api/team/command`, command query and cancel with someone else's request id, orient detail, nearby, apply, join) | PASS: every cross-team read and write refused; DB unchanged | — | `proof/team-idor-http.txt`. See F10 for the invite-code read |
| T12 | D: 我的队伍 (empty) → 附近招募 → team on map → 申请加入 → 提交申请 | PASS, but see F1 | 18–22 | member 990007 status 0 (pending) |
| T13 | Leader C finds the application | FAIL F4: not on 我的队伍 or team detail; only through the map sheet | 23–27 | — |
| T14 | C approves on the map sheet (同意) | PASS, but 「同意成员 990007」 and a raw id (F1) | 28, 29 | member status 1; team 2/3 |
| T15 | Ready: A and C each tap 我到了 → 定位并签到 | PASS: 1/5, then 2/5 | 59a, 59 | `team-progress` arrived count |
| T16 | Start: leader 9008 starts the trip over the API (the director page is j2's) | PASS: both players switch to 「带领中 · 2/5 人」 | 60 | `/api/club/lead/start` 「出发!」 |
| R1 | Consent sheet 「同场路线」 (default on, full-width 开始) | PASS | 57, 58 | `/api/team/trail/consent` 200 |
| R2 | Same-session team routes, two real accounts in two browsers at once, **no shim**, about 60 s of walking | PASS: A and C each sent 7 syncs, all HTTP 200 / code 200. Each sees the other team's green glow line, avatar pin and rail | 61-A, 61-C | Redis `team_trail:pts:9` fields 9004 and 990006 |
| R3 | Negative cases: other-session player 9009, unpaid 990008, guest, and 9004 asking about a session it is not in | PASS: `available:false` or 401; nothing stored | — | `proof/team-trail-http.txt`; Redis unchanged |
| R4 | C opts out (`share:false`) | PASS: C's points are removed at once; A's next sync gets `teams:[]`; C's later sync stores nothing | — | `opt:9` 990006 "0" |
| R5 | A ends the run (real 3 s hold handler) → `finished:true` | PASS in 2 of 2 clean runs: A's field removed | 62–65 | request log shows the `finished` sync; `HKEYS` empty |
| R6 | Route share card: draws, settles, big number, theme, time | PASS for what the rig can show. Theme code, city and album save NOT VERIFIED | 63, 64 | `/api/topic/share-code` 200, but WeChat is unreachable |
| M1 | 漫游 entry as a player | PASS, see F5 and F9 | 30 | — |
| M2 | Start roam → location consent → map, dots when zoomed out with 「附近 30 个地点」 | PASS | 31, 32, 35, 38 | `/api/roam/pois`, `/api/map/weather` → `clear` |
| M3 | Photo pins at scale 16 and 17; partner-shop name stickers | PASS: 8–9 photo callouts, no overlaps, 2 shop stickers | 36, 37 | — |
| M4 | Place card from a pin: photo, address, distance, 导航, 分享 | PASS. 导航 calls `wx.openLocation` with the place's coordinates, name and address | 39 | `/api/city/nodes/7` |
| M5 | 分享 lands a guest on the place | PASS: the place loads for a guest. See F12 | 41, 42 | `/api/city/nodes/7` 200 as guest |
| M6 | 确认到点 on a platform place (the ROUND8 lead) | Over HTTP: PASS. On screen: FAIL F11 | 33-after-walk, 67-place-confirm-arrival | `/api/roam/poi/discover` → 「发现新地点」 xp 10; `roam_poi_found` row 1 (9004, poi 23, session 40) |
| M7 | City list: 上海 → 鲁迅公园 → tap the address | List PASS. Address tap sends `/api/map/geocode`; no key in the rig, so nothing opens (NOT VERIFIED) | 43–46 | — |
| M8 | Location refused | Map: PASS (「定位未开启 · 点击重试」). Entry: FAIL F5 | 47–49 | entry requests use the fixed 31.230416, 121.473701 |
| M9 | Guest on 漫游: entry → 开始漫游 → 登录后继续 → 微信登录 | PASS: after login the roam continues to the location consent. See F13 | 50–52 | `/api/login/code` |
| M10 | Bubble text at normal and largest phone font (`fontSizeSetting` 22, scale 1.3) | Place card scales: PASS. Map stickers and entry cards don't: F14 | 53, 54, 36–38 large-font | `--cy-type-scale` 1.3 on the page |
| M11 | The lead 「anonymous `GET /api/city/nodes` returns 401」 | Explained: the owner opened only `GET /api/city/nodes/{id}` to guests (m4, `SecurityConfig.java:168-170`, `PublicReadEndpointsSecurityChainTest:124`). The list stays closed by decision. The guest 漫游 entry does not call it. | — | — |

## 2. Findings

### F1 HIGH: every team action ends on a developer recovery card with a raw request id

- **Repro (team detail and join pages):** B accepts an invite (07), leaves (13), or A removes B (16). The top of the page shows 「接受邀请入队原请求已执行原请求号：c7ea2558…」 with a 确认结果 button. The three strings run together with no spacing.
- **Repro (refused):** when a removed member tries the link again, the card says 「原请求被拒绝，请重新读取业务状态」 with the raw id (17). The real reason (「暂时无法加入该队伍」) is never shown, and 接受邀请并加入 stays active.
- **Repro (map, `subpackageRoam/nearby`):** after applying (22) or approving (29), a panel shows 「申请入队 · 队伍 2」 or 「同意成员 990007」, 「原请求已执行」, 「请求号 d44d9c66…」, 「确认并归档结果」 and 「这是原请求的结果，当前队伍状态以重新读取的列表为准。」. It shows a raw team id and a raw member id, and covers the team's status card.
- **Code:** `pages/team/join/index.wxml:2-13`, `pages/team/detail/index.wxml:5-17`, `utils/team-event-command.js:14-17` (`recoveryShown` is true for every terminal state), and `subpackageRoam/utils/roam-team-recovery.js:32`.
- **Layout fix:** on APPLIED, show the new state (「已加入」, the updated member list) and archive the receipt silently. On REJECTED, show the backend reason inline at the button. Keep the recovery card only for UNKNOWN.

### F2 HIGH: a wrong or expired invite code, or another user's team by id, shows a blank page

- **Repro:** open `pages/team/join/index?code=WRONGcode000000000000a` (09b), or, as C, open `pages/team/detail/index?teamId=1` (10). After the title the page is blank: no reason, no button, and no automatic back. The backend answered 「邀请已失效」 and 「无权查看该队伍」.
- **Cause:** the pages file a business refusal as `errorKind:'data'` (`pages/team/join/index.js:130-131`, `pages/team/detail/index.js:187`). `cy-state-shell` deliberately leaves `data` failures blank (`components/cy/state-shell/index.js:20`).
- **Fix:** use a refusal kind such as `no-permission` or `missing-param` for these answers, so the reason and an exit show. The blank rule for real load failures stays as is.
- No data leaked: the backend refused both (T11).

### F3 HIGH: after leaving a team, the page says 「已满员 2/2」 above 「还没有队员」

- **Repro:** B: team detail → 退出队伍 → confirm (13).
- **What's wrong:** the header keeps the stale 已满员 and 2/2, and the member list shows an empty-state illustration. The DB says 1/2, recruiting.
- **Fix:** once the person is no longer a member, leave or replace the page (back to 我的队伍) instead of re-reading a team they can no longer see.

### F4 HIGH: a leader cannot find pending applications from 我的队伍 or team detail

- **Repro:** D applies to C's public team (22). C opens 我的队伍: the card shows 「已组 1/3 · 进入队伍」 with no pending count (23). Team detail has no application section (24, 25).
- **Only route:** open the map, then open their own team's sheet. There the applicants appear (28). C has to know to do that.
- **Code:** `/api/team/applications` is called only from `subpackageRoam/nearby/index.js:661`.
- **Fix:** show the pending count on the 我的队伍 card and an 申请 section on team detail, both reusing the map sheet's rows.

### F5 HIGH: with location refused, 漫游 shows 「距你 0m」 measured from a fixed Shanghai point

- **Repro:** refuse location, then open 漫游 (47). Every activity card says 「距你 0m」, and the player avatar sits at People's Square. Nothing says the location is unknown.
- **Cause:** `utils/location/last-location.js:8` falls back to 31.230416, 121.473701. Its own comment says pages should show 「未获取定位」 when `located=false`, but the entry does not.
- **Effect:** a user in another city sees distances from People's Square.
- **Fix:** without a real fix, drop the 「距你」 part and the avatar, or show a single 「开启定位」 row.

### F6 HIGH: each session card says 「2 支队伍招募」 for every session of the theme

- **Repro:** 漫游 entry (30). Sessions 9–13 of topic 990014, and the topic card, all say 「距你 0m · 2 支队伍招募」. Both teams belong to session 9 only.
- **Cause:** `utils/roam-entry-map.js:17-21` `teamsFor` matches teams by `topicId` first. The same function also sets `registered`.
- **Effect:** a player picks session 12 for its "recruiting teams", but those teams need a ticket to session 9.
- **Fix:** for `kind:'activity'` rows, match only `team.activityId`.

### F7 HIGH: on the story map, 「下一站 · Xm」 and the compass distance do not change while walking

- **Repro:** A starts at 159 m from station 1 and walks onto it (12 location updates over 60 s). The arrow is drawn on the station, but the card still says 「下一站 · 159m」, and so does the compass (61-A, 60).
- **Cause:** `storyDistText` is computed only in `rebuild()` (`pages/play/index.js:4675`). The location callback updates only `fmPlayer` (`pages/play/index.js:~5030`).
- **Fix:** recompute the distance text in the same throttled location tick.
- A phone must confirm, but the code path is the same.

### F8 HIGH: ending the run with no station done shows 「本章完成」 and a big 「0 点亮坐标」

- **Repro:** start the run, walk, then hold 暂停这一局 for 3 s without reaching a station (65).
- **What's wrong:** the finish sheet says 「第1章 咖啡 · 本章完成」, a giant 0 and 「你的第一个主题正在路上 / 距 1 个还差 1 个」. The chapter was not completed.
- **Other issues:**
  - When scrolled, the card title slides under the status bar and capsule.
  - The fixed 继续 button covers the 「第1章 咖啡」 heading (64), as m2 noted.
- **Fix:** label it 「已结束」 and hide the count when it is 0.

### F9 LOW: taglines, helper lines and secondary labels (owner rule), with the layout change for each

- **Order detail team card:** 「组队与否不影响活动举行，也不改变订单与退款规则。」 (03). Drop it; the card title and 叫上队友 row are enough.
- **Join page** (06):
  - 「邀请码不能用于签到或核销，入队不代替报名或购票」 and 「入队后自动进群聊」. Drop both.
  - 「加入方式 邀请制」 is shown even for public teams (team 1 is 公开招募). That is wrong data, not only a label: drop the cell or bind it to `joinMode`.
- **Team detail** (05):
  - 「公开招募」 appears twice: as the sub-title and as the switch row.
  - 「陌生人可在附近队伍里申请加入」 under the switch.
  - 「退队不退款，退票请到订单」 at the bottom.
  - Keep one 公开招募 switch with no caption.
- **Quit dialog** (12):
  - It shows 「你是队长的话…」 to a member.
  - 「此操作不可撤销,需要重新被邀请才能回到这支队伍」 is false: B rejoined with the same link (T8).
  - It mixes half-width 「,」 and 「;」 into Chinese.
  - Show only the lines that apply to this person.
- **漫游 entry** (30):
  - 「走到哪，雾散到哪」 and the English 「城瘾 · CITY ROAM」 on every pass card.
  - 「已加入 · 申请中」 on the 我的队伍 card, even for a guest with no team.
- **Roam map:** 「继续走 · 点亮这片街区」 above the stats (32).
- **City list** (43, 45):
  - 「切换城市 · 3 处」.
  - The per-place reason line under each name.
  - 「复制地址，在常用地图中查找」 as a button label, now that the address itself opens the map. Use 「复制地址」.
- **Guest place page:** 「完成据点挑战后可以评分和评论。」 (42).
- **Not findings:** the location consent text (31, 59a) and 「公开资料交叉核对，未实地核验…」 (the source disclaimer, 45) are consent and legal text.

### F10 LOW: a forwarded invite code returns member ids and the code itself to anyone

- **Repro:** as a guest or as a player from another session, `POST /api/team/info {"inviteCode":…}` returns `members[].memberId`, `team.leaderMemberId`, `team.inviteCode`, `ownerId` and `createTime` (`proof/team-idor-http.txt`).
- The client never shows them (`subpackageRoam/utils/map-team.js:4`), but they are on the wire.
- Holding the link is the authorisation, so this is not a leak to strangers. But ROUND9 asks for "no ids beyond what the UI needs".
- **Fix:** project only title, counts, status, names and avatars when the reader is not a member.

### F11 HIGH: 「确认到点」 on a platform place succeeds but the card does not change

- **Repro:** walk next to 钢琴博物馆; its card appears with 「靠近后可确认地点 · 确认到点」 (33-after-walk). Tap 确认到点.
- **What happens:** the server answers 「发现新地点」 (+10) and writes `roam_poi_found`. Three seconds later the card is identical, still offering 确认到点 (67-place-confirm-arrival). No toast was seen in the shot.
- **Fix:** turn the button into the done state (「已到达 +10」) in place.
- **Also on this card:** it is a white card on the dark player map (33-after-walk, and the shop card in 66). The neighbouring place card (39) is dark. Distance units differ: 「61M」 (66) against 「155 米」 (39).

### F12 LOW: guest place page shows 「暂无评分，等你来体验」 and 「评价暂时无法读取 · 重试」 together

- **Repro:** a guest opens the shared place (42).
- `/api/comment/list` for this node answers 「据点未开放或已下线」 for guests and members alike. That may be because my fixture node is not a published template node, so this may be fixture-only.
- The two lines still contradict each other. When reviews can't load, keep only the retry.

### F13 LOW: after logging in from the 漫游 prompt, 附近活动 still says 「登录后查看附近活动与队伍 · 去登录」

- **Repro:** start 漫游 as a guest → 登录后继续 → 微信登录. The roam carries on, but the section behind it still shows the guest text (52).
- **Fix:** reload the entry lists on login.

### F14 LOW: the largest phone font barely changes 漫游

- **What:** with `fontSizeSetting` 22 (scale 1.3), only the 开始漫游 / 漫游护照 tabs and the place card grow (53, 54). The pass cards, the activity cards, the stat card and the shop name stickers on the map stay at base size (37-large-font against 37).
- The stickers are page `cover-view` text, so they could follow `--cy-type-scale`.
- A phone must confirm, but the CSS is the same.

### F15 LOW: two untitled teams read the same in lists

- **Where:** 我的队伍 and 附近招募 show 「一起出发的玩家队伍」 for every team without a title (18, 19). Only the 1/2 against 1/3 count tells them apart.
- `leaderName` is in the response.
- **Fix:** title untitled teams 「<队长昵称>的队伍」.

### F16 LOW: the team pin and rail show a blank black disc when the player has no avatar

- **Where:** story map (61-A, 61-C). No initial, no name, so a second team can't be told apart.
- **Also:** C's view shows A's team pin on top of station 1, hiding the 「1」, as m2 noted.

### F17 LOW (outside this lane, seen in passing): the order list says 「进行中」 for a session 10 days away

- **Repro:** 我的订单 for session 9 on 11.14, opened on 11.04 (01). It shows a green 「进行中」 badge, although a 「未开始」 tab exists.
- I did not dig further. This is for j1.

### HARNESS and fixture (not product)

- The rig lacked `wx.getRandomValues`, so team commands hung until I added it.
- The rig has no `<page-meta>` support.
- My `locdeny` stub rejects promises, which shows as rig page errors in 49.
- Rig MySQL runs in UTC, so 「8 小时前申请」 (28) and DB `createTime` values are 8 h off.
- Photos needed a trusted media base (see Setup).
- The topic cover goes to `oss-rig.invalid`, so it never loads.
- WeChat is unreachable: no theme or shop code on cards, no share sheet.
- There is no Tencent key: no geocode, no city name on the route card, and the weather answers `clear`.
- My fixture node has no reviews (F12).

## 3. Not covered in this pass

- **Map, not driven:**
  - Following a team by tapping the rail.
  - The author's 「同场路线」 off switch (`teamTrail:false`).
  - The swipe card row under 漫游 and fly-to.
  - The drag cloud layer.
  - Off-screen pins shrinking to the edge.
  - The atmosphere-entry video, which is not delivered yet.
  - Visible rain, snow or fog (the weather answered `clear`).
  - The 3D/zoom animation.
- **Phone only:**
  - Real gestures.
  - `translateMarker` smoothness.
  - Polling on cellular and pausing in the background.
  - The share sheets.
  - Album save of the route card.
  - The theme and shop mini-program codes.
  - `wx.openLocation` actually opening.
  - Geocoded address taps.
  - Real fonts at largest size.
- **Teams:**
  - Rejecting an application, withdrawing one, and disbanding a team.
  - Switching join mode from team detail.
  - The team IM group.
  - Expiry of teams at the session start.
  - A team of 3–4 filling up through applications.
- **Process:** the director page itself (j2). I started the trip over the API.
- **Accounts:** on a phone, two accounts would be two phones. I used two browsers at the same time, both real logins, with no request shim.

STATUS: PARTIAL - first pass; the map items and team actions listed in section 3 are still to walk, and everything marked phone-only needs a device.
