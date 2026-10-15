# EXTRA6滚动源码任务池

CY-HANDOFF: CODEX；真实thread `01a13f9c-e8ba-753e-9c39-03840bb4900b` / durable，Cloud running/connected。
最新已读扩权 `d3d38ccd70d25727ae6f972f1698254e935fce47`。分支 `codex/extra-six-rolling-20261015`，草稿[PR36](https://github.com/liboyang42-cpu/hengyin/pull/36)。

历史累计104具体项，源码/回归交付48、反证40、阻塞11；READY 0+在途3=3。目标50尚缺47，未补足；完成/反证/阻塞不算剩余任务，候选不算确认bug。

全部新验证NOT_RUN。禁止Java/Maven/Node/lint/DB/DevTools/业务验证或手动CI。SOURCE_DELIVERED_UNVERIFIED，不合并/部署/迁移/生产/VPS；未来命令仅文字。

## 实际worker分配

|worker gpt-6.1-sol|任务|状态|
|---|---|---|
|external_e01|E01-18|SOURCE_OWNER|
|external_e02|E02-20|SOURCE_OWNER|
|external_e03|无；等待精确转交|IDLE_AWAITING_EXACT_TRANSFER|
|external_e04|E04-14|SOURCE_OWNER|
|external_e05|无；等待精确转交|IDLE_AWAITING_EXACT_TRANSFER|
|external_e06|无；等待精确转交|IDLE_AUTHORIZED_SHARE_CANDIDATES_REVIEWED|

最多六模型槽；此刻3项在途、3槽空闲，不能写六模型一直运行。待授权时已完成无冲突候选/支援，不能造工作维持运行数。

## 精确队列

[QUEUE.json](QUEUE.json)为每项状态/公共完整SHA/未来命令；[CLAIMS.json](CLAIMS.json)为源码与支援证据单写边界。

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
|E03-08|节点快照显式空与缺席区分|DELEGATED_SOURCE_FIX|
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
|E04-09|后台原始券历史新增事务原子性|SOURCE_DELIVERED_UNVERIFIED|
|E05-09|订单历史列表同账号卸载晚回包|SOURCE_DELIVERED_UNVERIFIED|
|E05-10|参与详情读取账号与组件实例归属|SOURCE_DELIVERED_UNVERIFIED|
|E06-09|GPS启动API同步异常收口|SOURCE_DELIVERED_UNVERIFIED|
|E06-10|共享定位消费者异常隔离|SOURCE_DELIVERED_UNVERIFIED|
|E06-11|失败通知批次中同key重获身份|SOURCE_DELIVERED_UNVERIFIED|
|E01-12|排期24:00归一与HH:mm既定输入|SOURCE_DELIVERED_UNVERIFIED|
|E01-13|候补加入与售卖活动状态合同|BLOCKED_DECISION|
|E02-13|迟到帖文审核回调的内容版本绑定|BLOCKED_DEPENDENCY|
|E02-14|社区草稿退群再发布的既定成员边界|SOURCE_DELIVERED_UNVERIFIED|
|E04-10|核销期间模板失效当前事实|SOURCE_DELIVERED_UNVERIFIED|
|E03-10|场景剧情结局冻结的跨队最小依赖交接|RESOLVED_BY_OTHER_TEAM|
|E03-11|节点ID重映射producer合同|SOURCE_DELIVERED_UNVERIFIED|
|E01-14|系列成功请求跨上海日重发|SOURCE_DELIVERED_UNVERIFIED|
|E01-15|独立场次取消的作用域和状态读回|NO_CHANGE_SOURCE_REVIEW|
|E01-16|场次取消收尾失败原子性|SOURCE_DELIVERED_UNVERIFIED|
|E01-17|取消通知受众快照不被收尾改写|SOURCE_DELIVERED_UNVERIFIED|
|E02-15|删除编排后置失败整体回滚|SOURCE_DELIVERED_UNVERIFIED|
|E02-16|通知读取的可见性与未读聚合|SOURCE_DELIVERED_UNVERIFIED|
|E02-17|媒体擦除法律保留与共享对象引用|NO_CHANGE_SOURCE_REVIEW|
|E02-18|社区outbox处理行数和失败回执|SOURCE_DELIVERED_UNVERIFIED|
|E03-12|NPC剧情映射保留未知字段与归属|NO_CHANGE_SOURCE_REVIEW|
|E03-13|案件册线索进度沿冻结剧情|NO_CHANGE_SOURCE_REVIEW|
|E03-14|案件册日志重放与初始状态差分|SOURCE_DELIVERED_UNVERIFIED|
|E04-11|批量过期与核销当前状态保护|SOURCE_DELIVERED_UNVERIFIED|
|E04-12|占库成功后券历史落库失败回滚|NO_CHANGE_SOURCE_REVIEW|
|E05-11|订单历史同实例分页请求归属|NO_CHANGE_SOURCE_REVIEW|
|E06-12|定位stop同步回调异常传播|SOURCE_DELIVERED_UNVERIFIED|
|E05-12|返回收藏页的刷新排队|SOURCE_DELIVERED_UNVERIFIED|
|E05-13|订阅模板未知key继承成员过滤|SOURCE_DELIVERED_UNVERIFIED|
|E06-13|选址坐标非法类型|SOURCE_DELIVERED_UNVERIFIED|
|E01-18|新OFFERED活动当前可用性与旧hold释放|IN_PROGRESS_SOURCE_ONLY|
|E02-19|评论LIKE撤销后不可变重放身份审计|BLOCKED_OWNERSHIP|
|E02-20|通知偏好不相交PATCH原子保留|IN_PROGRESS_SOURCE_ONLY|
|E02-21|初次引用选项和快照权限反证|NO_CHANGE_SOURCE_REVIEW|
|E02-22|评论旧动作回执兼容精确转交方案|BLOCKED_OWNERSHIP|
|E03-15|新结局替换前旧引用映射的consumer来源|BLOCKED_DEPENDENCY|
|E03-16|当前旅程读面opt-in公共当前局选择|BLOCKED_OWNERSHIP|
|E04-13|奖励冲正积分当前锁读|SOURCE_DELIVERED_UNVERIFIED|
|E04-14|核销首读关联锁与后台编辑竞态|IN_PROGRESS_SOURCE_ONLY|
|E04-15|冲正真实caller事务和锁序反证|NO_CHANGE_SOURCE_REVIEW|
|E05-14|冻结玩法键显式损坏禁止回live|SOURCE_DELIVERED_UNVERIFIED|
|E05-15|分享文本UTF16截断边界|SOURCE_DELIVERED_UNVERIFIED|
|E05-16|分享坐标白名单及读写归一化审计|NO_CHANGE_SOURCE_REVIEW|
|E05-17|Encounter当前局共享helper接入合同|BLOCKED_OWNERSHIP|
|E06-14|分享同足迹重放与50上限反证|NO_CHANGE_SOURCE_REVIEW|
|E06-15|分享撤销与发布交错反证|NO_CHANGE_SOURCE_REVIEW|
|E06-16|匿名分享读取GONE与隐私闸反证|NO_CHANGE_SOURCE_REVIEW|

## 最近接续与阻塞

C00正式扩权后E05/E06六源码均逐字取7e5882公共路径，本地原内容一致无伪造baseline提交；仅共享findCurrent helper有独立import。E05-14冻结键损坏修复4方法已公共。Share重放/配额/撤销/匿名读取现有guard和真库套件已覆盖，三项反证不复制用例。E05 Unicode实际拆surrogate已由原writer交付3bd030c9f30ed2e79cb6350ae842fa2ef50a468c，4方法NOT_RUN。

最新d3d38控制已释放E01-18 Mapper+3fixture、E04-14四fixture、E02-20 nullable Mapper+单偏好旧fixture，原三writer正实施。E02旧LIKE额外query仍未授予。PR16 comment6195157560给准确packet链接及E03/E05 current scope必要旧fixture申请；当前不改未授权文件。E03-16 opt-in需3个必要旧fixture，依赖producer只导入不改。E03-15 consumer新ending替换前旧引用阻断交CORE原writer。

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

精确跨队依赖：E03-11最终扩围producer c9185cb6027ea04fa8a55ac16191d14f63f6f4d4已交CORE/C03，原freeze四参7cef70bf320c249b966f34cc3908f77912f06780已有CORE实际session消费者回执。E03-08已正式交E05并完成47f44e1dc98ce7d6bb11e7f95dd14535b0b7a98a；E03-10最新CORE已修，旧packet去重。E02-13审核内容版本仍交C00原owner。E04-06已获额外2旧测试正式转交并交ce669a5af4b5231fe9660e44f5e420240f078e56，原初批B03测试同步保全业务断言。E04-07原historyId缺失不可猜补，等待发放侧确切回执合同/旧数据边界，不做DDL。

平台时长/额度/环境限制导致停止时，留下公共SHA、文件持锁、未提交补丁和下一项；不承诺永久运行。本地未来统一验证，当前不等Mac/验证再派工。未来测试命令只文本，需按证据开独占scratch及环境前提，旧231项或历史Node结果不继承到当前SHA。


