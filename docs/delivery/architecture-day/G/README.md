# G：结算与完成事实合同核对

结论：**已增加独立只读批量路线完成事实查询和 14 项 Java 行为测试源码；Java/MySQL 未运行，未证明结算运行通过。** 不改 C Controller、现有资金入口或经济资格。无票探索 fallback 的测试按基线源码预计有 1 项红，需 C 修复后转绿；不能作为可直接上线的全绿候选。业务问题已从六项收敛为一个具体多人票计量矛盾，其余按既有规则处理。

## 基线、隔离和候选身份

| 项 | 实际记录 |
| --- | --- |
| 仓库 | https://github.com/liboyang42-cpu/hengyin.git |
| 参考/实际 HEAD | `2ec68899099a1e250df0949a8383bf85b5d3ffe0`，一致，无额外差异需套用 |
| 原 checkout | `/workspace/hengyin`，分支 `work`，开工时干净；未切换其分支 |
| G 独立工作树 | `/workspace/hengyin-g-fact-contracts` |
| G 分支 | `codex/architecture-day-g-fact-contracts`，从上述公共仓库基线建立 |
| 独占写入 | G 交付目录及 `implementation.md` 列出的 7 个新增 Java/XML 文件；无既有业务文件、根 pom、CI 或迁移变更 |
| 检查时间 | 2026-10-23；精确 UTC 时间、候选内容哈希见 `validation-output.txt` |
| 候选 | 新只读查询、4 个新测试类和本目录交付；内容哈希见 `artifact-manifest.json` |

可审阅 diff：`git -C /workspace/hengyin-g-fact-contracts diff 2ec68899099a1e250df0949a8383bf85b5d3ffe0 HEAD`（提交前用 `--cached`）。用户已额外批准**仅供 CI 的隔离候选提交/推送**；该例外不表示测试通过，不允许正式集成/合并部署。完整候选 SHA 由最终交付提供，补丁另存 `/tmp/architecture-day-G-fact-contracts.patch`。文件清单以 manifest 加自身和执行日志为准。

未迁入其他仓库历史、日志、备份或秘密；未创建新 Agent/会话，未访问生产、支付或微信接口，未执行 DDL 或部署。只按用户新增授权推送独立 codex CI 候选分支；不推 master。任务指定 codex 分支及“CI 隔离候选提交/推送”例外覆盖根 AGENTS 的一般分支/提交前测试要求，不放宽正式集成门槛。

## 交付与执行状态

- [第二轮实现、C 接入合同和 Java 测试执行入口](implementation.md)：源码完成/预计红项/未运行检查分别列出。
- [既有批准合同复核与唯一剩余业务问题](approved-contract-review.md)：D1–D6 收敛，D2 明确为技术修复，不要求用户重选经济 context。
- [结算合同、订单矩阵和产品决定](settlement-contract.md)：当前支持范围与可实施边界。
- [五类完成事实、逐指标期望集合](completion-contract.md)：actor/team、实例、版本、证据、消费者及风险分级。
- [受控环境夹具与测试计划](acceptance-fixtures.md)：失败机制先于未来补丁；包括事务、并发、退款、回放与外部 UNKNOWN。
- `fixtures.json`、`verify_contract.py`：可执行的合成集合/金额验收规格；仅标准库 Python，不是业务实现或数据库测试。
- `source-evidence.json`：每项关键结论的实际基线文件、行号、摘录及源文件哈希。
- `caller-inventory.txt`：在后端全部 main/test 与小程序/管理端源码搜索的当前调用者清单；包含命令与输出，不以旧文档或源码“生产数据”注释推断真实数据现状。
- `validation-output.txt`、`artifact-manifest.json`：实际执行输出和可比对内容身份。

**实际执行：** 仓库/HEAD/工作树检查、全库调用者搜索、关键 Java/XML/既有迁移与已批准合同静态阅读、Python 合成规格及静态文件检查、交付路径隔离检查和 `git diff --check`。新增 Java 行为测试实际执行数为 0；源码证据注明基线或候选来源。

**明确未执行：** Maven 编译、任何 Java 单测、MySQL/Redis、Spring 事务回滚/锁竞争、迁移预检、VPS 或 GitHub-hosted CI、真实支付/退款/结算链路。没有使用 H2/Mock 来宣称 MySQL 通过。当前任务未提供可用受控执行入口，留给集成人统一安排；未在云 VM/Mac 自建后端或数据库。仅因用户明确批准的 CI 候选例外提交/推送，未验证不得正式集成/合并部署。

## 集成边界和上线门槛

1. 新 reader/mapper 尚未由 C 接入；危险 fallback 测试预计红、所有 Java 检查未跑，不能据此解除任何 fail-closed。其余会话改共享入口后按实际新 HEAD 重新核对。
2. G 已实施不依赖 progress 的个人最新完成局批量查询与对应测试源码；不写进度、不发奖、不改变核销/结算资格，未创建 HTTP 接口。供 C 在原权限/可读性规则下合并来源。
3. 旧 D1–D6 以 approved-contract-review 的复核为准；保留 Q1 多人有人带票的可证履约单位问题，其余是既有规则的技术实现/验证阻塞，不重新猜比例、收款人或 context。
4. 任何“完成率/漏算比例”必须用经批准的只读数据快照按集合差验证。本轮没有数据库行数、占比或财务运行结论。

## 迁移和回退

本次无 schema 变更、无迁移执行，不要求数据回填；新只读 mapper 依赖既有路线迁移。MySQL IT 仅准备获批环境的 UUID 一次性夹具库，尚未执行。字段/唯一键存在于仓库不证明某环境已安装；集成人需受控预检。如果未来需新冻结字段或 scope 关系，另交最小 DDL/预检/迁移/回读/回退方案，未经授权不执行。

未接线前可以独立移除新查询，接线后先回退调用方再移除 bean/mapper，不回滚业务数据。资金补丁不得删 claim、明细、回执、退款/审计或清库回退；已生效经济事件用同一业务身份追加调整。历史投影回填不调用 advanceProgress、grantOnce、核销或结算写入口。
