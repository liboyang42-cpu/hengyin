# 2026-10-29 走查修复：lane 共用规则

基线分支：`integrate/device-walkthrough-20261029`，从 master `37181a41` 切出。每个 lane 从它切出 `feature/dw29-<lane>`，开 draft PR 回这个基线分支，**不要**直接对 master 开 PR。

先读 `修复实施文档.md` 和自己 lane 对应的截图（`shots-v2/`、`shots-merchant/`），再改代码。

## 必守

0. **最小改动**：只改走查条目点到的地方。现有 UI 就是基线：不要重排、不要换风格、不要「顺手优化」。业主说「删掉」的就删掉，说「还原」的以 git 历史版本为准恢复。
1. 文案：不加副标题和说明小字（业主规则：不要 secondary labels）。用词按「帖文、节点、管理员、商家」。没有数据显示「—」，营销页显示 0。后端回传的提示语一个字都不能改。
2. 商家视角用浅色，玩家视角用深色。
3. 不要连生产库，不要部署，不要提交任何密钥。腾讯地图 key 不能进仓库。不要启用生产 workflow。
4. **不要运行 `ci/xcx-check.sh` 和 `ds-hardcode-gate-selftest.sh`**，它们的 selftest 会往仓库里写夹具和提交。
5. 新表结构改动写成 `chengyinhub-admin/sql/migration_20261029_*.sql`，并在 `ci/migration-dependencies.json` 里登记。不能修改或删除已有的迁移。
6. 验证：
   - 每个改动都要有单测：`node --test chengyinhub-xcx/tests/unit/<相关>.test.js`，没有就新增。
   - 改了 Java 就跑对应模块的测试（`mvn -q -pl <模块> -Dtest=... test`，注意 `@Nested` 要同时选 `Outer$Nested`）。
7. 门禁：
   - 改动让 `chengyinhub-xcx/scripts/uiaudit/action-ledger.json` 漂移时，用 `node chengyinhub-xcx/scripts/uiaudit/build-action-ledger.js --write` 重建，再审阅 diff。
   - `chengyinhub-xcx/tests/lint/ui-integrity-baseline.js` 授权**有限重锚**：
     - 既有条目因为行号位移可以换 key；
     - 被删掉代码对应的条目要删除，棘轮只能下调；
     - **不准新增条目**，需要新增就改代码让它不需要登记；
     - keyDigest 用门禁自己的 `baselineKeyDigest`（对排序后的 key 用 `\n` 连接，再做 sha256）重算；
     - 在文件顶部按现有格式加一行日期注释。
   - 门禁自检：`node chengyinhub-xcx/tests/lint/ui-integrity.js` 必须是 `UI-GATE-0:PASS`。
8. 开向基线分支的 PR 不会自动触发 CI。完成后在自己的分支上手动触发：`gh workflow run ci.yml --ref feature/dw29-<lane>`，并把结果链接写进 PR。失败的检查要修到绿，只有当失败与本 lane 无关、在 master 上同样失败时才可以说明后跳过。
9. commit 用中文加类型前缀（feat/fix/chore/docs），与现有 git log 一致。PR 描述写清每个走查编号：改了什么、在哪个文件、怎么验证。
10. 做不了的条目（例如需要业主配置、或需要线上数据）不要硬做。在 PR 里列出来，说明原因，以及需要业主做什么。
