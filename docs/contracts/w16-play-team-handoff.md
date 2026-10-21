# W16 PlayTeam 原请求恢复（候选，默认关闭）

本文只描述本组这次接上的真实队伍入口。没有批准时不装配、不建队、不执行 DDL。H2 通过不是 MySQL 验收，也不是生产接通。

## 入口

`IPlayTeamService.create` / `join` 的参数和语义不动。新增：

```text
Fact createForHandoff(PlayTeamHandoffRequest request)
Fact joinForHandoff(PlayTeamHandoffRequest request)
Fact findHandoff(String namespace, String requestId)
```

上下文至少包含 `namespace`、`requestId`、`matchId`、`leader`、`peer`、`activity`。索引唯一键是 `(namespace, request_id)` 和 `(namespace, match_id)`。`Fact` 不带邀请码。

默认的会面恢复仍用 `RendezvousHandoffLegacyTeamPort`，不写队伍。只有显式传入 `PlayTeamHandoffGateway` 时，`RendezvousMeetingService.handoff` 才走这条路。生产构造不传。

## 同一事务

创建或加入各自一条事务，允许中间停在 `TEAM_CREATED`。一条事务里的顺序是：

1. `w16_consent_guard` 行锁（会面存储在事务开头取得）。
2. 双方 `verifyCurrent`，以及截止时间、证据、停止标记、原 `request_id`。
3. `w16_play_team_handoff` 行锁。
4. 真实 `play_team` / `play_team_member`。
5. `IImService.getOrCreateTeamGroup`（`REQUIRED`，走代理，加入当前事务）。
6. 请求阶段与上述写入一起提交。

不先提交 `markDispatched` 再释放锁去写队伍。`createForHandoff` / `joinForHandoff` 是 `MANDATORY`，不能自己开事务，也不调用 `this.create` / `this.join`。禁止 `REQUIRES_NEW`。

`findHandoff` 只读。没有行且读取成功才是 `ABSENT`。SQL 失败抛错，调用方记为未知，不创建。索引指向的队伍不存在是 `UNKNOWN`，不补建。

## 取消

取消先提交到守卫上：这次创建或加入还没开始，队伍行数是 0，阻断码 `CANCELLED_PREVENTED`。

队伍事务先提交：队伍、队长和群留下。随后的取消把阻断码写成 `CANCELLED_NOT_ROLLED_BACK`，并拦住下一次加入。不把已提交的队伍说成已回滚。

加入前会再取守卫并重读安全。创建和加入之间提交的撒权或拉黑，使加入事务拒绝，不再 `reserveSlot`。这不证明加入事务内部 `existsBetween` 之后才提交的拉黑也被挡住。H3 仍是 `ImServiceImpl.block` 的唯一写入方。要对齐竞争，H3 的写锁顺序应是先 `w16_consent_guard`，再块表。本组不改 H3 的文件，也不把一次块查询说成竞争已关闭。

A 的事实目录和 `RendezvousConsentService(Store, Safety, Clock, Admission)` 四参数构造不在本分支。本路径不构造同意服务，因此也不会记 admission charge。安全复核仍走本组已有的 `verifyCurrent`。

## 幂等

同一个命名空间和请求再创建，返回原 `team_id`，不建第二队，不再占队长名额。同键不同上下文拒绝。

`join` 在阶段已是 `JOINED` 时不再 `reserveSlot`。之后真实退出或踢出，重试不会把成员加回去。结束或解散的队伍返回当前状态，不重建。

新建队伍仍核对场次、已支付报名、主题 `teamMode` 和容量。交接队伍用邀请制（`joinMode=1`），避免出现在公开申请地图上。

## 候选 DDL

`chengyinhub-admin/sql/migration_w16_play_team_handoff_candidate.sql` 只建索引表。不改 `play_team`，不在本组执行。

## 锁序（与 A、H3 对齐，未改他们的文件）

1. `w16_consent_guard`
2. `w16_play_team_handoff`
3. `play_team`
4. IM 会话（`getOrCreateTeamGroup` 里再锁队伍行，然后写会话）

块表不在这条锁序里。默认数据源的动态路由不能代替上面的同一 `DataSourceTransactionManager` 证明。
