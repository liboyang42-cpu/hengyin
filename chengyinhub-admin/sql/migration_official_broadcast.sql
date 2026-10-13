-- ============================================================================
-- 官方活动系统 · 官方通知分发子系统 迁移
-- 内容:① official_broadcast 官方通知/广播 ② official_broadcast_log 分发日志(uk 防重发 + 触达/点击)
-- 定向:audience(player,club,merchant 多选)× city(名字关键词) → 圈 member 集;渠道 站内信 IM + 订阅消息
-- 幂等:CREATE IF NOT EXISTS
-- ⚠️ 生产执行走 chengyin-db-migrate 只读预检后再跑,勿直连。前置:migration_official_event.sql(event_id 引用 official_event.id,无外键约束)
-- 设计真源:vault《Questify官方活动系统_产品设计报告_2026-07-08》§8.2
-- ============================================================================

-- ① 官方通知/广播(可绑活动 event_id;NULL=纯官方公告)
CREATE TABLE IF NOT EXISTS official_broadcast (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  event_id       BIGINT       DEFAULT NULL COMMENT '关联 official_event.id,NULL=纯公告',
  title          VARCHAR(128) NOT NULL COMMENT '通知标题',
  audience       VARCHAR(32)  NOT NULL COMMENT '目标角色逗号集:player,club,merchant',
  copy_mode      TINYINT      DEFAULT 1 COMMENT '1一起发(统一文案) 2分开发(分角色文案)',
  content_json   TEXT         COMMENT '一起发={title,sub},分开发={player:{..},club:{..},merchant:{..}}',
  channels       VARCHAR(32)  NOT NULL DEFAULT 'inapp' COMMENT '触达渠道逗号集:inapp,subscribe',
  city           VARCHAR(32)  DEFAULT NULL COMMENT '城市名关键词,NULL=全国全体该角色',
  scheduled_time DATETIME     DEFAULT NULL COMMENT 'NULL=立即发',
  status         TINYINT      DEFAULT 0 COMMENT '0草稿 1待发 2已发 3已撤',
  reached_count  INT          DEFAULT 0 COMMENT '触达数(冗余统计)',
  sender_account BIGINT       DEFAULT NULL COMMENT '发起白名单账号 member_id',
  create_time    DATETIME     DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_event (event_id),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='官方通知/广播';

-- ② 分发日志(uk_bc_member_channel:同一通知同一人同一渠道仅一条,防重发)
CREATE TABLE IF NOT EXISTS official_broadcast_log (
  id           BIGINT      NOT NULL AUTO_INCREMENT,
  broadcast_id BIGINT      NOT NULL,
  member_id    BIGINT      NOT NULL,
  role         VARCHAR(16) DEFAULT NULL COMMENT 'player/club/merchant',
  channel      VARCHAR(16) DEFAULT NULL COMMENT 'inapp/subscribe',
  reached      TINYINT     DEFAULT 0 COMMENT '1已触达',
  clicked      TINYINT     DEFAULT 0 COMMENT '1已点击',
  create_time  DATETIME    DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_bc_member_channel (broadcast_id, member_id, channel),
  KEY idx_broadcast (broadcast_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='官方通知分发日志(uk 防重发)';
