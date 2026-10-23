# C03-backend · 原奖励接受账与触发者投影

Issue：https://github.com/liboyang42-cpu/chengyin-remote-ops/issues/5 领取组 **C03**  
仓库：`github.com/liboyang42-cpu/hengyin` 仅写本仓  
Base：`origin/master` `2ec68899099a1e250df0949a8383bf85b5d3ffe0`  
分支：`cursor/c03-reward-accept-ledger-e504`  
与 #576 六机制 consumer 同包不同文件，不覆写 RouteEffects / Runtime。

## 1. 白名单

- `RewardAcceptLedger.java` `304e8e70b584bcfad9a63119ca5538eb68126720e5fd19023f6620918b17ff59`
- `TriggerActorProjection.java` `1bac6b87e2a3da0db243bd948fbb67c7bebd6e1459e9d89dfc26e7f3917165fc`
- 测试：`RewardAcceptLedgerTest` / `TriggerActorProjectionTest` / `C03RewardAcceptJourneyIsolationTest`

未改：`TopicRouteRuntimeServiceImpl`、`RouteEffects`、钱包、骰子、公共 Controller / Mapper。无生产 DDL。

## 2. 首个隔离旅程

显式 trigger actor → 冻结 grant 的原 requestId 接受（`wroteWallet=false`）→ 同 id 再接受为 REPLAY、无二次发放 → UNKNOWN 不得清掉已接受意图；缺 id 保持 null。

```
mvn -pl chengyinhub-admin -am test \
  -Dtest=com.chengyinhub.business.service.support.rpg.mechanism.RewardAcceptLedgerTest,com.chengyinhub.business.service.support.rpg.mechanism.TriggerActorProjectionTest,com.chengyinhub.business.service.support.rpg.mechanism.C03RewardAcceptJourneyIsolationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
→ Tests run: 8, Failures: 0, BUILD SUCCESS
```

2026-10-23 13:33 America/Los_Angeles。App / MySQL / CI = NOT_RUN。不是 100%。阵营/领地/秘密目标经济值未编造。

## 3. Needed hook（do not apply here）

Runtime 一次效果后问 `RewardAcceptLedger.accept`；默认触发走 `TriggerActorProjection`，`defaultFired` 必须拒绝。
