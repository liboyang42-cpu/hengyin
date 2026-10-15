# EXTRA6 50项滚动源码任务池

CY-HANDOFF: CODEX；CY-LONGRUN-SOURCE-20261015-v1。

最新已读控制：`daca836d64a3186861faf412a68e6f930fc01979`。真实Cloud任务`01a13f9c-e8ba-753e-9c39-03840bb4900b` / durable；环境running/connected。
分支：`codex/extra-six-rolling-20261015`；汇总草稿[PR36](https://github.com/liboyang42-cpu/hengyin/pull/36)。

可行动/在途50，累计84具体项；源码交付20、反证11、阻塞3。新补位是已有真实方法/调用合同的审计或回归缺口，尚未确认有bug；先查已有覆盖，已有反证直接NO_CHANGE，不凭数量改源码。

全部新验证`NOT_RUN`，交付状态`SOURCE_DELIVERED_UNVERIFIED`。禁止Java/Maven/Node/lint/数据库/DevTools/业务验证；不手动触发/重跑/等待CI。自动CI不改。不合并、部署、迁移、生产或VPS操作。

## 当前领取

|lane|任务|分支|写者|
|---|---|---|---|
|E01|E01-14|codex/extra-e01-regression-20261015|gpt-6.1-sol / external_e01|
|E02|E02-05|codex/extra-e02-regression-20261015|gpt-6.1-sol / external_e02|
|E03|E03-05|codex/extra-e03-regression-20261015|gpt-6.1-sol / external_e03|
|E04|E04-05|codex/extra-e04-regression-20261015|gpt-6.1-sol / external_e04|
|E05|E05-02|codex/extra-e05-regression-20261015|gpt-6.1-sol / external_e05|
|E06|E06-05|codex/extra-e06-regression-20261015|gpt-6.1-sol / external_e06|

6线已领取；冻结待父提交的worker可能idle，不冒称6个模型持续生成。E03-11仅合同草案待C00，E03已转E03-05；E04-03扩权已解决并源交付。

## 精确队列

每项精确文件、触发、合同、依赖和公共完整SHA见[QUEUE.json](QUEUE.json)，全局转交文件与当前唯一child见[CLAIMS.json](CLAIMS.json)。

|ID|任务|状态|
|---|---|---|
|E01-01|排期日期边界归一|NO_CHANGE_SOURCE_REVIEW|
|E01-02|系列更新保留集合时刻|NO_CHANGE_SOURCE_REVIEW|
|E01-03|系列重发身份冲突|NO_CHANGE_SOURCE_REVIEW|
|E01-04|系列到场次作用域读取|READY_SOURCE_ONLY|
|E01-05|有候补场次结构保护|READY_SOURCE_ONLY|
|E01-06|候补取消与晋升竞争|READY_SOURCE_ONLY|
|E01-07|到期offer状态读回|SOURCE_DELIVERED_UNVERIFIED|
|E01-08|候补令牌归属校验|READY_SOURCE_ONLY|
|E01-09|候补订单绑定终态|READY_SOURCE_ONLY|
|E02-01|帖子详情实时可见性|SOURCE_DELIVERED_UNVERIFIED|
|E02-02|信息流游标与权限过滤|NO_CHANGE_SOURCE_REVIEW|
|E02-03|引用聚合不复活撤回内容|BLOCKED_DECISION|
|E02-04|删除命令重复与版本冲突|NO_CHANGE_SOURCE_REVIEW|
|E02-05|撤回位置后多个读面一致|IN_PROGRESS_SOURCE_ONLY|
|E02-06|评论列表父帖隔离|READY_SOURCE_ONLY|
|E02-07|跨帖评论删除关联|READY_SOURCE_ONLY|
|E02-08|互动重放不泄露不可见详情|READY_SOURCE_ONLY|
|E02-09|社区媒体回执归属|READY_SOURCE_ONLY|
|E03-01|已冻结结局优先返回|SOURCE_DELIVERED_UNVERIFIED|
|E03-02|未知旧快照不冒充空结局|SOURCE_DELIVERED_UNVERIFIED|
|E03-03|结局首命中及显式优先级保真|NO_CHANGE_SOURCE_REVIEW|
|E03-04|章节结束正文沿本局快照|BLOCKED_DECISION|
|E03-05|旅程读模型账号和场次关联|IN_PROGRESS_SOURCE_ONLY|
|E03-06|结局章引用重映射完整性|READY_SOURCE_ONLY|
|E03-07|结局校验未知枚举拒绝|READY_SOURCE_ONLY|
|E03-08|节点快照显式空与缺席区分|READY_SOURCE_ONLY|
|E04-01|发券库存更新结果处理|NO_CHANGE_SOURCE_REVIEW|
|E04-02|每人限领历史范围|NO_CHANGE_SOURCE_REVIEW|
|E04-03|停发与失效区分|SOURCE_DELIVERED_UNVERIFIED|
|E04-04|奖励来源回执身份冲突|NO_CHANGE_SOURCE_REVIEW|
|E04-05|核销旧状态与重复提交|IN_PROGRESS_SOURCE_ONLY|
|E04-06|券核销撤销身份关联|READY_SOURCE_ONLY|
|E04-07|通关券冲正限定原奖励|READY_SOURCE_ONLY|
|E04-08|退款券奖励冲正回执重复|READY_SOURCE_ONLY|
|E05-01|订阅模板去重与三模板上限|SOURCE_DELIVERED_UNVERIFIED|
|E05-02|订阅部分授权状态精确映射|IN_PROGRESS_SOURCE_ONLY|
|E05-03|订单历史退款确认与回包账号代次|SOURCE_DELIVERED_UNVERIFIED|
|E05-04|参与详情退款确认与回包账号代次|SOURCE_DELIVERED_UNVERIFIED|
|E05-05|收藏列表账号切换晚响应|NO_CHANGE_SOURCE_REVIEW|
|E05-06|收藏帖子与主题分页作用域|READY_SOURCE_ONLY|
|E05-07|取消收藏身份锁与刷新竞争|READY_SOURCE_ONLY|
|E05-08|收藏卸载与重新显示完成回调|READY_SOURCE_ONLY|
|E06-01|音频重复注册保留中断身份|SOURCE_DELIVERED_UNVERIFIED|
|E06-02|音频手动暂停覆盖自动恢复|READY_SOURCE_ONLY|
|E06-03|常亮异步失败可恢复|SOURCE_DELIVERED_UNVERIFIED|
|E06-04|poller停止重启在途世代|NO_CHANGE_SOURCE_REVIEW|
|E06-05|poller异常和退避配置边界|IN_PROGRESS_SOURCE_ONLY|
|E06-06|最近定位异常值与存储失败|READY_SOURCE_ONLY|
|E06-07|选址取消拒权回调隔离|READY_SOURCE_ONLY|
|E06-08|定位owner释放与启动竞态|SOURCE_DELIVERED_UNVERIFIED|
|E01-10|排期聚合读的父系列与场次快照|SOURCE_DELIVERED_UNVERIFIED|
|E01-11|候补维护畸形页的隔离边界|READY_SOURCE_ONLY|
|E02-10|作者编辑历史父子读取同一快照|SOURCE_DELIVERED_UNVERIFIED|
|E02-11|信息流父行与hydrate子集合快照|SOURCE_DELIVERED_UNVERIFIED|
|E02-12|POI选项与保存状态条件一致性|READY_SOURCE_ONLY|
|E03-09|首批结局真库夹具的非兜底条件|SOURCE_DELIVERED_UNVERIFIED|
|E04-09|后台原始券历史新增事务原子性|READY_SOURCE_ONLY|
|E05-09|订单历史列表同账号卸载晚回包|SOURCE_DELIVERED_UNVERIFIED|
|E05-10|参与详情读取账号与组件实例归属|SOURCE_DELIVERED_UNVERIFIED|
|E06-09|GPS启动API同步异常收口|SOURCE_DELIVERED_UNVERIFIED|
|E06-10|共享定位消费者异常隔离|SOURCE_DELIVERED_UNVERIFIED|
|E06-11|失败通知批次中同key重获身份|READY_SOURCE_ONLY|
|E01-12|排期24:00归一与HH:mm既定输入|SOURCE_DELIVERED_UNVERIFIED|
|E01-13|候补加入与售卖活动状态合同|READY_SOURCE_ONLY|
|E02-13|迟到帖文审核回调的内容版本绑定|READY_SOURCE_ONLY|
|E02-14|社区草稿退群再发布的既定成员边界|READY_SOURCE_ONLY|
|E04-10|核销期间模板失效当前事实|READY_SOURCE_ONLY|
|E03-10|场景剧情结局冻结的跨队最小依赖交接|READY_SOURCE_ONLY|
|E03-11|节点ID重映射producer合同|BLOCKED_CONTRACT|
|E01-14|系列成功请求跨上海日重发|IN_PROGRESS_SOURCE_ONLY|
|E01-15|独立场次取消的作用域和状态读回|READY_SOURCE_ONLY|
|E01-16|场次取消收尾失败原子性|READY_SOURCE_ONLY|
|E01-17|取消通知受众快照不被收尾改写|READY_SOURCE_ONLY|
|E02-15|删除编排后置失败整体回滚|READY_SOURCE_ONLY|
|E02-16|通知读取的可见性与未读聚合|READY_SOURCE_ONLY|
|E02-17|媒体擦除法律保留与共享对象引用|READY_SOURCE_ONLY|
|E02-18|社区outbox处理行数和失败回执|READY_SOURCE_ONLY|
|E03-12|NPC剧情映射保留未知字段与归属|READY_SOURCE_ONLY|
|E03-13|案件册线索进度沿冻结剧情|READY_SOURCE_ONLY|
|E03-14|案件册日志重放与初始状态差分|READY_SOURCE_ONLY|
|E04-11|批量过期与核销当前状态保护|READY_SOURCE_ONLY|
|E04-12|占库成功后券历史落库失败回滚|READY_SOURCE_ONLY|
|E05-11|订单历史同实例分页请求归属|READY_SOURCE_ONLY|
|E06-12|定位stop同步回调异常传播|READY_SOURCE_ONLY|

## 首批交付与历史证据

|路|草稿PR|公共完整SHA|方法数|
|---|---|---|---|
|E01|[#29](https://github.com/liboyang42-cpu/hengyin/pull/29)|04e03b955899eebd16cbb48aef64436e881ed20e|5|
|E02|[#30](https://github.com/liboyang42-cpu/hengyin/pull/30)|7fd6855e1cbad0186ae06c4ec8ca8a48c216f626|3|
|E03|[#31](https://github.com/liboyang42-cpu/hengyin/pull/31)|21d70f6069ca70c1f1d3ad74358ea58d43aace96|3|
|E04|[#32](https://github.com/liboyang42-cpu/hengyin/pull/32)|495e5b51570b2dc92ada05c860c7be2f287f3d9a|4|
|E05|[#33](https://github.com/liboyang42-cpu/hengyin/pull/33)|53b4aadcbcd38de8d6b99b5c7a6f093fc7579320|24|
|E06|[#34](https://github.com/liboyang42-cpu/hengyin/pull/34)|8a1f01767ad154756c1a1feb428362f3885b61d1|6|

首批E01–04 Java 15方法均未运行。E05历史24项16过8失败、E06历史6项4过2失败发生在暂停指令前；失败断言保留，不能当当前/后续候选验收。各路README保存未来精确命令与环境前提。


## 交付、补位及恢复

同lane串行写，worker不Git操作。父协调者审阅冻结补丁后只stage精确文件，小提交发布公共完整SHA，随后补位。目标50可行动/在途，缺合理候选如实EXHAUSTED，不能造bug或重复测试。C00维护全局docs/plans，本队仅维护此目录3文件。原29源码权加daca836正式转交的Mapper.java及3旧测试；order-detail仍不转交。

恢复先读最新控制LONGRUN_ROLLING、SOURCE_OWNERS、PR16最新评论及本队队列；核每工作树未提交补丁/HEAD，禁止覆盖。此checkpoint后的新小提交可能尚未回填队列，应先读lane证据及Git历史补账，不能重做。

当前shell GitHub凭据不可用；GitHub连接器仍可读写。使用Git Data create_tree(base parent tree)、create_commit(parent SHA)、update_ref(expected_sha=parent, force=false)发布。先保留本地提交；文本树SHA一致后更新公共ref，保存公共完整SHA。可读API commit元数据重建相同本地commit对象/仅在树相同后移动私有分支；不force push、不访问凭据、不依赖Mac。若连接器也不可用，保留未发布补丁和准确阻塞，不称已公共交付。

E03-11未有producer，CORE不能调用新签名；需C00先冻结withEndingChapters组合/已有节点变量范围。E03-01四参freeze已交7cef70bf320c249b966f34cc3908f77912f06780供CORE消费。直接ClubSession.open的24:00表外依赖已PR16交C00，不冒称已修。

平台时长/额度导致停止时，保存公共SHA、文件持锁、未提交补丁和下一项；不承诺永久运行。用户重新授权验证前，任何历史结果都不能继承到当前SHA。本地未来统一验证，不用本地中转派工。
