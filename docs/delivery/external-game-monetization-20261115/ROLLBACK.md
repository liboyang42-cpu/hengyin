# Rollback: external game monetization (2026-11-15)

Moved here from `chengyinhub-admin/sql/rollback_20261115_external_game_monetization.sql` during the roadmap integration: the migration ledger forbids unclassified SQL in the managed folder, and rollbacks are run by hand, never by the runner.

Run only by hand, after a read-only check, to undo `migration_20261115_external_game_monetization.sql`.

```sql
-- 回滚外部游戏变现、节点绑定、结果回执与流量激励日结表（按依赖逆序删除，不碰既有审核表）
DELETE FROM sys_menu WHERE component = 'business/externalgame/monetization';

DROP TABLE IF EXISTS external_game_daily_settlement;
DROP TABLE IF EXISTS external_game_play_session;
DROP TABLE IF EXISTS external_game_node_binding;
DROP TABLE IF EXISTS external_game_entitlement;
DROP TABLE IF EXISTS external_game_order;
DROP TABLE IF EXISTS external_game_monetization_config;
```
