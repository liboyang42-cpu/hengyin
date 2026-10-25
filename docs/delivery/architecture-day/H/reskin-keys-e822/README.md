# H：e822 原生玩法声明夹具与 DraftCopy 精确设桩修补

源码交付完成；未执行测试、parse、编译、Java、数据库、外呼或 CI。本候选不是通过结论，交 root 集中验收。

## 基线和范围

- 唯一 parent：`e82229ef85e58159078a42956560356074192541`。
- parent tree：`ed211d0215b206a682697d9470307c0fb0181d53`。
- 取自公共 `liboyang42-cpu/hengyin` 的精确 SHA，以独立快照目录 `/workspace/hengyin-h-reskin-keys-e822/{base,work}` 编辑；没有切换或重置任何共享 checkout。
- 只变更四个授权测试源文件，完整 delta 为 [owned.review.patch.base64](owned.review.patch.base64)，后像见 [POSTIMAGE.sha256](POSTIMAGE.sha256)。生产、D 的 TopicPublicationTestWiring、其他 owner 源码及迁移均未改。

## 实际失败与根因

root 提供的 run40228119362/job121273974170、source e822：Reskin 7 + SupportMatrix 8 因 `ThemeStateKeyDeclarationSnapshot.capture` 的主题归属拒绝；DraftCopy 的三主题用例因重复设桩递归而 StackOverflow。这是 16 个实际失败项的共同夹具首因，并非 16 个独立生产缺陷。日志与 artifact 由 root 已核，本次未重下或重跑。

1. 原夹具将未注册的全局原型 `keys` 直接当成所有主题的普通/current 声明读结果。真实 `ThemeStateKeyService.register` 在验证和 parent lock 后为输入写 topic/times，再执行 Mapper upsert；原 Mapper double 没有保存行、回填 ID，且第二个主题会改写第一个主题所借用的同一批 bean。
2. DraftCopy 的 `when(currentRead(...)).thenAnswer(...plainRead...)` 第二次设置时先执行旧 bridge，导致嵌套 plain read 成为新设桩目标，新 Answer 再调用自身。

## 具体源码修改

- `GameplayReskinFixture`：原型与持久行分离；每次将定义副本交原真实 register 服务和原 Spring 事务代理。Mapper 模型按 `(topicId,stateKey)` 保存独立行，插入分配并回填独立递增 ID；普通/current 列表都按 ID 排序、返回副本；两种按键读口也读同一行副本。
- 按现有 Mapper XML 的 duplicate SET 白名单保留原 ID/type/scope/createTime，label/initial 更新，扩展字段按原 COALESCE 保留。不以当前主题改写全局列表、不使用固定 ID。更新影响行数为 unit 模型，不是 MySQL affected-rows 实测。
- 原 compiler 的自动模板线索仍由真实 `registerTemplateClues` 调用原 upsert 与按键 current 读回；没有 mock capture、compiler 或 freeze 返回成功。
- `GameplayReskinRuntimeServiceTest`：在原三主题场景内增加行身份不重用、四个读口同源、原型无身份污染、主题间不串行、同键重复注册保持原行与扩展元数据、读取副本不改存储、跨主题 capture 仍拒绝、作者更改不回填原发布与原局快照的断言。
- `GameplaySupportMatrixRuntimeTest`：原六种 proof 输入全部保留，增加其实际声明行身份与两列表读口一致性断言；原收费、重放和两条精确 IllegalArgumentException 负例未变。
- `GameplayDraftCopyTest`：只将当前 parent/key bridge 用 `doReturn/doAnswer` 精确覆盖，避免设桩时执行旧 Answer；原失败后重选本主题挑战并再次实际 freeze 的路径保留，增加两次读取都仍属于原声明投影的断言。

## 调用和装配范围

- ReskinRuntime / SupportMatrix → `GameplayReskinFixture.publish` → 原 `TopicGraphTestWiring` 与真实 authoring 服务 → `ThemeStateKeyService.register` → 同主题 Mapper 行模型 → `TopicRoutePublicationCompiler.withCurrentContent/registerTemplateClues` → 原 `ThemeStateKeyDeclarationSnapshot.capture` 与真实规则编译/冻结 → 原 run/收费/证明/结束调用。
- `GameplayReskinMySqlIT` 是 fixture 的真实 SQL 调用者：`useSql` 仍切换到原 MyBatis Mapper、原服务与 Spring/JDBC 事务代理；本次原型克隆也适用于它，未替换其 SQL、cleanup、真实依赖或断言。该 IT 本次未修改/未运行。
- `GameplayReskinFrozenThemeComparisonTest` 只调用原静态 `JSON/assertSameFrozenTheme`，接口及实现未变。
- DraftCopy 的直接发布/冻结 helper 保持原真实 `DataSourceTransactionManager/TransactionTemplate`；其普通声明读仍是原同一 topic 的显式 rows，current 读仅桥接到该原投影。

所有新方法的 ThemeStateKey getter/setter 已对照本 SHA 的 domain、Mapper 接口/XML、真实 writer 与 compiler 按源码手工核对；这不是编译证据。没有改 public 业务接口、权限、费用、类型/registry、UNKNOWN 或发布门禁。

## 原 case 保留与验证限制

见 [CASES.md](CASES.md)。三类完整源码仍为 Reskin 7、SupportMatrix 6 参数输入 + 2 普通用例、DraftCopy 4 普通用例，共 19 个声明输入；本轮原失败子集为 16。无方法/参数/原 assertion 删除、禁用或宽化。修后结果全部 NOT_RUN。

本次仅阅读源码、编辑、生成 diff/哈希及发布 Git 对象。unit 的声明持久模型和 JDBC doubles 不证明 MySQL 并发、锁或 rollback；新增 Mapper 对比断言仅核对投影来源，不冒充真实 FOR UPDATE 验收。保留原 SQL caller，真实 Java/库/原局消费回归由 root 在集成源码统一运行。

无生产/DDL/数据迁移影响；回退仅撤此四测试源增量，不回退其他 owner。

Root transport normalization only: the review patch is losslessly base64 encoded because two literal diff-context blank lines contain trailing spaces. Decoded SHA256 `30b23d452c926c9ae6f0303d792c2d0a07fdebfb544d39f5d94a929e3dc1b96e`; all four Java postimages remain byte-exact to the author. Runtime verification NOT_RUN.
