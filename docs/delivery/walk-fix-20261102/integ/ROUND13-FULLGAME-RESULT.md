# Round 13: the owner's full game, walk result

Run 2026-11-05, m3 worker, on branch head `1a9b55ab5` plus my own pushed fixes. Rig: real jar, MySQL 8.0.45, Redis, the real mini-program in headless Chrome at 390x844. **Not a phone. Nothing was heard.** Audio and video are Chrome media elements behind the mini-program's wx calls; I logged every call and what the element did. Test media are tones and a 3 s colour-bar clip I made.

I authored the game on screen as author 9003 (`pages/publish/fabu`, template editor, 主题详情) with no database or API shortcut, published it (topic 990014 「磁带雨夜」, activity 9), signed up player 9009 on screen for the free ticket, and played the start. **I did not finish a run**: see "Stopped at" below. Two rig-database edits were made for diagnosis only (set a template's `validation_method` to 5 and back), and they changed nothing in the published game.

Stand-ins set up this round (rig only): TLS certificate and `/etc/hosts` for `api.weixin.qq.com` and the production bucket host, JVM truststore, `wxfake.py` and `fakeoss.py` on 443, `approve.py` for review, plus a rig seam so `wx.chooseMedia` can return an mp4 and poster (`nextVideo` in `mpweb/src/nav.js`).

## Requirement table

| Requirement | Works? | What I saw |
|---|---|---|
| Opening chapter (序章) | Yes | 添加序章 button; story text; saved. |
| Opening video | Yes (authoring and playback) | Uploaded through 开场视频 → 添加视频, approved in review, saved and published. In play the decoded colour-bar frames filled the screen with 跳过 (`m3-p-05`). |
| Opening music | Yes | Chapter audio on the opening chapter: uploaded, approved. On entering, theme music started at volume 0.25. |
| Theme music | Yes | 主题详情 → 主题配乐 upload. Play log: `create`, `src`, `volume 0.25`, `play()`, `el:playing`; paused at 4.3 s when the video started, `play()` again after it; volume 0.25. |
| Narration (chapter) | Yes | After the video: `2:create`, `src`, `play(0)`, `el:playing`; theme music went to 0.1 while it spoke. |
| Station voice (语音讲解) and station music (背景音乐) | Authored only | Uploaded on the 报亭 template (互动工具); not reached in play. |
| Photo station | Authored; **blocked in play** | Saved and published as 拍照打卡. In play the backpack task list says 报亭 「尚未到达」 and 开始任务 is grey, even with 开启定位 on and the location at the station (distance 0 m). See bug 4. |
| QR station | **Cannot be in this theme** | See bug 3. |
| Branch (互斥选项) | Saved and published | After 保存草稿 the group 老电台 / 旧码头 saved, and the server kept it on reload. 进行方式 「个人独立进行」 could be chosen, so the old block on choosing 进行方式 did not happen at head. Not played. |
| Easter egg | Seen firing | TIME egg after 1 minute. Player state showed `events: 雨中便条 RECORDED` after several minutes in chapter 1. The text on screen was not checked. Miss case not run. |
| Ending chapter, ending video and music | Authored; not played | 设为结局 chapter with audio and 结尾视频 uploaded and approved. Not reached. |
| Publish | Yes | `publish-draft` returned 已发布 and review approved. The screen after it said 「当前账号无权查看 · 返回上一页」 (bug 7). |
| Free solo ticket and sign-up | Yes | 获取通行证 → 立即报名 → 城市定向票 ¥0 → 参与人信息 → 确认报名 → 「免费报名成功」 (order A202611051E3089CA). |
| Both branch sides, egg miss and find, reopen | Not done | Blocked by bug 4. |
| Throttled and failing media, four looks, error states | Not done | The station sheet in 星尘 was seen (`m3-p-18`). Nothing else. |

## Bugs found

1. **FIXED, pushed (`c5d477097`)**. A template saved with 参与方式 「扫码参与」 stored `validationMethod` 2, so publish and save were refused with 「扫码玩法的参与方式要选「扫码」」. `pages/publish/temp/index.js` now stores 4 when the scan switch is on. Re-saved in the rig: request body `validationMethod 4`. Unit test added.
2. **FIXED, pushed earlier (`5ea8403f1`)**. The topic page showed 「Could not write JSON…」 raw.
3. **NOT FIXED, engine decision**. 扫码 stations cannot be in any theme that has a 进行方式, branch or egg. The server matrix has `r5CompletionProof=false` for `scan` (`GameplaySupportMatrix.java:48` list does not include it). Publishing was refused with 「「旧码头」的扫码：开了状态或 d20 检定的主题里，这种玩法还不能确认玩家是否完成，请换一种玩法」. The editor's 参与方式 list also says 「开了状态或 d20 检定的主题里要选定位到达或扫码」 for 直接参与, so 扫码 is offered and then refused at save. The owner's game (QR station plus branch plus egg) therefore cannot be built as asked. A scan bridge like the qaShot one is needed (lane m1 / Codex). I made the station 定位到达 to continue.
4. **FIXED in source, not re-walked (`2e60d1bc9`)**. A 拍照打卡 template always saved `validationMethod` 2 (`prepareFormData`), even if 定位到达 was chosen. In a theme with a 进行方式 the player only auto-arrives at stations with 0 or 5 (`pages/play/index.js:5155`), so the photo station stays 「尚未到达」 and 开始任务 stays grey. The change keeps 5 (and 4) when chosen. Not walked end to end: it needs a new theme with the photo station set to 定位到达, authored again. The published 990014 cannot be edited after it starts.
5. Editor UX: 添加互斥组 shows nothing useful before the chapter's nodes are saved (only a toast 「请先保存本章至少 2 个节点」); after 保存草稿 it works. 同场路线 is on by default.
6. Layout: in the 文字问答 template editor, 「再加一段题目」, 「按这份配置试玩」 and 「可触发的结果」 run edge to edge without the card margins (`m3-f-98`).
7. False alarms after real saves: after reload and a save the banner said 「这次还没有保存 · 云端保存的节点与章节的对应与本机不一致」 although the server returned 保存成功 and kept the data. After publish the screen showed 「当前账号无权查看」. Not fixed.
8. Photo station with 直接参与 plus the 扫码参与 option is allowed in the editor and refused later (same family as 3).

## Stopped at
Bug 4: no player can start the photo station in the published game. The next step is to author a second theme with the photo station on 定位到达 and replay: ticket, both branch sides, egg miss and find, ending video and music, reopen; then slow and failing media with `~/walk-rig/media` stand-ins (media server `/tmp/media_srv.py` on 127.0.0.1:8765) and the four looks with `looks.sh`. The rig, stand-ins and the on-screen steps above are the recipe. Screenshots I viewed: `integ/fix-shots/m3-*`.

## Checks
`node --test tests/unit/safe-user-message.test.js` 11 pass; `tests/unit/*temp*.test.js` 436 pass. Not run: `tests/lint/ui-integrity.js` (no line-anchored edits except one same-line change), `build-action-ledger.js --check`, preflight, because only one line of `pages/publish/temp/index.js` changed in place.

## Second round (second theme, same day)

Rig rebuilt on head `121630463` (jar and bundle), stand-ins restarted (`~/walk-rig/start-all.sh`). I authored a second theme on screen with the photo station on 定位到达 and a result rule on every station. It published, review approved, two players signed up on screen (9009, 9004).

| Requirement | Result | Seen |
|---|---|---|
| Photo station on 定位到达 | **Works** (fix `2e60d1bc9`) | Templates saved with `validation_method` 5. With 开启定位 on and the location at the station the quest started, 打开任务 showed 「拍一张」, the photo was accepted (「拍好了」, `m3-q-05`), 结算任务 and 确认装载 settled it. Log: station voice (id 2) 1.0, station music (id 3) at 0.14 under it, theme music paused. A volume-0 context was created and destroyed for the next station: the preload runs. |
| Branch, side 旧码头 (run A, player 9009) | Works up to the end of the station | 旧码头 text question showed after m1's fix `2ca541cc2` (before it, 打开任务 showed nothing: `playKit.show` false). 「答对了」, settled, server returned the success reply 「码头认出了你。」 |
| Branch, side 老电台 (run B, player 9004) | **Blocked** | After 报亭, the backpack task list shows only 旧码头 (「尚未到达」); 老电台 is not listed at all and the next-station marker points at it. Same hole as before, for a photo member of the group. For player 9009, once 旧码头 was done the 老电台 row was empty (no state, no button). |
| Chapter and run completion | **Blocked (m1's lane)** | After 旧码头 the server says nodes 122 and 123 COMPLETED, 124 (老电台) PLAYABLE, route status ACTIVE, theme quest 124 NOT_ARRIVED. The story screen went blank after 「继续」 (`m3-r-07`: no text, no button) and on reopen the chapter starts again with 老电台 as the next station. So the run never reached the ending. |
| Egg | Fired | `events: 雨中便条 RECORDED` for 9009 (it took several minutes in the chapter). Miss run not done: both runs passed one minute in chapter 1. |
| Ending video and music, reopen at the end | Not reached | Because of the completion block. |
| Opening video, theme music, narration in play | Seen again | Video decoded; theme music paused for it and resumed at 0.25; narration at 1.0 with theme music at 0.1. |
| Slow and failing media | Not done | Credit and the completion block; stand-in media server (`/tmp/media_srv.py`) is up. |
| Four looks | Partly | Story page and station sheet in 星尘, 纸本, 末世, 夜城 (`m3-looks-contact.jpg`; set with `look_json` in the rig database, not through the editor). The sheet is the same dark card with white text in all four, readable; only the background texture and particles differ (rain in 末世, dim blue in 夜城, warm blur in 纸本). The backpack shot missed (the click hit the page), and the photo, scan, finish card and error screens were not seen in the other looks. |

## More bugs and observations
- **Fixed, pushed (`121630463`)** (bug 7, publish half). The editor said 「网络中断，暂时确认不了是否已发布」 when the server had refused publishing with a clear reason (e.g. 「「报亭」：…个人任务的这一站玩法要带结果规则…」). The page treated a business refusal (body code 500) as a lost reply. It now shows the server's reason first. The real fix for the cause is that the 拍照打卡/文字问答 template needs 成功反馈 turned on; the editor does not say so before publishing.
- **Bug 7, save half, found but not fixed.** After a successful 保存草稿 the same editing session still thinks a saved exclusive group is unsaved: 添加序章 shows only a toast 「互斥选项须先保存，且必须属于本章」 (`theme-runtime.js:138`) until the page is reopened with the draft id. Reopening is the workaround. I could not find the stale-id cause by reading; the saved node ids are probably not refreshed in the chapter rows after a save.
- **After publishing** a club author lands on `merchantinfo` and sees 「当前账号无权查看」 because `/api/project/home` answers 403 「商家身份无效或权限不足」 for an author who is not a registered publisher. Not fixed.
- **First run never asks for location.** Starting a new run showed no 开启定位 sheet; arrival only worked after leaving and reopening the page (the sheet appears on resume). Not confirmed as a product bug: the first-open path may rely on a permission the rig does not model.
- **Backpack task list.** The 开始任务 / 打开任务 / 结算任务 button of the last row sits at or under the bottom edge (y 833 to 986 on an 844 px screen) until the panel is scrolled, so a player may not see it.
- **One bug in my first round's table is solved:** the photo station arrival (bug 4) is confirmed fixed.
- QR station: left alone, waiting for m7.

## To continue
1. m1: the sibling of a finished branch member must close (or complete) on the server, the chapter and run must complete, and the screen must never go blank. Evidence above (route-state and theme/state values). With that, run B (老电台 side), the ending video and music, and reopen can be walked.
2. Slow and failing media (media server on 127.0.0.1:8765, `/tmp/media_srv.py`), egg miss, and the photo, finish card and error screens in the four looks.
3. Bug 7 save half.

STATUS: PARTIAL - second theme authored, published and played to the first branch member with logs; photo-on-定位到达 fix proven; run blocked at chapter completion (sibling station left playable, screen blank), so the second branch side, ending, throttled media and most look screens are not done
