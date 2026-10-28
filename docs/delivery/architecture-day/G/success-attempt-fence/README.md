# SUCCESS消费共同准入接线

直接parent `ce4cd7d8baddcb62aad85b212fd0d89b9fd4aec1`，分支 `codex/architecture-day-g-success-consumable-ce4c-20261024`。只在已合G70之后接线，不重交公共守卫/其4个unit，不改B工厂、FactService、IntentStore、CI/schema。Java/MySQL/provider/CI **NOT_RUN**。

生产仅一行：CoopInviteServiceImpl typed4由 `requireVerified` 改为 root 的 MANDATORY `requireConsumablePayment(reference)`，在私有CAS和恢复票完成之前，由共同owner取得原业务行锁并检查最新未关闭的完整冻结意图。返回同原Stored；不复制JDBC、不依赖环境merchant、不用旧bool/closable作替代。原私有CAS方法主体逐字不变，失败不消费/不清票，不退款、不重开。

A正文未修改。`a-common-admission.review.patch` 精确作用于 ce4c/def9 的 private `checkVerifiedPaymentAdmission`：金额/已关闭原票检查之后、G准入及成功副作用之前调用同一 `requireConsumablePayment(consumed.reference())`。该helper同时由取消preflight及成功消费调用，避免只修真实消费；root串行应用。对实际公共ce4c源码apply检查已过。

## 回归源码

- G unit16→18，原16场景保留。新增typed4共同守卫否认时DAO/CAS和恢复完成零调用；当前原Reference成功及精确重放只CAS一次。其余成功/错误引用夹具只按确切Reference返回Stored，不mock公共守卫对所有输入成功。原恢复票fixture理由用LOCAL_CONFLICT，不把CLOSED事实伪装成可消费。
- G专属MySQL9→11，原9个case及业务效果断言保留。新增独立case：①旧attempt已CLOSED且原号产生APP新attempt后，旧SUCCESS经实际B writer落fact/票4，typed4拒绝、业务行字节不变、票4/原hash保留；当前新attempt真实SUCCESS仍消费、原引用重放零新增写，旧票继续4。②仍是最新但已CLOSED的attempt，迟到SUCCESS同样只保留fact/票4，不能消费或置2。独立UUID schema/原DDL/真实store+mapper+guard，通知观察与SDK原单为独立对象；SDK是受控double，不称真实渠道验证。全部fixture仅测试源码，未执行DDL。
- 原第9条正向回滚用例不再让CLOSED事实通过新合同。改用既有CoopDepositRefund测试已支持的场景：原invite.status=3但deposit_status=1、当前attempt未关闭，真实SUCCESS在非payable原业务下生成票4；原typed消费/paid-TX CAS与票完成同Tx，故障仍回滚全部原业务字节，原fact/票4独立保留，重试至2。合作取消status始终3，不重新开单、不自动退款。原金额/原号/TX/单fact单票/回滚断言保留；新增CLOSED独立负例承接新准入要求。
- CoopInvite旧122条保留；缺/错引用的否认迁到共同guard边界。CoopDepositRefund SQL29条不变，仅给原两个明确typed事实double补共同方法的精确Stored返回；未改B拥有的RegistrationFundsMybatisIntegrationTest或六工厂/helper。

源声明数不是运行数。公共守卫自身逻辑/单测由root维护；本片不声明已验证支付、MySQL隔离或并发通过。没有skip、baseline/registry放宽、新依赖或新资金政策。

实际静态：source diff --check、私有CAS主体比对、调用次序/不重复JDBC检查、旧case名称保留、Java分隔符检查（不是编译）、A共享patch对真实ce4c应用检查、有限秘密模式及逐文件hash。source-sha256.txt给完整文件身份。根本守卫与域接线必须一起统一回归；A patch需root合入后才覆盖报名共同preflight。原afterCompletion资源脱离、原唯一键/receipt/UNKNOWN保护均不动；回退不得删原fact/恢复票/审计。
