# Q2 club-reach — backend receipt (`feature/r-club-reach`, base `fix/r5-be-infra-stubs`)

App half: `chengyin-app` branch `cloud/r-club-reach`.

## Findings
- Default channels (brief "possible" item): **closed**. Finding: the directory already lazily provisioned 文字频道/综合 on first open, so `channelDirectoryUnavailable` was unlikely; clubs now get 闲聊 (default) / 公告 (announcement, kind 2) / 活动 instead.
- CLB-11 (data side): **closed**: `/api/club/detail` returns `memberAvatars`.

## Changes
- `ChannelPlatformService`: club guilds provision 闲聊 (default, position 0 because `ck_channel_default` requires it, so it lists first), 公告 (kind 2), 活动; teams keep 综合. New `ensureClubDefaults(clubId, ownerId)` (REQUIRES_NEW, idempotent: any channel row = skip) and `backfillClubDefaults(limit)` (≤500 per run, per-club isolation).
- `ChannelPlatformMapper` (+XML): `selectOpenClubsWithoutChannels`.
- `task/ClubChannelDefaultsTask` (`clubChannelDefaultsTask.backfill(200)`).
- `ApiClubController.create`: seeds defaults for a club created open (best effort, logged). `ClubController.audit`: seeds on approval.
- `ApiClubController.detail` + `Club` + `PublicClubVO`: `memberAvatars` (owner first, up to 5, http(s) only). Owner and active members always; others only when `chengyin.club.hero-avatars-public=true` (default false). Field added, nothing removed.
- `ClubMemberMapper.selectHeroAvatars`, `IClubMemberService.selectHeroAvatars` (default method), impl.
- `sql/migration_20261115_club-reach.sql`: registers the backfill job **paused**; idempotent; rollback note at top. No schema change.
- Tests: new `ClubChannelDefaultsTest` (4 tests); `ChannelsPlatformMySqlIT` expectation 1 → 3 channels.

## Verification
- `LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -DskipTests compile`: OK.
- `mvn -B -q -pl chengyinhub-admin -am test -Dtest=ClubChannelDefaultsTest,ChannelPlatformServiceTest`: all pass.
- `mvn ... -Dtest='Channel*Test,Club*Test,ApiClub*Test,PublicClub*Test'`: 1582 tests, 0 failures, 3 errors, all `CommunicationsException` from MyBatis/MySQL ITs that need a live database (not available here).

## Not verified
MySQL ITs (including the updated `ChannelsPlatformMySqlIT`), the migration on a real database, the Quartz invocation.

## Owner actions / questions
- After deploy, run job 俱乐部默认频道补齐 once from 定时任务 (it stays paused).
- Decide `chengyin.club.hero-avatars-public` (member photos for non-members).
- 公告 has no server-side send restriction yet (see app receipt).
- Not in this base: nothing from `feature/extgame-money`, `feature/game-types`, `feature/ingame-channels`, `feature/sys-weather` was needed.
