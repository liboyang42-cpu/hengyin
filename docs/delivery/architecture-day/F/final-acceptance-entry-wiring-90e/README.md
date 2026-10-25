# 最终验收入口接线（SOURCE / NOT_RUN）

结论：本片复用原登记表、真库 matrix、一次 restore build、七 cohort owner 和 C 的原 complete verifier；只补已定位的入场与收据绑定断点。没有新增生产代码、案例登记、job、runner、并发预算或审批项。没有执行任何测试、控制脚本、编译、Java/DB/HTTP/容器/故障/CI。

- parent：`90e18550ae75e83641e85bf4bb218f08922c7d3e`；parent tree：`55ad70d6e20746d7b47ce368ca1d08f2014b59ff`。
- 独立分支：`codex/architecture-day-f-final-entry-wiring`。共享文件只作为 `review.patch.b64` (base64 decode before applying) 提案，由 root 串行应用；不要同时应用 patch 和重复 cherry-pick。
- registry 不改：保留原 76 类 / 916 普通源码声明、完整 batch 顺序/类表/count/gates。该算术不是执行或通过证明；不把跨 phase XML 数量当唯一业务案例。
- 未修改 `authenticated_redis_recovery_probe.py`、其资源 cleanup、生产文件、trusted architecture 文件、旧 baseline、root/C 的恢复事实解析器。

## 已落增量与原调用链

1. `real-storage-it.yml plan → real_storage_it.py cmd_plan/precheck → storage-start → run → storage-stop → summary` 保留。parent 中只要存在 `CHENGYIN_RESTORE_MODE`，包括空串、`false`，即拒绝入场，不能由 `child_env` 静默删掉后声称原 parent 正常。该变量不能注册成测试审批项；cleanup/汇总不因坏 parent 被阻止。
2. B14 原 `BeforeAll` 真实读取 `GITHUB_SHA`，按它写证据目录。因此包含该类的 dispatch 必须由同 source 的 workflow ref 发起，`source_sha` 等于真实 `GITHUB_SHA`；不通过覆盖 GitHub 环境伪造身份。M1 原两 source lane不受此 B 专属要求影响。
3. 原普通 summary 下载本 attempt 的 `rs-class-*-a<attempt>`；CLI 强制验证 repository、workflow SHA/ref、run、attempt、原 `storage_it` job、schema、唯一 class、candidate SHA、batch、原 Maven 非零/实际 counts/skip、独立 CLEAN 和原 teardown。外来/重复/缺失/UNKNOWN 不晋升 PASS。普通与 M1 调用者均传真实 matrix result；即使先前测试 receipt 为 PASS，后续 collector/job 失败也不能签整批 PASS。原未限定 run 的直接 helper 测试及 latest-attempt 场景保留，生产 CLI 没有该退出开关。
4. 原执行 receipt 新增 `surefire_xml_sha256`，摘要取自实际已复制的 `TEST-*.xml`。原始 XML 仍原路径上传。B JSON collector 只收本 source 有界普通 `.json`，验证 native `identity.json.sourceSha`，另写本 run/attempt 的逐文件摘要；不上传 `.log`，不把该 lane 没构建的 boot JAR 填成已验证。
5. `restore-source-build → pack → 原 artifact → 各 cohort unpack → 原 host/probe/finalize → summary` 不变。pack 将实际 staged JAR 摘要作为本 build job output；summary 必须把七份同源/同 run/attempt 收据的 JAR 摘要与该 output 对齐，tool 摘要与同 source 的 canonical backup tool 字节对齐，再交原 C complete verifier。七份相同的伪摘要不能仅因彼此相等过关。build parent 也要求恢复标志不存在。原一编译、11 host/6 build 控制 SOURCE、七固定 cohort、max-parallel 20、清理/首错误围栏全部保留。

## 唯一执行与证据 manifest

全名以源码/registry 中 FQCN 为准；下列实际类均已登记，不新增第二登记表或重复 job。root 合成新 SHA 后再执行，不在旧 SHA 上拼接签发。

| 目标 / 源码 | 唯一现成入口与入场 | 已有输出 / 验证边界 |
|---|---|---|
| `business.service.impl.FundsProcessRecoveryMySqlIT`，14 普通声明 | `real-storage-it.yml` 的 `architecture-day-integrated-changes`（含本批 76/916）；workflow ref = source_sha；`B_PAYMENT_INTENT_DDL_APPROVED=1`、`B_FUNDS_PROCESS_RECOVERY_APPROVED=1` 只按原精确名单传入 | `rs-class-FundsProcessRecoveryMySqlIT-a<attempt>/receipt.json`、原 XML、`funds-process-recovery/<source>/<UUID>/*.json`、新增 `funds-process-evidence-index.json`。native identity 含 UUID/选定 loaded class、resource/source 摘要；不是整个 boot JAR 或生产恢复证明 |
| `business.service.RestoreMoneyDispatchMySqlIT`，1 普通声明 | 同一个 integrated batch；原 class env；workflow 原精确 `B_PAYMENT_FACT_DDL_APPROVED=1`、`A_REGISTRATION_PAYMENT_FACT_DDL_APPROVED=1` | 原 receipt/XML/独立 schema CLEAN + teardown。真实子 JVM 自设恢复 true；源码没有独立业务 JSON，不能捏造。root 的排序夹具 delta 不属本片 |
| `business.it.CrossDomainFaultIsolationMySqlIT`，1 普通声明 | 同一个 integrated batch；mysql+redis，原 class env，workflow 原精确 `B_PAYMENT_FACT_DDL_APPROVED=1` | 原 receipt/XML、MySQL schema 与 Redis DBSIZE 读回。HTTP/调度/状态观测在原 case；无独立业务 JSON，不包装成不存在的实测文件 |
| `business.it.TemplateAdoptionRoundTripMySqlIT` 6；`business.it.ThemeStateKeyDeclarationMySqlIT` 6；`business.service.impl.TopicPublicationBindingMySqlIT` 9 | 同一个 integrated batch；原 registry storage/gates/database_base、源码 scratch admission 不变 | 各自原 XML、source/precheck/Maven/CLEAN/teardown receipt。其余 integrated 成员完整保留；不再同时派重复的主题/资金子 batch来凑并行 |
| `ThemeRuntimeMySqlIT` 参数化入口 | 保留标准 `ci/backend-test.sh` 的 `MYBATIS_ITS`、原 `IT_LIST` Maven 参数及 `ci/assert_it_ran.py` 回读 | 标准 Backend 原 XML/source identity；不塞普通 `@Test` registry，不用源码声明数代替参数化执行数 |
| 固定 7 restore cohort | 原 `isolated-restore-business-reconciliation.yml` 手动入口；真实 hosted/repo/run/attempt/source；parent 恢复标志 absent | 一次 `restore-business-build-<attempt>` 的全文件 hash manifest + JAR；七份 `isolated-restore-business-reconciliation-<cohort>-<attempt>`，各只有 `host.json/reconciliation.json/finalized.json`；原 summary artifact。complete3 仍走原 C verifier；gap4 保持 BLOCKED，不授权生产 release |
| C 完整应用恢复启动 | C `c382451` + `bec4054` 最终 SOURCE 与 F `restore-http-entry-9496` shared 接线已齐；root 合成后唯一执行，不登记新 Java count。沿原 authenticated-redis workflow/host；见 `C-RESTORE-APPLICATION-HANDOFF.md` | 实际 run/四槽 cleanup/finalize，固定三 JSON；全部 NOT_RUN，不能以旧 recovery/7 cohort 签全应用启动 |

普通 lane 的 `run` 使用原 Maven `test` / test classpath，没有 `package`。其 XML 摘要和 B 的 loaded class/resource 摘要应与 source/run/attempt 一起回读，不能冒充七 cohort 的 once-build JAR。普通 teardown 的 `removed` 沿原机制记录；不扩称已经具备恢复 host 那种精确 CID/label 外层不存在性证明。

## SOURCE 控制与未执行项

保留全部旧 case/断言/negative controls，仅追加/补原调用接线：

- `FinalEntryBindingTest` 七个直接控制：parent absent、原 B source identity、同 run 正向、外来/重复/缺证/非零/skip/UNKNOWN 负向、CLI 当前 attempt、ThemeRuntime 原生参数化 lane、collection/matrix 失败不被旧 PASS 隐藏。
- 原 B collector 控制保留 valid/missing/bad_sha/symlink/invalid_json，再补 foreign native/workflow、缺 run、缺 identity 和逐文件 hash 校验。
- 原 `RunFlowTest` 正向增加原 XML 摘要断言；M1 原控制收紧为本 attempt 的 artifact pattern，原业务场景不删。
- host 新增 once-build 正向与三种不匹配/缺证负向；原 complete/gap、missing/source/run/attempt/JAR/scope/cleanup/final/private/symlink 全保留。
- build 新增实际 staged digest → job output 控制及 parent 空/false/true/非法拒绝；原六控制不删。

以上全部是测试 SOURCE，**NOT_RUN**。实际执行的仅 Git diff/apply 格式检查、文件摘要和有限秘密模式检查；没有 Python import/py_compile/测试，也无 Java/DB/HTTP/CI。完整秘密扫描与集中运行由 root 安排；未声称本片验收通过。回退应整组回退这两个 workflow 与其 helper/test 调用接线，不单独移除严格 verifier 造成无绑定执行。


Root transport note: original review.patch SHA256 455f7996999e3ccc95a22913a3217ea9f2842aa0fed332b83071ae213a1421f2 is preserved privately and exactly encoded as review.patch.b64. Literal diff-context blank lines in a Markdown-tracked raw patch trigger whitespace checks; encoding changes no source hunk. The eight code/workflow postimages were author-exact at integration. Root later changed only ci/test_real_storage_it.py expected source arithmetic25->28 and916->919 for E actual3 added copy cases; no assertion or case was removed. Root postimages for this transport representation are recorded separately; tests and compilation remain NOT_RUN.
