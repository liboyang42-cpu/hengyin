-- ==================================================
-- 城瘾 AI NPC 陪伴系统: NPC 人设表
-- 作用域: scope_type 1=剧本角色(挂activity) 2=门店分身(挂merchant) 3=全局城瘾NPC
-- 执行前预检: SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'npc_profile';
-- ==================================================

CREATE TABLE npc_profile (
  id              BIGINT       PRIMARY KEY AUTO_INCREMENT,
  scope_type      TINYINT      NOT NULL COMMENT '1=剧本角色(挂activity) 2=门店分身(挂merchant) 3=全局城瘾NPC',
  scope_id        BIGINT       NOT NULL DEFAULT 0 COMMENT 'activityId 或 merchantId;全局NPC固定0',
  name            VARCHAR(32)  NOT NULL COMMENT 'NPC 名,如 老周/店长阿喵/小瘾',
  avatar          VARCHAR(255) COMMENT '头像 URL(阿里云 OSS)',
  persona         TEXT         NOT NULL COMMENT '人设系统提示词(性格/语气/背景/边界)',
  greeting        VARCHAR(255) COMMENT '默认开场白(兜底)',
  sort            INT          DEFAULT 0 COMMENT '同一活动多个NPC的出场顺序(主角0/配角1)',
  audit_status    TINYINT      DEFAULT 0 COMMENT '0=待审 1=通过 2=驳回',
  enabled         TINYINT      DEFAULT 1,
  del_flag        CHAR(1)      DEFAULT '0',
  create_time     DATETIME,
  update_time     DATETIME,
  KEY idx_scope (scope_type, scope_id, sort)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI NPC 人设';
