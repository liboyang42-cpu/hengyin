# f-rehearsal · 上线迁移全量演练 — RESULT（2026-11-11）

分支 `claude/f-rehearsal-20261111`，从 `origin/launch/questo-20261110`（`4cf14763`）切出。按 `f-release/DEPLOY.md` 的轮次，在**本机一次性 MySQL 8.0** 上把整套发布迁移跑了两遍，再用 launch jar 连这个库做了只读接口冒烟和回滚演练。

以下事情都没有做：
- 没有连生产、VPS 或私有仓。演练库只在本次云端沙箱的 127.0.0.1 上，库名都是 `cy_e2e_*`，跑完即弃。
- 没有改任何已有迁移文件。缺陷都用**新增迁移**或改发布清单、预检、DEPLOY 文档来修。
- 没有运行 `ci/xcx-check.sh` 和 `ds-hardcode-gate-selftest.sh`。
- 没有提交任何密钥。jar 启动用的 JWT、AppSecret 等全是本机随机生成的一次性值。

## 一、结论

- 发布集一共 156 项（原 155 项，加上本线新增的 1 项修正）。其中 152 项按轮次执行，4 项「不执行」只记账。
- **第一遍**：151 项成功，1 项断言为假。失败的是 `20261101_stagger_sys_job_seconds`，原因是合成库的 `sys_job` 编号和生产不一样，不是迁移的问题。用照生产编号造的任务表单独验过，两遍都通过（见第五节）。
- **第二遍**（按 DEPLOY 规则重跑）：106 项实际重跑，零报错（stagger 原因同上）。46 项「APPLIED 时必须跳过」先跑预检，判 APPLIED 后跳过。全库指纹只在 3 张表上有变化，变的都是时间戳列，业务值不变（见第四节）。
- **原样重跑**：在克隆库上把 46 项单次执行迁移不跳过、直接再跑一次。42 项在建表、加列或前置断言处报错停下，4 项跑通，**46 项全部没有改动库状态**。
- **jar 冒烟**：`mvn -q -DskipTests package` 打出的 launch jar 用默认开关连迁移后的库，19.6 秒启动。27 个接口调用的响应和应用日志里，**没有** 1054、1146、1267、BadSqlGrammar、Check constraint 或 NoSuchBeanDefinition。调度器对 36 行 `sys_job` 做 bean 查找也没有报错。
- **回滚**：第 12 节能脚本化的部分都演练了。按本线修正后的清单，删任务、前滚恢复、数据回滚语句都按预期工作。
- 发现并修了 **4 个真实缺陷**（第三节），另有 3 条只报告、不在本线修改范围（第六节）。

## 二、环境与假设

| 项 | 值 |
|---|---|
| MySQL | 8.0.46（Ubuntu 包，本机 127.0.0.1:3316）。10-29 演练用的是 8.0.45 |
| sql_mode | `ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION`（MySQL 8.0 默认值） |
| 字符集 | 服务端 `utf8mb4` / `utf8mb4_0900_ai_ci`；建库 `DEFAULT CHARACTER SET utf8mb4`；`default-time-zone=+08:00` |
| Redis | 7.x，本机 6379，只给 jar 冒烟用 |
| JDK / Maven | OpenJDK 21.0.12，Maven；打包时需要 `LANG=C.UTF-8`，否则 javac 读中文文件名报 `InvalidPathException`。这是沙箱 locale 问题，和代码无关 |

**假设**：
1. **生产当前 schema 的模型**：`sql/base_schema_20260818.sql` + `base_seed_20260818.sql`，再按台账拓扑序叠加快照之后、且**不在本次发布集**里的 199 个迁移，一共 378 张表。这相当于生产参考基线 `19408d49`（Merge #452）上已有的迁移。10-22、10-28、10-29 三个包按「生产从未确认执行」处理，和 `f-release/DEPLOY.md` 第 0 节的口径一致。造库逻辑沿用 `scripts/e2e/build_schema.py` 和 `ci/release_migration_rehearsal.py`。
2. **sql_mode 和字符集**：仓库文档里没有记录生产的 sql_mode。这里用 MySQL 8.0 默认值，和 10-29 演练相同。已有表的排序规则（`utf8mb4_general_ci` 和 `0900_ai_ci` 混用）来自 base schema 的逐表定义。`zz_questo_collation_align` 的锚点预检（`cms_registration.parent_order_no` 是 `general_ci`）在这个模型上成立。
3. **合成业务行**：只写进演练库。用的是 10-29 演练的 `SYNTHETIC_ROWS`、`PRODUCTION_LIKE_ROWS`，加上本线的 `seed_rows.sql`（按各迁移文件头写的生产现状构造），目的是让第 4、5 轮的数据迁移真正命中行。发票申请这类数据只有新 jar 上线后才会产生，所以放在第 1 轮建表之后、第 4 轮之前写入（`-- @@before-round 4_OWNER_DATA_PRE` 分段）。
4. **step 134**（`20261013_admin_role_page_grants`）：合成库带了「生产形态」的角色页面授权，所以预检判 APPLIED，执行后零改动。预检判 NEEDED 时在断言处中途停下的路径，已由 10-29 演练的风险探针覆盖，本线没有重复。

## 三、缺陷与修正

| # | 缺陷 | 影响 | 修正 | 验证 |
|---|---|---|---|---|
| D1 | **step 11 的任务回滚后无法恢复**。DEPLOY 第 12 节规定：jar 回滚前删掉旧 jar 没有 bean 的 `sys_job` 行，再前滚时「重跑对应迁移即可恢复」。但 `committedOfficialNoticeTask.deliverPending()` 的任务行由 step 11 `migration_20261024_d_committed_official_notice_wx.sql` 插入，这个文件先 `CREATE TABLE`（不带 IF NOT EXISTS），属于单次执行项。重跑在建表处报 1050 停下，任务行回不来 | 回滚后再前滚，官方通知的微信补偿任务会悄悄消失 | 新增 `migration_zzz_committed_official_notice_job_reregister_20261111.sql`（step 156，第 3 轮）：按 invoke_target 判重，补登记同一条暂停任务，正常路径上是空操作。`zzz_` 前缀让它排在最后，已有的 step 号一个都不动。另外登记了 `ci/migration-dependencies.json`（依赖 step 11）和 `gen_release_set.py` 的 NEW_META；DEPLOY 第 6、12 节写明「不要重跑 step 11，改跑 step 156」 | 修正前：回滚演练删 9 行，前滚只回来 8 行（`restoredSameTargets=false`）。修正后：9 行全部恢复为暂停态。step 156 首跑和重跑都是 0 行、成功 |
| D2 | **预检把已执行的 step 24 误判成 CONFLICT**。`20261027_template_adoption_receipt` 的预检块原样取自 10-29 包，钉的是「建表后」的 12 列、2 个索引行的精确指纹。同一发布集里的 step 42 会给这张表再加 1 列和 1 个索引，所以全部执行之后重跑预检，step 24 判成 CONFLICT | DEPLOY 第 2 节规定 CONFLICT 时停下、后面的迁移都不跑。任何一步出错后「重跑预检」都会被这条误报卡住 | `gen_release_set.py` 新增 `PACK_POST_SHAPES`：对这张表追加「step 42 落地后」的第二个指纹（13 列、6 个索引行，取自演练库的 information_schema）。两个形状之一完全吻合就判 APPLIED，其余形状仍判 CONFLICT；然后重新生成 `release-precheck.sql` | 修正前，第一遍之后预检为 `CONFLICT`。修正后，基线判 NEEDED，执行后判 APPLIED（终版演练）。全部执行后的预检里没有任何 CONFLICT 或 PARTIAL |
| D3 | **5 个迁移的重跑规则标错**。`marketing_attribution_20261108`、`marketing_product_20261108`、`marketing_revenue_allocation_20261108`、`marketing_z_channel_voucher_20261108`、`video_bounty_host_tip_20261108` 被标成「幂等」，但它们的第一句是「目标表和约束都不存在」的前置断言（故意用 `JSON_EXTRACT` 报 3141） | 重跑必定报错停下。不会改坏数据，但按 DEPLOY 规则，报错就要停下整个窗口 | `gen_release_set.py` 新增 `PREFLIGHT_SINGLE_APPLY`，把这 5 项标成「APPLIED 时必须跳过」，再重新生成 `MIGRATIONS.md` 和 `release-migrations.json`。「APPLIED 时必须跳过」从 41 项变成 46 项 | 修正前，第二遍这 5 项报 3141。修正后，第二遍先跑预检、判 APPLIED 后跳过；原样重跑仍然报 3141，且状态不变 |
| D4 | **DEPLOY.md 写错了几处事实** | 业主照着做可能跳错文件，或者误删生产任务 | ① 第 3 节：替代文件的 step 写成「103、147」，实际是 **100、151**（103 是测试主题下架，147 是微信事实索引）。② 第 1 轮写成「100 项里的 96 项」，第 2 轮写成「27 项」，实际是 **98** 和 **28**。③ 第 10 节商家活动上限引用「108」，实际是 **step 111**（108 是俱乐部签到手机号）。④ 第 12 节把 `imNotificationDeliveryTask`、`roamRewardDeliveryTask` 列为「旧 jar 里没有的」，要求回滚前删掉。实际上这两个 bean 在 `19408d49` 里就有，任务行也由更早的 `20261013_*` 迁移插入，生产上可能已经启用。删掉会丢掉启用状态，前滚只能补回暂停态。现已改为「不要删」并说明原因。①②③ 和 launch 上同期提交 `17de18de` 的修法一致，合并基线时取了 launch 的措辞；第 1 轮写成「103 项里的 98 项；另 5 项必须项在第 3 轮」 | 回滚演练逐个核对迁移后库里每个 `sys_job.invoke_target` 在 `19408d49` 源码里是否有对应 bean：缺 bean 的 9 个和 DEPLOY 的删除清单完全一致，`notListedButMissing` 和 `listedButPresentInOldJar` 都为空 |

随这些修正一起重算的文件：`MIGRATIONS.md`、`release-migrations.json`、`release-precheck.sql`（`gen_release_set.py --head fa7fc0ef… --check` 输出「OK 156 项，产物一致」），以及 `release-manifest.json`（`ci/release_manifest.py manifest --commit fa7fc0ef…`，共 641 个迁移）。DEPLOY 第 0 节写明了重算到的候选头；第 9 节的小程序 tree hash 和 `app.json` blob 也换成了新值（这是分支上 f-* 收口提交带来的变化，不是本线改的）。

## 四、逐轮结果

说明：
- 「行数」是本迁移里 INSERT、UPDATE、DELETE、REPLACE 语句的**匹配行数**之和。PyMySQL 默认开启 FOUND_ROWS，所以 UPDATE 写同值也会计入。库状态是否真的改变，以「重跑改动表」那一列为准，依据是每张表的列、索引、约束加上 `CHECKSUM TABLE` 的全库指纹。
- 「重跑」按 DEPLOY 规则执行：幂等项实际重跑；「APPLIED 时必须跳过」项先跑预检，判 APPLIED 才跳过。
- 「原样重跑」是在克隆库上不跳过、直接再跑一次，只作参考。
- 4 项「不执行」两遍都没有执行：step 12、87、99、150（已被替代）。

重跑时有 3 张表发生变化，变的都是元数据：
- step 32 `theme_categories`：UPDATE 带 `update_time = NOW()`，会命中名称没变的行（25 生活美学，21–23 隐藏行），只刷新 `update_time`。
- step 84 `o14_content_city_code_backfill`：10-29 包已经声明过这一项（`rerunKnownDelta`），回填日志的 `write_time` 会刷新。
- step 107 `settlement_rule_three_percent`：写绝对值，同时刷新 `update_time`。
这三项的业务值都不变。

### 第 1 轮 1_PRE_REQUIRED（jar 前，必须）（98 项）

| step | 文件 | 预检（前→后） | 首跑 | 首跑行数 | 重跑 | 重跑行数 | 错误 / 备注 |
|---|---|---|---|---|---|---|---|
| 4 | `migration_20261022_f7_user_invoice.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 7 | `migration_20261023_creative_square_like_unique.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 9 | `migration_20261023_wx_pay_order_intent.sql` | NEEDED→APPLIED | 成功 | 0 | 预检 APPLIED→跳过 | 0 | 原样重跑：报错 1050 表已存在，状态不变 |
| 11 | `migration_20261024_d_committed_official_notice_wx.sql` | NEEDED→APPLIED | 成功 | 1 | 预检 APPLIED→跳过 | 0 | 原样重跑：报错 1050 表已存在，状态不变 |
| 13 | `migration_20261024_d_npc_asr_intent.sql` | NEEDED→APPLIED | 成功 | 0 | 预检 APPLIED→跳过 | 0 | 原样重跑：报错 1050 表已存在，状态不变 |
| 14 | `migration_20261024_d_npc_asr_shared_quota.sql` | NEEDED→APPLIED | 成功 | 0 | 预检 APPLIED→跳过 | 0 | 原样重跑：报错 1060 列已存在，状态不变 |
| 15 | `migration_20261024_g_before_start_notice_metadata_v2.sql` | NEEDED→APPLIED | 成功 | 0 | 预检 APPLIED→跳过 | 0 | 原样重跑：报错 3141 前置断言拒绝（对象已存在），状态不变 |
| 16 | `migration_20261024_registration_verified_seats.sql` | NEEDED→APPLIED | 成功 | 0 | 预检 APPLIED→跳过 | 0 | 原样重跑：报错 1050 表已存在，状态不变 |
| 17 | `migration_20261024_registration_seat_activity_scope.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 18 | `migration_20261025_coop_invite_request_receipt.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 19 | `migration_20261026_official_event_attendance.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 20 | `migration_20261026_team_event_command_receipt.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 21 | `migration_20261026_theme_attribute_registry_version.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 22 | `migration_20261026_user_invoice_processing.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 23 | `migration_20261026_user_invoice_title_receipt.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 24 | `migration_20261027_template_adoption_receipt.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 26 | `migration_20261028_topic_play_setup.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 27 | `migration_20261029_activity_cancellation_receipt.sql` | NEEDED→APPLIED | 成功 | 0 | 预检 APPLIED→跳过 | 0 | 原样重跑：报错 1060 列已存在，状态不变 |
| 28 | `migration_20261029_topic_cancellation_receipt.sql` | NEEDED→APPLIED | 成功 | 0 | 预检 APPLIED→跳过 | 0 | 原样重跑：报错 1060 列已存在，状态不变 |
| 29 | `migration_20261031_coop_invite_read_time.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 30 | `migration_20261101_author_write_receipt.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 32 | `migration_20261101_theme_categories.sql` | NEEDED→APPLIED | 成功 | 15 | 成功 | 4 | 重跑改动表：sys_category |
| 33 | `migration_20261102_merchant_industry_categories.sql` | NEEDED→APPLIED | 成功 | 10 | 成功 | 0 | — |
| 34 | `migration_20261103_content_safety_recheck.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 35 | `migration_20261103_sensitive_content_library.sql` | NEEDED→APPLIED | 成功 | 4 | 成功 | 1 | — |
| 38 | `migration_20261104_merchant_industry_categories_readback.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 40 | `migration_20261105_chapter_video.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 42 | `migration_20261107_template_adoption_inherited_config_hash.sql` | NEEDED→APPLIED | 成功 | 0 | 预检 APPLIED→跳过 | 0 | 原样重跑：报错 1060 列已存在，状态不变 |
| 43 | `migration_20261108_play_help_run_quota.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 44 | `migration_20261108_registration_seat_invitation.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 45 | `migration_20261108_topic_content_revision.sql` | NEEDED→APPLIED | 成功 | 0 | 预检 APPLIED→跳过 | 0 | 原样重跑：报错 1050 表已存在，状态不变 |
| 46 | `migration_20261109_app_media_provenance.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 47 | `migration_20261109_play_run_feedback.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 48 | `migration_20261110_ecommerce_payout.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 50 | `migration_20261110_platform_pass.sql` | NEEDED→APPLIED | 成功 | 2 | 成功 | 0 | — |
| 51 | `migration_20261111_aigc_filing_config_seed.sql` | NEEDED→APPLIED | 成功 | 1 | 成功 | 0 | — |
| 53 | `migration_20261111_ai_generation_log_retention_index.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 54 | `migration_ai_theme_draft_request_intent_20261024.sql` | NEEDED→APPLIED | 成功 | 0 | 预检 APPLIED→跳过 | 0 | 原样重跑：报错 3141 前置断言拒绝（对象已存在），状态不变 |
| 58 | `migration_20261024_d_chapter_application_round.sql` | NEEDED→APPLIED | 成功 | 0 | 预检 APPLIED→跳过 | 0 | 原样重跑：报错 3822 CHECK 约束已存在，状态不变 |
| 59 | `migration_20261029_club_post_repost.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 63 | `migration_marketing_attribution_20261108.sql` | NEEDED→APPLIED | 成功 | 0 | 预检 APPLIED→跳过 | 0 | 原样重跑：报错 3141 前置断言拒绝（对象已存在），状态不变 |
| 64 | `migration_marketing_product_20261108.sql` | NEEDED→APPLIED | 成功 | 0 | 预检 APPLIED→跳过 | 0 | 原样重跑：报错 3141 前置断言拒绝（对象已存在），状态不变 |
| 65 | `migration_marketing_extra_seat_20261108.sql` | NEEDED→APPLIED | 成功 | 0 | 预检 APPLIED→跳过 | 0 | 原样重跑：报错 3141 前置断言拒绝（对象已存在），状态不变 |
| 66 | `migration_marketing_revenue_allocation_20261108.sql` | NEEDED→APPLIED | 成功 | 0 | 预检 APPLIED→跳过 | 0 | 原样重跑：报错 3141 前置断言拒绝（对象已存在），状态不变 |
| 67 | `migration_marketing_z_channel_voucher_20261108.sql` | NEEDED→APPLIED | 成功 | 0 | 预检 APPLIED→跳过 | 0 | 原样重跑：报错 3141 前置断言拒绝（对象已存在），状态不变 |
| 69 | `migration_20261023_cash_withdraw_fund_fact.sql` | NEEDED→APPLIED | 成功 | 0 | 预检 APPLIED→跳过 | 0 | 原样重跑：报错 1050 表已存在，状态不变 |
| 70 | `migration_20261024_cash_withdraw_transfer_intent.sql` | NEEDED→APPLIED | 成功 | 0 | 预检 APPLIED→跳过 | 0 | 原样重跑：报错 1050 表已存在，状态不变 |
| 71 | `migration_20261025_cash_withdraw_fund_request_compat.sql` | NEEDED→APPLIED | 成功 | 0 | 预检 APPLIED→跳过 | 0 | 原样重跑：报错 3822 CHECK 约束已存在，状态不变 |
| 72 | `migration_member_profile_version_20261022.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 74 | `migration_merchant_category_ids_20261022.sql` | NEEDED→DATA_PREVIEW | 成功 | 2 | 成功 | 1 | — |
| 78 | `migration_merchant_wechat_qrcode_20261022.sql` | NEEDED→DATA_PREVIEW | 成功 | 3 | 成功 | 1 | — |
| 79 | `migration_npc_voice_enrollment_intent_20261024.sql` | NEEDED→APPLIED | 成功 | 0 | 预检 APPLIED→跳过 | 0 | 原样重跑：报错 3141 前置断言拒绝（对象已存在），状态不变 |
| 80 | `migration_npc_voice_confirmed_attempt_20261024.sql` | NEEDED→APPLIED | 成功 | 0 | 预检 APPLIED→跳过 | 0 | 原样重跑：报错 3141 前置断言拒绝（对象已存在），状态不变 |
| 83 | `migration_o14_cms_topic_city_code_20261022.sql` | NEEDED→APPLIED | 成功 | 1 | 成功 | 1 | — |
| 86 | `migration_20261023_d_official_broadcast_delivery.sql` | NEEDED→APPLIED | 成功 | 1 | 预检 APPLIED→跳过 | 0 | 原样重跑：报错 1060 列已存在，状态不变 |
| 88 | `migration_20261028_official_broadcast_protocol_union.sql` | NEEDED→APPLIED | 成功 | 1 | 预检 APPLIED→跳过 | 0 | 原样重跑：报错 1062 守卫拒绝（临时表主键冲突），状态不变 |
| 90 | `migration_orient_score_recovery_20261026.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 91 | `migration_orient_score_sync_receipt_20261026.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 94 | `migration_marketing_redemption_constraint_name_20261108.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 95 | `migration_marketing_promotion_20261108.sql` | NEEDED→APPLIED | 成功 | 1 | 成功 | 1 | — |
| 98 | `migration_preset_draft_source_20261108.sql` | NEEDED→APPLIED | 成功 | 0 | 预检 APPLIED→跳过 | 0 | 原样重跑：报错 1060 列已存在，状态不变 |
| 100 | `migration_public_content_catalog_index_shape_20261109.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 101 | `migration_public_content_catalog_node_topic_index_20261108.sql` | NEEDED→APPLIED | 成功 | 0 | 预检 APPLIED→跳过 | 0 | 原样重跑：报错 3141 前置断言拒绝（对象已存在），状态不变 |
| 102 | `migration_20261023_advanced_command_fingerprint.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 105 | `migration_orient_session_lifecycle_20261022.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 106 | `migration_photo_check_intent_20261023.sql` | NEEDED→APPLIED | 成功 | 0 | 预检 APPLIED→跳过 | 0 | 原样重跑：报错 1050 表已存在，状态不变 |
| 108 | `migration_20261102_club_checkin_phone_visible.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 109 | `migration_club_host_reservation_20261109.sql` | NEEDED→APPLIED | 成功 | 0 | 预检 APPLIED→跳过 | 0 | 原样重跑：报错 1060 列已存在，状态不变 |
| 113 | `migration_20261031_roam_session_track.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 114 | `migration_roam_share_version_20261022.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 115 | `migration_20261024_official_roam_challenges.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 116 | `migration_20261025_official_poi_quotes.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 117 | `migration_20261023_g_cash_evidence_expand.sql` | NEEDED→APPLIED | 成功 | 0 | 预检 APPLIED→跳过 | 0 | 原样重跑：报错 3141 前置断言拒绝（对象已存在），状态不变 |
| 118 | `migration_20261022_theme_state_key_attribute_def.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 119 | `migration_20261022_theme_character.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 120 | `migration_20261028_d20_attribute_registry.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 123 | `migration_topic_location_contract_20261107.sql` | NEEDED→APPLIED | 成功 | 1 | 成功 | 1 | — |
| 129 | `migration_video_bounty_host_tip_20261108.sql` | NEEDED→APPLIED | 成功 | 0 | 预检 APPLIED→跳过 | 0 | 原样重跑：报错 3141 前置断言拒绝（对象已存在），状态不变 |
| 130 | `migration_video_bounty_group_verification_20261108.sql` | NEEDED→APPLIED | 成功 | 0 | 预检 APPLIED→跳过 | 0 | 原样重跑：报错 3141 前置断言拒绝（对象已存在），状态不变 |
| 131 | `migration_20261025_coop_order_station_fulfillment.sql` | NEEDED→APPLIED | 成功 | 0 | 预检 APPLIED→跳过 | 0 | 原样重跑：报错 断言为假，状态不变 |
| 134 | `migration_20261013_admin_role_page_grants.sql` | APPLIED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 135 | `migration_20261016_admin_role_payee_separation_forward.sql` | NEEDED→APPLIED | 成功 | 15 | 成功 | 13 | — |
| 138 | `migration_20261109_play_help_audit_permission.sql` | NEEDED→APPLIED | 成功 | 1 | 预检 APPLIED→跳过 | 0 | 原样重跑：成功，状态不变 |
| 139 | `migration_20261109_questo_admin_menus.sql` | NEEDED→APPLIED | 成功 | 13 | 成功 | 1 | — |
| 141 | `migration_club_post_media_review_budget_20261024.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 142 | `migration_20261024_wx_verified_payment_fact.sql` | NEEDED→APPLIED | 成功 | 0 | 预检 APPLIED→跳过 | 0 | 原样重跑：报错 1050 表已存在，状态不变 |
| 143 | `migration_20261024_clm_commerce_payment_proof.sql` | NEEDED→APPLIED | 成功 | 0 | 预检 APPLIED→跳过 | 0 | 原样重跑：报错 3141 前置断言拒绝（对象已存在），状态不变 |
| 144 | `migration_20261024_coop_original_refund_components.sql` | NEEDED→APPLIED | 成功 | 0 | 预检 APPLIED→跳过 | 0 | 原样重跑：报错 3141 前置断言拒绝（对象已存在），状态不变 |
| 145 | `migration_20261024_coop_negative_margin_manual.sql` | NEEDED→APPLIED | 成功 | 0 | 预检 APPLIED→跳过 | 0 | 原样重跑：报错 1050 表已存在，状态不变 |
| 146 | `migration_20261024_registration_payment_fact.sql` | NEEDED→APPLIED | 成功 | 0 | 预检 APPLIED→跳过 | 0 | 原样重跑：报错 1050 表已存在，状态不变 |
| 147 | `migration_20261024_wx_success_fact_business_index.sql` | NEEDED→APPLIED | 成功 | 0 | 预检 APPLIED→跳过 | 0 | 原样重跑：报错 1061 索引已存在，状态不变 |
| 148 | `migration_x12_topic_look_20261029.sql` | NEEDED→APPLIED | 成功 | 0 | 预检 APPLIED→跳过 | 0 | 原样重跑：报错 1060 列已存在，状态不变 |
| 149 | `migration_z_channel_voucher_snapshot_20261109.sql` | NEEDED→APPLIED | 成功 | 0 | 预检 APPLIED→跳过 | 0 | 原样重跑：报错 3141 前置断言拒绝（对象已存在），状态不变 |
| 151 | `migration_z_marketing_revenue_channel_reverse_kind_index_shape_20261109.sql` | NEEDED→APPLIED | 成功 | 0 | 预检 APPLIED→跳过 | 0 | 原样重跑：成功，状态不变 |
| 152 | `migration_z_workshop_apple_iap_20261109.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 153 | `migration_z_workshop_purchase_20261109.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 154 | `migration_z_workshop_paid_review_20261109.sql` | NEEDED→APPLIED | 成功 | 0 | 预检 APPLIED→跳过 | 0 | 原样重跑：报错 1060 列已存在，状态不变 |
| 155 | `migration_zz_questo_collation_align_20261109.sql` | APPLIED→APPLIED | 成功 | 0 | 预检 APPLIED→跳过 | 0 | 原样重跑：成功，状态不变 |

### 第 2 轮 2_PRE_OPTIONAL（jar 前，可选）（28 项）

| step | 文件 | 预检（前→后） | 首跑 | 首跑行数 | 重跑 | 重跑行数 | 错误 / 备注 |
|---|---|---|---|---|---|---|---|
| 1 | `migration_20261018_infomation_change_audit.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 2 | `migration_20261022_audit_activity_switch_seed.sql` | NEEDED→APPLIED | 成功 | 2 | 成功 | 0 | — |
| 3 | `migration_20261022_f7_minor_guardian.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 5 | `migration_20261022_location_guard_switch_seed.sql` | NEEDED→APPLIED | 成功 | 3 | 成功 | 0 | — |
| 6 | `migration_20261022_merchant_desk_switch_seed.sql` | NEEDED→APPLIED | 成功 | 1 | 成功 | 0 | — |
| 8 | `migration_20261023_points_value_config_seed.sql` | NEEDED→APPLIED | 成功 | 2 | 成功 | 0 | — |
| 10 | `migration_20261024_community_open_flags.sql` | NEEDED→APPLIED | 成功 | 3 | 成功 | 0 | — |
| 31 | `migration_20261101_stagger_sys_job_seconds.sql` | NEEDED→NEEDED | **报错** | 1 | **报错** | 0 | 首跑：断言为假；重跑：断言为假 |
| 55 | `migration_audit_problem_20261022.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 60 | `migration_club_welcome_note_20261022.sql` | NEEDED→APPLIED | 成功 | 0 | 预检 APPLIED→跳过 | 0 | 原样重跑：报错 1060 列已存在，状态不变 |
| 61 | `migration_doc2_f3_bundle_ledger_20261022.sql` | NEEDED→APPLIED | 成功 | 1 | 成功 | 1 | — |
| 62 | `migration_doc2_f3_refund_hold_20261026.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 73 | `migration_member_visited_poi_poi_key_20261022.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 76 | `migration_merchant_desk_receipt_20261022.sql` | NEEDED→APPLIED | 成功 | 1 | 成功 | 1 | — |
| 77 | `migration_merchant_redemption_anchor_20261022.sql` | NEEDED→APPLIED | 成功 | 1 | 成功 | 1 | — |
| 81 | `migration_o14_city_code_backfill_log_20261022.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 82 | `migration_o14_cms_activity_city_code_20261022.sql` | NEEDED→APPLIED | 成功 | 1 | 成功 | 1 | — |
| 85 | `migration_o14_content_search_document_20261022.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 89 | `migration_orient_geo_ar_team_20261022.sql` | NEEDED→APPLIED | 成功 | 5 | 成功 | 0 | — |
| 92 | `migration_20261111_creator_income_detail_idempotency.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 96 | `migration_play_arrive_guard_log_20261022.sql` | NEEDED→APPLIED | 成功 | 1 | 成功 | 1 | — |
| 97 | `migration_pms_sku_booking_20261022.sql` | NEEDED→APPLIED | 成功 | 1 | 成功 | 1 | — |
| 104 | `migration_orient_scoring_20261022.sql` | NEEDED→APPLIED | 成功 | 5 | 成功 | 0 | — |
| 122 | `migration_20261101_prefab_life_template_state_keys.sql` | NEEDED→APPLIED | 成功 | 2 | 成功 | 0 | — |
| 128 | `migration_verification_review_evidence_20261022.sql` | NEEDED→APPLIED | 成功 | 1 | 成功 | 1 | — |
| 132 | `migration_w8_refund_fulfillment_recovery_20261026.sql` | NEEDED→APPLIED | 成功 | 1 | 成功 | 1 | — |
| 133 | `migration_20261026_user_invoice_menu.sql` | NEEDED→APPLIED | 成功 | 6 | 成功 | 1 | — |
| 136 | `migration_20261027_orient_scoring_menu_grants.sql` | NEEDED→APPLIED | 成功 | 5 | 成功 | 1 | — |

### 第 3 轮 3_POST_DEPLOY（新 jar 健康后）（6 项）

| step | 文件 | 预检（前→后） | 首跑 | 首跑行数 | 重跑 | 重跑行数 | 错误 / 备注 |
|---|---|---|---|---|---|---|---|
| 36 | `migration_20261104_content_safety_jobs_post_deploy.sql` | NEEDED→APPLIED | 成功 | 2 | 成功 | 0 | — |
| 49 | `migration_20261110_ecommerce_payout_wiring.sql` | NEEDED→APPLIED | 成功 | 4 | 预检 APPLIED→跳过 | 0 | 原样重跑：成功，状态不变 |
| 52 | `migration_20261111_ecommerce_combine.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 68 | `migration_marketing_task_registration_20261108.sql` | NEEDED→APPLIED | 成功 | 2 | 成功 | 0 | — |
| 93 | `migration_o14_reco_search_jobs_20261022.sql` | NEEDED→APPLIED | 成功 | 2 | 成功 | 0 | — |
| 156 | `migration_zzz_committed_official_notice_job_reregister_20261111.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |

### 第 4 轮 4_OWNER_DATA_PRE（业主授权数据）（18 项）

| step | 文件 | 预检（前→后） | 首跑 | 首跑行数 | 重跑 | 重跑行数 | 错误 / 备注 |
|---|---|---|---|---|---|---|---|
| 25 | `migration_20261027_user_invoice_processing_backfill.sql` | NEEDED→DATA_PREVIEW | 成功 | 1 | 成功 | 0 | — |
| 37 | `migration_20261104_invitee_signup_points_off.sql` | NEEDED→APPLIED | 成功 | 1 | 成功 | 0 | — |
| 41 | `migration_20261105_welcome_points_fifty.sql` | NEEDED→APPLIED | 成功 | 1 | 成功 | 0 | — |
| 56 | `migration_20261102_club_session_gather_point.sql` | NEEDED→APPLIED | 成功 | 1 | 成功 | 0 | — |
| 57 | `migration_20261104_club_session_gather_point_readback.sql` | NEEDED→APPLIED | 成功 | 0 | 成功 | 0 | — |
| 75 | `migration_20261027_merchant_brand_images_to_gallery.sql` | DATA_PREVIEW→DATA_PREVIEW | 成功 | 1 | 成功 | 0 | — |
| 84 | `migration_o14_content_city_code_backfill_20261022.sql` | NEEDED→DATA_PREVIEW | 成功 | 38 | 成功 | 38 | 重跑改动表：o14_city_code_backfill_log |
| 103 | `migration_20261029_unlist_test_topics.sql` | DATA_PREVIEW→APPLIED | 成功 | 2 | 成功 | 0 | — |
| 107 | `migration_20261103_settlement_rule_three_percent.sql` | NEEDED→APPLIED | 成功 | 2 | 成功 | 2 | 重跑改动表：settlement_rule |
| 110 | `migration_20261030_merchant_game_mechanics.sql` | NEEDED→APPLIED | 成功 | 1 | 成功 | 0 | — |
| 111 | `migration_20261104_merchant_activity_limit.sql` | NEEDED→APPLIED | 成功 | 1 | 成功 | 0 | — |
| 112 | `migration_member_visited_poi_poi_key_backfill_20261022.sql` | NEEDED→DATA_PREVIEW | 成功 | 6 | 成功 | 1 | — |
| 121 | `migration_20261101_prefab_life_template_copy.sql` | NEEDED→APPLIED | 成功 | 6 | 成功 | 0 | — |
| 124 | `migration_20261028_remove_wildlife_theme.sql` | DATA_PREVIEW→APPLIED | 成功 | 1 | 成功 | 0 | — |
| 125 | `migration_20261028_template_shelf_list_free_explore.sql` | DATA_PREVIEW→APPLIED | 成功 | 1 | 成功 | 0 | — |
| 126 | `migration_20261030_merchant_game_covers_to_oss.sql` | NEEDED→APPLIED | 成功 | 36 | 成功 | 0 | — |
| 127 | `migration_20261111_rule_seed_split_steps.sql` | NEEDED→APPLIED | 成功 | 2 | 成功 | 0 | — |
| 137 | `migration_20261104_review_wording_seeded_text.sql` | NEEDED→APPLIED | 成功 | 11 | 成功 | 0 | — |

### 第 5 轮 5_OWNER_DATA_POST（业主授权数据，jar 后）（2 项）

| step | 文件 | 预检（前→后） | 首跑 | 首跑行数 | 重跑 | 重跑行数 | 错误 / 备注 |
|---|---|---|---|---|---|---|---|
| 39 | `migration_20261104_topic_total_time_seconds.sql` | NEEDED→APPLIED | 成功 | 1 | 成功 | 0 | — |
| 140 | `migration_20261023_g_wp08_delivery_jobs_paused.sql` | APPLIED→APPLIED | 成功 | 0 | 成功 | 0 | — |

## 五、补充验证：stagger 迁移

`20261101_stagger_sys_job_seconds` 按**写死的 job_id**（102–140，取自 11-01 生产只读预检）改 cron。合成库里的任务编号是按迁移顺序自增出来的，和生产对不上（例如合成库 140 号是 `roamHangoutExpireTask`，`0 0/30`）。所以文件末尾断言「启用中的整秒任务为 0」不成立。断言之前的 UPDATE 已提交。它们都带「仍是原表达式」的条件，而且只改秒位和分钟偏移、不改频率，所以就算编号对错了行，影响也只是那个任务换了一个触发秒。

单独验证：在 `cy_e2e_stagger` 库里，按迁移文件里的 23 个 (job_id, 原 cron) 加上 D12 采样任务 121 造一张生产形态的 `sys_job`，迁移跑两遍，两遍都成功，第二遍零改动。结论：迁移本身没问题。上线时它对应的预检行就是同一条断言，业主跑预检时会先看到它是 NEEDED 还是 APPLIED。

## 六、jar 冒烟（launch jar，所有新开关取默认值）

- 构建：`mvn -q -DskipTests package` 退出码 0，产出 `chengyinhub-admin/target/chengyinhub-admin.jar`（候选头 `4cf14763` 加本线提交，本线没有改任何 Java）。
- 启动：打包内的 `application.yml` 加 `druid` profile，只用命令行覆盖了数据源 URL（指向终版演练库 `cy_e2e_frehearsal_final`）、用户名、端口和上传目录，其余配置全部取默认值：通行证、电商收付通、快速提现、营销现金、工坊支付、商家自助收银、漫游召回都关着。必填的 6 个环境变量用本机随机值注入（`CHENGYIN_DB_PASSWORD`、`CHENGYIN_JWT_SECRET`、`CHENGYIN_DRUID_PASSWORD`、`CHENGYIN_WITHDRAWAL_PII_KEY`、`CHENGYIN_WXMINI_SECRET`、`CHENGYIN_WXPAY_APIV3_KEY`）。
- 结果：`Started RuoYiApplication in 19.648 seconds`。按 DEPLOY 第 5 节的健康检查关键字查启动日志：`Unknown column`、`doesn't exist`、`Check constraint`、`NoSuchBeanDefinitionException`、`Illegal mix` 都是 0 条。ERROR 只有两类，都和 schema 无关：微信支付证书文件不存在（本机没有证书），告警 webhook 未配置。
- 登录：走真实的 `/api/login/phone`，短信验证码夹具写进本机 Redis（和 `scripts/e2e/chain_lib.app_token` 相同）。新建的玩家拿到 **50** 积分，说明第 4 轮 `welcome_points_fifty` 已经生效。
- 冒烟前的准备（只写演练库）：把两个合成主题设为上架；给登录玩家插一条已支付的自玩报名；给 `published_config_version` 置 1，让 `/api/play/nodes` 走到节点和会话查询。准备语句在 `smoke.py` 的说明里。

| 接口 | HTTP | 业务 code | 说明 |
|---|---|---|---|
| `POST /api/login/phone`（新建玩家） | 200 | 200 | |
| `POST /api/user/info` | 200 | 200 | |
| `POST /api/topic/list`（全部 / 推荐 / 随时开走） | 200 | 200 ×3 | 首页主题流 |
| `POST /api/topic/info-to-user`（游客 990059 / 登录 990059 / 登录 98400） | 200 | 200 ×3 | 主题详情 |
| `POST /api/public-catalog/list`、`filters`、`city/list`、`info` | 200 | 200 ×4 | Questo 公开目录（主题库） |
| `POST /api/activity/list` | 200 | 200 | 首页活动流 |
| `GET /api/play/nodes`（990059 / 98400） | 200 | 200 ×2 | 游玩节点 |
| `GET /api/play/content-manifest` | 200 | 200 | |
| `POST /api/wallet/balance`、`stages`、`withdraws` | 200 | 200 ×3 | 钱包 |
| `GET /api/platform-pass`、`/library`、`/orders` | 200 | 200 ×3 | 通行证状态（开关关闭） |
| `GET /api/play/journey/backpack`、`GET /api/play/run-session/list` | 200 | 200 ×2 | |
| `GET /api/play/journey/casebook` | 200 | 500「这一局还没开始」 | 业务前置条件，不是 schema 问题 |
| `POST /api/project/home`（无主题 / 带主题） | 200 | 500「缺少主题」/「你既不是该主题的发布者…」 | 这是创作者项目页的归属校验，不是 schema 问题 |

27 个调用的响应体和这段应用日志里，**0 条** `Unknown column`、`doesn't exist`、`Illegal mix of collations`、`BadSqlGrammar`、`SQLSyntaxErrorException`、`Check constraint`、`Data truncation`。

## 七、回滚演练（DEPLOY 第 12 节能脚本化的部分）

| 项 | 做法 | 结果 |
|---|---|---|
| 删除清单是否完整 | 对迁移后库里 36 行 `sys_job` 的每个 invoke_target，在 `19408d49` 源码里找 bean | 缺 bean 的 9 个和 DEPLOY 删除清单完全一致（修正 D4 之后） |
| 删除 → 前滚恢复 | SELECT 清单行 → DELETE（9 行）→ 重跑 step 36、49、68、93、**156** | 9 个任务全部恢复，全部是暂停态 `status='1'`，没有报错（修正 D1 之前只回来 8 个） |
| `20261029_unlist_test_topics` 文件头的回滚语句 | 执行回滚 UPDATE，再重跑迁移 | 回滚恢复 2 行（id 1、8）。id 6（有已支付报名，被守卫跳过）和 id 7（member_id 不吻合）没被动过。重跑后回到已应用状态 |
| `20261028_remove_wildlife_theme` 文件头的回滚语句 | 同上 | 回滚恢复 1 行，重跑后回到已应用状态 |
| 旧 jar 在删掉任务后能否启动 | 没做 | 本线没有构建 `19408d49` 的旧 jar。删除清单的正确性用上面的 bean 逐个核对来代替 |
| 开关回退、不删表、不覆盖备份、小程序回退 | 没做 | 这些是操作规程，不能脚本化 |

## 八、只报告、不在本线修改范围

1. **代码问题（不是 schema）**：`PublicContentCatalogServiceImpl.chapterNames` 在章节 `name` 为 NULL 时抛 NPE，`/api/public-catalog/info` 返回 500，报错为 `Cannot invoke "java.util.Map.get(Object)" because "row" is null`。原因是 MyBatis 对全 NULL 列的行返回 null map。`cms_topic_chapter.name` 允许为 NULL，合成库的章节一开始没有名字，就触发了。生产上如果有无名章节，也会这样。建议在循环里跳过 null 行，由主题详情线的 owner 处理。
2. **已有 CI 测试过期**：`ci/test_release_migration_rehearsal.py::test_pack_matches_head_release_set` 在本线的基点 `4cf14763` 上就失败。它要求 10-29 包和 HEAD 的发布集完全一致，但 10-29 之后已经新增了 67 项。不是本线引入的。要让 `release-migration-rehearsal.yml` 演练当前这 156 项，需要按 f-release RESULT 第 4 条把新项的元数据补进 pack 契约，这超出了本线范围。本线的 `rehearse.py` 直接读 `release-migrations.json`，算是替代方案。
3. **环境说明**：AGENTS.md 规定「Mac 上不起 MySQL、Java 构建或测试」。本次 MySQL、Redis、Maven 构建和 jar 都只在一次性云端沙箱里运行，没有碰 Mac、VPS 或生产。

## 九、复现

```bash
# 本机 MySQL 8.0（127.0.0.1:3316）与 Redis（127.0.0.1:6379）就绪后：
export CHENGYIN_REHEARSAL_MYSQL_PASSWORD=<本机一次性口令>
python3 docs/delivery/questo-ui-20261109/f-rehearsal/rehearse.py --schema cy_e2e_frehearsal_final --report /tmp/final.json   # 约 30 分钟
LANG=C.UTF-8 mvn -q -DskipTests package
# 用默认配置启动 jar，只覆盖数据源 URL/用户名、端口、上传目录，再执行：
python3 docs/delivery/questo-ui-20261109/f-rehearsal/smoke.py --base http://127.0.0.1:18080 --log <app.log> --report /tmp/smoke.json
python3 docs/delivery/questo-ui-20261109/f-rehearsal/rollback.py --schema cy_e2e_frehearsal_final --report /tmp/rollback.json
python3 docs/delivery/questo-ui-20261109/f-release/gen_release_set.py --head <含本线提交的 SHA> --check
```

本线改动文件：
- 新增：`chengyinhub-admin/sql/migration_zzz_committed_official_notice_job_reregister_20261111.sql`、`f-rehearsal/{rehearse.py,smoke.py,rollback.py,seed_rows.sql,RESULT.md}`
- 修改：`ci/migration-dependencies.json`（+1 行）、`ci/migration_ledger.py`（把 `seed_rows.sql` 登记为 AUXILIARY_NOT_EXECUTED，+2 行）、`f-release/gen_release_set.py`、`f-release/DEPLOY.md`，以及重新生成的 `MIGRATIONS.md`、`release-migrations.json`、`release-precheck.sql`、`release-manifest.json`

门禁：
- `ci/test_migration_ledger.py` 56 项 OK，`ci/test_migration_gates.py`、`ci/test_release_manifest.py`、`ci/test_run_migration.py`、`ci/test_migration_runner_ci_contract.py` 全部 OK。
- `ci/test_release_migration_rehearsal.py` 有 1 项失败，基点上就已失败（见第八节第 2 条）。
- `migration_ledger.py manifest --target-commit <本线 HEAD>`：641 个迁移，0 个问题。
- `forbidden_hosts_gate.py` 通过。

STATUS: DONE — 156 项发布迁移在一次性 MySQL 8.0 上按 DEPLOY 轮次两遍落库（唯一断言失败 stagger 已证实为合成库编号差异），jar 冒烟零 schema 错误，回滚可脚本化部分通过；修正 4 个缺陷（新增 1 个前滚补登记迁移、预检误判 CONFLICT、5 项重跑规则、DEPLOY 事实错误），1 个代码 NPE 与 1 个过期 CI 测试待对应 owner 处理。
