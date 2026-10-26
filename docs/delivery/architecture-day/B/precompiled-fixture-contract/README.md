# 五项共享fixture/caller接缝 SOURCE

父提交：`871b02d83676b3a384d5998ece65b30edf182b33`；原测试blob `34063e43e52452738d9fe4f39514243d3b401c8e`。仅改测试及本文，生产helper/C1/stub/C三Java/POM/CI均不改。

1. `configuration_scope`生成了11个真实固定方法名却仍写tests=9，且count/旧子集负控还替换旧9。统一从原完整方法集合生成header，保严格11及source/class绑定，修负控替换目标，并补原9缺新增2方法的子集拒绝；原旧2/3/5/7及其它负控均保留。
2. 缺离线工具链证明用例原pop整个cacheProof，同时删除新先决stub证明，先撞controlled guard。现在仅保留完整合法独立typed stub证明，删除所有原offline/core字段；原OFFLINE_CACHE_PREFLIGHT_REQUIRED断言保持，所测旧门禁确实可达。其它逐字段变异保持。
3. 原ONLINE probe→暂停生成→Config/Photo→OFFLINE已分阶段，但复制XML断言仍查ONLINE段。改为在Config/Photo段断言两份原XML各仅复制一次，并拒其出现在两probe段；三个fork段逐一保原无skip/单fork/reuse要求、原业务身份和预算不减。
4. `warm_transport_control(INVALID_RECEIPT)`先返回无final receipt，随后合成合法ONLINE 0及compiler 0，最后才注入坏final receipt，故既有observed=true来自先前合法事实。生产warm_cache在首次坏receipt校验前不赋observed；此处无已证helper语义bug。将坏值限定注入首次原final receipt读取，保原observed=false/null、不公开值/失败/cleanup断言；同一原方法增加已有合法ONLINE 0后坏final的独立场景，证明早期合法0保留、坏值不成为新退出、compiler独立0、overall仍失败。无原运行内层Maven/业务结果推定。
5. profile原4模板断言漏新Config/Photo模板。明确枚举package数组、ONLINE probe、Config/Photo、OFFLINE probe、offlinepreflight五段，逐段核唯一profile及原admin/-am选择，并核实际业务命令同profile；package数组的原offline尝试/条件online补齐仍复用同一模板。原缺类/编译漂移/离线与禁止skip负控均保持。

全部原125方法身份保留，不新增方法或镜像式测试；共享fixture的所有直接调用者保原签名。只做本轮明确失败的装配/断言接线，不扩大审查。源码依据区分首次未知与已观察原事实，不换预期异常串掩盖旧负控。

仅源码读取/编辑、Git差分及运输摘要。parse/import/py_compile/tests/Java/Maven/DB/Docker/HTTP/CI全部 **NOT_RUN**；不宣称125/220通过，不把准备控制结果当业务或内存收益，root仅集成不立即重跑。
