-- Inactive internal MEMBER_TEMPLATE text-subset installation, NOT a complete gameplay-package install.
-- Requires migration_w18_module_install_reservation_20261002.sql. No legacy tables are changed.
-- All content/rights are private server records. No endpoint, grant issuer, editor or publish wiring.
CREATE TABLE IF NOT EXISTS w18_module_owned_draft (
    id BIGINT NOT NULL AUTO_INCREMENT,
    reservation_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    buyer_kind VARCHAR(16) NOT NULL,
    buyer_id BIGINT NOT NULL,
    target_draft_id BIGINT NOT NULL,
    target_revision BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    review_status INT NOT NULL,
    publish_status INT NOT NULL,
    is_sync INT NOT NULL,
    source_content MEDIUMBLOB NOT NULL,
    terms_document MEDIUMBLOB NOT NULL,
    frozen_rights MEDIUMTEXT NOT NULL,
    installed_by BIGINT NOT NULL,
    installed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_w18_owned_draft_reservation (reservation_key),
    KEY idx_w18_owned_draft_buyer (buyer_kind, buyer_id, target_draft_id),
    CONSTRAINT fk_w18_owned_draft_reservation FOREIGN KEY (reservation_key)
        REFERENCES w18_module_install_reservation (reservation_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- Local component keys are scoped by owned draft. Future reviewed codecs can add component kinds;
-- source numeric IDs, seller collaborators, merchant grants and arbitrary JSON are never copied.
CREATE TABLE IF NOT EXISTS w18_module_draft_component (
    draft_id BIGINT NOT NULL,
    local_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    component_kind VARCHAR(32) NOT NULL,
    schema_version INT NOT NULL,
    content_json MEDIUMTEXT NOT NULL,
    PRIMARY KEY (draft_id, local_key),
    CONSTRAINT fk_w18_component_draft FOREIGN KEY (draft_id) REFERENCES w18_module_owned_draft (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- One receipt is one committed install usage. Run usage/admission is deliberately absent.
-- Scope and approved binding evidence remain in the original reservation tables permanently.
CREATE TABLE IF NOT EXISTS w18_module_install_usage (
    reservation_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owned_draft_id BIGINT NOT NULL,
    committed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (reservation_key),
    UNIQUE KEY uk_w18_usage_draft (owned_draft_id),
    CONSTRAINT fk_w18_usage_reservation FOREIGN KEY (reservation_key)
        REFERENCES w18_module_install_reservation (reservation_key),
    CONSTRAINT fk_w18_usage_draft FOREIGN KEY (owned_draft_id) REFERENCES w18_module_owned_draft (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
