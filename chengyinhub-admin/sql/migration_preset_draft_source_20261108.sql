-- 固定玩法预设来源标记（T3F）
--
-- 只追加、不执行。作用是让"这份草稿从哪个平台预设结构起头"可追溯，且与市场采用
-- （original_template_id -> cms_template_library.id）严格区分：
--   市场采用 = 某位作者已审核上架的货架内容，带版权/已售授权语义；
--   预设起头 = 平台自带的固定结构，没有市场来源，也不代表任何人的上架内容。
-- 因此预设草稿的 original_template_id 保持 0，来源只写本列（形如 'T08-multi-clue-investigation@1'）。
-- 历史行一律为 NULL，不回填、不猜测。

ALTER TABLE `cms_member_template`
    ADD COLUMN `preset_source` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL
        COMMENT '固定预设来源 presetKey@version；市场采用仍走 original_template_id，两者互斥' AFTER `node_submit_status`;
