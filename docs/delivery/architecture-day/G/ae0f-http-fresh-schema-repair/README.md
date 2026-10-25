# HTTP fresh-schema 旧 C/v2 重复入口修补

Parent：`ae0f1dbd4df2d65befd9214ffdeb20d7042b9d1e`。独立工作树 `/workspace/hengyin-g-ae0f-http-notice-forward`，分支 `codex/architecture-day-g-ae0f-http-notice-forward`。原环境一度 No usable execution environment；更新 workspace 上下文后恢复，只读确认原 G 工作树仍完整，再从公共 ae0f 建本工作树；没有重建环境/reset/覆盖旧未交树。

## 实际失败与证据边界

root 已读回 run40147612418 三个 job121032222026/121032222161/121032222201 的 artifact：均在 HTTP 前 schema-apply 失败，v2 fileIndex56/MySQL3821/line2；JavaExit98 是未启动哨兵，xmlVerified=false；清理独立确认。原 stderr 没有公开，具体3821约束名/运行时细项保持 UNKNOWN。本片不把此归为应用崩溃或已证明某具体约束失败。

ae0f 的这个调用者仍自行只用 ID/path 拼 inventory 后 `_ordered_migrations`，把旧 C 和互斥 v2 两份 DDL 都放进 files；它并未调用已交付的精确替代合同。这是源码已证的调用者遗漏，不是严格 auth builder selector 回归。

## 源码变更

只改 generic-gameplay-fresh-schema.py 与其直接测试：

- `build_plan` 改用生产 `build_manifest` 的完整 canonical 字段；调用既有 `migrations_after_baseline` 校验 source/baseline/data-only custody，再用既有 `notice_forward_plan` 校验/选择精确 pair。不复制第二套 hash/依赖/排序算法或修改该共享合同。
- HTTP 原 included 分类、data-only 文件及所有其他文件相对序保留。canonical custody 的返回过滤列表不拿来删除这些 data-only 文件，它们仍以原 skip 理由/SQL/计数保留。唯一不再放进 files 的是未执行旧 C；它不伪装 baseline/applied/data-only，不放入 completed。
- dry-run 只报告 `PLANNED_UNAPPLIED`；execute 在任何 MySQL 调用前用同一 build_plan 重建并匹配 pair/manifest/files/apply/skip/计数，拒绝缺记录、旧文件重新插入、计划或回执伪改。
- 原 MySQL8/loopback/新 cy_e2e_* 检查保留。schema 已存在则拒绝；不提供 DROP；CREATE 没有 IF NOT EXISTS，并发 CREATE 失败立即传播，不接管、不导入、不记 completed。只有原 CREATE 成功后才报告 `REPLACED_UNAPPLIED old C→v2` 和共享合同 fingerprints。
- 原文件处理/事务 delimiter/错误传播不变。成功只把实际选择并处理的 files 记入 completed，失败文件及旧 C 都不记入；skip 记录不会在执行过程中被改写。

没有改旧 SQL/断言、生产 runner、scripts/e2e/build_schema.py、DAG、F 的 ci/test_http_e2e_entry.py 或 shell。没有新依赖、新环境或 CI dispatch。

## 本 helper 的原验证边界保留

这仍是 HTTP 的 **DDL-only fixture**：迁移 runner assertions、backfill/cleanup/seed 的原 skip 策略没有扩大或转成“已严格通过”，baseline assertions 仍按原行为执行并拒绝 false。`completed` 是该 fixture 的文件处理记录，必须连同 skipped 阅读，不是生产迁移 applied ledger。

4da9 的严格 auth/v2 成功证据仍只归属原源，不转签到本 HTTP 候选。本片没有执行 MySQL/HTTP、没有读取或猜测原3821 stderr。

## 实际 Python 控制

原本文件17个测试方法/断言均保留；新增 NoticeForwardCallerTest 9个普通方法，源码总声明26。此次仅运行直接相关的新增9个和原 FreshSchemaTest 12个，**9/9（12.966s）+12/12（2.993s）PASS**；其余5个 MigrationGuardTest 未重复运行。命令及观察结果在 PYTHON_CONTROLS.txt。

新增控制覆盖：真实全 canonical manifest 中精确 pair 只去旧 C；其他文件顺序/source fingerprint/apply/skip 完全相同；缺 old/v2/D、manifest哈希/依赖/顺序漂移；任一冻结文件字节漂移；baseline/data-only custody；旧文件/SQL/skip/计数/状态/completed 伪改；已有库拒绝；并发 CREATE 不接管；传输成功时 completed 与 files 精确对应；文件失败时不记完成且保留 skip。

Python 传输控制使用 subprocess double，不运行 mysql 客户端，不代表真实 schema/HTTP/严格生产迁移通过。Java/MySQL/Redis/HTTP/CI 均 **NOT_RUN**；root 收齐后一次新目标验证。原所有拒绝、UNKNOWN、baseline断言、权限和清理行为未为迎合测试放宽。
