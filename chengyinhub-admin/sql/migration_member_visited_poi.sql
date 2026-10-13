-- 点亮城市「点到店」:非参与地点(店/地标)到点记录。member 级,坐标取整去重,每点终身一次。
-- 幂等:唯一键 (member_id, geo_key)。CREATE TABLE IF NOT EXISTS 可重复跑。
-- ⚠️ 部署顺序:先于新 jar 落库,否则 MemberVisitedPoiMapper 引用 member_visited_poi 会 SQL 报错。
-- 走 chengyin-db-migrate skill(只读预检后确认)。

CREATE TABLE IF NOT EXISTS member_visited_poi (
  id               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  member_id        BIGINT        NOT NULL COMMENT '玩家 member_id',
  geo_key          VARCHAR(24)   NOT NULL COMMENT '被点 POI 坐标取整3位小数,如 31.230,121.470',
  poi_name         VARCHAR(120)  NULL COMMENT '到点时的微信 POI 名',
  lat              DECIMAL(10,6) NULL COMMENT '纬度',
  lng              DECIMAL(10,6) NULL COMMENT '经度',
  first_visit_time DATETIME      NULL COMMENT '首次到点时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_member_geo (member_id, geo_key),
  KEY idx_member (member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='点亮城市:非参与地点到点记录';
