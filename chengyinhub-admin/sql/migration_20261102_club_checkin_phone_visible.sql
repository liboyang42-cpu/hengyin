-- 活动核销员「可见客户手机号」开关 club_role_assignment.customer_phone_visible(2026-11-02 用户裁决,ROUND3B 手机号)
-- 执行方式:走 chengyin-db-migrate,只读预检后确认。部署新 jar 前先落库(新 jar 读写该列)。
-- 语义:只对 EVENT_CHECKIN(活动核销员)有效;1 = 本场名册上可见完整手机号并可拨打。默认 0(否)。
--       主理人、副主理人、本场领队的完整号码权限来自固定角色策略,不读本列。
-- 可重复执行:加列先查 information_schema。不回填 —— 存量委派一律保持「否」。

SET @cra_phone_visible_exists := (
    SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='club_role_assignment' AND column_name='customer_phone_visible');
SET @cra_phone_visible_sql := IF(@cra_phone_visible_exists=0,
    'ALTER TABLE club_role_assignment ADD COLUMN customer_phone_visible TINYINT NOT NULL DEFAULT 0 COMMENT ''活动核销员可见客户完整手机号 0否 1是'' AFTER version',
    'SELECT 1');
PREPARE cra_phone_visible_stmt FROM @cra_phone_visible_sql;
EXECUTE cra_phone_visible_stmt;
DEALLOCATE PREPARE cra_phone_visible_stmt;

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='club_role_assignment' AND column_name='customer_phone_visible')=1 AS cra_phone_visible_ready;

-- 回退:保留列(旧 jar 不读它)。禁止 DROP —— 会丢掉主理人的授权选择。
