-- 双死线两个 Quartz 定时任务自动注册(报告 B1-6 / B1-7)
-- 执行方式:连生产库 source 本文件(走 chengyin-db-migrate skill,只读预检后确认再执行)
-- 影响:向 sys_job 插入两条任务(幂等:按 invoke_target 去重),jar 部署后 RuoYi 调度器启动时自动装载。
-- 省去在后台「定时任务」手动录入两条 invoke_target 的步骤。
-- cron 0 0/10 * * * ? = 每 10 分钟;concurrent=1 禁止并发;misfire_policy=3 放弃错过的执行;status=0 正常。

-- ① 邀约过期:挂起超 expire_time / 已接受超 config_deadline → status=5
INSERT INTO sys_job (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status, create_by, create_time, remark)
SELECT '双死线-邀约过期扫描', 'DEFAULT', 'coopInviteExpireTask.run()', '0 0/10 * * * ?', '3', '1', '0', 'coop-migrate', NOW(), 'B1-6 CoopInviteExpireTask'
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE invoke_target = 'coopInviteExpireTask.run()');

-- ② 招募到期:lifecycle=1 且 recruit_deadline 到点 → 0接受回草稿(0)/≥1接受进定价(2)
INSERT INTO sys_job (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status, create_by, create_time, remark)
SELECT '双死线-招募到期判定', 'DEFAULT', 'topicRecruitDeadlineTask.run()', '0 0/10 * * * ?', '3', '1', '0', 'coop-migrate', NOW(), 'B1-7 TopicRecruitDeadlineTask'
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE invoke_target = 'topicRecruitDeadlineTask.run()');

-- ③ 商家结算 T+7 打款(每天凌晨3点):扫到期待打款结算单 → 打款进商家余额
INSERT INTO sys_job (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status, create_by, create_time, remark)
SELECT '商家结算T+7打款', 'DEFAULT', 'coopSettlementPayoutTask.run()', '0 0 3 * * ?', '3', '1', '0', 'coop-migrate', NOW(), 'B4 CoopSettlementPayoutTask'
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE invoke_target = 'coopSettlementPayoutTask.run()');
