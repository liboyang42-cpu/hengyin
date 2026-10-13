-- P1 能力模型地基:member_capability 多能力表(替代单值 ums_member.role 作能力真源)
-- 走 chengyin-db-migrate skill:只读预检后确认再执行。
-- ⚠️ 回填铁律:严禁用 ums_member.role(覆盖式单值+ApiUmsMemberController:499禁用不回写→有僵尸merchant)。
--    必须用 UNION(club_leader status=1, mms_merchant status=1) 回填。
--    player 能力隐式默认,不落表。
-- role 列保留(双写过渡,作"默认视角指针"),本迁移不动 ums_member。
-- 阻断型:jar 的 CapabilityService 引用本表,须先于 jar 部署。

CREATE TABLE IF NOT EXISTS member_capability (
  id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  member_id    BIGINT       NOT NULL COMMENT '会员ID',
  capability   VARCHAR(32)  NOT NULL COMMENT '能力: club_leader / merchant (player 隐式不落表)',
  status       TINYINT      NOT NULL DEFAULT 1 COMMENT '1=有效 0=已撤销',
  granted_at   DATETIME     NULL COMMENT '授予时间',
  granted_by   BIGINT       NULL COMMENT '授予人(0=系统自动;后台审核=adminId)',
  create_time  DATETIME     NULL,
  update_time  DATETIME     NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_member_capability (member_id, capability),
  KEY idx_member_capability_member (member_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P1 会员能力索引(多能力,替代单值role)';

-- 回填:club_leader status=1 → club_leader 能力
INSERT INTO member_capability (member_id, capability, status, granted_at, granted_by, create_time)
SELECT member_id, 'club_leader', 1, NOW(), 0, NOW()
FROM club_leader
WHERE status = 1
  AND NOT EXISTS (SELECT 1 FROM member_capability mc WHERE mc.member_id = club_leader.member_id AND mc.capability = 'club_leader');

-- 回填:mms_merchant status=1(审核通过) → merchant 能力
INSERT INTO member_capability (member_id, capability, status, granted_at, granted_by, create_time)
SELECT member_id, 'merchant', 1, NOW(), 0, NOW()
FROM mms_merchant
WHERE status = 1
  AND NOT EXISTS (SELECT 1 FROM member_capability mc WHERE mc.member_id = mms_merchant.member_id AND mc.capability = 'merchant');
