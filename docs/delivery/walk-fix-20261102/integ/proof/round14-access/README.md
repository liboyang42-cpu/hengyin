# Historical-earnings authored business replay

**Prepared, not run against the rig.** No runtime-ready signal has arrived. The only execution so far is `node replay.mjs --self-check`, which uses built-in assertions and performs no browser, login, network, governance or funds action.

The executable `replay.mjs` reuses the existing rig `walk.mjs`; it adds no dependency, starts no server, and does not change product code, SQL, dates, earnings, roles or transfer records directly. It never calls a withdrawal/payment/refund endpoint. The only business writes are two transfers performed through the normal rendered governance picker, reason field and confirmation, after all income prerequisites are read back. Transfer IDs come from the actual HTTP response. It transfers **9014 → 9015 → 9014**, so **9016 remains the independently authenticated stranger throughout**, and it reauthenticates through the normal rig login after each transfer.

## Required preparation by the runtime/fixture owner

1. Wait for root's explicit runtime-ready signal and exact candidate SHA. Root still owns the runtime; do not start/restart a backend or tunnel.
2. Use normal authored business/settlement flows to give both allocated members 9014 and 9015 distinct historical receivables/earnings for the same task-owned club. Initially 9014 must be its current owner and 9015 an eligible normal member. A genuine end+14-day hold must not be bypassed. If that fixture does not exist, this script records the exact missing prerequisite and stops before governance mutation; it does not manufacture history.
3. Supply `ACCESS_CLUB_ID` from that normal club-creation/earnings receipt. The script independently requires that exact ID to appear in 9014's real `/settlement/mine` response. Supply an independently returned, different `ACCESS_OTHER_CLUB_ID` for the cross-club refusal case; otherwise that case stays unverified and the run exits 2.
4. Actor identities are fixed to normal `e2e-openid-9014`, `e2e-openid-9015`, `e2e-openid-9016` rig login. The returned authenticated member IDs must independently equal 9014/9015/9016. No JWT/global identity is injected by this script.

## Run

Set the following in the coordinator's existing environment with real observed values:

| Variable | Required value |
| --- | --- |
| `ACCESS_RUNTIME_READY` | `1`, only after root's readiness signal |
| `ACCESS_READY_SHA` | Actual full 40-character ready backend source SHA |
| `ACCESS_CLUB_ID` | Actual club ID returned by normal authored fixture flow |
| `ACCESS_OTHER_CLUB_ID` | Actual other-club ID, verified again through authenticated detail |
| `RIG_WEB` | `http://127.0.0.1:18197` |
| `RIG_WALK_MODULE` | `/private/tmp/cy-r14-rig-1105/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs` |
| `ACCESS_PROOF_DIR` | An absolute private path outside both public checkouts, e.g. `/private/tmp/cy-r14-access-proof-1105` |

From the checkout, run:

```sh
node docs/delivery/walk-fix-20261102/integ/proof/round14-access/replay.mjs --self-check
node docs/delivery/walk-fix-20261102/integ/proof/round14-access/replay.mjs
```

Before any transfer the script independently reads authenticated actuator source through the rig proxy. If the source is absent, denied, still old, or mismatches the ready SHA, it stops. A denied actuator read identifies a missing source-verification seam, not a product failure or permission to bypass it.

## Readback and limits

The replay starts at account earnings, discovers 更多 → 本人俱乐部结算, follows only server-returned club/settlement IDs, and opens the real detail page. Normal governance is entered through club detail → settings → 成员治理. The first transfer is independently read back using 9015's access context, and 9014 must lose club finance/governance authority while retaining only its own money rows. Both personal amount/row snapshots must remain unchanged across both transfers. Stranger, missing/zero/negative scopes and caller-supplied payee/member spoofing are tested with real authenticated read calls.

Private `readback.json` contains only allowlisted source/actor/club IDs, transfer receipts, own amount/status row snapshots and permission codes. No tokens, login stores, raw network dumps, openids or environment secrets are written. Screenshots stay private. All launched browser sessions are closed in `finally`; cleanup failure is a failed run. Review any produced screenshots before calling the UI portion checked.

Exit 0 / `VERIFIED_UI_HTTP_ONLY` covers only the declared UI/HTTP checks. Exit 2 / `UNVERIFIED_PREREQUISITE` or `PARTIAL_UI_HTTP` is not PASS. Exit 1 marks a failed assertion, transport/cleanup failure or invalid setup. No result proves a real payment, customer-service case completion, real phone, external provider availability or independent database/ledger reconciliation; those remain explicitly unverified.
