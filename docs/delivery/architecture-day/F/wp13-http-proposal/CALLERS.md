# 实际调用者与出口（source addeb，静态只读）

所有路径和字节指纹见 [evidence.json](evidence.json)。行号只对该 source 有效。查找仅限 Java 生产源码，不读取应用配置值、凭据、环境或真实 URL。没有运行请求。

## OpenAiCompatibleLlmClient 全部显式调用点

`LlmClientConfig:25/31/41/61` 构造 qwen/deepseek/vision/minimax 四个 OpenAi 兼容实例；qwen/deepseek 共用 AiModelConfig，minimax 关闭时是本地抛错 placeholder。还有 MockLlmClient；它不应被计算为真实 provider 发出成功，不能用它验收资源隔离。

| 直接调用点 | 上游与原身份 | 当前失败行为/预算缺口 |
| --- | --- | --- |
| `AiGatewayServiceImpl.generate:206 -> client.chat -> OpenAi.post` | ApiAiController -> AiCreator（themeDraft122/nodeGenerate198/templateFill227/chapterOverview260/safetyPrecheck319/merchantTaskSuggest361/clubDesign389/merchantInsight414）；merchantPlan -> AiTopicPlan.generate112；MerchantInsightServiceImpl139 -> AiCreator；ApiAiNpcController186 -> NpcChat.generateAndFinish335 | 普通 quota 原 reservation MODEL_STARTED 后异常 UNKNOWN；玩家 themeDraft 外层 finally 有独立释放风险；无 provider admission/硬 total/response cap |
| `PhotoCheckVisionService.reviewOnce:151 -> visionClient.chatVision -> OpenAi.post` | AdvancedGameRuntimeServiceImpl775 -> PhotoCheckIntentService.executeOnce（NEVER，原 nonce）；reviewPrepared/review -> reviewOnce；先 image encode，再原额度、snapshot、MODEL_STARTED | 发送前本地准备异常退原额度并 NOT_SENT；chatVision generic 异常 UNKNOWN，不退。新 admission 若无 typed 分支会被误归 UNKNOWN；不得改意图领取/终态合同 |
| `ObjectNamer.name:97 -> visionClient.chatVision -> OpenAi.post` | ObjectCardServiceImpl141..142 并行命名分支；与 Photo 共 vision bean，同源必须共准入；本链无 quota 池 | 错误只报类名并返回 null；铸卡外层 await 有共同 deadline/cancel，不能证明底层停止；与 Photo 是两次不同调用，不合并 |

`LlmClient.streamChat` 默认先 chat 再逐字回调；生产源码未发现 `.streamChat(` 调用。没有据此声称实际 SSE、流式 HTTP 或回调预算已治理。显式调用查询不覆盖反射、运行时 bean 替换和未来插件。

## 相邻 AI/vision/语音通道（不自动纳入第一补丁）

| 出口 | 实际 caller/现有保护 | UNKNOWN/后续 owner 工作 |
| --- | --- | --- |
| `HttpVisionImageProbe.fetchHead:41` GET | VisionImageGuard.inspect 的 probe；Range 头及 readUpTo 128 KiB、3000ms、禁 redirect、executeAsync | 无 absolute total/provider admission；literal 内网 host 过滤不能冒充完整 DNS/SSRF 证明 |
| `VisionImageEncoder.download:196` GET | PhotoCheckVisionService 与 ObjectNamer 各自 encode；10000ms、禁 redirect、executeAsync、ContentLength 和 readUpTo(32 MiB+1)、图片像素/格式检查 | 已有真正 stream cap，不能称 body 无限；无 absolute total/admission。encode/base64 分配在 LLM admission 前，不能从 LLM 1 MiB response cap 推出总体 heap 有界；URL/cause 日志另审 |
| `DashScopeVoiceClient.post:147` POST | ApiMerchantController1000/1015/1050 及 NodeNpcService229/242/271 enroll/isReady；NpcChat395 synthesize，随后 storage.stageGeneratedAudio396 | timeout + execute/body，缺 total/cap/admission。POST 无自动 retry 契约待接；raw 响应、业务 message、异常日志风险；语音注册/合成副作用不借 HTTP 切口自动重试 |
| `DashScopeSpeechToTextService.transcribe:83` POST | ApiAiNpcController334；带认证、JSON、禁 SSE 的业务协议 | timeout + execute/body，缺 total/cap/admission；cause 可能穿透。独立 owner 接线，不按“GET/查询无副作用”猜计费 |
| `ImageSegClient.get:227` 签名 GET | ObjectCardServiceImpl180 -> segment；本 source 是自有签名 Hutool HTTP，未发现这里调用 vendor SDK；4000ms、非 2xx/异常 null | execute/body 缺 total/cap/admission；签名 URL 不可日志；parse/missing-output 会打印 body 片段，截断仍不等于脱敏。GET 也可能触发收费，不套 explicit-idempotent GET retry |
| `OssCardCutoutStorage.download:249` GET | ObjectCardService cutout transfer/segmentInput；NpcChat 生成音频 stageGeneratedAudio369 | 已 executeAsync、按用途 maxBytes 的长度/流 cap、禁 redirect、host/状态/异常类日志；无 total/provider admission。不得迁到 String sendGet 丢 bytes cap/编码/来源安全约束；OSS 客户端/上传链资源仍需另审 |

这些通道的 socket timeout 不是端到端总 deadline；已有字节 cap 的通道也不代表有并发预算。首个 OpenAi 补丁不能给语音、图片、OSS、地图或支付链路代签完成。

## 已有 common 其他通道与传播风险

静态 `HttpUtils` caller 有 TencentMapClient49（显式 GET retry）、WeChatMiniSessionServiceImpl84、ApiLoginController255/259、AppUploadService411、WxSubscribeMsgServiceImpl179。它们共享 common executor，必须保持原路由/header/认证/重试策略；AI 全局准入需在共享池前完成。Webhook 有自己的 transport，不能假设其由本切口保护。支付 callback/状态读回的 servlet/DB/Redis/CPU 并发依赖还需 T19 的真实应用演练，local HTTP 并发测试只能验证 common 池内局部见证。

## 可复查查询（仓库根执行，都是只读）

```sh
rg -n 'new OpenAiCompatibleLlmClient|\.chat\(|\.chatVision\(|\.streamChat\(' chengyinhub-system/src/main/java chengyinhub-admin/src/main/java
rg -n 'gateway.generate|aiGatewayService.generate|objectNamer.name|photoCheckIntentService.executeOnce' chengyinhub-system/src/main/java
rg -n 'HttpRequest\.|executeAsync|\.execute\(|\.body\(|timeout\(' chengyinhub-system/src/main/java/com/chengyinhub/business/service/ai
rg -n 'HttpUtils\.(sendGet|sendPost|sendSSLPost)' chengyinhub-system/src/main/java chengyinhub-admin/src/main/java
rg -n 'releasePlayerThemeDraftQuota|accepted|inputBlocked|MODEL_STARTED|AiProviderOutcomeUnknownException' chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/AiCreatorServiceImpl.java chengyinhub-system/src/main/java/com/chengyinhub/business/service/ai/impl/AiGatewayServiceImpl.java
```

上述文本查询用于列证据，不是新 scanner，也不能证明不存在隐式/反射调用。真实运行 provider 地址、延迟、额度/费率和供应商计费事实均 UNKNOWN；没有读取部署配置去填补它们。
