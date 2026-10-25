# H：mixed 固定 GamePlay profile 接缝

本包仅 SOURCE。已收齐实际 caller、Python 装配和直接控制源码；parse、测试、编译、Java/DB、Docker、外呼及 CI 全部 NOT_RUN。由 root 唯一集成和统一验证。

## 候选身份与范围

- Parent：`6fcdf747e17cb5094121f609ae33f7734fda2ae7`
- Parent tree：`02e30bd0778ad2eff63b091429b89e7f17895f8f`
- 公共仓库：`liboyang42-cpu/hengyin`
- 独立源码目录：`/workspace/hengyin-h-mixed-profile-6fcdf/{base,work}`；原共享 checkout、未交文件和其它 owner 源码未切换或回退。
- 只改 `ci/capacity_mixed_probe.py`、`ci/gameplay_process_recovery_probe.py` 及它们两个直接 `test_*.py`。完整差分见 [owned.review.patch.base64](owned.review.patch.base64)，后像见 [POSTIMAGE.sha256](POSTIMAGE.sha256)。
- 未改 Java child、F CrossDomainTransportStage/OOM、C storage fixture、E restore stop、B host ports、shell/CI 入口、properties、SQL、网络和服务配置。

## 已有实证与根因

root 提供 mixed run40232922574/job121288299103：`gameplay.run → capacity.observe → Probe.__init__ → validate_profile` 在原 Docker Cmd 的 `--spring.config.location=file:/fixture/application.properties` 断言处失败。

实际 GamePlay 容器以固定 `PropertiesLauncher` 与仅该 child 的 test-classpath 启动；该 child 的 `main` 内部才以固定 app.run 参数加载原 properties。Docker Cmd 自然不含这个 Spring 参数。这是两个已授权 fixture 之间的入口接缝，不是应放宽资源/配置 guard 的理由。

原 report 的 BEFORE_TX、COMMITTED_NO_REPLY、ACKED 局部 ASSERTIONS_PASSED，以及 coreBusinessCurve 已生成事实由 root 提供；本次不重取 artifact，不将其迁签为本供体、全 mixed、T13 或 Q8 通过。原整体 FAIL_OR_UNKNOWN_CLEANUP_OR_SCOPE / FAIL_OR_UNKNOWN_NO_RETRY 不改写。

## 修改合同

### 现有 caller 显式给原物理 boot 绑定

GamePlay 原 `run` 在启动前将原 properties 字节 SHA 加到既有 report；在原健康回执检查后，以 `capacity_profile_binding(report, boot, mixed_before, networkId)` 传给原 `observe(..., gameplay_binding=...)`：

- 原完整 source、JAR、Java child source、主 class、仅该 child class tree、私有 config 和 fixture properties SHA；
- 原 ready bootId/Java PID/8080 及原 source/JAR/class/config 回执；
- 当前原 app CID、宿主 PID/StartedAt、网络 ID；
- 原 MySQL/Redis CID/PID/StartedAt/网络身份。

没有新 selector/registry/CLI 选项，不接收调用者指定路径或任意 Cmd。绑定元数据复制后冻结，不能靠调用方稍后改 boot/map 迁移观察身份。

### 容量侧严格核对固定两种入口

原六参数 standalone `observe` 和默认原 fixture 路径保留；没有 binding 时仍执行原 config-argv guard，实际 process stamp 另外核对原 shell 的精确 java -jar/trace 命令及原 store 地址，不能用其它 main 附加 config 参数伪装默认入口。

只有现有 GamePlay 两个 opt-in 均明确 true、binding 结构/完整 hash/原 boot/原 CID-store-network 一致时，才核对第二种固定 trace + PropertiesLauncher 命令。main、classpath、日志配置、argv、Env override、配置路径/挂载都不可随意替换。原内存/swap/CPU、无 privileged/发布端口、pool 和 admission 逻辑保留。

沿原 source/JAR 校验与原 `stamp`，独立复核：

- 唯一实际 Java PID 的 /proc cmdline 与 inspect 固定 java argv 逐字一致；
- 源 properties 和其挂载字节一致，并保留原 pool/Redis/jobs profile 值；
- GamePlay 原私有 config/ready 文件、权限、固定路径、SHA 与原 boot 一致；
- config JDBC/Redis 精确指向同一原容器 IP/schema/serverUuid，capacityItems 仍为原 72；
- fixed source、原编译输出、仅该 child 的只读挂载 class 及完整 manifest SHA 一致，无额外未绑定 class；
- 原 JAR/fixture/test-class/private bind mount 的源路径与读写属性一致。

新证据仅写 hash/元数据，不写 argv 原文、tokenSecret、私有配置正文或凭据。原真实 HTTP/SQL/actor/网络跟踪、类别、UNKNOWN 不重试、九曲线及负载、租约/财务拒绝、精确 cleanup 和 exit-first-failure/finalize 全保留。未添加任何应用启动/资源创建/额外 runner 或清理器。

## 真实调用和只读依赖

- `ci/authenticated-redis-recovery.sh`：原 java -jar/固定 properties 的 standalone observer，原六参数调用不变。
- `ci/gameplay_process_recovery_probe.py::run`：同一恢复后的 app/container/relay，唯一新增显式 keyword binding。
- `ci/capacity_mixed_probe.py::observe → Probe → validate_profile/stamp`：原预算/业务观察和原 cleanup/finalize 继续承担验收。
- `GameplayProcessRecoveryChild.java`：只读核实原 main、app.run、PropertiesLauncher/test-classpath、config、ready 及 8080 合同，无源码修改，不需要 root 串 Java hunk。
- 原 `authenticated_redis_recovery_probe.py` 的 target/env/SQL/secret/report 帮助函数只读复用，无改动。

本轮对实际 argv、方法签名、变量类型、导入、字节 hash 算法和 caller 字段进行了手工源码核对，没有运行解释器或解析器；这不是 Python 语法或运行通过证明。

## 原控制保留及新增源码

原两个文件的 27 个控制方法和所有原断言均保留，新增 4 个方法，源码现为 31 个方法（不把 subTest 数字当真实执行计数）：

1. 显式 GamePlay 正向、默认旧入口拒绝 GamePlay、缺绑定/未知结构/未 opt-in 拒绝；
2. 原预算、pool/端口、错误 argv/main/config、source/boot/JAR、CID/store/network、环境覆盖拒绝；
3. synthetic 协议字节控制：实际 profile 函数核对 host/mounted config/properties/class/source、proc、额外 class、改变 scope、可写 class mount；原 standalone 固定入口正向及其它 main 伪装负控。synthetic 字节/命令 doubles 不是实际 Java/存储证明；
4. GamePlay caller 构造器绑定值复制、旧 boot/制品不一致及停止/暂停/OOM 拒绝；原源控制另断言真实调用处传入该 binding。

任何控制均未执行。新 profile 的真实 Docker inspect/proc/mount 输出格式、额外只读核对的既有 900 秒预算开销、完整 mixed/cleanup/网络结果仍待 root 同源集中验证。原局部绿不继承；尚无本供体 PASS 结论。

无新依赖/权限/费用/业务策略/表/DDL，无迁移或生产数据回退影响；回退只撤此四文件增量。

Root transport only: the review patch is losslessly base64 encoded for 18 literal diff-context lines with trailing whitespace. Decoded SHA256 `46623c1ddb45dc116c0bb3415d62228e3fd2debfcea27c81bfb2eaf9e54b6b55`; all four Python source postimages remain byte-exact. Runtime verification NOT_RUN.
