# WP10 主题发布闭环源码候选

基线 `6d668e381e8f0b6f33db93470c93056161be4b70` / tree `8f4f3463ac50a06415acc75afbc26e2dfd7d8c3f`。只改D独占主题门面、新发布同域owner及必要测试；没有整旧分支覆盖master。

实际代码：CmsTopicServiceImpl 5238→4129行；47个定义迁入 TopicPublicationService（状态/草稿发布/组合审核）、TopicPublicationContentGuard（内容引用/供给恢复/快照校验）、TopicSelfHostActivityService（壳及既有票务服务协调）。新owner分别14/12/5个注入；发布快照SQL统一由TopicPublicationService.persistRouteSnapshot执行；没有Context转手、Controller lambda、反调ICmsTopicService或新增调度/外部服务。

所有旧公共API、外层事务注解、owner/锁/CAS及调用次序保留。创建/编辑的原插入点委托组合送审；草稿发布、留置复核、审核通过快照/活动壳入口均接实际owner。审核/商业资格/额度/欠款/票务仍调用已有服务；未迁移资金算法。47个方法体还原owner及唯一SQL写端口委托后与6d相同，127个未迁方法体全字节相同，创建原体仅两处发布快照调用改接唯一写端口；这是静态证据，不是Java等价验收。

D8的GraphImpl两处requireWithinLimits和TopicSizeLimits原helper均继承6d原blob；发布按库计数的第三道门禁移入真实发布guard，原调用位置不变。PointsValuePolicy、#585隐私/资质、#586 HTTP ACK及其他主线文件均继承6d，未修改。

测试仅owner/反射/源码位置装配：原366名字保留，D8四例保留，32/62及25观察夹具业务断言/SQL/归一化不改。旧“反射mock全部注入”工厂补接移走的六个原协作者，使用原mock/boundary工厂，避免null跳过校验或mock整个新owner。命名启动环保留原正/负控并装配三个真实owner。TopicLifecycle源码合同保留两种断线负控，改查真实委托链。

本树包含已冻结新侧修补 `0ca11cf6cf5b44ad980a467d4321be9bcebaef40` 的五文件测试增量；独立旧侧修补为 `aec007c2717d738743940ddbb959d72eff1d0c14`。publication后续只增加实际owner装配。共享观察夹具仍无新owner编译期引用，旧侧按原legacy模式查旧owner并验证新类不存在；root若对照，应同步复制同一夹具，不能拿旧aec夹具冒充与本树逐字节相同。

已做：公共preimage逐blob核对、方法体/用例名/规模门禁/规范化负控/生命周期断线负控静态检查、git apply --check。没有Java/MySQL/HTTP/CI执行或dispatch，没有DDL/依赖/registry/harness变更。统一回归由root调度。与C的ChapterFlowWriteServiceTest可能有小范围装配重叠，按方法hunk整合，不覆盖C新目标或原断言。
