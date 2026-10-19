-- Dormant navigation source evidence only. No backfill, provider guess, enable flag or live write.
-- Strict CREATE is intentional: an existing table (including drift) fails closed. The migration
-- ledger owns repeat detection. Never change this into CREATE IF NOT EXISTS without exact guards.
-- No FK/dependency on legacy POI tables: target identity/coordinates are bound by a fresh joined read.
CREATE TABLE roam_poi_navigation_provenance (
    poi_id BIGINT NOT NULL,
    provenance_revision BIGINT NOT NULL DEFAULT 1,
    review_state VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'UNREVIEWED',
    latitude DECIMAL(10,7) NULL DEFAULT NULL,
    longitude DECIMAL(10,7) NULL DEFAULT NULL,
    coordinate_datum VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
    country_region CHAR(2) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
    source_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
    source_reference VARCHAR(192) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
    source_revision VARCHAR(192) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
    review_reference VARCHAR(192) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
    reviewed_at BIGINT NULL DEFAULT NULL,
    PRIMARY KEY (poi_id),
    CONSTRAINT chk_walk_provenance_identity CHECK (poi_id > 0 AND provenance_revision > 0),
    CONSTRAINT chk_walk_provenance_state CHECK (review_state IN (_ascii'UNREVIEWED',_ascii'REVIEWED',_ascii'REVOKED')),
    CONSTRAINT chk_walk_provenance_review CHECK (review_state != _ascii'REVIEWED' OR (
        latitude IS NOT NULL AND longitude IS NOT NULL AND latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180
        AND coordinate_datum IS NOT NULL AND coordinate_datum IN (_ascii'WGS84',_ascii'GCJ02')
        AND country_region IS NOT NULL AND CHAR_LENGTH(country_region) = 2
        AND ASCII(SUBSTRING(country_region,1,1)) BETWEEN 65 AND 90 AND ASCII(SUBSTRING(country_region,2,1)) BETWEEN 65 AND 90
        AND source_kind IS NOT NULL AND source_kind IN (_ascii'PROVIDER_DATASET',_ascii'SURVEY_RECORD')
        AND source_reference IS NOT NULL AND CHAR_LENGTH(source_reference) > 0
        AND source_revision IS NOT NULL AND CHAR_LENGTH(source_revision) > 0
        AND review_reference IS NOT NULL AND CHAR_LENGTH(review_reference) > 0
        AND reviewed_at IS NOT NULL AND reviewed_at > 0
    ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Reviewed exact public-POI coordinate source evidence, inactive';
