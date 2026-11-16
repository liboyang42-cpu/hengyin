"""Shared harness for the Chengyin backend acceptance flows (TEST server only).

Every flow script builds a `Flow`, calls `step(...)` in the order a real app client would,
and returns its step records. `run_all.py` runs every flow and writes results.json.

Safety:
  * BASE must be an *.instabox.tech TEST host or loopback :8120; anything else is refused.
  * Only the e2e-test fixture accounts (phones 13900000001-13900000009) are used.
  * Login codes come from the TEST harness hooks, never from real users:
      E2E_TOKEN_<phone>   a token already minted for that fixture phone, or
      E2E_CODE_CMD        a shell command that prints the code, with {phone} substituted
                          (e.g. "ssh work docker exec be-int-redis redis-cli -n 9 GET sms_{phone}_code"),
      E2E_SMS_CODE        a fixed code pre-planted in TEST Redis (RESULT.md: SET sms_<phone>_code '"123456"'),
      E2E_PLANT_CMD       a shell command that stores {code} for {phone} in TEST Redis (the TEST login hook), so a
                          flow can sign a member in without /api/sms/send and its per-IP window. F01 and F18 still
                          call the real /api/sms/send.

Request shapes follow the native iOS client (chengyin-app origin/ui/main, Core/*):
  * the Authorization header carries the bare token (AuthRequestBuilder), not "Bearer <token>";
  * form fields go as multipart/form-data (AuthRequestBuilder.makeFormRequest); JSON bodies only where the
    native service sends JSON (club, wallet/stages, registration quote/create, consents, merchant finance, ...).
  * A step the TEST environment cannot run (no pay sandbox) is recorded as TEST-SKIP with its reason; a flow whose
    other steps all pass is PASS.
"""
import json, os, random, re, shlex, subprocess, sys, time, uuid

import requests

BASE = os.environ.get("E2E_BASE_URL", "https://8120-agqyfqxs5vwolymtoibzq3fhde.instabox.tech").rstrip("/")
if not (re.match(r"^https://[0-9a-z-]+\.instabox\.tech$", BASE) or BASE.startswith("http://127.0.0.1:8120")):
    sys.exit("refusing non-TEST base url: %s" % BASE)
TIMEOUT = float(os.environ.get("E2E_TIMEOUT", "30"))
RUN_ID = os.environ.get("E2E_RUN_ID") or time.strftime("%Y%m%d%H%M%S")

# Fixture accounts (scripts/e2e/seed_fixtures.py, docs/delivery/e2e-seed/RESULT.md)
PHONE = {m: "1390000000%d" % (m - 9000) for m in range(9001, 9010)}
PLAYER, PLAYER2, CLUB_LEADER, MERCHANT = 9004, 9009, 9003, 9002
TOPIC, ACTIVITY, FREE_TICKET, CLUB = 992001, 992031, 992041, 7001
NODES = [992021, 992022, 992023, 992024, 992025, 992026]
MERCHANT_ID = 8001

_TOKENS = {}


class Blocked(Exception):
    """The flow cannot continue (no network, no login, missing precondition)."""


def body_code(js):
    if isinstance(js, dict):
        return js.get("code")
    return None


def data_of(js):
    return js.get("data") if isinstance(js, dict) else None


class Flow:
    def __init__(self, fid, name):
        self.fid, self.name, self.steps, self.ctx = fid, name, [], {}
        self.blocked = None

    def step(self, label, method, path, member=None, form=None, js=None, q=None,
             expect_http=200, expect_code=200, check=None, owner="backend", files=None, enc="multipart"):
        """Call one endpoint and record request/status/business code/latency/verdict.

        check(js) -> None or a string describing why the response is wrong.
        expect_code may be an int, a tuple of ints, or None (don't care)."""
        if self.blocked:
            rec = dict(label=label, method=method, path=path, result="SKIP", note="blocked: " + self.blocked)
            self.steps.append(rec)
            return None
        headers = {"User-Agent": "chengyin-qa-acceptance/r3", "X-Request-Id": "qa-%s-%s" % (RUN_ID, uuid.uuid4().hex[:8]),
                   "Accept": "application/json"}
        if member is not None:
            headers["Authorization"] = token(member)
        data = None
        if form is not None and enc == "multipart" and files is None:
            files = {k: (None, str(v)) for k, v in form.items() if v is not None}
        elif form is not None:
            data = form
        rec = dict(label=label, method=method, path=path, member=member,
                   request=_redact(dict(q=q, form=form, json=js)))
        t0 = time.monotonic()
        try:
            r = requests.request(method, BASE + path, params=q, data=data, json=js, files=files,
                                 headers=headers, timeout=TIMEOUT)
        except requests.RequestException as e:
            rec.update(result="ERROR", status=None, code=None, ms=int((time.monotonic() - t0) * 1000),
                       note="transport: %s" % type(e).__name__ + ": " + str(e)[:200], owner="env")
            self.steps.append(rec)
            self.blocked = "transport error at '%s'" % label
            return None
        ms = int((time.monotonic() - t0) * 1000)
        try:
            out = r.json()
        except ValueError:
            out = {"_raw": r.text[:300]}
        code = body_code(out)
        problems = []
        if expect_http is not None and r.status_code != expect_http:
            problems.append("HTTP %s != %s" % (r.status_code, expect_http))
        exp = expect_code if isinstance(expect_code, (tuple, list)) or expect_code is None else (expect_code,)
        if exp is not None and code not in exp:
            problems.append("code %s not in %s" % (code, list(exp)))
        if not problems and check:
            try:
                why = check(out)
            except Exception as e:  # malformed shape
                why = "check raised %s: %s" % (type(e).__name__, e)
            if why:
                problems.append(why)
        rec.update(status=r.status_code, code=code, ms=ms, msg=(out.get("msg") if isinstance(out, dict) else None),
                   result="PASS" if not problems else "FAIL", note="; ".join(problems) or None,
                   owner=None if not problems else owner,
                   response=_scrub(json.dumps(out, ensure_ascii=False))[:400])
        self.steps.append(rec)
        return out

    def block(self, why):
        self.blocked = self.blocked or why

    def test_skip(self, label, method, path, why):
        """A step TEST cannot run (environment gap, not a product result); it does not fail the flow."""
        self.steps.append(dict(label=label, method=method, path=path, result="TEST-SKIP", note=why, owner="env"))

    def summary(self):
        res = [s["result"] for s in self.steps]
        ok = ("PASS", "TEST-SKIP")
        verdict = "PASS" if res and "PASS" in res and all(x in ok for x in res) else (
            "BLOCKED" if not any(x in ("PASS", "FAIL") for x in res) else "FAIL")
        return dict(id=self.fid, name=self.name, verdict=verdict, steps=self.steps,
                    test_skips=sum(1 for x in res if x == "TEST-SKIP"))


_SECRET_KEYS = r'(?:token|accessToken|refreshToken|qrToken|ticketToken|secret|challengeToken|resultToken|smscode)'


def _redact(d):
    s = json.dumps(d, ensure_ascii=False)
    s = re.sub(r'("code"\s*:\s*")\d{4,6}"', r'\1******"', s)
    s = re.sub(r'("code"\s*:\s*")(?!v1\.)[^"]{16,}"', r'\1<redacted>"', s)  # opaque scan tokens sent as form "code"
    return json.loads(_scrub(s))


def _scrub(s):
    """Never write tokens or QR secrets into results."""
    s = re.sub(r'("%s"\s*:\s*")[^"]+"' % _SECRET_KEYS, r'\1<redacted>"', s)
    # signed entry / coupon codes ("v1.<reg>.<type>.<ts>.<nonce>.<sig>"): keep the readable prefix, drop the signature
    return re.sub(r'\b(v1\.\d+\.[a-z_0-9]+)\.[-\w.]{20,}', r'\1.<redacted>', s)


def _sms_code(phone):
    cmd = os.environ.get("E2E_CODE_CMD")
    if cmd:
        out = subprocess.run(shlex.split(cmd.format(phone=phone)), capture_output=True, text=True, timeout=30)
        code = out.stdout.strip().strip('"')
        if code:
            return code
    return os.environ.get("E2E_SMS_CODE")


def token(member):
    """Log a fixture member in once per run via the real /api/sms/send + /api/login/phone."""
    if member in _TOKENS:
        return _TOKENS[member]
    phone = PHONE.get(member, member)  # a fixture member id, or a raw TEST phone (139000009xx)
    pre = os.environ.get("E2E_TOKEN_%s" % phone)
    if pre:
        _TOKENS[member] = pre
        return pre
    plant = os.environ.get("E2E_PLANT_CMD")
    if plant:
        code = "%06d" % random.randint(0, 999999)
        subprocess.run(shlex.split(plant.format(phone=phone, code=code)), capture_output=True, text=True, timeout=30)
    else:
        try:
            requests.post(BASE + "/api/sms/send", files={"phone": (None, phone)}, timeout=TIMEOUT)
        except requests.RequestException as e:
            raise Blocked("cannot reach TEST server: %s" % e)
        code = _sms_code(phone)
    if not code:
        raise Blocked("no SMS code source for %s (set E2E_CODE_CMD or E2E_SMS_CODE)" % phone)
    r = requests.post(BASE + "/api/login/phone", files={"phone": (None, phone), "code": (None, code)}, timeout=TIMEOUT).json()
    if r.get("code") != 200 or not r.get("token"):
        raise Blocked("login %s failed: %s" % (phone, r.get("msg")))
    _TOKENS[member] = r["token"]
    return r["token"]


def forget(member):
    """Drop a cached token (e.g. after account deletion revokes sessions)."""
    _TOKENS.pop(member, None)


def rid(tag=""):
    return ("qa%s%s%s" % (RUN_ID, tag, uuid.uuid4().hex))[:48]


def run(flow_fn):
    """Run a flow function f(flow) and always return the flow summary."""
    fid, name = flow_fn.FLOW
    f = Flow(fid, name)
    try:
        flow_fn(f)
    except Blocked as e:
        f.block(str(e))
        f.steps.append(dict(label="(precondition)", method="-", path="-", result="BLOCKED", note=str(e), owner="env"))
    except Exception as e:  # a script bug must not hide the other flows
        f.steps.append(dict(label="(harness error)", method="-", path="-", result="ERROR",
                            note="%s: %s" % (type(e).__name__, e), owner="qa"))
    return f.summary()


def ok_data(js):
    return None if data_of(js) is not None else "data is null"


def need(js, *keys):
    """Return a check message when a dotted key is missing from data."""
    d = data_of(js)
    for k in keys:
        cur = d
        for part in k.split("."):
            if not isinstance(cur, dict) or cur.get(part) is None:
                return "missing data.%s" % k
            cur = cur.get(part)
    return None


def advanced_qa(f, member, scope, node, answer, tag):
    """Play a node's advanced qa mechanic the way the native client does (JSON /api/play/advanced/start, then
    /api/play/advanced/action SUBMIT_QA {input}); the basic answer/check-in of such a node is refused until it passes."""
    st = f.step("%s: play/advanced/start node %s" % (tag, node), "POST", "/api/play/advanced/start", member,
                js=dict({k: int(v) for k, v in scope.items()}, nodeId=node), check=lambda j: need(j, "sessionId"))
    d = data_of(st) or {}
    return f.step("%s: play/advanced/action SUBMIT_QA" % tag, "POST", "/api/play/advanced/action", member,
                  js={"sessionId": d.get("sessionId"), "version": d.get("version"), "action": "SUBMIT_QA",
                      "idempotencyKey": "qa-%s-%s" % (RUN_ID, uuid.uuid4().hex[:10]), "payload": {"input": answer}},
                  check=lambda j: None if "1921" in answer and "passed" not in json.dumps(data_of(j) or {}) or
                  (json.dumps(data_of(j) or {}).find('"passed": true') >= 0) else "qa not passed: %s" % json.dumps(data_of(j))[:160])


def main(flow_fn):
    s = run(flow_fn)
    print(json.dumps(s, ensure_ascii=False, indent=1))
    sys.exit(0 if s["verdict"] == "PASS" else 1)
