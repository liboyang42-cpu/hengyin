-- Additive paid edition boundary; legacy free templates and licenses are unchanged.
ALTER TABLE workshop_version
  ADD COLUMN id bigint NOT NULL AUTO_INCREMENT UNIQUE,
  ADD COLUMN source_kind varchar(24) CHARACTER SET ascii NOT NULL DEFAULT 'LEGACY_PUBLIC',
  ADD COLUMN review_state varchar(24) CHARACTER SET ascii NOT NULL DEFAULT 'LEGACY_UNREVIEWED',
  ADD COLUMN audit_task_id bigint NULL,
  ADD COLUMN review_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD COLUMN review_snapshot longtext NULL,
  ADD COLUMN approved_at datetime(3) NULL,
  ADD UNIQUE KEY uk_workshop_version_audit(audit_task_id),
  ADD KEY idx_workshop_source(source_canonical_id,source_kind),
  ADD KEY idx_workshop_shelf(review_state,id);

ALTER TABLE workshop_order
  MODIFY COLUMN amount_fen bigint NULL,
  ADD COLUMN version_review_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD COLUMN currency char(3) CHARACTER SET ascii NOT NULL DEFAULT 'CNY',
  ADD COLUMN amount_milliunits bigint NULL,
  ADD COLUMN app_account_token char(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD COLUMN apple_bundle_id varchar(255) CHARACTER SET ascii NULL,
  ADD COLUMN apple_environment varchar(20) CHARACTER SET ascii NULL,
  ADD COLUMN apple_product_id varchar(255) CHARACTER SET ascii NULL,
  ADD COLUMN apple_transaction_id varchar(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD COLUMN apple_original_transaction_id varchar(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD COLUMN apple_fact_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD COLUMN apple_revocation_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD UNIQUE KEY uk_workshop_apple_account(app_account_token),
  ADD UNIQUE KEY uk_workshop_apple_transaction(apple_transaction_id),
  ADD UNIQUE KEY uk_workshop_apple_original(apple_original_transaction_id),
  ADD CONSTRAINT ck_workshop_channel_money CHECK (
    (provider='WECHAT_APP' AND amount_fen IS NOT NULL AND currency='CNY') OR
    (provider='APPLE_IAP' AND amount_fen IS NULL AND amount_milliunits>0 AND amount_milliunits IS NOT NULL
     AND app_account_token IS NOT NULL AND apple_bundle_id IS NOT NULL
     AND apple_environment IS NOT NULL AND apple_product_id IS NOT NULL));

-- Controlled server mapping only. No default SKUs, prices, exchange rates or enabled rows.
-- Configure a new frozen edition before its author submits it for review.
CREATE TABLE IF NOT EXISTS workshop_apple_product (
  version_id varchar(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  bundle_id varchar(255) CHARACTER SET ascii NOT NULL,
  environment varchar(20) CHARACTER SET ascii NOT NULL,
  product_id varchar(255) CHARACTER SET ascii NOT NULL,
  price_milliunits bigint NOT NULL,
  currency char(3) CHARACTER SET ascii NOT NULL,
  enabled tinyint NOT NULL DEFAULT 0,
  PRIMARY KEY(version_id,bundle_id,environment,product_id),
  UNIQUE KEY uk_workshop_apple_product(bundle_id,environment,product_id),
  CONSTRAINT fk_workshop_apple_version FOREIGN KEY(version_id) REFERENCES workshop_version(version_id),
  CONSTRAINT ck_workshop_apple_price CHECK(price_milliunits>0),
  CONSTRAINT ck_workshop_apple_environment CHECK(environment IN ('Sandbox','Production'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- A forced same-owner NPC/template copy retains its original paid license and consumes its copy limit.
CREATE TABLE IF NOT EXISTS workshop_draft_lineage (
  draft_id bigint NOT NULL,
  buyer_id bigint NOT NULL,
  order_no varchar(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  command_json text NOT NULL,
  parent_draft_id bigint NOT NULL,
  created_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY(draft_id), KEY idx_workshop_lineage_order(order_no),
  CONSTRAINT fk_workshop_lineage_order FOREIGN KEY(order_no) REFERENCES workshop_order(order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
