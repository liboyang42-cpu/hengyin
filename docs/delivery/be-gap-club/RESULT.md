# be-gap-club — BACKEND GAP PACKAGE CLUB (the 16 `club` rows of the true-missing list)

Branch `feature/be-gap-club`, cut from `origin/integration/backend-app-r2` **`f89005866`** (R3: be-gap-im merged). Worktree `/data/workspace/be-gap-club`
on VPS `work`, curl on **8124 / DB `cy_gap_club`**. Production, 8120 and hengyin `master` were not touched.

Source of the rows: app repo `docs/backend-gaps` `9e40fe69a`, `docs/release/backend-true-missing-85.tsv`, area `club` (16 rows; same as
`/data/workspace/capgraph/out/true-missing.tsv`). Client contract read from app `origin/ui/main`: `Core/ClubGapClient.swift` (the only HTTP caller of all
16), `Core/ClubGovernanceCommands.swift` (`submitEvidence`, `issueGroupCode`), `Core/ChinaReviewedRouteCapability.swift`
(`clubAndMerchantCoopOwnerGrant`, Handover 20, owner correction 2026-11-13), `Core/ChannelDiscordMessaging.swift:3008-3098` (mini-program page names),
`Core/Adapt3GroupCodeIssueAdapter.swift`, `docs/release/gap345/club-channels.md`. Semantics: mini-program `chengyinhub-xcx/pages/club/*`
(they call the long paths below), and backend owner decisions in code comments.

## The wire contract (all 16 rows)

`ClubGapClient.perform`: **POST** `application/json`, body = exactly the `ClubGapSurface.all` fields, raw token in `Authorization`, no idempotency
header. The reply decoder reads only `code`, `errorCode` and `msg`: success = HTTP 2xx **and** `code == 200`. Every other outcome is shown as unavailable,
and no `data` is decoded. The backend therefore keeps the AjaxResult envelope, and every refusal carries a non-200 `code` (and `errorCode` where the
long path has one).

## Path → implementation → test → curl (8124 / cy_gap_club)

Controller `ApiClubGapController` (new) unless noted. "Alias" = the app body is handed unchanged to the long-path controller method, so login,
permission, content check, idempotency and error codes are exactly the mini-program's. Owner 9003 (13900000003), member 9004, newcomer 9001, club 7001,
activity 992031, topic 992001. Transcript: `curl-club.txt`.

| # | Path (app body) | Implementation | Unit test | curl |
|---|---|---|---|---|
| 1 | `/api/club-compensation/quality/evidence` `{clubId, topicId, dimension, evidenceHash}` | **Retired, 410.** Owner decision 2026-09-10: shop-day quality evidence was removed with its whole chain (service, tables and tests are gone; Q is confirmed by hand in the back office). The route now exists only to answer `code 410, errorCode QUALITY_EVIDENCE_RETIRED` without reading the body (same convention as `ROUTE_EGG_OFFLINE`). It lives in `ApiClubCompensationController`. | `ApiClubGapControllerTest.qualityEvidenceIsRetiredBeforeLogin` | owner: `code 410 QUALITY_EVIDENCE_RETIRED`; signed out: 401 from the security filter |
| 2 | `/api/club/activity/cancel` `{activityId}` | Alias of `event-ops/waitlist/cancel`. The app sends no `ticketId`, so `ClubWaitlistTickets.forCancel` takes **the caller's own** active entry (REQUESTED/WAITING/OFFERED) on that session. With none, it falls back to the session ticket, and the service answers "nothing to cancel". With several, it returns **400 `TICKET_REQUIRED`** plus `ticketIds`. | `activityCancelIsTheWaitlistCancelOfTheCallersOwnEntry`; IT `waitlistTicketIsResolvedOnlyWhenUnambiguous` | 9004: `code 409 场次候补策略不存在` (the waitlist service; seeded 992031 is not a series occurrence, so it has no waitlist policy) |
| 3 | `/api/club/activity/detail` `{clubId, activityId}` | Alias of `event-ops/occurrence/status` (session state, cancellation receipt). Manager-only, as on the long path. | `activityDetailIsTheOccurrenceStatus` | owner 200 `occurrenceStatus ACTIVE`; member `code 403` |
| 4 | `/api/club/activity/join` `{activityId}` | Alias of `event-ops/waitlist/join`. `ClubWaitlistTickets.forJoin` takes the session's only ticket or its only sold-out ticket, else **400 `TICKET_REQUIRED`** plus `ticketIds` (it never guesses). Inventory, deadline, membership and merchant checks stay in `ClubWaitlistServiceImpl`. | `activityJoinIsTheWaitlistJoinWithTheResolvedTicket`; IT (above) | 9004: `code 409 场次候补策略不存在` (as above). Before the resolver this was `400 候补参数不完整`, fixed in `481e2ecd8`. |
| 5 | `/api/club/channels` `{clubId}` | The club guild's channel directory: `ChannelPlatformService.directory(me, 1, clubId)`, the same source as `GET /api/channels?guild_kind=1`, with the channel envelope (`errorCode`, HTTP status). | `clubChannelsIsTheClubGuildDirectory` | owner 200, groups of club 7001; non-member 403 `GUILD_FORBIDDEN` |
| 6 | `/api/club/events` `{clubId}` | Alias of `event-ops/series/list`. | `eventsIsTheSeriesList` | owner 200 `[]` (before the save); member `code 403` |
| 7 | `/api/club/events/Checkin` `{clubId, activityId, memberId, arrived, expectedVersion, reason, requestId}` | Alias of `event-ops/attendance/correct` (manager attendance correction: version CAS, reason, idempotent `requestId`). | `eventsCheckinIsTheAttendanceCorrection` | owner 200 correction 1 for 9004 (`newArrived true`); member `code 403` |
| 8 | `/api/club/events/proxy-enroll` `{clubId, activityId, memberId}` | **New.** `club:activity:manage` (club access context, 403 `CLUB_FORBIDDEN`), then `ClubProxyEnrollService.propose`. It records an **invitation only**: no registration, no money, no real name or phone. The member signs up and pays themselves on `/api/registration/*` (the per-member sign-up consent cannot be given by someone else). The state comes from facts: `REGISTERED` once the member has a valid registration for the session, otherwise `INVITED`. One row per (session, member), and a repeat returns it with `replayed: true`. Refusals: 404 `CLUB_NOT_FOUND` / `ACTIVITY_NOT_FOUND` (session not in this club), 409 `ACTIVITY_NOT_OPEN` (cancelled, ended, unpublished), 409 `PROXY_ENROLL_NOT_MEMBER` (not an active member; the owner counts), 409 `PROXY_ENROLL_ALREADY_REGISTERED`. The member reads their invitations at `POST /api/club/events/proxy-enroll/mine` (not called by the app yet). Table `club_proxy_enroll`, migration `20261118_club_proxy_enroll`. | `proxyEnrollNeedsActivityManageBeforeAnyWrite`, `proxyEnrollMineIsTheCallersOwnInvitations`; IT (3 cases) | owner → 9001: 200 `INVITED`; again: 200 same id `replayed true`; → 9004: 409 `PROXY_ENROLL_ALREADY_REGISTERED`; → 9002: 409 `PROXY_ENROLL_NOT_MEMBER`; as member 9004: 403 `CLUB_FORBIDDEN`; 9001 `/mine`: 1 invitation from 9003 |
| 9 | `/api/club/events/save` `{clubId, topicId, recurrenceType, startDate, startTime, occurrenceCount, customDates, capacity, defaultLeadMemberId, waitlistEnabled, offerMinutes, requestId}` | Alias of `event-ops/series/create` (content check, idempotent `requestId`, original-result snapshot). | `eventsSaveIsTheSeriesCreate` | owner 200 series 1 (one occurrence 2026-12-20 19:00); same `requestId`: 200, the same series 1 (one row in DB); member `code 403` |
| 10 | `/api/club/group-code` `{activityId}` | **New read.** `IGroupCodeService.status` runs the same session and permission checks as `issue` but signs nothing: `{activityId, topicId, clubId, issuable, ttlMs}`. The group code is a stateless signed token, so there is no "current code" to read. | `GroupCodeIssuanceAcceptanceTest.statusAnswersTheIssueChecksWithoutSigningACode`, `groupCodeReadsEligibilityAndSaveIssuesFromTheJsonBody` | owner 200 `issuable true, ttlMs 300000`; member `code 500 您没有该场次的团码核销权限` (the long path's error convention) |
| 11 | `/api/club/group-code/save` `{activityId}` | `IGroupCodeService.issue`, the same as `/api/verify/groupcode/issue`, but it reads `activityId` from the **JSON body**. The long path binds a form parameter, which is the ADAPT-3 defect in `Adapt3GroupCodeIssueAdapter.swift`. | (same test) | owner 200 signed code + QR (code redacted in the transcript) |
| 12 | `/api/club/join-requests/apply` `{id, joinMessage}` | Alias of `/api/club/join` (open club → joined; review club → pending, and the message is required, ≤ 60 chars, content check). | `applyIsTheClubJoinWithTheMessage` | 9001 → 7001: 200 `state joined`; signed out 401 |
| 13 | `/api/club/notices` `{clubId}` | Alias of `event-notification/audience-counts` (the "notice audience" counts). | `noticesIsTheAudienceCount` | owner 200 `ALL_MEMBERS 4, ADMINS 1, INACTIVE 0`; member `code 400 俱乐部身份无效或权限不足` (long-path code) |
| 14 | `/api/club/notices/send` `{clubId, audienceType, title, content, requestId}` | Alias of `event-notification/send`. The channel defaults to `IN_APP` in the service (the app sends none). The `requestId` replay returns the same campaign. | `noticesSendIsTheCampaignSend` | owner 200 campaign 1 `IN_APP`; same `requestId`: 200 campaign 1; member `code 403` |
| 15 | `/api/club/settlements/export` `{clubId}` | **New, read-only.** It needs `club:finance:read` first (403 `CLUB_SETTLEMENT_FORBIDDEN`), and then returns the **club** view of `/api/club/settlement/summary` with `clubId, readOnly: true, exportedAt` added. It never falls back to the personal view, never moves money and never withdraws. The app sends it only when its payout switch is on (off in China), so this is reachable but not exercised by the app today. | `settlementExportIsTheClubSummaryForFinanceReadersOnly` | owner 200 `¥0.00 verified, topics []` (no settlements on TEST); member 403 `CLUB_SETTLEMENT_FORBIDDEN` |
| 16 | `/api/club/workbench` `{clubId}` | **New aggregate** for the manage tab (the mini-program `pages/club/workbench` only redirects to the club detail manage tab). Club access context: `roleCodes`, `permissions`, `tools` (only the entries the caller holds: `join_requests, members, event_ops, checkin, notices, content, settlement, roles`), `isOwner`, `memberCount`, and `pendingJoinRequestCount` (with `club:member:approve` only, else null). A caller without any manage permission gets 403 `CLUB_WORKBENCH_FORBIDDEN`. | `workbenchListsOnlyTheToolsTheCallerHolds`, `workbenchRefusesPlainMembersAndOutsiders` | owner 200 (`CLUB_OWNER`, all tools); member 403 `CLUB_WORKBENCH_FORBIDDEN` |

## Migration

`chengyinhub-admin/sql/migration_20261118_club_proxy_enroll.sql`: one new table, `CREATE TABLE IF NOT EXISTS` (rerunnable), no FKs, no backfill, no
change to existing rows. The only ledger change is the entry `"20261118_club_proxy_enroll": []` in `ci/migration-dependencies.json`.
Gates on the branch head: `migration_ledger.py audit` problems=[], `manifest --target-commit <sha>` OK (the entry is in the manifest),
`migration-layout-check.sh` pass, `migration-safety-check.sh` 5/5 pass, `dup_create_table_gate.py` pass.
`cy_gap_club` = a copy of the seeded TEST DB `cy_e2e_r2` (616 tables), with the migration applied **twice** (617 tables).

## Tests (VPS, `flock /data/workspace/.mvn.lock`, `-T 1`, `MAVEN_OPTS=-Xmx1536m`, surefire `-Xmx1536m`)

On the branch head `481e2ecd8` (code; this file follows it and changes only `docs/`):

* **Build:** `mvn package -DskipTests` BUILD SUCCESS. The jar starts on 8124 without ambiguous mappings, so none of the 17 new routes collides with an existing one.
* **Full unit suite** (`-pl chengyinhub-framework,chengyinhub-system,chengyinhub-admin -am`): **19,369 run, 0 failures**, 34 errors, which are the same 14
  `*MyBatisTest` classes that need MySQL on 3306 (as on `integration/backend-app-r2`). Those 14 classes against be-int-mysql 3330: **39/39**.
* **Package tests: 34/34.** `ApiClubGapControllerTest` 17 (one or more per route), `ClubProxyEnrollMysqlIntegrationTest` **4** (real MySQL 8,
  `CHENGYIN_R2_MYSQL_IT=1`: migration applied twice + unique-key shape, invitation idempotency and fact-derived `REGISTERED`, every refusal, and the
  waitlist ticket resolver on real rows), `GroupCodeIssuanceAcceptanceTest` 6 (+1 `status` case), `ApiClubCompensationControllerTest` 6,
  `ApiPlayerGapsControllerEggTest` 1.
* The first pass on `b637ab620` (before the ticket resolver) was also 19,368 / 0 failures; its curl showed `activity/join|cancel` refused with
  `候补参数不完整` (no `ticketId`), which `481e2ecd8` fixes. Logs: VPS `/data/workspace/be-gap-club-out/` (`gates.log`, `build.log`,
  `test-unit.log`, `test-club.log`, `mybatis-rerun.log`, `curl-club.txt`), the first pass in `run2-b637ab6/`.
* **curl:** `smoke_club.py` (this folder) on 8124 / `cy_gap_club`, rebuilt fresh from `cy_e2e_r2` right before the run, results in the table above
  and in `curl-club.txt` (tokens never printed, the signed group code redacted). 8124 was stopped afterwards. Redis db 14 was used (8120 keeps db 9).

## Not done / for the owner

* **`quality/evidence` stays closed (410)**, because the owner removed it on 2026-09-10 and app-gaps-b listed it as OFF (club-host money). The app's
  2026-11-13 grant only allows the app to *name* the path. Reopening it needs an owner decision and the service and tables restored, which is out of
  scope here.
* **`proxy-enroll` is an invitation, not a registration.** A real on-behalf registration needs a product and payment decision (who pays, and how the
  member's own sign-up data-sharing consent is given), as `gap345/club-channels.md` notes. Nothing notifies the member yet. They see the invitation at
  `/api/club/events/proxy-enroll/mine`, and the app has no client for that.
* `activity/detail` is manager-only (the long path's rule), although the app lists it under the member enroll surface. `activity/join|cancel` could
  not be shown succeeding on TEST: the seeded session 992031 has no series waitlist policy, and a series occurrence has no ticket until publishing.
  The waitlist rules are covered by the existing waitlist tests.
* The app still lists these paths as `.missing` in `ChinaReviewedRouteCapability` and hides the surfaces. Turning them on is an app-side change.
