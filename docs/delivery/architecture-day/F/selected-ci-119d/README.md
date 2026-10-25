# 原 CI 显式手动定点入口（SOURCE / NOT_RUN）

本片只补 `.github/workflows/ci.yml` 的实际入口缺口，复用原 jobs、原20分片/Node命令、原 manifest 与原聚合，不新增 workflow/job/runner/依赖或任意命令输入。parent `119d3f87bbf89162d47db837c404b361915d1b81`，独立分支 `codex/architecture-day-f-selected-ci`；root唯一应用/派发。此前registry/restore-entry片不在本diff，不回退它们。

## 可用输入合同

- 默认 `validation_scope=full`：原PR/push路径路由、默认manual全开、完整required job实际名称、原20 shard、max-parallel20保持。三个selection输入不能在full模式偷带筛选；非法/空模式拒绝，不默认变成绿。
- 额外显式 `validation_scope=selected`：`selected_jobs`为不带空格/不重复的精确CSV，只允许 `fast_gates,backend,mini_unit,mini_static,admin_ui,admin_ui_contract,dependency_audit,migration_layout,migration_safety` 中的既有ID；空/未知/重复/脚本字符拒绝。
- 仅选了mini_unit时，`mini_shards`须为非空、唯一整数1..20的JSON数组；未选mini_unit则必须`[]`。没有119d六个失败片的内置默认值。执行仍是原 `node --test --test-shard=N/20 "tests/unit/**/*.test.js"`，没有新测试执行器/分片算法/精确文件DSL。
- `changes` 与新源码秘密扫描始终执行，不能由selector排除。selected的run-name、各job与聚合明确PARTIAL/SELECTED；不同concurrency后缀防止部分验证取消同ref完整验证。

root当前拟复验的job可以显式选择 backend、dependency_audit、migration_safety、fast_gates、mini_static、mini_unit；是否还需migration_layout等由最终实际修改决定。分片ID必须依据最终source manifest与D等实际新增/修改文件决定，不能把旧六片当完整受影响集。本工具保存当前Node manifest的文件表/hash/count，但**不自造Node分片映射、不证明语义依赖闭包或“全部受影响项已选”**。若不确定分片覆盖，应调整显式选择，而非借旧SHA绿回填。

## 调用、汇总和证据

`changes`既有checkout后调用固定helper `plan`，对真实HEAD/GITHUB_SHA/run/attempt和固定输入准入；输出选片exclude，只排除未选partition。原unit discovery/selftest/命令保留，新增同一job的`record-mini`检查原step实际outcome，skipped/未执行/失败不签通过。每片只上传一份metadata收据，不伪造JUnit数或原exit code。

既有`mini_program`在selected模式等待原所选jobs、秘密扫描与逐片原回执，校验精确shard集合/无重复/源与run/attempt/manifest匹配。缺失、foreign、取消、未执行或未选job意外执行皆FAIL。完整模式仍只执行原 changes/unit/static 三条required判断，不把fast/AdminUI等的失败并入完整mini required判定；依赖扩展只用于同一既有job的selected收口，没有新总控job。

输出：`ci-selection-plan-<attempt>`的plan/current mini manifest；`ci-selected-mini-N-a<attempt>`的原step outcome；`ci-selected-summary-<attempt>`只可为 `PASS_SELECTED_ONLY` 或FAIL，`fullSuiteVerified=false`。这不签20分母全绿，不能与119d旧绿拼成新SHA完整通过。full模式的原job显示名保持；selected名称不同，原完整CI/发布核验不能借其代签。

四份源码：ci.yml、固定helper `ci/ci-selected-validation.js`、6个直接Node控制SOURCE、原 `ci/test_ci_cost_contract.py` 的必要精确接线/负控更新。原cost控制/原20片/原必需断言全部保留；新负控覆盖非法/空/重复输入、全选/部分选、未执行/取消、缺片/重复/foreign/manifest漂移、默认完整与无条件秘密扫描。

**全部NOT_RUN**：没有Node/Python控制、parse、编译、DB/HTTP、CI。实际仅Git应用/差分检查与摘要/有限新增行秘密模式检查。`review.patch.b64`包含四文件完整diff，decode后SHA在`PATCH.sha256`；作者后像在`POSTIMAGES.sha256`。root应用该快照后仍由root决定新的同源定点验证，不自开CI。
