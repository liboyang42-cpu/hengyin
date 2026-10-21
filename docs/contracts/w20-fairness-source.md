# W20.21 公平摄取来源（B4-2）

主源是 `city_onsite_task_result`，按 `r.instance_id = i.id`、`r.request_id = q.request_id` 连接 `city_onsite_task_instance` 与 `city_onsite_task_request`。`game_player_node_outcome` 没有棋盘范围列，只作遗留说明，不接入 `ingest`。

源模块提交 `714039270128cefa6739a79223f927d4017962c0` 在 2026-10-21 无法从 origin 取到。本目录不引用、不重写该执行器。

链的基线是 `5af8ba54`。`GET /api/city-game/fairness/outing` 与 `first-outing` 等归并原因属于 B4-1，本适配器不改 `FairnessFieldContract`。

本适配器需要、但该合同里没有的项：`countsAsEffectiveOuting`、`verifiedAt`、`receivedAt`、`releaseId`、`contentHash`、`SOURCE_UNBOUND`、`TIMEZONE_UNVERIFIED`。

## 固定映射

`rulesVersion` 只等于 `rules_release_id`。`release_id` 是数值行号，`content_hash` 是冻结内容，两者不写入 `rulesVersion`。

`source_ref` 为 `CITY_ONSITE_TASK_RESULT_V1`。`event_id` 为 `r.id`。`occurred_at` 与 `known_at` 都取 `r.created_at` 的整秒；该列不是提交时间戳，没有毫秒。`verified_at` 取 `r.evidence_checked_at`，不写入 `verification_millis`。`received_at` 是适配器时钟的整秒，单独落在来源回执，不写入发生时间。

会话时区不是明确偏移（含 `SYSTEM`、含带夏令时的命名时区）时返回 `TIMEZONE_UNVERIFIED`，不换算、不调用 `ingest`。

## 计次

到场任务 `PASSED` 不自动成为有效出行。同范围须有已批准的 `counts_as_effective_outing = 1`，缺行则关闭。

源表没有 `outing_id`。`team_id`、`session_id` 不改名为出行。公平侧出行只在实例仍为 `ACTIVE` 且 `accepted_by` 为服务端本人时绑定，范围从实例行复制。一个实例一行。完成后不按时间或地点回填。没有绑定是 `SOURCE_UNBOUND`。`APP_REOPEN` 不建出行、不写有效事件。

撤销没有本源字段。历史 `PASSED` 保留，不因离队、门店暂停或证据过期删除。

## 覆盖缺口

`outing_id`、新手标签、人数、加入时点、收益、GPS 误拒、安全工单、核验耗时。这些不是 0。
