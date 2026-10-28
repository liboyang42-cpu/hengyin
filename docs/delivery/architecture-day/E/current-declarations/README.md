# E：发布声明的显式消费与只读预检查

源码候选，**所有测试/编译/Java/DB/HTTP/CI NOT_RUN**。父 `91a3bdd2aea254e39fe245337bfdd3f89122cf10`；原 ContentGuard 后像 SHA256 为 `bc32e871c0db0aa8a9d7dea9a18181ba05ffae6f3be0fb69c129936054f0fdf5`，本片从该精确后像继续，保留其全部路径/消息/异常类型/cause/见证拒绝。没有采用 G 父 fa5c 的旧 guard 覆盖诊断。

## 给 root/G 的正式合同（已提前发送）

```java
void deriveOptionTagsForPublication(CmsTopic topic);
void assertStateKeysRegistered(CmsTopic topic, List<ThemeStateKey> declarations);
void assertThemeRuntimeReferences(CmsTopic topic, List<ThemeStateKey> declarations);
String normalizedBranchRouteForPublish(CmsTopic topic, List<ThemeStateKey> declarations);
```

四者 `MANDATORY` 且显式检查原 actual write TX、正 topic ID；正式声明为 server-internal 投影，null/跨 topic/空键/重复键拒绝。没有 REST 入参、新 context/cache/ThreadLocal。作者送审/提交阶段，G 须持原 topic/内容锁并先完成 clue/tag 派生，**派生全部结束后**调用 F 当前读捕获并稳定复制最终声明，然后将同一作者投影用于 review hash、正式校验与 freeze。批准/安全恢复阶段只消费审核锁下当前声明，核对审核 hash，不再调用派生方法补新声明。不能 hash 后补声明，不能将时间字段或运行 state_json/character 值加入作者 hash。

E 派生方法使用 F.listCurrentDeclarations 判重，沿原 TAG/run/label=key 与 register 机制只补缺项，不用旧普通 RR 读覆盖已有定义；最终捕获归 G，不返回一个假装已包含注册结果的旧 list。两个正式重载从不再读声明服务/Mapper、不再派生；同一声明 list 的 membership 本地复制，bean 不改，G 负责其稳定作者副本。引用收集仍复用原 TopicStateKeyRefService / StateKeyRefs / JourneyStory / ThemeRuntimeConfig 等规则，绝不复制校验器。

正式 normalizedBranchRouteForPublish 重载始终调用 G 的 `freezePublication(topic, normalizedOrNull, capturedDeclarations)`；普通 LINEAR（有普通选项模板或无模板）不再因 requiresFullRuntimeSnapshot=false 返回 null，空冻结结果明确拒绝。它不再调用旧 compileTemplateRules 派生，不把 requiresFullRuntimeSnapshot 改 true；原功能开关与原单参数预检查分支保留。分支图仍共享原 validator/gate/91a3 定位，不造第二 compiler。

## 普通路径与实际 caller

- 正式发布两个 `TopicPublicationService` 调用点由 G 迁移上述合同，本片不编辑 G 文件。
- `CmsTopicServiceImpl.createTopicWithChapters` 在 `persistTemplateInteractionRules` 前调用单参数 `assertThemeRuntimeReferences`，仍是普通检查，不能因其已有写事务而隐式变发布/加锁/补库。两个单参数检查继续普通 listByTopic；合法自动 tag 和原生成 clue 只加入临时可见集合。未知 counter/relation/普通 clue、exhaustTag 原显式注册规则、关系类型及作用域仍拒绝，未改变类型/权限策略。
- `TopicPublishCheckService.check` 为另一真实只读预检查，原先已豁免自动 tag（不豁免 exhaustTag），但未识别 G 可派生 clue。此类不在 E 写权，本包提供 **preview-check-caller.review.patch.b64** 给 root 串行接入：复用原已读取/校验的模板，不加 SQL，将原纯 clue key 投影用于 existing registered map；不写/锁/调用 current declarations。

## 必须合成的真实依赖，非 standalone 编译候选

1. F `e3068c120af738b954b72afd22ceec953477b6d2` 精确 API：ThemeStateKeyService.listCurrentDeclarations；对应 Service Git blob `ac38f1c7de6d93342a374f74a785cf5d86d4fd68`、Mapper Java `fa4350d88ba13c819f30e2499f9d630a8d207d1d`、Mapper XML `bcc6fa44fa6bb65a6daee696babb3b334b5302ac`。本片未重复提交 F 源。
2. G 正式 producer/capture/hash/compiler 接线，以及真实新 `ITopicRouteRuntimeService.freezePublication(CmsTopic,String,List<ThemeStateKey>)` 端口（root 本轮精确指定，G 实现）。参考已读 `db2ec9417b3dfa4f4c632df1eff787a1e16855f1`，具体最终改动归 G/root，不能用本 guard 当全链路已闭合。
3. **shared-clue-projection.review.patch.b64**：精确 source proposal，非谎称父提交已有 API。G 原 package-private Compiler.collectTemplateClues 闭包（包括原正则、显式 labels 与隐式 var 的顺序）机械复用到既有公共 `StateKeyRefs.declaredTemplateClues(JsonNode):Map<String,String>`，Compiler 委托该唯一实现，E 只使用 keySet；无新类型/框架/白名单。E 未在实际工作树编辑 StateKeyRefs/Compiler，root/G 必须串行应用这个 review patch 或交同签名等价实现。两个 review patch 的 donor 是 G db2ec；不得整旧 Compiler 覆盖 G 正在写的 producer。

以上两份 review patch 未实际应用到外国 owner 源文件。候选 E 源显式依赖其公共 pure method；未合依赖不能声称可独立编译或启用。它不是通过反射/普通 catch fallback 绕过接口。

## 源码测试与 fixture

- 新 `TopicPublicationDeclarationsTest` 26 个 unit 场景源码：真实 RefCollector/原 validators，声明服务 double、thread flags 仅 unit context；覆盖 stale ordinary/current 区分、原定义不覆盖、原 tag 派生、两正式校验零重读/零派生、缺最终声明拒绝、preview 无锁无写和合法待派生内容、未知 counter/非生成 clue 拒绝、跨 topic/null/重复投影拒绝、metadata 不改、DB异常原样、原定位、真实 TopicPublishCheck caller。最后一例需对应 review patch 生效；不是 mock 一个成功结果代替 caller。
- 原 `CmsTopicPublishStateKeyGuardTest` 25 个 case、全部原断言保留。只补 unit TX flags 并 finally 恢复、当前 topic/子内容/key 读端口、明确原 topic/template 身份。原普通返回序列仍保留；另维护 current 声明集，**upsert 才改变 current 行集**，不靠读取次数伪装派生已落库。该 fixture 不证明实际事务、RR 或回滚。
- 新增普通 LINEAR 有模板/无模板的完整冻结调用、原开关不变、原单参分支不变、空冻结拒绝、正式分支图定位 SOURCE；其中冻结端口是 opaque unit double，仅证明 Guard 不再提前返回 null，不冒充 G 的实际题目/答案快照或完整 compiler 验证。
- 91a3 原 10 个 production diagnostic unit / gate 场景源码留在父提交，未删未弱化；G 新 producer 若引入额外协作者，其原入口装配必须随 G 精确源码一起迁移，E 不猜尚未发布的字段/API。

## 尚未闭合的联合边界

`TopicStateKeyRefService.collect(topic)` 当前自身仍会普通读取节点/模板/章节。若事务先建立旧 RR 视图，这些 reference 输入不自动因 G 锁过 current child 就变新；本片只消除了声明表的普通重读。G 当前内容投影须继续喂原引用收集器，root 已收到精确接缝，不能为此在 E 复制算法/建 ThreadLocal。E 不把本片称为全内容 RR、审核 hash/快照一致、MySQL 并发或完整游戏验收完成。

没有新 schema/依赖/权限/费用/生产动作。旧单参数方法不可被当正式发布 fallback；未迁移全部正式调用者前勿将其视作发布合同。只读临时集合不落库，不改变原运行值/UNKNOWN/provider。回退须和 G 同步恢复调用签名与发布合同，不能让一半 producer 继续使用旧 RR 声明；原已落自动键沿原事实保留。

只进行了源码读取/编辑/摘要、git diff 与有限秘密模式扫描；未运行测试，也不把源 hash 当行为证明。完整源补丁为 owned.patch.b64，全部 hash/依赖见 source-evidence.json。review patch 均标准 base64，解码后 git apply；root 唯一应用/集成和最后统一验收。
