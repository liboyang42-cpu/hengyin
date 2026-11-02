# Round 3 restart after the usage limit, 2026-11-02

The weekly limit stopped every lane at about 13:40 UTC. Anything not pushed is gone. **Commit and push after every finished item.** Read BRIEF.md, ROUND3.md and, for l6-club, ROUND3B.md. Check `git log` on your branch, skip what is already pushed, and do the rest. Merge origin/master first (merge, no force push). Before stopping: `node chengyinhub-xcx/tests/lint/ui-integrity.js` prints UI-GATE-0:PASS, related tests pass, shots and RESULT.md are pushed, and CI on your PR has no failures. RESULT.md must have a section `Round 3` listing each item as done or not done, and end with `STATUS:`. Never merge a PR.

## State by lane

- **l1-play (#713):** nothing from round 3 is done. Do the whole section.
- **l2-explore (#714):** g21, P-30 and C-22 are pushed. Add shots and the `Round 3` section. C-22 removed a server rule, so list exactly what changed.
- **l3-sell (#715):** nothing pushed. The lost run found these; redo and verify them:
  - **P-33:** `.slopes--full` used `--cy-color-bg-elevated`. Change to `.fabu.slopes--full { background: var(--cy-color-bg-page); }`.
  - **C-14:** add a read-only club-name check endpoint (same rule as `requireNameFree`), call it on blur on `pages/club/create`, and test it in `ClubNameUniquenessTest`. Keep the existing `aria-label`, because a contract test requires it.
  - **P-34:** real defect: the confirmed price did not reach a city theme's authored tickets, and after sale the page wrongly said 「合作阵容锁定后可定价」. Fix both and add a test in `PricingDepositDueTest`.
  - **P-38:** not started.
  - **Build note:** set `LANG=C.UTF-8 LC_ALL=C.UTF-8` for Maven, or Chinese file names fail compilation.
- **l4-counter (#717):** M-05, M-23, M-27 and M-10 are pushed. Do M-04's re-shoot and shots. Fix CI: `Mini program unit shard (12/20)` and `Mini program static checks`.
- **l5-onboard (#712):** code is pushed. Add shots of all three sheet states. Fix CI: `Fast gates`, `Mini program static gates` and `Mini program static checks`.
- **l6-club (#716):** C-11, C-09 and C-03 are pushed. Do C-12, C-05, C-04 and C-20, then all of ROUND3B.md, whose start condition is met by this run. Fix CI: `Mini program unit shard (12/20)`, `Mini program static gates` and `Mini program static checks`.
- **pay-gate (#718):** nothing pushed. The lost run found these; redo and verify them:
  - **Payee:** a `ClubPrincipalPayee` resolver in `service/support`: club session goes to the club's current 主理人. Credit it in both pay sites of `CmsActivityServiceImpl.settleActivity`.
  - **Refund debit:** refunds must debit whoever actually received the money, because the 主理人 may have changed since (add a mapper query of the actual income members for the event).
  - **Self-play:** add the branch in `CmsTopicServiceImpl.settleTopic` in place of `NON_COOP_TOPIC_CASH_SETTLEMENT_UNPROVEN`.
  - **3%:** `PricingServiceImpl.FALLBACK_PLATFORM_RATE` is 0.10 and `settlement_rule` is the source; state exactly what to set, and flag that fallback.
  - **`payout_status=5`:** the run concluded this is a code defect, not a rig artefact (guard `退款派发必须在原业务事务提交并退出后执行` in `RefundPayoutServiceImpl`). Confirm with evidence and fix it.
  - **Refund tiers:** then do them, per ROUND3.md. The 50% tier affects activity settlement totals (`selectTotalSalesByActivityId` sums the full payable), so settlement must net out the refund.
  - **Build note:** set the UTF-8 locale for Maven.
