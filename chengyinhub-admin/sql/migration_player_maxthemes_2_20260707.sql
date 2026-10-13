-- 玩家上架额度 1 → 2(用户 2026-07-07 定:玩家同时在架 2 个主题)
-- 与 checkThemeOnline 强制一致(在架 user_status=1 排 selfPlay/草稿 ≤ max_themes)
-- 可逆(改回 1 即可);仅改配置行,不动任何用户数据。
UPDATE user_permission SET max_themes = 2
WHERE role = 'player' AND membership_level IS NULL AND max_themes = 1;
