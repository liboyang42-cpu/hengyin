-- 广场运动feed:路线静态预览图字段
-- 用途:发布时前端把关联活动/主题的路线渲染成 <map> 快照并上传 OSS,
--      URL 存这里;广场列表卡片直接当普通图片渲染(轻量,避免每卡一个 map)。
-- 说明:后端 list/info 通过 base 表(ums_creative_square)回读该字段,
--      故 view_creative_square 视图可不改;如需视图也带该列,见文末可选语句。

ALTER TABLE `ums_creative_square`
    ADD COLUMN `route_preview_img` VARCHAR(500) NULL COMMENT '运动feed路线静态预览图URL(发布快照)' AFTER `is_crowdfunding`;

-- (可选)若希望视图直接返回该列(当前后端不依赖,留作将来):
-- 需用线上视图现有定义 CREATE OR REPLACE VIEW view_creative_square AS ... ,
-- 在其 SELECT 列表里补 c.route_preview_img(c 为 ums_creative_square 别名)。
-- 由于视图定义未纳入仓库,此处不提供完整 DDL,改视图时以线上现有定义为准。
