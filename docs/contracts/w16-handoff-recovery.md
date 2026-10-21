# W16 交接恢复（候选，默认关闭）

本组只描述会面交接的持久请求和恢复查询。不改同意、角色、`IPlayTeamService` 或队伍表。没有批准的证据事实时不能进入 `READY`。本文不是生产接通说明。

## 只读查询

```text
RendezvousMeetingService.HandoffState handoffStateFor(String matchId)
```

返回且仅返回：

| 值 | 含义 |
| --- | --- |
| `NOT_STARTED` | 这个 match 还没有交接请求，会面也没停在已发起未分类的状态 |
| `UNKNOWN` | 请求已落库但创建结果不能确认，或只有旧的 `IN_PROGRESS` / `RECOVERY_REQUIRED` 声明 |
| `CREATED_JOIN_UNCONFIRMED` | 权威查询或已确认的创建给出了 team，加入尚未确认 |
| `COMPLETE` | 创建和加入都已确认并允许公布 |

`handoffStateFor` 和 `GET .../handoff` 不调用 team 端口的 `lookup`、`create`、`join`。空白 `matchId` 返回 `NOT_STARTED`。

## 恢复

- 一个 match 只有一个 `request_id`。超时、丢响应和崩溃都不新造请求。
- 恢复先 `lookup(requestId)`。查到原 team 就沿用，不再 `create`。
- `lookup` 不能证明缺席、或调用已经发出但结果未落库时，保持 `UNKNOWN`，不删队、不补偿、不再创建。
- 创建已确认而加入失败时，只对同一请求 `join`。
- 恢复过程中取消、拉黑或到期会停下后续组队。已记下的 team id 保留，阻断码以 `NOT_ROLLED_BACK` 结尾。
- 每次 `create` 或 `join` 之前，在同一条守卫事务里重读原请求、双方当前安全、截止时间、证据行和停止标记，并按原 `request_id` 原子决定。`markDispatched` 只在 `dispatched=0`、阶段 `UNKNOWN`、且阻断码不是 `NOT_ROLLED_BACK` 时成功。
- 守卫事务在 `permit` 里重读原请求、双方安全、截止时间、证据和停止标记。这只排序 `permit` 返回之前已经提交的撤销。`permit` 返回之后到外部 `create` / `join` 之间的窗口是真的，不再加一道检查把它说成已经关上。

## 派发与取消

交接变成已派发的时刻，是创建用的 `markDispatched` 提交成功：同一条守卫事务里重读通过，并且 `dispatched` 从 0 改成 1 的那次更新提交。不是 `permit` 开始，不是外部 `create` 进入，也不是 team id 落库。`markDispatched` 只在 `dispatched=0`、阶段 `UNKNOWN`、且阻断码不是 `NOT_ROLLED_BACK` 或 `CANCELLED_PREVENTED` 时成功。加入没有第二枚持久派发位；创建一旦已派发，取消答案就已经是不保证回滚。

`cancel` 的返回用现有的 `state` 和 `blocker` 区分，不新增对外字段：

| 返回 | 含义 |
| --- | --- |
| `state=CANCELLED`，没有请求行，`blocker` 为空 | 还没有交接请求。派发被阻止。 |
| `state=CANCELLED`，`blocker=CANCELLED_PREVENTED` | 请求已有，`markDispatched` 尚未提交。外部创建未被放行。 |
| `state=RECOVERY_REQUIRED`，`blocker=CANCELLED_NOT_ROLLED_BACK` | `markDispatched` 已经提交。回滚不保证。已有 team id 留在账本上，响应不公布 team。 |

`permit` 已经返回之后才执行的取消，不能阻止随后的外部 `create` 或 `join`。调用发生了就记下结果，并保持 `NOT_ROLLED_BACK`。没有请求行、但会面已是 `IN_PROGRESS` 的旧声明，取消仍停在 `RECOVERY_REQUIRED`，不写成已阻止。

上面这段说的是默认关闭的外部端口：`permit` 先提交 `markDispatched`，真实队伍写在事务外面。真实 PlayTeam 接通走另一条路，见 `docs/contracts/w16-play-team-handoff.md`。那条路默认不装配。它把守卫锁、安全复核、原请求核对和真实 `create` 或 `join` 放进同一事务，不再先提交 `markDispatched` 再写队伍。加入若要单独的持久派发位，需要给 `w16_handoff_request` 加列；本轮不加。

## 证据

到场只认 `w16_meeting_evidence` 里 `fact_source = AUTHORITY_FACT` 且规则版本非空、地点和双方匹配的行。确认口令、扫码、双击和坐标不写这张表，也不能变成 `READY`。

## A 的事务内检查

本组端口仍是 `RendezvousHandoffSafety.verifyCurrent`。适配器在调用方已经打开的同一个 `Transaction` 上调用 A 在 `37b4b345` 冻结的

```text
verifyParticipants(tx, actor, ticketId, matchId, peer, true, now)
```

`OngoingVerdict` 不在这棵树上，适配器只接收 `safe`、`owned`、`mutual` 三个显式布尔。三者都为 true 才通过，缺一、`null` 或异常都失败关闭。`requireMutualAccepted` 固定为 true。派发前对双方各查一次。事务内 terminate 本组不调用。

## 组队端口

`RendezvousHandoffLegacyTeamPort` 仍是默认端口：`lookup` 固定为未知，`create` / `join` 直接拒绝，不写队伍表。真实请求索引在 `IPlayTeamService` 的 handoff 入口上，默认不装配，合同见 `docs/contracts/w16-play-team-handoff.md`。
