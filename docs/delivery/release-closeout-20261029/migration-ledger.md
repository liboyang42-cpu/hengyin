# 迁移差异台账（release-closeout-20261028）

> 只读静态分析：没有连接任何数据库或主机，没有执行任何 SQL。
> **applied? 全部为 UNKNOWN**。本文件不声称任何迁移已在生产执行或尚未执行；只有 owner 跑完只读预检（[release-migration-precheck.sql](release-migration-precheck.sql)）并留存回执后，才能逐项回填。

## 0 口径

| 项 | 值 |
|---|---|
| 生产参考基线 | `19408d49172c`（Merge #452），与 10-22 包相同 |
| 上一个发布包 | [release-closeout-20261022](../release-closeout-20261022/release-migration-pack.md)，源 `11c8055e`，35 项，生产执行状态 UNKNOWN（`_precheck_remove_wildlife_theme_20261028.sql` 记载 10-28 生产仍没有 `explore_bundle_*` 表，说明 10-22 包至少未全部落库） |
| 本包源提交（FINAL SHA） | `b739b00c082047c57167c5541682f443b4b2dda5`（Merge #640，2026-10-28；#661 已先合入） |
| 发布集 | `release_manifest.migration_diff(19408d49, b739b00c)`：60 项 = 10-22 包 35 项 + 10-23 后新增 25 项；新增 59、修改 1（`20261013`）、删除 0 |
| 受管迁移总数 | 源提交 545 个 |
| 执行序 | 与 runner 一致：先按 `ci/migration-dependencies.json` 的显式依赖，再按路径字节序拓扑排序（`ci/migration_ledger.py`）。下表“执行序”是全量拓扑位置，只按 step 从小到大执行 |
| 阶段 | PRE_DEPLOY_REQUIRED 24 / PRE_DEPLOY 30 / OWNER_GATED_DATA 6 |
| 复现 | `python3 ci/release_manifest.py diff --base 19408d49172c1d8fd16ec012f4498ff8ea584a37 --head b739b00c082047c57167c5541682f443b4b2dda5` |

## 1 有序清单

| step | 执行序 | 来源 | 变更 | 文件 | sha256 | 依赖（直接） | phase | applied? |
|---|---|---|---|---|---|---|---|---|
| 1 | 45 | 10-22 包 | 新增 | `migration_20261018_infomation_change_audit.sql` | `0e023798a882…` | — | PRE_DEPLOY | UNKNOWN |
| 2 | 46 | 10-22 包 | 新增 | `migration_20261022_audit_activity_switch_seed.sql` | `4a9d4e63cde4…` | — | PRE_DEPLOY | UNKNOWN |
| 3 | 47 | 10-22 包 | 新增 | `migration_20261022_f7_minor_guardian.sql` | `c375ee6846c1…` | — | PRE_DEPLOY | UNKNOWN |
| 4 | 48 | 10-22 包 | 新增 | `migration_20261022_f7_user_invoice.sql` | `1ee515288464…` | — | PRE_DEPLOY | UNKNOWN |
| 5 | 49 | 10-22 包 | 新增 | `migration_20261022_location_guard_switch_seed.sql` | `8eac6d4ec7c8…` | — | PRE_DEPLOY | UNKNOWN |
| 6 | 50 | 10-22 包 | 新增 | `migration_20261022_merchant_desk_switch_seed.sql` | `99ccf252582d…` | — | PRE_DEPLOY | UNKNOWN |
| 7 | 51 | 10-22 包 | 新增 | `migration_20261023_creative_square_like_unique.sql` | `8fe2deeef171…` | — | PRE_DEPLOY_REQUIRED | UNKNOWN |
| 8 | 52 | 10-22 包 | 新增 | `migration_20261023_points_value_config_seed.sql` | `c3b7d857e6c0…` | — | PRE_DEPLOY | UNKNOWN |
| 9 | 53 | 10-23 后新增 | 新增 | `migration_20261023_wx_pay_order_intent.sql` | `da55f93a46f7…` | — | PRE_DEPLOY_REQUIRED | UNKNOWN |
| 10 | 54 | 10-23 后新增 | 新增 | `migration_20261024_cash_withdraw_transfer_intent.sql` | `f2488f404fa9…` | — | PRE_DEPLOY_REQUIRED | UNKNOWN |
| 11 | 55 | 10-23 后新增 | 新增 | `migration_20261024_community_open_flags.sql` | `b936a66c590a…` | — | PRE_DEPLOY | UNKNOWN |
| 12 | 56 | 10-23 后新增 | 新增 | `migration_20261025_coop_invite_request_receipt.sql` | `0d3bbaf69303…` | — | PRE_DEPLOY_REQUIRED | UNKNOWN |
| 13 | 57 | 10-23 后新增 | 新增 | `migration_20261026_official_event_attendance.sql` | `b49da83d06dd…` | — | PRE_DEPLOY_REQUIRED | UNKNOWN |
| 14 | 58 | 10-23 后新增 | 新增 | `migration_20261026_team_event_command_receipt.sql` | `06888f11813b…` | — | PRE_DEPLOY_REQUIRED | UNKNOWN |
| 15 | 59 | 10-23 后新增 | 新增 | `migration_20261026_theme_attribute_registry_version.sql` | `6864624782c1…` | — | PRE_DEPLOY_REQUIRED | UNKNOWN |
| 16 | 60 | 10-23 后新增 | 新增 | `migration_20261026_user_invoice_processing.sql` | `35067817cedc…` | `20261022_f7_user_invoice` | PRE_DEPLOY_REQUIRED | UNKNOWN |
| 17 | 61 | 10-23 后新增 | 新增 | `migration_20261026_user_invoice_title_receipt.sql` | `db0dfe988c1d…` | `20261022_f7_user_invoice` | PRE_DEPLOY_REQUIRED | UNKNOWN |
| 18 | 62 | 10-23 后新增 | 新增 | `migration_20261027_template_adoption_receipt.sql` | `9c72d1698d6d…` | — | PRE_DEPLOY_REQUIRED | UNKNOWN |
| 19 | 63 | 10-23 后新增 | 新增 | `migration_20261027_user_invoice_processing_backfill.sql` | `cb3554540f42…` | `20261022_f7_user_invoice`, `20261026_user_invoice_processing` | OWNER_GATED_DATA | UNKNOWN |
| 20 | 64 | 10-23 后新增 | 新增 | `migration_20261028_topic_play_setup.sql` | `15aeb5092729…` | — | PRE_DEPLOY_REQUIRED | UNKNOWN |
| 21 | 80 | 10-22 包 | 新增 | `migration_audit_problem_20261022.sql` | `02353f0296b4…` | — | PRE_DEPLOY | UNKNOWN |
| 22 | 119 | 10-22 包 | 新增 | `migration_club_welcome_note_20261022.sql` | `668c8955b2c9…` | `club_open_settings_20260909` | PRE_DEPLOY | UNKNOWN |
| 23 | 152 | 10-22 包 | 新增 | `migration_doc2_f3_bundle_ledger_20261022.sql` | `f94e363d8492…` | — | PRE_DEPLOY | UNKNOWN |
| 24 | 153 | 10-23 后新增 | 新增 | `migration_doc2_f3_refund_hold_20261026.sql` | `74b1ef1e1488…` | — | PRE_DEPLOY | UNKNOWN |
| 25 | 198 | 10-22 包 | 新增 | `migration_member_profile_version_20261022.sql` | `8be4a0d82578…` | — | PRE_DEPLOY_REQUIRED | UNKNOWN |
| 26 | 200 | 10-22 包 | 新增 | `migration_member_visited_poi_poi_key_20261022.sql` | `ddb6c4652f81…` | `member_visited_poi` | PRE_DEPLOY | UNKNOWN |
| 27 | 206 | 10-22 包 | 新增 | `migration_merchant_category_ids_20261022.sql` | `e26703b2a6cb…` | — | PRE_DEPLOY_REQUIRED | UNKNOWN |
| 28 | 212 | 10-23 后新增 | 新增 | `migration_20261027_merchant_brand_images_to_gallery.sql` | `e7f68c884d43…` | `merchant_decor_20260708` | OWNER_GATED_DATA | UNKNOWN |
| 29 | 214 | 10-22 包 | 新增 | `migration_merchant_desk_receipt_20261022.sql` | `83746d2cb7e5…` | — | PRE_DEPLOY | UNKNOWN |
| 30 | 225 | 10-22 包 | 新增 | `migration_merchant_redemption_anchor_20261022.sql` | `907c01f72fb7…` | — | PRE_DEPLOY | UNKNOWN |
| 31 | 229 | 10-22 包 | 新增 | `migration_merchant_wechat_qrcode_20261022.sql` | `c5f52a9512e6…` | — | PRE_DEPLOY_REQUIRED | UNKNOWN |
| 32 | 251 | 10-22 包 | 新增 | `migration_o14_city_code_backfill_log_20261022.sql` | `cc5f6e4dd6e8…` | — | PRE_DEPLOY | UNKNOWN |
| 33 | 252 | 10-22 包 | 新增 | `migration_o14_cms_activity_city_code_20261022.sql` | `6f8aa3785069…` | — | PRE_DEPLOY | UNKNOWN |
| 34 | 253 | 10-22 包 | 新增 | `migration_o14_cms_topic_city_code_20261022.sql` | `fcda5dcf5bf4…` | — | PRE_DEPLOY | UNKNOWN |
| 35 | 254 | 10-22 包 | 新增 | `migration_o14_content_city_code_backfill_20261022.sql` | `abfb4c27b321…` | `o14_city_code_backfill_log_20261022`, `o14_cms_activity_city_code_20261022`, `o14_cms_topic_city_code_20261022` | OWNER_GATED_DATA | UNKNOWN |
| 36 | 255 | 10-22 包 | 新增 | `migration_o14_content_search_document_20261022.sql` | `1d153c6fbbe8…` | — | PRE_DEPLOY | UNKNOWN |
| 37 | 259 | 10-23 后新增 | 新增 | `migration_20261026_official_broadcast_delivery.sql` | `ca5538fae79b…` | `official_broadcast` | PRE_DEPLOY_REQUIRED | UNKNOWN |
| 38 | 272 | 10-22 包 | 新增 | `migration_orient_geo_ar_team_20261022.sql` | `d297c399d1c1…` | — | PRE_DEPLOY | UNKNOWN |
| 39 | 273 | 10-23 后新增 | 新增 | `migration_orient_score_recovery_20261026.sql` | `7ad0d698bd3b…` | — | PRE_DEPLOY_REQUIRED | UNKNOWN |
| 40 | 274 | 10-23 后新增 | 新增 | `migration_orient_score_sync_receipt_20261026.sql` | `615f1a9e64f4…` | — | PRE_DEPLOY_REQUIRED | UNKNOWN |
| 41 | 305 | 10-22 包 | 新增 | `migration_o14_reco_search_jobs_20261022.sql` | `046921a70dbb…` | `o14_content_search_document_20261022`, `p3_platform_foundation` | PRE_DEPLOY | UNKNOWN |
| 42 | 319 | 10-22 包 | 新增 | `migration_play_arrive_guard_log_20261022.sql` | `52b937c5468f…` | — | PRE_DEPLOY | UNKNOWN |
| 43 | 328 | 10-22 包 | 新增 | `migration_pms_sku_booking_20261022.sql` | `0057c4368f7a…` | — | PRE_DEPLOY | UNKNOWN |
| 44 | 350 | 10-22 包 | 新增 | `migration_orient_scoring_20261022.sql` | `17893c96611a…` | `20260823_topic_route_graph` | PRE_DEPLOY | UNKNOWN |
| 45 | 351 | 10-22 包 | 新增 | `migration_orient_session_lifecycle_20261022.sql` | `ffc9001685f8…` | `20260823_topic_route_graph` | PRE_DEPLOY_REQUIRED | UNKNOWN |
| 46 | 393 | 10-22 包 | 新增 | `migration_member_visited_poi_poi_key_backfill_20261022.sql` | `2980391167e7…` | `member_visited_poi_poi_key_20261022`, `roam` | OWNER_GATED_DATA | UNKNOWN |
| 47 | 404 | 10-22 包 | 新增 | `migration_roam_share_version_20261022.sql` | `563ddd80ae4e…` | `20260917_roam_share_snapshot` | PRE_DEPLOY_REQUIRED | UNKNOWN |
| 48 | 409 | 10-23 后新增 | 新增 | `migration_20261024_official_roam_challenges.sql` | `dbe4bae0ce20…` | `20261012_roam_claim_fingerprints`, `city_merchant_node`, `roam` | PRE_DEPLOY_REQUIRED | UNKNOWN |
| 49 | 410 | 10-23 后新增 | 新增 | `migration_20261025_official_poi_quotes.sql` | `7a0f93fc0dfd…` | `20261024_official_roam_challenges` | PRE_DEPLOY_REQUIRED | UNKNOWN |
| 50 | 444 | 10-22 包 | 新增 | `migration_20261022_theme_state_key_attribute_def.sql` | `8b69583c2479…` | `theme_state_key_20260916` | PRE_DEPLOY_REQUIRED | UNKNOWN |
| 51 | 445 | 10-22 包 | 新增 | `migration_20261022_theme_character.sql` | `a0a8637bdf95…` | `20261022_theme_state_key_attribute_def`, `theme_state_key_20260916` | PRE_DEPLOY_REQUIRED | UNKNOWN |
| 52 | 458 | 10-23 后新增 | 新增 | `migration_20261028_d20_attribute_registry.sql` | `9cba286e9a99…` | `20261022_theme_state_key_attribute_def`, `theme_state_key_20260916`, `topic_journey_rules_20260916` | PRE_DEPLOY_REQUIRED | UNKNOWN |
| 53 | 470 | 10-23 后新增 | 新增 | `migration_20261028_remove_wildlife_theme.sql` | `96a0967b9529…` | `publish_solo_story_topics_20260827`, `seed_official_solo_story_topics_20260823` | OWNER_GATED_DATA | UNKNOWN |
| 54 | 482 | 10-23 后新增 | 新增 | `migration_20261028_template_shelf_list_free_explore.sql` | `6779e927a590…` | `seed_shanghai_solo_topics_20260822`, `seed_topic_template_shelf_20260726` | OWNER_GATED_DATA | UNKNOWN |
| 55 | 501 | 10-22 包 | 新增 | `migration_verification_review_evidence_20261022.sql` | `9b13f58da445…` | — | PRE_DEPLOY | UNKNOWN |
| 56 | 504 | 10-23 后新增 | 新增 | `migration_w8_refund_fulfillment_recovery_20261026.sql` | `28b65fad53c1…` | `p3_phase_c_web_rules_refund` | PRE_DEPLOY | UNKNOWN |
| 57 | 509 | 10-23 后新增 | 新增 | `migration_20261026_user_invoice_menu.sql` | `078d59279328…` | `20261012_missing_business_menu`, `20261026_user_invoice_processing`, `20261026_user_invoice_title_receipt` | PRE_DEPLOY | UNKNOWN |
| 58 | 524 | 10-22 包 | 修改 | `migration_20261013_admin_role_page_grants.sql` | `1f147a2136e9…` | `20261012_media_review_menu`, `20261012_missing_business_menu`, `role2_analytics_perm_20260918`, `web_admin_roles_20260719` | PRE_DEPLOY | UNKNOWN |
| 59 | 525 | 10-22 包 | 新增 | `migration_20261016_admin_role_payee_separation_forward.sql` | `4eeef3936f72…` | `20260816_tandianri_batch3_funds_club_compensation`, `20260816_tandianri_batch3_funds_club_payee`, `20260817_tandianri_batch3_funds_club_hours_correction`, `20261013_admin_role_page_grants` | PRE_DEPLOY | UNKNOWN |
| 60 | 526 | 10-23 后新增 | 新增 | `migration_20261027_orient_scoring_menu_grants.sql` | `6af4376c7606…` | `20261012_missing_business_menu`, `topic_source_menu_20260722`, `web_admin_roles_20260719` | PRE_DEPLOY | UNKNOWN |

每项的变更说明、幂等守卫、锁风险和 SELECT-only 回读见 [release-migration-pack.md](release-migration-pack.md) 第 1 节。

## 2 10-23 之后新增 25 项的来源

- PR #655（文档二 UI 实施合并）正文列出的 17 个：W8 收口 15 个（`coop_invite_request_receipt`、`official_event_attendance`、`team_event_command_receipt`、`theme_attribute_registry_version`、`user_invoice_processing`、`user_invoice_title_receipt`、`template_adoption_receipt`、`user_invoice_processing_backfill`、`doc2_f3_refund_hold`、`official_broadcast_delivery`、`orient_score_recovery`、`orient_score_sync_receipt`、`w8_refund_fulfillment_recovery`、`user_invoice_menu`、`orient_scoring_menu_grants`）+ 文档二 L8 2 个（`topic_play_setup`、`d20_attribute_registry`）。全部在本包。
- #641（devfix 集成）：`20261027_merchant_brand_images_to_gallery`。
- 其他 10-23 之后合入的：`20261023_wx_pay_order_intent`、`20261024_cash_withdraw_transfer_intent`、`20261024_community_open_flags`、`20261024_official_roam_challenges`、`20261025_official_poi_quotes`、`20261028_remove_wildlife_theme`、`20261028_template_shelf_list_free_explore`。
- #661、#640 没有带迁移。

## 3 静态结论与人工核对点

- 没有既有迁移被删除；`ci/migration_gates.check_sql_text` 对 60 个文件都没有报问题；本包没有新增或修改任何迁移文件。
- 不可直接重跑（单次执行）：`club_welcome_note_20261022`、`20261023_wx_pay_order_intent`、`20261024_cash_withdraw_transfer_intent`、`20261026_official_broadcast_delivery`。只在预检 NEEDED 时执行一次，APPLIED 跳过。
- `20261023_wx_pay_order_intent.sql` 文件头仍写 “PROPOSAL ONLY”，但本 SHA 的支付代码已读写该表：执行前 owner 单独确认。
- `20261013_admin_role_page_grants` 的 checksum 对账与“NEEDED 时禁止执行”沿用 10-22 台账第 1 节。
- `20261016` 有 2 条带 WHERE 的定点 DELETE（收款审核/打款职责分离），会改变生产授权。
- 数据类 6 项（OWNER_GATED_DATA）先跑预检附录 B / C 的只读预览，owner 抽检后再执行。
- 静态分析不能代替只读预检，只读预检也不能代替 owner 确认。
