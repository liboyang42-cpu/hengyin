-- 城瘾 · 优惠券与到店核销 日常对账报表
-- 用途:商业化上线前/后,运营+风控定期跑,发现券超发、计数漂移、薅券、商家未核销。
-- 说明:列名按 sms_coupon / sms_coupon_history / roam_poi_reward_log 实际表结构;如有出入请调整。

-- 【1】券超发检测(C1 修复后新发应为 0;历史脏数据可能 >0,需人工核销并补足 publish_count)
SELECT id, publish_count, receive_count, use_count,
       (receive_count - publish_count) AS oversold
FROM sms_coupon
WHERE publish_count IS NOT NULL AND publish_count > 0
  AND receive_count > publish_count
ORDER BY oversold DESC;

-- 【2】receive_count 计数 vs 实际领取记录 对账(计数漂移检测:两者应一致)
SELECT c.id, c.receive_count AS counter,
       (SELECT COUNT(*) FROM sms_coupon_history h WHERE h.coupon_id = c.id) AS actual_history,
       c.receive_count - (SELECT COUNT(*) FROM sms_coupon_history h WHERE h.coupon_id = c.id) AS diff
FROM sms_coupon c
WHERE c.receive_count - (SELECT COUNT(*) FROM sms_coupon_history h WHERE h.coupon_id = c.id) <> 0
ORDER BY ABS(c.receive_count - (SELECT COUNT(*) FROM sms_coupon_history h WHERE h.coupon_id = c.id)) DESC;

-- 【3】发放-核销漏斗(每券 领取→已用 转化率,过低=玩家领了不用/过高=可能被集中薅)
SELECT id, publish_count, receive_count, use_count,
       ROUND(use_count / NULLIF(receive_count, 0) * 100, 1) AS use_rate_pct
FROM sms_coupon
WHERE receive_count > 0
ORDER BY receive_count DESC;

-- 【4】C2 悬挂待核销:到店打卡记 status=0(待核销)但 >7 天未被商家扫码核销
--     排查:商家未布据点码 / 玩家伪造 GPS 到店但无法核销(C2 拦截生效的正向信号)
SELECT poi_id, COUNT(*) AS pending_cnt, MIN(create_time) AS earliest_pending
FROM roam_poi_reward_log
WHERE status = 0 AND create_time < DATE_SUB(NOW(), INTERVAL 7 DAY)
GROUP BY poi_id
ORDER BY pending_cnt DESC;

-- 【5】单账号薅券排行(同一 member 到店打卡据点数 Top,风控盯防伪造 GPS 批量薅)
SELECT member_id, COUNT(DISTINCT poi_id) AS node_cnt, COUNT(*) AS log_cnt
FROM roam_poi_reward_log
GROUP BY member_id
HAVING node_cnt > 20
ORDER BY node_cnt DESC;
