"""qx-c1 #5(b) real HTTP: save a draft theme whose scene carries nodeArrivals for a node created in the same request
(client:<key>), then read back cms_topic.theme_runtime_config_json: the key is remapped to the real node id, values are
normalized, and nodeConfigs (the node template override) is not created."""
import json, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
import rigkit as r

OUT = Path(__file__).resolve().parents[1] / "proof" / "rig-http-arrivals.json"
T4 = r.app_token("e2e-openid-9004")
TOPIC = r.q("select id from cms_topic where member_id=9004 order by id desc limit 1")[0]["id"]
detail = r.call("POST", "/api/topic/edit-detail", T4, form={"id": TOPIC}, label="#5b edit-detail")["data"]
topic = detail["topic"]
theme = {"schemaVersion": 1, "currentScene": "s1", "scenes": [{"id": "s1", "name": "第一幕", "nodeArrivals": {
    "client:bell": {"arrivalPolicy": {"radiusMeters": 60, "maxAccuracyMeters": 40, "maxAgeSeconds": 120},
                    "arrivalReading": {"level": 2, "log": "你听见钟声"}}}}]}
chapter = {"name": "第一章", "nodes": [{"clientNodeKey": "bell", "name": "钟楼", "address": "上海市黄浦区",
                                      "longitude": "121.47", "latitude": "31.23", "sortID": 1}]}
body = {"id": TOPIC, "name": topic.get("name"), "description": topic.get("description") or "qx-c1",
        "chapters": (detail.get("chapters") or []) + [chapter], "themeRuntimeConfig": json.dumps(theme, ensure_ascii=False),
        "saveDraft": 1, "requestId": "qxc1-arrivals-0001"}
saved = r.call("POST", "/api/topic/update", T4, body=body, label="#5b save draft with nodeArrivals")
node = r.q("select id from cms_topic_node where topic_id=%s and name='钟楼' order by id desc limit 1", TOPIC)
stored = r.q("select theme_runtime_config_json j from cms_topic where id=%s", TOPIC)[0]["j"]
r.note("#5b read-back", {"node": node, "theme": stored})
scene = json.loads(stored)["scenes"][0] if stored else {}
nid = str(node[0]["id"]) if node else None
row = (scene.get("nodeArrivals") or {}).get(nid or "-", {})
ok = saved.get("code") == 200 and nid and row.get("arrivalPolicy", {}).get("fallbackMode") == "NONE" \
     and row.get("arrivalReading", {}).get("level") == 2 and "nodeConfigs" not in scene and "client:bell" not in stored
r.note("summary", {"#5b": ok})
print("PASS" if ok else "FAIL", saved.get("code"), saved.get("msg"), nid, json.dumps(scene, ensure_ascii=False)[:400])
r.dump(OUT)
