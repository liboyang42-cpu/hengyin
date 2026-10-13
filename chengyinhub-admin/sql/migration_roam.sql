-- ============================================================================
-- P2 点亮城市 · roam(真 GPS 迷雾探索)后端建表
-- 奖励契约(v3 §3):1 格=1 XP(日限 200)/ POI 发现=20 XP + 里程碑勋章;
-- 双轨红线:XP 只入 member_growth.exp_value(走 IGrowthService.addExp),绝不入 ums_member_points_detail。
-- ⚠️ 生产执行前走 chengyin-db-migrate 只读预检。全部 CREATE IF NOT EXISTS 幂等。
-- ============================================================================

-- 漫游会话:一次出门探索的起止与结算快照
CREATE TABLE IF NOT EXISTS roam_session (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  member_id       BIGINT       NOT NULL,
  started_at      DATETIME     DEFAULT NULL,
  finished_at     DATETIME     DEFAULT NULL,
  distance_m      INT          DEFAULT 0    COMMENT '本次里程(米)',
  tiles_revealed  INT          DEFAULT 0    COMMENT '本次新点亮格子数(结算写入)',
  pois_found      INT          DEFAULT 0    COMMENT '本次新发现 POI 数',
  xp_awarded      INT          DEFAULT 0    COMMENT '本次实发探索值(日限后)',
  status          TINYINT      DEFAULT 0    COMMENT '0 进行中 1 已结算',
  create_time     DATETIME     DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_member (member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P2 漫游会话';

-- 迷雾格子:按 member 全局去重(版图累积);uk 保证 reveal 幂等,一格终身只记一次
CREATE TABLE IF NOT EXISTS roam_fog_tile (
  id           BIGINT      NOT NULL AUTO_INCREMENT,
  member_id    BIGINT      NOT NULL,
  session_id   BIGINT      DEFAULT NULL,
  geohash7     VARCHAR(12) NOT NULL          COMMENT '~150m 精度网格键',
  revealed_at  DATETIME    NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_member_tile (member_id, geohash7),
  KEY idx_member_day (member_id, revealed_at),
  KEY idx_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P2 已点亮迷雾格(版图,uk 幂等)';

-- POI 兴趣点:type=1 地标/公园(平台内容) / type=2 商户招牌据点(P3 商户投放)
CREATE TABLE IF NOT EXISTS roam_poi (
  id                 BIGINT        NOT NULL AUTO_INCREMENT,
  name               VARCHAR(80)   NOT NULL,
  type               TINYINT       DEFAULT 1   COMMENT '1 地标/公园 2 商户招牌',
  lat                DECIMAL(10,6) NOT NULL,
  lng                DECIMAL(10,6) NOT NULL,
  radius_m           INT           DEFAULT 80  COMMENT '触发半径(米)',
  description        VARCHAR(255)  DEFAULT NULL,
  xp                 INT           DEFAULT 20  COMMENT '发现奖励探索值',
  coupon_template_id BIGINT        DEFAULT NULL COMMENT '商户 POI 关联券模板(可空)',
  city_code          VARCHAR(20)   DEFAULT NULL,
  status             TINYINT       DEFAULT 1   COMMENT '1 上架 0 下架',
  create_time        DATETIME      DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_city_status (city_code, status),
  KEY idx_geo (lat, lng)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P2 漫游 POI 兴趣点';

-- POI 发现记录:按 member 去重,保证 POI 20XP 不重复发放
CREATE TABLE IF NOT EXISTS roam_poi_found (
  id          BIGINT   NOT NULL AUTO_INCREMENT,
  member_id   BIGINT   NOT NULL,
  poi_id      BIGINT   NOT NULL,
  session_id  BIGINT   DEFAULT NULL,
  found_at    DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_member_poi (member_id, poi_id),
  KEY idx_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P2 POI 发现记录(uk 幂等,防重复发 XP)';
