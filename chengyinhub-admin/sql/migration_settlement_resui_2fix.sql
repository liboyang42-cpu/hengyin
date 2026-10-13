-- 资损两修 · 存量数据处置(退款不回落 registration_status + 定价 P_min 漏保底率)
-- ⚠️ 本文件不自动执行。必须走 chengyin-db-migrate skill:先只读预检(SELECT 盘点)→ 向用户确认 → 再执行 UPDATE。
-- ⚠️ 列名/取值以生产库实体为准,执行前用 information_schema 核对(生产库 MySQL 8)。
-- 执行顺序:先落库(本文件相关处置)再部署 jar(代码修复),避免新旧口径不一致窗口。

-- ============================================================
-- Bug#1 退款不回落 registration_status —— 存量脏数据回落 + 二次分账盘点
-- 根因已修:退款三路径(RefundServiceImpl / 微信回调 REFUND / 拼团)现均回落 registration_status=3,
-- 且核销闸(scanQrCode)加 payment_status!=2 拒核销。存量历史退款单仍残留 registration_status=2,需回落。
-- ============================================================

-- [1a] 只读盘点:已退款(payment_status=4)单的 registration_status 分布
SELECT payment_status, registration_status, COUNT(*) AS cnt
FROM cms_registration
WHERE payment_status = 4
GROUP BY payment_status, registration_status;

-- [1b] 只读盘点(资金重点):已退款但被错误核销的单(verification_status=1)——
--      其中 owner_type=1(topic)单很可能已发生二次分账,须交财务对账追回,本次不动 verification_status。
SELECT id, owner_type, verification_status, update_time
FROM cms_registration
WHERE payment_status = 4 AND registration_status = 2 AND verification_status = 1;

-- [1c] 回落(确认盘点后执行):仅"已退款且状态残留=2"精确集合,不动支付中/失败/已支付单。
--      source-agnostic:一并覆盖后台退款/微信回调/拼团三条历史路径的残留。
UPDATE cms_registration
SET registration_status = 3, update_by = 'refund-status-backfill', update_time = NOW()
WHERE payment_status = 4 AND registration_status = 2;

-- [1d] 复核:执行后残留应为 0
-- SELECT COUNT(*) FROM cms_registration WHERE payment_status = 4 AND registration_status = 2;

-- ============================================================
-- Bug#2 定价 P_min 漏保底率 —— 无干净 UPDATE(买家已按偏低价支付,不可追溯改价)
-- 根因已修:地板公式补保底率 + 锁价后冻结阵容(acceptInvite lifecycle 闸)。存量只做只读诊断,人工处置。
-- ⚠️ 下列列名(payable_amount/owner_id/owner_type、coop_settlement.line_type/topic_id、coop_order.topic_id/del_flag)
--    须按实体核对后再跑;这是修正设计稿口径错误(原稿用 amount/pay_status=1 是错的)后的版本。
-- ============================================================

-- [2a] 只读诊断:当前"卡结算"的存量主题(有生效 coop_order + 有已支付未退款销售额 + 已结束 + 从未成功结算)
SELECT t.id, t.name, t.self_play_price, t.end_date
FROM cms_topic t
WHERE EXISTS (SELECT 1 FROM coop_order co WHERE co.topic_id = t.id AND co.del_flag = 0)
  AND (SELECT COALESCE(SUM(r.payable_amount), 0) FROM cms_registration r
       WHERE r.owner_id = t.id AND r.owner_type = 1
         AND r.payment_status = 2 AND r.registration_status = 2) > 0
  AND t.end_date < NOW()
  AND NOT EXISTS (SELECT 1 FROM coop_settlement s WHERE s.topic_id = t.id AND s.line_type = 'PLATFORM');
-- 命中主题:逐个按调整后拆分口径人工结算 / 平台兜底吸收差额(策略待用户拍板),不做批量 UPDATE。
