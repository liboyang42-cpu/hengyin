-- 2026-11-19 角色(Cast)v1(业主批准):cms_topic 增一列 cast_config(可空 JSON)。
-- 形状与校验见 CastConfigSupport:{enabled, playerName, playerIdentity, identities[≤4], cast[≤12]}。
-- NULL = 功能关闭(老主题行为不变)。读写走 CmsTopicMapper.selectCastConfigById / insert / update 的 <if>,
-- 不进通用列清单,未执行本迁移的库只影响角色相关接口。
-- 玩家档案 / 信任 / 剧情分支选择写在 topic_route_session.state_json(已有 JSON 列),无需 DDL。
-- 只新增可空列,可重复执行;表不存在时跳过。部署顺序:先落库,再部署读写本列的 jar。
-- 回滚:ALTER TABLE cms_topic DROP COLUMN cast_config;(旧代码不读它)。生产执行须先只读预检并取得用户确认;本文件不连接生产数据库。

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.tables
               WHERE table_schema = DATABASE() AND table_name = 'cms_topic') = 1
           AND (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'cms_topic' AND column_name = 'cast_config') = 0,
    'ALTER TABLE cms_topic ADD COLUMN cast_config JSON NULL COMMENT ''角色(Cast)配置 v1;NULL=功能关闭''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
