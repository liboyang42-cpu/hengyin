-- C1 增强:优惠券"每人限领张数"
-- 背景:C1 已堵券总量超发(publish_count 占坑)。但同一 member 可能经多个触发点(节点券/彩蛋掉落券/据点券)
--       领到同一张券多次。加 per_member_limit 后,发券前校验该 member 已领数 < 上限。
-- 配套代码(执行本迁移后再改):SmsCouponHistoryServiceImpl.grantCoupon 占坑前先查
--   SELECT COUNT(*) FROM sms_coupon_history WHERE coupon_id=? AND member_id=?  与 per_member_limit 比较。
--
-- 执行走 chengyin-db-migrate skill。

ALTER TABLE sms_coupon
  ADD COLUMN per_member_limit INT DEFAULT NULL
  COMMENT '每人限领张数(NULL 或 <=0 = 不限;>0 = 每 member 最多领 N 张)';

-- C1-P2 备注:后台手动发券接口 SmsCouponHistoryController:80 走 service.insertSmsCouponHistory(无占坑),
--   属后台运营接口(需后台权限),豁免于 C1 占坑;如需一并防超发,改用 grantCoupon 路径。

-- 回滚:ALTER TABLE sms_coupon DROP COLUMN per_member_limit;
