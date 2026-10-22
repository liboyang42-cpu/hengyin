-- F2 RPG P0 / T02:动态角色卡的跨局属性台账 theme_character。
-- 一位玩家在一个主题下一行;只存 persist_scope=topic 的属性值(run 作用域每局从初值重置,不落这里)。
-- 写入只由服务端在建新一局时从该玩家最近一局的权威状态结转,(source_session_id, source_version) 单调递增保证幂等。
-- 没有行 = 还没建过角色,接口返回模板(各属性初值)。
-- 依赖:migration_theme_state_key_20260916.sql、migration_20261022_theme_state_key_attribute_def.sql(读 persist_scope)。
-- 生产执行须走 chengyin-db-migrate 只读预检并取得用户确认;本文件不连接生产数据库。

CREATE TABLE IF NOT EXISTS theme_character (
  id BIGINT NOT NULL AUTO_INCREMENT,
  member_id BIGINT NOT NULL COMMENT '玩家 ums_member.id',
  topic_id BIGINT NOT NULL COMMENT '所属主题 cms_topic.id',
  values_json VARCHAR(4096) NOT NULL COMMENT 'persist_scope=topic 的属性值 {stateKey:int}',
  source_session_id BIGINT NOT NULL DEFAULT 0 COMMENT '最近一次结转来源 topic_route_session.id',
  source_version INT NOT NULL DEFAULT 0 COMMENT '结转时来源局的状态版本',
  create_time DATETIME(3) NOT NULL,
  update_time DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_theme_character (member_id, topic_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='主题角色卡跨局属性台账';

-- runner: assert=scalar-true
SELECT (
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='theme_character' AND column_name IN
      ('id','member_id','topic_id','values_json','source_session_id','source_version','create_time','update_time'))=8
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='theme_character' AND index_name='uk_theme_character' AND non_unique=0)=2
) AS migration_assertion;

-- 回滚(有台账数据后必须先导出,禁止盲删):
-- DROP TABLE theme_character;
