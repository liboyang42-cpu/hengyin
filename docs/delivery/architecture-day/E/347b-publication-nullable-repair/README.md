# 347b 邀约 nullable / 发布 guard / 直接真库 caller 修补（SOURCE）

Parent: 347bcd0226c3bed71616e38ea7c1391fc76efe5c。
11 个 owned 代码文件；未修改共享 helper、Controller、任何 SQL、资金/运行消费代码。
所有原测试方法/场景保留；不新增 skip/disable，不伪造 provider、付款或券归属事实。

## 生产首因
CoopInviteServiceImpl.attachSupplyStationNames 的嵌套三元将 null 拆箱：外层 false 是 primitive boolean。
改为 Boolean.TRUE/Boolean.FALSE 明确对象分支，保留不适用=false、已选原站=true、旧未确认=null。
原 exact station/topic/merchant tuple 批量名称查询、空值/名称和零写合同原封不动。
原五类 unit 与 CoopDepositRefundMybatisIntegrationTest 的相关原断言由此贯通，不为了 NPE 改期望。
CoopSupplyStationContractTest 原同一回归补显式 CLUB/NODE=false、缺原站=null/已选=true；仍验证无第一站 fallback、无写入。

## 发布测试前提（7 类）
- CmsTopicPublishNarrativeNpcGuardTest：同一章 mapper 注入 facade/collector，当前 topic/chapter/node/template 读连接到各例原 rows；正式 write 上下文与恢复；原 NPC scope/消息/零写断言不变。
- CmsTopicPublishStateKeyGuardTest：保原真实 current-declaration fixture、异常类型/注册/纯读断言；无模板引用的成功场景也提供明确可运行节点；章节 beat 的现成节点属于原章节。
- CmsTopicRoutePublishPolicyTest：合法作者身份、write 上下文；pro 元数据 mock 仍是原测试端口，真实路线校验/编译不能被代替。不可达 edge 原消息保留并精确验证其 path/from/to cause；LINEAR 保持没有作者 branch graph，同时正式快照不再 stub 为 null，核完整 runtimeSnapshotVersion/routeMode/原 persistence 调用。
- CmsTopicSysVarPublishGateTest / TopicPublishJourneyStoryGateTest：同一当前 mapper rows 与声明类型/topic/scope；原 wrong-type/foreign node/NPC/章节拒绝全部保留。无引用的合法内容仍有真实可冻结节点，不把缺依赖当引用拒绝。
- TopicPublicationDeclarationsTest：只为原 one 场景 fixture 显式填写 currentScene=one；原 read-only/locked captured projection/不修改输入/纯读/禁普通 RR 回读等断言不变，生产不自动选场景。
- TopicPublicationDiagnosticsTest：补正式 key/runtime/current reads，真实 TopicRouteRuntimeServiceImpl spy 调原 compiler，未 stub freeze 成功。保同类型/path/node/template/cause/DB 异常身份与无写断言；新增三参 freeze 不得调用的检查。合法组合从完整冻结快照投影原 route 字段，逐字核原规范化 JSON/顺序，并核完整快照与实际三参调用；summer 场景显式 currentScene。
线程标志仅模拟 unit 调用上下文，AfterEach 恢复原 active/readOnly；不是实际 Spring propagation/MySQL RR/回滚证明。

## 真库直接 caller 两项增量
- TopicContentCopyMySqlFixture.installCouponOwnership：AopTestUtils 泛型内联会使 ReflectionTestUtils.setField 重载推断 Class，实际 CmsMemberTemplateServiceImpl 被强制转 Class。改为明确 CmsMemberTemplateServiceImpl 局部 owner 后调用原 setField；没有手工 cast/吞错。TopicContentCopyMySqlIT 原28方法（含3券归属/lineage/graph回滚例）不改。
- CoopSupplyStationMySqlIT.scratch：job121252564215 原 inner cause 为 SQLSyntaxErrorException near '' at line 1；实际 merchant-it-schema.sql 是一条无末尾分号的完整 CREATE，Spring fallback 误按换行分割。只对该资源改用现有 com.chengyinhub.business.it.ItSchema.apply，仍在已核身份的独占 scratch schema。其它两脚本、所有列/FK、7原业务正文和清理不改。没有修改 schema 文件，没有执行 DDL。
CoopDepositRefundMybatisIntegrationTest 原36方法不改，依赖生产 nullable 修复；旧的36/28/初始化1及CLEAN记录不是本候选验证。

## 两个共享接缝由 root 合成
1. D 独占 TopicPublicationTestWiring.java，必须沿原 wire/set/get 为 facade/content/publication 注入真实 runtime/compiler、声明服务和 current-read collaborators。此包不复制或改 helper；5个 facade unit 仍用现成 TopicPublicationTestFixtures/TopicGraphTestWiring，诊断测试直接装真实 production owner。
2. ApiCoopController.fillTopicFacts:1607 同一 nullable 三元真实缺陷，精确符号和 Boolean 对象分支建议已向 root 报告。自动审批拒绝上传含该 Controller diff 的文档补丁，理由是生产改动权保留 root；没有重试或变通传输该 diff，供体不含该文件。必须保后续真实 topic 分类（classic=true/nonclassic=false/unverifiable=null），不要把旧缺站默认 false。该修复与 D helper 未组合时，不能宣称本包独立覆盖全部失败。
root 唯一合成，不将旧整文件覆盖其他 owner 新源。

## 身份与限制
source-manifest.json：11代码前像与公共347b blob匹配，逐后像 SHA1/SHA256，并列6个未改 owned 测试源码身份。
owned.patch.b64 解码仅11代码文件，SHA256 0313aeb6998c6b75630a6119c18ae82a173b7cf3b1cc17c13b1027045dc196d5；文档不在此代码patch hash内；未传输 Controller review。
只完成 source、git diff/apply --check 和身份哈希；有限补丁敏感签名检查未命中，环境无 gitleaks，不冒充完整扫描。
测试、解析、编译、Java、DB、HTTP业务调用、故障注入、CI全部 NOT_RUN。没有重跑原client/Clm8；没有把旧编译或CLEAN当本包通过证据。
