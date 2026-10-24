# H 独立复审：G 到达记录实例固定

结论：对候选 `29c9fc90233c01fe6ba4efc93d008e33727bd44c`，本轮未发现有源码证据的新 P1/P2。原实例解析与插入不一致的根因在源码上已处理：复用真实进度服务解析一次，并给主节点、兄弟节点和 duplicate 回读显式绑定同一实例。此结论不是运行验收；Java、H2、MySQL、Spring 启动、HTTP 与微信均未执行，负控 36 项及修复后 39 项的运行结果仍为 UNKNOWN。

## 身份与范围

- 公共参考基线：`2ec68899099a1e250df0949a8383bf85b5d3ffe0`。
- 审查候选：`29c9fc90233c01fe6ba4efc93d008e33727bd44c`，tree `2dc3198d25c78f8f15148c2855e9526182d08cc4`。
- 父提交/负控：`acc4bf5ea10bbefabb9d5db52dd17dc800b3a955`；负控之前的比较源：`4cf548c3e8b05c6d23f3252c7e5d063691ef0eaa`。不把旧 2ec 行号套用本候选。
- 本轮开工 H HEAD：`e0e90b8a97de1a386a5d438b95fef1eae00dfa54`，独立工作树 `/workspace/hengyin-h-review`，分支 `codex/architecture-day-h-review`，开工工作树干净。
- 候选生产 delta 只有 `ShopDayProofServiceImpl.java`；另四个测试文件及 G 文档。H 仅新增本目录三份报告/索引，不编辑 G/root/C 源码或夹具，不派 CI。

## 真实调用链与修复合同

以下位置均对应候选 SHA；`system` 指 `chengyinhub-system/src/main`，`admin` 指 `chengyinhub-admin/src/main`。

1. `system/java/com/chengyinhub/business/service/impl/ShopDayProofServiceImpl.java:46` 校验 member/activity/node/node.id/node.topicId；51–58 给 probe 填 topic，调用真实 `IPlayerNodeProgressService.selectPlayerNodeProgressList`，取得被填入的实例。空实例在任何插入前抛错。activity=0 的自玩合法路径未被当作缺字段拒绝。
2. 真实 `PlayerNodeProgressServiceImpl.java:28–38` 只按 activity/topic/member 查询当前 session、给 probe 设置实例、查询进度。它不建局、不完成节点、不发奖；这是有 probe 变更的读取方法。现有 `routeSessions` 可选注入及无当前 session 时使用 `default` 的旧语义仍在，不应宣称本补丁消灭了所有 default 路径。新进度服务依赖是 required；未发现它反向依赖 ShopDay 形成循环，但实际 Spring 装配未运行。
3. `ShopDayProofServiceImpl.java:68–82,87–127` 主插入、兄弟查询/插入、主记录重复键后的回读都带原 member/activity/topic/node/实例。duplicate 回读不再重新调用服务解析最新实例；读不到原 scope 的可见行则抛出原异常，不把其他 scope 当成功。
4. `PlayerNodeProgressMapper.xml:28–40` 的显式实例条件使这些读取不再落到“按最新 session 推断”的 SQL 分支；insert 显式写实例。既有迁移 `migration_theme_runtime_20261004.sql:10–15` 的两组唯一键包含实例，本补丁没有修改 schema 或唯一键。
5. 兄弟节点仍按 topic/chapter 查询并沿用原 `samePlace`；`CmsTopicNodeMapper.xml:43–69` 在未指定商家/审核管理条件时保留原已审/撤回过滤。仅新建到达行，status=0，不写照片或完成事实。已有主/兄弟记录均无 UPDATE；软删记录仍受唯一键保护，不会被重新激活。软删主记录重复后回读无可见行会报错，不能把它说成成功幂等。

五个生产调用均在 `admin/java/com/chengyinhub/web/controller/api/ApiPlayProgressController.java`，传入真实 memberId、`resolveCtx` 得到的 actId 和包含 topic 的已取节点；不是仅检查 mock 参数。

| 调用位置 | 分支 | 外层事务入口 |
| --- | --- | --- |
| 1578 | checkin 的 FREE_COLLECT 到达凭证 | checkin，1506 |
| 1602 | checkin 的 personal/shared 到达 | checkin，1506 |
| 1778 | answer 的 personal/shared 到达 | answer，1681 |
| 2230 | arrive 的纯线上 FREE_COLLECT | arrive，2171 |
| 2316 | arrive 的 personal/shared 到达 | arrive，2171 |

以上三个实际映射入口均保留 `READ_COMMITTED`、`rollbackFor=Exception.class`。调用前的上下文/验证、原操作身份与版本/会话检查未因本补丁被删除。ShopDay 自身未新增事务；直接 new 服务的夹具不能证明这些 Controller 事务代理已经生效。

## 保留事实与并发界限

- 对比 4cf 源码，`hasArrived/submitPhoto/markGameDone/probe/samePlace/parseCoordinate/isPhotoWritableStatus` 方法体逐字未改。旧照片、已完成 status、旧局记录都没有新增覆写通路。
- 本次固定点是“进入服务后解析到的实例”，不是客户端最初看到的实例。真实 `TopicRouteSessionMapper.xml:42` 是普通当前 session 读取；在解析之前已发生的重玩仍可能使调用看到新局。这是需结合外层意图/版本和锁序验证的既有边界，不凭本文件宣称全请求线性化，也未据此虚报本补丁新增 P1/P2。
- 解析之后出现新局，本方法各次 SQL 仍使用捕获实例。下游未改的方法在自己的下一次调用中仍会按原策略解析当前局；不能由本次单次固定推出整条跨请求流程绑定旧局。
- 主插入成功后兄弟处理失败必须靠实际外层事务回滚；本补丁没有提供独立调用的事务原子性。主 duplicate 回读、兄弟重复键跳过在源码上不覆盖旧事实；真实连接阻塞、死锁/回滚及回执原子性尚未测试。

## 独立静态核对与待运行矩阵

H 实际执行：Python 对 20 份读取文件校验 Git blob 身份；XML 解析；Java 方法体/测试体静态比较。记录见 `SOURCE.json`、`STATIC.json`。没有把状态机、mock 或静态通过计作 JUnit。

原四类共 57 个普通 `@Test` 声明，候选共 66 个：unit 5→9，两个 H2 类各 9→9，MySQL 34→39（中间负控 36）。旧测试方法体保持不变；负控两项除必需的真实进度服务注入外保留原断言体。这些数字是源码清点，不是执行统计。

| 验证项 | 本轮结论 | 必须取得的证据 |
| --- | --- | --- |
| 真实服务只解析一次，主/兄弟/duplicate 固定实例 | 源码符合；新增 unit 有覆盖声明 | 实际运行 unit，保留捕获参数与 times(1) 断言 |
| 新局可读、旧 default 不碰撞 | 源码根因已改；运行 UNKNOWN | 同源负控 36 与修复 39 的原 XML/清理回执，逐项裁决 |
| 主/兄弟旧照片、完成状态、整行保留 | 新增三个真实 Mapper IT 覆盖声明 | 原整行读回断言实际通过；不得只查行数 |
| 真实并发 duplicate 与 scope 隔离 | UNKNOWN | 两连接 RC：同主、同兄弟竞争；其他 member/activity/topic/局及软删行保持原值 |
| 外层事务与回执一起回滚 | UNKNOWN | 走实际 Spring 事务入口，在主插入后兄弟失败，读回主/兄弟/回执均未提交 |
| 并发切局边界 | unit 仅模拟解析后切换；真实 UNKNOWN | 分别在解析前/后设 barrier，按外层版本/意图合同核对拒绝或原实例写入，不混为同一保证 |
| required 依赖接线及五条入口 | 源码调用参数完整 | 实际 Spring/MyBatis 装配与入口验证；没有 HTTP/JWT/微信实证 |

本报告不复制已修生产代码，也不为尚无可证问题另写重复实现测试。无新增依赖/DDL/迁移；回退生产补丁会恢复“读取当前实例但插入 default”的原风险，不能以回退清除任何已保存凭证。既有资金、退款或其他任务未闭 P1 不受本报告抵消。
