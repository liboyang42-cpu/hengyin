# 实际调用者与所有权

本清单是当前包的源码调用关系；不是测试运行或全仓审计。文件清单/精确 postimage 在 POSTIMAGE.sha256。

| 原入口/调用者（未改 public signature） | 修改的直接 owner/端口 | 保留的事实与顺序 |
| --- | --- | --- |
| ApiPlayEncounterController.resolve；PlayJourneyStoryServiceImpl 与 Advanced 原 Encounter 委托 | PlayEncounterServiceImpl | 原当前节点/topic、原个人局；原局 title/story/media/config，原叙事/提示/白名单 |
| ApiAdvancedPlayController.start/state/act；PlayNodeCompletionApplication.completeGate/reward ceiling | AdvancedGameRuntimeServiceImpl；IAdvancedGameRuntimeService.queryDefaultNodeContents；GameRuntimeSessionMapper + XML | 既有小游戏原 templateId/config/attempt；原 clock/random/attemptId、proof/CAS/reward/外部调用顺序不动 |
| ApiPlayProgressController.nodes / routeState | PlayEntryApplication → PlayNodeQueryApplication / PlayNodeContext；ITopicRouteRuntimeService.queryNodeContentSnapshot | 原入场 GET 的建局/共享刷新和角色分配仍属 Entry；新纯投影用本请求原内容，public/spectator 原准入 |
| ApiPlayProgressController.basic answer/checkin/sensor | PlayBasicCommandApplication → PlayNodeContext | 当前 scope/下架/已完成事实/可信 proof；原完成 owner 收当前原 node |
| ApiPlayProgressController.preference | PlayPreferenceApplication → PlayNodeContext → 原 PreferencePlayService | 原 routeAction/version/lead/到店/完成回执，原费用与奖励 |
| 原 PlayPhotoCommandApplication / PlayPuzzleCommandApplication 的 effectiveTemplate / frozenTemplateView | PlayNodeContext（原方法签名保留） | Photo UNKNOWN/原意图和 reveal 授权/收费不改；不改 provider 或写真事实 |
| PlayPuzzleCommandApplication.requestHint / unlockHint → NodeHintUnlockApplication/IPuzzlePlayService | PuzzlePlayServiceImpl 的 originalHintTemplate；纯 nodeAnswers | 原 hint request/attempt、ledger→debit、拒绝零经济效果、rollback；公共手装测试夹具 review hunk 补同一 runtime |
| TopicRouteRuntime 原三处建局/重开 seedCharacter | ThemeCharacterService.seedRun 新 snapshot overload（旧签名保留） | 原最近局/character 单调台账、persist_scope/clamp，不清值 |
| ApiPlayCharacterController.card | ThemeCharacterService.card/cardKeys | 原 actor/topic 安全准入；原局定义+规则，旧无完整声明保旧路 |
| ApiPlayJourneyReadController.casebook | PlayJourneyReadServiceImpl → JourneyInitialState.visibleKeys/frozenDefinitions | 原变量/tag/关系/动作/奖励/日志事实，无写 |
| GameSessionRuntime.clueLabels 与 TopicRouteRuntime.failCostTagLabel | JourneyInitialState.frozenDefinitions | 仅定义来源变更，运行值与原判断/结算不变 |
| TopicRouteRuntime.queryNodeContentSnapshot → GameSessionRuntime.querySharedRouteSnapshot | 原 IGameSessionRuntimeService 新 narrow default + 实现 | 原 activity/topic/actor/有效报名/TEAM/RUN/epoch/映射；纯读/defensive copy |
| TopicRouteRuntime 无主题局 metadata fallback → Advanced.queryDefaultNodeContents | 复用已有 Lazy Advanced 依赖，Advanced 仅 Mapper 读不回调 TopicRuntime | 本人/当前有效队伍、原 default 实例；每节点原个人优先，否则最近队伍，EXISTS 防重复，无锁/创建/重放动作 |
| TopicRoutePublicationCompiler 原 freezeKeys（三/四参） | G 保留 writer；H 仅 JourneyInitialState 新读取与 visibleKeys | H 不覆盖 producer；nodeAnswers/stateKeys additive，不补历史标志 |

纯读返回 JsonNode 均为服务端内部输入，调用者不得直接下发。原公开 DTO/controller 签名、响应外层、玩法/proof 支持矩阵不变。接口新增 default 方法保持旧 fakes 的源码兼容；真正实现由现有 Spring bean/MyBatis owner 提供。GameRuntimeSessionMapper 新 SQL 只供 Advanced owner；没有新 Mapper 注入 Query/Web 或 TopicRuntime。

现成 tests/fixtures 详细范围见 IMPLEMENTATION.md 的 45 项声明表；13 个测试源属于 H，PlayNodeAccessTestFixtures 仅接线 review.patch 由 root 串行应用。所有测试、编译和运行装配 NOT_RUN；接口兼容的真实编译结论 UNKNOWN。
