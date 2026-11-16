# W7 fix package x-mp-parity — hengyin receipt (backend + mini-program)

Branch `feature/r-x-mp-parity` from `origin/roadmap/be-integ` (`9aa0b2a2a`). App work (MPP-1/2/3/4/5/9) is on chengyin-app
`cloud/r-x-mp-parity` with its own receipt. Review: `origin/review/opus-w7:docs/review/opus/mp-parity/ISSUES.md`.

## Issues

| ID | Result |
|---|---|
| MPP-7 | **Fixed**: backend walk-route endpoint, mini-program in-story walking guide, persistent lock card. |
| MPP-6 | Open (owner decision on external games; no `externalGamesApp` flag added without it). |
| others | App-side, see the chengyin-app receipt. |

## Changes

### Backend
- `chengyinhub-system/.../service/WalkRoute.java` (new): `{distanceM, durationS, polyline:[[lat,lng]...]}` view.
- `chengyinhub-system/.../service/ITopicRouteService.java`: `walkRoute(fromLat, fromLng, toLat, toLng)` (default null).
- `chengyinhub-system/.../service/impl/TopicRouteServiceImpl.java`: the Tencent direction request is extracted into `requestRoute` (chapter `routeGeometry` unchanged); `walkRoute` always uses walking mode, decodes distance (m), duration (min → s) and the compressed polyline with the existing `decompress`; origins snap to a 20 m grid (the longitude step follows the snapped latitude) and share a separate 60 s cache; in-flight de-duplication as for chapter routes. Unready client / provider failure → null.
- `chengyinhub-admin/.../controller/api/ApiPlayWalkRouteController.java` (new): `GET /api/play/walk-route?topicId=&nodeId=&lat=&lng=[&activityId=]`, GCJ-02. Signed-in member (`ROUTE_LOGIN_REQUIRED`), node of that topic (`ROUTE_NODE_NOT_FOUND`), running session on the topic via `ITopicRouteRuntimeService.hasRunningSession` (`ROUTE_NOT_PLAYING`, read-only — `canPlay` was avoided because it can create sessions), origin within 20 km (`ROUTE_TOO_FAR`), otherwise `ROUTE_UNAVAILABLE`. `@RateLimiter(time = 60, count = 12, limitType = MEMBER)`. No new secret: the existing server-side Tencent key is reused. No SQL migration, no new table, no scheduled job.

### Mini-program
- `chengyinhub-xcx/pages/play/utils/walk-guide.js` (new, pure): route reading, metres to line, remaining distance along the line, 「还有 X 米 · 约 N 分钟」, re-plan rule (3 fixes > 40 m off, at most 2 re-plans), 50 m hand-back, failure copy.
- `chengyinhub-xcx/pages/play/utils/lock-card.js` (new, pure): the lock text (server reason first, the original `_lockedTip` fallbacks).
- `chengyinhub-xcx/pages/play/index.js`: `startWalkGuide`, `_requestWalkRoute`, `stopWalkGuide`, `_walkGuideOnFix` (one call from the existing location handler); `_lockedTip` shows the card instead of the toast (the paused-stop toast is unchanged); `closeLockCard`.
- `chengyinhub-xcx/pages/play/index.wxml`: story-map card gets 「在小程序里走过去」/「收起路线」 next to 导航过去 and draws the walk line through the existing `map-polyline`; the lock card at page level.
- `chengyinhub-xcx/pages/play/index.wxss`: `.story-map__acts`, `.lock-card` (same look as the existing story-map card; no new colours).
- Tests: `tests/unit/mpp7-walk-guide.test.js` (8); `tests/unit/action-ledger.test.js` two pinned control ids shifted by position only (357→363 #59→61, 547→553 #80→82).

## Verification
- `LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -DskipTests compile`: OK.
- `mvn -B -q -pl chengyinhub-admin -am test -Dtest='TopicWalkRouteTest,ApiPlayWalkRouteControllerTest,TopicRouteServiceImplTest,PlayerMessagePunctuationTest,ApiPlayEnvironmentControllerTest,E2eTestProvidersProfileTest'`: all pass (4 + 2 + 8 + 2 + 1 + 4).
- Mini-program: `node ../ci/prepare-mini-artifacts.js`, `node --test "tests/unit/**/*.test.js"`: base 17887 pass / 32 fail; branch before the ledger-pin fix 17893 / 34, the two new failures (ledger pins, 导航过去 guard pin) fixed in `264815bb3`; re-run of those files: only `按钮台账不把定位回调的 reveal…` fails, which also fails on the base.
- `node tests/lint/ui-integrity.js --warn-as-error`: same result as the base (U1 FAIL pre-existing; static paths 1263 → 1264 because the new route is used and has a backend mapping 1616 → 1617); no placeholder words, no new toasts.
- Not run: `ci/migration-*` (no migration), the full backend suite.

## Needs a device / the owner
- Real-device walk on TEST: start an orienteering run, open the story map, 「在小程序里走过去」, walk off the line to see the re-plan, arrive within 50 m.
- Owner: confirm the Tencent direction quota and cost on the existing server key (each walk start and each re-plan is one request; cached 60 s per 20 m cell and node; 12/min per member).
- `hasRunningSession` is false for a linear (non-session) route before the first recorded stop; the guide then says 「开局后才能看步行路线」 and 导航过去 still works.
