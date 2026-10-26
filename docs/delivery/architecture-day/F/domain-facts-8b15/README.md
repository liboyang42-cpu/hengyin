# 固定恢复领域 reader：源码合同与有限失败定位

结论：本片保留原事实判定，不声称已修复四个宿主 ValueError。公共 parent 为
`8b150cba25938a10cef8583b51454b6c3a40c864`，独立分支
`codex/architecture-day-f-domain-facts-8b15`。不修改生产、Java fixture、C 宿主 probe/test、workflow 或 normalBoot 准入。

## 原实际失败与证据边界

用户提供 full740274102998 四个宿主失败：withdrawal-complete
121413503272/12629098766、withdrawal-gap 121413503270/12629248109、
refund-complete 121413503305/12630000593、voice-gap
121413503298/12629522595。SOURCE child exit0/NO_TYPED_FAILURE，尚未 RESTORED_OWNER，
primaryReason UNKNOWN_PRIVATE_FAILURE，schema absence 已独立确认。
本片没有取得四份私有 snapshot/journal 或具体 Python predicate；不能把某个静态假设当原运行首因。
refund-gap 的 Java214 由 A 处理；不归因于本 reader。支付 gap、voice-complete 既有成功不重实现、不移称本 SHA 验证。

## 核对出的真实合同（维持原判定）

| 链 | 生产及 fixture 来源 | reader 必须保留 |
|---|---|---|
| 新提现 | `service/support/WithdrawalBalanceAdmissionService.debitCash` 同事务插入 DEBIT、绑定 balance_deduct_id、插入原 transfer_intent；`CashWithdrawServiceImpl.recordSubmissionIntent` 要求原 snapshot、markSubmitted，再 append SUBMIT_INTENT | 全原 ID/actor/毛额/余额；masterRequestHash 对原 snapshot；ledger requestHash 对原 SUBMIT_INTENT，两个 V1 不合并 |
| 历史提现 | `domain/dto/CashWithdrawTransferIntent` 的 TRANSFER_INTENT 构造；RestoreWithdrawalReconciliationFixture.source 原显式历史记录与独立实际扣减 | master_success 不伪造账本 DEBIT/SUBMIT；保留原 origin/recipient/CW/CD；无新 SUBMIT |
| 退款完整 | RestoreRefundReconciliationFixture.source → 原 paid/save/review/payout/facts/handleRefundNotify；原 RefundServiceImpl、WechatVerifiedPaymentFactService、WxPaymentCallbackInboxServiceImpl | 原消费 payment ID/hash、68 元毛额、20 元退款、原 transaction/provider ID、REFUND fact、原 biz_type2/DONE2；保留 binary inbox HEX 大写与 link CHAR 哈希小写的原区别 |
| voice UNKNOWN | NpcVoiceEnrollmentService.decide 先保存 admission profile_version；finish 的 `!"UNKNOWN".equals(state)` 明确跳过 profile update/version 推进；Mapper.complete 保存原 row version | UNKNOWN row version 必须等原 journal admission version；voice_id 仍 null，禁止 QUERY/重发，不改成 +1 或 +2 |
| voice READY | 原 finish(PENDING) 与 finish(READY) 各经过 profile CAS；NpcProfileMapper.updateMerchantNpcVoice 每次 `voice_version + 1`，随后 row.setProfileVersion(current.getVoiceVersion()) | READY projection = 原 journal admission +2；journal identity 保留最初 JDBC 数字，不能改成当前 projection |
| typed capture | C `capture/table_rows` 对普通数值列 CAST AS CHAR；二进制列 HEX；highWater 另保原 max/next | 不转换或删经济行、列型、NULL、hash、高水位；backup==restored 和完整 cohort backup==fault 原断言不变 |
| 实际 owner | C run 调 domains.evaluate；每个独立 owner boot 后 domains.verify_owner；原三个 Java adapter 均发 owner/phase/readback/blockedMethods evidence | 所有原经济行与原 provider identity/submits/prefix，不接收 guard-only boolean；gap 永不 normal boot；只原退款 pending observation 例外及原 inbox allocator 例外 |

## 实际代码增量

1. reader 新增固定 `DomainFactMismatch(ValueError)`，保留每条原失败消息及 ValueError 兼容；没有吞错或改变成功返回。
2. `failure_context(failure)` 仅把本模块确切异常及预审固定 literal 映射到固定 contractCode；未知、其他异常、附加字段/文本仍 UNKNOWN_DOMAIN_FACT_CONTRACT。无 row/SQL/body/token/动态值输出。
3. voice version 明确校验真实两种 wire 表达：capture projection 为正十进制 string，journal admission 为正 JSON integer，均在有符号 BIGINT 范围内。拒绝 bool/float/数字字符串冒充 journal、缺值、前导零/小数/空白 projection；不填默认、不重写 journal。
4. 原 UNKNOWN 相等、READY +2 判定、原正常/缺口 eligibility、原 verify_owner 和所有经济/hash/highWater 断言保持。此严格类型收口不是原四个 ValueError 首因已证。

## C 共享接缝（仅 review，未改其文件）

`host-context.review.patch.b64` 解码后 SHA256：
`01544928f4c10e78bfe5da7650d1001d502dd9d63f20d51f8bef47f456509069`。
在 C 原失败 receipt 只新增 `domainContractFailure: domains.failure_context(failure)`。
原 `primary= failure`、原 primaryReasonCode/UNKNOWN_PRIVATE_FAILURE、原非零、BLOCKED、dispatchRelease=false、RPO/RTO UNKNOWN 和清理完全不变。
C/root 可串行应用；不需要更改 evaluate/verify_owner 参数、snapshot、journal 或共同协议。
不是用新 enum 回填旧运行证据；原四份旧运行的精确 predicate 仍 UNKNOWN。
未知异常、KeyError/Decimal 转换或 canonical restore 自身失败不会冒充已确定 domain predicate。

## 控制 SOURCE 与交付

原 35 个 test 方法逐体保留；新增 10 个直接方法 SOURCE：UNKNOWN +1/+2 拒绝、READY 必须两次 CAS、journal 不可改写/以当前版本替代、journal 严格 integer、projection 严格 CAST string、实际 owner/Mapper/C fixture/capture 静态版本合同、两个实际提现 V1/原 snapshot、退款原毛额/DONE、失败消息与有限 code、canary/外来异常 UNKNOWN。
静态源码合同只证明当前源文本的对应关系，合成输入不替代 MySQL/真实 owner 运行证据。

允许的实际检查仅 Git diff/apply-check、文件 SHA 与传输编码回读；所有 Python/Node 测试、parse/import、编译、Java、DB、HTTP、容器、CI 均 NOT_RUN。
root 收齐 C 宿主固定阶段诊断后统一验证。若仍失败，应回读对应私有 predicate/snapshot 及本片安全 code 后再定点修；禁止通过加 version 偏移、补默认、删行/抹高水位、提前 normalBoot 换绿。
