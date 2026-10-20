# OWN-C04-20 real-DB regression supplement

SOURCE_DELIVERED_UNVERIFIED / NOT_RUN. This supplement adds no production change and does not modify the immutable original C04-20 snapshot or the existing B03 suite.

OWN_C04_20MySqlIT has two unexecuted real MySQL regression methods. The race uses actual Spring transaction proxies, actual MyBatis batch SQL, two worker threads/connections, and bounded latches: A reads PENDING then pauses after the real SELECT; B confirms by the same ops actor and commits; A resumes and must return false. An independent JDBC read must show CONFIRMED, confirmed_by=ops and no paid_by, paid_at or voucher. The mapper interception only pauses after delegating the real query and never substitutes a row/result or skips SQL. The positive control confirms with maker then records payment with checker and independently reads the PAID facts.

Setup reuses MysqlIntegrationTestConfig with explicit authorized host and isolated nonempty suffix. Spring ScriptUtils parses the full existing base schema, preserving semicolons within quoted comments; only the uniquely matched complete merchant_payable_batch CREATE statement is executed in the isolated test schema. The usual temporary-schema lifecycle and existing mapper aliases/transaction harness are reused. No production schema migration, resource changes or external payout is involved. Latch/future waits and worker shutdown are bounded. Gate: CHENGYIN_FUNDS_MYSQL_IT=1.

The earlier OWN_C04_20Test remains a service branch test with mapper doubles and is not real database evidence. This source has not been compiled or executed; the proposed MySQL behavior, race schedule and positive control remain awaiting authorized execution.
