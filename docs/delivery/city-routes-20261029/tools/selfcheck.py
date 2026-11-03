"""发布前自检：玩家可见文字里不能有网址、不能有英文；字数上限；【史实】段都有来源。在 tools/ 下运行。"""
import json, os, re, sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
LIMITS = {"name": 30, "subtitle": 30, "description": 1000, "station": 40, "reply": 2000, "text": 5000}
ID_KEYS = {"id", "mode", "answerOrder", "pairs", "answer"}  # 内部标识，不给玩家看
ok = True


def visible_cfg(o, out):
    if isinstance(o, dict):
        for k, v in o.items():
            if k not in ID_KEYS:
                visible_cfg(v, out)
    elif isinstance(o, list):
        for v in o:
            visible_cfg(v, out)
    elif isinstance(o, str):
        out.append(o)


for rid in ["commercial-press", "post"]:
    r = json.load(open(os.path.join(ROOT, "routes", rid, "route.json"), encoding="utf-8"))
    t = r["theme"]
    texts = [t["name"], t["subtitle"], t["description"]]
    checks = [("主题名称", len(t["name"]) <= LIMITS["name"]), ("主题描述", len(t["subtitle"]) <= LIMITS["subtitle"]),
              ("剧情详情", len(t["description"]) <= LIMITS["description"])]
    for ch in r["chapters"]:
        texts += [ch["name"], ch["summary"]] + ([ch["outro"]] if ch.get("outro") else [])
    for s in r["stations"]:
        g = s["game"]
        texts += [s["name"], s["arrive"], g["pass"], g["template_name"], g["highlight"]] + ([g["fail"]] if g.get("fail") else [])
        visible_cfg(g["config"], texts)
        checks += [(f"{s['id']} 站名≤40", len(s["name"]) <= LIMITS["station"]),
                   (f"{s['id']} 反馈≤2000", len(g["pass"]) <= LIMITS["reply"]),
                   (f"{s['id']} 到达文字≤5000", len(s["arrive"]) <= LIMITS["text"]),
                   (f"{s['id']} 史实有来源", ("【史实】" not in s["arrive"] and "【史实】" not in g["pass"]) or any(u for _, u in s["facts"]))]
    bad = [x for x in texts if re.search(r"https?://|[A-Za-z]", x)]
    checks.append(("玩家可见文字无网址、无英文", not bad))
    checks.append(("开头声明游戏设定", "【游戏设定】" in t["description"]))
    for name, passed in checks:
        ok &= passed
        print(f"[{'OK ' if passed else 'BAD'}] {rid} {name}")
    for x in bad:
        print("   ->", x[:80])
sys.exit(0 if ok else 1)
