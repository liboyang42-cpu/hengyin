# Photo losing-claim unit：阻止重设stub提前改变被测状态

结论：仅修一个测试fixture接缝，不改生产。Parent `f745c50ccca2a8147d1535e3a2b8566fc05402af`，独立分支 `codex/architecture-day-f-photo-unit-boundary-f745`。

## 原运行与证据边界

root已独立核实 run40343667555/job121644767946/attempt1，ZIP12660846941/3120bytes/SHA256 `4ea4fda2053550c53acc5b15483148e193bf9d2ca57c8e547fe1802e5a256a2b`/CRC及source/run/attempt。ONLINE_PROVIDER_RUNTIME Maven1，probe1与Config9均无failure/error/skip/retry；PhotoCheckIntentServiceTest23中唯一failure为 losingClaimUsesThreeIndependentBoundariesAndKeepsPublicReadCallerOwned，原frame202，类型UNKNOWN。
父源unit SHA256 `76e93f19b2359e360d4f9b8ee7a25acc4d1f5c5ba58856c8607d10e0e050e939`；root给定class SHA256 `042dfe16ec3be1996eb7290cc54fdacc65ab711198613cef7ed6dc3138ab5592`、runner XML SHA256 `ef6d2444afcc7c79c1ece30ffcf1a92c6b079954a829105e3fc772d83ca86ccf`。
原raw XML/异常正文未下载，不补造实际expected/actual数。HTTP240未执行；probe/config通过不代表整组通过。

## 可证源码原因及最小修补

- @BeforeEach为mapper.claim安装Answer：将共享row改为IN_FLIGHT并返回1。
- 新失败方法用 `when(mapper.claim(...)).thenAnswer(...)` 重设时，Java先求值when的参数调用；mock此时已经有setup的Answer，该旧Answer会在executeOnce之前改变row。
- 生产executeOnce先读取原row，非PREPARED即返回view。上述fixture副作用使测试不能代表自己声称的PREPARED→claim返回0→独立current read路径。源码可推导该绕行，但它不是原raw报告中独立观察的实际次数。
- 只在本方法改为 `doAnswer(...).when(mapper).claim(...)`，安装阶段不执行旧Answer。新Answer仅在被测claim真正发生时要求原PREPARED、变IN_FLIGHT并返回0。
- 调用前明确要求PREPARED、transactions零交互、claim尚未调用；没有reset/clearInvocations掩盖任何被测路径。
- 原getTransaction精确times3及每个REQUIRES_NEW、commit精确3/rollback0、selectOwned精确2、原intent/member/hash/IN_FLIGHT/null result、provider/gateway零调用均保留；追加read→commit→claim→commit→current read→commit的严格交错顺序，不允许额外操作。
- 原public read之后再次核事务get/commit仍精确3、rollback0且无未验证事务交互，provider/gateway/consent/media零交互。

## 范围 / caller / 保留

唯一Java文件 `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ai/PhotoCheckIntentServiceTest.java`，唯一修改方法保持原名。所有原16+7=23方法及其余方法正文不变；没有新增方法/类型/fixture输入或POM/profile/XML计数变化。原fixture setup仍用于全部旧场景。
被测caller仍原service.executeOnce，之后原service.read；没有绕过生产交易、改传播或将测试stub替代生产连接生命周期。实际生产短读的get/commit边界原封不动，production SHA256 `a4104da7a55d7f25aed169e6340535802537b241b47ffecc3c353685fafbba82`。C9整个配置原像不动，SHA256 `c182d5dde6b9f5e29083f99557da9c90569a183e7cd65e3f02b50b57a22ec0b0`。B诊断Python、预算/门禁/cleanup及其它owner文件不改。
原四Photo真库19/19通过是root的原93a运行证据，生产未改，不要求为此unit修补重复它们或其它已过项目。后续仅由root安排必要同源unit/准备入口验证。

新增前置状态断言也是本方法的回归围栏：若恢复旧when写法，应在被测入口前被PREPARED断言拒绝，而不是靠放宽times/atLeast/不执行claim拿绿。此负控仅SOURCE，没有运行。

## 交付与检查

owned-source.patch.b64为全部Java增量，PATCHES.sha256提供解码hash，SOURCE-POSTIMAGES.sha256/POSTIMAGES.sha256为作者后像。公共commit还包含此静态说明；fetch/cherry-pick或解码源码patch二选一，勿重复。
仅执行Git差分/应用检查、文件摘要、base64回读及有限新增行秘密模式。所有parse/import、测试、编译、Java/Maven/DB/Docker/HTTP/CI均NOT_RUN；不宣称本次修补已运行通过。
