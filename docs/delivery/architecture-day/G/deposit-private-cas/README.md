# 关闭保证金公开三参写旁路

直接parent：`e4bdaceb8c1eb800b38c93601bbbd82ca15b26e5`。新分支 `codex/architecture-day-g-deposit-private-cas-e4bd-20261024`。只含该公开组合之后缺失的typed fixture与私有CAS收口；不重放a726/a96，不用中间装配父树460df013覆盖root。

## 生产/API合同

- 从ICoopInviteService移除三参markDepositPaid。原实现**方法主体逐字不变**，仅改为private `applyConfirmedDepositPaid`，唯一调用者为已通过B原Reference/业务号/CNY/分币/TX验证的四参入口；没有public/default/optional兼容写路径。
- Topic SUCCESS直接四参，保留原topic→invite→attempt当前复核与状态/TX读回；删除其重复static完成和不再使用的B字段。完成恢复票统一在四参原业务Tx内。String查询wrapper仍锁外SDK、原短Tx内四参；afterCompletion退款资源脱离类与e4bd逐字一致。
- Router/ApiCoop生产已是四参，未重复修改。没有资金算法/状态政策/比例/收款人/DDL/依赖/CI变化。
- 新发现并迁移 `scripts/e2e/E2eWechatPayStub.java.stub:127`：删除三参调用，仅只读查找**已存在**的B原成功事实，再传该Reference四参。stub不recordVerified、不生成引用、不凭JSON成功造事实。缺可信事实拒绝；E2E正向场景必须显式提供匹配的既有事实，不能继续靠假支付正文自动置成功。

## 原场景/静态证据

迁移全部原三参mock、verify与直接测试；原金额不匹配/未知订单/旧type2/幂等/TX冲突场景仍在，改为明确原Reference和实际事务调用。原legacy guard/金额/CAS主体未改。删除API的三参never编译引用改为严格四参never/一次错误hash转交断言，另加公开API不存在/私有CAS可见性检查，以及缺或错Reference在DAO/CAS前拒绝。G恢复票用例改接**实际CoopInvite四参owner**，不mock它返回成功；SQL9的B证明/错误注入只把验证器装配从Topic移到真实owner，所有原业务断言保留。

保持E2e原DEP正向SUCCESS断言：由显式只读Stored adapter提供相同原引用并精确mock四参；额外验证无事实正文为FAIL且owner零调用。该unit不是provider/存储验证。完整fake E2E若未预置B可信原事实会拒绝押金消费，这是移除旁路的明确边界，未伪造VERIFIED回填。

e4bd尚无07f785的七文件fixture迁移，本候选包含其必要源码增量（不附旧兼容文档）。四路旧ACK、签名、APP/商户/币种/金额、同原引用重投等断言保留。测试声明：CoopInvite120→122；Gunit16→16；depositSQL9→9；退款SQL29→29；router9→11；status5→6；其余被迁移类不减场景。普通声明仅源码统计，不是已执行case。

已执行source/stub diff --check、全仓非文档caller搜索/实参计数（仅1或4）、原CAS主体逐字比对、唯一私有调用点检查、Java分隔符静态检查（不是编译）、有限补丁秘密模式及逐文件SHA。Java/MySQL/provider/CI全部 **NOT_RUN**。无真实通过声明、无skip、无原SQL/AfterCompletion断言削减。

## 应用方式

root直接采用本提交相对e4bd的delta即可。`production.review.patch`仅ICoop接口/两个G生产类/E2E stub；`fixtures.review.patch`为全部仍缺fixture。若脏树已先接07f785，则只使用 `post-07-only.review.patch`（以e4bd+07夹具为前态），避免重复fixture；不要同时应用两套patch。逐文件hash及全部可执行调用点附在本目录。提交/patch均不含root、B、A、D、F其他文件覆盖。回退不能恢复公开三参旁路，也不能删除原fact/恢复票/审计。
