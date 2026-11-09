# Round 12, lane k11-platform-clawback: result

Branch `integ/launch-test-20261103`, private local branch, `pull --rebase` before every push, no force-push.
Code commit: `66256f2d` (fix + tests + gate registration).

**Not a phone, and not a rig walk.** Proof is unit tests and real MySQL 8.0.45 ITs in this container (Docker started;
`mysql:8.0.45` on loopback). I did not bring up the full rig or drive the app over HTTP. No screens changed, so there
are no screenshots.

## The rule as built

Owner, 2026-11-05: on a refund after settlement the platform pays back its share too, so every part returns in the same
proportion. Percentage terms on club themes stay as they are (no invite block added).

- The refund clawback (`RefundServiceImpl`, `allocateClawback` + `createSettlementAdjustment`) already wrote a negative
  ledger line for the platform. It never touched a balance, because the platform's ledger line has `receiver_id = 0`.
- Now, when the activity was really paid out (`cms_activity.is_settlement = 1`, the same check that already gates the
  merchant debit), the platform's part is debited from **the member that actually received the platform share**: the
  member with positive `event_type = 7` income for that activity (`CmsActivityServiceImpl` pays the platform share to
  `cms_setting.platform_member_id` as event 7). If someone changes the platform account later, the debit still goes to
  the account that got the money.
- Same debt rule as the other payees (`debitReceiverBalance`): take what is there; record the rest in
  `ums_member_settlement_debt` with source `refund:platform`; alarm 「退款冲销扣款不足」.
- The platform's debt is now offset from its next platform share, like the 主理人's and merchant's
  (`settlementDebtLedger.offsetFromSettlement` after the platform payment in `settleActivity` and in the complaint
  release `releaseHeldRegistration`).
- No double debit on retry: the adjustment's idempotency key `refund:<id>:platform:0` is unchanged; a second run inserts
  nothing and returns before any debit. The debt row is insert-ignore on the same source.
- If an activity is settled but no platform income is found (platform rate 0, or very old data), nothing is debited and
  an alarm 「退款冲销平台份额无实收人」 is raised. The ledger line is still written.
- The parts still come from the one `allocateClawback` split, so platform + 主理人 + merchant = refund, to the fen.

## Per path

| Path | Platform share credited to a balance at settlement? | After this change | Status |
|---|---|---|---|
| Activity (not a club session) | Yes, event 7 | Debited from that member | Fixed; unit test |
| Club session, no merchant fee | Yes, event 7 | Debited | Fixed; real MySQL IT |
| Club session with merchant fee (k5) | Yes, event 7 | Debited; merchant `coop_merchant` part as k5 built | Fixed; real MySQL IT, k5's numbers |
| Complaint-held registration released later | Yes, event 7 (same activity id) | Debited, same code | Covered by the same lookup; no separate test |
| Theme with co-op orders | No. The `PLATFORM` head is an accounting line (`cash_head_kind = ACCOUNTING`, status 1, never paid by `payoutDueCoopSettlements`) | Unchanged: the refund reduces the platform head through the existing original-component reversal | No change needed |
| Theme without co-op, incl. club self-play pass | No. Same accounting line | Unchanged: platform line is ledger-only | No change needed; unit test pins "no member debited" |

So there is no platform balance to take back on theme and co-op paths; the platform's share there never left the
WeChat merchant account as a member balance.

## Screens and admin

- **俱乐部结算** (`selectClubSessionSettlements`): 平台服务费 = event 7 income + the platform's refund ledger lines. It
  already subtracted −1.17 (k5). It now equals the platform member's real balance movement. The IT asserts both.
- The platform member's balance history now has a 「退款冲销」 line (event 6, `event_id` = refund id), the same as the
  other payees. Nothing else in the code sums platform income; the admin settlement list shows the ledger rows as before.

## Proof

### Real MySQL 8.0.45 (`ClubSessionMerchantFeeMySqlIT`, two new cases, real `CmsActivityServiceImpl` settlement and real `RefundServiceImpl` clawback on real tables)

1. `refundAfterSettlementDebitsPlatformPrincipalAndMerchantBalances`, k5's S1:
   four ¥39 players, one 50% refund before the start, three counted at ¥8, settled at 15 days.

   | | Platform 9001 | 主理人 990005 | Merchant 9002 |
   |---|---|---|---|
   | After settlement | 4.10 | 108.40 | 24.00 |
   | Refund of reg 76, ¥39, after settlement | **2.93 (−1.17)** | **78.57 (−29.83)** | **16.00 (−8.00)** |
   | Same clawback run again | 2.93 | 78.57 | 16.00 |

   - The event 6 lines sum to −39.00. The refund ledger lines sum to −39.00. No debt is recorded.
   - The retry writes no new balance line (still 3).
   - 俱乐部结算 row: 平台服务费 2.93, 到账 78.57, 商家核销费 16.00, the same as the balances.
2. `platformShortBalanceRecordsDebtAndNextPlatformShareOffsetsIt`: a club session with no merchant fee, two ¥39
   players.
   - Settled: platform 2.34, 主理人 75.66.
   - The platform's balance is then set to 0.50, standing in for a withdrawal.
   - Full refund of one ticket: platform 0.00 (debited 0.50), debt 0.67 recorded as `refund:platform`, alarm raised.
     主理人 −37.83.
   - Retry: no change, still one debt row.
   - The next session settles: the platform share of 1.17 first clears the 0.67 debt. Balance 0.50, debt repaid 0.67.
- **The new tests fail on the old code.** With only the platform debit removed, three fail: the IT's 「expected 2.93 but
  was 4.10」 and 「expected 0.00 but was 0.50」, and the unit test's non-club activity case. All pass with the change.
- The suite is 6/6 green with the change. `EXPECTED` in `ci/funds_it_gate.py` and the selftest fixture went from 4 to 6
  in the same commit. `python3 -m unittest ci/test_funds_it_gate.py`: 45 OK.

### Unit tests

- `RefundPlatformShareClawbackTest` (new, 3 cases):
  - A non-club activity debits the platform member that received event 7 (−2.93) and the merchant (−94.57), and never
    member 0.
  - No platform income found: ledger only, plus the alarm.
  - A theme registration's platform line never debits a member.
- Refund, settlement, co-op and club settlement unit tests: 536 run, 0 failures. After the new class: `RefundServiceImplTest` 30,
  `RefundMultiPartyClawbackTest` 2, `RefundPlatformShareClawbackTest` 3, all green.

### Funds MySQL gate

`bash ci/funds-mysql-it-gate.sh` against the local MySQL 8.0.45, with the CI DDL flags:
- 738 tests, 0 failures, 0 errors, 0 skipped.
- `funds_it_gate.py` read back the reports, including `ClubSessionMerchantFeeMySqlIT` 6. Exit 0.
- `RUNNER_ENVIRONMENT` was set to satisfy the tests' guard. This is a container, not a GitHub runner.
- The run was on the tree just before code commit `66256f2d`. That tree had the same code, before rebasing onto the other
  lanes' pushes.

## Files

- `chengyinhub-system/.../service/impl/RefundServiceImpl.java`:
  - The clawback block moved unchanged into `clawBackSettledShares`, so the IT can drive it.
  - Platform debit member lookup (`platformDebitMember`).
  - `debitReceiverBalance` now accepts `platform`.
- `chengyinhub-system/.../service/impl/CmsActivityServiceImpl.java`: platform debt offset after the platform payment, in
  settlement and in the complaint release.
- `chengyinhub-admin/src/test/.../service/ClubSessionMerchantFeeMySqlIT.java`: 2 new cases.
- `chengyinhub-admin/src/test/.../service/RefundPlatformShareClawbackTest.java`: new.
- `ci/funds_it_gate.py`, `ci/test_funds_it_gate.py`: 4 → 6.
- No migration, no new table or column.

## Not verified / limits

- **Not walked in the rig, not on a phone.** No HTTP walk of `cancel-by-owner`. The ITs call the real clawback method
  inside a real transaction; `reviewRefund`'s other steps around it (status CAS, `markRegistrationRefunded`, void
  pending) are unchanged.
- **Platform debt and the publish block.** The debt ledger's existing rule blocks publishing for a member with overdue
  debt (`requireNoOverdueDebt`). If the platform account is short for more than 30 days and the same member publishes
  platform themes, that block applies to it too. That is the "same debt rule" literally; tell me if the platform should
  be exempt.
- **Club self-play pass refunded after its theme settled (not platform, outside this lane).** `settleClubSelfPlayTopic`
  writes a 主理人 `INITIATOR_MARGIN` line paid at T+14, and freezes no original components. I found no path that
  reduces that line, or takes it back, when a pass is refunded after the theme settled. The platform part there is an
  accounting line, so this change doesn't touch it. I didn't confirm that such a refund is reachable (pass refund
  windows normally close before the theme ends). I'd like k1 to check.
- **GitHub CI:** not watched to the end. Other lanes push to the same branch, and its concurrency setting cancels
  older runs. The local full funds gate above is the evidence.

STATUS: COMPLETE
