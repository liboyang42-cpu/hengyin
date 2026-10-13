-- P2 运营权委托:承接方(俱乐部领队)可改「当日备注」——活动场次加 day_note 字段。
-- 注意:MySQL 8 不支持 ADD COLUMN IF NOT EXISTS(那是 MariaDB 扩展),故用普通 ADD COLUMN;
-- 非幂等,执行前先跑 _precheck_migration_status.sql 确认 cms_activity.day_note 为「待落」再执行。
ALTER TABLE `cms_activity`
  ADD COLUMN `day_note` varchar(500) DEFAULT NULL COMMENT '当日运营备注(承接方领队可改,报备发起人)' AFTER `address`;
