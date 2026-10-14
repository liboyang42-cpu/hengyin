# 巡检任务登记只读核对（F-W3-04）

RuoYi 只在**启动时**装载 `sys_job`。从仓库重建的环境（灾备、预发）曾经一条巡检任务都没有，配了 `ALARM_WEBHOOK` 也收不到巡检告警。

## 仓库里的保障

- `migration_20261012_monitor_missing_jobs.sql`（POST_DEPLOY）：补登记指标采样、UGC 审核积压、提现日对账、结算金额巡检。
- `migration_20261012_merchant_commerce_reconcile_job.sql`（POST_DEPLOY）：商业化支付主动查单。
- `migration_20261013_monitor_remaining_jobs.sql`：补登记退款对账兜底、俱乐部带队场次对账（启用）；分润结算、商家结算 T+7 打款、出发前提醒（**默认暂停**，会出款或给用户发消息，须由资金/运营 owner 核对目标环境后在后台「定时任务」页启用）。
- 以上都只补缺失行，不改已有行的状态与计划（运维可能有意暂停或改过 cron）。
- 应用启动后 `MonitorJobRegistrationCheck` 自检一次：必需巡检任务缺失 → 告警 `巡检任务未登记`（Redis 固定事件 10 分钟限频）；已登记但暂停 → 只记 WARN（暂停可能是有意的，如结算金额巡检）。

## 只读核对脚本

在确认的开发测试库上，使用仓库外权限 600 的客户端配置，明确目标 server_uuid 和测试库后执行：

```sh
python3 scripts/audit-job-registration.py --defaults-file /private/task/test.cnf --database cy_task_test --server-uuid VERIFIED_TEST_UUID --confirm-test-target
```

脚本只读核对七项巡检（与启动自检同一集合）：缺失、重复、暂停、cron 不符、允许并发或 misfire 不符都会返回非零；不自动插入、启用或覆盖任何任务。

## 仍需人工

- 上线前只读回读生产 `sys_job`，核对以上 invoke_target 是否齐全、哪些是有意暂停（需用户授权的只读查询）。
- 新插入的行在**下次重启**后才被调度；重启后跨一个 cron 周期看 `sys_job_log` 首轮记录。
- 真实告警出口（钉钉/企微机器人）是否已在生产注入、能否送达，需要一次人工触发演练。
