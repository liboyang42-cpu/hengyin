-- C3 彻底:发奖待补发表
-- 背景:play advanceProgress / roam finish 的发奖(XP/掉落/勋章/券)现为"失败记 error 日志"(可观测),
--       但仍无自动补发。本表让发奖失败落库,定时 job 扫描重试,彻底杜绝"完成了却没拿到奖"。
-- 施工顺序:①执行本迁移建表 → ②发奖 catch 分支除 logger.error 外再 insert 一条 → ③加 quartz 定时补发 job。
--
-- 执行走 chengyin-db-migrate skill(只读预检 → 确认 → 执行)。

CREATE TABLE IF NOT EXISTS reward_pending (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  member_id   BIGINT      NOT NULL                COMMENT '玩家',
  reward_type VARCHAR(32) NOT NULL                COMMENT 'xp / coupon / badge / drop',
  ref_id      BIGINT                              COMMENT '关联 node/topic/coupon id',
  amount      INT                                 COMMENT 'xp 额度 或 券模板 id',
  reason      VARCHAR(128)                        COMMENT '发放场景(节点探索/通关奖励/漫游...)',
  status      TINYINT     DEFAULT 0               COMMENT '0待补 1已补 2放弃',
  retry_count INT         DEFAULT 0               COMMENT '已重试次数',
  create_time DATETIME,
  update_time DATETIME,
  INDEX idx_status (status),
  INDEX idx_member (member_id)
) COMMENT='发奖待补发(C3:发奖失败落表,定时 job 补发,杜绝静默丢奖)';

-- 回滚:DROP TABLE IF EXISTS reward_pending;
