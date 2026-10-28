# 组合生命周期 SOURCE 收口；整组内存验收仍 OPEN

Parent `e75fb84e6701bb963cbe4deafb77484078b3a761`；原 `33d4d8aa` 共享 Redis、
`c04ab229` core 准备/完整事实、`10f4ef6d` UTF-8 全部保留。不重新试参或重做准备。
本包收齐下列可证冗余，**不声称这些修改足以使整组在 1GiB 通过**，不应将候选当作另一次
OOM 探索器。被杀 PID、精确分配、必要进程同时 live/committed 工作集仍无充分证据。

| 实际 owner/生命周期 | 本包动作/边界 |
| --- | --- |
| 四路 16MiB 物理请求 | 原 static final 源 String 改为本轮实例源。在原四 Future 已完成、真实 aiEntered=0、aiServerExited=4、releaseAi=1、原物理 admission 仍 held 的见证之后，仅清 encoder 源引用。四个实际 transport byte[]/lease/handler 都不动。无显式 GC/RSS 下降承诺。 |
| 后续显式 busy 输入/恢复 | fault 模式下若再有原显式新命令取图，生成原完全相同 16MiB 值但不再次长期缓存；不是调用 provider 或重试 UNKNOWN。原 UNKNOWN 重放仍由 service 读原结果。非 fault 仍原 SMALL_IMAGE。 |
| 独立 SQL fixture | 保持原独立 schema/DataSource/事务/全部 13 XML，仅以 XML 真正用到的 10 个短 alias 替代再次扫描整个 domain 包。FQ 类型、内建 alias、所有 statement/真实 mapper 与 SQL 均保留；完整父 Spring 工厂不改。 |
| 双 scheduler child | 只提供一份 root-owned review.patch：原 SpringUtils 公共 BeanFactoryPostProcessor 入口替代静态字段测试辅助；两原 private 协调器字段用 JDK Field 写同值。真实 DefaultListableBeanFactory/alias、Druid/Wall、Coordinator/SQL/并发/READY/hash/kill/receipt/cleanup 全保留。没有参数修改或新进程。 |
| 原完整 Spring/Redis | 保留完整 context 和既有真实共享 Redis factory；不换轻量 owner，不再重写上轮资源归属。 |
| Maven/Surefire | 原完整 reactor、实际 fork、只读/offline/classpath/source/JAR/agent 及预算均不动。不能直接换成 surefire:test：Maven 3.9.11 ReactorReader:133–163 依赖当前 project artifact file 或 compile/test-compile phase。静态已存在 target 不能替代这些合同。本包无猜测的共享 launcher hunk。 |
| Core/facts/输出资源 | 原 72 身份/240HTTP/完整快照/SQL回读/有界记录、六 target 磁盘 bind 都必要且保留；没有删行/删列/滚掉原证据，也没有所谓整 JAR tmpfs 副本可删。 |

唯一组合 caller 在原 stillHeld 见证中、原 assertHeldAfterAiCallerTimeouts 后调用
retireImageSource(pending)，不移动任何 HTTP/故障屏障或释放时点。源 String 原内容表达式逐字
保留。显式新取图 fallback 保持原独立 CrossDomainFaultIsolationMySqlIT busy 原输入合同；
该用例正文未改。finishFault/真实终止后亦解除实例源引用。关闭没有自动重发/新 nonce。

配置仍 11 方法，业务仍原 1 方法；所有旧语句与断言保留。在既有方法内追加：全部 13 XML
解析与 10 alias/零 SQL 连接；child 三字段/公共入口签名；真实长度的源 String 引用退役前置
拒绝、未完成 Future 拒绝、退役不动 fault/release/exit 状态、幂等与自身 executor 清理。
这些是有限 SOURCE 控制，不是伪造物理 handler/SQL/容量通过。无新类型、依赖、schema。

共享 hunk 仅位于 scheduler-reflection.review.patch，原他-owner Java 未改；root 如采纳
应整体应用该 hunk，配套签名控制已在本包。Coordinator 的原字节 hash/协议算法不变。
SpringUtils.postProcessBeanFactory 的仓库原实现仅赋原 static beanFactory；原新建具体
Coordinator 的 running:boolean/activatedAt:long 与 JDK Field 写法一一对应。

源码静态界限：没有 safe 源依据删除父 Spring、Druid/Wall、Maven 或任一并发 child，也没有
足以证明上述 native/heap 必要工作集上界的数据。不能用采样峰值相加、Xmx 等于 RSS、缓存
引用消失等于立刻归还 cgroup 来宣告整组已修；剩余整体预算闭合仍 OPEN/UNKNOWN。
所有解析/import/测试/编译/Java/Maven/DB/Redis/Docker/HTTP/CI 均 NOT_RUN。只读 primary
源、Git 差分/apply-check/摘要及限定敏感词扫描；不公开运行元数据。
