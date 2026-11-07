"""l6-club rig fixtures through real APIs. Usage: fixtures.py <step> ...  (state in state.json)
steps: theme | session [minutes_ahead] | members | teams | shift [min] | prepare | start | finish | refund"""
import sys, time, subprocess
from rig import *
LEAD = 'e2e-openid-9008'; CLUB = 7002
PLAYERS = [('walk-player-1', '周一鸣', '13700000001'), ('walk-player-2', '吴佳怡', '13700000002'),
           ('walk-player-3', '郑浩然', '13700000003'), ('walk-player-4', '王小雨', '13700000004')]
S = load()
step = sys.argv[1]

def theme():
    tok, me = login(LEAD)
    # fixture 9008 owns club 7002 but starts with role=player; the real 成为主理人 API gives it the club role (as the walker's UI flow did)
    print('become-leader', J(cl.http("POST", "/api/club/become-leader", token=tok, json_body={"leaderName": "林小舟", "phone": "13900000008", "experience": "1-3年", "identity": "城市漫步爱好者"}))[:120])
    st = cl.http("POST", "/api/publisher/identity/status", token=tok, json_body={})
    if not (st.get('data') or {}).get('registered'):
        print('identity', J(cl.http("POST", "/api/publisher/identity", token=tok, json_body={"realName": "林小舟", "idCard": idcard('31010419900307003'), "consent": True, "source": "topic_publish"})))
    G = [("门楣上的年份", "外滩源壹号正门上方石刻刻着哪一年？（四位数字）", "1873"),
         ("真光大楼的楼层", "真光大楼地面以上一共有几层？（数字）", "9"),
         ("桥上的铆钉", "乍浦路桥北侧铭牌上的建造年份？（四位数字）", "1927"),
         ("四川路桥的灯", "四川路桥桥头路灯柱有几盏灯头？（数字）", "3")]
    tids = []
    for title, qn, ans in G:
        r = cl.http("POST", "/api/template/publish", token=tok, json_body={"title": title, "description": qn, "validationMethod": 1, "questionName": qn,
            "questionAnswer": ans, "hint1": "看仔细。", "hint2": "再看一遍。", "answerReveal": ans, "feedbackText": "答对了。", "duration": 8,
            "difficulty": "普通", "players": "2-4人", "usageLocation": "户外"})
        d = r.get('data'); tids.append(d if isinstance(d, int) else (d or {}).get('id') if isinstance(d, dict) else None)
        print('template', title, J(r)[:160])
    approve_all()
    STN = [("外滩源壹号", "上海市黄浦区中山东一路33号", 121.48841, 31.24268), ("真光大楼", "上海市黄浦区圆明园路209号", 121.48712, 31.24305),
           ("乍浦路桥", "上海市虹口区乍浦路桥北堍", 121.48770, 31.24470), ("四川路桥", "上海市虹口区四川北路桥南堍", 121.48500, 31.24440)]
    CH = [("第一章 迟到的电报", "1927 年冬，一封电报在外滩源压了一百年。", [0, 1]), ("第二章 苏州河上的信使", "电报的下半截被人带过了苏州河。", [2, 3])]
    now = sh_now().replace(second=0, microsecond=0)
    chapters = []
    for name, story, idx in CH:
        nodes, blocks = [], [{"type": "text", "content": story}]
        for k, si in enumerate(idx):
            n, addr, lng, lat = STN[si]
            nodes.append({"name": n, "address": addr, "longitude": "%.6f" % lng, "latitude": "%.6f" % lat, "nodeTime": 15, "templateId": tids[si], "description": "到达%s附近开始答题。" % n})
            blocks.append({"type": "node", "nodeIndex": k})
        chapters.append({"name": name, "description": story, "schemaVersion": 1, "required": 1, "blocks": blocks, "nodes": nodes})
    dto = {"name": "外滩源 · 迟到百年的电报", "subtitle": "沿苏州河两座桥，替百年前的收报人回电",
           "description": "城市定向：外滩源出发，过乍浦路桥、四川路桥。2 章 4 站，文字题，适合 2–4 人一队。",
           "startDate": fmt(now), "endDate": fmt(now + datetime.timedelta(days=60)), "saleEndAt": fmt(now + datetime.timedelta(days=55)),
           "categoryIds": "1", "productType": 1, "publishMode": "normal", "clubId": CLUB, "imgUrl": "", "teamMode": 1, "teamMaxMembers": 4,
           "chapters": chapters,
           "tickets": [{"name": "社员免费场", "price": 0, "totalStock": 20, "mode": 1, "teamSize": 4,
                        "meetingPoint": "外滩源壹号 正门台阶", "meetingPointAddress": "上海市黄浦区中山东一路33号",
                        "gatherLng": 121.48841, "gatherLat": 31.24268, "startTime": fmt(now), "endTime": fmt(now + datetime.timedelta(days=60)),
                        "description": "领队全程带队。", "refundSupported": True}]}
    r = cl.http("POST", "/api/topic/v2/create", token=tok, json_body=dto)
    print('topic', J(r))
    d = r.get('data'); topic = d if isinstance(d, int) else (d or {}).get('id') or (d or {}).get('topicId')
    approve_all()
    S['topic'] = topic; save(S)
    print('topic row', cl.q("SELECT id,status,user_status,club_id,product_type FROM cms_topic WHERE id=%s", (topic,)))

def session(minutes=40):
    tok, _ = login(LEAD)
    at = sh_now() + datetime.timedelta(minutes=int(minutes))
    body = {"clubId": CLUB, "topicId": S['topic'], "sessionDate": at.strftime('%Y-%m-%d') + "T00:00:00+08:00", "sessionTime": at.strftime('%H:%M')}
    r = cl.http("POST", "/api/club/open-session", token=tok, json_body=body)
    print('open', J(r))
    approve_all()
    S['act'] = (r.get('data') or {}).get('activityId'); save(S)
    print(cl.q("SELECT id,start_date,end_date,latitude,longitude,status,publish_status FROM cms_activity WHERE id=%s", (S['act'],)))

def members():
    act = S['act']
    for p, nm, ph in PLAYERS:
        tok, me = login(p)
        cl.http("POST", "/api/club/join", token=tok, json_body={"id": CLUB, "joinMessage": ""})
        cl.http("POST", "/api/compliance/consents", token=tok, json_body={"docType": "activity_host_data_sharing", "scene": "activity_signup", "eventType": "AGREE", "requestId": "l6-consent-%s-%d" % (p, act)})
        t = cl.q("SELECT id FROM oms_ticket WHERE owner_type=2 AND owner_id=%s AND del_flag=0 ORDER BY id", (act,))[0][0]
        body = {"ownerType": 2, "ownerId": act, "ticketId": t, "tickets": [{"ticketId": t, "count": 1}], "realName": nm, "phone": ph}
        qq = cl.http("POST", "/api/registration/quote", token=tok, json_body=body)
        if not qq.get('data'): print(p, 'QUOTE', J(qq)); continue
        body["quoteSign"] = qq["data"]["quoteSign"]; body["requestId"] = "l6-reg-%s-%d" % (p, act)
        c = cl.http("POST", "/api/registration/create", token=tok, json_body=body)
        print(p, me.get('id'), 'create', J(c)[:200])
        S.setdefault('mid', {})[p] = me.get('id')
        out = subprocess.run([RIG + '/venv/bin/python', RIGDIR + '/pay.py', '--member', str(me['id'])], capture_output=True, text=True)
        print('  pay', (out.stdout + out.stderr).strip()[:200])
    save(S)
    print(cl.q("SELECT member_id,payment_status,registration_status,real_name FROM cms_registration WHERE owner_type=2 AND owner_id=%s", (act,)))

def teams():
    act = S['act']
    for g in (PLAYERS[:2], PLAYERS[2:]):
        tok, _ = login(g[0][0])
        r = cl.http("POST", "/api/team/create", token=tok, json_body={"ownerType": 2, "ownerId": act, "maxMembers": 4, "title": "%s队" % g[0][1][0]})
        team = (r.get('data') or {}).get('team') or {}
        print('team', J(r)[:160])
        for p, _, _ in g[1:]:
            t2, _ = login(p)
            print('  join', J(cl.http("POST", "/api/team/join", token=t2, json_body={"inviteCode": team.get('inviteCode')}))[:120])

def shift(m=-1):
    out = subprocess.run([RIG + '/venv/bin/python', RIGDIR + '/seed-walk.py', 'shift-session', str(S['act']), '--start-in-min', str(m)], capture_output=True, text=True)
    print(out.stdout, out.stderr[-500:])

def cmd(action, payload=None):
    tok, _ = login(LEAD)
    v = cl.http("GET", "/api/game/session/view?activityId=%s&perspective=CLUB" % S['act'], token=tok)
    rev = (v.get('data') or {}).get('revision') or 0
    body = {"activityId": S['act'], "perspective": "CLUB", "action": action, "requestId": "l6-%s-%d" % (action, int(time.time() * 1000)),
            "expectedRevision": rev, "payload": payload or {}}
    r = cl.http("POST", "/api/game/session/command", token=tok, json_body=body)
    print(action, J(r))

if step == 'theme': theme()
elif step == 'session': session(*sys.argv[2:])
elif step == 'members': members()
elif step == 'teams': teams()
elif step == 'shift': shift(*sys.argv[2:])
elif step == 'cmd': cmd(sys.argv[2], json.loads(sys.argv[3]) if len(sys.argv) > 3 else None)
elif step == 'view':
    tok, _ = login(sys.argv[2] if len(sys.argv) > 2 else LEAD)
    print(json.dumps(cl.http("GET", "/api/game/session/view?activityId=%s&perspective=CLUB" % S['act'], token=tok), ensure_ascii=False, indent=1, default=str))
elif step == 'state': print(S)
