-- 俱乐部申请表单富字段补充
-- 执行方式：连生产库 source 本文件
-- 背景：小程序俱乐部申请表单(4步向导)提交的 clubTypes/activityPrefs/experience/joinReason/caseImages
--       当前 club 表无对应列，数据被静默丢弃。本迁移补齐。

ALTER TABLE club
    ADD COLUMN club_types     VARCHAR(500) NULL COMMENT '俱乐部类型(逗号分隔)' AFTER description,
    ADD COLUMN activity_prefs VARCHAR(500) NULL COMMENT '活动倾向(逗号分隔)' AFTER club_types,
    ADD COLUMN experience     VARCHAR(64)  NULL COMMENT '活动经验' AFTER activity_prefs,
    ADD COLUMN join_reason    VARCHAR(500) NULL COMMENT '加入理由' AFTER experience,
    ADD COLUMN case_images    TEXT         NULL COMMENT '案例展示图(逗号分隔URL)' AFTER join_reason;
