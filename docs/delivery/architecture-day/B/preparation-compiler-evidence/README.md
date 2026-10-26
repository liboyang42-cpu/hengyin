# 准备失败有限compiler投影 SOURCE

父提交：`663e6282a09d0a70d0127dc09e50c3d762dce426`。仅两个Python及本文；不改Java/POM/workflow/registry/参数，不含内部runtime资料。旧失败没有原source error行可读，具体文件/行/类别仍UNKNOWN，不能把静态import修补当作原运行原因。

`warm_cache`原finally已核task-private cache.log退出及32MiB上限、只读尾1MiB。本包复用该尾部，在原精确owner仍活着、清理之前与原JUnit投影同一接缝调用 `compile_failure_evidence`。原首因、actual Maven exit、OOM、failures、cleanup和accepted表达式不改；没有新日志上传或资源，不构造compiled postimage，不改变原三JUnit诊断。

新cache JSON字段 `compileFailure` 固定purpose=PREPARATION_COMPILER_DIAGNOSTIC_ONLY、businessAccepted=false，state仅OBSERVED或UNKNOWN。入口先核原实际非零Maven退出与originalExit、source/run/attempt/class、当前精确phase、CID/name/nonce/image/预算、固定Cmd/entrypoint/user/source环境、core/settings版本事实、原clean POM/profile和完整21源hash。只容原Maven生命周期编译阶段：OFFLINE_PACKAGE_PROBE、ONLINE_PACKAGE、ONLINE_PROVIDER_RUNTIME、ONLINE_CONFIGURATION_UNITS、OFFLINE_PROVIDER_RUNTIME、OFFLINE_TEST_PREFLIGHT；依赖get、独立javac、未知phase、零或未观察退出均UNKNOWN。

日志只接受当前compiler3.15.0:testCompile@admin的标准goal头、COMPILATION ERROR段及其官方error计数，最长尾1MiB。每条必须为完整原Maven格式 `[ERROR] <absolute-source.java>:[line,column] <reason>`，且对应原21 TEST_SOURCES内当前canonical普通文件；外部/相对/../symlink、混入未审source或source漂移拒绝整段，不挑安全子集掩盖混入。多条合法当前源各自核hash。行列按当前UTF-8源、UTF-16字符及tab宽8核有效范围，支持正常EOF位置；无旧行号或旧class证明依赖。

只投影source相对路径、实际sourceSha256、line、column及五个固定类别：CANNOT_FIND_SYMBOL、INCOMPATIBLE_TYPES、DUPLICATE_DEFINITION、UNDECLARED_CHECKED_EXCEPTION、SYNTAX_ERROR。仅严格已审reason模式用于选枚举，不输出reason、symbol、源码正文、私有路径/ID、SQL/URL、argv/env或raw log。必须有1至8条且与官方段内计数完全一致；重复位置类别、膨胀/超限、未知类别、坏头、截断或缺原头/计数均整段UNKNOWN。Maven随后重述的summary不是新证据；解析不到不猜。OBSERVED仅表示匹配到这些有限日志事实，不能视为验收或根因已复现。

官方格式依据：apache/maven-compiler-plugin tag `maven-compiler-plugin-3.15.0`，`src/main/java/org/apache/maven/plugin/compiler/AbstractCompilerMojo.java` blob `1aebf4c89e38f63372eef6995d70428546d99b17`，失败分支输出COMPILATION ERROR、各CompilerMessage.toString及error计数；本投影只支持上述窄格式，不支持的格式仍UNKNOWN。

原125控制身份和断言保留。transport helper仅新增默认None的私有compiler_log输入。新增3个直接控制SOURCE：标准错误形状/五类别/隐私及源码行hash平移、身份/阶段/退出/路径/格式/重复/超限拒绝、真实finally调用先于原cleanup并保首因/退出/JUnit未知。合成source/log/owner仅证明协议，不冒充Maven/编译/业务证据；不生成假class。现128方法声明不是执行成绩。

parse/import/py_compile/tests/compile、Java/Maven/DB/Docker/HTTP/CI全部 **NOT_RUN**。仅源码读取/编辑、Git差分及运输摘要；cache JSON沿原白名单上传，无共享entry或workflow改动，最终由root集成后统一安排，不为诊断另开运行。
