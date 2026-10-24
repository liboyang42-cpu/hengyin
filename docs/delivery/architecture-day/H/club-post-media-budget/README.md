# H：ClubPost 图片送审五次预算与 UNKNOWN

代码补丁已完成；Java、JUnit、MySQL、真实微信均未运行，不是验收通过。

- 源码基线：公共 `e4bdaceb8c1eb800b38c93601bbbd82ca15b26e5`。四个核心文件与原审查头 `477696c8eb2fa24bd3d7febc896415bf0d1b67c0` 相同。
- 原 H checkout：`/workspace/hengyin-h-review`，`codex/architecture-day-h-review`，HEAD `0558daca3cdb3bd1989a7c0e7a656159a2eab1fe`；未切换或修改其他任务工作树。
- git fetch 一次因执行器代理不可达失败。通过 GitHub 读取当前公共源码，在独占 `/tmp/h-media-budget/{base,candidate}` 实现；12 份原文件逐个匹配公共 blob SHA。
- `review.patch` 是完整分文件 unified diff；11 个变更文件及 SHA256、实际轻量检查、未执行项见 `verification.json`。root 可在目标 checkout 先 `git apply --check`，再应用、独立 review、秘密扫描与统一编译/真库验证。本交付不改 CI/registry。

## 失败机制与修复合同

原 selectDue/claim/retry 不执行 MAX_ATTEMPTS=5；过期 PROCESSING 被直接重新领取。旧 checker 把未发、外部超时、inbox 登记失败全部压成 null，不能将 null 当安全重试证明。

1. scan、原子 claim、retry 共用 `LEAST(max_attempts,5)`；较小原预算保留，旧较大值不能升高本次五次上限。第 5 次合法成功可收口，第 6 次零 claim。
2. 仅外呼前的本地暂态故障可写固定 `PRE_DISPATCH_UNAVAILABLE` 退避。外呼后的 null/异常/回执不明只停为 UNKNOWN，不自动重发。
3. 过期 PROCESSING 无未发送证明，一律停止；旧模糊 RETRYABLE 也停止。批量扫描与直接 dispatchOne 的 claim 均覆盖这些路径，不重置计数或释放身份。
4. 复用数值 5 停止态，reason 区分坏任务、`REVIEW_ATTEMPTS_EXHAUSTED`、`REVIEW_OUTCOME_UNKNOWN`。保留原 claim owner/fence、已知 trace SHA；同 owner/fence 才可附加迟到原 trace，不可替换不同 trace。
5. 不写 ClubPost 内容/版本/图片/可见性，不假 SUBMITTED/DONE，不删除或重建原任务。已登记 wx_content_callback_inbox 关联不变，迟到回调仍可按原 trace 处理。
6. ClubPost 实际没有 HOLD 字段，未制造假字段或改变发布规则；测试断言原内容不变、并发已下架内容不被复活。人工态仍可见于原积压指标/Alarm；没有供应商原号查询能力时不伪造查证。

调用者：ClubPostService 的原事务 enqueue → TransactionService(MANDATORY)；Task.retryDue / dispatchOne → 独立短事务 claim → 原 checker 事务外调用 → 独立短事务终态。Mapper 生产消费者仍只有这两个既有 owner；未更改其他域入口。

## Schema 与回退

仅新增 `migration_club_post_media_review_budget_20261024.sql` 源码；旧 migration 字节不改。原 CHECK 只允许 PROCESSING 保存 owner、SUBMITTED 保存 trace，无法保留 UNKNOWN 原执行身份。

新文件先只读精确预检原/扩展后的两条 CHECK，再一条原子 ALTER 窄扩展状态 5 的 owner/trace 留存；原 lease、state、attempt、唯一键均保留。无 UPDATE/DELETE 数据迁移，不执行 DDL。root 必须登记依赖并在获准环境验证；生产仍需独立 DDL 授权。

停止新派发后保留扩展 CHECK 与所有原行；含停止态 owner/trace 时不能恢复旧 CHECK。禁止退回会重领 UNKNOWN/过期 PROCESSING 的旧 dispatcher。旧 schema 下新终态会 fail-closed，不能以清空原 owner/trace 来造兼容。

## 原测试与必须验证

原场景不删除：将外部 null 可重试、阈值后无限重试、lease 到期盲重投三个危险预期改为当前授权合同；原成功、开关、媒体移除、毒任务、时间预算、积压、原事务回滚、owner/fence 断言保留。新增边界见测试源码。

- 普通 @Test 源码：原 23 → 30；其中两套原 Boundary(4)/Task(2)逐字未改。已有 MapperMyBatisTest 使用 H2，不能当 MySQL 证据。
- 新 `ClubPostMediaReviewBudgetMySqlIT`：8 个普通 @Test，沿原 CHENGYIN_FUNDS_MYSQL_IT 准入；显式 loopback/PORT/USER/PASSWORD，随机独占 scratch，不覆盖占用库，创建失败不清理他人库；只 drop 自建库并读回。
- MySQL 源码复用真实原/增量迁移、两套真实 XML 与 Spring Tx proxy。覆盖最后一次成功/失败、并发末次 claim、租约耗尽/未耗尽、旧模糊重试、UNKNOWN 零重发、迟到原 callback、原 trace 冲突、较小预算和旧较大预算。provider/目标读取为明确 doubles，不是 HTTP/真实微信或完整发布验证。
- root 必须执行新 MySQL 8 项及原相关 30 项、编译/装配/架构门禁与迁移前后断言。这里没有派发 CI。
- 已执行：XML/预算与 fence 静态负正控、词法括号检查（不是 Java parser）、固定 SQL 分割前提、完整 patch 应用并逐字比较、空白检查、原 secret-file gate。gitleaks 二进制不可用；完整秘密扫描交 root，不冒称扫描通过。
