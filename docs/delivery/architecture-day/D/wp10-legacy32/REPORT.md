# WP10 旧owner32项观察候选

仅移植三份观察夹具，未带回任何图生产实现；不表示编译/真库/等价验收通过。WP08盘点暂停，未发布其生产草案。

基线：公共 `liboyang42-cpu/hengyin` 的 `27471b00989e15f7641f4ee77c497cca2f9e53c1`，tree `51322a16c1085b4a59967b1265666a0f3b20df9d`。已读取完整公开tree（未截断）：CmsTopicServiceImpl blob为 `e7b9f22805e8d28292b46e36c68c62e5d5a1d41a`，与919原门面一致；五个新图owner生产类/接口与新TopicGraphTestWiring均不存在。基线也没有这三份新夹具。

三份文件来自已冻结 `dbbb2350abe95f22fcc7b6fb291fcd0f66d7a004`（原dea485相同内容）：TopicContentGraphMySqlIT.java、TopicContentGraphMySqlFixture.java、wp10-content-graph-scratch.sql。完整路径/旧侧SHA256见SOURCE.sha256。仅fixture第49行默认值一处由 `"extracted"` 改成 `"legacy"`；将该文字恢复即可与dbbb原fixture逐字节相同，IT与SQL逐字节相同。未改原32个case名、62个scenario、任何业务断言、异常/行/Mapper与领域顺序、归一化或事务记录。

旧侧保留原反射双owner设计：legacy分支对新GraphImpl/Guard/Reader/Ids四类逐一assertThrows(ClassNotFoundException)，不是假定旧owner。新owner类型只在字符串中出现，无编译期类型/构造器/字段引用；本候选tree也不增加接口或TestWiring。owner仍可由既有system property/env显式选择；本候选仅改变缺省值，禁止runner把旧侧覆盖为extracted。实际receipt必须owner=legacy，新侧必须owner=extracted。

既有编译依赖已作源码核对：MysqlIntegrationTestConfig（package business.service，经原通配import）blob `61e9c15a4f9751fb23c01c9f3a989e1542dc5f52`，TopicPublicationTestFixtures（support）blob `93cf0cd5de9004903c86b10d49b1e2d1213b4570` 均已在27471存在，后者不反调新图owner。原cms-topic-it-schema.sql blob `ca55bf011cc9a3002d6d28a3d1a6359f78b04f41`、真实CmsMemberTemplateServiceImpl与TopicRouteRuntimeServiceImpl亦继承基线。本轮未发现必须带新生产类才能编译的引用；此为静态检查，不替代Java编译。

PORT/USER/PASSWORD硬前提与MySQL8校验原样保留，无skip/Assumptions/EnabledIf；输出仍为 `target/surefire-reports/wp10-authoring-equivalence`。默认scratch schema按原UUID隔离，实际cleanup断言/WP10_GRAPH_CLEAN日志原样保留；SQL仅测试夹具，未执行DDL。

实际完成：核验公开base/tree/原门面blob/新类缺失；核验三份来源hash；恢复默认字面量后全字节对照；静态32case/62scenario及新owner编译依赖检查。没有运行Java/数据库/HTTP/CI，也没有修改CI/registry/pom、任何原生产/测试文件、资金或部署。此次候选仅三份新fixture加本D目录两份证据；原57文件候选及各工作区保留不变。

root负责以该旧source和独立新source分别跑32项，核对各XML 32/0failure/error/skip、source/hash、完整32份JSON与各自CLEAN，再使用已冻结dbbb的 compare_equivalence.py + mysql-cases.json 对照62scenario。比较器本轮未复制/更改；仅owner标签允许不同，其余按原冻结比较器严格比较。没有实际双方结果前不签T15/机械等价。

冻结比较器 SHA256：`f7b303f2c80f1cd667998eeba360ff13f4814ff9c69e13301d1d32b8e860286f`。
