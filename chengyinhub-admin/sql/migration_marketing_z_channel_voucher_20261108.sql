-- M8 OTA 可信渠道码：主体 / 验证事实 / 兑换码 / 兑换回执 / 退款事实。
-- 只新增 5 张营销渠道表；不改 cms_registration、payment、refund、settlement、费率或 RBAC。
-- 依赖 M3 商品目录（marketing_product / marketing_product_item）与既有 oms_ticket 已存在；
-- 不依赖已按 root 裁决 A 退场的 marketing_product_order / marketing_entitlement*。
-- Source-only ordered migration. 不执行生产 DDL；须走 chengyin-db-migrate 只读预检并由 root 决定落库时点。

-- runner: assert=scalar-true
SELECT CAST(IF(DATABASE() IS NOT NULL
  AND CAST(SUBSTRING_INDEX(VERSION(),'.',1) AS UNSIGNED)=8
  AND (CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(VERSION(),'.',2),'.',-1) AS UNSIGNED)>0
    OR CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(VERSION(),'-',1),'.',-1) AS UNSIGNED)>=16)
  AND (FIND_IN_SET('STRICT_TRANS_TABLES',@@sql_mode)>0 OR FIND_IN_SET('STRICT_ALL_TABLES',@@sql_mode)>0)
  AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
    AND table_name IN ('marketing_product','marketing_product_item','oms_ticket'))=3
  AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
    AND table_name IN ('marketing_channel_voucher_account','marketing_channel_order_verification',
      'marketing_channel_voucher','marketing_channel_redemption',
      'marketing_channel_refund_verification'))=0,
  1,JSON_EXTRACT('ASSERT_FAILED_marketing_channel_voucher_preflight','$')) AS UNSIGNED) AS assertion_ok;

-- 渠道主体：合同、状态、结算口径（只描述合同，不产生应付/可提现）。
CREATE TABLE IF NOT EXISTS marketing_channel_voucher_account (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    channel_key VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定渠道键，与 providerKey 对齐',
    display_name VARCHAR(120) NOT NULL,
    contract_ref VARCHAR(120) NOT NULL COMMENT '合同/协议编号；空值不允许发码',
    settlement_mode TINYINT NOT NULL DEFAULT 0 COMMENT '0不结算 1预付 2月结；仅合同口径，不写账本',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0待启用 1 ACTIVE 2暂停 3撤销',
    effective_from DATETIME NOT NULL,
    effective_to DATETIME NULL,
    create_by BIGINT NOT NULL COMMENT '管理端操作人 sys_user id',
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_channel_account_key (channel_key),
    KEY idx_channel_account_status (status, effective_from, effective_to),
    CONSTRAINT ck_channel_account_mode CHECK (settlement_mode IN (0,1,2)),
    CONSTRAINT ck_channel_account_status CHECK (status IN (0,1,2,3)),
    CONSTRAINT ck_channel_account_window CHECK (effective_to IS NULL OR effective_to > effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='渠道发码主体；合同与窗口决定能否发行';

-- 渠道订单验证事实：L1 唯一可信来源。金额只用于对账，不转平台已收。
CREATE TABLE IF NOT EXISTS marketing_channel_order_verification (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    account_id BIGINT UNSIGNED NULL COMMENT '未知渠道回调可为空；空值只允许留痕拒绝',
    channel_key VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_key VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '真实 provider 实现键',
    channel_order_no VARCHAR(96) NOT NULL COMMENT '渠道订单号，必须可追溯',
    external_reference VARCHAR(128) NULL,
    event_id VARCHAR(96) NULL COMMENT '渠道回调事件 id；与 provider_key 组成入站幂等键',
    amount DECIMAL(12,2) NULL COMMENT '渠道侧实付快照；仅对账，不进 settlement',
    currency VARCHAR(8) NULL,
    sku_json TEXT NULL COMMENT '渠道 SKU 原文/映射快照；不含支付凭据',
    product_id BIGINT NULL COMMENT '映射到我方 M3 商品 id；VERIFIED 时必填',
    quantity INT NULL COMMENT '映射数量；VERIFIED 时 >0',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0待验证 1 VERIFIED 2 REJECTED 3 REVOKED',
    reason_code VARCHAR(48) NULL,
    verified_at DATETIME NULL,
    expires_at DATETIME NULL,
    raw_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '回调报文摘要；不存原文/签名密钥',
    request_id VARCHAR(64) NOT NULL,
    request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_channel_verification_order (account_id, channel_order_no),
    UNIQUE KEY uk_channel_verification_event (provider_key, event_id),
    UNIQUE KEY uk_channel_verification_request (account_id, request_id),
    KEY idx_channel_verification_status (account_id, status, create_time),
    CONSTRAINT fk_channel_verification_account FOREIGN KEY (account_id)
        REFERENCES marketing_channel_voucher_account (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_channel_verification_status CHECK (status IN (0,1,2,3)),
    CONSTRAINT ck_channel_verification_amount CHECK (status <> 1 OR (amount IS NOT NULL AND amount > 0)),
    CONSTRAINT ck_channel_verification_mapping CHECK (status <> 1 OR
        (product_id IS NOT NULL AND product_id > 0 AND quantity IS NOT NULL AND quantity > 0)),
    CONSTRAINT ck_channel_verification_currency CHECK (status <> 1 OR
        (currency IS NOT NULL AND CHAR_LENGTH(currency) BETWEEN 3 AND 8))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='渠道订单可信验证事实；只有 VERIFIED 才能发码';

-- 兑换码：明文只返回一次，库内只存哈希；一验证一码。
CREATE TABLE IF NOT EXISTS marketing_channel_voucher (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    account_id BIGINT UNSIGNED NOT NULL,
    verification_id BIGINT UNSIGNED NOT NULL,
    public_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'HTTP 可见标识，非秘密',
    code_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'SHA-256(明文码)；明文不落库',
    product_id BIGINT NOT NULL,
    product_revision BIGINT NOT NULL,
    quantity INT NOT NULL COMMENT '原商品数量；同码一次兑换按此数量授权',
    seat_count INT NOT NULL COMMENT '总席位 = 商品席位 × 数量；不是硬编码 1',
    subject_mode TINYINT NOT NULL COMMENT '1绑定会员 2开放持有人',
    bound_member_id BIGINT NULL,
    inventory_ticket_id BIGINT NULL COMMENT '可选：用既有 oms_ticket 做 L3 预留',
    inventory_quantity INT NULL,
    inventory_state TINYINT NOT NULL DEFAULT 0 COMMENT '0无预留 1已预留 2已消费 3已释放',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0 ACTIVE 1 REDEEMED 2 REVOKED 3 EXPIRED',
    issued_at DATETIME NOT NULL,
    expires_at DATETIME NOT NULL,
    revoke_source VARCHAR(32) NULL COMMENT 'OPERATOR/CHANNEL/EXPIRY/REFUND',
    revoke_reason VARCHAR(120) NULL COMMENT '撤销申请原因，不等于已撤销',
    revoke_request_id VARCHAR(64) NULL COMMENT '撤销申请幂等键',
    revoke_requested_at DATETIME NULL COMMENT '撤销申请时刻；非空仅代表待退款确认',
    issued_by BIGINT NOT NULL COMMENT '管理端操作人 sys_user id',
    request_id VARCHAR(64) NOT NULL,
    request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_channel_voucher_public (public_id),
    UNIQUE KEY uk_channel_voucher_code (code_hash),
    UNIQUE KEY uk_channel_voucher_verification (verification_id),
    UNIQUE KEY uk_channel_voucher_request (account_id, request_id),
    KEY idx_channel_voucher_status (account_id, status, expires_at),
    CONSTRAINT fk_channel_voucher_account FOREIGN KEY (account_id)
        REFERENCES marketing_channel_voucher_account (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_channel_voucher_verification FOREIGN KEY (verification_id)
        REFERENCES marketing_channel_order_verification (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_channel_voucher_subject CHECK ((subject_mode = 1 AND bound_member_id IS NOT NULL)
        OR (subject_mode = 2 AND bound_member_id IS NULL)),
    CONSTRAINT ck_channel_voucher_status CHECK (status IN (0,1,2,3)),
    CONSTRAINT ck_channel_voucher_inventory CHECK (inventory_state IN (0,1,2,3)
        AND ((inventory_ticket_id IS NULL AND inventory_quantity IS NULL)
          OR (inventory_ticket_id IS NOT NULL AND inventory_quantity IS NOT NULL AND inventory_quantity > 0))),
    CONSTRAINT ck_channel_voucher_qty CHECK (quantity >= 1 AND seat_count >= 1),
    CONSTRAINT ck_channel_voucher_window CHECK (expires_at > issued_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='渠道兑换码；码哈希、商品/数量/席位/有效期/撤销来源冻结';

-- 兑换回执：一码一行；唯一键防重复发权益。
CREATE TABLE IF NOT EXISTS marketing_channel_redemption (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    voucher_id BIGINT UNSIGNED NOT NULL,
    member_id BIGINT NOT NULL,
    channel_order_no VARCHAR(96) NOT NULL COMMENT '渠道单号快照，用于对账',
    registration_id BIGINT NULL COMMENT '既有 cms_registration.id 主单；权威可玩事实，不建跨域 FK',
    registration_no VARCHAR(40) NULL COMMENT '主单业务号快照',
    registration_ids_json VARCHAR(512) NULL COMMENT '全部注册行 id 快照（多作品包），用于原单回查',
    entitlement_ids_json VARCHAR(512) NULL COMMENT '既有 registration_entitlement.id 快照；仅 ③ 探索票',
    grant_ref VARCHAR(128) NULL COMMENT 'M2-F grant 端口的不可变回执引用',
    valid_from DATETIME NULL,
    valid_to DATETIME NULL,
    receipt_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0有效 1已撤销',
    revoke_reason VARCHAR(120) NULL,
    redeemed_at DATETIME NOT NULL,
    revoked_at DATETIME NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_channel_redemption_voucher (voucher_id),
    UNIQUE KEY uk_channel_redemption_receipt (receipt_no),
    UNIQUE KEY uk_channel_redemption_request (member_id, request_id),
    KEY idx_channel_redemption_member (member_id, redeemed_at),
    CONSTRAINT fk_channel_redemption_voucher FOREIGN KEY (voucher_id)
        REFERENCES marketing_channel_voucher (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_channel_redemption_status CHECK (status IN (0,1)),
    CONSTRAINT ck_channel_redemption_revoke CHECK (status <> 1 OR revoked_at IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='渠道码兑换回执；一码一权益，指向原注册/权益事实';

-- 渠道退款完成事实：仅 provider 确认到账后落 VERIFIED；部分退款保留未支持，不擅自撤权。
CREATE TABLE IF NOT EXISTS marketing_channel_refund_verification (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    account_id BIGINT UNSIGNED NOT NULL,
    purchase_verification_id BIGINT UNSIGNED NOT NULL COMMENT '原购买验证事实，用于金额/币种/订单绑定',
    channel_key VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_key VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    channel_order_no VARCHAR(96) NOT NULL,
    channel_refund_no VARCHAR(96) NOT NULL,
    event_id VARCHAR(96) NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    currency VARCHAR(8) NOT NULL,
    full_refund TINYINT NOT NULL DEFAULT 0 COMMENT '1=全额退款；0=部分退款(当前未支持撤权)',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0待确认 1已验证退款完成 2拒绝',
    consumed TINYINT NOT NULL DEFAULT 0 COMMENT '1=已被原注册撤销消费',
    reason_code VARCHAR(48) NULL,
    raw_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    verified_at DATETIME NULL,
    consumed_at DATETIME NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_channel_refund_event (provider_key, event_id),
    UNIQUE KEY uk_channel_refund_no (account_id, channel_refund_no),
    KEY idx_channel_refund_order (account_id, channel_order_no),
    KEY idx_channel_refund_consumed (consumed, status),
    CONSTRAINT fk_channel_refund_account FOREIGN KEY (account_id)
        REFERENCES marketing_channel_voucher_account (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_channel_refund_purchase FOREIGN KEY (purchase_verification_id)
        REFERENCES marketing_channel_order_verification (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_channel_refund_status CHECK (status IN (0,1,2)),
    CONSTRAINT ck_channel_refund_amount CHECK (amount > 0),
    CONSTRAINT ck_channel_refund_flags CHECK (full_refund IN (0,1) AND consumed IN (0,1)),
    CONSTRAINT ck_channel_refund_consumed CHECK (consumed = 0 OR (status = 1 AND consumed_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='渠道退款完成事实；只有 VERIFIED+consumed 才允许撤原注册';

-- runner: assert=scalar-true
SELECT CAST(IF(
  (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
    AND table_name IN ('marketing_channel_voucher_account','marketing_channel_order_verification',
      'marketing_channel_voucher','marketing_channel_redemption',
      'marketing_channel_refund_verification')
    AND UPPER(engine)='INNODB')=5
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND index_name IN ('uk_channel_account_key','uk_channel_verification_order',
      'uk_channel_verification_event','uk_channel_verification_request','uk_channel_voucher_public',
      'uk_channel_voucher_code','uk_channel_voucher_verification','uk_channel_voucher_request',
      'uk_channel_redemption_voucher','uk_channel_redemption_receipt','uk_channel_redemption_request',
      'uk_channel_refund_event','uk_channel_refund_no')
    AND non_unique=0)>=13
  AND (SELECT COUNT(*) FROM information_schema.key_column_usage WHERE constraint_schema=DATABASE()
    AND constraint_name='fk_channel_voucher_verification'
    AND column_name='verification_id' AND referenced_table_name='marketing_channel_order_verification'
    AND referenced_column_name='id')=1
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name IN ('marketing_channel_voucher_account','marketing_channel_order_verification',
      'marketing_channel_voucher','marketing_channel_redemption',
      'marketing_channel_refund_verification')
    AND column_name IN ('payable_amount','fee_rate','fee_platform_rate','settlement_id',
      'merchant_payable_id','withdrawable_amount'))=0,
  1,JSON_EXTRACT('ASSERT_FAILED_marketing_channel_voucher_readback','$')) AS UNSIGNED) AS assertion_ok;
