# Commerce 通用写入口不可变快照修复

代码补丁完成，Java/MySQL 尚未运行。`review.patch` 是 def9 上的一片完整 delta：3 个生产文件、2 个新增测试文件；本交付提交只保存 H 补丁及证据，生产源由 root 串行应用。没有改 E 的 checkout/消费实现、Controller 权限、已有测试或 CI。

基线 `def951bef36a94417d90259906f6bda6abe6efb3`，tree `f73e683f52a695b06486436b9c49aad72e3ffa2d`。本地旧 H 工作树 `/workspace/hengyin-h-review` / `codex/architecture-day-h-review` / `0558daca3cdb3bd1989a7c0e7a656159a2eab1fe` 未切换；实现隔离在 `/tmp/h-commerce-guard`，通过 GitHub 读取 def9，四个原文件逐字校验 Git blob。候选仅从公共 def9 建立，不携带其他历史。

## 原触发链及调用者

- `/business/order` POST/PUT/DELETE → `OmsOrderController.add/edit/remove` → `IOmsOrderService` 通用入口 → 原宽字段 Mapper。
- 原 PUT 可省略 bizType，按已知 Commerce id 改 `entitlementMaxUsage/memberId/payAmount/orderSn/status` 等；E 后续的合法原支付消费从该行读取权益与归属。因此同一真实付款可能授予被改写的权益。物理删除还会丢失原订单身份。
- 普通积分商城 `UmsMemberCartServiceImpl` → 通用 `insertOmsOrder`。保留其普通单字段、生成 id、原插入及外层结算事务。
- 正式 `MerchantCommerceService.createOrder` → 专用 `orderMapper.insertOmsOrder`；`handlePaidOrder/applyVerifiedPayment` → 原 current-read、支付事实校验、CAS、授予、proof、recovery。均未改动，也未改为调用通用 Service。
- 生产通用 update/delete 调用者为后台 Controller；`ApiMerchantController` 的通用订单 Service 用于读取。没有发现手写 OmsOrderMapper 实现；新增接口由原 MyBatis XML 提供。已有购物车事务夹具以 CGLIB 代理真实订单 Service，新增事务注解不改变其 insert 路径。

## 修复合同

1. 通用 POST 拒绝 Commerce 类型、保留单号前缀及 Commerce 专属权益/请求/proof 标记；普通非 Commerce POST 原样插入。正式 checkout 的专用 Mapper 建单不受此 Service 闸影响。
2. 通用 PUT 先在 REQUIRED 事务内用原 id `FOR UPDATE` 读取完整当前行，禁 MyBatis 旧缓存。是否 Commerce 以数据库原行识别，不能通过传入类型/缺字段隐藏。普通单保留原宽字段编辑，但不能转成 Commerce。
3. Commerce 的归属、单号、类型、金额、状态、TX、权益、请求及支付事实身份发生变化时整次拒绝。金额用数值比较，19.900 与 19.90 不构成篡改；null 沿原动态 SQL 不写语义。允许备注时使用新白名单实体，只携带原 id、两项备注及更新审计字段，绝不转发提交实体。
4. 单删和批删共用同一事务路径：id 排序去重，所有原行先锁定，再检查，任何 Commerce 行都拒绝。缺失 id 保留零影响语义；普通单删除后核对真实行数，不符抛错整体回滚。无事务直接调用 update/delete 明确拒绝，不能把锁退化为 autocommit。

生产文件：`OmsOrderServiceImpl.java`、`OmsOrderMapper.java`、`OmsOrderMapper.xml`。Controller 无需新增重复分类；通过 Service 覆盖后台及直接调用者。原 Mapper 所有写方法和 E 专用付款入口均保留。

## 验证事实与 root 待执行

已实际执行 Python/轻量文件检查：4 个基线 Git blob 精确一致；生成补丁 `git apply --check --whitespace=error-all` 并实际应用后逐字比对全部 5 个输出文件；XML 解析、原 XML statement 逐项保持、当前读 cache flags；Java 词法括号检查（不是编译）；仓库 `secret-file-gate.scan_paths` 无命中。原有测试文件未进入补丁。哈希及文件清单见 `verification.json`。

新增源码 `OmsOrderCommerceGuardTest`：8 个普通 `@Test`，只证明预期的策略/转发边界；其手动事务标记不是回滚证据。

新增源码 `OmsOrderCommerceGuardMySqlIT`：6 个普通 `@Test`，待 root 登记及执行：伪造 POST 零建单；拒绝篡改后真实 E checkout/原 B fact/原 E consumer 原权益仅授予一次并完成原 recovery；普通编辑删除及 Commerce 备注正控；含 paid proof 的混合/单删零改变；刻意旧 RR 快照仍由 current-read 识别新 Commerce 行（保留旧普通 count=0 断言，外层按 rollback-only 结束）；实际 DELETE SQL 后注入行数异常并验证整笔回滚。

真库夹具复用 `P0AcceptanceSchema`、`MybatisItDatabase`、原 XML/原 migrations、真实 `WechatVerifiedFactTestSupport.realFacts/realPaymentGuards` 与 Spring 事务代理。正式 E checkout 也实际参与；预下单端明确是测试 double，且本地原 attempt guard 的 SDK double 拒绝外呼。没有签名、HTTP、真实微信证据。scratch 仅允许 hosted/loopback/显式 env/UUID 独占 schema，按原 helper 清理。

**未运行**：Java 编译/JUnit/MySQL/Spring 全装配/HTTP 权限/SDK/provider/CI；gitleaks 未安装，本地未运行。root 需做其原秘密扫描与统一验证，不可用本静态结果代替。

root 应登记 MySQL 新类 6 项并沿原支付事实套件提供 `B_PAYMENT_FACT_DDL_APPROVED=1` 及明确 scratch 连接 env；运行新单测 8 项、新 MySQL 6 项，以及原 Commerce/购物车/支付事实相关测试。全部原用例与期待值保留，无测试跳过、baseline 扩大或生产配置修改。

## 应用、迁移与回退

在 def9 或经精确冲突核对的集成源执行 `git apply --check <H目录>/review.patch` 再应用。无新 schema/DDL/依赖/数据回填或数据清理；测试只使用已有迁移。未授权部署。回退此补丁会重新开放通用 Commerce 篡改/删除，不能把回退视为安全恢复；保留已有订单、事实、权益与回执。
