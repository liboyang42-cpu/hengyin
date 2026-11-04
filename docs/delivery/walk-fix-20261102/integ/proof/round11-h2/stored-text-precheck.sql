-- 第十一轮 h2 第 2 项:改名迁移(migration_20261104_review_wording_seeded_text.sql)执行前的只读预检。
-- 只 SELECT,不写。①–③ 是迁移会改的平台种子行(期望值见每段注释,0 表示已改过或没有这条种子);
-- ④ 是迁移不会碰、交业主看的作者 / 商家复制件数量。

-- ① 平台种子里仍是旧文字的行数(执行前期望:主题 1+1、节点 3、玩法 3+4、章节 1;执行后全为 0)
SELECT 'cms_topic.name/description' AS what,
       SUM(name = '三个盲盒' COLLATE utf8mb4_bin) AS old_name,
       SUM(description LIKE '三个「盲盒袋子」%' COLLATE utf8mb4_bin) AS old_description
  FROM cms_topic WHERE member_id = 100000 AND is_template = 1 AND create_by = 'seed_playpack_20260820';
SELECT 'cms_topic_node.name' AS what, COUNT(*) AS old_rows
  FROM cms_topic_node n JOIN cms_topic t ON t.id = n.topic_id
 WHERE t.member_id = 100000 AND t.create_by = 'seed_playpack_20260820' AND n.create_by = 'seed_playpack_20260820'
   AND n.name COLLATE utf8mb4_bin IN ('盲盒一:观察', '盲盒二:拍照', '盲盒三:开口');
SELECT 'cms_member_template' AS what, COUNT(*) AS rows_with_old_text
  FROM cms_member_template WHERE member_id = 100000 AND create_by = 'seed_playpack_20260820'
   AND (title LIKE '玩法包·盲盒之%' OR question_name LIKE '盲盒%' OR photo_require_desc LIKE '盲盒%'
        OR answer_reveal LIKE '三个盲盒%');
SELECT 'cms_topic_chapter.name' AS what, COUNT(*) AS old_rows
  FROM cms_topic_chapter c JOIN cms_topic t ON t.id = c.topic_id
 WHERE t.create_by = 'seed_playpack2_20260821' AND c.create_by = 'seed_playpack2_20260821' AND c.name = '盲盒衣柜';

-- ② 后台菜单(期望:名称 6 条、备注 8 条带「探店日」)
SELECT perms, menu_name, LEFT(remark, 20) AS remark_head FROM sys_menu
 WHERE perms IN ('business:clubhours:confirm','business:clubextra:approve','business:clubquality:confirm',
                 'business:clubquality:exclude','business:editionfinal:execute','business:clubhours:correct',
                 'business:verificationReview:list','business:topicnode:add','business:topicnode:edit',
                 'business:topicnode:remove','business:roamRevisitReport:list','business:withdrawal:cooldown-config',
                 'business:clubpayee:verify','business:clubpayee:payout');

-- ③ 定时任务(期望 1 行,job_name = 竞猜轮次超时作废)
SELECT job_id, job_name, invoke_target, status, remark FROM sys_job WHERE invoke_target = 'predictVoidExpiredTask.run()';

-- ④ 交业主:迁移不碰的作者 / 商家复制件(create_by 不是种子,或归属不是平台)。
--   菜单与任务两句请在迁移之后跑:那时还剩的就是有人手改过、迁移按规则没碰的行。
SELECT 'topic copies' AS what, COUNT(*) AS n FROM cms_topic
 WHERE del_flag = 0 AND (name LIKE '%盲盒%' OR description LIKE '%盲盒%')
   AND NOT (member_id = 100000 AND create_by IN ('seed_playpack_20260820', 'seed_playpack2_20260821'));
SELECT 'node copies' AS what, COUNT(*) AS n FROM cms_topic_node
 WHERE name LIKE '%盲盒%' AND COALESCE(create_by, '') NOT IN ('seed_playpack_20260820', 'seed_playpack2_20260821');
SELECT 'chapter copies' AS what, COUNT(*) AS n FROM cms_topic_chapter
 WHERE name LIKE '%盲盒%' AND COALESCE(create_by, '') NOT IN ('seed_playpack_20260820', 'seed_playpack2_20260821');
SELECT 'game copies' AS what, COUNT(*) AS n FROM cms_member_template
 WHERE del_flag = 0 AND (title LIKE '%盲盒%' OR question_name LIKE '%盲盒%' OR photo_require_desc LIKE '%盲盒%'
        OR answer_reveal LIKE '%盲盒%')
   AND NOT (member_id = 100000 AND create_by = 'seed_playpack_20260820');
SELECT 'menus edited by hand' AS what, COUNT(*) AS n FROM sys_menu
 WHERE (menu_name LIKE '%探店日%' OR remark LIKE '%探店日%');
SELECT 'jobs named 竞猜 by hand' AS what, COUNT(*) AS n FROM sys_job
 WHERE (job_name LIKE '%竞猜%' OR remark LIKE '%竞猜%');
