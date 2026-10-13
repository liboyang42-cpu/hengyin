-- 俱乐部「城市定向团」归属:给主题挂 club_id(方案B)
-- 执行方式:连生产库 source 本文件(走 chengyin-db-migrate skill,只读预检后确认再执行)
-- 背景:方案B——团=城市定向主题分支。城市定向主题(票种 mode=1)发布时由后端写入发布者的 club_id,
--       用于俱乐部详情页聚合「该俱乐部办的团」(/api/club/topics 按 club_id 查),替代旧的 member_id 隐式反查。
-- 影响:仅新增可空列 + 索引,对存量数据无破坏;非城市定向主题 club_id 恒为 NULL。
-- ⚠️ 部署顺序:本迁移必须先于新 jar 落库,否则 CmsTopicMapper 引用 club_id 列会 SQL 报错(列不存在)。

ALTER TABLE cms_topic
    ADD COLUMN club_id BIGINT NULL COMMENT '所属俱乐部id(城市定向团);非城市定向团为NULL' AFTER member_id,
    ADD INDEX idx_topic_club (club_id);

-- 可选历史回填(默认不执行;城市定向判定需 join 票种 mode=1,确认无误后再手工放开):
-- UPDATE cms_topic t
--   JOIN club c ON c.member_id = t.member_id AND c.del_flag = 0
--   SET t.club_id = c.id
--   WHERE t.del_flag = 0 AND t.club_id IS NULL
--     AND EXISTS (SELECT 1 FROM oms_ticket k WHERE k.topic_id = t.id AND k.mode = 1);
