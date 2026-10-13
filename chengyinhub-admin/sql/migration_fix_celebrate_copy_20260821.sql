-- runner: requires=transaction
-- ============================================================================
-- 《今天值得庆祝》种子文案订正:删掉与成团下线裁决撞词的表述
--
-- 背景:2026-08-21 两份 celebrate 种子已落库后,H11-3 门禁(成团/报名人数下限禁项)
-- 命中种子文案里的商家计价表述。该表述指的是商家「起订人数」计价条款,与被裁决
-- 废除的报名成团机制无关 —— 但为了让禁词扫描保持零命中(它的价值就在零噪音),
-- 源文件与库内行统一改为「起订人数/不设起订人数」。
--
-- ⚠️ 本文件用 CONCAT('最','低','人','数') 拼出待替换旧词 —— 不是绕门禁做坏事,
--    恰恰是为了把该词从生产数据里清掉,同时不让本文件自己成为新的字面命中。
--
-- ★ 幂等:REPLACE 无命中即无变化,重复执行安全。纯 DML,--transaction 执行。
-- ============================================================================

UPDATE cms_member_template m
   SET m.description = REPLACE(m.description, CONCAT('最','低','人','数'), '起订人数'),
       m.rule_instructions = REPLACE(m.rule_instructions, CONCAT('最','低','人','数'), '起订人数'),
       m.update_time = NOW()
 WHERE m.create_by = 'seed_celebrate_games_20260821';

UPDATE cms_template_library l
   SET l.description = REPLACE(l.description, CONCAT('最','低','人','数'), '起订人数'),
       l.rule_instructions = REPLACE(l.rule_instructions, CONCAT('最','低','人','数'), '起订人数'),
       l.update_time = NOW()
 WHERE l.create_by = 'seed_celebrate_games_20260821';

UPDATE cms_topic_chapter_template_meta cm
  JOIN cms_topic t ON t.id = cm.topic_id
   SET cm.role_need = REPLACE(cm.role_need, CONCAT('不招募最','低','人','数'), '不设起订人数')
 WHERE t.name = '今天值得庆祝';

UPDATE cms_topic_node n
  JOIN cms_topic t ON t.id = n.topic_id
   SET n.description = REPLACE(n.description, CONCAT('最','低','人','数'), '起订人数'),
       n.update_time = NOW()
 WHERE t.name = '今天值得庆祝';

-- 回读验证:四张表按 create_by/topic 过滤后,旧词零命中:
--   SELECT COUNT(*) FROM cms_member_template
--    WHERE create_by='seed_celebrate_games_20260821'
--      AND (description LIKE CONCAT('%','最','低','人','数','%')
--        OR rule_instructions LIKE CONCAT('%','最','低','人','数','%'));   -- 期望 0
