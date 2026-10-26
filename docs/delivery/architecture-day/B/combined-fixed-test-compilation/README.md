# 固定组合 workload 的测试编译闭包

父提交：`ead7abc3ad98ceb66829e2a9bd65c5a0c04e0962`。仅 SOURCE 合同，不含内部运行回执或日志。

本包仅缩小 admin 的测试编译输入；不修改 workload、原方法/断言、HTTP 请求或混合故障，不把 Surefire 的 `-Dtest` 当编译过滤器。生产 reactor 编译/打包、源 git.properties、JAR 与应用类字节对应、全部实际 class-tree 哈希仍保留；其它模块 POM 和编译范围不变。原 manifest 只要求各模块有真实 class 并记录其实际树，不要求 admin 全测试源码；新 `testCompilation` 明示本次范围，不能据此声称全量测试已编译或执行。

admin POM 的唯一固定 profile 为 `combined-http-fixture-closure`，只配置 compiler 3.15.0 的 `default-testCompile`：逐文件 testIncludes，不使用 CLI includes、通配或动态参数；implicit=none，缺失显式依赖不能靠旧/隐式 class 糊过。原 release/parameters/encoding 继承根 POM；不增加依赖、版本、运行权限或 production profile。

package（含离线尝试/在线闭包修复）、最终 offline preflight、实际 offline test 统一激活此 profile。显式 provider-get 沿原固定非递归 root 目标，不伪装执行 admin profile。首次 package 前清理只覆盖原 admin target/test-classes、target/generated-test-sources 与 maven-status/maven-compiler-plugin/testCompile，先核所有路径再删除；不清生产 classes/JAR、依赖仓库、资源源码、公开证据或其它模块。源/输出根及符号链接边界必须匹配原路径。

准备前核验 profile 存在且 includes 精确等于 C 的 18 份本仓逐文件源码，记录每份源 SHA256；准备后核验源未变、C 指定 37 个稳定 binary name 均存在，并记录其 SHA256。另读真实 compiler 的 default-testCompile/inputFiles.lst，必须正好对应这 18 份源；新 generated Java 或另一个源编译单元的输出不得静默接受，需停查来源。缓存再采用和运行前后 source_manifest 都绑定这些同一证明；不能借先前编译输出，缺 profile、缺 source/class 或漂移一律拒绝签收。37 个是缺失拒绝集合，不删除或只哈希这 37 个；同 18 源生成的其他匿名/局部内部 class 保留，原全树哈希继续覆盖全部实际 class。原资源复制生命周期保持完整，未按 testIncludes 缩 SQL/XML/配置资源。

实际源码依赖闭包及反射/子进程依据来自 C 的公开 `7f54d31cd5e9d7962c4449ae827364901eca3acf`，目录 `docs/delivery/architecture-day/C/combined-test-compile-closure/`。本包逐字消费其 test-includes.list 和 required-test-classes.list 生成固定 POM/常量，不猜填其它测试作为依赖，也不从方法名宣称实际编译/启动已通过。C 的示例 profile id 统一为本包已定名称。18 源正文、资源、原 14 个运行 postimage 和所有业务方法均不改。最终包包含 POM；先前缺 includes 的中间快照不可运行，也不得叠加重复应用。

配套 Python SOURCE 保留原 78 条控制，新增 5 条：固定 profile/输入拒绝，三处精确测试输出清理及链接拒绝，所需外层/反射内部 class 缺失拒绝，三阶段同 profile/缓存编译漂移拒签，以及 37 个逐一缺失、额外匿名输出保留、清单外输入/输出/生成源拒绝；现共 83 条声明。合成 class 字节和 transport doubles 均仅为协议控制，不是 javac、依赖、Maven 或业务恢复证据。

所有解析、import、控制/测试、Java/Maven、Docker/DB/HTTP/CI **NOT_RUN**。原 1 CPU/1 GiB/no-swap/256 PID、1200 秒、read-only/offline/internal-network、receipt 与清理合同不变；不声称动态卡点或性能已经验证解决。root 同源集成后统一验收。
