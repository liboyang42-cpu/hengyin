-- B2 模板跨城 / 本地绑定：cms_topic 新增地点合同 content_contract_json。
-- 语义：模板要求（locationSlots）、城市实例绑定（locationBindings）、采用来源血缘（adoption）。
--      坐标/地址的唯一真源仍是 cms_topic_node；本合同只描述要求与绑定，不复制第二份可编辑坐标。
-- 伴随：cms_topic.city_code 已由 migration_o14_cms_topic_city_code_20261022.sql 建立，本迁移不重复建。
-- 前置：cms_topic 在 sql/base_schema_20260818.sql。
-- 落库顺序：必须先于新 jar 执行；selectCmsTopicVo 会读 content_contract_json 与 city_code。
-- 幂等：ALTER 前查 information_schema；同名但形状不符的列 fail-closed，不替换、不删除。
-- 生产执行必须走 chengyin-db-migrate；本文件只随代码交付，不自动落库。
-- 回滚（确认无数据依赖后）：ALTER TABLE cms_topic DROP COLUMN content_contract_json;

DROP TEMPORARY TABLE IF EXISTS b2_topic_contract_guard;
CREATE TEMPORARY TABLE b2_topic_contract_guard(id TINYINT PRIMARY KEY);
INSERT INTO b2_topic_contract_guard VALUES(1);

-- 目标表必须存在且为 InnoDB；否则下一条 INSERT 撞主键，整个文件停在这里。
INSERT INTO b2_topic_contract_guard
SELECT 1 WHERE (SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema=DATABASE() AND table_name='cms_topic' AND engine='InnoDB') <> 1;

-- 已存在的同名列必须正是本迁移要的形状(JSON NULL)；不符就停，不当成已完成。
INSERT INTO b2_topic_contract_guard
SELECT 1 WHERE EXISTS(SELECT 1 FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='cms_topic' AND column_name='content_contract_json'
    AND NOT (data_type='json' AND is_nullable='YES'));

SET @b2_topic_contract_ddl := IF(
  EXISTS(SELECT 1 FROM information_schema.columns
         WHERE table_schema=DATABASE() AND table_name='cms_topic' AND column_name='content_contract_json'),
  'SELECT 1',
  'ALTER TABLE cms_topic ADD COLUMN content_contract_json JSON NULL COMMENT ''地点合同:要求/绑定/采用来源;NULL=未建立'''
);
PREPARE b2_topic_contract_stmt FROM @b2_topic_contract_ddl;
EXECUTE b2_topic_contract_stmt;
DEALLOCATE PREPARE b2_topic_contract_stmt;

DROP TEMPORARY TABLE b2_topic_contract_guard;

-- runner: assert=scalar-true
SELECT COUNT(*)=1 AS assertion_ok
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='cms_topic' AND column_name='content_contract_json'
  AND data_type='json' AND is_nullable='YES';
