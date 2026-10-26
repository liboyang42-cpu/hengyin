# warm-cache 私有目录创建调用修补

Parent：`ec28c2bbcfb8e69c5f80439bc90f03b63801dcde`。仅叠加两个 owned 文件，不重交或修改原3份 shared review.patch。

原调用误用 Python `tempfile.mkdtemp` 的默认位置和随机后缀；该 API 不保证原 helper 要求的 `/tmp` 与八位 alnum。本次直接复用原 `generic-gameplay-http-e2e.sh` 的 GNU `mktemp -d /tmp/cy-http-budget.XXXXXXXX` 独占创建，再进入原 `private_directory` 校验。没有放宽其名称、父目录、canonical path、权限、owner 或 symlink 围栏，没有随机重试或 `tempfile.mktemp`。

新增直接 control driver 也用同一 GNU 命令准备其真实私有目录，并在模拟 transport 中断言 warm-cache 只调用该固定 argv 一次；fixture 只回收自己成功创建并通过原校验的目录。其余通用临时目录不用于原 private helper。保留原63个控制方法及断言，新补2个源码控制：allocator异常、allocator返回坏名/父路径，均须在 cache/inspect/container cleanup 之前拒绝；没有创建容器时不猜资源身份或清理他人资源。合计65个控制 SOURCE。

仅完成源码差分/Git检查与摘要。未运行 GNU allocator、控制、parse/import、Maven/Java、DB/Docker/HTTP或CI，全部 NOT_RUN；不将该新源码调用不匹配当作旧运行失败根因。
