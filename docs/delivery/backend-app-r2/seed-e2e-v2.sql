-- E2E V2 Idempotent Enrichment Seed (TEST ONLY: cy_e2e_r2 / cy_e2e_beint)
-- Never run against production. Run via docs/delivery/backend-app-r2/seed-e2e-v2.sh.

SET NAMES utf8mb4;

-- ============================================================================
-- 0. Strict TEST-DB Guard
-- ============================================================================
DROP PROCEDURE IF EXISTS _e2e_v2_assert_test_db;
DELIMITER //
CREATE PROCEDURE _e2e_v2_assert_test_db()
BEGIN
    IF DATABASE() IS NULL OR DATABASE() NOT IN ('cy_e2e_r2', 'cy_e2e_beint') THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'REFUSING TO RUN: seed-e2e-v2.sql only runs on TEST DB (cy_e2e_r2 / cy_e2e_beint)';
    END IF;
END //
DELIMITER ;
CALL _e2e_v2_assert_test_db();
DROP PROCEDURE IF EXISTS _e2e_v2_assert_test_db;

-- ============================================================================
-- 1. Templates (adoptable) + Roam POIs near e2e/route.gpx (Shanghai Huangpu)
-- ============================================================================
INSERT INTO sys_category (id, type, parent_id, category_name, level, sort_id, icon, status, del_flag, create_by, create_time, update_by, update_time)
VALUES
  (4101, 4, 0, '城市探索', 0, 1, NULL, 1, 0, 'e2e-seed-v2', NOW(), 'e2e-seed-v2', NOW()),
  (4102, 4, 0, '推理解谜', 0, 2, NULL, 1, 0, 'e2e-seed-v2', NOW(), 'e2e-seed-v2', NOW())
ON DUPLICATE KEY UPDATE
  type = VALUES(type),
  parent_id = VALUES(parent_id),
  category_name = VALUES(category_name),
  status = 1,
  del_flag = 0,
  update_by = 'e2e-seed-v2',
  update_time = NOW();

INSERT INTO cms_member_template (
  id, original_template_id, member_id, category_id, title, description,
  players, usage_location, required_materials, duration, difficulty, is_sync,
  rule_instructions, merchant_guide, validation_method,
  question_name, question_answer, feedback_text,
  hint1, hint2, answer_reveal,
  question_a, question_b, question_c, question_d, correct_answer,
  advanced_config_json, activity_categoryids,
  status, publish_status, draft_status, del_flag, rating, use_num, check_status,
  create_by, create_time, update_by, update_time, xp_value, medal_style
)
VALUES
  (
    992501, 0, 9003, 4101,
    'E2E 可复用·老城钟楼暗号问答',
    '适用于城市地标打卡的文字谜题+两级提示模板',
    '1-4人', '室外广场/地标', '无', 15, '简单', 1,
    '抵达地标后观察正门匾额文字作答。', '商家无需额外接待，保持门店指引可见即可。', 1,
    '钟楼正门匾额共有几个汉字？', '4', '回答正确，解锁钟楼探索徽章！',
    '抬头观察钟楼正门上方木匾', '一共只有四个字（填阿拉伯数字 4）', '4',
    NULL, NULL, NULL, NULL, NULL,
    JSON_OBJECT(
      'schemaVersion', 1,
      'qa', JSON_OBJECT('enabled', true, 'mode', 'TEXT', 'title', '钟楼正门匾额共有几个汉字？', 'answer', '4', 'maxTries', 0),
      'hintLadder', JSON_ARRAY(
        JSON_OBJECT('level', 1, 'type', 'TEXT', 'payload', '抬头观察钟楼正门上方木匾'),
        JSON_OBJECT('level', 2, 'type', 'TEXT', 'payload', '一共只有四个字（填阿拉伯数字 4）')
      )
    ),
    '4101',
    1, 1, 1, 0, '5.0', 12, 1,
    'e2e-seed-v2', NOW(), 'e2e-seed-v2', NOW(), 25, 'glow'
  ),
  (
    992502, 0, 9003, 4102,
    'E2E 可复用·外滩钟声线索选择',
    '适用于街区推理的选择题+提示模板，支持一键采用',
    '1-4人', '外滩沿岸', '无', 20, '中等', 1,
    '阅读线索卡后选出正确答案。', '可用于咖啡馆或书店合作点位。', 3,
    '外滩海关大钟报时曾采用哪首经典旋律？', 'C', '答对了！获得外滩声景线索卡。',
    '该旋律起源于英国议会大厦钟楼', '名称包含五个汉字：威斯敏斯特钟声', 'C 威斯敏斯特钟声',
    '东方红', '茉莉花', '威斯敏斯特钟声', '蓝色多瑙河', 'C',
    JSON_OBJECT(
      'schemaVersion', 1,
      'qa', JSON_OBJECT(
        'enabled', true,
        'mode', 'PICK',
        'title', '外滩海关大钟报时曾采用哪首经典旋律？',
        'options', JSON_ARRAY(
          JSON_OBJECT('id', 'a', 'label', '东方红', 'correct', false),
          JSON_OBJECT('id', 'b', 'label', '茉莉花', 'correct', false),
          JSON_OBJECT('id', 'c', 'label', '威斯敏斯特钟声', 'correct', true),
          JSON_OBJECT('id', 'd', 'label', '蓝色多瑙河', 'correct', false)
        ),
        'maxTries', 0
      ),
      'hintLadder', JSON_ARRAY(
        JSON_OBJECT('level', 1, 'type', 'TEXT', 'payload', '该旋律起源于英国议会大厦钟楼')
      )
    ),
    '4102',
    1, 1, 1, 0, '5.0', 8, 1,
    'e2e-seed-v2', NOW(), 'e2e-seed-v2', NOW(), 20, 'glow'
  )
ON DUPLICATE KEY UPDATE
  title = VALUES(title),
  description = VALUES(description),
  advanced_config_json = VALUES(advanced_config_json),
  status = 1,
  publish_status = 1,
  draft_status = 1,
  check_status = 1,
  del_flag = 0,
  update_by = 'e2e-seed-v2',
  update_time = NOW();

INSERT INTO cms_template_library (
  id, member_id, member_template_id, category_id, title, description,
  players, usage_location, required_materials, duration, difficulty, is_sync,
  rule_instructions, merchant_guide, validation_method,
  question_name, question_answer, feedback_text,
  hint1, hint2, answer_reveal,
  question_a, question_b, question_c, question_d, correct_answer,
  advanced_config_json, activity_categoryids,
  status, del_flag, rating, use_num, pack_type,
  create_by, create_time, update_by, update_time
)
VALUES
  (
    992501, 9003, 992501, 4101,
    'E2E 模板库·老城钟楼暗号',
    'E2E 公共模板库可采用单节点模板：文字验证 + 提示阶梯。',
    '1-4人', '人民广场/钟楼', '无', 15, '简单', 1,
    '到点位后观察匾额作答。', '适合所有线下打卡点。', 1,
    '钟楼正门匾额共有几个汉字？', '4', '回答正确，解锁钟楼探索徽章！',
    '抬头观察钟楼正门上方木匾', '一共只有四个字（填阿拉伯数字 4）', '4',
    NULL, NULL, NULL, NULL, NULL,
    JSON_OBJECT(
      'schemaVersion', 1,
      'qa', JSON_OBJECT('enabled', true, 'mode', 'TEXT', 'title', '钟楼正门匾额共有几个汉字？', 'answer', '4', 'maxTries', 0),
      'hintLadder', JSON_ARRAY(
        JSON_OBJECT('level', 1, 'type', 'TEXT', 'payload', '抬头观察钟楼正门上方木匾'),
        JSON_OBJECT('level', 2, 'type', 'TEXT', 'payload', '一共只有四个字（填阿拉伯数字 4）')
      )
    ),
    '4101',
    1, 0, '5.0', 12, 0,
    'e2e-seed-v2', NOW(), 'e2e-seed-v2', NOW()
  ),
  (
    992502, 9003, 992502, 4102,
    'E2E 模板库·外滩线索剧情包',
    'E2E 公共模板库可采用剧情包模板：选择题验证 + 线索揭示。',
    '1-4人', '黄浦外滩', '无', 20, '中等', 1,
    '根据线索卡完成选择。', '支持店铺 IP 联动。', 3,
    '外滩海关大钟报时曾采用哪首经典旋律？', 'C', '答对了！获得外滩声景线索卡。',
    '该旋律起源于英国议会大厦钟楼', '名称包含五个汉字：威斯敏斯特钟声', 'C 威斯敏斯特钟声',
    '东方红', '茉莉花', '威斯敏斯特钟声', '蓝色多瑙河', 'C',
    JSON_OBJECT(
      'schemaVersion', 1,
      'qa', JSON_OBJECT(
        'enabled', true,
        'mode', 'PICK',
        'title', '外滩海关大钟报时曾采用哪首经典旋律？',
        'options', JSON_ARRAY(
          JSON_OBJECT('id', 'a', 'label', '东方红', 'correct', false),
          JSON_OBJECT('id', 'b', 'label', '茉莉花', 'correct', false),
          JSON_OBJECT('id', 'c', 'label', '威斯敏斯特钟声', 'correct', true),
          JSON_OBJECT('id', 'd', 'label', '蓝色多瑙河', 'correct', false)
        ),
        'maxTries', 0
      ),
      'hintLadder', JSON_ARRAY(
        JSON_OBJECT('level', 1, 'type', 'TEXT', 'payload', '该旋律起源于英国议会大厦钟楼')
      )
    ),
    '4102',
    1, 0, '5.0', 8, 1,
    'e2e-seed-v2', NOW(), 'e2e-seed-v2', NOW()
  )
ON DUPLICATE KEY UPDATE
  title = VALUES(title),
  status = 1,
  del_flag = 0,
  use_num = VALUES(use_num),
  update_by = 'e2e-seed-v2',
  update_time = NOW();

-- Adoptable Topic Template (also unblocks /api/template/topic-template/list)
INSERT INTO cms_topic (
  id, member_id, topic_type, product_type, route_mode, config_version, published_config_version,
  name, subtitle, description, address_name, total_time, total_mileage, location_count, template_count,
  category_ids, status, user_status, merchant_status, open_club_pool, lifecycle, del_flag,
  is_draft, is_template, club_id, team_mode, team_max_members, content_safety_hold, city_code,
  create_by, create_time, update_by, update_time
)
VALUES (
  992510, 9003, 1, 1, 'LINEAR', 1, 1,
  'E2E 主题模板·黄浦老城慢游三站', '可直接一键复制成新项目',
  '沿人民广场—南京东路—外滩步道设计的城市定向示范模板，内置问答与提示阶梯。',
  '上海市黄浦区人民广场', 90, 2.60, 1, 1,
  '21,23', 1, 1, 1, 1, 3, 0,
  0, 1, 7001, 2, 4, 0, '310100',
  'e2e-seed-v2', NOW(), 'e2e-seed-v2', NOW()
)
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  status = 1,
  user_status = 1,
  is_template = 1,
  is_draft = 0,
  del_flag = 0,
  content_safety_hold = 0,
  update_by = 'e2e-seed-v2',
  update_time = NOW();

INSERT INTO cms_topic_chapter (
  id, topic_id, name, description, total_time, total_mileage, location_count, template_count,
  sort_id, required, terms_mode, circle_enabled, max_node_xp,
  create_by, create_time, update_by, update_time
)
VALUES (
  992511, 992510, '第一章 · 人民广场起点', '从钟楼出发，沿 e2e/route.gpx 向东探索。',
  30, 1.00, 1, 1, 1, 1, 'PERK', 1, 30,
  'e2e-seed-v2', NOW(), 'e2e-seed-v2', NOW()
)
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  update_by = 'e2e-seed-v2',
  update_time = NOW();

INSERT INTO cms_topic_node (
  id, topic_id, chapter_id, name, description, address, longitude, latitude,
  node_time, template_id, node_audit_status, status, sort_id, xp_value,
  create_by, create_time, update_by, update_time
)
VALUES (
  992512, 992510, 992511, '人民广场钟楼站点', '完成钟楼暗号问答。',
  '上海市黄浦区人民广场1号', '121.473700', '31.230400',
  15, 992501, 1, 1, 1, 25,
  'e2e-seed-v2', NOW(), 'e2e-seed-v2', NOW()
)
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  status = 1,
  update_by = 'e2e-seed-v2',
  update_time = NOW();

INSERT INTO cms_topic_template_meta (
  topic_id, template_status, version, applicable_roles, audience_who, audience_moment, audience_need,
  player_promise, core_loop, main_skeleton_template_id, evidence_level,
  risk_tags, risk_qualifications, risk_cancellation, risk_liability, risk_safety_notice,
  preview_before, preview_during, preview_after, author, is_official, del_flag
)
VALUES (
  992510, 'VERIFIED', 'v1.0', 'CLUB,MERCHANT', '城市漫步新手与好友小队', '周末午后城市探索', '低门槛有线索可玩的街区路线',
  '按照路书步行 2.6 公里，完成地标谜题与咖啡驿站打卡即可获得专属探索徽章。',
  '到达起点\n线索触发\n合作解谜\n驿站核销\n终章结算\n社区分享',
  992501, 'PILOTED',
  'LOW_RISK,WALKING', '线下公开步道，无需特殊资质', '开场前 24 小时可全额退', '参与者注意交通安全', '沿市政人行道行进，切勿横穿机动车道。',
  '查看起点坐标并组成 1-4 人小队', '按地图站点作答并使用提示阶梯', '结算徽章与探索值奖励', '城瘾官方E2E', 1, 0
)
ON DUPLICATE KEY UPDATE
  template_status = 'VERIFIED',
  applicable_roles = 'CLUB,MERCHANT',
  is_official = 1,
  del_flag = 0;

-- Roam POIs near e2e/route.gpx (31.230400, 121.473700 -> 31.238500, 121.484000)
INSERT INTO roam_poi (
  id, name, type, lat, lng, radius_m, address, description, xp,
  coupon_template_id, city_code, status, audit_status, application_type,
  merchant_id, template_id, node_level, tags, sort, photo_required, badge_code, create_time
)
VALUES
  (
    992521, '人民广场钟楼集合点', 1, 31.230400, 121.473700, 120,
    '上海市黄浦区人民广场1号', 'E2E GPX 路线起点地标，包含广场钟楼观测点。', 30,
    NULL, '310100', 1, NULL, NULL,
    NULL, NULL, 3, 'E2E,地标,起点', 100, 0, 'e2e_clock_tower', NOW()
  ),
  (
    992522, '南京东路猫咖驿站', 2, 31.233500, 121.476500, 100,
    '上海市黄浦区南京东路188号', 'E2E 合作商户据点，到店可领优惠券并触发互动。', 25,
    970001, '310100', 1, 1, 1,
    8001, 992101, 2, 'E2E,咖啡,商家据点', 95, 0, 'e2e_cat_cafe', NOW()
  ),
  (
    992523, '汉口路老报馆碑', 1, 31.236200, 121.480100, 100,
    '上海市黄浦区汉口路309号', 'E2E GPX 路线中段历史文化点位。', 30,
    NULL, '310100', 1, NULL, NULL,
    NULL, NULL, 2, 'E2E,人文,历史', 90, 0, 'e2e_old_press', NOW()
  ),
  (
    992524, '外滩万国钟楼驿站', 1, 31.238500, 121.484000, 120,
    '上海市黄浦区中山东一路13号', 'E2E GPX 路线终点站，可打卡外滩钟楼并点亮徽章。', 40,
    NULL, '310100', 1, 1, 2,
    8001, 992502, 3, 'E2E,外滩,终点', 85, 0, 'e2e_bund_tower', NOW()
  )
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  lat = VALUES(lat),
  lng = VALUES(lng),
  status = 1,
  audit_status = VALUES(audit_status),
  application_type = VALUES(application_type);

-- Ensure /api/map/nearby also finds a live merchant node at Shanghai route
INSERT INTO cms_registration_merchant (
  id, member_id, topic_id, mode, node_id, address, address_name,
  latitude, longitude, template_id, activity_desc, status, audit_status,
  del_flag, check_status, create_by, create_time, update_by, update_time
)
VALUES (
  992521, 9002, 992001, 2, 992021,
  '上海市黄浦区南京东路188号', '南京东路猫咖驿站',
  '31.233500', '121.476500', 992101,
  'E2E 合作驿站（地图附近点位测试数据）', 1, 1,
  0, 1, 'e2e-seed-v2', NOW(), 'e2e-seed-v2', NOW()
)
ON DUPLICATE KEY UPDATE
  latitude = '31.233500',
  longitude = '121.476500',
  status = 1,
  audit_status = 1,
  del_flag = 0,
  update_by = 'e2e-seed-v2',
  update_time = NOW();

-- ============================================================================
-- 2. Topic Playable Journey (QA + Hint Ladder) + Active Team Session & Backpack
-- ============================================================================
UPDATE cms_topic
SET
  published_theme_runtime_config_json = JSON_OBJECT(
    'schemaVersion', 1,
    'backpackEnabled', true,
    'gameplayRuntime', JSON_OBJECT(
      'teamPool', JSON_OBJECT('hints', 3, 'skips', 1, 'timerSec', 1800)
    )
  ),
  theme_runtime_config_json = JSON_OBJECT(
    'schemaVersion', 1,
    'backpackEnabled', true,
    'gameplayRuntime', JSON_OBJECT(
      'teamPool', JSON_OBJECT('hints', 3, 'skips', 1, 'timerSec', 1800)
    )
  ),
  published_route_graph_json = IF(
    published_route_graph_json IS NOT NULL,
    JSON_SET(published_route_graph_json, '$.journeyRules.stateEnabled', CAST(TRUE AS JSON)),
    published_route_graph_json
  ),
  published_config_version = GREATEST(COALESCE(published_config_version, 0), 1),
  team_mode = 2,
  team_max_members = 4,
  update_time = NOW()
WHERE id = 992001 AND del_flag = 0;

UPDATE ums_member
SET point = GREATEST(COALESCE(point, 0), 500)
WHERE id IN (9004, 9009);

INSERT INTO play_team (
  id, owner_type, owner_id, leader_member_id, invite_code, title,
  join_mode, max_members, joined_count, status, expire_time, create_time, update_time
)
VALUES (
  992530, 2, 992031, 9004, 'E2ETEAM1', 'E2E 钟楼探索小队',
  1, 4, 2, 0, DATE_ADD(NOW(), INTERVAL 30 DAY), NOW(), NOW()
)
ON DUPLICATE KEY UPDATE
  status = 0,
  joined_count = 2,
  expire_time = DATE_ADD(NOW(), INTERVAL 30 DAY),
  update_time = NOW();

INSERT INTO play_team_member (team_id, member_id, role, status, create_time, update_time)
VALUES
  (992530, 9004, 1, 1, NOW(), NOW()),
  (992530, 9009, 0, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE
  status = 1,
  update_time = NOW();

INSERT INTO play_team_resource_pool (
  team_id, topic_id, activity_id, hints_total, hints_used, skips_total, skips_used,
  timer_limit_sec, timer_started_at, version, frozen_at
)
VALUES (
  992530, 992001, 992031, 3, 0, 1, 0, 1800, NULL, 0, NOW(3)
)
ON DUPLICATE KEY UPDATE
  hints_total = GREATEST(hints_total, 3),
  skips_total = GREATEST(skips_total, 1),
  timer_limit_sec = 1800;

-- ============================================================================
-- 3. Club 7001: Channel + Free Activity + Dedicated Waitlist Activity
-- ============================================================================
INSERT INTO im_channel (
  id, guild_kind, guild_id, parent_id, kind, name, topic, position,
  is_default, opt_in_default, status, visibility, flags, age_gate,
  slowmode_sec, bot_enabled, feed_source, feed_ref, perm_version,
  del_flag, create_by, create_time, meta_version
)
VALUES (
  992531, 1, 7001, 4, 1, 'e2e-活动讨论', 'E2E 测试俱乐部活动频道：集合通知与路线交流', 10,
  0, 1, 0, 1, 0, 0,
  0, 1, 0, 0, 0,
  0, 'e2e-seed-v2', NOW(3), 0
)
ON DUPLICATE KEY UPDATE
  status = 0,
  visibility = 1,
  del_flag = 0,
  topic = VALUES(topic);

INSERT INTO cms_activity (
  id, member_id, name, description, address_name, longitude, latitude, address,
  status, publish_status, del_flag, start_date, end_date, topic_id,
  check_status, is_settlement, create_by, create_time, update_by, update_time,
  club_id, content_safety_hold, city_code
)
VALUES (
  992033, 9003, 'E2E 候补体验场·外滩夜行',
  '本场名额已满（remaining=0），用于验证俱乐部候补队列流程。',
  '人民广场钟楼集合点', '121.473700', '31.230400', '上海市黄浦区人民广场1号',
  1, 1, 0, DATE_ADD(NOW(), INTERVAL 7 DAY), DATE_ADD(NOW(), INTERVAL 8 DAY), 992001,
  1, 0, 'e2e-seed-v2', NOW(), 'e2e-seed-v2', NOW(),
  7001, 0, '310100'
)
ON DUPLICATE KEY UPDATE
  status = 1,
  publish_status = 1,
  check_status = 1,
  del_flag = 0,
  start_date = DATE_ADD(NOW(), INTERVAL 7 DAY),
  end_date = DATE_ADD(NOW(), INTERVAL 8 DAY),
  update_by = 'e2e-seed-v2',
  update_time = NOW();

INSERT INTO oms_ticket (
  id, member_id, owner_type, owner_id, name, price,
  start_time, end_time, sale_start_time, sale_end_time,
  total_inventory, remaining_inventory, mode, team_size, min_people, max_people,
  signup_deadline, team_status, refund_rule, refund_supported, sync_with_theme,
  need_audit, del_flag, create_by, create_time, update_by, update_time
)
VALUES (
  992043, 9003, 2, 992033, 'E2E 候补体验票（已满额）', 0.00,
  DATE_ADD(NOW(), INTERVAL 7 DAY), DATE_ADD(NOW(), INTERVAL 8 DAY),
  DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 7 DAY),
  1, 0, 1, 1, 1, 1,
  DATE_ADD(NOW(), INTERVAL 6 DAY), 0, '开场前可免费取消', 1, 0,
  0, 0, 'e2e-seed-v2', NOW(), 'e2e-seed-v2', NOW()
)
ON DUPLICATE KEY UPDATE
  remaining_inventory = 0,
  sale_end_time = DATE_ADD(NOW(), INTERVAL 7 DAY),
  signup_deadline = DATE_ADD(NOW(), INTERVAL 6 DAY),
  del_flag = 0,
  update_by = 'e2e-seed-v2',
  update_time = NOW();

INSERT INTO club_event_series (
  id, club_id, topic_id, recurrence_type, timezone, default_lead_member_id,
  default_capacity, waitlist_enabled, host_reservation_required, offer_minutes,
  status, version, created_by_member_id, request_id, request_fingerprint,
  create_by, create_time, update_by, update_time
)
VALUES (
  992531, 7001, 992001, 'ONCE', 'Asia/Shanghai', 9003,
  1, 1, 0, 60,
  'ACTIVE', 0, 9003, 'e2e-v2-waitlist-992033',
  SHA2('e2e-v2-waitlist-992033', 256),
  'e2e-seed-v2', NOW(), 'e2e-seed-v2', NOW()
)
ON DUPLICATE KEY UPDATE
  waitlist_enabled = 1,
  status = 'ACTIVE',
  update_by = 'e2e-seed-v2',
  update_time = NOW();

INSERT INTO club_event_occurrence (
  id, series_id, activity_id, occurrence_at, default_lead_member_id, status, version, create_time
)
VALUES (
  992531, 992531, 992033, DATE_ADD(NOW(), INTERVAL 7 DAY), 9003, 'ACTIVE', 0, NOW()
)
ON DUPLICATE KEY UPDATE
  occurrence_at = DATE_ADD(NOW(), INTERVAL 7 DAY),
  status = 'ACTIVE';

-- ============================================================================
-- 4. Merchant 8001 / Member 9002: Non-zero TEST Balance + Payout Account
-- ============================================================================
UPDATE ums_member
SET balance = GREATEST(COALESCE(balance, 0.00), 1280.00),
    update_time = NOW()
WHERE id = 9002;

INSERT INTO ums_member_balance_detail (
  id, member_id, current_balance, after_balance, change_balance,
  change_reason, event_type, event_id, change_type, del_flag,
  create_by, create_time, update_by, update_time
)
VALUES
  (
    992541, 9002, 0.00, 880.00, 880.00,
    'E2E TEST 主题分润结算', 1, 992001, 1, 0,
    'e2e-seed-v2', NOW(), 'e2e-seed-v2', NOW()
  ),
  (
    992542, 9002, 880.00, 1280.00, 400.00,
    'E2E TEST 活动核销分成', 2, 992031, 1, 0,
    'e2e-seed-v2', NOW(), 'e2e-seed-v2', NOW()
  )
ON DUPLICATE KEY UPDATE
  change_balance = VALUES(change_balance),
  del_flag = 0,
  create_time = NOW(),
  update_by = 'e2e-seed-v2',
  update_time = NOW();

INSERT INTO redemption (
  id, registration_id, chapter_id, topic_id, merchant_id, offer_id,
  operator_id, status, idempotency_key, create_time
)
VALUES (
  992541, 75, 992011, 992001, 9002, NULL,
  9002, 'ACTIVE', 'e2e-v2-redeem-75-992011', NOW()
)
ON DUPLICATE KEY UPDATE
  status = 'ACTIVE';

INSERT INTO merchant_payable (
  id, claim_id, redemption_id, topic_id, chapter_id, merchant_member_id,
  per_head_fee_snapshot, amount, entry_type, event_key, idempotency_key, create_time
)
VALUES (
  992541, NULL, 992541, 992001, 992011, 9002,
  150.00, 150.00, 'ACCRUAL', 'ORIGIN', 'e2e-v2-accrual-992541', NOW()
)
ON DUPLICATE KEY UPDATE
  amount = 150.00,
  create_time = NOW();

INSERT INTO merchant_payable_batch (
  id, merchant_member_id, period_ym, amount_total, status, invoice_status, create_time
)
VALUES (
  992541, 9002, DATE_FORMAT(NOW(), '%Y-%m'), 150.00, 'PENDING', 'NONE', NOW()
)
ON DUPLICATE KEY UPDATE
  amount_total = 150.00,
  status = 'PENDING';

INSERT INTO ecom_sub_merchant (
  owner_type, owner_id, out_request_no, applyment_id, applyment_state,
  applyment_state_desc, sign_url, sub_mchid, last_queried_at, created_by
)
VALUES (
  'MERCHANT', 8001, 'E2E_SUB_MCH_8001', 1900008001, 'FINISH',
  '已完成二级商户进件（TEST环境模拟户）', NULL, '1900008001', NOW(3), 'e2e-seed-v2'
)
ON DUPLICATE KEY UPDATE
  applyment_state = 'FINISH',
  applyment_state_desc = VALUES(applyment_state_desc),
  sub_mchid = '1900008001',
  last_queried_at = NOW(3);

-- ============================================================================
-- 5. Rewards Policy + Active Reward Pledge + Drop Rules + Store Items
-- ============================================================================
INSERT INTO w17_reward_policy (policy_code, rule_version, decision, actor_member_id)
VALUES
  ('CLAIM', 'e2e-v2-rule-1', 'OPEN', NULL),
  ('RULE', 'e2e-v2-rule-1', 'OPEN', NULL),
  ('REVIEWER', '', 'OPEN', 9004),
  ('STORE_HOLD', '', 'OPEN', NULL)
ON DUPLICATE KEY UPDATE
  decision = 'OPEN',
  actor_member_id = VALUES(actor_member_id);

INSERT INTO w17_reward_pledge (
  pledge_id, store_id, merchant_member_id, coupon_id, supply_ref, quantity,
  service_start, service_end, redeem_start, redeem_end, terms, minor_restricted, state, created_at
)
VALUES (
  'e2e-v2-pledge-001', 8001, 9002, 970001, 'E2E 合作门店饮品抵扣券', 20,
  1794499200000, 1893456000000, 1794499200000, 1893456000000,
  '完成指定主题章节即可领取，到店凭券兑换一杯指定饮品。', 0, 'FROZEN', 1794620000000
)
ON DUPLICATE KEY UPDATE
  state = 'FROZEN',
  quantity = 20,
  service_end = 1893456000000,
  redeem_end = 1893456000000;

INSERT INTO w17_reward_pledge_version (
  version_id, pledge_id, version_no, payload_hash, quantity,
  service_start, service_end, redeem_start, redeem_end, terms, minor_restricted,
  rule_version, state, merchant_member_id, merchant_hash, merchant_confirmed_at,
  reviewer_member_id, reviewer_hash, frozen_at
)
VALUES (
  'e2e-v2-pledge-001-v1', 'e2e-v2-pledge-001', 1,
  SHA2('e2e-v2-pledge-001-v1-payload', 256), 20,
  1794499200000, 1893456000000, 1794499200000, 1893456000000,
  '完成指定主题章节即可领取，到店凭券兑换一杯指定饮品。', 0,
  'e2e-v2-rule-1', 'FROZEN',
  9002, SHA2('merchant-confirm-e2e-v2-pledge-001', 256), 1794620001000,
  9004, SHA2('reviewer-freeze-e2e-v2-pledge-001', 256), 1794620002000
)
ON DUPLICATE KEY UPDATE
  state = 'FROZEN',
  service_end = 1893456000000,
  redeem_end = 1893456000000;

INSERT INTO w17_reward_stock (
  version_id, quantity, available, reserved, awarded_unredeemed,
  redeemed, expired, frozen_units, withdrawn, version_no
)
VALUES (
  'e2e-v2-pledge-001-v1', 20, 20, 0, 0, 0, 0, 20, 0, 1
)
ON DUPLICATE KEY UPDATE
  quantity = 20,
  available = GREATEST(available, 1);

INSERT INTO cms_points_setting (
  id, title, value, description, type, event_type, status, sort_order, del_flag,
  create_by, create_time, update_by, update_time
)
VALUES
  (1, '每日签到奖励', 10.00, '每日首次登录领取探索积分', 1, 1, 1, 1, 0, 'e2e-seed-v2', NOW(), 'e2e-seed-v2', NOW()),
  (2, '完成城市定向节点', 25.00, '完成任意站点打卡验证获得积分', 1, 2, 1, 2, 0, 'e2e-seed-v2', NOW(), 'e2e-seed-v2', NOW()),
  (3, '首次发现漫游 POI', 20.00, '步行靠近新地标并解锁点亮', 1, 3, 1, 3, 0, 'e2e-seed-v2', NOW(), 'e2e-seed-v2', NOW()),
  (4, '发布社区实录', 30.00, '发布公开漫游记录并审核通过', 1, 4, 1, 4, 0, 'e2e-seed-v2', NOW(), 'e2e-seed-v2', NOW())
ON DUPLICATE KEY UPDATE
  title = VALUES(title),
  value = VALUES(value),
  status = 1,
  del_flag = 0,
  update_time = NOW();

INSERT INTO reward_drop_rule (id, scope, topic_id, scene_key, drop_type, weight, param_json, status, create_time)
VALUES
  (992551, 0, NULL, NULL, 1, 60, '{"mult":2}', 1, NOW()),
  (992552, 1, 992001, NULL, 3, 40, '{"couponTemplateId":970001}', 1, NOW())
ON DUPLICATE KEY UPDATE
  status = 1,
  param_json = VALUES(param_json);

-- Store Items (pms_product + pms_sku + pms_gift)
INSERT INTO pms_product (
  id, merchant_id, product_name, pic, publish_status, sort_id,
  price, original_price, stock, unit, weight, detail_html,
  del_flag, create_by, create_time, update_by, update_time
)
VALUES
  (
    992551, 8001, 'E2E 城瘾限定探索帆布袋', '', 1, 100,
    200, 300, 50, '件', 220.00, '<p>E2E 积分商城可兑换实物帆布袋。</p>',
    0, 'e2e-seed-v2', NOW(), 'e2e-seed-v2', NOW()
  ),
  (
    992552, 8001, 'E2E 老城地标徽章礼盒', '', 1, 90,
    500, 680, 30, '套', 180.00, '<p>含老城三章限定金属徽章。</p>',
    0, 'e2e-seed-v2', NOW(), 'e2e-seed-v2', NOW()
  )
ON DUPLICATE KEY UPDATE
  product_name = VALUES(product_name),
  publish_status = 1,
  price = VALUES(price),
  stock = VALUES(stock),
  del_flag = 0,
  update_by = 'e2e-seed-v2',
  update_time = NOW();

INSERT INTO pms_sku (
  id, product_id, out_sku_id, sku_name, price, original_price, stock,
  create_by, create_time, update_by, update_time
)
VALUES
  (992551, 992551, 'E2E-BAG-STD', '米白标准款', 200.00, 300.00, 50, 'e2e-seed-v2', NOW(), 'e2e-seed-v2', NOW()),
  (992552, 992552, 'E2E-PIN-BOX', '三枚珍藏礼盒', 500.00, 680.00, 30, 'e2e-seed-v2', NOW(), 'e2e-seed-v2', NOW())
ON DUPLICATE KEY UPDATE
  price = VALUES(price),
  stock = VALUES(stock),
  update_by = 'e2e-seed-v2',
  update_time = NOW();

INSERT INTO pms_gift (
  id, gift_name, pic_url, contents, point, status, del_flag,
  create_by, create_time, update_by, update_time
)
VALUES
  (992551, 'E2E 合作门店特调咖啡礼', '', '积分兑换合作门店现磨美式/拿铁一杯', 100, 1, 0, 'e2e-seed-v2', NOW(), 'e2e-seed-v2', NOW()),
  (992552, 'E2E 外滩复古明信片套装', '', '四张手绘外滩建筑群明信片', 50, 1, 0, 'e2e-seed-v2', NOW(), 'e2e-seed-v2', NOW())
ON DUPLICATE KEY UPDATE
  gift_name = VALUES(gift_name),
  status = 1,
  del_flag = 0,
  update_by = 'e2e-seed-v2',
  update_time = NOW();

-- ============================================================================
-- 6. Workshop Purchased Licences (for both player/author 9004 and club 9003)
-- ============================================================================
DELETE FROM workshop_order WHERE order_no LIKE 'WKO_E2E_V2_%';

SET @wk_content_json = '{"adoptSourceOnly":false,"description":"E2E 可复用·老城钟楼暗号问答","isSync":0,"originalTemplateId":0,"scope":"personal","title":"E2E 可复用·老城钟楼暗号问答"}';
SET @wk_terms_json = '{"allowedRegions":["310000","310100"],"commercialUse":true,"installLimit":10,"text":"E2E 测试工坊商业授权条款：允许在指定城市规划区复用该章节模板。","version":"v1"}';

INSERT INTO workshop_version (
  id, version_id, seller_id, request_id, request_hash, module_id, source_canonical_id,
  content_hash, content_json, terms_hash, terms_json, price_fen, created_at,
  source_kind, review_state, approved_at
)
VALUES (
  970102, 'WKV_99250100000000000000000000992501', 9002, 'e2e-v2-wk-ver-1',
  SHA2('e2e-v2-wk-ver-1', 256), 992501, 992501,
  SHA2(@wk_content_json, 256),
  @wk_content_json,
  SHA2(@wk_terms_json, 256),
  @wk_terms_json,
  100, NOW(3),
  'PRIVATE_TEMPLATE', 'APPROVED', NOW(3)
)
ON DUPLICATE KEY UPDATE
  version_id = VALUES(version_id),
  content_hash = VALUES(content_hash),
  content_json = VALUES(content_json),
  terms_hash = VALUES(terms_hash),
  terms_json = VALUES(terms_json),
  review_state = 'APPROVED',
  approved_at = COALESCE(approved_at, NOW(3));

INSERT INTO workshop_order (
  id, order_no, buyer_id, request_id, request_hash, version_id,
  amount_fen, currency, provider, state, license_status, paid_at, created_at
)
VALUES
  (
    970211, 'WKO_99250100000000000000000000009004', 9004, 'e2e-v2-wk-ord-9004-1',
    SHA2('e2e-v2-wk-ord-9004-1', 256), 'WKV_99250100000000000000000000992501',
    100, 'CNY', 'WECHAT_APP', 'PAID', 'ACTIVE', NOW(3), NOW(3)
  ),
  (
    970212, 'WKO_99250100000000000000000000009003', 9003, 'e2e-v2-wk-ord-9003-1',
    SHA2('e2e-v2-wk-ord-9003-1', 256), 'WKV_99250100000000000000000000992501',
    100, 'CNY', 'WECHAT_APP', 'PAID', 'ACTIVE', NOW(3), NOW(3)
  )
ON DUPLICATE KEY UPDATE
  order_no = VALUES(order_no),
  version_id = VALUES(version_id),
  state = 'PAID',
  license_status = 'ACTIVE',
  paid_at = COALESCE(paid_at, NOW(3));

-- ============================================================================
-- 8. Project in Rejected State (for Appeals — leave un-appealed for E2E)
-- ============================================================================
INSERT INTO cms_topic (
  id, member_id, topic_type, product_type, route_mode, config_version, published_config_version,
  name, subtitle, description, address_name, total_time, total_mileage, location_count, template_count,
  category_ids, status, reason, user_status, merchant_status, check_status,
  lifecycle, del_flag, is_draft, is_template, club_id, team_mode, team_max_members,
  content_safety_hold, city_code, create_by, create_time, update_by, update_time
)
VALUES
  (
    992561, 9004, 2, 1, 'LINEAR', 1, 0,
    'E2E 待申诉项目·外滩夜景线', '审核驳回待申诉示例',
    '由玩家 9004 提交的城市探索草案，处于审核驳回状态供申诉链路测试。',
    '上海市黄浦区中山东一路', 60, 1.80, 1, 0,
    '21,24', 2, 'E2E 测试驳回：请补充夜间安全提示与集合点指引说明。', 0, 0, 2,
    0, 0, 0, 0, NULL, 0, 4,
    0, '310100', 'e2e-seed-v2', NOW(), 'e2e-seed-v2', NOW()
  ),
  (
    992562, 9003, 1, 1, 'LINEAR', 1, 0,
    'E2E 俱乐部待申诉项目·苏州河夜行', '审核驳回待申诉示例',
    '由俱乐部 9003 提交的城市探索草案，处于审核驳回状态供申诉链路测试。',
    '上海市黄浦区南苏州路', 75, 2.20, 1, 0,
    '21,24', 2, 'E2E 测试驳回：请补充应急联系人与退改规则说明。', 0, 0, 2,
    0, 0, 0, 0, 7001, 0, 4,
    0, '310100', 'e2e-seed-v2', NOW(), 'e2e-seed-v2', NOW()
  )
ON DUPLICATE KEY UPDATE
  status = 2,
  check_status = 2,
  user_status = 0,
  is_draft = 0,
  del_flag = 0,
  content_safety_hold = 0,
  reason = VALUES(reason),
  update_by = 'e2e-seed-v2',
  update_time = NOW();

INSERT INTO audit_task (
  id, biz_type, biz_id, submitter_id, source_type, risk_level, status,
  submitted_at, deadline_at, snapshot_json, version, create_by, create_time, update_by, update_time, remark
)
VALUES
  (
    992561, 'TOPIC', 992561, 9004, 'publish', 'NORMAL', 2,
    DATE_SUB(NOW(), INTERVAL 2 HOUR), DATE_ADD(NOW(), INTERVAL 22 HOUR),
    JSON_OBJECT('topicId', 992561, 'title', 'E2E 待申诉项目·外滩夜景线'),
    1, 'e2e-seed-v2', NOW(), 'e2e-seed-v2', NOW(), 'E2E 测试审核驳回'
  ),
  (
    992562, 'TOPIC', 992562, 9003, 'publish', 'NORMAL', 2,
    DATE_SUB(NOW(), INTERVAL 2 HOUR), DATE_ADD(NOW(), INTERVAL 22 HOUR),
    JSON_OBJECT('topicId', 992562, 'title', 'E2E 俱乐部待申诉项目·苏州河夜行'),
    1, 'e2e-seed-v2', NOW(), 'e2e-seed-v2', NOW(), 'E2E 测试审核驳回'
  )
ON DUPLICATE KEY UPDATE
  status = 2,
  submitter_id = VALUES(submitter_id),
  update_by = 'e2e-seed-v2',
  update_time = NOW();

INSERT INTO audit_record (
  id, task_id, action, operator_id, reason_code, reason_text, snapshot_json, create_time, reason_codes_json
)
VALUES
  (
    992561, 992561, 'REJECT', 1, 'CONTENT_INCOMPLETE', '请补充夜间安全提示与集合点指引说明。',
    JSON_OBJECT('topicId', 992561, 'decision', 'REJECT'), NOW(), JSON_ARRAY('CONTENT_INCOMPLETE')
  ),
  (
    992562, 992562, 'REJECT', 1, 'CONTENT_INCOMPLETE', '请补充应急联系人与退改规则说明。',
    JSON_OBJECT('topicId', 992562, 'decision', 'REJECT'), NOW(), JSON_ARRAY('CONTENT_INCOMPLETE')
  )
ON DUPLICATE KEY UPDATE
  action = 'REJECT',
  reason_text = VALUES(reason_text);

-- ============================================================================
-- 9. Published Community Post (PUBLIC)
-- ============================================================================
INSERT INTO community_post (
  id, author_id, community_id, client_request_id, post_type, title, body,
  audience, comment_policy, reply_approval_enabled, slow_mode_seconds, lifecycle,
  location_precision, city_code, poi_name, recommendation_eligible, search_eligible,
  interaction_enabled, disclosure_type, safety_labels_json, guideline_version_id,
  published_at, version, create_time, update_time, audit_level
)
VALUES (
  992571, 9004, NULL, 'e2e-v2-post-9004-01', 'ROUTE_DISCOVERY',
  'E2E 人民广场至外滩漫步实录',
  '沿着 e2e/route.gpx 从人民广场钟楼走到外滩万国建筑群，沿途解开了钟楼暗号谜题并领到合作猫咖饮品券，推荐大家组队探索！',
  'PUBLIC', 'EVERYONE', 0, 0, 'PUBLISHED',
  'CITY', '310100', '人民广场', 1, 1,
  1, 'NONE', '[]', (SELECT COALESCE(MAX(id), 1) FROM community_guideline_version WHERE status = 'ACTIVE'),
  NOW(), 1, NOW(), NOW(), 1
)
ON DUPLICATE KEY UPDATE
  lifecycle = 'PUBLISHED',
  audience = 'PUBLIC',
  title = VALUES(title),
  body = VALUES(body),
  published_at = COALESCE(published_at, NOW()),
  update_time = NOW();
