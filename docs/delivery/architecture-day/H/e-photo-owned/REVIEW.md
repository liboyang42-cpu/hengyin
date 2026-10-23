# H：E91 Photo 原意图回读独立审查

结论：`91e78c448f69b2e3e3407acc111ff460c4a37ebb` 的窄生产修复针对了真实失败机制：`selectOwned` 同时清理 L1、绕过 L2、使用锁定 current read。未确认此4文件增量新增 P1；**尚不能判定原失败已修复通过**，必须实际复跑原 Photo10 和新增3例，并验收 C 最终调用事务。原 Photo10 是已执行的 1 failure，不是待猜风险。

## 身份与实际证据

- 公共基线 `2ec68899099a1e250df0949a8383bf85b5d3ffe0`，唯一仓库 `liboyang42-cpu/hengyin`。
- E commit `91e78c448f69b2e3e3407acc111ff460c4a37ebb`，tree `392dac695de1aa84ec2fa7c6185505a395cb1dc1`，parent `ffc2e7159846892b530edb17bbb5e411471da8f4`，分支 `codex/architecture-day-e-photo-owned-read`。
- 相对2ec共34文件已从公共内容重建、逐 blob 核对并生成同一完整 tree。相对 parent 仅4文件：`PhotoCheckIntentMapper.xml`、新增 `PhotoCheckIntentFreshReadMySqlIT.java`、PHOTO-OWNED-READ报告/manifest。生产 service/Java Mapper、schema、旧测试没有被这个增量改写。细目和旧运行绑定在 `candidate-binding.json`、`source-identity.json`。
- H 沿用 `/workspace/hengyin-h-review` / `codex/architecture-day-h-review`；未切换别人的树或改实现。源码只读快照 `/tmp/h-e91-review/tree`。本次仅新增 H 证据/报告。
- 独立下载并解析 [CI40024662872](https://github.com/liboyang42-cpu/hengyin/actions/runs/40024662872) attempt1、job120657865513、artifact12531488336 的原始 XML/回执：source `f281df751ce002f6409f29a8be4201c4f4a62695`，**10实际执行，9pass/1failure/0error/0skip，cleanup CLEAN**。失败为 `concurrentExecutionRunsOnceAndLoserOnlySeesInFlight`，`PhotoCheckIntentMySqlIT.java:197`，expected RESULT_READY / actual PREPARED。旧10例文件 blob `3c0ae949c7f752575b51eeb7bb5d43ce5b9745e3` 与 E91 相同。
- 原始失败属性/stack、逐用例名、zip/XML/Maven/receipt哈希保存在 `prior-ci-failure.json`。旧运行不覆盖91修复，未把它改写成通过。本轮没有触发 CI，也没有在云 VM 运行 Java/MySQL。

## 锁读、缓存和独立结果提交

`chengyinhub-system/src/main/java/com/chengyinhub/business/service/ai/PhotoCheckIntentService.java`：

1. `executeOnce:79–89` 是 NEVER，并显式拒绝 actual transaction；先 owned 读取，只有 PREPARED 才在 REQUIRES_NEW 中 CAS claim。CAS败者只读。普通“没有业务事务”仍可有 Spring synchronization；同步绑定的 SqlSession 可以缓存首次 PREPARED。
2. `:93–94` 在外部 review 路径带原 reservation，并在模型调用前由 `saveSnapshot:152–163` 独立提交输入摘要/模型版本。`saveResult:112–116` 独立提交后，同一执行方法再调用自己的 read；内部事务挂起/恢复外层 SqlSession，另一个 SqlSession 的 UPDATE 不会自动清理它的 L1。
3. parent 的 selectOwned 是普通可缓存查询，因而原运行可能在真实结果已写入后仍取回原 PREPARED。E91 `PhotoCheckIntentMapper.xml:50` 附近加入 `flushCache=true`（清理本 SqlSession L1）和 `useCache=false`（L2 bypass），并在 owner/id/hash原条件后加 FOR UPDATE。只关L2不能修L1；只清L1、继续普通 RR SELECT 不能逃离早期 read view。新组合同时处理这两个机制。
4. parent 已修复的 selectByKeyForUpdate 同样有这三个条件；按ID的修复没有依赖“碰巧先调用原key方法清缓存”。原归属 WHERE 和 JSON member/session/key/hash/canonical 比对保留，未扩大可读主体。

H 静态核对两条语句的属性/current SQL，默认生产 MyBatis 配置未将 localCacheScope 改为 STATEMENT。完整tree和这些检查见 `executed-static-checks.json`；这是来源/源码证据，不是 MySQL 运行证据。

## 三个新增用例是否真正对应故障

| 新 `PhotoCheckIntentFreshReadMySqlIT` 用例 | 评价及限制 |
| --- | --- |
| independentlyCommittedReadyRowMustNotReturnCachedPreparedAfterResume | 显式 NEVER 空 synchronization，先按ID读PREPARED，再执行；独立 JDBC 先断言 DB=RESULT_READY 后断言 API 相同。可区分结果写库失败和恢复旧SqlSession；继续同同步重放，断言原nonce/reservation、一次review |
| cachedPreparedLoserOnlyReadsInFlightAndThenOriginalReadyResult | 另一线程赢家停在provider边界，独立JDBC先证明IN_FLIGHT；败者只能读IN_FLIGHT、无结果且updated_at不变；释放后读原RREADY/degraded/providerState/nonce。不是用新key重新执行 |
| ownedReadAfterSessionLockUsesCurrentRowDespiteOlderRepeatableReadSnapshot | 真RR先普通SELECT建立旧PREPARED快照，另线程独立提交原结果，再按 session-probe→intent 顺序读取；按ID与按key都须读最新行，覆盖只清缓存不够的问题 |

三例均复用旧fixture的连接/清理及真实Spring事务代理、生产XML/Mapper，不改旧10/16/4/5文件或断言；独立JDBC在API断言之前。provider入口还用另一真实连接的 FOR UPDATE NOWAIT 检查行锁已释放，而不是把 actualTransaction=false 当成数据库锁证据。**三例仍未编译或运行**；Vision/Gateway/Consent/Media为受控stub，不能称为真实供应商/Redis/完整玩法授权验证。session probe也不是C真实session。

## 公开 read 与 readOnly 事务的实际边界

`PhotoCheckIntentService.read(member,id,hash):120` 和原key重载`:129` 都没有自己的事务注解。新增 FOR UPDATE 是控制并发的锁动作，不能因为方法名read或不写业务列，就推定它适合 MySQL READ ONLY事务。

- 无外层实际事务：当前查询随连接的单语句事务完成；空 synchronization 仍会复用SqlSession，但L1清理应避免旧态。新增NOWAIT夹具检查的是其受控数据源条件下没有把行锁带到provider；生产连接/代理组合仍需实际接线验证。
- 有外层可写短事务：锁继承外层事务；C须先锁真实session，再调用intent读，不能持锁进入provider或反向再锁session。
- 有外层只读事务：方法不会自动升级成可写事务；若连接/服务器实际执行 READ ONLY，FOR UPDATE会被MySQL拒绝（而不是返回null）。不得捕获成“无意图”、放宽为普通RR读或分配新key。需要由最终调用者采用已约定的可写短事务，或明确区分普通展示读与权威恢复读；本次不替owner改接口政策。

已搜索E91生产源码的所有Intent service引用，仅存在服务定义/内部调用。又独立读取当前已发布联合 `2631846bd618df7afe6b341351ea694c712bc40b` 的 `AdvancedGameRuntimeServiceImpl`（blob53394b52…）及 `ApiAdvancedPlayController`（blob5bfb96a2…）：两者尚无Intent service调用，也没有能证实此read已在readOnlyTx调用的接线。**因此不报一个已触发的readOnly P1，也不报兼容性通过。** C正在编排的未冻结工作不能代替候选源码；需要最终SHA后核对真实入口、AOP代理、自调用、readOnly/isolation、session→intent锁序及异常映射。

该公开联合树仍有旧act事务内直接调用Vision的路径，不能用E91底层修复关闭H-PH-01或宣称C四阶段已经接通。已知 `markApplied:144–145` / `countMatchingReceipt` 的普通RR回执核对边界也未由此4文件修复；C终态事务的真实receipt/current read验证继续保留，不靠方法名或原10例有限场景签字。

## 一次provider、原nonce和UNKNOWN

源码保持：prepare的唯一 `(member,session,actionKey)` 及nonce行身份；竞争upsert后锁读原行，不覆盖原context；planQuotaReservation只规划原day/nonce。executeOnce只有 PREPARED→IN_FLIGHT CAS赢家执行一次review，输入快照和结果独立提交；IN_FLIGHT/UNKNOWN没有超时重置或租约重发。结果持久化失败留原IN_FLIGHT并传播错误，不分配新nonce。两种read均不调用prepare、quota或provider。

必须区分意图状态和供应商状态：意图UNKNOWN且没有保存结果不能合成fallback；RESULT_READY中**首次持久化的degraded**即使providerState=UNKNOWN，仍按用户确认的既有fallback语义重放。本审查不擅自更改这项业务规则。真实Gateway原reservation/nonce消费、恢复表和Redis仍需联合验收；MySQL中的mock review一次不能冒充一次实际HTTP或durable quota事实。

## 同候选裁决与后续运行要求

| 项目 | 裁决 |
| --- | --- |
| 34文件/完整tree、4文件窄增量 | 通过：H实际重建/核验 |
| 修复是否对准真实旧失败 | 源码机制匹配；旧XML确认为9pass/1fail |
| E91原Photo10复跑 | 未验证；旧1failure必须保持FAIL，不能预记绿 |
| FreshRead3、RecoveryMySQL4 | 未验证，3/4只是声明数 |
| IntentUnit16、RecoveryUnit5 | 本轮未获得执行证据，不能由静态或旧源码推定 |
| 当前读/L1机制及权限条件保留 | 源码通过；新JDBC行为尚待受控实测 |
| C真实只读/可写边界、epoch/资格/CAS/权益/回执联合 | 未验证，最终候选未审 |
| markApplied混合RR/receipt、跨scope key准入 | 此窄修复未关闭，保留原阻断 |
| 新增可确认P1 | 此4文件增量未确认；没有系统上线结论 |

受控CI至少按同一最终候选运行原Photo10、FreshRead3、RecoveryMySQL4，并核对各XML的failures/errors/skipped=0、source/run/attempt、数据库清理及原nonce/provider次数；单元16+5按根计划执行。需要根/C另交最终真实调用树及其readOnly负例、session锁后旧RR恢复、provider边界NOWAIT、回执失败回滚/重放、外账号拒绝与原参数绑定。只变成绿色构建或3个stub用例通过，仍不是全链路验收。

E91本增量无schema/迁移或依赖变化；其祖先photo_check_intent新表仍是未授权生产DDL候选，不因读修复自动执行。回退不得删除意图、结果、原nonce、quota、回执或审计，不把旧PREPARED回包当成确定未执行而重发。H未合并、部署、启动后端或访问生产。

## G交付已公开

原本地H862提交从公共GitHub返回404，确认之前仅本地。已保留原提交，并发布仅公共2ec+8份G审查文件的独立候选：分支 `codex/architecture-day-h-wp04-review-20261023`，commit `9de37f77b4d19389f7dadf6c6a88da4eece09e1b`，tree `1985b54573bfa7dd22c4766391a394fe6c054514`。远端ref、commit父/tree和8个blob均已读回匹配原H862的g-wp04目录。扫描范围和原/公开身份映射另存 `../publications/g-wp04.json`。没有声称向其他会话发送。
