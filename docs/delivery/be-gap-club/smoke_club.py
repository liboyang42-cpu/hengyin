#!/usr/bin/env python3
"""be-gap-club curl check on 8124 / cy_gap_club (a copy of the seeded TEST DB cy_e2e_r2 plus migration 20261118, applied twice).
Every call uses the exact app wire shape of Core/ClubGapClient: POST, JSON body = ClubGapSurface fields, token in Authorization.
The app reads only code / errorCode / msg; the transcript also shows data for review. Tokens are never printed."""
import json, os, subprocess, sys, urllib.error, urllib.request, uuid

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8124"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "curl-club.txt")
REDIS = ["docker", "exec", "be-int-redis", "redis-cli", "-n", "14"]
CLUB, ACTIVITY, TOPIC = 7001, 992031, 992001
lines = []


def call(path, tok=None, js=None):
    h = {"Accept": "application/json", "Content-Type": "application/json"}
    if tok:
        h["Authorization"] = tok
    req = urllib.request.Request(BASE + path, data=json.dumps(js if js is not None else {}).encode(), method="POST", headers=h)
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def show(label, path, who, st, d):
    ok = 200 <= st < 300 and d.get("code") == 200
    data = json.dumps(d.get("data"), ensure_ascii=False)
    line = "%-34s %-40s %-7s HTTP %s code=%s errorCode=%s %s msg=%s data=%s" % (
        label, path, who, st, d.get("code"), d.get("errorCode"), "SUCCEEDED" if ok else "refused", d.get("msg"), data[:260])
    lines.append(line)
    print(line[:300])
    return d


def login(phone):
    subprocess.run(REDIS + ["DEL", "sms_limit_phone_" + phone, "sms_send_phone_day_" + phone], capture_output=True)
    form = urllib.request.Request(BASE + "/api/sms/send", data=("phone=" + phone).encode(), method="POST",
                                  headers={"Content-Type": "application/x-www-form-urlencoded"})
    urllib.request.urlopen(form).read()
    code = subprocess.run(REDIS + ["GET", "sms_%s_code" % phone], capture_output=True, text=True).stdout.strip().strip('"')
    req = urllib.request.Request(BASE + "/api/login/phone", data=("phone=%s&code=%s" % (phone, code)).encode(), method="POST",
                                 headers={"Content-Type": "application/x-www-form-urlencoded"})
    return json.loads(urllib.request.urlopen(req).read())["token"]


owner, member, outsider = login("13900000003"), login("13900000004"), login("13900000001")

# quality evidence: retired (owner 2026-09-10)
show("quality/evidence", "/api/club-compensation/quality/evidence", "owner", *call("/api/club-compensation/quality/evidence", owner,
     {"clubId": CLUB, "topicId": TOPIC, "dimension": "DELIVERY_SAFETY", "evidenceHash": "a" * 64}))
show("quality/evidence (signed out)", "/api/club-compensation/quality/evidence", "-", *call("/api/club-compensation/quality/evidence", None, {}))

# join: 9001 applies to 7001 (becomes a member when the club is open, else pending)
show("join-requests/apply", "/api/club/join-requests/apply", "9001", *call("/api/club/join-requests/apply", outsider, {"id": CLUB, "joinMessage": "想一起跑城市定向"}))
show("join-requests/apply (signed out)", "/api/club/join-requests/apply", "-", *call("/api/club/join-requests/apply", None, {"id": CLUB, "joinMessage": ""}))

# activity (waitlist / occurrence status)
show("activity/detail", "/api/club/activity/detail", "owner", *call("/api/club/activity/detail", owner, {"clubId": CLUB, "activityId": ACTIVITY}))
show("activity/detail (member)", "/api/club/activity/detail", "9004", *call("/api/club/activity/detail", member, {"clubId": CLUB, "activityId": ACTIVITY}))
show("activity/join", "/api/club/activity/join", "9004", *call("/api/club/activity/join", member, {"activityId": ACTIVITY}))
show("activity/cancel", "/api/club/activity/cancel", "9004", *call("/api/club/activity/cancel", member, {"activityId": ACTIVITY}))

# channels
show("channels", "/api/club/channels", "owner", *call("/api/club/channels", owner, {"clubId": CLUB}))
show("channels (non-member)", "/api/club/channels", "9001?", *call("/api/club/channels", login("13900000002"), {"clubId": CLUB}))

# event ops
show("events", "/api/club/events", "owner", *call("/api/club/events", owner, {"clubId": CLUB}))
show("events (member)", "/api/club/events", "9004", *call("/api/club/events", member, {"clubId": CLUB}))
req = "series-" + uuid.uuid4().hex[:12]
save = {"clubId": CLUB, "topicId": TOPIC, "recurrenceType": "ONCE", "startDate": "2026-12-20", "startTime": "19:00", "occurrenceCount": 1,
        "customDates": None, "capacity": None, "defaultLeadMemberId": None, "waitlistEnabled": False, "offerMinutes": 30, "requestId": req}
d = show("events/save", "/api/club/events/save", "owner", *call("/api/club/events/save", owner, save))
show("events/save (same requestId)", "/api/club/events/save", "owner", *call("/api/club/events/save", owner, save))
show("events/save (member)", "/api/club/events/save", "9004", *call("/api/club/events/save", member, dict(save, requestId="series-m-" + req)))
show("events/Checkin", "/api/club/events/Checkin", "owner", *call("/api/club/events/Checkin", owner,
     {"clubId": CLUB, "activityId": ACTIVITY, "memberId": 9004, "arrived": True, "expectedVersion": 0, "reason": "现场已到", "requestId": "chk-" + uuid.uuid4().hex[:12]}))
show("events/Checkin (member)", "/api/club/events/Checkin", "9004", *call("/api/club/events/Checkin", member,
     {"clubId": CLUB, "activityId": ACTIVITY, "memberId": 9009, "arrived": True, "expectedVersion": 0, "reason": "现场已到", "requestId": "chk-" + uuid.uuid4().hex[:12]}))
show("events/proxy-enroll 9001", "/api/club/events/proxy-enroll", "owner", *call("/api/club/events/proxy-enroll", owner, {"clubId": CLUB, "activityId": ACTIVITY, "memberId": 9001}))
show("events/proxy-enroll 9001 (again)", "/api/club/events/proxy-enroll", "owner", *call("/api/club/events/proxy-enroll", owner, {"clubId": CLUB, "activityId": ACTIVITY, "memberId": 9001}))
show("events/proxy-enroll 9004 (registered)", "/api/club/events/proxy-enroll", "owner", *call("/api/club/events/proxy-enroll", owner, {"clubId": CLUB, "activityId": ACTIVITY, "memberId": 9004}))
show("events/proxy-enroll 9002 (not member)", "/api/club/events/proxy-enroll", "owner", *call("/api/club/events/proxy-enroll", owner, {"clubId": CLUB, "activityId": ACTIVITY, "memberId": 9002}))
show("events/proxy-enroll (member)", "/api/club/events/proxy-enroll", "9004", *call("/api/club/events/proxy-enroll", member, {"clubId": CLUB, "activityId": ACTIVITY, "memberId": 9001}))
show("events/proxy-enroll/mine", "/api/club/events/proxy-enroll/mine", "9001", *call("/api/club/events/proxy-enroll/mine", outsider, {}))

# group code
show("group-code", "/api/club/group-code", "owner", *call("/api/club/group-code", owner, {"activityId": ACTIVITY}))
show("group-code (member)", "/api/club/group-code", "9004", *call("/api/club/group-code", member, {"activityId": ACTIVITY}))
d = show("group-code/save", "/api/club/group-code/save", "owner", *call("/api/club/group-code/save", owner, {"activityId": ACTIVITY}))

# notices
show("notices", "/api/club/notices", "owner", *call("/api/club/notices", owner, {"clubId": CLUB}))
show("notices (member)", "/api/club/notices", "9004", *call("/api/club/notices", member, {"clubId": CLUB}))
send = {"clubId": CLUB, "audienceType": "ALL_MEMBERS", "title": "周六集合", "content": "周六晚七点老城门口集合", "requestId": "notice-" + uuid.uuid4().hex[:12]}
show("notices/send", "/api/club/notices/send", "owner", *call("/api/club/notices/send", owner, send))
show("notices/send (same requestId)", "/api/club/notices/send", "owner", *call("/api/club/notices/send", owner, send))
show("notices/send (member)", "/api/club/notices/send", "9004", *call("/api/club/notices/send", member, dict(send, requestId="notice-m-" + uuid.uuid4().hex[:8])))

# workbench and settlement
show("workbench", "/api/club/workbench", "owner", *call("/api/club/workbench", owner, {"clubId": CLUB}))
show("workbench (member)", "/api/club/workbench", "9004", *call("/api/club/workbench", member, {"clubId": CLUB}))
show("settlements/export", "/api/club/settlements/export", "owner", *call("/api/club/settlements/export", owner, {"clubId": CLUB}))
show("settlements/export (member)", "/api/club/settlements/export", "9004", *call("/api/club/settlements/export", member, {"clubId": CLUB}))

open(OUT, "w").write("\n".join(l.replace(owner, "<tok>") for l in lines) + "\n")
