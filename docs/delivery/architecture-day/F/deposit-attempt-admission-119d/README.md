# CoopDepositSettlementAttempt 原审批登记修补（SOURCE / NOT_RUN）

结论：真实失败为已有 fixture 的显式审批值在 registry 遗漏，不是已执行的 MySQL/业务失败。只将该 class 的 gates 从 `{}` 补为 `{"B_PAYMENT_INTENT_DDL_APPROVED":"1"}`，并追加一个精确正反控制 SOURCE；scanner、默认 gate 政策、workflow、Java/业务/DDL、原 case/count/guard 均未修改。

- parent：`119d3f87bbf89162d47db837c404b361915d1b81`；独立分支 `codex/architecture-day-f-deposit-attempt-gate`。
- 协调方实际首轮回执：storage run `40215798308` / job `121236532571`，原91 Python控制全部通过（0.570s），随后 plan 在起库前因该 gate 缺值拒绝。MySQL/Redis matrix **NOT_RUN**，不能称919通过或业务失败。F 未重跑或独立冒称回读日志。

## 精确源码依据和装配

| 原源位置/入口 | 事实与本片影响 |
|---|---|
| `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/CoopDepositSettlementAttemptMySqlIT.java` 的 `originalSchemaAndMapper` | `BeforeAll` 原样断言 github-hosted、审批 `1`、五项显式 scratch 连接和 loopback；原 UUID schema/原 base DDL/两份原 migration/真实 Mapper 与 DataSourceTransactionManager。登记应明确提供相同 `1`，不能删 assert 或默许继承值 |
| 同类 `originalIntentAndRealServices` | 原 `WechatPayTestWiring.facade()` → gateway/intents；实际 JDBC/事务/原业务锁 owner 注入，SDK仅原 double；原 CoopInviteServiceImpl/Topic service/Mapper装配不改 |
| 同类 `installRealFactRecoveryStorage` | 原 callback inbox/outbox DDL 和原 verified fact migration 在同任务库内；原 `WechatVerifiedFactTestSupport.realFacts` 装配保留。不由此增另一审批项/namespace/业务写权 |
| 同类 `cleanupOnlyOwnedSchema` | 原创建 schema 身份约束、DROP 与 information_schema 不存在性断言完整保留。11个普通声明及其所有断言不变 |
| `.github/workflows/real-storage-it.yml` | 原 B_PAYMENT_INTENT 精确 class 名单已经包括本类，无需修改；原 class_env → child_env 只显式给当前类登记值 |
| `ci/real_storage_it.py` | 原 REVIEWED_APPROVAL_GATES、literal scanner/precheck、继承审批过滤均不改；缺值/错误值仍在起库前失败 |

本类在 `architecture-day-deposit-attempt` 与原 `architecture-day-integrated-changes` 各一次，保持原11，不新增批次/重复执行。其余 registry 内容、全部原负控和源码算术原样保留。

## 控制 SOURCE / 交付

`test_deposit_attempt_requires_its_existing_exact_payment_intent_admission` 绑定 exact gates/count/storage/两个既有 route、真实 fixture 的 source_gates；缺值/`0`/`true` 均须 precheck 拒绝。保留继承审批会被过滤的断言，只由当前 class_env 明确提供 `1`；核原 workflow 已有名单。不是扩大一般审批规则。

完整两文件 review diff 经 `review.patch.b64` 运输，decode 摘要在 `PATCH.sha256`；原相同 context 空白不进入原样 Markdown diffcheck。全 owned 增量可从候选 commit 获取；作者后像见 `POSTIMAGES.sha256`。root 唯一实际应用，只选 apply 或 cherry-pick 一种，不重复。

实际执行仅 Git diff/apply 检查、摘要、有限新增行秘密模式检查。所有新/旧控制、Python parse、编译、Java/DB/CI 均 **NOT_RUN**；没有重复已过91控制或启动第二批。完整秘密扫描及同源定点验证由 root 统一安排。本片不签业务、真渠道或清理已通过。
