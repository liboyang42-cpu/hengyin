# 发布迁移包（release-closeout-20261022）

本包准备发布顺序、只读预检与隔离演练。**全部生产执行状态保持 UNKNOWN**；参考基线不等于生产已应用状态。本任务不连接生产、不执行生产 SQL、不部署。

机器契约为 [release-migration-pack.json](release-migration-pack.json)，预检为 [release-migration-precheck.sql](release-migration-precheck.sql)。演练使用现有 `ci/release_migration_rehearsal.py`、`ci/test_release_migration_rehearsal.py` 及 `.github/workflows/release-migration-rehearsal.yml`。

<!-- BEGIN GENERATED RELEASE INVENTORY -->
## 0 来源与覆盖范围

| 项 | 值 |
|---|---|
| 仓库参考基线 | `19408d49172c1d8fd16ec012f4498ff8ea584a37`；不声明生产已执行 |
| 源提交 | `11c8055e179930cd63a1d6e296b8285b0586dd16` |
| 源 tree | `99042a25713445028217379d87fc8ba83cb14562` |
| SQL 子树 | `15977e02aa702b90b34c46d6f13c7b2ee89d51b7` |
| 依赖台账 SHA-256 | `59fa9f5ff6f26511e88a52b517cebf00411466ea834f1918631bad381b5ec9f7` |
| 发布集 | 35 项；新增 34、修改 1、删除 0 |
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
| 9 | 68 | `chengyinhub-admin/sql/migration_audit_problem_20261022.sql` | ADDED | `02353f0296b49751e5a035a129a03fccf9173cddba2194e7489b32449434a080` | — | PRE_DEPLOY |
| 10 | 107 | `chengyinhub-admin/sql/migration_club_welcome_note_20261022.sql` | ADDED | `668c8955b2c965eeb84436ad70a72e264a9c3aaa7d8d77450a282cc3b680f1ee` | club_open_settings_20260909 | PRE_DEPLOY |
| 11 | 140 | `chengyinhub-admin/sql/migration_doc2_f3_bundle_ledger_20261022.sql` | ADDED | `f94e363d84923f4b6e97bce4ec49382364ec2568e7efe6cdcc83c10ed4b8f419` | — | PRE_DEPLOY |
| 12 | 185 | `chengyinhub-admin/sql/migration_member_profile_version_20261022.sql` | ADDED | `8be4a0d82578fe1d74579f5abbf859307f0b827655d670ff0e398cf026d018ba` | — | PRE_DEPLOY_REQUIRED |
| 13 | 187 | `chengyinhub-admin/sql/migration_member_visited_poi_poi_key_20261022.sql` | ADDED | `ddb6c4652f8138a42a2ec380af3981f6674e574834ade341736e12eacb31c07a` | member_visited_poi | PRE_DEPLOY |
| 14 | 193 | `chengyinhub-admin/sql/migration_merchant_category_ids_20261022.sql` | ADDED | `e26703b2a6cb15eea3996c2d188af546002c88f46135accd5956ce1fdd37cbd1` | — | PRE_DEPLOY_REQUIRED |
| 15 | 200 | `chengyinhub-admin/sql/migration_merchant_desk_receipt_20261022.sql` | ADDED | `83746d2cb7e5de51365f97782d9e8692fb58c6bdf3196e854475d102d2fc2278` | — | PRE_DEPLOY |
| 16 | 211 | `chengyinhub-admin/sql/migration_merchant_redemption_anchor_20261022.sql` | ADDED | `907c01f72fb7f77ceed83a0aa8eb51de4cbd55dc740218a0e1a65ca33c2da6e0` | — | PRE_DEPLOY |
| 17 | 215 | `chengyinhub-admin/sql/migration_merchant_wechat_qrcode_20261022.sql` | ADDED | `c5f52a9512e615c08522a8d0fa7b62241863664e3853461aecbd7406d680017e` | — | PRE_DEPLOY_REQUIRED |
| 18 | 237 | `chengyinhub-admin/sql/migration_o14_city_code_backfill_log_20261022.sql` | ADDED | `cc5f6e4dd6e8880f42012cbb7069a2ffaf79b8ac5ac75565e09b2c159922a1ed` | — | PRE_DEPLOY |
| 19 | 238 | `chengyinhub-admin/sql/migration_o14_cms_activity_city_code_20261022.sql` | ADDED | `6f8aa378506994897748617ae9209445cca32c2eefe613549194f6252351ee95` | — | PRE_DEPLOY |
| 20 | 239 | `chengyinhub-admin/sql/migration_o14_cms_topic_city_code_20261022.sql` | ADDED | `fcda5dcf5bf4172238564758d008b4b64d6eab6a8861335dbb5d063e2dd93ff1` | — | PRE_DEPLOY |
| 21 | 240 | `chengyinhub-admin/sql/migration_o14_content_city_code_backfill_20261022.sql` | ADDED | `abfb4c27b3219ab3906c29feb9c3a9c920f498913f26f06bb177740a271c085a` | o14_city_code_backfill_log_20261022, o14_cms_activity_city_code_20261022, o14_cms_topic_city_code_20261022 | OWNER_GATED_DATA |
| 22 | 241 | `chengyinhub-admin/sql/migration_o14_content_search_document_20261022.sql` | ADDED | `1d153c6fbbe8e7595cb169e906c64df975d1f85b88c486d1a0db6e4e8ebe3d5f` | — | PRE_DEPLOY |
| 23 | 257 | `chengyinhub-admin/sql/migration_orient_geo_ar_team_20261022.sql` | ADDED | `d297c399d1c12d568d551ebe6dc17d6cc87bc95a6bae6d5667ef1e991d01f1e3` | — | PRE_DEPLOY |
| 24 | 288 | `chengyinhub-admin/sql/migration_o14_reco_search_jobs_20261022.sql` | ADDED | `046921a70dbbf4f8ea32aa582d651e0a0d9c798cfd9c79a2df3b027972ccc370` | o14_content_search_document_20261022, p3_platform_foundation | PRE_DEPLOY |
| 25 | 302 | `chengyinhub-admin/sql/migration_play_arrive_guard_log_20261022.sql` | ADDED | `52b937c5468f03006aef2e6b15c1beeb9a625c539558ca805c57fc4ff453b1fb` | — | PRE_DEPLOY |
| 26 | 311 | `chengyinhub-admin/sql/migration_pms_sku_booking_20261022.sql` | ADDED | `0057c4368f7ade02120c109c791950e75b7f4c5d0dd6b42b8baf1fcb8f1fd8ac` | — | PRE_DEPLOY |
| 27 | 333 | `chengyinhub-admin/sql/migration_orient_scoring_20261022.sql` | ADDED | `17893c96611ad13875178c9b6ebf4fcb332e7a11fad5f6fb8a7d1f25fd9e055a` | 20260823_topic_route_graph | PRE_DEPLOY |
| 28 | 334 | `chengyinhub-admin/sql/migration_orient_session_lifecycle_20261022.sql` | ADDED | `ffc9001685f87179b58fa7bb9c104bc27c608664b3288e79234053699ffd2735` | 20260823_topic_route_graph | PRE_DEPLOY_REQUIRED |
| 29 | 376 | `chengyinhub-admin/sql/migration_member_visited_poi_poi_key_backfill_20261022.sql` | ADDED | `2980391167e7bb4113a9523158e7a7fb5577eb8875d534f27db7f612e240af27` | member_visited_poi_poi_key_20261022, roam | OWNER_GATED_DATA |
| 30 | 387 | `chengyinhub-admin/sql/migration_roam_share_version_20261022.sql` | ADDED | `563ddd80ae4e23ce96d02183380d9175209e68702aaf06f1061fd9a6688170b6` | 20260917_roam_share_snapshot | PRE_DEPLOY_REQUIRED |
| 31 | 425 | `chengyinhub-admin/sql/migration_20261022_theme_state_key_attribute_def.sql` | ADDED | `8b69583c24795742c0244610bb8dab6ef9abec64577a0a13666c5929146e0b25` | theme_state_key_20260916 | PRE_DEPLOY_REQUIRED |
| 32 | 426 | `chengyinhub-admin/sql/migration_20261022_theme_character.sql` | ADDED | `a0a8637bdf95674f4c11dabeb3852ab53a923789507a59e0b5cd64587f1adc71` | 20261022_theme_state_key_attribute_def, theme_state_key_20260916 | PRE_DEPLOY_REQUIRED |
| 33 | 479 | `chengyinhub-admin/sql/migration_verification_review_evidence_20261022.sql` | ADDED | `9b13f58da4455c4af67429bbe604086cf28cd451f19ad71f0bf09b489d42d889` | — | PRE_DEPLOY |
| 34 | 500 | `chengyinhub-admin/sql/migration_20261013_admin_role_page_grants.sql` | MODIFIED | `1f147a2136e999b6fd87a7e0a865d8fde5b0c332170d561b28e415b9fccaf56e` | 20261012_media_review_menu, 20261012_missing_business_menu, role2_analytics_perm_20260918, web_admin_roles_20260719 | PRE_DEPLOY |
| 35 | 501 | `chengyinhub-admin/sql/migration_20261016_admin_role_payee_separation_forward.sql` | ADDED | `4eeef3936f72f05e69b3440aa818c2c39d53e046aaabd82cfdef10c868cca34e` | 20260816_tandianri_batch3_funds_club_compensation, 20260816_tandianri_batch3_funds_club_payee, 20260817_tandianri_batch3_funds_club_hours_correction, 20261013_admin_role_page_grants | PRE_DEPLOY |

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
| 9 | `audit_problem_20261022` | 新表 audit_problem（审核逐项驳回原因）；必须先于打开 chengyin.audit.item-problems.enabled | 仅 CREATE TABLE IF NOT EXISTS，无 information_schema 断言（同名残表会被静默接受 → 由预检指纹判 CONFLICT） | 无：只建新表，空表，瞬时完成 |
| 10 | `club_welcome_note_20261022` | club 增加 welcome_note VARCHAR(600) NULL DEFAULT NULL；代码独立读取，旧行保持默认欢迎文案 | 历史 SQL 是普通 ADD COLUMN，不可直接重跑；开头 SELECT 仅供人工看结果，并不阻止 ALTER。只在预检 NEEDED 时执行一次；APPLIED 必须跳过；其他状态停止 | 低：加可空列需要短暂元数据锁；旧 SQL 未显式指定 ALGORITHM。直接重跑必须报 MySQL 1060，禁止吞错后继续 |
| 11 | `doc2_f3_bundle_ledger_20261022` | 新表 explore_bundle_order/grant/unit/reservation/waiver/exit_case（F3 套票台账；ledger-enabled 默认关） | CREATE TABLE IF NOT EXISTS + information_schema 列与唯一键 SHA-256 指纹；不符时 guard 临时表主键冲突让执行器停 | 无：只建新表，空表，瞬时完成 |
| 12 | `member_profile_version_20261022` | 新表 ums_member_profile_version（资料版本回执；新 jar 表缺失只 WARN） | 仅 CREATE TABLE IF NOT EXISTS，无 information_schema 断言（台账已标风险 → 由预检指纹判 CONFLICT） | 无：只建新表，空表，瞬时完成 |
| 13 | `member_visited_poi_poi_key_20261022` | member_visited_poi 加可空列 poi_key VARCHAR(32) AFTER geo_key + 唯一键 uk_member_poi_key(member_id, poi_key) | information_schema 判列/索引是否存在 → 动态 PREPARE（存在则 SELECT 1）+ 回读断言 | 中：ADD COLUMN 在 8.0.45 走 INSTANT（仅元数据锁）；ADD UNIQUE KEY 为 INPLACE 在线建索引，耗时 ∝ 表行数，全为 NULL 不会撞唯一 |
| 14 | `merchant_category_ids_20261022` | mms_merchant 增加 category_ids VARCHAR(255) NULL；将为空且 category_id>0 的行回填为原主行业，保留已有多选值 | InnoDB 表与同名列形状 guard；按 information_schema 判列后 INSTANT ADD；只回填空值，runner 断言回填无遗漏。主预检只核结构，列存在时标 DATA_PREVIEW，须执行附录 B3 的数据回读 | 中：INSTANT 加列需短暂元数据锁；UPDATE 扫描并锁住待回填商家行，低峰执行，先留只读行数预览 |
| 15 | `merchant_desk_receipt_20261022` | 新表 merchant_desk_receipt（F4 工作台幂等回执；merchant.desk.f4.enabled 默认关） | CREATE TABLE IF NOT EXISTS + 列串指纹 guard + 唯一键断言 | 无：只建新表，空表，瞬时完成 |
| 16 | `merchant_redemption_anchor_20261022` | 新表 merchant_redemption_anchor（F4 核销锚点配置） | CREATE TABLE IF NOT EXISTS + 列串指纹 guard + 唯一键断言 | 无：只建新表，空表，瞬时完成 |
| 17 | `merchant_wechat_qrcode_20261022` | mms_merchant 增加 wechat_qrcode VARCHAR(512) NULL；原 wechat 中的 URL 搬入新列并清空 URL 文本，保留已有二维码及文本微信号 | InnoDB 表与同名列形状 guard；按 information_schema 判列后 INSTANT ADD；复制只命中二维码空值，清理只命中已有二维码的 URL。主预检只核结构，列存在时标 DATA_PREVIEW，须执行附录 B4 的数据回读 | 中：INSTANT 加列需短暂元数据锁；两条 UPDATE 扫描并锁定待搬迁行。超长 URL 或历史异常会让末尾断言失败，必须停下核对，禁止放松断言 |
| 18 | `o14_city_code_backfill_log_20261022` | 新表 o14_city_code_backfill_log（O-14 回填来源标记） | CREATE TABLE IF NOT EXISTS + 关键列计数断言 | 无：只建新表，空表，瞬时完成 |
| 19 | `o14_cms_activity_city_code_20261022` | cms_activity 加可空列 city_code VARCHAR(32)（ALGORITHM=INSTANT）+ 索引 idx_cms_activity_city_code（INPLACE, LOCK=NONE） | information_schema 判列/索引；同名异形列 guard 让执行器停 + 回读断言 | 中：INSTANT 加列只需短暂元数据锁；建索引在线进行，耗时 ∝ 行数 |
| 20 | `o14_cms_topic_city_code_20261022` | cms_topic 加可空列 city_code VARCHAR(32)（INSTANT）+ 索引 idx_cms_topic_city_code（INPLACE, LOCK=NONE） | 同上 | 中：同上 |
| 21 | `o14_content_city_code_backfill_20261022` | 按 club.city 白名单回填 cms_topic / cms_activity.city_code，并在 o14_city_code_backfill_log 记来源；只改 city_code IS NULL 或本回填写过且未被改动的行 | 逻辑幂等（同规则重跑 city_code 零变更）；无 information_schema 守卫；有回读断言（只允许 6 位城市码与两种来源）。重跑会删除并重插本回填自己的 log 行（write_time 刷新） | 中/高：对 cms_topic、cms_activity 全表扫描 + UPDATE JOIN，行锁覆盖被写行；放低峰执行。执行前必须跑 _precheck_o14_content_city_code_backfill_20261022.sql 预览并由 owner 抽检 |
| 22 | `o14_content_search_document_20261022` | 新表 content_search_document（O-14 统一搜索投影） | CREATE TABLE IF NOT EXISTS + 关键列计数断言 | 无：只建新表，空表，瞬时完成 |
| 23 | `orient_geo_ar_team_20261022` | 新表 orient_node_geo / orient_task_alternative / orient_task_attempt / orient_task_completion / orient_safety_rule + sys_config orient.* 5 键（默认关） | CREATE TABLE IF NOT EXISTS + INSERT … WHERE NOT EXISTS + 回读断言（只数表与键，不核列形状 → 由预检指纹兜底） | 无：只建新表，空表，瞬时完成 |
| 24 | `o14_reco_search_jobs_20261022` | sys_job 登记 recommendationScoreDailyTask.run() 与 contentSearchProjectionTask.refresh()，status='1'（暂停） | INSERT … WHERE NOT EXISTS(invoke_target) + 回读断言恰好 1 行；已存在的登记不改 | 无：sys_job 小表。暂停任务不会被调度，旧 jar 无对应 bean 也不会触发 |
| 25 | `play_arrive_guard_log_20261022` | 新表 play_arrive_guard_log（到达守卫留痕；mode=off 时不读写） | CREATE TABLE IF NOT EXISTS + 列串指纹 guard + 索引断言 | 无：只建新表，空表，瞬时完成 |
| 26 | `pms_sku_booking_20261022` | 新表 pms_sku_booking_rule / pms_sku_capacity_slot（F4 SKU 预约容量） | CREATE TABLE IF NOT EXISTS + 列串指纹 guard + 唯一键断言 | 无：只建新表，空表，瞬时完成 |
| 27 | `orient_scoring_20261022` | 新表 orient_score_ledger / settlement / appeal / orient_result_dispute + sys_config orient.scoring.* 5 键（默认不发奖） | CREATE TABLE IF NOT EXISTS + INSERT … WHERE NOT EXISTS + 回读断言（只数表与键 → 由预检指纹兜底） | 无：只建新表，空表，瞬时完成 |
| 28 | `orient_session_lifecycle_20261022` | topic_route_session 加可空列 phase VARCHAR(16) AFTER status；status 列只改注释（类型/默认不变）；新表 orient_session_clock | information_schema 判列/注释 → 动态 PREPARE；CREATE TABLE IF NOT EXISTS；回读断言 | 低/中：ADD COLUMN 走 INSTANT；MODIFY 只改注释时为元数据变更。若生产 status 列类型/默认与 TINYINT NOT NULL DEFAULT 0 不一致，MODIFY 会变成整表重建——预检会判 CONFLICT，先停 |
| 29 | `member_visited_poi_poi_key_backfill_20261022` | 回填 member_visited_poi.poi_key（poi:{地标id} 或 pt:{5位坐标}；判为重复的行保持 NULL）；不删行、不改 geo_key | 只处理 poi_key IS NULL 的行；重跑时剩下的都是重复行，不再写；回读断言 | 中/高：相关子查询 ∝ 每会员点数²，大表需放低峰；只写 poi_key。必须在打开 roam.visited_poi.stable_identity 之前执行 |
| 30 | `roam_share_version_20261022` | roam_share_snapshot 加列 version INT NOT NULL DEFAULT 1（旧行即为 1） | information_schema 判列 → 动态 PREPARE + 回读断言（类型/非空/默认） | 低：INT NOT NULL DEFAULT 加在末尾，8.0.45 走 INSTANT，只需短暂元数据锁 |
| 31 | `20261022_theme_state_key_attribute_def` | theme_state_key 加 9 个可空列 min_value/max_value/display_type/color/icon/sort_order/visible/persist_scope/rules_json（ALGORITHM=INSTANT） | DDL 前断言（同名异形列 fail-closed）+ 逐列 information_schema 判断 → 动态 PREPARE + DDL 后断言 | 低：9 次 INSTANT 加列，每次短暂元数据锁 |
| 32 | `20261022_theme_character` | 新表 theme_character（F2 跨局角色卡） | CREATE TABLE IF NOT EXISTS + 列计数与唯一键断言 | 无：只建新表，空表，瞬时完成 |
| 33 | `verification_review_evidence_20261022` | 新表 verification_review_evidence（F4 复核补材料） | CREATE TABLE IF NOT EXISTS + 列串指纹 guard + 唯一键断言 | 无：只建新表，空表，瞬时完成 |
| 34 | `20261013_admin_role_page_grants` | 仅对「一个页面授权都没有」的 cy_operator/cy_auditor/cy_support/cy_finance 按 20260719 矩阵补授权并补祖先菜单；已有页面授权的角色不动（已有页面时 no-op；生产状态未知） | 全部 INSERT … NOT EXISTS；临时表先 DROP TEMPORARY IF EXISTS；无 information_schema 守卫（纯 DML）；有回读断言 | 低：sys_role_menu 小表 |
| 35 | `20261016_admin_role_payee_separation_forward` | D-PAYEE：撤销 cy_operator→business:clubpayee:verify 与 cy_finance→business:clubpayee:payout，补齐 cy_operator→payout 与 cy_finance→verify；全新安装（sys_user 为空且角色无页面）才走批量初始化分支 | -- runner: requires=transaction，单事务；guard 临时表主键冲突（角色/菜单不全、断言失败）让整个事务回滚；INSERT 带 NOT EXISTS，DELETE 重跑删 0 行 | 低：sys_role_menu 小表；SELECT … FOR UPDATE 锁 4 个角色行与 4 个菜单行，事务很短 |

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
| 9 | `audit_problem_20261022` | NEEDED | APPLIED | EXECUTE | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('audit_problem'); -- 期望 1` |
| 10 | `club_welcome_note_20261022` | NEEDED | APPLIED | PRECHECK_SKIP_APPLIED | `SELECT column_name, column_type, is_nullable, column_default, extra, generation_expression FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'club' AND column_name = 'welcome_note'; -- varchar(600)、YES、NULL、普通列` |
| 11 | `doc2_f3_bundle_ledger_20261022` | NEEDED | APPLIED | EXECUTE | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('explore_bundle_order', 'explore_bundle_grant', 'explore_bundle_unit', 'explore_bundle_reservation', 'explore_bundle_waiver', 'explore_bundle_exit_case'); -- 期望 6` |
| 12 | `member_profile_version_20261022` | NEEDED | APPLIED | EXECUTE | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('ums_member_profile_version'); -- 期望 1` |
| 13 | `member_visited_poi_poi_key_20261022` | NEEDED | APPLIED | EXECUTE | `SELECT column_name, column_type, is_nullable, column_default FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'member_visited_poi' AND column_name IN ('poi_key');`<br>`SELECT index_name, non_unique, seq_in_index, column_name FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'member_visited_poi' AND index_name = 'uk_member_poi_key';` |
| 14 | `merchant_category_ids_20261022` | NEEDED | DATA_PREVIEW | EXECUTE | `SELECT column_name, column_type, is_nullable, column_default FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'mms_merchant' AND column_name = 'category_ids'; -- varchar(255)、YES、NULL`<br>`SELECT COUNT(*) AS remaining_backfill FROM mms_merchant WHERE category_id > 0 AND (category_ids IS NULL OR category_ids = ''); -- 列存在后执行，期望 0` |
| 15 | `merchant_desk_receipt_20261022` | NEEDED | APPLIED | EXECUTE | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('merchant_desk_receipt'); -- 期望 1` |
| 16 | `merchant_redemption_anchor_20261022` | NEEDED | APPLIED | EXECUTE | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('merchant_redemption_anchor'); -- 期望 1` |
| 17 | `merchant_wechat_qrcode_20261022` | NEEDED | DATA_PREVIEW | EXECUTE | `SELECT column_name, column_type, is_nullable, column_default FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'mms_merchant' AND column_name = 'wechat_qrcode'; -- varchar(512)、YES、NULL`<br>`SELECT COUNT(*) AS remaining_url FROM mms_merchant WHERE wechat LIKE 'http://%' OR wechat LIKE 'https://%'; -- 期望 0` |
| 18 | `o14_city_code_backfill_log_20261022` | NEEDED | APPLIED | EXECUTE | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('o14_city_code_backfill_log'); -- 期望 1` |
| 19 | `o14_cms_activity_city_code_20261022` | NEEDED | APPLIED | EXECUTE | `SELECT column_name, column_type, is_nullable, column_default FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cms_activity' AND column_name IN ('city_code');`<br>`SELECT index_name, non_unique, seq_in_index, column_name FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'cms_activity' AND index_name = 'idx_cms_activity_city_code';` |
| 20 | `o14_cms_topic_city_code_20261022` | NEEDED | APPLIED | EXECUTE | `SELECT column_name, column_type, is_nullable, column_default FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cms_topic' AND column_name IN ('city_code');`<br>`SELECT index_name, non_unique, seq_in_index, column_name FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'cms_topic' AND index_name = 'idx_cms_topic_city_code';` |
| 21 | `o14_content_city_code_backfill_20261022` | NEEDED | DATA_PREVIEW | EXECUTE | `SELECT COUNT(*) FROM cms_topic WHERE city_code IS NOT NULL AND city_code NOT REGEXP '^[0-9]{6}$'; -- 期望 0`<br>`SELECT target_table, source, COUNT(*) FROM o14_city_code_backfill_log GROUP BY target_table, source;` |
| 22 | `o14_content_search_document_20261022` | NEEDED | APPLIED | EXECUTE | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('content_search_document'); -- 期望 1` |
| 23 | `orient_geo_ar_team_20261022` | NEEDED | APPLIED | EXECUTE | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('orient_node_geo', 'orient_task_alternative', 'orient_task_attempt', 'orient_task_completion', 'orient_safety_rule'); -- 期望 5`<br>`SELECT config_key, config_value, COUNT(*) OVER (PARTITION BY config_key) n FROM sys_config WHERE config_key IN ('orient.geo.normalize.enabled', 'orient.alt_task.enabled', 'orient.team.board.enabled', 'orient.team.completion_ratio', 'orient.safety.enabled'); -- 每键 n=1` |
| 24 | `o14_reco_search_jobs_20261022` | NEEDED | APPLIED | EXECUTE | `SELECT invoke_target, status, COUNT(*) FROM sys_job WHERE invoke_target IN ('recommendationScoreDailyTask.run()', 'contentSearchProjectionTask.refresh()') GROUP BY invoke_target, status; -- 每个 1 行且 status='1'` |
| 25 | `play_arrive_guard_log_20261022` | NEEDED | APPLIED | EXECUTE | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('play_arrive_guard_log'); -- 期望 1` |
| 26 | `pms_sku_booking_20261022` | NEEDED | APPLIED | EXECUTE | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('pms_sku_booking_rule', 'pms_sku_capacity_slot'); -- 期望 2` |
| 27 | `orient_scoring_20261022` | NEEDED | APPLIED | EXECUTE | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('orient_score_ledger', 'orient_score_settlement', 'orient_score_appeal', 'orient_result_dispute'); -- 期望 4`<br>`SELECT config_key, config_value FROM sys_config WHERE config_key LIKE 'orient.scoring.%'; -- 5 行` |
| 28 | `orient_session_lifecycle_20261022` | NEEDED | APPLIED | EXECUTE | `SELECT column_name, column_type, is_nullable, column_default FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'topic_route_session' AND column_name IN ('phase', 'status');`<br>`SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('orient_session_clock'); -- 期望 1` |
| 29 | `member_visited_poi_poi_key_backfill_20261022` | NEEDED | DATA_PREVIEW | EXECUTE | `SELECT COUNT(*) FROM member_visited_poi WHERE poi_key IS NULL; -- 只剩重复行`<br>`SELECT member_id, poi_key, COUNT(*) c FROM member_visited_poi WHERE poi_key IS NOT NULL GROUP BY member_id, poi_key HAVING c > 1; -- 期望 0 行` |
| 30 | `roam_share_version_20261022` | NEEDED | APPLIED | EXECUTE | `SELECT column_name, column_type, is_nullable, column_default FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'roam_share_snapshot' AND column_name IN ('version');` |
| 31 | `20261022_theme_state_key_attribute_def` | NEEDED | APPLIED | EXECUTE | `SELECT column_name, column_type, is_nullable, column_default FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'theme_state_key' AND column_name IN ('min_value', 'max_value', 'display_type', 'color', 'icon', 'sort_order', 'visible', 'persist_scope', 'rules_json');` |
| 32 | `20261022_theme_character` | NEEDED | APPLIED | EXECUTE | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('theme_character'); -- 期望 1` |
| 33 | `verification_review_evidence_20261022` | NEEDED | APPLIED | EXECUTE | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('verification_review_evidence'); -- 期望 1` |
| 34 | `20261013_admin_role_page_grants` | APPLIED | APPLIED | EXECUTE | `SELECT r.role_key, COUNT(*) FROM sys_role r JOIN sys_role_menu rm ON rm.role_id = r.role_id JOIN sys_menu m ON m.menu_id = rm.menu_id WHERE r.del_flag = '0' AND m.menu_type = 'C' AND r.role_key LIKE 'cy\\_%' GROUP BY r.role_key; -- 4 个角色均 > 0` |
| 35 | `20261016_admin_role_payee_separation_forward` | NEEDED | APPLIED | EXECUTE | `SELECT r.role_key, m.perms FROM sys_role_menu rm JOIN sys_role r ON r.role_id = rm.role_id JOIN sys_menu m ON m.menu_id = rm.menu_id WHERE r.del_flag = '0' AND r.role_key IN ('cy_operator', 'cy_finance') AND m.perms IN ('business:clubpayee:verify', 'business:clubpayee:payout'); -- 期望恰好 2 行：cy_operator/payout、cy_finance/verify` |
<!-- END GENERATED RELEASE INVENTORY -->

## 2 阶段与只读预检的处置

- `PRE_DEPLOY_REQUIRED`：新 mapper 直接读写对象，必须先于新 jar。
- `PRE_DEPLOY`：可能存在容错，但发布顺序统一放在新 jar 之前。
- `OWNER_GATED_DATA`：先只读预览并由 owner 抽检；可安排低峰执行，必须在打开相关开关前完成。
- `APPLIED`：对象/目标状态符合预检契约；不能据此推断迁移执行历史。**welcome_note 的 APPLIED 必须跳过原 SQL**；其他项仍按各自守卫处置。
- `NEEDED`：需执行；`20261013_admin_role_page_grants` 例外，见第 4 节。
- `PARTIAL`：只有元数据说明可续跑的迁移才允许恢复；先保存失败输出、预检与 owner 处置记录。
- `CONFLICT` / `BLOCKED_PREREQ`：停止，交 owner 处理；不跳过错误继续执行。
- `DATA_PREVIEW`：前置结构可用，数据仍须核对。两项历史回填及两项商家数据迁移使用这个状态；它不证明已完成。

预检全文只包含 SELECT/WITH，不含主机或凭据。主分类按台账拓扑序逐项覆盖发布集；其余段保留服务器身份、授权明细、开关/任务、依赖、体量和长事务。附录 B 的查询仅在相关列存在后手工执行，避免在旧 schema 上解析不存在的列。

商家 `category_ids` / `wechat_qrcode` 在主分类只检查列形状；不能只凭“列已存在”宣布回填完成。必须执行各项 `verifySelectOnly` 与附录 B3/B4 的数据回读。原 wechat 中超长 URL 等异常仍会使真实迁移末尾断言失败，必须保留失败并停下，不能放宽断言。

所有 ALTER 都需短暂元数据锁。索引创建、重复扫描和 UPDATE 受表体量影响；先看预检的体量与长事务结果，在维护窗口/低峰操作。唯一态度键发现任何历史重复时先由 owner 导出并核对具体行，**不自动删除、不自动合并**。

## 3 welcome_note：单次执行与预期拒绝

历史 `migration_club_welcome_note_20261022.sql` 使用普通 ADD COLUMN。开头的 SELECT 不会阻止重复 ALTER；直接重跑应报 **MySQL 1060 / Duplicate column name 'welcome_note'**。原 SQL 字节保持不变。

发布处置只有两种：预检为 NEEDED 时执行一次；APPLIED 时跳过。PARTIAL、CONFLICT、缺前置或未知状态都停止，禁止用捕获异常的方式伪装成功。

演练在首次应用后实际直接执行原文件，要求恰好在 ALTER TABLE club 上出现该 1060，且前后全库 schema/数据指纹完全一致。任何其他错误、意外成功或持久变更均判失败。随后按声明的 `PRECHECK_SKIP_APPLIED` 策略重入：新预检为 APPLIED 才允许跳过，并分别记录“实际重跑”和“已应用跳过”。列型漂移与缺列负控还必须证明该策略拒绝跳过。**这些是验收要求，未运行时不得称为已有证据。**

## 4 保留 D-PAYEE 的失败闸

`20261016_admin_role_payee_separation_forward` 仍排在 `20261013_admin_role_page_grants` 之后，声明 `runner: requires=transaction`，必须使用事务执行。四个后台角色和四个相关按钮菜单各须恰好一条有效记录；缺失、重复或回读不符使事务回滚。迁移只定点修正 operator/finance 的 verify/payout 授权；本任务不改 SQL、RBAC 或业务规则。

`20261013` 是相对参考基线已存在的历史字节差异，本包只如实记录当前 SHA-256 与 baseSha256，不编辑历史 SQL。它在“有角色没有页面授权”的合成库上存在已知风险：可能在断言处停止，前面的非事务 INSERT 已提交。因此 **预检为 APPLIED（角色均已有页面授权）时才按 no-op 执行；NEEDED 时禁止执行，交 owner**。演练保留独立 fresh-roles 风险探针，要求仍复现失败及已提交的部分授权，不能把 pending 或失败写成成功。

如有 `cy_migration_history`，由 owner 使用预检附录 A 对账；没有历史表时不能推断曾执行过哪个版本。生产当前授权、账号和迁移执行状态均未知。

## 5 隔离演练与验证范围

现有 GitHub 托管 workflow 使用钉 digest 的 MySQL 8.0.45 一次性容器，不连接 VPS 或生产：

1. 从参考基线与当前源提交计算发布集，逐项核对 path/hash/order/dependsOn；数量不写死。
2. 从仓库基线 schema/seed 和非发布集迁移重建合成库，写入既有角色、内容、地理与新商家/态度合成行。
3. 逐项检查 `expectedAtBaseline`，严格按序执行全部发布迁移，任何错误或断言失败立即停止。
4. 检查 `expectedAfterApply`；额外回读商家数据，证明主行业回填、URL 搬迁、文本微信号保留。
5. 保留 welcome_note 原文件直接重跑的预期 1060 负控；其余文件实际重跑。全库 schema、索引、约束及 CHECKSUM 必须零变更。
6. 保留既有坏表/坏列/重复键/授权漂移负控，新增 welcome_note 坏列/缺列、商家坏列及态度键缺失/重复行负控。
7. 保留独立 fresh-roles 风险探针和容器清理。

唯一已声明的数据差异仍为 `o14_city_code_backfill_log.write_time` 刷新：演练排除这一列后逐行核对其余数据，同时比较其他表/列；不扩大白名单。

验证层级必须分开：源码/静态测试不等于 hosted MySQL；hosted scratch MySQL 不等于 VPS；二者均不证明生产已应用或真机/真 HTTP 行为。

### 当前与历史证据

本次当前覆盖源、候选 SHA、CI URL 和实际结果记录于 [task2-real-storage-migration.md](task2-real-storage-migration.md)。没有成功回执前，当前静态、hosted MySQL、VPS、生产分别保持 NOT_RUN / PENDING / NOT_RUN / UNKNOWN。

历史 [run 39927883146](https://github.com/liboyang42-cpu/hengyin/actions/runs/39927883146) 是 PR #551 的旧 30 项迁移包结果，仅作历史参考，**不能外推为本包通过**。基线两类夹具失败见 [run 40058716940](https://github.com/liboyang42-cpu/hengyin/actions/runs/40058716940)。

## 6 备份、停止与回滚

由 owner 在执行任何生产写入前完成只读预检、维护窗口与带指纹的可恢复备份，按既有 `ops/db-backup-restore.md` 与 `release-steps.md` 留存回执。没有备份不执行；任一步失败立即停止，不继续后续文件。

DDL 会隐式提交，失败前的语句可能已生效。保存失败输出并重新只读回读；仅在具体迁移的幂等契约允许时从同一步恢复，welcome_note 必须按第 3 节处理。D-PAYEE 使用事务；两个历史回填及商家回填不是整文件事务，异常数据必须先由 owner 处置。

JAR 回滚不会撤销 DDL。商家迁移已经搬迁的数据不会因 JAR 回滚自动还原，须先评估旧版本对新数据语义的兼容性。不要把旧全库备份直接覆盖到已有新业务数据的库；如需核对或取数，只按既有隔离恢复流程处理。撤销结构、回填或授权须另行审阅前向迁移，禁止直接执行注释里的破坏性回滚语句。

## 7 合并新迁移后可复现刷新

只使用公开仓库历史。先将新增 SQL 与依赖台账提交到目标分支，再运行：

```sh
python3 ci/release_migration_rehearsal.py --inventory
python3 ci/release_migration_rehearsal.py --refresh-pack
python3 -m unittest -v ci.test_release_migration_rehearsal
git diff --check
```

`--refresh-pack` 不连库，只从**已提交 HEAD**计算差异。它保留人工审阅的阶段、风险、回读和预期状态，重算校验和/依赖/拓扑步序、预检顺序、源 SHA/tree/SQL 子树/台账指纹及本文件生成区。新增或移除迁移的元数据/预检未审阅齐全时明确拒绝刷新，不自动发明验收合同。随后必须重新运行现有托管迁移演练。

已合入源提交的 points config 迁移已在本包。尚未合入本覆盖源的 PR（包括后续支付迁移）不属于本包；协调者完成整合后再统一刷新。任何新包都不改变生产 UNKNOWN 状态。
