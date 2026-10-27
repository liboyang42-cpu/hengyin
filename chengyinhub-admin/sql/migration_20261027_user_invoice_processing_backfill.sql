-- R625 major (a)(2026-10-27):W8 前提交的发票申请没有 user_invoice_processing 行,处理台列表/命令/对账都读不到它。
-- 前置:migration_20261026_user_invoice_processing.sql(建表)。
-- 回填口径与 apply() 写入处理行同源:申请全部订单行经 user_invoice_issuer_owner 映射到【唯一】开票主体才回填;
-- 有订单映射缺失或跨主体的申请不回填(不得按名称推断主体),留人工核对。
-- 只插处理行(version=0、reconciliation_status 默认 NONE),不改申请状态、票号或订单占用。
-- 幂等:只处理还没有处理行的申请,重跑不重复插入。
INSERT INTO user_invoice_processing(application_id, issuer_id)
SELECT a.id, MIN(m.issuer_id)
FROM user_invoice_application a
JOIN user_invoice_application_order o ON o.application_id = a.id
LEFT JOIN cms_registration r ON o.order_source = 'registration' AND r.id = o.order_id
LEFT JOIN user_invoice_issuer_owner m ON m.owner_type = r.owner_type AND m.owner_id = r.owner_id
WHERE NOT EXISTS (SELECT 1 FROM user_invoice_processing p WHERE p.application_id = a.id)
GROUP BY a.id
HAVING COUNT(*) = COUNT(m.issuer_id) AND COUNT(DISTINCT m.issuer_id) = 1;
