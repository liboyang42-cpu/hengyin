# R13 privacy-erasure: backend receipt (hengyin `feature/r-privacy-erasure`)

Base: `origin/roadmap/be-integ` @ 4685166b7 (fog map, measurement, links and push, location trust, store-account deletion already merged). No app change was needed: the app's consent text already says kept cells last until the consent is withdrawn.

## Gaps closed

| Gap (from the receipts of) | State | How |
|---|---|---|
| Fog map Q3: deletion and withdrawal keep `roam_member_fog_cell` | Closed | Deletion deletes the rows. Withdrawing roam location consent deletes them in the same transaction as the ledger row. |
| Measurement Q4: `app_install_link` survives deletion | Closed | Deleted on deletion. |
| Links and push: `push_device` stays bound after deletion | Closed | Deleted on deletion. |
| Roam tracks (`roam_session`, `roam_fog_tile` and similar) not touched by deletion | Closed | See the table below. |
| Location trust: `location_trust_fix` and claim evidence | Closed | The trail is deleted. Evidence is anonymised. |
| Rows that already exist for members who withdrew or were deleted before this build | Closed | One-off migration `migration_20261115_privacy_erasure.sql`. |

## What happens to each table on account deletion

All of this runs in the existing per-account transaction of `AccountDeregistrationServiceImpl.executeOne`, after `deleteMemberAddresses` and before `complete`. If any step fails, the whole account erasure rolls back. The existing FAILED path then unfreezes the account, alarms and notifies the member, the same as for every other erasure step. Verification paths (SMS, WeChat, Apple) and the Apple token revocation after commit are unchanged.

| Table | Treatment | Why |
|---|---|---|
| `roam_member_fog_cell` | delete | Kept "for good" only while the account and the consent last. |
| `roam_fog_tile` | delete | One location cell per row, and no other table points at a tile id. The 30-day job keeps tile rows only for a `claim_fingerprint` that stops the same member claiming twice. A retired member id never claims again, so the fingerprint (a keyed hash of member and cell) has no purpose left. |
| `roam_session_record_archive`, `roam_share_snapshot` | delete | Session geometry. The 30-day job also deletes these by session. |
| `roam_session` | anonymise | Same as the 30-day job: `last_lat`/`last_lng`/`last_report_at`/`track_json` are set to NULL, `presence_visible=0`, and `finish_result_json` goes through the existing `RoamLocationRetention.stripJson`. `location_cleared_at` is set so the job does not revisit the session. Rows stay because XP, badge and reward ledgers refer to session ids. Distance, tile, POI and XP aggregates stay too, as they do in the job. This covers open sessions as well. |
| `roam_poi_found`, `roam_shop_visit` | anonymise | Place id (and shop name) and `claim_fingerprint` are set to NULL. Rows stay for the same ledger reasons. Before a shop visit is cleared, the existing `RoamMapper.recordAnonymousShopVisitor` registers it, exactly as the 30-day job does, so merchants' anonymous cumulative visitor counts do not drop. |
| `roam_poi_reward_log` | anonymise | Trigger coordinates and fingerprint are set to NULL. `poi_id` is set to NULL except on coupon rows, which keep it for coupon accounting (the job's rule). |
| `location_trust_fix` | delete | It is a raw coordinate trail. Its anti-abuse use is the speed baseline of this member, which no longer exists. |
| `location_claim_evidence` | anonymise | It is needed as an anti-abuse record: the SHA-256 and dHash catch another account reusing the same photo. `member_id` is set to 0 (the column is NOT NULL and no member has id 0). `photo_lat`/`photo_lng`, `fix_id` and `consumed_ref` are set to NULL. Place, hashes, verdict and `consumed_at` stay, so a used evidence row cannot be used again. |
| `app_install_link` | delete | The only table that ties an install id to the member. The anonymous `app_install_event` rows are not personal once the link is gone. |
| `push_device` | delete | |
| `consent_record`, age attestation, `account_deregistration` | **unchanged** | Kept on purpose as audit records (test asserts this). |

## Consent withdrawal

`RoamLocationConsentService.record(..., "roam_location", "REVOKE", ...)`, reached through `POST /api/compliance/consents`, now deletes the member's kept cells in the same transaction as the ledger row and the existing presence switch-off. A replayed withdrawal with the same request id runs the delete again: it is a no-op once done, and it also removes cells written before this build. An AGREE never deletes anything and never restores anything. Withdrawing `home_city_location` does not touch kept cells.

## Files

- `chengyinhub-system/.../mapper/MemberPrivacyErasureMapper.java` and `resources/mapper/business/MemberPrivacyErasureMapper.xml` (new): member-scoped, idempotent, portable SQL.
- `chengyinhub-system/.../service/impl/MemberPrivacyErasureService.java` (new): the erasure step. It returns per-table counts.
- `chengyinhub-system/.../mapper/RoamFogCellMapper.java` and its XML: new `deleteByMember`, shared by deletion and withdrawal.
- `AccountDeregistrationServiceImpl.java`: one call in the step list (field `@Autowired(required = false)`, the same pattern as the Apple revocation, so older tests can build the service bare; Spring always provides the bean).
- `RoamLocationConsentService.java`: new 4-argument `@Autowired` constructor with `RoamFogCellMapper`. The old 3-argument constructor is kept for existing tests.
- `chengyinhub-admin/sql/migration_20261115_privacy_erasure.sql` (new, id `20261115_privacy_erasure`, entry in `ci/migration-dependencies.json` depending on fog_map, measurement, links_push, location_trust and compliance_consent_deregister_20260711). It changes data only:
  - deletes kept cells where the latest roam location consent is REVOKE;
  - deletes kept cells, install links, push devices and trust fixes of `del_flag = 2` accounts;
  - anonymises their claim evidence.
  It has no structural rollback: the rows are data the members asked to remove. It is idempotent.
- Tests (new): `MemberPrivacyErasureServiceTest` (4) and `RoamConsentWithdrawalDeletesKeptCellsTest` (3). Two cases were added to `AccountDeregistrationServiceImplTest`.

## Verification

All commands were run with `LANG=C.UTF-8 LC_ALL=C.UTF-8`.

- `mvn -B -q -DskipTests compile`: passed.
- `mvn -B -q -pl chengyinhub-admin -am test -Dtest='MemberPrivacyErasureServiceTest,RoamConsentWithdrawalDeletesKeptCellsTest,AccountDeregistrationServiceImplTest,RoamLocationRevokeHidesPresenceTest' -Dsurefire.failIfNoSpecifiedTests=false`: 35 run, 0 failures.
  - The erasure test seeds all 12 tables plus 3 audit tables for member 7 and member 8. It runs the erasure with the production mapper XMLs in a Spring transaction (H2, MySQL mode) and proves that only member 7's rows are deleted or anonymised and that the audit tables are untouched.
  - A second run changes 0 rows in every step.
  - With `push_device` missing, the erasure throws and the earlier steps roll back.
  - The consent test drives the real controller route. It checks: withdrawal deletes only my cells; the replay is idempotent; AGREE neither deletes nor restores; home-city withdrawal keeps the cells.
  - The deregistration tests check that the erasure step runs after `anonymizeMember` and before `complete`/commit, and that a failure of the step rolls back with no `complete`.
- `bash ci/migration-safety-check.sh`: ends with `迁移安全门禁:通过`. The first draft of the migration used a multi-table DELETE without WHERE, which the gate rejected; it was rewritten.
- Full `mvn -B -pl chengyinhub-admin -am test`: 12,695 run, 1 failure, 34 errors, 559 skipped. This matches the baseline in the location-trust and store-account receipts:
  - The 34 errors are in 14 `com.chengyinhub.business.it.*MyBatisTest` classes. They need a live MySQL ("Communications link failure").
  - The 1 failure is `ChapterFlowWriteServiceTest.openBundleReviewCannotBeBypassedBySwitchingToSimpleMode`, which fails the same way on the untouched base.
  - No other test failed.

## Not verified

- No MySQL is available, so nothing ran against a real MySQL. The migration uses MySQL multi-table `DELETE … JOIN` / `UPDATE … JOIN` and a correlated `MAX(id)` subquery, and was only checked by the repository's SQL splitter and gate. The runtime mapper SQL is portable and ran on H2.
- `recordAnonymousShopVisitor` needs the PII blind-index key and the roam claim key guard, just as the 30-day job does. If production lacks them, a deletion of a member with un-cleared shop visits fails and is unfrozen, alarmed and reported by the existing FAILED path. It is never half-done.

## Owner and coordinator actions, open questions

1. **Apply the migration** `migration_20261115_privacy_erasure.sql` through the ledger, after the four 2026-11-15 migrations it depends on. Deploy this build only where those tables exist. The erasure step is strict on purpose: a missing table fails the deletion instead of silently skipping it.
2. **Legal: please confirm the choices.**
   - Anonymise rather than delete roam sessions, POI finds, shop visits and reward logs (aggregates and ledgers stay without location).
   - Keep claim evidence hashes, unlinked from the member, for anti-abuse.
   - The privacy policy can say: "on deletion or withdrawal, kept explored areas are deleted; footprint statistics without location may be kept".
3. **Backlog of accounts deleted before this build.** Their `roam_session` geometry and `roam_poi_found`/`roam_shop_visit` places are not erased retroactively, because session JSON stripping needs Java. The 30-day job already clears all of these for sessions finished more than 30 days ago, so the residue is at most 30 days of tracks per account deleted shortly before deploy. If legal wants it gone at once, run `MemberPrivacyErasureService.erase` once for each `del_flag = 2` member (a small admin task, not built here).
4. **Branches not in this base.** `feature/ingame-channels` (CITY claims, territory) is not in `roadmap/be-integ`. Once it merges, its claim and territory tables need the same review. Evidence `consumed_ref` is already cleared here.
