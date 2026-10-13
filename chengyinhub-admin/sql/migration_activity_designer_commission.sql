-- 活动三方分润迁移（商户 / 主题设计者 / 平台）
-- 配套代码：CmsActivityServiceImpl 三方结算、SettlementTask 定时任务、后台设置页可改比例
-- 执行方式：用一个会话（同一连接）source 本文件，第2、3步依赖 LAST_INSERT_ID() 必须同会话执行
-- 注意：执行前请备份 cms_setting、ums_member 表；本文件假定 cms_setting 已存在 id=1 的配置行

-- 1. cms_setting 新增「活动设计者佣金」与「平台账户会员ID」
ALTER TABLE cms_setting
    ADD COLUMN activity_designer_commission DECIMAL(5,2) DEFAULT 0 COMMENT '活动主题设计者佣金（百分比）' AFTER activity_merchant_commission,
    ADD COLUMN platform_member_id BIGINT NULL COMMENT '平台账户会员ID（接收平台分润）' AFTER activity_designer_commission;

-- 2. 新建平台专用账户（接收平台分润）。user_type=1 普通会员，status=1 启用
INSERT INTO ums_member (user_type, nickname, name, status, balance, point, del_flag, create_by, create_time, mark)
VALUES (1, '平台账户', '平台账户', 1, 0, 0, 0, 'system', NOW(), '系统平台分润收款账户');

-- 3. 将平台账户ID写入配置，并设默认比例：商户80% / 设计者10% / 平台10%（后台设置页可随时改）
UPDATE cms_setting
SET platform_member_id = LAST_INSERT_ID(),
    activity_merchant_commission = 80,
    activity_designer_commission = 10,
    activity_platform_commission = 10
WHERE id = 1;

-- 4. 配置定时任务自动结算（活动+主题）。白名单已放行 com.chengyinhub.web.task
--    cron: 每小时整点执行一次；status='0' 正常运行（如需先观察可在后台「定时任务」暂停）
INSERT INTO sys_job (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status, create_by, create_time, remark)
VALUES ('分润结算（活动+主题）', 'DEFAULT', 'settlementTask.run()', '0 0 * * * ?', '3', '1', '0', 'admin', NOW(), '每小时执行活动与主题分润结算');
