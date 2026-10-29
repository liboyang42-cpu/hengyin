# F→root/E：确定的 transport/adapter 接线合同

代码已写，Java 编译与测试 **NOT_RUN**；供 root 在获准 JDK21 CI 编译验证，不声称已编译。root/E 经济 catch 未共同验证前 **不可启用/发布**。没有新 schema/nonce/charge marker/callback 平台。

## E 所需公开类型

```java
package com.chengyinhub.business.service.ai;
public final class AiProviderNotSentException extends IllegalStateException {
    public AiProviderNotSentException();
}
```

继承 IllegalStateException 以保留当前本地 readiness 检查的异常基类；不是 AiServiceException，没有 Reason/getReason。仅无参构造，固定消息 `AI request was not sent`，构造时 cause 固定 null，不带 URL/auth/body/原始异常。E 按类型 catch，在 `RuntimeException` generic UNKNOWN 分支 **之前**。

`LlmClient.chat(String,String)`、`chatVision(String,String,String)` 及返回 String 均未变。`requireReady()` 仍是无 HTTP 的本地检查，配置无效抛该专用类型；不占许可，不创建额度。

可信 NOT_SENT 仅指 **本次 attempt** 未进入 `client.execute`：本地 disabled/空或含控制字符 key/空 model/非正 timeout/非法 endpoint、缺 admission/未知 origin、准入不足、序列化失败、executor 拒绝、CAS 赢得 dispatch 前取消/过期。它不证明任何旧请求/nonce 未发，不授权对旧 UNKNOWN 重试。

一旦 DISPATCH_ENTERED，即使 DNS/connect 失败、HTTP 4xx/5xx/302、body 超限、响应坏 JSON、timeout/interrupt，adapter 均抛 **非此类型**的无 cause 固定消息 RuntimeException，交既有 generic UNKNOWN 分支。不得根据 `status=0`、`retryable` 或普通 IllegalStateException 推断退款。

E 在 gateway/Photo 中使用 **原** member/pool/day/nonce/receipt 做既有持久补偿与分类；持久化失败保持原不确定状态，不产生新 request/nonce，不调用模型重发。Photo 原意图记录 NOT_SENT；generic UNKNOWN 不动。F 未修改这些文件，不替 E 规定收费规则。root 的 AiCreator typed-providerUnknown 窄修复独立：普通输入 UNKNOWN/有效稿政策不变，本合同不要求给玩家池新增阶段接口。

## common 接口

```java
public static String HttpUtils.sendJsonPost(
    String url, String json, String bearer,
    long deadlineNanos, HttpUtils.AttemptLease lease);

public static final class HttpUtils.AttemptLease implements AutoCloseable {
    public AttemptLease(Runnable release);
    public void close();
}

public enum HttpRequestException.SendDisposition {
    NOT_SENT, MAY_HAVE_BEEN_SENT
}
public HttpRequestException.SendDisposition HttpRequestException.getSendDisposition();
```

`deadlineNanos` 是同一进程 `System.nanoTime()` 的绝对截止点。adapter 在准入/序列化前计算 `now + min(providerTimeout,10000ms)`，不能排队后重新获得一份总预算。common 再取当前 common total 的较小上限（最多10000ms），connect/read 分别最多3000/5000ms且不超过剩余时间；不改全局属性或老调用者策略。

lease 的 release 只能是轻量 Semaphore release，不做业务/DB/Redis 操作。common 一次性 transfer 后，caller close 为 no-op；worker 已启动时仅其真实 HTTP/cleanup finally 完成后释放。未启动且 CAS 已禁止任何未来 dispatch 的取消方可释放。Future.done/cancel、租约过期均不是释放运行中许可的证据。取消排队只移除自己的 FutureTask。

新入口用安全 TLS client，单 POST，无 retry/redirect，并禁该请求的自动 authentication challenge 重放；响应声明长度预拒绝和 **解码后**累计 1 MiB 上限复用原 readLimited。无 raw cause，旧 HttpRequestException 构造器默认 MAY_HAVE_BEEN_SENT；只有 common 内部新路径生成 NOT_SENT。旧 GET/form/legacySSL 的入口和重试策略不变。

## startup admission / 构造器

```java
public final class AiHttpAdmission {
    public AiHttpAdmission(java.util.Collection<String> endpoints);
    public HttpUtils.AttemptLease acquire(String endpoint);
}
public OpenAiCompatibleLlmClient(String name, String baseUrl, String apiKey,
    String model, boolean enabled, int timeoutMs, AiHttpAdmission admission);
```

`LlmClientConfig` 新 bean 一次装配现有 text/vision/minimax endpoint 的有限 immutable origin 表；qwen/deepseek/vision/minimax 构造注入同一个实例。规范 origin=小写 scheme/host、去 host 末尾点、显式默认 port；path/model/bean name 不增加槽。全进程静态 global=4；同注册 origin=2；tryAcquire，无额外队列。未知/非法 origin 不动态注册、无 DNS 查询、无 credential key。

旧两个构造签名保留用于既有 parser/readiness 测试，但未注入 admission 时 **网络 fail-closed**。本地 requireReady 可成功不等于有准入；两个真正 HTTP 协议测试显式注入固定 fixture admission，其他调用者搜索清单见报告。生产新实例必须经上述 bean，不可为每次请求创建新 admission 表。DNS alias/不同供应商 host 是否同一商业 provider 仍由配置 owner 审定，不能凭 bean 名声称独立计费资源。
