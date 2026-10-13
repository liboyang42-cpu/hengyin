-- D12 P3-1B: metrics_snapshot 快照表 + MetricsProbeTask 注册。
-- 安全:只 CREATE TABLE IF NOT EXISTS 和幂等 INSERT sys_job，不修改存量表/列，不落 PII。

CREATE TABLE IF NOT EXISTS metrics_snapshot (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  metric_key VARCHAR(96) NOT NULL COMMENT '指标自然键，低基数，不含 URL/用户/订单维度',
  window_start DATETIME NOT NULL COMMENT '采样窗口开始',
  window_end DATETIME NOT NULL COMMENT '采样窗口结束',
  source VARCHAR(64) NOT NULL COMMENT '采样来源，如 app/druid/tomcat/jvm',
  numerator DECIMAL(20,6) NULL COMMENT '分子或计数',
  denominator DECIMAL(20,6) NULL COMMENT '分母',
  value DECIMAL(20,6) NOT NULL COMMENT '指标值',
  threshold_version VARCHAR(64) NOT NULL COMMENT '阈值/口径版本',
  status VARCHAR(16) NOT NULL COMMENT 'OK 或 FAIL',
  sampled_at DATETIME NOT NULL COMMENT '实际采样时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_metrics_snapshot_window (metric_key, window_start, window_end),
  KEY idx_metrics_snapshot_sampled_at (sampled_at),
  KEY idx_metrics_snapshot_status_sampled (status, sampled_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='D12 轻量指标快照，不含 PII';

INSERT INTO sys_job
    (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status, create_by, create_time, remark)
SELECT 'D12指标采样', 'DEFAULT', 'metricsProbeTask.run()', '0 0/5 * * * ?', '3', '1', '0', 'admin', NOW(),
       'D12 MetricsProbeTask 每5分钟采样 Druid/Tomcat/JVM/业务失败并写 metrics_snapshot 非并发运行 告警未接手机前只代表代码侧闭环'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE invoke_target = 'metricsProbeTask.run()');
