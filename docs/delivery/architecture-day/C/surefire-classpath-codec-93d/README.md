# Surefire 3.5.6 固定 classpath codec

Parent: `93d5952264264327da639010e3d3fa0b403d9e7d`。

仅修改 CombinedSurefireRuntimeProbeTest.captureTestClasspath 与既有
CombinedContextConfigurationTest.providerClasspathCapturePreservesExactBytesAndRejectsUnownedEntries。
原 11 配置方法身份保留；另一个 stub 控制、原 9 方法、Generic 及 probe boot 正文不变。

固定上游依据：Apache Surefire tag surefire-3.5.6，Classpath.java
blob `3dffd989726d70a4048ed7a5ea09099b1e6cb9f9` 第 98–103 行，
writeToSystemProperty 对每个 element 执行 append(element).append(pathSeparatorChar)。
链接：https://github.com/apache/maven-surefire/blob/surefire-3.5.6/surefire-booter/src/main/java/org/apache/maven/surefire/booter/Classpath.java#L98

生产者严格要求终止分隔符，只 substring 去恰好一个，再 split(...,-1) 检查每个真实项；
缺终止、双终止、开头/中间空项、null/CR/LF/NUL 与全部原路径负控拒绝。
原始 UTF-8 bytes（包括终止符）原样写文件，hash/byteLength 不变义；
entryCount 只计真实项，保重复和顺序，四键 schema 不变。不 trim/rstrip、不补猜路径。
直接控制用固定 serializer 形状驱动 ONLINE/OFFLINE，保精确 bytes、hash、4 项（含重复）、
0600、拒绝覆盖、无额外文件及原归属/越界/symlink/缺文件断言。

B 消费接口：验证同一固定 codec 后将真实非空项传 javac，不能把 raw 末尾分隔符传入
形成隐式 cwd。本片不修改 Python、POM、CI 或任何预算/资源/身份门禁。
未读取原运行 raw classpath；上游源码合同可证不等于已独立核原值。
所有解析/import/编译/测试/Java/Maven/DB/容器/CI 均 NOT_RUN。

后像 SHA256：

* CombinedSurefireRuntimeProbeTest.java: `6bba32e2fc2d69123eb066f0ed12e9227c855db21ba347184985fafee4caf275`
* CombinedContextConfigurationTest.java: `63b7f26c45a57b7baa7aa1cd31035a54e36f99dd0623117a0bcecd2b2c5fd52e`
