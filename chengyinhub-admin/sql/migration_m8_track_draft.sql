-- ============================================================================
-- M8 以走代编 · 轨迹草稿
-- ⚠️ 生产执行前走 chengyin-db-migrate 只读预检。
-- ============================================================================

CREATE TABLE IF NOT EXISTS track_draft (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  member_id     BIGINT       NULL,
  name          VARCHAR(60)  NULL,
  polyline      TEXT         NULL COMMENT '抽稀后轨迹(前端可直接渲染)',
  distance_m    INT          NULL COMMENT '距离(米)',
  duration_s    INT          NULL COMMENT '用时(秒)',
  waypoint_json JSON         NULL COMMENT '停靠点[{lat,lng,name,photo,note,seq}]',
  raw_oss_url   VARCHAR(255) NULL COMMENT '原始点>5000时转存 OSS 的 url',
  lit_blocks    INT          NULL COMMENT '点亮区块数(M4)',
  status        TINYINT      NULL COMMENT '0草稿 1已转路线 2已删',
  created_at    DATETIME     NULL,
  updated_at    DATETIME     NULL,
  PRIMARY KEY (id),
  KEY idx_member (member_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='M8 轨迹草稿';
