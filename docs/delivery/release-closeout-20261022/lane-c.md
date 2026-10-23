# Lane C — CI 门禁收口（Doc 1 §3 E1/E2）

- 日期：2026-10-22
- 参考 master：`063a6c3cd7c1f131618148c385174037f1d2786e`（与任务给定 063a6c3c 一致，执行时实测）
- 本 lane 分支：`feature/lane-c-ci-gates-20261022`（仅本文档）
- 修复分支：`feature/lane-c-449-fix-20261022`（基于 #449 head `0936ff1a`）
- 新 draft PR：https://github.com/liboyang42-cpu/hengyin/pull/467 （引用 #449；未合并、未标 ready）

## E1 — #434 核验（只读，未合并、未评论、未改动）

| 项 | 证据 |
|---|---|
| 状态 | open、非 draft，head `65cc424d4b9310c37149ed57ce16fbbe23495487`，base 记录 `13f20b16` |
| 可合并 | GitHub `mergeable_state: clean`；本地 `git merge-tree --write-tree origin/master(063a6c3c) PR-head` 退出码 0（零冲突，树 `20d3237b…`） |
| 落后 master | merge-base `13f20b16`，master 领先 146 个提交；#434 改的 6 个文件里，master 另外改动的 2 个（`ci/test_upgrade_startup_network.py`、`ci/upgrade-startup-smoke.sh`）与 PR head 字节一致，不会冲突 |
| CI（head 上） | 34 个 check run：29 success、5 skipped（Admin UI build/E2E、Migration safety/layout、SBOM：按路径跳过）、0 failure。主 CI run 39603508895（Backend tests、Mini static gates/checks、20 个单测分片、gitleaks、Fast gates 全绿）；Funds run 39603508883 绿；Real-HTTP chains run 39603518318 绿 |
| 夹具清理范围 | `ci/chain_postdeploy.py`：所有夹具写都走 `fixture_write`。先执行 `identity()`，要求 `DATABASE()=="cy_e2e_51"` 且 `@@server_uuid` 等于首次读到的值；`prepare_restart_seed` 还要求 fresh provenance：容器名必须匹配 `cy-chain-<run>-<attempt>-{mysql,redis}`，source SHA 固定，两次时钟身份一致。删除 `sys_job` 用的是逐行全列 `<=>` CAS（只删已核对的 22 条种子行），删除前 `FOR UPDATE` 锁定前像并逐字比对，提交前和新连接回读都必须为空，否则回滚。结论：清理只作用于任务自建的测试库 `cy_e2e_51` 的 `sys_job`，不会作用到其它库 |
| 说明 | CI 是对旧 base 13f20b16 的合并结果跑的；之后 master 又前进了 146 个提交，没有对新 master 重跑（不改他人 PR，也不 kick CI）。合并前建议 owner 先点 "Update branch" 或手动 rerun 一次 |

## E2 — #449 失败原因与修复

读取原始失败日志：run 39607786731（job 119248600207 单测分片 2/20、119248599680 static gates、119252080540 static checks）。

1. **单测分片 2/20**：`community-api-namespace-gate.test.js:41` 要求 `BASE + path` 报"运行时变量"。#449 的 `values()` 把未知 `+` 右操作数当成 `:dynamic`，于是"命名空间常量 + 任意后缀"被判为有限；而命名空间声明字面量又不进文件级字面量扫描，等于这个请求完全没被核对。
2. **static gates（UI-GATE-0 U1，11 项）**：
   - 8 项误报。`requestFinite` 取的是 callee 范围覆盖匹配点的第一个调用，遇到 `a().then(()=>app.sendRequest(…)).catch()` 会命中外层调用；`community.request(` 的匹配起点又在 receiver 上，不在点号上。另外 options 对象 wrapper（`Object.assign({}, options, …)`）被当成"实参整体就是 URL"去校验。
   - 3 项过期登记：#449 现在能正确证明这 3 处是有限 URL，基线因此未命中。这是保留文件的事，见下方 handoff。
3. **static checks**：汇总 job，只因上面两个 job 失败而失败。

修复内容（提交 `ece01d38`，只改 `chengyinhub-xcx/tests/lint/ui-integrity.js`，新增 `tests/unit/ui-integrity-finite-api-wrapper.test.js`）：
- 请求调用定位到紧随匹配点、属性名为 `sendRequest`/`request` 的那一次成员调用。
- 每个候选值都必须命中真实后端 Mapping。
- `url` 由最后写入方决定（对象内属性/展开倒序，Object.assign 参数倒序），据此区分 url/options 两种 wrapper；后续参数可覆盖 url 时，不承认为受控 wrapper。
- `{url, ...(opts||{})}` 只有在调用处实参可证明不含 url 时才放行；有同文件成员改写或 `Object.assign(x,…)` 的对象一律不证明。
- 分支写入：只有当每条路径都已赋值或已离开时，才按全部写入的并集证明，其余情况保持未知。
- 已知局限：不做跨函数的堆逃逸分析。

没有扩白名单、没有提高基线或上限、没有跳过断言、没有执行任何应用模块。

## CI 运行与结果

| Run | 对象 | 结果 |
|---|---|---|
| 39607786731 | #449 原 head | 单测 2/20 红；static gates 红（U1 11 项）；static checks 红 |
| 39759665180 | #467 head `ece01d38`（合并 master 063a6c3c） | 20/20 单测分片绿；gitleaks、Fast gates、Detect changes 绿；static gates 红，**只**剩 3 条过期 u1Variable 登记（ACTION-LEDGER PASS、route-matrix PASS、U1 PASS 变量路径 8/逐项理由 8）；static checks 随之红 |
| 39759665498 | #467 Funds | 绿（按路径判定，实际 0 项） |

static gates 的红**不是**因为 ledger/route-matrix，而是因为 C00 保留文件 `tests/lint/ui-integrity-baseline.js` 里 3 条过期登记需要删除。

## 验收清单

- [x] 先读原始失败日志再定位真实原因（见上）
- [x] 修复分支基于 #449 head，新 draft PR #467 引用 #449
- [x] 正负控：未知分支、顺序重赋、shadow（块级/形参/`Object` 本身）、可变闭包捕获、覆盖/展开、对象改写、链式定位、分支并集，共 9 项新测试；在 #449 原 head 上，前 5 项全部红
- [x] 本地：`node --test "tests/unit/**/*.test.js"` 13093/13093 通过、0 skipped；`ui-integrity.js --selftest --warn-as-error` PASS；`ui-integrity.js --warn-as-error` U1 PASS，UI-GATE-0 仅剩 3 条过期登记（临时在本地删除后 PASS，未提交）
- [x] 未跑 `ci/xcx-check.sh` 和 `ds-hardcode-gate-selftest.sh`
- [x] 没有新增 Actions matrix（既有分片 matrix 已是 `max-parallel: 20`，GitHub-hosted `ubuntu-latest`）；没改 workflow，不涉及 secrets 或生产
- [x] #434 只读核验，未合并

## 未决 / 交接

<!-- CY-HANDOFF -->
integrator 修改 `chengyinhub-xcx/tests/lint/ui-integrity-baseline.js`：
- 删除 `u1Variable` 中的 key：`chengyinhub-xcx/subpackageMember/components/cy/profile/index.js:1095`、`chengyinhub-xcx/pages/activity/official-inbox/index.js:109`、`chengyinhub-xcx/subpackageA/pages/myproject/index.js:571`
- `ratchet.u1Variable` 11 → 8
- `ratchet.keyDigest.u1Variable` `7b3271d1823b077ec5c15ac47b0f3812c2de5eb0c6416a3ce1fc821bbf7758b4` → `52fbcba7c6ba58a544ed92206874b6846c510041db927759ca4a1d3d0cb36bfb`
<!-- /CY-HANDOFF -->

- #434 合并前需要对最新 master 重跑 CI（owner 操作）。
- 不在本次范围：acceptance-213 CloudTen 夹具（Doc 1 §5.3），等 owner 提供旧文件。

## owner 需在本地 WeChat DevTools 手动确认的项

无。本 lane 只改静态门禁脚本和单测，不改页面运行时代码。
