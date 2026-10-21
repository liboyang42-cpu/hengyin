# W20.21 公平与二次有效出行

只读报告。不改 `ConsentService`，不写 `citygame/analytics/value`，不读 W10 报名二报。

## 可调用链

1. `CityFairnessIngestionService.ingest(event)` 写入候选表 `city_fairness_event`。身份是 `(source_ref, event_id)`。相同身份且内容一致则 `DEDUPED`，内容不同则 `CONFLICT` 且不覆盖。`APP_REOPEN`、`RECONNECT`、`REGISTRATION_REPEAT` 直接拒绝，不落成出行。
2. `CityFairnessReportService.read(accountId, queryId, asOf, cursor)` 只读该账号的事件。参数必须是唯一一行 `state = 'APPROVED'`，并带正数 `deltaMillis`、`horizonMillis`、`lowerEffectiveEvents`、`maxEvents`、`pageSize` 和来源 `sourceRef`。缺任一项是 `NOT_COMPUTABLE`，不填默认值。读到 `maxEvents + 1` 行则记 `event-bound`，不截断后当成功。
3. `CityFairnessReader.read` 在窗口 `(asOf - horizonMillis, asOf]` 内按 `(source, event_id)` 去重，再按 `outing_id` 归并，用 Δ/H/L 算二次有效出行。
4. 分类：报告不可计算是 `MISSING`。有效出行是 `MATURE` / `effective`。未达到 L 是 `IMMATURE` / `below-lower-bound`。已有效但距上次计入锚点不足 Δ 的，二次出行记 `IMMATURE` / `inside-delta`。没有出行的可计算窗口是 `EMPTY`，不是 0% 成功。
5. 第一次读把结果写入 `city_fairness_read`，键是 `(account_id, query_id)`。同一查询重试返回这份快照（`readBack=true`）。同一查询换 `asOf` 是 `query-conflict`。游标只切这一份快照里的分组。
6. `GET /api/city-game/fairness/report?queryId&asOf&cursor` 使用当前登录账号。没有登录是 401。不可计算的报告是 HTTP 422，body 里带缺项。这个路由没有加入匿名放行。

生产者必须保证同一个 `(source_ref, event_id)` 不表示两件不同的事。`event_id` 单独不跨来源唯一。

## 候选表

`chengyinhub-admin/sql/migration_w20_city_fairness_candidate.sql` 没有 INSERT，本轮不执行。`CityFairnessMapper` 没有 `@Mapper`。`UnconnectedFairnessSource` 只留给“流不可用”的阅读器测试，正式服务不调用它。

## 缺实现

| 项 | 状态 |
| --- | --- |
| CITY 任务、服务、GPS 误拒、安全工单、商户验证的发出点调用 `ingest` | 未实现。仓库里没有这些事实的生产者。摄取服务本身已实现 |
| 已批准的 Δ/H/L/`maxEvents`/`pageSize` 行 | 缺配置。没有批准行时报告 `MISSING`，不造阈值 |
| 跨账号的城市范围 | 未实现。当前只读登录账号自己的 `actor_id` |
| 客户端提交完成事实的 HTTP 入口 | 不提供。完成事实只能由服务端调用 `ingest` |
| 执行候选 DDL、接通生产库 | 未执行 |
