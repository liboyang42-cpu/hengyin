# W16.4 安全退出（候选，默认关闭）

本文件冻结 `ApiRendezvousExitController` 的路由。它不是生产接通说明。`ApiRendezvousPoolController` 不在本线。没有生产 Bean，没有菜单权限，没有匿名路由。候选 SQL 不在生产执行。

控制器只在 profile `w16-consent-contract` 且容器里已经有 `RendezvousSafeExitService` Bean 时才装载。应用默认 profile 是 `druid`。本线不提供该 Service 的 `@Bean`。

本补丁只含 J 的文件。编译依赖 A4 `08f44986`，不把 A 的提交带进本分支。

## 依赖

A4：`cursor/w16-a-eligibility` @ `08f44986`。退出在已经打开的同意守卫事务里调用：

```text
verifyParticipants(tx, actor, ticketId, null, null, false, now)
terminateCurrent(tx, actor, null, ticketId, Termination.EXIT, now)
```

`peer` 固定为 null。不调用 `terminateCurrentMatch`。测试里的配对使用四参构造和 `MyBatisRendezvousWaiting`。退出账本与占位释放共用同一个 `SqlSessionTemplate` 和 `DataSourceTransactionManager`。插入失败则两边一起回滚。本线不写队伍表，这次回滚也不碰到队伍。

C5：`cursor/w16-c-handoff-recovery` @ `dd143d03`。正式查询是

```text
RendezvousMeetingService.HandoffState handoffStateFor(String matchId)
```

只返回 `NOT_STARTED`、`UNKNOWN`、`CREATED_JOIN_UNCONFIRMED`、`COMPLETE`。J 不声明这份枚举。端口 `HandoffStateQuery.handoffStateFor(String)` 把上述名字原样传回。没有 match id 时不调用查询，交接记为 `NOT_STARTED`。查询抛错、返回空或返回这四个名字以外的值，都记成明确的 `UNKNOWN`。这不表示队伍已经回滚。

单人启动器端口 `SoloLauncher.startOrResume(long, String, String)` 默认不接通，生产不注册实现。`completed` 只有结果口对同一回执返回 `COMPLETED` 才为 true。

## 路由

前缀：`/api/rendezvous/tickets/{ticketId}/exit`

身份只取已登录小程序会员。请求不能提交对方身份、目标编号、罚则、难度、坐标、队伍编号或奖励。成功和失败都 `Cache-Control: no-store`。

### GET

读当前规则下的退出选项；若该票据已经有过一次显式选择，则读回那次持久结果。无 query，无 body。

### POST

body 有且仅有一个字段：`{"choice":"EXIT"}` 或 `{"choice":"SOLO"}`。多字段、空 body、其他取值都是 `INVALID_REQUEST`。

## 成功投影

不包含对方、线索、角色、地点、队伍、罚则、难度、奖励。没有「队伍已回滚」字段。

| 字段 | 含义 |
| --- | --- |
| ticketId | 本人票据 |
| decided | 是否已有持久选择 |
| exitAllowed | 未决定且当前阶段允许退出时为 true。查询失败不关掉它 |
| soloAllowed | 未决定，且交接正好是 `NOT_STARTED`，同时有已发布单人目标和已接通的启动器 |
| choice | `EXIT` 或 `SOLO`；未决定时为空 |
| phase | `WAITING`、`ONE_SIDED`、`MUTUAL` |
| handoff | C 的四个名字之一。查询故障是 `UNKNOWN` |
| soloState | `NOT_REQUESTED`、`STARTED`、`COMPLETED`，或启动结果不明时的 `UNKNOWN` |
| soloBlock | 可空。调用方只看 `soloState` 就能知道启动可能已经发出 |
| goalRef | 这次选择绑定到的已发布单人目标，可空 |
| launchReceipt | 权威回执，可空。空回执不是成功 |
| resumed | 启动器是否声明为恢复 |
| completed | 只有结果口返回 `COMPLETED` 才为 true |

`soloState=UNKNOWN` 是启动待核的主状态：异常、空回执，或进程停在 `STARTING` 之后的读回，都投影成它。它不是「还没启动」。同一选择不再次启动。

## 行为

- 可退出阶段只有等待、单边接受、双方接受。终态是 `EXIT_UNAVAILABLE`。非本人与缺票是 `NOT_FOUND`。`safe=false` 本身不拒绝退出。
- 新的 `EXIT` 和 `SOLO` 都先取交接。查询故障变成 `UNKNOWN`，不在 `terminateCurrent` 之前把退出打失败。
- `EXIT` 在任何已识别交接（含 `UNKNOWN`）上仍调用 `terminateCurrent(..., null, ..., EXIT, now)`，成功后再插入退出行。等待票只取消自己。有 match 时两边变为 `CANCELLED`（已过期则 `EXPIRED`），两边占位删除。重复同一选择只读回，不再终止，也不再启动。
- `options` 同样把查询故障显示为 `UNKNOWN`，`soloAllowed` 为 false，`exitAllowed` 仍按阶段给出。它不释放占位，也不声称队伍已回滚。
- `SOLO` 在交接不是 `NOT_STARTED` 时拒绝，不释放占位，不写行，不调用启动器。`UNKNOWN` 是 `HANDOFF_UNKNOWN`，`CREATED_JOIN_UNCONFIRMED` 和 `COMPLETE` 各用自己的码。
- 交接是 `NOT_STARTED` 但没有已发布目标或启动器时，同样在终止之前拒绝。
- 允许的 `SOLO` 先在守卫事务里写下 `STARTING` 并释放占位，提交后再调用一次启动器。异常或空回执把主状态写成 `UNKNOWN`，不写成 `NOT_STARTED`。读到残留的 `STARTING` 也投影为 `UNKNOWN`。不再次调用启动器。
- 拒绝或退出都不扣资源、不记罚、不发奖，也不回滚队伍。

## 错误

| code | HTTP | 何时 |
| --- | --- | --- |
| AUTH_REQUIRED | 401 | 无登录会员 |
| INVALID_REQUEST | 400 | 字段、取值或 query 不合法 |
| NOT_FOUND | 404 | 非本人或没有这张票据 |
| EXIT_UNAVAILABLE | 409 | 阶段不可退出 |
| EXIT_CONFLICT | 409 | 已有不同选择 |
| HANDOFF_UNKNOWN | 409 | `SOLO`，交接不是 `NOT_STARTED`（含查询故障） |
| HANDOFF_JOIN_UNCONFIRMED | 409 | `SOLO`，交接是 `CREATED_JOIN_UNCONFIRMED` |
| HANDOFF_COMPLETE | 409 | `SOLO`，交接是 `COMPLETE` |
| SOLO_GOAL_UNAVAILABLE | 409 | `SOLO`，没有已发布单人目标 |
| SOLO_LAUNCHER_UNAVAILABLE | 409 | `SOLO`，启动器未接通 |
| W16_EXIT_DISABLED | 503 | 服务未处于隔离合同评估 |
| W16_UNAVAILABLE | 503 | 其他未分类故障 |
