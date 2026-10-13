-- 俱乐部自建勋章:growth_badge 加 club_id 归属(L5)
-- 走 chengyin-db-migrate skill:只读预检(--describe growth_badge 看 club_id 是否已存在)后确认再执行。
-- ADD COLUMN nullable,MySQL8 为 INSTANT(秒级不锁表),可逆(DROP COLUMN club_id)。
-- ⚠️ 阻断型:jar 的 GrowthBadgeMapper 的 insert/select 引用 club_id,必须先于部署 jar 执行,否则报 Unknown column。

ALTER TABLE growth_badge
    ADD COLUMN club_id BIGINT NULL COMMENT '俱乐部ID(俱乐部自建勋章归属;NULL=平台全局)';
