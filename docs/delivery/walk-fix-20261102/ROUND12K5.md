# Round 12, lanes k5 and k6

Rules and owner decisions: `ROUND12.md`. Read `integ/ROUND12-K1-RESULT.md`, especially its proposed design under item 1. Use a private local branch, `git pull --rebase` before every push, no force-push, never push to master, merge a PR or touch production. If you add or remove a `@Test` in a gated suite, update `EXPECTED` in `ci/funds_it_gate.py` and its fixture in the same commit.

## Lane k5-merchant-pay

The owner decided this rule: a merchant on 按核销付费 on a club session gets the agreed fee per participating player at settlement, from the 主理人's share, and the platform stays 3% of retained sales. A scan moves no money. Money becomes withdrawable after 14 days (`FundsHoldPolicy`). Build it fully; don't stop at a design. Follow k1's design, items 1 to 5, where it holds up:
- **Heads:** per session and effective co-op order with a `coop_group_verify` row: paid, not refunded in any amount, took part, not held for a complaint. Fee is the order's frozen `fixed_fee`.
- **Split and ledger:** per counted registration, reduce the 主理人's line and add the merchant's line with its own receiver type and claim, so existing clawback works. Held registrations pay at release. Parts equal retained sales to the fen on every path.
- If the merchant's total would exceed the 主理人's share, refuse that session's settlement with a clear alarm. **Percentage terms (share_mode 1) on a club session:** the club invite page doesn't offer them. Refuse with an alarm if one exists, and say whether production can reach it.
- **Theme path:** no false alarms and no refusal for club session tickets. A club theme settled per session closes its co-op orders without double payment or `NO_PROVEN_FULFILLMENT`. No double payment under any order of jobs or a re-run.
- **Refund after settlement:** claws the merchant's part back to the fen; negative or insufficient balances follow the existing rule.
- **Screens:** 俱乐部结算, the merchant's 财务 and 核销与退款 show the fee with real numbers.

Prove on real MySQL with real HTTP in the rig: merchant at ¥8 per person, four players, one 50% refund, group scan, the 14-day wait, the real settlement job, a refund after settlement, re-running the job. Table to the fen at every step. A club theme with two sessions and two merchants, one 引流 and one paid. Also run the funds gate and chain acceptance.

Write `integ/ROUND12-K5-RESULT.md`.

## Lane k6-merchant-screens

k5 owns settlement and ledger writes, and the fee rows on the money screens.
1. `ROUND12.md` k1 item 8: j1 N4, p2 N-1, N-2, N-3, N-4, N-5, j1 N3. p1 N-1.
2. Rig walk with screenshots you've viewed for what k1 only unit-tested: double order and closing the pay sheet, 另存为模板 keeps the theme on sale, 39.99 on 发布活动, the limit of 3 not counting sessions, welcome points 50 in balance and ledger, the 14-day wait on the screens that mention it. Fix what the walk shows.

Write `integ/ROUND12-K6-RESULT.md`.

Each lane: plain words; per item: status, files, proof, unverified; CI on a commit containing all your work; end with `STATUS: COMPLETE` or `STATUS: PARTIAL - <reason>`.
