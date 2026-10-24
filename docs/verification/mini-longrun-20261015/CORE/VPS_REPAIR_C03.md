# VPS C03 regression fixture repair

SOURCE_DELIVERED_UNVERIFIED. No rerun performed by this worker. C00 reported the actual f7e0e523 VPS suite result483 total (470 pass/5 fail/8 error); this repair addresses exactly the two OWN_C03_22 failure cases and OWN_C03_24 media error assigned to C03.

## Root causes

OWN_C03_22 chapter/node mapper answers embedded null in a nested numeric conditional with primitive long alternatives. For failure mode1, Java conditional type conversion unboxed null before setId/mapper return, throwing fixture NPE instead of reaching production insert guards. Keep0-row, null-ID, zero-ID and negative-ID cases, but choose null in an outer reference-typed conditional and explicitly box the numeric branch with Long.valueOf. Existing IllegalStateException expectations and no-child/no-NPC/no-rule negative controls unchanged. No service behavior changed.

OWN_C03_24 directly called updateTopic and expected an AjaxResult from post-write media failure. The actual update controller catches only the preceding topic-service call; submitMediaCheck occurs after that catch. Runtime media failure propagates to production GlobalExceptionHandler.handleRuntimeException, which returns AjaxResult.error business500. Therefore the fixture omitted the real HTTP error-advice boundary, not an UNKNOWN production failure. Capture that exact IllegalStateException, feed it through the actual production handler with MockHttpServletRequest, retain the business500 assertion and verify update service invocation. No arbitrary exception expectation replacement, success masking, or500→400 change.

## Exact scope and intent

Only OWN_C03_22Test.java and OWN_C03_24Test.java changed, plus this evidence. CmsTopicServiceImpl and ApiTopicController remain unchanged. Tests remain enabled, existing negative controls retained. The media case now observes both method propagation and HTTP business classification; it does not claim an executed HTTP server integration or DB transaction proof.

No Java/Maven/Node/MySQL/CI/lint/syntax/diff-check/manual validation was run. C00 owns the Git index, commit and subsequent centralized rerun at a new SHA. Frozen complete files and a textual patch are under /tmp/longrun-snapshots/VPS_REPAIR_C03.
