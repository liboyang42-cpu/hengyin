-- ============================================================================
-- 官方活动系统 · 活动壳 + 报名 + 发布权限位 迁移
-- 内容:① official_event 活动实体 ② event_signup 报名(uk 防重) ③ user_permission.can_publish_official_event 白名单权限位
-- 幂等:CREATE IF NOT EXISTS + 列存在性守卫(information_schema)
-- 复用:任务玩法接 cms_topic/roam_poi(content_json 引用 id,无外键约束);奖励接 sms_coupon / member_collectible / 成长XP
-- ⚠️ 生产执行走 chengyin-db-migrate 只读预检后再跑,勿直连。
-- 设计真源:vault《Questify官方活动系统_产品设计报告_2026-07-08》§5.2 / §8.1
-- ============================================================================

-- ① 官方活动实体(可配置活动引擎;分类=预设,一切皆开关)
CREATE TABLE IF NOT EXISTS official_event (
  id                   BIGINT       NOT NULL AUTO_INCREMENT,
  title                VARCHAR(128) NOT NULL COMMENT '活动标题',
  subtitle             VARCHAR(255) DEFAULT NULL COMMENT '副标题',
  category             VARCHAR(32)  DEFAULT NULL COMMENT '预设分类:city_light/custom/festival/brand/challenge/hidden/community/merchant_hunt',
  cover_img            VARCHAR(500) DEFAULT NULL COMMENT '主题海报',
  story                TEXT         COMMENT '故事背景(富文本/纯文本)',
  city                 VARCHAR(32)  DEFAULT NULL COMMENT '城市名关键词(匹配club.city/work_address),NULL=全国',
  signup_start         DATETIME     DEFAULT NULL COMMENT '报名开始',
  activity_start       DATETIME     DEFAULT NULL COMMENT '活动开始',
  activity_end         DATETIME     DEFAULT NULL COMMENT '活动结束',
  settle_time          DATETIME     DEFAULT NULL COMMENT '结算时间',
  signup_limit         INT          DEFAULT 0 COMMENT '报名上限,0=不限',
  content_json         TEXT         COMMENT '绑定内容:{"play_type":"linear/collect","topic_ids":[..],"poi_ids":[..],"tasks":[..]}',
  collective_enabled   TINYINT      DEFAULT 0 COMMENT '集体玩法开关',
  collective_metric    VARCHAR(32)  DEFAULT NULL COMMENT '集体指标:light_count/signup_count/complete_count',
  collective_threshold INT          DEFAULT 0 COMMENT '集体达标阈值',
  leaderboard_enabled  TINYINT      DEFAULT 0 COMMENT '排行榜开关',
  reward_json          TEXT         COMMENT '奖励配置:{"instant":..,"milestone":..,"settle":..,"collective":..,"limit":N}',
  merchant_ids         VARCHAR(255) DEFAULT NULL COMMENT '绑定合作商家 mms_merchant.id,逗号分隔',
  brand_logo           VARCHAR(500) DEFAULT NULL COMMENT '品牌/主办方 logo',
  banner_enabled       TINYINT      DEFAULT 0 COMMENT '首页城市事件 banner 开关',
  sort                 INT          DEFAULT 0 COMMENT '运营排序权重',
  status               TINYINT      DEFAULT 0 COMMENT '0草稿 1已排期 2报名中 3进行中 4待结算 5已结束 6已复盘 9已下线',
  sender_account       BIGINT       DEFAULT NULL COMMENT '发起白名单账号 member_id',
  create_by            VARCHAR(64)  DEFAULT NULL,
  create_time          DATETIME     DEFAULT NULL,
  update_time          DATETIME     DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_city_status (city, status),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='官方活动(可配置活动引擎)';

-- ② 报名(uk_event_member:每人每活动仅一次;不占用户发布额度)
CREATE TABLE IF NOT EXISTS event_signup (
  id             BIGINT   NOT NULL AUTO_INCREMENT,
  event_id       BIGINT   NOT NULL,
  member_id      BIGINT   NOT NULL,
  status         TINYINT  DEFAULT 1 COMMENT '1已报名 2进行中 3已完成 4已领奖',
  progress       INT      DEFAULT 0 COMMENT '个人完成任务数',
  reward_claimed TINYINT  DEFAULT 0 COMMENT '结算奖是否已发',
  create_time    DATETIME DEFAULT NULL,
  update_time    DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_event_member (event_id, member_id),
  KEY idx_event (event_id),
  KEY idx_member (member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='官方活动报名';

-- ③ user_permission 加官方活动发布权限位(仅平台白名单账号可发)
SET @exist := (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'user_permission' AND column_name = 'can_publish_official_event');
SET @sql := IF(@exist = 0,
  'ALTER TABLE user_permission ADD COLUMN can_publish_official_event TINYINT DEFAULT 0 COMMENT ''是否可发官方活动/通知(白名单)''',
  'SELECT ''user_permission.can_publish_official_event 已存在,跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
