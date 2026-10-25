# ae0f：verify 前失败回执保留原身份与首因

parent：public `ae0f1dbd4df2d65befd9214ffdeb20d7042b9d1e`；独立分支 `codex/architecture-day-f-ae0f-http-failure-receipt`。仅修改 `ci/test_http_e2e_entry.py` 与本 F 目录，不修改 shell、G 的 fresh-schema 源码/测试、生产、workflow 或迁移。

root 已从 run 40147612418 三份原 artifact（12580804529/12580609002/12580324493）确认 schema-apply 首因：fileIndex56/mysqlErrorCode3821，stage=schema、javaExit98。此阶段没有 Java/HTTP 执行证据。缺口是 verify 尚未生成 receipt 时，finalize 从无身份空对象起步，随后 selection 空 classes 产生次生错误。

本片复用 shell 已导出的 classes/SOURCE_SHA/profile/任务 schema；先做精确选择、完整 SHA、profile/schema 格式校验。有既存 verify receipt 时必须与当前输入完全一致，有 schema report 时必须属于同一任务数据库。有效输入在 before-verify FAIL 回执中保留；非法/重复/foreign 元数据拒绝且不公开其原值。没有生成 tests、XML 或 fixture 已验证事实。

FAIL 回执及独立 diagnostic.json 共同保留原 stage、退出码、安全首因与真实 cleanup 布尔值。javaExit98 原样保留为入口 sentinel，不解释成 Java 已执行。原非零退出不能被 javaExit0、声称有 XML 或成功 cleanup 晋升为 PASS。finalize 打印安全首因后返回失败，避免再走空 classes 的次生验证。

summary 仍先执行原严格 PASS 校验；失败时只为身份合法、同 source、无重复类的回执生成 `FAIL_HTTP_FIXTURE_ONLY` 安全摘要，并返回非零。摘要保留原诊断数值/阶段及 cleanup，不输出“已通过测试数”或业务验收。缺少/未知 cleanup 保持 null；未知或 foreign 身份不被接纳。`validate_final` 继续拒绝 FAIL、无 XML、fixture 未验证、未知/重复 class、错误 source 与 cleanup 未确认，全部原 PASS 门槛保留。

实际仅一次运行 `python3 -m unittest -v ci/test_http_e2e_entry.py`：**27/27 PASS，0 skip，4.195 s**。原 21 项保留，新增 6 项覆盖三类 before-verify schema 失败回执/失败 summary、缺报告与 cleanup=false、unknown/重复/foreign metadata、原 receipt source 冲突、失败摘要 canary 投影、原非零退出不能被 Java0/声称 XML 晋升。完整输出见 `python-controls.txt`。这些是直接 CLI/元数据控制，不是真实 MySQL/Redis/HTTP 或测试执行证明。

静态逐字比对确认：原 41 literal map、exact 分类 parser、固定原文/动态追加/空格 UNKNOWN 和 redaction 两项控制均未改变。diff/秘密扫描通过。Java/DB/真实 HTTP、G 的 schema 调用者验证和新 CI：**NOT_RUN**；不重复旧 core/storage/pool/auth。root 合入 G 必要调用者后统一安排目标 HTTP 验证。
