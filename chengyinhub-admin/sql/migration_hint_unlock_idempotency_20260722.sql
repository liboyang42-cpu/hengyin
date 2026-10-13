-- 积分解锁提示并发幂等闸。
-- 解锁流水的业务唯一性:同一会员对同一节点(event_type=14)只能消费一次。
-- 此键覆盖整张积分流水表;event_id 为 NULL 的历史流水不参与 MySQL 唯一约束。
-- 执行前必须确认以下查重结果为空,并确认 uk_member_event_once 尚不存在:
-- SELECT member_id, event_type, event_id, COUNT(*)
-- FROM ums_member_points_detail
-- GROUP BY member_id, event_type, event_id
-- HAVING COUNT(*) > 1;
ALTER TABLE ums_member_points_detail
  ADD UNIQUE KEY uk_member_event_once (member_id, event_type, event_id);

SELECT index_name, column_name, seq_in_index
FROM information_schema.statistics
WHERE table_schema = DATABASE() AND table_name = 'ums_member_points_detail'
  AND index_name = 'uk_member_event_once'
ORDER BY seq_in_index;
