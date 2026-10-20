-- TEST FIXTURE ONLY. Exact CREATE projections from base_schema_20260818.sql and the three reviewed DRAFT files.
-- Executed only in a uniquely named disposable loopback MySQL database. No production activation.
CREATE TABLE `sms_coupon` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '优惠券名称',
  `coupon_type` int DEFAULT NULL COMMENT '优惠券类型：0礼品券 1、9折券  2、8折券',
  `platform` int DEFAULT NULL COMMENT '使用平台：0->全部；1->移动；2->PC',
  `amount` decimal(10,2) DEFAULT NULL COMMENT '金额 / 折扣系数',
  `min_point` decimal(10,2) DEFAULT NULL COMMENT '使用门槛（0.00表示无门槛）',
  `start_time` datetime DEFAULT NULL COMMENT '优惠券可使用开始时间',
  `end_time` datetime DEFAULT NULL COMMENT '优惠券可使用结束时间',
  `use_type` int DEFAULT NULL COMMENT '使用类型：0->全场通用；1->指定分类；2->指定商品',
  `publish_count` int DEFAULT NULL COMMENT '发行数量',
  `use_count` int DEFAULT NULL COMMENT '已使用数量',
  `receive_count` int DEFAULT NULL COMMENT '领取数量',
  `enable_time` datetime DEFAULT NULL COMMENT '可以领取的日期',
  `code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '优惠码',
  `scope_type` int DEFAULT '0' COMMENT '适用范围：0->平台优惠券；1->商家优惠券',
  `member_id` bigint DEFAULT '0' COMMENT '发布用户ID',
  `merchant_id` bigint DEFAULT NULL COMMENT '如果scope_type=1（商家券），此字段记录所属商家ID',
  `status` int DEFAULT NULL COMMENT '状态：0->未开始；1->进行中；2->已结束；3->手动失效',
  `del_flag` tinyint DEFAULT '0' COMMENT '删除标记 0 代表存在  2删除',
  `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '说明',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `publish_minute` varchar(16) COLLATE utf8mb4_general_ci GENERATED ALWAYS AS (date_format(`create_time`,_utf8mb4'%Y%m%d%H%i')) STORED COMMENT 'M0-4 发券分钟(生成列),用于发券防重唯一键',
  `per_member_limit` int DEFAULT NULL COMMENT '每人限领张数(NULL或<=0不限,>0每member最多领N张)',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_coupon_dedup` (`member_id`,`coupon_type`,`publish_minute`)
) ENGINE=InnoDB AUTO_INCREMENT=15 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='优惠券表';
CREATE TABLE `sms_coupon_history` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `coupon_id` bigint NOT NULL COMMENT '优惠券ID',
  `member_id` bigint NOT NULL COMMENT '用户ID',
  `coupon_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '优惠券码（唯一标识）',
  `qrcode_url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '核销二维码URL',
  `get_type` int DEFAULT '0' COMMENT '获取方式：0->后台赠送；1->主动领取',
  `get_time` datetime DEFAULT NULL COMMENT '领取时间',
  `use_status` int DEFAULT '0' COMMENT '使用状态：0->未使用；1->已使用；2->已过期',
  `use_time` datetime DEFAULT NULL COMMENT '使用时间',
  `order_id` bigint DEFAULT NULL COMMENT '订单ID',
  `order_sn` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '订单编号',
  `start_time` datetime DEFAULT NULL COMMENT '有效开始时间',
  `end_time` datetime DEFAULT NULL COMMENT '有效结束时间',
  `note` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  `del_flag` tinyint DEFAULT '0' COMMENT '删除标记 0 代表存在  2删除',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '修改人',
  `update_time` datetime DEFAULT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='优惠券领取表';
CREATE TABLE `cms_topic_completion_coupon_reward_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `topic_id` bigint NOT NULL,
  `member_id` bigint NOT NULL,
  `coupon_id` bigint NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_topic_member` (`topic_id`,`member_id`),
  KEY `idx_member` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='主题通关奖励券发放幂等日志';

-- DRAFT ONLY. NOT EXECUTED, NOT DEPLOYMENT-READY. No application wiring or backfill.
-- Requires approved same-datasource transaction wiring and a committed-source authority
-- which retains current authorization locks through commit. No such production port is supplied.
-- Requires independent MySQL schema/version/engine/privilege review; H2 is not that review.
-- Snapshot writer privilege should be INSERT/SELECT only; no UPDATE/DELETE path is proposed.
CREATE TABLE sms_coupon_terms_snapshot (
    history_id BIGINT NOT NULL,
    coupon_id BIGINT NOT NULL,
    issuer_member_id BIGINT NOT NULL,
    merchant_id BIGINT NOT NULL,
    actor_member_id BIGINT NOT NULL,
    beneficiary_member_id BIGINT NOT NULL,
    nonce VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_receipt_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    intent_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    terms_json TEXT NOT NULL,
    terms_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    recorded_at BIGINT NOT NULL,
    PRIMARY KEY (history_id),
    UNIQUE KEY uk_coupon_terms_issuer_nonce (issuer_member_id, nonce),
    UNIQUE KEY uk_coupon_terms_source_owner_coupon (source_receipt_id, beneficiary_member_id, coupon_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Intentionally no INSERT...SELECT backfill, parent-terms fallback, UPDATE, or activation SQL.

-- DRAFT ONLY. Not executed. Review MySQL/version/schema before any separately approved deployment.
-- Append-only exact grant association. No backfill or inferred matching of old histories.
-- No foreign key to the removable current completion log: approved clawback/recompletion
-- creates a new log ID while each prior grant/history association must remain distinct.
CREATE TABLE cms_topic_completion_coupon_receipt (
    grant_log_id BIGINT NOT NULL PRIMARY KEY,
    topic_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    coupon_id BIGINT NOT NULL,
    history_id BIGINT NOT NULL,
    granted_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_topic_completion_receipt_history (history_id),
    KEY ix_topic_completion_receipt_owner (member_id, grant_log_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- DRAFT ONLY. NEVER EXECUTED. Must be reviewed/deployed together with receipt protocol01ef + this revision.
-- There is no supported stage that deploys01ef first and later calls its unmarked receipts legacy.
-- NULL with no receipt denotes existing legacy; no historical values are backfilled.
ALTER TABLE cms_topic_completion_coupon_reward_log ADD COLUMN receipt_contract_version SMALLINT NULL DEFAULT NULL;

INSERT INTO sms_coupon(id,name,coupon_type,platform,amount,min_point,start_time,end_time,use_type,publish_count,use_count,receive_count,code,scope_type,member_id,merchant_id,status,del_flag,description) VALUES(8,'Original title',1,0,5.00,10.00,'2026-01-01 00:00:00','2036-01-01 00:00:00',0,10,0,0,'ISOLATED-DEFINITION-CODE',1,9,19,1,0,'Original conditions');
