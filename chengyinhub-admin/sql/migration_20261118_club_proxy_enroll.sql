-- 俱乐部代报名邀请（App Core/ClubGapClient `POST /api/club/events/proxy-enroll` {clubId, activityId, memberId}）。
-- 管理者（club:activity:manage）替本俱乐部成员占一个「待本人完成报名」的位置：只记邀请，不建报名单、不动钱、不收 PII。
-- 报名、单独同意与付款仍由成员本人走 /api/registration/*；读回时按 cms_registration 现查 REGISTERED。
-- 同一场次同一成员只有一行（重复提交读回原行）。只新增表；不回填、不改既有行。可重复执行。
CREATE TABLE IF NOT EXISTS club_proxy_enroll (
    id BIGINT NOT NULL AUTO_INCREMENT,
    club_id BIGINT NOT NULL,
    activity_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL COMMENT '被代报名的成员',
    proposer_member_id BIGINT NOT NULL COMMENT '发起代报名的管理者',
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_club_proxy_enroll (activity_id, member_id),
    KEY idx_club_proxy_enroll_member (member_id, id),
    KEY idx_club_proxy_enroll_club (club_id, activity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='俱乐部代报名邀请（成员本人完成报名）';
