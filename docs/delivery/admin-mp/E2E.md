# admin-mp — TEST End-to-End Verification (`cy_e2e_r2` / `127.0.0.1:8120`)

Verified on VPS `work` against **TEST** (`127.0.0.1:8120`, DB `cy_e2e_r2`, Redis DB `9`). Production was not touched. No secrets are stored in git.

## 1. Deployment & Test Configuration

| Item | Value |
|---|---|
| Merged local branch | `integ/admin-mp-e2e` (branched from `origin/integration/backend-app-r2` `5c2017eef`, merged `feature/admin-review-pages-and-mp` `d2c2913b7` with `--no-ff`) |
| Build & unit tests | `mvn -B -T 1 package -DskipTests` (`BUILD SUCCESS`); `MerchantOfferWithdrawalAdminServiceTest,ChannelWebhookAdminServiceTest,ReviewQueueAdminControllersTest`: **13/13 pass** |
| Migration applied (TEST DB `cy_e2e_r2`) | `chengyinhub-admin/sql/migration_20261121_admin_review_queue_menus.sql` only (657 tables preserved; inserted `sys_menu` IDs `2571`–`2575` under `audit-center`, `order_num` `52`–`56`) |
| Rollback preserved | `/data/workspace/be-int/app/{app.jar,application-beint.properties,start.sh}.prev-0185418` (all older `.prev-*` kept; `app.log.prev-r6` rotated) |
| TEST overlay addition | Added `chengyin.workshop.professional.review=MANUAL` to `/data/workspace/be-int/app/application-beint.properties` so workshop professional submissions enter `PENDING` for `/business/workshop-professional` review |
| TEST-only admin `sys_user` | `test_audit_admin` (`user_id=101`, role `cy_auditor` / `role_id=101`, permissions `business:audit:list`, `business:audit:review`, `business:audit:log:list`; random 20-char password stored only in `/data/workspace/test-admin.txt` with `chmod 600` on `work`) |
| E2E player / merchant / club accounts | `13900000004` (author/player1), `13900000005` (reporter/player2), `13900000002` (merchant `9002`, store `8001`), `13900000003` (club/workshop owner `9003`, club `7001`, channel `5`) |

## 2. Auth & Permission Verification

| Step | Actor | Method & Path | HTTP | `code` | Observed Result |
|---|---|---|---|---|---|
| 0.1 | `test_audit_admin` | `POST /login` | 200 | 200 | JWT issued (`token_len=218`) |
| 0.2 | `test_audit_admin` | `GET /getInfo` | 200 | 200 | `roles=["cy_auditor"]`; `permissions` includes `business:audit:list`, `business:audit:review`, `business:audit:log:list` |
| 0.3 | `test_audit_admin` | `GET /getRouters` | 200 | 200 | `/audit-center` present in `top_menus` |
| 0.4 | `13900000004`, `13900000005`, `13900000002`, `13900000003` | `POST /api/sms/send` + `POST /api/login/phone` | 200 | 200 | Player/merchant/owner app JWTs issued via TEST Redis SMS code |

## 3. Loop 1 — External Game Submit → Review (Approve / Reject → Resubmit) → Catalog → Player Report → Admin Reports

| Step | Actor | Method & Path | HTTP | `code` | Observed Result |
|---|---|---|---|---|---|
| 1.1 | `13900000004` | `POST /api/external-game/submissions?requestId=adm1-1794643461` (`e2e.adm1.1794643461` v`1.0.0`) | 200 | 200 | `submissionId=18`, `status=PENDING` |
| 1.2 | `test_audit_admin` | `GET /business/external-game/review/list` | 200 | 200 | `submissionId=18` listed in queue (`counts`: `PENDING=6, APPROVED=6, REJECTED=5, CANCELLED=1, ESCALATED=0`) |
| 1.3 | `test_audit_admin` | `GET /business/external-game/review/18` | 200 | 200 | Submission detail, manifest, files, and timeline returned |
| 1.4 | `test_audit_admin` | `POST /business/external-game/review/18/approve` (`{"note":"E2E approved"}`) | 200 | 200 | `status=APPROVED` |
| 1.5 | `13900000004` | `POST /api/external-game/submissions?requestId=adm2-1794643461` (`e2e.adm2.1794643461` v`1.0.0`) | 200 | 200 | `submissionId=19`, `status=PENDING` |
| 1.6 | `test_audit_admin` | `POST /business/external-game/review/19/reject` (`{"reason":"E2E missing instructions"}`) | 200 | 200 | `status=REJECTED`, `rejectReason="E2E missing instructions"` |
| 1.7 | `13900000004` | `POST /api/external-game/submissions/19/resubmit?requestId=adm2r-1794643461` (`e2e.adm2.1794643461` v`1.0.1`) | 200 | 200 | `submissionId=20`, `status=PENDING`, `version=1.0.1` |
| 1.8 | `test_audit_admin` | `GET /business/external-game/review/20/package` | 200 | — | Returns stored package JSON (`573` bytes) |
| 1.9 | `test_audit_admin` | `POST /business/external-game/review/sweep-timeouts` | 200 | 200 | `reviewTimeoutDays=3`, `escalated=0` |
| 1.10 | Anonymous | `GET /api/external-game/catalog` | 200 | 200 | Approved `e2e.adm1.1794643461` present (`true`); unapproved `e2e.adm2.1794643461` absent (`false`) |
| 1.11 | Anonymous | `GET /api/external-game/catalog/e2e.adm1.1794643461` | 200 | 200 | Returns signed `downloadPath` |
| 1.12 | Anonymous | `GET <signed downloadPath>` | 200 | — | Package JSON downloaded (`200`) |
| 1.13 | `13900000005` | `POST /api/external-game/reports` (`gameId=e2e.adm1.1794643461`, `reasonCode=BROKEN`) | 200 | 200 | `reportId=7`, `status=OPEN` |
| 1.14 | `test_audit_admin` | `GET /business/external-game/reports?gameId=e2e.adm1.1794643461` | 200 | 200 | `total=1`, `rows[0].reasonCode=BROKEN`, `rows[0].status=OPEN` |

Additionally, the full `r4/smoke_import_8120.py` suite ran with `test_audit_admin`: **24/24 OK** (including `same requestId replays`, `GAME_ID_TAKEN`, tampered signature `403 DOWNLOAD_LINK_INVALID`, reject without reason `400 BAD_REQUEST`, `escalate -> ESCALATED`, `cancel -> CANCELLED`, duplicate report `409 REPORT_ALREADY_OPEN`).

## 4. Loop 2 — Workshop Professional Submission → Admin Review (`MANUAL` mode)

| Step | Actor | Method & Path | HTTP | `code` | Observed Result |
|---|---|---|---|---|---|
| 2.1 | `13900000003` | `POST /api/workshop/purchased/professional-targets/list` + `POST /api/workshop/v2/purchased/install/targets` | 200 | 200 | Retrieved `topicId=992001` fingerprint/mode and `ownedDraftId=970301` revision/hash |
| 2.2 | `13900000003` | `POST /api/workshop/purchased/professional-operations/submit` (op1) | 200 | 200 | `state=PREPARED` (`review_status=PENDING`) |
| 2.3 | `test_audit_admin` | `GET /business/workshop-professional/review/list` | 200 | 200 | `autoReview=false`; op1 (`id=10`) visible in queue |
| 2.4 | `test_audit_admin` | `POST /business/workshop-professional/review/10/escalate` (`{"reason":"E2E escalate check"}`) | 200 | 200 | `reviewStatus=ESCALATED` |
| 2.5 | `test_audit_admin` | `POST /business/workshop-professional/review/10/approve` (`{"note":"E2E approve"}`) | 200 | 200 | `reviewStatus=APPROVED`, `templateId=992503` created |
| 2.6 | `13900000003` | `POST /api/workshop/purchased/professional-operations/status` (op1) | 200 | 200 | `state=CREATED`, `creation.templateId=992503` |
| 2.7 | `13900000003` | `POST /api/workshop/purchased/professional-operations/submit` (op2) | 200 | 200 | `state=PREPARED` (`id=11`) |
| 2.8 | `test_audit_admin` | `POST /business/workshop-professional/review/11/reject` (`{"reason":"E2E workshop reject"}`) | 200 | 200 | `reviewStatus=REJECTED`, `rejectionCode=CURRENT_SOURCE_OR_TARGET_UNAVAILABLE` |

DB `workshop_professional_event` audit trail verified for operations `10` and `11` (events `16`–`20`: `AUTHOR:PENDING` → `ADMIN(101):ESCALATED` → `ADMIN(101):APPROVED`; `AUTHOR:PENDING` → `ADMIN(101):REJECTED`).

## 5. Loop 3 — Merchant Offer-Withdrawal Read-Only Ledger

| Step | Actor | Method & Path | HTTP | `code` | Observed Result |
|---|---|---|---|---|---|
| 3.1 | `13900000002` | `GET /api/merchant/offer-withdrawal/request/current?merchantId=8001&offerId=992991` | 200 | 200 | Returned `snapshotSHA256` (`offerId=992991` already withdrawn in prior E2E) |
| 3.2 | `13900000002` | `POST /api/merchant/offer-withdrawal/request/withdraw` (`requestId=c4144bbf-a780-4962-bc16-1401b6a38168`) | 200 | 200 | `outcome=REJECTED_STATE` receipt written |
| 3.3 | `test_audit_admin` | `GET /business/offer-withdrawal/list?merchantId=8001&offerId=992991` | 200 | 200 | `total=5`, `counts={"WITHDRAWN":3,"REJECTED_STATE":2,"REJECTED_CHANGED":0,"REJECTED_INPUT":0}`; shows both prior `WITHDRAWN` E2E rows and new `requestId=c4144bbf-a780-4962-bc16-1401b6a38168` |
| 3.4 | `test_audit_admin` | `POST /business/offer-withdrawal/list` | 200 | 500 | `Request method POST is not supported` (read-only ledger) |

## 6. Loop 4 — IM Webhook DLQ → Redrive

| Step | Actor | Method & Path | HTTP | `code` | Observed Result |
|---|---|---|---|---|---|
| 4.1 | `13900000003` | `POST /api/im/webhook/subscriptions` (`club=7001`, `channel=5`, probe URL on TEST public base) | 200 | 200 | `subscription_id=3` created |
| 4.2 | `13900000003` | `POST /api/im/webhook/subscriptions/3/test` | 200 | 200 | `delivery_id=5` queued; outbound dispatcher received HTTP 401 and moved it to `DEAD` (`attempt=1`) |
| 4.3 | `test_audit_admin` | `GET /business/im-webhook/deliveries?state=DEAD&subscriptionId=3` | 200 | 200 | `delivery_id=5` visible with `state=DEAD`, `attempt=1`, `http_status=401`, `retryable=true` |
| 4.4 | `test_audit_admin` | `POST /business/im-webhook/deliveries/5/redrive` | 200 | 200 | `state=PENDING`, `reason=RETRY_REQUESTED`, `previous_state=DEAD`, `previous_attempt=1`, `redriven_by=101`; `sys_oper_log` row `763` (`oper_name=test_audit_admin`) recorded |
| 4.5 | `test_audit_admin` | `GET /business/im-webhook/deliveries?state=ALL&subscriptionId=3` (after redrive dispatch) | 200 | 200 | Dispatcher re-ran delivery (`update_time=2026-11-14 16:04:33.427` vs `create_time=16:04:24.643`), reset `attempt=1`, `http_status=401`, `state=DEAD` |
| 4.6 | `test_audit_admin` | `POST /business/im-webhook/deliveries/1/redrive` (deleted subscription) | 404 | 404 | `errorCode=WEBHOOK_NOT_FOUND` |
| 4.7 | `13900000003` | `DELETE /api/im/webhook/subscriptions/3` | 200 | 200 | Test subscription cleaned up |

## 7. Auth Guard Checks

| Method & Path | Credential | HTTP | `code` | Result |
|---|---|---|---|---|
| `GET /business/external-game/review/list` | Player JWT (`13900000004`) | 200 | 401 | Rejected (`登录状态已失效，请重新登录`) |
| `GET /business/workshop-professional/review/list` | Signed out | 200 | 401 | Rejected (`登录状态已失效，请重新登录`) |
| `GET /business/offer-withdrawal/list` | Player JWT (`13900000004`) | 200 | 401 | Rejected (`登录状态已失效，请重新登录`) |
| `GET /business/im-webhook/deliveries` | Player JWT (`13900000004`) | 200 | 401 | Rejected (`登录状态已失效，请重新登录`) |
