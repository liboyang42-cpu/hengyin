# Production Release Plan — `integration/backend-app-r2` → `master`

> **Docs-only release plan.** Production was not touched; `master` was not pushed; no deployment was executed. No secrets, credentials, host addresses, or tokens appear in this document.
>
> **Scope & Git References**
> - **Release candidate (`r2`):** `hengyin` `integration/backend-app-r2` at `5c2017eefaafa1e13377bc82fc78db344e8c994d` (verified on TEST `8120` / `cy_e2e_r2`; see [RESULT.md](RESULT.md)).
> - **Production baseline (`master`):** `hengyin` `origin/master` at `73f4f7be45aa5b49381baa2c1aff57e1b2245fad` (21 commits ahead of merge-base, PRs `#813`–`#829`).
> - **Merge-base:** `92eda91263159e43470c268a2eee34ca6c8b2613`.
> - **Production runtime contract:** ECS systemd unit `spring_chengyinhub-admin.service` (user `www`, port `8080`, JVM `-Duser.timezone=Asia/Shanghai`, jar `/www/wwwroot/bin/chengyinhub-admin.jar`, secrets/env via `/etc/systemd/system/spring_chengyinhub-admin.service.d/*.conf`). Never use Baota panel start/restart.

---

## 1. Database Migrations in Exact Ledger Order (`master` → `r2`)

Between merge-base `92eda9126` and `integration/backend-app-r2` (`5c2017eef`), the topological ledger (`python3 ci/migration_ledger.py manifest --target-commit 5c2017eefaafa1e13377bc82fc78db344e8c994d`, 680 total entries) introduces **29 migrations**, creating **136 new tables** and altering **1 existing production table** (`im_message`).

Additionally, `origin/master` added **2 migrations** after the merge-base (`20261113_selfplay_xpay`, `20261113_platform_pass_hardening`), listed at the end of this section so the merged release accounts for them in preflight.

### General DDL & Rollback Rules
- **DDL before JAR:** All migrations must be applied in exact ledger order via `ci/run_migration.py` (using `ci/sql_splitter.py` with runner assertions enabled) **before** deploying the new JAR.
- **No `e2e` / fixture seeds in production:** Never run `docs/delivery/e2e-seed/seed.sql`, `scripts/e2e/seed_app_gaps_a.py`, `r4/seed_r4_waitlist.sql.txt`, `be-gap-rewards-workshop/fixture_8125.sql.txt`, `r5/fixture-merchant-8120.sql.txt`, or `r6/fixture-merchant-r3-8120.sql.txt` on production.
- **Safe Rollback Posture:**
  1. **Primary rollback (application-only):** Roll back `/www/wwwroot/bin/chengyinhub-admin.jar` to `.bak.<ts>` and restart `spring_chengyinhub-admin`. All 136 new tables and additive nullable/defaulted columns are backward-compatible with the prior `master` JAR; keeping additive schema avoids destructive table drops during incident recovery.
  2. **Schema rollback (only if emergency cleanout is explicitly approved after stopping the new JAR and verifying zero production writes):** Use the per-migration rollback SQL below in **reverse ledger order** (`648 → 85`). Do **not** drop tables/columns that already received production traffic without first backing them up.

### 1.1 `r2` Migrations in Topological Ledger Order (29 Migrations)

| # | Ledger Order | Migration ID & File | `dependsOn` | Schema / Data Changes | Idempotency | DDL Lock & Backfill Risk | Rollback |
|---:|---:|---|---|---|---|---|---|
| 1 | 85 | `20261105_l4_invoice_command_receipt`<br>`chengyinhub-admin/sql/migration_20261105_l4_invoice_command_receipt.sql` | `[]` | `CREATE TABLE user_invoice_command_receipt` | **Not idempotent** (`CREATE TABLE` without `IF NOT EXISTS`; fails `1050` on rerun) | **Low.** New empty table only; no lock on existing tables; no backfill. | Keep table on JAR rollback, or `DROP TABLE IF EXISTS user_invoice_command_receipt;` |
| 2 | 86 | `20261105_l8_chain_storage`<br>`chengyinhub-admin/sql/migration_20261105_l8_chain_storage.sql` | `[]` | `CREATE TABLE IF NOT EXISTS`: `im_chain`, `im_chain_entry`, `im_chain_command_receipt`, `im_chain_entry_event` (with internal FKs among these 4 tables) | Idempotent (`IF NOT EXISTS`) | **Low.** 4 new empty tables; no FKs to existing prod tables; no backfill. | `DROP TABLE IF EXISTS im_chain_entry_event, im_chain_command_receipt, im_chain_entry, im_chain;` |
| 3 | 87 | `20261105_l8_channel_lifecycle`<br>`chengyinhub-admin/sql/migration_20261105_l8_channel_lifecycle.sql` | `[]` | `CREATE TABLE`: `im_channel`, `im_channel_op_log`, `im_channel_lifecycle_receipt` | **Not idempotent** (`CREATE TABLE` without `IF NOT EXISTS`; fails `1050` on rerun) | **Low.** 3 new empty tables; no lock on existing tables; no backfill. | `DROP TABLE IF EXISTS im_channel_lifecycle_receipt, im_channel_op_log, im_channel;` |
| 4 | 88 | `20261105_l9_friend_foundation`<br>`chengyinhub-admin/sql/migration_20261105_l9_friend_foundation.sql` | `[]` | `CREATE TABLE IF NOT EXISTS`: `member_friend_group`, `member_friend`, `member_friend_meta` | Idempotent (`IF NOT EXISTS`) | **Low.** 3 new empty tables; no backfill. | `DROP TABLE IF EXISTS member_friend_meta, member_friend, member_friend_group;` |
| 5 | 105 | `20261113_appgaps_original_request`<br>`chengyinhub-admin/sql/migration_20261113_appgaps_original_request.sql` | `[]` | `CREATE TABLE IF NOT EXISTS app_original_request` (+ scalar-true assert) | Idempotent (`IF NOT EXISTS`) | **Low.** 1 new empty table; no backfill. | `DROP TABLE IF EXISTS app_original_request;` |
| 6 | 106 | `20261113_appgaps_series_original_result`<br>`chengyinhub-admin/sql/migration_20261113_appgaps_series_original_result.sql` | `[]` | `CREATE TABLE IF NOT EXISTS club_event_series_original_result` (+ scalar-true assert) | Idempotent (`IF NOT EXISTS`) | **Low.** 1 new empty table; no backfill. | `DROP TABLE IF EXISTS club_event_series_original_result;` |
| 7 | 107 | `20261113_appgaps_support_ticket`<br>`chengyinhub-admin/sql/migration_20261113_appgaps_support_ticket.sql` | `[]` | `CREATE TABLE IF NOT EXISTS`: `support_ticket`, `support_ticket_message`, `support_ticket_event` (+ scalar-true assert) | Idempotent (`IF NOT EXISTS`) | **Low.** 3 new empty tables; no backfill. | `DROP TABLE IF EXISTS support_ticket_event, support_ticket_message, support_ticket;` |
| 8 | 108 | `20261113_gaps_a_city_game`<br>`chengyinhub-admin/sql/migration_20261113_gaps_a_city_game.sql` | `[]` | `CREATE TABLE IF NOT EXISTS` (13 tables): `city_game_board`, `city_game_point`, `city_game_property`, `city_game_service`, `city_game_team`, `city_game_participation`, `city_game_round`, `city_game_roll`, `city_game_task_attempt`, `city_game_quote`, `city_game_request`, `city_game_event`, `city_game_outing` | Idempotent (`IF NOT EXISTS`) | **Low.** 13 new empty tables; no backfill. | `DROP TABLE IF EXISTS city_game_outing, city_game_event, city_game_request, city_game_quote, city_game_task_attempt, city_game_roll, city_game_round, city_game_participation, city_game_team, city_game_service, city_game_property, city_game_point, city_game_board;` |
| 9 | 109 | `20261114_gaps_a_city_r491`<br>`chengyinhub-admin/sql/migration_20261114_gaps_a_city_r491.sql` | `['20261113_gaps_a_city_game']` | `ALTER TABLE city_game_point` (`ADD COLUMN merchant_id, place_id`), `ALTER TABLE city_game_roll` (`ADD COLUMN face_index, result_json`), plus `CREATE TABLE IF NOT EXISTS` (12 tables): `city_r491_request`, `city_arrival_challenge`, `city_arrival_evidence`, `city_task_definition`, `city_task_instance`, `city_task_attempt`, `city_acquisition_quote`, `city_acquisition_title`, `city_merchant_operation`, `city_value_visit`, `city_point_event`, `city_map_stream` | Idempotent (`PREPARE` column guards + `IF NOT EXISTS`) | **Low.** Alters only empty new tables from #8; 12 new empty tables; no backfill. | Drop the 12 new tables (`city_map_stream` … `city_r491_request`) and `ALTER TABLE city_game_point DROP COLUMN merchant_id, DROP COLUMN place_id;`, `ALTER TABLE city_game_roll DROP COLUMN face_index, DROP COLUMN result_json;` |
| 10 | 110 | `20261114_gaps_a_player`<br>`chengyinhub-admin/sql/migration_20261114_gaps_a_player.sql` | `[]` | `CREATE TABLE IF NOT EXISTS` (11 tables): `account_deregistration_result_token`, `city_orientation_answer`, `location_share_policy`, `location_share_grant`, `location_share_observation`, `location_share_event`, `topic_cover_asset`, `topic_cover_selection`, `topic_cover_request`, `topic_edit_request`, `topic_attribute_author_request` | Idempotent (`IF NOT EXISTS`) | **Low.** 11 new empty tables; no backfill. | `DROP TABLE IF EXISTS topic_attribute_author_request, topic_edit_request, topic_cover_request, topic_cover_selection, topic_cover_asset, location_share_event, location_share_observation, location_share_grant, location_share_policy, city_orientation_answer, account_deregistration_result_token;` |
| 11 | 111 | `20261115_chain_entry_request_nonunique`<br>`chengyinhub-admin/sql/migration_20261115_chain_entry_request_nonunique.sql` | `['20261105_l8_chain_storage']` | `ALTER TABLE im_chain_entry`: drops `uk_chain_entry_request`, adds non-unique `KEY idx_chain_entry_request (chain_id, request_id)` | Idempotent (`PREPARE` index guards) | **Low.** Alters empty new table from #2; no backfill. | Restore unique key only if rolling back #2 (`ALTER TABLE im_chain_entry DROP INDEX idx_chain_entry_request, ADD UNIQUE KEY uk_chain_entry_request (chain_id, request_id)` — not recommended unless reverting #2 completely) |
| 12 | 112 | `20261115_creator_maintenance`<br>`chengyinhub-admin/sql/migration_20261115_creator_maintenance.sql` | `['20261109_play_run_feedback']` | `CREATE TABLE IF NOT EXISTS`: `creator_maintenance_ticket`, `creator_maintenance_ticket_feedback` | Idempotent (`IF NOT EXISTS`) | **Low.** 2 new empty tables; no backfill. | `DROP TABLE IF EXISTS creator_maintenance_ticket_feedback, creator_maintenance_ticket;` |
| 13 | 113 | `20261116_external_game_review`<br>`chengyinhub-admin/sql/migration_20261116_external_game_review.sql` | `[]` | `CREATE TABLE IF NOT EXISTS` (5 tables): `external_game`, `external_game_submission`, `external_game_package`, `external_game_review_event`, `external_game_report` | Idempotent (`IF NOT EXISTS`) | **Low.** 5 new empty tables; no backfill. | `DROP TABLE IF EXISTS external_game_report, external_game_review_event, external_game_package, external_game_submission, external_game;` |
| 14 | 114 | `20261117_reward_pledge`<br>`chengyinhub-admin/sql/migration_20261117_reward_pledge.sql` | `[]` | `CREATE TABLE IF NOT EXISTS` (10 tables): `w17_reward_policy`, `w17_reward_pledge`, `w17_reward_pledge_version`, `w17_reward_stock`, `w17_reward_intent`, `w17_reward_award`, `w17_reward_receipt`, `w17_reward_outbox`, `w17_reward_case`, `w17_reward_op_receipt` | Idempotent (`IF NOT EXISTS`) | **Low.** 10 new empty tables; **zero policy seed rows** (rewards remain closed until owner inserts `w17_reward_policy`). | `DROP TABLE IF EXISTS w17_reward_op_receipt, w17_reward_case, w17_reward_outbox, w17_reward_receipt, w17_reward_award, w17_reward_intent, w17_reward_stock, w17_reward_pledge_version, w17_reward_pledge, w17_reward_policy;` |
| 15 | 115 | `20261117_workshop_professional`<br>`chengyinhub-admin/sql/migration_20261117_workshop_professional.sql` | `['20261116_external_game_review']` | `CREATE TABLE IF NOT EXISTS`: `workshop_professional_operation`, `workshop_professional_event` | Idempotent (`IF NOT EXISTS`) | **Low.** 2 new empty tables; no backfill. | `DROP TABLE IF EXISTS workshop_professional_event, workshop_professional_operation;` |
| 16 | 116 | `20261118_club_proxy_enroll`<br>`chengyinhub-admin/sql/migration_20261118_club_proxy_enroll.sql` | `[]` | `CREATE TABLE IF NOT EXISTS club_proxy_enroll` | Idempotent (`IF NOT EXISTS`) | **Low.** 1 new empty table; no backfill. | `DROP TABLE IF EXISTS club_proxy_enroll;` |
| 17 | 117 | `20261119_be_gap_misc`<br>`chengyinhub-admin/sql/migration_20261119_be_gap_misc.sql` | `[]` | `CREATE TABLE IF NOT EXISTS` (9 tables): `native_app_attest_guard`, `native_app_attest_key`, `native_app_attest_challenge`, `native_private_home`, `native_private_home_mutation`, `native_home_return_check`, `native_home_restore_receipt`, `us_apple_challenge`, `us_apple_account` | Idempotent (`IF NOT EXISTS`) | **Low.** 9 new empty tables; no backfill. | `DROP TABLE IF EXISTS us_apple_account, us_apple_challenge, native_home_restore_receipt, native_home_return_check, native_private_home_mutation, native_private_home, native_app_attest_challenge, native_app_attest_key, native_app_attest_guard;` |
| 18 | 118 | `20261119_merchant_gap`<br>`chengyinhub-admin/sql/migration_20261119_merchant_gap.sql` | `[]` | `CREATE TABLE IF NOT EXISTS` (13 tables): `w05_authoring_job`, `w05_authoring_draft`, `w05_authoring_revision`, `w05_authoring_approval`, `w05_authoring_release`, `w05_environment_snapshot`, `merchant_authoring_source`, `merchant_npc_knowledge_review`, `merchant_npc_avatar_job`, `merchant_offer_withdrawal_request`, `merchant_city_value_report`, `merchant_city_value_snapshot`, `merchant_city_value_fact` | Idempotent (`IF NOT EXISTS`) | **Low.** 13 new empty tables; no backfill. | `DROP TABLE IF EXISTS merchant_city_value_fact, merchant_city_value_snapshot, merchant_city_value_report, merchant_offer_withdrawal_request, merchant_npc_avatar_job, merchant_npc_knowledge_review, merchant_authoring_source, w05_environment_snapshot, w05_authoring_release, w05_authoring_approval, w05_authoring_revision, w05_authoring_draft, w05_authoring_job;` |
| 19 | 119 | `20261120_city_value_visit_feedback_at`<br>`chengyinhub-admin/sql/migration_20261120_city_value_visit_feedback_at.sql` | `['20261114_gaps_a_city_r491']` | `ALTER TABLE city_value_visit ADD COLUMN feedback_at datetime(3) DEFAULT NULL` | Idempotent (`PREPARE` column guard) | **Low.** Alters empty new table from #9; explicitly no historical backfill. | `ALTER TABLE city_value_visit DROP COLUMN feedback_at;` |
| 20 | 120 | `20261120_merchant_authoring_candidate`<br>`chengyinhub-admin/sql/migration_20261120_merchant_authoring_candidate.sql` | `['20261119_merchant_gap']` | `CREATE TABLE IF NOT EXISTS merchant_authoring_candidate` | Idempotent (`IF NOT EXISTS`) | **Low.** 1 new empty table; no backfill. | `DROP TABLE IF EXISTS merchant_authoring_candidate;` |
| 21 | 215 | `doc2_f3_bundle_extras_20261114`<br>`chengyinhub-admin/sql/migration_doc2_f3_bundle_extras_20261114.sql` | `['doc2_f3_bundle_ledger_20261022']` | `CREATE TABLE IF NOT EXISTS`: `explore_bundle_review`, `explore_bundle_renewal_intent`, `explore_bundle_appeal` | Idempotent (`IF NOT EXISTS`) | **Low.** 3 new empty tables; no backfill. | `DROP TABLE IF EXISTS explore_bundle_appeal, explore_bundle_renewal_intent, explore_bundle_review;` |
| 22 | 371 | `20261113_appgaps_approved_topic_release`<br>`chengyinhub-admin/sql/migration_20261113_appgaps_approved_topic_release.sql` | `['p3_platform_foundation']` | `CREATE TABLE IF NOT EXISTS`: `approved_topic_review_submission`, `approved_topic_release` (+ scalar-true assert) | Idempotent (`IF NOT EXISTS`) | **Low.** 2 new empty tables; no backfill. | `DROP TABLE IF EXISTS approved_topic_release, approved_topic_review_submission;` |
| 23 | 642 | `20261112_im_channels_platform`<br>`chengyinhub-admin/sql/migration_20261112_im_channels_platform.sql` | `['20260820_play_team_core', '20261012_im_media_visibility', '20261105_l8_channel_lifecycle', 'club_role_assignment_20260823', 'im_send_idempotency_20260913']` | **Alters existing prod table `im_message`** (`ADD COLUMN version INT NOT NULL DEFAULT 1`, `ADD COLUMN edited_at DATETIME(3) NULL`); alters new tables `im_channel` (`ADD COLUMN meta_version`), `im_channel_op_log` (`DROP/ADD CHECK ck_channel_op_action`); `CREATE TABLE IF NOT EXISTS` (12 tables): `im_channel_request_receipt`, `im_guild_role`, `im_guild_role_member`, `im_channel_permission_overwrite`, `im_message_revision`, `im_message_mutation`, `im_message_pin`, `im_message_mention`, `im_channel_progress_board`, `play_team_resource_pool`, `play_team_resource_use`, `play_team_pool_receipt` | **Not idempotent** (`ALTER TABLE` without column guards) | **HIGHEST ATTENTION (MDL on `im_message`).** Appending trailing nullable/defaulted columns on InnoDB MySQL 8.0 uses `ALGORITHM=INSTANT` (metadata-only, no row rewrite, no backfill of existing rows). However, `ALTER TABLE im_message` acquires a brief **Metadata Lock (MDL)**; if a long-running query holds MDL on `im_message`, this DDL will queue and block new `im_message` reads/writes. **Pre-check `SHOW PROCESSLIST` / `performance_schema.metadata_locks` and run during low traffic with `SET SESSION lock_wait_timeout = 5;`.** | **Prefer keeping columns and rolling back JAR only.** Dropping `version`/`edited_at` requires `ALTER TABLE im_message DROP COLUMN version, DROP COLUMN edited_at` (also `INSTANT` in 8.0.29+, or `INPLACE` rebuild on older 8.0) and dropping the 12 new tables (`play_team_pool_receipt` … `im_channel_request_receipt`), plus reverting `im_channel` and `im_channel_op_log`. |
| 24 | 643 | `20261113_im_channels_second_pass`<br>`chengyinhub-admin/sql/migration_20261113_im_channels_second_pass.sql` | `['20261105_l8_chain_storage', '20261105_l9_friend_foundation', '20261112_im_channels_platform']` | Alters new tables `im_channel_request_receipt` (`DROP/ADD CHECK`), `member_friend_meta`, `member_friend_group`, `im_guild_role`, `im_guild_role_member`; `CREATE TABLE IF NOT EXISTS` (12 tables): `im_moderation_action`, `im_moderation_appeal`, `im_channel_report`, `im_channel_member_state`, `im_channel_checkin`, `im_notify_pref`, `member_progress_privacy`, `im_channel_progress_privacy`, `friend_channel_invite`, `friend_team_offer`, `im_join_request`, `im_space_admission` | **Not idempotent** (`ALTER TABLE` without column guards) | **Low.** Alters only newly created empty tables from #4 and #23; 12 new empty tables; no backfill. | Drop the 12 new tables (`im_space_admission` … `im_moderation_action`); revert `ALTER`s only if rolling back #4 and #23. |
| 25 | 644 | `20261114_club_notify_default`<br>`chengyinhub-admin/sql/migration_20261114_club_notify_default.sql` | `['20261113_im_channels_second_pass']` | `CREATE TABLE IF NOT EXISTS im_club_notify_default` | Idempotent (`IF NOT EXISTS`) | **Low.** 1 new empty table; no backfill. | `DROP TABLE IF EXISTS im_club_notify_default;` |
| 26 | 645 | `20261114_im_channels_third_pass`<br>`chengyinhub-admin/sql/migration_20261114_im_channels_third_pass.sql` | `['20261012_app_media_asset', '20261012_app_media_asset_namespaces', '20261113_im_channels_second_pass']` | `CREATE TABLE IF NOT EXISTS`: `im_media_upload`, `im_onboarding_prompt`, `im_onboarding_option`, `im_onboarding_answer`; **DML seed:** `INSERT IGNORE` 1 prompt + 3 options per `club WHERE del_flag = 0` (+ 2 scalar-true asserts) | Idempotent (`IF NOT EXISTS` + `INSERT IGNORE` + `NOT EXISTS`) | **Low–Medium (data seed).** Reads `club WHERE del_flag = 0` (shared lock on read, small table: `1 prompt + 3 options` per active club; e.g., N clubs → N prompts, 3N options). | `DROP TABLE IF EXISTS im_onboarding_answer, im_onboarding_option, im_onboarding_prompt, im_media_upload;` |
| 27 | 646 | `20261115_im_channels_fourth_pass`<br>`chengyinhub-admin/sql/migration_20261115_im_channels_fourth_pass.sql` | `['20261114_im_channels_third_pass']` | `ALTER TABLE im_channel_request_receipt DROP CHECK ck_channel_receipt_family; ADD CONSTRAINT ck_channel_receipt_family ...` | **Not idempotent** (`DROP/ADD CHECK` fails if constraint missing/already updated) | **Low.** Alters new table from #23. | Revert CHECK constraint to #24 list (only if rolling back #27 without rolling back #23). |
| 28 | 647 | `20261116_im_gap_audit_webhook`<br>`chengyinhub-admin/sql/migration_20261116_im_gap_audit_webhook.sql` | `['20261105_l8_channel_lifecycle', '20261113_im_channels_second_pass', '20261115_im_channels_fourth_pass']` | Alters `im_channel_request_receipt` (`DROP/ADD CHECK` adding `'HOOK'`); `CREATE TABLE IF NOT EXISTS` (6 tables): `im_channel_audit_log`, `im_channel_audit_head`, `im_webhook_subscription`, `im_webhook_delivery`, `im_webhook_endpoint`, `im_webhook_inbound_log` | **Partially idempotent** (`DROP/ADD CHECK` is not guarded; `CREATE TABLE` is `IF NOT EXISTS`) | **Low.** Alters new table from #23; 6 new empty tables; no historical audit backfill. | `DROP TABLE IF EXISTS im_webhook_inbound_log, im_webhook_endpoint, im_webhook_delivery, im_webhook_subscription, im_channel_audit_head, im_channel_audit_log;` and revert CHECK if rolling back #28 only. |
| 29 | 648 | `20261117_im_webhook_dispatch`<br>`chengyinhub-admin/sql/migration_20261117_im_webhook_dispatch.sql` | `['20261116_im_gap_audit_webhook']` | Alters new tables `im_webhook_subscription`, `im_webhook_endpoint`, `im_webhook_delivery`, `im_webhook_inbound_log` (adds `secret_cipher`, `lease_until`, `last_attempt_at`, indexes); `CREATE TABLE IF NOT EXISTS im_webhook_inbound_nonce` | Idempotent (`PREPARE` column/index guards + `IF NOT EXISTS`) | **Low.** Alters empty new tables from #28; 1 new empty table; no backfill. | `DROP TABLE IF EXISTS im_webhook_inbound_nonce;` and drop added columns/indexes on #28 tables (or drop #28 tables directly). |

### 1.2 `origin/master` Post-Merge-Base Migrations (Verify Already Applied in Production)

| Migration ID & File | Summary | Prod Preflight Expectation |
|---|---|---|
| `20261113_selfplay_xpay`<br>`chengyinhub-admin/sql/migration_20261113_selfplay_xpay.sql` | `CREATE TABLE IF NOT EXISTS cms_registration_xpay` (idempotent; no existing table altered) | Should already be `APPLIED` on prod if `master` `#821` was deployed; if missing, apply idempotently before merged JAR. |
| `20261113_platform_pass_hardening`<br>`chengyinhub-admin/sql/migration_20261113_platform_pass_hardening.sql` | `CREATE TABLE IF NOT EXISTS platform_pass_member_lock`; guarded `ADD COLUMN` on `platform_pass_order` (16 cols) & `platform_pass_claim` (2 cols); inserts paused `sys_job` (`platformPassDeliveryTask.run()`, `status='1'`) | Should already be `APPLIED` on prod if `master` `#818` was deployed; verify with read-only column count (`= 18`) and `sys_job` check. |

---

## 2. New Config Keys, Switches, and Proposed Production Values

Production **must NOT** run `e2e-test` profile or `chengyin.e2e.test-providers=true`. Unless an owner decision explicitly opens a feature, every new switch/provider stays **OFF** or **unset** so new endpoints fail closed safely.

| Area | Config Key / Env Var | Code Default | TEST `8120` Value | Proposed **PRODUCTION** Value | Production Behavior Under Proposed Value |
|---|---|---|---|---|---|
| **Money / Cash Switches** (`CashPayoutSwitch`, `MiscMoneySwitch`) | `chengyin.release.money-payments` | `false` | `true` | **`false`** | Refuses `/api/pay/create`, `/api/asset/withdraw`, cash reward drafts, and `CASH_REFUND` reward cases with HTTP `403` (`errorCode: FEATURE_CLOSED`). |
| | `chengyin.release.payout` | `false` | `true` | **`false`** | Keeps platform cash payout paths closed (`403 FEATURE_CLOSED`). |
| **Self-Play XPAY** (`origin/master` `#821`, `SelfPlayXpayProperties`) | `chengyin.selfplay.xpay.enabled` | `false` | (not set) | **`false`** | Self-play checkout stays on existing WeChat JSAPI path (`goods=""`, `closeGraceMinutes=120` defaults). |
| **IM Webhook Dispatcher** (`ChannelWebhookDispatcher`, `ChannelWebhookService`, `ChannelWebhookInboundService`) | `chengyin.im.webhook.dispatch.enabled` | `false` | `true` | **`false`** | Outbound webhook dispatcher `@Scheduled` tick is a no-op; subscriptions/deliveries cannot trigger outbound HTTP calls. |
| | `chengyin.im.webhook.inbound-base-url` | `""` | `https://8120-…instabox.tech` | **unset / `""`** | Inbound endpoint creation refuses with `409 WEBHOOK_INBOUND_UNAVAILABLE` until an owner-approved HTTPS base URL is configured. |
| | `chengyin.im.webhook.dispatch.interval-ms`<br>`chengyin.im.webhook.dispatch.initial-delay-ms`<br>`chengyin.im.webhook.dispatch.batch`<br>`chengyin.im.webhook.dispatch.lease-ms`<br>`chengyin.im.webhook.dispatch.backoff-base-ms`<br>`chengyin.im.webhook.dispatch.backoff-max-ms`<br>`chengyin.im.webhook.dispatch.connect-timeout-ms`<br>`chengyin.im.webhook.dispatch.request-timeout-ms`<br>`chengyin.im.webhook.inbound.nonce-cleanup-ms` | `5000`<br>`15000`<br>`20`<br>`60000`<br>`30000`<br>`3600000`<br>`5000`<br>`10000`<br>`600000` | (defaults) | **Keep code defaults** | Tuning parameters; dispatcher remains disabled (`enabled=false`), and nonce cleanup harmlessly purges expired rows on empty `im_webhook_inbound_nonce`. |
| **Rewards Policy** (`w17_reward_policy` table) | DB table `w17_reward_policy` (not a property) | Empty (0 rows) | Seeded fixture (`REVIEWER`, `RULE`/`CLAIM`, `STORE_HOLD`) | **Empty (`0` rows)** | All reward pledge/review/claim operations remain closed until the owner inserts approved `w17_reward_policy` rows. |
| **US Apple Auth** (`UsAppleAuthService`, `UsAppleKeySource`) | `chengyin.us.apple.enabled` | `false` | `true` | **`false`** | `/api/us/auth/apple/{challenges,exchange}` and `/api/us/auth/session` fail closed (`503 US_APPLE_UNAVAILABLE` / `401`). |
| | `chengyin.us.apple.client-id`<br>`chengyin.us.apple.realm`<br>`chengyin.us.apple.jwks-url` | `""`<br>`""`<br>`https://appleid.apple.com/auth/keys` | `com.example.questify.us`<br>`us-test`<br>(default) | **unset / `""`** (keep default `jwks-url`) | No US realm/client-id configured; no JWKS fetches occur while `enabled=false`. |
| **App Attest** (`NativeAppAttestConfiguration`) | `chengyin.native-steps.app-attest.enabled` | `false` | `true` | **`false`** | App Attest verification remains disabled. |
| | `chengyin.native-steps.app-attest.app-id`<br>`chengyin.native-steps.app-attest.bundle-versions`<br>`chengyin.native-steps.app-attest.distribution-categories`<br>`chengyin.native-steps.app-attest.attestation-root-der-path`<br>`chengyin.native-steps.app-attest.receipt-root-der-path` | `""`<br>`""`<br>`""`<br>`""`<br>`""` | `ABCDE12345.com.chengyinhub.test`<br>`1.0`<br>`2,4`<br>`/data/.../appattest-root.der`<br>`/data/.../applerootg3.cer` | **unset / `""`** | No Apple root DER files or production App ID loaded until owner enables App Attest. |
| **External Games** (`ExternalGameSigner`, `ExternalGameReviewService`, `ExternalGameReviewTimeoutJob`) | `chengyin.external-game.signing-private-key` / `CHENGYIN_EXTERNAL_GAME_SIGNING_KEY` | `""` | Loaded from `.test-external-game-key` | **unset / `""`** | **OFF until review:** `ExternalGameSigner.configured()` is `false`; `GET /api/external-game/signing-key` returns `configured: false`; submission and package download fail closed with `503 SIGNING_UNCONFIGURED`. |
| | `chengyin.external-game.signing-key-id`<br>`chengyin.external-game.download-link-ttl-seconds`<br>`chengyin.external-game.review-timeout-days`<br>`chengyin.external-game.timeout-sweep-ms` | `ed25519-1`<br>`600`<br>`3`<br>`600000` | (defaults) | **Keep code defaults** | Timeout job sweeps empty `external_game_submission` (`status='PENDING'`) every 10 min (no-op). |
| **Private Home & Home Return** (`NativeHomePolicy`) | `chengyin.native.home.enabled`<br>`chengyin.native.home.return.enabled` | `false`<br>`false` | `true`<br>`true` | **`false`**<br>**`false`** | Private home and home-return verification/restore endpoints stay disabled. |
| | `chengyin.native.home.activation-delay-seconds`<br>`chengyin.native.home.change-cooldown-seconds`<br>`chengyin.native.home.policy-version`<br>`chengyin.native.home.return.dwell-seconds`<br>`chengyin.native.home.return.max-accuracy-meters`<br>`chengyin.native.home.return.max-fix-age-seconds`<br>`chengyin.native.home.return.proof-ttl-seconds`<br>`chengyin.native.home.return.radius-meters`<br>`chengyin.native.home.return.restore-cooldown-seconds` | `0`<br>`86400`<br>`home-return-v1`<br>`0`<br>`100`<br>`120`<br>`120`<br>`150`<br>`21600` | `home-return-test-1` (others default) | **Keep code defaults** | Ignored while `enabled=false`. |
| **Merchant W05 Authoring** (`MerchantAuthoringPolicyConfiguration`) | `chengyin.merchant.authoring.enabled` | `false` | `true` | **`false`** | Policy bean is not created; authoring actions requiring policy refuse with `CONFIG_*` until owner sets approved policy values. |
| | `chengyin.merchant.authoring.{model-version,prompt-version,cost-limit,template-ids,in-store-template-ids,visibility,timezone,branches,weather-safe-branches,environment-provider}`<br>`chengyin.merchant.authoring.{merchant-review,theme-review}` | `""`<br>`false` | TEST fixture values | **unset / `""`**<br>**`false`** | Keep unset until owner approves W05 policy parameters. |
| **Merchant NPC Avatar** (`HttpMerchantAvatarProvider`) | `chengyin.merchant.avatar.provider` | unset | unset (uses `E2eTestMerchantAvatarProvider`) | **unset** | No `MerchantAvatarProvider` bean loaded; `/api/merchant/npc/avatar/status` returns `available: false`, `generate` returns `503 AVATAR_PROVIDER_NOT_CONFIGURED`. |
| | `chengyin.merchant.avatar.http.base-url`<br>`chengyin.merchant.avatar.http.api-key`<br>`chengyin.merchant.avatar.http.styles`<br>`chengyin.merchant.avatar.http.timeout-ms` | `""`<br>`""`<br>`CARTOON,REALISTIC`<br>`15000` | unset | **unset** | Only configure if owner provisions a vendor HTTPS endpoint. |
| **Workshop Professional** (`WorkshopProfessionalService`) | `chengyin.workshop.professional.review` | `AUTO` | `AUTO` | **`AUTO`** (or `MANUAL` if owner prefers admin review) | Materializes a guarded unpublished private template; no money moves. |
| **Topic Cover Private Dir** (`TopicCoverService`) | `chengyin.topic-cover.private-dir` | `""` | unset | **unset / `""`** (or `/www/wwwroot/uploadPath-private/topic-cover` owned by `www` if enabling) | If `""`, `/api/topic/cover` upload fails closed with `503 COVER_STORAGE_UNAVAILABLE`. If enabled, directory must be outside public `/profile` and writable by `www`. |
| **Test Providers Guard** | `chengyin.e2e.test-providers` & Spring profile `e2e-test` | `false` | `true` (`e2e-test`) | **MUST NOT be set (`false`)** | Never enable `E2eTestSmsCodeSender`, `E2eTestContentSecurityService`, or `E2eTestMerchantAvatarProvider` in production. |
| **PII Encryption Key** (Existing invariant) | `CHENGYIN_WITHDRAWAL_PII_KEY` / `chengyin.pii.key` | required env | `.test-pii-key` | **Existing stable key in `pii.conf`** | **Critical:** Never randomize or rotate without migration; required by `scripts/production-preflight.sh` and used for withdrawal PII + future `im_webhook_subscription.secret_cipher`. |

---

## 3. Production Deploy Steps (with Canary & Safe-GET Health Check)

### Phase A — Pre-Merge CI & Rehearsal
1. Merge `integration/backend-app-r2` into a release branch from `origin/master` (see §5), resolve conflicts, and open PR to `master`.
2. Verify all gates pass on the exact merge commit SHA:
   - `python3 ci/migration_ledger.py audit` (`problems=[]`), `manifest --target-commit <SHA>`, `bash ci/migration-layout-check.sh`, `bash ci/migration-safety-check.sh`, `python3 ci/dup_create_table_gate.py`.
   - Unit tests (`mvn test -pl chengyinhub-framework,chengyinhub-system,chengyinhub-admin -am` with Surefire `-Xmx1536m`).
   - Real-MySQL ITs with `-Duser.timezone=Asia/Shanghai` + `Funds MySQL IT` + `MDB-F2` (`ci/real_storage_it.py`) + `release-migration-rehearsal`.
3. Merge PR into `master`; capture exact 40-char `FINAL_SHA`.

### Phase B — Backup & Read-Only Production Preflight (Before Any DDL)
1. **Full DB backup:** Execute `ops/db-backup-restore.md` / `scripts/db-backup-restore.py` on production MySQL; record backup ID and `sha256`. Do not proceed without a verified backup.
2. **Record rollback JAR:** Record current `/www/wwwroot/bin/chengyinhub-admin.jar` `sha256sum` and `/www/wwwroot/bin/chengyinhub-admin.deployed-sha`.
3. **Read-only DB preflight (via SSH tunnel, read-only session):**
   - Run `python3 ci/run_migration.py --check` and inspect `SHOW PROCESSLIST;` / `SELECT * FROM performance_schema.metadata_locks WHERE OBJECT_NAME IN ('im_message', 'club');` to ensure zero long-running transactions hold MDL on `im_message`.
   - Verify `origin/master` tables/columns (`cms_registration_xpay`, `platform_pass_member_lock`, 18 hardening columns, `sys_job` `platformPassDeliveryTask.run()`) and check none of the 136 new `r2` tables already exist unexpectedly.
   - Verify `SELECT COUNT(*) FROM club WHERE del_flag = 0;` (sizes the `[645]` onboarding seed).

### Phase C — Apply Migrations in Exact Ledger Order
1. Dry-run each migration first (`python3 ci/run_migration.py --sql <file> --dry-run`).
2. Apply migrations **1 through 29** (§1.1) strictly in ledger order using `ci/run_migration.py --config <prod-druid-yml> --sql <file>`.
   - Before `#23` (`20261112_im_channels_platform`), verify low traffic and zero MDL blockers on `im_message`; set session `lock_wait_timeout = 5` so `ALTER TABLE im_message` fails fast rather than piling up connections if a long query is active.
   - Stop immediately on any error or failed `-- runner: assert=scalar-true` assertion.
3. Verify post-migration read-only checks:
   - `SHOW COLUMNS FROM im_message LIKE 'version';` and `'edited_at'` present.
   - `SELECT COUNT(*) FROM w17_reward_policy;` = **`0`**.
   - `SELECT COUNT(*) FROM im_onboarding_prompt p WHERE NOT EXISTS (SELECT 1 FROM im_onboarding_option o WHERE o.prompt_id = p.id);` = **`0`**.

### Phase D — Production Config Audit & Canary Verification
1. **Audit systemd drop-ins** (`/etc/systemd/system/spring_chengyinhub-admin.service.d/`):
   - Confirm `scripts/production-preflight.sh --service` passes (including `Asia/Shanghai`, `CHENGYIN_WITHDRAWAL_PII_KEY`, no secret reuse).
   - Confirm none of the TEST-only flags (`chengyin.e2e.test-providers`, `chengyin.release.money-payments`, `chengyin.release.payout`, `chengyin.im.webhook.dispatch.enabled`, `chengyin.us.apple.enabled`, `chengyin.native-steps.app-attest.enabled`, `CHENGYIN_EXTERNAL_GAME_SIGNING_KEY`, `chengyin.native.home.enabled`, `chengyin.merchant.authoring.enabled`, `chengyin.merchant.avatar.provider`) are enabled.
2. **Canary deployment (single-ECS shadow-port canary before swapping `:8080`):**
   - Build exact `FINAL_SHA` jar via `.github/workflows/deploy.yml` / `mvn -B clean package -Dmaven.test.skip=true` and verify with `ci/verify-built-jar.sh "$FINAL_SHA"`.
   - Upload staged jar to `/www/wwwroot/bin/chengyinhub-admin.jar.new.$FINAL_SHA`.
   - Start a **read-only / loopback canary instance** on `127.0.0.1:8081` (as `www`, same systemd env drop-ins + `--server.port=8081 --server.address=127.0.0.1`, with `--spring.task.scheduling.enabled=false` on the canary so background `@Scheduled` jobs do not double-run alongside `:8080`).
   - Run the **Safe-GET Health Check (§3.1)** against `http://127.0.0.1:8081`.
   - Stop the `8081` canary cleanly (`kill` and wait for port release) **before** promoting to `8080`. (If multi-node ECS is later used, promote canary node 1 into Nginx upstream at low weight for 10–15 min, monitor 5xx/latency/logs, then roll remaining nodes.)
3. **Production cutover (`:8080`):**
   - Execute `ci/deploy-push.sh "$FINAL_SHA"` (runs `production-preflight.sh`, `preflight-h8-schema.sh`, `rsync`, `ci/deploy-restart.sh`, `ci/verify-deployed.sh`, and writes `.deployed-sha`).
   - Re-run the **Safe-GET Health Check (§3.1)** against `http://127.0.0.1:8080`.
   - Tail `/www/wwwlogs/java/springboot/chengyinhub-admin.log` for 10 minutes: confirm zero startup/SQL/bean/PII errors and confirm `ChannelWebhookDispatcher` is inactive.

### 3.1 Post-Deploy Health Check (Safe `GET`s Only — Reused from `RESULT.md`)

> **Strictly `GET` requests only** (no `POST`/`PUT`/`DELETE`, no `/api/sms/send`, no `login`, no state-mutating endpoints). Run against canary `http://127.0.0.1:8081` first, then `http://127.0.0.1:8080` after cutover.

**1) Protected Actuator Health (via `ci/check_deploy_health.py`):**
```bash
python3 ci/check_deploy_health.py "$DEPLOY_HEALTH_TOKEN_FILE"
# GET http://127.0.0.1:8080/actuator/health -> HTTP 200, status=UP, db=UP, redis=UP
```

**2) Anonymous / Public Safe `GET`s (no token required):**
```bash
BASE="http://127.0.0.1:8080"   # use :8081 for canary

# External games OFF check (must be configured:false)
curl -sf "$BASE/api/external-game/signing-key"
# External games catalog (empty/approved list, 200)
curl -sf "$BASE/api/external-game/catalog"
# Map weather (public GET)
curl -sf "$BASE/api/map/weather"
# Home return policy (expect closed/disabled or 401/403 when home.return.enabled=false)
curl -s -o /dev/null -w "%{http_code}\n" "$BASE/api/native/home/return/policy"
# US Apple session without token (must be 401/503 when us.apple.enabled=false)
curl -s -o /dev/null -w "%{http_code}\n" "$BASE/api/us/auth/session"
```

**3) Authenticated Read-Only `GET`s (using an existing read-only probe account Bearer token `$TOK` — never run SMS/login mutations in prod smoke):**
```bash
AUTH="Authorization: Bearer $TOK"

# Core play & channels (RESULT.md §3)
curl -sf -H "$AUTH" "$BASE/api/play/nodes?activityId=<PROBE_ACT_ID>&topicId=<PROBE_TOPIC_ID>"
curl -sf -H "$AUTH" "$BASE/api/channels?guild_kind=1&guild_id=<PROBE_CLUB_ID>"
curl -sf -H "$AUTH" "$BASE/api/guilds/1/<PROBE_CLUB_ID>/channels?with_unread=1"
curl -sf -H "$AUTH" "$BASE/api/club/settings/notify-level?clubId=<PROBE_CLUB_ID>"
curl -sf -H "$AUTH" "$BASE/api/im/audit-log?guild_kind=1&guild_id=<PROBE_CLUB_ID>&limit=5&verify=1"
curl -sf -H "$AUTH" "$BASE/api/im/webhook/context?owner_id=<PROBE_CLUB_ID>&channel_id=<PROBE_CH_ID>"
curl -sf -H "$AUTH" "$BASE/api/im/chain/info?conversation_id=<PROBE_CH_ID>&limit=20"
curl -sf -H "$AUTH" "$BASE/api/friend/requests?cursor=0&limit=20"
curl -sf -H "$AUTH" "$BASE/api/rank/list?scope=team&scopeId=<PROBE_TEAM_ID>&period=all&limit=10"

# Rewards (policy empty -> read-only empty/closed check, R4)
curl -sf -H "$AUTH" "$BASE/api/rewards/noncash"
curl -sf -H "$AUTH" "$BASE/api/reward-pledge/pledges"

# External games mine (R4)
curl -sf -H "$AUTH" "$BASE/api/external-game/submissions/mine"

# Misc OFF verification (R5: native home & device status)
curl -sf -H "$AUTH" "$BASE/api/native/device/status"
curl -s -H "$AUTH" "$BASE/api/native/home"

# Creator & Merchant read-only GETs (R2 / R5 / R6, using creator/merchant probe token if applicable)
curl -sf -H "$AUTH" "$BASE/api/creator/feedback?topicId=<PROBE_OWN_TOPIC_ID>"
curl -sf -H "$AUTH" "$BASE/api/creator/analytics/post-game-outcome?cohortDate=<YYYY-MM-DD>&windowDays=30"
curl -sf -H "$AUTH" "$BASE/api/merchant/crm/marketing-consents"
curl -sf -H "$AUTH" "$BASE/api/merchant/npc/avatar/status"   # expect available=false while provider unset
```

### 3.2 Fast Rollback Procedure
If canary fails, `verify-deployed.sh` fails, 5xx spikes, or log errors appear:
1. Restore the previous verified JAR on ECS:
   `cp -f /www/wwwroot/bin/chengyinhub-admin.jar.bak.<TIMESTAMP> /www/wwwroot/bin/chengyinhub-admin.jar && chmod 0644 /www/wwwroot/bin/chengyinhub-admin.jar`
2. Restart systemd: `systemctl restart spring_chengyinhub-admin`
3. Run `python3 ci/check_deploy_health.py "$DEPLOY_HEALTH_TOKEN_FILE"` and confirm single `MainPID` + previous SHA running.
4. Keep the 136 new tables and additive `im_message` columns in place (they do not break the old `master` JAR).

---

## 4. Data Migration & Backfill Jobs

| Item | Type | Scope & Behavior | Action Required for Prod |
|---|---|---|---|
| **Club default onboarding prompt/options** (`20261114_im_channels_third_pass.sql`) | Migration-time DML seed | `INSERT IGNORE INTO im_onboarding_prompt` + `INSERT IGNORE INTO im_onboarding_option` for every `club WHERE del_flag = 0` (`1` prompt + `3` options per active club). New clubs created later are lazily seeded on read. | Runs automatically as part of migration `#26`; verify post-migration orphan count = `0`. |
| **Historical IM messages & conversations** (`20261112_im_channels_platform.sql`) | **No backfill** | `im_message.version` (`NOT NULL DEFAULT 1`) and `edited_at` (`NULL`) rely on MySQL 8.0 metadata default; historical rows and `im_conversation` rows are untouched. | None. |
| **Channel audit log chain** (`20261116_im_gap_audit_webhook.sql`) | **No backfill** | Boundary `original-channel-audit-projection-v1; no historical backfill or external-anchor claim`. Starts recording from deploy forward. | None. |
| **CITY visit `feedback_at`** (`20261120_city_value_visit_feedback_at.sql`) | **No backfill** | Historical `city_value_visit` rows remain `feedback_at = NULL` and are excluded from `timeToFirstEffectiveChoice`. | None. |
| **Rewards policy (`w17_reward_policy`)** (`20261117_reward_pledge.sql`) | **Intentionally empty** | No policy rows are seeded. Rewards remain closed until owner inserts `REVIEWER` / `RULE` / `CLAIM` rows. | Verify `COUNT(*) = 0` after migration. |
| **Scheduled background jobs added in `r2`** | `@Scheduled` Spring tasks | 1. `ChannelWebhookDispatcher.tick()` (`5000ms` delay): **no-op** while `chengyin.im.webhook.dispatch.enabled=false`.<br>2. `ChannelWebhookInboundService.purgeNonces()` (`600000ms` delay): deletes expired rows from `im_webhook_inbound_nonce` (no-op while empty).<br>3. `ExternalGameReviewTimeoutJob.sweep()` (`600000ms` delay): sweeps `PENDING` rows past `review-timeout-days` in `external_game_submission` (no-op while empty/disabled). | Confirm `dispatch.enabled=false`; no manual `sys_job` changes needed. |
| **`origin/master` paused `sys_job`** (`20261113_platform_pass_hardening.sql`) | `sys_job` row | `platformPassDeliveryTask.run()` inserted with `status='1'` (paused). | Verify it remains paused (`status='1'`) unless XPAY is explicitly enabled by owner. |
| **PII key continuity** (`CHENGYIN_WITHDRAWAL_PII_KEY`) | Operational invariant | Used by `PiiCryptoService` for withdrawal PII and future `im_webhook_subscription.secret_cipher`. | Must remain identical across restarts (verified by `production-preflight.sh`). |

---

## 5. Merge Strategy (`integration/backend-app-r2` → `master`)

### 5.1 Trial Merge Assessment (`git merge-tree --write-tree origin/master integration/backend-app-r2`)
- `origin/master` (`73f4f7be4`) has **21 commits** after merge-base `92eda9126` (`#813`–`#829`, including `#818` Platform Pass hardening, `#821` Self-Play XPAY, and `#825` city-route photo fallback).
- Running `git merge-tree --write-tree --messages origin/master HEAD` in the worktree shows **1 textual conflict** and **3 auto-merged overlapping files**.

### 5.2 Strategy
1. Cut a clean integration branch from `origin/master`: `git checkout -b chore/merge-r2-to-master origin/master`.
2. Run `git merge --no-ff integration/backend-app-r2` (preserving `r2` commit lineage and allowing a single clean conflict-resolution commit).
3. Resolve the 1 textual conflict and audit all 3 auto-merged files below.
4. Run full CI gates (`migration_ledger.py audit`, `migration-layout-check.sh`, `migration-safety-check.sh`, `dup_create_table_gate.py`, unit tests, `Funds MySQL IT`, `PlatformPassMySqlIT`, `GenericGameplayHttpE2eIT`, `release-migration-rehearsal`) before merging into `master`.

### 5.3 File-by-File Conflict & Overlap Matrix

| # | File | Merge Status | What `origin/master` Changed | What `r2` Changed | Resolution & Verification |
|---:|---|---|---|---|---|
| 1 | `chengyinhub-system/src/main/resources/mapper/business/PlatformPassMapper.xml` | **CONFLICT (content)** | `#818`: Expanded `orderRow`/`orderColumns`/`insertOrder`/`markXpayPaid`; added T02–T10 queries (`freezeXpayIntent`, `markDelivery*`, `lockMember`, `selectOrdersForAdmin`, `markClaimsStopped`) and `selectCoverageTopics` immediately above `selectLibrary`. | Added JDBC `tinyInt1isBit=false` safety comment and changed `cms_topic.lifecycle` to `cms_topic.lifecycle + 0 as lifecycle` inside `selectLibrary`. | **Keep BOTH:** retain all `origin/master` `#818` additions including `selectCoverageTopics`, AND keep `r2`'s `cms_topic.lifecycle + 0 as lifecycle` in `selectLibrary`. Also audit `selectCoverageTopics` (which feeds `PlatformPassService.selfPlayAvailable` via `num(row.get("lifecycle"))`) and apply `cms_topic.lifecycle + 0 as lifecycle` there if production JDBC lacks `tinyInt1isBit=false`. Verify with `PlatformPassServiceTest` & `PlatformPassMySqlIT`. |
| 2 | `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/AdvancedGameRuntimeServiceImpl.java` | **Auto-merged** (disjoint hunks) | `#825`: Updated photo-check fallback branch (`r5 && "pass".equals(cfg.path("fallback"))` → `verdict = "FALLBACK_PASS"`). | Added `timeWindow(...)` and U06 `publicTurn(...)` / `turnOrder` in `publicMultiplayer`. | Review diff post-merge to confirm no semantic overlap; compile and run `AdvancedGameRuntimeServiceImplTest` + `GenericGameplayHttpE2eIT`. |
| 3 | `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/AdvancedGameRuntimeServiceImplTest.java` | **Auto-merged** (disjoint hunks) | Added photo-check fallback contract tests. | Added U06 `turn` and `timeWindow` unit tests. | Run full `AdvancedGameRuntimeServiceImplTest` suite (all tests pass). |
| 4 | `ci/migration-dependencies.json` | **Auto-merged** (valid JSON, 477 entries) | Added `"20261113_selfplay_xpay": []` and `"20261113_platform_pass_hardening": ["20261110_platform_pass"]`. | Added all 29 `r2` migration dependency entries. | Verify JSON formatting and run `ci/migration_ledger.py audit`, `ci/migration-layout-check.sh`, `ci/migration-safety-check.sh`, `ci/dup_create_table_gate.py`. |

- **Non-conflicting `origin/master` migrations (`20261113_selfplay_xpay`, `20261113_platform_pass_hardening`):** Zero table/column collisions with `r2`'s 29 migrations (`cms_registration_xpay` and `platform_pass_*` are untouched by `r2`).

---

## 6. Owner Go / No-Go Checklist

**Do NOT deploy if any item below is unchecked (`☐`) or fails.**

- [ ] **Merge & Exact-SHA CI Green:** `r2` merged cleanly into `master` (`PlatformPassMapper.xml` resolved; 4 overlapping files verified); `Main CI`, `Funds MySQL IT`, `PlatformPassMySqlIT`, `AdvancedGameRuntimeServiceImplTest`, `migration_ledger.py audit`, `migration-layout-check.sh`, `migration-safety-check.sh`, `dup_create_table_gate.py`, and `release-migration-rehearsal` all pass on `FINAL_SHA`.
- [ ] **Backup & Rollback Artifact Ready:** Full production MySQL backup taken and `sha256` recorded; current prod JAR `sha256` and `.deployed-sha` recorded; `/www/wwwroot/bin/` disk space verified for `.bak.*` retention.
- [ ] **Read-Only DB Preflight & MDL Clear:** `SHOW PROCESSLIST` / `performance_schema.metadata_locks` shows zero long transactions holding MDL on `im_message` or `club`; `origin/master` schema (`cms_registration_xpay`, `platform_pass_member_lock`, 18 hardening cols) confirmed present.
- [ ] **Migrations Applied in Ledger Order:** All 29 `r2` migrations executed via `ci/run_migration.py` in exact order (`85 → 648`), zero errors, all `-- runner: assert=scalar-true` assertions passed; `im_message.version` & `edited_at` verified; `w17_reward_policy` row count = `0`; no TEST fixture SQL applied.
- [ ] **Production Switches Verified OFF:**
  - `chengyin.release.money-payments=false`, `chengyin.release.payout=false`, `chengyin.selfplay.xpay.enabled=false`
  - `chengyin.im.webhook.dispatch.enabled=false`, `inbound-base-url` unset
  - `w17_reward_policy` empty (`0` rows)
  - `chengyin.us.apple.enabled=false`, `chengyin.native-steps.app-attest.enabled=false`
  - `CHENGYIN_EXTERNAL_GAME_SIGNING_KEY` / `chengyin.external-game.signing-private-key` unset (`configured: false`)
  - `chengyin.native.home.enabled=false`, `chengyin.native.home.return.enabled=false`
  - `chengyin.merchant.authoring.enabled=false`, `chengyin.merchant.avatar.provider` unset
  - `chengyin.e2e.test-providers` absent/`false` and `e2e-test` profile NOT active
  - `scripts/production-preflight.sh --service` passes; `CHENGYIN_WITHDRAWAL_PII_KEY` unchanged and `-Duser.timezone=Asia/Shanghai` active
- [ ] **Canary Passed (`127.0.0.1:8081`):** Staged `FINAL_SHA` JAR booted cleanly on shadow port with `--spring.task.scheduling.enabled=false`; `/actuator/health` UP (`db=UP`, `redis=UP`); all Safe-GET checks (§3.1) returned expected responses; canary process stopped cleanly before `:8080` cutover.
- [ ] **Cutover & Post-Deploy Verification Passed:** `ci/deploy-push.sh "$FINAL_SHA"` and `ci/verify-deployed.sh` green (exact JAR `sha256`, single `MainPID`, start time > deploy start, `/actuator/health` UP); §3.1 Safe-GET checks pass on `:8080`; 10-minute log watch shows zero startup/SQL/PII errors.
- [ ] **No-Go / Immediate Rollback Triggers:** Any failed migration assertion, MDL lock pileup on `im_message`, `/actuator/health` non-UP, PII decryption error, unexpected `5xx` on core play/IM/checkout GETs, or any accidentally enabled money/webhook/US/external-game switch → immediately restore `/www/wwwroot/bin/chengyinhub-admin.jar.bak.<TIMESTAMP>` and `systemctl restart spring_chengyinhub-admin`.
