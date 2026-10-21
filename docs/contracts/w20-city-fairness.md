# W20.21 公平与二次有效出行（只读候选）

本组只读。没有 HTTP 入口，没有 Spring Bean，不改 `ConsentService`，不写 `citygame/analytics/value`。

## 依赖

已接受的 CITY 发布域：`36071cdfd55513b59966c5185153eac692a9753d`。本组不读它的参与或积分接口，那两项仍是 `UNAVAILABLE`。

事实合同只放在本目录 `FairnessFactContract`（`city-fairness-fact-v1`）。价值统计组不要在公共目录再写一份。

未接上的来源：

- 没有事件生产者写入 `city_fairness_event`
- 没有已批准的参数行
- 没有把报名二报、重开 App、重连换成 `outing_id`

`UnconnectedFairnessSource` 可调用，结果是 `NOT_COMPUTABLE`，并列出缺的流和 `deltaMillis`、`horizonMillis`、`lowerEffectiveEvents`。

## 可调用链

`new CityFairnessReader().read(asOf, source)`

- `source` 可以是 `MyBatisCityFairnessSource(CityFairnessMapper)` 或测试用的内存实现
- `CityFairnessMapper` 没有 `@Mapper`，XML 在 `mapper/business/CityFairnessMapper.xml`
- 候选表在 `chengyinhub-admin/sql/migration_w20_city_fairness_candidate.sql`，没有 INSERT，本次不执行

参数行必须恰好一行，且 `state=APPROVED`、`source_ref` 非空、`delta_millis`、`horizon_millis`、`lower_effective_events` 都是正数。否则 `NOT_COMPUTABLE`，缺什么就写什么，不填业务默认。

## 计算

窗口是 `(asOf - horizonMillis, asOf]`。`APP_REOPEN`、`RECONNECT`、`REGISTRATION_REPEAT` 不建出行，报名二报不算二次出行。

其余事件必须带真实 `outing_id`。同一个 `event_id` 只计一次；落到两个出行或内容不一致则不可计算。一个出行里的加入时点、队伍人数、行动消耗、新手标记、actor 必须一致，报告按这四项分组。

`EFFECTIVE_TASK` 与 `EFFECTIVE_SERVICE` 去重后的条数达到 `lowerEffectiveEvents`，该出行有效。未达到该下限、又没有目标完成、零积分受阻、挑战补偿退出、GPS 误拒、安全工单或商户验证的出行不进入分组。同一人的有效出行按锚点排序，后一次只有 `anchor >= 上一次计入的锚点 + deltaMillis` 才计入下一次。间隔不够的出行不推进锚点。第二次计入的出行写入 `secondOutings`。

分组里另读：`GOAL_COMPLETED`、有效出行上的 `GAIN`、`ZERO_POINT_BLOCK`、`CHALLENGE_COMPENSATION_EXIT`、`GPS_FALSE_REJECTION`、`SAFETY_TICKET`、带 `verification_millis` 的 `MERCHANT_VERIFICATION`。有效收益合计为 0 时不报集中度百分比。增益或缺验证耗时则整份不可计算。

表是空的且参数已批准时，结果是可计算的空分组，不是缺覆盖。流被标成不可用时，不把空分组当成 0% 成功。
