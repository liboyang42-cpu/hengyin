-- ============================================================================
-- 商家据点常驻点亮城市 · P1-M3 迁移
-- cms_member_template 加玩法语义字段(正交于已有 validation_method 校验机制):
--   interaction_type=玩法语义(展示/表单/推荐)  validation_method=校验机制(怎么算完成)
-- 幂等:列存在性守卫。⚠️ 生产走 chengyin-db-migrate 只读预检后执行,勿直连。
-- ============================================================================
SET @exist := (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'cms_member_template' AND column_name = 'interaction_type');
SET @sql := IF(@exist = 0,
  'ALTER TABLE cms_member_template
     ADD COLUMN interaction_type VARCHAR(32) NULL COMMENT ''玩法语义:hidden_menu/city_story_card/photo_spot/qr_checkin'',
     ADD COLUMN merchant_node_enabled TINYINT DEFAULT 0 COMMENT ''是否作为点亮城市商家节点互动:0否 1是'',
     ADD COLUMN node_submit_status TINYINT DEFAULT 0 COMMENT ''商家节点提交态:0草稿 1审核中 2已上线 3已驳回''',
  'SELECT ''cms_member_template 玩法字段已存在,跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
