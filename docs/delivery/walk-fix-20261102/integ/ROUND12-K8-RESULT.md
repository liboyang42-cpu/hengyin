# Round 12, lane k8-play: result (second start)

This replaces the first start's result. The first start stopped when it couldn't start Docker. This time `dockerd` started.

- Branch: `integ/launch-test-20261103`, worked on a private local branch, with `pull --rebase` and `git show --stat` before every push. No force-push.
- Started from: `ca1ddd82`.
- Last code push: `dfd490a4`.
- Findings source: `docs/delivery/check-20261105/j3-player-game/REPORT.md` (j3 second pass).

**Overlap with k7.** A lane labelled k7 worked through the same j3 report at the same time and pushed first. It covered N4, N1, N2 and N3 in part, then N3, N5, N6 (with F9), N7–N10, N14–N16 and parts of F14/F16. See `ROUND12-K7-RESULT.md`. Once I saw that:
- I stopped my own N5 and N6 work, which had been running in parallel, and dropped it.
- I dropped my duplicate N3 changes.
- I kept only what k7 had not done, plus what my rig walks of k7's fixes turned up.

**Rig, not a phone.** The rig is `claude/cloud-walk-20261101-rig` plus j3's `tools/rig-changes.diff`:
- It runs the real jar on real MySQL 8 and Redis 7, with the real mini-program in headless Chrome at 390×844.
- The rig candidate was moved with each step: jar built at `ecbbcd8f` (it includes my backend fix and k7's N5 Java); the browser bundle was rebuilt at `4242c9b7` for the N4/N2/N7 walks and at `a8daa8d8` (my local head) for the mic walk.
- Fixtures come from j3's own tools (`author2.py`, which uses the editor's config functions, plus `approve.py`, `fx_onsale.py` and `buy_api2.py`). My helpers are in `proof/k8-tools/`.
- Shots are in `fix-shots/k8/`. I looked at every shot I cite.
- Fixture themes:
  - 990014: 状态
  - 990015: d20 检定
  - 990016: plain, 9 games
  - 990017: 喊一嗓子 / 安静挑战
  - 990018: 248-like
  - 990019: 267-like

## Found while walking: a blocker in k2's "solved station on reopen" (fixed)

- **What broke.** In every theme with 状态 or d20 检定, a fresh player could not get past the first station:
  - `/api/play/arrive` answered 409 「请先开始当前任务」.
  - The task row said 「尚未到达」, so its 开始任务 stayed disabled.
- **Cause.** The arrive path asks `AdvancedGameRuntimeServiceImpl.solvedForArrival` (k2's `b1616412`) whether the station's game is already solved. With no task started yet, finding the task instance throws 409. That read-only question was passing the 409 on to arrive.
- **Fix.** A 409 there now means "not solved", and the normal location fence applies. Other errors still propagate.
- **Files:**
  - `AdvancedGameRuntimeServiceImpl.java`
  - new `SolvedForArrivalNoQuestTest` (2 tests): it fails on the old code with `ServiceException: 请先开始当前任务` and passes on the new.
  - `ApiPlayGpsGeofenceValidationTest` (23 tests) passes.
- **Rig proof.**
  - Before the fix: `arrive` returned 409 「请先开始当前任务」 on 990015.
  - After the fix: `arrive` returned 200 「已记录本局到达，请继续当前任务或操作」, then `QUEST_START` 200 and `advanced/start` 200.
- This is in k2's area. I took it because it blocked every 状态/检定 walk in this lane.

## Items

| Item | Status | What changed | Files | Proof | Unverified |
|---|---|---|---|---|---|
| **1. N4** D20 检定 black screen | **fixed, walked** (k7 fixed the black screen; I fixed what was left) | k7 made the 检定 card render. In the rig, only the card's head showed on opening (「检定 / 简单 标准 困难」, at y≈450). The die and 「按住摇骰，松手掷出」 sat below the fold (y≈1089), so the player still saw no roll button. The inline card now scrolls into view, the way inline games do (`chapterGameAnchor` set on placement, then again after 1.2 s). The fail-safe that swaps an inline card that never shows for the full-screen card stays as k7 wrote it. An exact hit read 「过线 0 点」 and now reads 「正好过线」. | `pages/play/utils/r5-player-runtime.js`, `components/playkit-journey-check/index.js` | Before: `k8-N4-before-card-below-fold.jpg`. After: `k8-N4-after-reopen-card.jpg` (die and roll button on screen); `k8-N4-after-rolled.jpg` (`ROLL_CHECK` 200; 达成值 13 · 难度 13). Unit tests in `r12-k8-play-fixes`. | Phone: canvas die, hold-to-roll, scroll-into-view on a real WebView |
| **2. N1** 预测 stamp on the wrong card | **fixed by k7**; not re-walked by me | — | — | k7's `k7-N1-after-B-sent-seal-on-B.jpg`. My rig taps couldn't hit the sliced cards, so my own N1 shots were dropped. | — |
| **3. N2** `playkit-estimate` lock and the hidden real value | **fixed, walked**: lock by k7; the fail screen value by me | The fail sheet now shows 「你猜 X 米 · 实际 Y 米」. The server already sends `guess` and `answer` after submission, also when the session is FAILED. | `utils/playkit-view.js`, `components/playkit/index.wxml` | `k8-N2-after-fail-sheet.jpg`: guessed 18, answer 5, fail sheet reads 「你猜 18 米 · 实际 5 米」. Then 「重新开始本次玩法」 → `RETRY` 200 → wheel back. | — |
| **4. N3** 状态 theme | **fixed by k7** (fake 待审核, dead 提交 handled by an automatic cancel/start/reopen, 次数已用完 next to 开始任务, 0 / 0 格) | My duplicate fixes were dropped. The first-station deadlock above was the reason 状态 themes couldn't be walked at all at `ca1ddd82`. | — | k7's N3 shots. Mine: arrive and quest start now work on a fresh player (above). | `player_node_progress` after settlement (k7 lists it too) |
| **5. N5** editor offers games 状态/检定 themes can't publish | **fixed by k7** | My parallel N5 work was stopped and discarded. | — | k7 | — |
| **6. N6** solo pass 已完成, dates, ASCII comma | **fixed by k7** | My parallel N6 work was dropped. | — | k7 | — |
| **7. N7** 结果反馈 screens | **fixed, walked** (dark verdict by me; zero rows and helper line removed by k7) | The standalone result feedback (`pv--closable`) uses the player's dark background. The verdict colour is only on the tick and the word, and the button is dark. The zero 「得分 0 / 已完成 0 项」 rows and the helper line are gone. | `components/play-verdict/index.wxss`, `components/playkit/index.wxml` | `k8-N7-after-fail-verdict.jpg` (red 「失败」 on black, author reply, dark 继续), compared with j3's `50-t2s1-d` (full-screen red). | A failure still shows two screens, verdict then fail sheet. Merging them changes the retry flow; I left that for the owner. |
| N8, N10 (no NO. 0000, card not empty, full-width ？), N14 (shop line), N15 (？), N16 (计步 0 kg), F14, F16 (拍好了) | **fixed by k7** | — | — | k7 | — |
| **N9** 精准停表 tries off by one after reopen | **fixed, unit only** | The stage counted tries from full on every reopen. The server's `attempts` is now passed through as `used`, so tries left = tries − used. The local decrement on a miss happens before submit, and the server value then lands on the same number, so a miss isn't counted twice. | `utils/playkit-view.js`, `components/play-stage/index.js`, `components/playkit-stopwatch/index.{js,wxml}`, `components/playkit/index.wxml` | Unit tests: tries 3 / used 2 gives 1 left; `play-stage` and stopwatch suites pass. | Rig walk: the stopwatch is station 6 of 990016 and I didn't play through to it |
| **N11** 相册 | **fixed, unit only** | 「拍照或选择照片完成相册」 is now the main (dark) button and 「收起相册」 the outlined secondary one. After a photo is submitted with the album open, the album closes after 1.2 s, so 继续故事 shows. Reopening a finished album only to look doesn't auto-close. | `components/playkit-album/index.{js,wxml,wxss}` | Unit tests; `play-r5-album-feedback` passes | Rig walk: needs j3's OSS stand-in for a real upload, which I didn't set up this run |
| **N12** 参与人信息 phone ignored | **fixed, unit only** | The form reads the bound WeChat phone and shows it read-only; that number is saved with the participant. With no bound phone, typing still works, because the session checkout sends the participant's phone. No backend change. Older participant rows keep their old phone until they are saved again. | `pages/addressinfo/addressinfo.{js,wxml}`, test count fix in `addressinfo-state-contract` | `r12-k8-n12.test.js` | Rig walk |
| N13 led session map and title | **not done** | Needs the led-session fixture (j3 `l6/fixtures.py`, director runtime). Not started. | — | — | all |
| **N14** 「尝好了，松手睁眼」 after answering | **not a bug** | The line sits in the eyes-closed layer, which is `opacity:0` and `aria-hidden` once the eyes open. j3's own shot `36-t1s15-e` doesn't show it; the walker's text dump read hidden DOM text. | — | j3 shot 36-e | — |
| N15 first 继续故事 tap after the flip | **not done** | Not reproduced this run. | — | — | all |
| N16 PCM / 「本机成绩」 captions | **needs owner** (as k7 says) | Pinned by the C1 trust-boundary tests as honesty notices. | — | — | — |
| **N17** 「总积分排名 0」 | **needs owner, unchanged** | The owner chose 「没数据显示0」 (P-22, `e166ea23`), and `profile-first-run-state-contract` pins it. j3 asks for the opposite. If the owner wants it hidden, the change is in `subpackageMember/components/cy/profile/index.js` (around line 1477) and that test. | — | — | — |
| **N18** first tap on 出发 does nothing | **not a bug** (rig tap target) | Reproduced in the rig. The first tap never reaches `continueChapterStory`, because the 序章 gate covers the screen for its first seconds and says 「轻点跳过」. The walker tapped the 「出发」 text underneath, so the tap went to the gate. | — | `k8-N18-gate-over-opening.jpg`; wrapped-handler count 0 after the first tap, 1 after the second | Phone |
| **F13** 票夹 card | **fixed, unit only** | With no cover image, the card no longer falls back to `/images/route_city_cover.png` (the five-point route and grey bars) and shows no image block. 「生成中…」 shows only while the entry code is actually loading. | `subpackageMember/signup/index.{js,wxml}` | `r12-k8-f13.test.js` | Rig walk |
| F16 rest | **part** | 拍照打卡 「拍好了」 is k7's. D20 加值's 「继续」 is left as is: every full-screen game closes its own panel with 「继续」, and 「继续故事」 belongs to the story flow below it. Grey 再想想 + 提交, 猜图 verdict and stacked buttons: not done. | — | — | — |
| F17 author's 揭示答案 | **not done** | The server projection has to carry the 文字问答 answer after the station is finished (k7 says the same). | — | — | — |
| F18 editor look sheet | **not done** | — | — | — | all |
| **8a. Mic denial reason** (k2 item 5) | **fixed, walked** | 安静挑战 and 喊一嗓子 now show 「需要授权麦克风才能开始」 when the mic is refused, in the same form as 计步's 「需要授权微信运动才能计步」. Starting again clears it. Failure reports to the server are unchanged. | `components/playkit-quiethold/*`, `components/playkit-shout/*` | `k8-mic-denied-1.jpg`: denial injected in the rig's recorder (HARNESS seam: `start` fires `operateRecorder:fail auth deny`, `getSetting` reports `scope.record:false`); two `REPORT_SENSOR_FAILURE {denied:true}` 200, then 跳过这一站 offered | A real phone's permission dialog |
| **8b. Re-walk of the hidden main button** (k2 item 5) | **walked, PASS** | — | — | `k8-mic-skipped-no-start.jpg`: after `SKIP_SENSOR_STATION` 200 the screen reads 「这一站已跳过」 + 继续故事, and 「开始」 is gone | — |
| **Templates shaped like 248 / 267** | **walked; one finding** | Built from the editor's own functions (`gencfg2.js` `combo:`). With a result rule, the editor refuses both shapes: 「结果规则须且仅须一个主玩法」. Without one, both publish at this head. **990019 (相册 + 随机卡, plain):** played to the end (`DRAW` 200, arrive 200, finish card). The album half never shows, because 翻卡 takes the station, and the card shows its label (石阶卡), not its content. **990018 (罗盘 + D20 检定, 检定 theme):** the compass opens directly. Two failed reads (no compass in the rig), then skip, completes the station and the run ends. **The D20 检定 in the same template is never played.** If the live 248 really is "compass with check", a player whose compass doesn't read skips the check too. I didn't change this: whether a skip should still require the check is a game-rule question for the owner. | — | `k8-267-drawn.jpg`, `k8-267-after.jpg`, `k8-248-skip.jpg`, `k8-248-after.jpg` | A real compass reading on 248 (does the check follow a passed compass?); the live rows of 990068 / 990069 (no production access) |

## Tests and gates

- New `chengyinhub-xcx/tests/unit/r12-k8-play-fixes.test.js`, 14 tests:
  - N2 ×2, N7 ×2
  - N4 (scroll into view) ×1, N4 (正好过线) ×1
  - mic ×2
  - N11 ×1, N9 ×1
  - N12 and F13 are in their own files.
- Related suites pass, 0 failures: playkit-view, theme, sensor, album, play-stage, stopwatch, signup and addressinfo (379 in the last combined run).
- Java: `SolvedForArrivalNoQuestTest`, `ApiPlayGpsGeofenceValidationTest`.
  - I added `@Test`s only in a new class that isn't in `ci/funds_it_gate.py`, so `EXPECTED` is unchanged.
- `ui-integrity.js`: `UI-GATE-0:PASS`.
- `build-action-ledger.js --check`: PASS, 5718 controls (rebuilt for line shifts only; control count unchanged).

## CI

- My last code push `dfd490a4` started CI, but the CI run was cancelled by another lane's newer push.
- The next head, `528c130c`, contains all of my code. Its runs had just started when I wrote this, so I can't report a result yet.

## Not covered

- N13, N15, F17 and F18 are not done.
- N9, N11, N12 and F13 have no rig walk.
- A phone is needed for: the canvas die and hold-to-roll, real compass and mic levels, the permission dialogs, the camera, and the predict wheel's 3D seal.

STATUS: PARTIAL - N13, N15, F17, F18 not done; N9, N11, N12, F13 fixed with unit tests but not walked in the rig; N16, N17 and the 248-shape check-after-skip need the owner; CI result on a commit with this work not yet read
