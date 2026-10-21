# W20.21 公平与二次有效出行

公式以 `FairnessFieldContract` 为准（`city-fairness-cohort-v1`）。不改 `ConsentService`，不写 `citygame/analytics/value`，不读 W10 报名二报。

## 口径

- `t0` 是成员第一次服务端确认的有效任务或有效服务完成时间。换队不重置 `t0`。棋盘和新人队列锁在这次完成上。
- 分母 D 是满足 `t0 + H + L <= as_of` 的成员，去掉测试成员和已确认作弊。退出、低活跃不移出分母。
- 分子 N 是 D 中在 `(t0, t0 + H]` 内又完成了另一个有效 `outing_id`，并且该完成不早于 `t0 + Δ` 的成员。
- D 为 0 时报告 `NO_MATURE_SAMPLE`。`matureCount` 和 `denominator` 保持空，不写成 0。
- 事件按 `(source_ref, event_id)` 去重。相同内容重试不覆盖，内容不同是冲突。`event_id` 单独不跨来源唯一。
- `known_at` 晚于 `as_of` 的事实不可见。撤销、确认作弊是新事实，不改已回读的查询。

## 可调用链

1. `CityFairnessIngestionService.ingest(event)` 写入 `city_fairness_event`。身份是 `(source_ref, event_id)`。
2. `CityFairnessReportService.read` 只读该账号、该范围、`known_at <= as_of` 的事件。范围缺项是 `MISSING`。没有该范围的 `APPROVED` 行是 `scope-rejected`。
3. 参数必须带正数 Δ、H、L、`mergeWindowMillis`、`maxEvents`、`pageSize`，以及 `metricVersion` 和 `stateRule = member-cohort-v1`。缺任一项不可计算。
4. 扫码、重开、重连、报名二报都不是新的有效出行。
5. 正式报告是 `GET /api/city-game/fairness/report`。本包不提供 `GET /api/city-game/fairness`。
6. `GET /api/city-game/fairness/outing` 只返回当前登录账号本人的出行依据。分页用已批准行的 `pageSize`。同一 `queryId` 与范围回读同一份快照。

## 接口

两个 GET 都要求登录。查询参数相同，全部可选，名字固定为：

| 参数 | 含义 |
| --- | --- |
| `queryId` | 1–64 位，字符集 `[A-Za-z0-9._-]`。空或非法是 HTTP 400，`data.errorCode` 为 `queryId` |
| `asOf` | 毫秒。首次计算必填且 `>= 0`。回读可省略。与已存快照不一致是 HTTP 409，`data.errorCode` 为 `query-conflict` |
| `cursor` | 上一页的 `nextCursor`。空表示从头。非十进制数字是 HTTP 400，`data.errorCode` 为 `cursor` |
| `boardId` | 已批准范围 |
| `regionId` | 已批准范围 |
| `seasonId` | 已批准范围 |
| `rulesVersion` | 已批准范围 |

响应头 `X-Chengyin-Candidate: city-fairness`。JSON 顶层固定为 `code`、`msg`、`data`、`candidate`（`true`）。`code` 与 HTTP 状态相同。HTTP 200 时 `msg` 为 `操作成功`。非 200 仍带 `data`。

| HTTP | `code` | 条件 | `data.errorCode` 或 `data.status` |
| --- | --- | --- | --- |
| 200 | 200 | 可计算 | `data.status` = `COMPUTABLE` |
| 400 | 400 | `queryId`、`cursor` 或首次 `asOf` 非法 | `data.errorCode` |
| 401 | 401 | 未登录 | `data.errorCode` = `UNAUTHORIZED` |
| 403 | 403 | 该范围没有 `APPROVED` 行 | `data.errorCode` = `scope-rejected`。不落快照 |
| 409 | 409 | 同一账号、同一 `queryId`、同一范围的 `asOf` 不同 | `data.errorCode` = `query-conflict` |
| 422 | 422 | 不可计算 | `data.status` = `NOT_COMPUTABLE`，`msg` 为 `NOT_COMPUTABLE`。`data.errorCode` 不出现 |
| 503 | 503 | 读 `city_fairness_parameter`、`city_fairness_event` 或 `city_fairness_read` 失败 | `data.errorCode` 为表名。不落快照 |

范围四项有空白时 HTTP 422，`data.missing` 含对应参数名，不落快照。缺 Δ、H、L、`mergeWindowMillis`、`stateRule`、`metricVersion`、`maxEvents`、`pageSize` 同样是 422，计数保持空。

`/report` 按组分页。`/outing` 按本人出行分页。`cursor` 不写入快照，两个接口各自偏移。

### `GET /api/city-game/fairness/report` 的 `data`

| 字段 | 类型 |
| --- | --- |
| `accountId` | number |
| `queryId` | string 或 null |
| `asOf` | number 或 null |
| `metricVersion` | string 或 null |
| `formulaId` | string，可计算时为 `city-fairness-cohort-v1` |
| `factRevision` | number 或 null。可见事实里最大的 `known_at`，撤销剔除前取值 |
| `matureCount` | number 或 null。D 为 0 时为 null |
| `denominator` | number 或 null。D 为 0 时为 null |
| `boardId` | string 或 null |
| `regionId` | string 或 null |
| `seasonId` | string 或 null |
| `rulesVersion` | string 或 null |
| `status` | `COMPUTABLE` 或 `NOT_COMPUTABLE` |
| `maturity` | `COUNTED`、`NO_MATURE_SAMPLE` 或 `MISSING` |
| `missing` | string 数组 |
| `groups` | 当前页的组 |
| `members` | 范围内全部成员，与 `outingClasses` 同一数组 |
| `outingClasses` | 与 `members` 相同 |
| `secondOutings` | 计入分子的第二次出行 |
| `lateEvents` | `occurred_at` 晚于 `t0 + H` 的事件 |
| `cursor` | string 或 null |
| `nextCursor` | string 或 null。没有下一页时为 null |
| `readBack` | boolean。同一 `queryId` 再次读取已存快照时为 true |
| `errorCode` | 仅在上面表格写明时出现 |

`groups[]`：`joinTime`、`teamSize`、`actionCost`、`newcomer`、`goalCompletions`、`effectiveGain`、`topActorGain`、`totalGain`、`zeroPointBlocks`、`challengeCompensationExits`、`gpsFalseRejections`、`safetyTickets`、`merchantVerifications`、`merchantVerificationMillis`。

`members[]` 与 `outingClasses[]`：`outingId`、`actorId`、`maturity`、`reason`、`t0`、`newbieQueue`、`secondOutingId`。`maturity` 为 `MATURE`、`IMMATURE` 或 `EXCLUDED`。`reason` 为 `second-outing`、`no-second-outing`、`awaiting-maturity`、`confirm-cheat`、`test` 或 `no-t0`。

`secondOutings[]`：`actorId`、`outingId`、`anchorMillis`、`joinTime`、`teamSize`、`actionCost`、`newcomer`。

`lateEvents[]`：`outingId`、`eventId`、`occurredAt`。

### `GET /api/city-game/fairness/outing` 的 `data`

只含当前登录账号的行。其他账号的 `outingId` 不出现。快照按账号分开存放，不能读到别人的快照。

| 字段 | 类型 |
| --- | --- |
| `accountId` | number。登录账号 |
| `queryId` | string 或 null |
| `asOf` | number 或 null |
| `metricVersion` | string 或 null |
| `formulaId` | string 或 null |
| `factRevision` | number 或 null |
| `boardId` | string 或 null |
| `regionId` | string 或 null |
| `seasonId` | string 或 null |
| `rulesVersion` | string 或 null |
| `status` | `COMPUTABLE` 或 `NOT_COMPUTABLE` |
| `maturity` | 本人的 `MATURE`、`IMMATURE` 或 `EXCLUDED`。本人没有分类时用报告的 `NO_MATURE_SAMPLE` 或 `MISSING` |
| `t0` | number 或 null。本人第一次有效完成的 `occurred_at` |
| `missing` | string 数组 |
| `outings` | 当前页，只含本人 |
| `cursor` | string 或 null |
| `nextCursor` | string 或 null |
| `readBack` | boolean |
| `errorCode` | 仅在非成功且服务给出错误码时出现 |

`outings[]` 每个 `outing_id` 归并后一行。同一 `outing_id` 多次有效完成只留最早的一次。

| 字段 | 类型 |
| --- | --- |
| `outingId` | string |
| `t0` | number 或 null |
| `valid` | boolean。至少一次 `EFFECTIVE_TASK` 或 `EFFECTIVE_SERVICE` 为 true |
| `reason` | `first-outing`、`second-outing`、`inside-delta`、`after-horizon`、`before-t0` 或 `not-effective` |
| `inWindow` | boolean。`valid` 且锚点落在 `(t0, t0+H]` 并且 `>= t0+Δ` 时为 true |
| `maturity` | 本人的 `MATURE`、`IMMATURE` 或 `EXCLUDED` |
| `metricVersion` | 与 `data.metricVersion` 相同 |
| `factRevision` | 与 `data.factRevision` 相同 |

`first-outing` 是形成 `t0` 的那次出行，`inWindow` 为 false。`inside-delta` 落在 `(t0, t0+H]` 但早于 `t0+Δ`。`second-outing` 才是窗口内且不早于 `t0+Δ` 的另一次有效出行。未成熟成员仍返回这些行，不进入报告分子分母。

## 候选表

`chengyinhub-admin/sql/migration_w20_city_fairness_candidate.sql` 没有 INSERT，本轮不执行。

## 缺实现

| 项 | 状态 |
| --- | --- |
| CITY 任务、服务、GPS、安全工单、商户验证的发出点调用 `ingest` | 未实现 |
| 已批准的 Δ/H/L、合并窗、`stateRule`、`metricVersion` 和范围行 | 缺配置 |
| 执行候选 DDL | 未执行 |

## 未批准配置

Δ、H、L、`mergeWindowMillis`、`stateRule`、`metricVersion`、`maxEvents`、`pageSize` 以及 board/region/season/rules 只来自已批准行。未填时保持空。
