# W7 x-shop-games: shop games are really played and rewarded (hengyin)

Branch `feature/r-x-shop-games` from `origin/roadmap/be-integ` @ `63bc29f9d`. App side: chengyin-app `cloud/r-x-shop-games`.
Reviews: `review/opus-w7` merchant (MERCHANT-5, 6, 11) and game-core (GAME-8, 9, 15, 16). Owner decision 2 (2026-11-16) applied to shop-game prizes.

## Issues

| ID | Status | Notes |
|---|---|---|
| GAME-8 | Fixed (S, honesty + judged result); runner left open | Switch `shopgames.play.enabled` (default `false`): no play row, `result` 409 `SHOP_GAME_NOT_OPEN`, check-in answers `NOT_OPEN`. `result` now reads the player's own runtime session on the play's template (client `roundsWon` ignored). Expiry answered, not thrown (no rollback). Rotation hashes player, shop, day. `steps`/`react` are `deviceReported` and pay 0. Receipt line 128 corrected. |
| MERCHANT-5 | Partly fixed | Backend: `PLACE_PAGE` source (same trust + radius as a landing), `playOpen` on the profile, judged `finish`. Mini-program: no toast for `NOT_OPEN`. **Open:** no client can create a runtime session for a shop template outside a journey node (the runtime keys sessions by activity/topic/node), so the place-page runner in both clients is not built. Keep the switch off until it is. |
| MERCHANT-6 | Fixed (backend + app); deals pages open | `/api/merchant/plan` and the console list `effective` (`shopGames` capped by the pool, `dealsOffersPerMonth`, `ready` flags) and `notReady`. `updatePlan` refuses `extraNodeGames` beyond the pool (「超过可提供的小游戏数量」). Checkout 409 `PLAN_FEATURE_NOT_READY`; paid grant refused while a listed feature is not ready. Migration caps PRO 5 → 2 only while it still holds the seed value. **Open:** the deals self-submission pages (app + mini-program) depend on MERCHANT-4 coupons (another package); `merchant.plans.ready.dealsSubmission` stays `false`. |
| MERCHANT-11 | Fixed (app only) | See the app receipt. |
| GAME-9 | Fixed (backend keys + app launch) | Creator self-serve per-game result keys, AES-GCM at rest, verifier tries the stored key first. Console status + revoke. App side in the app receipt. |
| GAME-15 | Partly fixed (app) | App: 5×5 jigsaw, `revealTotal`, scored silent order hides the local clock. **Open:** no editor for the ambient kits exists in the app (`TemplateAdvancedGame` has no silent-order/music/time-window case), so a `scored` toggle has no home; music Play → `START_MUSIC_CORNER` not linked (the verdict row's own Start button already sends it); the mini-program has no runner for trueFalse / imageGuess / jigsaw / scratchCard. |
| GAME-16 | Fixed | Creator refused (「这是你自己藏的宝」, 403). Edits keep item ids (update in place, removed items deleted) and only finds of current items count, so an edit never completes a hunt early. Approve split into `business:treasureHunt:approve` (own route, granted to no role); console button and editor send ids back. |

## Changes, file by file

GAME-8 / MERCHANT-5
- `chengyinhub-system/.../shopgames/ShopGamePlayService.java`: `KEY_PLAY_ENABLED` + `playOpen()` (sys_config, anything but `true` = closed, errors = closed); `SOURCE_PLACE_PAGE`; `pick(member, merchant, day, n)`; `finish` = switch → lock row → expiry answered (`status:EXPIRED`) → `judge` (session `id`, `owner_type=1`, `owner_id=member`, `template_id` = play's, `started_at >= issued_at`; running → 409 `PLAY_NOT_FINISHED`, otherwise 409 `PLAY_NOT_JUDGED`) → `rewardCeiling` (`DEVICE_REPORTED`, runtime `rewardCappedToXpBadge` → `UNJUDGED_EVIDENCE`, `RewardPresencePolicy` presence at the shop → `REMOTE`) → governed award. Response adds `rewardCeilingReason`.
- `ShopGameCatalog.java`: `DEVICE_REPORTED` (`steps`, `react`), `Game.deviceReported()`, in `toMap`.
- `chengyinhub-admin/.../api/ApiShopGameController.java`: profile adds `playOpen`; doc of the routes.
- `chengyinhub-xcx/pages/roam/index.js`: `NOT_OPEN` → no toast. Test in `tests/unit/s5-shop-games-merchant.test.js`.
- `docs/roadmap/shop-games/RESULT.md`: the event-12 claim corrected.

GAME-16
- `chengyinhub-system/.../gamemech/TreasureHuntService.java`: creator refusal in `find`; `activeFound`; `validated` keeps item `id`; `write` updates/inserts/deletes per item.
- `.../gamemech/mapper/GameMechanicsMapper.java`: `updateItem`, `deleteItem`.
- `chengyinhub-admin/.../business/GameMechanicsAdminController.java`: `PUT /business/treasureHunt/{id}/approve` under `business:treasureHunt:approve`; the generic decision route refuses `approve`.
- `chengyinhub-ui/src/views/business/treasurehunt/index.vue`: approve button needs the new permission; edit form keeps item ids.

MERCHANT-6
- `chengyinhub-system/.../merchantplans/MerchantPlanService.java`: `effective`, `notReady`, `planMap`; readiness keys `merchant.plans.ready.shopGames` / `.dealsSubmission`; cap check in `updatePlan`; refusal in `checkout` and paid `grant`.

GAME-9
- `chengyinhub-system/.../externalgame/ExternalGameResultKeyService.java` (new): `issue` (owner of an approved game; 32 random bytes as hex, shown once; reissue rotates), `status`, `revoke`, `activeKey`. Wrap secret `CHENGYIN_EXTERNAL_GAME_KEY_WRAP` (or `chengyin.external-game.key-wrap`); without it 409 `RESULT_KEY_NOT_CONFIGURED`. The HMAC key is the hex string's ASCII bytes, same as the derived key.
- `ExternalGameMonetizationService.verifyAuthoritySignature`: stored key first, then the derived key, then Ed25519.
- `ApiExternalGameMonetizationController`: `POST /api/external-game/creator/games/{gameId}/result-key` (rate limit 5/min).
- `ExternalGameMonetizationAdminController`: `GET /{gameId}/result-key`, `POST /{gameId}/result-key/revoke` under `business:externalgame:resultkey`.

Migrations (`ci/migration-dependencies.json` entries added)
- `migration_20261116_shop_games_judged.sql`: sys_config `shopgames.play.enabled=false`, `merchant.plans.ready.shopGames=false`, `merchant.plans.ready.dealsSubmission=false`; sys_menu `business:treasureHunt:approve`; PRO `extraNodeGames` 5 → 2 guarded. Rollback note at the top.
- `migration_20261116_external_game_result_key.sql`: table `external_game_result_key` (no personal data); sys_menu `business:externalgame:resultkey`. Rollback note at the top.

Tests: `ShopGamesTest` (+5: switch off, judged session, away from the shop, device-reported/runtime-capped, place-page launch; updated win/loss/expiry/rotation), `GameMechanicsH2Test` (+4: creator refused, ids kept, dropped item, approve permission), `MerchantPlansTest` (+4), `ExternalGameResultKeyTest` (new, 4).

## Verification (all with `LANG=C.UTF-8 LC_ALL=C.UTF-8`)

- `mvn -B -q -pl chengyinhub-admin -am test -Dtest='*ShopGame*,*MerchantPlan*,ExternalGame*Test,GameMechanics*,*TreasureHunt*,TwoNodeSchedulingTest,SysJobStaggerPlanTest,E2eTestProvidersProfileTest,MemberPrivacyErasureServiceTest,PlayerMessagePunctuationTest,ApiRoam*'`: 168 run, 0 failures, 0 errors.
- `bash ci/migration-safety-check.sh`: 迁移安全门禁:通过. `bash ci/migration-layout-check.sh`: 迁移布局门禁:通过.
- Mini-program: `node ../ci/prepare-mini-artifacts.js`, then `node --test "tests/unit/**/*.test.js"`: base 17919 tests / 32 failing; branch 17920 / the same 32 failing (`diff` of the failing names is empty).
- `node tests/lint/ui-integrity.js --warn-as-error`: exit 1 on base and branch (pre-existing U1/U5/LEGACY items). The only difference: the scan counts 1617 backend mappings instead of 1616 (the new treasure-hunt approve route; the result-key routes are under already-scanned prefixes). No new item, no new toast, no placeholder words.

## Not verified here

- No MySQL: migrations not run; the `JSON_EXTRACT`/`JSON_SET` PRO patch and the menu inserts are read only.
- No end-to-end run of a judged shop play: it needs a runtime session on the shop's template, which only a journey node can create today.

## Owner and coordinator actions

1. Set `CHENGYIN_EXTERNAL_GAME_KEY_WRAP` (any long random secret) in TEST before creators can issue result keys. Never commit it. Changing it invalidates every stored key (creators reissue).
2. Keep `shopgames.play.enabled=false` until a client plays shop games through a runtime session. Then turn `merchant.plans.ready.shopGames` on with it.
3. Decide (receipt questions still open): event 31 on top of the place reward or instead of it; on a journey shop node, replace the author's game or add the shop game after it; the PRO plan beyond Basic (the migration caps PRO to 2 extra games; prices unchanged).
4. GAME-9: confirm creator self-serve (as built) versus operator-issued keys.
5. Grant `business:treasureHunt:approve` and `business:externalgame:resultkey` to the roles that should have them (granted to no role).

## Left open / notes

- Place-page shop-game runner (MERCHANT-5 step 2/3, GAME-8 step 2): needs a template-only runtime session in `AdvancedGameRuntimeServiceImpl` (sessions are keyed by activity/topic/node). Not attempted: a change there touches every journey game.
- Deals self-submission pages (MERCHANT-6 step 3): depend on MERCHANT-4.
- GAME-15 mini-program kits and ambient-kit editor: see the table.

## Overlap with the UI agent

- None. `ui/h-acceptance-r2` and `fix/registration-list-r3` do not touch these files.
