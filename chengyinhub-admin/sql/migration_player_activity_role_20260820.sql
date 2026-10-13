-- 角色系统(玩法模板配置库 §10 升级2):俱乐部活动自动分配角色。
-- 时机 = 玩家首次拉 /api/play/nodes 且该活动是俱乐部局(cms_activity.club_id 非空)且已报名:
-- 按已分配人数轮转发 5 个固定角色(导航员/观察员/记录员/交涉员/解谜员),落本表,响应带 myRole。
-- C06 文档口径:第一版线下抽卡 → 本迁移把它升级为系统自动分配。
--
-- ⚠️ 本文件只准备,不执行。生产库写操作由总控在用户点头后执行(chengyin-db-migrate)。
-- 预检:SELECT TABLE_NAME FROM information_schema.TABLES
--        WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='player_activity_role';(0 行才执行)

CREATE TABLE player_activity_role (
  id           bigint(20)  NOT NULL AUTO_INCREMENT,
  activity_id  bigint(20)  NOT NULL COMMENT '活动id',
  member_id    bigint(20)  NOT NULL COMMENT '会员id',
  role_code    varchar(20) NOT NULL COMMENT 'NAVIGATOR导航员/OBSERVER观察员/RECORDER记录员/NEGOTIATOR交涉员/DECODER解谜员',
  create_time  datetime    DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_par_activity_member (activity_id, member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='俱乐部活动自动分配角色(一人一活动一角色;并发双请求靠唯一键收敛)';

-- readback:执行后必须由目标库回读表、列与唯一约束,不能拿 CREATE 回执冒充成功。
-- runner: assert=scalar-true
SELECT (SELECT COUNT(*)
          FROM information_schema.TABLES
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = 'player_activity_role'
           AND ENGINE = 'InnoDB') = 1
       AND (SELECT COUNT(*)
              FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'player_activity_role'
               AND COLUMN_NAME IN ('id', 'activity_id', 'member_id', 'role_code', 'create_time')) = 5
       AND (SELECT COUNT(*)
              FROM information_schema.TABLE_CONSTRAINTS
             WHERE CONSTRAINT_SCHEMA = DATABASE()
               AND TABLE_NAME = 'player_activity_role'
               AND CONSTRAINT_NAME = 'PRIMARY'
               AND CONSTRAINT_TYPE = 'PRIMARY KEY') = 1
       AND (SELECT COUNT(*)
              FROM information_schema.TABLE_CONSTRAINTS
             WHERE CONSTRAINT_SCHEMA = DATABASE()
               AND TABLE_NAME = 'player_activity_role'
               AND CONSTRAINT_NAME = 'uk_par_activity_member'
               AND CONSTRAINT_TYPE = 'UNIQUE') = 1 AS assertion_ok;
