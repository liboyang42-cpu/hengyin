-- 官方模板「预制人生 · 四站试玩版」(990059)文案修订(2026-11-01 上线前生产走查,业主:都要修)
-- 走查实测:套用后玩家看到内部编号「PL01-」、站名「hello world」、简介写「内部试玩版,不进公开列表」(它在公开货架上);
--           第二章故事流以站点开头、没有开场文字 ⇒ 编辑器判「缺剧情」,套用的人发布不了;第三章有吸烟情节,AI 预检拦截。
-- 只改仍是种子原文的行(WHERE 带原文),人工改过的不覆盖;可重复执行。
-- 执行方式:走 chengyin-db-migrate,只读预检后确认。

-- 1) 玩法标题去掉内部编号
UPDATE cms_member_template SET title = '出生登记:写下你叫什么' WHERE id = 133 AND title = 'PL01-出生登记:写下你叫什么';
UPDATE cms_member_template SET title = '换乘通道:三题与六百步' WHERE id = 134 AND title = 'PL02-换乘通道:三题与六百步';
UPDATE cms_member_template SET title = '会议出口:打出那句「收到」' WHERE id = 135 AND title = 'PL03-会议出口:hello world';
UPDATE cms_member_template SET title = '被切开的天:拍一张留一句' WHERE id = 136 AND title = 'PL04-被切开的天:拍一张留一句';
UPDATE cms_member_template SET title = '边界:终章三选一' WHERE id = 137 AND title = 'PL05-边界:终章三选一';

-- 2) 第三站限时打字的目标:测试串 hello world 换成本主题的母题「收到」
UPDATE cms_member_template SET advanced_config_json = REPLACE(advanced_config_json, '"target":"hello world"', '"target":"收到"')
 WHERE id = 135 AND advanced_config_json LIKE '%"target":"hello world"%';
UPDATE cms_topic_node SET description = '在写字楼外的公共人行道过一道检定,再限时打出那句「收到」。'
 WHERE id = 194 AND topic_id = 990059 AND description = '在写字楼外的公共人行道过一道检定,再限时打出 hello world。';

-- 3) 第二章补开场文字(故事流不能以站点开头)
UPDATE cms_topic_chapter
   SET blocks = CAST(JSON_ARRAY_INSERT(CAST(blocks AS JSON), '$[0]',
       JSON_OBJECT('type', 'text', 'content', '早高峰的人民广场站,广播一遍遍念着同一个动词。你跟着人流往前走,没人问你要去哪儿。')) AS CHAR)
 WHERE id = 212 AND topic_id = 990059 AND JSON_UNQUOTE(JSON_EXTRACT(blocks, '$[0].type')) = 'node';

-- 4) 第三章去掉吸烟情节
UPDATE cms_topic_chapter SET blocks = REPLACE(blocks, '小周点了一根烟。', '小周靠在窗边。')
 WHERE id = 213 AND topic_id = 990059 AND blocks LIKE '%小周点了一根烟。%';

-- 5) 简介去掉「内部试玩版,不进公开列表」(它在公开货架上)
UPDATE cms_topic SET description = REPLACE(description, '本主题是内部试玩版,不进公开列表;持 0 元自玩通行证即可开走。', '持 0 元自玩通行证即可开走。')
 WHERE id = 990059 AND create_by = 'seed_prefab_life_20260918' AND description LIKE '%本主题是内部试玩版%';

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM cms_topic WHERE id = 990059) = 0
    OR ((SELECT COUNT(*) FROM cms_member_template WHERE id BETWEEN 133 AND 137 AND title LIKE 'PL0%') = 0
        AND (SELECT COUNT(*) FROM cms_member_template WHERE id = 135 AND advanced_config_json LIKE '%hello world%') = 0
        AND (SELECT JSON_UNQUOTE(JSON_EXTRACT(blocks, '$[0].type')) FROM cms_topic_chapter WHERE id = 212) = 'text'
        AND (SELECT COUNT(*) FROM cms_topic_chapter WHERE id = 213 AND blocks LIKE '%一根烟%') = 0
        AND (SELECT COUNT(*) FROM cms_topic WHERE id = 990059 AND description LIKE '%内部试玩版%') = 0) AS prefab_life_copy_fixed;
