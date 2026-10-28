# H 直接消费者修补 SOURCE 包

基线：`2fbfd6df4c4480d924eddd0984169a8d86877075`，tree `76ba8268302a5b7ab61f10cd676833666d5b7c1e`。仅处理已分派六个失败身份，不扩展审查。

供体仅直接修改四个 owned 测试及本目录。共享生产 `ThemeCharacterService.java` **不直接修改**，由 root 按 `production.review.patch` 串行集成；只取供体测试而漏该 hunk，原“不支持版本必须拒绝”的断言仍不会闭合。`complete.review.patch` 是基线上四测试加一生产建议的完整增量，不含别轮已集成源码。

| 原失败 | 根因与本包处理 |
| --- | --- |
| ThemeCharacter 新局应结转9、run键应重置5 | 原用例只有 stateKeys，没有 DEC02 完整 attributeDefinition；来源局也没有完整定义/configVersion。复用本类现成完整定义/carrySession，保真实 freezeKeys 的 stateKeys 与原规则。仅为原成功台账更新显式返回1；保原来源局41/version6、台账JSON和不读live断言。原partial/run→topic等负控不动。 |
| 不支持stateKeySnapshotVersion应拒绝 | seedRun在调用原声明版本读取器前因缺DEC02完整定义返回；生产建议在该返回前复用 JourneyInitialState.frozenDefinitions，既不补live也不放开partial结转。原抛错/零mapper断言保留，并补完整DEC02不能掩盖坏声明版本、变量零改变。 |
| photoAudit死锁两例 | 原测试调用已关闭的缺运行身份重载，根本未到事务/审核。改调真实完整controller重载，固定原局91/version4、actor7/activity500/topic300；装真实PlayBasicIntentApplication及有限mapper/报名叶子。原Spring事务拦截器、外层deadlock重试不动；实际logAttempt仍真执行并按原首次/持续死锁抛错。保回滚/关闭先于重试、原命令两次完全一致、下游次数1/0、最终异常类型全部断言；只把记录的原命令补齐现API的三个身份参数。 |
| 新偏好提交下架拒绝 | 缺真实intent owner，且旧请求没有原动作/局身份。装真实owner、同一报名与当前原局，快照仍来自本测试原冻结偏好内容；保500和判定零调用，新增必须走实际下架闸、原局锁与同原请求FAILED回执的断言。无成功judgement替身。 |
| 恢复期缓存QR纯读 | 实际v2缓存是对象key→storage.url现签展示地址，旧夹具v1 URL使它真实走miss并正确触发恢复guard。改为原v2对象键/URL读取叶子，保显示original-url、另会员/scene miss全部Blocked、gateway零调用、storage只读url一次无store、Redis仅三次读无锁/写。 |

## 已核直接调用/合同

- TopicRouteRuntimeServiceImpl:1365 → ThemeCharacterService.seedRun(五参)；旧四参仍以null快照不继承。原ThemeAttributeDefinition.read/matchesVersion、carry ledger record算法保持。
- JourneyInitialState.frozenDefinitions:32 对有标志的非1版本拒绝、无标志null；本hunk仅复用此已有验证，不改DEC02 D3旧展示策略。
- ApiPlayProgressController.photo(完整9参) → PlayPhotoCommandApplication.photo(完整10参) → PlayBasicIntentApplication.executeBasicIntent → 原局/报名/receipt → photoOnce → logAttempt → advanceProgress(含PlayOriginalRun)。缺身份重载仍拒绝。
- PlayPreferenceApplication.submitPreference → 同真实intent → 原局快照/实际PlayNodeContext → 当前下架检查；不修改任何production play或权限。
- PlayerWxacodeServiceImpl.getOrCreate/getOrCreateScene → v2缓存读取 → storage.url；仅cache miss才到RestoreDispatchGuard，再到任何分配/gateway。原生产源不改。

## 原方法与验证边界

PREIMAGE-POSTIMAGE.json列出所有原void方法身份、逐文件前后像SHA256和Git blob。未删除原方法、断言目标、skip/负载/预算；预期内容/事件序列原样保留，只有API身份参数和QR读合同必要适配。未修改共享helper、接口、schema、依赖、权限/费用/UNKNOWN或恢复策略。

Java/Node/Python项目parse/import、测试、编译、DB/Redis/Docker、CI、真实HTTP/供应商：**NOT_RUN**。SOURCE夹具中的Spring事务和JDBC mocks不能代替MySQL持久性/回滚/HTTP/RBAC/奖励验收。仅Git差分/apply-check与文件摘要可作运输一致性证据，不宣称六例已过。root统一审接与同源验证。
