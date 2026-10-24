# Topic 夹具迁移收口

基线：`9b284f0111b4337e388fc18d797b11c62112cd82`。独立分支：`codex/architecture-day-c-topic-fixtures-9b28`。

原证据：run `40110542516` / artifact `12566343389`（原 ZIP SHA-256 `03557452a07d681eb5754b51e1b7d81f258d06c212194df2397287b7c21dbd1a`）。例如 CmsTopicPublishDraftTest 在旧 mapper 装配处失败；OWN_C03_03Test 在 Graph.shared 的 sysCategoryService 读取处失败；OWN_C03_19Test 在 Copy.wire 的模板 mapper 读取处失败，尚未进入原业务断言。

本补丁只改测试：

- TopicPublicationTestWiring 识别真实 Query/Copy/Publication/Graph/ContentWriteGuard owner；迁走字段在真实 owner 上装配，同名 collaborator 复用原夹具对象。不存在的字段仍拒绝，原显式桩优先，重复装配不重建 collaborator。
- TopicPublicationTestFixtures、TopicCopyTestWiring 通过该桥读取模板 mapper；19 个调用文件只迁移声明为 CmsTopicServiceImpl 的反射接收者，其他同名服务字段不变。
- 新 TopicOwnerTestWiringTest 的 5 个 case 检查实际 owner/原对象身份、批量工厂重复装配、替换 mapper、真实 Query→原 mapper 可达及未知字段拒绝。均已编写，未运行 Java。

实际执行：git diff --check；Python 精确源码对照通过（19 文件原 202 个测试声明及 718 处断言/verify 保持，变更严格等于接收者迁移）；7 个生产字段 owner 锚点核对通过。未改生产字段/源码、G 结算夹具、D CurrentPlayControllerFixtures、CI policy、SQL 或依赖。原 source-contract 安全断言不删除、不放宽。

未执行：Java 编译/JUnit、MySQL、完整 CI；须由 root 同源组合验证，原 CI 失败数不能视为本补丁通过数。回退只撤本测试增量，不能恢复生产废字段。
