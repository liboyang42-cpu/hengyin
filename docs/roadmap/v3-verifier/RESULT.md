# v3 verifier — roadmap/be-integ 4cfb897d9 vs base feee515da

Run 2026-11-16 in a cloud container (no MySQL, no live servers). Maven with `LANG=C.UTF-8 LC_ALL=C.UTF-8`,
`-DargLine="-Xmx4g -Dspring.test.context.cache.maxSize=4"` (root pom's argLine kept apart from 512m → 4g;
admin pom still appends the Mockito agent). Runs were sequential.

## Full suite `mvn -B -pl chengyinhub-admin -am test -Dmaven.test.failure.ignore=true`

| tree | tests | failures | errors | skipped | test classes (surefire pattern) | reports |
|---|---|---|---|---|---|---|
| base feee515da | 20729 | 0 | 34 | 713 | 2570 | 2569 |
| integration 4cfb897d9 | 21033 | 3 | 34 | 713 | 2603 | 2602 |

Only `chengyinhub-admin` has test sources. The 34 errors on both trees are the same 14 `*MyBatisTest`
classes, all `Communications link failure` (no MySQL) — excluded.

Class without a report (both trees): `appgaps/ApprovedTopicReleasesTest.java` — the file is 0 bytes since
2abfd2889 (already in base); run alone, surefire finds nothing to run. Not an integration defect; the commit
message claims unit tests in it, so whoever owns app-gaps-b should restore or delete it.
`*IT.java` (256 files) are not in surefire's default includes and no failsafe is configured, so they do not run.

## New failures on integration (not on base)

1. `OWN_C04_21Test.bountyFailureRollsBackItsNestedBoundaryAfterMemberThenBountyLock`
2. `MerchantReferralBountyDeadlockRetryTest.deadlockInsideSavepointPropagatesAndOuterRetriesOnceThenAppliesBountyExactlyOnce`
3. `MerchantReferralBountyDeadlockRetryTest.deadlockThatOutlivesTheRetryBudgetFailsTheVerificationInsteadOfCommittingHalf`

Deterministic when run alone. Cause: 9206acb4f (MERCHANT-3) made `CashPayoutSwitch.open()` also require the
Q5 `MoneyGate` payout area; the two-argument constructor is now always closed. That package updated
`MerchantReferralBountyTest`, `MerchantReferralBountyReviewTest` and `RewardPledgeFlowTest`, but these two
classes (and `MerchantReferralBountyDeadlockRetryMySqlIT`) still built `new CashPayoutSwitch(true, true)`, so the
direct-pay path they assert became the review path. **Fixed** in 5350eed6d (test-only: pass
`MoneyGateTestSupport.openGate()`, also in the MySqlIT). After the fix: MerchantReferralBounty*Test,
OWN_C04_21Test, MoneyGateTest, RewardPledgeFlowTest — 72 tests, 0 failures; admin test-compile passes.
No production main code builds a `CashPayoutSwitch` by hand.

## Gates (integration tree)

- `ci/migration-safety-check.sh`: 迁移安全门禁:通过
- `ci/migration-layout-check.sh`: 迁移布局门禁:通过 (756 migrations)
- `ci/api-existence-gate.js`: exit 0, A=0 B=904 C=0
- `ci/role_permission_contract.py`: exit 0 (851 endpoints covered)
- `ci/migration_ledger.py audit`: exit 0, problems: []
- pytest (3 files): 228 passed, 302 subtests passed

## Console `chengyinhub-ui` — `node --test "tests/unit/**/*.test.js"`

Needed vue/vue-router/vuex/vue-template-compiler installed with `--no-save`.
Base 649 tests / 25 failing; integration 656 / 25 failing. Failing names identical — no new failures.

## Mini-program `chengyinhub-xcx` — prepare-mini-artifacts then `node --test`

prepare: exit 0 on both (ACTION-LEDGER WRITE PASS: base 6189, integration 6207 controls).
Base 17910 tests / 32 failing; integration 17966 / 32 failing. Failing names identical — no new failures.
