# 第三 Cloud：统计与成长可靠性开发包

标记：`CY-FORWARD3-20261015`。这是排除原 CORE、EXTRA 与 Claude/iOS 文件范围后新增的源码开发包。用户手动启动 Cloud，本地总控负责三队调度、失败反馈和 VPS 集中验证；原 C00 保留全局控制账的唯一编辑职责。

公共源码起点：`0d63043ad06775bc3dc52fb925620ef36d1081a7`（CORE PR44 的公开候选，未验收）。本计划分支只增文档；新 Cloud 从本计划分支创建自己的 `codex/mini-forward-source-20261015`，其业务源码起点仍为该 SHA，不从默认 master/旧私库分支开工，不把原候选测试结果继承为本任务通过。

## 两组可独立前进的任务

| ID | 工作 | 初始源码预算 | 写入责任 |
|---|---|---|---|
| F3-STAT-01 | 已有漏斗查询的非法日期和反向区间保护 | 0.5—1 人日 | 后台 AnalyticsController 与其测试 |
| F3-GROW-01 | 成长累计的读后覆盖、首次建行竞争及旧字段回写一致性 | 2—4 人日 | PlayerProgressionServiceImpl、PlayerGrowthMapper Java/XML 与其测试 |

这是源码准备/修复及回归用例编写的粗估，需按实际调用链重估，不含 VPS、真库、设备、集成或发布等待。两个任务都是先核源码再最小修复，不能把源码风险直接叫成两个已复现业务 bug。具体白名单、验收和停止条件见 [TASKS.json](TASKS.json)。

先开两条独立源码线，每组仅一位写者；最多六条并发是上限，不为凑六条或五十项拆出重复工作。每完成一项小提交并 push，随后可在已授权范围继续处理真实下一项，不等本地验证。新增模块/路径由本地总控转交；范围用尽如实记录，不制造任务。

## 源码依据与兼容约束

- 日期链：`AnalyticsController.funnel` 在起点源码没有格式/反向区间校验。实际消费者 `chengyinhub-ui/src/views/business/analytics/index.vue` 初始两日期为 null，并支持清空，因此不能突然要求日期必填、强套另一路 pilot 的 62 天或悄悄给统计行截断。保留既有无条件/单边合法请求，先修实际非法输入。全历史查询规模保护另列合同事项，**本包不声称已解决全量查询性能**。
- 成长链：`PlayerProgressionServiceImpl` 的 onNodeDone、onTopicComplete 先读取成长行，再将已读累计值写回；首次 ensureGrowth 先查后建。实际调用来自 ApiPlayProgressController、TopicCompletionAwardServiceImpl。开工须读这些调用者与事务/锁序（只读），确认现有保护是否已排除竞争；不能仅凭未见注解就强做修复。
- 成长不新增积分、徽章、等级、里程或连续打卡政策。未传节点请求身份不能自创重试去重；缺 TOPIC_CLEAR 配置时不能塞假徽章当幂等标记。无法在现有合同/结构保证的能力，写具体阻塞与方案交总控。

## 已核去重与文件边界

已按完整分页读取开放 PR 路径；最终 54 个开放 PR 快照中，本包六个既有精确路径与任何开放 PR 无交集，也未被当前全局源码账或保留表占用，见 [OWNERSHIP_CHECK.json](OWNERSHIP_CHECK.json)。这是时点核对，开工前刷新最新实际 claim。

扩域检查曾发现埋点 API/服务/定时任务/事件日志 Mapper 被历史 PR7/11/18 涉及，BizFunnelDailyMapper.xml 被 PR4 涉及，因此这些扩域文件**全部只读**；本包并不授予整个 analytics 目录。原两队的游戏/剧情/AI/资金/社区文件、Claude UI/新功能、原生 iOS 和 Flutter 均不接手。

根配置、公共 DTO/接口、SQL/迁移、CI/pom/锁文件、全局控制账、共享会员/积分/资金 Mapper 不可写。确需新增范围先给精确路径与原因，由总控正式转交，不能抢写。

## 交付、反馈与验证

只交源码和待执行回归；不得运行 Maven/Java/Node/DB/lint/设备/HTTP业务验证，不连 VPS，不造本地/Cloud 替代环境，不手动启动/重跑 CI。用户已明确把 Cloud 验证交本地执行；这不授权删除测试、禁用门禁或修改共享 hook。仓库自动 CI 自然运行，源码 push 不等待它。

小提交/草稿 PR，保留全部断言和负控；交付状态 SOURCE_DELIVERED_UNVERIFIED，未执行用例 NOT_RUN。交付回填任务ID、完整公共SHA、依赖、精确路径、测试类与声明数量（声明不等于执行数）、复现前提及剩余风险，不合并、不部署、不迁移、不接生产/支付/付费provider。

本地发失败包后，该队只占一条修复线（优先原作者；换人先正式移交），其他可独立任务继续，不整队停等。未运行新版本不能沿用旧候选通过成绩。

启动回执发到 [PR16](https://github.com/liboyang42-cpu/hengyin/pull/16)：标记 CY-FORWARD3-20261015、实际 Cloud threadId/环境状态、基线/分支/实际 child writer/精确 claim、测试 NOT_RUN。若缺跨聊天发送工具，直接用 PR16 评论与本队交付文档作为邮箱，不以本地 Mac 在线为开工前提。

## 总控与已有两队

- 本地总控：`01a13eed-e4d9-77f3-b875-c72d26ea7d59` / local。
- CORE / 原 C00、唯一全局账编辑：`01a13f3d-608a-745c-83d0-c5139121c02d` / durable。
- EXTRA：`01a13f9c-e8ba-753e-9c39-03840bb4900b` / durable。
- 新队只维护自己的 QUEUE/CLAIMS/DELIVERY 文档（TASKS 中精确路径）；全局 OWNERSHIP、BACKEND_CHANGES、iOS 交接由 C00统一登记片段。本地总控负责范围冲突裁决与验证反馈。

平台时长/额度停止时保存公共SHA、未提交补丁和恢复指令；不承诺无限运行。
