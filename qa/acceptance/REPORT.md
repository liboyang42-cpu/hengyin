# Chengyin backend acceptance — round r3 (`integration/backend-app-r3` @ `81db27155`)

- **Target:** TEST server `https://8120-agqyfqxs5vwolymtoibzq3fhde.instabox.tech` (be-int, DB `cy_e2e_beint`). Production was not touched.
- **Run:** 2026-11-15 16:43 UTC, scheduled routine session.
- **Result: BLOCKED. No flow was executed against the server.**
  - This session's cloud network policy refuses the TEST host: the egress proxy answers **403 to CONNECT** for `8120-agqyfqxs5vwolymtoibzq3fhde.instabox.tech:443`.
  - I asked to add the host to the environment. That needs a person's approval, and nobody was present for this unattended run, so the request was refused.
  - `results.json` and `results.md` therefore show every flow as `BLOCKED`, with transport error `Tunnel connection failed: 403 Forbidden`.
  - **There are no live pass or fail results below.** Section 3 lists defects found by reading the code. **None of them has been confirmed on the server.**

**To unblock:**
1. Add the host under the cloud environment's *Network access → Allowed domains*, or start this routine with **Run now** and approve the network request when it appears.
2. Provide a way to get SMS codes (see "How to run").
3. Re-run.

## 1. How to run

```bash
cd hengyin
pip install requests
# One SMS code source (TEST-only harness hooks; see docs/delivery/e2e-seed/RESULT.md):
export E2E_CODE_CMD='ssh work docker exec be-int-redis redis-cli -n 9 GET sms_{phone}_code'
#   or pre-plant a fixed code in TEST Redis and: export E2E_SMS_CODE=123456
#   or per-phone tokens: export E2E_TOKEN_13900000004=...
python3 qa/acceptance/run_all.py            # all 18 flows -> results.json + results.md
python3 qa/acceptance/run_all.py f07 f11    # a subset
python3 qa/acceptance/f04_register_pay.py   # one flow, prints its JSON record
```

How the harness behaves (`harness.py`):
- It refuses any base URL that is not `*.instabox.tech` or loopback `:8120`.
- It logs in only the e2e-test fixture phones `13900000001`–`13900000009`, plus one throwaway phone `139000009NN` for the account-deletion flow.
- Every step records the request, HTTP status, business `code`, latency and PASS/FAIL, with a suspected owner for each FAIL.
- Tokens, SMS codes and QR tokens are redacted from the results.

**Why login needs a TEST hook.** `/api/login/phone` needs a real SMS code, and on TEST that code exists only in TEST Redis. There is no remote, e2e-test login route; see finding S6.

## 2. Flows and steps

The analysis report's list of 18 core flows was not found in either repo's `docs/`. I searched for "18 flows", "core flows", 核心流程 and 核心链路. The flows below follow the routine's definition. Each script follows the calls the app makes (`chengyin-app/lib/data/api/*`) and checks them against the controllers. Where the app's request differs from what the backend accepts, the script sends the backend's form as the main step and adds an extra step that sends the request exactly as the app does, with owner=app.

The Result column is this run's result for every step of that flow.

| Flow | Script | Steps (in order) | Result |
|---|---|---|---|
| F01 Sign-in | `f01_sign_in.py` | sms/send → login/phone with a bad code (rejected) → login/phone → `/api/userInfo` role for player, club and merchant → `/api/user/info` → login/code `e2e-code-*` → userInfo without a token (401) | BLOCKED |
| F02 Browse home | `f02_browse_home.py` | common/banner → topic/list (feed, recommended) → activity/list (upcoming must include 992031, nearby, stream) → play/run-session/list → registration/my-joined → anonymous activity/list | BLOCKED |
| F03 Activity detail | `f03_activity_detail.py` | activity/info 992031, anonymous and signed in: topicId 992001, ticket 992041, price 0 → topic/info-to-user (lifecycle 3, linked to 992031) → payment-readiness → play/nodes (total 6) → unknown id | BLOCKED |
| F04 Register and pay | `f04_register_pay.py` | consent `activity_signup` → user/address/action → registration/quote (payAmount 0, quoteSign) → quote as the app sends it → create (free) → replay create with the same requestId (idempotent) → registration/info (paid) → registration/list → payment-readiness → paid quote → create → pay/app (TEST pay stub) | BLOCKED |
| F05 Ticket QR | `f05_ticket_qr.py` | registration/list wallet → wallet response shape the app expects → registration/info → verify/dyncode/issue (`v1.*`) → issue for another member's order (refused) | BLOCKED |
| F06 Check-in | `f06_check_in.py` | player dyncode/issue → merchant 9002 scan (refused, not the owner) → owner 9003 scan_dynamic_code (核销成功) → rescan (already verified) → player self check-in with the app's field set | BLOCKED |
| F07 Play game | `f07_play_game.py` | play/nodes for registered and unregistered players → route-state → action-context → help → help/use → help/use replay (no charge) → play/answer → answer as the app sends it → action-receipt → help/receipt → ending GET → ending POST (as the app calls it) | BLOCKED |
| F08 Roam and fog | `f08_roam_fog.py` | consent `roam_location` → roam/reveal (new session) → reveal again (0 new tiles) → presence → session → finish → second finish (already settled) → session/records → tiles includes the revealed tile | BLOCKED |
| F09 City Monopoly | `f09_city_monopoly.py` | season/current → territory/map → points → claim/quote (POINTS) → claim/command (CLAIMED) → rent: visitor territory/checkin (POINTS) → challenge/quote → challenge/command → owner revenue | BLOCKED |
| F10 Club join and chat | `f10_club_join_chat.py` | club/list as JSON → club/list as multipart (the app's encoding) → detail → join (9006) → leader join-requests → approve → members → club/chat → im/send → im/messages | BLOCKED |
| F11 Messages | `f11_messages.py` | im/conversations → im/start (9004 → 9009) → start again (same id) → send DM (checkStatus 1) → send-receipt → receiver messages → unread-total ≥ 1 → read → group send (conversation 3) → group messages | BLOCKED |
| F12 Publish topic | `f12_publish_topic.py` | publisher/identity/status → topic/create draft (simple mode) → publish-draft → replay publish-draft → play/nodes on the new topic | BLOCKED |
| F13 Publish activity | `f13_publish_activity.py` | publish_precheck → activity/publish → replay publish (idempotent) → activity/info → publish as a player (refused) → activity/delete (cleanup, frees quota) | BLOCKED |
| F14 Merchant verify | `f14_merchant_verify.py` | coupon/publish (9002) → myrecvlist → coupon/qr-token → coupon/verification (核销成功) → replay (alreadyUsed) → another merchant (refused) → coupon/status (used) → merchant/verification-records | BLOCKED |
| F15 Merchant refund | `f15_merchant_refund.py` | merchant/orders → aftercare/list → player reading aftercare (refused) → player cancel-refund → registration/info (cancelled) → aftercare/detail → aftercare/respond AGREE | BLOCKED |
| F16 Creator earnings | `f16_creator_earnings.py` | creator/center → club/settlement/summary → user/balance/list → balance/list → wallet/stages as JSON and as a form → merchant/finance overview and settlement-entries → player reading merchant finance (refused) | BLOCKED |
| F17 Withdraw (TEST) | `f17_withdraw.py` | withdrawal/list → wallet/stages → consent `withdrawal` → fund/preflight → confirm → withdrawal/create → list → withdrawal/delete (undo) → over-balance request (needs a clear error) | BLOCKED |
| F18 Account deletion | `f18_account_deletion.py` | Uses a throwaway `139000009NN` phone: status NORMAL → precheck ELIGIBLE → 9003 precheck BLOCKED → consent → sms/send → apply (PENDING) → old token revoked → result → cancel (CANCELLED) → status NORMAL. **It always cancels** during the 7-day cooling-off. | BLOCKED |

Per-step request, status, code and latency for this run: `results.md` and `results.json`.

## 3. Suspected defects found by reading the code (unconfirmed; the next live run checks each one)

Each item names its repro step. When the network is open, the step's PASS or FAIL settles it.

| # | Flow / step | Finding | Suspected owner |
|---|---|---|---|
| S1 | F07 "play/answer as the app sends it"; F06 "player self check-in" | The backend refuses play/answer and play/checkin with "必须提供动作标识、期望版本和原运行时身份" unless the request carries `routeActionId`, `expectedRouteVersion` **and** `routeSessionId` (and `nodeId` for checkin) (`PlayBasicIntentApplication.java:137`). The app never sends `routeSessionId` (`play_api.dart:738`), and `submitCheckin` (`play_api.dart:330`) sends no `nodeId`. The app also never calls `/api/play/action-context` to get these values. **Repro:** `POST /api/play/answer` with form `activityId=992031, topicId=992001, nodeId=992021, answer=…, routeActionId=x, expectedRouteVersion=1`. | app |
| S2 | F07 "play/ending POST" | The app calls `POST /api/play/ending` (`play_api.dart:586`); the backend maps only `GET /ending` (`ApiPlayProgressController.java:94`). Expect 405 or a wrapped error. | app (or the backend adds POST) |
| S3 | F10 "club/list as multipart" | `ApiClubController` `/list`, `/detail`, `/members`, `/join` and `/quit` take `@RequestBody` (JSON), but the app sends FormData (`club_api.dart:32,62,74,90,102`). Expect 415 or an error. | app/backend contract |
| S4 | F08 step 1 | The app never records the `roam_location` AGREE consent; it only revokes it (`account_api.dart:200`). `/api/roam/reveal` requires a current AGREE (`RoamLocationConsentService:65`), so fog reveal fails in the app. | app |
| S5 | F05 "wallet shape matches app" | The app's `ticketList` reads `body['data'] as List` (`activity_api.dart:325`), but `/api/registration/list` returns `data = {total, rows}`. The ticket wallet parse throws. | app |
| S6 | F01 "login/code with e2e-code" | `/api/login/code` calls `jscode2session` directly (`ApiLoginController.getOpenID`). It does not use the e2e-test `E2eTestWeChatMiniSessionService`. The TEST base-URL override applies only under the `test`, `dev`, `local` or `preview` profiles, and TEST runs `druid,e2e-test`. So `e2e-code-*` returns "微信服务暂时不可用", and there is no remote e2e login path. | backend (TEST wiring) |
| S7 | F17 "withdraw over balance" | When the balance is too low, `withdrawal/create` throws a RuntimeException that the controller maps to the generic "提现申请失败，请稍后重试" (code 500). The user is not told the balance is too low. | backend |
| S8 | F04 "quote as the app sends it" | The app sends only `ownerType, ownerId, ticketId, isUsePoint`. The seed scripts also send `tickets[]`, `realName` and `phone`. Need to confirm the backend accepts the app's shape. | app/backend (confirm live) |
| S9 | F16 "wallet/stages form-encoded" | `/api/wallet/stages` takes JSON only; a form body gets 415. Need to confirm which encoding the app sends. | confirm live |
| S10 | F09 | The app has **no client** for City Monopoly (`/api/city-game/*`), `/api/play/help/*`, `/api/play/action-context`, `/api/roam/session/records` or `/api/topic/publish-draft`. These can be tested on the backend only. | app (coverage gap) |
| S11 | F17 | The app does not submit withdrawals (R10: withdrawals are offline); it only reads `withdrawal/list` and `wallet/stages`. The F17 script exercises the backend chain for completeness. | product note |
| S12 | F13 | `POST /api/activity/publish` as 9003 fails with "已达到当前身份活动数量上限（3）" because earlier smoke runs used up the fixture club's quota (R3-INTEGRATION.md). F13 deletes its own activity to stay within the quota. | TEST data |

## 4. Data seeding needed on TEST

Some flows stop at a precondition step (BLOCKED with "data seeding needed") until the matching variable is set.

| Need | Used by | Suggested seed | Variable |
|---|---|---|---|
| Network allowlist for the TEST host | all | Cloud environment network settings | — |
| SMS code source | all | Redis read over SSH, or a pre-planted fixed code | `E2E_CODE_CMD` / `E2E_SMS_CODE` |
| Paid ticket fixture, plus the TEST WeChat pay stub deployed | F04 paid path | A paid activity and ticket (e.g. via `chain_player.py` open-session). The stubs in `scripts/e2e/*.stub` are not in the TEST jar. | `E2E_PAID_ACTIVITY`, `E2E_PAID_TICKET` |
| A fresh, unredeemed registration for 9009 | F05 / F06 rerun | F06 redeems registration 74; re-seed it or give the script a new one | `E2E_REG_9009` |
| 9004 not yet signed up for 992031 | F04 | After F04 has run, reset 9004's registration on 992031. Reruns otherwise test the replay / already-registered path. | — |
| Node `checkin_code` on seed nodes 992021–992026 | F06 self check-in | They are seeded NULL | `E2E_NODE_CHECKIN_CODE` |
| Monopoly board enabled (`city.monopoly.enabled`), an active season and a roam_poi | F09 | SQL fixture (see `scripts/e2e/seed_app_gaps_a*.py`) | `E2E_MONOPOLY_POI`, `E2E_MONOPOLY_BBOX`, `E2E_PHOTO_ASSET_ID` |
| An APPROVED `app_media_asset` cover owned by 9003 | F12 | `chain_platform.py seed_approved_cover` | `E2E_COVER_ASSET_ID` |
| Club activity quota (`max_events=3`) | F13 | Clean up smoke-created activities, or raise `user_permission.max_events` for `club` on TEST | — |
| A `sms_coupon_history` row for 9004 on a 9002 coupon (there is no app "receive" endpoint) | F14 | SQL insert, `use_status=0` | `E2E_COUPON_HISTORY_ID` |
| A cancellable registration for 9004, and a `refund_application` with status=0 on a 9002-hosted activity | F15 | SQL / back-office refund | `E2E_REFUND_REG`, `E2E_PENDING_REFUND_ID` |
| Balance ≥ 0.01 on 9003 | F17 | Balance ledger seed | `E2E_WITHDRAW_AMOUNT` |
| Previous roam consent requestId, if one exists | F08 | — | `E2E_ROAM_CONSENT_PREV` |
