# W7 x-life-games: receipt (hengyin)

Branch `feature/r-x-life-games` from `origin/roadmap/be-integ`. App side: chengyin-app `cloud/r-x-life-games`.
Both bases already contain `x-template-games` and `x-shop-game-runner`, so nothing was merged in.

## Design (written before the code)

R12 asks for real mechanics on the shelf copies of 《生活不掉线》 G0/G3/G4/G6 and the 8 《今天值得庆祝》 games.
Every one of them fits an engine that both clients already play, so **no new section type and no new runner** is added:

| Engine | Used when | Clients |
|---|---|---|
| vm=6 preference group (`PreferenceResultEvaluator`): `single` and `discard` steps, a result card that quotes the player's choices (`{{choices}}`) | there is no right answer | app `PlayPreferenceViews`, mini `pages/play` preference panel |
| vm=3 A–D option, judged on the server against `correct_answer`; `answer_reveal` comes back only after a right answer | there is one right answer (a time window, a budget, a walk limit, a fallback that keeps every condition) | app option cards, mini option cards |
| advanced `timer` (`AdvancedGameConfigValidator`), played before the node's own task; `completeGate` refuses a completion after an `EXPIRED` timer and the player restarts | the card promises a time limit | app `PlayAdvancedView`, mini `advanced-game` |

Per game (shelf rows in `cms_template_library` only):

| Game | Mechanic | Judging |
|---|---|---|
| 生活 G0 30秒生活掉线测试 | vm=6, 4 steps: situation → area (blurred to a district, never an address) → reachable time → top concern. Keeps its 30 s timer | preference; the result names the top concern and quotes all four picks |
| 生活 G3 营业时间拼图 | vm=3: three opening windows, four times; pick the one inside all three | server (C) |
| 生活 G4 去之前准备什么 | vm=6 `discard` step: four prep cards, drop the one you don't need, the three left are your list | preference; each result lists the three kept cards |
| 生活 G6 首选失效怎么办 | vm=3 + new 30 s timer: your usual shop is closed; pick the fallback that still meets the deadline | server (B) |
| 庆祝 G0 30秒今天值得什么 | vm=6, 2 steps: what to celebrate → ritual style. Keeps its 30 s timer | preference |
| 庆祝 G1 每人一个不能 | vm=6, 2 steps: one must-have, one no-go. Solo on the shelf: the copy says each companion plays once and you compare cards (the app does not merge answers) | preference |
| 庆祝 G2 气氛卡碰撞 | vm=6: pick a mood card; the result names the kind of real project that fits it | preference |
| 庆祝 G3 三小时时间方块 | vm=3: workshop hours, restaurant opening, walk time; which start fits in three hours without waiting. Keeps its 3 h timer | server (C) |
| 庆祝 G4 每人预算筹码 | vm=3: three priced parts and a per-person budget; which plan stays inside it | server (B) |
| 庆祝 G5 十分钟步行上限 | vm=3: four unnamed routes with leg times; which keeps every leg within 10 minutes. Keeps its 10 min timer | server (B) |
| 庆祝 G6 主选突然掉线 | vm=3 + new 30 s timer: the booked place is full; pick the replacement that keeps time, budget and the hard condition | server (C) |
| 庆祝 G7 60秒共同落款 | vm=6, 2 steps: a prompt and a short phrase card → a private sign-off card. Keeps its 60 s timer | preference |

Rules for the seed change: one migration, every UPDATE guarded on the seed text (`create_by`, `validation_method=0`, no `preference_json`/`correct_answer`, rule text still the seed's or `x-template-games`' rule-card wording). Merchant-edited rows are left alone.

R10, R11 and the tally screen use what exists: the answer receipt gains `feedbackText` next to `answerReveal`; the preference submit takes the same `longitude/latitude/accuracy` as `/photo` and the same switch and radius; the tally screens read `GET /api/template/preference-tally`.

## Fix status

| ID | Status | What |
|---|---|---|
| R12 生活 G0/G3/G4/G6 and 庆祝 G0–G7 describe mechanics they do not offer | Fixed (shelf rows) | `migration_20261116_life_games.sql`: six preference groups, six server-judged option puzzles, two new 30 s timers (table above). The two themes' own rows wait for the owner (below) |
| R10 the app never shows `answerReveal`/`feedbackText` | Fixed | Backend: a right answer's receipt now carries `feedbackText` next to `answerReveal`. App: decoded and shown under the passed verdict (chengyin-app receipt). Mini-program already showed both (`passText` from `/nodes`, `answerReveal` from the receipt; `pages/play/utils/answer-right-moment.js`): no change |
| R11 preference submits pay full prizes without presence | Fixed | Same rule as R2 photos: outside free-collect (which already needs the store scan), a located stop without a fix inside the GAME-2 radius completes with XP and badges only and records `remote_completion`. Same switch `play.arrive.remote_reward_policy` (FULL = old rule) and radius. Both clients send the fix |
| Merchant tally screen | Fixed | Mini-program: read-only `cy-preference-tally` on the owner's template detail (`pages/publish/temp`, 我的模板 scope, vm=6 only). App: the chengyin-app receipt. Under 3 answers only the total, as the server does |

## Changes, file by file

- `chengyinhub-admin/sql/migration_20261116_life_games.sql` (new). Not transactional (multi-table `UPDATE … JOIN`, as `20261116_template_games`). Rollback note at the top. A temporary table holds the 12 rows; ① writes the two G6 timers only where `advanced_config_json IS NULL` and the row is still vm=0; ② swaps in the mechanic only where the row is still vm=0, has no `preference_json`/`correct_answer`, and its rules are still the seed text (`玩家动作…`) or the x-template-games rule-card text (`这是一张规则卡…`). Player rules and descriptions are plain; merchant notes move to `merchant_guide`. A `runner: assert=scalar-true` post-check.
- `ci/migration-dependencies.json`: `20261116_life_games` after both seeds, the celebrate copy fix, the 0825 timers, `20261116_template_games`, the preference library, the advanced runtime and the merchant guide column.
- `chengyinhub-admin/src/main/java/com/chengyinhub/web/service/play/PlayBasicCommandApplication.java`: `answerFeedbackText` (frozen content first, else the live template, same source as `/nodes`); put in the right-answer receipt only.
- `chengyinhub-admin/src/main/java/com/chengyinhub/web/service/play/PlayPreferenceApplication.java`: presence check before `advanceProgress`; reuses `PlayPhotoCommandApplication.presentAtStop` and the `remoteCompletion` overload. Optional `ISysConfigService` for the switch.
- `chengyinhub-system/.../domain/dto/PreferenceSubmitRequest.java`: optional `longitude/latitude/accuracy` (strings, like `/photo`). Not part of the intent fingerprint (choices and tag only), so a resend with another reading is still the same intent.
- Mini-program:
  - `pages/play/index.js` `submitPreference`: merges `photoFixOf(this._loc, node, mode)` (the R2 helper: city mode, located stop, reading under 2 minutes) into the payload. Never blocks.
  - `pages/publish/components/preference-tally/` (new component) and `pages/publish/utils/preference-tally-view.js` (pure view mapping); `pages/publish/temp/index.{json,wxml,js}`: register the component, one `wx:if` line (`isMyScope && tallyTemplateId && validationMethod === 6`), and `tallyTemplateId` in page data.
- Tests:
  - `LifeGamesSeedContractTest` (new, 7 tests): the 12 rows parsed from the SQL; every preference config through `PreferenceResultEvaluator`, every combination of picks finishes with a result that quotes every pick, every result reachable, result titles distinct (the tally lists them by title); the pick-three is a `discard` of one from four and each result lists the three kept; every option puzzle complete, with the right answer **recomputed from the numbers in the question** (opening-window overlap, three-hour schedule, walk-leg limit, budget sum, the three fallback conditions); timers through `AdvancedGameConfigValidator`; no merchant words or banned player words in player text.
  - `ApiPlayProgressCompletionTruthTest.correctReceiptCarriesTheAuthorsFeedbackTextAndAWrongOneDoesNot`.
  - `ApiPlaySensorResultTest`: `locatedPreferenceStopWithoutAFixCompletesWithoutPoints`, `locatedPreferenceStopWithAFixAtTheStopPaysPoints`, `onlinePreferenceStopPaysPointsWithoutAFix`.
  - Mini: `tests/unit/play-preference-fix.test.js`, `tests/unit/preference-tally-view.test.js`.

## Theme rows waiting for the owner (not changed)

The 《生活不掉线》 and 《今天值得庆祝》 themes read their own official rows in `cms_member_template` (`member_id=100000`, same titles, `create_by` = the two seeds), wired to theme nodes by the topic seeds. If the owner approves, the change is exactly the shelf change, applied to those rows:

| Theme row (`cms_member_template`, member 100000) | Would change |
|---|---|
| 生活 G0｜30秒生活掉线测试 | `validation_method` 0→6, `preference_json`, `description`, `rule_instructions`, `merchant_guide` (keeps its 30 s timer) |
| 生活 G3｜营业时间拼图 | 0→3, `question_name`, `question_a..d`, `correct_answer`='C', `hint1`, `answer_reveal`, `feedback_text`, `description`, `rule_instructions`, `merchant_guide` |
| 生活 G4｜去之前准备什么 | 0→6 (`discard` step), `preference_json`, `description`, `rule_instructions`, `merchant_guide` |
| 生活 G6｜首选失效怎么办 | 0→3 (`correct_answer`='B'), the option fields as G3, plus `advanced_config_json` = 30 s timer |
| 庆祝 G0｜30秒今天值得什么 | 0→6 (keeps its 30 s timer) |
| 庆祝 G1｜每人一个不能 | 0→6 |
| 庆祝 G2｜气氛卡碰撞 | 0→6 |
| 庆祝 G3｜三小时时间方块 | 0→3, 'C' (keeps its 3 h timer) |
| 庆祝 G4｜每人预算筹码 | 0→3, 'B' |
| 庆祝 G5｜十分钟步行上限 | 0→3, 'B' (keeps its 10 min timer) |
| 庆祝 G6｜主选突然掉线 | 0→3, 'C', plus the 30 s timer |
| 庆祝 G7｜60秒共同落款 | 0→6 (keeps its 60 s timer) |
| 生活 G1/G2/G5 (from x-template-games) | 0→6 with the configs in `migration_20261116_template_games.sql` |

Values are the ones in `migration_20261116_life_games.sql`. Consequences to weigh: theme stops change from "arrive and done" to a choice or a puzzle; already-published theme runs keep their frozen answers (`freezeNodeAnswers`) and change only after a republish; 庆祝 G1 stays single-player (each companion plays once; nothing merges answers). Merchant copies adopted before this migration are not changed either: they keep the rule card until the merchant re-adopts or edits.

## Verification

All Maven runs with `LANG=C.UTF-8 LC_ALL=C.UTF-8`.
- `mvn -B -q -DskipTests compile`: OK.
- `mvn -B -pl chengyinhub-admin -am test -Dtest='*Template*Test,*Preference*Test,*Play*Test,*Seed*Test,AdvancedGameConfigValidatorTest,PlayerMessagePunctuationTest,SysJobStaggerPlanTest,TwoNodeSchedulingTest,MemberPrivacyErasureServiceTest,E2eTestProvidersProfileTest,*Migration*Test'`: **2340 run, 0 failures, 0 errors, 73 skipped**, BUILD SUCCESS.
- Negative control: flipping 生活 G3's `correct` from C to B makes `LifeGamesSeedContractTest.timePuzzlesHaveExactlyOneRightOption` fail ("expected [B] but was [C]"); restored.
- `bash ci/migration-safety-check.sh`: 迁移安全门禁:通过. `bash ci/migration-layout-check.sh`: 迁移布局门禁:通过.
- Mini-program (`node ../ci/prepare-mini-artifacts.js`, then `node --test "tests/unit/**/*.test.js"`):
  - base `origin/roadmap/be-integ`: 17950 tests, 32 failing;
  - first branch run: 17955 tests, 35 failing. The 3 new ones were all `press-feedback-coverage-contract` (my tally retry used a bare `bindtap`). Fixed by using `cy-inline-error`'s own action; that test file now passes (12/12 with the tally test). With the fix the failing set equals the base's.
- `node tests/lint/ui-integrity.js --warn-as-error`: exit 1 on base and on branch (pre-existing items). After making the tally's `setData` keys explicit (a U4 item on the first run), the findings are identical to the base once content hashes and line numbers are ignored; only the scan count grows (2 JS files, 1 WXML).

## Not verified here

- No MySQL: the migration was not run. The `UPDATE … JOIN` guards and JSON columns were checked by reading; the contract test covers every config and answer.
- No device: the mini-program tally card and the preference fix were unit- and source-checked only.

## Owner and coordinator actions

1. Run `migration_20261116_life_games.sql` through chengyin-db-migrate after `migration_20261116_template_games.sql` (read-only pre-check in the file header).
2. Decide whether the two themes' own rows get the same change (table above), and whether already-adopted merchant copies should be offered the new version.
3. Deploy order for R11: same as R2. Ship this backend with or after the app and mini-program builds that send the fix, or keep `play.arrive.remote_reward_policy=FULL` until then. Old clients send no fix, so their preference answers at located city stops would pay XP and badges only.
4. The shelf puzzles (G3/G6 and celebrate G3–G6) use example hours, prices and routes. Their guides ask adopters to replace them with their own and change the right option. They are not added to the R9 placeholder guard (they are puzzles, not shop secrets); say if they should be.

## Overlap with the UI agent

- None of the UI agent's defects were fixed here.
- Shared backend file: `PlayBasicCommandApplication.java` (answer receipt, one helper). Shared mini-program file: `pages/play/index.js` (`submitPreference` only) and `pages/publish/temp/index.{wxml,json,js}` (one line each).
