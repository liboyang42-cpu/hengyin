-- ============================================================================
-- M2 自玩票全链 · 数据基础(建议稿,执行前必须 chengyin-db-migrate 只读预检对照生产 schema)
-- ----------------------------------------------------------------------------
-- 注意:字段名以生产实际为准,尤其 player_node_progress 主键结构。
-- 本迁移只加列/键,不改既有数据语义;activity_id=0 约定为"自玩会话"。
-- ============================================================================

-- 1) 节点进度:支持自玩会话(activity_id=0)+ 主题维度键;唯一键含 activity_id 隔离"自玩/场次"两条独立进度
ALTER TABLE player_node_progress
  ADD COLUMN topic_id BIGINT NOT NULL DEFAULT 0 COMMENT 'M2 自玩:主题ID(0=旧活动会话)',
  ADD UNIQUE KEY uk_progress (member_id, topic_id, node_id, activity_id);
-- 若 activity_id 原为 NOT NULL 无默认,按需:
-- ALTER TABLE player_node_progress MODIFY activity_id BIGINT NOT NULL DEFAULT 0 COMMENT '0=自玩会话';

-- 2) 报名/通行证:activity_id=0 + topic_id = 主题通行证;expires_at = 购后有效期(默认 90 天)
ALTER TABLE cms_registration
  ADD COLUMN topic_id BIGINT NULL COMMENT 'M2 自玩:主题通行证的主题ID(配合 owner_type/activity_id=0)',
  ADD COLUMN expires_at DATETIME NULL COMMENT 'M2 自玩:通行证有效期(购后90天)';

-- 3) 主题:自玩开关 + 自玩价 + 自玩票数(仅「城市定向」mode=1 主题有意义;自由探索一人无自玩票)
ALTER TABLE cms_topic
  ADD COLUMN self_play TINYINT DEFAULT 0 COMMENT 'M2 是否开放自玩票 0否1是(仅城市定向)',
  ADD COLUMN self_play_price DECIMAL(10,2) NULL COMMENT 'M2 自玩票价格(元)',
  ADD COLUMN self_play_quota INT NULL COMMENT 'M2 自玩票数/配额(NULL 或 <=0 = 不限)';

-- 4) 分账规则:自玩场景(默认与场次同比例),主理人自玩长尾分成入账用
-- settlement_rule 若为"每场景一行"结构,插入 scene=self_play 规则行(比例同 activity 默认);
-- 具体列名以生产 settlement_rule 为准,示例(需按实际调整):
-- INSERT INTO settlement_rule (scene, ratio_json, status, remark, create_time)
--   SELECT 'self_play', ratio_json, status, 'M2 自玩长尾分成(同场次比例)', NOW()
--   FROM settlement_rule WHERE scene = 'activity' LIMIT 1;
