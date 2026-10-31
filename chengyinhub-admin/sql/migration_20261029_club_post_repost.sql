-- 俱乐部帖文转发 club_post_repost + club_post.repost_count(2026-10-29 业主拍板:动作行 点赞/评论/转发/分享)
-- 执行方式:走 chengyin-db-migrate,只读预检后确认。部署新 jar 前先落库(新 jar 读写 repost_count 与本表)。
-- 前置:club_post 表已建(migration_club_post.sql)。照 club_post_like 的形状,另加 del_flag:
--   撤销转发置 del_flag=2,再转发复用同一行(del_flag=0 + 刷新 create_time),
--   所以 uk_post_member 保证同一人对同一帖文最多一条有效转发。
-- 可重复执行:建表 IF NOT EXISTS;加列先查 information_schema。不回填、不改历史行。

CREATE TABLE IF NOT EXISTS `club_post_repost` (
  `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
  `post_id`     BIGINT   NOT NULL COMMENT '被转发的帖文ID',
  `member_id`   BIGINT   NOT NULL COMMENT '转发会员ID',
  `create_time` DATETIME NULL COMMENT '转发时间(再次转发时刷新)',
  `del_flag`    TINYINT  NOT NULL DEFAULT 0 COMMENT '0有效 2已撤销',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_post_member` (`post_id`, `member_id`),
  KEY `idx_member_time` (`member_id`, `del_flag`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='俱乐部帖文转发';

SET @club_post_repost_count_exists := (
    SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='club_post' AND column_name='repost_count');
SET @club_post_repost_count_sql := IF(@club_post_repost_count_exists=0,
    'ALTER TABLE club_post ADD COLUMN repost_count INT DEFAULT 0 COMMENT ''转发数'' AFTER comment_count',
    'SELECT 1');
PREPARE club_post_repost_count_stmt FROM @club_post_repost_count_sql;
EXECUTE club_post_repost_count_stmt;
DEALLOCATE PREPARE club_post_repost_count_stmt;

-- runner: assert=scalar-true
SELECT (
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='club_post' AND column_name='repost_count')=1
    AND (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema=DATABASE() AND table_name='club_post_repost'
        AND index_name='uk_post_member' AND non_unique=0)=2
) AS club_post_repost_ready;

-- 回退:保留表与列(旧 jar 不读它们)。禁止 DROP —— 会丢掉用户的转发记录。
