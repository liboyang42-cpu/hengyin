-- sys_job 暂停任务启用(2026-09-15 用户裁决)—— 本文件只包含依赖已就绪的任务。
--
-- 启用范围 = 102 出发前提醒。批内其余三个刻意不在本文件里(依赖未就绪,只写人工准备清单,
-- 见 worktree收口_0915/暂停定时任务启用_报告.md 的「上线前必须人工准备」):
--   · 111 结算批次金额巡检:alarm.webhook(ALARM_WEBHOOK)未被证明已在生产注入
--     (2026-07-22 只读查证 unit/drop-in/JVM 参数均无证据),且尚无一次人工核验 ——
--     「仅告警、不改账」的任务没有可达出口等于没上线;
--   · 120 微信交易账单下载:IWxTradeBillService#downloadAndIngestBill 仍是 P1-7 骨架
--     (调用即抛 UnsupportedOperationException),需真实商户资质/密钥联调后另行启用;
--   · 122 漫游完成召回:wx.subscribe.tplRoamRecall(后端)与小程序 utils/subscribe.js
--     roamRecall 均为空、CHENGYIN_ROAM_RECALL_ENABLED 默认 false —— 配齐前一启用就是空跑。
--
-- 102 依赖就绪证据(代码/配置层):
--   · wx.subscribe.tplBeforeStart 已配置(application.yml 与小程序 subscribe.js activityStart 同一个模板 ID);
--   · 报名页/官方详情页已有 requestSubscribeMessage 授权入口;
--   · 扫描不再依赖已下线的成团引擎(selectTeamFormedStartingSoon 只看 mode=1 + 24h 窗口);
--   · 订阅发送为 best-effort:未授权/失败按「票×人」留痕且不写去重键,下一小时重试(有既有单测锁定)。
-- 首轮前人工核验(不阻塞本 SQL 落库):模板在公众平台真实有效、线上小程序包含授权入口、小流量看首轮送达日志。
--
-- 形状:幂等 UPDATE —— job_id + invoke_target 双精确匹配,且仅当当前仍是暂停态才改;
--       重放 0 行、不刷新 update_time。精确匹配显式 CAST AS BINARY:sys_job 是
--       utf8mb4_general_ci(忽略大小写/尾空格),与 Quartz 大小写敏感的 bean 查找不是一回事
--       (合同沿用 migration_merchant_z_quartz_jobs_pause_repair_20260826.sql)。
--
-- ⚠️ 生效边界:直接 UPDATE 只改持久态,当前 JVM 里已装载的 Quartz 调度器不会立即 reload ——
--    落库后需在后台「定时任务」页对 102 执行一次「启用」(或受控重启),再跨 cron 观察首轮 sys_job_log。

UPDATE sys_job
SET status = '0',
    update_by = 'admin',
    update_time = NOW(),
    remark = '2026-09-15 用户裁决启用(出发前提醒);订阅模板 wx.subscribe.tplBeforeStart 两端已配置;首轮请查发送日志确认送达'
WHERE job_id = 102
  AND CAST(invoke_target AS BINARY) = CAST('beforeStartNotifyTask.run()' AS BINARY)
  AND status <> '0';

-- 执行后自检(可红):job_id=102 必须就是 beforeStartNotifyTask.run() 且已置启用;否则返回 0,runner 失败。
-- runner: assert=scalar-true
SELECT (
    SELECT COUNT(*)
    FROM sys_job
    WHERE job_id = 102
      AND CAST(invoke_target AS BINARY) = CAST('beforeStartNotifyTask.run()' AS BINARY)
      AND CAST(status AS BINARY) = CAST('0' AS BINARY)
) = 1;
