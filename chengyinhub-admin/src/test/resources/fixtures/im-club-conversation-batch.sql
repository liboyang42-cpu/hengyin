-- Scratch schema only. Projection columns needed by the real ClubMapper/ClubMemberMapper.
-- Not a production migration or a club-schema compatibility assertion.
CREATE TABLE club (
 id BIGINT PRIMARY KEY, member_id BIGINT, name VARCHAR(64), logo VARCHAR(255),
 cover VARCHAR(255), description TEXT, club_type VARCHAR(64), club_types VARCHAR(255),
 activity_prefs VARCHAR(255), experience TEXT, join_reason TEXT, case_images TEXT,
 city VARCHAR(64), keywords VARCHAR(255), style VARCHAR(255), max_event_size INT,
 avg_event_size INT, can_design_route INT, can_design_task INT, can_npc INT,
 can_merchant_coop INT, has_guide_cert INT, cert_images TEXT, category_id BIGINT,
 address VARCHAR(255), leader_name VARCHAR(64), phone VARCHAR(64), status BIGINT,
 level INT, tags VARCHAR(255), reject_reason TEXT, audit_by VARCHAR(64), audit_time DATETIME,
 member_count INT, priority_signup_enabled INT, member_discount_price DECIMAL(10,2),
 member_reserved_quota INT, public_visible INT, member_post_allowed INT, merchant_undertake_open INT,
 create_by VARCHAR(64), create_time DATETIME, update_by VARCHAR(64), update_time DATETIME,
 remark VARCHAR(255), del_flag INT
);
CREATE TABLE club_member (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, club_id BIGINT NOT NULL, member_id BIGINT NOT NULL,
 role TINYINT NOT NULL DEFAULT 0, status TINYINT NOT NULL DEFAULT 1,
 join_time DATETIME, del_flag INT NOT NULL DEFAULT 0,
 UNIQUE KEY uk_club_member_uniq(club_id,member_id),
 KEY idx_club(club_id), KEY idx_member(member_id)
);
