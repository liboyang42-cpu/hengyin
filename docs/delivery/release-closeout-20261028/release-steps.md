# 发布步骤（release-closeout-20261028）：先部署新后端，再上传小程序体验版

> owner 决定（2026-10-28 20:35Z）：**先部署新后端，再上传小程序体验版**。生产部署与生产 DDL/DML **只由 owner 操作**。
> 本文件只是 owner 的操作清单；准备本包的过程**没有执行其中任何一步**：没有部署、没有连生产、没有执行 DDL、没有上传制品。
> 每一步留一份**独立回执**（命令输出、截图或哈希），不能拿后一步的结果冒充前一步。
> 主机地址、appid、凭据、上传位置都**不写进本公开仓**，回填的回执放私有位置。

## 0 本次发布的固定输入

| 项 | 值 |
|---|---|
| FINAL SHA（公开 master） | `b739b00c082047c57167c5541682f443b4b2dda5`（Merge #640；不再移动） |
| 迁移发布集 | 60 项：A 轮 25 / B 轮 29 / C 轮 6（按 phase：PRE_DEPLOY_REQUIRED 24、PRE_DEPLOY 30、OWNER_GATED_DATA 6） |
| 机器契约 / 预检 / 台账 | [release-migration-pack.json](release-migration-pack.json)、[release-migration-precheck.sql](release-migration-precheck.sql)、[migration-ledger.md](migration-ledger.md) |
| 隔离演练 | `.github/workflows/release-migration-rehearsal.yml`，按下文 A→B→C 的顺序执行全部 60 项；运行链接见本包 PR 正文 |
| 后端 JAR | `.github/workflows/backend-jar.yml`（source_sha = FINAL SHA），制品 `backend-jar-<FINAL SHA>`；sha256 与内嵌 `git.commit.id` 见本包 PR 正文。**制品只保留 1 天**，过期就用同一 SHA 重新触发，重新核对 sha256 和内嵌 commit |

开始前确认：本包 PR 上的 CI 与 Release migration rehearsal 都是绿的；`python3 ci/release_manifest.py manifest --commit <FINAL SHA> --lookup-ci` 输出里 FINAL SHA 的 CI 没有红项（清单不提交进仓库）。

## 1 备份（任何 DDL/DML 之前）

- ☐ 按 `ops/db-backup-restore.md` 做一次全库备份，记下备份 ID 和 sha256。没有备份不执行后续任何一步。
- ☐ 记下当前线上 jar 的 sha256 和它内嵌的 `git.commit.id`，作为 JAR 回滚目标。
- 能做还原演练就做；没做就记 `UNKNOWN`，不要写成通过。

## 2 只读预检并留存回执

- ☐ 用只读通道（只读账号，或会话里先 `SET SESSION TRANSACTION READ ONLY`）对生产库执行 [release-migration-precheck.sql](release-migration-precheck.sql)。文件里只有 SELECT。
- ☐ 保存完整输出作为**预检回执**（[1/7] 服务器身份、[2/7] 60 行主分类、[3/7]–[7/7]）。
- 逐项按 [2/7] 的 status 处置：
  - `APPLIED`：该对象已就位。单次执行项（`club_welcome_note_20261022`、`20261023_wx_pay_order_intent`、`20261024_cash_withdraw_transfer_intent`、`20261026_official_broadcast_delivery`）**必须跳过**；其他项可跳过或按幂等守卫执行。
  - `NEEDED`：执行。例外：`20261013_admin_role_page_grants` 判 NEEDED 时**禁止执行**，停下（见 10-22 包第 4 节）。
  - `PARTIAL`：只有守卫可续跑的项才可执行；单次执行项一律停下。
  - `CONFLICT` / `BLOCKED_PREREQ`：**停下**，不得继续后面的迁移，交 owner 判断后另写前向修复。
  - `DATA_PREVIEW`：先跑预检附录 B / C 的对应预览，抽检通过再执行。
- ☐ 有 `cy_migration_history` 时跑附录 A，核对 `20261013` 的 checksum。
- ☐ 看 [6/7] 体量与 [7/7] 长事务；`official_broadcast` 唯一键、`refund_application` 加列与索引放在低峰。
- ☐ `20261023_wx_pay_order_intent.sql` 文件头写着 “PROPOSAL ONLY”，本 SHA 支付代码已读写此表：执行前单独确认。

## 3 按顺序执行迁移（chengyin-db-migrate skill，逐个确认）

- 每个文件都走 **chengyin-db-migrate** skill：只读预检 → 展示将执行的文件与预检状态 → **owner 确认** → 执行 → 跑该项的 `verifySelectOnly` 回读并留回执。
- 严格按下表顺序；任何一步报错或断言失败**立即停止**，保存输出，重新跑只读预检，不要跳过继续。
- `runner: requires=transaction` 的文件（`20261016`、`20261026_user_invoice_menu`、`20261027_orient_scoring_menu_grants`）必须用事务模式执行。
- 演练已按这个 A→B→C 顺序在一次性 MySQL 8.0.45 上执行全部 60 项并重跑零变更；这只证明仓库合成库，不证明生产数据。

#### A 轮：PRE_DEPLOY_REQUIRED（含其在发布集内的前置）—— 新 jar 之前必须全部完成

| # | step | 文件 | phase | 预检判 NEEDED 时 | 预检判 APPLIED 时 | 回执 |
|---|---|---|---|---|---|---|
| 1 | 4 | `migration_20261022_f7_user_invoice.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 2 | 7 | `migration_20261023_creative_square_like_unique.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 3 | 9 | `migration_20261023_wx_pay_order_intent.sql` | PRE_DEPLOY_REQUIRED | 执行一次 | **跳过（不得重跑）** | ☐ |
| 4 | 10 | `migration_20261024_cash_withdraw_transfer_intent.sql` | PRE_DEPLOY_REQUIRED | 执行一次 | **跳过（不得重跑）** | ☐ |
| 5 | 12 | `migration_20261025_coop_invite_request_receipt.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 6 | 13 | `migration_20261026_official_event_attendance.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 7 | 14 | `migration_20261026_team_event_command_receipt.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 8 | 15 | `migration_20261026_theme_attribute_registry_version.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 9 | 16 | `migration_20261026_user_invoice_processing.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 10 | 17 | `migration_20261026_user_invoice_title_receipt.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 11 | 18 | `migration_20261027_template_adoption_receipt.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 12 | 20 | `migration_20261028_topic_play_setup.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 13 | 25 | `migration_member_profile_version_20261022.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 14 | 27 | `migration_merchant_category_ids_20261022.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 15 | 31 | `migration_merchant_wechat_qrcode_20261022.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 16 | 37 | `migration_20261026_official_broadcast_delivery.sql` | PRE_DEPLOY_REQUIRED | 执行一次 | **跳过（不得重跑）** | ☐ |
| 17 | 39 | `migration_orient_score_recovery_20261026.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 18 | 40 | `migration_orient_score_sync_receipt_20261026.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 19 | 45 | `migration_orient_session_lifecycle_20261022.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 20 | 47 | `migration_roam_share_version_20261022.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 21 | 48 | `migration_20261024_official_roam_challenges.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 22 | 49 | `migration_20261025_official_poi_quotes.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 23 | 50 | `migration_20261022_theme_state_key_attribute_def.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 24 | 51 | `migration_20261022_theme_character.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 25 | 52 | `migration_20261028_d20_attribute_registry.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |

#### B 轮：其余 PRE_DEPLOY —— 同样在新 jar 之前完成

| # | step | 文件 | phase | 预检判 NEEDED 时 | 预检判 APPLIED 时 | 回执 |
|---|---|---|---|---|---|---|
| 26 | 1 | `migration_20261018_infomation_change_audit.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 27 | 2 | `migration_20261022_audit_activity_switch_seed.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 28 | 3 | `migration_20261022_f7_minor_guardian.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 29 | 5 | `migration_20261022_location_guard_switch_seed.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 30 | 6 | `migration_20261022_merchant_desk_switch_seed.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 31 | 8 | `migration_20261023_points_value_config_seed.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 32 | 11 | `migration_20261024_community_open_flags.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 33 | 21 | `migration_audit_problem_20261022.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 34 | 22 | `migration_club_welcome_note_20261022.sql` | PRE_DEPLOY | 执行一次 | **跳过（不得重跑）** | ☐ |
| 35 | 23 | `migration_doc2_f3_bundle_ledger_20261022.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 36 | 24 | `migration_doc2_f3_refund_hold_20261026.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 37 | 26 | `migration_member_visited_poi_poi_key_20261022.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 38 | 29 | `migration_merchant_desk_receipt_20261022.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 39 | 30 | `migration_merchant_redemption_anchor_20261022.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 40 | 32 | `migration_o14_city_code_backfill_log_20261022.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 41 | 33 | `migration_o14_cms_activity_city_code_20261022.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 42 | 34 | `migration_o14_cms_topic_city_code_20261022.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 43 | 36 | `migration_o14_content_search_document_20261022.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 44 | 38 | `migration_orient_geo_ar_team_20261022.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 45 | 41 | `migration_o14_reco_search_jobs_20261022.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 46 | 42 | `migration_play_arrive_guard_log_20261022.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 47 | 43 | `migration_pms_sku_booking_20261022.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 48 | 44 | `migration_orient_scoring_20261022.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 49 | 55 | `migration_verification_review_evidence_20261022.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 50 | 56 | `migration_w8_refund_fulfillment_recovery_20261026.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 51 | 57 | `migration_20261026_user_invoice_menu.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 52 | 58 | `migration_20261013_admin_role_page_grants.sql` | PRE_DEPLOY | **禁止执行，停下交 owner** | 执行（no-op） | ☐ |
| 53 | 59 | `migration_20261016_admin_role_payee_separation_forward.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 54 | 60 | `migration_20261027_orient_scoring_menu_grants.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |

#### C 轮：OWNER_GATED_DATA —— 先跑预览、owner 抽检后执行；可在 jar 之后，但须在打开相关功能前完成

| # | step | 文件 | phase | 预检判 NEEDED 时 | 预检判 APPLIED 时 | 回执 |
|---|---|---|---|---|---|---|
| 55 | 19 | `migration_20261027_user_invoice_processing_backfill.sql` | OWNER_GATED_DATA | 先跑预览再执行 | 跳过或执行均为 0 变更 | ☐ |
| 56 | 28 | `migration_20261027_merchant_brand_images_to_gallery.sql` | OWNER_GATED_DATA | 先跑预览再执行 | 跳过或执行均为 0 变更 | ☐ |
| 57 | 35 | `migration_o14_content_city_code_backfill_20261022.sql` | OWNER_GATED_DATA | 先跑预览再执行 | 跳过或执行均为 0 变更 | ☐ |
| 58 | 46 | `migration_member_visited_poi_poi_key_backfill_20261022.sql` | OWNER_GATED_DATA | 先跑预览再执行 | 跳过或执行均为 0 变更 | ☐ |
| 59 | 53 | `migration_20261028_remove_wildlife_theme.sql` | OWNER_GATED_DATA | 先跑预览再执行 | 跳过或执行均为 0 变更 | ☐ |
| 60 | 54 | `migration_20261028_template_shelf_list_free_explore.sql` | OWNER_GATED_DATA | 先跑预览再执行 | 跳过或执行均为 0 变更 | ☐ |
## 4 替换 jar 并重启（owner）

- ☐ A 轮、B 轮全部完成（或已按预检判 APPLIED 跳过）并有回执后才继续。
- ☐ 下载 `backend-jar-<FINAL SHA>`，核对 `sha256sum chengyinhub-admin.jar` 等于 PR 正文记录的值。
- ☐ `unzip -p chengyinhub-admin.jar BOOT-INF/classes/git.properties | grep git.commit.id` 必须等于 FINAL SHA。
- ☐ 按既有生产部署流程替换 jar 并重启（owner 操作；本包不启用、不修改任何生产 workflow）。

## 5 健康检查

- ☐ 应用启动日志无 `Unknown column` / `Table ... doesn't exist` 类报错。
- ☐ 健康检查接口返回正常；抽查依赖新表/新列的接口：主题可玩时段（`/api/topic/play-setup`）、漫游官方地点详情、官方播报、发票抬头/发票处理、定向计分申诉。
- ☐ 健康检查结果单独留一份回执。失败时按第 6 节回滚 jar，不要回滚 DDL。

## 6 回滚

- **JAR 回滚不会撤销 DDL。** 本批迁移只加表、加列、加菜单/授权或做可逆的数据更新，旧 jar 加新 schema 可以运行。
- 注释里的 `DROP` / 反向 UPDATE **不会自动执行**；真要撤销，另写前向迁移，经 owner 批准后执行。
- 不要把旧全库备份直接覆盖到已有新业务数据的库。

## 7 然后再上传小程序体验版

- ☐ 第 5 节健康检查通过后，告诉 Claude：“后端已部署（FINAL SHA b739b00c），可以上传体验版”。
- Claude 从**最终公开树**干净检出 FINAL SHA 导出小程序，核对 `chengyinhub-xcx` tree hash 与 `app.json` blob，appid / API 目标由 owner 在私有环境配置；上传、提审、发布三步各留独立回执（见 10-22 包 release-steps 第 6–7 节）。
