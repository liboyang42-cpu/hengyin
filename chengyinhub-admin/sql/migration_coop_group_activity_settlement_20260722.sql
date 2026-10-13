-- D22 场次独立团结算修复（2026-07-22）
-- 前置：migration_coop_group_verify_activity_20260722.sql 已执行。
-- 规则 1：同一 topic 下每个 cms_activity 是独立团；玩家核销和商家接待都按 activity 归属，
-- 不再由 topic_id + leader_member_id 推断。
--
-- 部署顺序：本 SQL 必须先于引用 coop_group_member_verify 的新 jar 落库。
-- 不回填旧数据：旧团码及玩家核销均无可信 activity 身份，猜测回填会造成跨团资损；
-- 历史未结算案件须人工对账，不能由新逻辑自动归属。

CREATE TABLE IF NOT EXISTS coop_group_member_verify (
    id                 BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
    topic_id           BIGINT(20) NOT NULL COMMENT '主题(cms_topic.id)',
    activity_id        BIGINT(20) NOT NULL COMMENT '带队场次(cms_activity.id)',
    registration_id    BIGINT(20) NOT NULL COMMENT '已核销主题报名单(cms_registration.id)',
    verifier_member_id BIGINT(20) NOT NULL COMMENT '执行玩家核销的主理人或管理员 memberId',
    verify_time        DATETIME   NOT NULL COMMENT '核销时间',
    PRIMARY KEY (id),
    -- verifyIfNotVerified 已保证报名单仅能核销一次；此键把该业务不变量落到库里，防旁路写入跨场次。
    UNIQUE KEY uk_group_member_registration (registration_id),
    KEY idx_group_member_activity (activity_id),
    KEY idx_group_member_topic (topic_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='带队场次玩家核销事实：报名单属于哪个独立团';

-- 同一商家接待同一 activity 只算一次；leader_member_id 是出码审计信息，不是团身份。
-- 单条 fail-fast 在线 DDL：无法无写锁完成就失败，绝不静默退化为阻塞性锁表。
SET SESSION lock_wait_timeout = 5;
ALTER TABLE coop_group_verify
    DROP INDEX uk_group_verify_activity,
    ADD UNIQUE KEY uk_group_verify_activity_merchant (activity_id, merchant_member_id),
    ALGORITHM=INPLACE,
    LOCK=NONE;
