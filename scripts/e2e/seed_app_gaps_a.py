#!/usr/bin/env python3
"""TEST-only fixture for app gaps A: one OPEN city board in region gaps-a-test.

Prints SQL for a TEST copy (never production, never the shared cy_e2e_beint):
    python3 scripts/e2e/seed_app_gaps_a.py | docker exec -i be-int-mysql sh -c 'MYSQL_PWD=$MYSQL_ROOT_PASSWORD mysql -uroot cy_gaps_a'
Idempotent: deletes the fixture board's rows first. Gives fixture players 9001-9003 some points.
"""
import hashlib

BOARD, REGION = "gapsa-board-1", "gaps-a-test"
RULES = "城瘾城市局测试规则：掷骰前进，落点可接任务、占领、付通行费或挑战。"
HASH = hashlib.sha256(RULES.encode("utf-8")).hexdigest()
TABLES = ["city_game_event", "city_game_request", "city_game_quote", "city_game_task_attempt", "city_game_roll",
          "city_game_round", "city_game_participation", "city_game_team", "city_game_service", "city_game_property",
          "city_game_point", "city_game_outing"]
print("SET NAMES utf8mb4;")
for t in TABLES:
    print(f"DELETE FROM {t} WHERE board_id = '{BOARD}';")
print("DELETE FROM city_game_board WHERE board_id = '%s';" % BOARD)
print(f"""INSERT INTO city_game_board(board_id, game_id, region_id, season_id, rules_release_id, release_id, rules_hash, rule_version,
 lifecycle, title, revision, board_version, event_sequence, dice_id, dice_faces, dice_weights, actions_per_round, max_rounds,
 acquire_points, challenge_points, holding_yield, goal_score, holding_limit, protection_seconds, quote_valid_seconds, term_seconds,
 min_members, max_members, rules_id, rules_text, challenge_rule_id, protection_text, safety_statement, valid_from,
 fairness_metric_version, fairness_horizon_seconds, fairness_delta_seconds, map_datum, map_south, map_west, map_north, map_east)
VALUES ('{BOARD}', 'city-game', '{REGION}', 's2026w46', 'rel-gapsa-1', 910001, '{HASH}', 'v1', 'OPEN', '测试城市局', 1, 1, 0,
 'd6', '[1,2,3,4,5,6]', '[1,1,1,1,1,1]', 6, 3, 30, 20, 2, 1, 3, 600, 300, 86400, 1, 4, 'rules-gapsa', '{RULES}',
 'challenge-gapsa', '占领后十分钟内不可被挑战。', '请注意交通安全，不要进入封闭区域。', '2026-11-01 00:00:00',
 'fm-1', 604800, 259200, 'GCJ02', 31.200000, 121.400000, 31.300000, 121.500000);""")
points = [("pt-a", "人民广场", 31.230, 121.470, "POI"), ("pt-b", "咖啡馆", 31.235, 121.475, "MERCHANT"),
          ("pt-c", "书店", 31.240, 121.480, "MERCHANT"), ("pt-d", "面馆", 31.245, 121.485, "MERCHANT"),
          ("pt-e", "花店", 31.250, 121.490, "MERCHANT"), ("pt-f", "江边步道", 31.255, 121.495, "POI")]
print("INSERT INTO city_game_point(board_id, point_id, title, latitude, longitude, domain, collected_at) VALUES " + ", ".join(
    f"('{BOARD}', '{p}', '{t}', {la}, {lo}, '{d}', '2026-11-01 00:00:00')" for p, t, la, lo, d in points) + ";")
roles = ["NEUTRAL", "OPERABLE", "OPERABLE", "OPERABLE", "OPERABLE", "NEUTRAL"]
print("INSERT INTO city_game_property(board_id, property_id, point_id, track_index, role) VALUES " + ", ".join(
    f"('{BOARD}', 'prop-{p[0][-1]}', '{p[0]}', {i}, '{roles[i]}')" for i, p in enumerate(points)) + ";")
print(f"INSERT INTO city_game_service(board_id, service_id, property_id, title, member_points_fee) VALUES "
      f"('{BOARD}', 'svc-b', 'prop-b', '手冲咖啡体验', 10), ('{BOARD}', 'svc-c', 'prop-c', '新书导读', 5);")
print("UPDATE ums_member SET point = 100 WHERE id IN (9001, 9002, 9003) AND phone LIKE '1390000000%';")
