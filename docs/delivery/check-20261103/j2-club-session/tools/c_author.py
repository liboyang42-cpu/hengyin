"""C (API leg, labelled): the lead authors the theme through the same endpoints the editor posts to.
Editor screens up to the 主题详情 sheet were walked in the UI (shots 28-31); the remaining editor input
pop-ups are post-launch by owner decision and the cover upload cannot run in the rig (no OSS): HARNESS.
Gameplay templates: the lead publishes three text/choice templates, then the theme's stations use them."""
from common import *
tok, me = login('j2-lead-a'); print('lead', me.get('id'))
st = cl.http("POST", "/api/publisher/identity/status", token=tok, json_body={})
print('identity status', J(st))
if not (st.get('data') or {}).get('registered'):
    print('register', J(cl.http("POST", "/api/publisher/identity", token=tok, json_body={"realName": "林小舟", "idCard": idcard('31010419900307002'), "consent": True, "source": "topic_publish"})))
G = [("喷泉的水柱", 1, "人民广场音乐喷泉中央最高的那根水柱旁，有几盏地灯围成一圈？（填数字）", "8", None, None, "站在喷泉南侧看。", "比 6 多。", "8 盏。", "喷泉记住了你们。"),
     ("博物馆的鼎", 3, "上海博物馆的建筑外形像哪一件古代器物？", None, ("铜鼎", "玉璧", "瓷瓶", "编钟"), "A", "抬头看整栋楼的轮廓。", "圆顶方座。", "铜鼎，天圆地方。", "答对了，下一站往东。"),
     ("大世界的塔楼", 1, "大世界转角塔楼一共有几层？（填数字）", "4", None, None, "站在西藏南路对面数。", "不算尖顶。", "4 层。", "终点到了，记得合影。")]
tids = []
for g in G:
    title, vm, qn, ans, ch, corr, h1, h2, rev, fb = g
    body = {"title": title, "description": qn, "validationMethod": vm, "questionName": qn, "hint1": h1, "hint2": h2,
            "answerReveal": rev, "feedbackText": fb, "duration": 8, "difficulty": "普通", "players": "2-4人", "usageLocation": "户外"}
    if vm == 1: body["questionAnswer"] = ans
    if vm == 3: body.update({"questionA": ch[0], "questionB": ch[1], "questionC": ch[2], "questionD": ch[3], "correctAnswer": corr})
    r = cl.http("POST", "/api/template/publish", token=tok, json_body=body)
    print('template', title, J(r)[:200])
    d = r.get('data'); tids.append(d if isinstance(d, int) else (d or {}).get('id') if isinstance(d, dict) else None)
print('templates', tids)
save(templates=tids)
