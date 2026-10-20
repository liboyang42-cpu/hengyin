-- FORWARD SOURCE PROPOSAL ONLY. Not applied to production. Requires schema/authority review.
-- Draft snapshots are never CityBoard runtime rows or approved rules. No balance or permission DML.
CREATE TABLE city_board_draft (
  draft_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  version BIGINT NOT NULL,
  snapshot_json MEDIUMTEXT NOT NULL,
  PRIMARY KEY (draft_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE city_board_draft_receipt (
  actor_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  snapshot_json MEDIUMTEXT NOT NULL,
  PRIMARY KEY (actor_id, request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
