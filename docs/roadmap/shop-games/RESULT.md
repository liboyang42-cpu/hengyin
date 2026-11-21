# S5 shop-games: result (hengyin)

Branch `feature/r-shop-games` from `origin/roadmap/be-integ` (81b65553d). App half: chengyin-app `cloud/r-shop-games` from `origin/roadmap/integ` (9cf50900b).
This is a fresh run: the earlier launch stopped on a usage limit before it pushed anything.

## For the owner: what to approve or change

**Recommendation: yes, give different shops different games, built as data.** Each shop gets a game profile from a pool that fits its kind. The merchant picks inside the pool, operators can override, and the City Monopoly rules stay as they are.

**Pools** (the first game is the default; all games are existing play-kit game types):

| Shop kind (matched from the merchant's industry, primary first) | Pool |
|---|---|
| Café / tea (咖啡, 茶, 饮品) | 快问快答 quick quiz · 看图猜一猜 guess from the picture · 对还是错 true or false |
| Bar (酒) | 对还是错 · 看图猜一猜 · 猜数字 guess the number |
| Bakery (烘焙, 甜品) | 看图猜一猜 · 比一比价 which costs more · 猜数字 |
| Bookshop (书, 阅读) | 猜字谜 word riddle · 谜语作答 answer the riddle · 对还是错 |
| Gym (健身, 运动, 瑜伽, 攀岩, 球) | 走几步 step challenge · 变色就点 tap on the colour · 对还是错 |
| Restaurant (餐, 饭, 面, 火锅, 小吃, 美食, 烧烤, 料理) | 拍照挑战 photo challenge · 比一比价 (order challenge) · 快问快答 |
| Gallery (美术, 画廊, 展, 博物, 艺术) | 找东西 spot it (observation) · 拼图 jigsaw · 看图猜一猜 |
| Craft workshop (手作, 工坊, 陶) | 排一排 put in order · 拼图 · 拍照挑战 |
| Vintage & gifts (古着, 中古, 文创, 杂货, 买手) | 猜数字 · 比一比价 · 找东西 |
| Florist (花) | 看图猜一猜 · 找东西 · 快问快答 |
| Pet friendly (宠物) | 拍照挑战 · 看图猜一猜 · 对还是错 |
| Any other shop | 快问快答 · 对还是错 · 找东西 |

All ten seeded merchant industries (咖啡茶饮, 茶馆, 书店, 手作工坊, 古着中古, 文创杂货, 花店, 烘焙甜品, 酒馆小酌, 宠物友好) map to a kind. Anything unknown gets "any other shop", so every merchant has a default.

**Fairness rule** (enforced in code and tests, and shown in the console when broken):

1. Every shop game runs at one standard: 3 rounds, win 2, and the same reward (10 points, event type 31). No shop is easier to win and no shop pays out more.
2. Every game takes 45 to 90 seconds. Every pool averages 50 to 75 seconds.
3. Every pool has at least one game that needs neither movement nor the camera.
4. The player cannot choose or re-roll. One play per player, shop and day. The game is picked by a fixed rotation over (player, day).
5. Paid plans only change **how many** games of the same pool a shop offers (Free = 1, as today; Basic and Pro up to the 3 in the pool). Sponsorship and paid placement are never read. A test fails if the shop-game code mentions sponsor, promotion, placement, paid or money.
6. Points go through the existing governed award path. The daily cap is 30 shop-game points per member, in programme PLAY.

Change any of these in `ShopGameCatalog` (pools, lengths), the points rule (value) and `points_award_cap` (cap).

## Findings and goals

| Goal (brief) | State |
|---|---|
| 1. Category-to-pool table with a default for every category; pools comparable | Done: `ShopGameCatalog`, fairness check, tests |
| 2. Merchant choice inside the pool, allowance from merchant-plans "extra node games" (Free = 1); operator override and curation | Done here: API, admin console, mini-program merchant page. The app merchant page is in chengyin-app. The plan's `extraNodeGames` flag is now enforced for shop games. |
| 3. Roam check-in and journey shop node launch the shop's game; City Monopoly read API and hook | Roam check-in: done (`/api/roam/checkin` returns `shopGame`). Journey node and City landing: launch API done (`source=JOURNEY_NODE \| CITY_LANDING`, `poiId` or `merchantId`). **No journey client calls it yet** (open item 3). City Monopoly: read API and hook done, rules untouched. |
| 4. Location trust, no paid influence, points through governance caps | Done (see the fairness rule) |
| 5. Tests | Done: 17 JUnit tests, a console contract test, a mini-program test |

## Changes, file by file

**Backend (`chengyinhub-system`, package `business/shopgames`, new)**
- `ShopGameCatalog.java`: the 13 games (existing `GameplaySupportMatrix` keys) with standard length, the 12 kinds with pools and the industry words that select them, `allowance(extraNodeGames)`, `detectGame(validationMethod, advancedConfig)` (which game a merchant's node template plays) and `fairnessProblems()`.
- `ShopGameProfileService.java`: a pure `resolve(kind, stored, disabled, allowance)`; operator override > merchant choice (kept to pool and allowance) > pool default. Also profile reads by shop or map place, the merchant view and save, operator override (kind, games, lock) and switching a game on or off for every shop. Every write is audited (`cy_shop_game_audit`, and `business_audit_event` for operators). Missing tables read as the default profile.
- `ShopGamePlayService.java`: `launchAfterCheckin` (never throws into the check-in) and `launch` (location trust `SHOP_VISIT` plus the radius check). Both enforce one play per player, shop and day (unique key), pick by rotation, and use only games that have a matching merchant template (otherwise `NOT_READY`). `finish` records the result once, expires after 30 minutes, and awards a win through `IPointsAwardService.award(member, 31, playId)`.
- `CityShopLandingHook.java`: the read-only landing offer for City Monopoly.

**Backend (`chengyinhub-admin`)**
- `ApiShopGameController.java` (new): `GET /api/shop-games/profile`, `GET /api/shop-games/catalog`, `GET /api/shop-games/city-landing`, `POST /api/shop-games/launch`, `POST /api/shop-games/result`.
- `ApiMerchantShopGameController.java` (new): `POST /api/merchant/shop-games`, `POST /api/merchant/shop-games/save` (merchant `BASIC_READ` / `PROJECT_MANAGE`).
- `ShopGamesAdminController.java` (new): `/business/shop-games/catalog`, `/profiles`, `PUT /profiles/{merchantId}`, `PUT /games/{key}`. Reason required; permissions `business:shopgames:list|edit`.
- `ApiRoamController.java` (+5 lines): the participating branch of `/api/roam/checkin` adds `shopGame` after location trust and the radius check. Additive field; existing fields unchanged.
- `sql/migration_20261115_shop_games.sql` (new, idempotent, rollback note at the top): four tables, the points rule (id/event type 31, 10 points), the governance cap (31, PLAY, 30 a day), and the menu 店铺小游戏 under 运营规则 with two permissions granted to no role. `ci/migration-dependencies.json`: `20261115_shop_games` → points_governance, missing_business_menu, play_progress_points, roam.
- `src/test/.../shopgames/ShopGamesTest.java` (new, 17 tests): see Verification.

**Admin console (`chengyinhub-ui`)**
- `src/api/business/shopGames.js`, `src/views/business/shopgames/index.vue` (new). Two tabs. 小游戏池 lists pools by shop kind, the games and their status, and takes a game down or back up. 单店设置 searches by shop number and sets a shop's kind, the games it offers, and the lock. Uses existing Element components only; every change asks for a reason.
- `tests/unit/s5-shop-games-admin-contract.test.js` (new).

**Mini-program (`chengyinhub-xcx`)**
- `pages/merchant/shop-games/` (new page plus `view-model.js`): pick games inside the pool up to the plan allowance, and choose which of the shop's contents plays each game. Read only when an operator set or locked it. Same structure and tokens as 收款账户.
- `app.json`: page registered in the `pages/merchant` subpackage. `pages/merchant/citynode/index.{wxml,js}`: entry 店铺小游戏 in the 成为节点 operations row.
- `pages/roam/index.js` (+4 lines): after a check-in at a shop, a toast names today's game, or says the shop is still setting it up. Then the existing place detail opens, where the node's game is played.
- `tests/unit/s5-shop-games-merchant.test.js` (new).

**Docs**: `docs/roadmap/shop-games/CITY_HOOK.md` (the City Monopoly hook) and this file.

## Verification

```
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -DskipTests install                     # rc=0
mvn -B -q -pl chengyinhub-admin test -Dtest='ShopGamesTest,CityGameRulesTest,CityBoardAndTerritoryUnitTest,MerchantPlansTest'
  # ShopGamesTest 17/17, CityGameRulesTest 9/9, CityBoardAndTerritoryUnitTest 2/2, MerchantPlansTest 15/15
bash ci/migration-safety-check.sh                                              # 迁移安全门禁:通过
python3 ci/role_permission_contract.py                                         # all ✓ (三层权限一致)
node --test tests/unit/s5-shop-games-admin-contract.test.js tests/unit/admin-sfc-template-compile-contract.test.js \
  tests/unit/admin-plain-language-contract.test.js                             # 14 pass (vue-template-compiler installed --no-save)
node --test tests/unit/s5-shop-games-merchant.test.js                          # 4 pass (mini-program)
node --test "tests/unit/**/*.test.js"   # mini-program, this branch: 17646 tests, 17573 pass, 73 fail
  # The base run hit its 25-minute limit, so the 23 files holding the 73 failures (action-ledger files aside) were rerun
  # on both trees: base 29 fail, branch 33 fail. The 4 extra were caused by this package (a native <picker> and
  # tappable rows without press feedback) and are fixed: picker-unification and press-feedback now pass.
node scripts/uiaudit/build-action-ledger.js --check   # fails on the base already (pages/shezhi/app/index not in
  # route-matrix); on this branch it also names pages/merchant/shop-games/index. route-matrix.json was NOT regenerated
  # here because that rewrites other packages' entries: the integrator should run
  # scripts/uiaudit/build-route-matrix.js once after merging.
```

What the 17 JUnit tests cover:

- **Defaults for every category.** Every seeded industry maps to its kind; unknown and empty map to the default; every kind resolves to its default game.
- **Comparable pools.** No fairness problems, and every game is an existing game type.
- **Template detection.**
- **Plan allowance.** Free = 1, Basic = 3 (pool cap); an unreadable plan gives 1.
- **Stored choice.** Kept to pool and allowance; a game switched off drops out; when all games are off, the general pool takes over; an operator override wins.
- **Merchant save.** Refused over the allowance, outside the pool, or with mismatched content; nothing is written on a refusal.
- **Operator profile.** The merchant cannot change it.
- **Operator writes.** A reason is required.
- **Check-in launch.** A check-in launches the default game with the shop's matching template, and the same day returns the same play without a new write. A shop with no matching content answers `NOT_READY`. A broken database answers `available=false`, never an exception. In `/api/roam/checkin`, the launch sits after trust and after the radius check.
- **Rotation.** The game is picked deterministically.
- **Results.** A win awards once through the governed award path. A loss, an expired play or a repeated result awards nothing.
- **Migration.** It seeds the cap and the unique key and grants no role.
- **No paid influence.** A test checks the shop-game code for paid-influence words.
- **City Monopoly untouched.** No `citygame` class references shop games, and the hook only reads.

## Not verified

- The migration against MySQL, and the services against a real schema. The SQL is MySQL-only; the tests use a fake `JdbcTemplate`.
- The console page in a browser, and the mini-program page in WeChat DevTools or on a device.
- `/api/roam/checkin` end to end. It was checked by a source-order test and by service tests.

## Owner actions and open questions

1. **Approve or change the pools, the defaults and the fairness rule above.**
2. Run `migration_20261115_shop_games.sql` after `20261115_points_governance`, then grant `business:shopgames:list` (and `:edit`) to the operator roles.
3. **Journey shop nodes do not call the launch yet.** The API takes `source=JOURNEY_NODE` with `merchantId` or `poiId`. Wiring it into the journey runner (app `PlayAdvancedView`, mini-program journey pages) belongs to the journey owner, because a journey node already plays its author's game. Decision needed: should a shop node in a journey replace the author's game with the shop's game, or offer it as an extra after the node?
4. **The result is reported by the client** (`roundsWon`), like the other client-side mini-games. The points cap limits abuse to 30 points a day. Server-checked answers would need the play-kit runtime to send the result instead; that is follow-up.
5. ~~The mini-program plays the shop game through the existing node completion on the place detail. That path awards its own governed node points (event 12)~~ **Corrected by W7 x-shop-games (GAME-8):** the place detail plays the place's own template through `/api/city/nodes/{id}/complete`, which pays no node points and knows no play id; event 12 is paid only by journey stops. No client plays the shop game yet, so `shopgames.play.enabled` keeps shop games closed (see `docs/roadmap/x-shop-games/RESULT.md`). Decision still needed: event 31 on top of the place reward, or instead of it.
6. City Monopoly: decide whether landing on a shop should offer the game (`GET /api/shop-games/city-landing`). A shop-game win changes nothing on the board unless the City Monopoly owner adds that rule.
7. Merchant-plans: Basic and Pro list 2 and 5 extra node games, but a pool has 3 games, so both plans give 3. Either shrink Pro's number or grow the pools.
8. No hard-coded secrets were found in the files touched.
