#!/usr/bin/env python3
"""
E2E V2 Idempotent Seed & Verification Runner (TEST ONLY: http://127.0.0.1:8120 + cy_e2e_r2).
Applies API-dependent enrichment (route session, team resource pool, signed external game)
and curls every seeded object across all 9 E2E categories to prove visibility.
"""

import base64
import hashlib
import json
import os
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE_URL = os.environ.get("E2E_BASE_URL", "http://127.0.0.1:8120").rstrip("/")
MYSQL_CONTAINER = os.environ.get("E2E_MYSQL_CONTAINER", "be-int-mysql")
REDIS_CONTAINER = os.environ.get("E2E_REDIS_CONTAINER", "be-int-redis")
DB_NAME = os.environ.get("E2E_DB_NAME", "cy_e2e_r2")
REDIS_DB = os.environ.get("E2E_REDIS_DB", "9")

if DB_NAME not in ("cy_e2e_r2", "cy_e2e_beint"):
    raise SystemExit(f"REFUSING TO RUN: DB_NAME={DB_NAME!r} is not an allowed TEST DB")


def run(cmd, stdin_text=None):
    res = subprocess.run(cmd, input=stdin_text, text=True, capture_output=True)
    if res.returncode != 0:
        raise RuntimeError(f"Command failed ({res.returncode}): {' '.join(cmd)}\n{res.stderr}\n{res.stdout}")
    return res.stdout.strip()


def mysql_exec(sql):
    return run([
        "docker", "exec", "-i", MYSQL_CONTAINER,
        "mysql", "-uroot", "--default-character-set=utf8mb4", "-N", "-B", DB_NAME
    ], stdin_text=sql)


def redis_cli(*args):
    return run(["docker", "exec", REDIS_CONTAINER, "redis-cli", "-n", REDIS_DB, *args])


def http_req(method, path, token=None, json_body=None, form_body=None, raw_body=None, content_type=None):
    url = f"{BASE_URL}{path}"
    data = None
    headers = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if json_body is not None:
        data = json.dumps(json_body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    elif form_body is not None:
        data = urllib.parse.urlencode(form_body).encode("utf-8")
        headers["Content-Type"] = "application/x-www-form-urlencoded; charset=utf-8"
    elif raw_body is not None:
        data = raw_body
        if content_type:
            headers["Content-Type"] = content_type
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            raw = resp.read().decode("utf-8")
            try:
                return resp.status, json.loads(raw)
            except json.JSONDecodeError:
                return resp.status, {"raw": raw}
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        try:
            return exc.code, json.loads(raw)
        except json.JSONDecodeError:
            return exc.code, {"raw": raw}


def login_phone(phone):
    redis_cli(
        "DEL",
        f"sms_limit_phone_{phone}",
        f"sms_send_phone_day_{phone}",
        "sms_send_ip_window_127.0.0.1",
        "sms_verify_ip_window_127.0.0.1",
    )
    st, d = http_req("POST", "/api/sms/send", form_body={"phone": phone})
    if st != 200 or d.get("code") != 200:
        raise RuntimeError(f"sms/send failed for {phone}: HTTP {st} {d}")
    code = redis_cli("GET", f"sms_{phone}_code").strip().strip('"')
    if not code:
        raise RuntimeError(f"No OTP code found in redis db {REDIS_DB} for {phone}")
    st, d = http_req("POST", "/api/login/phone", form_body={"phone": phone, "code": code})
    tok = d.get("token") or (d.get("data") or {}).get("token")
    if st != 200 or not tok:
        raise RuntimeError(f"login/phone failed for {phone}: HTTP {st} {d}")
    return tok


def multipart(parts):
    bnd = uuid.uuid4().hex
    out = b""
    for name, filename, content, mime in parts:
        disp = f'form-data; name="{name}"' + (f'; filename="{filename}"' if filename else "")
        out += f"--{bnd}\r\nContent-Disposition: {disp}\r\n".encode("utf-8")
        if mime:
            out += f"Content-Type: {mime}\r\n".encode("utf-8")
        out += b"\r\n" + content + b"\r\n"
    out += f"--{bnd}--\r\n".encode("utf-8")
    return out, f"multipart/form-data; boundary={bnd}"


def package_sha(files):
    h = hashlib.sha256()
    for path in sorted(files):
        h.update(path.encode("utf-8") + b"\0" + str(len(files[path])).encode("utf-8") + b"\0" + files[path] + b"\0")
    return h.hexdigest()


def ensure_approved_external_game(tok_9004):
    st, cat = http_req("GET", "/api/external-game/catalog")
    items = (cat.get("data") or {}).get("items") or []
    if any(i.get("gameId") == "e2e.city.quiz" for i in items):
        return "already-approved"

    files = {
        "index.html": "<!doctype html><html><body><h1>E2E City Quiz</h1><p>Peoples Square -> Bund</p></body></html>".encode("utf-8"),
        "app.js": b"console.log('e2e.city.quiz ready');",
    }
    manifest = {
        "id": "e2e.city.quiz",
        "name": "E2E 外滩探索小测验",
        "version": "1.0.0",
        "entry": "index.html",
        "permissions": ["storage"],
        "contentRating": "4+",
        "allowedDomains": [],
        "sha256": package_sha(files),
        "signature": "client",
        "author": "E2E",
        "summary": "城市探索外接互动小游戏示例包",
    }
    doc = {"manifest": manifest, "files": {k: base64.b64encode(v).decode("ascii") for k, v in files.items()}}
    body, ctype = multipart([("archive", "e2e-city-quiz.json", json.dumps(doc).encode("utf-8"), "application/json")])
    st, res = http_req("POST", "/api/external-game/submissions?requestId=e2e-v2-city-quiz-1", token=tok_9004, raw_body=body, content_type=ctype)
    if st != 200 or res.get("code") != 200:
        raise RuntimeError(f"Failed submitting e2e.city.quiz: HTTP {st} {res}")
    sid = (res.get("data") or {}).get("submissionId")
    if not sid:
        raise RuntimeError(f"Missing submissionId in response: {res}")

    mysql_exec(f"""
        UPDATE external_game_submission
        SET status = 'APPROVED',
            reviewer_id = 1,
            reviewed_time = NOW(3),
            update_time = NOW(3)
        WHERE id = {int(sid)} AND game_id = 'e2e.city.quiz';

        INSERT INTO external_game (game_id, owner_member_id, approved_submission_id, approved_time, create_time, update_time)
        VALUES ('e2e.city.quiz', 9004, {int(sid)}, NOW(3), NOW(3), NOW(3))
        ON DUPLICATE KEY UPDATE
            approved_submission_id = VALUES(approved_submission_id),
            approved_time = COALESCE(approved_time, NOW(3)),
            update_time = NOW(3);
    """)
    return f"submitted-and-approved(sid={sid})"



def extract_list(d):
    if isinstance(d, list):
        return d
    if not isinstance(d, dict):
        return []
    for k in ("rows", "items", "list", "records"):
        v = d.get(k)
        if isinstance(v, list):
            return v
    inner = d.get("data")
    if isinstance(inner, list):
        return inner
    if isinstance(inner, dict):
        for k in ("rows", "items", "list", "records"):
            v = inner.get(k)
            if isinstance(v, list):
                return v
    return []

def verify_all(tok_9004, tok_9009, tok_9002, tok_9003):
    results = []

    failures = []
    def check(label, cond, detail=""):
        status = "PASS" if cond else "FAIL"
        results.append((label, status, detail))
        print(f"[{status}] {label}" + (f" -> {detail}" if detail else ""))
        if not cond:
            failures.append(f"{label}: {detail}")

    # 1. Templates (adoptable) + Roam POIs near e2e/route.gpx
    st, d = http_req("POST", "/api/template/homeData")
    data = d.get("data") or {}
    check(
        "1a. template/homeData",
        st == 200 and d.get("code") == 200 and (data.get("total") or 0) >= 2 and len(data.get("categoryList") or []) >= 2,
        f"total={data.get('total')}, cats={len(data.get('categoryList') or [])}",
    )

    st, d = http_req("POST", "/api/template/list", token=tok_9003)
    rows = extract_list(d)
    check("1b. template/list", st == 200 and any(r.get("id") in (992501, 992502) for r in rows), f"count={len(rows)}")

    st, d = http_req("GET", "/api/template/adoption-source?originalTemplateId=992501", token=tok_9004)
    check("1c. template/adoption-source(992501)", st == 200 and d.get("code") == 200, f"keys={list((d.get('data') or {}).keys())[:6]}")

    st, d = http_req("POST", "/api/template/topic-template/list", token=tok_9003)
    t_rows = extract_list(d)
    check("1d. template/topic-template/list", st == 200 and any(r.get("id") == 992510 for r in t_rows), f"count={len(t_rows)}")

    st, d = http_req("GET", "/api/roam/pois?lat=31.2304&lng=121.4737&radius=3000", token=tok_9004)
    pois = d.get("data") or []
    poi_ids = [p.get("id") for p in pois]
    check("1e. roam/pois near e2e/route.gpx", st == 200 and all(pid in poi_ids for pid in (992521, 992522, 992523, 992524)), f"count={len(pois)}, ids={poi_ids[:6]}")

    st, d = http_req("POST", "/api/map/nearby", token=tok_9004, form_body={"longitude": "121.4737", "latitude": "31.2304", "radius": "3000"})
    m_rows = extract_list(d)
    check("1f. map/nearby near route", st == 200 and d.get("code") == 200 and len(m_rows) >= 1, f"count={len(m_rows)}")

    # 2. Playable journey (QA + hint ladder) + team-mode session + team backpack
    st, d = http_req("GET", "/api/play/route-state?topicId=992001&activityId=992031", token=tok_9004)
    rs = d.get("data") or {}
    run_id = rs.get("sessionId") or rs.get("routeSessionId") or rs.get("id")
    if not run_id:
        raw_sid = mysql_exec("SELECT id FROM topic_route_session WHERE member_id=9004 AND topic_id=992001 AND activity_id=992031 ORDER BY id DESC LIMIT 1")
        run_id = int(raw_sid) if raw_sid else None
    check("2a. play/route-state (9004)", st == 200 and d.get("code") == 200 and bool(run_id), f"runId={run_id}")

    st, d = http_req("GET", f"/api/play/help?runId={run_id}&nodeId=992021", token=tok_9004)
    hdata = d.get("data") or {}
    layers = hdata.get("layers") or hdata.get("levels") or []
    free_uses = hdata.get("remainingFreeUses") if "remainingFreeUses" in hdata else hdata.get("freeUsesPerRun")
    check("2b. play/help hint ladder (node 992021)", st == 200 and d.get("code") == 200 and hdata.get("enabled") is True and len(layers) >= 2, f"enabled={hdata.get('enabled')}, layers={len(layers)}, remainingFreeUses={free_uses}")

    st, d = http_req("POST", "/api/team/my", token=tok_9004, json_body={})
    teams = extract_list(d)
    check("2c. team/my (9004 active team 992530)", st == 200 and any(t.get("id") == 992530 and t.get("status") in (0, 1, 2) for t in teams), f"teams={[t.get('id') for t in teams]}")

    st, d = http_req("GET", "/api/team/pool?teamId=992530", token=tok_9004)
    pdata = d.get("data") or {}
    check("2d. team/pool (992530)", st == 200 and d.get("code") == 200 and pdata.get("enabled") is True and (pdata.get("hintsTotal") or 0) >= 3, f"hints={pdata.get('hintsTotal')}, skips={pdata.get('skipsTotal')}, timer={pdata.get('timerLimitSec')}")

    st, d = http_req("GET", "/api/play/journey/backpack?topicId=992001&activityId=992031", token=tok_9004)
    bdata = d.get("data") or {}
    bp_nodes = bdata.get("nodes") or bdata.get("items") or []
    check("2e. play/journey/backpack", st == 200 and d.get("code") == 200 and len(bp_nodes) >= 1, f"nodes={len(bp_nodes)}")

    # 3. Club 7001 + channel + waitlist activity + free activity
    st, d = http_req("POST", "/api/club/detail", token=tok_9004, json_body={"id": 7001})
    check("3a. club/detail (7001)", st == 200 and d.get("code") == 200 and (d.get("data") or {}).get("id") == 7001, f"name={(d.get('data') or {}).get('name')}")

    st, d = http_req("GET", "/api/guilds/1/7001/channels", token=tok_9004)
    gdata = d.get("data") or {}
    ch_list = []
    if isinstance(gdata, dict) and isinstance(gdata.get("groups"), list):
        for grp in gdata["groups"]:
            if isinstance(grp, dict) and isinstance(grp.get("channels"), list):
                ch_list.extend(grp["channels"])
    elif isinstance(gdata, dict) and isinstance(gdata.get("channels"), list):
        ch_list = gdata["channels"]
    else:
        ch_list = extract_list(d)
    check("3b. guilds/1/7001/channels", st == 200 and any(str(c.get("id")) == "992531" for c in ch_list), f"channels={len(ch_list)}")

    st, d = http_req("POST", "/api/activity/info", token=tok_9004, form_body={"id": "992031"})
    act_free = d.get("data") or {}
    check("3c. activity/info free (992031)", st == 200 and act_free.get("id") == 992031, f"name={act_free.get('name')}")

    st, d = http_req("POST", "/api/activity/info", token=tok_9004, form_body={"id": "992033"})
    act_wl = d.get("data") or {}
    check("3d. activity/info waitlist (992033)", st == 200 and act_wl.get("id") == 992033, f"name={act_wl.get('name')}")

    st, d = http_req("POST", "/api/club/event-ops/waitlist/status", token=tok_9004, json_body={"activityId": 992033, "ticketId": 992043})
    check("3e. club/event-ops/waitlist/status (992033)", st == 200 and d.get("code") == 200, f"data={d.get('data')}")

    # 4. Merchant 8001/9002 non-zero TEST balance + payout account
    st, d = http_req("POST", "/api/wallet/balance", token=tok_9002, json_body={})
    bal = float((d.get("data") or {}).get("balance") or 0)
    check("4a. wallet/balance (9002)", st == 200 and bal >= 1280.0, f"balance={bal}")

    st, d = http_req("POST", "/api/balance/list", token=tok_9002, form_body={})
    blist = extract_list(d)
    check("4b. balance/list (9002)", st == 200 and len(blist) >= 2, f"entries={len(blist)}")

    st, d = http_req("POST", "/api/merchant/finance/overview", token=tok_9002, json_body={})
    ov = d.get("data") or {}
    check(
        "4c. merchant/finance/overview (9002)",
        st == 200 and d.get("code") == 200 and float(ov.get("personalArrivedThisMonthNet") or 0) > 0,
        f"net={ov.get('personalArrivedThisMonthNet')}, publicPending={ov.get('publicPayablePending')}",
    )

    st, d = http_req("POST", "/api/merchant/payout-account", token=tok_9002, json_body={})
    po = d.get("data") or {}
    sub_row = mysql_exec("SELECT CONCAT(applyment_state, ':', COALESCE(sub_mchid, '')) FROM ecom_sub_merchant WHERE owner_type='MERCHANT' AND owner_id=8001 LIMIT 1")
    po_ok = (
        st == 200
        and d.get("code") == 200
        and sub_row == "FINISH:1900008001"
        and (
            po.get("state") == "FINISH"
            or (po.get("available") is False and po.get("unavailableReason") == "PLATFORM_NOT_OPEN")
        )
    )
    check("4d. merchant/payout-account (8001)", po_ok, f"api={po}, db={sub_row}")

    # 5. Rewards policy + store items
    st, d = http_req("GET", "/api/reward-pledge/pledges?storeId=8001", token=tok_9002)
    pledges = (d.get("data") or {}).get("pledges") or extract_list(d)
    check(
        "5a. reward-pledge/pledges (8001)",
        st == 200 and any((p.get("pledge") or p).get("pledgeId") == "e2e-v2-pledge-001" and (p.get("pledge") or p).get("state") == "FROZEN" for p in pledges),
        f"count={len(pledges)}",
    )

    st, d = http_req("POST", "/api/points/result_list", token=tok_9004)
    pts = extract_list(d)
    check("5b. points/result_list", st == 200 and len(pts) >= 4, f"rules={len(pts)}")

    st, d = http_req("POST", "/api/product/list", token=tok_9004, form_body={"publishStatus": "1"})
    prods = extract_list(d)
    check("5c. product/list", st == 200 and any(p.get("id") in (992551, 992552) for p in prods), f"products={len(prods)}")

    st, d = http_req("POST", "/api/gift/list", token=tok_9004)
    gifts = extract_list(d)
    check("5d. gift/list", st == 200 and any(g.get("id") in (992551, 992552) for g in gifts), f"gifts={len(gifts)}")

    # 6. Workshop purchased licences
    st, d = http_req("POST", "/api/workshop/v2/purchased/list", token=tok_9004, json_body={})
    wk_9004 = extract_list(d)
    check("6a. workshop/v2/purchased/list (9004)", st == 200 and any((w.get("licenseId") or w.get("orderNo")) == "WKO_99250100000000000000000000009004" and w.get("status") == "ACTIVE" for w in wk_9004), f"count={len(wk_9004)}, msg={d.get('msg') if not wk_9004 else ''}")

    st, d = http_req("POST", "/api/workshop/v2/purchased/list", token=tok_9003, json_body={})
    wk_9003 = extract_list(d)
    check("6b. workshop/v2/purchased/list (9003)", st == 200 and any((w.get("licenseId") or w.get("orderNo")) == "WKO_99250100000000000000000000009003" and w.get("status") == "ACTIVE" for w in wk_9003), f"count={len(wk_9003)}, msg={d.get('msg') if not wk_9003 else ''}")

    # 7. Approved external game in catalog
    st, d = http_req("GET", "/api/external-game/catalog")
    games = (d.get("data") or {}).get("items") or []
    check("7a. external-game/catalog", st == 200 and any(g.get("gameId") == "e2e.city.quiz" for g in games), f"games={[g.get('gameId') for g in games]}")

    st, d = http_req("GET", "/api/external-game/catalog/e2e.city.quiz")
    ginfo = d.get("data") or {}
    check("7b. external-game/catalog/e2e.city.quiz", st == 200 and ginfo.get("gameId") == "e2e.city.quiz", f"version={ginfo.get('version')}")

    # 8. Rejected project (for appeals)
    st, d = http_req("POST", "/api/project/my", token=tok_9004, form_body={})
    my_proj = extract_list(d)
    check("8a. project/my rejected topic (9004)", st == 200 and any(p.get("id") == 992561 and (p.get("state") == "rejected" or p.get("status") == 2) for p in my_proj), f"projects={len(my_proj)}")

    st, d = http_req("GET", "/api/audit/992561/detail", token=tok_9004)
    adet = d.get("data") or {}
    check("8b. audit/992561/detail", st == 200 and d.get("code") == 200, f"status={(adet.get('task') or {}).get('status', adet.get('status'))}")

    # 9. Published community post
    st, d = http_req("GET", "/api/v1/community/feeds/LATEST", token=tok_9004)
    feed = extract_list(d)
    check("9a. v1/community/feeds/LATEST", st == 200 and any(((item.get("post") if isinstance(item, dict) else None) or item).get("id") == 992571 for item in feed), f"feed_count={len(feed)}")

    st, d = http_req("GET", "/api/v1/community/posts/992571", token=tok_9004)
    pdata = d.get("data") or {}
    post = pdata.get("post") if isinstance(pdata, dict) and isinstance(pdata.get("post"), dict) else pdata
    check("9b. v1/community/posts/992571", st == 200 and post.get("id") == 992571 and post.get("lifecycle") == "PUBLISHED" and post.get("audience") == "PUBLIC", f"title={post.get('title')}")

    if failures:
        raise AssertionError("\n".join(failures))
    return results


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "seed-and-verify"
    tok_9004 = login_phone("13900000004")
    tok_9009 = login_phone("13900000009")
    tok_9002 = login_phone("13900000002")
    tok_9003 = login_phone("13900000003")

    ext_state = ensure_approved_external_game(tok_9004)
    print(f"[INFO] external game e2e.city.quiz: {ext_state}")

    # Initialize route session for 9009 too so team co-play has a route session
    http_req("GET", "/api/play/route-state?topicId=992001&activityId=992031", token=tok_9009)

    if mode in ("seed-and-verify", "verify"):
        results = verify_all(tok_9004, tok_9009, tok_9002, tok_9003)
        print(f"SUMMARY: {len(results)}/{len(results)} checks PASSED")


if __name__ == "__main__":
    main()
