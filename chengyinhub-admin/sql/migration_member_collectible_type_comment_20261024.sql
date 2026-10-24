-- C:U-063:member_collectible.coll_type / ref_id 列注释只写了第一期的 1=碎片 2=彩蛋,
-- 之后代码新增了 3/4/5(MemberCollectible.TYPE_NODE_FAV / TYPE_EVENT_BADGE / TYPE_EXPLORE_STAMP),
-- 读 schema 的人会以为只有两种。本文件只补注释,不改类型/可空性/默认值,不加约束。
-- 前置:member_collectible 由 migration_member_collectible.sql 建表(亦见 sql/base_schema_20260818.sql):
--     `coll_type` tinyint NOT NULL / `ref_id` bigint DEFAULT NULL。
--
-- ⚠️ MODIFY COLUMN 会用这里写的定义整条替换原定义。守卫先确认两列正是上述形状,不符就停在守卫上(主键冲突),不改任何东西。
--    coll_type、ref_id 都在唯一键 uk_member_type_ref 里;类型不变,索引不受影响。
-- 幂等:重跑只是把同样的注释再写一遍;ALGORITHM=INSTANT 只改元数据,不支持时响亮报错而不是重建表。
DROP TEMPORARY TABLE IF EXISTS member_collectible_type_comment_20261024_guard;
CREATE TEMPORARY TABLE member_collectible_type_comment_20261024_guard(id TINYINT PRIMARY KEY);
INSERT INTO member_collectible_type_comment_20261024_guard VALUES(1);

INSERT INTO member_collectible_type_comment_20261024_guard
SELECT 1 WHERE (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='member_collectible' AND column_name='coll_type'
    AND data_type='tinyint' AND is_nullable='NO' AND column_default IS NULL) <> 1;

INSERT INTO member_collectible_type_comment_20261024_guard
SELECT 1 WHERE (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='member_collectible' AND column_name='ref_id'
    AND data_type='bigint' AND is_nullable='YES' AND column_default IS NULL) <> 1;

DROP TEMPORARY TABLE member_collectible_type_comment_20261024_guard;

ALTER TABLE member_collectible
    MODIFY COLUMN coll_type tinyint NOT NULL COMMENT '1=碎片 2=彩蛋 3=漫游商家节点收藏 4=官方活动限定徽章 5=探店日按章核销凭证',
    MODIFY COLUMN ref_id bigint DEFAULT NULL COMMENT '1=node_id 2=egg_id 3=roam_poi.id 4=official_event.id 5=chapter_id',
    ALGORITHM=INSTANT;

-- 落库后回读:注释已补全,类型/可空性/默认值不变。
SELECT column_name, column_type, column_default, is_nullable, column_comment
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'member_collectible'
  AND column_name IN ('coll_type', 'ref_id');
