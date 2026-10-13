-- ============================================================================
-- 节点玩法模板库种子:12 个官方玩法模板(M01-M06 商家 / C01-C06 俱乐部)
--
-- 内容真源(逐字段可回溯,不是自编示例):
--   ~/Documents/城瘾/文档整理_20260711/05-参考资料/城瘾游戏玩法模板配置库_商家与俱乐部.md
--   (2026-07-07 定稿;§4 商家模板 M01-M06 · §5 俱乐部模板 C01-C06 的「推荐配置/完成方式/奖励」逐项转录)
--
-- 落点 = cms_template_library(公共模板库货架,App Store 风格 /api/template/list · homeData)。
-- 与 migration_seed_playpack_templates_20260820.sql(主题级副本模板)是不同层:
--   本文件是「节点玩法插件」,商家/俱乐部经模板市场「采用」(POST /api/template/use,同 PR 新增)
--   复制成私有 cms_member_template 后挂到自己的节点上。
--
-- 字段口径:
--   member_id = 100000(平台官方) · member_template_id = NULL(非会员模板同步,官方直灌)
--   status=1(审核通过) · publish_status=1(上架) · is_sync=0 · del_flag=0
--   (库表无 medal_*/xp_value/check_status 列 —— 勋章与探索值由采用者在自己的模板/节点上配)
--   use_num=0(市场热度从零攒) · coupon_id 一律 NULL(券归属是采用者自己的,采用时也会强制清空)
--   category_id / activity_categoryids 留空 —— 生产品类 id 环境相关,落库后由运营在后台归类;
--   分类缺失只影响 groupByCategory 分组展示,不影响 /list 与「采用」。
--   story_json 不灌 —— 文档的「剧情故事」三段合并进 story_text(storyJson 结构由编辑器生成,不手编)。
--
-- ⚠️ 前置依赖:migration_level_editor_fields.sql(公共库 story/hint/photo 字段)与
--   前置依赖:migration_level_editor_story_photo_review.sql(photo_review 字段)必须先落库。
-- ⚠️ 本文件只准备,不执行。生产库写操作由总控在用户点头后执行(chengyin-db-migrate)。
-- runner: requires=transaction
-- ★ 幂等:INSERT ... SELECT ... WHERE NOT EXISTS,幂等键 = (member_id=100000, title)。
--
-- ======================= 事务内写前 fail-closed guard =======================
CREATE TEMPORARY TABLE seed_library_12_guard (
    id INT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;
INSERT INTO seed_library_12_guard (id) VALUES (1);

-- 逐模板真源：validation_method 必须绑定到具名模板，不能靠总分布掩盖互换。
CREATE TEMPORARY TABLE seed_library_12_expected (
    title VARCHAR(64) NOT NULL PRIMARY KEY,
    validation_method INT NOT NULL
) ENGINE=InnoDB;
INSERT INTO seed_library_12_expected (title, validation_method)
VALUES
    ('今晚的暗号', 1),
    ('老板的秘密题', 3),
    ('拍下这一刻', 2),
    ('收集这枚风味印章', 4),
    ('新品盲测局', 3),
    ('今日搭配任务', 2),
    ('集合点亮', 4),
    ('小队竞速点亮', 5),
    ('街角谜题', 1),
    ('城市取景任务', 2),
    ('城市接力棒', 1),
    ('今日角色任务', 2);

-- 同一幂等键只允许不存在，或恰好一条且确由本迁移创建；拒绝借用同名业务数据。
INSERT INTO seed_library_12_guard (id)
SELECT 1
 WHERE EXISTS (
           SELECT 1
             FROM cms_template_library
            WHERE member_id = 100000 AND del_flag = 0
              AND title IN ('今晚的暗号','老板的秘密题','拍下这一刻','收集这枚风味印章','新品盲测局',
                            '今日搭配任务','集合点亮','小队竞速点亮','街角谜题','城市取景任务',
                            '城市接力棒','今日角色任务')
            GROUP BY title
           HAVING COUNT(*) <> 1 OR MIN(create_by) <> 'seed_library_12packs_20260820'
       );

-- ======================= 只读预检 =======================
-- 1) 现状(未灌时应 0 行):
--   SELECT id, title FROM cms_template_library
--    WHERE member_id = 100000 AND del_flag = 0
--      AND title IN ('今晚的暗号','老板的秘密题','拍下这一刻','收集这枚风味印章','新品盲测局',
--                    '今日搭配任务','集合点亮','小队竞速点亮','街角谜题','城市取景任务',
--                    '城市接力棒','今日角色任务');
-- ============================================================================


-- M01 暗号核销 —— 文字问答(方案A)
INSERT INTO cms_template_library (member_id, member_template_id, title, description, players,
        usage_location, required_materials, duration, difficulty, rule_instructions,
        validation_method, question_name, question_answer, hint1, hint2, answer_reveal,
        feedback_text, story_text,
        status, publish_status, is_sync, use_num, del_flag, create_by, create_time)
SELECT 100000, NULL, '今晚的暗号', '说出暗号,解锁隐藏奖励', '单人/双人/不限',
       '商圈街区/室内场馆', '手机;店员需知道今日暗号', 5, '入门',
       '1. 到达指定门店。2. 向店员说出今日暗号。3. 输入暗号或扫码完成任务,领取隐藏奖励。(运营提示:暗号每天可换一次;商家必须提前知道暗号和奖励,否则现场体验会断)',
       1, '向店员说出今日暗号,并在这里输入暗号', '城市醒了',
       '暗号藏在本关剧情里', '留意「城市」和「醒来」这两个词', '今日暗号:城市醒了',
       '暗号正确。你已被这家店记住,隐藏奖励已解锁。',
       '你推开这家店的门,灯光像一枚被藏起的坐标。店员看了你一眼,似乎在等一句只有玩家才知道的话。暗号说出口,隐藏菜单被点亮。',
       1, 1, 0, 0, 0, 'seed_library_12packs_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_template_library
                    WHERE member_id = 100000 AND title = '今晚的暗号' AND del_flag = 0);

-- M02 老板出题 —— 选项问答
INSERT INTO cms_template_library (member_id, member_template_id, title, description, players,
        usage_location, required_materials, duration, difficulty, rule_instructions,
        validation_method, question_name, question_a, question_b, question_c, question_d,
        correct_answer, hint1, hint2, answer_reveal,
        feedback_text, story_text,
        status, publish_status, is_sync, use_num, del_flag, create_by, create_time)
SELECT 100000, NULL, '老板的秘密题', '答对店铺问题,点亮故事徽章', '单人/不限',
       '商圈街区/古镇老街/室内场馆', '店铺故事资料;现场可观察的答案线索', 5, '普通',
       '1. 阅读店铺故事或观察现场线索。2. 回答老板留下的问题。3. 答对后点亮店铺故事徽章。(运营提示:题目必须能从现场找到答案,不要出只有老板知道、玩家猜不到的问题)',
       3, '这家店最早靠什么被街坊记住?', '一杯招牌饮品', '一面老墙', '一只店猫', '一张旧唱片',
       'A', '看看菜单上被标成「最初」的那一项', '它通常被放在菜单第一页',
       '答案是招牌饮品。它是这家店最早被记住的原因。',
       '你读懂了这家店的第一段故事。',
       '每家店都有一个最初被记住的理由。这里的答案不在屏幕里,而在菜单、墙面和老板的日常里。你找到了这家店的第一段故事。',
       1, 1, 0, 0, 0, 'seed_library_12packs_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_template_library
                    WHERE member_id = 100000 AND title = '老板的秘密题' AND del_flag = 0);

-- M03 拍照打卡任务 —— 拍照
INSERT INTO cms_template_library (member_id, member_template_id, title, description, players,
        usage_location, required_materials, duration, difficulty, rule_instructions,
        validation_method, photo_require_desc, photo_review,
        feedback_text, story_text,
        status, publish_status, is_sync, use_num, del_flag, create_by, create_time)
SELECT 100000, NULL, '拍下这一刻', '用一张照片完成城市任务', '单人/双人/不限',
       '商圈街区/室内场馆/古镇老街', '手机摄像头;商家准备拍照点', 8, '入门',
       '1. 找到店内指定拍照点。2. 拍下符合要求的照片。3. 上传照片后完成任务,生成分享素材。(运营提示:拍照要求必须具体,例如「包含店铺招牌」或「拍下今日限定甜品」,不要写「拍一张好看的照片」)',
       2, '拍下你和店内指定元素的合影,画面需包含店铺标识或指定商品', 0,
       '这张照片已成为你的城市证据。',
       '有些城市记忆不是写下来的,是被拍下来的。找到这家店最像「主角镜头」的角落。这一刻被收入你的城市相册。',
       1, 1, 0, 0, 0, 'seed_library_12packs_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_template_library
                    WHERE member_id = 100000 AND title = '拍下这一刻' AND del_flag = 0);

-- M04 风味集章 —— 扫码打卡
INSERT INTO cms_template_library (member_id, member_template_id, title, description, players,
        usage_location, required_materials, duration, difficulty, rule_instructions,
        validation_method,
        feedback_text, story_text,
        status, publish_status, is_sync, use_num, del_flag, create_by, create_time)
SELECT 100000, NULL, '收集这枚风味印章', '到店核销,点亮街区味道', '单人/双人/不限',
       '商圈街区/古镇老街', '店员核销码;印章奖励配置', 8, '入门',
       '1. 到达合作门店。2. 扫码或由店员核销。3. 点亮风味印章,集满指定数量兑换奖励。(运营提示:必须提前定义集几枚章可兑换什么,建议 3 家给基础奖励,5 家给进阶奖励;二维码在路线/点位层绑定)',
       4,
       '你收集到一枚新的风味印章。',
       '这座城市的味道,藏在一家家亮着灯的小店里。这一口风味被记录,街区地图又亮了一格。继续前往下一家,完成你的风味巡游。',
       1, 1, 0, 0, 0, 'seed_library_12packs_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_template_library
                    WHERE member_id = 100000 AND title = '收集这枚风味印章' AND del_flag = 0);

-- M05 新品盲测 —— 选项问答(注:当前选项题是「正确答案」模型,真实问卷收集需后续单独能力,见文档 §0.3)
INSERT INTO cms_template_library (member_id, member_template_id, title, description, players,
        usage_location, required_materials, duration, difficulty, rule_instructions,
        validation_method, question_name, question_a, question_b, question_c, question_d,
        correct_answer, hint1, answer_reveal,
        feedback_text, story_text,
        status, publish_status, is_sync, use_num, del_flag, create_by, create_time)
SELECT 100000, NULL, '新品盲测局', '成为第一批新品体验官', '单人/小队/不限',
       '室内场馆/商圈街区', '新品样品;店员说明;体验券', 15, '入门',
       '1. 到店领取新品体验。2. 完成试吃、试喝或试用。3. 提交你的判断,领取体验官奖励。(运营提示:商家改造时把「正确项」设为最想传达的卖点;如要收集真实偏好,等问卷能力上线)',
       3, '你觉得这款新品最突出的特点是什么?', '香气', '口感', '造型', '性价比',
       'A', '先完成体验,再选择你的真实感受', '你的选择已记录为新品反馈',
       '体验官反馈已收录,下一次新品也会优先邀请你。',
       '你不是普通顾客,而是今天这款新品的第一批体验官。认真感受它的气味、口感、颜色或手感。你的选择会影响这款新品下一次出现的方式。',
       1, 1, 0, 0, 0, 'seed_library_12packs_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_template_library
                    WHERE member_id = 100000 AND title = '新品盲测局' AND del_flag = 0);

-- M06 搭配挑战 —— 拍照
INSERT INTO cms_template_library (member_id, member_template_id, title, description, players,
        usage_location, required_materials, duration, difficulty, rule_instructions,
        validation_method, photo_require_desc, photo_review,
        feedback_text, story_text,
        status, publish_status, is_sync, use_num, del_flag, create_by, create_time)
SELECT 100000, NULL, '今日搭配任务', '组合你的专属风格方案', '单人/双人',
       '室内场馆/商圈街区', '商品陈列;搭配建议;拍照点', 15, '普通',
       '1. 按任务要求选择商品或套餐组合。2. 拍下你的搭配成果。3. 上传后获得风格称号或专属优惠。(运营提示:商家要提前准备 2-3 套推荐搭配,降低用户选择成本)',
       2, '拍下你的搭配成果,画面需包含至少 2 件指定商品或套餐组合', 0,
       '你的今日风格已生成,下一站可以继续升级。',
       '今天的你需要一个新的城市身份。从颜色、味道、材质或口味里,选出你的组合。搭配完成,你的今日风格被点亮。',
       1, 1, 0, 0, 0, 'seed_library_12packs_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_template_library
                    WHERE member_id = 100000 AND title = '今日搭配任务' AND del_flag = 0);

-- C01 集合破冰任务 —— 扫码打卡
INSERT INTO cms_template_library (member_id, member_template_id, title, description, players,
        usage_location, required_materials, duration, difficulty, rule_instructions,
        validation_method,
        feedback_text, story_text,
        status, publish_status, is_sync, use_num, del_flag, create_by, create_time)
SELECT 100000, NULL, '集合点亮', '到达集合点,领取今日身份', '小队/不限',
       '公园绿地/商圈街区/校园', '集合二维码;身份卡;领队', 5, '入门',
       '1. 到达集合点。2. 找到领队完成签到。3. 领取今日身份,准备出发。(运营提示:默认放在俱乐部主题的第一个节点,解决迟到、散乱、陌生人尴尬;小规模熟人局可改为无需验证)',
       4,
       '集合完成。你的城市小队已上线。',
       '今天你不是一个人出发,你加入了一支城市小队。每个玩家都会获得一个今日身份。队伍已集合,第一段路线即将点亮。',
       1, 1, 0, 0, 0, 'seed_library_12packs_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_template_library
                    WHERE member_id = 100000 AND title = '集合点亮' AND del_flag = 0);

-- C02 分队竞速 —— GPS 到达(注:实时排行榜未建,第一版按完成时间做活动后排名,见文档运营提示)
INSERT INTO cms_template_library (member_id, member_template_id, title, description, players,
        usage_location, required_materials, duration, difficulty, rule_instructions,
        validation_method, hint1, hint2, answer_reveal,
        feedback_text, story_text,
        status, publish_status, is_sync, use_num, del_flag, create_by, create_time)
SELECT 100000, NULL, '小队竞速点亮', '和你的队友抢先点亮目标点', '小队',
       '公园绿地/商圈街区/校园', '分队名单;路线点位;领队规则', 15, '挑战',
       '1. 小队同时出发。2. 在限定时间内到达目标点。3. 完成点亮后进入下一站,最终按完成时间和任务数排名。(运营提示:坐标在路线/点位层绑定;GPS 偏差大的点位可改扫码)',
       5, '靠近目标点 50 米内即可点亮', '注意路线安全,不要横穿马路', '到达目标点后等待系统点亮',
       '目标点已点亮,小队排名正在更新。',
       '倒计时结束,城市地图被分成几条路线。你们的小队正在接近目标点。坐标点亮,排名正在刷新。',
       1, 1, 0, 0, 0, 'seed_library_12packs_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_template_library
                    WHERE member_id = 100000 AND title = '小队竞速点亮' AND del_flag = 0);

-- C03 城市解谜 —— 文字问答(方案A)
INSERT INTO cms_template_library (member_id, member_template_id, title, description, players,
        usage_location, required_materials, duration, difficulty, rule_instructions,
        validation_method, question_name, question_answer, hint1, hint2, answer_reveal,
        feedback_text, story_text,
        status, publish_status, is_sync, use_num, del_flag, create_by, create_time)
SELECT 100000, NULL, '街角谜题', '观察现场线索,解开城市暗号', '单人/小队',
       '古镇老街/商圈街区/博物馆/校园', '现场线索;题目答案;备用提示', 8, '普通',
       '1. 到达指定地点。2. 观察现场细节,找到线索。3. 输入答案,解锁下一段故事。(运营提示:谜题答案必须稳定存在,不要依赖临时海报、会被遮挡的物件或容易变化的菜单)',
       1, '找到门牌上的年份,并输入四位数字', '1932',
       '线索在入口左侧', '找到那块带年份的金属牌', '答案是 1932,它是这栋建筑的重要年份。',
       '谜题解开。城市向你交出了一段隐藏信息。',
       '这条街看起来普通,但它把答案藏在了表面之下。不要急着离开,真正的线索通常在门牌、墙角和旧物上。你读懂了这座城市留在现场的暗号。',
       1, 1, 0, 0, 0, 'seed_library_12packs_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_template_library
                    WHERE member_id = 100000 AND title = '街角谜题' AND del_flag = 0);

-- C04 摄影任务 —— 拍照(人工审核)
INSERT INTO cms_template_library (member_id, member_template_id, title, description, players,
        usage_location, required_materials, duration, difficulty, rule_instructions,
        validation_method, photo_require_desc, photo_review,
        feedback_text, story_text,
        status, publish_status, is_sync, use_num, del_flag, create_by, create_time)
SELECT 100000, NULL, '城市取景任务', '拍下今天的主题画面', '单人/小队',
       '商圈街区/古镇老街/公园绿地/校园', '手机或相机;主题示例图', 15, '普通',
       '1. 阅读今日拍摄主题。2. 在指定区域内寻找画面。3. 上传照片,完成取景任务。(运营提示:如做评选,规则要提前说明——按主题契合、构图、故事感评分)',
       2, '拍下一个符合主题的城市画面:红色门牌、老窗、街角光影或一处生活瞬间', 1,
       '作品已提交,等待被收入今日城市影集。',
       '城市正在移动,但总有一个瞬间会等你按下快门。今天的主题不是景点,而是细节。你把这座城市的一秒钟带走了。',
       1, 1, 0, 0, 0, 'seed_library_12packs_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_template_library
                    WHERE member_id = 100000 AND title = '城市取景任务' AND del_flag = 0);

-- C05 接力任务 —— 文字问答
INSERT INTO cms_template_library (member_id, member_template_id, title, description, players,
        usage_location, required_materials, duration, difficulty, rule_instructions,
        validation_method, question_name, question_answer, hint1, hint2, answer_reveal,
        feedback_text, story_text,
        status, publish_status, is_sync, use_num, del_flag, create_by, create_time)
SELECT 100000, NULL, '城市接力棒', '完成你的线索,把任务传给下一队', '小队',
       '校园/商圈街区/公园绿地', '分队名单;接力暗号;领队记录', 8, '普通',
       '1. 完成本队线索。2. 得到接力暗号。3. 将暗号交给下一队,完成接力。(运营提示:第一版队伍顺序由领队线下控制,小程序负责节点完成和记录)',
       1, '输入你们传给下一队的接力暗号', '星火继续',
       '暗号由上一段线索组成', '两个词:一个代表光,一个代表继续', '接力暗号:星火继续',
       '接力完成。你们让下一段路线开始发光。',
       '这不是一个人的任务,线索会在队伍之间传递。你们手里的答案,会成为下一队的起点。接力成功,城市路线继续向前点亮。',
       1, 1, 0, 0, 0, 'seed_library_12packs_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_template_library
                    WHERE member_id = 100000 AND title = '城市接力棒' AND del_flag = 0);

-- C06 角色任务 —— 拍照(小队证据照)
INSERT INTO cms_template_library (member_id, member_template_id, title, description, players,
        usage_location, required_materials, duration, difficulty, rule_instructions,
        validation_method, photo_require_desc, photo_review,
        feedback_text, story_text,
        status, publish_status, is_sync, use_num, del_flag, create_by, create_time)
SELECT 100000, NULL, '今日角色任务', '抽取身份,完成你的专属任务', '小队',
       '商圈街区/古镇老街/校园/公园绿地', '角色卡;分工说明;领队', 15, '挑战',
       '1. 小队成员抽取角色。2. 按角色分工完成任务。3. 上传证据或回答问题,解锁小队奖励。(运营提示:第一版角色可先线下抽卡,小程序记录「角色任务完成」;系统自动分配角色上线后自动升级)',
       2, '拍下小队完成角色任务的证据,画面需包含至少 2 名队员和任务地点', 0,
       '小队任务完成。你们的角色配合已被记录。',
       '一支真正的小队,不靠一个人通关。导航员看路,观察员找线索,记录员留下证据,交涉员打开门。角色各就各位,这座城市被你们一起推进了一格。',
       1, 1, 0, 0, 0, 'seed_library_12packs_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_template_library
                    WHERE member_id = 100000 AND title = '今日角色任务' AND del_flag = 0);


-- ======================= 落库后回读断言 =======================
-- 1) 12 条全部在架(期望 12 行,status/publish_status 全 1):
--   SELECT title, status, publish_status, use_num FROM cms_template_library
--    WHERE create_by = 'seed_library_12packs_20260820' AND del_flag = 0;
--
-- 2) 完成方式分布应为:1文字×3(M01/C03/C05) 2拍照×4(M03/M06/C04/C06) 3选项×2(M02/M05) 4扫码×2(M04/C01) 5GPS×1(C02):
--   SELECT validation_method, COUNT(*) FROM cms_template_library
--    WHERE create_by = 'seed_library_12packs_20260820' AND del_flag = 0 GROUP BY validation_method;
--
-- 3) 采用闭环冒烟(部署后):任一会员 POST /api/template/use {id} → cms_member_template 多一行
--    (member_id=采用者, status=1, draft_status=1, coupon_id IS NULL),库行 use_num +1。


-- ======================= 事务内写后精确断言 =======================
-- 12 个具名模板、上架状态和五种完成方式分布任一漂移，立即红并回滚。
INSERT INTO seed_library_12_guard (id)
SELECT 1
 WHERE (SELECT COUNT(*)
          FROM cms_template_library
         WHERE create_by = 'seed_library_12packs_20260820'
           AND member_id = 100000 AND member_template_id IS NULL
           AND status = 1 AND publish_status = 1 AND is_sync = 0
           AND del_flag = 0
           AND title IN ('今晚的暗号','老板的秘密题','拍下这一刻','收集这枚风味印章','新品盲测局',
                         '今日搭配任务','集合点亮','小队竞速点亮','街角谜题','城市取景任务',
                         '城市接力棒','今日角色任务')) <> 12
    OR (SELECT COUNT(*)
          FROM cms_template_library
         WHERE create_by = 'seed_library_12packs_20260820' AND del_flag = 0) <> 12
    OR (SELECT COUNT(*) FROM cms_template_library
         WHERE create_by = 'seed_library_12packs_20260820' AND del_flag = 0
           AND validation_method = 1) <> 3
    OR (SELECT COUNT(*) FROM cms_template_library
         WHERE create_by = 'seed_library_12packs_20260820' AND del_flag = 0
           AND validation_method = 2) <> 4
    OR (SELECT COUNT(*) FROM cms_template_library
         WHERE create_by = 'seed_library_12packs_20260820' AND del_flag = 0
           AND validation_method = 3) <> 2
    OR (SELECT COUNT(*) FROM cms_template_library
         WHERE create_by = 'seed_library_12packs_20260820' AND del_flag = 0
           AND validation_method = 4) <> 2
    OR (SELECT COUNT(*) FROM cms_template_library
         WHERE create_by = 'seed_library_12packs_20260820' AND del_flag = 0
           AND validation_method = 5) <> 1
    OR EXISTS (
           SELECT 1
             FROM seed_library_12_expected e
             LEFT JOIN cms_template_library tpl
               ON tpl.title = e.title AND tpl.validation_method = e.validation_method
              AND tpl.member_id = 100000 AND tpl.member_template_id IS NULL
              AND tpl.status = 1 AND tpl.publish_status = 1 AND tpl.is_sync = 0
              AND tpl.coupon_id IS NULL AND tpl.del_flag = 0
              AND tpl.create_by = 'seed_library_12packs_20260820'
            WHERE tpl.id IS NULL
               OR NULLIF(TRIM(tpl.description), '') IS NULL
               OR NULLIF(TRIM(tpl.players), '') IS NULL
               OR NULLIF(TRIM(tpl.usage_location), '') IS NULL
               OR tpl.duration IS NULL OR tpl.duration <= 0
               OR NULLIF(TRIM(tpl.difficulty), '') IS NULL
               OR NULLIF(TRIM(tpl.rule_instructions), '') IS NULL
               OR (e.validation_method = 1
                   AND (NULLIF(TRIM(tpl.question_name), '') IS NULL
                        OR NULLIF(TRIM(tpl.question_answer), '') IS NULL))
               OR (e.validation_method = 2
                   AND NULLIF(TRIM(tpl.photo_require_desc), '') IS NULL)
               OR (e.validation_method = 3
                   AND (NULLIF(TRIM(tpl.question_name), '') IS NULL
                        OR NULLIF(TRIM(tpl.question_a), '') IS NULL
                        OR NULLIF(TRIM(tpl.question_b), '') IS NULL
                        OR NULLIF(TRIM(tpl.question_c), '') IS NULL
                        OR NULLIF(TRIM(tpl.question_d), '') IS NULL
                        OR NULLIF(TRIM(tpl.correct_answer), '') IS NULL))
       );

-- 事务内 guard 只防提交前漂移；COMMIT 后仍须换新只读连接，从 cms_template_library 回读同一组断言。
