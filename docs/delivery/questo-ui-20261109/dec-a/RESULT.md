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


## 第二轮：合并 #808 后的冲突处理（c035feae）
- 将 origin/master（含 #808 integ/decisions-20261112，c035feae）以 merge 提交合入分支，未强推；推送 fe77acc8。
- 唯一冲突：`ci/migration-dependencies.json`。保留双方：本分支的 `20261112_template_usage_count`、`20261112_template_market_status`、`20261112_template_author_title_unique` 与 master 的 `20261112_chapter_reward`。
- 重新生成：action ledger `--write` 后 `--check` 通过（6027 控件）；route matrix `--check` 通过（171 路由 / 27 场景）；ui-integrity 基线未新增任何条目。
- 本地门禁：根目录小程序全量单测 17539 条全部通过（首次因合并未提交导致 `action-ledger.test.js` 的 HEAD 摘要对比失败 1 条，提交合并后复跑通过）；UI-GATE-0 自证与全仓扫描均 PASS。
- 未改 ci/architecture/*、.github/workflows/*，未新增 business.service 类，未改已有 Mapper 语句 SQL，未改任何依赖。
- CI（fe77acc8）：除「SBOM + dependency scan」外全部通过（含 Backend tests、Funds MySQL IT、20 个小程序单测分片、迁移门禁、Secret scan）。

## SBOM Critical/High 清单（4 条）
- 失败日志：`Critical/High=4`，Grype 0.110.0，扫描 447 个包，门禁 `--fail-on high`。
- 无法列出逐条明细：本环境网络策略拒绝访问 GitHub 产物存储域名（`productionresultssa15.blob.core.windows.net` 返回 403），`gh run download` 无法下载 `chengyin-dependency-scan-1`（run 43312466264，artifact 13985648779，文件 chengyin-dependency-scan.json）；作业日志与检查注释只含总数，不含条目；本环境也没有 Grype 与漏洞库。
- 因此严重度、漏洞编号、包名、已装版本、修复版本及引入模块均未获取，不做猜测。
- 处理建议：在能访问产物存储的机器上执行 `gh run download 43312466264 -R liboyang42-cpu/hengyin -n chengyin-dependency-scan-1`，或放开该域名后重跑本任务。本 PR 未改任何依赖。

STATUS: PARTIAL - 除 SBOM 外检查全部通过；4 条 Critical/High 明细因产物存储域名被网络策略拦截（403）无法获取
