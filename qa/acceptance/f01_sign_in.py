"""F01 Sign-in: sms/send -> login/phone -> userInfo -> user/info; WeChat login with an e2e-code test code."""
from harness import *


def flow(f):
    m = PLAYER
    phone = PHONE[m]
    f.step("sms/send", "POST", "/api/sms/send", form={"phone": phone}, expect_code=(200, 500))  # 500 = 60 s limit on rerun
    f.step("login/phone bad code rejected", "POST", "/api/login/phone", form={"phone": phone, "code": "000000"},
           expect_code=500)
    tok = token(m)  # real sms/send + login/phone via harness code source
    f.step("userInfo (top-level appUser.role)", "POST", "/api/userInfo", m,
           check=lambda j: None if (j.get("appUser") or {}).get("role") == "player" else "appUser.role != player")
    f.step("user/info", "POST", "/api/user/info", m,
           check=lambda j: None if (data_of(j) or {}).get("role") == "player" else "data.role != player")
    f.step("userInfo club leader role", "POST", "/api/userInfo", CLUB_LEADER,
           check=lambda j: None if (j.get("appUser") or {}).get("role") == "club" else "9003 role != club")
    f.step("userInfo merchant role", "POST", "/api/userInfo", MERCHANT,
           check=lambda j: None if (j.get("appUser") or {}).get("role") == "merchant" else "9002 role != merchant")
    # The e2e-test WeChat stub is wired only into IWeChatMiniSessionService.exchange, which /api/login/code
    # does not use (it calls jscode2session directly). Expected to fail on TEST until the login path uses it.
    f.step("login/code with e2e-code test code", "POST", "/api/login/code", form={"code": "e2e-code-qa%s" % RUN_ID[-6:]},
           check=lambda j: None if j.get("token") else "no token")
    f.step("userInfo without token -> 401", "POST", "/api/userInfo", expect_code=(401,), expect_http=None)


flow.FLOW = ("F01", "Sign-in")
if __name__ == "__main__":
    main(flow)
