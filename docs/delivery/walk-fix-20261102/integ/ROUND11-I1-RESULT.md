# Round 11, lane i1-clean-run: result

Branch `integ/launch-test-20261103`. I worked on a private branch and pushed with `pull --rebase`, checking `git show --stat` first. I did not force-push, merge or touch master or production.

**Screenshots come from the rig, not a phone.** The rig is `claude/cloud-walk-20261101-rig` with j3's and j4's `tools/rig-changes.diff`. It runs the real jar (MySQL 8, Redis 7) and the real mini-program in headless Chrome at 390×844. The jar was built from `831f40b1`; the browser bundle was built from `904c4a66`. I looked at every screenshot I name.

## Code commits

| Commit | Item |
|---|---|
| `47d2ac9b` | 1. 自由探索 sale deadlock |
| `be27b4ed`, `0c338a18` | 3. gitleaks and the static-gate reds |
| `4fd4d6fd`, `ceb518f9` | 2. Sensor skip for 弹球 / 安静挑战 / 喊一嗓子 / 计步 |
| `831f40b1` | 2. Generic theme: the station card's 开始任务 |
| `41d44fca` | 2. 九宫格 (test only) |
| `6ec6c063` | 5. j3 F14 captions |
| `7239e122` | 5. p1 F-9 dark for players; follow-ups to 2 and 3 |
| `904c4a66` | Baseline and ledger line moves |
| the commit carrying this file | 3. Real-storage registry count |

## CI

On `ceb518f9`, which holds every code change except the registry count:
- GitHub CI run **41948022370**: green, all jobs, including gitleaks, Mini program static gates, every mini unit shard, Backend tests (with the atomic-migration step) and the migration safety gate.
- Funds MySQL IT run **41948022422**: green.
- Isolated authenticated Redis recovery run **41948022500**: green.
- Mini followup focused regression run **41948022414**: green.
- Real Storage IT (manual, batch `extra-storage`) run 41948014974: **red at its own static precheck**, before any test ran. See item 3. Fixed in the commit that carries this file, and dispatched again on it.

On `573c463b` (all code plus this file, before this update):
- GitHub CI run **41950298244**: green, all jobs.
- Funds MySQL IT run **41950298330**: green.
- Isolated authenticated Redis recovery run **41950298381**: green.
- Mini followup focused regression run **41950298256**: green.
- Real Storage IT (manual, batch `extra-storage`) run 41950279540: past the precheck. **`UmsMemberRankMySqlIT` passes**, as do 31 other classes. Four classes are red:
  - `CoopServiceRealDbIT`: `acceptWithLock_realDb…:141` throws 「关联主题不存在」.
  - `E01_10Test`: 3 failures.
  - `B04ScheduleReadbackMySqlIT`: 1 failure of 9.
  - `GameplayReskinMySqlIT`: 1 error.

  This batch had not run on this branch since 2026-10-30 (the last run was on another branch), and none of the four touches a file this lane changed. **Not fixed here; open for the next lane.**

This update is docs only.

## 1. 自由探索 sale deadlock. Status: fixed and proved.

- **Change** (`ClubSessionServiceImpl.requireTopicOpenForSessions`): open-session is allowed at lifecycle 2 (定价与开售审核) only when all three hold:
  - the theme is `product_type = 2`;
  - the opening club holds a live co-op order on it (`hasEffectiveClubOrder`);
  - the theme isn't ended.

  Everything else keeps h4's rule:
  - other products, and 自由探索 at lifecycle 7 or 1, get 「主题还没开售，开售后才能开一场」;
  - lifecycle 5 and 6 get 「主题已结束售卖，不能再开一场」;
  - lifecycle 2 without an order is refused.
- **Chain:** `scripts/e2e/chain_sale_close.py` opens sessions again before the sale review, while the theme is at PRICING. h5 had moved this after SELLING. Its test was updated.
- **Tests:**
  - `ClubSessionServiceImplTest`: 28/28. Two new tests, one in each direction. `freeExploreInSaleReview_withClubCoopOrder_opensSession` fails on the old code.
  - Chain unit tests: 164 OK.
- **Rig:** `chains.py player` gave platform PASS, merchant_club PASS, sale_close PASS and player PASS.
  - Four sessions opened (`开团成功`), with ticket projection 1:1.
  - The review moved the theme to lifecycle 3. The snapshot came back 15/15 non-empty.
  - Read-backs are in `fix-shots/i1-chains-readbacks.txt`.
- No money rule changed.

## 2. Dead ends. Status: fixed and walked, with limits below.

**弹球, 安静挑战, 喊一嗓子, 计步 on a solo pass.** They now follow the compass rule.
- **Server** (`AdvancedActionJudgement`, `AdvancedGameRuntimeServiceImpl`):
  - The four games are in `SKIPPABLE_SENSOR_SECTIONS`.
  - A denied mic or WeRun (`denied:true`) counts straight to the 2-read threshold.
  - A skipped station counts as done: 0 score, no xp for that part.
  - A skipped game takes no late start or submit (「这一站已经跳过了」).
  - Led sessions still need the author's setting or the lead's release.
- **Client:**
  - Each screen reports failed reads and shows the existing 「跳过这一站」 outline button only when the server sends `canSkip`.
  - After a skip, the main button is hidden.
  - 计步 with WeRun denied now says 「需要授权微信运动才能计步」, as a red line under the content (`cy-inline-error`).
- **Rig proof** (shots `i1-01`…`i1-05e`, read-backs in `fix-shots/i1-walk-readbacks.txt`):
  - `player_node_progress` rows for nodes 75–78 have `completion_mode=SKIPPED`, `puzzle_score=0`.
  - The session state has `skipVia SOLO`.
  - The skip button sits above 开始 and covers nothing.
- **Tests:**
  - `SoloSensorSkipTest`: 5. It covers the four games, led refusal, denial, skipped-is-done and no late score.
  - `r11-i1-sensor-skip.test.js`: 5.
  - Two tests that named 安静挑战 and 喊一嗓子 as "not sensor" were updated, because the owner's rule changed that.
  - Backend selection: 2,162 tests, 0 failures.
- **Not fixed:** a mic denial shows no reason on screen (only 计步 explains itself). The 「开始」 hidden after a skip (`ceb518f9`) was not re-walked in the rig.
- **Only a phone proves:** the real accelerometer, mic levels and WeRun denial.

**Generic theme, 结果反馈 FAIL.** My choice: **a new attempt through 取消 then 开始任务**, which is the design the code already has. Each QUEST_START gives a new task instance and a new game session.
- Walked in the rig (`i1-07`…`i1-12`):
  - wrong answer, then FAIL;
  - 取消 (200, `activeQuest` cleared, 「任务已取消，未扣除任何费用」);
  - 开始任务, then a new session;
  - right answer, then 结算任务 and the author's reply. Node 85 is COMPLETED.
- I could not reproduce h5's "取消 does nothing". The likely cause is that the stale game screen stays behind the 背包.
- **Left:**
  - The FAILED game screen still shows a 提交 that the server answers with a silent 200 no-op (`i1-07b`).
  - 开始任务 in the 背包 does not reopen the game. The player taps the card or 打开原任务.
  - `player_node_progress` for that node stays status 0 after resolve.

**Generic theme, the station card's 开始任务.**
- Fixed (`831f40b1`): when the station's task isn't started, the card first sends the 背包's own `QUEST_START`, using the same cost-option sheet, and then opens the game.
- Rig: QUEST_START 200, then `advanced/start`, then the game opens (`i1-06`, `i1-06b`).
- Before arrival it opens the 背包 with 尚未到达.
- Test `r11-i1-station-card-quest-start.test.js`.

**九宫格.** No code change.
- In the only picker the editor renders, `bingo` is already grey with 「在主题里配置」, and `pickR5Game` ignores grey rows.
- 九宫格 is played from the theme's own settings (`fabu` 九宫格 rows, `PlayBingoServiceImpl`).
- Pinned by `r11-i1-bingo-picker.test.js`.

## 3. CI reds. Status: fixed; one more found and fixed.

- **gitleaks:**
  - The constant is renamed to `SEEN_STORAGE_NAME`. The stored key value is unchanged.
  - On pull_request the scan reads history (`base^..head`), so the rename alone cannot clear commit `7f3ce957`.
  - What clears it is one exact fingerprint in `.gitleaksignore` (`7f3ce957…:roam-atmos-entry/index.js:generic-api-key:8`). That is the repo's existing practice, and no path or rule allowlist was added.
  - Green from `0c338a18` on.
- **Static gates:**
  - `hostMerchants` [C]: the component does consume it, through a multi-field observer whose key string the lint masks. The observer parameter is now named after the prop.
  - `ticketsError` U6: the club-members-only notice gets its own field, `ticketsGateText`. It is not an error.
  - `teamSizeError`: already green at this head.
  - Also fixed: growthcenter 「名」 unit fallback, a U5 baseline line move (8410→8411→8444, digest recomputed, no new entry) and ledger rebuilds.
  - g3's growthcenter test pinned `me.rank` with no fallback, against its own title and the unit gate. It now pins `me.rank || <fallback>`.
- **Atomic-migration step:** it runs in Backend tests and was green in run 41948022370.
  - The step is fail-closed: at least 80 tests and `skipped=0`.
  - h2's `ReviewWordingSeededTextMysqlIntegrationTest` is in its exact list, so it ran there.
  - I could not download the raw log through the proxy, so the count is inferred from the gate, not read.
- **`real-storage-it.yml` `UmsMemberRankMySqlIT`:** dispatched at `ceb518f9` (batch `extra-storage`). It stopped at the workflow's own registry precheck: 「源码声明 113 条 @Test，登记 108 条」.
  - Cause: f4 (+2) and h3 (+3) added tests to `RegistrationFundsMybatisIntegrationTest` and updated `ci/funds_it_gate.py`, but not `ci/real-storage-it-registry.json`.
  - Fixed: the registry says 113, and the batch totals in `ci/test_real_storage_it.py` are +5 (registration-write 137, funds-closure 291, integrated-changes 945). 92/92 locally.
  - Dispatched again on `573c463b`: the precheck passes and `UmsMemberRankMySqlIT` is green. Four other classes in that batch are red (see CI).
- No `@Test` was added or removed in a funds-gated suite, and `EXPECTED` is untouched.

## 4. Walks. Status: partly done.

- Done: item 1's chain, and item 2's walks above.
- **Not done:**
  - 「等队长放行」 and 返回地图 under the director runtime (j3's `tools/l6/fixtures.py`);
  - closed 预测 with a shop;
  - following a team from the rail;
  - the 同场路线 off switch;
  - swipe cards and fly-to;
  - drag clouds;
  - off-screen pins;
  - rain, snow and fog;
  - the p3 F1/F2 and p2 F-5/F-7 recheck.

  The rig time went to items 1 and 2.

## 5. Remaining findings. Status: partly done.

- **p1 F-9: fixed.**
  - official-detail and 主办控制台 now pick the theme by viewer, the same way 我发布的 does: `theme-merchant` for merchants, `theme-dark` for players. A failed role read falls back to player. The detail page imports the class-scoped merchant sheet.
  - The scrolled back arrow stays light for players.
  - The theme gate puts both pages in the shared domain, which must declare both themes, and its selftest was updated.
  - Two tests pinned the old fixed-light layout (「官方活动详情是商家工作域，必须固定使用商家浅色主题」 and 「商家官方活动…使用商家浅色主题」). They now pin merchant-light / player-dark, because the owner's rule is "player dark, merchant light".
  - Not walked in the rig.
- **j3 F14: partly fixed.** No more invented text:
  - the station 「走到这里，留意四周…」;
  - the chapter 「跟着脚步走完这几处坐标…」;
  - the 「本站任务」 eyebrow and 「到达后开启本站任务」;
  - the intro's 读/玩 lines;
  - 计步's 「步数只能同步，不能填…」.

  Not touched: the tools-menu sub-lines, the solo-pass sheet lines, the instruction lines under 排序/连线/分类/预测, 精准停表, the legacy score line, and 「查看获得时刻」.
- **Not done:** j3 F13, F16, F17, F18; f2's leftover captions; j1 F11's pink error blocks.

## Checks

- **Mini:**
  - Full unit run: 16,168 tests, 1 failure. The failure was the ledger event-contract count, fixed after the run and re-run 46/46.
  - `ui-integrity.js`: UI-GATE-0:PASS.
  - `build-action-ledger.js --check`: PASS, 5,723 controls.
  - All static sub-gates pass locally, without their selftests. `ci/xcx-check.sh` was not run.
- **Main package:** 2.97 MB by preflight's count, under the 3.00 MB ratchet.
- **Backend:**
  - `ClubSessionServiceImplTest`: 28/28.
  - Sensor and play selection: 2,162 tests, 0 failures.
- No migration. No new public Service, Mapper or Controller. No money rule changed.
- Secrets: the diffs were grepped, and the read-back files hold no tokens.
- Local gitleaks could not be downloaded in this container, so CI's gitleaks job is the proof.

STATUS: PARTIAL - items 1 and 3 done and green; item 2 done except the dead 提交 on a FAILED generic game, the mic-denial message and a re-walk of the hidden main button; item 4 walks other than items 1–2 not done; item 5 F-9 and part of F14 done, F13/F16/F17/F18, f2 captions and the pink error blocks not done; the PR CI is green on 573c463b; Real Storage IT extra-storage has 4 red classes (CoopServiceRealDbIT, E01_10Test, B04ScheduleReadbackMySqlIT, GameplayReskinMySqlIT) that this lane did not touch and did not fix
