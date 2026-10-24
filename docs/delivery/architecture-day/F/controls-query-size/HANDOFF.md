# F：c135后续工具配套Query规模单向许可

结论：parent `c135c71fc9067d2d0fc1783dc60a2a7b9b26064c`，直接把compiled_controls.py固定bd33改为独立verifier `0979b03b594b5b5bbc4ad500c89bca6b3d353d70`；旧bd33/f853/0e00/浮动/脏checkout均拒绝。未经过3327的0e00阶段，不回退policy。源树只改工具SHA、专属拒绝控制、本目录；只取c135之后delta，不把工具分支170旧35型生产/policy快照覆盖root。

新verifier parent f853（parentbd33），完整承接0e00缺raw行UNKNOWN、bd33 E_TOPIC_QUERY、f853确定SQL写owner修复，再追加Query→已存在D_CONTENT_SIZE_POLICY一条方向。root简称D_SIZE_RULES，真实owner ID以owners.json为准；仍56类型/45职责/52方向。原Query禁Facade/runtime/publication/claim不变，3private Mapper/2whole-table facts/2ecbaseline不变。六可信hash见trusted-files.sha256；root须纳bd33→0979完整delta（含f853）后逐项核对，而非仅owner.json更新。

实际源b9b894中Query.selectChapterTemplates借TopicSizeLimits.MAX_NODES=300进行IN批读分块。常量可能内联也可能有class引用，实际raw尚无证据，不据常量宣布无边。若实际raw未观察到该边也不证明源代码没有常量变更传播；传递纯读/方法/表写权仍UNKNOWN。既有producer/hash/index/JAR/source/run/attempt读回完全复用，不再跑一遍jdeps或新增scanner；全部声明类型缺raw行仍UNKNOWN，合法方向不能替代输入完整性。

工具8/8 Python、0skip（python-tests.txt）；原5工具控制正文保留，无伪造图/替换JDK结果。实际干净0979 checkout load_verifier成功，读到52方向且精确Query→Size已声明，未调用Java。verifier独立199/199、83producer含3新正/反/缺行控制；两份Python结果不合并为同源Java验收。真实leaf/JAR/javac/jdeps/DB/CI全部NOT_RUN。

root唯一双ref review.patch：公开7da3只作未变checkout结构定位，preimage bd33来自root本轮已纳双pin的明确报告，**不声称公开7da3自身已是bd33**。替换两处bd33→0979的临时Git apply模拟通过；若root布局改变逐hunk适配，不整旧workflow覆盖。工作流/registry/生产源码未修改。

leaf-only和完整三边后置控制命令沿c135交付不变，verifier路径HEAD须固定0979、完全干净；从本工具经审定的固定commit取执行文件，不由candidate浮动脚本自批准。完整模式仍要原实际tested JAR/startup identity/11hash先scoped PASS，再双leaf和SDK→router、DTO→facade、Common→Framework的真实negative/restored派生JAR控制；leaf-only只确认实际空class/interface raw形态，不能替代三项编译负控或主应用验收。无新job/框架/依赖/生产访问/本地Java。
