# manual-base实际双LCA定点修补

Parent公开 `ee815c108b791f7f2dbf801d50e4d302419e754f`。对应run40128663849/job120975470782。仅改可信gate、定点控制和本目录；未声明candidate source findings通过。

实际公共图：默认master fresh tip `892efe6f765a5748065569fd886ee9b00ef71d4b`（#598）。candidate ee815与该tip的两个真实LCA是 `e1083c6c5f4dd5fa992a43015d2ae8c764626d52`、`ae7b93f62bb62385bed8dc4110a85e4c0f315a8b`；对二者求merge-base --all只有 `e2e05b9614a3c5d62b774718797f9a6792a3eeca`，且该点是二者严格祖先、public2ec之后。旧manual CLI没有registry override，直接拒绝多LCA。

最终新增路径仅处理恰好两个合法LCA：二者的共同merge-base必须唯一、严格早于每个LCA且不早于public2ec。否则拒绝，不递归、不任选LCA、不用candidate parent、不接受任意调用参数。原fresh-fetch/checkout/repository/default branch/origin/FETCH_HEAD验证保持；原comparison-base对candidate的祖先校验保持。单LCA、PR/push逻辑和旧baseline/locking proposal/owner/command均不改。未合并master或其它history，也不产生virtual snapshot。

比较更保守：包含该共同祖先之后的两支净源码变更，可能重新列出已合入历史，不能因此提高baseline或加豁免。source回执manual_comparison明确mode/default tip/all LCAs及实际比较点；主CLI参数形状不变。先前考虑的default-tip树比较方案未用于本最终verifier。

实际仅跑InventoryAndDeltaTest的test_manual_* 10项：10/10 PASS、0skip，1.163s。新增3项用临时Git真实交叉merge图覆盖首个feature SQL违例+后续docs仍红、保守历史范围不洗白、下一层仍有多个共同祖先则拒绝。原7项fresh-fetch/伪ref/任意base/checkout/双提交等控制保留。隔离fixture不冒充真实CI。

无Java/DB/jdeps/全套回归/新CI；这些均NOT_RUN。六可信文件SHA见trusted-files.json，仅gate变化，其余5文件原样。root唯一同步后续工具提交VERIFIER及双ref补丁。
