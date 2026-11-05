# Round 12, lane k10-immersive: result

Branch `integ/launch-test-20261103`, cut from `2f9daae1`, rebased onto `f522c665` before pushing. Run 2026-11-05 as a scheduled cloud session.

**Another worker did lane k10 at the same time** (commits by 「Bob (OpenClaw Agent)」: `c09b51b8` checker and phone script, `37131d5c` empty backpack no longer blocks saving chapter eggs, `40c3a677` game end feedback). Their `k10-phone-script.md` and `k10-theme-check.md`/`.py` were already on the branch when I pushed. I kept them as they are and added mine below them in the same two files, under 「第二份」. The two checkers read different exports; either can be used.

**Not a phone.** Docker did start in this container. Everything below ran in the cloud rig: the real jar from this branch on real MySQL 8.0.45 and Redis 7.4, and the real mini-program in headless Chrome at 390×844 (1x, as the rig shoots). The rig's audio is Chrome's `<audio>` behind `wx.createInnerAudioContext`. I logged every call the game made on it (`create`, `src`, `volume`, `play()`, `pause()`, `stop()`, `destroy()`) and what the element really did (`playing`, `pause`, `ended`). **I heard nothing.** The test media were sine tones I generated, so the logs show what was playing and how loud. They do not show how it sounds. Balance, real headphones, the silent switch, the locked screen, real GPS, the camera and printed QR codes are left to the phone script.

Rig: `claude/cloud-walk-20261101-rig` with j3's `rig-changes.diff`, plus these rig-only changes (`proof/k10-walk/k10-rig-changes.diff`):
- the audio call log;
- `onAudioInterruptionBegin/End` that can be fired;
- page `onHide`/`onShow` around `scanCode`/`chooseMedia`/`chooseImage`, as WeChat does;
- `chooseMessageFile` returning my test mp3s, because audio upload picks a file from a WeChat chat;
- Chrome without a proxy, and local stand-ins for `api.weixin.qq.com` (token, image check) and for OSS under the production bucket host name (`/etc/hosts`, local only).

The bucket host name matters because the editor compares cover URLs against that exact host. Audio uploads go to the human review queue: I approved them as the platform admin through the real `/business/audit/review` endpoint (`proof/k10-walk/approve.py`).

## Summary

The owner's game cannot be configured in full today, and when it is played several sounds were wrong. I fixed the play-side bugs I found, and four editor bugs. The rest needs owner decisions, listed at the end.

What a player hears now, as logged in the rig after the fixes:
- The chapter narration plays once when the chapter opens.
- At 开始任务 the station's own voice (语音讲解) starts by itself, with the station music at 0.14 under it. The music goes back to 0.35 when the voice ends.
- The music does not restart after a photo.
- After the camera or crop page, the music carries on from where it stopped.
- During an interrupted call the narration pauses and then continues from the same second.
- The ending chapter opens as a story page with its ending music.

## 1. Author it as a user, on screen: PARTIAL (the editor cannot make the game asked for)

I built 「磁带雨夜」 (free, solo, 上海, 3 stations) using only `pages/publish/fabu` and its screens, as 9003. Published topic **990017**. Two earlier drafts, **990015** and **990016**, got stuck; see E10 and E18.

| Wanted | Could the editor do it? | What happened |
|---|---|---|
| Opening music | Partly | Only as a chapter's 章节音频 (auto-plays when the chapter opens). The opening chapter 序章 has no button (E20). A first chapter without a station is refused at publish (「第1章至少需要一个节点」). So the intro music has to sit on the first station's chapter. |
| Opening video | **No** | No video anywhere: not in the story-flow insert menu (文字/图片/音频/地点), not in 主题详情, no `<video>` in the player, no backend field. |
| Background music | Partly | Only per station: 玩法模板 → 互动工具 → 背景音乐. Theme-wide music is not settable: theme `audioUrl` was cleared from the editor on 2026-09-15, though the player still reads it. |
| Narration per station | Yes | 玩法模板 → 互动工具 → 语音讲解. Before this round it never played by itself (A3). |
| Photo check-in | Yes | 拍照打卡 template. The player is asked for the photo twice (P2). |
| QR scan | Yes | 参与方式 「扫码参与」 on a template. The player is asked to scan twice (P2). Printing the station code was not walked. |
| Branch, different solutions | **Only without photo stations** | 编辑章节 → 互斥选项 (two stations, only one needed). Adding it to a theme with a 拍照打卡 station makes every save fail (E16). |
| Easter eggs | **Only without photo stations** | 编辑章节 → 途中彩蛋. Triggers are only time or steps; content is text and rewards (E15). Same save failure as branches (E16). |
| Ending music | Yes, after a fix | 设为结局 on a chapter with 章节音频. The ending chapter was refused at publish (E19, fixed). The player never played it (A7, fixed). |
| Ending video | **No** | As above. |

Editor findings. Fixed ones are marked **FIXED**. Screenshots are in `fix-shots/`.
- **E9 FIXED**: 玩法规则 step box. Each key press rebuilt the box (`wx:key="*this"` on the text itself), so typing stopped after one character. In the rig the saved rule of station 报亭 is literally 「拍」, and the player sees 「拍」 on the station card (`k10-h05`).
- **E7 FIXED**: 到达与引导 on a station not yet saved opened a blank sheet (`k10-c25-arrive`). It now says why (`k10-fix-arrive-after`).
- **E19 FIXED**: an ending chapter with only story and music was refused 「第N章至少需要一个节点」 (`k10-f02-preview` before, `k10-f04-preview` after).
- **E14 FIXED (code only, not re-shot)**: in 我的故事玩法草稿, a draft without a cover was plain text with no visible selected state, and a second tap silently unselected it (`k10-e03-selected`). It now gets the same card frame as the others, and the existing selected outline shows on it.
- **E5 FIXED**: the 语音讲解 sheet showed 「k10-narr1.mp3.mp3」.
- **E10 / E16 / E18, BLOCKER, not fixed (engine policy)**: once a theme has any chapter rule (互斥组, 途中彩蛋, or choosing 进行方式), the server compiles it into the generic runtime. That runtime refuses every 拍照打卡 station. The save fails with the raw text 「节点 85（报亭） 玩法 qaShot 工具 genericSettlement：尚无通用结算完成证明」 (`k10-d15-savefail`). Removing the 互斥组 and the egg does not help: a leftover `chapters[{completion:ALL}]` stays in the config and nothing in the UI clears it. Both drafts are stuck for good. j3 found the same family; k8 owns it this round and could not run. Needs owner: see the list below.
- **E17, HIGH, not fixed**: the save-failure banner covers the last chapter's 编辑故事流/编辑章节 buttons and the page cannot scroll past it (`k10-e12`). The author cannot reach the setting that caused the failure. Its 「为什么会这样」 link sits over the status bar.
- **E21, not fixed**: publishing a theme whose dates have already started worked (「已发布」), but the editor said 「这次还没有保存 · 网络中断，暂时确认不了是否已发布」. Its check-back read is refused: 「主题已开始，不能再编辑」 (`k10-f05-publish`).
- **E2**: an audio upload goes to human review. The author sees 「上传失败：文件已提交审核，通过后请重试此文件」 and must re-pick the same file from a WeChat chat later. The second pick then works at once. Audio can only come from a WeChat chat file (`chooseMessageFile`).
- **E4, captions** (owner rule):
  - 玩法支持 「支持状态以玩法支持矩阵为准。反馈或工单是独立渠道…」
  - 值域 「开局值在第一章「初始状态」配置」
  - the three 进行方式 descriptions and 「与票务人数、自由探索的设置互不影响。」
  - 「节点玩法已保存…已绑定当前主题，将随主题一起审核。」
  - the grey hints in 编辑章节
- **E6**: in the template editor, 添加互动工具 and 保存并绑定 sit under the fixed bottom bar. The first tap hits nothing until you scroll more.
- **E12 / E13**: 编辑章节 keeps 「全部完成」 on together with a 互斥组, and shows 「请先保存本章节点」 even after the stations are saved.
- **P1**: the editor's 同场路线 switch is on by default. A solo player then gets the 「同场路线 · 让同场队伍看到我的路线 · 开始」 sheet at 开启定位 (`k10-h04-team`).

## 2. Play it start to finish: DONE in the rig, with fixes

Player 9009 (and 9004 before the fixes): free ticket through 报名 → 参与人信息 → 确认报名, then 开始游戏. Read-backs:
- `/api/play/photo` 「已提交,打卡成功」 done 1/3 and 3/3;
- `/api/play/checkin` 「打卡成功」 done 2/3;
- `/api/play/journey/ending` ending1;
- finish card 0.4 公里 · 02:53 · 3 站 (`k10-r19-ending`).

Audio state machine, from the logged calls. Before = 9004 at `2f9daae1`, after = 9009 with this round's fixes.

| Transition | Before | After |
|---|---|---|
| Chapter opens | chapter narration plays, volume 1 | same |
| Narration ends, walking | silence (no theme music can be set) | silence, until a station has music. Station music then carries on between stations. |
| 开始任务 at a station | **chapter narration restarted from 0** under the station music. Station voice never played. | station voice plays by itself. Music 0.35 → **0.14** while it speaks → 0.35 after. |
| Photo in the story | **station music stopped and restarted from 0** (re-signed URL counted as a new song) | music keeps playing |
| Second photo: camera, crop page, back | music and voice paused at hide, **nothing resumed**, chapter narration restarted. **The photo was dropped and the station could not be finished.** | music resumes at the same second, photo accepted, station done |
| Interrupted call (`onAudioInterruptionBegin/End`) | narration paused at 4.5 s, resumed at 4.5 s | same (verified after) |
| Panel-mode station music (advanced-game) | its own player at volume 1.0, not in the audio session, ignored the 背景音乐 switch, could play over the theme music, never came back after hide | goes through the page's one station-music channel |
| Ending | **ending chapter and its music never played**: the finished-run response nests it under `story.chapter`, the client read `chapter` | ending chapter opens with its music (0 → 8 s, ended; `k10-r24-ending-auto`) |
| Reopen after finishing | summary card only | ending chapter again, then the card |

Fixes, all in `chengyinhub-xcx/pages/play/index.js` unless named:
- **A1**: station music matched by file, not by signed URL (`_syncStationBgm`).
- **A2**: chapter narration auto-plays once per chapter. A new chapter is no longer blocked by the previous chapter's narration still playing. The sound button still replays (`playChapterAudio(auto)`).
- **A3**: station voice auto-plays once when the station's task opens, on the shared narration player, so it replaces the chapter narration rather than overlapping it (`_autoStationVoice`, called from `onAdvancedSession` and `startGame`).
- **A4**: music ducks under any narration (`_duckBgm`, `BGM_VOLUME` / `BGM_DUCKED`).
- **A6**: `onShow` resumes whatever was playing at `onHide` (`audioSession.resumeAll()`). Before, it only forced the theme music.
- **F1**: photo and scan results that come back from the native camera or scanner are no longer dropped as stale (`_isLegacyGameReturn` in `photoGame`/`scanGame`). Cause: `onHide` bumps `_playLoadEpoch`.
- **A7**: the ending chapter is read from `story.chapter` too (`_loadLifeEnding`).
- **F7**: `pages/play/components/advanced-game/index.js` + `index.wxml` hand panel music to the page (`bind:bgm` → `onAdvancedBgm`).

Not fixed, reported:
- **P2, HIGH UX**: every station asks for its proof twice.
  - 拍照打卡: 「拍一张」 in the story, then a second sheet 「拍照上传」 (`k10-r03-task`, `k10-h08`).
  - 扫码参与: the in-story scan, then a second sheet 「扫码」 (`k10-r10-answer`, `k10-r13-after-bridge`).
  - The template choice also sets the station's check-in method (2 or 4), so the old check-in runs after the game. It needs a decision on which one is the proof.
- **P3, copy**: the in-story scan says 「扫一下门口的码 / 仅确认这一站的任务码」 on a bridge (shop wording plus a caption). The second sheet adds 「扫描现场那枚二维码，让这一关的答案落到你手里。」. The station voice row shows 「线索藏在声音里 ·」 with a trailing dot. A photo is judged 「答对了」.
- **P4**: in the photo game the 前置说明 line is drawn larger and bolder than the question (`k10-r03-task`).
- **Easter eggs found and missed: not walked**, because the editor could not save a theme with an egg and a photo station (E16).
- **Locked screen**: `app.json` declares only `requiredBackgroundModes: ["location"]` and `onHide` pauses all game audio. With the screen locked in a pocket, music and narration stop. See the owner list.
- **Video: not walked**, because it does not exist.
- **Weak and slow network at each media step: not walked.** Code path: the player reads OSS media through `InnerAudioContext` with no preload. A station's voice and music start loading only when 开始任务 is tapped. An error on the music stops it silently. An error on the voice shows the raw `errMsg` as a toast (`initAudio onError`). Media are the authors' own files, up to the upload limit, so a large mp3 on mobile data will wait.

## 3. All four looks: DONE for the story screens, findings only

The same finished run shown in 星尘, 纸本, 末世 and 夜城 (`k10-looks-ending.jpg`). The look really changes: stars, warm paper tint, rain, cyan glitch line.

To switch looks I set `cms_topic.look_json` on 990017 directly in the rig database. A published theme cannot be edited (「主题已开始，不能再编辑」). This is a rendering shortcut, not authoring.

- **L1**: the opening screen (title + 开始游戏) is the same plain black in all four looks (`k10-looks-intro.jpg`). The first thing a player sees ignores the chosen look.
- The bright white 继续/开始游戏 pills are the same in every look.
- The backpack chip shows 「0/6」 in a theme with no items.

I did not compare against `docs/design/` prototypes in detail. Not verified: the look on the station sheets and the photo/scan screens.

## 4. Fix: DONE for the bugs above. Design choices listed below.

Tests:
- `node --test tests/unit/k10-immersive-audio.test.js`: 12 pass, all run the real page methods. They would fail on the old code: `_autoStationVoice`, `_duckBgm`, `_isLegacyGameReturn` and `onAdvancedBgm` don't exist there, and the dedupe, once-only and `story.chapter` checks fail.
- `tests/unit/gameplay-feedback-images-bgm.test.js` was updated for the new contract: the component no longer opens its own player.
- Full unit run: 16,211 tests. 7 failed with my first change set. 4 of those also fail at `2f9daae1` (`mycanyu-refresh-state`, `r10-j2-f06-session-entry`, `verification-unknown-adapters`, `play-chapter-story-blocks-contract`). The other 3 were mine and are fixed (the BGM contract and the two action-ledger tests, after the rebuild).
- `build-action-ledger.js --write` then `--check`: PASS (5,719 controls before the rebase, 5,729 after merging the upstream k9 work).
- `ui-integrity.js`: the two existing baseline keys in `pages/play/index.js` moved lines (1808→1813, 8446→8507). Same code, no new entries. Result in "Verify" below.

## 5. Owner's configured games: DONE (tool), not run on production

`integ/k10-theme-check.md` contains the read-only SQL, the media-state SQL and the checker script (Python 3, no packages). It checks:
- missing media;
- stations with no way on;
- unreachable branches, and 互斥组 under 全部完成;
- a missing ending;
- the one-character rule left by E9.

Proof in the rig: it lists 990015/990016 as unusable, and 990017's rule 「拍」. A planted bad copy of 990017 lists all eight planted problems. **I could not read production**, so 990068 and 990069 are unchecked.

## 6. Phone script: DONE

`integ/k10-phone-script.md`, 17 steps: silent switch, locked screen, background, a call, balance, real GPS, camera, printed QR, ending music, save and share.

## Needs owner (my recommendation first)

1. **Photo stations and branches/eggs together (E16), blocker for this game.** Recommend: extend the existing generic-settlement proof to 拍照打卡 (`qaShot`), the same way `scan` and `sort` already have one, in `GameplaySupportMatrix`, and add a test. Also make the server message plain words. Until then, the editor should say which station blocks the save at the moment the author adds a 互斥组 or 彩蛋, not at save time.
2. **Video at the opening and the ending.** Recommend: one optional 开场视频 on the opening chapter and one 结尾视频 on the ending chapter, both short (≤ 30 s, ≤ 10 MB) mp4. Upload through the existing media review. Play full screen, tap to skip, poster frame first (no black flash), and fall back to the chapter text if it fails or the network is slow. Music resumes after. This needs a backend field, review for video, an editor row and a `<video>` layer. It is not a fix.
3. **Theme-wide background music.** Recommend: bring back one 主题配乐 row in 主题详情 (the player already plays `topicAudioUrl`, ducks it, and gives way to station music). Without it, the walk between stations is silent unless a station's music carries on.
4. **Locked screen / phone in pocket.** Recommend: add `"audio"` to `requiredBackgroundModes` and keep music and narration playing on `onHide` while a run is active (pause only on leave, exit or interruption). This affects WeChat review: the app must really play audio in the background, and it may then need the background audio manager for lock-screen controls. If not accepted, the phone script records what players get today: silence when the screen locks.
5. **Opening chapter entry (E20).** `openTopicOpening()` exists and is tested but has no button. Recommend: a 「序章」 item next to 添加章节, so an intro with only music and text is possible.
6. **One proof per station (P2).** Recommend: when a template already has a photo or scan game, that game is the station's proof, and the old 拍照上传/扫码 sheet does not appear.
7. **Easter egg triggers (E15).** Recommend: add 「到达某站」 as a trigger and allow an image or audio in the egg's content.
8. **Silent switch.** The station music follows the iPhone silent switch; the narration does not (`obeyMuteSwitch = false`). With headphones and the switch on, a player hears narration but no music. Recommend: music ignores the silent switch while a run is active, like the narration.

## Reported for k8 (j3's game bugs)

E10/E16 is the same family as j3's 「generic themes refuse photoCheck…」 and is still there. The k8 lane did not run this round.

## Files

- Play: `chengyinhub-xcx/pages/play/index.js`, `index.wxml`, `components/advanced-game/index.js`
- Editor: `chengyinhub-xcx/pages/publish/temp/index.wxml`, `fabu/index.wxml`, `fabu/runtime-structure-editor.wxml`, `fabu/theme-runtime-structure.js`, `utils/publish/pro-editor-policy.js`
- Tests and gates: `chengyinhub-xcx/tests/unit/k10-immersive-audio.test.js` (new), `tests/unit/gameplay-feedback-images-bgm.test.js`, `scripts/uiaudit/action-ledger.json`, `tests/lint/ui-integrity-baseline.js` (two line keys)
- Docs: `integ/k10-theme-check.md`, `integ/k10-phone-script.md`, `integ/fix-shots/k10-*.jpg` (31 shots, all viewed), `integ/proof/k10-walk/` (walk scripts, rig diff, stand-ins, running notes)

## Verify

On the rebased tree (upstream `f522c665` + this commit):
- `node --test` on `k10-immersive-audio`, `gameplay-feedback-images-bgm`, `fabu-theme-runtime-config` (48 pass) and the other worker's `play-audio-resume` (all pass with mine): 0 fail. The other worker made the same `onShow` → `resumeAll()` fix (`a2285b3d`); I kept their line.
- `ui-integrity.js`: `UI-GATE-0:PASS`.
- `build-action-ledger.js --check`: `ACTION-LEDGER PASS: 5729 controls`.
- The full unit suite (16,211 tests) was run before the rebase, as described above.
- CI: the push of this commit to `integ/launch-test-20261103` is the CI run for all of this lane's work. I did not wait for it to finish.
- No migrations, no backend Java, no `@Test` change in a gated suite, so `ci/funds_it_gate.py` is untouched. Main package: about 2 KB of JS/WXML added to `pages/play/index.js` and the fabu/temp pages. Not re-measured with preflight.
- Secret scan of the diff: no keys. The rig stand-ins use fixed dummy values (`rig-offline-not-a-secret`).

STATUS: PARTIAL - the owner's full game cannot be built yet: no opening/ending video, no theme-wide music, and photo stations cannot be combined with branches or easter eggs (E16 blocker, needs owner); easter eggs, the locked screen, weak network, real headphones and production themes 990068/990069 were not walked or heard
