"""k5 rig walk helpers (throwaway rig DB cy_e2e_51 only). Every call is real HTTP to the real jar on 127.0.0.1:8099;
read-backs are SQL on the rig DB. Fixture writes and time shifts are printed with NOTE: so they are labelled in the log."""
import os, sys, json, datetime, time, pathlib, subprocess
RIG = os.path.expanduser('~/walk-rig')
sys.path.insert(0, RIG + '/candidate/scripts/e2e')
for k in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"): os.environ.pop(k, None)
os.environ['PATH'] = RIG + '/work/bin:' + os.environ['PATH']
for k, v in {"CHENGYIN_E2E_BASE": "http://127.0.0.1:8099", "CHENGYIN_E2E_DB_HOST": "127.0.0.1", "CHENGYIN_E2E_DB_PORT": "3316",
             "CHENGYIN_E2E_DB_USER": "root", "CHENGYIN_E2E_DB_PASSWORD": "", "CHENGYIN_E2E_DB_SCHEMA": "cy_e2e_51"}.items():
    os.environ.setdefault(k, v)
import chain_lib as cl  # noqa: E402
import urllib.request, urllib.parse  # noqa: E402
HERE = pathlib.Path(__file__).resolve().parent
LOG = HERE / 'k5-walk-readbacks.txt'
STATE = HERE / 'state.json'
JAR = subprocess.run(['git', '-C', RIG + '/candidate', 'rev-parse', '--short=8', 'HEAD'], capture_output=True, text=True).stdout.strip()

def stamp(): return datetime.datetime.utcnow().strftime('%m-%d %H:%M:%S')
def log(label, value):
    text = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False, default=str)
    line = '[%s jar@%s] %s: %s' % (stamp(), JAR, label, text)
    print(line[:1600]); LOG.open('a', encoding='utf-8').write(line[:4000] + '\n')
def note(text): log('NOTE', text)
def http(label, method, path, token=None, json_body=None, form=None, show=True):
    r = cl.http(method, path, token=token, json_body=json_body, form=form)
    if show: log(label, {k: v for k, v in r.items() if not k.startswith('_')} if isinstance(r, dict) else r)
    return r
def q(label, sql, args=None):
    rows = cl.q(sql, args)
    log(label + ' :: ' + sql[:150], [list(r) for r in rows])
    return rows
def ex(sql, args=None): return cl.ex(sql, args)
def login(openid):
    data = urllib.parse.urlencode({"code": "rig-" + openid}).encode()
    req = urllib.request.Request("http://127.0.0.1:8099/api/login/code", data=data, method="POST",
                                 headers={"content-type": "application/x-www-form-urlencoded"})
    r = json.loads(urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=20).read().decode())
    return r.get("token"), (r.get("data") or {})
def phone_token(phone):
    tok, r = cl.app_token(phone); return tok
def admin(): return cl.admin_token()
def state(): return json.loads(STATE.read_text()) if STATE.exists() else {}
def save(**kw):
    s = state(); s.update(kw); STATE.write_text(json.dumps(s, ensure_ascii=False, indent=1))
def run_job(atok, invoke_prefix, wait=6):
    rows = cl.q("SELECT job_id, job_group, job_name FROM sys_job WHERE invoke_target LIKE %s", (invoke_prefix + '%',))
    jid, grp, name = rows[0]
    http('admin run job %s %s' % (jid, name), 'PUT', '/monitor/job/run', token=atok, json_body={"jobId": jid, "jobGroup": grp})
    time.sleep(wait)
    q('job log %s' % name, "SELECT job_log_id,status,LEFT(job_message,90),LEFT(exception_info,700) FROM sys_job_log WHERE job_name=%s ORDER BY job_log_id DESC LIMIT 1", (name,))
def money_table(label, activity_ids, members):
    q('balances [%s]' % label, "SELECT id,balance FROM ums_member WHERE id IN (%s) ORDER BY id" % ','.join(str(m) for m in members))
    q('balance detail [%s]' % label, "SELECT id,member_id,event_type,event_id,change_type,change_balance,after_balance,change_reason FROM ums_member_balance_detail ORDER BY id")
    q('settlement_detail [%s]' % label, "SELECT d.id,d.source_type,d.source_id,d.receiver_type,d.receiver_id,d.gross_amount,d.settlement_amount,d.status FROM settlement_detail d ORDER BY d.id")
    q('registrations [%s]' % label, "SELECT id,owner_id,member_id,payable_amount,payment_status,registration_status,verification_status FROM cms_registration WHERE owner_type=2 AND owner_id IN (%s) ORDER BY id" % ','.join(str(a) for a in activity_ids))
    q('refunds [%s]' % label, "SELECT id,source_id,paid_amount,refund_amount,status FROM refund_application ORDER BY id")
    q('activities [%s]' % label, "SELECT id,start_date,end_date,is_settlement FROM cms_activity WHERE id IN (%s)" % ','.join(str(a) for a in activity_ids))

def idcard(body17):
    w=[7,9,10,5,8,4,2,1,6,3,7,9,10,5,8,4,2]; c='10X98765432'
    return body17 + c[sum(int(a)*b for a,b in zip(body17,w))%11]
