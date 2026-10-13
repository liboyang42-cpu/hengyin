-- ============================================================================
-- M6 随机奖励掉落 · 规则表 + 掉落流水
-- ----------------------------------------------------------------------------
-- advanceProgress 首次完成节点时 SecureRandom 掷骰(基础概率可配,默认15%)→ 按 weight
-- 选 drop_type;reward_drop_log 的 uk 保证重放/并发不多掉。灰度受 M10 feature.flag.drops 控。
-- ⚠️ 生产执行前走 chengyin-db-migrate 只读预检。
-- ============================================================================

CREATE TABLE IF NOT EXISTS reward_drop_rule (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  scope       TINYINT      NULL COMMENT '0=全局 1=指定主题',
  topic_id    BIGINT       NULL COMMENT 'scope=1 时指定主题',
  drop_type   TINYINT      NULL COMMENT '1=XP倍率 2=徽章碎片 3=商家彩蛋券',
  weight      INT          NULL COMMENT '按权重抽取',
  param_json  VARCHAR(200) NULL COMMENT '类型参数:{"mult":2} / {"badgeCode":"x"} / {"couponTemplateId":1}',
  status      TINYINT      NULL COMMENT '1=启用 0=停用',
  create_time DATETIME     NULL,
  PRIMARY KEY (id),
  KEY idx_scope_topic (scope, topic_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='M6 随机掉落规则';

CREATE TABLE IF NOT EXISTS reward_drop_log (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  member_id   BIGINT       NULL,
  topic_id    BIGINT       NULL,
  node_id     BIGINT       NULL,
  activity_id BIGINT       NULL,
  drop_type   TINYINT      NULL,
  param_json  VARCHAR(200) NULL,
  created_at  DATETIME     NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_drop (member_id, topic_id, node_id, activity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='M6 掉落流水(uk 保证每节点最多掉一次)';

-- 基础概率配置(basis point,1500=15%);灰度开关复用 M10 feature.flag.drops
INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
VALUES ('随机掉落-基础概率(万分)', 'reward.drop.base.prob.bp', '1500', 'N', 'admin', NOW(), 'M6 掉落基础概率,万分数(1500=15%)');

-- 示例全局规则(可选;上线按运营配):XP×2(权重60)/ 徽章碎片(权重30)/ 彩蛋券(权重10)
-- INSERT INTO reward_drop_rule (scope, topic_id, drop_type, weight, param_json, status, create_time) VALUES
--   (0, NULL, 1, 60, '{"mult":2}', 1, NOW()),
--   (0, NULL, 2, 30, '{"badgeCode":"FRAGMENT"}', 1, NOW()),
--   (0, NULL, 3, 10, '{"couponTemplateId":0}', 1, NOW());
