# 未完成bug与共用后端：10条滚动线路

用户澄清：不是新增产品功能，要完成现有bug和共用后端。先前N01/N02仅只读调查，未写任何源码，已停止新功能方向；积分规则已有接口不重造、协办新接口维持DRAFT不纳入本轮。现有恢复/作者读回界面和协议先复用，不新建同义面板/后端。

用户要求可拆10路；当前平台总并发7含C00，因此最多6个worker。实际先5条源码线路＋1个回归修复预留槽，排队线路完成精确取证/分配后滚动接续；所有worker显式gpt-6.1-sol，不虚报10个同时运行。VPS始终本地验证者串行一个Maven容器，worker只编辑/轻量Node，不争资源。

冻结已验证候选为1e403b135206844a34219753a36e2dee39c7dd59（PR18），231矩阵闭环/CI成功，证明不沿用到后续修改。后续工作仅在独立checkout `/workspace/hengyin-followups`，分支codex/mini-bug-arch-followups-20261015，基线同1e403b13，C00统一按路径提交；worker不得切分支/暂存/提交/修改控制账。尚无后续源提交时只叫工作基线，不称新已测候选。

## 线路与精确写权

### L01 现有UNKNOWN恢复：旧回执/投影单调与请求身份

唯一写者：B01_sol原写者；状态：ASSIGNED。

- `chengyinhub-xcx/pages/play/index.js`
- `chengyinhub-xcx/pages/play/utils/player-game-module.js`
- `chengyinhub-xcx/tests/unit/player-game-session-entry-contract.test.js`
- `chengyinhub-xcx/tests/unit/player-game-module.test.js`

### L02 作者edit-detail完整聚合快照读

唯一写者：B02补完Sol（feature_points_rules_sol）；状态：ASSIGNED。

- `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiTopicController.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/B02TopicEditDetailSnapshotMyBatisTest.java`

### L03 NPC本人删除即时屏障/旧回执擦除/防晚到重建

唯一写者：A02_sol原写者；状态：ASSIGNED。

- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/NpcChatDataService.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/NpcChatService.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/NpcChatWriteService.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/NpcChatRequestMapper.java`
- `chengyinhub-system/src/main/resources/mapper/business/NpcChatRequestMapper.xml`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/NpcChatServiceTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiAiNpcChatDataTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/A02NpcDeleteBarrierMySqlIT.java`

### L04 AI日志未知token不显示为0并标估算

唯一写者：旧bug显示Sol（feature_coorganizer_sol）；状态：ASSIGNED。

- `chengyinhub-ui/src/views/business/ailog/index.vue`
- `chengyinhub-ui/tests/unit/a02-ailog-usage.test.js`

### L05 作者create/update丢响应与账号晚响应现有流程修复

唯一写者：publish_unknown_sol；状态：ASSIGNED。

- `chengyinhub-xcx/pages/publish/utils/publish/publish-workflow.js`
- `chengyinhub-xcx/pages/publish/fabu/index.js`
- `chengyinhub-xcx/utils/publish/pro-editor-draft.js`
- `chengyinhub-xcx/tests/unit/publish-workflow.test.js`
- `chengyinhub-xcx/tests/unit/pro-editor-draft.test.js`
- `chengyinhub-xcx/tests/unit/b02-publish-write-unknown.test.js`

### L06 排期/候补真实MySQL读回与竞态缺口

唯一写者：B04原写者下一空位接续；状态：QUEUED。

无新增写权；排队/预留不占有未明确文件，不因沉默夺原作者文件。

### L07 隐私/旧详情/退出社区真实MySQL隔离验收

唯一写者：待实际精确派工；状态：QUEUED。

无新增写权；排队/预留不占有未明确文件，不因沉默夺原作者文件。

### L08 结局/版本/历史快照兼容未闭合链复核

唯一写者：A03原写者下一空位接续；状态：QUEUED。

无新增写权；排队/预留不占有未明确文件，不因沉默夺原作者文件。

### L09 B03券库存/限领/退款冲正剩余实际缺口

唯一写者：B03原写者下一空位接续；状态：QUEUED。

无新增写权；排队/预留不占有未明确文件，不因沉默夺原作者文件。

### L10 VPS/CI反馈修复专线及集中回归收口

唯一写者：C00保留worker槽，按失败原写者即时接续；状态：RESERVED_FOR_REGRESSION_FIX。

无新增写权；排队/预留不占有未明确文件，不因沉默夺原作者文件。

## 修复合同/边界

- L01已由既有VM harness复现revision9发回执→新投影12→旧APPLIED10回退到10；修当前投影/当前pending身份及单调版本，刷新保留内存原命令，重启仍只保留现有脱敏索引，不持久化现场正文/照片。不改WXML/HTTP/服务端回执或奖励；routeActionId与game requestId语义不混。
- L02 edit-detail无事务分开读旧topic与重建后children，可混合版本。仅给真实既有聚合读入口明确RR只读事务（仓库ApiRoleController有同范式），权限/DTO不变；真实MySQL代理+交错fixture和独立后续读回验证，不拿mock代替。
- L03现export/delete已承诺本人记录“立即生效且不可恢复”；落实既有删除语义不等于新留存政策。本人持久锁覆盖请求准入、删除、lease finish+日志同事务，网络/provider期间不持锁；擦safe_text与日志但保留requestId/user/session唯一身份墓碑、原已收费事实，不删除回执造成重发。所有成功/失败日志分支覆盖；删除数量仍只计日志，ai_generation_log审计留存保持原样，语音日池政策不纳入。无新状态/费用/DDL/权限模型；若确需新协议/产品决策，先给具体证据，其他独立工作继续。
- L04null/undefined→未知，真实0仍0，非null原估算数字标估算，列表/详情统一。仅Vue显示逻辑+专属测试，不改布局/路由/权限/API。
- L05首次create服务已提交但响应丢失不能失败后直接再次create；复用已有workflow/local draft身份、账号边界与既有结果提示，明确UNKNOWN与已知失败，不按名称猜认成功或声称后端幂等。已有ID可按原ID重新读事实；无ID不能自动再发create，需保留未知及当前草稿。修跨账号/卸载晚响应，不新建页面/孤儿发布链/“保存产品”，不加后端requestId或DDL。先提交精确最小方案再落超出既有模型的持久状态。
- L06–09先复用既有测试/查询；仅对证实剩余问题或必要真实集成缺口派工，不为凑10造代码或重跑已通过矩阵。新增测试/源码必须再次登记精确路径。

## 交接证据

旧B01/B02/A02所有者已交原补丁并明确停止（现有锁INTEGRATED），本轮B01/A02原写者接续；L02既有Controller初始预留后未被本轮其他worker编辑，C00按新精确读取端委派；L04/L05新范围仅现有行为bug修复，不夺Claude正在做的CL1–CL5文件。L01只读取证者已停止且无补丁，交原B01。每个文件一个写者，公共门禁/动作账/路由仍C00，不覆盖原冻结树。

## 已明确的最小补充合同/路径

L03追加唯一测试文件 `chengyinhub-admin/src/test/java/com/chengyinhub/business/it/NpcChatUserDataMyBatisIT.java` 给A02_sol：原夹具直接构造NpcChatDataService，必须注入真实事务helper及本人行fixture，不以null fallback绕屏障；原断言和门控保留。

L05准许在既有按member/draftUuid隔离的本地草稿信封增加可选 `publishAttempt:{status:'UNKNOWN',topicId:''|原主题ID}`，无payload/token/新敏感信息。旧信封无此字段沿既有行为。完成本地校验且马上发write前同步持久；存储失败不得发送；能证明未发出则清除，明确业务失败可回idle，成功按旧流程清草稿；网络/卸载等结果未知保留。首次create无ID时不可自动重发/按名称认领，现UI仅提示去项目列表核对，本地草稿不丢，不造后端幂等承诺。已有ID的UNKNOWN按原ID读取只说明服务器事实，不证明原写成功；不得自动把读回覆盖本地编辑或自动解锁。只可复用已有服务端版本/本地草稿冲突确认：用户明确采用服务器版本后清未知并更新真实ID/基线；若保留本地草稿则仍未知，任何继续覆写须明确用户确认及刷新基线，不能仅因HTTP200解锁。复用既有入口/对话框，不新增页面/按钮/产品步骤；现阶段无此确认路径时保留未知限制并如实交付，不偷偷放开重试。

## 六路独立Cloud委派（最新）

L06–L09改为PENDING_EXTERNAL_CLOUD，承接E01–E04；E05/E06独立定向复核。精确白名单与限制以[EXTERNAL_SIX_HANDOFF.md](EXTERNAL_SIX_HANDOFF.md)为准；仅新测试/专属证据文件写权，业务源码原锁保持。创建及接收前不算运行，不能启动本地替代。

L05追加`chengyinhub-xcx/tests/unit/fabu-edit-existing-topic.test.js`，仅修原匿名0/无UUID旧夹具为真实账号42与既有草稿身份，保留原API/行为断言，不放宽写前存储门禁。

正式回执已读并授权E01–E06启动，见EXTERNAL_SIX_HANDOFF最新节。L01–05已源码提交/原写者停止，候选eab62274，聚焦后端39项未运行；Node实际与公共候选验收待C00签收。L05追加三份旧夹具测试精确路径见tasks.json，不能据此改其他文件。
