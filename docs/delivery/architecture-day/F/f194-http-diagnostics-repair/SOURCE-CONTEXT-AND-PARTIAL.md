# Suppressed 上下文与仅 Generic 的诊断入口

本增量 parent：`d20412c1b223741600ada12435697d9505f6a8ed`。分支 `codex/architecture-day-f-generic-source-context`。仅修改既有 `ci/test_http_e2e_entry.py`、获准的 `.github/workflows/gameplay-http-e2e.yml` 和本 F 目录；不修改 A 的 Java、生产、schema 或其他 workflow。

安全投影只读取已识别 testcase 的失败 stack 原文中完整一行 `Suppressed: java.lang.AssertionError: HTTP_SOURCE_CONTEXT stage=<枚举> endpoint=<枚举>`；枚举严格限本轮 root 批准的 8 stage/16 endpoint。输出 `caseSourceContexts`，全体已识别失败 case 的上下文都一致时输出唯一 `sourceContext`；缺失覆盖/冲突/未知/追加文本/动态 JSON 冒充保持 UNKNOWN。没有 marker 时不新增上下文字段。重复同一条固定上下文去重，不输出任何原动态文本。原 failure/response/code/41 类消息映射不变，原实际 case 帧和混合 summary 真实性控制不减。

原 shell 已能选择单类，但原 hosted workflow matrix 固定三类，无法避免重复已通过的 Freeze8+2。本片只给原手动 workflow 新增 `http_scope` 两个固定 choice：默认 `all-three` 保留原三类，`generic-only` 只创建 Generic 一个执行 job。未知 scope 在 heavy setup 前拒绝；matrix 是两个固定字面数组，无任意 class、命令、权限或依赖入口。原 reusable caller 无 scope 参数时仍默认全部三类。max-parallel20、contents:read、hosted/profile、源身份、网络/清理围栏和原 build 行均不变。

summary 默认仍调用原三类/36 项严校验。`generic-only` 必须只有同 source 的唯一 Generic 回执，并经原 `validate_final` 的 class/fixture/XML/26 精确计数/java0/cleanup 全部门槛；即使通过，也只输出 `PASS_PARTIAL_DIAGNOSTIC_SCOPE` + `scope=PARTIAL_DIAGNOSTIC_SCOPE`、26 项和 `completeHttpSuiteAccepted=false`。不能加入同/不同 SHA 的旧 Freeze 回执补签；失败仍非零并输出标为 partial 的安全失败摘要。`HTTP_JOBS_RESULT=success` 检查保留。

root 最后集中调用现有 workflow 时选择 `http_scope=generic-only`，source_sha 使用合成 A/F 后完整 SHA。这里不派发 CI，也不宣称当前 16 个实际失败已修。

实际必要控制仅一次：`python3 -m unittest -v ci/test_http_e2e_entry.py`，**36/36 Python 控制 PASS，0 skip，7.229 s**（不是 HTTP36 执行）。原 31 项保留，新增 5 项覆盖固定枚举/冲突/JSON/canary、已识别 case 才公开、严格 partial26 与默认拒绝缺类、额外/foreign/重复/未知 scope 拒绝、原 workflow 选择及设置前拒绝。输出见 `source-context-python-controls.txt`。静态比对确认原 41 map/parser、validate_final、全量 summarize、混合 failure_summary 和原 case-frame helper 逐字未动；既有 workflow matrix 断言仅更新为两个精确固定数组，默认三类断言仍在。diff/秘密扫描通过。

A Java enum/lifecycle/suppressed 实现的合成编译、真实 Generic26 HTTP、Java/MySQL/Redis/CI：NOT_RUN。Python 使用合成 XML/回执和 shell 纯入口控制，不代替真实后端。未知原因继续 UNKNOWN；本片是取证/减少重复运行入口，不是业务修复。
