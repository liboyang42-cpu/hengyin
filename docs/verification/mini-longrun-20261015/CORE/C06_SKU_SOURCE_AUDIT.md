# C06 SKU 源码只读调查收束

状态：EXHAUSTED（本次授权切片未确认可行动新缺陷）。验证：NOT_RUN。仅记录源码依据，未执行 Java、Maven、数据库或其他验证；既有测试的存在不等于本轮通过。不计新增 bug 或修复任务，保留原 runtime owner。

## 真实扣减链

- ApiCartController.orderSettlement（约 134 行）通过注入的 IUmsMemberCartService 调用 settleCart，捕获 ServiceException 后返回错误。
- UmsMemberCartServiceImpl.settleCart（约 262 行）有 @Transactional(rollbackFor = Exception.class)。先 lockCartsForSettlement，按购物车 ID 排序并调用 lockUmsMemberCartByIdAndMemberId，缺行即拒绝，以同一购物车行锁串行化重复结算。
- loadValidatedCarts 校验请求 ID 集合、返回集合、会员归属；requireQuantity（约 477 行）要求 quantity 非空、正数且不超 MAX_CART_QUANTITY。随后校验商品上架/删除状态、SKU 商品归属、价格及库存，并使用当前 SKU 数值快照。
- settleCart（约 345 行）调用 PmsSkuServiceImpl.deductStockForProduct，最终执行 PmsSkuMapper.xml 的 deductStockForProduct：同一个 UPDATE 使用 id、product_id 和 stock >= qty 条件执行 stock = stock - qty。返回 0 行时抛库存不足错误；购物车删除非 1 行也抛错，外层事务保留原订单、明细、积分及库存一并回滚的结构。

因此不能仅凭前置库存查询把本链判为先查后写超卖。当前 SQL 没有额外 qty > 0 条件，但唯一真实业务调用已执行正数量门禁；未确认负数可达入口，不能据注释直接报告刷库存漏洞。

## 旧方法与回补边界

全仓源码检索中，旧 deductStock(id, qty) 只有 mapper/service 接口及实现声明，没有真实业务调用；业务只用绑定 productId 的 deductStockForProduct。

mapper 中对 pms_sku.stock 的写入集中在 PmsSkuMapper.xml。PmsProductServiceImpl 编辑 SKU 使用后台输入 stock 的绝对设置；这是现有配置行为，未据并发猜测改变其库存政策。未找到真实库存回补调用链；OmsOrderServiceImpl 当前是基础 CRUD，不能把缺少回补擅自定义为取消、退款或退货 bug，也不新增回补流程。EXTRA/E04 券代码未编辑或重复改造。

## 既有静态覆盖线索（未运行）

- PmsSkuMapperStockContractTest 已有 SQL 绑定 SKU、productId、库存条件的映射检查。
- CartSettlementTransactionalH2Test 已有真实映射 SQL 的归属不符/库存不足/成功扣减、Spring 事务代理、同购物车并发至多一次及库存/积分/订单/购物车独立读回断言，并覆盖合计数量上限拒绝无写入。
- ApiCartControllerSettlementTest 已有服务扣减成功/失败等既有回归。
- 历史 B03 README 已明确 stock>=qty guard 存在且未扩展 cart；此项是只读复核，不能重新计为 B03 新修复或运行验证。

本次未修改业务源码、SQL、控制表或测试；没有新的源码写权申请。后续真实 MySQL 事务行为、业务现场与设备层均未在此验证。
