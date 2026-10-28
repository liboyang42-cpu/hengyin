# F：locking SELECT 缓存属性增量约束

结论：已在现有 `gate.py` 实现最小技术规则；不改业务 XML、全局 MyBatis 配置、producer、workflow、依赖或 job。新规则只阻断本次新增/修改且确定含 `FOR UPDATE` 的静态 SELECT 缺少任一明确属性：`useCache="false"`、`flushCache="true"`。UNKNOWN 只列审阅项。代码完成不代表 Java/MySQL fresh-read 已验证。

## 身份、隔离与范围

- 实际起点：公共仓库 `liboyang42-cpu/hengyin` 的 `2631846bd618df7afe6b341351ea694c712bc40b`；任务分支 `codex/architecture-day-f-locking-select`，工作目录 `/workspace/hengyin-f-locking-select`。
- 不可变旧债来源：`2ec68899099a1e250df0949a8383bf85b5d3ffe0`。原 `baseline.json` 字节保持不变。
- 685 producer 的独立工作树/分支保持冻结。未修改任何 C/D/E/A 业务 XML、root registry/report harness、CI 接线或生产工作流。
- 改动仅 `ci/architecture/gate.py`、`test_gate.py` 的 fixture 注入、`test_locking_select.py`、`locking-select-baseline.proposal.json` 及本 F 交付目录。没有数据库/迁移影响。

## 失败机制与最小修复合同

协调者已有两次真实故障证据：C 锁后 cached-null，以及 E 在空同步跨 REQUIRES_NEW 后读到旧 PREPARED。本任务没有重跑这些业务用例。

`useCache=false` 只关闭该 SELECT 的二级缓存使用，不能单独证明一级会话缓存清除；`flushCache=true` 与其配合是本次明确技术合同。即使属性齐全，RR 快照、current-read SQL、事务传播、读写路由及真实调度仍需业务/真库验证；本规则不宣称完整 fresh-read、锁范围、SQL 正确性或事务原子性。

调用链沿用现有 `source → check_source → inventory/sql_details → static_findings`。XML 与 Git blob 读取沿用原 helper；SQL 注释/引号处理复用仓库唯一 `ci/sql_splitter.py`，不另写 lexer/SQL 框架。SELECT 的额外观察放在 `sql_details`，不改变原 inventory/schema/精确旧 SQL baseline。

可确定的窄范围：无 XML 子节点、无 `${}`、单条以 SELECT 开始的语句，去引号/注释语法视图中含终结的 `FOR UPDATE`，可带 NOWAIT/SKIP LOCKED。两项属性须为明确字面值；缺失或其它值产生 `LOCKING_SELECT_CACHE` finding。注释/字符串中的 FOR UPDATE 不算锁。

include/if/choose/foreach 等子节点、文本替换、可执行 SQL 注释、未闭合字符串、多语句、CTE/不支持的锁后缀等输出 `UNKNOWN_LOCKING_SELECT`。原有 mapper 文件/动态 include 审阅项继续保留。没有从 include 展开结果推断可自动放行。

仅对可信 CI compare-base 到 candidate 的 statement 增量执行阻断；原有 PR merge-base/manual 默认主干 merge-base 校验不变。只改同一 mapper 的其它语句不重报旧锁缺口。比较基点已删除/修复的缺口，后来按原 namespace/id/body 重引仍红；本规则不检查未进入比较基点的中间提交历史。

## 旧债提案，未自动批准

`locking-select-baseline.proposal.json` 是 **93 条** 2ec 可确定静态锁 SELECT 缺属性的提案，状态为 `PROPOSAL_NOT_APPROVED_NOT_AN_ALLOWLIST`，请 root 回读审定。没有把 263 的新增缺口纳入其中，也不根据该表豁免新增/修改语句。

每项保留精确 path#id、namespace、id、body_sha256、statement_sha256 及原缓存属性。fingerprint 使用标准库 XML C14N 规范化属性顺序/换行；保留 SQL 空白及字面量差异，不将字符串内空白折叠。排除父节点外 tail，避免相邻语句的布局混入 body。

`check_source` 每次从不可变公共 2ec blob 重算并精确比较提案，同时沿用 trusted-policy 字节保护；修改 trusted 提案或由 candidate 变更提案均不能自接受。既有 `baseline.json` 仍按原方式重算。普通未改旧债不阻断 PR，与提案是否被批准无关；没有为新增缺口建立例外通道。

## 263 待协调的业务 XML 清单

完整静态盘点在 `candidate-263.json`：96 条确定缺属性（其中 91 条与公共 2ec 完全未变）、34 条静态锁两项齐全、135 条潜在锁 UNKNOWN。它是本地静态观察，不是可信 CI gate 或 MySQL 证明；潜在锁 UNKNOWN 清单使用文本提示筛选，不保证覆盖所有运行时生成的锁查询。

以下 **5 条** 相对公共 2ec 新增/修改且确定缺两项属性，root 可协调原业务负责人补齐；F 未代改：

| 文件（均在 system/src/main/resources/mapper/business） | statement id（263 行号） |
| --- | --- |
| ClubLeadMapper.xml | selectByActivityForUpdate（30）、lockClubOwner（35） |
| CoopSettlementEvidenceMapper.xml | selectCashBasisBlockingRefundIdsForUpdate（94） |
| PhotoCheckIntentMapper.xml | selectByKeyForUpdate（42）、selectForUpdate（51） |

特别提示：`TopicRouteSessionMapper.xml#selectAccessibleByIdForUpdate`（263:67）缺两项属性，但带 include，本规则按要求输出 UNKNOWN，不伪装成已证明违规/安全；请 C/root 人审并结合已有 cached-null 真库用例处理。`ImNotificationIntentMapper` 的两个锁查询和 `MemberCashWithdrawMapper#selectByIdForUpdate` 虽已有两项属性，也因 include 保持 UNKNOWN，不宣称全 SQL 证明。

`delta-2ec-to-263.json` 复用原 static_findings 做显式公共 2ec→263 本地诊断：全部确定 finding 仅上述 5 条，增量 UNKNOWN_LOCKING_SELECT 为 2 条。未伪造 GitHub 事件去宣称它是实际 CI source 验收结果。

## 实际验证与限制

实际执行，日志随本目录交付：

```sh
python3 -m unittest discover -s ci/architecture -p 'test_*.py' -v
GATE=ci/assert_it_ran.py python3 -m unittest discover -s ci -p test_assert_it_ran.py -v
python3 ci/test_ci_cost_contract.py
git diff --check
```

- 架构自测 **94/94 OK**：原 75 项（含冻结 producer 的 18 项合成协议测试）+ 新 19 项；没有将 producer 合成输入称为真实 jdeps。
- 新负控包括只关 L2、flush-only、缺/错误属性、普通旧锁未改、同 mapper 其它语句变更、旧债修改、删除/修复后重引、改 namespace/id、提案漂移与 candidate 篡改；实际 source CLI fixture 缺属性 exit 1、两项齐全 exit 0。
- 原 assert_it_ran **5/5 OK**；CI cost contract PASS；旧 SQL baseline 与新提案都从 2ec 精确重算 PASS；diff 检查及秘密扫描见 verification.json。
- 先写负控后修复：初始 17 项运行出现 22 个失败 subcase、2 个缺 helper 错误，保留 before-selftests.txt；新增 CLI 自测首次暴露 fixture 没注入 import 时绑定的 baseline 默认路径，已仅修 fixture。一次 assert_it_ran 调用未提供脚本要求的 GATE 环境变量导致导入失败，按原合同重跑 5/5 通过，未改该工具。
- **未运行 Java 编译/单测、MySQL、Redis、真实 jdeps、业务集成链路或 GitHub CI；未触发任何工作流。** 用户已批准隔离 CI 候选提交例外；本候选未获得正式合并/部署验收。

## 集成与回退

root 审阅提案/代码后，在自己的接线职责范围将既有 architecture verifier ref 重新固定到本任务冻结提交完整 SHA，并将相同 gate/proposal 文件纳入联合 candidate；原 workflow 不需要新增 job、依赖、测试矩阵或并行预算。仍需在真实 GitHub 事件上下文运行现有 source 命令，不能拿本地诊断替代。

本任务只新增 Python 技术检查；回退应同时恢复 gate、proposal 的可信 pin/候选文件一致性。单独删 proposal 或只切候选 gate 会被保护机制拒绝。不能为使联合候选通过而删 finding、扩大旧债 baseline 或把 UNKNOWN 改成证明。
