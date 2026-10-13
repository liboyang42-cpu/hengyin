# R9-01 候补 offer token 精度修复（冻结候选）

基线 `0de03bd17`；工作区 `repair-waitlist-token-0913`，分支 `codex/repair-waitlist-token-0913`。只改一个产品方法、增加真实MySQL回归及本文。未commit/push/PR/部署；未操作现有业务库、9763、生产或其他任务工作区。待总控独立两轴审查。

## 问题与最小修复

原报告第九轮R9-01：WAITING晋级OFFERED已占库存，但本人状态返回409“候补 token 状态不一致”。原诊断 `/tmp/r9-01-waitlist-probe/run.log` 证实：Java `offerExpiresAt.getTime()` 毫秒参与HMAC，而正式列为 `DATETIME(0)`。MySQL8.0.45对`.123`保存为整秒、`.789`进位到下一秒，数据库读回事实与签名前事实不同。

`ClubWaitlistServiceImpl.offerDeadline` 仍先按原规则选取 `min(now + offerMinutes, signupDeadline或startTime)`；只在返回前把选定值向下对齐到完整秒，保证后续签名及 `offerWaitingCas` 保存的是同一个可无损持久化的Date。没有单独改token算法，旧HMAC、hash、归属、版本CAS、锁序、库存逻辑全部保留。

业务时限不改：合法5..1440分钟，未配置默认1440分钟，票报名截止优先、缺失时回退开场。秒精度最多提前不足1秒，绝不进位延长；若对齐后已无未来可表示截止，原 `deadline.after(now)` 闸拒绝晋级，不占库存。

## RED → GREEN

先新增真实MySQL测试再改产品：

- `/tmp/r9-01-waitlist-probe/repair-red.log`：7项中4红、3通过。`.123`读回状态409；`.789`回读截止向上进位；并发晋级后的正常读取409；默认一天同样被进位。其余正常整秒领取、权限/篡改拒绝、截止和超时回补正控通过。
- `/tmp/r9-01-waitlist-probe/repair-green.log`：修改方法后42/42，0失败、0错误、0跳过。

|测试组|数量|
|---|---:|
|ClubWaitlistTokenMybatisIntegrationTest（MySQL8.0.45）|7|
|ClubWaitlistServiceImplTest|24|
|ClubWaitlistQuoteWiringTest|2|
|ClubWaitlistRegistrationWiringTest|2|
|ClubWaitlistPromotionMembershipRaceTransactionTest|2|
|ClubWaitlistMaintenanceIsolationTransactionTest|1|
|ClubWaitlistClaimMembershipRaceTransactionTest|4|

复跑（仅本机127.0.0.1:3317；版本断言8.0.45）：

```sh
CHENGYIN_FUNDS_MYSQL_IT=1 mvn -q -pl chengyinhub-admin -am \
  -Dtest=ClubWaitlistTokenMybatisIntegrationTest,ClubWaitlistServiceImplTest,ClubWaitlistQuoteWiringTest,ClubWaitlistRegistrationWiringTest,ClubWaitlistPromotionMembershipRaceTransactionTest,ClubWaitlistMaintenanceIsolationTransactionTest,ClubWaitlistClaimMembershipRaceTransactionTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

MySQL7项覆盖`.123/.789`晋级→真实持久化→本人状态取token→正常claim；整秒正控；篡改token、他人、错活动、错票种、重复claim拒绝；报名截止/缺失时开场上限；截止相等拒绝claim与晋级；默认窗口及非法时限拒绝；两个并发晋级仅一次占库存且token可读；两个并发过期与重复过期仅一次返还库存，截止前1ms不释放。

## MySQL证据边界与清理

每个测试建立新UUID schema，CREATE不带IF NOT EXISTS，只有本次CREATE成功后才允许cleanup。waitlist采用正式migration DDL；票表采用base_schema，并按现有migration_20260817_tandianri_r6_product_contracts.sql补当前Mapper要求的source_ticket_id（只在新scratch）。真实Service、Codec、Spring事务代理、waitlist与ticket MyBatis SQL，库存与token均落真实库；独立JdbcTemplate在服务事务结束后回读状态/库存，不拿发送线程未提交值证明提交。

外部系列配置、俱乐部成员资格与治理校验使用合成替身，未声明完整鉴权E2E；原成员竞争/维护隔离等事务回归另行保留。每个scratch在AfterEach删除，并用新的查询回读information_schema剩余0，日志包含全部schema与`READBACK=0`。测试未调用微信或真实报名付款。`git diff --check`通过。

## 存量OFFERED错hash：本修复不会自动修复

此补丁只影响以后生成的offer，既有hash仍由无法恢复的原毫秒生成；不能猜原毫秒、重算原token或把未来offer成功说成存量已恢复。现有过期流程仍会按原截止释放名额，但等待过期不等于玩家已恢复领取。

可恢复的最小后续方案（**仅方案，未实现、未执行**）：

1. 在受控服务维护动作中限定明确的候补ID清单，按现有series→club→ticket→waitlist锁序重新读取；仅处理仍OFFERED、无registrationId、未到原持久化截止、scope有效的记录。重新读取资格；资格不符沿既有治理/过期合同处理，不因修复放权。
2. 以**已持久化的准确截止**重新生成一个可重建token，比较hash，仅对不一致行通过`id + state + version + oldHash + expiresAt`条件更新hash并增加version。保持原截止、offeredAt、会员/活动/票种及现有库存hold原值；不续期、不再次占库存、不创建新offer。
3. 事务提交后由独立读取重新确认status可返回token，再由本人走原quote/claim校验。失败回滚/条件更新0行应跳过并复核，不能强覆盖CLAIMED、EXPIRED或新版本。

这实质是**作废旧token并重签当前有效offer**，不是恢复原签名；可能使并行读到的旧版本失效、用户需刷新。锁竞争会短暂阻塞同票种操作；过程中到期的行应交给原过期路径。既已EXPIRED的用户如要补发、延长机会或重入队，将涉及排队公平/库存和时限产品决定，不能自动执行。启用存量维护需总控另行确定目标与操作授权；本次未新增恢复入口、自动写回、重试框架或扩大权限。

## 文件

- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/ClubWaitlistServiceImpl.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ClubWaitlistTokenMybatisIntegrationTest.java`
- `docs/repair-waitlist-token-0913.md`

冻结哈希 `/tmp/r9-01-waitlist-probe/frozen-sha256.json`。真实HTTP/UI、部署及存量修复未验证，不能总关闭R9-01的上线验收。

## 总控集成补验

源提交 f8793f4b1 已集成本地 root 9bef2ab4a；双轴独立审查均0项。root 重新运行上列七类测试42/42，0失败、0错误、0跳过，含真实MySQL8.0.45七项。日志 `/tmp/cy-r901-integrated.log`，逐类结果 `/tmp/cy-r901-integrated-summary.json`。存量修复、真实HTTP/UI及部署边界保持上述结论。
