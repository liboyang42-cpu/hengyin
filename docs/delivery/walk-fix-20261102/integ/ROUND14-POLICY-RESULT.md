# ROUND14 POLICY result

Base: `4aff0697d715b0ecb278b7fb0a631e65484fd84e` in the assigned public hengyin checkout, branch `codex-immersive-20261105`. No push, shared-branch write, merge, deployment, production access, DevTools interaction, local backend process, new dependency or workflow change.

Coordinator follow-up: Java source/tests are frozen and intentionally uncommitted for the root's unified VPS check/commit. `ROUND14-POLICY-JAVA-MANIFEST.json` records the exact eight paths, SHA256 values and eleven suites. The worker commit contains frontend/tests/report/manifest only; the coordinator must collect the frozen Java files separately, including the two new untracked test files.

## Delivered changes

| Approved item | Result | Callers and evidence |
| --- | --- | --- |
| Scan activity-bound game text on create/update | Source complete; Java execution pending coordinator | `ApiActivityController.publishOnce` and `updateOnce` append the persisted bound template's text to the existing activity safety request. Null template ID on an update resolves the stored binding; nonexistent/deleted/unreadable bindings do not save. Existing owner checks precede the lookup and checker. |
| Existing activity republish | Source complete; Java execution pending | `activityUpdatePublishStatus` rechecks a bound game before relisting. Rejected/unchecked text cannot set publish status; taking an activity off sale still works. HOLD asks the author to save through the existing reviewed-edit path. |
| Admin activity create/update | Source complete; Java execution pending | `CmsActivityController.add/edit/decide` use the same bound-text collector and existing gate, including a retained binding on partial updates. Admin permissions and the existing owner/held-state service remain unchanged. |
| Nested/new game text | Source complete; Java execution pending | `JourneyReviewTexts.collectBoundTemplate` walks string leaves, including arbitrarily named nested JSON fields, arrays, JSON encoded text and escaped Unicode. Non-string JSON numbers/booleans are not text. Malformed JSON is sent as raw text rather than silently skipped. The existing theme `ApiTopicController.boundTemplateReviewTexts` also consumes this collector. |
| Editing an already-bound game | Source complete; Java execution pending | `JourneyReviewTexts.collectTemplateFields(CmsTemplatePublishRequest)` uses the same walker. Its existing caller `ApiTemplateController.templateTextSecurity` continues to call the existing checker and refuse an unsuccessful check; no new service or review policy. |
| Reviewed material and failure semantics | Covered in new test source; runtime unverified | A prior `checkStatus` is not a safety exemption when binding. Clean text proceeds; BLOCK reaches no activity save; checker busy/exception remains HOLD on create/update. Existing recheck payload now contains the bound text. The create-vs-update draft moderation difference and rollback of hit-log rows are deliberately unchanged. |
| Hide new coupon creation | Local Node tests pass | `utils/coupon-form.js` exports default `CREATION_ENABLED=false`; the submit helper refuses without a request. The merchant list no longer renders its create CTA or promotion; its old handler also refuses. `couponInfo` hides and exits deep links and refuses submit. Reward selector gates both coupon/pass creation CTA, sheet and handlers, while leaving existing rewards selectable. Profile no longer advertises coupon publishing. Admin directory removes its create action and add API caller and requires an existing ID to save; existing edit/history APIs remain. |
| Keep early scans | Source consistent; focused Java test pending | `GroupCodeServiceImpl.issue/redeem/isActiveGroupActivity` does not ban scanning before the start. Redemption writes the exact `coop_group_verify` reception fact and business audit; it has no balance/withdrawal/settlement service dependency. `ApiVerifyGroupCodeTest.earlyGroupScanRecordsParticipation` sets tomorrow's start and checks the reception and audit writes. No monetary source or time rule was changed. |

## Caller inventory and ownership limits

- Activity safety callers: `/api/activity/publish`, `/api/activity/update`, `/api/activity/update_publish_status`, `POST/PUT /business/activity`.
- Shared bound collector callers: activity controllers plus theme create/edit/draft publish through `ApiTopicController.boundTemplateReviewTexts` and `persistedBoundTemplateReviewTexts`.
- Template request collector caller: `ApiTemplateController.templateTextSecurity`, used by its existing draft/publish/edit chain. The existing template-library republish snapshot contract was not redesigned.
- Coupon creation callers: merchant list `GoAdd`, independent `couponInfo`, `reward-selector.createCoupon/onCreateSubmit`, shared `couponForm.submitCoupon`, admin coupon directory. Both `COUPON` and `PASS` use the coupon creation API and are closed together; stored coupons/passes remain usable/selectable.
- Preserved: coupon list/detail/summary, stop notice "停发后不可领取，已领券仍可用", wallet, history, redemption, and underlying APIs. No stored rows are deleted. The 11 other nonblocking owner decisions remain unchanged.
- Early-scan source review also traced ordinary ticket verification into `SettlementEntryServiceImpl.recordPaidRegistration`: this writes pending split facts rather than crediting a balance. Monetary settlement/release rules and any runtime ledger rehearsal remain MONEY/coordinator ownership. Prior ROUND12-K5 recorded scan/balance readbacks are not presented as a new run.

## Local verification

1. PASS: `node --test --test-reporter=tap chengyinhub-xcx/tests/unit/coupon*.test.js chengyinhub-xcx/tests/unit/reward-selector*.test.js chengyinhub-xcx/tests/unit/merchant-coupon*.test.js chengyinhub-xcx/tests/unit/r1-c04-order-ticket-contract.test.js chengyinhub-xcx/tests/unit/devfix-l09-merchant-ops-20261027.test.js chengyinhub-xcx/tests/unit/bl238-member-nav-guard.test.js chengyinhub-xcx/tests/unit/sheet-cancel-mutex-contract.test.js chengyinhub-xcx/tests/unit/primary-action-form-state-contract.test.js` — **235 tests, 235 pass, 0 fail/skip**. Existing disabled-form recovery tests explicitly enable only their isolated fixture; a new default-config suite tests the launch closure, deep links, handlers, all four coupon types, and selection of existing coupons/passes.
2. PASS: `node --test --test-reporter=tap chengyinhub-xcx/tests/unit/coupon-creation-launch-closed.test.js chengyinhub-ui/tests/unit/coupon-ledger-contract.test.js` — **9/9**, including 5 existing admin history/ledger tests.
3. PASS: `python3 -m unittest ci.test_secret_file_gate ci.test_funds_it_gate` — **48/48**. No gated Java suite count was changed.
4. PASS: `node --check` for changed coupon form/reward selector/list/independent page scripts; extracted admin Vue script parsed with `new Function`; `git diff --check`. Gitleaks scanned the reviewed complete patch (including new Java files): no leaks found.
5. NOT RUN successfully: `node --test chengyinhub-ui/tests/unit/coupon*.test.js` — `coupon-migration-contract.test.js` cannot load the pre-existing dependency `vue-router` (`MODULE_NOT_FOUND`) in this worktree. Its import adapter was updated for the removed add API; no dependency was installed. Admin closure is additionally covered by the new dependency-free launch regression test.
6. EXPECTED INTEGRATION REGEN REQUIRED: `node chengyinhub-xcx/tests/lint/ui-integrity.js` — six violations, all profile baseline line drift caused by deleting the publishing benefit row: U1 721 -> 720, U4 1297 -> 1296 and 2146 -> 2145, plus the three stale keys. Coordinator owns this shared baseline; no waiver or gate change made.
7. EXPECTED INTEGRATION REGEN REQUIRED: `node chengyinhub-xcx/scripts/uiaudit/build-action-ledger.js --check` — FAIL, action ledger drift after UI changes. Coordinator must regenerate/review the ledger and line baselines after integration; generated artifacts were not edited here.

## Exact Java suites for coordinator VPS/CI

Compile and execute these on the unified source snapshot, **not on the Mac**:

```text
ActivityBoundGameContentSafetyTest
BoundTemplateReviewTextsTest
ActivityPublishReviewModeTest
ActivityPublishRoleGateTest
ThemeBoundTemplateTextSafetyTest
CmsActivityAdminReviewGateTest
JourneyReviewTextsTest
JourneyReviewTextsPlainFieldTest
TopicTextSafetyTest
ApiVerifyGroupCodeTest
RegistrationVerifyAndSettleTest
```

New source tests cover create/update BLOCK before save; clean reviewed material and nested allowed values; HOLD on busy/throwing checker; retained bindings; missing/read-failed games; activity owner rejection before scanning; relist rejection/HOLD/pass; every String field on the template model; unknown nested fields and Unicode decoding; admin no-write rejection and held-state mapper writes; already-bound draft configuration checks; early reception before start.

## Remaining evidence limits

- Java compilation/suites have been requested from the coordinator and are not locally claimed as passing. Mapper/service mock tests are not a database transaction rehearsal.
- No newly built backend, authenticated multi-account walkthrough, independent DB/ledger readback, screenshot, real phone, real WeChat scanner check or provider availability test was run. UI tests are synthetic Node tests.
- Activity text continues to use the existing combined-text gate request. Long content that exceeds the provider's accepted size must remain held/failed closed under that existing checker; this patch does not add a new batching policy.
- Shared baseline/action-ledger regeneration, package-size verification and unified CI remain coordinator work. No launch/release acceptance is claimed.

STATUS: PARTIAL — assigned source/UI changes and local focused checks complete; unified Java execution, generated integration checks and runtime/device evidence remain unverified.
