# F 手动比较点修复

结论：修复 workflow_dispatch 只比较 candidate 第一父提交、漏掉同分支更早业务改动的盲点。WP01 已暂停；没有修改其工作树、Backend artifact、真库登记或 A/C/D/E 业务文件。旧 527 候选保留。

## 身份与范围

- 仓库仅为公共 `liboyang42-cpu/hengyin`；公共存量 baseline 为 `2ec68899099a1e250df0949a8383bf85b5d3ffe0`，本补丁父候选为 `527adf3fe003b6e9022c255f9e7a3716a9aabe95`。
- 独立工作树 `/workspace/hengyin-f-manual-base`，分支 `codex/architecture-day-f-manual-base`。没有切换或重置其他工作树。
- 实现文件仅 `ci/architecture/gate.py`、`test_gate.py`、`README.md` 及 `.github/workflows/ci.yml` 的 F 标记区间；其余为本 F 交付证据。本次不改 baseline/owner/command 元数据、不新增依赖或审批 JSON。
- verifier 已单独冻结为 `cdc2e7d0b28e609dae2787500d6e0e345da47899`，workflow 再 pin 此完整 SHA；最终候选身份由提交后的 Git HEAD/远端回读给出，不以报告内自引用 SHA 代替。

## 失败机制与修复合同

原 manual 路径在 workflow 和 verifier 都使用 candidate^1。feature 首提交新增越界写、次提交只改文档时，前者被错误排除。

调用链改为 workflow 的 manual 分支 → 冻结 verifier 的 `manual-base` 子命令 → `manual_base`；source 检查再调用同一函数重新核对 supplied compare-base。候选必须等于真实 GITHUB_SHA，仓库身份固定；默认分支只能来自真实事件 repository.default_branch，先由 git check-ref-format 校验。origin 的全部有效 URL 必须是固定公共仓库 HTTPS 地址。显式 fetch 该分支到 refs/remotes/origin/<default_branch>，核对该 commit 与本次 FETCH_HEAD 相等，再求 candidate 和主干的唯一 merge-base。缺上下文、其他 origin、fetch 失败、远端引用不一致、无/多 merge-base 均失败，不退回缓存或 parent。

PR 和 push 既有比较合同不变。若主干在 workflow 求比较点和 source 复核之间移动并改变 merge-base，将拒绝，需要重跑；不静默缩小范围。不会反复报告已包含在真实主干比较点中的历史。固定存量 baseline 仍从 2ec Git blobs 精确再生；没有扩大任何例外。

## 实际执行

- `python3 -m unittest discover -s ci/architecture -p 'test_*.py' -v`：57 项全部通过，原始输出 selftests.txt。
- 关键负控：首个 feature commit 的跨表 owner 写、第二个 docs-only commit，manual 仍有 NEW_TABLE_WRITER，故意传第一父提交被拒绝。正控：已进入真实主干的同样历史后接普通文档小改，仅报告本次文档、无 findings。另覆盖主干分叉、非法默认 ref/远端、fetch 失败但本地有旧 ref、FETCH_HEAD 不一致、歧义 merge-base、伪造 checkout/仓库。
- Git 图与 ref/fetch/merge-base 使用真实本地隔离 fixture；仅把网络传输重定向到 fixture bare repository，不把它当真实 GitHub dispatch。
- `GATE=ci/assert_it_ran.py python3 -m unittest discover -s ci -p 'test_assert_it_ran.py' -v`：5 项通过，原始输出 assert-it-ran.txt。最初未设 GATE 的试跑因既有脚本要求环境变量而 ImportError，已用上述正式命令纠正；不是后端测试失败/通过证据。
- `python3 ci/test_ci_cost_contract.py`：PASS，ci-cost.txt。F 区间外 workflow 逐字相同，未新增 job/矩阵/并行，20 shard 预算不变。
- 固定 2ec baseline 字节再生、git diff --check 和本轮文件/私钥门禁、Gitleaks 扫描结果见 verification.txt。

## 未验证与边界

本轮没有运行 Java 编译、Java 测试、MySQL、Redis、Docker、VPS/SSH 或 GitHub workflow dispatch。联合候选 69f327c7cc1673ce21c2a321e20596c13ce15abc 的 real-storage-it 由协调者另行验证，不能证明旧 manual gate 全范围通过。Backend job 总绿或路径过滤跳过也不能代替实际测试。

自动检查仅证明可信事件比较范围和现有有限静态规则；动态 SQL/业务语义仍是 UNKNOWN 与正常 PR 审查。本补丁没有扩大可自动证明的业务范围。CI 执行器、冻结 verifier、GitHub 事件和 HTTPS/Git 信任链受控是前提；候选若能改任意 CI 执行代码则须正常 code review，脚本不能自证该信任链。

没有 schema、数据迁移或 DDL。回退无数据操作，但退回 527 接线会恢复已知漏检，应阻止其作为手动全范围验收。按用户已授权例外推送隔离候选，仅供协调者 fetch/审查/实际 CI；未验证前不得正式集成、合并或部署。
