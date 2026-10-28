# F dbd12 runtime compile / authchain SOURCE repair

父：dbd12e0a0d88e54597c274b56b08f2784c415d0e。仅直接修改 get-auth-matrix-contract.test.js；共享 7 个 Java 文件只以完整 review.patch 交 root，不在供体实际生产路径应用。未改 workflow、门禁、POM、SQL、预算或生成台账。

## 原失败与修补合同

- 原 CI 40758024272 / Backend123062585908 与 SBOM123062585839：三接口和三实现重复同签名方法；GameSessionRuntimeServiceImpl 的合并尾部旧 persistedRunControl 还调用已抽走的 runOwner；Advanced 的 Mapper Java 声明缺失（对应真实 XML 已在）。这是合并装配缺陷，不是将业务失败归给 fixture。
- IAdvancedGameRuntimeService / IGameSessionRuntimeService / ITopicRouteRuntimeService：只删尾部重复声明，保留原有带合同的声明/default。生产实现仍真实 override，不改查询返回值或放宽接口 scope。
- AdvancedGameRuntimeServiceImpl：删除前部旧重复实现，保尾部包含 templateId null/NullNode 处理修正的完整只读实现。原个人/active-team/default-instance 选择、旧局不借未来出版补原内容、完整归属检查与防御性副本不变。
- GameSessionRuntimeServiceImpl：保前部实际 sharedRunControl().persistedRunControl 委托的 state/snapshot/project 实现，带回尾部只读事务注解；删尾部重复实现及已经抽取的私有 persistedRunControl。原 originalSharedRouteEligible 改为同一既有 owner 的 persistedRunControl 委托，不复活私有 God body，不 initialize/refresh RUN。原 member/team/activity/epoch/active-quest/原内容映射所有判断保留。
- TopicRouteRuntimeServiceImpl：删除尾部重复 queryNodeContentSnapshot/publishedContentSnapshot，保前部同语义原局优先、无局才读发布+原小游戏来源的完整纯读实现。旧原局缺证据仍不补未来发布，原其他方法不改。
- GameRuntimeSessionMapper：仅补 selectAccessibleByIdForUpdate(@Param("id") Long id,@Param("memberId") Long memberId) 声明。现有 XML 同 id 在104行，useCache=false/flushCache=true，原 player 或 active-team scope，membership current lock + session FOR UPDATE 全保；不改成弱 selectById，不改任何 SQL。
- CALL-SITES.txt 列固定父源这些端口的生产静态引用位置；原 AdvancedCommandFingerprintTest、AdvancedPhotoOrchestrationTest、GameSharedRouteQueryTest、PublishedNodeContentConsumptionTest、AdvancedGameRuntimeServiceImplTest、TopicRouteRuntimeServiceImplTest 既有场景均不改/不删。去重和绑定不要求 caller 新签名。

## authmatrix 的真实可达链

123062586803 的单失败：新 public 五参 resolvePreferencePlayCtx 仅委托 private 六参 resolver。原静态测试停在 public wrapper，所以错误地要求该 wrapper 本文直接含 resolveCtx。生产 GET 确实先 wrapper(null originalRun) 再同一 guarded resolver；原局提交沿同 resolver 传实际 originalRun。

测试现精确验证 wrapper 原 member/node/activity/topic/action 参数和 null 转交，再继续 private resolver 原 resolveCtx → error → leadGate → unlockGate → routeAccess → unlockErr 全次序；追加 frozenAnswer 原 originalRun 参数守卫。新增三条受控 mutation：wrapper断链、伪造member、丢原originalRun，都必须由原断言拒绝。原41个test、G current-account/community fixture、其它所有原断言保持；没有修改生产授权策略。

## 补片/后像/检查

runtime-shared.review.patch.b64 解码为 20735 bytes，SHA256 2e33ddd4502d3d9acaa42526c4fcf317b53cc00c775ee67dac260e89f5a47806。SHARED-IMAGES.tsv 给7文件精确前后像，可用固定父+补片重建完整生产后像。该补片已在独立临时 Git index 对 dbd12 apply-check；实际工作树生产文件未修改。

仅 Git读取/diff/apply-check/摘要检查。全部项目 parse/import、Node/Python tests、Java/Maven/DB/Redis/Docker/HTTP/CI：NOT_RUN。不能将补片签为编译已通过；由 root 合成并做必要同源验。原1GiB/四路16MiB/held先读后释放/身份金额权限UNKNOWN与所有清理合同不变。

34个新技术边界：本片不修改 owners/commands 或任何可信 pin。artifact连接器下载地址实际HTTP403，正向root索取精确path/type；后续独立review包仅登记原JSON的精确身份，禁止候选自授权或改legacy/business基线。
