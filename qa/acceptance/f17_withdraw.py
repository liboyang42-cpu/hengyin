"""F17 Withdraw (TEST): list -> consent -> preflight -> confirm -> create -> list -> delete (undo).
The app itself only reads withdrawal/list and wallet/stages (R10: withdrawals are offline). Needs a TEST balance
>= E2E_WITHDRAW_AMOUNT on 9003 (default 0.01). Bank details are obvious TEST placeholders."""
from harness import *


def flow(f):
    m = CLUB_LEADER
    amt = os.environ.get("E2E_WITHDRAW_AMOUNT", "0.01")
    f.step("withdrawal/list", "POST", "/api/withdrawal/list", m,
           check=lambda j: None if isinstance(data_of(j), list) else "data not a list")
    f.step("wallet/stages", "POST", "/api/wallet/stages", m, js={}, check=ok_data)
    f.step("consent bank_account_collection", "POST", "/api/compliance/consents", m,
           js={"docType": "bank_account_collection", "scene": "withdrawal", "eventType": "AGREE", "requestId": rid("w")})
    bank = {"amount": amt, "realname": "测试主理人", "bankName": "TEST BANK", "bankAccount": "6222000000000000000",
            "mobilephone": PHONE[m]}
    pf = f.step("fund/preflight/bank-withdrawal", "POST", "/api/fund/preflight/bank-withdrawal", m,
                js=dict(bank, requestId=rid("pf")), check=lambda j: need(j, "challengeId", "challengeToken"))
    d = data_of(pf) or {}
    f.step("fund/preflight confirm", "POST", "/api/fund/preflight/%s/confirm" % d.get("challengeId"), m,
           js={"challengeToken": d.get("challengeToken")})
    c = f.step("withdrawal/create", "POST", "/api/withdrawal/create", m,
               js=dict(bank, withdrawalAmount=amt, challengeId=d.get("challengeId"), requestId=rid("wc")),
               check=lambda j: None if isinstance(data_of(j), int) else "no withdrawal id")
    wid = data_of(c)
    f.step("withdrawal/list has pending row", "POST", "/api/withdrawal/list", m,
           check=lambda j: None if str(wid) in str(data_of(j)) else "new withdrawal not listed")
    f.step("withdrawal/delete (undo)", "POST", "/api/withdrawal/delete", m, form={"id": wid},
           check=lambda j: None if j.get("msg") == "撤回成功" else "msg=%s" % j.get("msg"))
    f.step("withdraw over balance refused with clear msg", "POST", "/api/withdrawal/create", m,
           js=dict(bank, amount="99999999", withdrawalAmount="99999999", challengeId=d.get("challengeId"),
                   requestId=rid("wx")), expect_code=None,
           check=lambda j: None if j.get("code") != 200 and "稍后重试" not in (j.get("msg") or "") else
           "over-balance gives generic/accepted response: %s" % j.get("msg"))


flow.FLOW = ("F17", "Withdraw (TEST)")
if __name__ == "__main__":
    main(flow)
