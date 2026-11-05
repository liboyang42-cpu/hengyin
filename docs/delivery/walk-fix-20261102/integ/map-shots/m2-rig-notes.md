# Round 9 · lane m2-routes · rig walk notes

Candidate: first walk on `40b4dcdb`. **Recheck on `a726b420`, then `12d84be8`** (see "Recheck on the fix" below). Built in its own detached worktree
(`$HOME/walk-rig/candidate`). `/home/user/hengyin` was not committed to, pushed or switched. The only files
added there are the ones in this folder whose names start with `m2-`. **All shots come from the rig, not a
phone.** The map in every shot is the rig's canvas stand-in, not Tencent's map.

## Recheck on the fix (`a726b420` → `12d84be8`): all three bugs FIXED

- **What was rebuilt.** The candidate worktree was moved to `a726b420`, then to `12d84be8`, and the browser bundle was rebuilt from it each time.
  - The backend jar was not rebuilt. `git diff 40b4dcdb 12d84be8` changes no Java, XML or SQL, only `chengyinhub-xcx` and docs. So the running backend is the same code.
  - `up.sh` is pinned to `12d84be8`.
- **The JSON shim is no longer used.** All rechecked shots and evidence come from the product code as shipped.
- **Bug 1 (JSON) fixed.** The bundle now sends `{'content-type':'application/json'}` on consent and sync.
  - A normal browser consent + sync returned 200 / code 200 (`m2-team-negative.txt` §13).
  - The two-player run made 2 consent and 5 sync calls, all code 200, with no 「访问过于频繁」 (§14).
  - Client end-of-run `finished:true` returned 200 (§15).
- **Bug 2 (privacy) fixed.** §11 was repeated in §12: the switch was set OFF and the page's `/trail/consent` request was made to fail.
  - The client stays `share:false` and does not poll, so no `/sync` is sent.
  - `HGET team_trail:pts:14 990007` returns nil.
- **Bug 3 (finish sheet) fixed** (`m2-card2.jpg`). The card now starts below the capsule, and 保存图片 / 分享 sit clear of the date line.
  - Remaining observation: on the first screen the fixed 「继续」 button sits over the 「第1章 咖啡」 title. That is scrollable content under a fixed button, not the old overlap.
- **Retaken without the shim:**
  - `m2-team-a.jpg`, `m2-team-b.jpg`, `m2-team-follow.jpg`: same content as before (other team's glow line, avatar pin with 「第 1 站」 on A's view, rail, follow).
  - `m2-route-play.jpg` and `m2-card2.jpg`.
  - `m2-shop-share-after.jpg`: the address line is a little brighter. There is still no shop code, because WeChat is unreachable.
  - `m2-fly-after.jpg` on `12d84be8`: the swipe card is shorter (200rpx), with a photo thumbnail.
    - Fixture: 弄堂口咖啡 (`roam_poi` 36) was given an approved cover so that the first card has a photo.
    - Its pin is shown enlarged as the selected one.
    - Still seen: the shop's name tag sits partly over the player's blue dot (the player marker is not part of avoidance).

## Product bugs found (first walk, on 40b4dcdb; all three fixed in a726b420, see above)

1. **Same-session team routes never work on a real client (blocker).** The client sends
   `/api/team/trail/consent` and `/api/team/trail/sync` form-encoded, but the backend only accepts JSON.
   - Client: `chengyinhub-xcx/pages/play/index.js:4908-4909` passes no header:
     `req('/api/team/trail/consent', 'POST', data, undefined, true)`, and the same for `/sync`.
     `utils/transport/request-client.js:117-120` then sets `content-type: application/x-www-form-urlencoded` for any POST without a header.
   - Server: `ApiPlayTeamController.java:264` (`trailConsent`) and `:275` (`trailSync`) take `@RequestBody Map<String,Object>`.
   - Real response: `{"msg":"Content-Type 'application/x-www-form-urlencoded;charset=UTF-8' is not supported","code":500}`.
   - Result: consent is never saved, so `decided` stays false. No points are ever uploaded, and no other teams are shown.
   - Other `@RequestBody` callers already send JSON, for example `subpackageMember/utils/team-up.js` for `/api/team/create`.
     The likely fix is `{ 'content-type': 'application/json' }` on both calls.
   - The unit tests mock `req`, so they cannot catch this.
   - Evidence: `m2-team-negative.txt` §8 (curl) and §10 (the page's own network log, no shim).
2. **A failed consent POST still starts sharing (privacy).**
   - What happens: `team-trail.js` `consent()` returns the previous state when the request fails. That state is
     `share:true` from `/state`. `onTeamConsentStart` (`pages/play/index.js:4939`; `utils/team-trail.js:99-104`) then calls `tt.start()` because `st.share` is true.
   - The server has no opt row for this person, so it uses `default-share=true` and stores their points.
   - Rig repro (§11): the player turned the switch OFF and pressed 开始. Only `/sync` was made JSON here, so consent failed as
     it would on a bad network (with bug 1 it always fails). Their points then appeared in `team_trail:pts:14`.
   - Even after bug 1 is fixed, a flaky network leaks the route of someone who said no.
   - Suggested fix: treat a failed consent as "not decided / not sharing" when the user chose off, or retry before
     starting the poller.
3. **Layout: on the finish sheet the route card's buttons are overlapped by the next section (`m2-card2.jpg`).**
   - `.finsheet__head { margin-top:-35rpx }` (`pages/play/index.wxss:1920`) was made to tuck the head into the old map's
     bottom gradient. After the new `.finsheet__card` it pulls the date line 「11月4日 周三 · E2E 探店日一期」 up under 「保存图片」.
   - The card also starts about 16 px from the top of a full-screen sheet, so the status bar and the capsule sit on top of the
     card's upper-right corner. The old full-bleed map was designed to sit under them; a framed card is not.

## Setup actually used

- Rig scripts:
  - `git worktree add $HOME/rig origin/claude/cloud-walk-20261101-rig` (rig commit `592aa21b`).
  - `up.sh` takes `SHA=${RIG_SHA:-40b4dcdb…}` and builds that commit in `$HOME/walk-rig/candidate`.
  - Ran with `LC_ALL=C.UTF-8`.
- Club-lane rig fix: `tools/harness-patches.diff` does not exist on `origin/claude/cloud-walk-20261101-club`. The club lane's
  rig fix is commit `d0d2e253` (`compile.mjs`: wx:for/wx:if on `<template is>`; `runtime.js`: nested `<template name>`).
  I applied it to the rig worktree with `git apply`, before the bundle was built.
- Docker:
  - dockerd started with `/etc/docker/daemon.json` `{"registry-mirrors":["https://mirror.gcr.io"]}`.
  - The same pinned digests were pulled (`mysql:8.0.45@sha256:4af1…`, `redis:7.4-alpine@sha256:858f…`).
- Full `up.sh`: about 2 min (warm `~/.m2`). Schema PASS, 457 tables.
- Then `chains.py player`: all 4 chains PASS (topic 990014, activities 9–13).
- App restarted with `APP_MEDIA_PUBLIC_BASE_URL=https://www.chengyinhub.com/prod-api`. This needed a rig patch in `app.py`,
  because `chain_acceptance.app_env` drops unknown env vars.
- Redis for read-backs: container `cy-chain-local-1-redis`, port 6379, db 9 (`docker exec … redis-cli -p 6379 -n 9`).
- Harness hygiene: headless Chromium from runs that crashed kept polling `/trail/sync`. That caused 「访问过于频繁」 rate-limit
  replies (12/min per member) in some early runs. I killed the strays and re-ran every team shot from a clean state.

## Rig / harness changes (rig worktree only; full diff in `m2-rig-standin.diff`)

| File | Change | Why |
|---|---|---|
| `up.sh` | candidate SHA pinned to `40b4dcdb` (overridable `RIG_SHA`) | build this lane's tip |
| `app.py` | pass through `APP_MEDIA_PUBLIC_BASE_URL` | local approved images need a public base |
| `mpweb-seed/tools/compile.mjs`, `mpweb/src/runtime.js` | club lane patch `d0d2e253` | nested templates |
| `mpweb/src/nav.js` `MP.drawMap` | draws marker `iconPath` images (width/height/anchor/zIndex/alpha) | photo pins, dots, team pins |
| | draws polylines at their real width and colour (`#RRGGBBAA`) with border and round caps | glow route |
| | places `cover-view slot="callout"` children at their marker by `customCallout` (bottom-centre at marker top + `anchorY`) | photo and avatar callouts |
| | when there are custom callouts (or `MPWEB.mapTrueScale`), projects with the page's own centre and scale (Web Mercator); `MPWEB.mapFitAll` keeps the old fit-everything view, standing in for `includePoints` | map view |
| | fires `bindupdated` after each draw | roam's first-frame watchdog showed 「地图加载异常」 without it |
| `mpweb/src/runtime.js` | `getFileSystemManager().writeFileSync/unlink/…` keep files in memory as data: URLs; `assetUrl` resolves them | roam's screen fog writes a PNG per frame, and the guard stayed full-black without it |
| | `createMapContext().getRegion` returns the stand-in's last true-scale view | fog and pins use the real region instead of the fallback |

Per-run seams (in the walk scripts, not in the rig):
- `onRegionChange({type:'end', causedBy:'scale', scale, region})` stands in for a pinch, as the previous lane did.
- Roam POIs in state `fog` were set to `seen`, the state they reach after walking past them, for the pin shots.
- `openRoamDiscover()` was called for the shop strip. That is the handler behind the passport tile 「发现附近店铺」; it is not in 更多.
- **JSON shim for team-trail POSTs** (`JSON_SHIM=1`; first walk only, NOT used in the recheck on `a726b420`/`12d84be8`): it wraps `wx.request` so `/api/team/trail/(consent|sync)` go out as
  `application/json`. This stands in for the missing header (bug 1). Without it no team shot is possible.
  `m2-team-a/b/follow`, `m2-route-play` and `m2-card2` were taken with it. `m2-consent` was taken without it (the sheet is the same).

## Fixture rows (throwaway rig DB `cy_e2e_51`)

| Rows | How |
|---|---|
| `roam_poi`: 47 rows around People's Square, `description='m2-fixture'`. 32 have covers (repo `assets/covers/*.jpg` copied to `work/upload/upload/2026/11/04/<md5>.jpg`); 3 are merchant nodes (type 2, merchants 8001–8003) | direct SQL |
| `roam_poi`: +153 rows `description='m2-fixture-extra'` for the 200 check, deleted afterwards | direct SQL |
| `app_media_asset`: APPROVED / SYSTEM / LOCAL rows for every fixture image (the same columns `registerApprovedSystemImage` writes). The real admin upload path failed: WeChat content security is unreachable (503 「安全检查服务异常」) | direct SQL |
| `app_media_asset` id 1 (chain topic cover, a fake OSS key) switched to a LOCAL file. With the OSS key, `/api/play/nodes` and `/api/topic/list` fail with 「对象存储配置缺失: CHENGYIN_OSS_BUCKET_NAME」 | direct SQL |
| `mms_merchant` 8001 `cover_image` = approved local photo | direct SQL (a coverImage row) |
| `ums_member` avatars for 9004, 990005, 990007; nicknames 夜航小周 and 独行阿青 | direct SQL |
| `official_event` id 1 「城市点亮周 · 走查样例」, status 2, contract v1 | direct SQL |
| Players `walk-player-1..4` (990005–990008) | `seed-walk.py add-players 4` (real login) |
| Club 7002 membership for 9004 and walk-player-1..4 | real `POST /api/club/join` |
| Activity 14: today, 13:35–23:59, club 7002, topic 990014 | real `POST /api/club/open-session` (9008) |
| Paid registrations in 14: 9004 (75), 990005 (76), 990007 (77); in activity 9: 990008 (79) | real quote → create, then the rig pay seam: the real `/api/registration/callback`. `payment_status` was never written. |
| Unpaid registration in 14: 990006 (78, payment_status=1) | real create, never paid |
| 9009: paid in activity 13 (from the player chain) | chain |
| `play_team` 晨光队 {9004} and 夜航队 {990005} for activity 14, plus `play_team_member` | direct SQL. `/api/team/create` answers 「该主题未开放组队」 for this topic. 990007 has no team (a team of one). |
| Topic 990014 `product_type` 2→1 (shop day → 城市定向) | direct SQL. Mode 2 has no run stack and no compass/story map, so the route, consent and team UI cannot be reached in it. |
| Node coordinates moved to around People's Square in `cms_topic_node`, `cms_topic.published_route_graph_json` and the frozen order copy `cms_registration_scan` (regs 75–78) | direct SQL |
| Lead session: leader 9008 started the trip and unlocked chapter 2 | real `/api/club/lead/start`, `/api/club/lead/unlock-chapter` |
| walk-player-1 completed station 1 | real scan arrival: rig `scanCode` seam → `POST /api/play/checkin` with the node's checkin code. Photo check-in fails in the rig: `/api/common/uploadOSS` 503. |

## Per shot

| Shot | What it shows | Notes |
|---|---|---|
| `m2-route-roam.jpg` | Roam after 14 `loc` steps (~320 m east then north) at scale 17: wide translucent white glow (`#FFFFFF38`, 14 px) under a bright line (`#FFFFFFF2`, 4 px), ending at the player dot | The fog's revealed blob sits a little up and left of the line (fog vs stand-in projection); the HUD shows 0.32 km |
| `m2-route-play.jpg` | Story map (`openStoryMap`) after the same walk in play, with the run started from 游玩工具 → 开始这一局: the same two-layer glow line, the arrow at its end, the 1–4 station route | Retaken on `12d84be8` without the shim; no other team active at the time |
| `m2-consent.jpg` | The 「同场路线」 sheet: switch row 「让同场队伍看到我的路线」 (on by default) and a full-width 「开始」 | No shim. It appears after 开始游戏 → 开启定位, i.e. when location starts (`watchLocation` → `_startTeamTrail`), not from the run button. Behind it is the club-lead 「队伍集合中 0/4」 screen. The sheet has no line saying what is shared or for how long. |
| `m2-team-a.jpg` | 9004 (晨光队) story map: own white glow route; 夜航队's green glow line; its avatar pin with 「第 1 站」; the right-side avatar rail | 夜航队's pin is drawn above its last point because of the stand-in's customCallout placement (ring + tag height). It sits right next to station 1 and the player arrow. |
| `m2-team-b.jpg` | walk-player-1 (夜航队) sees 晨光队's line, its avatar pin (no tag: 0 stations done) and the rail | 晨光队's last point is ~35 m from station 1, so its avatar hides the 「1」 station marker. This is geography, but team pins can cover the next-stop marker. |
| `m2-team-follow.jpg` | After tapping the rail avatar (「跟随夜航队」): `fit` off, map centred on 夜航队's last point at scale 16 | The rail button sits over the map near station 4; I could not see a clear "on" state on the rail item in this shot |
| `m2-card2.jpg` | Finish sheet after a long-press (3.8 s) on 暂停这一局: the route card has drawn (L-shaped route), 「0.3 公里」, the theme name and 00:37; 保存图片 / 分享 | Bug 3 (overlap and status bar). The theme code is missing: `/api/topic/share-code` → 「个人码生成服务繁忙」 because WeChat is unreachable from the rig. The city is missing: reverse geocode has no Tencent key. The stop count is missing: 9004 completed 0 stations. The client did send `finished:true` on end, and the server removed its points (network log). |
| `m2-fly-after.jpg` | Roam shop strip on the dark map: dark cards with only name, distance and 「去这儿」; the strip opens on 弄堂口咖啡 (120 m) | Compare `m-fly.jpg`. The first card's shop has no photo, so its pin is a dark dot under its name tag. The lower ~40 px of each card is empty. |
| `m2-pins-46.jpg` | 47 POIs at scale 16: photo pins don't overlap; lower-priority ones drop to small dots; 2 participating shops have name tags | The 「弄堂口咖啡」 tag sits on the edge of the player's blue dot (the player marker is not part of avoidance) |
| `m2-pins-200.jpg` | 200 POIs at scale 16: 197 markers, 40 photo callouts (the cap), the rest dots and dark place circles; no photo overlaps | Two photos touch the player dot |
| `m2-zoom-far-after.jpg` | Scale 13: white soft heat dots, bigger and brighter where they stack; 「附近 47 个地点」 | The view is under fog (darker than `m-zoom-far.jpg`, which had no fog) |
| `m2-shop-share-after.jpg` | Merchant home → 分享店铺: the shop's cover photo full-bleed and darkened, 「店铺」, name in large italics, address | No shop code: `/api/merchant/share-code` → 「个人码生成服务繁忙」 (WeChat unreachable), so the bottom-right is empty. The address line is low-contrast on the bright photo. 「复制文案」 is cut at the right edge of the action row (horizontal scroller). |
| `m2-land-coop-received.jpg` | `pages/coop/list/index` as 9008 (club 7002, the invitee): 「收到的」 tab with the accepted invite from 城瘾跑团 | As 9002 the tab is empty. For a club owner, `/api/official/merchant-invites` returns 403 NOT_ACTIVE_MERCHANT in the background (no toast). |
| `m2-land-coop-sent.jpg` | `?tab=sent` as merchant 9002: 「我发出的」 selected, with the 「供给已生效」 card | OK |
| `m2-land-myproject.jpg` | `subpackageA/pages/myproject/index` as player 9004: 主题 tab, empty state 「还没有主题」 | OK; 9004 has no projects |
| `m2-land-official-detail.jpg` | `pages/activity/official-detail/index?id=1` as 9004: hero, stats, title, 立即报名 | `/api/official/events/1/attendance` → 「仅支持官方V2赛事」 because the fixture is a v1 event, so it shows 「集合信息加载失败 重试」. The 「集合与联系」 label sits flush on the left edge with no page margin; I don't know if that only happens in this error state. |
| `m2-land-merchant.jpg` | `pages/merchant/index/index` as 9002: 「Hi，探店日样板咖啡」, ¥0.00, the project card | The in-page tab bar covers the bottom of the project card (its subtitle line). This was already the case in the rig's `m01-merchant` proof, not new in this lane. |
| `m2-land-club.jpg` | `pages/club/workbench/index` as 9008 redirects to club detail 管理 (manage) tab for 执行俱乐部: scan, QR, share, settle, settings, 活动运营 | `/api/club/access/me` → 403 「俱乐部身份无效或权限不足」 in the background (no club role rows in the fixture); the page still renders. As 9003 it is the same. |

None of the six landing pages showed a page error or failed to open. The only non-200 business replies are the
background ones listed above.

## Negative access (`m2-team-negative.txt`)

Run over real HTTP with JSON bodies:
- **Another-session players** (9009, paid in activity 13; 990008, paid in activity 9): `{"available":false}` for state, consent and sync.
  Redis is unchanged: no 9009 or 990008 field, and no opt rows.
- **Guest**: HTTP 401 on all three.
- **Unpaid registrant** (990006): `available:false`, nothing stored.
- **Opt-out** (990007, a team of one, which A sees as 「独行阿青」 before the opt-out):
  - consent `share=false` removes its field: `HGETALL team_trail:pts:14` then shows only 9004 and 990005, and `opt:14` holds `990007 "0"`.
  - A later sync from 990007 returns `share:false` and stores nothing.
  - A no longer receives it.
- **`finished:true`** from 990005 removes its field: only 9004 is left.
- §8 reproduces bug 1. §10 and §11 add the browser evidence for bugs 1 and 2.

## What only a phone can prove

- Real Tencent map rendering:
  - glow polylines on the custom dark style (the alpha line under the bright line);
  - `customCallout` placement of photo and avatar pins (the stand-in approximates it, and the team pin's ring sits above its point);
  - `translateMarker` smoothness, `includePoints` fit with the given padding, and `moveToLocation` easing.
- Real gestures and `regionchange` (here it is simulated), fog alignment, and the drag cloud.
- That the client can talk to `/api/team/trail/*` at all. It cannot until bug 1 is fixed. After that:
  - 12 s polling over a cellular network and the backoff to 60 s;
  - pausing when the app is in the background;
  - the rate limit (12/min per member) for someone who reopens the page often.
- The theme mini-program code and the shop code on the share cards (needs WeChat `getwxacodeunlimit`), and the city name (needs the Tencent key).
- Saving the route card image to the album (`canvasToTempFilePath` → `saveImageToPhotosAlbum`), the 分享 sheet, and the card's look on real fonts.
- Subscribe-message delivery and the tap landing from the WeChat service notification (here the paths were only opened directly).
- Photo check-in (needs OSS and WeChat content security).


---

# m2 rig notes (2026-11-05)

Rig: worktree of `claude/cloud-walk-20261101-rig`, `up.sh` from that branch. Backend = the rig's pinned master jar (not this branch's jar). Docker started fine. Browser bundle rebuilt from this branch with `CAND=<this repo> bash mpweb/build.sh`, so "before" shots are the rig's master bundle and "after" shots are this branch's mini-program.

Walked (390x844, headless Chrome, not a phone):
- E6: `pages/publish/temp/index` as 9003, picked 选项问答 → 完成, scrolled to the end. Before: the last card sits right on the bar edge. After: clear space under 添加互动工具. Shots `integ/fix-shots/m2-before-temp-bottom2.jpg`, `m2-after-temp-bottom2.jpg` (both viewed).

Not walked (needs a saved theme with chapters/stations and a story theme for the player; the K10 theme 990017 and its rig diff are not in this rig run): E4 in 主题详情/编辑章节, E12, E13, E2, P1, P3, P4, L1, backpack chip. Covered by unit tests and code reading only.

## Video worker, 2026-11-05: own test runtime

Media code `9307c39df`, reopen correction `29856bc38`. See `../CODEX-VIDEO-RESULT.md` for item statuses and `../proof/codex-video/` for viewed screenshots and actual playback traces. This is a browser-rig walk, not a phone or full-game PASS.

Used the owner's authorized Puppeteer harness, privately copied from `/private/tmp/codex-k10-rig-20261105/harness/mpweb/walk.mjs`. Own Mac frontend18108/admin18118, own VPS backend18119/MySQL18116/Redis18112; schema `cy_video_walk_20261105`. GitHub JARs da9 (author/publish/photo/opening) and ce84 (poster/skip/reopen/slow/error) had checksums and embedded SHAs verified. External wx/OSS stand-ins were reused, not restarted. Native storage persistence was corrected in the private harness so reload preserves the exact UI-written draft; no form injection.

Author9003 created theme990032 with both videos, all chapter audio, theme music, two templates/nodes and a TIME text egg through UI. Admin approved actual media and topic task38 through UI. Players9004/9009 separately used free checkout. Actual opening video ended at3s; BGM paused/resumed, narration started. Viewed poster-first, decoded, tap-skip and re-entry shots. An8.5s MP4 delay fell back at5009ms; an aborted MP4 emitted error and returned to text. Photo upload/proof completed node124; QR node125 remains unfinished. Branches/ending playback and phone sound remain unverified. Nothing was heard.

My shared-daemon clone/import attempt caused its512MiB MySQL container to OOM. The owner was informed and assigned shared restoration to play; I used only my independent MySQL afterward. The complete pre-incident fixture dump survives privately at `/private/tmp/codex-video-rig-20261105/fixture.private.sql`; no database material is committed. Cleanup state is in the video result.
