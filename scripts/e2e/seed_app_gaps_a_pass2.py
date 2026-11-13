#!/usr/bin/env python3
"""TEST-only fixture for app gaps A pass 2 (run after seed_app_gaps_a.py, on a TEST copy only).

    python3 scripts/e2e/seed_app_gaps_a_pass2.py | docker exec -i be-int-mysql sh -c 'MYSQL_PWD=$MYSQL_ROOT_PASSWORD mysql -uroot cy_gaps_a'

Adds an r491 CITY board (region gaps-a-r491: only rolls and acquisitions spend actions) with one merchant point of
merchant 8001 (owner 9002) and one public POI point, task definitions, a published location-share policy, a mutual
follow 9001<->9004, a free-explore bundle held by 9001 and a topic authored by 9001. Idempotent.
"""
import hashlib

BOARD, REGION = "gapsa-r491-1", "gaps-a-r491"
RULES = "城瘾城市局 r491 测试规则：掷骰只记录；到店由门店确认；完成到店任务后可自费占领。"
HASH = hashlib.sha256(RULES.encode("utf-8")).hexdigest()
print("SET NAMES utf8mb4;")
for t in ["city_game_event", "city_game_request", "city_game_quote", "city_game_task_attempt", "city_game_roll", "city_game_round",
          "city_game_participation", "city_game_team", "city_game_service", "city_game_property", "city_game_point", "city_game_outing",
          "city_task_definition", "city_task_instance", "city_arrival_challenge", "city_arrival_evidence", "city_acquisition_quote",
          "city_acquisition_title", "city_value_visit", "city_point_event", "city_map_stream"]:
    print(f"DELETE FROM {t} WHERE board_id = '{BOARD}';")
print(f"DELETE FROM city_game_board WHERE board_id = '{BOARD}';")
print("DELETE FROM city_r491_request WHERE member_id IN (9001, 9002, 9004);")
print("DELETE FROM city_merchant_operation WHERE merchant_id = 8001;")
print(f"""INSERT INTO city_game_board(board_id, game_id, region_id, season_id, rules_release_id, release_id, rules_hash, rule_version,
 lifecycle, title, revision, board_version, event_sequence, dice_id, dice_faces, dice_weights, actions_per_round, max_rounds,
 roll_cost, acquisition_cost, task_cost, service_cost, challenge_cost,
 acquire_points, challenge_points, holding_yield, goal_score, holding_limit, protection_seconds, quote_valid_seconds, term_seconds,
 min_members, max_members, rules_id, rules_text, challenge_rule_id, protection_text, safety_statement, valid_from,
 map_datum, map_south, map_west, map_north, map_east)
VALUES ('{BOARD}', 'city-game', '{REGION}', 's2026w46r', 'rel-r491-1', 910002, '{HASH}', 'v1', 'OPEN', '测试城市局 r491', 1, 1, 0,
 'd6', '[1,2,3,4,5,6]', '[1,1,1,1,1,1]', 6, 2, 1, 2, 0, 0, 0, 30, 20, 2, 1, 3, 600, 300, 86400, 1, 4, 'rules-r491', '{RULES}',
 'challenge-r491', '占领后十分钟内不可被挑战。', '请注意交通安全。', '2026-11-01 00:00:00', 'GCJ02', 31.200000, 121.400000, 31.300000, 121.500000);""")
print("DELETE FROM roam_poi WHERE id IN (990101, 990102);")
print("INSERT INTO roam_poi(id, name, type, lat, lng, status, city_code, merchant_id) VALUES"
      " (990101, '测试咖啡馆', 2, 31.235000, 121.475000, 1, '310000', 8001), (990102, '人民广场', 1, 31.230000, 121.470000, 1, '310000', NULL);")
print(f"""INSERT INTO city_game_point(board_id, point_id, title, latitude, longitude, domain, collected_at, merchant_id, place_id) VALUES
 ('{BOARD}', 'rp-a', '人民广场', 31.230, 121.470, 'POI', '2026-11-01 00:00:00', NULL, 990102),
 ('{BOARD}', 'rp-b', '测试咖啡馆', 31.235, 121.475, 'MERCHANT', '2026-11-01 00:00:00', 8001, 990101),
 ('{BOARD}', 'rp-c', '书店', 31.240, 121.480, 'MERCHANT', '2026-11-01 00:00:00', NULL, NULL);""")
print(f"""INSERT INTO city_game_property(board_id, property_id, point_id, track_index, role) VALUES
 ('{BOARD}', 'rprop-a', 'rp-a', 0, 'NEUTRAL'), ('{BOARD}', 'rprop-b', 'rp-b', 1, 'OPERABLE'), ('{BOARD}', 'rprop-c', 'rp-c', 2, 'OPERABLE');""")
print(f"""INSERT INTO city_task_definition(board_id, task_id, access, point_id, title, question, options_json, answer_option_id, max_attempts) VALUES
 ('{BOARD}', 'nt-1', 'REMOTE_NEUTRAL', NULL, '外滩钟楼', '外滩海关大楼的钟楼面朝哪条江？', '[{{"id":"a","label":"苏州河"}},{{"id":"b","label":"黄浦江"}}]', 'b', 2),
 ('{BOARD}', 'ot-1', 'ONSITE_ZERO_EFFECT', 'rp-b', '店里的招牌', '这家店的招牌饮品是？', '[{{"id":"a","label":"拿铁"}},{{"id":"b","label":"手冲"}}]', 'b', 3);""")
print("UPDATE ums_member SET point = 100 WHERE id IN (9001, 9004) AND phone LIKE '1390000000%';")
# location share
print("DELETE FROM location_share_event WHERE recipient_id IN (9001, 9004);")
print("DELETE FROM location_share_observation WHERE grant_id IN (SELECT grant_id FROM location_share_grant WHERE owner_id IN (9001, 9004));")
print("DELETE FROM location_share_grant WHERE owner_id IN (9001, 9004);")
print("DELETE FROM location_share_policy WHERE policy_id = 'pls-test';")
print("""INSERT INTO location_share_policy(policy_id, version, approval_ref, mode_choices, duration_choices, refresh_after_ms, stale_after_ms,
 display_lease_ms, owner_lease_ms, status) VALUES ('pls-test', 1, 'TEST-ONLY-review',
 '[{"id":"exact","mode":"EXACT","datum":"WGS84"},{"id":"coarse","mode":"COARSE","datum":"WGS84","latitudeStep":0.01,"longitudeStep":0.01}]',
 '[{"id":"h1","seconds":3600},{"id":"m15","seconds":900}]', 5000, 30000, 60000, 60000, 'PUBLISHED');""")
print("DELETE FROM ums_member_follow WHERE (member_id, follow_member_id) IN ((9001, 9004), (9004, 9001));")
print("INSERT INTO ums_member_follow(member_id, follow_member_id, create_time) VALUES (9001, 9004, NOW()), (9004, 9001, NOW());")
# authored topic (cover + attribute author) and a free-explore bundle held by 9001
TOPIC = 990101
print(f"DELETE FROM topic_cover_selection WHERE topic_id = {TOPIC};")
print(f"DELETE FROM theme_state_key WHERE topic_id = {TOPIC};")
print(f"DELETE FROM theme_attribute_registry_version WHERE topic_id = {TOPIC};")
print(f"DELETE FROM cms_topic_chapter WHERE topic_id = {TOPIC};")
print(f"DELETE FROM cms_topic WHERE id = {TOPIC};")
print(f"""INSERT INTO cms_topic(id, name, member_id, product_type, lifecycle, del_flag, img_url, config_version)
 VALUES ({TOPIC}, '外滩自由探索(测试)', 9001, 2, 3, 0, NULL, 1);""")
print(f"INSERT INTO cms_topic_chapter(id, topic_id, name, required) VALUES (990111, {TOPIC}, '第一章', 1), (990112, {TOPIC}, '第二章', 1);")
# a draft topic authored by 9001 for the cover and attribute-author checks (a selling topic is sale-frozen)
DRAFT = 990102
print(f"DELETE FROM topic_cover_selection WHERE topic_id = {DRAFT};")
print(f"DELETE FROM theme_state_key WHERE topic_id = {DRAFT};")
print(f"DELETE FROM theme_attribute_registry_version WHERE topic_id = {DRAFT};")
print(f"DELETE FROM cms_topic WHERE id = {DRAFT};")
print(f"INSERT INTO cms_topic(id, name, member_id, product_type, lifecycle, del_flag, config_version) VALUES ({DRAFT}, '草稿主题(测试)', 9001, 2, 0, 0, 1);")
REG = 990201
for t in ["explore_bundle_review", "explore_bundle_renewal_intent", "explore_bundle_appeal", "explore_bundle_unit", "explore_bundle_grant", "explore_bundle_order"]:
    print(f"DELETE FROM {t} WHERE registration_id = {REG};")
print(f"DELETE FROM cms_registration WHERE id = {REG};")
print(f"""INSERT INTO cms_registration(id, registration_no, owner_id, ticket_id, ticket_price, member_id, original_amount, total_amount,
 payable_amount, pay_expire_time, payment_status, registration_status, verification_code)
 VALUES ({REG}, 'TESTR990201', 0, 0, 199.00, 9001, 199.00, 199.00, 199.00, '2026-11-01', 2, 2, 'TESTVC990201');""")
print(f"""INSERT INTO explore_bundle_order(id, registration_id, holder_member_id, topic_id, product_version, quota_version, policy_version, contract_version,
 settlement_rule_version, snapshot_json, paid_amount, valid_from, valid_to, idempotency_key, request_fingerprint, create_time)
 VALUES (990301, {REG}, 9001, {TOPIC}, '{"p"*64}', '{"q"*64}', 'pv1', '{"c"*64}', 'sr1', '{{}}', 199.00, '2026-11-01', '2027-01-31',
 'bundle-order:{REG}', '{"f"*64}', '2026-11-01');""")
print(f"""INSERT INTO explore_bundle_grant(id, bundle_order_id, registration_id, holder_member_id, topic_id, chapter_id, grant_no, grant_type, units_total,
 valid_from, valid_to, create_time) VALUES (990401, 990301, {REG}, 9001, {TOPIC}, 990111, 1, 'PURCHASE', 1, '2026-11-01', '2027-01-31', '2026-11-01'),
 (990402, 990301, {REG}, 9001, {TOPIC}, 990112, 1, 'PURCHASE', 1, '2026-11-01', '2027-01-31', '2026-11-01');""")
print(f"""INSERT INTO explore_bundle_unit(id, grant_id, registration_id, holder_member_id, chapter_id, unit_seq, unit_code, unit_value, status, create_time)
 VALUES (990501, 990401, {REG}, 9001, 990111, 1, 'FXTEST990501', 99.50, 'AVAILABLE', '2026-11-01'),
 (990502, 990402, {REG}, 9001, 990112, 1, 'FXTEST990502', 99.50, 'AVAILABLE', '2026-11-01');""")
