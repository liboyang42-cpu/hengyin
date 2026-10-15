# A01 会话回执与未知恢复：复用调查交付

2026-10-15；共同基线 d551c6b，调查候选 HEAD cc6a8b9（工作区其他 lane 另计）。本单仅改TopicRouteRuntimeServiceImpl与其专属既有测试中的A01用例；未改DTO、Controller、SQL或页面。已读根AGENTS、任务计划全部文件及B01–B05证据。BUG批次已有明确处置，不把B05未授权差异扩入本单。

## 源码证据与裁决

- `GameSessionRuntimeServiceImpl.command:264` 已先按 activityId/memberId/requestId 查原回执，再查当前 revision、派发业务。`requestHash:2969` 对 activityId、nodeId、expectedRevision、规范化action、递归排序payload做SHA-256；同身份异命令抛 GAME_IDEMPOTENCY_CONFLICT，同命令返原result/revision，不能改expectedRevision后冒充原重试。`replay:2948` 不二次执行业务。
- `GameSessionRuntimeMapper.xml:778–813` 回执读按活动/当前会员隔离；竞争插入失败后当前锁读原回执；回执完成要求 PENDING+原hash。`command` 事务内写回执及业务，失败回滚；Controller在捕获已知GameSessionException后用REQUIRES_NEW保存FAILED。内部异常不伪造FAILED/APPLIED，客户端应继续保留未知。
- `receipt:304` 未找到返回PENDING且无receiptId，这是“尚不能确认”，不是已接收证明，也不是确定失败。回执读不要求客户端perspective；只读取当前会员原事实，不借此授予新写权限。第二设备同账号可用原activityId/requestId读；另一账号同键查不到该回执。
- `TopicRouteRuntimeServiceImpl.advance:593` 与 `advanceLinear:1516` 按既有routeActionId定位原推进事实，CAS防重复推进。这里**不同于game requestId指纹合同**：已有明确测试 `sameActionIdWithDifferentPayloadReplaysWithoutConflictCode` 保留同actionId异node/receipt直接返回原结果。协调者明确本轮保持兼容，不改成409，不在唯一键加action。不能宣传此接口具有完整异命令指纹检测。
- `TopicRouteSessionMapper.xml` selectCurrentByOwner/selectAccessibleById携memberId；updateStateCas限定id/memberId/version/status=0，selectByOwner携playInstanceId。`recordNodeResult:2743` 显式比较当前playInstanceId。epoch是客户端回调生命周期概念，不是HTTP字段；不能虚构服务端epoch校验。B01已有晚到回调隔离证据，本单未重复编辑JS。
- 游戏prepare冻结moduleConfigSnapshotJson，运行读同一快照；路线建局冻结configSnapshotJson，nodeBlockConfig优先冻结nodeJourney。老局缺该键仍按既有兼容回退实时模板，见既有 `legacySnapshotWithoutNodeJourneyFallsBackToLiveTemplate`；不能将所有历史局声称完整不可变。A03负责更全面兼容核验。
- 游戏PLAYER_SUBMIT先validateEvidence并校验上传真实存在/当前会员归属，后写提交和完成结果；照片存储不可用抛GAME_EVIDENCE_STORAGE_UNAVAILABLE。既有测试覆盖假照片、混入无效照片、存储失败均不记完成。`ApiPlayProgressController.photo:2096/2182` 缺picUrl直接错误，首次完成前校验上传事实；已有完成重放豁免重验用于原事实读回，不能解读成缺照片也能首次发奖。

## 实际与未执行

实际运行 `node --test chengyinhub-xcx/tests/unit/game-session-client.test.js`，16/16通过、0跳过，原始输出在game-session-client-node.txt。覆盖客户端传输失败未知、回执PENDING/FAILED/APPLIED解析等；使用假请求桩，不是HTTP或数据库验收。客户端未新增测试。另新增两个A01 Java行为用例（未执行）：真实runtime.state建局冻结结局正文/排除其他主题/不加入可推进章节、修改或删除源章节后续局不重冻；无结局配置也写空对象标记新快照。

Java/Maven/数据库/VPS/真实HTTP/微信设备均未执行。既有CI应集中运行 GameSessionRuntimeServiceImplTest、GameSessionRuntimeIsolationTest、GameSessionRuntimeMapperSqlTest、ApiGameSessionControllerTest、TopicRouteRuntimeServiceImplTest、ApiPlayRouteConflictHttpContractTest、TopicRouteFrozenSessionReadSideTest；这些名称是交接清单，不是通过声明。

独立业务读回仍须在授权测试环境：同账号第二设备丢响应后按原键读回，核一次有效状态/资产变化；异账号不可取原回执；旧revision新键确定失败；同键同命令返原结果、异命令冲突；未知不换键补奖；旧局编辑主题后继续读取快照；缺照片/存储故障无完成与奖励。请读取实际积分流水、完成行、session revision/route version，而非只看接口200。真实DB索引存在性、事务隔离及并发均未核实，本报告不替代运行库证据。

## 与A03协作的已确认缺口与最小修复

A03确认PlayJourneyStoryServiceImpl.endingChapter实时读章节，而原freezeThoughtChapters明确排除结局章。作者改写/重建后旧局结局正文会漂移或消失。协调者授权A01继续独占TopicRouteRuntimeServiceImpl写侧，A03独占PlayJourneyStoryServiceImpl读侧。

本单在freezeThoughtChapters中独立写snapshot顶层endingChapters对象（key=chapterId），value仅id/name/atmospherePreset/audioUrl/blocks；blocks沿原结局resolve(chapter,emptyList)，仅冻结同主题且被journeyStory引用的结局章。新快照即使无章也写空对象；不将结局放入thoughtChapters，不改变结局选择顺序/条件/优先级。A03读侧仅投影已选章，缺整个键才兼容原实时回退；已有键缺该ID不回退。没有新表/字段/版本政策。

本单可审阅范围为合同冻结、反证、轻量客户端验证与A03结局冻结写侧补丁；没有建立第二会话真源。源码交付完成，Java及业务验收未关闭；无需DDL、权限/RBAC调整或迁移。回退须与A03读侧协同撤销endingChapters消费/写入和对应测试，不删历史快照、不回退其他lane。已有历史局缺键的正文若已丢失，本补丁不能还原。
