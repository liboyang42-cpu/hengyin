-- AI 组题候选快照 ai_merchant_match_snapshot(2026-06-27)
-- 走 chengyin-db-migrate skill:只读预检后确认再执行。
-- CREATE TABLE IF NOT EXISTS,幂等安全;del_flag 0存在 2删除。
-- ⚠️ 阻断型:jar 的 AiCreatorService 组题写此表,必须先于部署 jar 执行,否则报 Table doesn't exist。

CREATE TABLE IF NOT EXISTS `ai_merchant_match_snapshot` (
  `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `member_id`        BIGINT       NOT NULL COMMENT '会员ID',
  `trace_id`         VARCHAR(40)  NOT NULL COMMENT '关联ai_generation_log.trace_id',
  `idea`             VARCHAR(512) NULL COMMENT '用户一句话想法',
  `conditions_json`  TEXT         NULL COMMENT '筛选条件(lat/lng/radius/duration/needCoupon)',
  `candidates_json`  TEXT         NULL COMMENT '候选商家Top-N(含距离/库存/审核态)',
  `choice_json`      TEXT         NULL COMMENT 'AI选择与理由',
  `del_flag`         INT          DEFAULT 0 COMMENT '0存在 2删除',
  `create_time`      DATETIME     NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_ai_snap_member` (`member_id`, `create_time`),
  KEY `idx_ai_snap_trace` (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI组题候选快照';
