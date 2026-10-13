-- runner: requires=transaction
-- 修复 2026-08-22 00:56 已落生产的第二组圈层旧种子。
-- 旧前像：A5/H6/H7/K7/K8/O8 仍映射“娱乐达人”，40 条商家准备工时为 0.17 小时。
-- 新后像：六个候选按最终产品合同精确分类，准备工时 0.16 小时（9.6 分钟，严格低于10分钟）。
-- 本文件是一次性收敛迁移；前像不完全匹配时必须整笔拒绝，不把未知生产状态猜成可修复。

CREATE TEMPORARY TABLE questify_circle_batch2_repair_guard (
    guard_name VARCHAR(64) NOT NULL,
    PRIMARY KEY (guard_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TEMPORARY TABLE questify_circle_batch2_repair_target (
    candidate_code VARCHAR(8) NOT NULL,
    source_category_name VARCHAR(64) NOT NULL,
    target_category_name VARCHAR(64) NOT NULL,
    source_category_id BIGINT NULL,
    target_category_id BIGINT NULL,
    PRIMARY KEY (candidate_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TEMPORARY TABLE questify_circle_batch2_repair_meta_snapshot (
    meta_id BIGINT NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (meta_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

INSERT INTO questify_circle_batch2_repair_target (
    candidate_code, source_category_name, target_category_name
)
SELECT 'A5' AS candidate_code, '娱乐达人' AS source_category_name, '生活美学' AS target_category_name
UNION ALL SELECT 'H6' AS candidate_code, '娱乐达人' AS source_category_name, '礼品纪念' AS target_category_name
UNION ALL SELECT 'H7' AS candidate_code, '娱乐达人' AS source_category_name, '礼品纪念' AS target_category_name
UNION ALL SELECT 'K7' AS candidate_code, '娱乐达人' AS source_category_name, '礼品纪念' AS target_category_name
UNION ALL SELECT 'K8' AS candidate_code, '娱乐达人' AS source_category_name, '生活美学' AS target_category_name
UNION ALL SELECT 'O8' AS candidate_code, '娱乐达人' AS source_category_name, '礼品纪念' AS target_category_name;

UPDATE questify_circle_batch2_repair_target r
JOIN sys_category c
  ON c.category_name = r.source_category_name
SET r.source_category_id = c.id
WHERE c.type = 1 AND c.status = 1 AND c.del_flag = 0;

UPDATE questify_circle_batch2_repair_target r
JOIN sys_category c
  ON c.category_name = r.target_category_name
SET r.target_category_id = c.id
WHERE c.type = 1 AND c.status = 1 AND c.del_flag = 0;

-- runner: assert=rowcount-positive
INSERT INTO questify_circle_batch2_repair_guard (guard_name)
SELECT 'catalog-targets'
WHERE (SELECT COUNT(*) FROM sys_category
       WHERE type = 1 AND status = 1 AND del_flag = 0
         AND category_name IN ('美食烹饪','运动健身','娱乐达人','生活美学','礼品纪念')) = 5;

-- runner: assert=rowcount-positive
INSERT INTO questify_circle_batch2_repair_guard (guard_name)
SELECT 'resolved-targets'
FROM questify_circle_batch2_repair_target
HAVING COUNT(*) = 6
   AND COUNT(source_category_id) = 6
   AND COUNT(target_category_id) = 6
   AND COUNT(DISTINCT candidate_code) = 6
   AND COUNT(DISTINCT source_category_id) = 1
   AND COUNT(DISTINCT target_category_id) = 2;

-- runner: assert=rowcount-positive
INSERT INTO questify_circle_batch2_repair_guard (guard_name)
SELECT 'shape'
WHERE (SELECT COUNT(*) FROM cms_topic t
       WHERE t.circle_theme_code IN ('AFTER_WORK','SOLO','PARENT_CHILD','PHOTO','NIGHT')
         AND t.is_template = 1 AND t.del_flag = 0) = 5
  AND (SELECT COUNT(*) FROM cms_topic_chapter ch
       JOIN cms_topic t ON t.id = ch.topic_id
       WHERE t.circle_theme_code IN ('AFTER_WORK','SOLO','PARENT_CHILD','PHOTO','NIGHT')
         AND t.is_template = 1 AND t.del_flag = 0) = 40
  AND (SELECT COUNT(DISTINCT ch.circle_candidate_code) FROM cms_topic_chapter ch
       JOIN cms_topic t ON t.id = ch.topic_id
       WHERE t.circle_theme_code IN ('AFTER_WORK','SOLO','PARENT_CHILD','PHOTO','NIGHT')
         AND t.is_template = 1 AND t.del_flag = 0) = 40
  AND (SELECT COUNT(*) FROM cms_topic_chapter_template_meta m
       JOIN cms_topic t ON t.id = m.topic_id
       WHERE t.circle_theme_code IN ('AFTER_WORK','SOLO','PARENT_CHILD','PHOTO','NIGHT')
         AND t.is_template = 1 AND t.del_flag = 0) = 40;

-- runner: assert=rowcount-positive
INSERT INTO questify_circle_batch2_repair_guard (guard_name)
SELECT 'preimage-categories'
WHERE (SELECT COUNT(*)
       FROM cms_topic_chapter ch
       JOIN cms_topic t ON t.id = ch.topic_id
       JOIN sys_category c ON c.id = ch.category_id
        AND c.type = 1 AND c.status = 1 AND c.del_flag = 0
       WHERE t.circle_theme_code IN ('AFTER_WORK','SOLO','PARENT_CHILD','PHOTO','NIGHT')
         AND t.is_template = 1 AND t.del_flag = 0) = 40
  AND (SELECT COUNT(*)
       FROM cms_topic_chapter ch
       JOIN cms_topic t ON t.id = ch.topic_id
       JOIN sys_category c ON c.id = ch.category_id
        AND c.type = 1 AND c.status = 1 AND c.del_flag = 0
       WHERE t.circle_theme_code IN ('AFTER_WORK','SOLO','PARENT_CHILD','PHOTO','NIGHT')
         AND t.is_template = 1 AND t.del_flag = 0
         AND c.category_name <> CASE ch.circle_candidate_code
             WHEN 'A1' THEN '美食烹饪' WHEN 'A2' THEN '运动健身'
             WHEN 'A3' THEN '生活美学' WHEN 'A4' THEN '生活美学'
             WHEN 'A5' THEN '娱乐达人' WHEN 'A6' THEN '美食烹饪'
             WHEN 'A7' THEN '娱乐达人' WHEN 'A8' THEN '美食烹饪'
             WHEN 'O1' THEN '美食烹饪' WHEN 'O2' THEN '美食烹饪'
             WHEN 'O3' THEN '生活美学' WHEN 'O4' THEN '生活美学'
             WHEN 'O5' THEN '礼品纪念' WHEN 'O6' THEN '生活美学'
             WHEN 'O7' THEN '娱乐达人' WHEN 'O8' THEN '娱乐达人'
             WHEN 'K1' THEN '运动健身' WHEN 'K2' THEN '生活美学'
             WHEN 'K3' THEN '礼品纪念' WHEN 'K4' THEN '美食烹饪'
             WHEN 'K5' THEN '美食烹饪' WHEN 'K6' THEN '生活美学'
             WHEN 'K7' THEN '娱乐达人' WHEN 'K8' THEN '娱乐达人'
             WHEN 'H1' THEN '生活美学' WHEN 'H2' THEN '生活美学'
             WHEN 'H3' THEN '生活美学' WHEN 'H4' THEN '美食烹饪'
             WHEN 'H5' THEN '美食烹饪' WHEN 'H6' THEN '娱乐达人'
             WHEN 'H7' THEN '娱乐达人' WHEN 'H8' THEN '美食烹饪'
             WHEN 'N1' THEN '美食烹饪' WHEN 'N2' THEN '美食烹饪'
             WHEN 'N3' THEN '生活美学' WHEN 'N4' THEN '娱乐达人'
             WHEN 'N5' THEN '娱乐达人' WHEN 'N6' THEN '娱乐达人'
             WHEN 'N7' THEN '美食烹饪' WHEN 'N8' THEN '美食烹饪'
             ELSE '__UNMAPPED__'
         END) = 0;

-- runner: assert=rowcount-positive
INSERT INTO questify_circle_batch2_repair_guard (guard_name)
SELECT 'preimage-effort'
WHERE (SELECT COUNT(*) FROM cms_topic_chapter_template_meta m
       JOIN cms_topic t ON t.id = m.topic_id
       WHERE t.circle_theme_code IN ('AFTER_WORK','SOLO','PARENT_CHILD','PHOTO','NIGHT')
         AND t.is_template = 1 AND t.del_flag = 0
         AND m.effort_prep_hours = 0.17) = 40;

INSERT INTO questify_circle_batch2_repair_meta_snapshot (meta_id, update_time)
SELECT m.id, m.update_time
FROM cms_topic_chapter_template_meta m
JOIN cms_topic t ON t.id = m.topic_id
WHERE t.circle_theme_code IN ('AFTER_WORK','SOLO','PARENT_CHILD','PHOTO','NIGHT')
  AND t.is_template = 1 AND t.del_flag = 0;

-- runner: assert=rowcount-positive
INSERT INTO questify_circle_batch2_repair_guard (guard_name)
SELECT 'meta-update-time-snapshot'
WHERE (SELECT COUNT(*) FROM questify_circle_batch2_repair_meta_snapshot) = 40;

-- runner: assert=rowcount-positive
UPDATE cms_topic_chapter ch
JOIN questify_circle_batch2_repair_target r
  ON r.candidate_code = ch.circle_candidate_code
SET ch.category_id = r.target_category_id
WHERE ch.category_id = r.source_category_id
  AND EXISTS (
      SELECT 1 FROM cms_topic t
      WHERE t.id = ch.topic_id
        AND t.circle_theme_code IN ('AFTER_WORK','SOLO','PARENT_CHILD','PHOTO','NIGHT')
        AND t.is_template = 1 AND t.del_flag = 0
  );

-- runner: assert=rowcount-positive
UPDATE cms_topic_chapter_template_meta m
SET m.effort_prep_hours = 0.16,
    m.update_time = m.update_time
WHERE m.effort_prep_hours = 0.17
  AND EXISTS (
      SELECT 1 FROM cms_topic t
      WHERE t.id = m.topic_id
        AND t.circle_theme_code IN ('AFTER_WORK','SOLO','PARENT_CHILD','PHOTO','NIGHT')
        AND t.is_template = 1 AND t.del_flag = 0
  );

-- runner: assert=rowcount-positive
INSERT INTO questify_circle_batch2_repair_guard (guard_name)
SELECT 'postimage-categories'
WHERE (SELECT COUNT(*)
       FROM cms_topic_chapter ch
       JOIN cms_topic t ON t.id = ch.topic_id
       JOIN sys_category c ON c.id = ch.category_id
        AND c.type = 1 AND c.status = 1 AND c.del_flag = 0
       WHERE t.circle_theme_code IN ('AFTER_WORK','SOLO','PARENT_CHILD','PHOTO','NIGHT')
         AND t.is_template = 1 AND t.del_flag = 0) = 40
  AND (SELECT COUNT(*)
       FROM cms_topic_chapter ch
       JOIN cms_topic t ON t.id = ch.topic_id
       JOIN sys_category c ON c.id = ch.category_id
        AND c.type = 1 AND c.status = 1 AND c.del_flag = 0
       WHERE t.circle_theme_code IN ('AFTER_WORK','SOLO','PARENT_CHILD','PHOTO','NIGHT')
         AND t.is_template = 1 AND t.del_flag = 0
         AND c.category_name <> CASE ch.circle_candidate_code
             WHEN 'A1' THEN '美食烹饪' WHEN 'A2' THEN '运动健身'
             WHEN 'A3' THEN '生活美学' WHEN 'A4' THEN '生活美学'
             WHEN 'A5' THEN '生活美学' WHEN 'A6' THEN '美食烹饪'
             WHEN 'A7' THEN '娱乐达人' WHEN 'A8' THEN '美食烹饪'
             WHEN 'O1' THEN '美食烹饪' WHEN 'O2' THEN '美食烹饪'
             WHEN 'O3' THEN '生活美学' WHEN 'O4' THEN '生活美学'
             WHEN 'O5' THEN '礼品纪念' WHEN 'O6' THEN '生活美学'
             WHEN 'O7' THEN '娱乐达人' WHEN 'O8' THEN '礼品纪念'
             WHEN 'K1' THEN '运动健身' WHEN 'K2' THEN '生活美学'
             WHEN 'K3' THEN '礼品纪念' WHEN 'K4' THEN '美食烹饪'
             WHEN 'K5' THEN '美食烹饪' WHEN 'K6' THEN '生活美学'
             WHEN 'K7' THEN '礼品纪念' WHEN 'K8' THEN '生活美学'
             WHEN 'H1' THEN '生活美学' WHEN 'H2' THEN '生活美学'
             WHEN 'H3' THEN '生活美学' WHEN 'H4' THEN '美食烹饪'
             WHEN 'H5' THEN '美食烹饪' WHEN 'H6' THEN '礼品纪念'
             WHEN 'H7' THEN '礼品纪念' WHEN 'H8' THEN '美食烹饪'
             WHEN 'N1' THEN '美食烹饪' WHEN 'N2' THEN '美食烹饪'
             WHEN 'N3' THEN '生活美学' WHEN 'N4' THEN '娱乐达人'
             WHEN 'N5' THEN '娱乐达人' WHEN 'N6' THEN '娱乐达人'
             WHEN 'N7' THEN '美食烹饪' WHEN 'N8' THEN '美食烹饪'
             ELSE '__UNMAPPED__'
         END) = 0;

-- runner: assert=rowcount-positive
INSERT INTO questify_circle_batch2_repair_guard (guard_name)
SELECT 'postimage-effort'
WHERE (SELECT COUNT(*) FROM cms_topic_chapter_template_meta m
       JOIN cms_topic t ON t.id = m.topic_id
       WHERE t.circle_theme_code IN ('AFTER_WORK','SOLO','PARENT_CHILD','PHOTO','NIGHT')
         AND t.is_template = 1 AND t.del_flag = 0
         AND m.effort_prep_hours = 0.16) = 40
  AND (SELECT COUNT(*) FROM cms_topic_chapter_template_meta m
       JOIN questify_circle_batch2_repair_meta_snapshot s ON s.meta_id = m.id
       WHERE NOT (m.update_time <=> s.update_time)) = 0;

DROP TEMPORARY TABLE questify_circle_batch2_repair_meta_snapshot;
DROP TEMPORARY TABLE questify_circle_batch2_repair_target;
DROP TEMPORARY TABLE questify_circle_batch2_repair_guard;

-- 回滚前像（仅在确认尚无人工后续编辑时使用）：
-- 六个候选 category_id 恢复为启用一级分类“娱乐达人”，40 条 effort_prep_hours 恢复为0.17。
