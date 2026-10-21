# W20.21 公平摄取来源（B4-2）

B4-1 的字段合同分支 `cursor/w20-b4-fairness-core` 在 2026-10-21 尚未推送。本文件只记录来源和拒绝规则，不改 `FairnessFactContract`。

## 已核的提交事实

`game_player_node_outcome` 是服务端节点完成权威行。唯一键是 `(game_session_id, member_id, node_id)`，主键 `id` 由插入生成。`status` 只允许 `COMPLETED` 与 `FALLBACK_COMPLETED`。`completion_source` 由 `GameSessionRuntimeServiceImpl.ensureOutcome` 写成 `EVIDENCE_ONLY`、`AUTO_HASH`、`MERCHANT_APPROVAL` 或 `FALLBACK_PLAN`。

`game_session` 没有 `board_id`、`region_id`、`season_id`、`rules_version`。`city_board_release` 有这四项，但没有指向 `game_session.id` 或 `game_player_node_outcome.id` 的列。不能把发布快照套到一次节点完成上。

## 转发

`CityFairnessSourceRelay.forward(outcomeId)` 只读上述行，再决定是否调用已有的 `CityFairnessIngestionService.ingest`。

- 行不存在：回滚或未提交。不调用 `ingest`。
- `status` 不是上述两个提交值：含 `UNKNOWN`。不调用 `ingest`。
- `completion_source` 不是上述四个服务端值：含客户端 `success`。不调用 `ingest`。
- 四项范围键缺任一：不调用 `ingest`，不补默认范围。
- 四项都在行上时才调用 `ingest`。`source` 固定为 `game_player_node_outcome`。`event_id` 是结果主键的十进制。`outing_id` 是 `game-session:` 加上已持久的 `game_session_id`。种类是 `EFFECTIVE_TASK`。相同来源和事件由摄取服务去重。本调用不加新事务，外层回滚会一起撤掉已写入的公平事件。

GPS 误拒和安全工单没有表，也没有写入方。它们是覆盖缺口，不是 0。
