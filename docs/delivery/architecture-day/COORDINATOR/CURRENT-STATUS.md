# 当前施工状态（用户授权继续）

**整体未完成。原截止时间不再停止本次继续施工，10分钟协调已恢复；所有剩余失败已分配给原8个会话，root唯一集成，最后统一验收。** 完整范围仍为WP00–WP17 / Q1–Q10 / A01–A15 / T01–T22 / G0–G5。未合并、未部署、未执行生产DDL或微信发布。

## 同一修补基线

公开基线`d19276f56b3eb2a34ed8fab06e41ea8ea654a0d0`，分支`codex/architecture-day-repair-base-20261024`。含原订单号长度修补、单人团费用修补、C声音代次及夹具修补、F精确公开来源约束，现已加入D289e同号ASR/文本终态竞态的完整3文件：原member锁内复核ASR，存在时不另写文本终态，同scope回读原身份、冲突scope拒绝，不二扣不换号；新增3条barrier测试源码。该组合Java/DB及完整CI尚未运行，不能继承ca0e或局部绿色。

## 真实失败输入与所有权

旧候选`ca0e0d2598727f6e567c802b7a664f9634d945c6`的标准CI40122522919已失败。1866份原始XML有15069个唯一testcase、无重复：14384通过、31failure、7error、647门控skip，涉及19个失败suite。失败case不是业务BUG计数。

真实存储run40122516880的65份原始同SHA回执：46类PASS、19类FAIL；65份均有独立CLEAN。与workflow的计划/汇总等job计数分开。所有原记录及后续修补证据仍绑定各自来源，缺验证不算功能BUG。

| Owner | 当前明确责任 |
|---|---|
| A | 报名付款/Club/整体退款的4个unit suite及3个真库suite、原事实共享factory |
| B | 退款/提现2个unit suite及RefundRetry、ApprovedRefundFence、PaymentIntent真库 |
| C | 动作台账15处未知多写合同、U4/U5/U6精确静态错误与必要调用者 |
| D | ASR投影/原shop gateway及真实Redis quota用例、289e必需直接调用者 |
| E | 主题/内容6个unit suite共同facade装配及TopicContentGraph真库 |
| F | 真实Spring循环/托管代理装配2例及必要精确边界配置 |
| G | 7个通知/轮次真库共同canonical migration fixture及CLM/Commerce原付款证明 |
| H | 4个玩法/核销查询unit suite及漫游留存真库 |
| root | 剩余High依赖告警、共享契约、registry/依赖/生成台账、唯一整合与最终验收 |

精确类名、触发与所有权见[剩余失败分工](RESIDUAL-FAILURE-OWNERS.json)。原8会话复用，不创建第9个。原工作区与未交补丁保留；环境不可用者交完整聊天diff，不用active数量代替产出。

## 执行与验收

先把共同根因、真实调用者、装配与测试源码修齐，不新全仓审计、不逐补丁反复跑全批CI。保留全部金额/身份/权限/UNKNOWN/迁移/负控制断言，不删测试换绿色。必要修补完整收齐后冻结一个组合候选，root统一审查与标准CI/真实存储回归。

已有局部证据仅限相应片段：C声音5个Node文件156/156、核销源断言9/9、种子控制9/9；F来源引用owner报告3/3。以上不证明完整组合。原804项真库声明以及新增Graph负控后805项声明均不是通过数。真实HTTP身份、外部供应商、进程终止/恢复、容量与业务验收的缺口继续保留。

用户已批准业务规则见[批准记录](approved-settlement-voice-policy-20261024.json)，公开方案参考见[五项规则对照](五项业务规则-公开方案对照-20261024.md)。不新增费用、依赖或权限政策；不执行生产DDL、部署、微信或真实付费外呼。完整终点与范围见[治理范围](governance-scope.md)。

## 本轮已收取的必要修补

A完整两段聊天diff（8文件）、B完整主补丁（4文件）及独立共享历史factory补片、D93a372f6、F2faf2427、Hcfc6c3bd均已应用。没有生产付款保护放行，原金额/库存/回滚/账号/未知态断言保留；全部新的Java/DB组合仍NOT_RUN。C2cd05f1c（精确台账与静态证据）、E2f09aa52（真实主题装配及新增负控）亦已应用；Gfff8f312完成barrier与canonical audit fixture，root串行接入纯读ConflictException不标记外层事务回滚。Gdb3eb060附9项只读schema诊断且保留原异常。六个共享通知C前检失败根因仍OPEN，需一次隔离单类元数据诊断，其他已交付者不重派审查。不是整体完成。

High告警已定位为转台工具Puppeteer→BiDi→Zod3.25.76；根修补使用同主版本Puppeteer25.16.0官方依赖链，锁至Zod4.6.7，不添加漏洞豁免或改安全门禁。配套Puppeteer API加载、实际BiDi容器提前拒绝及原GLB头自检已接原依赖CI job；源码NOT_RUN，Grype与完整组合待最后同SHA验证。


root已逐调用核对C精确证据，U4固定8字段路线投影5项、U5原核销身份/UNKNOWN可见恢复3项，登记与digest冻结同步；无全局豁免/删零多写断言。Graph类32→33、联合65唯一类/805项为源码声明，Java/DB NOT_RUN。最终台账根据同一完整客户端源码重建，实际条数待生成回读。

必要单类诊断已启动：候选 `aab91b9eb7b2732de09aa5513a5be8d5856c81a2`，run `40127844664`（仅 `CommittedOfficialNoticeMySqlIT`），不算最终验收；原9项前检仍失败即照常抛出。两声音错误恢复组件接线后台账源库存5121，localtap2185/event2541（分类变化待C精确说明）；原未知多写为0仅为源码合同，不是执行通过。

C已完成定点生成器调用者对照：saveDraft普通分支仍POST、拒绝分支无请求；profile本人扫码与访客关注按variant合同均保留；注销apply真实wx.login归external，effectClasses.write/consent→apply/异常原status链仍保留，external验收仍验证写权威证据。root据此冻结台账5121/localtap2185/event2541，零未知多写断言不改。运行元数据已定位通知失败为CHECK_CLAUSE引号转义；G正在补前向严格入口及负控，不执行生产DDL，旧migration字节保留。

## 必要残项源码已收齐，进入最终同候选验收

G018c6253完整应用；新增严格v2只改原四处CHECK metadata规范化，原C/D字节不变，root逐字核对。六类共同fixture改用v2；原13用例不变，新增6用例声明。只用于未成功旧C且完整D形状，已typed首条拒绝重复ALTER；无生产DDL。root依赖声明v2仅依赖D，registry最终65唯一类/811声明，非通过数。

原8会话全部必要修补与调用者已收齐；准备冻结完整候选后一次标准CI+真实存储，及最终定向源码审查。完整WP/Q/A/T/G仍未完成验收，all_complete=false；失败证据不继承新SHA，不将计划、SOURCE声明、构建或CLEAN写成业务通过。

最终冻结候选：`ee815c108b791f7f2dbf801d50e4d302419e754f`，分支`codex/architecture-day-final-ee815-20261024`。标准CI [40128663849](https://github.com/liboyang42-cpu/hengyin/actions/runs/40128663849)；65类/811声明真实存储 [40128669303](https://github.com/liboyang42-cpu/hengyin/actions/runs/40128669303)。均已实际启动，尚无验收结论。原A/B/H仅在此SHA做最终资金/ASR/客户端合同源码核验，不开额外CI；root负责同SHA原XML/receipt/CLEAN及失败定点修补。

统一验收当前实际失败：存储40128669303在Plan因Graph登记33/严格解析32停止，0个真库job启动；root纠正统计（新增负控为原case内assert，原@Test均保留），实际65唯一类/810声明。迁移门只因历史只读诊断文档误用.sql后缀触发UNMANAGED_SQL_LOCATION，已改带代码块的.md文档，无SQL门禁放宽；静态inventory问题回读为空。Fast gate40128663849/job120975470782在可信manual-base“ambiguous manual merge-base requires review”停止，已交原F做精确安全解阻，未造base/清旧snapshot；其他标准job继续原run，不重开整批。原B已提交ee815同候选ASR源码核验，未发现指定增量的确定阻断；事务double/真实存储分层覆盖限制继续保留，不能宣称端到端全通过。

A/B/H三个最终指定增量源码核验均已收口：未发现新的确定阻断，报告与覆盖限制保存于`final-source-review-ee815.json`。root纠正计数后执行原严格precheck：65类errors=[]；批次计数合同测试1项PASS；SQL inventory无问题。这些仅证明修补后的静态接线，不是Java/MySQL/业务通过，原CI与原失败记录保留。

后端同ee815原XML回读：1933份、15453唯一testcase、无重复；14819PASS/1failure/0error/633门控skip。唯一失败为P0Deregistration真实hasActiveOrder→OmsOrderMapper查询缺verified_payment_fact_id；H接手canonical fixture装配，不能放宽注销/财务留存。C接实际3个U1有限路径证明及2个旧锚点，F接真实多公共LCA基线拒绝。原标准run40128663849已FAIL；不要把改善计数当全量通过。真实存储0个job启动，先将C/F/H收齐再启动新完整候选。摘要见`ee815-final-validation.json`。

本轮统一失败必要修补已收齐：Cbd776b6e精确U1三调用点/二迁行及4负控，root审定9key digest（cap13不扩大）；F4a149 verifier/81a597工具及root双pin，严格两LCA唯一共同祖先，原五策略/旧snapshot字节不变；H9484bfc3按canonical迁移补P0注销夹具，原case正文不变。尚未重新运行Java/DB组合，待冻结下一候选。上轮原38个failure/error按完整(classname,name)逐个匹配：本轮38/38实际PASS、无missing/skip；仍有新显露P0夹具失败，不能当整体完成。原Zod High在ee815实际Grype报告中消失（0High/Critical），余一Medium一Low，不将依赖门当实际renderer浏览器验收。

C新增测试首次3PASS/1FAIL源于测试错把profile的var声明当const；Ccd6f41d5按两页真实AST修正，原guard/URL来源/覆盖负控不删，root同一4例复验4PASS/0skip（约0.3s），非整批回归。原生产JS未改；台账生成物无需改变。F6可信文件实际bytes及donor4a149逐个比对一致，只有gate变化，五个旧策略快照不变，双ref和工具VERIFIER一致。H9484只改建库装配，原测试正文不变。上述实际失败源码已收齐，将冻结后再统一CI/真实存储，旧ee815仍FAIL。

## 当前同SHA统一验收

当前冻结完整修补候选：`3e976bcefb2a14b19600e6bc4a20029767d58b75`，隔离分支`codex/architecture-day-final-3e976-20261024`。标准CI [40130007237](https://github.com/liboyang42-cpu/hengyin/actions/runs/40130007237)，65唯一类/810声明真实存储 [40130012017](https://github.com/liboyang42-cpu/hengyin/actions/runs/40130012017)均已启动。旧ee815结果不继承新SHA绿色；真实存储上轮0类启动，本轮须逐个原XML/receipt/CLEAN回读。已交付者保持冻结，仅实际失败才回对应原owner；不再广泛审计。all_complete=false。

3e976存储65类job已全部结束，仅RegistrationFunds的一例FAIL（其它64类job success），不是64类业务独立回读PASS结论。A定位为该case指Activity55.topic3但seed只存在topic99，真实主题围栏正确拒绝；root只补topic3父行，所有原SUCCESS/scan/template30断言不变，生产代码不改。原XML/receipt/CLEAN正在一次性下载核对，标准后端job仍在原run，不取消不重开。

上线阻断新证据：3e976标准Backend在完整隔离应用启动时失败，真实环为CmsRegistration→CoopSettlementClaim→TopicSettlementEvidence→RegistrationSeat→CmsRegistration（原unit/core步骤已过，不能替代整图）。G接最小原席位结算读owner边界拆分，F接真实Spring图回归和精确新类型policy，不开circular/不lazy隐藏。存储65原receipt/XML已独立回读同SHA：810例809PASS/1FAIL、0skip/0重复、64类PASS/1FAIL、65CLEAN，只有A父主题fixture待新SHA验证。证据在`3e976-real-storage-readback.json`，后续修补不可继承旧绿色。

3e976后端原XML亦已独立读回：1933份/15453唯一case，14820PASS、633门控skip、0failure/0error/0重复；ASR终态barrier3例及P0注销1例实过。该事实不改变整应用启动FAIL：必须修真实装配环再证明启动。65存储810原case中809PASS/1FAIL，65份原source/precheck SHA均为3e976且CLEAN，已验证classes精确集合与原XML条数，摘要保存在两份readback JSON；未把旧registered_source历史锚当本次执行源。

G4e5ee6e1窄读owner已集成：唯一构造RegistrationSeatMapper，原MANDATORY/rollbackFor/两个FOR UPDATE/current-read方法及legacy409原义搬入；TopicEvidence直依赖Reader，旧SeatService同签名委托，不经过报名命令链。Mapper/XML不变，新增4单测源码，原Seat真库case内增读回而声明数不变；严格65类source precheck无错误（非执行PASS）。F d7df真实图首片已合入，等最后精确owner/consumer及tool pin，未再次开CI。

真实startup环的必要源码全部收齐：G4e5窄读owner；F首片d7df+最终verifier16ce2a26/tool3c25f3bb及35场景源码，精确两个incoming consumers、禁止反调，未授Mapper/table新写权；root六可信文件与donor逐字哈希一致、双workflow ref/VERIFIER一致、旧baseline/locking/commands不变。A原资金用例只补Topic3父行，原96方法/断言保留。下一完整候选编译、全启动和65类真实存储均未运行，禁止继承3e旧绿色或把567静态review线索写成已逐个审完。

## 最新完整冻结候选

`55b9e722fcfeeb118781a35ad077ed23849f86b0`，分支`codex/architecture-day-final-55b9-20261024`。标准统一CI [40132354873](https://github.com/liboyang42-cpu/hengyin/actions/runs/40132354873)、65类/810声明真实存储 [40132361105](https://github.com/liboyang42-cpu/hengyin/actions/runs/40132361105)已实际启动。包含窄read owner、精确约束与35Spring场景及A父主题fixture的完整组合。source扫描无秘密；Java/真实启动/DB均等待本SHA新证据，不继承旧3e绿色；旧3e startupFAIL及64/65存储结果仍完整保留。原8已交付者不派额外审查，只有实际失败回对应owner；未合并/部署/生产DDL/微信或付费外呼。

55b9本轮环境失败：固定MySQL镜像在registry-1.docker.io拉层返回HTTP502，docker125发生在任何测试前；标准Backend120986684047及5存储作业受影响。不是源码/业务失败，未运行保持NOT_RUN。后续存储已有20类作业成功，继续保留原运行，全部结束后仅对同SHA失败job重试，不重新运行通过类、不改镜像/代理/权限/门禁、不回Mac/VPS绕预算。

55b9标准CI首轮30项success，唯一Backend因镜像502在测试前失败；已仅重试job120986684047（同run40132354873/同SHA，30项通过者不重跑）。存储继续原矩阵，当前仅最初5个镜像拉取失败，无新增失败；结束后使用原summary支持的latest-attempt-per-class机制只重试失败类，保留全65类同SHA原回执，不新开完整批次、不改门禁。

55b9同run40132361105最终真实存储已PASS；root独立回读65类精确集合、同SOURCE/precheck.sha55、原XML810/810PASS、0skip/0duplicate，65份CLEAN、无binding问题。60类使用原a1、5类仅环境重试a2，原失败回执保留；原汇总按类选最新attempt，未重跑已通过60类。原RegistrationFunds96/96及权威主题case实过。标准Backend仅环境重试中，完整新JAR启动尚待证据，未宣称总体验收完成。

55b9标准Backend attempt2的Java步骤已成功，但下一原子迁移Python真MySQL阶段88例中2error，MerchantOfferWithdrawalColumns两个原例用当前Mapper projection却缺application_round列；实际1054，非Docker502，详见job120988405767。已交原D只修canonical round/withdrawal夹具依赖，历史/回放/缺列负控不删、旧migration/生产XML不改；其余65/810真库结果保持绑定55不假套新SHA。完整JAR启动步骤尚未执行，Reader启动环仍待关闭。

状态更正：root之前按阶段名误将完整startup列为atomic之后，实际workflow535startup、544atomic。API明确Build/test=SUCCESS、full application startup=SUCCESS、atomic Python=FAIL。55b9/job120988405767原日志21:19:23确认packaged app ready、MySQL/RedisUP、缓存roundtrip、protectedHTTP401、jobs disabled；读边界启动P1在此源实际验证。后续compiled/privateMapper/JAR身份步骤确实SKIPPED，不能混成已通过；两旧Python夹具仍需D补齐。

D804d70bf已集成（仅一Python夹具文件）：旧历史行后先通过原runner执行canonical Round迁移，原字段snapshot不变、application_round两历史行均NULL；被测旧withdraw迁移/全部6原case/缺列/历史/replay断言不删，新缺列断言明确replacement_of_offer_id。生产XML/旧SQL字节不改，AST语法已解析，真MySQL待新完整候选验证。55b9完整后端原XML已回读：15459唯一case、14826PASS/633skip/0failure/error/重复，Reader4、Spring真实场景35、ASR屏障3、注销1均实过；实际全JARstartup亦过，后置compiled/JAR metadata被Python失败阻断尚未跑。摘要`55b9-backend-readback.json`，绝不套新SHA。

## 当前候选 b626（最后旧迁移夹具依赖已补）

完整冻结`b626f425625ccae793aa84a44147fe557c8df6ef`，分支`codex/architecture-day-final-b626-20261024`。标准 [40135082606](https://github.com/liboyang42-cpu/hengyin/actions/runs/40135082606)、65类/810声明真实存储 [40135087066](https://github.com/liboyang42-cpu/hengyin/actions/runs/40135087066)已启动。相对55仅改旧Python夹具依赖和证据文档，生产Java未再改；仍按同完整候选验证，不继承旧SHA绿色。旧55的810/810、35Spring/4Reader/3ASR屏障、实际JARstartupPASS和两Pythonerrors均保留原绑定。原8所有当前必要源码已交，不派无收益任务，只收本轮真实失败及后置compiled/JAR身份结果。all_complete=false，未合并/部署/生产DDL。

b626真实存储40135087066已最终SUCCESS；root逐类原XML/receipt回读：65唯一类810/810PASS，0failure/error/skip/重复、65CLEAN、候选/precheck/workflow SHA和类集合/计数均一致，全部a1不需重试，摘要`b626-real-storage-readback.json`。后端原XML身份哈希逐份一致：14826PASS/633环境门控skip、0failure/error/重复，ASR屏障3、reader4、Spring35、注销1均实过。完整JAR启动步骤亦SUCCESS；当前原子迁移Python步骤运行中，compiled/JAR身份待后续，不把存储绿色写成全部架构完成。

b626标准40135082606最终FAIL于privateMapper编译依赖门：原始证据/source/index已验证，三个原privateMapper消费者唯一正确；11条NEW_OWNER_DEPENDENCY_DIRECTION折叠5个owner方向（C city→ticket、G claim→B factDTO、B router→factDTO、B factstore→orderlock、B intents→factstore）。已交原F精确根因与合同，不直接扩grant。此前完整startup、88原子迁移Python、所有Java及30个其他标准job已PASS；negative/restored因该门SKIPPED。root实际JAR SHA/source/buildmetadata与compiled报告一致，摘要`b626-final-backend-artifact-readback.json`。当前唯一源码待办F；原65/810存储绿色仍严格绑定b626，不称本轮完成。

C e1f22单行常量解耦与F verifierd58288ed/tool4b3239完整增量已集成。F exact_directions仅10class pair/4方向，其他内部类/JDBC实现/反向/新环/缺raw继续拒绝；C peer方向未授。root六可信文件按原SHA256与donor逐个一致、两workflow ref及tool VERIFIER一致、旧baseline/commands/locking字节不变；F8定点Python控制PASS为协议fixture，不能代替新JAR/Java/DB。全部必要源码再次收齐，扫描后冻结同一新候选统一验收，旧b626FAIL及其通过证据不套新SHA。

## 最新冻结候选190a

`190a97098e9ba8c1db3d0439e19adf28c9848468`，隔离分支`codex/architecture-day-final-190a-20261024`。C peer常量单行和F精确10引用/4方向及严格负控已齐，root六可信文件/双pin/旧snapshot原字节核对，扫描84716bytes无泄漏。标准 [40137286127](https://github.com/liboyang42-cpu/hengyin/actions/runs/40137286127)、65类810声明真实存储 [40137293464](https://github.com/liboyang42-cpu/hengyin/actions/runs/40137293464)已实际启动；尚无本SHA验收结果，旧b626绿色不继承。所有owner冻结，不加无收益任务。只收同候选真实失败及最后negative/restored与JAR证明；all_complete=false，未合并/部署/生产DDL/微信/付费外呼。

190a实际新增失败：Mini shard1/job121001506357仅registration-ticket-application-source.test.js:51仍要求CityNode跨应用引用Ticket常量，与已批准peer解耦相冲突；交原C只补同一真实五分钟相等/signing/localPNG与禁止peer引用断言，原case不删。后端/存储原run继续，暂不重开整批，等其它实际结果与必要补丁收齐。

C83d390 single JS source-contract correction applied. Both actual numeric TTLs must equal300000 and each other; peer application reference forbidden; actual ttlMs/expiresAt usage asserted; original signing/permissions/PNG checks retained, case count unchanged. C direct Node file run PASS0skip. Root keeps remaining190a jobs running; no new full run before actual compiled/storage results. This test-only delta has no new same-candidate CI evidence.

190a full original receipts/XML independently read:65 unique classes810/810PASS, zero failure/error/skip/duplicate,65CLEAN, all attempt1, exact source/precheck/workflow SHA and count/hash binding clean; summary190a-real-storage-readback.json. Backend XML1934files/15459unique:14826PASS633environment-gated skip/0failure/error/duplicate, selected ASR3/reader4/Spring35/deregistration1 PASS; full app startup API stepSUCCESS. Atomic/compiled still original run. No production Java changes after190a, only C83 test-source correction pending final source freeze.

190a Backend job121001506266 finalSUCCESS: source Java XML14826PASS633gatedskip, actual startup22:19:59 health/MySQL/Redis/cache/HTTP401PASS, 88atomic migration testsOK, original compiled verdictPASS_SCOPED_COMPILED_REFERENCES, real negative/restored logEXPECTED_RED_THEN_RESTORED22:22:34. Full standard remainsFAIL solely original stale TTL source assertion and dependent mini aggregate; C83 exact fix already applied/one Node filePASS. Artifact download then independent SHA/raw/control readback pending, no all-system verdict. No new production source delta after190a.

190a original compiled artifact fully read independently: actual JAR SHA41d3bcaf1a611f5eefa7417106016f253186d747cb97e046ce83630ceeb52085/source/tree/run/attempt all match;292 original evidence file hashes verified; privateMapper verdictPASS with no findings; sdk-router/dto-facade/common-framework actual compiled negative raw refs present and BLOCKED, restored raw refs absent and PASS. Standard original failure remains ONLY stale Mini source assertion/dependent aggregate. C83 test-only source collected, no production change after190a. Freeze final combined candidate after scan; not all WP/Q/A/T/G acceptance or deployment.
