# 城瘾小程序全量审核整改收口报告

> 日期：2026-08-22（登录后动态续跑复核）
>
> 初始代码整改基线：`297802c7f`，基于 `github/master=32992766476d2a644ea5f52d3a9071368d698b04`；本报告随后继续纳入验收程序的 deadline、真实点击与导航观测修复，最终出身以 PR head 回读为准。
>
> 分支：`codex/xcx-release-stoploss-0821`
>
> 结论口径：用户已确认审核版本上架，并将本轮范围明确收窄为“只改程序 Bug”。已知代码缺陷、静态清单和受控视觉状态已收口，程序整改状态为 `CODE_FIX_COMPLETE`；2,474 个动作的逐条真实交互与对端事实仍是独立 QA/发布证据，不在本轮程序修改范围内，原因见第 6 节。

## 1. 结论

本轮已完成小程序源码、所调用后端契约、CI 门禁和 DevTools 受控状态中已发现问题的整改。空值、重复请求、并发竞态、鉴权、超时、异常恢复、敏感信息、支付/核销幂等、定位退出、上传清理、环境隔离和首页任务层级均已落到代码与负控。

不能把本地结论扩写成“线上无任何 bug”或“每个按钮已在真机真实后端通过”。用户确认审核版本已经上架属于外部状态，本轮没有重新上传、部署、改库或核验生产证书；生产证书轮换、两条 MySQL 迁移、微信合法域名/包体回读、四身份与第二账号、iOS/Android 外部能力和无障碍/性能仍需要外部系统与真实设备。

## 2. 关键整改

| 审核面 | 已落地 |
|---|---|
| 空值与会话 | 登录 payload 完整校验后原子落地；畸形 200 fail-closed；账号切换/退出同步清 session、角色与归属缓存 |
| 重复请求与并发 | 首展去重；列表 epoch/abort；搜索条件快照；写动作按业务键 single-flight；迟到响应不得覆盖新条件 |
| 权限 | 所有 method 默认鉴权，匿名必须显式 `auth:false`；GET auth/role/scope 矩阵与后端真源交叉；owner/第二账号拒绝写入契约 |
| 超时与异常 | 请求默认 12s；支付单次 5s、墙钟 20s；写动作 15s；上传单文件 15s、总 60s；统一 `success/failed/cancelled/unknown` |
| 资金与幂等 | 权益、参与人、报名与核销入口防双击；创建型写入带稳定 requestId；支付 SDK success 后仍以服务端终态为准 |
| 定位与隐私 | 后台定位 starting/running/stopping 状态机、generation、owner/waiter 去重；退出与撤回补偿 stop；拒权后可恢复原动作 |
| 敏感信息 | 支付私钥/证书移出 Git tree；CI 密钥文件门禁及负控；前端净化与后端 `AjaxResult` 非成功响应统一脱敏；微信 `request:fail` 诊断信息不再透传 |
| 配置与上传 | release 固定正式 HTTPS；develop/staging 默认 fail-closed；资产 allowlist；上传并发池、deadline、abort 与离页销毁 |
| UI/UX | 首页首屏同屏露出“附近任务”；状态组件有明确下一步；238 个可操作状态全量采集并人工复核最终红项 |

本轮继续用 DevTools 真点补出了静态门禁未覆盖的运行时问题：头像与自定义 tabBar 热区不足 44px、营销页 `cy-card` 重试语义没有穿过组件边界、首页重试继续展示旧错误、同一列表旧请求可迟到覆盖新请求。现已分别以 49×49px 头像、70×45px tabBar、组件内部 button role/label、显式 loading 和 per-list generation/abort 收口，并补独立负控。

后续真点又关闭了四类执行缺口：主页问候层会实际盖住头像（改为 `pointer-events:none`）；模板搜索热区不足 44px（补统一按钮高度）；设置页十个同类 `cy-cell` 无法唯一定位（补稳定 id）；执行器把文档坐标当视口坐标、异步页面只等一次基线、自定义组件宿主 `Element.tap()` 不进入 shadow tree。执行器现会轮询到连续稳定基线、自动滚入安全区并扣除 `page.scrollTop`、只扫描可遮挡层；原生控件使用 `Element.tap()`，六类映射 `cy-*` 组件先解析真实内部根节点，再在该节点执行 `Element.tap()` 并校验 44px、遮挡与可访问名称/role/state；只有已经解析到 shadow tree 原生节点的 `component:` 动作保留明确标注的 `dispatchEvent`。同时修复 `cy-btn` 根节点漏绑 `__onTap`（此前 `disabled/loading` 守卫是死代码），补齐 `cy-cell/avatar/card/club-card/merchant-card` 的交互语义；58 个同步顶层或显式 variant 可达、且用户选择会控制后续动作的 `wx.showModal` 动作新增原生确认/取消驱动及 `modal-cancelled` 回执，同步 helper 与 legacy `name: function` 会展开，异步失败回调、信息提示、非 tap 事件及同 handler 的其它互斥状态不会被误绑。到店互动进一步拆为答题、图片、选项、扫码、直接完成五条路径；图片路径锁定“选图上传→同一 URL 完成节点”的阶段结果、拒权/取消 `not-called` 事实与权威链接。`kick/disband` 也已按 POST 写操作和 owner 门禁归类。上述代表性导航均已在 DevTools 进入预期下一页；当时最新源码尚未提交，因此 digest 门按设计阻断且未伪造 release evidence，现已收口到代码提交 `297802c7f`。

最终复核又修复三处验收程序缺陷：`pageStack()` 永不回调时现在有硬 deadline，且协议调用超时后不再堆积不可取消的重复请求；导航动作不再用固定 500ms 截图，而是轮询到声明的精确目标路由；映射 `cy-*` 不再用无法触发 WXML `catchtap` 的 `dispatchEvent` 制造假阴性。设置→关于已用真实内部 `Element.tap()` 进入 `pages/shezhi/about/index` 并回读页面栈；设置页十项抽测中七项完成真实点击与预期状态/路由回读，另三项最初被 DevTools `access_token expired`/模拟器启动失败中断。

2026-08-22 登录后续跑改用官方 Stable 2.02.2608040。真实 AppID 最小项目可正常返回 `pages/index/index`，排除了账号/AppID 权限；完整项目的控制变量进一步定位为本机 `project.private.config.json` 中 `ignoreDevUnusedFiles=true` 会使当前 24 分包项目只启动逻辑层、不创建页面层，并连带触发 RC 的 `MaxCodeSize` 内部异常。相同源码仅在本机把该开关设为 `false` 后，`bigPackageSizeSupport=true/false` 两组均恢复页面栈；冷启动、清 compile cache 和 reset fileutils 后，权威 worktree 回读 `pageStack=["pages/index/index"]`。该文件按工程规范属于只读、不可提交的本机私有配置，因此没有把工具绕过伪装成产品程序修复，工作树已恢复原状。

解除工具阻断后，历史批次曾对当时 `app.json` 注册的 115 个页面逐页执行真实 `reLaunch`、读取 `currentPage`、页面根节点和 data：112 个直接打开；`pages/publish/topicadd/topicadd`、`pages/club/workbench/index`、`pages/coop/withdraw/records/index` 三个按源码与现有单测正确重定向到新承载页；`stamp-album` 一次协议超时独立重跑立即通过。当前源码已注册 116 页，route-matrix freshness 门会阻断旧 111 页生成物，但本轮尚未对当前唯一 SHA 重跑 116 页 DevTools 烟测。历史结果只能证明当时页面可启动与兼容壳不断链，不能外推为当前 2,474 个动作分支或真实后端/真机能力已全部执行。

## 3. 按最高标准的 UI/UX 判据

采用“微信平台规则优先、可移植人因原则其次、Apple iOS 专属实现不照搬”的顺序。好的设计必须同时满足：用户价值与主任务清楚、流程可完成且可恢复、平台交互自然、反馈明确、常用任务可无障碍完成、真实设备性能可靠、隐私权限值得信任。任何核心流程、隐私、资金、权限或恢复硬闸失败，都不能被视觉完成度抵消。

本轮改动遵守现有玩家暗色/商家浅色设计系统，没有另造视觉语言。首页把“现在能做什么”拉回首屏；loading/empty/error/unknown 继续使用共享组件；状态不只靠颜色；危险动作不与主动作同权。

## 4. 按钮、路由与状态证据

- Action Ledger：2,474 个可达动作 variant 全部入账，handler 真源可定位；动作分类为 state 1,138、navigation 420、other 262、read 258、write 211、external 185。生成 inventory 的默认 `evidence.status` 为 `pending`；动态结果先按 action/branch 写不可变片段，再持锁、临时文件 `fsync`、原子 rename 汇总到 `action-evidence.json`，并绑定受控 fixture、递归真源 digest、逐分支事实、请求时序与附件哈希。当前 overlay 为 0 条，它是执行计划与防漏门，不是 2,474 条动态通过记录。
- 静态门：U1–U5 全绿，动态/变量 URL 均有逐条理由；Action Ledger freshness gate 通过。
- 代表性行为合同：投诉选择、章节招商、局部保存、提现闸、角色门、金额、空/错/重试、返回/关闭、纯重定向壳有对应本地测试或历史 DevTools 页面栈回读；不能外推为全部 2,474 个动作均已动态执行。
- 页面启动续跑：Stable 2.02.2608040 + 真实 AppID 的历史 115 页批次已完成页面栈、根节点与 data 回读；当前注册页为 116，必须在最终唯一 SHA 重新执行，旧进程与旧证据不得复用。
- 状态矩阵：263 项；25 项明确 blocked 并绑定替代验证；238 项可操作状态全部采集，`238/238 ok`、重复 md5 `0`。
- 最终修正：B36 滚动到真实空态；B37 注入标题/时间/正文；B50 对齐现底栏 `.slopes-tabbar`；C10 按已退役兼容壳验缺参出口，不再伪造不存在的商家内容页。

截图证据目录：

`/Users/arlan/.codex/visualizations/2026/08/21/01a02358-1901-7851-8ad8-fde28447dfe3/xcx-final-7193f824f`

截图 manifest 的最终四项出身为 `5ba61490ce18442096f8e8bee303eb04ff6d7ee2`。当前整改已继续修改资料、活动、结算、游玩与漫游页面，因此这批截图仅作历史回归参考，不是当前 2,474 项 release evidence，也不能用于关闭最新源码的动态门禁。

当前工作树的代表性 DevTools 真点截图位于：

`/Users/arlan/.codex/visualizations/2026/08/21/01a02358-1901-7851-8ad8-fde28447dfe3/xcx-live-current/`

其中头像→会员页、营销 tab→营销页、AI 店铺参谋→下一页与首页重试 loading 均由当前运行产生；这些证据只关闭对应代表性问题，不扩大为全量动态验收。

## 5. 最终本地门禁

| 门禁 | 结果 |
|---|---|
| 小程序全量单测 | 3,171 tests；3,169 pass；0 fail；2 skip |
| `bash ci/xcx-check.sh` | PASS |
| Maven 全仓 `mvn -q test` | PASS |
| `lint:requestbody` | 0 error / 3 已解释动态 wrapper warn |
| `preflight:release` | exit 1；Action evidence pending 2,474，正确保持 No-Go |
| Route matrix freshness | PASS：当前 `app.json` 116 routes / 26 scenes；旧 111 页生成物负控会红，CI 已接入 `--check` |
| Action Ledger inventory/freshness | 2,474 controls 入账且真源未漂移；evidence overlay 0 条，动态状态仍为 pending |
| Action Ledger release readiness | BLOCK：pending 2,474 / nonPassed 0；不得发布 |
| DevTools 页面启动烟测 | STALE：历史 115 页批次曾通过；当前 116 页和最终唯一 SHA 尚未重跑，不得复用旧进程/旧证据 |
| DevTools 真实后端登录旅程 | BLOCK：当前 `develop + extConfig 空` 按设计 fail-closed；必须由受控测试 `extConfig` 提供 API 基址，继续禁止连接生产冒充测试环境 |
| `npm audit --omit=dev` | 0 vulnerabilities |
| Secret file gate | PASS；Git tree 无证书/私钥正文 |
| 本地 gitleaks | PASS；`github/master..HEAD` 扫描 33 commits / 约 8.68 MB；三条负控测试假阳性按 commit/file/rule/line 精确指纹登记，0 leaks |
| GitHub PR #783 代码 head | 以 GitHub `headRefOid` 对端回读为唯一真源；本文不自嵌最终 SHA，避免“写入 SHA 又产生新 SHA”的自引用假证据；ship 后必须在 PR 描述记录并核对当前 head |
| 双轴独立复核 | rebase 后最终候选相对 `github/master` 重新执行 Standards/Spec 双轴复核：两轴均 0 findings |
| Migration layout | PASS；295 个 migration 文件布局合规 |
| Migration safety | PASS；2 个本轮 SQL 可分句、依赖顺序正确、107 + 40 负控/契约绿 |
| 后端全仓 | Maven exit 0 |

## 6. 仓库内待执行证据与外部门禁

### 6.1 仓库内/受控测试环境仍待执行

| 门禁 | 当前状态 | 负责人/条件 | 建议完成时间 |
|---|---|---|---|
| 1,144 个非 external tap 动作；1,145 个非 tap 绑定走 event-contract driver → 精确 DOM/页面栈/API/服务端事实 | `LOCAL_QA_PENDING`（独立 QA，不属于本轮程序修改） | DevTools 自动化 + 受控测试后端；不得把协议派发冒充真机触摸，也不得把 input/close/change 等事件伪装按钮点击；逐分支不可变片段，写动作含重复/超时负控；全部校验 44px、视口/胶囊/遮挡与 aria role/label/state | 后续 QA 计划 |

这 2,289 条不是 external 能力，也不能因本地单测、静态清单或截图通过而关闭；其中 1,144 条是 tap，另 1,145 条是 input/close/change/retry 等事件合同。它们需要在可控环境按真实交互类型逐条执行；当前结论明确保留为仓库内未完成证据，不把执行成本外包成 `EXTERNAL_GATE`。

### 6.2 真正依赖外部系统/权限的门禁

| 门禁 | 当前状态 | 负责人/条件 | 建议完成时间 |
|---|---|---|---|
| 微信支付 API 证书与 APIv3 key 轮换 | `EXTERNAL_GATE` | 商户平台管理员；新证书只放生产机并更新环境变量 | PR 合并前 |
| 两条幂等迁移真实落库与回读 | `EXTERNAL_GATE` | DB 管理员按 `chengyin-db-migrate`；先只读预检，再人工确认执行 | 后端部署前 |
| 微信 request 合法域名与 `--info-output` 包体 | `EXTERNAL_GATE` | 小程序管理员 / DevTools 登录态 | 提体验版前 |
| anonymous/player/merchant/club + 第二账号 IDOR | `EXTERNAL_GATE` | 测试后端与真实账号 | 提体验版前 |
| 185 个微信 external 动作按能力适用分支执行并事实回读 | `EXTERNAL_GATE` | 微信 iOS + Android 物理真机；每平台/分支绑定设备、版本、受控账号、网络、截图/录屏及内容哈希 | 提审前 |
| 定位/后台定位/相机/扫码/分享/文件/支付/订阅消息 | `EXTERNAL_GATE` | 微信 iOS + Android 真机 | 提审前 |
| 读屏、200% 文本、灰度、热区、安全区、键盘/返回 | `EXTERNAL_GATE` | 双端真机与辅助功能 | 提审前 |
| P50/P90/P95 性能 | `EXTERNAL_GATE` | 按平台/机型/网络/入口采样 | 提审前 |

两条迁移的强制顺序：

1. `migration_20260821_commerce_order_idempotency.sql`
2. `migration_20260821_member_address_idempotency.sql`

生产数据库、部署和微信上传均未在本轮执行。用户确认审核版本已上架，但该外部事实不由本轮本地证据重新证明；当前结论仅是“本轮已知程序 Bug 整改完成并可进入 PR/CI”。

## 7. 发布完成定义

只有第 6 节全部有对端回读证据，包括 inventory 每条通过不可变分支片段原子汇总为带源码 SHA、当前递归 WXML/JS/WXSS/JSON digest、受控账号回读、时间、分支、事实回读与附件哈希的动态结果，且生产/体验版 SHA、账号、设备、系统、微信版本、网络条件、预期、实际、截图/录屏与服务端事实都记录完整，才可把状态从 `No-Go` 改为 `Go`。本地 exit 0、截图、toast、HTTP 200 或 workflow 绿都不能单独替代该结论。
