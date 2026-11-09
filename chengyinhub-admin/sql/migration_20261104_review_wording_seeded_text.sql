-- 审核用语:平台种子里存着的「盲盒 / 探店日 / 竞猜」改成「随机卡 / 自由探索 / 预测」(2026-11-04 业主拍板,第十一轮 h2 第 2 项)
--
-- 只改平台自己种进去的行,只改与种子原文逐字相同的值(utf8mb4_bin 比较,大小写、首尾空格都算改过):
--   - 有人在后台或编辑器里改过的文字,和原文不再逐字相同,本文件不碰。
--   - 商家复制出去的主题、节点、玩法(create_by = 'system_copy' 或 member_id 不是平台)不碰;
--     复制件数量见 docs/delivery/walk-fix-20261102/integ/proof/round11-h2/stored-text-precheck.sql,交业主看。
--   - 不改任何 code / perms / invoke_target / 主键;sys_job 只改 job_name 与 remark,调用目标不变
--     (Quartz 的 JobKey 由 job_id + job_group 组成,改名不影响调度;新 jar 启动时按库重装)。
--
-- 来源(旧文件一律不改):
--   ① 模板「三个盲盒」及其节点、玩法:migration_seed_playpack_templates_20260820.sql(create_by = 'seed_playpack_20260820')
--      模板「穿搭盲配」的章节「盲盒衣柜」:migration_seed_playpack_batch2_figma_20260821.sql(create_by = 'seed_playpack2_20260821')
--      封面:migration_20260822_template_cover_images.sql 按主题名把 img_url 写到行上,改名后 img_url 留在原行,
--      不需要再改;封面图本身不含文字。docs/assets/template-covers/manifest.json 记录的是那份历史迁移的映射,不改。
--   ② sys_menu 名称与备注里的「探店日」:migration_20260816_tandianri_batch3_funds_club_compensation、
--      migration_20260817_tandianri_batch3_funds_club_hours_correction、migration_20260817_tandianri_batch5_admin_menus;
--      同一规则下另有 4 条备注(探店日A3 / A4 / 5-5,来自 ..._player_cooldown、..._club_payee、
--      migration_20260815_tandianri_roam_revisit_report_permission),一并改。
--   ③ sys_job「竞猜轮次超时作废」及备注:migration_predict_void_task_register_20260915.sql
--      (migration_predict_void_expired_job_20260906.sql 的同名同备注)。
--
-- 可重跑:每句 WHERE 都带旧值,第二次执行命中 0 行;断言只看「平台种子行里不再有旧值」,
--   库里没有这些种子(新环境、已被删)时断言照样成立。
-- 回退:把下面每句的新旧值对调执行即可(同样只命中逐字等于新值的平台行)。

-- ======================= ① 模板文字:盲盒 → 随机卡 =======================

UPDATE cms_topic
   SET name = '三个随机卡',
       update_by = 'migration_20261104',
       update_time = NOW()
 WHERE member_id = 100000 AND is_template = 1 AND create_by = 'seed_playpack_20260820'
   AND name = '三个盲盒' COLLATE utf8mb4_bin;

UPDATE cms_topic
   SET description = '三个「随机卡袋子」,每个拆开是一种不同的小任务:观察、拍照、开口问。全部完成即通关。适合想让常客每次来都玩点新花样的店。复制后把三个任务分别换成你店的版本。',
       update_by = 'migration_20261104',
       update_time = NOW()
 WHERE member_id = 100000 AND is_template = 1 AND create_by = 'seed_playpack_20260820'
   AND description = '三个「盲盒袋子」,每个拆开是一种不同的小任务:观察、拍照、开口问。全部完成即通关。适合想让常客每次来都玩点新花样的店。复制后把三个任务分别换成你店的版本。' COLLATE utf8mb4_bin;

UPDATE cms_topic_node n
  JOIN cms_topic t ON t.id = n.topic_id
   SET n.name = '随机卡一:观察',
       n.update_by = 'migration_20261104',
       n.update_time = NOW()
 WHERE t.member_id = 100000 AND t.is_template = 1 AND t.create_by = 'seed_playpack_20260820'
   AND n.create_by = 'seed_playpack_20260820'
   AND n.name = '盲盒一:观察' COLLATE utf8mb4_bin;

UPDATE cms_topic_node n
  JOIN cms_topic t ON t.id = n.topic_id
   SET n.name = '随机卡二:拍照',
       n.update_by = 'migration_20261104',
       n.update_time = NOW()
 WHERE t.member_id = 100000 AND t.is_template = 1 AND t.create_by = 'seed_playpack_20260820'
   AND n.create_by = 'seed_playpack_20260820'
   AND n.name = '盲盒二:拍照' COLLATE utf8mb4_bin;

UPDATE cms_topic_node n
  JOIN cms_topic t ON t.id = n.topic_id
   SET n.name = '随机卡三:开口',
       n.update_by = 'migration_20261104',
       n.update_time = NOW()
 WHERE t.member_id = 100000 AND t.is_template = 1 AND t.create_by = 'seed_playpack_20260820'
   AND n.create_by = 'seed_playpack_20260820'
   AND n.name = '盲盒三:开口' COLLATE utf8mb4_bin;

UPDATE cms_member_template
   SET title = '玩法包·随机卡之观察',
       update_by = 'migration_20261104',
       update_time = NOW()
 WHERE member_id = 100000 AND create_by = 'seed_playpack_20260820'
   AND title = '玩法包·盲盒之观察' COLLATE utf8mb4_bin;

UPDATE cms_member_template
   SET title = '玩法包·随机卡之拍照',
       update_by = 'migration_20261104',
       update_time = NOW()
 WHERE member_id = 100000 AND create_by = 'seed_playpack_20260820'
   AND title = '玩法包·盲盒之拍照' COLLATE utf8mb4_bin;

UPDATE cms_member_template
   SET title = '玩法包·随机卡之开口',
       update_by = 'migration_20261104',
       update_time = NOW()
 WHERE member_id = 100000 AND create_by = 'seed_playpack_20260820'
   AND title = '玩法包·盲盒之开口' COLLATE utf8mb4_bin;

UPDATE cms_member_template
   SET question_name = '随机卡一拆开是一张纸条:「找到店里唯一会动的装饰。」它是什么?',
       update_by = 'migration_20261104',
       update_time = NOW()
 WHERE member_id = 100000 AND create_by = 'seed_playpack_20260820'
   AND question_name = '盲盒一拆开是一张纸条:「找到店里唯一会动的装饰。」它是什么?' COLLATE utf8mb4_bin;

UPDATE cms_member_template
   SET photo_require_desc = '随机卡二是一台「拍立得」:拍下你此刻桌面上的东西,怎么摆随你,但画面里要有至少一样店里的东西。',
       update_by = 'migration_20261104',
       update_time = NOW()
 WHERE member_id = 100000 AND create_by = 'seed_playpack_20260820'
   AND photo_require_desc = '盲盒二是一台「拍立得」:拍下你此刻桌面上的东西,怎么摆随你,但画面里要有至少一样店里的东西。' COLLATE utf8mb4_bin;

UPDATE cms_member_template
   SET question_name = '随机卡三是一个问题,答案只有店员知道:「问问店员,今天店里最推荐的是什么?」把 TA 说的那样东西填进来。(提示:店员答什么填什么,两到五个字)',
       update_by = 'migration_20261104',
       update_time = NOW()
 WHERE member_id = 100000 AND create_by = 'seed_playpack_20260820'
   AND question_name = '盲盒三是一个问题,答案只有店员知道:「问问店员,今天店里最推荐的是什么?」把 TA 说的那样东西填进来。(提示:店员答什么填什么,两到五个字)' COLLATE utf8mb4_bin;

UPDATE cms_member_template
   SET answer_reveal = '三个随机卡全部拆完!把通关画面出示给店员——顺便,真的试试 TA 刚推荐的那样东西?',
       update_by = 'migration_20261104',
       update_time = NOW()
 WHERE member_id = 100000 AND create_by = 'seed_playpack_20260820'
   AND answer_reveal = '三个盲盒全部拆完!把通关画面出示给店员——顺便,真的试试 TA 刚推荐的那样东西?' COLLATE utf8mb4_bin;

UPDATE cms_topic_chapter c
  JOIN cms_topic t ON t.id = c.topic_id
   SET c.name = '随机卡衣柜',
       c.update_by = 'migration_20261104',
       c.update_time = NOW()
 WHERE t.member_id = 100000 AND t.is_template = 1 AND t.create_by = 'seed_playpack2_20260821'
   AND c.create_by = 'seed_playpack2_20260821'
   AND c.name = '盲盒衣柜' COLLATE utf8mb4_bin;

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM cms_topic WHERE member_id = 100000 AND is_template = 1 AND create_by = 'seed_playpack_20260820' AND (name = '三个盲盒' COLLATE utf8mb4_bin OR description = '三个「盲盒袋子」,每个拆开是一种不同的小任务:观察、拍照、开口问。全部完成即通关。适合想让常客每次来都玩点新花样的店。复制后把三个任务分别换成你店的版本。' COLLATE utf8mb4_bin))
     + (SELECT COUNT(*) FROM cms_topic_node n JOIN cms_topic t ON t.id = n.topic_id
        WHERE t.member_id = 100000 AND t.is_template = 1 AND t.create_by = 'seed_playpack_20260820' AND n.create_by = 'seed_playpack_20260820'
          AND n.name COLLATE utf8mb4_bin IN ('盲盒一:观察', '盲盒二:拍照', '盲盒三:开口'))
     + (SELECT COUNT(*) FROM cms_member_template WHERE member_id = 100000 AND create_by = 'seed_playpack_20260820'
          AND (title = '玩法包·盲盒之观察' COLLATE utf8mb4_bin OR title = '玩法包·盲盒之拍照' COLLATE utf8mb4_bin OR title = '玩法包·盲盒之开口' COLLATE utf8mb4_bin OR question_name = '盲盒一拆开是一张纸条:「找到店里唯一会动的装饰。」它是什么?' COLLATE utf8mb4_bin OR photo_require_desc = '盲盒二是一台「拍立得」:拍下你此刻桌面上的东西,怎么摆随你,但画面里要有至少一样店里的东西。' COLLATE utf8mb4_bin OR question_name = '盲盒三是一个问题,答案只有店员知道:「问问店员,今天店里最推荐的是什么?」把 TA 说的那样东西填进来。(提示:店员答什么填什么,两到五个字)' COLLATE utf8mb4_bin OR answer_reveal = '三个盲盒全部拆完!把通关画面出示给店员——顺便,真的试试 TA 刚推荐的那样东西?' COLLATE utf8mb4_bin))
     + (SELECT COUNT(*) FROM cms_topic_chapter c JOIN cms_topic t ON t.id = c.topic_id
        WHERE t.member_id = 100000 AND t.is_template = 1 AND t.create_by = 'seed_playpack2_20260821' AND c.create_by = 'seed_playpack2_20260821'
          AND c.name = '盲盒衣柜' COLLATE utf8mb4_bin) = 0 AS seeded_template_text_renamed;

-- ======================= ② 后台菜单:探店日 → 自由探索 =======================

UPDATE sys_menu
   SET menu_name = '自由探索实际工时确认',
       update_by = 'migration_20261104',
       update_time = NOW()
 WHERE perms = 'business:clubhours:confirm'
   AND menu_name = '探店日实际工时确认' COLLATE utf8mb4_bin;

UPDATE sys_menu
   SET menu_name = '自由探索额外工时审批',
       update_by = 'migration_20261104',
       update_time = NOW()
 WHERE perms = 'business:clubextra:approve'
   AND menu_name = '探店日额外工时审批' COLLATE utf8mb4_bin;

UPDATE sys_menu
   SET menu_name = '自由探索质量奖励确认',
       update_by = 'migration_20261104',
       update_time = NOW()
 WHERE perms = 'business:clubquality:confirm'
   AND menu_name = '探店日质量奖励确认' COLLATE utf8mb4_bin;

UPDATE sys_menu
   SET menu_name = '自由探索质量奖励过错排除',
       update_by = 'migration_20261104',
       update_time = NOW()
 WHERE perms = 'business:clubquality:exclude'
   AND menu_name = '探店日质量奖励过错排除' COLLATE utf8mb4_bin;

UPDATE sys_menu
   SET menu_name = '自由探索期级FINAL结算',
       update_by = 'migration_20261104',
       update_time = NOW()
 WHERE perms = 'business:editionfinal:execute'
   AND menu_name = '探店日期级FINAL结算' COLLATE utf8mb4_bin;

UPDATE sys_menu
   SET menu_name = '自由探索已确认工时更正',
       update_by = 'migration_20261104',
       update_time = NOW()
 WHERE perms = 'business:clubhours:correct'
   AND menu_name = '探店日已确认工时更正' COLLATE utf8mb4_bin;

UPDATE sys_menu
   SET remark = '自由探索批5:核销率/频次异常人工复核队列。只做人审,不改核销事实、不退款、不动资金',
       update_by = 'migration_20261104',
       update_time = NOW()
 WHERE perms = 'business:verificationReview:list'
   AND remark = '探店日批5:核销率/频次异常人工复核队列。只做人审,不改核销事实、不退款、不动资金' COLLATE utf8mb4_bin;

UPDATE sys_menu
   SET remark = '自由探索批5:平台从零建店位节点。绑商家的节点由服务端强制按已过审留痕,否则玩家侧静默消失',
       update_by = 'migration_20261104',
       update_time = NOW()
 WHERE perms = 'business:topicnode:add'
   AND remark = '探店日批5:平台从零建店位节点。绑商家的节点由服务端强制按已过审留痕,否则玩家侧静默消失' COLLATE utf8mb4_bin;

UPDATE sys_menu
   SET remark = '自由探索批5:改店位节点内容。打卡码在页面上只读 —— 换码会让店里已印好的海报全部作废',
       update_by = 'migration_20261104',
       update_time = NOW()
 WHERE perms = 'business:topicnode:edit'
   AND remark = '探店日批5:改店位节点内容。打卡码在页面上只读 —— 换码会让店里已印好的海报全部作废' COLLATE utf8mb4_bin;

UPDATE sys_menu
   SET remark = '自由探索批5:删店位节点。已有玩家进店记录的节点删掉会让那些记录指向空,删前先看漏斗',
       update_by = 'migration_20261104',
       update_time = NOW()
 WHERE perms = 'business:topicnode:remove'
   AND remark = '探店日批5:删店位节点。已有玩家进店记录的节点删掉会让那些记录指向空,删前先看漏斗' COLLATE utf8mb4_bin;

UPDATE sys_menu
   SET remark = '自由探索5-5只读报表权限；仅允许查询SHOP_REVISIT/ROAM_REVISIT 7/30/90日观察窗',
       update_by = 'migration_20261104',
       update_time = NOW()
 WHERE perms = 'business:roamRevisitReport:list'
   AND remark = '探店日5-5只读报表权限；仅允许查询SHOP_REVISIT/ROAM_REVISIT 7/30/90日观察窗' COLLATE utf8mb4_bin;

UPDATE sys_menu
   SET remark = '自由探索A3：改冷静期小时数的专用入口（PlayerWithdrawalCooldownController#rewrite）；前值/后值/理由落 player_withdrawal_cooldown_config_audit，只影响新事件',
       update_by = 'migration_20261104',
       update_time = NOW()
 WHERE perms = 'business:withdrawal:cooldown-config'
   AND remark = '探店日A3：改冷静期小时数的专用入口（PlayerWithdrawalCooldownController#rewrite）；前值/后值/理由落 player_withdrawal_cooldown_config_audit，只影响新事件' COLLATE utf8mb4_bin;

UPDATE sys_menu
   SET remark = '自由探索A4：财务一次审核；不设 maker/checker，理由见 ClubCorporatePayeeServiceImpl.verify；俱乐部侧不得自审由 app 侧零 payee 入口保证',
       update_by = 'migration_20261104',
       update_time = NOW()
 WHERE perms = 'business:clubpayee:verify'
   AND remark = '探店日A4：财务一次审核；不设 maker/checker，理由见 ClubCorporatePayeeServiceImpl.verify；俱乐部侧不得自审由 app 侧零 payee 入口保证' COLLATE utf8mb4_bin;

UPDATE sys_menu
   SET remark = '自由探索A4：受控完整解密，仅付款登记环节使用；与审核权限互斥分派（见本文件末的角色分派与回读）',
       update_by = 'migration_20261104',
       update_time = NOW()
 WHERE perms = 'business:clubpayee:payout'
   AND remark = '探店日A4：受控完整解密，仅付款登记环节使用；与审核权限互斥分派（见本文件末的角色分派与回读）' COLLATE utf8mb4_bin;

-- runner: assert=scalar-true
SELECT COUNT(*) = 0 AS seeded_menu_text_renamed
FROM sys_menu
WHERE (perms = 'business:clubhours:confirm' AND menu_name = '探店日实际工时确认' COLLATE utf8mb4_bin)
    OR (perms = 'business:clubextra:approve' AND menu_name = '探店日额外工时审批' COLLATE utf8mb4_bin)
    OR (perms = 'business:clubquality:confirm' AND menu_name = '探店日质量奖励确认' COLLATE utf8mb4_bin)
    OR (perms = 'business:clubquality:exclude' AND menu_name = '探店日质量奖励过错排除' COLLATE utf8mb4_bin)
    OR (perms = 'business:editionfinal:execute' AND menu_name = '探店日期级FINAL结算' COLLATE utf8mb4_bin)
    OR (perms = 'business:clubhours:correct' AND menu_name = '探店日已确认工时更正' COLLATE utf8mb4_bin)
    OR (perms = 'business:verificationReview:list' AND remark = '探店日批5:核销率/频次异常人工复核队列。只做人审,不改核销事实、不退款、不动资金' COLLATE utf8mb4_bin)
    OR (perms = 'business:topicnode:add' AND remark = '探店日批5:平台从零建店位节点。绑商家的节点由服务端强制按已过审留痕,否则玩家侧静默消失' COLLATE utf8mb4_bin)
    OR (perms = 'business:topicnode:edit' AND remark = '探店日批5:改店位节点内容。打卡码在页面上只读 —— 换码会让店里已印好的海报全部作废' COLLATE utf8mb4_bin)
    OR (perms = 'business:topicnode:remove' AND remark = '探店日批5:删店位节点。已有玩家进店记录的节点删掉会让那些记录指向空,删前先看漏斗' COLLATE utf8mb4_bin)
    OR (perms = 'business:roamRevisitReport:list' AND remark = '探店日5-5只读报表权限；仅允许查询SHOP_REVISIT/ROAM_REVISIT 7/30/90日观察窗' COLLATE utf8mb4_bin)
    OR (perms = 'business:withdrawal:cooldown-config' AND remark = '探店日A3：改冷静期小时数的专用入口（PlayerWithdrawalCooldownController#rewrite）；前值/后值/理由落 player_withdrawal_cooldown_config_audit，只影响新事件' COLLATE utf8mb4_bin)
    OR (perms = 'business:clubpayee:verify' AND remark = '探店日A4：财务一次审核；不设 maker/checker，理由见 ClubCorporatePayeeServiceImpl.verify；俱乐部侧不得自审由 app 侧零 payee 入口保证' COLLATE utf8mb4_bin)
    OR (perms = 'business:clubpayee:payout' AND remark = '探店日A4：受控完整解密，仅付款登记环节使用；与审核权限互斥分派（见本文件末的角色分派与回读）' COLLATE utf8mb4_bin);

-- ======================= ③ 定时任务:竞猜 → 预测(调用目标不变) =======================

UPDATE sys_job
   SET job_name = '预测轮次超时作废',
       update_by = 'migration_20261104',
       update_time = NOW()
 WHERE invoke_target = 'predictVoidExpiredTask.run()'
   AND job_name = '竞猜轮次超时作废' COLLATE utf8mb4_bin;

UPDATE sys_job
   SET remark = '商家超过48小时不给预测答案的轮次作废且不发奖;自收敛任务,无过期轮次时空跑',
       update_by = 'migration_20261104',
       update_time = NOW()
 WHERE invoke_target = 'predictVoidExpiredTask.run()'
   AND remark = '商家超过48小时不给竞猜答案的轮次作废且不发奖;自收敛任务,无过期轮次时空跑' COLLATE utf8mb4_bin;

-- runner: assert=scalar-true
SELECT COUNT(*) = 0 AS seeded_job_text_renamed
FROM sys_job
WHERE invoke_target = 'predictVoidExpiredTask.run()'
  AND (job_name = '竞猜轮次超时作废' COLLATE utf8mb4_bin OR remark = '商家超过48小时不给竞猜答案的轮次作废且不发奖;自收敛任务,无过期轮次时空跑' COLLATE utf8mb4_bin);
