# W16.4 举报受理合同（拟议，未接通）

本文件只冻结拟议的 `ApiRendezvousReportController` 路由、状态和错误。该类**不在本树中**，没有注册为 Spring Bean，没有 profile，没有菜单，没有匿名白名单。下面的路径**不是现有 API**，当前不能对它们发请求。

服务入口是显式构造的 `RendezvousReportIntake`（不是 Bean，不是 HTTP）。本文件先冻结合同；HTTP 控制器不随合同出现。`RendezvousReportStop.report(long, long)` 仍只是旧的一对用户标记，不是案件，也不把举报送去审核。

## 拟议路由

类名若将来获准实现，只能是 `com.chengyinhub.web.controller.api.ApiRendezvousReportController`。本线不创建该类。即使另批实现，也必须单独批准 profile 和显式 Bean；默认 `druid` profile 下不可用。响应包装与现有接头控制器相同：`AjaxResult`，`Cache-Control: no-store`。actor 只来自已登录 `LoginAppUser`，不接受 body、query 或路径里的 memberId。

### `POST /api/rendezvous/reports`

受理。body 恰好三个字符串字段：`requestId`、`ticketId`、`evidenceKind`。没有 query，没有理由正文、图片、坐标、聊天记录或对方 id。`evidenceKind` 只允许 `TICKET_REF`，表示用本人这条接头票据作已有关联证据，不另存一份证据。

成功时 `200`，`data` 只有：`caseId`、`requestId`、`ticketId`、`state`、`reviewConnection`、`reviewRef`、`dispositionCode`。不含对方身份、match、地点、线索或证据正文。

### `GET /api/rendezvous/reports/{requestId}`

丢响应后的本人读回。只按登录人和原来的 `requestId` 读已落库的回执。不重新停止联系，不调用审核。他人、被举报人、不存在的键都是 `404 NOT_FOUND`。

### `POST /api/rendezvous/reports/{requestId}/refresh`

显式读一次审核端口并按下面的状态规则写回。没有定时器，没有时限。生产审核端口未接通时，状态保持原样。

## 状态

三个状态分开，不能跳步，也不能把后一个状态当成前一个的别名。

| state | 含义 | reviewConnection | reviewRef | dispositionCode |
| --- | --- | --- | --- | --- |
| `LOCAL_INTAKE` | 本地已受理。同一守卫事务里已停止该对这次接头联系，回执已提交。 | `NOT_CONNECTED` 或 `AMBIGUOUS` | 空 | 空 |
| `SENT_TO_REVIEW` | 真实审核端口返回了接受，并且带了非空 `reviewRef`。 | `ACCEPTED` | 端口给的不透明引用 | 空 |
| `RESOLVED` | 此前已经是 `SENT_TO_REVIEW`，端口随后给了符合字面规则的处置码。 | `ACCEPTED` | 保持原引用 | 端口给的码 |

`LOCAL_INTAKE` 不是已送审核。`AMBIGUOUS`（含端口超时、抛错、接受但没有引用、在本地受理阶段直接返回已处置）都留在 `LOCAL_INTAKE`，不记成已送审核或已处置。处置码只保存，不解释成封禁，不触发处罚。

## 错误

| HTTP | errorCode | 何时 |
| --- | --- | --- |
| 401 | `AUTH_REQUIRED` | 未登录或 actor 无效 |
| 400 | `INVALID_REQUEST` | 字段缺失、超长，或 `evidenceKind` 不是 `TICKET_REF` |
| 404 | `NOT_FOUND` | 票据不存在、不是本人的票据，或回执不是本人的 |
| 409 | `NOT_ASSOCIATED` | 票据是本人的，但没有这次共同接头 |
| 409 | `CONFLICT` | 同一个举报人的同一个 `requestId` 对上了不同票据 |
| 503 | `CONTACT_STOP_NOT_CONNECTED` | 同事务停止端口未接通。不写回执，不停止 |
| 503 | `CONTACT_STOP_FAILED` | 停止没有完成。回执和停止一起回滚 |
| 503 | `W16_UNAVAILABLE` | 持久化未开启，或其它未列明的失败 |

冒充他人票据与无此票据同一 `NOT_FOUND`。无关联不写案件。重试同一个键且载荷相同，返回原来的 `caseId`，不第二行、不第二次停止。同键不同载荷返回 `CONFLICT`，不覆盖旧案。另一个 `requestId` 是另一起案件，即使仍是同一对人。

## 同事务端口（对齐 A @ 37b4b345）

A 在 `cursor/w16-a-eligibility` 的 `37b4b345` 上冻结了守卫事务内的两个方法。本线不改 Consent。生产适配器尚未接入；接通时必须逐参转发，不能改名或改含义。

```text
OngoingVerdict verifyParticipants(Transaction tx, long actor, String ticketId,
    String expectedMatchId, Long expectedPeer, boolean requireMutualAccepted, long now)
OngoingVerdict terminateCurrent(Transaction tx, long actor, Long peer, String ticketId,
    Termination reason, long now)
Termination { BLOCK, REPORT, EXIT }
```

`OngoingVerdict` 的字段顺序与 A 相同：`safe`、`intact`、`expired`、`eligible`、`blocked`、`placeAllowed`、`owned`、`mutual`、`ticketId`、`matchId`、`ticketState`、`matchState`。适配器按这个顺序抄字段。`Termination.REPORT` 对应 `RendezvousConsentService.Termination.REPORT`。本线不调用 `BLOCK` 或 `EXIT`。

本线实际调用：

```text
verifyParticipants(tx, reporter, ticketId, matchId, reported, false, now)
terminateCurrent(tx, reporter, reported, ticketId, Termination.REPORT, now)
```

`reported` 从该票据的 match 推导，不来自客户端。`requireMutualAccepted` 固定为 false：举报只要求这条接头属于本人，不要求双方都已接受。`terminateCurrent` 只停止这两人当前共同的活跃 match（`SAFETY_STOPPED`，并释放预约），重复调用不改版本；任一侧对不上时在写入前抛 `CORRUPT_RESERVATION`。已经结束的 match 保持原状态，不复活。

未接通的适配器直接抛 `CONTACT_STOP_NOT_CONNECTED`，不返回 `safe=false`。`safe=false` 是 A 的真实否定结论，不能拿来表示端口不存在。

## 不在本线

不自动封禁。不设阈值、TTL、审核时限或频率窗口。不写全局 IM、社区举报或 `audit_task`。不调用 `ImServiceImpl.report`。不把 `w16_safety_report` 的一对用户行当成案件。审核端口在本树没有可写的接头审核实现，生产装配固定为未接通，读回不得显示已送审核。
