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
- 线性化边界：在这条守卫事务开始之前已经提交的撤销，排序在派发之前，禁止 `create` 和 `join`。外部组队调用不在守卫事务里。事务返回之后才提交的撤销，仍可能和这次调用交错；之后拒绝 `COMPLETED` 不能把这次调用说成已回滚。

## 证据

到场只认 `w16_meeting_evidence` 里 `fact_source = AUTHORITY_FACT` 且规则版本非空、地点和双方匹配的行。确认口令、扫码、双击和坐标不写这张表，也不能变成 `READY`。

## A 的事务内检查

本组端口仍是 `RendezvousHandoffSafety.verifyCurrent`。适配器在调用方已经打开的同一个 `Transaction` 上调用 A 在 `37b4b345` 冻结的

```text
verifyParticipants(tx, actor, ticketId, matchId, peer, true, now)
```

`OngoingVerdict` 不在这棵树上，适配器只接收 `safe`、`owned`、`mutual` 三个显式布尔。三者都为 true 才通过，缺一、`null` 或异常都失败关闭。`requireMutualAccepted` 固定为 true。派发前对双方各查一次。事务内 terminate 本组不调用。

## 组队端口

`IPlayTeamService.create` / `join` 仍没有请求号和权威结果查询。`RendezvousHandoffLegacyTeamPort` 的 `lookup` 固定为未知，`create` / `join` 直接拒绝，不写队伍表。完整接通等跨模块改动获准之后。
