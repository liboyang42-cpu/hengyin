# e822 六项实际失败修补（SOURCE）

Parent: e82229ef85e58159078a42956560356074192541；tree ed211d0215b206a682697d9470307c0fb0181d53。
5 个 owned 代码文件：3 unit + 现有 JourneyStoryValidator/TopicPublicationContentGuard 的最小诊断接缝。
不改公共 helper、资金/运行消费、编译器、CI、SQL、权限或经济规则；不重跑既有验收。

| 原失败 case | 源首因与本次收口 |
|---|---|
| Declarations.formalBranchReusesTheExistingDiagnosticBeforeCallingCompleteFreeze | 原 fixture 只改普通 SELECT 返回 start/terminal，currentNodes 仍缺 terminal12，先触发不存在节点。同步同次 currentNodes 后，保留原未声明变量/type/path/cause及 freeze 零调用断言。 |
| Declarations.nestedJourneyStillRejectsInactiveDispositionNodeWithOriginalSceneDiagnostic | 原配置 disposition12 没有任何非fallback ending when引用，合法结构校验先拒绝。补真正 sys.outcome.12 when 及唯一 fallback，保留 status0 和当前作用域拒绝、ServiceException/scene[0]路径，不放松 validator。 |
| Diagnostics.foreignJourneyChapterKeepsServiceExceptionAndExistingReferenceExplanation | JourneyStoryValidator 原开局章节越域消息只有 chapterId，缺 openingChapterId。仅该原字段校验失败附 path=openingChapterId，保持 IllegalArgumentException 与原说明/cause。 |
| Diagnostics.nestedSceneReferenceKeepsTheActualSceneIndexInsteadOfPretendingTopLevelStory | 同一 opening 字段诊断；原 guard 的准确外层 scene[0].journeyStory 定位保留，ServiceException 保留原 cause，新增 cause/原chapter999断言。 |
| Diagnostics.foreignCompletedNodeReportsTheOriginalTemplateAndRetainsScopeRejection | 生产 AdvancedGameConfigValidator 本就聚合成 nodeId=[999]，原 scalar contains(nodeId=999) 与真实合同不符。未改生产该闸，改核完整精确原错误字符串，包含 NODE_COMPLETED、完整foreign ID列表、node2/template200/topic7字段路径；作用域拒绝类型和零副作用断言不变。 |
| Sale.storyAndNameEdit_keepsSaleReview_andRefreshesThePendingTask | 原旧章节集为空而 DTO 插入一章，真实 TopicSaleTerms 保留章节条款多重集，因此正确触发重审；该 fixture 不是“只改文案”。补独立冻结的原章节/节点和真实删除回调，四原条款列明确为0/1/PERK/1，DTO从原行复制完全相同值，不按数据库缺省或当前角色推断。原5/8元、never invalidate/times1及审核刷新断言均保留，新增全旧/新条款指纹相等/不同和真实金额断言。 |

生产变化只有：openingChapterId 失败附字段定位；Guard 把原验证异常作为现有 ServiceException 的 cause。
没有把拒绝变warning/成功，没有输出答案、原配置或敏感输入；不改变有效配置/快照字节/当前读合同。
手工沿实际 publish -> ContentGuard -> JourneyStoryValidator 核原类型/字段/scene/node/template；既有 JourneyStoryValidatorTest opening 引用用例保留原异常类型和消息可识别部分，无需跨owner修改。
声明、诊断和开售原 @Test 方法名称/数量不变；未删 case/disable/skip/经济或授权负控。错误断言附实际 message；已纠正的聚合消息用完整精确匹配，不用通配放宽。

手工核当前 types/signatures：两个 Mapper deleteById(Long):int；章节4setter Integer/Integer/String/Integer；TermsMode.PERK 为原 String 常量；JUnit wildcard/全限定断言和 Throwable.initCause 均复用现有API。
真实 Spring/当前内容 fixture与原 compiler不被stub掉；本包不设置事务active标志、不创建新数据库或运行外呼。
source-manifest.json 给每项公共 e822 前像与最终后像 SHA1/SHA256。
owned.patch.b64 解码仅5代码文件，SHA256 b8c8cd1fc6dea5712e4d45e0df9006f8e9d346f826dabd288e947fdbe21784fc；文档不在此代码 patch hash内。

实际执行仅读取源码、编辑、git diff/apply --check、源身份哈希及有限敏感签名检查（未命中；无完整gitleaks工具）。
Node/源码解析检查/导入检查/Java/编译/JUnit/MySQL/H2/HTTP业务请求/故障/CI全部 NOT_RUN。
root提供的原XML作为定位证据，没有重新下载大日志或把原编译/其它绿色结果挪给本候选。
