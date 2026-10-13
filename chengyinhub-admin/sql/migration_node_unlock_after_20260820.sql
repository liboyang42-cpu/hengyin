-- 多节点条件(玩法模板配置库 §10 升级4):节点级前置解锁 —— 完成 A 才能玩 B。
-- 与「经典定向 mode=1 整题线性防跳关(isPrevDone)」互补:本列是任意模式下的按节点前置,
-- NULL/0 = 无前置(存量行为完全不变)。服务端在 checkin/answer/arrive/photo 四个完成入口硬闸,
-- 玩家端 /nodes 对未解锁节点回 locked=true。
-- 复制主题(copyNodes)会把该引用重映射到副本节点 id。
--
-- ⚠️ 本文件只准备,不执行。生产库写操作由总控在用户点头后执行(chengyin-db-migrate)。
-- 预检:SELECT COLUMN_NAME FROM information_schema.COLUMNS
--        WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='cms_topic_node' AND COLUMN_NAME='unlock_after_node_id';
--      (0 行才执行;MySQL8 禁 ADD COLUMN IF NOT EXISTS)

ALTER TABLE cms_topic_node
  ADD COLUMN unlock_after_node_id bigint(20) NULL DEFAULT NULL
  COMMENT '前置解锁节点id:完成该节点(player_node_progress.status=1)才能玩本节点;NULL/0=无前置';

-- readback:执行后必须由目标库 information_schema 回读列形状,不能拿 DDL 回执冒充成功。
-- runner: assert=scalar-true
SELECT COUNT(*) = 1
       AND MIN(DATA_TYPE) = 'bigint'
       AND MIN(IS_NULLABLE) = 'YES'
       AND MIN(COLUMN_DEFAULT) IS NULL AS assertion_ok
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME = 'cms_topic_node'
   AND COLUMN_NAME = 'unlock_after_node_id';
