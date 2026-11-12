# dec-a 接手结果（PR #804）

## 各决策完成情况
- D-108：通行证覆盖全部已发布主题，由可配置规则 `platform-pass.coverage-topic-ids` 控制（留空＝全部，非空＝精确收窄）。
- D-113：游戏主题库读取公开目录，独立于通行证开关；已含运行时用例。
- D-121：AI 对话失败、超时、空内容时返还额度，复用原 pending/fence/Redis 补偿，重复通知不重复返还。
- D-89：模板新增真实使用数列 `use_num`，按首次采用人数回填并排序。
- D-90：`cms_template_library.status` 作为单一状态字段，同作者未删除模板标题唯一。
- 迁移文件：`migration_20261112_template_usage_count.sql`、`migration_20261112_template_market_status.sql`、`migration_20261112_template_author_title_unique.sql`。

## 接手工作
- 将最新 origin/master 合并进分支（merge 提交，无冲突，未强推），推送 aef3ea66。
- 「Fast gates」失败原因是分支落后于 master，CI 检出既不是 PR head 也不是其精确合并提交（fail-closed）。合并 master 后该检查通过。未改 ci/architecture 与 .github/workflows，没有新增架构边界违规，也没有改动已有 Mapper XML 语句。
- 「SBOM + dependency scan」：本分支相对 master 没有任何 pom/package/lock 变更，无新增依赖可回退。它只是因为 `ci/test_ci_cost_contract.py`（合法加强的测试）命中了依赖路径过滤器才被触发；Grype 报告 4 条 Critical/High，来自漏洞库更新后对既有依赖的新披露。本环境无法访问漏洞库或扫描产物，无法定位具体条目，且不应通过改依赖或豁免来掩盖。

## 本地门禁
- 小程序单测（根目录运行 `TZ=Asia/Shanghai node --test chengyinhub-xcx/tests/unit/*.test.js`）：17519 通过，0 失败，0 跳过。
- UI-GATE-0:PASS，无新增基线。
- action ledger `--check`：PASS（6025 控件）。
- PR 正文已含 `## 影响分类` 与 `- ③ 迁移：` 并列出三个迁移文件。

## CI 结果
- 其余检查在 aef3ea66 上通过；Backend tests 在写本文时仍在运行。
- SBOM + dependency scan 仍失败（见上），需要业主决定升级受影响依赖或评估后豁免。

STATUS: PARTIAL - SBOM + dependency scan 因漏洞库新披露的 4 条 Critical/High（既有依赖，非本 PR 引入）失败，需业主决定处置
