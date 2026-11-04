# Round 11, lane g1-play: result

Branch `integ/launch-test-20261103`. My code commits, in order: `6bd80422` (item 1), `3c056cf3` (item 2), `3245b215` (item 3), `0928d192` (item 5), `6e6bd54f` (item 4). This file is pushed on top of them.

**This lane has no rig walk.** I did not set up the walkthrough rig and there are no screenshots under `integ/fix-shots/` from me. Everything below is checked by unit tests (mini and backend) and the static gates. A rig walk and a phone must still confirm each visible change.

## Checks I ran

- Mini unit tests, full run on `6e6bd54f`: 16,123 tests, 16,122 pass. The one failure is `tests/unit/r11-g3-order-truth.test.js` ("订单详情:自玩通行证只说一次不可退款…"). That is lane g3's new test of the order detail page. I did not touch those files.
- An earlier full run also showed `std-g2-wizard-shell-contract` and `wt2-player-scene-fixes-contract` failing. Both failed the same way with my changes stashed, so they come from other lanes.
- `node tests/lint/ui-integrity.js`: `UI-GATE-0:PASS` on `6e6bd54f`.
- `build-action-ledger.js --check`: PASS, 5,705 controls, on `6e6bd54f`.
- Backend tests: `mvn -pl chengyinhub-admin test` for the changed areas, all green. That covers `AdvancedGameRuntimeServiceImplTest` (210 tests), `AdvancedGameConfigValidatorTest`, `TopicPublishCheck*`, `TemplatePublish*`, `PlayKitPhotoCheckRuntimeTest`, `AdvancedPhoto*`, `*PhotoCheck*`, `Predict*`, `PlayNodeCompletion*`, `ClubLead*` and `GameSessionRuntime*`. I did not run the full backend suite.
- Main package by preflight's count: 3,106,263 bytes, 39,465 under the ratchet. Only the `pages/roam` edits are in the main package (+1,173 bytes). `pages/play` and `pages/publish` are subpackages.
- No migrations.
- **CI: not confirmed green.** Every run on this branch while I worked was cancelled by another lane's push within minutes (for example runs 41898241379 and 41898492895). I found no quiet spell, so I have no green CI run on a commit containing all my work. Someone needs to re-run CI on a head that includes `6e6bd54f` once pushes stop.

## Items

### 1. j3 F1: a closed 预测 station no longer stops the route. Status: fixed.

- **Server.** New action `SKIP_CLOSED_PREDICT`. The server allows it only when all of these hold:
  - the player has not chosen;
  - the round is closed: past `closeAtHour` (Asia/Shanghai), or today's round is already settled or voided;
  - the station has no `outcomeRules` and is not a recovery challenge.
- **Skipping gives no reward.** It writes `predict.skipped`, which counts as done in `mechanicsReady`. The section's xp is not given. The completion chain records `SKIPPED` with 0 puzzle score, through the existing `sensorStationSkipped`. Rewards are capped to exploration value and badge through `untrustedCompletionEvidence` (`predict:SKIPPED_CLOSED`).
- **View.** The predict view now sends `closed`, `skipped` and `shopAnswers`. `shopAnswers` is true when the station's node has a merchant (`merchantMemberId`), who is the only role that can settle a round.
- **Client.** When the round is closed and the player hasn't chosen, the hint reads 「今天这一轮已经截止」 and the one button is 「不选，继续」. Without a shop:
  - the wait line says only the closing time;
  - the 「等商家给答案」 block is gone;
  - the void text no longer names a shop.
- The error text 「今天这一轮已经截止,明天再来」 is now 「今天这一轮已经截止」 (no ASCII comma; the player is no longer told to come back).
- **Files:**
  - `AdvancedGameRuntimeServiceImpl.java`
  - `AdvancedActionJudgement.java`
  - `playkit-predict/index.{js,wxml}`
  - `playkit/index.wxml`
  - `playkit-view.js`
- **Proof:**
  - `AdvancedGameRuntimeServiceImplTest` `r11ClosedPredictCanBeSkippedWithoutReward`, `r11OpenPredictCannotBeSkipped`, `r11SettledRoundClosesPredictEvenBeforeCloseHour`.
  - `tests/unit/r11-g1-predict-closed.test.js` (5 cases).
  - Ledger tests: action-ledger counts +1 tap and +1 event contract, recorded in the test.
- **Unverified:** daytime play and a shop's next-day answer; any rig or phone walk.
- **Needs owner:** a 预测 station that has `outcomeRules` (按结果分支) still waits for the shop's answer before it completes. If the theme has no shop, the round voids and the station never closes. Today the editor only produces this when the author edits 结果反馈 on a 预测 station. Recommended default: the editor stops offering 结果反馈 on 预测 when the theme has no shop.

### 2. j3 F2: D20 三档难度 must be playable. Status: fixed (stored themes: listed, not changed).

- **Cause.** The backend counts `check` as a station game only when `outcomeRules` exists (`hasEnabledMechanic`). Without it, a check-only template can still work as an R14 journey check, but only when the theme turns 检定 on. The editor's picker added `outcomeRules` only for 输入挑战.
- **Editor.** Picking `check` or `album` now adds `outcomeRules` straight away, in the same place and the same shape as 输入挑战 (`publish/temp/index.js`, `OUTCOME_REQUIRED_SECTIONS`).
- **Validator.** New `AdvancedGameConfigValidator.outcomeRulesMissingReason(json, journeyCheckEnabled)`. Theme publish check (`TopicPublishCheckService`) now reports a template that can't open as an error:
  - 「D20 三档难度请配置通过和没通过之后」 when the theme's 检定 is off;
  - 「相册请配置完成之后」 always.
- **Stored themes.** Stored themes are caught the next time they go through the publish check.
- **Where I put the gate.** Template saves are not blocked, and the client validator is unchanged. The repo's tests treat a check-only config as a valid R14 journey check, and album as a story media block. A blanket save gate broke 15 of them, so I put the gate where the theme context is known.
- **Every picker game type, as the editor makes it.** I took all 32 picker types through the editor's own `applyToConfig` → `validate` → `serialize`, then the backend's `validateAndNormalize` → `hasEnabledMechanic`.
  - Two types save but can't start: `check` (D20 三档难度) and `album` (相册). Both are fixed as above.
  - 分支剧情, 找东西 and 拍物成卡 are refused by the client until the author fills them in. That is correct.
  - Every other type saves and starts.
- **Proof:**
  - `AdvancedGameConfigValidatorTest.r11CheckOrAlbumAloneWithoutOutcomeRulesIsUnplayable`.
  - `tests/unit/r11-g1-check-outcome-rules.test.js`.
  - The probe output above (scripts in my scratchpad, not committed).
- **Not done:**
  - I have no production access, so the list of stored check-only or album-only templates on live themes is not checked. Read-only query for the owner: `SELECT id, member_id FROM cms_member_template WHERE del_flag=0 AND advanced_config_json LIKE '%"check":{"enabled":true%' AND advanced_config_json NOT LIKE '%"outcomeRules"%';` (then the same with `"album":{"enabled":true`). Change nothing.
  - D20 三档难度 not played through in the rig.

### 3. j3 F3: the photo check follows the server's `fallback`. Status: fixed.

- **Server: when the check service is down (`degraded`) and fallback is `retake`.**
  - While tries remain, the player retakes, as before.
  - On the last try (`tries >= maxTries`, minimum 1), the server lets the player go on: `passed=true`, verdict stays `DEGRADED`.
  - That pass is not "verified": no xp, rewards capped through `photoCheck:FALLBACK`.
  - A 按结果分支 station records 没过 (`REJECT`) and follows the author's 没通过之后.
- **Client.**
  - Only server `passed` closes the station. `flagged` is a mark for the merchant, not "done".
  - Degraded with no pass: 「这次没能审，再拍一张」 plus the server's reason, and the button stays 「再拍一张」.
  - Degraded with a pass: 「这次没能审，先往下走」.
- **Tests updated, with reasons:**
  - `AdvancedGameRuntimeServiceImplTest.r5PhotoAndAlbumUseVerifiedServerEvidence[DEGRADED]` pinned "degraded never concludes, stay on the station". The owner's rule "a station must never strand a player" replaces that.
  - Two mini tests pinned "flagged = done": `playkit-profile-view-contract`, `playkit-photocheck-frame-contract`. That contradicts the server, whose `mechanicsReady` needs `passed`.
- **Files:**
  - `AdvancedGameRuntimeServiceImpl.java`
  - `playkit-photocheck/index.{js,wxml}`
  - `playkit-view.js`
- **Proof:**
  - `PlayKitPhotoCheckRuntimeTest.降级且重拍机会用完时放行往下走_不算核验也不给分`
  - new case in `playkit-photocheck-server-judge-contract.test.js`
- **Unverified:**
  - rig walk with a stubbed vision service;
  - photo preview frame (HARNESS in j3).
- **Needs owner:** a 按结果分支 photo station now takes the 没通过 branch after the last try when the service is down. If that branch has costs (hp), the player pays for an outage. Recommended default: keep it, since the alternative is a dead end; or tell me to add a "skip without costs" path.

### 4. j3 F4, F5, F6

**F4. Wait-for-release under the director runtime. Status: fixed.**
- **Cause.** `/api/club/lead/team-progress` reported `status` and `currentChapterId` from the old `club_lead_state` row. Under the director runtime, chapter release is written to `game_session.current_chapter_id`, and the old row stays at status 1 with no chapter. So the player page's `leadHoldsChapter` never held a chapter.
- **Fix.** Added `IGameSessionRuntimeService.routeCurrentChapterId`. While the route session is RUNNING, team-progress reports status 2 and the runtime's chapter, along with the next chapter and the chapter name.
- `ClubLeadFlowTest.teamProgressUnderDirectorRuntimeReportsRuntimeChapter` covers it.
- **Unverified:** the 「等队长放行」 screen and 返回地图 in the rig. The client hold check compares chapter order from `chapterCards` with the server's chapter order (by sort). I believe they match but did not prove it in a walk.

**F4, header count. Status: fixed.**
- The number is the arrival count: gather check-in, redemption, or any station progress. That rule is C-10, which is why it moved when a station was finished.
- The text 「带领中 · 1/2 人」 now reads 「到场 1/2 人」.

**F5. A finished theme reopens on its ending. Status: fixed.**
- When `rebuild` reports `allDone`, the play page's first screen opens `openFinish()` instead of the 「开始游戏」 intro. `allDone` comes from the server's route state, including the branch statuses COMPLETED and FINISHED.
- `openFinish()` plays the 尾声 ending page when the theme has one, then shows the finish card. 继续 goes back to the detail page, as before.
- `tests/unit/r11-g1-finished-reopen.test.js` covers it.
- Unverified in the rig.
- Free exploration (mode 2) is unchanged.

**F6. Solved station completes on reopen without walking back. Status: not done. Needs owner.**
- Advanced games have no geofence. The `/api/play/arrive` geofence after a solved game is the only server proof that the player was at the station.
- Completing on reopen without walking back would remove that proof for every advanced station.
- A sound fix needs one of these:
  - record a server presence proof when the player is inside the fence: at 开始任务, or with the solving action;
  - the owner accepts "solved = done" for these stations.
- I did not change the gate. Recommended default: record the presence proof when `arrive` returns `ADVANCED_FIRST`, because the geofence has already passed at that point. Then accept that proof on the later arrive. This needs a place to store the proof, probably a column or table, so the owner should decide.

### 5. j4 F7, F8, F11

**F7. Distance updates while walking. Status: fixed.**
- The location callback recomputes 「下一站 · Xm」 and the compass distance each tick. It only calls `setData` when the text changes.
- `tests/unit/r11-g1-story-dist.test.js` fails on the old code and passes now.

**F8. Finish sheet when nothing was done. Status: partly fixed.**
- **Label.** The label says 「本章完成」 only when every station of the current chapter is done, otherwise 「已结束」 (still 「已通关」 when the theme is finished).
- **Count.** The 「点亮坐标」 cell is hidden when the count is 0, so there is no giant 0.
- **Tests updated:** two tests pinned the old unconditional 「本章完成」 (`longrun-own-c01-13`, `play-ui-contract`).
- **Not done:**
  - On the first screen with a route card, the fixed 继续 button still covers the chapter heading. j4 shot 64.
  - The card title still scrolls under the status bar.
  - Both need a rig walk to fix without changing the share card's 3:4 image.

**F11. 确认到点 changes to its done state; colour and units match. Status: fixed.**
- **Done state.** After the server confirms, the place card shows a disabled 「已到达」 where the button was. It stays done when the card is reopened (`confirmed` comes from `poi.state` or the found list).
- **No +N on the button.** I left out the 「+10」 j4 suggested, because the xp is credited at finish, not at this tap.
- **Colour.** The shared `.topcard` skin now uses the same dark surface and text tokens as `roam-place-card`. It covers four prompt slots: 官方活动, 附近商家, 地标卡 and 漫游提示. This replaces the 9-08 decision 「漫游顶部一律白面」, per ROUND11's "card colour … match the neighbouring card".
- **Units.** The shop card's 「61M」 (a `61m` string upper-cased by CSS) is now 「61 米」, like the place card.
- **Proof:** new case in `roam-finish-write-order.test.js`.
- **Unverified:** how the dark top cards look in the rig and on a phone.

### 6. Remaining j3 findings and the 8 game types not played. Status: not done.

- F9, F10 and F12 are lane g3's (orders, levels, times), and g3 has commits for them.
- Not touched by me:
  - F13: 票夹 card;
  - F14: captions on the play intro, 序章, station sheet, tools menu, 排序, 连线, 分类, 精准停表 and legacy questions;
  - F15: 「这一生」 wording on other themes, the particle burst and `reducedMotion`;
  - F16: answer feedback consistency;
  - F17: 揭示答案 after a right answer;
  - F18: editor look sheet;
  - F19: 手写 font from fonts.gstatic.com. It needs the font hosted on our own static path, which is an owner or infra call.
- The 8 game types j3 couldn't play were not re-tried, because I had no rig: 找东西, 相册, 拍物成卡, 罗盘, 弹球, 安静挑战, 喊一嗓子, 计步, 九宫格.
  - Two blockers in front of them are now removed: F1 (预测) and F3 (拍照审核).
  - 找东西 needs the rig's image path.
  - The sensor games need a phone.

## Gate notes

- The UI gate baseline is keyed by line number, so my edits moved anchors in `publish/temp`, `roam` and `play`. I re-anchored them as the repo has done before: only the line number changes, the call line is word-for-word the same, there are no new entries, and the digest is recomputed. A note is at the top of `tests/lint/ui-integrity-baseline.js`.
- Lanes g2 and g3 re-anchored the same file at the same time. I merged by keeping both notes and recomputing the digest on the merged key set.
- Action ledger counts (`action-ledger.test.js`): +1 tap and +1 event contract for 「不选，继续」, recorded with a note.

STATUS: PARTIAL - no rig walk or screenshots; no green CI run (every run cancelled by other lanes' pushes); F6 needs the owner's decision on a presence proof; F8 button overlap not fixed; item 6 (remaining j3 LOW findings, 8 unplayed game types) not done
