# x-be-test: post-deploy check on TEST

Base URL: `https://8120-agqyfqxs5vwolymtoibzq3fhde.instabox.tech`. Sign in through the TEST API as usual (the `e2e-code-*` mini-program login still works: the deploy script builds with `-Pe2e-test-providers`) and send the token as `Authorization: Bearer <token>`.

All routes below exist only on the merged line (`feature/r-x-be-test`), except the last two rows, which are controls.

- **Before the deploy** (r3 jar), every merged-only route answers **HTTP 404** with the envelope `{"code":404,"msg":"请求路径不存在"}`.
- **After the deploy**, the route exists. A read with valid parameters answers **`code: 200`**. "200 or refusal" means the route can also answer a business refusal for this account, for example 403 or 404 for a channel or club it cannot see, but never `请求路径不存在`. That is still a pass.

| # | Area | Method and path | Parameters | Account | Before | After |
|---|---|---|---|---|---|---|
| 1 | Channel events (SOC) | `GET /api/channels/{id}/events` | `id` = a channel the player belongs to (from `GET /api/channels` or the club detail) | player 9004 | 404 `请求路径不存在` | 200 (list; may be empty); an unknown channel gives business 404 `频道不存在` |
| 2 | Channel events | `GET /api/channel-events/{id}` | `id` = an event from row 1, or `0` | player 9004 | 404 `请求路径不存在` | 200 for a real event; for `0` a business 404 `频道不存在` (`errorCode` `CHANNEL_NOT_FOUND`), **not** `请求路径不存在` |
| 3 | Growth loops: win-back preference | `GET /api/growth-loops/winback/pref` | none | player 9004 | 404 `请求路径不存在` | 200 (`data` has the preference) |
| 4 | Growth loops | `GET /api/growth-loops/invite/summary` | none | player 9004 | 404 `请求路径不存在` | 200 |
| 5 | Compliance: age status | `POST /api/compliance/age/status` | empty body `{}` | player 9004 | 404 `请求路径不存在` | 200 (`data` says whether 18+ is confirmed) |
| 6 | Roam: fog cells (CITY-16) | `GET /api/roam/fog/cells` | `south=23.10&west=113.24&north=23.16&east=113.30` | player 9004 | 404 `请求路径不存在` | 200 |
| 7 | City Monopoly | `GET /api/city-game/territory/capabilities` | none | player 9004 | 404 `请求路径不存在` | 200 |
| 8 | City coin passport (CITY-7) | `GET /api/city-coin/me` | none | player 9004 | 404 `请求路径不存在` | 200 |
| 9 | Club invites (social) | `GET /api/club/invites` | `clubId` = a club 9004 owns or manages | player 9004 | 404 `请求路径不存在` | 200; an unknown club gives business 404 `俱乐部不存在或已关闭`; 403 if 9004 cannot manage it |
| 10 | IM conversations page | `POST /api/im/conversations/page` | form `size=20` | player 9004 | 404 `请求路径不存在` | 200 |
| 11 | Async duels (game core) | `GET /api/duel/mine` | none | player 9004 | 404 `请求路径不存在` | 200 |
| 12 | Discovery (onboarding/home) | `GET /api/discovery/near-you` | `minLat=23.10&maxLat=23.16&minLng=113.24&maxLng=113.30` | player 9004 | 404 `请求路径不存在` | 200 (may be empty while scoring is off) |
| 13 | Money switch (read only) | `GET /api/app/money-switch` | none | player 9004 | 404 `请求路径不存在` | 200; `data` shows money off unless TEST turned it on |
| 14 | Merchant plan (merchant) | `POST /api/merchant/plan` | JSON `{}` | merchant 9002 | 404 `请求路径不存在` | 200 (current plan) |
| 15 | Control: roam stamp list | `POST /api/roam/stamp/list` | JSON `{}` | player 9004 | 200 | 200 (same shape; place stamps stay off: `chengyin.roam.place-stamp.enabled` defaults to false) |
| 16 | Control: in-game channel summary | `GET /api/im/ingame/summary` | none | player 9004 | 200 | 200 (the route is on both lines; a control that the channel code still answers) |

## Also check (r3 behaviour that must survive)

- `POST /api/login/code` with `code=e2e-code-9004` returns a token (r3 B4). The stub now lives in `chengyinhub-e2e`, which is in the jar only with `-Pe2e-test-providers`.
- Multipart `POST /api/club/list` returns `code: 415`, `errorCode: UNSUPPORTED_MEDIA_TYPE` (r3 B8).
- A business refusal keeps r3's 4xx body code, for example `POST /api/coop/review/summary` with `{}` returns `code: 400`, `msg: 缺少被评人`.
- With the header `X-Error-Contract: 2`, the same refusal comes back with HTTP 400 and an `errorCode` field (be-integ R5).

## Rehearsed in the cloud (2026-11-21)

Every row was called against both jars on the same local MySQL 8.0 database, one built like TEST (r3 schema + this package's migrations), as player `e2e-code-9004` (row 14 needs merchant 9002, which that database does not have). r3 jar (`8ef24ebb6`, 123,502,019 bytes, the Wave 9 size): rows 1–14 `code 404 请求路径不存在`, rows 15–16 `200`. Merged jar: rows 3–8 and 10–13 `200`, rows 1, 2 and 9 the business 404s above, rows 15–16 `200`, and the four r3 checks below as stated.

If a merged-only row still answers `请求路径不存在` after the deploy, the old jar is still running. Look at the last `RESULT:` line of the deploy log in `/data/workspace/be-int/x-be-test/`.
