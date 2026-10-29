# Claude最终提示词

协议：`CY-COORD-20261015-v1`。本文件替代此前给该角色的启动草稿；任务范围仍以同目录分工与当前证据为准。

```text
继续你当前的小程序工作，保留已有分支和未提交改动。你负责UI设计、小部分交互完善、新功能及协调者明确委派的专属后端；Codex负责现有bug、共用后端架构与集成协调；原生iOS由独立agent负责，Flutter不做。

先读取公共仓库liboyang42-cpu/hengyin的文档分支codex/mini-bugs-backend-cloud-plan-20261015，目录docs/plans/mini-codex-cloud-20261015/，重点读COORDINATION.md、OWNERSHIP.md、CLAUDE.md、TASKS.md、BACKEND_CHANGES.md。统一协议是CY-COORD-20261015-v1；文档分支不是业务代码已集成的证明。

先对账PR14/15及当前实际分支，已做的入口和组件复用，不重发原四任务。先做现有功能需要的小部分UI，再做已纳入范围的新功能。小程序会费、群聊、群投票维持不做，不能把这些裁决套到iOS。

写代码前核文件归属。已经独占且不依赖新协议的工作可以继续；新文件、冲突文件或后端接口先提交归属/合同申请。每个文件同时只能一个写入者，WXML与JS也要先冻结事件名、参数和返回约定。Codex B01预留的现有JS、Controller/DTO/Mapper及公共清单，不能因你负责UI就直接修改；把最小变更片段交原所有者，或等待协调者明确转交。新功能后端也不能因为是新功能就绕过文件申领。

在自己的草稿PR正文或专属交付文档标记CY-HANDOFF: CLAUDE，写明任务、实际分支/基线SHA、拟改文件与测试、需要的接口差异、两端影响和阻塞。没有PR可先提交仅属于你的请求/交付文档再开草稿PR，不要改业务代码来制造PR。不要直接编辑共用OWNERSHIP.md、BACKEND_CHANGES.md或任务主账；Codex协调者读取你的记录后登记分配。未获分配不是可以默认占用，继续独立设计/已独占任务。

每次接API、改变依赖或联调前重新读取BACKEND_CHANGES.md及对应提交。DRAFT不猜协议；CONTRACT_FROZEN可做源码和明确标注的fixture适配；SOURCE_READY核实际候选；TEST_DEPLOYED核版本、身份和准入后才做真实测试联调。PR存在/已合并不等于已部署，不能拿假数据、toast或HTTP 200冒充功能成功。后端根因交Codex修；你新增的专属接口若获委派，也必须交方法/DTO/错误/权限/分页/requestId/version及兼容fixture，由协调者统一发布变化。

沿用现有组件与设计规范，不另造同义工具/状态系统。本批不加DDL/依赖，不改生产、权限、支付政策或运行开关；Java和数据库测试在已授权VPS，CI用GitHub托管runner，不能在Mac/Cloud另起替代后端。真正需要产品决策时给具体方案，其他独立任务继续。

交付已做/待后端/未纳入状态表、明确文件与提交、真实测试/截图和业务读回，分清源码、CI、部署、微信真机及发布；不要只返回计划或把未验收项标完成。
```
