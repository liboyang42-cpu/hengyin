-- D-08: activate existing guarded onboarding and plan/submit/sync jobs.
-- This does not enable any payout feature property.
UPDATE sys_job SET status='0', update_by='migration', update_time=NOW()
WHERE invoke_target IN ('ecommercePayoutTask.shareAndRetry()', 'ecommercePayoutTask.syncOnboarding()');
-- runner: assert=scalar-true
SELECT COUNT(*)=2 AND SUM(status='0' AND concurrent='1' AND misfire_policy='3')=2
FROM sys_job WHERE invoke_target IN ('ecommercePayoutTask.shareAndRetry()', 'ecommercePayoutTask.syncOnboarding()');
