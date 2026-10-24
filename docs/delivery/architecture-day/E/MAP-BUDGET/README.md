# E / WP11 地图与几何预算候选

Parent：`cc91b0845e3abd4a2060d54a857c8c179bd22d4f`；独立目录 `/workspace/hengyin-e-map-budgets`、分支 `codex/architecture-day-e-map-budgets`。仅 TencentMapClient、TopicRouteServiceImpl、两个新定点测试、Spring 装配测试新增一例及本文件。不改 common HttpUtils、ApiMap SQL、AI、发布、玩法/事务/回执、CI/registry/schema。

先核对原几何缓存 8+7、Spring 8、Tencent 3 例，以及 IM 4+7 矩阵/COORDINATOR wave2；未重做已有修复，也不把旧回执盖给此候选。逐 blob 检查当前 2492 个生产 Java：Tencent 出口仅路线和 MapGeocode 两个消费者；geometry 生产入口仅 ApiTopicController 的两处详情，原主题/商家及 teaser/subset 闸均在调用前，未修改。ApiMapServiceImpl 仅一条 nearby SELECT，拼音在内存计算；其 SQL 内相关查找的扫描成本未测，不能称 O(1)，本片不改页大小/SQL/索引。

| 预算 | 本片硬上限及规则 |
|---|---|
| 几何留存 | 8 MiB 总计费，256 KiB 单项计费；key/value 各按 UTF-16 上界 `2*length` 加每项 256 字节；LRU/从写入起 TTL ≤300 秒，命中不续期；超过配置上限启动拒绝，不静默放大 |
| 项数 | `floor(maxBytes/256)`，默认至多 32768；单项超过总预算仍拒绝留存；这是计费模型，不是 JVM heap 实测 |
| 重算 | 每个生产路线 bean 最多 4 个 key；同 key 冷并发或第 5 个 key 立即返回 null，不登记等待者；命中不占重算槽，provider 不持缓存锁，异常/finally 释放原 key |
| 输入/输出 | 复用既有 300 节点上限；有限且合法经纬度、每个坐标字符串 ≤64 字符、原四种出行方式；最多4096个解压/拼接点；超限不缓存部分结果；key最多 `10+24*300` 字符，4个待算key的UTF-16 payload上界57680字节，另有集合元数据 |
| 地图外呼 | 所有实际 Tencent 客户端共享进程级4槽、无执行器/队列；槽持有至同步请求、有限重试、流关闭和 disconnect 实际退出；URL≤8192字符、key≤512字符、仅原腾讯 HTTPS origin，禁止重定向带出 key |
| 响应/时间 | 1 MiB 响应字节；已声明长度不完整不返回成功；连接≤3秒、读取≤5秒、10秒单调预算在I/O边界检查；既有可重试GET状态502/503/504及明确网络类型最多两次，仍共用同一槽和预算；路线10秒检查停止继续段调用，不交付部分几何 |

F common GET 无 worker lease：外围 semaphore 若在 Future 超时即释放，不能证明真实外呼有界。本片在唯一地图客户端使用标准库同步连接并保留关闭生命周期，未修改/绕写 common 实现或新建服务。总预算检查不是 DNS/TLS 的端到端时延 SLO；若底层迟迟不退出，原槽继续占用，不能靠超时换槽。原 reverse-geocode 控制器分钟限流、坐标系、六位小数及压缩算法保持；没有容量/RPS/耗时/heap 实测。

最小复现：原同key/不同key并发可重复外呼、Long.MAX_VALUE配置可放大缓存、任意节点/压缩点数组可放大单次工作。新增 TencentMapClientBudgetTest 5例、TopicRouteBudgetTest 9方法/10次预期执行、Spring新增1例：4槽/第5个拒绝、取消仍占槽及disconnect barrier、关闭恢复、字节精确边界/超大与不完整响应、有限重试、超大配置、同key/多key及关闭缓存、300/301节点、4096/4097点与拼接超限、原序列子集隔离、异常/预算截止后恢复。全部provider/connection受控不联网，不冒充真实供应商/HTTP授权链。原26场景及断言保留；本批相关六套预期42次执行，实际 Java/JUnit/DB/CI全部 NOT_RUN。

实际仅源码差异/括号与原用例保留检查、候选范围及有限秘密模式扫描；完整固定版 gitleaks 未安装，须由 root 复扫。无新增依赖、DDL、外部动作或部署。

待协调：原失败段直连返回且不缓存的合同/8个旧测试保持；本轮“UNKNOWN不表现为真路线”若要求整体返回null，需要root裁定现有String接口的降级表示，本片不自行换协议。超限/准入/路线预算拒绝已返回null。MapGeocodeServiceImpl 自有城市缓存和requestLocks不在本次独占文件：2000项的size/put竞争、单项/总字节与等待者预算仍未闭合，不把Tencent外呼4槽说成它们也已修。端到端撤权竞态/真实供应商、Java装配及性能仍待统一验收。
