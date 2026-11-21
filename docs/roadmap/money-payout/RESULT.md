# R6b money-payout: result (hengyin)

Branch `feature/r-money-payout` from `origin/roadmap/be-integ`. App half: chengyin-app `cloud/r-money-payout`.
Second half of brief `r6-money-flows-test.md` (build steps 2 and 3, and the matching part of step 4). The pass and
StoreKit (step 1) belong to money-pass and are untouched.

This run resumed from 81f72a5fd (pushed by the earlier run before its usage stop); nothing was recreated or force-pushed.

With the money switch closed (every production value, and the default everywhere) every surface behaves as today:
the server refuses every new write with `COMMON.FEATURE_OFF`, the mini-program cash-out page opens the same
customer-service popup, the admin queue can only reject, and the app shows no new entry.

## Findings

| ID | State | How |
|---|---|---|
| FLOW-11 (P1) withdrawal cannot start in TEST | **closed** (with the app branch) | A member withdrawal is now a request (`/api/payout/request/*`) reviewed in the admin queue and paid out through the existing WeChat transfer (`CashWithdrawServiceImpl.withdraw`). It needs no bank document: the bank-card form was retired by the owner on 2026-09-16, so the app's old bank context stays as it is and the new flow replaces it. See "Decisions". |
| FLOW-13 (P1) binding a payout receiver needs a mini-program `jsCode` | **closed** | Server-issued, short-lived binding code (`/api/payout/binding/code`), shown as a QR code in the app; the mini-program binding page proves the WeChat with a fresh `wx.login` (`/api/payout/binding/claim`); the app confirms (`/api/payout/binding/confirm`), which ties it to the app account: wallet withdrawals (`cy_member_payout_wechat`) or the club personal receiver (existing `EcommercePersonalReceiverService`, new `bindFromAppCode`). |
| MON-08 (P1) cash-out is a customer-service chat | **closed in TEST** | Mini-program member cash-out (`subpackageMember/tixian`) goes to the new request page when the server switch says withdrawal is open; closed, it opens the same popup. The contact number stays in `chengyinhub-xcx/utils/withdraw-cs.js:8` (a public contact, not a secret) for the closed state and the other five entries. |

Not changed on purpose: the club settlement, merchant profit and co-op withdrawal entries still open the
customer-service popup. Those are third-party sale proceeds; collection-model is moving them to single-seller split
settlement. Nothing here moves third-party sales into a member balance: the request flow only drains
`ums_member.balance` through the existing transfer, and never credits any balance.

## Money rules (fail-closed)

| Rule | Where | Test |
|---|---|---|
| Submit, binding code, claim and confirm refused unless the area is open (wallet: `withdrawal`, club receiver: `payout`); any switch read error is closed | `PayoutRequestService.submit`, `PayoutBindingService.requireOpen`, `PayoutMoneyGate.isOpen` | `closedSwitchRefusesSubmitAndApproveAndSendsNothing`, `closedSwitchRefusesEveryBindingWriteAndCallsNoWechat`, `gateReadsClosedWhenTheSwitchThrows` |
| Approve also needs the transfer's own `withdrawal.fast-withdraw.enabled` (default false) | `PayoutRequestService.approve` | `openSwitchButClosedTransferRefusesApprove`, existing `FastWithdrawDefaultOffTest` |
| A request moves no money; the balance is debited only by the transfer's own atomic debit at payout time; short balance then fails with nothing debited | `PayoutRequestService` | `submitQueuesARequestWithoutMovingMoney`, `approveRecordsATransferRefusalAsFailed` |
| One open request per member; reviews are compare-and-set, so a second reviewer loses and nothing is sent twice | `PayoutRequestMapper.reviewPending` | `secondReviewerLosesAndNothingIsSent` |
| Every decision needs a note and writes an `OpsConsoleAudit` row; reject and member cancel work with the switch closed and move no money | `PayoutRequestService.reject/cancel` | `rejectMovesNoMoneyNeedsANoteAndWorksWithTheSwitchClosed`, `cancelIsOwnerAndPendingOnly` |
| Binding codes: 18 random bytes, stored only as sha256, single use, 60-600 s (default 300), a new code cancels the old; claim proves the openid but binds nothing; confirm only by the issuing account; a WeChat other than the account's own mini-program openid is refused | `PayoutBindingService` | `issueStoresOnlyTheHashAndBuildsTheQrLink`, `claimProvesTheOpenidButBindsNothing`, `expiredOrUnknownCodeIsRefusedBeforeWechat`, `confirm*` |
| Club receiver binding: owner only; existing identity-immutability rules | `PayoutBindingService.requireOwner`, `EcommercePersonalReceiverService.bindFromAppCode` | `clubReceiverCodeNeedsTheOwner`, `confirmBindsTheClubReceiverThroughTheExistingService`, `EcommercePersonalReceiverServiceTest` (21) |
| The member's own mini-program openid always wins over an app-bound one | `CashWithdrawServiceImpl.miniOpenidOrBound` | `TransferChannelResolveTest` (7) |
| Admin permissions granted to no role | migration menu rows + runner assertion | migration `assert=scalar-true` |

## What changed, file by file

Backend (commit 81f72a5fd, earlier run; reviewed and kept):
- `chengyinhub-admin/sql/migration_20261115_money_payout.sql` (new; rollback note at top): `cy_payout_request`,
  `cy_payout_bind_code`, `cy_member_payout_wechat`; menu 「提现审核」 + button under 资金与售后, no role grants.
- `ci/migration-dependencies.json`: registers the migration.
- `chengyinhub-system/.../moneypayout/PayoutMoneyGate.java`, `PayoutRequestService.java`, `PayoutBindingService.java`,
  `mapper/PayoutRequestMapper.java`, `mapper/PayoutBindingMapper.java` (new).
- `chengyinhub-admin/.../controller/api/ApiMoneyPayoutController.java` (new): `GET /api/payout/request/overview`,
  `POST /api/payout/request/submit|cancel`, `POST /api/payout/binding/code|confirm`, `GET /api/payout/binding/status`,
  `POST /api/payout/binding/claim` (`@Anonymous`, rate limited 20/min per IP: the one-time code plus a fresh `wx.login`
  code are the proof, and nothing binds until the app account confirms).
- `chengyinhub-admin/.../controller/business/PayoutRequestAdminController.java` (new): `GET /business/payout-request/list`,
  `POST /business/payout-request/{id}/approve|reject`.
- `chengyinhub-system/.../payout/EcommercePersonalReceiverService.java`: `bindFromAppCode` (shared `completeBind`).
- `ICashWithdrawService.java`, `CashWithdrawServiceImpl.java`: `withdraw(memberId, amount, boundMiniOpenid)` overload.
- Tests: `MoneyPayoutServiceTest` (20), additions to `EcommercePersonalReceiverServiceTest`, `TransferChannelResolveTest`.

This run:
- `CashWithdrawServiceImpl.java`: moved the new helper above `resolveTransferChannel`'s javadoc (it had split it).
- `chengyinhub-ui/src/api/business/payoutRequest.js`, `src/views/business/payoutrequest/index.vue` (new): review queue
  (status filter, amount, member, note, transfer record), approve/reject with a required note; approve disabled with a
  sentence while the area or the transfer channel is off. Existing `cy-page-header` / `cy-panel` / Element components.
- Mini-program:
  - `utils/money-payout.js` (new): switch read (anonymous `GET /api/app/money-switch`, only an explicit `true` opens),
    overview/submit/cancel/claim calls, code and amount parsing.
  - `subpackageMember/tixian/tixian.js|.wxml`: button reads 「申请提现」 and opens the request page only while the switch
    is open; otherwise unchanged 「联系客服提现」 popup.
  - `subpackageMember/payout-request/*` (new): available amount, payout WeChat state, open request with cancel,
    amount + submit, last ten requests with the review note or failure reason.
  - `subpackageMember/payout-bind/*` (new): reads the code from the scanned link (`q`) or `?bc=`, or lets the member type
    it; claims with a fresh `wx.login`; tells the member to confirm in the app.
  - `app.json`: two pages in `subpackageMember`.
  - `tests/unit/money-payout-r6b.test.js` (new, 8); `tests/unit/r10-guest-no-silent-login.test.js`: the binding page is
    added to the reviewed `wx.login` list (it proves the WeChat only; no account is created or signed in).
- `docs/roadmap/money-payout/e2e-test.sh` (new): the TEST end-to-end script below.

## Verification

```
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -pl chengyinhub-admin -am test \
  -Dtest='MoneyPayoutServiceTest,EcommercePersonalReceiverServiceTest,TransferChannelResolveTest,FastWithdrawDefaultOffTest,MoneySwitchServiceTest,*Anonymous*' \
  -Dsurefire.failIfNoSpecifiedTests=false        # EXIT 0: 20 + 21 + 7 + 5 + 13 + 19 + 2, 0 failures
mvn ... -Dtest='*PermitAll*'                      # PermitAllUrlPropertiesPathPatternTest 4/4
bash ci/migration-safety-check.sh                 # 迁移安全门禁:通过
node ci/api-existence-gate.js                     # rc=0
python3 -m pytest -q ci/test_role_permission_contract.py ci/test_migration_gates.py ci/test_migration_ledger.py
                                                  # 227 passed (base origin/roadmap/be-integ: 227 passed)
cd chengyinhub-xcx && node --test tests/unit/money-payout-r6b.test.js   # 8/8
```

Mini-program unit suite: `node --test "tests/unit/**/*.test.js"` hangs on this machine on the base as well, so each
file was run on its own with a 60 s limit (`xargs -P 8`, `timeout 60 node --test <file>`), on this branch and on a
worktree of `origin/roadmap/be-integ`. The 394 files that touch `app.json`, the cash-out page, `withdraw-cs` or
`subpackageMember`: 12 files fail (or time out) on both, the identical list. All 1909 files: 21 fail or time out on
the base and the same 21 on this branch (the one extra failure the first full run found, `blank-value-display-contract`
for a bare `¥{{…}}` on the new request page, was fixed with the shared `money.amount2` formatter and now passes).

## TEST end-to-end script

`docs/roadmap/money-payout/e2e-test.sh` (refuses `chengyinhub.com`). Needs `BASE_URL`, `MEMBER_TOKEN`, `ADMIN_TOKEN`
(and optionally `AMOUNT`, `CLUB_ID`) from the environment; no credential is in the file. It checks, in order:
1. switch closed: submit and binding code are refused, the admin gate reads closed;
2. opens `withdrawal` (needs `CHENGYIN_MONEY_SWITCH_SERVER_CEILING=true` on TEST);
3. wallet binding: code → scan in WeChat (manual step) → status `CLAIMED` → confirm → `walletBound`;
4. submit → second submit refused → approve without note refused → approve → `SENT` (or a clear refusal when
   `withdrawal.fast-withdraw.enabled` is off on TEST);
5. a second request rejected (no money);
6. optional club receiver binding with `payout` open;
7. closes both areas and checks submit is refused again.

## Not verified

- No MySQL here: the migration and the MyBatis annotation SQL were not run against a database.
- No live WeChat: `code2session` and the transfer are stubbed in tests; the real transfer in TEST is step 4 of the script.
- The Vue page and the mini-program pages were not built or opened in a simulator (no `node_modules`, no DevTools).
- The QR scan path needs the owner's mini-program console setting (below); until then the app shows the code as text
  and the member types it into the binding page.

## Owner actions and open questions

1. **Mini-program console**: register 「扫普通链接二维码打开小程序」 for an https prefix you own (for example
   `https://<your-domain>/payout-bind`) that opens `subpackageMember/payout-bind/index`, then set
   `chengyin.payout-binding.qr-link-prefix` to it on TEST. Optional: `chengyin.payout-binding.ttl-seconds` (60-600,
   default 300). A server-generated mini-program URL Link was not built: it needs the mini-program access token at
   runtime; the QR prefix needs no secret.
2. **TEST only**: `CHENGYIN_MONEY_SWITCH_SERVER_CEILING=true`, open `withdrawal` / `payout` in 资金开关, and
   `withdrawal.fast-withdraw.enabled=true` with the TEST merchant transfer configuration (from server configuration).
3. Grant `business:payoutRequest:list` / `:review` to named people only.
4. Single-request limit is ¥20,000 (`PayoutRequestService.MAX_AMOUNT`) and the minimum is the existing transfer minimum.
   Confirm or change before "money on".
5. The old bank-card withdrawal (`/api/withdrawal/create`, `ums_member_withdrawal`) still exists server-side for the
   mini-program's history list; it is not used by either client for new requests. Decide whether to retire it.
6. Creator earnings: the creator-earnings ledger records a creator's money share as `HELD_MONEY_OFF` /
   `PENDING_PAYOUT` and does not credit `ums_member.balance`, so this withdrawal does not pay it yet. Crediting it into
   the balance (or a separate payout from the ledger) is an owner decision; nothing here moves it.
7. Legal: payout terms for withdrawals to WeChat change need the owner's legal text before "money on".
8. No hard-coded secret was found in the files touched. The customer-service WeChat id in
   `chengyinhub-xcx/utils/withdraw-cs.js:8` is a public contact, kept as is.
