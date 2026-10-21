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
5. `GET /api/city-game/fairness/report` 使用当前登录账号。每份报告带 `asOf`、`metricVersion`、`formulaId`、`factRevision`、成员 `t0` 和 N/D。

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
