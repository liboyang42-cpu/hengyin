-- 俱乐部类型字段合并(C4):新增单选大类 club_type;原多选 club_types 语义降级为"活动方向"
-- 执行方式:连生产库 source 本文件(走 chengyin-db-migrate skill,只读预检后确认再执行)
-- 背景:不再维护两套类型语义。club_type=单选大类(分类+自动标签来源),club_types=活动方向(原多选降级)。
-- 影响:新增可空列 + 改注释 + 存量回填(取 club_types 首值兜底,运营可后台校正)。
-- ⚠️ 部署顺序:本迁移必须先于新 jar 落库,否则 ClubMapper 引用 club_type 列会 SQL 报错。

ALTER TABLE club
    ADD COLUMN club_type VARCHAR(32) NULL COMMENT '俱乐部类型大类(单选):校园社团/旅行组织/兴趣社群/商业活动组织方/内容创作团队/其他' AFTER description;

-- club_types 语义降级为"活动方向"(仅改注释,长度/类型不变,存量兼容):
ALTER TABLE club
    MODIFY COLUMN club_types VARCHAR(500) NULL COMMENT '活动方向(逗号分隔,原"俱乐部类型多选"降级)';

-- 存量回填:取 club_types 首值写入 club_type 兜底(简单映射,非 6 大类时由运营后台校正):
UPDATE club
   SET club_type = SUBSTRING_INDEX(club_types, ',', 1)
 WHERE club_type IS NULL AND club_types IS NOT NULL AND club_types <> '';
