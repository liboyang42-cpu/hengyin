# W7 x-template-games: receipt (hengyin)

Branch `feature/r-x-template-games` from `origin/roadmap/be-integ` @ `63bc29f9d`. App side: chengyin-app `cloud/r-x-template-games` (receipt `docs/roadmap/x-template-games/RESULT.md` there).
Review first: [REVIEW.md](REVIEW.md) (pushed before any code, commit `69e04e736`). The ranked fix list R1–R12 there is what this receipt closes.

The shelf now holds 69 official rows, not 19 (REVIEW "What is actually on the shelf now"). This package covers the 19 the owner named. `x-shop-games` exists only as the earlier `feature/r-shop-games`, which is already in the base; it does not touch these 19 rows. x-rewards-evidence is in the base and is built on, not undone.

## Fix status

| ID | Status | What |
|---|---|---|
| R1 M05 judged against a "correct" answer | Fixed | M05 becomes a preference group (vm=6): 香气/口感/造型/性价比, each with its own result card quoting the choice. Owner-only tally `GET /api/template/preference-tally` (counts only; under 3 answers only the total) |
| R2 photos pay full prizes from anywhere | Fixed | `/api/play/photo` takes `longitude/latitude/accuracy`. A located city stop without a fix in the GAME-2 reward radius still completes but pays XP and badges only. Same switch (`play.arrive.remote_reward_policy`, FULL = old rule) and radius. Mini-program and app send the fix |
| R3 operator notes shown to players | Fixed | Seed: "(运营提示:…)" moved to `merchant_guide` (player-secret) for all 12 shelf rows, and for adopted copies whose rules are still the seed text and that have no guide of their own |
| R4 C04 promises review and selection; photo texts imply checks | Fixed | Seed: `photo_review=0`; honest feedback; all four photo games say "照片不做自动识别" |
| R5 C06 roles only offline | Fixed | Seed: advanced `random` role deck (导航员/观察员/记录员/交涉员, draw 1). Both clients already play `random` before the photo |
| R6 G1/G2/G5 offer no choice; G0/G3/G4/G6 describe missing mechanics | Fixed (shelf rows) | G1/G2/G5 shelf rows become single-step preference groups. G0/G3/G4/G6 rules now say plainly that they are rule cards. The 《生活不掉线》 theme's own template rows (`cms_member_template`) are not changed: owner decision below |
| R7 C02 needs an activity + team; claims a time-and-task ranking | Fixed | Seed copy says it needs an activity session and a team, and that the board ranks team time only. App: the timer row counts down |
| R8 C05 relay hand-over offline | Fixed (honest copy) | Seed copy: the leader sets the order; the app records each team's leg. No real relay mechanic built |
| R9 placeholder answers stay on adopted copies | Fixed | Publishing an adopted copy whose question and answer are still an official sample (城市醒了 / 1932 / 星火继续 / 老板的秘密题 A–D) is refused: "这是官方示例题，请把题目和答案换成你自己的再发布". Switch `template.official_placeholder_guard`, on unless `false` |
| R10 app never shows `answerReveal`/`feedbackText` | Open | Needs the app answer receipt and verdict screen, the stop-answer path the UI agent owns (F-05 work in `ui/h-acceptance-r2`) |
| R11 preference submit pays FULL without presence on located linear stops | Open | Free-collect stops already require the store scan. Linear located preference stops are rare. Same fix shape as R2 if the owner wants it |
| R12 real mechanics for G3/G4/G6 and the 8 celebrate games | Open | Product work |

## Changes, file by file

- `chengyinhub-admin/sql/migration_20261116_template_games.sql` (new). Not transactional: it uses multi-table `UPDATE … JOIN`, like `20260825_named_mechanic_advanced_config`. Every UPDATE is guarded on the seed text, so it is idempotent and leaves edited rows alone. Rollback note at the top. Contents:
  - R3/R4/R7/R8 copy table;
  - adopted-copy rule fix;
  - shelf-row rule and guide;
  - R1/R6 preference configs;
  - C04 `photo_review`;
  - C06 role deck;
  - a `runner: assert=scalar-true` post-check.
- `ci/migration-dependencies.json`: `20261116_template_games` after the two seeds, merchant_guide, preference_json, advanced runtime, and the team-race migration.
- `chengyinhub-admin/.../web/service/play/PlayPhotoCommandApplication.java`:
  - new photo overload with the fix;
  - presence check (`presentAtStop`, GAME-2 radius);
  - the remote path uses the `remoteCompletion` overload of `advanceProgress` (writes `remote_completion`, returns `rewardCeiling`). The present path is unchanged.
- `.../web/controller/api/ApiPlayProgressController.java`: the `@PostMapping("/photo")` method gains `longitude/latitude/accuracy`. The old 9-argument shape stays, with `@DeadlockRetry`, and calls the original application method.
- `.../web/controller/api/ApiTemplateController.java`: `GET /api/template/preference-tally?templateId=&scope=`. Login required. Owner is resolved like the other template routes (`resolveTemplateOwner`). vm=6 only.
- `chengyinhub-system/.../service/support/PreferenceTally.java` (new): author-ordered results, zero rows kept, the not-applicable result and an "other" bucket, a 3-answer floor.
- `.../mapper/PlayAttemptLogMapper.java/.xml`: `selectPreferenceTallyByTemplate` (counts by `$.evaluation.resultCode` over stops using the template).
- `.../service/support/OfficialPlaceholderGuard.java` (new) and `.../service/impl/TemplatePublishServiceImpl.java` (`assertOfficialPlaceholderReplaced` in `publishTemplateWithTransaction`; reads the switch through an optional `ISysConfigService`). The guard never reads the source row: editing an adopted copy must work when the source is gone (`TemplateEditOwnershipTest`).
- Mini-program:
  - `pages/play/utils/photo-fix.js` (new): the last fix, if under 2 minutes old, on a located city stop. It never blocks the photo.
  - `pages/play/index.js`: `submitPhoto` merges it into the payload.
- Tests (new or updated):
  - `TemplateGamesSeedContractTest` (every seeded preference config through `PreferenceResultEvaluator`, each option gives its own result; role deck through `AdvancedGameConfigValidator`; no operator notes in player rules);
  - `PreferenceTallyTest`;
  - `OfficialPlaceholderGuardTest` (samples pinned to the seed migration);
  - `ApiPlaySensorResultTest` (`locatedPhotoStopWithoutAFixCompletesWithoutPoints`, `locatedPhotoStopWithAFixAtTheStopPaysPoints`);
  - `BasicPlayCommandFixtures.photoAt`;
  - `ApiPlayRouteContractTest` (photo mapping signature);
  - the two fake `PlayAttemptLogMapper`s;
  - mini `tests/unit/play-photo-fix.test.js`.

## Verification

All Maven runs with `LANG=C.UTF-8 LC_ALL=C.UTF-8`.
- `mvn -B -q -DskipTests compile`: OK.
- `mvn -B -q -pl chengyinhub-admin -am test -Dtest='*Template*Test,*Preference*Test,*Play*Test,*Photo*Test,*Seed*Test,*Migration*Test,PlayerMessagePunctuationTest,SysJobStaggerPlanTest,TwoNodeSchedulingTest,MemberPrivacyErasureServiceTest,E2eTestProvidersProfileTest,DeadlockRetry*Test,AdvancedGameConfigValidatorTest'`: **2481 run, 2 failures, both failing on the unchanged base too**:
  - `ApiPlaySensorResultTest.ordinaryPhotoKeepsFullRewardPolicy` still expects a photo drop roll, which x-rewards-evidence removed;
  - `ClubMembershipMigrationContractTest.renewalCommitsLocalIntentBeforeWechatAndNeverLocksSettingAcrossNetwork`.
  - Both were checked on a worktree of `origin/roadmap/be-integ`.
- `bash ci/migration-safety-check.sh`: 迁移安全门禁:通过. `bash ci/migration-layout-check.sh`: 迁移布局门禁:通过.
- Mini-program: `node ../ci/prepare-mini-artifacts.js`, then `node --test "tests/unit/**/*.test.js"`:
  - base 17919 tests / 33 failing;
  - branch 17921 / 32 failing;
  - the branch's failing names are a subset of the base's (the extra base failure is the flaky `V3-20` switch-colour check). No new failures.
- `node tests/lint/ui-integrity.js --warn-as-error`: exit 1 on base and on branch (pre-existing U1/U5/LEGACY items). Their findings are identical once content hashes are ignored. The only difference is the scan count (one more JS file, `photo-fix.js`, and one more backend mapping, `preference-tally`). No new toast and no placeholder words.

## Not verified here

- No MySQL: the migration was not run. `JSON_EXTRACT` in the tally query and the `UPDATE … JOIN` statements were checked by reading only. Contract tests cover the JSON configs.
- No device: the mini-program photo payload and the app photo/fix flow are unit-checked only.

## Owner and coordinator actions

1. Run `migration_20261116_template_games.sql` through chengyin-db-migrate (read-only pre-check in the file header first).
2. Deploy order:
   - The photo presence rule caps photo prizes for clients that do not send a fix.
   - Ship this backend with, or after, the mini-program and app builds that send the fix.
   - Or keep `play.arrive.remote_reward_policy=FULL` until then.
3. Decide whether the 《生活不掉线》 theme's own G1/G2/G5 rows (`cms_member_template`, used by the theme) should also become preference groups. This package changed only the shelf rows.
4. Decide whether a merchant screen for the preference tally is wanted (backend route only; no mini-program or app screen in this package).
5. Confirm R11 (presence for preference submits on located linear stops) and R10 (show `answerReveal`/`feedbackText` in the app).

## Overlap with the UI agent

- None of the UI agent's defects were fixed here.
- Shared backend file: `ApiPlayProgressController.java` (photo mapping). `TemplatePublishServiceImpl.java` also differs in `origin/fix/registration-list-r3` by one line; nothing was cherry-picked (that branch conflicts with `roadmap/be-integ` in 20+ files, as noted by x-rewards-evidence).
- Shared mini-program file: `pages/play/index.js` (`submitPhoto` only).
