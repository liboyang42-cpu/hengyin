"""qx-c1 #3 real HTTP: GET /api/play/advanced/state for a player-owned session whose frozen config has QA option images
and accusation suspect avatars stored as media-asset references. The app runs with APP_MEDIA_PUBLIC_BASE_URL set
(rig-only config) so APPROVED local images have a public URL. Fixture rows by SQL; the read is the real endpoint."""
import json, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
import rigkit as r

OUT = Path(__file__).resolve().parents[1] / "proof" / "rig-http-media.json"
T4 = r.app_token("e2e-openid-9004")
KEY_OK = "/profile/upload/2026/11/09/" + "a" * 32 + ".png"
KEY_PENDING = "/profile/upload/2026/11/09/" + "b" * 32 + ".png"
r.x("delete from app_media_asset where id in (501,502)")
r.x("insert into app_media_asset(id,member_id,object_key,media_type,review_state,storage_type,create_time,update_time) values(501,9003,%s,'IMAGE','APPROVED','LOCAL',now(),now()),(502,9004,%s,'IMAGE','PENDING','LOCAL',now(),now())", KEY_OK, KEY_PENDING)
config = {"schemaVersion": 1, "qa": {"enabled": True, "mode": "PICK", "title": "谁是凶手", "options": [
    {"id": "o1", "label": "张医生", "correct": True, "img": "media-asset:501", "alt": "穿白大褂的人"},
    {"id": "o2", "label": "李师傅", "img": "media-asset:502", "alt": "待审的图"},
    {"id": "o3", "label": "周老板"}]},
    "accusation": {"enabled": True, "suspects": [
        {"id": "o1", "name": "张医生", "avatar": "media-asset:501"},
        {"id": "o2", "name": "李师傅", "avatar": "media-asset:502"}, {"id": "o3", "name": "周老板"}],
        "winScreen": {"title": "真相大白", "body": "你找到了他"}, "loseScreen": {"title": "再想想", "body": "证据不足"}}}
r.x("delete from game_runtime_session where id=660001")
r.x("insert into game_runtime_session(id,activity_id,topic_id,node_id,template_id,owner_type,owner_id,config_snapshot_json,state_json,status,version,score,started_at,create_time,update_time) values(660001,0,990013,880001,7701,1,9004,%s,'{}',0,0,0,now(3),now(3),now(3))", json.dumps(config, ensure_ascii=False))
view = r.call("GET", "/api/play/advanced/state?sessionId=660001", T4, label="#3 runtime session view")
r.note("#3 read-back assets", r.q("select id,review_state,storage_type,object_key from app_media_asset where id in (501,502)"))
data = view.get("data") or {}
opts = (((data.get("playKit") or {}).get("qa") or {}).get("options")) or []
sus = ((data.get("accusation") or {}).get("suspects")) or []
dumped = json.dumps(view, ensure_ascii=False)
ok = (view.get("code") == 200 and opts and opts[0].get("img", "").endswith(KEY_OK) and opts[0].get("img", "").startswith("https://")
      and opts[0].get("alt") == "穿白大褂的人" and "img" not in opts[1] and "media-asset:" not in dumped and "correct" not in dumped
      and sus and sus[0].get("avatar", "").endswith(KEY_OK) and "avatar" not in sus[1])
r.note("summary", {"#3": {"ok": ok, "options": opts, "suspects": sus}})
print("PASS" if ok else "FAIL", json.dumps({"code": view.get("code"), "msg": view.get("msg"), "options": opts, "suspects": sus}, ensure_ascii=False)[:900])
r.dump(OUT)
