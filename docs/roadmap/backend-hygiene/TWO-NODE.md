# Running the backend on two nodes (R9 BE-26/BE-27/BE-28)

## What already makes two nodes safe

- **One scheduling leader.** `MySqlSchedulerCoordinator` (quartz module) takes a MySQL session lock (`GET_LOCK`) every 2 s. Only the node holding it starts Quartz (`LeaderJobsQuartzConfig` sets `autoStartup=false`; the leader calls `scheduler.start()`), and every Quartz fire and coordinator plan goes through `coordinator.execute`, which re-checks leadership, the leader epoch and a per-task lock before any business code runs. A standby takes over within seconds when the leader's MySQL session ends, and never replays the old leader's fire time.
- **R9:** the five Spring `@Scheduled` methods (external-game review timeout, IM webhook nonce purge and dispatch, user-invoice refund reconciliation, self-play xpay reconcile) now return immediately unless this node is the scheduling leader (`SchedulingLeadership.allows`). Before R9 they ran on every node. `TwoNodeSchedulingTest` fails if a new `@Scheduled` method skips the check.
- **R9:** every `sys_job` has its own start second (`migration_20261115_stagger_all_sys_jobs.sql`; `SysJobStaggerPlanTest` simulates 24 h). The pool-exhaustion spike (BE-28) cannot recur from job alignment; it is independent of the node count because only the leader runs jobs.

## Optional: Quartz JDBC cluster store (off by default)

`QuartzClusterConfig` replaces the commented-out RuoYi `ScheduleConfig`. It switches Quartz to the JDBC job store in cluster mode (`isClustered=true`, `instanceId=AUTO`, check-in 15 s, misfire threshold 12 s, table prefix `QRTZ_`). Execution stays leader-fenced either way; what it adds is a shared, durable trigger store, so a standby that becomes leader sees the same misfire state and the schedule is visible in the `QRTZ_` tables.

To turn it on (both nodes, same values):

1. Once, on the database: run `sql/quartz.sql` (creates the 11 `QRTZ_` tables). Read-only pre-check: `SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME LIKE 'QRTZ\_%';` → expect 0 before, 11 after.
2. Set `chengyin.quartz.cluster.enabled=true` (env `CHENGYIN_QUARTZ_CLUSTER_ENABLED=true` with Spring relaxed binding) on both nodes.
3. Keep `chengyin.jobs.enabled=true` on both nodes; the leader election decides who runs.
4. Clocks on both nodes must be NTP-synced (Quartz cluster check-in and the fire-time fence both compare times).
5. Rollback: set the flag back to false and restart; the `QRTZ_` tables can stay.

## Other two-node prerequisites (not code, owner/ops)

- Shared Redis (sessions, rate limits, caches) — already external.
- Uploaded files: the local upload path (`ruoyi.profile`) must be shared storage or object storage on both nodes.
- Load balancer health check: `/actuator/health` (management port, see below).
- Druid pool: each node has its own pool (`CHENGYIN_DB_POOL_MAX_ACTIVE`, default 40); check MySQL `max_connections` ≥ 2 × pool + headroom.

## Metrics (BE-27)

- Prometheus format is served only at `http://<CHENGYIN_MANAGEMENT_ADDRESS>:<CHENGYIN_MANAGEMENT_PORT>/actuator/prometheus` (address defaults to `127.0.0.1`). On the public port the path answers 404 (`MetricsEndpointGuard`), and the scrape is opened without a login only on the management port.
- Default: management port = application port, so `http://127.0.0.1:8080/actuator/health` (used by `ci/check_deploy_health.py`) keeps working and Prometheus is effectively off. To enable: set `CHENGYIN_MANAGEMENT_PORT=9091` and move the deploy health check to `http://127.0.0.1:9091/actuator/health` in the same change (deploy files are owner-controlled).
- Useful series: `http_server_requests_seconds_bucket{uri,status,le}` (latency, SLO buckets 100 ms/300 ms/1 s/3 s/10 s), `cy_api_envelope_total{uri,code_class,kind}` (envelope outcome; this API answers most failures as HTTP 200), JVM and process meters, and the existing `cy_game_*` SLO meters. The Druid connection pool is not exported to Micrometer; it stays on the Druid console (`CHENGYIN_DRUID_CONSOLE_ENABLED`).
- Error rate: `sum(rate(cy_api_envelope_total{kind="SYSTEM"}[5m])) / sum(rate(cy_api_envelope_total[5m]))`. Business refusals are `kind="BUSINESS"` and should not page anyone.
