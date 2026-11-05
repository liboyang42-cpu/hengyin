"""r1-release second pass: apply release migrations (candidate's own ci/run_migration.py) and snapshot state."""
import json, os, subprocess, sys, hashlib, pymysql, datetime
CAND = os.path.expanduser("~/walk-new/candidate"); OUT = os.path.expanduser("~/reh/out")
PY = os.path.expanduser("~/walk-old/venv/bin/python")
PRE = ["20261101_author_write_receipt", "20261102_merchant_industry_categories", "20261103_content_safety_recheck",
       "20261103_sensitive_content_library", "20261104_invitee_signup_points_off",
       "20261104_merchant_industry_categories_readback", "20261104_topic_total_time_seconds",
       "20261102_club_session_gather_point", "20261104_club_session_gather_point_readback",
       "20261103_settlement_rule_three_percent", "20261102_club_checkin_phone_visible",
       "20261104_merchant_activity_limit", "20261104_review_wording_seeded_text"]
POST = ["20261104_content_safety_jobs_post_deploy"]
def conn():
    return pymysql.connect(host="127.0.0.1", port=3316, user="root", password="", database="cy_e2e_51", autocommit=True, charset="utf8mb4")
def q(s):
    c = conn(); cur = c.cursor(); cur.execute(s); r = [[x if isinstance(x, (int, type(None))) else str(x) for x in row] for row in cur.fetchall()]; c.close(); return r
def run(mid):
    env = dict(os.environ, CHENGYIN_DB_SSH_HOST="local", LANG="C.UTF-8", PYTHONIOENCODING="utf-8")
    p = subprocess.run([PY, "ci/run_migration.py", "--config", os.path.expanduser("~/reh/druid.yml"), "--sql",
                        "chengyinhub-admin/sql/migration_%s.sql" % mid], cwd=CAND, env=env, capture_output=True, text=True)
    txt = p.stdout + p.stderr
    asserts = [l.strip() for l in txt.splitlines() if "readback" in l or "断言" in l or "assert" in l.lower()]
    return {"migration": mid, "exit": p.returncode, "asserts": asserts[-6:], "tail": txt[-300:] if p.returncode else ""}
def state():
    s = {}
    s["settlement_rule"] = q("SELECT id,biz_type,merchant_id,platform_commission_rate,merchant_rate,creator_rate,status FROM settlement_rule WHERE id BETWEEN 9801 AND 9805 ORDER BY id")
    s["sys_category"] = q("SELECT id,type,category_name FROM sys_category WHERE type=6 OR id IN (9811,9812) ORDER BY id")
    s["gather"] = q("SELECT id,latitude,longitude FROM cms_activity WHERE id BETWEEN 9801 AND 9805 ORDER BY id")
    s["club_role_assignment"] = q("SELECT id,role_code,customer_phone_visible FROM club_role_assignment WHERE id=9831") if q("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='club_role_assignment' AND column_name='customer_phone_visible'")[0][0] else "column absent"
    s["user_permission"] = q("SELECT role,membership_level,can_create_event,max_events FROM user_permission ORDER BY role")
    s["points"] = q("SELECT id,title,value,status,del_flag FROM cms_points_setting WHERE id BETWEEN 1 AND 7 ORDER BY id")
    s["total_time"] = q("SELECT id,name,total_time FROM cms_topic WHERE id BETWEEN 9831 AND 9835 OR id IN (SELECT id FROM (SELECT id FROM cms_topic WHERE id>=990000) z) ORDER BY id")
    s["wording_topic"] = q("SELECT id,name,LEFT(description,12) FROM cms_topic WHERE id BETWEEN 9851 AND 9854 ORDER BY id")
    s["wording_node"] = q("SELECT topic_id,name FROM cms_topic_node WHERE topic_id IN (9851,9854) ORDER BY id")
    s["wording_tpl"] = q("SELECT id,title FROM cms_member_template WHERE id IN (9861,9862) ORDER BY id")
    s["wording_chapter"] = q("SELECT topic_id,name FROM cms_topic_chapter WHERE topic_id=9853")
    s["wording_menu"] = q("SELECT menu_id,menu_name,LEFT(remark,14) FROM sys_menu WHERE menu_id BETWEEN 2541 AND 2551 ORDER BY menu_id")
    s["sys_job_new"] = q("SELECT job_name,invoke_target,cron_expression,status FROM sys_job WHERE invoke_target IN ('contentSafetyRecheckTask.run()','sensitiveContentRescanTask.run()','predictVoidExpiredTask.run()') ORDER BY invoke_target")
    s["config"] = q("SELECT config_key,config_value FROM sys_config WHERE config_key='content.sensitive.rescan.remove'")
    s["menus_sensitive"] = q("SELECT menu_name,perms,(SELECT COUNT(*) FROM sys_role_menu rm WHERE rm.menu_id=m.menu_id) FROM sys_menu m WHERE perms LIKE 'business:sensitive:%' ORDER BY perms")
    s["new_tables"] = q("SELECT table_name,engine FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('app_author_write_receipt','content_safety_recheck','content_sensitive_word','content_sensitive_allow','content_sensitive_image','content_sensitive_hit') ORDER BY 1")
    return s
def fingerprint():
    cols = q("SELECT table_name,column_name,column_type,is_nullable,IFNULL(column_default,'~') FROM information_schema.columns WHERE table_schema=DATABASE() ORDER BY 1,2")
    idx = q("SELECT table_name,index_name,non_unique,seq_in_index,column_name FROM information_schema.statistics WHERE table_schema=DATABASE() ORDER BY 1,2,4")
    h = lambda o: hashlib.sha256(json.dumps(o, ensure_ascii=False).encode()).hexdigest()[:16]
    return {"tables": len({c[0] for c in cols}), "columns": len(cols), "index_cols": len(idx), "col_digest": h(cols), "idx_digest": h(idx)}
if __name__ == "__main__":
    cmd = sys.argv[1]; tag = sys.argv[2] if len(sys.argv) > 2 else cmd
    out = {"at_utc": datetime.datetime.utcnow().isoformat(timespec="seconds"), "step": tag}
    if cmd == "state":
        out["state"] = state(); out["fingerprint"] = fingerprint()
    elif cmd in ("pre", "post"):
        out["runs"] = [run(m) for m in (PRE if cmd == "pre" else POST)]
        out["state"] = state(); out["fingerprint"] = fingerprint()
    elif cmd == "one":
        out["runs"] = [run(sys.argv[3])]
    json.dump(out, open(os.path.join(OUT, tag + ".json"), "w"), ensure_ascii=False, indent=1)
    print(json.dumps({k: v for k, v in out.items() if k != "state"}, ensure_ascii=False)[:3000])
