# 固定 Surefire classpath codec（SOURCE）

- 父提交：`93d5952264264327da639010e3d3fa0b403d9e7d`。
- 官方 Surefire 3.5.6 `Classpath.writeToSystemProperty` 为每个元素追加分隔符，包括最后一个；[原源](https://github.com/apache/maven-surefire/blob/surefire-3.5.6/surefire-booter/src/main/java/org/apache/maven/surefire/booter/Classpath.java#L98)，blob `3dffd989726d70a4048ed7a5ea09099b1e6cb9f9`。
- `.classpath` 原 UTF-8 字节保留唯一终止 `:`。四键证明不变：SHA/长度针对完整原字节，entryCount 只数真实元素。
- `provider_classpath_proof` 同次读取返回原证明、原字节、执行值；只精确去掉一个终止符。缺终止、双终止、前置/内部空项、NUL、换行、坏 UTF-8、错误计数均拒绝。原路径、缓存/六模块边界、主类目录、owner/mode/hash 校验保留。
- `provider_runtime_proof` 保留 ONLINE/OFFLINE 原字节证明比较；`compile_controlled_content` 只把已核执行值作为 javac 的固定位置参数，避免把尾 `:` 作为隐式 cwd。无 rstrip、去重、重排、缓存扫描或第二套证明。
- 原 128 个 Python 方法身份不变，仅适配共享 fixture 与既有三个相关方法的 subtests/断言；补单终止、重复顺序、空项/缺终止/坏元数据和真实 javac argv 协议控制。控制输入为合成协议，不是编译或业务成功证据。
- compileFailure、actual exit、预算、接受条件、离线/只读、原业务及精确清理不变。Java producer 由 C 负责，本片不覆盖 Java/POM/共享入口。
- 解析/import/测试/编译/Java/Maven/DB/Docker/HTTP/CI：全部 **NOT_RUN**。本片仅源码交付；运行收益和完整业务验收未验证。
