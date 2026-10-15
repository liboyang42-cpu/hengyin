# EXTRA6 50项滚动源码任务池

CY-HANDOFF: CODEX；CY-LONGRUN-SOURCE-20261015-v1。

控制授权：`06cc7af4170bbf77532cfab6186be809d84ac3e9`；真实Cloud任务`01a13f9c-e8ba-753e-9c39-03840bb4900b`/durable。
分支：`codex/extra-six-rolling-20261015`；本队工作树`/workspace/hengyin-extra-rolling`。

当前池：50项可行动/在途，历史累计68项；9项源码交付、7项反证、2项阻塞。6 lane各有当前领取，包含冻结待父提交阶段，不等于6个模型此刻都在生成。全部新验证NOT_RUN。

状态统一SOURCE_DELIVERED_UNVERIFIED；新回归NOT_RUN。用户已推迟所有编译、测试、lint、Node、Java/Maven、数据库、设备/业务验证；不手动重跑或等待CI，不合并、部署、迁移或访问生产。自动CI保留自然运行。

## 六线当前领取

|线路|当前项|工作分支|模型/写者|
|---|---|---|---|
|E01|E01-10|codex/extra-e01-regression-20261015|gpt-6.1-sol / external_e01|
|E02|E02-10|codex/extra-e02-regression-20261015|gpt-6.1-sol / external_e02|
|E03|E03-09|codex/extra-e03-regression-20261015|gpt-6.1-sol / external_e03|
|E04|E04-04|codex/extra-e04-regression-20261015|gpt-6.1-sol / external_e04|
|E05|E05-09|codex/extra-e05-regression-20261015|gpt-6.1-sol / external_e05|
|E06|E06-09|codex/extra-e06-regression-20261015|gpt-6.1-sol / external_e06|

## 50项精确队列

详细触发/不变量/精确路径、优先级、预计源码工时、依赖、提交字段见[QUEUE.json](QUEUE.json)；每文件唯一child写者见[CLAIMS.json](CLAIMS.json)。

|ID|任务|状态|
|---|---|---|
|E01-01|排期日期边界归一|NO_CHANGE_SOURCE_REVIEW|
|E01-02|系列更新保留集合时刻|NO_CHANGE_SOURCE_REVIEW|
|E01-03|系列重发身份冲突|READY_SOURCE_ONLY|
|E01-04|系列到场次作用域读取|READY_SOURCE_ONLY|
|E01-05|有候补场次结构保护|READY_SOURCE_ONLY|
|E01-06|候补取消与晋升竞争|READY_SOURCE_ONLY|
|E01-07|到期offer状态读回|SOURCE_DELIVERED_UNVERIFIED|
|E01-08|候补令牌归属校验|READY_SOURCE_ONLY|
|E01-09|候补订单绑定终态|READY_SOURCE_ONLY|
|E02-01|帖子详情实时可见性|SOURCE_DELIVERED_UNVERIFIED|
|E02-02|信息流游标与权限过滤|NO_CHANGE_SOURCE_REVIEW|
|E02-03|引用聚合不复活撤回内容|BLOCKED_DECISION|
|E02-04|删除命令重复与版本冲突|READY_SOURCE_ONLY|
|E02-05|撤回位置后多个读面一致|READY_SOURCE_ONLY|
|E02-06|评论列表父帖隔离|READY_SOURCE_ONLY|
|E02-07|跨帖评论删除关联|READY_SOURCE_ONLY|
|E02-08|互动重放不泄露不可见详情|READY_SOURCE_ONLY|
|E02-09|社区媒体回执归属|READY_SOURCE_ONLY|
|E03-01|已冻结结局优先返回|SOURCE_DELIVERED_UNVERIFIED|
|E03-02|未知旧快照不冒充空结局|SOURCE_DELIVERED_UNVERIFIED|
|E03-03|结局首命中及显式优先级保真|NO_CHANGE_SOURCE_REVIEW|
|E03-04|章节结束正文沿本局快照|READY_SOURCE_ONLY|
|E03-05|旅程读模型账号和场次关联|READY_SOURCE_ONLY|
|E03-06|结局章引用重映射完整性|READY_SOURCE_ONLY|
|E03-07|结局校验未知枚举拒绝|READY_SOURCE_ONLY|
|E03-08|节点快照显式空与缺席区分|READY_SOURCE_ONLY|
|E04-01|发券库存更新结果处理|NO_CHANGE_SOURCE_REVIEW|
|E04-02|每人限领历史范围|NO_CHANGE_SOURCE_REVIEW|
|E04-03|停发与失效区分|BLOCKED_OWNERSHIP|
|E04-04|奖励来源回执身份冲突|IN_PROGRESS_SOURCE_ONLY|
|E04-05|核销旧状态与重复提交|READY_SOURCE_ONLY|
|E04-06|券核销撤销身份关联|READY_SOURCE_ONLY|
|E04-07|通关券冲正限定原奖励|READY_SOURCE_ONLY|
|E04-08|退款券奖励冲正回执重复|READY_SOURCE_ONLY|
|E05-01|订阅模板去重与三模板上限|READY_SOURCE_ONLY|
|E05-02|订阅部分授权状态精确映射|READY_SOURCE_ONLY|
|E05-03|订单历史退款确认与回包账号代次|SOURCE_DELIVERED_UNVERIFIED|
|E05-04|参与详情退款确认与回包账号代次|SOURCE_DELIVERED_UNVERIFIED|
|E05-05|收藏列表账号切换晚响应|NO_CHANGE_SOURCE_REVIEW|
|E05-06|收藏帖子与主题分页作用域|READY_SOURCE_ONLY|
|E05-07|取消收藏身份锁与刷新竞争|READY_SOURCE_ONLY|
|E05-08|收藏卸载与重新显示完成回调|READY_SOURCE_ONLY|
|E06-01|音频重复注册保留中断身份|SOURCE_DELIVERED_UNVERIFIED|
|E06-02|音频手动暂停覆盖自动恢复|READY_SOURCE_ONLY|
|E06-03|常亮异步失败可恢复|SOURCE_DELIVERED_UNVERIFIED|
|E06-04|poller停止重启在途世代|READY_SOURCE_ONLY|
|E06-05|poller异常和退避配置边界|READY_SOURCE_ONLY|
|E06-06|最近定位异常值与存储失败|READY_SOURCE_ONLY|
|E06-07|选址取消拒权回调隔离|READY_SOURCE_ONLY|
|E06-08|定位owner释放与启动竞态|SOURCE_DELIVERED_UNVERIFIED|
|E01-10|排期聚合读的父系列与场次快照|IN_PROGRESS_SOURCE_ONLY|
|E01-11|候补维护畸形页的隔离边界|READY_SOURCE_ONLY|
|E02-10|作者编辑历史父子读取同一快照|IN_PROGRESS_SOURCE_ONLY|
|E02-11|信息流父行与hydrate子集合快照|READY_SOURCE_ONLY|
|E02-12|POI选项与保存状态条件一致性|READY_SOURCE_ONLY|
|E03-09|首批结局真库夹具的非兜底条件|IN_PROGRESS_SOURCE_ONLY|
|E04-09|后台原始券历史新增事务原子性|READY_SOURCE_ONLY|
|E05-09|订单历史列表同账号卸载晚回包|IN_PROGRESS_SOURCE_ONLY|
|E05-10|参与详情读取账号与组件实例归属|READY_SOURCE_ONLY|
|E06-09|GPS启动API同步异常收口|IN_PROGRESS_SOURCE_ONLY|
|E06-10|共享定位消费者异常隔离|READY_SOURCE_ONLY|
|E06-11|失败通知批次中同key重获身份|READY_SOURCE_ONLY|
|E01-12|排期24:00归一与HH:mm既定输入|READY_SOURCE_ONLY|
|E01-13|候补加入与售卖活动状态合同|READY_SOURCE_ONLY|
|E02-13|迟到帖文审核回调的内容版本绑定|READY_SOURCE_ONLY|
|E02-14|社区草稿退群再发布的既定成员边界|READY_SOURCE_ONLY|
|E04-10|核销期间模板失效当前事实|READY_SOURCE_ONLY|
|E03-10|场景剧情结局冻结的跨队最小依赖交接|READY_SOURCE_ONLY|

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

每lane一个worker/同文件任务串行。完成一项先冻结精确补丁，由本队协调者串行小提交、push公共完整SHA并更新本账；随后立即领下一READY，不等验证。完成/反证/阻塞后从实际授权调用链补一个未重复具体项，递增ID并保留历史，目标50项可行动/在途；无合理候选记录EXHAUSTED，不造永动任务。

本队只写全局SOURCE_OWNERS明确的29个源码文件、各任务列出的新regression/evidence、首批12文件和本协调目录三文件。其他源码、旧测试、全局控制账、Claude/iOS/Flutter/共享门禁只读。E05订单detail明确不转交。E03 runtime调用点归CORE/C06；本队先交兼容4参freezeEnding公共SHA，对方再接现有session。

恢复先读当前控制LONGRUN_ROLLING/SOURCE_OWNERS、PR16最新评论和本队QUEUE/CLAIMS；核远端SHA与每tree未提交补丁，禁止覆盖工作。平台时长/额度/环境终止前保存队列游标、实际持锁、阻塞与下一项到GitHub；本队不承诺永久运行，不依赖本地Mac中转。
