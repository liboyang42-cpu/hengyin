-- 站内消息(IM) 迁移
-- 执行：MySQL 连库后 source 本文件
-- 设计见 Obsidian: 站内消息功能_设计方案

-- 1. 会话
CREATE TABLE IF NOT EXISTS im_conversation (
    id            BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT '主键',
    type          TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '类型 1单聊 2系统 3商家',
    biz_key       VARCHAR(64)  NOT NULL COMMENT '业务唯一键(单聊=小id_大id、系统=sys_会员id)',
    last_msg_type TINYINT(1)   NULL COMMENT '最后一条消息类型',
    last_msg_text VARCHAR(500) NULL COMMENT '最后一条消息摘要',
    last_msg_at   DATETIME     NULL COMMENT '最后一条消息时间',
    status        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '状态 0正常 1封禁',
    create_by     VARCHAR(64)  NULL,
    create_time   DATETIME     NULL,
    update_by     VARCHAR(64)  NULL,
    update_time   DATETIME     NULL,
    remark        VARCHAR(255) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_type_bizkey (type, biz_key),
    KEY idx_last_msg_at (last_msg_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IM会话';

-- 2. 会话参与者
CREATE TABLE IF NOT EXISTS im_conversation_member (
    id               BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
    conversation_id  BIGINT(20) NOT NULL COMMENT '会话id',
    member_id        BIGINT(20) NOT NULL COMMENT '会员id',
    role             TINYINT(1) NOT NULL DEFAULT 0 COMMENT '角色 0普通 1商家 2系统',
    unread_count     INT        NOT NULL DEFAULT 0 COMMENT '未读数',
    last_read_msg_id BIGINT(20) NOT NULL DEFAULT 0 COMMENT '已读到的消息id',
    muted            TINYINT(1) NOT NULL DEFAULT 0 COMMENT '免打扰',
    deleted          TINYINT(1) NOT NULL DEFAULT 0 COMMENT '0正常 1删除会话',
    create_time      DATETIME   NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_conv_member (conversation_id, member_id),
    KEY idx_member (member_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IM会话参与者';

-- 3. 消息
CREATE TABLE IF NOT EXISTS im_message (
    id              BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT '主键',
    conversation_id BIGINT(20)   NOT NULL COMMENT '会话id',
    sender_id       BIGINT(20)   NOT NULL DEFAULT 0 COMMENT '发送者会员id(0=系统)',
    receiver_id     BIGINT(20)   NOT NULL DEFAULT 0 COMMENT '接收者会员id',
    msg_type        TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1文本 2图片 3系统卡片',
    content         VARCHAR(2000) NULL COMMENT '内容(文本/图片url/卡片json备注)',
    extra_json      VARCHAR(1000) NULL COMMENT '卡片扩展(跳转/活动id等)',
    status          TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '0正常 1撤回 2违规屏蔽',
    check_status    TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '内容安全 0待检 1通过 2违规',
    trace_id        VARCHAR(64)  NULL COMMENT '图片异步检测trace_id',
    create_time     DATETIME     NULL,
    PRIMARY KEY (id),
    KEY idx_conv (conversation_id, id),
    KEY idx_receiver (receiver_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IM消息';

-- 4. 拉黑
CREATE TABLE IF NOT EXISTS im_block (
    id                 BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
    member_id          BIGINT(20) NOT NULL COMMENT '拉黑发起人',
    blocked_member_id  BIGINT(20) NOT NULL COMMENT '被拉黑人',
    create_time        DATETIME   NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_block (member_id, blocked_member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IM拉黑';
