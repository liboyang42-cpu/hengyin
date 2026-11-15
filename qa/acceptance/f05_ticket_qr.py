"""F05 Ticket QR: ticket wallet list -> ticket detail -> dyncode/issue. Uses 9009's seeded registration (E2E_REG_9009, default 74)."""
from harness import *


def flow(f):
    m = PLAYER2
    reg = int(os.environ.get("E2E_REG_9009", "74"))
    f.step("registration/list ticket wallet", "POST", "/api/registration/list", m, form={"owner_type": 2},
           check=lambda j: None if str(reg) in str(data_of(j)) else "registration not in wallet")
    # ticket_list in activity_api.dart:325 reads body['data'] as List; backend sends {total,rows}
    f.step("wallet shape matches app (data is a list)", "POST", "/api/registration/list", m, form={"owner_type": 2},
           check=lambda j: None if isinstance(data_of(j), list) else "data is %s, app expects List" % type(data_of(j)).__name__,
           owner="app")
    f.step("registration/info ticket detail", "POST", "/api/registration/info", m, form={"id": reg},
           check=lambda j: None if (data_of(j) or {}).get("registrationStatus") == 2 else "registrationStatus != 2")
    q = f.step("verify/dyncode/issue", "POST", "/api/verify/dyncode/issue", m, form={"registrationId": reg},
               check=lambda j: None if str((data_of(j) or {}).get("code", "")).startswith("v1.") else "code not v1.*")
    f.step("dyncode/issue for someone else's order is refused", "POST", "/api/verify/dyncode/issue", PLAYER,
           form={"registrationId": reg}, expect_code=None,
           check=lambda j: None if j.get("code") != 200 else "other member got a QR for 9009's ticket")


flow.FLOW = ("F05", "Ticket QR")
if __name__ == "__main__":
    main(flow)
