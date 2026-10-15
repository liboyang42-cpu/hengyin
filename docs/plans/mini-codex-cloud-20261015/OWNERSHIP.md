# 文件归属与候选登记

协议：`CY-COORD-20261015-v1`。**当前是待C00核实的初始规划，尚未登记正在运行的agent或实际候选，不能把下表当已领取。** 唯一维护者是将收到Codex最终提示词的C00/C99协调者。

## 当前控制信息

- 控制分支：`liboyang42-cpu/hengyin@codex/mini-bugs-backend-cloud-plan-20261015`。
- 业务候选分支/SHA：PENDING，由C00核公共PR父链及三方当前工作后填写。
- 三方实际分支/提交/交付PR：PENDING，不能拿历史快照当当前任务进度。
- 合同状态：初始BACKEND_CHANGES.md为查询快照/提案及计划；本次没有将任何条目标为CONTRACT_FROZEN、SOURCE_READY或TEST_DEPLOYED。

## 初始范围

| 范围 | 预期角色 | 当前状态 | 文件依据/限制 |
|---|---|---|---|
| 控制文档、归属表、后端变化与主账 | Codex C00/C99协调者 | PENDING_C00 | 协调者唯一写入者；各worker/Claude/iOS只交请求片段 |
| B01–B05、A01–A03源码与测试 | 对应Codex worker | PROPOSED_RESERVED | 以tasks.json的owned_files为精确预留；C00核实际工作后ASSIGNED，不代表已派人 |
| 小程序UI、新功能及专属后端 | Claude | PENDING_FILES | 接续已有授权工作，新增/冲突文件需提交归属申请；不写已锁JS/Controller/DTO/Mapper |
| 原生iOS源码、测试与文档 | 独立iOS agent | EXISTING_LANE_VERIFY | 接续该agent已授权原任务，具体分支/内部并行归属由其登记；hengyin后端不归其直接写 |
| 全局路由/动作清单、共享DTO/请求封装/fixture | 协调者统筹 | READ_ONLY_PENDING | 确需变更交最小补丁串行整合；保留原门禁，不改分母制造通过 |

## C00确认后填写的实际归属

每条至少填写：仓库、精确路径/限定新增目录、任务/角色、实际写入者、基线SHA、状态、提交/PR、转交/释放证据。状态用REQUESTED、ASSIGNED、IN_REVIEW、INTEGRATED、RELEASED；未分配不编辑，等待不自动释放。

目前无ASSIGNED记录。本轮只整理提示词与协议，不替未登记的agent伪造领取状态。独立iOS或其他agent已拥有的原任务文件不因本空表被回收，C00应先核实再协调。
