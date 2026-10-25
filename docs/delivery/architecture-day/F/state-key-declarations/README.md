# F：作者 state-key 当前读生产者与 writer fence

状态：`PRODUCER_SOURCE_READY / G_CONSUMER_WIRING_PENDING / ALL_TESTS_NOT_RUN / all_complete=false`。

parent `edf731c15090d2289553dd7f966f6c7d8ec7f5e2`；独立分支 `codex/architecture-day-f-state-key-declarations`、工作树 `/workspace/hengyin-f-state-key-declarations`。完整目的、字段/调用者与可编译签名见 `CONTRACT.md`，供 root 立即串 G/E 的原发布投影；未更改 G/H 独占源码或另造引擎/版本表。

owned 生产增量：原 ThemeStateKeyMapper.java/XML 新增三个窄 SELECT，原 ThemeStateKeyService 的三类声明写入共用 topic→key fence，updateDefinition 使用当前行，delete 置于原 Spring 事务；另给原发布调用者提供显式 MANDATORY 当前声明 producer，普通 preview/runtime 读不变。全部旧金额/权限/支持 scope/校验/原 upsert/定义更新合同保留。无 DDL/数据迁移/依赖，未修改运行值、已开局状态、原回执/奖励/次数/清理策略。

测试源码（**未运行**）：

- 原 ThemeStateKeyServiceTest **18 项保留**，仅将两个旧 point-query stub 迁到真实 current port，并为原合法主题7补 parent 端口。新增 **5 项**：三种 writer 锁序、不存在 parent 零写、非法批次/只读预览无写锁、Mapper cache/current SQL合同、明确 producer 无事务不降级。
- 新 ThemeStateKeyDeclarationMySqlIT **6 个普通 @Test**：旧 RR+MyBatis cache 后当前读；空声明仍挡新注册且另主题可编辑；register/update/delete 均实际等候同 parent；旧缓存行删除重建后的 current identity；原事务实际删除后回滚；不存在 parent/无原事务/只读事务拒绝。用原 canonical topic CREATE、原 key CREATE+attribute migration、真实 Mapper/真实 Spring事务代理；显式受控 loopback/凭据前提、每类 UUID schema、AfterAll 只删自己创建的库且独立确认不存在。不把慢线程当锁证明：查询仅本 schema 的 performance_schema.data_lock_waits，缺真实等待即失败。未连接任何数据库。
- `shared-unit-wiring.review.patch.b64` 保存 F 原补丁的标准 base64 运输件，解码后原 SHA256 为 `3be23cc9816257f2e7f557273183f50cdd05ba8ddc8de64d89d342ded9a72d07`。原始补丁亦可从 F 公共提交 `e3068c120af738b954b72afd22ceec953477b6d2` 读取；root 私有临时收据保留原字节。改用 base64 是为了保留补丁的字面空 context，同时不向候选源码引入尾空白，不修改补丁含义。
- root **已串行应用**这三份旧测试装配：TopicPublicationTestWiring、SeventhPierFixtureTest、GameplayReskinFixture。只为纯 mock 绑定原 fixture 主题身份，不改真实 Mapper/spy；Reskin 的 real SQL 分支给真实声明服务使用原 Spring事务代理。原场景/断言未删减，所有测试仍未执行；不要对已装配文件再次应用补丁。

本轮只读源码、编辑、Git diff/摘要与秘密扫描；**没有运行任何 Node/Python/Java/MySQL/Redis/HTTP 测试、编译或 CI**。没有通过数；上述 5+6 仅测试声明。新 IT 尚未登记/显式执行，不能因默认 IT exclude 视作通过；root 源齐统一登记和验收，F 不编辑 registry/CI。

交付限制：G 的 Bundle/hash/Compiler/派生键预处理和 E 的发布 guard 仍须沿 CONTRACT 串到同次声明投影；H 的 runtime 当前值/冻结消费者由原 owner 接，F 不改。待真实消费者齐后再补最终整链 owned patch 对照，不把 producer_ready 冒充目标完成。回退此片会失去这批声明并发/当前读保证；已有作者/发布/运行数据不删、不重写 hash，不以历史表清空或回填制造兼容。
