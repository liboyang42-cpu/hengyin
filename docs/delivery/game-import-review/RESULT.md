# feature/game-import-review — result (2026-11-14)

Finished on `feature/be-gap-rewards-workshop`, which merged `origin/feature/game-import-review` (`e10a8e98f`) first, as a
fast-forward from `origin/integration/backend-app-r2` (`2e6f6651c`). The handover in `HANDOVER.md` describes the routes, the
state machine and the config keys; this file records what was left and how it was closed. Production was not touched.

## What changed after the handover

* **Druid catalog fix verified.** `GET /api/external-game/catalog` (newest APPROVED per game) and `GET /business/external-game/reports`
  now return 200 on a Druid-backed app (8121). The WIP commit had only compiled.
* **Shared state machine.** The transition rules (PENDING → APPROVED / REJECTED / CANCELLED / ESCALATED, ESCALATED →
  APPROVED / REJECTED / CANCELLED) moved into `com.chengyinhub.business.review.ReviewStateMachine`, so that workshop
  professional submissions use the same machine (see `docs/delivery/be-gap-rewards-workshop/RESULT.md`).
  `ExternalGameReviewService` keeps its constants and behaviour: it passes `ReviewStateMachine.sources(target)` where it used to pass
  literal sets. `ExternalGameReviewMysqlIntegrationTest` is still 7/7.

## 8121 curl checks (loopback, DB `cy_import_test`, Redis db 10, jar from this branch)

Script: `smoke_8121.py` (this folder). **24/24 OK.**

| Check | Result |
|---|---|
| `GET /api/external-game/signing-key` anonymous | 200, `configured:true` |
| submit signed out | 401 |
| player submit (archive form) | 200 `PENDING`, server Ed25519 signature (client `signature` ignored) |
| same `requestId` again | same `submissionId` |
| another author, same game id | 409 `GAME_ID_TAKEN` |
| `GET …/submissions/mine` | 200 |
| admin login, queue, detail | 200; queue counts by status |
| approve | `APPROVED` |
| **catalog** | **200 with the approved game** (the Druid `WHERE 1 = 1` fix) |
| catalog detail → signed `downloadPath` → download | 200; a tampered `sig` is 403 `DOWNLOAD_LINK_INVALID` |
| reject without / with a reason | 400 `BAD_REQUEST` / `REJECTED`, author sees `rejectReason` |
| escalate, then author cancel | `ESCALATED` → `CANCELLED` |
| report, second open report | 200 / 409 `REPORT_ALREADY_OPEN` |
| **admin reports** | **200** (the `WHERE id > 0` fix) |

TEST-only setup on `cy_import_test`: one `sys_user` (`rwtest_admin`, user_id 1, role 1) with a random password kept in a
`chmod 600` file under `/data/workspace/be-import-out` (never printed), and `sys.account.captchaEnabled=false`. The admin password
must be 5–20 characters (`UserConstants.PASSWORD_MAX_LENGTH`); the first 24-character one was refused as a mismatch.
8121 was stopped after the run. As the handover asked, `cy_import_test` and `/data/workspace/be-import-out` were deleted afterwards.

## Still open (app side, not this repo)

* The app keeps its hard-coded HMAC key in `ExternalGameImport.swift`; it should verify the Ed25519 signature with the key from
  `/api/external-game/signing-key` and mark the new routes reviewed in `ChinaReviewedRouteCapability.swift`.
* No controller-level multipart test was added (`ApiExternalGameController.read`); the 8121 run covers the archive form end to end.
