# CCD5 隔离管理员鉴权与退款租约 Redis 恢复子链回读

结论：本次子链 **BLOCKED_BEFORE_HTTP**。准备阶段的 schema builder 失败，未启动应用，未执行任何本子链 HTTP 或租约业务断言。不能登记为 Q2/Q3、完整产品或资金闭环通过。

## 本次证据身份

- 公共 source：`ccd55005c75d105f8245b0591e63adcd0dd15280`
- ref：`codex/architecture-day-final-ccd5-20261024`
- 原 workflow：`.github/workflows/isolated-authenticated-redis-recovery.yml`
- [run 40142606869 / attempt 1](https://github.com/liboyang42-cpu/hengyin/actions/runs/40142606869)
- [原 job 121017302795](https://github.com/liboyang42-cpu/hengyin/actions/runs/40142606869/job/121017302795)，结论 failure；未由 B 重跑。
- [原 artifact 12579491081](https://github.com/liboyang42-cpu/hengyin/actions/runs/40142606869/artifacts/12579491081)：`isolated-authenticated-redis-recovery-1`，4640 bytes。
- 下载 ZIP 实际重算 SHA256：`7c591206ab6ec6d4c99749f7c210b7ba969139815341404632c8daed886bda40`，与 GitHub artifact digest 一致。
- 本目录仅保存脱敏回读结论，不复制凭据、JWT、验证码答案、lease token、私有输入或原 application 日志。

ZIP 仅含三件原件：

| 原件 | SHA256 |
| --- | --- |
| `entry.log` | `dd2f05d66afc95e19a22d8e7ed99fd32859e6427d7fd7d1a967bef8e48e4f752` |
| `cy-auth.n58B0kbV/schema-summary.json` | `59c3d23f10c268b1b2fa56fa6b402da3f1a88f8f58b63ad560d18f7eefd7c6bb` |
| `cy-auth.n58B0kbV/authenticated-redis-recovery.json` | `6f3e129ba5d2ee40e460c1a656adfb236afc085c3f066b39534153713d9360fa` |

## 原 harness 盘点：以下是执行计划，不是本次通过项

`ci/authenticated_redis_recovery_probe.py`、`ci/authenticated-redis-recovery.sh` 均按同一 source 读取。

- 身份：新鲜隔离 schema 内 bootstrap 的管理员 `admin/user_id=1/role_id=1`。它是合成测试身份，不是生产真人。本次在 bootstrap 前已中止，未完成该身份登录。
- 入口：`/captchaImage`、`/login`、`/getInfo`，以及 `/business/refund/manual/claim`、`/business/refund/manual/release`。
- CAPTCHA 计划保持开启；oracle 直接从本次隔离 Redis 读取答案，故即使执行成功也不是真人 UI/CAPTCHA 可用性验证。
- 业务夹具：直接预置 `refund_application.id=20`，`refund_no=NULL`、`status=1`、`payout_status=2`，金额 1 元，仅作未提交渠道的人工队列租约；不创建真实付款、退款或渠道请求。
- 状态机：空 owner/token/expiry → 原管理员领取并持有有效 lease → 重复领取和错误 token 拒绝 → 暂停确切自有 Redis → 原 release 得到明确鉴权拒绝、整行不变 → 恢复同 Redis、同 JWT 身份 → 原 token 释放、lease 三字段清空 → 重放 release 拒绝。
- 证据判据：独立 Python MySQL 连接全行比较；仅允许 owner/token/expiry/update_time 改变；九张资金/权益表和权限/账号摘要保持不变。故障拒绝必须业务码 401，允许 HTTP 200 或 401，不能仅看 HTTP 状态。
- 隔离判据：GitHub-hosted、内部 Docker 网络、精确 owner label/CID；故障前后 app/MySQL/Redis PID 和运行状态相同；结束时独立 inspect 验证确切容器/网络不存在。

## 已验证：仅限本次准备与证据回读

- 原 job checkout 日志、artifact metadata 的 source 均为 CCD5。
- hosted 执行环境与纯负控步骤成功；原日志记录 10 tests / OK。它们不是本子链业务通过证据。
- 原 job `mvn -B -DskipTests package` 成功。schema 摘要报告 embedded checkout 与 CCD5 一致，native JAR SHA256 为 `ce95029a38951e2cbe897ff73ddbfe8368925d21c0ed35b2312bbd028e1cb2b4`。ZIP 不含 JAR，B 未独立重算 JAR 字节；不转签原 4ef3 制品成绩，也不声明与它同一制品。
- 原 schema 摘要报告自有 MySQL 8.0.45 TCP ready，无宿主发布端口，relay 与 native server 身份匹配。
- builder 日志：base schema 227 表，基线问题 0；seed 问题 0，seed 负控 6/6；最终 254 表，迁移阶段问题 1。这些是准备阶段观察，不是业务或完整迁移验收通过。
- 原 terminal JSON 报 `CONFIRMED_EXACT_OWNED_REMOVAL`；MySQL、Redis 两容器均记录 `independentNoSuch=true`。该标记在源码 cleanup 完成确切容器/网络缺失检查后才写入。B 未另连 hosted Docker 复查资源。

## 问题与精确首因

首因在 `prepare`：`full schema builder failed; no continuation`，`builderExit=1`。

原 `schema-summary.json.builderLogTail`：

```text
迁移阶段问题: 1
[ASSERT] 20261023_g_wp08_delivery_jobs_paused #3  ValueError: scalar-true readback=0
SELECT (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND engine='InnoDB'
```

引用链为 probe.py:350–369 的 builder 与退出码门禁；shell:52 在启动应用的 shell:58 前失败。此处只定位首个失败断言，未进一步归因为业务代码或迁移规则缺陷。

次级错误：应用尚未创建，cleanup 无 application log，随后记录 `application logs unavailable after exact resource cleanup`，外层打印 `exit=1 cleanupUnknown=1`。最终 JSON 的 phase/reason 被 cleanup 错误覆盖，不能用它替换 entry/schema 摘要保存的 prepare 首因。资源移除确认与应用日志缺失分别记录，不把前者说成完整成功，也不把后者误报为容器残留。

## 未验证与必要下一步

| 项目 | 本次结果 |
| --- | --- |
| 应用启动、管理员 bootstrap、真实 HTTP 登录/鉴权 | NOT_RUN |
| CAPTCHA HTTP 正负例、原 JWT 身份回读 | NOT_RUN |
| claim 落库、重复领取、错误 fence | NOT_RUN |
| Redis 暂停下的鉴权拒绝及整行/资金权限零差值 | NOT_RUN |
| 恢复后原身份/原 lease 释放及重放拒绝 | NOT_RUN |
| app/MySQL/Redis 故障前后 PID 不变、运行期出站约束 | NOT_RUN |
| 原 HTTP 结果、独立 SQL before/after 差值 | 无执行证据 |

最终 JSON 无 `events`、`protectedBefore`、`protectedAtSettlement`、`finalRefundRowSha256`；artifact 无 `application.log`、`network.trace`、`isolation.log`。因此不能将缺少差值解释成“差值为零”。

schema 前置依赖由 G 修补，root 统一集成并安排原 workflow 的完整 HTTP/SQL/Redis 合同验证。B 后续获授权仅修 probe 诊断层：保留 prepare 首因、分离 cleanup 次级错误，仅在已知 pre-app 失败且精确确认 app 不存在时标记 NOT_STARTED；损坏/不可读报告不免除精确资源清理，也不推定未启动，记录读取错误类型并以 FAIL_UNKNOWN 结束，不复制坏报告内容或编造原首因。shell、schema、权限及资金/租约逻辑不变。原 10 条 Python 控制保留，新增 7 条，最终定点实跑 17/17 PASS，完整补丁 apply-check PASS；这不改变上述原 run 的失败记录，也不是 hosted 业务验证。

普通用户 UI、人的意外矩阵、现金付款/真实退款/渠道、完整业务闭环始终不在本次已跑范围。B 未自行重跑 CI 或运行 Java/DB。
