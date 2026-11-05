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

STATUS: PARTIAL - game authored, published and started on screen with media logged; no full run because the photo station could not start (fix pushed, not re-walked) and the QR station cannot coexist with a branch (engine gap, named); throttled-media and four-look passes not done
