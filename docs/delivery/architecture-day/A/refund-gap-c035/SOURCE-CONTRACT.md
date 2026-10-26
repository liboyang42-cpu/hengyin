# 原退款 gap 观察与重放：静态 SOURCE 合同

公共父源码：`c0355527bd2082ea957b08fd684a4a8887470858`。
独立分支：`codex/architecture-day-a-refund-gap-c035`。
本目录只交付源码合同，不包含内部运行报告、日志、状态回执、数据库行或 provider journal。
本片全部运行验证未执行；不得以本文件声称退款恢复通过。

## Owned 范围

- `RestoreRefundReconciliationFixture.java`。
- 新直接 unit `RestoreRefundObservationSourceTest.java`。
- 本静态说明及 source SHA256 清单。

没有修改生产、原退款金额/资格/双审/UNKNOWN、共享 probe/host/diagnostics/workflow、SQL/migration、其它 fixture、依赖或恢复准入政策。

## 原时序及修补合同

SOURCE 的原审批与首次退款派发保留。gap 在 BACKUP_READY 前只保留原付款消费关联及原退款申请，本地不得已有 REFUND SUCCESS；provider journal 的原退款先为 UNKNOWN，只能在 AFTER_BACKUP 后追加原 SUCCESS。没有为了造 gap 删除本地行，也没有恢复后从当前配置回填渠道或重分配退款号。

旧夹具在派发返回后直接调用 retainedCallback，假设其原事实已持久化；查询 double 还没有核验传入的原付款 Reference。该前提对首次 gap 观察不足。缺失事实本身不能推出某个 SQL、约束或生产业务错误，本片不将未取得的异常归因为确定根因。

本片将原观察来源接齐：

1. `RefundPayoutServiceImpl` 的既有 restore 查询仍是实际 caller。`RegistrationPaymentConsumptionReadService` 在既有短事务读原消费关联，然后锁外传原 Reference 和原退款号；不额外领取派发、不发第二次 submit。
2. controlled query double 先让真实 fact reader 核验传入 Reference，再按退款 ID/source ID/原退款号/分金额，与备份外原 journal 的付款 fact ID/hash、business no、交易号、mchid/appid、总分金额、provider refund ID 逐项绑定。
3. 首次没有本地 REFUND 的 SUCCESS 观察不再使用 `ARefundFactFixture.bindSuccess` 直接代写事实。只将独立 journal 的明确 SUCCESS 投影为原 SDK 边界应答，复用 `WechatVerifiedFactTestSupport.realPaymentGuards` 的真实 gateway、原本地意图 reader 和事实 writer；mock 仅限付款/退款 SDK client。
4. 原支付 SDK 应答从已持久原 PAYMENT 取 channel/wx number/交易号/币种/总额；原退款 SDK 应答使用已核对的原 provider refund ID、原退款号和分金额。退款成功时间沿原 house fixture 固定 `SUCCESS_TIME`，不读取恢复时钟或当前配置。SDK query 断言在业务事务外，client 仅允许精确的两个 query 调用。
5. 真实 gateway 核验 SDK 原单与 Reference，调用原事实 writer；随后原三元组读回必须存在，且 ID/hash 与实际查询回包一致，才能回调重放。没有手工 INSERT、没有缺行补造、没有由 journal 直接伪造 verified Reference。
6. 后续重放/重启只查备份外原渠道身份所指向的既有事实；不能硬编码恢复时商户或从退款号重新推断 provider ID。重放还核对事实的全部原渠道、交易、金额、intent hash 和 canonical hash。
7. query double 的原异常在 fixture 内保留 cause，若被原派发 best-effort catch 捕获，fixture 在重放前失败并把原 cause 交现有 diagnostics。没有新诊断协议，也不输出异常消息、SQL、环境或 journal 正文。

## complete 与 held 语义保留

- 原 complete 的 SOURCE 事实消费、SOURCE/RESTORED_OWNER/RESTORED_INVALID_OWNER/RESTORED_EMPTY_OWNER/NORMAL_READBACK 阶段结构及调用保持。
- 恢复回调只登记原事实/inbox，原 `ack == !restoring`、完整退款行前后相等断言及 blocked consumers 保留；normal 仍仅允许 complete 原 DONE 重放。
- gap 的 SUCCESS 观察不能变成正常启动授权：既有 shared reader 仍将其归为 gap/held；本片没有改变 normalBootEligible、外部编号高水位或 cleanup。
- UNKNOWN/PROCESSING/NOT_FOUND/CLOSED 均不能在本 SOURCE helper 中投影为成功应答。没有原 PAYMENT、原退款申请、精确 journal 或已持久退款事实时，不得用回放补齐经济事实。
- 原 `BACKUP_READY`/`AFTER_BACKUP`/`FAULT_READY`/`STOP`、scope/UUID/schema guard 与经济行等值断言未删除，没有修改独立清理所有者。

## 新直接控制源码（七条普通 @Test）

1. 非当前默认渠道及 alias wx number 的原退款 SUCCESS 应答字段准确。
2. UNKNOWN/PROCESSING/NOT_FOUND/CLOSED 不能变 SUCCESS。
3. journal SUCCESS 但缺持久退款事实时，重放必须拒绝，原退款身份/金额不变。
4. 错付款 Reference/原 hash/商户/app/交易/金额/报名身份不能进入观察。
5. 错原退款号、原退款 ID 或金额不能被修成合法观察。
6. 回放保留已持久 REFUND 的精确 Reference、渠道、原号码及金额。
7. 冲突的已持久退款事实不能被 journal 字段替换，原付款 hash 不变。

这些 unit 只使用显式 typed 值量测 owned SOURCE 边界，不声称 SQL、SDK 验签或真实 provider 成功。现有完整恢复 fixture 才负责未来受控环境中的真实 Mapper/TX、独立 journal 与原 callback/inbox 接线。没有运行这些控制、编译、Java/DB/HTTP、parse/import 检查或 CI；仅允许的源阅读、编辑、git diff、摘要和秘密扫描。
