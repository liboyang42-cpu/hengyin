-- runner: requires=transaction
-- ============================================================================
-- 圈层自由探索并回「自由探索」
--
-- 背景:
--   用户已批准「圈层自由探索」不再作为独立分类，最终分类只保留
--   「自由探索」和「城市定向」。
--
-- 本文件只把既有圈层模板/实例收回 product_type=2(FREE_EXPLORE)，并清空
-- circle_theme_code，保留供给声明和历史表结构，不做整块功能重设计。
-- 只写文件，不在开发阶段连接或写生产库。
-- ============================================================================

-- ======================== 人工落库前只读预检 ========================
-- SELECT circle_theme_code, COUNT(*) AS rows
--   FROM cms_topic
--  WHERE circle_theme_code IN
--        ('FITNESS','MIDLIFE','DATE','FRIENDS','SHANGHAI',
--         'AFTER_WORK','SOLO','PARENT_CHILD','PHOTO','NIGHT')
--    AND del_flag = 0
--  GROUP BY circle_theme_code
--  ORDER BY circle_theme_code;

CREATE TEMPORARY TABLE circle_theme_decommission_topic_ids (
    topic_id BIGINT NOT NULL,
    PRIMARY KEY (topic_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

INSERT INTO circle_theme_decommission_topic_ids (topic_id)
SELECT id
  FROM cms_topic
 WHERE del_flag = 0
   AND circle_theme_code IN
       ('FITNESS','MIDLIFE','DATE','FRIENDS','SHANGHAI',
        'AFTER_WORK','SOLO','PARENT_CHILD','PHOTO','NIGHT');

UPDATE cms_topic t
  JOIN circle_theme_decommission_topic_ids d ON d.topic_id = t.id
   SET t.product_type = 2,
       t.circle_theme_code = NULL,
       t.update_time = NOW()
 WHERE t.del_flag = 0;

DROP TEMPORARY TABLE circle_theme_decommission_topic_ids;

-- ======================== 落库后回读 ========================
-- SELECT COUNT(*) AS remaining_circle_theme_topics
--   FROM cms_topic
--  WHERE del_flag = 0
--    AND circle_theme_code IN
--        ('FITNESS','MIDLIFE','DATE','FRIENDS','SHANGHAI',
--         'AFTER_WORK','SOLO','PARENT_CHILD','PHOTO','NIGHT');
-- 期望 0。
--
-- SELECT product_type, COUNT(*) AS rows
--   FROM cms_topic
--  WHERE del_flag = 0
--    AND create_by IN ('seed_circle_primary_20260821','seed_circle_batch2_20260821')
--  GROUP BY product_type;
-- 期望只出现 product_type=2。
