# x-plain-copy (backend + mini-program) — receipt

Branch `feature/r-x-plain-copy` from `origin/roadmap/be-integ`. Wording only: no codes, routes, fields or behaviour changed.
Scope reviewed: every player-facing Chinese literal added in `63bc29f9d..origin/roadmap/be-integ` (212 Java lines, about 140 mini-program lines). Admin-only texts (`@Log`, `@Operation`, money-switch and refund admin errors) were left alone.

## Backend changes

| Class and line | Before | After | Why |
|---|---|---|---|
| `CityEventNotifier` monopolyLost/defended/decaying/released (W7 section only; 8 strings) | 你的据点 / 你失去了一个据点 / 据点守住了 / 据点需要照看 / 据点已重新开放 | 你的地点 / 你失去了一个地点 / 地点守住了 / 地点需要照看 / 地点已重新开放 | one name: 地点 (据点 is also the Lv2 level name); en already said "place" |
| `ApiPlayWalkRouteController` :62, :81 | 步行路线暂时无法读取 | 步行路线暂时加载不了，请稍后再试 | plain words; says what to do next |
| `ApiShareLinkController` :42 | 链接不对 | 这个链接打不开，请对方重新分享 | says what happened and what to do |
| `ChannelEventService` :148 | 俱乐部暂时没有主理人，暂不能发活动 | 俱乐部暂时没有主理人，还不能发活动 | no doubled 暂 |
| `ShopGamePlayService` :236, `ShopGameSessionService` :71, :132 | 店铺小游戏还没开放 | 店铺小游戏还没开放，开放后再来 | next step; matches the app |

Classic (non-Monopoly) territory pushes (`challenged`, `lost`, `weeklyYield` with 城市币) are older code that W7 did not change. Classic territory keeps city coin, so they were left as they are.

## Mini-program changes

| File and line | Before | After | Why |
|---|---|---|---|
| `pages/play/utils/shop-game-runner.js` :65-66 | 没有找到这局小游戏，回到据点再开一局。 | 没有找到这局小游戏，回到地点页再开一局。 | one name: 地点 |
| `pages/play/utils/lock-card.js` :7 | 前路未知 · 先完成前置关卡 | 前路未知 · 先完成前面的站 | no jargon |
| `pages/club/detail/index.js` :2776, :2779 (toast) | 加入失败 | 加入失败，请稍后再试 | next step; 10 chars, within the 16 limit |

## Checks

- Compile: `LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -DskipTests compile` passes.
- Tests: `mvn -B -q -pl chengyinhub-admin -am test -Dtest='PlayerMessagePunctuationTest,CityEventNotifierTest,ApiPlayWalkRouteControllerTest,ShareUrlLinkServiceTest,ShopGamesTest,ShopGameRunnerTest,ChannelEventServiceTest'`: 79 tests, 0 failures.
- Mini-program setup: `node ../ci/prepare-mini-artifacts.js` OK.
- Mini-program unit tests: `node --test "tests/unit/**/*.test.js"` gives 17934 pass and 32 fail, both before and after. The failing tests are the same set, so nothing new fails.
- Lint: `node tests/lint/ui-integrity.js --warn-as-error` exits 1 both before and after. Its findings are identical; they are pre-existing (money-payout silentError, club/membership 17-char toast).
- No migrations, jobs or new tables, so the migration, stagger, scheduling and erasure ratchets are not touched.

## Needs a product decision (not changed)

- `CityMonopolyService` APP_ATTEST_REQUIRED says 这台设备暂时不能参加城市大富翁 without a next step. I don't know what the player can do (update iOS? use another device?), so I left it.
- `pages/roam/index.js` toast 今天这家店的小游戏：+ game name can go over 16 characters with a long name. Moving it into the page needs a layout change, which is outside this package.
- `ApiActivityController` 仅俱乐部主理人或商家可发布活动 and `ChannelEventService` EVENT_POST_FORBIDDEN differ in who may post: the activity route allows merchants, the channel route allows permitted roles. Wording follows each route; confirm the rule is intended.
- Classic territory pushes still say 据点 and 城市币. The owner should confirm whether classic territory stays.

## Overlap with the UI agent

None. `fix/registration-list-r3` files (registration list) were not touched; `pages/club/detail/index.js` was touched only for the join-failed toast, not leave-club (F-08).
