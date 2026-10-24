# Typed保证金caller：仅原夹具迁移

Parent `477696c8eb2fa24bd3d7febc896415bf0d1b67c0`。`fixtures.review.patch` 仅6个既有测试文件及1个G-owned断言helper；生产router/controller、G ledger、接口、B/A代码、afterCompletion证明、CI/schema均未改。须与root已应用的a96四参接口、shared-callers补丁及B9实际fact reader合成；裸parent仍是三参生产，不能单独作为最终运行树。

## 已追完的caller/夹具

- Router直接unit、ApiCoop.depositStatus unit：精确匹配原Reference四参，原业务号/金额/TX/scope/owner断言保留。低层SDK map double使用显式typed事实的hash，只证明引用转交，不冒充provider/数据库验证。
- WechatPayCallbackFactsTest、WechatPayCallbackForgedSignatureTest（保留root新增A原proof/current-read全部正文）、WechatPaymentSuccessRecoveryTest、WechatPayCallbackHttpAckTest：三参mock/verify改为四参。`DepositVerifiedReferenceTestSupport.assertOriginal`只调用现有B reader核对该Reference确实指向刚由真实gateway保存的原PAYMENT、业务号、金额、CNY、TX及hash；不造fact、不替代业务成功判定。原false→true重试、抛异常、拒绝、签名/APP/商户/币种/金额及HTTP204/500断言原样保存。
- 新3个普通@Test：router缺id不触owner；缺hash由严格typed owner明确拒绝，不能依赖默认false；controller缺id/hash均409且pending不变、不能fallback三参。hash缺失可能到达typed owner，但实际G owner的B requireVerified在CAS前拒绝，不声称router本身验证了provider事实。
- 全仓Java/显式fake搜索，只有CoopInviteServiceImpl实现ICoopInviteService，没有遗漏的匿名/手写fake接口实现。E2eWechatPayStub.java.stub实现IWechatPayService并独立直接调用保留的三参兼容API，没走本次router；其ContractTest和stub不变，禁止给fake补伪VERIFIED引用。
- 剩余三参合法位置：G结算helper在原三参mark后自行核对同fact完成恢复票；CoopInvite原API状态/金额/幂等直接测试；SQL旧三参兼容测试；上述E2E独立stub。拒绝断言保留三参never并新增四参never，没有漏迁的本次router/controller正向mock。

## 源声明与实际验证

六类普通@Test：Facts 9→9，ForgedSignature 33→33，Router 9→11，SuccessRecovery 9→9，DepositStatus 5→6，HttpAck 5→5；总声明70→73。无参数化分支变化、没有skip。source计数不是执行数。

已执行diff --check、旧assert删除行=0检查、原参数注解/输入比对、Java分隔符静态检查（不是编译）、有限补丁秘密模式检查及逐文件SHA256。Java/MySQL/provider/CI均 **NOT_RUN**。本片不会把B9底层fixture的原意图/当前读问题用any-reference成功或放宽原guard覆盖；若原SDK fixture不能保存真实原fact，新断言应如实失败，须与root/B统一实际验证，不能报绿。无新生产缺口声明。

root使用fixtures.review.patch或本提交的七文件delta，不能把公共parent的其他生产文件整体覆盖脏合成树。原a726/a96、afterCompletion资源脱离及原资金规则均冻结未动。无DDL/部署。
