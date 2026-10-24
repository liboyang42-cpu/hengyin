# A：模组配置与冻结规则投影 owner

源码已完成；只做本批次静态核对，未运行 Java/JUnit/MySQL/CI，未增加专项回归或修改旧场景。

独立分支 `codex/architecture-day-a-runtime-config`，基线 `062c6cb71ffa0ab6b005ee622e8a2045e4c496cd`。生产增量仅父 runtime + 新包内普通类 `GameSessionConfiguration`。父类 5424 → 4832 行，再净减 592 行；新 owner 713 行。

唯一 owner 的 40 个迁出方法：`isGameModuleConfigEnabled/readRouteJson`，`readyConfig/configuredChecklist/configuredStation`，`validateModuleConfig/validateStoryEffects/validateStoryConditions/requireStoryRuleCount/requireStoryTag/validateStoryVariables/isStoryRuleValue/validateHintPolicy`，`sessionConfig/configuredRoles/configuredChapterIds/firstChapterId/chapterIndex/isChapterUnlocked/isLaterChapter/hasUnlockedChoices/roleOptions/chapterOptions`，`findChoice/allRequiredChoicesApplied/choiceGroupAlreadyApplied/configuredChoice`，`publicRole/configuredTask/publicTask/requireHintPolicy/hintView/roleClue/configuredRole/allowedChoices/findEnding/publicEnding/stationView/approvedFallbackPlans/safeOperationsText`。14 个仅在该域内部使用的 helper 从父类移除；26 个真实边界调用保留参数逐项相同的薄适配。

真实 caller：`ApiClubController` 主题列表 → `isGameSessionEnabled` → 配置有效性；原 command → PREPARE 校验与保存冻结配置、STATION_READY 规范化服务窗口/清单、角色分配/剧情选择/解幕 → 冻结规则选择器；原三身份 view → 角色/章节/任务/提示/安全运营文案投影。原准备过程仍先锁主题、在原时点调用 route freeze、再插 session/站点；原角色/剧情的锁/CAS和结果持久化留父类。没有新建配置编译器，也未触 E 编译器。

owner 只有一个现有 `ObjectMapper` 依赖，无存储/时钟/服务集合、Spring bean、父类反调或回调。原 JSON/Map API 返回形状不变，未用新泛型容器承载上下文；原 `ReadyConfig` 类型与四字段随边界迁移，仅调整包内字段可见性供父类原写入使用。6 个小型父类通用 codec/文本 helper 原样复用在 owner 内，未引入新算法；总命令回执、聚合锁、事务、scope、dispatch、运行状态迁移和持久化仍在原 owner。

`python3 docs/delivery/architecture-day/A/RUNTIME-CONFIG/verify_static.py` 与 `git diff --check` 实际通过：40 个迁出方法 + 6 个小型 helper 方法体与父候选逐字相等；26 个适配仅对应方法和原参数委托；其余 261 组父类方法、所有原事务/注入注解保持原样。输出源码 SHA256；不是编译或行为验证。所有既有测试文件未改，字段注入和公开入口保持，因此无需夹具迁移。本次未反复运行先前冻结小片检查。

H-AE-01 微修 `ce49f49b6a4ccd92c67f53f72d4fe387aa734f1f` 已另交 root，必须保留。此配置候选与其同父，但只取本候选增量，不能整树覆盖而恢复旧的 RUN 缺失返回 null。原 `GameSharedRunControl`、只读端口、E/C/root 文件均未修改；无 schema/权限/金额规则变化。回退仅撤本配置 owner 增量，不撤 H-AE-01。A95 原 P1 仍未关闭。
