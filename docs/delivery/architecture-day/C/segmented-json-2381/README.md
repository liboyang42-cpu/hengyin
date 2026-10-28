# 原组合发送表示修补 SOURCE

Parent `2381a837bc2bf26a0096444507c199835ea951cd`，tree `e24b74553b4f05545dd8ee5097ffccaa5c662dc6`；包含最近全部源，root 49/f4 CI 修正保持只读。
没有新诊断、参数或取证运行。SOURCE 完整，运行/解析/import/编译/测试/Java/Maven/DB/Redis/Docker/CI 全部 NOT_RUN；不声称 1GiB 已通过或精确 OOM 分配已定位。

生产只交 `segmented-json.review.patch`，root 审后整体应用 HttpUtils 与 OpenAiCompatibleLlmClient 两个 hunk；本分支没有直接修改共享生产/CI。唯一 owned Java 是既有 CombinedContextConfigurationTest，在原方法追加直接控制，配置11/业务1及全部旧断言不删。

## 同一个完整请求，不物化四份完整编码数组

原 chatVision 的 image String/值不变。大且无需转义的 ASCII image 使用三段：原 UTF8 JSON 前段、原 immutable image、原 UTF8 后段。前后段仍由 pinned Fastjson 原 codec 生成；只对 private 新建 JSONObject 的 image 字段暂置空与一个本地 `!`，证明两个结果只差该位置插入一个字节，再在 finally 恢复原值。`!` 不发、不持久、不作业务身份。不是按文本搜索用户内容或重新手写 JSON serializer。

限于原默认 codec/标准 Map/List writer；小图、Unicode/转义图、非默认 feature、不同 writer、较大 metadata/原 codec 范围外均走原 encodeBody/字节入口，不产生新输入拒绝/截断。8192 来自 pinned writer 原初始 buffer；这是可选表示优化的适用条件，不是新产品/费用门槛。

每次请求均新建小前后段和 JsonPartsInputStream，position/closed/输入引用均为实例字段。原 fixture 同一 immutable 源 String 不复制成四份全量 JSON 数组；没有跨账号缓存、共享可变 entity/cursor、压缩/裁剪图像、disk spool 或请求合并。真实四路16MiB原内容、完整JSON字节/长度/顺序/鉴权仍逐一发送。

## 原 physical worker 与 credit 所有权

`chatVision → post → visionParts → HttpUtils.sendJsonPostParts → 原 JSON guards → executeAttemptEntity → 原 physical HTTP_EXECUTOR/FutureTask/AttemptState → executeBlockingEntity → 同原 HTTP_CLIENT.execute`。

- 三段内存源没有外部 producer/file/socket 回调。原 lease 先转移到传输方；仅一个内部 delegate lease 把源关闭绑定到同一个原完成 callback，没有再次取得 origin/global credit。
- QUEUED 取消/拒绝：原 state 阻止任何 worker 开始，关闭自身源并归还原 credit。PREPARING/DISPATCH_ENTERED worker 已获所有权：caller/Future timeout 不关闭输入、不归还 credit；原 finally 完成后才 release。InputStreamEntity 在实际 body write 结束/失败时关闭自身输入，这不等于 credit 提前完成。
- source/任务构造或 executor 抛 Runtime/Error 时，未开始者走原 state cancellation；已经赢得 worker 者仍由原 finally 结束。Error 原样抛出，不吞 OOM/造结果；cleanup 失败保为 suppressed，不取代首错。
- 原 URL/bearer/header/deadline/response limit、NOT_SENT/UNKNOWN 分类、lease phase、async abort/精确队列移除不改。String/byte 与 legacy GET/SSL 公共入口保留，用 ByteArrayEntity 适配同一内部路径；ASR 的原 SSE header 规则不改。
- 同 pinned InputStreamEntity，长度为 prefix.length+原ASCII字符数+suffix.length，不使用 chunked framing。不可重放，原 automatic retry/redirect 与认证 replay 禁用继续保留，没有第二次派发或流重新打开。

## 原 held 时序保持

handler 仍在读取 body 前 `aiEntered.countDown → releaseAi.await(60s)`，维持写入背压；releaseAi 后仍走原流式完整 JSON 消费/校验再关闭。没有移动屏障、提前消费、另存四份整图数组或把服务端等待响应冒充客户端 physical credit。
fixture 的 `retireImageSource` 原代码/断言保持。它只清 fixture 自身缓存；四实体各自仍强引用所需原 immutable 输入，caller 完成不切断物理消费。原 close/finishFault、UNKNOWN、origin/global admission、2/5/6/60/150秒、完整Spring/双DruidWall child、72身份/240HTTP与精确清理完全保留。

## 现成直接控制 SOURCE

原 photoPrepareFailureProjectionKeepsOnlyReviewedCodesAndCause 追加：完整 JSON 原 codec 字节对照；四个游标先同时构造再分别消费，关闭一个不影响其它；原生 entity 非 repeatable/固定长度/消费后关闭；真实 client 仅到新 transport 入口并以明确 NOT_SENT 停止，不 mock provider success；QUEUED/PREPARING/DISPATCH 三态 caller close/credit/输入持有；复用 lease 拒绝且不关别人的源；本地非法参数零派发/一次释放。原全部编码/隐私/来源/拒绝控制仍在。
这些有限内存数据控制不是四个真实 handler 已通过；原组合负责真实并发/故障验收，没有替代/缩小原负载。

## 已 pin 主源

common pom 声明 HttpClient4.5.14，原 buildHttpClient 已 disableAutomaticRetries/disableRedirectHandling；per-request auth disabled 继续原样。原生 InputStreamEntity(HttpCore4.4.16) writeTo 用固定小 buffer、按声明长度读写并 finally close 输入；RequestContent 用已知 length 生成 Content-Length。RequestEntityProxy 仅委托/记录 consumed，不把内容复制到全量缓存；HttpClientBuilder 在禁重试时不加入 RetryExec。
primary-sources.tsv 给出原 Maven Central source JAR 内精确文件/摘要及已读 Fastjson 主源。生产预/后像与唯一 owned Java 后像齐备，root 应连同 review hunk 一次串接；不运行部分补丁试探。
