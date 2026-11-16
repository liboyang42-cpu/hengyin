# W7 x-channel-events: receipt (hengyin)

Branch `feature/r-x-channel-events` from `origin/roadmap/be-integ` (d814ff1b5). App side: chengyin-app `cloud/r-x-channel-events`.
x-channel-screen was already in `roadmap/be-integ`, so nothing was merged in.

## Issues

| ID | Status | Notes |
|---|---|---|
| SOC-5 | App only | Posting a pin from search or the map; the server's `EVENT_PIN_REQUIRED` is unchanged. See the app receipt. |
| SOC-6 | Fixed (owner decision 3, option A) | `CREATE_EVENT` role permission; admins and chosen roles post **free** events hosted under the club owner; the real poster is kept in `channel_event.poster_member_id`; paid stays owner-only. |
| SOC-15 | Fixed (server + mini-program); app partly, see below | Invite links with expiry, use limit, revoke and a target channel. |

## Changes

### SOC-6
- `ChannelCapability`: `CREATE_EVENT` on bit 28 (bit 26 is reserved for MANAGE_BOUNTY, 27 is MANAGE_PROGRESS), channel scoped. `WRITABLE_BITS` = bits 0–25 plus 28; `parseHex` (role bits and overwrites) accepts exactly those, so 26/27 are still refused.
- `ChannelPermissionCalculator`: `CREATE_EVENT` is in `ADMIN_BASE` (admins have it by default; an overwrite can deny it) and in archive `WRITE_BITS`.
- `ChannelEventService`:
  - `mayPostEvents`: owner, or effective bits that include `CREATE_EVENT`;
  - a paid draft from a non-owner is refused with `EVENT_POST_FORBIDDEN`, whatever the money switch says;
  - `PostTarget` now carries `hostMemberId`, which is the club owner when someone else posts. `attach` checks that the activity belongs to that host, and the poster is stored in `poster_member_id`;
  - the list adds `can_post_paid`;
  - new refusal text: 你暂时不能在这个频道发活动，可以联系主理人或管理员.
- `ChannelGuildAccess.clubOwnerId`.
- `ApiActivityController.publishHostedByClubOwner`: the same gates as `/publish` (content safety, quota, review). The host is the club owner and the actor is the poster. It is not an HTTP route; only `ApiChannelEventController` calls it, after `preparePost` has authorised the poster. `publishActivity` still checks that `club.member_id` equals the host.
- `migration_20261129_channel_create_event_bit.sql`: widens `ck_overwrite_bits` from ≤67108863 to the bits 0–25 plus 28 mask (335544319). It is guarded through `information_schema.check_constraints` and has a rollback note. It runs before the new jar.

### SOC-15
- `migration_20261129_club_invite.sql`: table `club_invite` (code CHAR(10) unique, channel_id NULL, max_uses NULL, uses, expires_at NULL, revoked_at), idempotent, with a rollback note.
- `ClubInviteService` and `ClubInviteMapper`:
  - expiry is 1 day, 7 days or never; uses are unlimited, 1, 10 or 50;
  - a channel target must be a live channel of that club, and the creator needs `MANAGE_CHANNEL` on it;
  - one use is taken with a guarded UPDATE and given back if the join does not go through;
  - a member who is already in the club takes no use;
  - the creator or a manager can revoke.
- `ApiClubInviteController` (additive):
  - `POST /api/club/invites`
  - `GET /api/club/invites?clubId=`
  - `GET /api/club/invites/{code}` (landing)
  - `POST /api/club/invites/{code}/accept`: runs the existing `ApiClubController.join`, so the join policy still applies
  - `POST /api/club/invites/{code}/revoke`
- Erasure: `MemberPrivacyErasureService` revokes the member's invites and unlinks them as creator.
- Mini-program:
  - `utils/club-invite.js` holds the logic;
  - `pages/club/detail` reads `invite=`, shows an info banner with the existing `cy-inline-error` (data kind), joins through `/accept` while the invite is valid, and opens the channel list after joining when the invite targets a channel;
  - an expired, used-up or revoked invite says why and keeps the normal join.

## Verification (all run here)
- `mvn -B -q -DskipTests compile` (UTF-8): passed.
- `mvn … test -Dtest=ChannelEventServiceTest,ChannelPermissionCalculatorTest,ChannelPlatformServiceTest,ChannelGovernanceServiceTest,PlayerMessagePunctuationTest`: 32+8+14+4+2 tests, 0 failures.
- `ClubInviteServiceTest` 11, `MemberPrivacyErasureServiceTest` 4, `E2eTestProvidersProfileTest` 4, `TwoNodeSchedulingTest` 4, `SysJobStaggerPlanTest` 3: 0 failures.
- `bash ci/migration-safety-check.sh`: 迁移安全门禁:通过. `bash ci/migration-layout-check.sh`: 迁移布局门禁:通过. No new `rollback_*.sql`.
- Mini-program (`chengyinhub-xcx`):
  - `node ../ci/prepare-mini-artifacts.js`: passed.
  - `node --test "tests/unit/**/*.test.js"`: base `roadmap/be-integ` 17904 pass / 32 fail; branch 17909 pass / 32 fail, with identical failing test names. New: `tests/unit/club-invite.test.js`, 5 tests.
  - `node tests/lint/ui-integrity.js --warn-as-error`: UI-GATE-0 FAIL (6 violations) on both base and branch, the same pre-existing set. No new findings: request paths in the page are `/api` literals, and there are no new toasts.
  - The page loads `utils/club-invite.js` lazily, only when the link carries `invite=`, because the page test harnesses only allow a fixed list of requires.

## Owner actions and open questions
1. **Who may make invite links**: the review leaves this open. The conservative default is owner and admins only; `chengyin.club.invite.members-create=true` lets members make them too.
2. **Hosting confirmed by decision 3**: quota, settlement and refunds of admin-posted free events are the owner's.
3. Run the two migrations before deploying the jar (both are additive).

## Not done or left open
- SOC-15 in the app:
  - the app makes invites and opens the club from an invite link, but it does not call `/accept` yet;
  - a join from the app goes through the normal join, so it does not count a use or open the target channel.
  Doing that means carrying the code through `NativeEntryIntent` to the club page, which is a larger change across shared routing.
- The mini-program `pages/club/channels` share still shares the plain club link. Invites are made in the app (owners and admins).
- Old app builds: an overwrite or role with bit 28 set makes the old app's overwrite decoder refuse that row (old cap 0x3ffffff). Only new builds can set the bit.

## Overlap with the UI agent
- `fix/registration-list-r3` changes `ApiActivityController` (the cancel path) and `ApiClubController` (refusal codes). My hunks are elsewhere, and `git merge-tree` shows no conflict in `ApiActivityController`. Invite accept treats any non-200 join result as a refusal, so it works with either refusal shape.
