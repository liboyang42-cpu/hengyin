-- 出发前提醒 定时任务注册(I2)
-- 执行方式:连生产库 source 本文件(走 chengyin-db-migrate skill,只读预检后确认再执行)
-- 依赖:含 BeforeStartNotifyTask 的新 jar 已部署
-- 说明:非阻断(仅 sys_job 一条 INSERT,可随时执行或在后台「定时任务」手动添加)。
--      默认 status='1'(暂停):订阅消息配好模板前发送为 no-op,配好 wx.subscribe.tplBeforeStart 后再启用。
--      cron 每小时一次;只读扫描 + 发提醒,不动资金。

INSERT INTO sys_job
    (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status, create_by, create_time, remark)
SELECT '出发前提醒', 'DEFAULT', 'beforeStartNotifyTask.run()', '0 0 * * * ?', '3', '1', '1', 'admin', NOW(),
       'I2 已成团活动出发前订阅提醒(redis去重);默认暂停,配好订阅模板后启用'
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE invoke_target = 'beforeStartNotifyTask.run()');
