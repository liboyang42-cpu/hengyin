-- ============================================================================
-- 把打进小程序包的封面路径改成走服务端静态目录,给主包腾体积。
--
-- 背景:cms_topic / cms_template_library 里有 44 行封面写成 `/images/...`,
-- 那是小程序包内路径 —— 图必须跟着包一起发,主包因此被撑到 6.48MB(微信限 2MB)。
-- 仓库里 41/58 个主题本来就走 http 外链,`/images/` 是 seed 引进来的例外。
--
-- 落点沿用仓库既有成例 uploadPath/template-covers:
--   /www/wwwroot/uploadPath/<dir>/<file>
--   → https://www.chengyinhub.com/prod-api/profile/<dir>/<file>
-- 主题封面转成 webp(1280x960 → 900 宽,2.19MB → 0.89MB);
-- 商家游戏封面本来就是 640x480@28KB,webp 反而变大,原样搬。
--
-- ⚠️ 前置:图必须先真上传并公网回读通过,再跑本迁移。顺序反了 = 封面全裂。
-- 幂等:按旧值精确匹配改写,重跑不会二次改动。
-- 不走 --transaction:窄事务白名单不收后置断言 SELECT。两条 UPDATE 各自幂等,
-- 中途失败重跑即收敛,末尾断言会把「只搬了一半」判红。
-- ============================================================================

UPDATE cms_topic
SET img_url = CONCAT('https://www.chengyinhub.com/prod-api/profile/topic-covers/',
                     REPLACE(SUBSTRING_INDEX(img_url, '/', -1), '.jpg', '.webp'))
WHERE img_url LIKE '/images/topic-templates/shanghai-solo/%.jpg';

UPDATE cms_template_library
SET img_url = CONCAT('https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/',
                     SUBSTRING_INDEX(img_url, '/', -1))
WHERE img_url LIKE '/images/merchant-games/%';

-- runner: assert=scalar-true
SELECT (
  NOT EXISTS (SELECT 1 FROM cms_topic WHERE img_url LIKE '/images/%')
  AND NOT EXISTS (SELECT 1 FROM cms_template_library WHERE img_url LIKE '/images/%')
  AND (SELECT COUNT(*) FROM cms_topic
        WHERE img_url LIKE 'https://www.chengyinhub.com/prod-api/profile/topic-covers/%.webp') >= 9
  AND (SELECT COUNT(*) FROM cms_template_library
        WHERE img_url LIKE 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/%') >= 35
) AS covers_moved_off_package;

-- 回滚(仅在图被撤下时用):
-- UPDATE cms_topic SET img_url = CONCAT('/images/topic-templates/shanghai-solo/',
--   REPLACE(SUBSTRING_INDEX(img_url,'/',-1),'.webp','.jpg'))
--   WHERE img_url LIKE 'https://www.chengyinhub.com/prod-api/profile/topic-covers/%';
-- UPDATE cms_template_library SET img_url = CONCAT('/images/merchant-games/',
--   SUBSTRING_INDEX(img_url,'/',-1))
--   WHERE img_url LIKE 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/%';
