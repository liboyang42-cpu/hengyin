-- 探店日 批5 · T1:节点打卡码存量回填 + 唯一索引。
--
-- 【类别】③ 要在库上跑(DDL + 回填 DML)。落库先于合并 —— 唯一索引不落库,
--   撞码仍会被 /api/play/checkin 的 selectCmsTopicNodeList().get(0) 静默取首行。
--
-- 【为什么要这份】cms_topic_node.checkin_code 是店内静态码的二维码内容,玩家扫它进店。
--   ① 走标准 CRUD(CmsTopicNodeController.add → CmsTopicNodeServiceImpl)建的节点此前不生成码 ⇒
--      落 NULL ⇒ 该节点**永远扫不出来且零报错** ⇒ arrived 恒 false ⇒ 拍照被挡、queue_joined_at 全期为空
--      ⇒ G2 样本覆盖率 = 0% < 95% ⇒ 硬门恒判失败。生成缺口已在本次一并修(Service 层)。
--   ② 该列建表时是 `VARCHAR(64) DEFAULT NULL` **无唯一索引**
--      (migration_play_progress_points.sql:5),撞码没有任何一层会拒。
--
-- 【顺序铁律】先补空码 → 再断言无重复 → 最后加唯一索引。反过来会以 1062 中断,
--   而那时无法分辨「存量有重复」还是「本迁移写错了」。
--
-- 【故意不做:不轮换存量已有的码】migration_play_progress_points.sql:7 当年用
--   `REPLACE(UUID(),'-','')` 回填过一批 —— MySQL 的 UUID() 是 v1 时间序列,**可推导**。
--   本份**不重新生成**它们:码一换,店里已印好的海报全部作废。残留风险有界:
--   静态码只证明进店(审计 3 · T1),券仍要商家扫玩家的动态码才发。
--   要彻底消除可推导性,只能配合「重印该店海报」单独排期,不在本份里偷偷做。
--
-- 【幂等】三段全幂等:回填带 WHERE(重跑 0 行)、索引带形状预判(同形跳过)、断言纯只读。
--
-- 【可逆】ALTER TABLE cms_topic_node DROP INDEX uk_cms_topic_node_checkin_code;
--   (回填不可逆,也不需要 —— 空码本身就是缺陷态。)
--
-- 【风险】中。cms_topic_node 是玩家扫码的热表,加唯一索引会锁表重建;
--   行数量级为「主题 × 章节 × 点位」,应在低峰执行。
--
-- ======================= 第一步:只读预检 =======================
--
-- P1【必看】空码与重复码的存量:
--   SELECT COUNT(*) AS total,
--          SUM(checkin_code IS NULL OR checkin_code = '') AS blank_code
--     FROM cms_topic_node;
--   · blank_code > 0 → 正常,下面第 ① 段会补上。
--
-- P2【必看】是否已有重复码 —— 有重复就**停下来人工判**,不要直接跑本份:
--   SELECT checkin_code, COUNT(*) c FROM cms_topic_node
--    WHERE checkin_code IS NOT NULL AND checkin_code <> ''
--    GROUP BY checkin_code HAVING c > 1;
--   · 0 行 → 全执行。
--   · 有行 → **停**。第 ② 段断言会红。重复码意味着两家店贴了同一张纸,
--     必须先确认哪一家该换码并重印,再手工改那一行,然后回来跑本份。
--
-- P3【索引现状】同名索引只有形状精确为 (checkin_code) 且 UNIQUE 才可跳过:
--   SELECT index_name, non_unique, seq_in_index, column_name
--     FROM information_schema.STATISTICS
--    WHERE table_schema = DATABASE() AND table_name = 'cms_topic_node'
--      AND index_name = 'uk_cms_topic_node_checkin_code';


-- ======================= ① 空码回填 =======================
--
-- ⚠️ 用 RANDOM_BYTES 不用 UUID():UUID() 是 v1 时间序列,拿到一个就能推邻居;
--   RANDOM_BYTES 走 SSL RAND_bytes,128 bit 不可猜。LOWER 后与 Java 侧
--   CmsTopicNodeServiceImpl.newCheckinCode()(UUID.randomUUID 去横线)同为 32 位小写十六进制。
UPDATE cms_topic_node
   SET checkin_code = LOWER(HEX(RANDOM_BYTES(16)))
 WHERE checkin_code IS NULL OR checkin_code = '';

-- 回读:空码必须清零。非 0 = 上面那句被跳过或该列不存在。
-- runner: assert=scalar-true
SELECT COUNT(*) = 0
  FROM cms_topic_node
 WHERE checkin_code IS NULL OR checkin_code = '';


-- ======================= ② 唯一性前置断言(fail-closed)=======================
--
-- 必须为 0 组。非 0 = 存量有重复码,**本份到此为止**:见 P2,人工判哪家换码重印。
-- 放在加索引之前是为了让失败可读 —— 直接 CREATE UNIQUE INDEX 只会给一个 1062。
-- runner: assert=scalar-true
SELECT COUNT(*) = 0
  FROM (
    SELECT checkin_code
      FROM cms_topic_node
     WHERE checkin_code IS NOT NULL AND checkin_code <> ''
     GROUP BY checkin_code
    HAVING COUNT(*) > 1
  ) duplicated_checkin_codes;


-- ======================= ③ 唯一索引 =======================
--
-- 同名索引只有「UNIQUE 且列序精确为 checkin_code」才可跳过;同名错形会再次 ADD 并以 1061 fail-closed。
-- (MySQL 没有 CREATE UNIQUE INDEX IF NOT EXISTS,照抄
--  migration_20260815_tandianri_roam_revisit_report_indexes.sql 的 PREPARE 范式。)
SET @node_checkin_code_shape := (
    SELECT CONCAT(MIN(non_unique), ':', GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ','))
    FROM information_schema.STATISTICS
    WHERE table_schema = DATABASE()
      AND table_name = 'cms_topic_node'
      AND index_name = 'uk_cms_topic_node_checkin_code'
);
SET @node_checkin_code_ddl := IF(
    @node_checkin_code_shape IS NULL OR @node_checkin_code_shape <> '0:checkin_code',
    'ALTER TABLE cms_topic_node ADD UNIQUE INDEX uk_cms_topic_node_checkin_code (checkin_code)',
    'SELECT ''uk_cms_topic_node_checkin_code already unique, skip''');
PREPARE node_checkin_code_stmt FROM @node_checkin_code_ddl;
EXECUTE node_checkin_code_stmt;
DEALLOCATE PREPARE node_checkin_code_stmt;

-- 回读:索引存在、是 UNIQUE(non_unique = 0)、列序精确为 checkin_code。
-- 三条缺一即红 —— 建成非唯一索引会让「唯一性已落地」这句话变成假的。
-- runner: assert=scalar-true
SELECT COUNT(*) = 1
  FROM (
    SELECT index_name
      FROM information_schema.STATISTICS
     WHERE table_schema = DATABASE()
       AND table_name = 'cms_topic_node'
       AND index_name = 'uk_cms_topic_node_checkin_code'
       AND non_unique = 0
     GROUP BY index_name
    HAVING GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') = 'checkin_code'
  ) verified_node_checkin_code_index;


-- ======================= 落库后的对端回读(人工执行,别只看 "Query OK") =======================
--
-- A1:随便挑一个新建节点,确认码是 32 位小写十六进制:
--   SELECT id, topic_id, chapter_id, checkin_code, CHAR_LENGTH(checkin_code) len
--     FROM cms_topic_node ORDER BY id DESC LIMIT 5;
-- A2:负控 —— 手工插一条重复码必须以 1062 被拒(拒了再回滚):
--   START TRANSACTION;
--   INSERT INTO cms_topic_node (topic_id, chapter_id, name, checkin_code)
--   SELECT topic_id, chapter_id, '负控-撞码', checkin_code FROM cms_topic_node LIMIT 1;
--   -- 期望:ERROR 1062 Duplicate entry ... for key 'uk_cms_topic_node_checkin_code'
--   ROLLBACK;
--   ⚠️ 这一条是本份唯一能证明「唯一性真通电」的观测。不跑它 = 只有回执没有观测。
