# 给 root/E：正式引用收集的当前输入

父 `db2ec9417b3dfa4f4c632df1eff787a1e16855f1`，本依赖片只交 collector 和 5 条直接 unit 测试源码；不是整个 TG1/TG2 完成或独立行为验收。测试/编译/CI 均 NOT_RUN。余 producer 正在同一分支完成，root 不整树覆盖 E/H/F。

确切类型与 API：

```java
TopicStateKeyRefService.CurrentContent currentForPublication(CmsTopic topic);
TopicStateKeyRefService.RefCollection collectCurrent(CurrentContent content);
TopicStateKeyRefService.RefCollection collectForPublication(CmsTopic topic);
```

`CurrentContent` 是原 collector 内的短生命输入容器，构造器私有，只有 `topic()/nodes()/activeNodes()/chapters()/templates()`，不存跨请求字段/ThreadLocal、不是 public HTTP payload。`nodes()` 保留全部节点，`activeNodes()` 只取原 status=1；templates 为全部当前正 templateId 对应实体 map。容器集合不可增删；实体只供本次原校验器读取，不宣称通用不可变缓存。

正式入口显式拒绝无事务/只读/null/坏 scope/缺来源，沿现有 `CmsTopicMapper.selectCmsTopicByIdForUpdate` → 原章节 FOR UPDATE → 原节点 FOR UPDATE → 排序去重模板批 FOR UPDATE。新注入只有原 CmsTopicMapper，无新 managed type/Mapper/SQL。数组/node 顺序按原 sortId 查询口径，模板锁按 ID 升序。全量当前查询均限原 topic，原 parent cache flags 由前片 edf 提供。

`collectCurrent` 复用原 `collectNode`、`collectJourneyRules`、`collectJourneyStory`、`collectChapters` 解析逻辑；无二次 SQL。旧 `collect(topic)` 普通 query/逐模板读/preview、原 parseError kind/条件引用行为不改。缺实际来源的正式当前输入拒绝，不退回普通视图。

E 正式 tag 派生/注册引用校验用 `collectForPublication`；正式主题配置的节点、模板、章节验证用同一 `CurrentContent`，不要重新普通 SELECT。父物理锁与 G/F 相同；正式方法留在原 caller 事务，不创建独立事务。

新测试源码 `TopicStateKeyRefCurrentContentTest` 5 声明：current vs ordinary 输入；捕获后 parser 零 SQL；preview 保原路；block/chapter parseError 和 disabled-node；无 TX/readOnly/缺 parent/跨 topic/缺模板拒绝。它们使用 mapper doubles 与 unit flags，不证明 MySQL RR/锁。G 原真实存储 fixture 将注入真实 parent mapper；其他手工 new collector 的正式 fixture 需显式装配 parent port，不能填 guard=true。

新 full snapshot 合同由 G producer 后续完整头交付：root `stateKeySnapshotVersion:1 + stateKeys`（完整作者定义，不含 id/time/运行值）；按 ROOT_FINAL_CONTRACT_V3 不新增 contentSnapshotOnly，只保全原冻结规则/剧情/节点事实给 H 复用原 helper；每节点 `contentSnapshotVersion:1/templateId/templatePresentation/nodePresentation/chapterAudioUrl`。旧无标志保持原语义，H 按冻结字段读取，不读作者未来 live 重判。
