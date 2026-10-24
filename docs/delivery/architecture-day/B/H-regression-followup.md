# H 审查 V01/V02 最小测试补充

结论：基于 `7b12bd43dc7df9df3d80e9019df0a3977caa87e8`，只新增两处回归覆盖及本交付文档，生产代码/接口/mapper/依赖/CI/G文件零改。新源码未执行 Java 或 MySQL，不宣称通过；由 root 与 `97cd641c6f4c494038f3fc7b0511272eebaa3240` 的共享 E2E fixture 修复组合后统一验证。

独立工作树 `/workspace/architecture-day-b-h-tests`，分支 `codex/architecture-day-b-h-regression-tests`，开工 HEAD 精确为 7b12；原 B 工作树保持原状。公共 Git fetch 因继承代理不可达失败后，通过 GitHub connector 读取公共 commit 元数据和两条 import，重建并校验 commit SHA 与完整 tree `c7c48bd962697ae0ab0f7b043acb888278f398d2` 完全一致，再建立工作树；未改代理/凭据或导入私有历史。

## V01：APP alias 原参数幂等重放

文件 `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/WechatPaymentIntentMySqlIT.java`，仍恰好4个 @Test。

在原第三项 `oldClosedObservationCannotCloseNewAttemptAndNewSuccessCreditsExactlyOnce` 内，旧 CLOSED 查单已经提交、新 APP alias 创建后、旧 close barrier 仍暂停且业务 payable、SUCCESS 之前：

1. 读回 APP alias 的 request_hash。
2. 用完全相同业务号、merchant、appid、APP、CNY、金额、空openid、description、notifyUrl 再次 freeze（复用同一原 helper）。
3. 断言返回同 alias、request_hash 未变、意图表仍2行。

保留原两个线程/barrier、SDK double、旧关闭不得CAS、新成功两次通知、业务状态/交易号/一次权益读回、原渠道/新渠道/旧关闭事实等全部断言。

## V02：CLM 关闭 W1 后动态查到 W2

文件 `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ClubMembershipServiceImplTest.java`，保留原73项，新增3项：

- `explicitCancelOfW1CannotCloseOrGrantForNewNotpayW2`：409；原PAYING、版本、参数、快照不变；无关闭/支付CAS、权益或退款审核；SDK只close W1。
- `explicitCancelOfW1RecordsOnlyVerifiedSuccessfulW2WithOriginalInvoiceOwnerAndAmount`：只按W2成功交易号走原支付CAS及权益创建；保留原账单owner/member/club/9900分/版本/冻结快照；不执行关闭CAS。
- `explicitCancelOfW1ClosesLocalInvoiceOnlyAfterCurrentClosedW2Fence`：W2的官方CLOSED事实匹配后，真实网关在原领域事务内读取当前W2并通过栅栏；验证W2栅栏在原版本关闭CAS之前，绝不使用W1栅栏关闭业务；无权益/支付CAS。

最小夹具 `CloseAttemptReadbackFixture` 调用真实 ClubMembershipServiceImpl 和真实 WechatPayServiceImpl。既有领域 mapper 继续为受控 double，官方 JsapiService query/close 为 double；SDK必须依次查询 W1(NOTPAY)、W1(CLOSED)、W2(对应状态)，只 close W1 一次。W1关闭真实网关路径返回后才将意图读取 double 的 current 切到 W2，CLM 的原二次 reconcile 自行重新解析当前号。

意图读取 double 提供两号各自的 merchant/appid/JSAPI或APP/CNY/原金额、稳定业务映射及当前attempt；真实 queryVerifiedOrder/closePaymentAttempt/lockPaymentAttemptForUpdate 均执行。Spring 无数据库资源事务管理器只使真实网关的事务边界检查有有效上下文，SDK调用断言位于事务外。它不是 InnoDB 隔离、真实持久化或四域全链路证明；真实MySQL覆盖仍由原4项提供。

## 实际验证与注册

本地仅执行：git diff --check、2文件范围核对、原源代码行/所有既有断言保留核对、MySQL @Test 4→4/CLM 73→76、重放位于 barrier 释放前核对、秘密文件/凭据标记扫描、补丁 apply --check及精确字节比对。未运行Java/JUnit/MySQL/SDK网络/生产DDL，未新增或调度CI。

root已报告旧7b真实四项 PASS4/0/0/0/CLEAN；此结果不覆盖本轮新增断言。旧主CI唯一错误为已有E2eWechatPayStub未实现新abstract方法，root独占修复于97cd，本候选没有改该共享fixture或为接口增加default。

注册沿用原 `WechatPaymentIntentMySqlIT` 和 `ClubMembershipServiceImplTest`。root在既有受控GitHub-hosted临时MySQL/JDK21路径按新组合SHA显式运行这两类（新表四项仍需 B_PAYMENT_INTENT_DDL_APPROVED=1 和 RUNNER_ENVIRONMENT=github-hosted），逐方法核对报告、source SHA和CLEAN；不以旧SHA通过或默认未选*IT算通过。

精确测试补丁见同目录 `H-regression-from7b12.patch`（不含文档），SHA256 `2d7db957bfd3b94f682a7a9447457670a2eb390cb5643fe375600398d653a44e`，17172字节。没有迁移或生产回退影响。
