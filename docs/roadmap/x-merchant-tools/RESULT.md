# W7 x-merchant-tools: backend receipt (hengyin)

Branch `feature/r-x-merchant-tools`, based on `origin/roadmap/be-integ` (`63bc29f9d`). App: chengyin-app branch
`cloud/r-x-merchant-tools` (its receipt covers MERCHANT-4, -7, -13 and the app side of -14).

## Issues

| ID | Result | Notes |
|---|---|---|
| MERCHANT-14 | Fixed (backend part) | Review-time config on `/api/config/features`; decision notice to the applicant. |
| MERCHANT-4, -7, -13 | App only | No backend change needed. |

## Changes, file by file

- `chengyinhub-admin/.../api/ApiConfigController.java`: `/api/config/features` adds `merchantReviewSla`, the trimmed
  sys_config text `merchant.onboarding.review-sla` (at most 32 characters, empty when unset).
- `chengyinhub-admin/sql/migration_20261116_merchant_review_sla.sql` (new): seeds the key empty, only when missing.
  Rollback note at the top. `ci/migration-dependencies.json`: `"20261116_merchant_review_sla": []`.
- `chengyinhub-system/.../notify/NotifyEvent.java`: `MERCHANT_REVIEW`.
- `chengyinhub-system/.../service/INotifyService.java`: `notifyMerchantReview(memberId, approved, reason)` (default no-op).
- `chengyinhub-system/.../service/impl/NotifyServiceImpl.java`: fans the event out.
- `chengyinhub-system/.../notify/InAppNotifyChannel.java`: in-app system message. Approved: 「你的商家入驻申请已通过。
  下一步：在「我的店铺」里设置据点、小游戏和优惠券。」 Rejected: 「你的商家入驻申请未通过。原因：…。请完善资料后重新申请。」
  (reason optional, at most 120 characters).
- `chengyinhub-system/.../service/impl/MmsMerchantServiceImpl.java`: after a successful admin edit that moves the
  review status to approved (1) or rejected (2) from a different status, sends the notice after commit. Best effort:
  a notifier failure never affects the review; same-status saves send nothing; the notifier is injected lazily and
  optional.
- Tests: `MerchantReviewNoticeTest` (6), `InAppMerchantReviewNoticeTest` (4), `NpcFeatureFlagsTest` (key set now
  includes `merchantReviewSla`; new value test).

## Verification

- `LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -DskipTests compile`: OK.
- `mvn -B -q -pl chengyinhub-admin -am test -Dtest='MerchantReviewNoticeTest,InAppMerchantReviewNoticeTest,MmsMerchantServiceImplTest,InAppNotifyChannelRefundTest,InAppBusinessResultTest,PlayerMessagePunctuationTest,NpcFeatureFlagsTest,MemberPrivacyErasureServiceTest'`:
  53 tests, 0 failures.
- `bash ci/migration-safety-check.sh`: 迁移安全门禁:通过. `bash ci/migration-layout-check.sh`: 迁移布局门禁:通过.
- No new table, no sys_job, no `@Scheduled` method, no personal data stored.

## Not verified

- The notice on a real TEST database (approve and reject an application from the admin console, then read the
  applicant's message list). APNs push is not added: the notice is an in-app message (the existing channel).

## Owner actions and questions

- Run the migration through the normal ledger. Fill `merchant.onboarding.review-sla` when the review time is a
  commitment; until then the app names no time.
- Optional: push the decision to the phone as well (APNs via `IPushFanoutService.notifyMemberAfterCommit`); not done
  here because the package scope is a small config read.
