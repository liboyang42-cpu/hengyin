#!/usr/bin/env python3
"""P-22 第二轮证明(只对走查 rig 的一次性库 cy_e2e_*):「累计打卡 N 处」= 核销过的店 + 完成的城市站点,不重复计。

前提:rig 已 up(本分支),chains.py player 已跑完(玩家 9009 在 990014 的四家店各有一条 ACTIVE 核销)。
用法: python3 -I p22_checkin_places_proof.py
夹具(写测试库,如实报告):
  - 990014 节点 71 的 player_node_progress.status 置 1,模拟自由探索店里游戏已答完(商家节点),验证不重复计。
  - 新建一个城市定向主题(product_type=1)+ 一个非商家站点 + 9009 的完成行,验证城市站点计入。
"""
import sys, json, pathlib
ROOT = pathlib.Path(__file__).resolve().parents[5]
sys.path.insert(0, str(ROOT / "scripts" / "e2e"))
from chain_lib import http, q, ex, app_token, step, show, readback  # noqa: E402

PLAYER, PLAYER_PHONE = 9009, "13900000009"
EMPTY_PHONE = "13900000004"   # 9004:本库里没有核销也没有完成站点
CITY_TOPIC, CITY_NODE = 990901, 990902


def places(phone):
    tok, _ = app_token(phone)
    r = http("POST", "/api/play/growth", token=tok)
    show("POST /api/play/growth (%s)" % phone, json.dumps(r.get("data"), ensure_ascii=False))
    return (r.get("data") or {}).get("checkinPlaces")


def segments():
    readback("核销过的店(按章去重)",
             "SELECT count(distinct r.chapter_id) FROM redemption r JOIN cms_registration reg ON reg.id=r.registration_id "
             "WHERE reg.member_id=%s AND r.status='ACTIVE'", (PLAYER,))
    readback("完成的城市站点(非商家节点、非自由探索)",
             "SELECT count(distinct p.node_id) FROM player_node_progress p JOIN cms_topic_node n ON n.id=p.node_id "
             "JOIN cms_topic t ON t.id=p.topic_id WHERE p.member_id=%s AND p.status=1 AND p.del_flag=0 "
             "AND n.merchant_member_id IS NULL AND (t.product_type IS NULL OR t.product_type != 2)", (PLAYER,))


def main():
    ok = True
    step("0. 复位本脚本的夹具(可重跑)")
    ex("DELETE FROM player_node_progress WHERE node_id=%s", (CITY_NODE,))
    ex("UPDATE player_node_progress SET status=0 WHERE member_id=%s AND topic_id=990014 AND node_id=71", (PLAYER,))
    step("1. 四家店已核销,没有完成站点 ⇒ 4")
    segments()
    v = places(PLAYER_PHONE); ok &= v == 4; print("  expect 4 ->", v)

    step("2. 夹具:店里游戏也答完了(商家节点 71 status=1) ⇒ 仍是 4,不重复计")
    ex("UPDATE player_node_progress SET status=1 WHERE member_id=%s AND topic_id=990014 AND node_id=71", (PLAYER,))
    readback("节点进度", "SELECT node_id,status FROM player_node_progress WHERE member_id=%s ORDER BY node_id", (PLAYER,))
    segments()
    v = places(PLAYER_PHONE); ok &= v == 4; print("  expect 4 ->", v)

    step("3. 夹具:一个城市定向主题的非商家站点完成 ⇒ 5")
    ex("DELETE FROM player_node_progress WHERE node_id=%s", (CITY_NODE,))
    ex("DELETE FROM cms_topic_node WHERE id=%s", (CITY_NODE,))
    ex("DELETE FROM cms_topic WHERE id=%s", (CITY_TOPIC,))
    ex("INSERT INTO cms_topic (id, member_id, product_type, name, status, del_flag) VALUES (%s, 9001, 1, 'P-22 证明用城市主题', 1, 0)", (CITY_TOPIC,))
    ex("INSERT INTO cms_topic_node (id, topic_id, name, status) VALUES (%s, %s, 'P-22 证明站点', 1)", (CITY_NODE, CITY_TOPIC))
    ex("INSERT INTO player_node_progress (member_id, activity_id, topic_id, node_id, status, del_flag, create_time) "
       "VALUES (%s, 0, %s, %s, 1, 0, NOW())", (PLAYER, CITY_TOPIC, CITY_NODE))
    segments()
    v = places(PLAYER_PHONE); ok &= v == 5; print("  expect 5 ->", v)

    step("4. 没有任何数据的玩家 ⇒ 0")
    v = places(EMPTY_PHONE); ok &= v == 0; print("  expect 0 ->", v)

    print("\nP22-PROOF:", "PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
