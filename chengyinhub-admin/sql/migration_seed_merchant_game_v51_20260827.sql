-- ============================================================================
-- 商家互动游戏 · v5.1 新玩法首发种子 7 款
--
-- 背景:#853 做了 Figma 组件库 v5.1 的九个玩法壳,#857 补上服务端并接进既有玩法引擎
--   (advanced_config_json 七个新机制段 + /api/play/advanced/action)。
--   代码链路已通并已部署上线(commit 2ac83806,已观测 jar 自报 commit 与进程重启),
--   但**存量模板一个都没配这七段**,玩法不会凭空出现 —— 本文件把它们上货架。
--
-- 落点是 cms_template_library 公共节点玩法库(和 A8/B27 同一个货架),
-- 商家在模板市场采用后复制进自己的 cms_member_template,play 读的是那份正本。
--   ⇒ 本文件**不替任何商家决定用哪个玩法**,只把七款放上货架。
--
-- 品类按 sys_category.category_name 语义解析 不写死环境数字 id(沿用 A8 做法)
--
-- 七款各用一个 v5.1 新机制,便于商家按业态挑:
--   闭眼味觉师   blindTaste   茶饮/咖啡/酒吧    答对才给分 可重答
--   沉默点单     silentOrder  便利店/餐饮        核销仍走既有的商家扫玩家码
--   便利店调酒师 diyName      便利店/饮品        作品名过微信内容安全再落库
--   治愈音乐角   musicCorner  咖啡馆/书店        **不判定通关**(2026-08-26 拍板)
--   午夜电台     timeWindow   酒吧/深夜食堂      开局按服务端时钟判 跨夜合法
--   低碳行动     steps        运动/户外          只认服务端解密的微信运动步数
--   今日城市签   dailySign    通用               一天一条 签文池不下发
--
-- validation_method 一律 0(到点即完成):玩法整个跑在 advanced 段里,
--   不占用 vm 的四型交互。客户端 onAdvancedReady 有 vm===0 的专门分支,
--   高级流程跑完回详情卡走到达路径(见 migration_20260825 的说明)。
--
-- 前置依赖
--   migration_20260822_advanced_game_runtime.sql
--   migration_seed_merchant_game_a8_20260823.sql(货架与品类语义)
--   代码:#857(七个机制段的校验、投影与动作),否则采用后配置存不进去
--
-- 本文件只随代码交付 不自动执行
-- 生产执行必须走 chengyin-db-migrate 并做落库后业务回读
-- runner: requires=transaction
-- ============================================================================

CREATE TEMPORARY TABLE seed_merchant_game_v51_guard (
    id INT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;
INSERT INTO seed_merchant_game_v51_guard (id) VALUES (1);

CREATE TEMPORARY TABLE seed_merchant_game_v51_config (
    slug VARCHAR(64) NOT NULL PRIMARY KEY,
    advanced_config JSON NOT NULL
) ENGINE=InnoDB;

CREATE TEMPORARY TABLE seed_merchant_game_v51_category (
    slug VARCHAR(64) NOT NULL PRIMARY KEY,
    title VARCHAR(64) NOT NULL UNIQUE,
    category_name VARCHAR(64) NOT NULL,
    category_id INT NULL,
    activity_categoryids VARCHAR(32) NULL
) ENGINE=InnoDB;
INSERT INTO seed_merchant_game_v51_category (slug, title, category_name)
VALUES
    ('blind_taste_master', '闭眼味觉师', '美食烹饪'),
    ('silent_order_witness', '沉默点单', '美食烹饪'),
    ('diy_drink_naming', '便利店调酒师', '美食烹饪'),
    ('healing_music_corner', '治愈音乐角', '生活美学'),
    ('midnight_radio_window', '午夜电台', '娱乐达人'),
    ('low_carbon_walk', '低碳行动', '运动健身'),
    ('daily_city_sign', '今日城市签', '生活美学');

-- 每个语义品类必须在当前环境唯一存在 否则整笔回滚(guard 主键冲突 fail closed)
INSERT INTO seed_merchant_game_v51_guard (id)
SELECT 1
 WHERE EXISTS (
       SELECT r.category_name
         FROM seed_merchant_game_v51_category r
         LEFT JOIN sys_category c
           ON c.category_name = r.category_name
          AND c.type = 1
          AND c.status = 1
          AND c.del_flag = 0
        GROUP BY r.category_name
       HAVING COUNT(DISTINCT c.id) <> 1
 );

UPDATE seed_merchant_game_v51_category r
   SET r.category_id = (
           SELECT MIN(c.id)
             FROM sys_category c
            WHERE c.category_name = r.category_name
              AND c.type = 1 AND c.status = 1 AND c.del_flag = 0),
       r.activity_categoryids = CAST((
           SELECT MIN(c.id)
             FROM sys_category c
            WHERE c.category_name = r.category_name
              AND c.type = 1 AND c.status = 1 AND c.del_flag = 0) AS CHAR)
 WHERE r.category_id IS NULL
   AND r.activity_categoryids IS NULL;

INSERT INTO seed_merchant_game_v51_guard (id)
SELECT 1
 WHERE EXISTS (
       SELECT slug
         FROM seed_merchant_game_v51_category
        GROUP BY slug
       HAVING COALESCE(MIN(category_id), 0) = 0
          OR COALESCE(MIN(activity_categoryids), '') = ''
 );

-- 同名存量只能是本迁移已经写入的唯一一行;发现业务同名或重复数据时 fail closed
INSERT INTO seed_merchant_game_v51_guard (id)
SELECT 1
 WHERE EXISTS (
           SELECT 1
             FROM cms_template_library
            WHERE member_id = 100000
              AND del_flag = 0
              AND title IN ('闭眼味觉师','沉默点单','便利店调酒师','治愈音乐角',
                            '午夜电台','低碳行动','今日城市签')
            GROUP BY title
           HAVING COUNT(*) <> 1
               OR COALESCE(MIN(create_by), '') <> 'seed_merchant_game_v51_20260827'
       );

-- ============================================================================
-- TEMPLATE blind_taste_master|闭眼味觉师|0|茶饮,咖啡馆,酒吧|blindTaste
-- ★ answerKey 存在配置里但从不下发(服务端两处投影都剥掉),所以模板自带的答案
--   不会泄露给玩家;商家采用后必须把选项与答案改成本店真实商品。
-- ============================================================================
INSERT INTO seed_merchant_game_v51_config (slug, advanced_config)
VALUES ('blind_taste_master', '{"schemaVersion":1,"blindTaste":{"enabled":true,"title":"闭上眼 先尝再猜","steps":"① 向店员领盲品小样 ② 闭眼尝 ③ 睁眼作答","hint":"猫向导:别偷看 舌头比眼睛诚实","xp":15,"options":[{"key":"A","label":"桂花乌龙"},{"key":"B","label":"茉莉雪芽"},{"key":"C","label":"蜜桃红茶"},{"key":"D","label":"陈皮白茶"}],"answerKey":"B"}}');
INSERT INTO cms_template_library (member_id, member_template_id, title, description, players,
        usage_location, required_materials, duration, difficulty, rule_instructions,
        validation_method, feedback_text, story_text, preference_json, advanced_config_json,
        status, publish_status, is_sync, use_num, del_flag, create_by, create_time)
SELECT 100000, NULL, '闭眼味觉师', '闭眼尝一口再从四个选项里猜出它是什么 适合茶饮 咖啡馆和酒吧', '单人/双人',
       '茶饮店/咖啡馆/酒吧', '一份可盲品的小样 商家确认答案', 5, '入门',
       '1. 向店员领取盲品小样。2. 闭眼尝。3. 睁眼在四个选项中作答。4. 答错可以再答 答对才给分。商家采用后必须把四个选项和正确答案改成本店真实商品 答案由服务端保管 不会下发给玩家。',
       0,
       '舌头比眼睛诚实 这一口你记住了。',
       '有些味道要闭上眼才认得出来。睁着眼时 我们总在看价格 看包装 看别人点了什么。',
       NULL, (SELECT advanced_config FROM seed_merchant_game_v51_config WHERE slug = 'blind_taste_master'),
       1, 1, 0, 0, 0, 'seed_merchant_game_v51_20260827', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_template_library
                    WHERE member_id = 100000 AND title = '闭眼味觉师' AND del_flag = 0);

-- ============================================================================
-- TEMPLATE silent_order_witness|沉默点单|0|便利店,餐饮,咖啡馆|silentOrder
-- 核销仍走既有的「商家扫玩家码」链路,本机制只管展示规则与表演计时。
-- ============================================================================
INSERT INTO seed_merchant_game_v51_config (slug, advanced_config)
VALUES ('silent_order_witness', '{"schemaVersion":1,"silentOrder":{"enabled":true,"title":"不说一个字 让店员猜中你要什么","rule":"比划 眼神 演技 随便你 开口即失败。店员猜中后请 TA 扫你的见证码。","limitSeconds":600}}');
INSERT INTO cms_template_library (member_id, member_template_id, title, description, players,
        usage_location, required_materials, duration, difficulty, rule_instructions,
        validation_method, feedback_text, story_text, preference_json, advanced_config_json,
        status, publish_status, is_sync, use_num, del_flag, create_by, create_time)
SELECT 100000, NULL, '沉默点单', '全程不说话 靠比划让店员猜中你要的东西 适合便利店和餐饮门店', '单人',
       '便利店/餐饮门店/咖啡馆', '需要店员配合 建议在非高峰时段进行', 10, '进阶',
       '1. 全程不开口 用比划 眼神或演技表达你要的东西。2. 开口即失败 由玩家自己认输。3. 店员猜中后扫玩家出示的见证码完成核销。商家采用后请先和店员说明规则 高峰时段不建议开启。',
       0,
       '一个字都没说 你们还是懂了。',
       '语言是后来才有的东西。在那之前 人靠手 靠眼睛 靠猜 也照样把日子过明白了。',
       NULL, (SELECT advanced_config FROM seed_merchant_game_v51_config WHERE slug = 'silent_order_witness'),
       1, 1, 0, 0, 0, 'seed_merchant_game_v51_20260827', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_template_library
                    WHERE member_id = 100000 AND title = '沉默点单' AND del_flag = 0);

-- ============================================================================
-- TEMPLATE diy_drink_naming|便利店调酒师|0|便利店,酒吧,饮品|diyName
-- 玩家起的名字会公开展示,服务端提交前过微信内容安全检测。
-- ============================================================================
INSERT INTO seed_merchant_game_v51_config (slug, advanced_config)
VALUES ('diy_drink_naming', '{"schemaVersion":1,"diyName":{"enabled":true,"title":"拍好了 给它起个名字","maxLength":16,"suggestions":["熬夜特调","便利店之光","后悔药 2.0"]}}');
INSERT INTO cms_template_library (member_id, member_template_id, title, description, players,
        usage_location, required_materials, duration, difficulty, rule_instructions,
        validation_method, feedback_text, story_text, preference_json, advanced_config_json,
        status, publish_status, is_sync, use_num, del_flag, create_by, create_time)
SELECT 100000, NULL, '便利店调酒师', '自己配一杯再给它起个名字 适合便利店 酒吧和饮品门店', '单人/双人',
       '便利店/酒吧/饮品门店', '可自由搭配的饮品或食材 商家允许拍照', 10, '入门',
       '1. 用店里现有的东西配出你的作品。2. 拍一张照片。3. 给它起一个名字并提交。名字会公开展示 提交前会做内容安全检测。商家采用后可把备选名改成本店风格 并决定是否把作品上今日推荐。',
       0,
       '这个名字归你了 下一个来的人会看见它。',
       '所有被记住的东西都先有了名字。你给它起名的那一刻 它才算真的存在过。',
       NULL, (SELECT advanced_config FROM seed_merchant_game_v51_config WHERE slug = 'diy_drink_naming'),
       1, 1, 0, 0, 0, 'seed_merchant_game_v51_20260827', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_template_library
                    WHERE member_id = 100000 AND title = '便利店调酒师' AND del_flag = 0);

-- ============================================================================
-- TEMPLATE healing_music_corner|治愈音乐角|0|咖啡馆,书店,唱片店|musicCorner
-- ★ 明确**不判定通关**(2026-08-26 用户拍板):听完与否无法在客户端证明,
--   与其做一个挡不住的判定 不如让它就是氛围件。所以文案不承诺任何奖励。
--   audioUrl 留空由商家采用后自行填(必须 https 或站内路径)。
-- ============================================================================
INSERT INTO seed_merchant_game_v51_config (slug, advanced_config)
VALUES ('healing_music_corner', '{"schemaVersion":1,"musicCorner":{"enabled":true,"title":"坐下来 这一站只用耳朵","trackName":"店主的歌单","durationSeconds":206}}');
INSERT INTO cms_template_library (member_id, member_template_id, title, description, players,
        usage_location, required_materials, duration, difficulty, rule_instructions,
        validation_method, feedback_text, story_text, preference_json, advanced_config_json,
        status, publish_status, is_sync, use_num, del_flag, create_by, create_time)
SELECT 100000, NULL, '治愈音乐角', '坐下来听完店主选的一首歌 什么都不用做 适合咖啡馆 书店和唱片店', '单人',
       '咖啡馆/书店/唱片店', '店主挑选的一首曲目 一个可以坐下的位置', 5, '入门',
       '1. 找个位置坐下。2. 播放店主的歌单。3. 听完。本玩法不判定通关 也不给奖励分 它只是让你在这一站停一会儿。商家采用后请填入曲目地址 并确认已获得播放授权。',
       0,
       '这一首听完了 城市的音量好像小了一点。',
       '一家店的性格 有时候不在菜单上 在它循环播放的那几首歌里。',
       NULL, (SELECT advanced_config FROM seed_merchant_game_v51_config WHERE slug = 'healing_music_corner'),
       1, 1, 0, 0, 0, 'seed_merchant_game_v51_20260827', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_template_library
                    WHERE member_id = 100000 AND title = '治愈音乐角' AND del_flag = 0);

-- ============================================================================
-- TEMPLATE midnight_radio_window|午夜电台|0|酒吧,深夜食堂,livehouse|timeWindow
-- 开放判定按**服务端时钟**,跨夜区间(23:00-01:00)合法。
-- 客户端那个倒计时到 0 只是回来问一次 不会自己开门。
-- ============================================================================
INSERT INTO seed_merchant_game_v51_config (slug, advanced_config)
VALUES ('midnight_radio_window', '{"schemaVersion":1,"timeWindow":{"enabled":true,"eyebrow":"午夜电台 · 时段限定","title":"还没到开播时间","openFrom":"23:00","openTo":"01:00"}}');
INSERT INTO cms_template_library (member_id, member_template_id, title, description, players,
        usage_location, required_materials, duration, difficulty, rule_instructions,
        validation_method, feedback_text, story_text, preference_json, advanced_config_json,
        status, publish_status, is_sync, use_num, del_flag, create_by, create_time)
SELECT 100000, NULL, '午夜电台', '只在深夜某个时段开放的玩法 白天来会被挡在门外 适合酒吧和深夜食堂', '单人/不限',
       '酒吧/深夜食堂/livehouse', '门店在该时段真的营业', 15, '入门',
       '1. 只有在开放时段内才能开局 时段由服务端判定。2. 不在时段内会看到倒计时和订阅提醒。3. 时段内进入即可开始。商家采用后必须把开放时段改成本店真实营业时间 跨夜时段(如 23:00 到 01:00)是允许的。',
       0,
       '你赶上了 这个时段只有此刻的人看得见。',
       '有些店只在天黑之后才露出真面目。白天路过一百次 也不会知道它晚上是什么样子。',
       NULL, (SELECT advanced_config FROM seed_merchant_game_v51_config WHERE slug = 'midnight_radio_window'),
       1, 1, 0, 0, 0, 'seed_merchant_game_v51_20260827', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_template_library
                    WHERE member_id = 100000 AND title = '午夜电台' AND del_flag = 0);

-- ============================================================================
-- TEMPLATE low_carbon_walk|低碳行动|0|运动健身,户外,城市漫游|steps
-- ★ 只认服务端解密微信运动拿到的步数(WeRunDecryptor + watermark.appid 校验),
--   客户端自报的步数一律不采信;达标只给一次分。
-- ============================================================================
INSERT INTO seed_merchant_game_v51_config (slug, advanced_config)
VALUES ('low_carbon_walk', '{"schemaVersion":1,"steps":{"enabled":true,"eyebrow":"低碳行动 · 今日步数","goal":6000,"xp":20}}');
INSERT INTO cms_template_library (member_id, member_template_id, title, description, players,
        usage_location, required_materials, duration, difficulty, rule_instructions,
        validation_method, feedback_text, story_text, preference_json, advanced_config_json,
        status, publish_status, is_sync, use_num, del_flag, create_by, create_time)
SELECT 100000, NULL, '低碳行动', '用今天真实走过的步数换一枚爪印 适合运动场馆和城市漫游线路', '单人/不限',
       '运动场馆/户外/城市漫游线路', '需要玩家授权微信运动', 30, '入门',
       '1. 授权微信运动。2. 刷新步数 步数由服务端解密微信运动数据得到。3. 达到目标步数即落章。客户端自己填的步数不作数。商家采用后可按线路长度调整目标步数 建议 3000 到 8000 之间。',
       0,
       '今天这些步都算数了。',
       '城市的尺度是用脚量出来的。地铁让它变小 走路让它重新变大。',
       NULL, (SELECT advanced_config FROM seed_merchant_game_v51_config WHERE slug = 'low_carbon_walk'),
       1, 1, 0, 0, 0, 'seed_merchant_game_v51_20260827', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_template_library
                    WHERE member_id = 100000 AND title = '低碳行动' AND del_flag = 0);

-- ============================================================================
-- TEMPLATE daily_city_sign|今日城市签|0|通用|dailySign
-- ★ 签文池从不下发给客户端;一天一条 由服务端按日期确定性地选。
--   同一天重复领是幂等的(返回已领那条) 不是报错。
-- ============================================================================
INSERT INTO seed_merchant_game_v51_config (slug, advanced_config)
VALUES ('daily_city_sign', '{"schemaVersion":1,"dailySign":{"enabled":true,"signer":"猫向导","sealText":"城瘾","poems":[["这座城市今晚会把","一条没走过的巷子","悄悄留给你"],["今天适合走慢一点","该遇见的东西","不会因为你慢而错过"],["有些店只等一种人","你今天正好是那种人"],["把手机放回口袋","这条街值得用眼睛看一遍"],["你路过这里很多次","今天是第一次真的到达"],["城市不欠你什么","但它今天想还你一点东西"]]}}');
INSERT INTO cms_template_library (member_id, member_template_id, title, description, players,
        usage_location, required_materials, duration, difficulty, rule_instructions,
        validation_method, feedback_text, story_text, preference_json, advanced_config_json,
        status, publish_status, is_sync, use_num, del_flag, create_by, create_time)
SELECT 100000, NULL, '今日城市签', '一天领一张城市签 由服务端当天选定 适合任何业态作为开场仪式', '单人/不限',
       '任何门店/城市漫游线路', '无', 2, '入门',
       '1. 到达后领取今日城市签。2. 一天只有一条 由服务端按日期选定。3. 同一天重复领会拿到同一条 不会换。商家采用后可把签文改成本店口吻 每条 1 至 4 行 每行不超过 24 字。',
       0,
       '收下了 今天就照这句走。',
       '古时候人们出门前要问一卦。现在我们不问了 但还是想有人在门口说一句 今天会好的。',
       NULL, (SELECT advanced_config FROM seed_merchant_game_v51_config WHERE slug = 'daily_city_sign'),
       1, 1, 0, 0, 0, 'seed_merchant_game_v51_20260827', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_template_library
                    WHERE member_id = 100000 AND title = '今日城市签' AND del_flag = 0);

-- ============================================================================
-- 落库后的回读:**不写在本文件里**,有两个原因
--   ① --transaction 白名单只收 INSERT/UPDATE 与临时表 DDL,SELECT 断言会被拒(已 dry-run 验证);
--   ② 更重要的是,同一个事务里 SELECT 自己刚写的值属于「问自己」,不是观测。
--      按总纲「回执 ≠ 观测」,回读必须换一条**新的只读连接**去问业务表。
--
-- 执行者请在 COMMIT 之后单独跑(七款都在货架且都带配置才算成功):
--   SELECT COUNT(*) FROM cms_template_library
--    WHERE member_id = 100000 AND del_flag = 0
--      AND create_by = 'seed_merchant_game_v51_20260827'
--      AND advanced_config_json IS NOT NULL;   -- 期望 7
-- ============================================================================
