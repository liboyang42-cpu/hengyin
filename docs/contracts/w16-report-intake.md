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

四个状态分开，不能跳步，也不能把后一个状态当成前一个的别名。

| state | 含义 | reviewConnection | reviewRef | dispositionCode |
| --- | --- | --- | --- | --- |
| `LOCAL_INTAKE` | 本地已受理。同一守卫事务里已停止该对这次接头联系，回执已提交。还没有一次有结果的 submit。 | `NOT_CONNECTED` | 空 | 空 |
| `UNKNOWN` | 已经调用过一次 submit，结果没有落成已保存的接受。回放只核对 status，不再 submit。 | `UNKNOWN` 或 `AMBIGUOUS` | 空 | 空 |
| `SENT_TO_REVIEW` | 真实审核端口返回了接受，并且带了非空 `reviewRef`，本地已写下。 | `ACCEPTED` | 端口给的不透明引用 | 空 |
| `RESOLVED` | 此前已经是 `SENT_TO_REVIEW`，端口随后给了符合字面规则的处置码。 | `ACCEPTED` | 保持原引用 | 端口给的码 |

`LOCAL_INTAKE` 不是已送审核。`UNKNOWN` 也不是已送审核，更不是已处置。端口超时、抛错、含糊、接受但没有引用、在还没送到审核时直接返回已处置，都记成 `UNKNOWN`。对方接受但本地没有写成 `SENT_TO_REVIEW` 时，同样记成 `UNKNOWN`，不保留那个没写上的引用。处置码只保存，不解释成封禁，不触发处罚。`RESOLVED` 不能从 `LOCAL_INTAKE` 或 `UNKNOWN` 直接跳来。

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

## 同事务停止（依赖 A4 `08f44986`，本线不改 ConsentService）

停止依赖 `cursor/w16-a-eligibility` 的 A4 `08f44986`（三参构造默认拒绝未记账的新配对）及其前置提交。`RendezvousConsentService` 是共享类型，本线不修改它，也不再保留 `OngoingVerdict` / `Termination` 的镜像副本。类型和下面两个方法都用 A 的正式定义。

生产受理在**已经打开的同一守卫事务、同一库**里调用：

```text
verifyParticipants(tx, reporter, ticketId, matchId, reported, false, now)
terminateCurrent(tx, reporter, reported, ticketId, Termination.REPORT, now)
```

`reported` 从该票据的 match 推导，不来自客户端。`requireMutualAccepted` 固定为 false。`owned` 不为真，或票据不属于举报人：`NOT_FOUND`。不完整，或 match 对不上：`NOT_ASSOCIATED`。不把 `safe=false` 当成未接通。已经结束的 match 保持原状态，不复活。活跃 match 被记为 `SAFETY_STOPPED` 并释放预约。回执插入与这次停止在同一事务：任一步失败，两边一起回滚。同一个 `requestId` 且载荷相同只读回原案，不再次停止。同键不同载荷是 `CONFLICT`，不覆盖、不再次停止。

未传入 `RendezvousConsentService` 时抛 `CONTACT_STOP_NOT_CONNECTED`，不写回执，不停止。这和 `safe=false` 不是一回事。

## 审核与 HTTP（未接通）

审核端口生产装配固定为 `NotConnectedReview`。它不返回引用，也不返回处置码。`connected(...)` 只接上 A 的停止，审核仍是这个未接通实现。因此生产回执停在 `LOCAL_INTAKE` / `NOT_CONNECTED`。`SENT_TO_REVIEW` 与 `RESOLVED` 只有测试里换上的审核替身才能到达，生产路径到不了。

HTTP 控制器 `ApiRendezvousReportController` **不在本树中**。没有 Bean，没有 profile，没有菜单，没有匿名白名单。上面的路径不是现有 API。

## 审核幂等合同（真实端口尚未接通）

相同 `caseId` 不等于外部已经去重。生产端口现在没有副作用。未接通时的再次 `submit` 不能说成已经发生了外部重复，也不能说成端到端已经去重。

将来若接通真实审核，该端口必须把 `(caseId, requestId)` 当作同一次移交：

- `submit(caseId, requestId)` 再次出现时，返回这一次移交的原结果，不另开一单。
- `status(caseId, requestId)` 只核对这一次移交，不另开一单，可以重复调用。
- 超时不证明对方没收到。端口不能因为“没见过这次调用”就新开一单。

本进程只保证下面这些，并且只在本地行写成功时成立：

- 新案件在回执提交后调用一次 `submit`。
- 返回 `NOT_CONNECTED` 时不改状态。这是未接通，不是一次有结果的尝试。同键回放仍可能再调用 `submit`。生产实现没有外部副作用。这不是端到端去重。
- `submit` 得到含糊、抛错（含超时）、无引用的接受、或过早的已处置：状态改为 `UNKNOWN`。之后同键回放只调用 `status`，不再 `submit`。
- 对方接受且引用合法，但本地没有写成 `SENT_TO_REVIEW`：再写一笔 `UNKNOWN`，不留下 `reviewRef`。写成功之后，回放只调用 `status`。
- `UNKNOWN` 上的 `status` 若给出带合法引用的接受，可以进入 `SENT_TO_REVIEW`。这仍是核对，不是第二次 `submit`。
- `UNKNOWN` 这一笔如果也写失败，行会留在 `LOCAL_INTAKE`。那种失败没有被记成已去重。

## 不在本线

不自动封禁。不设阈值、TTL、审核时限或频率窗口。不写全局 IM、社区举报或 `audit_task`。不调用 `ImServiceImpl.report` 或 `ImServiceImpl.block`。不把 `w16_safety_report` 的一对用户行当成案件。不改共享 `RendezvousConsentService`。
