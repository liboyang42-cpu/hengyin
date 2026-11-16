# W7 x-channel-reach — backend receipt (hengyin)

Branch `feature/r-x-channel-reach` from `origin/roadmap/be-integ` (`cbbf7e3d0`). Review source: `review/opus-w7` →
`docs/review/opus/social/ISSUES.md` (SOC-2, SOC-9, SOC-13). App side: chengyin-app `cloud/r-x-channel-reach`.

## Issues

| ID | State | Notes |
|---|---|---|
| SOC-2 | Fixed (backend + app) | Enrolment on join / channel create / overwrite change, leave cleanup, paused backfill job, per-channel default level (公告 and 活动 = all), event cards ping like @everyone, paged push audience, directory `mention_count`. |
| SOC-9 | Fixed (backend + app) | Channel pushes: club/team name as title, `#channel sender：text`, 【公告】/[Announcement], 新活动 / New event with China time; page carries `guildKind`/`guildId`. |
| SOC-13 | Fixed | One set-based unread UPDATE per group/channel/in-game message; the member list is loaded only for 1:1. |

All cited `path:line` were re-checked against `cbbf7e3d0`; none was already fixed. Adaptations are listed below.

## Changes, file by file

SOC-13
- `mapper/ImConversationMemberMapper.java` + `mapper/business/ImConversationMemberMapper.xml`: `addUnreadForActiveMembersExcept(conversationId, exceptMemberId, delta)` (same join on `im_conversation.status = 0`, `deleted = 0`).
- `service/impl/ImServiceImpl.send`: groups and channels call the bulk statement; `selectByConversation` runs only for 1:1 (receiver lookup).
- `service/impl/InGameChannelService.postSystemMessage`: same bulk call, actor excluded (0 when there is no actor).

SOC-2
- New `service/channel/platform/ChannelMembershipSync.java`:
  - `enrollMember(kind, guild, member)`: live text/announcement channels the member can VIEW (live permission calculator), `INSERT IGNORE` in one statement, each row read up to the channel's latest message (history does not show as unread, mentions count from now).
  - `enrollChannel(row)`: guild members in keyset pages of 500; a channel without overwrites skips the per-member check; private channels check each member.
  - `memberLeft(...)`: deletes the member's rows in that guild's channels, inside the leave transaction, never throws.
  - Join/channel hooks run **after commit** (channel enrolment on the `threadPoolTaskExecutor` when present) and only log failures.
  - `backfill(pageSize)`: walks every live channel by id keyset; a failing channel is logged and skipped.
- New `task/ChannelMembershipBackfillTask.java` (bean `channelMembershipBackfillTask`). No `@Scheduled`.
- `ChannelPlatformMapper.java/.xml`: `selectLatestMessageId`, `insertIgnoreMembers`, `insertIgnoreMemberChannels`, `deleteGuildMemberships`, `selectLiveChannelsAfter`, `selectMentionUnread`, `updateNotifyDefault`.
- `ChannelPlatformService`: channel create (incl. provisioning) and `putOverwrite`/`deleteOverwrite` call `channelChangedAfterCommit`; kind 2 channels and the provisioned 活动 channel get `notify_default = 0` (failure tolerated, logged); `directory(with_unread=1)` adds `mention_count` (unread @me, @everyone, @my role, replies to me, after my last read, not my own).
- Call sites: `ClubMemberServiceImpl.joinMember`, `requestJoin` (direct join), `reviewJoinRequest(approve)`; `PlayTeamServiceImpl.join`, `handle(approved)`; `ImServiceImpl.leaveClubGroup` / `leaveTeamGroup` (covers quit, governance removal, team quit and kick).
- `push/PushPolicy`: level order is conversation → account → **channel default** → club default → 0 (old signatures kept).
- `push/PushFanoutService.fanOutChat` + `PushMapper.java/.xml`:
  - `selectChatMessage` also returns `extraJson`; new `selectChannelPushContext` (channel name, kind, guild, guild name, `notify_default`) and `countPostedEventCard`.
  - A channel message whose `extra_json.cardType = channel_event` **and** whose id is a posted `channel_event.message_id` counts as @everyone (a member cannot fake a card); `suppressBroadcast`, mutes and quiet hours still apply; no "[有人@你]" prefix on cards.
  - Audience read in keyset pages of 500 (`member_id > after`), up to 200 pages, replacing the single `LIMIT 2000`.
  - If the channel context read fails (e.g. column not migrated yet), the push falls back to the old club lookup and wording.

SOC-9 (same `fanOutChat`)
- Title = club or team name; body zh `#闲聊 小李：…` / en `#闲聊 小李: …`; kind 2 prefixes 【公告】/`[Announcement] `; event card → zh `新活动：{title} · M月d日 HH:mm` / en `New event: {title} · MMM d, HH:mm` (Asia/Shanghai). Preview off keeps the generic 群消息 / 你收到一条新消息. In-game channels and groups keep their wording.
- `page` gains `&guildKind=<k>&guildId=<id>` for channels (additive; the mini-program ignores unknown keys).

Migrations (all idempotent, rollback notes at the top, ledger entries in `ci/migration-dependencies.json`)
- `migration_20261116_channel_notify_default.sql`: guarded `ALTER TABLE im_channel ADD COLUMN notify_default TINYINT NULL`; sets 0 for kind 2 and for the provisioned 活动 channel (op-log `CREATE`/`provision`). Pre-deploy.
- `migration_20261116_channel_membership_backfill.sql`: paused sys_job `channelMembershipBackfillTask.backfill(200)`. Post-deploy (`POST_DEPLOY_MIGRATIONS` in `ci/migration_ledger.py`).
- `migration_20261116_stagger_channel_membership_job.sql`: `-- plan:` second 54 (`54 20 4 * * ?`). Post-deploy.

## Adaptations from the review

- Leaving a guild **deletes** the member's channel rows instead of `deleted = 1`: with `INSERT IGNORE` enrolment a `deleted = 1` row would keep a re-joining member silent. Rows are only an index; pushes and reads re-check membership live.
- `notify_default` is read through the new push context query, not added to `channelColumns`, so a not-yet-migrated column cannot break channel reads.
- The event-card @everyone rule also checks `channel_event.message_id` (anti-spoofing; not in the review).
- `restore` does not re-enrol: rows are never removed when a channel is archived or deleted, so a restored channel keeps them. Role grants (a role gaining VIEW on a private channel) enrol on the member's first open, as before.

## Verification (Java 21, `LANG=C.UTF-8 LC_ALL=C.UTF-8`)

- `mvn -B -q -DskipTests install` → OK.
- `mvn -B -q -pl chengyinhub-admin -am test -Dtest=…` → all green:
  ChannelMembershipSyncTest 8 (new), PushFanoutServiceTest 18 (+8), PushPolicyTest 8 (+1), ChannelPlatformServiceTest 14 (+3), ImServiceImplTest 55 (+2), InGameChannelServiceTest 4, PlayTeamServiceImplTest 26, ClubLifecycleServiceTest 36, ClubAdminCapTest 9, ClubAdminGovernanceGuardTest 8, ClubGovernanceJoinIntegrationTest 2, ClubGovernanceParticipationWiringTest 2, ClubMemberEffectiveCountTest 4, ClubMemberImCleanupTransactionTest 1, ChannelEventServiceTest 23, ChannelEventMapperH2Test 4, ChannelEventDraftTest 5, ChannelEventMaybeReminderTest 2, MemberPrivacyErasureServiceTest 4, PlayerMessagePunctuationTest 2, SysJobStaggerPlanTest 3, TwoNodeSchedulingTest 4, E2eTestProvidersProfileTest 4.
- `bash ci/migration-safety-check.sh` → 迁移安全门禁:通过. `bash ci/migration-layout-check.sh` → 迁移布局门禁:通过. No new `rollback_*.sql`.
- `python3 -m pytest -q ci/test_migration_ledger.py` → 58 passed.
- `python3 -m pytest -q ci` (whole folder): the same 8 failures and 13 collection errors as the base branch (git-history/MySQL-only tests: `architecture/test_gate`, `test_funds_it_gate`, `test_http_e2e_entry`, …); checked by running them on a clean `roadmap/be-integ` worktree. No new failures.

Not run: the env-gated MySQL ITs for the new SQL (`INSERT IGNORE … VALUES`, `INSERT IGNORE … SELECT`, multi-table `DELETE … JOIN`, the mention count query). They use plain MySQL 8 syntax; verify on TEST.

## Owner / ops actions

1. Run `migration_20261116_channel_notify_default.sql` before the jar, the other two after it (ledger already says so).
2. After the new jar is healthy, run 频道成员补齐 (`channelMembershipBackfillTask.backfill(200)`) once from 系统监控 → 定时任务; leave it paused.
3. TEST check: member B joins a club; owner posts in 公告 → B gets a push titled with the club name ("【公告】#公告 …") without ever opening 公告; B's channel list shows "1 条未读"; posting an event pushes "新活动：…" to B.
4. Owner decision applied (from the review): 公告 and 活动 default to "all messages", event cards ping like @everyone. Members can still mute each channel.

## Overlap with the UI agent

- `fix/registration-list-r3` exists. It shares `ImServiceImpl.java`, `ClubMemberServiceImpl.java` and `ImServiceImplTest.java` with this branch (its commit `fdf15c0ff`, acceptance B1–B8). None of our hunks touch the stop-answer/lease path, club chat screen or merchant dashboard, and `git merge-tree` shows no conflict in our files, so nothing was cherry-picked.

## Related, not fixed here

- Push `canRead` re-checks channel access per recipient (several queries each); with large enrolled audiences this is the next cost after SOC-13. A batched visibility check would help.
- `channel_event.message_id` has no index; the card check runs only for messages whose `extra_json` already says `channel_event`.
