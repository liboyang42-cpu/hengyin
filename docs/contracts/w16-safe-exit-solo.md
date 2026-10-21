# W16.4 安全退出与独自续玩（候选，默认关闭）

本文件先冻结拟新增 `ApiRendezvousExitController` 的路由、状态和错误。它不是生产接通说明，也不把已有入池、同意或会面接口改成退出接口。`ApiRendezvousPoolController` 不在本线。没有注册生产 Bean，没有菜单权限，没有匿名路由。

控制器只在 profile `w16-consent-contract` 且容器里已经有 `RendezvousSafeExitService` Bean 时才装载。应用默认 profile 是 `druid`。本线不提供该 Service 的 `@Bean`。

## 路由

前缀：`/api/rendezvous/tickets/{ticketId}/exit`

身份只取已登录小程序会员。请求不能提交对方身份、目标编号、罚则、难度、坐标、队伍编号或奖励。成功和失败都 `Cache-Control: no-store`。

### GET

读当前规则下的退出/独自选项；若该票据已经有过一次显式选择，则读回那次持久结果。无 query，无 body。

### POST

body 有且仅有一个字段：`{"choice":"EXIT"}` 或 `{"choice":"SOLO"}`。多字段、空 body、其他取值都是 `INVALID_REQUEST`。

## 成功投影

不包含对方、线索、角色、地点、队伍、罚则、难度、奖励。

| 字段 | 含义 |
| --- | --- |
| ticketId | 本人票据 |
| decided | 是否已有持久选择 |
| exitAllowed | 未决定且当前阶段允许退出时为 true |
| soloAllowed | 未决定，且 `handoffStateFor` 返回 `NOT_STARTED`，同时已有明确允许单人的已发布目标和权威启动器时为 true |
| choice | `EXIT`、`SOLO`，未决定时为空 |
| phase | `WAITING`、`ONE_SIDED`、`MUTUAL` |
| handoff | `NOT_STARTED`、`UNKNOWN`、`CREATED_JOIN_UNCONFIRMED`、`COMPLETE` |
| soloState | `NOT_REQUESTED`、`NOT_STARTED`、`STARTED`、`COMPLETED` |
| soloBlock | 未启动原因，可空 |
| goalRef | 仅当这次选择已经绑定到目录返回的已发布单人目标时出现 |
| launchReceipt | 权威启动器回执，可空 |
| resumed | 启动器是否声明为恢复 |
| completed | 只有权威结果口对同一回执返回 `COMPLETED` 才为 true |

`soloState=STARTED` 不是完成。`completed=true` 不能由客户端、退出成功或占位释放推出。

## 行为

- 可退出阶段只有等待、单边接受、双方接受。终态、缺票、非本人与票据不存在一样，返回 `NOT_FOUND` 或 `EXIT_UNAVAILABLE`，不泄露对方。
- `EXIT` 在同一守卫事务里释放本人占位并落下唯一选择。重复同一选择只读回原行，不再释放，不启动单人。另一选择返回 `EXIT_CONFLICT`。
- `SOLO` 只有在 `handoffStateFor` 返回 `NOT_STARTED`，且目标目录有明确允许单人的已发布目标、权威启动器也在时，才会写选择。`UNKNOWN`、`CREATED_JOIN_UNCONFIRMED` 和 `COMPLETE` 都不启动：不写选择、不释放占位、不调用启动器、不新造 request id。本人仍可改发 `EXIT`。
- 没有 match id 时不调用交接查询，交接记为 `NOT_STARTED`。有 match id 时只把该 id 原样传入。本线不创建、加入、退出或解散队伍，也不把未知副作用报成已回滚。
- 允许启动时，先落下 `STARTING` 并释放占位，事务提交后最多调用一次启动器。回执缺失、异常或在回执落库前崩溃时，POST 返回 200 的持久读回：选择已记下，`soloState` 为 `NOT_STARTED`，`completed` 为 false。这不是独自完成，且不再次启动。
- 拒绝单人或退出都不得扣资源、记罚或发奖。本线没有罚则、难度、奖励参数。
- 不复制对方秘密或角色，不把对方写进单人启动。

## 错误

| code | HTTP | 何时 |
| --- | --- | --- |
| AUTH_REQUIRED | 401 | 无登录会员 |
| INVALID_REQUEST | 400 | 字段、取值或 query 不合法 |
| NOT_FOUND | 404 | 非本人或没有这张票据 |
| EXIT_UNAVAILABLE | 409 | 阶段不可退出，或核验结果无法识别 |
| EXIT_CONFLICT | 409 | 已有不同选择 |
| HANDOFF_UNKNOWN | 409 | 选择了 SOLO，但交接结果不是 `NOT_STARTED`（含未知或查询缺失） |
| HANDOFF_JOIN_UNCONFIRMED | 409 | 选择了 SOLO，但交接是 `CREATED_JOIN_UNCONFIRMED` |
| HANDOFF_COMPLETE | 409 | 选择了 SOLO，但交接已是 `COMPLETE` |
| SOLO_GOAL_UNAVAILABLE | 409 | 选择了 SOLO，但没有明确允许单人的已发布目标 |
| SOLO_LAUNCHER_UNAVAILABLE | 409 | 选择了 SOLO，但没有权威启动器 |
| W16_EXIT_DISABLED | 503 | 服务未处于隔离合同评估 |
| W16_UNAVAILABLE | 503 | 其他未分类故障 |

## 本线需要的签名

A 的方法在已经打开的同意守卫事务里调用，本线不再另开同意事务。C 的查询没有事务参数。本分支没有 A 在 `37b4b345` 上的类型；`RendezvousMeetingService` 也还没有 `HandoffState`。适配器按下表原样转发，不猜额外字段。

```text
OngoingVerdict verifyParticipants(Transaction tx, long actor, String ticketId, String expectedMatchId, Long expectedPeer, boolean requireMutualAccepted, long now)
OngoingVerdict terminateCurrent(Transaction tx, long actor, Long peer, String ticketId, Termination reason, long now)
RendezvousMeetingService.HandoffState handoffStateFor(String matchId)
String publishedSoloGoal(Transaction tx, long actor, String matchId)
LaunchReceipt startOrResume(long actor, String goalRef, String attemptId)
Status read(String receiptId)
```

本线实际调用：

```text
verifyParticipants(tx, actor, ticketId, null, null, false, now)
terminateCurrent(tx, actor, null, ticketId, Termination.EXIT, now)
handoffStateFor(matchId)   // 仅当判决里已有 matchId
```

`expectedMatchId` 和 `expectedPeer` 都传 null，不绑定对方，也不要求双方都已接受。`Termination` 只传 `EXIT`，不传 `BLOCK` 或 `REPORT`。`peer` 必须是 null。非本人票据由 A 抛 `NOT_FOUND` 且不写；本线在该调用成功之后才插入退出行。等待中的票据只取消自己，有 match 时两边取消并删两边占位，这些都是 A 的 `terminateCurrent` 行为，本线不写同意表。

`OngoingVerdict` 与 `37b4b345` 的字段一一对应：`safe`、`intact`、`expired`、`eligible`、`blocked`、`placeAllowed`、`owned`、`mutual`、`ticketId`、`matchId`、`ticketState`、`matchState`。适配器逐字段拷贝。`safe=false` 本身不拒绝退出。`owned=false` 或没有票据状态视为 `NOT_FOUND`。

`handoffStateFor` 只认 `NOT_STARTED`、`UNKNOWN`、`CREATED_JOIN_UNCONFIRMED`、`COMPLETE`，无副作用，不新造 request id。独自启动只接受 `NOT_STARTED`。`Status` 只认 `RUNNING`、`COMPLETED`、`NOT_FOUND`。`publishedSoloGoal` 只能返回已经标明允许单人的已发布目标。`startOrResume` 只接收本人，`attemptId` 是票据号。空回执不是成功。`read` 缺席或不是 `COMPLETED` 时，不得把独自玩法报成完成。

当前仓库没有可复用的「明确允许单人」的已发布目标，也没有本流程的权威启动器。安全退出可以落库。选择 `SOLO` 不会记成完成。
