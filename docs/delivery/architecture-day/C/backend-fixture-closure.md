# C 后端第一批全套夹具收口候选

结论：代码与夹具候选已完成第一轮收口，23 个 Java 文件；仅执行静态检查，Java 编译/JUnit/MySQL 均未运行，必须由根受控 CI 验收后才可集成。不是“84 个失败已通过”的声明。

## 基线与边界

- 本批父提交 `ac441634a0226f4786e41f383b9cc8c50880d47a`，独立分支 `codex/architecture-day-c-play-command`。全部新增/修改文件见 `evidence/first-batch-closure/java-candidate-files.json`。
- 根已集成的原指纹 `83a0479` 不重带；E `493c7dead`、root Photo adapter `bb4a18520` 不在本批中。本批不依赖 Photo 新 API，不修改 PhotoCheckVisionService 或其专属夹具。
- 根 `orderedMembers`、ClubLeadChapterIntentMySqlIT 环境/清理修复不覆盖；本树未修改该 IT。根负责 ThemeRuntime/PredictLateBet fixture 的 request_fingerprint 新增列，本批仅改 ThemeRuntime 到达/回放调用参数，保留该集成差异。

## 根因与最小修复

1. **恢复原资格合同**：新增基础回执入口统一强制 paymentStatus=2，错误拒绝原本合法的普通免费报名/自玩。按既有 CurrentPlayRegistration 合同恢复 status+owner 的普通资格，只有原 pk3 探索订单要求已付；仍严格核对真实 member/owner/status，不为缺字段添加 fallback。PlayCommandIntentTest 增加普通免费可执行/读回，未付探索和外账号均拒绝的合同测试。退款和新使用时效仍由原当前锁读规则判断。
2. **个人回执首响表示**：recordPlayerArrival 和 gameplayAction 同章节一样，首响 Map 的 Long 与 JSON 重放 Integer 不等价。首响保存后从同一 JSON 解码；原等价断言保留，业务结果 JSON 与奖励/次数逻辑不变。
3. **合法意图夹具**：新增 BasicPlayCommandFixtures 仅用于测试，从各测试显式声明的 topic/member/activity 与已有 route/shared session 读取原身份，向真实控制器入口提交完整命令。未声明主题或共享局身份直接报错；不注入授权通过函数，不重写业务结果，不在 helper 内吞掉拒绝。回执 mock 用 activity/member/request 三元身份隔离。未建局显式使用 session=0/version=0，与生产 action-context 合同一致；已有局从实际 fixture mapper 取原版本，不把 expected 改成执行后的版本。生产缺字段拒绝仍保持。
4. **保留原业务场景**：旧 checkin/answer/arrive 调用补稳定动作 ID、原目标、expected/runtime；报名夹具补原有归属列，共享场补 session/revision/runtime/epoch。GPS NaN/范围/精度、扫码 USED/EXPIRED/重复静态码/活动结束、旧下架站、冻结答案、真实错误答案/奖励/完成断言均保留。目标预检现在早于码检查，对原先“零节点查询”的断言只计入必要原目标读取；仍禁止静态码回落和 consume/权益副作用。Http 409 测试保留实际 MockMvc→控制器→协调器→全局异常映射路径，不能只测早拒绝。
5. **入口扫描合同**：DeadlockRetryEntryPointContractTest 沿实际 private helper 调用识别 advanceProgress，继续检查最外层 mapped endpoint 的 DeadlockRetry/Transactional；没有降低入口数量或删除事务断言。ApiPlayRouteContractTest 检查当前完整 HTTP 签名。
6. **剩余个人/章节夹具**：PersonalGameplay 的旧重放 expected=0 改为 originalVersions 中首次保存的原版本，保留原回执等价及同键异输入冲突。GameplayChapter 的 scan 缺版本/跨站复用 key 改为每次尝试独立 ID+原版本。GameplayTemplateSlot 的跨节点扫码采用独立 attempt ID。ThemeRuntimeMySqlIT 补 5 处到达原版本和 4 处回放首次版本；不改其 schema/清理和 Photo 配置。

## 真实旧 CI 清单与本批未验状态

原公开 run 40016642215，source 089790aaf3254f62ebbc79394b8aaf5fa66cb2b5。下表是根从真实 Surefire XML 核对的旧结果，均是本批待重新验收项；没有把旧通过/跳过数算作本补丁结果。

| C 测试类 | 旧 tests | 旧 failure | 旧 error |
| --- | ---: | ---: | ---: |
| ApiPlayCheckinLiveCodeTest | 7 | 7 | 0 |
| JourneyPublishFreezeTest | 7 | 3 | 0 |
| ApiPlayRouteContractTest | 1 | 1 | 0 |
| ApiPlaySensorResultTest | 30 | 1 | 0 |
| DeadlockRetryEntryPointContractTest | 4 | 1 | 0 |
| ApiPlayGpsGeofenceValidationTest | 21 | 19 | 0 |
| ApiPlayProgressCompletionTruthTest | 31 | 24 | 0 |
| ShopDayExperienceLayerContractTest | 13 | 3 | 1 |
| ApiPlayDelistedCompletedRetryContractTest | 13 | 8 | 0 |
| PersonalGameplayRuntimeServiceTest | 49 | 2 | 1 |
| ApiPlayHistoryNodesContractTest | 15 | 2 | 0 |
| ApiPlayCheckinDuplicateCodeTest | 1 | 1 | 0 |
| ApiPlayHistoryNodesOldEntryContractTest | 3 | 2 | 0 |
| ApiPlayRouteConflictHttpContractTest | 4 | 4 | 0 |
| ClubLeadChapterIntentTest | 5 | 1 | 0 |
| GameplayChapterRuntimeServiceTest | 11 | 0 | 4 |
| ApiPlayCurrentUseBoundaryTest | 6 | 5 | 0 |

C 合计 17 类、84 failure、6 error；D 的 OfficialBroadcastSubmissionControllerTest 另 1 error，不在 C 修改范围。ClubLeadChapterIntentTest 的类型根因已在先行 3e25bdd 修复；本批其余 16 类逐类处理调用或生产根因。方法级原始 XML 在 C 环境无法完整下载，已向协调者请求失败方法短索引；PersonalGameplay 的 expected=0 错误已经从源码精确定位。所有 Java 仍需真实重跑，不能用静态分析宣布全部收口。

## 实际检查与验收请求

- 实际 `git diff --check`、23 Java 文件括号/字符串剥离静态平衡检查通过；此检查不是 Java 语法编译。
- 实际确认 PlayCommandIntentTest 原 missing ID/missing version/stale expected 零副作用断言仍在。
- 提交前以现有配置扫描本批源码、报告及 diff，不增加 gitleaks ignore/baseline。
- 未运行 Maven、Java、后端进程、MySQL、Redis、H2 或 Mock 结果冒充真实存储；没有执行 DDL、支付、模型或生产访问。
- 根先把本批与原集成修复合并，再受控跑上述 C 全类、ThemeRuntimeMySqlIT，以及 3e25bdd 的真实 Mapper 锁后回读追加用例（根 IT 保持清理、登记 4 条）。随后完整 Java/小程序 shards 与门禁统一验收。

## 迁移与回退限制

本批无 schema、依赖或全站框架变更。只恢复普通免费报名既有资格，不作新的金额/退款规则决定。不回退严格原身份，不清除已执行回执，不因响应 TTL 释放业务身份。若 CI 仍失败，保留原场景断言继续修复，禁止恢复随机 UUID 或缺版本入口。
