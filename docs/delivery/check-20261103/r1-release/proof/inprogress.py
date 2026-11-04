"""Fixture for the old-client resume step: fictional member 9004 joins club 7002 (direct fixture row, logged),
buys today's session 9810 over real HTTP (consent/quote/create), pays via the rig payment seam (real callback),
and checks in at ONE of the four stops (real /api/play/checkin) so the game is left in progress."""
import json, os, subprocess, sys
sys.path.insert(0, os.path.expanduser("~/wt/cand/scripts/e2e"))
for k, v in {"CHENGYIN_E2E_BASE": "http://127.0.0.1:8099", "CHENGYIN_E2E_DB_HOST": "127.0.0.1", "CHENGYIN_E2E_DB_PORT": "3316",
             "CHENGYIN_E2E_DB_USER": "root", "CHENGYIN_E2E_DB_PASSWORD": "", "CHENGYIN_E2E_DB_SCHEMA": "cy_e2e_51"}.items():
    os.environ.setdefault(k, v)
for k in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"): os.environ.pop(k, None)
from chain_lib import http, q
import pymysql
def execute(sql):
    c=pymysql.connect(host='127.0.0.1',port=3316,user='root',password='',database='cy_e2e_51',autocommit=True); n=c.cursor().execute(sql); c.close(); return n
tok = http("POST", "/api/login/code", form={"code": "rig-e2e-openid-9004"}) if False else None
import urllib.request, urllib.parse
def login(openid):
    req = urllib.request.Request("http://127.0.0.1:8097/__api/api/login/code", data=urllib.parse.urlencode({"code": "rig-" + openid}).encode())
    return json.loads(urllib.request.urlopen(req).read())["token"]
print("fixture club_member:", execute("INSERT IGNORE INTO club_member(club_id,member_id,role,status,join_time) VALUES (7002,9004,0,1,NOW())"))
ptok = login("e2e-openid-9004")
import io, contextlib, time
with contextlib.redirect_stdout(io.StringIO()):
    import chain_player as cp
from chain_lib import app_token
tid = 990014
etok, _ = app_token(cp.EXEC_CLUB_PHONE)
today, st = q("SELECT DATE_FORMAT(DATE_ADD(NOW(), INTERVAL 8 HOUR) + INTERVAL 4 MINUTE, '%%Y-%%m-%%d'), DATE_FORMAT(DATE_ADD(NOW(), INTERVAL 8 HOUR) + INTERVAL 4 MINUTE, '%%H:%%i')")[0]
r = http("POST", "/api/club/open-session", token=etok, json_body={"clubId": cp.EXEC_CLUB_ID, "topicId": tid, "sessionDate": today, "sessionTime": st})
print("open-session", json.dumps(r, ensure_ascii=False)[:200])
act = r["data"]["activityId"]
print("consent", http("POST", "/api/compliance/consents", token=ptok, json_body={"docType": "activity_host_data_sharing", "scene": "activity_signup", "eventType": "AGREE", "requestId": "r1-consent-9004"}).get("msg"))
ticket = q("SELECT id FROM oms_ticket WHERE owner_type=2 AND owner_id=%s ORDER BY id LIMIT 1", (act,))[0][0]
body = {"ownerType": 2, "ownerId": act, "ticketId": ticket, "tickets": [{"ticketId": ticket, "count": 1}], "realName": "测试玩家四号", "phone": "13800004004"}
quote = http("POST", "/api/registration/quote", token=ptok, json_body=body); print("quote", json.dumps(quote, ensure_ascii=False)[:200])
body["quoteSign"] = quote["data"]["quoteSign"]; body["requestId"] = "r1-reg-9004-%d" % act
created = http("POST", "/api/registration/create", token=ptok, json_body=body); print("create", json.dumps(created, ensure_ascii=False)[:200])
reg_no = created["data"]["registrationNo"]
p = subprocess.run([os.path.expanduser("~/walk-rig/venv/bin/python"), os.path.expanduser("~/wt/rig/docs/delivery/cloud-walk-20261101/rig/pay.py"), "--reg-no", reg_no, "--json"],
                   capture_output=True, text=True, env=dict(os.environ, RIG_HOME=os.path.expanduser("~/walk-rig"), CAND=os.path.expanduser("~/wt/cand")))
print("pay", p.stdout[-300:], p.stderr[-300:])
for _ in range(60):
    if q("SELECT DATE_ADD(NOW(), INTERVAL 8 HOUR) >= start_date FROM cms_activity WHERE id=%s", (act,))[0][0]: break
    time.sleep(5)
print("session started")
nodes = q("SELECT id,chapter_id,checkin_code FROM cms_topic_node WHERE topic_id=%s AND merchant_member_id IS NOT NULL ORDER BY chapter_id", (tid,))
nid, ch, code = nodes[0]
print("checkin ch%s" % ch, json.dumps(http("POST", "/api/play/checkin", token=ptok, form={"activityId": act, "code": code, "topicId": tid}), ensure_ascii=False)[:200])
print("readback reg", q("SELECT id,registration_no,payment_status,payable_amount FROM cms_registration WHERE registration_no=%s", (reg_no,)))
print("readback progress", q("SELECT node_id,checkin_time FROM player_node_progress WHERE member_id=9004 AND activity_id=%s AND del_flag=0", (act,)))
