# C03-backend · 原奖励接受账与触发者投影

Issue：https://github.com/liboyang42-cpu/chengyin-remote-ops/issues/5 领取组 **C03**  
仓库：`github.com/liboyang42-cpu/hengyin` 仅写本仓  
Base：`origin/master` `2ec68899099a1e250df0949a8383bf85b5d3ffe0`  
分支：`cursor/c03-reward-accept-ledger-e504`  
与 #576 六机制 consumer 同包不同文件，不覆写 RouteEffects / Runtime。

## 1. 白名单

- `RewardAcceptLedger.java` `76f37774b143b51e2c00a9593d601f7617dd171d4d17dfa7f88cc749fef78864`
- `TriggerActorProjection.java` `1bac6b87e2a3da0db243bd948fbb67c7bebd6e1459e9d89dfc26e7f3917165fc`
- 测试：`RewardAcceptLedgerTest` / `TriggerActorProjectionTest` / `C03RewardAcceptJourneyIsolationTest` / `C03AccountSwitchRestartIsolationTest`

未改：`TopicRouteRuntimeServiceImpl`、`RouteEffects`、钱包、骰子、公共 Controller / Mapper。无生产 DDL。
不要对未见过的 requestId 做 `recover` / `recoverOriginal` / `markUnknown` 时 `put` 进账本；未见过只回 UNKNOWN 且 `existing==null`。`recoverOriginal` 对齐只读原 requestId。

## 2. 首个隔离旅程

显式 trigger actor → 冻结 grant 的原 requestId 接受（`wroteWallet=false`）→ 同 id 再接受为 REPLAY、无二次发放 → UNKNOWN 不得清掉已接受意图；缺 id 保持 null。

```
mvn -pl chengyinhub-admin -am test \
  -Dtest=com.chengyinhub.business.service.support.rpg.mechanism.*Test \
  -Dsurefire.failIfNoSpecifiedTests=false
→ Tests run: 13, Failures: 0, BUILD SUCCESS 2026-10-23T23:06:15Z
```

Head：`9520f2001`。2026-10-23 16:06 America/Los_Angeles。App / MySQL / CI = NOT_RUN。不是 100%。阵营/领地/秘密目标经济值未编造。

## 4. Parent addendum 2026-10-23 18:32 America/Los_Angeles

Class-level `RewardAcceptLedgerTest.recoverOriginalOfNeverSeenIdDoesNotOccupyThenUniqueAcceptApplies`：未见 `c03-rew-class-never` 的 `recoverOriginal` 保持 UNKNOWN / `existing==null`；后续唯一 `accept` 才是第一笔 APPLIED。`TriggerActorProjection` 无 requestId 账本，不编 `recoverOriginal`。

父进程独立 surefire（workspace `cursor/c03-reward-accept-ledger-e504`，HEAD `ef2bcb4e9`，2026-10-24T01:32:39Z）：

```
Tests run: 5 -- C03AccountSwitchRestartIsolationTest
Tests run: 5 -- RewardAcceptLedgerTest
Tests run: 1 -- C03RewardAcceptJourneyIsolationTest
Tests run: 3 -- TriggerActorProjectionTest
Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

App / HTTP / MySQL / CI = NOT_RUN。不是 100%。

## 3. Needed hook（do not apply here）

Runtime 一次效果后问 `RewardAcceptLedger.accept`；默认触发走 `TriggerActorProjection`，`defaultFired` 必须拒绝。
