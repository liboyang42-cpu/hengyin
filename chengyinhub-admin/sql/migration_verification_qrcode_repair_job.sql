-- migration_verification_qrcode_repair_job.sql
-- 核销码补偿定时任务注册(资损级:用户已扣款但拿不到核销二维码)
-- 走 chengyin-db-migrate skill:只读预检后确认再执行
--
-- 背景:支付回调期 generateVerificationQrcode 失败被吞 -> payment_status=2 已支付但 qrcode_url 为空,
--       且回调幂等门对微信重推直接回 SUCCESS,无自然重试路径,只能靠本任务补跑。
-- 依赖:含 VerificationQrcodeRepairTask 的 jar 已部署(2026-07-11 起在 master)
--       + migration_reg_paid_qr_index.sql 已落库(否则每轮全表扫 cms_registration)
-- 幂等:invoke_target 已存在则跳过
-- cron 每 10 分钟(取类注释的建议值)
--
-- status='0' 注册即启用:任务幂等(generateVerificationQrcode 已生成即短路)、逐单 try/catch、
--   只扫近 7 天、限批 100/轮,空跑成本仅一次带索引 SELECT,无需暂停等人工启用。
--
-- 注意:注释与语句内不要出现 ASCII 分号(执行器按分号拆句,见 2026-07-14 badge 迁移踩坑)

INSERT INTO sys_job
  (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status, create_by, create_time, remark)
SELECT '核销码补偿', 'DEFAULT', 'verificationQrcodeRepairTask.repairMissing()', '0 0/10 * * * ?', '3', '1', '0', 'admin', now(),
       '扫已支付但 qrcode_url 为空的报名单补生成核销码,满批(100/轮)会打 warn 日志需人看'
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM (SELECT job_id FROM sys_job WHERE invoke_target = 'verificationQrcodeRepairTask.repairMissing()' LIMIT 1) e);
