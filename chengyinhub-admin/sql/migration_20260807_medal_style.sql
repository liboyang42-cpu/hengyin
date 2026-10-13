-- 勋章双样式(用户 2026-08-07 拍板):发布者可选 glow=二维发光 / enamel=3D珐琅。
-- 预检(执行前先跑,列已存在则跳过对应 ALTER —— MySQL8 禁 ADD COLUMN IF NOT EXISTS):
--   SELECT column_name FROM information_schema.columns
--    WHERE table_schema = DATABASE() AND table_name IN ('cms_member_template','growth_badge')
--      AND column_name IN ('medal_style','badge_style');

-- ① 创作者侧:模板勋章样式(发布模板时选择)
ALTER TABLE cms_member_template
  ADD COLUMN medal_style varchar(16) NOT NULL DEFAULT 'glow' COMMENT '勋章样式:glow=二维发光/enamel=3D珐琅';

-- ② 运营侧:成长/城市徽章样式
ALTER TABLE growth_badge
  ADD COLUMN badge_style varchar(16) NOT NULL DEFAULT 'glow' COMMENT '勋章样式:glow=二维发光/enamel=3D珐琅';
