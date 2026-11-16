#!/usr/bin/env python3
"""Idempotent reset/seed of the TEST data the 18 acceptance flows need (TEST DB through the VPS only).

run_all.py calls setup() before the first flow and release_for_f18() before F18. Every step can run again:
it first undoes what an earlier run (or an api-smoke pass) left behind, then writes fresh rows. Nothing here
touches production: the base URL must be the TEST host (harness.py refuses anything else) and every SQL batch
starts with a guard that aborts unless the database is a cy_e2e_* TEST schema.

How SQL and Redis are reached (no secrets are needed or written):
  * on the `work` VPS (the default when `docker` and the be-int containers exist): `docker exec -i be-int-mysql mysql`
    over the container socket, `docker exec be-int-redis redis-cli -n 9`;
  * from the Mac: the same commands wrapped in `instabox exec work --stdin -- ...` (E2E_FIXTURE_REMOTE=1).
  * E2E_DB overrides the schema (default cy_e2e_r2).

What it sets up for each run (exported as E2E_* variables for the flows, and written to fixtures.json):
  * a fresh live QA activity on topic 992001 (club 7001, starts now-15 min) with a free and a paid ticket, so every run
    gets a new play session (E2E_LIVE_ACTIVITY / E2E_LIVE_FREE_TICKET / E2E_LIVE_PAID_TICKET);
  * a fresh free registration and a fresh paid (pending payment) registration for 9001 on it (E2E_REG_9001_FREE,
    E2E_REG_9001_PAID), and a free registration for 9004 for the self check-in (E2E_REG_9004_LIVE);
  * a node check-in code on node 992021 (E2E_NODE_CHECKIN_CODE);
  * topic 992001 back on the shelf; no im_block rows between the fixture players 9001-9009;
  * room in club 9003's activity quota and merchant 9002's coupon quota (only rows created by test runners are
    retired: 'QA 验收活动%', 'E2E API smoke check', 'QA 验收券%', and smoke coupon 970003 that seed_4xx re-creates);
  * a granted, unused coupon for 9004 on merchant 9002's coupon 970002 (E2E_COUPON_HISTORY_ID);
  * a pending (status 0) refund_application on merchant 9002's activity 992035 (E2E_PENDING_REFUND_ID);
  * an APPROVED cover media asset of 9003 (E2E_COVER_ASSET_ID); QA topics of 9003 taken off the shelf;
  * 9006 out of club 7001 so F10 can join again;
  * a City Monopoly LIVE board 'qa-monopoly-1' (currency POINTS = 积分) with one district and three POIs, a
    defended territory of 9001 past its protection for the challenge, and the claim/visitor account gates met by
    9004/9009 (E2E_MONOPOLY_POI, E2E_MONOPOLY_BBOX, E2E_MONOPOLY_DEFENDED);
  * TEST payment: TEST has no pay sandbox (no WeChat Pay merchant config, and the only stub is an exploded-jar hack),
    so the paid path is marked TEST-SKIP with that reason (E2E_PAY_SKIP).
"""
import json, os, random, re, shlex, shutil, subprocess, sys, time, uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import harness  # noqa: E402  (enforces the TEST base URL)
import requests  # noqa: E402

DB = os.environ.get("E2E_DB", "cy_e2e_r2")
if not re.match(r"^cy_e2e_[a-z0-9_]+$", DB):
    sys.exit("refusing non-TEST database: %s" % DB)

FIXTURE_PHONES = ["1390000000%d" % i for i in range(1, 10)]
TOPIC, CLUB, CLUB_LEADER, MERCHANT = 992001, 7001, 9003, 9002
SEED_REFUND, NODE1 = 992657, 992021
QA_COUPON, COUPON_PUBLISHER = 9940601, 9005  # QA store coupon of 9002 (scope_type 1, store 8001) used for the grant
LIVE_NAME = "QA 验收直播场"
BOARD = "qa-monopoly-1"
POIS = [  # QA POIs around the TEST play area (Guangzhou old town, GCJ-02)
    (994501, "QA 大富翁·钟楼角", 23.129112, 113.264385),
    (994502, "QA 大富翁·骑楼口", 23.129600, 113.265100),
    (994503, "QA 大富翁·江边仓", 23.128500, 113.263600),
]
DEFENDED = "00000000-0000-4000-8000-00000qa99452"  # fixed id of the territory 9001 defends on POI 994502
BBOX = "23.120,113.255,23.140,113.275"


def _remote():
    if os.environ.get("E2E_FIXTURE_REMOTE") == "1":
        return True
    if os.environ.get("E2E_FIXTURE_REMOTE") == "0":
        return False
    return not shutil.which("docker")


def _wrap(cmd, stdin=False):
    if _remote():
        return ["instabox", "exec", "work"] + (["--stdin"] if stdin else []) + ["--"] + cmd
    return cmd


def sql(text):
    """Run SQL on the TEST schema; return rows (lists of str). Aborts the run on any error."""
    guard = ("SET NAMES utf8mb4;\nSELECT IF(DATABASE() LIKE 'cy\\_e2e\\_%%','ok',"
             "(SELECT 'wrong database' FROM information_schema.tables)) INTO @guard;\n")
    cmd = _wrap(["docker", "exec", "-i", "be-int-mysql", "mysql", "--default-character-set=utf8mb4",
                 "-uroot", "-N", "-B", DB], stdin=True)
    out = subprocess.run(cmd, input=guard + text, capture_output=True, text=True, timeout=55)
    err = "\n".join(l for l in out.stderr.splitlines() if "/data is" not in l and l.strip())
    if out.returncode != 0 or "ERROR" in err:
        raise RuntimeError("fixture SQL failed: %s" % err[:400])
    return [line.split("\t") for line in out.stdout.splitlines() if line.strip()]


def one(text):
    rows = sql(text)
    return rows[-1][0] if rows and rows[-1] else None


def redis(*args):
    cmd = _wrap(["docker", "exec", "be-int-redis", "redis-cli", "-n", "9"] + list(args))
    out = subprocess.run(cmd, capture_output=True, text=True, timeout=55)
    return out.stdout.strip()


def api(member, method, path, form=None, js=None, q=None):
    headers = {"User-Agent": "chengyin-qa-acceptance/r3-fixtures"}
    if member is not None:
        headers["Authorization"] = harness.token(member)
    files = {k: (None, str(v)) for k, v in form.items()} if form is not None else None
    r = requests.request(method, harness.BASE + path, params=q, files=files, json=js, headers=headers,
                         timeout=harness.TIMEOUT)
    try:
        return r.json()
    except ValueError:
        return {"_http": r.status_code, "_raw": r.text[:200]}


def _data(j):
    return j.get("data") if isinstance(j, dict) else None


LOG = []


def note(msg):
    LOG.append(msg)
    print("[fixtures] " + msg, flush=True)


# ------------------------------------------------------------------ steps


def reset_rate_limits():
    for ph in FIXTURE_PHONES:
        redis("DEL", "sms_send_phone_day_%s" % ph)


def restore_topic_and_blocks():
    prev = one("SELECT user_status FROM cms_topic WHERE id=%d;" % TOPIC)
    sql("UPDATE cms_topic SET user_status=1 WHERE id=%d AND user_status<>1;" % TOPIC)
    n = one("SELECT COUNT(*) FROM im_block WHERE member_id BETWEEN 9001 AND 9009 AND blocked_member_id BETWEEN 9001 AND 9009;")
    sql("DELETE FROM im_block WHERE member_id BETWEEN 9001 AND 9009 AND blocked_member_id BETWEEN 9001 AND 9009;")
    note("topic %d user_status %s -> 1; im_block rows between fixture players removed: %s" % (TOPIC, prev, n))


_QUOTA = ("SELECT COUNT(*) FROM cms_activity a WHERE member_id=%d AND del_flag=0 AND cancel_time IS NULL "
          "AND (end_date IS NULL OR end_date>NOW()) AND NOT (topic_id IS NOT NULL AND topic_id>0 AND "
          "((club_id IS NOT NULL AND club_id>0) OR EXISTS (SELECT 1 FROM cms_topic t WHERE t.id=a.topic_id AND t.club_id>0)));")


def activity_quota():
    sql("UPDATE cms_activity a SET cancel_time=NOW(), update_time=NOW() WHERE member_id=%d AND del_flag=0 "
        "AND cancel_time IS NULL AND (name LIKE 'QA 验收活动%%' OR name='E2E API smoke check');" % CLUB_LEADER)
    used = int(one(_QUOTA % CLUB_LEADER))
    cap = int(one("SELECT max_events FROM user_permission WHERE role='club';"))
    if used >= cap:
        raise RuntimeError("club %d activity quota still full (%d/%d) after retiring test-runner rows" % (CLUB_LEADER, used, cap))
    note("club 9003 activity quota %d/%d" % (used, cap))


def coupon_quota():
    """F14 publishes as merchant 9005 (store 8002); retire coupons earlier QA runs left active (F14 also stops its own)."""
    sql("UPDATE sms_coupon SET status=3, update_time=NOW() WHERE member_id IN (9002,9005) AND status IN (1,5) AND del_flag=0 "
        "AND name LIKE 'QA 验收券 %%';")
    used = int(one("SELECT COUNT(*) FROM sms_coupon WHERE member_id=%d AND del_flag=0 AND status=1;" % COUPON_PUBLISHER))
    cap = int(one("SELECT max_coupons FROM user_permission WHERE role='merchant';"))
    if used >= cap:
        raise RuntimeError("merchant %d coupon quota full (%d/%d)" % (COUPON_PUBLISHER, used, cap))
    note("merchant %d coupon quota %d/%d" % (COUPON_PUBLISHER, used, cap))


def granted_coupon():
    # Merchant redemption needs a store coupon (scope_type 1) of the verifying store; seed coupon 970002 is scope 0.
    sql("INSERT INTO sms_coupon(id,name,coupon_type,platform,amount,min_point,start_time,end_time,use_type,publish_count,use_count,"
        "receive_count,code,scope_type,member_id,merchant_id,status,del_flag,description,create_by,create_time,update_time,per_member_limit) "
        "VALUES (%d,'QA 核销券（验收夹具）',0,0,5.00,0.00,DATE_SUB(NOW(),INTERVAL 1 DAY),DATE_ADD(NOW(),INTERVAL 60 DAY),0,100000,0,0,"
        "'QA9940601',1,9002,8001,1,0,'acceptance fixture','qa-acceptance',NOW(),NOW(),100000) ON DUPLICATE KEY UPDATE status=1,"
        "del_flag=0,scope_type=1,merchant_id=8001,member_id=9002,end_time=DATE_ADD(NOW(),INTERVAL 60 DAY),update_time=NOW();" % QA_COUPON)
    hid = one(
        "INSERT INTO sms_coupon_history(coupon_id,member_id,coupon_code,get_type,get_time,use_status,start_time,end_time,"
        "note,del_flag,create_by,create_time) SELECT id,9004,CONCAT('QA',DATE_FORMAT(NOW(),'%%y%%m%%d%%H%%i%%s')),1,NOW(),0,"
        "DATE_SUB(NOW(),INTERVAL 1 MINUTE),DATE_ADD(NOW(),INTERVAL 30 DAY),'qa-acceptance',0,'qa-acceptance',NOW() FROM sms_coupon WHERE id=%d;"
        "SELECT LAST_INSERT_ID();" % QA_COUPON)
    note("granted coupon history %s (9004, QA store coupon %d of 9002)" % (hid, QA_COUPON))
    return hid


def pending_refund():
    rid = one(
        "INSERT INTO refund_application(refund_no,source_type,source_id,owner_type,owner_id,member_id,merchant_id,paid_amount,"
        "refund_amount,status,reason,del_flag,create_time,update_time,idempotency_key) SELECT CONCAT('QARF',DATE_FORMAT(NOW(3),'%%y%%m%%d%%H%%i%%s%%f')),"
        "source_type,source_id,owner_type,owner_id,member_id,merchant_id,paid_amount,refund_amount,0,'QA 验收退款申请',0,NOW(),NOW(),"
        "CONCAT('qa-acc-',UUID()) FROM refund_application WHERE id=%d;SELECT LAST_INSERT_ID();" % SEED_REFUND)
    note("pending refund_application %s (copy of seed %d, status 0)" % (rid, SEED_REFUND))
    return rid


def cover_asset():
    key = "upload/e2e-topic-cover-9003.jpg"
    sql("INSERT INTO app_media_asset(member_id,object_key,media_type,review_state,version,create_time,update_time) "
        "SELECT 9003,'%s','IMAGE','APPROVED',0,NOW(),NOW() FROM DUAL WHERE NOT EXISTS "
        "(SELECT 1 FROM app_media_asset WHERE member_id=9003 AND object_key='%s');" % (key, key))
    aid = one("SELECT id FROM app_media_asset WHERE member_id=9003 AND object_key='%s' AND review_state='APPROVED' ORDER BY id LIMIT 1;" % key)
    # F12 publishes a new topic per run; take earlier QA topics off the shelf so the on-shelf limit has room.
    sql("UPDATE cms_topic SET user_status=0 WHERE member_id=9003 AND name LIKE 'QA 验收主题%%' AND user_status=1;")
    note("APPROVED cover asset %s (9003)" % aid)
    return aid


def club_joiner():
    j = api(9006, "POST", "/api/club/quit", js={"id": CLUB})
    sql("UPDATE club SET member_count=(SELECT COUNT(*) FROM club_member WHERE club_id=club.id AND status=1 AND del_flag=0) WHERE id=%d;" % CLUB)
    note("9006 quit club 7001: code=%s msg=%s" % (j.get("code"), j.get("msg")))


def _cancel_registration(member, reg_id, status, pay, live):
    if live:  # the live QA session has started, so the player may no longer cancel; the topic owner 9003 releases it
        actor, path = CLUB_LEADER, "/api/registration/cancel-by-owner"
    else:
        actor, path = member, ("/api/registration/cancel" if str(pay) in ("0", "1") else "/api/registration/cancel-refund")
    j = api(actor, "POST", path, form={"id": reg_id})
    return "%s %s by %s -> %s %s" % (member, reg_id, actor, j.get("code"), (j.get("msg") or "")[:30])


def release_registrations(members=(9001, 9004), only_qa=True):
    """Cancel the open registrations an earlier run created (QA live activities, and F04's qa* requests on 992031)."""
    rows = sql(
        "SELECT r.member_id, r.id, r.registration_status, r.payment_status, IF(a.name LIKE '%s%%',1,0) FROM cms_registration r "
        "LEFT JOIN cms_activity a ON a.id=r.owner_id AND r.owner_type=2 "
        "WHERE r.member_id IN (%s) AND r.registration_status IN (0,1,2) AND r.payment_status IN (0,1,2) "
        "AND (r.verification_status IS NULL OR r.verification_status=0) AND (a.name LIKE '%s%%' %s);"
        % (LIVE_NAME, ",".join(map(str, members)), LIVE_NAME, "OR r.request_id LIKE 'qa%'" if only_qa else "OR 1=1"))
    out = [_cancel_registration(int(m), rid, s, p, live == "1") for m, rid, s, p, live in rows]
    if out:
        note("cancelled earlier QA registrations: " + "; ".join(out))


def live_activity():
    """A fresh live activity on topic 992001 for this run; earlier QA live activities are retired."""
    sql("UPDATE cms_activity SET cancel_time=NOW(), end_date=LEAST(end_date,NOW()), update_time=NOW() "
        "WHERE name LIKE '%s%%' AND cancel_time IS NULL;" % LIVE_NAME)
    aid = int(one("SELECT GREATEST(COALESCE(MAX(id),9940000),9940000)+1 FROM cms_activity WHERE id BETWEEN 9940001 AND 9949999;"))
    sql("INSERT INTO cms_activity (id,member_id,name,description,address_name,longitude,latitude,address,status,publish_status,"
        "del_flag,start_date,end_date,topic_id,check_status,is_settlement,create_by,create_time,club_id,content_safety_hold) "
        "SELECT %d,member_id,'%s %d',description,address_name,longitude,latitude,address,1,1,0,DATE_SUB(NOW(),INTERVAL 15 MINUTE),"
        "DATE_ADD(NOW(),INTERVAL 4 HOUR),topic_id,1,0,'qa-acceptance',NOW(),club_id,0 FROM cms_activity WHERE id=992031;"
        % (aid, LIVE_NAME, aid))
    tickets = {"free": aid, "paid": aid + 5000}  # ids in the QA range 9940001+, never an auto id near other seeds
    for kind, price in (("free", "0.00"), ("paid", "0.01")):
        one(
            "INSERT INTO oms_ticket (id,member_id,owner_type,owner_id,name,price,start_time,end_time,sale_start_time,sale_end_time,"
            "total_inventory,remaining_inventory,mode,team_size,min_people,max_people,signup_deadline,refund_rule,refund_supported,"
            "sync_with_theme,need_audit,del_flag,create_by,create_time) VALUES (%d,9003,2,%d,'%s',%s,DATE_ADD(NOW(),INTERVAL 30 MINUTE),"
            "DATE_ADD(NOW(),INTERVAL 4 HOUR),DATE_SUB(NOW(),INTERVAL 1 HOUR),DATE_ADD(NOW(),INTERVAL 25 MINUTE),50,50,1,1,1,1,"
            "DATE_ADD(NOW(),INTERVAL 25 MINUTE),'开场前可免费取消',1,0,0,0,'qa-acceptance',NOW());"
            % (tickets[kind], aid, "QA 免费票" if kind == "free" else "QA 付费票", price))
    note("live activity %d (now-15min .. now+4h), tickets free %s / paid %s" % (aid, tickets["free"], tickets["paid"]))
    return aid, tickets


def register(member, activity, ticket, name, paid=False):
    api(member, "POST", "/api/compliance/consents",
        js={"docType": "activity_host_data_sharing", "scene": "activity_signup", "eventType": "AGREE",
            "requestId": harness.rid("fc")})
    api(member, "POST", "/api/user/address/action",
        form={"fullName": name, "mobilePhone": harness.PHONE[member], "isDefault": 1, "requestId": harness.rid("fa")})
    sel = {"ownerType": 2, "ownerId": activity, "ticketId": int(ticket), "isUsePoint": 0}
    q = api(member, "POST", "/api/registration/quote", js=sel)
    sign = (_data(q) or {}).get("quoteSign")
    if not sign:
        raise RuntimeError("fixture quote %s/%s failed: %s" % (member, ticket, q.get("msg")))
    c = api(member, "POST", "/api/registration/create",
            js=dict(sel, realName=name, phone=harness.PHONE[member], quoteSign=sign, payChannel="APP",
                    requestId=harness.rid("fx")))
    reg = (_data(c) or {}).get("registrationId")
    if not reg and paid and "支付服务暂不可用" in (c.get("msg") or ""):
        note("paid registration for %s not possible on TEST: create answers code %s '%s'" % (member, c.get("code"), c.get("msg")))
        return None
    if not reg:
        raise RuntimeError("fixture create %s/%s failed: %s" % (member, ticket, c.get("msg")))
    note("registration %s for %s on %s (%s)" % (reg, member, activity, "paid, pending payment" if paid else "free"))
    return reg


SEED_TOPIC_MEDIA = {  # seed.sql values; an earlier wave-6 attempt to republish through publish-draft rewrote them
    "img_url": "https://www.chengyinhub.com/prod-api/profile/covers/topic-templates/shanghai-solo/old-movie.jpg",
    "img_arr": "https://www.chengyinhub.com/prod-api/profile/covers/topic-templates/shanghai-solo/old-movie.jpg,"
               "https://www.chengyinhub.com/prod-api/profile/covers/topic-templates/shanghai-solo/anomaly-coordinate.jpg",
    "finish_medal_img": "https://www.chengyinhub.com/prod-api/profile/home-banners/memoji/lead.png",
}


def frozen_answer():
    """B5 reaches a new run only through the published snapshot: TopicRoutePublicationCompiler freezes
    BasicTextAnswer.expectedAnswer(question_answer, advanced_config_json) into nodeAnswers.<node>.questionAnswer. Topic 992001
    was published before the fix, and publish-draft can no longer recompile it on TEST (a SELLING commercial topic is refused:
    "商业主题只有 DRAFT/PENDING_REVIEW 可提交立项审核"; its seed media are bare URLs, refused by P-17). So write exactly the value
    the compiler now produces (template 992101 qa.answerText) into the published snapshot; new runs copy it."""
    for col, val in SEED_TOPIC_MEDIA.items():
        sql("UPDATE cms_topic SET %s='%s' WHERE id=%d AND %s LIKE 'media-asset:%%';" % (col, val, TOPIC, col))
    sql("UPDATE cms_topic SET is_draft=0 WHERE id=%d AND is_draft=1 AND published_route_graph_json IS NOT NULL;" % TOPIC)
    path = "'$.nodeAnswers.\"%d\".questionAnswer'" % NODE1
    expected = one("SELECT JSON_UNQUOTE(JSON_EXTRACT(advanced_config_json,'$.qa.answerText')) FROM cms_member_template "
                   "WHERE id=(SELECT template_id FROM cms_topic_node WHERE id=%d);" % NODE1)
    sql("UPDATE cms_topic SET published_route_graph_json=JSON_SET(published_route_graph_json,%s,'%s') WHERE id=%d "
        "AND JSON_EXTRACT(published_route_graph_json,%s) IS NULL;" % (path, expected, TOPIC, path))
    got = one("SELECT JSON_UNQUOTE(JSON_EXTRACT(published_route_graph_json,%s)) FROM cms_topic WHERE id=%d;" % (path, TOPIC))
    note("topic %d published answer for node %d: %s" % (TOPIC, NODE1, "present" if got == expected else "MISSING"))


def node_checkin_code():
    code = "QA%d" % NODE1
    sql("UPDATE cms_topic_node SET checkin_code='%s' WHERE id=%d AND (checkin_code IS NULL OR checkin_code<>'%s');" % (code, NODE1, code))
    return code


def geohash7(lat, lng):
    chars, bits, out = "0123456789bcdefghjkmnpqrstuvwxyz", [16, 8, 4, 2, 1], ""
    lo, hi, even, bit, ch = [-90.0, -180.0], [90.0, 180.0], True, 0, 0
    while len(out) < 7:
        i, v = (1, lng) if even else (0, lat)
        mid = (lo[i] + hi[i]) / 2
        if v > mid:
            ch |= bits[bit]; lo[i] = mid
        else:
            hi[i] = mid
        even = not even
        if bit < 4:
            bit += 1
        else:
            out += chars[ch]; bit, ch = 0, 0
    return out


def monopoly():
    """QA City Monopoly LIVE board (one currency: 积分 / POINTS), its POIs, and the account gates the flow needs."""
    overrides = json.dumps({
        # A harness cannot produce an Apple App Attest assertion; this TEST board alone skips that device check.
        "city.monopoly.claim.require_app_attest": "false",
        # Deterministic runs at any hour; the 06:00-23:00 window is unit-tested in CityMonopolyRulesTest.
        "city.monopoly.claim.hours.open": "00:00", "city.monopoly.claim.hours.close": "23:59",
    })
    s = ["DELETE FROM city_yield_event WHERE territory_id IN (SELECT territory_id FROM roam_territory WHERE board_id='%s');" % BOARD,
         "DELETE FROM city_challenge WHERE territory_id IN (SELECT territory_id FROM roam_territory WHERE board_id='%s');" % BOARD,
         "DELETE FROM city_claim_evidence WHERE target_key LIKE 'board:%s:%%' OR target_key IN (%s);"
         % (BOARD, ",".join("'poi:%d'" % p[0] for p in POIS)),
         "DELETE FROM roam_territory WHERE board_id='%s' OR poi_id IN (%s);" % (BOARD, ",".join(str(p[0]) for p in POIS)),
    ]
    sql("\n".join(s))
    if one("SELECT COUNT(*) FROM city_game_board WHERE board_id='%s';" % BOARD) != "1":
        sql("CREATE TEMPORARY TABLE qa_b SELECT * FROM city_game_board WHERE board_id='gapsa-board-1';"
            "UPDATE qa_b SET board_id='%s', release_id=994901, rules_release_id='rel-qa-monopoly-1';"
            "INSERT INTO city_game_board SELECT * FROM qa_b;" % BOARD)
    sql("UPDATE city_game_board SET region_id='qa-acceptance', title='QA 城市大富翁', lifecycle='OPEN', mode='LIVE', currency='POINTS', "
        "config_json='%s', map_south=23.12, map_west=113.255, map_north=23.14, map_east=113.275, update_time=NOW(3) WHERE board_id='%s';"
        % (overrides, BOARD))
    sql("INSERT INTO city_district(board_id,district_id,name,color_token,ownable_count,safety_blocked) VALUES "
        "('%s','qa-d1','QA 老城街区','district.c1',%d,0) ON DUPLICATE KEY UPDATE ownable_count=VALUES(ownable_count),safety_blocked=0;"
        % (BOARD, len(POIS)))
    for pid, name, lat, lng in POIS:
        sql("INSERT INTO roam_poi(id,name,type,lat,lng,radius_m,address,description,xp,city_code,status,audit_status,create_time) "
            "VALUES (%d,'%s',1,%f,%f,50,'QA 验收地址','QA City Monopoly fixture',0,'440100',1,1,NOW()) "
            "ON DUPLICATE KEY UPDATE name=VALUES(name),lat=VALUES(lat),lng=VALUES(lng),status=1,merchant_id=NULL;"
            % (pid, name, lat, lng))
        sql("INSERT INTO city_game_point(board_id,point_id,title,latitude,longitude,domain,collected_at,availability,del_flag,place_id,"
            "district_id,tier,merchant_id) VALUES ('%s','qa-pt-%d','%s',%f,%f,'POI',NOW(3),'AVAILABLE',0,%d,'qa-d1',1,NULL) "
            "ON DUPLICATE KEY UPDATE latitude=VALUES(latitude),longitude=VALUES(longitude),place_id=VALUES(place_id),del_flag=0,"
            "availability='AVAILABLE',district_id='qa-d1',tier=1,merchant_id=NULL;" % (BOARD, pid, name, lat, lng, pid))
    # Defended territory for the challenge: held by 9001, protection already over (a fresh claim is protected 24 h).
    season = one("SELECT season_id FROM city_season WHERE status='OPEN' AND starts_at<=NOW() AND ends_at>NOW() ORDER BY starts_at DESC LIMIT 1;") or "s2026w46"
    pid, name, lat, lng = POIS[1]
    sql("INSERT INTO roam_territory(territory_id,geohash7,poi_id,board_id,season_id,point_id,title,city_code,latitude,longitude,radius_m,"
        "owner_member_id,claim_photo_url,claim_request_id,checkin_toll_points,owner_revenue_points,revenue_mode,checkin_count,"
        "revenue_total_points,status,version,claimed_at,protected_until,rules_kind,tier,upgrade_level,invested,district_id,"
        "last_owner_visit_at,decay_state) VALUES ('%s','%s',%d,'%s','%s','qa-pt-%d','%s','440100',%f,%f,50,9001,"
        "'media-asset:qa','QA-DEFENDED',0,0,'POINTS',0,0,'ACTIVE',1,DATE_SUB(NOW(),INTERVAL 2 DAY),DATE_SUB(NOW(),INTERVAL 1 DAY),"
        "'MONOPOLY',1,0,60,'qa-d1',NOW(),'OK');" % (DEFENDED, geohash7(lat, lng), pid, BOARD, season, pid, name, lat, lng))
    # Account gates (§2.2 / abuse.visitor_*): fixture accounts were all created by the seed on 2026-11-13.
    sql("UPDATE ums_member SET create_time='2026-11-01 00:00:00' WHERE id IN (9001,9004,9009) AND create_time>'2026-11-01';"
        "INSERT INTO member_growth(member_id,level_no,exp_value,create_time,update_time) SELECT m.id,3,0,NOW(),NOW() FROM ums_member m "
        "WHERE m.id IN (9004,9009) AND NOT EXISTS (SELECT 1 FROM member_growth g WHERE g.member_id=m.id);"
        "UPDATE member_growth SET level_no=GREATEST(level_no,3) WHERE member_id IN (9004,9009);"
        "UPDATE ums_member SET point=GREATEST(point,600) WHERE id IN (9004,9009);"
        "DELETE FROM city_abuse_signal WHERE member_id IN (9001,9004,9009);")
    have = int(one("SELECT COUNT(*) FROM member_visited_poi WHERE member_id=9009 AND first_visit_time>=DATE_SUB(NOW(),INTERVAL 13 DAY);") or 0)
    for i in range(max(0, 3 - have)):
        sql("INSERT INTO member_visited_poi(member_id,geo_key,first_visit_time) VALUES (9009,'qa-geo-%d-%s',NOW());" % (i, uuid.uuid4().hex[:6]))
    note("monopoly board %s (POINTS) with %d POIs; defended territory %s (9001, season %s)" % (BOARD, len(POIS), DEFENDED, season))


def payment():
    j = api(9001, "POST", "/api/registration/payment-readiness")
    ready = bool((_data(j) or {}).get("ready"))
    if ready:
        note("TEST payment-readiness: ready")
        return None
    why = ("TEST has no pay sandbox: payment-readiness says '%s' (no WeChat Pay merchant config on be-int; the only stub, "
           "scripts/e2e/E2eWechatPayStub.java.stub, is compiled into an exploded jar by hand and is not in the TEST jar)"
           % (_data(j) or {}).get("message"))
    note("paid path TEST-SKIP: " + why)
    return why


def setup():
    """Reset and seed everything; export the ids as E2E_* environment variables. Returns the dict."""
    t0 = time.time()
    env = {}
    reset_rate_limits()
    restore_topic_and_blocks()
    activity_quota()
    coupon_quota()
    env["E2E_COUPON_HISTORY_ID"] = granted_coupon()
    env["E2E_PENDING_REFUND_ID"] = pending_refund()
    env["E2E_COVER_ASSET_ID"] = cover_asset()
    env["E2E_NODE_CHECKIN_CODE"] = node_checkin_code()
    frozen_answer()
    club_joiner()
    release_registrations()
    aid, tickets = live_activity()
    env.update(E2E_LIVE_ACTIVITY=str(aid), E2E_LIVE_FREE_TICKET=str(tickets["free"]), E2E_LIVE_PAID_TICKET=str(tickets["paid"]))
    env["E2E_REG_9001_FREE"] = str(register(9001, aid, tickets["free"], "玩家一号"))
    paid = register(9001, aid, tickets["paid"], "玩家一号", paid=True)
    if paid:
        env["E2E_REG_9001_PAID"] = str(paid)
    env["E2E_REG_9004_LIVE"] = str(register(9004, aid, tickets["free"], "玩家小城"))
    monopoly()
    env.update(E2E_MONOPOLY_POI=str(POIS[0][0]), E2E_MONOPOLY_BBOX=BBOX, E2E_MONOPOLY_DEFENDED=DEFENDED,
               E2E_MONOPOLY_BOARD=BOARD)
    why = payment()
    if why:
        env["E2E_PAY_SKIP"] = why
    env["E2E_PLAY_MEMBER"] = "9001"
    for k, v in env.items():
        if v is not None:
            os.environ[k] = str(v)
    out = dict(at=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), db=DB, seconds=round(time.time() - t0, 1),
               env=env, log=LOG)
    with open(os.path.join(HERE, "fixtures.json"), "w") as fh:
        json.dump(out, fh, ensure_ascii=False, indent=1)
    return out


def release_for_f18():
    """F18 needs 9001 without open orders: cancel this run's pending/unverified fixture registrations of 9001."""
    release_registrations(members=(9001,))


def teardown():
    """After a run: release this run's open QA registrations and retire the live QA activity (the rest stays seeded)."""
    release_registrations(members=(9001, 9004), only_qa=True)
    sql("UPDATE cms_activity SET cancel_time=NOW(), end_date=LEAST(end_date,NOW()), update_time=NOW() "
        "WHERE name LIKE '%s%%' AND cancel_time IS NULL;" % LIVE_NAME)
    note("teardown: QA live activity retired")


if __name__ == "__main__":
    if sys.argv[1:2] == ["teardown"]:
        teardown()
        sys.exit(0)
    res = setup()
    print(json.dumps(res["env"], ensure_ascii=False, indent=1))
