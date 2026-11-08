-- M4 促销(组合包/完局限时/人数阶梯/首关)。礼品独立售价归 M3 礼品 SKU，不在本迁移。
-- 只新增 6 张表,不改任何既有表、不回填数据、不写任何资金列语义。
-- 依赖:base schema 的 cms_registration / oms_ticket / player_badge 只作为逻辑引用,
-- 本迁移不建外键到它们(跨域强耦合会在历史数据清理时挡住运维)。
-- 【默认关闭】本迁移只建表;没有任何一行配置时,报价与建单行为与现在完全一致。
-- 【前置】无。落库顺序:先手工执行本迁移,再部署新 jar,最后由后台按主题逐条配置并启用。
-- 幂等:表/索引/约束已存在则跳过;列或唯一键形状不符时由校验段报错,不静默改表。
DROP TEMPORARY TABLE IF EXISTS marketing_promotion_guard;
CREATE TEMPORARY TABLE marketing_promotion_guard(id TINYINT PRIMARY KEY);
INSERT INTO marketing_promotion_guard VALUES(1);

CREATE TABLE IF NOT EXISTS marketing_offer (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  offer_code VARCHAR(40) NOT NULL COMMENT '稳定业务码:客户端只回传它,不回传价格',
  offer_kind TINYINT NOT NULL COMMENT '1=组合包(打包价);礼品独立售价由 M3 礼品 SKU 承接,不在本表',
  name VARCHAR(80) NOT NULL COMMENT '展示名',
  description VARCHAR(500) NULL COMMENT '展示说明',
  owner_type TINYINT NOT NULL COMMENT '1=主题 2=活动,与 cms_registration.owner_type 对齐',
  owner_id BIGINT NOT NULL COMMENT '主题/活动ID',
  price_amount DECIMAL(18,2) NOT NULL COMMENT '组合包打包价(元)',
  list_amount DECIMAL(18,2) NOT NULL COMMENT '成员分买合计快照(服务端算,仅用于校验与展示)',
  status TINYINT NOT NULL DEFAULT 0 COMMENT '0=停用 1=启用 2=下架',
  valid_from DATETIME NULL COMMENT '生效起;NULL=不设起点',
  valid_to DATETIME NULL COMMENT '生效止;NULL=不设终点',
  max_per_member INT NOT NULL DEFAULT 1 COMMENT '同一会员可购买次数上限',
  rule_version INT NOT NULL DEFAULT 1 COMMENT '规则版本,服务端递增;报价签名与订单快照都带它',
  del_flag TINYINT NOT NULL DEFAULT 0 COMMENT '0=存在 2=删除',
  create_by VARCHAR(64) NULL,
  create_time DATETIME NULL,
  update_by VARCHAR(64) NULL,
  update_time DATETIME NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_offer_code (offer_code),
  KEY idx_offer_scope (owner_type, owner_id, status),
  CONSTRAINT ck_offer_kind CHECK (offer_kind IN (1)),
  CONSTRAINT ck_offer_status CHECK (status IN (0,1,2)),
  CONSTRAINT ck_offer_price CHECK (price_amount >= 0),
  CONSTRAINT ck_offer_list CHECK (list_amount >= 0),
  CONSTRAINT ck_offer_max CHECK (max_per_member >= 0),
  CONSTRAINT ck_offer_window CHECK (valid_from IS NULL OR valid_to IS NULL OR valid_to > valid_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='促销供给:组合包打包价与成员(不含任何分润费率)';

CREATE TABLE IF NOT EXISTS marketing_offer_item (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  offer_id BIGINT NOT NULL COMMENT 'marketing_offer.id',
  item_type TINYINT NOT NULL DEFAULT 1 COMMENT '1=票种(oms_ticket.id)',
  item_id BIGINT NOT NULL COMMENT '票种ID',
  quantity INT NOT NULL DEFAULT 1 COMMENT '该票种张数',
  unit_list_amount DECIMAL(18,2) NOT NULL COMMENT '配置时票面价快照,仅校验与展示',
  create_time DATETIME NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_offer_item (offer_id, item_type, item_id),
  KEY idx_offer_item_ticket (item_id),
  CONSTRAINT fk_offer_item_offer FOREIGN KEY (offer_id)
    REFERENCES marketing_offer (id) ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT ck_offer_item_type CHECK (item_type IN (1)),
  CONSTRAINT ck_offer_item_qty CHECK (quantity > 0),
  CONSTRAINT ck_offer_item_price CHECK (unit_list_amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='促销供给成员:一张票+张数;报价必须逐项与购物车一致才生效';

CREATE TABLE IF NOT EXISTS marketing_tier (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tier_code VARCHAR(64) NOT NULL COMMENT '业务码(后台检索/日志定位)',
  owner_type TINYINT NOT NULL COMMENT '1=主题 2=活动',
  owner_id BIGINT NOT NULL,
  ticket_id BIGINT NOT NULL DEFAULT 0 COMMENT '票种ID;0=该范围全部票种',
  min_quantity INT NOT NULL COMMENT '起始人数(含)',
  max_quantity INT NULL COMMENT '结束人数(含);NULL=无上界',
  unit_price DECIMAL(18,2) NOT NULL COMMENT '该区间的单价(≤票面价才生效)',
  status TINYINT NOT NULL DEFAULT 0 COMMENT '0=停用 1=启用',
  rule_version INT NOT NULL DEFAULT 1,
  valid_from DATETIME NULL,
  valid_to DATETIME NULL,
  del_flag TINYINT NOT NULL DEFAULT 0,
  create_by VARCHAR(64) NULL,
  create_time DATETIME NULL,
  update_by VARCHAR(64) NULL,
  update_time DATETIME NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tier_code (tier_code),
  UNIQUE KEY uk_tier_range (owner_type, owner_id, ticket_id, min_quantity),
  KEY idx_tier_scope (owner_type, owner_id, status),
  CONSTRAINT ck_tier_owner CHECK (owner_type IN (1,2)),
  CONSTRAINT ck_tier_status CHECK (status IN (0,1)),
  CONSTRAINT ck_tier_min CHECK (min_quantity >= 1),
  CONSTRAINT ck_tier_range CHECK (max_quantity IS NULL OR max_quantity >= min_quantity),
  CONSTRAINT ck_tier_price CHECK (unit_price >= 0),
  CONSTRAINT ck_tier_window CHECK (valid_from IS NULL OR valid_to IS NULL OR valid_to > valid_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人数阶梯价:买家侧按本单人数改单价;不是商家侧保底率阶梯';

CREATE TABLE IF NOT EXISTS marketing_promotion (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  promo_code VARCHAR(40) NOT NULL COMMENT '稳定业务码',
  trigger_kind TINYINT NOT NULL COMMENT '1=完局后限时 2=首关完成后有效期',
  name VARCHAR(80) NOT NULL,
  description VARCHAR(500) NULL,
  scope_type TINYINT NOT NULL DEFAULT 0 COMMENT '0=全局 1=主题 2=活动',
  scope_id BIGINT NULL COMMENT 'scope_type=1/2 时必填',
  discount_type TINYINT NOT NULL COMMENT '1=固定减免(元) 2=折扣率(减掉的比例)',
  discount_value DECIMAL(12,4) NOT NULL COMMENT '金额或比率,含义由 discount_type 决定',
  max_discount_amount DECIMAL(18,2) NULL COMMENT '比率折扣的封顶金额',
  window_hours INT NOT NULL COMMENT '触发事实之后多少小时内有效;无默认值,必须显式配置',
  min_payable_amount DECIMAL(18,2) NULL COMMENT '折前金额门槛;NULL=无门槛',
  exclusive_flag TINYINT NOT NULL DEFAULT 0 COMMENT '1=独占:生效后其余促销不参与',
  stack_group VARCHAR(32) NOT NULL DEFAULT 'DEFAULT' COMMENT '同组只取一个',
  priority INT NOT NULL DEFAULT 0 COMMENT '越大越先生效',
  max_uses_per_member INT NOT NULL DEFAULT 1 COMMENT '会员使用次数上限;<=0 视为不限',
  status TINYINT NOT NULL DEFAULT 0 COMMENT '0=停用 1=启用',
  rule_version INT NOT NULL DEFAULT 1,
  valid_from DATETIME NULL,
  valid_to DATETIME NULL,
  del_flag TINYINT NOT NULL DEFAULT 0,
  create_by VARCHAR(64) NULL,
  create_time DATETIME NULL,
  update_by VARCHAR(64) NULL,
  update_time DATETIME NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_promo_code (promo_code),
  KEY idx_promo_scope (scope_type, scope_id, status),
  CONSTRAINT ck_promo_trigger CHECK (trigger_kind IN (1,2)),
  CONSTRAINT ck_promo_scope CHECK (scope_type IN (0,1,2)),
  CONSTRAINT ck_promo_discount_type CHECK (discount_type IN (1,2)),
  CONSTRAINT ck_promo_status CHECK (status IN (0,1)),
  CONSTRAINT ck_promo_window CHECK (window_hours > 0),
  CONSTRAINT ck_promo_priority CHECK (priority >= 0),
  CONSTRAINT ck_promo_window_dates CHECK (valid_from IS NULL OR valid_to IS NULL OR valid_to > valid_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='触发式优惠:资格只认服务端通关事实;窗口时长每条显式配置';

CREATE TABLE IF NOT EXISTS marketing_promotion_redemption (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  promotion_id BIGINT NOT NULL COMMENT 'marketing_promotion.id',
  promo_code VARCHAR(40) NOT NULL COMMENT '业务码冗余,配置改名后快照仍可读',
  member_id BIGINT NOT NULL COMMENT '下单人(=资格主体,不是收礼人)',
  source_fact_type TINYINT NOT NULL COMMENT '1=player_badge 通关事实(当前唯一资格来源)',
  source_fact_id BIGINT NOT NULL COMMENT '触发事实主键;一次性语义靠它判重',
  order_type TINYINT NOT NULL COMMENT '1=cms_registration',
  order_id BIGINT NOT NULL,
  discount_amount DECIMAL(18,2) NOT NULL COMMENT '本单实际抵扣(元),来自报价计算',
  rule_version INT NOT NULL,
  status TINYINT NOT NULL DEFAULT 1 COMMENT '1=已使用 2=随退款撤销(只置状态,不删行)',
  idempotency_key VARCHAR(96) NOT NULL,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_redemption_fact (promotion_id, member_id, source_fact_id),
  UNIQUE KEY uk_redemption_order (promotion_id, order_type, order_id),
  UNIQUE KEY uk_redemption_idem (idempotency_key),
  KEY idx_redemption_order (order_type, order_id),
  CONSTRAINT fk_redemption_promotion FOREIGN KEY (promotion_id)
    REFERENCES marketing_promotion (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT ck_redemption_fact_type CHECK (source_fact_type IN (1)),
  CONSTRAINT ck_redemption_status CHECK (status IN (1,2)),
  CONSTRAINT ck_redemption_amount CHECK (discount_amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='促销使用事实:insert-only;退款只改状态不删行,防止退款后再领一次';

CREATE TABLE IF NOT EXISTS marketing_order_adjustment (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  order_type TINYINT NOT NULL COMMENT '1=cms_registration(v1 仅此一种)',
  order_id BIGINT NOT NULL,
  member_id BIGINT NOT NULL COMMENT '下单人',
  owner_type TINYINT NOT NULL COMMENT '1=主题 2=活动',
  owner_id BIGINT NOT NULL,
  list_amount DECIMAL(18,2) NOT NULL COMMENT '票面合计(任何优惠之前)',
  tier_discount_amount DECIMAL(18,2) NOT NULL COMMENT '阶梯让利',
  offer_discount_amount DECIMAL(18,2) NOT NULL COMMENT '组合包相对分买的差额(>=0)',
  promotion_discount_amount DECIMAL(18,2) NOT NULL COMMENT '触发式优惠让利合计',
  payable_amount DECIMAL(18,2) NOT NULL COMMENT '一切让利后应付(未扣积分)',
  offer_id BIGINT NULL COMMENT 'marketing_offer.id',
  offer_code VARCHAR(40) NULL,
  applied_json JSON NOT NULL COMMENT '分项正文:行/分项/排除原因/版本/签名',
  rule_version VARCHAR(128) NOT NULL COMMENT '参与计算的规则版本串',
  quote_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '促销报价指纹',
  create_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_adjustment_order (order_type, order_id),
  KEY idx_adjustment_offer (offer_id, member_id),
  KEY idx_adjustment_member (member_id, create_time),
  CONSTRAINT ck_adjustment_order_type CHECK (order_type IN (1)),
  CONSTRAINT ck_adjustment_amounts CHECK (list_amount >= 0 AND payable_amount >= 0
    AND promotion_discount_amount >= 0 AND tier_discount_amount >= 0 AND offer_discount_amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单促销成交快照:一单一行,退款/对账读它,不重算现规则';

-- 退款到账事实(促销域自己的只读账):每个 refund-settled 事实一行,用于累计判断"这单是否已全额退完"。
-- 为什么必须累计而不是看单笔:部分退款是既有能力(admitted partial),一次 50% 不等于整单退;
-- 只有累计实退 ≥ 原支付总额才允许撤销整单的促销资格 —— 否则用户付了一小部分却丢了优惠。
CREATE TABLE IF NOT EXISTS marketing_promotion_refund_fact (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  order_type TINYINT NOT NULL COMMENT '1=cms_registration,与 marketing_order_adjustment.order_type 对齐',
  order_id BIGINT NOT NULL COMMENT '促销成交快照所在的报名单 id(组合/多票按 parent_order_no 解析,可能不是被退那张)',
  refund_registration_id BIGINT NOT NULL COMMENT '实际被退款的报名单 id(审计用;子单退款时与 order_id 不同)',
  refund_application_id BIGINT NOT NULL COMMENT 'refund_application.id',
  out_refund_no VARCHAR(64) NOT NULL COMMENT '我方退款单号(对账口径)',
  refunded_fen BIGINT NOT NULL COMMENT '本次实退金额(分),来自已校验的渠道退款事实',
  total_fen BIGINT NOT NULL COMMENT '原支付总额(分),同一单各事实应一致',
  idempotency_key VARCHAR(96) NOT NULL COMMENT 'refund-settled:{registrationId}:{refundApplicationId}',
  create_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_promo_refund_idem (idempotency_key),
  UNIQUE KEY uk_promo_refund_app (order_type, order_id, refund_application_id),
  KEY idx_promo_refund_order (order_type, order_id),
  CONSTRAINT ck_promo_refund_order_type CHECK (order_type IN (1)),
  CONSTRAINT ck_promo_refund_amount CHECK (refunded_fen >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退款到账事实(促销域):累计后才撤销整单促销资格,部分退款不撤销';

-- runner: assert=scalar-true
SELECT CAST(IF(
  (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
    AND table_name IN ('marketing_offer','marketing_offer_item','marketing_tier',
                       'marketing_promotion','marketing_promotion_redemption','marketing_order_adjustment',
                       'marketing_promotion_refund_fact')
    AND UPPER(engine) = 'INNODB') = 7
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE()
    AND index_name IN ('uk_offer_code','uk_offer_item','uk_tier_code','uk_tier_range',
                       'uk_promo_code','uk_redemption_fact','uk_redemption_order',
                       'uk_redemption_idem','uk_adjustment_order','uk_promo_refund_idem','uk_promo_refund_app')
    AND non_unique = 0) >= 11
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name = 'marketing_promotion' AND column_name = 'window_hours'
    AND column_default IS NULL AND is_nullable = 'NO') = 1
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name IN ('marketing_offer','marketing_offer_item','marketing_tier',
                       'marketing_promotion','marketing_promotion_redemption',
                       'marketing_order_adjustment','marketing_promotion_refund_fact')
    AND column_name IN ('fee_rate','creator_rate','inviter_rate','platform_rate',
                        'wallet_balance','refund_policy','settlement_id')) = 0,
  1, JSON_EXTRACT('ASSERT_FAILED_marketing_promotion_readback','$')) AS UNSIGNED) AS assertion_ok;
