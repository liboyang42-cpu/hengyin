# Current result: DRAFT PR #625 — NOT READY TO MERGE

Published commit: 2d7c5dfd99339ed24d43a97819c59e850e2fa275. Approved-VPS compile PASS; admin selected tests: 2360 run, 17 failures, 240 errors, zero skipped. Many errors are stale Controller reflection injection after application-layer migration; preserve assertions while repairing fixtures. CI run 40374590684 has failed jobs and is still running. These are known failures, not acceptance. Later paragraphs retain earlier diagnostic history.

# DRAFT PR — NOT READY TO MERGE OR DEPLOY

User explicitly authorized this closeout-only draft exception before compile/unit gates pass. Source preservation is not acceptance. The seven test-file repair is included; the recheck is running. compile-errors.txt records the previous candidate failure, not an assertion that the repaired candidate passed or failed.

# W8 closeout handoff

User requested closure: stop new cloud development; preserve existing source, finish necessary gates, publish a draft PR and hand off. No autonomous new work or merge/deployment.

Public base: `921d172199f3ffe224206ee05af031c87df62b87`. The source-only tree after both compile fixes is `d9e5aac6b628f0aa947f297f56fa1bfe33b1812f`; this document is a later documentation addition.

| Lane | Scope | Included source |
|---|---|---|
| 01 | Funds and refunds | F3 preparation, refund outcomes and persistent fair reconciliation queue |
| 02 | Identity, community and support | Idempotent admin messaging, receipt recovery and privacy/voice state |
| 03 | Authoring and publishing | Frozen published attributes and template-adoption request recovery |
| 04 | Gameplay | Original-run actions, attribute checks, scoring, endings and recovery; owns former 06/08 gameplay work |
| 05 | Maps, roaming and passport | Account-scoped history, detail and summary readback |
| 06 | Audit and guard appeals | Scoped queue, immutable original receipt, personal/shared eligibility and onsite recheck |
| 07 | Teams, events and broadcast | Contact/attendance, command recovery, manual broadcast recovery and typed channel results |
| 08 | Search | City facts, exact IDs and stale-response isolation |
| 09 | F3 rights and aftersales | Frozen refund contracts and entitlement reconciliation; new mall features deferred |
| 10 | Merchant tools | Previously completed source retained; further mall development paused by user |
| 11 | Invoice | Entity-scoped processing, original-request recovery and refund reconciliation hold |
| 12 | Integration | Public routes, entries, merged source and verification inventory |

## Validation and limitations

First approved-VPS compile found a wrong TopicRouteMapper reference and missing StringUtils import. The original owner supplied a two-file correction; subsequent build encountered missing HEAD in the reconstructed verification directory. That environment issue was repaired by fetching the real public base, without inventing a candidate commit. Final compile/unit outcome is recorded separately by the coordinator and GitHub checks, not assumed here.

- No merge, production deployment, migration or real message/payment was authorized by this closeout.
- B restore-fence review patch is NOT applied; restore-mode acceptance remains NOT_READY.
- Broadcast automatic retry/backoff/new expiry is DEFERRED_BY_USER.
- Existing frontend evidence is reused; Java/MySQL evidence must bind the exact candidate.
- Do not interpret source integration or green CI as device/provider/business acceptance.

## Resume safely

Start from this public branch, preserve existing source and unique domain ownership. Read repository AGENTS.md. Use the approved VPS for backend builds and GitHub-hosted CI for automated gates; never run local Java or bypass resource admission. Check exact head SHA and actual test reports; do not rerun completed suites without a change or failure. Complete missing compilation/test fixes first, then the 31 named MySQL classes and real-role/device/provider acceptance as applicable. Do not default unconfigured invoice reviewer scopes to broader access. Guard-appeal approval permits onsite recheck of the still-valid original run only, with no automatic completion/reward or risk-history clearing.

Full lane table: [交接总表](交接总表.md). Exact test inventory: [verification/test-targets.json](verification/test-targets.json).

Latest approved-VPS result: production source compiled, test compilation failed on missing fixture/type references and imports; see compile-errors.txt. Unit tests and 31 MySQL classes have NOT run. The default 384 MiB Java heap caused GC saturation; a task-only 1024 MiB heap within the unchanged 1536 MiB budget resolved that environment delay. The pending compile fixes must preserve assertions; do not skip or delete tests to pass.
