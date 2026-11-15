# R8 club-ops — backend receipt (`feature/r-club-ops`, base `roadmap/be-integ`)

## Findings
| ID | Status | Notes |
|---|---|---|
| CLB-06 | Closed (backend) | `POST /api/club/nearby` ranks public, approved clubs by great-circle distance. A club's point is its pinned address point (`club.location_lat/lng`, new) or else its latest published, located, uncancelled event. `/api/club/home` is unchanged (mini-program compatibility). |
| CLB-08 | Closed (backend) | `POST /api/club/ops/projects`: today's (and running) plus next 14 days' sessions, with enrolled / arrived counts and the actions this account may take (`scan`, `roster`, `checkin`, `proxyEnroll`, `settlement`), from club permissions. |
| CLB-09 | Closed (backend unchanged) | Proxy enrolment, settlement export and the workbench were already served (`ApiClubGapController`); the app now calls them from real screens. |
| CLB-10 | Closed | `POST /api/club/ops/dashboard {clubId, months≤12}`: per month new members, members at month end, events held, enrolled, arrived, no-shows, attendance rate, attendees and repeat attendees. |
| CLB-13 | Closed | Leaderboard gains `sortBy` `events` (events attended), `checkins` (check-ins) and `hosted` (sessions led). `POST /api/club/leaderboard/sorts` says which sorts fit a club: the three general ones always; `composite/mileage/pace/duration` only when the club has a topic with mileage. Existing sorts and response shape are unchanged. |

## Changes
- `chengyinhub-admin/.../api/ApiClubOpsController.java` (new): the four endpoints. `nearby` and `leaderboard/sorts` are `@Anonymous` reads (sorts of a non-public club need owner/member); `projects` needs `club:activity:manage` or `club:event:checkin`; `dashboard` needs `club:activity:manage`.
- `chengyinhub-system/.../clubops/ClubOpsService.java` (new): distance ranking, event-day board, monthly dashboard, sorts. Pure calculations are static and unit-tested.
- `chengyinhub-system/.../mapper/ClubOpsMapper.java` + `mapper/business/ClubOpsMapper.xml` (new): read-only SQL. Arrival uses the same rule as `selectPaidRoster` (lead check-in, ticket verification or node progress; latest attendance correction wins).
- `ClubMemberMapper.xml#selectClubContribution`: three extra columns `attendedCount`, `checkinCount`, `hostedSessions` (same weekly window).
- `ClubMemberServiceImpl`: count sorts in `qualifiesForBoard` / `comparatorFor`; `countBoardKey(...)`.
- `sql/migration_20261115_club_ops.sql` (new, id `20261115_club_ops`, depends on `club`): two nullable columns `club.location_lat`, `club.location_lng`, guarded by `information_schema`; rollback note at top.
- `ci/migration-dependencies.json`: entry for the migration.
- Tests: `ClubOpsServiceTest` (9), `ApiClubOpsControllerTest` (4), `ClubLeaderboardWeightingTest` (+1 → 14).

## Verification
- `LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -DskipTests compile` → OK.
- `mvn -B -q -pl chengyinhub-admin -am test -Dtest='ApiClubOpsControllerTest,ClubOpsServiceTest,ClubLeaderboardWeightingTest'` → 27 tests, 0 failures.
- `mvn ... -Dtest='*Club*Test,GuestBrowseAnonymousReadTest,PublicReadEndpointsSecurityChainTest'` → 1482 run, 0 failures, 3 errors: `ClubDissolutionGateMyBatisTest`, `ClubCrmCheckinDetailMyBatisTest`, `ClubTopicManageStatsMyBatisTest` need a live MySQL (`Communications link failure`), unrelated to this change.
- `bash ci/migration-safety-check.sh` → `迁移安全门禁:通过`.

## Not verified
- The new SQL (`ClubOpsMapper.xml`, the three leaderboard sub-queries) has not run on MySQL 8 here: no database in the cloud box. A MySQL IT in the style of `ClubLeaderboardContributionMyBatisIT` is the next step.
- Query cost on large clubs: `selectAttendance` scans the club's paid registrations in a 24-month lookback for the dashboard.

## Owner actions / open questions
- Club location: fill `club.location_lat/lng` (GCJ-02) from each club's address with the map geocoder (needs the map key in server config). Until then clubs appear "near you" only through their events. Should owners set a pin in the app? (not built).
- "Members" in the dashboard counts current members only: leaving deletes the `club_member` row, so past leavers cannot be counted. A leave log would be needed for true churn.
- "Repeat attendees" = attended this month and either attended an earlier club event (24-month lookback) or came more than once this month. Confirm the definition.
- Today's events enter the dashboard the next day (an event still running cannot have no-shows yet).
- No hard-coded secrets found in the touched files.
