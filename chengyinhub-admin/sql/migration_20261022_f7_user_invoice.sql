-- DOC2 F7 用户发票：抬头 / 申请单 / 申请单-订单占用。只新建三张表，不改既有表、不回填数据。
-- 命名避开 club_membership_invoice（那是会员费支付单，不是发票）与 merchant/club_payable_batch 的结算发票台账。
--
-- 隐私最小化：
--   · 抬头只存「类型 + 名称 + 税号」，不收地址/开户行/邮箱/手机号；
--   · title_name / tax_no 走 PiiCryptoTypeHandler（AES-GCM，v1: 前缀密文），缺 chengyin.pii.key 时写入 fail-closed；
--   · 申请单对抬头做快照（同样是密文），之后改/删抬头不会改写已提交的申请。
-- 一单不得重复开票：user_invoice_application_order.active_key 占用期 = 1，申请被驳回/取消时置 NULL 释放；
--   唯一键 (order_source, order_id, active_key) 只拦「同时有两张占用」，NULL 不参与唯一约束，可再次申请。
-- 幂等：(member_id, request_id) 唯一；重放返回同一张申请单。
-- 无任何税控/电子发票服务对接（InvoiceIssuer 默认 MANUAL 适配器，人工开具）。
--
-- 【前置】无（订单来源 cms_registration / refund_application 均在 base schema）。
-- 【落库顺序】先手工执行本迁移，再部署新 jar；功能开关 feature.flag.userInvoice 缺省关闭。
-- 幂等：表不存在则建；已存在则核对列/索引 fingerprint，不一致时断言失败，不改不删。

CREATE TABLE IF NOT EXISTS user_invoice_title (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  member_id BIGINT NOT NULL COMMENT '归属会员',
  title_type VARCHAR(16) NOT NULL COMMENT 'PERSONAL 个人 / COMPANY 单位',
  title_name VARCHAR(768) NOT NULL COMMENT '抬头名称(AES-GCM密文)',
  tax_no VARCHAR(256) NULL COMMENT '纳税人识别号(AES-GCM密文)，仅 COMPANY',
  is_default TINYINT NOT NULL DEFAULT 0 COMMENT '是否默认抬头',
  del_flag TINYINT NOT NULL DEFAULT 0 COMMENT '0 存在 2 删除',
  create_time DATETIME NOT NULL COMMENT '创建时间',
  update_time DATETIME NULL COMMENT '修改时间',
  PRIMARY KEY (id),
  KEY idx_user_invoice_title_member (member_id, del_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户发票抬头';

CREATE TABLE IF NOT EXISTS user_invoice_application (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  application_no VARCHAR(32) NOT NULL COMMENT '申请单号',
  member_id BIGINT NOT NULL COMMENT '申请人',
  request_id VARCHAR(64) NOT NULL COMMENT '客户端幂等键',
  title_id BIGINT NOT NULL COMMENT '申请时选择的抬头ID',
  title_type VARCHAR(16) NOT NULL COMMENT '抬头类型快照',
  title_name VARCHAR(768) NOT NULL COMMENT '抬头名称快照(AES-GCM密文)',
  tax_no VARCHAR(256) NULL COMMENT '税号快照(AES-GCM密文)',
  total_amount DECIMAL(10,2) NOT NULL COMMENT '开票金额合计(订单微信实付合计)',
  order_count INT NOT NULL COMMENT '合并订单数',
  status VARCHAR(16) NOT NULL DEFAULT 'SUBMITTED' COMMENT 'SUBMITTED/ISSUED/REJECTED/CANCELLED',
  provider VARCHAR(32) NOT NULL DEFAULT 'MANUAL' COMMENT '开票适配器',
  invoice_no VARCHAR(64) NULL COMMENT '发票号码(人工回填)',
  reject_reason VARCHAR(200) NULL COMMENT '驳回原因',
  issued_at DATETIME NULL COMMENT '开具时间',
  create_time DATETIME NOT NULL COMMENT '创建时间',
  update_time DATETIME NULL COMMENT '修改时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_invoice_application_no (application_no),
  UNIQUE KEY uk_user_invoice_member_request (member_id, request_id),
  KEY idx_user_invoice_application_member (member_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户发票申请单（可合并多笔订单）';

CREATE TABLE IF NOT EXISTS user_invoice_application_order (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  application_id BIGINT NOT NULL COMMENT '申请单ID',
  member_id BIGINT NOT NULL COMMENT '订单归属会员(冗余，查询带 owner)',
  order_source VARCHAR(32) NOT NULL COMMENT '订单来源：registration',
  order_id BIGINT NOT NULL COMMENT '订单ID',
  amount DECIMAL(10,2) NOT NULL COMMENT '该订单计入开票的金额',
  active_key TINYINT NULL DEFAULT 1 COMMENT '占用标记：1 占用中；NULL 已释放(驳回/取消)',
  create_time DATETIME NOT NULL COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_invoice_order_active (order_source, order_id, active_key),
  KEY idx_user_invoice_order_application (application_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='发票申请单占用的订单（一单同时只能被一张申请占用）';

-- 同名但形状不对的旧表不得被 IF NOT EXISTS 静默接受：列 + 关键唯一键 fingerprint。
-- runner: assert=scalar-true
SELECT
  (SELECT GROUP_CONCAT(CONCAT_WS(':', column_name, column_type, is_nullable) ORDER BY ordinal_position SEPARATOR '|')
     FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='user_invoice_title')
  = CONCAT('id:bigint:NO|member_id:bigint:NO|title_type:varchar(16):NO|title_name:varchar(768):NO',
           '|tax_no:varchar(256):YES|is_default:tinyint:NO|del_flag:tinyint:NO|create_time:datetime:NO',
           '|update_time:datetime:YES')
  AND
  (SELECT GROUP_CONCAT(CONCAT_WS(':', column_name, column_type, is_nullable) ORDER BY ordinal_position SEPARATOR '|')
     FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='user_invoice_application')
  = CONCAT('id:bigint:NO|application_no:varchar(32):NO|member_id:bigint:NO|request_id:varchar(64):NO',
           '|title_id:bigint:NO|title_type:varchar(16):NO|title_name:varchar(768):NO|tax_no:varchar(256):YES',
           '|total_amount:decimal(10,2):NO|order_count:int:NO|status:varchar(16):NO|provider:varchar(32):NO',
           '|invoice_no:varchar(64):YES|reject_reason:varchar(200):YES|issued_at:datetime:YES',
           '|create_time:datetime:NO|update_time:datetime:YES')
  AND
  (SELECT GROUP_CONCAT(CONCAT_WS(':', column_name, column_type, is_nullable) ORDER BY ordinal_position SEPARATOR '|')
     FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='user_invoice_application_order')
  = CONCAT('id:bigint:NO|application_id:bigint:NO|member_id:bigint:NO|order_source:varchar(32):NO',
           '|order_id:bigint:NO|amount:decimal(10,2):NO|active_key:tinyint:YES|create_time:datetime:NO')
  AND
  (SELECT GROUP_CONCAT(CONCAT_WS(':', index_name, non_unique, seq_in_index, column_name)
            ORDER BY index_name, seq_in_index SEPARATOR '|')
     FROM information_schema.statistics WHERE table_schema=DATABASE()
      AND table_name='user_invoice_application_order' AND index_name='uk_user_invoice_order_active')
  = 'uk_user_invoice_order_active:0:1:order_source|uk_user_invoice_order_active:0:2:order_id|uk_user_invoice_order_active:0:3:active_key'
  AND
  (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
      AND table_name='user_invoice_application' AND index_name='uk_user_invoice_member_request' AND non_unique=0) = 2
  AS assertion_ok;
