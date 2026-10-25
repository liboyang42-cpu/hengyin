# 已观察的声明读取消费者（非新全仓审计）

固定观察源 G `edf731c15090d2289553dd7f966f6c7d8ec7f5e2`；本片只改声明 writer/producer，不改下列 G/H/E 独占行为。以下是源码事实，不是已运行漏洞/验收结果。

| 直接读取 | 精确源码位置（观察源） | 实际使用 |
| --- | --- | --- |
| Mapper.selectByTopicId | TopicRoutePublicationCompiler:444、467、707 | 派生 clue 判重/直接 upsert；配置引用验证；JourneyInitialState.freezeKeys。归 G；正式发布须复用同一当前投影，不能各自普通读 |
| Mapper.selectByTopicId | ThemeStateKeyService.listByTopic:181（本片行号后移） | 普通声明列表；Controller 作者列表、ContentGuard、PlayJourneyReadService 等间接使用。F 保留普通 API，不偷偷变为加锁 runtime 读取 |
| Mapper.selectByTopicId | TopicRouteRuntimeServiceImpl.failCostTagLabel:989 | tag 的 live label；不是 frozen registry；归 H |
| Mapper.selectByTopicId | GameSessionRuntimeServiceImpl.clueLabels:1927 | live clue 键存在性/label；clueView 还用 labels.containsKey 决定变量已有线索能否显示。其剧情 sources 来自 session snapshot 不等于 label/存在性也已冻结；归 H |
| Mapper.selectByTopicId | ThemeCharacterService.seedRun:144；TopicRouteRuntimeServiceImpl:1219 调用 | live counter keyType + persistsAcrossRuns 选持久属性；按声明/规则 clamp 原值，再放入新局 state.variables。不是单纯文案；归 H |
| Mapper.selectByTopicId | ThemeCharacterService.cardKeys:224 | live counter keyType/visible/sortOrder/id；card:77–101 同时读取 live journeyRules、initial、min/max/display/color/icon/persistScope/rulesJson，结合当前 session state_json 与原 character.values_json 展示/夹值；归 H |
| service.listByTopic | PlayJourneyReadServiceImpl.casebook:164 | 先读 live 声明，再 visibleKeys(...,rules,snapshot)。若 snapshot 有 stateKeys，则 JourneyInitialState:148–167 只从冻结 key/label 重建 bean；不代表 type/scope/范围/rules 已完整冻结 |
| Mapper.selectByTopicIdAndStateKey | ThemeStateKeyService.updateDefinition（原128） | 原生产唯一单键直接消费者；F 已改为 selectByTopicIdAndStateKeyForUpdate，不再使用旧一致性视图行。普通单键 API 留给原兼容/测试；本次未发现生产 runtime 的单键直接调用 |

`ThemeCharacterService` 的原 live 回落、跨局保留政策和 H 的完整消费者改动不由 F 在此猜改。仅发布 hash/冻结 keylabel 无法消除上表中已证的 live 元数据解释。G/H 应在原快照/实例合同中明确实际字段与旧快照行为，保持次数、奖励、UNKNOWN、现有值和跨局台账政策。运行 `state_json`、`ThemeCharacter.values_json` 不进入作者内容 hash。
