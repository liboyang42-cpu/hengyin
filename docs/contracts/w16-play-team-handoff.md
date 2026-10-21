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

查询结果带上索引行的 `namespace`、`requestId`、`matchId`、`leader`、`peer`、`activity`。会面恢复在采纳 `teamId`、跳过已 `JOINED` 的加入、以及发布之前，把这六项和当前会面请求逐项比较。缺任意一项，或和预期不一致，直接返回当前只读结果：不建队、不加入、不改索引、不把外来 `teamId` 写成已完成。这是损坏行或错接 gateway 的拒绝，不是已发生的越权证明。正常唯一索引没有被用来声称线上已经串队。

## 取消

取消先提交到守卫上：这次创建或加入还没开始，队伍行数是 0，阻断码 `CANCELLED_PREVENTED`。

队伍事务先提交：队伍、队长和群留下。随后的取消把阻断码写成 `CANCELLED_NOT_ROLLED_BACK`，并拦住下一次加入。不把已提交的队伍说成已回滚。

加入前会再取守卫并重读安全。创建和加入之间提交的撒权或拉黑，使加入事务拒绝，不再 `reserveSlot`。这不证明加入事务内部 `existsBetween` 之后才提交的拉黑也被挡住。H3 仍是 `ImServiceImpl.block` 的唯一写入方。要对齐竞争，H3 的写锁顺序应是先 `w16_consent_guard`，再块表。本组不改 H3 的文件，也不把一次块查询说成竞争已关闭。

`RendezvousConsentService` 使用 A 在 `08f44986` 的原文件。三参构造的默认 `Admission` 对新配对抛出 `POLICY_CLOSED`。要形成新配对必须走四参，并在守卫事务里调用 `Admission.charge`。本组不恢复不记账的三参旁路。联系人账本 `RendezvousContactLedger` 不在这份同意服务的类型依赖里，本增量不复制、不启用它。安全复核仍走本组已有的 `verifyCurrent`。

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

块表不在这条锁序里。`MANDATORY` 只说明调用方已经有某个事务，不能证明是同一 `DataSource`、同一 `DataSourceTransactionManager`，也不能证明已经持有 `w16_consent_guard`。

正式入口是 `PlayTeamHandoffDelegate.bind(SqlSessionTemplate)`。它只接收这一个会话：事务工厂必须是 `SpringManagedTransactionFactory`，否则 `W16_TRANSACTION_RESOURCE_MISMATCH`。事务管理器从该会话环境的 `DataSource` 新建。然后调用 `RendezvousPairingComposition.formal`，参数是这个 `DataSource`、同一个 `SqlSessionTemplate`、派生管理器和时钟。返回的同意服务是 `Mode.FORMAL`，准入是 `RendezvousContactLedger.charge`。`propose` 在该次 `lockGuard` 事务里把双方写入 `w16_contact_event`。会面 `confirm` 与 `handoff` 之后仍用这个会话和这个管理器，不再次调用 `charge`。队伍、交接索引、拉黑查询、场次、主题、IM 会话和成员映射都从这一个会话取出，再挂到 `PlayTeamServiceImpl` 和 `ImServiceImpl` 上。这两个实现的事务代理都使用上面那个管理器。交给队伍服务的 IM 对象只转发 `getOrCreateTeamGroup`；包括 `block` 在内的其它方法抛出 `W16_BLOCK_NOT_OWNED`。`ImServiceImpl.block` 仍只由 H 修改。守卫包装使用这个派生管理器。比较调用者另外传入的 session 或 manager 不是这条链。`PlayTeamHandoffAssembly.open` 一律拒绝，错误码 `W16_CALLER_SUPPLIED_MANAGER_REFUSED`。

会面存储和同意存储用 `bind` 返回的同一个管理器，这样 `lockGuard` 之后的守卫标记和队伍代理是同一资源。`create` / `join` 在运行时拒绝：当前事务没有绑定该数据源（`W16_TRANSACTION_RESOURCE_MISMATCH`），或该管理器上没有守卫标记（`W16_GUARD_MISSING`）。直接调用 `createForHandoff` 仍只有 `MANDATORY`，不查守卫标记；会面交接走 `bind` 返回的 gateway。生产不调用 `bind`，也不打开这个开关。默认数据源的动态路由不能代替这个检查。

页面回读是已有的 `GET /api/rendezvous/tickets/{ticketId}/handoff`，演员来自登录主体，只读 `result`。该控制器仍要 `w16-consent-contract` 且容器里有会面 bean。本包不注册这个 bean。
