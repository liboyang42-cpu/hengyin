# 发布迁移包（TASK MIG，2026-10-23）

> **本文件只准备与演练，不执行。** 本 lane 没有连接任何生产主机或生产库，没有执行任何生产 SQL，也没有部署。
> owner 已于 2026-10-23 批准「allow migration」（= 本次发布的全部迁移），**由人在生产上执行**；本文件给出顺序、校验和、只读预检、演练证据与回滚规则。
> 主机地址、账号、配置路径都不写进本公开仓。

配套文件：

| 文件 | 用途 |
|---|---|
| `release-migration-pack.json` | 本文件第 1 节的机器可读版（逐项 path / sha256 / 依赖 / 阶段 / 守卫 / 风险 / 回读 SQL / 预检期望状态） |
| `release-migration-precheck.sql` | 第 3 节：生产上**先跑**的单一只读预检脚本（全部是 SELECT） |
| `ci/release_migration_rehearsal.py` | 第 4 节：一次性 MySQL 8.0 演练（只连回环、只操作 `cy_e2e_*` scratch 库） |
| `ci/test_release_migration_rehearsal.py` | 静态契约：pack 与 HEAD 一致、sha256 一致、预检只含 SELECT 且逐项覆盖 |
| `.github/workflows/release-migration-rehearsal.yml` | 在 GitHub 托管 runner 上跑上面两项（`pull_request` 改到这些文件时 + `workflow_dispatch`） |

## 0 口径

| 项 | 值 |
|---|---|
| 生产参考基线 | **`19408d49172c1d8fd16ec012f4498ff8ea584a37`**（Merge #452）。出处：`migration-ledger.md` §0。基线**已在 master 上记录**，不需要 owner 另给 |
| 生产上哪些已执行 | **UNKNOWN**。仓库从未声明任何迁移已在生产执行；以第 3 节预检结果为准 |
| 发布目标 | master `f099c5be9eb3ef22214ce81e80303eb851f417dc`（Merge #541）。pack 钉在这个提交上；master 再进新迁移时，演练会报「pack 过期」 |
| 发布集 | `python3 ci/release_manifest.py diff --base 19408d49 --head f099c5be` 的结果：**30 个**（29 个新增 + 1 个修改，0 个删除）。`migration-ledger.md` 写的是 6 个，那是 batch 2 时的快照；batch 3–5 又带入 24 个 |
| 执行序 | 与 runner 相同：`ci/migration-dependencies.json` 显式依赖 + 路径字节序拓扑排序（`ci/migration_ledger.py`）。「执行序」列是该迁移在 HEAD 全量 515 个迁移中的序号；**生产上只按「步」列从 1 到 30 依次执行** |
| 执行工具 | chengyin-db-migrate 流程 / `ci/run_migration.py --sql`（逐个文件；`20261016` 声明了 `runner: requires=transaction`，必须加 `--transaction`）。runner 在文件内第一条失败语句处退出 |

阶段含义：

- **先于 jar（硬）**：新 jar 直接读写该表/列（如 `ThemeStateKeyMapper.xml` 的 select 列表含新列、`RoamShareSnapshotMapper.xml` 写 `version`），不落库新 jar 会报错。
- **先于 jar**：新 jar 在开关关闭或表缺失时可容忍，但发布文档（`integration-batch2/4/5.md`）统一要求先于新 jar 落库，照办。
- **数据回填（owner 闸）**：先跑配套只读预览并由 owner 抽检。不阻塞 jar 部署，但必须在打开对应开关之前完成（`roam.visited_poi.stable_identity`、O-14 统一搜索）。

本发布集**没有** post-deploy 迁移：`ci/migration_ledger.py` 的 `POST_DEPLOY_MIGRATIONS`（3 个 `20261012_*` 任务登记）都在基线之前。`o14_reco_search_jobs_20261022` 登记的两个 `sys_job` 是暂停状态（`status='1'`），旧 jar 没有对应 bean 也不会被调度，所以可以先于 jar。

## 1 有序清单

### 1.1 文件、校验和、依赖、阶段

| 步 | 执行序 | 文件（`chengyinhub-admin/sql/`） | 变更 | sha256 | 依赖边（→ 本迁移） | 阶段 |
|---|---|---|---|---|---|---|
| 1 | 45 | `migration_20261018_infomation_change_audit.sql` | 新增 | `0e023798a882a7aec4e5a9de9815d7dcf39175600fc9d3c6d6c640a4dc304695` | — | 先于 jar |
| 2 | 46 | `migration_20261022_audit_activity_switch_seed.sql` | 新增 | `4a9d4e63cde4afc7b5d6f4a17f46d0489b7508bafead3f3e8363448c3d4f59c7` | — | 先于 jar |
| 3 | 47 | `migration_20261022_f7_minor_guardian.sql` | 新增 | `c375ee6846c1ed0702b224ab12ad5301e6f6056b236ee30ca2ca052fb3b66c1e` | — | 先于 jar |
| 4 | 48 | `migration_20261022_f7_user_invoice.sql` | 新增 | `1ee515288464e4998ee4ea1341add368dd3e0db611908adccf7d950a7adb8c98` | — | 先于 jar |
| 5 | 49 | `migration_20261022_location_guard_switch_seed.sql` | 新增 | `8eac6d4ec7c88e223611e9c2a9c41fc7fa814941361c36e4e8f856ab4b6bd377` | — | 先于 jar |
| 6 | 50 | `migration_20261022_merchant_desk_switch_seed.sql` | 新增 | `99ccf252582db7d085d9061c18c91afec30783ee829e585038fc479b95da5b8f` | — | 先于 jar |
| 7 | 66 | `migration_audit_problem_20261022.sql` | 新增 | `02353f0296b49751e5a035a129a03fccf9173cddba2194e7489b32449434a080` | — | 先于 jar |
| 8 | 137 | `migration_doc2_f3_bundle_ledger_20261022.sql` | 新增 | `f94e363d84923f4b6e97bce4ec49382364ec2568e7efe6cdcc83c10ed4b8f419` | — | 先于 jar |
| 9 | 182 | `migration_member_profile_version_20261022.sql` | 新增 | `8be4a0d82578fe1d74579f5abbf859307f0b827655d670ff0e398cf026d018ba` | — | **先于 jar（硬）** |
| 10 | 184 | `migration_member_visited_poi_poi_key_20261022.sql` | 新增 | `ddb6c4652f8138a42a2ec380af3981f6674e574834ade341736e12eacb31c07a` | `member_visited_poi` | 先于 jar |
| 11 | 196 | `migration_merchant_desk_receipt_20261022.sql` | 新增 | `83746d2cb7e5de51365f97782d9e8692fb58c6bdf3196e854475d102d2fc2278` | — | 先于 jar |
| 12 | 207 | `migration_merchant_redemption_anchor_20261022.sql` | 新增 | `907c01f72fb7f77ceed83a0aa8eb51de4cbd55dc740218a0e1a65ca33c2da6e0` | — | 先于 jar |
| 13 | 232 | `migration_o14_city_code_backfill_log_20261022.sql` | 新增 | `cc5f6e4dd6e8880f42012cbb7069a2ffaf79b8ac5ac75565e09b2c159922a1ed` | — | 先于 jar |
| 14 | 233 | `migration_o14_cms_activity_city_code_20261022.sql` | 新增 | `6f8aa378506994897748617ae9209445cca32c2eefe613549194f6252351ee95` | — | 先于 jar |
| 15 | 234 | `migration_o14_cms_topic_city_code_20261022.sql` | 新增 | `fcda5dcf5bf4172238564758d008b4b64d6eab6a8861335dbb5d063e2dd93ff1` | — | 先于 jar |
| 16 | 235 | `migration_o14_content_city_code_backfill_20261022.sql` | 新增 | `abfb4c27b3219ab3906c29feb9c3a9c920f498913f26f06bb177740a271c085a` | `o14_city_code_backfill_log_20261022`、`o14_cms_activity_city_code_20261022`、`o14_cms_topic_city_code_20261022` | 数据回填（owner 闸） |
| 17 | 236 | `migration_o14_content_search_document_20261022.sql` | 新增 | `1d153c6fbbe8e7595cb169e906c64df975d1f85b88c486d1a0db6e4e8ebe3d5f` | — | 先于 jar |
| 18 | 252 | `migration_orient_geo_ar_team_20261022.sql` | 新增 | `d297c399d1c12d568d551ebe6dc17d6cc87bc95a6bae6d5667ef1e991d01f1e3` | — | 先于 jar |
| 19 | 283 | `migration_o14_reco_search_jobs_20261022.sql` | 新增 | `046921a70dbbf4f8ea32aa582d651e0a0d9c798cfd9c79a2df3b027972ccc370` | `o14_content_search_document_20261022`、`p3_platform_foundation` | 先于 jar |
| 20 | 297 | `migration_play_arrive_guard_log_20261022.sql` | 新增 | `52b937c5468f03006aef2e6b15c1beeb9a625c539558ca805c57fc4ff453b1fb` | — | 先于 jar |
| 21 | 306 | `migration_pms_sku_booking_20261022.sql` | 新增 | `0057c4368f7ade02120c109c791950e75b7f4c5d0dd6b42b8baf1fcb8f1fd8ac` | — | 先于 jar |
| 22 | 328 | `migration_orient_scoring_20261022.sql` | 新增 | `17893c96611ad13875178c9b6ebf4fcb332e7a11fad5f6fb8a7d1f25fd9e055a` | `20260823_topic_route_graph` | 先于 jar |
| 23 | 329 | `migration_orient_session_lifecycle_20261022.sql` | 新增 | `ffc9001685f87179b58fa7bb9c104bc27c608664b3288e79234053699ffd2735` | `20260823_topic_route_graph` | **先于 jar（硬）** |
| 24 | 371 | `migration_member_visited_poi_poi_key_backfill_20261022.sql` | 新增 | `2980391167e7bb4113a9523158e7a7fb5577eb8875d534f27db7f612e240af27` | `member_visited_poi_poi_key_20261022`、`roam` | 数据回填（owner 闸） |
| 25 | 382 | `migration_roam_share_version_20261022.sql` | 新增 | `563ddd80ae4e23ce96d02183380d9175209e68702aaf06f1061fd9a6688170b6` | `20260917_roam_share_snapshot` | **先于 jar（硬）** |
| 26 | 420 | `migration_20261022_theme_state_key_attribute_def.sql` | 新增 | `8b69583c24795742c0244610bb8dab6ef9abec64577a0a13666c5929146e0b25` | `theme_state_key_20260916` | **先于 jar（硬）** |
| 27 | 421 | `migration_20261022_theme_character.sql` | 新增 | `a0a8637bdf95674f4c11dabeb3852ab53a923789507a59e0b5cd64587f1adc71` | `20261022_theme_state_key_attribute_def`、`theme_state_key_20260916` | **先于 jar（硬）** |
| 28 | 474 | `migration_verification_review_evidence_20261022.sql` | 新增 | `9b13f58da4455c4af67429bbe604086cf28cd451f19ad71f0bf09b489d42d889` | — | 先于 jar |
| 29 | 495 | `migration_20261013_admin_role_page_grants.sql` | **修改** | `1f147a2136e999b6fd87a7e0a865d8fde5b0c332170d561b28e415b9fccaf56e` | `20261012_media_review_menu`、`20261012_missing_business_menu`、`role2_analytics_perm_20260918`、`web_admin_roles_20260719` | 先于 jar |
| 30 | 496 | `migration_20261016_admin_role_payee_separation_forward.sql` | 新增 | `4eeef3936f72f05e69b3440aa818c2c39d53e046aaabd82cfdef10c868cca34e` | `20260816_tandianri_batch3_funds_club_compensation`、`20260816_tandianri_batch3_funds_club_payee`、`20260817_tandianri_batch3_funds_club_hours_correction`、`20261013_admin_role_page_grants` | 先于 jar |

依赖边只列 `ci/migration-dependencies.json` 里的显式依赖。基线之前的前置（`member_visited_poi`、`roam`、`20260917_roam_share_snapshot`、`20260823_topic_route_graph`、`theme_state_key_20260916`、`p3_platform_foundation`、三个 tandianri batch3 资金迁移、`web_admin_roles_20260719`、两个 `20261012_*` 菜单迁移、`role2_analytics_perm_20260918`）在生产上是否已执行同样 UNKNOWN，由预检第 [5/7] 段逐个确认。

### 1.2 改了什么、幂等守卫、锁与体量风险

| 步 | 迁移 | 改了什么 | 幂等守卫 | 锁 / 体量风险 |
|---|---|---|---|---|
| 1 | `20261018_infomation_change_audit` | 新表 cms_infomation_change_audit（玩法说明追加审计） | CREATE TABLE IF NOT EXISTS + information_schema 全形状断言（列/索引/约束/无触发器） | 无：只建新表，空表，瞬时完成 |
| 2 | `20261022_audit_activity_switch_seed` | sys_config 落种 audit.activity.pre_review_all='false'、audit.activity.high_risk_categories=''（= 代码现行默认） | INSERT … WHERE NOT EXISTS(config_key) + 回读断言每键恰好 1 行 | 无：sys_config 小表，按自然键 INSERT … WHERE NOT EXISTS |
| 3 | `20261022_f7_minor_guardian` | 新表 minor_guardian_authorization（F7 未成年监护授权；feature.flag.minorMode 缺省关） | CREATE TABLE IF NOT EXISTS + information_schema 列串与两个唯一键断言 | 无：只建新表，空表，瞬时完成 |
| 4 | `20261022_f7_user_invoice` | 新表 user_invoice_title / user_invoice_application / user_invoice_application_order（F7 发票；feature.flag.userInvoice 缺省关） | CREATE TABLE IF NOT EXISTS + information_schema 三表列串与唯一键断言 | 无：只建新表，空表，瞬时完成 |
| 5 | `20261022_location_guard_switch_seed` | sys_config 落种 play.arrive.location_guard.mode='off'、play.arrive.require_accuracy='false'、roam.visited_poi.stable_identity='false' | INSERT … WHERE NOT EXISTS(config_key) + 回读断言 | 无：sys_config 小表，按自然键 INSERT … WHERE NOT EXISTS |
| 6 | `20261022_merchant_desk_switch_seed` | sys_config 落种 merchant.desk.f4.enabled='false' | INSERT … WHERE NOT EXISTS(config_key) + 回读断言 | 无：sys_config 小表，按自然键 INSERT … WHERE NOT EXISTS |
| 7 | `audit_problem_20261022` | 新表 audit_problem（审核逐项驳回原因）；必须先于打开 chengyin.audit.item-problems.enabled | 仅 CREATE TABLE IF NOT EXISTS，无 information_schema 断言（同名残表会被静默接受 → 由预检指纹判 CONFLICT） | 无：只建新表，空表，瞬时完成 |
| 8 | `doc2_f3_bundle_ledger_20261022` | 新表 explore_bundle_order/grant/unit/reservation/waiver/exit_case（F3 套票台账；ledger-enabled 默认关） | CREATE TABLE IF NOT EXISTS + information_schema 列与唯一键 SHA-256 指纹；不符时 guard 临时表主键冲突让执行器停 | 无：只建新表，空表，瞬时完成 |
| 9 | `member_profile_version_20261022` | 新表 ums_member_profile_version（资料版本回执；新 jar 表缺失只 WARN） | 仅 CREATE TABLE IF NOT EXISTS，无 information_schema 断言（台账已标风险 → 由预检指纹判 CONFLICT） | 无：只建新表，空表，瞬时完成 |
| 10 | `member_visited_poi_poi_key_20261022` | member_visited_poi 加可空列 poi_key VARCHAR(32) AFTER geo_key + 唯一键 uk_member_poi_key(member_id, poi_key) | information_schema 判列/索引是否存在 → 动态 PREPARE（存在则 SELECT 1）+ 回读断言 | 中：ADD COLUMN 在 8.0.45 走 INSTANT（仅元数据锁）；ADD UNIQUE KEY 为 INPLACE 在线建索引，耗时 ∝ 表行数，全为 NULL 不会撞唯一 |
| 11 | `merchant_desk_receipt_20261022` | 新表 merchant_desk_receipt（F4 工作台幂等回执；merchant.desk.f4.enabled 默认关） | CREATE TABLE IF NOT EXISTS + 列串指纹 guard + 唯一键断言 | 无：只建新表，空表，瞬时完成 |
| 12 | `merchant_redemption_anchor_20261022` | 新表 merchant_redemption_anchor（F4 核销锚点配置） | CREATE TABLE IF NOT EXISTS + 列串指纹 guard + 唯一键断言 | 无：只建新表，空表，瞬时完成 |
| 13 | `o14_city_code_backfill_log_20261022` | 新表 o14_city_code_backfill_log（O-14 回填来源标记） | CREATE TABLE IF NOT EXISTS + 关键列计数断言 | 无：只建新表，空表，瞬时完成 |
| 14 | `o14_cms_activity_city_code_20261022` | cms_activity 加可空列 city_code VARCHAR(32)（ALGORITHM=INSTANT）+ 索引 idx_cms_activity_city_code（INPLACE, LOCK=NONE） | information_schema 判列/索引；同名异形列 guard 让执行器停 + 回读断言 | 中：INSTANT 加列只需短暂元数据锁；建索引在线进行，耗时 ∝ 行数 |
| 15 | `o14_cms_topic_city_code_20261022` | cms_topic 加可空列 city_code VARCHAR(32)（INSTANT）+ 索引 idx_cms_topic_city_code（INPLACE, LOCK=NONE） | 同上 | 中：同上 |
| 16 | `o14_content_city_code_backfill_20261022` | 按 club.city 白名单回填 cms_topic / cms_activity.city_code，并在 o14_city_code_backfill_log 记来源；只改 city_code IS NULL 或本回填写过且未被改动的行 | 逻辑幂等（同规则重跑 city_code 零变更）；无 information_schema 守卫；有回读断言（只允许 6 位城市码与两种来源）。重跑会删除并重插本回填自己的 log 行（write_time 刷新） | 中/高：对 cms_topic、cms_activity 全表扫描 + UPDATE JOIN，行锁覆盖被写行；放低峰执行。执行前必须跑 _precheck_o14_content_city_code_backfill_20261022.sql 预览并由 owner 抽检 |
| 17 | `o14_content_search_document_20261022` | 新表 content_search_document（O-14 统一搜索投影） | CREATE TABLE IF NOT EXISTS + 关键列计数断言 | 无：只建新表，空表，瞬时完成 |
| 18 | `orient_geo_ar_team_20261022` | 新表 orient_node_geo / orient_task_alternative / orient_task_attempt / orient_task_completion / orient_safety_rule + sys_config orient.* 5 键（默认关） | CREATE TABLE IF NOT EXISTS + INSERT … WHERE NOT EXISTS + 回读断言（只数表与键，不核列形状 → 由预检指纹兜底） | 无：只建新表，空表，瞬时完成 |
| 19 | `o14_reco_search_jobs_20261022` | sys_job 登记 recommendationScoreDailyTask.run() 与 contentSearchProjectionTask.refresh()，status='1'（暂停） | INSERT … WHERE NOT EXISTS(invoke_target) + 回读断言恰好 1 行；已存在的登记不改 | 无：sys_job 小表。暂停任务不会被调度，旧 jar 无对应 bean 也不会触发 |
| 20 | `play_arrive_guard_log_20261022` | 新表 play_arrive_guard_log（到达守卫留痕；mode=off 时不读写） | CREATE TABLE IF NOT EXISTS + 列串指纹 guard + 索引断言 | 无：只建新表，空表，瞬时完成 |
| 21 | `pms_sku_booking_20261022` | 新表 pms_sku_booking_rule / pms_sku_capacity_slot（F4 SKU 预约容量） | CREATE TABLE IF NOT EXISTS + 列串指纹 guard + 唯一键断言 | 无：只建新表，空表，瞬时完成 |
| 22 | `orient_scoring_20261022` | 新表 orient_score_ledger / settlement / appeal / orient_result_dispute + sys_config orient.scoring.* 5 键（默认不发奖） | CREATE TABLE IF NOT EXISTS + INSERT … WHERE NOT EXISTS + 回读断言（只数表与键 → 由预检指纹兜底） | 无：只建新表，空表，瞬时完成 |
| 23 | `orient_session_lifecycle_20261022` | topic_route_session 加可空列 phase VARCHAR(16) AFTER status；status 列只改注释（类型/默认不变）；新表 orient_session_clock | information_schema 判列/注释 → 动态 PREPARE；CREATE TABLE IF NOT EXISTS；回读断言 | 低/中：ADD COLUMN 走 INSTANT；MODIFY 只改注释时为元数据变更。若生产 status 列类型/默认与 TINYINT NOT NULL DEFAULT 0 不一致，MODIFY 会变成整表重建——预检会判 CONFLICT，先停 |
| 24 | `member_visited_poi_poi_key_backfill_20261022` | 回填 member_visited_poi.poi_key（poi:{地标id} 或 pt:{5位坐标}；判为重复的行保持 NULL）；不删行、不改 geo_key | 只处理 poi_key IS NULL 的行；重跑时剩下的都是重复行，不再写；回读断言 | 中/高：相关子查询 ∝ 每会员点数²，大表需放低峰；只写 poi_key。必须在打开 roam.visited_poi.stable_identity 之前执行 |
| 25 | `roam_share_version_20261022` | roam_share_snapshot 加列 version INT NOT NULL DEFAULT 1（旧行即为 1） | information_schema 判列 → 动态 PREPARE + 回读断言（类型/非空/默认） | 低：INT NOT NULL DEFAULT 加在末尾，8.0.45 走 INSTANT，只需短暂元数据锁 |
| 26 | `20261022_theme_state_key_attribute_def` | theme_state_key 加 9 个可空列 min_value/max_value/display_type/color/icon/sort_order/visible/persist_scope/rules_json（ALGORITHM=INSTANT） | DDL 前断言（同名异形列 fail-closed）+ 逐列 information_schema 判断 → 动态 PREPARE + DDL 后断言 | 低：9 次 INSTANT 加列，每次短暂元数据锁 |
| 27 | `20261022_theme_character` | 新表 theme_character（F2 跨局角色卡） | CREATE TABLE IF NOT EXISTS + 列计数与唯一键断言 | 无：只建新表，空表，瞬时完成 |
| 28 | `verification_review_evidence_20261022` | 新表 verification_review_evidence（F4 复核补材料） | CREATE TABLE IF NOT EXISTS + 列串指纹 guard + 唯一键断言 | 无：只建新表，空表，瞬时完成 |
| 29 | `20261013_admin_role_page_grants` | 仅对「一个页面授权都没有」的 cy_operator/cy_auditor/cy_support/cy_finance 按 20260719 矩阵补授权并补祖先菜单；已有页面授权的角色不动（生产预期 no-op） | 全部 INSERT … NOT EXISTS；临时表先 DROP TEMPORARY IF EXISTS；无 information_schema 守卫（纯 DML）；有回读断言 | 低：sys_role_menu 小表 |
| 30 | `20261016_admin_role_payee_separation_forward` | D-PAYEE：撤销 cy_operator→business:clubpayee:verify 与 cy_finance→business:clubpayee:payout，补齐 cy_operator→payout 与 cy_finance→verify；全新安装（sys_user 为空且角色无页面）才走批量初始化分支 | -- runner: requires=transaction，单事务；guard 临时表主键冲突（角色/菜单不全、断言失败）让整个事务回滚；INSERT 带 NOT EXISTS，DELETE 重跑删 0 行 | 低：sys_role_menu 小表；SELECT … FOR UPDATE 锁 4 个角色行与 4 个菜单行，事务很短 |

锁风险的共同前提：所有 `ALTER` 都要短暂拿表的排他元数据锁。目标表上有未提交的长事务时，`ALTER` 会排队，并把后面所有读写一起堵住。执行 1.2 里标「中」的那几步之前，先跑预检第 [7/7] 段看 `innodb_trx`；有长事务就等它结束，不要硬上。表的估算行数和体积见预检第 [6/7] 段。

### 1.3 执行后回读（只读）

每一步执行完，先看 runner 输出里的断言全部通过，再跑下表对应的 SELECT，结果留作该步的回执。全部 30 步执行完后，**再跑一遍 `release-migration-precheck.sql`**：除两个回填为 `DATA_PREVIEW` 外，其余都必须是 `APPLIED`。

| 步 | 迁移 | 执行后只读回读（SELECT-only） |
|---|---|---|
| 1 | `20261018_infomation_change_audit` | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('cms_infomation_change_audit'); -- 期望 1` |
| 2 | `20261022_audit_activity_switch_seed` | `SELECT config_key, config_value, COUNT(*) OVER (PARTITION BY config_key) n FROM sys_config WHERE config_key IN ('audit.activity.pre_review_all', 'audit.activity.high_risk_categories'); -- 每键 n=1` |
| 3 | `20261022_f7_minor_guardian` | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('minor_guardian_authorization'); -- 期望 1` |
| 4 | `20261022_f7_user_invoice` | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('user_invoice_title', 'user_invoice_application', 'user_invoice_application_order'); -- 期望 3` |
| 5 | `20261022_location_guard_switch_seed` | `SELECT config_key, config_value, COUNT(*) OVER (PARTITION BY config_key) n FROM sys_config WHERE config_key IN ('play.arrive.location_guard.mode', 'play.arrive.require_accuracy', 'roam.visited_poi.stable_identity'); -- 每键 n=1` |
| 6 | `20261022_merchant_desk_switch_seed` | `SELECT config_key, config_value, COUNT(*) OVER (PARTITION BY config_key) n FROM sys_config WHERE config_key IN ('merchant.desk.f4.enabled'); -- 每键 n=1` |
| 7 | `audit_problem_20261022` | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('audit_problem'); -- 期望 1` |
| 8 | `doc2_f3_bundle_ledger_20261022` | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('explore_bundle_order', 'explore_bundle_grant', 'explore_bundle_unit', 'explore_bundle_reservation', 'explore_bundle_waiver', 'explore_bundle_exit_case'); -- 期望 6` |
| 9 | `member_profile_version_20261022` | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('ums_member_profile_version'); -- 期望 1` |
| 10 | `member_visited_poi_poi_key_20261022` | `SELECT column_name, column_type, is_nullable, column_default FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'member_visited_poi' AND column_name IN ('poi_key');`<br>`SELECT index_name, non_unique, seq_in_index, column_name FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'member_visited_poi' AND index_name = 'uk_member_poi_key';` |
| 11 | `merchant_desk_receipt_20261022` | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('merchant_desk_receipt'); -- 期望 1` |
| 12 | `merchant_redemption_anchor_20261022` | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('merchant_redemption_anchor'); -- 期望 1` |
| 13 | `o14_city_code_backfill_log_20261022` | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('o14_city_code_backfill_log'); -- 期望 1` |
| 14 | `o14_cms_activity_city_code_20261022` | `SELECT column_name, column_type, is_nullable, column_default FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cms_activity' AND column_name IN ('city_code');`<br>`SELECT index_name, non_unique, seq_in_index, column_name FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'cms_activity' AND index_name = 'idx_cms_activity_city_code';` |
| 15 | `o14_cms_topic_city_code_20261022` | `SELECT column_name, column_type, is_nullable, column_default FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cms_topic' AND column_name IN ('city_code');`<br>`SELECT index_name, non_unique, seq_in_index, column_name FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'cms_topic' AND index_name = 'idx_cms_topic_city_code';` |
| 16 | `o14_content_city_code_backfill_20261022` | `SELECT COUNT(*) FROM cms_topic WHERE city_code IS NOT NULL AND city_code NOT REGEXP '^[0-9]{6}$'; -- 期望 0`<br>`SELECT target_table, source, COUNT(*) FROM o14_city_code_backfill_log GROUP BY target_table, source;` |
| 17 | `o14_content_search_document_20261022` | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('content_search_document'); -- 期望 1` |
| 18 | `orient_geo_ar_team_20261022` | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('orient_node_geo', 'orient_task_alternative', 'orient_task_attempt', 'orient_task_completion', 'orient_safety_rule'); -- 期望 5`<br>`SELECT config_key, config_value, COUNT(*) OVER (PARTITION BY config_key) n FROM sys_config WHERE config_key IN ('orient.geo.normalize.enabled', 'orient.alt_task.enabled', 'orient.team.board.enabled', 'orient.team.completion_ratio', 'orient.safety.enabled'); -- 每键 n=1` |
| 19 | `o14_reco_search_jobs_20261022` | `SELECT invoke_target, status, COUNT(*) FROM sys_job WHERE invoke_target IN ('recommendationScoreDailyTask.run()', 'contentSearchProjectionTask.refresh()') GROUP BY invoke_target, status; -- 每个 1 行且 status='1'` |
| 20 | `play_arrive_guard_log_20261022` | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('play_arrive_guard_log'); -- 期望 1` |
| 21 | `pms_sku_booking_20261022` | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('pms_sku_booking_rule', 'pms_sku_capacity_slot'); -- 期望 2` |
| 22 | `orient_scoring_20261022` | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('orient_score_ledger', 'orient_score_settlement', 'orient_score_appeal', 'orient_result_dispute'); -- 期望 4`<br>`SELECT config_key, config_value FROM sys_config WHERE config_key LIKE 'orient.scoring.%'; -- 5 行` |
| 23 | `orient_session_lifecycle_20261022` | `SELECT column_name, column_type, is_nullable, column_default FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'topic_route_session' AND column_name IN ('phase', 'status');`<br>`SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('orient_session_clock'); -- 期望 1` |
| 24 | `member_visited_poi_poi_key_backfill_20261022` | `SELECT COUNT(*) FROM member_visited_poi WHERE poi_key IS NULL; -- 只剩重复行`<br>`SELECT member_id, poi_key, COUNT(*) c FROM member_visited_poi WHERE poi_key IS NOT NULL GROUP BY member_id, poi_key HAVING c > 1; -- 期望 0 行` |
| 25 | `roam_share_version_20261022` | `SELECT column_name, column_type, is_nullable, column_default FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'roam_share_snapshot' AND column_name IN ('version');` |
| 26 | `20261022_theme_state_key_attribute_def` | `SELECT column_name, column_type, is_nullable, column_default FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'theme_state_key' AND column_name IN ('min_value', 'max_value', 'display_type', 'color', 'icon', 'sort_order', 'visible', 'persist_scope', 'rules_json');` |
| 27 | `20261022_theme_character` | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('theme_character'); -- 期望 1` |
| 28 | `verification_review_evidence_20261022` | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('verification_review_evidence'); -- 期望 1` |
| 29 | `20261013_admin_role_page_grants` | `SELECT r.role_key, COUNT(*) FROM sys_role r JOIN sys_role_menu rm ON rm.role_id = r.role_id JOIN sys_menu m ON m.menu_id = rm.menu_id WHERE r.del_flag = '0' AND m.menu_type = 'C' AND r.role_key LIKE 'cy\\_%' GROUP BY r.role_key; -- 4 个角色均 > 0` |
| 30 | `20261016_admin_role_payee_separation_forward` | `SELECT r.role_key, m.perms FROM sys_role_menu rm JOIN sys_role r ON r.role_id = rm.role_id JOIN sys_menu m ON m.menu_id = rm.menu_id WHERE r.del_flag = '0' AND r.role_key IN ('cy_operator', 'cy_finance') AND m.perms IN ('business:clubpayee:verify', 'business:clubpayee:payout'); -- 期望恰好 2 行：cy_operator/payout、cy_finance/verify` |


## 2 D-PAYEE：`migration_20261016_admin_role_payee_separation_forward.sql`

| 项 | 结论 |
|---|---|
| sha256 | `4eeef3936f72f05e69b3440aa818c2c39d53e046aaabd82cfdef10c868cca34e`（与 `migration-ledger.md` 一致；基线上不存在这个文件） |
| 执行位置 | 第 30 步，排在 `20261013_admin_role_page_grants`（第 29 步）之后。依赖：`20261013_admin_role_page_grants`、`20260816_tandianri_batch3_funds_club_payee`、`20260816_tandianri_batch3_funds_club_compensation`、`20260817_tandianri_batch3_funds_club_hours_correction` |
| 执行方式 | `runner: requires=transaction`：整个文件一个事务，必须 `--transaction`。guard 临时表主键冲突（前置不全、任一断言失败）会让整个事务回滚，不会留下半套授权 |

**它做什么。** 在 `sys_role_menu` 上恢复收款资料审核与打款的职责分离：

1. 撤销 `cy_operator → business:clubpayee:verify`（收款资料审核），撤销 `cy_finance → business:clubpayee:payout`（打款时受控解密）。这是两条带 WHERE 的定点 `DELETE`。
2. 补齐 `cy_operator → business:clubpayee:payout`、`cy_finance → business:clubpayee:verify`（缺才插）。
3. 已存在的四条例外授权（`cy_operator` 的 payout，`cy_finance` 的 verify / `clubpayable:confirm` / `clubhours:correct`）原样保留，并有断言保证。
4. 只有「全新安装」（`sys_user` 为空，且四个角色都没有任何页面授权）时，才按 20260719 矩阵批量初始化四个角色并补祖先菜单。生产有后台账号，**不会走这个分支**。
5. 事务内的断言：两条正向授权恰好存在、两条冲突授权不存在、例外授权都还在、新初始化的角色没有 system/monitor/tool 菜单，运营没有资金/导出类动作。

**前置数据（缺任何一项，事务都会回滚）：**

- `sys_role` 中 `cy_operator`、`cy_auditor`、`cy_support`、`cy_finance` 四个角色各有**恰好一行** `del_flag='0'`。重复的活跃 role_key 会撞临时表主键，同样回滚。
- `sys_menu` 中 `business:clubpayee:verify`、`business:clubpayee:payout`、`business:clubpayable:confirm`、`business:clubhours:correct` 四个按钮各有**恰好一行**，且 `menu_type='F'`、`status='0'`、`visible='0'`。这四个按钮来自三个 tandianri batch3 资金迁移。

**执行前必须先跑的只读预检：** `release-migration-precheck.sql` 的第 [2/7] 段（第 496 行判定）和第 [3/7] 段：

- 3a / 3b：四个角色、四个菜单各一行（`active_rows = 1`，菜单 `F/0/0` 计数为 1）。
- 3c：`sys_user` 行数大于 0，说明不会走全新初始化分支。
- 3d：当前 4 × 4 授权矩阵。
- 3e：**这次会删除的授权行**。预期删除数就是这一段的行数；执行后再跑，必须是 0 行。
- 3f：持有 verify / payout 的后台账号清单（不输出手机号或邮箱），供 owner 人工复核职责分离：同一个人不应同时经由两个角色拿到 verify 和 payout。

判定：`APPLIED` = 目标状态已经成立（执行是 no-op，可以安全执行）；`NEEDED` = 有冲突授权或缺正向授权，执行后会被纠正；`BLOCKED_PREREQ` = 前置不全或不唯一，**禁止执行**，先由 owner 查清。

**master 上有没有代码依赖它？没有。** 它只改 `sys_role_menu` 的行，不改 schema。读这两个权限的代码，`ClubCorporatePayeeController` 里的 `@PreAuthorize("@ss.hasPermi('business:clubpayee:verify')")` 和 `…payout`、`ClubCorporatePayeeServiceImpl`、后台 `topic/detail.vue` 的 `v-hasPermi`，从基线 `19408d49` 到 master 一行都没改过（`git diff --stat 19408d49 f099c5be` 对这三个文件为空）。新 jar 不论先部署还是后部署都能运行。这个迁移影响的是**谁能点**这两个按钮，不影响 jar 能不能启动。引用它的只有测试和门禁：`ClubCorporatePayeeMybatisIntegrationTest`（断言最终授权状态）、`ci/test_migration_historical_schema_forward_fix_mysql_integration.py`、`ci/test_run_migration.py`（runner 只允许这两条定点 DELETE 出现在事务里）、`scripts/e2e/test_build_schema.py`。

**演练里看到的情况：**

- 只用仓库迁移重建的库里，三个 tandianri 迁移已经把授权分对了：`cy_operator` 持 payout，`cy_finance` 持 verify。所以在「角色已有页面」的库上，冲突授权只可能来自人工改动，或者来自 `20261013` 的批量初始化。**生产上 D-PAYEE 很可能是 no-op**，以预检 3e 为准。
- 演练的主库刻意造出了漂移（`cy_operator` 持 verify、`cy_finance` 持 payout）。第一遍执行后两条冲突授权被撤销、两条正向授权保留，第二遍零变更。
- 负控：再给 `cy_finance` 授 payout，预检判 `NEEDED`；复制出第二个 `business:clubpayee:verify` 菜单，预检判 `BLOCKED_PREREQ`。

**相关的 `20261013`（第 29 步）必须先看清楚。** 这个文件在基线和 master 之间变过：基线版 `0834bcaf…`，master 版恢复成了原始字节 `1f147a21…`（见台账 §1）。演练确认了 master 版有一个陷阱：

- 只要四个角色里**有任何一个没有页面授权**，它就会给这个角色批量授权。随后，它对「新初始化的运营不得持有 `%payout%`」的回读断言会因为 `cy_operator` 早已持有 `business:clubpayee:payout` 而判假。
- 这个文件**不是**事务文件，前面的 INSERT 已经自动提交。演练探针里停在第 15 条语句时，`cy_operator` 已经被提交了 45 条页面授权，其中包括 `clubpayee:verify`。
- 因此规则是：**预检把 `20261013` 判为 `APPLIED`（四个角色都已有页面授权，即 no-op）时才执行；判为 `NEEDED` 时不要执行，交 owner 处置。** 生产角色按其文件头的说明应当都已配置页面，属于 no-op。

checksum 对账：如果生产有 `cy_migration_history` 表，按预检附录 A 比对上面两个候选值；没有这张表，说明生产没有 runner 历史可对账，按对象状态判定。

## 3 只读预检：`release-migration-precheck.sql`

- 全文只有 SELECT（查 `information_schema` 和 `sys_*` 等业务表），没有 DDL、DML、SET、PREPARE 或事务语句。`ci/test_release_migration_rehearsal.py` 会逐句检查，演练执行前也会再查一遍。
- 文件里不含主机、端口或账号。owner 用自己的只读通道执行；建议用只读账号，或者在会话里先设成只读事务（这一步不写进文件）。
- 输出分 7 段：[1] 服务器身份、[2] **主分类**（每个迁移一行）、[3] D-PAYEE 明细、[4] 种子键与任务的现值、[5] 基线前置、[6] 被改表的体量、[7] 长事务（需要 PROCESS 权限，没有权限时只有这一句报错）。另有两个注释附录：A 是 `cy_migration_history` 对账，B 是两个回填的数据预览。这两段引用的对象只在特定状态下才存在，放进主脚本会在基线库上解析失败，所以按条件手工执行，同样只读。

第 [2/7] 段的状态和处置：

| 状态 | 含义 | 处置 |
|---|---|---|
| `APPLIED` | 对象都在，形状与迁移一致（新表比较列指纹 + 索引指纹；加列比较类型、可空、默认值；种子键和任务要恰好 1 行；RBAC 看目标授权是否成立） | 照常执行（no-op，可以安全重跑），或者由 owner 决定跳过 |
| `NEEDED` | 对象都不存在 | 执行（`20261013` 除外，见第 2 节） |
| `PARTIAL` | 一部分对象已存在且形状正确，其余缺失 | 执行（每个迁移都能续跑） |
| `CONFLICT` | 有同名但定义不同的表、列或索引，或者种子键、任务重复 | **禁止执行**，交 owner 人工处置。尤其是 `audit_problem_20261022` 和 `member_profile_version_20261022`：这两个文件只有 `IF NOT EXISTS`，同名残表会被静默接受，只有预检能发现 |
| `BLOCKED_PREREQ` | 依赖的表、角色或菜单缺失或不唯一 | **禁止执行**，先查前置迁移 |
| `DATA_PREVIEW` | 回填迁移的前置列已就位 | 先跑附录 B（O-14 用仓库里的 `_precheck_o14_content_city_code_backfill_20261022.sql`），owner 抽检确认后再执行 |

列和索引指纹用 `BIT_XOR` 合并每行 SHA-256 的前 64 位，不受 `group_concat_max_len` 影响，也不比较注释和排序规则。期望值由演练在 MySQL 8.0.45 上回读钉住，迁移文件一改，演练就会变红。

## 4 演练（MySQL 8.0 一次性库）

`ci/release_migration_rehearsal.py` 依次执行以下检查，任何一步失败就以退出码 1 停下：

1. **pack 对账**：用 `release_manifest.migration_diff(19408d49, HEAD)` 重新计算发布集，与 `release-migration-pack.json` 逐项比对 id、path、sha256、执行序和依赖。
2. **模拟基线**：从 `sql/base_schema_20260818.sql` 和指纹化的 base seed 建库，再按台账拓扑序叠加快照之后、不属于发布集的 199 个迁移，复用 `scripts/e2e/build_schema.py` 的执行器和守卫。之后写入少量合成行，只在 scratch 库里：一个后台账号；四个角色各一个页面授权，并造出 D-PAYEE 漂移；俱乐部城市、主题、活动（一个可识别城市、一个歧义城市、一个继承父主题）；一个地标和三个到访点（含一个 5m 内的重复点）；一条分享快照；一条属性定义；一个定向会话。
3. **基线预检**：30 项必须等于 pack 里的 `expectedAtBaseline`。29 项为 `NEEDED`，`20261013` 为 `APPLIED`（角色都已有页面授权）。
4. **第一遍执行**：按步 1→30 严格执行，回读断言为假就停，同时记录每个迁移改了哪些表。
5. **执行后预检**：28 项 `APPLIED`，2 个回填为 `DATA_PREVIEW`。
6. **第二遍执行（重跑）**：每个迁移执行前后比较全库 schema 指纹（表、列、索引、约束）和所有表的 `CHECKSUM TABLE … EXTENDED`，必须完全一致。
7. **负控**：7 个人为制造的坏状态，预检必须判出对应状态（同名表多一列、同名表列型不同、种子键重复、`topic_route_session.status` 列型不同 → `CONFLICT`；`cy_auditor` 没有页面授权 → `20261013` 为 `NEEDED`；`cy_finance` 被授予 payout → `NEEDED`；按钮菜单重复 → `BLOCKED_PREREQ`）。
8. **风险探针**：另建一个「角色没有页面授权」的库，复现第 2 节 `20261013` 中途停下、部分授权已提交的问题。如果探针不再复现，说明代码变了，pack 需要更新，演练判红。

**本机结果**（同一份脚本，`mysql:8.0.45` 容器）：PASS。基线建库 17 s、378 张表；第一遍 30/30 成功；第二遍零变更；负控 7/7；风险探针复现。

**零变更的唯一例外（已声明，并且仍然被核对）**：`o14_content_city_code_backfill_20261022` 重跑时，会先 DELETE 再 INSERT 它自己写过的 `o14_city_code_backfill_log` 行，所以 `write_time` 会刷新成 `NOW()`。`cms_topic.city_code`、`cms_activity.city_code` 以及 log 表其余各列都零变更：演练在去掉 `write_time` 列后逐行比对，结果一致。这是迁移本身的行为，本 PR 不改迁移，pack 用 `rerunKnownDelta` 把它记了下来。

**CI 结果**：见第 7 节。

演练证明不了的事：模拟基线是**由仓库重建的**，不是生产的副本。生产上的手工改动或历史漂移，只有第 3 节的预检能发现。演练证明的是这 30 个文件按这个顺序、在仓库定义的基线上能干净落库并且可以重跑，同时也证明了预检脚本本身判得准。

## 5 备份与 stop-on-first-error

执行顺序（每一步单独留回执，不能拿后一步的结果冒充前一步）：

1. **只读预检**：跑 `release-migration-precheck.sql`，保存完整输出。出现任何 `CONFLICT` 或 `BLOCKED_PREREQ`，或者 `20261013` 判为 `NEEDED`，就停在这里交 owner，不进入下一步。
2. **全库备份**：按 `ops/db-backup-restore.md` §2，在维护窗口里停写，用 `scripts/db-backup-restore.py backup` 做一份带回执的逻辑备份（前后快照一致才算成功）。记下备份文件的 sha256 和回执，回填 `release-steps.md` §3 的 `backup`。如果用云盘快照，同样记下快照 ID。**没有备份，不执行任何一步。**
3. **按步 1 → 30 逐个执行**（`20261016` 加 `--transaction`），一次只执行一个文件。每一步执行完：
   - runner 退出码不是 0，或者有任何断言失败，**立即停下**，不执行下一步，不重试同一步，不跳过。
   - 停下以后先只读回读：重跑预检，看这一步是 `PARTIAL`、`APPLIED` 还是 `CONFLICT`，把 runner 输出和预检输出一起交给 owner。
   - DDL 是隐式提交的，失败的文件前面的语句可能已经生效。所有 DDL 迁移都能续跑：owner 确认原因并处理之后，从**同一步**重新开始。DML 迁移里，只有 `20261016` 是事务性的，失败就整体回滚；`20261013` 和两个回填不是事务性的，失败时可能留下部分写入，必须由 owner 按回读结果处置。
4. **全部执行完后再跑一遍预检**：28 项 `APPLIED`、2 项 `DATA_PREVIEW`。之后才进入 jar 部署（`release-steps.md` §5）。
5. **两个回填**（第 16、24 步）：按 owner 的决定，可以和上面一起执行，也可以在部署后的低峰期执行。执行前先跑附录 B 的预览，并由 owner 抽检。但必须在打开 `roam.visited_poi.stable_identity`、启用 O-14 任务之前完成。

**回滚规则（Doc 1 §10，`release-steps.md` §8）：**

- **JAR 回滚不会撤销 DDL。** 回滚到上一个 jar 之后，新加的表和列仍然留在库里。本批迁移都是「新表、可空列、带默认值的列、默认关闭的开关、暂停的任务」，旧 jar 配新 schema 可以正常运行。静态扫描（`release_manifest.analyze_sql`）表明，本发布集里没有 `DROP TABLE`、`DROP COLUMN`、`RENAME`、`TRUNCATE` 或改列型的语句。唯一的 `MODIFY` 只改 `topic_route_session.status` 的注释，类型和默认值都不变。
- **绝不能把旧的全库备份恢复到一个已经有更新数据的库上**，否则从备份时点到恢复时点之间的订单、支付、授权等数据会全部丢失。第 2 步的备份只用于 `ops/db-backup-restore.md` 第 3 节的隔离恢复（恢复到空的独立实例做核对或取数），不覆盖生产。
- 迁移注释里写的 `DROP TABLE` / `DROP COLUMN` / `UPDATE … SET poi_key = NULL` 等回滚语句**不会自动执行**。真要撤销，只能另写前向迁移，由 owner 批准后执行。
- D-PAYEE 如果要撤销，同样只能写前向迁移，把授权改回来，不能恢复 `sys_role_menu` 的旧备份。

## 6 发现与风险清单

| # | 发现 | 影响 | 处置 |
|---|---|---|---|
| R1 | master 版 `20261013` 在「有角色没有页面授权」的库上会在断言处中途停下，前面的授权已经提交（演练探针可以复现） | 可能让 `cy_operator` 意外拿到 `clubpayee:verify` 等授权 | 只在预检判 `APPLIED` 时执行；判 `NEEDED` 时停下交 owner。随后执行的 `20261016` 会撤销 verify，但其余批量授权仍需 owner 判断 |
| R2 | `20261013` 的字节在基线和 master 之间变过 | 生产执行过哪一版不得而知 | 预检附录 A；没有 `cy_migration_history` 就按对象状态判定 |
| R3 | `audit_problem_20261022`、`member_profile_version_20261022` 只有 `IF NOT EXISTS`，没有形状断言 | 同名残表会被静默接受 | 预检用列和索引指纹判 `CONFLICT`（负控已验证） |
| R4 | `o14_content_city_code_backfill_20261022` 重跑会刷新 log 的 `write_time` | 不是严格的零变更（只影响元数据） | 已声明，演练按列核对其余部分 |
| R5 | `orient_session_lifecycle_20261022` 对 `topic_route_session.status` 做 `MODIFY` | 生产上这个列的类型或默认值如果和仓库不一致，`MODIFY` 会变成改型 + 整表重建 | 预检判 `CONFLICT`（负控已验证）时停下 |
| R6 | 两个回填会全表扫描 `cms_topic` / `cms_activity` / `member_visited_poi` | 可能有长时间行锁或高 IO | 低峰执行，先跑预览；看预检第 [6/7] 段的体量 |
| R7 | 演练基线由仓库重建，不是生产副本 | 演练发现不了生产漂移 | 预检是唯一的生产事实来源；建议 owner 在生产副本上先跑一遍（用只读预检 + 隔离恢复的库） |

## 7 CI 演练记录

（PR 上的运行 ID 和结果见本节末尾，由 PR 推送后回填。）

