# 4da9 HTTP pure-entry 固定分类来源修复

结论：真实入口失败来自固定 literal 的源码 owner 随拆包迁移后未同步，不是 HTTP 业务运行失败。parent 为 public `4da950c59809c4367b2b136b519dbda5e5952ece`；独立分支 `codex/architecture-day-f-4da9-http-entry-repair`。仅改 `ci/test_http_e2e_entry.py` 与本 F 目录，不修改生产、workflow、迁移或其他 owner 文件。

逐项核对了原 41 个固定 literal：40 项仍在原声明 owner；唯一迁移项是 `GAME_RUN_PAUSED_OR_MANUAL`，原文“本局暂停或由人工账本接管，当前只能读取”实际由 `service.impl.GameSharedRunControl.requireOnlineRun` 第 123 行抛出，Runtime 第 186–187 行构造该 helper。把这一项精确移到 helper 的完整路径，没有合并整仓源码、glob、fallback 或新分类。逐项路径和命中行保存在 `literal-bindings.json`，观察源均为本 parent。

原 41 个 message/category 对与 parent 完全相等，仅这一项 owner 改变。全部 exact 分类、JSON Unicode 形式、动态追加/前缀/空格 UNKNOWN、canary/redaction、XML/清理/caller/router 负控保留。源码 literal 缺失仍失败，改为布尔存在性断言并只输出固定 owner/category，不再让 unittest 把整个 Java 文件倾倒到日志。

实际 Python 输出见 `python-controls.txt`：一次执行 workflow 原三模块命令，41 项中 40 PASS、1 ERROR、0 skip；原失败的固定分类项及 caller/router 均 PASS。唯一 ERROR 为本工作区起初没有本地 master，既有 A8 byte-identical-to-master 用例无法读取该 ref。按 workflow 原有前置行为从已存在 public `origin/master=892efe6f765a5748065569fd886ee9b00ef71d4b` 新建此前不存在的本地 master（未移动或切换任何已有工作分支），仅重跑该一个原用例：1/1 PASS，0 skip，0.004 s。没有重复整套回归，也没有修改 SQL 或断言。首次运行为 5.971 s；不能写成一次 41/41 全绿。

另已静态比对 parent/current 声明：41 对完全保留、仅一项 owner 变化、41 个带引号原文均存在于精确 owner。`git diff --check` 与本片秘密扫描通过。Java/MySQL/Redis/真实 HTTP 36 声明项：NOT_RUN；未派 CI。本补丁仅解除三类 HTTP 的 pure-entry 阻断，root 后续只需统一调度必要三类真实 HTTP，不借用旧 core/storage/pool/auth 绿色。
