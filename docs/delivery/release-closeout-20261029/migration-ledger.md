# 迁移差异台账（release-closeout-20261029）

> 只读静态分析：没有连接任何数据库或主机，没有执行任何 SQL。
> **applied? 全部为 UNKNOWN**。本文件不声称任何迁移已在生产执行或尚未执行；只有 owner 跑完只读预检（[release-migration-precheck.sql](release-migration-precheck.sql)）并留存回执后，才能逐项回填。

## 0 口径

| 项 | 值 |
|---|---|
| 生产参考基线 | `19408d49172c`（Merge #452），与 10-22、10-28 包相同 |
| 上一个发布包 | [release-closeout-20261028](../release-closeout-20261028/release-migration-pack.md)，FINAL `b739b00c`，60 项，生产执行状态 UNKNOWN；#660 合入后过期 |
| 本包 FINAL SHA（jar / 体验版） | `963b351c5adf55934a20e34010e2f90e473296dd`（Merge pull request #662 from liboyang42-cpu/codex/fix-gameplay-http16-20261028） |
| 迁移源提交 | `60048713ab6a57890a056fe3091f5be441919939` = FINAL SHA + 1 个提交（新增 `migration_20261029_unlist_test_topics.sql` 与依赖声明） |
| 发布集 | `release_manifest.migration_diff(19408d49, 源提交)`：88 项 = 10-28 包 60 项 + #660 27 项 + 本包新增 1 项；新增 87、修改 1（`20261013`）、删除 0 |
| 受管迁移总数 | 源提交 573 个 |
| 执行序 | 与 runner 一致：先按 `ci/migration-dependencies.json` 的显式依赖，再按路径字节序拓扑排序（`ci/migration_ledger.py`）。下表“执行序”是全量拓扑位置，只按 step 从小到大执行；owner 实际按 release-steps.md 的 A/B/C 轮执行 |
| 阶段 | PRE_DEPLOY_REQUIRED 50 / PRE_DEPLOY 30 / OWNER_GATED_DATA 8；执行轮次 A 51 / B 29 / C 8 |
| 复现 | `python3 ci/release_manifest.py diff --base 19408d49172c1d8fd16ec012f4498ff8ea584a37 --head 60048713ab6a57890a056fe3091f5be441919939` |

## 1 有序清单

| step | 执行序 | 来源 | 变更 | 文件 | sha256 | 依赖（直接） | phase | 重入策略 | applied? |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 45 | 10-22 包 | 新增 | `migration_20261018_infomation_change_audit.sql` | `0e023798a882…` | — | PRE_DEPLOY | 可重入 | UNKNOWN |
| 2 | 46 | 10-22 包 | 新增 | `migration_20261022_audit_activity_switch_seed.sql` | `4a9d4e63cde4…` | — | PRE_DEPLOY | 可重入 | UNKNOWN |
| 3 | 47 | 10-22 包 | 新增 | `migration_20261022_f7_minor_guardian.sql` | `c375ee6846c1…` | — | PRE_DEPLOY | 可重入 | UNKNOWN |
| 4 | 48 | 10-22 包 | 新增 | `migration_20261022_f7_user_invoice.sql` | `1ee515288464…` | — | PRE_DEPLOY | 可重入 | UNKNOWN |
| 5 | 49 | 10-22 包 | 新增 | `migration_20261022_location_guard_switch_seed.sql` | `8eac6d4ec7c8…` | — | PRE_DEPLOY | 可重入 | UNKNOWN |
| 6 | 50 | 10-22 包 | 新增 | `migration_20261022_merchant_desk_switch_seed.sql` | `99ccf252582d…` | — | PRE_DEPLOY | 可重入 | UNKNOWN |
| 7 | 51 | 10-22 包 | 新增 | `migration_20261023_creative_square_like_unique.sql` | `8fe2deeef171…` | — | PRE_DEPLOY_REQUIRED | 可重入 | UNKNOWN |
| 8 | 52 | 10-22 包 | 新增 | `migration_20261023_points_value_config_seed.sql` | `c3b7d857e6c0…` | — | PRE_DEPLOY | 可重入 | UNKNOWN |
| 9 | 53 | 10-23 后新增 | 新增 | `migration_20261023_wx_pay_order_intent.sql` | `da55f93a46f7…` | — | PRE_DEPLOY_REQUIRED | 单次执行 | UNKNOWN |
| 10 | 54 | 10-23 后新增 | 新增 | `migration_20261024_community_open_flags.sql` | `b936a66c590a…` | — | PRE_DEPLOY | 可重入 | UNKNOWN |
| 11 | 55 | #660 | 新增 | `migration_20261024_d_committed_official_notice_wx.sql` | `132e2d74d13a…` | `20261013_b_im_notification_intent` | PRE_DEPLOY_REQUIRED | 单次执行 | UNKNOWN |
| 12 | 56 | #660 | 新增 | `migration_20261024_c_before_start_notice_typed.sql` | `615c8c7883a9…` | `20261024_d_committed_official_notice_wx` | PRE_DEPLOY_REQUIRED | **永不执行** | UNKNOWN |
| 13 | 57 | #660 | 新增 | `migration_20261024_d_npc_asr_intent.sql` | `eab5fa521fa2…` | — | PRE_DEPLOY_REQUIRED | 单次执行 | UNKNOWN |
| 14 | 58 | #660 | 新增 | `migration_20261024_d_npc_asr_shared_quota.sql` | `ed8a14aeabb2…` | `20261024_d_npc_asr_intent` | PRE_DEPLOY_REQUIRED | 单次执行 | UNKNOWN |
| 15 | 59 | #660 | 新增 | `migration_20261024_g_before_start_notice_metadata_v2.sql` | `a36889f13d1d…` | `20261024_d_committed_official_notice_wx` | PRE_DEPLOY_REQUIRED | 单次执行 | UNKNOWN |
| 16 | 60 | #660 | 新增 | `migration_20261024_registration_verified_seats.sql` | `8d1912dd674a…` | — | PRE_DEPLOY_REQUIRED | 单次执行 | UNKNOWN |
| 17 | 61 | #660 | 新增 | `migration_20261024_registration_seat_activity_scope.sql` | `b2c1aabc496c…` | `20261024_registration_verified_seats` | PRE_DEPLOY_REQUIRED | 可重入 | UNKNOWN |
| 18 | 62 | 10-23 后新增 | 新增 | `migration_20261025_coop_invite_request_receipt.sql` | `0d3bbaf69303…` | — | PRE_DEPLOY_REQUIRED | 可重入 | UNKNOWN |
| 19 | 63 | 10-23 后新增 | 新增 | `migration_20261026_official_event_attendance.sql` | `b49da83d06dd…` | — | PRE_DEPLOY_REQUIRED | 可重入 | UNKNOWN |
| 20 | 64 | 10-23 后新增 | 新增 | `migration_20261026_team_event_command_receipt.sql` | `06888f11813b…` | — | PRE_DEPLOY_REQUIRED | 可重入 | UNKNOWN |
| 21 | 65 | 10-23 后新增 | 新增 | `migration_20261026_theme_attribute_registry_version.sql` | `6864624782c1…` | — | PRE_DEPLOY_REQUIRED | 可重入 | UNKNOWN |
| 22 | 66 | 10-23 后新增 | 新增 | `migration_20261026_user_invoice_processing.sql` | `35067817cedc…` | `20261022_f7_user_invoice` | PRE_DEPLOY_REQUIRED | 可重入 | UNKNOWN |
| 23 | 67 | 10-23 后新增 | 新增 | `migration_20261026_user_invoice_title_receipt.sql` | `db0dfe988c1d…` | `20261022_f7_user_invoice` | PRE_DEPLOY_REQUIRED | 可重入 | UNKNOWN |
| 24 | 68 | 10-23 后新增 | 新增 | `migration_20261027_template_adoption_receipt.sql` | `9c72d1698d6d…` | — | PRE_DEPLOY_REQUIRED | 可重入 | UNKNOWN |
| 25 | 69 | 10-23 后新增 | 新增 | `migration_20261027_user_invoice_processing_backfill.sql` | `cb3554540f42…` | `20261022_f7_user_invoice`, `20261026_user_invoice_processing` | OWNER_GATED_DATA | 可重入 | UNKNOWN |
| 26 | 70 | 10-23 后新增 | 新增 | `migration_20261028_topic_play_setup.sql` | `15aeb5092729…` | — | PRE_DEPLOY_REQUIRED | 可重入 | UNKNOWN |
| 27 | 82 | #660 | 新增 | `migration_ai_theme_draft_request_intent_20261024.sql` | `d0ff6289481f…` | — | PRE_DEPLOY_REQUIRED | 单次执行 | UNKNOWN |
| 28 | 87 | 10-22 包 | 新增 | `migration_audit_problem_20261022.sql` | `02353f0296b4…` | — | PRE_DEPLOY | 可重入 | UNKNOWN |
| 29 | 103 | #660 | 新增 | `migration_20261024_d_chapter_application_round.sql` | `800f1cd74678…` | `chapter_recruitment_20260722` | PRE_DEPLOY_REQUIRED | 单次执行 | UNKNOWN |
| 30 | 127 | 10-22 包 | 新增 | `migration_club_welcome_note_20261022.sql` | `668c8955b2c9…` | `club_open_settings_20260909` | PRE_DEPLOY | 单次执行 | UNKNOWN |
| 31 | 160 | 10-22 包 | 新增 | `migration_doc2_f3_bundle_ledger_20261022.sql` | `f94e363d8492…` | — | PRE_DEPLOY | 可重入 | UNKNOWN |
| 32 | 161 | 10-23 后新增 | 新增 | `migration_doc2_f3_refund_hold_20261026.sql` | `74b1ef1e1488…` | — | PRE_DEPLOY | 可重入 | UNKNOWN |
| 33 | 202 | #660 | 新增 | `migration_20261023_cash_withdraw_fund_fact.sql` | `cf981d89214b…` | `member_cash_withdraw` | PRE_DEPLOY_REQUIRED | 单次执行 | UNKNOWN |
| 34 | 203 | 10-23 后新增 | 新增 | `migration_20261024_cash_withdraw_transfer_intent.sql` | `f2488f404fa9…` | `20261023_cash_withdraw_fund_fact` | PRE_DEPLOY_REQUIRED | 单次执行 | UNKNOWN |
| 35 | 204 | #660 | 新增 | `migration_20261025_cash_withdraw_fund_request_compat.sql` | `7f94d39360c9…` | `20261023_cash_withdraw_fund_fact` | PRE_DEPLOY_REQUIRED | 单次执行 | UNKNOWN |
| 36 | 209 | 10-22 包 | 新增 | `migration_member_profile_version_20261022.sql` | `8be4a0d82578…` | — | PRE_DEPLOY_REQUIRED | 可重入 | UNKNOWN |
| 37 | 211 | 10-22 包 | 新增 | `migration_member_visited_poi_poi_key_20261022.sql` | `ddb6c4652f81…` | `member_visited_poi` | PRE_DEPLOY | 可重入 | UNKNOWN |
| 38 | 217 | 10-22 包 | 新增 | `migration_merchant_category_ids_20261022.sql` | `e26703b2a6cb…` | — | PRE_DEPLOY_REQUIRED | 可重入 | UNKNOWN |
| 39 | 223 | 10-23 后新增 | 新增 | `migration_20261027_merchant_brand_images_to_gallery.sql` | `e7f68c884d43…` | `merchant_decor_20260708` | OWNER_GATED_DATA | 可重入 | UNKNOWN |
| 40 | 225 | 10-22 包 | 新增 | `migration_merchant_desk_receipt_20261022.sql` | `83746d2cb7e5…` | — | PRE_DEPLOY | 可重入 | UNKNOWN |
| 41 | 236 | 10-22 包 | 新增 | `migration_merchant_redemption_anchor_20261022.sql` | `907c01f72fb7…` | — | PRE_DEPLOY | 可重入 | UNKNOWN |
| 42 | 240 | 10-22 包 | 新增 | `migration_merchant_wechat_qrcode_20261022.sql` | `c5f52a9512e6…` | — | PRE_DEPLOY_REQUIRED | 可重入 | UNKNOWN |
| 43 | 262 | #660 | 新增 | `migration_npc_voice_enrollment_intent_20261024.sql` | `b4573ae45593…` | `20261011_npc_voice_version` | PRE_DEPLOY_REQUIRED | 单次执行 | UNKNOWN |
| 44 | 263 | #660 | 新增 | `migration_npc_voice_confirmed_attempt_20261024.sql` | `a25f38f256e3…` | `npc_voice_enrollment_intent_20261024` | PRE_DEPLOY_REQUIRED | 单次执行 | UNKNOWN |
| 45 | 264 | 10-22 包 | 新增 | `migration_o14_city_code_backfill_log_20261022.sql` | `cc5f6e4dd6e8…` | — | PRE_DEPLOY | 可重入 | UNKNOWN |
| 46 | 265 | 10-22 包 | 新增 | `migration_o14_cms_activity_city_code_20261022.sql` | `6f8aa3785069…` | — | PRE_DEPLOY | 可重入 | UNKNOWN |
| 47 | 266 | 10-22 包 | 新增 | `migration_o14_cms_topic_city_code_20261022.sql` | `fcda5dcf5bf4…` | — | PRE_DEPLOY | 可重入 | UNKNOWN |
| 48 | 267 | 10-22 包 | 新增 | `migration_o14_content_city_code_backfill_20261022.sql` | `abfb4c27b321…` | `o14_city_code_backfill_log_20261022`, `o14_cms_activity_city_code_20261022`, `o14_cms_topic_city_code_20261022` | OWNER_GATED_DATA | 可重入 | UNKNOWN |
| 49 | 268 | 10-22 包 | 新增 | `migration_o14_content_search_document_20261022.sql` | `1d153c6fbbe8…` | — | PRE_DEPLOY | 可重入 | UNKNOWN |
| 50 | 272 | #660 | 新增 | `migration_20261023_d_official_broadcast_delivery.sql` | `c822ad772727…` | `official_broadcast` | PRE_DEPLOY_REQUIRED | 单次执行 | UNKNOWN |
| 51 | 273 | 10-23 后新增 | 新增 | `migration_20261026_official_broadcast_delivery.sql` | `ca5538fae79b…` | `official_broadcast` | PRE_DEPLOY_REQUIRED | **永不执行** | UNKNOWN |
| 52 | 274 | #660 | 新增 | `migration_20261028_official_broadcast_protocol_union.sql` | `44dc1ee52ab1…` | `20261023_d_official_broadcast_delivery`, `20261026_official_broadcast_delivery` | PRE_DEPLOY_REQUIRED | 单次执行 | UNKNOWN |
| 53 | 287 | 10-22 包 | 新增 | `migration_orient_geo_ar_team_20261022.sql` | `d297c399d1c1…` | — | PRE_DEPLOY | 可重入 | UNKNOWN |
| 54 | 288 | 10-23 后新增 | 新增 | `migration_orient_score_recovery_20261026.sql` | `7ad0d698bd3b…` | — | PRE_DEPLOY_REQUIRED | 可重入 | UNKNOWN |
| 55 | 289 | 10-23 后新增 | 新增 | `migration_orient_score_sync_receipt_20261026.sql` | `615f1a9e64f4…` | — | PRE_DEPLOY_REQUIRED | 可重入 | UNKNOWN |
| 56 | 320 | 10-22 包 | 新增 | `migration_o14_reco_search_jobs_20261022.sql` | `046921a70dbb…` | `o14_content_search_document_20261022`, `p3_platform_foundation` | PRE_DEPLOY | 可重入 | UNKNOWN |
| 57 | 334 | 10-22 包 | 新增 | `migration_play_arrive_guard_log_20261022.sql` | `52b937c5468f…` | — | PRE_DEPLOY | 可重入 | UNKNOWN |
| 58 | 343 | 10-22 包 | 新增 | `migration_pms_sku_booking_20261022.sql` | `0057c4368f7a…` | — | PRE_DEPLOY | 可重入 | UNKNOWN |
| 59 | 365 | #660 | 新增 | `migration_20261023_advanced_command_fingerprint.sql` | `d1c413a6abab…` | `20260820_new_city_preference_chain` | PRE_DEPLOY_REQUIRED | 可重入 | UNKNOWN |
| 60 | 366 | 本包新增 | 新增 | `migration_20261029_unlist_test_topics.sql` | `f4d7cb0f0c55…` | `20260823_topic_route_graph`, `20260917_player_run_session` | OWNER_GATED_DATA | 可重入 | UNKNOWN |
| 61 | 367 | 10-22 包 | 新增 | `migration_orient_scoring_20261022.sql` | `17893c96611a…` | `20260823_topic_route_graph` | PRE_DEPLOY | 可重入 | UNKNOWN |
| 62 | 368 | 10-22 包 | 新增 | `migration_orient_session_lifecycle_20261022.sql` | `ffc9001685f8…` | `20260823_topic_route_graph` | PRE_DEPLOY_REQUIRED | 可重入 | UNKNOWN |
| 63 | 369 | #660 | 新增 | `migration_photo_check_intent_20261023.sql` | `8671f805d520…` | `20261023_advanced_command_fingerprint` | PRE_DEPLOY_REQUIRED | 单次执行 | UNKNOWN |
| 64 | 411 | 10-22 包 | 新增 | `migration_member_visited_poi_poi_key_backfill_20261022.sql` | `2980391167e7…` | `member_visited_poi_poi_key_20261022`, `roam` | OWNER_GATED_DATA | 可重入 | UNKNOWN |
| 65 | 422 | 10-22 包 | 新增 | `migration_roam_share_version_20261022.sql` | `563ddd80ae4e…` | `20260917_roam_share_snapshot` | PRE_DEPLOY_REQUIRED | 可重入 | UNKNOWN |
| 66 | 427 | 10-23 后新增 | 新增 | `migration_20261024_official_roam_challenges.sql` | `dbe4bae0ce20…` | `20261012_roam_claim_fingerprints`, `city_merchant_node`, `roam` | PRE_DEPLOY_REQUIRED | 可重入 | UNKNOWN |
| 67 | 428 | 10-23 后新增 | 新增 | `migration_20261025_official_poi_quotes.sql` | `7a0f93fc0dfd…` | `20261024_official_roam_challenges` | PRE_DEPLOY_REQUIRED | 可重入 | UNKNOWN |
| 68 | 437 | #660 | 新增 | `migration_20261023_g_cash_evidence_expand.sql` | `5f37c67e8578…` | `coop_settlement`, `coop_settlement_line`, `fee_snapshot_20260716`, `p3_phase_c_web_rules_refund`, `settlement_claim_20260811` | PRE_DEPLOY_REQUIRED | 单次执行 | UNKNOWN |
| 69 | 463 | 10-22 包 | 新增 | `migration_20261022_theme_state_key_attribute_def.sql` | `8b69583c2479…` | `theme_state_key_20260916` | PRE_DEPLOY_REQUIRED | 可重入 | UNKNOWN |
| 70 | 464 | 10-22 包 | 新增 | `migration_20261022_theme_character.sql` | `a0a8637bdf95…` | `20261022_theme_state_key_attribute_def`, `theme_state_key_20260916` | PRE_DEPLOY_REQUIRED | 可重入 | UNKNOWN |
| 71 | 477 | 10-23 后新增 | 新增 | `migration_20261028_d20_attribute_registry.sql` | `9cba286e9a99…` | `20261022_theme_state_key_attribute_def`, `theme_state_key_20260916`, `topic_journey_rules_20260916` | PRE_DEPLOY_REQUIRED | 可重入 | UNKNOWN |
| 72 | 489 | 10-23 后新增 | 新增 | `migration_20261028_remove_wildlife_theme.sql` | `96a0967b9529…` | `publish_solo_story_topics_20260827`, `seed_official_solo_story_topics_20260823` | OWNER_GATED_DATA | 可重入 | UNKNOWN |
| 73 | 501 | 10-23 后新增 | 新增 | `migration_20261028_template_shelf_list_free_explore.sql` | `6779e927a590…` | `seed_shanghai_solo_topics_20260822`, `seed_topic_template_shelf_20260726` | OWNER_GATED_DATA | 可重入 | UNKNOWN |
| 74 | 520 | 10-22 包 | 新增 | `migration_verification_review_evidence_20261022.sql` | `9b13f58da445…` | — | PRE_DEPLOY | 可重入 | UNKNOWN |
| 75 | 523 | #660 | 新增 | `migration_20261025_coop_order_station_fulfillment.sql` | `73f6586a2f6c…` | `20261023_g_cash_evidence_expand`, `business_audit_event_20260823`, `w2_terms_axes_20260811` | PRE_DEPLOY_REQUIRED | 单次执行 | UNKNOWN |
| 76 | 524 | 10-23 后新增 | 新增 | `migration_w8_refund_fulfillment_recovery_20261026.sql` | `28b65fad53c1…` | `p3_phase_c_web_rules_refund` | PRE_DEPLOY | 可重入 | UNKNOWN |
| 77 | 529 | 10-23 后新增 | 新增 | `migration_20261026_user_invoice_menu.sql` | `078d59279328…` | `20261012_missing_business_menu`, `20261026_user_invoice_processing`, `20261026_user_invoice_title_receipt` | PRE_DEPLOY | 可重入 | UNKNOWN |
| 78 | 544 | 10-22 包 | 修改 | `migration_20261013_admin_role_page_grants.sql` | `1f147a2136e9…` | `20261012_media_review_menu`, `20261012_missing_business_menu`, `role2_analytics_perm_20260918`, `web_admin_roles_20260719` | PRE_DEPLOY | 可重入 | UNKNOWN |
| 79 | 545 | 10-22 包 | 新增 | `migration_20261016_admin_role_payee_separation_forward.sql` | `4eeef3936f72…` | `20260816_tandianri_batch3_funds_club_compensation`, `20260816_tandianri_batch3_funds_club_payee`, `20260817_tandianri_batch3_funds_club_hours_correction`, `20261013_admin_role_page_grants` | PRE_DEPLOY | 可重入 | UNKNOWN |
| 80 | 546 | 10-23 后新增 | 新增 | `migration_20261027_orient_scoring_menu_grants.sql` | `6af4376c7606…` | `20261012_missing_business_menu`, `topic_source_menu_20260722`, `web_admin_roles_20260719` | PRE_DEPLOY | 可重入 | UNKNOWN |
| 81 | 553 | #660 | 新增 | `migration_20261023_g_wp08_delivery_jobs_paused.sql` | `9d5b05113ddb…` | `20261012_im_media_visibility`, `20261013_b_im_notification_intent`, `20261013_roam_badge_delivery`, `20261013_roam_reward_delivery`, `member_exp_earned_20261011` | OWNER_GATED_DATA | 可重入 | UNKNOWN |
| 82 | 555 | #660 | 新增 | `migration_club_post_media_review_budget_20261024.sql` | `5d4f02c02dcd…` | `club_role_media_review_outbox_20260824` | PRE_DEPLOY_REQUIRED | 可重入 | UNKNOWN |
| 83 | 559 | #660 | 新增 | `migration_20261024_wx_verified_payment_fact.sql` | `1bc8ed6c9fdf…` | `wx_payment_callback_inbox_outbox_20260725` | PRE_DEPLOY_REQUIRED | 单次执行 | UNKNOWN |
| 84 | 560 | #660 | 新增 | `migration_20261024_clm_commerce_payment_proof.sql` | `039c78683499…` | `20261024_wx_verified_payment_fact`, `club_role_membership_20260823`, `commerce_order_fields_and_verification_review_20260805` | PRE_DEPLOY_REQUIRED | 单次执行 | UNKNOWN |
| 85 | 561 | #660 | 新增 | `migration_20261024_coop_original_refund_components.sql` | `ccde7e232295…` | `20261023_g_cash_evidence_expand`, `20261024_wx_verified_payment_fact` | PRE_DEPLOY_REQUIRED | 单次执行 | UNKNOWN |
| 86 | 562 | #660 | 新增 | `migration_20261024_coop_negative_margin_manual.sql` | `8c1d57fdce37…` | `20261024_coop_original_refund_components` | PRE_DEPLOY_REQUIRED | 单次执行 | UNKNOWN |
| 87 | 563 | #660 | 新增 | `migration_20261024_registration_payment_fact.sql` | `fdc733febedd…` | `20261024_wx_verified_payment_fact` | PRE_DEPLOY_REQUIRED | 单次执行 | UNKNOWN |
| 88 | 564 | #660 | 新增 | `migration_20261024_wx_success_fact_business_index.sql` | `0937ea8846f8…` | `20261024_wx_verified_payment_fact` | PRE_DEPLOY_REQUIRED | 单次执行 | UNKNOWN |

每项的变更说明、幂等守卫、锁风险和 SELECT-only 回读见 [release-migration-pack.md](release-migration-pack.md) 第 1 节。

## 2 #660 带来的 27 项

全部是 #660（整批架构治理源码合并）合入的候选迁移，`src/main` 均有读写方，所以除 `20261023_g_wp08_delivery_jobs_paused`（只登记暂停任务，OWNER_GATED_DATA）外都是 PRE_DEPLOY_REQUIRED：

- 资金/支付：`20261023_cash_withdraw_fund_fact`、`20261025_cash_withdraw_fund_request_compat`、`20261024_wx_verified_payment_fact`、`20261024_wx_success_fact_business_index`、`20261024_clm_commerce_payment_proof`、`20261024_registration_payment_fact`。
- 报名席位：`20261024_registration_verified_seats`、`20261024_registration_seat_activity_scope`。
- 合作结算：`20261023_g_cash_evidence_expand`、`20261024_coop_original_refund_components`、`20261024_coop_negative_margin_manual`、`20261025_coop_order_station_fulfillment`。
- 通知/广播：`20261024_d_committed_official_notice_wx`、`20261024_c_before_start_notice_typed`（永不执行）、`20261024_g_before_start_notice_metadata_v2`、`20261023_d_official_broadcast_delivery`、`20261028_official_broadcast_protocol_union`、`20261023_g_wp08_delivery_jobs_paused`。
- NPC / AI / 玩法：`20261024_d_npc_asr_intent`、`20261024_d_npc_asr_shared_quota`、`npc_voice_enrollment_intent_20261024`、`npc_voice_confirmed_attempt_20261024`、`ai_theme_draft_request_intent_20261024`、`20261023_advanced_command_fingerprint`、`photo_check_intent_20261023`、`20261024_d_chapter_application_round`。
- 俱乐部：`club_post_media_review_budget_20261024`。

#659、#662 没有带迁移。本包新增：`20261029_unlist_test_topics`（OWNER_GATED_DATA）。

## 3 静态结论与人工核对点

- 没有既有迁移被删除或修改（`20261013` 的修改是 10-22 包已记录的那一次）；本包只新增 `migration_20261029_unlist_test_topics.sql`，`ci/migration_gates.py sql` 通过。
- 不可直接重跑（单次执行，25 项）：`20261023_wx_pay_order_intent`、`20261024_d_committed_official_notice_wx`、`20261024_d_npc_asr_intent`、`20261024_d_npc_asr_shared_quota`、`20261024_g_before_start_notice_metadata_v2`、`20261024_registration_verified_seats`、`ai_theme_draft_request_intent_20261024`、`20261024_d_chapter_application_round`、`club_welcome_note_20261022`、`20261023_cash_withdraw_fund_fact`、`20261024_cash_withdraw_transfer_intent`、`20261025_cash_withdraw_fund_request_compat`、`npc_voice_enrollment_intent_20261024`、`npc_voice_confirmed_attempt_20261024`、`20261023_d_official_broadcast_delivery`、`20261028_official_broadcast_protocol_union`、`photo_check_intent_20261023`、`20261023_g_cash_evidence_expand`、`20261025_coop_order_station_fulfillment`、`20261024_wx_verified_payment_fact`、`20261024_clm_commerce_payment_proof`、`20261024_coop_original_refund_components`、`20261024_coop_negative_margin_manual`、`20261024_registration_payment_fact`、`20261024_wx_success_fact_business_index`。只在预检 NEEDED 时执行一次，APPLIED 跳过。
- 永不执行（REPLACED_UNAPPLIED）：`20261024_c_before_start_notice_typed`（由 v2 取代）、`20261026_official_broadcast_delivery`（由 D + union 取代）。生产广播的处置见 release-migration-pack.md 第 4.1 节。
- 文件头写 PROPOSAL / candidate / source only / not approved 的：`20261023_wx_pay_order_intent` 与 #660 的几乎全部迁移。本 SHA 的主代码已读写这些对象，执行前 owner 逐个确认。
- `20261023_g_cash_evidence_expand` 前置断言会拒绝存量平台受款结算行 / 空受款人 / 悬空 claim（预检 BLOCKED_PREREQ）。
- `20261013_admin_role_page_grants` 的 checksum 对账与“NEEDED 时禁止执行”沿用 10-22 台账第 1 节；`20261016` 有 2 条带 WHERE 的定点 DELETE，会改变生产授权。
- 数据类 8 项（OWNER_GATED_DATA）先跑预检附录 B / C 的只读预览，owner 抽检后再执行；测试主题下架最后执行。
- 静态分析不能代替只读预检，只读预检也不能代替 owner 确认。
