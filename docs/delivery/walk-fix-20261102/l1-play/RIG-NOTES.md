# L1 play fixes: rig notes (2026-11-02)

## Candidate and rig

- Candidate: `claude/walk-fix-1102-l1-play` at **0abf58c338b042530d8efb95e4e798810b90788d** (checked out read-only as a detached worktree `$HOME/walk-rig/candidate`; jar built from it with `mvn -DskipTests package`, browser bundle built from its `chengyinhub-xcx`).
- Rig: `claude/cloud-walk-20261101-rig` (worktree `$HOME/rig`), `up.sh` copied to a scratch dir with `SHA` set to the candidate. I applied the club lane's `harness-patches.diff` (`compile.mjs`, `runtime.js`) to `$HOME/rig` only.
- Backend on 127.0.0.1:8099 (MySQL 8.0.45 + Redis 7.4 in Docker, schema `cy_e2e_51` built and seeded by the candidate's own `ci/chain_acceptance.py` stages). Browser runtime on 127.0.0.1:8097.
- Harness incident: Docker Hub returned 429 for the pinned images. I pulled the **same digests** from `mirror.gcr.io/library/{mysql,redis}@sha256:…` and tagged them locally as `mysql:8.0.45` and `redis:7.4-alpine`. `docker image inspect mysql:8.0.45@sha256:4af1f881…` resolves to the pinned digest, and so does Redis (`858f009f…`). Nothing else changed.
- Shots are 390×844 JPEG q70 from the rig's own `shot` step. They come from `walk.mjs` `launch()` plus small step scripts in `$HOME/rig/l1/` (not committed).

## What I authored (all through real APIs)

| What | Ids | How |
|---|---|---|
| Publisher identity for author 9003 (fixture 俱乐部主理人, club 7001) | — | `POST /api/publisher/identity` (dummy name + checksum-valid dummy ID number) |
| Legacy 文字问答 「门楣上的年份」 answer `1873`, hint1/hint2, answerReveal 「1873，这座楼重建落成的那一年。」 | template 6005 | `POST /api/template/draft` vm=1 |
| Legacy 文字问答 「第几根灯柱」 answer `7、七、第七根`, 2 hints, reveal 「第 7 根。老周在这里留下了第二张字条。」 | template 6006 | same |
| Advanced estimate 「海关大楼钟面的直径大约有几米？」 米, 1–20, answer 5.4, tolerance 0.5, reveal 「约 5.4 米。」 | template 6007 | `/api/template/draft` vm=0 + `advancedConfigJson` (schemaVersion 1) |
| Advanced qa TYPE 「苏州河在老上海人嘴里还有一个名字，叫什么江？」 answer `吴淞江、吴淞` | template 6008 | same |
| Advanced photoCheck 「拍下海关大楼的钟楼」 (maxTries 3, fallback retake) | template 6009 | same |
| Theme A 「外滩钟声：报时人的尾声」. Ch1 (6005 with fragment 「钥匙不在锁里，在数字里。」, 6006 with fragment 「第二张字条：去钟楼看看。」), ch2 (6007, 6008, 6009), ending chapter 尾声 (`ending:{fallback:true}`, one text block, no stations, **no opening chapter**). LINEAR, free ¥0 ticket, clubId 7001 | topic 990014, chapters 42/43/44, nodes 71–75 | `POST /api/topic/v2/create` |
| Control theme 「外滩钟声：无尾声对照版」: 1 chapter, 1 station (6006), no story/ending | topic 990017 (990015 was the first try; its audit went stale after 990014's approval changed template 6006, so I deleted it) | same |
| Theme B 「外滩钟声：报时人的尾声（晴天版）」: same as A **without the photo station**, so the last station can be finished in the rig (the photo upload always 503s). Ch1 (6005, 6006), ch2 (6007, 6008), 尾声 ending chapter | topic 990019, chapters 51/52/53, nodes 83–86 | same |
| Admin approvals | audit tasks 1, 4, 6 | `POST /business/audit/review` with `expectedVersion` |
| Club-led sessions (开一场) | activity 12 (990014), 13 (990017), 15 (990019) | `POST /api/club/open-session` as 9003 |
| Players | 9009 → act 12; 9004 → act 13; `walk-player-1` (member 990005, created by real login via `seed-walk.py add-players 1`) → act 15 | `club/join` + consent + `registration/quote` + `registration/create` (¥0, `payment_status=2`) |
| Leader actions | `lead/start` on 12, 13, 15; `lead/unlock-chapter` 12: 42→43; 15: 51→52 (fired while the player page was open, for P-35) | `/api/club/lead/*` as 9003 |

### Fixture / direct DB actions

- `seed-walk.py shift-session` on activities 12, 13 and 15. It writes `cms_activity.start_date/end_date` straight to the test DB so the sessions start now.
- No other DB writes. Every other DB access was a read (`SELECT`).
- Theme 990017 was unpublished (`/api/topic/update_user_status`) after the control check. The author limit is 「同时上架的主题已达上限（2）」, and I needed the slot for 990019.

## Shots (`shots/`)

| File | Shows |
|---|---|
| P-02-after.jpg | Station 「第几根灯柱」, typed **第七根** (third item of `7、七、第七根`) → 「对了」 + reveal + fragment (walk-player-1, act 15) |
| P-02-after-2.jpg | Same station, typed **7** → 「对了」 (9009, act 12) |
| P-04-after.jpg | After the last station: the 尾声 chapter text 「你说出那句话的时候，远处海关大楼的钟，走了一格。老周的字条到这里就齐了。」 in the story stream with 「继续」 |
| P-04-after-2.jpg | Next screen: 「这一生的结局 · 尾声」 page with the same text and 「这一生的档案 · 走过 …」 |
| g20-after.jpg | The single 「全部完成」 card (4 坐标 · 02:50 用时 · 200 解谜分 · 继续 / 保存通关卡) |
| P-08-after.jpg | Chapter 2 held at the gate: header 「…第二章 · 钟…」, 「继续探索」, pill 「等队长放行」 |
| P-09-after.jpg | Advanced estimate in the story: the question is shown. Top-left: back + file + menu; the compass/map and theme title are gone |
| P-09-after-2.jpg | After submitting 6: 「差一点 · 你猜 6 米 · 实际 5.4 米 · 差 0.6 米 · 约 5.4 米。」 |
| P-09-after-3.jpg | Same, scrolled: 「这一关结束了」 (disabled) and 「继续故事」 |
| P-10-after.jpg | Legacy typed wrong (1900): input ringed red + 「答案不正确」 |
| P-10-after-2.jpg | Advanced TYPE, **live** wrong submit (黄浦江): 「再想想 / 不对。把它清掉，再试一次。」 in a **grey** bar, underline not red, wrong text not cleared (see Findings) |
| P-10-after-3.jpg | Same station re-entered after wrong tries: red underline + red bar 「再想想 / 不对。把它清掉，再试一次。」, input cleared |
| P-11-after.jpg | Legacy correct (1873): 「对了」 + reveal + 「碎片 · 钥匙不在锁里，在数字里。」 + 「继续」 |
| P-13-after.jpg, -2 | Gather screen (`lead.status==1`): three dots at different brightness in two frames 0.8 s apart, 「队伍集合中 0/2」 |
| P-24-after.jpg | photoCheck: after consent, upload 503 → 「上传失败，请重拍」 under the frame, 「拍一张」 |
| P-25-after.jpg, -2 | 「看提示（1/2 · −30 分）」 → after hint 1: 「在正门拱券的正上方。」, 「当前最高 70 解谜分」, 「看提示（2/2 · −30 分）」 |
| P-28-after.jpg | Tools menu row 「扫码到达 · 到站后扫现场的码」 |
| P-35-after.jpg | Leader released ch2 while the player waited: toast 「队长放行了「第二章 · 钟楼背后」」 and the chapter text appears |

## HTTP proof for P-04 (player walk-player-1 = member 990005, activity 15, topic 990019)

Requests and responses are copied from the browser runtime's network log while the player finished the last station (`吴淞江` on node 86) in the real page. The token is in the Authorization header and is not logged.

```
POST /api/play/advanced/action HTTP 200
  request: {"sessionId":5,"version":0,"idempotencyKey":"adv-5-v0-ecaf236b","action":"SUBMIT_QA","payload":{"input":"吴淞江"}}
  response: {"msg":"操作成功","code":200,"data":{"sessionId":5,"activityId":15,"topicId":990019,"nodeId":86,…} (truncated)
GET /api/play/route-state?activityId=15 HTTP 200
  response: {"code":200,"data":{"routeMode":"LINEAR","sessionId":2,"status":"ACTIVE","currentNodeId":86,"version":3,
             "nodeStates":{"83":"COMPLETED","84":"COMPLETED","85":"COMPLETED","86":"PLAYABLE"}}}
GET /api/play/action-context?nodeId=86&activityId=15 HTTP 200
  response: {"code":200,"data":{"routeSessionId":"2","expectedRouteVersion":3}}
POST /api/play/arrive HTTP 200
  request: activityId=15&nodeId=86&longitude=121.4908&latitude=31.239&accuracy=8&routeActionId=route-mvyz8vhr-usfjlqka4p&expectedRouteVersion=3&routeSessionId=2
  response: {"msg":"已到达，打卡成功","code":200,"data":{"nodeId":86,"firstTime":true,"done":4,"total":4,"completed":true,
             "routeState":{"routeMode":"LINEAR","sessionId":2,"status":"COMPLETED","version":4,
             "nodeStates":{"83":"COMPLETED","84":"COMPLETED","85":"COMPLETED","86":"COMPLETED"},"hp":10,"luck":3},
             "newBadges":[{"code":"TOPIC_FINISH_990019","name":"外滩钟声：报时人的尾声（晴天版） · 通关"}],"xp":1425,"medalRank":1,"puzzlePersonalBest":200},
             "intentTerminal":true}
GET /api/play/journey/ending?topicId=990019&activityId=15 HTTP 200
  response: {"msg":"操作成功","code":200,"data":{"code":"ending1","title":"尾声",
             "summary":"你说出那句话的时候，远处海关大楼的钟，走了一格。老周的字条到这里就齐了。","epilogues":[],"exhibits":[],"badge":null,
             "chapter":{"id":53,"name":"尾声","atmospherePreset":"DEFAULT","audioUrl":null,
             "blocks":[{"type":"text","content":"你说出那句话的时候，远处海关大楼的钟，走了一格。老周的字条到这里就齐了。"}]}}}
```

The same `GET /api/play/journey/ending?topicId=990019&activityId=15` repeated afterwards from a script returns the identical `ending1 / 尾声 / chapter 53` body. It does **not** return 「本局尚未开始」.

DB read-back (`topic_route_session`). Columns: id, activity_id, topic_id, member_id, status, phase, current_node_id, version, completed_at, state.status, state.completedNodeIds.

```
(2, 15, 990019, 990005, 1, None, None, 4, 2026-11-02 16:08:33.320, "COMPLETED", [83, 84, 85, 86])   <- walk-player-1, theme B, finished
(1, 12, 990014, 9009,   0, None, 75,   4, None,                    "ACTIVE",    [71, 72, 73, 74])   <- 9009, theme A, held at the photo station
```

Both rows were created when the player first played a station. Neither theme has an opening chapter; both have only `journey_story_json.ending.endings` (`[{"code":"ending1","title":"尾声","fallback":true,"chapterId":…}]`).

## Control: linear theme without ending/story (990017, activity 13, player 9004)

```
# before: topic_route_session rows for topic 990017 = 0
GET /api/play/action-context?nodeId=78&activityId=13
  -> {"code":200,"data":{"routeSessionId":"0","expectedRouteVersion":0}}
POST /api/play/answer body={"activityId":13,"nodeId":78,"answer":"第七根","longitude":"121.488410","latitude":"31.242680","accuracy":8,"routeActionId":"route-l1-342672496","expectedRouteVersion":0,"routeSessionId":"0"}
  -> {"msg":"答对了！","code":200,"data":{"nodeId":78,"firstTime":true,"done":1,"total":1,"completed":true,
      "newBadges":[{"code":"TOPIC_FINISH_990017",…}],"xp":3000,"medalRank":1,"puzzleScore":100,"completionMode":"SOLVED",
      "answerReveal":"第 7 根。老周在这里留下了第二张字条。"},"intentTerminal":true}
GET /api/play/journey/ending?topicId=990017&activityId=13
  -> {"msg":"本主题未配置剧情","code":500}
# after: topic_route_session rows for topic 990017 = 0;  cms_topic.journey_story_json = NULL
```

With no ending, playing and finishing the station creates no route session. The station completes through the session-less path (`routeSessionId 0`) as before. The ending read still says 「本主题未配置剧情」. This call also shows P-02 server-side: the third alternative `第七根` is accepted through `/api/play/answer`.

## Findings (what looked wrong or partial)

1. **P-10 advanced TYPE, live wrong answer is not red.** On the first wrong submit (`SUBMIT_QA` 黄浦江 → 200), 「再想想 / 不对。把它清掉，再试一次。」 appears in a grey bar. The underline is not red, and the typed answer stays in the box (P-10-after-2). On later wrong submits nothing changes on screen at all. I traced the component: no `setData` with `typeWrong:true` or `typed:''` arrives after the response, and the only writes are `onInput`'s. The red bar and the cleared input appear only after leaving and re-entering the station (P-10-after-3). That re-entry goes through the observer path, where `attempts>0`. The page does not call `playkit-qa.settle()` on the inline story-flow path, and the observer keys do not include `attempts`. The legacy sheet (P-10-after) does work.
2. **The first tap on 「提交」 after typing sometimes sent nothing** (advanced TYPE). It happened once out of three, and I re-tapped. This may be the known rig tap issue on `hover-class` views. Not judged as a product bug.
3. **P-09 result fits below the fold.** At 844 px the reveal line sits at the bottom edge, and 「继续故事」 is reachable only by scrolling. The disabled 「这一关结束了」 and 「继续故事」 are two stacked buttons (P-09-after-3). The unit 「米」 sits at the far left, away from the number.
4. **Odd chapter card at the finale.** Right after the last station (chapter 2) the full-screen card reads 「第 1 章 / 共 2 章 · 第一章 · 江海之门 · 2 坐标 · 轻点跳过」. After that the 尾声 plays. While held at the chapter-2 gate, a 「01 · 第一章」 card with 「等队长放行」 also shows briefly.
5. **g20.** No 解锁徽章 card or medal screen was visible. The sequence was: 尾声 in the story → 「这一生的结局」 page → a pyramid 「通关 · 外滩钟声…」 animation → particles → the one 全部完成 card. However, the DOM text kept 「4处旧址已全部点亮 · 领取你的城市足迹卡」 and a 足迹卡 (「已通关 · 4 点亮坐标 · 首达 外滩源·旧邮筒 · 继续 / 保存通关卡」) under the overlays. They were not visible in any frame I captured. After 「继续」 on 全部完成, three rig taps did not leave the card (rig tap or product: unverified).
6. **P-13 gather screen (pre-existing).** The 「我到了」 button overlaps a darker pill whose text is hidden behind it, and the story text shows faintly through the overlay.
7. **P-28 menu (pre-existing).** The 「扫码到达」 row has no icon while its neighbours do, and 「开始这一局」 is still offered mid-game.
8. **P-24 (pre-existing).** The author's `requirement` sentence (「画面里要有海关大楼顶上的钟面」) is not shown, only the title and shotNote.

## Not verified

- **Photo station.** The upload always returns 503 in the rig (WeChat `img_sec_check` unreachable). So theme A cannot be finished, and P-04/g20 were shown on theme B, which has no photo station. A successful photo pass is NOT VERIFIED.
- Real-device taps, the native map, GPS and timing of animations. P-13's pulsing is judged from two still frames only.
- P-35 was seen as a toast in the rig's `showToast` rendering. P-08 and P-35 were run only with one player per session.
- 「我到了」 self check-in (club sessions have no gather lat/lng, a known issue) was not used. The leader started without arrivals.

P-10 re-shoot: `P-10-after-2.jpg` and `P-10-after-3.jpg` now come from commit **2110aafb6ffbff5988a3f2a907763783a86e3797**. Only the mpweb bundle was rebuilt from that commit's `chengyinhub-xcx`; the backend jar is still 0abf58c3.
Fresh run on the same rig data: new club session activity 16 on theme 990019, player walk-player-2 (member 990006), `shift-session 16`, leader `lead/start` and unlock 51→52. Station 86 (advanced TYPE).
First live wrong submit (黄浦江, attempts 1) and second (长江, attempts 2) each show a red underline, the box cleared, and a red bar 「再想想 / 不对。把它清掉，再试一次。」. This replaces Finding 1 above, which described commit 0abf58c3.


# Round 2 (P-23, P-29), 2026-11-02

## Candidate and rig

- Fresh container. Rig worktree `$HOME/rig` = `origin/claude/cloud-walk-20261101-rig` with the club lane's `docs/delivery/cloud-walk-20261101/club/tools/harness-patches.diff` applied (rig only). `up.sh` `SHA` pointed at this branch.
- Docker Hub answered 429 again. The pinned MySQL / Redis digests were pulled from `mirror.gcr.io/library/{mysql,redis}@sha256:4af1f881… / 858f009f…` and tagged `mysql:8.0.45` / `redis:7.4-alpine`; `docker image inspect mysql:8.0.45@sha256:4af1f881…` resolves to the pinned digest.
- Candidate commits used: backend jar first from 8648f47b (P-23 backend), then rebuilt from the commit that limits the leader's list to stations without 「允许跳过」 (the app was restarted on the re-exploded jar; data kept). Browser bundle rebuilt per step: **before** shots from `f2f04d56` (this branch before round 2), **after** shots from this round's commits.
- Step scripts in `$HOME/rig/l1/` and `scratchpad/l1/` (not committed). Shots 390×844 JPEG q70 from the rig's own `shot`.

## What I authored (all through real APIs)

| What | Ids | How |
|---|---|---|
| Publisher identity for 9003 (fixture 俱乐部主理人, club 7001) | — | `POST /api/publisher/identity` (dummy name + checksum-valid dummy ID number) |
| Legacy 文字问答 「门楣上的年份」 answer `1873` | template 6005 | `POST /api/template/publish` vm=1 |
| Advanced qa TYPE 「江的另一个名字」 answer `吴淞江、吴淞` | template 6008 | `/api/template/publish` vm=0 + `advancedConfigJson` |
| Advanced compass 「对准海关大楼的钟」 bearing 90, tol 15, hold 3 s, xp 5, **`allowSkip: true`** | template 6006 | same |
| Advanced compass 「对准外白渡桥」 bearing 10, tol 15, hold 3 s, xp 5, **no `allowSkip`** | template 6007 | same |
| Theme 「外滩钟声：罗盘与收尾（走查）」, LINEAR, club 7001, free ticket. Ch1 (44): text, node 75 (6005), **trailing text 「你把年份念出来的时候，钟楼里传出一声闷响。第一章的门在身后合上了。」**. Ch2 (45): text, node 76 (6008), 77 (6006), 78 (6007), trailing text | topic 990016 | `POST /api/topic/v2/create` |
| Admin approvals | audit tasks 1–5 | `POST /business/audit/review` |
| Club-led session | activity 10 | `POST /api/club/open-session` as 9003; `lead/start`; `lead/unlock-chapter` 44→45 |
| Players 9009, 9004, walk-player-1…5 (990005–990009, real login via `seed-walk.py add-players`) | registrations 74–80 | `club/join` + consent + `registration/quote` + `registration/create` (¥0; the server set `payment_status=2`) |

Fixture / direct DB actions: `seed-walk.py shift-session 10` (writes `cms_activity.start_date/end_date`). Everything else was a read.

Rig-only injection for P-23: the rig's compass seam emits a heading every 500 ms, so a phone that cannot read a direction was simulated by replacing `wx.startCompass` in the page with one that calls `fail` (`page.evaluate`, not in product code).

## P-23 over HTTP (players 9009 and 9004, leader 9003, activity 10)

Run by a script against the real backend; tokens in headers, not logged.

```
start 76                                                   -> {"code": 200, "msg": "操作成功", "data": {"sessionId": 1, "version": 0}}
SKIP_SENSOR_STATION game=compass on qa station             -> {"code": 400, "msg": "本站没有这个玩法"}
SKIP_SENSOR_STATION game=qa                                -> {"code": 400, "msg": "这一项不支持跳过"}
REPORT_SENSOR_FAILURE game=qa                              -> {"code": 400, "msg": "这一项不支持跳过"}
SUBMIT_QA 吴淞江                                              -> {"code": 200, "msg": "操作成功"}
arrive 76                                                  -> {"code": 200, "msg": "已到达，打卡成功"}
start 77                                                   -> {"code": 200, "msg": "操作成功", "data": {"allowSkip": true, "failedReads": 0, "canSkip": false, "released": false, "skipped": false, "done": false}}
SKIP before any failed read                                -> {"code": 400, "msg": "读取失败两次后才能跳过这一站"}
REPORT_SENSOR_FAILURE #1                                   -> {"code": 200, "msg": "操作成功", "data": {"allowSkip": true, "failedReads": 1, "canSkip": false, "released": false, "skipped": false, "done": false}}
other member 9004: SKIP on 9009's session                  -> {"code": 400, "msg": "玩法会话不存在或无权访问"}
other member 9004: REPORT on 9009's session                -> {"code": 400, "msg": "玩法会话不存在或无权访问"}
SKIP after one failed read                                 -> {"code": 400, "msg": "读取失败两次后才能跳过这一站"}
REPORT_SENSOR_FAILURE #2                                   -> {"code": 200, "msg": "操作成功", "data": {"allowSkip": true, "failedReads": 2, "canSkip": true, "released": false, "skipped": false, "done": false}}
SKIP_SENSOR_STATION                                        -> {"code": 200, "msg": "操作成功", "data": {"allowSkip": true, "failedReads": 2, "canSkip": false, "released": false, "skipped": true, "done": true}}
arrive 77                                                  -> {"code": 200, "msg": "已到达，打卡成功"}
start 78                                                   -> {"code": 200, "msg": "操作成功", "data": {"allowSkip": false, "failedReads": 0, "canSkip": false, "released": false, "skipped": false, "done": false}}
REPORT_SENSOR_FAILURE #1                                   -> {"code": 200, "msg": "操作成功", "data": {"allowSkip": false, "failedReads": 1, "canSkip": false, "released": false, "skipped": false, "done": false}}
REPORT_SENSOR_FAILURE #2                                   -> {"code": 200, "msg": "操作成功", "data": {"allowSkip": false, "failedReads": 2, "canSkip": false, "released": false, "skipped": false, "done": false}}
SKIP without author permission or release                  -> {"code": 400, "msg": "这一站不允许跳过"}
leader team-progress skipRequests -> [{"memberId": 9009, "nickname": "玩家二号", "nodeId": 78}]
member 9004 team-progress has skipRequests key -> False
member 9004 calls release-station for 9009/78              -> {"code": 500, "msg": "只有本场队长可以放行"}
9004 start 76 (qa)                                         -> {"code": 200, "msg": "操作成功", "data": {"sessionId": 4}}
leader release-station 9004/76 (qa, not sensor)            -> {"code": 500, "msg": "这一站不是传感器玩法，不能放行"}
leader release-station 9009/78                             -> {"code": 200, "msg": "已放行，成员可以跳过这一站"}
state 78 after release                                     -> {"code": 200, "msg": "操作成功", "data": {"allowSkip": false, "failedReads": 2, "canSkip": true, "released": true, "skipped": false, "done": false}}
SKIP_SENSOR_STATION (released by leader)                   -> {"code": 200, "msg": "操作成功", "data": {"allowSkip": false, "failedReads": 2, "canSkip": false, "released": true, "skipped": true, "done": true}}
arrive 78                                                  -> {"code": 200, "msg": "已到达，打卡成功"}
# columns: id, node_id, owner_type(1=player), owner_id, status, score, state_json (originalNodeContent trimmed)
game_runtime_session (1, 76, 1, 9009, 1, 0, state_json={"qa": {"passed": true, "attempts": 1, "finished": true, "lastInput": "吴淞江", "lastCorrect": true} ...})
game_runtime_session (2, 77, 1, 9009, 1, 0, state_json={"compass": {"done": true, "skipVia": "AUTHOR", "skipped": true, "skippedBy": 9009, "failedReads": {"9009": 2}} ...})
game_runtime_session (3, 78, 1, 9009, 1, 0, state_json={"compass": {"done": true, "skipVia": "LEADER", "skipped": true, "skippedBy": 9009, "failedReads": {"9009": 2}, "releasedFor": {"9009": {"at": 1793613263346, "by": 9003}}} ...})
# columns: member_id, node_id, status, puzzle_score, completion_mode
player_node_progress (9009, 75, 1, 100, 'SOLVED')
player_node_progress (9009, 76, 1, None, None)
player_node_progress (9009, 77, 1, 0, 'SKIPPED')
player_node_progress (9009, 78, 1, 0, 'SKIPPED')
# columns: member_id, node_id, result_json.playKit.compass.skipped
play_attempt_log (9009, 77, 'false')
play_attempt_log (9009, 77, 'false')
play_attempt_log (9009, 77, 'true')
play_attempt_log (9009, 77, None)
play_attempt_log (9009, 78, 'false')
play_attempt_log (9009, 78, 'false')
play_attempt_log (9009, 78, 'true')
play_attempt_log (9009, 78, None)
```

`game_runtime_session.score` stays 0 on both compass sessions (no compass xp on a skip). `play_attempt_log` keeps every REPORT / SKIP action with its result view (`playKit.compass.skipped` false, false, true).

The same chain through the real page (walk-player-4 on node 77; 9004 on 77 and 78): `REPORT_SENSOR_FAILURE` ×2 from two failed starts → kit `canSkip: true` → 「跳过这一站」 → `SKIP_SENSOR_STATION` 200 → 「这一站已跳过」. Leader 9003: team-progress `skipRequests` = `[{memberId 9004, nodeId 78, 玩家小城, 桥头 · 桥的方向}]` → 更多 → 「放行这一站」 → confirm → `POST /api/club/lead/release-station` 200 → 9004 re-enters node 78: `canSkip: true` → skip 200.

## P-29 in the browser (theme 990016, chapter 44 → 45)

- Before (bundle f2f04d56, player 9004): answer `1873` → 「对了」 → 「继续」 → straight to the 「第 1 章 · 完成」 card, then the chapter-2 card and chapter-2 text. Chapter 1's trailing line never appears.
- I traced the after-fix walk with a `setData` hook: the answer receipt triggers a rebuild while chapter 44's blocks are still `[text, node 75]` (the server cuts blocks at the first unfinished node); ~150 ms later the node reload brings `[text, node 75, text]`. The first fix attempt held only when the story flow was open and only on `data.chapter`, and both missed. The final fix keys on the last shown chapter and waits for the reload.
- After (walk-player-4): 「继续」 → chapter 1 with the trailing line and 「继续探索」 → tap → 「第 1 章 · 完成」 → chapter-2 card.

## Round 2 shots

| File | Shows |
|---|---|
| P-23-before.jpg | Bundle f2f04d56, node 77 after two failed reads: 「这台设备还没报出方向或读数已中断，请重试或退出」, only 「开始」 |
| P-23-after.jpg | Same station and state on this round's bundle: 「开始」 + 「跳过这一站」 (author allowed) |
| P-23-after-2.jpg | After skipping: 「这一站已跳过」, dial not green, 「继续故事」 |
| P-23-after-3.jpg | Leader 9003, 更多: 「放行这一站 · 玩家小城 · 桥头 · 桥的方向」 between 解锁下一章节 and 整团结算 |
| P-23-after-4.jpg | Leader confirm: 「让 玩家小城 跳过「桥头 · 桥的方向」？这一站记 0 分。」 取消 / 放行 |
| P-23-after-5.jpg | 9004 re-enters node 78 after the release: 「开始」 + 「跳过这一站」 (author had not allowed it) |
| P-29-before.jpg | Bundle f2f04d56: right after 「继续」, the 「第 1 章 · 完成」 card; the trailing line was never shown |
| P-29-after.jpg | This round: chapter 1's trailing line under its opening line, 「继续探索」 |
| P-29-after-2.jpg | After 「继续探索」: the 「第 1 章 · 完成」 card, then chapter 2 |
