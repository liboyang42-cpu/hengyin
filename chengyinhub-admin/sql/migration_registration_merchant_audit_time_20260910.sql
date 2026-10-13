-- 稿 88:4368 的承接进度行:「审核通过 8.2 14:05」。
--
-- cms_registration_merchant 一直没有「审核发生在什么时候」这一列 —— 只有 audit_status
-- 记住了结论。前端因此只能把这一行的时间留空:拿 update_time 顶替会写出一个**会漂的时间**
-- (商家改一次资料、后台动一次分润,那个时间就变了),比留空更糟。
--
-- 写入点只有两处,都是既有的 CAS 语句(mapper 里 confirmWinnerIfPending / rejectIfPending):
-- 审核这个动作本身就发生在那一条 update 里,时间跟结论同一条语句落库,不会出现
-- 「状态是通过、时间还是空」的中间态。商家改资料退回待定时(updateEditableFieldsIfStateMatches
-- 的 rejected 分支)同步清空 —— 那一刻上一次审核已经作废。
--
-- ⚠️ view_registration_merchant 不会自己跟上基表新增的列,必须连带 CREATE OR REPLACE。
--    读侧(承接进度页)走的正是这张视图。

ALTER TABLE `cms_registration_merchant`
    ADD COLUMN `audit_time` datetime NULL DEFAULT NULL
        COMMENT '审核决定发生的时刻(通过/驳回都写;退回待定时清空)' AFTER `audit_status`;

-- 存量行不回填:历史上这个时刻没有被记录过,编一个出来就是假数据。
-- 前端对空值的处理与补这一列之前完全一样(那一行不写时间)。

-- ⚠️ 下面这条必须跟着上面的 ALTER 一起跑:视图不会自己跟上基表新增的列。
--    漏了它 = ALTER 提交了、视图没重建,而 ViewRegistrationMerchantMapper 已经 select audit_time
--    ⇒ 承接进度页 Unknown column 'audit_time' 500。

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
    -- 2026-09-10 新增:审核决定发生的时刻。稿 88:4368 的进度行要写「审核通过 8.2 14:05」,
    -- 而 update_time 任何一次改动都会动,拿它顶替等于编一个会漂的时间。
    `crm`.`audit_time`        AS `audit_time`,
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
