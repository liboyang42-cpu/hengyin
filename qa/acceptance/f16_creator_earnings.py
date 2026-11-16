"""F16 Creator earnings: creator center -> club settlement summary -> balance list (income) -> wallet stages -> merchant finance."""
from harness import *


def flow(f):
    c = CLUB_LEADER
    f.step("creator/center", "POST", "/api/creator/center", c)
    f.step("club/settlement/summary club 7001", "POST", "/api/club/settlement/summary", c, js={"clubId": CLUB}, check=ok_data)
    f.step("user/balance/list income", "POST", "/api/user/balance/list", c, form={"pageNum": 1, "pageSize": 20},
           check=lambda j: None if isinstance(data_of(j), dict) and "rows" in data_of(j) else "data.rows missing")
    f.step("balance/list", "POST", "/api/balance/list", c,
           check=lambda j: None if isinstance(data_of(j), list) else "data not a list")
    f.step("wallet/stages (JSON)", "POST", "/api/wallet/stages", c, js={}, check=ok_data)
    f.step("merchant/finance/overview", "POST", "/api/merchant/finance/overview", MERCHANT, js={}, check=ok_data)
    f.step("merchant/finance/settlement-entries", "POST", "/api/merchant/finance/settlement-entries", MERCHANT,
           js={"source": "all", "pageNum": 1, "pageSize": 20}, check=ok_data)
    f.step("player cannot read merchant finance", "POST", "/api/merchant/finance/overview", PLAYER, js={},
           expect_code=None, check=lambda j: None if j.get("code") != 200 else "player read merchant finance")


flow.FLOW = ("F16", "Creator earnings")
if __name__ == "__main__":
    main(flow)
