"""F05 Ticket QR: ticket wallet list -> ticket detail -> dyncode/issue, for 9001's fresh free registration on the run's
live QA activity (fixtures.py: E2E_REG_9001_FREE). The wallet is decoded the way the native client does it
(TicketWalletService.RouteEnvelope: data is {total, rows}, or a bare list)."""
from harness import *


def wallet_rows(j):
    d = data_of(j)
    if isinstance(d, list):
        return d
    if isinstance(d, dict) and isinstance(d.get("rows"), list):
        return d["rows"]
    return None


def flow(f):
    m = 9001
    reg = os.environ.get("E2E_REG_9001_FREE")
    if not reg:
        raise Blocked("no fixture registration (run fixtures.py first)")
    f.step("registration/list ticket wallet (owner_type 2)", "POST", "/api/registration/list", m, form={"owner_type": 2},
           check=lambda j: None if wallet_rows(j) is not None and any(str(x.get("id")) == reg for x in wallet_rows(j))
           else "registration not in wallet rows")
    f.step("wallet decodes as the native client does ({total, rows})", "POST", "/api/registration/list", m,
           form={"owner_type": 2},
           check=lambda j: None if isinstance(data_of(j), dict) and isinstance((data_of(j) or {}).get("total"), int)
           and isinstance((data_of(j) or {}).get("rows"), list) else "data is not {total, rows}")
    f.step("registration/info ticket detail", "POST", "/api/registration/info", m, form={"id": reg},
           check=lambda j: None if str((data_of(j) or {}).get("id")) == reg and (data_of(j) or {}).get("registrationStatus") == 2
           else "detail id/registrationStatus mismatch")
    f.step("verify/dyncode/issue", "POST", "/api/verify/dyncode/issue", m, form={"registrationId": reg},
           check=lambda j: None if str((data_of(j) or {}).get("code", "")).startswith("v1.") else "code not v1.*")
    f.step("dyncode/issue for someone else's order is refused", "POST", "/api/verify/dyncode/issue", PLAYER,
           form={"registrationId": reg}, expect_code=None,
           check=lambda j: None if j.get("code") != 200 and j.get("msg") else "not refused")  # still code 500, see REPORT B9


flow.FLOW = ("F05", "Ticket QR")
if __name__ == "__main__":
    main(flow)
