# Environment fixtures (same as first pass F-03 and f2-C): production's pre-migration settlement rows, then the repo's own
# 3% migration; cms_setting id 1 (platform account 9001); the H8 points migration missing from the rig snapshot.
PY=$HOME/walk-rig/venv/bin/python; SQL=$HOME/walk-rig/candidate/chengyinhub-admin/sql
$PY db.py "INSERT INTO settlement_rule(merchant_id,biz_type,rule_name,platform_commission_rate,merchant_rate,creator_rate,status,del_flag) VALUES (0,'activity','平台默认-活动',0.1000,0.9000,0,1,0),(0,'topic','平台默认-主题',0.1000,0.9000,0,1,0)" \
  "INSERT INTO cms_setting(id,platform_member_id,create_time) VALUES (1,9001,NOW())"
$PY rig_apply_migration.py $SQL/migration_20261103_settlement_rule_three_percent.sql
$PY rig_apply_migration.py $SQL/migration_20260815_tandianri_h8_publisher_points_funds.sql
$PY db.py "SELECT id,biz_type,platform_commission_rate,merchant_rate,creator_rate FROM settlement_rule" "SELECT id,platform_member_id FROM cms_setting"
