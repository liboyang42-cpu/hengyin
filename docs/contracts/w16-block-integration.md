# W16.4 屏蔽即时终止（候选，默认关闭）

本人既有屏蔽写进权威 `im_block` 后，同一条同意 guard 事务停止双方当前共同接头。解除只删这一方向的屏蔽行，不把已停止的 match 改回活跃。本文不是生产接通说明。没有显式注入已接通的入口时，`ImServiceImpl.block` 仍走原来的插入。

## 入口

`RendezvousBlockIntegration.block` 在 `MyBatisRendezvousConsentStore` 的 guard 事务里：

1. 用既有 `ImBlockMapper.insertImBlock` 写权威屏蔽。重复键仍走原来的 `on duplicate key update`，不新建屏蔽表。
2. `selectOne` 与双向 `existsBetween` 读回失败则抛 `W16_BLOCK_NOT_PERSISTED`，本事务回滚。
3. 仅当双方当前活跃票据指向同一个仍活跃的 match 时，调用终止端口。
4. 端口返回后，该 match 必须已是 `SAFETY_STOPPED`，且双方票据不再活跃。否则抛 `W16_MATCH_NOT_TERMINATED`，屏蔽写入一起回滚。

没有共同活跃 match 时不改任何票据。等待池里的单方票据、以及任一方和第三人的 match，都不停。

`unblock` 只执行 `deleteImBlock`。它不调用终止端口，不写票据，不写 match。

## 终止端口

`RendezvousBlockIntegration.consentStop(consent)` 把端口接到 A 的实例方法：

```java
public void terminateCurrentMatch(RendezvousConsentService.Transaction tx, long actor, long other);
```

调用时 guard 事务已经由 H 打开。该方法把共同活跃 match 写成 `SAFETY_STOPPED`，释放双方预约，并且第二次调用不再写。双方 id 不合法时 A 抛 `AUTH_REQUIRED` 或 `INVALID_INPUT`。预约对不上时，A 在任何 save 之前抛 `CORRUPT_RESERVATION`，H 的屏蔽插入一起回滚。

H 在进入事务前拒绝 null、非正数和自己屏蔽自己，错误码 `W16_INVALID_BLOCK`。

## 三参构造走 guardStop，不走 unwired

`new RendezvousImBlockMutation(mode, session, manager)` 只做：

```text
new RendezvousBlockIntegration(mode, session, manager, RendezvousBlockIntegration.guardStop())
```

`guardStop()` 调用包内 `RendezvousConsentService.stopBlockedPair`。它不选择 `unwired()`，也不调用 `consent.terminateCurrentMatch`。要接上 A 的实例方法，调用方必须自己持有 `RendezvousConsentService` 并传入 `consentStop(consent)`。

`unwired()` 仍只在显式传入时抛 `W16_MATCH_TERMINATION_UNWIRED`，屏蔽与 match 都不提交。

`verifyParticipants(Transaction, String)` 由 A 提供。H 的屏蔽路径不调用它。

## 撤回事实

B、C 读已有 match 状态，不读新表。`SAFETY_STOPPED` 之后：接受不再变成双方同意；角色线索 `SECRET_UNAVAILABLE`；挑战和交接由会面刷新拒绝，且不调用组队。屏蔽事务本身不改线索行、会面行或 team。

## 真实 block 入口

`ImServiceImpl.block` 在双方 id 合法后：

- `rendezvousBlock == null`，或注入的 `RendezvousBlockEntry.connected()` 为 false：仍调用原来的 `blockMapper.insertImBlock`。不停止 match，也不报 W16 已接通。
- 显式注入且 `connected()` 为 true：只调用 `RendezvousBlockEntry.block`。这一路径用构造时传入的同一个 `SqlSessionTemplate` 和 `DataSourceTransactionManager`，在 consent guard 里写权威 `im_block` 并 `consentStop` → `terminateCurrentMatch`。不再次调用 `blockMapper.insertImBlock`。

`ImServiceImpl.setRendezvousBlock` 是 `@Autowired(required = false)`。容器里没有 Bean 时不会调用，字段保持 null，不会空指针。`open` 只有隔离评估模式且 consent 非空才接通，并且只绑定 `consentStop`，不使用 `guardStop` 或三参 mutation。`DISABLED`、consent 为空都保持断开。

`unblock`、`report`、聊天和队伍语义没有改。

## 组合根默认关闭

`RendezvousBlockComposition` 是唯一的入口 `@Bean`。属性 `chengyinhub.w16.block-termination` 写在该类上，默认 `false`，不写入 `application*.yml`。属性不是 `true` 时不注册 Bean，`ImServiceImpl.block` 仍只插入一次权威 `im_block`，返回 null，接口响应仍是「已拉黑」。

属性为 `true` 且同一个 `DataSource`、`SqlSessionTemplate`、`DataSourceTransactionManager` 一致时，`formal` 用该会话上的 `ImBlockMapper` 和 `MyBatisRendezvousConsentStore` 装配唯一入口。终止调用 A 的 `terminateCurrent(..., Termination.BLOCK, now)`。同一事务写屏蔽、终止、读回双方当前联系。`ApiImController.block` 用 `getAppUserId()` 作为 actor，响应仍是「已拉黑」，data 带读回。登录主体的 app user 与传入 actor 不一致时拒绝，不写行。

A 的 `Mode` 枚举仍没有单独的生产成员。store 的 `transaction()` 只在 `ISOLATED_PERSISTENCE_EVALUATION` 下写。`formal` 使用这个已有可写模式，不把 `evaluation()` 注册成 Bean，也不使用内存替身。新配对的 Admission 固定抛 `POLICY_CLOSED`，这条装配不记账、不放行新配对。

依赖缺失抛 `W16_BLOCK_DEPENDENCY_MISSING`（容器缺 Bean 时由 Spring 拒绝创建）。数据源或事务工厂不一致抛 `W16_BLOCK_RESOURCE_MISMATCH`。会话缺少拉黑或同意 mapper 抛 `W16_BLOCK_MAPPER_MISSING`。传入的 mapper 不属于该会话抛 `W16_BLOCK_MAPPER_MISMATCH`。

`evaluation` 只给隔离本地试验，不是 Spring Bean，不是正式装配。

## 仍未接通的生产开关

- 默认没有 `RendezvousBlockEntry` Bean，也没有改全局安全配置或动态数据源。生产默认仍是原来的屏蔽插入。
- 三参 `RendezvousImBlockMutation` 仍走 `guardStop` → `stopBlockedPair`。入口测试用 A 的四参 `Admission` 构造，不走三参免记账。
- 合成替身只覆盖未接线端口和空终止的回滚。真实入口的回滚、重复、反向、无关配对和解除不复活走 `ImServiceImpl.block`。
