# CY-FORWARD3-20261015 交付与恢复

真实任务：`01a140c1-6379-71fd-8711-f0b55889c01b`。工作分支：`codex/mini-forward-source-20261015`。
业务基线：`0d63043ad06775bc3dc52fb925620ef36d1081a7`；计划父：`9f96f7a858866dd2a09cd244f90c6f0ecbac5e88`，仅四计划文档差异。

启动已实际读取四计划文档、根 AGENTS 与最新 LONGRUN_CURRENT / LONGRUN_ROLLING / LONGRUN_SOURCE_OWNERS。
控制分支读回 `a77eaf5edf3ed19a6271bf2b50101817845b6462`，六条既有路径保留给第三队；刷新 56 个开放 PR 全分页文件列表，无交集。原工作树干净。
[PR16 启动 ACK](https://github.com/liboyang42-cpu/hengyin/pull/16#issuecomment-6199048354) 已包含真实 child 与精确 claim。

STAT 唯一写者 `/root/stat`；GROW 唯一写者 `/root/grow`。父协调者唯一维护本目录三文档。
只在现有授权内源码修复、回归编写和 Git 交付。验证全部 `NOT_RUN`；不编译、测试、lint、业务 HTTP、数据库、设备、手动 CI、VPS、合并或部署，不改门禁/hook。用户当前分工覆盖根 AGENTS 的默认测试及 master 分支要求。

## 当前进度

- F3-STAT-01：源码审计后修复中；保留空值/单边与响应、pilot 旧合同。
- F3-GROW-01：调用者、事务锁及唯一约束核实中；有反证不造缺陷，不新增奖励/幂等身份政策。
- 公共源码交付 SHA：尚无；完成一项即按精确路径提交、push 草稿 PR。

## 恢复

先读本目录 QUEUE/CLAIMS/DELIVERY 与 PR16 最新反馈，核远端分支和工作树，不能覆盖 WIP。保留两 lane 单写者；本地失败仅一条原作者修复线。范围完成后交接候选，不能自行扩域。公共源码交付均标 `SOURCE_DELIVERED_UNVERIFIED`，待本地总控集中验证。
