# 城瘾小程序功能、流程、按钮与全部事件绑定审查报告

> **历史审计快照，不是当前发布分母。** 本文固定在下述 `f6b126e4` 基线，保留当时对 116 页、36 个流程族、14 个不可达 WXML 和开放 PR 重叠关系的逐项审查。此后 #788/#789/#779/#782/#787 已合并，#783 已重新生成当前 route/action 真源；当前发布判断必须使用 #783 的 `2026-08-21-chengyin-miniprogram-remediation-closure.md`，不能把本文的 2,416/2,464 与新 SHA 拼接。

> 审查日期：2026-08-22
>
> 审计基线：`github/master=f6b126e4c3cf77e0a5c1dde2483265ca81cfde62`
>
> 审查对象：`chengyinhub-xcx/` 当前主线；同时交叉全部 7 个开放 PR。
>
> 结论规则：静态门绿只证明“代码可解析、绑定存在、路由/API 有合同”，不等于真实点击、真实后端、真机能力和资金/权限终态已通过。

## 一、结论

**当前不能判定“小程序每一个功能都通、每一个按钮反馈都正确、36 条流程全部完整”。结论为 `NO-GO / EVIDENCE_INCOMPLETE`。**

- 116 个注册页面级功能：静态源码与 handler 门均通过；但只有静态合同，不足以证明 116/116 端到端。
- 36 个产品流程族：已覆盖全部 116 个注册页面；0/36 拥有当前主线、真实后端/真机、全分支的完整动态闭环证据。
- 当前主线有 1 条已确定功能故障：商家合作中心“可申请路线”接口成功后抛 `ReferenceError: chinaDayStart is not defined`，页面永久 loading。该缺陷已由开放 PR #788 和 #783 覆盖，本次不重复修改。
- 2,416 条是事件绑定，不是按钮：其中 2,412 条进入可操作 Action Ledger，展开为 2,464 个互斥动作分支；另 4 条属于仅声明、未实例化的 `cy-npc-chat`。2,464 个分支的当前动态证据均为 `pending`。
- 明确按钮只有 237 个（`<button>` 54 + `<cy-btn>` 183）；可点击节点为 1,254 个；其余绑定包含输入、滚动、关闭、重试、焦点、拖动、自定义事件等。
- 14 个 WXML 是从注册页面/组件依赖图完全不可达的资产；此外还有 1 个“JSON 已声明但 WXML 没实例化”的 `cy-npc-chat`，必须与 14 个完全不可达资产分开。

## 二、关键数字与口径

| 指标 | 数量 | 审查结论 |
|---|---:|---|
| 注册页面级功能 | 116 | 静态存在；动态完整通过 0 |
| 产品流程族 | 36 | 覆盖全部 116 个页面；完整动态闭环 0 |
| 明确按钮标签 | 237 | 54 个原生 `button` + 183 个 `cy-btn` |
| 可点击节点 | 1,254 | 点击目标，不含 input/scroll/close 等事件 |
| 图可达事件绑定 | 2416 | 全部静态 handler 门通过；动态结论不可由静态外推 |
| 严格可操作原始绑定 | 2412 | 排除仅声明未实例化的 4 条 `cy-npc-chat` |
| Action Ledger 动作分支 | 2464 | external 143；navigation 423；other 263；read 252；state 1171；write 212 |
| 交互模式 | 2464 | tap 1304；event-contract 1160 |
| 真机/外部能力动作分支 | 143 | 相机、定位、扫码、手机号、支付等不能由模拟器冒充 |
| 完全不可达 WXML | 14 | 不计入当前用户可操作分母 |
| 声明但未实例化组件 | 1 | `components/cy/npc-chat/index.wxml`，4 条绑定 |
| 开放 PR | 7 | 4 个可合并、3 个冲突；多处交叠，必须避免重复改 |

## 三、审查方法与证据等级

1. 固定 `github/master` SHA，读取 `app.json`、所有生产 WXML/JS/JSON 与组件依赖。
2. 对每条 WXML 事件做 handler 存在性、JS 作用域、路由、API method/body、错误反馈合同和可见状态门禁。
3. 用带负控的门禁证明：伪造不存在 handler/API/可见反馈会判红，恢复后才判绿。
4. 用 #783 的 Action Ledger 生成器覆盖当前 master（只复制审计工具，不复制产品修复），给每条动作建立 effect/readback/分支计划。
5. 查询全部开放 PR，按文件与语义两层标记“已被 PR 覆盖”，不创建重复修复。
6. 动态证据按四级解释：`STATIC_PASS` < `DEVTOOLS_STARTUP_PASS` < `CONTROLLED_BACKEND_PASS` < `REAL_DEVICE/PRODUCTION_READBACK_PASS`。前一级不能替代后一级。

## 四、已经执行的验证

| 验证 | 当前主线结果 | 能证明什么 | 不能证明什么 |
|---|---|---|---|
| `npm run test:unit` | 2,736 tests；2,734 pass；0 fail；2 skip | 单元/合同回归 | 真实点击、生产数据、真机能力 |
| `bash ci/xcx-check.sh` | PASS | 路由、绑定、API、UI 负控与静态合同 | Action 分支真实执行 |
| U2 handler 门 | 2,439 纯标识符 + 6 动态；0 新死绑定 | 绑定名在 JS/behavior 存在 | handler 内部业务一定正确 |
| UI-GATE U3/U4 | U3 100/100、U4 195/195 存量登记；65 动态 setData 登记 | 无新增债务 | 已登记存量不是“已修复” |
| API 合同 | U1 PASS；动态 URL 18、变量 URL 13 均有理由 | 静态路由存在 | 线上接口、权限、数据状态真实可用 |
| `lint:requestbody` | 0 error / 5 warn | 明确 body 契约无硬错 | 5 个动态 method/url 仍需人工/动态确认 |
| `preflight:release` | exit 0，0 blocker | 旧发布自检项通过 | master 尚未接 Action Ledger，故不能用它证明 2,464 动作可发布 |
| #788 定向负控 | master 2 fail / PR #788 3 pass | 确认合作中心故障与 PR 修复有效 | 其它合作流程 |
| 当前 master 页面层枚举 | 116/116 出现页面栈和根节点；114 直达、2 重定向 | 只能证明页面层/路由清单可枚举 | `getApp()` 为 undefined、Page 未注册，整轮动态结果拒绝采信 |

### 当前 DevTools 说明

独立临时副本仅把本机私有选项 `ignoreDevUnusedFiles` 设为 `false`，并用 `open-other` 明确打开该项目；随后只清 compile cache、执行 `reset-fileutils`，未清 storage/auth。页面层能枚举 116/116 条路由，但 App 真源持续回读 `getApp()=undefined`，控制台为 `Page "pages/index/index" has not been registered yet` 与 `Component is not found in path "wx://not-found"`。因此 116 页结果按假绿作废，归类为 `DEVTOOLS_APP_LAYER_BLOCKED`，不判产品通过或失败。

## 五、确定发现与处置边界

### P1：主线合作中心成功分支永久 loading

- 位置：`pages/merchant/coop-center/index.js:40`。
- 证据：主线注入 #788 回归用例后，成功分支 2 条用例均以 `ReferenceError: chinaDayStart is not defined` 失败；失败分支反而通过。
- 用户影响：商家看不到、也无法申请“可申请路线”。
- PR 状态：#788 与 #783 均已删除该死代码并带回归测试；不应再创建第三份修复。
- 当前状态：`BROKEN_ON_MASTER / FIXED_IN_OPEN_PR / NOT_MERGED`。

### P2：2,416 口径包含 4 条声明可达但运行不可达事件

- `pages/roam/index.json` 声明 `cy-npc-chat`，但当前任何生产 WXML 都没有 `<cy-npc-chat>` 实例，也没有入口 handler。
- 组件本身有 close/input/confirm/tap 共 4 条绑定；因此依赖声明图计 2,416，实际可操作 Action Ledger 只接入 2,412 条原始绑定。
- 后端 `ApiConfigController` 注释明确 Chat V1 固定为 false；当前更适合归类为关闭功能/遗留资产，不应当成在线按钮。

### P2：路由矩阵真源文件已陈旧

- `scripts/uiaudit/route-matrix.json` 只有 111 条，缺少 5 个已注册页面：`pages/team/detail/index`、`pages/team/join/index`、`pages/play/celebrate/index`、`pages/club/edition-report/index`、`pages/merchant/marketing/ai-insight/index`。
- 现有 screenshot coverage gate 直接读取 app.json，仍报告 116 = 112 覆盖 + 4 显式豁免，因此没有造成当前截图覆盖漏报；但该 JSON 自称“从 app.json 生成的真源”，应更新生成/新鲜度闸。
- 本轮只审查、不修改，且没有开放 PR 专门覆盖这 5 条新鲜度缺口。

### 发布证据缺口：不是代码 bug，但阻止“全部通”结论

- 当前 master 的 2464 个动作分支全部为 `pending`；#783 自己的动态 overlay 也为 0。
- 本地受控环境仍需逐条执行 tap 与 event-contract；写操作必须含成功、失败、取消、超时、重复提交与权威回读。
- 真机/外部能力需 iOS/Android、第二账号、定位、相机、扫码、getPhoneNumber、支付、合法域名与后台权限。
- 因此“门禁绿”不能改写成“每个按钮反馈正确”。

## 六、7 个开放 PR 去重表

> “绑定交叠”只表示 PR 修改了该绑定所在 WXML/JS，不等于每条绑定都被该 PR 修复。

| PR | 标题 | GitHub 状态 | Checks | 小程序文件 | 页面交叠 | 绑定交叠 | 与 #783 重叠文件 | 审查结论 |
|---:|---|---|---:|---:|---:|---:|---:|---|
| [#789](https://github.com/liboyang42-cpu/chengyin/pull/789) | fix(home): 修正首页 Feed 卡片与主题字段 | MERGEABLE | 11 success / 3 skipped | 6 | 1 | 19 | 3 | 首页 Feed 卡片、主题字段与视觉；F06。 与 #783 重叠首页 3 文件，内容不同，需合并后复检。 |
| [#788](https://github.com/liboyang42-cpu/chengyin/pull/788) | fix(xcx): 商家合作中心「可申请路线」接口成功时永远转圈 —— 删掉必崩的死代码行 | MERGEABLE | 8 success / 6 skipped | 2 | 1 | 18 | 2 | 商家合作中心成功分支永久 loading；同一缺陷也已进入 #783。 缺陷语义已被 #783 覆盖；不要再做第三份修复。 |
| [#787](https://github.com/liboyang42-cpu/chengyin/pull/787) | feat(xcx): 系统深色模式 —— 商家域跟随系统变深,玩家域恒深不受影响 | MERGEABLE | 8 success / 6 skipped | 81 | 31 | 1385 | 34 | 系统深色模式，跨页面视觉状态；不等于业务流程修复。 与 #783 重叠 34 文件；当前 GitHub 显示可合并，但合入顺序后必须复检。 |
| [#783](https://github.com/liboyang42-cpu/chengyin/pull/783) | fix: 小程序全量可靠性安全审计整改 | MERGEABLE | 14 success / 0 skipped | 296 | 57 | 1817 | — | 全量可靠性/安全整改、Action Ledger 与大量反馈/流程合同；动态 overlay 仍为 0。 审计主整改 PR；同时吸收 #788 的合作中心缺陷修复。 |
| [#782](https://github.com/liboyang42-cpu/chengyin/pull/782) | fix(xcx): 交互反馈补全 —— 弹窗退场动画 / 按压态 94 处 / 金额滚动 / 骨架屏淡切 / 触感收口 | CONFLICTING | 8 success / 6 skipped | 85 | 38 | 1001 | 22 | 弹窗退场、94 处按压、金额/骨架/触感反馈；与 #783 多文件冲突。 与 #783 重叠 22 文件，且当前 GitHub 已标冲突。 |
| [#780](https://github.com/liboyang42-cpu/chengyin/pull/780) | feat: 落地 Questify 五个圈层自由探索主题 | CONFLICTING | 11 success / 3 skipped | 27 | 3 | 263 | 11 | Questify 五圈层自由探索主题、模板与配置；新增功能，不是本轮审计缺陷修复。 与 #783 重叠 11 文件，且当前 GitHub 已标冲突。 |
| [#779](https://github.com/liboyang42-cpu/chengyin/pull/779) | feat(xcx): 玩法端 P1 前端接线 —— 锁定态/角色卡/同行者榜 + AI 助写整表填充 | CONFLICTING | 8 success / 6 skipped | 6 | 2 | 271 | 6 | 玩法 P1：锁定态、角色卡、同行榜、AI 整表填充；与 #783/780 重叠。 与 #783 重叠 6 文件，且当前 GitHub 已标冲突。 |

### PR 去重结论

- #788 的确定故障修复已被 #783 同时覆盖；不要重复改。
- #782 的反馈补全与 #783 重叠 22 文件，且 GitHub 已标 `CONFLICTING`；不能把 #782 的 94 处反馈直接视为当前 master 已生效。
- #779、#780 与 #783 均有玩法/模板重叠且已冲突；它们是新增功能或接线，不应用来覆盖当前 master 的审计结论。
- #787 是系统深色模式，虽触达大量 WXML，但主要是视觉主题，不等于业务流程闭环。
- #789 改首页 Feed，和 #783 首页三文件内容不同；只能标“开放 PR 已改”，不能判哪一边自动胜出。

## 七、14 个完全不可达 WXML 是什么

| # | 路径 | 类型 | 事件绑定 | 体积（目录） | 结论 |
|---:|---|---|---:|---:|---|
| 1 | `components/cy/city-card/index.wxml` | 组件 | 1 | 5.8 KiB | 旧城市卡组件；无生产宿主。 |
| 2 | `components/cy/divider/index.wxml` | 组件 | 0 | 1.5 KiB | 旧分隔线组件；无生产宿主。 |
| 3 | `components/cy/event-card/index.wxml` | 组件 | 1 | 9.5 KiB | 旧活动卡组件；页面里的同名 class 不是该组件。 |
| 4 | `components/cy/field/index.wxml` | 组件 | 8 | 7.1 KiB | 旧表单字段组件；8 条输入/焦点/清空事件均不在生产宿主中。 |
| 5 | `components/cy/level-progress/index.wxml` | 组件 | 1 | 6.2 KiB | 旧等级进度组件；无生产宿主。 |
| 6 | `components/cy/node/index.wxml` | 组件 | 0 | 1.9 KiB | 旧节点组件；无生产宿主。 |
| 7 | `components/cy/play-hud/index.wxml` | 组件 | 4 | 6.4 KiB | 仅测试引用，生产 WXML 未实例化。 |
| 8 | `components/cy/play-pause/index.wxml` | 组件 | 1 | 3.7 KiB | 仅测试引用，生产 WXML 未实例化。 |
| 9 | `components/cy/points/index.wxml` | 组件 | 1 | 4.5 KiB | 旧积分组件；无生产宿主。 |
| 10 | `components/cy/quest-card/index.wxml` | 组件 | 2 | 9.5 KiB | 旧任务卡组件；无生产宿主。 |
| 11 | `components/cy/section-header/index.wxml` | 组件 | 0 | 1.1 KiB | 旧分区标题组件；无生产宿主。 |
| 12 | `components/cy/work-item/index.wxml` | 组件 | 2 | 7.0 KiB | 旧工作项组件；无生产宿主。 |
| 13 | `pages/explore/index.wxml` | 页面 | 0 | 826 B | 未注册占位页；单测明确要求不进入 app.json。 |
| 14 | `subpackageA/components/ec-canvas/ec-canvas.wxml` | 组件 | 8 | 1004.8 KiB | 仅在分包 JSON 声明，页面未实例化；目录含约 1 MiB echarts.js。 |

这 14 个不属于当前用户可操作功能，不应纳入“按钮都通”的分母；也不能仅凭不可达就直接删除，因为测试夹具、未来开关或分包体积治理需要单独决策。尤其 `ec-canvas` 目录约 1 MiB，若被打进分包会形成明显体积债。

## 八、116 个页面级功能逐页审查

> `host variants` 含该页面直接动作及其实际引用组件的动作分支，会因共享组件在多个页面出现而跨页重复，不能相加当全局总数。

| # | 路由 | 角色 | 直接绑定 | host variants | 外部能力 variants | 静态门 | 当前启动 | 开放 PR | 结论 |
|---:|---|---|---:|---:|---:|---|---|---|---|
| P001 | `pages/index/index` | player | 19 | 32 | 1 | PASS | PAGE_LAYER_ONLY | #789, #787, #783, #782 | STATIC_PASS_DYNAMIC_PENDING |
| P002 | `pages/template/index` | merchant | 22 | 41 | 0 | PASS | PAGE_LAYER_ONLY | #787, #783, #782, #780 | STATIC_PASS_DYNAMIC_PENDING |
| P003 | `pages/talent/list/index` | merchant | 15 | 29 | 0 | PASS | PAGE_LAYER_ONLY | #787, #783, #782 | STATIC_PASS_DYNAMIC_PENDING |
| P004 | `pages/member/index/index` | player | 1 | 200 | 6 | PASS | PAGE_LAYER_ONLY | #787 | STATIC_PASS_DYNAMIC_PENDING |
| P005 | `pages/roam/index` | player | 132 | 261 | 31 | PASS | PAGE_LAYER_ONLY | #787, #783, #782 | STATIC_PASS_DYNAMIC_PENDING |
| P006 | `pages/privacy/index` | player | 5 | 10 | 2 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |
| P007 | `pages/agreement/index` | player | 1 | 2 | 0 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |
| P008 | `pages/deregister/index` | player | 2 | 22 | 0 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |
| P009 | `pages/team/detail/index` | player | 5 | 6 | 0 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |
| P010 | `pages/team/join/index` | player | 3 | 4 | 0 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |
| P011 | `pages/play/index` | player | 184 | 299 | 51 | PASS | PAGE_LAYER_ONLY | #787, #783, #782, #780, #779 | STATIC_PASS_DYNAMIC_PENDING |
| P012 | `pages/play/merchant/index` | player | 15 | 15 | 3 | PASS | PAGE_LAYER_ONLY | #787, #783 | STATIC_PASS_DYNAMIC_PENDING |
| P013 | `pages/play/celebrate/index` | player | 36 | 37 | 0 | PASS | PAGE_LAYER_ONLY | #783 | STATIC_PASS_DYNAMIC_PENDING |
| P014 | `pages/activity/detail/index` | merchant | 4 | 33 | 2 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |
| P015 | `pages/activity/baoming/baoming` | player | 19 | 37 | 12 | PASS | PAGE_LAYER_ONLY | #787, #783 | STATIC_PASS_DYNAMIC_PENDING |
| P016 | `pages/activity/list/index` | player | 37 | 56 | 0 | PASS | PAGE_LAYER_ONLY | #783, #782 | STATIC_PASS_DYNAMIC_PENDING |
| P017 | `pages/activity/official-detail/index` | player | 3 | 8 | 0 | PASS | PAGE_LAYER_ONLY | #783, #782 | STATIC_PASS_DYNAMIC_PENDING |
| P018 | `pages/activity/official-inbox/index` | merchant | 4 | 7 | 0 | PASS | PAGE_LAYER_ONLY | #787, #783, #782 | STATIC_PASS_DYNAMIC_PENDING |
| P019 | `pages/activity/official-mine/index` | merchant | 2 | 5 | 0 | PASS | PAGE_LAYER_ONLY | #787, #782 | STATIC_PASS_DYNAMIC_PENDING |
| P020 | `subpackageA/pages/myproject/index` | merchant | 18 | 30 | 0 | PASS | PAGE_LAYER_ONLY | #783 | STATIC_PASS_DYNAMIC_PENDING |
| P021 | `subpackageA/pages/infomation/infomation` | player | 12 | 20 | 0 | PASS | PAGE_LAYER_ONLY | #782 | STATIC_PASS_DYNAMIC_PENDING |
| P022 | `subpackageA/pages/infomationdetail/infomationdetail` | player | 4 | 7 | 0 | PASS | PAGE_LAYER_ONLY | #782 | STATIC_PASS_DYNAMIC_PENDING |
| P023 | `subpackageA/pages/assetcenter/income-detail/income-detail` | merchant | 0 | 9 | 0 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |
| P024 | `subpackageA/pages/assetcenter/earnings/index` | player | 25 | 51 | 0 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |
| P025 | `subpackageP3/pages/creator/index/index` | player | 5 | 9 | 0 | PASS | PAGE_LAYER_ONLY | #782 | STATIC_PASS_DYNAMIC_PENDING |
| P026 | `subpackageP3/pages/growthcenter/index/index` | player | 4 | 6 | 0 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |
| P027 | `subpackageP3/pages/growthcenter/leaderboard/index` | player | 4 | 10 | 0 | PASS | PAGE_LAYER_ONLY | #783 | STATIC_PASS_DYNAMIC_PENDING |
| P028 | `subpackageP3/pages/stamp-camera/index/index` | player | 9 | 10 | 2 | PASS | PAGE_LAYER_ONLY | #787, #783 | STATIC_PASS_EXTERNAL_DEVICE_REQUIRED |
| P029 | `subpackageP3/pages/stamp-album/index/index` | player | 4 | 6 | 0 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |
| P030 | `subpackageP3/pages/badge-wall/index/index` | player | 16 | 18 | 0 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |
| P031 | `subpackageP3/pages/badge-3d/index` | player | 4 | 4 | 0 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |
| P032 | `subpackageB/pages/im/list/index` | player | 14 | 21 | 0 | PASS | PAGE_LAYER_ONLY | #783, #782 | STATIC_PASS_DYNAMIC_PENDING |
| P033 | `subpackageB/pages/im/chat/index` | player | 16 | 30 | 2 | PASS | PAGE_LAYER_ONLY | #787, #783, #782 | STATIC_PASS_DYNAMIC_PENDING |
| P034 | `pages/publish/fabu/index` | player | 124 | 139 | 1 | PASS | PAGE_LAYER_ONLY | #787, #783 | STATIC_PASS_DYNAMIC_PENDING |
| P035 | `pages/publish/simple/index` | player | 10 | 11 | 1 | PASS | PAGE_LAYER_ONLY | #783 | STATIC_PASS_DYNAMIC_PENDING |
| P036 | `pages/publish/temp/index` | player | 87 | 100 | 1 | PASS | PAGE_LAYER_ONLY | #787, #783, #779 | STATIC_PASS_DYNAMIC_PENDING |
| P037 | `pages/publish/activity/index` | player | 49 | 71 | 1 | PASS | PAGE_LAYER_ONLY | #787, #783 | STATIC_PASS_DYNAMIC_PENDING |
| P038 | `pages/publish/biaoqian/biaoqian` | player | 3 | 5 | 0 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |
| P039 | `pages/publish/topicadd/topicadd` | player | 0 | 0 | 0 | PASS | PAGE_LAYER→pages/publish/fabu/index | — | COMPATIBILITY_REDIRECT_STATIC_PASS |
| P040 | `pages/publish/templateadd/templateadd` | player | 3 | 4 | 0 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |
| P041 | `pages/publish/template-intro/index` | player | 2 | 3 | 0 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |
| P042 | `pages/club/apply/index` | club | 18 | 24 | 0 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |
| P043 | `pages/club/create/index` | club | 12 | 13 | 0 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |
| P044 | `pages/club/detail/index` | club | 99 | 158 | 5 | PASS | PAGE_LAYER_ONLY | #787, #783, #782 | STATIC_PASS_DYNAMIC_PENDING |
| P045 | `pages/club/edit/index` | club | 7 | 45 | 0 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |
| P046 | `pages/club/enroll/index` | club | 8 | 12 | 0 | PASS | PAGE_LAYER_ONLY | #783, #782 | STATIC_PASS_DYNAMIC_PENDING |
| P047 | `pages/club/group-code/index` | club | 4 | 14 | 0 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |
| P048 | `pages/club/dissolution-blockers/index` | club | 2 | 5 | 0 | PASS | PAGE_LAYER_ONLY | #783, #782 | STATIC_PASS_DYNAMIC_PENDING |
| P049 | `pages/club/join-requests/index` | club | 5 | 9 | 0 | PASS | PAGE_LAYER_ONLY | #787, #783, #782 | STATIC_PASS_DYNAMIC_PENDING |
| P050 | `pages/club/workbench/index` | club | 0 | 1 | 0 | PASS | PAGE_LAYER_ONLY | — | COMPATIBILITY_REDIRECT_STATIC_PASS |
| P051 | `pages/club/edition-report/index` | club | 7 | 8 | 0 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |
| P052 | `pages/topic/index/index` | player | 36 | 48 | 4 | PASS | PAGE_LAYER_ONLY | #787, #783 | STATIC_PASS_DYNAMIC_PENDING |
| P053 | `pages/topic/merchantinfo/merchantinfo` | merchant | 27 | 51 | 1 | PASS | PAGE_LAYER_ONLY | #787, #783, #780 | STATIC_PASS_DYNAMIC_PENDING |
| P054 | `pages/topic/merchantapply/index` | merchant | 20 | 25 | 0 | PASS | PAGE_LAYER_ONLY | #783 | STATIC_PASS_DYNAMIC_PENDING |
| P055 | `pages/topic/pricing/index` | merchant | 8 | 14 | 0 | PASS | PAGE_LAYER_ONLY | #782 | STATIC_PASS_DYNAMIC_PENDING |
| P056 | `pages/topic/pricing/partner/index` | merchant | 4 | 7 | 1 | PASS | PAGE_LAYER_ONLY | #782 | STATIC_PASS_DYNAMIC_PENDING |
| P057 | `pages/merchant/index/index` | merchant | 51 | 197 | 13 | PASS | PAGE_LAYER_ONLY | #787, #783 | STATIC_PASS_DYNAMIC_PENDING |
| P058 | `pages/merchant/apply/index` | merchant | 20 | 23 | 1 | PASS | PAGE_LAYER_ONLY | #783 | STATIC_PASS_DYNAMIC_PENDING |
| P059 | `pages/merchant/ledger/index` | merchant | 8 | 14 | 0 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |
| P060 | `pages/merchant/ledger/order-detail/index` | merchant | 3 | 5 | 0 | PASS | PAGE_LAYER_ONLY | #782 | STATIC_PASS_DYNAMIC_PENDING |
| P061 | `pages/merchant/ledger/batch-detail/index` | merchant | 3 | 5 | 1 | PASS | PAGE_LAYER_ONLY | #783, #782 | STATIC_PASS_DYNAMIC_PENDING |
| P062 | `pages/merchant/profile/index` | merchant | 5 | 8 | 0 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |
| P063 | `pages/merchant/marketing/index` | merchant | 5 | 7 | 0 | PASS | PAGE_LAYER_ONLY | #783 | STATIC_PASS_DYNAMIC_PENDING |
| P064 | `pages/merchant/marketing/ai-insight/index` | merchant | 8 | 11 | 0 | PASS | PAGE_LAYER_ONLY | #783, #782 | STATIC_PASS_DYNAMIC_PENDING |
| P065 | `pages/merchant/relation/index` | merchant | 6 | 18 | 0 | PASS | PAGE_LAYER_ONLY | #787, #783, #782 | STATIC_PASS_DYNAMIC_PENDING |
| P066 | `pages/merchant/citynode/index` | merchant | 12 | 27 | 1 | PASS | PAGE_LAYER_ONLY | #782 | STATIC_PASS_DYNAMIC_PENDING |
| P067 | `pages/merchant/citynode/create/index` | merchant | 7 | 8 | 2 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |
| P068 | `pages/merchant/decor/index` | merchant | 41 | 66 | 3 | PASS | PAGE_LAYER_ONLY | #783, #782 | STATIC_PASS_DYNAMIC_PENDING |
| P069 | `pages/merchant/decor/story/index` | merchant | 5 | 8 | 0 | PASS | PAGE_LAYER_ONLY | #783, #782 | STATIC_PASS_DYNAMIC_PENDING |
| P070 | `pages/merchant/decor/coop-setting/index` | merchant | 8 | 11 | 0 | PASS | PAGE_LAYER_ONLY | #783, #782 | STATIC_PASS_DYNAMIC_PENDING |
| P071 | `pages/merchant/decor/gallery/index` | merchant | 5 | 8 | 0 | PASS | PAGE_LAYER_ONLY | #782 | STATIC_PASS_DYNAMIC_PENDING |
| P072 | `pages/merchant/decor/perks/index` | merchant | 14 | 25 | 0 | PASS | PAGE_LAYER_ONLY | #783, #782 | STATIC_PASS_DYNAMIC_PENDING |
| P073 | `pages/merchant/discover/index` | merchant | 7 | 13 | 0 | PASS | PAGE_LAYER_ONLY | #787, #783 | STATIC_PASS_DYNAMIC_PENDING |
| P074 | `pages/merchant/coop-center/index` | merchant | 18 | 41 | 0 | PASS | PAGE_LAYER_ONLY | #788, #783, #782 | BROKEN_ON_MASTER_FIXED_IN_OPEN_PR：主线成功分支 ReferenceError，路线列表永远 loading；PR #788 与 #783 已覆盖 |
| P075 | `pages/merchant/customer/index` | merchant | 6 | 15 | 1 | PASS | PAGE_LAYER_ONLY | #783, #782 | STATIC_PASS_DYNAMIC_PENDING |
| P076 | `pages/coop/list/index` | merchant | 26 | 37 | 2 | PASS | PAGE_LAYER_ONLY | #787, #783 | STATIC_PASS_DYNAMIC_PENDING |
| P077 | `pages/coop/invite/index` | merchant | 14 | 22 | 0 | PASS | PAGE_LAYER_ONLY | #783 | STATIC_PASS_DYNAMIC_PENDING |
| P078 | `pages/coop/nearby/index` | merchant | 5 | 8 | 4 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |
| P079 | `pages/coop/candidates/index` | merchant | 9 | 27 | 0 | PASS | PAGE_LAYER_ONLY | #783 | STATIC_PASS_DYNAMIC_PENDING |
| P080 | `pages/coop/finance/index` | merchant | 0 | 6 | 0 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |
| P081 | `pages/coop/settlement-detail/index` | merchant | 2 | 4 | 0 | PASS | PAGE_LAYER_ONLY | #782 | STATIC_PASS_DYNAMIC_PENDING |
| P082 | `pages/coop/withdraw/index` | merchant | 1 | 3 | 0 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |
| P083 | `pages/coop/withdraw/records/index` | merchant | 0 | 0 | 0 | PASS | PAGE_LAYER→subpackageMember/tixianjilu/tixianjilu | — | COMPATIBILITY_REDIRECT_STATIC_PASS |
| P084 | `pages/square/list/index` | player | 30 | 33 | 1 | PASS | PAGE_LAYER_ONLY | #787, #783 | STATIC_PASS_DYNAMIC_PENDING |
| P085 | `pages/square/detail/index` | player | 24 | 32 | 1 | PASS | PAGE_LAYER_ONLY | #787, #783 | STATIC_PASS_DYNAMIC_PENDING |
| P086 | `pages/search2/index` | player | 26 | 40 | 0 | PASS | PAGE_LAYER_ONLY | #783, #782 | STATIC_PASS_DYNAMIC_PENDING |
| P087 | `pages/search2/result/index` | player | 5 | 16 | 0 | PASS | PAGE_LAYER_ONLY | #782 | STATIC_PASS_DYNAMIC_PENDING |
| P088 | `pages/searchmap/index` | player | 39 | 57 | 2 | PASS | PAGE_LAYER_ONLY | #787, #783 | STATIC_PASS_DYNAMIC_PENDING |
| P089 | `pages/templatedetail/templatedetail` | merchant | 25 | 35 | 1 | PASS | PAGE_LAYER_ONLY | #787, #783 | STATIC_PASS_DYNAMIC_PENDING |
| P090 | `pages/address/address` | player | 5 | 11 | 0 | PASS | PAGE_LAYER_ONLY | #783 | STATIC_PASS_DYNAMIC_PENDING |
| P091 | `pages/addressinfo/addressinfo` | player | 4 | 9 | 0 | PASS | PAGE_LAYER_ONLY | #783 | STATIC_PASS_DYNAMIC_PENDING |
| P092 | `pages/shezhi/shezhi` | merchant | 30 | 137 | 1 | PASS | PAGE_LAYER_ONLY | #783 | STATIC_PASS_DYNAMIC_PENDING |
| P093 | `pages/shezhi/about/index` | merchant | 4 | 15 | 0 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |
| P094 | `pages/gerenziliao/gerenziliao` | player | 28 | 43 | 0 | PASS | PAGE_LAYER_ONLY | #787, #783 | STATIC_PASS_DYNAMIC_PENDING |
| P095 | `pages/userinfo/userinfo` | player | 0 | 196 | 5 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |
| P096 | `pages/mylike/mylike` | player | 3 | 6 | 0 | PASS | PAGE_LAYER_ONLY | #787, #783 | STATIC_PASS_DYNAMIC_PENDING |
| P097 | `pages/crop/index` | player | 6 | 7 | 0 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |
| P098 | `subpackageTalent/search/index` | player | 13 | 24 | 0 | PASS | PAGE_LAYER_ONLY | #787, #783, #782 | STATIC_PASS_DYNAMIC_PENDING |
| P099 | `subpackageMember/complaint/index` | player | 11 | 19 | 0 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |
| P100 | `subpackageMember/coupon-wallet/index` | player | 0 | 253 | 13 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |
| P101 | `subpackageMember/coupon-qr/index` | player | 4 | 13 | 2 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |
| P102 | `subpackageMember/coupon/coupon` | merchant | 4 | 10 | 0 | PASS | PAGE_LAYER_ONLY | #787, #782 | STATIC_PASS_DYNAMIC_PENDING |
| P103 | `subpackageMember/couponInfo/couponInfo` | merchant | 17 | 25 | 0 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |
| P104 | `subpackageMember/mycanyu/mycanyu` | player | 10 | 20 | 0 | PASS | PAGE_LAYER_ONLY | #783, #782 | STATIC_PASS_DYNAMIC_PENDING |
| P105 | `subpackageMember/mycanyuinfo/mycanyuinfo` | merchant | 3 | 6 | 0 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |
| P106 | `subpackageMember/myinvite/myinvite` | player | 1 | 13 | 0 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |
| P107 | `subpackageMember/mytemplate/mytemplate` | merchant | 8 | 11 | 0 | PASS | PAGE_LAYER_ONLY | #783 | STATIC_PASS_DYNAMIC_PENDING |
| P108 | `subpackageMember/order/order` | player | 12 | 71 | 4 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |
| P109 | `subpackageMember/orderinfo/orderinfo` | player | 4 | 20 | 2 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |
| P110 | `subpackageMember/signup/index` | player | 7 | 13 | 0 | PASS | PAGE_LAYER_ONLY | #783 | STATIC_PASS_DYNAMIC_PENDING |
| P111 | `subpackageMember/tixian/tixian` | merchant | 10 | 21 | 0 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |
| P112 | `subpackageMember/tixianjilu/tixianjilu` | merchant | 1 | 12 | 0 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |
| P113 | `subpackageRoam/citynode-code/index` | player | 3 | 13 | 0 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |
| P114 | `subpackageRoam/history/index` | player | 3 | 7 | 0 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |
| P115 | `subpackageRoam/poi-detail/index` | player | 0 | 253 | 13 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |
| P116 | `subpackageRoam/session/index` | player | 9 | 20 | 3 | PASS | PAGE_LAYER_ONLY | — | STATIC_PASS_DYNAMIC_PENDING |

## 九、36 个产品流程族逐条审查

| 流程 | 名称 | 页面数 | 关键页面 | 开放 PR 覆盖 | 当前结论 |
|---|---|---:|---|---|---|
| F01 | 启动、会话恢复、登录续期与角色门 | 1 | `pages/index/index` | #783 | STATIC_PASS_DYNAMIC_PENDING；动态全分支 PENDING |
| F02 | 隐私授权、隐私政策与用户协议 | 2 | `pages/privacy/index`<br>`pages/agreement/index` | #783, #787 | STATIC_PASS_EXTERNAL_WECHAT_AUTH；动态全分支 PENDING |
| F03 | 会员中心、个人资料、手机号、头像与裁剪 | 4 | `pages/member/index/index`<br>`pages/gerenziliao/gerenziliao`<br>`pages/userinfo/userinfo`<br>`pages/crop/index` | #783, #787, #782 | STATIC_PASS_EXTERNAL_PHONE_ALBUM；动态全分支 PENDING |
| F04 | 设置与关于 | 2 | `pages/shezhi/shezhi`<br>`pages/shezhi/about/index` | #783, #787 | STATIC_PASS_DYNAMIC_PENDING；动态全分支 PENDING |
| F05 | 账号注销 | 1 | `pages/deregister/index` | #783 | STATIC_PASS_DYNAMIC_PENDING；动态全分支 PENDING |
| F06 | 首页 Feed、附近活动与推荐主题 | 1 | `pages/index/index` | #789, #783, #787, #782 | STATIC_PASS_OPEN_PR_OVERLAP；动态全分支 PENDING |
| F07 | 全局搜索、搜索结果与地图选点 | 3 | `pages/search2/index`<br>`pages/search2/result/index`<br>`pages/searchmap/index` | #783, #782 | STATIC_PASS_EXTERNAL_LOCATION；动态全分支 PENDING |
| F08 | 广场内容列表、详情与收藏 | 3 | `pages/square/list/index`<br>`pages/square/detail/index`<br>`pages/mylike/mylike` | #783 | STATIC_PASS_DYNAMIC_PENDING；动态全分支 PENDING |
| F09 | 模板货架、模板详情、介绍与我的模板 | 4 | `pages/template/index`<br>`pages/templatedetail/templatedetail`<br>`pages/publish/template-intro/index`<br>`subpackageMember/mytemplate/mytemplate` | #780, #783, #787, #782 | STATIC_PASS_OPEN_PR_OVERLAP；动态全分支 PENDING |
| F10 | 活动列表、普通/官方详情与官方消息 | 7 | `pages/activity/list/index`<br>`pages/activity/detail/index`<br>`pages/activity/official-detail/index`<br>`pages/activity/official-inbox/index`<br>`pages/activity/official-mine/index`<br>`subpackageA/pages/infomation/infomation`<br>`subpackageA/pages/infomationdetail/infomationdetail` | #783, #787 | STATIC_PASS_DYNAMIC_PENDING；动态全分支 PENDING |
| F11 | 活动报名、地址、结算与支付 | 4 | `pages/activity/baoming/baoming`<br>`pages/address/address`<br>`pages/addressinfo/addressinfo`<br>`subpackageMember/signup/index` | #783, #787 | STATIC_PASS_EXTERNAL_PAYMENT_PHONE；动态全分支 PENDING |
| F12 | 参与记录、订单详情、退款、投诉与恢复 | 5 | `subpackageMember/mycanyu/mycanyu`<br>`subpackageMember/mycanyuinfo/mycanyuinfo`<br>`subpackageMember/order/order`<br>`subpackageMember/orderinfo/orderinfo`<br>`subpackageMember/complaint/index` | #783, #787 | STATIC_PASS_EXTERNAL_PAYMENT；动态全分支 PENDING |
| F13 | 优惠券钱包、详情与核销二维码 | 4 | `subpackageMember/coupon-wallet/index`<br>`subpackageMember/coupon-qr/index`<br>`subpackageMember/coupon/coupon`<br>`subpackageMember/couponInfo/couponInfo` | #783, #787 | STATIC_PASS_EXTERNAL_QR_SCAN；动态全分支 PENDING |
| F14 | 游玩会话进入、加载与场景切换 | 1 | `pages/play/index` | #783, #782, #780, #779, #787 | STATIC_PASS_OPEN_PR_OVERLAP；动态全分支 PENDING |
| F15 | 节点任务、答题、提示、彩蛋与完成回读 | 1 | `pages/play/index` | #783, #780, #779 | STATIC_PASS_EXTERNAL_LOCATION_CAMERA；动态全分支 PENDING |
| F16 | 同行队伍加入、详情与容量边界 | 2 | `pages/team/join/index`<br>`pages/team/detail/index` | #783 | STATIC_PASS_DYNAMIC_PENDING；动态全分支 PENDING |
| F17 | 通关庆祝、奖励与排行榜入口 | 2 | `pages/play/celebrate/index`<br>`subpackageP3/pages/growthcenter/leaderboard/index` | #783, #779 | STATIC_PASS_DYNAMIC_PENDING；动态全分支 PENDING |
| F18 | 商家节点游玩、到店验证与核销 | 1 | `pages/play/merchant/index` | #783, #787 | STATIC_PASS_EXTERNAL_LOCATION_SCAN；动态全分支 PENDING |
| F19 | 自由漫游启动、实时定位、迷雾与暂停/结束 | 1 | `pages/roam/index` | #783, #787, #782 | STATIC_PASS_EXTERNAL_LOCATION；动态全分支 PENDING；cy-npc-chat 仅声明未实例化，V1 开关固定关闭 |
| F20 | 漫游 POI、城市节点码与地点详情 | 2 | `subpackageRoam/citynode-code/index`<br>`subpackageRoam/poi-detail/index` | #783, #787 | STATIC_PASS_EXTERNAL_LOCATION_SCAN；动态全分支 PENDING |
| F21 | 漫游历史与会话详情 | 2 | `subpackageRoam/history/index`<br>`subpackageRoam/session/index` | #783 | STATIC_PASS_DYNAMIC_PENDING；动态全分支 PENDING |
| F22 | 印章相机、相册、徽章墙与 3D 徽章 | 4 | `subpackageP3/pages/stamp-camera/index/index`<br>`subpackageP3/pages/stamp-album/index/index`<br>`subpackageP3/pages/badge-wall/index/index`<br>`subpackageP3/pages/badge-3d/index` | #783, #787 | STATIC_PASS_EXTERNAL_CAMERA_WEBGL；动态全分支 PENDING |
| F23 | 创作者中心、成长中心、邀请记录与排行榜 | 4 | `subpackageP3/pages/creator/index/index`<br>`subpackageP3/pages/growthcenter/index/index`<br>`subpackageP3/pages/growthcenter/leaderboard/index`<br>`subpackageMember/myinvite/myinvite` | #783 | STATIC_PASS_DYNAMIC_PENDING；动态全分支 PENDING |
| F24 | 站内消息列表与聊天 | 2 | `subpackageB/pages/im/list/index`<br>`subpackageB/pages/im/chat/index` | #783, #782, #787 | STATIC_PASS_DYNAMIC_PENDING；动态全分支 PENDING |
| F25 | 达人列表、达人搜索与联系 | 2 | `pages/talent/list/index`<br>`subpackageTalent/search/index` | #783, #782, #787 | STATIC_PASS_DYNAMIC_PENDING；动态全分支 PENDING |
| F26 | 俱乐部申请与创建 | 2 | `pages/club/apply/index`<br>`pages/club/create/index` | #783, #787 | STATIC_PASS_DYNAMIC_PENDING；动态全分支 PENDING |
| F27 | 俱乐部详情、编辑、成员与排行 | 2 | `pages/club/detail/index`<br>`pages/club/edit/index` | #783, #782, #787 | STATIC_PASS_DYNAMIC_PENDING；动态全分支 PENDING |
| F28 | 俱乐部报名、入群码与加入申请处理 | 3 | `pages/club/enroll/index`<br>`pages/club/group-code/index`<br>`pages/club/join-requests/index` | #783, #782, #787 | STATIC_PASS_DYNAMIC_PENDING；动态全分支 PENDING |
| F29 | 俱乐部解散阻断、运营报告与旧工作台兼容 | 3 | `pages/club/dissolution-blockers/index`<br>`pages/club/edition-report/index`<br>`pages/club/workbench/index` | #783 | STATIC_PASS_DYNAMIC_PENDING；动态全分支 PENDING |
| F30 | 商家入驻、资料与身份切换 | 3 | `pages/merchant/apply/index`<br>`pages/merchant/profile/index`<br>`pages/merchant/index/index` | #783, #787 | STATIC_PASS_DYNAMIC_PENDING；动态全分支 PENDING |
| F31 | 商家城市节点与店铺装修 | 7 | `pages/merchant/citynode/index`<br>`pages/merchant/citynode/create/index`<br>`pages/merchant/decor/index`<br>`pages/merchant/decor/story/index`<br>`pages/merchant/decor/coop-setting/index`<br>`pages/merchant/decor/gallery/index`<br>`pages/merchant/decor/perks/index` | #783, #782, #787 | STATIC_PASS_DYNAMIC_PENDING；动态全分支 PENDING |
| F32 | 商家工作台、营销与 AI 洞察 | 3 | `pages/merchant/index/index`<br>`pages/merchant/marketing/index`<br>`pages/merchant/marketing/ai-insight/index` | #783, #782, #787 | STATIC_PASS_DYNAMIC_PENDING；动态全分支 PENDING |
| F33 | 商家客户、关系与发现 | 3 | `pages/merchant/customer/index`<br>`pages/merchant/relation/index`<br>`pages/merchant/discover/index` | #783, #782, #787 | STATIC_PASS_DYNAMIC_PENDING；动态全分支 PENDING |
| F34 | 商家账本、资产收益与提现 | 7 | `pages/merchant/ledger/index`<br>`pages/merchant/ledger/order-detail/index`<br>`pages/merchant/ledger/batch-detail/index`<br>`subpackageA/pages/assetcenter/income-detail/income-detail`<br>`subpackageA/pages/assetcenter/earnings/index`<br>`subpackageMember/tixian/tixian`<br>`subpackageMember/tixianjilu/tixianjilu` | #783, #787 | STATIC_PASS_EXTERNAL_FINANCE；动态全分支 PENDING |
| F35 | 合作中心、邀请、候选、财务、结算与提现 | 9 | `pages/merchant/coop-center/index`<br>`pages/coop/list/index`<br>`pages/coop/invite/index`<br>`pages/coop/nearby/index`<br>`pages/coop/candidates/index`<br>`pages/coop/finance/index`<br>`pages/coop/settlement-detail/index`<br>`pages/coop/withdraw/index`<br>`pages/coop/withdraw/records/index` | #788, #783, #782, #787, #780 | BROKEN_ON_MASTER_FIXED_IN_PR_788_AND_783；动态全分支 PENDING |
| F36 | 发布编辑、主题经营、项目管理与商家报价 | 13 | `pages/publish/fabu/index`<br>`pages/publish/simple/index`<br>`pages/publish/temp/index`<br>`pages/publish/activity/index`<br>`pages/publish/biaoqian/biaoqian`<br>`pages/publish/topicadd/topicadd`<br>`pages/publish/templateadd/templateadd`<br>`pages/topic/index/index`<br>`pages/topic/merchantinfo/merchantinfo`<br>`pages/topic/merchantapply/index`<br>`pages/topic/pricing/index`<br>`pages/topic/pricing/partner/index`<br>`subpackageA/pages/myproject/index` | #783, #782, #780, #779, #787 | STATIC_PASS_OPEN_PR_OVERLAP；动态全分支 PENDING |

流程结论：只有 F35 已证明当前 master 存在确定中断；其余 35 条是“静态合同通过、动态闭环证据不足”，不能解读为已通，也不能解读为已坏。

## 十、事件绑定类型汇总

| 事件属性 | 数量 |
|---|---:|
| `bindtap` | 1006 |
| `catchtap` | 218 |
| `bindinput` | 210 |
| `bind:close` | 146 |
| `bind:retry` | 133 |
| `bind:back` | 100 |
| `bindchange` | 76 |
| `catchtouchmove` | 61 |
| `bind:requestclose` | 59 |
| `bind:cta` | 44 |
| `bind:change` | 36 |
| `bind:open` | 34 |
| `bind:tap` | 33 |
| `bind:confirm` | 28 |
| `bind:cancel` | 20 |
| `bind:action` | 16 |
| `bindblur` | 13 |
| `bindtouchmove` | 12 |
| `binderror` | 11 |
| `bindconfirm` | 9 |
| `bindfocus` | 9 |
| `bind:input` | 9 |
| `bind:select` | 9 |
| `bindcta` | 8 |
| `bindtouchstart` | 8 |
| `bindtouchend` | 8 |
| `bind:dirtychange` | 7 |
| `bindscrolltolower` | 6 |
| `bind:settled` | 6 |
| `bindmarkertap` | 5 |
| `bindtouchcancel` | 5 |
| `bind:share` | 4 |
| `bind:disabledtap` | 4 |
| `bind:camera` | 3 |
| `bind:saved` | 3 |
| `bind:dissolved` | 3 |
| `bindscroll` | 3 |
| `bindlongpress` | 3 |
| `bindregionchange` | 2 |
| `bindagreeprivacyauthorization` | 2 |
| `bind:secondary` | 2 |
| `bind:loaded` | 2 |
| `bind:add` | 2 |
| `bind:remove` | 2 |
| `bind:submit` | 2 |
| `bind:afterleave` | 2 |
| `bind:detail` | 2 |
| `bind:play` | 2 |
| `bindretry` | 2 |
| `bind:act` | 2 |
| `bind:refresh` | 2 |
| `binddisabledtap` | 1 |
| `bindupdated` | 1 |
| `bindpoitap` | 1 |
| `catchtouchstart` | 1 |
| `catchtouchend` | 1 |
| `catchtouchcancel` | 1 |
| `bindgetphonenumber` | 1 |
| `bind:pintap` | 1 |
| `bind:done` | 1 |
| `bind:preview` | 1 |
| `catchlongpress` | 1 |
| `bind:generate` | 1 |
| `bind:updated` | 1 |
| `bind:markertap` | 1 |
| `bind:regionchange` | 1 |
| `bind:poitap` | 1 |
| `bindload` | 1 |
| `bind:ready` | 1 |
| `bind:empty` | 1 |
| `bindcontact` | 1 |
| `bind:unsupported` | 1 |
| `bind:xready` | 1 |

## 十一、全部 2,416 条事件绑定逐条审查附录

> 每行的 `PR touch` 仅表示开放 PR 修改了所在 WXML 或 handler JS；`PENDING` 表示没有当前主线的真实交互与权威终态证据。四条 `ledger variants=0` 即声明但未实例化的 `cy-npc-chat`。

| ID | WXML:line | tag | event | handler | ledger variants | action class | mode | 分支 | 外部能力 | handler 门 | PR touch | 动态证据 |
|---|---|---|---|---|---:|---|---|---|---|---|---|---|
| E0001 | `components/cy/agreement-sheet/index.wxml:6` | `cy-scene-sheet` | `bind:requestclose` | `onClose` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0002 | `components/cy/avatar/index.wxml:3` | `view` | `bindtap` | `onTap` | 1 | other | tap | error,success | NO | PASS | #783 | PENDING |
| E0003 | `components/cy/category-sheet/index.wxml:1` | `cy-sheet` | `bind:close` | `onClose` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E0004 | `components/cy/category-sheet/index.wxml:7` | `view` | `bindtap` | `toggleCategory` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E0005 | `components/cy/category-sheet/index.wxml:15` | `cy-empty` | `bindcta` | `getCategoryList` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | — | PENDING |
| E0006 | `components/cy/category-sheet/index.wxml:18` | `cy-btn` | `bindtap` | `onSave` | 1 | other | tap | error,success | NO | PASS | — | PENDING |
| E0007 | `components/cy/cell/index.wxml:5` | `view` | `catchtap` | `onTap` | 1 | other | tap | error,success | NO | PASS | #783 | PENDING |
| E0008 | `components/cy/chapter-node-form/index.wxml:2` | `cy-sheet` | `bind:close` | `onClose` | 1 | other | event-contract | error,success | NO | PASS | #783,#780 | PENDING |
| E0009 | `components/cy/chapter-node-form/index.wxml:9` | `input` | `bindinput` | `onInput` | 1 | state | event-contract | success | NO | PASS | #783,#780 | PENDING |
| E0010 | `components/cy/chapter-node-form/index.wxml:19` | `picker` | `bindchange` | `onPerkPick` | 1 | state | event-contract | success | NO | PASS | #783,#780 | PENDING |
| E0011 | `components/cy/chapter-node-form/index.wxml:27` | `cy-btn` | `bindtap` | `goCreatePerkTemplate` | 1 | navigation | tap | error,success | NO | PASS | #783,#780 | PENDING |
| E0012 | `components/cy/chapter-node-form/index.wxml:34` | `input` | `bindinput` | `onInput` | 1 | state | event-contract | success | NO | PASS | #783,#780 | PENDING |
| E0013 | `components/cy/chapter-node-form/index.wxml:39` | `input` | `bindinput` | `onInput` | 1 | state | event-contract | success | NO | PASS | #783,#780 | PENDING |
| E0014 | `components/cy/chapter-node-form/index.wxml:47` | `input` | `bindinput` | `onInput` | 1 | state | event-contract | success | NO | PASS | #783,#780 | PENDING |
| E0015 | `components/cy/chapter-node-form/index.wxml:53` | `input` | `bindinput` | `onInput` | 1 | state | event-contract | success | NO | PASS | #783,#780 | PENDING |
| E0016 | `components/cy/chapter-node-form/index.wxml:58` | `picker` | `bindchange` | `onTemplatePick` | 1 | state | event-contract | success | NO | PASS | #783,#780 | PENDING |
| E0017 | `components/cy/chapter-node-form/index.wxml:69` | `input` | `bindinput` | `onInput` | 1 | state | event-contract | success | NO | PASS | #783,#780 | PENDING |
| E0018 | `components/cy/chapter-node-form/index.wxml:74` | `cy-btn` | `bindtap` | `onClose` | 1 | other | tap | error,success | NO | PASS | #783,#780 | PENDING |
| E0019 | `components/cy/chapter-node-form/index.wxml:75` | `cy-btn` | `bindtap` | `onSubmit` | 1 | other | tap | error,success | NO | PASS | #783,#780 | PENDING |
| E0020 | `components/cy/club-card/index.wxml:1` | `view` | `catchtap` | `onTap` | 1 | other | tap | error,success | NO | PASS | #783 | PENDING |
| E0021 | `components/cy/club-card/index.wxml:56` | `view` | `catchtap` | `onAction` | 1 | other | tap | error,success | NO | PASS | #783 | PENDING |
| E0022 | `components/cy/date-sheet/index.wxml:3` | `button` | `bindtap` | `onCancel` | 1 | other | tap | error,success | NO | PASS | — | PENDING |
| E0023 | `components/cy/date-sheet/index.wxml:5` | `button` | `bindtap` | `onConfirm` | 1 | other | tap | error,success | NO | PASS | — | PENDING |
| E0024 | `components/cy/empty/index.wxml:9` | `view` | `bindtap` | `onCta` | 1 | other | tap | error,success | NO | PASS | — | PENDING |
| E0025 | `components/cy/error/index.wxml:5` | `view` | `bindtap` | `onRetry` | 1 | other | tap | error,success | NO | PASS | — | PENDING |
| E0026 | `components/cy/footer-bar/index.wxml:4` | `cy-btn` | `bindtap` | `onSecondary` | 1 | other | tap | error,success | NO | PASS | — | PENDING |
| E0027 | `components/cy/footer-bar/index.wxml:11` | `cy-btn` | `bindtap` | `onPrimary` | 1 | other | tap | error,success | NO | PASS | — | PENDING |
| E0028 | `components/cy/footer-bar/index.wxml:12` | `cy-btn` | `binddisabledtap` | `onPrimaryDisabled` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E0029 | `components/cy/free-map/index.wxml:12` | `map` | `bindupdated` | `onUpdated` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E0030 | `components/cy/free-map/index.wxml:12` | `map` | `bindmarkertap` | `onMarkerTap` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E0031 | `components/cy/free-map/index.wxml:13` | `map` | `bindregionchange` | `onRegionChange` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E0032 | `components/cy/free-map/index.wxml:13` | `map` | `bindpoitap` | `onPoiTap` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E0033 | `components/cy/free-map/index.wxml:22` | `cover-view` | `bindtap` | `onExitTap` | 1 | other | tap | error,success | NO | PASS | — | PENDING |
| E0034 | `components/cy/free-map/index.wxml:31` | `view` | `bindtap` | `recenter` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E0035 | `components/cy/free-map/index.wxml:33` | `view` | `bindtap` | `zoomIn` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E0036 | `components/cy/free-map/index.wxml:35` | `view` | `bindtap` | `zoomOut` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E0037 | `components/cy/info-pop/index.wxml:8` | `view` | `catchtap` | `onOpen` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E0038 | `components/cy/info-pop/index.wxml:25` | `cy-modal` | `bind:confirm` | `onClose` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0039 | `components/cy/info-pop/index.wxml:26` | `cy-modal` | `bind:cancel` | `onClose` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0040 | `components/cy/info-pop/index.wxml:37` | `cy-sheet` | `bind:close` | `onClose` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0041 | `components/cy/merchant-card/index.wxml:1` | `view` | `catchtap` | `onTap` | 1 | other | tap | error,success | NO | PASS | #783 | PENDING |
| E0042 | `components/cy/merchant-card/index.wxml:39` | `view` | `catchtap` | `onService` | 1 | other | tap | error,success | NO | PASS | #783 | PENDING |
| E0043 | `components/cy/merchant-card/index.wxml:45` | `view` | `catchtap` | `onAction` | 1 | other | tap | error,success | NO | PASS | #783 | PENDING |
| E0044 | `components/cy/modal/index.wxml:2` | `view` | `bindtap` | `onMask` | 1 | other | tap | error,success | NO | PASS | #787,#782 | PENDING |
| E0045 | `components/cy/modal/index.wxml:10` | `view` | `bindtap` | `onCancel` | 1 | other | tap | error,success | NO | PASS | #787,#782 | PENDING |
| E0046 | `components/cy/modal/index.wxml:11` | `view` | `bindtap` | `onConfirm` | 1 | other | tap | error,success | NO | PASS | #787,#782 | PENDING |
| E0047 | `components/cy/nav-bar/index.wxml:6` | `view` | `bindtap` | `onBack` | 1 | navigation | tap | error,success | NO | PASS | — | PENDING |
| E0048 | `components/cy/npc-chat/index.wxml:1` | `cy-sheet` | `bind:close` | `onSheetClose` | 0 | DECLARED_ONLY | — | — | NO | PASS | — | PENDING |
| E0049 | `components/cy/npc-chat/index.wxml:23` | `textarea` | `bindinput` | `onInput` | 0 | DECLARED_ONLY | — | — | NO | PASS | — | PENDING |
| E0050 | `components/cy/npc-chat/index.wxml:23` | `textarea` | `bindconfirm` | `onSend` | 0 | DECLARED_ONLY | — | — | NO | PASS | — | PENDING |
| E0051 | `components/cy/npc-chat/index.wxml:26` | `view` | `bindtap` | `onSend` | 0 | DECLARED_ONLY | — | — | NO | PASS | — | PENDING |
| E0052 | `components/cy/play-header/index.wxml:7` | `view` | `catchtap` | `onAction` | 1 | other | tap | error,success | NO | PASS | — | PENDING |
| E0053 | `components/cy/privacy-gate/index.wxml:12` | `view` | `bindtap` | `openPrivacyContract` | 1 | external | tap | cancelled,denied,duplicate-trigger,error,success,timeout | YES | PASS | — | PENDING |
| E0054 | `components/cy/privacy-gate/index.wxml:14` | `button` | `bindagreeprivacyauthorization` | `agreePrivacy` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0055 | `components/cy/privacy-gate/index.wxml:15` | `cy-btn` | `bindtap` | `disagreePrivacy` | 1 | other | tap | error,success | NO | PASS | — | PENDING |
| E0056 | `components/cy/profile/index.wxml:14` | `view` | `bindtap` | `goSetting` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0057 | `components/cy/profile/index.wxml:17` | `view` | `bindtap` | `goBack` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0058 | `components/cy/profile/index.wxml:28` | `scroll-view` | `bindscrolltolower` | `onFeedReachBottom` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0059 | `components/cy/profile/index.wxml:40` | `view` | `bindtap` | `onAvatarTap` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0060 | `components/cy/profile/index.wxml:82` | `cy-empty` | `bind:cta` | `goRoam` | 1 | navigation | event-contract | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0061 | `components/cy/profile/index.wxml:89` | `cy-error` | `bind:retry` | `reloadProfileMetrics` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0062 | `components/cy/profile/index.wxml:100` | `view` | `bindtap` | `onPrimaryCta` | 2 | external,write | tap | cancelled,denied,duplicate-trigger,error,forbidden-owner,success,timeout | YES | PASS | #787,#783,#782 | PENDING |
| E0063 | `components/cy/profile/index.wxml:102` | `view` | `bindtap` | `goSignup` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0064 | `components/cy/profile/index.wxml:106` | `view` | `bindtap` | `onStartChat` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0065 | `components/cy/profile/index.wxml:110` | `view` | `bindtap` | `openMoreMenu` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E0066 | `components/cy/profile/index.wxml:116` | `cy-tabs` | `bind:change` | `onTabChange` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0067 | `components/cy/profile/index.wxml:122` | `text` | `bindtap` | `goOrder` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0068 | `components/cy/profile/index.wxml:126` | `view` | `bindtap` | `onOrderPreviewTap` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0069 | `components/cy/profile/index.wxml:136` | `cy-error` | `bind:retry` | `reloadOrders` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0070 | `components/cy/profile/index.wxml:145` | `text` | `bindtap` | `goProject` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0071 | `components/cy/profile/index.wxml:148` | `view` | `bindtap` | `onProjectPreviewTap` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0072 | `components/cy/profile/index.wxml:159` | `cy-error` | `bind:retry` | `reloadProjects` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0073 | `components/cy/profile/index.wxml:167` | `text` | `bindtap` | `goEarnings` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0074 | `components/cy/profile/index.wxml:170` | `view` | `bindtap` | `goEarnings` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0075 | `components/cy/profile/index.wxml:183` | `cy-error` | `bind:retry` | `reloadFeed` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0076 | `components/cy/profile/index.wxml:193` | `view` | `bindtap` | `jumpDetail` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0077 | `components/cy/profile/index.wxml:196` | `image` | `catchtap` | `previewImage` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #787,#783,#782 | PENDING |
| E0078 | `components/cy/profile/index.wxml:199` | `view` | `catchtap` | `lickClick` | 1 | write | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0079 | `components/cy/profile/index.wxml:202` | `view` | `catchtap` | `lickClick` | 1 | write | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0080 | `components/cy/profile/index.wxml:224` | `view` | `bindtap` | `onPointsHelp` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0081 | `components/cy/profile/index.wxml:228` | `view` | `bindtap` | `goGrowthCenter` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0082 | `components/cy/profile/index.wxml:251` | `view` | `bindtap` | `goWall` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0083 | `components/cy/profile/index.wxml:260` | `view` | `bindtap` | `goAlbum` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0084 | `components/cy/profile/index.wxml:272` | `cy-error` | `bind:retry` | `reloadMedals` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0085 | `components/cy/profile/index.wxml:294` | `cy-error` | `bind:retry` | `reloadProfileMetrics` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0086 | `components/cy/profile/index.wxml:331` | `image` | `bindtap` | `previewMerchantGallery` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #787,#783,#782 | PENDING |
| E0087 | `components/cy/profile/index.wxml:363` | `view` | `bindtap` | `goMerchantLocation` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #787,#783,#782 | PENDING |
| E0088 | `components/cy/profile/index.wxml:400` | `cy-error` | `bind:retry` | `retryMerchantProbe` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0089 | `components/cy/profile/index.wxml:407` | `view` | `bindtap` | `goUserInfo` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0090 | `components/cy/profile/index.wxml:423` | `image` | `bindtap` | `previewWork` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #787,#783,#782 | PENDING |
| E0091 | `components/cy/profile/index.wxml:437` | `view` | `bindtap` | `goClub` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0092 | `components/cy/profile/index.wxml:442` | `view` | `bindtap` | `goBecomeTalent` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0093 | `components/cy/profile/index.wxml:447` | `view` | `bindtap` | `goEarnings` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0094 | `components/cy/profile/index.wxml:452` | `view` | `bindtap` | `goCreator` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0095 | `components/cy/profile/index.wxml:457` | `view` | `bindtap` | `goCoupon` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0096 | `components/cy/profile/index.wxml:462` | `view` | `bindtap` | `goInvite` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E0097 | `components/cy/profile/index.wxml:467` | `view` | `bindtap` | `goComplaint` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0098 | `components/cy/profile/index.wxml:472` | `view` | `bindtap` | `onRenewMember` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E0099 | `components/cy/profile/index.wxml:482` | `view` | `bindtap` | `onBenefitTap` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0100 | `components/cy/profile/index.wxml:508` | `view` | `bindtap` | `onCoopInvite` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0101 | `components/cy/profile/index.wxml:513` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0102 | `components/cy/profile/index.wxml:514` | `cy-scene-sheet` | `bind:requestclose` | `closeMoreMenu` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E0103 | `components/cy/profile/index.wxml:515` | `cy-cell` | `bind:tap` | `goShop` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0104 | `components/cy/profile/index.wxml:516` | `cy-cell` | `bind:tap` | `onShareProfile` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E0105 | `components/cy/profile/index.wxml:517` | `cy-cell` | `bind:tap` | `onShowQr` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E0106 | `components/cy/profile/index.wxml:518` | `cy-cell` | `bind:tap` | `onChangeHeroBg` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E0107 | `components/cy/profile/index.wxml:519` | `cy-cell` | `bind:tap` | `onMoreSetting` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0108 | `components/cy/profile/index.wxml:520` | `cy-cell` | `bind:tap` | `onBlock` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E0109 | `components/cy/profile/index.wxml:527` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0110 | `components/cy/profile/index.wxml:528` | `cy-scene-sheet` | `bind:requestclose` | `onChapterSheetClose` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E0111 | `components/cy/profile/index.wxml:532` | `view` | `bindtap` | `onChapterPick` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0112 | `components/cy/profile/index.wxml:540` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0113 | `components/cy/profile/index.wxml:541` | `cy-scene-sheet` | `bind:requestclose` | `onStationSheetClose` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E0114 | `components/cy/profile/index.wxml:545` | `view` | `bindtap` | `onStationPick` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0115 | `components/cy/profile/index.wxml:554` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0116 | `components/cy/profile/index.wxml:555` | `cy-scene-sheet` | `bind:requestclose` | `onPointsSheetClose` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E0117 | `components/cy/profile/index.wxml:568` | `view` | `bindtap` | `loadPointsTasks` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0118 | `components/cy/profile/index.wxml:586` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0119 | `components/cy/profile/index.wxml:587` | `cy-scene-sheet` | `bind:requestclose` | `requestSceneClose` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E0120 | `components/cy/profile/index.wxml:587` | `cy-scene-sheet` | `bind:back` | `backScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E0121 | `components/cy/profile/index.wxml:588` | `cy-scene-route-content` | `bind:open` | `openChildScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E0122 | `components/cy/profile/index.wxml:588` | `cy-scene-route-content` | `bind:back` | `backScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E0123 | `components/cy/profile/index.wxml:588` | `cy-scene-route-content` | `bind:close` | `closeScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E0124 | `components/cy/profile/index.wxml:588` | `cy-scene-route-content` | `bind:dirtychange` | `onSceneDirtyChange` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E0125 | `components/cy/profile/index.wxml:590` | `cy-btn` | `bindtap` | `submitSceneForm` | 1 | other | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0126 | `components/cy/profile/index.wxml:594` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0127 | `components/cy/profile/index.wxml:595` | `cy-scene-sheet` | `bind:requestclose` | `requestSceneClose` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E0128 | `components/cy/profile/index.wxml:595` | `cy-scene-sheet` | `bind:back` | `backScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E0129 | `components/cy/profile/index.wxml:599` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0130 | `components/cy/profile/index.wxml:600` | `cy-scene-sheet` | `bind:requestclose` | `requestSceneClose` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E0131 | `components/cy/profile/index.wxml:600` | `cy-scene-sheet` | `bind:back` | `backScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E0132 | `components/cy/profile/index.wxml:601` | `cy-scene-qr-coupon` | `bind:close` | `backScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E0133 | `components/cy/profile/index.wxml:604` | `cy-modal` | `bind:confirm` | `confirmSceneDiscard` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E0134 | `components/cy/profile/index.wxml:604` | `cy-modal` | `bind:cancel` | `cancelSceneDiscard` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E0135 | `components/cy/publish-sheet/index.wxml:2` | `view` | `bindtap` | `onMask` | 1 | other | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E0136 | `components/cy/publish-sheet/index.wxml:6` | `view` | `bindtouchstart` | `onTouchStart` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E0137 | `components/cy/publish-sheet/index.wxml:7` | `view` | `bindtouchmove` | `onTouchMove` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E0138 | `components/cy/publish-sheet/index.wxml:8` | `view` | `bindtouchend` | `onTouchEnd` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E0139 | `components/cy/publish-sheet/index.wxml:9` | `view` | `catchtap` | `noop` | 1 | other | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E0140 | `components/cy/publish-sheet/index.wxml:12` | `view` | `bindtap` | `backToCards` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E0141 | `components/cy/publish-sheet/index.wxml:13` | `view` | `bindtap` | `onClose` | 1 | other | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E0142 | `components/cy/publish-sheet/index.wxml:18` | `swiper` | `bindchange` | `onSwiperChange` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E0143 | `components/cy/publish-sheet/index.wxml:27` | `view` | `bindtap` | `openTopicMode` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E0144 | `components/cy/publish-sheet/index.wxml:38` | `view` | `bindtap` | `goTemplate` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E0145 | `components/cy/publish-sheet/index.wxml:49` | `view` | `bindtap` | `goActivity` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E0146 | `components/cy/publish-sheet/index.wxml:68` | `view` | `bindtap` | `goCoupon` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E0147 | `components/cy/publish-sheet/index.wxml:95` | `view` | `bindtap` | `chooseTopicMode` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E0148 | `components/cy/publish-sheet/index.wxml:103` | `view` | `bindtap` | `chooseTopicMode` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E0149 | `components/cy/publish-sheet/index.wxml:119` | `view` | `bindtap` | `chooseSimple` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E0150 | `components/cy/publish-sheet/index.wxml:127` | `view` | `bindtap` | `choosePro` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E0151 | `components/cy/qr-voucher/index.wxml:8` | `view` | `catchtap` | `onRetry` | 1 | other | tap | error,success | NO | PASS | — | PENDING |
| E0152 | `components/cy/qr-voucher/index.wxml:21` | `view` | `catchtap` | `noop` | 1 | other | tap | error,success | NO | PASS | — | PENDING |
| E0153 | `components/cy/qr-voucher/index.wxml:36` | `view` | `catchtap` | `onSecondary` | 1 | other | tap | error,success | NO | PASS | — | PENDING |
| E0154 | `components/cy/qr-voucher/index.wxml:48` | `cy-scene-sheet` | `bind:requestclose` | `onClose` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E0155 | `components/cy/scene-club-edit/index.wxml:3` | `cy-error` | `bind:retry` | `onBack` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E0156 | `components/cy/scene-club-edit/index.wxml:4` | `cy-error` | `bind:retry` | `loadClub` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | — | PENDING |
| E0157 | `components/cy/scene-club-edit/index.wxml:5` | `cy-error` | `bind:retry` | `loadClub` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | — | PENDING |
| E0158 | `components/cy/scene-club-edit/index.wxml:15` | `view` | `bindtap` | `pickLogo` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E0159 | `components/cy/scene-club-edit/index.wxml:19` | `text` | `bindtap` | `pickLogo` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E0160 | `components/cy/scene-club-edit/index.wxml:23` | `view` | `bindtap` | `pickCover` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E0161 | `components/cy/scene-club-edit/index.wxml:36` | `input` | `bindinput` | `onInput` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0162 | `components/cy/scene-club-edit/index.wxml:40` | `input` | `bindinput` | `onInput` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0163 | `components/cy/scene-club-edit/index.wxml:44` | `input` | `bindinput` | `onInput` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0164 | `components/cy/scene-club-edit/index.wxml:48` | `input` | `bindinput` | `onInput` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0165 | `components/cy/scene-club-edit/index.wxml:56` | `textarea` | `bindinput` | `onInput` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0166 | `components/cy/scene-club-edit/index.wxml:66` | `view` | `bindtap` | `pickType` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E0167 | `components/cy/scene-club-edit/index.wxml:75` | `view` | `bindtap` | `toggleDir` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E0168 | `components/cy/scene-club-edit/index.wxml:86` | `cy-switch` | `bindchange` | `onPrioritySignupChange` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0169 | `components/cy/scene-club-edit/index.wxml:93` | `cy-switch` | `bindchange` | `onJoinPolicyChange` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0170 | `components/cy/scene-club-edit/index.wxml:101` | `input` | `bindinput` | `onInput` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0171 | `components/cy/scene-club-edit/index.wxml:111` | `cy-error` | `bind:retry` | `save` | 1 | write | event-contract | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | — | PENDING |
| E0172 | `components/cy/scene-club-edit/index.wxml:112` | `cy-btn` | `bindtap` | `save` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | — | PENDING |
| E0173 | `components/cy/scene-club-edit/index.wxml:115` | `cy-btn` | `bindtap` | `dissolveClub` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E0174 | `components/cy/scene-club-edit/index.wxml:121` | `cy-sheet` | `bind:close` | `closeDissolutionConfirm` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0175 | `components/cy/scene-club-edit/index.wxml:126` | `cy-btn` | `bindtap` | `closeDissolutionConfirm` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E0176 | `components/cy/scene-club-edit/index.wxml:127` | `cy-btn` | `bindtap` | `continueDissolution` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | — | PENDING |
| E0177 | `components/cy/scene-club-edit/index.wxml:128` | `cy-btn` | `bindtap` | `submitMemberConfirmedDissolution` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | — | PENDING |
| E0178 | `components/cy/scene-club-edit/index.wxml:133` | `cy-sheet` | `bind:close` | `closeDissolutionBlockers` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0179 | `components/cy/scene-club-edit/index.wxml:137` | `view` | `catchtap` | `openDissolutionBlockerItem` | 1 | navigation | tap | error,success | NO | PASS | — | PENDING |
| E0180 | `components/cy/scene-club-edit/index.wxml:144` | `cy-sheet` | `bind:close` | `closeDissolutionError` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0181 | `components/cy/scene-club-edit/index.wxml:145` | `cy-error` | `bind:retry` | `closeDissolutionError` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0182 | `components/cy/scene-deep-link/index.wxml:1` | `view` | `catchtouchmove` | `blockTouch` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E0183 | `components/cy/scene-deep-link/index.wxml:12` | `cy-scene-sheet` | `bind:requestclose` | `requestSceneClose` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E0184 | `components/cy/scene-deep-link/index.wxml:13` | `cy-scene-sheet` | `bind:back` | `backScene` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E0185 | `components/cy/scene-deep-link/index.wxml:16` | `cy-scene-play-activity-detail` | `bind:open` | `openChildScene` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0186 | `components/cy/scene-deep-link/index.wxml:16` | `cy-scene-play-activity-detail` | `bind:close` | `closeChildScene` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E0187 | `components/cy/scene-deep-link/index.wxml:19` | `cy-scene-roam-task-list` | `bind:open` | `openChildScene` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0188 | `components/cy/scene-deep-link/index.wxml:19` | `cy-scene-roam-task-list` | `bind:close` | `closeChildScene` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E0189 | `components/cy/scene-deep-link/index.wxml:22` | `cy-scene-roam-poi-detail` | `bind:open` | `openChildScene` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0190 | `components/cy/scene-deep-link/index.wxml:22` | `cy-scene-roam-poi-detail` | `bind:close` | `closeChildScene` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E0191 | `components/cy/scene-deep-link/index.wxml:25` | `cy-scene-roam-discover` | `bind:open` | `openChildScene` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0192 | `components/cy/scene-deep-link/index.wxml:25` | `cy-scene-roam-discover` | `bind:close` | `closeChildScene` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E0193 | `components/cy/scene-deep-link/index.wxml:28` | `cy-scene-roam-history` | `bind:open` | `openChildScene` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0194 | `components/cy/scene-deep-link/index.wxml:28` | `cy-scene-roam-history` | `bind:close` | `closeChildScene` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E0195 | `components/cy/scene-deep-link/index.wxml:31` | `cy-scene-roam-session` | `bind:back` | `backScene` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E0196 | `components/cy/scene-deep-link/index.wxml:31` | `cy-scene-roam-session` | `bind:share` | `onSessionShare` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E0197 | `components/cy/scene-deep-link/index.wxml:31` | `cy-scene-roam-session` | `bind:close` | `closeChildScene` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E0198 | `components/cy/scene-deep-link/index.wxml:34` | `cy-scene-game-coupon-wallet` | `bind:open` | `openChildScene` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0199 | `components/cy/scene-deep-link/index.wxml:34` | `cy-scene-game-coupon-wallet` | `bind:close` | `closeChildScene` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E0200 | `components/cy/scene-deep-link/index.wxml:37` | `cy-scene-qr-coupon` | `bind:close` | `closeChildScene` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E0201 | `components/cy/scene-deep-link/index.wxml:40` | `cy-scene-qr-group-code` | `bind:close` | `closeChildScene` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E0202 | `components/cy/scene-deep-link/index.wxml:43` | `cy-scene-qr-citynode` | `bind:close` | `closeChildScene` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E0203 | `components/cy/scene-deep-link/index.wxml:46` | `cy-scene-qr-ticket` | `bind:close` | `closeChildScene` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E0204 | `components/cy/scene-deep-link/index.wxml:49` | `cy-scene-roam-stamp-album` | `bind:camera` | `openStampCamera` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E0205 | `components/cy/scene-deep-link/index.wxml:49` | `cy-scene-roam-stamp-album` | `bind:close` | `closeChildScene` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E0206 | `components/cy/scene-deep-link/index.wxml:55` | `cy-scene-member-order-detail` | `bind:open` | `openChildScene` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0207 | `components/cy/scene-deep-link/index.wxml:55` | `cy-scene-member-order-detail` | `bind:back` | `backScene` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E0208 | `components/cy/scene-deep-link/index.wxml:55` | `cy-scene-member-order-detail` | `bind:close` | `closeChildScene` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E0209 | `components/cy/scene-deep-link/index.wxml:61` | `cy-scene-member-order-history` | `bind:open` | `openChildScene` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0210 | `components/cy/scene-deep-link/index.wxml:64` | `cy-scene-asset-earnings` | `bind:open` | `openChildScene` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0211 | `components/cy/scene-deep-link/index.wxml:73` | `cy-scene-route-content` | `bind:open` | `openChildScene` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0212 | `components/cy/scene-deep-link/index.wxml:73` | `cy-scene-route-content` | `bind:back` | `backScene` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E0213 | `components/cy/scene-deep-link/index.wxml:73` | `cy-scene-route-content` | `bind:close` | `closeChildScene` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E0214 | `components/cy/scene-deep-link/index.wxml:73` | `cy-scene-route-content` | `bind:dirtychange` | `onSceneDirtyChange` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0215 | `components/cy/scene-deep-link/index.wxml:76` | `cy-btn` | `bindtap` | `submitSceneForm` | 1 | other | tap | error,success | NO | PASS | — | PENDING |
| E0216 | `components/cy/scene-deep-link/index.wxml:79` | `cy-modal` | `bind:confirm` | `confirmSceneDiscard` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E0217 | `components/cy/scene-deep-link/index.wxml:79` | `cy-modal` | `bind:cancel` | `cancelSceneDiscard` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0218 | `components/cy/scene-game-coupon-wallet/index.wxml:2` | `view` | `bindtap` | `selectTab` | 1 | state | tap | success | NO | PASS | #783,#782 | PENDING |
| E0219 | `components/cy/scene-game-coupon-wallet/index.wxml:4` | `cy-error` | `bind:retry` | `retry` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0220 | `components/cy/scene-game-coupon-wallet/index.wxml:7` | `view` | `bindtap` | `openCode` | 1 | other | tap | error,success | NO | PASS | #783,#782 | PENDING |
| E0221 | `components/cy/scene-member-invite-history/index.wxml:20` | `cy-error` | `bind:retry` | `retry` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | — | PENDING |
| E0222 | `components/cy/scene-member-invite-history/index.wxml:45` | `view` | `bindtap` | `loadMore` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | — | PENDING |
| E0223 | `components/cy/scene-member-invite-history/index.wxml:47` | `view` | `bindtap` | `loadMore` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | — | PENDING |
| E0224 | `components/cy/scene-member-order-detail/index.wxml:11` | `view` | `bindtap` | `reloadOrder` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #783 | PENDING |
| E0225 | `components/cy/scene-member-order-detail/index.wxml:12` | `view` | `bindtap` | `backToOrderList` | 1 | navigation | tap | error,success | NO | PASS | #783 | PENDING |
| E0226 | `components/cy/scene-member-order-detail/index.wxml:32` | `view` | `bindtap` | `goRoute` | 1 | navigation | tap | error,success | NO | PASS | #783 | PENDING |
| E0227 | `components/cy/scene-member-order-detail/index.wxml:37` | `view` | `bindtap` | `goActivity` | 1 | navigation | tap | error,success | NO | PASS | #783 | PENDING |
| E0228 | `components/cy/scene-member-order-detail/index.wxml:46` | `view` | `bindtap` | `startOrderTeam` | 1 | write | tap | duplicate-trigger,error,success,timeout | NO | PASS | #783 | PENDING |
| E0229 | `components/cy/scene-member-order-detail/index.wxml:77` | `view` | `bindtap` | `openMeetingPoint` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #783 | PENDING |
| E0230 | `components/cy/scene-member-order-detail/index.wxml:124` | `view` | `bindtap` | `copyTransactionId` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #783 | PENDING |
| E0231 | `components/cy/scene-member-order-detail/index.wxml:199` | `view` | `bindtap` | `followClub` | 1 | write | tap | duplicate-trigger,error,success,timeout | NO | PASS | #783 | PENDING |
| E0232 | `components/cy/scene-member-order-detail/index.wxml:201` | `view` | `bindtap` | `joinClub` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783 | PENDING |
| E0233 | `components/cy/scene-member-order-detail/index.wxml:206` | `view` | `bindtap` | `goNextEdition` | 1 | navigation | tap | error,success | NO | PASS | #783 | PENDING |
| E0234 | `components/cy/scene-member-order-detail/index.wxml:219` | `view` | `bindtap` | `cancelRegistration` | 1 | write | tap | duplicate-trigger,error,modal-cancelled,success,timeout | NO | PASS | #783 | PENDING |
| E0235 | `components/cy/scene-member-order-detail/index.wxml:221` | `view` | `bindtap` | `goSignInfo` | 1 | navigation | tap | error,success | NO | PASS | #783 | PENDING |
| E0236 | `components/cy/scene-member-order-detail/index.wxml:227` | `view` | `bindtap` | `payOrder` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783 | PENDING |
| E0237 | `components/cy/scene-member-order-detail/index.wxml:228` | `view` | `bindtap` | `goSignInfo` | 1 | navigation | tap | error,success | NO | PASS | #783 | PENDING |
| E0238 | `components/cy/scene-member-order-history/index.wxml:6` | `cy-tabs` | `bind:change` | `switchTab` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0239 | `components/cy/scene-member-order-history/index.wxml:18` | `cy-error` | `bind:retry` | `getList` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0240 | `components/cy/scene-member-order-history/index.wxml:23` | `cy-empty` | `bind:cta` | `goDiscoverRoutes` | 1 | navigation | event-contract | error,success | NO | PASS | #783,#782 | PENDING |
| E0241 | `components/cy/scene-member-order-history/index.wxml:29` | `view` | `bindtap` | `openDetail` | 1 | other | tap | error,success | NO | PASS | #783,#782 | PENDING |
| E0242 | `components/cy/scene-member-order-history/index.wxml:64` | `view` | `catchtap` | `cancelOrder` | 1 | write | tap | duplicate-trigger,error,modal-cancelled,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0243 | `components/cy/scene-member-order-history/index.wxml:65` | `view` | `catchtap` | `payOrder` | 1 | navigation | tap | error,success | NO | PASS | #783,#782 | PENDING |
| E0244 | `components/cy/scene-member-order-history/index.wxml:68` | `view` | `catchtap` | `cancelOrder` | 1 | write | tap | duplicate-trigger,error,modal-cancelled,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0245 | `components/cy/scene-member-order-history/index.wxml:69` | `view` | `catchtap` | `openDetail` | 1 | other | tap | error,success | NO | PASS | #783,#782 | PENDING |
| E0246 | `components/cy/scene-member-order-history/index.wxml:72` | `view` | `catchtap` | `openDetail` | 1 | other | tap | error,success | NO | PASS | #783,#782 | PENDING |
| E0247 | `components/cy/scene-member-withdraw-history/index.wxml:16` | `view` | `bindtap` | `loadMore` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #782 | PENDING |
| E0248 | `components/cy/scene-member-withdraw-history/index.wxml:17` | `cy-error` | `bind:retry` | `onPageRetry` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #782 | PENDING |
| E0249 | `components/cy/scene-member-withdraw-history/index.wxml:20` | `cy-error` | `bind:retry` | `onRetry` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #782 | PENDING |
| E0250 | `components/cy/scene-merchant-profit/index.wxml:10` | `cy-btn` | `bind:tap` | `goWithdraw` | 1 | navigation | tap | error,success | NO | PASS | #783 | PENDING |
| E0251 | `components/cy/scene-merchant-profit/index.wxml:17` | `view` | `bindtap` | `goSettlementDetail` | 1 | navigation | tap | error,success | NO | PASS | #783 | PENDING |
| E0252 | `components/cy/scene-merchant-profit/index.wxml:37` | `cy-error` | `bind:retry` | `onRetry` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783 | PENDING |
| E0253 | `components/cy/scene-play-activity-detail/index.wxml:3` | `cy-error` | `bind:retry` | `retry` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0254 | `components/cy/scene-play-activity-detail/index.wxml:4` | `cy-empty` | `bind:cta` | `close` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0255 | `components/cy/scene-play-activity-detail/index.wxml:5` | `cy-empty` | `bind:cta` | `close` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0256 | `components/cy/scene-play-activity-detail/index.wxml:27` | `view` | `bindtap` | `openLocation` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #787,#783,#782 | PENDING |
| E0257 | `components/cy/scene-play-activity-detail/index.wxml:39` | `view` | `bindtap` | `goUserInfo` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0258 | `components/cy/scene-play-activity-detail/index.wxml:48` | `view` | `bindtap` | `goUserInfo` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0259 | `components/cy/scene-play-activity-detail/index.wxml:57` | `view` | `bindtap` | `goTemplateDetail` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0260 | `components/cy/scene-play-activity-detail/index.wxml:68` | `view` | `bindtap` | `selectTicket` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E0261 | `components/cy/scene-play-activity-detail/index.wxml:71` | `view` | `catchtap` | `toggleTicket` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E0262 | `components/cy/scene-play-activity-detail/index.wxml:76` | `view` | `catchtap` | `goUserInfo` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0263 | `components/cy/scene-play-activity-detail/index.wxml:91` | `cy-btn` | `bindtap` | `openComment` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E0264 | `components/cy/scene-play-activity-detail/index.wxml:106` | `text` | `bindtap` | `closeCancel` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E0265 | `components/cy/scene-play-activity-detail/index.wxml:108` | `textarea` | `bindinput` | `onCancelReasonInput` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E0266 | `components/cy/scene-play-activity-detail/index.wxml:109` | `cy-btn` | `bindtap` | `submitCancel` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0267 | `components/cy/scene-play-activity-detail/index.wxml:113` | `text` | `bindtap` | `closeComment` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E0268 | `components/cy/scene-play-activity-detail/index.wxml:114` | `view` | `bindtap` | `changeRating` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E0269 | `components/cy/scene-play-activity-detail/index.wxml:115` | `textarea` | `bindinput` | `onCommentInput` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E0270 | `components/cy/scene-play-activity-detail/index.wxml:117` | `image` | `bindtap` | `previewCommentImage` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #787,#783,#782 | PENDING |
| E0271 | `components/cy/scene-play-activity-detail/index.wxml:117` | `view` | `bindtap` | `deleteCommentImage` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E0272 | `components/cy/scene-play-activity-detail/index.wxml:118` | `view` | `bindtap` | `chooseCommentImages` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E0273 | `components/cy/scene-play-activity-detail/index.wxml:120` | `cy-btn` | `bindtap` | `submitComment` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0274 | `components/cy/scene-play-activity-detail/index.wxml:124` | `button` | `bindtap` | `trackShare` | 1 | other | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0275 | `components/cy/scene-play-activity-detail/index.wxml:126` | `cy-btn` | `bindtap` | `openCancel` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E0276 | `components/cy/scene-play-activity-detail/index.wxml:127` | `cy-btn` | `bindtap` | `goPlay` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0277 | `components/cy/scene-play-activity-detail/index.wxml:128` | `cy-btn` | `bindtap` | `openSignup` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0278 | `components/cy/scene-qr-citynode/index.wxml:2` | `cy-empty` | `bind:cta` | `close` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E0279 | `components/cy/scene-qr-citynode/index.wxml:3` | `cy-qr-voucher` | `bind:close` | `close` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E0280 | `components/cy/scene-qr-citynode/index.wxml:3` | `cy-qr-voucher` | `bind:retry` | `retry` | 1 | write | event-contract | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | — | PENDING |
| E0281 | `components/cy/scene-qr-coupon/index.wxml:2` | `cy-empty` | `bind:cta` | `close` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E0282 | `components/cy/scene-qr-coupon/index.wxml:3` | `cy-qr-voucher` | `bind:close` | `close` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E0283 | `components/cy/scene-qr-coupon/index.wxml:3` | `cy-qr-voucher` | `bind:retry` | `retry` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | — | PENDING |
| E0284 | `components/cy/scene-qr-coupon/index.wxml:3` | `cy-qr-voucher` | `bind:secondary` | `retry` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | — | PENDING |
| E0285 | `components/cy/scene-qr-group-code/index.wxml:5` | `view` | `bindtap` | `chooseActivity` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | — | PENDING |
| E0286 | `components/cy/scene-qr-group-code/index.wxml:7` | `cy-empty` | `bind:cta` | `close` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E0287 | `components/cy/scene-qr-group-code/index.wxml:8` | `cy-empty` | `bind:cta` | `close` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E0288 | `components/cy/scene-qr-group-code/index.wxml:9` | `cy-qr-voucher` | `bind:close` | `close` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E0289 | `components/cy/scene-qr-group-code/index.wxml:9` | `cy-qr-voucher` | `bind:retry` | `retry` | 1 | write | event-contract | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | — | PENDING |
| E0290 | `components/cy/scene-roam-discover/index.wxml:2` | `input` | `bindinput` | `onKeyword` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E0291 | `components/cy/scene-roam-discover/index.wxml:2` | `input` | `bindconfirm` | `search` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0292 | `components/cy/scene-roam-discover/index.wxml:2` | `text` | `bindtap` | `search` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0293 | `components/cy/scene-roam-discover/index.wxml:3` | `text` | `bindtap` | `toggleTag` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0294 | `components/cy/scene-roam-discover/index.wxml:5` | `cy-error` | `bind:retry` | `retry` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0295 | `components/cy/scene-roam-discover/index.wxml:6` | `cy-merchant-card` | `bind:tap` | `openProfile` | 1 | other | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0296 | `components/cy/scene-roam-discover/index.wxml:6` | `cy-merchant-card` | `bind:action` | `openProfile` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0297 | `components/cy/scene-roam-history/index.wxml:10` | `view` | `bindtap` | `openSession` | 1 | other | tap | error,success | NO | PASS | — | PENDING |
| E0298 | `components/cy/scene-roam-history/index.wxml:35` | `cy-empty` | `bind:cta` | `closeToRoam` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E0299 | `components/cy/scene-roam-poi-detail/index.wxml:2` | `cy-error` | `bind:retry` | `retry` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0300 | `components/cy/scene-roam-poi-detail/index.wxml:3` | `cy-empty` | `bind:cta` | `close` | 1 | other | event-contract | error,success | NO | PASS | #783,#782 | PENDING |
| E0301 | `components/cy/scene-roam-poi-detail/index.wxml:10` | `view` | `bindtap` | `openFeatured` | 1 | other | tap | error,success | NO | PASS | #783,#782 | PENDING |
| E0302 | `components/cy/scene-roam-poi-detail/index.wxml:11` | `cy-error` | `bind:retry` | `recoverInteraction` | 1 | external | event-contract | duplicate-trigger,error,forbidden-owner,getLocation-denied,openSetting-cancelled,openSetting-denied,scanCode-cancelled,scanCode-denied,success,timeout | YES | PASS | #783,#782 | PENDING |
| E0303 | `components/cy/scene-roam-poi-detail/index.wxml:11` | `text` | `bindtap` | `toggleFavorite` | 1 | write | tap | duplicate-trigger,error,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0304 | `components/cy/scene-roam-poi-detail/index.wxml:11` | `text` | `bindtap` | `openLocation` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #783,#782 | PENDING |
| E0305 | `components/cy/scene-roam-poi-detail/index.wxml:11` | `text` | `bindtap` | `startInteract` | 5 | external | tap | cancelled,chooseImage-denied,denied,duplicate-trigger,error,first-error,first-timeout,forbidden-owner,getLocation-denied,modal-cancelled,scanCode-denied,second-error-after-first,second-timeout-after-first,success,timeout | YES | PASS | #783,#782 | PENDING |
| E0306 | `components/cy/scene-roam-poi-detail/index.wxml:12` | `image` | `bindtap` | `previewGallery` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #783,#782 | PENDING |
| E0307 | `components/cy/scene-roam-poi-detail/index.wxml:14` | `view` | `bindtap` | `openLocation` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #783,#782 | PENDING |
| E0308 | `components/cy/scene-roam-poi-detail/index.wxml:16` | `cy-error` | `bind:retry` | `retry` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0309 | `components/cy/scene-roam-session/index.wxml:5` | `cy-error` | `bind:retry` | `retry` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0310 | `components/cy/scene-roam-session/index.wxml:6` | `cy-empty` | `bind:cta` | `back` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E0311 | `components/cy/scene-roam-session/index.wxml:43` | `view` | `bindtap` | `share` | 1 | other | tap | error,success | NO | PASS | — | PENDING |
| E0312 | `components/cy/scene-roam-stamp-album/index.wxml:6` | `cy-error` | `bind:retry` | `retryLoad` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783 | PENDING |
| E0313 | `components/cy/scene-roam-stamp-album/index.wxml:8` | `view` | `bindtap` | `openCamera` | 1 | other | tap | error,success | NO | PASS | #783 | PENDING |
| E0314 | `components/cy/scene-roam-stamp-album/index.wxml:10` | `view` | `bindtap` | `retryLoad` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #783 | PENDING |
| E0315 | `components/cy/scene-roam-stamp-album/index.wxml:11` | `view` | `bindtap` | `openCamera` | 1 | other | tap | error,success | NO | PASS | #783 | PENDING |
| E0316 | `components/cy/scene-roam-task-list/index.wxml:2` | `input` | `bindinput` | `onKeyword` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0317 | `components/cy/scene-roam-task-list/index.wxml:3` | `view` | `bindtap` | `selectTab` | 1 | state | tap | success | NO | PASS | #783,#782 | PENDING |
| E0318 | `components/cy/scene-roam-task-list/index.wxml:6` | `cy-error` | `bind:retry` | `retry` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0319 | `components/cy/scene-roam-task-list/index.wxml:7` | `view` | `bindtap` | `openDetail` | 1 | other | tap | error,success | NO | PASS | #783,#782 | PENDING |
| E0320 | `components/cy/scene-route-content/index.wxml:8` | `cy-scene-club-edit` | `bind:back` | `onNestedBack` | 1 | other | event-contract | error,success | NO | PASS | #787,#782 | PENDING |
| E0321 | `components/cy/scene-route-content/index.wxml:9` | `cy-scene-club-edit` | `bind:saved` | `onNestedSaved` | 1 | other | event-contract | error,success | NO | PASS | #787,#782 | PENDING |
| E0322 | `components/cy/scene-route-content/index.wxml:10` | `cy-scene-club-edit` | `bind:dissolved` | `onNestedDissolved` | 1 | other | event-contract | error,success | NO | PASS | #787,#782 | PENDING |
| E0323 | `components/cy/scene-route-content/index.wxml:11` | `cy-scene-club-edit` | `bind:dirtychange` | `onNestedDirtyChange` | 1 | other | event-contract | error,success | NO | PASS | #787,#782 | PENDING |
| E0324 | `components/cy/scene-route-content/index.wxml:15` | `cy-error` | `bind:retry` | `reload` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#782 | PENDING |
| E0325 | `components/cy/scene-route-content/index.wxml:40` | `image` | `bindtap` | `onItemTap` | 1 | navigation | tap | error,success | NO | PASS | #787,#782 | PENDING |
| E0326 | `components/cy/scene-route-content/index.wxml:46` | `button` | `bindtap` | `topicUnlike` | 1 | write | tap | duplicate-trigger,error,modal-cancelled,success,timeout | NO | PASS | #787,#782 | PENDING |
| E0327 | `components/cy/scene-route-content/index.wxml:54` | `view` | `bindtap` | `onItemTap` | 1 | navigation | tap | error,success | NO | PASS | #787,#782 | PENDING |
| E0328 | `components/cy/scene-route-content/index.wxml:72` | `input` | `bindinput` | `onFieldInput` | 1 | state | event-contract | success | NO | PASS | #787,#782 | PENDING |
| E0329 | `components/cy/scene-route-content/index.wxml:75` | `input` | `bindinput` | `onFieldInput` | 1 | state | event-contract | success | NO | PASS | #787,#782 | PENDING |
| E0330 | `components/cy/scene-route-content/index.wxml:77` | `input` | `bindinput` | `onFieldInput` | 1 | state | event-contract | success | NO | PASS | #787,#782 | PENDING |
| E0331 | `components/cy/scene-route-content/index.wxml:79` | `input` | `bindinput` | `onFieldInput` | 1 | state | event-contract | success | NO | PASS | #787,#782 | PENDING |
| E0332 | `components/cy/scene-route-content/index.wxml:81` | `input` | `bindinput` | `onFieldInput` | 1 | state | event-contract | success | NO | PASS | #787,#782 | PENDING |
| E0333 | `components/cy/scene-route-content/index.wxml:86` | `input` | `bindinput` | `onFieldInput` | 1 | state | event-contract | success | NO | PASS | #787,#782 | PENDING |
| E0334 | `components/cy/scene-route-content/index.wxml:88` | `input` | `bindinput` | `onFieldInput` | 1 | state | event-contract | success | NO | PASS | #787,#782 | PENDING |
| E0335 | `components/cy/scene-route-content/index.wxml:93` | `input` | `bindinput` | `onFieldInput` | 1 | state | event-contract | success | NO | PASS | #787,#782 | PENDING |
| E0336 | `components/cy/scene-route-content/index.wxml:95` | `textarea` | `bindinput` | `onFieldInput` | 1 | state | event-contract | success | NO | PASS | #787,#782 | PENDING |
| E0337 | `components/cy/scene-route-content/index.wxml:100` | `input` | `bindinput` | `onFieldInput` | 1 | state | event-contract | success | NO | PASS | #787,#782 | PENDING |
| E0338 | `components/cy/scene-route-content/index.wxml:102` | `input` | `bindinput` | `onFieldInput` | 1 | state | event-contract | success | NO | PASS | #787,#782 | PENDING |
| E0339 | `components/cy/scene-route-content/index.wxml:103` | `input` | `bindinput` | `onFieldInput` | 1 | state | event-contract | success | NO | PASS | #787,#782 | PENDING |
| E0340 | `components/cy/scene-route-content/index.wxml:104` | `input` | `bindinput` | `onFieldInput` | 1 | state | event-contract | success | NO | PASS | #787,#782 | PENDING |
| E0341 | `components/cy/scene-route-content/index.wxml:105` | `input` | `bindinput` | `onFieldInput` | 1 | state | event-contract | success | NO | PASS | #787,#782 | PENDING |
| E0342 | `components/cy/scene-route-content/index.wxml:106` | `input` | `bindinput` | `onFieldInput` | 1 | state | event-contract | success | NO | PASS | #787,#782 | PENDING |
| E0343 | `components/cy/scene-route-content/index.wxml:109` | `input` | `bindinput` | `onFieldInput` | 1 | state | event-contract | success | NO | PASS | #787,#782 | PENDING |
| E0344 | `components/cy/scene-route-content/index.wxml:110` | `input` | `bindinput` | `onFieldInput` | 1 | state | event-contract | success | NO | PASS | #787,#782 | PENDING |
| E0345 | `components/cy/scene-route-content/index.wxml:111` | `input` | `bindinput` | `onFieldInput` | 1 | state | event-contract | success | NO | PASS | #787,#782 | PENDING |
| E0346 | `components/cy/scene-route-content/index.wxml:112` | `input` | `bindinput` | `onFieldInput` | 1 | state | event-contract | success | NO | PASS | #787,#782 | PENDING |
| E0347 | `components/cy/scene-route-content/index.wxml:113` | `input` | `bindinput` | `onFieldInput` | 1 | state | event-contract | success | NO | PASS | #787,#782 | PENDING |
| E0348 | `components/cy/scene-route-content/index.wxml:114` | `input` | `bindinput` | `onFieldInput` | 1 | state | event-contract | success | NO | PASS | #787,#782 | PENDING |
| E0349 | `components/cy/scene-route-content/index.wxml:115` | `input` | `bindinput` | `onFieldInput` | 1 | state | event-contract | success | NO | PASS | #787,#782 | PENDING |
| E0350 | `components/cy/scene-route-content/index.wxml:118` | `textarea` | `bindinput` | `onFieldInput` | 1 | state | event-contract | success | NO | PASS | #787,#782 | PENDING |
| E0351 | `components/cy/scene-route-content/index.wxml:122` | `input` | `bindinput` | `onFieldInput` | 1 | state | event-contract | success | NO | PASS | #787,#782 | PENDING |
| E0352 | `components/cy/scene-route-content/index.wxml:123` | `input` | `bindinput` | `onFieldInput` | 1 | state | event-contract | success | NO | PASS | #787,#782 | PENDING |
| E0353 | `components/cy/scene-route-content/index.wxml:124` | `input` | `bindinput` | `onFieldInput` | 1 | state | event-contract | success | NO | PASS | #787,#782 | PENDING |
| E0354 | `components/cy/scene-route-content/index.wxml:125` | `input` | `bindinput` | `onFieldInput` | 1 | state | event-contract | success | NO | PASS | #787,#782 | PENDING |
| E0355 | `components/cy/scene-route-content/index.wxml:126` | `input` | `bindinput` | `onFieldInput` | 1 | state | event-contract | success | NO | PASS | #787,#782 | PENDING |
| E0356 | `components/cy/scene-route-content/index.wxml:127` | `textarea` | `bindinput` | `onFieldInput` | 1 | state | event-contract | success | NO | PASS | #787,#782 | PENDING |
| E0357 | `components/cy/scene-route-content/index.wxml:131` | `input` | `bindinput` | `onFieldInput` | 1 | state | event-contract | success | NO | PASS | #787,#782 | PENDING |
| E0358 | `components/cy/scene-route-content/index.wxml:132` | `input` | `bindinput` | `onFieldInput` | 1 | state | event-contract | success | NO | PASS | #787,#782 | PENDING |
| E0359 | `components/cy/scene-route-content/index.wxml:133` | `input` | `bindinput` | `onFieldInput` | 1 | state | event-contract | success | NO | PASS | #787,#782 | PENDING |
| E0360 | `components/cy/scene-route-content/index.wxml:134` | `input` | `bindinput` | `onFieldInput` | 1 | state | event-contract | success | NO | PASS | #787,#782 | PENDING |
| E0361 | `components/cy/scene-route-content/index.wxml:135` | `textarea` | `bindinput` | `onFieldInput` | 1 | state | event-contract | success | NO | PASS | #787,#782 | PENDING |
| E0362 | `components/cy/scene-route-content/index.wxml:139` | `input` | `bindinput` | `onFieldInput` | 1 | state | event-contract | success | NO | PASS | #787,#782 | PENDING |
| E0363 | `components/cy/scene-route-content/index.wxml:140` | `input` | `bindinput` | `onFieldInput` | 1 | state | event-contract | success | NO | PASS | #787,#782 | PENDING |
| E0364 | `components/cy/scene-route-content/index.wxml:141` | `input` | `bindinput` | `onFieldInput` | 1 | state | event-contract | success | NO | PASS | #787,#782 | PENDING |
| E0365 | `components/cy/scene-route-content/index.wxml:142` | `input` | `bindinput` | `onFieldInput` | 1 | state | event-contract | success | NO | PASS | #787,#782 | PENDING |
| E0366 | `components/cy/scene-route-content/index.wxml:143` | `input` | `bindinput` | `onFieldInput` | 1 | state | event-contract | success | NO | PASS | #787,#782 | PENDING |
| E0367 | `components/cy/scene-route-content/index.wxml:144` | `input` | `bindinput` | `onFieldInput` | 1 | state | event-contract | success | NO | PASS | #787,#782 | PENDING |
| E0368 | `components/cy/scene-route-content/index.wxml:145` | `textarea` | `bindinput` | `onFieldInput` | 1 | state | event-contract | success | NO | PASS | #787,#782 | PENDING |
| E0369 | `components/cy/scene-route-content/index.wxml:150` | `button` | `bindtap` | `openSecondary` | 1 | navigation | tap | error,success | NO | PASS | #787,#782 | PENDING |
| E0370 | `components/cy/scene-route-content/index.wxml:151` | `button` | `bindtap` | `openSecondary` | 1 | navigation | tap | error,success | NO | PASS | #787,#782 | PENDING |
| E0371 | `components/cy/scene-settings-deregister/index.wxml:6` | `cy-btn` | `bindtap` | `cancel` | 1 | other | tap | error,success | NO | PASS | — | PENDING |
| E0372 | `components/cy/scene-settings-deregister/index.wxml:11` | `text` | `bindtap` | `goCancellationNotice` | 1 | navigation | tap | error,success | NO | PASS | — | PENDING |
| E0373 | `components/cy/scene-settings-deregister/index.wxml:12` | `checkbox-group` | `bindchange` | `toggleConfirmed` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E0374 | `components/cy/scene-settings-deregister/index.wxml:13` | `input` | `bindinput` | `inputCode` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E0375 | `components/cy/scene-settings-deregister/index.wxml:13` | `text` | `bindtap` | `sendSms` | 1 | other | tap | error,success | NO | PASS | — | PENDING |
| E0376 | `components/cy/scene-settings-deregister/index.wxml:14` | `cy-btn` | `bindtap` | `apply` | 1 | other | tap | error,success | NO | PASS | — | PENDING |
| E0377 | `components/cy/scene-settings-deregister/index.wxml:26` | `cy-btn` | `bindtap` | `loadStatus` | 1 | other | tap | error,success | NO | PASS | — | PENDING |
| E0378 | `components/cy/scene-settings-deregister/index.wxml:32` | `cy-agreement-sheet` | `bind:close` | `closeNoticeSheet` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E0379 | `components/cy/scene-sheet/index.wxml:2` | `view` | `bindtap` | `onMask` | 1 | other | tap | error,success | NO | PASS | #787 | PENDING |
| E0380 | `components/cy/scene-sheet/index.wxml:4` | `view` | `catchtap` | `noop` | 1 | other | tap | error,success | NO | PASS | #787 | PENDING |
| E0381 | `components/cy/scene-sheet/index.wxml:6` | `view` | `bindtap` | `onBack` | 1 | other | tap | error,success | NO | PASS | #787 | PENDING |
| E0382 | `components/cy/scene-sheet/index.wxml:8` | `view` | `bindtap` | `onClose` | 1 | other | tap | error,success | NO | PASS | #787 | PENDING |
| E0383 | `components/cy/search/index.wxml:12` | `input` | `bindinput` | `onInput` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E0384 | `components/cy/search/index.wxml:13` | `input` | `bindconfirm` | `onConfirm` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E0385 | `components/cy/search/index.wxml:14` | `input` | `bindfocus` | `onFocus` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0386 | `components/cy/search/index.wxml:15` | `input` | `bindblur` | `onBlur` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0387 | `components/cy/search/index.wxml:19` | `view` | `catchtap` | `onClear` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E0388 | `components/cy/sheet/index.wxml:2` | `view` | `bindtap` | `onMask` | 1 | other | tap | error,success | NO | PASS | #787,#782 | PENDING |
| E0389 | `components/cy/sheet/index.wxml:3` | `view` | `catchtap` | `noop` | 1 | other | tap | error,success | NO | PASS | #787,#782 | PENDING |
| E0390 | `components/cy/sheet/index.wxml:10` | `view` | `bindtap` | `onBack` | 1 | other | tap | error,success | NO | PASS | #787,#782 | PENDING |
| E0391 | `components/cy/sheet/index.wxml:13` | `view` | `bindtap` | `onClose` | 1 | other | tap | error,success | NO | PASS | #787,#782 | PENDING |
| E0392 | `components/cy/sheet/index.wxml:28` | `view` | `bindtap` | `onClose` | 1 | other | tap | error,success | NO | PASS | #787,#782 | PENDING |
| E0393 | `components/cy/slide-confirm/index.wxml:2` | `view` | `bindtap` | `onTap` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E0394 | `components/cy/slide-confirm/index.wxml:2` | `view` | `bindtouchstart` | `onStart` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E0395 | `components/cy/slide-confirm/index.wxml:2` | `view` | `bindtouchmove` | `onMove` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0396 | `components/cy/slide-confirm/index.wxml:2` | `view` | `bindtouchend` | `onEnd` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0397 | `components/cy/slide-confirm/index.wxml:2` | `view` | `bindtouchcancel` | `onCancel` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0398 | `components/cy/switch/index.wxml:6` | `switch` | `bindchange` | `onChange` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E0399 | `components/cy/tabs/index.wxml:9` | `view` | `bindtap` | `onTab` | 1 | other | tap | error,success | NO | PASS | — | PENDING |
| E0400 | `components/cy/tabs/index.wxml:22` | `view` | `bindtap` | `onTab` | 1 | other | tap | error,success | NO | PASS | — | PENDING |
| E0401 | `components/cy/tabs/index.wxml:32` | `view` | `bindtap` | `onTab` | 1 | other | tap | error,success | NO | PASS | — | PENDING |
| E0402 | `components/cy/upload/index.wxml:5` | `image` | `bindtap` | `onPreview` | 1 | other | tap | error,success | NO | PASS | #787 | PENDING |
| E0403 | `components/cy/upload/index.wxml:12` | `view` | `catchtap` | `onRetry` | 1 | other | tap | error,success | NO | PASS | #787 | PENDING |
| E0404 | `components/cy/upload/index.wxml:17` | `view` | `catchtap` | `onRemove` | 1 | other | tap | error,success | NO | PASS | #787 | PENDING |
| E0405 | `components/cy/upload/index.wxml:23` | `view` | `bindtap` | `onAdd` | 1 | other | tap | error,success | NO | PASS | #787 | PENDING |
| E0406 | `components/tabBar/index.wxml:7` | `view` | `bindtap` | `onChange` | 1 | navigation | tap | error,success | NO | PASS | — | PENDING |
| E0407 | `pages/activity/baoming/baoming.wxml:14` | `view` | `bindtap` | `checkAndShowAddressPopup` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E0408 | `pages/activity/baoming/baoming.wxml:20` | `view` | `bindtap` | `checkAndShowAddressPopup` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E0409 | `pages/activity/baoming/baoming.wxml:35` | `cy-error` | `bind:retry` | `retryActivityInfo` | 1 | write | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E0410 | `pages/activity/baoming/baoming.wxml:85` | `view` | `bindtap` | `toggleDiscount` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783 | PENDING |
| E0411 | `pages/activity/baoming/baoming.wxml:97` | `cy-sheet` | `bind:close` | `hideAddressPopup` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E0412 | `pages/activity/baoming/baoming.wxml:102` | `view` | `catchtap` | `selectAddress` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E0413 | `pages/activity/baoming/baoming.wxml:114` | `cy-btn` | `bindtap` | `goToAddAddress` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E0414 | `pages/activity/baoming/baoming.wxml:119` | `cy-btn` | `bindtap` | `goToAddressList` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E0415 | `pages/activity/baoming/baoming.wxml:129` | `view` | `bindtap` | `selectSignupKind` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E0416 | `pages/activity/baoming/baoming.wxml:130` | `view` | `bindtap` | `selectSignupKind` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E0417 | `pages/activity/baoming/baoming.wxml:132` | `picker` | `bindchange` | `onTeamMemberChange` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E0418 | `pages/activity/baoming/baoming.wxml:145` | `view` | `bindtap` | `toggleHostShare` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E0419 | `pages/activity/baoming/baoming.wxml:151` | `cy-btn` | `bindtap` | `handlePayment` | 12 | external | tap | consent-error,consent-timeout,create-error,create-timeout,duplicate-trigger,payment-cancelled,payment-error,quote-error-then-create-error,quote-timeout-then-create-error,subscription-cancelled,success,team-error,team-timeout,verify-error,verify-timeout | YES | PASS | #787,#783 | PENDING |
| E0420 | `pages/activity/baoming/baoming.wxml:151` | `cy-btn` | `bind:disabledtap` | `handlePayment` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E0421 | `pages/activity/baoming/baoming.wxml:159` | `view` | `catchtouchmove` | `noop` | 1 | other | event-contract | error,success | NO | PASS | #787,#783 | PENDING |
| E0422 | `pages/activity/baoming/baoming.wxml:167` | `cy-btn` | `bindtap` | `retrySignupTeam` | 1 | write | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E0423 | `pages/activity/baoming/baoming.wxml:168` | `view` | `bindtap` | `goToOrderDetail` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E0424 | `pages/activity/baoming/baoming.wxml:172` | `cy-btn` | `bindtap` | `startPostSignupTeam` | 1 | write | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E0425 | `pages/activity/baoming/baoming.wxml:173` | `view` | `bindtap` | `goToOrderDetail` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E0426 | `pages/activity/detail/index.wxml:3` | `cy-nav-bar` | `bind:back` | `goBack` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E0427 | `pages/activity/detail/index.wxml:6` | `cy-scene-play-activity-detail` | `bind:loaded` | `onSceneLoaded` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0428 | `pages/activity/detail/index.wxml:6` | `cy-scene-play-activity-detail` | `bind:open` | `openChildScene` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E0429 | `pages/activity/detail/index.wxml:6` | `cy-scene-play-activity-detail` | `bind:close` | `goBack` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E0430 | `pages/activity/list/index.wxml:7` | `cy-nav-bar` | `bind:back` | `onNavBack` | 1 | navigation | event-contract | error,success | NO | PASS | #783,#782 | PENDING |
| E0431 | `pages/activity/list/index.wxml:11` | `cy-search` | `bind:input` | `onSearchInput` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0432 | `pages/activity/list/index.wxml:13` | `cy-tabs` | `bind:change` | `onTab` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0433 | `pages/activity/list/index.wxml:23` | `view` | `bindtap` | `goMine` | 1 | navigation | tap | error,success | NO | PASS | #783,#782 | PENDING |
| E0434 | `pages/activity/list/index.wxml:24` | `view` | `bindtap` | `goInbox` | 1 | navigation | tap | error,success | NO | PASS | #783,#782 | PENDING |
| E0435 | `pages/activity/list/index.wxml:31` | `cy-error` | `bind:retry` | `onRetry` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0436 | `pages/activity/list/index.wxml:34` | `view` | `bindtap` | `openDetail` | 1 | navigation | tap | error,success | NO | PASS | #783,#782 | PENDING |
| E0437 | `pages/activity/list/index.wxml:67` | `cy-btn` | `bindtap` | `goPublish` | 1 | state | tap | success | NO | PASS | #783,#782 | PENDING |
| E0438 | `pages/activity/list/index.wxml:73` | `cy-sheet` | `bind:requestclose` | `pubRequestClose` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0439 | `pages/activity/list/index.wxml:73` | `cy-sheet` | `bind:close` | `pubOnSheetClosed` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0440 | `pages/activity/list/index.wxml:76` | `cy-tabs` | `bind:change` | `pubSetMode` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0441 | `pages/activity/list/index.wxml:82` | `input` | `bindinput` | `pubOnField` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0442 | `pages/activity/list/index.wxml:83` | `input` | `bindinput` | `pubOnField` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0443 | `pages/activity/list/index.wxml:84` | `input` | `bindinput` | `pubOnField` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0444 | `pages/activity/list/index.wxml:86` | `picker` | `bindchange` | `pubOnCategory` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0445 | `pages/activity/list/index.wxml:89` | `picker` | `bindchange` | `pubOnStart` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0446 | `pages/activity/list/index.wxml:92` | `picker` | `bindchange` | `pubOnEnd` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0447 | `pages/activity/list/index.wxml:94` | `cy-switch` | `bind:change` | `pubOnCollective` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0448 | `pages/activity/list/index.wxml:97` | `picker` | `bindchange` | `pubOnMetric` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0449 | `pages/activity/list/index.wxml:99` | `input` | `bindinput` | `pubOnField` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0450 | `pages/activity/list/index.wxml:101` | `input` | `bindinput` | `pubOnField` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0451 | `pages/activity/list/index.wxml:102` | `cy-switch` | `bind:change` | `pubOnBadge` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0452 | `pages/activity/list/index.wxml:112` | `view` | `bindtap` | `pubToggleRole` | 1 | state | tap | success | NO | PASS | #783,#782 | PENDING |
| E0453 | `pages/activity/list/index.wxml:113` | `view` | `bindtap` | `pubToggleRole` | 1 | state | tap | success | NO | PASS | #783,#782 | PENDING |
| E0454 | `pages/activity/list/index.wxml:114` | `view` | `bindtap` | `pubToggleRole` | 1 | state | tap | success | NO | PASS | #783,#782 | PENDING |
| E0455 | `pages/activity/list/index.wxml:117` | `cy-tabs` | `bind:change` | `pubSetCopyMode` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0456 | `pages/activity/list/index.wxml:121` | `input` | `bindinput` | `pubOnNotify` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0457 | `pages/activity/list/index.wxml:122` | `input` | `bindinput` | `pubOnNotify` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0458 | `pages/activity/list/index.wxml:129` | `input` | `bindinput` | `pubOnNotify` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0459 | `pages/activity/list/index.wxml:130` | `input` | `bindinput` | `pubOnNotify` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0460 | `pages/activity/list/index.wxml:134` | `input` | `bindinput` | `pubOnNotify` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0461 | `pages/activity/list/index.wxml:135` | `input` | `bindinput` | `pubOnNotify` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0462 | `pages/activity/list/index.wxml:139` | `input` | `bindinput` | `pubOnNotify` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0463 | `pages/activity/list/index.wxml:140` | `input` | `bindinput` | `pubOnNotify` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0464 | `pages/activity/list/index.wxml:145` | `view` | `bindtap` | `pubToggleChannel` | 1 | state | tap | success | NO | PASS | #783,#782 | PENDING |
| E0465 | `pages/activity/list/index.wxml:146` | `view` | `bindtap` | `pubToggleChannel` | 1 | state | tap | success | NO | PASS | #783,#782 | PENDING |
| E0466 | `pages/activity/list/index.wxml:152` | `cy-btn` | `bindtap` | `pubOnSubmit` | 3 | write | tap | broadcast-error-after-publish,broadcast-timeout-after-publish,duplicate-trigger,error,forbidden-capability,publish-error,publish-timeout,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0467 | `pages/activity/official-detail/index.wxml:41` | `view` | `bindtap` | `onMissionTap` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0468 | `pages/activity/official-detail/index.wxml:65` | `view` | `bindtap` | `onCta` | 4 | navigation,state,write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0469 | `pages/activity/official-detail/index.wxml:70` | `cy-error` | `bind:retry` | `onRetry` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0470 | `pages/activity/official-inbox/index.wxml:6` | `cy-error` | `bind:retry` | `fetch` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0471 | `pages/activity/official-inbox/index.wxml:13` | `view` | `bindtap` | `act` | 2 | read | tap | duplicate-trigger,error,modal-cancelled,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0472 | `pages/activity/official-inbox/index.wxml:13` | `view` | `bindtap` | `act` | 2 | read | tap | duplicate-trigger,error,modal-cancelled,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0473 | `pages/activity/official-inbox/index.wxml:16` | `view` | `bindtap` | `act` | 1 | read | tap | duplicate-trigger,error,modal-cancelled,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0474 | `pages/activity/official-mine/index.wxml:6` | `cy-error` | `bind:retry` | `fetch` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#782 | PENDING |
| E0475 | `pages/activity/official-mine/index.wxml:10` | `view` | `bindtap` | `openEvent` | 1 | navigation | tap | error,success | NO | PASS | #787,#782 | PENDING |
| E0476 | `pages/address/address.wxml:5` | `cy-nav-bar` | `bind:back` | `onBack` | 1 | navigation | event-contract | error,success | NO | PASS | #783 | PENDING |
| E0477 | `pages/address/address.wxml:11` | `view` | `bindtap` | `selectAddress` | 1 | navigation | tap | error,success | NO | PASS | #783 | PENDING |
| E0478 | `pages/address/address.wxml:20` | `view` | `catchtap` | `goEditAddress` | 1 | navigation | tap | error,success | NO | PASS | #783 | PENDING |
| E0479 | `pages/address/address.wxml:21` | `view` | `catchtap` | `deleteAddress` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,modal-cancelled,success,timeout | NO | PASS | #783 | PENDING |
| E0480 | `pages/address/address.wxml:32` | `cy-error` | `bind:retry` | `getList` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783 | PENDING |
| E0481 | `pages/addressinfo/addressinfo.wxml:6` | `cy-nav-bar` | `bind:back` | `onBack` | 1 | navigation | event-contract | error,success | NO | PASS | #783 | PENDING |
| E0482 | `pages/addressinfo/addressinfo.wxml:14` | `input` | `bindinput` | `onInputChange` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E0483 | `pages/addressinfo/addressinfo.wxml:21` | `input` | `bindinput` | `onInputChange` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E0484 | `pages/addressinfo/addressinfo.wxml:34` | `cy-btn` | `bindtap` | `saveAddress` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783 | PENDING |
| E0485 | `pages/agreement/index.wxml:5` | `cy-nav-bar` | `bind:back` | `onBack` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E0486 | `pages/club/apply/index.wxml:3` | `cy-nav-bar` | `bind:back` | `onHeaderBack` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E0487 | `pages/club/apply/index.wxml:24` | `input` | `bindinput` | `onInput` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0488 | `pages/club/apply/index.wxml:28` | `input` | `bindinput` | `onInput` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0489 | `pages/club/apply/index.wxml:32` | `input` | `bindinput` | `onInput` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0490 | `pages/club/apply/index.wxml:36` | `input` | `bindinput` | `onInput` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0491 | `pages/club/apply/index.wxml:48` | `view` | `bindtap` | `pickExp` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E0492 | `pages/club/apply/index.wxml:54` | `input` | `bindinput` | `onInput` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0493 | `pages/club/apply/index.wxml:58` | `input` | `bindinput` | `onInput` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0494 | `pages/club/apply/index.wxml:70` | `view` | `bindtap` | `toggleBool` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E0495 | `pages/club/apply/index.wxml:74` | `view` | `bindtap` | `toggleBool` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E0496 | `pages/club/apply/index.wxml:78` | `view` | `bindtap` | `toggleBool` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E0497 | `pages/club/apply/index.wxml:82` | `view` | `bindtap` | `toggleBool` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E0498 | `pages/club/apply/index.wxml:94` | `view` | `bindtap` | `toggleBool` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E0499 | `pages/club/apply/index.wxml:99` | `cy-upload` | `bind:add` | `addCerts` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0500 | `pages/club/apply/index.wxml:99` | `cy-upload` | `bind:remove` | `removeCert` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0501 | `pages/club/apply/index.wxml:109` | `cy-btn` | `bindtap` | `onNext` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | — | PENDING |
| E0502 | `pages/club/apply/index.wxml:115` | `cy-error` | `bind:retry` | `retryLeaderStatus` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0503 | `pages/club/apply/index.wxml:130` | `cy-btn` | `bindtap` | `goCreateClub` | 1 | navigation | tap | error,success | NO | PASS | — | PENDING |
| E0504 | `pages/club/create/index.wxml:3` | `cy-nav-bar` | `bind:back` | `onBack` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E0505 | `pages/club/create/index.wxml:4` | `view` | `bindtap` | `onClose` | 1 | navigation | tap | error,success | NO | PASS | — | PENDING |
| E0506 | `pages/club/create/index.wxml:19` | `view` | `bindtap` | `pickType` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E0507 | `pages/club/create/index.wxml:33` | `view` | `bindtap` | `toggleDir` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E0508 | `pages/club/create/index.wxml:40` | `view` | `bindtap` | `chooseCover` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E0509 | `pages/club/create/index.wxml:44` | `view` | `bindtap` | `chooseLogo` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E0510 | `pages/club/create/index.wxml:55` | `input` | `bindinput` | `onInput` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0511 | `pages/club/create/index.wxml:59` | `textarea` | `bindinput` | `onInput` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0512 | `pages/club/create/index.wxml:68` | `input` | `bindinput` | `onInput` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0513 | `pages/club/create/index.wxml:72` | `input` | `bindinput` | `onInput` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0514 | `pages/club/create/index.wxml:76` | `input` | `bindinput` | `onInput` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0515 | `pages/club/create/index.wxml:83` | `cy-btn` | `bindtap` | `onNext` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | — | PENDING |
| E0516 | `pages/club/detail/index.wxml:3` | `cy-nav-bar` | `bind:back` | `goBack` | 1 | navigation | event-contract | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0517 | `pages/club/detail/index.wxml:4` | `view` | `bindtap` | `onSettings` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E0518 | `pages/club/detail/index.wxml:14` | `view` | `bindtap` | `onRetry` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0519 | `pages/club/detail/index.wxml:15` | `view` | `bindtap` | `goManage` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0520 | `pages/club/detail/index.wxml:54` | `view` | `bindtap` | `onShare` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #787,#783,#782 | PENDING |
| E0521 | `pages/club/detail/index.wxml:62` | `view` | `bindtap` | `onJoin` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0522 | `pages/club/detail/index.wxml:66` | `view` | `bindtap` | `goGroupChat` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0523 | `pages/club/detail/index.wxml:67` | `view` | `bindtap` | `onOwnerPrimary` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0524 | `pages/club/detail/index.wxml:71` | `view` | `bindtap` | `goGroupChat` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0525 | `pages/club/detail/index.wxml:75` | `view` | `bindtap` | `goClubCoop` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0526 | `pages/club/detail/index.wxml:77` | `view` | `bindtap` | `onQuit` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,modal-cancelled,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0527 | `pages/club/detail/index.wxml:81` | `cy-tabs` | `bind:change` | `switchTab` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E0528 | `pages/club/detail/index.wxml:88` | `textarea` | `bindinput` | `onPostText` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E0529 | `pages/club/detail/index.wxml:93` | `view` | `catchtap` | `onRemovePostImage` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E0530 | `pages/club/detail/index.wxml:95` | `view` | `bindtap` | `onPickPostImage` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E0531 | `pages/club/detail/index.wxml:96` | `view` | `bindtap` | `onPostSubmit` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0532 | `pages/club/detail/index.wxml:112` | `view` | `catchtap` | `onExpandPost` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E0533 | `pages/club/detail/index.wxml:113` | `view` | `catchtap` | `onCollapsePost` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E0534 | `pages/club/detail/index.wxml:115` | `image` | `catchtap` | `onPreviewImage` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #787,#783,#782 | PENDING |
| E0535 | `pages/club/detail/index.wxml:118` | `view` | `catchtap` | `onLikePost` | 1 | write | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0536 | `pages/club/detail/index.wxml:119` | `view` | `catchtap` | `onToggleComments` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0537 | `pages/club/detail/index.wxml:120` | `view` | `catchtap` | `onDeletePost` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,modal-cancelled,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0538 | `pages/club/detail/index.wxml:121` | `view` | `catchtap` | `onReportPost` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,modal-cancelled,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0539 | `pages/club/detail/index.wxml:127` | `text` | `catchtap` | `onDeleteComment` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,modal-cancelled,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0540 | `pages/club/detail/index.wxml:128` | `text` | `catchtap` | `onReportComment` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,modal-cancelled,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0541 | `pages/club/detail/index.wxml:132` | `input` | `bindinput` | `onCommentInput` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E0542 | `pages/club/detail/index.wxml:132` | `input` | `bindconfirm` | `onCommentSubmit` | 1 | write | event-contract | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0543 | `pages/club/detail/index.wxml:133` | `view` | `catchtap` | `onCommentSubmit` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0544 | `pages/club/detail/index.wxml:144` | `view` | `bindtap` | `goTopic` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0545 | `pages/club/detail/index.wxml:155` | `view` | `catchtap` | `showGroupCode` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0546 | `pages/club/detail/index.wxml:159` | `cy-error` | `bind:retry` | `loadTopics` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0547 | `pages/club/detail/index.wxml:160` | `cy-empty` | `bindcta` | `onCreateTeam` | 1 | navigation | event-contract | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0548 | `pages/club/detail/index.wxml:183` | `text` | `catchtap` | `onShare` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #787,#783,#782 | PENDING |
| E0549 | `pages/club/detail/index.wxml:188` | `cy-error` | `bind:retry` | `loadMembers` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0550 | `pages/club/detail/index.wxml:189` | `cy-error` | `bind:retry` | `loadMembers` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0551 | `pages/club/detail/index.wxml:192` | `cy-avatar` | `catchtap` | `goMemberProfile` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0552 | `pages/club/detail/index.wxml:193` | `view` | `catchtap` | `goMemberProfile` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0553 | `pages/club/detail/index.wxml:206` | `cy-tabs` | `bind:change` | `onRankTabChange` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0554 | `pages/club/detail/index.wxml:209` | `cy-avatar` | `catchtap` | `goMemberProfile` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0555 | `pages/club/detail/index.wxml:210` | `view` | `catchtap` | `goMemberProfile` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0556 | `pages/club/detail/index.wxml:230` | `text` | `catchtap` | `onCreateTeam` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0557 | `pages/club/detail/index.wxml:232` | `cy-error` | `bind:retry` | `loadTopics` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0558 | `pages/club/detail/index.wxml:234` | `view` | `bindtap` | `goManageTopic` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0559 | `pages/club/detail/index.wxml:243` | `cy-empty` | `bind:cta` | `onCreateTeam` | 1 | navigation | event-contract | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0560 | `pages/club/detail/index.wxml:249` | `cy-error` | `bind:retry` | `loadShareEditions` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0561 | `pages/club/detail/index.wxml:257` | `button` | `catchtap` | `stopShareBubble` | 1 | other | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0562 | `pages/club/detail/index.wxml:264` | `text` | `catchtap` | `goCoopList` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0563 | `pages/club/detail/index.wxml:265` | `cy-search` | `bind:input` | `onMerchantKeyword` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E0564 | `pages/club/detail/index.wxml:273` | `cy-merchant-card` | `bind:tap` | `goMerchantProfile` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0565 | `pages/club/detail/index.wxml:290` | `view` | `catchtap` | `onCreateTeam` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0566 | `pages/club/detail/index.wxml:291` | `view` | `bindtap` | `onOpenClubSession` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0567 | `pages/club/detail/index.wxml:292` | `view` | `catchtap` | `goEnroll` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0568 | `pages/club/detail/index.wxml:294` | `view` | `catchtap` | `goEditionReport` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0569 | `pages/club/detail/index.wxml:295` | `view` | `catchtap` | `onClubAiOpen` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E0570 | `pages/club/detail/index.wxml:296` | `view` | `bindtap` | `goJoinRequests` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0571 | `pages/club/detail/index.wxml:297` | `view` | `bindtap` | `onSettings` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E0572 | `pages/club/detail/index.wxml:302` | `text` | `catchtap` | `goEnroll` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0573 | `pages/club/detail/index.wxml:304` | `cy-error` | `bind:retry` | `loadTopics` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0574 | `pages/club/detail/index.wxml:306` | `view` | `bindtap` | `goManageTopic` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0575 | `pages/club/detail/index.wxml:320` | `cy-error` | `bind:retry` | `reloadMessages` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0576 | `pages/club/detail/index.wxml:331` | `text` | `catchtap` | `goCooperation` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0577 | `pages/club/detail/index.wxml:333` | `cy-error` | `bind:retry` | `reloadInviteFeedback` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0578 | `pages/club/detail/index.wxml:335` | `view` | `bindtap` | `goCooperation` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0579 | `pages/club/detail/index.wxml:348` | `text` | `catchtap` | `onShare` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #787,#783,#782 | PENDING |
| E0580 | `pages/club/detail/index.wxml:350` | `cy-error` | `bind:retry` | `loadMembers` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0581 | `pages/club/detail/index.wxml:351` | `cy-error` | `bind:retry` | `loadMembers` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0582 | `pages/club/detail/index.wxml:354` | `cy-avatar` | `catchtap` | `goMemberProfile` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0583 | `pages/club/detail/index.wxml:355` | `view` | `catchtap` | `goMemberProfile` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0584 | `pages/club/detail/index.wxml:359` | `view` | `catchtap` | `onSetRole` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,modal-cancelled,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0585 | `pages/club/detail/index.wxml:360` | `view` | `catchtap` | `removeMember` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,modal-cancelled,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0586 | `pages/club/detail/index.wxml:369` | `cy-sheet` | `bind:close` | `closeOwnerPicker` | 1 | navigation | event-contract | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0587 | `pages/club/detail/index.wxml:370` | `view` | `catchtap` | `onPickOwnerClub` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0588 | `pages/club/detail/index.wxml:374` | `cy-sheet` | `bind:close` | `closeClubSessionSheet` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E0589 | `pages/club/detail/index.wxml:379` | `view` | `catchtap` | `onSelectSessionTopic` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E0590 | `pages/club/detail/index.wxml:385` | `picker` | `bindchange` | `onSessionDateChange` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E0591 | `pages/club/detail/index.wxml:388` | `view` | `catchtap` | `submitClubSession` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0592 | `pages/club/detail/index.wxml:396` | `cy-sheet` | `bind:close` | `onClubAiClose` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E0593 | `pages/club/detail/index.wxml:401` | `view` | `catchtap` | `onClubAiChip` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E0594 | `pages/club/detail/index.wxml:404` | `textarea` | `bindinput` | `onClubAiIdea` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E0595 | `pages/club/detail/index.wxml:409` | `input` | `bindinput` | `onClubAiStyle` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E0596 | `pages/club/detail/index.wxml:412` | `input` | `bindinput` | `onClubAiDuration` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E0597 | `pages/club/detail/index.wxml:421` | `view` | `catchtap` | `onClubAiCancel` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E0598 | `pages/club/detail/index.wxml:424` | `view` | `catchtap` | `onClubAiGenerate` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0599 | `pages/club/detail/index.wxml:427` | `view` | `catchtap` | `onClubAiGenerate` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0600 | `pages/club/detail/index.wxml:460` | `text` | `catchtap` | `onClubAiCopy` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #787,#783,#782 | PENDING |
| E0601 | `pages/club/detail/index.wxml:467` | `view` | `catchtap` | `onClubAiAdopt` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0602 | `pages/club/detail/index.wxml:475` | `cy-sheet` | `bind:close` | `onCloseGroupCode` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E0603 | `pages/club/detail/index.wxml:477` | `view` | `bindtap` | `onGroupCodeSelectActivity` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0604 | `pages/club/detail/index.wxml:491` | `cy-qr-voucher` | `bind:close` | `onCloseGroupCode` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E0605 | `pages/club/detail/index.wxml:492` | `cy-qr-voucher` | `bind:retry` | `onGroupCodeRetry` | 1 | write | event-contract | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0606 | `pages/club/detail/index.wxml:499` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0607 | `pages/club/detail/index.wxml:500` | `cy-scene-sheet` | `bind:requestclose` | `requestSceneClose` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E0608 | `pages/club/detail/index.wxml:500` | `cy-scene-sheet` | `bind:back` | `requestSceneClose` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E0609 | `pages/club/detail/index.wxml:501` | `cy-scene-club-edit` | `bind:back` | `closeScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E0610 | `pages/club/detail/index.wxml:501` | `cy-scene-club-edit` | `bind:saved` | `onClubEdited` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0611 | `pages/club/detail/index.wxml:501` | `cy-scene-club-edit` | `bind:dissolved` | `onClubDissolved` | 1 | navigation | event-contract | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0612 | `pages/club/detail/index.wxml:501` | `cy-scene-club-edit` | `bind:dirtychange` | `onSceneDirtyChange` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E0613 | `pages/club/detail/index.wxml:504` | `cy-modal` | `bind:confirm` | `confirmSceneDiscard` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E0614 | `pages/club/detail/index.wxml:504` | `cy-modal` | `bind:cancel` | `cancelSceneDiscard` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E0615 | `pages/club/dissolution-blockers/index.wxml:7` | `cy-error` | `bind:retry` | `load` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0616 | `pages/club/dissolution-blockers/index.wxml:17` | `view` | `bindtap` | `retryDepositRefund` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0617 | `pages/club/edit/index.wxml:4` | `cy-nav-bar` | `bind:back` | `requestBack` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E0618 | `pages/club/edit/index.wxml:7` | `cy-scene-club-edit` | `bind:back` | `requestBack` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E0619 | `pages/club/edit/index.wxml:7` | `cy-scene-club-edit` | `bind:saved` | `onSaved` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0620 | `pages/club/edit/index.wxml:7` | `cy-scene-club-edit` | `bind:dissolved` | `onDissolved` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E0621 | `pages/club/edit/index.wxml:7` | `cy-scene-club-edit` | `bind:dirtychange` | `onDirtyChange` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0622 | `pages/club/edit/index.wxml:8` | `cy-modal` | `bind:confirm` | `confirmBack` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E0623 | `pages/club/edit/index.wxml:8` | `cy-modal` | `bind:cancel` | `cancelBack` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0624 | `pages/club/edition-report/index.wxml:14` | `picker` | `bindchange` | `onTopicChange` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0625 | `pages/club/edition-report/index.wxml:29` | `text` | `bindtap` | `loadTopics` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | — | PENDING |
| E0626 | `pages/club/edition-report/index.wxml:39` | `input` | `bindinput` | `onHourInput` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0627 | `pages/club/edition-report/index.wxml:40` | `view` | `bindtap` | `submitHours` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | — | PENDING |
| E0628 | `pages/club/edition-report/index.wxml:48` | `picker` | `bindchange` | `onDimensionChange` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0629 | `pages/club/edition-report/index.wxml:52` | `input` | `bindinput` | `onEvidenceInput` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0630 | `pages/club/edition-report/index.wxml:53` | `view` | `bindtap` | `submitEvidence` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | — | PENDING |
| E0631 | `pages/club/enroll/index.wxml:8` | `cy-empty` | `bind:cta` | `goBack` | 1 | navigation | event-contract | error,success | NO | PASS | #783,#782 | PENDING |
| E0632 | `pages/club/enroll/index.wxml:17` | `cy-error` | `bind:retry` | `retryLoadTeams` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0633 | `pages/club/enroll/index.wxml:18` | `cy-error` | `bind:retry` | `retryLoadTeams` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0634 | `pages/club/enroll/index.wxml:27` | `view` | `bindtap` | `toggleTeam` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0635 | `pages/club/enroll/index.wxml:39` | `cy-error` | `bind:retry` | `retryTeamDetail` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0636 | `pages/club/enroll/index.wxml:40` | `cy-error` | `bind:retry` | `retryTeamDetail` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0637 | `pages/club/enroll/index.wxml:57` | `view` | `bindtap` | `goUserInfo` | 1 | navigation | tap | error,success | NO | PASS | #783,#782 | PENDING |
| E0638 | `pages/club/enroll/index.wxml:61` | `view` | `catchtap` | `refundReg` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,modal-cancelled,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0639 | `pages/club/group-code/index.wxml:3` | `cy-nav-bar` | `bind:back` | `onClose` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E0640 | `pages/club/group-code/index.wxml:11` | `view` | `bindtap` | `onSelectActivity` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | — | PENDING |
| E0641 | `pages/club/group-code/index.wxml:26` | `cy-qr-voucher` | `bind:close` | `onClose` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E0642 | `pages/club/group-code/index.wxml:27` | `cy-qr-voucher` | `bind:retry` | `onRetry` | 1 | write | event-contract | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | — | PENDING |
| E0643 | `pages/club/join-requests/index.wxml:3` | `cy-nav-bar` | `bind:back` | `goBack` | 1 | navigation | event-contract | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0644 | `pages/club/join-requests/index.wxml:8` | `cy-error` | `bind:retry` | `loadRequests` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0645 | `pages/club/join-requests/index.wxml:9` | `cy-error` | `bind:retry` | `loadRequests` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0646 | `pages/club/join-requests/index.wxml:20` | `view` | `catchtap` | `reject` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,modal-cancelled,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0647 | `pages/club/join-requests/index.wxml:21` | `view` | `catchtap` | `approve` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,modal-cancelled,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0648 | `pages/coop/candidates/index.wxml:18` | `cy-empty` | `bind:cta` | `goBack` | 1 | navigation | event-contract | error,success | NO | PASS | #783 | PENDING |
| E0649 | `pages/coop/candidates/index.wxml:21` | `cy-error` | `bind:retry` | `retry` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783 | PENDING |
| E0650 | `pages/coop/candidates/index.wxml:26` | `cy-tabs` | `bind:change` | `onTabChange` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E0651 | `pages/coop/candidates/index.wxml:38` | `cy-empty` | `bind:cta` | `goCoopDiscovery` | 1 | navigation | event-contract | error,success | NO | PASS | #783 | PENDING |
| E0652 | `pages/coop/candidates/index.wxml:51` | `cy-btn` | `catchtap` | `declineClubApply` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,modal-cancelled,success,timeout | NO | PASS | #783 | PENDING |
| E0653 | `pages/coop/candidates/index.wxml:53` | `cy-btn` | `catchtap` | `goInviteClub` | 1 | navigation | tap | error,success | NO | PASS | #783 | PENDING |
| E0654 | `pages/coop/candidates/index.wxml:58` | `cy-empty` | `bind:cta` | `goTopicCoopSetting` | 1 | navigation | event-contract | error,success | NO | PASS | #783 | PENDING |
| E0655 | `pages/coop/candidates/index.wxml:74` | `cy-btn` | `catchtap` | `confirmReg` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,modal-cancelled,success,timeout | NO | PASS | #783 | PENDING |
| E0656 | `pages/coop/candidates/index.wxml:80` | `cy-empty` | `bind:cta` | `goTopicCoopSetting` | 1 | navigation | event-contract | error,success | NO | PASS | #783 | PENDING |
| E0657 | `pages/coop/invite/index.wxml:11` | `cy-error` | `bind:retry` | `retryTopics` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783 | PENDING |
| E0658 | `pages/coop/invite/index.wxml:12` | `picker` | `bindchange` | `onTopicChange` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E0659 | `pages/coop/invite/index.wxml:23` | `view` | `bindtap` | `switchShareMode` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E0660 | `pages/coop/invite/index.wxml:27` | `view` | `bindtap` | `switchShareMode` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E0661 | `pages/coop/invite/index.wxml:34` | `input` | `bindinput` | `onFeeInput` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E0662 | `pages/coop/invite/index.wxml:41` | `cy-error` | `bind:retry` | `retryTargets` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783 | PENDING |
| E0663 | `pages/coop/invite/index.wxml:52` | `view` | `bindtap` | `toggleTarget` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E0664 | `pages/coop/invite/index.wxml:62` | `view` | `bindtap` | `toggleTarget` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E0665 | `pages/coop/invite/index.wxml:71` | `view` | `bindtap` | `goNearby` | 1 | navigation | tap | error,success | NO | PASS | #783 | PENDING |
| E0666 | `pages/coop/invite/index.wxml:77` | `view` | `bindtap` | `sendInvite` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E0667 | `pages/coop/invite/index.wxml:80` | `cy-sheet` | `bind:close` | `closeInviteModal` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E0668 | `pages/coop/invite/index.wxml:84` | `textarea` | `bindinput` | `onMessageInput` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E0669 | `pages/coop/invite/index.wxml:87` | `view` | `bindtap` | `goEditTopic` | 1 | navigation | tap | error,success | NO | PASS | #783 | PENDING |
| E0670 | `pages/coop/invite/index.wxml:88` | `view` | `bindtap` | `confirmInvite` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783 | PENDING |
| E0671 | `pages/coop/list/index.wxml:14` | `cy-tabs` | `bind:change` | `onTabChange` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E0672 | `pages/coop/list/index.wxml:19` | `cy-error` | `bind:retry` | `load` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E0673 | `pages/coop/list/index.wxml:29` | `view` | `catchtap` | `copyPartnerPhone` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #787,#783 | PENDING |
| E0674 | `pages/coop/list/index.wxml:40` | `cy-error` | `bind:retry` | `load` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E0675 | `pages/coop/list/index.wxml:42` | `view` | `catchtap` | `payDeposit` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783 | PENDING |
| E0676 | `pages/coop/list/index.wxml:43` | `view` | `catchtap` | `contactPartner` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E0677 | `pages/coop/list/index.wxml:44` | `view` | `catchtap` | `openPerkPick` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E0678 | `pages/coop/list/index.wxml:45` | `view` | `catchtap` | `reviewCoop` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783 | PENDING |
| E0679 | `pages/coop/list/index.wxml:48` | `view` | `catchtap` | `retryDepositRefund` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783 | PENDING |
| E0680 | `pages/coop/list/index.wxml:51` | `cy-empty` | `bindcta` | `goInvite` | 1 | navigation | event-contract | error,success | NO | PASS | #787,#783 | PENDING |
| E0681 | `pages/coop/list/index.wxml:58` | `cy-error` | `bind:retry` | `loadPool` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E0682 | `pages/coop/list/index.wxml:71` | `view` | `catchtap` | `applyPool` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,modal-cancelled,success,timeout | NO | PASS | #787,#783 | PENDING |
| E0683 | `pages/coop/list/index.wxml:74` | `view` | `catchtap` | `withdrawPool` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,modal-cancelled,success,timeout | NO | PASS | #787,#783 | PENDING |
| E0684 | `pages/coop/list/index.wxml:84` | `cy-error` | `bind:retry` | `load` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E0685 | `pages/coop/list/index.wxml:94` | `view` | `catchtap` | `copyPartnerPhone` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #787,#783 | PENDING |
| E0686 | `pages/coop/list/index.wxml:102` | `text` | `catchtap` | `goCandidates` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E0687 | `pages/coop/list/index.wxml:112` | `view` | `catchtap` | `cancelSent` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,modal-cancelled,success,timeout | NO | PASS | #787,#783 | PENDING |
| E0688 | `pages/coop/list/index.wxml:115` | `view` | `catchtap` | `payDeposit` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783 | PENDING |
| E0689 | `pages/coop/list/index.wxml:116` | `view` | `catchtap` | `contactPartner` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E0690 | `pages/coop/list/index.wxml:117` | `view` | `catchtap` | `cancelAccepted` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,modal-cancelled,success,timeout | NO | PASS | #787,#783 | PENDING |
| E0691 | `pages/coop/list/index.wxml:118` | `view` | `catchtap` | `reviewCoop` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783 | PENDING |
| E0692 | `pages/coop/list/index.wxml:121` | `view` | `catchtap` | `retryDepositRefund` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783 | PENDING |
| E0693 | `pages/coop/list/index.wxml:124` | `cy-empty` | `bindcta` | `goInvite` | 1 | navigation | event-contract | error,success | NO | PASS | #787,#783 | PENDING |
| E0694 | `pages/coop/list/index.wxml:130` | `cy-sheet` | `bind:close` | `closePerkPick` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E0695 | `pages/coop/list/index.wxml:133` | `view` | `catchtap` | `togglePerk` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E0696 | `pages/coop/list/index.wxml:142` | `view` | `catchtap` | `submitPerks` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783 | PENDING |
| E0697 | `pages/coop/nearby/index.wxml:13` | `view` | `bindtap` | `openMerchant` | 1 | navigation | tap | error,success | NO | PASS | — | PENDING |
| E0698 | `pages/coop/nearby/index.wxml:25` | `cy-btn` | `catchtap` | `callMerchant` | 1 | external | tap | cancelled,duplicate-trigger,error,success,timeout | YES | PASS | — | PENDING |
| E0699 | `pages/coop/nearby/index.wxml:31` | `cy-error` | `bind:retry` | `openLocationSettings` | 1 | external | event-contract | cancelled,duplicate-trigger,error,getLocation-denied,openSetting-denied,success,timeout | YES | PASS | — | PENDING |
| E0700 | `pages/coop/nearby/index.wxml:33` | `cy-error` | `bind:retry` | `retry` | 1 | external | event-contract | denied,duplicate-trigger,error,success,timeout | YES | PASS | — | PENDING |
| E0701 | `pages/coop/nearby/index.wxml:35` | `cy-empty` | `bind:cta` | `retry` | 1 | external | event-contract | denied,duplicate-trigger,error,success,timeout | YES | PASS | — | PENDING |
| E0702 | `pages/coop/settlement-detail/index.wxml:2` | `cy-nav-bar` | `bind:back` | `onNavBack` | 1 | navigation | event-contract | error,success | NO | PASS | #782 | PENDING |
| E0703 | `pages/coop/settlement-detail/index.wxml:8` | `cy-error` | `bind:retry` | `load` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #782 | PENDING |
| E0704 | `pages/coop/withdraw/index.wxml:10` | `view` | `bindtap` | `goBankWithdraw` | 1 | navigation | tap | error,success | NO | PASS | — | PENDING |
| E0705 | `pages/crop/index.wxml:13` | `view` | `catchtouchstart` | `onTouchStart` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E0706 | `pages/crop/index.wxml:14` | `view` | `catchtouchmove` | `onTouchMove` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E0707 | `pages/crop/index.wxml:15` | `view` | `catchtouchend` | `onTouchEnd` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E0708 | `pages/crop/index.wxml:16` | `view` | `catchtouchcancel` | `onTouchEnd` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E0709 | `pages/crop/index.wxml:38` | `view` | `bindtap` | `onCancel` | 1 | navigation | tap | error,success | NO | PASS | — | PENDING |
| E0710 | `pages/crop/index.wxml:41` | `view` | `bindtap` | `onConfirm` | 1 | navigation | tap | error,success | NO | PASS | — | PENDING |
| E0711 | `pages/deregister/index.wxml:3` | `cy-nav-bar` | `bind:back` | `onBack` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E0712 | `pages/deregister/index.wxml:6` | `cy-sheet` | `bind:close` | `onBack` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E0713 | `pages/gerenziliao/gerenziliao.wxml:5` | `view` | `bindtap` | `uploadPic` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E0714 | `pages/gerenziliao/gerenziliao.wxml:18` | `view` | `bindtap` | `openPop4` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E0715 | `pages/gerenziliao/gerenziliao.wxml:27` | `view` | `bindtap` | `openPop` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E0716 | `pages/gerenziliao/gerenziliao.wxml:40` | `view` | `bindtap` | `openPop2` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E0717 | `pages/gerenziliao/gerenziliao.wxml:53` | `view` | `bindtap` | `openPop3` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E0718 | `pages/gerenziliao/gerenziliao.wxml:62` | `image` | `bindtap` | `removeCategory` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E0719 | `pages/gerenziliao/gerenziliao.wxml:67` | `view` | `bindtap` | `navigateToCategorySelect` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E0720 | `pages/gerenziliao/gerenziliao.wxml:80` | `cy-btn` | `bindtap` | `saveInfo` | 1 | write | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E0721 | `pages/gerenziliao/gerenziliao.wxml:84` | `cy-sheet` | `bind:close` | `cancel` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E0722 | `pages/gerenziliao/gerenziliao.wxml:87` | `button` | `bindtap` | `cancel` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E0723 | `pages/gerenziliao/gerenziliao.wxml:89` | `button` | `bindtap` | `confirm` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E0724 | `pages/gerenziliao/gerenziliao.wxml:93` | `textarea` | `bindinput` | `onInputChange` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E0725 | `pages/gerenziliao/gerenziliao.wxml:100` | `cy-sheet` | `bind:close` | `cancel2` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E0726 | `pages/gerenziliao/gerenziliao.wxml:103` | `button` | `bindtap` | `cancel2` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E0727 | `pages/gerenziliao/gerenziliao.wxml:105` | `button` | `bindtap` | `confirm2` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E0728 | `pages/gerenziliao/gerenziliao.wxml:111` | `image` | `bindtap` | `deleteCasePic` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E0729 | `pages/gerenziliao/gerenziliao.wxml:113` | `button` | `bindtap` | `uploadCasePics` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E0730 | `pages/gerenziliao/gerenziliao.wxml:123` | `cy-sheet` | `bind:close` | `cancel3` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E0731 | `pages/gerenziliao/gerenziliao.wxml:126` | `button` | `bindtap` | `cancel3` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E0732 | `pages/gerenziliao/gerenziliao.wxml:128` | `button` | `bindtap` | `confirm3` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E0733 | `pages/gerenziliao/gerenziliao.wxml:134` | `image` | `bindtap` | `deleteWechat` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E0734 | `pages/gerenziliao/gerenziliao.wxml:136` | `button` | `bindtap` | `uploadWechat` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E0735 | `pages/gerenziliao/gerenziliao.wxml:146` | `cy-sheet` | `bind:close` | `cancel4` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E0736 | `pages/gerenziliao/gerenziliao.wxml:149` | `button` | `bindtap` | `cancel4` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E0737 | `pages/gerenziliao/gerenziliao.wxml:151` | `button` | `bindtap` | `confirm4` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E0738 | `pages/gerenziliao/gerenziliao.wxml:155` | `textarea` | `bindinput` | `onInputChange` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E0739 | `pages/gerenziliao/gerenziliao.wxml:162` | `cy-category-sheet` | `bind:select` | `onCategorySelect` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E0740 | `pages/gerenziliao/gerenziliao.wxml:162` | `cy-category-sheet` | `bind:close` | `onCategorySheetClose` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E0741 | `pages/index/index.wxml:7` | `scroll-view` | `bindscroll` | `onMainScroll` | 1 | state | event-contract | success | NO | PASS | #789,#787,#783,#782 | PENDING |
| E0742 | `pages/index/index.wxml:7` | `scroll-view` | `bindscrolltolower` | `onPagerLower` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #789,#787,#783,#782 | PENDING |
| E0743 | `pages/index/index.wxml:12` | `swiper` | `bindchange` | `onBannerChange` | 1 | state | event-contract | success | NO | PASS | #789,#787,#783,#782 | PENDING |
| E0744 | `pages/index/index.wxml:14` | `swiper-item` | `bindtap` | `onHeroBannerTap` | 1 | navigation | tap | error,success | NO | PASS | #789,#787,#783,#782 | PENDING |
| E0745 | `pages/index/index.wxml:25` | `view` | `bindtap` | `goUser` | 1 | navigation | tap | error,success | NO | PASS | #789,#787,#783,#782 | PENDING |
| E0746 | `pages/index/index.wxml:47` | `view` | `bindtap` | `goContinueExplore` | 1 | navigation | tap | error,success | NO | PASS | #789,#787,#783,#782 | PENDING |
| E0747 | `pages/index/index.wxml:68` | `cy-error` | `bind:retry` | `retryList` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #789,#787,#783,#782 | PENDING |
| E0748 | `pages/index/index.wxml:76` | `view` | `bindtap` | `goActivity` | 1 | navigation | tap | error,success | NO | PASS | #789,#787,#783,#782 | PENDING |
| E0749 | `pages/index/index.wxml:102` | `cy-error` | `bind:retry` | `retryList` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #789,#787,#783,#782 | PENDING |
| E0750 | `pages/index/index.wxml:107` | `view` | `bindtap` | `goRecoHero` | 1 | navigation | tap | error,success | NO | PASS | #789,#787,#783,#782 | PENDING |
| E0751 | `pages/index/index.wxml:138` | `cy-error` | `bind:retry` | `retryList` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #789,#787,#783,#782 | PENDING |
| E0752 | `pages/index/index.wxml:143` | `view` | `bindtap` | `goActivity` | 1 | navigation | tap | error,success | NO | PASS | #789,#787,#783,#782 | PENDING |
| E0753 | `pages/index/index.wxml:164` | `cy-tabs` | `bind:change` | `switchTab` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #789,#787,#783,#782 | PENDING |
| E0754 | `pages/index/index.wxml:169` | `view` | `bindtap` | `goTopic` | 1 | navigation | tap | error,success | NO | PASS | #789,#787,#783,#782 | PENDING |
| E0755 | `pages/index/index.wxml:185` | `view` | `bindtap` | `goTopic` | 1 | navigation | tap | error,success | NO | PASS | #789,#787,#783,#782 | PENDING |
| E0756 | `pages/index/index.wxml:206` | `view` | `bindtap` | `goActivity` | 1 | navigation | tap | error,success | NO | PASS | #789,#787,#783,#782 | PENDING |
| E0757 | `pages/index/index.wxml:222` | `view` | `bindtap` | `goActivity` | 1 | navigation | tap | error,success | NO | PASS | #789,#787,#783,#782 | PENDING |
| E0758 | `pages/index/index.wxml:249` | `button` | `bindgetphonenumber` | `getPhoneNumber` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #789,#787,#783,#782 | PENDING |
| E0759 | `pages/index/index.wxml:255` | `cy-privacy-gate` | `bind:settled` | `onPrivacyGateSettled` | 1 | state | event-contract | success | NO | PASS | #789,#787,#783,#782 | PENDING |
| E0760 | `pages/member/index/index.wxml:3` | `cy-privacy-gate` | `bind:settled` | `onPrivacyGateSettled` | 1 | state | event-contract | success | NO | PASS | #787 | PENDING |
| E0761 | `pages/merchant/apply/index.wxml:3` | `cy-nav-bar` | `bind:back` | `onHeaderBack` | 1 | navigation | event-contract | error,success | NO | PASS | #783 | PENDING |
| E0762 | `pages/merchant/apply/index.wxml:53` | `input` | `bindinput` | `onInput` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E0763 | `pages/merchant/apply/index.wxml:58` | `input` | `bindinput` | `onInput` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E0764 | `pages/merchant/apply/index.wxml:62` | `input` | `bindinput` | `onInput` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E0765 | `pages/merchant/apply/index.wxml:72` | `view` | `bindtap` | `chooseLocation` | 1 | external | tap | chooseLocation-cancelled,chooseLocation-denied,duplicate-trigger,error,modal-cancelled,openSetting-cancelled,openSetting-denied,success,timeout | YES | PASS | #783 | PENDING |
| E0766 | `pages/merchant/apply/index.wxml:77` | `view` | `bindtap` | `openHoursPicker` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E0767 | `pages/merchant/apply/index.wxml:88` | `textarea` | `bindinput` | `onInput` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E0768 | `pages/merchant/apply/index.wxml:103` | `view` | `bindtap` | `uploadLicense` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E0769 | `pages/merchant/apply/index.wxml:108` | `view` | `catchtap` | `removeLicense` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E0770 | `pages/merchant/apply/index.wxml:119` | `view` | `catchtap` | `removeDerivative` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E0771 | `pages/merchant/apply/index.wxml:121` | `view` | `bindtap` | `addDerivative` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E0772 | `pages/merchant/apply/index.wxml:184` | `cy-btn` | `bindtap` | `onBack` | 1 | navigation | tap | error,success | NO | PASS | #783 | PENDING |
| E0773 | `pages/merchant/apply/index.wxml:185` | `cy-btn` | `bindtap` | `onNext` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #783 | PENDING |
| E0774 | `pages/merchant/apply/index.wxml:188` | `cy-btn` | `bindtap` | `onHome` | 1 | navigation | tap | error,success | NO | PASS | #783 | PENDING |
| E0775 | `pages/merchant/apply/index.wxml:191` | `cy-btn` | `bindtap` | `onStatusAction` | 1 | navigation | tap | error,success | NO | PASS | #783 | PENDING |
| E0776 | `pages/merchant/apply/index.wxml:196` | `cy-date-sheet` | `bind:cancel` | `closeHoursPicker` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E0777 | `pages/merchant/apply/index.wxml:196` | `cy-date-sheet` | `bind:confirm` | `confirmHoursPicker` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E0778 | `pages/merchant/apply/index.wxml:201` | `view` | `bindtap` | `toggleHoursDay` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E0779 | `pages/merchant/apply/index.wxml:207` | `picker-view` | `bindchange` | `onHoursStartChange` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E0780 | `pages/merchant/apply/index.wxml:219` | `picker-view` | `bindchange` | `onHoursEndChange` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E0781 | `pages/merchant/citynode/create/index.wxml:2` | `cy-nav-bar` | `bind:back` | `onNavBack` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E0782 | `pages/merchant/citynode/create/index.wxml:15` | `cy-btn` | `bind:tap` | `goConfigTemplate` | 1 | navigation | tap | error,success | NO | PASS | — | PENDING |
| E0783 | `pages/merchant/citynode/create/index.wxml:19` | `cy-btn` | `bind:tap` | `goConfigTemplate` | 1 | navigation | tap | error,success | NO | PASS | — | PENDING |
| E0784 | `pages/merchant/citynode/create/index.wxml:31` | `text` | `bindtap` | `loadShop` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | — | PENDING |
| E0785 | `pages/merchant/citynode/create/index.wxml:38` | `cy-btn` | `bind:tap` | `repick` | 1 | external | tap | chooseLocation-cancelled,chooseLocation-denied,duplicate-trigger,error,openSetting-cancelled,openSetting-denied,success,timeout | YES | PASS | — | PENDING |
| E0786 | `pages/merchant/citynode/create/index.wxml:42` | `cy-btn` | `bind:tap` | `repick` | 1 | external | tap | chooseLocation-cancelled,chooseLocation-denied,duplicate-trigger,error,openSetting-cancelled,openSetting-denied,success,timeout | YES | PASS | — | PENDING |
| E0787 | `pages/merchant/citynode/create/index.wxml:51` | `cy-btn` | `bind:tap` | `submitNode` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | — | PENDING |
| E0788 | `pages/merchant/citynode/index.wxml:2` | `cy-nav-bar` | `bind:back` | `onNavBack` | 1 | navigation | event-contract | error,success | NO | PASS | #782 | PENDING |
| E0789 | `pages/merchant/citynode/index.wxml:3` | `view` | `bindtap` | `scanRedeem` | 1 | external | tap | cancelled,denied,duplicate-trigger,error,forbidden-owner,success,timeout | YES | PASS | #782 | PENDING |
| E0790 | `pages/merchant/citynode/index.wxml:21` | `text` | `bindtap` | `toggleClaim` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #782 | PENDING |
| E0791 | `pages/merchant/citynode/index.wxml:22` | `text` | `bindtap` | `goCreate` | 1 | navigation | tap | error,success | NO | PASS | #782 | PENDING |
| E0792 | `pages/merchant/citynode/index.wxml:29` | `cy-error` | `bind:retry` | `retry` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #782 | PENDING |
| E0793 | `pages/merchant/citynode/index.wxml:47` | `cy-empty` | `bind:cta` | `goCreate` | 1 | navigation | event-contract | error,success | NO | PASS | #782 | PENDING |
| E0794 | `pages/merchant/citynode/index.wxml:58` | `text` | `bindtap` | `showPosterCode` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #782 | PENDING |
| E0795 | `pages/merchant/citynode/index.wxml:61` | `text` | `bindtap` | `toggleNode` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #782 | PENDING |
| E0796 | `pages/merchant/citynode/index.wxml:65` | `cy-sheet` | `bind:close` | `toggleClaim` | 1 | write | event-contract | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #782 | PENDING |
| E0797 | `pages/merchant/citynode/index.wxml:75` | `text` | `bindtap` | `submitClaim` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #782 | PENDING |
| E0798 | `pages/merchant/citynode/index.wxml:80` | `cy-sheet` | `bind:close` | `closePosterCode` | 1 | state | event-contract | success | NO | PASS | #782 | PENDING |
| E0799 | `pages/merchant/citynode/index.wxml:86` | `cy-error` | `bind:retry` | `retryPosterCode` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #782 | PENDING |
| E0800 | `pages/merchant/coop-center/index.wxml:2` | `cy-nav-bar` | `bind:back` | `onNavBack` | 1 | navigation | event-contract | error,success | NO | PASS | #788,#783,#782 | PENDING |
| E0801 | `pages/merchant/coop-center/index.wxml:3` | `cy-tabs` | `bind:change` | `onTabChange` | 1 | state | event-contract | success | NO | PASS | #788,#783,#782 | PENDING |
| E0802 | `pages/merchant/coop-center/index.wxml:15` | `cy-error` | `bind:retry` | `loadRoutes` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #788,#783,#782 | PENDING |
| E0803 | `pages/merchant/coop-center/index.wxml:20` | `image` | `binderror` | `onRouteCoverError` | 1 | state | event-contract | success | NO | PASS | #788,#783,#782 | PENDING |
| E0804 | `pages/merchant/coop-center/index.wxml:24` | `view` | `bindtap` | `openTopic` | 1 | navigation | tap | error,success | NO | PASS | #788,#783,#782 | PENDING |
| E0805 | `pages/merchant/coop-center/index.wxml:30` | `cy-btn` | `bindtap` | `prepareApplication` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #788,#783,#782 | PENDING |
| E0806 | `pages/merchant/coop-center/index.wxml:31` | `cy-btn` | `bindtap` | `openTopic` | 1 | navigation | tap | error,success | NO | PASS | #788,#783,#782 | PENDING |
| E0807 | `pages/merchant/coop-center/index.wxml:35` | `cy-empty` | `bind:cta` | `loadAvailable` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #788,#783,#782 | PENDING |
| E0808 | `pages/merchant/coop-center/index.wxml:43` | `cy-error` | `bind:retry` | `loadEvents` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #788,#783,#782 | PENDING |
| E0809 | `pages/merchant/coop-center/index.wxml:45` | `view` | `bindtap` | `openEvent` | 1 | navigation | tap | error,success | NO | PASS | #788,#783,#782 | PENDING |
| E0810 | `pages/merchant/coop-center/index.wxml:52` | `cy-empty` | `bind:cta` | `loadAvailable` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #788,#783,#782 | PENDING |
| E0811 | `pages/merchant/coop-center/index.wxml:61` | `cy-btn` | `bindtap` | `enableInviteAlerts` | 1 | other | tap | error,success | NO | PASS | #788,#783,#782 | PENDING |
| E0812 | `pages/merchant/coop-center/index.wxml:64` | `cy-error` | `bind:retry` | `loadInvites` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #788,#783,#782 | PENDING |
| E0813 | `pages/merchant/coop-center/index.wxml:78` | `cy-error` | `bind:retry` | `loadInvites` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #788,#783,#782 | PENDING |
| E0814 | `pages/merchant/coop-center/index.wxml:80` | `cy-btn` | `bindtap` | `rejectInvite` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,modal-cancelled,success,timeout | NO | PASS | #788,#783,#782 | PENDING |
| E0815 | `pages/merchant/coop-center/index.wxml:81` | `cy-btn` | `bindtap` | `acceptInvite` | 1 | read | tap | duplicate-trigger,error,modal-cancelled,success,timeout | NO | PASS | #788,#783,#782 | PENDING |
| E0816 | `pages/merchant/coop-center/index.wxml:95` | `chapter-node-form` | `bind:close` | `closeChapterNodeForm` | 1 | state | event-contract | success | NO | PASS | #788,#783,#782 | PENDING |
| E0817 | `pages/merchant/coop-center/index.wxml:96` | `chapter-node-form` | `bind:submit` | `onChapterNodeFormSubmit` | 1 | write | event-contract | duplicate-trigger,first-error,first-timeout,forbidden-owner,second-error-after-first,second-timeout-after-first,success | NO | PASS | #788,#783,#782 | PENDING |
| E0818 | `pages/merchant/customer/index.wxml:4` | `cy-search` | `bind:input` | `onKeywordInput` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0819 | `pages/merchant/customer/index.wxml:4` | `cy-search` | `bind:confirm` | `onKeywordConfirm` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0820 | `pages/merchant/customer/index.wxml:12` | `view` | `bindtap` | `onSegmentTap` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0821 | `pages/merchant/customer/index.wxml:19` | `cy-error` | `bind:retry` | `retryLoad` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0822 | `pages/merchant/customer/index.wxml:23` | `scroll-view` | `bindscrolltolower` | `loadMore` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0823 | `pages/merchant/customer/index.wxml:35` | `text` | `catchtap` | `callCustomer` | 1 | external | tap | cancelled,duplicate-trigger,error,success,timeout | YES | PASS | #783,#782 | PENDING |
| E0824 | `pages/merchant/decor/coop-setting/index.wxml:2` | `cy-nav-bar` | `bind:back` | `onNavBack` | 1 | navigation | event-contract | error,success | NO | PASS | #783,#782 | PENDING |
| E0825 | `pages/merchant/decor/coop-setting/index.wxml:7` | `cy-error` | `bind:retry` | `load` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0826 | `pages/merchant/decor/coop-setting/index.wxml:11` | `cy-empty` | `bind:cta` | `goApply` | 1 | navigation | event-contract | error,success | NO | PASS | #783,#782 | PENDING |
| E0827 | `pages/merchant/decor/coop-setting/index.wxml:18` | `input` | `bindinput` | `onInput` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0828 | `pages/merchant/decor/coop-setting/index.wxml:22` | `input` | `bindinput` | `onInput` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0829 | `pages/merchant/decor/coop-setting/index.wxml:26` | `radio-group` | `bindchange` | `onChargeType` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0830 | `pages/merchant/decor/coop-setting/index.wxml:33` | `input` | `bindinput` | `onInput` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0831 | `pages/merchant/decor/coop-setting/index.wxml:38` | `cy-btn` | `bindtap` | `onSave` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0832 | `pages/merchant/decor/gallery/index.wxml:2` | `cy-nav-bar` | `bind:back` | `onNavBack` | 1 | navigation | event-contract | error,success | NO | PASS | #782 | PENDING |
| E0833 | `pages/merchant/decor/gallery/index.wxml:6` | `cy-error` | `bind:retry` | `load` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #782 | PENDING |
| E0834 | `pages/merchant/decor/gallery/index.wxml:18` | `view` | `catchtap` | `removeGallery` | 1 | state | tap | success | NO | PASS | #782 | PENDING |
| E0835 | `pages/merchant/decor/gallery/index.wxml:20` | `view` | `bindtap` | `addGallery` | 1 | state | tap | success | NO | PASS | #782 | PENDING |
| E0836 | `pages/merchant/decor/gallery/index.wxml:28` | `cy-btn` | `bindtap` | `save` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #782 | PENDING |
| E0837 | `pages/merchant/decor/index.wxml:6` | `cy-modal` | `bind:confirm` | `onUpgradeClose` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0838 | `pages/merchant/decor/index.wxml:20` | `cy-error` | `bind:retry` | `load` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0839 | `pages/merchant/decor/index.wxml:24` | `cy-empty` | `bind:cta` | `goApply` | 1 | navigation | event-contract | error,success | NO | PASS | #783,#782 | PENDING |
| E0840 | `pages/merchant/decor/index.wxml:31` | `view` | `bindtap` | `goPreview` | 1 | navigation | tap | error,success | NO | PASS | #783,#782 | PENDING |
| E0841 | `pages/merchant/decor/index.wxml:34` | `view` | `catchtap` | `noop` | 1 | other | tap | error,success | NO | PASS | #783,#782 | PENDING |
| E0842 | `pages/merchant/decor/index.wxml:36` | `cy-switch` | `bind:change` | `toggleBusiness` | 1 | write | event-contract | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0843 | `pages/merchant/decor/index.wxml:54` | `view` | `bindtap` | `onGuideTap` | 7 | external,navigation,state,write | tap | cancelled,denied,duplicate-trigger,error,first-error,first-timeout,forbidden-owner,second-error-after-first,second-timeout-after-first,success,timeout | YES | PASS | #783,#782 | PENDING |
| E0844 | `pages/merchant/decor/index.wxml:69` | `view` | `bindtap` | `uploadCover` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0845 | `pages/merchant/decor/index.wxml:73` | `view` | `bindtap` | `uploadLogo` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0846 | `pages/merchant/decor/index.wxml:77` | `view` | `bindtap` | `openField` | 1 | state | tap | success | NO | PASS | #783,#782 | PENDING |
| E0847 | `pages/merchant/decor/index.wxml:81` | `view` | `bindtap` | `goCategory` | 1 | state | tap | success | NO | PASS | #783,#782 | PENDING |
| E0848 | `pages/merchant/decor/index.wxml:85` | `view` | `bindtap` | `geoLocate` | 1 | external | tap | cancelled,denied,duplicate-trigger,error,forbidden-owner,success,timeout | YES | PASS | #783,#782 | PENDING |
| E0849 | `pages/merchant/decor/index.wxml:98` | `view` | `bindtap` | `openField` | 1 | state | tap | success | NO | PASS | #783,#782 | PENDING |
| E0850 | `pages/merchant/decor/index.wxml:102` | `view` | `bindtap` | `openField` | 1 | state | tap | success | NO | PASS | #783,#782 | PENDING |
| E0851 | `pages/merchant/decor/index.wxml:106` | `view` | `bindtap` | `goStory` | 1 | navigation | tap | error,success | NO | PASS | #783,#782 | PENDING |
| E0852 | `pages/merchant/decor/index.wxml:110` | `view` | `bindtap` | `openField` | 1 | state | tap | success | NO | PASS | #783,#782 | PENDING |
| E0853 | `pages/merchant/decor/index.wxml:114` | `view` | `bindtap` | `openTagPanel` | 1 | state | tap | success | NO | PASS | #783,#782 | PENDING |
| E0854 | `pages/merchant/decor/index.wxml:119` | `view` | `bindtap` | `goGallery` | 1 | navigation | tap | error,success | NO | PASS | #783,#782 | PENDING |
| E0855 | `pages/merchant/decor/index.wxml:132` | `view` | `bindtap` | `goCoopSetting` | 1 | navigation | tap | error,success | NO | PASS | #783,#782 | PENDING |
| E0856 | `pages/merchant/decor/index.wxml:136` | `view` | `bindtap` | `openFeatured` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0857 | `pages/merchant/decor/index.wxml:140` | `view` | `bindtap` | `goPerks` | 1 | navigation | tap | error,success | NO | PASS | #783,#782 | PENDING |
| E0858 | `pages/merchant/decor/index.wxml:149` | `view` | `bindtap` | `openTierSheet` | 1 | state | tap | success | NO | PASS | #783,#782 | PENDING |
| E0859 | `pages/merchant/decor/index.wxml:157` | `view` | `bindtap` | `goCityNode` | 1 | navigation | tap | error,success | NO | PASS | #783,#782 | PENDING |
| E0860 | `pages/merchant/decor/index.wxml:167` | `cy-sheet` | `bind:close` | `closeTagPanel` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0861 | `pages/merchant/decor/index.wxml:168` | `text` | `bindtap` | `confirmTags` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0862 | `pages/merchant/decor/index.wxml:174` | `text` | `bindtap` | `toggleTag` | 1 | state | tap | success | NO | PASS | #783,#782 | PENDING |
| E0863 | `pages/merchant/decor/index.wxml:179` | `input` | `bindinput` | `onCustomTag` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0864 | `pages/merchant/decor/index.wxml:180` | `text` | `bindtap` | `addCustomTag` | 1 | state | tap | success | NO | PASS | #783,#782 | PENDING |
| E0865 | `pages/merchant/decor/index.wxml:183` | `text` | `bindtap` | `toggleTag` | 1 | state | tap | success | NO | PASS | #783,#782 | PENDING |
| E0866 | `pages/merchant/decor/index.wxml:189` | `cy-sheet` | `bind:close` | `closeFeatured` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0867 | `pages/merchant/decor/index.wxml:190` | `text` | `bindtap` | `clearFeatured` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0868 | `pages/merchant/decor/index.wxml:194` | `cy-error` | `bind:retry` | `retryFeatured` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0869 | `pages/merchant/decor/index.wxml:196` | `view` | `bindtap` | `selectFeatured` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0870 | `pages/merchant/decor/index.wxml:203` | `cy-empty` | `bind:cta` | `goPublishActivity` | 1 | navigation | event-contract | error,success | NO | PASS | #783,#782 | PENDING |
| E0871 | `pages/merchant/decor/index.wxml:208` | `cy-sheet` | `bind:close` | `closeFieldSheet` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0872 | `pages/merchant/decor/index.wxml:209` | `text` | `bindtap` | `confirmField` | 4 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0873 | `pages/merchant/decor/index.wxml:211` | `input` | `bindinput` | `onFieldInput` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0874 | `pages/merchant/decor/index.wxml:216` | `cy-sheet` | `bind:close` | `closeTierSheet` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0875 | `pages/merchant/decor/index.wxml:222` | `view` | `bindtap` | `tapTier` | 1 | external | tap | cancelled,duplicate-trigger,error,success,timeout | YES | PASS | #783,#782 | PENDING |
| E0876 | `pages/merchant/decor/index.wxml:233` | `cy-category-sheet` | `bind:select` | `onCategorySelect` | 1 | write | event-contract | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0877 | `pages/merchant/decor/index.wxml:233` | `cy-category-sheet` | `bind:close` | `onCategorySheetClose` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0878 | `pages/merchant/decor/perks/index.wxml:2` | `cy-nav-bar` | `bind:back` | `onNavBack` | 1 | navigation | event-contract | error,success | NO | PASS | #783,#782 | PENDING |
| E0879 | `pages/merchant/decor/perks/index.wxml:5` | `cy-modal` | `bind:confirm` | `confirmDelete` | 1 | write | event-contract | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0880 | `pages/merchant/decor/perks/index.wxml:5` | `cy-modal` | `bind:cancel` | `cancelDelete` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0881 | `pages/merchant/decor/perks/index.wxml:9` | `cy-error` | `bind:retry` | `load` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0882 | `pages/merchant/decor/perks/index.wxml:21` | `text` | `bindtap` | `askDelete` | 1 | state | tap | success | NO | PASS | #783,#782 | PENDING |
| E0883 | `pages/merchant/decor/perks/index.wxml:26` | `cy-btn` | `bindtap` | `openForm` | 1 | state | tap | success | NO | PASS | #783,#782 | PENDING |
| E0884 | `pages/merchant/decor/perks/index.wxml:28` | `cy-sheet` | `bind:close` | `closeForm` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0885 | `pages/merchant/decor/perks/index.wxml:31` | `text` | `bindtap` | `pickType` | 1 | state | tap | success | NO | PASS | #783,#782 | PENDING |
| E0886 | `pages/merchant/decor/perks/index.wxml:33` | `input` | `bindinput` | `onInput` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0887 | `pages/merchant/decor/perks/index.wxml:34` | `input` | `bindinput` | `onInput` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0888 | `pages/merchant/decor/perks/index.wxml:35` | `input` | `bindinput` | `onInput` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0889 | `pages/merchant/decor/perks/index.wxml:36` | `input` | `bindinput` | `onInput` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0890 | `pages/merchant/decor/perks/index.wxml:37` | `picker` | `bindchange` | `onDate` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0891 | `pages/merchant/decor/perks/index.wxml:38` | `cy-btn` | `bindtap` | `save` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0892 | `pages/merchant/decor/story/index.wxml:2` | `cy-nav-bar` | `bind:back` | `onNavBack` | 1 | navigation | event-contract | error,success | NO | PASS | #783,#782 | PENDING |
| E0893 | `pages/merchant/decor/story/index.wxml:7` | `cy-error` | `bind:retry` | `load` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0894 | `pages/merchant/decor/story/index.wxml:11` | `cy-empty` | `bind:cta` | `goApply` | 1 | navigation | event-contract | error,success | NO | PASS | #783,#782 | PENDING |
| E0895 | `pages/merchant/decor/story/index.wxml:20` | `textarea` | `bindinput` | `onDesc` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E0896 | `pages/merchant/decor/story/index.wxml:23` | `cy-btn` | `bindtap` | `onSave` | 1 | write | tap | both-error,brand-error,brand-timeout,decor-error,decor-timeout,duplicate-trigger,forbidden-owner,success | NO | PASS | #783,#782 | PENDING |
| E0897 | `pages/merchant/discover/index.wxml:6` | `input` | `bindinput` | `onKeyword` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E0898 | `pages/merchant/discover/index.wxml:6` | `input` | `bindconfirm` | `doSearch` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E0899 | `pages/merchant/discover/index.wxml:7` | `text` | `bindtap` | `doSearch` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E0900 | `pages/merchant/discover/index.wxml:11` | `text` | `bindtap` | `toggleTag` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783 | PENDING |
| E0901 | `pages/merchant/discover/index.wxml:20` | `cy-error` | `bind:retry` | `load` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E0902 | `pages/merchant/discover/index.wxml:24` | `cy-merchant-card` | `bind:tap` | `goProfile` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E0903 | `pages/merchant/discover/index.wxml:24` | `cy-merchant-card` | `bind:action` | `goProfile` | 1 | navigation | event-contract | error,success | NO | PASS | #787,#783 | PENDING |
| E0904 | `pages/merchant/index/index.wxml:16` | `cy-empty` | `bind:cta` | `goLogin` | 1 | navigation | event-contract | error,success | NO | PASS | #787,#783 | PENDING |
| E0905 | `pages/merchant/index/index.wxml:22` | `cy-error` | `bind:retry` | `reloadConsole` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E0906 | `pages/merchant/index/index.wxml:28` | `scroll-view` | `bindscrolltolower` | `onScrollReachBottom` | 1 | other | event-contract | error,success | NO | PASS | #787,#783 | PENDING |
| E0907 | `pages/merchant/index/index.wxml:38` | `view` | `catchtap` | `toggleBusiness` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783 | PENDING |
| E0908 | `pages/merchant/index/index.wxml:44` | `view` | `bindtap` | `goBrandManage` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E0909 | `pages/merchant/index/index.wxml:62` | `view` | `bindtap` | `goScanQR` | 1 | external | tap | cancelled,denied,duplicate-trigger,error,forbidden-owner,success,timeout | YES | PASS | #787,#783 | PENDING |
| E0910 | `pages/merchant/index/index.wxml:68` | `view` | `bindtap` | `goCustomers` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E0911 | `pages/merchant/index/index.wxml:74` | `view` | `bindtap` | `goFinance` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E0912 | `pages/merchant/index/index.wxml:80` | `view` | `bindtap` | `openMoreMenu` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E0913 | `pages/merchant/index/index.wxml:95` | `text` | `bindtap` | `goPublishedProjects` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E0914 | `pages/merchant/index/index.wxml:104` | `view` | `bindtap` | `openProjectCard` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E0915 | `pages/merchant/index/index.wxml:123` | `view` | `catchtap` | `goProjectMessages` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E0916 | `pages/merchant/index/index.wxml:166` | `view` | `bindtap` | `goPublishTopic` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E0917 | `pages/merchant/index/index.wxml:175` | `text` | `bindtap` | `goMessages` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E0918 | `pages/merchant/index/index.wxml:179` | `view` | `bindtap` | `goMessages` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E0919 | `pages/merchant/index/index.wxml:196` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #787,#783 | PENDING |
| E0920 | `pages/merchant/index/index.wxml:197` | `cy-scene-sheet` | `bind:requestclose` | `closeMoreMenu` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E0921 | `pages/merchant/index/index.wxml:200` | `view` | `bindtap` | `onMoreItem` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E0922 | `pages/merchant/index/index.wxml:205` | `view` | `bindtap` | `onMoreItem` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E0923 | `pages/merchant/index/index.wxml:210` | `view` | `bindtap` | `onMoreItem` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E0924 | `pages/merchant/index/index.wxml:215` | `view` | `bindtap` | `onMoreItem` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E0925 | `pages/merchant/index/index.wxml:226` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #787,#783 | PENDING |
| E0926 | `pages/merchant/index/index.wxml:227` | `cy-scene-sheet` | `bind:requestclose` | `onChapterSheetClose` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E0927 | `pages/merchant/index/index.wxml:231` | `view` | `bindtap` | `onChapterPick` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E0928 | `pages/merchant/index/index.wxml:240` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #787,#783 | PENDING |
| E0929 | `pages/merchant/index/index.wxml:241` | `cy-scene-sheet` | `bind:requestclose` | `onStationSheetClose` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E0930 | `pages/merchant/index/index.wxml:245` | `view` | `bindtap` | `onStationPick` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E0931 | `pages/merchant/index/index.wxml:253` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #787,#783 | PENDING |
| E0932 | `pages/merchant/index/index.wxml:254` | `cy-scene-sheet` | `bind:requestclose` | `closeVerificationResult` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E0933 | `pages/merchant/index/index.wxml:258` | `view` | `bindtap` | `closeVerificationResult` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E0934 | `pages/merchant/index/index.wxml:267` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #787,#783 | PENDING |
| E0935 | `pages/merchant/index/index.wxml:268` | `cy-scene-sheet` | `bind:requestclose` | `requestSceneClose` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E0936 | `pages/merchant/index/index.wxml:268` | `cy-scene-sheet` | `bind:back` | `backScene` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E0937 | `pages/merchant/index/index.wxml:269` | `cy-scene-route-content` | `bind:open` | `openChildScene` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E0938 | `pages/merchant/index/index.wxml:269` | `cy-scene-route-content` | `bind:back` | `backScene` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E0939 | `pages/merchant/index/index.wxml:269` | `cy-scene-route-content` | `bind:close` | `closeScene` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E0940 | `pages/merchant/index/index.wxml:269` | `cy-scene-route-content` | `bind:dirtychange` | `onSceneDirtyChange` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E0941 | `pages/merchant/index/index.wxml:271` | `cy-btn` | `bindtap` | `submitSceneForm` | 1 | other | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E0942 | `pages/merchant/index/index.wxml:276` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #787,#783 | PENDING |
| E0943 | `pages/merchant/index/index.wxml:277` | `cy-scene-sheet` | `bind:requestclose` | `requestSceneClose` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E0944 | `pages/merchant/index/index.wxml:277` | `cy-scene-sheet` | `bind:back` | `backScene` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E0945 | `pages/merchant/index/index.wxml:278` | `cy-scene-play-activity-detail` | `bind:open` | `openChildScene` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E0946 | `pages/merchant/index/index.wxml:278` | `cy-scene-play-activity-detail` | `bind:close` | `closeScene` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E0947 | `pages/merchant/index/index.wxml:281` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #787,#783 | PENDING |
| E0948 | `pages/merchant/index/index.wxml:282` | `cy-scene-sheet` | `bind:requestclose` | `requestSceneClose` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E0949 | `pages/merchant/index/index.wxml:282` | `cy-scene-sheet` | `bind:back` | `backScene` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E0950 | `pages/merchant/index/index.wxml:283` | `cy-scene-roam-poi-detail` | `bind:open` | `openChildScene` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E0951 | `pages/merchant/index/index.wxml:283` | `cy-scene-roam-poi-detail` | `bind:close` | `closeScene` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E0952 | `pages/merchant/index/index.wxml:286` | `cy-modal` | `bind:confirm` | `confirmSceneDiscard` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E0953 | `pages/merchant/index/index.wxml:286` | `cy-modal` | `bind:cancel` | `cancelSceneDiscard` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E0954 | `pages/merchant/index/index.wxml:289` | `cy-privacy-gate` | `bind:settled` | `onPrivacyGateSettled` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E0955 | `pages/merchant/ledger/batch-detail/index.wxml:1` | `cy-nav-bar` | `bind:back` | `onNavBack` | 1 | navigation | event-contract | error,success | NO | PASS | #783,#782 | PENDING |
| E0956 | `pages/merchant/ledger/batch-detail/index.wxml:1` | `cy-error` | `bind:retry` | `load` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0957 | `pages/merchant/ledger/batch-detail/index.wxml:11` | `text` | `bindtap` | `copyVoucher` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #783,#782 | PENDING |
| E0958 | `pages/merchant/ledger/index.wxml:2` | `cy-nav-bar` | `bind:back` | `onNavBack` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E0959 | `pages/merchant/ledger/index.wxml:5` | `cy-error` | `bind:retry` | `retry` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | — | PENDING |
| E0960 | `pages/merchant/ledger/index.wxml:9` | `cy-tabs` | `bind:change` | `onRedemptionFilter` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | — | PENDING |
| E0961 | `pages/merchant/ledger/index.wxml:20` | `view` | `bindtap` | `goRedemptionDetail` | 1 | navigation | tap | error,success | NO | PASS | — | PENDING |
| E0962 | `pages/merchant/ledger/index.wxml:49` | `cy-btn` | `bindtap` | `goAssets` | 1 | navigation | tap | error,success | NO | PASS | — | PENDING |
| E0963 | `pages/merchant/ledger/index.wxml:50` | `cy-btn` | `bindtap` | `enableCoopSettleAlerts` | 1 | other | tap | error,success | NO | PASS | — | PENDING |
| E0964 | `pages/merchant/ledger/index.wxml:71` | `view` | `bindtap` | `goBatchDetail` | 1 | navigation | tap | error,success | NO | PASS | — | PENDING |
| E0965 | `pages/merchant/ledger/index.wxml:87` | `view` | `bindtap` | `goInbox` | 1 | navigation | tap | error,success | NO | PASS | — | PENDING |
| E0966 | `pages/merchant/ledger/order-detail/index.wxml:2` | `cy-nav-bar` | `bind:back` | `onNavBack` | 1 | navigation | event-contract | error,success | NO | PASS | #782 | PENDING |
| E0967 | `pages/merchant/ledger/order-detail/index.wxml:5` | `cy-error` | `bind:retry` | `load` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #782 | PENDING |
| E0968 | `pages/merchant/ledger/order-detail/index.wxml:19` | `text` | `bindtap` | `goAssets` | 1 | navigation | tap | error,success | NO | PASS | #782 | PENDING |
| E0969 | `pages/merchant/marketing/ai-insight/index.wxml:3` | `cy-nav-bar` | `bind:back` | `onNavBack` | 1 | navigation | event-contract | error,success | NO | PASS | #783,#782 | PENDING |
| E0970 | `pages/merchant/marketing/ai-insight/index.wxml:7` | `cy-error` | `bind:retry` | `retry` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783,#782 | PENDING |
| E0971 | `pages/merchant/marketing/ai-insight/index.wxml:17` | `view` | `bindtap` | `goProfileSetup` | 1 | navigation | tap | error,success | NO | PASS | #783,#782 | PENDING |
| E0972 | `pages/merchant/marketing/ai-insight/index.wxml:21` | `view` | `bindtap` | `goProfileSetup` | 1 | navigation | tap | error,success | NO | PASS | #783,#782 | PENDING |
| E0973 | `pages/merchant/marketing/ai-insight/index.wxml:30` | `view` | `bindtap` | `goCoopCenter` | 1 | navigation | tap | error,success | NO | PASS | #783,#782 | PENDING |
| E0974 | `pages/merchant/marketing/ai-insight/index.wxml:152` | `view` | `bindtap` | `goRecTopic` | 1 | navigation | tap | error,success | NO | PASS | #783,#782 | PENDING |
| E0975 | `pages/merchant/marketing/ai-insight/index.wxml:171` | `view` | `bindtap` | `goRecPartner` | 1 | navigation | tap | error,success | NO | PASS | #783,#782 | PENDING |
| E0976 | `pages/merchant/marketing/ai-insight/index.wxml:209` | `view` | `bindtap` | `goSuggestion` | 1 | navigation | tap | error,success | NO | PASS | #783,#782 | PENDING |
| E0977 | `pages/merchant/marketing/index.wxml:30` | `cy-card` | `bindtap` | `loadMarketingHome` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #783 | PENDING |
| E0978 | `pages/merchant/marketing/index.wxml:34` | `view` | `bindtap` | `go` | 1 | navigation | tap | error,success | NO | PASS | #783 | PENDING |
| E0979 | `pages/merchant/marketing/index.wxml:39` | `view` | `bindtap` | `go` | 1 | navigation | tap | error,success | NO | PASS | #783 | PENDING |
| E0980 | `pages/merchant/marketing/index.wxml:49` | `view` | `bindtap` | `goMyContent` | 1 | navigation | tap | error,success | NO | PASS | #783 | PENDING |
| E0981 | `pages/merchant/marketing/index.wxml:66` | `view` | `bindtap` | `goCoopCenter` | 1 | navigation | tap | error,success | NO | PASS | #783 | PENDING |
| E0982 | `pages/merchant/profile/index.wxml:9` | `cy-error` | `bind:retry` | `retryLoad` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | — | PENDING |
| E0983 | `pages/merchant/profile/index.wxml:12` | `cy-empty` | `bind:cta` | `goHome` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E0984 | `pages/merchant/profile/index.wxml:15` | `cy-empty` | `bind:cta` | `goHome` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E0985 | `pages/merchant/profile/index.wxml:19` | `text` | `bindtap` | `goHome` | 1 | navigation | tap | error,success | NO | PASS | — | PENDING |
| E0986 | `pages/merchant/profile/index.wxml:20` | `text` | `bindtap` | `goBack` | 1 | navigation | tap | error,success | NO | PASS | — | PENDING |
| E0987 | `pages/merchant/relation/index.wxml:21` | `cy-tabs` | `bind:change` | `onTabChange` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E0988 | `pages/merchant/relation/index.wxml:25` | `cy-error` | `bind:retry` | `loadRelationHome` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E0989 | `pages/merchant/relation/index.wxml:35` | `cy-merchant-card` | `bind:tap` | `goMerchant` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0990 | `pages/merchant/relation/index.wxml:35` | `cy-merchant-card` | `bind:action` | `goMerchant` | 1 | navigation | event-contract | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0991 | `pages/merchant/relation/index.wxml:55` | `cy-club-card` | `bind:tap` | `goClub` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0992 | `pages/merchant/relation/index.wxml:55` | `cy-club-card` | `bind:action` | `goClub` | 1 | navigation | event-contract | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E0993 | `pages/mylike/mylike.wxml:10` | `cy-error` | `bind:retry` | `refreshData` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E0994 | `pages/mylike/mylike.wxml:18` | `image` | `bindtap` | `goTopic` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E0995 | `pages/mylike/mylike.wxml:26` | `button` | `bindtap` | `topicUnlike` | 1 | write | tap | duplicate-trigger,error,modal-cancelled,success,timeout | NO | PASS | #787,#783 | PENDING |
| E0996 | `pages/play/celebrate/index.wxml:3` | `cy-nav-bar` | `bind:back` | `goBack` | 1 | navigation | event-contract | error,success | NO | PASS | #783 | PENDING |
| E0997 | `pages/play/celebrate/index.wxml:17` | `view` | `bindtap` | `joinNow` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783 | PENDING |
| E0998 | `pages/play/celebrate/index.wxml:26` | `text` | `bindtap` | `tapWinCategory` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783 | PENDING |
| E0999 | `pages/play/celebrate/index.wxml:29` | `input` | `bindblur` | `onWinLabel` | 1 | write | event-contract | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783 | PENDING |
| E1000 | `pages/play/celebrate/index.wxml:34` | `text` | `bindtap` | `tapRitualStyle` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783 | PENDING |
| E1001 | `pages/play/celebrate/index.wxml:45` | `input` | `bindblur` | `onArea` | 1 | write | event-contract | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783 | PENDING |
| E1002 | `pages/play/celebrate/index.wxml:50` | `picker` | `bindchange` | `onGroupSize` | 1 | write | event-contract | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783 | PENDING |
| E1003 | `pages/play/celebrate/index.wxml:56` | `picker` | `bindchange` | `onStartTime` | 1 | write | event-contract | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783 | PENDING |
| E1004 | `pages/play/celebrate/index.wxml:60` | `picker` | `bindchange` | `onEndTime` | 1 | write | event-contract | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783 | PENDING |
| E1005 | `pages/play/celebrate/index.wxml:68` | `input` | `bindblur` | `onBudget` | 1 | write | event-contract | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783 | PENDING |
| E1006 | `pages/play/celebrate/index.wxml:74` | `input` | `bindblur` | `onWalkLimit` | 1 | write | event-contract | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783 | PENDING |
| E1007 | `pages/play/celebrate/index.wxml:81` | `text` | `bindtap` | `tapMood` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783 | PENDING |
| E1008 | `pages/play/celebrate/index.wxml:95` | `input` | `bindinput` | `onMyMust` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1009 | `pages/play/celebrate/index.wxml:100` | `input` | `bindinput` | `onMyNoGo` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1010 | `pages/play/celebrate/index.wxml:102` | `view` | `bindtap` | `submitLimits` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783 | PENDING |
| E1011 | `pages/play/celebrate/index.wxml:119` | `text` | `bindtap` | `openSlotEdit` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E1012 | `pages/play/celebrate/index.wxml:121` | `text` | `bindtap` | `removeSlot` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783 | PENDING |
| E1013 | `pages/play/celebrate/index.wxml:127` | `input` | `bindinput` | `onEditField` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1014 | `pages/play/celebrate/index.wxml:129` | `picker` | `bindchange` | `onEditField` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1015 | `pages/play/celebrate/index.wxml:133` | `picker` | `bindchange` | `onEditField` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1016 | `pages/play/celebrate/index.wxml:140` | `input` | `bindinput` | `onEditField` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1017 | `pages/play/celebrate/index.wxml:143` | `input` | `bindinput` | `onEditField` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1018 | `pages/play/celebrate/index.wxml:146` | `view` | `bindtap` | `closeSlotEdit` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E1019 | `pages/play/celebrate/index.wxml:147` | `view` | `bindtap` | `saveSlot` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783 | PENDING |
| E1020 | `pages/play/celebrate/index.wxml:157` | `view` | `bindtap` | `runCheck` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #783 | PENDING |
| E1021 | `pages/play/celebrate/index.wxml:172` | `view` | `bindtap` | `resolveDrill` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #783 | PENDING |
| E1022 | `pages/play/celebrate/index.wxml:175` | `view` | `bindtap` | `startDrill` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #783 | PENDING |
| E1023 | `pages/play/celebrate/index.wxml:189` | `view` | `bindtap` | `voteYes` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #783 | PENDING |
| E1024 | `pages/play/celebrate/index.wxml:190` | `view` | `bindtap` | `voteNo` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #783 | PENDING |
| E1025 | `pages/play/celebrate/index.wxml:202` | `text` | `bindtap` | `pickSignoffPrompt` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E1026 | `pages/play/celebrate/index.wxml:207` | `text` | `bindtap` | `pickSignoffCard` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E1027 | `pages/play/celebrate/index.wxml:210` | `input` | `bindinput` | `onSignoffCustom` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1028 | `pages/play/celebrate/index.wxml:211` | `view` | `bindtap` | `submitSignoff` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783 | PENDING |
| E1029 | `pages/play/celebrate/index.wxml:218` | `input` | `bindinput` | `onBookProof` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1030 | `pages/play/celebrate/index.wxml:220` | `input` | `bindinput` | `onBookOwner` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1031 | `pages/play/celebrate/index.wxml:221` | `view` | `bindtap` | `submitBook` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783 | PENDING |
| E1032 | `pages/play/components/fragment/index.wxml:1` | `view` | `catchtap` | `onTap` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E1033 | `pages/play/components/scene-qr-ticket/index.wxml:2` | `cy-error` | `bind:retry` | `retry` | 1 | write | event-contract | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787 | PENDING |
| E1034 | `pages/play/components/scene-qr-ticket/index.wxml:25` | `view` | `bindtap` | `close` | 1 | other | tap | error,success | NO | PASS | #787 | PENDING |
| E1035 | `pages/play/components/scene-qr-ticket/index.wxml:26` | `view` | `bindtap` | `openBigQr` | 1 | state | tap | success | NO | PASS | #787 | PENDING |
| E1036 | `pages/play/components/scene-qr-ticket/index.wxml:30` | `view` | `bindtap` | `closeBigQr` | 1 | state | tap | success | NO | PASS | #787 | PENDING |
| E1037 | `pages/play/components/scene-qr-ticket/index.wxml:34` | `view` | `bindtap` | `closeBigQr` | 1 | state | tap | success | NO | PASS | #787 | PENDING |
| E1038 | `pages/play/components/scene-qr-ticket/index.wxml:39` | `view` | `bindtap` | `retry` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787 | PENDING |
| E1039 | `pages/play/components/story-sheet/index.wxml:3` | `view` | `bindtap` | `onClose` | 1 | other | tap | error,success | NO | PASS | — | PENDING |
| E1040 | `pages/play/components/story-sheet/index.wxml:5` | `view` | `catchtap` | `noop` | 1 | other | tap | error,success | NO | PASS | — | PENDING |
| E1041 | `pages/play/components/story-sheet/index.wxml:11` | `view` | `bindtap` | `onClose` | 1 | other | tap | error,success | NO | PASS | — | PENDING |
| E1042 | `pages/play/index.wxml:7` | `view` | `bindtap` | `previewGoBack` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1043 | `pages/play/index.wxml:24` | `free-map` | `bind:pintap` | `onPin` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1044 | `pages/play/index.wxml:47` | `view` | `bindtap` | `retryLeadProgress` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1045 | `pages/play/index.wxml:53` | `view` | `bindtap` | `goCouponWallet` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1046 | `pages/play/index.wxml:70` | `scroll-view` | `bindscroll` | `onChapterScroll` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1047 | `pages/play/index.wxml:77` | `view` | `bindtap` | `toggleChapterAudio` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1048 | `pages/play/index.wxml:89` | `view` | `bindtap` | `closeChapterFull` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1049 | `pages/play/index.wxml:107` | `view` | `bindtap` | `closeChapterPick` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1050 | `pages/play/index.wxml:112` | `view` | `bindtap` | `onPickChapter` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1051 | `pages/play/index.wxml:130` | `view` | `bindtap` | `openPoiSearch` | 1 | other | tap | error,success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1052 | `pages/play/index.wxml:135` | `view` | `bindtap` | `closePoiCard` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1053 | `pages/play/index.wxml:139` | `swiper` | `bindchange` | `onPoiSwipe` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1054 | `pages/play/index.wxml:153` | `view` | `catchtap` | `onPoiNav` | 1 | external | tap | denied,duplicate-trigger,error,forbidden-owner,success,timeout | YES | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1055 | `pages/play/index.wxml:177` | `view` | `catchtap` | `stopNav` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1056 | `pages/play/index.wxml:235` | `view` | `bindtap` | `onSeatTap` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1057 | `pages/play/index.wxml:266` | `view` | `bindtap` | `openPack` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1058 | `pages/play/index.wxml:267` | `view` | `bindtouchstart` | `onPackTouchStart` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1059 | `pages/play/index.wxml:267` | `view` | `catchtouchmove` | `onPackTouchMove` | 1 | external | event-contract | duplicate-trigger,error,success,timeout | YES | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1060 | `pages/play/index.wxml:268` | `view` | `bindtouchend` | `onPackTouchEnd` | 1 | external | event-contract | duplicate-trigger,error,success,timeout | YES | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1061 | `pages/play/index.wxml:268` | `view` | `bindtouchcancel` | `onPackTouchEnd` | 1 | external | event-contract | duplicate-trigger,error,success,timeout | YES | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1062 | `pages/play/index.wxml:314` | `view` | `bindtap` | `openEntryQr` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1063 | `pages/play/index.wxml:318` | `view` | `bindtap` | `openChapterFull` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1064 | `pages/play/index.wxml:323` | `view` | `bindtap` | `openNext` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1065 | `pages/play/index.wxml:354` | `view` | `bindtap` | `retryArrival` | 1 | external | tap | cancelled,denied,duplicate-trigger,error,forbidden-owner,success,timeout | YES | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1066 | `pages/play/index.wxml:365` | `view` | `bindtap` | `toggleSessionTools` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1067 | `pages/play/index.wxml:368` | `view` | `bindtap` | `onRunToggle` | 1 | external | tap | denied,duplicate-trigger,error,forbidden-owner,success,timeout | YES | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1068 | `pages/play/index.wxml:371` | `view` | `bindtap` | `onRunStop` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,modal-cancelled,success,timeout | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1069 | `pages/play/index.wxml:377` | `view` | `bindtap` | `onLeftAction` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1070 | `pages/play/index.wxml:381` | `view` | `bindtap` | `onRunToggle` | 1 | external | tap | denied,duplicate-trigger,error,forbidden-owner,success,timeout | YES | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1071 | `pages/play/index.wxml:390` | `view` | `bindtap` | `onScanArrive` | 1 | external | tap | cancelled,denied,duplicate-trigger,error,forbidden-owner,success,timeout | YES | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1072 | `pages/play/index.wxml:401` | `view` | `bindtap` | `leadBroadcastFromTools` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,modal-cancelled,success,timeout | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1073 | `pages/play/index.wxml:407` | `view` | `bindtap` | `leadVerifyMemberTicketFromTools` | 1 | external | tap | cancelled,denied,duplicate-trigger,error,success,timeout | YES | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1074 | `pages/play/index.wxml:412` | `view` | `bindtap` | `leadShowGroupCodeFromTools` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1075 | `pages/play/index.wxml:417` | `view` | `bindtap` | `leadUnlockFromTools` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,modal-cancelled,success,timeout | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1076 | `pages/play/index.wxml:422` | `view` | `bindtap` | `leadSettleFromTools` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,modal-cancelled,success,timeout | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1077 | `pages/play/index.wxml:434` | `view` | `bindtap` | `onGoTap` | 1 | external | tap | denied,duplicate-trigger,error,forbidden-owner,success,timeout | YES | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1078 | `pages/play/index.wxml:441` | `view` | `bindtap` | `openTicketFromTools` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1079 | `pages/play/index.wxml:446` | `view` | `bindtap` | `openJournalFromTools` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1080 | `pages/play/index.wxml:452` | `view` | `bindtap` | `reopenIntro` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1081 | `pages/play/index.wxml:455` | `view` | `bindtap` | `toggleReducedMotion` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1082 | `pages/play/index.wxml:492` | `cy-btn` | `bindtap` | `leadArrive` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1083 | `pages/play/index.wxml:494` | `cy-btn` | `bindtap` | `leadStart` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1084 | `pages/play/index.wxml:499` | `view` | `bindtap` | `openEggCard` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1085 | `pages/play/index.wxml:503` | `view` | `bindtap` | `closeEggCard` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1086 | `pages/play/index.wxml:508` | `cy-btn` | `bindtap` | `closeEggCard` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1087 | `pages/play/index.wxml:512` | `view` | `bindtap` | `openFinish` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1088 | `pages/play/index.wxml:524` | `view` | `bindtap` | `closeSheet` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1089 | `pages/play/index.wxml:526` | `view` | `bindtap` | `previewNodeImages` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1090 | `pages/play/index.wxml:557` | `cy-btn` | `bindtap` | `continueNext` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1091 | `pages/play/index.wxml:569` | `cy-btn` | `bindtap` | `startGame` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1092 | `pages/play/index.wxml:573` | `cy-btn` | `bindtap` | `onSheetGoTap` | 1 | external | tap | denied,duplicate-trigger,error,forbidden-owner,success,timeout | YES | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1093 | `pages/play/index.wxml:574` | `text` | `bindtap` | `onScanArrive` | 1 | external | tap | cancelled,denied,duplicate-trigger,error,forbidden-owner,success,timeout | YES | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1094 | `pages/play/index.wxml:576` | `text` | `bindtap` | `onScanArrive` | 1 | external | tap | cancelled,denied,duplicate-trigger,error,forbidden-owner,success,timeout | YES | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1095 | `pages/play/index.wxml:580` | `view` | `bindtap` | `sheetNav` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1096 | `pages/play/index.wxml:587` | `view` | `bindtap` | `openMerchantFromSheet` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1097 | `pages/play/index.wxml:591` | `view` | `bindtap` | `openTicketFromSheet` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1098 | `pages/play/index.wxml:595` | `view` | `bindtap` | `openPoiFromSheet` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1099 | `pages/play/index.wxml:601` | `cy-btn` | `bindtap` | `previewEditNode` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1100 | `pages/play/index.wxml:610` | `view` | `bindtap` | `closeGame` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1101 | `pages/play/index.wxml:615` | `view` | `bindtap` | `closeGame` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1102 | `pages/play/index.wxml:620` | `view` | `bindtap` | `toggleAudio` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1103 | `pages/play/index.wxml:637` | `image` | `bindtap` | `previewQuestionImg` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1104 | `pages/play/index.wxml:638` | `view` | `bindtap` | `toggleQuestionAudio` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1105 | `pages/play/index.wxml:644` | `input` | `bindinput` | `onInput` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1106 | `pages/play/index.wxml:648` | `image` | `bindtap` | `previewQuestionImg` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1107 | `pages/play/index.wxml:649` | `view` | `bindtap` | `toggleQuestionAudio` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1108 | `pages/play/index.wxml:656` | `view` | `bindtap` | `selOpt` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1109 | `pages/play/index.wxml:662` | `view` | `catchtap` | `toggleOptionAudio` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1110 | `pages/play/index.wxml:671` | `view` | `bindtap` | `retryPreference` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1111 | `pages/play/index.wxml:686` | `text` | `bindtap` | `correctPreferenceTag` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1112 | `pages/play/index.wxml:688` | `text` | `bindtap` | `skipPreferenceTag` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1113 | `pages/play/index.wxml:689` | `text` | `bindtap` | `confirmPreferenceTag` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1114 | `pages/play/index.wxml:700` | `view` | `bindtap` | `useInheritedPreference` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1115 | `pages/play/index.wxml:703` | `text` | `bindtap` | `answerPreferenceHere` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1116 | `pages/play/index.wxml:714` | `view` | `bindtap` | `selectPreferenceOption` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1117 | `pages/play/index.wxml:725` | `view` | `bindtap` | `photoGame` | 1 | external | tap | duplicate-trigger,error,forbidden-owner,success,timeout | YES | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1118 | `pages/play/index.wxml:734` | `text` | `bindtap` | `retryGameSubmission` | 5 | external | tap | cancelled,denied,duplicate-trigger,error,forbidden-owner,success,timeout | YES | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1119 | `pages/play/index.wxml:740` | `text` | `bindtap` | `useHint` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1120 | `pages/play/index.wxml:741` | `text` | `bindtap` | `revealAnswer` | 1 | external | tap | duplicate-trigger,error,forbidden-owner,success,timeout | YES | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1121 | `pages/play/index.wxml:748` | `view` | `bindtap` | `scanGame` | 1 | external | tap | cancelled,denied,duplicate-trigger,error,forbidden-owner,success,timeout | YES | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1122 | `pages/play/index.wxml:749` | `view` | `bindtap` | `photoGame` | 1 | external | tap | duplicate-trigger,error,forbidden-owner,success,timeout | YES | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1123 | `pages/play/index.wxml:750` | `view` | `bindtap` | `finishPreferenceResult` | 1 | external | tap | duplicate-trigger,error,forbidden-owner,success,timeout | YES | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1124 | `pages/play/index.wxml:751` | `view` | `bindtap` | `nextPreferenceStep` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1125 | `pages/play/index.wxml:752` | `view` | `bindtap` | `submitGame` | 1 | external | tap | duplicate-trigger,error,forbidden-owner,success,timeout | YES | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1126 | `pages/play/index.wxml:769` | `view` | `bindtap` | `goBackDetail` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1127 | `pages/play/index.wxml:774` | `view` | `bindtap` | `openThemeQr` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1128 | `pages/play/index.wxml:776` | `cy-tabs` | `bind:change` | `switchIntroTab` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1129 | `pages/play/index.wxml:780` | `swiper` | `bindchange` | `onIntroCardSwipe` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1130 | `pages/play/index.wxml:782` | `view` | `bindtap` | `onChapterCardTap` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1131 | `pages/play/index.wxml:800` | `view` | `bindtap` | `goCelebrateBoard` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1132 | `pages/play/index.wxml:829` | `view` | `bindtap` | `onScanArrive` | 1 | external | tap | cancelled,denied,duplicate-trigger,error,forbidden-owner,success,timeout | YES | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1133 | `pages/play/index.wxml:833` | `view` | `bindtap` | `closeIntro` | 1 | external | tap | denied,duplicate-trigger,error,forbidden-owner,success,timeout | YES | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1134 | `pages/play/index.wxml:838` | `view` | `bindtap` | `openStackOrChapters` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1135 | `pages/play/index.wxml:839` | `view` | `bindtap` | `openEntryQr` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1136 | `pages/play/index.wxml:856` | `view` | `bindtap` | `onScriptTileTap` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1137 | `pages/play/index.wxml:888` | `text` | `bindtap` | `closeJournal` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1138 | `pages/play/index.wxml:893` | `view` | `bindtouchmove` | `onJournalTouchMove` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1139 | `pages/play/index.wxml:901` | `view` | `bindtap` | `openStory` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1140 | `pages/play/index.wxml:902` | `view` | `bindtap` | `goToNode` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1141 | `pages/play/index.wxml:912` | `view` | `bindtap` | `closeFinish` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1142 | `pages/play/index.wxml:925` | `view` | `bindtap` | `retryRouteThumb` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1143 | `pages/play/index.wxml:948` | `text` | `bindtap` | `revokeOsTag` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1144 | `pages/play/index.wxml:976` | `view` | `bindtap` | `openFinishJournal` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1145 | `pages/play/index.wxml:983` | `text` | `bindtap` | `retryEnding` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1146 | `pages/play/index.wxml:984` | `view` | `bindtap` | `goFinishRouteRecommendation` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1147 | `pages/play/index.wxml:996` | `view` | `bindtap` | `closeFinish` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1148 | `pages/play/index.wxml:998` | `text` | `bindtap` | `shareFinish` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1149 | `pages/play/index.wxml:1000` | `text` | `bindtap` | `saveFinishCard` | 1 | external | tap | duplicate-trigger,error,openSetting-cancelled,openSetting-denied,saveImageToPhotosAlbum-cancelled,saveImageToPhotosAlbum-denied,success,timeout | YES | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1150 | `pages/play/index.wxml:1006` | `view` | `bindtap` | `closeEnding` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1151 | `pages/play/index.wxml:1016` | `cy-btn` | `bindtap` | `saveFinishCard` | 1 | external | tap | duplicate-trigger,error,openSetting-cancelled,openSetting-denied,saveImageToPhotosAlbum-cancelled,saveImageToPhotosAlbum-denied,success,timeout | YES | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1152 | `pages/play/index.wxml:1017` | `cy-btn` | `bindtap` | `shareFinish` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1153 | `pages/play/index.wxml:1022` | `cy-fragment` | `bind:done` | `closeFragment` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1154 | `pages/play/index.wxml:1037` | `cy-btn` | `bindtap` | `goSignup` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1155 | `pages/play/index.wxml:1038` | `cy-btn` | `bindtap` | `doRelogin` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1156 | `pages/play/index.wxml:1040` | `cy-btn` | `bindtap` | `reloadPlay` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1157 | `pages/play/index.wxml:1041` | `cy-btn` | `bindtap` | `goBackDetail` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1158 | `pages/play/index.wxml:1044` | `cy-btn` | `bindtap` | `goBackDetail` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1159 | `pages/play/index.wxml:1045` | `cy-btn` | `bindtap` | `goSignup` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1160 | `pages/play/index.wxml:1082` | `view` | `bindtap` | `closeVoucher` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1161 | `pages/play/index.wxml:1084` | `view` | `bindtap` | `openEntryQr` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1162 | `pages/play/index.wxml:1089` | `view` | `bindtap` | `closeThemeQr` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1163 | `pages/play/index.wxml:1093` | `view` | `bindtap` | `closeThemeQr` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1164 | `pages/play/index.wxml:1113` | `view` | `bindtap` | `goCouponWallet` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1165 | `pages/play/index.wxml:1116` | `cy-slide-confirm` | `bind:confirm` | `closeMedal` | 1 | write | event-contract | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1166 | `pages/play/index.wxml:1122` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1167 | `pages/play/index.wxml:1123` | `cy-scene-sheet` | `bind:requestclose` | `requestSceneClose` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1168 | `pages/play/index.wxml:1123` | `cy-scene-sheet` | `bind:back` | `backScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1169 | `pages/play/index.wxml:1124` | `cy-scene-play-activity-detail` | `bind:open` | `openChildScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1170 | `pages/play/index.wxml:1124` | `cy-scene-play-activity-detail` | `bind:close` | `closeScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1171 | `pages/play/index.wxml:1127` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1172 | `pages/play/index.wxml:1128` | `cy-scene-sheet` | `bind:requestclose` | `requestSceneClose` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1173 | `pages/play/index.wxml:1128` | `cy-scene-sheet` | `bind:back` | `backScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1174 | `pages/play/index.wxml:1129` | `cy-scene-roam-task-list` | `bind:open` | `openChildScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1175 | `pages/play/index.wxml:1129` | `cy-scene-roam-task-list` | `bind:close` | `closeScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1176 | `pages/play/index.wxml:1132` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1177 | `pages/play/index.wxml:1133` | `cy-scene-sheet` | `bind:requestclose` | `requestSceneClose` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1178 | `pages/play/index.wxml:1133` | `cy-scene-sheet` | `bind:back` | `backScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1179 | `pages/play/index.wxml:1134` | `cy-scene-roam-poi-detail` | `bind:open` | `openChildScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1180 | `pages/play/index.wxml:1134` | `cy-scene-roam-poi-detail` | `bind:close` | `closeScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1181 | `pages/play/index.wxml:1137` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1182 | `pages/play/index.wxml:1138` | `cy-scene-sheet` | `bind:requestclose` | `requestSceneClose` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1183 | `pages/play/index.wxml:1138` | `cy-scene-sheet` | `bind:back` | `backScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1184 | `pages/play/index.wxml:1139` | `cy-scene-roam-discover` | `bind:open` | `openChildScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1185 | `pages/play/index.wxml:1139` | `cy-scene-roam-discover` | `bind:close` | `closeScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1186 | `pages/play/index.wxml:1142` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1187 | `pages/play/index.wxml:1143` | `cy-scene-sheet` | `bind:requestclose` | `requestSceneClose` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1188 | `pages/play/index.wxml:1143` | `cy-scene-sheet` | `bind:back` | `backScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1189 | `pages/play/index.wxml:1144` | `cy-scene-roam-history` | `bind:open` | `openChildScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1190 | `pages/play/index.wxml:1144` | `cy-scene-roam-history` | `bind:close` | `closeScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1191 | `pages/play/index.wxml:1147` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1192 | `pages/play/index.wxml:1148` | `cy-scene-sheet` | `bind:requestclose` | `requestSceneClose` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1193 | `pages/play/index.wxml:1148` | `cy-scene-sheet` | `bind:back` | `backScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1194 | `pages/play/index.wxml:1149` | `cy-scene-roam-session` | `bind:share` | `onSceneSessionShare` | 1 | external | event-contract | duplicate-trigger,error,success,timeout | YES | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1195 | `pages/play/index.wxml:1149` | `cy-scene-roam-session` | `bind:close` | `closeScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1196 | `pages/play/index.wxml:1152` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1197 | `pages/play/index.wxml:1153` | `cy-scene-sheet` | `bind:requestclose` | `requestSceneClose` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1198 | `pages/play/index.wxml:1153` | `cy-scene-sheet` | `bind:back` | `backScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1199 | `pages/play/index.wxml:1154` | `cy-scene-game-coupon-wallet` | `bind:open` | `openChildScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1200 | `pages/play/index.wxml:1154` | `cy-scene-game-coupon-wallet` | `bind:close` | `closeScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1201 | `pages/play/index.wxml:1157` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1202 | `pages/play/index.wxml:1158` | `cy-scene-sheet` | `bind:requestclose` | `requestSceneClose` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1203 | `pages/play/index.wxml:1158` | `cy-scene-sheet` | `bind:back` | `backScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1204 | `pages/play/index.wxml:1159` | `cy-scene-qr-coupon` | `bind:close` | `backScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1205 | `pages/play/index.wxml:1162` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1206 | `pages/play/index.wxml:1163` | `cy-scene-sheet` | `bind:requestclose` | `requestSceneClose` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1207 | `pages/play/index.wxml:1163` | `cy-scene-sheet` | `bind:back` | `backScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1208 | `pages/play/index.wxml:1164` | `cy-scene-qr-group-code` | `bind:close` | `backScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1209 | `pages/play/index.wxml:1167` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1210 | `pages/play/index.wxml:1168` | `cy-scene-sheet` | `bind:requestclose` | `requestSceneClose` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1211 | `pages/play/index.wxml:1168` | `cy-scene-sheet` | `bind:back` | `backScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1212 | `pages/play/index.wxml:1169` | `cy-scene-qr-citynode` | `bind:close` | `backScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1213 | `pages/play/index.wxml:1173` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1214 | `pages/play/index.wxml:1174` | `cy-scene-sheet` | `bind:requestclose` | `requestSceneClose` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1215 | `pages/play/index.wxml:1174` | `cy-scene-sheet` | `bind:back` | `backScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1216 | `pages/play/index.wxml:1175` | `cy-scene-qr-ticket` | `bind:close` | `backScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1217 | `pages/play/index.wxml:1178` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1218 | `pages/play/index.wxml:1179` | `cy-scene-sheet` | `bind:requestclose` | `requestSceneClose` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1219 | `pages/play/index.wxml:1179` | `cy-scene-sheet` | `bind:back` | `backScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1220 | `pages/play/index.wxml:1180` | `cy-scene-roam-stamp-album` | `bind:camera` | `openStampCameraFromScene` | 1 | navigation | event-contract | error,success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1221 | `pages/play/index.wxml:1180` | `cy-scene-roam-stamp-album` | `bind:close` | `closeScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1222 | `pages/play/index.wxml:1183` | `cy-modal` | `bind:confirm` | `confirmSceneDiscard` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1223 | `pages/play/index.wxml:1183` | `cy-modal` | `bind:cancel` | `cancelSceneDiscard` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1224 | `pages/play/index.wxml:1195` | `cy-story-sheet` | `bind:close` | `closeStory` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1225 | `pages/play/index.wxml:1199` | `cy-privacy-gate` | `bind:settled` | `onPrivacyGateSettled` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780,#779 | PENDING |
| E1226 | `pages/play/merchant/index.wxml:6` | `view` | `bindtap` | `goBack` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E1227 | `pages/play/merchant/index.wxml:15` | `view` | `bindtap` | `goBack` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E1228 | `pages/play/merchant/index.wxml:50` | `view` | `bindtap` | `revokeMerchantConsent` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E1229 | `pages/play/merchant/index.wxml:55` | `view` | `bindtap` | `openMap` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #787,#783 | PENDING |
| E1230 | `pages/play/merchant/index.wxml:62` | `view` | `bindtap` | `closeSheets` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1231 | `pages/play/merchant/index.wxml:69` | `view` | `bindtap` | `onGamePick` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1232 | `pages/play/merchant/index.wxml:73` | `input` | `bindinput` | `onGameInput` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1233 | `pages/play/merchant/index.wxml:75` | `view` | `bindtap` | `submitGame` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783 | PENDING |
| E1234 | `pages/play/merchant/index.wxml:79` | `view` | `bindtap` | `closeGame` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1235 | `pages/play/merchant/index.wxml:91` | `view` | `bindtap` | `taskGoPhoto` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E1236 | `pages/play/merchant/index.wxml:92` | `view` | `bindtap` | `closeTaskCard` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1237 | `pages/play/merchant/index.wxml:97` | `view` | `bindtap` | `openMap` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #787,#783 | PENDING |
| E1238 | `pages/play/merchant/index.wxml:98` | `view` | `bindtap` | `scanArrival` | 1 | external | tap | cancelled,denied,duplicate-trigger,error,success,timeout | YES | PASS | #787,#783 | PENDING |
| E1239 | `pages/play/merchant/index.wxml:99` | `view` | `bindtap` | `uploadProof` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E1240 | `pages/play/merchant/index.wxml:100` | `view` | `bindtap` | `openVerifyCode` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E1241 | `pages/privacy/index.wxml:4` | `cy-sheet` | `bind:close` | `onClose` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E1242 | `pages/privacy/index.wxml:9` | `view` | `bindtap` | `openPrivacyContract` | 1 | external | tap | cancelled,denied,duplicate-trigger,error,success,timeout | YES | PASS | — | PENDING |
| E1243 | `pages/privacy/index.wxml:15` | `cy-btn` | `bindtap` | `disagreePrivacy` | 1 | navigation | tap | error,success | NO | PASS | — | PENDING |
| E1244 | `pages/privacy/index.wxml:16` | `button` | `bindagreeprivacyauthorization` | `agreePrivacy` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E1245 | `pages/privacy/index.wxml:23` | `cy-btn` | `bindtap` | `withdrawRoamLocationConsent` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | — | PENDING |
| E1246 | `pages/publish/activity/index.wxml:20` | `input` | `bindinput` | `onInputChange` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1247 | `pages/publish/activity/index.wxml:20` | `input` | `bindfocus` | `onFieldFocus` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1248 | `pages/publish/activity/index.wxml:20` | `input` | `bindblur` | `onFieldBlur` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1249 | `pages/publish/activity/index.wxml:27` | `view` | `bindtap` | `choosePoiForNode` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1250 | `pages/publish/activity/index.wxml:37` | `view` | `bindtap` | `showStartTimePicker` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1251 | `pages/publish/activity/index.wxml:56` | `view` | `bindtap` | `navigateToCategorySelect` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1252 | `pages/publish/activity/index.wxml:75` | `textarea` | `bindinput` | `onInputChange` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1253 | `pages/publish/activity/index.wxml:75` | `textarea` | `bindfocus` | `onFieldFocus` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1254 | `pages/publish/activity/index.wxml:75` | `textarea` | `bindblur` | `onFieldBlur` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1255 | `pages/publish/activity/index.wxml:89` | `cy-upload` | `bind:add` | `onCoverAdd` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1256 | `pages/publish/activity/index.wxml:90` | `cy-upload` | `bind:remove` | `onCoverRemove` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1257 | `pages/publish/activity/index.wxml:91` | `cy-upload` | `bind:preview` | `onCoverPreview` | 1 | external | event-contract | duplicate-trigger,error,success,timeout | YES | PASS | #787,#783 | PENDING |
| E1258 | `pages/publish/activity/index.wxml:100` | `view` | `bindtap` | `showTemp` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1259 | `pages/publish/activity/index.wxml:126` | `view` | `bindtap` | `removeCollaborator` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1260 | `pages/publish/activity/index.wxml:129` | `view` | `bindtap` | `AddCollaborator` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1261 | `pages/publish/activity/index.wxml:134` | `cy-collaborator-picker` | `bind:select` | `onCollaboratorPickerSelect` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1262 | `pages/publish/activity/index.wxml:134` | `cy-collaborator-picker` | `bind:close` | `onCollaboratorPickerClose` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1263 | `pages/publish/activity/index.wxml:140` | `view` | `bindtap` | `removeTicket` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1264 | `pages/publish/activity/index.wxml:143` | `input` | `bindinput` | `onTicketChange` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1265 | `pages/publish/activity/index.wxml:143` | `input` | `bindfocus` | `onFieldFocus` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1266 | `pages/publish/activity/index.wxml:143` | `input` | `bindblur` | `onFieldBlur` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1267 | `pages/publish/activity/index.wxml:154` | `input` | `bindinput` | `onTicketChange` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1268 | `pages/publish/activity/index.wxml:154` | `input` | `bindfocus` | `onFieldFocus` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1269 | `pages/publish/activity/index.wxml:154` | `input` | `bindblur` | `onFieldBlur` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1270 | `pages/publish/activity/index.wxml:161` | `input` | `bindinput` | `onTicketChange` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1271 | `pages/publish/activity/index.wxml:161` | `input` | `bindfocus` | `onFieldFocus` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1272 | `pages/publish/activity/index.wxml:161` | `input` | `bindblur` | `onFieldBlur` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1273 | `pages/publish/activity/index.wxml:171` | `cy-btn` | `bindtap` | `addTicket` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1274 | `pages/publish/activity/index.wxml:179` | `cy-sheet` | `bind:close` | `cancelTemp` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1275 | `pages/publish/activity/index.wxml:182` | `cy-btn` | `bindtap` | `cancelTemp` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1276 | `pages/publish/activity/index.wxml:184` | `cy-btn` | `bindtap` | `confirmTemp` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1277 | `pages/publish/activity/index.wxml:187` | `cy-search` | `bind:confirm` | `handleSearch` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E1278 | `pages/publish/activity/index.wxml:191` | `view` | `bindtap` | `selectTemp` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1279 | `pages/publish/activity/index.wxml:220` | `cy-btn` | `bindtap` | `onCancel` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E1280 | `pages/publish/activity/index.wxml:221` | `cy-btn` | `bindtap` | `prevStep` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1281 | `pages/publish/activity/index.wxml:222` | `cy-btn` | `bindtap` | `nextStep` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1282 | `pages/publish/activity/index.wxml:223` | `cy-btn` | `bindtap` | `submitForm` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783 | PENDING |
| E1283 | `pages/publish/activity/index.wxml:228` | `cy-sheet` | `bind:close` | `closeStartTimePicker` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1284 | `pages/publish/activity/index.wxml:231` | `cy-btn` | `bindtap` | `closeStartTimePicker` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1285 | `pages/publish/activity/index.wxml:233` | `cy-btn` | `bindtap` | `confirmStartTime` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1286 | `pages/publish/activity/index.wxml:241` | `picker-view` | `bindchange` | `onStartDateChange` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1287 | `pages/publish/activity/index.wxml:252` | `picker-view` | `bindchange` | `onStartTimeChange` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1288 | `pages/publish/activity/index.wxml:269` | `cy-sheet` | `bind:close` | `closeEndTimePicker` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1289 | `pages/publish/activity/index.wxml:272` | `cy-btn` | `bindtap` | `closeEndTimePicker` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1290 | `pages/publish/activity/index.wxml:274` | `cy-btn` | `bindtap` | `confirmEndTime` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1291 | `pages/publish/activity/index.wxml:282` | `picker-view` | `bindchange` | `onEndDateChange` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1292 | `pages/publish/activity/index.wxml:293` | `picker-view` | `bindchange` | `onEndTimeChange` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1293 | `pages/publish/activity/index.wxml:310` | `cy-category-sheet` | `bind:select` | `onCategorySelect` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1294 | `pages/publish/activity/index.wxml:310` | `cy-category-sheet` | `bind:close` | `onCategorySheetClose` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1295 | `pages/publish/biaoqian/biaoqian.wxml:10` | `view` | `bindtap` | `toggleCategory` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E1296 | `pages/publish/biaoqian/biaoqian.wxml:19` | `cy-empty` | `bindcta` | `getCategoryList` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | — | PENDING |
| E1297 | `pages/publish/biaoqian/biaoqian.wxml:26` | `cy-btn` | `bindtap` | `saveSelection` | 1 | navigation | tap | error,success | NO | PASS | — | PENDING |
| E1298 | `pages/publish/components/ai-assist/index.wxml:4` | `view` | `catchtap` | `onExpand` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E1299 | `pages/publish/components/ai-assist/index.wxml:13` | `text` | `catchtap` | `onCollapse` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E1300 | `pages/publish/components/ai-assist/index.wxml:23` | `view` | `catchtap` | `onChip` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E1301 | `pages/publish/components/ai-assist/index.wxml:35` | `input` | `bindinput` | `onIdeaInput` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E1302 | `pages/publish/components/ai-assist/index.wxml:36` | `input` | `bindconfirm` | `onGenerate` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E1303 | `pages/publish/components/ai-assist/index.wxml:46` | `view` | `catchtap` | `onCancel` | 1 | other | tap | error,success | NO | PASS | — | PENDING |
| E1304 | `pages/publish/components/ai-assist/index.wxml:51` | `view` | `catchtap` | `onRegenerate` | 1 | other | tap | error,success | NO | PASS | — | PENDING |
| E1305 | `pages/publish/components/ai-assist/index.wxml:56` | `view` | `catchtap` | `onGenerate` | 1 | other | tap | error,success | NO | PASS | — | PENDING |
| E1306 | `pages/publish/components/ai-assist/index.wxml:60` | `view` | `catchtap` | `onGenerate` | 1 | other | tap | error,success | NO | PASS | — | PENDING |
| E1307 | `pages/publish/components/collaborator-picker/index.wxml:1` | `cy-sheet` | `bind:close` | `onClose` | 1 | other | event-contract | error,success | NO | PASS | #783,#782 | PENDING |
| E1308 | `pages/publish/components/collaborator-picker/index.wxml:2` | `cy-search` | `bind:input` | `onSearchInput` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E1309 | `pages/publish/components/collaborator-picker/index.wxml:4` | `scroll-view` | `bindscrolltolower` | `onReachBottom` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783,#782 | PENDING |
| E1310 | `pages/publish/components/collaborator-picker/index.wxml:6` | `cy-error` | `bind:retry` | `onRetry` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783,#782 | PENDING |
| E1311 | `pages/publish/components/collaborator-picker/index.wxml:15` | `cy-btn` | `bindtap` | `selectMember` | 1 | other | tap | error,success | NO | PASS | #783,#782 | PENDING |
| E1312 | `pages/publish/components/collaborator-picker/index.wxml:21` | `cy-error` | `bind:retry` | `onRetry` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783,#782 | PENDING |
| E1313 | `pages/publish/components/cover-fallback/index.wxml:8` | `image` | `binderror` | `onError` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E1314 | `pages/publish/components/creation-success/index.wxml:1` | `view` | `catchtouchmove` | `block` | 1 | other | event-contract | error,success | NO | PASS | #783 | PENDING |
| E1315 | `pages/publish/components/creation-success/index.wxml:2` | `view` | `bindtap` | `onAction` | 1 | other | tap | error,success | NO | PASS | #783 | PENDING |
| E1316 | `pages/publish/components/creation-success/index.wxml:18` | `view` | `bindtap` | `onAction` | 1 | other | tap | error,success | NO | PASS | #783 | PENDING |
| E1317 | `pages/publish/components/creation-success/index.wxml:19` | `view` | `bindtap` | `onAction` | 1 | other | tap | error,success | NO | PASS | #783 | PENDING |
| E1318 | `pages/publish/components/reward-selector/index.wxml:6` | `view` | `bindtap` | `openSelector` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #783 | PENDING |
| E1319 | `pages/publish/components/reward-selector/index.wxml:12` | `cy-sheet` | `bind:close` | `closeSelector` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1320 | `pages/publish/components/reward-selector/index.wxml:14` | `view` | `bindtap` | `clearCoupon` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E1321 | `pages/publish/components/reward-selector/index.wxml:24` | `view` | `bindtap` | `chooseCoupon` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E1322 | `pages/publish/components/reward-selector/index.wxml:32` | `view` | `bindtap` | `createCoupon` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783 | PENDING |
| E1323 | `pages/publish/components/reward-selector/index.wxml:38` | `cy-sheet` | `bind:close` | `onCreateClose` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1324 | `pages/publish/components/reward-selector/index.wxml:38` | `cy-sheet` | `bind:requestclose` | `onCreateRequestClose` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1325 | `pages/publish/components/reward-selector/index.wxml:42` | `input` | `bindinput` | `onCreateName` | 1 | write | event-contract | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783 | PENDING |
| E1326 | `pages/publish/components/reward-selector/index.wxml:47` | `view` | `bindtap` | `showCreateStartTimePicker` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E1327 | `pages/publish/components/reward-selector/index.wxml:56` | `input` | `bindinput` | `onCreatePublishCount` | 1 | write | event-contract | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783 | PENDING |
| E1328 | `pages/publish/components/reward-selector/index.wxml:61` | `picker` | `bindchange` | `onCreateTypeChange` | 1 | write | event-contract | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783 | PENDING |
| E1329 | `pages/publish/components/reward-selector/index.wxml:71` | `textarea` | `bindinput` | `onCreateDescription` | 1 | write | event-contract | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783 | PENDING |
| E1330 | `pages/publish/components/reward-selector/index.wxml:76` | `cy-btn` | `bindtap` | `onCreateCancel` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E1331 | `pages/publish/components/reward-selector/index.wxml:77` | `cy-btn` | `bindtap` | `onCreateSubmit` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783 | PENDING |
| E1332 | `pages/publish/components/reward-selector/index.wxml:82` | `cy-sheet` | `bind:close` | `closeCreateStartTimePicker` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1333 | `pages/publish/components/reward-selector/index.wxml:85` | `cy-btn` | `bindtap` | `closeCreateStartTimePicker` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E1334 | `pages/publish/components/reward-selector/index.wxml:87` | `cy-btn` | `bindtap` | `confirmCreateStartTime` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783 | PENDING |
| E1335 | `pages/publish/components/reward-selector/index.wxml:92` | `picker-view` | `bindchange` | `onCreateStartDateChange` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1336 | `pages/publish/components/reward-selector/index.wxml:101` | `picker-view` | `bindchange` | `onCreateStartTimeChange` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1337 | `pages/publish/components/reward-selector/index.wxml:115` | `cy-sheet` | `bind:close` | `closeCreateEndTimePicker` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1338 | `pages/publish/components/reward-selector/index.wxml:118` | `cy-btn` | `bindtap` | `closeCreateEndTimePicker` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E1339 | `pages/publish/components/reward-selector/index.wxml:120` | `cy-btn` | `bindtap` | `confirmCreateEndTime` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783 | PENDING |
| E1340 | `pages/publish/components/reward-selector/index.wxml:125` | `picker-view` | `bindchange` | `onCreateEndDateChange` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1341 | `pages/publish/components/reward-selector/index.wxml:134` | `picker-view` | `bindchange` | `onCreateEndTimeChange` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1342 | `pages/publish/fabu/index.wxml:19` | `map` | `bindmarkertap` | `onMarkerTap` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1343 | `pages/publish/fabu/index.wxml:31` | `view` | `bindtap` | `goBack` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E1344 | `pages/publish/fabu/index.wxml:56` | `input` | `bindinput` | `onNameChange` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1345 | `pages/publish/fabu/index.wxml:69` | `cy-card` | `bindtap` | `openTopicDetail` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1346 | `pages/publish/fabu/index.wxml:84` | `view` | `bindtap` | `openModePicker` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1347 | `pages/publish/fabu/index.wxml:89` | `view` | `bindtap` | `switchEditorPage` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1348 | `pages/publish/fabu/index.wxml:104` | `text` | `bindtap` | `onTapGuideCollapse` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1349 | `pages/publish/fabu/index.wxml:106` | `view` | `bindtap` | `onTapGuideStep` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1350 | `pages/publish/fabu/index.wxml:138` | `cy-btn` | `bindtap` | `onTapPrimaryStarter` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1351 | `pages/publish/fabu/index.wxml:155` | `text` | `bindtap` | `startClubPlaceCapture` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1352 | `pages/publish/fabu/index.wxml:166` | `view` | `bindtap` | `editPendingMaterial` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1353 | `pages/publish/fabu/index.wxml:167` | `picker` | `bindchange` | `arrangePendingIntoChapter` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E1354 | `pages/publish/fabu/index.wxml:170` | `view` | `bindtap` | `createChapterForPending` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1355 | `pages/publish/fabu/index.wxml:171` | `view` | `bindtap` | `removePendingMaterial` | 1 | state | tap | modal-cancelled,success | NO | PASS | #787,#783 | PENDING |
| E1356 | `pages/publish/fabu/index.wxml:183` | `view` | `catchtap` | `openStoryEditor` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1357 | `pages/publish/fabu/index.wxml:227` | `cy-btn` | `catchtap` | `openStoryEditor` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1358 | `pages/publish/fabu/index.wxml:261` | `movable-view` | `bindchange` | `onNodeDragChange` | 1 | other | event-contract | error,success | NO | PASS | #787,#783 | PENDING |
| E1359 | `pages/publish/fabu/index.wxml:262` | `movable-view` | `bindtouchend` | `onNodeDragEnd` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1360 | `pages/publish/fabu/index.wxml:268` | `view` | `catchtap` | `selectNode` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1361 | `pages/publish/fabu/index.wxml:269` | `view` | `catchlongpress` | `onNodeDragStart` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1362 | `pages/publish/fabu/index.wxml:279` | `view` | `catchtap` | `pickNodePhoto` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1363 | `pages/publish/fabu/index.wxml:296` | `view` | `catchtap` | `pickNodeLocation` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1364 | `pages/publish/fabu/index.wxml:308` | `view` | `catchtap` | `showEditNodes` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1365 | `pages/publish/fabu/index.wxml:321` | `cy-btn` | `catchtap` | `showEditChapter` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E1366 | `pages/publish/fabu/index.wxml:322` | `cy-btn` | `catchtap` | `onTapAddNode` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E1367 | `pages/publish/fabu/index.wxml:329` | `view` | `bindtap` | `onTapAddChapter` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1368 | `pages/publish/fabu/index.wxml:340` | `cy-btn` | `bindtap` | `copyToProfessionalDraft` | 1 | other | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E1369 | `pages/publish/fabu/index.wxml:353` | `view` | `catchtouchmove` | `noop` | 1 | other | event-contract | error,success | NO | PASS | #787,#783 | PENDING |
| E1370 | `pages/publish/fabu/index.wxml:354` | `view` | `bindtap` | `closeStoryEditor` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1371 | `pages/publish/fabu/index.wxml:358` | `view` | `bindtap` | `closeStoryEditor` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1372 | `pages/publish/fabu/index.wxml:360` | `view` | `bindtap` | `closeStoryEditor` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1373 | `pages/publish/fabu/index.wxml:366` | `input` | `bindinput` | `onStoryChapterNameInput` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1374 | `pages/publish/fabu/index.wxml:378` | `view` | `bindtap` | `toggleStoryInsertMenu` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1375 | `pages/publish/fabu/index.wxml:383` | `view` | `catchtap` | `insertStoryTextAt` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1376 | `pages/publish/fabu/index.wxml:384` | `view` | `catchtap` | `insertStoryImageAt` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1377 | `pages/publish/fabu/index.wxml:385` | `view` | `catchtap` | `insertStoryAudioAt` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1378 | `pages/publish/fabu/index.wxml:386` | `view` | `catchtap` | `insertStoryNodeAt` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1379 | `pages/publish/fabu/index.wxml:396` | `textarea` | `bindinput` | `onStoryTextInput` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1380 | `pages/publish/fabu/index.wxml:402` | `textarea` | `bindfocus` | `selectStoryBlock` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1381 | `pages/publish/fabu/index.wxml:403` | `textarea` | `bindblur` | `onStoryTextBlur` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1382 | `pages/publish/fabu/index.wxml:405` | `view` | `catchtap` | `removeStoryText` | 1 | state | tap | modal-cancelled,success | NO | PASS | #787,#783 | PENDING |
| E1383 | `pages/publish/fabu/index.wxml:410` | `view` | `bindlongpress` | `selectStoryBlock` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1384 | `pages/publish/fabu/index.wxml:412` | `view` | `catchtap` | `removeStoryMedia` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1385 | `pages/publish/fabu/index.wxml:417` | `view` | `bindlongpress` | `selectStoryBlock` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1386 | `pages/publish/fabu/index.wxml:420` | `view` | `catchtap` | `removeStoryMedia` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1387 | `pages/publish/fabu/index.wxml:423` | `view` | `bindtap` | `editStoryNode` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1388 | `pages/publish/fabu/index.wxml:423` | `view` | `bindlongpress` | `selectStoryBlock` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1389 | `pages/publish/fabu/index.wxml:434` | `view` | `catchtap` | `removeStoryNode` | 1 | state | tap | modal-cancelled,success | NO | PASS | #787,#783 | PENDING |
| E1390 | `pages/publish/fabu/index.wxml:442` | `view` | `bindtap` | `toggleStoryInsertMenu` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1391 | `pages/publish/fabu/index.wxml:447` | `view` | `catchtap` | `insertStoryTextAt` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1392 | `pages/publish/fabu/index.wxml:448` | `view` | `catchtap` | `insertStoryImageAt` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1393 | `pages/publish/fabu/index.wxml:449` | `view` | `catchtap` | `insertStoryAudioAt` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1394 | `pages/publish/fabu/index.wxml:450` | `view` | `catchtap` | `insertStoryNodeAt` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1395 | `pages/publish/fabu/index.wxml:459` | `view` | `catchtap` | `insertStoryTextAt` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1396 | `pages/publish/fabu/index.wxml:460` | `view` | `catchtap` | `insertStoryNodeAt` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1397 | `pages/publish/fabu/index.wxml:467` | `text` | `bindtap` | `undoPendingRemoval` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1398 | `pages/publish/fabu/index.wxml:487` | `view` | `bindtap` | `switchViewMode` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1399 | `pages/publish/fabu/index.wxml:491` | `view` | `bindtap` | `switchViewMode` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1400 | `pages/publish/fabu/index.wxml:496` | `view` | `bindtap` | `switchEditorPage` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1401 | `pages/publish/fabu/index.wxml:498` | `cy-btn` | `bindtap` | `previewTopic` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E1402 | `pages/publish/fabu/index.wxml:499` | `cy-btn` | `bindtap` | `submitForm` | 2 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E1403 | `pages/publish/fabu/index.wxml:508` | `view` | `bindtap` | `closeNarrativeEditor` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1404 | `pages/publish/fabu/index.wxml:518` | `textarea` | `bindinput` | `onNarrativeEditorInput` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1405 | `pages/publish/fabu/index.wxml:523` | `cy-btn` | `bindtap` | `saveNarrativeEditor` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1406 | `pages/publish/fabu/index.wxml:529` | `view` | `bindtap` | `hideMultiSelectModal` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1407 | `pages/publish/fabu/index.wxml:533` | `view` | `bindtap` | `hideMultiSelectModal` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1408 | `pages/publish/fabu/index.wxml:537` | `view` | `bindtap` | `toggleCategorySelect` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1409 | `pages/publish/fabu/index.wxml:549` | `cy-btn` | `bindtap` | `confirmMultiSelect` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1410 | `pages/publish/fabu/index.wxml:557` | `cy-btn` | `bindtap` | `cancelEdit` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1411 | `pages/publish/fabu/index.wxml:559` | `cy-btn` | `bindtap` | `confirmEdit` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1412 | `pages/publish/fabu/index.wxml:564` | `input` | `bindinput` | `onEditInputChange` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1413 | `pages/publish/fabu/index.wxml:567` | `input` | `bindinput` | `onEditInputChange` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1414 | `pages/publish/fabu/index.wxml:570` | `textarea` | `bindinput` | `onEditInputChange` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1415 | `pages/publish/fabu/index.wxml:583` | `cy-sheet` | `bind:close` | `cancelPopChapter` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1416 | `pages/publish/fabu/index.wxml:588` | `view` | `bindtap` | `backToNodeDetail` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1417 | `pages/publish/fabu/index.wxml:591` | `view` | `bindtap` | `confirmTemp` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1418 | `pages/publish/fabu/index.wxml:594` | `view` | `bindtap` | `cancelPopChapter` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1419 | `pages/publish/fabu/index.wxml:597` | `view` | `bindtap` | `confrimNode` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1420 | `pages/publish/fabu/index.wxml:605` | `input` | `bindinput` | `onNodeNameInput` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1421 | `pages/publish/fabu/index.wxml:626` | `view` | `catchtap` | `choosePoiForNode` | 1 | external | tap | cancelled,denied,duplicate-trigger,error,success,timeout | YES | PASS | #787,#783 | PENDING |
| E1422 | `pages/publish/fabu/index.wxml:640` | `cy-switch` | `bind:change` | `onToggleNodeGame` | 1 | navigation | event-contract | error,success | NO | PASS | #787,#783 | PENDING |
| E1423 | `pages/publish/fabu/index.wxml:644` | `view` | `catchtap` | `showTemp` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1424 | `pages/publish/fabu/index.wxml:658` | `textarea` | `bindinput` | `onNodesDescriptionInputChange` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1425 | `pages/publish/fabu/index.wxml:675` | `view` | `catchtap` | `removeNodePhoto` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1426 | `pages/publish/fabu/index.wxml:678` | `view` | `bindtap` | `uploadAddressPic` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1427 | `pages/publish/fabu/index.wxml:684` | `view` | `bindtap` | `deleteNode` | 1 | state | tap | modal-cancelled,success | NO | PASS | #787,#783 | PENDING |
| E1428 | `pages/publish/fabu/index.wxml:698` | `view` | `bindtap` | `selectTemp` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1429 | `pages/publish/fabu/index.wxml:719` | `view` | `bindtap` | `selectTemp` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1430 | `pages/publish/fabu/index.wxml:734` | `cy-btn` | `bindtap` | `onCreateGame` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E1431 | `pages/publish/fabu/index.wxml:743` | `cy-btn` | `bindtap` | `cancelChapter` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1432 | `pages/publish/fabu/index.wxml:745` | `cy-btn` | `bindtap` | `confrimChapter` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1433 | `pages/publish/fabu/index.wxml:750` | `input` | `bindinput` | `onChapterNameInputChange` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1434 | `pages/publish/fabu/index.wxml:764` | `textarea` | `bindinput` | `onChapterDescInput` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1435 | `pages/publish/fabu/index.wxml:780` | `view` | `bindtap` | `onChapterAtmosphereSelect` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1436 | `pages/publish/fabu/index.wxml:792` | `cy-btn` | `bindtap` | `deleteChapter` | 1 | state | tap | modal-cancelled,success | NO | PASS | #787,#783 | PENDING |
| E1437 | `pages/publish/fabu/index.wxml:802` | `cy-btn` | `bindtap` | `closePublishCheck` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1438 | `pages/publish/fabu/index.wxml:808` | `view` | `bindtap` | `locatePublishIssue` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1439 | `pages/publish/fabu/index.wxml:817` | `view` | `bindtap` | `locatePublishIssue` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1440 | `pages/publish/fabu/index.wxml:832` | `cy-btn` | `bindtap` | `confirmPublishCheck` | 2 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783 | PENDING |
| E1441 | `pages/publish/fabu/index.wxml:840` | `cy-date-sheet` | `bind:cancel` | `onBusinessTimeCancel` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1442 | `pages/publish/fabu/index.wxml:840` | `cy-date-sheet` | `bind:confirm` | `onBusinessTimeConfirm` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1443 | `pages/publish/fabu/index.wxml:846` | `picker-view` | `bindchange` | `onStartTimeChange` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1444 | `pages/publish/fabu/index.wxml:860` | `picker-view` | `bindchange` | `onEndTimeChange` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1445 | `pages/publish/fabu/index.wxml:874` | `creation-success` | `bind:action` | `onCreationSuccessAction` | 1 | navigation | event-contract | error,success | NO | PASS | #787,#783 | PENDING |
| E1446 | `pages/publish/fabu/index.wxml:878` | `cy-date-sheet` | `bind:cancel` | `closeStartTimePicker` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1447 | `pages/publish/fabu/index.wxml:878` | `cy-date-sheet` | `bind:confirm` | `confirmStartTime` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1448 | `pages/publish/fabu/index.wxml:884` | `picker-view` | `bindchange` | `onStartDateChange` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1449 | `pages/publish/fabu/index.wxml:895` | `picker-view` | `bindchange` | `onStartTimePickerChange` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1450 | `pages/publish/fabu/index.wxml:910` | `cy-date-sheet` | `bind:cancel` | `closeTicketTimePicker` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1451 | `pages/publish/fabu/index.wxml:910` | `cy-date-sheet` | `bind:confirm` | `confirmTicketTime` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1452 | `pages/publish/fabu/index.wxml:916` | `picker-view` | `bindchange` | `onTicketPickerDateChange` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1453 | `pages/publish/fabu/index.wxml:928` | `picker-view` | `bindchange` | `onTicketPickerTimeChange` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1454 | `pages/publish/fabu/index.wxml:944` | `cy-date-sheet` | `bind:cancel` | `closeEndTimePicker` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1455 | `pages/publish/fabu/index.wxml:944` | `cy-date-sheet` | `bind:confirm` | `confirmEndTime` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1456 | `pages/publish/fabu/index.wxml:950` | `picker-view` | `bindchange` | `onEndDateChange` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1457 | `pages/publish/fabu/index.wxml:961` | `picker-view` | `bindchange` | `onEndTimePickerChange` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1458 | `pages/publish/fabu/index.wxml:976` | `cy-category-sheet` | `bind:select` | `onCategorySelect` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1459 | `pages/publish/fabu/index.wxml:976` | `cy-category-sheet` | `bind:close` | `onCategorySheetClose` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1460 | `pages/publish/fabu/index.wxml:982` | `cy-sheet` | `bind:close` | `closeModePicker` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1461 | `pages/publish/fabu/index.wxml:985` | `view` | `bindtap` | `closeModePicker` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1462 | `pages/publish/fabu/index.wxml:988` | `view` | `bindtap` | `confirmModePicker` | 1 | state | tap | modal-cancelled,success | NO | PASS | #787,#783 | PENDING |
| E1463 | `pages/publish/fabu/index.wxml:995` | `view` | `bindtap` | `onPickMode` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1464 | `pages/publish/fabu/index.wxml:1006` | `view` | `bindtap` | `onPickMode` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1465 | `pages/publish/fabu/index.wxml:1031` | `page-container` | `bind:afterleave` | `onNativeBackGuard` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1466 | `pages/publish/fabu/step3.wxml:15` | `view` | `bindtap` | `showStartTimePicker` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E1467 | `pages/publish/fabu/step3.wxml:24` | `view` | `bindtap` | `showEndTimePicker` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E1468 | `pages/publish/fabu/step3.wxml:34` | `picker` | `bindchange` | `onRecruitDeadlineChange` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1469 | `pages/publish/fabu/step3.wxml:57` | `cy-switch` | `bindchange` | `onMerchantPoolChange` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1470 | `pages/publish/fabu/step3.wxml:77` | `cy-switch` | `bindchange` | `onTeamSupportChange` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1471 | `pages/publish/fabu/step3.wxml:84` | `view` | `bindtap` | `onTeamModeSelect` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E1472 | `pages/publish/fabu/step3.wxml:85` | `view` | `bindtap` | `onTeamModeSelect` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E1473 | `pages/publish/fabu/step3.wxml:89` | `picker` | `bindchange` | `onTeamMaxMembersChange` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1474 | `pages/publish/fabu/step3.wxml:103` | `view` | `bindtap` | `openTicketEditor` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E1475 | `pages/publish/fabu/step3.wxml:116` | `view` | `bindtap` | `openTicketEditor` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E1476 | `pages/publish/fabu/step3.wxml:130` | `view` | `bindtap` | `closeTicketEditor` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E1477 | `pages/publish/fabu/step3.wxml:135` | `input` | `bindinput` | `onEditTicketChange` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1478 | `pages/publish/fabu/step3.wxml:146` | `input` | `bindinput` | `onEditTicketChange` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1479 | `pages/publish/fabu/step3.wxml:150` | `input` | `bindinput` | `onEditTicketChange` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1480 | `pages/publish/fabu/step3.wxml:156` | `view` | `bindtap` | `showTicketTimePicker` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E1481 | `pages/publish/fabu/step3.wxml:165` | `view` | `bindtap` | `showTicketTimePicker` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E1482 | `pages/publish/fabu/step3.wxml:187` | `view` | `bindtap` | `showTicketTimePicker` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E1483 | `pages/publish/fabu/step3.wxml:195` | `view` | `bindtap` | `showTicketTimePicker` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E1484 | `pages/publish/fabu/step3.wxml:205` | `input` | `bindinput` | `onEditTicketChange` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1485 | `pages/publish/fabu/step3.wxml:208` | `view` | `bindtap` | `chooseTicketMeetingPoint` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E1486 | `pages/publish/fabu/step3.wxml:224` | `cy-switch` | `bindchange` | `onEditTicketSyncToggle` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1487 | `pages/publish/fabu/step3.wxml:228` | `view` | `bindtap` | `showTicketTimePicker` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E1488 | `pages/publish/fabu/step3.wxml:236` | `view` | `bindtap` | `showTicketTimePicker` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E1489 | `pages/publish/fabu/step3.wxml:254` | `cy-switch` | `bindchange` | `onEditTicketRefundToggle` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1490 | `pages/publish/fabu/step3.wxml:259` | `textarea` | `bindinput` | `onEditTicketChange` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1491 | `pages/publish/fabu/step3.wxml:264` | `cy-btn` | `bindtap` | `saveTicket` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E1492 | `pages/publish/fabu/step3.wxml:279` | `cy-switch` | `bindchange` | `onSelfPlayToggle` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1493 | `pages/publish/fabu/step3.wxml:286` | `input` | `bindinput` | `onSelfPlayPriceInput` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1494 | `pages/publish/fabu/step3.wxml:290` | `input` | `bindinput` | `onSelfPlayQuotaInput` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1495 | `pages/publish/fabu/topic-detail-sheet.wxml:8` | `cy-sheet` | `bind:close` | `closeTopicDetail` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1496 | `pages/publish/fabu/topic-detail-sheet.wxml:11` | `view` | `bindtap` | `closeTopicDetail` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1497 | `pages/publish/fabu/topic-detail-sheet.wxml:14` | `view` | `bindtap` | `closeTopicDetail` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1498 | `pages/publish/fabu/topic-detail-sheet.wxml:29` | `input` | `bindinput` | `onSubtitleChange` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1499 | `pages/publish/fabu/topic-detail-sheet.wxml:34` | `view` | `bindtap` | `navigateToCategorySelect` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1500 | `pages/publish/fabu/topic-detail-sheet.wxml:52` | `view` | `catchtap` | `uploadBanner` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1501 | `pages/publish/fabu/topic-detail-sheet.wxml:65` | `view` | `catchtap` | `uploadImgArr` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1502 | `pages/publish/fabu/topic-detail-sheet.wxml:72` | `view` | `catchtap` | `removeImgArr` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1503 | `pages/publish/fabu/topic-detail-sheet.wxml:85` | `textarea` | `bindinput` | `onIntroductionChange` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1504 | `pages/publish/fabu/topic-detail-sheet.wxml:91` | `view` | `bindtap` | `uploadFinishMedal` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1505 | `pages/publish/fabu/topic-detail-sheet.wxml:97` | `input` | `bindinput` | `onFinishMedalNameInput` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1506 | `pages/publish/fabu/topic-detail-sheet.wxml:99` | `view` | `catchtap` | `clearFinishMedal` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1507 | `pages/publish/fabu/topic-detail-sheet.wxml:114` | `cy-reward-selector` | `bind:change` | `onCompletionRewardChange` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1508 | `pages/publish/fabu/topic-detail-sheet.wxml:117` | `view` | `bindtap` | `addEgg` | 1 | external | tap | cancelled,denied,duplicate-trigger,error,success,timeout | YES | PASS | #787,#783 | PENDING |
| E1509 | `pages/publish/fabu/topic-detail-sheet.wxml:138` | `cy-switch` | `bindchange` | `onPublishToCreativeChange` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1510 | `pages/publish/fabu/topic-detail-sheet.wxml:142` | `picker` | `bindchange` | `onClubChange` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1511 | `pages/publish/fabu/topic-detail-sheet.wxml:156` | `view` | `catchtap` | `AddCollaborator` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1512 | `pages/publish/fabu/topic-detail-sheet.wxml:172` | `view` | `catchtap` | `removeCollaborator` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1513 | `pages/publish/fabu/topic-detail-sheet.wxml:180` | `view` | `bindtap` | `clearLocalDraft` | 1 | navigation | tap | error,modal-cancelled,success | NO | PASS | #787,#783 | PENDING |
| E1514 | `pages/publish/fabu/topic-detail-sheet.wxml:183` | `cy-collaborator-picker` | `bind:select` | `onCollaboratorPickerSelect` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1515 | `pages/publish/fabu/topic-detail-sheet.wxml:183` | `cy-collaborator-picker` | `bind:close` | `onCollaboratorPickerClose` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1516 | `pages/publish/simple/index.wxml:29` | `view` | `bindtap` | `useAiPrompt` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E1517 | `pages/publish/simple/index.wxml:45` | `input` | `bindinput` | `onAiPlanTitleInput` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1518 | `pages/publish/simple/index.wxml:47` | `textarea` | `bindinput` | `onAiPlanStoryInput` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1519 | `pages/publish/simple/index.wxml:61` | `input` | `bindinput` | `onAiNodeNameInput` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1520 | `pages/publish/simple/index.wxml:62` | `textarea` | `bindinput` | `onAiNodeStoryInput` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1521 | `pages/publish/simple/index.wxml:65` | `view` | `bindtap` | `confirmAiNodePoi` | 1 | external | tap | cancelled,denied,duplicate-trigger,error,success,timeout | YES | PASS | #783 | PENDING |
| E1522 | `pages/publish/simple/index.wxml:71` | `cy-btn` | `bindtap` | `enterAiEditor` | 1 | navigation | tap | error,success | NO | PASS | #783 | PENDING |
| E1523 | `pages/publish/simple/index.wxml:80` | `textarea` | `bindinput` | `onAiIdeaInput` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1524 | `pages/publish/simple/index.wxml:81` | `view` | `bindtap` | `generateAiPlan` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #783 | PENDING |
| E1525 | `pages/publish/simple/index.wxml:90` | `creation-success` | `bind:action` | `onCreationSuccessAction` | 1 | navigation | event-contract | error,success | NO | PASS | #783 | PENDING |
| E1526 | `pages/publish/temp/index.wxml:6` | `cy-nav-bar` | `bind:back` | `goBack` | 1 | navigation | event-contract | error,success | NO | PASS | #787,#783,#779 | PENDING |
| E1527 | `pages/publish/temp/index.wxml:15` | `input` | `bindinput` | `onTitleChange` | 1 | state | event-contract | success | NO | PASS | #787,#783,#779 | PENDING |
| E1528 | `pages/publish/temp/index.wxml:28` | `cy-ai-assist` | `bind:generate` | `onNodeAiGenerate` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#779 | PENDING |
| E1529 | `pages/publish/temp/index.wxml:29` | `cy-ai-assist` | `bind:cancel` | `onNodeAiCancel` | 1 | state | event-contract | success | NO | PASS | #787,#783,#779 | PENDING |
| E1530 | `pages/publish/temp/index.wxml:43` | `textarea` | `bindinput` | `onDescriptionChange` | 1 | state | event-contract | success | NO | PASS | #787,#783,#779 | PENDING |
| E1531 | `pages/publish/temp/index.wxml:52` | `view` | `bindtap` | `openPlayersSheet` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1532 | `pages/publish/temp/index.wxml:59` | `view` | `bindtap` | `openDurationSheet` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1533 | `pages/publish/temp/index.wxml:70` | `view` | `bindtap` | `openDifficultySheet` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1534 | `pages/publish/temp/index.wxml:80` | `input` | `bindinput` | `onRequiredMaterialsChange` | 1 | state | event-contract | success | NO | PASS | #787,#783,#779 | PENDING |
| E1535 | `pages/publish/temp/index.wxml:85` | `view` | `bindtap` | `openVenueSheet` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1536 | `pages/publish/temp/index.wxml:97` | `view` | `catchtap` | `clearCover` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1537 | `pages/publish/temp/index.wxml:99` | `view` | `bindtap` | `uploadPic` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1538 | `pages/publish/temp/index.wxml:114` | `text` | `catchtap` | `removeCategory` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1539 | `pages/publish/temp/index.wxml:116` | `view` | `bindtap` | `navigateToCategorySelect` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1540 | `pages/publish/temp/index.wxml:128` | `view` | `bindtap` | `onPickInteraction` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1541 | `pages/publish/temp/index.wxml:137` | `textarea` | `bindinput` | `onRuleInstructionsChange` | 1 | state | event-contract | success | NO | PASS | #787,#783,#779 | PENDING |
| E1542 | `pages/publish/temp/index.wxml:160` | `view` | `bindtap` | `removeModule` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1543 | `pages/publish/temp/index.wxml:167` | `view` | `bindtap` | `selectValidationMethod` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1544 | `pages/publish/temp/index.wxml:180` | `input` | `bindinput` | `onQuestionNameChange` | 1 | state | event-contract | success | NO | PASS | #787,#783,#779 | PENDING |
| E1545 | `pages/publish/temp/index.wxml:183` | `input` | `bindinput` | `onQuestionAnswerChange` | 1 | state | event-contract | success | NO | PASS | #787,#783,#779 | PENDING |
| E1546 | `pages/publish/temp/index.wxml:191` | `input` | `bindinput` | `onOptionQuestionChange` | 1 | state | event-contract | success | NO | PASS | #787,#783,#779 | PENDING |
| E1547 | `pages/publish/temp/index.wxml:197` | `view` | `bindtap` | `pickCorrectOption` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1548 | `pages/publish/temp/index.wxml:198` | `input` | `bindinput` | `onOptionTextChange` | 1 | state | event-contract | success | NO | PASS | #787,#783,#779 | PENDING |
| E1549 | `pages/publish/temp/index.wxml:200` | `view` | `catchtap` | `deleteOption` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1550 | `pages/publish/temp/index.wxml:205` | `image` | `catchtap` | `uploadOptionImg` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1551 | `pages/publish/temp/index.wxml:206` | `view` | `catchtap` | `uploadOptionImg` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1552 | `pages/publish/temp/index.wxml:207` | `view` | `catchtap` | `uploadOptionAudio` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1553 | `pages/publish/temp/index.wxml:209` | `view` | `catchtap` | `clearOptionMedia` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1554 | `pages/publish/temp/index.wxml:213` | `view` | `bindtap` | `addChoiceOption` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1555 | `pages/publish/temp/index.wxml:220` | `textarea` | `bindinput` | `onPreferenceJsonChange` | 1 | state | event-contract | success | NO | PASS | #787,#783,#779 | PENDING |
| E1556 | `pages/publish/temp/index.wxml:222` | `view` | `bindtap` | `fillPreferenceExample` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1557 | `pages/publish/temp/index.wxml:239` | `view` | `catchtap` | `clearQuestionImg` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1558 | `pages/publish/temp/index.wxml:241` | `view` | `bindtap` | `uploadQuestionImg` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1559 | `pages/publish/temp/index.wxml:247` | `view` | `catchtap` | `clearQuestionAudio` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1560 | `pages/publish/temp/index.wxml:249` | `view` | `bindtap` | `uploadQuestionAudio` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1561 | `pages/publish/temp/index.wxml:259` | `textarea` | `bindinput` | `onPhotoRequireDescChange` | 1 | state | event-contract | success | NO | PASS | #787,#783,#779 | PENDING |
| E1562 | `pages/publish/temp/index.wxml:263` | `cy-switch` | `bindchange` | `onPhotoReviewChange` | 1 | state | event-contract | success | NO | PASS | #787,#783,#779 | PENDING |
| E1563 | `pages/publish/temp/index.wxml:282` | `cy-switch` | `bindchange` | `onHintEnabledChange` | 1 | state | event-contract | success | NO | PASS | #787,#783,#779 | PENDING |
| E1564 | `pages/publish/temp/index.wxml:285` | `input` | `bindinput` | `onHint1Change` | 1 | state | event-contract | success | NO | PASS | #787,#783,#779 | PENDING |
| E1565 | `pages/publish/temp/index.wxml:287` | `input` | `bindinput` | `onHint2Change` | 1 | state | event-contract | success | NO | PASS | #787,#783,#779 | PENDING |
| E1566 | `pages/publish/temp/index.wxml:289` | `input` | `bindinput` | `onAnswerRevealChange` | 1 | state | event-contract | success | NO | PASS | #787,#783,#779 | PENDING |
| E1567 | `pages/publish/temp/index.wxml:304` | `view` | `bindtap` | `removeModule` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1568 | `pages/publish/temp/index.wxml:315` | `cy-switch` | `bindchange` | `onToggleCoupon` | 1 | state | event-contract | success | NO | PASS | #787,#783,#779 | PENDING |
| E1569 | `pages/publish/temp/index.wxml:325` | `cy-reward-selector` | `bind:change` | `onRewardCouponChange` | 1 | state | event-contract | success | NO | PASS | #787,#783,#779 | PENDING |
| E1570 | `pages/publish/temp/index.wxml:337` | `cy-switch` | `bindchange` | `onToggleText` | 1 | state | event-contract | success | NO | PASS | #787,#783,#779 | PENDING |
| E1571 | `pages/publish/temp/index.wxml:340` | `textarea` | `bindinput` | `onFeedbackTextChange` | 1 | state | event-contract | success | NO | PASS | #787,#783,#779 | PENDING |
| E1572 | `pages/publish/temp/index.wxml:353` | `cy-switch` | `bindchange` | `onToggleMedal` | 1 | state | event-contract | success | NO | PASS | #787,#783,#779 | PENDING |
| E1573 | `pages/publish/temp/index.wxml:357` | `view` | `bindtap` | `uploadMedal` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1574 | `pages/publish/temp/index.wxml:361` | `input` | `bindinput` | `onMedalNameInput` | 1 | state | event-contract | success | NO | PASS | #787,#783,#779 | PENDING |
| E1575 | `pages/publish/temp/index.wxml:363` | `view` | `catchtap` | `clearMedal` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1576 | `pages/publish/temp/index.wxml:368` | `view` | `bindtap` | `onMedalStyle` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1577 | `pages/publish/temp/index.wxml:369` | `view` | `bindtap` | `onMedalStyle` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1578 | `pages/publish/temp/index.wxml:385` | `view` | `bindtap` | `removeModule` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1579 | `pages/publish/temp/index.wxml:391` | `view` | `bindtap` | `removeStoryBeat` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1580 | `pages/publish/temp/index.wxml:393` | `textarea` | `bindinput` | `onStoryBeatText` | 1 | state | event-contract | success | NO | PASS | #787,#783,#779 | PENDING |
| E1581 | `pages/publish/temp/index.wxml:396` | `input` | `bindinput` | `onStoryBeatTag` | 1 | state | event-contract | success | NO | PASS | #787,#783,#779 | PENDING |
| E1582 | `pages/publish/temp/index.wxml:408` | `view` | `catchtap` | `removeStoryBeatImg` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1583 | `pages/publish/temp/index.wxml:411` | `view` | `bindtap` | `uploadStoryBeatImg` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1584 | `pages/publish/temp/index.wxml:418` | `view` | `bindtap` | `addStoryBeat` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1585 | `pages/publish/temp/index.wxml:430` | `view` | `bindtap` | `removeModule` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1586 | `pages/publish/temp/index.wxml:436` | `view` | `bindtap` | `onPreviewAudio` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #787,#783,#779 | PENDING |
| E1587 | `pages/publish/temp/index.wxml:440` | `view` | `bindtap` | `clearAudio` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1588 | `pages/publish/temp/index.wxml:443` | `view` | `bindtap` | `uploadAudio` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1589 | `pages/publish/temp/index.wxml:446` | `input` | `bindinput` | `onAudioDurationChange` | 1 | state | event-contract | success | NO | PASS | #787,#783,#779 | PENDING |
| E1590 | `pages/publish/temp/index.wxml:459` | `cy-btn` | `bindtap` | `openAddSheet` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1591 | `pages/publish/temp/index.wxml:461` | `view` | `bindtap` | `openAddSheet` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1592 | `pages/publish/temp/index.wxml:466` | `cy-switch` | `bindchange` | `switch1Change` | 1 | state | event-contract | success | NO | PASS | #787,#783,#779 | PENDING |
| E1593 | `pages/publish/temp/index.wxml:474` | `cy-btn` | `bindtap` | `openPreview` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1594 | `pages/publish/temp/index.wxml:482` | `view` | `bindtap` | `pvPrev` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1595 | `pages/publish/temp/index.wxml:486` | `view` | `bindtap` | `pvNext` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1596 | `pages/publish/temp/index.wxml:509` | `cy-btn` | `bindtap` | `pvNext` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1597 | `pages/publish/temp/index.wxml:520` | `cy-btn` | `bindtap` | `pvNext` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1598 | `pages/publish/temp/index.wxml:534` | `input` | `bindinput` | `onPvAnswer` | 1 | state | event-contract | success | NO | PASS | #787,#783,#779 | PENDING |
| E1599 | `pages/publish/temp/index.wxml:538` | `view` | `bindtap` | `onPvSelectChoice` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1600 | `pages/publish/temp/index.wxml:556` | `view` | `bindtap` | `pvUseHint` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1601 | `pages/publish/temp/index.wxml:558` | `cy-btn` | `bindtap` | `pvSubmitChallenge` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1602 | `pages/publish/temp/index.wxml:566` | `cy-btn` | `bindtap` | `pvNext` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1603 | `pages/publish/temp/index.wxml:595` | `cy-btn` | `bindtap` | `saveDraft` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783,#779 | PENDING |
| E1604 | `pages/publish/temp/index.wxml:596` | `cy-btn` | `bindtap` | `publishGame` | 2 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783,#779 | PENDING |
| E1605 | `pages/publish/temp/index.wxml:601` | `view` | `bindtap` | `closeAddSheet` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1606 | `pages/publish/temp/index.wxml:607` | `view` | `bindtap` | `addModule` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1607 | `pages/publish/temp/index.wxml:620` | `view` | `bindtap` | `closePickerSheet` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1608 | `pages/publish/temp/index.wxml:626` | `view` | `bindtap` | `onPickerSheetSelect` | 1 | state | tap | success | NO | PASS | #787,#783,#779 | PENDING |
| E1609 | `pages/publish/temp/index.wxml:633` | `cy-category-sheet` | `bind:select` | `onCategorySelect` | 1 | state | event-contract | success | NO | PASS | #787,#783,#779 | PENDING |
| E1610 | `pages/publish/temp/index.wxml:633` | `cy-category-sheet` | `bind:close` | `onCategorySheetClose` | 1 | state | event-contract | success | NO | PASS | #787,#783,#779 | PENDING |
| E1611 | `pages/publish/temp/index.wxml:642` | `page-container` | `bind:afterleave` | `onNativeBackGuard` | 1 | state | event-contract | success | NO | PASS | #787,#783,#779 | PENDING |
| E1612 | `pages/publish/temp/index.wxml:645` | `creation-success` | `bind:action` | `onCreationSuccessAction` | 1 | navigation | event-contract | error,success | NO | PASS | #787,#783,#779 | PENDING |
| E1613 | `pages/publish/template-intro/index.wxml:10` | `view` | `bindtap` | `onSkip` | 1 | navigation | tap | error,success | NO | PASS | — | PENDING |
| E1614 | `pages/publish/template-intro/index.wxml:35` | `cy-btn` | `bindtap` | `goCreate` | 1 | navigation | tap | error,success | NO | PASS | — | PENDING |
| E1615 | `pages/publish/templateadd/templateadd.wxml:7` | `input` | `bindinput` | `onInputChange` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E1616 | `pages/publish/templateadd/templateadd.wxml:9` | `cy-btn` | `bindtap` | `onCreateTemplate` | 1 | navigation | tap | error,success | NO | PASS | — | PENDING |
| E1617 | `pages/publish/templateadd/templateadd.wxml:9` | `cy-btn` | `bind:disabledtap` | `onCreateTemplate` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E1618 | `pages/roam/index.wxml:21` | `image` | `bindtap` | `goProfile` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E1619 | `pages/roam/index.wxml:26` | `cy-tabs` | `bind:change` | `switchIntroTab` | 1 | navigation | event-contract | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E1620 | `pages/roam/index.wxml:29` | `text` | `bindtap` | `openRoamLocationSetting` | 1 | external | tap | cancelled,denied,duplicate-trigger,error,success,timeout | YES | PASS | #787,#783,#782 | PENDING |
| E1621 | `pages/roam/index.wxml:34` | `swiper` | `bindchange` | `onIntroCardSwipe` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1622 | `pages/roam/index.wxml:36` | `view` | `bindtap` | `onIntroCardTap` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E1623 | `pages/roam/index.wxml:67` | `view` | `bindtap` | `onPassportTileTap` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E1624 | `pages/roam/index.wxml:76` | `view` | `bindtap` | `onPassportPlayTap` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E1625 | `pages/roam/index.wxml:92` | `view` | `bindtap` | `openRoamRules` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E1626 | `pages/roam/index.wxml:97` | `view` | `bindtap` | `goStart` | 1 | external | tap | denied,duplicate-trigger,error,success,timeout | YES | PASS | #787,#783,#782 | PENDING |
| E1627 | `pages/roam/index.wxml:101` | `view` | `bindtap` | `goStartAndShoot` | 1 | external | tap | cancelled,chooseMedia-denied,duplicate-trigger,error,getLocation-denied,success,timeout | YES | PASS | #787,#783,#782 | PENDING |
| E1628 | `pages/roam/index.wxml:116` | `free-map` | `bind:updated` | `onMapUpdated` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1629 | `pages/roam/index.wxml:116` | `free-map` | `bind:markertap` | `onMarkerTap` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1630 | `pages/roam/index.wxml:116` | `free-map` | `bind:regionchange` | `onRegionChange` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1631 | `pages/roam/index.wxml:116` | `free-map` | `bind:poitap` | `onPoiTap` | 1 | write | event-contract | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E1632 | `pages/roam/index.wxml:122` | `image` | `bindload` | `onFogScreenLoad` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1633 | `pages/roam/index.wxml:122` | `image` | `binderror` | `onFogScreenError` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1634 | `pages/roam/index.wxml:133` | `cy-play-header` | `bind:action` | `onBoundEventTap` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E1635 | `pages/roam/index.wxml:144` | `view` | `bindtap` | `retryScreenFog` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E1636 | `pages/roam/index.wxml:153` | `view` | `bindtap` | `closeEventOverlay` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E1637 | `pages/roam/index.wxml:159` | `view` | `bindtap` | `verifyEventArrival` | 6 | external,state | tap | denied,duplicate-trigger,error,forbidden-owner,success,timeout | YES | PASS | #787,#783,#782 | PENDING |
| E1638 | `pages/roam/index.wxml:169` | `swiper` | `bindchange` | `onNearbySwipe` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1639 | `pages/roam/index.wxml:174` | `view` | `bindtap` | `startVisit` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #787,#783,#782 | PENDING |
| E1640 | `pages/roam/index.wxml:183` | `view` | `bindtap` | `closeNearbyBanner` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E1641 | `pages/roam/index.wxml:205` | `view` | `bindtap` | `closePaceCard` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E1642 | `pages/roam/index.wxml:210` | `view` | `bindtap` | `discoverRoamPoi` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E1643 | `pages/roam/index.wxml:218` | `view` | `bindtap` | `retryRoamPoiDiscover` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E1644 | `pages/roam/index.wxml:229` | `view` | `bindtap` | `closeFootprintHint` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E1645 | `pages/roam/index.wxml:262` | `view` | `bindtap` | `retryCheckin` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E1646 | `pages/roam/index.wxml:268` | `view` | `bindtap` | `onMapRetry` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E1647 | `pages/roam/index.wxml:278` | `view` | `bindtap` | `onPoiRetry` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E1648 | `pages/roam/index.wxml:290` | `view` | `bindtap` | `onGoalTap` | 6 | external,read,state,write | tap | denied,duplicate-trigger,error,forbidden-owner,success,timeout | YES | PASS | #787,#783,#782 | PENDING |
| E1649 | `pages/roam/index.wxml:295` | `view` | `catchtap` | `openRoamRules` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E1650 | `pages/roam/index.wxml:307` | `view` | `bindtap` | `toggleRoamTools` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E1651 | `pages/roam/index.wxml:309` | `view` | `bindtap` | `togglePause` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E1652 | `pages/roam/index.wxml:312` | `view` | `bindtap` | `onRoamStop` | 1 | external | tap | duplicate-trigger,error,forbidden-owner,modal-cancelled,success,timeout | YES | PASS | #787,#783,#782 | PENDING |
| E1653 | `pages/roam/index.wxml:317` | `view` | `bindtap` | `onTakePhoto` | 1 | external | tap | cancelled,denied,duplicate-trigger,error,success,timeout | YES | PASS | #787,#783,#782 | PENDING |
| E1654 | `pages/roam/index.wxml:321` | `view` | `bindtap` | `togglePause` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E1655 | `pages/roam/index.wxml:325` | `view` | `bindtap` | `onCenterTap` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E1656 | `pages/roam/index.wxml:336` | `cy-switch` | `bind:change` | `toggleGps` | 2 | external | event-contract | denied,duplicate-trigger,error,success,timeout | YES | PASS | #787,#783,#782 | PENDING |
| E1657 | `pages/roam/index.wxml:338` | `view` | `bindtap` | `goStampAlbum` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E1658 | `pages/roam/index.wxml:344` | `view` | `bindtap` | `openPassport` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E1659 | `pages/roam/index.wxml:349` | `view` | `bindtap` | `openRoamTasks` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E1660 | `pages/roam/index.wxml:354` | `view` | `bindtap` | `openRoamReward` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E1661 | `pages/roam/index.wxml:359` | `view` | `bindtap` | `openRoamHistory` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E1662 | `pages/roam/index.wxml:364` | `view` | `bindtap` | `openRoamRules` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E1663 | `pages/roam/index.wxml:369` | `view` | `bindtap` | `goDiscover` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E1664 | `pages/roam/index.wxml:374` | `view` | `bindtap` | `onInputAddress` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E1665 | `pages/roam/index.wxml:379` | `view` | `bindtap` | `toggleReducedMotion` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E1666 | `pages/roam/index.wxml:390` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E1667 | `pages/roam/index.wxml:391` | `cy-scene-sheet` | `bind:requestclose` | `requestSceneClose` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1668 | `pages/roam/index.wxml:394` | `view` | `bindtap` | `chooseLocationFromTencent` | 1 | external | tap | cancelled,denied,duplicate-trigger,error,forbidden-owner,success,timeout | YES | PASS | #787,#783,#782 | PENDING |
| E1669 | `pages/roam/index.wxml:405` | `view` | `bindtap` | `chooseLocationFromTencent` | 1 | external | tap | cancelled,denied,duplicate-trigger,error,forbidden-owner,success,timeout | YES | PASS | #787,#783,#782 | PENDING |
| E1670 | `pages/roam/index.wxml:411` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E1671 | `pages/roam/index.wxml:412` | `cy-scene-sheet` | `bind:requestclose` | `requestSceneClose` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1672 | `pages/roam/index.wxml:412` | `cy-scene-sheet` | `bind:back` | `backScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1673 | `pages/roam/index.wxml:422` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E1674 | `pages/roam/index.wxml:423` | `cy-scene-sheet` | `bind:requestclose` | `requestSceneClose` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1675 | `pages/roam/index.wxml:423` | `cy-scene-sheet` | `bind:back` | `backScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1676 | `pages/roam/index.wxml:424` | `cy-scene-roam-history` | `bind:open` | `openChildScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1677 | `pages/roam/index.wxml:424` | `cy-scene-roam-history` | `bind:close` | `closeScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1678 | `pages/roam/index.wxml:428` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E1679 | `pages/roam/index.wxml:429` | `cy-scene-sheet` | `bind:requestclose` | `requestSceneClose` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1680 | `pages/roam/index.wxml:429` | `cy-scene-sheet` | `bind:back` | `backScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1681 | `pages/roam/index.wxml:430` | `cy-scene-roam-session` | `bind:back` | `backScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1682 | `pages/roam/index.wxml:430` | `cy-scene-roam-session` | `bind:close` | `closeScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1683 | `pages/roam/index.wxml:430` | `cy-scene-roam-session` | `bind:share` | `onSceneSessionShare` | 1 | external | event-contract | duplicate-trigger,error,success,timeout | YES | PASS | #787,#783,#782 | PENDING |
| E1684 | `pages/roam/index.wxml:435` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E1685 | `pages/roam/index.wxml:436` | `cy-scene-sheet` | `bind:requestclose` | `requestSceneClose` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1686 | `pages/roam/index.wxml:436` | `cy-scene-sheet` | `bind:back` | `backScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1687 | `pages/roam/index.wxml:437` | `cy-scene-play-activity-detail` | `bind:open` | `openChildScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1688 | `pages/roam/index.wxml:437` | `cy-scene-play-activity-detail` | `bind:close` | `closeScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1689 | `pages/roam/index.wxml:441` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E1690 | `pages/roam/index.wxml:442` | `cy-scene-sheet` | `bind:requestclose` | `requestSceneClose` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1691 | `pages/roam/index.wxml:442` | `cy-scene-sheet` | `bind:back` | `backScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1692 | `pages/roam/index.wxml:443` | `cy-scene-game-coupon-wallet` | `bind:open` | `openChildScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1693 | `pages/roam/index.wxml:443` | `cy-scene-game-coupon-wallet` | `bind:close` | `closeScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1694 | `pages/roam/index.wxml:447` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E1695 | `pages/roam/index.wxml:448` | `cy-scene-sheet` | `bind:requestclose` | `requestSceneClose` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1696 | `pages/roam/index.wxml:448` | `cy-scene-sheet` | `bind:back` | `backScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1697 | `pages/roam/index.wxml:449` | `cy-scene-qr-coupon` | `bind:close` | `backScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1698 | `pages/roam/index.wxml:453` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E1699 | `pages/roam/index.wxml:454` | `cy-scene-sheet` | `bind:requestclose` | `requestSceneClose` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1700 | `pages/roam/index.wxml:454` | `cy-scene-sheet` | `bind:back` | `backScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1701 | `pages/roam/index.wxml:455` | `cy-scene-qr-group-code` | `bind:close` | `backScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1702 | `pages/roam/index.wxml:459` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E1703 | `pages/roam/index.wxml:460` | `cy-scene-sheet` | `bind:requestclose` | `requestSceneClose` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1704 | `pages/roam/index.wxml:460` | `cy-scene-sheet` | `bind:back` | `backScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1705 | `pages/roam/index.wxml:461` | `cy-scene-qr-citynode` | `bind:close` | `backScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1706 | `pages/roam/index.wxml:465` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E1707 | `pages/roam/index.wxml:466` | `cy-scene-sheet` | `bind:requestclose` | `requestSceneClose` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1708 | `pages/roam/index.wxml:466` | `cy-scene-sheet` | `bind:back` | `backScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1709 | `pages/roam/index.wxml:467` | `cy-scene-roam-stamp-album` | `bind:camera` | `openStampCamera` | 1 | navigation | event-contract | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E1710 | `pages/roam/index.wxml:467` | `cy-scene-roam-stamp-album` | `bind:close` | `closeScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1711 | `pages/roam/index.wxml:471` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E1712 | `pages/roam/index.wxml:472` | `cy-scene-sheet` | `bind:requestclose` | `requestSceneClose` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1713 | `pages/roam/index.wxml:472` | `cy-scene-sheet` | `bind:back` | `backScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1714 | `pages/roam/index.wxml:473` | `cy-scene-roam-task-list` | `bind:open` | `openChildScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1715 | `pages/roam/index.wxml:473` | `cy-scene-roam-task-list` | `bind:close` | `closeScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1716 | `pages/roam/index.wxml:477` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E1717 | `pages/roam/index.wxml:478` | `cy-scene-sheet` | `bind:requestclose` | `requestSceneClose` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1718 | `pages/roam/index.wxml:478` | `cy-scene-sheet` | `bind:back` | `backScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1719 | `pages/roam/index.wxml:479` | `cy-scene-roam-discover` | `bind:open` | `openChildScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1720 | `pages/roam/index.wxml:479` | `cy-scene-roam-discover` | `bind:close` | `closeScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1721 | `pages/roam/index.wxml:483` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E1722 | `pages/roam/index.wxml:484` | `cy-scene-sheet` | `bind:requestclose` | `requestSceneClose` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1723 | `pages/roam/index.wxml:484` | `cy-scene-sheet` | `bind:back` | `backScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1724 | `pages/roam/index.wxml:485` | `cy-scene-roam-poi-detail` | `bind:open` | `openChildScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1725 | `pages/roam/index.wxml:485` | `cy-scene-roam-poi-detail` | `bind:close` | `closeScene` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1726 | `pages/roam/index.wxml:489` | `cy-modal` | `bind:confirm` | `confirmSceneDiscard` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1727 | `pages/roam/index.wxml:489` | `cy-modal` | `bind:cancel` | `cancelSceneDiscard` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1728 | `pages/roam/index.wxml:499` | `view` | `bindtap` | `backToSquare` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E1729 | `pages/roam/index.wxml:529` | `cy-error` | `bind:retry` | `retrySettle` | 1 | write | event-contract | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E1730 | `pages/roam/index.wxml:534` | `cy-error` | `bind:retry` | `retryEventReport` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E1731 | `pages/roam/index.wxml:544` | `view` | `bindtap` | `openShare` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E1732 | `pages/roam/index.wxml:546` | `text` | `bindtap` | `openPassport` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E1733 | `pages/roam/index.wxml:548` | `text` | `bindtap` | `backToSquare` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E1734 | `pages/roam/index.wxml:555` | `cy-sheet` | `bind:close` | `closeDiscoverReward` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1735 | `pages/roam/index.wxml:561` | `view` | `bindtap` | `onDiscoverCoupon` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E1736 | `pages/roam/index.wxml:566` | `view` | `bindtap` | `onDiscoverTask` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E1737 | `pages/roam/index.wxml:571` | `view` | `bindtap` | `onDiscoverExploreDay` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E1738 | `pages/roam/index.wxml:577` | `view` | `bindtap` | `closeDiscoverReward` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E1739 | `pages/roam/index.wxml:584` | `view` | `bindtap` | `openBadge3d` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E1740 | `pages/roam/index.wxml:589` | `view` | `bindtap` | `shareMedal` | 1 | external | tap | duplicate-trigger,error,openSetting-cancelled,openSetting-denied,saveImageToPhotosAlbum-cancelled,saveImageToPhotosAlbum-denied,success,timeout | YES | PASS | #787,#783,#782 | PENDING |
| E1741 | `pages/roam/index.wxml:591` | `cy-slide-confirm` | `bind:confirm` | `closeMedal` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1742 | `pages/roam/index.wxml:598` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E1743 | `pages/roam/index.wxml:599` | `cy-scene-sheet` | `bind:requestclose` | `requestSceneClose` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1744 | `pages/roam/index.wxml:603` | `view` | `bindtap` | `shareTo` | 1 | external | tap | duplicate-trigger,error,forbidden-owner,openSetting-cancelled,openSetting-denied,saveImageToPhotosAlbum-cancelled,saveImageToPhotosAlbum-denied,success,timeout | YES | PASS | #787,#783,#782 | PENDING |
| E1745 | `pages/roam/index.wxml:604` | `view` | `bindtap` | `shareTo` | 1 | external | tap | duplicate-trigger,error,forbidden-owner,openSetting-cancelled,openSetting-denied,saveImageToPhotosAlbum-cancelled,saveImageToPhotosAlbum-denied,success,timeout | YES | PASS | #787,#783,#782 | PENDING |
| E1746 | `pages/roam/index.wxml:605` | `view` | `bindtap` | `shareTo` | 1 | external | tap | duplicate-trigger,error,forbidden-owner,openSetting-cancelled,openSetting-denied,saveImageToPhotosAlbum-cancelled,saveImageToPhotosAlbum-denied,success,timeout | YES | PASS | #787,#783,#782 | PENDING |
| E1747 | `pages/roam/index.wxml:606` | `view` | `bindtap` | `shareTo` | 1 | external | tap | duplicate-trigger,error,forbidden-owner,openSetting-cancelled,openSetting-denied,saveImageToPhotosAlbum-cancelled,saveImageToPhotosAlbum-denied,success,timeout | YES | PASS | #787,#783,#782 | PENDING |
| E1748 | `pages/roam/index.wxml:608` | `view` | `bindtap` | `closeShare` | 1 | state | tap | success | NO | PASS | #787,#783,#782 | PENDING |
| E1749 | `pages/roam/index.wxml:622` | `cy-privacy-gate` | `bind:settled` | `onPrivacyGateSettled` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1750 | `pages/search2/index.wxml:10` | `cy-search` | `bind:input` | `onKeywordInput` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E1751 | `pages/search2/index.wxml:10` | `cy-search` | `bind:confirm` | `onSearchConfirm` | 1 | navigation | event-contract | error,success | NO | PASS | #783,#782 | PENDING |
| E1752 | `pages/search2/index.wxml:11` | `cy-btn` | `bindtap` | `popShow` | 1 | state | tap | success | NO | PASS | #783,#782 | PENDING |
| E1753 | `pages/search2/index.wxml:21` | `text` | `bindtap` | `onClearHistory` | 1 | state | tap | success | NO | PASS | #783,#782 | PENDING |
| E1754 | `pages/search2/index.wxml:28` | `view` | `bindtap` | `onHotKeyClick` | 1 | navigation | tap | error,success | NO | PASS | #783,#782 | PENDING |
| E1755 | `pages/search2/index.wxml:44` | `view` | `bindtap` | `onHotKeyClick` | 1 | navigation | tap | error,success | NO | PASS | #783,#782 | PENDING |
| E1756 | `pages/search2/index.wxml:57` | `cy-error` | `bind:retry` | `getCategoryList` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783,#782 | PENDING |
| E1757 | `pages/search2/index.wxml:63` | `view` | `bindtap` | `onCategoryClick` | 1 | navigation | tap | error,success | NO | PASS | #783,#782 | PENDING |
| E1758 | `pages/search2/index.wxml:79` | `cy-btn` | `bindtap` | `goMap` | 1 | navigation | tap | error,success | NO | PASS | #783,#782 | PENDING |
| E1759 | `pages/search2/index.wxml:88` | `cy-sheet` | `bind:close` | `popClose` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E1760 | `pages/search2/index.wxml:94` | `view` | `bindtap` | `selectDate` | 1 | state | tap | success | NO | PASS | #783,#782 | PENDING |
| E1761 | `pages/search2/index.wxml:98` | `view` | `bindtap` | `selectDate` | 1 | state | tap | success | NO | PASS | #783,#782 | PENDING |
| E1762 | `pages/search2/index.wxml:102` | `view` | `bindtap` | `selectDate` | 1 | state | tap | success | NO | PASS | #783,#782 | PENDING |
| E1763 | `pages/search2/index.wxml:110` | `view` | `bindtap` | `openDatePicker` | 1 | state | tap | success | NO | PASS | #783,#782 | PENDING |
| E1764 | `pages/search2/index.wxml:114` | `view` | `bindtap` | `openDatePicker` | 1 | state | tap | success | NO | PASS | #783,#782 | PENDING |
| E1765 | `pages/search2/index.wxml:129` | `input` | `bindinput` | `onMinPriceInput` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E1766 | `pages/search2/index.wxml:135` | `input` | `bindinput` | `onMaxPriceInput` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E1767 | `pages/search2/index.wxml:144` | `view` | `bindtouchmove` | `onMinSliderMove` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E1768 | `pages/search2/index.wxml:145` | `view` | `bindtouchmove` | `onMaxSliderMove` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E1769 | `pages/search2/index.wxml:157` | `view` | `bindtap` | `selectSort` | 1 | state | tap | success | NO | PASS | #783,#782 | PENDING |
| E1770 | `pages/search2/index.wxml:161` | `view` | `bindtap` | `selectSort` | 1 | state | tap | success | NO | PASS | #783,#782 | PENDING |
| E1771 | `pages/search2/index.wxml:170` | `cy-btn` | `bindtap` | `resetFilter` | 1 | state | tap | success | NO | PASS | #783,#782 | PENDING |
| E1772 | `pages/search2/index.wxml:171` | `cy-btn` | `bindtap` | `searchFilter` | 1 | navigation | tap | error,success | NO | PASS | #783,#782 | PENDING |
| E1773 | `pages/search2/index.wxml:176` | `cy-date-sheet` | `bind:cancel` | `cancelDatePicker` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E1774 | `pages/search2/index.wxml:176` | `cy-date-sheet` | `bind:confirm` | `confirmDatePicker` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E1775 | `pages/search2/index.wxml:177` | `picker-view` | `bindchange` | `onDatePickerChange` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E1776 | `pages/search2/result/index.wxml:7` | `cy-search` | `bind:input` | `onKeywordInput` | 1 | state | event-contract | success | NO | PASS | #782 | PENDING |
| E1777 | `pages/search2/result/index.wxml:7` | `cy-search` | `bind:confirm` | `onSearchConfirm` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #782 | PENDING |
| E1778 | `pages/search2/result/index.wxml:13` | `cy-tabs` | `bind:change` | `onResultTab` | 1 | state | event-contract | success | NO | PASS | #782 | PENDING |
| E1779 | `pages/search2/result/index.wxml:16` | `cy-error` | `bind:retry` | `runSearch` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #782 | PENDING |
| E1780 | `pages/search2/result/index.wxml:19` | `view` | `bindtap` | `goResult` | 1 | navigation | tap | error,success | NO | PASS | #782 | PENDING |
| E1781 | `pages/searchmap/index.wxml:11` | `image` | `bindtap` | `goActivity` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E1782 | `pages/searchmap/index.wxml:17` | `view` | `bindtap` | `goActivity` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E1783 | `pages/searchmap/index.wxml:43` | `map` | `bindtap` | `onMapTap` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1784 | `pages/searchmap/index.wxml:44` | `map` | `bindmarkertap` | `onMarkerTap` | 1 | navigation | event-contract | error,success | NO | PASS | #787,#783 | PENDING |
| E1785 | `pages/searchmap/index.wxml:45` | `map` | `bindregionchange` | `onRegionChange` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E1786 | `pages/searchmap/index.wxml:48` | `cover-view` | `bindtap` | `requestLocation` | 1 | external | tap | denied,duplicate-trigger,error,modal-cancelled,success,timeout | YES | PASS | #787,#783 | PENDING |
| E1787 | `pages/searchmap/index.wxml:53` | `cover-view` | `bindtap` | `recoverLocation` | 1 | external | tap | cancelled,duplicate-trigger,error,getLocation-denied,openSetting-denied,success,timeout | YES | PASS | #787,#783 | PENDING |
| E1788 | `pages/searchmap/index.wxml:60` | `view` | `bindtap` | `goBack` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E1789 | `pages/searchmap/index.wxml:68` | `cy-search` | `bind:input` | `onKeywordInput` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1790 | `pages/searchmap/index.wxml:68` | `cy-search` | `bind:confirm` | `onSearchConfirm` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E1791 | `pages/searchmap/index.wxml:69` | `cy-btn` | `bindtap` | `popShow` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1792 | `pages/searchmap/index.wxml:78` | `view` | `bindtap` | `openList` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E1793 | `pages/searchmap/index.wxml:86` | `cy-sheet` | `bind:close` | `bmClose` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1794 | `pages/searchmap/index.wxml:87` | `cy-error` | `bind:retry` | `onRetry` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E1795 | `pages/searchmap/index.wxml:101` | `cy-sheet` | `bind:close` | `selClose` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1796 | `pages/searchmap/index.wxml:104` | `cy-btn` | `bindtap` | `goActivity` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E1797 | `pages/searchmap/index.wxml:105` | `cy-btn` | `bindtap` | `goTopic` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E1798 | `pages/searchmap/index.wxml:110` | `cy-sheet` | `bind:close` | `popClose` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1799 | `pages/searchmap/index.wxml:115` | `view` | `bindtap` | `openMerchantCategorySheet` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1800 | `pages/searchmap/index.wxml:116` | `input` | `bindinput` | `onMerchantTagInput` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1801 | `pages/searchmap/index.wxml:117` | `input` | `bindinput` | `onMerchantRoleInput` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1802 | `pages/searchmap/index.wxml:124` | `view` | `bindtap` | `selectDate` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1803 | `pages/searchmap/index.wxml:128` | `view` | `bindtap` | `selectDate` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1804 | `pages/searchmap/index.wxml:132` | `view` | `bindtap` | `selectDate` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1805 | `pages/searchmap/index.wxml:140` | `view` | `bindtap` | `openDatePicker` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1806 | `pages/searchmap/index.wxml:144` | `view` | `bindtap` | `openDatePicker` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1807 | `pages/searchmap/index.wxml:159` | `input` | `bindinput` | `onMinPriceInput` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1808 | `pages/searchmap/index.wxml:165` | `input` | `bindinput` | `onMaxPriceInput` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1809 | `pages/searchmap/index.wxml:174` | `view` | `bindtouchmove` | `onMinSliderMove` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1810 | `pages/searchmap/index.wxml:175` | `view` | `bindtouchmove` | `onMaxSliderMove` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1811 | `pages/searchmap/index.wxml:187` | `view` | `bindtap` | `selectSort` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1812 | `pages/searchmap/index.wxml:191` | `view` | `bindtap` | `selectSort` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1813 | `pages/searchmap/index.wxml:200` | `cy-btn` | `bindtap` | `resetFilter` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1814 | `pages/searchmap/index.wxml:201` | `cy-btn` | `bindtap` | `searchFilter` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E1815 | `pages/searchmap/index.wxml:206` | `cy-category-sheet` | `bind:select` | `onMerchantCategoryChange` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1816 | `pages/searchmap/index.wxml:206` | `cy-category-sheet` | `bind:close` | `closeMerchantCategorySheet` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1817 | `pages/searchmap/index.wxml:209` | `cy-date-sheet` | `bind:cancel` | `cancelDatePicker` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1818 | `pages/searchmap/index.wxml:209` | `cy-date-sheet` | `bind:confirm` | `confirmDatePicker` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1819 | `pages/searchmap/index.wxml:210` | `picker-view` | `bindchange` | `onDatePickerChange` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1820 | `pages/shezhi/about/index.wxml:2` | `cy-nav-bar` | `bind:back` | `onBack` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E1821 | `pages/shezhi/about/index.wxml:25` | `cy-qr-voucher` | `bind:retry` | `explainQrPending` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E1822 | `pages/shezhi/about/index.wxml:30` | `cy-cell` | `bind:tap` | `openAgreement` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E1823 | `pages/shezhi/about/index.wxml:36` | `cy-agreement-sheet` | `bind:close` | `closeAgreement` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E1824 | `pages/shezhi/components/privacy-sheet/index.wxml:2` | `cy-scene-sheet` | `bind:requestclose` | `onClose` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E1825 | `pages/shezhi/components/privacy-sheet/index.wxml:5` | `view` | `bindtap` | `openPrivacyContract` | 1 | external | tap | cancelled,denied,duplicate-trigger,error,success,timeout | YES | PASS | — | PENDING |
| E1826 | `pages/shezhi/components/privacy-sheet/index.wxml:9` | `cy-btn` | `bindtap` | `withdrawRoamLocationConsent` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | — | PENDING |
| E1827 | `pages/shezhi/components/scene-sound-haptics/index.wxml:3` | `view` | `bindtap` | `toggle` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E1828 | `pages/shezhi/components/scene-sound-haptics/index.wxml:11` | `view` | `bindtap` | `toggle` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E1829 | `pages/shezhi/components/scene-sound-haptics/index.wxml:23` | `view` | `bindtap` | `toggle` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E1830 | `pages/shezhi/components/scene-sound-haptics/index.wxml:31` | `view` | `bindtap` | `toggle` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E1831 | `pages/shezhi/components/scene-sound-haptics/index.wxml:39` | `view` | `bindtap` | `toggle` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E1832 | `pages/shezhi/components/scene-sound-haptics/index.wxml:47` | `view` | `bindtap` | `toggle` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E1833 | `pages/shezhi/shezhi.wxml:2` | `cy-nav-bar` | `bind:back` | `onBack` | 1 | navigation | event-contract | error,success | NO | PASS | #783 | PENDING |
| E1834 | `pages/shezhi/shezhi.wxml:11` | `cy-cell` | `bind:tap` | `goUserInfo` | 1 | navigation | tap | error,success | NO | PASS | #783 | PENDING |
| E1835 | `pages/shezhi/shezhi.wxml:15` | `cy-cell` | `bind:tap` | `pickClub` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #783 | PENDING |
| E1836 | `pages/shezhi/shezhi.wxml:16` | `cy-cell` | `bind:tap` | `pickMerchant` | 1 | navigation | tap | error,success | NO | PASS | #783 | PENDING |
| E1837 | `pages/shezhi/shezhi.wxml:17` | `cy-cell` | `bind:tap` | `goInfomation` | 1 | navigation | tap | error,success | NO | PASS | #783 | PENDING |
| E1838 | `pages/shezhi/shezhi.wxml:18` | `cy-cell` | `bind:tap` | `goMylike` | 1 | navigation | tap | error,success | NO | PASS | #783 | PENDING |
| E1839 | `pages/shezhi/shezhi.wxml:19` | `cy-cell` | `bind:tap` | `goSoundHaptics` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E1840 | `pages/shezhi/shezhi.wxml:20` | `cy-cell` | `bind:tap` | `goPrivacy` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E1841 | `pages/shezhi/shezhi.wxml:21` | `cy-cell` | `bind:tap` | `goUserAgreement` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E1842 | `pages/shezhi/shezhi.wxml:22` | `cy-cell` | `bind:tap` | `goDeregister` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E1843 | `pages/shezhi/shezhi.wxml:23` | `cy-cell` | `bind:tap` | `goAbout` | 1 | navigation | tap | error,success | NO | PASS | #783 | PENDING |
| E1844 | `pages/shezhi/shezhi.wxml:31` | `cy-btn` | `bindtap` | `logout` | 1 | read | tap | duplicate-trigger,error,modal-cancelled,success,timeout | NO | PASS | #783 | PENDING |
| E1845 | `pages/shezhi/shezhi.wxml:38` | `cy-agreement-sheet` | `bind:close` | `closeAgreementSheet` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1846 | `pages/shezhi/shezhi.wxml:40` | `cy-privacy-sheet` | `bind:close` | `closePrivacySheet` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1847 | `pages/shezhi/shezhi.wxml:43` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #783 | PENDING |
| E1848 | `pages/shezhi/shezhi.wxml:44` | `cy-scene-sheet` | `bind:requestclose` | `closeRegPicker` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1849 | `pages/shezhi/shezhi.wxml:45` | `view` | `bindtap` | `pickClub` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #783 | PENDING |
| E1850 | `pages/shezhi/shezhi.wxml:53` | `view` | `bindtap` | `pickMerchant` | 1 | navigation | tap | error,success | NO | PASS | #783 | PENDING |
| E1851 | `pages/shezhi/shezhi.wxml:61` | `view` | `bindtap` | `closeRegPicker` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E1852 | `pages/shezhi/shezhi.wxml:67` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #783 | PENDING |
| E1853 | `pages/shezhi/shezhi.wxml:68` | `cy-scene-sheet` | `bind:requestclose` | `requestSceneClose` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1854 | `pages/shezhi/shezhi.wxml:68` | `cy-scene-sheet` | `bind:back` | `backScene` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1855 | `pages/shezhi/shezhi.wxml:70` | `cy-scene-route-content` | `bind:open` | `openChildScene` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1856 | `pages/shezhi/shezhi.wxml:70` | `cy-scene-route-content` | `bind:back` | `backScene` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1857 | `pages/shezhi/shezhi.wxml:70` | `cy-scene-route-content` | `bind:close` | `closeScene` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1858 | `pages/shezhi/shezhi.wxml:70` | `cy-scene-route-content` | `bind:dirtychange` | `onSceneDirtyChange` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1859 | `pages/shezhi/shezhi.wxml:72` | `cy-btn` | `bindtap` | `submitSceneForm` | 1 | other | tap | error,success | NO | PASS | #783 | PENDING |
| E1860 | `pages/shezhi/shezhi.wxml:76` | `cy-modal` | `bind:confirm` | `confirmSceneDiscard` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1861 | `pages/shezhi/shezhi.wxml:76` | `cy-modal` | `bind:cancel` | `cancelSceneDiscard` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1862 | `pages/shezhi/shezhi.wxml:79` | `cy-privacy-gate` | `bind:settled` | `onPrivacyGateSettled` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1863 | `pages/square/components/activity-picker/index.wxml:1` | `view` | `catchtap` | `onClose` | 1 | other | tap | error,success | NO | PASS | #783 | PENDING |
| E1864 | `pages/square/components/activity-picker/index.wxml:1` | `view` | `catchtouchmove` | `noop` | 1 | other | event-contract | error,success | NO | PASS | #783 | PENDING |
| E1865 | `pages/square/components/activity-picker/index.wxml:2` | `view` | `catchtap` | `noop` | 1 | other | tap | error,success | NO | PASS | #783 | PENDING |
| E1866 | `pages/square/components/activity-picker/index.wxml:5` | `view` | `catchtap` | `onClose` | 1 | other | tap | error,success | NO | PASS | #783 | PENDING |
| E1867 | `pages/square/components/activity-picker/index.wxml:11` | `view` | `catchtap` | `onSelect` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E1868 | `pages/square/components/activity-picker/index.wxml:24` | `view` | `catchtap` | `noop` | 1 | other | tap | error,success | NO | PASS | #783 | PENDING |
| E1869 | `pages/square/components/activity-picker/index.wxml:24` | `view` | `catchtouchmove` | `noop` | 1 | other | event-contract | error,success | NO | PASS | #783 | PENDING |
| E1870 | `pages/square/components/activity-picker/index.wxml:25` | `route-map` | `bind:ready` | `onRouteReady` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783 | PENDING |
| E1871 | `pages/square/components/activity-picker/index.wxml:25` | `route-map` | `bind:empty` | `onRouteEmpty` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E1872 | `pages/square/components/feed-play-card/index.wxml:1` | `view` | `catchtap` | `onDetail` | 1 | other | tap | error,success | NO | PASS | — | PENDING |
| E1873 | `pages/square/components/feed-play-card/index.wxml:12` | `view` | `catchtap` | `onDetail` | 1 | other | tap | error,success | NO | PASS | — | PENDING |
| E1874 | `pages/square/components/feed-play-card/index.wxml:14` | `view` | `catchtap` | `onPlay` | 1 | other | tap | error,success | NO | PASS | — | PENDING |
| E1875 | `pages/square/components/route-map/index.wxml:11` | `map` | `bindmarkertap` | `onNodeTap` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E1876 | `pages/square/detail/index.wxml:3` | `cy-nav-bar` | `bind:back` | `goBack` | 1 | navigation | event-contract | error,success | NO | PASS | #787,#783 | PENDING |
| E1877 | `pages/square/detail/index.wxml:10` | `cy-error` | `bind:retry` | `onRetryDetail` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E1878 | `pages/square/detail/index.wxml:11` | `cy-btn` | `bindtap` | `goBack` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E1879 | `pages/square/detail/index.wxml:14` | `cy-empty` | `bind:cta` | `goBack` | 1 | navigation | event-contract | error,success | NO | PASS | #787,#783 | PENDING |
| E1880 | `pages/square/detail/index.wxml:17` | `cy-empty` | `bind:cta` | `goBack` | 1 | navigation | event-contract | error,success | NO | PASS | #787,#783 | PENDING |
| E1881 | `pages/square/detail/index.wxml:24` | `view` | `bindtap` | `goUserInfoClcik` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E1882 | `pages/square/detail/index.wxml:26` | `button` | `catchtap` | `followClick` | 1 | write | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E1883 | `pages/square/detail/index.wxml:37` | `button` | `catchtap` | `followClick` | 1 | write | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E1884 | `pages/square/detail/index.wxml:38` | `view` | `catchtap` | `openPostActions` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1885 | `pages/square/detail/index.wxml:53` | `cy-feed-play-card` | `bind:detail` | `goSportTopic` | 1 | navigation | event-contract | error,success | NO | PASS | #787,#783 | PENDING |
| E1886 | `pages/square/detail/index.wxml:54` | `cy-feed-play-card` | `bind:play` | `goPlay` | 1 | navigation | event-contract | error,success | NO | PASS | #787,#783 | PENDING |
| E1887 | `pages/square/detail/index.wxml:61` | `image` | `catchtap` | `previewImage` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #787,#783 | PENDING |
| E1888 | `pages/square/detail/index.wxml:69` | `view` | `catchtap` | `lickClick` | 1 | write | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E1889 | `pages/square/detail/index.wxml:72` | `view` | `catchtap` | `focusCommentInput` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1890 | `pages/square/detail/index.wxml:121` | `button` | `bindtap` | `commentLikeClick` | 1 | write | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E1891 | `pages/square/detail/index.wxml:125` | `button` | `bindtap` | `msgClick` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1892 | `pages/square/detail/index.wxml:128` | `button` | `bindtap` | `reportComment` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,modal-cancelled,success,timeout | NO | PASS | #787,#783 | PENDING |
| E1893 | `pages/square/detail/index.wxml:148` | `input` | `bindinput` | `onCommentInput` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1894 | `pages/square/detail/index.wxml:149` | `input` | `bindconfirm` | `addComment` | 1 | write | event-contract | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783 | PENDING |
| E1895 | `pages/square/detail/index.wxml:155` | `cy-sheet` | `bind:close` | `closePostActions` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1896 | `pages/square/detail/index.wxml:157` | `view` | `bindtap` | `reportSquareFromActions` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,modal-cancelled,success,timeout | NO | PASS | #787,#783 | PENDING |
| E1897 | `pages/square/detail/index.wxml:163` | `cy-sheet` | `bind:close` | `popClose` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1898 | `pages/square/detail/index.wxml:173` | `input` | `bindinput` | `onReplyInput` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1899 | `pages/square/detail/index.wxml:177` | `view` | `bindtap` | `addReplyComment` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783 | PENDING |
| E1900 | `pages/square/list/index.wxml:4` | `cy-nav-bar` | `bind:back` | `goBack` | 1 | navigation | event-contract | error,success | NO | PASS | #787,#783 | PENDING |
| E1901 | `pages/square/list/index.wxml:11` | `scroll-view` | `bindscroll` | `onFeedScroll` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1902 | `pages/square/list/index.wxml:12` | `scroll-view` | `bindscrolltolower` | `onReachBottom` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E1903 | `pages/square/list/index.wxml:22` | `view` | `bindtap` | `goUpcoming` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E1904 | `pages/square/list/index.wxml:50` | `textarea` | `bindinput` | `bindTextAreaBlur` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1905 | `pages/square/list/index.wxml:51` | `textarea` | `bindconfirm` | `submitFormData` | 1 | write | event-contract | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783 | PENDING |
| E1906 | `pages/square/list/index.wxml:61` | `image` | `catchtap` | `delPic` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1907 | `pages/square/list/index.wxml:73` | `image` | `catchtap` | `clearLocation` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1908 | `pages/square/list/index.wxml:82` | `image` | `catchtap` | `clearActivity` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1909 | `pages/square/list/index.wxml:88` | `button` | `bindtap` | `uploadPic` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1910 | `pages/square/list/index.wxml:91` | `button` | `bindtap` | `choosePoiForNode` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1911 | `pages/square/list/index.wxml:94` | `button` | `bindtap` | `openActivityPicker` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1912 | `pages/square/list/index.wxml:102` | `view` | `bindtap` | `jumpDetail` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E1913 | `pages/square/list/index.wxml:105` | `image` | `catchtap` | `goUserInfoClcik` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E1914 | `pages/square/list/index.wxml:106` | `button` | `catchtap` | `followClick` | 1 | write | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E1915 | `pages/square/list/index.wxml:126` | `view` | `catchtap` | `showAction` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1916 | `pages/square/list/index.wxml:135` | `view` | `catchtap` | `jumpDetail` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E1917 | `pages/square/list/index.wxml:140` | `view` | `catchtap` | `goPlayTopicDetail` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E1918 | `pages/square/list/index.wxml:172` | `cy-feed-play-card` | `bind:detail` | `goPlayTopicDetail` | 1 | navigation | event-contract | error,success | NO | PASS | #787,#783 | PENDING |
| E1919 | `pages/square/list/index.wxml:173` | `cy-feed-play-card` | `bind:play` | `goPlay` | 1 | navigation | event-contract | error,success | NO | PASS | #787,#783 | PENDING |
| E1920 | `pages/square/list/index.wxml:180` | `image` | `catchtap` | `jumpDetail` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E1921 | `pages/square/list/index.wxml:182` | `image` | `catchtap` | `previewImage` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #787,#783 | PENDING |
| E1922 | `pages/square/list/index.wxml:189` | `view` | `catchtap` | `lickClick` | 1 | write | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E1923 | `pages/square/list/index.wxml:194` | `view` | `catchtap` | `jumpDetail2` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E1924 | `pages/square/list/index.wxml:208` | `cy-error` | `bindretry` | `onLoadRetry` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E1925 | `pages/square/list/index.wxml:212` | `activity-picker` | `bind:select` | `onActivitySelect` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1926 | `pages/square/list/index.wxml:212` | `activity-picker` | `bind:close` | `onActivityPickerClose` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E1927 | `pages/square/list/index.wxml:217` | `view` | `bindtap` | `bmClose` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1928 | `pages/square/list/index.wxml:220` | `view` | `bindtap` | `reportSquare` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,modal-cancelled,success,timeout | NO | PASS | #787,#783 | PENDING |
| E1929 | `pages/square/list/index.wxml:225` | `view` | `bindtap` | `bmClose` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E1930 | `pages/talent/list/index.wxml:8` | `view` | `bindtap` | `onMsgTap` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E1931 | `pages/talent/list/index.wxml:21` | `cy-tabs` | `bind:change` | `onTopTabChange` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E1932 | `pages/talent/list/index.wxml:26` | `cy-error` | `bind:retry` | `loadFeed` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E1933 | `pages/talent/list/index.wxml:28` | `cy-card` | `bindtap` | `onPostTap` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E1934 | `pages/talent/list/index.wxml:56` | `view` | `bindtap` | `onClubApply` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E1935 | `pages/talent/list/index.wxml:60` | `view` | `bindtap` | `goCoopCenter` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E1936 | `pages/talent/list/index.wxml:77` | `cy-club-card` | `bind:tap` | `goClubDetail` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E1937 | `pages/talent/list/index.wxml:77` | `cy-club-card` | `bind:action` | `onClubCardAction` | 1 | write | event-contract | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E1938 | `pages/talent/list/index.wxml:79` | `cy-club-card` | `bind:tap` | `goClubDetail` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E1939 | `pages/talent/list/index.wxml:79` | `cy-club-card` | `bind:action` | `onClubCardAction` | 1 | write | event-contract | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E1940 | `pages/talent/list/index.wxml:95` | `cy-club-card` | `bind:tap` | `goClubDetail` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E1941 | `pages/talent/list/index.wxml:95` | `cy-club-card` | `bind:action` | `onClubCardAction` | 1 | write | event-contract | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E1942 | `pages/talent/list/index.wxml:97` | `cy-empty` | `bindcta` | `onClubApply` | 1 | navigation | event-contract | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E1943 | `pages/talent/list/index.wxml:101` | `cy-sheet` | `bind:close` | `closeClubManage` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E1944 | `pages/talent/list/index.wxml:110` | `view` | `catchtap` | `removeClubMember` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,modal-cancelled,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E1945 | `pages/team/detail/index.wxml:5` | `view` | `bindtap` | `load` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | — | PENDING |
| E1946 | `pages/team/detail/index.wxml:19` | `view` | `bindtap` | `goJoin` | 1 | navigation | tap | error,success | NO | PASS | — | PENDING |
| E1947 | `pages/team/detail/index.wxml:30` | `view` | `bindtap` | `kick` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,modal-cancelled,success,timeout | NO | PASS | — | PENDING |
| E1948 | `pages/team/detail/index.wxml:35` | `view` | `bindtap` | `quit` | 1 | write | tap | duplicate-trigger,error,modal-cancelled,success,timeout | NO | PASS | — | PENDING |
| E1949 | `pages/team/detail/index.wxml:36` | `view` | `bindtap` | `disband` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,modal-cancelled,success,timeout | NO | PASS | — | PENDING |
| E1950 | `pages/team/join/index.wxml:5` | `view` | `bindtap` | `load` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | — | PENDING |
| E1951 | `pages/team/join/index.wxml:20` | `view` | `bindtap` | `goDetail` | 1 | navigation | tap | error,success | NO | PASS | — | PENDING |
| E1952 | `pages/team/join/index.wxml:21` | `view` | `bindtap` | `join` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | — | PENDING |
| E1953 | `pages/template/index.wxml:8` | `image` | `bindtap` | `goMine` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782,#780 | PENDING |
| E1954 | `pages/template/index.wxml:10` | `image` | `bindtap` | `goInbox` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782,#780 | PENDING |
| E1955 | `pages/template/index.wxml:15` | `view` | `bindtap` | `goSearch` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782,#780 | PENDING |
| E1956 | `pages/template/index.wxml:22` | `view` | `bindtap` | `switchCat` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782,#780 | PENDING |
| E1957 | `pages/template/index.wxml:27` | `view` | `bindtap` | `switchCat` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782,#780 | PENDING |
| E1958 | `pages/template/index.wxml:40` | `cy-error` | `bindretry` | `retryTemplateLoad` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782,#780 | PENDING |
| E1959 | `pages/template/index.wxml:41` | `cy-empty` | `bindcta` | `retryTemplateLoad` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782,#780 | PENDING |
| E1960 | `pages/template/index.wxml:49` | `view` | `bindtap` | `jumpDetail` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780 | PENDING |
| E1961 | `pages/template/index.wxml:54` | `image` | `binderror` | `onCoverError` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780 | PENDING |
| E1962 | `pages/template/index.wxml:80` | `view` | `bindtap` | `jumpDetail` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780 | PENDING |
| E1963 | `pages/template/index.wxml:82` | `image` | `binderror` | `onCoverError` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780 | PENDING |
| E1964 | `pages/template/index.wxml:119` | `view` | `bindtap` | `useTt` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782,#780 | PENDING |
| E1965 | `pages/template/index.wxml:131` | `view` | `bindtap` | `jumpDetail` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780 | PENDING |
| E1966 | `pages/template/index.wxml:151` | `view` | `bindtap` | `jumpDetail` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780 | PENDING |
| E1967 | `pages/template/index.wxml:182` | `view` | `bindtap` | `jumpDetail` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780 | PENDING |
| E1968 | `pages/template/index.wxml:219` | `text` | `bindtap` | `closeTemplate` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780 | PENDING |
| E1969 | `pages/template/index.wxml:220` | `text` | `bindtap` | `closeTemplate` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780 | PENDING |
| E1970 | `pages/template/index.wxml:226` | `image` | `binderror` | `onDetailCoverError` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780 | PENDING |
| E1971 | `pages/template/index.wxml:277` | `view` | `bindtap` | `closeTemplate` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780 | PENDING |
| E1972 | `pages/template/index.wxml:278` | `view` | `bindtap` | `goTemplateInfo` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782,#780 | PENDING |
| E1973 | `pages/template/index.wxml:284` | `view` | `bindtap` | `openPublishSheet` | 1 | state | tap | success | NO | PASS | #787,#783,#782,#780 | PENDING |
| E1974 | `pages/template/index.wxml:288` | `cy-publish-sheet` | `bind:close` | `closePublishSheet` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782,#780 | PENDING |
| E1975 | `pages/templatedetail/templatedetail.wxml:12` | `cy-nav-bar` | `bind:back` | `goBack` | 1 | navigation | event-contract | error,success | NO | PASS | #787 | PENDING |
| E1976 | `pages/templatedetail/templatedetail.wxml:18` | `cy-error` | `bind:retry` | `onRetry` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787 | PENDING |
| E1977 | `pages/templatedetail/templatedetail.wxml:45` | `cy-btn` | `bindtap` | `goPrimary` | 1 | navigation | tap | error,success | NO | PASS | #787 | PENDING |
| E1978 | `pages/templatedetail/templatedetail.wxml:46` | `view` | `bindtap` | `openMore` | 1 | state | tap | success | NO | PASS | #787 | PENDING |
| E1979 | `pages/templatedetail/templatedetail.wxml:53` | `cy-tabs` | `bind:change` | `switchTab` | 1 | state | event-contract | success | NO | PASS | #787 | PENDING |
| E1980 | `pages/templatedetail/templatedetail.wxml:65` | `view` | `bindtap` | `previewGallery` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #787 | PENDING |
| E1981 | `pages/templatedetail/templatedetail.wxml:75` | `view` | `bindtap` | `toggleDescMore` | 1 | state | tap | success | NO | PASS | #787 | PENDING |
| E1982 | `pages/templatedetail/templatedetail.wxml:85` | `view` | `bindtap` | `toggleRuleMore` | 1 | state | tap | success | NO | PASS | #787 | PENDING |
| E1983 | `pages/templatedetail/templatedetail.wxml:145` | `view` | `bindtap` | `openPop` | 1 | state | tap | success | NO | PASS | #787 | PENDING |
| E1984 | `pages/templatedetail/templatedetail.wxml:189` | `cy-sheet` | `bind:close` | `closeMore` | 1 | state | event-contract | success | NO | PASS | #787 | PENDING |
| E1985 | `pages/templatedetail/templatedetail.wxml:190` | `view` | `bindtap` | `onShare` | 1 | state | tap | success | NO | PASS | #787 | PENDING |
| E1986 | `pages/templatedetail/templatedetail.wxml:191` | `view` | `bindtap` | `openPopFromMore` | 1 | state | tap | success | NO | PASS | #787 | PENDING |
| E1987 | `pages/templatedetail/templatedetail.wxml:192` | `view` | `bindtap` | `closeMore` | 1 | state | tap | success | NO | PASS | #787 | PENDING |
| E1988 | `pages/templatedetail/templatedetail.wxml:196` | `cy-sheet` | `bind:close` | `closePop` | 1 | state | event-contract | success | NO | PASS | #787 | PENDING |
| E1989 | `pages/templatedetail/templatedetail.wxml:199` | `cy-btn` | `bindtap` | `closePop` | 1 | state | tap | success | NO | PASS | #787 | PENDING |
| E1990 | `pages/templatedetail/templatedetail.wxml:201` | `cy-btn` | `bindtap` | `closePop` | 1 | state | tap | success | NO | PASS | #787 | PENDING |
| E1991 | `pages/templatedetail/templatedetail.wxml:210` | `input` | `bindinput` | `onInputAnswer` | 1 | state | event-contract | success | NO | PASS | #787 | PENDING |
| E1992 | `pages/templatedetail/templatedetail.wxml:213` | `cy-btn` | `bindtap` | `validateAnswer` | 1 | other | tap | error,success | NO | PASS | #787 | PENDING |
| E1993 | `pages/templatedetail/templatedetail.wxml:213` | `cy-btn` | `bind:disabledtap` | `validateAnswer` | 1 | other | event-contract | error,success | NO | PASS | #787 | PENDING |
| E1994 | `pages/templatedetail/templatedetail.wxml:221` | `view` | `bindtap` | `onSelectOption` | 1 | state | tap | success | NO | PASS | #787 | PENDING |
| E1995 | `pages/templatedetail/templatedetail.wxml:223` | `view` | `bindtap` | `onSelectOption` | 1 | state | tap | success | NO | PASS | #787 | PENDING |
| E1996 | `pages/templatedetail/templatedetail.wxml:225` | `view` | `bindtap` | `onSelectOption` | 1 | state | tap | success | NO | PASS | #787 | PENDING |
| E1997 | `pages/templatedetail/templatedetail.wxml:227` | `view` | `bindtap` | `onSelectOption` | 1 | state | tap | success | NO | PASS | #787 | PENDING |
| E1998 | `pages/templatedetail/templatedetail.wxml:229` | `cy-btn` | `bindtap` | `validateAnswer` | 1 | other | tap | error,success | NO | PASS | #787 | PENDING |
| E1999 | `pages/templatedetail/templatedetail.wxml:229` | `cy-btn` | `bind:disabledtap` | `validateAnswer` | 1 | other | event-contract | error,success | NO | PASS | #787 | PENDING |
| E2000 | `pages/topic/components/project-drawer/index.wxml:6` | `view` | `bindtap` | `onClose` | 1 | other | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E2001 | `pages/topic/components/project-drawer/index.wxml:11` | `text` | `catchtap` | `onClose` | 1 | other | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E2002 | `pages/topic/components/project-drawer/index.wxml:13` | `text` | `catchtap` | `onClose` | 1 | other | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E2003 | `pages/topic/components/project-drawer/index.wxml:17` | `view` | `catchtap` | `onClose` | 1 | other | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E2004 | `pages/topic/components/project-drawer/index.wxml:29` | `view` | `catchtap` | `onAction` | 1 | other | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E2005 | `pages/topic/components/project-host/index.wxml:11` | `cy-btn` | `bindtap` | `emit` | 1 | other | tap | error,success | NO | PASS | #787,#783,#780 | PENDING |
| E2006 | `pages/topic/components/project-host/index.wxml:43` | `view` | `bindtap` | `emit` | 1 | other | tap | error,success | NO | PASS | #787,#783,#780 | PENDING |
| E2007 | `pages/topic/components/project-host/index.wxml:53` | `view` | `bindtap` | `emit` | 1 | other | tap | error,success | NO | PASS | #787,#783,#780 | PENDING |
| E2008 | `pages/topic/components/project-host/index.wxml:76` | `view` | `bindtap` | `emit` | 1 | other | tap | error,success | NO | PASS | #787,#783,#780 | PENDING |
| E2009 | `pages/topic/components/project-host/index.wxml:86` | `cy-btn` | `bindtap` | `emit` | 1 | other | tap | error,success | NO | PASS | #787,#783,#780 | PENDING |
| E2010 | `pages/topic/components/project-host/index.wxml:93` | `project-drawer` | `bind:close` | `emitCloseClub` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#780 | PENDING |
| E2011 | `pages/topic/components/project-host/index.wxml:93` | `project-drawer` | `bind:action` | `emitDrawerAction` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#780 | PENDING |
| E2012 | `pages/topic/components/project-host/index.wxml:108` | `view` | `catchtap` | `emit` | 1 | other | tap | error,success | NO | PASS | #787,#783,#780 | PENDING |
| E2013 | `pages/topic/components/project-host/index.wxml:120` | `project-drawer` | `bind:close` | `emitCloseMerchant` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#780 | PENDING |
| E2014 | `pages/topic/components/project-host/index.wxml:120` | `project-drawer` | `bind:action` | `emitDrawerAction` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#780 | PENDING |
| E2015 | `pages/topic/components/project-host/index.wxml:141` | `view` | `catchtap` | `emit` | 1 | other | tap | error,success | NO | PASS | #787,#783,#780 | PENDING |
| E2016 | `pages/topic/components/project-host/index.wxml:154` | `view` | `catchtap` | `emit` | 1 | other | tap | error,success | NO | PASS | #787,#783,#780 | PENDING |
| E2017 | `pages/topic/components/project-host/index.wxml:163` | `project-drawer` | `bind:close` | `emitCloseEdit` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#780 | PENDING |
| E2018 | `pages/topic/components/project-host/index.wxml:166` | `view` | `catchtap` | `emit` | 1 | other | tap | error,success | NO | PASS | #787,#783,#780 | PENDING |
| E2019 | `pages/topic/components/project-host/index.wxml:178` | `project-drawer` | `bind:close` | `emitClosePlayer` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#780 | PENDING |
| E2020 | `pages/topic/components/project-host/index.wxml:178` | `project-drawer` | `bind:action` | `emitDrawerAction` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#780 | PENDING |
| E2021 | `pages/topic/components/project-host/index.wxml:192` | `view` | `catchtap` | `emit` | 1 | other | tap | error,success | NO | PASS | #787,#783,#780 | PENDING |
| E2022 | `pages/topic/components/project-host/index.wxml:213` | `view` | `catchtap` | `emit` | 1 | other | tap | error,success | NO | PASS | #787,#783,#780 | PENDING |
| E2023 | `pages/topic/components/project-join/index.wxml:13` | `cy-btn` | `bindtap` | `emit` | 1 | other | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E2024 | `pages/topic/components/project-join/index.wxml:24` | `view` | `bindtap` | `onSwitchStation` | 1 | other | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E2025 | `pages/topic/components/project-join/index.wxml:30` | `image` | `binderror` | `emitHeroError` | 1 | other | event-contract | error,success | NO | PASS | #787,#783 | PENDING |
| E2026 | `pages/topic/components/project-join/index.wxml:76` | `view` | `bindtap` | `emit` | 1 | other | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E2027 | `pages/topic/components/project-join/index.wxml:86` | `view` | `bindtap` | `emit` | 1 | other | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E2028 | `pages/topic/components/project-join/index.wxml:107` | `cy-btn` | `bindtap` | `emit` | 1 | other | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E2029 | `pages/topic/components/project-join/index.wxml:113` | `view` | `bindtap` | `emit` | 1 | other | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E2030 | `pages/topic/components/project-join/index.wxml:133` | `cy-btn` | `catchtap` | `emit` | 1 | other | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E2031 | `pages/topic/components/project-join/index.wxml:142` | `view` | `bindtap` | `emit` | 1 | other | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E2032 | `pages/topic/components/project-join/index.wxml:165` | `view` | `catchtap` | `emit` | 1 | other | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E2033 | `pages/topic/components/project-join/index.wxml:193` | `view` | `catchtap` | `emit` | 1 | other | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E2034 | `pages/topic/components/project-join/index.wxml:248` | `view` | `bindtap` | `emit` | 1 | other | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E2035 | `pages/topic/components/project-join/index.wxml:262` | `cy-btn` | `bindtap` | `emit` | 1 | other | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E2036 | `pages/topic/components/project-join/index.wxml:271` | `project-drawer` | `bind:close` | `emitCloseClub` | 1 | other | event-contract | error,success | NO | PASS | #787,#783 | PENDING |
| E2037 | `pages/topic/components/project-join/index.wxml:271` | `project-drawer` | `bind:action` | `emitDrawerAction` | 1 | other | event-contract | error,success | NO | PASS | #787,#783 | PENDING |
| E2038 | `pages/topic/components/project-join/index.wxml:286` | `view` | `catchtap` | `emit` | 1 | other | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E2039 | `pages/topic/components/project-join/index.wxml:298` | `project-drawer` | `bind:close` | `emitClosePlayer` | 1 | other | event-contract | error,success | NO | PASS | #787,#783 | PENDING |
| E2040 | `pages/topic/components/project-join/index.wxml:298` | `project-drawer` | `bind:action` | `emitDrawerAction` | 1 | other | event-contract | error,success | NO | PASS | #787,#783 | PENDING |
| E2041 | `pages/topic/components/project-join/index.wxml:312` | `view` | `catchtap` | `emit` | 1 | other | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E2042 | `pages/topic/components/project-join/index.wxml:333` | `view` | `catchtap` | `emit` | 1 | other | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E2043 | `pages/topic/index/index.wxml:3` | `view` | `bindtouchstart` | `touchStart` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E2044 | `pages/topic/index/index.wxml:3` | `view` | `bindtouchmove` | `touchMove` | 1 | other | event-contract | error,success | NO | PASS | #787,#783 | PENDING |
| E2045 | `pages/topic/index/index.wxml:3` | `view` | `bindtouchend` | `touchEnd` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E2046 | `pages/topic/index/index.wxml:6` | `cy-nav-bar` | `bind:back` | `goBack` | 1 | navigation | event-contract | error,success | NO | PASS | #787,#783 | PENDING |
| E2047 | `pages/topic/index/index.wxml:11` | `button` | `bindtap` | `goBack` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E2048 | `pages/topic/index/index.wxml:22` | `cy-empty` | `bind:cta` | `goTopicList` | 1 | navigation | event-contract | error,success | NO | PASS | #787,#783 | PENDING |
| E2049 | `pages/topic/index/index.wxml:23` | `cy-error` | `bind:retry` | `onRetryLoad` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783 | PENDING |
| E2050 | `pages/topic/index/index.wxml:38` | `button` | `bindtap` | `goBack` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E2051 | `pages/topic/index/index.wxml:45` | `cy-tabs` | `bind:change` | `switchTab` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E2052 | `pages/topic/index/index.wxml:51` | `view` | `bindtap` | `scrollToSecond` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E2053 | `pages/topic/index/index.wxml:74` | `cy-btn` | `bindtap` | `onGraduateBeta` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,modal-cancelled,success,timeout | NO | PASS | #787,#783 | PENDING |
| E2054 | `pages/topic/index/index.wxml:94` | `cy-btn` | `bindtap` | `onTransferToClub` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783 | PENDING |
| E2055 | `pages/topic/index/index.wxml:127` | `cy-tabs` | `bind:change` | `switchTab` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E2056 | `pages/topic/index/index.wxml:142` | `view` | `bindtap` | `goLocation` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #787,#783 | PENDING |
| E2057 | `pages/topic/index/index.wxml:155` | `view` | `bindtap` | `toggleTopicAudio` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #787,#783 | PENDING |
| E2058 | `pages/topic/index/index.wxml:178` | `view` | `bindtap` | `goTemplateDetail` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E2059 | `pages/topic/index/index.wxml:224` | `view` | `bindtap` | `bmClick` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E2060 | `pages/topic/index/index.wxml:242` | `view` | `bindtap` | `davidzkbut` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E2061 | `pages/topic/index/index.wxml:253` | `view` | `bindtap` | `goUserInfoClcik` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E2062 | `pages/topic/index/index.wxml:281` | `view` | `bindtap` | `goUserInfoClcik` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E2063 | `pages/topic/index/index.wxml:316` | `cy-btn` | `bindtap` | `voteClick` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E2064 | `pages/topic/index/index.wxml:340` | `view` | `bindtap` | `goPoint` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E2065 | `pages/topic/index/index.wxml:359` | `cy-btn` | `bindtap` | `lockedTip` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E2066 | `pages/topic/index/index.wxml:363` | `cy-btn` | `bindtap` | `bmClick` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E2067 | `pages/topic/index/index.wxml:364` | `cy-btn` | `bindtap` | `selfPlayBuy` | 1 | navigation | tap | error,modal-cancelled,success | NO | PASS | #787,#783 | PENDING |
| E2068 | `pages/topic/index/index.wxml:366` | `cy-btn` | `bindtap` | `bmClick` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E2069 | `pages/topic/index/index.wxml:393` | `map` | `bindmarkertap` | `onNodeTap` | 1 | other | event-contract | error,success | NO | PASS | #787,#783 | PENDING |
| E2070 | `pages/topic/index/index.wxml:428` | `view` | `bindtap` | `goTemplateDetail` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E2071 | `pages/topic/index/index.wxml:458` | `view` | `bindtap` | `goLocation` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #787,#783 | PENDING |
| E2072 | `pages/topic/index/index.wxml:502` | `cy-sheet` | `bind:close` | `voteClose` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E2073 | `pages/topic/index/index.wxml:508` | `image` | `bindtap` | `changePic` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E2074 | `pages/topic/index/index.wxml:514` | `textarea` | `bindinput` | `onCommentInput` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E2075 | `pages/topic/index/index.wxml:519` | `image` | `bindtap` | `previewImage` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #787,#783 | PENDING |
| E2076 | `pages/topic/index/index.wxml:520` | `view` | `bindtap` | `deleteImage` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E2077 | `pages/topic/index/index.wxml:525` | `view` | `bindtap` | `upLoadImg` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E2078 | `pages/topic/index/index.wxml:535` | `cy-btn` | `bindtap` | `submitComment` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783 | PENDING |
| E2079 | `pages/topic/merchantapply/index.wxml:19` | `cy-sheet` | `bind:close` | `onSheetClose` | 1 | navigation | event-contract | error,success | NO | PASS | #783 | PENDING |
| E2080 | `pages/topic/merchantapply/index.wxml:20` | `cy-sheet` | `bind:requestclose` | `onSheetRequestClose` | 1 | navigation | event-contract | error,success | NO | PASS | #783 | PENDING |
| E2081 | `pages/topic/merchantapply/index.wxml:27` | `view` | `bindtap` | `choosePoiForNode` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E2082 | `pages/topic/merchantapply/index.wxml:35` | `view` | `bindtap` | `handleTimePopup` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E2083 | `pages/topic/merchantapply/index.wxml:47` | `input` | `bindinput` | `onLimitNumChange` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E2084 | `pages/topic/merchantapply/index.wxml:47` | `input` | `bindfocus` | `onFieldFocus` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E2085 | `pages/topic/merchantapply/index.wxml:47` | `input` | `bindblur` | `onFieldBlur` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E2086 | `pages/topic/merchantapply/index.wxml:58` | `textarea` | `bindinput` | `onRuleInstructionsChange` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E2087 | `pages/topic/merchantapply/index.wxml:58` | `textarea` | `bindfocus` | `onFieldFocus` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E2088 | `pages/topic/merchantapply/index.wxml:58` | `textarea` | `bindblur` | `onFieldBlur` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E2089 | `pages/topic/merchantapply/index.wxml:72` | `view` | `catchtap` | `removePic` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E2090 | `pages/topic/merchantapply/index.wxml:74` | `view` | `bindtap` | `uploadPic` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E2091 | `pages/topic/merchantapply/index.wxml:95` | `cy-btn` | `bindtap` | `onSheetClose` | 1 | navigation | tap | error,success | NO | PASS | #783 | PENDING |
| E2092 | `pages/topic/merchantapply/index.wxml:96` | `cy-btn` | `bindtap` | `onSubmit` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783 | PENDING |
| E2093 | `pages/topic/merchantapply/index.wxml:99` | `cy-btn` | `bindtap` | `goHome` | 1 | navigation | tap | error,success | NO | PASS | #783 | PENDING |
| E2094 | `pages/topic/merchantapply/index.wxml:100` | `cy-btn` | `bindtap` | `goMore` | 1 | navigation | tap | error,success | NO | PASS | #783 | PENDING |
| E2095 | `pages/topic/merchantapply/index.wxml:106` | `cy-sheet` | `bind:close` | `handleTimePopup` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E2096 | `pages/topic/merchantapply/index.wxml:113` | `picker-view` | `bindchange` | `onStartTimeChange` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E2097 | `pages/topic/merchantapply/index.wxml:127` | `picker-view` | `bindchange` | `onEndTimeChange` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E2098 | `pages/topic/merchantapply/index.wxml:139` | `cy-btn` | `bindtap` | `handleTimePopup` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E2099 | `pages/topic/merchantinfo/merchantinfo.wxml:28` | `project-host` | `bind:act` | `onJoinAct` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#780 | PENDING |
| E2100 | `pages/topic/merchantinfo/merchantinfo.wxml:79` | `project-join` | `bind:act` | `onJoinAct` | 1 | other | event-contract | error,success | NO | PASS | #787,#783,#780 | PENDING |
| E2101 | `pages/topic/merchantinfo/merchantinfo.wxml:85` | `cy-error` | `bind:retry` | `retryBrowseData` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#780 | PENDING |
| E2102 | `pages/topic/merchantinfo/merchantinfo.wxml:88` | `image` | `binderror` | `onCoverError` | 1 | state | event-contract | success | NO | PASS | #787,#783,#780 | PENDING |
| E2103 | `pages/topic/merchantinfo/merchantinfo.wxml:107` | `view` | `bindtap` | `scrollToSecond` | 1 | other | tap | error,success | NO | PASS | #787,#783,#780 | PENDING |
| E2104 | `pages/topic/merchantinfo/merchantinfo.wxml:108` | `view` | `bindtap` | `scrollToSecond` | 1 | other | tap | error,success | NO | PASS | #787,#783,#780 | PENDING |
| E2105 | `pages/topic/merchantinfo/merchantinfo.wxml:116` | `image` | `binderror` | `onDetailCoverError` | 1 | state | event-contract | success | NO | PASS | #787,#783,#780 | PENDING |
| E2106 | `pages/topic/merchantinfo/merchantinfo.wxml:160` | `cy-tabs` | `bind:change` | `switchTab` | 1 | state | event-contract | success | NO | PASS | #787,#783,#780 | PENDING |
| E2107 | `pages/topic/merchantinfo/merchantinfo.wxml:183` | `view` | `bindtap` | `goTemplateDetail` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#780 | PENDING |
| E2108 | `pages/topic/merchantinfo/merchantinfo.wxml:225` | `view` | `bindtap` | `goUserInfoClcik` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#780 | PENDING |
| E2109 | `pages/topic/merchantinfo/merchantinfo.wxml:240` | `cy-btn` | `bindtap` | `bmClick2` | 1 | state | tap | success | NO | PASS | #787,#783,#780 | PENDING |
| E2110 | `pages/topic/merchantinfo/merchantinfo.wxml:302` | `view` | `bindtap` | `goLocation` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #787,#783,#780 | PENDING |
| E2111 | `pages/topic/merchantinfo/merchantinfo.wxml:334` | `view` | `bindtap` | `topicClose` | 1 | state | tap | success | NO | PASS | #787,#783,#780 | PENDING |
| E2112 | `pages/topic/merchantinfo/merchantinfo.wxml:336` | `view` | `bindtap` | `topicClose` | 1 | state | tap | success | NO | PASS | #787,#783,#780 | PENDING |
| E2113 | `pages/topic/merchantinfo/merchantinfo.wxml:340` | `view` | `bindtap` | `topicClose` | 1 | state | tap | success | NO | PASS | #787,#783,#780 | PENDING |
| E2114 | `pages/topic/merchantinfo/merchantinfo.wxml:355` | `view` | `bindtap` | `davidClick` | 1 | state | tap | success | NO | PASS | #787,#783,#780 | PENDING |
| E2115 | `pages/topic/merchantinfo/merchantinfo.wxml:369` | `cy-error` | `bind:retry` | `loadMerchantRecruitmentChapters` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#780 | PENDING |
| E2116 | `pages/topic/merchantinfo/merchantinfo.wxml:372` | `cy-empty` | `bind:cta` | `goMerchantCategory` | 1 | navigation | event-contract | error,success | NO | PASS | #787,#783,#780 | PENDING |
| E2117 | `pages/topic/merchantinfo/merchantinfo.wxml:375` | `view` | `bindtap` | `selectChapter` | 1 | state | tap | success | NO | PASS | #787,#783,#780 | PENDING |
| E2118 | `pages/topic/merchantinfo/merchantinfo.wxml:400` | `cy-btn` | `bindtap` | `openChapterOfferForm` | 1 | state | tap | success | NO | PASS | #787,#783,#780 | PENDING |
| E2119 | `pages/topic/merchantinfo/merchantinfo.wxml:402` | `cy-btn` | `bindtap` | `withdrawChapterApplication` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,modal-cancelled,success,timeout | NO | PASS | #787,#783,#780 | PENDING |
| E2120 | `pages/topic/merchantinfo/merchantinfo.wxml:418` | `view` | `bindtap` | `selectNode` | 1 | state | tap | success | NO | PASS | #787,#783,#780 | PENDING |
| E2121 | `pages/topic/merchantinfo/merchantinfo.wxml:426` | `cy-btn` | `bindtap` | `goSettlement` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#780 | PENDING |
| E2122 | `pages/topic/merchantinfo/merchantinfo.wxml:433` | `view` | `bindtap` | `davidClose` | 1 | state | tap | success | NO | PASS | #787,#783,#780 | PENDING |
| E2123 | `pages/topic/merchantinfo/merchantinfo.wxml:439` | `view` | `bindtap` | `davidClose` | 1 | state | tap | success | NO | PASS | #787,#783,#780 | PENDING |
| E2124 | `pages/topic/merchantinfo/merchantinfo.wxml:487` | `chapter-node-form` | `bind:close` | `closeChapterNodeForm` | 1 | state | event-contract | success | NO | PASS | #787,#783,#780 | PENDING |
| E2125 | `pages/topic/merchantinfo/merchantinfo.wxml:488` | `chapter-node-form` | `bind:submit` | `onChapterNodeFormSubmit` | 2 | write | event-contract | duplicate-trigger,error,first-error,first-timeout,forbidden-owner,second-error-after-first,second-timeout-after-first,success,timeout | NO | PASS | #787,#783,#780 | PENDING |
| E2126 | `pages/topic/pricing/index.wxml:8` | `cy-empty` | `bind:cta` | `goBack` | 1 | navigation | event-contract | error,success | NO | PASS | #782 | PENDING |
| E2127 | `pages/topic/pricing/index.wxml:10` | `cy-error` | `bind:retry` | `preview` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #782 | PENDING |
| E2128 | `pages/topic/pricing/index.wxml:12` | `cy-tabs` | `bind:change` | `chooseType` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #782 | PENDING |
| E2129 | `pages/topic/pricing/index.wxml:22` | `input` | `bindinput` | `onCostInput` | 1 | state | event-contract | success | NO | PASS | #782 | PENDING |
| E2130 | `pages/topic/pricing/index.wxml:26` | `input` | `bindinput` | `onTeamInput` | 1 | state | event-contract | success | NO | PASS | #782 | PENDING |
| E2131 | `pages/topic/pricing/index.wxml:29` | `cy-btn` | `bindtap` | `recalculate` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #782 | PENDING |
| E2132 | `pages/topic/pricing/index.wxml:45` | `slider` | `bindchange` | `onSliderChange` | 1 | state | event-contract | success | NO | PASS | #782 | PENDING |
| E2133 | `pages/topic/pricing/index.wxml:55` | `cy-btn` | `bindtap` | `confirmPrice` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #782 | PENDING |
| E2134 | `pages/topic/pricing/partner/index.wxml:7` | `cy-error` | `bind:retry` | `retryProfile` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #782 | PENDING |
| E2135 | `pages/topic/pricing/partner/index.wxml:21` | `cy-empty` | `bind:cta` | `goBack` | 1 | navigation | event-contract | error,success | NO | PASS | #782 | PENDING |
| E2136 | `pages/topic/pricing/partner/index.wxml:25` | `view` | `bindtap` | `goFullProfile` | 1 | navigation | tap | error,success | NO | PASS | #782 | PENDING |
| E2137 | `pages/topic/pricing/partner/index.wxml:33` | `view` | `bindtap` | `onDetailTap` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #782 | PENDING |
| E2138 | `subpackageA/components/scene-asset-earnings/index.wxml:5` | `cy-error` | `bind:retry` | `refresh` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #782 | PENDING |
| E2139 | `subpackageA/components/scene-asset-earnings/index.wxml:14` | `view` | `bindtap` | `openIncomeDetail` | 1 | navigation | tap | error,success | NO | PASS | #782 | PENDING |
| E2140 | `subpackageA/components/scene-asset-earnings/index.wxml:15` | `view` | `bindtap` | `openWithdraw` | 1 | other | tap | error,success | NO | PASS | #782 | PENDING |
| E2141 | `subpackageA/components/scene-asset-earnings/index.wxml:16` | `view` | `bindtap` | `openWithdrawHistory` | 1 | other | tap | error,success | NO | PASS | #782 | PENDING |
| E2142 | `subpackageA/components/scene-asset-earnings/index.wxml:20` | `cy-cell` | `bind:tap` | `openInvite` | 1 | other | tap | error,success | NO | PASS | #782 | PENDING |
| E2143 | `subpackageA/components/scene-asset-earnings/index.wxml:22` | `cy-empty` | `bind:cta` | `goPublish` | 1 | navigation | event-contract | error,success | NO | PASS | #782 | PENDING |
| E2144 | `subpackageA/components/scene-asset-income-detail/index.wxml:8` | `cy-tabs` | `bind:change` | `onFilterChange` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #782 | PENDING |
| E2145 | `subpackageA/components/scene-asset-income-detail/index.wxml:10` | `cy-scene-merchant-profit` | `bind:close` | `onClubClose` | 1 | other | event-contract | error,success | NO | PASS | #782 | PENDING |
| E2146 | `subpackageA/components/scene-asset-income-detail/index.wxml:35` | `view` | `bindtap` | `loadMore` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #782 | PENDING |
| E2147 | `subpackageA/components/scene-asset-income-detail/index.wxml:39` | `cy-error` | `bind:retry` | `onRetry` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #782 | PENDING |
| E2148 | `subpackageA/components/scene-how-to-play-detail/index.wxml:8` | `cy-empty` | `bind:cta` | `onMissingBack` | 1 | other | event-contract | error,success | NO | PASS | #782 | PENDING |
| E2149 | `subpackageA/components/scene-how-to-play-detail/index.wxml:10` | `cy-error` | `bind:retry` | `retryLoad` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #782 | PENDING |
| E2150 | `subpackageA/pages/assetcenter/earnings/index.wxml:8` | `cy-scene-asset-earnings` | `bind:open` | `openScene` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | — | PENDING |
| E2151 | `subpackageA/pages/assetcenter/earnings/index.wxml:11` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E2152 | `subpackageA/pages/assetcenter/earnings/index.wxml:12` | `cy-scene-sheet` | `bind:requestclose` | `closeScene` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2153 | `subpackageA/pages/assetcenter/earnings/index.wxml:12` | `cy-scene-sheet` | `bind:back` | `closeScene` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2154 | `subpackageA/pages/assetcenter/earnings/index.wxml:13` | `cy-scene-asset-income-detail` | `bind:close` | `closeScene` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2155 | `subpackageA/pages/assetcenter/earnings/index.wxml:16` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E2156 | `subpackageA/pages/assetcenter/earnings/index.wxml:17` | `cy-scene-sheet` | `bind:requestclose` | `closeScene` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2157 | `subpackageA/pages/assetcenter/earnings/index.wxml:17` | `cy-scene-sheet` | `bind:back` | `closeScene` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2158 | `subpackageA/pages/assetcenter/earnings/index.wxml:21` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E2159 | `subpackageA/pages/assetcenter/earnings/index.wxml:22` | `cy-scene-sheet` | `bind:requestclose` | `closeScene` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2160 | `subpackageA/pages/assetcenter/earnings/index.wxml:22` | `cy-scene-sheet` | `bind:back` | `closeScene` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2161 | `subpackageA/pages/assetcenter/earnings/index.wxml:26` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E2162 | `subpackageA/pages/assetcenter/earnings/index.wxml:27` | `cy-scene-sheet` | `bind:requestclose` | `closeScene` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2163 | `subpackageA/pages/assetcenter/earnings/index.wxml:27` | `cy-scene-sheet` | `bind:back` | `closeScene` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2164 | `subpackageA/pages/assetcenter/earnings/index.wxml:28` | `cy-scene-merchant-profit` | `bind:close` | `closeScene` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2165 | `subpackageA/pages/assetcenter/earnings/index.wxml:38` | `cy-sheet` | `bind:requestclose` | `onTxRequestClose` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2166 | `subpackageA/pages/assetcenter/earnings/index.wxml:39` | `cy-sheet` | `bind:close` | `onTxClose` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2167 | `subpackageA/pages/assetcenter/earnings/index.wxml:46` | `view` | `bindtap` | `loadTxBalance` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | — | PENDING |
| E2168 | `subpackageA/pages/assetcenter/earnings/index.wxml:47` | `view` | `bindtap` | `onTxAllWithdrawal` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E2169 | `subpackageA/pages/assetcenter/earnings/index.wxml:54` | `input` | `bindinput` | `onTxAmountInput` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2170 | `subpackageA/pages/assetcenter/earnings/index.wxml:57` | `input` | `bindinput` | `onTxRealnameInput` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2171 | `subpackageA/pages/assetcenter/earnings/index.wxml:58` | `input` | `bindinput` | `onTxBankNameInput` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2172 | `subpackageA/pages/assetcenter/earnings/index.wxml:59` | `input` | `bindinput` | `onTxBankAccountInput` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2173 | `subpackageA/pages/assetcenter/earnings/index.wxml:60` | `input` | `bindinput` | `onTxMobilephoneInput` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2174 | `subpackageA/pages/assetcenter/earnings/index.wxml:64` | `cy-btn` | `bindtap` | `onTxSave` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | — | PENDING |
| E2175 | `subpackageA/pages/infomation/infomation.wxml:5` | `cy-nav-bar` | `bind:back` | `onBack` | 1 | navigation | event-contract | error,success | NO | PASS | #782 | PENDING |
| E2176 | `subpackageA/pages/infomation/infomation.wxml:25` | `cy-btn` | `bindtap` | `goMode` | 1 | navigation | tap | error,success | NO | PASS | #782 | PENDING |
| E2177 | `subpackageA/pages/infomation/infomation.wxml:37` | `cy-error` | `bind:retry` | `loadTemplates` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #782 | PENDING |
| E2178 | `subpackageA/pages/infomation/infomation.wxml:40` | `cy-empty` | `bind:cta` | `goTemplateSquare` | 1 | navigation | event-contract | error,success | NO | PASS | #782 | PENDING |
| E2179 | `subpackageA/pages/infomation/infomation.wxml:42` | `view` | `bindtap` | `goTemplateDetail` | 1 | navigation | tap | error,success | NO | PASS | #782 | PENDING |
| E2180 | `subpackageA/pages/infomation/infomation.wxml:50` | `cy-btn` | `catchtap` | `goTemplateDetail` | 1 | navigation | tap | error,success | NO | PASS | #782 | PENDING |
| E2181 | `subpackageA/pages/infomation/infomation.wxml:54` | `view` | `bindtap` | `goTemplateSquare` | 1 | navigation | tap | error,success | NO | PASS | #782 | PENDING |
| E2182 | `subpackageA/pages/infomation/infomation.wxml:67` | `cy-cell` | `bindtap` | `goToDetail` | 1 | state | tap | success | NO | PASS | #782 | PENDING |
| E2183 | `subpackageA/pages/infomation/infomation.wxml:80` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #782 | PENDING |
| E2184 | `subpackageA/pages/infomation/infomation.wxml:81` | `cy-scene-sheet` | `bind:requestclose` | `closeScene` | 1 | state | event-contract | success | NO | PASS | #782 | PENDING |
| E2185 | `subpackageA/pages/infomation/infomation.wxml:81` | `cy-scene-sheet` | `bind:back` | `closeScene` | 1 | state | event-contract | success | NO | PASS | #782 | PENDING |
| E2186 | `subpackageA/pages/infomation/infomation.wxml:82` | `cy-scene-how-to-play-detail` | `bind:back` | `closeScene` | 1 | state | event-contract | success | NO | PASS | #782 | PENDING |
| E2187 | `subpackageA/pages/infomationdetail/infomationdetail.wxml:6` | `cy-nav-bar` | `bind:back` | `onBack` | 1 | navigation | event-contract | error,success | NO | PASS | #782 | PENDING |
| E2188 | `subpackageA/pages/infomationdetail/infomationdetail.wxml:15` | `cy-empty` | `bind:cta` | `onMissingBack` | 1 | navigation | event-contract | error,success | NO | PASS | #782 | PENDING |
| E2189 | `subpackageA/pages/infomationdetail/infomationdetail.wxml:20` | `cy-empty` | `bind:cta` | `onMissingBack` | 1 | navigation | event-contract | error,success | NO | PASS | #782 | PENDING |
| E2190 | `subpackageA/pages/infomationdetail/infomationdetail.wxml:22` | `cy-error` | `bind:retry` | `retryLoad` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #782 | PENDING |
| E2191 | `subpackageA/pages/myproject/index.wxml:6` | `cy-tabs` | `bind:change` | `switchTypeTab` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783 | PENDING |
| E2192 | `subpackageA/pages/myproject/index.wxml:15` | `cy-error` | `bind:retry` | `loadProjects` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783 | PENDING |
| E2193 | `subpackageA/pages/myproject/index.wxml:20` | `view` | `bindtap` | `switchOwner` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E2194 | `subpackageA/pages/myproject/index.wxml:28` | `text` | `bindtap` | `tapSummary` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E2195 | `subpackageA/pages/myproject/index.wxml:29` | `text` | `bindtap` | `tapSummary` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E2196 | `subpackageA/pages/myproject/index.wxml:30` | `text` | `bindtap` | `tapSummary` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E2197 | `subpackageA/pages/myproject/index.wxml:35` | `view` | `bindtap` | `openStateSheet` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E2198 | `subpackageA/pages/myproject/index.wxml:39` | `view` | `bindtap` | `onCardTap` | 1 | navigation | tap | error,success | NO | PASS | #783 | PENDING |
| E2199 | `subpackageA/pages/myproject/index.wxml:62` | `view` | `catchtap` | `goCandidates` | 1 | navigation | tap | error,success | NO | PASS | #783 | PENDING |
| E2200 | `subpackageA/pages/myproject/index.wxml:63` | `view` | `catchtap` | `toggleStatus` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783 | PENDING |
| E2201 | `subpackageA/pages/myproject/index.wxml:64` | `view` | `catchtap` | `deleteItem` | 2 | write | tap | duplicate-trigger,error,forbidden-owner,modal-cancelled,success,timeout | NO | PASS | #783 | PENDING |
| E2202 | `subpackageA/pages/myproject/index.wxml:71` | `cy-empty` | `bind:cta` | `goCreate` | 1 | navigation | event-contract | error,success | NO | PASS | #783 | PENDING |
| E2203 | `subpackageA/pages/myproject/index.wxml:80` | `view` | `bindtap` | `tplTap` | 1 | navigation | tap | error,success | NO | PASS | #783 | PENDING |
| E2204 | `subpackageA/pages/myproject/index.wxml:97` | `view` | `catchtap` | `tplToggle` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783 | PENDING |
| E2205 | `subpackageA/pages/myproject/index.wxml:98` | `view` | `catchtap` | `tplDelete` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,modal-cancelled,success,timeout | NO | PASS | #783 | PENDING |
| E2206 | `subpackageA/pages/myproject/index.wxml:104` | `cy-error` | `bind:retry` | `retryTemplates` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783 | PENDING |
| E2207 | `subpackageA/pages/myproject/index.wxml:111` | `cy-sheet` | `bind:close` | `closeStateSheet` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E2208 | `subpackageA/pages/myproject/index.wxml:113` | `view` | `bindtap` | `onStatePick` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E2209 | `subpackageB/pages/im/chat/index.wxml:5` | `view` | `bindtap` | `onMore` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E2210 | `subpackageB/pages/im/chat/index.wxml:19` | `view` | `bindtap` | `loadMore` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E2211 | `subpackageB/pages/im/chat/index.wxml:24` | `cy-error` | `bind:retry` | `retryLoad` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E2212 | `subpackageB/pages/im/chat/index.wxml:39` | `cy-card` | `catchtap` | `onLocationTap` | 1 | external | tap | duplicate-trigger,error,success,timeout | YES | PASS | #787,#783,#782 | PENDING |
| E2213 | `subpackageB/pages/im/chat/index.wxml:53` | `cy-card` | `catchtap` | `onRouteTap` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E2214 | `subpackageB/pages/im/chat/index.wxml:83` | `cy-card` | `bindtap` | `onCardTap` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E2215 | `subpackageB/pages/im/chat/index.wxml:96` | `view` | `catchtap` | `onCardBtn` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E2216 | `subpackageB/pages/im/chat/index.wxml:99` | `view` | `catchtap` | `onCardTap` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E2217 | `subpackageB/pages/im/chat/index.wxml:113` | `view` | `catchtap` | `onResend` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E2218 | `subpackageB/pages/im/chat/index.wxml:143` | `view` | `bindtap` | `onPlus` | 1 | external | tap | cancelled,denied,duplicate-trigger,error,success,timeout | YES | PASS | #787,#783,#782 | PENDING |
| E2219 | `subpackageB/pages/im/chat/index.wxml:146` | `input` | `bindinput` | `onInput` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E2220 | `subpackageB/pages/im/chat/index.wxml:146` | `input` | `bindconfirm` | `onSend` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E2221 | `subpackageB/pages/im/chat/index.wxml:147` | `view` | `bindtap` | `onSend` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E2222 | `subpackageB/pages/im/chat/index.wxml:156` | `cy-sheet` | `bind:close` | `closeRoutePicker` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E2223 | `subpackageB/pages/im/chat/index.wxml:157` | `cy-search` | `bind:input` | `onRouteSearch` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E2224 | `subpackageB/pages/im/chat/index.wxml:163` | `view` | `bindtap` | `onRoutePick` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E2225 | `subpackageB/pages/im/list/index.wxml:6` | `cy-nav-bar` | `bind:back` | `onBack` | 1 | navigation | event-contract | error,success | NO | PASS | #783,#782 | PENDING |
| E2226 | `subpackageB/pages/im/list/index.wxml:10` | `view` | `bindtap` | `onReadAll` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #783,#782 | PENDING |
| E2227 | `subpackageB/pages/im/list/index.wxml:15` | `cy-tabs` | `bind:change` | `onTabChange` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E2228 | `subpackageB/pages/im/list/index.wxml:19` | `cy-cell` | `bind:tap` | `goCoopPool` | 1 | navigation | tap | error,success | NO | PASS | #783,#782 | PENDING |
| E2229 | `subpackageB/pages/im/list/index.wxml:25` | `cy-error` | `bind:retry` | `loadConversations` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783,#782 | PENDING |
| E2230 | `subpackageB/pages/im/list/index.wxml:34` | `cy-empty` | `bindcta` | `onEmptyCta` | 1 | navigation | event-contract | error,success | NO | PASS | #783,#782 | PENDING |
| E2231 | `subpackageB/pages/im/list/index.wxml:41` | `view` | `catchtap` | `onRowRead` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #783,#782 | PENDING |
| E2232 | `subpackageB/pages/im/list/index.wxml:44` | `view` | `catchtap` | `onRowMute` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #783,#782 | PENDING |
| E2233 | `subpackageB/pages/im/list/index.wxml:47` | `view` | `catchtap` | `onRowDelete` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,modal-cancelled,success,timeout | NO | PASS | #783,#782 | PENDING |
| E2234 | `subpackageB/pages/im/list/index.wxml:56` | `view` | `bindtouchstart` | `onTouchStart` | 1 | other | event-contract | error,success | NO | PASS | #783,#782 | PENDING |
| E2235 | `subpackageB/pages/im/list/index.wxml:56` | `view` | `bindtouchmove` | `onTouchMove` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E2236 | `subpackageB/pages/im/list/index.wxml:56` | `view` | `bindtouchend` | `onTouchEnd` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E2237 | `subpackageB/pages/im/list/index.wxml:57` | `view` | `bindtouchcancel` | `onTouchEnd` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E2238 | `subpackageB/pages/im/list/index.wxml:58` | `view` | `bindtap` | `openChat` | 1 | navigation | tap | error,success | NO | PASS | #783,#782 | PENDING |
| E2239 | `subpackageMember/complaint/index.wxml:9` | `cy-nav-bar` | `bind:back` | `onBack` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E2240 | `subpackageMember/complaint/index.wxml:24` | `cy-error` | `bind:retry` | `onRetry` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | — | PENDING |
| E2241 | `subpackageMember/complaint/index.wxml:36` | `view` | `bindtap` | `openPickerSheet` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E2242 | `subpackageMember/complaint/index.wxml:44` | `view` | `bindtap` | `openPickerSheet` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E2243 | `subpackageMember/complaint/index.wxml:52` | `textarea` | `bindinput` | `onReasonInput` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2244 | `subpackageMember/complaint/index.wxml:57` | `input` | `bindinput` | `onContactInput` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2245 | `subpackageMember/complaint/index.wxml:60` | `cy-btn` | `bindtap` | `submit` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | — | PENDING |
| E2246 | `subpackageMember/complaint/index.wxml:65` | `cy-sheet` | `bind:close` | `closePickerSheet` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2247 | `subpackageMember/complaint/index.wxml:67` | `view` | `bindtap` | `selectPickerOption` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E2248 | `subpackageMember/complaint/index.wxml:69` | `cy-sheet` | `bind:close` | `closePickerSheet` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2249 | `subpackageMember/complaint/index.wxml:71` | `view` | `bindtap` | `selectPickerOption` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E2250 | `subpackageMember/components/scene-member-participation-detail/index.wxml:14` | `cy-error` | `bind:retry` | `onErrorAction` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783 | PENDING |
| E2251 | `subpackageMember/components/scene-member-participation-detail/index.wxml:81` | `view` | `bindtap` | `goInfo` | 1 | navigation | tap | error,success | NO | PASS | #783 | PENDING |
| E2252 | `subpackageMember/components/scene-member-participation-detail/index.wxml:109` | `view` | `bindtap` | `goScanQR` | 1 | external | tap | cancelled,denied,duplicate-trigger,error,forbidden-owner,success,timeout | YES | PASS | #783 | PENDING |
| E2253 | `subpackageMember/components/scene-member-participation-detail/index.wxml:119` | `view` | `bindtap` | `cancelParticipation` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,modal-cancelled,success,timeout | NO | PASS | #783 | PENDING |
| E2254 | `subpackageMember/components/scene-member-participation-detail/index.wxml:122` | `view` | `bindtap` | `goModify` | 1 | navigation | tap | error,success | NO | PASS | #783 | PENDING |
| E2255 | `subpackageMember/components/scene-member-participation-detail/index.wxml:129` | `button` | `bindcontact` | `handleContact` | 1 | other | event-contract | error,success | NO | PASS | #783 | PENDING |
| E2256 | `subpackageMember/coupon-qr/index.wxml:25` | `cy-nav-bar` | `bind:back` | `onClose` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E2257 | `subpackageMember/coupon-qr/index.wxml:39` | `cy-qr-voucher` | `bind:close` | `onClose` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E2258 | `subpackageMember/coupon-qr/index.wxml:40` | `cy-qr-voucher` | `bind:retry` | `refreshToken` | 1 | external | event-contract | duplicate-trigger,error,success,timeout | YES | PASS | — | PENDING |
| E2259 | `subpackageMember/coupon-qr/index.wxml:41` | `cy-qr-voucher` | `bind:secondary` | `refreshToken` | 1 | external | event-contract | duplicate-trigger,error,success,timeout | YES | PASS | — | PENDING |
| E2260 | `subpackageMember/coupon/coupon.wxml:6` | `cy-error` | `bind:retry` | `retryLoad` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#782 | PENDING |
| E2261 | `subpackageMember/coupon/coupon.wxml:9` | `view` | `bindtap` | `tpClick` | 1 | state | tap | success | NO | PASS | #787,#782 | PENDING |
| E2262 | `subpackageMember/coupon/coupon.wxml:46` | `cy-btn` | `bindtap` | `GoAdd` | 1 | navigation | tap | error,success | NO | PASS | #787,#782 | PENDING |
| E2263 | `subpackageMember/coupon/coupon.wxml:84` | `view` | `bindtap` | `tpClose` | 1 | state | tap | success | NO | PASS | #787,#782 | PENDING |
| E2264 | `subpackageMember/couponInfo/couponInfo.wxml:11` | `input` | `bindinput` | `onNameInput` | 1 | write | event-contract | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | — | PENDING |
| E2265 | `subpackageMember/couponInfo/couponInfo.wxml:20` | `view` | `bindtap` | `showStartTimePicker` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E2266 | `subpackageMember/couponInfo/couponInfo.wxml:42` | `input` | `bindinput` | `onPublishCountInput` | 1 | write | event-contract | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | — | PENDING |
| E2267 | `subpackageMember/couponInfo/couponInfo.wxml:51` | `view` | `bindtap` | `openCouponTypeSheet` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E2268 | `subpackageMember/couponInfo/couponInfo.wxml:64` | `textarea` | `bindinput` | `onDescriptionInput` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2269 | `subpackageMember/couponInfo/couponInfo.wxml:72` | `cy-btn` | `bindtap` | `onCancel` | 1 | navigation | tap | error,success | NO | PASS | — | PENDING |
| E2270 | `subpackageMember/couponInfo/couponInfo.wxml:73` | `cy-btn` | `bindtap` | `onSubmit` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | — | PENDING |
| E2271 | `subpackageMember/couponInfo/couponInfo.wxml:77` | `cy-sheet` | `bind:close` | `closeCouponTypeSheet` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2272 | `subpackageMember/couponInfo/couponInfo.wxml:79` | `view` | `bindtap` | `selectCouponType` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | — | PENDING |
| E2273 | `subpackageMember/couponInfo/couponInfo.wxml:84` | `cy-date-sheet` | `bind:cancel` | `closeStartTimePicker` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2274 | `subpackageMember/couponInfo/couponInfo.wxml:84` | `cy-date-sheet` | `bind:confirm` | `confirmStartTime` | 1 | write | event-contract | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | — | PENDING |
| E2275 | `subpackageMember/couponInfo/couponInfo.wxml:89` | `picker-view` | `bindchange` | `onStartDateChange` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2276 | `subpackageMember/couponInfo/couponInfo.wxml:100` | `picker-view` | `bindchange` | `onStartTimeChange` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2277 | `subpackageMember/couponInfo/couponInfo.wxml:115` | `cy-date-sheet` | `bind:cancel` | `closeEndTimePicker` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2278 | `subpackageMember/couponInfo/couponInfo.wxml:115` | `cy-date-sheet` | `bind:confirm` | `confirmEndTime` | 1 | write | event-contract | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | — | PENDING |
| E2279 | `subpackageMember/couponInfo/couponInfo.wxml:120` | `picker-view` | `bindchange` | `onEndDateChange` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2280 | `subpackageMember/couponInfo/couponInfo.wxml:131` | `picker-view` | `bindchange` | `onEndTimeChange` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2281 | `subpackageMember/mycanyu/mycanyu.wxml:4` | `cy-nav-bar` | `bind:back` | `onBack` | 1 | navigation | event-contract | error,success | NO | PASS | #783,#782 | PENDING |
| E2282 | `subpackageMember/mycanyu/mycanyu.wxml:7` | `cy-tabs` | `bind:change` | `switchTab` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E2283 | `subpackageMember/mycanyu/mycanyu.wxml:11` | `cy-error` | `bind:retry` | `onRetry` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783,#782 | PENDING |
| E2284 | `subpackageMember/mycanyu/mycanyu.wxml:14` | `view` | `bindtap` | `goOrder` | 1 | state | tap | success | NO | PASS | #783,#782 | PENDING |
| E2285 | `subpackageMember/mycanyu/mycanyu.wxml:38` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | #783,#782 | PENDING |
| E2286 | `subpackageMember/mycanyu/mycanyu.wxml:39` | `cy-scene-sheet` | `bind:requestclose` | `closeScene` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E2287 | `subpackageMember/mycanyu/mycanyu.wxml:39` | `cy-scene-sheet` | `bind:back` | `closeScene` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E2288 | `subpackageMember/mycanyu/mycanyu.wxml:40` | `cy-scene-member-participation-detail` | `bind:back` | `closeScene` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E2289 | `subpackageMember/mycanyu/mycanyu.wxml:40` | `cy-scene-member-participation-detail` | `bind:close` | `closeScene` | 1 | state | event-contract | success | NO | PASS | #783,#782 | PENDING |
| E2290 | `subpackageMember/mycanyu/mycanyu.wxml:40` | `cy-scene-member-participation-detail` | `bind:refresh` | `onRetry` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783,#782 | PENDING |
| E2291 | `subpackageMember/mycanyuinfo/mycanyuinfo.wxml:7` | `cy-scene-member-participation-detail` | `bind:back` | `backToList` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E2292 | `subpackageMember/mycanyuinfo/mycanyuinfo.wxml:7` | `cy-scene-member-participation-detail` | `bind:close` | `backToList` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E2293 | `subpackageMember/mycanyuinfo/mycanyuinfo.wxml:7` | `cy-scene-member-participation-detail` | `bind:refresh` | `noop` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E2294 | `subpackageMember/myinvite/myinvite.wxml:5` | `cy-sheet` | `bind:close` | `onClose` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E2295 | `subpackageMember/mytemplate/mytemplate.wxml:13` | `view` | `bindtap` | `goInfo` | 1 | navigation | tap | error,success | NO | PASS | #783 | PENDING |
| E2296 | `subpackageMember/mytemplate/mytemplate.wxml:20` | `image` | `binderror` | `onCoverError` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E2297 | `subpackageMember/mytemplate/mytemplate.wxml:40` | `view` | `bindtap` | `toggleDropdown` | 1 | state | tap | success | NO | PASS | #783 | PENDING |
| E2298 | `subpackageMember/mytemplate/mytemplate.wxml:83` | `view` | `bindtap` | `templateStatus` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783 | PENDING |
| E2299 | `subpackageMember/mytemplate/mytemplate.wxml:90` | `view` | `bindtap` | `templateStatus` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #783 | PENDING |
| E2300 | `subpackageMember/mytemplate/mytemplate.wxml:97` | `view` | `bindtap` | `deleteItem` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,modal-cancelled,success,timeout | NO | PASS | #783 | PENDING |
| E2301 | `subpackageMember/mytemplate/mytemplate.wxml:113` | `cy-error` | `bind:retry` | `refreshList` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783 | PENDING |
| E2302 | `subpackageMember/mytemplate/mytemplate.wxml:120` | `view` | `bindtap` | `loadMore` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #783 | PENDING |
| E2303 | `subpackageMember/order/order.wxml:8` | `cy-scene-member-order-history` | `bind:open` | `openScene` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2304 | `subpackageMember/order/order.wxml:11` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E2305 | `subpackageMember/order/order.wxml:12` | `cy-scene-sheet` | `bind:requestclose` | `closeScene` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2306 | `subpackageMember/order/order.wxml:12` | `cy-scene-sheet` | `bind:back` | `closeScene` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2307 | `subpackageMember/order/order.wxml:13` | `cy-scene-member-order-detail` | `bind:open` | `openScene` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2308 | `subpackageMember/order/order.wxml:13` | `cy-scene-member-order-detail` | `bind:back` | `closeScene` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2309 | `subpackageMember/order/order.wxml:13` | `cy-scene-member-order-detail` | `bind:close` | `closeScene` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2310 | `subpackageMember/order/order.wxml:17` | `view` | `catchtouchmove` | `blockSceneTouch` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E2311 | `subpackageMember/order/order.wxml:18` | `cy-scene-sheet` | `bind:requestclose` | `backScene` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2312 | `subpackageMember/order/order.wxml:18` | `cy-scene-sheet` | `bind:back` | `backScene` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2313 | `subpackageMember/order/order.wxml:19` | `cy-scene-play-activity-detail` | `bind:open` | `openScene` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2314 | `subpackageMember/order/order.wxml:19` | `cy-scene-play-activity-detail` | `bind:close` | `backScene` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2315 | `subpackageMember/orderinfo/orderinfo.wxml:4` | `cy-nav-bar` | `bind:back` | `backToOrderList` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E2316 | `subpackageMember/orderinfo/orderinfo.wxml:7` | `cy-scene-member-order-detail` | `bind:open` | `openChildScene` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E2317 | `subpackageMember/orderinfo/orderinfo.wxml:7` | `cy-scene-member-order-detail` | `bind:back` | `backToOrderList` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E2318 | `subpackageMember/orderinfo/orderinfo.wxml:7` | `cy-scene-member-order-detail` | `bind:close` | `backToOrderList` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E2319 | `subpackageMember/signup/index.wxml:40` | `cy-error` | `bind:retry` | `retryActivity` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783 | PENDING |
| E2320 | `subpackageMember/signup/index.wxml:46` | `cy-error` | `bind:retry` | `retryTopic` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783 | PENDING |
| E2321 | `subpackageMember/signup/index.wxml:54` | `cy-tabs` | `bind:change` | `switchTab2` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E2322 | `subpackageMember/signup/index.wxml:70` | `swiper` | `bindchange` | `onTopicSwiperChange` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E2323 | `subpackageMember/signup/index.wxml:77` | `swiper-item` | `bindtap` | `goInfo` | 1 | navigation | tap | error,success | NO | PASS | #783 | PENDING |
| E2324 | `subpackageMember/signup/index.wxml:114` | `swiper` | `bindchange` | `onActivitySwiperChange` | 1 | state | event-contract | success | NO | PASS | #783 | PENDING |
| E2325 | `subpackageMember/signup/index.wxml:121` | `swiper-item` | `bindtap` | `goInfo2` | 1 | navigation | tap | error,success | NO | PASS | #783 | PENDING |
| E2326 | `subpackageMember/tixian/tixian.wxml:7` | `view` | `bindtap` | `openWithdrawHistory` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E2327 | `subpackageMember/tixian/tixian.wxml:15` | `view` | `bindtap` | `getUserData` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | — | PENDING |
| E2328 | `subpackageMember/tixian/tixian.wxml:16` | `view` | `bindtap` | `allWithdrawal` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E2329 | `subpackageMember/tixian/tixian.wxml:28` | `input` | `bindinput` | `onAmountInput` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2330 | `subpackageMember/tixian/tixian.wxml:39` | `input` | `bindinput` | `onRealnameInput` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2331 | `subpackageMember/tixian/tixian.wxml:45` | `input` | `bindinput` | `onBankNameInput` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2332 | `subpackageMember/tixian/tixian.wxml:51` | `input` | `bindinput` | `onBankAccountInput` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2333 | `subpackageMember/tixian/tixian.wxml:57` | `input` | `bindinput` | `onMobilephoneInput` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2334 | `subpackageMember/tixian/tixian.wxml:68` | `cy-btn` | `bindtap` | `saveData` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | — | PENDING |
| E2335 | `subpackageMember/tixian/tixian.wxml:74` | `cy-sheet` | `bind:close` | `closeWithdrawHistory` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2336 | `subpackageMember/tixianjilu/tixianjilu.wxml:6` | `cy-sheet` | `bind:close` | `onClose` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E2337 | `subpackageP3/components/metric/index.wxml:32` | `view` | `catchtap` | `onAction` | 1 | other | tap | error,success | NO | PASS | — | PENDING |
| E2338 | `subpackageP3/components/metric/index.wxml:40` | `view` | `catchtap` | `onAction` | 1 | other | tap | error,success | NO | PASS | — | PENDING |
| E2339 | `subpackageP3/components/xr-badge/index.wxml:3` | `canvas` | `bindtouchstart` | `onTouchStart` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E2340 | `subpackageP3/components/xr-badge/index.wxml:3` | `canvas` | `bindtouchmove` | `onTouchMove` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E2341 | `subpackageP3/components/xr-badge/index.wxml:3` | `canvas` | `bindtouchend` | `onTouchEnd` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E2342 | `subpackageP3/components/xr-badge/index.wxml:3` | `canvas` | `bindtouchcancel` | `onTouchEnd` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E2343 | `subpackageP3/pages/badge-3d/index.wxml:5` | `view` | `bindtap` | `goBack` | 1 | navigation | tap | error,success | NO | PASS | — | PENDING |
| E2344 | `subpackageP3/pages/badge-3d/index.wxml:13` | `xr-badge` | `bind:unsupported` | `onUnsupported` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2345 | `subpackageP3/pages/badge-3d/index.wxml:13` | `xr-badge` | `bind:loaded` | `onLoaded` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E2346 | `subpackageP3/pages/badge-3d/index.wxml:13` | `xr-badge` | `bind:xready` | `onXrReady` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E2347 | `subpackageP3/pages/badge-wall/index/index.wxml:10` | `canvas` | `bindtouchstart` | `onTouch` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E2348 | `subpackageP3/pages/badge-wall/index/index.wxml:10` | `canvas` | `bindtouchmove` | `onTouch` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E2349 | `subpackageP3/pages/badge-wall/index/index.wxml:10` | `canvas` | `bindtouchend` | `onTouchEnd` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2350 | `subpackageP3/pages/badge-wall/index/index.wxml:11` | `canvas` | `bindtouchcancel` | `onTouchEnd` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2351 | `subpackageP3/pages/badge-wall/index/index.wxml:16` | `view` | `bindtap` | `onFbTap` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E2352 | `subpackageP3/pages/badge-wall/index/index.wxml:28` | `view` | `bindtap` | `onFbTap` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E2353 | `subpackageP3/pages/badge-wall/index/index.wxml:42` | `cy-empty` | `bind:cta` | `goExplore` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E2354 | `subpackageP3/pages/badge-wall/index/index.wxml:48` | `cover-view` | `bindtap` | `goBack` | 1 | navigation | tap | error,success | NO | PASS | — | PENDING |
| E2355 | `subpackageP3/pages/badge-wall/index/index.wxml:59` | `cover-view` | `bindtap` | `setWallMode` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E2356 | `subpackageP3/pages/badge-wall/index/index.wxml:60` | `cover-view` | `bindtap` | `setListMode` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E2357 | `subpackageP3/pages/badge-wall/index/index.wxml:78` | `cy-error` | `bind:retry` | `loadData` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | — | PENDING |
| E2358 | `subpackageP3/pages/badge-wall/index/index.wxml:81` | `cover-view` | `bindtap` | `closeSheet` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E2359 | `subpackageP3/pages/badge-wall/index/index.wxml:81` | `cover-view` | `catchtouchmove` | `noop` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E2360 | `subpackageP3/pages/badge-wall/index/index.wxml:82` | `cover-view` | `catchtouchmove` | `noop` | 1 | other | event-contract | error,success | NO | PASS | — | PENDING |
| E2361 | `subpackageP3/pages/badge-wall/index/index.wxml:86` | `cover-view` | `bindtap` | `closeSheet` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E2362 | `subpackageP3/pages/badge-wall/index/index.wxml:96` | `cover-view` | `bindtap` | `goBadge3d` | 1 | navigation | tap | error,success | NO | PASS | — | PENDING |
| E2363 | `subpackageP3/pages/creator/index/index.wxml:7` | `cy-error` | `bind:retry` | `loadCreatorCenter` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #782 | PENDING |
| E2364 | `subpackageP3/pages/creator/index/index.wxml:21` | `input` | `bindinput` | `onNameInput` | 1 | state | event-contract | success | NO | PASS | #782 | PENDING |
| E2365 | `subpackageP3/pages/creator/index/index.wxml:22` | `textarea` | `bindinput` | `onBioInput` | 1 | state | event-contract | success | NO | PASS | #782 | PENDING |
| E2366 | `subpackageP3/pages/creator/index/index.wxml:23` | `cy-btn` | `bindtap` | `submitApply` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #782 | PENDING |
| E2367 | `subpackageP3/pages/creator/index/index.wxml:37` | `view` | `bindtap` | `goIncomeAll` | 1 | navigation | tap | error,success | NO | PASS | #782 | PENDING |
| E2368 | `subpackageP3/pages/growthcenter/index/index.wxml:15` | `view` | `bindtap` | `goLeaderboard` | 1 | navigation | tap | error,success | NO | PASS | — | PENDING |
| E2369 | `subpackageP3/pages/growthcenter/index/index.wxml:52` | `view` | `bindtap` | `loadOverview` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | — | PENDING |
| E2370 | `subpackageP3/pages/growthcenter/index/index.wxml:57` | `view` | `bindtap` | `onBadgeTap` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E2371 | `subpackageP3/pages/growthcenter/index/index.wxml:73` | `cy-empty` | `bind:cta` | `goExplore` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E2372 | `subpackageP3/pages/growthcenter/leaderboard/index.wxml:14` | `cy-tabs` | `bind:change` | `onPeriodChange` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783 | PENDING |
| E2373 | `subpackageP3/pages/growthcenter/leaderboard/index.wxml:29` | `cy-error` | `bind:retry` | `onRetry` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783 | PENDING |
| E2374 | `subpackageP3/pages/growthcenter/leaderboard/index.wxml:60` | `cy-empty` | `bind:cta` | `goExplore` | 1 | navigation | event-contract | error,success | NO | PASS | #783 | PENDING |
| E2375 | `subpackageP3/pages/growthcenter/leaderboard/index.wxml:121` | `cy-tabs` | `bind:change` | `onMetricChange` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #783 | PENDING |
| E2376 | `subpackageP3/pages/stamp-album/index/index.wxml:21` | `cy-error` | `bind:retry` | `retryLoad` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | — | PENDING |
| E2377 | `subpackageP3/pages/stamp-album/index/index.wxml:26` | `view` | `bindtap` | `goCamera` | 1 | navigation | tap | error,success | NO | PASS | — | PENDING |
| E2378 | `subpackageP3/pages/stamp-album/index/index.wxml:32` | `view` | `bindtap` | `retryLoad` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | — | PENDING |
| E2379 | `subpackageP3/pages/stamp-album/index/index.wxml:33` | `view` | `bindtap` | `goCamera` | 1 | navigation | tap | error,success | NO | PASS | — | PENDING |
| E2380 | `subpackageP3/pages/stamp-camera/index/index.wxml:5` | `camera` | `binderror` | `onCamErr` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E2381 | `subpackageP3/pages/stamp-camera/index/index.wxml:6` | `camera` | `bindtouchstart` | `onTouchStart` | 1 | other | event-contract | error,success | NO | PASS | #787,#783 | PENDING |
| E2382 | `subpackageP3/pages/stamp-camera/index/index.wxml:6` | `camera` | `bindtouchmove` | `onTouchMove` | 1 | state | event-contract | success | NO | PASS | #787,#783 | PENDING |
| E2383 | `subpackageP3/pages/stamp-camera/index/index.wxml:13` | `cy-nav-bar` | `bind:back` | `onBack` | 1 | navigation | event-contract | error,success | NO | PASS | #787,#783 | PENDING |
| E2384 | `subpackageP3/pages/stamp-camera/index/index.wxml:22` | `button` | `bindtap` | `openSetting` | 1 | external | tap | cancelled,denied,duplicate-trigger,error,success,timeout | YES | PASS | #787,#783 | PENDING |
| E2385 | `subpackageP3/pages/stamp-camera/index/index.wxml:23` | `view` | `bindtap` | `onBack` | 1 | navigation | tap | error,success | NO | PASS | #787,#783 | PENDING |
| E2386 | `subpackageP3/pages/stamp-camera/index/index.wxml:28` | `view` | `bindtap` | `onShutter` | 1 | external | tap | denied,duplicate-trigger,error,success,timeout | YES | PASS | #787,#783 | PENDING |
| E2387 | `subpackageP3/pages/stamp-camera/index/index.wxml:39` | `view` | `bindtap` | `onRetake` | 1 | state | tap | success | NO | PASS | #787,#783 | PENDING |
| E2388 | `subpackageP3/pages/stamp-camera/index/index.wxml:40` | `view` | `bindtap` | `onSave` | 1 | write | tap | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | #787,#783 | PENDING |
| E2389 | `subpackageRoam/citynode-code/index.wxml:17` | `cy-empty` | `bind:cta` | `onMissingBack` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E2390 | `subpackageRoam/citynode-code/index.wxml:31` | `cy-qr-voucher` | `bind:close` | `onClose` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E2391 | `subpackageRoam/citynode-code/index.wxml:32` | `cy-qr-voucher` | `bind:retry` | `onRetry` | 1 | write | event-contract | duplicate-trigger,error,forbidden-owner,success,timeout | NO | PASS | — | PENDING |
| E2392 | `subpackageRoam/history/index.wxml:4` | `cy-nav-bar` | `bind:back` | `goBack` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E2393 | `subpackageRoam/history/index.wxml:7` | `cy-scene-roam-history` | `bind:open` | `openSession` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E2394 | `subpackageRoam/history/index.wxml:7` | `cy-scene-roam-history` | `bind:close` | `goBack` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E2395 | `subpackageRoam/session/index.wxml:4` | `cy-nav-bar` | `bind:back` | `goBack` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E2396 | `subpackageRoam/session/index.wxml:7` | `cy-scene-roam-session` | `bind:back` | `goBack` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E2397 | `subpackageRoam/session/index.wxml:7` | `cy-scene-roam-session` | `bind:close` | `goBack` | 1 | navigation | event-contract | error,success | NO | PASS | — | PENDING |
| E2398 | `subpackageRoam/session/index.wxml:7` | `cy-scene-roam-session` | `bind:share` | `share` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2399 | `subpackageRoam/session/index.wxml:12` | `cy-sheet` | `bind:close` | `closeShare` | 1 | state | event-contract | success | NO | PASS | — | PENDING |
| E2400 | `subpackageRoam/session/index.wxml:39` | `cy-error` | `bind:retry` | `retryShare` | 1 | external | event-contract | duplicate-trigger,error,openSetting-cancelled,openSetting-denied,saveImageToPhotosAlbum-cancelled,saveImageToPhotosAlbum-denied,success,timeout | YES | PASS | — | PENDING |
| E2401 | `subpackageRoam/session/index.wxml:42` | `cy-btn` | `bindtap` | `saveShareCard` | 1 | external | tap | duplicate-trigger,error,openSetting-cancelled,openSetting-denied,saveImageToPhotosAlbum-cancelled,saveImageToPhotosAlbum-denied,success,timeout | YES | PASS | — | PENDING |
| E2402 | `subpackageRoam/session/index.wxml:52` | `cy-error` | `bind:retry` | `retryShare` | 1 | external | event-contract | duplicate-trigger,error,openSetting-cancelled,openSetting-denied,saveImageToPhotosAlbum-cancelled,saveImageToPhotosAlbum-denied,success,timeout | YES | PASS | — | PENDING |
| E2403 | `subpackageRoam/session/index.wxml:54` | `view` | `bindtap` | `closeShare` | 1 | state | tap | success | NO | PASS | — | PENDING |
| E2404 | `subpackageTalent/search/index.wxml:13` | `cy-search` | `bind:input` | `onInputChange` | 1 | state | event-contract | success | NO | PASS | #787,#783,#782 | PENDING |
| E2405 | `subpackageTalent/search/index.wxml:13` | `cy-search` | `bind:confirm` | `onSearchSubmit` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E2406 | `subpackageTalent/search/index.wxml:14` | `view` | `bindtap` | `onSearchSubmit` | 1 | read | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E2407 | `subpackageTalent/search/index.wxml:20` | `cy-tabs` | `bindchange` | `onCategoryChange` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E2408 | `subpackageTalent/search/index.wxml:24` | `cy-error` | `bind:retry` | `getSearchList` | 1 | read | event-contract | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E2409 | `subpackageTalent/search/index.wxml:33` | `view` | `bindtap` | `goUserInfoClcik` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E2410 | `subpackageTalent/search/index.wxml:61` | `view` | `bindtap` | `goTemplateDetail` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E2411 | `subpackageTalent/search/index.wxml:102` | `image` | `bindtap` | `goActivity` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E2412 | `subpackageTalent/search/index.wxml:107` | `button` | `bindtap` | `activityLike` | 1 | write | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E2413 | `subpackageTalent/search/index.wxml:116` | `view` | `bindtap` | `goActivity` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E2414 | `subpackageTalent/search/index.wxml:154` | `image` | `bindtap` | `goTopic` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |
| E2415 | `subpackageTalent/search/index.wxml:159` | `button` | `bindtap` | `activityLike` | 1 | write | tap | duplicate-trigger,error,success,timeout | NO | PASS | #787,#783,#782 | PENDING |
| E2416 | `subpackageTalent/search/index.wxml:168` | `view` | `bindtap` | `goTopic` | 1 | navigation | tap | error,success | NO | PASS | #787,#783,#782 | PENDING |

## 十二、完成“全部通”还缺什么

1. 先合并或等价落地 #788/#783 的合作中心修复，并在最新 master 重跑负控→绿。
2. 处理开放 PR 的重叠与冲突，再以最终合并后的唯一 SHA 重新生成 2,416/2,464 分母；不能把多个 PR 的绿拼成 master 的绿。
3. 在受控测试后端执行 2,321 个非 external 动作分支（2,464 - 143），逐分支写入不可变 evidence；写操作必须读取服务端权威终态。
4. 在真实 iOS/Android 设备完成 143 个 external 动作分支，覆盖定位、相机、扫码、手机号、支付、系统权限拒绝/恢复。
5. 用玩家、商家、俱乐部主理人、第二账号四身份跑 owner/scope 拒绝与恢复；资金、权限、生命周期全部 fail-closed。
6. 合并后完成微信上传、合法域名/包体回读与版本观测；代码/PR 绿不等于已发布。
7. 更新 route-matrix 新鲜度并重新验证 116 页当前 SHA；若 DevTools 仍被其它任务占用，应排队而不是复用旧进程。

## 十三、最终判定

| 断言 | 判定 |
|---|---|
| “116 个功能都能启动” | 未证明；页面层可枚举 116/116，但 App 未注册，动态结果按假绿作废；静态 116/116 |
| “36 个流程都完整” | 否；F35 当前确定中断，其余流程动态证据不足 |
| “237 个按钮都有正确反馈” | 未证明；#782/#783 有大量修复但尚未合入，且反馈需真实点击/终态回读 |
| “2,416 条事件都正确” | 未证明；2,412 条进入 2,464 分支计划，全部动态 pending；4 条声明未实例化 |
| “14 个 WXML 是线上功能” | 否；它们完全不可达，不属于当前线上操作分母 |
| “开放 PR 已处理的还要不要再改” | 不要；先标 PR 覆盖并等待合入/冲突处理，最终 SHA 再复审 |
| 发版结论 | `NO-GO / EVIDENCE_INCOMPLETE` |
