# H：玩家草稿 b453 额度 UNKNOWN 窄修复复审

结论：**b453 在 Creator 层封住了 typed provider UNKNOWN 被 finally 释放的具体漏口**；未发现该新增分支仍能触达 refund/retain。输入 generic UNKNOWN、已知发送前故障、有效稿与不完整稿的原政策没有被扩大。该判断是源码结论，Java/MySQL/Redis未执行。外层原请求恢复仍有两项可触发 P2：typed UNKNOWN 被写成 FAILED，及24小时请求缓存过期后原号可重入生成；均为既有边界，不是此补丁新引入的回归，不应把窄修复表述成完整恢复验收。

## 身份、范围和执行

- 公共仓库 `liboyang42-cpu/hengyin`，基线 `2ec68899099a1e250df0949a8383bf85b5d3ffe0`。
- 候选 `b453b6e1b41e59b365255a05c318cd305fd1c054`，parent `9b92fb5738244c677fea7470232f67d3b97b7a98`，tree `35e43f886ab01a29249008e00bb29dfe484ef113`。
- H起始 HEAD `1bebf3df0169f4a04086c665d8301000afef734f`，独占 `/workspace/hengyin-h-review` / `codex/architecture-day-h-review`，开始干净；只写本H目录。
- 从已验证de31视图补齐当前15个生产差异；完整当前2721个生产Java/XML blob逐项匹配未截断公共tree，真实caller搜索基于当前候选。另获取精确当前测试、Page及helper，校验blob。详见 `evidence.json`。
- 实际执行：Python源码/blob核对、`git apply --check`、原客户端状态测试 **Node6/6通过，0fail/skip**。输出见 `node-client-state.txt`。该Node测试真实运行Page/helper、明确替身HTTP响应，不执行Java或证明服务端状态正确。
- Java编译/单元测试、MySQL/Redis、HTTP真实身份、外部模型均未运行/未调用。作者新增参数化测试为1方法×2参数，不是已运行证据。本轮未派发CI、未新增Agent、未改生产。D W19属于后续独立范围，本轮未审，不外推通过。

## 已核实的窄合同

真实caller：`ApiAiController.themeDraft:157–175` 无requestId时直接调Creator；有requestId时走 `themeDraftWithRequestId:179` → `AiThemeDraftRequestService.submit:99–101` → `AiCreatorServiceImpl.themeDraft` → `AiGatewayServiceImpl.generate` → `LlmClient.chat`。

玩家草稿的额度所有者是Creator独立3次池，Gateway `:131–133` 不再占general池。因此Gateway没有该玩家receipt，`stageQuota` 对null receipt不执行；Creator原 `:123` 已提交 `INPUT_REVIEW_STARTED`，并非MODEL_STARTED。**worker逻辑不是只认MODEL_STARTED**：`AiQuotaRecoveryService.reconcile:109–110` 对所有非PRE_RESERVE且非REFUND_DUE行直接转UNCERTAIN，不碰Redis、不调用provider；故本次保留在INPUT_REVIEW_STARTED仍会保守归类，不会因租约到期盲退或重发。

`Creator:146–162` 的catch按已有subtype判定并原对象重抛，finally在typed UNKNOWN时整段跳过。Gateway `:205–222` 的实际chat异常/空答复均包装该subtype，日志落库失败被saveLog内部捕获，不会替换成普通异常再触发Creator退款。没有基于错误文字猜是否配置故障。

原身份链：Creator `:601–611` 用原北京时间日counter + nonce receipt；`prepare`独立REQUIRES_NEW先落MySQL；Lua原子预占绑定generation；`reserved`只从该receipt读原generation并落库，不取当前generation冒充；stage先落再进入Gateway。typed UNKNOWN发生后这些字段不被清空/更换。worker以原counter/receipt/generation和owner/fence处理，保留原行，不拿新日counter退款。

Spring `AiQuotaRecoveryService` 是@Service且Mapper/Redis/事务管理器构造器必需；正常完整组件装配会注入Creator。`required=false`和新增测试的无recovery分支只说明替身/缺bean时不即时退款：**没有持久恢复证明**。不把mock prepare/reserved调用视为MySQL已提交。调度仅提供 `aiQuotaRecoveryTask.run`，源码注释明确不自动安装生产job；是否实际登记/启用未验证。

## 发送前故障和既有稿件政策

| 分支 | 当前源码行为 | 判断 |
| --- | --- | --- |
| prepare失败 | 尚无Redis预占，不进入Gateway | fail-closed |
| Lua抛异常/空/非法结果、reserved落库失败 | 以原receipt补偿；失败留下原恢复待办，0provider | 未见新误扣路径；存储执行未验证 |
| Creator stage失败 | 尚未调Gateway，finally原身份补偿 | 不等同provider UNKNOWN |
| 输入审核generic UNKNOWN/审核不可用/已知模型配置缺失 | Gateway包装普通AiServiceException，Creator按原政策release | 本补丁未改扣费语义 |
| 输入明确INPUT_BLOCKED | 原retain政策 | 保留 |
| chat异常/空响应typed UNKNOWN | 不release，不retain成功；worker UNCERTAIN | 具体源码修复成立 |
| 有效完整draft | 原accepted/retain，返回剩余额度 | 保留 |
| mock/JSON坏稿/缺plans或nodes/其他既定无效输出 | 原release政策 | **不建议改成全部扣费** |

补偿依赖数据库/Redis可用及原租约/状态CAS；存储故障或租约已被worker接管时不保证立即退额，源码会保守待查。这是未执行的恢复边界，不能承诺发送前故障任何条件下都实时退到账。新增两参数用例mock Gateway，没有覆盖真实Gateway配置检查+Creator+恢复库的组合；root需安排定点正反例，不能只凭Mockito交互签运行验收。

## H-QB453-01 / P2：外层把真实 provider UNKNOWN 固化为 FAILED

精确位置：`AiThemeDraftRequestService.java:110–113` catch所有AiServiceException并设FAILED，`:121–125` 再持久化FAILED；`ApiAiController.java:206–209` 映射 `AI_DRAFT_FAILED`。真实客户端 `pages/publish/simple/index.js:141–150` 仅将三个PENDING code留锁，其他响应清原requestId；`:178–181` 回读FAILED同样清锁。未修改灵感再次点击会在 `:132` 创建新requestId并POST，而正确UNKNOWN应只回读原号。

最小复现：使用合法member+requestId；让实际Gateway chat超时产生typed UNKNOWN。Creator正确保留额度；RequestService却返回/写FAILED。原rid回读和重放也FAILED（TTL内不再调用Creator）；Page清pending，原灵感再点产生新rid。**不是证明原rid在TTL内自动重发，也没有观察真实供应商计费**；问题是未知事实被错误宣告明确失败，使原查询身份在正常UI路径被丢弃。

最小建议：在普通AiServiceException之前单独处理该subtype，保存/返回原requestId的UNKNOWN，供已有Controller `AI_DRAFT_OUTCOME_UNKNOWN` 和客户端原留锁路径使用；不把输入generic UNKNOWN、已知配置故障或无效稿全部变成UNKNOWN。`read`也须稳定重放UNKNOWN，而非只改首次HTTP文案。新增review patch第一例要求submit/read/replay均UNKNOWN且Creator一次，**未编译/运行，按源码预计在当前首个状态断言失败**。

## H-QB453-02 / P2：请求缓存TTL仍释放原号，额度恢复行无法找回它

精确位置：RequestService `:39` RETENTION_HOURS=24；`:76–78` 用同TTL的setIfAbsent作唯一身份；`:84–95` 仅有缓存记录时阻止重入；`:145–148` 缓存缺失直接NOT_FOUND；`:193–196` 终态仍24小时。quota恢复行保存member/biz/day/counter/nonce/receipt，**没有客户端requestId**；Gateway玩家traceId亦新生成，不能据此声称已持久绑定该原请求。

最小复现：原rid发生typed UNKNOWN并占额度，等待请求Redis键24小时TTL失效（或批准测试fixture精确模拟expiry）；跨日恢复了当日额度后，以同member/rid原payload再次submit，setIfAbsent可成功，Creator再调用并创建新日/nonce。旧恢复行即使仍UNCERTAIN也没有该rid可阻止重入。原Page回读NOT_FOUND也宣称“没送达”而清pending。**旧原日额度自然换日不是此问题；问题是响应缓存消失被当成原外部调用未发生。**

这是原设计既有边界，不要求本窄补丁私自引入通用框架或猜政策。最小合同是原request业务身份/UNKNOWN存在性须在响应TTL后仍可查，缺响应不能据此重发；需owner提出具体原rid与意图/nonce绑定及保留方案，若新增schema先给迁移/预检/回退，不在H执行DDL。review patch第二例只模拟缓存expiry，要求同号不再进入Creator；**未编译/运行，不能当真实Redis TTL证据**。仅把FAILED改UNKNOWN可修01，但不能单独关闭02。

## 必须回归与裁决

1. Creator新增两参数分支，以及原generic UNKNOWN、配置故障、完整稿/不完整稿、第四次0provider；保留所有旧断言。
2. 组合实际Gateway+Creator：缺key/disabled/input unavailable均0chat、原预占最终补偿；typed chat异常/空答复各1chat、同nonce不refund/retain。
3. 批准MySQL/Redis：原day/generation/receipt写读回，INPUT_REVIEW_STARTED到期经真实worker变UNCERTAIN且counter不减；恢复缺库/失败不宣称成功；核对调度登记而非自动启用。
4. 外层原rid首次/回读/重试UNKNOWN、client保留原rid；缓存过期不能重发（方案批准后真实存储测）；已知故障/无效稿原失败及退额行为不变。

窄修复源码：可接受，运行未知。整个provider UNKNOWN请求恢复：上述P2仍未闭合。Node6通过只证明客户端已有区分路径，反而说明服务端状态分类必须准确；不抵消Java/真库缺口。未审D29、未改变资金共同fence或B支付P1裁决。

H仅交付报告、证据、原Node日志和未应用两例测试patch。无schema/依赖/权限变更、无生产/供应商动作。回退b453会重开已识别的UNKNOWN错误退额；任何回退均不能删除原counter、generation、receipt、恢复/审计记录。
