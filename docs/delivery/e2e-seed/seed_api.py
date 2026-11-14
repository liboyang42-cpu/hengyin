#!/usr/bin/env python3
"""E2E TEST seed, API half: run after seed.sql against the be-int TEST backend (port 8120, cy_e2e_beint).

Writes go through the real endpoints (no SQL for these rows):
  1. square: player 9004 acks the active plaza guideline, creates and publishes one MOMENT post;
  2. club 7001 channel: leader 9003 opens the club group chat (POST /api/club/chat) and posts a welcome line,
     member 9009 replies;
  3. IM: 9009 -> 9004 direct message;
  0. topic: club leader 9003 publishes the seeded draft topic 992001 (POST /api/topic/publish-draft);
  4. sign-up: player 9009 signs up for the free activity 992031 with ticket 992041 (zero-yuan registration).
Player 13900000004 is deliberately left NOT signed up so the app can exercise sign-up itself.

Idempotent: every write carries a fixed requestId / clientRequestId, or is skipped when the row already exists.
TEST ONLY: refuses any base URL other than the loopback be-int backend unless E2E_BASE_URL is set to an
instabox TEST host. Login codes are read from TEST Redis (be-int-redis db 9), as the e2e harness does.
"""
import json, os, subprocess, sys, urllib.error, urllib.parse, urllib.request

BASE = os.environ.get("E2E_BASE_URL", "http://127.0.0.1:8120")
DB = os.environ.get("E2E_DB", "cy_e2e_beint")
if DB not in ("cy_e2e_beint", "cy_e2e_r2"):
    sys.exit("refusing non-TEST database: %s" % DB)
if not (BASE.startswith("http://127.0.0.1:8120") or ".instabox.tech" in BASE):
    sys.exit("refusing non-TEST base url: %s" % BASE)
REDIS = ["docker", "exec", "be-int-redis", "redis-cli", "-n", "9"]
ACTIVITY, TICKET, TOPIC, CLUB = 992031, 992041, 992001, 7001


def call(method, path, token=None, form=None, js=None, q=None):
    url = BASE + path + ("?" + urllib.parse.urlencode(q) if q else "")
    data, headers = None, {}
    if token:
        headers["Authorization"] = "Bearer " + token
    if form is not None:
        data, headers["Content-Type"] = urllib.parse.urlencode(form).encode(), "application/x-www-form-urlencoded"
    if js is not None:
        data, headers["Content-Type"] = json.dumps(js).encode(), "application/json"
    try:
        with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers, method=method), timeout=30) as r:
            body = r.read().decode()
    except urllib.error.HTTPError as e:
        body = e.read().decode()
    try:
        return json.loads(body)
    except ValueError:
        return {"code": -1, "raw": body[:300]}


def login(phone):
    """Real /api/sms/send (TEST sender keeps the code in Redis) -> read code -> /api/login/phone."""
    # TEST Redis only: clear the 60s / 10-per-day send limits so reruns keep exercising the real sms/send.
    subprocess.run(REDIS + ["DEL", "sms_limit_phone_" + phone, "sms_send_phone_day_" + phone], capture_output=True)
    sent = call("POST", "/api/sms/send", form={"phone": phone})
    code = subprocess.run(REDIS + ["GET", "sms_%s_code" % phone], capture_output=True, text=True).stdout.strip().strip('"')
    if sent.get("code") != 200 or not code:
        # Fallback used before the TEST sender existed: plant a fixture code.
        code = "123456"
        subprocess.run(REDIS + ["SET", "sms_%s_code" % phone, '"123456"'], capture_output=True)
    r = call("POST", "/api/login/phone", form={"phone": phone, "code": code})
    if r.get("code") != 200:
        sys.exit("login %s failed: %s" % (phone, r))
    return r["token"]


def ok(label, r):
    good = r.get("code") == 200
    print("%-28s %s %s" % (label, "OK " if good else "ERR", json.dumps(r, ensure_ascii=False)[:160]))
    return good


def seed_square(t4):
    mine = call("GET", "/api/v1/community/me/posts", t4)
    for item in (mine.get("data") or {}).get("items") or []:
        post = item.get("post") or {}
        if post.get("title") != "老城三章初体验":
            continue
        if post.get("lifecycle") == "PUBLISHED":
            print("square post                  SKIP already published id=%s" % post.get("id"))
            return
        # A held copy (e.g. posted with a location, which forces STRUCTURED_REVIEW) is withdrawn first.
        ok("square post withdraw held", call("DELETE", "/api/v1/community/posts/%s" % post["id"], t4,
                                             js={"expectedVersion": post.get("version"),
                                                 "requestId": "e2e-seed-square-withdraw-%s" % post["id"]}))
    g = call("GET", "/api/v1/community/guidelines/active", t4)
    gid = (g.get("data") or {}).get("id")
    ok("guideline/active", g)
    ok("guideline/ack", call("POST", "/api/v1/community/guidelines/ack", t4,
                             js={"guidelineVersionId": gid, "scene": "POST_CREATE", "requestId": "e2e-seed-guideline-9004"}))
    # Text only, no location: the TEST moderation provider passes the text; geo or media would be held for
    # manual review by ContentSafetyGateImpl (PRE_REVIEW), which has no reviewer on TEST.
    d = call("POST", "/api/v1/community/posts", t4, js={
        "clientRequestId": "e2e-seed-square-2", "postType": "MOMENT", "title": "老城三章初体验",
        "body": "第一次玩老城三章，骑楼拱廊那站拍照超出片！", "audience": "PUBLIC", "commentPolicy": "EVERYONE",
        "locationPrecision": "NONE", "guidelineVersionId": gid})
    if not ok("square post create", d):
        return
    post = d["data"].get("post") or d["data"]
    ok("square post publish", call("POST", "/api/v1/community/posts/%s/publish" % post["id"], t4,
                                   js={"expectedVersion": post.get("version"), "requestId": "e2e-seed-square-2-publish"}))


def seed_club_channel(t3, t9):
    r = call("POST", "/api/club/chat", t3, js={"id": CLUB})
    if not ok("club/chat (open channel)", r):
        return
    data = r.get("data")
    conv = data.get("conversationId") if isinstance(data, dict) else data
    hist = call("POST", "/api/im/messages", t3, form={"conversation_id": conv})
    if "欢迎来到城瘾跑团频道" in json.dumps(hist, ensure_ascii=False):
        print("club channel messages        SKIP already posted")
        return
    ok("channel msg (leader)", call("POST", "/api/im/send", t3, form={
        "conversation_id": conv, "msg_type": 1, "content": "欢迎来到城瘾跑团频道，周六老城三章见！",
        "client_message_id": "e2e-seed-club-7001-welcome"}))
    ok("channel msg (9009)", call("POST", "/api/im/send", t9, form={
        "conversation_id": conv, "msg_type": 1, "content": "收到，我已经报名了免费体验场。",
        "client_message_id": "e2e-seed-club-7001-reply-9009"}))


def seed_signup(t9):
    ok("consent activity_signup", call("POST", "/api/compliance/consents", t9, js={
        "docType": "activity_host_data_sharing", "scene": "activity_signup", "eventType": "AGREE",
        "requestId": "e2e-seed-consent-9009"}))
    ok("participant", call("POST", "/api/user/address/action", t9, form={
        "fullName": "E2E 玩家二号", "mobilePhone": "13900000009", "isDefault": "1",
        "requestId": "e2e-seed-participant-9009"}))
    body = {"ownerType": 2, "ownerId": ACTIVITY, "ticketId": TICKET, "tickets": [{"ticketId": TICKET, "count": 1}],
            "realName": "E2E 玩家二号", "phone": "13900000009"}
    quote = call("POST", "/api/registration/quote", t9, js=body)
    if not ok("registration/quote", quote):
        return
    body["quoteSign"], body["requestId"] = quote["data"]["quoteSign"], "e2e-seed-reg-9009-992031"
    ok("registration/create (free)", call("POST", "/api/registration/create", t9, js=body))


def sql(query):
    return subprocess.run(["docker", "exec", "-i", "be-int-mysql", "mysql", "-uroot", "-N", DB, "-e", query],
                          capture_output=True, text=True, check=True).stdout.strip()


def publish_topic(t3):
    """Publish the seeded draft through the real author path (compiles the route snapshot play/nodes needs)."""
    if sql("SELECT is_draft FROM cms_topic WHERE id=%d" % TOPIC) != "1":
        print("topic publish                SKIP already published (config v%s)"
              % sql("SELECT published_config_version FROM cms_topic WHERE id=%d" % TOPIC))
        return
    status = call("POST", "/api/publisher/identity/status", t3, js={})
    if not (status.get("data") or {}).get("registered"):
        # Fixture identity for the TEST club leader (same values as scripts/e2e/chain_platform.py).
        ok("publisher/identity", call("POST", "/api/publisher/identity", t3, js={
            "realName": "测试主理人", "idCard": "110101199001010015", "consent": True, "source": "topic_publish"}))
    version = sql("SELECT config_version FROM cms_topic WHERE id=%d" % TOPIC)
    ok("topic/publish-draft", call("POST", "/api/topic/publish-draft", t3, form={
        "id": TOPIC, "publishRequestId": "e2e-seed-publish-%d-v%s-%s" % (TOPIC, version, os.getpid())}))
    # Publishing must not move the topic off SELLING (activity tickets only sell on a SELLING topic).
    print("topic state after publish   ", sql("SELECT CONCAT('lifecycle=',lifecycle,' published_config_version=',"
                                               "published_config_version) FROM cms_topic WHERE id=%d" % TOPIC))


def main():
    t3, t4, t9 = login("13900000003"), login("13900000004"), login("13900000009")
    publish_topic(t3)
    seed_square(t4)
    seed_club_channel(t3, t9)
    ok("im/send 9009->9004", call("POST", "/api/im/send", t9, form={
        "target_member_id": 9004, "msg_type": 1, "content": "周六一起去老城三章吗？",
        "client_message_id": "e2e-seed-dm-9009-9004"}))
    seed_signup(t9)


if __name__ == "__main__":
    main()
