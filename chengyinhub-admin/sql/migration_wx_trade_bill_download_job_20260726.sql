-- P1-6 N块:微信交易账单 T+1 下载任务(wxTradeBillDownloadTask)注册,暂停态。
-- ★触发时机:必须在 P1-7 真实接入微信商户环境、IWxTradeBillService#downloadAndIngestBill
--    实现落地(不再抛 UnsupportedOperationException)之后,才能把本条 status 改成 '0' 启用按 cron 跑。
-- ★负责人:P1-7 阶段跟进人(城瘾后端);到期日:P1-7 商户资质/密钥就绪后一周内完成启用或书面延期。
-- 现在提前注册为暂停,是为了让门禁(migration_safety_check 门3)满足"有 task bean 必有注册 SQL",
-- 同时避免 P1-6 阶段就被 cron 反复调度、反复抛异常刷日志噪音。
DELETE FROM sys_job WHERE invoke_target = 'wxTradeBillDownloadTask.download()';
INSERT INTO sys_job (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status, create_time, remark)
VALUES ('微信交易账单T+1下载(P1-7启用)', 'DEFAULT', 'wxTradeBillDownloadTask.download()', '0 30 10 * * ?', '3', '1', '1',
        NOW(), 'P1-7接入真实微信商户API后启用;负责人=P1-7跟进人;到期日=商户资质/密钥就绪后一周内');
