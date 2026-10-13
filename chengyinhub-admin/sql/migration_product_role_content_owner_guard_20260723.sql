-- P0:生效内容槽的主体只能是 PLATFORM / MEMBER / CLUB。
--
-- 【为什么要补这条库约束】
-- Controller 与 ProductRoleService 已拒绝 MERCHANT，但它们只能保护新请求。旧版本接口或直连
-- 写入留下的生效 CONTENT + MERCHANT 行仍会被 ProductRoleMapper 读作票款归属，必须由库兜底。
--
-- 【范围】
-- 只约束 status=1 的生效槽。status=0 是历史归属审计，既不会被业务读作当前内容方，也不能在
-- 未核清真实归属前被静默改写。任何发现的生效商家内容槽都必须先人工判定真实内容方，不能靠
-- 本迁移自动改为 PLATFORM/MEMBER/CLUB。
--
-- 【第一步：只读预检，发现生效脏数据即停止，不要执行本文件】
-- SELECT status, COUNT(*) AS row_count
--   FROM product_role
--  WHERE slot = 'CONTENT' AND entity_type = 'MERCHANT'
--  GROUP BY status;
-- 期望：没有 status=1 的行。若有，先逐主题核定内容与票款归属并另开处置 migration；本文件
-- 不做删除、改主体或失效处理。
--
-- 同时确认版本、表量与现有约束：
-- SELECT VERSION();
-- SELECT COUNT(*) AS row_count FROM product_role;
-- SHOW CREATE TABLE product_role;
-- MySQL 必须为 8.0.16+，否则 CHECK 可能只解析不执行。ADD CHECK 会校验存量并持有元数据锁；
-- 表量较大时在低峰执行，且不要承诺 INSTANT 算法。
--
-- 【落库后只读回查】
-- SELECT CONSTRAINT_NAME, CHECK_CLAUSE
--   FROM information_schema.CHECK_CONSTRAINTS
--  WHERE CONSTRAINT_SCHEMA = DATABASE()
--    AND CONSTRAINT_NAME = 'ck_role_active_content_owner';
-- SELECT COUNT(*) AS active_merchant_content_slots
--   FROM product_role
--  WHERE slot = 'CONTENT' AND status = 1 AND entity_type = 'MERCHANT';
--
-- 【回退】
-- ALTER TABLE product_role DROP CHECK ck_role_active_content_owner;
-- 仅在应用层也已回退、且经人工评估后使用；不能把回退当作存量归属处置手段。

ALTER TABLE product_role
    ADD CONSTRAINT ck_role_active_content_owner CHECK (
        slot <> 'CONTENT'
        OR status <> 1
        OR entity_type IN ('PLATFORM', 'MEMBER', 'CLUB')
    );
