# 原付款冲突负控夹具接线

父源：`54ab9282bee31c7b6dd276682f4c86e9903709c9`。只改 `WechatPayCallbackForgedSignatureTest.RegistrationFailureFixture`、必要同文件 caller 与一条新回归；不修改 SDK/A/B/G 生产、B helper 或 CI；原32测试声明及触发场景保留。除 root 明确裁定的旧 REFUND case 外，其余103条原 assert/verify 源码行按序保留；原 any/anyMap imports 完整保留；wireParser 到文件末尾逐字相同。

wireParser 完成 B SDK/intent 绑定后，取得其实际 `verifiedFacts`，同对象装到实际 registrationService；inbox 复用现有 `passThrough(null,verifiedFacts)`。reader 是真实 B service + 现有 MemoryMapper/unit PTM，inbox/业务 mapper/G 是明确单位夹具，**不是 MySQL/真实事务/G验收**。

原 fixture 缺 owner/member。现建合法主题 scope、单行原父单，实际 RegistrationTopicFence 仍执行 topic→整单当前读；与后续 CAS 后单行当前读分开观察。金额1.00、transaction、默认 CAS=0、原后读/告警断言不变；不通过 mockStatic/清调用历史/宽化 matcher 绕过新锁读。必要旧 caller 只追加精确原整单/topic 读断言。G 显式 mock，消费 mapper/分账接口仅观察零调用，无 optional/null fallback。

新增一条要求：gateway 实际保存可读原 fact；同 reader 与原 Reference 的 registerVerified/processVerified→topic/order 锁读→G require→原 claimPaymentSuccess→原当前后读→G retain 顺序成立；仍精确 payment_state_conflict；报名状态/原金额不变、无消费凭证/分账/关闭/退款变更。提前 fixture FAIL 无法满足这条回归。

静态检查：diff --check、Java 词法括号、原 imports/assertion/32 case 保留及 B helper 后缀相同；新增后33声明，原imports及API_V3_KEY所在原前缀/行号不动。Java编译/JUnit/MySQL/CI未运行，不借 immutable54 的 run40091200759 结果。

REFUND case 按 root 明确裁定纠正：保留 payment trade_state=REFUND/原100分金额场景，加入原已用100积分/尚未返还夹具；原不可信退款 claim 期望已过时，现精确观察 `refund_requires_verified_original_refund`（对应 A.applyWechatPayCallback REFUND 分支）。仍实际经过原 scope/inbox；无 refundNo/refundId/独立可信REFUND SUCCESS 时，退款claim/积分返还claim/付款claim/消费凭证/钱包/积分账本/分账/退款服务均零调用，原金额/状态/已用及返还积分保持不变。只纠正旧测试，不改生产或模拟禁止路径；其余原断言保留。
