# F 交付：架构门禁与测试真实性

> 本文是 941612/1ce413 阶段的历史记录。后续增量比较、低成本审阅和实际 CI 接线已修订，当前状态及提交前编译阻塞以 [FOLLOWUP.md](FOLLOWUP.md) 为准；旧接线 patch 已移除。

结论：F 的轻量门禁代码已完成，44 项专属自测及 5 项既有报告门禁自测实际通过；公共基线可再生成校验，候选源码静态检查通过。没有执行 Java 编译/单测、MySQL/Redis 或真实业务链路，不把 Python/合成报告结果算作后端验收。现有 CI 未修改，接线仍由集成人审阅、应用及安排受控验证。

## 基线、隔离、候选

- 仓库：`https://github.com/liboyang42-cpu/hengyin.git`，没有读取/迁入私有 chengyin 历史、分支、日志、备份或秘密。
- 开工实际 HEAD：`2ec68899099a1e250df0949a8383bf85b5d3ffe0`，与指定参考一致。原分支 `work`、原工作树 `/workspace/hengyin` 干净。
- 隔离工作树：`/workspace/hengyin-architecture-f`，任务分支 `codex/architecture-day-f-guardrails`，直接从公共基线创建；没有切换原工作树分支或回退任何他人修改。
- 已验证的代码候选：`941612b0ee71a536168a9b103878d834019ae632`，提交 `feat: 新增架构增量门禁与测试真实性回读`。
- 本报告、原始输出和接线建议是代码候选之后的独立交付文档；最终分支 HEAD 见交付消息。后续文档提交不代表 Java/真库重新验证。

## 独占修改清单

| 文件 | 用途 |
| --- | --- |
| `ci/architecture/gate.py` | 公共基线 blob 盘点、精确增量/source 门禁、报告身份/内容回读、精确外部审阅合同 |
| `ci/architecture/baseline.json` | 固定公共提交的可再生成存量记录；2,948 条 Mapper 语句、94 条依赖声明及表字段写入口提示 |
| `ci/architecture/owners.json` | 六个后端模块职责与技术写 owner 的含义；业务领域 owner 尚须集成人确认 |
| `ci/architecture/commands.json` | 报名、支付回调、报名退款、保证金退款四条高风险命令的代码/恢复源/测试清单 |
| `ci/architecture/test_gate.py` | 每类规则的正常/违规负控，临时 Git 仓库和合成 XML 均清理自身资源 |
| `ci/architecture/README.md` | 规则与自测映射、命令、schema、信任边界、盲区、审阅流程 |
| `docs/delivery/architecture-day/F/*` | 本报告、真实执行输出/摘要、未应用的接线 patch |

没有修改根 POM、现有 workflow、业务入口、共享 action ledger、迁移总清单或他人交付目录。无新增依赖、schema/DDL、生产/VPS/微信/真实收费调用，也没有创建 Agent、额外会话或 runner。

## 失败机制、调用链与最小修复合同

开始实现前检查了全部后端根/子 POM、Mapper XML、既有 `ci/assert_it_ran.py` 及其自测/调用方、`ci/backend-test.sh`、`ci/funds_it_gate.py`、`ci/real_storage_it.py` 及批准的 workflow。新增 gate 当前没有既有生产调用者；唯一实际调用者为 F 自测和本次只读执行，拟议 CI 调用仅在 patch 内。

既有 `assert_it_ran.enforce` 从 `ci/backend-test.sh` 点名 IT 后回读，可拦截缺报告/零条/skip/error/failure，但以短类名索引，单独不足以关联候选及运行身份。`real_storage_it.py` 已有独立的真存储执行、候选 SHA、源码门控/用例预检、存储身份及清理回读；F 不改写或替代该机制。新 evidence 层先复用既有 enforce，再校验全限定类名、实际 testcase、报告哈希与候选/run/attempt；它只证明这些工件一致，不证明存储或报告生产者可信。

业务链的调用者/持久源/已有测试已搜索并记录在 `commands.json`：

1. `RegistrationCheckoutServiceImpl → IRegistrationOrderService.createOrReplay → RegistrationOrderServiceImpl → IRegistrationCreationTransaction/ICmsRegistrationService → CmsRegistrationMapper`。关注 member/requestId/fingerprint 与持久身份，不能按响应正文 TTL 释放唯一身份。
2. `CmsRegistrationServiceImpl.handleWechatPayCallback → WxPaymentCallbackInboxServiceImpl.process → WxPaymentCallbackMutationService.apply`，持久源 `WxPaymentCallbackInboxMapper`。关注业务效果与 inbox 终态同事务提交、唯一处理权、重复拒绝零副作用。
3. `RefundController / RefundServiceImpl / TeamGroupServiceImpl / RefundReconcileTask → RefundPayoutServiceImpl.payoutAfterReview`，持久源 `RefundApplicationMapper`，恢复入口 `RefundReconcileTask`。关注原退款身份、意图先落库、外部事务边界、租约与 UNKNOWN 查证。
4. `CoopInviteServiceImpl → CoopDepositRefundService.requestLocked/refresh/handle/reconcile`；`RefundReconcileTask` 调用公开合作服务恢复入口，间接到该服务。持久源 `CoopInviteMapper`。关注原支付号/退款号/金额、lease token CAS、历史/归档只查证。

这些是审阅入口盘点，不是重新判定资金业务正确。未知资金/退款/结算/授权语义仍交对应 owner，不猜新规则；列出已有测试不等于其完整覆盖上述合同。

规则先用最小临时仓库/报告样本复现再验证：

- 保留方法名、移除 SQL owner 条件：必须产生 SQL findings。
- 同一 system 模块新增 wallet 写入或更换 Mapper namespace：必须产生 NEW_TABLE_WRITER；不能凭模块相同放行跨表写。
- 删除恢复/回执语句、伪装为 select/affectData、修改传递 include、改变 Maven 依赖：必须红。
- 动态 SQL 标 UNKNOWN。新增生产 Java/JDBC/注解/外部副作用调用不能靠扫描漏检默认绿，变更整体需合同审阅；不对所有 afterCommit 一刀切。
- 缺报告、零用例、skip、总数伪绿而 testcase 含失败/skip/flaky、重复测试、全限定名碰撞、错候选/错 run/错 attempt、文件被改：必须红。
- 候选改 gate/owner/baseline 或新增豁免文件必须红；可信 baseline 必须与固定公共 blob 的再生成字节完全相同。

开发中负控实际发现并修正了跨 namespace 的嵌套 include 解析上下文错误；同时为动态 refid 无法建立完整静态引用图的片段变更加上文件级审阅门禁，避免 statement 指纹未变时漏检。没有靠跳过失败或扩大 baseline 解决。

变化项可以经集成人提供的外部受控审阅工件处理：绑定完整候选 SHA、findings SHA256、每条 rule/key 的 scope/identity/intent/version/recovery/atomicity/reject_zero_effect、审阅者/决策引用及已回读的精确测试计划。工件必须在候选目录之外、由受信任调用者确认真实审批来源；不是让 PR 作者复制一份 JSON 到 /tmp 自批。工具不验证审批签名，CI 必须承担来源信任。新依赖本任务未获授权，不可用此路径豁免；不能重建 baseline 洗白变化。原 findings 始终保留。

## 实际执行及输出

所有输出对应代码候选 `941612b0ee71a536168a9b103878d834019ae632`，汇总和日志哈希在 `verification.json`，均为云 VM 获准的轻量 Python/只读 Git 检查：

| 检查 | 实际结果 | 原始输出 |
| --- | --- | --- |
| `python3 -m unittest discover -s ci/architecture -p 'test_*.py' -v` | 44 tests，OK，exit 0 | `architecture-selftests.log` |
| `GATE_SELFTEST=1 GATE=.../ci/assert_it_ran.py python3 -m unittest ci/test_assert_it_ran.py -v` | 5 tests，OK，exit 0 | `existing-assert-selftests.log` |
| `python3 ci/architecture/gate.py source --source ... --candidate 941612...` | findings=[]，STATIC_ONLY，exit 0 | `source-check.log` |
| `git diff --check 2ec688... 941612...` | 无输出，exit 0 | `diff-check.log` |
| `git apply --check docs/delivery/architecture-day/F/ci-wiring.patch` | context 可应用，exit 0；未实际应用 | 本报告记录；仅校验 patch 上下文 |

source 盘点为 2,948 条语句、384 个表名提示、2,031 条 UNKNOWN（包含未作语义分析的 select），写语句 1,313 条，其中 UNKNOWN 396 条；23 个表名提示存在多个 Mapper 技术入口。存量基线是观察与精确例外，不是对这批 UNKNOWN 的安全结论。最终静态通过仅说明 F 本身没有更改业务或扩大依赖。

专属测试的报告 XML、候选 SHA 和审阅者均为标注清楚的临时负控数据；它们不是真 Java/Surefire/MySQL 结果。`verification.json.kind` 明确为 `PYTHON_STATIC_SELFTESTS_ONLY`。

## 未执行、接线和阻塞

- Java 编译、Java 单测、MySQL、Redis、真实 HTTP/身份/业务链均未运行。任务明确禁止在云 VM/Mac 自建后端或数据库替代受控环境；这里没有请求/复制凭据或启动容器。
- GitHub CI 没有触发；没有生产 workflow、部署、master push 或微信上传。本地自测不替代受控 CI。根 AGENTS 的构建要求尚未满足，需集成人按本次用户授权的环境约束统一安排，不能把本候选标为“后端验收完成”。
- `ci-wiring.patch` 仅建议在现有 CI 增加 GitHub-hosted Python job，gate checkout 冻结到上述代码 SHA，candidate checkout 使用本次 `github.sha` 且取全历史，避免旧队员 SHA 冒充集成候选。patch 未修改工作树的现有 workflow；实际应用由集成人单一合并。
- 该最小 patch 只接静态阻断和 findings 上传，不自动生成批准工件。审阅工件来源核验及已批准真库执行工件到 evidence schema 的接线尚待集成人实现/审阅；不能直接改名现有 real-storage receipt 当成新格式。需要先核对现有测试登记批次、门控及精确用例计划，再安排受控执行。
- `commands.json` 的业务领域 owner、所有 UNKNOWN 的具体语义与所需故障/并发测试仍须对应队伍确认；技术 Mapper owner 不替代安全/资金审阅。

## 检查盲区、迁移及回退

不做完整 SQL 方言/动态分支解析、Java 调用图/字节码解析，也不证明业务授权、原子提交、外部 UNKNOWN 原号查证或持久恢复调度可达。表/字段为可能不完整的文字提示，遇到复杂表达式不猜归属。POM 是声明级检查，不是 effective POM；无害文本重排可能触发保守审阅。动态/参数化/@Nested 测试需要明确实跑计划；不支持的报告结构 fail-closed，不放松期望数。

无 schema、数据迁移或运行时行为变化；没有清理数据库、资金记录、回执或审计。未接线前回退仅撤销 F 的独立新增文件。若后续 gate 成为 required check，集成人必须同时协调其可信版本与 CI/分支保护回退，不能靠缩小测试计划、删除报告或扩大 baseline 消红；历史验收工件应保留。F 的回退不应回退其他队伍的业务修复。
