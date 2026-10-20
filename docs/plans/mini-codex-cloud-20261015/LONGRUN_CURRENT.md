## 当前f8验收分层：HTTP／Funds通过，完整Backend失败，单槽定向修复

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

## 本轮三队真实未完成项与公开证据

[紧凑执行回执](THREE_TEAM_REMAINING_RECEIPT_20261018.json)：CORE已确认未交源码0；保留8类23项真实MySQL及补充AI存储、独立B05的实库/运行时验收缺口，38项反证与NPC/photo两项NO_SOURCE_CHANGE不再派工。EXTRA E02-13据公开PR231 `b0dc1019f9d2485dc8ea8ca74184eb6728e4474a`原owner已实现、控制候选未整合；E04源码已交，剩2类8项真库与迁移未验，不重做原实现。F3原范围没有已确认遗漏；28普通PASS、d592仅编译、11真库NOT_RUN分开。

既有公开OWNERSHIP分别列日期聚合Mapper为Foundation B、报名caller为Foundation A；原源码路径与后续PR4提交见回执。最大日期缺真实引擎返回、紧随warnings及1/1/2原指标结果；RR缺实际隔离、合法第二事务旧视图，票互斥反证未推翻。原公开交付及现有材料均未提供这两项所需真实证据，NO_EXISTING_EVIDENCE；当前适用入口没有这些真实取证覆盖，不新增入口/网络/CI，保持NEEDS_EVIDENCE/NOT_RUN。没有新增源码或grant，不合并部署。

## 2026-10-18 两项既有合同已确认：NO_SOURCE_CHANGE

两项真人选择已由本地协调者明确转交、C00实际签收并更新原剩余表及owner记录：[决定与签收](CORE_COMPATIBILITY_DECISIONS_20261018.json)。

- OWN-C04-01：保留同玩家／同NPC／同请求号重放第一次结果，新问题换新请求号；原scope检查与既有terminalReplay回归合同不变。不加内容指纹、列或迁移，不推测旧回执身份。收口 **NO_SOURCE_CHANGE**，不计新增修复BUG。
- OWN-C05-22：保留照片AI异常退还照片池次数，沿既有照片合同；不改为通用网关UNKNOWN占用，不改降级／放行／奖励。收口 **NO_SOURCE_CHANGE**，不新增政策源码、迁移或付费调用。

CORE这两项待真人裁决数现为 **0**；历史BLOCKED提案保留，原owner与文件锁不变，不唤起作者重扫或测试、不复跑旧PASS。当前已确认可行动未交源码仍0，不造任务。四夹具源码已交；B05仅test-compile PASS／实际0，E02空间自然恢复后已沿原212完成test-compile PASS／实际0，原ENOSPC保留历史；各3项真MySQL仍NOT_RUN，现有入口不新增，验证状态未受本决定改变。下方旧“待真人选择”只为历史，已由本段覆盖。

# 当前恢复入口：四文件源码完成，两个独立候选均仅编译通过

四文件 grant、逐作者 ACK 与源码交付均已完成，原作者停写、独占锁保留。C00已核读 PR24 固定证据 [`8fdb9fdb72975f7617a4ac70eb4e4eeacd27c3ac`](https://github.com/liboyang42-cpu/hengyin/commit/8fdb9fdb72975f7617a4ac70eb4e4eeacd27c3ac) 的两份 README/summary；E02后续恢复编译的新证据为 [`90b81ee7af715f0c72598a7b7a9f59e235910ea6`](https://github.com/liboyang42-cpu/hengyin/commit/90b81ee7af715f0c72598a7b7a9f59e235910ea6)，C00亦已核读其README/summary。以下为当前结果，后文较早“均未编译/待 ACK”仅为历史快照。

- CORE/B05 源码 `2ac55d20a9f4932be8d411e53191c69e5141772c`：现有212 `test-compile` **PASS**，guard0/completed/BUILD SUCCESS，指定 fresh class1；无 Surefire XML，实际测试 **0**，原3项真实MySQL仍 **NOT_RUN**。见[B05编译证据签收](B05_2AC55_TEST_COMPILE_RESULT.json)。
- EXTRA/E02 源码 `6c44636974220ca9876386ee638c99981488db42`：4328文件再次逐hash一致，资源自然恢复后沿原212于02:23:12.350784Z—02:25:47.051161Z完成 **test-compile PASS**，guard0/completed/BUILD SUCCESS，三个指定fresh class及hash已核；无Surefire XML，实际测试 **0**，真MySQL3项仍 **NOT_RUN**。精确自有容器已确认清理，当前无活动Java。见[E02恢复编译签收](E02_6C446_TEST_COMPILE_RESULT.json)。原8fdb ENOSPC准备失败、原summary及远端preparation-result-disk-full-020540Z.json完整保留在[历史准备回执](E02_6C446_PREPARATION_BLOCKED_RESULT.json)，不再作为当前磁盘阻塞。

原空间阻塞已自然恢复并完成未完编译；无需源码修复槽、不再重试或Cloud替代，不新增入口/网络/CI。两个候选仍为同父26d的独立分支，未组合成四文件集成候选。原失败历史保留；无真实MySQL/Redis、HTTP、设备业务、迁移、合并或部署验收。两项CORE业务选择均已由真人确认保留现状，按CORE_COMPATIBILITY_DECISIONS_20261018.json收口NO_SOURCE_CHANGE，无待裁决源码。无需EXTRA为本回执重复改本队文档。完整索引见[四文件批次](FOUR_FIXTURE_SOURCE_BATCH.json)。

## 先前记录（按原SHA保留历史，不覆盖上述当前状态）

# 当前恢复入口

E02协调者已将原child f74的完全相同3个补丁blob串行交到现有PR36：完整rolling源`6c44636974220ca9876386ee638c99981488db42`，父`26d8245b458d18497eb9040a4c7d9f1509b41bb1`。C00已实际读完整commit并逐blob SHA核与原child相同；没有新增源码或测试结果。CORE/B05 `2ac55d20...`是同父的另一单文件候选，未静默合成四文件分支，仍各STATIC_ONLY/实际0。下文f74保作原作者child交付来源，以本行rolling完整SHA作为EXTRA完整候选。

四个旧夹具均已在公共grant fa3f及逐作者ACK后源码交付：[批次](FOUR_FIXTURE_SOURCE_BATCH.json)。原E02三文件`f74dd9be0c17a17db506ee8e0c46c60804a1a785`（父`9a27f6b0d8fe532320cbe646775464cf49ba1d10`，PR30）；CORE/B05单文件`2ac55d20a9f4932be8d411e53191c69e5141772c`（父`26d8245b458d18497eb9040a4c7d9f1509b41bb1`，独占branch）。总共4文件各仅+1空表行/-0，逐文件移除新增行即恢复原文；原gate/6方法/全部断言和created清理不变。原E02已明确B05冻结交接，双方完成停写/锁保留。均STATIC_ONLY、实际测试0/未编译/真库未验，不把6143普通4PASS或b034普通8PASS套用。无新生产BUG/新入口/合并部署。

## 四文件精确交付

| 责任 | 公共源码/直接父 | 原作者接收与范围 |
|---|---|---|
| 原EXTRA/E02三夹具 | `f74dd9be0c17a17db506ee8e0c46c60804a1a785` / `9a27f6b0d8fe532320cbe646775464cf49ba1d10` | E02_01Test/E02_11Test/E02_06Test，各一行；原作者亲自读完整fa3f后ACK开写并停写 |
| CORE/B05单夹具 | `2ac55d20a9f4932be8d411e53191c69e5141772c` / `26d8245b458d18497eb9040a4c7d9f1509b41bb1` | B05CommunityVisibilityMySqlIT仅一行；原测试创建者EXTRA/E02已冻结交接，新接收CORE已明确ACK，不冒称原作者在线 |

完整路径/基线hash/门禁/文本前提见[精确grant](FOUR_FIXTURE_MINIMAL_GRANT.json)、[E02交付](EXTRA_E02_THREE_FIXTURE_SOURCE_DELIVERY.json)、[B05交付](CORE_B05_FIXTURE_SOURCE_DELIVERY.json)。45开放PR全分页仅原PR30和集成PR36触达，无第二writer。新4文件不含已完成6c6的E02_25MySqlIT；未改任何生产/依赖/SQL mapper/gate/方法/断言。各自created保护清理本就正确，不改以免误伤别库。

新夹具均SOURCE_DELIVERED_UNVERIFIED/STATIC_ONLY，实际0/未编译/真实MySQL NOT_RUN。仅局部建表字符串修正不需要重跑旧普通4项来冒充真库。原作者源writer当前0，锁保留；EXTRA协调者维护本队rolling源码/三账，C00只全局账与CORE精确B05分支，未强改对方分支/合并代码。双方内部协调走私有聊天和授权读取，不发公开PR内部协调评论；非敏感源码/授权清单按用户要求持久化公共GitHub。

## 本次补齐的独立证据

[公共证据索引](RECENT_EXTERNAL_EVIDENCE_ACK.json)逐份README/summary已由C00实际读取，未取原VPS日志/XML重解析、未Cloud补跑。

| 固定源码 | 实际范围 | 结论与保留历史 |
|---|---|---|
| 923170abd8e468d62eb8404b16b990609a4da42a | 6类35=28P/0F/7E/0S | H2别名夹具导致2类7异常，原失败和两次0项内存拒绝保留；不是35项均失败或7个生产BUG |
| b034a4aabdacffbf7a2ef4f400b56eb2c6eed6cf | 原两异常类8实际PASS | 原E02已ACK并释放修复槽；不新35全跑或继承旧28 |
| 530e72281a9bd1a443f58a21119367d55eca3623 | E01/E03直接影响4类60实际PASS | 原59静态漏一个全限定@Test、初始收集器NOT_PASS与计数审计保留，无源码改/重跑 |
| 6143c37f3c32753bd22208ace9c4d3c4520b9fd6 | E02_03Test4实际PASS | 仅H2生产XML合同；不是真MySQL/HTTP/完整业务 |
| 6c6f82d7d9049f3f2d65f609be6b01c4a5643ec4 | E02_25MySqlIT单行前提 | STATIC_ONLY，实际0/未编译/真实MySQL未跑，不继承6143的4PASS |

E02旧18声明遗漏6CsvSource调用，已更正24；再加E04普通11为原35实际选择。只算@Test注解不是执行调用数。各SHA成绩不跨候选累计；旧CORE5752定向31、adf51失败、EXTRA1bfa2、第三d592编译0/真库11未跑等历史继续原证据保留。

## 可继续范围与验证约束

E01-13/E03-04/E02-03合同已由后续真实候选交付并各有60/4普通证据，不再说待产品裁决。复用现有CORE清单，当前合同明确+精确写权可继续生产/架构项0，NPC不同命令请求身份和AI发送后计次政策仍未决，不泛审计或补任务凑数。F3 READY0：最大日期缺真实MySQL DATE_ADD/warning/原指标对照且聚合Mapper表外；RR缺实际隔离与合法并行caller，不由当前packet直接改生产。

本地root统一现有212必要新源验证；Cloud不Java/Maven/Node/编译测试/lint/手动CI/DB/HTTP/VPS。用户已定“保持现有入口，不新增”，新真库入口方案关闭、不再催批，212/CI/网络不改，未覆盖实库继续NOT_RUN_EXISTING_ENTRY_COVERAGE_GAP。既有自动CI自然运行不等于当前候选有实际结果。

既有三项身份/券决策源码链A dedb→E02 13be→B9231保留，各候选/层级分别登记；B未来history_id迁移仅源码，旧行NULL不回填，未执行迁移/合并部署。Claude UI/新功能、原生iOS独立、Flutter排除；最多6个gpt-6.1-sol/队，失败仅原作者1修复槽。运行HTTP/真实身份、设备业务、跨候选集成仍未验，TEST_DEPLOYED=NO。见[源账](LONGRUN_SOURCE_OWNERS.json)、[余量](LONGRUN_REMAINING.json)、[验证移交](BACKEND_VALIDATION_VPS_HANDOFF.json)。
