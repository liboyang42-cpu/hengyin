# 文档二集成 PR #655 · 迁移落库顺序（2026-10-28）

本集成分支相对 master 带 17 个新迁移：#625 W8 收口的 15 个，加上文档二 L8 的 2 个。
它们必须在 jar 部署**之前**手动落库：先用 chengyin-db-migrate 做只读预检，用户确认后再执行。
执行顺序按 `ci/migration-dependencies.json` 的拓扑序（由 `ci/migration_ledger.py` 的 build_manifest 算出），不要按 `ls` 的顺序。

1. `chengyinhub-admin/sql/migration_20261025_coop_invite_request_receipt.sql`（#625 W8）
2. `chengyinhub-admin/sql/migration_20261026_official_event_attendance.sql`（#625 W8）
3. `chengyinhub-admin/sql/migration_20261026_team_event_command_receipt.sql`（#625 W8）
4. `chengyinhub-admin/sql/migration_20261026_theme_attribute_registry_version.sql`（#625 W8）
5. `chengyinhub-admin/sql/migration_20261026_user_invoice_processing.sql`（#625 W8）
6. `chengyinhub-admin/sql/migration_20261026_user_invoice_title_receipt.sql`（#625 W8）
7. `chengyinhub-admin/sql/migration_20261027_template_adoption_receipt.sql`（#625 W8）
8. `chengyinhub-admin/sql/migration_20261027_user_invoice_processing_backfill.sql`（#625 W8）
9. `chengyinhub-admin/sql/migration_20261028_topic_play_setup.sql`（文档二 L8）
10. `chengyinhub-admin/sql/migration_doc2_f3_refund_hold_20261026.sql`（#625 W8）
11. `chengyinhub-admin/sql/migration_20261026_official_broadcast_delivery.sql`（#625 W8）
12. `chengyinhub-admin/sql/migration_orient_score_recovery_20261026.sql`（#625 W8）
13. `chengyinhub-admin/sql/migration_orient_score_sync_receipt_20261026.sql`（#625 W8）
14. `chengyinhub-admin/sql/migration_20261028_d20_attribute_registry.sql`（文档二 L8）
15. `chengyinhub-admin/sql/migration_w8_refund_fulfillment_recovery_20261026.sql`（#625 W8）
16. `chengyinhub-admin/sql/migration_20261026_user_invoice_menu.sql`（#625 W8）
17. `chengyinhub-admin/sql/migration_20261027_orient_scoring_menu_grants.sql`（#625 W8）

集成过程中没有对任何数据库执行这些迁移。
