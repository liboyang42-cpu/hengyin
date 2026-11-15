# Chengyin backend acceptance — round r3 (`integration/backend-app-r3` @ `81db27155`)

- **Target:** TEST server `https://8120-agqyfqxs5vwolymtoibzq3fhde.instabox.tech` (be-int, DB `cy_e2e_r2`). Production was not touched.
- **Live run:** 2026-11-15 17:05 UTC from the owner's Mac (the cloud session could not reach the TEST host; see the history note at the end). Full run of all 18 flows: `results.json` / `results.md`. Targeted re-runs afterwards: `results-rerun.json`.
- **Accounts:** only the e2e fixture phones 13900000001–13900000009. SMS codes were read from TEST Redis (`be-int-redis`, db 9).
- **Result: 0 of 18 flows pass every step.** Most failures are TEST data or harness expectations, not product defects. The confirmed product defects are:
  - **Backend:** 3 medium (B1 linear route session shown as ACTIVE after it ended; B2 over-balance withdrawal gives a generic error; B3 roam records have no clock-skew tolerance) and 5 low (B4–B8).
  - **App (Flutter `chengyin-app`):** 4 (A1–A4) confirmed by sending the app's request shape. The native iOS app (`native-ios`) avoids A2–A4 and its main play path avoids A1, but its store-day path may hit A1 (see A1).

## 1. How to run

```bash
cd hengyin
python3 -m venv "$TMPDIR/qa-venv" && "$TMPDIR/qa-venv/bin/pip" install requests
# SMS codes: read from TEST Redis on the `work` VPS (container be-int-redis, db 9)
export E2E_CODE_CMD='instabox exec work -- docker exec be-int-redis redis-cli -n 9 GET sms_{phone}_code'
#   or pre-plant a fixed code in TEST Redis and: export E2E_SMS_CODE=123456
#   or per-phone tokens: export E2E_TOKEN_13900000004=...
"$TMPDIR/qa-venv/bin/python3" qa/acceptance/run_all.py            # all 18 flows -> results.json + results.md
"$TMPDIR/qa-venv/bin/python3" qa/acceptance/run_all.py f07 f11    # a subset
```

How the harness behaves (`harness.py`):
- It refuses any base URL that is not `*.instabox.tech` or loopback `:8120`.
- It logs in only the fixture phones `13900000001`–`13900000009`. F18 (account deletion) now defaults to `13900000001` (player 9001), refuses any other phone range, and always cancels in the same run.
- Every step records the request, HTTP status, business `code`, latency and PASS/FAIL, with a suspected owner for each FAIL.
- Tokens, SMS codes, challenge/result tokens and the signature part of `v1.*` entry codes are redacted from the results.

**SMS rate limits.** These matter for re-runs:
- `/api/sms/send` allows one send per phone per 60 s, and 10 per phone per day. Clear the daily counter on TEST with `DEL sms_send_phone_day_<phone>`.
- It also keeps a per-IP window. Behind the instabox proxy every client appears as `127.0.0.1` (`sms_send_ip_window_127.0.0.1`), so several logins in quick succession fail with "发送过于频繁". `run_all.py` paces logins naturally. For targeted re-runs, log in each member about 10 s apart.
- F18 now waits 65 s (`E2E_SMS_COOLDOWN`) before each new code.

**Changes to the test code in this round** (QA harness only, no product code):
- F08 anchors the archived tile time on the server's `finishedAt` and adds a separate client-clock step.
- F17 replays the preflight `requestId` into `withdrawal/create`, and gives the over-balance case its own challenge.
- Redaction now covers more secret fields.
- F18 uses fixture phone 13900000001 and waits out the SMS cooldown.

## 2. Live results per flow

Counts are from the full run (`results.md`). "Re-run" is from `results-rerun.json`. During R1–R3 the start of activity 992031 was briefly set to now − 10 min, then restored to 2026-11-22 14:00–17:00. Verdict key:
- **Partial:** every executed step passed; others were skipped for missing TEST data.
- **Fail (data):** the failures are caused by TEST data or a harness expectation; no product defect.
- **Fail:** at least one confirmed product defect.

| Flow | Full run (pass/fail/skip) | Re-run | Verdict | Cause of each non-pass step |
|---|---|---|---|---|
| F01 Sign-in | 7 / 1 / 0 | — | Fail | `login/code` with `e2e-code-*` → "微信服务暂时不可用" (B4). SMS login and the role in `/api/userInfo` pass for player, club and merchant. |
| F02 Browse home | 8 / 1 / 0 | — | Fail (data) | Upcoming list page 1 (6 of 12 rows) does not include 992031. The harness checks page 1 only; no defect shown. |
| F03 Activity detail | 5 / 1 / 0 | — | Fail (data) | Anonymous `activity/info 992031` returns the club gate (`gate:true`, "这一场由「城瘾跑团」执行，加入后查看"), not the detail. The harness expected the full detail; this looks like intended gating (product to confirm). The signed-in detail passes. |
| F04 Register and pay | 8 / 1 / 3 | R3 free path as new player 9001: 8 / 1 / 3 | Partial | The free path passes end to end, including the quote in the app's shape (S8 is not a defect) and the idempotent create replay. Paid path: `payment-readiness` = "支付服务暂不可用" (TEST has no pay config); paid steps skipped (no paid fixture). |
| F05 Ticket QR | 4 / 1 / 0 | — | Fail | `registration/list` returns `data={total,rows}`; the Flutter app reads `data` as a List (A4). Entry code issue and the foreign-order refusal pass. |
| F06 Check-in | 2 / 3 / 0 | R1 live window: 4 / 1 / 0 | Fail | Full run: the scan was refused with "俱乐部场次开始前60分钟内才可核销" because 992031 starts on 2026-11-22 (data). In the live window: owner scan → 核销成功 and rescan → "该订单已核销" `alreadyVerified:true`, both pass. Remaining failure: self check-in in the app's shape (A1). |
| F07 Play game | 3 / 10 / 0 | R1 (9009) 3 / 10; R2 (9004) 5 / 8; R3 (new player 9001) 8 / 5 | Fail | 9009 and 9004 hit B1 (session ended but shown as ACTIVE). With new player 9001: nodes, route-state, action-context, help ladder, help/use and help/use replay (`chargedPoints 0`) pass. `answer 1921` → "答案不正确" (B5, seed template has no `question_answer`), so the action-receipt echoes that. App-shape answer (A1) and POST ending (A2) also fail. |
| F08 Roam and fog | 9 / 1 / 0 | — | Fail | All server-anchored steps pass. Records stamped with the client clock are rejected "采集时间不属于原行程" (B3; the TEST VPS is ~6 s ahead). |
| F09 City Monopoly | 3 / 0 / 6 | — | Partial | season/current, territory/map and points pass. Claim, rent and challenge are skipped: no monopoly board/POI fixture. |
| F10 Club join and chat | 9 / 1 / 0 | — | Fail | `club/list` as multipart (the Flutter app's encoding) → Content-Type not supported (A3). Join, approve, members, club chat and im/send/messages pass. |
| F11 Messages | 8 / 2 / 0 | R4: 8 / 2 | Fail (data) | DM chain passes (start, idempotent start, send, receipt, receiver messages, unread, read). Group steps fail on fixtures: conversation 3 is a system conversation; with conversation 1, 9009 has left club 7001 ("已退出俱乐部"). Group send itself passes in F10. Side finding B6. |
| F12 Publish topic | 1 / 0 / 4 | — | Partial | Only identity/status ran. Skipped: no APPROVED cover media asset on TEST. |
| F13 Publish activity | 1 / 5 / 0 | — | Fail (data) | Club 9003 is at its activity quota: "已达到当前身份活动数量上限（3）" (S12); the later steps cascade from that. "Player cannot publish" passes. |
| F14 Merchant verify | 1 / 1 / 6 | — | Fail (data) | Merchant 9002 is at its coupon quota ("已达到当前身份优惠券数量上限（3）"). No granted coupon for 9004, so verification is skipped. |
| F15 Merchant refund | 3 / 0 / 2 | — | Partial | Orders, aftercare list and the player refusal pass. Detail/respond skipped: no pending refund_application. |
| F16 Creator earnings | 8 / 1 / 0 | — | Fail (data) | `wallet/stages` sent as a form → Content-Type not supported. Native iOS sends JSON (`WalletCommerceService.swift:28`); the Flutter encoding is unverified, so this is not a confirmed defect (see B8). |
| F17 Withdraw (TEST) | 10 / 1 / 0 | — | Fail | Preflight → confirm → create → list → delete (undo) pass. Over-balance → generic "提现申请失败，请稍后重试" (B2). |
| F18 Account deletion | 5 / 0 / 5 | R4/R5 on 13900000001: apply, revoke, cancel pass | Partial | Full run: skipped after the precheck because the apply SMS hit the cooldown (harness, now fixed). Re-run: apply → PENDING, old token → 401, cancel → CANCELLED. Side finding B7. Account 9001 is back to usable. |

## 3. Confirmed defects (live), with repro

All repros run against TEST with a fixture token (`Authorization: Bearer <token of the member>`).

### Backend

| # | Severity | Flow | Defect | Exact repro | Evidence |
|---|---|---|---|---|---|
| B1 | **Medium** | F07 | For a LINEAR route, `route-state` and `action-context` treat the member's latest `topic_route_session` as ACTIVE even when its status is COMPLETED (1) or ENDED. They return PLAYABLE nodes and hand out that dead session. `play/answer` then gets 409 `INTENT_SESSION_ENDED` "原局已结束", `play/help*` gets 400 "本局已结束，不能使用帮助", and no new run is started, so the player is stuck. Code: `TopicRouteRuntimeServiceImpl.toLinearView`/`linearState` derive status only from node-completion facts and ignore `session.status`; `selectCurrentByOwner` takes `max(id)`. The branch-graph `toView` does honour `session.status`. | As 9004: `GET /api/play/route-state?activityId=992031&topicId=992001` → `sessionId 993803, status ACTIVE` (DB `status=1, completed_at=2026-11-10`). `GET /api/play/action-context?…&nodeId=992021` → `routeSessionId 993803`. `POST /api/play/answer` (form: activityId, topicId, nodeId=992021, answer, routeActionId=<new>, expectedRouteVersion=0, routeSessionId=993803) → 409. Same for 9009 / session 1. | rerun R1, R2 |
| B2 | **Medium** | F17 | A withdrawal over the available balance returns the generic `code 500` "提现申请失败，请稍后重试". The user is not told the balance is too low, and the message invites a pointless retry (S7). | As 9003: `POST /api/fund/preflight/bank-withdrawal` with `amount=99999999` and bank fields → confirm → `POST /api/withdrawal/create` with the same requestId, `withdrawalAmount=99999999` and the challengeId. | full run F17 |
| B3 | **Medium** | F08 | `POST /api/roam/session/records` rejects a tile whose `collectedAt` comes from a phone clock a few seconds behind the server: "采集时间不属于原行程" (`RoamSessionWriteService.validateArchive` requires `[startedAt, finishedAt]` on the server clock, with no tolerance). Real phones drift, so fog tiles are silently lost. The same request with a server-anchored time passes. The TEST VPS is also not NTP-synced (measured +5.7–5.9 s), which makes it worse on TEST. | As 9004: `roam/reveal` (new session) → `roam/finish` → `POST /api/roam/session/records` with `records=[{"tile":"<tile>","collectedAt":<local ms at reveal + 500>}]` → code 500. | full run F08 |
| B4 | Low (test infra) | F01 | `/api/login/code` with `e2e-code-*` → "微信服务暂时不可用，请稍后重试". The e2e-test WeChat session stub is not wired for the `druid,e2e-test` profile, so there is no remote e2e login path (S6). | `POST /api/login/code` with form `code=e2e-code-9004`. | full run F01 |
| B5 | Low | F07 | A basic text answer (`validation_method=1`) is checked only against `cms_member_template.question_answer`. A template whose answer is only in `advanced_config_json.qa.answerText` (the seed template 992101 has `qa.answerText="1921"`, `question_answer=NULL`) answers every input with "答案不正确" `answerWrong:true` instead of a config error, so the node can never be completed. Fix the seed and/or have the backend refuse (`CONFIG_INVALID`) or publish-validate such templates. | As new player 9001 in a live window: action-context → `POST /api/play/answer` with `answer=1921` and the context fields → "答案不正确". | rerun R3 |
| B6 | Low | F11 | `im/conversations` returns a negative `counterparty.memberCount` (−16) for the club_7001 conversation. | As 9004: `POST /api/im/conversations` → the item with `bizKey club_7001` has `memberCount: -16`. | full run F11 |
| B7 | Low | F18 | After `deregister/cancel`, `deregister/status` returns `status CANCELLED` and still carries the old `executeAfter` (2026-11-23), not NORMAL/null. A client could keep showing the deletion countdown. Confirm the intended contract. | As 13900000001: apply → cancel → `GET /api/user/deregister/status`. | rerun R5 |
| B8 | Low | F07/F10/F16 | A wrong HTTP method or content type comes back as HTTP 200 + `code 500`, with Spring's own text ("Request method 'POST' is not supported", "Content-Type 'multipart/form-data…' is not supported") instead of 405/415 and a user-safe message. | `POST /api/play/ending`; multipart `POST /api/club/list`; form `POST /api/wallet/stages`. | full run |

### App

Owner: the Flutter `chengyin-app` (file references from the static review). The native iOS app was checked by reading its source in `native-ios`; this was not run live.

| # | Severity | Flow | Defect | Live repro | Native iOS |
|---|---|---|---|---|---|
| A1 | **High** | F06, F07 | `play/answer` and `play/checkin` are refused with "必须提供动作标识、期望版本和原运行时身份" (`INTENT_INPUT_INVALID`) unless the request carries `routeActionId`, `expectedRouteVersion` **and** `routeSessionId` from `GET /api/play/action-context`. The Flutter app omits `routeSessionId` (`play_api.dart:738`) and never calls action-context; `submitCheckin` (`play_api.dart:330`) sends no node identity. No Flutter answer or check-in can succeed (S1). | `POST /api/play/answer` with form activityId=992031, topicId=992001, nodeId=992021, answer, routeActionId, expectedRouteVersion (no routeSessionId) → 500 `INTENT_INPUT_INVALID`. `POST /api/play/checkin` with activityId, code, routeActionId, expectedRouteVersion → same. | Main play path is OK (`PlayExperienceService` binds action-context). The store-day path (`Core/PlayStoreDayService.swift:99-124`, checkin/answer) sends only scope + code/answer, so it likely hits the same refusal. Not verified live. |
| A2 | Medium | F07 | The app calls `POST /api/play/ending` (`play_api.dart:586`); the backend maps only GET, so the ending letter never loads (S2). | `POST /api/play/ending?activityId=992031&topicId=992001` → "Request method 'POST' is not supported". | OK (GET, `PlayExperienceService.swift:153`). |
| A3 | **High** | F10 | The club APIs take JSON (`@RequestBody`), but the app sends multipart FormData (`club_api.dart:32,62,74,90,102`). `club/list` fails live; detail, members, join and quit carry the same annotation (S3). | `POST /api/club/list` as multipart → Content-Type not supported. | OK (JSON, `ClubService.swift:41`). |
| A4 | **High** | F05 | `registration/list` returns `data={total, rows}`, but the app reads `body['data'] as List` (`activity_api.dart:325`), so parsing the ticket wallet throws (S5). | `POST /api/registration/list` → `data` is an object. | OK (reads `rows`, `TicketWalletService.swift:13`). |

### Needs a product decision (not counted as defects)

- **Multiple active free registrations.** 9004 holds three active free registrations for activity 992031 (992197, 992198 from seeding; 992200 created by this run's F04 with a new requestId). The create replay with the same requestId is idempotent, but a new requestId creates another order. Confirm whether one member may hold several free registrations for one session.
- **Anonymous activity detail.** For a club-run activity it returns only the club gate (F03). Confirm that this is intended for the share and browse entry points.
- **SMS per-IP window behind a proxy.** On TEST the per-IP window is keyed on `127.0.0.1` because the backend sees the proxy address. Check that production reads the real client IP (X-Forwarded-For), otherwise all users share one SMS window.

## 4. Static-review findings (written before the live run) and their live outcome

| # | Flow / step | Finding | Suspected owner | Live outcome |
|---|---|---|---|---|
| S1 | F07 "play/answer as the app sends it"; F06 "player self check-in" | The backend refuses play/answer and play/checkin with "必须提供动作标识、期望版本和原运行时身份" unless the request carries `routeActionId`, `expectedRouteVersion` **and** `routeSessionId` (and `nodeId` for checkin) (`PlayBasicIntentApplication.java:137`). The app never sends `routeSessionId` (`play_api.dart:738`), and `submitCheckin` (`play_api.dart:330`) sends no `nodeId`. The app also never calls `/api/play/action-context`. | app | **Confirmed** → A1 |
| S2 | F07 "play/ending POST" | The app calls `POST /api/play/ending` (`play_api.dart:586`); the backend maps only `GET /ending` (`ApiPlayProgressController.java:94`). | app (or the backend adds POST) | **Confirmed** → A2 (HTTP 200 / code 500, see B8) |
| S3 | F10 "club/list as multipart" | `ApiClubController` `/list`, `/detail`, `/members`, `/join` and `/quit` take `@RequestBody` (JSON), but the app sends FormData (`club_api.dart:32,62,74,90,102`). | app/backend contract | **Confirmed** for `/list` → A3 |
| S4 | F08 step 1 | The app never records the `roam_location` AGREE consent; it only revokes it (`account_api.dart:200`). `/api/roam/reveal` requires a current AGREE (`RoamLocationConsentService:65`). | app | Not testable from the harness (the fixture already had AGREE: consent returned 409, reveal passed). Native iOS does record AGREE (`AccountComplianceCoordinator.swift:468`). Still open for Flutter. |
| S5 | F05 "wallet shape matches app" | The app's `ticketList` reads `body['data'] as List` (`activity_api.dart:325`), but `/api/registration/list` returns `data = {total, rows}`. | app | **Confirmed** → A4 |
| S6 | F01 "login/code with e2e-code" | `/api/login/code` calls `jscode2session` directly and does not use the e2e-test WeChat session stub. | backend (TEST wiring) | **Confirmed** → B4 |
| S7 | F17 "withdraw over balance" | Over-balance `withdrawal/create` maps to the generic "提现申请失败，请稍后重试" (code 500). | backend | **Confirmed** → B2 |
| S8 | F04 "quote as the app sends it" | The app sends only `ownerType, ownerId, ticketId, isUsePoint`. | app/backend (confirm live) | **Not a defect.** The app-shape quote returns 200 with the same quoteSign (rerun R3). |
| S9 | F16 "wallet/stages form-encoded" | `/api/wallet/stages` takes JSON only; a form body is refused. | confirm live | Backend refuses form (B8). Native iOS sends JSON. Flutter encoding unverified. |
| S10 | F09 | The app has no client for City Monopoly, `/api/play/help/*`, `/api/play/action-context`, `/api/roam/session/records` or `/api/topic/publish-draft`. | app (coverage gap) | Unchanged (Flutter). Native iOS calls action-context. |
| S11 | F17 | The app does not submit withdrawals (R10: withdrawals are offline). | product note | Unchanged. |
| S12 | F13 | `activity/publish` as 9003 hits the club quota "已达到当前身份活动数量上限（3）". | TEST data | **Confirmed** (and coupons for 9002 hit the same limit, F14). |

## 5. Data seeding needed on TEST

| Need | Used by | Suggested seed | Variable |
|---|---|---|---|
| Activity 992031 in a live window (it starts 2026-11-22 14:00) | F06 scan, F07 play | Temporarily set `cms_activity.start_date` to now − 10 min for the run, then restore (done for R1–R3) | — |
| A clean linear route session for the F07 player (9009 and 9004 have ended sessions that trigger B1) | F07 | Use a player with no session on 992031 (R3 used 9001), or fix B1 | — |
| `cms_member_template.question_answer='1921'` for template 992101 (B5) | F07 answer | Seed fix | — |
| Paid ticket fixture, plus TEST pay configured or stubbed | F04 paid path | A paid activity and ticket; `payment-readiness` currently says "支付服务暂不可用" | `E2E_PAID_ACTIVITY`, `E2E_PAID_TICKET` |
| A fresh, unredeemed registration for 9009 | F06 rerun | Registration 74 was redeemed in R1 | `E2E_REG_9009` |
| Node `checkin_code` on 992021–992026 | F06 self check-in | They are NULL | `E2E_NODE_CHECKIN_CODE` |
| Monopoly board enabled, an active season and a roam_poi | F09 | SQL fixture (`scripts/e2e/seed_app_gaps_a*.py`) | `E2E_MONOPOLY_POI`, `E2E_MONOPOLY_BBOX`, `E2E_PHOTO_ASSET_ID` |
| An APPROVED `app_media_asset` cover owned by 9003 | F12 | `chain_platform.py seed_approved_cover` | `E2E_COVER_ASSET_ID` |
| Club activity quota and merchant coupon quota (both at 3) | F13, F14 | Clean up smoke-created rows, or raise `user_permission` limits on TEST | — |
| A `sms_coupon_history` row for 9004 on a 9002 coupon | F14 | SQL insert, `use_status=0` | `E2E_COUPON_HISTORY_ID` |
| A `refund_application` with status=0 on a 9002-hosted activity | F15 | SQL / back-office refund | `E2E_PENDING_REFUND_ID` |
| A group-chat member for F11 (9009 left club 7001; conversation 3 is a system conversation) | F11 group | Use the `club/chat` conversation id (1) with a current member | `E2E_CLUB_CONV` |

## 6. TEST state changed by this run

- 9004: new free registration 992200 on 992031 (F04). 9001: new free registration 992201, address 50 and route session 993807 (F07 re-run R3, help level 1 used).
- Registration 74 (9009) redeemed by 9003 in R1.
- Activity 992031 start/end moved to "now" three times for a few minutes each, and restored each time to 2026-11-22 14:00–17:00 (verified).
- 13900000001 (9001): deregistration applied and then **cancelled** (status CANCELLED, account usable).
- The full run's F18 logged in throwaway phone 13900000918 (outside the fixture range) before the harness was changed. It only read status/precheck and recorded a consent; no deletion was applied.
- Withdrawal created and withdrawn (undo) for 9003. SMS daily counters for 13900000001–09 were cleared before the run.

## History

The first attempt (2026-11-15 16:43 UTC, cloud routine) was BLOCKED: the cloud egress proxy answered 403 to CONNECT for the TEST host, so no flow ran there. This live run was done from the Mac.
