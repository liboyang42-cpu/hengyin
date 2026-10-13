-- G5 礼物目录迁移：不可缺失的同事务变更审计。
-- 阻断型迁移：PmsGiftChangeAuditMapper 在 jar 中会写本表；必须在合并/部署该 jar 前执行。
-- 生产执行前按 chengyin-db-migrate 做只读预检（表是否已存在、行数、MySQL 版本），再单独确认执行。

CREATE TABLE IF NOT EXISTS pms_gift_change_audit (
  id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  gift_id           BIGINT       NOT NULL COMMENT 'pms_gift 主键',
  action            VARCHAR(32)  NOT NULL COMMENT 'CREATE/PRICE_CHANGE/STATUS_CHANGE/DETAIL_CHANGE',
  old_point         BIGINT       NULL COMMENT '变更前积分',
  new_point         BIGINT       NULL COMMENT '变更后积分',
  old_status        TINYINT      NULL COMMENT '变更前状态 1可赠送 2停用',
  new_status        TINYINT      NULL COMMENT '变更后状态 1可赠送 2停用',
  reason            VARCHAR(500) NOT NULL COMMENT '操作人填写的变更说明',
  operator_username VARCHAR(64)  NOT NULL COMMENT '后台登录操作人',
  create_time       DATETIME     NOT NULL COMMENT '审计写入时间',
  PRIMARY KEY (id),
  KEY idx_pms_gift_change_audit_gift_time (gift_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='礼物目录不可缺失变更审计';
