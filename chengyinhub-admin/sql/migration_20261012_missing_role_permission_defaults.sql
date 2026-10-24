-- PRE_DEPLOY: missing rows only. No role grants or existing config overwrite.
-- Sources: ci/prelaunch-seed-provenance.json
-- Transaction guards: duplicate key aborts all preceding DML; requires InnoDB.
DROP TEMPORARY TABLE IF EXISTS tmp_cy_seed_guard;
CREATE TEMPORARY TABLE tmp_cy_seed_guard(id INT PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO tmp_cy_seed_guard(id) VALUES(1);
INSERT INTO tmp_cy_seed_guard(id) SELECT 1 WHERE EXISTS (SELECT role FROM user_permission WHERE membership_level IS NULL AND role IN ('player','club','merchant') GROUP BY role HAVING COUNT(*)>1);
INSERT INTO user_permission (role,max_themes,max_nodes_per_theme,max_events,max_coupons,can_create_theme,can_create_event,can_publish_coupon,can_redeem_coupon,node_types,can_branch,can_game_mechanic,can_open_as_node,can_design_medal,can_create_group,data_scope,withdrawable,max_city_nodes,membership_level)
SELECT 'player',2,2,0,1,1,0,1,0,'checkin',0,0,0,0,0,'self',1,0,NULL
WHERE NOT EXISTS (SELECT 1 FROM user_permission WHERE role='player' AND membership_level IS NULL);
INSERT INTO user_permission (role,max_themes,max_nodes_per_theme,max_events,max_coupons,can_create_theme,can_create_event,can_publish_coupon,can_redeem_coupon,node_types,can_branch,can_game_mechanic,can_open_as_node,can_design_medal,can_create_group,data_scope,withdrawable,max_city_nodes,membership_level)
SELECT 'club',5,NULL,3,3,1,1,1,0,'checkin,scan,quiz,puzzle,collect,occupy,minigame',1,1,0,1,1,'theme+city',1,0,NULL
WHERE NOT EXISTS (SELECT 1 FROM user_permission WHERE role='club' AND membership_level IS NULL);
INSERT INTO user_permission (role,max_themes,max_nodes_per_theme,max_events,max_coupons,can_create_theme,can_create_event,can_publish_coupon,can_redeem_coupon,node_types,can_branch,can_game_mechanic,can_open_as_node,can_design_medal,can_create_group,data_scope,withdrawable,max_city_nodes,membership_level)
SELECT 'merchant',0,NULL,0,3,0,0,1,1,'checkin,scan',0,0,1,0,1,'store',1,3,NULL
WHERE NOT EXISTS (SELECT 1 FROM user_permission WHERE role='merchant' AND membership_level IS NULL);
INSERT INTO tmp_cy_seed_guard(id) SELECT 1 WHERE NOT ((SELECT COUNT(*)=3 AND COUNT(DISTINCT role)=3 FROM user_permission WHERE role IN ('player','club','merchant') AND membership_level IS NULL));
DROP TEMPORARY TABLE tmp_cy_seed_guard;
