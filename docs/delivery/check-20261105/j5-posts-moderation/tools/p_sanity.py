"""Seam sanity: library word refused, WeChat busy reaches the real errcode path (proves RigWxOutboundStub is live)."""
from common import *
t = tok("e2e-openid-9003")
for mode, text in (("pass", "桥边 zqvxlm"), ("busy", "干净的帖文 sanity"), ("pass", "干净的帖文 zqvxlmpark sanity")):
    wx_mode(mode)
    r = http("POST", "/api/club/post/create", token=t, json_body={"clubId": 7001, "content": text, "postType": "post"})
    print(mode, text, "->", r.get("code"), r.get("msg"))
wx_mode("pass")
