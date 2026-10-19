CREATE TABLE oms_cart_settlement_receipt (
  id BIGINT NOT NULL AUTO_INCREMENT,
  member_id BIGINT NOT NULL,
  request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_hash CHAR(64) NOT NULL,
  intent_hash CHAR(64) DEFAULT NULL,
  first_order_id BIGINT DEFAULT NULL,
  total_points BIGINT DEFAULT NULL,
  status VARCHAR(16) NOT NULL,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_cart_settlement_member_request (member_id, request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='积分兑换请求收据';

CREATE TABLE oms_cart_settlement_order (
  receipt_id BIGINT NOT NULL,
  order_id BIGINT NOT NULL,
  sort_order INT NOT NULL,
  PRIMARY KEY (receipt_id, order_id),
  UNIQUE KEY uk_cart_settlement_order_sort (receipt_id, sort_order),
  KEY idx_cart_settlement_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='积分兑换子单映射';
