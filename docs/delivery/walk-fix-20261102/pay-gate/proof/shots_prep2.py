"""Round 3D screenshot prep 2 (rig only): club 7002's current 主理人 9004 opens a session on rig theme 990014 starting
in about 5 hours, for the within-24 h confirm shot. Prints the activity id."""
import datetime
from rig_env import http, app_token, show, j
tok, _ = app_token("13900000004")
st = http("POST", "/api/publisher/identity/status", token=tok, json_body={})
if not (st.get("data") or {}).get("registered"):
    st = http("POST", "/api/publisher/identity", token=tok, json_body={
        "realName": "测试新主理人", "idCard": "110101199001010058", "consent": True, "source": "topic_publish"})
show("identity 9004", j(st, 160))
at = datetime.datetime.utcnow() + datetime.timedelta(hours=13)
at = at.replace(minute=(at.minute // 5) * 5, second=0, microsecond=0)
r = http("POST", "/api/club/open-session", token=tok, json_body={"clubId": 7002, "topicId": 990014,
         "sessionDate": at.strftime("%Y-%m-%d"), "sessionTime": at.strftime("%H:%M")})
show("open-session 990014 %s" % at.strftime("%Y-%m-%d %H:%M"), j(r))
