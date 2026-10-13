-- 提审前清理生产脏数据(2026-08-19)。用户 2026-08-19 明确裁决「脏数据删除」。
--
-- 病:提审时审核员看到的第一屏就是测试残留。
--   ① 广场(ums_creative_square)在架 35 条里有 13 条是键盘乱敲/联调残留:
--      hi / jo / 哈哈哈哈哈哈啊哈 / 123123123 / 呵呵呵 / 222 / 1111 /
--      横说竖说睡觉睡觉 / 江苏在身上 / 还是好多好多好多话 / 横说竖说上课 / 哈哈 /
--      「我就在想怎么做一只手呢哈哈……」(语无伦次)
--   ② 玩法说明(cms_infomation)整表只有 1 行,就是 title='11' contents='<p>123123123</p>'。
--      它是 /api/common/infomation_detail 的唯一数据源,B37「玩法详情」拍到的就是它。
--
-- 两张表处置方式不同,因为它们的能力不同:
--   · ums_creative_square 有 del_flag ⇒ **软删(del_flag=1)**,可逆,且 App 侧本来就按 del_flag=0 过滤。
--   · cms_infomation **没有 del_flag** ⇒ 只能硬删。整行原样备份在下面,要恢复直接照抄 INSERT。
--     删后「玩法文档」落到既有空态(「暂无玩法说明 · 内容上线后会出现在这里」),比现在好。
--
-- ★ 硬删行的完整备份(2026-08-19 只读取自生产,恢复用):
--   INSERT INTO cms_infomation (id, title, subtitle, sort_id, contents, create_by, create_time, update_by, update_time)
--   VALUES (1, '11', '11', 11, '<p>123123123</p>', NULL, '2025-11-24 12:31:44', NULL, NULL);
--
-- ⚠️ 本文件**不动**另外 22 条:
--   · id=37 是一段完整的《怪奇物语》追剧感想 —— 真人写的真内容,只是跑题,不是脏数据。
--   · id=1..26 那 21 条是 2025-11-01/02 整点批量生成的种子文案,属运营内容,去留另议。
--   判据是「是不是键盘乱敲/联调残留」,不是「我觉得它好不好」。
--
-- 幂等:锚在 contents 的 MD5 上,软删不改 contents ⇒ 前置断言重跑仍成立;
--       UPDATE 带 del_flag=0、DELETE 带 title/contents 全等,重跑命中 0 行。

-- 前置断言:那 13 个 id 必须仍然携带我 2026-08-19 读到的那 13 份内容。
-- 任何一条对不上 ⇒ 生产漂移了(行被编辑/被复用/id 被回收),这份迁移就不该跑 —— 停在这。
-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM ums_creative_square WHERE (id, MD5(contents)) IN (
          (53,'49f68a5c8493ec2c0bf489821c21fc3b'), (52,'733ebe8f5da1266b568f2a10487297ef'),
          (51,'aa2adf6bfc13ed277460fa125e124e79'), (42,'f5bb0c8de146c67b44babbf4e6584cc0'),
          (41,'92cf14790880a1d5447076517eea73ac'), (40,'bcbe3365e6ac95ea2c0343a2395834dd'),
          (39,'b59c67bf196a4758191e42f76670ceba'), (38,'38cd8ff4adbefc76d2d692deaec7c522'),
          (36,'2154665a69b61cd1be200c0c54401fc2'), (35,'22970d462b85911157fc593b1ae74a0d'),
          (34,'3b6bdf8c7c7cae9b0dae156ce2b07890'), (33,'8c8fa3529ee34d4e69a0baafb7069da3'),
          (32,'78b696c49a73858cd676341e67e9dcc4'))) = 13
   AND (SELECT COUNT(*) FROM cms_infomation) = 1
   AND (SELECT COUNT(*) FROM cms_infomation
        WHERE id = 1 AND title = '11' AND contents = '<p>123123123</p>') = 1;

UPDATE ums_creative_square
   SET del_flag = 1, update_by = 'migration_20260819_purge_prelaunch_dirty_content'
 WHERE id IN (53,52,51,42,41,40,39,38,36,35,34,33,32)
   AND del_flag = 0;

DELETE FROM cms_infomation
 WHERE id = 1 AND title = '11' AND contents = '<p>123123123</p>';

-- 回读合同:从对面读回来,不是回执。
--   ① 那 13 条一条都不许再以 del_flag=0 出现(=App 侧广场再也读不到)
--   ② 用同一份 MD5 名单反查,确认我软删掉的正好是它们、没有多杀
--   ③ cms_infomation 清空
-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM ums_creative_square
        WHERE id IN (53,52,51,42,41,40,39,38,36,35,34,33,32) AND del_flag = 0) = 0
   AND (SELECT COUNT(*) FROM ums_creative_square
        WHERE id IN (53,52,51,42,41,40,39,38,36,35,34,33,32) AND del_flag = 1) = 13
   AND (SELECT COUNT(*) FROM cms_infomation) = 0;

-- 回读合同:没有误伤。id=37 与 21 条种子必须仍然在架。
-- 少一条都说明 WHERE 写宽了 —— 那比不清理更糟。
-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM ums_creative_square WHERE id = 37 AND del_flag = 0) = 1
   AND (SELECT COUNT(*) FROM ums_creative_square WHERE del_flag = 0) = 22;
