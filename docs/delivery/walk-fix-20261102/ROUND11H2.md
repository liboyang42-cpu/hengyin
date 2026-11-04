# Round 11, lane h2-owner-answers

Owner's answers, 2026-11-04. Rules: `ROUND10.md` "Parallel" section and `ROUND11.md`. Work on `integ/launch-test-20261103`, `git pull --rebase` before every push, no force-push, never push to master, merge a PR or touch production.

1. **Revoked consent hides the row.** When a player has revoked per-shop consent (`merchant_onsite_data_sharing`), a participating shop that isn't the host no longer lists them on 客户 or in anything built on that roster, matching the onsite roster. The host's view and all other consent rules are unchanged. Test it with a revoke and a re-grant; extend `MerchantCrmFreeExploreRosterMySqlIT`.
2. **Rename stored text at release.** New migration files only, never editing old ones, with runner read-back assertions, safe to re-run, changing exact rows by exact old value:
   - Seeded template names, covers and text using 盲盒 become 随机卡: 「三个盲盒」, 「盲盒一/二/三:…」, 「玩法包·盲盒之…」, 「盲盒衣柜」. Sources: `migration_seed_playpack_templates_20260820`, `..._batch2_figma_20260821`, `migration_20260822_template_cover_images`.
   - `sys_menu` names and remarks with 探店日 become 自由探索: `migration_20260816_tandianri_batch3_funds_club_compensation`, `migration_20260817_tandianri_batch3_funds_club_hours_correction`, `migration_20260817_tandianri_batch5_admin_menus`.
   - `sys_job` 「竞猜轮次超时作废」 and its remark become 预测. Don't change a job's invoke target.
   Rows whose text an author has changed must not be touched. Only rename platform-seeded rows; if an author-owned copy exists, list the count for me. Register them in `ci/migration-dependencies.json`, update PR #723's ③ section, and rehearse twice on a throwaway database.
3. **Editor label 「抽卡」 becomes 「翻卡」**, wherever a user sees it.
4. **Do not do** the points 停用 switch. It's waiting for the owner.

Write `integ/ROUND11-H2-RESULT.md`. Plain words, proof per item, CI on a commit containing your work, ending with `STATUS: COMPLETE` or `STATUS: PARTIAL - <reason>`.
