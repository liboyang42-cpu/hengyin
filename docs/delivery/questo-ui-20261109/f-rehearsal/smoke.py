#!/usr/bin/env python3
"""f-rehearsal 冒烟：对已按发布集迁移的演练库启动的 launch jar 打一组只读接口，证明 schema 与代码一致。

只连 127.0.0.1。登录走真实 /api/login/phone：短信验证码夹具先写进本机 Redis（同 scripts/e2e/chain_lib.app_token）。
判定：HTTP 状态、业务 code，以及响应体与应用日志里是否出现 1054 / 1146 / 1267 / BadSqlGrammar 等 schema 错误。

用法：python3 smoke.py --base http://127.0.0.1:18080 --log <app.log> --report <out.json>
"""
import argparse
import json
import pathlib
import re
import subprocess
import urllib.error
import urllib.parse
import urllib.request

SCHEMA_ERRORS = re.compile(r"Unknown column|doesn't exist|Illegal mix of collations|BadSqlGrammar|"
                           r"SQLSyntaxErrorException|Check constraint|Data truncation|Field '\w+' doesn't have a default")
PHONE = "13900009999"


def http(base, method, path, token=None, params=None, form=None, body=None):
    url = base + path + ("?" + urllib.parse.urlencode(params) if params else "")
    data, headers = None, {}
    if body is not None:
        data, headers["Content-Type"] = json.dumps(body).encode(), "application/json"
    elif form is not None:
        data, headers["Content-Type"] = urllib.parse.urlencode(form).encode(), "application/x-www-form-urlencoded"
    elif method == "POST":
        data = b""
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            status, text = r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        status, text = e.code, e.read().decode("utf-8", "replace")
    try:
        out = json.loads(text)
    except ValueError:
        out = {"_raw": text[:300]}
    return status, out, text


# (名称, 方法, 路径, 需要登录, 查询参数, 表单)
SMOKE = [
    ("首页 project/home", "POST", "/api/project/home", True, None, {}),
    ("首页 project/home（带主题）", "POST", "/api/project/home", True, None, {"topicId": 98400}),
    ("用户信息 user/info", "POST", "/api/user/info", True, None, None),
    ("主题列表（全部）", "POST", "/api/topic/list", False, None, {"is_my": "0"}),
    ("主题列表（推荐）", "POST", "/api/topic/list", False, None, {"is_recommend": "1"}),
    ("主题列表（随时开走）", "POST", "/api/topic/list", False, None, {"self_play": "1"}),
    ("主题详情（游客）990059", "POST", "/api/topic/info-to-user", False, None, {"id": "990059"}),
    ("主题详情（登录）990059", "POST", "/api/topic/info-to-user", True, None, {"id": "990059"}),
    ("主题详情（登录）98400", "POST", "/api/topic/info-to-user", True, None, {"id": "98400"}),
    ("公开目录 list", "POST", "/api/public-catalog/list", False, None, None),
    ("公开目录 filters", "POST", "/api/public-catalog/filters", False, None, None),
    ("公开目录 city/list", "POST", "/api/public-catalog/city/list", False, None, None),
    ("公开目录 info", "POST", "/api/public-catalog/info", False, None, {"id": "990059"}),
    ("活动列表", "POST", "/api/activity/list", False, None, None),
    ("游玩节点 topic 990059", "GET", "/api/play/nodes", True, {"topicId": "990059"}, None),
    ("游玩节点 topic 98400", "GET", "/api/play/nodes", True, {"topicId": "98400"}, None),
    ("内容清单 990059", "GET", "/api/play/content-manifest", True, {"topicId": "990059"}, None),
    ("钱包余额", "POST", "/api/wallet/balance", True, None, {}),
    ("钱包阶段", "POST", "/api/wallet/stages", True, None, {}),
    ("提现记录", "POST", "/api/wallet/withdraws", True, None, {}),
    ("通行证状态", "GET", "/api/platform-pass", True, None, None),
    ("通行证主题库", "GET", "/api/platform-pass/library", True, None, None),
    ("通行证订单", "GET", "/api/platform-pass/orders", True, None, None),
    ("背包", "GET", "/api/play/journey/backpack", True, {"topicId": "990059"}, None),
    ("案卷", "GET", "/api/play/journey/casebook", True, {"topicId": "990059"}, None),
    ("跑局会话", "GET", "/api/play/run-session/list", True, None, None),
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:18080")
    ap.add_argument("--log", type=pathlib.Path, required=True)
    ap.add_argument("--report", type=pathlib.Path, required=True)
    a = ap.parse_args()
    if not re.match(r"http://(127\.0\.0\.1|localhost)(:\d+)?$", a.base):
        raise SystemExit("只允许本机地址")
    log_start = a.log.stat().st_size if a.log.exists() else 0

    subprocess.run(["redis-cli", "-h", "127.0.0.1", "SET", "sms_%s_code" % PHONE, '"123456"'], check=True,
                   capture_output=True)
    status, login, _ = http(a.base, "POST", "/api/login/phone", form={"phone": PHONE, "code": "123456"})
    token = login.get("token")
    results = [{"name": "手机号登录（新建玩家，读注册赠送积分规则）", "path": "/api/login/phone", "http": status,
                "code": login.get("code"), "msg": str(login.get("msg"))[:120], "schemaError": None}]
    for name, method, path, auth, params, form in SMOKE:
        json_body = form if (form == {} or (form and not all(isinstance(v, str) for v in form.values()))) else None
        status, out, text = http(a.base, method, path, token if auth else None, params,
                                 None if json_body is not None else form, json_body)
        hit = SCHEMA_ERRORS.search(text)
        results.append({"name": name, "path": path, "http": status, "code": out.get("code"),
                        "msg": str(out.get("msg"))[:120], "schemaError": hit.group(0) if hit else None})
    log_text = a.log.read_text(encoding="utf-8", errors="replace")[log_start:] if a.log.exists() else ""
    log_hits = sorted({m.group(0) for m in SCHEMA_ERRORS.finditer(log_text)})
    log_lines = [line[:300] for line in log_text.splitlines() if SCHEMA_ERRORS.search(line)][:40]
    report = {"loggedIn": bool(token), "results": results, "logSchemaErrors": log_hits, "logLines": log_lines}
    a.report.write_text(json.dumps(report, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    for r in results:
        print("%-28s HTTP %s code %s %s %s" % (r["name"], r["http"], r["code"], r["msg"][:50],
                                              ("SCHEMA:" + r["schemaError"]) if r["schemaError"] else ""))
    print("log schema errors:", log_hits)
    bad = [r for r in results if r["schemaError"] or r["http"] >= 500] or log_hits
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main())
