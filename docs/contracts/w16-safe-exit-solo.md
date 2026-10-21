# W16.4 安全退出（候选，默认关闭）

本文件冻结 `ApiRendezvousExitController` 的路由。它不是生产接通说明。`ApiRendezvousPoolController` 不在本线。没有生产 Bean，没有菜单权限，没有匿名路由。候选 SQL 不在生产执行。

控制器只在 profile `w16-consent-contract` 且容器里已经有 `RendezvousSafeExitService` Bean 时才装载。应用默认 profile 是 `druid`。本线不提供该 Service 的 `@Bean`。

本补丁只含 J 的文件。它依赖 A4，不把 A 的提交带进本分支。

## 依赖

A4：`cursor/w16-a-eligibility` @ `08f44986`。退出在 A 的同意守卫事务里调用，不再保留 J 自己的 `OngoingVerdict` / `Termination` 镜像：

```text
OngoingVerdict verifyParticipants(Transaction tx, long actor, String ticketId, String expectedMatchId, Long expectedPeer, boolean requireMutualAccepted, long now)
OngoingVerdict terminateCurrent(Transaction tx, long actor, Long peer, String ticketId, Termination reason, long now)
```

本线实际调用：

```text
verifyParticipants(tx, actor, ticketId, null, null, false, now)
terminateCurrent(tx, actor, null, ticketId, Termination.EXIT, now)
```

`peer` 固定为 null。不调用 `terminateCurrentMatch`（那是拉黑停在 `SAFETY_STOPPED`）。不调用三参构造；测试里的配对使用四参构造和 `MyBatisRendezvousWaiting`。退出账本的读写使用交给同意守卫的同一个 `SqlSessionTemplate` 和 `DataSourceTransactionManager`。`terminateCurrent` 删除占位和退出行插入发生在这一次 `store.transaction` 里。插入失败则两边一起回滚。本线不写队伍表，这次回滚也不碰到队伍。

C：`cursor/w16-c-handoff-recovery` @ `9b8293df` 的正式查询是

```text
RendezvousMeetingService.HandoffState handoffStateFor(String matchId)
```

返回 `NOT_STARTED`、`UNKNOWN`、`CREATED_JOIN_UNCONFIRMED`、`COMPLETE`。见该分支 `docs/contracts/w16-handoff-recovery.md`。C5 仍在进行，这个类型不在本树。J 不复制这四个状态，也不调用一个替身。查询未进本树之前，`SOLO` 直接拒绝，不释放占位，不写退出行。

单人启动器没有接通。`completed` 恒为 false。没有第二次启动。

## 路由

前缀：`/api/rendezvous/tickets/{ticketId}/exit`

身份只取已登录小程序会员。请求不能提交对方身份、目标编号、罚则、难度、坐标、队伍编号或奖励。成功和失败都 `Cache-Control: no-store`。

### GET

读当前规则下的退出选项；若该票据已经有过一次显式选择，则读回那次持久结果。无 query，无 body。

### POST

body 有且仅有一个字段：`{"choice":"EXIT"}` 或 `{"choice":"SOLO"}`。多字段、空 body、其他取值都是 `INVALID_REQUEST`。

## 成功投影

不包含对方、线索、角色、地点、队伍、罚则、难度、奖励。

| 字段 | 含义 |
| --- | --- |
| ticketId | 本人票据 |
| decided | 是否已有持久选择 |
| exitAllowed | 未决定且当前阶段允许退出时为 true |
| soloAllowed | 恒为 false。交接查询和单人启动器都未接通 |
| choice | `EXIT` 或 `SOLO`；未决定时为空 |
| phase | `WAITING`、`ONE_SIDED`、`MUTUAL` |
| handoff | 未决定或本次 `EXIT` 记为 `NOT_PUBLISHED`。这不是 C 的状态值 |
| soloState | 退出行为 `NOT_REQUESTED` |
| soloBlock | 可空 |
| goalRef | 空。没有接通的单人目标 |
| launchReceipt | 空 |
| resumed | false |
| completed | false |

## 行为

- 可退出阶段只有等待、单边接受、双方接受。终态是 `EXIT_UNAVAILABLE`。非本人与缺票是 `NOT_FOUND`。`safe=false` 本身不拒绝退出。
- `EXIT` 在同一次同意守卫事务里调用 `terminateCurrent(..., null, ..., EXIT, now)`，成功后再插入退出行。等待票只取消自己。有 match 时两边变为 `CANCELLED`（已过期则 `EXPIRED`），两边占位删除。这些写都是 A 的方法。重复同一选择只读回原行，不再调用终止。另一选择返回 `EXIT_CONFLICT`。
- `SOLO` 在交接查询和启动器接通之前拒绝，代码 `SOLO_LAUNCHER_UNAVAILABLE`。不写选择、不释放占位、不调用启动器。
- 拒绝单人或退出都不得扣资源、记罚或发奖。本线没有罚则、难度、奖励参数，也不回滚队伍。

## 错误

| code | HTTP | 何时 |
| --- | --- | --- |
| AUTH_REQUIRED | 401 | 无登录会员 |
| INVALID_REQUEST | 400 | 字段、取值或 query 不合法 |
| NOT_FOUND | 404 | 非本人或没有这张票据 |
| EXIT_UNAVAILABLE | 409 | 阶段不可退出 |
| EXIT_CONFLICT | 409 | 已有不同选择 |
| SOLO_LAUNCHER_UNAVAILABLE | 409 | 选择了 `SOLO`。启动器未接通，交接查询也未进本树 |
| W16_EXIT_DISABLED | 503 | 服务未处于隔离合同评估 |
| W16_UNAVAILABLE | 503 | 其他未分类故障 |

`HANDOFF_UNKNOWN`、`HANDOFF_JOIN_UNCONFIRMED`、`HANDOFF_COMPLETE`、`SOLO_GOAL_UNAVAILABLE` 仍留在控制器映射里，留给 C 的查询进树之后。本树现在不会发出这些码。
