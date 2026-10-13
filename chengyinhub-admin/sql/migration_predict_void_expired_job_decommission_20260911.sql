-- 摘掉 sys_job #128「竞猜轮次超时作废」的注册 —— 它跑在代码前面了。
--
-- 观测(2026-09-11 直接查生产库,不是推断):
--   · sys_job #128 invoke_target='predictVoidExpiredTask.run()',cron='0 0 3 * * ?',
--     status='0'(启用),注册于 2026-09-07 08:48。
--   · sys_job_log 共 2 次执行,**两次都失败**:2026-09-10 03:00 与 2026-09-11 03:00,
--     异常同为 NoSuchBeanDefinitionException: No bean named 'predictVoidExpiredTask'。
--
-- 病根不是「漏了注销」,而是**注册 SQL 先于代码落库**:
--   带这个 bean 的 PredictVoidExpiredTask.java 在 PR #1062
--   ([历史分支标识已移除])里,那个 PR 今天才开、还没合。
--   按仓库流程「带迁移的 PR 合并前先落库」,注册本身没做错,只是它那一批 SQL 里
--   这一条不该在代码到生产之前跑 —— 注册任务这类 DML 的前提是 bean 已经部署。
--
-- 为什么是删掉而不是暂停:
--   暂停(status='1')等于给未来的自己派一个没有到期日的活,而本仓已经为这个形态
--   吃过亏(piiBackfillTask 注册即暂停、从没人触发)。删掉之后,#1062 自己带的
--   migration_predict_void_expired_job_20260906.sql 是 DELETE+INSERT 的幂等写法,
--   合并前按常规流程落库就会把它原样装回来,且那时 bean 已随 jar 一起到位。
--
-- ⚠️ 因此 #1062 合并前必须落它自己那一批迁移(它已声明 ③)。
--    漏了的话这条任务会变成「代码在、注册不在」的**静默**缺失 ——
--    比现在这种每天报一次错的形态更难发现。合并后请跑 audit_sys_job.py 对账。

DELETE FROM sys_job WHERE invoke_target = 'predictVoidExpiredTask.run()';

-- ======================= 写后断言 =======================
-- 断言:这条注册已经不在了。不满足就喂 JSON_EXTRACT 一段非 JSON,让 MySQL 当场报错
-- —— runner 不回显结果集,SELECT COUNT(*) 当断言等于没断言。
SELECT JSON_EXTRACT(IF(COUNT(*) = 0, '[]', 'ASSERT_FAILED__predict_job_still_registered'), '$[0]')
       AS predict_job_decommissioned
FROM sys_job
WHERE invoke_target = 'predictVoidExpiredTask.run()';
