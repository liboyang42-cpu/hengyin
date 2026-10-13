-- 俱乐部动态(社群feed)P2:club_post
-- 执行方式:走 chengyin-db-migrate(宝塔终端 mysql 本机执行,只读预检后确认)
-- 部署顺序:本迁移先于新 jar 落库,否则 ClubPostMapper 引用表会报"表不存在"。

CREATE TABLE IF NOT EXISTS `club_post` (
  `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `club_id`          BIGINT       NOT NULL COMMENT '俱乐部ID',
  `author_member_id` BIGINT       NOT NULL COMMENT '作者会员ID',
  `content`          TEXT         NULL COMMENT '正文',
  `images`           VARCHAR(2000) NULL COMMENT '图片(逗号分隔URL)',
  `type`             INT          DEFAULT 0 COMMENT '类型 0图文 1战报 2公告',
  `like_count`       INT          DEFAULT 0 COMMENT '点赞数',
  `comment_count`    INT          DEFAULT 0 COMMENT '评论数',
  `del_flag`         INT          DEFAULT 0 COMMENT '0存在 2删除',
  `create_time`      DATETIME     NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_club_post_club` (`club_id`, `del_flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='俱乐部动态';
