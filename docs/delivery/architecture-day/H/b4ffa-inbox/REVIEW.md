# H：B 报名/inbox 三项组合回归复审

结论：未发现可证的新 P1/P2，三例的源码装配与关键断言有实际检验力，没有看到用 mapper/业务成功替身绕过原状态机。Java/MySQL/CI 均未执行，不能据源码称3项通过或关闭所有支付风险。

唯一候选 `4ffa600cbb89abfc7e1e5a77d5d99c8aa8b10e3d`，tree `9201981a6fbff835c0ce30dda1cc5e32521ad50d`，parent `a3fdd1650f26d88ec8c0cccd30bb37488cda06a9`。候选 delta 仅一个新 IT 与两份 B 文档，没有生产变化；其中 gateway/registration 生产文件与 H 前次读取的 v8 逐字一致。本报告不重审同一旧生产实现或覆盖 root 的后续组合源。

H 开工 HEAD `344eefd8641302d4676e288361ef078c1b45d323`，独立工作树 `/workspace/hengyin-h-review`、分支 `codex/architecture-day-h-review`，干净；仅新增本 H 目录。

以下位置均为候选的 `chengyinhub-admin/src/test/java/com/chengyinhub/business/it/RegistrationCallbackIntentMySqlIT.java`：

| 核查点 | 源码结论与限制 |
| --- | --- |
| 原 schema/XML | 101–127 使用原 P0AcceptanceSchema 从仓库 `sql/base_schema_20260818.sql` 截出10张原表，执行列出的4份原迁移，加载6份原 mapper XML。无 mapper mock、简化领域表或 JDBC 仿制业务 adapter。SqlSessionFactoryBean 与 DataSourceTransactionManager 使用同一 DataSource，探针再核对真实物理连接。 |
| 真事务服务组合 | 178–210 把真实 registration/inbox/claim/mutation/points/settlement 服务接到事务代理；claim/release 的原 REQUIRES_NEW 与 mutation 的 REQUIRED 被真实 AnnotationTransactionAttributeSource 读取。无 passThrough inbox。SettlementEntry 的 owner_type=1 原分支在付款时返回0，实际走此分支，不是人为成功 stub；因此不证明商家结算/现金算法。 |
| 签名与原单事实 | 149–176、426–451 使用官方 NotificationParser、临时 RSA 公钥与 AES-GCM 本地生成的通知。篡改原 body、签名正确但金额不符均在业务前断言拒绝。JsapiService 查单仍是 double，返回独立设置的原号/appid/mchid/channel/金额/状态；不是真微信、证书轮换/HTTP或供应商事实证明。 |
| DONE 后故障位置 | 389–414 先执行真实 claimPaymentSuccess，记录连接；markDone 前检查事务内业务结果及 PROCESSING，另一连接仍见旧 PAYING/80积分/零账本和scan。随后 invocation.proceed 真执行 DONE UPDATE、要求影响1行、事务内读DONE，另一连接仍见旧业务/PROCESSING，最后才抛一次异常。不是 SQL 执行前的模拟失败，也没有伪造 SQL 返回值。 |
| 整体回滚及原身份恢复 | 248–268 要求业务/积分/账本/scan回到原值，inbox为原 owner/fence 释放后的 RETRYABLE；原 id 恢复、attempt/fence 1→2，最终仅一份账本和scan，再重投不加次数/通知。真实 claim、mutation、release 的 Mapper/事务链均参与。 |
| 顺序 duplicate | 223–244 检查原 inbox id/fence、两条指定业务键账本、一次scan、一次DONE及一次 post-commit 排队。是顺序重投的幂等覆盖，不是两连接并发通知或租约失效竞态证明。 |
| W1 CLOSED / W2 | 272–302 先真实确认 W1 CLOSED，再真实 freeze W2；本地仍 PAYING 时投递旧 W1，必须FAIL/非DONE，库存、原积分预占、权益不释放，W2不被关闭。再支付W2并重投旧W1仍不能改新交易/渠道/原hash。这能暴露省掉当前 attempt 栅栏而仅凭本地 PAYING 做 CLOSED CAS 的回归。SDK中的W2先设SUCCESS不替代本地支付写入，后者确由新回调完成。该例没有并发 barrier，也不证明主动 SDK close 的交错；CLOSED 签名事件是刻意构造的对抗输入，不声称微信正常会推送它。 |

通知边界：199–209 的 scheduler double 不执行二维码/HTTP Runnable，只观察提交后排队；回调中独立连接须同时见到完整业务及DONE，断言失败不会增加 scheduledNotices，后续必须为1的断言仍会失败。不能把这项读回说成通知真实送达。HTTP Controller 的204/500不在本类范围。

H 实际静态执行：23份文件 Git blob 身份一致，6 XML可解析，10张原DDL片段可按原 helper截取；3个普通@Test，无skip声明；上述两生产文件与既有 v8 快照逐字相同。没有执行任何DDL或Java。读取文件精确身份见 `SOURCE.json`。

root 必须按最终组合源实跑3项并核对原XML（failure/error/skip均0）、源/产物hash及唯一 scratch CLEAN；独立连接/同事务探针的断言必须实际走到，不能只看最终SUCCESS或case数。此3例不抵消旧B各域/旧attempt并发、真实HTTP/JWT、真微信、资金/退款/灾备等未验证边界。本候选无需新增生产迁移/回退操作，既有scratch DDL仅由获准CI执行。
