-- ============================================================================
-- 具名玩法包种子:10 套官方主题模板(商家「选包→改参数→发布」的内容层)
--
-- 设计真源:
--   ~/Documents/城瘾/文档整理_20260711/04-未开始/具名玩法包_10个商家游戏模板与AI填充施工方案_20260820.md
--
-- 结构(每包一致):
--   cms_topic(is_template=1, member_id=100000, product_type=2 自由探索)
--     + product_role CONTENT/PLATFORM 槽(来源栏显示「官方」)
--     + 1 个 cms_topic_chapter(故事流 blocks 在节点落库后回填)
--     + N 个 cms_member_template(member_id=100000, status=1, draft_status=1 —— W4 可引用态)
--     + N 个 cms_topic_node(template_id 指向上面的模板;无坐标,商家复制后自己选点)
--
-- ★ user_status=0(刻意偏离 20260726 先例的 user_status=1):
--   玩家广场列表过滤 status=1 AND user_status=1,但模板货架查询(selectTopicTemplateList)
--   只看 del_flag=0 AND is_template=1 AND status=1 —— 置 0 让 10 个无坐标模板只进货架、
--   不污染玩家广场。/use 复制与 /info 详情均不校验 user_status。
--
-- ★ 机制降级说明(诚实版,文案不承诺未建成的机制):
--   ⑦三连快答 无服务端计时(TIMER 未建) —— 文案只说「一口气」,不承诺限时判定;
--   ⑧集章巡店 通关=全部完成(N/M 集章判定未建);
--   ⑨岔路口的选择 为线性剧情+选择题(分支引擎未建);
--   ⑩三个盲盒 三个任务全可见(随机下发未建)。
--   机制落地后由 AI 填充链路升级文案,勿在此提前吹。
--
-- ⚠️ 前置依赖:
--   1) 前置依赖:migration_topic_template_20260707.sql(cms_topic.is_template 列)已落库;
--   2) 前置依赖:migration_product_role_20260716.sql(product_role 表)、migration_terms_mode_20260716.sql
--      前置依赖:migration_m7_xp_budget.sql(节点/模板 xp_value)已落库;
--   3) 前置依赖:migration_level_editor_fields.sql + migration_level_editor_story_photo_review.sql
--      (story/hint/draft/photo_review 字段)已落库;
--   4) 同 PR 的 copyNodes 模板归属深拷贝修复([W4闭环])需随后端部署上线 ——
--      种子先落库不报错,但商家 /use 后在编辑器保存会被 W4 拦,故落库与部署应同批完成。
--
-- ⚠️ 本文件只准备,不执行。生产库写操作由总控在用户点头后执行(chengyin-db-migrate)。
-- runner: requires=transaction
-- W4 后端部署状态必须由外部发布闸回读生产 jar 自报 SHA；SQL 不猜、不伪造该证据。
--
-- ★ 幂等:所有 INSERT 均为 INSERT ... SELECT ... WHERE NOT EXISTS;
--   幂等键 = cms_topic.name / (member_id=100000, cms_member_template.title) / (topic_id, node.name)。
--   blocks 回填 UPDATE 结果确定,重复执行无副作用。
-- ============================================================================


-- ======================= 第零步:事务内写前 fail-closed guard =======================
-- 固定主键 1 已占用；任一同名对象冲突再写 1，触发 duplicate key 并回滚。
CREATE TEMPORARY TABLE seed_playpack_guard (
    id INT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;
INSERT INTO seed_playpack_guard (id) VALUES (1);

-- 逐节点真源：写后必须逐行命中同一 topic/chapter/node/template/vm，聚合数量不能代替绑定关系。
CREATE TEMPORARY TABLE seed_playpack_expected (
    topic_name VARCHAR(64) NOT NULL,
    chapter_name VARCHAR(64) NOT NULL,
    node_name VARCHAR(64) NOT NULL,
    template_title VARCHAR(64) NOT NULL,
    validation_method INT NOT NULL,
    blocks_length INT NOT NULL,
    PRIMARY KEY (topic_name, node_name)
) ENGINE=InnoDB;
INSERT INTO seed_playpack_expected
    (topic_name, chapter_name, node_name, template_title, validation_method, blocks_length)
VALUES
    ('隐藏菜单', '菜单背面', '解开暗号', '玩法包·隐藏菜单暗号', 1, 3),
    ('店内寻宝', '寻宝图', '第一件:门口', '玩法包·寻宝第一件', 3, 5),
    ('店内寻宝', '寻宝图', '第二件:深处', '玩法包·寻宝第二件', 3, 5),
    ('店内寻宝', '寻宝图', '第三件:收银台', '玩法包·寻宝第三件', 3, 5),
    ('拍照挑战', '取景框', '按题拍一张', '玩法包·构图题', 2, 2),
    ('神秘店员的暗号', '接头', '找到知情人', '玩法包·接头回令', 1, 3),
    ('三步解谜', '失物招领', '第一步:失物', '玩法包·解谜第一步', 3, 6),
    ('三步解谜', '失物招领', '第二步:线索', '玩法包·解谜第二步', 1, 6),
    ('三步解谜', '失物招领', '第三步:结局', '玩法包·解谜第三步', 3, 6),
    ('听音辨物', '闭眼十秒', '听声辨位', '玩法包·店内声音题', 3, 2),
    ('三连快答', '快问快答', '第一问', '玩法包·快答第一题', 3, 4),
    ('三连快答', '快问快答', '第二问', '玩法包·快答第二题', 3, 4),
    ('三连快答', '快问快答', '第三问', '玩法包·快答第三题', 3, 4),
    ('集章巡店', '巡店地图', '印章·东', '玩法包·印章东', 4, 5),
    ('集章巡店', '巡店地图', '印章·南', '玩法包·印章南', 4, 5),
    ('集章巡店', '巡店地图', '印章·西', '玩法包·印章西', 4, 5),
    ('集章巡店', '巡店地图', '印章·北', '玩法包·印章北', 4, 5),
    ('岔路口的选择', '两封信', '岔口:两封信', '玩法包·岔口抉择', 3, 4),
    ('岔路口的选择', '两封信', '结局:补上那句话', '玩法包·结局由你写', 1, 4),
    ('三个盲盒', '拆袋子', '盲盒一:观察', '玩法包·盲盒之观察', 3, 4),
    ('三个盲盒', '拆袋子', '盲盒二:拍照', '玩法包·盲盒之拍照', 2, 4),
    ('三个盲盒', '拆袋子', '盲盒三:开口', '玩法包·盲盒之开口', 1, 4);

-- 同一幂等键只允许不存在，或恰好一条且确由本迁移创建；拒绝借用同名业务数据。
INSERT INTO seed_playpack_guard (id)
SELECT 1
 WHERE EXISTS (
           SELECT 1
             FROM cms_topic
            WHERE member_id = 100000 AND del_flag = 0
              AND name IN ('隐藏菜单','店内寻宝','拍照挑战','神秘店员的暗号','三步解谜',
                           '听音辨物','三连快答','集章巡店','岔路口的选择','三个盲盒')
            GROUP BY name
           HAVING COUNT(*) <> 1 OR MIN(create_by) <> 'seed_playpack_20260820'
       )
    OR EXISTS (
           SELECT 1
             FROM cms_member_template
            WHERE member_id = 100000 AND del_flag = 0 AND title LIKE '玩法包·%'
            GROUP BY title
           HAVING COUNT(*) <> 1 OR MIN(create_by) <> 'seed_playpack_20260820'
       );


-- ======================= 第一步:只读预检 =======================
-- 1) is_template 列是否存在(期望 1 行):
--   SELECT COLUMN_NAME FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cms_topic' AND COLUMN_NAME = 'is_template';
--
-- 2) 现状(本种子未灌时应为 0 行):
--   SELECT id, name, is_template, user_status FROM cms_topic
--    WHERE name IN ('隐藏菜单','店内寻宝','拍照挑战','神秘店员的暗号','三步解谜',
--                   '听音辨物','三连快答','集章巡店','岔路口的选择','三个盲盒')
--      AND del_flag = 0;
--
-- 3) 模板标题冲突预检(应为 0 行;有行说明 100000 号下已有同名模板,需先改名):
--   SELECT id, title FROM cms_member_template
--    WHERE member_id = 100000 AND del_flag = 0 AND title LIKE '玩法包·%';


-- ============================================================================
-- 包① 隐藏菜单(咖啡店/酒吧/茶馆 · 1 节点 · 文字暗号)
-- ============================================================================

INSERT INTO cms_topic (member_id, topic_type, product_type, name, subtitle, description,
                       address_name, total_time, location_count, is_template,
                       status, user_status, del_flag, create_by, create_time)
SELECT 100000, 1, 2,
       '隐藏菜单',
       '菜单上没有的那一款,只给解开暗号的人',
       '给你的店加一份「隐藏菜单」:玩家到店扫码,解开一道藏在店内细节里的暗号题,答对即揭示隐藏款名称,向店员报出即可点单。适合咖啡店、酒吧、茶馆。你只需要决定哪款产品是隐藏款,并把暗号答案换成你店里的真实细节。',
       '单店(复制后请在编辑器为节点选择你的店铺位置)',
       10, 1, 1,
       1, 0, 0, 'seed_playpack_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_topic WHERE name = '隐藏菜单' AND member_id = 100000 AND del_flag = 0);

INSERT INTO product_role (topic_id, slot, entity_type, entity_id, status)
SELECT t.id, 'CONTENT', 'PLATFORM', NULL, 1
  FROM cms_topic t
 WHERE t.name = '隐藏菜单' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM product_role r
                    WHERE r.topic_id = t.id AND r.slot = 'CONTENT' AND r.status = 1);

INSERT INTO cms_topic_chapter (topic_id, name, description, sort_id, location_count, template_count,
                               required, recruit_enabled, max_merchant, terms_mode,
                               create_by, create_time)
SELECT t.id, '菜单背面', '这家店有一份不在菜单上的味道。', 1, 1, 1,
       1, 0, 0, 'PERK', 'seed_playpack_20260820', NOW()
  FROM cms_topic t
 WHERE t.name = '隐藏菜单' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_chapter c
                    WHERE c.topic_id = t.id AND c.name = '菜单背面');

INSERT INTO cms_member_template (member_id, title, description, players, usage_location, duration,
                                 difficulty, rule_instructions, validation_method,
                                 question_name, question_answer, hint1, hint2, answer_reveal,
                                 feedback_text, story_text, xp_value,
                                 draft_status, status, publish_status, check_status, is_sync,
                                 del_flag, create_by, create_time)
SELECT 100000, '玩法包·隐藏菜单暗号',
       '文字暗号题:答案藏在店内细节里,答对揭示隐藏菜单。',
       '1人', '店内', 5, '简单',
       '商家改造指南:①把题面里的「细节」换成你店里真实存在的东西(墙上的画/某本书/一句标语);②把答案换成对应的词;③在「答案揭示」里写上你的隐藏款名称和点单方式;④建议搭配一张优惠券作为通关奖励。',
       1,
       '吧台后的墙上挂着一样东西,它出现的年份就是暗号。仔细看看,把那个年份填进来。',
       '1997',
       '暗号不在菜单上,在墙上。',
       '找一件带数字的老物件。',
       '答对了。向店员轻声说出「我要菜单背面的那一杯」,你会得到一份只属于解谜者的隐藏款。',
       '再看看店里,暗号一定在某个不起眼的角落。',
       '每家店都有一份不写在菜单上的味道,只留给愿意多看一眼的人。',
       10,
       1, 1, 0, 1, 0,
       0, 'seed_playpack_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_member_template
                    WHERE member_id = 100000 AND title = '玩法包·隐藏菜单暗号' AND del_flag = 0);

INSERT INTO cms_topic_node (topic_id, chapter_id, name, description, template_id, checkin_code,
                            status, sort_id, xp_value, create_by, create_time)
SELECT t.id, c.id, '解开暗号', '扫店内二维码进店,解开藏在细节里的暗号。',
       tpl.id, REPLACE(UUID(), '-', ''), 1, 1, 10, 'seed_playpack_20260820', NOW()
  FROM cms_topic t
  JOIN cms_topic_chapter c   ON c.topic_id = t.id AND c.name = '菜单背面'
  JOIN cms_member_template tpl ON tpl.member_id = 100000 AND tpl.title = '玩法包·隐藏菜单暗号' AND tpl.del_flag = 0
 WHERE t.name = '隐藏菜单' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n WHERE n.topic_id = t.id AND n.name = '解开暗号');

UPDATE cms_topic_chapter c
   SET c.blocks = CONCAT(
       '[{"type":"text","content":"这家店的菜单只印了一半。另一半,老板只讲给解开暗号的人。"},',
       '{"type":"node","nodeId":',
       (SELECT n.id FROM cms_topic_node n WHERE n.topic_id = c.topic_id AND n.name = '解开暗号'),
       '},{"type":"text","content":"隐藏款到手之后,别声张——下一位解谜者还蒙在鼓里。"}]')
 WHERE c.name = '菜单背面'
   AND c.topic_id = (SELECT t.id FROM cms_topic t WHERE t.name = '隐藏菜单' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0);


-- ============================================================================
-- 包② 店内寻宝(书店/杂货/展览空间 · 3 节点 · 选项题)
-- ============================================================================

INSERT INTO cms_topic (member_id, topic_type, product_type, name, subtitle, description,
                       address_name, total_time, location_count, is_template,
                       status, user_status, del_flag, create_by, create_time)
SELECT 100000, 1, 2,
       '店内寻宝',
       '三件被藏起来的小东西,和一份找齐后的奖励',
       '把你的店变成一张寻宝图:玩家按提示在店内找到三处细节,每处答对一道选项题,集齐三处即通关。适合书店、杂货铺、展览空间。复制后把三道题的「宝物」换成你店里真实的角落。',
       '单店(复制后请在编辑器为节点选择你的店铺位置)',
       20, 3, 1,
       1, 0, 0, 'seed_playpack_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_topic WHERE name = '店内寻宝' AND member_id = 100000 AND del_flag = 0);

INSERT INTO product_role (topic_id, slot, entity_type, entity_id, status)
SELECT t.id, 'CONTENT', 'PLATFORM', NULL, 1
  FROM cms_topic t
 WHERE t.name = '店内寻宝' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM product_role r
                    WHERE r.topic_id = t.id AND r.slot = 'CONTENT' AND r.status = 1);

INSERT INTO cms_topic_chapter (topic_id, name, description, sort_id, location_count, template_count,
                               required, recruit_enabled, max_merchant, terms_mode,
                               create_by, create_time)
SELECT t.id, '寻宝图', '三件小东西藏在店里,按线索一件件找。', 1, 3, 3,
       1, 0, 0, 'PERK', 'seed_playpack_20260820', NOW()
  FROM cms_topic t
 WHERE t.name = '店内寻宝' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_chapter c
                    WHERE c.topic_id = t.id AND c.name = '寻宝图');

INSERT INTO cms_member_template (member_id, title, description, players, usage_location, duration,
                                 difficulty, rule_instructions, validation_method,
                                 question_name, question_a, question_b, question_c, question_d,
                                 correct_answer, hint1, hint2, answer_reveal, feedback_text, xp_value,
                                 draft_status, status, publish_status, check_status, is_sync,
                                 del_flag, create_by, create_time)
SELECT 100000, '玩法包·寻宝第一件',
       '选项题:第一处宝物,入口附近的细节。',
       '1人', '店内', 5, '简单',
       '商家改造指南:把题面与选项换成你店入口处一件真实的小物件(招牌下的摆件/门口的植物/一张老照片),正确项设为真实答案。',
       3,
       '进门先别急着往里走。门口那件最不像商品的东西,是什么颜色?',
       '红色', '蓝色', '绿色', '黄色',
       'A',
       '它每天迎客,但从不标价。',
       '抬头,或者低头。',
       '第一件宝物到手。它在这里的时间,比大多数商品都久。',
       '再找找,它真的不是商品。',
       10,
       1, 1, 0, 1, 0,
       0, 'seed_playpack_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_member_template
                    WHERE member_id = 100000 AND title = '玩法包·寻宝第一件' AND del_flag = 0);

INSERT INTO cms_member_template (member_id, title, description, players, usage_location, duration,
                                 difficulty, rule_instructions, validation_method,
                                 question_name, question_a, question_b, question_c, question_d,
                                 correct_answer, hint1, hint2, answer_reveal, feedback_text, xp_value,
                                 draft_status, status, publish_status, check_status, is_sync,
                                 del_flag, create_by, create_time)
SELECT 100000, '玩法包·寻宝第二件',
       '选项题:第二处宝物,店铺深处的细节。',
       '1人', '店内', 5, '简单',
       '商家改造指南:把题面与选项换成店铺深处一件有故事的东西(某本书/一件旧工具/一张手写卡片),正确项设为真实答案。',
       3,
       '往店里走。有一样东西被放在最显眼的位置,却最少有人碰。它上面写着一个词,是哪个?',
       '欢迎', '非卖品', '样品', '收藏',
       'B',
       '显眼,但没人碰。',
       '它在提醒你:不是所有东西都待价而沽。',
       '第二件宝物到手。老板说,这件东西谁问卖不卖,就跟谁多聊十分钟。',
       '不对,再仔细看看那个词。',
       10,
       1, 1, 0, 1, 0,
       0, 'seed_playpack_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_member_template
                    WHERE member_id = 100000 AND title = '玩法包·寻宝第二件' AND del_flag = 0);

INSERT INTO cms_member_template (member_id, title, description, players, usage_location, duration,
                                 difficulty, rule_instructions, validation_method,
                                 question_name, question_a, question_b, question_c, question_d,
                                 correct_answer, hint1, hint2, answer_reveal, feedback_text, xp_value,
                                 draft_status, status, publish_status, check_status, is_sync,
                                 del_flag, create_by, create_time)
SELECT 100000, '玩法包·寻宝第三件',
       '选项题:第三处宝物,收银台附近的细节。',
       '1人', '店内', 5, '简单',
       '商家改造指南:把题面与选项换成收银台附近一件常被忽略的东西,正确项设为真实答案;通关奖励建议配一张店内优惠券。',
       3,
       '最后一件宝物离结账的地方最近。收银台上有一样不收钱的东西,它是给谁准备的?',
       '给老板', '给店员', '给客人', '给猫',
       'C',
       '它免费,而且常常被拿走。',
       '你结账的时候顺手就能碰到。',
       '三件宝物集齐!把通关画面出示给店员,领取属于寻宝者的奖励。',
       '再想想,它不收钱。',
       10,
       1, 1, 0, 1, 0,
       0, 'seed_playpack_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_member_template
                    WHERE member_id = 100000 AND title = '玩法包·寻宝第三件' AND del_flag = 0);

INSERT INTO cms_topic_node (topic_id, chapter_id, name, description, template_id, checkin_code,
                            status, sort_id, xp_value, create_by, create_time)
SELECT t.id, c.id, '第一件:门口', '入口附近的第一处线索。',
       tpl.id, REPLACE(UUID(), '-', ''), 1, 1, 10, 'seed_playpack_20260820', NOW()
  FROM cms_topic t
  JOIN cms_topic_chapter c ON c.topic_id = t.id AND c.name = '寻宝图'
  JOIN cms_member_template tpl ON tpl.member_id = 100000 AND tpl.title = '玩法包·寻宝第一件' AND tpl.del_flag = 0
 WHERE t.name = '店内寻宝' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n WHERE n.topic_id = t.id AND n.name = '第一件:门口');

INSERT INTO cms_topic_node (topic_id, chapter_id, name, description, template_id, checkin_code,
                            status, sort_id, xp_value, create_by, create_time)
SELECT t.id, c.id, '第二件:深处', '店铺深处的第二处线索。',
       tpl.id, REPLACE(UUID(), '-', ''), 1, 2, 10, 'seed_playpack_20260820', NOW()
  FROM cms_topic t
  JOIN cms_topic_chapter c ON c.topic_id = t.id AND c.name = '寻宝图'
  JOIN cms_member_template tpl ON tpl.member_id = 100000 AND tpl.title = '玩法包·寻宝第二件' AND tpl.del_flag = 0
 WHERE t.name = '店内寻宝' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n WHERE n.topic_id = t.id AND n.name = '第二件:深处');

INSERT INTO cms_topic_node (topic_id, chapter_id, name, description, template_id, checkin_code,
                            status, sort_id, xp_value, create_by, create_time)
SELECT t.id, c.id, '第三件:收银台', '收银台附近的最后一处线索。',
       tpl.id, REPLACE(UUID(), '-', ''), 1, 3, 10, 'seed_playpack_20260820', NOW()
  FROM cms_topic t
  JOIN cms_topic_chapter c ON c.topic_id = t.id AND c.name = '寻宝图'
  JOIN cms_member_template tpl ON tpl.member_id = 100000 AND tpl.title = '玩法包·寻宝第三件' AND tpl.del_flag = 0
 WHERE t.name = '店内寻宝' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n WHERE n.topic_id = t.id AND n.name = '第三件:收银台');

UPDATE cms_topic_chapter c
   SET c.blocks = CONCAT(
       '[{"type":"text","content":"这家店里藏了三件小东西。它们不卖,但找到的人会得到一份奖励。"},',
       '{"type":"node","nodeId":',
       (SELECT n.id FROM cms_topic_node n WHERE n.topic_id = c.topic_id AND n.name = '第一件:门口'),
       '},{"type":"node","nodeId":',
       (SELECT n.id FROM cms_topic_node n WHERE n.topic_id = c.topic_id AND n.name = '第二件:深处'),
       '},{"type":"node","nodeId":',
       (SELECT n.id FROM cms_topic_node n WHERE n.topic_id = c.topic_id AND n.name = '第三件:收银台'),
       '},{"type":"text","content":"寻宝完成。下次再来,东西可能会换地方。"}]')
 WHERE c.name = '寻宝图'
   AND c.topic_id = (SELECT t.id FROM cms_topic t WHERE t.name = '店内寻宝' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0);


-- ============================================================================
-- 包③ 拍照挑战(网红店/景观位商家 · 1 节点 · 拍照审核后置)
-- ============================================================================

INSERT INTO cms_topic (member_id, topic_type, product_type, name, subtitle, description,
                       address_name, total_time, location_count, is_template,
                       status, user_status, del_flag, create_by, create_time)
SELECT 100000, 1, 2,
       '拍照挑战',
       '按店里的构图题拍一张,照片就是你的通关凭证',
       '给玩家出一道拍照构图题:到店按要求拍摄并上传,即完成打卡。照片审核后置,不阻断体验。玩家拍的每一张照片,都是你的店在城瘾里的展示素材。复制后把构图要求换成你店里最上镜的角落。',
       '单店(复制后请在编辑器为节点选择你的店铺位置)',
       10, 1, 1,
       1, 0, 0, 'seed_playpack_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_topic WHERE name = '拍照挑战' AND member_id = 100000 AND del_flag = 0);

INSERT INTO product_role (topic_id, slot, entity_type, entity_id, status)
SELECT t.id, 'CONTENT', 'PLATFORM', NULL, 1
  FROM cms_topic t
 WHERE t.name = '拍照挑战' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM product_role r
                    WHERE r.topic_id = t.id AND r.slot = 'CONTENT' AND r.status = 1);

INSERT INTO cms_topic_chapter (topic_id, name, description, sort_id, location_count, template_count,
                               required, recruit_enabled, max_merchant, terms_mode,
                               create_by, create_time)
SELECT t.id, '取景框', '这家店有一个最上镜的角度,等你找到它。', 1, 1, 1,
       1, 0, 0, 'PERK', 'seed_playpack_20260820', NOW()
  FROM cms_topic t
 WHERE t.name = '拍照挑战' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_chapter c
                    WHERE c.topic_id = t.id AND c.name = '取景框');

INSERT INTO cms_member_template (member_id, title, description, players, usage_location, duration,
                                 difficulty, rule_instructions, validation_method,
                                 photo_require_desc, photo_review, feedback_text, story_text, xp_value,
                                 draft_status, status, publish_status, check_status, is_sync,
                                 del_flag, create_by, create_time)
SELECT 100000, '玩法包·构图题',
       '拍照打卡:按构图要求拍摄上传,审核后置。',
       '1-2人', '店内', 5, '简单',
       '商家改造指南:把构图要求换成你店里真实的机位(招牌/窗边/某面墙),写清楚「拍什么+怎么拍」;拍照完成不自动发真金券,如需发券请走扫码核销或后台审核。',
       2,
       '三选一,拍出其中一张:①和门口招牌的合影,要求招牌完整入镜;②店内你最喜欢的一个角落,横构图;③把你点的东西和店里任意一件老物件摆在一起拍。',
       1,
       '收到!照片会出现在你的探索记录里,拍得好的还会被店主翻牌。',
       '有的店适合拍,有的店适合被拍。这家店觉得自己两者都行,不服来试。',
       10,
       1, 1, 0, 1, 0,
       0, 'seed_playpack_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_member_template
                    WHERE member_id = 100000 AND title = '玩法包·构图题' AND del_flag = 0);

INSERT INTO cms_topic_node (topic_id, chapter_id, name, description, template_id, checkin_code,
                            status, sort_id, xp_value, create_by, create_time)
SELECT t.id, c.id, '按题拍一张', '扫码进店,按构图题拍摄并上传。',
       tpl.id, REPLACE(UUID(), '-', ''), 1, 1, 10, 'seed_playpack_20260820', NOW()
  FROM cms_topic t
  JOIN cms_topic_chapter c ON c.topic_id = t.id AND c.name = '取景框'
  JOIN cms_member_template tpl ON tpl.member_id = 100000 AND tpl.title = '玩法包·构图题' AND tpl.del_flag = 0
 WHERE t.name = '拍照挑战' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n WHERE n.topic_id = t.id AND n.name = '按题拍一张');

UPDATE cms_topic_chapter c
   SET c.blocks = CONCAT(
       '[{"type":"text","content":"这不是普通的打卡。这是一道构图题,店就是你的取景框。"},',
       '{"type":"node","nodeId":',
       (SELECT n.id FROM cms_topic_node n WHERE n.topic_id = c.topic_id AND n.name = '按题拍一张'),
       '}]')
 WHERE c.name = '取景框'
   AND c.topic_id = (SELECT t.id FROM cms_topic t WHERE t.name = '拍照挑战' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0);


-- ============================================================================
-- 包④ 神秘店员的暗号(有店员配合意愿的店 · 1 节点 · 文字回令)
-- ============================================================================

INSERT INTO cms_topic (member_id, topic_type, product_type, name, subtitle, description,
                       address_name, total_time, location_count, is_template,
                       status, user_status, del_flag, create_by, create_time)
SELECT 100000, 1, 2,
       '神秘店员的暗号',
       '对店员说出暗号,拿到回令才算接头成功',
       '一场需要真人配合的接头游戏:玩家向店员说出暗号,店员回一个回令,玩家把回令填进来即通关。真人互动是这个玩法的全部记忆点。复制后把暗号和回令换成你们自己的,并把「店员剧本」交给当班同事。',
       '单店(复制后请在编辑器为节点选择你的店铺位置)',
       10, 1, 1,
       1, 0, 0, 'seed_playpack_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_topic WHERE name = '神秘店员的暗号' AND member_id = 100000 AND del_flag = 0);

INSERT INTO product_role (topic_id, slot, entity_type, entity_id, status)
SELECT t.id, 'CONTENT', 'PLATFORM', NULL, 1
  FROM cms_topic t
 WHERE t.name = '神秘店员的暗号' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM product_role r
                    WHERE r.topic_id = t.id AND r.slot = 'CONTENT' AND r.status = 1);

INSERT INTO cms_topic_chapter (topic_id, name, description, sort_id, location_count, template_count,
                               required, recruit_enabled, max_merchant, terms_mode,
                               create_by, create_time)
SELECT t.id, '接头', '店里有一位知情人,只认暗号不认脸。', 1, 1, 1,
       1, 0, 0, 'PERK', 'seed_playpack_20260820', NOW()
  FROM cms_topic t
 WHERE t.name = '神秘店员的暗号' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_chapter c
                    WHERE c.topic_id = t.id AND c.name = '接头');

INSERT INTO cms_member_template (member_id, title, description, players, usage_location, duration,
                                 difficulty, rule_instructions, validation_method,
                                 question_name, question_answer, hint1, hint2, answer_reveal,
                                 feedback_text, story_text, xp_value,
                                 draft_status, status, publish_status, check_status, is_sync,
                                 del_flag, create_by, create_time)
SELECT 100000, '玩法包·接头回令',
       '文字回令:向店员说暗号,得到回令后填入。',
       '1人', '店内', 5, '简单',
       '店员一页纸剧本(请交给当班同事):①客人对你说「今晚的月亮不错」;②你回答「可惜看不见星星」,不要多解释;③如客人一脸茫然,那只是普通客人,正常接待。商家改造:把暗号/回令换成你们自己的一对短句,回令即本题答案。',
       1,
       '走到吧台,对店员说出暗号:「今晚的月亮不错」。把店员回给你的那句话,一字不差地填进来。',
       '可惜看不见星星',
       '说暗号时不必压低声音,但压低声音更有味道。',
       '店员回你的是一句完整的话,别只填一半。',
       '接头成功。从现在起,你是这家店名单上的人了——下次进门,店员可能会先认出你。',
       '回令不对。要么你找错了人,要么店员今天心情复杂,再试一次。',
       '每家店都需要一点秘密成分。这家店的秘密成分,是吧台后面那位看起来最忙的人。',
       10,
       1, 1, 0, 1, 0,
       0, 'seed_playpack_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_member_template
                    WHERE member_id = 100000 AND title = '玩法包·接头回令' AND del_flag = 0);

INSERT INTO cms_topic_node (topic_id, chapter_id, name, description, template_id, checkin_code,
                            status, sort_id, xp_value, create_by, create_time)
SELECT t.id, c.id, '找到知情人', '扫码进店,向店员说出暗号,取得回令。',
       tpl.id, REPLACE(UUID(), '-', ''), 1, 1, 10, 'seed_playpack_20260820', NOW()
  FROM cms_topic t
  JOIN cms_topic_chapter c ON c.topic_id = t.id AND c.name = '接头'
  JOIN cms_member_template tpl ON tpl.member_id = 100000 AND tpl.title = '玩法包·接头回令' AND tpl.del_flag = 0
 WHERE t.name = '神秘店员的暗号' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n WHERE n.topic_id = t.id AND n.name = '找到知情人');

UPDATE cms_topic_chapter c
   SET c.blocks = CONCAT(
       '[{"type":"text","content":"情报显示,这家店里有一位知情人。他不认脸,只认暗号。"},',
       '{"type":"node","nodeId":',
       (SELECT n.id FROM cms_topic_node n WHERE n.topic_id = c.topic_id AND n.name = '找到知情人'),
       '},{"type":"text","content":"接头完成。记住:真正的接头人,从不问奖励在哪。"}]')
 WHERE c.name = '接头'
   AND c.topic_id = (SELECT t.id FROM cms_topic t WHERE t.name = '神秘店员的暗号' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0);


-- ============================================================================
-- 包⑤ 三步解谜(酒吧/文化空间 · 3 节点 · 递进谜题)
-- ============================================================================

INSERT INTO cms_topic (member_id, topic_type, product_type, name, subtitle, description,
                       address_name, total_time, location_count, is_template,
                       status, user_status, del_flag, create_by, create_time)
SELECT 100000, 1, 2,
       '三步解谜',
       '一个发生在这家店里的小故事,分三步解开',
       '一段完整的小故事拆成三章,每章一道递进谜题,全部解开后剧情收束、发放奖励。适合酒吧、文化空间、有故事感的店。复制后把故事和谜题改写成你店里的版本——AI 可以帮你把店铺特色写进剧情。',
       '单店(复制后请在编辑器为节点选择你的店铺位置)',
       25, 3, 1,
       1, 0, 0, 'seed_playpack_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_topic WHERE name = '三步解谜' AND member_id = 100000 AND del_flag = 0);

INSERT INTO product_role (topic_id, slot, entity_type, entity_id, status)
SELECT t.id, 'CONTENT', 'PLATFORM', NULL, 1
  FROM cms_topic t
 WHERE t.name = '三步解谜' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM product_role r
                    WHERE r.topic_id = t.id AND r.slot = 'CONTENT' AND r.status = 1);

INSERT INTO cms_topic_chapter (topic_id, name, description, sort_id, location_count, template_count,
                               required, recruit_enabled, max_merchant, terms_mode,
                               create_by, create_time)
SELECT t.id, '失物招领', '有人在这家店留下了一样东西,和三条线索。', 1, 3, 3,
       1, 0, 0, 'PERK', 'seed_playpack_20260820', NOW()
  FROM cms_topic t
 WHERE t.name = '三步解谜' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_chapter c
                    WHERE c.topic_id = t.id AND c.name = '失物招领');

INSERT INTO cms_member_template (member_id, title, description, players, usage_location, duration,
                                 difficulty, rule_instructions, validation_method,
                                 question_name, question_a, question_b, question_c, question_d,
                                 correct_answer, hint1, hint2, answer_reveal, feedback_text, xp_value,
                                 draft_status, status, publish_status, check_status, is_sync,
                                 del_flag, create_by, create_time)
SELECT 100000, '玩法包·解谜第一步',
       '选项题:故事第一步,确认失物是什么。',
       '1-2人', '店内', 8, '中等',
       '商家改造指南:三道题构成一条故事线(失物→线索→归属),改写时保持递进关系;可把故事主角换成你店里真实的常客或老物件。',
       3,
       '打烊前,店员在角落的座位上发现了一样被落下的东西。根据失物招领帖的描述——「它装着别人的话,自己却一句话不说」——那是什么?',
       '一部手机', '一本笔记本', '一副耳机', '一把伞',
       'B',
       '它不会响,也不会漏电。',
       '装着「话」,但要用手翻开才看得见。',
       '是一本写满字的笔记本。翻开第一页,只有一行字:「如果你找到它,请替我做完最后一件事。」',
       '再读一遍描述:装着别人的话,自己却一句话不说。',
       10,
       1, 1, 0, 1, 0,
       0, 'seed_playpack_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_member_template
                    WHERE member_id = 100000 AND title = '玩法包·解谜第一步' AND del_flag = 0);

INSERT INTO cms_member_template (member_id, title, description, players, usage_location, duration,
                                 difficulty, rule_instructions, validation_method,
                                 question_name, question_answer, hint1, hint2, answer_reveal,
                                 feedback_text, xp_value,
                                 draft_status, status, publish_status, check_status, is_sync,
                                 del_flag, create_by, create_time)
SELECT 100000, '玩法包·解谜第二步',
       '文字题:故事第二步,破译笔记本里的线索。',
       '1-2人', '店内', 8, '中等',
       '商家改造指南:这一步是文字谜,答案建议换成你店内真实可见的一个词(菜单上的品名/墙上的字),让玩家「抬头就能对答案」。',
       1,
       '笔记本的最后一页画着一个简笔画:一杯冒着热气的东西,旁边写着「每次都点它,今天替我再点一次」。翻翻这家店的菜单,把那样东西的名字填进来。(提示:菜单上的招牌款)',
       '招牌特调',
       '是菜单上被标了记号的那一款。',
       '问店员「哪款是招牌」,答案就出来了。',
       '你点下了那一杯。店员看了你一眼,像是想起了什么人。',
       '菜单上找找,是最招牌的那一款。',
       10,
       1, 1, 0, 1, 0,
       0, 'seed_playpack_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_member_template
                    WHERE member_id = 100000 AND title = '玩法包·解谜第二步' AND del_flag = 0);

INSERT INTO cms_member_template (member_id, title, description, players, usage_location, duration,
                                 difficulty, rule_instructions, validation_method,
                                 question_name, question_a, question_b, question_c, question_d,
                                 correct_answer, hint1, hint2, answer_reveal, feedback_text, xp_value,
                                 draft_status, status, publish_status, check_status, is_sync,
                                 del_flag, create_by, create_time)
SELECT 100000, '玩法包·解谜第三步',
       '选项题:故事第三步,替失主完成最后一件事。',
       '1-2人', '店内', 8, '中等',
       '商家改造指南:结局题;答案揭示里的奖励说明请按你配置的实际奖励改写。',
       3,
       '杯子端上来时,杯垫底下压着一张字条:「谢谢你。把这个故事讲给下一个坐在这个位置的人。」——所以「最后一件事」到底是什么?',
       '喝掉这一杯', '付掉这一单', '把故事传下去', '把笔记本带走',
       'C',
       '字条上写得明明白白。',
       '重点不在杯子,在「讲给下一个人」。',
       '谜底解开:失主要的从来不是找回笔记本,而是让这个位置一直有故事。通关奖励已发放——下次你坐在这里,也许会有人来问你这个故事。',
       '再读一遍字条。',
       10,
       1, 1, 0, 1, 0,
       0, 'seed_playpack_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_member_template
                    WHERE member_id = 100000 AND title = '玩法包·解谜第三步' AND del_flag = 0);

INSERT INTO cms_topic_node (topic_id, chapter_id, name, description, template_id, checkin_code,
                            status, sort_id, xp_value, create_by, create_time)
SELECT t.id, c.id, '第一步:失物', '确认角落座位上留下的东西。',
       tpl.id, REPLACE(UUID(), '-', ''), 1, 1, 10, 'seed_playpack_20260820', NOW()
  FROM cms_topic t
  JOIN cms_topic_chapter c ON c.topic_id = t.id AND c.name = '失物招领'
  JOIN cms_member_template tpl ON tpl.member_id = 100000 AND tpl.title = '玩法包·解谜第一步' AND tpl.del_flag = 0
 WHERE t.name = '三步解谜' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n WHERE n.topic_id = t.id AND n.name = '第一步:失物');

INSERT INTO cms_topic_node (topic_id, chapter_id, name, description, template_id, checkin_code,
                            status, sort_id, xp_value, create_by, create_time)
SELECT t.id, c.id, '第二步:线索', '破译笔记本最后一页的线索。',
       tpl.id, REPLACE(UUID(), '-', ''), 1, 2, 10, 'seed_playpack_20260820', NOW()
  FROM cms_topic t
  JOIN cms_topic_chapter c ON c.topic_id = t.id AND c.name = '失物招领'
  JOIN cms_member_template tpl ON tpl.member_id = 100000 AND tpl.title = '玩法包·解谜第二步' AND tpl.del_flag = 0
 WHERE t.name = '三步解谜' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n WHERE n.topic_id = t.id AND n.name = '第二步:线索');

INSERT INTO cms_topic_node (topic_id, chapter_id, name, description, template_id, checkin_code,
                            status, sort_id, xp_value, create_by, create_time)
SELECT t.id, c.id, '第三步:结局', '替失主完成最后一件事。',
       tpl.id, REPLACE(UUID(), '-', ''), 1, 3, 10, 'seed_playpack_20260820', NOW()
  FROM cms_topic t
  JOIN cms_topic_chapter c ON c.topic_id = t.id AND c.name = '失物招领'
  JOIN cms_member_template tpl ON tpl.member_id = 100000 AND tpl.title = '玩法包·解谜第三步' AND tpl.del_flag = 0
 WHERE t.name = '三步解谜' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n WHERE n.topic_id = t.id AND n.name = '第三步:结局');

UPDATE cms_topic_chapter c
   SET c.blocks = CONCAT(
       '[{"type":"text","content":"打烊前十分钟,店员在角落的座位上发现了一样被落下的东西,和一张失物招领帖。这个故事,需要一个解谜的人。"},',
       '{"type":"node","nodeId":',
       (SELECT n.id FROM cms_topic_node n WHERE n.topic_id = c.topic_id AND n.name = '第一步:失物'),
       '},{"type":"text","content":"笔记本翻到最后一页,那里画着一个简笔画。"},',
       '{"type":"node","nodeId":',
       (SELECT n.id FROM cms_topic_node n WHERE n.topic_id = c.topic_id AND n.name = '第二步:线索'),
       '},{"type":"text","content":"杯垫底下,还压着最后一张字条。"},',
       '{"type":"node","nodeId":',
       (SELECT n.id FROM cms_topic_node n WHERE n.topic_id = c.topic_id AND n.name = '第三步:结局'),
       '}]')
 WHERE c.name = '失物招领'
   AND c.topic_id = (SELECT t.id FROM cms_topic t WHERE t.name = '三步解谜' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0);


-- ============================================================================
-- 包⑥ 听音辨物(咖啡/livehouse/酒吧 · 1 节点 · 选项题,音频由商家补)
-- ============================================================================

INSERT INTO cms_topic (member_id, topic_type, product_type, name, subtitle, description,
                       address_name, total_time, location_count, is_template,
                       status, user_status, del_flag, create_by, create_time)
SELECT 100000, 1, 2,
       '听音辨物',
       '闭上眼听十秒,猜出这是店里哪个声音',
       '录一段你店里的标志性声音(磨豆声/摇壶声/开门铃),玩家到店听音答题,答对解锁声音背后的故事。复制后请在编辑器给题目补上你自己录的 10 秒音频——手机录音即可,真实感比音质重要。',
       '单店(复制后请在编辑器为节点选择你的店铺位置)',
       10, 1, 1,
       1, 0, 0, 'seed_playpack_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_topic WHERE name = '听音辨物' AND member_id = 100000 AND del_flag = 0);

INSERT INTO product_role (topic_id, slot, entity_type, entity_id, status)
SELECT t.id, 'CONTENT', 'PLATFORM', NULL, 1
  FROM cms_topic t
 WHERE t.name = '听音辨物' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM product_role r
                    WHERE r.topic_id = t.id AND r.slot = 'CONTENT' AND r.status = 1);

INSERT INTO cms_topic_chapter (topic_id, name, description, sort_id, location_count, template_count,
                               required, recruit_enabled, max_merchant, terms_mode,
                               create_by, create_time)
SELECT t.id, '闭眼十秒', '每家店都有自己的声音,你听得出来吗。', 1, 1, 1,
       1, 0, 0, 'PERK', 'seed_playpack_20260820', NOW()
  FROM cms_topic t
 WHERE t.name = '听音辨物' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_chapter c
                    WHERE c.topic_id = t.id AND c.name = '闭眼十秒');

INSERT INTO cms_member_template (member_id, title, description, players, usage_location, duration,
                                 difficulty, rule_instructions, validation_method,
                                 question_name, question_a, question_b, question_c, question_d,
                                 correct_answer, hint1, hint2, answer_reveal, feedback_text, xp_value,
                                 draft_status, status, publish_status, check_status, is_sync,
                                 del_flag, create_by, create_time)
SELECT 100000, '玩法包·店内声音题',
       '听音选项题:商家自录 10 秒店内声音(编辑器里补音频)。',
       '1人', '店内', 5, '简单',
       '商家改造指南(必做):①用手机录一段 10 秒店内标志性声音;②在编辑器里把音频传到本题的「题干配音」;③把四个选项换成你店里的真实场景,正确项对应录音内容。未补音频前本题是纯文字猜谜,补上后才是完整体验。',
       3,
       '戴上耳机听这段声音——这是本店每天都会出现的声音之一。它来自哪里?',
       '磨豆机在磨今天的第一份豆子', '摇壶里的冰块在跳舞', '门口的风铃被推门的人惊动', '洗杯子的水流声',
       'A',
       '这是一天里最早出现的那个声音。',
       '闻着香味猜也行。',
       '答对了。这台机器每天早上七点半准时开工,比店里任何人都勤快——这就是这家店一天的开场白。',
       '再听一遍,注意声音的节奏。',
       10,
       1, 1, 0, 1, 0,
       0, 'seed_playpack_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_member_template
                    WHERE member_id = 100000 AND title = '玩法包·店内声音题' AND del_flag = 0);

INSERT INTO cms_topic_node (topic_id, chapter_id, name, description, template_id, checkin_code,
                            status, sort_id, xp_value, create_by, create_time)
SELECT t.id, c.id, '听声辨位', '扫码进店,听一段店内声音并作答。',
       tpl.id, REPLACE(UUID(), '-', ''), 1, 1, 10, 'seed_playpack_20260820', NOW()
  FROM cms_topic t
  JOIN cms_topic_chapter c ON c.topic_id = t.id AND c.name = '闭眼十秒'
  JOIN cms_member_template tpl ON tpl.member_id = 100000 AND tpl.title = '玩法包·店内声音题' AND tpl.del_flag = 0
 WHERE t.name = '听音辨物' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n WHERE n.topic_id = t.id AND n.name = '听声辨位');

UPDATE cms_topic_chapter c
   SET c.blocks = CONCAT(
       '[{"type":"text","content":"视觉太吵了。接下来十秒,请只用耳朵逛店。"},',
       '{"type":"node","nodeId":',
       (SELECT n.id FROM cms_topic_node n WHERE n.topic_id = c.topic_id AND n.name = '听声辨位'),
       '}]')
 WHERE c.name = '闭眼十秒'
   AND c.topic_id = (SELECT t.id FROM cms_topic t WHERE t.name = '听音辨物' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0);


-- ============================================================================
-- 包⑦ 三连快答(快节奏业态/排队场景 · 3 节点 · 选项题;无计时机制,文案不承诺)
-- ============================================================================

INSERT INTO cms_topic (member_id, topic_type, product_type, name, subtitle, description,
                       address_name, total_time, location_count, is_template,
                       status, user_status, del_flag, create_by, create_time)
SELECT 100000, 1, 2,
       '三连快答',
       '关于这家店的三道题,一口气全答对',
       '三道关于本店的快问快答,连着答完最过瘾,适合排队等位、等餐间隙玩。复制后把三道题换成你店的真实冷知识——招牌怎么来的、哪样东西最老、老板最得意的是什么。',
       '单店(复制后请在编辑器为节点选择你的店铺位置)',
       10, 3, 1,
       1, 0, 0, 'seed_playpack_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_topic WHERE name = '三连快答' AND member_id = 100000 AND del_flag = 0);

INSERT INTO product_role (topic_id, slot, entity_type, entity_id, status)
SELECT t.id, 'CONTENT', 'PLATFORM', NULL, 1
  FROM cms_topic t
 WHERE t.name = '三连快答' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM product_role r
                    WHERE r.topic_id = t.id AND r.slot = 'CONTENT' AND r.status = 1);

INSERT INTO cms_topic_chapter (topic_id, name, description, sort_id, location_count, template_count,
                               required, recruit_enabled, max_merchant, terms_mode,
                               create_by, create_time)
SELECT t.id, '快问快答', '三道题,试试你对这家店的了解程度。', 1, 3, 3,
       1, 0, 0, 'PERK', 'seed_playpack_20260820', NOW()
  FROM cms_topic t
 WHERE t.name = '三连快答' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_chapter c
                    WHERE c.topic_id = t.id AND c.name = '快问快答');

INSERT INTO cms_member_template (member_id, title, description, players, usage_location, duration,
                                 difficulty, rule_instructions, validation_method,
                                 question_name, question_a, question_b, question_c, question_d,
                                 correct_answer, hint1, hint2, answer_reveal, feedback_text, xp_value,
                                 draft_status, status, publish_status, check_status, is_sync,
                                 del_flag, create_by, create_time)
SELECT 100000, '玩法包·快答第一题',
       '选项题:关于本店的第一道冷知识。',
       '1人', '店内', 3, '简单',
       '商家改造指南:换成你店的真实冷知识,答案要在店内可验证(看得到/问得到)。',
       3,
       '第一题:这家店的招牌,用的是哪种颜色打底?抬头看一眼就有答案。',
       '黑色', '白色', '木色', '其他颜色',
       'D',
       '别凭印象,真的抬头看一眼。',
       '门口三秒钟,答案就有了。',
       '观察力不错。招牌的颜色是老板当年纠结最久的一件事。',
       '出门看一眼再回来,不算犯规。',
       10,
       1, 1, 0, 1, 0,
       0, 'seed_playpack_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_member_template
                    WHERE member_id = 100000 AND title = '玩法包·快答第一题' AND del_flag = 0);

INSERT INTO cms_member_template (member_id, title, description, players, usage_location, duration,
                                 difficulty, rule_instructions, validation_method,
                                 question_name, question_a, question_b, question_c, question_d,
                                 correct_answer, hint1, hint2, answer_reveal, feedback_text, xp_value,
                                 draft_status, status, publish_status, check_status, is_sync,
                                 del_flag, create_by, create_time)
SELECT 100000, '玩法包·快答第二题',
       '选项题:关于本店的第二道冷知识。',
       '1人', '店内', 3, '简单',
       '商家改造指南:换成菜单/商品相关的题,顺便让玩家把菜单翻个遍。',
       3,
       '第二题:菜单上一共有多少款可以点的东西?数一遍,四舍五入到最接近的选项。',
       '10款以内', '11到20款', '21到30款', '30款以上',
       'B',
       '数的时候别漏了季节限定。',
       '问店员也行,但自己数更有成就感。',
       '数对了。顺便——数的过程中有没有哪款让你想点?',
       '再数一遍,这次认真点。',
       10,
       1, 1, 0, 1, 0,
       0, 'seed_playpack_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_member_template
                    WHERE member_id = 100000 AND title = '玩法包·快答第二题' AND del_flag = 0);

INSERT INTO cms_member_template (member_id, title, description, players, usage_location, duration,
                                 difficulty, rule_instructions, validation_method,
                                 question_name, question_a, question_b, question_c, question_d,
                                 correct_answer, hint1, hint2, answer_reveal, feedback_text, xp_value,
                                 draft_status, status, publish_status, check_status, is_sync,
                                 del_flag, create_by, create_time)
SELECT 100000, '玩法包·快答第三题',
       '选项题:关于本店的第三道冷知识。',
       '1人', '店内', 3, '简单',
       '商家改造指南:最后一题建议出「问店员才知道」的题,制造一次自然搭话。',
       3,
       '最后一题:问问店员,这家店开了几年了?',
       '不到1年', '1到3年', '3到5年', '5年以上',
       'B',
       '这题店员一定知道。',
       '顺便问问开店那天的故事。',
       '三连全对!把通关画面出示给店员——你现在对这家店的了解,超过了九成路过的人。',
       '别猜了,去问店员。',
       10,
       1, 1, 0, 1, 0,
       0, 'seed_playpack_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_member_template
                    WHERE member_id = 100000 AND title = '玩法包·快答第三题' AND del_flag = 0);

INSERT INTO cms_topic_node (topic_id, chapter_id, name, description, template_id, checkin_code,
                            status, sort_id, xp_value, create_by, create_time)
SELECT t.id, c.id, '第一问', '关于招牌的观察题。',
       tpl.id, REPLACE(UUID(), '-', ''), 1, 1, 10, 'seed_playpack_20260820', NOW()
  FROM cms_topic t
  JOIN cms_topic_chapter c ON c.topic_id = t.id AND c.name = '快问快答'
  JOIN cms_member_template tpl ON tpl.member_id = 100000 AND tpl.title = '玩法包·快答第一题' AND tpl.del_flag = 0
 WHERE t.name = '三连快答' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n WHERE n.topic_id = t.id AND n.name = '第一问');

INSERT INTO cms_topic_node (topic_id, chapter_id, name, description, template_id, checkin_code,
                            status, sort_id, xp_value, create_by, create_time)
SELECT t.id, c.id, '第二问', '关于菜单的清点题。',
       tpl.id, REPLACE(UUID(), '-', ''), 1, 2, 10, 'seed_playpack_20260820', NOW()
  FROM cms_topic t
  JOIN cms_topic_chapter c ON c.topic_id = t.id AND c.name = '快问快答'
  JOIN cms_member_template tpl ON tpl.member_id = 100000 AND tpl.title = '玩法包·快答第二题' AND tpl.del_flag = 0
 WHERE t.name = '三连快答' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n WHERE n.topic_id = t.id AND n.name = '第二问');

INSERT INTO cms_topic_node (topic_id, chapter_id, name, description, template_id, checkin_code,
                            status, sort_id, xp_value, create_by, create_time)
SELECT t.id, c.id, '第三问', '需要开口问店员的最后一题。',
       tpl.id, REPLACE(UUID(), '-', ''), 1, 3, 10, 'seed_playpack_20260820', NOW()
  FROM cms_topic t
  JOIN cms_topic_chapter c ON c.topic_id = t.id AND c.name = '快问快答'
  JOIN cms_member_template tpl ON tpl.member_id = 100000 AND tpl.title = '玩法包·快答第三题' AND tpl.del_flag = 0
 WHERE t.name = '三连快答' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n WHERE n.topic_id = t.id AND n.name = '第三问');

UPDATE cms_topic_chapter c
   SET c.blocks = CONCAT(
       '[{"type":"text","content":"规则很简单:三道题,都和这家店有关。答案全在店里,眼睛和嘴巴都可以用。"},',
       '{"type":"node","nodeId":',
       (SELECT n.id FROM cms_topic_node n WHERE n.topic_id = c.topic_id AND n.name = '第一问'),
       '},{"type":"node","nodeId":',
       (SELECT n.id FROM cms_topic_node n WHERE n.topic_id = c.topic_id AND n.name = '第二问'),
       '},{"type":"node","nodeId":',
       (SELECT n.id FROM cms_topic_node n WHERE n.topic_id = c.topic_id AND n.name = '第三问'),
       '}]')
 WHERE c.name = '快问快答'
   AND c.topic_id = (SELECT t.id FROM cms_topic t WHERE t.name = '三连快答' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0);


-- ============================================================================
-- 包⑧ 集章巡店(商场/街区/市集 · 4 节点 · 扫码集章;通关=全部完成,N/M 机制未建)
-- ============================================================================

INSERT INTO cms_topic (member_id, topic_type, product_type, name, subtitle, description,
                       address_name, total_time, location_count, is_template,
                       status, user_status, del_flag, create_by, create_time)
SELECT 100000, 1, 2,
       '集章巡店',
       '四个点位,四枚印章,集齐换奖励',
       '一张巡店集章图:四个点位各有一枚印章,到点扫码即盖章,集齐通关。适合商场、街区、市集多点联动,也可以是一家大店里的四个角落。复制后为每个节点选择真实点位;主理人/商场运营方是本模板的典型使用者。',
       '多点位(复制后请在编辑器为每个节点分别选点)',
       60, 4, 1,
       1, 0, 0, 'seed_playpack_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_topic WHERE name = '集章巡店' AND member_id = 100000 AND del_flag = 0);

INSERT INTO product_role (topic_id, slot, entity_type, entity_id, status)
SELECT t.id, 'CONTENT', 'PLATFORM', NULL, 1
  FROM cms_topic t
 WHERE t.name = '集章巡店' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM product_role r
                    WHERE r.topic_id = t.id AND r.slot = 'CONTENT' AND r.status = 1);

INSERT INTO cms_topic_chapter (topic_id, name, description, sort_id, location_count, template_count,
                               required, recruit_enabled, max_merchant, terms_mode,
                               create_by, create_time)
SELECT t.id, '巡店地图', '四枚印章散落在四个点位,把它们都收进口袋。', 1, 4, 4,
       1, 0, 0, 'PERK', 'seed_playpack_20260820', NOW()
  FROM cms_topic t
 WHERE t.name = '集章巡店' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_chapter c
                    WHERE c.topic_id = t.id AND c.name = '巡店地图');

INSERT INTO cms_member_template (member_id, title, description, players, usage_location, duration,
                                 difficulty, rule_instructions, validation_method,
                                 feedback_text, story_text, xp_value,
                                 draft_status, status, publish_status, check_status, is_sync,
                                 del_flag, create_by, create_time)
SELECT 100000, '玩法包·印章东',
       '扫码盖章:第一枚印章。',
       '1-4人', '点位现场', 10, '简单',
       '商家改造指南:每个印章节点对应一个真实点位;把 story_text 换成该点位的一句话介绍。',
       4,
       '第一枚印章到手!还差三枚。',
       '巡店从这里开始。这个点位是整条路线的东边起点。',
       10,
       1, 1, 0, 1, 0,
       0, 'seed_playpack_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_member_template
                    WHERE member_id = 100000 AND title = '玩法包·印章东' AND del_flag = 0);

INSERT INTO cms_member_template (member_id, title, description, players, usage_location, duration,
                                 difficulty, rule_instructions, validation_method,
                                 feedback_text, story_text, xp_value,
                                 draft_status, status, publish_status, check_status, is_sync,
                                 del_flag, create_by, create_time)
SELECT 100000, '玩法包·印章南',
       '扫码盖章:第二枚印章。',
       '1-4人', '点位现场', 10, '简单',
       '商家改造指南:同印章东。',
       4,
       '第二枚印章到手!过半了。',
       '南边这个点位,常被路过的人错过——现在你不会了。',
       10,
       1, 1, 0, 1, 0,
       0, 'seed_playpack_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_member_template
                    WHERE member_id = 100000 AND title = '玩法包·印章南' AND del_flag = 0);

INSERT INTO cms_member_template (member_id, title, description, players, usage_location, duration,
                                 difficulty, rule_instructions, validation_method,
                                 feedback_text, story_text, xp_value,
                                 draft_status, status, publish_status, check_status, is_sync,
                                 del_flag, create_by, create_time)
SELECT 100000, '玩法包·印章西',
       '扫码盖章:第三枚印章。',
       '1-4人', '点位现场', 10, '简单',
       '商家改造指南:同印章东。',
       4,
       '第三枚印章到手!只差最后一枚。',
       '西边的这一站,适合放慢脚步。',
       10,
       1, 1, 0, 1, 0,
       0, 'seed_playpack_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_member_template
                    WHERE member_id = 100000 AND title = '玩法包·印章西' AND del_flag = 0);

INSERT INTO cms_member_template (member_id, title, description, players, usage_location, duration,
                                 difficulty, rule_instructions, validation_method,
                                 feedback_text, story_text, xp_value,
                                 draft_status, status, publish_status, check_status, is_sync,
                                 del_flag, create_by, create_time)
SELECT 100000, '玩法包·印章北',
       '扫码盖章:最后一枚印章。',
       '1-4人', '点位现场', 10, '简单',
       '商家改造指南:同印章东;集齐奖励(联名券/礼品)在主题通关奖励处配置。',
       4,
       '四枚印章集齐!把通关画面出示给任意参与点位的店员,领取集章奖励。',
       '最后一站。集齐的人不多,你马上是其中之一。',
       10,
       1, 1, 0, 1, 0,
       0, 'seed_playpack_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_member_template
                    WHERE member_id = 100000 AND title = '玩法包·印章北' AND del_flag = 0);

INSERT INTO cms_topic_node (topic_id, chapter_id, name, description, template_id, checkin_code,
                            status, sort_id, xp_value, create_by, create_time)
SELECT t.id, c.id, '印章·东', '东侧点位,扫码盖章。',
       tpl.id, REPLACE(UUID(), '-', ''), 1, 1, 10, 'seed_playpack_20260820', NOW()
  FROM cms_topic t
  JOIN cms_topic_chapter c ON c.topic_id = t.id AND c.name = '巡店地图'
  JOIN cms_member_template tpl ON tpl.member_id = 100000 AND tpl.title = '玩法包·印章东' AND tpl.del_flag = 0
 WHERE t.name = '集章巡店' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n WHERE n.topic_id = t.id AND n.name = '印章·东');

INSERT INTO cms_topic_node (topic_id, chapter_id, name, description, template_id, checkin_code,
                            status, sort_id, xp_value, create_by, create_time)
SELECT t.id, c.id, '印章·南', '南侧点位,扫码盖章。',
       tpl.id, REPLACE(UUID(), '-', ''), 1, 2, 10, 'seed_playpack_20260820', NOW()
  FROM cms_topic t
  JOIN cms_topic_chapter c ON c.topic_id = t.id AND c.name = '巡店地图'
  JOIN cms_member_template tpl ON tpl.member_id = 100000 AND tpl.title = '玩法包·印章南' AND tpl.del_flag = 0
 WHERE t.name = '集章巡店' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n WHERE n.topic_id = t.id AND n.name = '印章·南');

INSERT INTO cms_topic_node (topic_id, chapter_id, name, description, template_id, checkin_code,
                            status, sort_id, xp_value, create_by, create_time)
SELECT t.id, c.id, '印章·西', '西侧点位,扫码盖章。',
       tpl.id, REPLACE(UUID(), '-', ''), 1, 3, 10, 'seed_playpack_20260820', NOW()
  FROM cms_topic t
  JOIN cms_topic_chapter c ON c.topic_id = t.id AND c.name = '巡店地图'
  JOIN cms_member_template tpl ON tpl.member_id = 100000 AND tpl.title = '玩法包·印章西' AND tpl.del_flag = 0
 WHERE t.name = '集章巡店' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n WHERE n.topic_id = t.id AND n.name = '印章·西');

INSERT INTO cms_topic_node (topic_id, chapter_id, name, description, template_id, checkin_code,
                            status, sort_id, xp_value, create_by, create_time)
SELECT t.id, c.id, '印章·北', '北侧点位,扫码盖章,集齐领奖。',
       tpl.id, REPLACE(UUID(), '-', ''), 1, 4, 10, 'seed_playpack_20260820', NOW()
  FROM cms_topic t
  JOIN cms_topic_chapter c ON c.topic_id = t.id AND c.name = '巡店地图'
  JOIN cms_member_template tpl ON tpl.member_id = 100000 AND tpl.title = '玩法包·印章北' AND tpl.del_flag = 0
 WHERE t.name = '集章巡店' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n WHERE n.topic_id = t.id AND n.name = '印章·北');

UPDATE cms_topic_chapter c
   SET c.blocks = CONCAT(
       '[{"type":"text","content":"一张巡店地图,四枚印章。顺序随你,集齐为止。"},',
       '{"type":"node","nodeId":',
       (SELECT n.id FROM cms_topic_node n WHERE n.topic_id = c.topic_id AND n.name = '印章·东'),
       '},{"type":"node","nodeId":',
       (SELECT n.id FROM cms_topic_node n WHERE n.topic_id = c.topic_id AND n.name = '印章·南'),
       '},{"type":"node","nodeId":',
       (SELECT n.id FROM cms_topic_node n WHERE n.topic_id = c.topic_id AND n.name = '印章·西'),
       '},{"type":"node","nodeId":',
       (SELECT n.id FROM cms_topic_node n WHERE n.topic_id = c.topic_id AND n.name = '印章·北'),
       '}]')
 WHERE c.name = '巡店地图'
   AND c.topic_id = (SELECT t.id FROM cms_topic t WHERE t.name = '集章巡店' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0);


-- ============================================================================
-- 包⑨ 岔路口的选择(剧情店/情侣客群 · 2 节点 · 线性剧情+选择;分支引擎未建)
-- ============================================================================

INSERT INTO cms_topic (member_id, topic_type, product_type, name, subtitle, description,
                       address_name, total_time, location_count, is_template,
                       status, user_status, del_flag, create_by, create_time)
SELECT 100000, 1, 2,
       '岔路口的选择',
       '故事走到岔口,替主角做出那个选择',
       '一段带选择感的店内小剧情:玩家在故事岔口做出选择,并亲手把结局补完。适合想给客人留下记忆点的店、情侣客群。复制后把剧情场景换成你店里的真实布置。',
       '单店(复制后请在编辑器为节点选择你的店铺位置)',
       15, 2, 1,
       1, 0, 0, 'seed_playpack_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_topic WHERE name = '岔路口的选择' AND member_id = 100000 AND del_flag = 0);

INSERT INTO product_role (topic_id, slot, entity_type, entity_id, status)
SELECT t.id, 'CONTENT', 'PLATFORM', NULL, 1
  FROM cms_topic t
 WHERE t.name = '岔路口的选择' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM product_role r
                    WHERE r.topic_id = t.id AND r.slot = 'CONTENT' AND r.status = 1);

INSERT INTO cms_topic_chapter (topic_id, name, description, sort_id, location_count, template_count,
                               required, recruit_enabled, max_merchant, terms_mode,
                               create_by, create_time)
SELECT t.id, '两封信', '桌上放着两封信,你只能先拆一封。', 1, 2, 2,
       1, 0, 0, 'PERK', 'seed_playpack_20260820', NOW()
  FROM cms_topic t
 WHERE t.name = '岔路口的选择' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_chapter c
                    WHERE c.topic_id = t.id AND c.name = '两封信');

INSERT INTO cms_member_template (member_id, title, description, players, usage_location, duration,
                                 difficulty, rule_instructions, validation_method,
                                 question_name, question_a, question_b, question_c, question_d,
                                 correct_answer, hint1, hint2, answer_reveal, feedback_text, xp_value,
                                 draft_status, status, publish_status, check_status, is_sync,
                                 del_flag, create_by, create_time)
SELECT 100000, '玩法包·岔口抉择',
       '选项题:剧情岔口,读懂两封信各自通向哪里。',
       '1-2人', '店内', 8, '中等',
       '商家改造指南:把两封信的场景换成你店里的真实道具(可以真的在店里放两封信);本题考「读懂选择的后果」,不是拼运气。',
       3,
       '桌上有两封信。红色封口那封写着「拆我,今晚会多一个故事」;白色封口那封写着「拆我,今晚会少一个遗憾」。哪封信拆开后,你需要马上做一件事?',
       '红色那封', '白色那封', '两封都要', '都不用',
       'B',
       '「少一个遗憾」意味着有件事现在还来得及。',
       '故事可以慢慢听,遗憾不能慢慢补。',
       '白色信封里只有一行字:「趁热——不管是桌上那杯,还是你想说的话。」现在,去把该做的那件事做了。',
       '再读一遍两句封口语的区别。',
       10,
       1, 1, 0, 1, 0,
       0, 'seed_playpack_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_member_template
                    WHERE member_id = 100000 AND title = '玩法包·岔口抉择' AND del_flag = 0);

INSERT INTO cms_member_template (member_id, title, description, players, usage_location, duration,
                                 difficulty, rule_instructions, validation_method,
                                 question_name, question_answer, hint1, hint2, answer_reveal,
                                 feedback_text, xp_value,
                                 draft_status, status, publish_status, check_status, is_sync,
                                 del_flag, create_by, create_time)
SELECT 100000, '玩法包·结局由你写',
       '文字题:亲手写下这个故事的结局关键词。',
       '1-2人', '店内', 5, '简单',
       '商家改造指南:答案是一个开放的「仪式词」,建议换成你店的告别语/口头禅,让结局带上你店的印记。',
       1,
       '故事的最后一句留了个空:「离开前,他对这家店说了一声____。」按店里墙上写的那句告别语,把空填上。(提示:两个字)',
       '再见',
       '答案写在店里最显眼的一句话里。',
       '就是离开时最常说的那两个字。',
       '故事完成了。这个结局有你的一笔——下次再来,故事会认得你。',
       '再看看店里的墙,答案一直在那里。',
       10,
       1, 1, 0, 1, 0,
       0, 'seed_playpack_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_member_template
                    WHERE member_id = 100000 AND title = '玩法包·结局由你写' AND del_flag = 0);

INSERT INTO cms_topic_node (topic_id, chapter_id, name, description, template_id, checkin_code,
                            status, sort_id, xp_value, create_by, create_time)
SELECT t.id, c.id, '岔口:两封信', '在两封信之间做出选择。',
       tpl.id, REPLACE(UUID(), '-', ''), 1, 1, 10, 'seed_playpack_20260820', NOW()
  FROM cms_topic t
  JOIN cms_topic_chapter c ON c.topic_id = t.id AND c.name = '两封信'
  JOIN cms_member_template tpl ON tpl.member_id = 100000 AND tpl.title = '玩法包·岔口抉择' AND tpl.del_flag = 0
 WHERE t.name = '岔路口的选择' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n WHERE n.topic_id = t.id AND n.name = '岔口:两封信');

INSERT INTO cms_topic_node (topic_id, chapter_id, name, description, template_id, checkin_code,
                            status, sort_id, xp_value, create_by, create_time)
SELECT t.id, c.id, '结局:补上那句话', '亲手写下故事的结局。',
       tpl.id, REPLACE(UUID(), '-', ''), 1, 2, 10, 'seed_playpack_20260820', NOW()
  FROM cms_topic t
  JOIN cms_topic_chapter c ON c.topic_id = t.id AND c.name = '两封信'
  JOIN cms_member_template tpl ON tpl.member_id = 100000 AND tpl.title = '玩法包·结局由你写' AND tpl.del_flag = 0
 WHERE t.name = '岔路口的选择' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n WHERE n.topic_id = t.id AND n.name = '结局:补上那句话');

UPDATE cms_topic_chapter c
   SET c.blocks = CONCAT(
       '[{"type":"text","content":"故事从你坐下的那一刻开始。桌上放着两封信,你只能先拆一封。"},',
       '{"type":"node","nodeId":',
       (SELECT n.id FROM cms_topic_node n WHERE n.topic_id = c.topic_id AND n.name = '岔口:两封信'),
       '},{"type":"text","content":"信读完了,但故事的最后一句还空着。"},',
       '{"type":"node","nodeId":',
       (SELECT n.id FROM cms_topic_node n WHERE n.topic_id = c.topic_id AND n.name = '结局:补上那句话'),
       '}]')
 WHERE c.name = '两封信'
   AND c.topic_id = (SELECT t.id FROM cms_topic t WHERE t.name = '岔路口的选择' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0);


-- ============================================================================
-- 包⑩ 三个盲盒(高复购业态 · 3 节点 · 三种轻任务;随机下发机制未建,三个全可见)
-- ============================================================================

INSERT INTO cms_topic (member_id, topic_type, product_type, name, subtitle, description,
                       address_name, total_time, location_count, is_template,
                       status, user_status, del_flag, create_by, create_time)
SELECT 100000, 1, 2,
       '三个盲盒',
       '三个袋子,三种小任务,拆开哪个都不亏',
       '三个「盲盒袋子」,每个拆开是一种不同的小任务:观察、拍照、开口问。全部完成即通关。适合想让常客每次来都玩点新花样的店。复制后把三个任务分别换成你店的版本。',
       '单店(复制后请在编辑器为节点选择你的店铺位置)',
       15, 3, 1,
       1, 0, 0, 'seed_playpack_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_topic WHERE name = '三个盲盒' AND member_id = 100000 AND del_flag = 0);

INSERT INTO product_role (topic_id, slot, entity_type, entity_id, status)
SELECT t.id, 'CONTENT', 'PLATFORM', NULL, 1
  FROM cms_topic t
 WHERE t.name = '三个盲盒' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM product_role r
                    WHERE r.topic_id = t.id AND r.slot = 'CONTENT' AND r.status = 1);

INSERT INTO cms_topic_chapter (topic_id, name, description, sort_id, location_count, template_count,
                               required, recruit_enabled, max_merchant, terms_mode,
                               create_by, create_time)
SELECT t.id, '拆袋子', '三个袋子摆在你面前,一个一个拆。', 1, 3, 3,
       1, 0, 0, 'PERK', 'seed_playpack_20260820', NOW()
  FROM cms_topic t
 WHERE t.name = '三个盲盒' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_chapter c
                    WHERE c.topic_id = t.id AND c.name = '拆袋子');

INSERT INTO cms_member_template (member_id, title, description, players, usage_location, duration,
                                 difficulty, rule_instructions, validation_method,
                                 question_name, question_a, question_b, question_c, question_d,
                                 correct_answer, hint1, hint2, answer_reveal, feedback_text, xp_value,
                                 draft_status, status, publish_status, check_status, is_sync,
                                 del_flag, create_by, create_time)
SELECT 100000, '玩法包·盲盒之观察',
       '选项题:观察类小任务。',
       '1人', '店内', 4, '简单',
       '商家改造指南:换成你店里一个「常客也未必注意过」的细节。',
       3,
       '盲盒一拆开是一张纸条:「找到店里唯一会动的装饰。」它是什么?',
       '挂钟', '风铃', '鱼缸', '转盘摆件',
       'A',
       '它一直在动,只是动得很慢。',
       '每过一秒,它就动一下。',
       '找到了。它从开店那天起就没停过——店里最勤奋的员工其实是它。',
       '再环视一圈,注意那些「慢慢动」的东西。',
       10,
       1, 1, 0, 1, 0,
       0, 'seed_playpack_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_member_template
                    WHERE member_id = 100000 AND title = '玩法包·盲盒之观察' AND del_flag = 0);

INSERT INTO cms_member_template (member_id, title, description, players, usage_location, duration,
                                 difficulty, rule_instructions, validation_method,
                                 photo_require_desc, photo_review, feedback_text, xp_value,
                                 draft_status, status, publish_status, check_status, is_sync,
                                 del_flag, create_by, create_time)
SELECT 100000, '玩法包·盲盒之拍照',
       '拍照小任务:记录此刻。',
       '1人', '店内', 4, '简单',
       '商家改造指南:换成你希望客人拍下并传播的画面;拍照完成不自动发真金券。',
       2,
       '盲盒二是一台「拍立得」:拍下你此刻桌面上的东西,怎么摆随你,但画面里要有至少一样店里的东西。',
       1,
       '收进相册了。今天你桌上的这一幕,就是这家店此刻的样子。',
       10,
       1, 1, 0, 1, 0,
       0, 'seed_playpack_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_member_template
                    WHERE member_id = 100000 AND title = '玩法包·盲盒之拍照' AND del_flag = 0);

INSERT INTO cms_member_template (member_id, title, description, players, usage_location, duration,
                                 difficulty, rule_instructions, validation_method,
                                 question_name, question_answer, hint1, hint2, answer_reveal,
                                 feedback_text, xp_value,
                                 draft_status, status, publish_status, check_status, is_sync,
                                 del_flag, create_by, create_time)
SELECT 100000, '玩法包·盲盒之开口',
       '文字题:需要开口问店员的小任务。',
       '1人', '店内', 4, '简单',
       '商家改造指南:把问题换成你愿意被客人问、且店员答得出的一件事;答案设为店员会给出的关键词。',
       1,
       '盲盒三是一个问题,答案只有店员知道:「问问店员,今天店里最推荐的是什么?」把 TA 说的那样东西填进来。(提示:店员答什么填什么,两到五个字)',
       '今日特调',
       '直接问就行,这题没有陷阱。',
       '如果店员反问你,就说是袋子让你问的。',
       '三个盲盒全部拆完!把通关画面出示给店员——顺便,真的试试 TA 刚推荐的那样东西?',
       '把店员说的原话填进来就行。',
       10,
       1, 1, 0, 1, 0,
       0, 'seed_playpack_20260820', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_member_template
                    WHERE member_id = 100000 AND title = '玩法包·盲盒之开口' AND del_flag = 0);

INSERT INTO cms_topic_node (topic_id, chapter_id, name, description, template_id, checkin_code,
                            status, sort_id, xp_value, create_by, create_time)
SELECT t.id, c.id, '盲盒一:观察', '拆开第一个袋子:观察任务。',
       tpl.id, REPLACE(UUID(), '-', ''), 1, 1, 10, 'seed_playpack_20260820', NOW()
  FROM cms_topic t
  JOIN cms_topic_chapter c ON c.topic_id = t.id AND c.name = '拆袋子'
  JOIN cms_member_template tpl ON tpl.member_id = 100000 AND tpl.title = '玩法包·盲盒之观察' AND tpl.del_flag = 0
 WHERE t.name = '三个盲盒' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n WHERE n.topic_id = t.id AND n.name = '盲盒一:观察');

INSERT INTO cms_topic_node (topic_id, chapter_id, name, description, template_id, checkin_code,
                            status, sort_id, xp_value, create_by, create_time)
SELECT t.id, c.id, '盲盒二:拍照', '拆开第二个袋子:拍照任务。',
       tpl.id, REPLACE(UUID(), '-', ''), 1, 2, 10, 'seed_playpack_20260820', NOW()
  FROM cms_topic t
  JOIN cms_topic_chapter c ON c.topic_id = t.id AND c.name = '拆袋子'
  JOIN cms_member_template tpl ON tpl.member_id = 100000 AND tpl.title = '玩法包·盲盒之拍照' AND tpl.del_flag = 0
 WHERE t.name = '三个盲盒' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n WHERE n.topic_id = t.id AND n.name = '盲盒二:拍照');

INSERT INTO cms_topic_node (topic_id, chapter_id, name, description, template_id, checkin_code,
                            status, sort_id, xp_value, create_by, create_time)
SELECT t.id, c.id, '盲盒三:开口', '拆开第三个袋子:开口问任务。',
       tpl.id, REPLACE(UUID(), '-', ''), 1, 3, 10, 'seed_playpack_20260820', NOW()
  FROM cms_topic t
  JOIN cms_topic_chapter c ON c.topic_id = t.id AND c.name = '拆袋子'
  JOIN cms_member_template tpl ON tpl.member_id = 100000 AND tpl.title = '玩法包·盲盒之开口' AND tpl.del_flag = 0
 WHERE t.name = '三个盲盒' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n WHERE n.topic_id = t.id AND n.name = '盲盒三:开口');

UPDATE cms_topic_chapter c
   SET c.blocks = CONCAT(
       '[{"type":"text","content":"桌上摆着三个袋子。规则只有一条:一个一个拆,拆开就得做。"},',
       '{"type":"node","nodeId":',
       (SELECT n.id FROM cms_topic_node n WHERE n.topic_id = c.topic_id AND n.name = '盲盒一:观察'),
       '},{"type":"node","nodeId":',
       (SELECT n.id FROM cms_topic_node n WHERE n.topic_id = c.topic_id AND n.name = '盲盒二:拍照'),
       '},{"type":"node","nodeId":',
       (SELECT n.id FROM cms_topic_node n WHERE n.topic_id = c.topic_id AND n.name = '盲盒三:开口'),
       '}]')
 WHERE c.name = '拆袋子'
   AND c.topic_id = (SELECT t.id FROM cms_topic t WHERE t.name = '三个盲盒' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0);


-- ======================= 第三步:落库后回读断言 =======================
-- 1) 10 个模板主题都在货架、都不在玩家广场(期望 10 行,user_status 全 0):
--   SELECT name, is_template, status, user_status FROM cms_topic
--    WHERE create_by = 'seed_playpack_20260820' AND del_flag = 0;
--
-- 2) 22 个节点全部挂上了模板(期望 count=22, missing=0):
--   SELECT COUNT(*) AS total, SUM(template_id IS NULL) AS missing
--     FROM cms_topic_node WHERE create_by = 'seed_playpack_20260820';
--
-- 3) 22 个模板全部是 W4 可引用态(期望 22 行都 ok=1):
--   SELECT title, (status = 1 AND draft_status = 1 AND del_flag = 0) AS ok
--     FROM cms_member_template
--    WHERE member_id = 100000 AND title LIKE '玩法包·%';
--
-- 4) 10 个章节 blocks 全部回填且 nodeId 有效(期望 0 行):
--   SELECT c.id, c.name FROM cms_topic_chapter c
--     JOIN cms_topic t ON t.id = c.topic_id AND t.create_by = 'seed_playpack_20260820'
--    WHERE c.blocks IS NULL OR c.blocks NOT LIKE '%nodeId%';
--
-- 5) 内容槽齐全(期望 10 行):
--   SELECT t.name FROM cms_topic t
--     JOIN product_role r ON r.topic_id = t.id AND r.slot = 'CONTENT'
--                        AND r.entity_type = 'PLATFORM' AND r.status = 1
--    WHERE t.create_by = 'seed_playpack_20260820';


-- ======================= 第四步:事务内写后精确断言 =======================
-- 任一数量、归属、节点引用或 blocks 漂移，再写固定主键 1，立即红并回滚。
INSERT INTO seed_playpack_guard (id)
SELECT 1
 WHERE (SELECT COUNT(*)
          FROM cms_topic
         WHERE create_by = 'seed_playpack_20260820'
           AND member_id = 100000 AND is_template = 1 AND product_type = 2
           AND status = 1 AND user_status = 0 AND del_flag = 0
           AND name IN ('隐藏菜单','店内寻宝','拍照挑战','神秘店员的暗号','三步解谜',
                        '听音辨物','三连快答','集章巡店','岔路口的选择','三个盲盒')) <> 10
    OR (SELECT COUNT(*) FROM cms_topic
         WHERE create_by = 'seed_playpack_20260820' AND del_flag = 0) <> 10
    OR (SELECT COUNT(*)
          FROM cms_topic_chapter c
          JOIN cms_topic t ON t.id = c.topic_id
         WHERE c.create_by = 'seed_playpack_20260820'
           AND t.create_by = 'seed_playpack_20260820' AND t.del_flag = 0) <> 10
    OR (SELECT COUNT(DISTINCT t.id)
          FROM cms_topic_chapter c
          JOIN cms_topic t ON t.id = c.topic_id
         WHERE c.create_by = 'seed_playpack_20260820'
           AND t.create_by = 'seed_playpack_20260820' AND t.del_flag = 0) <> 10
    OR (SELECT COUNT(*)
          FROM cms_member_template
         WHERE create_by = 'seed_playpack_20260820'
           AND member_id = 100000 AND status = 1 AND draft_status = 1 AND del_flag = 0) <> 22
    OR (SELECT COUNT(*)
          FROM cms_topic_node n
          JOIN cms_topic t ON t.id = n.topic_id
         WHERE n.create_by = 'seed_playpack_20260820'
           AND t.create_by = 'seed_playpack_20260820' AND t.del_flag = 0) <> 22
    OR (SELECT COUNT(*)
          FROM cms_topic_node n
          JOIN cms_topic t ON t.id = n.topic_id
          JOIN cms_member_template tpl ON tpl.id = n.template_id
         WHERE n.create_by = 'seed_playpack_20260820'
           AND t.create_by = 'seed_playpack_20260820' AND t.del_flag = 0
           AND tpl.create_by = 'seed_playpack_20260820'
           AND tpl.member_id = 100000 AND tpl.del_flag = 0) <> 22
    OR EXISTS (
           SELECT 1
             FROM cms_topic t
            WHERE t.create_by = 'seed_playpack_20260820' AND t.del_flag = 0
              AND t.location_count <> (SELECT COUNT(*) FROM cms_topic_node n WHERE n.topic_id = t.id)
       )
    OR EXISTS (
           SELECT 1
             FROM cms_topic_chapter c
             JOIN cms_topic t ON t.id = c.topic_id
            WHERE c.create_by = 'seed_playpack_20260820'
              AND t.create_by = 'seed_playpack_20260820' AND t.del_flag = 0
              AND (c.location_count <> (SELECT COUNT(*) FROM cms_topic_node n WHERE n.chapter_id = c.id)
                   OR c.template_count <> (SELECT COUNT(*) FROM cms_topic_node n WHERE n.chapter_id = c.id))
       )
    OR (SELECT COUNT(*)
          FROM cms_topic_chapter c
          JOIN cms_topic t ON t.id = c.topic_id
         WHERE c.create_by = 'seed_playpack_20260820'
           AND t.create_by = 'seed_playpack_20260820' AND t.del_flag = 0
           AND c.blocks IS NOT NULL AND JSON_VALID(c.blocks) = 1
           AND NOT EXISTS (
               SELECT 1 FROM cms_topic_node n
                WHERE n.topic_id = t.id
                  AND LOCATE(CONCAT('"nodeId":', n.id), c.blocks) = 0
           )) <> 10
    OR (SELECT COUNT(*)
          FROM product_role r
          JOIN cms_topic t ON t.id = r.topic_id
         WHERE t.create_by = 'seed_playpack_20260820' AND t.del_flag = 0
           AND r.slot = 'CONTENT' AND r.entity_type = 'PLATFORM'
           AND r.entity_id IS NULL AND r.status = 1) <> 10
    OR (SELECT COUNT(DISTINCT t.id)
          FROM product_role r
          JOIN cms_topic t ON t.id = r.topic_id
         WHERE t.create_by = 'seed_playpack_20260820' AND t.del_flag = 0
           AND r.slot = 'CONTENT' AND r.entity_type = 'PLATFORM'
           AND r.entity_id IS NULL AND r.status = 1) <> 10
    OR EXISTS (
           SELECT 1
             FROM seed_playpack_expected e
             LEFT JOIN cms_topic t
               ON t.name = e.topic_name
              AND t.member_id = 100000 AND t.is_template = 1 AND t.product_type = 2
              AND t.status = 1 AND t.user_status = 0 AND t.del_flag = 0
              AND t.create_by = 'seed_playpack_20260820'
             LEFT JOIN cms_topic_chapter c
               ON c.topic_id = t.id AND c.name = e.chapter_name
              AND c.create_by = 'seed_playpack_20260820'
             LEFT JOIN cms_topic_node n
               ON n.topic_id = t.id AND n.chapter_id = c.id AND n.name = e.node_name
              AND n.status = 1
              AND n.create_by = 'seed_playpack_20260820'
             LEFT JOIN cms_member_template tpl
               ON tpl.id = n.template_id AND tpl.title = e.template_title
              AND tpl.member_id = 100000 AND tpl.validation_method = e.validation_method
              AND tpl.status = 1 AND tpl.draft_status = 1 AND tpl.del_flag = 0
              AND tpl.create_by = 'seed_playpack_20260820'
            WHERE t.id IS NULL OR c.id IS NULL OR n.id IS NULL OR tpl.id IS NULL
               OR c.blocks IS NULL OR JSON_VALID(c.blocks) <> 1
               OR JSON_LENGTH(c.blocks) <> e.blocks_length
               OR JSON_CONTAINS(c.blocks, JSON_OBJECT('type', 'node', 'nodeId', n.id)) <> 1
       );

-- 事务内 guard 只防提交前漂移；COMMIT 后仍须换新只读连接，从业务表回读同一组断言。
