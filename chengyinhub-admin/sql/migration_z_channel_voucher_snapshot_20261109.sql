-- 仅新增可空列；历史码不追溯伪造快照，兑换返回 VOUCHER_SNAPSHOT_UNAVAILABLE。
-- runner: assert=scalar-true
SELECT CAST(IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='marketing_channel_voucher'
  AND column_name='grant_snapshot_json')=0,
  1,JSON_EXTRACT('ASSERT_FAILED_channel_voucher_snapshot_preflight','$')) AS UNSIGNED) AS assertion_ok;

ALTER TABLE marketing_channel_voucher ADD COLUMN grant_snapshot_json MEDIUMTEXT NULL
  COMMENT '服务端发行冻结商品期限条款作品及金额分摊；历史空值禁止兑换';

-- runner: assert=scalar-true
SELECT CAST(IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='marketing_channel_voucher'
  AND column_name='grant_snapshot_json' AND data_type='mediumtext')=1,
  1,JSON_EXTRACT('ASSERT_FAILED_channel_voucher_snapshot_readback','$')) AS UNSIGNED) AS assertion_ok;
