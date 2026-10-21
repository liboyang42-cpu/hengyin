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

## 证据

到场只认 `w16_meeting_evidence` 里 `fact_source = AUTHORITY_FACT` 且规则版本非空、地点和双方匹配的行。确认口令、扫码、双击和坐标不写这张表，也不能变成 `READY`。

## A 的事务内检查

本组端口仍是 `RendezvousHandoffSafety.verifyCurrent`。适配器 `RendezvousHandoffParticipantAdapter` 在调用方已经打开的 `Transaction` 上调用

```text
OngoingVerdict verifyParticipants(Transaction tx, String ticketId)
```

A 的类型还不在这棵树上。合并前只把明确的仍安全结论映射为 `Verdict.SAFE`，其余失败关闭。`matchId`、`actor`、`peer` 由适配器在同一个 `tx` 上核对。`requireMutualAccepted` 和事务内 terminate 还没冻结，本组不调用它们。

## 组队端口

`IPlayTeamService.create` / `join` 仍没有请求号和权威结果查询。`RendezvousHandoffLegacyTeamPort` 的 `lookup` 固定为未知，`create` / `join` 直接拒绝，不写队伍表。完整接通等跨模块改动获准之后。
