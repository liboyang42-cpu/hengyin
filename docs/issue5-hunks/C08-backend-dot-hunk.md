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
