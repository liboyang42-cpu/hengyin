# 最终CI命令来源定点修复

Parent `07ff55fe0ec1e460dc93f4bc1d07caf54738f329`。仅修ca0e/run40122522919/job120956965186的新checklist错误读取2ec。

旧4命令仍固定2ec；新3命令source_sha分别固定公开G54c/D379d/Ge57。可信gate中的精确ID→SHA绑定拒绝缺来源/HEAD/分支/其它SHA/未审命令；commands仍按六文件冻结，candidate不能自选来源。所有entry/caller/persistent/test路径及symbol仍逐项git blob核对该固定public提交；缺blob不回退candidate/本地文件。BASE和两份旧baseline完全不改。

定点RegisteredReferencesTest 3/3 PASS，0skip（最终输出见根可复跑命令 `python -m unittest ci.architecture.test_gate.RegisteredReferencesTest`）。初始新增负例误选了G源中已存在的test_gate路径，改为当前checkout存在、G固定源不存在的本F PIN.md；未减断言。未跑全套Python/Java/DB/jdeps/CI，不据此声明统一CI通过。工具pin和root双ref补丁在后续独立提交；需要完整public Git对象，缺失仍失败，不动态信任candidate。
