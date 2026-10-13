-- RBAC R2 俱乐部子系统 迁移
-- 执行方式：MySQL 连生产库后 source 本文件
-- 依赖：先执行 migration_rbac_role.sql（role 字段）

-- 1. 俱乐部表（镜像商家：一个会员一个俱乐部，status 审核态）
CREATE TABLE IF NOT EXISTS club (
    id            BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT '主键',
    member_id     BIGINT(20)   NOT NULL COMMENT '拥有者会员ID',
    name          VARCHAR(64)  NOT NULL COMMENT '俱乐部名称',
    logo          VARCHAR(255) NULL COMMENT 'LOGO/头像',
    cover         VARCHAR(255) NULL COMMENT '封面图',
    description   VARCHAR(1000) NULL COMMENT '简介',
    category_id   BIGINT(20)   NULL COMMENT '分类ID(SysCategory)',
    address       VARCHAR(255) NULL COMMENT '地点',
    leader_name   VARCHAR(64)  NULL COMMENT '负责人',
    phone         VARCHAR(20)  NULL COMMENT '联系电话',
    status        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '审核状态 0待审核 1通过 2驳回',
    reject_reason VARCHAR(255) NULL COMMENT '驳回原因',
    member_count  INT          NOT NULL DEFAULT 0 COMMENT '成员数(冗余)',
    create_by     VARCHAR(64)  NULL,
    create_time   DATETIME     NULL,
    update_by     VARCHAR(64)  NULL,
    update_time   DATETIME     NULL,
    remark        VARCHAR(500) NULL,
    del_flag      INT          NOT NULL DEFAULT 0 COMMENT '0存在 2删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_club_member (member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='俱乐部';

-- 2. 俱乐部成员表（加入关系，加入即时无需审核）
CREATE TABLE IF NOT EXISTS club_member (
    id          BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
    club_id     BIGINT(20) NOT NULL COMMENT '俱乐部ID',
    member_id   BIGINT(20) NOT NULL COMMENT '会员ID',
    role        TINYINT(1) NOT NULL DEFAULT 0 COMMENT '成员角色 0成员(群主=club.member_id)',
    status      TINYINT(1) NOT NULL DEFAULT 1 COMMENT '1正常',
    join_time   DATETIME   NULL COMMENT '加入时间',
    del_flag    INT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_club_member_uniq (club_id, member_id),
    KEY idx_club (club_id),
    KEY idx_member (member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='俱乐部成员';

-- 3. 后台菜单 + 权限（IDs 如与现有冲突请调整；或用 RuoYi「菜单管理」手动加）
-- 父菜单：业务管理(按现有 business 父菜单 menu_id 调整 parent_id)；这里用子查询找名为"业务管理"的菜单，找不到则需手动指定
SET @parent := (SELECT menu_id FROM sys_menu WHERE menu_name IN ('业务管理','内容管理','系统管理') ORDER BY parent_id LIMIT 1);
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time)
VALUES ('俱乐部管理', IFNULL(@parent,0), 9, 'club', 'business/club/index', 1, 0, 'C', '0', '0', 'business:club:list', 'peoples', 'admin', sysdate());
SET @m := LAST_INSERT_ID();
INSERT INTO sys_menu (menu_name, parent_id, order_num, menu_type, visible, status, perms, create_by, create_time) VALUES
('俱乐部查询', @m, 1, 'F', '0', '0', 'business:club:query',  'admin', sysdate()),
('俱乐部新增', @m, 2, 'F', '0', '0', 'business:club:add',    'admin', sysdate()),
('俱乐部修改', @m, 3, 'F', '0', '0', 'business:club:edit',   'admin', sysdate()),
('俱乐部删除', @m, 4, 'F', '0', '0', 'business:club:remove', 'admin', sysdate()),
('俱乐部审核', @m, 5, 'F', '0', '0', 'business:club:audit',  'admin', sysdate());
