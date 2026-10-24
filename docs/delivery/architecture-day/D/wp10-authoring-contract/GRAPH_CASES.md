# 旧新内容图真库对照合同（计划32项，尚未编写/运行）

旧源固定864e原门面，新源由root后续授写/集成后固定。每项两边运行完全相同business body/schema，显式模式默认差异单独记录；旧源硬检查新graph/guard/reader不存在，新源硬检查真实owner存在并被Spring代理装入。不得只改标签运行同一实现。暂定case名供root登记，未在registry添加或派发。

每项均比较返回/根异常、所有相关真实表行、原行完整不变或明确合法变化、mapper写序及关键读/领域调用序；不能只断言节点数。失败后回滚真实内容表，清理独立核对；日志出现receipt不代表XML/cleanup通过。原协作者零行、XP/audit捕获、runtime可选依赖等若观测到原风险，记录为相同行为，不写成已fail-closed。

| ID / 拟定case | 夹具与必须证明的合同 |
|---|---|
| G01 v1CreatePersistsLegacyGraphInSubmittedOrder | V1创建，多章/同sort/每章多节点；不物化blocks，chapter/node按输入顺序落库；chapter shape与逐node模板校验仍交错写入；collab/XP/ticket位置不变 |
| G02 v2CreateCommitsOpeningEndingAndAllReferences | V2经典定向，opening+ending、blocks、node/template/NPC、story/runtime/BRANCH_GRAPH；真实生成身份全部一致；template→story→route→runtime→interaction顺序 |
| G03 v2FreeExploreWithoutBlocksKeepsLegacyWriter | V2自由探索无blocks，走原legacywriter，旧节点资格/生成字段不变 |
| G04 v2FreeExploreWithBlocksUsesCompiledWriter | V2自由探索显式blocks，原完整预检/物化路径，无私自新增产品类型限制 |
| G05 v1RebuildDeletesFlowBeforeCollaborators | 可重建主题；trace必须先node/chapter删除、再collab删除、再新章/node写入；后续重存collab在XP后、ticket前 |
| G06 v2InvalidCompiledReplacementKeepsOldGraphAfterRollback | 使用纯compiler及E4校验已通过、仅写入阶段模板资格guard失败的有效blocks；V2先执行collab删除，再二次compile/guard失败；旧flow尚未删除，整个事务恢复原collab。语法错误可能在统计处第一次compile就拒绝，不能误报成已执行collab删除 |
| G07 v2CompiledReplacementRebindsEveryIdentity | V2经典定向完整重建；预检后删除旧flow，hidden→template→unlock→story→runtime→interaction→route顺序全部保持 |
| G08 v2NonCompiledReplacementKeepsLegacyDeleteOrder | V2自由探索无blocks；按原非compiled分支删除并保存；不统一成G07流程 |
| G09 repeatedTemplateUseClonesOnlyOncePerRebuild | template含旧node/sysvar引用，多node复用；调用现有copyForContentOwner原语，独立新template、cache身份和原模板原值严格一致 |
| G10 foreignTemplateRejectsAndRollsBackExistingWrites | foreign/deleted/unapproved模板代表分支；比较原异常及已执行后回滚的statement序，不误称所有guard都先于topic/章写入 |
| G11 privateDraftTemplateKeepsOriginalBundledEligibility | 同作者私有草稿四字段原判据；V2草稿/专业组合允许，simple直接发布拒绝；不改变权限/补默认 |
| G12 omittedStoryKeepsOriginalContentAndRemapsChapterIds | 编辑缺story，保留原文本/未知字段，重建opening/ending及chapter/node引用；原非目标行完整不变 |
| G13 hiddenAndUnlockRelationshipsSurviveReordering | 隐藏node、新node、原前置node重排；只保留既有隐藏，不取消隐藏；依赖缺失的异常和回滚另在同case受控子场景记录 |
| G14 runtimeBindingsUseCorrectOldAndCreatedClientMaps | 已有真实node绑定+新增clientKey绑定；clientToNew与createdClientToNew不混用，持久化不残留client alias |
| G15 duplicateIdentityRejectsAtOriginalPoint | 重复old nodeId/clientKey及超长key；原异常根与插入后触发位置一致，拒绝不留下新图 |
| G16 outcomesReaderKeepsAdvancedPreferenceAndDefaultBranches | 真实模板branch terminal、d20、preference及默认完成、隐藏node；reader内容/遍历序、优先级、非法JSON原异常；用原publish helper薄delegate读回但不执行发布 |
| G17 chapterGuardPreservesRevshareShortCircuitReads | 非REVSHARE不读topic/slot；REVSHARE先topic再slot，ClubDirect判断不变；category仅启用招商才查；读取trace精确比较 |
| G18 chapterGuardKeepsAudioRecruitmentAndMethodOrder | audio超长、招募枚举/category、allowedValidationMethods、maxXP代表分支；不能提前查询Category或把所有guard合并重排 |
| G19 chapterGuardKeepsFeeAndPerkShape | ClubDirect/非ClubDirect、REVSHARE正数两位fee、其他档拒fee、perkMinValue；原异常/字段均相等；不执行任何收付款 |
| G20 invalidGeneratedIdentityRejectsAndRollsBack | 真实chapter/node mapper受控拦截返回0或缺generatedId，保持原检查与异常，不放宽失败分支 |
| G21 secondChapterFailureRestoresOriginalGraphAndCollaborators | 已删旧flow/collab后第二章写失败，旧章节/node/collab完整恢复；新template/role等实际新增亦回滚 |
| G22 blocksUpdateFailureRestoresDeletedAndInsertedRows | 真实mapper后受控blocks更新失败；Spring外层事务恢复旧flow，非H2证明 |
| G23 templatePatchFailureRestoresCloneAndNodeBinding | 新克隆模板advancedConfig或node.templateId patch失败，原模板不变、新克隆及新图回滚；两失败点独立子场景 |
| G24 storyPatchFailureRollsBackWholeReplacement | story最终patch失败，旧图/story/协作者恢复；不只比较异常 |
| G25 runtimePatchFailureKeepsCreateAndRebuildMessages | create与rebuild两个子场景分别保留“主题运行配置保存失败/主题运行配置重映射失败”，真实内容全部回滚 |
| G26 routeVersionConflictRollsBackAllEarlierWrites | requestVersion不符、最终CAS返回0各子场景；BRANCH产品限制/版本/图解析顺序不变；不新增fence或重试 |
| G27 interactionCompilerKeepsWriteAndNoopBehavior | 既有runtime编译器真实算法；相同值/null结果不写，变化才写，更新失败根异常相同；可选依赖缺失的原return单列风险 |
| G28 xpFailureStillContinuesAtOriginalPosition | XP受控抛异常，原catch后仍collab→ticket→后续原流程；不改成业务回滚或成功宣称XP已分配 |
| G29 ticketParticipantFailureRollsBackCompletedGraph | 原票务调用处受控抛错，真实graph/template/collab与外层topic回滚；票务若用stub/事实adapter明确标注，不能声称真实资金mapper通过 |
| G30 reviewParticipantFailureRollsBackCompletedV2Graph | V2原组合审核边界抛错，完整新图/原图替换与collab回滚；原V1 audit catch继续的不同合同在原141保留，不强行统一 |
| G31 draftSaveKeepsVisibilityAndExternalActionsUnchanged | 创建/编辑保留草稿；无新审核/发布snapshot/活动壳/通知动作，无真实WX；topic字段、flags、条款、票务边界trace一致 |
| G32 callerRollbackAfterFullGraphRestoresAllContent | 由额外外层Spring事务调用真实门面，返回后抛错；证明新graph REQUIRED参与原事务而非另提交；真实mapper全部回滚 |

32是顶层case登记目标；含子场景的case要求逐子场景记录receipt，不能只运行一个分支冒充整组。fixture设计阶段如无法保持足够可读的独立断言，可在root登记前展开为多个确切@Test并同步名单，不静默改期望计数。旧141完整保留，不用新增表征取代旧票/活动/发布行为断言。

收据建议包含 `owner,caseName,scenario,outcome{class,message},tables,mapperStatements,orderedBoundaryTrace`。顶层32case完整名单+子场景名单必须两边相同；每个receipt有独立原行assert。surrogate IDs要求同schema/种子下精确相等，随机checkin仅字节归一化并另验合法性/唯一性；audit time仅本次新增/更新时刻可归一化。任何额外字段/行/UNKNOWN/失败不可丢弃。

执行仍由root现有GitHub/VPS通道承担；D不申请凭据、不启动本机Java/DB、不派CI。需先有真实fixture源码审查和原/新源码身份，再执行。当前没有新增IT Java/SQL文件、registry或业务代码。
