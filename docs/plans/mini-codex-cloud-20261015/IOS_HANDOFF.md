## 当前605：完整fresh SQL通过，POST_DEPLOY／旧JAR仍未运行

[PR392固定6054b634785fc8af8c2deba44d40e3aab96ae4c2](https://github.com/liboyang42-cpu/hengyin/commit/6054b634785fc8af8c2deba44d40e3aab96ae4c2)，直接父033e626d1cc505cab65af1ab60076482e31b4cb4。C00已读公开源码并核SQL SHA256为 `0834bcafb1c9a2f169377445d463cc34a6dce7fa2e96e6b6f81d09ab65254ead`：真人另行批准了原候选1df69556的单行NULL修正；原作者回执已消费一次授权并恢复正常保护。四路径源码交付冻结、原owner锁保留；既有测试计数11→14不等于14项已经执行。

验证协调者实际报告：完整fresh485台账／205pre／378表，基线seed＋migration问题0、6／6seed负控通过；每次run_file强制schema_only=False，DATA_ASSERT观察0且未抑制，builder0／client0／无OOM。新库独立读回仅finance verify／operator payout，相反两项0；旧库双持异常完整保留不删。精确自有client已确认清理，212守卫／预算、global及他库counts保持。原始文件为 `evidence/fresh605-{result.json,builder.log,client-run.json}` 和 `evidence/role-two-way605-readback.tsv`，实际报告由验证者提供；C00未运行SQL／Java或审阅这些原始数据库工件。

**仅当前605 SQL通过**，不继承21的HTTP／JAR／18delta成绩。原21快照／4报告／首次role-both原文件保留，新旧读回分文件。3项POST_DEPLOY需候选bean＋health仍NOT_RUN，Java实际0，旧JAR实际运行仍NOT_RUN；数据库restore PASS不能代替旧JAR。#397三路径已由验证者核size／hash并纳入605，未merge397或master；无生产／部署。精确分层见 `ROLE_PAGE_GRANT_ASSERT15_TRIAGE_21aaa153.json.runtime_rehearsal_current.fresh605`，此前837工程闭环不变。

## 先前限定批准与待交付记录（历史）

## 当前独立迁移演练：限定修正已批准，由PR392原作者独占执行

固定 `21aaa1536cec8a9baa0f7b0494c2597bf5e09243` 的新／旧隔离库读回确认：20261013补页迁移产生了finance payout与operator verify的交叉授权，破坏20260816既有互斥。旧operator payout与finance verify本身是合法授权，必须保留；原statement15 DATA_ASSERT红证据保留，不能以结构执行0 SQL错误判整体通过。

真人已批准一次受控修正：仅该受保护SQL的SHA256 `1f147a2136e999b6fd87a7e0a865d8fde5b0c332170d561b28e415b9fccaf56e` → `1df695567871dc154a03cddd2ebe9495ddb60346dc2279db44a17558fe6504af`，以及必要专属回归／既有计数和隔离测试。由[PR392](https://github.com/liboyang42-cpu/hengyin/pull/392)现有源码作者独占，已实际接收批准；C00和验证协调者不另写源码，不新派作者。保合法旧grant，旧异常只fail-closed不删除；提交后恢复保护。精确四路径／批准摘要／测试交接见 `ROLE_PAGE_GRANT_ASSERT15_TRIAGE_21aaa153.json`。尚未取得本修复完整frozen SHA，回归待实际新源，未签通过。

验证方另已报告：同一本任务库18条严格delta从366至378表通过，完整备份后恢复366表／9member，全量data SHA256一致，global和他库counts不变。这是数据库快照恢复，不是旧JAR回滚验收；Java2560准入仍拒／实际0。无生产、合并、部署；原837b0255工程闭环及分源证据保持原样，以下663收口为此前独立候选记录。

## 当前工程收口：PR394固定663729，授权CI通过，未合并／未部署

固定源码 `663729b8623fa63c2f1c2a0812ea2a287d70c4fc` 的代码整合与既有授权CI工程闭环，**SOURCE_READY；TEST_DEPLOYED未执行**。[PR394](https://github.com/liboyang42-cpu/hengyin/pull/394) 仍为未合并草稿，原来源／三队和唯一修复槽冻结。C00直接核到 [CI39439276632 attempt2](https://github.com/liboyang42-cpu/hengyin/actions/runs/39439276632)、[Funds39439278301](https://github.com/liboyang42-cpu/hengyin/actions/runs/39439278301)、[Followup39439276885](https://github.com/liboyang42-cpu/hengyin/actions/runs/39439276885)、[JAR39439466174](https://github.com/liboyang42-cpu/hengyin/actions/runs/39439466174) 均SUCCESS。精确报告与工件由root独立核验；C00未执行编译／测试／触发CI。

| 固定663的选择／门禁 | 最终报告（各轮独立，不相加去重） |
| --- | --- |
| Backend普通 | 12696报告／12068实际执行／628跳过／0失败／0错误 |
| 既有MyBatis门禁 | 44类／293实际／0失败／错误／跳过 |
| Funds | 62类／657实际／0失败／错误／跳过；依完成job逐类日志，无artifact |
| Followup | 5类／39实际／0失败／错误／跳过；5份原XML逐类核 |
| 小程序 | 11100报告／11095通过／0失败／2跳过／3TODO |
| 资源门禁 | 2084路径／19合法异步／0跨分包 |
| Admin build／E2E、Fast、Secret、两迁移门禁 | SUCCESS；SBOM按原规则SKIPPED |

首次checkout卡5分钟自动取消保留为准备阶段历史，Java／Mini未启动，不是源码或业务失败；root仅重试Detect job及下游，未改CI／超时／规则。attempt2实际checkout `b206e768549ca0367e8e90743040e1984678e180` 与head663的tree均为 `987a5ca2c30a67ed05d2d01a113e5b78e2097a16`，C00也已只读核等，不把其他master变化混入结论。

同头JAR工件12271659352，117760734 bytes，JAR SHA256 `9a5c6b60f953ca0526904746dcf739c8be63a1fd9bd26776d6144e5507d9d5ca`，root已核CRC／SOURCE_SHA／声明hash。与a89 JAR的614个ZIP载荷项中613项代码／资源／依赖hash相同，唯一差别为git.properties中的git.commit.id；第一次把metadata也要求全等导致的审计assert保留，属于比较条件修正，不是构建／业务失败。C00仅核工件manifest，未下载／部署JAR。

**a89实跑不改源**：40类／40XML／169选择用例、0失败／错误／跳过及HTTP五链／13独立读回全ok仍归 `a89a74c4077f0ee12369e5199446619b40f79695`；HTTP工件12271962488的problems=[]／harnessdrift=[]、精确MySQL／Redis teardown=true已由root最终核清。a89→663除一个普通test外全源等值与JAR载荷证明独立保留，不冒充663重跑真库／HTTP。Extra74subset、core含4普通outer负控、券clawback一类4方法类级LEFTOVER／4scratch且精确容器removed=true均不抹掉；普通628skip也不冒充真库skip0。

十四条迁移静态清单绑定663并通过；a89一次性HTTP建库schemaPASS／迁移阶段问题0，只是临时测试schema和静态门禁，**不是持久目标／生产迁移演练**。真实角色设备验收、真实微信／外部渠道（支付和内容安全出站stub）、持久目标迁移与旧JAR回滚仍独立未验；未合master、未部署、不执行生产迁移。旧95f／f8／6aa／a89失败、checkout取消及清理标记按原源保留。当前源码任务闭环，无新增作者／代码／测试／CI配置。唯一最终索引：`LONGRUN_REMAINING.json.independent_backend_acceptance.engineering_closure`。

## 先前663单测试交付与当时待验记录（历史）

[PR394](https://github.com/liboyang42-cpu/hengyin/pull/394) 已固定 `663729b8623fa63c2f1c2a0812ea2a287d70c4fc`，直接父 `a89a74c4077f0ee12369e5199446619b40f79695`。C00直接核读公开diff：仅CommunityPostAuditLevelTest一个文件，3行新增／2行删除，只含SIGNED_IMAGE现有URL常量／stub返回／断言第三参；17／7／openid／一次调用／真实canonical parser及其他11方法保持。状态 **SOURCE_DELIVERED_FROZEN／WAIT_CI**，唯一修复槽冻结，无新增作者。

**真库／存储实跑仍只归a89**：九批全部SUCCESS，C00核到九个工作流源SHA一致且完成成功；root独立核40类工件／40份原始XML／169个选择用例、0失败／错误／跳过、逐类Maven退出0及所有精确teardown removed=true。批次数为core27／ai22／growth12／rr4／coupon9／community14／B05 3／extra74／maxdate4。Extra为19类74项subset=true，已排掉与CORE重复E04_13的2项，不能改称76或BUG数；core27含4项普通outer负控，不能说169项全部是实库业务。

券clawback保留清理粒度：RedemptionRewardClawbackMyBatisTest一类4方法的类级receipt仍有LEFTOVER／4 scratch标记，精确容器removed=true；不是四份逐testcase清理回执，也不抹掉LEFTOVER。a89 HTTP39437293832工作流SUCCESS，最终13读回工件本次仍待root最后核。a89 jar39437646837已由root下载核CRC／SOURCE_SHA／SHA256 `c586172facac9ad3338d4006a7f393b9f4b55cd9fbb02aebfb3c9af793072ecd`，C00仅核工作流元数据，未部署。

a89→663只有该普通test差异；排除该文件的全部git tree等值及指纹 `f95ad20084aac5e8a90b052b56242dc7c9a5cd779e4657ccc57ce85a8bd3c39d` 由root核验，生产／SQL／依赖／40IT／HTTP源码相同。此为源码等值证据，**不把a89真库／HTTP改写成663实跑**。root只让唯一失败普通类由自然CI复验，不手动重跑40个已通过类。663现有CI39439276632、Funds39439278301、selected39439276885及jar39439466174在同头运行，最终结果／同头jar待签收；a89唯一1F及全部旧失败保留。

本次集中登记一次，等root收最终663结果及剩余工件后再一条收口，不为pending反复改文档。原来源／三队保持冻结，Cloud不测试／编译／触发CI；未合master、部署、执行迁移或新增入口。真实微信／设备、支付与内容安全出站stub、持久迁移和生产未验边界保留。精确证据见 `LONGRUN_REMAINING.json.independent_backend_acceptance` 的 `latest_frozen_repair`、`a89_real_storage_evidence`、`a89_to_663_unchanged_source_proof`。

## 已实施单测试夹具授权历史：私有媒体出站签名URL

固定 `a89a74c4077f0ee12369e5199446619b40f79695` 完整Backend报告12696项／1失败／0错误／628跳过，仅CommunityPostAuditLevelTest的directPublishWithImages用例仍期待原IMAGE key；其余原失败报告已闭环。不是OSS凭据问题：C00只读核到publish先mediaForRead并真实校验canonical key，再把临时签名URL放入mediaUrls供微信异步审核。既有CommunityPrivateMediaContractTest／CommunityAuditMediaPreviewTest源码也保签名投影、存储key不变及签名失败无长URL降级合同；未执行这些回归。

仅授权本地root同一修复槽修改 `chengyinhub-admin/src/test/java/com/chengyinhub/business/community/CommunityPostAuditLevelTest.java`：新增沿现有stub URL的SIGNED_IMAGE常量，签名stub返回该常量，唯一异步提交断言第三参IMAGE→SIGNED_IMAGE。类型17／帖文7／openid-9／精确一次调用不变，不改anyString或verify计数；canonical key解析仍thenCallRealMethod，其他11方法／所有其他业务断言保持。生产、权限／媒体政策、测试门禁、凭据／网络均不动。精确合同与授权见 `LONGRUN_REMAINING.json.independent_backend_acceptance.signed_image_contract_review` 及 `signed_image_assertion_repair_authority`。

同a89的HTTP工作流已SUCCESS且五链报告通过，Funds检查亦SUCCESS；首波多个真库batch成功、余项继续由root跟进，未提供本轮完整batch回执，不签整体完成。C00只读当前元数据，不从旧源继承，也不把未来metadata／test-only SHA称为全部业务复跑。下一单测试修复尚无公开SHA，其余源码／三队保持冻结，无新增writer或Cloud测试；整体最终验收待剩余结果集中签收。

## a89共同修复交付与当时WAIT_CI历史

[PR394](https://github.com/liboyang42-cpu/hengyin/pull/394) 已一次push固定 `a89a74c4077f0ee12369e5199446619b40f79695`，直接父提交 `6aa8c2c676c48ab1203c7156c85783ef4b57783a`。C00直接核读公开diff：精确七授权路径，52行新增／43行删除。新主包工具与6aa分包旧实现内容完全相同；旧路径只留单行forward，三caller七处引用恢复主包utils，单一实现；无app／配置／组件／策略变动。

Community仅publishWith及必要导入隔离出站签名，canonical key解析thenCallRealMethod保真，原12个@Test体不变由验证者报告；ApiTopicPostReview仅两条BLOCK期望500→400，其他字节相同由验证者比对，公开patch无其他差异。无生产Java、权限、SQL、门禁或CI配置修改。

固定a89交付报告：资源门禁2084引用／19合法异步／跨包0／互引0 PASS，71项JS全PASS且0失败／错误／跳过，行动账PASS4755且无diff，旧新require同一对象。C00仅核读源码和既有CI元数据，未执行这些检查；新Java交付时未手动运行，状态 **SOURCE_DELIVERED_FROZEN／WAIT_CI**，不继承旧源成绩。

既有 [CI39437260043](https://github.com/liboyang42-cpu/hengyin/actions/runs/39437260043)、[Funds39437260044](https://github.com/liboyang42-cpu/hengyin/actions/runs/39437260044)、[selected39437260070](https://github.com/liboyang42-cpu/hengyin/actions/runs/39437260070)、[HTTP39437293832](https://github.com/liboyang42-cpu/hengyin/actions/runs/39437293832) 已在同固定源运行，最终结果待root汇总后**一次更新当前索引**。原唯一修复槽冻结停写，无新增writer；Cloud不编译／测试／触发CI，不为每个pending步骤反复更新文档。

旧6aa完整Backend12696／2失败／1错误／628跳过及其余原19项回归通过保留为原源历史，不相加或移给a89；f8／更早通过与失败边界保留。整体最终验收尚未完成，真实微信／设备、支付／内容安全出站stub、持久迁移和生产边界不变；不合master、不部署、不执行迁移、不新增入口。精确来源和七路径见 `LONGRUN_REMAINING.json.independent_backend_acceptance.latest_frozen_repair`。

## 已实施测试授权历史：BLOCK400与出站签名夹具

固定6aa完整Backend报告仍有两类共3个失败／异常方法：ApiTopicPostReviewTest两处新BLOCK用例仍期望500，与恢复后的既有明确拒绝400冲突；CommunityPostAuditLevelTest合法key真实校验已通过，后续图像预览签名需要隔离出站夹具。未提供本轮新完整总数或F／E／跳过拆分，不推算。原其他四类（Journey、C03_21、C03_24、SaleReview）报告PASS。C00直接核到6aa Backend FAIL、Migration safety SUCCESS、Funds SUCCESS；自然CI整体未签收，不迁移执行，也不把单项元数据当新源验收。

本地root同一修复槽仅新增 `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiTopicPostReviewTest.java` 两条代码期望写权：既有124／188行500→400，保BLOCK文案及无写校验，不改其他方法／断言，真实异常仍500。C00已只读核到这两条assert；不新增生产修改。既有CommunityPostAuditLevelTest写权仅在publishWith内沿现有MockedStatic模式stub出站generateCommunityMediaPresignedUrl；合法key的communityMediaObjectKey仍thenCallRealMethod，所有@Test体／业务断言不动，无OSS凭据／联网／安全边界降级，并正常关闭静态夹具作用域。精确边界见 `LONGRUN_REMAINING.json.independent_backend_acceptance.post_review_test_repair_authority`。

包迁移五路径补丁已在root工作区完成但尚未push：实现逐字相同、旧新require同一对象；资源门禁2084引用／19合法异步／跨包0／分包互引0及71项JS全PASS由验证者报告，**仅为未公开SHA补丁观察**，不归给6aa或未来源码，不修改测试／配置或豁免门禁。root获此授权后与当前两处测试夹具一次冻结；不新增writer或Cloud验证，最终新SHA尚待。旧f8／6aa成绩及失败留在各自源码，整体验收仍未完成。

## 已实施包迁移授权历史：主包单实现与旧路径转接

f8 Mini最终失败已由验证者定位为组件／资源路径门禁：三处新caller同步依赖 `pages/square` 分包下工具不合法，17项单测PASS不足以证明包依赖正确。C00只读核到app.json确实将pages/square注册为subpackageSquare；旧工具实现没有require／import依赖。无资源门禁豁免，不把此问题称为单测已绿即完成。

仅追加本地root同一组合分支／唯一修复槽的两条写权：`chengyinhub-xcx/utils/community-report-sheet.js` 承接6aa旧实现逐字不变；`chengyinhub-xcx/pages/square/utils/community-report-sheet.js` 仅留 `module.exports = require('../../../utils/community-report-sheet.js');` 转接。实现只有一份，保square原caller和测试路径；沿原三caller授权只将七处require改回各自主包utils相对路径。不改app.json／分包注册／组件配置／页面交互／权限／测试／门禁，不复制两套实现或增加新行为。该精确迁移取代先前禁止新主包模块的限制，仅为既有调用兼容，不扩域。

6aa已交六文件仍冻结，既有自然CI继续不取消／重跑；下一包依赖修复工作区已完成但尚无公开SHA，不继承旧PASS。root待这项固定后再接续必要HTTP／存储，Cloud不验证、不新增作者。精确路径及逐文件边界见 `LONGRUN_REMAINING.json.independent_backend_acceptance.shared_report_sheet_repair_authority`。

## 先前固定6aa源码交付与当时验收历史

[PR394](https://github.com/liboyang42-cpu/hengyin/pull/394) 最新固定源码 `6aa8c2c676c48ab1203c7156c85783ef4b57783a`，直接父提交 `f8fe6a5ba5d986b51a9dbbfacf26a263667433b1`。C00已只读核对公开diff：精确六文件，23行新增／5行删除；Controller仅两处blocked恢复400，C03_21／24接入既有真实安全夹具，24 rejected仅允许selectCmsTopicById(77L)一次再禁止其余交互，继续零写。Community图片改既有合法upload/32hex.jpg key，不要OSS凭据／不移除安全边界；SaleReview仅补同一既有身份保护链需要的chapter／node／activity成功insert ID。生产身份保护及其他合同不动。

集成写者报告四类26个@Test方法体逐字不变、Journey原5测试未动；公开diff仅涉及setup／常量及已授权rejected helper，不推定26项已运行。provenance只改已授权CmsActivity单hash，6静态合同PASS、6条权限记录逐字一致由集成写者报告。未新增权限／SQL／依赖／门禁／CI配置。

状态 **SOURCE_DELIVERED_FROZEN／WAIT_CI**；交付时新Java未手动执行，既有自然 [CI39435545650](https://github.com/liboyang42-cpu/hengyin/actions/runs/39435545650)、[Funds39435545671](https://github.com/liboyang42-cpu/hengyin/actions/runs/39435545671)、[selected backend39435545738](https://github.com/liboyang42-cpu/hengyin/actions/runs/39435545738) 已启动，当前无最终签收。6aa交付冻结，root同一槽仅按当前共享工具迁移授权续修，无新增作者；Cloud仅读证据和维护控制文档，未编译／测试／触发CI。

旧f8的五HTTP／13读回、Funds、39-selected通过仅归f8；完整Backend失败与Mini最终失败亦保留原源，Mini根因为同步跨分包依赖，见当前最小迁移授权，不能预报通过。新6aa不继承任何旧成绩，整体／真微信／设备／出站stub／持久迁移／生产边界保留。后续新结果集中更新同一当前索引；不合master、不部署、不执行生产迁移、不新增入口。精确交付见 `LONGRUN_REMAINING.json.independent_backend_acceptance.latest_frozen_repair`。

## 先前固定f8验收及修复授权历史：成绩不继承6aa

固定 `f8fe6a5ba5d986b51a9dbbfacf26a263667433b1` 的完整Backend报告12696项，7失败／7错误／628跳过；包含关闭的IT，不能称真库skip0或12696项业务全执行。失败仅5类：JourneyNarrativeSecuritySaveTest(2F)、OWN_C03_21Test(4F)、OWN_C03_24Test(1F4E)、CommunityPostAuditLevelTest(1E)、TopicSaleReviewDecouplingTest(2E)。C00直接核到Backend检查FAIL；精确数量与诊断由集成验证者提供。

本地root同一组合修复槽新增四条精确测试路径，均前缀 `chengyinhub-admin/src/test/java/com/chengyinhub/business/`：`longrun/OWN_C03_21Test.java`、`longrun/OWN_C03_24Test.java`、`community/CommunityPostAuditLevelTest.java`、`service/TopicSaleReviewDecouplingTest.java`。仅复用已有真实内容安全夹具、canonical媒体夹具、成功chapter／node身份生成夹具；保方法数／业务断言。C03_24仅允许既有合同要求的指定主题select一次，并禁止全部写入及其余交互，不能放松写负控。

原已授权 `ApiTopicController.java` 仅恢复两处明确内容拒绝为 `AjaxResult.error(HttpStatus.BAD_REQUEST, textVerdict.getBlockMessage())`，保新TopicTextSafety门禁及操作者／主体隔离；不改Service／其他生产。Journey原测试只读，不改400断言为500。此前来源指纹仅改单hash仍未commit，6静态合同报告通过只属未冻结补丁。原来源／锁保留，无新增Cloud作者、入口或CI配置变更。精确授权见 `LONGRUN_REMAINING.json.independent_backend_acceptance.backend_fixture_repair_authority`。

同固定f8的 [HTTP39433391973](https://github.com/liboyang42-cpu/hengyin/actions/runs/39433391973) 已SUCCESS；工件12268773420由验证者核得五链PASS、problems=[]、harnessdrift=[]。C00核读运行及工件目录，不重跑／重解析summary。[Funds39433312735](https://github.com/liboyang42-cpu/hengyin/actions/runs/39433312735) 已SUCCESS／全PASS报告，未另推测试数。两者仅属于f8，不能继承给下一修复SHA，完整组合仍未验收。支付／内容安全出站stub、真实微信／设备／持久迁移／生产未验边界保持，旧95f失败及其Java实际0／HTTP未跑历史保留。Cloud仅控制文档，不编译／测试／触发CI。

## f8首次修复交付与当时WAIT_CI历史

[PR394](https://github.com/liboyang42-cpu/hengyin/pull/394) 已更新固定源码 `f8fe6a5ba5d986b51a9dbbfacf26a263667433b1`，直接父提交为 `95f038ae57ac38d35d532a07fbd82375c74d887f`。C00直接核读公开diff：仅授权五路径，24行新增／8行删除；七处require复用既有举报工具，E01_12仅改setUp的合法上架主题／10参依赖／真实policy预审配置true和显式HOLD，allowlist仅新增4+2实名理由，冻结72不变。3方法及后续断言逐字未变由集成写者比对报告；公开差异未触及这些方法。未新增工具、生产Java、SQL或CI配置。

集成写者报告固定新源17项JS全PASS／0失败／0跳过、silent门禁PASS；既有行动账重建4755且与95f逐字不变，实际commit未包含行动账差异。迁移静态与14迁移依赖顺序报告PASS；先前一次ryTask缺SQL登记源于稀疏checkout缺根sql目录，补齐只读目录后通过，属于准备范围问题，不是业务失败或新增SQL。以上仅签收该源报告，C00未执行检查，不从旧源继承。

固定f8的既有 [CI39433312461](https://github.com/liboyang42-cpu/hengyin/actions/runs/39433312461)、[Funds39433312735](https://github.com/liboyang42-cpu/hengyin/actions/runs/39433312735) 当时核读IN_PROGRESS／WAIT_CI；现已取得完整Backend失败与同源Funds／HTTP通过，见当前分层，不再以旧pending覆盖结果。root唯一修复槽停写待反馈，原来源／三队冻结，不派其他作者。旧95f的JS11100项（11090通过／5失败／2跳过／3TODO）、构造testCompile失败、Java业务实际0和HTTP五链MISSING／NOT_RUN均保留历史，不移给f8。

最终组合整体尚未验收；f8的Funds／HTTP同源通过见当前段，旧41f83真库及8bc728 HTTP／CI不继承；支付／内容安全出站stub、真实微信／设备、持久迁移与生产边界保持。不合master、不部署、不执行生产迁移、不新增入口或修改212／CI／网络；Cloud仅控制文档，不运行验证。当前精确交付与运行索引见 `LONGRUN_REMAINING.json.independent_backend_acceptance`。

## 先前组合95f源码交付与失败历史：不作为f8验收

[草稿PR394](https://github.com/liboyang42-cpu/hengyin/pull/394) 已公开，固定组合源码 `95f038ae57ac38d35d532a07fbd82375c74d887f`，分支 `codex/final-backend-acceptance-20261020`；base为master但未合并，本轮不合master。来源顺序为 port `b548cdc4d098defb5c4857c793897ca79668d579` → #391 `8bc728ad99d0c748698c5210343e6f4ec782e894` → #385 `b19200a3ad6fc19319665fe5b81ad6161fd9dac7` → #388 `f4d75f59fa044e634f5c06fbe6abb6bfcd37ab8a` → #390 `9e8c870c7b748b3f25e3ca3bbb970609bc9a137a` → #387 `0f8d1796983b3a0300462e818efba3f02aa6d2d3`。#388／#390预演及合并均报告无冲突，无新增路径授权。

集成写者已按控制提交 `1e62861bbf8317c10d607c2abf74150c29350c32` 解决五处已授权冲突：Controller保留文本安全门禁及操作者／主体隔离，Service兼保省略配置／节点关系与开售条款前后比较／刷新；行动账由既有生成器按最终WXML重建4755，GET矩阵77项全保，29项GET负控报告通过。以上为源码交付及集成写者观察，C00未执行生成器或测试；不改UI、鉴权策略或CI配置，不据此签收组合后端／真库／HTTP验收。

固定95f038的既有 [GitHub CI39431906431](https://github.com/liboyang42-cpu/hengyin/actions/runs/39431906431)、[Funds39431906479](https://github.com/liboyang42-cpu/hengyin/actions/runs/39431906479) 先前核读为IN_PROGRESS，现已观察到Fast／Migration safety失败；组合HTTP39432014261也已FAIL，根因待精确日志。组合未验收，不将旧pending视为当前结果；真库最终证据仍待签收。41f83九批真库与8bc728五链HTTP及其单源CI均不继承到此组合。原来源和三队源码保持冻结，本地root为唯一集成写者；Cloud仅登记文档，不新增写者、不编译测试或触发CI。

用户已授权独立整合与现有CI验收；不部署、不执行生产迁移、不新增入口，不修改212／CI／网络。真实微信／设备、出站stub边界、持久迁移及生产未验边界保持。精确来源、冲突及当前证据见 `LONGRUN_REMAINING.json.independent_backend_acceptance`。#391外部已合master的既成事实保留，不构成本轮合master授权。

## 当前HTTP闭环：固定8bc728五链PASS，出站stub与最终验收边界保留

[既有运行39427671750](https://github.com/liboyang42-cpu/hengyin/actions/runs/39427671750) 在固定源码 `8bc728ad99d0c748698c5210343e6f4ec782e894` 已完成SUCCESS。工件 `12265809908` summary已独立核验 **verdict=PASS、problems=[]**；platform、merchant_club、sale_close、player、finance **五链全PASS**，每条terminal_readbacks全部ok。玩家订单唯一、回调交易号一致、四权益各核销一次、4条ACTIVE redemption及唯一勋章；FINAL资金快照唯一、batchPAID、确认付款身份分离。本次仅签收已有报告，没有新测试或CI执行。

**NOT_COVERED保持**：支付、内容安全出站为stub；真实微信／设备／最终审核跨分支候选／持久迁移／生产仍未验。九批真实存储只属于 `41f83e65797edd5c17511ea609659f9a55de8767`，与此8bc728 HTTP证据分源，不相加、不继承。原a9售票时间投影失败、798报名等待顺序失败及finance NOT_RUN均保留为各自SHA的历史。

PR391当前由外部合入master；固定8bc728的 Backend/Funds/Mini static 三项检查现均COMPLETED/SUCCESS，此前OPEN及pending为历史读数，不推定最终组合通过。[PR390](https://github.com/liboyang42-cpu/hengyin/pull/390) `9e8c870c7b748b3f25e3ca3bbb970609bc9a137a`保持源码已交、最终验收未完。未新增该修复的本地源码写权或Cloud团队，原外部来源与归属保留；本地独立整合仅按本节精确范围执行。

## 当前验证更新：九批真实存储通过，仅限固定41f83候选

既有 GitHub Real Storage IT 的九批结果均为 **PASS**，固定源码 `41f83e65797edd5c17511ea609659f9a55de8767`。逐批 summary/receipts 已独立核读；本次直接核读运行元数据、工件目录及后续源码/CI边界，没有重新编译、测试或触发CI。完整当前记录见 [LONGRUN_REMAINING.current_real_storage_evidence](LONGRUN_REMAINING.json)。

| 既有运行 | 该批实际项数 | 结果 |
|---|---:|---|
| [port-core-funds](https://github.com/liboyang42-cpu/hengyin/actions/runs/39411121962) | 27 | PASS |
| [port-ai-quota](https://github.com/liboyang42-cpu/hengyin/actions/runs/39411125954) | 22 | PASS |
| [port-f3-growth](https://github.com/liboyang42-cpu/hengyin/actions/runs/39411129899) | 12 | PASS |
| [port-f3-evidence](https://github.com/liboyang42-cpu/hengyin/actions/runs/39411134054) | 4 | PASS |
| [port-e04-07-coupon](https://github.com/liboyang42-cpu/hengyin/actions/runs/39411138417) | 9 | PASS |
| [port-community-e02](https://github.com/liboyang42-cpu/hengyin/actions/runs/39411144116) | 14 | PASS |
| [port-b05-visibility](https://github.com/liboyang42-cpu/hengyin/actions/runs/39411148566) | 3 | PASS |
| [port-extra-storage](https://github.com/liboyang42-cpu/hengyin/actions/runs/39411153383) | 76 | PASS |
| [funnel-max-date](https://github.com/liboyang42-cpu/hengyin/actions/runs/39411158792) | 4 | PASS |

不将九批相加为去重总数：E04_13Test跨批重复。旧注册source匹配false不等于未执行，真实执行候选为41f83。券冲正批次中RedemptionRewardClawbackMyBatisTest一类(4实际PASS)的类级cleanup=LEFTOVER；精确MySQL容器removed=true。未推定四条testcase各有cleanup回执。另外，既有41个class工件中的41份原始Surefire XML已逐份与各自receipt tests核齐、F/E/S均0；没有新执行，也不计作41个去重类。原NOT_RUN、无入口、编译实际0及取证疑点只保留为原SHA/当时条件历史；当前不得据此说上述类从未实跑或该既有入口不存在。

后续 `41e04e061a66f7a028467de431d3bada1c4dcd6e` Backend/Funds检查SUCCESS，PR386已合到移植分支 `b548cdc4d098defb5c4857c793897ca79668d579`；期间有NpcChatService生产变更，**九批成绩不继承到后续源码**。当前HTTP运行39427671750在8bc728五链PASS，原39423292504@a9及39427075967@798失败保留历史；不新增重复源码线。真实微信/支付/设备、持久迁移及最终组合验收仍未完成。

本组三队现有业务源码和文件归属保持冻结。PR385/387/388/389/390/391不属本组写权，不抢改；本次仅更新现有全局账和跨端索引，没有入口/212/CI/网络修改、生产迁移执行、合并或部署。

## 先前记录（保留原候选与当时入口条件，以本节当前证据为准）

## 2026-10-18 两项既有合同已确认：NO_SOURCE_CHANGE

两项真人选择已由本地协调者明确转交、C00实际签收并更新原剩余表及owner记录：[决定与签收](CORE_COMPATIBILITY_DECISIONS_20261018.json)。

- OWN-C04-01：保留同玩家／同NPC／同请求号重放第一次结果，新问题换新请求号；原scope检查与既有terminalReplay回归合同不变。不加内容指纹、列或迁移，不推测旧回执身份。收口 **NO_SOURCE_CHANGE**，不计新增修复BUG。
- OWN-C05-22：保留照片AI异常退还照片池次数，沿既有照片合同；不改为通用网关UNKNOWN占用，不改降级／放行／奖励。收口 **NO_SOURCE_CHANGE**，不新增政策源码、迁移或付费调用。

CORE这两项待真人裁决数现为 **0**；历史BLOCKED提案保留，原owner与文件锁不变，不唤起作者重扫或测试、不复跑旧PASS。当前已确认可行动未交源码仍0，不造任务。四夹具源码已交；B05仅test-compile PASS／实际0，E02空间自然恢复后已沿原212完成test-compile PASS／实际0，原ENOSPC保留历史；各3项真MySQL仍NOT_RUN，现有入口不新增，验证状态未受本决定改变。下方旧“待真人选择”只为历史，已由本段覆盖。

## 2026-10-18 四文件源码完成与本地验证分层签收

四文件 grant、逐作者 ACK 与源码交付均已完成，原作者停写、独占锁保留。C00已核读 PR24 固定证据 [`8fdb9fdb72975f7617a4ac70eb4e4eeacd27c3ac`](https://github.com/liboyang42-cpu/hengyin/commit/8fdb9fdb72975f7617a4ac70eb4e4eeacd27c3ac) 的两份 README/summary；E02后续恢复编译的新证据为 [`90b81ee7af715f0c72598a7b7a9f59e235910ea6`](https://github.com/liboyang42-cpu/hengyin/commit/90b81ee7af715f0c72598a7b7a9f59e235910ea6)，C00亦已核读其README/summary。以下为当前结果，后文较早“均未编译/待 ACK”仅为历史快照。

- CORE/B05 源码 `2ac55d20a9f4932be8d411e53191c69e5141772c`：现有212 `test-compile` **PASS**，guard0/completed/BUILD SUCCESS，指定 fresh class1；无 Surefire XML，实际测试 **0**，原3项真实MySQL仍 **NOT_RUN**。见[B05编译证据签收](B05_2AC55_TEST_COMPILE_RESULT.json)。
- EXTRA/E02 源码 `6c44636974220ca9876386ee638c99981488db42`：4328文件再次逐hash一致，资源自然恢复后沿原212于02:23:12.350784Z—02:25:47.051161Z完成 **test-compile PASS**，guard0/completed/BUILD SUCCESS，三个指定fresh class及hash已核；无Surefire XML，实际测试 **0**，真MySQL3项仍 **NOT_RUN**。精确自有容器已确认清理，当前无活动Java。见[E02恢复编译签收](E02_6C446_TEST_COMPILE_RESULT.json)。原8fdb ENOSPC准备失败、原summary及远端preparation-result-disk-full-020540Z.json完整保留在[历史准备回执](E02_6C446_PREPARATION_BLOCKED_RESULT.json)，不再作为当前磁盘阻塞。

原空间阻塞已自然恢复并完成未完编译；无需源码修复槽、不再重试或Cloud替代，不新增入口/网络/CI。两个候选仍为同父26d的独立分支，未组合成四文件集成候选。原失败历史保留；无真实MySQL/Redis、HTTP、设备业务、迁移、合并或部署验收。两项CORE业务选择均已由真人确认保留现状，按CORE_COMPATIBILITY_DECISIONS_20261018.json收口NO_SOURCE_CHANGE，无待裁决源码。无需EXTRA为本回执重复改本队文档。完整索引见[四文件批次](FOUR_FIXTURE_SOURCE_BATCH.json)。

## 先前记录（按原SHA保留历史，不覆盖上述当前状态）

## CORE5752单类31项通过／原作者签收并释放修复槽

E02协调者已将原child f74的完全相同3个补丁blob串行交到现有PR36：完整rolling源`6c44636974220ca9876386ee638c99981488db42`，父`26d8245b458d18497eb9040a4c7d9f1509b41bb1`。C00已实际读完整commit并逐blob SHA核与原child相同；没有新增源码或测试结果。CORE/B05 `2ac55d20...`是同父的另一单文件候选，未静默合成四文件分支，仍各STATIC_ONLY/实际0。下文f74保作原作者child交付来源，以本行rolling完整SHA作为EXTRA完整候选。

四个旧夹具均已在公共grant fa3f及逐作者ACK后源码交付：[批次](FOUR_FIXTURE_SOURCE_BATCH.json)。原E02三文件`f74dd9be0c17a17db506ee8e0c46c60804a1a785`（父`9a27f6b0d8fe532320cbe646775464cf49ba1d10`，PR30）；CORE/B05单文件`2ac55d20a9f4932be8d411e53191c69e5141772c`（父`26d8245b458d18497eb9040a4c7d9f1509b41bb1`，独占branch）。总共4文件各仅+1空表行/-0，逐文件移除新增行即恢复原文；原gate/6方法/全部断言和created清理不变。原E02已明确B05冻结交接，双方完成停写/锁保留。均STATIC_ONLY、实际测试0/未编译/真库未验，不把6143普通4PASS或b034普通8PASS套用。无新生产BUG/新入口/合并部署。

CORE/B05已按fa3f公共grant明确ACK后交付：`2ac55d20a9f4932be8d411e53191c69e5141772c`，父`26d8245b458d18497eb9040a4c7d9f1509b41bb1`；[单文件交付包](CORE_B05_FIXTURE_SOURCE_DELIVERY.json)。仅新增一行既有cms_activity空表，去掉该行逐字恢复原文；3原方法/gate/全部断言/created清理不变。STATIC_ONLY、实际0/未编译/真库NOT_RUN；不借普通4PASS代替真库。新接收者完成停写、锁保留，未改EXTRA集成分支。root已私有转发原E02三文件grant，不等CORE验证；等待实际post-grant作者回执，不公开代发内部协调。

已实际核读[近期PR24证据索引](RECENT_EXTERNAL_EVIDENCE_ACK.json)：923170ab原六类35=28PASS/7ERROR（H2别名夹具）保留为失败历史，b034a4aa仅两原异常类8定向PASS闭环且原E02修复槽已释放；530e7228四类60PASS，保原59声明/计数审计及不重跑记录；6143c37f单类4PASS仅H2生产XML。6c6f82d7仅一行建表前提STATIC_ONLY、实际0/未编译/真库未跑。旧E02声明18漏6CsvSource已更正24，未改源码凑数，不能把8/60/4跨SHA累加为一次通过。普通PASS不代表真实MySQL/Redis/HTTP、设备业务或部署；未迁移合并。四个旧fixture新grant仍只补既有表前提，不是四个生产BUG。

四个旧真库夹具新增读取前提已[精确登记](FOUR_FIXTURE_MINIMAL_GRANT.json)：E02_01Test/E02_11Test/E02_06Test仍原EXTRA/E02；B05CommunityVisibilityMySqlIT实际测试创建者也为EXTRA/E02，现按用户要求正式转CORE/B05唯一接收者core_b05_fixture_sol（明确新接收，不冒称原作者在线）。45开放PR全分页仅PR30/36同作者及集成触达，无第二writer。仅补同一行cms_activity空表，原created自有scratch清理已正确，保gate/6原方法及断言；公共SHA逐作者ACK前不写。Cloud不编译测试/CI/VPS，不改生产/入口，已有6c6不重改。内部协调不写公开PR评论；非敏感权限表按要求保存公共GitHub。

本次三项真人决定已全部形成公共源码：A `dedb44d54a4a277273943c0cbbca9f6e7528482a` → E02 `13be997646c88b9eea282cc8c8225b42820dc3b3` → B `923170abd8e468d62eb8404b16b990609a4da42a`，完整父链/精确路径/本地文本命令见[源码批次](BUSINESS_DECISIONS_SOURCE_BATCH.json)、[E02](E02_23_SOURCE_DELIVERY.json)、[B](E04_07_B_SOURCE_DELIVERY.json)。原两作者已按225b grant真实ACK并完成停写、保留独占锁，当前源码writer0，非仍等三项决定。E02真实行为变化：无可信旧帖映射收藏/取消返回原错误封装，收藏筛选/状态同一受保护来源谓词；无新URL，但不能标无行为变化。B原发券boolean外部签名不变，仅精确回执与审计内部合同，旧未知只审计不动券、其他冲正继续。静态逐源码声明A3、E02四类18、B两类11；B新5+旧3真库8均NOT_RUN/现有入口覆盖缺口，不能累加不同SHA为一个已跑矩阵。B唯一新增迁移仅审查，旧行NULL不回填，先加列才可未来部署B；当前无执行/集成合并部署，TEST_DEPLOYED=NO。

E04-A已真实源码交付：[dedb44d54a4a277273943c0cbbca9f6e7528482a](https://github.com/liboyang42-cpu/hengyin/commit/dedb44d54a4a277273943c0cbbca9f6e7528482a)，直接父a76268d5，仅consumer未知券分支+新3方法普通回归+本包说明。无新列/迁移依赖，原券/log/重开标记不动，原积分/经验冲正及audit继续；新执行NOT_RUN，已交本地现有212定向3声明，不继承旧PASS。原E04已ACK A公共SHA并接续B，当前已证实1源码writer；E02真人决定/scope已ACK，但补充grant后的写入回执仍待收，不虚报2运行。详见[E04-07-A交付包](E04_07_A_SOURCE_DELIVERY.json)。

原EXTRA两作者已实际接收真人决定并给出精确scope提案（[真实回执](https://github.com/liboyang42-cpu/hengyin/pull/16#issuecomment-6215854647)）；这不是新grant写入ACK，未报源码writer数。补充3个View转发路径和2旧fixture已核212开放PR零交集并精确转给原E02。静态核得LEGACY_MIGRATED安全标签不在作者允许值内、create/edit均拒绝，只有历史迁移设置；仅“旧行+保留ID/作者+exact迁移requestId+JSON受保护标签”共同成立才可保留历史关联，任一不成立按已选止错拒绝，不能单凭字符串/同号/作者。没有部署数据证据，真库仍未验。新增迁移源码统一采用作者请求的`chengyinhub-admin/sql/migration_topic_completion_coupon_history_receipt_20261016.sql`，替代此前未写root路径，只准一份、不执行；原E04真库IT亦仅门控源码NOT_RUN。

## 2026-10-16 真人身份与通关券决定已落账（尚无新源码/验收）

E02-23：无可信legacy→community映射的旧帖明确拒绝收藏/取消收藏；列表、收藏筛选及状态不再按数字同号或客户端legacy-square字串关联。没有证明的映射不恢复收藏，不建立新桥或切发布源。

E04-07：原券身份未知时仅审计、券不动，其他已授权冲正继续；USED保持只审计，不猜时间窗/MAX/替代券。允许未来精确history_id回执的最小生产/回归/新增迁移源码供审查，旧行保持NULL不回填；迁移不执行、无合并部署。分A止错与B未来回执，同一原E04作者串行。B依赖迁移在未来另行部署后才可运行，不称已上线。

[精确独占grant](BUSINESS_DECISIONS_IMPLEMENTATION_GRANT.json)已保留原EXTRA/E02、E04；新旧Controller/View、producer/receipt Mapper与指定回归分别列明。212个开放PR全分页已核；活跃PR231的社区接口明确排除，UI/iOS等不扩权。原协调者已实时表示接收决定并等待精确grant；child实际接收待ACK，暂不声称两线已运行。源码一push一完整SHA交本地root，Cloud不执行验证；现有入口不新增，未覆盖真库仍NOT_RUN。


C00已核读[PR24公开README及summary](https://github.com/liboyang42-cpu/hengyin/blob/d31786327b5e9c1be346eb911a386b28e31b3da5/docs/verification/mini-bug-arch-20261015/VPS_CORE_5752ee6f/README.md)，证据`d31786327b5e9c1be346eb911a386b28e31b3da5`：固定5752ee6fd302dab9e610f65cb8f3dc5b2b66201d的TopicUpdateByOwnerTest **31实际PASS／0失败错误跳过**，fresh精确1XML/31testcase、212guard0/completed、源包/日志/XML哈希与精确容器清理入[C03_5752_TARGETED_VPS_RESULT](C03_5752_TARGETED_VPS_RESULT.json)。原CORE/C03作者已实际只读签收ACK，当前普通失败闭环，单修复槽释放，原身份/文件锁保留。

原adf51=35P/1F/15E/0S公开证据cd57永久保留；5752只复测旧失败类31，不继承其他三类20，不称新51全跑。EXTRA1bfa H2两项PASS（1ae7b60c）、第三d592仅编译PASS/实际0测试（1d266f99）各自分层保留；11真MySQL及其他现有入口未覆盖真存储仍NOT_RUN。root本轮无活动Java，不重复旧PASS。真实DB/Redis/HTTP/设备业务/候选集成合并部署未验，TEST_DEPLOYED=NO；保持现有入口不新增。三个业务问题仅阻塞对应身份/DDL合同，未答不猜规则，不影响明确独立项。

### 以下为原失败、修复及此前验证历史，以本节最新实际结果为准

## 第三队d592公开编译证据已核／实际测试0

已核读[PR24公开README及summary](https://github.com/liboyang42-cpu/hengyin/blob/1d266f99ec55a50008bdc56057b9487adf7a3bd0/docs/verification/mini-bug-arch-20261015/VPS_FORWARD_d592334a/README.md)，证据`1d266f99ec55a50008bdc56057b9487adf7a3bd0`：固定d592334abee1a379963e7b72a58eec5e0f7bd5ad于06:08:19Z—06:10:59Z完成既有212 test-compile，guard0/completed；fresh主IT与匿名时钟class2份哈希、源码/日志哈希及精确容器清理入[FORWARD_D592_TEST_COMPILE_RESULT](FORWARD_D592_TEST_COMPILE_RESULT.json)。**无Surefire XML、实际测试0、11真MySQL仍NOT_RUN**；不称真并发/午夜行为闭环，不重跑或继承旧28。首次05:52:29Z锁拒绝0项/未编译原回执保留。

EXTRA1bfa H2两项PASS已独立签收；CORE5752单失败类31定向待实际结果，adf51原FAIL保留。入口现有不新增，真实MySQL/Redis/HTTP/设备业务/候选集成合并部署均未验收，TEST_DEPLOYED=NO。

### 以下为此前直接回报／待公开历史，当前以本节公开证据为准

## EXTRA H2两项通过／第三队仅编译通过

已核读[EXTRA公开README及summary](https://github.com/liboyang42-cpu/hengyin/blob/1ae7b60cab62696ec0a9259907cfad265496c554/docs/verification/mini-bug-arch-20261015/VPS_EXTRA_1bfa81a5/README.md)：固定1bfa81a5的E02_12Test **2实际PASS／0失败错误跳过**，fresh精确1XML、212guard0/completed及精确清理，原首次锁拒绝0项保留。证据1ae7b60cab62696ec0a9259907cfad265496c554，源包/日志/XML哈希见[E02_12_ORDINARY_VPS_RESULT](E02_12_ORDINARY_VPS_RESULT.json)。仅生产Mapper XML/H2合同回归，NULL生产来源仍未证，不称真MySQL/生产NULL复现、不重复旧64。

第三d592本地已报test-compile guard0，fresh主类/匿名时钟类和精确清理已核，**实际测试0**；公开编译报告待补，C00未冒称读原class文件。原拒绝准入历史保留，11真MySQL仍NOT_RUN，旧28不重跑/不继承。CORE5752已由本地fetch/review并准备独占源包，单旧失败类31复测结果待回；adf原51失败不覆盖、不迁移新51。入口现有不新增，真实DB/Redis/HTTP/设备/集成部署继续未验。

### 以下为此前失败、修复与队列历史，以本节最新实际层级为准

## CORE旧编辑夹具修复已push／31项待本地定向复测

最新完整CORE候选`5752ee6fd302dab9e610f65cb8f3dc5b2b66201d` / PR44，直接父adf59791。C00已核公开compare仅旧TopicUpdateByOwnerTest.java的imports/setUp三段成功insert生成ID回答＋新修复说明；章801+/节点901+/活动1001+，null重stub不耗ID，31方法/全部业务断言/负控和node101/102局部覆盖不变。生产、新协作者测试、其他测试、门禁和依赖均未改。原C03作者已交付停写，锁保留。

修后SOURCE_DELIVERED_UNVERIFIED／NOT_RUN；已交本地仅TopicUpdateByOwnerTest31定向，不重复51，不继承adf其余三类20为新SHA实跑。原adf51实际35P/1F/15E/0S，已核读[PR24公开原失败](https://github.com/liboyang42-cpu/hengyin/blob/cd57aef324b03c6c5d3f3f2580bbba91d5d295b4/docs/verification/mini-bug-arch-20261015/VPS_CORE_adf59791/README.md)及summary，源包/日志/四XML哈希/guard/精确清理入[C03_ADF51_VPS_FAILURE](C03_ADF51_VPS_FAILURE.json)永久保留，未因push关闭失败。最小命令/精确2路径及fixture链见[修复包](C03_TOPIC_UPDATE_FIXTURE_REPAIR.json)。EXTRA2本地正常准入中、F3编译队列，尚未接新成绩；入口现有不新增，真实DB/HTTP/设备/集成部署仍未验。

### 以下为此前失败/授权/排队历史，以最新候选与实际结果为准

## CORE adf新51项实际失败／单原作者修复槽

本地VPS212已实际执行冻结`adf597917da2654b6b753141180bbbcb6db92fc5`四类51：**35PASS／1失败／15错误／0跳过**，4fresh精确XML、guard1/child_command_failed、精确容器清理已按本地直接报告登记，公开PR24完整证据待补。新OWN_C03_14Test10、OWN_C03_12Test4、TopicCreateBusinessErrorPassthroughTest6均PASS；仅旧TopicUpdateByOwnerTest31的16项失败/错误，其中15项是章insert未生成身份，1项免费转商业断言被提前章身份错误挡住。

C00初核旧setUp无章节/节点insert成功及生成ID前提，章身份保护已在父e0，不能改生产guard求绿。已[精确授原CORE/C03唯一修复者](C03_TOPIC_UPDATE_FIXTURE_REPAIR.json)旧TopicUpdateByOwnerTest.java及单独修复说明；先核全实际调用链，仅修必要夹具，保31方法/全部业务断言/负控及node101+逐例覆盖。新源码未交/修后NOT_RUN；后续只交本地失败类31定向，不重跑51。EXTRA普通2已由本地串行启动、结果待回；F3编译继续现有队列。其他真实独立源码不等整队修复，不绕锁、不改入口。

### 以下为此前待验/准入历史，以本轮实际结果为准

> 当前验证排队更新：CORE adf普通51／EXTRA 1bfa普通2尚未运行，本地源包准备中；F3 d592首次test-compile准入exit75/containerId=null/0项、未编译，属其他任务持共享锁，不是源码失败。只等待现有212正常准入，不绕锁或重复旧PASS，详见[排队记录](LOCAL_VALIDATION_QUEUE_STATUS.json)。业务3问未答不擅改身份/原券策略或DDL；明确独立源码不因排队停工。

## POI查询合同修复已交／本轮三队候选已分别冻结

EXTRA新完整源码`1bfa81a5a58ffa8112c10414dad9fdb1701ef188` / PR36，原E02已停写，C00核精确3路径：只POI options的COALESCE(status,1)=1改status=1；新E02_12Test真实生产XML/H2两方法及原说明。保city/projection/order/limit/snapshot/count，无Java/DDL/权限/依赖更改。NULL生产来源未证，分类查询合同/NULL兼容修复，不计现场新增bug。[交付包](E02_12_SOURCE_DELIVERY.json)记录原child、父链及本地单类2方法命令；Cloud未运行，不重跑或继承29fe64。docs02b99bea仅三账已核。

CORE `adf597917da2654b6b753141180bbbcb6db92fc5`普通4类51声明待本地报告；第三`d592334abee1a379963e7b72a58eec5e0f7bd5ad`只改IT测试时钟，root已申请既有212 test-compile、守卫结果待回，不计11真库执行或普通28重跑。三队原作者本轮均交付停写，源码writer0，后续实际失败才原作者单修复线。真实HTTP/DB/设备/候选集成合并部署仍未验；用户不新增验证入口的决定不变。当前完整状态以[恢复页](LONGRUN_CURRENT.md)为准。

### 以下为本轮过程与先前历史，以当前候选和实际结果为准

## 本轮真实源码继续交付：CORE协作者输入保护

[PR44](https://github.com/liboyang42-cpu/hengyin/pull/44)新完整源码`adf597917da2654b6b753141180bbbcb6db92fc5`，直接父e0e3f517。唯一CORE/C03作者已交付停写；仅CmsTopicServiceImpl新增14行写前ID检查＋新10方法回归＋原说明。create/FULL显式名单含null/<=0沿原IllegalArgumentException/默认body500整包失败，重复正ID和顺序、list缺席/[]清空/已有行缺席保护/WHITELIST、owner与完整性校验顺序保留；不新增权限/成员存在/邀请/DDL/依赖。

源码和必要回归已push，**SOURCE_DELIVERED_UNVERIFIED／NOT_RUN**；已交本地root聚焦4类10新＋4＋31＋6=51源声明，实际执行数未知，无DB/Redis前提。旧e0普通10/0d157不继承新源码。完整FQN/命令/精确3路径及前提见[C03_14_SOURCE_DELIVERY](C03_14_SOURCE_DELIVERY.json)。真实HTTP/DB/设备/合并部署仍未验，TEST_DEPLOYED=NO。重复正ID按本轮明确指令保原行为，不另外造待决策任务。

EXTRA E02-12已原作者实际[ACK恢复](https://github.com/liboyang42-cpu/hengyin/pull/16#issuecomment-6209021853)：既有status1公共/保存上限支持仅POI选项NULL fail-closed查询对齐，分类QUERY_CONTRACT_NULL_COMPATIBILITY，生产NULL来源仍未证、不算现场新增bug；新SHA待交。E01-13售卖字段仅存读，WAITING准入政策未定义，不猜窗口拦截。第三F3-GROW-03已交`d592334abee1a379963e7b72a58eec5e0f7bd5ad`，仅原11项MySQL测试时钟和seed一致化，生产仍bde且普通两类未改；新测试源码NOT_RUN、真库11继续现有入口覆盖缺口，不重复旧28PASS。所有新状态见[恢复包](RESUME_KNOWN_BLOCKERS.json)。

### 以下为此前恢复与验证历史，以当前源码SHA及实际回执为准

## 当前有效约束：保持现有入口，不新增

用户最新明确决定：**“保持现有入口，不新增”**。新增隔离MySQL/Redis集成入口方案已关闭，不再处于等待批准状态，也不再催批同一方案。现有VPS212和CI配置保持原样；现有自动CI自然运行，不手动触发或重复重跑。普通回归只在新真实源码候选或未解失败时由本地按现有212验证，已通过且未变的候选不补跑。

现有入口未覆盖的真实MySQL/Redis项继续为 **NOT_RUN／现有入口覆盖缺口**，包括E02_25MySqlIT2、第三Growth真MySQL11等；不改称通过，不以普通/mock/skip结果代替。已有精确SHA的PASS和失败/锁拒绝历史均保留；真实HTTP/设备业务/集成合并部署仍未验收。此为约束更新，无新增开发或补跑任务。详见[持久决定](VALIDATION_ENTRY_DECISION.json)。C00只维护全局账，各队只维护自有交付账。

### 以下为此前交付与状态历史；涉及新增入口待批的表述已被本决定覆盖

## E02-25普通64项公开证据已签收／真RR2未运行

C00已核读[PR24公开README及summary](https://github.com/liboyang42-cpu/hengyin/blob/0d3b6195203b0b963e0dd77df8412fde70b3923e/docs/verification/mini-bug-arch-20261015/VPS_EXTRA_29fe9236/README.md)，证据提交`0d3b6195203b0b963e0dd77df8412fde70b3923e`。冻结源码`29fe9236ede15a8f1bbd3aaf2b93e3fc25409726`实际执行E02_25Test4＋E02_24Test5＋community.CommunityPostServiceImplTest55＝**64PASS，0失败/错误/跳过**；fresh精确3XML、212guard0/completed和精确容器清理均已核对，原日志/XML/源码哈希见[E02_25_ORDINARY_VPS_RESULT](E02_25_ORDINARY_VPS_RESULT.json)。本地本轮Java已结束，无失败修复槽需要开启，原作者身份/文件锁保留。

这是新SHA实际普通Service/SQL替身回归，不继承faef60PASS、不称66通过或RR实库闭环。E02_25MySqlIT **2项仍NOT_RUN**，真实RR快照、DuplicateKey/current读及SQL副作用提交未验；真Redis、运行HTTP、设备业务、候选集成、合并部署仍未验收，TEST_DEPLOYED=NO。新真库入口授权仍待用户明确答复，212/CI/网络不变。无真实READY不凑任务，后续实际源码另交新SHA。

### 以下为此前交付／待验历史，以本节当前结果为准

## E02-25已交源码，64普通／2真库待验

EXTRA新冻结源码`29fe9236ede15a8f1bbd3aaf2b93e3fc25409726` / PR36，原E02已停写/锁保留。C00已核公开diff：仅3生产文件按授权改comment重复键catch当前读及exact Mapper/SQL，另新4方法普通回归、新2方法guarded MySQL回归、原E02_24的duplicate夹具接线及说明；首次普通读、正常插入后读、post/body/parent绑定、事务/权限/HTTP/DDL/依赖不变，旧55未改。`e9b9e1912fa449e1ee1c0344ac2ff0dc4cb268ec`直接父为29fe，仅本队3交付文档，不另作源修复。

本地已接收，现按212准备E02_25Test4＋E02_24Test5＋community.CommunityPostServiceImplTest55=64源声明，实际报告未回，NOT_RUN；真实E02_25MySqlIT2仍缺批准入口，NOT_RUN。旧faef60PASS仍只在faef，不套29fe。Cloud未运行验证，TEST_DEPLOYED=NO；运行HTTP/设备/集成合并部署均未验。[精确七路径／完整FQN／依赖交接](E02_25_SOURCE_DELIVERY.json)。当前原队实际writer0/READY0，不凑任务；实测失败再由原E02单修复槽继续。

### 以下为此前候选及交付历史

## 当前三候选普通定向证据已签收

已核读PR24@`cae72e05ee668271d79ea9c95e505d7e8971d3a5`的三份README和summary：CORE `e0e3f517` **10/10**、EXTRA `faefbaf8` **60/60**、第三 `bde356f3` **28/28** 实际通过，均0失败/错误/跳过、fresh精确2XML、212guard0/completed、自有精确容器清理。[CORE证据](https://github.com/liboyang42-cpu/hengyin/blob/cae72e05ee668271d79ea9c95e505d7e8971d3a5/docs/verification/mini-bug-arch-20261015/VPS_CORE_e0e3f517/README.md) / [EXTRA证据](https://github.com/liboyang42-cpu/hengyin/blob/cae72e05ee668271d79ea9c95e505d7e8971d3a5/docs/verification/mini-bug-arch-20261015/VPS_EXTRA_faefbaf8/README.md) / [第三队证据](https://github.com/liboyang42-cpu/hengyin/blob/cae72e05ee668271d79ea9c95e505d7e8971d3a5/docs/verification/mini-bug-arch-20261015/VPS_FORWARD_bde356f3/README.md)。EXTRA首次锁拒绝0项仍保留，不改写历史。各结果只属于各自完整SHA，不是合并候选全量验证；SQL边界有替身，不是实际数据库/Redis/运行HTTP或设备业务验收。TEST_DEPLOYED=NO，未合并/部署。

真存储[入口缺口](https://github.com/liboyang42-cpu/hengyin/blob/cae72e05ee668271d79ea9c95e505d7e8971d3a5/docs/verification/mini-bug-arch-20261015/VPS_DB_ENTRY_GAP_20261016/README.md)已核：本次列CORE8类未入funds固定名单、EXTRA所列23类仅2已入/21未入，第三Growth真MySQL11未入；名单覆盖不等于新SHA已运行，有Redis mock不能称真Redis联验。独立限额入口仍待用户明确答复，212/CI/网络不改。

E02-25原`/root/external_e02`已实际[ACK恢复](https://github.com/liboyang42-cpu/hengyin/pull/16#issuecomment-6207665975)，源码writer1（队伍回执快照），无需等待上述结果。仅附加同owner已有E02_24Test的duplicate当前读夹具前提，保5方法与全部断言、不改旧55；新RR源码/真实库回归仍NOT_RUN，不继承faef60PASS。

### 以下为此前交付与验证历史，当前以本节及LONGRUN_CURRENT为准

## E02-24普通定向结果更新

EXTRA `faefbaf8947b6ff202849385d28473fe2eaaa21e` 已由本地VPS212实际执行E02_24Test5＋CommunityPostServiceImplTest55，**60PASS/0失败/0错误/0跳过**，fresh精确2XML、guard0/completed、精确自有容器清理。开始03:44:19Z、结束03:47:03Z（2026-10-16 UTC）；首次03:40:30Z锁拒绝0项作为历史保留。独立公开PR24汇总待补，当前仅按本地实际回报登记，不编造证据SHA/日志哈希。普通SQL替身测试不等于真实MySQL RR/Redis/HTTP；后续E02-25新候选不能继承60PASS。第三bde于03:47:03Z进入同入口串行执行，尚无结果。Cloud不补跑，TEST_DEPLOYED=NO。

## E01-19普通定向结果更新

CORE e0e3f517b8e6deb26ed3697a00d38ae14f015e76已获本地VPS212普通2类10实际PASS（0failure/error/skip，fresh精确类集、guard0/completed、精确容器清理）；已核读[PR24公开README及结构化summary](https://github.com/liboyang42-cpu/hengyin/blob/5a90a1e81db8274960c3d5dee9d6646aa73311be/docs/verification/mini-bug-arch-20261015/VPS_CORE_e0e3f517/README.md)，证据提交`5a90a1e81db8274960c3d5dee9d6646aa73311be`；原始报告由本地保留。旧483不迁移，真MySQL/Redis、HTTP、设备/业务、合并部署仍未验。EXTRA faef普通60项PASS已回报、公开报告待补；第三bde串行验证进行中，不影响原E02按已发精确grant继续RR源码；ACK未到不虚报worker运行。

## E01-19 显式取消收藏的并发ACK修复

公共源码 `e0e3f517b8e6deb26ed3697a00d38ae14f015e76` / PR44，原0d之上一小提交，源码作者CORE/C03；Cloud未运行，本地新普通2类10项定向PASS，真实SQL/HTTP仍未验。`POST /api/topic/like` 显式type0两请求都读到旧行而DELETE1/0时，第二个body.code由500改为200、msg“取消点赞成功”，符合既有方向幂等合同；HTTP方法/路径/参数/字段/账号过滤/目标ID不变。空type旧toggle的DELETE0仍失败，负数失败、数据库异常仍传播，不增加自动重试或端侧写权。

新5＋既有方向5已由本地在新SHA实际执行10/10通过；无新库/配置/DDL，不沿用0d157PASS。真实数据库/HTTP/设备/合并部署未验收，TEST_DEPLOYED=NO。回执及精确文件见[E01_19_SOURCE_CLAIM](E01_19_SOURCE_CLAIM.json)。

## 当前E06针对性复测闭环：5PASS／真实HTTP未验

C00已完整读回[PR24公开报告](https://github.com/liboyang42-cpu/hengyin/blob/b5273c3233d1753c9488edc410e1a1c0d5b29fed/docs/verification/mini-bug-arch-20261015/VPS_EXTRA_b45664de/README.md)与summary：固定源码 `b45664de6136eb97c46ed0c86e95fc25686dc1b7` 正常212准入后，E06_17Test单类**5实际项PASS，0失败/0错误/0跳过**，fresh XML精确类集，guard0/completed，精确容器清理。源包、日志及XML哈希已入总控；仅普通单类针对性签收。

旧ed598的11类76实际75PASS/1FAIL原记录及初次锁拒绝原证据继续保留；该唯一失败方法现按b456五项targeted PASS闭环，不称b456重跑全76、不将其他类成绩迁移。原E06修复槽已释放，不新增源码改动；仅有真实独立READY才继续领取，否则EXHAUSTED，不为凑线造任务。EXTRA自己维护本队交付账，C00只更新总控；Cloud无补跑。

裸SQL疑点仍NEEDS_REACHABILITY/NO_SOURCE_CHANGE，无真实业务可达证据不计新BUG，不改共享生产文件。GET分享读库故障HTTP503/body500、原GONE/隐私边界的MockMvc回归通过不等于运行中HTTP验收；真实数据库/Redis、HTTP/身份业务、设备、合并部署及迁移均未验收，TEST_DEPLOYED=NO。第三队普通24PASS/真MySQL11未运行仍独立。

### 以下排队及旧失败为历史，以本节当前签收为准

## 当前E06修复复测排队：NOT_RUN，非源码失败

当前状态一致口径：旧 `ed598258c0f614385940f326099b9626531fab02` 的11类76实际=75PASS/1FAIL仍保留，失败未因push关闭；修复 `b45664de6136eb97c46ed0c86e95fc25686dc1b7` 仅E06_17Test单类5个源码声明，212因another_build_holds_lock返回75/containerId=null，本次实际0项、未编译测试，**NOT_RUN排队**，不是修复失败或通过。

C00已完整读回[PR24公开排队证据](https://github.com/liboyang42-cpu/hengyin/blob/60febdbdcdcc7eed2b445a80f3d1f21d2cd76966/docs/verification/mini-bug-arch-20261015/VPS_EXTRA_b45664de/README.md)及admission-refused.json；commit `60febdbdcdcc7eed2b445a80f3d1f21d2cd76966`，源包SHA256 `2ab95e689599d4c67bf1777bb7e00293faf2ef50248ab40b695f393d214c5db3`。本地本次回报无活动Java验证，待正常212准入；Cloud不补跑、不重复改测试或生产源、不绕锁，不再全76。裸SQL疑点保持NEEDS_REACHABILITY/NO_SOURCE_CHANGE，无业务可达证据不计新bug。

跨端仍按已交源码合同读：GET分享读库DataAccessException的HTTP200→503/body500不变，非法token/无行/坏存储仍HTTP200/GONE；真实HTTP/业务/两端/部署未验收。Claude/iOS接收ACK待回，不能据源码或旧四Share方法通过称已上线。第三队普通24PASS/真MySQL11未运行独立记录。

## E06修复新候选交付／裸SQL疑点裁定

EXTRA原E06已交修复 `b45664de6136eb97c46ed0c86e95fc25686dc1b7`（直接父ed598，原child a9d1dcda7d9944ace6c89000fc2aa5696018fdff），C00实际核对公共compare仅2路径：E06_17Test.java和E06-17.md，生产/依赖0改。夹具增加既有Error querying database标记、保留原裸SQL子串，原generic/HTTP200/no-mapper及Share503与全部负控不变，额外加2条响应不含SQL断言。SOURCE_DELIVERED_UNVERIFIED/修后NOT_RUN，不能把旧75PASS或旧56PASS继承到此SHA。已交本地PR24#6202107770，仅E06_17Test聚焦复测（5是源码声明、实际以fresh XML计），不再全76，无新DB/Redis前提；Cloud不运行。

裸SQL裁定：**NEEDS_REACHABILITY / NO_SOURCE_CHANGE；不计新增已确认BUG。** C00只读核GlobalAdvice→UserSafeMessage与AjaxResult重复脱敏链，及原sqlLeak_isMaskedToGeneric使用的框架异常形状；定向main源码查找没有证明真实业务会抛出该人工短裸SQL形状。仅有合成Controller/MVC证据不能扩成生产可利用结论，不自动扩regex/屏蔽业务文案、不转写共享源；独立疑点保留，夹具修复不等于修掉它。

## 当前EXTRA影响面失败与唯一原作者修复线

EXTRA候选 `ed598258c0f614385940f326099b9626531fab02` 已据[PR24@77cb3320fbbf47ef061db811fdc51a0a07c462f8](https://github.com/liboyang42-cpu/hengyin/blob/77cb3320fbbf47ef061db811fdc51a0a07c462f8/docs/verification/mini-bug-arch-20261015/VPS_EXTRA_ed598258/README.md)完整读回：编译PASS，11类76实际项=75PASS/1FAIL/0ERROR/0skip，exact fresh XML，guard1/child_command_failed。75源码声明不能当实际执行数；PlayJourneyStoryServiceImplTest实际26，合计76。唯一失败为E06_17Test无关Controller脱敏隔离负控:111，预期generic、实际裸SQL。E03_18新5项通过；E06 Share503/GONE/隐私/成功4项通过，不得说Share503失效或整组通过。

已收到[EXTRA原E06实际接收ACK](https://github.com/liboyang42-cpu/hengyin/pull/16#issuecomment-6202056607)：external_e06只占一条修复线，沿自有E06_17Test/E06-17文档核旧全局Advice支持的真实异常形状，保generic/不泄漏/隔离/503全部断言，不删负控/跳过/期待泄漏。其他真实独立工作继续；无READY不造任务。修复必须新完整SHA、路径/依赖交本地针对性复测；Cloud不手动跑。共享UserSafeMessage/GlobalExceptionHandler未授E06写权，先核归属再正式转交，C00不替其改原测试。

裸SQL疑点独立保留为[SHARED_SAFE_MESSAGE_SQL_OBSERVATION.json](SHARED_SAFE_MESSAGE_SQL_OBSERVATION.json)：C00已直接比较ed598与e72，UserSafeMessage/GlobalAdvice内容完全相同。原pattern未覆盖该短裸SQL，of返回原消息；这是既有脱敏覆盖缺口的静态/MockMvc证据，不是已证明生产端点真实泄漏。待真实caller/驱动异常形状、旧合同与原owner核清，未改任何共享生产文件；即使隔离fixture修正通过也不关闭该疑点。真实HTTP/DB/设备/业务/合并部署继续未验。

# iOS独立agent优化与共用后端交接

> 三方统一协作规则：[CY-COORD-20261015-v1](COORDINATION.md)。当前文件归属与候选见[OWNERSHIP.md](OWNERSHIP.md)；初始规划不等于已领单。最终提示词：[Codex](START_PROMPT.md)、[Claude](CLAUDE_START_PROMPT.md)、[iOS](IOS_START_PROMPT.md)。

2026年10月15日。**iOS由独立agent继续负责，小程序Codex/Claude任务不改iOS代码；后端仍统一。** Flutter仍不在当前工作范围。本文件补充你已在做的iOS任务，不要求重新开始迁移或放弃现有分支。

## 从哪里开始

先读本文件，再读 [BACKEND_CHANGES.md](BACKEND_CHANGES.md) 和 [IOS_START_PROMPT.md](IOS_START_PROMPT.md)。[TASKS.md](TASKS.md)与[tasks.json](tasks.json)是共用后端/小程序Codex责任清单；[CLAUDE.md](CLAUDE.md)是小程序UI/新功能边界。原来的“不做iOS”只约束该任务包，不能理解为让你停止iOS工作。

## E06-17 分享快照运输合同及第三队兼容索引

本节同步已交付源码合同，不是新增原生/后端开发权限或部署通知。最新完整说明见[后端总账E06-17](BACKEND_CHANGES.md#e06-17-运输合同同步分享读库异常-http200--503)。

- EXTRA/PR36固定源码 `ed598258c0f614385940f326099b9626531fab02`：GET `/api/roam/share/snapshot` 有效token读库抛DataAccessException时，由HTTP200改为**HTTP503**，body仍AjaxResult.code500/通用文案且无data。非法token/无行/坏存储/隐私失败仍HTTP200及既有GONE，成功读取与匿名隐私闸不变。路径、参数和body形状相同不能掩盖HTTP状态变化，旧`NO_API_CHANGE`标签不代表运输合同不变。
- 源码状态 **SOURCE_DELIVERED_UNVERIFIED**；本地11类已回76实际75PASS/1FAIL，唯一隔离负控失败，见当前状态，**真实HTTP未验**；旧e72两类56PASS不覆盖。TEST_DEPLOYED=NO、合并/部署/两端验收未完成。iOS若实际使用此分享读面，保留既有错误与GONE区别，不因503认定服务端已撤回；不在未部署版本上宣称已可联调，不新增共享请求策略或自动重试政策。
- 第三队PR72/2babbe28：后台漏斗日期保null/空/blank/单边查询和完整rows，不增加必填/62天/分页；不要求iOS新增后台接口调用。成长修复无新字段/DDL/奖励语义，普通24PASS仅属普通回归，11真MySQL仍NOT_RUN，真实并发/HTTP/业务另验。

Claude/iOS不重复修后端，后端缺陷仍交EXTRA6/E06或第三队原作者；原生源归独立iOS所有者，本文不扩共享请求模块/产品政策写权。此索引已发布不等于对端已读，Claude/iOS合同接收ACK待收。

## 本次核实的源码基线

| 仓库/分支 | 核实SHA | 说明 |
|---|---|---|
| 公共统一后端 hengyin/master | 8b7eb814e3db00aa2543fee489fad44f9aa7aec3 | 当前文档分支的代码基底，不代表测试运行包 |
| 公共后端集成分支 claude/mini-foundation-abc-integration-squash-20261014 | 0b301c9b8d1853a86ed689c701eaad40cb3b70d7 | PR11头，相关子PR12–15未合并，需C00组合审查 |
| 公共iOS main | a8d387e206ed3994b6d265b3618db0c293f6ec15 | 本机只读快照与GitHub提交匹配 |
| 公共iOS migration/native-ios | f0df08598adbd73cdd85089ce4d1e0965e0e070c | GitHub查询比main多52提交；你实际在用的分支仍由你登记，不自动切分支 |

iOS源码与说明公开于 [chengyin-ios](https://github.com/liboyang42-cpu/chengyin-ios)。以下指针固定到已查询的migration快照，README虽仍称main为foundation，也不能仅凭这句判断主干功能。[iOS README](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/README.md)写明多项production能力仍受批准注册表/端点门控；[CN登录合同](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/docs/cn-native-session-contract.md)要求真实账号读回，地址/开关本身不证明登录已可用。不要因后端有新PR就打开全部原生能力。

## 已有能力先复用

| 已核原生实现线索 | 当前证据 | 不要误判为 |
|---|---|---|
| 会话回执与稳定重放 | [Core/PlayGameSessionRuntime.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/PlayGameSessionRuntime.swift)：playerReceipt、expectedRevision、retryExact | 所有恢复缺失，或已证明跨进程/跨设备全部验收 |
| 订单未知与账号隔离 | [Core/OrderLifecycleCoordinator.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/OrderLifecycleCoordinator.swift)；[Core/RegionalSessionStorageScope.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/RegionalSessionStorageScope.swift) | 只有小程序有幂等/账号保护 |
| 原生音频中断 | [App/NativePlatformAdapters.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/App/NativePlatformAdapters.swift)；[App/MerchantNPCVoiceDevice.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/App/MerchantNPCVoiceDevice.swift) | iOS没有中断监听，需要照抄wx音频模块 |
| 积分两类读面 | [Core/WalletCommerceService.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/WalletCommerceService.swift)：assetPoints非分页，points另一分页路径 | 所有积分接口都分页，或页面有数据就已结算正确 |
| NPC数据读面 | [Core/NPCChatData.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/NPCChatData.swift)：export只读模型 | 原生已有本人全部NPC删除闭环 |
| 原生群投票 | [Core/GroupPollService.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/GroupPollService.swift)；[App/GroupPollViews.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/App/GroupPollViews.swift) | 因小程序不露出，就要删除iOS实现 |

源码/测试入口存在只是静态线索，本次没有运行Swift、Xcode、设备或真实后端验收。旧研究的“iOS最全”“已接好”“AOT决定性能”“后台音频唯一在Flutter”等不作为本轮完成结论。

## 优先级与任务

这是12项复核/优化卡，不是12个已复现bug。先I00和P0的已确认问题；等待后端的项可先做兼容测试、输入保真及UI状态，不擅自猜协议。已修或未纳入的项目记录证据即可。

| 编号 | 优先级/任务 | 依赖 |
|---|---|---|
| I00 | P0 先接续现有分支与准入合同 | 独立可先做；后端C00协作 |
| I01 | P0 GPS精度与坐标系适配 | BK-GPS；PR12仅代码提案，运行待核 |
| I02 | P0 写入未知恢复与账号隔离 | A01合同；本地回归可先做 |
| I03 | P0 作者保存重开与媒体恢复 | B02、A03；现有UI/本地草稿可独立回归 |
| I04 | P0 报价支付退款与资产读回 | B03；既有原生能力不得因小程序范围被删 |
| I05 | P0 私密内容引用撤回及缓存访问 | B05；不改变授权模型 |
| I06 | P1 原生音频中断与生命周期资源 | 原生可独立核；不照搬wx实现 |
| I07 | P1 徽章积分榜单与列表语义 | BK-BADGE及既有读接口 |
| I08 | P1 AI不可用用量与NPC数据权利 | A02；既有删除接口见BK-EXISTING |
| I09 | P1 排期与团队页面的过期状态读回 | B04及现有允许动作 |
| I10 | P2 原生交互无障碍与性能测量 | 不依赖新后端合同，可与等待项并行 |
| I11 | P2候选 新入口按iOS已授权范围选择 | 需先核现有实现；不要求全量移植 |

### I00 先接续现有分支与准入合同

P0；独立可先做；后端C00协作。

记录你实际分支/HEAD/未提交工作、当前iOS任务及运行包，不切回main重做迁移。核现有端点、market/realm、token与role读回、功能准入和测试身份；后端地址/批准注册表为空不是可以填生产地址的提示。

验收：有当前基线与源/运行对应表；无合法环境时保留OFF，不用假身份或role默认值冒充可用。

源码入口：[README.md](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/README.md)；[docs/cn-native-session-contract.md](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/docs/cn-native-session-contract.md)；[Core/CNAccountSessionService.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/CNAccountSessionService.swift)；[Core/RegionalSessionStorageScope.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/RegionalSessionStorageScope.swift)。

已有测试入口：[Tests/CoreTests/CNAccountSessionServiceTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/CNAccountSessionServiceTests.swift)；[Tests/CoreTests/RegionalSessionStorageScopeTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/RegionalSessionStorageScopeTests.swift)。这些是需由你核实并运行的入口，不是本次已通过的测试。

### I01 GPS精度与坐标系适配

P0；BK-GPS；PR12仅代码提案，运行待核。

已核该iOS快照的PlayCompletionEvidence.location只有经纬度/coordinateSystem，complete的location分支只发经纬度；PR12新增accuracy尚不能由该证据类型表达。这是即将接入合同的缺口，不证明已发生上线故障。沿实际provider→projection→evidence→请求链保留精度与样本时间；负精度无效、差精度不能故意省略。

验收：核PR12目标部署后验证缺失/无效/阈值内外、旧客户端兼容、扫码通路；WGS84不改标签冒充GCJ02，未获有效投影能力保持受控不可用。

源码入口：[Core/PlayExperienceService.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/PlayExperienceService.swift)；[Core/RuntimeLocationProjection.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/RuntimeLocationProjection.swift)；[Core/RoamLiveSessionController.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/RoamLiveSessionController.swift)；[App/PlayNativeDeviceProvider.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/App/PlayNativeDeviceProvider.swift)；[App/RuntimeNativeLocationProvider.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/App/RuntimeNativeLocationProvider.swift)。

已有测试入口：[Tests/CoreTests/PlayExperienceTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/PlayExperienceTests.swift)；[Tests/CoreTests/RoamLiveSessionTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/RoamLiveSessionTests.swift)。这些是需由你核实并运行的入口，不是本次已通过的测试。

### I02 写入未知恢复与账号隔离

P0；A01合同；本地回归可先做。

已有playerReceipt与retryExact，复用，不新建回执服务。逐链核requestId/version/旧scope捕获、持久化未决锁、进程重启及两设备读回；不得把某个pending内存属性泛化成全项目无持久化。取消/退款等旧接口没有requestId的地方不能凭空补字段。

验收：超时先查原事实，同requestId异命令不换ID绕过；晚到响应不串账号/realm；进程重启保留原身份，不能以查不到、读取失败或退出页面直接清锁。

源码入口：[Core/PlayGameSessionRuntime.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/PlayGameSessionRuntime.swift)；[Core/PlayExperienceCoordinator.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/PlayExperienceCoordinator.swift)；[Core/RegionalSessionStorageScope.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/RegionalSessionStorageScope.swift)；[Core/OrderLifecycleCoordinator.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/OrderLifecycleCoordinator.swift)。

已有测试入口：[Tests/CoreTests/PlayExperienceTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/PlayExperienceTests.swift)；[Tests/CoreTests/RegionalSessionStorageScopeTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/RegionalSessionStorageScopeTests.swift)；[Tests/CoreTests/OrderLifecycleCoordinatorTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/OrderLifecycleCoordinatorTests.swift)。这些是需由你核实并运行的入口，不是本次已通过的测试。

### I03 作者保存重开与媒体恢复

P0；B02、A03；现有UI/本地草稿可独立回归。

保留草稿/主题/章节/节点ID与revision，保存后以真实读回验证顺序、模板绑定和运行规则；区分媒体已选/上传未知/已确认引用，发布失败保留编辑。后端字段尚未冻结时不同时重写编辑器或新增DSL。

验收：重开、章重排、二次编辑、丢响应、上传中关闭、旧作品更新均不丢语义；旧会话/旧内容继续可读，新增内容不反转旧结局。

源码入口：[Core/TemplateAuthoringService.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/TemplateAuthoringService.swift)；[Core/TemplateAuthoringSavedDraft.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/TemplateAuthoringSavedDraft.swift)；[Core/TemplateAuthoringPublishReceipt.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/TemplateAuthoringPublishReceipt.swift)；[Core/TemplateAuthoringMediaReferenceSnapshot.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/TemplateAuthoringMediaReferenceSnapshot.swift)。

已有测试入口：[Tests/CoreTests/TemplateAuthoringSavedDraftTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/TemplateAuthoringSavedDraftTests.swift)；[Tests/CoreTests/TemplateAuthoringPublishReceiptTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/TemplateAuthoringPublishReceiptTests.swift)；[Tests/CoreTests/TemplateAuthoringMediaReferenceSnapshotTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/TemplateAuthoringMediaReferenceSnapshotTests.swift)。这些是需由你核实并运行的入口，不是本次已通过的测试。

### I04 报价支付退款与资产读回

P0；B03；既有原生能力不得因小程序范围被删。

沿已授权iOS订单/核销/退款链核服务端报价、版本、积分单位、资产读回和SDK回跳；SDK成功不能确认付款，UNKNOWN不能自动退款重付。cart在iOS可能有既有契约，小程序零调用不等于iOS应删；维持其当前准入状态。

验收：重复点击/回跳/超时/退款中断不重复资产变更；价款与旧订单快照不变；有独立账务读回；不恢复支付开关、IAP、出金或新资金政策。

源码入口：[Core/OrderLifecycleCoordinator.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/OrderLifecycleCoordinator.swift)；[Core/OrderLifecycleService.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/OrderLifecycleService.swift)；[Core/WalletCommerceService.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/WalletCommerceService.swift)；[Core/WalletCommerceContracts.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/WalletCommerceContracts.swift)。

已有测试入口：[Tests/CoreTests/OrderLifecycleCoordinatorTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/OrderLifecycleCoordinatorTests.swift)；[Tests/CoreTests/OrderLifecycleServiceTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/OrderLifecycleServiceTests.swift)；[Tests/CoreTests/WalletCommerceTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/WalletCommerceTests.swift)。这些是需由你核实并运行的入口，不是本次已通过的测试。

### I05 私密内容引用撤回及缓存访问

P0；B05；不改变授权模型。

核原帖/引用/媒体缓存、退群/撤销分享/删改后的再次访问；权限不能由旧快照永久授予，也不能因删除关联默认变PUBLIC。适配现有后端拒绝与失效投影；根因在后端时交B05，不造仅靠客户端的权限保护。

验收：两账号及原旧链接回读符合原政策，失效媒体不残留可见；合法公开内容不被误清；鉴权/RBAC变化另批。

源码入口：[Core/SquareWorkspaceService.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/SquareWorkspaceService.swift)；[Core/SquareWorkspaceStore.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/SquareWorkspaceStore.swift)；[Core/SquareWorkspaceCoordinator.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/SquareWorkspaceCoordinator.swift)。

已有测试入口：[Tests/CoreTests/SquareWorkspaceTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/SquareWorkspaceTests.swift)；[Tests/CoreTests/SquareWorkspaceLegacyRecoveryTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/SquareWorkspaceLegacyRecoveryTests.swift)。这些是需由你核实并运行的入口，不是本次已通过的测试。

### I06 原生音频中断与生命周期资源

P1；原生可独立核；不照搬wx实现。

已有AVAudioSession中断/路由变更监听，先核覆盖与释放。来电/耳机拔出/录音抢占/页面退场/切账号后正确暂停与恢复意图，取消或用户主动暂停后不擅自续播；不重复注册观察者或让旧回调恢复新会话。

验收：真实设备验证中断/锁屏/前后台与UI一致，无残留会话/观察者/临时文件；兼容既有最低版本，不为采用新文档API擅自提高部署目标或追加后台entitlement。

源码入口：[Core/PlatformAudioPlayback.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/PlatformAudioPlayback.swift)；[Core/PlatformChapterAudioBridge.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/PlatformChapterAudioBridge.swift)；[App/NativePlatformAdapters.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/App/NativePlatformAdapters.swift)；[App/MerchantNPCVoiceDevice.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/App/MerchantNPCVoiceDevice.swift)。

已有测试入口：[Tests/CoreTests/PlatformChapterAudioTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/PlatformChapterAudioTests.swift)；[Tests/CoreTests/ShopNPCInterruptionTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/ShopNPCInterruptionTests.swift)。这些是需由你核实并运行的入口，不是本次已通过的测试。

### I07 徽章积分榜单与列表语义

P1；BK-BADGE及既有读接口。

PR12平台徽章将排除club_id非空定义，端上不补回俱乐部数据凑数量。积分已有assetPoints非分页与另一个分页points分支，保持不同合同；未在Top50不表示0分；部分读取失败不能抹掉已成立身份卡。

验收：徽章来源/缓存失效正确；两种积分读面不混page参数与返回模型；榜外显示真实名次/分数或未知；异常/空/拒绝状态可区分。

源码入口：[Core/ProfileService.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/ProfileService.swift)；[Core/GrowthCenterService.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/GrowthCenterService.swift)；[Core/RoamBadgeWallReader.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/RoamBadgeWallReader.swift)；[Core/WalletCommerceService.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/WalletCommerceService.swift)。

已有测试入口：[Tests/CoreTests/ProfileServiceTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/ProfileServiceTests.swift)；[Tests/CoreTests/GrowthCenterTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/GrowthCenterTests.swift)；[Tests/CoreTests/PlayPlayerLeaderboardReadbackTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/PlayPlayerLeaderboardReadbackTests.swift)。这些是需由你核实并运行的入口，不是本次已通过的测试。

### I08 AI不可用用量与NPC数据权利

P1；A02；既有删除接口见BK-EXISTING。

当前NPCChatData是export只读模型，不据此说已有原生删除。若已授权iOS范围包括删除，按现有后端本人全部NPC历史语义、危险确认及晚到请求作废接入；否则明确待纳入，不假装PR15小程序按钮可直接给原生用。AI缺配置/审核失败/超时不可回示例成功，用量未知不显示0真实成本。

验收：删除含义与后端一致，重开回读和其他账号隔离；A02未冻结新usage/额度字段前不造成功账本或新API，不自动打开模型付费/记忆场景。

源码入口：[Core/NPCChatData.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/NPCChatData.swift)；[Core/NPCChatDataCoordinator.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/NPCChatDataCoordinator.swift)；[Core/MerchantNPCConversationHistory.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/MerchantNPCConversationHistory.swift)；[App/NPCChatDataView.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/App/NPCChatDataView.swift)。

已有测试入口：[Tests/CoreTests/NPCChatDataTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/NPCChatDataTests.swift)；[Tests/AppUnitTests/NPCChatDataViewTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/AppUnitTests/NPCChatDataViewTests.swift)。这些是需由你核实并运行的入口，不是本次已通过的测试。

### I09 排期与团队页面的过期状态读回

P1；B04及现有允许动作。

核系列原时刻/时区/候补状态、当前allowedActions和更新后读回；不要把原生编辑默认值覆盖到服务端保存值，也不允许仅凭旧页面按钮可点绕过最新状态。

验收：非09:00场次保存重开不改时刻；报名/候补后动作与服务端一致；队伍轮询慢响应不覆盖新状态，离页后正确停止。

源码入口：[Core/ClubSeriesLeadOptions.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/ClubSeriesLeadOptions.swift)；[Core/PlayPlayerTeamReadback.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/PlayPlayerTeamReadback.swift)；[Core/PlayExperienceService.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/PlayExperienceService.swift)。

已有测试入口：[Tests/CoreTests/PlayPlayerTeamReadbackTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/PlayPlayerTeamReadbackTests.swift)；[Tests/CoreTests/PlayExperienceTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/PlayExperienceTests.swift)。这些是需由你核实并运行的入口，不是本次已通过的测试。

### I10 原生交互无障碍与性能测量

P2；不依赖新后端合同，可与等待项并行。

先量地图/长列表/图片音频内存与更新频率，使用稳定业务ID、取消离页Task、必要增量刷新/可见内容加载，避免为了对齐小程序组件重写SwiftUI。检查关闭sheet/返回保留草稿、重试/空/权限/未知结果、VoiceOver、动态字体和Reduce Motion。

验收：代表性真机/系统版本有同场景前后证据及回归，无卡死/误触/资源泄露；synthetic截图与真实业务截图分开，不拿AR/3D源码数量当设备表现排名。

源码入口：[App/WalkingNavigationView.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/App/WalkingNavigationView.swift)；[App/PlayRouteMapView.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/App/PlayRouteMapView.swift)；[App/PlayExperienceView.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/App/PlayExperienceView.swift)。

已有测试入口：[Tests/AppUITests/AccessibilityPresentationFlowTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/AppUITests/AccessibilityPresentationFlowTests.swift)；[Tests/CoreTests/PlayRouteMapCameraTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/PlayRouteMapCameraTests.swift)。这些是需由你核实并运行的入口，不是本次已通过的测试。

### I11 新入口按iOS已授权范围选择

P2候选；需先核现有实现；不要求全量移植。

对官方路线、收到印章、队伍举报、主题状态键、认领已有门店/进度等逐项核当前原生入口。只给确实缺且纳入你原计划的项拆单；creator已有服务、群投票已有服务与UI，不把旧零调用/小程序不露出裁决套成iOS缺陷。

验收：每项标已有/部分/未纳入/依赖后端；新功能字段用真实合同，不发明表或端点；小程序不做会费/群聊/投票不意味着原生删除它们，也不等于批准原生开收费。

源码入口：[Core/RoamExperienceService.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/RoamExperienceService.swift)；[Core/CreatorContentService.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/CreatorContentService.swift)；[Core/GroupPollService.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/GroupPollService.swift)。

已有测试入口：[Tests/CoreTests/RoamExperienceServiceTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/RoamExperienceServiceTests.swift)；[Tests/CoreTests/GroupPollTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/GroupPollTests.swift)。这些是需由你核实并运行的入口，不是本次已通过的测试。

## 后端依赖与工作交界

| 后端所有者 | 给iOS的内容 | iOS可先做 | 等待合同/部署后做 |
|---|---|---|---|
| C00/C99 | 固定代码SHA、真实接口/DTO/错误/权限/版本/兼容fixture和变化记录 | 记录当前原生适配器/能力开关/测试缺口 | 在同一获授权VPS版本下集成 |
| B02 | 保存/重开/发布保真 | 原生草稿/媒体/离页与旧数据回归 | 新的DTO差异与读回 |
| B03 | 资产/报价/并发/冲正 | SDK结果不直接确认、UNKNOWN状态、旧订单显示 | 账务事实及当前策略联调 |
| B04/B05 | 时刻/候补/可见性撤回 | 旧页面和缓存失效回归 | 服务端当前allowedActions/拒绝结果 |
| A01 | 会话/回执/稳定请求身份 | 复用现有原生pending/receipt，覆盖重启/账号切换 | 新原因/版本边界，以真实合同为准 |
| A02/A03 | AI失败/用量、结局/版本兼容 | 不可用/未知展示与旧fixture回归 | 真实字段/新fixture；不默认启用新策略 |

本agent只改iOS仓库及其专属测试/文档，不直接并行编辑hengyin的Codex独占文件。发现后端根因，提交最小失败请求（脱敏）、预期/实际、所属BK/后端任务和可观察读回，由对应后端所有者处理。若iOS新增功能需要专属接口，先向小程序Claude/后端协调者申领文件并冻结合同，不能另造/iOS API真源。

## 如何持续获得后端变化

每次开始适配或联调前，更新文档分支并读 [BACKEND_CHANGES.md](BACKEND_CHANGES.md)，再核对应源提交/PR、合并状态和测试部署证据。C00/C99独占维护该公共记录，各worker把变化交协调者汇总，避免大家同时改一份日志。没有建立自动推送或已授予跨agent消息通道，不把静态文档当实时通知。

后端变更若改变字段含义、HTTP方法、必填、错误、权限、版本/CAS或重放语义，需要兼容方案及iOS测试向量；未冻结前iOS只做准备，不猜字段。后端已合并也不等于VPS已部署；iOS不得依未部署合同开启写入。

## 环境和交付

iOS代码/Swift测试/Xcode构建使用iOS agent已经获准的平台；本任务不运行它们。Java构建、开发集成后端、MySQL/Redis留在专用VPS，额外CI用GitHub托管runner；Cloud的localhost不是Mac隧道。发布、签名、实际支付、短信、账号/权限和production开关仍按原审批边界，不把本交接当生产批准。

交付一张状态表：I编号、当前分支/SHA、已有/缺口/待后端/不纳入、关联BK与实际后端SHA、修改文件、实际测试、设备/系统、业务独立读回和未验收项。单列Swift/Core XCTest、AppUnit、XCUITest、模拟器、真机、真实账号/支付/provider、部署和发布，不拿Python契约/构建/fixture截图代替后面的层级。iOS工期由你按现有已完成范围核算，不能累加Codex34—57人日或旧跨端预算。

平台核对参考：[Apple水平精度说明](https://developer.apple.com/documentation/corelocation/cllocation/horizontalaccuracy)（精度以米表示，负值表示位置无效）；[Apple音频中断说明](https://developer.apple.com/documentation/avfaudio/handling-audio-interruptions)。新版本音频API须按现有最低版本做可用性处理，本交接不要求提升部署目标。
