# f194 HTTP 诊断投影定点修补

parent：public `f194799f35811c8153a750dee2958f48ee5b5ab6`；分支 `codex/architecture-day-f-f194-http-diagnostics`。仅修改 `ci/test_http_e2e_entry.py` 和本 F 目录。A 独占真实 Java HTTP 根因；本片不修改 Java、业务行为、workflow、schema 或其他 owner 文件。

1. 原 `failure_summary` 把所有已验证回执都写成 FAIL，是投影缺陷。现在每条先调用原 `validate_final(receipt, source)`：完整满足原门槛才能保留 PASS，并仅对这些记录保留 tests/XML/fixture/Java0 证明字段。输入声明 FAIL 保留 FAIL；声称 PASS 但证据不完整为 INVALID；未决或未知声明为 UNKNOWN。身份 foreign/未知/重复仍拒绝，不晋升。整体 fallback summary 继续 `FAIL_HTTP_FIXTURE_ONLY` 且 CLI 返回非零，不把 2 个真实通过类误写失败，不把 Generic 失败抹平。
2. 原两条 helper 帧照常保留。额外在完整 stack 中寻找该已登记 testcase 的精确类名/方法名/文件名，并核对行号落在当前源码中该方法的声明至同缩进结束行范围；最多追加一个真实 case 帧，使 `caseSourceFrames` 每 case 最多三帧。`caseCallFrames` 仅在实际找到时输出该精确调用位置。未知方法、foreign 类、方法范围外行号或不支持的源码布局不生成 case 帧。没有新通用 Java parser；这只是现有三份受控测试源码布局的有限定位，不证明任意 Java 语义。

原 `validate_final`、成功 summary、41 个固定 literal、exact parser 和固定分类/UNKNOWN/redaction 控制逐字未变。没有加入动态消息白名单，没有输出原 XML、response、SQL、secret、任意 stack 原文或 Java 源码。

控制实际执行：首次 `python3 -m unittest -v ci/test_http_e2e_entry.py` 为 **31 项，30 PASS、1 FAIL、0 skip，6.115 s**。唯一失败是新混合 summary 测试误把 rglob 文件遍历顺序当作 class 顺序；按 class 排序后仍比较所有字段，仅重跑这一项，**1/1 PASS、0 skip，0.105 s**。原场景及断言保留；新增 4 项覆盖 2PASS1FAIL 且 CLI 非零、伪 PASS/foreign/重复/未知不晋升、两 helper 加实际 case 的真实 CLI 安全投影、普通/参数 testcase 精确范围正反例和 canary 不公开。完整首次输出和定点复验输出见 `python-controls.txt`，不能表述成一次 31/31 全绿。

这些使用合成 XML/回执直接执行 Python CLI，不是 Java/HTTP/数据库验收。root 已提供的实际 run40149600486 证据保持独立：FreezeGame8 与 FreezeReuse2 原回执 PASS；Generic26/16failure/0error/0skip 的 500/UNKNOWN 是实际业务失败，由 A 修复；本片诊断修补不能改变该原结果。Java/DB/真实新 HTTP/CI：NOT_RUN；未派新 run。diff 和本片秘密扫描通过。
