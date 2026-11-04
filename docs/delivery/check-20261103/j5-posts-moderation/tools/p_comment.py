"""Theme review (/api/comment/add, ums_comment) over HTTP as player 9009 on theme 990014 (screen entry did not open in the rig)."""
import json
from common import *
p = tok("e2e-openid-9009")
for t, img in (("路线里 zqvxlm 很有趣", None), ("路线里 z.q.v.x.l.m 很有趣", None), ("路线里 zqvxlmpark 很有趣", None), ("外链图片评价", "https://example.invalid/x.jpg")):
    form = {"owner_type": "1", "owner_id": "990014", "rating": "5", "contents": t}
    if img: form["img_arr"] = img
    r = http("POST", "/api/comment/add", token=p, form=form)
    print(t, "->", r.get("code"), r.get("msg"), "rows", q("SELECT COUNT(*) FROM ums_comment WHERE contents=%s", (t,))[0][0])
