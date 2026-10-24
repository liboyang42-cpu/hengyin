# WP10 完整内容复制候选

已实施内容复制owner迁移；仅供root受控验证和共享文件逐hunk整合，未Java/MySQL验收。基线ad7e59fb，设计af251abc；原冻结候选/工作区保持不动。

- [报告与边界](REPORT.md)：七生产文件、原调用/事务/字段合同、未修风险、迁移/回退限制。
- [原测试计数](existing-test-counts.json)：15类92个原case/断言保持；另2unit、25新MySQL case，D均未运行。
- [25个真库case及gates](mysql-cases.json)：gates={}、缺连接硬失败、旧/新使用同一fixture。
- [实际静态输出](verification.txt)：源码字节与范围检查，不是Java/MySQL证据。
- [源码范围核对](verify_scope.py)：需准确ad7源与候选目录。
- [真实执行后比较器](compare_equivalence.py)：两个完整25-case receipt目录；此轮未运行。
- [逐文件SHA256](CANDIDATE.sha256)：清单自身hash在交付回执。

```bash
python docs/delivery/architecture-day/D/wp10-copy/verify_scope.py --base-root /path/to/ad7e --candidate-root /path/to/candidate
```

同一MySQL fixture在ad7源/新源分别运行的精确复制文件和mode，见报告第5节。不得将旧/new标签改为自动fallback，也不得用Mock/H2、case数量或静态检查替代真库行差分与清理证据。
