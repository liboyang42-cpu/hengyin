# H：F 663c transport 独立源码审查

结论：在精确候选的真实调用链中，**暂未发现可证的新增P1/P2**。CAS未发证明、worker持有许可、解码后正文预算与单次POST在源码上相符。**Java编译/JUnit/真实HTTP fixture均未运行，E的经济catch未联合验收，不可启用或据此签运行通过。** H补交1例未应用的真实transport＋admission组合回归，不把状态机模型、源码测试数量或未执行patch当JUnit实证。

先前quota/W19结论已独立冻结于 `60fc464b01fcb4cca25a5f0b0fd932444bc113df`、`c89f5944985a538cb0af9c5854a841de8c81eb54`，本轮不覆盖。裸POST真实积分P1、共同资金fence、B支付P1及quota外层两P2不因本轮结论关闭。

## 身份、范围与实际执行

- 公共仓库仅 `liboyang42-cpu/hengyin`；基线 `2ec68899099a1e250df0949a8383bf85b5d3ffe0`。
- F candidate `663c2c634e4cff53c876630fe4e166d3fcd29d20`，parent `05f4aa69e61752dcafb0bec73ca9ae9b2ff7a7f5`，tree `73a343a3385cd0df3652dd4f7bb983f6f9bf2a7b`，15变更文件。
- H起始HEAD `12543aefa28d36402d9ab27ef58e53086c7a0c62`，独占 `/workspace/hengyin-h-review` / `codex/architecture-day-h-review`，开始干净。只新增本H目录四文件，无F/E生产修改、新Agent、CI派发、真实外部调用。
- 取得未截断公共tree，建立**当前全部2723个生产Java/XML逐blob一致**的独立视图后搜索caller；14个变更文本文件逐blob核对，verification.json以连接器解析JSON读取，未将重序列化字节冒充原blob。身份清单 `source-evidence.json`。
- H实际仅执行Python源码/指纹/方法正文核对、搜索、`git apply --check`与秘密文件门禁。Java/JUnit/HTTP/TLS/MySQL/Redis、完整Spring启动、jdeps均NOT_RUN；未访问供应商。`static-evidence.json`记录准确范围。

## 调用者、接线和准入边界

真实生产仅 `LlmClientConfig` 创建OpenAI客户端，qwen/deepseek/vision/minimax四处全注入同一startup admission bean。text/vision/minimax配置建立有限不可变origin表；不按请求/model/path动态注册。实际网络caller为Gateway `client.chat`、Photo `chatVision`、ObjectNamer `chatVision`，统一进adapter post→common新JSON入口。LlmClient公开chat/chatVision签名未改；旧两个构造签名仍可本地readiness/parser，但无admission的网络发送fail-closed，不偷偷新建可绕过的表。

4个global许可为static Semaphore；当前生产单registry内，origin按小写scheme/host、末尾点/default port规范化，共2许可，path/model/bean名不增槽；unknown origin拒绝。tryAcquire无新增等待队列。**common原128队列仍存在**：获准AI至多4个可在common既有队列等待；“0extraQueue”不是声称系统总队列为0。没有新线程池/依赖/POM改动。

容量结论依赖生产保持同一registry。公共构造器新建其他registry仍共global4，但各表的同origin Semaphore不是static共享；源码搜索当前无可达的每请求建表或多个生产registry，不据假设扩权列新P2，也不宣传任意多registry下仍是全进程同origin2。DNS alias、IPv6文本别名与不同配置域名的商业provider归并也不由本helper猜测，需要配置owner审定。

## 真实CAS：未发证明与释放时点

`HttpUtils.sendJsonPost:138`先CAS转移AttemptLease所有权，然后校验URL/Bearer/json/deadline。尚未移交AttemptState时的确定本地失败在finally释放；移交后caller close不再释放。future为实际入队的同一FutureTask，不是另造模型任务。

| 真实竞态 | 源码胜者与结果 | 许可 |
| --- | --- | --- |
| executor拒绝 / QUEUED取消 | `cancelBeforeDispatch`将QUEUED CAS到CANCELLED_QUEUED，任何后续startWorker失败；NOT_SENT | CAS赢家可立即complete，一次释放 |
| worker已start，仍PREPARING时取消 | CAS到CANCELLED_PREPARING；后续enterDispatch失败，NOT_SENT | 不由取消方释放，等该worker finally |
| PREPARING取消与enterDispatch竞争 | 只有一方从PREPARING CAS成功 | 取消赢则禁发；dispatch赢则只能UNKNOWN |
| DISPATCH_ENTERED后timeout/interrupt | cancel不改写发送事实；finish保留FINISHED_DISPATCHED | Future cancel/done不释放，worker finally才complete |
| worker正常/错误退出与caller取消竞争 | FINISHED状态保留是否曾进入dispatch | AtomicBoolean released使重复finish/close不双放 |

精确位置：`HttpUtils.java:289–336`创建/启动/等待FutureTask；`:340–356`取消与分类；`:373–393`构造request、设置activeRequest、再次检查取消，**enterDispatch CAS紧贴实际client.execute之前**；`:729–800`为lease/state。未发分类不靠HTTP status=0、错误文字、普通IllegalStateException、租约过期或Future完成推断。进入execute之前的保守边界允许“CAS赢但尚未实际发字节”被归UNKNOWN，避免相反方向误退款。

取消只 `HTTP_EXECUTOR.remove(attempt)` 移除本JSON任务，无purge、无清他人队列。PREPARING时即便FutureTask已经cancel/done，实际Callable仍可能在运行，lambda自己的finally才调用finishWorker；没有在Future.done挂release。executeBlocking的finally仍同步releaseConnection/abort，再离开真实worker。异步abort排队拒绝只计数告警，不CallerRuns、不complete lease；仍堵在实体写/读/清理的worker占着原许可。因此不能用调用方已返回当物理资源已归还证明。

这些是对实际源码路径的检查，**不是宣称JDK/Apache/OS竞态已经运行证明**。尤其被接受的异步abort、同步finally/资源关闭之间的真实交错及平台socket阻塞行为，仍须获准CI的HTTP fixture；不以手工调用AttemptState的测试替代。

## deadline、正文预算与单次认证POST

adapter post在准入/JSON序列化前取同一单调绝对deadline，provider timeout上限10秒；common再与当前total的较小值相交，connect/read分别不超过3/5秒及剩余时间，不排队后重获预算。序列化前的body构造、图片encode/base64、输入审核及返回JSON解析不全在这一预算内，不能宣称整个业务请求有10秒硬上界。

`executeBlocking:403–417`先按声明长度拒超限，再从Apache自动解码后的entity InputStream调用原readLimited。`:568–593`每读块累计字节，超过1MiB拒绝后才允许写入ByteArrayOutputStream；gzip/chunked没有靠压缩Content-Length绕上限的分支。不是对整个响应先readAllBytes再substring。精确1MiB可接受；没有新增request/base64体积上限，不能把响应上限说成整请求内存上限。

新JSON固定单POST，使用secure HTTP_CLIENT，`buildHttpClient`原disableAutomaticRetries/disableRedirectHandling保留；每请求在Bearer分支额外 `setAuthenticationEnabled(false)`，防401/407 challenge重放。只有一次client.execute，无GET重试循环。4xx/5xx/302、DNS/connect错误、空/坏JSON、超大body、发送后timeout均非typed NOT_SENT。重定向不跟随，POST不因可重试status重放。

本地 `jsonTarget/origin` 拒绝非http(s)、userInfo、query、fragment、非法host/port；Bearer拒控制字符/空值。不通过trusted-any client、不引入URL fallback、DNS注册或重定向绕过。配置origin不是新的通用SSRF防火墙：原先也由受控供应商配置决定目的地；源码未见任何旧URL安全检查被删去，不能外推已证明完整SSRF/DNS rebinding策略。

## 旧调用兼容、脱敏和E边界

H逐方法正文比较，原 `execute`（显式GET重试总策略）、createRequest、readLimited、buildHttpClient、abortAsync、disconnectAsync、status/network retry判断及backoff共9个方法与parent完全一致。旧GET/form/SSL入口走null lease的重载，保留retryable和原TLS选择；公共submit改显式FutureTask/execute，旧异常内部未知cause类别诊断缩为固定文本，不改变成功/拒绝策略。旧HttpRequestException构造器默认MAY_HAVE_BEEN_SENT，不能成为退款凭据。

新 `AiProviderNotSentException` 为final IllegalStateException，唯一无参构造，固定消息、cause固定null；无URL/Bearer/body。adapter对本地准入/序列化和common明确NOT_SENT按类型抛出；其他transport/解析异常重建固定phase/status RuntimeException，不挂raw cause/suppressed。日志只有限白名单name、phase/status/code。common新路径的异常消息也是固定原因/status，未输出JSON/Bearer；raw requestId不进入adapter对外日志。旧响应体脱敏测试仅补新admission接线，原断言保留并加suppressed/type断言，源码存在不等于已执行。

**当前F source尚无E新增catch**：在Gateway/Photo真正chat入口的准入拒绝，会被当前generic RuntimeException保守记UNKNOWN；不能据此声称已做到NOT_SENT原额度补偿。readiness本地检查原catch已有发送前补偿，但不能代替chat内部准入/队列取消的catch。E必须在generic UNKNOWN之前按该专用类型处理，只处置本attempt的原member/pool/day/nonce/receipt，持久化失败不得放行/换号重发；不能退款旧UNKNOWN或把普通IllegalStateException/status0概括为未发。AiCreator有效/无效稿及输入generic UNKNOWN政策不由F改写。联合前继续不可启用。

## 测试源码盘点与H独立组合回归

F精确声明：HttpUtilsJsonPostTest15、AiHttpAdmissionTest7、既有Vision2、Redaction2，共26；另旧Security19和兼容3合计建议48。**全部是声明，不是本轮JUnit运行数**。common前5例手工操作AttemptState，只能验证局部状态类；后续真实loopback协议/丢响应/大小/队列/慢滴/16MiB阻塞cleanup测试才触达实际transport，同样还需执行。

H附 `admission-physical-worker.review.patch`，只在AiHttpAdmissionTest新增1例（7→8；若全部采纳建议48→49），其路径真实经过OpenAI adapter→实际common worker→loopback HTTP：

1. 仅占满既有cleanup池的本测试任务，两个同origin AI POST向不读请求体的受控loopback发送大body。
2. 等两条请求确实到server，等调用方timeout返回，要求是UNKNOWN且无cause/suppressed；cleanup仍饱和。
3. 同origin第三次必须NOT_SENT；临时占另origin两槽后global第五次也必须拒绝，证明**调用方已done时原两个物理worker仍占实际admission槽**。
4. 放开本测试body/cleanup门，等待真实HTTP worker退出；同origin两槽才应恢复。只释放自有门，不shutdown/prune公共池，不调用真实供应商。

这补足F的“common lease计数器+cleanup饱和”和“真实admission4个悬挂但未超时”两组之间的组合覆盖，**没有声称当前实现会红**。H仅通过patch应用检查，未编译/执行；大body阻塞fixture依赖受控JDK/OS socket行为，若其前提未成立须如实记录，不删关键容量断言去造绿。root可在获准Java环境安排，不由H派发。

## 裁决矩阵与后续门槛

| 项 | 同一候选源码结论 | 实际运行 |
| --- | --- | --- |
| 真实CAS未发/dispatch后UNKNOWN | 未见可达误分；保守边界相符 | 未验证 |
| Future cancel/done与release-once | 释放归属真实worker，queued例外有CAS禁发证明 | 未验证 |
| cleanup饱和/own queue remove | 不提前complete/不清他人任务 | 未验证 |
| global4/单生产registry origin2/无新增队列池 | 源码相符；多registry/配置别名边界明确 | 未验证 |
| 1MiB解码后cap/POST不重放/TLS | 源码相符；旧关键方法保留 | 未验证 |
| Bearer/body/raw cause脱敏 | 未见新增外泄路径 | 未验证 |
| Spring装配/E quota/Photo原回执接线 | F只提供类型和transport，联合尚未交验 | **阻断启用** |

root下一步应获取精确最终联合source的编译、定点原始JUnit XML（0fail/error/skip），真实loopback负例含401/redirect/response-lost/gzip/chunk/slowdrip/队列/cleanup/interrupt，以及E真实原回执补偿/UNKNOWN不退款回归；再跑旧GET/form/SSL和完整Spring/所需门禁。Java失败或环境前提不足必须保留原记录，不能用静态模型或其他source绿项抵消。

本H仅报告、源码证据与1例未应用回归patch，无schema/权限/依赖/DDL变化。回退不得删除原意图/receipt/UNKNOWN，也不能单边留下E可误判的catch；若需停用应在原配置/入口fail-closed，不能切回无界body/自动POST重试或新nonce。此结论不构成正式集成、上线或部署许可。
