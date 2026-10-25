# H：347bcd 原生玩法/冻结消费夹具修补包

结论：SOURCE_ONLY，全部测试/编译/解析控制/Java/数据库/CI/供应商 NOT_RUN。仅修本轮原日志对应的夹具合同，不新增玩法、框架、表、权限、费用或生产 fallback。本提交不代表 23 个案例或任何整批已通过。

parent：347bcd0226c3bed71616e38ea7c1391fc76efe5c
parent tree：37a46f313f20fa33112b7cc5482c6e86baa4d66c

依据为同源 run40220558637/job121251116626/artifact12609615426 的七类实际失败，以及 storage121252559749 的 GameplayReskinMySqlIT.currentForPublication 缺 topic reader。179 是原XML35类中的重复首因总数，不在本包拆成179个BUG。原 source compile/SBOM 成功、Clm8或客户端旧绿不转签本包。

## 按实际合同修补

| 原组 | 已交源码/根因 | 原保护 |
| --- | --- | --- |
| Advanced 两个完整 ObjectNode 比较 | 原 expected 的 long ID 是 LongNode；原 state_json 持久往返会将小整数读成 IntNode。仅把完整 expected 也经过同一 ObjectMapper.write/read 形态，继续整树 equals | 不删 originalNodeContent，不只比标题/字符串片段；原 session/template/node、独立局、深拷贝、author config 不得伪造及重开保留全部断言 |
| GameSharedRouteQueryTest 线索 | 使用真实 fresh publication 四参 freezeKeys，clue 按原80主题归属，保原完整 stateKeySnapshotVersion:1 | 仍拒读 live registry，原局优先，不填旧声明 |
| GameplayQaPickTemplateReuseTest 三例 | authored act 缺现行必需 completionRule 对象，按原 schema 配 ALL；补真实 Spring DataSourceTransactionManager/TransactionTemplate 的写事务及原 parent→chapter/node/template/声明 current read 模型 | 不松 validator，不添加第二 QA 类型；原问题、答案、费用、重试、instance、receipt、proof、跨主题隔离断言保留 |
| GameplayDraftCopyTest 原生复制 | 正式 freeze 进入实际 Spring JDBC 事务；当前 parent/原节点/模板/章节/声明由原fixture模型读回，补原线索完整元数据 | assertThrows 仍精确 IllegalArgumentException，仍断言“完成证明”拒绝及本主题重选后可发布，不接受泛异常或空冻结 |
| GameplayReskinRuntimeServiceTest / GameplaySupportMatrixRuntimeTest | 两测试源码不改；它们已经通过 GameplayReskinFixture.publish → TopicGraphTestWiring.wire/shared 的正式 owner。共因在原共享 fixture，完整窄 review.patch 已提供 | 原7+7失败路径和既有正负控/六个参数输入、资源扣费、防重、上限、结束/回退/挑战断言原样保留 |
| ApiPlayPuzzleHintTest 普通无局/无模板 | 手工发布体缺真实 producer 的 routeMode:LINEAR，原 TopicRouteModePolicy 按无完整标记的旧分支处理使未报名节点为空。补该字段、当前在架行事实、合法无模板 null/空presentation形态 | expected total=2、不读runtime、不建局、不写progress、不泄答案/未用提示、原章节音频、原非模板身份断言保留，不改读侧分支安全政策 |
| GameplayReskinMySqlIT | useSql 后，用原真实 SQL node/template/chapter/topic Mapper 构造完整 TopicStateKeyRefService，经原 set 接到实际发布 owner | 同数据源/原事务代理/current reader，不手造表/支付或内容事实，不改脚本、DDL、cleanup、归属/资源/回放断言 |

## 必须串行应用的共享夹具 hunk

shared-reskin-fixture.review.patch.b64 只针对 GameplayReskinFixture.java（parent blob f11d79ab9100ffaf9c60004218c6b86161c074eb），**没有直接修改该非owned Java**，交root串行应用：
- unit current reader 注入原 topicMapper；chapter/node/template/声明 FOR UPDATE 查询返回同一fixture原topic范围的模型，不统一 ack/空冻结。
- 沿现有 useSql 的 Spring DataSourceTransactionManager + TransactionInterceptor + ProxyFactory 机制，unit 用显式 DataSource/Connection doubles 为原 authoring/keyService/runtime 接真实事务代理；没有 TransactionSynchronizationManager.set*、没有 mock manager 返回成功或只设 TxActive flag。
- keyService/register、正式创建/更新/发布、运行命令仍执行真实 owner/规则/helper；发布字符串由实际 compiler 产生，费用/回执/结束算法未复制。
- unit JDBC与Mapper均是明确的 doubles，不是MySQL锁/rollback证据；SQL useSql 仍用原真实 DataSource/Mapper/TransactionManager，owned IT 另将其缺失 topic reader 正确注入。
- 不修改 D 的 TopicPublicationTestWiring；保留既有 wire/set/get/TopicGraphTestWiring 调用。D同轮修补须保留已有事务代理/真实MyBatis mapper，root按其最终postimage串行合成。

直接 QA/Draft 夹具调用 route compiler，故在各原测试类内用原 Spring JDBC manager/TransactionTemplate建立实际事务，不依赖将未来 helper 默认变成宽松成功。Connection doubles 没有外部连接；Spring负责begin/commit/rollback/close，Draft finally 还验证原连接close。真正 SQL 用例继续原真连接。上述均为源码，不是本轮运行证据。

## 所有权、调用者与交付

6个 owned 测试文件实际改动；2个 owned 原测试保持逐字不变并列入后像；1个共享 fixture 仅窄 review。没有生产源码改动，没有改 D shared helper、E publishing guard、G结算、C/F入口或A资金。
- 基础呈现链：ApiPlayProgressController.nodes → PlayEntryApplication → PlayNodeQueryApplication / TopicRouteModePolicy；此次只纠正手工发布夹具。
- 原小游戏：AdvancedGameRuntimeServiceImpl.start/restart → state_json.originalNodeContent；此次只纠正测试expected的持久化数字节点类型。
- 发布链：原测试 → route.compileTemplateRules/freezePublication 或 CmsTopicServiceImpl真实authoring/publication owner → TopicRoutePublicationCompiler.current content → TopicStateKeyRefService。
- 运行链：GameplayReskinFixture 原 IGameSessionRuntimeService proxy → quest/facility/item/challenge/plan；原收费/资源/回执/UNKNOWN/proof不修改。

owned.review.patch.b64 是相对parent的完整owned delta；shared-reskin-fixture.review.patch.b64 是共享接缝。先合D同轮公共wiring，再由root应用这两片并核9文件POSTIMAGE.sha256（两未改测试hash保持parent）。PARENT-BLOBS.json记录原身份。源方案不依赖新增API/表/依赖。

所有原测试方法、参数化输入、金额/actor/scope/次数/UNKNOWN/原身份/清理均保留；未删除断言或改成更宽错误类型。只有完整JSON expected从原对象改为同一持久JSON往返的整树，仍比较所有字段。测试成功、真实SQL并发/锁/事务回滚、实际HTTP/供应商与最终装配一律 UNKNOWN/NOT_RUN；root集中按影响复验。不打开新审计或全仓回归。

工作在 /workspace/hengyin-h-game-fixtures-347 独立公开源码快照；原H checkout及他人未交源不回退。共享fixture只在 shared-review/after 快照中准备窄补丁，不改其他owner工作文件。此为完整源码目的包，不是以文档/空补丁代替修补。
