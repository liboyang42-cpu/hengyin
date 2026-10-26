# Restore HTTP lifecycle cost — SOURCE

Parent: `8b150cba25938a10cef8583b51454b6c3a40c864`.

The original full-row sampler opened one connection for metadata and another connection for every protected table. The original `authenticated_redis_recovery_probe._owned_mysql_relay.Handler.handle` verifies its physical endpoint through Docker/container/network inspection before every new upstream connection. This repeats endpoint/connection work N+1 times for N protected tables, independently of the required N table SELECTs.

The sampler now opens one NEW connection per complete observation. It explicitly requests autocommit, reads back integer session autocommit=1, and retains same-session effective `information_schema_stats_expiry=0`, server UUID and schema verification. Every protected table still executes its original `SELECT *`, typed encoding, sorting and exact nextId capture. The connection is closed on success/error; no connection, rows, metadata, authorization, fact, PID, readiness or baseline is reused across observations or boots. Autocommit leaves each SELECT as a fresh statement, not a new long-lived RR snapshot. No relay/shared owner is changed.

For 418 protected tables, the source-level connection count changes from 419 to 1 **per sampler call**, while all 418 table SELECTs remain, plus one effective-autocommit check. This is a static count, not a measured duration/RPS guarantee.

The normal-readback report previously issued a second full sample immediately after a full equality assertion. It now projects the exact sample that passed the original equality and original provider-record checks. Subsequent phase/action/callback/stop/boot observations are still independently read. No economic assertion or full comparison is removed.

Existing checkpoints now retain at most 128 relative phase durations and fixed lifecycle elapsed/remaining milliseconds. The original budget check records its finite sample and still rejects at the unchanged deadline. These observations are never readiness/completion proof and contain no message, SQL, body, arbitrary locals or raw logs. Runtime reports remain private to the coordinator.

The 1500-second lifecycle, 180-second readiness and 20-second stop bounds; five boot modes; each held workload; actual Quartz/owner calls; original reference verification; rows/high waters; private log capture; cleanup and no-dispatch fences are unchanged. Canonical backup tooling, Java child and production code are untouched. A specific internal startup bean cause remains **UNKNOWN** without corresponding evidence.

Eight new direct Python control SOURCE cases cover 418 full table reads through one connection, fresh independent observations/drift refusal, effective-autocommit rejection, error closure, exact normal report projection, finite/bounded phase timing and unchanged deadline rejection. Existing controls/assertions are retained; their driver only receives the required keyword/session contract.

Tests, parse/import, compilation, DB, HTTP, containers and CI: **NOT_RUN**. Only source/diff/hash operations are used for this delivery. This is not full five-boot acceptance or a production recovery performance claim.

The code-only `owned.patch` contains three files; static docs are separate commit content.
