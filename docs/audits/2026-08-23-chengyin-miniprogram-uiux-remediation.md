# 城瘾原生小程序全页面源码盘点与静态 UI/UX 整改

初建：2026-08-23

最后静态核验：2026-08-24

分支：`codex/uiux-doc-optimization-0823`

基线：`github/master@133c62f0d4e78db496efba071574550d5cc88f67`

> **2026-08-25 补记(合并到主线时追加,原文其余部分保持不动)**
>
> 本报告成文时这轮改动还停在未提交的隔离工作树上。2026-08-25 已把它提交并 rebase 到
> `github/master@3e071c15a`，41 个文件冲突逐条判定后合并，分支为 `[历史工具路径或分支标识已移除]`。
> 判定原则:master 上有契约 + 负控锁着的行为一律以 master 为准(一次性完成动效禁 `infinite`、
> 全页返回栈 `exitPage`、创建链路保留引导页、真实图标);两边都成立的改动合并保留,不二选一。
>
> 因此下文 §7「未 commit、未 push、未创建 PR」以及 §5 表中「唯一失败为未提交源码的
> `sourceDigestAtCommit=null`」都已过期。合并后的实测口径:
>
> - Shot Matrix:269 total / 246 actionable / 23 blocked(并入 master 的 F17/F18 导演台与本站两行)。
> - Action Ledger inventory:2909 controls，dynamic evidence 仍为 0，release gate 仍 RED。
> - UI-GATE-0 / JS-SCOPE / 单测:全绿。
> - 合并过程另抓出三类**原报告没覆盖**的问题:一条引用相等的假绿契约、7 个页面缺根栈落点的裸
>   `navigateBack`、一个被解冲突误删的 `onNavBack`。详见
>   `04-未开始/小程序UIUX现状判断与提升清单_20260825.md`。
>
> **P0 三条的真实状态(纠正原报告)**:只有 B30 奖励序列化是真修了,且是 2026-08-25 合并时才修的
> —— `_rewards()` 此前一直是 `'✨ ' + rw.settleXp + ' 成长值'`,奖励字段回对象就渲染成
> `[object Object]`;现已 fail-closed 到只接受可读标量并带负控。**A42 他人资料黑屏与 C23/C24
> 主题主体不可见,根因至今未定位**:静态读码找不到任何能让 `viewNodeCount=0` 的渲染闸(路由正常
> 注册在分包、`options.id` 有兜底、`cy-profile` 根节点无 `wx:if`)。本轮对这两条做的是改断言锚点、
> 注入 fixture 与补空态,**不构成修复** —— 需要 automator 真复现另立一轮,本 PR 不认领这两条。
>
> 真机、微信上传、27 个 blocked 状态与 2914 个控件的动态证据仍未完成，发布判定不变。

> 本报告记录源码覆盖、静态门禁和 239 个可自动化状态的开发者工具证据。它不等于 2814 个控件已完成动态下一步，也不代替真机、真实身份和真实后端验收。

## 1. 结论

本轮已把玩家、商家、俱乐部三种身份的 **118 个注册页面**和 **26 个 `scene-*` 组件**全部纳入静态审核分母，商家版没有抽样跳过。

- 108 个注册页面直接修改了页面源码：玩家 53、商家 45、俱乐部 10。
- 其余 10 个注册页面没有直接修改页面四件套，但会消费本轮已改的 scene 或共享组件：玩家 8、商家 2、俱乐部 0。
- 26/26 个 scene 组件目录均有本轮改动。
- 核心状态组件共 10 个：增强既有组件 6 个，新增组件 4 个。
- Action Ledger 静态 inventory 为 2814 个 control variant，动态 evidence 为 0；release gate 仍为 RED。
- Shot Matrix 为 266 个状态，其中 239 个 actionable、27 个 blocked；blocked 均保留具体原因与替代验证，未被静默移出分母。
- 239/239 个 actionable 状态已在同一源码摘要下完成最终采集：P0 125、P1 112、P2 2，截图错误 0、pending 0、陈旧帧 0。
- 六批 manifest 合并后，磁盘文件、状态、MD5 与感知查重均通过；239 张 MD5 全互异，感知重复 0 组。
- 239 张已用 6 张 contact sheet 全量可见走查，并对 A48、C04/C06、D30/D32、加载/错误/空态与商家关键页做原图复核；这仍不是与每一张外部参考逐像素一致的证明。
- 真机、VoiceOver、真实授权弹窗、真实图片比例、相机、定位、扫码与微信包体仍未完成验收。
- 本轮未 commit、未 push、未创建 PR、未上传微信、未部署后端。

因此，当前状态是：**源码整改与受控模拟器视觉矩阵已完成；控件行为、真机和发布验收仍为 NO-GO。**

## 2. 覆盖口径

### 2.1 注册页面

| 身份 | 注册页面 | 直接修改页面源码 | 间接受影响 | 静态分母覆盖 |
| --- | ---: | ---: | ---: | ---: |
| 玩家 | 61 | 53 | 8 | 61/61 |
| 商家 | 47 | 45 | 2 | 47/47 |
| 俱乐部 | 10 | 10 | 0 | 10/10 |
| 合计 | 118 | 108 | 10 | 118/118 |

这里的“直接修改”指该注册路由的 `.js`、`.json`、`.wxml`、`.wxss` 或 `.wxs` 至少一个文件相对基线发生变化；它不等于该页已经完成动态视觉验收。

118 个页面中，91 个需要登录，49 个需要业务数据前置条件。`route-matrix.json` 中的 `added=21/removed=1` 是相对历史截图批次的变化，不是本分支相对 `github/master` 的 Git delta。

### 2.2 未直接修改的 10 个注册页面

以下页面通过本轮已改的 scene 或共享组件受到间接影响：

- `pages/member/index/index`
- `pages/agreement/index`
- `pages/deregister/index`
- `pages/activity/detail/index`
- `subpackageA/pages/infomationdetail/infomationdetail`
- `pages/shezhi/about/index`
- `subpackageMember/coupon-wallet/index`
- `subpackageMember/orderinfo/orderinfo`
- `subpackageRoam/history/index`
- `subpackageRoam/poi-detail/index`

其中深链/薄壳页面的正文由 scene 承载；其余页面消费了本轮变更的 `cy-profile`、`cy-nav-bar`、`cy-empty`、`cy-error`、`cy-skeleton`、`cy-cell` 或 `cy-qr-voucher` 等共享组件。该关系只能证明源码影响范围，不能代替逐页截图和交互回读。

### 2.3 Scene 与核心状态组件

26 个 scene 均进入盘点并发生源码改动，覆盖活动详情、订单与提现、商家收益、二维码、漫游历史/会话/点位、路线内容、注销、分享邀请、声音与触感等场景。

增强的既有核心状态组件：

- `cy-empty`
- `cy-error`
- `cy-profile`
- `cy-qr-voucher`
- `cy-skeleton`
- `cy-tabs`

新增的核心状态组件：

- `cy-inline-error`
- `cy-offline-banner`
- `cy-progress-status`
- `cy-state-shell`

此外还修改了导航、图标、表单、上传、搜索、sheet 和 scene 等共享组件；“核心状态组件 10 个”不是全部共享组件改动数。

## 3. 设计参考与取舍

本轮没有把参考产品的视觉表皮直接套到城瘾，也没有把 Web/iOS 实现方式带入原生微信小程序。采用的是能解释真实业务状态的方法，并落到城瘾现有 token、主题和组件体系。

| 参考 | 采用的方法 | 没有照搬的部分 |
| --- | --- | --- |
| 60fps / Atlys | 奖励获得时的低频庆祝、明确进度、动作完成后的持久回执 | 不用字符或 CSS 伪造 3D coin；不把品牌动画铺满业务页 |
| Atlys roadmap | 区分单请求等待与多步骤进度，reduced motion 下保留静态路径与状态 | 不在账本、结算、密集表单中加入连续路径动画 |
| Emil Kowalski animation principles | 动画解释状态变化；刷新保留旧内容；失败原位恢复 | 不给所有卡片添加泛化 transition |
| Appllama 方法 | 先建立页面、旅程、状态和动作分母，再决定整改优先级 | 不用少量漂亮截图代替全量审核 |
| Inspora / Best Designs on X | 参考层级、留白、状态组件和信息密度 | 不做逐像素克隆，不破坏城瘾既有主题域 |
| SwiftUI Agent Skill | 借鉴尊重原生平台、避免 LLM 伪实现的原则 | 项目是 WXML/WXSS/JS，不引入 SwiftUI 代码或依赖 |

进一步检索 60fps 的 loading、progress、map、empty 与 reward 分类后，采用了以下可落地模式：

| 具体模式 | 城瘾落点 | 约束 |
| --- | --- | --- |
| AllTrails：切换分类时正文先过渡到同构 skeleton，再进入新卡片 | 列表首载使用同构骨架；已有内容刷新不清空旧卡 | 不给每次短请求制造整页闪烁 |
| Eimi：同一 bottom sheet 内由占位卡转为完成卡，并同步更新动作 | `cy-progress-status` 与 scene sheet 保持任务上下文，完成后给持久回执 | reduced motion 下仍须靠文字和步骤表达，不依赖 morph 才能理解 |
| Airlearn：长等待期间用有节奏的 loading 序列维持可感知反馈 | 仅用于确实较长、无法局部保留正文的等待 | 不在普通接口请求中循环播放品牌动画 |
| Smallcase：资金/数据抓取使用阶段化进度和完成检查 | 资金页区分 loading、unknown、0、ready 与局部 stale-error | 不伪造百分比、金额或“即将完成”承诺 |
| Corner：地图进入前先表达 getting ready，再交付可操作地图 | 漫游与搜索地图区分准备、定位未授权、数据失败与可用态 | 地图未完成时不提前显示可操作假标记 |
| District / Fold：错误空态与业务空态都有明确、单一的下一步 | `cy-error`、`cy-empty`、`cy-inline-error` 分开网络、数据、权限、缺参和合法空结果 | 不用“暂无数据”吞掉错误，也不为无权用户伪造 CTA |
| Atlys / Duolingo：奖励与路径进度在真实完成事件后庆祝 | 新徽章、路线完成和庆祝板只在真实新增/完成时触发；静态画面保留奖励与步骤 | 3D/粒子只用于低频高价值时刻；无真实资产时使用项目现有静态资产，不用 CSS/字符伪造 |

用户提供的若干 X 单帖在本轮访问环境中返回 403。本报告不声称已直接看见、逐帧分析或复刻这些帖子；可访问的 Atlys 页面、公开仓库和当前小程序源码才作为可核验依据。

## 4. 静态整改摘要

### 4.1 加载、未完成与错误状态

本轮在相关高风险页面和共享组件中统一采用以下状态语义：

1. 首次加载使用与正文结构相近的 skeleton，不用“加载中……”文字占位。
2. 刷新保留上一次已确认内容，使用局部 progress，避免整页闪白。
3. 部分失败保留成功区块，只在失败区块显示 inline error 和单一恢复动作。
4. 刷新失败进入 `stale-error`，明确旧内容尚未更新，不清空已确认数据。
5. 首载失败区分 network、data、permission、missing-param 与 missing-record。
6. 只有合法空数组或空结果进入 empty；畸形 HTTP 200 进入可恢复错误态。
7. 关键成功动作保留页内可读回执，不只依赖瞬时 toast。
8. 高风险请求页使用 single-flight、abort 或 epoch，避免迟到回调覆盖新状态。

这些语义已经进入静态合同，并由 239 个可自动化状态在开发者工具中复核；它们不表示 27 个 blocked 状态、真实后端数据或真机能力已经验收。

### 4.2 数据真值与恢复能力

- 资金和统计字段保留 `unknown/null` 与合法 `0` 的差异，不用强制数值转换制造假读数。
- 合法空榜、空列表与真实错误分开；刷新失败时保留已确认空态或旧数据。
- 列表、详情、权益和成长记录对 id、number、array、boolean 与空白字符串做 fail-closed 校验。
- 表单提交失败保留草稿和页内恢复入口，避免重复 toast；离页与并发回调使用冻结/abort/epoch 保护。
- 玩家、商家和俱乐部页面均进入同一静态状态模型，但身份、授权和真实资金结果仍需动态回读。

当前工作树中的并行后端 WIP 不属于本轮报告范围，本报告不对其 SQL、CAS、时区、MyBatis 测试或生产效果作归因。

### 4.3 动效、图标与无障碍边界

- 新徽章只应在真实刷新发现新增项时庆祝，首载不制造“刚获得”的假反馈。
- `cy-progress-status` 区分多步骤进度与单请求等待；关闭动态效果后仍保留静态步骤和回执。
- 密集商家页以读数稳定、局部刷新和清晰恢复动作为主。
- 整改范围优先使用项目真实 `cy-icon` 或真实图片资产，不新增 emoji、字符图标或伪 3D 占位。
- 定向状态组件已补充 88rpx 点击区、ARIA 属性和 reduced-motion 静态合同。

VoiceOver、焦点顺序、播报内容、动画手感和不同机型上的裁切仍须设备验收；静态合同不能证明无障碍合规。

## 5. 机器证据

| 门禁 | 当前可核验结果 | 证据层级 |
| --- | --- | --- |
| 注册路由矩阵 | 118 routes；玩家 61 / 商家 47 / 俱乐部 10 | source/static |
| Scene 盘点 | 26 scenes；26/26 目录有改动 | source/static |
| 页面源码影响 | 108 直接修改；10 间接受影响 | source/static |
| UI-GATE-0 | PASS；338 JS / 225 WXML / 460 backend Mapping | static lint |
| UI 负控自测 | PASS；U1–U5 及 naming/warn/baseline 负控可先红后绿 | mutation/selftest |
| JS scope | PASS；338 个生产 JS，未解析标识符 0，不存在导出 0 | static lint |
| RequestBody 契约 | 0 error / 4 warn，warning 仍需人工确认 | static contract |
| Action Ledger inventory | PASS；2814 controls / 0 evidence results | source/static |
| Action Ledger release | RED；pending=2814，nonPassed=0 | release gate |
| Shot Matrix | PASS；266 total / 239 actionable / 27 blocked | static matrix |
| 最终模拟器截图 | PASS；P0 125/125、P1 112/112、P2 2/2，合计 239/239 OK | controlled simulator |
| 截图来源 | PASS；六批均为 HEAD `133c62f0d`、source digest `6fd60a1d…fcc32` | provenance |
| 截图批次判决 | PASS；盘上 239、MD5 重复 0、感知重复 0 | pixel/batch gate |
| DevTools console | 31 条原始消息保留并分类；没有被从 manifest 删除 | dynamic diagnostic |
| JS / JSON / diff | 变更 JS 语法、JSON 解析与 `git diff --check` 通过 | source/static |
| 全量单测汇总 | 3812 tests；3809 pass；1 fail；2 skip；唯一失败为未提交源码的 `sourceDigestAtCommit=null`，无法满足持久证据 provenance | unit / expected release red |
| 微信包体 | WAIT-EXTERNAL；需 DevTools/miniprogram-ci 生成真实 package info | external |
| 真机与无障碍 | WAIT-EXTERNAL | real device |

Action Ledger 分类：state 1199、navigation 475、other 274、read 408、write 268、external 190，共 2814。`action-evidence.json` 当前结果为 0，因此不得把 inventory PASS 写成“全部按钮已可点击并完成下一步”。

## 6. 动态截图与行为证据

Shot Matrix 的静态分母为：

- P0：135 total，其中 125 actionable、10 blocked。
- P1：122 total，其中 112 actionable、10 blocked。
- P2：9 total，其中 2 actionable、7 blocked。
- 合计：266 total，其中 239 actionable、27 blocked。

最终动态结果只允许从与最终工作树摘要绑定、已经结束并完成合并校验的 manifests 读取：

| 优先级 | Actionable | 最终 OK | 最终 Error | 最终 Pending |
| --- | ---: | ---: | ---: | ---: |
| P0 | 125 | 125 | 0 | 0 |
| P1 | 112 | 112 | 0 | 0 |
| P2 | 2 | 2 | 0 | 0 |

最终动态证据：

- 最终工作树源码摘要：`6fd60a1d9102c0c1aae9e21dcda4c16a6de3a2db419e59e7e30984fce67fcc32`。
- 最终证据目录：`/Users/arlan/.codex/visualizations/2026/08/23/01a02e00-e37c-7f00-8176-672d4547700a/uiux-final-20260824/final-freeze4-2GKN4Y`。
- 六批 manifest：`manifest.final4-p0a.json`、`manifest.final4-p0b.json`、`manifest.final4-p0c.json`、`manifest.final4-p1a.json`、`manifest.final4-p1b.json`、`manifest.final4-p1c-p2.json`。
- 合并清单：`manifest.merged.json`；状态为 OK 的截图数 239，Error 0，Pending 0。
- 自动像素判据：空白/DevTools chrome/尺寸/状态锚点通过；MD5 239 个全互异；感知重复 0 组。
- 人工视觉检查：6 张 contact sheet 覆盖并可见全部 239 张；A48、C04/C06、D30/D32 及代表性 loading/error/empty/merchant 状态另看原图。未发现需要废弃的帧；该检查不等于与所有外部参考逐像素对齐。

本轮已满足的合并判据：

1. 每个 manifest 的项目路径、源码摘要、viewport、startedAt、finishedAt 和 summary 均完整。
2. `OK + Error + Pending` 必须等于对应 priority 的 actionable 数。
3. 文件存在、manifest 写 `ok` 之外，还完成了全量 contact sheet 可见走查、重点状态原图复核和感知查重。
4. 参考图与当前截图尚未完成同 viewport 并排复核时，不得声称逐像素一致。
5. 相机、定位、扫码、授权和系统弹窗必须使用真机证据，不能由 setData fixture 冒充。

`fixtureVerified=21` 只表示页面仍消费对应 fixture key；其中 1 条 blocked，actionable 内为 20。本轮没有继承旧截图结论，239 个 actionable 状态均在最终源码摘要下重新采集。

## 7. 已知边界与发布状态

当前静态证据可以确认：

- 注册路由和 scene 分母；
- WXML 绑定、部分请求和响应形状；
- 状态字段、空值语义、错误恢复入口和静态无障碍属性；
- Action Ledger inventory 已与当前源码一致。
- 239 个可自动化状态在固定 viewport、同一 source digest 下完成受控渲染，截图文件、状态锚点、像素与查重门禁通过。

当前证据不能确认：

- 27 个 blocked 状态以及所有真实业务数据组合的视觉效果；
- 不同机型的安全区、裁切、键盘顶起、字体渲染和滚动位置；
- 动画节奏、触感、VoiceOver 与系统授权弹窗；
- 2814 个动作的动态下一步与对端事实回读；
- 真实后端、数据库、资金结果、微信包体与线上发布状态。

发布边界：

- Git：未 commit、未 push、未创建 PR。
- 微信：未上传、未提交审核、未发布。
- 后端：未部署；本报告也未授权部署。
- Action Ledger：release gate RED。
- 动态 UI/UX：受控模拟器视觉矩阵 239/239 已收口；真机、真实数据与全动作验收仍为 NO-GO。

## 8. 发布前 TODO

1. 对 2814 个动作补动态 evidence；写操作必须读回对端事实，不能用页面跳转或 toast 代替。
2. 使用真实玩家、商家、俱乐部身份验证切换、撤权、断网、超时、并发和旧数据刷新。
3. 在真机验证相机、定位、扫码、授权、键盘、触感与 VoiceOver；真实签名二维码必须完成扫码回读。
4. 获取真实 package info，验证主包、分包和总包体。
5. commit 后重跑 Action Ledger provenance 单测，使 `sourceDigestAtCommit` 有可验证来源；当前未提交状态下该项保持红是正确行为。
6. 若目标变为逐像素复刻，再对可访问参考逐张建立相同 viewport 的并排比较；本轮只采用其交互与状态方法，不声称视觉克隆。
7. 处理终审中确认的代码/console 风险后再 commit、push、创建 PR；微信上传与发布仍是独立动作。

## 9. 参考链接

- [60fps / Atlys](https://60fps.design/apps/atlys)
- [Atlys rewards 3D coin spin animation](https://60fps.design/shots/atlys-rewards-3d-coin-spin-animation)
- [Atlys roadmap path animation](https://60fps.design/shots/atlys-roadmap-path-animation)
- [AllTrails tab / skeleton transition](https://60fps.design/shots/alltrails-tab-icon-animation)
- [Eimi generating card state transition](https://60fps.design/shots/eimi-generating-card-preview-morph-pop-scale-in-sheet-animation)
- [Airlearn page loading animation](https://60fps.design/shots/airlearn-page-loading-animation)
- [Smallcase loading references](https://60fps.design/apps/smallcase)
- [Corner map getting-ready references](https://60fps.design/apps/corner)
- [60fps empty-state collection（含 Fold / District）](https://60fps.design/shots/filter/empty)
- [60fps progress collection（含 Smallcase / Duolingo）](https://60fps.design/shots/filter/progress)
- [Emil Kowalski skills](https://github.com/emilkowalski/skills)
- [Appllama skills](https://github.com/Appllama/appllama-skills)
- [SwiftUI Agent Skill](https://github.com/twostraws/SwiftUI-Agent-Skill)
- [Inspora](https://www.inspora.design/)
- [Best Designs on X](https://bestdesignsonx.com/)
- [用户提供的 X 参考：tranmautritam](https://x.com/tranmautritam/status/2091449978355093745?s=46) — 本轮环境返回 403
- [用户提供的 X 参考：60fpsdesign](https://x.com/60fpsdesign/status/2091403957218492653?s=46) — 本轮环境返回 403
- [用户提供的 X 参考：proskuaaa](https://x.com/proskuaaa/status/2084153539568492917?s=46) — 本轮环境返回 403
- [用户提供的 X 参考：fberrez](https://x.com/fberrez/status/2091406593405374975?s=46) — 本轮环境返回 403
