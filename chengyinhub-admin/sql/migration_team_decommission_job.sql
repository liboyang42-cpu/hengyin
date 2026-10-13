-- 成团下线:注销散团定时任务(autoRefundTask)
--
-- 背景:
--   sys_job id=100 / invoke_target='autoRefundTask.autoRefundUnformed()' / job_name='成团未成团自动退款'。
--   注册时即为暂停态(status='1',remark 写着「默认暂停,真机验证后启用」),生产从未执行过一次。
--   其 bean AutoRefundTask 已随成团引擎一并删除(见同 PR 的后端改动),若保留这行注册,
--   库里就会引用一个不存在的 bean = 「悬空」,audit_sys_job.py 会报落差;RuoYi 若被人误点启动
--   还会抛 bean not found。
--
-- 幂等:按 invoke_target 删,重复执行无副作用(第二次删 0 行)。
-- 可逆:用 chengyinhub-admin/sql/migration_c3_team_job.sql 重新 insert 即可恢复注册行
--       (但 bean 已删,恢复注册没有意义,除非同时回滚代码)。
-- 风险:低。该任务从未运行,删除不影响任何在途业务;生产实测在途成团订单 = 0。
--
-- ⚠️ 不要动 sys_job id=102(beforeStartNotifyTask)—— 它的 bean 仍在,只是保持暂停态,
--    启用属独立决策。

DELETE FROM sys_job WHERE invoke_target = 'autoRefundTask.autoRefundUnformed()';
