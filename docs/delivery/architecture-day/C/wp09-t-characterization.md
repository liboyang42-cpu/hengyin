# C：W09-T 节点提示解锁真实 MySQL 表征候选

结论：新增独立 `PlayHintUnlockMySqlIT`，在**当前未抽取的** Controller 入口表征完整提示解锁命令；没有改生产接口、共享 helper、HTTP caller、schema、根构建或 CI。8 个普通测试 + 2 个参数用例仅为已编写的 10 项，**Java 编译/JUnit/MySQL 均未运行，WP09 尚未完成**。五红修订另已冻结在 e190，不混为同一补丁。

## 基线与交付身份

- 公共仓库：`https://github.com/liboyang42-cpu/hengyin`。
- 本轮 parent：`e190fcbf502693c08bb0d43474ca1c221604f3bb`，其 parent 为 root 完整 CI 源 `66e9e1798bf0b3e1c2b7e6cb2b6ab13a3b3e1329`。
- 工作树：`/workspace/hengyin-c-photo-tests`（C 独立所有，无他人写入）；分支 `codex/architecture-day-c-wp09-characterization`。原 C 分支和 e190 分支均保留，不重写。
- 实施依据：公开 `4a6e3cf423594f4e4dc4b30aa769a90048f6aa6e:docs/delivery/architecture-day/COORDINATOR/governance-scope.md` 的 WP09 / T14；既有 doc-only 方案在 `d359cd2de8d4b5f78fc2f998db969ad8ae657327:docs/delivery/architecture-day/C/wp09-plan.md`。root 本轮只授权 W09-T，不授权生产接口和 14 helpers 迁移。
- 候选身份为本报告所在提交；只取 `git diff e190fcbf..HEAD` 的三个新增文件即可与五红提交分开审查。

## 实际入口、caller 与表征合同

原生产调用链：小程序 `pages/play/index.js` 的 `unlockHint` → `POST /api/play/hint/unlock(nodeId,activityId,topicId)` → 可信 `currentMemberId` → node / `resolveCtx` → **真实 CurrentPlayRegistration + activity / registration 锁读** → lead / route gate → template → **真实积分规则** → 会员普通余额预检 → **真实 event14 唯一流水** → **真实 point>=cost 条件 UPDATE**。仍用原 Controller 方法注解的 REQUIRED / 默认隔离 / rollbackFor=Exception，测试经 CGLIB + Spring TransactionInterceptor，未用直接未代理调用冒充事务。

当前入口没有 actionId / expectedVersion / 原响应 receipt，永久身份是 `(member_id,event_type=14,event_id=node_id)`，覆盖同一节点的活动与自玩；重复返回当下提示 + cost=0。本测试明确表征这些现存事实，不声称补上原意图合同或现代应用服务 API。真实 caller / 原有 `ApiPlayHintUnlockTest` 不改。

| 已编写场景 | 观察与断言 |
| --- | --- |
| 合法自玩、合法活动（2 参数用例） | 提示原值 / cost10，余额100→90；一条 member7/event14/node401 支出；真实 current/after/change 快照及身份；报名无改动。 |
| 匿名、未知节点、异 topic、无资格 | 精确 code/msg、无 hint 正文；余额/流水/报名零变化。 |
| 丢响应后原请求 / 新 Controller / 合法跨活动范围重复 | 首次 cost10，后两次 cost0；恰一条流水、扣一次分。只重构 Controller 与事务，**不是进程崩溃重启实证**。 |
| 真两连接同节点并发 | 第一事务在真实 ledger INSERT 后受闩锁阻挡；第二连接到达入口后等待原报名锁。记录两个不同 CONNECTION_ID，等待负断言后释放；一个 cost10、一个 cost0，最终一条流水 / 余额90。 |
| 预检后另一事务成功消费95分 | 用另一真实 REQUIRES_NEW 事务的账本 + debit 执行交错；原 hint 的条件扣分失败，返回原错误，hint 流水回滚；另一消费流水及余额5保留。 |
| ledger 真 INSERT 后抛错 | 真实事务回滚 ledger，原异常精确保留，余额100，资格不变。 |
| debit 真 UPDATE 后抛错 | Controller 原 catch 设置实际事务 rollbackOnly；余额和 ledger 一起回滚；原请求正常再试只扣一次。 |
| 买过提示后资格撤销 | 原流水不授予被撤销资格读取权；拒绝无正文、原扣分/流水不漂移，撤销事实保留。 |
| 规则停用 | 无资格仍拒绝；合法资格免费，零积分写。 |

## 真实设施和替身边界

- 真 MySQL / MyBatis Mapper XML：CmsRegistration、CmsActivity、ClubLead、CmsPointsSetting、UmsMember、UmsMemberPointsDetail；真 `DataSourceTransactionManager` 和 SqlSessionTemplate；points/member/detail 的生产 Service 参与真实 SQL。故障注入只包住真实写入前/后的测试 service，不把写入换成返回1。
- 复用公开 `it-schema/run41-prod-ddl.sql`，以已存在的首次扫码和 ClubLead canonical migrations 补齐读侧；从公共 `sql/base_schema_20260818.sql` **逐字提取**积分规则和积分明细 DDL，保留原唯一键。静态提取 SHA 见 source-proof；运行时再读 information_schema 精确核对 `uk_member_event_once`。
- node/template/topic 的目录数据为明确的只读 mock 输入；本套不替代内容库查询与路由/带领各分支验收。当前测试选择有真实资格的 FREE_EXPLORE 主题、无节点前置、无带领记录；不伪称表征了全部14个 helper。
- 身份来自受控测试覆盖的 getAppUserId；没有真实 HTTP/JWT/RateLimiter 验证，没有调用将来的应用服务（它尚未实现）。并发 CONNECTION_ID 读取仅测试取证，不算生产 SQL 预算。

## 隔离、预检、迁移与回退

仅在批准 CI/VPS 执行。BeforeAll fail-closed 要求显式 `CHENGYIN_MYBATIS_IT_HOST/PORT/USER/PASSWORD/DB_SUFFIX`；host 必须127.0.0.1、port为合法数字端口、suffix为 `_[a-z0-9_]{1,20}`。只创建/初始化本 suite 精确 schema `chengyin_mybatis_c_hint_unlock<suffix>`；无默认凭据降级、无环境缺失 skip。AfterAll 先关本 pool，只 DROP 记录的精确 suite schema，不枚举/清除其他库。线程池 finally 释放闩锁、shutdownNow 并等待本 pool 终止。

没有生产迁移、没有新增业务表/索引，不执行 DDL；测试里的建库/建表仅待受控运行。回退只撤新增 IT/交付证据，不回退 e190、9da/971、root Theme/APPLIED/台账或任何生产接口。

## 实际执行与仍阻塞

- 实际执行：`git diff --check`；Python 源括号检查、六 XML 解析、两份 canonical DDL 唯一提取和 SHA 核对、Controller 与 parent 字节一致检查；既有 Gitleaks 规则扫描新增材料。静态结果在 `evidence/wp09-t/`。
- **未执行：Java 编译、10 项 JUnit/MySQL、迁移 SQL 执行、并发锁时序、rollback、清库门禁、应用启动或 HTTP。** 静态 XML 解析不等于 MyBatis 配置加载；源码用例数不是真实执行数。
- root 需将该独立类接入批准的真实存储 job / cleanup 账本并绑定候选 SHA；C 未改 workflow、未 dispatch。受控执行前先确认五红提交编译/测试结果。
- 余项：旧余额预检早于重复判断、缺显式原意图/版本、规则/模板变化后的历史响应语义、不同节点消费快照守恒、全部路由/带领/前置 helper、SQL 预算和完整 T14 两候选向量，均不能由本套窄场景推为通过；如旧缺陷在受控运行中报红，保留红证据，另行冻结行为修复合同。
- 生产 `PuzzlePlayService` 接口与 shared helpers 移动继续暂停待 root 另审；WP09/WP13 不能报全完成。
