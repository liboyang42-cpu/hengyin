-- runner: requires=transaction
-- ============================================================================
-- 首页 8 场「自己玩」活动的节点玩法（每场 1 个 cms_member_template）
--
-- 依赖 migration_seed_home_solo_activities_20260826.sql（那批活动落库后才有 id 可挂）。
--
-- 为什么要这一批：cms_activity.template_id 为空时，活动详情页「玩法节点」渲成「0 个」，
-- 且「节点玩法」整段被 wx:if 吃掉 —— 玩家看不到「到底要做什么才算完成」。
--
-- 边界：
--   · cms_activity.template_id 只有两个读者（ApiActivityController.activityInfo 展示、
--     后台 CmsActivityController 编辑回显），挂上去不改任何玩法运行时行为；
--     真正驱动玩法的是 cms_topic_node.template_id，与本批无关；
--   · 全部 validation_method=2（拍照打卡）+ photo_review=0，玩家自证，不需要商家核销；
--   · status=1 / publish_status=1 / draft_status=1 / del_flag=0 —— 这四个值是
--     /api/template/myinfo 不报「审核中 / 已下架 / 已删除」的充要条件（节点卡可点进详情）；
--   · 答案与提示类字段一概不写，非 owner 本来就会被 TemplateSecrets.strip 剥掉。
--
-- 幂等键：cms_member_template(member_id=100000, title)；活动侧回填是等值覆盖，重跑无副作用。
-- ⚠️ 幂等键的局限（一次性种子可接受，但别当它是约束）：title 与 name 背后都没有唯一索引。
--    · 若 member_id=100000 名下已存在同 title 的模板，下方 `ON t.title = s.title` 会任选一条；
--    · 若这 8 条被软删（del_flag≠0）后重跑，NOT EXISTS 不再命中，会再插一份。
--    真要做成约束得建唯一索引，那是 DDL，不在本批范围内。
-- 纯 DML，不创建/修改表结构。
-- ============================================================================

DROP TEMPORARY TABLE IF EXISTS seed_home_solo_nodes;
CREATE TEMPORARY TABLE seed_home_solo_nodes (
  activity_name VARCHAR(80) NOT NULL PRIMARY KEY,
  title VARCHAR(80) NOT NULL,
  description VARCHAR(500) NOT NULL,
  img VARCHAR(255) NOT NULL,
  players VARCHAR(20) NOT NULL,
  usage_location VARCHAR(100) NOT NULL,
  required_materials VARCHAR(100) NOT NULL,
  duration INT NOT NULL,
  difficulty VARCHAR(20) NOT NULL,
  rule_instructions VARCHAR(800) NOT NULL,
  photo_require_desc VARCHAR(255) NOT NULL,
  category_id BIGINT NOT NULL,
  template_id BIGINT NULL
) ENGINE=InnoDB;

INSERT INTO seed_home_solo_nodes
       (activity_name, title, description, img, players, usage_location,
        required_materials, duration, difficulty, rule_instructions,
        photo_require_desc, category_id)
SELECT '苏州河，一个人走完九座桥', '九桥打卡：把桥名牌拍下来',
       '每走到一座桥，拍下桥头那块桥名牌。九张凑齐就算完成——顺序不限，分几天走完也算。',
       'https://www.chengyinhub.com/prod-api/profile/template-covers/t21-solo-departure.webp',
       '1人', '苏州河沿岸公共步道', '手机', 150, '简单',
       '1. 从外白渡桥起步，沿苏州河向西。2. 每到一座桥，拍下桥头的桥名牌。3. 走累了随时收工，下次接着走。4. 九张集齐视为完成。全程走人行道，不翻护栏，不在桥面车行道一侧停留拍摄。',
       '桥名牌清晰可读，九座桥各一张。', 13
  UNION ALL SELECT '一个人的美术馆下午', '一件作品，看满十分钟',
       '挑一件真正让你停下来的作品，坐着看满十分钟，再拍下它的展签，用一句话写下它让你想到什么。',
       'https://www.chengyinhub.com/prod-api/profile/template-covers/t26-shanghai-midlife-day.webp',
       '1人', '美术馆展厅', '手机', 60, '简单',
       '1. 进馆后先不看导览，随便走到哪算哪。2. 选一件让你自己停下来的作品。3. 坐下或站定看满十分钟，中途不看手机。4. 拍下展签，写一句话。展厅拍摄请遵守场馆规定，禁拍区域不拍。',
       '作品展签清晰可读，一张即可。', 16
  UNION ALL SELECT '独立书店巡游：五家店，五本没听过的书', '五本你完全没听过的书',
       '每家店找一本你完全没听说过的书，读完前三页再决定要不要带走。不买也算完成。',
       'https://www.chengyinhub.com/prod-api/profile/topic-covers/prebuilt-life.webp',
       '1人', '独立书店店内', '手机', 180, '简单',
       '1. 每家店只做一件事：找一本你没听过的书。2. 站着读完前三页。3. 拍下封面，放回原位或结账带走，都算完成。4. 五家店集满五张。请遵守各店的拍照与试读规定，不拆封、不长时间占用座位。',
       '书封面清晰可读，五家店各一张。', 25
  UNION ALL SELECT '清晨菜场味觉考：一个人的早市', '三样叫不出名字的时令菜',
       '拍下三样你叫不出名字的时令菜，问清其中一种怎么做，再买一样今天就吃得掉的东西。',
       'https://www.chengyinhub.com/prod-api/profile/template-covers/t25-shanghai-flavor-tour.webp',
       '1人', '菜场摊位前', '手机、零钱或移动支付', 30, '简单',
       '1. 早上七点半到八点半之间到场。2. 找三样你叫不出名字的时令菜，各拍一张。3. 挑其中一样，问摊主怎么做。4. 买一样今天就吃得掉的东西收工。摊主在做生意，问一句就好，别长时间占着摊位。',
       '三样时令菜各一张，能看清菜本身。', 15
  UNION ALL SELECT '夜色外滩·独行摄影任务', '三张照片，只留一张',
       '拍三张——不含人的建筑局部、看不清脸的街景、你自己的影子。选一张留下，其余两张删掉。',
       'https://www.chengyinhub.com/prod-api/profile/template-covers/t24-night-owl-city.webp',
       '1人', '外滩源公共街区', '手机', 60, '中等',
       '1. 天黑后到达，手机就够。2. 拍第一张：完全不含人的建筑局部。3. 拍第二张：有陌生人但看不清脸的街景。4. 拍第三张：你自己的影子。5. 选一张留下，另外两张当场删掉——这一步才是任务本身。不为取景越过围栏或站上台阶边沿。',
       '最终留下的那一张。', 16
  UNION ALL SELECT '梧桐区慢跑五公里：不看配速那一次', '不看配速的五公里',
       '全程不看配速、不发朋友圈。终点回到武康大楼，拍一张路口，记下当时第一个念头。',
       'https://www.chengyinhub.com/prod-api/profile/template-covers/t20-after-work-switch.webp',
       '1人', '武康路、五原路、安福路人行道', '跑鞋、手机', 40, '中等',
       '1. 出发前把配速播报关掉。2. 沿武康路、五原路、安福路跑一圈，约五公里。3. 想停就停，遇到好看的路口可以走过去。4. 回到武康大楼路口拍一张，记下当时第一个念头。人行道行人多，请降速避让、礼让行人。',
       '武康大楼路口一张。', 13
  UNION ALL SELECT '滨江骑行十五公里：一个人的江边', '江边的十分钟',
       '骑到折返点，找一处江边台阶停下来待够十分钟，什么都不做，然后拍一张你面前的江面。',
       'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/route-badge-story.jpg',
       '1人', '徐汇滨江骑行道', '共享单车或自有单车、手机', 90, '中等',
       '1. 从龙美术馆前广场出发，沿骑行道往南骑约七点五公里。2. 找一处江边台阶停下。3. 待够十分钟，不刷手机、不听播客，什么都不做。4. 拍一张你面前的江面，然后原路折返。遵守骑行道限速与单向指引。',
       '折返点面前的江面一张。', 13
  UNION ALL SELECT '老城厢：找出十个没人拍过的细节', '十个不会被拍到的细节',
       '旧门牌号、砖缝里的植物、被漆盖过的招牌、修过三次的排水口……凑满十张就结束。',
       'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/city-sidequest-observation.jpg',
       '1人', '文庙到老西门之间的小马路', '手机', 90, '简单',
       '1. 只走公共街面，不进私人院落。2. 找那些几乎不会被拍到的细节：旧门牌编号、砖缝里的植物、被漆盖过的招牌、修过三次的排水口。3. 找到就拍，凑满十张结束。这一带住着人：请压低音量，不拍摄居民与室内，遇施工围挡绕行。',
       '十个细节各一张。', 18;

-- ======================= 一、8 个节点玩法 =======================

INSERT INTO cms_member_template
       (member_id, original_template_id, category_id, title, description, img_url,
        players, usage_location, required_materials, duration, difficulty,
        rule_instructions, validation_method, photo_require_desc, photo_review,
        activity_categoryids, status, publish_status, draft_status, check_status,
        del_flag, create_by, create_time)
SELECT 100000, 0, s.category_id, s.title, s.description, s.img,
       s.players, s.usage_location, s.required_materials, s.duration, s.difficulty,
       s.rule_instructions, 2, s.photo_require_desc, 0,
       CAST(s.category_id AS CHAR), 1, 1, 1, 1,
       0, 'seed_home_solo_node_templates_20260826', NOW()
  FROM seed_home_solo_nodes s
 WHERE NOT EXISTS (SELECT 1 FROM cms_member_template t
                    WHERE t.member_id = 100000 AND t.title = s.title AND t.del_flag = 0);

-- 纠正首跑盖错的批次标签:2026-08-26 首次执行时这里写成了上一个迁移的名字
-- 'seed_home_solo_activities_20260826',生产 8 行(id 118–125)全被盖成活动批次。
-- 无功能影响(下方回填筛的是 cms_activity.create_by,那个是对的),但按批次捞节点模板
-- 会把活动和模板混在一起。cms_member_template 里除这 8 行外没有任何行带该标签
-- —— 活动种子那支从未写过本表 —— 所以下面这条按标签纠正是精确的。
-- 幂等:纠正过就匹配不到,重跑 0 行。
UPDATE cms_member_template t
   SET t.create_by = 'seed_home_solo_node_templates_20260826',
       t.update_by = 'seed_home_solo_node_templates_20260826',
       t.update_time = NOW()
 WHERE t.member_id = 100000
   AND t.del_flag = 0
   AND t.create_by = 'seed_home_solo_activities_20260826';

-- ======================= 二、回填 cms_activity.template_id =======================
-- 分两步:白名单只允许「单条 INNER JOIN + 单个等值 ON」,一条语句跨不过
-- 活动名 → 节点标题 → 模板 id 这两跳。先把 id 收进临时表,再等值回填。

UPDATE seed_home_solo_nodes s
  INNER JOIN cms_member_template t ON t.title = s.title
   SET s.template_id = t.id
 WHERE t.member_id = 100000 AND t.del_flag = 0;

UPDATE cms_activity a
  INNER JOIN seed_home_solo_nodes s ON s.activity_name = a.name
   SET a.template_id = s.template_id
 WHERE a.member_id = 100000
   AND a.del_flag = 0
   AND a.create_by = 'seed_home_solo_activities_20260826'
   AND s.template_id IS NOT NULL;

DROP TEMPORARY TABLE IF EXISTS seed_home_solo_nodes;
