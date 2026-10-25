# H TG 运行内容消费交付（ROOT_FINAL_CONTRACT_V3）

结论：完整消费者源码和配套测试源码已交付；**测试、编译、CI、Java/MySQL/Redis/HTTP/真机/供应商全部 NOT_RUN，all_complete=false**。本提交的实际生产/测试文件为 H owned 部分；共享端口/叶子及公共测试夹具以精确 review.patch 交 root 串行应用。此候选不能单独当作已装配可运行制品。

## 不可变输入与文件

- 公共 parent：`e306ae9b38555262651991591d433876ae9069b3`；tree `582e67d4a40596ef61ecd3d0a7dd6085996e03e4`。完整 SHA 为包含本文的提交，由交付消息给出；没有私有历史。
- 已读取完整方案 ceb4e5f923091dc0fc5e5b6b7670d60c27bff85f、F e3068c120af738b954b72afd22ceec953477b6d2 合同、E 56bc9324e1ebb7244130c6f9525a43262a046d88 caller，以及上述 G 的真实 PRODUCER-CLOSURE.md/producer/声明 serializer。
- 32 个 H 文件：19 个生产/接口/Mapper 源、13 个测试源。其中 owned 26 文件已经在提交中；6 个共享文件只交 shared-runtime.review.patch。另有公共 PlayNodeAccessTestFixtures 的 1 个接线 hunk。
- owned.review.patch 相对公共 parent；所有 fetched 原文件与原 base 逐文件相同。shared-runtime.review.patch 的基底为该 parent 加 **G 已交 shared-producer-ports.review.patch.b64 解码补丁**，不重复 G 的三参 freeze/derive/四参 freezeKeys 方法。
- POSTIMAGE.sha256 逐文件列 33 个预期合成 postimage（含 G 的上述共享方法）。它不是声称共享文件已经写进本提交；root 完成串行应用后按它读回。
- PATCHES.sha256 是三份实际补丁摘要；PARENT-BLOBS.json 列公共 parent 原 blob。完整调用清单见 CALLERS.md。
- 原 /workspace/hengyin-h-review（0558daca3cdb3bd1989a7c0e7a656159a2eab1fe）及其未交目录未修改。施工在 /workspace/hengyin-h-tg-consumption 独立源码快照目录，未伪称该目录为 Git checkout。

应用顺序：G/F/E 原 caller 与 G 五份共享 hunk由 root 保留 → 取本提交 owned delta → H shared-runtime.review.patch → H test-wiring.review.patch。不能整类覆盖 root 共享 runtime，也不要重复应用 G 的补丁。H 不触 G producer/freezeKeys writer、E validator 或 A/B/D/C 页面与内容采用实现。

## 原内容与读取合同

最终 V3 **不增加 contentSnapshotOnly**。现有主题/共享局先返回本局；新内容版用原冻结 journeyRules/journeyStory/nodeConfigs/themeConfig，复用 JourneyRules.parse、JourneyStory.openingChapterId 和 NodeJourneySnapshot.needsJourneyState；opening/check/requires/effects 原需局行为保留。旧 V1 无内容标志保持原 true 路，损坏新结构拒绝，禁止用作者未来 live 行补齐后冒称原内容。普通 LINEAR/合法无模板且原来无需局的浏览，不为 metadata 新建局。

新节点字典复用 G 的 nodeAnswers[nodeId].contentSnapshotVersion:1：真实个人 templateId、templatePresentation、nodePresentation、chapterAudioUrl。hint1/hint2 取私密顶层，答案/揭示/preference/sensor 保留原私密字段；nodeConfigs 仍唯一玩法配置。合法无模板 templateId=null + 空 presentation，不造正 ID。重建的是服务端 typed view，不整块序列化到 HTTP。公开仍逐字段经过原题干/选项/已用提示/reveal/叙事/AdvancedGamePublicProjection 的闸；当前节点存在、topic、status、QR、审核/准入事实来自当前原行，不被冻结呈现替代。

读口 querySharedRouteSnapshot 保原 actor/topic/activity/有效报名/当前 TEAM/原冻结 TEAM owner；原 RUN 缺失拒绝。捕获一次 control/epoch/binding 与 TEAM state，原 sourceNode→routeStations 映射按原当前任务/操作选择；歧义不猜。specific node 拒绝歧义，bulk 不借另一 source；返回 defensive copy。它不 refreshZeroHpRead、ensure、建局、回血、时间推进、写回、发奖。个人读口继续原 TopicRouteSessionScope；原局即使缺旧字段也不借未来发布回填。

无主题局的合法读口可取原已发布内容。已有 default 小游戏的 templateId/configSnapshotJson 继续自身原值；新小游戏仅将其原节点内容存入**服务器创建的 state_json.originalNodeContent**，重开/原重试保留。configSnapshotJson 未改包装，作者 config 的同名键不能提供这份身份。无主题局时由 Advanced 原 owner 的只读 Mapper 一次捕获本人/default 或当前有效队伍/default，原个人优先/否则原最近队伍；SQL 以 activity/topic/member 和 EXISTS 限 scope、防 fanout，不借非 default/别场局。server-only originalNodeGames 覆盖逐节点呈现和题/提示读，旧小游戏无原内容身份明确 legacy，不从新发布伪造；它不创建一份主题局、不改当前局选择/次数/奖励策略。原 state 白名单与 StoryVars 固定子路径不会输出私密原答案。

完整状态声明读取 stateKeySnapshotVersion:1、stateKeys 数组，保留 G 的 topicId/key==stateKey 和 type/scope/label/range/initial/display/color/icon/sort/visible/persist/rules 及原 null。技术 ID/时间不捏造，数组原序保留；原 state_json、theme_character.values_json 不进入作者 hash。本片只替换定义/规则来源，不改持久值台账/单调来源/跨局携带/clamp 算法。新坏版本/结构拒绝；旧 keylabel-only 沿明确旧路。G 的 freezeKeys writer 原字节保留。

## 原行为与安全边界

Basic/sensor/preference 的早期 live templateId 拒绝，移到原内容读取后按原 ID 判定；当前节点、scope、报名/期限/到店、下架及可信 proof 闸仍执行。提示继续原 scope/lead/route 与 attempt 身份、积分规则、先流水抢占后扣分、防重及回滚；不改收费政策。Entry 原 GET 的明确入场/共享刷新位置保留，纯 Query 只消费捕获值；公开/未报名旁观保留原准入，不为读取原 metadata 新加报名门槛。新快照无本节点不补未来节点。

完成 mutation 仍得到当前原 node，不把呈现 snapshot 当经济授权；原 coupon/XP/条件奖励/完成奖/CAS/receipt/UNKNOWN 路径不移动。只读里的券/奖牌为原展示引用，不声称经济规则已经版本化。未新增表/迁移/依赖/引擎/HTTP入口/权限/玩法或 proof 支持白名单，未删资金/回执/UNKNOWN/次数/持久角色值。当前安全撤销仍生效；不会用冻结内容重建被删除的当前节点。

## 测试源码与未验收

新增 **45 个普通 @Test 声明**（不是执行数量），原方法、原断言保留；未新增 skip/参数拆分：

| 文件 | 新增源码方法 |
| --- | ---: |
| AdvancedGameRuntimeServiceImplTest | 9 |
| GameSharedRouteQueryTest | 5 |
| NodeHintUnlockApplicationTest | 1 |
| PlayJourneyReadServiceImplTest | 1 |
| PublishedNodeContentConsumptionTest（新） | 9 |
| PuzzlePlayServiceImplTest | 2 |
| ThemeCharacterServiceTest | 3 |
| ApiPlayEncounterControllerTest | 4 |
| ApiPlayProgressCompletionTruthTest | 1 |
| ApiPlaySensorResultTest | 1 |
| ApiPlayPuzzleHintTest | 2 |
| PlayFrozenContentProjectionTest（新） | 4 |
| PlayFrozenPreferenceAdmissionTest（新） | 3 |

源码场景包括真实 compiler→存储 JSON→真实 Encounter/runtime；源修改/解绑/删除/再次发布；普通无局/无模板；V3 opening/check/requires/effects 仍需局、现局优先、旧 V1、不完整新快照拒绝；shared alias/epoch/TEAM/缺 RUN/defensive copy/无写；原小游戏内容/配置/重开/作者同名键无效；基础答题原 proof、sensor 不可信拒绝、hint 原流水/费用/回放；跨 scope/下架、公开视图不漏答案/未用提示；声明与原角色台账/clamp。Mapper doubles 不是 MySQL，ReflectionTestUtils 直接 helper 不是 Spring/HTTP 验收，未执行任何这些场景。

仅做源读取/编辑、diff、源补丁组合和 SHA 摘要。G 共享 writer 与 H visibleKeys 相邻 hunk、原公共 hint fixture 的无上下文草稿已在交付中换成按真实 G writer 基底/原夹具生成的补丁，未以失败应用冒充执行通过。原权限/完整 Spring 装配、编译/JUnit、真实 SQL/并发、HTTP/真机/供应商、最终公开 payload 须由 root 同一合成候选统一验；旧任何绿色不继承。没有 all_complete/可上线/无 BUG 结论。

回退需 producer+reader 一起审阅，不删已存原快照/原状态/回执，不回填历史身份；仅回退 reader 会恢复旧 live 解释缺口。无生产/DDL/部署执行。
