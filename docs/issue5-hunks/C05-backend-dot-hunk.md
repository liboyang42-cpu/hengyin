# C05-backend → D3/dot 最小 hunk

Issue：https://github.com/liboyang42-cpu/chengyin-remote-ops/issues/5 领取组 **C05-be**  
仓库：`github.com/liboyang42-cpu/hengyin`  
Base：`origin/master` `2ec68899099a1e250df0949a8383bf85b5d3ffe0`  
分支：`cursor/c05-offline-pack-e504`

未改 PlayExperience 原会话、PlayNode、20 个原暂停操作、TopicRouteRuntime*、公共 Controller/Mapper。**Do not edit those files from this PR.**

不是 100%。qmod final ops / vol12 resume writer / T41 scoring writer **WAITING_SOURCE**（present=false）。DB / HTTP / App / CI = **NOT_RUN**。

iOS exclusive already on chengyin-ios#67. This is the matching isolation producer.

## 白名单

- `.../service/offline/OfflinePackOperation.java`
- `.../service/offline/NavigationDestinationConsent.java`
- `.../service/offline/PresenceProofDistinctFromFog.java`
- `.../service/offline/SideQuestAcceptHold.java`
- matching isolation tests
- this file

## D3/dot 接线

离线包 operationId 接到既有 resume writer（源未到则保持 WAITING_SOURCE）。导航同意不得并入位置分享。迷雾不得写入到场证明。

## Addendum 2026-10-23 16:22 America/Los_Angeles

HEAD `6a82b4cae`：`discover` 在已有 original 时抛 `ALREADY_TERMINAL`，不再抹掉原记录。`recoverOriginal` 未见 id 仍抛 `ORIGINAL_RECORD_UNKNOWN`，`original`/`hold` 保持 null，不占账；之后对该 id 的唯一 `acceptHold` 才是原文写入。

`SideQuestAcceptHold.java` SHA256 `45c3792d5571174d26c1456054324a78000efac48a1271fd0c0f49f8300fac13`。

命令：`mvn -pl chengyinhub-admin -am -Dtest=com.chengyinhub.business.service.offline.*Test -Dsurefire.failIfNoSpecifiedTests=false test`

结果：`BUILD SUCCESS` **7/0** 2026-10-23T23:22:52Z。`C05AccountSwitchRestartIsolationTest` 4/0。App / XCTest / MySQL / HTTP / CI = NOT_RUN。不声称 100%。
