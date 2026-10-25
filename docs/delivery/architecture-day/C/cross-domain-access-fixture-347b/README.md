# C · CrossDomainFaultIsolation 的 canonical access fixture

parent `347bcd0226c3bed71616e38ea7c1391fc76efe5c`。原真实 storage40221047209 首因是 setup 的 T19-ACCESS 缺 canonical NOT NULL ticket_id；不是产品授权/资金错误。新增源码全部 NOT_RUN，没有解析、编译、测试、DB、HTTP、资源或 CI 执行。

实际唯一调用链：`CrossDomainFaultIsolationMySqlIT.sameWindowRetainsOriginalEconomicIdentitiesAndRequiresUnpoisonedNotificationProgress` → `CrossDomainStorageFixture.open` → `installRoute` → 原 `TopicRouteRuntimeServiceImpl.queryState/currentScope`。`ci/test_real_storage_it.py` 只静态读取本 fixture 的已有入场围栏，未修改该控制/registry/workflow。

原 fixture 没建 oms_ticket，也没有可复用的同域票行；现通过原 `P0AcceptanceSchema.baseTables` 原样加载 canonical oms_ticket，未写生产 DDL/migration/临时宽松字段。建真实正 ID81 票行，owner_type=1/owner_id=80、发行人原 MEMBER，与 topic80 及 T19-ACCESS 的 member/owner/topic/ticket 完整相连。

原主题名 cashless witness 的访问夹具显式标成现有 FREE_EXPLORE（product_type=2、票mode=2）；真实 ticketId 对应现有 `PurchaseKind.derive(ticketId, FREE_EXPLORE)=EXPLORE_PASS(3)`，不冒用“无票ID自玩”或零ID占位。该零价成功访问输入的价格、应付、积分/微信分项全为0，单席、原状态2/2；全部 canonical 必填金额、票价、支付期限一次补齐，保留明确未过期的 fixture 访问期限。不是经过下单/付款服务的验收，不虚构可信渠道金额/资金事实；实际经济见证仍是独立原商城订单19.90、原1990分/原transaction/hash与真实callback链，丝毫未变。

`assertRouteAccessFixture` 在setup及恢复末各以真实SQL join复核原登记/票/主题归属、原actor、同scope、价格与分项、库存/数量、状态与有效期。原 MEMBER+1 查询拒绝、原会话ACTIVE/version7/原JSON/零action行断言完整保留；未给查询添加写入、未替换任何权限helper或mock成功。

原1个@Test、150秒timeout、父pool maxActive12/maxWait2000、同窗provider/fault/通知原UNKNOWN及独立进展要求、原配额/nonce/回执原号、release/join与精确schema/Redis清理正文全部保留。测试正文只新增末端同票关联复核调用；未修饰 healthyWhilePoisonHeld，也不宣称该后续业务断言已执行或通过。

交付只有2个自身测试/fixture文件及本C证据；无A/G/E/D/H/B/F生产或共享源码改动。后像见postimages.sha256（不含清单自身，由Git tree绑定）。仅Git差分/摘要检查，不借用旧run的CLEAN作为新源码验收。
