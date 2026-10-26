# 同工具链离线 package 闭包优先

源码父提交：`6713fb6de418f21b45f918524b3eb7aa4d9758fd`。仅静态 SOURCE 合同，不包含内部 run、回执或日志。

## 原消费者约束

`gameplay-http-e2e.yml` 的 controlled-combination 入口调用 `combined_http_budget.py warm-cache`，随后原 generic shell 的 prepare/run/finalize 使用其证明。`source_manifest` 和 `CrossDomainCombinedLoadMySqlIT.identity` 要求完整 reactor JAR、原 admin JAR 内的 source git.properties、实际 class-tree 及选定应用类与 JAR 字节相符。因此不能把 package 换成仅编译，不能跳过测试编译，也不能拿其他 job 的 JAR/绿色记录代替本次同工具链构建。现有 backend-jar/restore build 未提供本 budget owner 所需的同镜像/core/settings/仓库与 classes 证明，本包不按名称就复用其产物。

## 最小可完成路径

在原固定容器、原 source/POM/settings/repository、原 1 CPU/1 GiB/no-swap/256 PID 和同一 1200 秒总预算内，先执行同一 package argv 的 offline 尝试。成功即不再执行在线 package；它仍会按原 Maven 生命周期编译/测试编译/打包这个 source，不是直接接受旧 JAR。只有非零退出且出现原 Central 的标准“offline mode / artifact has not been downloaded from it before”诊断，才执行原在线 package 修复闭包。其他编译/打包错误直接保留非零，不无条件重试。没有新命令/profile/镜像/依赖选择器，所有 argv 由固定 SOURCE 生成。

原 provider-get 和最终 offline test preflight 都保留。离线尝试/在线修复共享同一容器与原 deadline，没有新增时间或资源预算。记录原子 offlinePackageProbeExit；最终成功 receipt 不能替代缺失/非法的前置 receipt。原 mavenExitObserved/mavenExit、源码与完整 manifest 检查、成功后终态拒签、精确所有权/清理、实际测试 read-only/offline/internal-network 门槛继续有效。既有缓存若闭包完整可避免在线 package；闭包不完整或编译过慢时仍拒绝，不能保证在未执行前已解决所有超时。

## 必要进度证据

准备 Maven 取消 quiet，启用原 core 支持的 no-transfer-progress 和固定 color=never。原始 INFO 留在原私有日志；公开只投影有界尾部的最后一个已审插件/版本/goal/本仓 module header，未知仍为空。不公开 execution ID、路径、URL、下载坐标、SQL、正文或错误消息，也不将最后观察到的 header 声称为超时根因。首次失败、Maven receipt 与容器清理仍分别记录。

原运行仅能证明未在 deadline 内观察到终态，不能据此判定是网络下载、编译、插件失败或 OOM。本包提供缓存完整时可跳过在线 package 的实际固定路径和后续可定位证据，不声称已证明原动态卡点或性能已通过。

## 调用者与配套 SOURCE

只修改 B-owned 两个 Python 文件及本文。workflow、generic shell 和诊断消费者调用接口无需修改；root 不需应用额外共享 hunk。原 74 条控制/负控保留，新增 4 条，合计 78 条声明：固定离线/在线分支结构、两条闭包路径的同工具链 receipt、缺失/坏前置 receipt 拒签，以及有界白名单进度。静态/transport doubles 不代替真实 Maven 或业务结果。

解析、import、控制/测试、Java/Maven、Docker/DB/HTTP/CI 全部 **NOT_RUN**。由 root 最后统一验；不把缓存准备或源码计数当 HTTP240/混合故障通过。
