# Rollback: weather event rules (2026-11-14)

Moved here from `chengyinhub-admin/sql/rollback_20261114_weather_event_rule.sql` during the roadmap integration: the migration ledger forbids unclassified SQL in the managed folder, and rollbacks are run by hand, never by the runner. The migration header still names the old path.

Run only by hand, after a read-only check, to undo `migration_20261114_weather_event_rule.sql`.

```sql
-- Rollback for migration_20261114_weather_event_rule.sql
DROP TABLE IF EXISTS weather_event_rule;
```
