# H：同轮两个恢复消费者夹具的构造接线

结论：仅 SOURCE 修补完成。编译、测试（含 child JVM）、解析控制、CI、Java/DB/外呼全部 NOT_RUN；不以源码代替运行验收。

parent：fb871607e4c4a5a038776201ff0b785f999f9ab1
parent tree：b859b07a802ab3c6303e167aef4a8a21c094ef56

## 唯一 owned 文件与实际首因

| 文件 | 原 parent blob | 实际首因 |
| --- | --- | --- |
| RestoreMoneyDispatchTest.java | 778e1c5e2c815cf19a212e822ff612fde63297ad | run40219238315 / SBOM job121247109763 原167行调用不存在的 ClubEventOpsServiceImpl() |
| RestoreDispatchConsumersTest.java | 20eeee952da32104a678fe751eac805f5ecf3b2b | 同轮 HTTP121247076937 / mixed121247089603 / backend121247109875 / CoopOrderStation121247151229 原227行 CommunityPostServiceImpl 构造不匹配 |

两项均为现存恢复 unit 的构造接线漂移，不按多入口日志拆成多个业务 BUG。本完整 delta 同时包含两文件，从同一公共 parent 建立；不改任何生产服务、其他 owner 测试或共享 helper。

## 真实构造与 guard 顺序

已 rg 现存构造调用，并读 parent 生产类及现有 fixture：
- ClubEventOpsServiceImpl（ce20e3511dcdb43376333c8218743b83c06198cd）只有七参/八参构造。ClubOccurrenceCancellationTransactionBoundaryTest（d3f5c8d84f6fd4dff001cb0d316d41532b18aa2e）实际取消装配用八参；ClubEventOpsServiceImplTest 同时保留七参原用途。
- CommunityPostServiceImpl（dcc8e834e8bb9f5a593bc975f2de4a8836e9391d）只有六参构造。CommunityPostServiceImplTest（b6248ccdc34e8f685c63681bdaf784a00a3851cf）使用真实 CommunityPostLifecyclePolicy + Mapper、安全、audit、trust、媒体回执依赖。

生产 cancelOccurrence:437–450 首句是 RestoreDispatchGuard.requireDispatchAllowed()，先于请求校验、事务、topic/registration 当前锁读及 capture/observe/finalphase；runMaintenance:1470–1475 同样先 guard，随后才 boundedLimit 与首个 Mapper 扫描。fixture 的目的就是证明恢复模式在这些依赖前拒绝；不伪造 reader/事务/支付事实，不让无参或 noop 生产默认吞掉缺依赖。

## 完整最小修补

1. RestoreMoneyDispatchTest.adjacent：
   - import 真实 IClubAccessService；
   - 使用真实 ClubEventScheduleMaterializer 与现有八参 owner 构造，全部必要 service/Mapper/role/team 依赖为显式 typed mocks；
   - 原 blocked(cancelOccurrence(9L,"actor",null)) 精确类型/message 断言保留，补依赖零调用断言。
2. RestoreDispatchConsumersTest.durableNotificationWorkersPauseBeforeEnumeratingOrClaiming：
   - 依原六参构造装配 CommunityPostMapper、真实 CommunityPostLifecyclePolicy、安全 gate、audit、trust、媒体回执；
   - 原 blocked(runMaintenance(10))、原通知零 claim/零调用与 never getTransaction 断言逐字保留；
   - 新增社区 Mapper/安全/audit/trust/receipt 零调用断言，证明拒绝不是被未装配依赖的异常代替。

没有生产无参构造、默认空依赖、反射绕构造、修改 guard/权限/业务返回、跳过或放宽断言。原 16+22 个 @Test 源码场景保留，无新增/删除测试方法；原 child 环境/限时/cleanup、金额、原号、UNKNOWN、迟到成功、ASR/voice 原身份、actor/scope、派发、原查询可读及正常模式正控均未更改。

review.patch.b64 解码为两文件完整 delta；PATCHES.sha256 为解码补丁摘要，POSTIMAGE.sha256 为两文件作者后像。工具只用于读源/编辑/diff/摘要/提交，没有执行测试、解析控制、编译或 CI。Mapper/SDK doubles 仍是原 unit 边界，不充当 MySQL、供应商验签、真实 I/O 或事务回滚证据。

施工在 /workspace/hengyin-h-restore-constructor-fb871 独立公开源码补丁目录；原 H checkout 与他人修改未回退。root 唯一集成，收齐同轮源码后按影响定点验证。包含本文件的提交是最终双文件交付，parent 仍为上述 fb871。
