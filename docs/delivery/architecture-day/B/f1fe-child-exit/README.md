# f1fe fixed restore child exit SOURCE

Code parent: `f1fe75d646f8ae57864ee5603c68e70e7fabc854`.
Original run/job/artifact: `40237893397` / `121303678122` / `12615004081`, attempt 1.
Original ZIP SHA256: `a5d80cfcbcd9570460f95f5dd756669a187399509ff4deffaff5bfdf6031cd8a`.
Original scope: `e6d02c14170747a1b71236e4be784fbe`.
Root's [original receipt readback](https://github.com/liboyang42-cpu/hengyin/blob/192a122f3f0716d132eb7075fc7dba303feb7f13/docs/delivery/architecture-day/COORDINATOR/f1fe75d64-payment-gap-failure-readback.json)
is evidence only; that docs commit is not this code parent or the running source.

## Proven failure and unavailable cause

Both owned MySQL transports reached the existing relay path and passed the
original resource/loopback/native UUID checks before launching the child. The
previous BINDING_MISSING problem is not reopened. The original payment child
exited before BACKUP_READY. Its catch block deliberately emitted no exception
category, and the three public JSON files contain no reviewed Java cause.
SQL, driver, authentication or business rejection as the underlying cause is
therefore **UNKNOWN**. No historical cause is manufactured by this patch.

Two actual source seams are repaired:

* `Host.run_probe` returned the original nonzero exit without recording it.
  `finish` then closed relays and called the finalizer; its expected FAIL exit
  became host.firstFailure. The stale substage still said relay-close although
  the recorded operation was PROBE_FINALIZE. The host now records the original
  scoped probe exit first, sets accurate cleanup/finalize substages, and keeps
  any secondary error from overwriting it.
* A finalizer can successfully write the exact FAIL/absence receipt and still
  exit nonzero by contract. The host now separately verifies that receipt and
  records probeFinalized=true for completed finalization, without changing the
  original exit, business FAIL, unknown schema cleanup or dispatch BLOCKED.

`source-create-unconfirmed` is intentional: the pending flag is set before
Popen, and ownership is confirmed only after BACKUP_READY, following original
schema setup and economic fixture work. Early EOF does not prove whether CREATE
or a later step ran. This patch does not adopt/drop such a schema or relabel it
clean. Outer container/network exact absence remains a separate observation.

## Fixed diagnostic and lifetime contract

All four fixed child mains (seven original cohorts) use the test-only
RestoreChildDiagnostics helper. Only fixed stages, reviewed cause class names,
reviewed SQLState categories, bounded numeric error codes and a whitelisted
fixture class/line origin (without filename/method/stack payload) are emitted. No
exception message, assertion payload, stack, SQL, password, defaults or raw
environment is serialized. Unknown causes remain UNKNOWN. Original methods,
economic effects, assertions, phase commands and nonzero failure semantics stay.

The Python probe binds each child exit to the original source/scope/cohort/boot,
actual Popen PID/exit, fixed class, compiled class/helper hash, classpath-file hash
and original JAR hash. It reads at most 1 MiB of a private stable stderr file;
only its digest/length and an exact matching bounded diagnostic are published.
Malformed, duplicate, excessive or foreign-source/scope/boot evidence is not
adopted. A missing marker stays unknown, except two exact fixed-main launcher
headers which map to reviewed fixed codes. Raw log contents remain private.

The source, restored guard and owner observation callers all preserve private
stderr and exact Popen cleanup. Cleanup cannot replace the original exception;
an unconfirmed child exit keeps the result failed. The existing relay remains
alive through child/SQL/schema work, then drains/closes before exact resource
removal. No new relay, launcher DSL, provider call, repair or retry is added.
Host/finalizer receipts additionally bind run/attempt and original manifest hash.
Zero process exit cannot override a missing/untrusted or failed business receipt.

## Delivery and execution boundary

Changed source: host/probe and their two direct controls; four existing fixed
Java child mains; one test-only diagnostic helper and its direct unit source;
this document. No production funds/SDK/mapper/schema, auth relay, workflow,
registry, original cohort roster or full-seven verifier changes are included.
No shared-auth review.patch is needed in this round.

Original host 27 and probe 39 controls remain; new declarations are host 30,
probe 44 and 3 Java diagnostic unit cases. The source orchestration control uses
controlled launch/SQL boundaries and explicitly is not a Java/MySQL execution.
It preserves FAIL/BLOCKED, source-create-unconfirmed and original child exit;
the existing canonical backup, scope/UUID, payload and cleanup negative cases
remain. New controls cover typed/unknown/foreign evidence, no secret payload,
EOF/exit preservation, finalization of FAIL and secondary-failure ordering.

All tests, imports/parsing controls, compilation, Java/DB/HTTP/Docker/resources,
fault injection and CI: **NOT_RUN**. Only source reads, edits, diff/applycheck and
hash operations are permitted for this delivery. The original f1fe standard CI
success does not sign this patch. Root must verify the same new source: first
recover a bounded typed child cause or explicitly UNKNOWN, retain business FAIL
when appropriate, prove exact cleanup/finalization separately, and only accept
full-seven business reconciliation after all original cohort proofs pass.
