# W20 CITY 乐趣与效用只读统计候选合同

本组只读当前冻结发布上的价值事件，计算显式口径的漏斗、首次有效选择耗时、主动跳过与退出原因、服务评分、单次访问净积分和预算。它不改积分账，不执行资金动作，不建影子钱包，也不写公平性目录。

候选接口没有写入 `SecurityConfig`。响应带 `candidate: true`、`transport: NOT_APPROVED` 和 `contract: CITY_VALUE_READ_V1`。匿名不可用。本文件不批准生产 transport，也不执行 DDL。

## 依赖

- 登录与方法权限：现有 `SecurityUtils`、`@PreAuthorize`、`PermissionService`（`@ss.hasPermi`）。
- 返回包装：`AjaxResult`。业务是否可计算看 `data.status`，不看 HTTP 包装里的“操作成功”。
- 发布事实：`CityBoardMapper.selectCurrentRelease` 与冻结快照。`CityValueReleaseCaller.pull` 在每次报告和事件页读取前被实际调用。
- 持久化端口：`ValueEventMapper` 与 `mapper/business/ValueEventMapper.xml`。表只存在于 `chengyinhub-admin/sql/migration_w20_city_value_candidate.sql`，本次不执行。
- 公平性：`citygame/analytics/fairness` 由 B 独占。本组不创建该目录，也不共享 Java 类型。两边只在各自合同里声明依赖。
- 不依赖也不修改：发布服务、支付与积分账、商户 citynode 核销、W10 二次报名、`pom`、`application*.yml`、workflow、`SecurityConfig`。

## 权限

权限串只用于方法鉴权。候选 SQL 不插入 `sys_menu` 或 `sys_role_menu`，因此默认无人持有 `cityvalue:report:query`。

| 权限 | 入口 |
| --- | --- |
| `cityvalue:report:query` | `GET /business/city-value/report` |
| `cityvalue:report:query` | `GET /business/city-value/events` |
| `cityvalue:report:query` | `GET /business/city-value/result/{id}` |
| `cityvalue:report:query` | `GET /business/city-value/request/{requestId}` |

操作者只来自登录用户。查询参数里的 `actor`、`cohort`、`sample`、`success` 以及其他未列出的键直接拒绝。取消是 `GET /business/city-value/request/{requestId}?intent=cancel`，不另开 POST。

## 链

1. `CityValueReleaseCaller` 读取当前 release。没有发布时返回 `NO_CURRENT_BOARD`，不写孤儿事实。
2. 同一 release 写入一条 `DEFINITION` 和十条 `COVERAGE`。自然键重复且内容相同则回放，不增加分母。
3. 可信服务端调用 `ValueEventIngestor.acceptBehavior` 写入行为事件。去重键是自然身份的 SHA-256，冲突保留原行。
4. `ValueAnalyticsService` 按区域、棋盘、赛季、规则发布和时间窗做有界读取。事件页用绑定操作者与范围的不透明游标。
5. 报告正文写入 `city_value_report`，请求回执写入 `city_value_request`。同一请求号与同一载荷回放原正文；载荷不同返回 `DUPLICATE_REQUEST` 且不覆盖。换账号返回 `ACCOUNT_CHANGED`，不带回另一人的正文或事件。
6. `readResult` 按结果编号回读。调用方必须带上相同的 `regionId` 与 `rulesReleaseId`，否则 `SCOPE_MISMATCH`，不泄露其他范围的正文。

行为事件的生产受信任来源只有 `city-release-fact`，而这个来源只能写定义和覆盖，不能写行为。行为摄入要在构造摄入器时加入被审过的服务端来源后才会接受。这是缺配置，摄入、校验和装配代码已经在。

## 口径

有意义动作和有效选择必须由查询显式给出。空名单拒绝。`click`、`clicks`、`walk`、`walking`、`daily_login`、`rent_login`、`collect_rent`（大小写与连字符忽略）出现在名单里会拒绝整次查询，并且不落请求。这些动作也不能充当漏斗步骤。

覆盖只来自窗口内已接受的该类事件。适配器写的 `MISSING_EMITTER` 不能在已有真实事件时把结果打成 0。没有事件则该段为 `MISSING_COVERAGE`，不写比率，不写访问次数，不写成功。口径缺失为 `MISSING_DEFINITION`，不补默认值。分母大于 0 且该类确有事件时，比率写成 `n/d`，可以是 `0/5`，但不写成百分号，也不写成功布尔值。分母为 0 时省略比率。

漏斗顺序是进入，然后进入之后、且落在名单里的最早有意义动作，然后该动作之后的服务成功，然后服务成功之后各自计算的公共目标和合作目标。缺一边目标时另一边仍可单独计算。没有先完成服务的目标不算转化。

首次有效选择只用 `effectiveChoices`，不回退到有意义动作名单。中位数在偶数个样本上取中间两值的整数平均。窗口内没有任何有意义动作事件时，耗时段是 `MISSING_COVERAGE`，不写 0 毫秒。事件存在但没有访问命中选择名单时，`visitCount` 为 0 并省略中位数。

老手阈值 `veteranMinPriorServiceSuccess` 至少为 1，并且必须和前窗一起给出。前窗结束不晚于报告开始，跨度不超过 31 天。分类按前窗里不同的 `(visitId, serviceId)` 服务成功次数。前窗没有服务成功事件时，分群为 `MISSING_COVERAGE`，不把所有人当成新手。前窗读截断时，整次查询为 `INCOMPLETE`，不分类。

评分存储范围是 0 到 100。报告刻度 `ratingMin`/`ratingMax` 必须成对出现，且最小不大于最大。刻度外的评分剔除，不改写。没有落在刻度内的评分时省略均值。均值写成 `sum/count`。

净积分必须给出 `POINT` 或 `MINOR`。不假设点数，不读账本。`POINT` 不能带币种，`MINOR` 必须带币种。币种对不上时为 `MISSING_UNIT`，省略合计，不做汇率换算。访问预算必须给出币种，不默认 `CNY`。缺事件的访问列在 `incompleteVisits`，不补 0。棋盘 `pointCost` 为 0 只出现在 `boardFacts`，不进入漏斗。

时间窗为含起点、不含终点，跨度大于 0 且不超过 31 天。报告内部读取上限是 1 到 200，多读一行探测截断。截断时状态为 `INCOMPLETE`，不计算比率，并带 `resumeFrom`。同一请求号重试仍返回这次未完成结果。事件页大小是 1 到 50。失败时回显 `retryCursor`，不前进游标。

## 发出点

冻结快照提供赛季预算、服务费、点数成本、任务条数和内容哈希。它不提供玩家行为。下列家族目前没有发出点，适配器把缺口写成 `COVERAGE` / `MISSING_EMITTER`：

- 进入：`playerCurrent` 只读。
- 有意义动作：棋盘域没有发出点。点击、走路、每日登录、收租登录不算。
- 服务成功：不读商户 citynode 核销。
- 公共目标、合作目标：`tasks` 没有可见性字段，也没有完成发出点。
- 主动跳过、主动退出、服务评分：没有发出点。
- 净积分：`playerPoints` 固定 `CITY_POINTS_UNAVAILABLE`。
- 单次访问预算：`season.budget` 是棋盘预算，不是访问预算。

## 取消、重试、换号、重启

请求还没有落库时，取消写入 `CANCELLED`。再次取消仍返回该行。已经是 `REPORTED`、`REJECTED` 或 `INCOMPLETE` 时返回 `CANCEL_REJECTED`，不删事实和报告。之后再用这个请求号做报告会冲突，必须换新请求号。

同一操作者、同一载荷回放原正文。重启后新的服务实例读同一映射，仍回放该行。另一操作者拿到 `ACCOUNT_CHANGED`，响应里没有报告正文和事件列表。

## 缺配置、缺实现、未运行

缺配置：权限未授予；生产行为来源未加入受信任集合；每次查询的有意义动作、有效选择、评分刻度、老手阈值、预算币种和积分单位都由调用方给出；transport 未批准。

缺实现：本组目录内没有只留接口或模型的缺口。行为事件要等真实服务端发出点调用 `acceptBehavior`。

未运行：候选 SQL 未对任何数据库执行；MyBatis XML 未对 MySQL 执行；未做生产发布、DDL、支付或发奖；未跑全量 CI。
