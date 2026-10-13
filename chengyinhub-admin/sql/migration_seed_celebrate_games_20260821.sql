-- runner: requires=transaction
-- ============================================================================
-- 《今天值得庆祝》G0—G7 八个即插即用游戏落位(先于主题种子,用户指定顺序)
--
-- 内容真源(逐字段可回溯):
--   ~/Documents/城瘾/文档整理_20260711/05-参考资料/归档_20260803/状态终审_原04/
--   Questify_今天值得庆祝_一件小事的3小时城市仪式_即插即用自由探索模板_20260724.md §5
--
-- 实现口径(沿用 migration_seed_life_backup_games_20260820.sql 已验证剧本):
--   · validation_method=0(无判分)、interaction_type 留 NULL、零 DDL;
--   · 双表:cms_member_template(member_id=100000,主题详情 games[] 数据链)
--     + cms_template_library(小程序模板库,「世界一刻」(18) tab);
--   · 节点接线在主题种子(migration_seed_celebrate_topic_20260821.sql)里做 —— 主题后落。
--   · G2—G5/G6 的真实交互由仪式板(celebration_board)承载,这里是模板库里的
--     规则文案本体;两者不冲突:模板卡=规则说明,仪式板=运行时。
--
-- ★ 幂等:幂等键=(member_id=100000, title);INSERT ... SELECT ... WHERE NOT EXISTS。
-- ★ 纯 DML,InnoDB,单表锚点形状,必须带 --transaction 执行。
--   锚点用 sys_category id=3(美食烹饪,平台常驻行)—— 引擎回读需要真实持久表。
-- ============================================================================


-- ======================= 一、八个玩法模板(cms_member_template) =======================

INSERT INTO cms_member_template
       (member_id, title, description, players, usage_location, required_materials,
        duration, difficulty, is_sync, rule_instructions, validation_method,
        status, publish_status, draft_status, check_status, del_flag, create_by, create_time)
SELECT 100000, 'G0｜30秒今天值得什么',
       '玩家从「终于完成、第一次做到、熬过一关、重新见面、开启新阶段、只想认真夸自己一次」中选一个类别,再选择想要热闹、安静、动手或好好吃一顿。',
       '1—8人', '本地生活圈(线上完成,不要求到店)', '无需任何物料',
       1, '简单', 1,
       '玩家动作:选庆祝类别→选仪式风格。无需写单位、病情、成绩或其他敏感详情;私人标签只归玩家自己。商家配置:无。员工动作:零。',
       0, 1, 1, 1, 1, 0, 'seed_celebrate_games_20260821', NOW()
  FROM sys_category anchor
 WHERE anchor.id = 3
   AND NOT EXISTS (SELECT 1 FROM cms_member_template m
        WHERE m.member_id = 100000 AND m.title = 'G0｜30秒今天值得什么' AND m.del_flag = 0);

INSERT INTO cms_member_template
       (member_id, title, description, players, usage_location, required_materials,
        duration, difficulty, is_sync, rule_instructions, validation_method,
        status, publish_status, draft_status, check_status, del_flag, create_by, create_time)
SELECT 100000, 'G1｜每人一个不能',
       '同行人或发起人各选一个 MUST_HAVE 和一个 NO_GO。系统只保留交集,不展示谁否决了什么,输出全局硬条件。',
       '1—8人', '本地生活圈(线上完成,不要求到店)', '无需任何物料',
       1, '简单', 1,
       '玩家动作:每人一个必须有+一个不能有。系统只保留交集;不展示谁否决了什么(保护表达安全)。未回复不代填。商家配置:无。员工动作:零。',
       0, 1, 1, 1, 1, 0, 'seed_celebrate_games_20260821', NOW()
  FROM sys_category anchor
 WHERE anchor.id = 3
   AND NOT EXISTS (SELECT 1 FROM cms_member_template m
        WHERE m.member_id = 100000 AND m.title = 'G1｜每人一个不能' AND m.del_flag = 0);

INSERT INTO cms_member_template
       (member_id, title, description, players, usage_location, required_materials,
        duration, difficulty, is_sync, rule_instructions, validation_method,
        status, publish_status, draft_status, check_status, del_flag, create_by, create_time)
SELECT 100000, 'G2｜气氛卡碰撞',
       '玩家将「热闹、松弛、动手、新鲜、安静」等气氛卡与候选商家真实项目匹配。项目内容、噪音、互动方式和人数改变结局,不做品牌常识题。',
       '1—8人', '本地生活圈(线上完成,不要求到店)', '无需任何物料',
       2, '简单', 1,
       '玩家动作:气氛卡↔候选真实项目匹配。商家资产:项目内容、噪音、互动方式、人数。反事实检查:删掉商家真实条件后结局不变=广告,不能进主游戏。员工动作:零。',
       0, 1, 1, 1, 1, 0, 'seed_celebrate_games_20260821', NOW()
  FROM sys_category anchor
 WHERE anchor.id = 3
   AND NOT EXISTS (SELECT 1 FROM cms_member_template m
        WHERE m.member_id = 100000 AND m.title = 'G2｜气氛卡碰撞' AND m.del_flag = 0);

INSERT INTO cms_member_template
       (member_id, title, description, players, usage_location, required_materials,
        duration, difficulty, is_sync, rule_instructions, validation_method,
        status, publish_status, draft_status, check_status, del_flag, create_by, create_time)
SELECT 100000, 'G3｜三小时时间方块',
       '玩家把 TOGETHER、LAND 和可选 MARK 放入 120—210 分钟时间板。营业、截单、项目时长、预约提前量和路线会让方块变绿、变黄或冲突,直到形成静态条件可检查的组合。',
       '1—8人', '本地生活圈(线上完成,不要求到店)', '无需任何物料',
       3, '简单', 1,
       '玩家动作:三段放入时间板→看绿黄红。商家资产:营业时间、截单、项目时长、预约提前量。单个项目超时限时减少角色,不硬塞第二三家。员工动作:零。',
       0, 1, 1, 1, 1, 0, 'seed_celebrate_games_20260821', NOW()
  FROM sys_category anchor
 WHERE anchor.id = 3
   AND NOT EXISTS (SELECT 1 FROM cms_member_template m
        WHERE m.member_id = 100000 AND m.title = 'G3｜三小时时间方块' AND m.del_flag = 0);

INSERT INTO cms_member_template
       (member_id, title, description, players, usage_location, required_materials,
        duration, difficulty, is_sync, rule_instructions, validation_method,
        status, publish_status, draft_status, check_status, del_flag, create_by, create_time)
SELECT 100000, 'G4｜每人预算筹码',
       '玩家把总预算分给 TOGETHER、LAND 和 MARK。系统读取真实价格带、计价单位、服务费和起订人数,不以「更贵」代表更好。',
       '1—8人', '本地生活圈(线上完成,不要求到店)', '无需任何物料',
       2, '简单', 1,
       '玩家动作:预算筹码分配三角色→超支即冲突。商家资产:价格带、计价单位、服务费、起订人数。任一角色超每人预算必须重新分配或淘汰方案。员工动作:零。',
       0, 1, 1, 1, 1, 0, 'seed_celebrate_games_20260821', NOW()
  FROM sys_category anchor
 WHERE anchor.id = 3
   AND NOT EXISTS (SELECT 1 FROM cms_member_template m
        WHERE m.member_id = 100000 AND m.title = 'G4｜每人预算筹码' AND m.del_flag = 0);

INSERT INTO cms_member_template
       (member_id, title, description, players, usage_location, required_materials,
        duration, difficulty, is_sync, rule_instructions, validation_method,
        status, publish_status, draft_status, check_status, del_flag, create_by, create_time)
SELECT 100000, 'G5｜十分钟步行上限',
       '平台隐藏商家名字,只显示真实路线时间、无障碍和天气条件;玩家先淘汰超出步行限制的组合,再揭晓商家,避免只被图片吸引。',
       '1—8人', '本地生活圈(线上完成,不要求到店)', '无需任何物料',
       2, '简单', 1,
       '玩家动作:蒙名先筛路线→再揭晓商家。商家资产:真实路线时间、无障碍、天气条件。单段步行超上限的组合直接淘汰,不许文案救回。员工动作:零。',
       0, 1, 1, 1, 1, 0, 'seed_celebrate_games_20260821', NOW()
  FROM sys_category anchor
 WHERE anchor.id = 3
   AND NOT EXISTS (SELECT 1 FROM cms_member_template m
        WHERE m.member_id = 100000 AND m.title = 'G5｜十分钟步行上限' AND m.del_flag = 0);

INSERT INTO cms_member_template
       (member_id, title, description, players, usage_location, required_materials,
        duration, difficulty, is_sync, rule_instructions, validation_method,
        status, publish_status, draft_status, check_status, del_flag, create_by, create_time)
SELECT 100000, 'G6｜主选突然掉线',
       '统一方案终局:系统模拟满员、临时停业、下雨或超时中的一个真实失效条件。玩家要在30秒内替换同角色备选,并保持总时长、预算和硬条件仍成立。',
       '1—8人', '本地生活圈(线上完成,不要求到店)', '无需任何物料',
       1, '简单', 1,
       '玩家动作:30秒内用备选修复计划。成功且其他静态约束均通过→进入 READY_TO_CHECK;否则保持 HOST_DRAFT 并显示冲突。失败结果明确指出冲突,不羞辱玩家。员工动作:零。',
       0, 1, 1, 1, 1, 0, 'seed_celebrate_games_20260821', NOW()
  FROM sys_category anchor
 WHERE anchor.id = 3
   AND NOT EXISTS (SELECT 1 FROM cms_member_template m
        WHERE m.member_id = 100000 AND m.title = 'G6｜主选突然掉线' AND m.del_flag = 0);

INSERT INTO cms_member_template
       (member_id, title, description, players, usage_location, required_materials,
        duration, difficulty, is_sync, rule_instructions, validation_method,
        status, publish_status, draft_status, check_status, del_flag, create_by, create_time)
SELECT 100000, 'G7｜60秒共同落款',
       '玩家自己或每位同行人从「今天最值得记住的是____」「我想认真说一句____」「下次想继续____」中选一个提示,选结构化短句卡或自愿填写一句话,合成私密纪念卡与可编辑邀请。',
       '1—8人', '本地生活圈(线上完成,不要求到店)', '无需任何物料',
       1, '简单', 1,
       '玩家动作:选提示→结构化落款。落款不发送给商家、不要求公开分享、不换券;没有人填自由文本平台也能用结构化选择生成完整结果;未回复者不被系统代写。员工动作:零。',
       0, 1, 1, 1, 1, 0, 'seed_celebrate_games_20260821', NOW()
  FROM sys_category anchor
 WHERE anchor.id = 3
   AND NOT EXISTS (SELECT 1 FROM cms_member_template m
        WHERE m.member_id = 100000 AND m.title = 'G7｜60秒共同落款' AND m.del_flag = 0);


-- ======================= 二、小程序公共模板库(cms_template_library) =======================

INSERT INTO cms_template_library
       (member_id, category_id, title, description, players, duration, difficulty,
        rule_instructions, validation_method, activity_categoryids,
        status, publish_status, del_flag, create_by, create_time)
SELECT 100000, 18, m.title, m.description, m.players, m.duration, m.difficulty,
       m.rule_instructions, 0, '18', 1, 1, 0, 'seed_celebrate_games_20260821', NOW()
  FROM cms_member_template m
 WHERE m.member_id = 100000 AND m.del_flag = 0
   AND m.create_by = 'seed_celebrate_games_20260821'
   AND NOT EXISTS (SELECT 1 FROM cms_template_library l
        WHERE l.member_id = 100000 AND l.title = m.title AND l.del_flag = 0);


-- ======================= 落库后回读验证(「回执 ≠ 观测」) =======================
--   SELECT (SELECT COUNT(*) FROM cms_member_template
--            WHERE create_by='seed_celebrate_games_20260821' AND del_flag=0) AS games,
--          (SELECT COUNT(*) FROM cms_template_library
--            WHERE create_by='seed_celebrate_games_20260821' AND del_flag=0) AS lib_games;
--   -- 期望:8 / 8;重复执行后仍 8 / 8。


-- ======================= 回滚 =======================
-- DELETE FROM cms_template_library WHERE create_by='seed_celebrate_games_20260821';
-- DELETE FROM cms_member_template WHERE create_by='seed_celebrate_games_20260821';
