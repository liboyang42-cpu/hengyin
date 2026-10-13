-- ============================================================================
-- 多图上传:主题节点图 / 商家报名图 单图 → 多图
-- 存储策略:复用原列,存逗号分隔的多个 OSS URL(CSV),与项目既有 pics/categoryIds
--           的 CSV 惯例一致;后端写入为透传(BeanUtils 拷贝),无需 Java 改动。
-- 本迁移只做「加宽列」以容纳多个 URL(原 varchar(255)/varchar(300) 会溢出)。
-- 幂等:MODIFY COLUMN 可重复执行;不改数据、不丢数据。
-- 日期:2026-07-11
-- ============================================================================

-- 主题节点/地点图(原 varchar(255) 注释「地点图片(单张)」)
ALTER TABLE `cms_topic_node`
  MODIFY COLUMN `img_url` VARCHAR(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci
  DEFAULT NULL COMMENT '地点图片(多张,逗号分隔URL)';

-- 商家报名图(原 varchar(300) 注释「商家报名图片」)
ALTER TABLE `cms_registration_merchant`
  MODIFY COLUMN `pic_url` VARCHAR(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci
  DEFAULT '' COMMENT '商家报名图片(多张,逗号分隔URL)';
