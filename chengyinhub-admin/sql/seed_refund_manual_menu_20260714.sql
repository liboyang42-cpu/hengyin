-- §6.1 退款人工兜底页菜单。RuoYi 按 component 路径自动路由到 business/refund/manual.vue。
-- 复用 business:refund:review 权限(处理与复核由「处理人≠复核人」在后端强校验,非靠权限区分)。
-- 幂等:component 已存在则跳过。父菜单取现有退款菜单(component=business/refund/index),取不到则挂 0(顶级),
-- 用户可按实际菜单树调整 parent_id。执行前用 chengyin-db-migrate 预检 sys_menu。

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, query, is_frame, is_cache,
   menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '退款人工兜底',
       COALESCE((SELECT menu_id FROM (SELECT menu_id FROM sys_menu WHERE component = 'business/refund/index' LIMIT 1) p), 0),
       5, 'refund-manual', 'business/refund/manual', '', 1, 0,
       'C', '0', '0', 'business:refund:review', 'money', 'admin', now(), '§6.1 退款失败人工兜底队列'
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM (SELECT menu_id FROM sys_menu WHERE component = 'business/refund/manual' LIMIT 1) e);

-- §6.1 退款对账兜底定时任务(P3 回调的纵深防御):扫 payout_status=0 卡单拉取微信终态。
-- 幂等:invoke_target 已存在则跳过。cron 每 15 分钟。执行后需在 RuoYi 定时任务管理里「启动」该任务。
INSERT INTO sys_job
  (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status, create_by, create_time, remark)
SELECT '退款对账兜底', 'DEFAULT', 'refundReconcileTask.reconcile()', '0 0/15 * * * ?', '3', '1', '0', 'admin', now(), '§6.1 扫描受理后卡PROCESSING的退款单,拉取微信终态回写'
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM (SELECT job_id FROM sys_job WHERE invoke_target = 'refundReconcileTask.reconcile()' LIMIT 1) e);
