-- Round5C(2026-11-03 owner):内容安全全自动。机审没给出结论(检测关闭/繁忙/缺 openid/异常)或先审带图的内容,
-- 不再等人工:进本表自动复检,通过自动发布,违规或微信 suggest=review 自动驳回下架并通知作者,
-- 重试有界(默认 12 次,约 16.5 小时),耗尽仍不公开并通知作者。复检期间内容只有作者可见。
-- 只新建一张队列表 + 注册一个 Quartz 任务;不改、不删、不回填既有表。
-- 阶段:先跑本文件(建表 + 注册任务),再部署含 contentSafetyRecheckTask 的新 jar;
-- 任务注册为暂停(status='1'),新 jar 部署并读回后由运维在后台启用。可重复执行。

CREATE TABLE IF NOT EXISTS content_safety_recheck (
  id BIGINT NOT NULL AUTO_INCREMENT,
  biz_type VARCHAR(40) NOT NULL COMMENT '审核单 biz_type(TOPIC/ACTIVITY/COUPON/COMMUNITY_POST/OFFICIAL_EVENT…)',
  biz_id BIGINT NOT NULL,
  member_id BIGINT DEFAULT NULL COMMENT '作者;复检时按它取最新 openid',
  text_body MEDIUMTEXT DEFAULT NULL COMMENT '待复检文本(作者自己提交的内容)',
  media_json TEXT DEFAULT NULL COMMENT '待复检图片地址 JSON 数组',
  business_review TINYINT NOT NULL DEFAULT 0 COMMENT '1=内容安全通过后仍需人工业务审批(价格/地点/报名、官方活动)',
  state TINYINT NOT NULL DEFAULT 0 COMMENT '0待复检 1通过 2违规驳回 3重试耗尽 4过期',
  attempt_count INT NOT NULL DEFAULT 0,
  max_attempts INT NOT NULL DEFAULT 12,
  next_attempt_at DATETIME NOT NULL,
  last_error_code VARCHAR(64) DEFAULT NULL,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  KEY idx_content_safety_recheck_due (state, next_attempt_at, id),
  KEY idx_content_safety_recheck_biz (biz_type, biz_id, state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='机审未完成内容的自动复检队列(Round5C)';

INSERT INTO sys_job
  (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status,
   create_by, create_time, remark)
SELECT '内容安全自动复检', 'DEFAULT', 'contentSafetyRecheckTask.run()', '21 1/5 * * * ?',
       '3', '1', '1', 'admin', NOW(),
       '每 5 分钟一轮，每轮最多 50 条；通过自动发布，违规/耗尽自动驳回并通知作者'
WHERE NOT EXISTS (
  SELECT 1 FROM sys_job WHERE invoke_target = 'contentSafetyRecheckTask.run()'
);

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = 'content_safety_recheck' AND engine = 'InnoDB') = 1
  AND (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'content_safety_recheck') = 14
  AND (SELECT COUNT(*) FROM sys_job WHERE invoke_target = 'contentSafetyRecheckTask.run()') = 1 AS assertion_ok;
