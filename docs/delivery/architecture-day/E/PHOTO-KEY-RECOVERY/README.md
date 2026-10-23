# E Photo 原 key 恢复重载

结论：新增原 member/session/scoped actionKey/hash 只读恢复 API，解决 prepare 已提交但回包丢失、客户端尚无 intentId 时无法找回原意图。只改实际 service 和已有 Mapper XML，新增独立 5 unit/4 MySQL 回归；原 16 unit/10 MySQL 文件逐字不变。本批 Java/MySQL 尚未运行。

## 身份及独占范围

- 唯一 parent：公开冻结 `1162a0e672fc1c07d4592fc3ee9f1eb6b6df1cc9`；parent tree `ec28e2cac4192134b704010a95493ed4467d6869`。
- 独立目录 `/workspace/hengyin-e-photo-key`；分支 `codex/architecture-day-e-photo-key-recovery`。未改 1162/493、cache/IM 冻结 ref。
- source-only tree：`a4c7c004d5f0b21a59554c9a556e9c5856e47175`；4 个 source/test 文件及 SHA256 见 source-manifest.json，完整候选另含本报告与 manifest。
- 没有新服务、interface、DTO、Mapper Java 方法、依赖、表结构或迁移。未改 C 的 Runtime/Controller/PlayAttemptLog、root Gateway/Vision 或共享 CI。

## 失败机制与最小合同

此前唯一 read(memberId,intentId,hash) 需要 prepare 回包中的 intentId。prepare 成功提交但客户端丢回包时，即使原 row/nonce/result 完整存在，也无法按原命令恢复。新签名直接加到实际 PhotoCheckIntentService：

```java
PhotoCheckIntentView read(Long memberId, Long sessionId,
        String actionKey, String requestFingerprint);
```

- 有效身份下原 member/session/key 无行：精确返回 null。不同 owner 的 tuple 同样不泄露其他 owner 行，返回 null；原 session 读取授权仍由 C 负责。
- member/session 非正、key 空/超 128、hash 非 64 位小写 hex：409，查询前拒绝。
- 已绑定原 key 但 hash 不同：409，不能伪装无行。行若与查询 tuple 或原 JSON member/session/key/hash/canonical 引用不一致，fail closed。
- SQL/连接等数据库异常直接传播；没有 catch→null、prepare replacement、新 nonce、quota/provider/consent/media 调用或写业务字段。结果用既有 factory 还原，返回既有 View，不暴露 nonce/配额凭证。
- 沿用 selectByKeyForUpdate，而非普通快照查询。给该语句添加 useCache=false 与 flushCache=true；后者会清理 MyBatis SqlSession 一级缓存，不能以只关二级缓存代替。FOR UPDATE 使用 MySQL current read，读取提交后的原行，不受旧 RR consistent read view 隐藏。
- C 最终/准备短事务应先锁原 session，再调用此重载锁 intent；E 不查/不锁 session，不引入 intent→session 反向锁。外层事务持有锁直到其结束；单独无事务调用是一次 current read，不承诺跨调用保留锁。本 API 未擅自新增 MANDATORY 要求，也未标注会妨碍 FOR UPDATE 的只读事务。

SQL 行锁属于控制并发的数据库动作；本方法不修改 intent/state/nonce/result/receipt 或权益。若 C 收到 DB 异常，不能将它当不存在后重做 prepare。找到原意图后仍沿用原 key/hash/id/nonce/result，不能拿最新 expectedVersion/epoch 重新绑定。

## 新增用例（未运行）

PhotoCheckIntentRecoveryReadTest：5 个。

1. 无行仅锁定查询，返回 null，零其他 Mapper/配额/provider/授权/事务管理操作。
2. 丢 prepare 回包后找回原 ID、原状态及已保存 degraded/providerState，nonce/reservation 不变。
3. hash 冲突 409；不同 owner 无行；即便 Mapper 错返回另一 owner 也 fail closed；零外部动作。
4. DB 错误原异常直接抛出，不伪装不存在、不 prepare replacement。
5. 非法原身份在 DB 前 409，零副作用。

PhotoCheckIntentRecoveryMySqlIT：4 个，真实 MySQL8+/生产 Mapper/XML/真实 Spring IntentService 及事务。复用 1162 的 connect/cleanup 夹具，不调用其 10 个业务测试，不复制/改写旧 fixture。mock 依赖仍按 1162 registerSingleton，真实服务保留 AOP。

1. 丢弃 prepare 返回值，只按原 key 恢复 ID；独立 SQL 比对原 nonce/reservation/updated_at 未变。首次受控 review 持久化真实 Mapper result 后，再按 key 重放原结果，不调用新 quota/provider。
2. 不存在 key/不同 owner 返回 null，hash 冲突 409；原行/nonce/updated_at/项数不变，外部 mock 零调用。
3. REPEATABLE READ reader 先普通 Mapper 查空并建立旧 read view。并发 writer 持有 session probe→prepare，reader 查空后 writer 提交；reader 再取得同 session probe 锁并调用新 API，必须看见原提交行。普通 SELECT 仍看不到它，证明不能用普通快照读替代该锁定读。
4. 单独在 READ COMMITTED 下隔离一级缓存缺口：reader 先持有 session probe，第一次同一 locking statement 查空；无反向 session 锁的受控 intent writer 提交；reader 同一 SqlSession、同语句、同参数再查，必须拿到原行。RC 在此仅用于去掉 absent-row gap lock，以能确定暴露 cached-null；不是把 RC 冒充 RR，RR 由第 3 例另验。

session probe 仅在该类独占随机 scratch schema 中模拟锁序，不冒充 C 的真实授权/完整 gameplay。Vision/Gateway/Consent/Media 都是受控 mock；没有真实 Redis/provider 调用或全链路权限结论。真库类不加 skip gate，缺少既有 AI MySQL 显式连接配置必须失败。

## 实际验证与后续 CI

实际运行：Python XML 解析确认 useCache=false/flushCache=true/FOR UPDATE；原 10/16 字节与 @Test 数核对；4 个文件 scope/source-only tree/SHA256 核对；新 API body 检查没有 catch、nonce、prepare、quota/provider 或 session Mapper；secret-file gate、git diff --check、有限敏感模式扫描。均 PASS。没有 Java、MySQL 或 DDL 执行，缺完整 gitleaks，需根复扫。

根应显式执行新增 unit 类（期望 5）和新 MySQL 类（期望 4，storage=[mysql]，gates={}，既有 CHENGYIN_AI_MYSQL_IT_PORT/USER/PASSWORD 即可）。要求 actual tests=5/4、failures/errors/skipped=0，保存真实报告；不能把父候选旧 16/10 通过或跳过当作新用例已通过。共享 registry/CI 由根单一修改。

协调者提供 Photo10 复跑 run40024200966 正在运行；本批不重做 fixture，不改其计数/断言，不据此提前记绿。此前原初始化失败仍为业务 0/10。

挂起恢复、旧 RR markApplied receipt 混合读、跨 scope/global-key admission 和 C 最终编排的审查边界继续有效（独立报告 79fec29c）。本次只补原 key 恢复及其真正 current read，不冒称已关闭这些其他事项。schema 无改变；回退应同步停止依赖新重载的调用者，保留所有 intent/nonce/result/receipt/audit，不清表、不重发。
