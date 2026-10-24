# G：AdvancedRuntime动作判定子系统源码候选

代码已接实际入口：公共基线`d49c12b820419308236010e0710f96d7bf48c4c9`；工作树`/workspace/hengyin-g-advanced-judgement`，分支`codex/architecture-day-g-advanced-judgement-20261024`。父runtime **4850→3435行**；21个动作、60个原算法方法及题组闭包迁至无持久化能力的`AdvancedActionJudgement`。不是改名God类或增加新Service；原22处注入仍在父类。PG batch/consumer新增工作保持冻结，旧未提交树保留，不混入本提交。

- 实际链：`act → actInTransaction`原命令身份/回执/授权/版本与尝试准备 → typed Action/Input → judge在JSON副本上返回Plan（本局state、score及是否赋值、顺序ChoiceRef、必要上传凭证要求）→ 父类原上传校验 → 父类应用提案 → 原CAS/共享与归零/R14/结果结算/日志顺序。
- 迁出：QA、排序、连线、分类、对照、盲品、估数、猜图、找物、限时打字，抽卡/硬币/骰子，以及开表与反应/摇动/安静/喊声/罗盘/倒计时/停表、原尝试规则、完成与成败判据。是封闭enum与明确状态提案，没有无约束handler Map，没有God runtime回调。
- 父类保留：session生命周期、scope/队伍资格、锁/版本/CAS、原receipt与UNKNOWN、一次结算/旅程回写、Photo admission/事务外I/O/原号确认/铸卡，及其他带持久或外部依赖的动作。`awarded`仅原本局JSON位，judge无member积分/券/账本/事务能力。QA照片仅返回原上传引用检查需求，父类校验成功后才应用提案。
- clock/random/attemptIdSource由父类按原对象传入，不重建来源、不预采样；迁出函数的采样位置、算式、阈值/重试/成员attempt键及JSON字段保留。`playKitRuntime`继续复用原实现，仅attempt投影调用同域owner。已读99627230的`support.GameplayActionProjection`，本片没有重复实现或改写该共享投影。

供E直接调用的新纯入口（均public static，无须创建任何runtime）：

```java
com.chengyinhub.business.service.impl.AdvancedActionJudgement.verifyObjectiveAnswers(JsonNode config, JsonNode answers) // boolean
com.chengyinhub.business.service.impl.AdvancedActionJudgement.verifiedObjectiveChoices(JsonNode config, JsonNode answers) // List<String[]>
```

父`AdvancedGameRuntimeServiceImpl`原两个public static签名保留为兼容委托；judge内不再new/引用God Runtime或GameRuntimeSession。E可迁移TopicRouteRuntime的两个静态依赖，G没有修改E文件。

实际检查仅git diff与定点Python静态：60迁出算法body经明确的session→局部score、上传校验→凭证需求替换后逐字一致，字符串键/错误文本保留；两题组闭包仅替换原God容器；act准入前缀与CAS/结算/log后缀逐字一致；其余101个原方法保持原body（两处attempt投影仅替换委托）；事务注解和22注入不变。指纹/方法身份见source-checks.json。不是Java编译或语义等价实跑证明。

新增6条必要边界测试源码（提案隔离、错误排序不改输入、QA上传需求、原clock/attempt/member规则、硬币不重抽、公开题组闭包）；全部旧测试/断言/装配保留未改。**Java编译、Java/DB测试、CI dispatch均0**，按用户最新优先级交root统一回归，不新增专项CI，不提前报验收。

已从公共仓库核对#567/#585/#586对应head；与本片文件零重叠。最终集成取本commit的窄delta，保留主线规则，禁止用旧分支整树覆盖主线。无schema/DDL/金额/授权政策/生产操作变化。
