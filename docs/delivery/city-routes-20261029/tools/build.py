"""由 routes_data.py 生成：
  routes/<id>/route.json   机器可读的完整内容
  routes/<id>/route.md     人读版（含【史实】来源、坐标、玩法配置）
  validation/configs.json  交给 validator/validate.js 与 run_java_validator.sh 的输入
用法：python3 build.py   （在 tools/ 下运行）
"""
import json, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from coords import gcj02_to_wgs84, haversine  # noqa: E402
from routes_data import ROUTES  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
PRECISION = {
    "repo-seed": "取自仓库既有种子（chengyinhub-admin/sql/migration_seed_*），精度未知，±50 m 内",
    "est-80": "按地址与相邻已知点人工推估，误差可能 30–80 m",
    "est-100": "按地址与相邻已知点人工推估，误差可能 50–100 m",
    "est-300": "仅知门牌，未能取得地图数据，误差可能达 300 m；必须以地图选点为准",
}
WALK = 1.3  # 无法访问 OSRM：步行距离 = 直线 × 1.3


def chars(s):
    return len((s or "").replace("\n", ""))


def build_route(r):
    st = {s["id"]: s for s in r["stations"]}
    order = [sid for ch in r["chapters"] for sid in ch["stations"]]
    prev, total = None, 0.0
    for sid in order:
        s = st[sid]
        s["wgs84"] = list(gcj02_to_wgs84(*s["gcj02"]))
        line = haversine(prev["gcj02"], s["gcj02"]) if prev else 0.0
        s["from_prev_line_m"] = round(line)
        s["from_prev_walk_m"] = round(line * WALK)
        total += line * WALK
        s["cum_walk_m"] = round(total)
        s["arrive_chars"] = chars(s["arrive"])
        prev = s
    r["theme"]["distance_km_est"] = round(total / 1000, 1)
    r["theme"]["price"] = 0
    r["theme"]["ticket_inventory"] = 0
    r["distance_method"] = "直线距离 × 1.3（本环境无法访问 OSRM / OSM，步行距离为估算）"
    return r


def md(r):
    t = r["theme"]
    out = [f"# 《{t['name']}》", "", f"**主题描述**：{t['subtitle']}", "",
           f"- 估算步行：约 {t['distance_km_est']} km（{r['distance_method']}）；建议用时 {t['duration_min']} 分钟；难度：{t['difficulty']}",
           f"- 完成勋章：{t['medal']}（编辑器里只有俱乐部负责人可改勋章名，见 publish-guide）",
           f"- 价格 0 元；票数 0；可玩时段：{t['play_time']}",
           f"- 标签（编辑器无此字段，写进简介即可）：{'、'.join(t['tags'])}", "",
           "## 剧情详情（主题简介，原文照填）", "", "```text", t["description"], "```", "",
           f"字数：{chars(t['description'])}（上限 1000）", ""]
    st = {s["id"]: s for s in r["stations"]}
    for ch in r["chapters"]:
        out += [f"## {ch['name']}", "", ch["summary"], ""]
        for sid in ch["stations"]:
            s = st[sid]
            g = s["game"]
            out += [f"### {sid} {s['name']}", "",
                    f"- 地址：{s['address']}",
                    f"- 地图选点搜索词：`{s['poi']}`",
                    f"- 坐标 GCJ-02（估值）：`{s['gcj02'][0]}, {s['gcj02'][1]}`｜WGS84：`{s['wgs84'][0]}, {s['wgs84'][1]}`｜精度：`{s['precision']}`（{PRECISION[s['precision']]}）",
                    f"- 距上一站：直线 {s['from_prev_line_m']} m，步行约 {s['from_prev_walk_m']} m；累计约 {s['cum_walk_m']} m",
                    f"- 开放与注意：{s['access']}｜时段：{s['hours']}", "",
                    f"**到达文字**（故事流中放在本站之前的文字块，{s['arrive_chars']} 字）", "", "```text", s["arrive"], "```", "",
                    f"**玩法**：{g['label']}（{g['picker_path']}）；模板名 `{g['template_name']}`；玩法看点：{g['highlight']}；参与方式：定位到达", "",
                    "| 编辑器字段 | 填写 |", "|---|---|"]
            out += [f"| {k} | {v} |" for k, v in g["ui_fields"]]
            out += ["", "advanced_config（编辑器保存后应得到的配置，已校验）：", "", "```json",
                    json.dumps(g["config"], ensure_ascii=False), "```", "",
                    "**完成反馈**（结果反馈 → 成功反馈／完成反馈 → 回复一句）", "", "```text", g["pass"], "```", ""]
            if g.get("fail"):
                out += ["**失败反馈**（结果反馈 → 失败反馈 → 回复一句）", "", "```text", g["fail"], "```", ""]
            out += ["**【史实】来源**", ""]
            out += [f"- {fact} ｜ " + " ｜ ".join(urls) for fact, urls in s["facts"]]
            out += ["", "**配图候选**（Commons，下载与许可核验见 images/）：" + "；".join(s["images"]), ""]
        if ch.get("outro"):
            out += ["**章末文字**（故事流中放在本章最后一站之后的文字块）", "", "```text", ch["outro"], "```", ""]
    return "\n".join(out) + "\n"


def main():
    configs = {}
    for r in ROUTES:
        r = build_route(r)
        d = os.path.join(ROOT, "routes", r["id"])
        os.makedirs(d, exist_ok=True)
        json.dump(r, open(os.path.join(d, "route.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
        open(os.path.join(d, "route.md"), "w", encoding="utf-8").write(md(r))
        for s in r["stations"]:
            cfg = json.loads(json.dumps(s["game"]["config"]))
            # 编辑器的“结果反馈”会写成 outcomeRules v1；一并校验
            rules = {"version": 1, "pass": {"reply": s["game"]["pass"]}}
            if s["game"].get("fail"):
                rules["fail"] = {"reply": s["game"]["fail"]}
            cfg["outcomeRules"] = rules
            configs[f"{s['id']} {s['game']['template_name']}"] = cfg
        print(r["id"], "distance≈", r["theme"]["distance_km_est"], "km",
              [(s["id"], s["from_prev_walk_m"], s["arrive_chars"]) for s in r["stations"]])
    vd = os.path.join(ROOT, "validation")
    os.makedirs(vd, exist_ok=True)
    json.dump(configs, open(os.path.join(vd, "configs.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
