-- runner: requires=transaction
-- 模板与主题封面映射：27 个公共玩法 + 29 个完整主题。
-- 图片文件必须先上传并通过 SHA-256 回读，再执行本迁移；数据库只保存稳定 HTTPS URL。

CREATE TEMPORARY TABLE template_cover_map (
    kind VARCHAR(16) NOT NULL,
    title VARCHAR(255) NOT NULL,
    img_url VARCHAR(500) NOT NULL,
    PRIMARY KEY (kind, title),
    CHECK (kind IN ('library', 'topic'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

INSERT INTO template_cover_map (kind, title, img_url) VALUES
    ('library', '今晚的暗号', 'https://www.chengyinhub.com/prod-api/profile/template-covers/p01-tonights-passcode.webp'),
    ('library', '老板的秘密题', 'https://www.chengyinhub.com/prod-api/profile/template-covers/p02-boss-secret-question.webp'),
    ('library', '拍下这一刻', 'https://www.chengyinhub.com/prod-api/profile/template-covers/p03-capture-this-moment.webp'),
    ('library', '收集这枚风味印章', 'https://www.chengyinhub.com/prod-api/profile/template-covers/p04-flavor-stamp.webp'),
    ('library', '新品盲测局', 'https://www.chengyinhub.com/prod-api/profile/template-covers/p05-new-product-blind-test.webp'),
    ('library', '今日搭配任务', 'https://www.chengyinhub.com/prod-api/profile/template-covers/p06-todays-styling-task.webp'),
    ('library', '集合点亮', 'https://www.chengyinhub.com/prod-api/profile/template-covers/p07-rally-light-up.webp'),
    ('library', '小队竞速点亮', 'https://www.chengyinhub.com/prod-api/profile/template-covers/p08-team-race-light-up.webp'),
    ('library', '街角谜题', 'https://www.chengyinhub.com/prod-api/profile/template-covers/p09-street-corner-riddle.webp'),
    ('library', '城市取景任务', 'https://www.chengyinhub.com/prod-api/profile/template-covers/p10-city-framing-task.webp'),
    ('library', '城市接力棒', 'https://www.chengyinhub.com/prod-api/profile/template-covers/p11-city-relay.webp'),
    ('library', '今日角色任务', 'https://www.chengyinhub.com/prod-api/profile/template-covers/p12-todays-role-task.webp'),
    ('library', 'G0｜30秒生活掉线测试', 'https://www.chengyinhub.com/prod-api/profile/template-covers/p13-life-outage-test.webp'),
    ('library', 'G1｜现在出状况,你先选什么', 'https://www.chengyinhub.com/prod-api/profile/template-covers/p14-what-to-choose-first.webp'),
    ('library', 'G2｜我的底线三选一', 'https://www.chengyinhub.com/prod-api/profile/template-covers/p15-bottom-line-three-choices.webp'),
    ('library', 'G3｜营业时间拼图', 'https://www.chengyinhub.com/prod-api/profile/template-covers/p16-business-hours-puzzle.webp'),
    ('library', 'G4｜去之前准备什么', 'https://www.chengyinhub.com/prod-api/profile/template-covers/p17-before-you-go-checklist.webp'),
    ('library', 'G5｜值不值得现在处理', 'https://www.chengyinhub.com/prod-api/profile/template-covers/p18-worth-doing-now.webp'),
    ('library', 'G6｜首选失效怎么办', 'https://www.chengyinhub.com/prod-api/profile/template-covers/p19-first-choice-offline.webp'),
    -- 2026-08-25 补:今天值得庆祝 G0–G7 此前只有图标没有封面,货架 bannerList/latestList
    -- 读 item.imgUrl,缺封面渲染成「封面暂不可用」错误卡,而它们是最新一批会被排到最前。
    ('library', 'G0｜30秒今天值得什么', 'https://www.chengyinhub.com/prod-api/profile/template-covers/p20-worth-today-30s.webp'),
    ('library', 'G1｜每人一个不能', 'https://www.chengyinhub.com/prod-api/profile/template-covers/p21-one-cannot-each.webp'),
    ('library', 'G2｜气氛卡碰撞', 'https://www.chengyinhub.com/prod-api/profile/template-covers/p22-mood-card-clash.webp'),
    ('library', 'G3｜三小时时间方块', 'https://www.chengyinhub.com/prod-api/profile/template-covers/p23-three-hour-blocks.webp'),
    ('library', 'G4｜每人预算筹码', 'https://www.chengyinhub.com/prod-api/profile/template-covers/p24-budget-chips.webp'),
    ('library', 'G5｜十分钟步行上限', 'https://www.chengyinhub.com/prod-api/profile/template-covers/p25-ten-minute-walk.webp'),
    ('library', 'G6｜主选突然掉线', 'https://www.chengyinhub.com/prod-api/profile/template-covers/p26-main-choice-offline.webp'),
    ('library', 'G7｜60秒共同落款', 'https://www.chengyinhub.com/prod-api/profile/template-covers/p27-cosign-60s.webp'),

    ('topic', '隐藏菜单', 'https://www.chengyinhub.com/prod-api/profile/template-covers/t01-hidden-menu.webp'),
    ('topic', '店内寻宝', 'https://www.chengyinhub.com/prod-api/profile/template-covers/t02-in-store-treasure-hunt.webp'),
    ('topic', '拍照挑战', 'https://www.chengyinhub.com/prod-api/profile/template-covers/t03-photo-challenge.webp'),
    ('topic', '神秘店员的暗号', 'https://www.chengyinhub.com/prod-api/profile/template-covers/t04-mysterious-clerk-passcode.webp'),
    ('topic', '三步解谜', 'https://www.chengyinhub.com/prod-api/profile/template-covers/t05-three-step-puzzle.webp'),
    ('topic', '听音辨物', 'https://www.chengyinhub.com/prod-api/profile/template-covers/t06-listen-and-identify.webp'),
    ('topic', '三连快答', 'https://www.chengyinhub.com/prod-api/profile/template-covers/t07-three-quick-answers.webp'),
    ('topic', '集章巡店', 'https://www.chengyinhub.com/prod-api/profile/template-covers/t08-stamp-tour.webp'),
    ('topic', '岔路口的选择', 'https://www.chengyinhub.com/prod-api/profile/template-covers/t09-fork-in-the-road.webp'),
    ('topic', '三个盲盒', 'https://www.chengyinhub.com/prod-api/profile/template-covers/t10-three-blind-boxes.webp'),
    ('topic', '闭眼味觉师', 'https://www.chengyinhub.com/prod-api/profile/template-covers/t11-blind-taste-master.webp'),
    ('topic', '菜单顺口溜', 'https://www.chengyinhub.com/prod-api/profile/template-covers/t12-menu-tongue-twister.webp'),
    ('topic', '沉默点单', 'https://www.chengyinhub.com/prod-api/profile/template-covers/t13-silent-ordering.webp'),
    ('topic', '便利店调酒师', 'https://www.chengyinhub.com/prod-api/profile/template-covers/t14-convenience-store-mixologist.webp'),
    ('topic', '逆向NPC', 'https://www.chengyinhub.com/prod-api/profile/template-covers/t15-reverse-npc.webp'),
    ('topic', '爪印巡街', 'https://www.chengyinhub.com/prod-api/profile/template-covers/t16-pawprint-street-tour.webp'),
    ('topic', '治愈音乐角', 'https://www.chengyinhub.com/prod-api/profile/template-covers/t17-healing-music-corner.webp'),
    ('topic', '穿搭盲配', 'https://www.chengyinhub.com/prod-api/profile/template-covers/t18-blind-style-mix.webp'),
    ('topic', '生活不掉线', 'https://www.chengyinhub.com/prod-api/profile/template-covers/t19-life-backup.webp'),
    ('topic', '下班后切换频道', 'https://www.chengyinhub.com/prod-api/profile/template-covers/t20-after-work-switch.webp'),
    ('topic', '一个人也出发', 'https://www.chengyinhub.com/prod-api/profile/template-covers/t21-solo-departure.webp'),
    ('topic', '亲子各自开心', 'https://www.chengyinhub.com/prod-api/profile/template-covers/t22-parent-child-happy.webp'),
    ('topic', '今天拍点好看的', 'https://www.chengyinhub.com/prod-api/profile/template-covers/t23-photo-day.webp'),
    ('topic', '夜猫子城市生活', 'https://www.chengyinhub.com/prod-api/profile/template-covers/t24-night-owl-city.webp'),

    -- 2026-08-25 补:上海单人自由探索 5 主题原用 Wikimedia 实拍照,与其余 51 张自绘
    -- 场景 key art 混在同一个货架上撞风格。换成同一套画法后,描述里的「封面摄影：…」
    -- 署名也必须撤销 —— 下面单独一段清理,留着就是假署名。
    ('topic', '风味巡游', 'https://www.chengyinhub.com/prod-api/profile/template-covers/t25-shanghai-flavor-tour.webp'),
    ('topic', '不惑之年', 'https://www.chengyinhub.com/prod-api/profile/template-covers/t26-shanghai-midlife-day.webp'),
    ('topic', '预制人生', 'https://www.chengyinhub.com/prod-api/profile/template-covers/t27-shanghai-prebuilt-life.webp'),
    ('topic', '高温末日逃生', 'https://www.chengyinhub.com/prod-api/profile/template-covers/t28-shanghai-heat-escape.webp'),
    ('topic', '上海像一部旧电影', 'https://www.chengyinhub.com/prod-api/profile/template-covers/t29-shanghai-old-movie.webp');

CREATE TEMPORARY TABLE template_cover_guard (
    id INT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;
INSERT INTO template_cover_guard (id) VALUES (1);

-- 任一标题缺失、重名或串到非官方数据，整批通过 duplicate-key fail closed。
INSERT INTO template_cover_guard (id)
SELECT 1
WHERE (SELECT COUNT(*) FROM template_cover_map WHERE kind = 'library') <> 27;

INSERT INTO template_cover_guard (id)
SELECT 1
WHERE (SELECT COUNT(*) FROM template_cover_map WHERE kind = 'topic') <> 29;

INSERT INTO template_cover_guard (id)
SELECT 1
WHERE EXISTS (
        SELECT m.title
          FROM template_cover_map m
          LEFT JOIN cms_template_library l
            ON m.kind = 'library' AND l.title = m.title
           AND l.member_id = 100000 AND l.del_flag = 0
         WHERE m.kind = 'library'
         GROUP BY m.title
        HAVING COUNT(l.id) <> 1
   );

INSERT INTO template_cover_guard (id)
SELECT 1
WHERE EXISTS (
        SELECT m.title
          FROM template_cover_map m
          LEFT JOIN cms_topic t
            ON m.kind = 'topic' AND t.name = m.title
           AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
         WHERE m.kind = 'topic'
         GROUP BY m.title
        HAVING COUNT(t.id) <> 1
   );

UPDATE cms_template_library l
INNER JOIN template_cover_map m ON m.title = l.title
SET l.img_url = m.img_url,
    l.update_by = 'template_cover_images_20260822',
    l.update_time = NOW()
WHERE m.kind = 'library'
  AND l.member_id = 100000
  AND l.del_flag = 0;

UPDATE cms_topic t
INNER JOIN template_cover_map m ON m.title = t.name
SET t.img_url = m.img_url,
    t.update_by = 'template_cover_images_20260822',
    t.update_time = NOW()
WHERE m.kind = 'topic'
  AND t.member_id = 100000
  AND t.is_template = 1
  AND t.del_flag = 0;

-- 写后从业务表自身回读；少一条或 URL 漂移都回滚。
INSERT INTO template_cover_guard (id)
SELECT 1
WHERE EXISTS (
        SELECT 1
          FROM template_cover_map m
          LEFT JOIN cms_template_library l
            ON m.kind = 'library' AND l.title = m.title
           AND l.member_id = 100000 AND l.del_flag = 0
         WHERE m.kind = 'library' AND COALESCE(l.img_url, '') <> m.img_url
   );

INSERT INTO template_cover_guard (id)
SELECT 1
WHERE EXISTS (
        SELECT 1
          FROM template_cover_map m
          LEFT JOIN cms_topic t
            ON m.kind = 'topic' AND t.name = m.title
           AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
         WHERE m.kind = 'topic' AND COALESCE(t.img_url, '') <> m.img_url
   );

-- 封面换成自绘场景后，描述末尾的「封面摄影：…」是假署名，随本批一起撤销。
-- ⚠️ JOIN 的 ON 只能是「列 = 列」:ci/run_migration.py 的 --transaction 白名单在连库前就按这个
-- 形状判,把 m.kind 塞进 ON 会被整条拒掉(2026-08-25 CI 实测)。kind 一律放 WHERE,与本文件
-- 上面两条 UPDATE 同形。
UPDATE cms_topic t
INNER JOIN template_cover_map m ON m.title = t.name
SET t.description = TRIM(SUBSTRING_INDEX(t.description, '封面摄影：', 1)),
    t.update_by = 'template_cover_images_20260822',
    t.update_time = NOW()
WHERE m.kind = 'topic'
  AND t.member_id = 100000
  AND t.is_template = 1
  AND t.del_flag = 0
  AND t.description LIKE '%封面摄影：%';

INSERT INTO template_cover_guard (id)
SELECT 1
WHERE EXISTS (
        SELECT 1
          FROM cms_topic t
         INNER JOIN template_cover_map m ON m.kind = 'topic' AND m.title = t.name
         WHERE t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
           AND t.description LIKE '%封面摄影%'
   );

-- 回滚（仅在仍指向本批 URL 时清空，避免覆盖后续人工换图）：
-- UPDATE cms_template_library l JOIN template_cover_map m ON m.kind='library' AND m.title=l.title
--    SET l.img_url=NULL WHERE l.member_id=100000 AND l.del_flag=0 AND l.img_url=m.img_url;
-- UPDATE cms_topic t JOIN template_cover_map m ON m.kind='topic' AND m.title=t.name
--    SET t.img_url=NULL WHERE t.member_id=100000 AND t.is_template=1 AND t.del_flag=0 AND t.img_url=m.img_url;
