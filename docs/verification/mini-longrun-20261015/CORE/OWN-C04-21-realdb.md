# OWN-C04-21 real-DB regression source supplement

SOURCE_DELIVERED_UNVERIFIED / NOT_RUN. This is necessary regression source for C04-21, not a new bug task or validation result. Producer dependency is the live C04 admission/resolver/frozen-query implementation, including cash insufficient-funds finalization inside its row lock. Exact public producer SHA: df54dc030faa12fed4c0ff6b8e7211b257a44ffc (PR44 head supplied by C00 after the producer freeze). This binds source dependencies only and does not establish executed coverage. No production/old fixture file changed.

## Actual interfaces exercised

The test registers actual WithdrawalBalanceAdmissionService and WithdrawalFrozenAmountResolver in a Spring context with @EnableTransactionManagement and DataSourceTransactionManager. It asserts the admission bean is proxied. Four unchanged production MyBatis XML resources are loaded: UmsMemberMapper, MerchantReferralBountyMapper, PlayerWithdrawalCooldownMapper, MemberCashWithdrawMapper. Resolver display-only legacy dependencies are null because resolveForWithdrawal never calls them; all current financial reads and writes use real mapper proxies and MySQL. No Mockito, fake persistence, copied SQL implementation or H2 fallback.

Three source cases:

1. A real outer RR transaction first establishes a stale ordinary-read snapshot at balance100/no cooldown. An independent writer transaction commits balance150 plus cooldown50 and bounty20. The outer ordinary reads demonstrably remain100/no cooldown (negative control), while proxied debitBank(80) must obtain current balance150, current freezes70 and return Admission.before150/after70; committed balance stays70. This isolates the admission boundary that supplies ledger inputs. It does **not** invoke the full Bank withdrawal request/intent/encryption/ledger writer, so it does not establish an actual bank ledger row's before/after values.
2. Cash admission is called while an outer RR transaction remains alive. After the REQUIRES_NEW helper returns, another physical connection must read committed balance70 and lock member, original cash row and both freeze sources under a two-second InnoDB lock timeout. This establishes the helper transaction/lock-release boundary before caller continuation. No provider is invoked, and this does not prove the full production provider call stack or deployment transaction manager.
3. Insufficient cash admission must commit FAILED_NO_DEBIT on the original identity. An independent transaction later credits the member; a second admission for the same cash ID must remain ineligible and cannot debit late.

No barrier waits after both contenders have acquired locks. The Bank writer completes before admission takes financial locks; the cash probe runs after helper return and fails on a short lock timeout if helper still owns locks. Future waits are bounded. No arbitrary sleeps or PROCESS/processlist polling.

## Gate and prerequisites

CHENGYIN_FUNDS_MYSQL_IT=1 enables the suite. Once enabled, all six CHENGYIN_TEST_MYSQL_HOST/PORT/DATABASE_BASE/DATABASE_SUFFIX/USER/PASSWORD variables must be explicitly present (empty password/suffix can be explicit). Missing configuration, connection/auth errors, unsupported MySQL, mapper/schema errors or transaction failures must fail, never skip. MysqlIntegrationTestConfig is reused only after this explicit presence guard; its implicit localhost/root/database fallbacks are not admitted.

MySQL8+ (not MariaDB), JDBC driver, and create/drop scratch-database/table permissions are prerequisites. A per-run UUID schema is created and only that created schema is dropped. Fixture-only InnoDB tables include columns required by the exercised production statements; this is not a migration/deployed-schema verification. PROCESS is not required because no lock metadata is queried. The admitted transaction's database credentials need ordinary DML/row-lock privileges. Nothing in this delivery executed DDL, MySQL, Java, Maven, Node, lint, syntax, diff-check, CI or manual validation.

## Honest limits

Bounty NESTED savepoint recovery and member-first lock ordering are not covered: actual activation/refund needs a larger real registration/verification harness. No mock ordering is substituted. Production DataSourceTransactionManager/nested-savepoint support and deployment configuration remain unverified. No full cash-channel transfer, receipt recovery, actual bank ledger persistence, financial-policy acceptance or deployment claim is made. All assertions remain unexecuted test source.
