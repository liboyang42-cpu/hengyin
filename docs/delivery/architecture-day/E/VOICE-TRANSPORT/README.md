# E DashScope Voice 复用既有受控传输

公共 parent `de7f432ec6f0c1c6225168ecf2e4808210de42e0`；独立工作树 `/workspace/hengyin-e-voice-transport`，分支 `codex/architecture-day-e-voice-transport`。执行器可用，未重启；仅改 DashScopeVoiceClient 和新增专属测试。共享 LlmClientConfig + AiHttpAdmissionTest **必须由 root 串行应用 wiring.review.patch 后统一验收**，未装配不得启用此片。没有新池/队列/依赖/表/部署。

真实缺口：原 DashScopeVoiceClient 的复刻、query_voice、合成通过 Hutool同步execute/body读取完整响应，绕过现有AiHttpAdmission/HttpUtils受控JSON通道。现在三个原public方法共用原post入口，改调用既有 admission.acquire → HttpUtils.sendJsonPost；body序列化在准入后。共用同一固定origin表和物理lease，4进程/2同源约束不因模型/语音路径新增实例绕过，响应沿既有1MiB上限、单次POST/no redirect replay与deadline，不实现第二套传输。

post之外六个原方法体逐字保留；JSON解析尾部及voice_id/voice、audio_url/audio.url、status=OK语义原文不动。VoiceCloneClient仍抛AiServiceException/原UNKNOWN reason；内部保留受控transport异常cause，不依据cause改额度/退款或业务stage。已dispatch失败/超大响应绝不改写成NOT_SENT，合法结果按原方式返回；同样不生成requestId/nonce、不自动再次enroll/synthesize。

工厂patch仅在已有aiHttpAdmission的启动endpoint表加入AiVoiceConfig.baseUrl，仍为一个bean，文本/视觉/语音同源共享2许可。旧AiHttpAdmissionTest仅为新增factory参数补一个voice mock，原8例/断言保留。默认本地手工new的voice没有注入admission时只可本地拒绝，不能外呼；Spring仍强制必需注入。

caller已补读本地缺失132个生产Java及347测试Java，工厂直调旧测试只有上述1处。生产主要执行者为ApiMerchantController复刻/轮询、NodeNpcService复刻/轮询、NpcChatService已存文本后的可选合成。caller源码、原文本receipt/requestId/额度/媒体审核不改。当前enroll异常后的业务VOICE_FAILED/人工重试策略未改，本片不能证明跨请求外部复刻至多一次；该意图/恢复缺口需root分配业务owner。

已核对未重复修：OpenAiCompatibleLlmClient已在准入后序列化，HttpVisionImageProbe已有executeAsync/128KiB/禁重定向。DashScopeSpeechToTextService仍同步整包响应、含X-DashScope-SSE头且尚未接本通道，是下一独立切口；本片不声称全部音视频已受控。deadline是现有软预算，不保证DNS/TLS/cleanup硬终止；许可随实际worker退出释放，不因调用方超时先释放。

新增DashScopeVoiceTransportBudgetTest六例源码：原wire payload/返回别名；缺/错admission零外呼；302/503不重放且非NOT_SENT；1MiB/超大结果及原voice显式查询恢复；语音2请求阻止同源文本第三请求；真实AnnotationConfigApplicationContext装配实际工厂/client（受控loopback provider）。预完成配置mock用registerSingleton，避免Spring再填充mock字段。没有真实模型/资金/receipt/MySQL验证。

实际执行：verify-static.py、补丁在临时原基线副本apply、diff/文件门禁/有限秘密模式扫描PASS。**Java/JUnit/MySQL/Redis/CI/DevTools/完整gitleaks均NOT_RUN**，六例仅源码，不能挪用F旧通道回执当此新caller已实测。回退时client与工厂/fixture接线一起处理，不覆盖其他owner；HttpUtils/AiHttpAdmission/OpenAI/额度/意图源码均未改。
