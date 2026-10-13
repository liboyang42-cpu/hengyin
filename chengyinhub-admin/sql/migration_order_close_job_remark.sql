-- W9:订正 sys_job #109(orderCloseTask)的 remark —— 原文「默认暂停,jar 部署后启用」已成假话。
-- 执行方式:连生产库走 chengyin-db-migrate skill(只读预检 → 用户确认 → 执行 → 回读复核)。
--
-- 为什么要改这行字:2026-07-17 该任务已启用并实测在跑(sys_job_log 有 status=0 成功记录),
-- remark 却还写着「默认暂停」。过期的 remark 比没有 remark 更坏 —— 下一个来看这行的人会以为
-- 它没在跑而去「启用」一遍,或反过来以为暂停是预期状态而不去管它。
--
-- 顺带把 W9 删除闸对本任务的依赖写死在这里:那道闸的正确性直接押在本任务持续运行上,
-- 而任务被暂停是**零告警**的 —— 唯一能提醒后人的地方就是这行 remark。
--
-- 幂等:重复执行只是把同样的值再写一遍,无副作用。
-- 影响面:仅 sys_job 一行的备注文本,不动 status / cron / invoke_target,不影响调度行为。

UPDATE sys_job
SET remark = 'P0-10 弃单关单:pay_expire_time 已过仍待支付 → 关单(payment_status 0→3)+ 回补库存 + 退积分。2026-07-17 已启用并实测运行(sys_job_log 有成功记录)。★W9 主题/活动删除闸依赖本任务:它一停,弃单永远停在 payment_status=0,主题/活动将永久删不掉且零告警,勿随意暂停。⚠️ pay_expire_time 为 NULL 的单本任务永远扫不到(selectExpiredUnpaidIds 带 is not null),需人工处置(2026-07-17 实测全表 0 条)。',
    update_by = 'w9-remark-fix',
    update_time = NOW()
WHERE invoke_target = 'orderCloseTask.closeExpired()';
