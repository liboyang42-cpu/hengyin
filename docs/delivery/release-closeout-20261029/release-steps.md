# 发布步骤（release-closeout-20261029）：先部署新后端，再上传小程序体验版

> owner 决定：**先部署新后端（owner 亲自做生产部署与 DDL），再由 Claude 从同一 SHA 上传小程序体验版**。
> 本文件只是 owner 的操作清单；准备本包的过程**没有执行其中任何一步**：没有部署、没有连生产、没有执行 DDL/DML、没有上传制品。
> 每一步留一份**独立回执**（命令输出、截图或哈希），不能拿后一步的结果冒充前一步。
> 主机地址、appid、凭据、上传位置都**不写进本公开仓**，回填的回执放私有位置。
> 本包取代 [release-closeout-20261028](../release-closeout-20261028/release-steps.md)（那个包已过期，不要再按它执行）。

## 0 本次发布的固定输入

| 项 | 值 |
|---|---|
| FINAL SHA（公开 master；jar 与体验版都用它） | `963b351c5adf55934a20e34010e2f90e473296dd`（Merge pull request #662 from liboyang42-cpu/codex/fix-gameplay-http16-20261028；不再移动） |
| 迁移源提交 | `60048713ab6a57890a056fe3091f5be441919939` = FINAL SHA + 1 个提交（只新增 `migration_20261029_unlist_test_topics.sql` 与它的依赖声明）；SQL 文件从本包合入后的 master 取，除这一个文件外与 FINAL SHA 逐字节相同 |
| 迁移发布集 | 88 项：A 轮 51 / B 轮 29 / C 轮 8（按 phase：PRE_DEPLOY_REQUIRED 50、PRE_DEPLOY 30、OWNER_GATED_DATA 8）；其中 2 项永不执行（REPLACED_UNAPPLIED），25 项单次执行 |
| 机器契约 / 预检 / 台账 | [release-migration-pack.json](release-migration-pack.json)、[release-migration-pack.md](release-migration-pack.md)、[release-migration-precheck.sql](release-migration-precheck.sql)、[migration-ledger.md](migration-ledger.md) |
| 隔离演练 | `.github/workflows/release-migration-rehearsal.yml`，按下文 A→B→C 顺序执行全部 88 项并重跑零变更；运行链接见本包 PR 正文 |
| 后端 JAR | `.github/workflows/backend-jar.yml`（source_sha = FINAL SHA），制品 `backend-jar-<FINAL SHA>`；sha256、内嵌 `git.commit.id` 与过期时间见本包 PR 正文。**制品只保留 1 天**，过期就用同一 SHA 重新触发，重新核对 sha256 和内嵌 commit |

开始前确认：本包 PR 上的 CI 与 Release migration rehearsal 都是绿的；`python3 ci/release_manifest.py manifest --commit <FINAL SHA> --lookup-ci` 输出里 FINAL SHA 的 CI 没有红项（清单不提交进仓库）。

## 0.1 相对 20261028 包改了什么

- **FINAL SHA 换了**：`b739b00c` → `963b351c`。期间合入了 #660（整批架构治理源码，+27 个迁移）、#659（移除游玩故事流合成环境音/音效）、#662（个人玩法 HTTP 扫码夹具与回执日期回放修复）。旧 jar（`backend-jar-b739b00c…`）作废。
- **迁移 60 → 88**：10-28 包的 60 项原样保留（契约不变），新增 #660 的 27 项（提现资金事实、微信支付已验证事实、报名席位/支付事实、合作结算现金证据与原路退款组件、官方通知/开赛前通知、NPC 语音识别/声纹、AI 主题草稿意图、拍照检定意图、帖子媒体审核、广播双协议组合等）和本包新增的 1 项测试主题下架。
- **单次执行 4 → 25 项**（见第 2 节清单）；#660 的迁移文件头大多写着 “candidate / source only / proposal / not approved”，确认时逐个点头。
- **`20261026_official_broadcast_delivery` 从“执行一次”改为“永不执行”**：广播改走 D（`20261023_d_official_broadcast_delivery`）+ union（`20261028_official_broadcast_protocol_union`），见第 2 节广播专项。`20261024_c_before_start_notice_typed` 同样永不执行（由 v2 取代）。
- **新增 C 轮数据项**：`20261023_g_wp08_delivery_jobs_paused`（登记两个暂停任务）与 `20261029_unlist_test_topics`（下架 5 个测试主题）。

## 1 备份（任何 DDL/DML 之前）

- ☐ 按 `ops/db-backup-restore.md` 做一次全库备份，记下备份 ID 和 sha256。没有备份不执行后续任何一步。
- ☐ 记下当前线上 jar 的 sha256 和它内嵌的 `git.commit.id`，作为 JAR 回滚目标。
- 能做还原演练就做；没做就记 `UNKNOWN`，不要写成通过。

## 2 只读预检并留存回执

- ☐ 用只读通道（只读账号，或会话里先 `SET SESSION TRANSACTION READ ONLY`）对生产库执行 [release-migration-precheck.sql](release-migration-precheck.sql)。文件里只有 SELECT。
- ☐ 保存完整输出作为**预检回执**（[1/7] 服务器身份、[2/7] 88 行主分类、[3/7]–[7/7]）。
- 逐项按 [2/7] 的 status 处置：
  - `APPLIED`：该对象已就位。**单次执行项必须跳过**；其他项可跳过或按幂等守卫执行。
  - `NEEDED`：执行。例外：`20261013_admin_role_page_grants` 判 NEEDED 时**禁止执行**，停下（见 10-22 包第 4 节）。
  - `REPLACED_UNAPPLIED`：**跳过，永不执行**（`20261024_c_before_start_notice_typed`、`20261026_official_broadcast_delivery`）。
  - `PARTIAL`：只有守卫可续跑的项才可执行；单次执行项一律停下。
  - `CONFLICT` / `BLOCKED_PREREQ`：**停下**，不得继续后面的迁移，交 owner 判断后另写前向修复。
  - `DATA_PREVIEW`：先跑预检附录 B / C 的对应预览，抽检通过再执行。
- ☐ 有 `cy_migration_history` 时跑附录 A，核对 `20261013` 的 checksum。
- ☐ 看 [6/7] 体量与 [7/7] 长事务；`coop_settlement`（现金证据，COPY 重建）、`official_broadcast_log` / `official_broadcast`（广播）、`oms_order` / `club_membership_invoice`（支付证明外键）、`coop_invite`、`refund_application` 放在低峰。

**单次执行（预检 APPLIED 时必须跳过，不得重跑）**：`20261023_wx_pay_order_intent`、`20261024_d_committed_official_notice_wx`、`20261024_d_npc_asr_intent`、`20261024_d_npc_asr_shared_quota`、`20261024_g_before_start_notice_metadata_v2`、`20261024_registration_verified_seats`、`ai_theme_draft_request_intent_20261024`、`20261024_d_chapter_application_round`、`club_welcome_note_20261022`、`20261023_cash_withdraw_fund_fact`、`20261024_cash_withdraw_transfer_intent`、`20261025_cash_withdraw_fund_request_compat`、`npc_voice_enrollment_intent_20261024`、`npc_voice_confirmed_attempt_20261024`、`20261023_d_official_broadcast_delivery`、`20261028_official_broadcast_protocol_union`、`photo_check_intent_20261023`、`20261023_g_cash_evidence_expand`、`20261025_coop_order_station_fulfillment`、`20261024_wx_verified_payment_fact`、`20261024_clm_commerce_payment_proof`、`20261024_coop_original_refund_components`、`20261024_coop_negative_margin_manual`、`20261024_registration_payment_fact`、`20261024_wx_success_fact_business_index`。

**文件头带“候选/未批准”标注、执行前需 owner 单独确认的**：`20261023_wx_pay_order_intent`（PROPOSAL ONLY）、`20261024_wx_verified_payment_fact`（SOURCE-ONLY PROPOSAL）、`20261024_coop_original_refund_components`（NOT approved for application）、`20261023_g_cash_evidence_expand`（EXPAND ONLY）、`20261023_d_official_broadcast_delivery` 与 `20261028_official_broadcast_protocol_union`（CANDIDATE）；其余 #660 迁移的标注见 [release-migration-pack.md](release-migration-pack.md) 第 3 节。本 FINAL SHA 的主代码已读写这些表/列，不执行就不能上新 jar。

**广播专项（必须按这张表做）**：

| 预检 D / 10-26 / union | 做法 |
|---|---|
| NEEDED / REPLACED_UNAPPLIED / NEEDED | 先停广播 worker 与发布入口 → 执行 D 一次 → **跳过 10-26** → 执行 union 一次 → 回读；worker 保持停止直到新 jar 启动 |
| APPLIED / REPLACED_UNAPPLIED / NEEDED | 只执行 union |
| APPLIED / APPLIED / APPLIED | 三个都跳过 |
| CONFLICT（任一） | **停下**：10-26 旧协议曾被单独执行，仓库没有从它升级的迁移；交 Claude 另写前向修复并重新演练，不要手工补列 |
| PARTIAL（任一） | 停下，保存输出交 owner；不得整文件重跑 |

## 3 按顺序执行迁移（chengyin-db-migrate skill，逐个确认）

- 每个文件都走 **chengyin-db-migrate** skill：只读预检 → 展示将执行的文件与预检状态 → **owner 确认** → 执行 → 跑该项的 `verifySelectOnly` 回读并留回执。
- 严格按下表顺序；任何一步报错或断言失败**立即停止**，保存输出，重新跑只读预检，不要跳过继续。
- `runner: requires=transaction` 的文件（`20261016`、`20261026_user_invoice_menu`、`20261027_orient_scoring_menu_grants`）必须用事务模式执行；带 `runner: assert=scalar-true` 的文件必须用支持断言的执行方式，断言为假即停。
- 演练已按这个 A→B→C 顺序在一次性 MySQL 8.0.45 上执行全部 88 项并重跑零变更；这只证明仓库合成库，不证明生产数据。

#### A 轮：PRE_DEPLOY_REQUIRED（含其在发布集内的前置）—— 新 jar 之前必须全部完成

| # | step | 文件 | phase | 预检判 NEEDED 时 | 预检判 APPLIED 时 | 回执 |
|---|---|---|---|---|---|---|
| 1 | 4 | `migration_20261022_f7_user_invoice.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 2 | 7 | `migration_20261023_creative_square_like_unique.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 3 | 9 | `migration_20261023_wx_pay_order_intent.sql` | PRE_DEPLOY_REQUIRED | 执行一次 | **跳过（不得重跑）** | ☐ |
| 4 | 11 | `migration_20261024_d_committed_official_notice_wx.sql` | PRE_DEPLOY_REQUIRED | 执行一次 | **跳过（不得重跑）** | ☐ |
| 5 | 12 | `migration_20261024_c_before_start_notice_typed.sql` | PRE_DEPLOY_REQUIRED | **永不执行（跳过）** | 跳过 | ☐ |
| 6 | 13 | `migration_20261024_d_npc_asr_intent.sql` | PRE_DEPLOY_REQUIRED | 执行一次 | **跳过（不得重跑）** | ☐ |
| 7 | 14 | `migration_20261024_d_npc_asr_shared_quota.sql` | PRE_DEPLOY_REQUIRED | 执行一次 | **跳过（不得重跑）** | ☐ |
| 8 | 15 | `migration_20261024_g_before_start_notice_metadata_v2.sql` | PRE_DEPLOY_REQUIRED | 执行一次 | **跳过（不得重跑）** | ☐ |
| 9 | 16 | `migration_20261024_registration_verified_seats.sql` | PRE_DEPLOY_REQUIRED | 执行一次 | **跳过（不得重跑）** | ☐ |
| 10 | 17 | `migration_20261024_registration_seat_activity_scope.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 11 | 18 | `migration_20261025_coop_invite_request_receipt.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 12 | 19 | `migration_20261026_official_event_attendance.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 13 | 20 | `migration_20261026_team_event_command_receipt.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 14 | 21 | `migration_20261026_theme_attribute_registry_version.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 15 | 22 | `migration_20261026_user_invoice_processing.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 16 | 23 | `migration_20261026_user_invoice_title_receipt.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 17 | 24 | `migration_20261027_template_adoption_receipt.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 18 | 26 | `migration_20261028_topic_play_setup.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 19 | 27 | `migration_ai_theme_draft_request_intent_20261024.sql` | PRE_DEPLOY_REQUIRED | 执行一次 | **跳过（不得重跑）** | ☐ |
| 20 | 29 | `migration_20261024_d_chapter_application_round.sql` | PRE_DEPLOY_REQUIRED | 执行一次 | **跳过（不得重跑）** | ☐ |
| 21 | 33 | `migration_20261023_cash_withdraw_fund_fact.sql` | PRE_DEPLOY_REQUIRED | 执行一次 | **跳过（不得重跑）** | ☐ |
| 22 | 34 | `migration_20261024_cash_withdraw_transfer_intent.sql` | PRE_DEPLOY_REQUIRED | 执行一次 | **跳过（不得重跑）** | ☐ |
| 23 | 35 | `migration_20261025_cash_withdraw_fund_request_compat.sql` | PRE_DEPLOY_REQUIRED | 执行一次 | **跳过（不得重跑）** | ☐ |
| 24 | 36 | `migration_member_profile_version_20261022.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 25 | 38 | `migration_merchant_category_ids_20261022.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 26 | 42 | `migration_merchant_wechat_qrcode_20261022.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 27 | 43 | `migration_npc_voice_enrollment_intent_20261024.sql` | PRE_DEPLOY_REQUIRED | 执行一次 | **跳过（不得重跑）** | ☐ |
| 28 | 44 | `migration_npc_voice_confirmed_attempt_20261024.sql` | PRE_DEPLOY_REQUIRED | 执行一次 | **跳过（不得重跑）** | ☐ |
| 29 | 50 | `migration_20261023_d_official_broadcast_delivery.sql` | PRE_DEPLOY_REQUIRED | 执行一次 | **跳过（不得重跑）** | ☐ |
| 30 | 51 | `migration_20261026_official_broadcast_delivery.sql` | PRE_DEPLOY_REQUIRED | **永不执行（跳过）** | 跳过 | ☐ |
| 31 | 52 | `migration_20261028_official_broadcast_protocol_union.sql` | PRE_DEPLOY_REQUIRED | 执行一次 | **跳过（不得重跑）** | ☐ |
| 32 | 54 | `migration_orient_score_recovery_20261026.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 33 | 55 | `migration_orient_score_sync_receipt_20261026.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 34 | 59 | `migration_20261023_advanced_command_fingerprint.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 35 | 62 | `migration_orient_session_lifecycle_20261022.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 36 | 63 | `migration_photo_check_intent_20261023.sql` | PRE_DEPLOY_REQUIRED | 执行一次 | **跳过（不得重跑）** | ☐ |
| 37 | 65 | `migration_roam_share_version_20261022.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 38 | 66 | `migration_20261024_official_roam_challenges.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 39 | 67 | `migration_20261025_official_poi_quotes.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 40 | 68 | `migration_20261023_g_cash_evidence_expand.sql` | PRE_DEPLOY_REQUIRED | 执行一次 | **跳过（不得重跑）** | ☐ |
| 41 | 69 | `migration_20261022_theme_state_key_attribute_def.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 42 | 70 | `migration_20261022_theme_character.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 43 | 71 | `migration_20261028_d20_attribute_registry.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 44 | 75 | `migration_20261025_coop_order_station_fulfillment.sql` | PRE_DEPLOY_REQUIRED | 执行一次 | **跳过（不得重跑）** | ☐ |
| 45 | 82 | `migration_club_post_media_review_budget_20261024.sql` | PRE_DEPLOY_REQUIRED | 执行 | 可执行（幂等）或跳过 | ☐ |
| 46 | 83 | `migration_20261024_wx_verified_payment_fact.sql` | PRE_DEPLOY_REQUIRED | 执行一次 | **跳过（不得重跑）** | ☐ |
| 47 | 84 | `migration_20261024_clm_commerce_payment_proof.sql` | PRE_DEPLOY_REQUIRED | 执行一次 | **跳过（不得重跑）** | ☐ |
| 48 | 85 | `migration_20261024_coop_original_refund_components.sql` | PRE_DEPLOY_REQUIRED | 执行一次 | **跳过（不得重跑）** | ☐ |
| 49 | 86 | `migration_20261024_coop_negative_margin_manual.sql` | PRE_DEPLOY_REQUIRED | 执行一次 | **跳过（不得重跑）** | ☐ |
| 50 | 87 | `migration_20261024_registration_payment_fact.sql` | PRE_DEPLOY_REQUIRED | 执行一次 | **跳过（不得重跑）** | ☐ |
| 51 | 88 | `migration_20261024_wx_success_fact_business_index.sql` | PRE_DEPLOY_REQUIRED | 执行一次 | **跳过（不得重跑）** | ☐ |

#### B 轮：其余 PRE_DEPLOY —— 同样在新 jar 之前完成

| # | step | 文件 | phase | 预检判 NEEDED 时 | 预检判 APPLIED 时 | 回执 |
|---|---|---|---|---|---|---|
| 52 | 1 | `migration_20261018_infomation_change_audit.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 53 | 2 | `migration_20261022_audit_activity_switch_seed.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 54 | 3 | `migration_20261022_f7_minor_guardian.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 55 | 5 | `migration_20261022_location_guard_switch_seed.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 56 | 6 | `migration_20261022_merchant_desk_switch_seed.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 57 | 8 | `migration_20261023_points_value_config_seed.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 58 | 10 | `migration_20261024_community_open_flags.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 59 | 28 | `migration_audit_problem_20261022.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 60 | 30 | `migration_club_welcome_note_20261022.sql` | PRE_DEPLOY | 执行一次 | **跳过（不得重跑）** | ☐ |
| 61 | 31 | `migration_doc2_f3_bundle_ledger_20261022.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 62 | 32 | `migration_doc2_f3_refund_hold_20261026.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 63 | 37 | `migration_member_visited_poi_poi_key_20261022.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 64 | 40 | `migration_merchant_desk_receipt_20261022.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 65 | 41 | `migration_merchant_redemption_anchor_20261022.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 66 | 45 | `migration_o14_city_code_backfill_log_20261022.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 67 | 46 | `migration_o14_cms_activity_city_code_20261022.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 68 | 47 | `migration_o14_cms_topic_city_code_20261022.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 69 | 49 | `migration_o14_content_search_document_20261022.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 70 | 53 | `migration_orient_geo_ar_team_20261022.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 71 | 56 | `migration_o14_reco_search_jobs_20261022.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 72 | 57 | `migration_play_arrive_guard_log_20261022.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 73 | 58 | `migration_pms_sku_booking_20261022.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 74 | 61 | `migration_orient_scoring_20261022.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 75 | 74 | `migration_verification_review_evidence_20261022.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 76 | 76 | `migration_w8_refund_fulfillment_recovery_20261026.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 77 | 77 | `migration_20261026_user_invoice_menu.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 78 | 78 | `migration_20261013_admin_role_page_grants.sql` | PRE_DEPLOY | **禁止执行，停下交 owner** | 执行（no-op） | ☐ |
| 79 | 79 | `migration_20261016_admin_role_payee_separation_forward.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |
| 80 | 80 | `migration_20261027_orient_scoring_menu_grants.sql` | PRE_DEPLOY | 执行 | 可执行（幂等）或跳过 | ☐ |

#### C 轮：OWNER_GATED_DATA —— 先跑预览、owner 抽检后执行；可在 jar 之后，但须在打开相关功能前完成；整个 C 轮（含测试主题下架）排在最后

| # | step | 文件 | phase | 预检判 NEEDED 时 | 预检判 APPLIED 时 | 回执 |
|---|---|---|---|---|---|---|
| 81 | 25 | `migration_20261027_user_invoice_processing_backfill.sql` | OWNER_GATED_DATA | 先跑预览再执行 | 跳过或执行均为 0 变更 | ☐ |
| 82 | 39 | `migration_20261027_merchant_brand_images_to_gallery.sql` | OWNER_GATED_DATA | 先跑预览再执行 | 跳过或执行均为 0 变更 | ☐ |
| 83 | 48 | `migration_o14_content_city_code_backfill_20261022.sql` | OWNER_GATED_DATA | 先跑预览再执行 | 跳过或执行均为 0 变更 | ☐ |
| 84 | 60 | `migration_20261029_unlist_test_topics.sql` | OWNER_GATED_DATA | 先跑预览再执行 | 跳过或执行均为 0 变更 | ☐ |
| 85 | 64 | `migration_member_visited_poi_poi_key_backfill_20261022.sql` | OWNER_GATED_DATA | 先跑预览再执行 | 跳过或执行均为 0 变更 | ☐ |
| 86 | 72 | `migration_20261028_remove_wildlife_theme.sql` | OWNER_GATED_DATA | 先跑预览再执行 | 跳过或执行均为 0 变更 | ☐ |
| 87 | 73 | `migration_20261028_template_shelf_list_free_explore.sql` | OWNER_GATED_DATA | 先跑预览再执行 | 跳过或执行均为 0 变更 | ☐ |
| 88 | 81 | `migration_20261023_g_wp08_delivery_jobs_paused.sql` | OWNER_GATED_DATA | 先跑预览再执行 | 跳过或执行均为 0 变更 | ☐ |

- ☐ `20261029_unlist_test_topics`：先跑文件头的只读 SELECT（或预检附录 C5），确认最多 5 行、看 `live_*` 计数；执行后再跑一次，被软删的行 `del_flag=1`、`update_by='unlist_test_topics_20261029'`，被守卫跳过的行仍为 0（交 owner 另定处置）。990052–990056、990068、990069 不在范围内。

## 4 替换 jar 并重启（owner）

- ☐ A 轮、B 轮全部完成（或已按预检判 APPLIED / REPLACED_UNAPPLIED 跳过）并有回执后才继续。
- ☐ 下载 `backend-jar-<FINAL SHA>`，核对 `sha256sum chengyinhub-admin.jar` 等于 PR 正文记录的值。
- ☐ `unzip -p chengyinhub-admin.jar BOOT-INF/classes/git.properties | grep git.commit.id` 必须等于 FINAL SHA `963b351c5adf55934a20e34010e2f90e473296dd`。
- ☐ 按既有生产部署流程替换 jar 并重启（owner 操作；本包不启用、不修改任何生产 workflow）。
- ☐ 新 jar 启动后再恢复广播 worker / 发布入口；新登记的 sys_job（官方通知微信恢复、站内通知意图投递、漫游掉落补交付）保持暂停，启用需另行批准。

## 5 健康检查

- ☐ 应用启动日志无 `Unknown column` / `Table ... doesn't exist` / `Check constraint` 类报错。
- ☐ 健康检查接口返回正常；抽查依赖新表/新列的接口：主题可玩时段、漫游官方地点详情、官方播报（发布一条只给自己的测试播报再读回）、发票抬头/发票处理、定向计分申诉、提现记录读取、报名/核销席位读取、NPC 对话。
- ☐ 健康检查结果单独留一份回执。失败时按第 6 节回滚 jar，不要回滚 DDL。

## 6 回滚

- **JAR 回滚不会撤销 DDL。** 本批迁移只加表、加列、加约束/触发器、加菜单/授权或做可逆的数据更新，旧 jar 加新 schema 可以运行。
- 注释里的 `DROP` / 反向 UPDATE **不会自动执行**；真要撤销，另写前向迁移，经 owner 批准后执行。测试主题下架的回滚语句写在该迁移文件头，只恢复本迁移改过的行。
- #660 的只追加触发器表（提现资金事实、报名支付事实、站点履约）一旦有真实数据就不得删除。
- 不要把旧全库备份直接覆盖到已有新业务数据的库。

## 7 然后再上传小程序体验版

- ☐ 第 5 节健康检查通过后，告诉 Claude：“后端已部署（FINAL SHA 963b351c），可以上传体验版”。
- Claude 从**最终公开树**干净检出 FINAL SHA 导出小程序，核对 `chengyinhub-xcx` tree hash 与 `app.json` blob，appid / API 目标由 owner 在私有环境配置；上传、提审、发布三步各留独立回执（见 10-22 包 release-steps 第 6–7 节）。
