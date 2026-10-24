# E 路线发布编译/冻结抽取候选

源码抽取及追加只读端口完成；未运行Java/DB/CI，待root收齐源码批次后统一回归。本次只改三个生产Java文件及本文，不新增服务bean、接口层、依赖、schema、专项测试/CI或运行开关。

- 基线：从公共 `d49c12b820419308236010e0710f96d7bf48c4c9`施工，最终parent更新为root投影闭包 `99627230e0f2b6d976d24e999f375aeb8c8e14df`，tree `cdd8bca3ff87a907789cfddc7679e431f17726fa`，先继承其leaf与三个facade。最终独立目录 `/workspace/hengyin-e-route-publication-final`、分支 `codex/architecture-day-e-route-publication-final`；另保留原E工作树。
- 范围：`TopicRouteRuntimeServiceImpl.java`、新同package具体类 `TopicRoutePublicationCompiler.java`、原`ITopicRouteRuntimeService.java`。相对最终parent，runtime净减709行（Git LF4599→3890；原d49为4690，root投影减少的91行不冒领为E收益），新owner859行，覆盖完整发布编译/冻结闭环，非整类改名。
- 真实调用：已核对本基线2465个生产Java blob（2457本地精确读取+8个公共API补查）；`CmsTopicServiceImpl.normalizedBranchRouteForPublish`及D的`TopicContentGraphAuthoringServiceImpl`仍经原接口编译/发布；`GameSessionRuntimeServiceImpl`与路线建局/重放仍经原freezePublishedJourney/freezeJourney。公开API、全部构造器及原chapter mapper必注入保持；其它owner文件未动。
- 抽取闭环：模板配置/同请求实体probe、GameplayTemplateRules编译、章节要求/真实归属、资源引用/状态键和线索登记、作者校验、发布标记/routeStations、章节叙事/开场/结局、nodeAnswers与clueContent、规则/主题场景/已发布重放快照，均由新owner实现。
- 复用：原GameplayTemplateRules、ThemeRuntimeConfig、GameplaySupportMatrix、NodeJourneySnapshot、JourneyInitialState等规则对象不复制/重写。新owner只收4个既有typed mapper、原Clock、原ObjectMapper和validator；无runtime引用/Supplier回调/依赖Map。请求probe只保留同一次模板批查，不跨请求缓存。
- 接线：runtime的三个公开编译/发布入口与建局/重放冻结已真实委托；小型共享读取/分类helper保留原门面。每次使用从已完成注入的runtime字段构造轻量owner，故原手工构造/Reflection注入测试无需改接线；不是编译owner回调runtime。
- 合同：同一JSON实例/Clock/validator、原字段写入顺序与LinkedHashMap、原异常文本、模板/章节验证与clue upsert次序保留。compiler无新事务边界，发布仍在原caller事务中完成；新query只标readOnly。会话、动作receipt、scope、唯一身份、CAS、UNKNOWN、授权及运行态副作用仍在runtime，未迁入compiler；原receipt序列化helper原文保留。
- 追加端口：`TopicRouteState queryState(Long memberId,Long topicId,Long activityId)`仅读当前实例；有效原scope下无session返回null，不伪造ACTIVE。原state不变。原scope校验主体原文抽为currentScope，旧requireScope仍保留原共享快照闸，新query不走该有刷新副作用的闸；个人session用原toView及sessionRouteMode读取冻结状态，保留sessionId/version/status，不ensure/INSERT/UPDATE/FOR UPDATE/零HP刷新。角色分配/初始化留给C的原Entry时点。
- A编译依赖：共享query只调用`TopicRouteState IGameSessionRuntimeService.querySharedRouteState(Long,Long,Long)`，不调旧sharedRouteState/sharedRouteSnapshot；d49尚无该新声明，E不修改A源或加反射/危险fallback，须root联合A最终端口编译并转C使用。DB/授权错误继续抛，不伪装null。
- 已执行的有限检查：相对root最终parent，Git/Python确认40个方法体迁入，其中39原文一致（含相同2行JSON序列化实现），1个只把addon包装调用改为已有GameplaySupportMatrix直调；其余216个runtime方法体仅typed probe创建点替换及获授权scope主体原文抽取；11个编译委托门面及原write保留。构造器、receipt/CAS/旧事务方法与所有旧测试原文未改。新query链静态限定只读选择/原投影，词法括号/依赖与可见性、diff check及有限秘密检查通过；这些不是Java编译或snapshot运行实证。
- root交叉范围：最终完整Runtime保留root三个public static facade委托GameplayActionProjection、无原private两helper；leaf直接继承996原blob，E不修改。合并仅取E增量，不回写旧算法。gameplayAddon保留原SupportMatrix直接委托，新compiler直接使用同一个SupportMatrix，不另造helper。E未new AdvancedGameRuntimeServiceImpl；运行态旧两static judge调用留G后续owner替换。
- 未执行：Java编译/JUnit/MySQL/CI及完整JSON输出回归。无新增/削减旧测试、skip或断言；root按用户新优先级统一回归，不新增专项工作流。现有发布/模板槽/章节/快照与runtime测试继续经旧入口覆盖新owner。
- 主线保留：已只读核对#567/#585/#586的41/4/2文件清单，与本次两Java文件无重叠。只集成E增量，不整棵旧分支覆盖主线；不改CmsTopic/D图、GameSession/A、Advanced/G或这些PR的规则。
- 迁移/回退：无DDL、数据迁移、Redis清理或生产操作。若回退须同时还原runtime委托并移除compiler，不能只删新类；不得回退其它owner源码。IM club41c与未发布route边界工作树保持冻结，未混入本候选。
