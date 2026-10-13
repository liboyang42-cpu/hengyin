-- ============================================================================
-- CELEBRATION BOARD 仪式板三表(《今天值得庆祝》机制层)
--
-- 真源:设计稿 §3(共同进度对象)/§3.4(四状态不能混用)/§12.1(匿名表态隐私)。
-- 设计决策(2026-08-21,记录在 vault 今天值得庆祝_模板全量落地_系统设计与TODO_20260821):
--   · 板级成员,不建 play_team —— 熟人小组邀请码入板即够;组局模型留给城市定向 SKU。
--   · 时间用「当日分钟」(start_minute/end_minute,0—1439),不含日期 —— 板是当天计划;
--   · 静态检查 = 结构检查(窗口/重叠/预算/步行/必填/演练),不是求解器;
--   · BOOKED = 玩家自报凭证,平台不担保履约(§3.4 原文语义)。
--
-- 预检(执行前人工跑,三表均不应存在):
--   SELECT TABLE_NAME FROM information_schema.TABLES
--    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME LIKE 'celebration_board%';
-- ============================================================================

CREATE TABLE celebration_board (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  topic_id         BIGINT NOT NULL COMMENT '货架主题 id(play_kind=BOARD 的模板)',
  owner_member_id  BIGINT NOT NULL COMMENT '发起人',
  invite_code      VARCHAR(32) NOT NULL COMMENT '成员入板邀请码(随机,分享链路用)',
  win_category     VARCHAR(32)  DEFAULT NULL COMMENT 'G0 六类:done_at_last/first_time/survived/reunion/new_chapter/self_praise',
  win_label        VARCHAR(60)  DEFAULT NULL COMMENT '私人标签,不要求敏感详情;默认不给商家看原文',
  ritual_style     VARCHAR(16)  DEFAULT NULL COMMENT '热闹/安静/动手/好好吃一顿',
  area_name        VARCHAR(60)  DEFAULT NULL COMMENT '片区(模糊,不存精确住址)',
  group_size       INT NOT NULL DEFAULT 1 COMMENT '1/2/3-4取4/5-8取8',
  relation_mode    VARCHAR(16)  DEFAULT NULL COMMENT '朋友/同事/伴侣/家人(只改语言,不做敏感推断)',
  start_minute     INT DEFAULT NULL COMMENT '时段开始(当日分钟 0-1439)',
  end_minute       INT DEFAULT NULL COMMENT '时段结束;窗口=120-210分钟',
  budget_pp        INT DEFAULT NULL COMMENT '每人预算(元)',
  walk_limit       INT NOT NULL DEFAULT 10 COMMENT '单段步行上限(分钟)',
  mood             VARCHAR(60)  DEFAULT NULL COMMENT '气氛(逗号多选:热闹,松弛,动手,新鲜,安静)',
  must_have        VARCHAR(200) DEFAULT NULL COMMENT '全局硬条件(发起人+成员交集,逗号)',
  no_go            VARCHAR(200) DEFAULT NULL COMMENT '全局否决项(并集,逗号;不展示来自谁)',
  plan_state       VARCHAR(20) NOT NULL DEFAULT 'HOST_DRAFT' COMMENT 'HOST_DRAFT/READY_TO_CHECK/GROUP_CONFIRMED/BOOKED',
  drill_state      TINYINT NOT NULL DEFAULT 0 COMMENT 'G6:0未演练 1演练中 2已通过',
  drill_slot_id    BIGINT DEFAULT NULL COMMENT '演练中被打掉的主选槽位',
  drill_deadline   DATETIME DEFAULT NULL COMMENT '演练截止(开始+30秒,服务端判定)',
  booking_owner    VARCHAR(30) DEFAULT NULL COMMENT '预约责任人(发起人/某同行人昵称/待定);只记责任',
  booked_proof     VARCHAR(500) DEFAULT NULL COMMENT '外部预约凭证(玩家自报,文本或图 URL);非平台担保',
  signoff_text     VARCHAR(200) DEFAULT NULL COMMENT '发起人 G7 落款(结构化短句)',
  del_flag         TINYINT NOT NULL DEFAULT 0 COMMENT '0存在 2删除;删除同步清成员与槽位(§12.1)',
  create_time      DATETIME, update_time DATETIME,
  UNIQUE KEY uk_board_code (invite_code),
  KEY idx_board_owner (owner_member_id, del_flag),
  KEY idx_board_topic (topic_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='今天值得庆祝·仪式板';

CREATE TABLE celebration_board_slot (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  board_id      BIGINT NOT NULL,
  role          VARCHAR(16) NOT NULL COMMENT 'START/TOGETHER/LAND/MARK',
  rank_no       TINYINT NOT NULL DEFAULT 0 COMMENT '0主选 1备选',
  title         VARCHAR(80) NOT NULL COMMENT '候选名(商家/公共点/自定义)',
  node_id       BIGINT DEFAULT NULL COMMENT '可选:挂主题章节节点(商家候选)',
  start_minute  INT DEFAULT NULL COMMENT '本段开始(当日分钟)',
  end_minute    INT DEFAULT NULL COMMENT '本段结束',
  price_pp      INT NOT NULL DEFAULT 0 COMMENT '每人价格(元;公共点=0)',
  walk_minutes  INT NOT NULL DEFAULT 0 COMMENT '从上一段步行分钟(玩家自填/后台预填)',
  slot_state    TINYINT NOT NULL DEFAULT 0 COMMENT '0正常 1演练失效(G6 打掉)',
  del_flag      TINYINT NOT NULL DEFAULT 0,
  create_time   DATETIME, update_time DATETIME,
  KEY idx_slot_board (board_id, role, rank_no, del_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仪式板槽位(主选/备选)';

CREATE TABLE celebration_board_member (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  board_id      BIGINT NOT NULL,
  member_id     BIGINT NOT NULL COMMENT '小程序会员;发起人也占一行',
  display_name  VARCHAR(30) DEFAULT NULL COMMENT '板内昵称',
  is_owner      TINYINT NOT NULL DEFAULT 0,
  must_have     VARCHAR(30) DEFAULT NULL COMMENT 'G1:每人一个必须有',
  no_go         VARCHAR(30) DEFAULT NULL COMMENT 'G1:每人一个不能有;聚合后不展示来自谁',
  vote          TINYINT NOT NULL DEFAULT 0 COMMENT '0未表态 1可以 2不行;未回复永不算同意',
  signoff_text  VARCHAR(200) DEFAULT NULL COMMENT 'G7 落款;未回复者不被代写',
  del_flag      TINYINT NOT NULL DEFAULT 0,
  create_time   DATETIME, update_time DATETIME,
  UNIQUE KEY uk_board_member (board_id, member_id),
  KEY idx_member_boards (member_id, del_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仪式板成员(含发起人)';

-- 回读验证:
--   SELECT TABLE_NAME, ENGINE FROM information_schema.TABLES
--    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME LIKE 'celebration_board%';  -- 三行,全 InnoDB
-- 回滚:
--   DROP TABLE celebration_board_member; DROP TABLE celebration_board_slot; DROP TABLE celebration_board;
