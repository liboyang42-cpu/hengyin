-- DOC2 F3 自由探索(product_type=2)套票 Phase 1(决策 D-11)：订单快照 / 发放 / 单位码 / 预约 / 月度豁免 / 商家退出处置。
-- 只新建 6 张表，不改既有表、不回填数据；既有 cms_topic / cms_topic_chapter / merchant_chapter_offer /
-- registration_entitlement / redemption 原样复用(本批只在其旁边记「每持有人每单位」账)。
-- 【前置】无(不建外键；registration_id / chapter_id / offer_id 指向的既有表在 base schema 中)。
-- 【落库顺序】先手工执行本迁移，再部署新 jar，
--   最后才按需打开 chengyin.free-explore.bundle.ledger-enabled；sale-enabled / reservation-enabled 默认关闭。
-- 幂等：表不存在则建；已存在则逐表核对列 fingerprint 与唯一键 fingerprint，不一致时 guard 主键冲突让客户端报错，不改不删。
-- 余额口径：持有人余额 = explore_bundle_unit 按 (registration_id, holder_member_id, chapter_id, grant_id) 计数，
--   绝不读 merchant_chapter_offer.quota_total - quota_used 这类共享名额当个人余额。
DROP TEMPORARY TABLE IF EXISTS doc2_f3_bundle_guard;
CREATE TEMPORARY TABLE doc2_f3_bundle_guard(id TINYINT PRIMARY KEY);
INSERT INTO doc2_f3_bundle_guard VALUES(1);

-- 订单快照：建单即冻结产品/名额/政策/合同/结算规则五个版本；只插不改(mapper 无 update 语句)。
CREATE TABLE IF NOT EXISTS explore_bundle_order (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  registration_id BIGINT NOT NULL COMMENT '既有报名单 cms_registration.id(订单真源仍是它)',
  holder_member_id BIGINT NOT NULL COMMENT '持有人会员ID(=购票人)',
  topic_id BIGINT NOT NULL COMMENT 'cms_topic.id(product_type=2)',
  activity_id BIGINT NULL COMMENT 'cms_activity.id(活动售票路径)',
  product_version CHAR(64) NOT NULL COMMENT '产品快照 SHA-256',
  quota_version CHAR(64) NOT NULL COMMENT '名额快照 SHA-256',
  policy_version VARCHAR(32) NOT NULL COMMENT '退款/预约政策版本号',
  contract_version CHAR(64) NOT NULL COMMENT '商家报价合同快照 SHA-256',
  settlement_rule_version VARCHAR(64) NOT NULL COMMENT '结算规则版本(rule id + 更新时间)',
  snapshot_json JSON NOT NULL COMMENT '冻结快照正文',
  paid_amount DECIMAL(18,2) NOT NULL COMMENT '建单时应付金额',
  valid_from DATETIME NOT NULL COMMENT '有效期起',
  valid_to DATETIME NOT NULL COMMENT '有效期止',
  idempotency_key VARCHAR(96) NOT NULL COMMENT '幂等键 bundle-order:{registrationId}',
  request_fingerprint CHAR(64) NOT NULL COMMENT '快照正文指纹',
  create_time DATETIME NOT NULL COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_bundle_order_registration (registration_id),
  UNIQUE KEY uk_bundle_order_idem (idempotency_key),
  KEY idx_bundle_order_holder (holder_member_id, create_time),
  KEY idx_bundle_order_topic (topic_id),
  CONSTRAINT ck_bundle_order_validity CHECK (valid_to > valid_from),
  CONSTRAINT ck_bundle_order_amount CHECK (paid_amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='F3 套票订单冻结快照';

-- 发放：每 (订单, 持有人, 章节, 发放序号) 一行；PURCHASE=购票发放，SWAP=经持有人同意的等值换发。
CREATE TABLE IF NOT EXISTS explore_bundle_grant (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  bundle_order_id BIGINT NOT NULL COMMENT 'explore_bundle_order.id',
  registration_id BIGINT NOT NULL COMMENT '报名单ID',
  holder_member_id BIGINT NOT NULL COMMENT '持有人会员ID',
  topic_id BIGINT NOT NULL COMMENT '主题ID',
  chapter_id BIGINT NOT NULL COMMENT '章节ID(权益项)',
  grant_no INT NOT NULL COMMENT '发放序号,1=购票',
  grant_type VARCHAR(16) NOT NULL COMMENT 'PURCHASE/SWAP',
  source_grant_id BIGINT NULL COMMENT 'SWAP 来源发放',
  units_total INT NOT NULL COMMENT '本次发放单位数',
  valid_from DATETIME NOT NULL COMMENT '有效期起',
  valid_to DATETIME NOT NULL COMMENT '有效期止(换发不得缩短)',
  create_time DATETIME NOT NULL COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_bundle_grant_key (registration_id, holder_member_id, chapter_id, grant_no),
  KEY idx_bundle_grant_order (bundle_order_id),
  CONSTRAINT ck_bundle_grant_type CHECK (grant_type IN ('PURCHASE','SWAP')),
  CONSTRAINT ck_bundle_grant_units CHECK (units_total > 0),
  CONSTRAINT ck_bundle_grant_validity CHECK (valid_to > valid_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='F3 套票发放(余额键)';

-- 单位：一单位一行一码，状态即余额真源。罚没(FORFEITED)不写 redemption，故不产生商家分成。
CREATE TABLE IF NOT EXISTS explore_bundle_unit (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  grant_id BIGINT NOT NULL COMMENT 'explore_bundle_grant.id',
  registration_id BIGINT NOT NULL COMMENT '报名单ID',
  holder_member_id BIGINT NOT NULL COMMENT '持有人会员ID',
  chapter_id BIGINT NOT NULL COMMENT '章节ID',
  unit_seq INT NOT NULL COMMENT '发放内序号,从1起',
  unit_code VARCHAR(32) NOT NULL COMMENT '单位码(结算单位维度)',
  unit_value DECIMAL(18,2) NOT NULL COMMENT '单位价值(退款比例口径)',
  status VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE' COMMENT 'AVAILABLE/RESERVED/REDEEMED/FORFEITED/HELD/REFUND_PENDING/REFUNDED',
  redemption_id BIGINT NULL COMMENT '真实核销 redemption.id',
  reservation_id BIGINT NULL COMMENT '当前/最后预约',
  status_reason VARCHAR(32) NULL COMMENT '状态原因',
  version INT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  create_time DATETIME NOT NULL COMMENT '创建时间',
  update_time DATETIME NULL COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_bundle_unit_seq (grant_id, unit_seq),
  UNIQUE KEY uk_bundle_unit_code (unit_code),
  UNIQUE KEY uk_bundle_unit_redemption (redemption_id),
  KEY idx_bundle_unit_holder (registration_id, holder_member_id, chapter_id, status),
  CONSTRAINT ck_bundle_unit_status CHECK (status IN
    ('AVAILABLE','RESERVED','REDEEMED','FORFEITED','HELD','REFUND_PENDING','REFUNDED')),
  CONSTRAINT ck_bundle_unit_redeemed CHECK ((status = 'REDEEMED') = (redemption_id IS NOT NULL)),
  CONSTRAINT ck_bundle_unit_value CHECK (unit_value >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='F3 套票单位(每持有人余额真源)';

-- 预约：开始前 >=12h 免费取消；<12h 或爽约只消耗该单位；同一单位同时只许一条 BOOKED。
CREATE TABLE IF NOT EXISTS explore_bundle_reservation (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  unit_id BIGINT NOT NULL COMMENT 'explore_bundle_unit.id',
  registration_id BIGINT NOT NULL COMMENT '报名单ID',
  holder_member_id BIGINT NOT NULL COMMENT '持有人会员ID',
  chapter_id BIGINT NOT NULL COMMENT '章节ID',
  offer_id BIGINT NOT NULL COMMENT 'merchant_chapter_offer.id(门店不得静默更换)',
  merchant_id BIGINT NOT NULL COMMENT '商家主账号会员ID(与 offer.merchant_id 同口径)',
  start_at DATETIME NOT NULL COMMENT '预约开始时间',
  status VARCHAR(16) NOT NULL DEFAULT 'BOOKED' COMMENT 'BOOKED/CANCELLED/WAIVED/LATE_CANCELLED/NO_SHOW/FULFILLED/MERCHANT_EXITED',
  idempotency_key VARCHAR(96) NOT NULL COMMENT '客户端幂等键',
  request_fingerprint CHAR(64) NOT NULL COMMENT '请求指纹',
  closed_at DATETIME NULL COMMENT '终态时间',
  version INT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  create_time DATETIME NOT NULL COMMENT '创建时间',
  update_time DATETIME NULL COMMENT '更新时间',
  active_unit_key BIGINT GENERATED ALWAYS AS (IF(status = 'BOOKED', unit_id, NULL)) STORED COMMENT '同单位唯一在约',
  PRIMARY KEY (id),
  UNIQUE KEY uk_bundle_reservation_idem (holder_member_id, idempotency_key),
  UNIQUE KEY uk_bundle_reservation_active_unit (active_unit_key),
  KEY idx_bundle_reservation_merchant (merchant_id, start_at),
  KEY idx_bundle_reservation_registration (registration_id, chapter_id),
  CONSTRAINT ck_bundle_reservation_status CHECK (status IN
    ('BOOKED','CANCELLED','WAIVED','LATE_CANCELLED','NO_SHOW','FULFILLED','MERCHANT_EXITED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='F3 套票预约';

-- 每会员每自然月(Asia/Shanghai)一次主动豁免：唯一键即闸。
CREATE TABLE IF NOT EXISTS explore_bundle_waiver (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  member_id BIGINT NOT NULL COMMENT '会员ID',
  waiver_month CHAR(7) NOT NULL COMMENT '自然月 yyyy-MM',
  reservation_id BIGINT NOT NULL COMMENT '被豁免的预约',
  create_time DATETIME NOT NULL COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_bundle_waiver_month (member_id, waiver_month),
  UNIQUE KEY uk_bundle_waiver_reservation (reservation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='F3 套票月度主动豁免';

-- 商家退出/闭店：持有人同意才换发等值项，否则退未履约部分；不静默换店、不缩短有效期。
CREATE TABLE IF NOT EXISTS explore_bundle_exit_case (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  unit_id BIGINT NOT NULL COMMENT 'explore_bundle_unit.id',
  registration_id BIGINT NOT NULL COMMENT '报名单ID',
  holder_member_id BIGINT NOT NULL COMMENT '持有人会员ID',
  exited_offer_id BIGINT NOT NULL COMMENT '已退出的 offer(无预约时为 0=章节无在营门店)',
  reservation_id BIGINT NULL COMMENT '受影响预约',
  decision VARCHAR(20) NOT NULL COMMENT 'SWAP_CONSENTED/REFUND_UNFULFILLED',
  target_offer_id BIGINT NULL COMMENT '持有人同意换去的 offer',
  idempotency_key VARCHAR(96) NOT NULL COMMENT '客户端幂等键',
  request_fingerprint CHAR(64) NOT NULL COMMENT '请求指纹',
  create_time DATETIME NOT NULL COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_bundle_exit_unit_offer (unit_id, exited_offer_id),
  UNIQUE KEY uk_bundle_exit_idem (holder_member_id, idempotency_key),
  CONSTRAINT ck_bundle_exit_decision CHECK (decision IN ('SWAP_CONSENTED','REFUND_UNFULFILLED')),
  CONSTRAINT ck_bundle_exit_target CHECK ((decision = 'SWAP_CONSENTED') = (target_offer_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='F3 套票商家退出处置(持有人决定)';

-- 同名但形状不对的旧表禁止当成已完成：列 fingerprint(表:列名:类型:可空) 取 SHA-256 比对。
-- 期望值由 FreeExploreBundleLedgerMySqlIT 在真 MySQL 上按同一表达式重算钉住；改表结构必须同步改这里。
SET SESSION group_concat_max_len = 65535;
SET @doc2_f3_columns := (SELECT GROUP_CONCAT(CONCAT_WS(':', table_name, column_name, column_type, is_nullable)
    ORDER BY table_name, ordinal_position SEPARATOR '|')
  FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name IN
  ('explore_bundle_order','explore_bundle_grant','explore_bundle_unit',
   'explore_bundle_reservation','explore_bundle_waiver','explore_bundle_exit_case'));
INSERT INTO doc2_f3_bundle_guard
SELECT 1 WHERE SHA2(COALESCE(@doc2_f3_columns, ''), 256) <> '9286d31ff88689206028b3ecaf9a8e8ec8eb197626fef1c76742e01bd5e27bc2';

-- 唯一键 fingerprint：余额键、单位码、核销一对一、预约在约唯一、月度豁免唯一 —— 任何一条缺失都是资金口径漏洞。
SET @doc2_f3_unique := (SELECT GROUP_CONCAT(CONCAT_WS(':', table_name, index_name, seq_in_index, column_name)
    ORDER BY table_name, index_name, seq_in_index SEPARATOR '|')
  FROM information_schema.statistics WHERE table_schema=DATABASE() AND non_unique=0 AND table_name IN
  ('explore_bundle_order','explore_bundle_grant','explore_bundle_unit',
   'explore_bundle_reservation','explore_bundle_waiver','explore_bundle_exit_case'));
INSERT INTO doc2_f3_bundle_guard
SELECT 1 WHERE SHA2(COALESCE(@doc2_f3_unique, ''), 256) <> 'fde16c6f53f573f86c1cc59d03db34b8641311fbf7dcce257dbd361b8cbcebb3';
DROP TEMPORARY TABLE doc2_f3_bundle_guard;

-- runner: assert=scalar-true
SELECT COUNT(*)=6 AS assertion_ok FROM information_schema.tables
WHERE table_schema=DATABASE() AND engine='InnoDB' AND table_name IN
  ('explore_bundle_order','explore_bundle_grant','explore_bundle_unit',
   'explore_bundle_reservation','explore_bundle_waiver','explore_bundle_exit_case');
