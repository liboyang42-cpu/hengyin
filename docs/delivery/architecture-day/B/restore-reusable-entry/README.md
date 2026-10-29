# B：已注册 Backend jar 入口接七项恢复工作流

公共父提交：`9496e7028ef3793ac19a1687f9391fff9ff33e72`。本片只补已存在工作流的真实调用接缝，不新增 workflow、资源启动器、权限、secret、依赖或业务规则。

## 实际入口与身份

通过现已注册的 `backend-jar.yml` workflow_dispatch，以**本片最终集成提交所在 ref**和输入 `source_sha=<该同一个完整40位小写SHA>,run_restore_fixtures=true,run_http_fixtures=false` 调用；不假设默认分支的网页表单已展示候选新增输入。原默认两项均false仍构建原JAR；只选HTTP仍沿原 gameplay-http-e2e，相应原参数/默认范围不改。两项同时true由真正 admission job失败，不能靠三个子job全部skip冒成功。

调用为 `uses: ./.github/workflows/isolated-restore-business-reconciliation.yml`，输入仅 `source_sha`。按原项目HTTP模式，同repo相对路径使用caller同commit的callee；不要求新callee先登记成独立dispatch入口、不合默认分支绕授权，也不使用`@master`或其它动态ref。官方合同：https://docs.github.com/en/actions/how-tos/reuse-automations/reuse-workflows 。GitHub实际能否完成本候选调用仍未运行签收。

恢复模式先要求输入等于真实 `github.sha` 和 `github.workflow_sha`；callee在checkout前、unpack前及summary前再次校验。未覆盖或补造 `GITHUB_SHA`。原hosted/build/probe/summary仍核repo、手动事件、原job ID、完整SHA、checkout、run/attempt和原收据，不修改这些脚本。原callee直接manual入口保留，也显式要求同源`source_sha`；manual与workflow_call均声明required string，不将空输入默认为当前SHA掩盖错误。

## 原七项合同保留

原 `restore-source-build` 只build一次并发布原attempt绑定artifact；七matrix原payment-gap、withdrawal-complete/gap、refund-complete/gap、voice-complete/gap不删改，`max-parallel:20`、fail-fast:false及原host/隔离scope/精确cleanup/JSON-only artifact不变。原summary仍独立核七份原收据与实际一次构建JAR/tool摘要，不重建、不混源、不把gap变放行。

backend-jar的原jar与http分支均依赖admission且排除restore，因此restore-only不先构建另一个JAR或顺跑旧HTTP。caller不继承secrets、不增加permissions、不持callee相同concurrency组；原backend concurrency/cancel-in-progress:false保留。

## 配套控制源码与限制

- `ci/test_restore_business_reconciliation_build.py` 保留原7个测试，新增4个测试方法，共11条源码声明：实际固定admission shell的三合法选择/双选拒绝、错误SHA/实际workflow身份/事件/repo/未知模式拒绝、同commit相对uses与无第二build合同、callee三段原source校验。只在root后续执行时调用这些固定无外部效果shell，不是运行Java/DB的替代。
- `ci/test_http_e2e_entry.py` 仅随caller新增互斥条件迁移原ManualRouterTest接线，原所有正负例保留，没有删断言或把未知当成功。原host controls继续核七matrix、JSON路径和一次构建，本片不动其源码。
- 本轮只读源、编辑、git diff/applycheck及哈希；未运行任何测试、YAML解析、Python导入/控制、Node、编译、Java/DB/HTTP、CI或资源/故障。全部 **NOT_RUN**。没有workflow dispatch、默认分支合并或部署；root唯一集成并决定同源后续验收。
