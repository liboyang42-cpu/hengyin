-- P0-4 开放范围·俱乐部承接:cms_topic 开放开关 + 俱乐部承接申请表
-- 执行方式:走 chengyin-db-migrate skill(只读预检确认后执行)。生产 MySQL 8,ADD COLUMN 不用 IF NOT EXISTS(1064),
--          执行前先预检列是否已存在:SELECT COUNT(*) FROM information_schema.columns
--            WHERE table_schema=DATABASE() AND table_name='cms_topic' AND column_name='open_club_pool';
-- 影响:cms_topic 加一列(默认 0,存量主题全部「不开放」,行为不变);新建 coop_club_apply 表,对存量无影响。
-- ⚠️ 部署顺序:本迁移必须先于新 jar 落库,否则 CmsTopicMapper/CoopClubApplyMapper 引用列/表会 SQL 报错。

ALTER TABLE cms_topic
    ADD COLUMN open_club_pool TINYINT(1) NOT NULL DEFAULT 0 COMMENT '开放范围·俱乐部承接(P0-4):0不开放 1开放给俱乐部申请承接' AFTER merchant_status;

CREATE TABLE IF NOT EXISTS coop_club_apply (
    id          BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT '主键',
    topic_id    BIGINT(20)   NOT NULL COMMENT '主题ID',
    club_id     BIGINT(20)   NOT NULL COMMENT '申请俱乐部ID',
    member_id   BIGINT(20)   NOT NULL COMMENT '申请时的俱乐部主理人memberId',
    status      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '状态 0待处理 1已婉拒 2已撤回;已邀约/已合作按 coop_invite 派生不落本表',
    message     VARCHAR(500) NULL COMMENT '申请留言',
    handle_time DATETIME     NULL COMMENT '处理时间(婉拒/撤回)',
    create_by   VARCHAR(64)  NULL,
    create_time DATETIME     NULL,
    update_by   VARCHAR(64)  NULL,
    update_time DATETIME     NULL,
    del_flag    INT          NOT NULL DEFAULT 0 COMMENT '0存在 2删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_club_apply_topic_club (topic_id, club_id),
    KEY idx_club_apply_topic_status (topic_id, status),
    KEY idx_club_apply_club (club_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='俱乐部承接申请(P0-4 开放范围·意向记录,不生成合作单)';
