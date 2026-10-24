# 已实施的 M1 比较器窄修复工具

本工具候选从冻结 `1c138a000387583e224c9acb5369140fdf2dba18` 独立建分支，正式应用 `230a4a54` 中已审查的两份补丁；旧冻结候选和原始证据不改。生产比较器已实际修复，运行时不依赖应用review.patch。

- PK/FK关联：仅用Decimal数值完成无符号会员PK与有符号ledger.member_id的关联，累计回原typed PK桶；原typed raw、同列/跨source类型、余额、完整ledger行仍比较，孤儿/重复数值身份拒绝。
- DATETIME(0)：仅原登记ledger.create_time恰在high+1、原DATETIME scale0且非截断模式，并有同业务身份的成功已COMMIT INSERT、case窗内原Date bind和精确codec+秒舍入等式，才承认该端点。没有更宽时间容差、ignore或新日期白名单。

原 `test_compare_receipts.py` 16控制和 `inputs-manifest.json` 逐字不变；XML/source/CLEAN、CLI、canonical/compare主体、金额/ledger→member锁序校验保留。既有13控制方法正文/断言与230a逐AST相同；唯一测试装配变化是直接import本候选实际compare_receipts.py为SUT，不再先把补丁套在临时副本后测试。两份review.patch在此只用来反向重建旧规则反例见证，其SHA256必须为冻结工具的`8daee5ccef9d4966d3e45effdf7be2527cf109639eb4ce6e0893ac86b4375c23`；不需要git历史或网络，重建副本绝不作为新工具的SUT。

实际运行：原16/16 + 精准13/13，0失败/错误/跳过；无.git的独立临时副本同样29通过。内存变异负控中，关闭实际SUT金额校验产生1个预期失败，关闭实际SUT时间校验产生4个预期subtest失败，证明控制不会绕到临时已修副本self-pass。

root精确引用两个入口（应都执行，共29控制）：

```sh
PYTHONDONTWRITEBYTECODE=1 python3 docs/delivery/architecture-day/C/evidence/wp09-m1-equivalence/test_compare_receipts.py
PYTHONDONTWRITEBYTECODE=1 python3 docs/delivery/architecture-day/C/evidence/wp09-m1-comparator-patches/test_member_fk_review.py
```

比较CLI路径和参数保持原样。root唯一更新workflow与pinned工具/测试SHA；本候选未改CI、observer、14输入manifest、旧新source候选、raw/XML/trace或原金额。root已独立回放run40061448735原证据14pair一致；本工具候选没有重新生成、修改或公开原始证据，也没有运行Java/DB/CI。
