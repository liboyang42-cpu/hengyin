-- 俱乐部动态评论 club_post_comment(让 club_post.comment_count 死字段变真)
-- 执行方式:走 chengyin-db-migrate(宝塔终端 mysql 本机),只读预检后确认。部署新jar前先落库。
-- 前置:club_post 表已建(migration_club_post.sql)。

CREATE TABLE IF NOT EXISTS `club_post_comment` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `post_id`     BIGINT       NOT NULL COMMENT '动态ID',
  `member_id`   BIGINT       NOT NULL COMMENT '评论会员ID',
  `content`     VARCHAR(1000) NULL COMMENT '评论内容',
  `del_flag`    INT          DEFAULT 0 COMMENT '0存在 2删除',
  `create_time` DATETIME     NULL COMMENT '评论时间',
  PRIMARY KEY (`id`),
  KEY `idx_comment_post` (`post_id`, `del_flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='俱乐部动态评论';
