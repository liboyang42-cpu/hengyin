-- A8 原迁移已属于历史制品，保持 master 原字节，不改执行台账校验和。
-- 原封面 guard 使用相对路径，而种子实际写绝对 CDN 地址；以独立只读断言替代。
-- 没有本批种子时安全通过；存在任一行时必须完整、唯一且保持作者身份与 CDN 封面。
-- 不插入种子、不覆盖作者内容；重复执行只有同一校验结果，不改变业务数据。
-- 旧迁移是否已执行须负责人先只读核对台账；本文件不能挽救旧迁移首次执行的事务失败。
-- runner: assert=scalar-true
SELECT (
    NOT EXISTS (
        SELECT 1 FROM cms_template_library
        WHERE member_id = 100000 AND del_flag = 0
          AND (create_by = 'seed_merchant_game_a8_20260823'
               OR title IN ('招牌菜限时盲猜','咖啡风味人格','隐藏菜单线索链','60秒健身知识反应赛',
                            '节奏盲猜夜','分支故事接力','污渍侦探','家居安全排雷'))
    )
    OR (
        (SELECT COUNT(*) FROM cms_template_library
         WHERE member_id = 100000 AND del_flag = 0
           AND create_by = 'seed_merchant_game_a8_20260823') = 8
        AND (SELECT COUNT(DISTINCT title) FROM cms_template_library
             WHERE member_id = 100000 AND del_flag = 0
               AND title IN ('招牌菜限时盲猜','咖啡风味人格','隐藏菜单线索链','60秒健身知识反应赛',
                             '节奏盲猜夜','分支故事接力','污渍侦探','家居安全排雷')) = 8
        AND (SELECT COUNT(*) FROM cms_template_library
             WHERE member_id = 100000 AND del_flag = 0
               AND title IN ('招牌菜限时盲猜','咖啡风味人格','隐藏菜单线索链','60秒健身知识反应赛',
                             '节奏盲猜夜','分支故事接力','污渍侦探','家居安全排雷')) = 8
        AND (SELECT COUNT(*) FROM cms_template_library
             WHERE member_id = 100000 AND del_flag = 0
               AND create_by = 'seed_merchant_game_a8_20260823'
               AND img_url LIKE 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/%.jpg'
               AND story_img = img_url) = 8
    )
) AS a8_cdn_cover_ok;
