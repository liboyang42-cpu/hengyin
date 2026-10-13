-- 圈层主题候选品类修复：把80个候选映射到生产现有一级商家品类。
-- runner: requires=transaction
-- 前置：两组主题种子均已落库；只回填空 category_id，不改运营已配置的品类。

CREATE TEMPORARY TABLE assert_circle_category_before (
    topic_count INT NOT NULL,
    chapter_count INT NOT NULL,
    category_count INT NOT NULL,
    CONSTRAINT ck_circle_category_topic_count CHECK (topic_count = 10),
    CONSTRAINT ck_circle_category_chapter_count CHECK (chapter_count = 80),
    CONSTRAINT ck_circle_category_source_count CHECK (category_count = 5)
) ENGINE=InnoDB;

INSERT INTO assert_circle_category_before (topic_count, chapter_count, category_count)
SELECT
    (SELECT COUNT(*)
       FROM cms_topic t
      WHERE t.circle_theme_code IN (
          'AFTER_WORK','SOLO','PARENT_CHILD','PHOTO','NIGHT',
          'FITNESS','MIDLIFE','DATE','FRIENDS','SHANGHAI'
      )
        AND t.is_template = 1
        AND t.del_flag = 0),
    (SELECT COUNT(*)
       FROM cms_topic t
       JOIN cms_topic_chapter ch ON ch.topic_id = t.id
      WHERE t.circle_theme_code IN (
          'AFTER_WORK','SOLO','PARENT_CHILD','PHOTO','NIGHT',
          'FITNESS','MIDLIFE','DATE','FRIENDS','SHANGHAI'
      )
        AND t.is_template = 1
        AND t.del_flag = 0),
    (SELECT COUNT(*)
       FROM sys_category c
      WHERE c.category_name IN ('运动健身','美食烹饪','娱乐达人','生活美学','礼品纪念')
        AND c.type = 1
        AND c.parent_id = 0
        AND c.status = 1
        AND c.del_flag = 0);

DROP TEMPORARY TABLE assert_circle_category_before;

UPDATE cms_topic_chapter ch
JOIN cms_topic t ON t.id = ch.topic_id
SET ch.category_id = (
    SELECT c.id
      FROM sys_category c
     WHERE c.category_name = CASE
         WHEN ch.circle_candidate_code IN (
             'A2','F1','F2','F3','F4','F5','F7','F8','M3','K1'
         ) THEN '运动健身'
         WHEN ch.circle_candidate_code IN (
             'A1','A6','A8','C1','C6','C8','F6','P1','P2','P8',
             'M1','M8','N1','N2','N7','N8','K4','K5','H4','H5',
             'H8','S1','S2','S6','O1','O2'
         ) THEN '美食烹饪'
         WHEN ch.circle_candidate_code IN (
             'A5','A7','C5','C7','P4','P5','P6','P7','N4','N5',
             'N6','K7','K8','H6','H7','S7','S8','O7','O8'
         ) THEN '娱乐达人'
         WHEN ch.circle_candidate_code IN (
             'A3','A4','C2','C3','P3','M2','M4','M5','M7','N3',
             'K2','K6','H1','H2','H3','S3','S4','O3','O4','O6'
         ) THEN '生活美学'
         WHEN ch.circle_candidate_code IN ('C4','M6','K3','S5','O5')
             THEN '礼品纪念'
         ELSE NULL
     END
       AND c.type = 1
       AND c.parent_id = 0
       AND c.status = 1
       AND c.del_flag = 0
     ORDER BY c.id
     LIMIT 1
)
WHERE t.circle_theme_code IN (
    'AFTER_WORK','SOLO','PARENT_CHILD','PHOTO','NIGHT',
    'FITNESS','MIDLIFE','DATE','FRIENDS','SHANGHAI'
)
  AND t.is_template = 1
  AND t.del_flag = 0
  AND ch.category_id IS NULL;

CREATE TEMPORARY TABLE assert_circle_category_after (
    bad_count INT NOT NULL,
    CONSTRAINT ck_circle_category_after CHECK (bad_count = 0)
) ENGINE=InnoDB;

INSERT INTO assert_circle_category_after (bad_count)
SELECT COUNT(*)
  FROM cms_topic t
  JOIN cms_topic_chapter ch ON ch.topic_id = t.id
  LEFT JOIN sys_category c ON c.id = ch.category_id
 WHERE t.circle_theme_code IN (
     'AFTER_WORK','SOLO','PARENT_CHILD','PHOTO','NIGHT',
     'FITNESS','MIDLIFE','DATE','FRIENDS','SHANGHAI'
 )
   AND t.is_template = 1
   AND t.del_flag = 0
   AND (
       ch.category_id IS NULL
       OR c.id IS NULL
       OR c.type <> 1
       OR c.parent_id <> 0
       OR c.status <> 1
       OR c.del_flag <> 0
   );

DROP TEMPORARY TABLE assert_circle_category_after;
