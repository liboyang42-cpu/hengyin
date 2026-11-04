"""r1-release rehearsal on loopback scratch cy_e2e_51 (baseline = 014fc0d83 schema tools + fixtures).
Applies the 7 release migrations with the candidate's own ci/run_migration.py (runner assertions evaluated)."""
import json, os, subprocess, sys, hashlib, pymysql
CAND = os.path.expanduser("~/wt/cand"); RH = os.path.expanduser("~/walk-rig/reh")
PY = os.path.expanduser("~/walk-rig/venv/bin/python")
ORDER = ["migration_20261101_author_write_receipt.sql", "migration_20261102_merchant_industry_categories.sql",
         "migration_20261103_content_safety_recheck.sql", "migration_20261103_sensitive_content_library.sql",
         "migration_20261102_club_session_gather_point.sql", "migration_20261103_settlement_rule_three_percent.sql",
         "migration_20261102_club_checkin_phone_visible.sql"]
c = pymysql.connect(host="127.0.0.1", port=3316, user="root", password="", database="cy_e2e_51", autocommit=True)
cur = c.cursor()
def q(s, a=None):
    cur.execute(s, a); return [list(map(lambda x: x if isinstance(x, (int, type(None))) else str(x), r)) for r in cur.fetchall()]
def x(s, a=None):
    return cur.execute(s, a)
def run(fname):
    env = dict(os.environ, CHENGYIN_DB_SSH_HOST="local", LANG="C.UTF-8", PYTHONIOENCODING="utf-8")
    p = subprocess.run([PY, "ci/run_migration.py", "--config", RH + "/druid.yml", "--sql", "chengyinhub-admin/sql/" + fname],
                       cwd=CAND, env=env, capture_output=True, text=True)
    return p.returncode, (p.stdout + p.stderr)
def state():
    s = {}
    s["settlement_rule"] = q("SELECT id,merchant_id,biz_type,platform_commission_rate,merchant_rate,creator_rate,status,del_flag FROM settlement_rule ORDER BY id")
    s["sys_category_6"] = q("SELECT id,type,category_name,sort_id,status,del_flag FROM sys_category WHERE type=6 OR id IN (9811,9812) ORDER BY id")
    s["gather"] = q("SELECT id,club_id,latitude,longitude FROM cms_activity WHERE id BETWEEN 9801 AND 9805 ORDER BY id")
    s["jobs_new"] = q("SELECT job_name,invoke_target,cron_expression,misfire_policy,concurrent,status FROM sys_job WHERE invoke_target IN ('contentSafetyRecheckTask.run()','sensitiveContentRescanTask.run()') ORDER BY invoke_target")
    s["config_rescan"] = q("SELECT config_key,config_value,config_type FROM sys_config WHERE config_key='content.sensitive.rescan.remove'")
    s["menus"] = q("SELECT menu_name,perms,menu_type,visible,status,parent_id=(SELECT menu_id FROM (SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='audit-center') z) FROM sys_menu WHERE perms LIKE 'business:sensitive:%' ORDER BY perms")
    s["menu_grants"] = q("SELECT COUNT(*) FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id=rm.menu_id WHERE m.perms LIKE 'business:sensitive:%'")
    s["cra"] = q("SELECT id,role_code,customer_phone_visible FROM club_role_assignment ORDER BY id") if q("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='club_role_assignment' AND column_name='customer_phone_visible'")[0][0] else "column absent"
    s["new_tables"] = q("SELECT table_name,engine FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('app_author_write_receipt','content_safety_recheck','content_sensitive_word','content_sensitive_allow','content_sensitive_image','content_sensitive_hit') ORDER BY 1")
    return s
def digest(o): return hashlib.sha256(json.dumps(o, ensure_ascii=False, sort_keys=True).encode()).hexdigest()[:16]
log = {"baseline": None, "negative_controls": [], "pass1": [], "pass2": []}
phase = sys.argv[1]
if phase == "seed":
    for t,w in (("settlement_rule","id BETWEEN 9801 AND 9806"),("sys_category","id IN (9811,9812)"),("cms_topic","id=9821"),("cms_activity","id BETWEEN 9801 AND 9805"),("oms_ticket","id BETWEEN 98011 AND 98018"),("club_role_assignment","id=9831")): x("DELETE FROM %s WHERE %s" % (t,w))
    # fictional, scratch-only rows so each data migration has something to do
    x("INSERT INTO settlement_rule(id,rule_name,merchant_id,biz_type,platform_commission_rate,merchant_rate,creator_rate,status,del_flag,create_time,update_time) VALUES "
      "(9801,'rehearsal activity generic',0,'activity',0.1000,0.9000,0.0000,1,0,NOW(),NOW()),"
      "(9802,'rehearsal topic generic',0,'topic',0.1000,0.9000,0.0000,1,0,NOW(),NOW()),"
      "(9803,'rehearsal topic_club generic',0,'topic_club',0.1000,0.9000,0.0000,1,0,NOW(),NOW()),"
      "(9804,'rehearsal merchant-specific (must not change)',8001,'activity',0.0500,0.9500,0.0000,1,0,NOW(),NOW()),"
      "(9805,'rehearsal disabled generic (must not change)',0,'activity',0.2000,0.8000,0.0000,0,0,NOW(),NOW())")
    x("INSERT INTO sys_category(id,type,parent_id,category_name,level,sort_id,status,del_flag,create_time,update_time) VALUES "
      "(9811,1,0,'茶馆',0,90,1,0,NOW(),NOW()),(9812,1,0,'书店',0,91,1,0,NOW(),NOW())")
    x("INSERT INTO cms_topic(id,name,category_ids,del_flag) VALUES (9821,'rehearsal topic using 书店','21,9812',0)")
    x("INSERT INTO cms_activity(id,club_id,del_flag,cancel_time,end_date,latitude,longitude) VALUES "
      "(9801,7001,0,NULL,DATE_ADD(NOW(),INTERVAL 3 DAY),NULL,NULL),"     # backfill expected from ticket 98012
      "(9802,7001,0,NULL,DATE_SUB(NOW(),INTERVAL 1 DAY),NULL,NULL),"     # ended: untouched
      "(9803,7001,0,NULL,DATE_ADD(NOW(),INTERVAL 3 DAY),'30.100000','120.100000')," # has coords: untouched
      "(9804,NULL,0,NULL,DATE_ADD(NOW(),INTERVAL 3 DAY),NULL,NULL),"     # not a club session: untouched
      "(9805,7001,0,NOW(),DATE_ADD(NOW(),INTERVAL 3 DAY),NULL,NULL)")    # cancelled: untouched
    x("INSERT INTO oms_ticket(id,owner_type,owner_id,name,gather_lat,gather_lng,del_flag) VALUES "
      "(98011,2,9801,'no coords',NULL,NULL,0),(98012,2,9801,'pick me',31.230400,121.473700,0),(98013,2,9801,'later',32.000000,122.000000,0),"
      "(98014,2,9802,'ended',31.1,121.1,0),(98015,2,9803,'has',31.2,121.2,0),(98016,2,9804,'noclub',31.3,121.3,0),(98017,2,9805,'cancelled',31.4,121.4,0),"
      "(98018,1,9801,'topic-owned ticket, ignored',10.0,10.0,0)")
    x("INSERT INTO club_role_assignment(id,club_id,role_code,scope_type,scope_id,target_member_id,assigned_by_member_id,assigned_at,create_time) VALUES "
      "(9831,7001,'EVENT_CHECKIN','EVENT',9801,9005,9003,NOW(),NOW())")
    json.dump(state(), open(RH + "/state-baseline.json", "w"), ensure_ascii=False, indent=1)
    print(json.dumps(state(), ensure_ascii=False))
elif phase == "negctl":
    out = []
    # NC1: two generic activity rows -> pre-assertion must stop before any UPDATE
    x("UPDATE settlement_rule SET status=0 WHERE id=9802")  # topic generic row missing (uk_rule_scope forbids a duplicate)
    before = q("SELECT id,platform_commission_rate FROM settlement_rule ORDER BY id")
    rc, txt = run("migration_20261103_settlement_rule_three_percent.sql")
    after = q("SELECT id,platform_commission_rate FROM settlement_rule ORDER BY id")
    out.append({"control": "generic topic rule missing; activity row must stay 0.10", "exit": rc, "unchanged": before == after, "tail": txt[-600:]})
    x("UPDATE settlement_rule SET status=1 WHERE id=9802")
    # NC2: no generic rows at all (what the bare scratch build has) -> must stop too
    x("CREATE TEMPORARY TABLE sr_bak AS SELECT * FROM settlement_rule"); x("DELETE FROM settlement_rule")
    rc, txt = run("migration_20261103_settlement_rule_three_percent.sql")
    out.append({"control": "empty settlement_rule", "exit": rc, "rows_after": q("SELECT COUNT(*) FROM settlement_rule")[0][0], "tail": txt[-400:]})
    x("INSERT INTO settlement_rule SELECT * FROM sr_bak"); x("DROP TEMPORARY TABLE sr_bak")
    out.append({"restored": q("SELECT id,platform_commission_rate FROM settlement_rule ORDER BY id")})
    json.dump(out, open(RH + "/negative-controls.json", "w"), ensure_ascii=False, indent=1)
    for o in out: print(json.dumps(o, ensure_ascii=False)[:900])
elif phase in ("pass1", "pass2"):
    res = []
    for f in ORDER:
        b = state(); rc, txt = run(f); a = state()
        changed = [k for k in a if digest(a[k]) != digest(b[k])]
        res.append({"migration": f, "exit": rc, "changed": changed,
                    "assert_lines": [l for l in txt.splitlines() if "assert" in l.lower() or "断言" in l][-6:], "tail": txt[-300:]})
        print(f, "exit", rc, "changed", changed)
        for l in res[-1]["assert_lines"]: print("   ", l)
        if rc != 0: print(txt[-1500:]); break
    json.dump({"results": res, "state_after": state()}, open(RH + "/%s.json" % phase, "w"), ensure_ascii=False, indent=1)
elif phase == "state":
    print(json.dumps(state(), ensure_ascii=False, indent=1))
