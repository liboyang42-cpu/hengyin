# F：2fbf 两个原失败测试合同修补

父：2fbfd6df4c4480d924eddd0984169a8d86877075。仅修改分配的两个原测试方法正文；无新@Test、无测试删除/skip、无生产/接口/SQL/POM/workflow/预算变更。原Doc2包及其它owner改动都继承自该公共父，不重做已过项。

## 原因与保留的合同

1. ThemeStateKeyServiceTest.staleRegistryBindingRejectsBeforeReadingDefinitions：实际 freezeDefinition → lockRegistry → lockTopicForStateKeyWrite → ensureRegistryVersion → lockRegistryVersion，随后版本不一致立即拒绝。原 mock 已正确配置新锁，旧 InOrder 却验证 lockTopic。现仅将该验证绑定真实锁，保 conflict 原码与两个原never断言，补普通/单键定义读取和所有定义写入零调用、完整三锁顺序及无额外交互。原topic7与当前锁SQL不变。

2. PersonalGameplayRuntimeServiceTest.gameplayBridgeKeepsActionCasResponseOrderAndReplayHasNoScoring：setup 的两次 arrival 已推进局版本，首次请求用当时 session.getVersion()，原重放却用常量0。真实 gameplay:v2 指纹明确包含原 expectedVersion，这两个请求不相同；同类已有 gameplayReplayRequiresOriginalExpectedEvenAfterEntityVersionChanges 正是此合同。原XML在重放行抛“动作标识已用于其他操作”，与源路径相符；无需修改生产或加入兼容默认。

现在在首次请求前保存 originalVersion，所有真重放继续原member7/session99/bridge-start/QUEST_START/north/原版本，不换actionID。保原lock → insertAction → updateStateCas → complete → updateActionResponse顺序及两次原verifyNoInteractions。原east内容负控改带正确原版本，避免仅因版本不符而假证内容拒绝；增加同ID改operation/改expectedVersion负控。检查原gameplay ledger的session/member/node0/action/outcome/receipt前缀、保存回包相等、只一次insert/CAS/response更新、无新receipt、原状态/当前版本/冻结snapshot不变。没有clear/reset隐藏被测写；沿原setup后clear(mapper)和首次动作后clear(bridge)划分原观察阶段。

## 真实caller / 执行边界

- ApiThemeRuntimeController.gameplayAction 原样转交 member、sessionId、expectedVersion、actionId、operation、content/choice、preview/cost给 ITopicRouteRuntimeService。
- TopicRouteRuntimeServiceImpl 的既有overloads → gameplayActionInternal：原可达session/generic冻结身份校验，gameplay:v2完整输入指纹，原receipt current lookup，匹配才回放；新执行才scope/评分锁/current row/version/CAS，最终保存响应。全链生产逐字不改。
- ThemeStateKeyService.freezeDefinition 的原MANDATORY/原topic parent锁/registry版本与 current声明读取路径，Mapper/XML缓存标志、锁与并发场景全部保留。

source.patch.b64 是两个测试文件完整增量：5398 bytes，SHA256 0920ecc363a6a53efe1ff27264a87b4e7253eed05011978f35bf71dc859d791d。IMAGES.tsv 含完整前后像；UNCHANGED.sha256记录有关生产caller/接口/Mapper/SQL后像。实际Git apply-check对固定公共父通过。

本片仅源代码读取、Git diff/apply-check、摘要及有限敏感模式检查。所有项目parse/import、测试、编译/Java/Maven、DB/Redis/Docker/HTTP/CI：NOT_RUN。原同源XML仅是root已给失败证据，不将修补推断为新PASS；root唯一集成与必要同源验证。
