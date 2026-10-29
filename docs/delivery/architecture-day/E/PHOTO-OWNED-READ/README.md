# E Photo owned 回读的生产缓存修复

结论：修复生产 Mapper 的按 intentId 回读；不是修改 fixture 期望。`selectOwned` 增加 flushCache=true/useCache=false，并使用 FOR UPDATE current read。新增独立 3 个真实 MySQL 回归；原 Photo10/Intent16、key-recovery MySQL4/unit5 完整字节与断言不变。本批 Java/MySQL 未运行。

## 身份、范围和已知真实失败

- 唯一 parent：公开 `ffc2e7159846892b530edb17bbb5e411471da8f4`；parent tree `28abe91181dcc5e9e837e2ed43271c68c4511f7e`。该依赖已包含经授权的原 key 恢复重载及 selectByKeyForUpdate 一级缓存保护，本批不重写它们。
- 目录 `/workspace/hengyin-e-photo-owned`，分支 `codex/architecture-day-e-photo-owned-read`，source-only tree `ba265d3d1ade94528bf78bbb4899555681f14d9a`；完整候选另含本报告/manifest。
- 生产增量仅 `chengyinhub-system/src/main/resources/mapper/business/PhotoCheckIntentMapper.xml` 的 selectOwned。无新 API、schema、依赖，未改 service/Mapper Java、root Gateway/Vision、C Runtime/Controller/回执、共享 CI 或任何冻结测试文件。
- 根提供真实 CI40024662872/source f281：Photo10 全部业务已执行，9 pass、1 failure、0 error、0 skip。失败为 concurrentExecutionRunsOnceAndLoserOnlySeesInFlight:197 期望 RESULT_READY，API 返回 PREPARED。该记录保留，不改期望、不删除或跳过用例。此前初始化失败仍独立保留业务0/10。
- root full CI c53 基于旧 snapshot，不能覆盖本次新生产修复；本报告不提前记绿。

## 根因与修复合同

NEVER 保证没有 actual business transaction，但默认 Spring 仍可开启 synchronization。第一次 owned/selectOwned 把 PREPARED 缓存在该 synchronization 绑定的 SqlSession。内部 REQUIRES_NEW 的 claim/snapshot/saveResult 会挂起此 SqlSession，在独立事务提交；恢复外层 SqlSession 时，其本地缓存不会因另一 SqlSession 的 UPDATE 自动失效。executeOnce 最后的 self read 因此可读回旧 PREPARED，即使真实行已 RESULT_READY。类似的 CAS 败者也可能在并发赢家已 IN_FLIGHT 时读回缓存 PREPARED。

XML flushCache=true 在每次 statement 执行前清理一级本地缓存；useCache=false 同时绕过二级缓存，仅后者不够。FOR UPDATE 使原 session 锁后 authoritative owned read 使用当前提交行，而不是同事务早期建立的 RR snapshot。member/id/hash WHERE 和 JSON 原身份校验均保留。原 selectByKeyForUpdate 已有同样保护，不通过原 key 的 flush 偶然掩盖按 ID 的缺陷。

executeOnce 的 NEVER/显式无业务事务检查不变，claim/snapshot/result 独立事务不变，provider 仅首次原意图执行，原 day/nonce/reservation 不变。C 读取仍遵守原 session→intent 锁序；本修改不引入反向 session 锁。测试在受控 provider 边界使用另一真实 JDBC 连接 FOR UPDATE NOWAIT，验证 owned 回读没有把 intent 行锁持有到外部工作边界；没有把 Spring no-actualTx 当成该数据库锁事实的替代证明。

## 新回归：PhotoCheckIntentFreshReadMySqlIT，3 个（未运行）

该类仅复用冻结 Photo10 fixture 的 connect/cleanup，不调用其 10 个测试；采用真实生产 Mapper/XML/Spring proxy、受控 MySQL8+。所有外部组件 mock 使用 1162 原 singleton 夹具；新建的 session probe 只说明锁序，不是 C 的真实授权或完整玩法链路。

1. 显式 NEVER 空 synchronization：先按 ID 读 PREPARED，执行一次带 snapshot 的 review，再用独立 JDBC **先断言 DB 行已经 RESULT_READY**，随后断言 API 同为 RESULT_READY 和原结果。保持同一 synchronization 中再次按 ID read/execute 重放，provider 仍一次、原 nonce/reservation 不变，原结果不被替换。DB 断言在 API 状态断言之前，可区分“写库失败”与“API 返回缓存旧态”。
2. 同一空 synchronization 先缓存 PREPARED，另线程赢家 claim 后在 provider 边界停住。独立 JDBC 先证明 IN_FLIGHT，再断言败者仅返回 IN_FLIGHT、没有合成 result，DB 行（含 updated_at）不变，provider 调用仍一次。赢家释放后独立 JDBC 先证明 RESULT_READY，再断言赢家/API 重放原 degraded/providerState，原 nonce/reservation 仍不变。
3. RR reader 先普通 SELECT 建立 PREPARED 旧快照，另线程完成首次 provider/独立结果提交。独立 JDBC 已见 RESULT_READY 后，reader 按 session probe→intent 锁序读取；按 ID 必须与原 key API 一样返回 RESULT_READY。用于证明只清缓存而继续普通 RR snapshot SELECT 仍不充分。

所有 provider work 均是受控 Vision stub，断言 entry 的 actual transaction=false、独立 NOWAIT 可锁原 intent；不声称真实模型 HTTP/Redis/权限链路已验。类无 skip gate，沿用现有 CHENGYIN_AI_MYSQL_IT_PORT/USER/PASSWORD、随机独占 scratch schema 和原清理规则。

## 实际静态验证和根 CI 入口

实际运行：XML 解析断言 selectOwned 与 selectByKeyForUpdate 都有 L1 flush、L2 bypass、FOR UPDATE；保留原 member/id/hash predicates；原 10/16/4/5 测试逐字/计数核对；新 @Test=3；独立 DB-before-API 断言顺序和 NEVER synchronization/NOWAIT 覆盖核对；service/Mapper Java/schema 未改；secret-file gate、git diff --check 与有限敏感模式扫描。全部 PASS。没有 Java/MySQL/DDL 执行，完整 gitleaks 待根复扫。

根应先保留并复跑原 Photo10 全绿要求，再显式运行 PhotoCheckIntentFreshReadMySqlIT（tests=3，storage=[mysql]，gates={}，使用既有 AI MySQL env）；key-recovery 4 与 unit5、原 unit16 继续按根计划验收。所有实际用例须 failures/errors/skipped=0。共享 registry/CI 仍由根单一处理，E 不开并行旧 fixture 工作。

该修复不声称关闭已报告的 markApplied receipt 普通 RR SELECT 边界，也不决定跨 scope/global-key admission；C 最终 Photo 编排仍须联合验收。无迁移；回退不删除任何 intent/result/nonce/receipt，不能把 API 旧 PREPARED 理解为确定未执行并重发。
