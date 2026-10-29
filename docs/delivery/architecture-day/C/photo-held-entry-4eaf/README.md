# 原四路 photo held 入口的固定源码接缝

Parent: `4eaf93f9a77bbcfca4318e4c1bbac8da87d677be`。

仅修改三个既有测试 Java；CrossDomainStorageFixture 和所有生产源码不变。

原调用链：组合 case 创建左右两个 storage.photo 服务（两个原 origin，每 origin 两个
物理请求）；四个原 command 经 preparePhoto 的原可写事务提交，再由原四 Future 调用
PhotoCheckIntentService.executeOnce。原服务完成短事务 owned/claim、reviewPrepared、
真实 quota 与输入 snapshot 后，OpenAiCompatibleLlmClient.chatVision 经原 AiHttpAdmission
及 HttpUtils.sendJsonPost 到 CrossDomainTransportStage 的两个本地 HTTP server。
每个 handler 读取 fault，再在原 aiEntered/releaseAi 屏障内阻塞。原 16 MiB 请求及所有
原预算、四并发、时限、状态/UNKNOWN/经济事实/HTTP240/清理断言均保留。

源码可证问题：CrossDomainTransportStage.fault 原为普通 boolean，测试线程在
beginFault/finishFault 写，独立 HTTP handler 读取；这条跨线程读取没有由该字段本身或
一个共同屏障建立可靠发布关系。改为 volatile，符合原意图的当前 fault 模式发布。
同域 CombinedControlledProviders.mode 已使用 volatile。这里不声称本次实际缺失的
handler 就一定由旧值读取导致；具体哪一个原执行/传输分支未进入仍 UNKNOWN。
Java 内存模型依据：JLS 21 §17.4.5（volatile 写 happens-before 后续读），
https://docs.oracle.com/javase/specs/jls/se21/html/jls-17.html#jls-17.4.5 。

原等待点仍只 await(5,SECONDS)，成功仍 assertTrue；失败仅调用固定 fixture 方法
aiEntryFailure，读取 isDone 的 Future，不多等、不取消、不重试、不提交任何新命令。
首个 ExecutionException 原 cause 以对象身份成为断言 cause，其余真实失败及原 provider
失败作为 suppressed 保留。已完成的原 photo 只附 enum state/固定 providerState，
不输出原身份、请求、图像、SQL、URL、配置或任意异常文本到新公开 JSON；没有新增
诊断 schema。缺失 handler 的断言仍失败，完成 Future 不冒充真实 handler 已进入。

既有 photoPrepareFailureProjectionKeepsOnlyReviewedCodesAndCause 中追加有限控制：
fault 为 volatile、原 Future cause/handler failure 身份保留、未完成 Future 保持未完成、
空结果仍只产生失败断言。原 11 方法身份/旧方法正文内容和其它断言保留，不新增 @Test。
这里只是声明/有限 Future 控制源码，不是四 HTTP handler 或真实数据库验收。

本片仅源码差分/空白/摘要及限定敏感词扫描。解析、import、测试、编译、Java/Maven、
DB/Redis、Docker、HTTP、CI 全部 NOT_RUN；不声称实际运行首因已复现或业务通过。
