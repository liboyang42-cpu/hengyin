# A03 剧情兼容调查与候选

基线 `d551c6babd018d41aec90dad2c0acaf1c534644d` 上共享候选，未切分支、暂存、提交或运行 Java/DB。当前只达到源码调查、候选补丁与静态核对，不能称业务验收通过。

## 确认并修复的新写漏洞

`ChapterFlowCompiler.compileChapterFlow` 对 `ending.fallback` 原先直接 `asBoolean(false)`；字符串 `"true"` 被当作兜底。随后 `JourneyStory.withEndingChapters` 写出真正布尔 true，因此下游 `JourneyStoryValidator.boolField` 看不到原坏类型。最小修复在编译时要求出现的 fallback 必须是 JSON boolean；缺席仍为 false，合法 true/false 行为不变。无新 DSL、结局政策或读校验改变。v2 写错误沿用 ChapterFlowValidationException/400。

新增 `A03EndingWriteCompatibilityTest`：真实编译器入口，合法布尔保留；字符串、数值、null、对象均拒绝。未执行，不声称红绿通过。

## 原行为反证与旧样本

| 真源 | 当前规则和应保持的旧样本 |
|---|---|
| `PlayJourneyStoryServiceImpl.pickEnding` | 按数组首个非 fallback 命中，全部未命中取 fallback。`JourneyStory.withEndingChapters` 按章节顺序生成 ending1/ending2，未添加 priority。旧样本两个均符合条件，仍应 ending1。 |
| `ThemeRuntimeEngine.terminal` | priority 最大，缺席为0；`>=` 使平局最后一个胜。旧样本 first/last 均无 priority，仍 last；first=2/last=1 则 first；同2仍 last；全未命中取唯一 fallback。成功、结束原因及奖励筛选维持原样。 |
| `TopicRouteEngine.advance` | 非终点优先普通 eligible edges；无普通边才 fallback；最高 priority 子集按既有 seeded weighted 选择。同权不是首命中，也不能改为剧情顺序。不是结局引擎，无合并。 |
| `ThemeRuntimeConfig.parse/normalize` | priority 可缺席，不回填下标；未知字段/condition op 新写已有拒绝，schemaVersion严格整数1。 |
| `JourneyStoryValidator` | 已拒未知条件 op、非法状态键/外来节点、缺少唯一 fallback。共享读写入口存在宽松数值解析；本轮不突然收紧而拒绝旧快照。 |

新增 `A03RuntimeEndingCompatibilityTest` 固定上述主题结局平局/显式priority/fallback、freeze深拷贝与未知新写拒绝。既有 `PlayJourneyStoryServiceImplTest.stageEndAndEndingReadFrozenStoryAfterTopicStoryEdit` 覆盖冻结剧情标题/选择，`ThemeRuntimeEngineTest` 已有终局结果不可重复改写。全部待既有CI运行。

## 版本与快照，以及尚未解决的真实限制

协议 schemaVersion=1 只代表形状，不是内容递增版本。路线 configVersion 是草稿CAS，publishedConfigVersion 是发布内容版；`TopicRouteRuntimeServiceImpl` 开始时把发布版本和 configSnapshotJson 存入 session。themeConfig由 parse→freeze 深拷贝进入开始快照；后续 runtime 使用 session 快照。`PlayJourneyStoryServiceImpl.requireStory` 优先冻结 journeyStory；冻结空串不回退，极旧快照没有该键才回退实时主题。无法补造极旧局的历史内容，不迁移、不把当前版本覆盖回旧局。

**确认并补齐结局正文快照缺口（候选，待CI）。** 原 `freezeThoughtChapters` 排除 endingIds；`PlayJourneyStoryServiceImpl.endingChapter` 实时查询，作者编辑/重建导致旧局正文漂移或消失。协调者追加授权读侧；写侧仍由A01独占协作修改 `TopicRouteRuntimeServiceImpl.freezeThoughtChapters`。新快照始终写 `endingChapters:{chapterId:{id,name,atmospherePreset,audioUrl,blocks}}`，仅本主题结局章，不预投影。读侧优先该键且只返回选中章，按本局标签/已完成节点投影正文；有键缺章返回null，禁止实时补造；整个键缺席的历史局保留原live fallback。异常JSON给出“本局结局快照不可用”。无DDL/新数据库字段，未改结局选择顺序。

新增 `A03EndingSnapshotReadTest` 覆盖冻结内容、未选章不泄漏、条件正文过滤、有键缺章不实时回退、旧无键保持原回退。A01提供真实建局的写侧专属测试，见A01报告。历史已丢正文无法追溯，此补丁不补造历史数据。

未知读不是任意成功：已知旧格式继续按原策略；不支持的剧情协议由 requireStory 返回可观察“本主题剧情配置不可用”；结局章丢失只降级标题/摘要，不奖励或授权扩张。

## 实际验证和交接

实际运行 `git diff --check` 对本单源码/测试返回0，无空白错误。仅静态核对，不等于 Java 编译/行为测试。未连接外部服务、MySQL/Redis或生产，未新增设施/workflow/依赖/DDL。

CI建议串行选择：`A03EndingWriteCompatibilityTest,A03RuntimeEndingCompatibilityTest,A03EndingSnapshotReadTest,JourneyStoryValidatorTest,ThemeRuntimeEngineTest,JourneyStoryEndingChapterTest,PlayJourneyStoryServiceImplTest,TopicRoutePublishedSnapshotContractTest,ChapterFlowWriteServiceTest`。Java编译、CI、VPS HTTP/SQL独立读回、微信设备全部未验证。C99需覆盖旧局开始→作者改发布→旧局结局/新局对比；结局正文读写候选必须一起通过CI与独立读回后才能关闭该验收。

回退须协调撤销 compiler 的新增boolean检查、结局正文快照读写及本单新增测试，不撤他人变更、历史迁移或旧快照。没有为凑改动修改正确优先级或另建引擎。
