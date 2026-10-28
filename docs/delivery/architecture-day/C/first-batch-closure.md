# C 第一批验收补丁：P1 缓存与章节回执

结论：修复代码和相关测试候选已编写；仅执行静态检查，Java/MySQL 未在云 VM 执行，不能声称 P1 已经真实链路验收通过。完整第一批夹具收口仍在继续。

## 基线与范围

- 本补丁父提交 `4e0bd27f823de1c74399fa3d6b05492f12c60fec`；独立工作树 `/workspace/hengyin-c`，分支 `codex/architecture-day-c-play-command`。
- 首批 `9a90123b4a3735eb9303f4b38b72e81e51694be5` 和高级玩法指纹 `83a0479d2d28987e2ec757659c9ed7655ba8c742` 均保持原样。
- 本批修改 `ApiClubLeadController`、章节专属单测，以及 H-C01 精确涉及的 `GameSessionRuntimeMapper.selectReceiptForUpdate` XML 属性和该 Mapper 的配置断言；无接口/schema/全局缓存配置变更。
- 不改根拥有的 `ClubLeadChapterIntentMySqlIT`；不覆盖根的 `orderedMembers` 编译修复或 IT 环境/清理修复。

## 失败机制和合同

1. `ApiPlayProgressController.executeBasicIntent` 先查同 key 回执，再等待聚合锁，再查同 key。MyBatis 默认 SESSION 一阶缓存可保留第一次空结果，导致第二次没有访问数据库，误报 `intentNotExecuted`。锁读的 `flushCache=true` 清空一阶缓存，`useCache=false` 同时禁止二阶缓存；保留原锁、身份检查、UNKNOWN 规则，无全局配置变更。现有 `PlayCommandIntentTest.concurrentWinnerIsRecheckedAfterBusinessLockBeforeVersionConflict` 保留；新增 Mapper 实际配置断言。该 Mapper 原有导演和章节调用者也使用同一精确查询，SQL/参数/返回结构不变。
2. 真实 MySQL run `40016650848` 的章节 3 条中 2 通过、1 失败，清理 CLEAN。首响业务 Map 的小 ID 是 Long，Jackson 无类型 JSON 回放为 Integer，导致 Map.equals 失败，与键顺序无关。首响完成持久化后也通过同一 `chapterReplay` 解码，统一 POST 首响、POST 重放和 GET 回执的 Java 表示；HTTP JSON 数值和语义不变。保留原等价断言，补小 ID、显式 0、大于 Integer.MAX_VALUE 的 ID 三种读回覆盖。

## 实际验证与未执行

- 实际执行：`git diff --check`；Python XML 解析及精确 `flushCache/useCache` 属性断言，通过。
- 实际执行：`git apply --check docs/delivery/architecture-day/C/evidence/first-batch-closure/receipt-cache-mysql-regression.patch`，通过。该补丁源于 H-C01 已审查的双事务实际 Mapper 用例，仅作为根 IT 的追加方法候选交付，未应用到本树 IT，也未运行。根应用后需登记 IT 从 3 条变为 4 条，并保留其当前环境守卫和 AfterAll。
- 未执行：Java 编译/JUnit/MySQL/Redis/HTTP/微信真机。Java/MySQL 只由协调者受控环境运行；此处不把 Mock 或静态结果当成真实并发证明。
- 发布前扫描：提交前使用现有 gitleaks 配置扫描本批文件及 diff，结果随本次提交记录。

## FULL CI 待收口清单

公开 run `40016642215`，source `089790aaf3254f62ebbc79394b8aaf5fa66cb2b5`：根读取 1751 份 Surefire 报告且校验摘要全部匹配，后端总 13854 条、84 failure、7 error、631 skipped。`evidence/first-batch-closure/backend-failure-classes.json` 登记全部 18 个失败类，其中 D 的 OfficialBroadcastSubmissionControllerTest 为 1 error，C 其余 17 类为 84 failure/6 error。此数字是该次真实 CI 结果，不是本补丁重跑结果。

小程序失败日志实际提取 54 个失败项，文件/场景/错误见 `mini-failure-inventory.json`。其中 broadcaststats(clicked) 静态契约属于 D；其余需逐条区分 C 账号/命令上下文 fixture、GET/控件完整调用链清单及产品文案，不放宽命令合同或授权以让旧测试通过。完整 Java 方法级 XML 在本机尚不可用（日志连接器超出传输容量，artifact 返回 File Service 引用，shell 下载受网络策略拒绝）；当前基于根已验证的类级清单和源码逐类定位。

## 迁移和回退

无 DDL、数据迁移或历史清理。缓存属性增加读库频率，范围仅命令回执锁读。不可回退该属性来掩盖并发错误；章节表示修复仅统一既有 JSON 回执解释。回退需保留已写业务身份及回执，不能删除已执行结果。
