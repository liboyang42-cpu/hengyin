# Round 13, lane m1-game-matrix: result

Branch `integ/launch-test-20261103`. Worked on a private local branch, rebased onto the branch tip just before pushing. Run 2026-11-05 as a scheduled cloud session.

**Not a phone.** Docker started in this container (`dockerd` 29.9.2). The walk ran in the cloud rig: the real jar from this branch on real MySQL 8.0.45 and Redis 7.4, and the real mini-program in headless Chrome at 390×844. Rig notes are in `integ/map-shots/m2-rig-notes.md`, section 「Round 13 · m1-game-matrix」.

## What was really wrong

I authored a theme on screen (「分岔雨巷」, topic 990014, author 9003): one chapter, two stations, 左巷 · 硬币摊 (抛硬币) and 右巷 · 骰子摊 (掷骰子). Then I added a 互斥组 with both stations.

- The save was refused: 「「左巷 · 硬币摊」的抛硬币：开了状态或 d20 检定的主题里，这种玩法要带结果规则才能确认完成。请在这一站重新打开玩法设置，保存一次后再发布」. The screen only showed 「待确认」 (`fix-shots/r13-m1-a20-saveresult.jpg`).
- Following that advice did nothing. Reopening the station did not add the rules, because the editor only treats a station as a task station after a save has succeeded. The theme was stuck.
- A chapter rule (完成要求, 互斥组 or 额外站点) makes the server turn every station into a personal task. The task is then settled by the existing settlement path that already exists for these games: the game's result rules (`outcomeRules`), judged on the server and recorded through `recordGameplayOutcome`. Every NO_BRIDGE game except the six phone-measured ones, 预测 without a shop, and 分支剧情 already has that path (`r5CompletionProof`), with runtime tests (`AdvancedGameRuntimeServiceImplTest.r5MainFamilySettlesOnlyItsServerResult`, `r5PhotoMustVerifyExistingUploadBeforeAccepting`).
- So the missing piece was in the editor. **I did not flip any flag in `GameplaySupportMatrix`.** Every row is the same as before, and no server check was loosened.
- Easter eggs: after the branch saved, publishing was refused with 「通用局章节彩蛋触发尚未接通」. Another worker (`a8b142029`, 「个人通用局按章节时钟与认证步数触发彩蛋」) wired eggs into personal task runs while I was working. I had made the same change, so I dropped mine and kept theirs. The walk below runs on their fix.

## What I changed (commit 「fix: 加互斥选项或完成要求的主题里每一站按任务站结算…」)

1. **The editor knows about chapter rules** (`pages/publish/utils/publish/gameplay-support-matrix.js` `context`, new `chapterTasks`). In a theme whose chapters have a 完成要求, 互斥组 or 额外站点, every station counts as a task station from the start. This is the same test the server uses (`TopicRoutePublicationCompiler.hasChapterRequirements`). A theme with only eggs doesn't count.
2. **Reopening a saved station fixes it** (`pages/publish/temp/index.js` `_settleLoadedGame`). It adds the result rules the same way as when the game is first picked. It sets 参与方式 to 定位到达, unless the author already chose 定位到达 or 扫码. It also marks the station unsaved, so the author only has to tap 保存并绑定.
3. **The author is told at the moment they add the 互斥组, naming each station** (`pages/publish/fabu/theme-runtime.js` `_chapterTaskStations`, called from `_saveChapterRules`):
   - If a station can never be confirmed, the 互斥组 is not added. A red toast names the station and says why, in plain words:
     - 「「球场」：这种玩法的成绩只在手机上测，确认不了玩家真的完成，请换一种玩法」
     - 「「X」：预测要由承接商家给答案，没有承接商家的站点在这里不能用」
     - 「「岔口」：这种主题里每一站都要能确认玩家完成，这种玩法确认不了，请换一种玩法」
     - 「「路口」：这一站的参与方式要选「定位到达」或「扫码」」
   - If stations only need one more save, the rule is added and a dialog 「还差一步」 lists them: 「「左巷 · 硬币摊」「右巷 · 骰子摊」要重新打开玩法，点一次保存，才能和这一章的设置一起用」 (`r13-m1-b02-resave-notice.jpg`).
   - These are notices at the moment of the action. No new captions were added to the page.
4. **Game list in task stations** (`pages/publish/fabu/index.js` `_refreshSupportMatrix`): it now also lists games that can settle once the result rules are added. Before, those games were hidden.
5. **Phone-only games follow C1.** The six games whose score is measured only on the phone (变色就点, 弹球, 安静挑战, 罗盘, 喊一嗓子, 精准停表; the same list as the server's `SELF_REPORTED_SECTIONS`) are refused in themes with chapter rules. `SensorGameTrustBoundaryTest` is unchanged and passes. In state or d20 themes without chapter rules (k7's case), nothing changed.
6. Wording: 「开了状态或 d20 检定的主题里不能用这种玩法」 was wrong for themes with chapter rules. It is now 「这种主题里每一站都要能确认玩家完成，这种玩法确认不了，请换一种玩法」. The k7 test that pinned the old wording was updated to the new text; the check itself is unchanged.

Tests:
- `chengyinhub-xcx/tests/unit/r13-m1-chapter-task-games.test.js` (new, 4 tests): each of the 23 NO_BRIDGE games in a theme with a 互斥组 and an egg. Either it gets result rules and passes the pre-publish check, or it is refused with the plain reason. Also covered: the add-time check names stations, uses no internal names, and refuses or lists correctly; reopening a station fills in the rules once and marks it unsaved.
- `TopicRoutePublicationOutcomeScopeTest.resavedGameWithBranchAndEggSavesAndFreezes` (new, 11 cases): 抛硬币, 掷骰子, 翻卡, 猜数字, 猜图, 找东西, 角色建档, 留言, 输入挑战, 相册, 拍照审核. Each has result rules, 定位到达, a chapter rule and an egg; each one compiles to a task and freezes for publishing. No `@Test` was added or removed in a gated suite, so `ci/funds_it_gate.py` is unchanged.
- Run on the rebased tree: `TopicRoutePublicationOutcomeScopeTest`, `PersonalGameplayRuntimeServiceTest`, `SensorGameTrustBoundaryTest`, `GameplaySupportMatrixTest`, `GameplaySupportMatrixRuntimeTest` all green. JS: the new test, `r12-k7-play-fixes`, `gameplay-support-matrix-ui`, `generic-gameplay-r5-editor` and `gameplay-reskin-publish-roundtrip`: 133 pass, 0 fail. `ui-integrity.js`: `UI-GATE-0:PASS`. Only three existing temp line keys moved; no new entries; digest recomputed. `build-action-ledger.js --check`: PASS, 5743 controls.

## Per game

「Walked」 means authored on screen with a 互斥组 and an egg, published, and played in the rig. 「Not walked」 means only the code and tests above.

| Game | Status | Proof | Unverified |
|---|---|---|---|
| 抛硬币 coin | **DONE, walked** | Authored before the 互斥组 existed. Adding the group named the station. I reopened it: rules and 定位到达 were filled in (`r13-m1-b03`), and one save fixed it. The theme then saved with the group and the egg, published (`publish-draft` 「已发布」), and was approved through the real `/business/audit/review`. Player 9009: 开始任务 → flip 正面 (`r13-m1-d08`) → 结算任务 → task PASS, `lastOutcome.settled:true`, run finished (`r13-m1-d12`). The egg 「墙上的涂鸦」 fired after 1 minute and shows under 线索 (`r13-m1-d14`). **No double pay:** replaying the same flip and the same 结算 returned the stored answers, and the session version, state hash and action count were unchanged. A new flip was refused, with no state change. | A real phone. A reward item on the egg (this egg had none). |
| 掷骰子 dice | Authored on screen with the group and the egg; saved and published in the same theme. **Play not walked.** | Same editor path as 抛硬币 (reopened, rules added, saved). Freeze test passes. The r5 runtime test already exists. | Playing it: the player could not choose the second branch station (finding P1). |
| 猜数字 estimate, 猜图 pricePair, 找东西 hidden, 翻卡 random, 角色建档 profile, 留言 note, 输入挑战 typeIn, 拍照审核 photoCheck | Code path done, **not walked** | Editor JS test plus the freeze test for each. Existing r5 runtime tests cover 猜数字, 猜图, 翻卡 and photo upload ownership. | All screens. |
| 拍物成卡 objectCard, D20 check, D20 加值 d20, 计步 steps, 倒计时 countdown | Code path done in the editor, **not walked** | Editor JS test (gets result rules, passes the pre-publish check). | No freeze test. D20 also needs the theme's 「d20 检定」 switch. 计步 relies on WeChat step data, which the server treats as trusted (not on the C1 list). |
| 变色就点 react, 弹球 shake, 安静挑战 quiet, 罗盘 compass, 喊一嗓子 shout, 精准停表 stopwatch | **Cannot have one.** Their result is measured only on the phone (C1). The editor refuses the 互斥组 and names the station. | JS test of the refusal text and that it names the station. C1 tests unchanged. | The refusal toast on screen. |
| 预测 predict | Only with a 承接商家, because the shop gives the answer. Without one, the editor refuses and names the station. | JS test. | Screens. A shop-run prediction inside a branch theme. |
| 分支剧情 branch (as a station game) | **Cannot settle on its own.** The editor refuses and names the station. | JS test. | Screens. |

## Findings from the walk (not fixed, other lanes)

- **P1, HIGH, play: the player cannot take the second branch.** Linear route, 互斥组 on both stations. Player 9004 stands at 右巷 · 骰子摊 (station 2):
  - The story sheet only offers 第 1 站 左巷 · 硬币摊 (`r13-m1-e07-map`).
  - In the backpack's 任务 tab both tasks say 「尚未到达」 and 开始任务 is grey (`r13-m1-e06-start2`).
  - No arrival request is ever sent for station 2. `_arriveTarget` only covers the sheet's or the next station.
  - So in practice a branch is always the first station. Needs the play lane.
- **P2, editor: stuck at 「待确认」.** After a refused save, I fixed the stations and pressed 保存草稿 again in the same session. It only re-checked the old save and sent nothing new (「云端保存的版本尚未确认…重试只核对原主题」). Reloading the editor fixed it.
- **P3, wording:** the server's refusal says 「开了状态或 d20 检定的主题里…」 even when the author only added a 互斥组. It is `GameplaySupportMatrix.fail` and `personalArrivalRefusal`. I left the server text alone; the editor now speaks first.
- **P4, layout:** in 背包 › 任务 after the run ends, the 「通过」 pill overlaps the line 「本局已结束，仅可领取待领奖励」 (`r13-m1-d12`).
- **P5:** after 发布, the editor landed on `merchantinfo` saying 「当前账号无权查看」.
- **P6:** a second coin flip after the task is done is answered 「请先完成上一站」. Nothing moves, but the words are wrong.

## Verify

- CI: pushed at 11:32 UTC as `54df2df65` (fix) and `1dc29f8f0` (this doc). The doc commit is `[skip ci]`, like the branch's other batched pushes, so that push started no CI run. Every check listed above was run locally on the rebased tree. **No CI result for this lane yet.**
- No migration added. The rig DB needed the branch's existing `migration_20261105_chapter_video.sql` (applied as is).
- Secret scan of the diff: no keys. The rig stand-ins use dummy values.

STATUS: PARTIAL - the chapter-rule path is fixed and walked end to end only for 抛硬币; 掷骰子 is authored but not played because players cannot reach a second branch station (P1, play lane); the other games are proven by tests only, and the six phone-measured games, 预测 without a shop and 分支剧情 cannot settle and are refused by name
