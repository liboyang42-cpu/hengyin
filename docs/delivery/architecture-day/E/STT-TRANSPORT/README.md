# E STT 受控单次传输

parent `10426a532822ee8053cd75e3d77da5b6393d30c2`，分支 `codex/architecture-day-e-stt-budget`，工作树 `/workspace/hengyin-e-stt-budget`。执行器正常。实际只改 DashScopeSpeechToTextService，新增专属测试；shared common/config/旧fixture只交 **common-and-wiring.review.patch**。root须先应用10426的voice wiring，再应用此patch，才有新增common重载及ASR固定origin；不是未装配即可启用的片。

真实闭环：ApiAiNpcController.voiceChat先原requestId回读，再检查MultipartFile.getSize≤2*1024*1024，才getBytes；NpcVoiceAudioFormat按字节识别受控mp3/m4a/aac/wav，之后调用STT。叶子只做Base64 data URL、一次同步生成POST、output.text解析，无远程文件下载/落盘/轮询。ASR完成/异常后controller仍优先回读原rid，再进入原NPC文本请求；这些caller和额度/意图源码不改。

修复：镜像入口既有2MiB上限，在Base64/JSON分配前拒绝直接绕过入口的超限byte[]；正常最大值不变、没有新时长/收费规则。Base64最大2796204字符；原始byte[]在进入叶子前已分配，不冒充流式上传硬内存总预算。body编码/UTF8发送在原共享admission内，复用4全局/2同源、软deadline、1MiB响应实际读取上限，旧Hutool同步整包body路径移除。无新池/队列/依赖。

精准common patch新增6参数sendJsonPost(..., AttemptLease, Map<String,String>)；旧5参API及旧private重载保留。只准空map或精确X-DashScope-SSE=disable，不准凭据/Host/framing覆盖；复制成常量不可变map，在排队前验证。旧Future/CAS/取消/cleanup、AttemptLease/AttemptState、重试/重定向策略不改。ASR原SSE头及所有body字段/成功解析尾部保留；未dispatch拒绝由既有专用证据给出，已dispatch失败不改为NOT_SENT。Voice/STT沿原AiServiceException接口包装并保留transport cause，不依据cause改补偿规则。paid POST一次，无自动重试。

响应不依赖Content-Length分配：已有common检查声明超限并限制实际流读取，支持无长度/chunked及解压后上限；不跟随重定向，中途断流不作成功/null。合法HTTP framing之后的额外字节不能靠Content-Length证明不存在，本片不声称探测任意TCP残留。deadline不保证DNS/TLS/cleanup硬终止，超时仍由原物理worker持许可到退出。

新增DashScopeSpeechToTextBudgetTest七例源码覆盖：2MiB边界/有效PCM WAV和原请求头/载荷；无长度+gzip/超大实际流；伪报长短长度+中断；302/503不重放；同源满额在编码前拒绝；受限header不覆写鉴权/帧且回收许可；真实工厂/Spring接线（完成mock用registerSingleton）。旧9个@Test+1个4行CSV参数测试的assert行/参数行保留，仅4处原socket fixture注入真实准入。没有真实模型/DB/计费/完整HTTP JWT验收。

实际执行：verify-static.py、临时原副本顺序apply两片patch、diff/文件门禁/有限秘密模式扫描PASS。**Java/JUnit/MySQL/Redis/CI/DevTools/完整gitleaks均NOT_RUN**。当前读取的公共集成251dbdd8中上述STT/common/caller未变化，独立片以10426为依赖，不覆盖251其它owner。

明确余项：同rid并发/后续HTTP重试可能在NPC意图写入前重复ASR，现controller顺序可复现；叶子没有自动重发，但本片不声称跨请求外部至多一次，需root分配业务owner处理原意图。其它图像/上传资源预算留后续，未清任何数据/Redis，无schema/迁移。
