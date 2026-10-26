# 标准插件解析消息的 group 字符边界

Parent：`acda5c2080c968e136f4da9802bd0b661c1a62a6`。只修改 C owned parser 与直接控制源码，不重做 bootstrap、allocator、Maven 准备或共享 owner。

`safe_plugin_resolution` 已有有限白名单包含 `io.github.git-commit-id:git-commit-id-maven-plugin:5.0.0`，原 group regex 却不含连字符。本次仅将 `[a-z0-9_.]` 改为 `[a-z0-9_.-]`；首字母、group100字符/artifact80字符/version40字符上限、完整标准消息后缀、精确 group/artifact/version 白名单和私有cause不输出均保持。

直接 caller 已逐一读取：owned `warm_cache` 的有限私有日志投影，以及 shared `ci/test_http_e2e_entry.py.safe_maven_failure` 的现成动态 fallback，均直接调用同一 parser，不需共享补丁。共享旧 goal parser 的现有 group 白名单没有连字符项，本片不扩大其独立 goal 合同；标准 resolution-before-goal 消息沿现成 fallback 取得新修复。

原65个控制方法及断言不改，新增2个控制 SOURCE：白名单连字符正向与近似group/非白名单version/长度/后缀负控；真实shared consumer接线、私有cause不输出及无坐标仍unknown。合计67个 SOURCE，不代表执行通过，不能据此倒推任何旧运行缺失插件坐标。

仅Git diff --check、源码文字搜索、SHA256和限本片秘密模式扫描；未执行测试、parse/import、Java/编译、DB/Docker/HTTP或CI。全部新增验证 NOT_RUN。两个owned完整后像与本文摘要见 `postimages.sha256`。
