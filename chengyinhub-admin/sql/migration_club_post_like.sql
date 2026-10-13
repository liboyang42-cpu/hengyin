-- 俱乐部动态点赞 club_post_like(让 club_post.like_count 死字段变真)
-- 执行方式:走 chengyin-db-migrate(宝塔终端 mysql 本机),只读预检后确认。部署新jar前先落库。
-- 前置:club_post 表已建(migration_club_post.sql)。

CREATE TABLE IF NOT EXISTS `club_post_like` (
  `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
  `post_id`     BIGINT   NOT NULL COMMENT '动态ID',
  `member_id`   BIGINT   NOT NULL COMMENT '点赞会员ID',
  `create_time` DATETIME NULL COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_post_member` (`post_id`, `member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='俱乐部动态点赞';
