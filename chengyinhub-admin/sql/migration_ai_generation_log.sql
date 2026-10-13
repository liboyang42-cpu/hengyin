-- AI 生成日志 ai_generation_log(2026-06-27)
-- 走 chengyin-db-migrate skill:只读预检(--describe ai_generation_log 看表是否已存在)后确认再执行。
-- CREATE TABLE IF NOT EXISTS,幂等安全;ENGINE=InnoDB CHARSET=utf8mb4;del_flag 0=存在 2=删除。
-- ⚠️ 阻断型:jar 的 AiGenerationLogMapper XML 引用此表,必须先于部署 jar 执行,否则报 Table doesn't exist。

CREATE TABLE IF NOT EXISTS `ai_generation_log` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `member_id`    BIGINT       NOT NULL COMMENT '会员ID',
  `role`         VARCHAR(16)  NULL COMMENT '当前角色 player/club/merchant',
  `biz_type`     VARCHAR(32)  NOT NULL COMMENT '场景 merchant_plan/theme_draft/node_generate/safety_precheck',
  `provider`     VARCHAR(16)  NULL COMMENT '命中模型 mock/qwen/deepseek',
  `input_brief`  VARCHAR(512) NULL COMMENT '输入摘要(截断)',
  `output_brief` VARCHAR(512) NULL COMMENT '输出摘要(截断)',
  `safety_flag`  TINYINT      DEFAULT 0 COMMENT '0正常 1输入被拦 2输出被拦',
  `latency_ms`   INT          DEFAULT 0 COMMENT '耗时ms',
  `token_est`    INT          DEFAULT 0 COMMENT 'token估算',
  `trace_id`     VARCHAR(40)  NULL COMMENT '本次调用traceId',
  `del_flag`     INT          DEFAULT 0 COMMENT '0存在 2删除',
  `create_time`  DATETIME     NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_ai_gen_member` (`member_id`, `create_time`),
  KEY `idx_ai_gen_biz` (`biz_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI生成日志';
