-- NEW, unapplied design. No prices, checkout, provider configuration, scheduling or default bean.
-- Freeze a reviewed order-line snapshot BEFORE payment. Do not seed existing subscription orders.
-- Reviewed ingress and refund/revoke writers must take the oms_order row lock before this sidecar.
-- No IF NOT EXISTS: a pre-existing incompatible table must fail rather than silently weaken constraints.
CREATE TABLE w18_paid_order_line_snapshot (
 order_line_id BIGINT NOT NULL PRIMARY KEY,
 order_id BIGINT NOT NULL,
 buyer_member_id BIGINT NOT NULL,
 product_id BIGINT NOT NULL,
 sku_id BIGINT NULL,
 amount_minor BIGINT NOT NULL,
 currency VARCHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 provider_app_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 provider_merchant_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 snapshot_json MEDIUMBLOB NOT NULL,
 snapshot_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_content MEDIUMBLOB NOT NULL,
 terms_document BLOB NOT NULL,
 state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 frozen_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_w18_paid_order(order_id),
 UNIQUE KEY uk_w18_paid_line_order_buyer(order_line_id,order_id,buyer_member_id),
 CONSTRAINT fk_w18_paid_snapshot_line FOREIGN KEY(order_line_id) REFERENCES oms_order_item(id),
 CONSTRAINT fk_w18_paid_snapshot_order FOREIGN KEY(order_id) REFERENCES oms_order(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE w18_paid_license (
 license_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 order_line_id BIGINT NOT NULL,
 order_id BIGINT NOT NULL,
 buyer_member_id BIGINT NOT NULL,
 provider_transaction_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_event_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 snapshot_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 paid_at DATETIME(6) NOT NULL,
 issued_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_w18_paid_license_line(order_line_id),
 UNIQUE KEY uk_w18_paid_license_transaction(provider_transaction_id),
 UNIQUE KEY uk_w18_paid_license_event(source_event_id),
 CONSTRAINT fk_w18_paid_license_snapshot FOREIGN KEY(order_line_id,order_id,buyer_member_id)
   REFERENCES w18_paid_order_line_snapshot(order_line_id,order_id,buyer_member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
