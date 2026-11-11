-- ================= migration_20261111_aigc_filing_config_seed.sql =================
SELECT 'migration_20261111_aigc_filing_config_seed.sql' AS running;
-- 合规(P0-14 / P2-020):生成式 AI 算法备案号公示位。值为空 = 小程序不显示备案号这一行;
-- 拿到备案号后由运营在后台「参数设置」填入(例:网信算备xxxxxxxxxxxxxxxx号),无需发版。
-- 读方:ApiConfigController /api/config/features 下发 aigcFilingNo;小程序「关于」页与 AI 生成入口显示。
-- 只在键缺失时插入空值,不覆盖运维已配置的值,可重复执行。依赖:sys_config(RuoYi 基础表)。
-- ⚠️ 生产执行前必须走 chengyin-db-migrate 的只读预检 + owner 确认,本 PR 不在任何库上执行。
--
-- 落库前只读预检:
--   SELECT config_key, config_value FROM sys_config WHERE config_key = 'aigc.algorithm.filing-no';

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '生成式AI-算法备案号', 'aigc.algorithm.filing-no', '', 'Y', 'admin', NOW(),
       '填写后在小程序「关于」页与 AI 生成入口公示;留空则不显示'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'aigc.algorithm.filing-no');

-- 回读断言:键恰好一行(不校验值)。
-- runner: assert=scalar-true
SELECT COUNT(*) = 1 AS assertion_ok FROM sys_config WHERE config_key = 'aigc.algorithm.filing-no';
