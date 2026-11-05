# POLICY independent review

What this change does: It adds bound-game text to activity create, edit and relist checks, and makes the existing template text collector walk nested strings. It preserves the existing review gate and activity ownership checks.

Reviewed independently from ACCESS against the POLICY manifest at `codex-immersive-20261105`: all **8/8 original file SHA256 values matched** before review. The review covered all eight frozen production/test files and their immediate safety/save callers. It assumed normal author/editor requests, including user-controlled plain text that happens to look like JSON. No Java was executed locally and no other worker's source was modified.

## Must fix

1. **JSON-looking plain text loses visible words before safety checking** (`JourneyReviewTexts.java:310`, `:647`).

   - **What this is:** The new collector attempts to parse every string, including ordinary story/title/question text, then replaces the original with decoded JSON values.
   - **Problem:** `storyText="{} 违禁正文"` or `questionName="[] 违禁题干"` is parsed as an empty JSON container, so no entry reaches the activity/theme checker. Jackson 2's default reader does not check trailing tokens. Separately, the complete plain string `storyText="{\"违禁正文\":1}"` loses its visible key because object keys are only used as paths and number values are skipped. Strict parsing alone would not fix the second case. The play page uses `n.storyText` as the story source (`pages/play/index.js:4555`); this is not just hidden metadata.
   - **Fix:** Retain each raw nonempty string and additionally decode nested string leaves, deduplicating collected text. Keep the existing general JSON parser behavior unchanged. Test raw object/array prefixes with suffixes, valid JSON-looking ordinary story text, malformed text, duplicate raw text and JSON-encoded nested text.
   - **If we skip it:** Visible author text can be saved or bound without the safety provider receiving those words. The original malformed-prefix test does not catch a valid JSON prefix or a valid JSON-looking plain field.

The parser behavior is supported by the [Jackson 2 DeserializationFeature source](https://raw.githubusercontent.com/FasterXML/jackson-databind/2.18/src/main/java/com/fasterxml/jackson/databind/DeserializationFeature.java): `FAIL_ON_TRAILING_TOKENS` defaults to false. This review inferred the concrete text-loss behavior from that API contract and the local collector branches; it is not a claimed local JVM reproduction.

## Authorized correction prepared

Root assigned only `JourneyReviewTexts.java` and `BoundTemplateReviewTextsTest.java` to ACCESS after the finding. I copied the exact original POLICY frozen versions first, then made the narrow raw-preservation change. The global `parse` method and all controllers remain unchanged. Raw preservation makes a separate strict reader unnecessary: trailing text is retained regardless of whether optional nested decoding recognizes only the first JSON value.

`BoundTemplateReviewTextsTest` now has four tests: the original model-field and nested/Unicode/malformed cases, plus raw suffix/JSON-key/dedup cases against both the persisted model and draft DTO, and a JSON-encoded nested-text case. No tests were removed and no shared gate or registry was changed. Exact two-file overlay is recorded in `/tmp/round14-policy-review-overlay-manifest.json`:

| File | SHA256 |
| --- | --- |
| `chengyinhub-system/src/main/java/com/chengyinhub/business/util/JourneyReviewTexts.java` | `42ab4afe838079f131276278fe35dc46138480648657c5265e660953085df9ab` |
| `chengyinhub-admin/src/test/java/com/chengyinhub/business/util/BoundTemplateReviewTextsTest.java` | `acf39b3a3b1290fa47f423fb06389c6ad6b45a9639792cfd9e0e4b7cad4895f8` |

Root and the active POLICY Dispatch were notified. Root owns copying the overlay, compiling and running `BoundTemplateReviewTextsTest`, `ActivityBoundGameContentSafetyTest`, `ThemeBoundTemplateTextSafetyTest`, `JourneyReviewTextsTest`, `JourneyReviewTextsPlainFieldTest`, `TopicTextSafetyTest` and the original activity/admin/verification suites on the final union source.

## Other reviewed boundaries

- Activity update and relist still resolve the publisher, check activity owner and hosted-run scope before loading/scanning a bound game. Create still uses the existing publisher, role, quota and media checks. No caller-supplied owner ID replaces the authenticated publisher.
- Omitted templateId on an edit uses the persisted binding. Missing/deleted/unreadable positive bindings refuse before save. A previously reviewed template is scanned again; `checkStatus` is not a bypass.
- Both app create/edit and admin create/edit continue to refuse BLOCK and preserve the current HOLD save/state behavior. Relist refuses BLOCK/HOLD and leaves publishStatus unchanged, while off-sale operations remain available. The current `ContentSafetyGateImpl.unsure` returns HOLD for unavailable/throwing scanners in both review modes.
- Template draft/publish and theme create/edit/draft-publish call the shared collector. The correction therefore reaches those callers without separate moderation implementations.
- The early group-scan test records participation and audit through the existing group-code service; no payout/refund arithmetic or monetary rule was modified in these eight files.
- No additional concrete correctness/ownership defect was established within this bounded review. This is not a repository-wide audit or provider acceptance.

Verdict: fix 1 first. The corrective overlay is ready, but its compile/tests and integrated runtime evidence remain pending root.

Not checked: final JVM execution, real moderation provider, database transaction/state readback, screenshots, phone acceptance, concurrent game mutation, or final combined CI. No real funds action occurred.
