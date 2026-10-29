# 发布迁移包（release-closeout-20261028）

本包为 2026-10-28「先部署新后端、再上传小程序体验版」准备发布顺序、只读预检与隔离演练。**全部生产执行状态保持 UNKNOWN**；参考基线不等于生产已应用状态。本任务不连接生产、不执行生产 SQL、不部署。

机器契约为 [release-migration-pack.json](release-migration-pack.json)，预检为 [release-migration-precheck.sql](release-migration-precheck.sql)，owner 操作清单为 [release-steps.md](release-steps.md)。演练使用现有 `ci/release_migration_rehearsal.py`、`ci/test_release_migration_rehearsal.py` 及 `.github/workflows/release-migration-rehearsal.yml`（现指向本包；10-22 包原样保留为历史记录）。

## 发布集口径

- 生产参考基线仍是 10-22 包的 `19408d49`（Merge #452）。10-22 包（源 `11c8055e`）列出的 35 项**从未在生产确认执行**，所以本包把它们与 `11c8055e` 之后合入 master 的 25 项合成一个发布集：`release_manifest.migration_diff(19408d49, 源提交)`，共 60 项（新增 59、修改 1、删除 0）。
- 10-22 那 35 项的阶段、守卫、风险、回读与演练状态契约原样沿用（见 10-22 包），执行序按源提交的全量拓扑序重算。
- 新增 25 项对应 PR #655 正文列出的 17 个（#625 W8 收口 15 + 文档二 L8 2）、#641 的 `20261027_merchant_brand_images_to_gallery`，以及 10-23 之后其他已合入 PR 带来的 7 个（`20261023_wx_pay_order_intent`、`20261024_cash_withdraw_transfer_intent`、`20261024_community_open_flags`、`20261024_official_roam_challenges`、`20261025_official_poi_quotes`、`20261028_remove_wildlife_theme`、`20261028_template_shelf_list_free_explore`）。#661 / #640 没有带迁移。

<!-- BEGIN GENERATED RELEASE INVENTORY -->
## 0 来源与覆盖范围

| 项 | 值 |
|---|---|
| 仓库参考基线 | `19408d49172c1d8fd16ec012f4498ff8ea584a37`；不声明生产已执行 |
| 源提交 | `b739b00c082047c57167c5541682f443b4b2dda5` |
| 源 tree | `ab5a90b50161a90aa39299e986d7906b4b2791a7` |
| SQL 子树 | `61eff9b0b9419cf4b1b9cb09ec3d771fc9cc1525` |
| 依赖台账 SHA-256 | `0118e7fda3512918d2b0faf3f40f06d6468fbdbeeec984041ff354bc99558d91` |
| 发布集 | 60 项；新增 59、修改 1、删除 0 |
| 生产执行状态 | **UNKNOWN**（每一项均未知） |

## 1 有序迁移清单

order 是源提交全量迁移的拓扑位置；只按 step 从小到大逐项执行。

| step | order | 文件 | 变更 | SHA-256 | dependsOn | phase |
|---|---|---|---|---|---|---|
| 1 | 45 | `chengyinhub-admin/sql/migration_20261018_infomation_change_audit.sql` | ADDED | `0e023798a882a7aec4e5a9de9815d7dcf39175600fc9d3c6d6c640a4dc304695` | — | PRE_DEPLOY |
| 2 | 46 | `chengyinhub-admin/sql/migration_20261022_audit_activity_switch_seed.sql` | ADDED | `4a9d4e63cde4afc7b5d6f4a17f46d0489b7508bafead3f3e8363448c3d4f59c7` | — | PRE_DEPLOY |
| 3 | 47 | `chengyinhub-admin/sql/migration_20261022_f7_minor_guardian.sql` | ADDED | `c375ee6846c1ed0702b224ab12ad5301e6f6056b236ee30ca2ca052fb3b66c1e` | — | PRE_DEPLOY |
| 4 | 48 | `chengyinhub-admin/sql/migration_20261022_f7_user_invoice.sql` | ADDED | `1ee515288464e4998ee4ea1341add368dd3e0db611908adccf7d950a7adb8c98` | — | PRE_DEPLOY |
| 5 | 49 | `chengyinhub-admin/sql/migration_20261022_location_guard_switch_seed.sql` | ADDED | `8eac6d4ec7c88e223611e9c2a9c41fc7fa814941361c36e4e8f856ab4b6bd377` | — | PRE_DEPLOY |
| 6 | 50 | `chengyinhub-admin/sql/migration_20261022_merchant_desk_switch_seed.sql` | ADDED | `99ccf252582db7d085d9061c18c91afec30783ee829e585038fc479b95da5b8f` | — | PRE_DEPLOY |
| 7 | 51 | `chengyinhub-admin/sql/migration_20261023_creative_square_like_unique.sql` | ADDED | `8fe2deeef171d7c44e09f5dacc661c9c834ff6f2ca436d2f810e87235b212780` | — | PRE_DEPLOY_REQUIRED |
| 8 | 52 | `chengyinhub-admin/sql/migration_20261023_points_value_config_seed.sql` | ADDED | `c3b7d857e6c0d72107964eb6d049fe9ebe639410ab2a5abe567b4e4054a54923` | — | PRE_DEPLOY |
| 9 | 53 | `chengyinhub-admin/sql/migration_20261023_wx_pay_order_intent.sql` | ADDED | `da55f93a46f7e71a48633e42f05adafb9db1c06d5c3e7e06594d7feeb407c292` | — | PRE_DEPLOY_REQUIRED |
| 10 | 54 | `chengyinhub-admin/sql/migration_20261024_cash_withdraw_transfer_intent.sql` | ADDED | `f2488f404fa9c1940377b47cbe531351bd2e31e3f8b1fc50aa7b2813ba00e55c` | — | PRE_DEPLOY_REQUIRED |
| 11 | 55 | `chengyinhub-admin/sql/migration_20261024_community_open_flags.sql` | ADDED | `b936a66c590a25b1ea57e827aa036b874e06ae620763dd0f53586fda1e3ae4b0` | — | PRE_DEPLOY |
| 12 | 56 | `chengyinhub-admin/sql/migration_20261025_coop_invite_request_receipt.sql` | ADDED | `0d3bbaf69303ad78a8a094b777be40f4eae89c442400559a6d093d10eefeea53` | — | PRE_DEPLOY_REQUIRED |
| 13 | 57 | `chengyinhub-admin/sql/migration_20261026_official_event_attendance.sql` | ADDED | `b49da83d06dd84b699f11c1e21ab4188bd4c8bf32d7e14e83b06fad6f57373d9` | — | PRE_DEPLOY_REQUIRED |
| 14 | 58 | `chengyinhub-admin/sql/migration_20261026_team_event_command_receipt.sql` | ADDED | `06888f11813bb1024de1ae0041fbbfacd57aff1cbf0b99433fadd537ee18c1ce` | — | PRE_DEPLOY_REQUIRED |
| 15 | 59 | `chengyinhub-admin/sql/migration_20261026_theme_attribute_registry_version.sql` | ADDED | `6864624782c112b9c804112fe559002e97a83fb85b78ea2364772ac0573ff54f` | — | PRE_DEPLOY_REQUIRED |
| 16 | 60 | `chengyinhub-admin/sql/migration_20261026_user_invoice_processing.sql` | ADDED | `35067817cedcf73e0e513fee1024fd32a018cf18802d6ca0fd4f612932487b1a` | 20261022_f7_user_invoice | PRE_DEPLOY_REQUIRED |
| 17 | 61 | `chengyinhub-admin/sql/migration_20261026_user_invoice_title_receipt.sql` | ADDED | `db0dfe988c1d1963f7d4313b9032c9ece9f63cbddd55ef4e0493aaa683cb4e68` | 20261022_f7_user_invoice | PRE_DEPLOY_REQUIRED |
| 18 | 62 | `chengyinhub-admin/sql/migration_20261027_template_adoption_receipt.sql` | ADDED | `9c72d1698d6de5920ad00d87fed6f0f37789c78793b2119d113cc394f88663da` | — | PRE_DEPLOY_REQUIRED |
| 19 | 63 | `chengyinhub-admin/sql/migration_20261027_user_invoice_processing_backfill.sql` | ADDED | `cb3554540f429268e0f8476844754f98c7250c6cc54a40a2102d25a59f6e0e43` | 20261022_f7_user_invoice, 20261026_user_invoice_processing | OWNER_GATED_DATA |
| 20 | 64 | `chengyinhub-admin/sql/migration_20261028_topic_play_setup.sql` | ADDED | `15aeb5092729262b25d330948ebdf28e22e9e0af59d8084c48e6531350d7e482` | — | PRE_DEPLOY_REQUIRED |
| 21 | 80 | `chengyinhub-admin/sql/migration_audit_problem_20261022.sql` | ADDED | `02353f0296b49751e5a035a129a03fccf9173cddba2194e7489b32449434a080` | — | PRE_DEPLOY |
| 22 | 119 | `chengyinhub-admin/sql/migration_club_welcome_note_20261022.sql` | ADDED | `668c8955b2c965eeb84436ad70a72e264a9c3aaa7d8d77450a282cc3b680f1ee` | club_open_settings_20260909 | PRE_DEPLOY |
| 23 | 152 | `chengyinhub-admin/sql/migration_doc2_f3_bundle_ledger_20261022.sql` | ADDED | `f94e363d84923f4b6e97bce4ec49382364ec2568e7efe6cdcc83c10ed4b8f419` | — | PRE_DEPLOY |
| 24 | 153 | `chengyinhub-admin/sql/migration_doc2_f3_refund_hold_20261026.sql` | ADDED | `74b1ef1e1488b1ee69eb8f104dde6466246d690db7fc01de19ab8c84d37b2556` | — | PRE_DEPLOY |
| 25 | 198 | `chengyinhub-admin/sql/migration_member_profile_version_20261022.sql` | ADDED | `8be4a0d82578fe1d74579f5abbf859307f0b827655d670ff0e398cf026d018ba` | — | PRE_DEPLOY_REQUIRED |
| 26 | 200 | `chengyinhub-admin/sql/migration_member_visited_poi_poi_key_20261022.sql` | ADDED | `ddb6c4652f8138a42a2ec380af3981f6674e574834ade341736e12eacb31c07a` | member_visited_poi | PRE_DEPLOY |
| 27 | 206 | `chengyinhub-admin/sql/migration_merchant_category_ids_20261022.sql` | ADDED | `e26703b2a6cb15eea3996c2d188af546002c88f46135accd5956ce1fdd37cbd1` | — | PRE_DEPLOY_REQUIRED |
| 28 | 212 | `chengyinhub-admin/sql/migration_20261027_merchant_brand_images_to_gallery.sql` | ADDED | `e7f68c884d431fc095102a6327d3a76dc023d9940e30511052c0826322563b58` | merchant_decor_20260708 | OWNER_GATED_DATA |
| 29 | 214 | `chengyinhub-admin/sql/migration_merchant_desk_receipt_20261022.sql` | ADDED | `83746d2cb7e5de51365f97782d9e8692fb58c6bdf3196e854475d102d2fc2278` | — | PRE_DEPLOY |
| 30 | 225 | `chengyinhub-admin/sql/migration_merchant_redemption_anchor_20261022.sql` | ADDED | `907c01f72fb7f77ceed83a0aa8eb51de4cbd55dc740218a0e1a65ca33c2da6e0` | — | PRE_DEPLOY |
| 31 | 229 | `chengyinhub-admin/sql/migration_merchant_wechat_qrcode_20261022.sql` | ADDED | `c5f52a9512e615c08522a8d0fa7b62241863664e3853461aecbd7406d680017e` | — | PRE_DEPLOY_REQUIRED |
| 32 | 251 | `chengyinhub-admin/sql/migration_o14_city_code_backfill_log_20261022.sql` | ADDED | `cc5f6e4dd6e8880f42012cbb7069a2ffaf79b8ac5ac75565e09b2c159922a1ed` | — | PRE_DEPLOY |
| 33 | 252 | `chengyinhub-admin/sql/migration_o14_cms_activity_city_code_20261022.sql` | ADDED | `6f8aa378506994897748617ae9209445cca32c2eefe613549194f6252351ee95` | — | PRE_DEPLOY |
| 34 | 253 | `chengyinhub-admin/sql/migration_o14_cms_topic_city_code_20261022.sql` | ADDED | `fcda5dcf5bf4172238564758d008b4b64d6eab6a8861335dbb5d063e2dd93ff1` | — | PRE_DEPLOY |
| 35 | 254 | `chengyinhub-admin/sql/migration_o14_content_city_code_backfill_20261022.sql` | ADDED | `abfb4c27b3219ab3906c29feb9c3a9c920f498913f26f06bb177740a271c085a` | o14_city_code_backfill_log_20261022, o14_cms_activity_city_code_20261022, o14_cms_topic_city_code_20261022 | OWNER_GATED_DATA |
| 36 | 255 | `chengyinhub-admin/sql/migration_o14_content_search_document_20261022.sql` | ADDED | `1d153c6fbbe8e7595cb169e906c64df975d1f85b88c486d1a0db6e4e8ebe3d5f` | — | PRE_DEPLOY |
| 37 | 259 | `chengyinhub-admin/sql/migration_20261026_official_broadcast_delivery.sql` | ADDED | `ca5538fae79b01ac4690065da6cde4ac3b7922dce5c0d09a04a5ff58bca1076f` | official_broadcast | PRE_DEPLOY_REQUIRED |
| 38 | 272 | `chengyinhub-admin/sql/migration_orient_geo_ar_team_20261022.sql` | ADDED | `d297c399d1c12d568d551ebe6dc17d6cc87bc95a6bae6d5667ef1e991d01f1e3` | — | PRE_DEPLOY |
| 39 | 273 | `chengyinhub-admin/sql/migration_orient_score_recovery_20261026.sql` | ADDED | `7ad0d698bd3b5f0ebb5d36b1c30a2099868ca2b7598570cb82c8848fb93134a8` | — | PRE_DEPLOY_REQUIRED |
| 40 | 274 | `chengyinhub-admin/sql/migration_orient_score_sync_receipt_20261026.sql` | ADDED | `615f1a9e64f42ff8d8ee398e32aac14168ff00f67edd4ddf34b6c12f90fd9f33` | — | PRE_DEPLOY_REQUIRED |
| 41 | 305 | `chengyinhub-admin/sql/migration_o14_reco_search_jobs_20261022.sql` | ADDED | `046921a70dbbf4f8ea32aa582d651e0a0d9c798cfd9c79a2df3b027972ccc370` | o14_content_search_document_20261022, p3_platform_foundation | PRE_DEPLOY |
| 42 | 319 | `chengyinhub-admin/sql/migration_play_arrive_guard_log_20261022.sql` | ADDED | `52b937c5468f03006aef2e6b15c1beeb9a625c539558ca805c57fc4ff453b1fb` | — | PRE_DEPLOY |
| 43 | 328 | `chengyinhub-admin/sql/migration_pms_sku_booking_20261022.sql` | ADDED | `0057c4368f7ade02120c109c791950e75b7f4c5d0dd6b42b8baf1fcb8f1fd8ac` | — | PRE_DEPLOY |
| 44 | 350 | `chengyinhub-admin/sql/migration_orient_scoring_20261022.sql` | ADDED | `17893c96611ad13875178c9b6ebf4fcb332e7a11fad5f6fb8a7d1f25fd9e055a` | 20260823_topic_route_graph | PRE_DEPLOY |
| 45 | 351 | `chengyinhub-admin/sql/migration_orient_session_lifecycle_20261022.sql` | ADDED | `ffc9001685f87179b58fa7bb9c104bc27c608664b3288e79234053699ffd2735` | 20260823_topic_route_graph | PRE_DEPLOY_REQUIRED |
| 46 | 393 | `chengyinhub-admin/sql/migration_member_visited_poi_poi_key_backfill_20261022.sql` | ADDED | `2980391167e7bb4113a9523158e7a7fb5577eb8875d534f27db7f612e240af27` | member_visited_poi_poi_key_20261022, roam | OWNER_GATED_DATA |
| 47 | 404 | `chengyinhub-admin/sql/migration_roam_share_version_20261022.sql` | ADDED | `563ddd80ae4e23ce96d02183380d9175209e68702aaf06f1061fd9a6688170b6` | 20260917_roam_share_snapshot | PRE_DEPLOY_REQUIRED |
| 48 | 409 | `chengyinhub-admin/sql/migration_20261024_official_roam_challenges.sql` | ADDED | `dbe4bae0ce203ca9959ef649a5eeb4e807395249a1a408d6513ef54fac27692c` | 20261012_roam_claim_fingerprints, city_merchant_node, roam | PRE_DEPLOY_REQUIRED |
| 49 | 410 | `chengyinhub-admin/sql/migration_20261025_official_poi_quotes.sql` | ADDED | `7a0f93fc0dfd6b552a13951e9ccfdb4b7ac72c7fad90d55e332855fe1d8ad77d` | 20261024_official_roam_challenges | PRE_DEPLOY_REQUIRED |
| 50 | 444 | `chengyinhub-admin/sql/migration_20261022_theme_state_key_attribute_def.sql` | ADDED | `8b69583c24795742c0244610bb8dab6ef9abec64577a0a13666c5929146e0b25` | theme_state_key_20260916 | PRE_DEPLOY_REQUIRED |
| 51 | 445 | `chengyinhub-admin/sql/migration_20261022_theme_character.sql` | ADDED | `a0a8637bdf95674f4c11dabeb3852ab53a923789507a59e0b5cd64587f1adc71` | 20261022_theme_state_key_attribute_def, theme_state_key_20260916 | PRE_DEPLOY_REQUIRED |
| 52 | 458 | `chengyinhub-admin/sql/migration_20261028_d20_attribute_registry.sql` | ADDED | `9cba286e9a9963314ee572364b8b944f3c7dd865c00e4266735c37a2a16ecdf0` | 20261022_theme_state_key_attribute_def, theme_state_key_20260916, topic_journey_rules_20260916 | PRE_DEPLOY_REQUIRED |
| 53 | 470 | `chengyinhub-admin/sql/migration_20261028_remove_wildlife_theme.sql` | ADDED | `96a0967b9529c4bd62158c2fd5ca660458a7feb60a1e1937bf325295c02bcf62` | publish_solo_story_topics_20260827, seed_official_solo_story_topics_20260823 | OWNER_GATED_DATA |
| 54 | 482 | `chengyinhub-admin/sql/migration_20261028_template_shelf_list_free_explore.sql` | ADDED | `6779e927a59031f4bfeff8fee4e4f63de746dd4056f18e655ae979c1b46bd181` | seed_shanghai_solo_topics_20260822, seed_topic_template_shelf_20260726 | OWNER_GATED_DATA |
| 55 | 501 | `chengyinhub-admin/sql/migration_verification_review_evidence_20261022.sql` | ADDED | `9b13f58da4455c4af67429bbe604086cf28cd451f19ad71f0bf09b489d42d889` | — | PRE_DEPLOY |
| 56 | 504 | `chengyinhub-admin/sql/migration_w8_refund_fulfillment_recovery_20261026.sql` | ADDED | `28b65fad53c1aa768b7d7d56886e4b4a1998cecb8faad59bcc8d9c996bf432f1` | p3_phase_c_web_rules_refund | PRE_DEPLOY |
| 57 | 509 | `chengyinhub-admin/sql/migration_20261026_user_invoice_menu.sql` | ADDED | `078d59279328a462bf73a948382904129ef2abce8acb9864b3731a9bd9614c61` | 20261012_missing_business_menu, 20261026_user_invoice_processing, 20261026_user_invoice_title_receipt | PRE_DEPLOY |
| 58 | 524 | `chengyinhub-admin/sql/migration_20261013_admin_role_page_grants.sql` | MODIFIED | `1f147a2136e999b6fd87a7e0a865d8fde5b0c332170d561b28e415b9fccaf56e` | 20261012_media_review_menu, 20261012_missing_business_menu, role2_analytics_perm_20260918, web_admin_roles_20260719 | PRE_DEPLOY |
| 59 | 525 | `chengyinhub-admin/sql/migration_20261016_admin_role_payee_separation_forward.sql` | ADDED | `4eeef3936f72f05e69b3440aa818c2c39d53e046aaabd82cfdef10c868cca34e` | 20260816_tandianri_batch3_funds_club_compensation, 20260816_tandianri_batch3_funds_club_payee, 20260817_tandianri_batch3_funds_club_hours_correction, 20261013_admin_role_page_grants | PRE_DEPLOY |
| 60 | 526 | `chengyinhub-admin/sql/migration_20261027_orient_scoring_menu_grants.sql` | ADDED | `6af4376c7606e5bce733fe97f97f56213930d02cc0d4c2a1e9a39313505606cc` | 20261012_missing_business_menu, topic_source_menu_20260722, web_admin_roles_20260719 | PRE_DEPLOY |

### 1.1 变更、守卫与风险

| step | migrationId | 变更 | 幂等/拒绝约定 | 锁与体量风险 |
|---|---|---|---|---|
| 1 | `20261018_infomation_change_audit` | 新表 cms_infomation_change_audit（玩法说明追加审计） | CREATE TABLE IF NOT EXISTS + information_schema 全形状断言（列/索引/约束/无触发器） | 无：只建新表，空表，瞬时完成 |
| 2 | `20261022_audit_activity_switch_seed` | sys_config 落种 audit.activity.pre_review_all='false'、audit.activity.high_risk_categories=''（= 代码现行默认） | INSERT … WHERE NOT EXISTS(config_key) + 回读断言每键恰好 1 行 | 无：sys_config 小表，按自然键 INSERT … WHERE NOT EXISTS |
| 3 | `20261022_f7_minor_guardian` | 新表 minor_guardian_authorization（F7 未成年监护授权；feature.flag.minorMode 缺省关） | CREATE TABLE IF NOT EXISTS + information_schema 列串与两个唯一键断言 | 无：只建新表，空表，瞬时完成 |
| 4 | `20261022_f7_user_invoice` | 新表 user_invoice_title / user_invoice_application / user_invoice_application_order（F7 发票；feature.flag.userInvoice 缺省关） | CREATE TABLE IF NOT EXISTS + information_schema 三表列串与唯一键断言 | 无：只建新表，空表，瞬时完成 |
| 5 | `20261022_location_guard_switch_seed` | sys_config 落种 play.arrive.location_guard.mode='off'、play.arrive.require_accuracy='false'、roam.visited_poi.stable_identity='false' | INSERT … WHERE NOT EXISTS(config_key) + 回读断言 | 无：sys_config 小表，按自然键 INSERT … WHERE NOT EXISTS |
| 6 | `20261022_merchant_desk_switch_seed` | sys_config 落种 merchant.desk.f4.enabled='false' | INSERT … WHERE NOT EXISTS(config_key) + 回读断言 | 无：sys_config 小表，按自然键 INSERT … WHERE NOT EXISTS |
| 7 | `20261023_creative_square_like_unique` | ums_creative_square_like 增加唯一键 uk_square_like_member_data(member_id,data_id)，每个会员/帖子只保留一个态度状态；不自动删除历史重复行 | 执行前 runner 断言无重复 member_id/data_id；按索引名判是否建键；末尾断言唯一性及完整列序。预检额外核对同名索引形状；重复行必须由 owner 导出/核对后处置 | 中/高：先全表 GROUP BY 查重复，再在线建唯一索引；耗时与表体量相关，需短暂元数据锁。发现重复或同名异形索引禁止执行 |
| 8 | `20261023_points_value_config_seed` | sys_config 缺失时落种 points.deduct.rate_yuan_per_point='0.02' 与 points.deduct.max_yuan_per_order='50.00'；PointsValuePolicy 缺失/无效时有同值缺省，不覆盖已配置值 | INSERT SELECT WHERE NOT EXISTS(config_key)；末尾 runner 断言两个键各恰好一行。只检查行数，不强制覆盖运维配置值；重复自然键预检判 CONFLICT | 低：sys_config 小表，自然键查询后按需插入；执行窗口仍须避免并发写入相同键。公开回执只包含这两个非敏感积分配置键 |
| 9 | `20261023_wx_pay_order_intent` | 新表 wx_pay_order_intent（微信支付下单意图冻结；WechatPayServiceImpl 下单前读写） | 普通 CREATE TABLE（无 IF NOT EXISTS）：直接重跑必报 1050。只在预检 NEEDED 时执行一次；APPLIED 跳过；PARTIAL/CONFLICT 停止交 owner | 无：只建新表，空表，瞬时完成 |
| 10 | `20261024_cash_withdraw_transfer_intent` | 新表 member_cash_withdraw_transfer_intent（提现扣款/转账原始身份；CashWithdrawTransferIntentMapper 读写） | 普通 CREATE TABLE（无 IF NOT EXISTS）：直接重跑必报 1050。只在预检 NEEDED 时执行一次；APPLIED 跳过；PARTIAL/CONFLICT 停止交 owner | 无：只建新表，空表，瞬时完成 |
| 11 | `20261024_community_open_flags` | sys_config 3 键：专业广场浏览/发帖开关 true、灰度账号 *（owner 2026-10-24：上线即对所有玩家开放；键缺失时网关按关闭处理） | INSERT … WHERE NOT EXISTS（自然键 config_key），不覆盖已配置值；末尾断言每键恰好一行 | 无：sys_config 3 行插入 |
| 12 | `20261025_coop_invite_request_receipt` | 新表 coop_invite_request_receipt（协作邀请原请求回执） | CREATE TABLE IF NOT EXISTS；同名异形表不会被迁移拒绝，靠预检列/索引指纹判 CONFLICT | 无：只建新表，空表，瞬时完成 |
| 13 | `20261026_official_event_attendance` | 新表 official_event_attendance_config、official_event_attendance（官方赛事签到） | CREATE TABLE IF NOT EXISTS；同名异形表不会被迁移拒绝，靠预检列/索引指纹判 CONFLICT | 无：只建新表，空表，瞬时完成 |
| 14 | `20261026_team_event_command_receipt` | 新表 team_event_command_receipt（组队/赛事命令原请求回执） | CREATE TABLE IF NOT EXISTS；同名异形表不会被迁移拒绝，靠预检列/索引指纹判 CONFLICT | 无：只建新表，空表，瞬时完成 |
| 15 | `20261026_theme_attribute_registry_version` | 新表 theme_attribute_registry_version（主题属性注册表版本；ThemeStateKeyMapper 读写） | CREATE TABLE IF NOT EXISTS；同名异形表不会被迁移拒绝，靠预检列/索引指纹判 CONFLICT | 无：只建新表，空表，瞬时完成 |
| 16 | `20261026_user_invoice_processing` | 新表 x6：user_invoice_issuer、user_invoice_issuer_owner、user_invoice_scope、user_invoice_processing、user_invoice_processing_audit、user_invoice_issued_reference（发票处理台；空配置，无授权） | CREATE TABLE IF NOT EXISTS；同名异形表不会被迁移拒绝，靠预检列/索引指纹判 CONFLICT；文件末尾 information_schema 断言（列序/唯一键/ascii_bin） | 无：只建新表，空表，瞬时完成 |
| 17 | `20261026_user_invoice_title_receipt` | 新表 user_invoice_title_receipt（发票抬头保存原请求回执） | CREATE TABLE IF NOT EXISTS；同名异形表不会被迁移拒绝，靠预检列/索引指纹判 CONFLICT；文件末尾列类型/主键/ascii_bin 断言 | 无：只建新表，空表，瞬时完成 |
| 18 | `20261027_template_adoption_receipt` | 新表 template_adoption_receipt（模板采用原请求回执） | CREATE TABLE IF NOT EXISTS；同名异形表不会被迁移拒绝，靠预检列/索引指纹判 CONFLICT | 无：只建新表，空表，瞬时完成 |
| 19 | `20261027_user_invoice_processing_backfill` | 为 W8 之前提交、全部订单行能经 user_invoice_issuer_owner 映射到唯一开票主体的发票申请补 user_invoice_processing 行（version=0、reconciliation_status=NONE）；映射缺失或跨主体的申请不回填，留人工 | INSERT … SELECT … WHERE NOT EXISTS（已有处理行的申请跳过），重跑零变更；不改申请状态/票号/订单占用 | 低：按申请数插入；user_invoice_issuer_owner 为空配置时插入 0 行（开票主体须先由 owner 配置） |
| 20 | `20261028_topic_play_setup` | 新表 cms_topic_play_setup（主题可玩时段与开局前准备；/api/topic/play-setup 读写） | CREATE TABLE IF NOT EXISTS；同名异形表不会被迁移拒绝，靠预检列/索引指纹判 CONFLICT | 无：只建新表，空表，瞬时完成 |
| 21 | `audit_problem_20261022` | 新表 audit_problem（审核逐项驳回原因）；必须先于打开 chengyin.audit.item-problems.enabled | 仅 CREATE TABLE IF NOT EXISTS，无 information_schema 断言（同名残表会被静默接受 → 由预检指纹判 CONFLICT） | 无：只建新表，空表，瞬时完成 |
| 22 | `club_welcome_note_20261022` | club 增加 welcome_note VARCHAR(600) NULL DEFAULT NULL；代码独立读取，旧行保持默认欢迎文案 | 历史 SQL 是普通 ADD COLUMN，不可直接重跑；开头 SELECT 仅供人工看结果，并不阻止 ALTER。只在预检 NEEDED 时执行一次；APPLIED 必须跳过；其他状态停止 | 低：加可空列需要短暂元数据锁；旧 SQL 未显式指定 ALGORITHM。直接重跑必须报 MySQL 1060，禁止吞错后继续 |
| 23 | `doc2_f3_bundle_ledger_20261022` | 新表 explore_bundle_order/grant/unit/reservation/waiver/exit_case（F3 套票台账；ledger-enabled 默认关） | CREATE TABLE IF NOT EXISTS + information_schema 列与唯一键 SHA-256 指纹；不符时 guard 临时表主键冲突让执行器停 | 无：只建新表，空表，瞬时完成 |
| 24 | `doc2_f3_refund_hold_20261026` | 新表 explore_bundle_refund_hold（F3 履约冻结；本 SHA 主代码尚无读写方） | CREATE TABLE IF NOT EXISTS；同名异形表不会被迁移拒绝，靠预检列/索引指纹判 CONFLICT | 无：只建新表，空表，瞬时完成 |
| 25 | `member_profile_version_20261022` | 新表 ums_member_profile_version（资料版本回执；新 jar 表缺失只 WARN） | 仅 CREATE TABLE IF NOT EXISTS，无 information_schema 断言（台账已标风险 → 由预检指纹判 CONFLICT） | 无：只建新表，空表，瞬时完成 |
| 26 | `member_visited_poi_poi_key_20261022` | member_visited_poi 加可空列 poi_key VARCHAR(32) AFTER geo_key + 唯一键 uk_member_poi_key(member_id, poi_key) | information_schema 判列/索引是否存在 → 动态 PREPARE（存在则 SELECT 1）+ 回读断言 | 中：ADD COLUMN 在 8.0.45 走 INSTANT（仅元数据锁）；ADD UNIQUE KEY 为 INPLACE 在线建索引，耗时 ∝ 表行数，全为 NULL 不会撞唯一 |
| 27 | `merchant_category_ids_20261022` | mms_merchant 增加 category_ids VARCHAR(255) NULL；将为空且 category_id>0 的行回填为原主行业，保留已有多选值 | InnoDB 表与同名列形状 guard；按 information_schema 判列后 INSTANT ADD；只回填空值，runner 断言回填无遗漏。主预检只核结构，列存在时标 DATA_PREVIEW，须执行附录 B3 的数据回读 | 中：INSTANT 加列需短暂元数据锁；UPDATE 扫描并锁住待回填商家行，低峰执行，先留只读行数预览 |
| 28 | `20261027_merchant_brand_images_to_gallery` | 把入驻品牌形象图 mms_merchant.derivatives（逗号/分号分隔）追加进门店相册 gallery（JSON 数组，上限 9 张）；derivatives 原值保留 | 只追加相册里还没有的图、相册满 9 张不再追加；含引号/反斜杠或 gallery 非 JSON 数组的行不碰；重跑零变更 | 中：单条 UPDATE … JOIN（窗口函数 + JSON_TABLE）扫 mms_merchant 全表并锁命中行；按商家数线性，低峰执行 |
| 29 | `merchant_desk_receipt_20261022` | 新表 merchant_desk_receipt（F4 工作台幂等回执；merchant.desk.f4.enabled 默认关） | CREATE TABLE IF NOT EXISTS + 列串指纹 guard + 唯一键断言 | 无：只建新表，空表，瞬时完成 |
| 30 | `merchant_redemption_anchor_20261022` | 新表 merchant_redemption_anchor（F4 核销锚点配置） | CREATE TABLE IF NOT EXISTS + 列串指纹 guard + 唯一键断言 | 无：只建新表，空表，瞬时完成 |
| 31 | `merchant_wechat_qrcode_20261022` | mms_merchant 增加 wechat_qrcode VARCHAR(512) NULL；原 wechat 中的 URL 搬入新列并清空 URL 文本，保留已有二维码及文本微信号 | InnoDB 表与同名列形状 guard；按 information_schema 判列后 INSTANT ADD；复制只命中二维码空值，清理只命中已有二维码的 URL。主预检只核结构，列存在时标 DATA_PREVIEW，须执行附录 B4 的数据回读 | 中：INSTANT 加列需短暂元数据锁；两条 UPDATE 扫描并锁定待搬迁行。超长 URL 或历史异常会让末尾断言失败，必须停下核对，禁止放松断言 |
| 32 | `o14_city_code_backfill_log_20261022` | 新表 o14_city_code_backfill_log（O-14 回填来源标记） | CREATE TABLE IF NOT EXISTS + 关键列计数断言 | 无：只建新表，空表，瞬时完成 |
| 33 | `o14_cms_activity_city_code_20261022` | cms_activity 加可空列 city_code VARCHAR(32)（ALGORITHM=INSTANT）+ 索引 idx_cms_activity_city_code（INPLACE, LOCK=NONE） | information_schema 判列/索引；同名异形列 guard 让执行器停 + 回读断言 | 中：INSTANT 加列只需短暂元数据锁；建索引在线进行，耗时 ∝ 行数 |
| 34 | `o14_cms_topic_city_code_20261022` | cms_topic 加可空列 city_code VARCHAR(32)（INSTANT）+ 索引 idx_cms_topic_city_code（INPLACE, LOCK=NONE） | 同上 | 中：同上 |
| 35 | `o14_content_city_code_backfill_20261022` | 按 club.city 白名单回填 cms_topic / cms_activity.city_code，并在 o14_city_code_backfill_log 记来源；只改 city_code IS NULL 或本回填写过且未被改动的行 | 逻辑幂等（同规则重跑 city_code 零变更）；无 information_schema 守卫；有回读断言（只允许 6 位城市码与两种来源）。重跑会删除并重插本回填自己的 log 行（write_time 刷新） | 中/高：对 cms_topic、cms_activity 全表扫描 + UPDATE JOIN，行锁覆盖被写行；放低峰执行。执行前必须跑 _precheck_o14_content_city_code_backfill_20261022.sql 预览并由 owner 抽检 |
| 36 | `o14_content_search_document_20261022` | 新表 content_search_document（O-14 统一搜索投影） | CREATE TABLE IF NOT EXISTS + 关键列计数断言 | 无：只建新表，空表，瞬时完成 |
| 37 | `20261026_official_broadcast_delivery` | official_broadcast 加 audience_frozen/client_request_id/request_owner/request_hash + 唯一键 uk_broadcast_request；official_broadcast_log 加 delivery_state/frozen_title/frozen_sub/intent_id/message_id/attempts/update_time；新表 official_broadcast_creation（OfficialBroadcastMapper / OfficialBroadcastLogMapper 直接读写新列） | 普通 ALTER TABLE ADD COLUMN（无存在性守卫）：直接重跑必报 1060；中途失败会留下 PARTIAL，不能整文件重跑。只在预检 NEEDED 时执行一次；APPLIED 跳过；PARTIAL/CONFLICT 停止交 owner 写前向修复 | 中：两张既有表 ALTER（加可空/带常量默认列走 INSTANT；加唯一键需 INPLACE 建索引并扫表）；看预检 [6] 体量 |
| 38 | `orient_geo_ar_team_20261022` | 新表 orient_node_geo / orient_task_alternative / orient_task_attempt / orient_task_completion / orient_safety_rule + sys_config orient.* 5 键（默认关） | CREATE TABLE IF NOT EXISTS + INSERT … WHERE NOT EXISTS + 回读断言（只数表与键，不核列形状 → 由预检指纹兜底） | 无：只建新表，空表，瞬时完成 |
| 39 | `orient_score_recovery_20261026` | 新表 orient_score_recovery（定向计分 UNKNOWN 人工处置；OrientScoringMapper 读写） | CREATE TABLE IF NOT EXISTS；同名异形表不会被迁移拒绝，靠预检列/索引指纹判 CONFLICT | 无：只建新表，空表，瞬时完成 |
| 40 | `orient_score_sync_receipt_20261026` | 新表 orient_score_sync_receipt（定向计分同步回执；OrientScoringMapper 读写） | CREATE TABLE IF NOT EXISTS；同名异形表不会被迁移拒绝，靠预检列/索引指纹判 CONFLICT | 无：只建新表，空表，瞬时完成 |
| 41 | `o14_reco_search_jobs_20261022` | sys_job 登记 recommendationScoreDailyTask.run() 与 contentSearchProjectionTask.refresh()，status='1'（暂停） | INSERT … WHERE NOT EXISTS(invoke_target) + 回读断言恰好 1 行；已存在的登记不改 | 无：sys_job 小表。暂停任务不会被调度，旧 jar 无对应 bean 也不会触发 |
| 42 | `play_arrive_guard_log_20261022` | 新表 play_arrive_guard_log（到达守卫留痕；mode=off 时不读写） | CREATE TABLE IF NOT EXISTS + 列串指纹 guard + 索引断言 | 无：只建新表，空表，瞬时完成 |
| 43 | `pms_sku_booking_20261022` | 新表 pms_sku_booking_rule / pms_sku_capacity_slot（F4 SKU 预约容量） | CREATE TABLE IF NOT EXISTS + 列串指纹 guard + 唯一键断言 | 无：只建新表，空表，瞬时完成 |
| 44 | `orient_scoring_20261022` | 新表 orient_score_ledger / settlement / appeal / orient_result_dispute + sys_config orient.scoring.* 5 键（默认不发奖） | CREATE TABLE IF NOT EXISTS + INSERT … WHERE NOT EXISTS + 回读断言（只数表与键 → 由预检指纹兜底） | 无：只建新表，空表，瞬时完成 |
| 45 | `orient_session_lifecycle_20261022` | topic_route_session 加可空列 phase VARCHAR(16) AFTER status；status 列只改注释（类型/默认不变）；新表 orient_session_clock | information_schema 判列/注释 → 动态 PREPARE；CREATE TABLE IF NOT EXISTS；回读断言 | 低/中：ADD COLUMN 走 INSTANT；MODIFY 只改注释时为元数据变更。若生产 status 列类型/默认与 TINYINT NOT NULL DEFAULT 0 不一致，MODIFY 会变成整表重建——预检会判 CONFLICT，先停 |
| 46 | `member_visited_poi_poi_key_backfill_20261022` | 回填 member_visited_poi.poi_key（poi:{地标id} 或 pt:{5位坐标}；判为重复的行保持 NULL）；不删行、不改 geo_key | 只处理 poi_key IS NULL 的行；重跑时剩下的都是重复行，不再写；回读断言 | 中/高：相关子查询 ∝ 每会员点数²，大表需放低峰；只写 poi_key。必须在打开 roam.visited_poi.stable_identity 之前执行 |
| 47 | `roam_share_version_20261022` | roam_share_snapshot 加列 version INT NOT NULL DEFAULT 1（旧行即为 1） | information_schema 判列 → 动态 PREPARE + 回读断言（类型/非空/默认） | 低：INT NOT NULL DEFAULT 加在末尾，8.0.45 走 INSTANT，只需短暂元数据锁 |
| 48 | `20261024_official_roam_challenges` | roam_poi 加 gallery_json/photo_required/badge_code；roam_poi_reward_log 加 photo_url/submitted_answer（RoamMapper 直接 SELECT 新列） | 每列 information_schema 存在性守卫 + 动态 ALTER（已存在则 SELECT 1），可重跑；不校验既有同名列形状，靠预检判 CONFLICT | 低：两张既有表加可空/常量默认列，MySQL 8.0 走 INSTANT；仍需短暂元数据锁 |
| 49 | `20261025_official_poi_quotes` | roam_poi 加 quotes_json/photo_credit（官方地点公开引述与照片署名；RoamMapper 直接 SELECT） | 每列 information_schema 存在性守卫 + 动态 ALTER，可重跑 | 低：加可空 TEXT 列，INSTANT；短暂元数据锁 |
| 50 | `20261022_theme_state_key_attribute_def` | theme_state_key 加 9 个可空列 min_value/max_value/display_type/color/icon/sort_order/visible/persist_scope/rules_json（ALGORITHM=INSTANT） | DDL 前断言（同名异形列 fail-closed）+ 逐列 information_schema 判断 → 动态 PREPARE + DDL 后断言 | 低：9 次 INSTANT 加列，每次短暂元数据锁 |
| 51 | `20261022_theme_character` | 新表 theme_character（F2 跨局角色卡） | CREATE TABLE IF NOT EXISTS + 列计数与唯一键断言 | 无：只建新表，空表，瞬时完成 |
| 52 | `20261028_d20_attribute_registry` | theme_state_key 加 is_attribute/attribute_preset（ALGORITHM=INSTANT）；把老主题 journey_rules_json.attributes 的 counter.* 登记进 theme_state_key 并标为属性（ThemeAttributeMapper 直接读写新列） | 列存在性守卫 + 动态 ALTER；回填 INSERT … ON DUPLICATE KEY UPDATE（只补 is_attribute/attribute_preset，不改 label/范围/初值），重跑零变更 | 低：INSTANT 加列；回填扫 cms_topic.journey_rules_json（JSON_TABLE），按主题数线性 |
| 53 | `20261028_remove_wildlife_theme` | 软删测试主题「野生邻居观察局」（990052–990056 区间同名行）：del_flag=2、user_status=0、is_recommend=0 | WHERE 钉住 id 区间 + 名称 + del_flag=0，重跑命中 0 行；末尾断言无在架目标行。执行前必须先跑 _precheck_remove_wildlife_theme_20261028.sql（报名/购买/开玩/结算全 0） | 无：主键区间内单行 UPDATE |
| 54 | `20261028_template_shelf_list_free_explore` | 6 个自由探索主题模板 user_status 0→1，出现在模板页货架（templateStatus 仍为 EXPERIMENTAL） | WHERE 钉住名称 + is_template=1 + status=1 + user_status=0，重跑命中 0 行；末尾断言无待上架行 | 无：最多 6 行 UPDATE |
| 55 | `verification_review_evidence_20261022` | 新表 verification_review_evidence（F4 复核补材料） | CREATE TABLE IF NOT EXISTS + 列串指纹 guard + 唯一键断言 | 无：只建新表，空表，瞬时完成 |
| 56 | `w8_refund_fulfillment_recovery_20261026` | refund_application 加 fulfillment_sync_queue_at（DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)）与索引 idx_refund_fulfillment_recovery（本 SHA 主代码尚无读写方） | 列/索引存在性守卫 + 动态 DDL，可重跑；末尾临时守卫表对既有列/索引形状失败即报主键冲突（fail closed） | 中：既有退款表加带 CURRENT_TIMESTAMP(6) 默认的 NOT NULL 列可能触发表重建，另建 4 列二级索引；看预检 [6] 体量，低峰执行 |
| 57 | `20261026_user_invoice_menu` | sys_menu 新增「用户发票处理」C 菜单（funds-aftercare 下）与 query/taxView/process/reconcile 4 个 F 按钮；不授任何角色 | runner: requires=transaction；逐行 INSERT … WHERE NOT EXISTS（按 perms）；临时守卫表对父菜单不唯一、同名权限重复或形状不符即主键冲突整批回滚 | 无：sys_menu 5 行插入，事务内完成 |
| 58 | `20261013_admin_role_page_grants` | 仅对「一个页面授权都没有」的 cy_operator/cy_auditor/cy_support/cy_finance 按 20260719 矩阵补授权并补祖先菜单；已有页面授权的角色不动（已有页面时 no-op；生产状态未知） | 全部 INSERT … NOT EXISTS；临时表先 DROP TEMPORARY IF EXISTS；无 information_schema 守卫（纯 DML）；有回读断言 | 低：sys_role_menu 小表 |
| 59 | `20261016_admin_role_payee_separation_forward` | D-PAYEE：撤销 cy_operator→business:clubpayee:verify 与 cy_finance→business:clubpayee:payout，补齐 cy_operator→payout 与 cy_finance→verify；全新安装（sys_user 为空且角色无页面）才走批量初始化分支 | -- runner: requires=transaction，单事务；guard 临时表主键冲突（角色/菜单不全、断言失败）让整个事务回滚；INSERT 带 NOT EXISTS，DELETE 重跑删 0 行 | 低：sys_role_menu 小表；SELECT … FOR UPDATE 锁 4 个角色行与 4 个菜单行，事务很短 |
| 60 | `20261027_orient_scoring_menu_grants` | sys_menu 在官方主题 C 菜单下补 business:orientScoring:list/review 两个 F 按钮；sys_role_menu 只授 cy_operator（owner 2026-10-27） | runner: requires=transaction；INSERT … WHERE NOT EXISTS；前置（父菜单恰好 1 行、同名权限不重复且为 F）与回读（2 个按钮、每个有效 cy_operator 持有、其他角色不持有）任一不成立即主键冲突整批回滚 | 无：sys_menu 2 行 + sys_role_menu 少量行，事务内完成 |

### 1.2 演练状态契约与执行后只读回读

expectedAtBaseline 只指仓库重建的合成库，不是生产当前状态；DATA_PREVIEW 不证明数据已完成。

| step | migrationId | 合成基线 | 首次执行后 | 重入策略 | SELECT-only 回读 |
|---|---|---|---|---|---|
| 1 | `20261018_infomation_change_audit` | NEEDED | APPLIED | EXECUTE | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('cms_infomation_change_audit'); -- 期望 1` |
| 2 | `20261022_audit_activity_switch_seed` | NEEDED | APPLIED | EXECUTE | `SELECT config_key, config_value, COUNT(*) OVER (PARTITION BY config_key) n FROM sys_config WHERE config_key IN ('audit.activity.pre_review_all', 'audit.activity.high_risk_categories'); -- 每键 n=1` |
| 3 | `20261022_f7_minor_guardian` | NEEDED | APPLIED | EXECUTE | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('minor_guardian_authorization'); -- 期望 1` |
| 4 | `20261022_f7_user_invoice` | NEEDED | APPLIED | EXECUTE | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('user_invoice_title', 'user_invoice_application', 'user_invoice_application_order'); -- 期望 3` |
| 5 | `20261022_location_guard_switch_seed` | NEEDED | APPLIED | EXECUTE | `SELECT config_key, config_value, COUNT(*) OVER (PARTITION BY config_key) n FROM sys_config WHERE config_key IN ('play.arrive.location_guard.mode', 'play.arrive.require_accuracy', 'roam.visited_poi.stable_identity'); -- 每键 n=1` |
| 6 | `20261022_merchant_desk_switch_seed` | NEEDED | APPLIED | EXECUTE | `SELECT config_key, config_value, COUNT(*) OVER (PARTITION BY config_key) n FROM sys_config WHERE config_key IN ('merchant.desk.f4.enabled'); -- 每键 n=1` |
| 7 | `20261023_creative_square_like_unique` | NEEDED | APPLIED | EXECUTE | `SELECT member_id, data_id, COUNT(*) AS duplicate_rows FROM ums_creative_square_like GROUP BY member_id, data_id HAVING COUNT(*) > 1; -- 期望 0 行`<br>`SELECT index_name, non_unique, seq_in_index, column_name, sub_part, index_type, is_visible FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'ums_creative_square_like' AND index_name = 'uk_square_like_member_data'; -- 两列唯一 BTREE，无前缀` |
| 8 | `20261023_points_value_config_seed` | NEEDED | APPLIED | EXECUTE | `SELECT config_key, config_value, COUNT(*) OVER (PARTITION BY config_key) AS rows_for_key FROM sys_config WHERE config_key IN ('points.deduct.rate_yuan_per_point','points.deduct.max_yuan_per_order'); -- 每键恰好 1 行，已有值保持原样` |
| 9 | `20261023_wx_pay_order_intent` | NEEDED | APPLIED | PRECHECK_SKIP_APPLIED | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('wx_pay_order_intent'); -- 期望 1` |
| 10 | `20261024_cash_withdraw_transfer_intent` | NEEDED | APPLIED | PRECHECK_SKIP_APPLIED | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('member_cash_withdraw_transfer_intent'); -- 期望 1` |
| 11 | `20261024_community_open_flags` | NEEDED | APPLIED | EXECUTE | `SELECT config_key, config_value FROM sys_config WHERE config_key IN ('feature.flag.communityPostRead', 'feature.flag.communityPostWrite', 'feature.flag.communityPostPilotMembers'); -- 期望 3 行；值以 owner 决定为准` |
| 12 | `20261025_coop_invite_request_receipt` | NEEDED | APPLIED | EXECUTE | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('coop_invite_request_receipt'); -- 期望 1` |
| 13 | `20261026_official_event_attendance` | NEEDED | APPLIED | EXECUTE | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('official_event_attendance_config', 'official_event_attendance'); -- 期望 2` |
| 14 | `20261026_team_event_command_receipt` | NEEDED | APPLIED | EXECUTE | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('team_event_command_receipt'); -- 期望 1` |
| 15 | `20261026_theme_attribute_registry_version` | NEEDED | APPLIED | EXECUTE | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('theme_attribute_registry_version'); -- 期望 1` |
| 16 | `20261026_user_invoice_processing` | NEEDED | APPLIED | EXECUTE | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('user_invoice_issuer', 'user_invoice_issuer_owner', 'user_invoice_scope', 'user_invoice_processing', 'user_invoice_processing_audit', 'user_invoice_issued_reference'); -- 期望 6` |
| 17 | `20261026_user_invoice_title_receipt` | NEEDED | APPLIED | EXECUTE | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('user_invoice_title_receipt'); -- 期望 1` |
| 18 | `20261027_template_adoption_receipt` | NEEDED | APPLIED | EXECUTE | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('template_adoption_receipt'); -- 期望 1` |
| 19 | `20261027_user_invoice_processing_backfill` | NEEDED | DATA_PREVIEW | EXECUTE | `SELECT COUNT(*) FROM user_invoice_application a WHERE NOT EXISTS (SELECT 1 FROM user_invoice_processing p WHERE p.application_id = a.id); -- 回填后仍无处理行的申请数（映射缺失/跨主体），交 owner 核对` |
| 20 | `20261028_topic_play_setup` | NEEDED | APPLIED | EXECUTE | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('cms_topic_play_setup'); -- 期望 1` |
| 21 | `audit_problem_20261022` | NEEDED | APPLIED | EXECUTE | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('audit_problem'); -- 期望 1` |
| 22 | `club_welcome_note_20261022` | NEEDED | APPLIED | PRECHECK_SKIP_APPLIED | `SELECT column_name, column_type, is_nullable, column_default, extra, generation_expression FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'club' AND column_name = 'welcome_note'; -- varchar(600)、YES、NULL、普通列` |
| 23 | `doc2_f3_bundle_ledger_20261022` | NEEDED | APPLIED | EXECUTE | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('explore_bundle_order', 'explore_bundle_grant', 'explore_bundle_unit', 'explore_bundle_reservation', 'explore_bundle_waiver', 'explore_bundle_exit_case'); -- 期望 6` |
| 24 | `doc2_f3_refund_hold_20261026` | NEEDED | APPLIED | EXECUTE | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('explore_bundle_refund_hold'); -- 期望 1` |
| 25 | `member_profile_version_20261022` | NEEDED | APPLIED | EXECUTE | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('ums_member_profile_version'); -- 期望 1` |
| 26 | `member_visited_poi_poi_key_20261022` | NEEDED | APPLIED | EXECUTE | `SELECT column_name, column_type, is_nullable, column_default FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'member_visited_poi' AND column_name IN ('poi_key');`<br>`SELECT index_name, non_unique, seq_in_index, column_name FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'member_visited_poi' AND index_name = 'uk_member_poi_key';` |
| 27 | `merchant_category_ids_20261022` | NEEDED | DATA_PREVIEW | EXECUTE | `SELECT column_name, column_type, is_nullable, column_default FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'mms_merchant' AND column_name = 'category_ids'; -- varchar(255)、YES、NULL`<br>`SELECT COUNT(*) AS remaining_backfill FROM mms_merchant WHERE category_id > 0 AND (category_ids IS NULL OR category_ids = ''); -- 列存在后执行，期望 0` |
| 28 | `20261027_merchant_brand_images_to_gallery` | DATA_PREVIEW | DATA_PREVIEW | EXECUTE | `SELECT COUNT(*) FROM mms_merchant WHERE derivatives IS NOT NULL AND TRIM(derivatives) <> ''; -- 有品牌形象图的商家数`<br>`SELECT COUNT(*) FROM mms_merchant WHERE gallery IS NOT NULL AND TRIM(gallery) <> '' AND (JSON_VALID(gallery) = 0 OR JSON_TYPE(gallery) <> 'ARRAY'); -- 被跳过的非法 gallery 行，交 owner` |
| 29 | `merchant_desk_receipt_20261022` | NEEDED | APPLIED | EXECUTE | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('merchant_desk_receipt'); -- 期望 1` |
| 30 | `merchant_redemption_anchor_20261022` | NEEDED | APPLIED | EXECUTE | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('merchant_redemption_anchor'); -- 期望 1` |
| 31 | `merchant_wechat_qrcode_20261022` | NEEDED | DATA_PREVIEW | EXECUTE | `SELECT column_name, column_type, is_nullable, column_default FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'mms_merchant' AND column_name = 'wechat_qrcode'; -- varchar(512)、YES、NULL`<br>`SELECT COUNT(*) AS remaining_url FROM mms_merchant WHERE wechat LIKE 'http://%' OR wechat LIKE 'https://%'; -- 期望 0` |
| 32 | `o14_city_code_backfill_log_20261022` | NEEDED | APPLIED | EXECUTE | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('o14_city_code_backfill_log'); -- 期望 1` |
| 33 | `o14_cms_activity_city_code_20261022` | NEEDED | APPLIED | EXECUTE | `SELECT column_name, column_type, is_nullable, column_default FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cms_activity' AND column_name IN ('city_code');`<br>`SELECT index_name, non_unique, seq_in_index, column_name FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'cms_activity' AND index_name = 'idx_cms_activity_city_code';` |
| 34 | `o14_cms_topic_city_code_20261022` | NEEDED | APPLIED | EXECUTE | `SELECT column_name, column_type, is_nullable, column_default FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cms_topic' AND column_name IN ('city_code');`<br>`SELECT index_name, non_unique, seq_in_index, column_name FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'cms_topic' AND index_name = 'idx_cms_topic_city_code';` |
| 35 | `o14_content_city_code_backfill_20261022` | NEEDED | DATA_PREVIEW | EXECUTE | `SELECT COUNT(*) FROM cms_topic WHERE city_code IS NOT NULL AND city_code NOT REGEXP '^[0-9]{6}$'; -- 期望 0`<br>`SELECT target_table, source, COUNT(*) FROM o14_city_code_backfill_log GROUP BY target_table, source;` |
| 36 | `o14_content_search_document_20261022` | NEEDED | APPLIED | EXECUTE | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('content_search_document'); -- 期望 1` |
| 37 | `20261026_official_broadcast_delivery` | NEEDED | APPLIED | PRECHECK_SKIP_APPLIED | `SELECT table_name, column_name, column_type, is_nullable, column_default FROM information_schema.columns WHERE table_schema = DATABASE() AND ((table_name = 'official_broadcast' AND column_name IN ('audience_frozen', 'client_request_id', 'request_owner', 'request_hash')) OR (table_name = 'official_broadcast_log' AND column_name IN ('delivery_state', 'frozen_title', 'frozen_sub', 'intent_id', 'message_id', 'attempts', 'update_time'))); -- 期望 11 行`<br>`SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_creation'; -- 期望 1` |
| 38 | `orient_geo_ar_team_20261022` | NEEDED | APPLIED | EXECUTE | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('orient_node_geo', 'orient_task_alternative', 'orient_task_attempt', 'orient_task_completion', 'orient_safety_rule'); -- 期望 5`<br>`SELECT config_key, config_value, COUNT(*) OVER (PARTITION BY config_key) n FROM sys_config WHERE config_key IN ('orient.geo.normalize.enabled', 'orient.alt_task.enabled', 'orient.team.board.enabled', 'orient.team.completion_ratio', 'orient.safety.enabled'); -- 每键 n=1` |
| 39 | `orient_score_recovery_20261026` | NEEDED | APPLIED | EXECUTE | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('orient_score_recovery'); -- 期望 1` |
| 40 | `orient_score_sync_receipt_20261026` | NEEDED | APPLIED | EXECUTE | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('orient_score_sync_receipt'); -- 期望 1` |
| 41 | `o14_reco_search_jobs_20261022` | NEEDED | APPLIED | EXECUTE | `SELECT invoke_target, status, COUNT(*) FROM sys_job WHERE invoke_target IN ('recommendationScoreDailyTask.run()', 'contentSearchProjectionTask.refresh()') GROUP BY invoke_target, status; -- 每个 1 行且 status='1'` |
| 42 | `play_arrive_guard_log_20261022` | NEEDED | APPLIED | EXECUTE | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('play_arrive_guard_log'); -- 期望 1` |
| 43 | `pms_sku_booking_20261022` | NEEDED | APPLIED | EXECUTE | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('pms_sku_booking_rule', 'pms_sku_capacity_slot'); -- 期望 2` |
| 44 | `orient_scoring_20261022` | NEEDED | APPLIED | EXECUTE | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('orient_score_ledger', 'orient_score_settlement', 'orient_score_appeal', 'orient_result_dispute'); -- 期望 4`<br>`SELECT config_key, config_value FROM sys_config WHERE config_key LIKE 'orient.scoring.%'; -- 5 行` |
| 45 | `orient_session_lifecycle_20261022` | NEEDED | APPLIED | EXECUTE | `SELECT column_name, column_type, is_nullable, column_default FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'topic_route_session' AND column_name IN ('phase', 'status');`<br>`SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('orient_session_clock'); -- 期望 1` |
| 46 | `member_visited_poi_poi_key_backfill_20261022` | NEEDED | DATA_PREVIEW | EXECUTE | `SELECT COUNT(*) FROM member_visited_poi WHERE poi_key IS NULL; -- 只剩重复行`<br>`SELECT member_id, poi_key, COUNT(*) c FROM member_visited_poi WHERE poi_key IS NOT NULL GROUP BY member_id, poi_key HAVING c > 1; -- 期望 0 行` |
| 47 | `roam_share_version_20261022` | NEEDED | APPLIED | EXECUTE | `SELECT column_name, column_type, is_nullable, column_default FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'roam_share_snapshot' AND column_name IN ('version');` |
| 48 | `20261024_official_roam_challenges` | NEEDED | APPLIED | EXECUTE | `SELECT table_name, column_name, column_type, is_nullable, column_default FROM information_schema.columns WHERE table_schema = DATABASE() AND ((table_name = 'roam_poi' AND column_name IN ('gallery_json', 'photo_required', 'badge_code')) OR (table_name = 'roam_poi_reward_log' AND column_name IN ('photo_url', 'submitted_answer'))); -- 期望 5 行` |
| 49 | `20261025_official_poi_quotes` | NEEDED | APPLIED | EXECUTE | `SELECT column_name, column_type, is_nullable FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'roam_poi' AND column_name IN ('quotes_json', 'photo_credit'); -- 期望 2 行` |
| 50 | `20261022_theme_state_key_attribute_def` | NEEDED | APPLIED | EXECUTE | `SELECT column_name, column_type, is_nullable, column_default FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'theme_state_key' AND column_name IN ('min_value', 'max_value', 'display_type', 'color', 'icon', 'sort_order', 'visible', 'persist_scope', 'rules_json');` |
| 51 | `20261022_theme_character` | NEEDED | APPLIED | EXECUTE | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('theme_character'); -- 期望 1` |
| 52 | `20261028_d20_attribute_registry` | NEEDED | APPLIED | EXECUTE | `SELECT column_name, column_type, is_nullable, column_default FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'theme_state_key' AND column_name IN ('is_attribute', 'attribute_preset'); -- 期望 2 行`<br>`SELECT COUNT(*) FROM theme_state_key WHERE is_attribute = 1; -- 回填后的属性键数，仅记录` |
| 53 | `20261028_remove_wildlife_theme` | APPLIED | APPLIED | EXECUTE | `SELECT id, del_flag, user_status, is_recommend FROM cms_topic WHERE id BETWEEN 990052 AND 990056 AND name = '野生邻居观察局'; -- 期望 del_flag=2（或无行）` |
| 54 | `20261028_template_shelf_list_free_explore` | APPLIED | APPLIED | EXECUTE | `SELECT id, name, is_template, status, user_status, del_flag FROM cms_topic WHERE del_flag = 0 AND name IN ('风味巡游', '不惑之年', '预制人生', '高温末日逃生', '上海像一部旧电影', '生活不掉线'); -- 期望 6 行，user_status=1` |
| 55 | `verification_review_evidence_20261022` | NEEDED | APPLIED | EXECUTE | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('verification_review_evidence'); -- 期望 1` |
| 56 | `w8_refund_fulfillment_recovery_20261026` | NEEDED | APPLIED | EXECUTE | `SELECT column_type, is_nullable, column_default FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'refund_application' AND column_name = 'fulfillment_sync_queue_at'; -- 期望 datetime(6)/NO/CURRENT_TIMESTAMP(6)`<br>`SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'refund_application' AND index_name = 'idx_refund_fulfillment_recovery'; -- 期望 source_type,del_flag,fulfillment_sync_queue_at,id` |
| 57 | `20261026_user_invoice_menu` | NEEDED | APPLIED | EXECUTE | `SELECT menu_id, parent_id, menu_type, path, component, perms FROM sys_menu WHERE perms IN ('business:userInvoice:list', 'business:userInvoice:query', 'business:userInvoice:taxView', 'business:userInvoice:process', 'business:userInvoice:reconcile') ORDER BY menu_type, perms; -- 期望 1 行 C + 4 行 F` |
| 58 | `20261013_admin_role_page_grants` | APPLIED | APPLIED | EXECUTE | `SELECT r.role_key, COUNT(*) FROM sys_role r JOIN sys_role_menu rm ON rm.role_id = r.role_id JOIN sys_menu m ON m.menu_id = rm.menu_id WHERE r.del_flag = '0' AND m.menu_type = 'C' AND r.role_key LIKE 'cy\\_%' GROUP BY r.role_key; -- 4 个角色均 > 0` |
| 59 | `20261016_admin_role_payee_separation_forward` | NEEDED | APPLIED | EXECUTE | `SELECT r.role_key, m.perms FROM sys_role_menu rm JOIN sys_role r ON r.role_id = rm.role_id JOIN sys_menu m ON m.menu_id = rm.menu_id WHERE r.del_flag = '0' AND r.role_key IN ('cy_operator', 'cy_finance') AND m.perms IN ('business:clubpayee:verify', 'business:clubpayee:payout'); -- 期望恰好 2 行：cy_operator/payout、cy_finance/verify` |
| 60 | `20261027_orient_scoring_menu_grants` | NEEDED | APPLIED | EXECUTE | `SELECT r.role_key, m.perms FROM sys_role_menu rm JOIN sys_role r ON r.role_id = rm.role_id JOIN sys_menu m ON m.menu_id = rm.menu_id WHERE m.perms IN ('business:orientScoring:list', 'business:orientScoring:review') ORDER BY r.role_key, m.perms; -- 期望只有 cy_operator x2` |
<!-- END GENERATED RELEASE INVENTORY -->

## 2 阶段与只读预检的处置

- `PRE_DEPLOY_REQUIRED`：新 jar 的 mapper / JDBC 直接读写该表或列，必须先于新 jar 落库，否则对应接口在新 jar 上直接报错。
- `PRE_DEPLOY`：本 SHA 主代码对缺失有容错（开关关闭 / 尚无读写方 / 后台菜单），发布顺序仍统一放在新 jar 之前。
- `OWNER_GATED_DATA`：数据回填/搬迁/上下架；先跑只读预览（预检附录 B / C）并由 owner 抽检，再执行。不阻塞 jar，但必须在打开相关功能前完成。
- `APPLIED`：对象/目标状态符合预检契约；不能据此推断迁移执行历史。**单次执行项（见第 3 节）的 APPLIED 必须跳过原 SQL**；其他项按各自守卫可重跑。
- `NEEDED`：需执行；`20261013_admin_role_page_grants` 例外，见第 4 节。
- `PARTIAL`：只有守卫说明可续跑的迁移才允许恢复；单次执行项的 PARTIAL 一律停止交 owner。
- `CONFLICT` / `BLOCKED_PREREQ`：停止，交 owner 处理；不跳过错误继续执行。
- `DATA_PREVIEW`：前置结构可用，数据仍须核对；它不证明已完成。

新增项的表结构指纹沿用 10-22 预检的算法（列 = 序号/列名/类型/可空/默认；索引 = 名称/唯一/序号/列），由迁移文本推出，演练在 MySQL 8.0.45 上回读钉住；加列类迁移逐列比对类型/可空/默认。

## 3 单次执行迁移

以下迁移使用普通 `CREATE TABLE` / `ALTER TABLE ADD COLUMN`，直接重跑必然报错（1050 / 1060），原 SQL 字节保持不变：

| migrationId | 直接重跑 | 处置 |
|---|---|---|
| `club_welcome_note_20261022` | 1060 Duplicate column | 10-22 包第 3 节原样适用（演练保留直接重跑负控） |
| `20261023_wx_pay_order_intent` | 1050 Table exists | NEEDED 执行一次；APPLIED 跳过；其他停止 |
| `20261024_cash_withdraw_transfer_intent` | 1050 Table exists | 同上 |
| `20261026_official_broadcast_delivery` | 1060 Duplicate column | 同上；中途失败会留下 PARTIAL（前面的 ALTER 已隐式提交），不得整文件重跑，交 owner 写前向修复 |

`20261023_wx_pay_order_intent.sql` 文件头写着 “PROPOSAL ONLY … Integrator approval and isolated MySQL preflight required”。本 SHA 的 `WechatPayServiceImpl` 已直接读写这张表，所以它列为 PRE_DEPLOY_REQUIRED；**执行前请 owner 明确确认这一项**。

演练第二遍对这四项先跑新鲜预检，APPLIED 才跳过并单独记账；负控制造坏表/缺表后必须拒绝跳过。

## 4 沿用 10-22 的闸门

- D-PAYEE（`20261016`）与 `20261013` checksum 对账、fresh-roles 风险探针：见 10-22 包第 4 节，本包不改。**`20261013` 预检 NEEDED 时禁止执行，交 owner。**
- 商家 `category_ids` / `wechat_qrcode` 回读、态度唯一键重复不自动删除：见 10-22 包第 2 节。

## 5 新增项要特别看的

- `20261024_community_open_flags`：键缺失时插入 `true` / `true` / `*`，即上线即对所有玩家开放专业广场浏览与发帖（owner 2026-10-24 决定）。已配置的值不会被覆盖。
- `20261026_official_broadcast_delivery` 给 `official_broadcast` 加唯一键、`w8_refund_fulfillment_recovery_20261026` 给 `refund_application` 加带 `CURRENT_TIMESTAMP(6)` 默认的 NOT NULL 列和 4 列索引：可能需要建索引/重建表，先看预检 [6/7] 体量与 [7/7] 长事务，低峰执行。
- `20261028_remove_wildlife_theme`：`_precheck_remove_wildlife_theme_20261028.sql` 的注释记录了“2026-10-28 生产实跑……随后迁移已执行”。本包不据此改判，仍为 UNKNOWN；预检对无在架目标行判 APPLIED（跳过即可）。
- `20261027_user_invoice_processing_backfill`：开票主体映射 `user_invoice_issuer_owner` 是空配置，未配置时回填 0 行；回填后仍无处理行的申请交 owner 人工核对（附录 C1）。
- `20261027_merchant_brand_images_to_gallery`：整表 `UPDATE … JOIN`，先跑附录 C2 预览。演练用合成商家证明“已在相册的图不重复、按原顺序追加、derivatives 原值保留”。
- 菜单两项（发票处理、计分申诉）走 `runner: requires=transaction`，任一前置/回读不成立整批回滚；计分申诉权限只授 `cy_operator`。

## 6 隔离演练与验证范围

GitHub 托管 workflow 使用钉 digest 的 MySQL 8.0.45 一次性容器，不连接 VPS 或生产：

1. 从参考基线与当前源提交计算发布集，逐项核对 path/hash/order/dependsOn；数量不写死。
2. 从仓库基线 schema/seed 和非发布集迁移重建合成库，写入合成行（含品牌形象图商家）。
3. 逐项检查 `expectedAtBaseline`，按 owner 执行顺序（`execution_rounds`：A 轮 PRE_DEPLOY_REQUIRED 及其发布集内前置 → B 轮其余 PRE_DEPLOY → C 轮 OWNER_GATED_DATA，每轮内按 step）执行全部 60 项，任何错误或断言失败立即停止；脚本先校验该顺序不违反任何声明的依赖。
4. 检查 `expectedAfterApply`；回读商家回填、积分种子与品牌相册搬迁。
5. welcome_note 原文件直接重跑的 1060 负控；第二遍可重入项实际重跑、单次执行项按预检跳过，全库 schema/索引/约束/CHECKSUM 零变更（唯一声明差异仍是 `o14_city_code_backfill_log.write_time`）。
6. 负控：10-22 的全部负控 + 新增单次执行坏表/缺表、加列漂移、配置键重复、菜单形状漂移、授权越权、索引缺失。
7. fresh-roles 风险探针与容器清理。

验证层级必须分开：源码/静态测试不等于 hosted MySQL；hosted scratch MySQL 不等于 VPS；二者均不证明生产已应用或真机/真 HTTP 行为。演练运行链接与结果记录在本包对应 PR 的正文与总结评论里（不提交回执）。

## 7 备份、停止与回滚

由 owner 在执行任何生产写入前完成只读预检、维护窗口与带指纹的可恢复备份，按 [release-steps.md](release-steps.md) 留存回执。没有备份不执行；任一步失败立即停止，不继续后续文件。

DDL 会隐式提交，失败前的语句可能已生效。保存失败输出并重新只读回读；只在具体迁移的幂等契约允许时从同一步恢复。JAR 回滚不会撤销 DDL，本批迁移都只加表/加列/加菜单或做可逆数据更新，“旧 jar + 新 schema”可运行；撤销结构或数据须另写前向迁移并经 owner 批准，禁止直接执行注释里的破坏性回滚语句。

## 8 合并新迁移后可复现刷新

```sh
python3 ci/release_migration_rehearsal.py --inventory
python3 ci/release_migration_rehearsal.py --refresh-pack
python3 -m unittest -v ci.test_release_migration_rehearsal
git diff --check
```

`--refresh-pack` 不连库，只从**已提交 HEAD**计算差异，保留人工审阅的阶段、风险、回读和预期状态，重算校验和/依赖/拓扑步序、预检顺序、源 SHA/tree/SQL 子树/台账指纹及本文件生成区。新增迁移未审阅齐全时拒绝刷新。任何新包都不改变生产 UNKNOWN 状态。
