-- ============================================================
-- L 批待部署迁移整合(2026-06-27)
-- 在【生产服务器】执行(本机出口IP被库白名单拒,连不上)。
-- 全部幂等:ALTER 若报 "Duplicate column name 'club_id'" = 已加过,忽略即可;
--           sys_job 均 WHERE NOT EXISTS,重复跑不会重复插。
-- 顺序:先 ① 阻断型 schema,再部署 jar;② 定时任务可任意时刻(默认暂停)。
-- ============================================================

-- ① 【阻断型·必须先于 jar】俱乐部自建勋章归属列(L5)
ALTER TABLE growth_badge
    ADD COLUMN club_id BIGINT NULL COMMENT '俱乐部ID(俱乐部自建勋章归属;NULL=平台全局)';

-- ② 【非阻断·默认暂停】T+N 自动结算定时任务(L3)
INSERT INTO sys_job
    (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status, create_by, create_time, remark)
SELECT 'T+N自动结算', 'DEFAULT', 'autoSettlementTask.run()', '0 30 2 * * ?', '3', '1', '1', 'admin', NOW(),
       'L3 待结算明细超T+N天自动生成批次并确认打款;默认暂停,核对后启用'
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE invoke_target = 'autoSettlementTask.run()');

-- ③ 【非阻断·默认暂停】出发前提醒定时任务(I2)
INSERT INTO sys_job
    (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status, create_by, create_time, remark)
SELECT '出发前提醒', 'DEFAULT', 'beforeStartNotifyTask.run()', '0 0 * * * ?', '3', '1', '1', 'admin', NOW(),
       'I2 已成团活动出发前订阅提醒(redis去重);默认暂停,配好订阅模板后启用'
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE invoke_target = 'beforeStartNotifyTask.run()');
