# 固定组合用例 booter 绑定接缝（SOURCE / NOT_RUN）

父提交：`a2f20508f747b8ce69dc92c59c8d6642ff3d64d7`。只新增本目录；没有编辑 B 的 helper/test、POM、workflow、预算、业务或清理代码。官方 Surefire3.5.6 tag 对应提交为 **`25ea054860a5c1e5932b360d8aa0a31944c2b841`**（annotated tag object `4d2ac80420e49ecb7f5edf6c045c4a000d25974e`）。原源码与本文用到的公开文本哈希见清单。

结论：有一个**静态可证明的 matcher 不匹配**：官方生成器优先把 Class-Path 写为相对 booter JAR 父目录的 URI，而本候选在解析 URI 前先要求 raw 字符串包含绝对 test/classes 目录。必要修改是移除这两个 raw substring 判断，在原全部条目完成解码、相对解析及白名单后，检查两个目录的**精确规范化成员关系**。不是删目录绑定，不是允许任意路径。

本次真实失败谓词仍 **UNKNOWN**。没有取得原实际 `/proc` argv、booter manifest 或私有 Maven 诊断，不能把下述源码反例说成已重现原运行。也不能把缺少已接受的 SUREFIRE 记录说成没有 fork。

## 先交给 B/root 的精确文件

- `binding.review.patch`：单一有界 hunk，基于当前 `ci/combined_http_budget.py:1241-1253`，复用原解析、原路径白名单及原 `REAL_TEST_CLASSPATH_BINDING_REQUIRED` 错误码。仅供 B/root 实际应用。
- `contract.json`：官方 commit、原 caller 行、URI/argv/折行规则、逐项对照，以及10个直接控制 SOURCE 场景。
- `project-source-sha256.txt`、`official-sources.tsv`、`POSTIMAGES.sha256`：原源身份、官方依据与本包后像；不含私有 manifest/日志。

## 官方生成器与 house matcher 对照

| 规则 | Surefire3.5.6 primary源 | 当前 house 源与结论 |
|---|---|---|
| 固定 manifest-only 模式 | `AbstractSurefireMojo:2398-2444,2690-2693`；`SurefireMojo:250-265` 默认 systemClassLoader/manifestOnly 为true | 原非模块化固定模式继续要求 `-jar`，不为官方另有 `-cp`/module-path 模式而添加 fallback |
| booter 临时位置 | `AbstractSurefireMojo:2576-2587,3378-3382`，`tempDir`默认`surefire`在`:435-436` | Linux正常生成在模块 `target/surefire`；house`:1235-1237` 的 admin target位置、非symlink、大小和后缀检查保留，不扩大到任意`/tmp` |
| 文件名 | `JarManifestForkConfiguration:128` 调用 `TempFileManager.createTempFile("surefirebooter", ".jar")`；后者`:140-146` 把前缀、时间戳和fileId用`-`/`_`连接 | `surefirebooter-…jar`前缀符合真实生成规则，不需要放宽名称 |
| Java argv | `DefaultForkConfiguration:150-177` 使用真实配置的java，先加原argLine，再resolveClasspath；`JarManifestForkConfiguration:107-109` 加`-jar`和**绝对**JAR路径 | house`:1233-1237`寻找`-jar`后的原JAR路径符合此模式；Maven日志中的shell引号不是`/proc/cmdline`的NUL分隔参数，应保留当前实际PID读取来源 |
| 尾部参数 | `ForkStarter:537-543` 再追加tempDir、dumpPrefix、Surefire properties basename及可选system-properties basename | 不能要求`-jar`/JAR是最后两个argv；也不能把这些可能私有的参数公开。现matcher没有这种尾部限制，无需改 |
| Class-Path 内容 | `DefaultForkConfiguration:190-204` 合并boot、test、provider classpath；`JarManifestForkConfiguration:142-169`写入JAR manifest | 不能只保留两个测试目录或剔除原provider/booter依赖；现有逐项允许原cache/reactor target检查保留 |
| 相对URI优先 | `JarManifestForkConfiguration:177-194`：相对的是JAR的parent，不是shell cwd；无法relativize才在`:195-212`回退绝对file URI | house`:1244-1245`的raw绝对substring不是此协议要求；下面`:1246-1252`已有正确的相对解析阶段，应把精确目录判断放到后面 |
| 转义与目录 | 同源`:146-158,233-240`，UTF-8百分号编码；空格用`%20`，目录末尾`/`保留，条目间空格 | 当前先unfold，再split，再`unquote`/resolve可承接；`unquote_plus`会把字面`+`误当空格，不应替换。目录尾`/`可由Path规范化，无需raw字符串后缀比较 |
| authority/外部路径 | 相对化失败可写绝对file URI，但不是授权远程读取 | house`:1247,1251-1252`仍只容空scheme/file、空authority、原cache/reactor target规范化路径；相对`..`只有最终仍在这些真实根下才合法，不给remote/foreign放行 |
| Mockito启动agent | admin POM`:103-120` 的dependency-plugin properties与原`${org.mockito:mockito-core:jar}`；官方`:168-170`传入argLine | house`:1253-1257`原恰好一个/cache/repository Mockito agent、原hostcache映射/文件/哈希检查不变。不增加选项、可选null或其他agent位置 |

`JarManifestForkConfiguration`确实将`java.util.jar.Manifest.write`输出写入`META-INF/MANIFEST.MF`。另读取了固定OpenJDK21源参考tag `jdk-21.0.8-ga`：`Manifest.println72:238-262`按72字节内容写CRLF并加一个continuation SPACE，`Attributes:390-401`先合并continuation再解释值。因此当前`:1242-1243`先还原折行再拆Class-Path的顺序与此生成器匹配。本参考**不代表实际pinned镜像的JVM patch版本已核验或运行**。Oracle网页本次未取到；没有拿不可读页面冒充已核依据。

## 最小语义修正与直接反例

以下仅是静态构造的路径例子，**不是原运行manifest**：

```text
JAR: <ROOT>/chengyinhub-admin/target/surefire/surefirebooter-<stamp>_<id>.jar
Class-Path: ../test-classes/ ../classes/ <原boot/provider/cache条目>
```

它是官方同文件系统relativize允许的正常表达。raw绝对substring会拒绝；按JAR父目录解析后则恰好是原两个目录。相反，只有 `target/test-classes-shadow` / `target/classes-shadow` 的绝对条目即使包含这些字符串，也不应满足真实目录绑定。review.patch把两种情况收敛为同一个精确成员条件，而非放宽根：

```text
for each原manifest条目:
    原URI限制 → percent decode → 以原booter parent解析 → resolve
    原cache/reactor target containment必须通过 → 记入resolved集合
原两个required目录分别resolve，必须都在resolved集合中
继续原Mockito agent/哈希及全部进程证明
```

如果任一条目越界，依然先失败；不能因为两个必要目录存在就跳过其他条目。若没有Class-Path或必要目录缺失，集合条件仍拒绝。不删除原文件大小/只读/源码/JAR/cgroup/网络/exit/XML/业务断言/cleanup门禁。

直接控制 SOURCE 已在 `contract.json` 列齐：合法relative、合法absolute file、官方折行、合法百分号编码、缺目录、目录substring诱饵、remote/authority、foreign traversal、wrongbooter/agent、实际候选PID但binding拒绝。B拥有真实测试代码；本包不添加通用parser/runner，也没有运行这些控制。对于非官方格式或解析错误继续失败，不提供任意模式回退。

## 观察与执行结论边界

原caller `run:1345`调用`sample_java`；后者`:1274-1282`先读取真实PID/argv/cgroup并调用binding，成功后才在`:1284-1287`构造并append proof。因此中间任一`ValueError`都可能阻止记录已观察到的候选；短进程退出/文件删除竞态也不能由空accepted列表排除。原`FileNotFoundError`路径`:1288-1290`只是不提供身份通过证据，不是成功或未fork证明。

B正在负责固定phase/reason/cleanup，不在此重复实现。允许公开固定阶段、错误码和类型及原身份摘要；不输出argv、URI正文、manifest、内层异常文本或properties内容。未见实际私有证据时不得宣告命中某个guard、没有fork、OOM、业务执行、XML通过或清理完成；新的规范化接线必须仍接受后续同源真实执行检验。

本包只做公开源读取、Git差分/应用检查和摘要/秘密扫描；**Python import/parse、控制、Java/Maven、DB、Docker、HTTP业务及CI全部NOT_RUN**。不是原运行的复现报告，也不重新打开compiler闭包或provider预取任务。
