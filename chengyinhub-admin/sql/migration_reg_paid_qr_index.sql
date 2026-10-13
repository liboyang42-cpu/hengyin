-- migration_reg_paid_qr_index.sql
-- 核销码补偿任务(verificationQrcodeRepairTask)扫描索引:
-- selectPaidMissingQrcodeIds 按 payment_status + create_time 过滤,现有索引
-- (parent_order / owner_id+participate_date / member_id+request_id)均不覆盖,
-- 每 10 分钟一轮会全表扫描 cms_registration。加 (payment_status, create_time)
-- 联合索引改为范围扫描。MySQL 8 在线建索引不锁表,可逆(DROP INDEX)。
-- 非部署阻塞:任务没有索引也能正确工作,只是慢;建议随本批合并落库。
-- 注意:注释与语句内不要出现 ASCII 分号(执行器按分号拆句,见 2026-07-14 badge 迁移踩坑)

ALTER TABLE cms_registration ADD INDEX idx_reg_paystatus_ctime (payment_status, create_time);
