-- FE-01 玩家收集(碎片/彩蛋)member 级持久化。
-- 统一一张表,coll_type 区分:1=碎片(mode2 完成节点翻出的 fragment)/ 2=彩蛋(途中 egg 触发)。
-- 幂等:唯一键 (member_id, coll_type, ref_id) —— 同一节点碎片 / 同一彩蛋只收一次。
-- 执行:连生产库 source 本文件(走 chengyin-db-migrate skill,只读预检后确认)。幂等,可重复跑。
-- ⚠️ 部署顺序:先于新 jar 落库,否则 MemberCollectibleMapper 引用 member_collectible 会 SQL 报错。

CREATE TABLE IF NOT EXISTS member_collectible (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  member_id   BIGINT       NOT NULL COMMENT '玩家 member_id',
  coll_type   TINYINT      NOT NULL COMMENT '1=碎片 2=彩蛋',
  topic_id    BIGINT       NULL COMMENT '所属主题',
  ref_id      BIGINT       NULL COMMENT '碎片=node_id / 彩蛋=egg_id',
  content     VARCHAR(1024) NULL COMMENT '碎片文 / 彩蛋文',
  create_time DATETIME     NULL COMMENT '收集时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_member_type_ref (member_id, coll_type, ref_id),
  KEY idx_member_type (member_id, coll_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='FE-01 玩家收集:碎片/彩蛋';
