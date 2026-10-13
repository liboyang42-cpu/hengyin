-- view_registration_merchant 的定义入库存档
--
-- 背景:这张视图只活在生产库里,仓库中**没有任何 DDL** —— 全仓 grep 只命中 mapper XML
-- 和域类,谁也重建不了它。库塌了、要开发库、要换环境,都无从下手。
-- 本文件是 2026-08-06 从生产库 information_schema.VIEWS 原样导出后整理的版本。
--
-- ⚠️ 它是**存档**,不是待跑的迁移:生产库已经有这张视图,跑不跑都不改变生产现状。
--    只有在重建环境(新库/开发库/灾备)时才需要执行。放在 sql/ 一层是为了让
--    migration_layout 门禁认得它、且跟其它 SQL 一起被检索到。
--
-- ⚠️ 与生产实况的两处**有意差异**:
--   1. 生产是 `DEFINER=chengyinhubdb@% SQL SECURITY DEFINER`。这里改用 INVOKER ——
--      账号名不该硬编进版本库,而且 DEFINER 语义会让视图带创建者权限执行,
--      在别的环境重建时容易水土不服。
--   2. 生产定义里表名带库名前缀(`chengyinhubdb`.`cms_registration_merchant`)。
--      这里去掉,让它跟着当前库走,免得换库名就失效。
--
-- 依赖:cms_registration_merchant、cms_topic。改这两张表的列会连带影响本视图,
--       改完记得 CREATE OR REPLACE 一次(视图不会自己跟上)。

CREATE OR REPLACE
    SQL SECURITY INVOKER
VIEW `view_registration_merchant` AS
SELECT
    -- ===== 报名本体 =====
    `crm`.`id`                AS `id`,
    `crm`.`member_id`         AS `member_id`,
    `crm`.`topic_id`          AS `topic_id`,
    `crm`.`mode`              AS `mode`,
    `crm`.`node_id`           AS `node_id`,
    `crm`.`address`           AS `address`,
    `crm`.`address_name`      AS `address_name`,
    `crm`.`longitude`         AS `longitude`,
    `crm`.`latitude`          AS `latitude`,
    `crm`.`template_id`       AS `template_id`,
    `crm`.`pic_url`           AS `pic_url`,
    `crm`.`email`             AS `email`,
    `crm`.`start_date`        AS `start_date`,
    `crm`.`end_date`          AS `end_date`,
    `crm`.`cooperate_date`    AS `cooperate_date`,
    `crm`.`activity_desc`     AS `activity_desc`,
    `crm`.`limit_num`         AS `limit_num`,
    -- 承接方可自定义的现场执行内容(规则说明 / 反馈话术 / 到店券)
    `crm`.`rule_instructions` AS `rule_instructions`,
    `crm`.`feedback_text`     AS `feedback_text`,
    `crm`.`coupon_id`         AS `coupon_id`,
    -- 玩法判定字段:归主办方(节点模板)。留在视图里是历史,读的人要清楚它不是真源。
    `crm`.`validation_method` AS `validation_method`,
    `crm`.`question_name`     AS `question_name`,
    `crm`.`question_answer`   AS `question_answer`,
    `crm`.`question_a`        AS `question_a`,
    `crm`.`question_b`        AS `question_b`,
    `crm`.`question_c`        AS `question_c`,
    `crm`.`question_d`        AS `question_d`,
    `crm`.`correct_answer`    AS `correct_answer`,
    `crm`.`status`            AS `status`,
    `crm`.`sharing_rate`      AS `sharing_rate`,
    `crm`.`sharing_amount`    AS `sharing_amount`,
    `crm`.`reason`            AS `reason`,
    `crm`.`del_flag`          AS `del_flag`,
    `crm`.`create_by`         AS `create_by`,
    `crm`.`create_time`       AS `create_time`,
    `crm`.`update_by`         AS `update_by`,
    `crm`.`update_time`       AS `update_time`,
    -- ===== 主题侧富化(全部加 topic_ 前缀,避开与报名同名列) =====
    `ct`.`member_id`                   AS `topic_member_id`,
    `ct`.`topic_type`                  AS `topic_type`,
    `ct`.`topic_id`                    AS `related_topic_id`,
    `ct`.`name`                        AS `topic_name`,
    `ct`.`subtitle`                    AS `topic_subtitle`,
    `ct`.`description`                 AS `topic_description`,
    `ct`.`address_name`                AS `topic_address_name`,
    `ct`.`total_time`                  AS `total_time`,
    `ct`.`total_mileage`               AS `total_mileage`,
    `ct`.`location_count`              AS `location_count`,
    `ct`.`template_count`              AS `template_count`,
    `ct`.`start_date`                  AS `topic_start_date`,
    `ct`.`end_date`                    AS `topic_end_date`,
    `ct`.`img_url`                     AS `topic_img_url`,
    `ct`.`img_arr`                     AS `topic_img_arr`,
    `ct`.`category_ids`                AS `category_ids`,
    `ct`.`status`                      AS `topic_status`,
    `ct`.`reason`                      AS `topic_reason`,
    `ct`.`user_status`                 AS `user_status`,
    `ct`.`merchant_status`             AS `merchant_status`,
    `ct`.`merchant_sign_up_start_date` AS `merchant_sign_up_start_date`,
    `ct`.`merchant_sign_up_end_date`   AS `merchant_sign_up_end_date`,
    `ct`.`del_flag`                    AS `topic_del_flag`,
    `ct`.`is_sync`                     AS `is_sync`,
    `ct`.`is_recommend`                AS `is_recommend`,
    `ct`.`check_status`                AS `topic_check_status`,
    `ct`.`is_settlement`               AS `is_settlement`,
    `ct`.`create_by`                   AS `topic_create_by`,
    `ct`.`create_time`                 AS `topic_create_time`,
    `ct`.`update_by`                   AS `topic_update_by`,
    `ct`.`update_time`                 AS `topic_update_time`,
    -- ===== 派生展示态 =====
    -- ⚠️ 用的是 now():这几列随查询时刻变化,不是快照。拿它们做对账要留意时点。
    (CASE
        WHEN (`crm`.`status` = 2) THEN '已驳回'
        WHEN (`crm`.`status` = 0) THEN '审核中'
        WHEN (`crm`.`status` = 1) THEN
            (CASE
                WHEN (`ct`.`end_date` < NOW()) THEN '已结束'
                WHEN ((`ct`.`start_date` <= NOW()) AND (`ct`.`end_date` >= NOW())) THEN '进行中'
                WHEN (`ct`.`start_date` > NOW()) THEN '未开始'
                ELSE '已审核'
            END)
        ELSE '未知状态'
    END) AS `display_status`,
    (CASE WHEN ((`crm`.`status` = 1) AND (`ct`.`start_date` <= NOW()) AND (`ct`.`end_date` >= NOW()))
        THEN 1 ELSE 0 END) AS `is_in_progress`,
    (CASE WHEN ((`crm`.`status` = 1) AND (`ct`.`end_date` < NOW()))
        THEN 1 ELSE 0 END) AS `is_ended`,
    (CASE WHEN (`crm`.`status` = 0) THEN 1 ELSE 0 END) AS `is_under_review`,
    (CASE WHEN (`crm`.`status` = 2) THEN 1 ELSE 0 END) AS `is_rejected`
FROM (`cms_registration_merchant` `crm`
    LEFT JOIN `cms_topic` `ct` ON ((`crm`.`topic_id` = `ct`.`id`)))
-- 软删的报名不进视图。⚠️ 读侧因此看不到 del_flag<>0 的行,要查已取消的报名得直接读基表。
WHERE (`crm`.`del_flag` = 0);
