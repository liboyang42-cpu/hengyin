# C 后续候选：Advanced 原动作指纹

结论：已实现服务端请求指纹与保留式历史拒绝，尚未经过 Java 编译或真实 MySQL 验证，不能正式集成/部署。第一批 `9a90123b4a3735eb9303f4b38b72e81e51694be5` 保持原样。本报告替代第一批 advanced-fingerprint-design.md 中“未实施”的状态，仅覆盖 H-01 指纹项；Photo 编排是下一批独立合同与补丁。

## 基线、隔离与文件

- 公共仓库：`liboyang42-cpu/hengyin`。原参考基线 `2ec68899099a1e250df0949a8383bf85b5d3ffe0`；本批直接父候选 `9a90123b4a3735eb9303f4b38b72e81e51694be5`。
- 独立工作树 `/workspace/hengyin-c`；分支 `codex/architecture-day-c-play-command`；没有切换/回退其他会话工作树，也没有导入私有历史。
- 生产文件：AdvancedGameRuntimeServiceImpl.java、PlayAttemptLog.java、PlayAttemptLogMapper.java、PlayAttemptLogMapper.xml。
- 新迁移：`chengyinhub-admin/sql/migration_20261023_advanced_command_fingerprint.sql`。
- 测试：新增 AdvancedCommandFingerprintTest.java / AdvancedCommandFingerprintMySqlIT.java；更新 AdvancedGameRuntimeServiceImplTest.java 与 AdvancedGameRuntimeMySqlIT.java 的相关回放断言/迁移前提。未修改根负责的 Photo 文件/夹具、共享 service interface、CI、pom、其他人的交付目录。完整路径与 SHA256 见 `evidence/advanced-fingerprint/files.json`。

## 调用链、失败机制与修复合同

主代码只有 ApiAdvancedPlayController.action 调用 `IAdvancedGameRuntimeService.act(memberId,sessionId,expectedVersion,idempotencyKey,action,payload)`。当前小程序 `pages/play/components/advanced-game/index.js` 的 act/resolveUnknown 发该 HTTP，现有调用已传 version/action/key/payload；不引入旧小程序缺字段兼容。

原 act 在授权后按 key 返回 PlayAttemptLog.resultJson，只校验 actor/session，改 action/payload/expected 仍会得到旧成功。原 logAction 的重复键 catch 只验证旧回执然后继续，存在本事务已写效果继续提交的风险。

本批在任何业务 helper 前复制输入并计算 `advanced-act:v1` SHA256，绑定 memberId、sessionId、活动/主题/节点、固定 owner/原 playInstanceId、原 expectedVersion、完整客户端 key、trim/ROOT upper 后 action 和递归排序对象键的完整 payload。数组保序；数字/字符串、null/缺字段不同。不读实体当前 version/status/epoch 计算 hash，因此合法旧命令在状态推进后仍可读原结果。现有 scope key 算法保持；hash 另外绑定完整 client key，避免其截断摘要把不同原 key 当成同意图。

读回顺序为输入必要字段 → session 读取授权 → 原 key lookup → actor/session/指纹核验 → 原结果，早于当前版本/资格状态冲突。expected 缺失/负数、action 空拒绝；同 key 异输入/异 expected 返回 409，不调用模型或做本地写。历史 NULL/非法 hash 返回“历史动作身份无法证明”，不猜值、不清 key、不自动换身份。

所有 4 个 logAction 写入分支（普通动作、反馈 CHOOSE、RETRY 重开、零血 RETRY）都携带进入入口时冻结的指纹，与业务效果同事务。重复键 catch 核验旧主体/session/hash 后仍抛出冲突，使输家事务回滚；不得在有本地写的事务中直接返回旧成功。并发 CAS/唯一键输家可收到冲突，随后以原命令重试读赢家；本批没有宣称并发首次响应必定直接成功，也没有将冲突标成 intentNotExecuted。

## 真实执行证据与未运行检查

- 实际运行 4 个 Node 文件，回读 **77 tests / 77 pass / 0 fail / 0 skipped**：advanced-game-receipt-contract 50、advanced-game-variants-recovery 7、advanced-game-object-card-relay 8、play-merchant-advanced-handoff-contract 12。逐条日志和精确命令在 `evidence/advanced-fingerprint/`。这些只验证当前调用者的前端回执合同，不代表 Java/数据库正确。
- 实际运行 `git diff --check`；Python XML 解析 PlayAttemptLogMapper.xml；人工检查全部 logAction/replay 调用点。静态检查输出随证据提交。
- 实际运行既有 secret-file-gate 与已校验来源的 gitleaks 8.24.3，扫描暂存文件及展开 diff（同时扫描下一批 Photo 合同文本），0 findings；未放宽规则或 baseline。精确范围与 diff SHA256 在 `evidence/advanced-fingerprint/secret-scan-status.json`。
- 新增 11 个 Java @Test、5 个 MySQL @Test，均**只编写，未执行**。包含三个同 key 异语义负控、原结果先于当前版本/epoch、授权、历史 NULL、递归键/数组/类型、同答案新 attempt、答错次数和奖励不漂移、重复插入必须回滚；MySQL 用例覆盖真实 mapper hash round trip、迁移重跑/保留 NULL、真实事务效果与终态同提交、同 key 并发唯一赢家、错误列/丢失唯一索引拒绝。MySQL 类中的最小效果表是事务边界夹具，不能冒充完整 act/Photo 链路；现有 AdvancedGameRuntimeMySqlIT 的实际 service 回放处另补 changed action/input/expected 无变更断言。
- 新 MySQL suite 要求显式 HOST/PORT/USER/PASSWORD/DB_SUFFIX，限制 loopback、端口和后缀；记录自身精确 schema；AfterAll 先关 pool 再只删本 suite schema。没有使用 helper 的默认 root/空口令去启动环境。
- **未运行** Maven/Java 编译、Java 单测、MySQL/Redis、真实 Photo HTTP、微信真机；没有执行生产或本地 DDL、没有启动 Java 后端/数据库。相关检查由根在获准环境统一安排。没有以 H2/Mock 代替真实链路结果。

建议受控执行：`AdvancedCommandFingerprintTest,AdvancedGameRuntimeServiceImplTest`；新真实存储 suite `AdvancedCommandFingerprintMySqlIT` 静态登记 5 条，另运行已有 `AdvancedGameRuntimeMySqlIT`。根负责 CI allowlist/预期实际条数与环境注入，本批未改 CI 门禁或扩大 baseline。

## 迁移、集成接线与回退

扩一列 nullable `request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin`。普通埋点和历史行仍可为 NULL；新高级命令强制非空 hash。SQL 预检 InnoDB、原结果/key 列、完整且非前缀的唯一索引、已有列精确定义；即使执行器不解释 runner 注释，失败 guard 也以 SQL 错误停止。新列前部署、回读确认后再部署代码；不能 catch 缺列继续放行。只编写迁移，不执行任何 DDL。

共享夹具的精确集成余项（本分支未修改它们，避免越过独占范围）：

1. `ThemeRuntimeMySqlIT` 在其 `migration_20260820_new_city_preference_chain.sql` 后加载本迁移，否则新的 PlayAttemptLogMapper 使用新列会失败。
2. `PredictLateBetRaceMySqlIT` 的手写 `create table play_attempt_log` 增加同定义 `request_fingerprint` 列，或在该表完成创建后执行本迁移。不得改预测业务断言。
3. 其他会执行普通 PlayAttemptLogMapper 的 schema 路径同样先扩列；生产预检与窗口由根/用户掌控。不要把仅编译通过视作迁移已验证。

回退保留新列、旧 NULL、所有回执和唯一身份。有新指纹回执后不能退回不校验 hash 的写代码；必要时关闭入口并保留读取/调证。不能 DROP、清表、猜 hash 或删除执行 key。

## 仍阻塞/下一批

Photo 外层事务回滚后可能重调供应商的问题仍未修复；root 的 Photo 单文件异常修复不能代替持久原意图。下一批按 `photo-orchestration-contract.md` 的 prepare/executeOnce/read/markApplied 签名、事务外 provider、原 nonce/UNKNOWN、持久 RESULT_READY + 原子玩法回执边界衔接，等根合并共享接口后实施。G 的 myCompleted/无票 FREE_EXPLORE 接线排在核心写合同稳定之后，不混入本指纹提交。
