# W7 x-shop-game-runner: shop games played outside a journey (hengyin)

Branch `feature/r-x-shop-game-runner` from `origin/roadmap/be-integ` @ `f580b26a7` (already contains `x-shop-games`, `x-city-monopoly`, `x-merchant-tools`, `x-city-economy`). App side: chengyin-app `cloud/r-x-shop-game-runner`.

## Issues

| ID | Status | Notes |
|---|---|---|
| MERCHANT-5 / GAME-8 open part (runner) | Fixed | A template-only runtime session for an issued shop play, bound to the play, judged by the advanced runtime's section engine. `finish` judges from it. |
| CITY-23 (backend part) | Fixed (nothing to change) | `GET /api/shop-games/city-landing` and `POST /api/shop-games/launch` with `source: CITY_LANDING` already existed; the new session routes serve those plays too. No City Monopoly file changed (`ShopGamesTest.cityMonopolyRulesAreUntouched` still passes). |
| Mini-program runner | Fixed | `pages/play/shop-game` on the existing `cy-advanced-game` component; plain states for every 409. |

## Design

- **Separate storage, no fake journey ids.** Shop sessions live in `cy_shop_game_session` (one per play, `uk play_id`), never in `game_runtime_session`. Journey keying and lookups (`activity, topic, node, owner, play_instance_id`) are untouched, so neither kind of session can see the other.
- **Same engine.** `IAdvancedGameRuntimeService` gains side-effect-free `templateStart`, `templateAct`, `templateView`, `templateUntrustedEvidence` (default methods; implemented in `AdvancedGameRuntimeServiceImpl`). They normalize the template with `AdvancedGameConfigValidator`, build the initial state with the runtime's own `initialState`, judge every action with `AdvancedActionJudgement` (attempt ids, photo-evidence check included) and project the view with the runtime's own `toView` (answers stay hidden). No journey guard, fact, reward or mapper is called.
- **Only sections that need no journey** (`TemplatePlayRules.SECTIONS`): qa, trueFalse, imageGuess, hangman, reaction, pricePair, hiddenObject, jigsaw, sort, estimate, timer. Branch, team, outcome rules, predictions, bingo, steps and the rest answer 409 `SHOP_GAME_NOT_PLAYABLE` and the play stays open. Actions are limited to `TemplatePlayRules.ACTIONS` (plus `GIVE_UP`).
- **Fair standard.** A section with unlimited tries gets three (`TemplatePlayRules.TRIES_CAP`, the shop-game standard of three rounds), so a win cannot be reached by guessing until right. A stricter author limit stays.
- **Over and won.** The session ends when every section is done (`mechanicsReady`) or hidden objects run out of misses; it is won only when every section was won. Deadline = the template timer, never later than the play's expiry; an overdue session is closed as expired.
- **Prizes need presence.** `finish` keeps the x-shop-games rules: `RewardPresencePolicy` with the shop's radius (at least 80 m), device-reported games pay nothing, and evidence the runtime cannot judge (`untrustedCompletionEvidence`, for example a photo question) pays nothing. Remote wins finish as WON with 0 points and `rewardCeilingReason: REMOTE`.
- **A shop-game win changes no City Monopoly outcome.** The owner has not decided otherwise; this is the safe default.

## Routes (all under the existing `shopgames.play.enabled` switch)

| Route | Body / query | Answer |
|---|---|---|
| `POST /api/shop-games/plays/{playId}/session` | none | Starts or resumes the play's session (409 `SHOP_GAME_NOT_OPEN`, `PLAY_ALREADY_FINISHED`, `PLAY_EXPIRED`, `SHOP_GAME_NOT_PLAYABLE`; 404 `PLAY_NOT_FOUND`) |
| `GET /api/shop-games/sessions/state` | `sessionId` | The player's own session (404 `SESSION_NOT_FOUND`) |
| `POST /api/shop-games/sessions/action` | `{sessionId, version, idempotencyKey, action, payload}` (same as `/api/play/advanced/action`) | Judged view; same key replays, a different request under it is 409 `IDEMPOTENCY_CONFLICT`; 409 `VERSION_CONFLICT` (fresh view in `data`), `SESSION_CLOSED`; 400 `INVALID_ACTION` |
| `POST /api/shop-games/result` (existing) | `{playId, lat, lng, accuracy}` | Judged from the bound shop session when there is one, else as before (journey session) |

## Changes, file by file

- `chengyinhub-system/.../service/IAdvancedGameRuntimeService.java`: four default `template*` methods.
- `chengyinhub-system/.../service/impl/AdvancedGameRuntimeServiceImpl.java`: their implementations, appended at the end (no existing method changed).
- `chengyinhub-system/.../service/impl/TemplatePlayRules.java` (new): sections, actions, `unsupported`, `capTries`, `over`, `won`.
- `chengyinhub-system/.../shopgames/ShopGameSessionService.java` (new): start/resume, state, act (row lock, CAS version, idempotent replay), expiry.
- `chengyinhub-system/.../shopgames/ShopGamePlayService.java`: `judge` first looks for the play's shop session (`judgeShopSession`); `rewardCeiling` uses the shop session's evidence list instead of journey ids.
- `chengyinhub-admin/.../api/ApiShopGameController.java`: the three routes.
- `chengyinhub-admin/sql/migration_20261117_shop_game_runner.sql` (new): `cy_shop_game_session`, `cy_shop_game_session_action`; rollback note at the top. `ci/migration-dependencies.json`: entry added.
- `chengyinhub-admin/src/test/.../shopgames/ShopGameRunnerTest.java` (new, 12 tests, H2 on the migration's own DDL).
- Mini-program: `pages/play/utils/shop-game-runner.js` (new, pure), `pages/play/shop-game/*` (new page, registered in `app.json`), `pages/play/components/advanced-game/index.js` (new `shopPlayId` property; `request` maps start/state/action and refuses the rest), `subpackageMember/components/cy/scene-roam-poi-detail/index.{js,wxml}` (the shop's game on the place sheet: 「玩今天的小游戏」 while open, 「店铺小游戏还没开放，开放后就在这里玩。」 while closed), `tests/unit/w7-shop-game-runner.test.js` (new, 6 tests).

## Verification (all Maven runs with `LANG=C.UTF-8 LC_ALL=C.UTF-8`)

- `mvn -B -q -pl chengyinhub-admin -am test -Dtest=ShopGameRunnerTest`: 12 run, 0 failures. Covers a win at the shop (points via governance), a remote win (0 points, `REMOTE`), the tries cap (three wrong → LOST), give-up, replay and conflicts, owner-only access, the switch, an unsupported template (no session, play stays ISSUED), a finished play, journey-only actions refused, and **journey vs shop isolation** (a shop start writes nothing to `game_runtime_session`; a journey session id is not readable as a shop session; a completed journey session sent as `sessionId` does not judge a shop play that has its own session).
- `mvn -B -pl chengyinhub-admin -am test -Dtest='*AdvancedGameRuntime*,*ShopGame*,*PlayRuntime*,*Runtime*Test,PlayerMessagePunctuationTest,SysJobStaggerPlanTest,TwoNodeSchedulingTest,MemberPrivacyErasureServiceTest,E2eTestProvidersProfileTest'`: 993 run, 0 failures, 4 errors — all four in `AdvancedGameRuntimeMySqlIT`, which needs a live MySQL ("Communications link failure"; no database in this container).
- `bash ci/migration-safety-check.sh`: 迁移安全门禁:通过. `bash ci/migration-layout-check.sh`: 迁移布局门禁:通过.
- Mini-program (`node ../ci/prepare-mini-artifacts.js`, then `node --test "tests/unit/**/*.test.js"`): MP_RESULTS
- `node tests/lint/ui-integrity.js --warn-as-error`: pending (see above).

## Not verified here

- No MySQL: the migration was run only as H2 DDL (JSON/MEDIUMTEXT mapped to VARCHAR); `FOR UPDATE` and the unique key behave the same on H2, but a TEST run on MySQL is still needed.
- No device: the mini-program page and the place-sheet entry are checked by unit tests and syntax only.

## Owner / coordinator actions — TEST steps to turn shop games on

1. Apply `migration_20261117_shop_game_runner.sql` (after `20261116_shop_games_judged`).
2. Give a test shop a game with an **advanced** template (for example `{"trueFalse":{"enabled":true,"answer":false}}`), from the merchant's shop-games page or the console.
3. In TEST only, set both switches (sys_config, console 参数设置):
   - `shopgames.play.enabled` = `true`
   - `merchant.plans.ready.shopGames` = `true`
   Refresh the config cache. Never change the production values.
4. On the app or mini-program: open the shop's place, 「玩今天的小游戏」, play, 「看结果」; once at the shop (points) and once away (0 points, "only the result is kept"). Roll on the City board onto that shop and accept the offer (`source: CITY_LANDING`).
5. To turn off: set `shopgames.play.enabled` back to `false`; every route answers 409 `SHOP_GAME_NOT_OPEN` and the clients say 「店铺小游戏还没开放」.

## Open questions

- Basic shop templates (only `validation_method` 1/2/3/8/9/10, no advanced config) are listed as ready by the profile but cannot run outside a journey; the runner answers `SHOP_GAME_NOT_PLAYABLE`. Convert them to advanced templates, or should the profile count only runnable templates as ready? (Not changed: the journey shop node uses the same profile.)
- `steps` (step challenge) needs WeRun and is not allowed in a template-only session.
- Should a shop-game win count for anything on the City Monopoly board? Built as "no".
- The new tables hold no location, money or contact data, so they do not join `MemberPrivacyErasureService`; confirm.

## Overlap with the UI agent

- `fix/registration-list-r3` touches none of these files. No `ui/*` branch changes the mini-program files here.
