-- M3 商品包/通行证/礼品（裁决 A：cms_registration / registration_entitlement 是唯一可玩真源）。
-- 本迁移只新增 4 张营销辅助表：商品目录、作品绑定、购买来源、礼品领取来源。
-- 不新增第二套订单/权益/席位/钱包表；不引用也未定义结算、费率、支付状态列。
-- Source-only ordered migration. 不执行生产 DDL；须走 chengyin-db-migrate 只读预检并由 root 决定落库时点。
-- runner: assert=scalar-true
SELECT CAST(IF(DATABASE() IS NOT NULL
  AND CAST(SUBSTRING_INDEX(VERSION(),'.',1) AS UNSIGNED)=8
  AND (CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(VERSION(),'.',2),'.',-1) AS UNSIGNED)>0
    OR CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(VERSION(),'-',1),'.',-1) AS UNSIGNED)>=16)
  AND (FIND_IN_SET('STRICT_TRANS_TABLES',@@sql_mode)>0 OR FIND_IN_SET('STRICT_ALL_TABLES',@@sql_mode)>0)
  AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
    AND table_name IN ('marketing_product','marketing_product_item',
      'marketing_product_order','gift_entitlement'))=0
  AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
    AND table_name IN ('marketing_entitlement','marketing_entitlement_item',
      'marketing_seat_entitlement','marketing_seat_claim_token'))=0,
  1,JSON_EXTRACT('ASSERT_FAILED_marketing_product_preflight','$')) AS UNSIGNED) AS assertion_ok;

-- 商品目录。purchase_kind / explore_capacity 是 M3 提供给 M2-F 的 kind→PurchaseKind 唯一来源。
CREATE TABLE IF NOT EXISTS marketing_product (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    product_code VARCHAR(48) NOT NULL COMMENT '运营配置的稳定商品编码',
    product_name VARCHAR(120) NOT NULL,
    product_kind TINYINT NOT NULL COMMENT '1单关 2城市包 3限时3/7日 4固定期年卡 5团体 6学校 7礼品 8加座',
    purchase_kind TINYINT NOT NULL DEFAULT 2 COMMENT '落 cms_registration.purchase_kind：2=自玩通行证 3=探索票',
    explore_capacity TINYINT NOT NULL DEFAULT 0 COMMENT '1=该商品行是③探索票，才调 reserveEntitlements 占章节容量',
    sale_city VARCHAR(64) NULL COMMENT '城市绑定；NULL=不按城市限售',
    parent_product_id BIGINT UNSIGNED NULL COMMENT '加座商品指向被加座的通行证商品',
    fulfillment_route TINYINT NOT NULL DEFAULT 1 COMMENT '1=M2F自玩grant 2=既有ticket报名 3=加座grant',
    ticket_id BIGINT NULL COMMENT 'route=2 时绑定的既有 oms_ticket.id（席位/团体由原链路承载）',
    cash_price DECIMAL(12,2) NULL COMMENT '现金价（元）',
    points_price INT NULL COMMENT '积分价（宝石=现有可兑换积分，不新钱包）',
    validity_kind TINYINT NOT NULL COMMENT '0永久 1购买起N日 2固定期限 3首次使用起N日',
    valid_days INT NULL,
    fixed_valid_from DATETIME NULL,
    fixed_valid_to DATETIME NULL,
    seat_count INT NOT NULL DEFAULT 1 COMMENT '每份商品包含的席位（团体/学校/加座）',
    gift_allowed TINYINT NOT NULL DEFAULT 0,
    gift_claim_days INT NULL,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0草稿 1在售 2停售',
    sale_start_at DATETIME NULL,
    sale_end_at DATETIME NULL,
    terms_version VARCHAR(40) NOT NULL COMMENT '条款版本快照',
    revision BIGINT NOT NULL DEFAULT 1 COMMENT '商品配置修订，改价/改内容必须递增',
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_marketing_product_code (product_code),
    KEY idx_marketing_product_sale (status, sale_start_at, sale_end_at),
    KEY idx_marketing_product_city (sale_city, status),
    CONSTRAINT fk_marketing_product_parent FOREIGN KEY (parent_product_id)
        REFERENCES marketing_product (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_marketing_product_kind CHECK (product_kind IN (1,2,3,4,5,6,7,8)),
    CONSTRAINT ck_marketing_product_purchase_kind CHECK (purchase_kind IN (2,3)),
    CONSTRAINT ck_marketing_product_explore CHECK (explore_capacity IN (0,1)
        AND (explore_capacity = 0 OR purchase_kind = 3)),
    CONSTRAINT ck_marketing_product_status CHECK (status IN (0,1,2)),
    CONSTRAINT ck_marketing_product_price CHECK ((cash_price IS NOT NULL AND cash_price > 0)
        OR (points_price IS NOT NULL AND points_price > 0)),
    CONSTRAINT ck_marketing_product_validity CHECK (validity_kind IN (0,1,2,3)
        AND ((validity_kind IN (0,2)) OR (valid_days IS NOT NULL AND valid_days > 0))
        AND (validity_kind <> 2 OR (fixed_valid_from IS NOT NULL AND fixed_valid_to IS NOT NULL
             AND fixed_valid_to > fixed_valid_from))),
    CONSTRAINT ck_marketing_product_route CHECK (fulfillment_route IN (1,2,3)
        AND ((fulfillment_route = 2 AND ticket_id IS NOT NULL)
          OR (fulfillment_route <> 2 AND ticket_id IS NULL))),
    CONSTRAINT ck_marketing_product_seats CHECK (seat_count >= 1),
    CONSTRAINT ck_marketing_product_gift CHECK (gift_allowed IN (0,1)
        AND (gift_claim_days IS NULL OR gift_claim_days > 0)),
    CONSTRAINT ck_marketing_product_revision CHECK (revision >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='可售商品目录；不承载订单、权益或资金真源';

-- 商品绑定的作品与冻结内容修订（内容修订 / 商品 / 订单 / 授权四层分开）。
CREATE TABLE IF NOT EXISTS marketing_product_item (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    product_id BIGINT UNSIGNED NOT NULL,
    topic_id BIGINT NOT NULL,
    topic_title VARCHAR(160) NOT NULL COMMENT '绑定时的标题快照，公开读面不 join 未发布正文',
    content_revision BIGINT NOT NULL COMMENT '绑定时的 cms_topic.published_config_version',
    sort_no INT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_marketing_product_item (product_id, topic_id),
    KEY idx_marketing_product_item_topic (topic_id),
    CONSTRAINT fk_marketing_product_item_product FOREIGN KEY (product_id)
        REFERENCES marketing_product (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_marketing_product_item_revision CHECK (content_revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品-作品/城市绑定；各带冻结修订';

-- 购买来源与父子映射。可玩事实不在这里，在 cms_registration。
CREATE TABLE IF NOT EXISTS marketing_product_order (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_no VARCHAR(40) NOT NULL,
    member_id BIGINT NOT NULL COMMENT '付款/购买人；礼品受益人可不同',
    product_id BIGINT UNSIGNED NOT NULL,
    product_revision BIGINT NOT NULL,
    fulfillment_kind TINYINT NOT NULL COMMENT '1自用 2礼品',
    gift_recipient_member_id BIGINT NULL COMMENT '可选：预绑定领取人；NULL=凭领取码领取',
    original_order_id BIGINT UNSIGNED NULL COMMENT '加座来源订单（加座不再建营销权益）',
    pay_mode TINYINT NOT NULL COMMENT '1现金 2积分',
    quantity INT NOT NULL DEFAULT 1,
    unit_cash_price DECIMAL(12,2) NULL,
    unit_points_price INT NULL,
    total_cash_amount DECIMAL(12,2) NULL,
    total_points_amount INT NULL,
    currency VARCHAR(8) NOT NULL DEFAULT 'CNY',
    product_snapshot_json LONGTEXT NOT NULL COMMENT '下单时商品+items+分摊+条款快照',
    quote_sign CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    quote_expires_at DATETIME NOT NULL,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0待现金支付 1已付 2已取消 3已退款撤销',
    payment_fact_type TINYINT NULL COMMENT '1微信验证事实 2积分扣减流水',
    payment_fact_id BIGINT NULL,
    primary_registration_id BIGINT NULL COMMENT 'M2-F grant 产生的父 cms_registration.id',
    primary_registration_no VARCHAR(40) NULL COMMENT '父 registration_no，供 C07 退款回查',
    registration_ids_json TEXT NULL COMMENT '父/子 cms_registration.id 列表；分摊守恒在 payable_amount 上',
    fulfillment_state TINYINT NOT NULL DEFAULT 0 COMMENT '0未发放 1已由原注册服务发放 2首次使用待激活',
    paid_at DATETIME NULL,
    refunded_at DATETIME NULL,
    revoked_reason VARCHAR(120) NULL,
    request_id VARCHAR(64) NOT NULL,
    request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_marketing_order_no (order_no),
    UNIQUE KEY uk_marketing_order_request (member_id, request_id),
    KEY idx_marketing_order_member (member_id, create_time),
    KEY idx_marketing_order_product (product_id, status),
    KEY idx_marketing_order_primary_reg (primary_registration_no),
    CONSTRAINT fk_marketing_order_product FOREIGN KEY (product_id)
        REFERENCES marketing_product (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_marketing_order_original FOREIGN KEY (original_order_id)
        REFERENCES marketing_product_order (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_marketing_order_fulfillment CHECK (fulfillment_kind IN (1,2)),
    CONSTRAINT ck_marketing_order_pay CHECK (pay_mode IN (1,2)),
    CONSTRAINT ck_marketing_order_status CHECK (status IN (0,1,2,3)),
    CONSTRAINT ck_marketing_order_grant_state CHECK (fulfillment_state IN (0,1,2)),
    CONSTRAINT ck_marketing_order_qty CHECK (quantity >= 1),
    CONSTRAINT ck_marketing_order_amount CHECK (
        (pay_mode=1 AND total_cash_amount IS NOT NULL AND total_cash_amount > 0)
        OR (pay_mode=2 AND total_points_amount IS NOT NULL AND total_points_amount > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品购买来源；可玩事实在 cms_registration，本表只做来源与父子映射';

-- 成交时冻结的分摊行（M2-F 首次使用激活只读本表，不解析 JSON、不猜格式）。
CREATE TABLE IF NOT EXISTS marketing_product_order_line (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_id BIGINT UNSIGNED NOT NULL,
    topic_id BIGINT NOT NULL,
    list_amount DECIMAL(12,2) NOT NULL,
    payable_amount DECIMAL(12,2) NOT NULL,
    validity_kind TINYINT NOT NULL COMMENT '-1首次使用 0永久 1购买起N日 2固定期限',
    valid_days INT NOT NULL DEFAULT 0,
    fixed_valid_from DATETIME NULL,
    fixed_valid_to DATETIME NULL,
    explore_capacity TINYINT NOT NULL DEFAULT 0,
    sort_no INT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_marketing_order_line (order_id, topic_id),
    CONSTRAINT fk_marketing_order_line_order FOREIGN KEY (order_id)
        REFERENCES marketing_product_order (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_marketing_order_line_validity CHECK (validity_kind IN (-1,0,1,2)),
    CONSTRAINT ck_marketing_order_line_amount CHECK (list_amount >= 0 AND payable_amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='成交冻结分摊行；激活/对账/退款只读，不参与可玩判定';

-- 礼品领取来源。领取成功指向既有 cms_registration / registration_entitlement，不建第二权益。
CREATE TABLE IF NOT EXISTS gift_entitlement (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id VARCHAR(32) NOT NULL,
    order_id BIGINT UNSIGNED NOT NULL,
    product_id BIGINT UNSIGNED NOT NULL,
    source_registration_id BIGINT NULL COMMENT '买家已付的 cms_registration.id（单行语义的唯一可玩真源）',
    buyer_member_id BIGINT NOT NULL,
    recipient_member_id BIGINT NULL COMMENT '预绑定领取人；NULL=凭码领取',
    claim_token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    claim_expires_at DATETIME NULL,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0待领取 1已领取 2已过期 3已撤销',
    claimed_member_id BIGINT NULL,
    claimed_registration_id BIGINT NULL COMMENT '领取后授予的 cms_registration.id（由原注册服务产出）',
    claimed_at DATETIME NULL,
    claim_request_id VARCHAR(64) NULL,
    revoked_reason VARCHAR(120) NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_gift_entitlement_public (public_id),
    UNIQUE KEY uk_gift_entitlement_order (order_id),
    UNIQUE KEY uk_gift_entitlement_token (claim_token_hash),
    KEY idx_gift_entitlement_source_reg (source_registration_id),
    KEY idx_gift_entitlement_buyer (buyer_member_id, status),
    KEY idx_gift_entitlement_claimed (claimed_member_id, status),
    CONSTRAINT fk_gift_entitlement_order FOREIGN KEY (order_id)
        REFERENCES marketing_product_order (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_gift_entitlement_status CHECK (status IN (0,1,2,3)),
    CONSTRAINT ck_gift_entitlement_claim CHECK (status <> 1
        OR (claimed_member_id IS NOT NULL AND claimed_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='礼品领取来源；一次领取幂等，成功后指向原 registration';

-- runner: assert=scalar-true
SELECT CAST(IF(
  (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
    AND table_name IN ('marketing_product','marketing_product_item',
      'marketing_product_order','marketing_product_order_line','gift_entitlement')
    AND UPPER(engine)='INNODB')=5
  AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
    AND table_name IN ('marketing_entitlement','marketing_entitlement_item',
      'marketing_seat_entitlement','marketing_seat_claim_token'))=0
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND index_name IN ('uk_marketing_product_code','uk_marketing_product_item','uk_marketing_order_no',
      'uk_marketing_order_request','uk_gift_entitlement_order','uk_gift_entitlement_token')
    AND non_unique=0)>=6
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name IN ('marketing_product','marketing_product_order')
    AND column_name IN ('payment_status','fee_rate','settlement_id','auto_renew','wallet_balance'))=0
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='marketing_product'
    AND column_name IN ('purchase_kind','explore_capacity','fulfillment_route','ticket_id'))=4
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name IN ('marketing_product','marketing_product_order_line')
    AND column_name IN ('refund_policy_code','refund_policy_version'))=0
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='gift_entitlement' AND column_name='source_registration_id')=1,
  1,JSON_EXTRACT('ASSERT_FAILED_marketing_product_readback','$')) AS UNSIGNED) AS assertion_ok;
