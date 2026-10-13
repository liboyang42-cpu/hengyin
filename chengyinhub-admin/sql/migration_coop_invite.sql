-- 协作邀请表(P1.5 §7.6):统一达人×商家×商家三类邀请 + 自审核握手(发起+同意=通过,不进 web 审核)
-- 执行方式:连生产库 source 本文件(走 chengyin-db-migrate skill,只读预检后确认再执行)
-- 影响:新建表,对存量无影响。
-- ⚠️ 部署顺序:本迁移必须先于新 jar 落库,否则 CoopInviteMapper 引用表会 SQL 报错。

CREATE TABLE IF NOT EXISTS coop_invite (
    id          BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT '主键',
    invite_type TINYINT(1)   NOT NULL COMMENT '邀请类型 0达人↔商家 1商家→俱乐部 2商家→商家做节点',
    from_type   VARCHAR(16)  NOT NULL COMMENT '发起方类型 talent/merchant',
    from_id     BIGINT(20)   NOT NULL COMMENT '发起方ID(memberId)',
    to_type     VARCHAR(16)  NOT NULL COMMENT '受邀方类型 merchant/club',
    to_id       BIGINT(20)   NOT NULL COMMENT '受邀方ID(memberId或clubId)',
    topic_id    BIGINT(20)   NULL COMMENT '关联城市定向主题(类型1/2必填)',
    node_id     BIGINT(20)   NULL COMMENT '类型2同意后写入的节点',
    status      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '状态 0待确认 1已接受 2已拒绝 3已取消',
    message     VARCHAR(500) NULL COMMENT '留言',
    handle_time DATETIME     NULL COMMENT '处理时间',
    create_by   VARCHAR(64)  NULL,
    create_time DATETIME     NULL,
    update_by   VARCHAR(64)  NULL,
    update_time DATETIME     NULL,
    del_flag    INT          NOT NULL DEFAULT 0 COMMENT '0存在 2删除',
    PRIMARY KEY (id),
    KEY idx_coop_from (from_type, from_id, status),
    KEY idx_coop_to (to_type, to_id, status),
    KEY idx_coop_topic (topic_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='协作邀请(达人×商家×商家自审核握手)';
