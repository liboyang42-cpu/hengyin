import json, subprocess, sys, urllib.error, urllib.parse, urllib.request
BASE = "http://127.0.0.1:8120"
REDIS = ["docker", "exec", "be-int-redis", "redis-cli", "-n", "9"]
def call(method, path, token=None, body=None, form=None, query=None):
    url = BASE + path + ("?" + urllib.parse.urlencode(query) if query else "")
    data, headers = None, {}
    if token: headers["Authorization"] = token
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode(); headers["Content-Type"] = "application/json"
    elif form is not None:
        data = urllib.parse.urlencode(form).encode(); headers["Content-Type"] = "application/x-www-form-urlencoded"
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=30) as r: status, raw = r.status, r.read()
    except urllib.error.HTTPError as e: status, raw = e.code, e.read()
    try: return status, json.loads(raw)
    except Exception: return status, {"raw": raw[:200].decode(errors="replace")}
def login(phone):
    subprocess.run(REDIS + ["DEL", "sms_limit_phone_" + phone, "sms_send_phone_day_" + phone, "sms_verify_ip_window_127.0.0.1", "sms_send_ip_window_127.0.0.1"], capture_output=True)
    call("POST", "/api/sms/send", form={"phone": phone})
    code = subprocess.run(REDIS + ["GET", "sms_%s_code" % phone], capture_output=True, text=True).stdout.strip().strip('"')
    st, d = call("POST", "/api/login/phone", form={"phone": phone, "code": code})
    return d["token"]
