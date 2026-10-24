# C04-backend 交付报告

Issue: https://github.com/liboyang42-cpu/chengyin-remote-ops/issues/5  
Lane: C04 多俱乐部、赛事与真正的普通城市定向（后端独占）  
Repo: `github.com/liboyang42-cpu/hengyin`  
Base: `2ec68899099a1e250df0949a8383bf85b5d3ffe0` (`origin/master`)

## 1. 签收与库存

已读、未改（非缺口）：

| 文件 | 结论 |
|---|---|
| `OrienteeringParticipation.java` | 未成年场次事实记录，不是赛事权威 |
| `TopicCityOrienteeringClubGateTest.java` | 发布「不绑俱乐部」门禁，不是 TEAM 报名权威 |
| `TopicRouteSession.java` / Mapper / Scope | 12/35 生命周期，只读 |

远端 `service/orienteering/**` 在 base SHA 上不存在。`OrientSessionServiceImpl` 对 `game:` 实例抛 `ORIENT_TEAM_UNSUPPORTED`。现有 `IOrientScoringService.decorateLeaderboard` 是成长/主题榜装饰，不能替代赛事版本成绩。

## 2. 本车道写入（白名单）

生产：

- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/orienteering/PublishedRuleSelector.java`
- `.../TeamClubEligibilityAuthority.java`
- `.../OrdinaryCityTimezoneQualifier.java`
- `.../EventScoreVersioning.java`
- `.../EventResultDispute.java`
- `.../OrienteeringEventException.java`（包内错误码）

测试：`chengyinhub-admin/src/test/java/com/chengyinhub/business/service/orienteering/*`

未写：公共 Controller / Mapper / migration / TopicRouteSession 写路径 / 小程序 / 旧 chengyin。

## 3. 首个可跑旅程

普通已发布城市定向（`productType=1`，上架，`publishedConfigVersion=7`，草稿 `configVersion=9`）  
→ TEAM 三成员，资格来自真实俱乐部权威（主理人无成员行 + 成员 status=1 + 第二俱乐部）  
→ 城市时区 Asia/Shanghai 逐项安全确认  
→ start 冻结规则版本 7  
→ SCAN/PHOTO/QUIZ 证据（拒绝 GPS）+ 有符号计分  
→ finish 写入 version=1；`scoreCenti` 不是 `originalRewardCenti`（缺原奖励来源时奖励为 0）  
→ `readback(fin-journey1)` 返回同一条结果；未知键 `EVENT_NOT_FOUND`，不新造请求  
→ 争议恢复指向原完赛 requestId 与封存的 `originalRewardCenti`，不把成绩当奖励  
→ `persistOriginal` 交给既有 `IOrientScoringService.appendEntry`；缺 `TopicRouteSession` 为 `WAITING_SOURCE`  
→ 新实例 `recoverFromLedger` 走 `OrientScoringMapper.selectEntryByIdem`，进程 Map 不是持久恢复

## 4. 明确未做

- 未接公共 Controller / 未改 session writer（hunk 交 dot）
- 未跑生产 DDL、支付、发奖、真实 GPS
- 未恢复随机匹配
- 未用成长个人榜充当赛事榜
- 原奖励 writer 仍 WAITING_SOURCE（`eventScore.originalRewardSource`）；finish 不再把 scoreCenti 抄进 originalRewardCenti
- TopicRouteSession 仍由 Runtime/dot 供数；缺会话时 persist/recover 为 WAITING_SOURCE
- 真实 MySQL / Boot 启动 / HTTP 为 NOT_RUN（本车道只做隔离单测；mapper mock 不是 DB）

## 5. 验证（已跑）

命令：

```
mvn -pl chengyinhub-admin -am -Dtest=com.chengyinhub.business.service.orienteering.*Test -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：`BUILD SUCCESS`。chengyinhub-admin 隔离单测 **29 run, 0 fail, 0 error, 0 skip**（2026-10-24T03:23:04Z）。

| 测试类 | run |
|---|---|
| OrdinaryCityTeamEventJourneyTest | 1 |
| EventScoreVersioningTest | 8 |
| PublishedRuleSelectorTest | 3 |
| TeamClubEligibilityAuthorityTest | 6 |
| EventResultDisputeTest | 3 |
| OrdinaryCityTimezoneQualifierTest | 3 |
| C04AccountSwitchRestartIsolationTest | 5 |

静态检查/编译：通过（test-compile + 上述 surefire）。DB/HTTP/App/CI：NOT_RUN。

Head（本车道）：`a6dd47b7923699f5190786186cd509c727ac66bc`（报告本次提交会再前进一格）。

## 6. 文件 SHA256（提交前快照）

| SHA256 | bytes | path |
|---|---|---|
| b20c62935a54aef6be24ef19478933296c87dc120793ef42a39f45e46332b263 | 2189 | OrienteeringEventException.java |
| ac2502818e0d264934f03f1e6cd75efe2bb547bd45668b683b712a96b6238ff8 | 5023 | PublishedRuleSelector.java |
| 30544c404e0bf6ff7626eca0806583558c54e52217e9cf7d5e85c208c6df1d2e | 6501 | OrdinaryCityTimezoneQualifier.java |
| 78921f6b32b4df8b8fa0bbedcb74e11cb1afd9acfb152c12ba37c5348c5442de | 9333 | TeamClubEligibilityAuthority.java |
| fab552aa09d16086fffbd33be181e574f713f6ffc35fa1bdcafc0d34118af728 | 17341 | EventScoreVersioning.java |
| 62abc03dab0b4b6ec097c93b65ee56e8164552664db7a3f517323c548c09b5c2 | 6941 | EventResultDispute.java |

## 7. Addendum 2026-10-23 15:45 America/Los_Angeles

HEAD `5b89fb6505189e6a26ccf2e808f10445199d44f0`

`EventScoreVersioning.recoverUnknown` / `TeamClubEligibilityAuthority.recoverUnknown` / `EventResultDispute.recoverUnknown`：

- 缺 / 空 requestId → typed `NOT_FOUND`，`originalRequestId` 保持 Java null，`mintedNewRequest=false`，不 throw、不占账
- 从未见过的 `fin-unknow` / `reg-unknow` / `dsp-unknow` → `NOT_FOUND` 且不写入 ledger
- 之后对该 id 的 finish / register / open 仍是唯一原文写入
- 既有 `readback(null)` 仍 throw `EVENT_NOT_FOUND`

命令：

```
mvn -pl chengyinhub-admin -am -Dtest=com.chengyinhub.business.service.orienteering.*Test -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：`BUILD SUCCESS`。chengyinhub-admin 隔离单测 **19 run, 0 fail, 0 error, 0 skip**（2026-10-23T22:45:39Z）。`C04AccountSwitchRestartIsolationTest` 4/0。

生产 SHA256（本 HEAD 字节）：

| SHA256 | bytes | path |
|---|---|---|
| 91ea62936018ffcb0d7759e998bf7235c05626dd6cf79e9664fad84d34fe05b0 | 11091 | TeamClubEligibilityAuthority.java |
| 5fce0bc309e37a430a48b18a64245c9d3930ff4be0eb6c62fa38024adf56e0d4 | 19116 | EventScoreVersioning.java |
| e2e873d98c1da74d9047d7b925818c917c2e8ec7a3879da491ffec105d19e2a5 | 9078 | EventResultDispute.java |

Runtime / TopicRouteSession / 公共 Controller 仍未接线。App / XCTest / MySQL / HTTP / CI = NOT_RUN。不声称 100%。

## 7. Addendum 2026-10-23 16:12 America/Los_Angeles — recoverOriginal isolation

HEAD `516f59977`（`test(c04): recoverOriginal 未见 id 不占账再唯一写入 [skip ci]`）。生产 `recoverOriginal` 仍 alias `recoverUnknown`，SHA256 与 `eb95ffb50` 相同。

`C04AccountSwitchRestartIsolationTest.recoverOriginalOfNeverSeenIdDoesNotOccupyLedgerThenUniqueWriteApplies`：

- 缺 requestId `recoverOriginal(null)` → typed `NOT_FOUND`，`originalRequestId` 保持 Java null，不占账
- 从未见过的 `fin-orig-unk` / `reg-orig-unk` / `dsp-orig-unk` → `NOT_FOUND`，`readback` 仍 throw，不写入 ledger
- 之后对该 id 的 finish / register / open 仍是唯一原文写入；`recoverOriginal` 再读到 `FINISHED` / `REGISTERED` / `PENDING`

命令：

```
mvn -pl chengyinhub-admin -am -Dtest=com.chengyinhub.business.service.orienteering.*Test -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：`BUILD SUCCESS`。chengyinhub-admin 隔离单测 **20 run, 0 fail, 0 error, 0 skip**（2026-10-23T23:12:25Z）。`C04AccountSwitchRestartIsolationTest` 5/0。

Runtime / TopicRouteSession / 公共 Controller 仍未接线。App / XCTest / MySQL / HTTP / CI = NOT_RUN。不声称 100%。

## 8. Addendum 2026-10-23 17:51 America/Los_Angeles — class-level recoverOriginal occupancy

HEAD `49f672fb3`（`test(c04): recoverOriginal 未见完赛/报名/争议 id 不占账再唯一写入 [skip ci]`）。未发明 recoverOriginal。生产 SHA256 与 `eb95ffb50` 相同。

- `EventScoreVersioningTest.recoverOriginalOfNeverSeenIdDoesNotOccupyThenUniqueFinishApplies`：未见 `c04-fin-never` 保持 `NOT_FOUND` 且 `readback` 仍 throw；后续唯一 finish 才是 FINISHED。
- `TeamClubEligibilityAuthorityTest.recoverOriginalOfNeverSeenIdDoesNotOccupyThenUniqueRegisterApplies`：未见 `c04-reg-never` 不占账；后续唯一 register 才是 REGISTERED。
- `EventResultDisputeTest.recoverOriginalOfNeverSeenIdDoesNotOccupyThenUniqueOpenApplies`：未见 `c04-dsp-never` 不占账；后续唯一 open 才是 PENDING。

命令：

```
mvn -pl chengyinhub-admin -am -Dtest=com.chengyinhub.business.service.orienteering.*Test -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：`BUILD SUCCESS`。chengyinhub-admin 隔离单测 **23 run, 0 fail, 0 error, 0 skip**（2026-10-24T00:51:06Z）。EventScoreVersioningTest 4/0。TeamClubEligibilityAuthorityTest 5/0。EventResultDisputeTest 2/0。Isolation 5/0。

Runtime / TopicRouteSession / 公共 Controller 仍未接线。App / XCTest / MySQL / HTTP / CI = NOT_RUN。不声称 100%。

## 9. Addendum 2026-10-23 18:21 America/Los_Angeles — class-level start occupancy

HEAD `fe1b266fc`（`test(c04): recoverOriginal 未见 start id 不占账再唯一开局 [skip ci]`）。未发明 recoverOriginal。`recoverOriginal` 仍只回读完赛账，开局占用看 `runs.startRequestId`。

- `EventScoreVersioningTest.recoverOriginalOfNeverSeenStartIdDoesNotOccupyThenUniqueStartApplies`：未见 `c04-sta-never` 保持 `NOT_FOUND` 且 `readback` 仍 throw；后续唯一 `start` 才写入该开局键，replay 同 run id。

命令：

```
mvn -pl chengyinhub-admin -am -Dtest=com.chengyinhub.business.service.orienteering.*Test -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：`BUILD SUCCESS`。chengyinhub-admin 隔离单测 **24 run, 0 fail, 0 error, 0 skip**（2026-10-24T01:21:14Z）。EventScoreVersioningTest 5/0。Isolation 5/0。

Runtime / TopicRouteSession / 公共 Controller 仍未接线。App / XCTest / MySQL / HTTP / CI = NOT_RUN。不声称 100%。

## 10. Addendum 2026-10-23 19:01 America/Los_Angeles — unused pending/ineligible/restore/GPS occupancy

HEAD `8a99d1c1c`（`test(c04): 待审完赛/不合格报名/缺失争议恢复/GPS 证据观察不占账再唯一写入 [skip ci]`）。未发明 recoverOriginal。未发明 iOS `dispute()`。OrdinaryCityTeamEventJourney 只是 composer，不另造账。

- `EventScoreVersioningTest.unusedPendingFinishDoesNotOccupyThenUniqueFinishApplies`：待审 `finish(c04-fin-pend)` 抛 `PENDING_REVIEW` 且 `recoverOriginal`/`readback` 仍 `NOT_FOUND`；清 PENDING 后同一键唯一 finish 才是 FINISHED。
- `EventScoreVersioningTest.unusedGpsEvidenceDoesNotOccupyThenUniqueScanApplies`：GPS 证据抛 `GPS_FORBIDDEN` 不占 evidence；同一键唯一 SCAN 才是原文。
- `TeamClubEligibilityAuthorityTest.unusedIneligibleRegisterDoesNotOccupyThenUniqueRegisterApplies`：局外人 `register(c04-reg-inelig)` 抛 `TEAM_INELIGIBLE` 不占账；合格 roster 同一键唯一 register 才是 REGISTERED。
- `EventResultDisputeTest.unusedRestoreOfMissingDisputeDoesNotOccupyThenUniqueRestoreApplies`：缺失争议上的 `restoreOriginalReward(c04-rst-never)` 抛 `NOT_FOUND` 不占恢复账；open 后再唯一 restore 才是原文。

命令：

```
mvn -pl chengyinhub-admin -am -Dtest=com.chengyinhub.business.service.orienteering.*Test -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：`BUILD SUCCESS`。chengyinhub-admin 隔离单测 **28 run, 0 fail, 0 error, 0 skip**（2026-10-24T02:01:15Z）。EventScoreVersioningTest 7/0。TeamClubEligibilityAuthorityTest 6/0。EventResultDisputeTest 3/0。Isolation 5/0。

Runtime / TopicRouteSession / 公共 Controller 仍未接线。App / XCTest / MySQL / HTTP / CI = NOT_RUN。不声称 100%。
