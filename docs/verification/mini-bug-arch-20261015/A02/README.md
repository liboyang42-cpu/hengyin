# A02：AI bug 前半交付，架构后半待批次屏障

共同源码基线：`d551c6babd018d41aec90dad2c0acaf1c534644d`。本单只修改 AiSceneRouter、AiGatewayServiceImpl 与各自专属 Java 测试，不改依赖、配置、DDL、价格或留存政策。没有调用真实收费模型，也没有运行 Java/DB 构建或集成测试。

## 复核结果与最小修复

| 线索 | 基线源码证据 | 当前改动 |
|---|---|---|
| 未注册场景返回假成功 | AiSceneRouter.resolve(null/unknown) 返回 `mock`；MockLlmClient 是真实 Spring bean | 未注册/空场景抛 `AiServiceException(UPSTREAM_UNAVAILABLE)`，保留 `npc_event` 缓存路由 |
| 两条 provider 选择链缺配置时用 mock | selectClient 的 bean 命中和旧 clientMap/resolveClient 均可拿到 mock；两条 map 链有 `m.get("mock")` 兜底 | 两条链及两个 bean 名别名共同经过 requireAvailableClient；未知 provider、显式 mock、mock bean 误绑定均明确不可用 |
| 未审核后缀与 system 上下文进入模型 | 只 checkText(truncate(userPrompt, 2000))，随后原样 client.chat(systemPrompt,userPrompt) | 对 system + 换行 + user 完整组装文本分段审核后才调用模型，provider 原始参数不变 |
| 超长输出审核长度与输入不同 | 输出整段送检，输入仅前缀；超长输入后的审核无保证 | 输入输出复用完整审核 helper，每段最多 2000 UTF-16 单元，重叠 100 单元，不切断合法代理对；任一段拒绝/异常即终止 |
| 输出审核不可用被当违规/裸异常 | secOut 非 null 全按违规；RuntimeException 原样逃出 | 固定不可用文案和异常统一成 UPSTREAM_UNAVAILABLE；输出已经生成，沿原政策不退额度，记录 OUTPUT_REVIEW_UNKNOWN |
| 空模型结果可被当成功 | checkText(null) 可放行，返回 text=null | null/空白回复抛上游不可用，不产生成功结果 |

2000 的分段上限来自既有网关前缀审核界限；底层 WxContentSecurityService.checkText 原样发送文本，没有再次截断。100 字符重叠用于保留边界附近上下文，不声称等同于整篇语义理解。分段会增加审核调用次数，真实微信限流/延时及配置仍需专用环境验证。原有 `wx.sec.check.enabled` 开关、各池大小、输入明确违规不退款、输出已生成不退款均保持原行为。未启用审核时仍由既有服务返回放行，不在此更改生产配置。

## 实际执行与未执行

实际执行：

- `python3 docs/verification/mini-bug-arch-20261015/A02/check_source.py d551c6babd018d41aec90dad2c0acaf1c534644d`：退出 1，6 项源码 guard 均失败，见 baseline-source-check.txt。
- `python3 docs/verification/mini-bug-arch-20261015/A02/check_source.py`：退出 0，6/6 源码 guard 通过，见 updated-source-check.txt。
- 本单 4 个 Java 文件 `git diff --check`：退出 0，无空白错误。

上述是源码回归证据，**不是 Java 行为测试通过**，不能凭 6/6 关闭运行时验收。遵守任务边界，本 Cloud 没有编译 Java、启动 Spring/Redis/MySQL 或尝试远端服务。Java 回归文件已写，待协调者在已授权专用 VPS 或既有 GitHub CI 集中运行：

- AiSceneRouterContractTest：真实 creator 场景路由；null/空/未知场景报不可用；cache 路由保留。
- AiGatewayServiceImplTest：已有额度/trace 回归；新增两条 provider 分支、显式 mock、bean 别名 mock、缺 key/disabled（真实 client readiness 在网络调用前拒绝）、未审核后缀、system 上下文、全部字符覆盖与原始参数、长输出、输入后段异常、输出审核不可用/异常、空模型响应、超时、UTF-16 边界。
- AiQuotaRecoveryServiceTest、AiQuotaReservationRedisIT、AiQuotaRecoveryMySqlRedisIT：现有补偿/原日/代次/回执测试需要集中串行执行。
- 独立业务读回、实际微信审核、MySQL 日志与原日额度守恒、专用 VPS、设备均未验证。

## 后半待协调者放行

网关已有模型调用 catch 会对所有 RuntimeException 退款，包含可能已被 provider 执行的超时；本次仅证明不会返回假成功，没有声称已满足结果未知对账。这是后半必须处理的现存缺口，不能把新增超时测试视为未知成本已解决。

后半只读准备清单见 phase-two-inventory.md；尚未修改那些文件。A02 同一 owner 保留，等待 B01–B05 及 bug 批次证据处置后继续；无新增场景/长期记忆/UI。
