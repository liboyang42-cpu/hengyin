-- ============================================================================
-- 商家据点常驻点亮城市 · P1-M2 迁移
-- 前置:migration_roam.sql 须先执行(依赖 roam_poi 表存在)
-- 内容:① roam_poi 加商户节点字段 ② roam_poi_reward_log 发券防重 ③ user_permission.max_city_nodes 配额
-- 幂等:列存在性守卫 + CREATE IF NOT EXISTS + UPDATE ... WHERE IS NULL/0
-- ⚠️ 生产执行走 chengyin-db-migrate 只读预检后再跑,勿直连。
-- ============================================================================

-- ① roam_poi 加商户节点字段(type=2 商户据点用;type=1 系统 POI 保持 NULL)
--    奖励券复用已有列 coupon_template_id(save 时从模板 coupon_id 冗余进来)
SET @exist := (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'roam_poi' AND column_name = 'merchant_id');
SET @sql := IF(@exist = 0,
  'ALTER TABLE roam_poi
     ADD COLUMN merchant_id BIGINT NULL COMMENT ''商户据点:mms_merchant.id'',
     ADD COLUMN template_id BIGINT NULL COMMENT ''商户据点:绑定互动模板 cms_member_template.id'',
     ADD COLUMN node_level TINYINT DEFAULT 1 COMMENT ''1普通 2精选 3官方合作 4主题路线'',
     ADD COLUMN tags VARCHAR(255) NULL COMMENT ''标签,逗号分隔'',
     ADD COLUMN cover_img VARCHAR(500) NULL COMMENT ''地图卡封面'',
     ADD COLUMN sort INT DEFAULT 0 COMMENT ''运营排序'',
     ADD KEY idx_merchant (merchant_id)',
  'SELECT ''roam_poi 商户字段已存在,跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ② 发券防重记录(uk_poi_member:每玩家每据点仅一次)
CREATE TABLE IF NOT EXISTS roam_poi_reward_log (
  id                BIGINT        NOT NULL AUTO_INCREMENT,
  poi_id            BIGINT        NOT NULL,
  member_id         BIGINT        NOT NULL COMMENT '触发的玩家',
  coupon_id         BIGINT        DEFAULT NULL COMMENT '券模板 sms_coupon.id',
  coupon_history_id BIGINT        DEFAULT NULL COMMENT '发放的券包记录 sms_coupon_history.id',
  trigger_lat       DECIMAL(10,6) DEFAULT NULL COMMENT '触发点(审计)',
  trigger_lng       DECIMAL(10,6) DEFAULT NULL,
  status            TINYINT       DEFAULT 1 COMMENT '1已领 2已核销',
  create_time       DATETIME      DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_poi_member (poi_id, member_id),
  KEY idx_member (member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='据点互动领券记录(uk 防重发券)';

-- ③ user_permission 加城市据点配额(角色级)
SET @exist2 := (SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'user_permission' AND column_name = 'max_city_nodes');
SET @sql2 := IF(@exist2 = 0,
  'ALTER TABLE user_permission ADD COLUMN max_city_nodes INT DEFAULT 0 COMMENT ''可常驻点亮城市据点数(仅商家)''',
  'SELECT ''user_permission.max_city_nodes 已存在,跳过''');
PREPARE stmt2 FROM @sql2; EXECUTE stmt2; DEALLOCATE PREPARE stmt2;

-- 商家默认 1(仅首次设,不覆盖后台已调整的值)
UPDATE user_permission SET max_city_nodes = 1
 WHERE role = 'merchant' AND (max_city_nodes IS NULL OR max_city_nodes = 0);
