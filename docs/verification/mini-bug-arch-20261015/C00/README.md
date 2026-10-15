# C00 公共候选与所有权证据

共同冻结源码：`d551c6babd018d41aec90dad2c0acaf1c534644d`；候选分支 `codex/mini-bug-arch-integration-20261015`；草稿 PR #18。只在候选中组合公共提交，未合并任一 GitHub PR。

## 父链与复用

公共 master 为 `8b7eb814e3db00aa2543fee489fad44f9aa7aec3`。PR11 不是 master 后继：共同祖先为公共快照 `a20fb9b602008ffbdac8bb95f792289626b008f1`。PR11 父链包含 `fafde98 → 0984189 → 1a87263 → 55cd557 → 7046676 → 9c5d903 → 806abb8 → 0f0c32f → e67e72a → 231f201 → 67bfe34 → 0b301c9`。已保留 master 自身修复与 PR16 五份任务文档，没有取旧私有历史或工作区 WIP。

复核时 PR11–15 均 OPEN/DRAFT/未合并，head 分别 `0b301c9`、`a5af41f`、`d6cd231`、`6a96477`、`8884a67`。PR11 base 为交接分支，PR12–15 base 为 PR11 分支。`fetch_commit_workflow_runs` 查询只返回 PR 触发记录，对这些 SHA 均为空；它不涵盖历史 workflow_dispatch，不能据此否认或确认作者所报手动 CI。

现有 ci.yml 的 pull_request 分支仅 master、feature/mini-unify-1003、feature/popup-ship-1005，push 仅 master，另支持 workflow_dispatch。这解释子 PR 无自动 CI。PR18 向 master 提草稿以使用既有托管 CI；未改工作流。

PR12 GPS accuracy >80m 拒绝，与 PR13 客户端阈值匹配；旧端缺 accuracy 仍兼容，50m 围栏保留。显式点赞方向与平台/本人俱乐部徽章查询修复复用。PR13 音频/退避工具复用，发现后台音频恢复及晚到轮询重启缺口，交 B01。

## 合并冲突审查

PR11 与 master 的 action-ledger 测试冲突：保留 PR11 一次构建后独立 clone 夹具，并保留 master 用空树 commit 做历史缺源码负控，不创建易被并行扫描的临时产品目录。

PR13/14 的 UI baseline 冲突只有等量行号位移：组合两侧各自源码锚点，理由和 ratchet 条数不变，digest 用门禁原算法按 key 重算。PR15 与 PR14 的 GET 数量组合为 33（公共29 + 认领1 + 路线/状态键3）。

动作清单由集成人集中运行原生成器：`ACTION-LEDGER WRITE PASS: 4480 controls`。公共4432 + PR14实际新增23 + PR15净增25 =4480；local tap=1796，event-contract=2348。只读审查对比实质字段：PR13无新增删除；PR14 +23/-0；PR15 +28/-3，其中3项替换为集邮册增加 received 请求。组合计数是实际控件集合并集，不删业务规则、不缩减分母。

## 四条并行线路

所有 worker 已收到同一 SHA，并被告知共享工作区、不得覆盖他人、不得切分支/提交、不跑全量或 Java/DB、不得改公共基线。

| lane | 独占范围 | 追加许可 |
|---|---|---|
| B01 | tasks.json B01 owned_files 与专属测试 | utils/audio-session.js、utils/poller.js |
| B02 | tasks.json B02 owned_files 与专属测试 | CmsTopicServiceImpl.rebuildTopicContent 及仅保真辅助方法 |
| B03 | tasks.json B03 owned_files 与专属测试 | UmsMemberMapper.java/xml 的 selectPointsForUpdate |
| A02 | tasks.json A02 owned_files 与专属测试 | 无 |

根/小程序AGENTS、公共DTO、请求封装、动作与路由清单、基线、workflow/pom/lockfile/sql 由集成人独占且默认只读。B04/B05 在有空位后领取。Bug证据处置前不启动 A01/A03 或 A02 架构后半。

## 未验证与限制

环境 readiness：running/connected，无 secret/runtime variable/outbound identity；network-policy.json 的 vpn_configured=false、TCP domains/IP均空，未配置 VPS 路径。没有连接生产、没有在 Cloud 自建 Java/MySQL/Redis，没有把 localhost 当 Mac 隧道。Cloud只运行必要 Node 检查，后端交既有 GitHub CI，VPS/真机层保持未验证。

PR14 退款授权缺口交 B01；clubInterest/roamRecall 空模板维持未配置。认领 PHOTO+URL 只有 DTO 非空形状可接收，资产存在/归属/审核材料消费无完整fixture，保持未验收。PR15 官方路线只读、收到邮票 POST、积分明细 POST且全量、状态键仅 run作用域，均不扩展。原迁移随父链保留，本批不新增/执行DDL，不改资金/鉴权政策，不部署、不上传小程序。
