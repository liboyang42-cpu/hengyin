# 据点核销事务应用归位

本片源码以冻结 `d2d09a0deec78e298d0a814eca65bf80b8e121cb` 为父，独立工作树 `/workspace/hengyin-c-seat-verification`，分支 `codex/architecture-day-c-seat-verification`。执行器正常，源码和fixture已实际修改；不以active状态替代进度。不运行Java/DB/DevTools/CI、不部署、不发送其它会话消息。

## 类型、依赖与逐方法映射

新增唯一生产类型 `com.chengyinhub.web.service.verify.CityNodeRedemptionApplication`，`@Service`、104行、单一公共命令：

```
AjaxResult redeemCityNodeCode(long principalMemberId, String code)
```

构造器4个既有依赖及原配置：`IMerchantAccessService merchantAccessService`、`RoamMapper roamMapper`、`ISmsCouponHistoryService couponHistoryService`、`BusinessAuditEventMapper businessAuditEventMapper`、`@Value("${verify.dyncode-secret:${token.secret}}") String dynSecret`。无接口/模板/新依赖、无可选bean或@Lazy、无Controller callback/lambda。

| 旧位置 | 新位置/保持范围 |
|---|---|
| ApiVerifyController.redeemCityNodeCode(String) 的 actor获取 | 原Controller保留appUid及同一HTTP路径，获取真实SecurityContext actor后只委托应用 |
| 同方法的登录拒绝、当前VERIFY权限、HMAC/类型/玩家解析 | 完整移动至应用命令开头，顺序不变；VERIFY仍在事务内，不把权限读取放到事务外 |
| 同方法的玩家claim锁→据点/merchantId归属→pending coupon原快照→CAS | 同一应用命令内原样保留；不把主账号memberId、操作人或merchant行id混用 |
| 同方法的券失效跳过/库存发放→审计→原Ajax响应 | 同一应用命令内原样保留，库存或审计失败仍throw，不改为return error吞回滚 |
| Controller原@Transactional(rollbackFor=Exception.class) | 原属性移动到应用公共命令：REQUIRED、默认隔离级别、可写、Exception回滚；Controller不再拥有事务 |
| 其余报名票签发、席位回读、据点签发、团码issue/redeem、appUid | 6个方法体逐字保持，一参数报名兼容入口也不动；其它owner源码不改 |

Controller216→151行，注入bean7→6。权限HTTP入口仍为原Controller；实际当前商家权限复核随完整事务闭包移动，避免移到事务外或重复复核两次。actor getter经BaseController/SecurityUtils仅读取SecurityContext，无SQL/资金副作用；原业务检查顺序未变。

## 实际调用、事务与UNKNOWN

生产唯一调用来自 `pages/merchant/citynode/index.js.submitCityNodeRedeem → /api/verify/citynode/redeem`；其它Java生产引用只是流程说明，没有发现Controller内部this调用。原HTTP通过Controller代理拦截事务；新链为HTTP Controller→注入的Application Spring代理→公共@Transactional命令，无self-invocation绕过。商家服务require/resolve的当前数据库权限读取仍在该事务内；券service的`grantCouponById`为REQUIRED并加入同一事务，其内部grant调用不依赖自身代理另开事务。该闭包没有增加外部HTTP/付款调用。

原ID/签名/TTL、claim锁和CAS防重、checkin冻结coupon而非当前模板、couponGranted布尔与原文案、审计owner/actor/role和`citynode-redeem-poi-player`身份均保持。HTTP无外层事务时code=200在应用事务提交后返回；若参与既有事务则保留原REQUIRED由外层提交的语义。异常仍经原全局handler。客户端原分类器和持久UNKNOWN协议未改：提交后断网/畸形/不明确错误仍保留原目标待办，不新增回执查询、自动重发、资金或券政策。券库存失败的原消息仍对应整事务回滚，审计异常不被包装成成功/NOT_EXECUTED。

## 夹具迁移与证明

4个旧类的全部47个case名和assert/verify行hash保持：ApiVerifyControllerTest19、MerchantStaffRevocationAcceptanceTest2、RegistrationEquityMybatisIntegrationTest5、RoamLocationRetentionMySqlIT21。只迁移本命令的装配/观察owner。前两者保持原单测性质；后两者必须将TransactionInterceptor由Controller移至真实应用proxy，再注入plain Controller，避免只改注解而让实际回滚失效。券proxy、同一真实DataSource/事务管理器、SQL、库存不足/恢复、双线程闩锁、状态/stock/history/audit断言及cleanup不改。

共享隐私IT仅修改C的verifyController装配helper；其余源码除新增import空行外完全保持。未修改B reader及其生产控制器/Mapper，未修改A/G资金/券业务服务或D通知代码。原RoamMapper、XML、SmsCouponHistoryServiceImpl、RegistrationTicketApplication及ApiRegistrationController内容hash均未变。

`citynode-application-move-equivalence.json`记录base40SHA、业务体归一hash及旧case断言行hash：仅去除Controller actor获取行、将等价BaseController.error限定为AjaxResult.error后，命令体与原文逐字相同；其余6个Controller方法与6个HTTP映射保持。

新增8条 `CityNodeRedemptionApplicationTest` 已编写，使用真实Spring ProxyFactory/AnnotationTransactionAttributeSource/DataSourceTransactionManager和**mock JDBC Connection**，拟验证：plain Controller调用应用代理；权限/锁/CAS/券/审计同一可写连接；库存/审计失败rollback不commit；重复CAS不发券审计；权限在事务内最先复核；REQUIRED加入已有事务不内层提交；失效券跳过；匿名先拒绝。它不是MySQL测试，不以mock JDBC声称实际SQL回滚/锁/库存守恒，且本地没有执行Java。

实际轻量Node **3文件15/15 PASS、0fail/skip**：新应用源码约束5、原票应用归位约束6、真实据点客户端原请求/UNKNOWN场景4，详情见`citynode-application-node-results.json`。旧源码断言中Controller注解检查仅迁到真实事务owner，没有删断言。JS语法、7个变更Java词法平衡和diff检查通过；词法检查不是编译。47旧case、8新case、Spring实际执行、两个MySQL suite和startup全部待root下一批验证，不借父候选结果。

无schema变化、无业务/金额决定。回退须连同Controller/application/代理fixture整片回退，不能删除核销、券、审计或UNKNOWN。下一剩余Controller业务为据点签发簇；本片只收口完整核销事务，不提前拆其它职责。
