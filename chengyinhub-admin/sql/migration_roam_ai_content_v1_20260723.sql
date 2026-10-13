-- 影响分类 ③ 数据库迁移:合前先落库(chengyin-db-migrate),幂等可重跑。
-- 漫游无形陪伴 V1：独立审核内容库。
-- 只允许审核通过(audit_status=1)且启用(enabled=1)的内容进入玩家接口；
-- 迁移里的路线模板均为待审，不会因执行迁移而直接面向玩家。

CREATE TABLE IF NOT EXISTS `roam_ai_content` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `content_type` varchar(32) NOT NULL COMMENT 'ROUTE_STORY / POI_MEANING',
  `content_key` varchar(64) NOT NULL COMMENT '运营稳定键；地点意义由 poi-{poiId} 固定生成',
  `poi_id` bigint DEFAULT NULL COMMENT '仅 POI_MEANING 绑定 roam_poi.id',
  `content_text` varchar(80) NOT NULL COMMENT '审核短文本；路线故事仅允许白名单指标占位符',
  `audit_status` tinyint NOT NULL DEFAULT '0' COMMENT '0待审 1通过 2驳回',
  `enabled` tinyint NOT NULL DEFAULT '1' COMMENT '0停用 1启用',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_time` datetime NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_roam_ai_content_type_key` (`content_type`, `content_key`),
  KEY `idx_roam_ai_content_poi_audit` (`poi_id`, `audit_status`, `enabled`),
  KEY `idx_roam_ai_content_route_audit` (`content_type`, `audit_status`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='漫游无形陪伴审核内容';

-- 仅提供待审核的事实指标模板样例；不写入任何地点事实，不会自动发布。
INSERT IGNORE INTO `roam_ai_content`
(`content_type`, `content_key`, `poi_id`, `content_text`, `audit_status`, `enabled`, `create_time`, `update_time`)
VALUES
('ROUTE_STORY', 'route-default-1', NULL, '本次点亮 {{tiles}} 格，发现 {{pois}} 处，留下了一段自己的足迹。', 0, 1, NOW(), NOW()),
('ROUTE_STORY', 'route-default-2', NULL, '{{tiles}} 格新点亮与 {{pois}} 处发现，收进了这次漫游的足迹。', 0, 1, NOW(), NOW());
