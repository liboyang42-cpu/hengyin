# F / WP13：有界 LLM transport 候选

结论：已实现已授权的 transport、固定 AI admission、最小 LLM adapter 与专属测试源码；**未运行 Java/HTTP fixture，未证明编译通过，不可启用**。Gateway/Photo 的 typed NOT_SENT 经济 catch 归 E，root 的 AiCreator typed UNKNOWN 修复不在此 delta；共同集成/实际验证前不能把本候选算作经济合同闭环。不是全部 WP13 完成。

## 基线与独占

- 公共仓库 `https://github.com/liboyang42-cpu/hengyin`；实际 parent `05f4aa69e61752dcafb0bec73ca9ae9b2ff7a7f5`，分支 `codex/architecture-day-f-wp13-http`，独立 worktree，干净起点；原公共2ec仅为历史参考，不套旧行号。
- 只改 common `HttpUtils/HttpRequestException`，system `OpenAiCompatibleLlmClient/LlmClientConfig`，新增两个小型 `AiHttpAdmission/AiProviderNotSentException`，两个新 `*Test`、两个原 HTTP 协议/脱敏 Test 及本目录报告。完整名单和 parent/current blob 指纹见 verification.json。
- 不修改 root/E 的 AiCreator、gateway、Photo、quota、业务 XML、POM、CI/registry、SQL；155 checker/685 producer 冻结保持。用户已明确批准先提交已审查/秘密扫描的隔离 CI 候选，Java 仅 root 获准环境运行；未绕过提交前 Java 限制去本 VM 编译。

## 失败机制、调用者和最小修复

原 `OpenAiCompatibleLlmClient.post` 的 Hutool 同步 execute/body 缺硬总 deadline/响应体预算/供应商并发准入；socket timeout 无法挡持续慢滴流，事后 substring 不限制堆。先写新的控制测试源码，再复用 common 原 8..32 worker/128 队列/AbortPolicy、1..4 cleanup/64 队列、connect/read/total、readLimited 1 MiB 及 secure client。没有新生产线程池/依赖/动态注册平台。

发送前局部确定无外呼与已发未知分开，公开签名和 E 接线要求见 [CONTRACT.md](CONTRACT.md)。QUEUED→PREPARING→DISPATCH_ENTERED 与取消 CAS 竞争：QUEUED 取消成功可立即释放，PREPARING 取消禁止 execute 但等待 worker finally，dispatch 赢则只能 MAY_HAVE_BEEN_SENT；finish 保留是否曾 dispatch。caller close 对已移交 lease 无效，cancel Future 不提前释放，排队清理只 remove 本 task。cleanup 饱和仍拒绝且不 CallerRuns，物理任务未退即占槽，后续 AI fail-closed。

新认证 POST 不重试、不跳转、不走 legacy trust-any，并为该请求禁自动 auth challenge 重放；byte cap 在 Apache 解码流后累计，gzip/chunked 不能靠压缩长度绕过。header/URL 本地校验失败使用固定错误，不输出原 cause/auth/URL/body。common 旧 public exception 构造器没有未发证明，默认 MAY_HAVE_BEEN_SENT。旧 GET/form/SSL 走 null lease 的原 retry policy；底层 submit 改为等价显式 FutureTask 以仅删除本次排队 task，未扩大 pool/queue。

adapter 在准入和 JSON 序列化前取绝对单调 deadline；global4/origin2/queue0 获技术批准。所有生产 bean 共固定 startup 表；同 origin 的不同模型/路径共两槽，所有表实例仍共进程四槽。非法 endpoint、空 baseUrl（不再偷偷回落公共 provider）、无效 key/model/timeout、本地序列化/容量拒绝为 typed NOT_SENT。response/parse/provider 错误绝不伪装 NOT_SENT。String-only common API 不提供成功 HTTP status，parse error 的 status 保持未知 -1，不编造200。

全部显式生产 LLM 调用点仍是：

1. `AiGatewayServiceImpl.generate -> client.chat`：AiCreator 八场景、AiTopicPlan.merchantPlan、MerchantInsight、NpcChat；selectClient/原 quota/traceId 不改。
2. `PhotoCheckIntentService.executeOnce -> PhotoCheckVisionService.reviewOnce -> chatVision`：原 nonce/receipt/状态机不改，E 负责 NOT_SENT catch。
3. `ObjectCardServiceImpl -> ObjectNamer.name -> chatVision`：同 vision admission，原命名 null 降级和铸卡 taskBudget 不改；取消铸卡外层 Future 不使 common lease 提前释放。

`LlmClientConfig` 原四个生产构造点已全部注入 admission。搜索出的其他构造者是 tests：两组真实 loopback OpenAi HTTP Test 显式注入；PhotoCheckVisionServiceTest、ObjectCardMintHookTest、PlayKitPhotoCheckRuntimeTest 的缺 key 拒绝，OWN_C05_15/16/20 的 parser/readiness/truncate 调用保留原签名。旧签名无 admission 只能本地检查，网络拒绝，不能私自创建独立池绕边界。`LlmClient` 方法签名及严格 content String 解析不变。

## 验证证据与 root 命令

本 VM 只执行 Python 静态检查（复用 real_storage_it.strip_java_comments 的词法 helper 做括号/注解盘点，不是 Java parser）、parent 保护范围/方法体比对、diff 和秘密扫描；具体实际输出见 verification.json/static-checks.txt。没有执行 Maven、javac、jdeps、Java/HTTP fixture、Redis/MySQL、VPS/SSH、CI dispatch 或真实 provider。没有 Surefire XML，不能宣称 Java 通过。

待 root 在已批准 JDK21 CI 精确运行（不增加 job/并行/依赖），随后用已有 assert_it_ran 和原始 XML/source/run/attempt/hash 核对：

```sh
mvn -B -pl chengyinhub-admin -am test -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest=HttpUtilsJsonPostTest,AiHttpAdmissionTest,OpenAiCompatibleLlmClientVisionTest,OpenAiCompatibleLlmClientLogRedactionTest,HttpUtilsSecurityTest,OWN_C05_15Test,OWN_C05_16Test,OWN_C05_20Test
python3 ci/assert_it_ran.py chengyinhub-admin/target/surefire-reports HttpUtilsJsonPostTest AiHttpAdmissionTest OpenAiCompatibleLlmClientVisionTest OpenAiCompatibleLlmClientLogRedactionTest HttpUtilsSecurityTest OWN_C05_15Test OWN_C05_16Test OWN_C05_20Test
```

两个新类是普通 `*Test`，默认 Backend `mvn clean test` 会包含，无 `*IT` 默认 exclude 问题；未增加 skip/assumption/profile。静态声明数量和测试名称在 verification.json，只是预期，不是执行数。root 后续全套 Backend 和 E 真 MySQL/Redis 补偿/恢复验证仍必要，不改现有登记绕过它。

有意义正反例覆盖：legacy error 无 NOT_SENT、lease close/取消期间 worker 保持占用、QUEUED 取消禁 dispatch、PREPARING 取消等 finally、非法URL/header/过期 deadline 未发、正常 JSON/Bearer、500/302/401 challenge 不重放、gzip/chunked/已知长度超限与恰好1MiB、慢滴流总期限、common 队列满/排队超时仅移除本task、真实写入阻塞且 cleanup 满的许可寿命；origin case/default-port/模型别名两槽、全进程四槽、非法/未知 origin 与中断无泄漏、旧构造 fail-closed、序列化失败释放、生产共享 bean、四条真实 AI 悬挂时第五条未发且其他 common 通道完成；原 vision/body/auth 与日志脱敏断言保留。

## 限制、迁移与回退

无 schema/DDL/迁移/外部调用；无业务唯一身份释放或 nonce 生成。新的 typed NOT_SENT **仅本 attempt** 的证据，不能覆盖旧 UNKNOWN。E catch 未集成前拒绝可能被旧 generic catch 保守留 UNKNOWN，所以此候选不允许孤立启用，root 必须与 E 的原 receipt 持久补偿验证一起集成；不能按 HTTP code/普通 IllegalStateException 广义退款。玩家草稿有效稿定义/输入审核 UNKNOWN 可退政策由 root 保持，F 不修改。

HTTP deadline不证明序列化/解析可抢占，也不包含输入审核、图片 encode/base64、业务总耗时；一 MiB 是 response cap，不是图片/request cap。图片准备、语音/STT/ImageSeg/OSS/地图等旁路、业务关联指标/日志历史债、真实支付回调/状态读回 T19 仍未完成。局部 common HTTP 见证不等于整应用隔离或容量/SLO；compiled jdeps 仍无本任务实证。

回退优先关闭原 AI 配置/令发送入口 fail-closed，保留原 receipt/intent/UNKNOWN 和恢复 worker；不可切回无界 body、POST retry、trust-any 或新 nonce，不清库。若代码回退须连同 E 的 typed catch 按已验证合同回退，不能留下可误退的单边状态。超时任务真实退出前许可仍保留，重启不是外部未执行证据。此提交只冻结 F transport delta，root 后续按实际 Java/HTTP/真库输出决定是否集成/启用。
