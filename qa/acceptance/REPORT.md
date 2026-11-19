# Chengyin backend acceptance — round r3

## Wave 9 (2026-11-19 17:25 – 17:29 UTC): City Monopoly gaps merged, re-test on TEST

The coordinator's brief asked for a "wave 7" section; waves 7 and 8 already exist below, so this one is numbered 9.

- **Target:** TEST 8120 (be-int, DB `cy_e2e_r2`) over loopback on the `work` VPS. New jar from `integration/backend-app-r3` @ `259ad0932`, which merges `fix/city-gaps-r3` (`b884d44ef`, receipt `qa/CITY-GAPS-R3.md`): the claim-quote 500 fix, district names, deed serials, defender and rent in-app notifications, and the on-site challenge task verifier. Deployed at 17:25 UTC under `flock /data/workspace/.deploy.lock`; started in 23 s. Rollback jar: `app/app.jar.prev-5acc166a0`. Production was not touched.
- **Migration:** `migration_20261126_city_gaps_r3.sql` applied with the mysql client, as in earlier waves (`cy_migration_history` stays at 0 rows). Tables 691 → 693 (`city_deed_counter`, `city_onsite_task_submission`). `roam_territory` gained `deed_serial` and the virtual `deed_serial_live` with `UNIQUE (board_id, district_id, deed_serial_live)`. The backfill numbered the two live `qa-d1` deeds 1 and 2 (in claim order), and seeded a `city_district` row `qa-d2` (`district.c2`, name = id) for the TEST-only point below. Existing `city_district` rows were not changed.
- **Run:** one full `run_all.py` (fixtures first, teardown at the end) at 17:26 UTC, using the wave 7 harness `152b8829f` with no code change. `results.json`, `results.md` and `fixtures.json` are in this commit.
- **Result: 17 of 18 flows pass**, the same as waves 7 and 8, so nothing regressed. F04's paid path is still TEST-SKIP (no pay sandbox). F06 still fails only on A5: the native self check-in sends no `nodeId`. That is an app defect, and the backend is unchanged. In F09, 9004's fresh claim got deed serial 3 in `qa-d1`, and 9009's takeover (roll 13 ≥ DC 12) sent 9001 a `cityTerritory` / `PLACE_LOST` system card (+12 积分 compensation).

| Flow | Result (pass / fail / TEST-SKIP) | Verdict |
|---|---|---|
| F01–F03, F05, F07–F18 | all steps pass (F09 11 / 0 / 0) | **Pass** |
| F04 Register and pay | 10 / 0 / 1 | **Pass** (paid TEST-SKIP) |
| F06 Check-in | 10 / 1 / 0 | **Fail (app A5)** |

### City gaps live check (`/tmp/r3w9-verify.py` on the VPS, 14/14)

TEST-only fixture: board point `qa-monopoly-1` / `qa-pt-np1` (`QA 无地点格`, 23.1302, 113.2668) with `place_id = NULL`, district `qa-d2`, added before the migration. City fixture player 9009 (claims) and 9004 (challenger), tokens from codes planted in TEST Redis.

| Check | Result |
|---|---|
| Baseline on the old jar (`5acc166a0`): `POST /api/city-game/territory/claim/quote` `{boardId, pointId: qa-pt-np1}` | HTTP 200, **code 500** `系统繁忙，请稍后重试` (the NPE) |
| Same request on `259ad0932` | code 200; `targetKey board:qa-monopoly-1:qa-pt-np1`, no `poiId`, `cost 60`, `currency POINTS` |
| `GET /api/city-game/territory/map` (QA bbox) | top-level `districts[]`: `qa-d1` `QA 老城街区` `district.c1` `colorIndex 1` `ownableCount 3`; `qa-d2` `district.c2`; every item carries `districtName` |
| `GET /api/roam/territory/nearby` (board read, `boardId=qa-monopoly-1`) | `districts[]` (2); every board item carries `districtId` + `districtName` (`qa-pt-np1` → `qa-d2`) |
| `claim/command` by 9009 on `qa-pt-np1` | `CLAIMED`; `property.deedSerial = 1` (first deed in `qa-d2`); `property/{id}` returns `deedSerial 1` + `districtName`; DB `deed_serial 1`, `city_deed_counter` `qa-d2` = 1 |
| `challenge/quote` by 9004 on 9009's `骑楼口` (protection lifted, TEST-only) | code 200; `task` = `{type PHOTO_PRESENCE, radiusM 80, optional true, submitPath …/challenge/task/submit}` |
| `challenge/command` | `LOSS` (roll 6 < DC 12) |
| Defender 9009's system conversation | `im_message` `msg_type 3`: `你守住了「QA 大富翁·骑楼口」：挑战者 6 未达 DC 12，补偿 +9 积分。`, `extra {"cardType":"cityTerritory","kind":"DEFENDED","territoryId":…,"points":9,"currency":"POINTS"}` |

No `站内信未送达` / `城市大富翁站内信异常` line and no NPE in `app.log`. The on-site task submit route was not called live: TEST has no vision provider, so it would only show the `DEGRADED → NONE` path, which `CityOnSiteTaskServiceTest` covers.

### New defects

None. Observation (not a defect): a deed with no serial keeps none after a takeover (only a first claim issues one). On TEST this shows up for the fixture's defended territory, which `fixtures.py` inserts directly without a serial, so 9009's takeover row in F09 has `deed_serial NULL`. Real deeds get one from the claim or from the migration backfill. Carried over: A5 (app), B5b, B9, A6 (see wave 6).

### TEST state changed by this wave

- `8120` jar `5acc166a0` → `259ad0932` (rollback `app/app.jar.prev-5acc166a0`); migration `20261126_city_gaps_r3` applied (additive; can stay on rollback).
- New board point `qa-monopoly-1` / `qa-pt-np1` (no `place_id`, district `qa-d2`) and its backfilled `city_district` row. `city_deed_counter`: `qa-d1` = 3, `qa-d2` = 1.
- Two `cityTerritory` system messages (9009 `DEFENDED`, 9001 `PLACE_LOST`); 9009's `骑楼口` protection was lifted for the challenge. The run's fixture refresh then reset the board's territories as usual.

---

## Wave 8 (2026-11-16 20:25 – 20:30 UTC): merchant identity merged, re-test on TEST

- **Target:** TEST 8120 (be-int, DB `cy_e2e_r2`) over loopback on the `work` VPS. New jar from `integration/backend-app-r3` @ `5acc166a0`, which merges `fix/merchant-identity-r3` (`c18158761`, receipt `qa/MERCHANT-IDENTITY-R3.md`). Merchant onboarding now takes `realName` + `idCardNumber` + `identityConsent` and stores them through the publisher-identity PII store (AES-256-GCM). Reads return masked values only. Deployed at 20:25 UTC under `flock /data/workspace/.deploy.lock` and started in 20 s. Rollback jar: `app/app.jar.prev-3f0cdb762`. No migration (the `ums_member_identity` table already exists). Production was not touched.
- **PII key:** `CHENGYIN_WITHDRAWAL_PII_KEY` is set in the 8120 process from the TEST-only `app/.test-pii-key`. Only its presence was checked; the value was never printed.
- **Run:** one full `run_all.py` (fixtures first, teardown at the end) at 20:29 UTC, using the wave 7 harness `152b8829f` with no code change. `results.json`, `results.md` and `fixtures.json` are in this commit.
- **Result: 17 of 18 flows pass**, the same as wave 7, so nothing regressed. F04's paid path is still TEST-SKIP because TEST has no pay sandbox. F06 still fails only on A5: the native self check-in sends no `nodeId`. That is an app defect, and the backend is unchanged.

| Flow | Result (pass / fail / TEST-SKIP) | Verdict |
|---|---|---|
| F01–F03, F05, F07–F18 | all steps pass | **Pass** |
| F04 Register and pay | 10 / 0 / 1 | **Pass** (paid TEST-SKIP) |
| F06 Check-in | 10 / 1 / 0 | **Fail (app A5)** |

### Merchant identity live check (`/tmp/r3w8-verify.py` on the VPS, 17/17)

A fresh TEST phone `13900000971` was logged in with a TEST code planted in Redis. This created member 990014, which had no merchant and no identity row. The ID number is synthetic: `999999199001010016` has a valid checksum, but area `999999` does not exist, so it cannot belong to a real person.

| Check | Result |
|---|---|
| `POST /api/merchant/merchant_registration` with a bad check digit (`…0017`) | code 500, `身份证号格式不正确,请核对后重填`; the response does not echo the input |
| Same request with `identityConsent: false` | refused with `请先勾选同意提供真实姓名与身份证号`; no identity row was written |
| Valid submit | code 200 `{"msg":"更新成功"}`; the response holds no number, middle digits or name |
| `POST /api/publisher/identity/status` | `registered: true`, `idCardNumberMasked: 999***********0016`, `realNameMasked: 测*`; no plaintext |
| `ums_member_identity` (prefix and length only) | `id_card` = `v1:`, 67 chars; `real_name` = `v1:`, 63 chars; blind index set; `source merchant_apply` |
| `mms_merchant` | pending row 8007 (`status 0`) |
| Second application with another card | refused (`已提交过商家入驻申请，请勿重复提交`); the identity row is unchanged |
| `app.log`, `sys_oper_log` | 0 lines or rows contain the number, its middle digits or the name; 1 `consent_record` row |

The admin detail `GET /business/merchant/{id}` (`ownerIdentity`, masked) was not called live, because back-office logins are disabled on TEST. `MerchantIdentityR3Test` covers it.

### New defects

None. Carried over: A5 (app), B5b, B9, A6 (see wave 6).

### TEST state changed by this wave

- `8120` jar `3f0cdb762` → `5acc166a0` (rollback `app/app.jar.prev-3f0cdb762`).
- New TEST member 990014 (`13900000971`) with an encrypted identity row (`merchant_apply`), a `consent_record` row and a pending merchant application 8007.
- The usual fixture refresh and teardown of this run (QA live activity 9940019 retired, free registrations for 9001 and 9004, a QA territory on `qa-monopoly-1`).

---

## Wave 7 (2026-11-16 14:21 – 14:35 UTC): F-19 and B10 merged, re-test on TEST

- **Target:** TEST 8120 (be-int, DB `cy_e2e_r2`) over loopback on the `work` VPS. New jar from `integration/backend-app-r3` @ `3f0cdb762`: `dfabdb76c` merges `fix/registration-list-r3` (F-19, test + receipt only, no production code) and `3f0cdb762` merges `fix/challenge-npe-r3` (B10). Deployed at 14:21 UTC under `flock /data/workspace/.deploy.lock`; started in 20 s. Rollback jar `app/app.jar.prev-82f0bab79`. No migration. Production was not touched.
- **Run:** one full `run_all.py` (fixtures first, teardown at the end) at 14:25 UTC, harness at wave 6 `166b125e3` with no code change. `results.json` / `results.md` / `fixtures.json` in this commit.
- **Result: 17 of 18 flows pass** (wave 6: 16 of 18). F04's paid path is TEST-SKIP, as in wave 6 (no pay sandbox on TEST).
  - **F09 passes (B10 fixed on TEST).** `challenge/command` → HTTP 200 / code 200. The stored `city_challenge` row has `roll 15`, `nat 0`, `outcome WIN`: a mid roll now resolves. app.log has no `CityMonopolyService.challengeCommand` NullPointerException.
  - **F06 still fails, only on A5 (app, native iOS).** The self check-in as the native client sends it (no `nodeId`) is refused (HTTP 200, code 500). Every other F06 step passes, including the check-in with `nodeId`. This is a client defect, so no backend change can close it. The app needs to send the `nodeId` the action context was read for. Until then, 18 of 18 cannot be reached.

| Flow | Result (pass / fail / TEST-SKIP) | Verdict |
|---|---|---|
| F01–F03, F05, F07, F08, F10–F18 | all steps pass | **Pass** |
| F04 Register and pay | 10 / 0 / 1 | **Pass** (paid TEST-SKIP) |
| F06 Check-in | 10 / 1 / 0 | **Fail (app A5)** |
| F09 City Monopoly | 11 / 0 / 0 | **Pass** (was Fail, B10) |

### F-19 re-check: registration list and my-joined for 9004

Logged in as 9004 (`login/phone 13900000004`, TEST code planted in Redis), multipart form as the native client sends it:

| Check | Wave 5 jar `82f0bab79` (before deploy) | Wave 7 jar `3f0cdb762` (after the run) |
|---|---|---|
| `cms_registration` rows of 9004 (all / status 2) | 29 / 6 | 31 / 6 (the run's fixtures added 2) |
| `POST /api/registration/list owner_type=3` | total 29, 29 rows, all `memberId 9004` | total 31, 31 rows, all `memberId 9004` |
| `POST /api/registration/list owner_type=2` | total 6, all 9004 | total 6, all 9004 |
| `POST /api/registration/my-joined` | 6 items, holds 992031 | 6 items, holds 992031 |

F-19 does not reproduce on either jar. The counts match the DB, and only 9004's own rows come back (owner scope intact). This matches `qa/REGLIST-R3.md`: an empty list means the caller's token was not 9004's, or another jar was answering.

### New defects

None. Carried over: A5 (app), B5b, B9, A6 (see wave 6).

### TEST state changed by this wave

- `8120` jar `82f0bab79` → `3f0cdb762` (rollback `app/app.jar.prev-82f0bab79`).
- Fixture rows of this run: QA live activity 9940017 and its tickets (retired by `fixtures.teardown()`), new free registrations for 9001/9004, a QA territory challenge on `qa-monopoly-1`, and the usual fixture refresh (see wave 6).

---

## Wave 6 (2026-11-15 22:20 – 2026-11-16 04:12 UTC): native-client harness + idempotent fixtures

- **Target:** TEST 8120 (be-int, DB `cy_e2e_r2`) over loopback on the `work` VPS. Same jar as wave 5 (`integration/backend-app-r3` @ `82f0bab79`). Production was not touched.
- **Runs:** after iterative partial runs, `full` (ended 04:09 UTC) and `full2` (ended 04:11 UTC, `results.json` / `results.md`), each preceded by `fixtures.setup()`; both gave the same verdicts. After `full2`, F08's consent step was switched to the native read-latest-then-AGREE sequence and F08 was re-run alone (`results-rerun-w6.json`, PASS). `fixtures.json` holds the ids the fixture wrote for `full2`.
- **Result: 16 of 18 flows pass, twice in a row** (wave 5: 3 of 18). The two failures are confirmed product defects, one per owner:
  - **F06** fails only on the native app's self-check-in request shape (**A5, app**). The same check-in with the backend contract passes.
  - **F09** fails only on `challenge/command`, which throws a NullPointerException for every d20 roll from 2 to 19 (**B10, backend**). Claim, rent, protection and the challenge quote pass.
- **TEST-SKIP:** F04's paid path after the paid quote. TEST has no pay sandbox: `payment-readiness` reports "支付服务暂不可用", and the only pay stub (`scripts/e2e/E2eWechatPayStub.java.stub`) has to be compiled into an exploded jar by hand, so it is not in the TEST jar. Because of that, `registration/create` for a paid ticket fails closed with code 503 before it writes an order. The harness checks that 503 refusal and records "create → pending order → pay/app" as TEST-SKIP. For the same reason, no paid registration for 9001 can be seeded.

### Per flow (`full2`; pass / fail / TEST-SKIP)

| Flow | Result | Verdict | Notes |
|---|---|---|---|
| F01 Sign-in | 8 / 0 / 0 | **Pass** | Real `sms/send` + `login/phone`; `login/code` e2e stub (B4). The bad-code refusal is still code 500 (not part of SMOKE-500-R4). |
| F02 Browse home | 11 / 0 / 0 | **Pass** | The harness now pages through the whole upcoming list (`is_my=2`). Club-run 992031 is on **no** page: the public shelf (`PublicActivityScope.PUBLIC_SHELF`) never lists community sessions. The flow now asserts that rule (product decision, see below). Members reach 992031 through `registration/my-joined` (passes). |
| F03 Activity detail | 6 / 0 / 0 | **Pass** | The anonymous detail is the club gate (`gate:true, clubId 7001, message`), which the native client renders (`ActivityDetail.swift`). Product decision, not changed. |
| F04 Register and pay | 10 / 0 / 1 | **Pass** (paid TEST-SKIP) | Native shapes: quote `{ownerType, ownerId, ticketId, isUsePoint}`; create adds `realName, phone, quoteSign, payChannel APP, requestId`. Free path, idempotent replay and paid quote (`payAmount > 0`) pass. The paid create is refused with 503 (pay not configured), as described above. |
| F05 Ticket QR | 5 / 0 / 0 | **Pass** | Uses 9001's fresh free registration on the run's live activity. The wallet is decoded as `{total, rows}` (`TicketWalletService.RouteEnvelope`). |
| F06 Check-in | 10 / 1 / 0 | **Fail (app A5)** | Entry QR → non-owner merchant refused → owner scan "核销成功" → rescan `alreadyVerified` all pass. Self check-in as 9004: action-context, then the node's advanced qa (`/api/play/advanced/start` + `SUBMIT_QA`), then `play/checkin` with the action fields **and `nodeId`** → "打卡成功"; the replay is idempotent. The request the native client sends (no `nodeId`) → `INTENT_INPUT_INVALID` "参数有误" (A5). |
| F07 Play game | 16 / 0 / 0 | **Pass** | 9001 on a fresh run: nodes, route-state ACTIVE, action-context, hint ladder, help/use and its replay (no charge), advanced qa, answer "答对了！" and its replay, action-receipt, help receipt, 992021 COMPLETED, `GET ending`. Unregistered 9009 is not playable. B1 and B5 are confirmed on live data. |
| F08 Roam and fog | 10 / 0 / 0 | **Pass** | Unchanged except encoding. Re-run with the native consent sequence: pass. |
| F09 City Monopoly | 10 / 1 / 0 | **Fail (backend B10)** | The QA LIVE board pays in 积分 (`currency POINTS`). Season, map (unlit tiles show only `districtId/tier`), points, claim quote → claim `CLAIMED`, visitor `consents/latest` + rent check-in (`revenueMode POINTS`, owner revenue event), protection refusal (HTTP 409 `TERRITORY_PROTECTED`), challenge quote on the defended property and owner revenue all pass. `challenge/command` → HTTP 200, code 500, "系统繁忙" (B10). |
| F10 Club join and chat | 9 / 0 / 0 | **Pass** | JSON only, as the native client sends it (the multipart probe was Flutter's A3). `join-request/approve` after a direct join → 400 "入会申请不存在或已处理" (SMOKE-500-R4 code, asserted). |
| F11 Messages | 12 / 0 / 0 | **Pass** | The DM chain passes. `im/read` carries `read_to` + `request_id` (UUID), as `IMExpandedService` sends it. The group part uses the club 7001 chat from `club/chat`: 9004 sends, 9001 reads. |
| F12 Publish topic | 5 / 0 / 0 | **Pass** | Uses a fixture APPROVED cover. `play/nodes` on the new self-play topic → 402 "请先购买自玩通行证" (route published; self-play pass gate), accepted. |
| F13 Publish activity | 6 / 0 / 0 | **Pass** | Quota room from the fixture; the flow deletes its own activity. |
| F14 Merchant verify | 9 / 0 / 0 | **Pass** | 9005 publishes and then stops its coupon (native `CouponManagementContract`: JSON publish with `scope MERCHANT`, multipart stop). 9002 verifies the fixture-granted coupon on a store coupon (`scope_type 1`): "核销成功", replay `alreadyUsed`, foreign merchant refused, `useStatus 1`. |
| F15 Merchant refund | 7 / 0 / 0 | **Pass** | Player cancel-refund of F04's free order; aftercare detail + respond AGREE on the fixture's pending refund_application. |
| F16 Creator earnings | 8 / 0 / 0 | **Pass** | `wallet/stages` as JSON only (`WalletCommerceService.swift:28`); the form probe is gone. |
| F17 Withdraw | 11 / 0 / 0 | **Pass** | Over-balance → 409 `WITHDRAWAL_INSUFFICIENT_BALANCE` (B2). |
| F18 Account deletion | 10 / 0 / 0 | **Pass** | `fixtures.release_for_f18()` releases 9001's fixture registrations first; apply → revoke → result → cancel → `NORMAL`. |

### New defects (live, with repro)

| # | Owner | Severity | Defect | Repro on TEST | Evidence |
|---|---|---|---|---|---|
| B10 | backend | **High** | `CityMonopolyService.challengeCommand` builds its reply with `out.put("nat", roll == 20 ? 20 : roll == 1 ? 1 : null)`. Java unboxes the inner `Integer` null into the outer `int` ternary, so every roll from 2 to 19 throws a NullPointerException after the roll and the transaction rolls back. About 90% of challenges answer HTTP 200 / code 500 "系统繁忙，请稍后重试", and no challenge can resolve except on a natural 1 or 20. Fix: `Integer nat = roll == 20 ? Integer.valueOf(20) : roll == 1 ? Integer.valueOf(1) : null;` plus a test with a mid roll (CityMonopolyServiceUnitTest only covers fixed rolls). | As 9009 on board `qa-monopoly-1`: `POST /api/city-game/territory/challenge/quote {territoryId}` → `challenge/command {requestId, quoteId, quoteHash, evidence}`. | app.log: `NullPointerException: Cannot invoke "java.lang.Integer.intValue()" at CityMonopolyService.challengeCommand(CityMonopolyService.java:1292)`; both full runs. |
| A5 | app (native iOS) | **High** | `PlayExperienceService.completionRequest(.scan)` removes `nodeId` ("source checkin resolves node from code") but still merges the action-context fields. The backend refuses every context-bound basic command without `nodeId` (`PlayBasicIntentApplication.basicIntentScope` → `INTENT_INPUT_INVALID` "参数有误"), so no in-app door check-in succeeds once action-context binding is on. Fix in the app: send the `nodeId` the action context was read for. Alternatively, the backend resolves the node from `code` when `nodeId` is absent. | As 9004 on a live run after the node's advanced qa: `POST /api/play/checkin` (multipart) `activityId, topicId, code, routeActionId, expectedRouteVersion, routeSessionId` → "参数有误". The same request plus `nodeId=992021` → "打卡成功". | F06, both full runs. |
| B5b | backend / data | Medium | The B5 fallback (`qa.answerText`) is applied only when a topic is compiled at publish. Topics published before the fix keep `nodeAnswers.<node>.questionAnswer = null` in `published_route_graph_json`, so every new run answers `CONFIG_INVALID` "该节点答案未配置" until the topic is recompiled. On TEST, 992001 cannot be recompiled through `publish-draft`: a SELLING commercial topic is refused with "商业主题只有 DRAFT/PENDING_REVIEW 可提交立项审核", and its seed media are bare URLs, which P-17 refuses. Production topics published before B5 will behave the same. Needs a one-off recompile/backfill of `nodeAnswers` (or the runtime fallback from the template). | Fresh run on 992001 before the fixture repair: `play/answer` → `CONFIG_INVALID`. | Wave 6 run 1; fixed on TEST by `fixtures.frozen_answer()`. |
| B9 | backend | Low | Authorization and "already done" refusals that SMOKE-500-R4 did not convert still answer code 500: `dyncode/issue` on another member's order ("无权操作该报名单"), non-owner `scan_dynamic_code`, rescan "该订单已核销", foreign `coupon/verification`, `activity/info` unknown id, player `activity/publish`, `login/phone` bad code, `roam/finish` twice. The messages are clear. The harness asserts "refused with a message" for these, and SMOKE-500-R4 codes (400/404/409) where they exist. | See the steps in `results.md`. | `full2`. |
| A6 | app (native iOS) | Coverage | The native client has no client for the City Monopoly territory endpoints (`/api/city-game/territory/*`, `/api/roam/territory/checkin`). F09 is a backend-contract flow written from spec §6.2. The store-day path (`Core/PlayStoreDayService.swift:99-124`) still sends no action context (A1 residual, not on the main play path). | — | source read of `origin/ui/main` a31949367 |

### Product decisions to confirm (not changed)

- **F03:** the anonymous detail of a club-run activity shows the club gate, not the detail.
- **F02 (same rule):** club-run sessions never appear on the public home lists (`PUBLIC_SHELF` excludes community activities), so a club-run session is only reachable through the club page or "my joined".
- Carried over: several active free registrations per member and session; the SMS per-IP window keyed on the proxy address.

### Harness changes (requests now match the native iOS client, `chengyin-app` `origin/ui/main` Core/*)

- The `Authorization` header carries the bare token, and form fields go as multipart/form-data (`AuthRequestBuilder`). JSON is used only where the native service sends JSON (club, wallet/stages, registration quote/create, consents, help/use, advanced play, merchant finance, aftercare respond, coupon publish).
- The Flutter probes are removed (multipart `club/list`, form `wallet/stages`, `POST ending`, answer without `routeSessionId`). They are replaced by the native shapes, and by replay/idempotency checks where the probe was the only use.
- Self check-in and answers bind to `GET /api/play/action-context` and send `routeActionId, expectedRouteVersion, routeSessionId[, runtimeInstance, authorityEpoch]` (A1). Nodes with an advanced mechanic are played through `/api/play/advanced/start` + `SUBMIT_QA` first.
- The ticket wallet decodes `{total, rows}`. F02 pages through the whole upcoming list. Assertions use the SMOKE-500-R4 codes (`approve` 400, protection 409, over-balance 409).
- Logins plant a TEST code in Redis (`E2E_PLANT_CMD`) instead of calling `sms/send` for every member, so the per-IP SMS window no longer breaks runs. F01 and F18 still call the real `sms/send`.
- New result kind **TEST-SKIP** (an environment gap with its reason; does not fail the flow). `results.md` shows pass / fail / TEST-SKIP per flow.

### Fixtures (`fixtures.py`, run first by `run_all.py`; idempotent; TEST DB through the VPS)

Each run first undoes what the last run or an api-smoke pass left behind, then writes fresh rows. SQL runs through `docker exec be-int-mysql` (on the VPS) or `instabox exec work --stdin` (from the Mac). Every batch aborts unless the schema is `cy_e2e_*`.
- **Live play window:** a new QA activity on topic 992001 / club 7001 per run (ids 9940001+, starts now − 15 min, free ticket + 0.01 paid ticket). Earlier QA activities are retired and their open registrations released by the topic owner (`cancel-by-owner`), so every run has a fresh session. 992031's schedule is no longer moved.
- **Registrations:** a fresh free registration for 9001 (F05, F06, F07) and for 9004 (F06 self check-in). A paid registration for 9001 is impossible on TEST (503, see above).
- **Topic 992001:** back on the shelf (`user_status 1`). The published snapshot gets node 992021's frozen answer, the value the post-B5 compiler produces (B5b). Node 992021 gets check-in code `QA992021`.
- **im_block** rows between 9001 and 9009 are deleted.
- **Quotas:** club 9003 activity quota gets room by retiring test-runner rows only (`QA 验收活动%`, `E2E API smoke check`). The coupon publisher is 9005 (0/3), and earlier `QA 验收券 %` coupons are stopped.
- **Granted coupon:** a fresh unused coupon for 9004 on QA store coupon 9940601 of 9002 (`scope_type 1`, store 8001).
- **Pending refund_application** (status 0) copied from seed 992657 on 9002's activity 992035.
- **APPROVED cover asset** of 9003 (990103). Earlier QA topics of 9003 are taken off the shelf. 9006 leaves club 7001 so F10 can join again.
- **City Monopoly:** LIVE board `qa-monopoly-1` (currency POINTS = 积分, per the owner ruling of 2026-11-15) with district `qa-d1` and POIs 994501–994503 in the TEST play area. It also holds a territory of 9001 past its protection, for the challenge. Earlier QA territories, evidence, challenges and yield events are cleared. The board's frozen `config_json` overrides only two settings: `claim.require_app_attest=false` (a harness cannot produce an Apple App Attest assertion) and claim hours 00:00–23:59 (deterministic at any hour). The account gates are met with TEST data: `create_time` of 9001/9004/9009 set to 2026-11-01, level ≥ 3 for 9004/9009, at least 600 积分, and three recent visited POIs for 9009.
- **Payment:** TEST-SKIP with the reason exported as `E2E_PAY_SKIP`.

### api-smoke now restores the state it changes

`qa/acceptance/smoke_state_guard.py` is installed next to the shared runner (`/data/workspace/api-smoke/`, and the paged copy `/tmp/r3-w4/api-smoke-paged/`). Both `smoke_runner.py` files now end with `smoke_state_guard.guarded(main)`; the originals are kept as `smoke_runner.py.prev-acc-w6`. It snapshots topic 992001's shelf status and the `im_block` ids between fixture players before a pass, and restores them afterwards, also on an error. Verified on TEST with a simulated pass: shelf 1 → 0 → 1, and the new `im_block` row 95 was removed.

### How to run (on the `work` VPS)

```bash
cd /data/workspace/qa-acc-r3
E2E_BASE_URL=http://127.0.0.1:8120 \
E2E_CODE_CMD='docker exec be-int-redis redis-cli -n 9 GET sms_{phone}_code' \
E2E_PLANT_CMD="docker exec be-int-redis redis-cli -n 9 SET sms_{phone}_code '\"{code}\"' EX 300" \
/tmp/qa-venv/bin/python3 qa/acceptance/run_all.py          # fixtures + all 18 flows (wrapper: /tmp/acc-w6.sh TAG)
```

From the Mac, set `E2E_FIXTURE_REMOTE=1` (SQL/Redis through `instabox exec work`). `E2E_SKIP_FIXTURES=1` runs the flows on the current data.

### TEST state changed by this wave

- New rows: QA live activities in 9940001–9940016 and their tickets, all retired (`fixtures.teardown()` now runs at the end of `run_all.py`; the ids 9940005/7/13/15 are F13's own published-and-deleted activities, which follow the raised auto-increment); QA coupon 9940601 and granted histories 970103–970115; refund_application copies 992658–992669; board `qa-monopoly-1`, district `qa-d1`, POIs 994501–994503, their board points and QA territories; three `member_visited_poi` rows for 9009.
- Changed: topic 992001 `published_route_graph_json` (node 992021 `questionAnswer`), and `cms_topic_node` 992021 `checkin_code`. 992001's `img_url`/`img_arr`/`finish_medal_img` were briefly set to `media-asset:990103` during a failed republish attempt and then restored to the seed URLs; `is_draft` is back to 0.
- Changed: `create_time` of 9001/9004/9009 → 2026-11-01; `member_growth` level ≥ 3 for 9004/9009; `city_abuse_signal` rows of 9001/9004/9009 cleared.
- Retired: smoke activities 993067–993069 (`cancel_time`), and coupon 970003 (status 0; `seed_4xx_fixtures.py` upserts it back).
- api-smoke runners patched as described above.

---

## Wave 5 re-test (2026-11-15 21:49–22:02 UTC, backend `integration/backend-app-r3` @ `785d70908`)

- **Target:** TEST 8120 (be-int, DB `cy_e2e_r2`), called over loopback `http://127.0.0.1:8120` on the `work` VPS. Production was not touched.
- **Backend:** jar built from `82f0bab79` = `81db27155` + `fix/security-r3` (`2f50c7fe0`) + `fix/acceptance-r3` (`fdf15c0ff`, B1–B8) + `fix/smoke-500-r4` (`d58cdc2d1`). `785d70908` adds one test-only commit on top. Migration `migration_20261125_admin_r3_perms` was applied first.
- **Runs:** full run #1 (finished 21:49 UTC) directly after an api-smoke round; full run #2 (finished 21:53 UTC) after restoring two fixtures that the api-smoke run had changed (see "Interference"); then targeted re-runs R1 (F11), R2c (F04 + F07 as 9001 in a live window) and R3 (F18). `results.json` / `results.md` hold full run #2; `results-rerun.json` holds the re-runs.
- **Logins:** fixture phones only. Codes are read from TEST Redis (`E2E_CODE_CMD='docker exec be-int-redis redis-cli -n 9 GET sms_{phone}_code'`). F01's `e2e-code` login works too now (B4).
- **Result: 3 of 18 flows pass every step in the full run (F01, F08, F17), plus F18 in its re-run. Before the fixes it was 0 of 18.** All eight backend defects B1–B8 behave as fixed on TEST. No new backend defect was found. The remaining non-pass steps are TEST data, the four Flutter app gaps (A1–A4, which the harness asserts on purpose), or harness expectations that the fixes made outdated.

### Per flow (full run #2, pass / fail / skip)

| Flow | Full run #2 | Re-run | Verdict | Cause of each non-pass step |
|---|---|---|---|---|
| F01 Sign-in | 8 / 0 / 0 | — | **Pass** | `login/code` with `e2e-code-*` now returns a token (B4 fixed). |
| F02 Browse home | 8 / 1 / 0 | — | Fail (data) | Unchanged: the harness checks page 1 of the upcoming list only, and 992031 is not on it. |
| F03 Activity detail | 5 / 1 / 0 | — | Fail (data) | Unchanged: the anonymous detail of a club-run activity is the club gate (product decision, see below). |
| F04 Register and pay | 8 / 1 / 3 | R2c as 9001: 8 / 1 / 3 | Partial | Free path passes end to end (also as 9001). `payment-readiness` = "支付服务暂不可用" (no pay config on TEST); paid steps skipped (no paid fixture). |
| F05 Ticket QR | 3 / 2 / 0 | — | Fail | A4 (app shape; native iOS is fine). `dyncode/issue` for registration 74 → "该票已核销": it was redeemed in the previous round (data). |
| F06 Check-in | 1 / 4 / 0 | — | Fail | Registration 74 is already redeemed (data), so issue/scan/rescan cannot pass. Self check-in in the app's shape → A1 (app). |
| F07 Play game | 2 / 11 / 0 | R2c as 9001 in a live window: 8 / 5 / 0 | Fail | Full run uses 9009, whose run finished: `route-state` now reports `COMPLETED` and `action-context` answers 409 "本局已结束" (B1 fixed); every later step cascades. As 9001: nodes, route-state (`ACTIVE`), action-context, help ladder, help/use and its idempotent replay pass. `play/answer` now refuses with `errorCode CONFIG_INVALID` "该节点答案未配置", not "答案不正确" (B5 fixed): session 993807 was frozen in the previous round, before the fix, with an empty answer, as FIXES-R3 predicts. A fresh run would freeze the fallback answer. App-shape answer (A1) and `POST ending` (A2, now 405) fail by design. |
| F08 Roam and fog | 10 / 0 / 0 | — | **Pass** | The client-clock records step now passes (B3 fixed). |
| F09 City Monopoly | 3 / 0 / 6 | — | Partial | Unchanged: no board/POI fixture. |
| F10 Club join and chat | 8 / 2 / 0 | — | Fail (app + harness) | Multipart `club/list` → `415 UNSUPPORTED_MEDIA_TYPE` "请求格式不正确…" (A3 app gap, response now per B8). `join-request/approve` → 400 "入会申请不存在或已处理" because 9006 joined directly; the harness accepts only 200/500 and predates the SMOKE-500-R4 change to 400 (harness expectation). |
| F11 Messages | 2 / 8 / 0 | R1: 8 / 2 / 0 | Fail (data) | Full run: the api-smoke pass had just blocked 9009 for 9004 (`im_block` 94), so the DM chain was refused "对方暂不可联系". After removing that row (R1) the DM chain passes; the group steps fail on fixtures as before. `memberCount` of club_7001 is now 0, not −16 (B6 fixed). |
| F12 Publish topic | 1 / 0 / 4 | — | Partial | Unchanged: no APPROVED cover asset. |
| F13 Publish activity | 1 / 5 / 0 | — | Fail (data) | Unchanged: club 9003 at its activity quota (3). |
| F14 Merchant verify | 1 / 1 / 6 | — | Fail (data) | Unchanged: merchant 9002 at its coupon quota (3); no granted coupon. |
| F15 Merchant refund | 3 / 0 / 2 | — | Partial | Unchanged: no pending refund_application. |
| F16 Creator earnings | 8 / 1 / 0 | — | Fail (harness) | Form `wallet/stages` → `415 UNSUPPORTED_MEDIA_TYPE` (B8 response). Native iOS sends JSON. |
| F17 Withdraw (TEST) | 11 / 0 / 0 | — | **Pass** | Over-balance → `409` "用户余额不足" (B2 fixed). |
| F18 Account deletion | 5 / 5 / 0 | R3: 10 / 0 / 0 | **Pass (re-run)** | Full run: 9001 still held free registration 992201 from the previous round, so precheck listed "存在待支付或未核销的有效报名" (data; the blocker logic is unchanged). After cancelling that order as 9001 (R3): apply → PENDING, old token 401, result PENDING, cancel → CANCELLED, and status is back to `NORMAL` with `executeAfter: null` (B7 fixed). |

### Backend defects B1–B8: live status on TEST

| # | Status | Live evidence |
|---|---|---|
| B1 | **Fixed** | 9009 (session 1, COMPLETED): `route-state` → `status COMPLETED`, nodes `DISCOVERED_LOCKED`, no current node; `action-context` → 409 "本局已结束". 9001 (live session 993807) still gets `ACTIVE` and PLAYABLE nodes. |
| B2 | **Fixed** | F17 over-balance `withdrawal/create` → code 409 "用户余额不足". |
| B3 | **Fixed** | F08 records stamped with the client clock → 200. |
| B4 | **Fixed** | F01 `login/code` with `e2e-code-qa…` → 200 with a token. |
| B5 | **Fixed** (live run still blocked by data) | `play/answer` → `CONFIG_INVALID` "该节点答案未配置" (no longer "答案不正确" `answerWrong`). 9001's session was frozen before the fix; a session started after the fix freezes `qa.answerText`. |
| B6 | **Fixed** | `im/conversations` club_7001 `memberCount` 0 (was −16); stored `club.member_count` for 7001 is 0. |
| B7 | **Fixed** | After `deregister/cancel`: `status NORMAL`, `executeAfter null`, `blockers []`. |
| B8 | **Fixed** | `POST /api/play/ending` → 405 `METHOD_NOT_ALLOWED`; multipart `club/list` and form `wallet/stages` → 415 `UNSUPPORTED_MEDIA_TYPE`, each with the friendly message and no Spring text. |

App items A1–A4 are unchanged in this harness, because the harness sends the Flutter app's request shapes on purpose. The native iOS fixes are on the app branch `ui/h-acceptance-fixes` (see `qa/acceptance/FIXES-R3.md` on the backend branch).

### Interference from api-smoke (found in this run)

The api-smoke runner changes fixtures that these flows depend on, so the order of the two suites matters:
- Every smoke pass calls `POST /api/topic/update_user_status` for topic 992001 and toggles it on or off the shelf. After this round's three passes it was off, and full run #1 failed F03, F04 and F07 with "主题活动不存在/已下架" (3 / 6 / 2 passes). It was restored with `UPDATE cms_topic SET user_status=1 WHERE id=992001` (the seed value) before full run #2.
- A smoke pass created `im_block` id 94 (9004 blocks 9009), which breaks the F11 DM chain. It was deleted before R1.
- Run acceptance before smoke, or re-check these two fixtures first.

### Changes to the test code in this wave

- `f07_play_game.py`: the player is now `E2E_PLAY_MEMBER` (default 9009, as before). Use a player whose linear run is not finished (here 9001), because after B1 a finished run is reported honestly.
- Not changed, but outdated after SMOKE-500-R4 (refusals that used to be code 500 now carry 400/404/409): F01 "bad code rejected" still passes, while F10 `join-request/approve` accepts only 200/500. Update these expectations when the harness is next edited.

### TEST state changed by this wave

- DB: migration `migration_20261125_admin_r3_perms` (+10 `sys_menu` button rows, idempotent). Topic 992001 `user_status` 0 → 1 (seed value). `im_block` id 94 (9004 → 9009, created by api-smoke) deleted.
- 9001: free registration 992201 cancelled (cancel-refund, "无现金退款，本单未使用积分"); a new free registration **992203** on 992031 created by R2c and left active (F07 needs a registered live player). Cancel it before the next F18 run. Deregistration applied and cancelled again (status NORMAL).
- 9004: new free registration from F04 (full runs). 9003: a withdrawal created and undone (F17). 9006 joined club 7001 (F10).
- Activity 992031 moved to "now − 10 min" three times for a few seconds (R2, R2b, R2c) and restored each time to 2026-11-22 14:00–17:00 (verified). R2 and R2b were blocked before their first step by the SMS per-IP window and the order cancel; only R2c counts.
- SMS daily counters for 13900000001–09 were cleared before each run.

---

# Earlier run on `81db27155` (2026-11-15, before the fixes)

- **Target:** TEST server `https://8120-agqyfqxs5vwolymtoibzq3fhde.instabox.tech` (be-int, DB `cy_e2e_r2`). Production was not touched.
- **Live run:** 2026-11-15 17:05 UTC from the owner's Mac (the cloud session could not reach the TEST host; see the history note at the end). Full run of all 18 flows: `results-81db27155.json` / `results-81db27155.md`. Targeted re-runs afterwards: `results-rerun-81db27155.json`.
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

Counts are from the full run (`results-81db27155.md`). "Re-run" is from `results-rerun-81db27155.json`. During R1–R3 the start of activity 992031 was briefly set to now − 10 min, then restored to 2026-11-22 14:00–17:00. Verdict key:
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
