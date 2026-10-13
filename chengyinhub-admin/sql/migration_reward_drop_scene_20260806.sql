-- L1 掉落规则公共化：主题节点 / roam 商户据点按 scene_key 分流。
-- ⚠️ 合并前需先落库；生产执行前走 chengyin-db-migrate 只读预检。
--
-- 预检（均应返回 0 行）：
-- SELECT scene_key FROM reward_drop_rule WHERE scene_key IS NOT NULL AND CHAR_LENGTH(scene_key) > 64;
-- SELECT scene_key FROM reward_drop_log WHERE scene_key IS NOT NULL AND CHAR_LENGTH(scene_key) > 64;

ALTER TABLE reward_drop_rule
  MODIFY COLUMN scope TINYINT NULL COMMENT '0=全局 1=指定主题 2=指定场景',
  ADD COLUMN scene_key VARCHAR(64) NULL COMMENT 'scope=2 场景键，如 roam_merchant_poi' AFTER topic_id,
  ADD KEY idx_scope_scene (scope, scene_key, status);

ALTER TABLE reward_drop_log
  ADD COLUMN scene_key VARCHAR(64) NOT NULL DEFAULT 'topic_node' COMMENT '掉落发生场景' AFTER activity_id,
  DROP INDEX uk_drop,
  ADD UNIQUE KEY uk_drop_scene (member_id, scene_key, topic_id, node_id, activity_id);

-- scope=2 示例（按运营配置权重/参数后再启用）：
-- INSERT INTO reward_drop_rule(scope, topic_id, scene_key, drop_type, weight, param_json, status, create_time)
-- VALUES (2, NULL, 'roam_merchant_poi', 1, 100, '{"mult":2}', 0, NOW());
