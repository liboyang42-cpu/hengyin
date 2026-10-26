# Restore HTTP callback replay — SOURCE

Parent: `c0355527bd2082ea957b08fd684a4a8887470858`.

## Source change

The test-only verified-callback controller previously reconstructed the same received provider evidence and called `recordVerified` for every delivery. It did not retain the committed Reference across held boots. The controller now records a bounded private original-reference receipt, bound to source/JAR/schema/server UUID/registration and the original fact hash. A lost receipt first recovers the exact committed fact by its original provider identity. A saved but missing/conflicting Reference is rejected; it is never replaced.

Every delivery still freshly verifies that Reference and calls the actual registration callback/inbox path. No business outcome is cached, no provider is called, and no business consumption authority is added. The original held result, callback replay and economic/high-water assertions remain. Fact absence after a lost response is not interpreted as evidence that an external action never happened; this fixture only persists its already-received, fixed historical evidence.

A bounded, boot-bound callback stage/type observation is written privately and correlated before/after the existing HTTP operation. It contains only closed stage/reason/cause classifications, whitelisted owner class/line, and whitelisted SQL state/error code. No exception message, SQL, request body, raw log or arbitrary stack frame is exposed. An unchanged previous observation cannot explain a new failed request. HTTP business 500 remains a failure; transport exceptions escape unchanged.

Only per-boot diagnostic files are cleared before the next boot. The original reference receipt/provider identity survive true/invalid/empty boots and remain inside the existing owned private cleanup subtree. Original five-boot, stop, log, authorization, UNKNOWN, exact rows/high-water and cleanup guards are retained.

## Direct control SOURCE

New Java file `RestoreHttpCallbackReplayTest`: 8 cases covering cross-mode same-reference replay, lost-receipt recovery, missing/conflicting/foreign receipt refusal, unchanged callback exceptions, active outer transaction refusal, and bounded diagnostic privacy. Service doubles in these direct controls do not prove SQL/economic effects.

Existing Python control file: 7 added cases covering boot/type binding, rejected private documents, business 500, stale observations, transport exceptions, unknown correlation and diagnostic-only reset across boot. Existing scenarios/assertions, including prior traceback-capture fixes, are retained.

## Verification boundary

All added controls, Java compilation, MySQL/Redis, HTTP, restore and CI: **NOT_RUN** in this delivery. Only source/diff/hash operations are performed. The historical business-500 cause remains **UNKNOWN**; the source gap above is independently reproducible from the original controller. Fresh typed observations are required to attribute a future refusal. This package does not claim full restore acceptance or production disaster-recovery performance.

No shared helper, production payment owner, SQL, workflow, budget, endpoint or dependency is changed. The supplied `owned.patch` contains 5 code files; this README and manifest are static metadata, so the whole commit diff has a different identity.
