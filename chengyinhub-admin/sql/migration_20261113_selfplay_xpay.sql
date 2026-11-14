-- 2026-11-13 自玩票走小程序虚拟支付（XPAY）：只新增表，不改 cms_registration 及任何既有表。
-- 一行 = 一张自玩报名单首次签发虚拟支付参数时冻结的商品 / 环境 / 金额；旧 JSAPI 报名单没有这一行，回调与退款照旧。
-- 全部功能由 chengyin.selfplay.xpay.enabled 控制，默认关闭；建表本身不改变任何现有业务结果。
-- 生产执行必须走 chengyin-db-migrate；可重复执行（IF NOT EXISTS）。

CREATE TABLE IF NOT EXISTS cms_registration_xpay (
  id bigint NOT NULL AUTO_INCREMENT,
  registration_id bigint NOT NULL,
  registration_no varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  member_id bigint NOT NULL,
  product_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '首次签发时冻结的虚拟支付道具 ID',
  offer_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '首次签发时冻结的 OfferID（非密钥）',
  xpay_env tinyint NOT NULL COMMENT '首次签发时冻结：0 正式 / 1 沙箱',
  price_fen bigint NOT NULL COMMENT '首次签发时冻结的应付分',
  state tinyint NOT NULL DEFAULT 0 COMMENT '0 已签发待付 / 1 已付并开通 / 2 退款中 / 3 已退款 / 4 已付但订单已关闭（待自动退款） / 5 确认未付已放行关单',
  wx_order_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'XPAY wx_order_id',
  paid_time datetime(3) NULL,
  delivery_confirmed tinyint NOT NULL DEFAULT 0 COMMENT '发货确认：0 待补报 / 1 已确认（推送或补报成功）',
  refund_no varchar(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
  refunded_time datetime(3) NULL,
  create_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_cms_registration_xpay_reg (registration_id),
  UNIQUE KEY uk_cms_registration_xpay_no (registration_no),
  UNIQUE KEY uk_cms_registration_xpay_refund (refund_no),
  KEY idx_cms_registration_xpay_state (state, delivery_confirmed, update_time),
  CONSTRAINT ck_cms_registration_xpay_state CHECK (state IN (0, 1, 2, 3, 4, 5)),
  CONSTRAINT ck_cms_registration_xpay_price CHECK (price_fen > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='自玩票虚拟支付冻结与状态';
