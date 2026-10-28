# 发布迁移包（release-closeout-20261028）

本包为 2026-10-28「先部署新后端、再上传小程序体验版」准备发布顺序、只读预检与隔离演练。**全部生产执行状态保持 UNKNOWN**；参考基线不等于生产已应用状态。本任务不连接生产、不执行生产 SQL、不部署。

机器契约为 [release-migration-pack.json](release-migration-pack.json)，预检为 [release-migration-precheck.sql](release-migration-precheck.sql)，owner 操作清单为 [release-steps.md](release-steps.md)。演练使用现有 `ci/release_migration_rehearsal.py`、`ci/test_release_migration_rehearsal.py` 及 `.github/workflows/release-migration-rehearsal.yml`（现指向本包；10-22 包原样保留为历史记录）。

## 发布集口径

- 生产参考基线仍是 10-22 包的 `19408d49`（Merge #452）。10-22 包（源 `11c8055e`）列出的 35 项**从未在生产确认执行**，所以本包把它们与 `11c8055e` 之后合入 master 的 25 项合成一个发布集：`release_manifest.migration_diff(19408d49, 源提交)`，共 60 项（新增 59、修改 1、删除 0）。
- 10-22 那 35 项的阶段、守卫、风险、回读与演练状态契约原样沿用（见 10-22 包），执行序按源提交的全量拓扑序重算。
- 新增 25 项对应 PR #655 正文列出的 17 个（#625 W8 收口 15 + 文档二 L8 2）、#641 的 `20261027_merchant_brand_images_to_gallery`，以及 10-23 之后其他已合入 PR 带来的 7 个（`20261023_wx_pay_order_intent`、`20261024_cash_withdraw_transfer_intent`、`20261024_community_open_flags`、`20261024_official_roam_challenges`、`20261025_official_poi_quotes`、`20261028_remove_wildlife_theme`、`20261028_template_shelf_list_free_explore`）。#661 / #640 没有带迁移。

<!-- BEGIN GENERATED RELEASE INVENTORY -->
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
3. 逐项检查 `expectedAtBaseline`，严格按序执行全部 60 项，任何错误或断言失败立即停止。
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
