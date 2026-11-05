# j4-roam-teams: second pass

I tested commit `573c463b3b457b77544e63be4271a07d5d565e09` on branch `claude/check-1105-j4-roam-teams`, on 2026-11-05. First-pass report: `claude/check-1104-j4-roam-teams` (tested `ecef2f07`). Fix lane: `integ/ROUND11-G2-RESULT.md`.

**These results come from the rig, not a phone.** The rig runs the real jar, MySQL 8 and Redis 7, and the real mini-program code in headless Chrome at 390×844 with 2× screenshots. WeChat login, payment, share sheets, gestures and Tencent's map are rig stand-ins. The map is a canvas stand-in, and the real map styles are not bound; as asked, neither is reported.

## Setup

- **Rig.** I used `claude/cloud-walk-20261101-rig` plus the first pass's rig patch, pinned to `573c463b`, with `LANG=C.UTF-8` and Docker images pulled through `mirror.gcr.io`. Full diff: `tools/rig-changes.diff`.
- **New harness changes this pass** (all rig, none in product code):
  - **Weather stub.** Only the outbound Tencent call is stubbed:
    - `apis.map.qq.com` points to a local HTTPS server (`tools/weather-stub.py`), with its own certificate in a trust store given to the JVM.
    - The app runs with `tencent.map.enabled=true` and a dummy key, so the real `TencentMapClient`, `MapWeatherServiceImpl.parse`, the Redis cache and the rate limit all run.
    - The reply follows Tencent's `ws/weather/v1` shape (`result.realtime[0].infos.weather`), which is what the code parses. Every call it received is in `proof/weather-stub-calls.log`.
  - **Fly-to.** The rig's `MapContext.moveToLocation` was a no-op. It now records each call and moves the stand-in's centre.
  - **Style tones.** The stand-in's base colour is tinted by `layer-style`, so overlay contrast can be judged. For that walk only, the bundle was built from a copy filled by the product's own `scripts/release-map-style.js` with a dummy key (black 1, white 2, paper 3, 2077 4). The repo file stays `subkey: ''`.
- **Data.** `chains.py player` passed all 4 chains (topic 990014).
  - Players `walk-player-1..4` (990005–990008) were made by real login.
  - 9004 and walk-players 1–4 joined club 7002 through the real API and signed up for session 9 with real quote and create.
  - All but 990008 paid through the rig pay seam, which calls the real callback. 990008 stays unpaid.
  - 9009 holds a ticket for session 13 (from the chain) and, later, session 10.
- **Fixture writes (test DB and Redis only):**
  - The first pass's `team_mode` 0→1, station move and 30 places (`tools/poi.sql`, `tools/move.sql`). Photos are repo art copied into the rig's upload folder.
  - Session 9 start was moved with `seed-walk.py shift-session` several times:
    - into the future to form teams (public teams can only be made before the start);
    - to "started" to play.
  - One official route (`preset_route` 4 with 3 stops; one stop has no coordinates).
  - `cms_topic.look_json` set to PAPER, CYBER and STARDUST for the tone walk, then reset.
  - The author's switch was copied into `published_theme_runtime_config_json` as `{"teamTrail":false}`, then reset. A re-publish approval would do this copy.
  - Weather cache and rate-limit keys were cleared in Redis between weather states.
- **People.**
  - A = 9004 「玩家小城」, leader of team 2.
  - B = 990005, A's member.
  - C = 990006, leader of public team 1.
  - D = 990007, applicant.
  - 9003 is the theme's author.
  - 9008 runs the club session. I started the trip over the API, as in the first pass; the director page is j2's.

## 1. First-pass findings: is each one really gone?

| ID | Gone? | Evidence |
|---|---|---|
| F1 recovery card with raw request id | **Gone** | Accept (05), apply (11), withdraw (14), reject (18), quit (25), disband (21, 22): no card, no request or member number. A refused join shows the backend reason: D's old link after rejection got 「暂时无法加入该队伍」 over HTTP. |
| F2 blank page on wrong code or someone else's team | **Gone** | 06 「邀请已失效」 + 返回上一页; 07 「无权查看该队伍」 + 返回上一页. |
| F3 「已满员 2/2」 above an empty list after leaving | **Gone** | B leaves and lands on 我的队伍 (25, 26). DB: member status 2, team 1/2. B rejoined with the same link afterwards. |
| F4 leader can't find applications | **Gone** | 我的队伍 card 「· 1 条入队申请」 (15); team detail 入队申请 section with 拒绝 / 同意 (16). |
| F5 「距你 0m」 from a fixed point without location | **Gone** | Location refused: no distance, no avatar, one 开启定位 (72). After 开启定位: real distances (74). |
| F6 every session counted the theme's teams | **Gone** | Only session 9's card says 「1 支队伍招募」 (75). |
| F7 「下一站」 distance frozen while walking | **Gone** | A walking to station 1: 113m → 68m → 23m (`proof/two-accounts-run.log`). |
| F8 「本章完成」 and a giant 0 after ending with no station | **Mostly gone** | Now 「已结束」, no count (67, 67b). Still open: the fixed 继续 button sits on top of a row of four round items (67b). |
| F9 captions | **Mostly gone** | Join page (04), team detail (03), member quit dialog (24), entry pass cards (30), and city list (91, 93 「复制地址」) are clean. Two lines the g2 lane left for the owner are not re-reported. New captions are in N7. |
| F10 invite read leaks ids | **Gone** | Guest and other-session reads return only id, title, status, joinMode, counts, names, avatars and roles. Members still get the full team (`proof/f10-team-info.txt`). |
| F11 「确认到点」 succeeds but the card doesn't change | **Gone** | The button turns into 「已到达」 with toast 「地点已确认」. The card is now dark (50–52). DB: `roam_poi_found` (9004, poi 23). |
| F12 「暂无评分」 next to a load failure | **Gone** | Guest place page shows only 「评价暂时无法读取 · 重试」 (83). |
| F13 guest text stays after logging in from 漫游 | **Gone** | After 微信登录 the entry reloads; 「登录后查看…」 is gone (88 → 90). |
| F14 largest font barely changes 漫游 | **Still there** | `--cy-type-scale` 1.3: only the 开始漫游 / 漫游护照 tabs grow. Activity cards, the stat card and the shop stickers stay at base size (75 against 76; 45 against 77). |
| F15 untitled teams read the same | **Still there** | 「一起出发的玩家队伍」 twice in 附近在招募, told apart only by 1/3 against 1/2 (26). |
| F16 blank black disc for a team with no avatar, and pin over station number | **Still there** | Rail and pin are a black disc with a coloured ring (61-routes-storymap-A, 96-tone-paper). In C's view, A's pin hides station 1's number (61-routes-storymap-C). |
| F17 「进行中」 for a future session | **Gone** | Order list and detail show 「未开始」 for session 10 on 11.16 (94, 95). |

## 2. New walks: every step

| # | Step | Result | Shots | Read-back |
|---|---|---|---|---|
| T1 | C creates a 3-person public team from order detail | PASS | 01, 02, 03 | `play_team` 1: leader 990006, joinMode 2, max 3 |
| T2 | A creates a 2-person team; B accepts the invite | PASS | 04, 05 | team 2: 2/2, status 1 |
| T3 | Wrong code; another user's team by id | PASS | 06, 07 | — |
| T4 | D: 我的队伍 → 附近招募 → 在地图上看 → 申请加入 → 提交申请 | PASS | 08–11 | member 990007 status 0 |
| T5 | **Withdraw:** D: 我的队伍 → 撤回申请 → 撤回 | PASS | 12–14 | status 5; the team shows again as 可申请 |
| T6 | D applies again | PASS | — | same row back to status 0 |
| T7 | **Reject:** C: 我的队伍 (count) → 进入队伍 → 拒绝 → 拒绝申请 | PASS, see N8 for the dialog | 15–18 | status 4. D's 我的队伍 shows 未通过 · 不能再申请这支队伍 (19). The team is gone from D's 附近招募. Re-apply over HTTP: 「队长未同意，不能再次申请这支队伍」 (`APPLY_REJECTED`). The old invite link is refused too. |
| T8 | **Disband an empty team:** D creates a team → 解散队伍 → confirm | PASS, see N8 | 20–22 | team 3 status 4; member status 2 |
| T9 | B leaves A's team, then rejoins with the same link | PASS | 23–26 | member status 2, then 1 |
| T10 | **Disband with a member, during the session:** A → team detail → 解散队伍 → confirm | PASS | 71 | team 2 status 4; both members status 2 |
| R1 | Consent sheet 「同场路线」, both accounts (default on, full-width 开始) | PASS | 60-consent-A | `/api/team/trail/consent` 200 |
| R2 | Same-session routes, A and C in two browsers at once, real logins, **no shim**, about 75 s of walking | PASS: A sent 8 syncs and C 7, all 200. Each sees the other's line, pin and rail item. | 61-routes-storymap-A, -C | Redis `team_trail:pts:9` holds fields 9004 and 990006 |
| R3 | **Follow a team from the rail:** A taps C's rail item, C keeps walking, A taps again | PASS. Tap: `teamFollowKey` set and the map centres on C's last point. After C moves, the centre follows C's new point (31.2309,121.4721 → 31.2310,121.4719). Second tap: follow off. | 62, 63, 64 | log lines 「A follow」 |
| R4 | C ends the run with no station done (real toggle, then the 3 s hold handler) | PASS for the label (F8). C's points are removed on finish. | 65, 66, 67, 67b | HKEYS after: only 9004 |
| R5 | **Author's 同场路线 off switch,** as author 9003: editor → 主题详情 → 同场路线 | Switch: PASS. The draft runtime config gets `"teamTrail":false` (54 on, 55 off). | 54, 55 | `formData.themeRuntimeConfig` |
| R6 | Effect of the author's switch once published (fixture copy, see Setup) | PASS: A's next sync returns `available:false`, polling stops, and rail and pins clear (70). C reopening gets no consent sheet. Stored points are not deleted (N10). | 69, 70 | `state()` `{available:false}` |
| R7 | Negative cases at the commit: other-session 9009, unpaid 990008, guest, 9004 on session 13, and D opting out | PASS. The other-session, unpaid and session-13 reads get `available:false`. The guest gets 401. The opted-out D stores nothing and is not served. A receives only key, done, name, avatar and points; no ids. | — | `proof/team-trail-negative.txt` |
| M1 | **Rain** with stubbed 中雨 → 0.6 | PASS: thin streaks move between frames and sit under the cards and buttons | 31, 31b | `/api/map/weather` → `{kind:rain,intensity:…}`. Stub saw `location=31.225,121.475`, the cell centre, not the player's position. |
| M2 | **Snow** with stubbed 大雪 | PASS, but see N3 | 32 | — |
| M3 | **Fog** with stubbed 浓雾 | PASS: still grey veil, no animation | 33 | — |
| M4 | Weather stops when hidden: open 我的队伍 over 漫游, then come back | PASS: `active` false and `raf` 0 while hidden; restarts on return | — | component state read in the page |
| M5 | Weather and clouds off under reduced motion: 更多 → 设置 → 减少动态效果 | PASS: weather canvas removed, `raf` 0; the cloud layer is gated on the same flag | 98–100 | — |
| M6 | Weather rate limit: 70 calls in a minute from one IP | PASS: after 20, `「访问过于频繁，请稍候再试」`. The client draws nothing and shows nothing. | — | — |
| M7 | **Drag clouds:** gesture begin, then end with the camera ~350 m east | PASS: the cloud layer brightens on begin (`cloudOn`), then drifts −120 px (0.35× the move, clamped) and settles | 40–43 | `cloudStyle` `translate3d(-120px,0,0)` |
| M8 | **Off-screen pins:** same drag at scale 17 | PASS for behaviour: near pins outside the view shrink to 26 or 10 px and clamp to the edge; far ones are hidden (25 → 18 markers); dragging back restores every position. See N2 for where they land. | 40, 42, 43, 44 | marker lists in the run output |
| M9 | **Swipe cards and fly-to:** the card row under 漫游 | Row opened through its own handler (N1: no normal entry). Swipe 1→2→3: each swipe calls `moveToLocation` with that shop's coordinates, centres on it and enlarges its pin to 60 px. Tapping card 1's pin sets the row back to card 1. 去这儿 opens that shop's detail. | 45–49 | `MPWEB.moves`: 4 calls with the shops' coordinates |
| M10 | Row scrolls to the tapped pin's card | NOT VERIFIED: the data index changes, but the rig's swiper lags one card when its `current` is set from code (48). Needs a phone. | 48 | — |
| G1 | **Guest opens a shared place** (`poi-detail?poiId=7`, the 分享 path) | PASS: place loads; 收藏 asks to log in (84) | 82–85 | `/api/city/nodes/7` 200 as guest |
| G2 | **Guest opens official routes:** list → 人民广场慢行 → tap a stop | PASS: list and detail load. A stop with coordinates calls `wx.openLocation` with them; the stop without coordinates opens nothing. Share path `/subpackageRoam/official-route/index?id=4`. | 78–81 | Unlisted route (status 0): 「路线不存在」 for a guest. Guest list uses the public projection. |
| G3 | Guest on 漫游: 开始漫游 → 登录后继续 → 微信登录 → 暂不开启 | PASS (F13) | 87–90 | `/api/login/code` then `/api/topic/list` |
| S1 | **Four style tones over the stand-in,** with the bundle filled by the release script | black (default, STARDUST): white route and trail, layer 1. paper: layer 3; the route, the player's own trail and the station rings turn dark brown; team colour lines keep their colour. 2077 (CYBER): layer 4, white route. All read clearly. | 96-tone-default, -paper, -2077, -stardust | `fmLayerStyle` 1/3/4/1, `fmTone` black/paper/2077 |
| S2 | white tone on a merchant map (`pages/searchmap` as merchant 9002) | Layer 2 is chosen. See N6 for the controls. | 97 | `layerStyle` 2 |
| S3 | Entry at the largest phone font | FAIL (F14 still there) | 76, 77 | `--cy-type-scale` 1.3 |

## 3. New findings

### N1 HIGH: the swipe card row can only be opened in areas with no roam places

- **Repro:** start 漫游 anywhere that has roam places (the fixture has 30). The goal line reads 「继续走 · 点亮这片街区」. Nothing on the map, in 更多, or on the passport opens the card row.
- **Where it can open:**
  - `onGoalTap` with `act === 'discover'` (`pages/roam/index.js:4056`).
  - `buildRoamGoal` only produces that when `poiEmpty`, that is when the area has **no** roam places (`utils/play-state-contract.js:230-238`).
  - The passport card 「发现附近店铺」 goes to `subpackageRoam/nearby` instead (`pages/roam/index.js:864-866`).
  - `goDiscover` and `openRoamDiscover` have no other callers.
- **Effect:** the ROUND9 m-fly row (swipe = fly to the shop, tap a pin = scroll to its card) is missing wherever the map already has places, which is where shops are. When the row did open (through its own handler), it worked: M9.
- **Layout fix:** let the shop photo pins or the 「附近 N 个地点」 chip open the row, rather than adding a caption.

### N2 LOW: off-screen edge pins sit under the status bar, the capsule and the bottom buttons

- **Repro:** zoom to 17 and drag (40, 42, 45). Clamped pins appear:
  - over the clock and next to the capsule at the top;
  - behind 更多 / 暂停 / 拍照 and the stat card at the bottom, where they can't be seen at all;
  - half off the left edge.
- **Cause:** `pages/roam/photo-pins.js:8,41-45` clamps to a uniform 6% inset of the whole map box, not to the area between the search bar and the bottom card.
- **Fix:** clamp to the visible map area (below the search bar, above the card or row).
- The positions come from page code, not the stand-in. A phone should still confirm.

### N3 LOW: snowflakes look like the zoomed-out place dots

- **Repro:** stubbed 大雪 at the default zoom (32). The snow dots are white and the same size as the real place dots, so the 30 places can't be picked out.
- **Fix:** draw snow softer or larger and lower contrast, or keep dots out of the snow band.
- Fog at 浓雾 lifts the black map to mid-grey (33). It looks intended. I note it only because the player side is meant to be dark.

### N4 LOW: 「附近正在组队」 shows when no team is forming

- **Where:** the 寻找组队 pass card says 「附近正在组队」:
  - for a guest (87);
  - for D with 0 visible teams (74).
- **Code:** it is the default and the zero-team fallback (`pages/roam/index.js:342, 6060, 6116`). The comment at line 342 says an empty string should hide it.
- **Effect:** it reads as live data that doesn't exist; tapping finds 「附近暂时没有在招募的队伍」.
- **Fix:** show the line only with a real count.

### N5 LOW: the five session cards of one theme read the same

- **Repro:** 漫游 entry, located (75). Five cards each say 「城市定向 · E2E 探店日一期 · 距你 2m」 with the same cover. Nothing shows which date each session is.
- **Code:** `utils/roam-entry-map.js:76` builds meta from distance and team count only.
- **Fix:** put the session date in the meta line, which is real data.

### N6 LOW: merchant search map keeps the dark player controls

- **Repro:** merchant 9002 opens `pages/searchmap` (97). The map is the light style (layer 2), but the back button, search field and filter are dark translucent pills.
- **Rule:** merchant views are light. This holds even with no key, since the default base is light too.

### N7 LOW: captions and helper lines (owner rule), with the layout change for each

- **漫游 更多 drawer** (98): every row has a second line (「这会儿没人在附近走」, 「走近店铺就会点亮」, 「迷雾、动效、位置权限」). Titles alone are enough. Keep only lines that carry real data.
- **设置 sheet** (99): every switch row has a caption (「关掉后地图不再盖雾」, 「关掉地图漂移和呼吸」, 「默认关闭 · 街区模糊位置」, 「使用期间允许」, 「锁屏也记录足迹」, 「走近节点时震动」, 「不影响已上传的记录」). The switch state already says it. Keep a line only where it states a consequence the user can't see, such as the location-sharing default, which is close to consent.
- **Place card near a place** (50): 「靠近后可确认地点」 above 确认到点. The button alone is enough; when the player isn't close, disable it in place.
- **Invite expired** (06): 「请让队长重新分享」 under 「邀请已失效」. The title and 返回上一页 are enough.

### N8 LOW: team confirm dialogs mix half-width punctuation and repeat themselves

- **Reject** (17): 「拒绝「城瘾用户」的申请?」 and 「此操作不可撤销;对方不能再申请加入这支队伍」 use half-width 「?」 and 「;」.
- **Disband** (20):
  - Half-width 「,」 「;」 「?」 throughout.
  - The subtitle 「队伍解散后群聊关闭,队员各自回到未组队状态。」 repeats the first bullet 「全部队员会被移出队伍,队伍群聊同时关闭」.
  - Fix: drop the subtitle and keep the three consequence bullets.
- **Member quit** (24): the title still ends in a half-width 「?」. The body is full-width.
- These are consequence lines in a destructive confirm, so they stay; only the duplication and punctuation are findings.

### N9 LOW: the 继续 button on the end card covers a row of round items

- **Repro:** end a run (67b).
- The fixed 继续 sits on top of four round items above 「用时」; only their edges show.
- This is the rest of F8: the label is fixed, the overlap isn't.

### N10 LOW: stored route points stay when the author turns 同场路线 off

- **Repro:** with A and C sharing, publish `teamTrail:false` (R6).
- Reads and syncs return `available:false` at once, but `team_trail:pts:9` still holds both players' points. A later 「结束」 can't delete them either, because `sync` returns before the delete when the theme is off (`TeamTrailServiceImpl.java:93-94` via `eligibleTtl`).
- They are never served, and they expire at the stored TTL: at most 6 hours, and never later than one hour after the session ends.
- **Fix:** when the author switch is off, delete the caller's field before returning `unavailable`.
- Also: the switch edits the draft. A theme already on sale keeps routes on until the change is published again. That may be intended; the editor gives no sign either way.

### Not findings (checked)

- The grey pill and bar on each 附近活动 card come from the bundled default cover art `/images/route_city_cover.png`. Bundled images are unchanged by owner decision.
- 开始挑战 is greyed for a guest on the shared place because my fixture place has no challenge (`node.canInteract` false). This is not product evidence either way.
- Team detail during the session shows 「11月5日 15:15 场次开始」. That is the team's expiry time, frozen at creation, after I moved the session start: a fixture artefact.
- My first official-route fixture used seconds; the column is minutes (`migration_preset_route.sql:38`). I corrected the fixture. The page formats minutes correctly (79).

### HARNESS (not product)

- Weather: the stub stands in for Tencent. A phone and the real key must confirm the real reply's text values (for example whether Tencent sends 「小雨」 or 「小雨转阴」) and real traffic.
- `moveToLocation` easing, swiper gestures and the swiper scrolling on `current` (M10) need a phone.
- No `<page-meta>` in the rig. For largest-font shots I applied the page's own `fontScaleStyle` to the page host, as in the first pass.
- Rig MySQL runs in UTC, so 「8 小时前申请」 (16) is off by 8 hours.
- Topic covers go to `oss-rig.invalid` and never load.
- The stand-in base colours for white, paper and 2077 are my tints, not the real styles.

## 4. Not covered

- **Phone only:**
  - Real map gestures, `translateMarker` smoothness, `moveToLocation` easing.
  - The swiper following a pin tap.
  - Real fonts at largest size.
  - Share sheets.
  - `wx.openLocation` actually opening.
  - The real Tencent weather text, and the real styles (not bound).
  - Polling on cellular and in the background.
- **Not walked:**
  - The author save → review → approve path that publishes the switch; only the draft toggle and its effect were walked.
  - 「退出并移交队长」 by a leader alone in a team.
  - Switching 公开招募 on team detail after creation.
  - A team of 3–4 filling up through applications.
  - The team IM group.
  - The atmosphere-entry video, not delivered.
  - The 3D zoom (`ROAM_3D.on` is false).
- **Process:** the director page (j2). I started the trip over the API.
- **Accounts:** two real logins in two browsers at once stand for two phones. No request shim.

STATUS: PARTIAL - the rig walks are done; the items above need a phone, the real Tencent key and bound styles, or the author's publish-and-approve path.
