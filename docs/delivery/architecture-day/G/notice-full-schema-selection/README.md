# 本轮旧 C 首条失败：strict fresh scratch 替代接线

Parent/source：`c86d212f66b92ed1af2c0c661d68169ddc9c48a5`。独立公共工作树 `/workspace/hengyin-g-notice-canonical-preflight` / 分支 `codex/architecture-day-g-notice-canonical-preflight`。没有修改旧 SQL、DAG、B 的 probe/cleanup 或其他 owner。

## 已确认 / 未确认

本轮 run40144172218/job121021893563 仍是 FAILED_BEFORE_APP。root 已读回原 artifact12580135669 的唯一失败：旧 `20261024_c_before_start_notice_typed` #1 OperationalError3141。没有本轮九个分项的实际值，全部维持 UNKNOWN；不能仅凭该 JSON_EXTRACT 拒绝分支错误认定具体缺列、缺 CHECK 或 JSON 解析器 BUG。这里仅读取了完成 job 的文本日志，没有冒充原 artifact/XML 读回。

当前 DAG 已明确是 D→旧 C、D→v2，两条边都存在，不应再添加假前置。纯 Python 对完整 canonical manifest 和真实 `migrations_after_baseline` 的本轮执行观察：

|ID|manifest order（1 起）|baseline 过滤后 selectedRank（0 起）|
|---|---:|---:|
|20261024_d_committed_official_notice_wx|55|46|
|20261024_c_before_start_notice_typed|56|47|
|20261024_g_before_start_notice_metadata_v2|59|50|

全 inventory=547，selected=260（再剔除既有3个POST_DEPLOY即本轮257）。三个条目都仍待回放，旧 C 先于严格 v2 被执行。

`build_schema.py:migrations_after_baseline` 末尾只剔除 included/data_only，未选择这对互斥替代；main 随后直接循环 ordered 执行。旧 C/v2 均不在 baseline manifest 或历史 data-only 分类中。此前交付的 v2 SQL 文件头明确要求以 v2 **替代未执行/首条失败的旧 C**，不是“旧 C→v2”两次 ALTER；实际 selector 漏了该执行计划接线。

旧 C 指纹 `615c8c7883a97ca70e1cc14ff4ac965a895db9ccfea91f6f08e269dbf99e889a`；v2 指纹 `a36889f13d1de308edfcba06409ef153438521268f355881006864ae842bc02c`。本轮纯 Python 静态对比确认：v2 除说明头和4处 metadata `反斜杠+单引号` 精确规范化外，原 12 列/3 CHECK/enforced/冲突/旧数据/DDL/typed 后检逐字保留。

先前 run40127844664/job120973091452 对同一旧 C/D 字节的 MySQL8.0.45 实测，曾确认只有 CHECK 文本匹配=0，owner/state metadata 存在 ASCII92,39 转义；原证据保留在 `docs/delivery/architecture-day/G/notice-metadata-v2/DIAGNOSTIC_EVIDENCE.txt`。这解释已存在 v2 的修复目标，不冒充本轮分项读回。

## 原最小方案（498b 先交证据；随后 root 已明确授权以下实现）

仅在 `scripts/e2e/build_schema.py` 的 fresh scratch 选择入口处理这一对精确替代：

1. 原完整 manifest/547 inventory、baseline provenance、source custody、data-only policy 均保留；不把旧 C 伪记 baseline/applied/data-only，不把它移出通用生产台账。
2. 固定原 C/v2/D 的确切 ID/path/上述两 SHA256，验证原两项都在 pending、v2 确实依赖原 D、完整拓扑先 D 后 v2；缺项、字节漂移、前置缺失、选中集不明都 fail-closed。
3. 仅在已有鉴权 harness 已核验的全新空 scratch schema 执行计划中，用完整 v2 取代旧 C 的执行；保留所有其他迁移原相对顺序，并明确打印 REPLACED_UNAPPLIED: old C→v2（不叫已应用）。builder 必须拒绝把该选择用于复用/已 typed/状态不明的库；已有 B 的“schema 不存在”核验不删、不反向绕过。不改 B 文件。
4. v2 原首条必须在 D 的真实12列原形状上完整执行，仍由原 runner 解析/求值；旧数据检查/ALTER/后检不变。已有 typed 库只读核验并阻断重复 ALTER，没有 fallback/自动 reset/DROP。
5. 在既有 `scripts/e2e/test_build_schema.py` 补直接控制：合法精确 pair 只执行 v2且其他项不变；缺 v2、任一哈希漂移、D依赖漂移、非 fresh/已 typed 均拒绝；替代选择发生在DB写入之前，不 mock schema gate 成功。root 串行协调共享 builder 符号和必要既有 fixture。

单纯给旧 C 添加 v2 前置也不能修：v2 完成后旧 C 的12列前检会拒绝；给 v2 加旧 C 前置则仍先撞当前失败。不能靠 DAG 调整让两份互斥 ALTER 都执行。

若 root 仍需本轮分项取证，复用既有 `docs/delivery/architecture-day/G/ca0e-notice-commerce-repair/notice-preflight-readonly-diagnosis.sql` 的9谓词及CHECK元数据 SELECT，在原旧 C #1前只读采集。保持原失败、无retry/no-skip，仅同一已有harness；不需要另造schema/表或扩大豁免。本分支没有自发诊断CI。

## 实际验证范围

一次必要纯 Python 控制已实际 PASS：真实完整 manifest/selector 同时选择 D/旧 C/v2且旧 C先执行；v2 严格源码等价性比对PASS。完整输出见 SOURCE_SELECTION.txt。

以上为498b证据阶段的静态定位；随后 root 授权的实现与定点结果见下节。Java/MySQL/Redis/CI 仍未运行，本轮 schema/鉴权恢复尚未验证，不当业务验证完成。


## 已授权实现与本次交付

实现 parent：`498b535da27568845950b1654aa4985c2c0905db`（仅 docs 的 c86d 子提交）。沿同一独立分支继续，没有 reset/rebase/回退他人修改。

仅编辑 `scripts/e2e/build_schema.py`、`scripts/e2e/test_build_schema.py` 和本目录。未改 B/DAG/旧 SQL/通用生产 runner、baseline/data-only/source-custody 文件。

- `notice_forward_plan(migrations, selected)` 在连接 DB 前核验固定 old C/v2/D 的 ID/path/SHA256、唯一性、manifest 与 pending 同一完整条目、原依赖、原 manifest order 及相对顺序、三者均 pending。D 的原 IM 前置也须存在并排在它之前。缺项、重复、文件/manifest 漂移、POST_DEPLOY 误分类、顺序/依赖变化都失败；不含 old/v2 的原入口直接不启用此特例。
- 本 pair 必须先只读得到当前目标 schema 的严格 count=0，NULL/畸形读回也拒绝。存在的任意 schema（包括已 typed）即使指定 --drop 也在 DROP/CREATE/import 前拒绝。
- 使用精确 `CREATE DATABASE`，不用 IF NOT EXISTS；并发创建造成失败时不接管、不导入。只有 CREATE 明确成功后才采用去掉 old C 的执行计划，打印 `REPLACED_UNAPPLIED old→v2` 和三文件 fingerprint；不伪记 baseline/applied/data-only。保留完整547 inventory、其余顺序和3个 POST_DEPLOY 延后项。
- v2 仍由未修改的 `run_file` 解析并执行原 preflight/DDL/后检；本 pair 的 v2 强制 keep_going=False，失败立即停，不能借 --keep-going 越过前检执行 ALTER。无 pair 时原 --drop/--keep-going/失败传播/关闭连接语义保持。

### 直接 Python 控制已实际执行

命令与结果保存在 PYTHON_CONTROLS.txt：9 个新方法 + 5 个直接相关既有控制，共 **14 tests，1.856s，OK**。没有失败测试被改门槛或删断言，没有运行完整无关套件。

新控制涵盖：精确真实 canonical pair/其余条目与 phase 相对序不变；无 pair 保持原入口；缺 old/v2/D、重复或 pending 漂移；每个文件的字节漂移及每个 manifest ID/path/hash/phase 漂移；D/old/v2 依赖或全局/pending 顺序错误；非 fresh/NULL/畸形读回含 --drop；并发 CREATE；fresh 入口经过**真实 run_file 的 v2 原首条 SQL**注入受控 cursor 失败，确认无 ALTER、保留原失败且 --keep-going 也不继续。其他文件只用于路由 double，绝不把这些 Python mock 当作 MySQL gate 成功。

在旧源码已确认的 selector 260 项基础上，本 pair 的 fresh 候选仅去掉未执行 old C 一项，其他 phase 不变。实际完整 schema 重放、MySQL/Redis/应用启动/鉴权恢复均 **NOT_RUN**，只有 root 收齐后执行新目标。此次没有读取或编造本轮旧库九谓词实值。
