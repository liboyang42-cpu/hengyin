# F：架构增量门禁与测试回读

这是轻量 Python 标准库门禁，不运行 Java/数据库、不解析 Java 字节码，也不把 SQL 正则提示当成安全证明。生产合同尚须相应领域 owner 审阅及受控测试。只接收完整提交 SHA。

## 固定存量和 owner

`baseline.json` 由公共提交 `2ec68899099a1e250df0949a8383bf85b5d3ffe0` 的 git blob 生成：7 个 POM 的 94 个 dependency 声明、2,948 条 Mapper 语句，以及表/字段写入口提示。生成数据约 1.38 MB，一条语句一行，便于精确 diff；不要手工编辑。

存量例外是 `path#id + statement SHA256 + 传递 include 指纹`，不是目录、类名或危险关键词通配白名单。跨 namespace 的 include 按定义片段的 namespace 递归解析。解析失败、循环引用、动态标签、`${}`、复杂形状保留 UNKNOWN。存量存在不等于存量安全；其中 396 条写语句为 UNKNOWN。

`owners.json` 解释模块职责；`baseline.json.observed_table_field_writers` 列出 384 个表名提示及其 Mapper namespace 技术写入口，23 个表存在多个入口。`column_hints` 仅从 SET 左值及 INSERT 列表提取，可能不完整；DELETE 的空列列表不是无副作用。技术写入口不授予业务授权、不代替业务领域 owner。跨模块以外，同模块新增表写入口也需审阅。无法解析的表/字段不猜归属。

`commands.json` 是四条高风险调用链的合同/恢复源/现有测试清单，不是“已验证安全”注册表。所有新生产文件变化保守标为 UNKNOWN，防止 JDBC、注解 SQL、反射、外部调用或 afterCommit 绕过只看 XML 的门禁。不禁止所有 afterCommit；审阅其持久恢复源及可达恢复入口。

## 可执行检查

在受信任的独立 gate checkout 执行，`--source` 指向待验候选；不要执行 PR 自己修改的 verifier。候选必须 HEAD 等于给定 SHA、工作树干净，并包含固定公共基线。baseline 每次从固定提交重算比较，不能用候选的新违规重建 baseline。

```sh
python3 gate/ci/architecture/gate.py source --source candidate --candidate "$SOURCE_SHA"
python3 -m unittest discover -s gate/ci/architecture -p 'test_*.py' -v
```

静态检查有 findings 即退出 1，无 findings 仅返回 `STATIC_ONLY`，不宣称 Java/业务测试通过。

| 规则 | 阻断条件 | 正常/违规自测 |
| --- | --- | --- |
| DEPENDENCY_EXPANSION | Maven dependency 新增/改变版本、scope、管理或插件内声明；保留重复项计数 | unchanged、new_dependency、dependency_removal |
| NEW_TABLE_WRITER | 表名提示出现新模块/namespace 写入口 | unchanged、new_cross_table_writer、existing_table_new_namespace |
| SQL_REVIEW_REQUIRED | 新/变更 Mapper 语句或传递 include；包括 select/affectData 防伪装写 | unchanged、scope_removal、dynamic_sql、fragment_change、query_disguised_write |
| MAPPER_FILE_REVIEW_REQUIRED | XML 根属性或仅 sql 片段变化也须审阅，防止动态 refid 不能建立静态引用图时漏检 | unchanged、dynamic_refid_fragment_only_change |
| SQL_REMOVAL_REVIEW_REQUIRED | 删除语句可能移除回执/恢复/校验 | unchanged、removed_recovery_statement |
| PRODUCTION_REVIEW_REQUIRED | 其他 src/main 变更；调用语义 UNKNOWN，需 scope/intent/version/recovery 审阅 | documentation、unknown_java_and_side_effect |
| SQL_SCRIPT_REVIEW_REQUIRED | Mapper 之外 SQL（迁移、seed、脚本）变化 | documentation、migration_and_maven_flags |
| TEST_GATE / TEST_SOURCE_REVIEW_REQUIRED | CI/workflow 或测试/fixture 变化，避免删测试、改 skip/门控假绿 | documentation、shared_gate_and_test_skip_change |
| BUILD / UNKNOWN_BUILD_FILE | POM、Maven flags/launcher 或未分类 build 文件变化 | documentation、dependency_removal、unknown_build_file、migration_and_maven_flags |
| BASELINE_LAUNDERING / POLICY_CHANGE | 存量不是固定 blob 的精确再生成；候选改变/删除/增加受信任门禁文件 | clean_exact_candidate、baseline_laundering、candidate_cannot_edit_or_add_policy_exception |
| candidate identity | SHA/HEAD 不符、dirty、非基线后代 | clean_exact_candidate、dirty_mismatched |
| 报告真实性一致性 | 缺报告、零用例、skip/失败/错误/flaky、缺/重复 testcase、全限定名碰撞、错候选/run/attempt、报告哈希不符 | EvidenceTest 的成功 CLI 与各 red 用例 |
| 精确外部审阅 | 错候选/findings/测试计划、缺合同/恢复/审阅者，或企图豁免未授权新依赖 | ExternalReviewTest 的 green/red 用例 |

依赖声明的精确比较属于保守文本/XML检查，重排 dependency 子元素等无害变更也可能需审阅；不是 Maven effective POM 解析。删除依赖仍需 build 审阅。新依赖未经授权，不能通过外部审阅路径豁免。

## 测试回读与信任边界

```sh
python3 gate/ci/architecture/gate.py evidence \
  --plan trusted-plan.json --receipt job-receipt.json --reports job-reports
```

先复用受信任 `ci/assert_it_ran.py` 的缺失/零/skip/error/failure 回读，再核对全限定 suite 名、精确 testcase 数、唯一 testcase 身份、每条状态、报告集合与 SHA256、候选 SHA、run_id、run_attempt、Maven 退出码。XML 只支持单根 testsuite；@Nested 必须按实际叶类的 `$` 全限定名登记。动态/参数化测试需受控的精确实跑计划，本工具不推断 `@Test` 文件数量。

外部 plan 严格字段：

```json
{
  "candidate_sha": "<完整40位候选SHA>",
  "run_id": "<受控CI运行ID>",
  "run_attempt": "<运行attempt>",
  "suites": {"<全限定类名>": 1}
}
```

receipt 必须包含全部同值字段，另含 `maven_exit_code: 0` 和 `reports_sha256: {"TEST-完整类名.xml": "<实际文件sha256>"}`。plan 由集成人维护的测试选择合同产生；receipt 必须由受信任 job 在清理旧报告、核对检出 SHA、实际执行后产生。不得让候选自己写 plan 或把旧报告补新 receipt。拒绝 symlink 报告目录/文件。

本工具只给出 `REPORTS_CONSISTENT`，不验证审阅者身份的签名、不独立证明报告生产者诚实、存储身份、测试有效性或业务覆盖。不能把自测合成 XML 当成真实测试。真 MySQL/Redis 继续使用现有批准的 `ci/real_storage_it.py` / `ci/funds_it_gate.py`、其容器身份/执行/清理回读及原始 artifacts；不得用本工具的回读成功替代它们。该 evidence 格式不是现有 real-storage receipt 格式；未提供自动转换器，接线必须由集成人用受信任 job 填充，不能直接重命名原 JSON。

## 审阅变化项，不扩大 baseline

变更不一定是违规；UNKNOWN 先阻断。已经完成人工语义审阅并有受控测试报告的候选，可显式提供候选目录之外的受信任输入：

```sh
python3 gate/ci/architecture/gate.py source --source candidate --candidate "$SOURCE_SHA" \
  --review approved-review.json --plan trusted-plan.json \
  --receipt job-receipt.json --reports job-reports
```

review 严格字段为 `schema: 1`、`candidate_sha`、`findings_sha256`、`reviewer`、`decision_ref`、`required_suites`、`contracts`。`findings_sha256` 是 Python `gate.digest(gate.encode(source_result["findings"]))` 的结果；`required_suites` 必须等于已核对的 plan.suites。每个 `(rule,key)` 恰有一份合同，含这两个标识及非空 `scope/identity/intent/version/recovery/atomicity/reject_zero_effect`，逐项写候选代码、测试和审阅依据；不适用项说明原因。结果保留全部 findings，标为 `REVIEWED_STATIC_WITH_REPORTS`。

受信任调用者必须从真实审阅系统核实 reviewer/decision_ref 和候选身份，再把批准工件传入；工具不从字符串推断审批真实性。PR 提交的 review.json 没有审批效力，不能复制到 /tmp 就视为批准。此路径不覆盖 POLICY_CHANGE、BASELINE_LAUNDERING 或未授权新依赖。集成后的新 SHA 需要重算 findings、重审及重跑所需测试，不能沿用队员旧候选回执。

## 本日边界与接线

没有修改现有 workflow/根 POM/业务 Java/SQL，没有新依赖、DDL、VPS 或生产访问。接线建议在 `docs/delivery/architecture-day/F/ci-wiring.patch`，仅供集成人审阅后应用。可信 gate SHA 必须冻结，候选 checkout 必须 `fetch-depth: 0` 以读固定基线 blob。

盲区：不解析完整 MySQL 方言、动态分支可达性、运行时反射或 Java 调用图；不证明锁顺序、原子性、真实身份、拒绝零副作用、外部幂等和恢复调度注册。宽松的正则提示不能抹掉 UNKNOWN；人审合同和真库测试依旧是验收条件。静态通过不是后端编译通过，合成报告自测不是 MySQL/真实链路证明。
