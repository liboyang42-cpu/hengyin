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

## Addendum 2026-10-23 18:00 America/Los_Angeles

HEAD `90808494c` tests. Class-level occupancy without inventing `recoverOriginal` on Navigation:

- `NavigationDestinationConsentTest`: unused start does not occupy nav grant; unique nav grant does not occupy share; unique `LOCATION_SHARE` grant is the first share write. Restarted instance stays ungranted.
- `SideQuestAcceptHoldTest`: `recoverOriginal` of never-seen `sq-class-unk` throws `ORIGINAL_RECORD_UNKNOWN` without occupying `original`/`hold`; later unique `acceptHold` then complete is the first original. Isolation occupancy on `sq-orig-unk` remains.

Parent surefire `com.chengyinhub.business.service.offline.*Test` **9/0** 2026-10-24T01:00:24Z：

```
Tests run: 1 -- SideQuestAcceptHoldTest
Tests run: 4 -- C05AccountSwitchRestartIsolationTest
Tests run: 2 -- C05FirstJourneyIsolationTest
Tests run: 2 -- NavigationDestinationConsentTest
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

App / XCTest / MySQL / HTTP / CI = NOT_RUN。不是 100%。

## Addendum 2026-10-23 18:07 America/Los_Angeles

HEAD `edf3882ff` tests. Class-level `OfflinePackOperationTest`: unused `resume` of never-seen `op-class-never` throws `NOT_PAUSED` without occupying `operationId`; later unique `startDownload("op-class")` is the first write. Wrong resume does not rewrite the original id. Did not invent `recoverOriginal` on OfflinePack.

Parent surefire `com.chengyinhub.business.service.offline.*Test` **10/0** 2026-10-24T01:07:49Z：

```
Tests run: 1 -- OfflinePackOperationTest
Tests run: 1 -- SideQuestAcceptHoldTest
Tests run: 4 -- C05AccountSwitchRestartIsolationTest
Tests run: 2 -- C05FirstJourneyIsolationTest
Tests run: 2 -- NavigationDestinationConsentTest
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

App / XCTest / MySQL / HTTP / CI = NOT_RUN。不是 100%。

## Addendum 2026-10-23 19:05 America/Los_Angeles

HEAD `69f6c982b` tests. Did not invent recoverOriginal.

- `SideQuestAcceptHoldTest.unusedAcceptHoldBeforeDiscoverDoesNotOccupyThenUniqueHoldApplies`：IDLE `acceptHold` 抛 `NOT_COMPLIANT`，`hold`/`original` 仍 null；后续唯一 discover+acceptHold 才是 HOLD_ACCEPTED。
- `SideQuestAcceptHoldTest.unusedCompleteBeforeHoldDoesNotOccupyThenUniqueCompleteApplies`：DISCOVERED `complete` 抛 `HOLD_REQUIRED`，`original` 仍 null；后续唯一 acceptHold+proof+complete 才是 COMPLETED 原文。

Parent surefire `com.chengyinhub.business.service.offline.*Test` **12/0** 2026-10-24T02:05:05Z：

```
Tests run: 1 -- OfflinePackOperationTest
Tests run: 3 -- SideQuestAcceptHoldTest
Tests run: 4 -- C05AccountSwitchRestartIsolationTest
Tests run: 2 -- C05FirstJourneyIsolationTest
Tests run: 2 -- NavigationDestinationConsentTest
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

App / XCTest / MySQL / HTTP / CI = NOT_RUN。不是 100%。

## Addendum 2026-10-23 19:36 America/Los_Angeles

HEAD `f0b38b667` tests. Did not invent recoverOriginal.

`SideQuestAcceptHoldTest.unusedSubmitProofBeforeHoldDoesNotOccupyThenUniqueSubmitApplies`：DISCOVERED `submitProof` 抛 `HOLD_REQUIRED` 且 phase 仍 DISCOVERED、hold/original 仍 null；后续唯一 acceptHold+submitProof 才是 PROOF_PENDING。

Parent surefire `com.chengyinhub.business.service.offline.*Test` **13/0** 2026-10-24T02:36:24Z：

```
Tests run: 1 -- OfflinePackOperationTest
Tests run: 4 -- SideQuestAcceptHoldTest
Tests run: 4 -- C05AccountSwitchRestartIsolationTest
Tests run: 2 -- C05FirstJourneyIsolationTest
Tests run: 2 -- NavigationDestinationConsentTest
Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

App / XCTest / MySQL / HTTP / CI = NOT_RUN。不是 100%。

## Addendum 2026-10-23 19:39 America/Los_Angeles

HEAD `046661647` tests. Did not invent recoverOriginal.

`SideQuestAcceptHoldTest.unusedExitBeforeHoldDoesNotOccupyThenUniqueExitApplies`：DISCOVERED `exit` 抛 `HOLD_REQUIRED` 且 original 仍 null；后续唯一 acceptHold+exit 才是 EXITED 原文（无需 proof）。

Parent surefire `com.chengyinhub.business.service.offline.*Test` **14/0** 2026-10-24T02:39:08Z：

```
Tests run: 1 -- OfflinePackOperationTest
Tests run: 5 -- SideQuestAcceptHoldTest
Tests run: 4 -- C05AccountSwitchRestartIsolationTest
Tests run: 2 -- C05FirstJourneyIsolationTest
Tests run: 2 -- NavigationDestinationConsentTest
Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

App / XCTest / MySQL / HTTP / CI = NOT_RUN。不是 100%。

## Addendum 2026-10-23 19:41 America/Los_Angeles

HEAD `e7c48238e` tests. Did not invent recoverOriginal.

`OfflinePackOperationTest.unusedDeleteOfIdleDoesNotOccupyThenUniqueStartThenDeleteApplies`：IDLE `delete` 抛 `INVALID_MANIFEST` 且 phase 仍 IDLE、operationId 仍空；后续唯一 startDownload+delete 才是 DELETED。

Parent surefire `com.chengyinhub.business.service.offline.*Test` **15/0** 2026-10-24T02:41:32Z：

```
Tests run: 2 -- OfflinePackOperationTest
Tests run: 5 -- SideQuestAcceptHoldTest
Tests run: 4 -- C05AccountSwitchRestartIsolationTest
Tests run: 2 -- C05FirstJourneyIsolationTest
Tests run: 2 -- NavigationDestinationConsentTest
Tests run: 15, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

App / XCTest / MySQL / HTTP / CI = NOT_RUN。不是 100%。

## Addendum 2026-10-23 19:46 America/Los_Angeles

HEAD `1f02763b7` tests. Did not invent recoverOriginal.

`OfflinePackOperationTest.unusedExpireOfIdleDoesNotOccupyThenUniqueStartThenExpireApplies`：IDLE `expire` 抛 `INVALID_MANIFEST` 且 phase 仍 IDLE、operationId 仍空；后续唯一 startDownload+expire 才是 EXPIRED（DOWNLOADING 允许 expire，即使 now 早于 expiresAt）。与 unused delete 的 DELETED 占账不同。

Parent surefire `com.chengyinhub.business.service.offline.*Test` **16/0** 2026-10-24T02:46:17Z：

```
Tests run: 3 -- OfflinePackOperationTest
Tests run: 5 -- SideQuestAcceptHoldTest
Tests run: 4 -- C05AccountSwitchRestartIsolationTest
Tests run: 2 -- C05FirstJourneyIsolationTest
Tests run: 2 -- NavigationDestinationConsentTest
Tests run: 16, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

App / XCTest / MySQL / HTTP / CI = NOT_RUN。不是 100%。

## Addendum 2026-10-23 19:48 America/Los_Angeles

HEAD `23b7215b5` tests. Did not invent recoverOriginal.

`OfflinePackOperationTest.unusedRecordCorruptOfIdleDoesNotOccupyThenUniqueStartThenCorruptApplies`：IDLE `recordCorrupt` 抛 `INVALID_MANIFEST` 且 phase 仍 IDLE、operationId 仍空；后续唯一 startDownload+recordCorrupt 才是 CORRUPT（抛 CORRUPT 发生在占账之后）。与 unused expire 的 EXPIRED、unused delete 的 DELETED 占账不同。

Parent surefire `com.chengyinhub.business.service.offline.*Test` **17/0** 2026-10-24T02:48:50Z：

```
Tests run: 4 -- OfflinePackOperationTest
Tests run: 5 -- SideQuestAcceptHoldTest
Tests run: 4 -- C05AccountSwitchRestartIsolationTest
Tests run: 2 -- C05FirstJourneyIsolationTest
Tests run: 2 -- NavigationDestinationConsentTest
Tests run: 17, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

App / XCTest / MySQL / HTTP / CI = NOT_RUN。不是 100%。

## Addendum 2026-10-23 19:50 America/Los_Angeles

HEAD `8d9aa8a09` tests. Did not invent recoverOriginal.

`OfflinePackOperationTest.unusedRecordFullDiskOfIdleDoesNotOccupyThenUniqueStartThenFullDiskApplies`：IDLE `recordFullDisk` 抛 `NOT_DOWNLOADING` 且 phase 仍 IDLE、operationId 仍空；后续唯一 startDownload+recordFullDisk 才是 FULL_DISK（抛 FULL_DISK 发生在占账之后）。与 unused recordCorrupt 的 CORRUPT 占账不同。

Parent surefire `com.chengyinhub.business.service.offline.*Test` **18/0** 2026-10-24T02:50:37Z：

```
Tests run: 5 -- OfflinePackOperationTest
Tests run: 5 -- SideQuestAcceptHoldTest
Tests run: 4 -- C05AccountSwitchRestartIsolationTest
Tests run: 2 -- C05FirstJourneyIsolationTest
Tests run: 2 -- NavigationDestinationConsentTest
Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

App / XCTest / MySQL / HTTP / CI = NOT_RUN。不是 100%。

## Addendum 2026-10-23 19:54 America/Los_Angeles

HEAD `5e0f04e7a` tests. Did not invent recoverOriginal.

`OfflinePackOperationTest.unusedApplyUpdateOfIdleDoesNotOccupyThenUniqueStartFinishThenUpdateApplies`：IDLE `applyUpdate` 抛 `NOT_READY` 且 phase 仍 IDLE、operationId 仍空；后续唯一 startDownload+finishDownload+applyUpdate 才是 UPDATING。与 unused recordFullDisk 的 FULL_DISK 占账不同。

Parent surefire `com.chengyinhub.business.service.offline.*Test` **19/0** 2026-10-24T02:54:48Z：

```
Tests run: 6 -- OfflinePackOperationTest
Tests run: 5 -- SideQuestAcceptHoldTest
Tests run: 4 -- C05AccountSwitchRestartIsolationTest
Tests run: 2 -- C05FirstJourneyIsolationTest
Tests run: 2 -- NavigationDestinationConsentTest
Tests run: 19, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

App / XCTest / MySQL / HTTP / CI = NOT_RUN。不是 100%。

## Addendum 2026-10-23 19:57 America/Los_Angeles

HEAD `c6c4c398c` tests. Did not invent recoverOriginal.

`OfflinePackOperationTest.unusedFinishUpdateOfIdleDoesNotOccupyThenUniqueUpdateThenFinishApplies`：IDLE `finishUpdate` 抛 `NOT_DOWNLOADING` 且 phase 仍 IDLE、operationId 仍空；后续唯一 startDownload+finishDownload+applyUpdate+finishUpdate 才是 v2 READY。与 unused resume 的 v1 READY、unused applyUpdate 的 UPDATING 占账不同。

Parent surefire `com.chengyinhub.business.service.offline.*Test` **20/0** 2026-10-24T02:57:24Z：

```
Tests run: 7 -- OfflinePackOperationTest
Tests run: 5 -- SideQuestAcceptHoldTest
Tests run: 4 -- C05AccountSwitchRestartIsolationTest
Tests run: 2 -- C05FirstJourneyIsolationTest
Tests run: 2 -- NavigationDestinationConsentTest
Tests run: 20, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

App / XCTest / MySQL / HTTP / CI = NOT_RUN。不是 100%。

## Addendum 2026-10-23 20:13 America/Los_Angeles

HEAD `03df83ec9`. Dot Issue #5 C05 comment 2026-10-24T03:05:41Z.

`expire(now)` now requires `now >= expiresAt`; DOWNLOADING/READY/PAUSED no longer expire early. No-arg `finishDownload`/`finishUpdate` throw WAITING_SOURCE `offlinePack.payloadConsumer` without occupying READY; unique `finishDownload(byte[])` SHA-256+byteSize matches then READY. `Proof.create(APPROVED_ALTERNATIVE)` requires attested plus approvalAuthority. `acceptHold` without `PlaceCapacitySource` is WAITING_SOURCE `sideQuest.placeCapacitySource` without occupying hold.

Parent surefire `com.chengyinhub.business.service.offline.*Test` **21/0** 2026-10-24T03:13:26Z：

```
Tests run: 7 -- OfflinePackOperationTest
Tests run: 6 -- SideQuestAcceptHoldTest
Tests run: 4 -- C05AccountSwitchRestartIsolationTest
Tests run: 2 -- C05FirstJourneyIsolationTest
Tests run: 2 -- NavigationDestinationConsentTest
Tests run: 21, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Still missing original download/storage consumer implementation (typed WAITING_SOURCE, not invented HTTP). PlaceCapacitySource is a port; MemoryPlaceCapacity is tests only, not registration_entitlement. App / XCTest / MySQL / HTTP / CI = NOT_RUN。不是 100%。
