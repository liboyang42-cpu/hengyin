# EXTRA6滚动源码任务池

CY-HANDOFF: CODEX；CY-LONGRUN-SOURCE-20261015-v1。

最新已读控制`53d50db24b83ec856abb7d998d913c79699934dc`；真实Cloud任务`01a13f9c-e8ba-753e-9c39-03840bb4900b` / durable，环境running/connected。
分支`codex/extra-six-rolling-20261015`；汇总[PR36](https://github.com/liboyang42-cpu/hengyin/pull/36)。

历史累计87具体项：36项源码或回归交付、29项反证、7项阻塞；实际READY 9+领取6=15项可行动/在途。
目标仍50；当前待补35，尚未补足。不得把历史完成/阻塞当剩余50。继续已排任务并从实际新缺陷/缺口或C00精确转交补位，拒绝空标题/重复测试/无意义源码改动凑数。

所有源码交付`SOURCE_DELIVERED_UNVERIFIED`，新验证全部`NOT_RUN`。Java/Maven/Node/lint/数据库/DevTools/业务验证禁止；不触发/重跑/等待CI，自动CI不改。不合并、部署、迁移、生产或VPS操作。

## 六worker实际分配

|worker槽|当前项|写入模式|工作树|
|---|---|---|---|
|gpt-6.1-sol / external_e01|E01-13|SOURCE_OWNER|/workspace/hengyin-extra-e01|
|gpt-6.1-sol / external_e02|E02-14|SOURCE_OWNER|/workspace/hengyin-extra-e02|
|gpt-6.1-sol / external_e03|E03-12|SOURCE_OWNER|/workspace/hengyin-extra-e03|
|gpt-6.1-sol / external_e04|E04-09|SOURCE_OWNER|/workspace/hengyin-extra-e04|
|gpt-6.1-sol / external_e05|E02-15|REGRESSION_ONLY_SUPPORT|/workspace/hengyin-extra-e02|
|gpt-6.1-sol / external_e06|E01-16|REGRESSION_ONLY_SUPPORT|/workspace/hengyin-extra-e01|

冻结待父提交的worker可能idle，不能据领取冒称6模型持续生成。E06工具域当前候选暂EXHAUSTED，不代表无bug/已验收；E05当前客户端切片已交付。空闲worker改支援已有后端回归任务，仅独占新测试/证据，业务源码仍原E01/E02作者单写，不把支援当源码转交。

## 精确队列

[QUEUE.json](QUEUE.json)记录每项触发/不变量/文件/预期数量/未来命令/完整SHA；[CLAIMS.json](CLAIMS.json)记录源码原写者与支援新文件独占。

|ID|任务|状态|
|---|---|---|
|E01-01|排期日期边界归一|NO_CHANGE_SOURCE_REVIEW|
|E01-02|系列更新保留集合时刻|NO_CHANGE_SOURCE_REVIEW|
|E01-03|系列重发身份冲突|NO_CHANGE_SOURCE_REVIEW|
|E01-04|系列到场次作用域读取|NO_CHANGE_SOURCE_REVIEW|
|E01-05|有候补场次结构保护|NO_CHANGE_SOURCE_REVIEW|
|E01-06|候补取消与晋升竞争|SOURCE_DELIVERED_UNVERIFIED|
|E01-07|到期offer状态读回|SOURCE_DELIVERED_UNVERIFIED|
|E01-08|候补令牌归属校验|NO_CHANGE_SOURCE_REVIEW|
|E01-09|候补订单绑定终态|SOURCE_DELIVERED_UNVERIFIED|
|E02-01|帖子详情实时可见性|SOURCE_DELIVERED_UNVERIFIED|
|E02-02|信息流游标与权限过滤|NO_CHANGE_SOURCE_REVIEW|
|E02-03|引用聚合不复活撤回内容|BLOCKED_DECISION|
|E02-04|删除命令重复与版本冲突|NO_CHANGE_SOURCE_REVIEW|
|E02-05|撤回位置后多个读面一致|NO_CHANGE_SOURCE_REVIEW|
|E02-06|评论列表父帖隔离|SOURCE_DELIVERED_UNVERIFIED|
|E02-07|跨帖评论删除关联|NO_CHANGE_SOURCE_REVIEW|
|E02-08|互动重放不泄露不可见详情|NO_CHANGE_SOURCE_REVIEW|
|E02-09|社区媒体回执归属|NO_CHANGE_SOURCE_REVIEW|
|E03-01|已冻结结局优先返回|SOURCE_DELIVERED_UNVERIFIED|
|E03-02|未知旧快照不冒充空结局|SOURCE_DELIVERED_UNVERIFIED|
|E03-03|结局首命中及显式优先级保真|NO_CHANGE_SOURCE_REVIEW|
|E03-04|章节结束正文沿本局快照|BLOCKED_DECISION|
|E03-05|旅程读模型账号和场次关联|NO_CHANGE_SOURCE_REVIEW|
|E03-06|结局章引用重映射完整性|NO_CHANGE_SOURCE_REVIEW|
|E03-07|结局校验未知枚举拒绝|NO_CHANGE_SOURCE_REVIEW|
|E03-08|节点快照显式空与缺席区分|BLOCKED_DEPENDENCY|
|E04-01|发券库存更新结果处理|NO_CHANGE_SOURCE_REVIEW|
|E04-02|每人限领历史范围|NO_CHANGE_SOURCE_REVIEW|
|E04-03|停发与失效区分|SOURCE_DELIVERED_UNVERIFIED|
|E04-04|奖励来源回执身份冲突|NO_CHANGE_SOURCE_REVIEW|
|E04-05|核销旧状态与重复提交|NO_CHANGE_SOURCE_REVIEW|
|E04-06|券核销撤销身份关联|SOURCE_DELIVERED_UNVERIFIED|
|E04-07|通关券冲正限定原奖励|BLOCKED_OWNERSHIP|
|E04-08|退款券奖励冲正回执重复|NO_CHANGE_SOURCE_REVIEW|
|E05-01|订阅模板去重与三模板上限|SOURCE_DELIVERED_UNVERIFIED|
|E05-02|订阅部分授权状态精确映射|NO_CHANGE_SOURCE_REVIEW|
|E05-03|订单历史退款确认与回包账号代次|SOURCE_DELIVERED_UNVERIFIED|
|E05-04|参与详情退款确认与回包账号代次|SOURCE_DELIVERED_UNVERIFIED|
|E05-05|收藏列表账号切换晚响应|NO_CHANGE_SOURCE_REVIEW|
|E05-06|收藏帖子与主题分页作用域|SOURCE_DELIVERED_UNVERIFIED|
|E05-07|取消收藏身份锁与刷新竞争|SOURCE_DELIVERED_UNVERIFIED|
|E05-08|收藏卸载与重新显示完成回调|NO_CHANGE_SOURCE_REVIEW|
|E06-01|音频重复注册保留中断身份|SOURCE_DELIVERED_UNVERIFIED|
|E06-02|音频手动暂停覆盖自动恢复|NO_CHANGE_SOURCE_REVIEW|
|E06-03|常亮异步失败可恢复|SOURCE_DELIVERED_UNVERIFIED|
|E06-04|poller停止重启在途世代|NO_CHANGE_SOURCE_REVIEW|
|E06-05|poller异常和退避配置边界|SOURCE_DELIVERED_UNVERIFIED|
|E06-06|最近定位异常值与存储失败|SOURCE_DELIVERED_UNVERIFIED|
|E06-07|选址取消拒权回调隔离|NO_CHANGE_SOURCE_REVIEW|
|E06-08|定位owner释放与启动竞态|SOURCE_DELIVERED_UNVERIFIED|
|E01-10|排期聚合读的父系列与场次快照|SOURCE_DELIVERED_UNVERIFIED|
|E01-11|候补维护畸形页的隔离边界|NO_CHANGE_SOURCE_REVIEW|
|E02-10|作者编辑历史父子读取同一快照|SOURCE_DELIVERED_UNVERIFIED|
|E02-11|信息流父行与hydrate子集合快照|SOURCE_DELIVERED_UNVERIFIED|
|E02-12|POI选项与保存状态条件一致性|BLOCKED_DECISION|
|E03-09|首批结局真库夹具的非兜底条件|SOURCE_DELIVERED_UNVERIFIED|
|E04-09|后台原始券历史新增事务原子性|IN_PROGRESS_SOURCE_ONLY|
|E05-09|订单历史列表同账号卸载晚回包|SOURCE_DELIVERED_UNVERIFIED|
|E05-10|参与详情读取账号与组件实例归属|SOURCE_DELIVERED_UNVERIFIED|
|E06-09|GPS启动API同步异常收口|SOURCE_DELIVERED_UNVERIFIED|
|E06-10|共享定位消费者异常隔离|SOURCE_DELIVERED_UNVERIFIED|
|E06-11|失败通知批次中同key重获身份|SOURCE_DELIVERED_UNVERIFIED|
|E01-12|排期24:00归一与HH:mm既定输入|SOURCE_DELIVERED_UNVERIFIED|
|E01-13|候补加入与售卖活动状态合同|IN_PROGRESS_SOURCE_ONLY|
|E02-13|迟到帖文审核回调的内容版本绑定|BLOCKED_DEPENDENCY|
|E02-14|社区草稿退群再发布的既定成员边界|IN_PROGRESS_SOURCE_ONLY|
|E04-10|核销期间模板失效当前事实|SOURCE_DELIVERED_UNVERIFIED|
|E03-10|场景剧情结局冻结的跨队最小依赖交接|BLOCKED_DEPENDENCY|
|E03-11|节点ID重映射producer合同|SOURCE_DELIVERED_UNVERIFIED|
|E01-14|系列成功请求跨上海日重发|SOURCE_DELIVERED_UNVERIFIED|
|E01-15|独立场次取消的作用域和状态读回|READY_SOURCE_ONLY|
|E01-16|场次取消收尾失败原子性|IN_PROGRESS_SOURCE_ONLY|
|E01-17|取消通知受众快照不被收尾改写|READY_SOURCE_ONLY|
|E02-15|删除编排后置失败整体回滚|IN_PROGRESS_SOURCE_ONLY|
|E02-16|通知读取的可见性与未读聚合|READY_SOURCE_ONLY|
|E02-17|媒体擦除法律保留与共享对象引用|READY_SOURCE_ONLY|
|E02-18|社区outbox处理行数和失败回执|READY_SOURCE_ONLY|
|E03-12|NPC剧情映射保留未知字段与归属|IN_PROGRESS_SOURCE_ONLY|
|E03-13|案件册线索进度沿冻结剧情|READY_SOURCE_ONLY|
|E03-14|案件册日志重放与初始状态差分|READY_SOURCE_ONLY|
|E04-11|批量过期与核销当前状态保护|READY_SOURCE_ONLY|
|E04-12|占库成功后券历史落库失败回滚|READY_SOURCE_ONLY|
|E05-11|订单历史同实例分页请求归属|NO_CHANGE_SOURCE_REVIEW|
|E06-12|定位stop同步回调异常传播|SOURCE_DELIVERED_UNVERIFIED|
|E05-12|返回收藏页的刷新排队|SOURCE_DELIVERED_UNVERIFIED|
|E05-13|订阅模板未知key继承成员过滤|SOURCE_DELIVERED_UNVERIFIED|
|E06-13|选址坐标非法类型|SOURCE_DELIVERED_UNVERIFIED|

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

一个文件同一时刻只有一个child写者；worker不Git操作。父协调者只stage冻结精确路径，小提交发布公共完整SHA并串行cherry-pick汇总。支援worker可以只写明确未被其他writer领取的新测试/证据，不能修改业务源码；若发现源缺陷交其原writer下一串行项。C00独占全局docs/plans控制账，本队只维护此目录3文件。

恢复先读最新LONGRUN_ROLLING、SOURCE_OWNERS、PR16及本队队列，核各工作树未提交补丁/公共HEAD，禁止覆盖。当前checkpoint之后可能已有新lane公共提交，先按Git历史及专属证据补账，不能重做。

shell GitHub凭据不可用；授权GitHub连接器可用。Git Data create_tree(base parent tree)、create_commit(parent SHA)、update_ref(expected_sha=parent,force=false)发布；确认文本树SHA相同后保存公共完整SHA，读取API commit元数据可重建相同本地对象/在树相同后移动私有ref。不得force push、读取凭据或依赖Mac。连接器也不可用时保留未发布补丁并如实阻塞。PR36 app附加工具挂起已中止，GitHub草稿与链接本身公共可读。

精确跨队依赖：E03-11最终扩围producer c9185cb6027ea04fa8a55ac16191d14f63f6f4d4已交CORE/C03，原freeze四参7cef70bf320c249b966f34cc3908f77912f06780已有CORE实际session消费者回执。E03-08损坏快照consumer、E03-10 scene先后顺序、E02-13审核内容版本交C00原owner；不并写表外source。E04-06已获额外2旧测试正式转交并交ce669a5af4b5231fe9660e44f5e420240f078e56，原初批B03测试同步保全业务断言。E04-07原historyId缺失不可猜补，等待发放侧确切回执合同/旧数据边界，不做DDL。

平台时长/额度/环境限制导致停止时，留下公共SHA、文件持锁、未提交补丁和下一项；不承诺永久运行。本地未来统一验证，当前不等Mac/验证再派工。未来测试命令只文本，需按证据开独占scratch及环境前提，旧231项或历史Node结果不继承到当前SHA。
