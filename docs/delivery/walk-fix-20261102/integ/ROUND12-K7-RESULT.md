# Round 12, lane k7-game-fixes: result

Report fixed: `docs/delivery/check-20261105/j3-player-game/REPORT.md` (j3 second pass, tested at `573c463b3`).
Work started at `integ/launch-test-20261103` head `1467da34`, private local branch, `git pull --rebase` before every push.
Commits: `68f6661a`-line (N4 N1 N2 N3 part), `fea76d01` (N3 N5 N6 N7–N10 N14 N15), `fdaa614f` (N6 detail, F14, F16, N16, editor), and this doc.

**Rig, not a phone.** Cloud rig (`claude/cloud-walk-20261101-rig` + j3's `rig-changes.diff`), real jar on real MySQL 8 / Redis 7,
real mini-program in headless Chrome at 390×844 @2x. Fixtures from j3's own tools (`author2.py` building configs with the
editor's own functions, `approve.py`, `fx_onsale.py`, `buy_api2.py`); my walk helpers are in `integ/proof/k7-tools/`.
The backend in the rig was this branch's build (planned jar exploded, then this branch's `chengyinhub-*` jars and classes
overlaid, e2e stubs kept — see `proof/k7-tools/README.md`); the browser bundle was rebuilt from this working tree.
Shots are under `integ/fix-shots/k7-*.jpg`; I looked at every one I cite.

Another lane (k8) landed N2's terminal detail (guess and true value on the fail sheet), the N7 dark verdict screen and a
first-station arrival fix (`b4b2ad71`, `0c926f30`) on top of my first push. Those are k8's; I list them so nothing is
claimed twice.

## Items

| ID | Status | What changed | Files | Proof | Unverified |
|---|---|---|---|---|---|
| **N4** D20 检定 black screen | **fixed** | Real cause: for an r5 check, `advancedPlay.show` is true and `playKit.kit` is null, so the chapter layer `.chfull` is `hidden` (measured 0×0: probe in rig), its inline check paragraph can never be "in view" and stays `in:false`, and the advanced-game panel is also hidden because a check is open → nothing on screen. Now `.chfull` is not hidden while an inline check is open; if the chapter flow isn't open the check goes full screen; and a 2.5 s guard swaps an inline check that still isn't visible to the full-screen card, so a station never renders nothing. | `pages/play/index.wxml`, `utils/r5-player-runtime.js`, `index.js` (legacy inline path calls the guard) | Before: `k7-N4-before-black.jpg` (page data `journeyCheck.show:true`, paras `text:false,journeyCheck:false`). After: `k7-N4-after-check-card`, `-rolled` (roll 8 vs 13), `-settled` (没过线 → 继续); network `ROLL_CHECK` 200, `SUBMIT_CHECK` 200 | Phone (canvas die, touch hold) |
| **N1** 预测 stamp | **fixed** | The seal was pinned at the screen's right 16%/top 23% while the wheel kept turning after a pick. Now a pick parks that card in the centre and stops; commit snaps it centred; the seal sits on the centred card (not over the question). On reopen the server's `myOptionKey` parks the wheel on that card, sealed. | `components/playkit-predict/*` | `k7-N1-after-B-sent-seal-on-B.jpg`: tapped 不会, request `SUBMIT_PREDICT {optionKey:"B"}`, seal on 「不会」 | — |
| **N2** 猜数字 retry dead | **fixed** | `locked` only ever turned on; now a server reset (receipt back to not submitted) unlocks 「就这个数」. | `components/playkit-estimate/index.js` | Rig: `SUBMIT_ESTIMATE 1` → fail → 重新开始本次玩法 (`RETRY`) → `SUBMIT_ESTIMATE 9` sent (`k7-N2-fail`, `k7-N2-after-retry-wheel`). Hidden real value on the fail screen: k8 `b4b2ad71` | — |
| **N3** 状态 theme flow | **fixed** | (a) Bag no longer says 「待审核 / 凭证未通过」: server `PROOF_PENDING` only means "this station's game isn't passed yet"; the row reads 「打开任务」 and that button reopens the game (no duplicate 「打开原任务」). (b) Dead 提交: a quest station whose game is `FAILED` with no in-place retry now does what j3 found by hand — cancel the task (free while in progress), start it again, reopen the game — once per game session; a task with a cost choice opens the bag's task tab instead. (c) Pass already leads on: 继续故事 opens the bag on 「结算任务」. (d) Used-up task: no 「开始任务」 or 「剩 0 次」. (e) No 「背包装载 0 / 0 格」 / top-bar 0/0 / 「领取后的装载 0 / 0 格」 when the theme has no bag. | `utils/theme-gameplay.js`, `theme-backpack.wxml`, `utils/r5-player-runtime.js`, `index.wxml` | Fresh player on a 状态 theme: wrong (`SUBMIT_QA opt_2`) → 失败/继续 (`k7-N3-wrong-verdict`) → automatic `QUEST_CANCEL` 200, `QUEST_START` 200, `advanced/start` new session → fresh question (`k7-N3-after-fail-fresh-question`) → right (`SUBMIT_QA opt_1` 200, `k7-N3-right-after-restart`) → bag with 结算任务 and no 0/0 (`k7-N3-bag-settle-row-no-0of0`) → 确认装载 `QUEST_RESOLVE` 200 | `player_node_progress` row after settlement not re-read (i1/j3 saw status 0); the reward-preview helper line 「本次动作的全部奖励…」 is still there (`k7-N3-reward-preview`) |
| **N6** solo pass reads 已完成 | **fixed** | A pass's first scan is only the start of play: not finished and not expired → 「进行中」; timeline says 「开始游玩」 not 「首次扫码成功」. One date everywhere: 「可玩至 M 月 D 日」 (expiry at 00:00 counts as the day before) on the list, the detail hero, 凭证与有效期 and the theme's buy sheet; the theme's start date no longer shows as the pass's date; date-only list values never show 00:00. Refund reasons use full-width commas (「已开始，不可退款」), tests updated. | `utils/order-status.js`, `scene-member-order-detail/*`, `scene-member-order-history/index.js`, `pages/topic/utils/topic-motion.js`, `RefundPolicy.java` + 6 Java tests, 4 xcx tests | `k7-N6-order-list`, `k7-N6-order-detail` (reg 76: 进行中, 可玩至 12 月 5 日, 开始游玩 13:15) | — |
| **N5** publish refusals | **fixed — chose: make the games work** (and lock only what truly can't) | Why: in a 状态 / 检定 theme every station is a quest and the server only accepts a game with `outcomeRules` v1 on GPS / scan; it can already settle almost every editor game that way (`r5CompletionProof`). The editor attaches `outcomeRules` only for 输入挑战 / D20 / 相册, so 拍照审核 / 拍物成卡 / 找东西 / 翻卡 / 罗盘 / plain games were refused. Now, on a quest station the picker attaches the same rule shape to any game the server can settle, defaults 参与方式 to 定位到达 and marks 直接参与 「开了状态或 d20 检定的主题里要选定位到达或扫码」; games it can't settle even with a rule say 「开了状态或 d20 检定的主题里不能用这种玩法」. No new engine, no theme branch: same runtime, same matrix. Server refusals now name the station and game in Chinese and say what to do, e.g. 「「01 拍照审核」的拍照审核：…这种玩法要带结果规则才能确认完成。请在这一站重新打开玩法设置，保存一次后再发布」 and 「「01 选项问答·反馈」：…个人任务的这一站参与方式要选「定位到达」或「扫码」，或绑定承接商家」. The engineering diagnostic (node id, game key, tool, reason code) stays in the exception's `toString()` for logs and the existing tests. | `GameplaySupportMatrix.java`, `TopicRoutePublicationCompiler.java`, `pages/publish/temp/{index,r5-editor}.js`, `utils/publish/gameplay-support-matrix.js` | Rig editor with a quest-station context: pick 拍照审核 → `outcomeRules {version:1,pass,fail}`, `validationMethod 5`, 参与方式 定位到达 (`k7-N5-editor-task-station-gps`). Rig publish (API leg, editor's configs): photoCheck+翻卡 with rules on GPS → 「主题发布成功」 (990020); without rules → station-named message; 直接参与 → station-named message | Author seeing the message on the publish screen (rig shows it via API only); playing 拍照审核 / 罗盘 in a 状态 theme end to end (needs vision / compass) |
| N7 结果反馈 screens | **fixed (part k8)** | Fail sheet drops 「得分 0 / 已完成 0 项 / 原结果已保留…」, advanced panel drops 「本次玩法已结束，重新进入会保留原结果。」. Dark verdict colours: k8 `b4b2ad71`. | `components/playkit/index.wxml`, `components/advanced-game/index.wxml` | unit + rig text read-back | Two screens still show for one fail (verdict, then sheet) |
| N8 输入挑战 wrong is silent | **fixed** | After a wrong submit the line reads 「没打对，再打一次」. | `components/playkit-typein/index.wxml` | unit only | Rig walk |
| N9 精准停表 | **part** | Shown error uses the judge's precision (「早 0.304 秒」 vs ±0.300). Tries off by one after reopen: **not done** (the stage counts misses locally; needs server attempts passed in). | `components/playkit-stopwatch/index.js` | unit | — |
| N10 今日城市签 | **part** | No 「NO. 0000」 when the server gives no serial; the 「你留给下一个人的」 card shows what was just typed and doesn't appear empty. **Needs owner:** the server never stores the note (`claimDailySign` ignores it), so the relay to the next person doesn't exist; that's a missing mechanism, not a copy fix. ASCII 「撕不下来?」 → 「？」. | `playkit-dailysign/*`, `utils/playkit-view.js` | unit | Light panel, two backs, stacked buttons not changed |
| N11 相册 | not done | — | — | — | — |
| N12 参与人信息 phone ignored | not done | Belongs to checkout (k1/k6 area); report only. | — | — | — |
| N13 led map / title | not done | — | — | — | — |
| N14 闭眼盲品 | **part** | 「店员会把小样摆在你面前」 removed. 「尝好了，松手睁眼」 after answering not changed. | `playkit-blindtaste/index.wxml` | — | — |
| N15 抛硬币 | **part** | 「正面还是反面？」 full-width. First-tap ignore not reproduced/changed. | `playkit-coinflip/index.js` | — | — |
| N16 captions | **part** | 计步 「少排 0.00 kg」 hidden at zero. **Needs owner:** 安静挑战 / 喊一嗓子 「相对 PCM… 不是分贝」 and 「本机成绩：只记探索值与徽章…」 are pinned by the C1 trust-boundary tests as honesty disclosures; removing them is a decision, not a polish. Default I recommend: keep 「不是分贝」 once on the result, drop 「PCM」. | `playkit-walk/index.wxml` | — | — |
| N17 「总积分排名 0」 | **owner decision, unchanged** | Code cites P-22 (业主「没数据显示0」). | — | — | — |
| N18 出发 during reveal | not done | Not in `continueChapterStory`; likely the text-effect layer takes the first tap. Not guessed at. | — | — | — |
| F9 | **fixed** with N6 | 00:00, one end date, ASCII comma. | see N6 | see N6 | — |
| F13 票夹 card | not done | — | — | — | — |
| F14 captions | **part** | 排序 / 连线 / 分类 instruction lines and 「都放好了，看看下面对不对」 removed; 「当前最高 N 解谜分」 keeps the real number, drops 「· 不影响探索值」. 预测 「转到想选的那张，点它」 and 精准停表's lines kept. | `playkit-sort`, `playkit-match`, `playkit-classify`, `index.wxml` | unit | Rig shots |
| F16 answers and feedback | **part** | 拍照打卡 no longer says 「答对了」 (「拍好了」). Grey 再想想 + 提交, 猜图 verdict, D20 加值 「继续」, stacked buttons: not done. | `playkit-qa/index.js` | unit | — |
| F17 揭示答案 | not done | Needs the server projection to carry the 文字问答 answer after finish. | — | — | — |
| F18 editor look sheet | not done | — | — | — | — |

## Money rules

None changed. RefundPolicy edits are punctuation in player-facing reason strings only; verdicts, windows and amounts are untouched (84 refund tests pass).

## Tests

- New `chengyinhub-xcx/tests/unit/r12-k7-play-fixes.test.js` (11 tests: N4 ×3, N1 ×2, N2, N3 ×2, N5 ×2, N6). Updated: `play-generic-flow`, `action-ledger` (5719 → 5718, the removed duplicate button), `playkit-stopwatch-miss-contract`, `r11-g3-order-truth`, order tests.
- Java (local Maven, `-pl chengyinhub-admin -am`): `TopicRoute*Test, Gameplay*Test, AdvancedGameRuntimeServiceImplTest, PublishedNodeContentConsumptionTest, TopicPublicationContentSnapshotTest, ApiThemeRuntimeControllerTest` and the refund tests: 954 + 84 run, 0 failures. `TopicContentGraphMySqlIT` assertion updated (no `@Test` added or removed; `ci/funds_it_gate.py` unchanged).
- `ui-integrity.js`: `UI-GATE-0:PASS`. `build-action-ledger.js --check`: PASS, 5718 controls.
- Full xcx unit sweep: 4 files fail **with or without my changes** on the current head (`mycanyu-refresh-state`, `play-chapter-story-blocks-contract`, `r10-j2-f06-session-entry`, `verification-unknown-adapters`): other lanes' work, not touched.

## CI

See the last section, added after the push of this file.

## What only a phone proves

D20 die canvas and hold-to-roll; the predict wheel's 3D transform and seal landing on a real WebView; native picker-view gestures on 猜数字; real GPS arrival and the location sheet; camera / vision for 拍照审核 in a 状态 theme; WeChat payment.
