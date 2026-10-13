ALTER TABLE club
    ADD COLUMN level      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '俱乐部定级 0未定级 1-5' AFTER status,
    ADD COLUMN tags       VARCHAR(255) NULL COMMENT '运营标签(逗号分隔)' AFTER level,
    ADD COLUMN audit_by   VARCHAR(64)  NULL COMMENT '定级审核人' AFTER reject_reason,
    ADD COLUMN audit_time DATETIME     NULL COMMENT '定级审核时间' AFTER audit_by;
ALTER TABLE club
    ADD COLUMN city              VARCHAR(64)   NULL COMMENT '所在城市' AFTER address,
    ADD COLUMN keywords          VARCHAR(255)  NULL COMMENT '核心关键词(逗号分隔,≤3)' AFTER city,
    ADD COLUMN style             VARCHAR(64)   NULL COMMENT '风格/调性' AFTER keywords,
    ADD COLUMN max_event_size    INT           NULL COMMENT '可承接最大活动人数' AFTER style,
    ADD COLUMN avg_event_size    INT           NULL COMMENT '常规活动人数' AFTER max_event_size,
    ADD COLUMN can_design_route  TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '能否设计路线 0否1是' AFTER avg_event_size,
    ADD COLUMN can_design_task   TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '能否设计任务 0否1是' AFTER can_design_route,
    ADD COLUMN can_npc           TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '能否提供NPC 0否1是' AFTER can_design_task,
    ADD COLUMN can_merchant_coop TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '能否商家合作 0否1是' AFTER can_npc,
    ADD COLUMN has_guide_cert    TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '是否有导游/旅游资质 0否1是' AFTER can_merchant_coop,
    ADD COLUMN cert_images       TEXT          NULL COMMENT '资质证书图(逗号分隔URL)' AFTER has_guide_cert;
ALTER TABLE club
    ADD COLUMN club_type VARCHAR(32) NULL COMMENT '俱乐部类型大类(单选):校园社团/旅行组织/兴趣社群/商业活动组织方/内容创作团队/其他' AFTER description;
ALTER TABLE club
    MODIFY COLUMN club_types VARCHAR(500) NULL COMMENT '活动方向(逗号分隔,原"俱乐部类型多选"降级)';
UPDATE club
   SET club_type = SUBSTRING_INDEX(club_types, ',', 1)
 WHERE club_type IS NULL AND club_types IS NOT NULL AND club_types <> '';
ALTER TABLE club
    DROP INDEX uk_club_member,
    ADD INDEX idx_club_member (member_id);
CREATE TABLE IF NOT EXISTS club_leader (
    id               BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT '主键',
    member_id        BIGINT(20)   NOT NULL COMMENT '主理人会员ID(唯一,一人一档)',
    leader_name      VARCHAR(64)  NULL COMMENT '真实/对外昵称',
    phone            VARCHAR(20)  NULL COMMENT '联系电话(内部审核用)',
    identity         VARCHAR(255) NULL COMMENT '身份说明',
    co_founders      VARCHAR(255) NULL COMMENT '联合创始人',
    has_experience   TINYINT(1)   NULL COMMENT '是否有过往组织经验 0否1是',
    experience       VARCHAR(64)  NULL COMMENT '过往活动经验(场次档)',
    max_event_size   INT          NULL COMMENT '最大活动人数',
    avg_event_size   INT          NULL COMMENT '平均活动人数',
    can_design_route TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '能否设计路线 0否1是',
    can_design_task  TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '能否设计任务 0否1是',
    can_npc          TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '能否带NPC/角色 0否1是',
    can_merchant_coop TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '能否与商家联动 0否1是',
    has_guide_cert   TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '有无导游证/旅游资质 0否1是',
    cert_images      TEXT         NULL COMMENT '资质/案例图(逗号分隔URL)',
    level            TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '等级 0未定级 1-5=L1-L5',
    tags             VARCHAR(255) NULL COMMENT '标签(逗号分隔,系统初判+后台可改)',
    audit_by         VARCHAR(64)  NULL COMMENT '定级操作人',
    audit_time       DATETIME     NULL COMMENT '定级时间',
    status           TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1正常',
    create_by        VARCHAR(64)  NULL,
    create_time      DATETIME     NULL,
    update_by        VARCHAR(64)  NULL,
    update_time      DATETIME     NULL,
    remark           VARCHAR(500) NULL,
    del_flag         INT          NOT NULL DEFAULT 0 COMMENT '0存在 2删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_club_leader_member (member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='俱乐部主理人(达人)档案与定级';
INSERT INTO club_leader
    (member_id, leader_name, phone, experience, max_event_size, avg_event_size,
     can_design_route, can_design_task, can_npc, can_merchant_coop, has_guide_cert, cert_images,
     level, tags, audit_by, audit_time, status, create_by, create_time)
SELECT t.member_id, t.leader_name, t.phone, t.experience, t.max_event_size, t.avg_event_size,
       t.can_design_route, t.can_design_task, t.can_npc, t.can_merchant_coop, t.has_guide_cert, t.cert_images,
       t.level, t.tags, t.audit_by, t.audit_time, 1, 'migration', NOW()
FROM (
    SELECT member_id,
           MAX(leader_name) leader_name, MAX(phone) phone, MAX(experience) experience,
           MAX(max_event_size) max_event_size, MAX(avg_event_size) avg_event_size,
           MAX(can_design_route) can_design_route, MAX(can_design_task) can_design_task,
           MAX(can_npc) can_npc, MAX(can_merchant_coop) can_merchant_coop,
           MAX(has_guide_cert) has_guide_cert, MAX(cert_images) cert_images,
           MAX(level) level, MAX(tags) tags, MAX(audit_by) audit_by, MAX(audit_time) audit_time
    FROM club
    WHERE del_flag = 0
    GROUP BY member_id
) t
WHERE NOT EXISTS (SELECT 1 FROM club_leader l WHERE l.member_id = t.member_id);
ALTER TABLE oms_ticket
    ADD COLUMN min_people      INT           NULL COMMENT '最低成团人数(达到则成团)' AFTER team_size,
    ADD COLUMN max_people      INT           NULL COMMENT '成团人数上限' AFTER min_people,
    ADD COLUMN signup_deadline DATETIME      NULL COMMENT '报名截止时间' AFTER max_people,
    ADD COLUMN gather_lng      DECIMAL(10,6)  NULL COMMENT '集合点经度(集合点文字复用 meeting_point)' AFTER signup_deadline,
    ADD COLUMN gather_lat      DECIMAL(10,6)  NULL COMMENT '集合点纬度' AFTER gather_lng,
    ADD COLUMN team_status     TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '成团状态 0募集中 1已成团 2未成团已散' AFTER gather_lat,
    ADD COLUMN refund_rule     VARCHAR(255)   NULL COMMENT '退款规则(JSON/文本)' AFTER team_status,
    ADD COLUMN need_audit      TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '报名是否需主理人确认 0否1是(预留)' AFTER refund_rule;
ALTER TABLE mms_merchant
    ADD COLUMN capacity            INT          NULL COMMENT '可容纳/承接人数',
    ADD COLUMN available_time      VARCHAR(255) NULL COMMENT '可承接时段',
    ADD COLUMN suit_activity_types VARCHAR(255) NULL COMMENT '适合/想承接的活动类型(逗号分隔)',
    ADD COLUMN charge_type         TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '承接收费 0免费 1收费',
    ADD COLUMN demand              VARCHAR(500) NULL COMMENT '诉求(想要什么)',
    ADD COLUMN coop_open           TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否开放被达人对接 0否1是';
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
INSERT INTO sys_job
    (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status, create_by, create_time, remark)
SELECT '成团未成团自动退款', 'DEFAULT', 'autoRefundTask.autoRefundUnformed()', '0 0/10 * * * ?', '3', '1', '1', 'admin', NOW(),
       'F4 未成团自动退余额/积分(不接微信原路);默认暂停,真机验证后启用'
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE invoke_target = 'autoRefundTask.autoRefundUnformed()');
