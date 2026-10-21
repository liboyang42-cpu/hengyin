# W20.21 公平与二次有效出行

只读报告。不改 `ConsentService`，不写 `citygame/analytics/value`，不读 W10 报名二报。

## 可调用链

1. `CityFairnessIngestionService.ingest(event)` 写入候选表 `city_fairness_event`。身份是 `(source_ref, event_id)`。相同身份且内容一致则 `DEDUPED`，内容不同则 `CONFLICT` 且不覆盖。事件带 `board_id`、`region_id`、`season_id`、`rules_version`。`APP_REOPEN`、`RECONNECT`、`REGISTRATION_REPEAT` 直接拒绝，不落成出行。
2. `CityFairnessReportService.read(accountId, queryId, asOf, cursor, boardId, regionId, seasonId, rulesVersion)` 只读该账号、该范围的事件。范围四项缺任何一项是 `MISSING`，不填默认范围。没有匹配的 `APPROVED` 行是 `scope-rejected`，不把别的范围算进来。换范围是另一份快照。
3. 参数行必须是该范围唯一一行 `state = 'APPROVED'`，并带正数 `deltaMillis`（Δ）、`horizonMillis`（H）、`graceMillis`（L）、`maxEvents`、`pageSize`、`metricVersion` 和来源 `sourceRef`。缺任一项是 `NOT_COMPUTABLE`，`matureCount` 和 `denominator` 保持空，不写成 0。
4. `CityFairnessReader.read` 在窗口 `(asOf - H, asOf]` 内按 `(source, event_id)` 去重，再按 `outing_id` 归并。`t0` 是该出行最早的有效任务或有效服务时间。`t0 + L` 之前的事件计入；晚于截止的事件记入 `lateEvents`，不进指标。`asOf < t0 + L` 时该出行是 `IMMATURE` / `inside-grace`，还不进入 N/D。宽限结束后才分类。二次有效出行仍按 Δ：后一次只有 `t0 >= 上次计入的 t0 + Δ` 才计入，否则 `IMMATURE` / `inside-delta`。
5. 可计算且已有完成宽限的出行时，报告给出 `matureCount` / `denominator`（N/D）。还没有任何完成宽限的出行时，这两项保持空。每条出行带 `t0`。每份报告带 `metricVersion` 和 `asOf`。
6. 第一次读把结果写入 `city_fairness_read`，键含账号、查询和四个范围字段。同一范围重试回读这份快照。同一查询换 `asOf` 是 `query-conflict`。
7. `GET /api/city-game/fairness/report` 使用当前登录账号和查询上的范围。没有登录是 401。范围未批准是 403。不可计算是 HTTP 422。

生产者必须保证同一个 `(source_ref, event_id)` 不表示两件不同的事。`event_id` 单独不跨来源唯一。

## 候选表

`chengyinhub-admin/sql/migration_w20_city_fairness_candidate.sql` 没有 INSERT，本轮不执行。`L` 列是 `grace_millis`，不是事件条数。`CityFairnessMapper` 没有 `@Mapper`。`UnconnectedFairnessSource` 只留给“流不可用”的阅读器测试，正式服务不调用它。

## 缺实现

| 项 | 状态 |
| --- | --- |
| CITY 任务、服务、GPS 误拒、安全工单、商户验证的发出点调用 `ingest` | 未实现。仓库里没有这些事实的生产者。摄取服务本身已实现 |
| 已批准的 Δ/H/L、`metricVersion`、`maxEvents`、`pageSize` 和范围行 | 缺配置。没有批准值时报告 `MISSING` 或 `scope-rejected`，不造阈值 |
| 客户端提交完成事实的 HTTP 入口 | 不提供。完成事实只能由服务端调用 `ingest` |
| 执行候选 DDL、接通生产库 | 未执行 |

## 未批准配置

Δ、H、L、`metricVersion`、`maxEvents`、`pageSize` 以及 board/region/season/rules 都只来自已批准行。未填时保持空，报告不把它们写成 0。
