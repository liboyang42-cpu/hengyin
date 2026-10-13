-- D18 / Option B:主理人(达人)档案表 —— 能力档案 + L1–L5 等级「挂主理人」,名下多个俱乐部共享继承
-- 执行方式:连生产库 source 本文件(走 chengyin-db-migrate skill,先只读预检)
-- 依赖:migration_club.sql、migration_club_multi.sql(club 已改 1:N)已执行
-- 设计:
--   * 能力档案 + level/tags + 定级审计 以 club_leader 为「唯一真源」(一人一档,member_id 唯一)
--   * club 表上同名能力/level 列(由 migration_club_apply_fields/audit_level 添加)在 B 方案下「废弃不用」,
--     留 NULL 无害,不在本迁移删除(避免动到在写的 WIP);展示走 join club_leader
--   * 俱乐部列表/详情按 club.member_id = club_leader.member_id join 取 level/tags 展示(同一达人名下多团同级)

CREATE TABLE IF NOT EXISTS club_leader (
    id               BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT '主键',
    member_id        BIGINT(20)   NOT NULL COMMENT '主理人会员ID(唯一,一人一档)',
    leader_name      VARCHAR(64)  NULL COMMENT '真实/对外昵称',
    phone            VARCHAR(20)  NULL COMMENT '联系电话(内部审核用)',
    identity         VARCHAR(255) NULL COMMENT '身份说明',
    co_founders      VARCHAR(255) NULL COMMENT '联合创始人',
    has_experience   TINYINT(1)   NULL COMMENT '是否有过往组织经验 0否1是',
    experience       VARCHAR(64)  NULL COMMENT '过往活动经验(场次档)',
    -- 能力自评(L1–L5 定级证据;自报仅作参考,最终人工敲定)
    max_event_size   INT          NULL COMMENT '最大活动人数',
    avg_event_size   INT          NULL COMMENT '平均活动人数',
    can_design_route TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '能否设计路线 0否1是',
    can_design_task  TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '能否设计任务 0否1是',
    can_npc          TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '能否带NPC/角色 0否1是',
    can_merchant_coop TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '能否与商家联动 0否1是',
    has_guide_cert   TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '有无导游证/旅游资质 0否1是',
    cert_images      TEXT         NULL COMMENT '资质/案例图(逗号分隔URL)',
    -- 后台人工定级(异步,不阻塞建团)
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

-- 回填:为已有俱乐部的 owner 建一条主理人档案,把 club 上已存在的能力/等级值搬过来(取该会员任一团的值)
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
