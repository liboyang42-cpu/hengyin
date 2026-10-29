# D / WP10 内容复制：仅文档候选

建议先迁移完整的模板使用/内容复制闭包，保留现有主题门面及原授权、事务、字段和图兼容。具体方案、精确方法owner分配、共享helper反向调用、T14表征、Q5/T15演练及阻塞见 [PLAN.md](PLAN.md)。**没有生产实现或Java/MySQL等价验证。**

观察与远端候选parent：`ad7e59fbf6a20e85e741318afe654c5411bf1101`。本轮只写此交付目录；不改CmsTopicServiceImpl/Controller/G/A/B/E文件，不派发CI，不执行DDL。W19/signup原候选冻结。

| 资料 | 用途 |
|---|---|
| [PRODUCTION_CALLERS.csv](PRODUCTION_CALLERS.csv) | 192个直接/方法引用/显式provider调用点，55文件 |
| [FORWARDED_CALLERS.json](FORWARDED_CALLERS.json) | 四个Orient调用方经既有辅助方法传递，七处 |
| [PUBLIC_METHODS.json](PUBLIC_METHODS.json) | 58个公共方法与建议责任方向；不是已批准移权 |
| [INTERNAL_METHODS.json](INTERNAL_METHODS.json) | 207个词法方法定义、自调用名称及依赖；不是编译器绑定分析 |
| [EXISTING_TESTS.json](EXISTING_TESTS.json) | 22个关键词命中类的case与证据类型；全部未在本轮执行 |
| [SOURCE_FINGERPRINTS.json](SOURCE_FINGERPRINTS.json) | 107个关键公开来源的Git blob/SHA256 |
| [AUDIT_SUMMARY.json](AUDIT_SUMMARY.json) | 扫描范围、数量与未运行状态 |
| [VERIFICATION.md](VERIFICATION.md) | 本轮轻量校验真实输出与局限 |
| [CANDIDATE.sha256](CANDIDATE.sha256) | 本目录每个交付文件SHA256，清单自身另报hash |

在公共ad7e完整checkout或同字节只读快照运行：

```bash
python3 docs/delivery/architecture-day/D/wp10-copy-design/verify_evidence.py --source-root /path/to/ad7e
```

这个脚本只核对来源指纹、静态调用清单和资料一致性，PASS不代表业务测试通过。基线升级后应重新盘点，不能放宽指纹断言。下一步需要root按PLAN第6/10节分配共享文件的精确方法编写权；当前文档候选可直接独立审阅/取回。
