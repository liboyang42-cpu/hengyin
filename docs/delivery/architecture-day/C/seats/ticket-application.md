# 报名票签发/席位回读应用归位

代码基于冻结 `6d113bd0ec177ea6d2423fa2f76e7e50b44ed5ac`，工作树 `/workspace/hengyin-c-seat-verification`、分支 `codex/architecture-day-c-seat-verification`。本片为纯机械归位，不顺手更改检查或异常/资金语义；A366、下单、退款、G费用、会话设施、CI不改。

## 实际方法闭包与选择

| Controller簇 | 实际依赖/副作用/事务 | 本片归属 |
|---|---|---|
| ApiVerify.issueDynCode(registrationId,seatNo) 与一参数Java入口 | registration读→归属/支付→明确选席metadata或seat.prepare→perk判据→活动取消/时效→原HMAC+nonce+TTL→本地PNG→AjaxMap；外层无事务，seat准备保留原短事务 | 完整迁往下述单一应用；HTTP仅取actor/委托，一参数入口仍只转null seatNo |
| ApiVerify.seatReceipt | 原actor+registration/seat/activity→原SeatService授权/当前锁读→原520 UNKNOWN或原Receipt；外层无事务 | 同一应用投影，不扩为核销，不解释成新使用 |
| ApiVerify.citynode.issue/redeem | RoamMapper、签名、本地PNG；当前商家VERIFY/据点merchant归属→锁/CAS→CouponService→审计；redeem原rollbackFor=Exception事务 | 方法体及事务原样保留在Controller，后续可独立归位 |
| ApiVerify.groupcode.issue/redeem | 既有IGroupCodeService；redeem先当前商家VERIFY/owner/actor/role | 已是服务委托，不复制一套逻辑 |
| ApiRegistration.scanDynamicCode/groupMemberTicket/scanQrCode及Java重载 | 原签名/类型、当前actor/商家/团、支付/使用窗；复用C席位服务、原订单/权益、积分/成长；6d拒绝分支授权终态回读 | 全文件不改，保留并行财务owner的接线位置 |
| ApiRegistration.scanQrCodeChapter/Station及helpers | resolveRegistrationByScanCode、assertScanTypeMatchesOwner、resolveScanContext/Explore、章节/站点候选、原perk/合作/报名service、原奖励 | 本片不触碰，不搬入新的大类 |

真实客户端签发调用者是 `scene-qr-ticket.load` 与 `pages/play.ensureTicket`；回读调用者是 `utils/registration-seat.seatRecovery`，由四个通用扫码入口和带队席位分支复用。无新增HTTP路径、参数、caller或兼容fallback。

## 给 root/F 的精确类型与合同

新增唯一生产类型：`com.chengyinhub.web.service.verify.RegistrationTicketApplication`，`@Service`，143行。构造器5个业务依赖加原同表达式签名配置：

- ICmsRegistrationService registrationService
- RegistrationSeatService registrationSeatService
- ICmsActivityService activityService
- IPerkLedgerService perkLedgerService
- QrcodeServiceImpl qrcodeService
- `@Value("${verify.dyncode-secret:${token.secret}}") String dynSecret`

两个实际入口：

```
AjaxResult issueDynCode(Long actorMemberId, Long registrationId, Integer seatNo)
AjaxResult seatReceipt(Long actorMemberId, Long registrationId, Integer seatNo, Long activityId)
```

无新interface/DTO/框架。应用不继承BaseController、不读HTTP/登录上下文、不收Controller lambda、不回调Controller、不加@Lazy或可选依赖。actor来自Controller原appUid，失败仍为0并进入原拒绝顺序。原response字段逐字保留：选择信息无code/PNG；正常code/qrcodeUrl/ttlMs/expiresAt/type及可选seat元数据；receipt为空520，成功返回原Receipt对象。原ServiceException继续原样传播。

应用不加事务：先prepareOwnedSeat短事务提交、后活动检查/签名/PNG的原次序不动；不扩大锁跨渲染窗口，也不以本次迁移修正原有时序。纯回执应用仍委托原Service可写锁读事务，不挂readOnly。五分钟TTL常量归同一应用，Controller据点码使用其同值别名，避免两份数值；据点签名配置/二维码依赖仍留原Controller供未迁移方法使用。没有二次定义签名或回执规则。

## 机械证明与测试接线

`ticket-application-move-equivalence.json`记录原base40SHA和hash：签发体只归一原actor获取/局部alias、BaseController.error→等价AjaxResult.error后，与迁移体**逐字相同**；receipt体只将appUid实参改actor参数后逐字相同。其余5个Controller方法体及citynode事务注解不变，6个HTTP映射同序不变。ApiRegistrationController、RegistrationSeatService、VerifyDynCodeUtil三份6d回执顺序文件逐字未改。

ApiVerifyController实际322→216行，注入bean10→7；只删除原簇独占的registration/activity/perk/seat四个依赖，新增应用委托。不是将整个Controller改名或平移。

5个受影响旧fixture类63个case的名称与assert/verify行hash保持：ApiVerifyControllerTest19、ExploreIssueCodeGateTest7、FreeOrderCancelEntryGateAcceptanceTest2、RegistrationSeatEndpointTest16、RegistrationSeatReplayBoundaryTest19。依赖装配到新真实owner，禁止生产nullable fallback；Endpoint中setup后替换activityService的测试也重新构造应用。单独调用citynode/groupcode的其他旧fixtures无需装配未使用的报名应用，其方法体未改。

新增 `RegistrationTicketApplicationTest` 9条直接应用/HTTP适配场景，覆盖actor最先拒绝、原归属/支付优先、选席零准备、原prepare→活动→签名PNG顺序/身份/TTL、异常不改协议、PNG失败保留原响应、UNKNOWN/原Receipt、不进入执行口、两HTTP和Java兼容入口的真实actor与原对象返回。

**未运行Java**：上述旧63/新增9、编译、真实Spring构造/启动与MySQL均待root统一执行；不借父候选的测试结果。实际轻量Node为4文件 **43/43 PASS，0 fail/skip**，含新应用源码约束6（错传actor/删除归属检查的负控）、原回执接线6、席位VM23、扫码8；逐文件输出见 `ticket-application-node-results.json`。8个变更Java只做词法平衡检查，不冒称编译。JS syntax与diff检查通过，限定秘密扫描另执行。

无需迁移。回退须连同Controller/app/fixture装配整片回退，不能删席位或审计事实；原数据、UNKNOWN和资金政策不变。root按本提交差异集成并转F登记该精确应用类型，保留A/G及其它owner修改。下一职责完整的候选是citynode核销权限→CAS→券→审计事务闭包；本片没有提前迁移它。
