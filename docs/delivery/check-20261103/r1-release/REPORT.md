# r1-release: release rehearsal (first pass, in progress)

Tested commit: `8b78f5ca3c06f486631e827827e973cbb47e3094` on `integ/launch-test-20261103`.
Branch: `claude/check-1104-r1-release`. Rig in a cloud container, not a phone.

## Migrations new against master `c6eac3b8c` in the tested commit

From `ci/release_manifest.migration_diff` (the repo's own tool), order = position in the ledger's topological order (592):

| Order | File | vs master | vs rollback jar `014fc0d83` |
|---|---|---|---|
| 74 | `migration_20261101_author_write_receipt.sql` | already on master | ADDED |
| 77 | `migration_20261102_merchant_industry_categories.sql` | ADDED | ADDED |
| 78 | `migration_20261103_content_safety_recheck.sql` | ADDED | ADDED |
| 79 | `migration_20261103_sensitive_content_library.sql` | ADDED | ADDED |
| 107 | `migration_20261102_club_session_gather_point.sql` | ADDED | ADDED |
| 387 | `migration_20261103_settlement_rule_three_percent.sql` | ADDED | ADDED |
| 392 | `migration_20261102_club_checkin_phone_visible.sql` | ADDED | ADDED |

No migration is MODIFIED or DELETED against either base.

Work in progress; results follow.

STATUS: PARTIAL - in progress
