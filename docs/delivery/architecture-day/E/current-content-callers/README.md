# E 正式发布当前内容 caller 收口

源码完成；编译、所有测试、MySQL、CI 均 NOT_RUN。父提交为 56bc9324e1ebb7244130c6f9525a43262a046d88，仅修改三个 owned 源文件；没有修改 collector、编译器、持久入口、Mapper 或 SQL。

## 依赖与接线

已读取公开 G 1718c569ffb9e09847a6199c85b27b2dafd56689 的实际 TopicStateKeyRefService。CurrentContent.templates() 为 Map<Long,CmsMemberTemplate>，nodes/chapters 为 List，activeNodes 保持 status=1；构造器私有。该依赖由 root 联合集成，本候选单父 56bc，不复制 G 文件，不能声称独立可编译。

- 正式 deriveOptionTagsForPublication 和 assertStateKeysRegistered(topic,declarations) 改用 collectForPublication。沿原 tag 类型、scope、注册方式和捕获声明校验，无普通 RR 引用回读。
- 正式 assertThemeRuntimeReferences(topic,declarations) 只捕获一次 currentForPublication，用其原 topic/节点/章节/模板；嵌套 scene 剧情、章节预算和 NPC 的节点枚举复用同一对象。剧情仍过滤启用节点，主题运行引用原本包含全部节点的语义保留。
- 旧单参 preview 仍普通 collect/SELECT，含在写事务内调用的 preview，不偷偷锁读或持久派生。
- 56bc 的三参 freeze、普通线性/无模板完整冻结、功能开关及审批不得派生的既有合同不变；保留 91a3 的诊断位置、消息、拒绝和异常包装。

四类作者内容来自 G 的当前投影；NPC 自身的原查询不在该投影合同中，没有改写。这里不宣称所有 SQL 当前读、整个发布 RR 已证明或跨方法只有一次查询。没有 ThreadLocal、缓存或第二套 context 框架。

## 配套测试源码

TopicPublicationDeclarationsTest 原 26 场景/断言保留，当前读 fixture 注入实际 G collector 所需 topic mapper；新增 6 例，合计 32 个 @Test 源场景：

1. 正式 tag 派生只消费当前 collector，普通 collector 作为负控。
2. 正式键校验消费当前引用及既定声明，不回读补声明。
3. preview 即便处于写事务也保持普通只读 collector。
4. 正式运行引用使用锁下 topic/模板，旧 caller JSON 与普通子内容读取作为反例。
5. scene 剧情/章节预算/NPC 节点枚举复用一次当前投影。
6. inactive disposition 节点仍拒绝并保留原 scene 字段路径。

CmsTopicPublishStateKeyGuardTest 原 25 场景/业务断言不改，仅补 collector 的 topic mapper 装配。unit 使用受控 mapper 和事务线程标志，不是真 MySQL/Spring 传播证明，新增六例亦未执行。

完整 owned source patch 的 base64 在 owned.patch.b64；解码 SHA256 与三文件后像 SHA256 见 source-evidence.json。仅进行 git diff 格式检查和交付身份记录；没有运行测试。root 最终需与 G/F/正式 producer 同源编译并集中验收。
