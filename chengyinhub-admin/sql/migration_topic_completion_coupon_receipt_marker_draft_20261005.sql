-- DRAFT ONLY. NEVER EXECUTED. Must be reviewed/deployed together with receipt protocol01ef + this revision.
-- There is no supported stage that deploys01ef first and later calls its unmarked receipts legacy.
-- NULL with no receipt denotes existing legacy; no historical values are backfilled.
ALTER TABLE cms_topic_completion_coupon_reward_log ADD COLUMN receipt_contract_version SMALLINT NULL DEFAULT NULL;
