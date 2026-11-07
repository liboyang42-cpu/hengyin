#!/bin/bash
# HARNESS (rig seed gap, not product): production has these rows; the rig seed has none.
# settlement_rule generic rows at 3% (migration_20261103_settlement_rule_three_percent asserts exactly these), cms_setting 1.
docker exec -i cy-chain-local-1-mysql mysql -uroot cy_e2e_51 <<'SQL'
INSERT INTO settlement_rule (merchant_id,biz_type,rule_name,platform_commission_rate,merchant_rate,creator_rate,status,del_flag,create_time)
SELECT 0,b,CONCAT('rig ',b),0.0300,0.9700,0.0000,1,0,NOW() FROM (SELECT 'activity' b UNION ALL SELECT 'topic' UNION ALL SELECT 'topic_club') x
WHERE NOT EXISTS (SELECT 1 FROM settlement_rule r WHERE r.merchant_id=0 AND r.biz_type=x.b AND r.status=1 AND r.del_flag=0);
INSERT INTO cms_setting (id,platform_member_id,create_time) SELECT 1,9001,NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM cms_setting WHERE id=1);
SELECT merchant_id,biz_type,platform_commission_rate FROM settlement_rule; SELECT id,platform_member_id FROM cms_setting;
SQL
