# Roam audit correlation fixture — SOURCE

Parent: `2fbfd6df4c4480d924eddd0984169a8d86877075`.

The actual owner remains RoamPresenceRevokeAuditService: deliver opens the original command scope and writes INFO attempt/result records; retryDue and RoamPresenceRevokeAuditTask call the same deliver path. No logger relocation or extra production log is required.

The shared admin test resource logback-test.xml sets com.chengyinhub and the root logger to WARN. The correlation test attaches its ListAppender without enabling the INFO level it asserts. Production logback.xml keeps com.chengyinhub at info. The fixture now saves this specific owner's explicit level, enables INFO only during the existing scenario, and restores the original level in finally while detaching/stopping the appender. It does not change the root/package/test configuration or production code.

The original test method, exact two result-log assertion, request/command/job-run correlation, failure and alarm behavior, distinct lease owners, persisted claim/fail/complete checks, MDC cleanup and no throwable/payload/lease disclosure assertions are retained. Two fixture checks confirm INFO admission and restoration of the previous level. There are no new test cases or dependencies.

Tests, compilation, project parsing/import, DB/Redis/Docker/HTTP and CI: NOT_RUN. Diff/hash operations are source checks only. Runtime evidence remains with the coordinator; this source donor inherits no prior acceptance.
