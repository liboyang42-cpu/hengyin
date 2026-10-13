-- 俱乐部帖子的「引用」:一条帖子可以带上一次游玩记录或一个模板,卡片形态由引用类型派生。
-- 类别:③ 数据库迁移。合并前必须由总控走 chengyin-db-migrate,只读预检、用户确认、快照后手动落库。
-- 部署顺序:本 SQL 先落库,再合并/部署引用它的新 jar。
--
-- ★ 为什么是两列而不是一列外键:被引用的对象不在同一张表里。口径**照抄**广场帖的
--   (data_type, data_id) —— 1=活动完赛 2=主题/模板 3=自由漫游成绩。照抄的好处是
--   前端那套 variant 判定(utils/feed-play-card.js)一个字都不用改:把 ref_type 当
--   dataType 喂进去就认得出来。自己发明一套编号,迟早跟广场那边分叉。
--
-- ★ 两列都可空,默认 NULL = 普通图文帖,与加这两列之前的行为完全一致。
--   不设外键约束:被引用的行可能后来被删/下架,那时卡片降级成普通图文帖即可,
--   不能因为一条引用失效就让整个俱乐部帖子流写不进去。
--
-- 执行前预检(前两条必须各返回 0 行;第三条确认 AFTER 锚点存在):
-- SELECT COLUMN_NAME FROM information_schema.COLUMNS
-- WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'club_post' AND COLUMN_NAME = 'ref_type';
-- SELECT COLUMN_NAME FROM information_schema.COLUMNS
-- WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'club_post' AND COLUMN_NAME = 'ref_id';
-- SELECT COLUMN_NAME FROM information_schema.COLUMNS
-- WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'club_post' AND COLUMN_NAME = 'images';

ALTER TABLE club_post
  ADD COLUMN ref_type TINYINT(1) NULL DEFAULT NULL
  COMMENT '引用类型(口径同广场帖 data_type):1活动完赛 2主题/模板 3自由漫游成绩;NULL=普通图文帖'
  AFTER images;

ALTER TABLE club_post
  ADD COLUMN ref_id BIGINT NULL DEFAULT NULL
  COMMENT '被引用对象ID(口径同广场帖 data_id):ref_type=1 时是 activity_id,2/3 时是 topic_id'
  AFTER ref_type;

-- 执行后回读(各返回 1 行,nullable=YES、default=NULL):
-- SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
-- FROM information_schema.COLUMNS
-- WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'club_post' AND COLUMN_NAME IN ('ref_type', 'ref_id');
-- SELECT ref_type, COUNT(*) AS post_count FROM club_post WHERE del_flag = 0 GROUP BY ref_type;
--
-- 回滚(先回滚 jar 再删列;顺序反了,还在跑的旧请求会写两个已经不存在的列):
-- SELECT id, club_id, author_member_id, ref_type, ref_id FROM club_post WHERE ref_type IS NOT NULL;
-- ALTER TABLE club_post DROP COLUMN ref_id;
-- ALTER TABLE club_post DROP COLUMN ref_type;
