-- ============================================================================
-- M0-4 发券防重 · DB 兜底唯一约束
-- ----------------------------------------------------------------------------
-- 主防线是后端 Redis SETNX 业务锁(coupon:publish:{memberId}:{couponType}:{minute}, 60s)。
-- 本约束为二道防线:同一商家 + 同一券类型 + 同一分钟内只允许 1 条,防 Redis 故障时穿透。
--
-- ⚠️ 生产执行前(走 chengyin-db-migrate 只读预检):
--   1) MySQL 版本需 >= 5.7(STORED 生成列);
--   2) 先查历史脏数据是否已违反唯一性,否则 ADD UNIQUE 会失败:
--      SELECT member_id, coupon_type, DATE_FORMAT(create_time,'%Y%m%d%H%i') m, COUNT(*) c
--        FROM sms_coupon WHERE del_flag = 0
--        GROUP BY member_id, coupon_type, m HAVING c > 1;
--      如有冲突,先清理/软删重复行再执行第二条 ALTER。
-- ============================================================================

-- 1) 分钟粒度生成列(承载表达式,MySQL < 8.0 不能直接对函数建唯一索引)
ALTER TABLE sms_coupon
  ADD COLUMN publish_minute VARCHAR(16)
    GENERATED ALWAYS AS (DATE_FORMAT(create_time, '%Y%m%d%H%i')) STORED
    COMMENT 'M0-4 发券分钟(生成列),用于发券防重唯一键';

-- 2) 唯一键:商家 + 券类型 + 分钟
ALTER TABLE sms_coupon
  ADD UNIQUE KEY uk_coupon_dedup (member_id, coupon_type, publish_minute);
