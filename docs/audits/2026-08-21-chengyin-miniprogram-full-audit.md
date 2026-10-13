# 城瘾微信小程序全量功能、可靠性、安全与 UI/UX 审计

## 2026-08-21 收口更新（当前结论；以下章节保留初审过程证据）

本轮已知代码缺陷、静态清单与受控视觉状态已经整改，代码证据基线为 `ecc306329236cc29fea1782c5b2ca98e3e367148`，已 rebase 到 `github/master=32992766476d2a644ea5f52d3a9071368d698b04`（ahead 25 / behind 0），交付 PR 为 [#783](https://github.com/liboyang42-cpu/chengyin/pull/783)，当前 head `f9193cea3ec4f7283f15e5425fbd1c7b274be427`，GitHub CI 14/14 success（0 failed / 0 pending）。Action Ledger 追踪本文件方法调用闭包与仅来自 `App()` 真源的全局能力 wrapper 后，2,273 个非 external 动作仍为 `LOCAL_QA_PENDING`，107 个 external 动作与其它外部事实仍为 `EXTERNAL_GATE`。独立收口报告见 `docs/audits/2026-08-21-chengyin-miniprogram-remediation-closure.md`。

当前本地证据：小程序 `2,779` tests（`2,777 pass / 0 fail / 2 skip`）；`bash ci/xcx-check.sh`、Maven 全仓、Action Ledger inventory/freshness、requestbody、npm audit、secret-file gate、migration layout/safety 全部通过。事件绑定已从初审的 2,360 更新为 2,380，并全部进入 Action Ledger；动态结果由 `action-evidence.json` 单独持久化，当前 overlay 0 条。`preflight:release` 因 `pending=2380` 正确给出 1 个 blocker，不能写成全部按钮行为通过或可以发布。

DevTools 状态矩阵不再停在 44/235：当前矩阵共 263 项，25 项明确 blocked 并绑定替代验证，238 个可操作状态已经全部采集，合并终闸为 `238/238 ok`，重复 md5 为 0。证据目录：`/Users/arlan/.codex/visualizations/2026/08/21/01a02358-1901-7851-8ad8-fde28447dfe3/xcx-final-7193f824f`。

最终四个红项已关闭：B36 滚动到真实空态；B37 补齐标题/时间/正文；B50 对齐当前 `.slopes-tabbar`；C10 改验已退役商家兼容壳的真实缺参出口，并修复微信 `request:fail` 诊断信息透传。截图出身 `5ba61490c`，随后 rebase 仅引入 `chengyinhub-xcx/tests/unit/shop-day-game-sheet-contract.test.js`，生产小程序源码字节级无差异。

发布结论仍为 `No-Go`，但原因已从已知代码缺陷收窄为待执行证据与外部事实：2,273 个非 external 动作仍是 `LOCAL_QA_PENDING`；107 个 external 动作需双端真机；此外还有微信支付 API 证书/APIv3 key 轮换、两条幂等迁移真 MySQL 执行与回读、微信合法域名和 `--info-output` 包体、四身份与第二账号、无障碍及性能分位数。生产数据库、部署和微信上传均未执行，不能宣称线上无任何 bug或“每个按钮已在真机真实后端通过”。

---

> 审计日期：2026-08-21
> 代码基线：初始审计 `bba3d08d6d0ca476925bff72f97124b4f0120549`；隔离修复基线 `56afb88671f7c9a48e100e3e908975de6bccdf69`。最终回读 `github/master=edfacf277757b7c24f0070596b230fd8c3e03ab0`，修复 worktree 已落后 2 个提交，提交前必须先同步并复跑门禁。
> 审计范围：`chengyinhub-xcx/` 全量源码、前端所调用的后端 Controller 契约、微信开发者工具受控态。
> 证据边界：本报告不是线上、生产库、微信体验版或真机验收证明；没有把模拟器绿灯写成“已上线/已可用”。

当前 worktree 在开审前已有用户改动：`chengyinhub-xcx/app.wxss` 为 modified，另有与本审计无关的 untracked 文件。本次没有覆盖或清理它们；视觉截图代表“该 HEAD + 当前未提交 app.wxss”的工作区实况，不应冒充干净 commit 的视觉证据。

> 修复进度（2026-08-21）：可在仓库内闭环的 P0/P1/P2 代码项已在隔离分支 `codex/xcx-release-stoploss-0821`、基线 `56afb88671f7c9a48e100e3e908975de6bccdf69` 完成；尚未 commit、push、合并、执行两条数据库迁移、上传微信版本或完成真机/真实后端验收，因此本文总体 No-Go 不变。

最终本地证据：全量小程序单测 `2,735` 条，`2,733 pass / 0 fail / 2 skip`；`lint:ui` 扫描 `315 JS / 215 WXML / 411 backend Mapping`，`2,360` 个事件绑定全部解析，U1–U5 与门禁 selftest 均通过；`lint:requestbody` 为 `0 error / 3` 条已人工复核的动态 wrapper 警告；`preflight:release` 为 `0 blocker / 0 warn`；微信源码隐私门通过，package size 仍为 `WAIT-EXTERNAL`。后端全仓 Surefire XML 汇总为 `4,443` 条、`0 failure / 0 error / 202 skip`；其中本轮 commerce/address/coop 精确测试 `9 + 8 + 8 + 2 = 27` 条全绿，购物车真实 MyBatis/H2 事务组 `16/16` 全绿；`mvn -q -DskipTests package` 通过。两条新迁移已通过真分句器与目录门，但**未执行任何数据库写入**。

DevTools 动态证据：登录首页烟测通过；商家结算页真实渲染与截图通过；真实可见的 `play.sheetNav` 地图导航用例先红后绿并调用 `wx.openLocation`。AI RequestBody 端到端被微信 request 合法域名配置挡在网络层；集邮相机 EXIF 自动夹具在当前 DevTools 报临时文件不可读。这两项均按外部/基础设施闸门保留，未伪造为通过。

### 0.1 本轮已落地的修改

| 领域 | 已完成修改 | 关键验收 |
|---|---|---|
| 空值/异常/敏感信息 | 登录 payload fail-closed；统一 `safeUserMessage` 过滤 HTML、SQL、Java 堆栈、路径、URL、token、手机号、邮箱与超长文本 | 畸形 200、对象型错误和泄露负控均绿 |
| 重复请求/并发 | 首展 guard、列表 generation/abort、搜索条件快照、写操作 keyed single-flight、上传并发池 | 旧响应覆盖、双击、同步 abort、离页迟到回调负控均绿 |
| 权限 | GET 默认携带 JWT；25 个 GET 建角色/scope 矩阵并与后端真源交叉；地址与订单按 member/merchant owner 回读 | 私有 GET 降匿名、team-progress 归属、roam scope、第二账号拒绝用例均绿 |
| 超时/未知态 | 统一请求默认 12s；支付回读单次 5s/总墙钟 20s；写操作 15s；上传单文件 15s/总 60s | 永不回调与同步 abort 均进入 unknown，禁止盲目重付/重提 |
| 资金/幂等 | 商家权益订单与参与人新增使用 requestId + 唯一键；报名、权益、保证金支付均以服务端终态为准 | 双击、取消复用、关闭订单换新 requestId、支付 SDK success 但服务端未确认均有负控 |
| 定位/隐私 | 后台定位 generation + starting/stopping 状态机；owner/waiter 去重；退出补偿 stop；拒权后设置页授权可自动继续原动作 | delayed start/stop、同 owner、双 owner、退出、stale waiter 全覆盖 |
| UI/流程 | 地址保存/删除/默认按钮禁用；错误/unknown 可见反馈；清除退场 `navTo` 孤儿，动态测试改为真实 `sheetNav` 按钮 | UI 门禁与 DevTools 地图导航先红后绿 |

### 0.2 仍为发布硬闸的 TODO

- [ ] 提交前同步最新 `github/master=edfacf277...`（当前 behind 2）；唯一重叠的 `ci/migration-dependencies.json` 已在工作区预合并最新 5 条声明与本轮 2 条声明，ratchet/dep-order 已全绿，但仍须在真正 rebase 后重跑全量前后端门禁，不能把预合并当作已同步基线。
- [ ] 按 `chengyin-db-migrate` 走只读预检、真实 MySQL 兼容性验证、人工确认后，依次落库 `migration_20260821_commerce_order_idempotency.sql` 与 `migration_20260821_member_address_idempotency.sql`，再从数据库回读列、唯一索引与重复键负控。
- [ ] 在微信后台补齐并回读 request 合法域名；以 anonymous/player/merchant/club 四身份跑真实后端核心旅程及第二账号 IDOR。
- [ ] 用 DevTools 预览/上传的 `--info-output` 回读主包、分包与总包体；当前只有源码门，不能宣称包体合格。
- [ ] 微信 iOS + Android 真机验证定位/后台定位、相机 EXIF、扫码、分享、文件、支付、订阅消息的允许/拒绝/取消/系统失败与离页清理。
- [ ] 跑完 235 个可操作状态与全部核心 CTA 的 tap→状态/路由/API/服务端事实回读；当前只能证明静态 `2,360` 个绑定存在和已覆盖的自动化主链，不等于“每个按钮已真机验收”。
- [ ] 完成读屏、约 200% 文本、灰度、触控热区、安全区、键盘/返回，以及 iOS/Android 分平台 P50/P90/P95 性能采样。

## 0. 执行结论

**No-Go。当前版本不得宣称“每个按钮都能进入下一步”、不得宣称达到 Apple Design Award 级质量，也不适合直接进入发布验收。**

原始 P0/P1 代码缺陷已在隔离分支修复并由本地负控覆盖；当前 No-Go 由以下**外部事实缺口**维持：

1. 两条唯一键迁移尚未在真实 MySQL 预检、执行并从数据库回读，代码不能先于 schema 发布。
2. 微信 request 合法域名当前阻断 AI 端到端实验；anonymous/player/merchant/club 与第二账号 IDOR 尚无真实后端矩阵证据。
3. package size 只有源码态 `WAIT-EXTERNAL`，没有 DevTools 预览/上传产物的对端数据。
4. iOS/Android 真机尚未覆盖后台定位、相机 EXIF、支付 callback loss、扫码、分享、文件与订阅消息。
5. 全按钮行为证明仍不完整：当前已证明 2,360 个 WXML 绑定静态有效，并动态跑通登录、商家结算和真实地图导航，但尚未完成 235 个状态与全部核心 CTA 的 tap→结果回读。
6. 全链路读屏、文本放大、灰度、触控热区和分平台性能分位数仍无当前版本证据。

剩余优先级是 `迁移/真实角色权限 → 微信包体与合法域名 → 235 状态与核心 CTA → 双端真机无障碍/外部能力/性能`；在这些闸门完成前不再以新增视觉润色替代发布证据。

---

## 1. 审计标准：什么才算“好的设计”

本次不把 Apple 的视觉风格照搬到微信小程序。规则优先级是：

> 微信目标平台明确规则 > 可移植的人因与产品原则 > Apple 的 iOS 专属实现细节

“最高标准”要求七件事同时成立：用户价值明确、流程可完成并可恢复、平台交互自然、状态反馈清楚、常用任务可无障碍完成、真实设备性能可靠、隐私与权限值得信任。Apple Design Awards 是卓越案例与质量信号，不是合规清单；真正可执行的依据来自 Apple HIG/Accessibility 与微信官方设计、ARIA、性能规则。

详细的 18 条可复测判据、微信专属边界、P0 硬门禁和 100 分评分框架见：

- `docs/research/2026-08-21-mini-program-design-excellence-primary-sources.md`

本轮采用的硬门禁：

- 核心角色能完成发现 → 理解条件 → 执行 → 明确结果 → 状态持久化/可复查。
- 弱网、超时、拒绝授权、空值、重复点击、深链和服务端异常都有解释与退路。
- 破坏性/资金/核销动作有幂等、确认和结果回读。
- 读屏、非颜色线索、约 200% 文本放大、对比度和动效降级均有真机证据。
- 权限只在用户触发相关功能时申请；退出/撤回之后不能继续采集。
- 性能按 iOS/Android、机型、网络、入口和分位数给真实数据，不能以开发者工具单次体验代替。

任一硬门禁失败，无论界面多漂亮都判 No-Go；因此本轮不计算一个会掩盖 P0 的“综合高分”。

---

## 2. 覆盖范围与本次证据

### 2.1 代码与交互库存

| 项目 | 本次结果 |
|---|---:|
| 小程序生产 JS | 315 个 |
| WXML | 215 个 |
| JSON | 221 个 |
| `app.json` 注册路由 | 113 个 |
| Scene 双生/场景页 | 26 个 |
| WXML 事件绑定 | 2,360 个，静态解析 0 未决 |
| 前端静态 API 路径 | 485 个；动态 12 个；变量 12 个均有逐条理由 |
| `sendRequest` 调用 | RequestBody 门禁扫描 360 处 |
| `wx.requestPayment` | 6 处，逐处回读 |
| 定位 API | 31 处 |
| Storage API | 78 处 |
| 截图矩阵 | 260 状态；25 个明确 blocked；235 个可操作状态 |
| 路由覆盖 | 109 个有矩阵状态 + 4 个明确豁免 = 113/113 |

### 2.2 已执行验证

| 验证 | 结果 | 能证明什么 | 不能证明什么 |
|---|---|---|---|
| `npm run test:unit` | 2,735 tests；2,733 pass；0 fail；2 skip | 纯函数、契约和既有回归未红 | 真机、真实后端、全按钮命中、线上权限 |
| `npm run lint:ui:selftest` | U1–U5 负控均能红后恢复 | UI 静态门禁不是恒绿 | 交互流程真实可达 |
| `npm run lint:ui` | 315 JS / 215 WXML / 411 后端映射；U1–U5 PASS | 路由、handler、字段和逐项静默理由未漂移 | 真机遮挡、全部动态旅程 |
| `npm run lint:requestbody` | 0 error；3 WARN，回读后均为受控动态 wrapper | RequestBody 静态契约未发现新增阻断 | 真实合法域名与后端部署状态 |
| route/coverage/shot/verify selftests | 9 / 7 / 全矩阵 / 10 条负控均绿 | 矩阵、漏项和假截图门禁能被负控打红 | 当前 235 个状态已经全部有效采集 |
| `npm run preflight:release` | 0 blocker / 0 warn / 5 info | 正式 HTTPS 基址、主分包、协议文本等静态项 | 可以发布；该命令本身明确只有静态自检 |
| `npm run verify:wechat-release` | privacy source PASS；package size `WAIT-EXTERNAL` | 隐私 API 声明与源码契约 | 预览包体、上传成功或真机可用 |
| `dead-ui-regression` | 先红后绿，`play.sheetNav → wx.openLocation` PASS | 真实可见地图导航动作在 DevTools 可达 | 其它所有按钮均已动态通过 |
| 登录/商家结算 automator | PASS / PASS | 首页无无限 loading；结算视图和两条资金去向真实渲染 | 真角色后端授权与真机一致性 |
| 后端全仓 Surefire | 4,443 tests；0 failure；0 error；202 skip | 全仓 Java 回归未红；地址 `request_id` 首轮触发 H2 schema 漂移，补齐夹具后购物车事务组 16/16、全仓复跑全绿 | 真实 MySQL schema 与生产部署；202 条 skip 不是执行证据 |
| 后端精确 Surefire | 27 tests；0 failure/error/skip | commerce/address/coop status 的幂等与 owner 拒绝契约 | 真实 MySQL schema 与生产部署 |
| Maven package | `mvn -q -DskipTests package` PASS | 全仓编译/打包未回归 | 已部署或线上运行 |
| 迁移静态门 | 2 个 SQL 真分句器 PASS；目录门 PASS；依赖 ratchet/声明/批内顺序 PASS；门禁负控 107/107 | 文件可被真实 runner 正确分句、位置合法，依赖声明未倒退且文件名执行顺序安全 | 已落库；真实 MySQL 兼容性 |
| `npm audit --omit=dev` | 0 vulnerability | 小程序生产依赖树未报告已知漏洞 | DevTools 自动化工具链；全量 audit 仍有 10 条仅开发依赖告警 |

### 2.3 DevTools 视觉证据状态

本次使用当前 HEAD、独立自动化端口和 624×1352 视口重新采集，不复用旧截图。每张要求：目标 route 回读、DOM/fixture 状态回读、非零 view node、干净 frame、磁盘 md5 对齐、图片不重复/非同屏假态。

截至报告定稿，本次全量批次在第 44/235 个状态后因连续隐私页重定向和 automator timeout 停止；随后用独立端口与原端口各补跑一次商家 P0（46 状态），两次都在 150 秒内未得到可响应的逻辑层，均为 0 张。该结果按验收基础设施阻断保留，没有用旧图补数。

当前批次结果：

- 44 条记录：`ok=32`、`state-unmet=2`、`error=10`；磁盘 34 张当前 PNG。
- A04 注销态：guest storage/global identity 写入虽回读一致，但首页仍显示上个账号昵称，DOM forbid 红；说明 fixture 没有清完整用户快照。
- A13 骨架态：目标 `cy-skeleton` 数量为 0，内容与加载态混合。
- A37/A38：automator response timeout。
- A39/A40/A42/A45/A48/A52/A53：目标 route 回读为 `/pages/privacy/index`，不能给目标页注 fixture。
- `_merge_manifests.js`：44/235，有 265 项终闸错误，退出 1。
- `verify-batch.js`：`VOID`；201 张缺失；A03 error 与 A04 logout 被感知判据识别为“md5 不同但肉眼同屏”。

批次 manifest 与图像目录：

- `/Users/arlan/.codex/visualizations/2026/08/21/01a02358-1901-7851-8ad8-fde28447dfe3/xcx-full-audit-20260821/manifest.merged.json`

任何 `state-unmet`、`error`、缺图、重复 md5 或感知同屏组都按失败处理，不能从分母删除。结论因此是“当前视觉/状态矩阵未通过”，不是“剩余 201 张默认正常”。

---

## 3. 最高优先级问题

### P0-01 后台定位可在用户退出后迟到启动

**证据**

- `chengyinhub-xcx/utils/location/bg-tracker.js:25-41`
- `chengyinhub-xcx/utils/location/bg-tracker.js:46-52`
- `chengyinhub-xcx/pages/roam/index.js:527-529`
- `chengyinhub-xcx/pages/roam/index.js:2453-2457`
- 动态负控：`acquire → release → delayed success` 后 `isRunning=true, stopLocationUpdate=0`。

**根因**

`release()` 在 `started === false` 时直接返回；迟到的 background/foreground success 回调没有 owner 或 generation 复核，仍设置 `started=true`。同时 `_stopReal()` 在 `_realOn=false` 时不调用 `release('roam')`。

**影响**

用户已经退出、取消或撤回操作，后台定位仍可能启动并持续。这不是一般体验问题，而是隐私和合规红线。

**TODO / 验收**

- [x] tracker 已显式区分 starting/running/stopping，并以 generation 失效旧启动；同 owner acquire 幂等、不同 owner single-flight；旧 stop 完成前禁止新 start。
- [x] `release` 在 starting 状态记录取消；成功回调若 owner 已不存在，立即 `stopLocationUpdate()`。
- [x] `_stopReal()` 无论 `_realOn` 是否已置真都先释放 owner；已注册 handler/keep-screen 在 active 分支收口。
- [x] 已新增异步负控：background success 迟到、fallback foreground success 迟到、两个 owner 交错、force release、同 owner 重入、released-owner 迟到失败、delayed stop→restart、stopping 中 release 后的 stale waiter 不得复活。
- [ ] 真机 iOS/Android 验证：立即返回、锁屏、切后台、拒绝权限、撤回权限；用系统定位指示和回调计数回读。

---

## 4. P1：会直接打断正常流程或产生高代价风险

### P1-01 当前用户 GET 不带 JWT，且一个端点 method 错配

**根因**：`chengyinhub-xcx/utils/transport/request-client.js:35-45` 把“无 header 的 GET”永久视为公共请求；`auth` 默认值也不会让 GET 带 JWT。

**已确认受影响**

- `pages/activity/official-inbox/index.js:30-55`：`GET /api/official/v2/party-inbox`
- `pages/activity/list/index.js:110-118`：`GET /api/official/can-publish`
- `pages/activity/list/index.js:147-163`：`GET /api/official/my-events`
- `pages/activity/official-mine/index.js:28-43`：`GET /api/official/my-published`
- `pages/merchant/coop-center/index.js:205-219`：`GET /api/official/merchant-invites`
- `utils/deregister-flow.js:21-34`：`GET /api/user/deregister/status`
- `pages/play/index.js:2668-2682`：前端 `GET /api/play/my-completed`，后端 `ApiPlayProgressController.java:1489` 是 `@PostMapping`。

这些 Controller 都读取当前用户或当前权限。正常鉴权下会 401/业务拒绝；静默重登后重试仍不带 JWT。若生产居然能返回个性化数据，则必须反向核验后端是否误开放，不能把“能用”当安全。

**TODO / 验收**

- [x] 统一请求已改为所有 method 默认注入认证；真正匿名请求仍须显式 `auth:false`，禁止再用 HTTP method 推断。`official/events` 虽 permitAll，但会按 `uid()` 返回个人报名态，故保留可选认证，不强制匿名。
- [x] 最新 `github/master` 实际有 25 个 GET 调用点，已建立“URL + method + auth 模式 + 角色 + scope”清单并锁入 `get-auth-matrix-contract.test.js`；scope 使用受控枚举，并对 `team-progress` 与 roam `pois/tiles` 绑定后端真源断言；新增调用未分类会红。
- [x] 静态门禁已交叉比较调用点与 `SecurityConfig`：`official/events*` 可匿名但保留可选身份，`city/nodes*` 必须认证；`my-completed` 前后端均锁为 POST。
- [ ] 用真实后端分别以 anonymous/player/merchant/club 调用；私有端点无 token 必须红，有正确 token 才绿。

### P1-02 商家权益购买缺防重、幂等键和终态核验

**证据**：`pages/merchant/decor/index.js:246-270`。

快速双击或弱网重试可并发创建多个 commerce order；当前无法仅凭前端断言一定重复扣款，但重复订单和冲突回调已是确定风险。

- [x] 复用 `checkout-workflow`，增加 busy 锁和服务器幂等 `requestId`。
- [x] 校验完整支付参数；支付完成后查询订单终态，不能以 `requestPayment.success` 当最终成功。
- [x] 取消、失败、HTTP 异常、页面卸载都必须释放锁并终止 timer/request。
- [x] 负控：双击、回调乱序、支付成功但查询超时、支付取消、页面离开。

### P1-03 支付轮询没有墙钟 deadline

**证据**

- `pages/activity/baoming/baoming.js:702-730`
- `pages/topic/index/index.js:758-786`
- `components/cy/scene-member-order-detail/index.js:362-390`
- `utils/transport/request-client.js:165`

当前“最多 5 次”只有在单次请求返回之后才会推进。统一请求无默认 timeout，任何一次不回包都可让 workflow 长时间停在 `verifying`。

- [x] 每次查询设短 timeout，并设置独立总 deadline。
- [x] 超时进入“结果未知，请到订单查看”，停止 timer、abort 请求、恢复可退出状态。
- [x] 支付/核销/上传等写动作统一定义 `success / failed / cancelled / unknown` 四终态。
- [x] 用永不回调的 request stub 做负控，必须在 deadline 后退出核验态。

### P1-04 达人搜索分类存在旧响应覆盖新分类

**证据**：`subpackageTalent/search/index.js:156-170,191-258`。

A 分类请求在途时切到空 B 分类，B 因 `loading` 被丢弃；A 返回时读取当前 `activeCategoryIndex`，把 A 数据写进 B。

- [x] 请求开始时捕获 `{category,page,keyword}` 快照。
- [x] 新条件使旧请求 sequence 失效或直接 abort。
- [x] 回调只能写入捕获条件对应的列表。
- [x] 负控：A 慢 B 快、A 快 B 慢、连续输入、切分类后下拉分页。

### P1-05 权限缓存未与所有清会话路径同步失效

**证据**

- `app.js:151-156,182-189`
- `utils/roleGuard.js:9-16,35-54`

token 过期且静默重登失败时，session key 被清，但模块 `_cache` 和 `role_permission_cache_v1` 可继续保留旧权限。34 个角色/能力读取点可能显示旧账号高权限入口；是否能写成功还要靠后端 IDOR/owner 测试，前端隐藏不能替代服务器授权。

- [x] 所有 `clearSession()` 路径同步 `roleGuard.clear()`。
- [x] 缓存绑定 `user_id + session fingerprint + expiry`，身份不匹配即失效。
- [ ] player → merchant → 登出 → player 连续切换负控；每步都回读页面栈和可见动作。
- [ ] 所有写接口保留后端 role + owner/scope 校验，并以第二账号做 IDOR 负控。

### P1-06 核销写接口缺少 single-flight

**证据**

- `pages/merchant/index/index.js:591-634,671-690,708-727`
- `components/cy/profile/index.js:1077-1123,1151-1170,1187-1206`
- `subpackageMember/components/scene-member-participation-detail/index.js:79-120`

扫码结果或选章/选据点按钮重复触发时，可并发提交多条核销请求。

- [x] 抽成统一 verification workflow，按 `code + chapter/station` single-flight。
- [x] 在途禁用全部等价入口，完成/取消后再解锁。
- [ ] 后端保留原子幂等，回读核销记录而不是只认 toast。

### P1-07 参与人新增、删除、设默认缺防重

**证据**

- `pages/addressinfo/addressinfo.js:119-175`
- `pages/address/address.js:98-176`

保存按钮只按表单合法性禁用，没有 `saving` 防重；删除确认后的请求、设默认也没有按 ID 的在途锁。弱网双击会发出重复写入或乱序刷新。

- [x] `saving/deletingIds/defaultingId` 纳入 data 和按钮禁用态。
- [x] 新增/编辑使用客户端 requestId + 服务端幂等；删除和设默认用后端条件更新/唯一约束兜底。
- [x] `complete/finally` 统一解锁；HTTP 非 200、超时、页面离开均覆盖。
- [x] 双击/三击和乱序返回负控必须只观察到一次业务结果。

---

## 5. P2：状态一致性、隐私最小化和异常质量

### P2-01 首次进入存在确定性双请求

确认示例：

- `pages/address/address.js:25-35`
- `pages/activity/baoming/baoming.js:68-84,994-996`
- `pages/talent/list/index.js:100-104,140-150`
- `pages/shezhi/shezhi.js:31-40`
- `pages/square/list/index.js:557-588,805-814`
- `pages/merchant/index/index.js:115-125,346-382`：本地已有 merchant 身份时，首次进入可重复拉整组 console 请求。
- `components/cy/scene-member-order-history/index.js:67-78`

首次展示会顺序触发 `onLoad/attached` 与 `onShow/show`。无首展 guard 的页面会并发请求同一接口，迟到响应可覆盖正确状态。

- [x] 首展只由一个生命周期加载。
- [x] `onShow` 用 `_hasShown` 或 dirty/version 标记，只在子页、支付或编辑返回时刷新。
- [x] 列表请求带 epoch/abort，旧响应不得覆盖新条件。
- [ ] 建 lifecycle 请求计数测试：首次进入每个逻辑资源只能 1 次。

### P2-02 报名联系人完整 PII 长期缓存且可采用陈旧记录

**证据**

- 完整对象写入：`pages/activity/baoming/baoming.js:245-255,262-272,339-350`
- 读取后直接采用：同文件 `:160-187`

`selectedAddress` 保存姓名、手机号、邮箱等完整对象；再次进入时没有与当前 `/api/user/address/list` 求交。服务端已删除的联系人仍可被采用，数据会保留到显式退出清全 Storage。

- [x] 只缓存 `selectedAddressId`，并按 `user_id` 分区。
- [x] 每次只从当前服务端列表按 ID 选择；找不到即清除并回退默认项。
- [x] 清会话、注销、换账号都清此键；不要在客户端长期复制 PII。
- [x] 负控：删除联系人、换账号、token 过期重登、列表为空。

### P2-03 对象型错误消息可绕过脱敏，121 处直接展示 `res.msg`

**证据**

- `app.js:355-394`
- 动态负控：顶层 HTML 字符串会被回落；`{msg:'<html>...长堆栈...'}` 会原样返回。
- 直接展示示例：`pages/merchant/decor/index.js:258`、`pages/square/detail/index.js:466`、`pages/addressinfo/addressinfo.js:162`。

标签/长度检查只覆盖顶层 string；`res.msg/res.message` 仅过少量 Java/SQL 关键词。若网关 JSON message 含路径、堆栈或敏感字段，页面可直接展示。

- [x] 建唯一 `safeUserMessage(any)`，所有 string/msg/message/errMsg 走同一长度、HTML、堆栈、路径、SQL 与 allowlist 规则。
- [x] 响应进入业务页前统一净化，直接读取 `res.msg` 也不再得到原始敏感文本。
- [x] 负控覆盖 HTML、SQL、Java stack、URL/API path、超长文本、手机号/token/邮箱。

### P2-04 登录响应缺少 token/字段结构校验

**证据**：`utils/session/session-manager.js:36-45,64-74`。动态负控中 `{data:{id:7}}` 被判为 `ok=true, authed=true`，authorization 为 `undefined`。

- [x] 非空 token、有效 user id、受控 role/userType 全部通过后再落地。
- [x] 畸形 200 判 `invalidPayload` 并 fail closed，不能制造“假登录成功”。
- [ ] 避免部分字段先写入后失败，确保身份快照原子切换。

### P2-05 上传并发、超时、取消未收口

**证据**

- `utils/transport/upload-client.js:26-91`
- `pages/square/components/activity-picker/index.js:57-60,82-114`

选择 9 张时会同时启动 9 个上传；统一上传没有 timeout/abort/RequestTask 返回；路线截图还有 4 秒整体 timer，迟到上传可能白耗流量且预览静默丢失。

- [x] 上传池并发限制为 2–3。
- [x] 支持 per-file timeout、总 deadline、abort/destroy 和页面卸载清理。
- [ ] 路线预览清除生成 timer，上传用独立 deadline；直接 `wx.uploadFile` 迁回统一客户端。
- [x] 失败项保留精确 index/path/status，不让一张失败拖死全组，也不把部分成功冒充全成功。

### P2-06 checkout workflow 的销毁能力未被所有 consumer 接入

已有 `destroy()`：`utils/checkout/checkout-workflow.js:37-50,92-97`；报名页已接入，但以下示例未完整接入：

- `pages/coop/list/index.js:142-149`
- `pages/topic/index/index.js:244-252`
- `components/cy/scene-member-order-detail/index.js:184-203`
- `components/cy/scene-member-order-history/index.js:67-78`

- [ ] 所有页面/组件在 `onUnload/detached` 调 `workflow.destroy()`。
- [x] 已接入的高价值 workflow 一并清 timer、abort request、屏蔽迟到 toast/navigation/setData。
- [x] 离页后回调负控：页面栈和 data 不得再变化。

### P2-07 网络与地址配置缺少环境和域名治理

本次运行时代码只发现两个 API/资源基址，都是正式 HTTPS：

- `app.js:56`：`https://www.chengyinhub.com/prod-api`
- `utils/config.js:4`：`https://www.chengyinhub.com/prod-api/profile/`

另有：

- `pages/templatedetail/templatedetail.wxss:458` 直接引用外部 OSS 图片，绕过统一资源基址与域名治理。
- `utils/config.js:6` 的 `amapWebKey` 为空且运行时代码没有引用，是误导性的死配置。
- `app.js:415-420 getImgUrl()` 接受任意 `http://`/`https://` 服务端字符串；虽然发布预检未发现源码中的明文 HTTP，但服务端响应仍可把不可信或非 HTTPS 资源带进 UI。
- 本地 develop 默认也指向生产 base；没有显式 staging/dev 环境选择，测试误碰生产的操作风险偏高。

- [x] API、图片与上传基址已收口到单一配置；资产 URL 使用 allowlist。
- [x] `getImgUrl()` 只接受 HTTPS 且命中受信域名；相对路径统一走 `baseImgUrl`。
- [x] 本轮命中的硬编码远程 CSS 资源已迁为本地资产，release 静态门禁通过。
- [x] 删除未使用的 `amapWebKey`；禁止在源码提交未使用 secret。
- [ ] develop/staging/release 三环境显式区分；非 release 构建默认不得命中生产写接口。

---

## 6. P3：明确的“点了没反应”和长期维护风险

### P3-01 定位失败时有按钮静默

- `pages/square/list/index.js:818-845`
- `pages/roam/index.js:3059-3073`
- `pages/publish/fabu/index.js:4284-4294`

拒绝权限、GPS 关闭或微信 API 失败时，重定位、手选漫游位置、添加彩蛋可能表现为没有反应。

- [x] 本轮命中的定位/选点入口统一迁到 `utils/location/location-manager.js`。
- [x] 授权拒绝给设置入口，用户取消与系统失败分开文案；设置页授权后自动继续原动作。
- [ ] 动态 tap 后必须观察到页面状态、modal/toast 或页面栈之一发生正确变化。

### P3-02 JWT 持久化没有客户端 expiry 元数据

- `utils/session/session-store.js:14-22,36-39,48-56`
- `utils/session/session-manager.js:102-107`

微信 Storage 属于小程序沙箱，不能按浏览器 localStorage 直接定高危；但 token 会跨启动保存并在第一次 401 前无条件信任。

- [x] 存发放时间/expiry，启动时先本地失效。
- [x] 换账号/清会话同步清 token 元数据与角色缓存；服务端撤销仍需真实后端回读。

---

## 7. 用户流程健康度

这里的“Broken”指当前代码/证据已足以说明正常路径不可靠；“Friction”指可进入但存在重复、竞态、隐私或恢复问题；“Unproven”指没有足够动态证据，不等于默认通过。

| 核心流程 | 健康度 | 当前判断 |
|---|---|---|
| 启动 / 首页 / 五 Tab | Friction | 路由与默认画面存在；部分首展双请求；注销/异常态本次 fixture 未稳定兑现，不能结案 |
| 官方活动收件箱 / 我的活动 / 我的发布 | **Broken** | 当前用户 GET 不带 JWT；静默重登后仍不带 |
| 主题浏览 → 报名 → 参与人 → 支付 | Friction | 主报名已有 checkout 防重，但 PII 缓存陈旧、地址写入口可重复、终态轮询无墙钟 deadline |
| 探店日导航 / 到店 / 通关 | **Broken / Unproven** | `my-completed` method 错配；`navTo` 动态断言红；其余到店写保护较完整但需真实 GPS/后端 |
| 漫游实时定位 | **Broken** | 退出后迟到启动定位的 P0 已动态复现 |
| 发布主题 / 广场帖子 / 媒体上传 | Friction | 表单静态契约充分；上传无并发池、timeout、abort；部分定位失败静默 |
| 俱乐部发现 / 达人搜索 | Friction | 分类快速切换可被旧响应覆盖；俱乐部 owner 权限仍需双账号后端负控 |
| 商家首页 / 经营台 | Friction | 首次进入可重复拉整组 console；用户态 merchant invites 不带 JWT |
| 商家权益购买 | **Broken-risk** | 无防重、幂等键和支付终态核验 |
| 商家扫码核销 / 选章 / 选据点 | **Broken-risk** | 多入口缺 single-flight；后端幂等未在本轮生产回读 |
| 订单 / 结算 / 提现 | Unproven | 金额与 UI 契约测试较多；真实资金、权限和终态禁止用 fixture 宣称通过 |
| 设置 / 账号注销 | **Broken** | `/deregister/status` 当前用户 GET 不带 JWT |
| 隐私授权 / 登录 / 换账号 | Friction | 隐私页和 session 测试较多；畸形登录 payload 可制造假登录，权限 cache 有陈旧窗口 |
| IM / 分享 / 相机 / 文件 / 微信支付 | Unproven | 依赖微信真机、账号、权限和平台回执；必须在体验版逐项完成 |

---

## 8. UI/UX 设计审查

### 8.1 已确认的优点

- 玩家暗色与商家浅色两套主题已有 token 和共享组件，不是 100 多页各自随意造样式。
- 大量列表具备 loading / empty / error / content 状态，截图矩阵也把状态作为独立对象管理。
- 核心资金、日期、主题编辑、对比度、返回和 ARIA 已有多组契约测试与负控。
- 首页、广场等画面有明确品牌辨识度；不是套模板式后台 UI。
- 微信隐私检查、定位用途和相关 private API 已在 `app.json` 声明。

### 8.2 最高标准下仍不达标的部分

1. **任务层级被品牌 hero 压过。** 首页首屏大幅插画占据绝大多数视口，真实“附近活动/下一步”落在首屏底部或以下；品牌记忆强，但任务效率和信息密度偏低。
2. **异常/身份态的视觉证据不稳定。** 本次 A04 注销态未兑现，画面仍保留已登录昵称；这首先说明验收 fixture 不可信，也意味着该状态不能凭旧截图结案。
3. **错误恢复仍需全旅程视觉收口。** transport 已在响应进入页面前净化原始错误，高价值定位/支付/上传/写入也已有明确 unknown/retry；但全量页面是否都以一致组件、就地文案和可恢复动作呈现，仍缺 235 状态动态回读。
4. **点击绑定数量远大于行为证明。** 2,360 个 binding 全部“有 handler”是好基础，登录、结算和地图导航已有少量 DevTools 动态证据；ADA/HIG 级质量仍要求每条核心旅程逐步观察结果，而不是只证明 handler 存在。
5. **无障碍证据不足。** 零散页面有 ARIA 和对比度契约，但没有微信 iOS/Android 读屏走完全量 common tasks、200% 文本、灰度、减少动效、不同机型热区的当前证据。
6. **性能证据不足。** 当前只有静态、单测和开发者工具行为；没有按平台、机型、网络、入口、样本量和 P50/P90/P95 分层的正式数据。
7. **部分视觉验收备注已经漂移。** A17 的矩阵备注仍写“商家浅色”，当前捕获却是共享暗色俱乐部页；这可能是有意保持五 Tab 的玩家壳，也可能是商家视角没有兑现。没有产品边界裁决前应标为 `UNPROVEN`，不能照旧备注修代码或把截图判绿。

### 8.3 设计整改原则

- 先保证“下一步可发现、点后有反馈、结果可回读”，再增加愉悦细节。
- 每页只保留一个视觉主动作；危险动作与主动作不能同权。
- loading 尽量局部化；超过明确阈值后给取消/重试/结果未知出口。
- 表单错误贴近字段，保留用户已填内容；不可逆动作给确认或撤销。
- 状态不能只靠颜色；自定义图标按钮必须有 label/role/state。
- 不仿制 Apple 外观；以微信胶囊、页面栈、标准组件、iOS/Android 微信真实行为为准。

---

## 9. “每一个按钮可以点开并进入下一步”的验收 TODO

静态全绿只完成第一层。应建立一份可机器执行的 Button/Action Ledger，每一条包含：

`route + role + entry + control selector + visible condition + action class + expected route/state/API + success readback + error/timeout/duplicate expectation + external gate`

### 9.1 分层执行

- [x] **L1 静态存在**：2,360 个 binding、目标 handler、目标 route、API URL + method + auth 契约全部解析；动态 URL 已有逐条理由，U1–U5 与 selftest 通过。
- [ ] **L2 命中能力**：用真实 `tap()` 而非只 `callMethod()`；回读控件尺寸、遮挡、disabled、aria、胶囊和安全区。
- [ ] **L3 页面内状态**：弹层打开/关闭、Tab 切换、校验、loading、empty、error、retry 后 data/DOM 真变化。
- [ ] **L4 导航**：点击后回读 `getCurrentPages()`，目标 route/options 与返回路径正确；不能只信 `navigateTo success`。
- [ ] **L5 读操作**：fixture/测试后端断 URL + method + auth + 参数；快速切换时旧响应不得覆盖。
- [ ] **L6 写操作**：双击只产生一个业务事实；超时后进入 unknown；从后端/列表重新读取目标状态，不能以 toast 当成功。
- [ ] **L7 外部能力**：定位、相机、扫码、分享、文件、支付、订阅消息在微信 iOS/Android 体验版分别验证允许/拒绝/取消/系统失败。

### 9.2 必跑行为清单

- [ ] 执行并自动化 `scripts/uiaudit/behavior-assertions.md` 中 A1–A6、B1–B6、C1–C3、D1–D3、F1–F2；不再保留“清单存在但一条未跑”。
- [ ] 对 46 个 `needsData` 页面至少覆盖 loading / empty / error / content / retry。
- [ ] 对 87 个登录页覆盖 anonymous、token 过期重登成功、重登失败、换账号。
- [ ] 对 player / merchant / club 三身份覆盖可见性 + 实际服务器授权；每个写接口用第二账号做 IDOR。
- [ ] 对 6 个支付入口覆盖 double tap、cancel、fail、callback loss、终态超时、页面离开。
- [ ] 对 31 个定位调用覆盖权限拒绝、GPS 关闭、模糊定位、后台/锁屏、立即退出和撤回。
- [ ] 对 78 个 Storage 调用分类为 session / preference / draft / PII；定义 owner、expiry、登出/换账号清理规则。

### 9.3 完成定义

只有同时满足以下条件，才可把“每个按钮可用”从目标改为结论：

1. ledger 零缺项，且每条动作有当前版本的动态结果；
2. 写动作有服务端结果回读和重复/超时负控；
3. 权限有 anonymous + 正确角色 + 错误角色 + 第二账号证据；
4. DevTools 只作为逻辑层，外部能力有双端真机证据；
5. 任何 blocked 项都写清负责人、外部条件和到期时间，不能从分母消失。

---

## 10. 建议修复顺序与交付批次

### 批次 0：发布止血（必须先完成）

- [x] P0-01 后台定位状态机 + 异步竞态负控（本地分支完成，真机验收仍待外部闸门）。
- [x] P1-01 GET 认证策略、method 契约和 25 个 GET 的 auth/角色/scope 分类；并静态锁定高风险 `team-progress` 的登录身份与本场 leader/paid-member 归属门禁（本地分支完成，真实后端角色矩阵仍待外部闸门）。
- [ ] 关闭“已达到全按钮可用/卓越设计”的发布结论，直到动态矩阵回读完成。

### 批次 1：高代价写操作

- [x] 权益购买、核销、参与人保存/删除/默认已接 single-flight；创建型订单/地址使用 requestId + 后端唯一约束，既有核销原子幂等保持不变。
- [x] 支付轮询增加 per-request timeout、总 deadline 和 unknown 终态。
- [x] 本轮全部 checkout consumer 接入 destroy/abort。

### 批次 2：状态一致性与隐私

- [x] 本轮命中的首展双请求已去重，易乱序列表增加 request generation/abort。
- [x] 达人搜索旧响应隔离。
- [x] `selectedAddress` 改为按账号分区的 ID，并与服务端列表求交。
- [x] login payload 先完整校验再落地，role cache 与 session 同步失效。
- [x] `safeUserMessage` 在 transport 入口统一净化原始错误消息。

### 批次 3：网络、上传与配置治理

- [x] 统一请求增加默认 12s timeout、显式只读 retry 与 abort 控制器。
- [x] 上传并发池、per-file/total timeout、abort 与精确失败项已落地。
- [x] API/base image URL 与资产 allowlist 收口单一配置，删除死 `amapWebKey` 与本轮命中的硬编码远程资源；多环境隔离仍列外部发布治理项。

### 批次 4：全按钮与 UI/UX 证据

- [ ] 建 action ledger 并把行为清单转成可执行测试。
- [ ] 修复截图身份隔离：guest 必须同时清 session、userInfo/nickname/avatar、role cache、页面组件快照；写后从页面真实文案/身份态回读。
- [ ] 为隐私授权建立受控 fixture：每张状态前显式结算 pending resolver，禁止一次匿名态把后续 200 张都重定向到隐私页。
- [ ] DevTools 启动器按 project 建单实例/单端口租约；逻辑层未响应要明确失败，不能并行冷启同一项目制造假故障。
- [ ] 跑完 235 个可操作状态的当前截图终闸；任何 state-unmet/同屏/缺图都修或明确 blocked。
- [ ] 首页首屏把“用户现在能做什么”提升到品牌 hero 之前或同屏可见。
- [ ] 统一 error/retry/unknown/empty 的组件、文案和下一步。

### 批次 5：真机与体验版

- [ ] 微信 iOS + Android：读屏、200% 文本、灰度、触控热区、胶囊、安全区、键盘、返回。
- [ ] 相机、定位、后台定位、扫码、分享、支付、订阅消息逐项走允许/拒绝/取消/失败。
- [ ] We 分析/`wx.getPerformance`：按平台、机型、网络、入口报告启动、首屏、路由和核心交互 P50/P90/P95。
- [ ] 体验版接测试后端完成 player/merchant/club 核心旅程；上线后再做生产只读回读。

---

## 11. 已驳回或降低的静态命中

为避免报告把扫描噪声冒充缺陷，本轮保留以下判决：

- WXML 扫描初见 47 个“缺 handler”：43 个来自 include 后由宿主页提供，4 个来自 ECharts 动态 vendor 绑定，均驳回。
- 10 个空 handler 多数用于 `catchtap` 吸收冒泡或 sheet 遮罩；不能仅凭空函数判死按钮。`merchant/index.onScrollReachBottom` 仍应结合列表是否分页再复核。
- 动态 URL `/api/coop/finance` 命中来自通用 `_leave(url)` 参数，不是硬编码坏路由。
- `lint:requestbody` 的 4 个 WARN 是通用 wrapper/扫码动态 URL；最终鉴权由 `request-client` 注入，不能据此宣称 RequestBody 缺 header。GET 鉴权问题是另一条真实根因。
- `preflight:release` 报“6 处 console.log”来自 6 行已注释代码（含 ECharts 注释），不是运行时日志泄露；应修正 preflight 的注释屏蔽逻辑，而不是把 WARN 当产品缺陷。
- `amapWebKey` 虽为空，但运行时代码未引用；它是死配置/误导风险，不是当前地图功能直接失败证据。
- 未发现提交的 API secret、private key 或明文密码；`project.config.json` 的 appid 不是 app secret。

---

## 12. 现有良好控制

- request client 已有单航班重登、401 只重试一次、abort 后屏蔽迟到回调、只对显式只读请求 retry。
- 主报名/复付流程已有 checkout 状态机、防重和支付后终态核验；创建报名带 `requestId/quoteSign`。
- `analytics.js` 对 token、坐标、地址、手机号、姓名、keyword/content 做键和值双重脱敏。
- 登录不再持久化 OpenID；显式退出会清 Storage 和 roleGuard。
- `location-manager.js` 已有坐标范围、取消静默、拒权引导和失败反馈；问题在于部分页面没有复用它。
- `app.json` 已声明隐私检查、定位用途与相关私有 API。
- 运行时 API 基址为正式 HTTPS，未发现源码中的 localhost 或明文 HTTP 生产命中。
- 5 个自动 retry 均为商家首页只读拉取，未发现写请求声明自动 retry。

这些控制说明项目已经具备较好的工程基础；本报告的 No-Go 不是否定已有工作，而是说明仍存在能突破发布硬门禁的具体缺口。

---

## 13. 外部门禁与不能自行宣称的结论

以下均未在本轮执行，必须单独报告，不能被本地测试覆盖：

- 微信开发者工具截图 ≠ iOS/Android 真机。
- 模拟 fixture ≠ 真实测试后端，更不等于生产权限、owner、资金和幂等。
- 本地 HEAD ≠ 已上传体验版/正式版。
- API 返回 200/前端 toast ≠ 业务事实已落地；写操作必须从目标系统重新读取。
- Apple/微信官方规范研究 ≠ 产品已符合无障碍或性能标准。

最终发布门禁应逐项留下：代码 SHA、体验版版本、后端 SHA、测试账号/角色、设备/系统/微信版本、网络条件、步骤、预期、实际、截图/录屏、后端回读和 blocked 原因。
