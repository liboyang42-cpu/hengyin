-- D22 团核销场次归属修复（2026-07-22）
-- 前置：migration_coop_group_verify.sql 必须已先执行，创建 coop_group_verify 基表。
-- 执行：走 chengyin-db-migrate：只读预检 -> 风险回报 -> 用户最终确认 -> 执行 -> DESCRIBE 回读。
--
-- 原唯一键只含 (topic_id, merchant_member_id, leader_member_id)，同一领队在同一主题下
-- 为两个承接俱乐部带队时，第二场的商家接待会被 INSERT IGNORE 静默吞掉，且无法在俱乐部 360
-- 可靠归属。团码 v2 改为签入 cms_activity.id，本迁移把该不可变身份写进台账并纳入幂等键。
--
-- 存量行刻意不回填 activity_id：旧团码未签场次，不能由 topic/leader 猜测归属；错误回填比
-- 暂不展示更危险。俱乐部 360 仅展示本迁移后具有 activity_id 的台账事实。
-- 结算仍按既有 topic+leader 口径读取台账，不在本迁移中重算任何历史金额。

-- 先把本连接的元数据锁等待限制为 5 秒：若有长事务占表，宁可失败重试也不排队卡业务。
SET SESSION lock_wait_timeout = 5;

-- 必须保持为【单条】多动作 ALTER：run_migration.py 逐句自动提交，拆成两条会在中断后
-- 留下「已有列、仍是旧唯一键」的半迁移状态，重跑又会因重复列无法续跑。
-- ALGORITHM=INPLACE、LOCK=NONE 是 fail-fast 合约：不能在线无写锁完成就报错，不降级为阻塞性 DDL。
ALTER TABLE coop_group_verify
    ADD COLUMN activity_id BIGINT(20) NULL COMMENT '签发团码时绑定的带队场次(cms_activity.id)' AFTER topic_id,
    DROP INDEX uk_group_verify,
    ADD UNIQUE KEY uk_group_verify_activity (activity_id, merchant_member_id, leader_member_id),
    ADD KEY idx_group_verify_activity (activity_id),
    ALGORITHM=INPLACE,
    LOCK=NONE;
