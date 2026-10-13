-- ============================================================================
-- 具名玩法包种子·第二批(⑪–⑱):8 套官方主题模板,源自 Figma 游戏概念卡评审
--
-- 设计真源:
--   ~/Documents/城瘾/文档整理_20260711/04-未开始/Figma概念卡_模组库扩充第二批与机制补全_施工方案_20260821.md
--   (Figma 文件 cLWAlpdzEdIh367OP2r2RD「Untitled」17 组游戏概念卡 → 直拼档筛选)
--
-- 结构与幂等策略与第一批(migration_seed_playpack_templates_20260820.sql,
-- 在 feat/playbook-templates 分支)完全一致:
--   cms_topic(is_template=1, member_id=100000, product_type=2, user_status=0 只进货架)
--     + product_role CONTENT/PLATFORM 槽 + 1 章 + N 模板 + N 节点 + blocks 回填
--   幂等键 = cms_topic.name / (member_id=100000, cms_member_template.title) / (topic_id, node.name)
--
-- ★ 全部 8 包零新机制,只用现有 validation_method(1 文字 / 2 拍照 / 3 选项 / 4 扫码 / 5 GPS)。
--   限时类概念(30秒背诵/3秒决断)一律降级为「不承诺限时」文案,TIMER 机制落地后再升级。
--
-- ⚠️ 前置依赖(与第一批相同):
--   1) migration_topic_template_20260707.sql(cms_topic.is_template 列)已落库(master 已有);
--   2) feat/playbook-templates 的 copyNodes 模板归属深拷贝修复([W4闭环])需先或同批部署,
--      否则商家 /use 后在编辑器保存会被 W4 拦。→ 本种子应在第一批之后落库。
--
-- ⚠️ 本文件只准备,不执行。生产库写操作由总控在用户点头后执行(chengyin-db-migrate)。
-- runner: requires=transaction
-- 部署 SHA 必须由外部发布闸回读生产 jar 自报事实；SQL 不猜、不伪造这条证据。
-- ============================================================================


-- ======================= 第一步:事务内写前 fail-closed guard =======================
-- guard 先写入固定主键 1；任一前置/冲突异常再写 1，用 duplicate key 中断并回滚。
CREATE TEMPORARY TABLE seed_playpack2_guard (
    id INT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;
INSERT INTO seed_playpack2_guard (id) VALUES (1);

-- 预期关系表是 guard 的独立真源：按主题、章节、节点和模板逐行比对，
-- 不允许用总数相等掩盖「缺一行 + 重一行」。
CREATE TEMPORARY TABLE seed_playpack_expected_topic (
    seed_tag VARCHAR(64) NOT NULL,
    topic_name VARCHAR(255) NOT NULL,
    chapter_name VARCHAR(255) NOT NULL,
    expected_nodes INT NOT NULL,
    PRIMARY KEY (seed_tag, topic_name)
) ENGINE=InnoDB;

INSERT INTO seed_playpack_expected_topic
    (seed_tag, topic_name, chapter_name, expected_nodes)
VALUES
    ('seed_playpack_20260820', '隐藏菜单', '菜单背面', 1),
    ('seed_playpack_20260820', '店内寻宝', '寻宝图', 3),
    ('seed_playpack_20260820', '拍照挑战', '取景框', 1),
    ('seed_playpack_20260820', '神秘店员的暗号', '接头', 1),
    ('seed_playpack_20260820', '三步解谜', '失物招领', 3),
    ('seed_playpack_20260820', '听音辨物', '闭眼十秒', 1),
    ('seed_playpack_20260820', '三连快答', '快问快答', 3),
    ('seed_playpack_20260820', '集章巡店', '巡店地图', 4),
    ('seed_playpack_20260820', '岔路口的选择', '两封信', 2),
    ('seed_playpack_20260820', '三个盲盒', '拆袋子', 3),
    ('seed_playpack2_20260821', '闭眼味觉师', '盲品席', 1),
    ('seed_playpack2_20260821', '菜单顺口溜', '贯口台', 1),
    ('seed_playpack2_20260821', '沉默点单', '默剧场', 1),
    ('seed_playpack2_20260821', '便利店调酒师', '调配台', 1),
    ('seed_playpack2_20260821', '逆向NPC', '胡话局', 1),
    ('seed_playpack2_20260821', '爪印巡街', '爪印册', 3),
    ('seed_playpack2_20260821', '治愈音乐角', '声音角落', 1),
    ('seed_playpack2_20260821', '穿搭盲配', '盲盒衣柜', 1);

CREATE TEMPORARY TABLE seed_playpack_expected_node (
    seed_tag VARCHAR(64) NOT NULL,
    topic_name VARCHAR(255) NOT NULL,
    node_name VARCHAR(255) NOT NULL,
    template_title VARCHAR(255) NOT NULL,
    sort_id INT NOT NULL,
    validation_method INT NULL,
    PRIMARY KEY (seed_tag, topic_name, node_name)
) ENGINE=InnoDB;

INSERT INTO seed_playpack_expected_node
    (seed_tag, topic_name, node_name, template_title, sort_id, validation_method)
VALUES
    ('seed_playpack_20260820', '隐藏菜单', '解开暗号', '玩法包·隐藏菜单暗号', 1, NULL),
    ('seed_playpack_20260820', '店内寻宝', '第一件:门口', '玩法包·寻宝第一件', 1, NULL),
    ('seed_playpack_20260820', '店内寻宝', '第二件:深处', '玩法包·寻宝第二件', 2, NULL),
    ('seed_playpack_20260820', '店内寻宝', '第三件:收银台', '玩法包·寻宝第三件', 3, NULL),
    ('seed_playpack_20260820', '拍照挑战', '按题拍一张', '玩法包·构图题', 1, NULL),
    ('seed_playpack_20260820', '神秘店员的暗号', '找到知情人', '玩法包·接头回令', 1, NULL),
    ('seed_playpack_20260820', '三步解谜', '第一步:失物', '玩法包·解谜第一步', 1, NULL),
    ('seed_playpack_20260820', '三步解谜', '第二步:线索', '玩法包·解谜第二步', 2, NULL),
    ('seed_playpack_20260820', '三步解谜', '第三步:结局', '玩法包·解谜第三步', 3, NULL),
    ('seed_playpack_20260820', '听音辨物', '听声辨位', '玩法包·店内声音题', 1, NULL),
    ('seed_playpack_20260820', '三连快答', '第一问', '玩法包·快答第一题', 1, NULL),
    ('seed_playpack_20260820', '三连快答', '第二问', '玩法包·快答第二题', 2, NULL),
    ('seed_playpack_20260820', '三连快答', '第三问', '玩法包·快答第三题', 3, NULL),
    ('seed_playpack_20260820', '集章巡店', '印章·东', '玩法包·印章东', 1, NULL),
    ('seed_playpack_20260820', '集章巡店', '印章·南', '玩法包·印章南', 2, NULL),
    ('seed_playpack_20260820', '集章巡店', '印章·西', '玩法包·印章西', 3, NULL),
    ('seed_playpack_20260820', '集章巡店', '印章·北', '玩法包·印章北', 4, NULL),
    ('seed_playpack_20260820', '岔路口的选择', '岔口:两封信', '玩法包·岔口抉择', 1, NULL),
    ('seed_playpack_20260820', '岔路口的选择', '结局:补上那句话', '玩法包·结局由你写', 2, NULL),
    ('seed_playpack_20260820', '三个盲盒', '盲盒一:观察', '玩法包·盲盒之观察', 1, NULL),
    ('seed_playpack_20260820', '三个盲盒', '盲盒二:拍照', '玩法包·盲盒之拍照', 2, NULL),
    ('seed_playpack_20260820', '三个盲盒', '盲盒三:开口', '玩法包·盲盒之开口', 3, NULL),
    ('seed_playpack2_20260821', '闭眼味觉师', '盲品一口', '玩法包2·盲品猜口味', 1, 3),
    ('seed_playpack2_20260821', '菜单顺口溜', '背出贯口', '玩法包2·顺口溜见证照', 1, 2),
    ('seed_playpack2_20260821', '沉默点单', '无声点单', '玩法包2·哑剧接头', 1, 4),
    ('seed_playpack2_20260821', '便利店调酒师', '调一杯上桌', '玩法包2·特调作品照', 1, 2),
    ('seed_playpack2_20260821', '逆向NPC', '听懂胡话', '玩法包2·胡话推理', 1, 1),
    ('seed_playpack2_20260821', '爪印巡街', '第一枚爪印', '玩法包2·爪印第一枚', 1, 2),
    ('seed_playpack2_20260821', '爪印巡街', '第二枚爪印', '玩法包2·爪印第二枚', 2, 2),
    ('seed_playpack2_20260821', '爪印巡街', '第三枚爪印', '玩法包2·爪印第三枚', 3, 2),
    ('seed_playpack2_20260821', '治愈音乐角', '走到角落', '玩法包2·声音坐标', 1, 5),
    ('seed_playpack2_20260821', '穿搭盲配', '盲配上身', '玩法包2·盲配造型照', 1, 2);

-- 第一批必须已完整落库：每个主题、章节、节点→模板、blocks 引用与 CONTENT 槽逐项相等。
-- 只认 create_by='seed_playpack_20260820' 的库内事实，不用文件存在或部署回执代替。
INSERT INTO seed_playpack2_guard (id)
SELECT 1
 WHERE EXISTS (
           SELECT 1
             FROM seed_playpack_expected_topic e
            WHERE e.seed_tag = 'seed_playpack_20260820'
              AND ((SELECT COUNT(*) FROM cms_topic t
                     WHERE t.create_by = e.seed_tag AND t.name = e.topic_name
                       AND t.member_id = 100000 AND t.is_template = 1 AND t.product_type = 2
                       AND t.status = 1 AND t.user_status = 0 AND t.del_flag = 0) <> 1
                OR (SELECT COUNT(*)
                      FROM cms_topic_chapter c
                      JOIN cms_topic t ON t.id = c.topic_id
                     WHERE t.create_by = e.seed_tag AND t.name = e.topic_name
                       AND t.member_id = 100000 AND t.del_flag = 0
                       AND c.create_by = e.seed_tag AND c.name = e.chapter_name
                       AND c.location_count = e.expected_nodes
                       AND c.template_count = e.expected_nodes
                       AND c.blocks IS NOT NULL AND JSON_VALID(c.blocks) = 1) <> 1
                OR (SELECT COUNT(*)
                      FROM product_role r
                      JOIN cms_topic t ON t.id = r.topic_id
                     WHERE t.create_by = e.seed_tag AND t.name = e.topic_name
                       AND t.member_id = 100000 AND t.del_flag = 0
                       AND r.slot = 'CONTENT' AND r.entity_type = 'PLATFORM'
                       AND r.entity_id IS NULL AND r.status = 1) <> 1)
       )
    OR EXISTS (
           SELECT 1
             FROM seed_playpack_expected_node e
            WHERE e.seed_tag = 'seed_playpack_20260820'
              AND (SELECT COUNT(*)
                     FROM cms_topic_node n
                     JOIN cms_topic t ON t.id = n.topic_id
                     JOIN cms_topic_chapter c ON c.id = n.chapter_id AND c.topic_id = t.id
                     JOIN cms_member_template tpl ON tpl.id = n.template_id
                    WHERE t.create_by = e.seed_tag AND t.name = e.topic_name
                      AND t.member_id = 100000 AND t.del_flag = 0
                      AND c.create_by = e.seed_tag
                      AND JSON_VALID(c.blocks) = 1
                      AND LOCATE(CONCAT('"nodeId":', n.id), c.blocks) > 0
                      AND n.create_by = e.seed_tag AND n.name = e.node_name
                      AND n.sort_id = e.sort_id AND n.status = 1
                      AND tpl.create_by = e.seed_tag AND tpl.title = e.template_title
                      AND tpl.member_id = 100000 AND tpl.status = 1
                      AND tpl.draft_status = 1 AND tpl.del_flag = 0) <> 1
       )
    OR (SELECT COUNT(*) FROM cms_topic WHERE create_by = 'seed_playpack_20260820') <> 10
    OR (SELECT COUNT(*) FROM cms_topic_chapter WHERE create_by = 'seed_playpack_20260820') <> 10
    OR (SELECT COUNT(*) FROM cms_member_template WHERE create_by = 'seed_playpack_20260820') <> 22
    OR (SELECT COUNT(*) FROM cms_topic_node WHERE create_by = 'seed_playpack_20260820') <> 22
    OR (SELECT COUNT(*)
          FROM product_role r
          JOIN cms_topic t ON t.id = r.topic_id
         WHERE t.create_by = 'seed_playpack_20260820'
           AND r.slot = 'CONTENT' AND r.entity_type = 'PLATFORM'
           AND r.entity_id IS NULL AND r.status = 1) <> 10;

-- 本批同名主题/同 title 模板只允许不存在，或由本批 create_by 创建；
-- 同一幂等键出现多行同样拒绝，不猜哪行可复用。
INSERT INTO seed_playpack2_guard (id)
SELECT 1
 WHERE EXISTS (
           SELECT 1 FROM cms_topic
            WHERE member_id = 100000 AND del_flag = 0
              AND name IN ('闭眼味觉师','菜单顺口溜','沉默点单','便利店调酒师',
                           '逆向NPC','爪印巡街','治愈音乐角','穿搭盲配')
            GROUP BY name
          HAVING COUNT(*) <> 1 OR COALESCE(MIN(create_by), '') <> 'seed_playpack2_20260821'
       )
    OR EXISTS (
           SELECT 1 FROM cms_member_template
            WHERE member_id = 100000 AND del_flag = 0
              AND title IN ('玩法包2·盲品猜口味','玩法包2·顺口溜见证照','玩法包2·哑剧接头',
                            '玩法包2·特调作品照','玩法包2·胡话推理','玩法包2·爪印第一枚',
                            '玩法包2·爪印第二枚','玩法包2·爪印第三枚','玩法包2·声音坐标',
                            '玩法包2·盲配造型照')
            GROUP BY title
          HAVING COUNT(*) <> 1 OR COALESCE(MIN(create_by), '') <> 'seed_playpack2_20260821'
       );


-- ======================= 第二步:人工只读预检参考 =======================
-- 1) is_template 列是否存在(期望 1 行):
--   SELECT COLUMN_NAME FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cms_topic' AND COLUMN_NAME = 'is_template';
--
-- 2) 现状(本种子未灌时应为 0 行):
--   SELECT id, name, is_template, user_status FROM cms_topic
--    WHERE name IN ('闭眼味觉师','菜单顺口溜','沉默点单','便利店调酒师',
--                   '逆向NPC','爪印巡街','治愈音乐角','穿搭盲配')
--      AND del_flag = 0;
--
-- 3) 模板标题冲突预检(应为 0 行):
--   SELECT id, title FROM cms_member_template
--    WHERE member_id = 100000 AND del_flag = 0 AND title LIKE '玩法包2·%';


-- ============================================================================
-- 包⑪ 闭眼味觉师(饮品店/咖啡/酒吧 · 1 节点 · 选项题盲品)
-- ============================================================================

INSERT INTO cms_topic (member_id, topic_type, product_type, name, subtitle, description,
                       address_name, total_time, location_count, is_template,
                       status, user_status, del_flag, create_by, create_time)
SELECT 100000, 1, 2,
       '闭眼味觉师',
       '闭上眼睛尝一口,猜中它是什么',
       '一场盲品游戏:店员端上一小杯神秘饮品,玩家闭眼品尝后在四个选项里猜出它的真实口味,猜中即通关。适合咖啡店、茶饮店、酒吧。复制后把选项换成你店里真实在售的四款口味,并把正确项设为你端出的那一款。',
       '单店(复制后请在编辑器为节点选择你的店铺位置)',
       10, 1, 1,
       1, 0, 0, 'seed_playpack2_20260821', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_topic WHERE name = '闭眼味觉师' AND member_id = 100000 AND del_flag = 0);

INSERT INTO product_role (topic_id, slot, entity_type, entity_id, status)
SELECT t.id, 'CONTENT', 'PLATFORM', NULL, 1
  FROM cms_topic t
 WHERE t.name = '闭眼味觉师' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM product_role r
                    WHERE r.topic_id = t.id AND r.slot = 'CONTENT' AND r.status = 1);

INSERT INTO cms_topic_chapter (topic_id, name, description, sort_id, location_count, template_count,
                               required, recruit_enabled, max_merchant, terms_mode,
                               create_by, create_time)
SELECT t.id, '盲品席', '视觉下班了,舌头开始加班。', 1, 1, 1,
       1, 0, 0, 'PERK', 'seed_playpack2_20260821', NOW()
  FROM cms_topic t
 WHERE t.name = '闭眼味觉师' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_chapter c
                    WHERE c.topic_id = t.id AND c.name = '盲品席');

INSERT INTO cms_member_template (member_id, title, description, players, usage_location, duration,
                                 difficulty, rule_instructions, validation_method,
                                 question_name, question_a, question_b, question_c, question_d,
                                 correct_answer, hint1, hint2, answer_reveal, feedback_text, xp_value,
                                 draft_status, status, publish_status, check_status, is_sync,
                                 del_flag, create_by, create_time)
SELECT 100000, '玩法包2·盲品猜口味',
       '选项题盲品:闭眼尝一口神秘小样,四选一猜口味。',
       '1-2人', '店内', 10, '简单',
       '商家改造指南:①把四个选项换成你店里真实在售的四款口味;②告知店员本玩法流程:玩家扫码进店后向店员说「我要盲品」,店员端出小样(不透露名称);③正确项设为端出的那一款;④建议在「答案揭示」写上这款产品的正价购买名称,顺手转化。',
       3,
       '闭上眼,喝一口店员端来的神秘小样。它是下面哪一款?',
       '选项A(替换为真实口味)', '选项B(替换为真实口味)', '选项C(替换为真实口味)', '选项D(替换为真实口味)',
       'A',
       '别急着咽下去,让它在舌尖多停两秒。',
       '先分辨甜还是酸,再猜是什么。',
       '猜中了!这一款就在菜单上,想喝完整版就向店员报出它的名字。',
       '舌头也会说谎。再喝一口,这次慢一点。',
       10,
       1, 1, 0, 1, 0,
       0, 'seed_playpack2_20260821', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_member_template
                    WHERE member_id = 100000 AND title = '玩法包2·盲品猜口味' AND del_flag = 0);

INSERT INTO cms_topic_node (topic_id, chapter_id, name, description, template_id, checkin_code,
                            status, sort_id, xp_value, create_by, create_time)
SELECT t.id, c.id, '盲品一口', '扫码进店,向店员要一杯神秘小样,闭眼猜口味。',
       tpl.id, REPLACE(UUID(), '-', ''), 1, 1, 10, 'seed_playpack2_20260821', NOW()
  FROM cms_topic t
  JOIN cms_topic_chapter c ON c.topic_id = t.id AND c.name = '盲品席'
  JOIN cms_member_template tpl ON tpl.member_id = 100000 AND tpl.title = '玩法包2·盲品猜口味' AND tpl.del_flag = 0
 WHERE t.name = '闭眼味觉师' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n WHERE n.topic_id = t.id AND n.name = '盲品一口');

UPDATE cms_topic_chapter c
   SET c.blocks = CONCAT(
       '[{"type":"text","content":"你的眼睛认识这家店的每一款饮品,但你的舌头未必。今天让它单独考试。"},',
       '{"type":"node","nodeId":',
       (SELECT n.id FROM cms_topic_node n WHERE n.topic_id = c.topic_id AND n.name = '盲品一口'),
       '},{"type":"text","content":"不管猜没猜中,你已经比大多数客人更认真地喝过一杯了。"}]')
 WHERE c.name = '盲品席'
   AND c.topic_id = (SELECT t.id FROM cms_topic t
                      WHERE t.name = '闭眼味觉师' AND t.member_id = 100000
                        AND t.is_template = 1 AND t.del_flag = 0);


-- ============================================================================
-- 包⑫ 菜单顺口溜(茶饮/快饮/排队场景 · 1 节点 · 拍照·店员见证)
-- ============================================================================

INSERT INTO cms_topic (member_id, topic_type, product_type, name, subtitle, description,
                       address_name, total_time, location_count, is_template,
                       status, user_status, del_flag, create_by, create_time)
SELECT 100000, 1, 2,
       '菜单顺口溜',
       '一口气背完顺口溜,店员为你作证',
       '把你的招牌产品编成一段顺口溜:玩家抽卡背诵,当着店员的面一口气背全对,和顺口溜卡合影上传即通关。天然适合排队场景,背的人和听的人都乐。复制后把顺口溜换成你家真实产品名串烧,打印一张「顺口溜卡」放在吧台。',
       '单店(复制后请在编辑器为节点选择你的店铺位置)',
       10, 1, 1,
       1, 0, 0, 'seed_playpack2_20260821', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_topic WHERE name = '菜单顺口溜' AND member_id = 100000 AND del_flag = 0);

INSERT INTO product_role (topic_id, slot, entity_type, entity_id, status)
SELECT t.id, 'CONTENT', 'PLATFORM', NULL, 1
  FROM cms_topic t
 WHERE t.name = '菜单顺口溜' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM product_role r
                    WHERE r.topic_id = t.id AND r.slot = 'CONTENT' AND r.status = 1);

INSERT INTO cms_topic_chapter (topic_id, name, description, sort_id, location_count, template_count,
                               required, recruit_enabled, max_merchant, terms_mode,
                               create_by, create_time)
SELECT t.id, '贯口台', '菜单不是用来看的,是用来背的。', 1, 1, 1,
       1, 0, 0, 'PERK', 'seed_playpack2_20260821', NOW()
  FROM cms_topic t
 WHERE t.name = '菜单顺口溜' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_chapter c
                    WHERE c.topic_id = t.id AND c.name = '贯口台');

INSERT INTO cms_member_template (member_id, title, description, players, usage_location, duration,
                                 difficulty, rule_instructions, validation_method,
                                 photo_require_desc, photo_review, feedback_text, story_text, xp_value,
                                 draft_status, status, publish_status, check_status, is_sync,
                                 del_flag, create_by, create_time)
SELECT 100000, '玩法包2·顺口溜见证照',
       '拍照打卡:当店员面背完顺口溜,与顺口溜卡合影上传,商家审核。',
       '1-2人', '店内', 5, '简单',
       '商家改造指南:①把顺口溜换成你家产品名串烧(三到五句最顺口);②打印一张「顺口溜卡」放吧台;③告知店员:玩家背全对后,请把卡递给玩家合影;④审核照片时认卡即可。注意:背诵是否流利由店员现场把关,平台不做限时判定。',
       2,
       '找店员抽一张「顺口溜卡」,当着店员的面一口气背全对,然后手持顺口溜卡和吧台合影上传。背不顺?多来两遍,店员看得开心也算你赢。',
       1,
       '收到!照片审核通过后,你就是本店认证的「贯口大师」。',
       '这段顺口溜是老板熬夜编的,背出来的人都说好——主要是老板说好。',
       10,
       1, 1, 0, 1, 0,
       0, 'seed_playpack2_20260821', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_member_template
                    WHERE member_id = 100000 AND title = '玩法包2·顺口溜见证照' AND del_flag = 0);

INSERT INTO cms_topic_node (topic_id, chapter_id, name, description, template_id, checkin_code,
                            status, sort_id, xp_value, create_by, create_time)
SELECT t.id, c.id, '背出贯口', '扫码进店,抽顺口溜卡,当店员面背全对并合影上传。',
       tpl.id, REPLACE(UUID(), '-', ''), 1, 1, 10, 'seed_playpack2_20260821', NOW()
  FROM cms_topic t
  JOIN cms_topic_chapter c ON c.topic_id = t.id AND c.name = '贯口台'
  JOIN cms_member_template tpl ON tpl.member_id = 100000 AND tpl.title = '玩法包2·顺口溜见证照' AND tpl.del_flag = 0
 WHERE t.name = '菜单顺口溜' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n WHERE n.topic_id = t.id AND n.name = '背出贯口');

UPDATE cms_topic_chapter c
   SET c.blocks = CONCAT(
       '[{"type":"text","content":"排队的时候人最无聊,也最愿意干点傻事。比如,背一段顺口溜。"},',
       '{"type":"node","nodeId":',
       (SELECT n.id FROM cms_topic_node n WHERE n.topic_id = c.topic_id AND n.name = '背出贯口'),
       '}]')
 WHERE c.name = '贯口台'
   AND c.topic_id = (SELECT t.id FROM cms_topic t
                      WHERE t.name = '菜单顺口溜' AND t.member_id = 100000
                        AND t.is_template = 1 AND t.del_flag = 0);


-- ============================================================================
-- 包⑬ 沉默点单(有店员配合意愿的店 · 1 节点 · 扫码核销·店员判定)
-- ============================================================================

INSERT INTO cms_topic (member_id, topic_type, product_type, name, subtitle, description,
                       address_name, total_time, location_count, is_template,
                       status, user_status, del_flag, create_by, create_time)
SELECT 100000, 1, 2,
       '沉默点单',
       '全程禁言,用比划让店员猜中你要喝什么',
       '一场哑剧点单挑战:玩家进店后全程不说话,只用肢体动作或画画让店员猜出想点的产品,店员猜中后放行扫核销码即通关。真人互动拉满,天然出短视频素材。复制后请给店员交底:猜中了才让扫码,猜不中就让客人继续演。',
       '单店(复制后请在编辑器为节点选择你的店铺位置)',
       10, 1, 1,
       1, 0, 0, 'seed_playpack2_20260821', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_topic WHERE name = '沉默点单' AND member_id = 100000 AND del_flag = 0);

INSERT INTO product_role (topic_id, slot, entity_type, entity_id, status)
SELECT t.id, 'CONTENT', 'PLATFORM', NULL, 1
  FROM cms_topic t
 WHERE t.name = '沉默点单' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM product_role r
                    WHERE r.topic_id = t.id AND r.slot = 'CONTENT' AND r.status = 1);

INSERT INTO cms_topic_chapter (topic_id, name, description, sort_id, location_count, template_count,
                               required, recruit_enabled, max_merchant, terms_mode,
                               create_by, create_time)
SELECT t.id, '默剧场', '从进门那一刻起,你失声了。', 1, 1, 1,
       1, 0, 0, 'PERK', 'seed_playpack2_20260821', NOW()
  FROM cms_topic t
 WHERE t.name = '沉默点单' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_chapter c
                    WHERE c.topic_id = t.id AND c.name = '默剧场');

INSERT INTO cms_member_template (member_id, title, description, players, usage_location, duration,
                                 difficulty, rule_instructions, validation_method,
                                 feedback_text, story_text, xp_value,
                                 draft_status, status, publish_status, check_status, is_sync,
                                 del_flag, create_by, create_time)
SELECT 100000, '玩法包2·哑剧接头',
       '扫码核销:全程禁言比划点单,店员猜中后放行扫码。',
       '1-2人', '店内', 10, '中等',
       '商家改造指南:①给店员交底流程——客人做出「拉链嘴」手势即代表挑战开始,全程禁言比划,猜中所点产品后再把核销码给客人扫;②猜不中可以让客人换个演法,气氛越尬越好;③高峰期可婉拒挑战,建议标注冷场时段。',
       4,
       '接头成功!你是今天最安静、也最吵闹的客人。',
       '语言是点单的捷径,今天捷径封路。',
       10,
       1, 1, 0, 1, 0,
       0, 'seed_playpack2_20260821', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_member_template
                    WHERE member_id = 100000 AND title = '玩法包2·哑剧接头' AND del_flag = 0);

INSERT INTO cms_topic_node (topic_id, chapter_id, name, description, template_id, checkin_code,
                            status, sort_id, xp_value, create_by, create_time)
SELECT t.id, c.id, '无声点单', '进店后全程禁言,比划到店员猜中,扫店员出示的码通关。',
       tpl.id, REPLACE(UUID(), '-', ''), 1, 1, 10, 'seed_playpack2_20260821', NOW()
  FROM cms_topic t
  JOIN cms_topic_chapter c ON c.topic_id = t.id AND c.name = '默剧场'
  JOIN cms_member_template tpl ON tpl.member_id = 100000 AND tpl.title = '玩法包2·哑剧接头' AND tpl.del_flag = 0
 WHERE t.name = '沉默点单' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n WHERE n.topic_id = t.id AND n.name = '无声点单');

UPDATE cms_topic_chapter c
   SET c.blocks = CONCAT(
       '[{"type":"text","content":"推门之前,做一个拉上嘴巴拉链的动作。从现在起,你的台词只剩肢体。"},',
       '{"type":"node","nodeId":',
       (SELECT n.id FROM cms_topic_node n WHERE n.topic_id = c.topic_id AND n.name = '无声点单'),
       '},{"type":"text","content":"恭喜通关。现在你可以说话了——不过很多人表示,演上瘾了。"}]')
 WHERE c.name = '默剧场'
   AND c.topic_id = (SELECT t.id FROM cms_topic t
                      WHERE t.name = '沉默点单' AND t.member_id = 100000
                        AND t.is_template = 1 AND t.del_flag = 0);


-- ============================================================================
-- 包⑭ 便利店调酒师(便利店/小酒馆/市集 · 1 节点 · 拍照 DIY)
-- ============================================================================

INSERT INTO cms_topic (member_id, topic_type, product_type, name, subtitle, description,
                       address_name, total_time, location_count, is_template,
                       status, user_status, del_flag, create_by, create_time)
SELECT 100000, 1, 2,
       '便利店调酒师',
       '用店里的原料,调一杯只属于你的特调',
       '一场 DIY 混饮实验:玩家在店内自选两到三种在售原料,亲手调一杯「专属特调」,给成品拍照上传并起名即通关。适合便利店、小酒馆、市集摊位。复制后圈定允许混搭的原料范围,并在审核时给最有创意的起名翻牌。',
       '单店(复制后请在编辑器为节点选择你的店铺位置)',
       15, 1, 1,
       1, 0, 0, 'seed_playpack2_20260821', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_topic WHERE name = '便利店调酒师' AND member_id = 100000 AND del_flag = 0);

INSERT INTO product_role (topic_id, slot, entity_type, entity_id, status)
SELECT t.id, 'CONTENT', 'PLATFORM', NULL, 1
  FROM cms_topic t
 WHERE t.name = '便利店调酒师' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM product_role r
                    WHERE r.topic_id = t.id AND r.slot = 'CONTENT' AND r.status = 1);

INSERT INTO cms_topic_chapter (topic_id, name, description, sort_id, location_count, template_count,
                               required, recruit_enabled, max_merchant, terms_mode,
                               create_by, create_time)
SELECT t.id, '调配台', '货架就是你的酒柜,配方由你说了算。', 1, 1, 1,
       1, 0, 0, 'PERK', 'seed_playpack2_20260821', NOW()
  FROM cms_topic t
 WHERE t.name = '便利店调酒师' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_chapter c
                    WHERE c.topic_id = t.id AND c.name = '调配台');

INSERT INTO cms_member_template (member_id, title, description, players, usage_location, duration,
                                 difficulty, rule_instructions, validation_method,
                                 photo_require_desc, photo_review, feedback_text, story_text, xp_value,
                                 draft_status, status, publish_status, check_status, is_sync,
                                 del_flag, create_by, create_time)
SELECT 100000, '玩法包2·特调作品照',
       '拍照打卡:自选在售原料 DIY 混饮,拍成品照并附配方名,商家审核。',
       '1-2人', '店内', 10, '简单',
       '商家改造指南:①圈定允许混搭的原料范围(建议低客单、高毛利的组合);②准备透明杯让成品上镜;③照片审核时认「成品+所用原料同框」;④可定期把最受欢迎的玩家配方做成真实在售款,形成 UGC 反哺。注意:原料需玩家正常购买,本玩法不含免费试用承诺。',
       2,
       '把你调好的特调和所用的原料摆在一起拍一张,并在照片说明里写上你给它起的名字。原料需在店内正常购买。',
       1,
       '收到你的配方!命名不错,店主审核通过后,说不定它会出现在货架上。',
       '每个深夜便利店里,都藏着一位无证调酒师。今晚轮到你持杯上岗。',
       10,
       1, 1, 0, 1, 0,
       0, 'seed_playpack2_20260821', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_member_template
                    WHERE member_id = 100000 AND title = '玩法包2·特调作品照' AND del_flag = 0);

INSERT INTO cms_topic_node (topic_id, chapter_id, name, description, template_id, checkin_code,
                            status, sort_id, xp_value, create_by, create_time)
SELECT t.id, c.id, '调一杯上桌', '扫码进店,购买原料调一杯专属特调,拍照命名上传。',
       tpl.id, REPLACE(UUID(), '-', ''), 1, 1, 10, 'seed_playpack2_20260821', NOW()
  FROM cms_topic t
  JOIN cms_topic_chapter c ON c.topic_id = t.id AND c.name = '调配台'
  JOIN cms_member_template tpl ON tpl.member_id = 100000 AND tpl.title = '玩法包2·特调作品照' AND tpl.del_flag = 0
 WHERE t.name = '便利店调酒师' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n WHERE n.topic_id = t.id AND n.name = '调一杯上桌');

UPDATE cms_topic_chapter c
   SET c.blocks = CONCAT(
       '[{"type":"text","content":"传说中的「回魂水」没有标准配方,每一杯都是即兴演出。今天,吧台是你的。"},',
       '{"type":"node","nodeId":',
       (SELECT n.id FROM cms_topic_node n WHERE n.topic_id = c.topic_id AND n.name = '调一杯上桌'),
       '}]')
 WHERE c.name = '调配台'
   AND c.topic_id = (SELECT t.id FROM cms_topic t
                      WHERE t.name = '便利店调酒师' AND t.member_id = 100000
                        AND t.is_template = 1 AND t.del_flag = 0);


-- ============================================================================
-- 包⑮ 逆向NPC(任意业态 · 1 节点 · 文字推理)
-- ============================================================================

INSERT INTO cms_topic (member_id, topic_type, product_type, name, subtitle, description,
                       address_name, total_time, location_count, is_template,
                       status, user_status, del_flag, create_by, create_time)
SELECT 100000, 1, 2,
       '逆向NPC',
       '它只会答非所问,答案藏在胡话里',
       '一场反着来的问答:剧情里的 NPC 从不正面回答,玩家要从三句「胡话」里推理出真正的暗号并填写,答对即通关。适合想做记忆点、客群偏年轻的店。复制后把三句胡话和答案换成你店的梗——胡话越离谱,推出来越爽。',
       '单店(复制后请在编辑器为节点选择你的店铺位置)',
       10, 1, 1,
       1, 0, 0, 'seed_playpack2_20260821', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_topic WHERE name = '逆向NPC' AND member_id = 100000 AND del_flag = 0);

INSERT INTO product_role (topic_id, slot, entity_type, entity_id, status)
SELECT t.id, 'CONTENT', 'PLATFORM', NULL, 1
  FROM cms_topic t
 WHERE t.name = '逆向NPC' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM product_role r
                    WHERE r.topic_id = t.id AND r.slot = 'CONTENT' AND r.status = 1);

INSERT INTO cms_topic_chapter (topic_id, name, description, sort_id, location_count, template_count,
                               required, recruit_enabled, max_merchant, terms_mode,
                               create_by, create_time)
SELECT t.id, '胡话局', '问它爱吃什么,它答宇宙大爆炸。', 1, 1, 1,
       1, 0, 0, 'PERK', 'seed_playpack2_20260821', NOW()
  FROM cms_topic t
 WHERE t.name = '逆向NPC' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_chapter c
                    WHERE c.topic_id = t.id AND c.name = '胡话局');

INSERT INTO cms_member_template (member_id, title, description, players, usage_location, duration,
                                 difficulty, rule_instructions, validation_method,
                                 question_name, question_answer, hint1, hint2, answer_reveal,
                                 feedback_text, story_text, xp_value,
                                 draft_status, status, publish_status, check_status, is_sync,
                                 del_flag, create_by, create_time)
SELECT 100000, '玩法包2·胡话推理',
       '文字推理题:NPC 三句答非所问,从胡话里推出真暗号。',
       '1人', '店内', 10, '中等',
       '商家改造指南:①把三句胡话换成你店的梗,每句藏一个字或一条线索;②答案建议是店内真实存在的词(产品名/墙上标语);③在「答案揭示」里解释推理逻辑,让没推出来的人也会心一笑。',
       1,
       '你问它:「今天的暗号是什么?」它答了三句胡话:「日常是从西边升起时才营业。」「月薪要用星星的数量来结算。」「豆腐花开在咖啡机里。」——每句第一个字各藏一字,按顺序连起来就是暗号。',
       '日月豆',
       '别解释胡话,只看每句第一个字。',
       '三句的句首依次是「日」「月」「豆」。',
       '答案是「日月豆」:三句胡话的第一个字依次为「日」「月」「豆」,取字规则唯一,不需要猜意象。',
       '胡话不是没有逻辑,是逻辑绕了个弯。再读一遍,慢慢来。',
       '这家店有个不成文的规矩:问正经问题,得不到正经回答。',
       10,
       1, 1, 0, 1, 0,
       0, 'seed_playpack2_20260821', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_member_template
                    WHERE member_id = 100000 AND title = '玩法包2·胡话推理' AND del_flag = 0);

INSERT INTO cms_topic_node (topic_id, chapter_id, name, description, template_id, checkin_code,
                            status, sort_id, xp_value, create_by, create_time)
SELECT t.id, c.id, '听懂胡话', '扫码进店,读 NPC 的三句胡话,推出真正的暗号。',
       tpl.id, REPLACE(UUID(), '-', ''), 1, 1, 10, 'seed_playpack2_20260821', NOW()
  FROM cms_topic t
  JOIN cms_topic_chapter c ON c.topic_id = t.id AND c.name = '胡话局'
  JOIN cms_member_template tpl ON tpl.member_id = 100000 AND tpl.title = '玩法包2·胡话推理' AND tpl.del_flag = 0
 WHERE t.name = '逆向NPC' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n WHERE n.topic_id = t.id AND n.name = '听懂胡话');

UPDATE cms_topic_chapter c
   SET c.blocks = CONCAT(
       '[{"type":"text","content":"店里住着一位 NPC。它知道所有答案,但拒绝好好说话。"},',
       '{"type":"node","nodeId":',
       (SELECT n.id FROM cms_topic_node n WHERE n.topic_id = c.topic_id AND n.name = '听懂胡话'),
       '},{"type":"text","content":"你听懂了胡话。从今天起,你也是能和 NPC 对上频道的人了。"}]')
 WHERE c.name = '胡话局'
   AND c.topic_id = (SELECT t.id FROM cms_topic t
                      WHERE t.name = '逆向NPC' AND t.member_id = 100000
                        AND t.is_template = 1 AND t.del_flag = 0);


-- ============================================================================
-- 包⑯ 爪印巡街(宠物友好街区/多店联动 · 3 节点 · 拍照集章)
-- ============================================================================

INSERT INTO cms_topic (member_id, topic_type, product_type, name, subtitle, description,
                       address_name, total_time, location_count, is_template,
                       status, user_status, del_flag, create_by, create_time)
SELECT 100000, 1, 2,
       '爪印巡街',
       '找到三家店的店宠,集齐它们的「爪印」',
       '一条为毛孩子爱好者设计的巡街线:三家店各住着一位店宠,玩家依次探店,拍下与店宠的合照(或它的睡姿)即集得一枚爪印,三枚集齐通关。适合宠物友好街区、多店联动。复制后为每个节点绑定真实店铺,并确认店宠当值时间。',
       '街区(复制后请在编辑器为各节点选择店铺位置)',
       60, 3, 1,
       1, 0, 0, 'seed_playpack2_20260821', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_topic WHERE name = '爪印巡街' AND member_id = 100000 AND del_flag = 0);

INSERT INTO product_role (topic_id, slot, entity_type, entity_id, status)
SELECT t.id, 'CONTENT', 'PLATFORM', NULL, 1
  FROM cms_topic t
 WHERE t.name = '爪印巡街' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM product_role r
                    WHERE r.topic_id = t.id AND r.slot = 'CONTENT' AND r.status = 1);

INSERT INTO cms_topic_chapter (topic_id, name, description, sort_id, location_count, template_count,
                               required, recruit_enabled, max_merchant, terms_mode,
                               create_by, create_time)
SELECT t.id, '爪印册', '这条街由三位毛茸茸的地头蛇罩着。', 1, 3, 3,
       1, 0, 0, 'PERK', 'seed_playpack2_20260821', NOW()
  FROM cms_topic t
 WHERE t.name = '爪印巡街' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_chapter c
                    WHERE c.topic_id = t.id AND c.name = '爪印册');

INSERT INTO cms_member_template (member_id, title, description, players, usage_location, duration,
                                 difficulty, rule_instructions, validation_method,
                                 photo_require_desc, photo_review, feedback_text, story_text, xp_value,
                                 draft_status, status, publish_status, check_status, is_sync,
                                 del_flag, create_by, create_time)
SELECT 100000, '玩法包2·爪印第一枚',
       '拍照集章:找到第一家店的店宠,拍照留档。',
       '1-2人', '店内', 15, '简单',
       '商家改造指南:①在题面写清店宠的名字、品种和它常待的位置;②注明「它不想理你时请勿强撸」;③店宠休假时在店内放它的立牌,拍立牌同样算数。',
       2,
       '这家店的首席员工是一位毛孩子。找到它,拍一张它的近照(睡着也算,它营业与否全看心情)。它今天休假的话,拍它的立牌也算集章成功。',
       1,
       '第一枚爪印到手!它可能没看你一眼,但你已经记住它了。',
       '据街坊说,这条街真正的主理人从来不是人。',
       10,
       1, 1, 0, 1, 0,
       0, 'seed_playpack2_20260821', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_member_template
                    WHERE member_id = 100000 AND title = '玩法包2·爪印第一枚' AND del_flag = 0);

INSERT INTO cms_member_template (member_id, title, description, players, usage_location, duration,
                                 difficulty, rule_instructions, validation_method,
                                 photo_require_desc, photo_review, feedback_text, story_text, xp_value,
                                 draft_status, status, publish_status, check_status, is_sync,
                                 del_flag, create_by, create_time)
SELECT 100000, '玩法包2·爪印第二枚',
       '拍照集章:找到第二家店的店宠,拍照留档。',
       '1-2人', '店内', 15, '简单',
       '商家改造指南:同第一枚;建议三家店的店宠花色/品种各不相同,集齐才有「图鉴感」。',
       2,
       '第二位毛茸茸主理人在这家店当值。拍下它的照片——如果它主动蹭你,恭喜,你触发了隐藏剧情。它休假则拍立牌。',
       1,
       '第二枚爪印到手!还差最后一位,坚持住。',
       '每一家宠物友好的店,都是这座城市温柔的证据。',
       10,
       1, 1, 0, 1, 0,
       0, 'seed_playpack2_20260821', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_member_template
                    WHERE member_id = 100000 AND title = '玩法包2·爪印第二枚' AND del_flag = 0);

INSERT INTO cms_member_template (member_id, title, description, players, usage_location, duration,
                                 difficulty, rule_instructions, validation_method,
                                 photo_require_desc, photo_review, feedback_text, story_text, xp_value,
                                 draft_status, status, publish_status, check_status, is_sync,
                                 del_flag, create_by, create_time)
SELECT 100000, '玩法包2·爪印第三枚',
       '拍照集章:找到第三家店的店宠,集齐通关。',
       '1-2人', '店内', 15, '简单',
       '商家改造指南:同前两枚;通关奖励建议三家店联合出资(如联合优惠券),让玩家为了最后一枚爪印走完全程。',
       2,
       '最后一位毛孩子在此镇店。拍下它,集齐三枚爪印。它休假则拍立牌——但据说它全年无休,因为它的工位是收银台。',
       1,
       '三枚爪印集齐!这条街的毛茸茸议会已一致通过:你是它们认证的朋友。',
       '巡街结束。你没买多少东西,但你摸到了整条街最柔软的部分。',
       10,
       1, 1, 0, 1, 0,
       0, 'seed_playpack2_20260821', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_member_template
                    WHERE member_id = 100000 AND title = '玩法包2·爪印第三枚' AND del_flag = 0);

INSERT INTO cms_topic_node (topic_id, chapter_id, name, description, template_id, checkin_code,
                            status, sort_id, xp_value, create_by, create_time)
SELECT t.id, c.id, '第一枚爪印', '到第一家店,找到店宠拍照集章。',
       tpl.id, REPLACE(UUID(), '-', ''), 1, 1, 10, 'seed_playpack2_20260821', NOW()
  FROM cms_topic t
  JOIN cms_topic_chapter c ON c.topic_id = t.id AND c.name = '爪印册'
  JOIN cms_member_template tpl ON tpl.member_id = 100000 AND tpl.title = '玩法包2·爪印第一枚' AND tpl.del_flag = 0
 WHERE t.name = '爪印巡街' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n WHERE n.topic_id = t.id AND n.name = '第一枚爪印');

INSERT INTO cms_topic_node (topic_id, chapter_id, name, description, template_id, checkin_code,
                            status, sort_id, xp_value, create_by, create_time)
SELECT t.id, c.id, '第二枚爪印', '到第二家店,找到店宠拍照集章。',
       tpl.id, REPLACE(UUID(), '-', ''), 1, 2, 10, 'seed_playpack2_20260821', NOW()
  FROM cms_topic t
  JOIN cms_topic_chapter c ON c.topic_id = t.id AND c.name = '爪印册'
  JOIN cms_member_template tpl ON tpl.member_id = 100000 AND tpl.title = '玩法包2·爪印第二枚' AND tpl.del_flag = 0
 WHERE t.name = '爪印巡街' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n WHERE n.topic_id = t.id AND n.name = '第二枚爪印');

INSERT INTO cms_topic_node (topic_id, chapter_id, name, description, template_id, checkin_code,
                            status, sort_id, xp_value, create_by, create_time)
SELECT t.id, c.id, '第三枚爪印', '到第三家店,找到店宠拍照集齐通关。',
       tpl.id, REPLACE(UUID(), '-', ''), 1, 3, 10, 'seed_playpack2_20260821', NOW()
  FROM cms_topic t
  JOIN cms_topic_chapter c ON c.topic_id = t.id AND c.name = '爪印册'
  JOIN cms_member_template tpl ON tpl.member_id = 100000 AND tpl.title = '玩法包2·爪印第三枚' AND tpl.del_flag = 0
 WHERE t.name = '爪印巡街' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n WHERE n.topic_id = t.id AND n.name = '第三枚爪印');

UPDATE cms_topic_chapter c
   SET c.blocks = CONCAT(
       '[{"type":"text","content":"这条街的每家店都有一位不领工资的主理人:有的负责睡觉,有的负责监工,有的负责在你结账时踩键盘。"},',
       '{"type":"node","nodeId":',
       (SELECT n.id FROM cms_topic_node n WHERE n.topic_id = c.topic_id AND n.name = '第一枚爪印'),
       '},{"type":"node","nodeId":',
       (SELECT n.id FROM cms_topic_node n WHERE n.topic_id = c.topic_id AND n.name = '第二枚爪印'),
       '},{"type":"node","nodeId":',
       (SELECT n.id FROM cms_topic_node n WHERE n.topic_id = c.topic_id AND n.name = '第三枚爪印'),
       '},{"type":"text","content":"三枚爪印,一条街的柔软全被你收进了口袋。"}]')
 WHERE c.name = '爪印册'
   AND c.topic_id = (SELECT t.id FROM cms_topic t
                      WHERE t.name = '爪印巡街' AND t.member_id = 100000
                        AND t.is_template = 1 AND t.del_flag = 0);


-- ============================================================================
-- 包⑰ 治愈音乐角(公园/书店/庭院/安静空间 · 1 节点 · GPS 到达)
-- ============================================================================

INSERT INTO cms_topic (member_id, topic_type, product_type, name, subtitle, description,
                       address_name, total_time, location_count, is_template,
                       status, user_status, del_flag, create_by, create_time)
SELECT 100000, 1, 2,
       '治愈音乐角',
       '走到那个角落,一段只属于这里的声音就会响起',
       '一处用脚步解锁的声音彩蛋:玩家走到指定角落(GPS 判定到达),解锁一段专属音频——可以是白噪音、店主录的一段话、这条街的声音故事。适合公园、书店、庭院、任何有「角落感」的空间。复制后选好点位,并在编辑器为章节添加你的音频(或用节点语音讲解上传一段 10 秒手机录音即可)。',
       '单点(复制后请在编辑器为节点选择位置)',
       10, 1, 1,
       1, 0, 0, 'seed_playpack2_20260821', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_topic WHERE name = '治愈音乐角' AND member_id = 100000 AND del_flag = 0);

INSERT INTO product_role (topic_id, slot, entity_type, entity_id, status)
SELECT t.id, 'CONTENT', 'PLATFORM', NULL, 1
  FROM cms_topic t
 WHERE t.name = '治愈音乐角' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM product_role r
                    WHERE r.topic_id = t.id AND r.slot = 'CONTENT' AND r.status = 1);

INSERT INTO cms_topic_chapter (topic_id, name, description, sort_id, location_count, template_count,
                               required, recruit_enabled, max_merchant, terms_mode,
                               create_by, create_time)
SELECT t.id, '声音角落', '有些声音,只在一个坐标上生效。', 1, 1, 1,
       1, 0, 0, 'PERK', 'seed_playpack2_20260821', NOW()
  FROM cms_topic t
 WHERE t.name = '治愈音乐角' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_chapter c
                    WHERE c.topic_id = t.id AND c.name = '声音角落');

INSERT INTO cms_member_template (member_id, title, description, players, usage_location, duration,
                                 difficulty, rule_instructions, validation_method,
                                 feedback_text, story_text, xp_value,
                                 draft_status, status, publish_status, check_status, is_sync,
                                 del_flag, create_by, create_time)
SELECT 100000, '玩法包2·声音坐标',
       'GPS 到达:走到指定角落,解锁一段只属于这里的声音。',
       '1人', '户外/店内均可', 10, '简单',
       '商家改造指南:①在编辑器为节点选好点位(GPS 判定半径约 50 米,选空旷处更稳);②为这个节点上传一段「语音讲解」音频——白噪音、你录的一段话、或这个角落的声音故事,手机录 10 秒即可;③到达后玩家会看到你的文字反馈并可播放音频,建议文案引导玩家戴上耳机。',
       5,
       '你到了。戴上耳机,点开这段声音——它只在这个坐标上有意义。',
       '城市很吵,但总有几个角落替你把音量调小了。',
       10,
       1, 1, 0, 1, 0,
       0, 'seed_playpack2_20260821', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_member_template
                    WHERE member_id = 100000 AND title = '玩法包2·声音坐标' AND del_flag = 0);

INSERT INTO cms_topic_node (topic_id, chapter_id, name, description, template_id, checkin_code,
                            status, sort_id, xp_value, create_by, create_time)
SELECT t.id, c.id, '走到角落', '按地图走到那个角落,声音会自己找到你。',
       tpl.id, REPLACE(UUID(), '-', ''), 1, 1, 10, 'seed_playpack2_20260821', NOW()
  FROM cms_topic t
  JOIN cms_topic_chapter c ON c.topic_id = t.id AND c.name = '声音角落'
  JOIN cms_member_template tpl ON tpl.member_id = 100000 AND tpl.title = '玩法包2·声音坐标' AND tpl.del_flag = 0
 WHERE t.name = '治愈音乐角' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n WHERE n.topic_id = t.id AND n.name = '走到角落');

UPDATE cms_topic_chapter c
   SET c.blocks = CONCAT(
       '[{"type":"text","content":"这个角落被人悄悄放了一段声音。地图知道位置,耳机知道内容。"},',
       '{"type":"node","nodeId":',
       (SELECT n.id FROM cms_topic_node n WHERE n.topic_id = c.topic_id AND n.name = '走到角落'),
       '},{"type":"text","content":"听完了?不必急着走。这个坐标现在也有你待过的一分钟。"}]')
 WHERE c.name = '声音角落'
   AND c.topic_id = (SELECT t.id FROM cms_topic t
                      WHERE t.name = '治愈音乐角' AND t.member_id = 100000
                        AND t.is_template = 1 AND t.del_flag = 0);


-- ============================================================================
-- 包⑱ 穿搭盲配(服装店/买手店/古着店 · 1 节点 · 拍照挑战)
-- ============================================================================

INSERT INTO cms_topic (member_id, topic_type, product_type, name, subtitle, description,
                       address_name, total_time, location_count, is_template,
                       status, user_status, del_flag, create_by, create_time)
SELECT 100000, 1, 2,
       '穿搭盲配',
       '闭眼抽三件,穿出一套奇迹',
       '一场反常识的穿搭实验:店员从店内现货里盲抽上衣、下装、配饰各一件(或玩家闭眼随机指),玩家用这三件随机单品搭出一套完整造型并试穿拍照,由店主审核「敢穿指数」。适合服装店、买手店、古着店。复制后圈定可参与盲抽的货区。',
       '单店(复制后请在编辑器为节点选择你的店铺位置)',
       20, 1, 1,
       1, 0, 0, 'seed_playpack2_20260821', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_topic WHERE name = '穿搭盲配' AND member_id = 100000 AND del_flag = 0);

INSERT INTO product_role (topic_id, slot, entity_type, entity_id, status)
SELECT t.id, 'CONTENT', 'PLATFORM', NULL, 1
  FROM cms_topic t
 WHERE t.name = '穿搭盲配' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM product_role r
                    WHERE r.topic_id = t.id AND r.slot = 'CONTENT' AND r.status = 1);

INSERT INTO cms_topic_chapter (topic_id, name, description, sort_id, location_count, template_count,
                               required, recruit_enabled, max_merchant, terms_mode,
                               create_by, create_time)
SELECT t.id, '盲盒衣柜', '风格是规则,今天规则放假。', 1, 1, 1,
       1, 0, 0, 'PERK', 'seed_playpack2_20260821', NOW()
  FROM cms_topic t
 WHERE t.name = '穿搭盲配' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_chapter c
                    WHERE c.topic_id = t.id AND c.name = '盲盒衣柜');

INSERT INTO cms_member_template (member_id, title, description, players, usage_location, duration,
                                 difficulty, rule_instructions, validation_method,
                                 photo_require_desc, photo_review, feedback_text, story_text, xp_value,
                                 draft_status, status, publish_status, check_status, is_sync,
                                 del_flag, create_by, create_time)
SELECT 100000, '玩法包2·盲配造型照',
       '拍照挑战:盲抽三件随机单品搭一套造型,试穿拍照,店主审核。',
       '1-2人', '店内', 20, '中等',
       '商家改造指南:①圈定可参与盲抽的货区(建议放入压箱货和试错款,盲配是它们的高光时刻);②盲抽方式二选一:店员抽或玩家闭眼指;③审核标准是「三件都上身+造型完整」,不是好看——越离谱越出片;④拍照墙/试衣镜位建议贴玩法海报,方便路人围观入坑。',
       2,
       '用盲抽到的上衣、下装、配饰完成一套完整造型,试穿后在店内拍一张全身照上传。三件单品必须全部上身,姿势越自信越好。',
       1,
       '收到你的造型!敢穿就已经赢了一半,店主正在给「敢穿指数」打分。',
       '每一件被冷落的单品,都在等一个不按常理出牌的人。',
       10,
       1, 1, 0, 1, 0,
       0, 'seed_playpack2_20260821', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_member_template
                    WHERE member_id = 100000 AND title = '玩法包2·盲配造型照' AND del_flag = 0);

INSERT INTO cms_topic_node (topic_id, chapter_id, name, description, template_id, checkin_code,
                            status, sort_id, xp_value, create_by, create_time)
SELECT t.id, c.id, '盲配上身', '扫码进店,盲抽三件单品,搭一套敢穿的造型拍照上传。',
       tpl.id, REPLACE(UUID(), '-', ''), 1, 1, 10, 'seed_playpack2_20260821', NOW()
  FROM cms_topic t
  JOIN cms_topic_chapter c ON c.topic_id = t.id AND c.name = '盲盒衣柜'
  JOIN cms_member_template tpl ON tpl.member_id = 100000 AND tpl.title = '玩法包2·盲配造型照' AND tpl.del_flag = 0
 WHERE t.name = '穿搭盲配' AND t.member_id = 100000 AND t.is_template = 1 AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n WHERE n.topic_id = t.id AND n.name = '盲配上身');

UPDATE cms_topic_chapter c
   SET c.blocks = CONCAT(
       '[{"type":"text","content":"你的衣柜里全是安全牌。这家店今天没收了你的安全牌。"},',
       '{"type":"node","nodeId":',
       (SELECT n.id FROM cms_topic_node n WHERE n.topic_id = c.topic_id AND n.name = '盲配上身'),
       '},{"type":"text","content":"造型完成。也许你不会买下这三件,但你会记住敢穿的自己。"}]')
 WHERE c.name = '盲盒衣柜'
   AND c.topic_id = (SELECT t.id FROM cms_topic t
                      WHERE t.name = '穿搭盲配' AND t.member_id = 100000
                        AND t.is_template = 1 AND t.del_flag = 0);


-- ======================= 第三步:事务内写后精确断言 =======================
-- 逐主题验货章节和 CONTENT 槽，逐节点验货预期模板、顺序、校验方式与 blocks 引用。
-- 任一行缺失、重复或节点→模板互换，再写固定主键 1 立即红并回滚。
INSERT INTO seed_playpack2_guard (id)
SELECT 1
 WHERE EXISTS (
           SELECT 1
             FROM seed_playpack_expected_topic e
            WHERE e.seed_tag = 'seed_playpack2_20260821'
              AND ((SELECT COUNT(*) FROM cms_topic t
                     WHERE t.create_by = e.seed_tag AND t.name = e.topic_name
                       AND t.member_id = 100000 AND t.is_template = 1 AND t.product_type = 2
                       AND t.status = 1 AND t.user_status = 0 AND t.del_flag = 0) <> 1
                OR (SELECT COUNT(*)
                      FROM cms_topic_chapter c
                      JOIN cms_topic t ON t.id = c.topic_id
                     WHERE t.create_by = e.seed_tag AND t.name = e.topic_name
                       AND t.member_id = 100000 AND t.del_flag = 0
                       AND c.create_by = e.seed_tag AND c.name = e.chapter_name
                       AND c.location_count = e.expected_nodes
                       AND c.template_count = e.expected_nodes
                       AND c.blocks IS NOT NULL AND JSON_VALID(c.blocks) = 1) <> 1
                OR (SELECT COUNT(*)
                      FROM product_role r
                      JOIN cms_topic t ON t.id = r.topic_id
                     WHERE t.create_by = e.seed_tag AND t.name = e.topic_name
                       AND t.member_id = 100000 AND t.del_flag = 0
                       AND r.slot = 'CONTENT' AND r.entity_type = 'PLATFORM'
                       AND r.entity_id IS NULL AND r.status = 1) <> 1)
       )
    OR EXISTS (
           SELECT 1
             FROM seed_playpack_expected_node e
            WHERE e.seed_tag = 'seed_playpack2_20260821'
              AND (SELECT COUNT(*)
                     FROM cms_topic_node n
                     JOIN cms_topic t ON t.id = n.topic_id
                     JOIN cms_topic_chapter c ON c.id = n.chapter_id AND c.topic_id = t.id
                     JOIN cms_member_template tpl ON tpl.id = n.template_id
                    WHERE t.create_by = e.seed_tag AND t.name = e.topic_name
                      AND t.member_id = 100000 AND t.del_flag = 0
                      AND c.create_by = e.seed_tag
                      AND JSON_VALID(c.blocks) = 1
                      AND LOCATE(CONCAT('"nodeId":', n.id), c.blocks) > 0
                      AND n.create_by = e.seed_tag AND n.name = e.node_name
                      AND n.sort_id = e.sort_id AND n.status = 1 AND n.xp_value = 10
                      AND tpl.create_by = e.seed_tag AND tpl.title = e.template_title
                      AND tpl.member_id = 100000 AND tpl.status = 1
                      AND tpl.draft_status = 1 AND tpl.del_flag = 0
                      AND tpl.validation_method = e.validation_method) <> 1
       )
    OR (SELECT COUNT(*) FROM cms_topic WHERE create_by = 'seed_playpack2_20260821') <> 8
    OR (SELECT COUNT(*) FROM cms_topic_chapter WHERE create_by = 'seed_playpack2_20260821') <> 8
    OR (SELECT COUNT(*) FROM cms_member_template WHERE create_by = 'seed_playpack2_20260821') <> 10
    OR (SELECT COUNT(*) FROM cms_topic_node WHERE create_by = 'seed_playpack2_20260821') <> 10
    OR (SELECT COUNT(*)
          FROM product_role r
          JOIN cms_topic t ON t.id = r.topic_id
         WHERE t.create_by = 'seed_playpack2_20260821'
           AND r.slot = 'CONTENT' AND r.entity_type = 'PLATFORM'
           AND r.entity_id IS NULL AND r.status = 1) <> 8;

-- 事务内 guard 只防提交前漂移；COMMIT 后仍必须换新的只读连接，从业务表回读同一组精确断言。
