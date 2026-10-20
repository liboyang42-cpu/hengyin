-- W18 internal reservation ledger only. NO runtime enablement or grant/payment creation.
-- Apply only through separately authorized migration review. Never run against a live DB here.
-- New tables only; idempotent CREATE does not repair drift: inspect existing schema before applying.
-- Hash keys are domain-separated exact UTF-8 digests, avoiding text-collation/length-index ambiguity.
-- Scope rows and terminal reservations retain audit evidence; no deletion/cascade or run mutation.
CREATE TABLE IF NOT EXISTS w18_module_install_mutex (
    lock_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS w18_module_install_reservation (
    reservation_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    buyer_kind VARCHAR(16) NOT NULL,
    buyer_id BIGINT NOT NULL,
    request_id TEXT NOT NULL,
    license_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    license_id TEXT NOT NULL,
    payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    authority_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    module_id TEXT NOT NULL,
    version_id TEXT NOT NULL,
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    offer_version TEXT NOT NULL,
    terms_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    seller_kind VARCHAR(16) NOT NULL,
    seller_id BIGINT NOT NULL,
    source_event_id TEXT NOT NULL,
    grant_evidence_reference TEXT NOT NULL,
    approval_reference TEXT NOT NULL,
    draft_id BIGINT NOT NULL,
    draft_revision BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    reserved_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    terminal_at DATETIME(6) NULL,
    PRIMARY KEY (reservation_key),
    KEY idx_w18_reservation_license_state (license_key, state, expires_at),
    KEY idx_w18_reservation_buyer (buyer_kind, buyer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS w18_module_install_scope (
    reservation_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope_kind VARCHAR(16) NOT NULL,
    scope_id BIGINT NOT NULL,
    PRIMARY KEY (reservation_key, scope_kind, scope_id),
    CONSTRAINT fk_w18_scope_reservation FOREIGN KEY (reservation_key)
        REFERENCES w18_module_install_reservation (reservation_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS w18_module_install_binding (
    reservation_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    placeholder_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    placeholder_name TEXT NOT NULL,
    resource_kind VARCHAR(16) NOT NULL,
    resource_id BIGINT NOT NULL,
    merchant_id BIGINT NULL,
    authority_reference TEXT NOT NULL,
    region TEXT NOT NULL,
    valid_until DATETIME(6) NOT NULL,
    PRIMARY KEY (reservation_key, placeholder_key),
    CONSTRAINT fk_w18_binding_reservation FOREIGN KEY (reservation_key)
        REFERENCES w18_module_install_reservation (reservation_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
