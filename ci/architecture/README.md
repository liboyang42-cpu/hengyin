# F：架构增量门禁与报告回读

本工具使用 Python 标准库，只做可可靠识别的源级检查。它不要求普通 Java/测试/文档变化提交七项 JSON 审批或 Java 测试回执；审阅仍走正常 PR code review。源码门禁和真实测试回读是两个独立入口。

## 固定存量，增量比较

`baseline.json` 始终从公共 `2ec68899099a1e250df0949a8383bf85b5d3ffe0` 的 git blob 精确再生成核对，保持 2,948 条 Mapper 语句、94 条依赖声明、表字段写入口提示及传递 include 指纹；不按新候选重建、增补例外。其字节及 verifier 的修改需要受信任 verifier 的新固定 SHA，不能由候选自豁免。普通门禁测试、README 变化本身不阻断。

本次影响另用可信 GitHub CI 事件校验的 `--compare-base`，不是永远 diff 2ec→candidate：

- PR：candidate 为事件中的完整 head SHA，compare-base 必须等于 head 与事件 base SHA 的唯一实际 merge-base。CI checkout 必须是该 head 或父节点恰为 base/head 的 synthetic merge。
- push：candidate 与事件 after、GITHUB_SHA 相等，compare-base 必须是事件 before，并且为 candidate 祖先；全零 before、非祖先强推等不能静默改用旧 baseline。
- workflow_dispatch：candidate 为 GITHUB_SHA；从真实事件 repository.default_branch 读取主干名，校验 origin 是固定公共仓库 HTTPS 地址，显式 fetch 此主干到 origin tracking ref 并与 FETCH_HEAD 核对，再取其与 candidate 的唯一 merge-base。缺事件、错误远端、fetch 失败、歧义 merge-base 均拒绝，不回退到 candidate 父提交。执行 source 时再次核对；主干在两次计算间移动导致比较点变化时拒绝并重跑。
- 缺可信 CI 上下文、错误仓库、短 SHA、任意祖先冒充 merge-base、自身充当 base 均拒绝。GitHub 事件文件和 verifier 必须由受信任工作流提供；这是 CI 来源信任，不是本地环境变量的密码学证明。

已进入比较 base 的历史改动不重复列出，也不修改固定存量 baseline。原技术 owner 检查仍从比较 base 的字面写目标取得 Mapper/module 写入口；新增的窄 business policy 则只使用独立受信任版本中经root明确批准的两项事实，不从 observed writers 推定业务权限。未改历史不要求后续每个 PR 重审，也不成为新增写能力的许可。

## 自动阻断与审阅项

| 类型 | 自动行为与界限 |
| --- | --- |
| 新增依赖/扩大 scope | 比较标准化 group/artifact/type/classifier、声明数量及已知 scope 使用范围；新增坐标、增量声明、test→compile 等扩大为红。仅格式、版本变化或 compile→runtime 不作为新增依赖，POM 仍列审阅项。不是 effective POM/传递依赖分析。 |
| 跨技术 owner 写 | 新/变更语句的字面写目标是已有表，而 module/Mapper namespace 不在比较 base 的已知写入口中，判红。表名不明确或新表无既定 owner，列 UNKNOWN，不猜归属。 |
| 两项已批准 whole-table 写权 | 仅 `AI_QUOTA_RESERVATION` / `PHOTO_CHECK_INTENT`：新增确定写边的 module+namespace 未声明为纯writer，报 `UNDECLARED_WRITE_BINDING`；已声明但属于另一批准业务owner，报 `NEW_BUSINESS_OWNER_WRITE`。比较 base 无该表也适用。按 path#id、writer、操作、字面目标比较新增能力，旧债不因普通修改反复列出；删后重引/改id/新增操作不能借旧债。固定前缀目标确定、字段动态时可同时红且列 UNKNOWN；不证明完整效果。 |
| 明确无作用域写 | 新/变更的可识别字面 UPDATE/DELETE 缺 WHERE 或 WHERE 恒为 1=1/true，判红。复用 `ci/sql_splitter.py` 处理引号/注释，避免字符串中的 where 伪装作用域；select 标签伪装的直接 DML 同样检查。 |
| 动态/复杂 SQL、include | 不做完整 SQL 语义证明。动态标签/表名、未解析/循环 refid、复杂 SQL、删除语句、新表等输出 `review_items`，交普通 PR 审阅。保留跨 namespace 传递 include 指纹和片段文件级提示，动态 refid 片段变化不会漏出清单。 |
| Java、测试、workflow 变化 | 不是违规本身，不要求 JSON 审批/Java 报告；由既有测试/成本/报告门禁及 PR review 处理。不声明自动证明 JDBC、反射、授权、事务、版本/CAS、意图/恢复和拒绝零副作用。 |

`findings` 是可识别阻断项，非空退出 1；`review_items` 是仍需审阅的未知/合同提示，单独存在不导致退出 1。`PASS` 或 `PASS_WITH_REVIEW_ITEMS` 只代表该源级检查，没有表示业务合同安全或后端测试通过。

`owners.json.business_policy` 只启用两个whole-table事实及其唯一纯writer，`commands.json.business_commands` 引用原 AiQuotaRecoveryService / PhotoCheckIntentService 公开合同；整份owners/commands与gate/原baseline/locking proposal/实际producer共六个文件按可信verifier字节冻结。未声明或无效policy不得静默关闭检查，candidate修改owner/删除fact/清空scope/伪造command都须独立review的trusted revision，不能自批准；CI固定ref由root单独repin；候选不能自改绑定或producer后批准自身。

输出 `business_policy.coverage=PARTIAL`；范围外字面写列 `BUSINESS_WRITE_OUTSIDE_POLICY`，动态/未知目标或效果列 `UNKNOWN_BUSINESS_WRITE_EFFECT`，不把UNKNOWN叫安全或宣称完整A02。原四条高风险链仍是代码/恢复源/已有测试参考。所有command引用均不证明Java调用必经Service，不授权其它domain直接调用Mapper，不是新审批系统或运行时合规认证。scope、唯一身份、关键意图、版本/CAS、恢复调度和原子提交只有业务 owner+相应故障/并发测试才能确认；静态解析做不到的必须保留在 PR 审阅中。

## 两项私有 Mapper 的编译引用回读

`owners.business_policy.private_ports` 只声明 AiQuotaReservationMapper→AiQuotaRecoveryService、PhotoCheckIntentMapper→PhotoCheckIntentService 的精确class绑定（均system）；直接只读依赖也受限，`$`内部类不按前缀允许。它与SQL writer身份分开，不自动给其它fact增加私有依赖规则。新增绑定须独立trusted revision。

`collect_jdeps.parse_output` 可在跨模块过滤前额外收集这两个目标；默认调用和原五项返回值、模块图格式/观察口径不变，不增加System自环或基线。`gate.py compiled-ports` 从实际Boot/original模块Jar/target classes重枚举并核对原11项证据、实际source/tree/run/attempt/JDK工具hash、class-index和invocation，再重读raw；不信候选JSON的references/findings/complete声明，不再运行Java/jdeps。

新CLI退出码为0（仅两个声明目标的静态引用完整通过）、1（确定的其它consumer，PRIVATE_MAPPER_CONSUMER）、2（证据/目标/索引/位置/覆盖UNKNOWN）。已解析确定违规即使同时存在其它UNKNOWN也返回1，缺图/缺边不通过；不证明方法调用、反射/XML、运行授权或完整A01/A02。源级规则继续工作，source的既有退出码不变。CI只能采用同job固定可信producer产生的raw，hash自洽不是任意producer诚实的密码学证明。

本候选不修改root工作流；post-build真实接线及同System真实编译负控尚待root完成。参数、忽略target可信checkout、单次采集及失败保留原artifacts的建议见 `docs/delivery/architecture-day/F/private-mapper-compiled/CONTRACT.md`。

## 运行与 CI 接线

```sh
python3 -m unittest discover -s ci/architecture -p 'test_*.py' -v
# CI 环境中，用固定版本 verifier 扫描独立 candidate worktree：
python3 gate/ci/architecture/gate.py source --source candidate \
  --candidate "$SOURCE_SHA" --compare-base "$COMPARISON_BASE_SHA"
```

`.github/workflows/ci.yml` 在既有无条件 `fast_gates` 尾部增加架构步骤，不新增 job/matrix、不改既有检查条件和 `max-parallel: 20`。checkout verifier 使用固定完整 SHA；自测从该受信任版本执行。candidate 使用本 job 唯一临时 worktree，完整历史读取；`if: !cancelled()` 保留前序失败后的诊断，阻断退出码不会吞掉；上传 findings/review_items，清理只触及本 job 的 worktree。

PR 架构检查使用 PR head；原 Backend job 仍按既有 checkout 检查 integrated merge。输出明确区分 candidate_sha 和 ci_checkout_sha，不能把一个 SHA 的 Java 报告当另一个 SHA 的验收。手动冻结候选使用 workflow_dispatch 可统一在该候选 SHA 上实跑。

## 现有报告机制

`evidence --plan ... --receipt ... --reports ...` 是独立的一致性辅助入口，先复用受信任 `ci/assert_it_ran.py`，再核对全限定类名、精确 testcase、报告集合/哈希、candidate/run/attempt 和 Maven exit code。缺报告、零用例、skip、失败/error/flaky、重复 testcase、错候选、错 attempt 必须红。它没有接成普通源码变更的额外审批门槛。

真实 Java/MySQL/Redis 验证继续使用现有批准的 `ci/backend-test.sh`、`ci/funds_it_gate.py`、`ci/real_storage_it.py` 及其 GitHub-hosted scratch workflow。不要把合成 XML 自测、push 总绿或路径过滤 skipped 当成 Backend tests 实跑。回读工具不独立证明存储身份、报告生产者诚实或测试语义覆盖。

当前交付和合法 Java 执行入口见 `docs/delivery/architecture-day/F/FOLLOWUP.md`。旧 `REPORT.md`/verification 是 941612/1ce413 阶段的历史结果，不适用于本轮未提交代码。
