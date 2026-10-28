# A：90e185 最终运行消费闭环核对

结论：在下列指定生产调用链及直接接口/Mapper/夹具范围内，**未确认需要新增生产补丁的可触发缺陷**。没有为了产生补丁而改实现、降低断言或放宽授权/proof。此结论不是全系统无 BUG、实际装配成功或验收通过。

- 审查候选：`90e18550ae75e83641e85bf4bb218f08922c7d3e`。
- 候选 tree：`55ad70d6e20746d7b47ce368ca1d08f2014b59ff`。
- 独立工作树：`/workspace/hengyin-a-runtime-final-consumption`；独立分支：`codex/architecture-day-a-runtime-final-consumption`。
- 资金/恢复工作树及 `197ac0ca8f3f98970b4d2701097a3e8da839fdcc` 保持冻结；本片不修改 Java、XML、旧测试、共享 writer、CI 或 registry。
- 已读取指定 ACTIVE-WORK、CURRENT-STATUS、完整复用施工方案、本轮施工交付与验收，以及 H `TG-runtime-consumption/CALLERS.md`、IMPLEMENTATION 和具体生产源码。协调文件的旧历史 PASS/任务状态不当作本候选运行证据，最新本轮派单和源码为本次范围。

## 已核对的完整消费链

下表行号绑定上述候选，路径均为现有生产源。

| 实际调用链/落点 | 源码核对结果与边界 | 直接配套测试源码 |
|---|---|---|
| `ApiPlayEncounterController.encounter` → `PlayEncounterServiceImpl.resolve:92` | 本人原 scoped route session；当前节点仍须存在并属于主题。新原内容从原 `nodeAnswers` 重建模板/节点呈现，配置从同局 `nodeConfigs`；title/story/media/chapterAudio 不补读未来作者模板。旧无新标志按旧路径。`allowedActions`、check、narrative、role/preload 仍走各自原公开投影，不直接下发答案/未用提示或整块服务端快照。 | `PublishedNodeContentConsumptionTest`、`ApiPlayEncounterControllerTest` |
| `ApiAdvancedPlayController.start/state/act` → `AdvancedGameRuntimeServiceImpl.requireInput:1237`、`start:210`、`restart:321` | 原小游戏优先返回自身 templateId/configSnapshotJson；新局使用原内容 templateId，合法无模板不会捏正 ID。`originalNodeContent` 由服务端写入 state，不接受作者 config 同名键作为身份；原重试保留。现有 CurrentPlayRegistration、route/TEAM/epoch、deadline、CAS、receipt/proof 和奖励路径仍在原位置。`toView:3162` 逐项投影，没有直接公开原内容或整个 state。 | `AdvancedGameRuntimeServiceImplTest`、`AdvancedGameRuntimeMySqlIT`（实际 XML/事务夹具，未执行） |
| `ApiPlayProgressController.nodes` → `PlayEntryApplication:178–265` → `PlayNodeQueryApplication:209/379/405/512` | Entry 保留原显式入场、state 初始化和共享刷新职责；纯 Query 消费本请求已捕获的原输入。原局、新发布、原 default 游戏按真实端口分开选择，不以 live templateId 覆盖已有小游戏。当前完成事实、XP、券/权益、到店、下架/旁观准入不由内容快照授权。题面/选项、已用提示、公开 Advanced config 等按原字段下发；答案和原 credential 不整块输出。 | `PlayFrozenContentProjectionTest`、`ApiPlayProgressCompletionTruthTest`、原 `PlayNodeAccessTestFixtures` |
| `PlayBasicCommandApplication` checkin/answer/sensor/arrive → `PlayNodeContext.effectiveTemplate:144` / `frozenTemplateView:108` | 原内容先于 live 判定字段，当前 node 和 ctx 仍用于 scope、lead、到店、下架、可信传感器 proof、完成和奖励。无模板保持 null；缺失/损坏的新结构不转 live 成功。Sensor 原实际验证和 Basic 原 proof 通道没有因为内容冻结被省略。 | `ApiPlaySensorResultTest`、`ApiPlayProgressCompletionTruthTest`、原 completion/proof 场景 |
| `PlayPhotoCommandApplication:107` / `PlayPuzzleCommandApplication:63` → 同一个 `PlayNodeContext` | 两个直接相邻 caller 没有另取一套 live 判定输入；照片原上传来源、UNKNOWN/幂等、reveal 的原授权/记分路径保留。 | 原 photo/reveal fixtures 与 `PlayNodeAccessTestFixtures` |
| `PlayPreferenceApplication.resolvePreferencePlayCtx:127` → 原 `PreferencePlayService` | 先原 scope/lead/unlock/route/到店，再用冻结 preference 模板；原 current node、routeAction/version 和实际完成 owner 不被 presentation snapshot 替代。没有强并普通 QA 与偏好题合同。 | `PlayFrozenPreferenceAdmissionTest`、原 Preference 测试 |
| `PlayPuzzleCommandApplication.requestHint/unlockHint` → `PuzzlePlayServiceImpl.originalHintTemplate:135` / `unlockNodeHint:256` | 新 hint 正文来自原内容；ctx/current scope/lead/route 继续执行，积分价格仍来自原规则；原账本抢占先于 debit，原重复和回滚分支保留。无新标志历史模板仍按明确旧路径，不补造旧局身份。 | `PuzzlePlayServiceImplTest`、`NodeHintUnlockApplicationTest`、`ApiPlayPuzzleHintTest` |
| 原三处 seed → `ThemeCharacterService.seedRun:151`；`ApiPlayCharacterController.card` → `card:64` | 原角色台账/当前或结转值不冻结成作者内容；只由原 snapshot 取得定义/规则，原初值、persist_scope、clamp、RPG 及 actor/topic 准入保留。新声明损坏拒绝，旧 keylabel-only 仍走原旧路。 | `ThemeCharacterServiceTest`；原 mapper MyBatis 夹具 |
| `ApiPlayJourneyReadController.casebook` → `PlayJourneyReadServiceImpl.casebook:153` | 本人原 scoped session；定义/规则/故事来自同局，实际变量、tag、关系、日志仍读原 state/事实。frozen 空故事不回落到未来 live 故事；旧无字段才沿旧路径。 | `PlayJourneyReadServiceImplTest`、原 controller 读测试 |
| `TopicRouteRuntimeServiceImpl.queryNodeContentSnapshot:1018` → `GameSessionRuntimeServiceImpl.querySharedRouteSnapshot:1394`；无主题局 → `Advanced.queryDefaultNodeContents:1290` → Mapper/XML | 个人现局先于 published fallback；default 读取按原 activity/topic/member/default 收窄，个人优先，否则可访问团局按原顺序选取，SQL 用 EXISTS 防 membership fanout。共享读取保留 activity/topic/当前报名、active TEAM、RUN 和冻结 TEAM owner；捕获 control/epoch/原 sourceNode→station 映射并 defensive copy，歧义不猜。该新纯读路径没有调用 sharedRouteSnapshot/refreshZeroHpRead、ensure、建局、FOR UPDATE、时间推进或写账。 | `GameSharedRouteQueryTest`、`AdvancedGameRuntimeServiceImplTest` 的 default 原内容场景；Mapper/XML 符号装配一致 |

## 共同需局与快照边界

只读核对 `TopicRouteRuntimeServiceImpl.journeyConfigured:1122` 与 `NodeJourneySnapshot` 原 helpers：

- 已有 route session 优先，不能因作者后来清空规则而降为无局路径。
- 新内容版保留原 `JourneyRules.hasRuntimeState`、attributes/statLinks、themeConfig、openingChapterId 及 `NodeJourneySnapshot.needsJourneyState` 对 check/requires/effects 的判据；普通不含这些状态要求的 LINEAR 内容仍可无局读。
- 旧 runtimeSnapshotVersion=1 且无新内容标志的分支明确保留；损坏的新内容结构拒绝，不补造正 templateId，不取未来 live 数据伪称原快照。
- 共享 alias 的 nodePresentation 原 source ID 和实际 station ID 不混为一份身份；源映射在原共享 owner 中产生，消费者没有接受客户端 source/order/payee 等经济授权字段。
- 读取返回的 JsonNode 只作为服务端内部输入；`contentNode` 保留当前状态/credential，Basic/完成 owner 仍接收当前 node，私密原答案不能通过整个快照或整个 state 直通响应。

## 直接装配与源码限制

- 已核对三个新增窄读端口的接口声明、真实 Spring 实现及调用参数顺序；默认方法保留旧 fake 的源码兼容，但没有把 default-null 当作生产能力已执行的证明。
- 已核对 `GameRuntimeSessionMapper.selectDefaultMemberSessionsByTopic` Java 参数和 XML namespace/id、scope/default/EXISTS/有序选择，以及消费者的原 row scope/身份防御检查。
- H 公共 `PlayNodeAccessTestFixtures` 已将同一个 `topicRouteRuntimeService` 接到真实 `PuzzlePlayServiceImpl`；直接 helper 测试和真实 Mapper/事务测试仍是不同证据层，未混称 HTTP/Spring/MySQL 通过。
- 本片没有提交共享 Journey/runtime 的 review.patch：没有确认需要修正的共享 hunk，也没有以缺测试或未知执行结果构造 BUG。
- 尚未验证实际 Spring proxy/循环依赖装配、编译、JDBC/MySQL 行为、并发发布/切换 epoch 时的运行结果、HTTP 最终 payload、设备、供应商和完整跨局回归；这些是同源最终验收的覆盖限制，不是已证实缺陷。

全部测试/编译/Node/Python 控制/Java/数据库/HTTP/故障注入/CI **NOT_RUN**。本片只读取源码、记录报告及执行 git 差异/摘要/秘密扫描；旧绿色不继承。源码保持冻结，不继续扩展范围或空待。
