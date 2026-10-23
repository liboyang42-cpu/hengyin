-- 俱乐部「入会欢迎语」(业主 2026-10-22):新成员加入后的欢迎弹层里,主理人自己写的一段话。
-- 本文件仅为迁移草案。执行前必须走 chengyin-db-migrate 只读预检,真库执行须业主确认。
--
-- ★ 默认 NULL = 没写:与加列之前的行为逐字等价(欢迎弹层只显示系统默认的「你可以做什么」)。
-- ★ 代码对这一列是「单独读写 + 读失败当没写」(ClubMapper.selectWelcomeNote / updateWelcomeNote),
--   没有并进 selectClubVo:代码先于迁移上线时,俱乐部详情与列表照常,只是设置页保存会提示稍后再试。
-- ★ 长度:接口限 200 字(按码点),列给 VARCHAR(600) 按 utf8mb4 足够且留余量。

-- 重复/半迁移预检:必须返回 0 行;有结果即停止,不得继续 ALTER。
SELECT column_name
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'club'
  AND column_name = 'welcome_note';

ALTER TABLE club
    ADD COLUMN welcome_note VARCHAR(600) NULL DEFAULT NULL
        COMMENT '入会欢迎语:主理人自定,新成员加入后欢迎弹层最上面显示;NULL/空=没写';

-- 落库后回读:列存在、可空、默认 NULL。
SELECT column_name, column_default, is_nullable, column_type, column_comment
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'club'
  AND column_name = 'welcome_note';
