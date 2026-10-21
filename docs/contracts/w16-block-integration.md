# W16.4 屏蔽即时终止（候选，默认关闭）

本人既有屏蔽写进权威 `im_block` 后，同一条同意 guard 事务停止双方当前共同接头。解除只删这一方向的屏蔽行，不把已停止的 match 改回活跃。本文不是生产接通说明。`ImServiceImpl.block` 未接。

## 入口

`RendezvousBlockIntegration.block` 在 `MyBatisRendezvousConsentStore` 的 guard 事务里：

1. 用既有 `ImBlockMapper.insertImBlock` 写权威屏蔽。重复键仍走原来的 `on duplicate key update`，不新建屏蔽表。
2. `selectOne` 与双向 `existsBetween` 读回失败则抛 `W16_BLOCK_NOT_PERSISTED`，本事务回滚。
3. 仅当双方当前活跃票据指向同一个仍活跃的 match 时，调用终止端口。
4. 端口返回后，该 match 必须已是 `SAFETY_STOPPED`，且双方票据不再活跃。否则抛 `W16_MATCH_NOT_TERMINATED`，屏蔽写入一起回滚。

没有共同活跃 match 时不改任何票据。等待池里的单方票据、以及任一方和第三人的 match，都不停。

`unblock` 只执行 `deleteImBlock`。它不调用终止端口，不写票据，不写 match。

## 终止端口（A 未接）

本树没有 A 冻结的 `verifyParticipants`，也没有 A 的终止方法。端口是：

```java
void terminateCurrentMatch(RendezvousConsentService.Transaction tx, long actor, long other);
```

调用时 guard 事务已经打开。实现不得再调用 `Store.transaction`，不得解散 team，不得删聊天，不得把终态 match 改回活跃。没有共同活跃 match 时直接返回。有则在本事务把该 match 写成 `SAFETY_STOPPED` 并释放双方占位。损坏的预约或写入失败要抛错。

默认绑定 `RendezvousBlockIntegration.unwired()` 抛 `W16_MATCH_TERMINATION_UNWIRED`，屏蔽与 match 都不提交。`guardStop()` 只是包内既有 `stopBlockedPair` 的过渡，给隔离适配器用，不是上述签名。

A 已冻结、本树尚不可调用的核对是：

```java
OngoingVerdict verifyParticipants(RendezvousConsentService.Transaction tx, String ticketId);
```

H 需要能在同一事务里看出该票据的 match 是否仍在进行，以及 match id。字段未落地前不猜测。

## 撤回事实

B、C 读已有 match 状态，不读新表。`SAFETY_STOPPED` 之后：接受不再变成双方同意；角色线索 `SECRET_UNAVAILABLE`；挑战和交接由会面刷新拒绝，且不调用组队。屏蔽事务本身不改线索行、会面行或 team。

## 未接

- `ImServiceImpl.block` 仍直接 `insertImBlock`。最小 hunk 只交给 dot，本线不应用、不注册 Bean、不替换全局 IM Service。
- `ImServiceImpl.unblock` 本就只删屏蔽行。match 已是 `SAFETY_STOPPED` 时，这条旧实现也不会把旧 match 复活。本线不改该方法。
- 生产终止实现未接。合成替身只出现在 `RendezvousBlockIntegrationTest`。
