-- 主题具名状态键注册表(B1 旅程体验模组化第一轮)。
-- 主题注册 clue./relation./tag./counter. 前缀的状态键;玩法块配置(advancedConfigJson)
-- 的 requires/effects/check 引用这些键才能发布。运行期状态只由服务端写,本表只存注册声明。
-- 生产执行必须走 chengyin-db-migrate；本文件只随代码交付，不自动落库。

CREATE TABLE IF NOT EXISTS theme_state_key (
  id BIGINT NOT NULL AUTO_INCREMENT,
  topic_id BIGINT NOT NULL COMMENT '所属主题 cms_topic.id',
  state_key VARCHAR(64) NOT NULL COMMENT '键名:^(clue|relation|tag|counter)\.[a-z][a-z0-9_]{0,47}$',
  key_type VARCHAR(16) NOT NULL COMMENT 'flag/counter/relation/tag;clue.* 用 flag',
  scope VARCHAR(16) NOT NULL COMMENT 'run/party/deployment;本轮只实现 run',
  label VARCHAR(128) DEFAULT NULL COMMENT '作者可读名',
  initial_value INT NOT NULL DEFAULT 0 COMMENT '初始值,仅 counter 有意义',
  create_time DATETIME(3) NOT NULL,
  update_time DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_theme_state_key (topic_id, state_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='主题具名状态键注册表';

-- runner: assert=scalar-true
SELECT (
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='theme_state_key' AND column_name IN
      ('id','topic_id','state_key','key_type','scope','label','initial_value','create_time','update_time'))=9
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='theme_state_key' AND index_name='uk_theme_state_key' AND non_unique=0)=2
) AS migration_assertion;

-- 回滚（有注册数据后必须先导出，禁止盲删）：
-- DROP TABLE theme_state_key;
