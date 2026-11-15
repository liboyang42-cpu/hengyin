-- ================= migration_20261115_store_safety.sql =================
SELECT 'migration_20261115_store_safety.sql' AS running;
-- P1b store-and-safety(SEC-13 / SEC-14 / SEC-16):
--   1. ums_member_age_attestation:玩家「已满 18 周岁」确认与确认时间(每会员一行,首次时间不覆盖)。
--      不存生日、证件或任何年龄数字;回答未满 18 岁不落库。
--   2. sys_config 种子(只在键缺失时插入,不覆盖运维已配置值):
--      app.agePolicy        = adults(缺省;guardian 预留,监护人流程未建,读到也按 adults 执行)
--      app.filing.icp-no    = ''    (App 备案号;留空则 App 不显示备案行。拿到号后在后台「参数设置」填入)
--      app.support.contact  = ''    (玩家可见的客服联系方式;留空则不显示)
-- 读方:ApiConfigController /api/config/features(appFilingNo / supportContact / agePolicy)、ApiAgeGateController。
-- 可重复执行。依赖:sys_config(RuoYi 基础表)。
-- ⚠️ 生产执行前必须走 chengyin-db-migrate 的只读预检 + owner 确认,本分支不在任何库上执行。
--
-- 回滚(仅在确认无读方后):
--   DROP TABLE IF EXISTS ums_member_age_attestation;
--   DELETE FROM sys_config WHERE config_key IN ('app.agePolicy','app.filing.icp-no','app.support.contact');
--
-- 落库前只读预检:
--   SELECT config_key, config_value FROM sys_config
--    WHERE config_key IN ('app.agePolicy','app.filing.icp-no','app.support.contact');
--   SHOW TABLES LIKE 'ums_member_age_attestation';

CREATE TABLE IF NOT EXISTS ums_member_age_attestation (
  member_id bigint NOT NULL COMMENT 'ums_member.id',
  age_policy varchar(16) CHARACTER SET ascii NOT NULL COMMENT '确认时执行的年龄政策:adults',
  confirmed_at datetime(3) NOT NULL COMMENT '本人确认已满 18 周岁的时间',
  source varchar(32) CHARACTER SET ascii NOT NULL DEFAULT 'app' COMMENT '确认入口:sign_in / app_launch 等',
  client_ip varchar(64) NOT NULL DEFAULT '' COMMENT '确认时的请求 IP(审计)',
  created_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='玩家年龄确认(SEC-14)';

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '年龄政策', 'app.agePolicy', 'adults', 'Y', 'admin', NOW(),
       'adults=只向 18 周岁及以上开放(缺省);guardian 预留,监护人流程未建,当前仍按 adults 执行'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'app.agePolicy');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT 'App 备案号', 'app.filing.icp-no', '', 'Y', 'admin', NOW(),
       '填写后在 App「设置-关于」与登录页法律信息处公示并链接 beian.miit.gov.cn;留空则不显示'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'app.filing.icp-no');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '客服联系方式', 'app.support.contact', '', 'Y', 'admin', NOW(),
       '玩家可见的客服电话或邮箱,显示在社区公约与「关于」页;留空则不显示'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'app.support.contact');

-- 回读断言:三个键各一行,表存在。
-- runner: assert=scalar-true
SELECT COUNT(*) = 3 AS assertion_ok FROM sys_config
 WHERE config_key IN ('app.agePolicy','app.filing.icp-no','app.support.contact');
-- runner: assert=scalar-true
SELECT COUNT(*) = 1 AS assertion_ok FROM information_schema.tables
 WHERE table_schema = DATABASE() AND table_name = 'ums_member_age_attestation';
