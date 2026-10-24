# C08-backend → D5/dot 最小 hunk

Issue：https://github.com/liboyang42-cpu/chengyin-remote-ops/issues/5 领取组 **C08-be**  
仓库：`github.com/liboyang42-cpu/hengyin`（Java 21 / Boot 4.1.1）  
Base：`origin/master` `2ec68899099a1e250df0949a8383bf85b5d3ffe0`  
分支：`cursor/c08-channel-governance-e504`

C08-be 未改下列禁止文件。公共 IM send/receipt、journal、NativeWebhook*、P38 ACCOUNT4 writer、P04 CAS writer 请由 D5/dot 按本页接线；**Do not edit those files from this PR.**

本仓 **不是** 100%。v12 DOCX、232 pack、unpushed backend SHA `e16c2c5` 均 **WAITING_SOURCE**。禁止猜代码重写那些原文。DB / HTTP / App / CI = **NOT_RUN**。隔离单测不是 App 闭环。

Inventoried, not rewritten:

- public IM send / send-receipt protocol
- original IM journal
- NativeWebhook*
- TopicRouteRuntimeServiceImpl / RouteEffects / CmsTopicServiceImpl
- JWT / RBAC

iOS exclusive already on chengyin-ios#64 (`013a717c`). This package is the matching isolation producer.

## 1. 白名单（实际写入）

- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/channel/ChannelAuthorizationException.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/channel/ChannelGovernanceTypes.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/channel/ChannelRulesAdmission.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/channel/ChannelMemberAuthorizationProjection.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/channel/ChannelRevokeIsolation.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/channel/FriendPermissionDistinctFromChannel.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/channel/ChannelGovernanceProjection.java`
- matching `chengyinhub-admin` isolation tests
- this file

## 2. D5/dot 接线（本 PR 不改）

把 `ChannelGovernanceProjection.consentToPublishedRules` / `authorizeReadWrite` / `admit` 接到既有 P04 CAS 与 P38 ACCOUNT4 成员授权出口。公共 send/receipt 仍走原 writer。频道 grant 不得写入好友边。

## 3. 首个隔离旅程

规则同意 publishedVersion=3 → MEMBER 读写授权 → 频道 grant 不能 START 好友会话 / 不能当备注或私聊 → leave 后旧 pageLease 写请求 `ISOLATED_LATE_REQUEST`；历史投票只读 ALLOWED、写票 DENIED。

## Addendum 2026-10-23 16:48 America/Los_Angeles

HEAD `7e77514e9`。cataloged / consent / invite / member-role 观察不得占用 `grant()`；随后唯一 `authorizeReadWrite` 才是第一次写入。Did not invent `recoverOriginal`。

命令：`mvn -pl chengyinhub-admin -am test -Dtest=com.chengyinhub.business.service.channel.*Test -Dsurefire.failIfNoSpecifiedTests=false`

结果：`BUILD SUCCESS` **14/0** 2026-10-23T23:48:32Z。`C08AccountSwitchRestartIsolationTest` 4/0。IM send/receipt still not owned. App / XCTest / MySQL / HTTP / CI = NOT_RUN。不声称 100%。

## Addendum 2026-10-23 18:02 America/Los_Angeles

HEAD `828057afe` tests. Class-level occupancy on `ChannelGovernanceProjectionTest`: cataloged / consent / invite / member-role observation does not occupy `grant()`; later unique `authorizeReadWrite` is the first write. Did not invent `recoverOriginal`. Isolation occupancy remains.

Parent surefire `com.chengyinhub.business.service.channel.*Test` **15/0** 2026-10-24T01:02:41Z：

```
Tests run: 3 -- ChannelGovernanceProjectionTest
Tests run: 4 -- C08AccountSwitchRestartIsolationTest
Tests run: 2 -- ChannelRevokeIsolationTest
Tests run: 4 -- C08FirstJourneyIsolationTest
Tests run: 2 -- FriendPermissionDistinctFromChannelTest
Tests run: 15, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

IM send/receipt still not owned. App / XCTest / MySQL / HTTP / CI = NOT_RUN。不是 100%。

## Addendum 2026-10-23 18:43 America/Los_Angeles

HEAD `b2f6c58d8` tests. Class-level occupancy on `ChannelRevokeIsolationTest`: unused in-membership `HISTORICAL_VOTE_READ` does not occupy leave (phase stays `AUTHORIZED_READ_WRITE`, grant still present); unique `leave()` is the first LEFT write. Post-leave historical vote read of the old lease stays ALLOWED. Did not invent `recoverOriginal`.

Parent surefire `com.chengyinhub.business.service.channel.*Test` **16/0** 2026-10-24T01:43:23Z：

```
Tests run: 3 -- ChannelGovernanceProjectionTest
Tests run: 4 -- C08AccountSwitchRestartIsolationTest
Tests run: 3 -- ChannelRevokeIsolationTest
Tests run: 4 -- C08FirstJourneyIsolationTest
Tests run: 2 -- FriendPermissionDistinctFromChannelTest
Tests run: 16, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

IM send/receipt still not owned. App / XCTest / MySQL / HTTP / CI = NOT_RUN。不是 100%。

## Addendum 2026-10-23 18:46 America/Los_Angeles

HEAD `5aa879a6e` tests. Class-level occupancy on `ChannelGovernanceProjectionTest`: unused in-membership `appeal()` throws REVOKED without occupying ban (phase stays `AUTHORIZED_READ_WRITE`, grant still present); unique `ban()` is the first BANNED write; later `appeal()` is APPEALING. Did not invent `recoverOriginal`. Leave occupancy remains.

Parent surefire `com.chengyinhub.business.service.channel.*Test` **17/0** 2026-10-24T01:46:38Z：

```
Tests run: 4 -- ChannelGovernanceProjectionTest
Tests run: 4 -- C08AccountSwitchRestartIsolationTest
Tests run: 3 -- ChannelRevokeIsolationTest
Tests run: 4 -- C08FirstJourneyIsolationTest
Tests run: 2 -- FriendPermissionDistinctFromChannelTest
Tests run: 17, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

IM send/receipt still not owned. App / XCTest / MySQL / HTTP / CI = NOT_RUN。不是 100%。

## Addendum 2026-10-23 18:50 America/Los_Angeles

HEAD `50f88a638` tests. Class-level occupancy on `ChannelGovernanceProjectionTest`: unused cataloged `leave()` throws ROLE_REQUIRED without occupying LEFT (phase stays CATALOGED, grant null); unique `leave()` after authorize is the first LEFT write. Did not invent `recoverOriginal`. Vote-read and appeal occupancy remain.

Parent surefire `com.chengyinhub.business.service.channel.*Test` **18/0** 2026-10-24T01:50:20Z：

```
Tests run: 5 -- ChannelGovernanceProjectionTest
Tests run: 4 -- C08AccountSwitchRestartIsolationTest
Tests run: 3 -- ChannelRevokeIsolationTest
Tests run: 4 -- C08FirstJourneyIsolationTest
Tests run: 2 -- FriendPermissionDistinctFromChannelTest
Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

IM send/receipt still not owned. App / XCTest / MySQL / HTTP / CI = NOT_RUN。不是 100%。

## Addendum 2026-10-23 18:52 America/Los_Angeles

HEAD `8ae553788` tests. Class-level occupancy on `ChannelGovernanceProjectionTest`: unused cataloged `submitInviteOrApply()` throws RULES_CONSENT_REQUIRED without occupying applicant (role stays NONE, phase CATALOGED); unique submit after consent is the first APPLICANT write. Did not invent `recoverOriginal`. Leave / vote-read / appeal occupancy remain.

Parent surefire `com.chengyinhub.business.service.channel.*Test` **19/0** 2026-10-24T01:52:24Z：

```
Tests run: 6 -- ChannelGovernanceProjectionTest
Tests run: 4 -- C08AccountSwitchRestartIsolationTest
Tests run: 3 -- ChannelRevokeIsolationTest
Tests run: 4 -- C08FirstJourneyIsolationTest
Tests run: 2 -- FriendPermissionDistinctFromChannelTest
Tests run: 19, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

IM send/receipt still not owned. App / XCTest / MySQL / HTTP / CI = NOT_RUN。不是 100%。

## Addendum 2026-10-23 18:54 America/Los_Angeles

HEAD `33472e804` tests. Class-level occupancy on `ChannelGovernanceProjectionTest`: unused cataloged `assignMemberRole(MEMBER)` throws ROLE_REQUIRED without occupying member (role stays NONE, phase CATALOGED); unique assign after applicant is the first MEMBER write. Did not invent `recoverOriginal`. Invite / leave / vote-read / appeal occupancy remain.

Parent surefire `com.chengyinhub.business.service.channel.*Test` **20/0** 2026-10-24T01:54:22Z：

```
Tests run: 7 -- ChannelGovernanceProjectionTest
Tests run: 4 -- C08AccountSwitchRestartIsolationTest
Tests run: 3 -- ChannelRevokeIsolationTest
Tests run: 4 -- C08FirstJourneyIsolationTest
Tests run: 2 -- FriendPermissionDistinctFromChannelTest
Tests run: 20, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

IM send/receipt still not owned. App / XCTest / MySQL / HTTP / CI = NOT_RUN。不是 100%。

## Addendum 2026-10-23 20:40 America/Los_Angeles

生产接线：`ChannelGovernanceProjection.authorizeFromMembership` 只读既有 `IImService.isActiveGroupMember`（`ImServiceChannelMembership`）。缺端口 WAITING_SOURCE `im.isActiveGroupMember`。非成员 NOT_MEMBER，不签发 grant。不把 ACCOUNT4 accountId 猜成 IM memberId。公共 send/receipt/journal 仍归 D5。

Did not invent `recoverOriginal`. App / XCTest / MySQL / HTTP / CI = NOT_RUN。不是 100%。
