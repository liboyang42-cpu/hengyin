# 四路 photo 的可证拷贝接缝（SOURCE）

Parent: `4295e76d5abdde7ad0b4d6a01abbd41e00cb7a51`。
保留前轮 `1bda219e` 固定 classpath codec、`fdb3fd40` fault 发布/原 Future cause；不重交。

**必须由 root 串行审/apply `shared-production.review.patch` 三生产 hunk 后，再验本片配置控制。**
本提交不修改任何生产文件。控制刻意要求新 byte 入口；不能单拿测试代码当独立可通过候选。
若生产 owner 不采纳该接缝，本片不能被签为已修复，也不能用 fixture 绕过真实客户端。

源码可证链（均为 parent 行号）：PhotoCheckIntentService:98 只是 reviewPrepared 调用点，
不能定位具体 OOM 分配。PhotoCheckVisionService:137/195–207 的 snapshot 哈希对整个 encoded
String 分配 UTF-8 byte[]；OpenAiCompatibleLlmClient:171–181 又创建完整 JSON String；
HttpUtils:174 再编码成完整 byte[]，最后在原物理 worker 的 ByteArrayEntity 中持有。
CrossDomainStorageFixture.photo 已共享原大图 String，transport 的流式 decoder 不物化 image；
在 C 测试写权内消除上述生产拷贝会绕真实路径，故只给生产 owner 精确 review hunk。

三 hunk 是一个完整接线：

* PhotoCheckVisionService 的原私有 hash 改为 4096 char 分块、标准 DigestOutputStream +
  OutputStreamWriter UTF-8；close 后取摘要。跨块代理对/尾部不合法代理仍沿原替换语义。
* OpenAiCompatibleLlmClient.post 用已 pin Fastjson 2.0.57 的 JSON.toJSONBytes，直接产生
  私有请求 bytes；原 model/messages、deadline 起点、admission、失败分类/无重试不变。
* HttpUtils 新增命名入口 sendJsonPostBytes（避免与 String overload 的 null caller 歧义），
  与所有旧 String caller 共用原门禁/lease/worker/abort 路径。旧 String 延迟编码仍在原
  deadline/鉴权检查后。新 caller 将新建 bytes 的独占所有权交 transport，之后绝不修改/
  复用；即使调用者超时，物理 worker 可继续持有。没有防御性整包 clone，没有新依赖/type。
  新 bytes 唯一 caller 是 OpenAiCompatibleLlmClient；voice/ASR 等现有 String caller 不改。

这会去掉整图 hash 临时 bytes 及 JSON String→UTF-8 的中间链，不宣称消除了所有分配：
Fastjson UTF-8 writer 仍有增长 buffer 与 getBytes 的最终 Arrays.copyOf，原 encoded String、
四个物理实体及 Spring/其它工作集仍在。没有本次 heap dump/具体分配帧，OOM 精确位置与
修后是否满足现预算均 UNKNOWN；不从 Error 推断容器 OOM kill，不公开运行材料。

CombinedContextConfigurationTest 仅在既有 photoPrepareFailureProjectionKeepsOnlyReviewedCodesAndCause
追加有限控制：原 OutOfMemoryError cause 对象不吞/不重试；有限 UTF-8/中文/NUL/合法与非法
surrogate、跨 4096 边界 hash 与原 getBytes 摘要一致；真实 LLM caller 必须送 bytes，拦截处
明确抛本地 NOT_SENT，不制造 provider 成功；新 byte 入口的 URL/header/null/deadline 拒绝、
原 lease 一次释放及输入不变。原九方法所有旧语句和原十一方法身份保留，不新增 @Test。
原组合 case/四路 16 MiB/同时 held/5、6、60 秒/pool3/HTTP240/金额权限 UNKNOWN/精确清理
全部不改；所有 JVM/容器/时间预算不改。真实 I/O 回归仍复用原 HttpUtilsJsonPostTest、
OpenAiCompatibleLlmClientVisionTest 和组合 case，未用这些有限控制冒充业务通过。

已只做 Git 差分/空白/apply-check、限定敏感词及摘要。解析/import/测试/编译/Java/Maven/
DB/Redis/Docker/HTTP/CI 均 NOT_RUN。primary 文件仅只读下载，无执行。
