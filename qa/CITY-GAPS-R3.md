# City Monopoly gaps r3: backend fixes for the iOS City client

Source: the gap list in `chengyin-app` `origin/ui/main:docs/release/receipts/ui-h-code-city.md` ("Missing endpoints / backend
findings"), checked against the approved spec (`docs/delivery/backend-app-r2/CITY-NUMBERS-ATTR.md`): City Monopoly uses
积分 only; prizes need presence within 80 m; rent and income go through points governance with caps.
Branch `fix/city-gaps-r3`, cut from `origin/integration/backend-app-r3` d43976db8.

All API changes are additive: no field was renamed or removed, and no request field became required. Clients that ignore
the new fields keep working, and so do clients that never call the new route.

Migration: `chengyinhub-admin/sql/migration_20261126_city_gaps_r3.sql`. It is additive, can be re-run, and documents
its rollback in the header. It is registered in `ci/migration-dependencies.json` after `20261124_city_numbers_attr`.
`ci/migration-safety-check.sh`, `ci/migration-layout-check.sh` and `ci/dup_create_table_gate.py` pass locally. It has
not been applied to any database (no deploys).

| # | Gap | Result |
|---|---|---|
| 1 | `claim/quote` returns HTTP 500 for a board point with no `place_id` | Fixed. Such a point now gets a normal quote |
| 2 | No district names, so the client shows 街区 1…6 | `districtName`, `districtColorToken` and `districts[]` added to the reads the client already calls |
| 3 | No deed serial (`地契 · No. N`) | `deedSerial` is issued atomically per district at first claim and kept on takeover |
| 4 | No defender or rent notification | In-app IM system cards through the existing `INotifyService` channel. No new push provider |
| 5 | N35 on-site task: no verifier | Built on the existing photo-check and GPS-evidence hooks. Presence is checked server-side within 80 m. New submit route |

---

## 1. `claim/quote` HTTP 500 for board points without `place_id`

**Cause.** `CityMonopolyService.resolveTarget` had
`Long place = r.get("place_id") instanceof Number ? ((Number) r.get("place_id")).longValue() : poiId;`.
One branch is a `long` and the other a `Long`, so Java types the whole expression as `long` and unboxes `poiId`. With
`boardId + pointId` and no `poiId` in the body, `poiId` is null, so the line threw an NPE and the endpoint returned 500.
B10 (`natural()`) was the same kind of bug.

**Fix.** Both branches are now boxed. A board-only point resolves to a target with `poiId = null` and is quoted, claimed
and held by `board_id + point_id`. Every downstream query already handles that case (`activeTerritory` board branch,
`poi_id <=> ?`). An unknown point is still the business refusal `404 SPACE_NOT_FOUND`.

**API.** No change to the contract. `POST /api/city-game/territory/claim/quote` with `{boardId, pointId}` now returns
the normal quote: `targetKey = "board:{boardId}:{pointId}"`, `boardId`, `pointId`, `propertyId?`, `tier`,
`districtId?`, `title`, `protectionHours`, `balance`, `quoteId`, `quoteHash`, `cost`, `currency: "POINTS"`,
`rulesHash`, `expiresAt`. There is no `poiId` key.

**Migration.** None.

**Tests.** `CityGapsR3Test#claimQuoteForBoardPointWithoutPlaceIdIsPricedInsteadOfHttp500` (regression: it calls
`resolveTarget` and `claimQuote` with a `place_id = NULL` row; the old line throws on it) and
`#unknownBoardPointIsStillABusinessRefusal`.

## 2. District names and colour keys

The model already had `city_district(board_id, district_id, name, color_token 'district.cN', ownable_count,
safety_blocked)`, but nothing read it for the client and nothing seeded it.

**API (new fields only).**

| Read | New fields |
|---|---|
| `GET /api/city-game/territory/map` | each `items[]` entry, including fogged ones: `districtName: string?`, `districtColorToken: string?`. Top level: `districts: [{boardId, districtId, name, colorToken, colorIndex: int?, ownableCount: int, safetyBlocked: bool}]` for every board in the result |
| `GET /api/city-game/territory/property/{id}` | `districtName`, `districtColorToken` |
| `GET /api/roam/territory/nearby` | each `items[]` / `territories[]` entry: `districtId` (new: from the board point, or from the held territory for roam POIs), `districtName`, `districtColorToken`, `deedSerial`. Top level: `districts[]` (same shape as above) for the resolved board and any board an item belongs to |

- `colorIndex` is N for a `district.cN` token. It is null for any other token, and then the client keeps its own slot.
- District names and colours are public board metadata, so they are also sent for fogged map items (which already
  carried `districtId`). Fog still hides the title, holder and deed.
- A district with no `city_district` row returns `districtName: null` and the client falls back to `街区 N`.

**Seed (migration §4).** `INSERT IGNORE` one `city_district` row per `(board_id, district_id)` found on live
`city_game_point` rows that don't have one yet:
- name = the district id, as a placeholder;
- colour = `district.c1`…`c6`, by sorted district id within the board (the same stable order as the iOS
  `CityDistrictSlots`);
- `ownable_count` = point count.

Existing rows are never overwritten, so real names go in with `UPDATE city_district SET name = '北京路' WHERE
board_id = ? AND district_id = ?` (or a later seed migration) and the reads serve them at once. **Open:** the real
Chinese names for the Guangzhou acceptance board are product data that is not in the repo.

**Tests.** `CityGapsR3Test#mapLayerCarriesDistrictNameColourAndDeedSerial`, `#propertyCardCarriesDistrictAndDeedSerial`,
`#nearbySpacesCarryDistrictAndDeedSerial`, `#missingDistrictRowMeansNullNameNotAnError`.

## 3. Deed serial (`地契 · No. N`)

**Model.**
- `roam_territory.deed_serial int NULL`: issued per `(board_id, district_id)` at first claim.
- `roam_territory.deed_serial_live` (virtual, `IF(status='ACTIVE', deed_serial, NULL)`) with
  `UNIQUE KEY uk_roam_territory_deed (board_id, district_id, deed_serial_live)`. Two live deeds in a district can
  never share a number, and a takeover can keep it.
- New counter table `city_deed_counter(board_id, district_id, last_serial)`.

**Assignment (atomic).** In `claimCommand`, after every check and the 积分 debit, in the same transaction:
`INSERT … VALUES (?,?,1) ON DUPLICATE KEY UPDATE last_serial = last_serial + 1`, then
`SELECT last_serial … FOR UPDATE`. The upsert holds the counter row's exclusive lock until commit, so concurrent first
claims in one district run one after another and each reads its own value. A claim that rolls back also rolls back its
number, so serials have no gaps. The unique key is the backstop.
- A **takeover** keeps the deed's serial: the old row becomes `REVOKED`, so its live serial goes to NULL, and the new
  row is inserted with the same serial.
- A deed that is **released** (decay or season reset) and claimed again later gets a new number.
- A space outside any board district (plain roam POI, or a point with no `district_id`) has `deedSerial: null`.

**Backfill (migration §3).** Live MONOPOLY deeds without a serial are numbered in `claimed_at` order after the district's
counter, and the counters catch up with the highest serial. A re-run only numbers rows that are still NULL.

**API (new field only).** `deedSerial: int?` is on every property object the service returns, through `wire()`:
- `territory/map` `items[]` (visible items; fogged items stay `{districtId, tier, geohash7, districtName, districtColorToken}`);
- `territory/property/{id}`;
- the claim, upgrade and challenge command `property`;
- `roam/territory/nearby` items;
- `GET /api/roam/territory/my` rows (also `districtId`).

The client renders `地契 · No. {deedSerial}` and hides the line when the field is null (the Q10 drop can be reversed).

**Tests.**
- `CityDeedSerialConcurrencyTest#concurrentFirstClaimsInOneDistrictGetDistinctConsecutiveSerials`: 12 real concurrent
  transactions on H2 in MySQL mode with row locks, running the production SQL, get exactly 1..12.
- `#districtsCountIndependentlyAndARolledBackClaimReturnsItsNumber`.
- `#migrationIsGuardedAdditiveAndKeepsLiveDeedsUnique`.
- `CityGapsR3Test#deedSerialIsOnlyIssuedInsideADistrict`.
- `#takeoverKeepsTheDeedSerialAndTellsTheDefenderThePlaceIsLost`: the new row carries serial 4 and no counter is touched.

## 4. Defender and rent notifications

Notifications go through the existing `INotifyService` fanout. A new event `NotifyEvent.CITY_TERRITORY` /
`INotifyService#notifyCityTerritory` is handled **only** by `InAppNotifyChannel`, which writes an IM system message
(`IImService.sendSystem`, the same path as team status and refunds). `WxSubscribeChannel` returns false for it. No push
provider and no subscribe template were added.

**Message (system conversation, `msgType = 3` card).**
- `content`: one complete Chinese sentence, so older clients show it as text.
- `extra` JSON: `{"cardType": "cityTerritory", "kind": "PLACE_LOST" | "DEFENDED" | "RENT" | "RENT_SETTLED", "territoryId": string?, "title": string, "points": int, "currency": "POINTS"}`.

| kind | Recipient | When | `points` |
|---|---|---|---|
| `PLACE_LOST` (被抢) | defender | a challenge command ends in `WIN` | defender compensation (40%) |
| `DEFENDED` (守住了) | defender | a challenge command ends in `LOSS`, or `TASK_FAILED` (the challenger failed the on-site task) | defender compensation (30%), or 0 |
| `RENT` | holder | the **first** visit of the board day that actually earned rent for this holder | that event's capped, pending rent |
| `RENT_SETTLED` | holder | the 03:00 settle credits PENDING rent | the holder's total credited in this settle run |

Rules respected:
- **After commit only.** The send is registered as `afterCommit` when a transaction is active, so a rolled-back
  challenge or visit never notifies. A failure is logged and swallowed and never fails or rolls back the game action.
- **Caps and anti-self-farming.** `RENT` is only sent after `onVisit` has passed every existing rule:
  - self visit, same team, store staff, visitor-diversity, device, pair and daily caps;
  - the owner's daily cap (`granted > 0`).

  A challenge that is refused (`OWN_PROPERTY`, `SAME_TEAM`, presence, attest, limits) notifies no one. Voided (flagged)
  rent never reaches `RENT_SETTLED`.
- **No flooding.** There is at most one `RENT` per holder per board day and one `RENT_SETTLED` per holder per settle run.
  The challenge notifications follow the existing per-property and per-member daily challenge caps.

**API.** No new route. Cards arrive through the existing IM system-conversation reads.

**Migration.** None.

**Tests.**
- `CityGapsR3Test#takeoverKeepsTheDeedSerialAndTellsTheDefenderThePlaceIsLost`, `#failedChallengeTellsTheDefenderTheyHeld`,
  `#rejectedChallengeNotifiesNobody`.
- `#notificationWaitsForCommitAndIsDroppedOnRollback`, `#notificationFailureNeverFailsTheGameAction`.
- `#firstEarningVisitOfTheDayTellsTheHolderOnce`, `#selfFarmedOrCappedVisitsNeverNotify`,
  `#settleSendsOneRentSummaryPerHolderAndNoneForVoidedRent`.
- `CityTerritoryNotifyChannelTest` (card shape, IM failure swallowed, WeChat channel doesn't send).

## 5. On-site challenge task verifier (N35)

The pattern fits, so it was wired rather than designed on paper. `CityOnSiteTaskService` is the `CityTaskVerifier`
bean, and it reuses two hooks that already exist:
- **GPS presence:** the City evidence format (`CityMonopolyService.parseEvidence`), checked on the server against the
  held place:
  - distance ≤ **80 m** (`CityOnSiteTaskService.PRESENCE_RADIUS_M`, the approved prize-presence radius; claims keep
    their own 40 m);
  - `hAcc` ≤ `city.monopoly.claim.max_h_accuracy_m`;
  - not a simulated location;
  - photo taken ≤ `claim.photo.max_capture_age_s` ago, within 80 m of the place.
- **Photo check:** `PhotoCheckVisionService.review`, with its own consent gate, the `photo_check` quota pool and degraded
  mode. It is checked against a prompt the server wrote into the quote. The client never reports a result.

**Flow.**
1. `POST /api/city-game/territory/challenge/quote` now includes a `task` object. The `taskRef` is as before; the other
   fields are new: `{"taskRef": "challenge:{territoryId}:{uuid}", "type": "PHOTO_PRESENCE", "prompt": "在现场拍一张「{title}」的门头、招牌或标志性外观", "radiusM": 80, "optional": true, "submitPath": "/api/city-game/territory/challenge/task/submit"}`.
2. **New:** `POST /api/city-game/territory/challenge/task/submit`. The body is `{quoteId, taskRef, photoUrl, evidence: {lat, lng, hAcc, simulated?, capturedAt, captureLat, captureLng, samples?}}`. The server checks:
   - the quote belongs to the caller, is a `CHALLENGE` quote, and is unused and unexpired;
   - `taskRef` is the one issued on that quote;
   - the territory is still live.

   It then grades the submission. The response is `{submissionId, taskRef, territoryId, result: "PERFECT" | "PASS" | "FAIL" | "NONE", presenceOk, distanceM, radiusM: 80, photoState: "PASS" | "FAIL" | "DEGRADED" | "SKIPPED", confidence?, photoReason?, reasons: [OUT_OF_RADIUS | POOR_ACCURACY | SIMULATED_LOCATION | PHOTO_TOO_OLD | PHOTO_LOCATION_MISMATCH | PHOTO_NOT_MATCHED], consumed, idempotentReplay}`.

   The route is member rate-limited at 10/min. The roam location consent gate runs first, in its own short
   transaction, and the model call runs with no DB transaction held.
3. `POST …/challenge/command` with `taskSubmissionId` consumes the submission once (`UPDATE … WHERE submission_id AND member_id AND task_ref AND consumed_at IS NULL`) and applies the existing spec modifiers: PASS +1, PERFECT +2, FAIL → `TASK_FAILED` with the 50% refund.

**Grading.**

| Outcome | Result |
|---|---|
| Presence fails | `FAIL`. The model is not called |
| Photo rejected | `FAIL` |
| Photo passes | `PASS`, or `PERFECT` at confidence ≥ 90 |
| Review unavailable (no key, quota, timeout) | `NONE`: no modifier and no refund. Never a fake pass |

Other rules:
- There is one submission per member per issued task (`UK member_id, task_ref`), so a player can't retry for a better
  grade, and a repeat call replays the stored result.
- A submission counts for one challenge only.
- Tasks never move 积分.

**Backward compatibility.** A challenge command without `taskSubmissionId` skips the task (`NONE`, modifier 0), which is
exactly how challenges ran before this bean existed. Today's iOS client, which doesn't build N35, is unaffected.

Chance-space tasks (`chance:` refs: QUIZ, FIND, CHOOSE) are **not** graded by this verifier.
`CityTaskVerifier#supports` keeps `chance/command` `COMPLETE` at `409 TASK_VERIFIER_UNAVAILABLE`, as before.

**Migration §5.** `city_onsite_task_submission`, with
`UNIQUE KEY uk_city_onsite_task_member_ref (member_id, task_ref)`.

**Privacy.** The table gets the same treatment as `city_claim_evidence`:
- `CityLocationRetentionService.clearTaskSubmissions` nulls `lat`, `lng` and `h_acc` after 30 days and keeps the
  distance and the result. It runs in `DataRetentionServiceImpl.cleanRoamLocations`.
- Account deregistration deletes the member's rows (`AccountDeregistrationMapper.deleteOnsiteTaskSubmissions`).

**Tests.** `CityOnSiteTaskServiceTest`, 11 tests:
- grading: PERFECT, PASS, FAIL and degraded→NONE;
- presence: 100 m out fails with no model call, 60 m passes; mock location, stale photo and poor accuracy are rejected;
- one submission per task (replay, no regrade);
- the task must be the one issued on the caller's open quote;
- `verify` consumes once and returns `NONE` without a submission;
- the challenge quote carries the task block, and chance stays closed.

Controller: `CityR3RateLimitContractTest` (now lists `challengeTaskSubmit`). Privacy: `CityLocationRetentionServiceTest#onsiteTaskSubmissionsDropCoordinatesButKeepDistanceAndResult`, `DataRetentionServiceImplTest`, `AccountDeregistrationR3ErasureTest`.

---

## iOS client field summary

| Where | Field | Type |
|---|---|---|
| `territory/map` items, `territory/property/{id}`, command `property`, `roam/territory/nearby` items, `roam/territory/my` | `deedSerial` | `Int?` |
| `territory/map` items (incl. fogged), `territory/property/{id}`, `roam/territory/nearby` items | `districtName`, `districtColorToken` | `String?` |
| `roam/territory/nearby` items, `roam/territory/my` | `districtId` | `String?` |
| `territory/map`, `roam/territory/nearby` (top level) | `districts` | `[{boardId, districtId, name, colorToken, colorIndex: Int?, ownableCount: Int, safetyBlocked: Bool}]` |
| `territory/challenge/quote` | `task.type`, `task.prompt`, `task.radiusM`, `task.optional`, `task.submitPath` (`task.taskRef` as before) | |
| new `POST territory/challenge/task/submit` | see §5 | |
| `challenge/command` body | `taskSubmissionId` (already in the contract, now honoured) | `String?` |
| IM system card | `extra.cardType == "cityTerritory"`, `kind`, `territoryId`, `title`, `points`, `currency` | |

## Verification

Run on 2026-11-19 in the cloud sandbox: JDK 21, no MySQL, no deploy.

**New and changed tests, all passing:**

| Class | Tests |
|---|---|
| `CityGapsR3Test` | 15 |
| `CityOnSiteTaskServiceTest` | 11 |
| `CityDeedSerialConcurrencyTest` | 3 |
| `CityTerritoryNotifyChannelTest` | 3 |
| `CityLocationRetentionServiceTest` | 4 |
| `DataRetentionServiceImplTest` | 12 |
| `AccountDeregistrationR3ErasureTest` | 2 |
| `CityR3RateLimitContractTest` | 1 |
| `CityLocationConsentGateTest` | 2 |
| `CityMonopolyServiceUnitTest` | 7 |
| `NotifyFanoutContractTest` | 18 |

**Affected modules:** `LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -pl chengyinhub-admin -am test -Dmaven.test.failure.ignore=true`.
The `-am` covers common, system, framework, quartz, generator and admin; the tests live in admin.
- Result: 12,741 tests run, **0 failures**, 34 errors, 563 skipped.
- The forked JVM then died with `OutOfMemoryError: Java heap space` in `TopicUpdateByOwnerTest`, so the admin module
  was cut short and reported `BUILD FAILURE`.

**Full suite:** `LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B test -Dmaven.test.failure.ignore=true`.
- Result: 12,742 run, **0 failures**, 34 errors, 563 skipped.
- The same OOM hit at the same test and the build ended in `BUILD FAILURE`.
- **The OOM is not caused by this change.** Clean `origin/integration/backend-app-r3` (d43976db8), run the same way,
  OOMs at the same test (12,752 run, 0 failures, 34 errors), and its set of failing classes is identical to this
  branch's.
- Cause: the root pom caps the test fork at `-Xmx512m`.

**Full suite, complete run:** the same command with only the heap raised:
`"-DargLine=-Xmx2g -Dspring.test.context.cache.maxSize=4"`.
- Result: **19,980 tests run, 0 failures, 34 errors, 713 skipped, `BUILD SUCCESS`**, no OOM.

**The 34 errors:** in every run they are the same 14 `*MyBatisTest` classes, all failing with
`Communications link failure` because they need the test VPS MySQL. None of them touches this change.

**Not run here (needs MySQL on the test VPS):**
- the migration itself;
- the `CHENGYIN_CITY_GAME_MYSQL_IT` suites;
- `AccountDeregistrationR3ErasureMyBatisTest`.

**Gates run locally:** `ci/migration-safety-check.sh`, `ci/migration-layout-check.sh`, `ci/dup_create_table_gate.py`
and `ci/forbidden_hosts_gate.py` all pass.

Scope: 积分 only. No ledger, gitleaks, secret or deploy changes.
