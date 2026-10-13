# 城瘾小程序架构改进 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改业务行为、不引入新框架的前提下，将城瘾原生微信小程序的会话、权限、业务流程、位置、分包和质量反馈收敛为可测试、可观测、可渐进迁移的模块。

**Architecture:** 保留原生 Page/Component 和现有后端接口，以“替换旧路径、不叠加第二套实现”为原则逐步迁移。先建立测试和指标基线，再深化会话与传输模块；随后收拢身份能力、发布/结算工作流和位置能力，最后依据真实包体数据调整分包。

**Tech Stack:** 微信原生小程序、CommonJS、glass-easel、miniprogram-automator、Node.js 内置 `node:test`、微信开发者工具 CLI。

---

## 一、目标结构

```text
chengyinhub-xcx/
├── app.js                         # 只保留应用生命周期与模块装配
├── utils/
│   ├── session/
│   │   ├── session-store.js       # 会话快照的内存/Storage 一致性
│   │   └── session-manager.js     # 登录、单航班续期、退出
│   ├── transport/
│   │   ├── request-client.js      # HTTP、错误归一化、401 重试
│   │   └── upload-client.js       # 图片/文件上传与认证
│   ├── identity/
│   │   └── identity-policy.js     # role、permission、usage、视角覆盖
│   ├── publish/
│   │   ├── publish-draft.js       # 草稿和字段归一化
│   │   ├── publish-validator.js   # 发布不变量
│   │   └── publish-workflow.js    # 提交状态机
│   ├── checkout/
│   │   ├── checkout-model.js      # 地址、票项、金额
│   │   └── checkout-workflow.js   # 创建订单、支付、结果处理
│   ├── location/
│   │   └── location-manager.js    # 用途、授权、精度、缓存、POI
│   └── observability/
│       ├── analytics-queue.js     # 单航班批量投递
│       ├── error-reporter.js      # 全局错误与 Promise 拒绝
│       └── performance-reporter.js# appLaunch/firstRender/包加载指标
└── tests/
    ├── unit/                       # 纯模块测试
    └── automator/                  # 开发者工具关键旅程测试
```

约束：这只是目标形态。每阶段只创建当期需要的文件，不一次性创建空目录或空模块。

## 二、总体完成标准

- 所有业务请求通过传输模块；页面不再拼接 `Authorization`。
- 同一时刻最多存在一个 `wx.login`/换 token 请求；并发 401 共享结果。
- `role`、能力和额度由身份模块解释；`debug_user_view` 不再改写业务身份。
- 发布和结算页面不再拥有跨页面业务不变量。
- 所有定位调用必须声明用途，统一处理授权拒绝和降级。
- 有开发版、体验版真机的启动性能基线和改造后对比。
- 登录、权限、发布、报名支付至少各有一条自动化冒烟路径。
- 每一阶段一个独立 feature 分支和独立提交序列，可单独回滚。

---

## Phase 0：冻结基线和建立安全网

**目标：** 在改变架构前固定当前行为、包体和关键接口清单。

**Files:**
- Create: `chengyinhub-xcx/tests/unit/smoke.test.js`
- Create: `chengyinhub-xcx/tests/automator/login-smoke.js`
- Create: `chengyinhub-xcx/docs/architecture/current-api-inventory.md`
- Modify: `chengyinhub-xcx/package.json`
- Modify: `chengyinhub-xcx/project.config.json`（仅在确认测试编译配置确有需要时）

- [ ] 从 `master` 创建 `feature/xcx-architecture-baseline`，确认当前工作区无本任务外改动被带入。
- [ ] 用开发者工具“代码依赖分析”和“代码质量”记录主包、每个分包、未使用文件和启动耗时截图。
- [ ] 分别在冷启动、热启动、弱网下记录首页可交互时间、首次请求完成时间和登录请求次数。
- [ ] 扫描 `app.sendRequest`、`wx.request`、`wx.uploadFile`，生成接口清单：路径、方法、是否认证、调用页面、错误码语义。
- [ ] 在 `package.json` 增加 `test:unit` 和 `test:automator` 命令；不新增依赖，单元测试使用 Node 内置 `node:test`。
- [ ] 添加最小单元测试，验证测试命令确实能失败和成功，避免“测试脚本始终退出 0”。
- [ ] 添加登录冒烟脚本：重新启动首页，等待 app ready，断言页面存在且没有无限 loading。
- [ ] 执行：`npm --prefix chengyinhub-xcx run test:unit`；期望全部 PASS。
- [ ] 执行开发者工具编译；期望无新增 warning/error。
- [ ] 提交：`test: 建立小程序架构改造基线`

**验收：** 后续每一阶段都有可比较的包体、启动、请求次数和冒烟结果。

---

## Phase 1：深化会话与传输模块（最高优先级）

**目标：** 解决假 ready、并发重登、认证头泄漏和请求旁路问题，不改变后端接口。

**Files:**
- Create: `chengyinhub-xcx/utils/session/session-store.js`
- Create: `chengyinhub-xcx/utils/session/session-manager.js`
- Create: `chengyinhub-xcx/utils/transport/request-client.js`
- Create: `chengyinhub-xcx/utils/transport/upload-client.js`
- Create: `chengyinhub-xcx/tests/unit/session-store.test.js`
- Create: `chengyinhub-xcx/tests/unit/session-manager.test.js`
- Create: `chengyinhub-xcx/tests/unit/request-client.test.js`
- Create: `chengyinhub-xcx/tests/unit/upload-client.test.js`
- Modify: `chengyinhub-xcx/app.js`
- Modify: `chengyinhub-xcx/utils/analytics.js`
- Modify: 当前自行传入 Authorization 的 20 个页面脚本

### 1.1 会话快照

- [ ] 先写失败测试：从 Storage 恢复完整会话；空字段不覆盖默认值；清空会话同时清内存与 Storage。
- [ ] 实现 `session-store.js`，只管理 `token/openId/userId/userType/role/avatar/nickname` 快照，不发网络请求。
- [ ] 执行单元测试，期望 PASS。
- [ ] 将 `app.js` 的七组 getter/setter 委托给 session store，保留旧方法作为临时兼容接口。
- [ ] 编译并运行登录冒烟，确认现有页面没有因兼容接口失效。
- [ ] 提交：`refactor: 收拢小程序会话存储`

### 1.2 单航班登录和续期

- [ ] 写失败测试：首次登录成功、正式版登录失败、开发版 mock、两个并发 refresh 只调用一次 `wx.login`。
- [ ] 实现 `session-manager.js`，内部维护唯一的 `loginPromise`；成功后原子替换会话快照，失败返回明确失败结果。
- [ ] 修正 app ready 语义：初始化完成和认证成功分开；正式版登录失败不能伪装成已认证。
- [ ] 保留 `useDevUser()` 且继续受 `isDevEnv()` 保护，不改变现有正确行为。
- [ ] 执行单元测试和登录冒烟。
- [ ] 提交：`fix: 统一登录状态与并发续期`

### 1.3 请求与上传

- [ ] 写失败测试：GET/POST 都自动认证；公共请求可显式关闭认证；401 并发共享 refresh；最多重试一次；业务错误和网络错误结构稳定。
- [ ] 实现 `request-client.js`，认证、基础 URL、错误归一化和 refresh 全部藏在模块内部。
- [ ] 写失败测试：图片/文件上传附带认证；解析非法 JSON 返回可识别错误；部分文件失败能结束 loading 并返回失败列表。
- [ ] 实现 `upload-client.js`，替换 `app.js` 两套上传循环。
- [ ] 迁移 `analytics.js`，不再直接调用 `wx.request`。
- [ ] 按页面逐个删除自定义 Authorization；每迁移 3—5 个页面执行一次编译和相关冒烟。
- [ ] `rg "Authorization.*getAuthorization|wx\\.request\\(" chengyinhub-xcx/pages chengyinhub-xcx/utils`；期望只剩登录适配器或明确允许的底层实现。
- [ ] 执行所有单元测试和登录/发布/支付冒烟。
- [ ] 提交：`refactor: 统一小程序请求与上传通道`

**验收：** 10 个并发 401 只产生一次换 token；页面代码不知道认证头；失败不会无限 loading；旧后端零改动。

---

## Phase 2：统一身份、能力和调试视角

**确认门：** 此阶段改变 RBAC 前端解释逻辑，开始编码前必须得到用户确认。

**目标：** 让后端 role/permission/usage 成为业务唯一真源，同时保留开发版视角切换。

**Files:**
- Create: `chengyinhub-xcx/utils/identity/identity-policy.js`
- Create: `chengyinhub-xcx/tests/unit/identity-policy.test.js`
- Modify: `chengyinhub-xcx/utils/roleGuard.js`
- Modify: `chengyinhub-xcx/app.js`
- Modify: `chengyinhub-xcx/pages/index/index.js`
- Modify: `chengyinhub-xcx/pages/shezhi/shezhi.js`
- Modify: `chengyinhub-xcx/pages/merchant/index/index.js`
- Modify: 其他直接比较 `role/user_type` 的页面

- [ ] 与后端确认角色集合、能力字段、额度 `null` 的语义、role 更新时机和缓存失效时机，并写入测试用例。
- [ ] 写失败测试：player/club/merchant/official；能力允许/拒绝；额度达到/未达到；无缓存时 fail-closed；开发视角只影响展示不改业务身份。
- [ ] 实现纯函数身份策略模块，输入后端快照和可选开发视角，输出展示身份与能力判断。
- [ ] 让 `roleGuard.js` 只负责拉取/缓存，所有解释交给 identity policy。
- [ ] 退出、重新登录、role-info 更新时统一清除或替换权限快照。
- [ ] 移除设置页切换视角时写 `user_type` 的行为，只保存 `debug_user_view`；正式版忽略该键。
- [ ] 逐个迁移首页、商户页和发布入口的散落判断。
- [ ] `rg "user_type|userType|role ===|role ==" chengyinhub-xcx/pages`，逐项确认剩余命中只用于展示或数据映射。
- [ ] 自动化验证玩家、俱乐部、商户三种快照的首页入口和发布守卫。
- [ ] 提交：`refactor: 统一小程序身份与能力判定`

**验收：** 同一身份在首页、个人中心、发布页和商户页的展示与操作权限一致；调试切换不污染 Storage 中真实身份。

---

## Phase 3：深化发布和结算工作流

**目标：** 从巨型页面中抽出稳定业务不变量，不重写 WXML/WXSS。

**Files:**
- Create: `chengyinhub-xcx/utils/publish/publish-draft.js`
- Create: `chengyinhub-xcx/utils/publish/publish-validator.js`
- Create: `chengyinhub-xcx/utils/publish/publish-workflow.js`
- Create: `chengyinhub-xcx/utils/checkout/checkout-model.js`
- Create: `chengyinhub-xcx/utils/checkout/checkout-workflow.js`
- Create: 对应 `chengyinhub-xcx/tests/unit/*.test.js`
- Modify: `pages/publish/fabu/index.js`
- Modify: `pages/publish/temp/index.js`
- Modify: `pages/publish/activity/index.js`
- Modify: `pages/settlement/settlement.js`
- Modify: `pages/topic/settlement/settlement.js`

### 3.1 发布

- [ ] 从三个发布页面列出字段、默认值、校验、接口 DTO 和差异矩阵。
- [ ] 只抽取至少被两个页面共享的规则；单页面 UI 行为继续留在页面，避免制造浅模块。
- [ ] 写校验测试：必填字段、时间范围、节点上限、图片/地点要求、后端 DTO 归一化。
- [ ] 写工作流测试：idle → validating → submitting → success/failure；重复点击只提交一次。
- [ ] 先迁移模板发布页，再迁移活动发布页，最后迁移 2526 行综合发布页。
- [ ] 每迁移一个页面执行截图回归：初始态、校验错误、上传中、成功、失败恢复。
- [ ] 提交：`refactor: 深化小程序发布工作流`

### 3.2 结算

- [ ] 比较两套 settlement 的地址、票项、金额、订单和支付差异，确定共享不变量。
- [ ] 写模型测试：数量边界、优惠、金额精度、地址选择、库存变化。
- [ ] 写工作流测试：创建订单失败不拉起支付；支付取消可重试；支付成功只上报一次；页面销毁后不再 setData。
- [ ] 两个页面改为消费同一 checkout model/workflow，保留各自显示差异。
- [ ] 真机验证 `wx.requestPayment` 的取消、失败和成功回调。
- [ ] 提交：`refactor: 统一小程序结算状态机`

**验收：** 页面脚本明显缩小；共享规则只有一个实现；业务规则测试无需启动开发者工具。

---

## Phase 4：建立合规感知的位置模块

**目标：** 所有位置调用都有明确用途、精度策略和拒绝降级。

**Files:**
- Create: `chengyinhub-xcx/utils/location/location-manager.js`
- Create: `chengyinhub-xcx/tests/unit/location-manager.test.js`
- Modify: 所有调用 `getLocation/choosePoi` 的页面和组件
- Review: `chengyinhub-xcx/app.json`

- [ ] 建立用途表：城市推荐使用模糊位置；到点打卡使用精确位置；发布选址由用户主动选择 POI。
- [ ] 写失败测试：首次用途说明、已授权、拒绝、系统定位关闭、超时、缓存命中、强制刷新。
- [ ] 实现用途驱动的位置模块，不在后台持续采集，不把拒绝当业务异常反复弹窗。
- [ ] 为推荐场景增加无位置降级；为打卡场景提供明确的设置引导；发布场景不自动读取位置替代用户选择。
- [ ] 逐个迁移调用点，并清除页面自己的授权状态机。
- [ ] 对照微信公众平台现网和提审版隐私保护指引，确认接口调用与声明完全一致。
- [ ] 真机验证首次授权、拒绝后重试、关闭系统定位、弱 GPS 四种情况。
- [ ] 提交：`refactor: 统一小程序位置与授权策略`

**验收：** `rg "wx\\.(getLocation|choosePoi)"` 只命中 location manager 底层适配器；每种位置用途都有测试和降级。

---

## Phase 5：可观测性和埋点可靠性

**目标：** 能回答“哪里慢、哪里崩、事件是否丢失”，并以数据验证架构优化。

**Files:**
- Create: `chengyinhub-xcx/utils/observability/error-reporter.js`
- Create: `chengyinhub-xcx/utils/observability/performance-reporter.js`
- Create: `chengyinhub-xcx/utils/observability/analytics-queue.js`
- Create: 对应单元测试
- Modify: `chengyinhub-xcx/app.js`
- Modify: `chengyinhub-xcx/utils/analytics.js`

- [ ] 写失败测试复现当前两个并发 flush 删除错误批次的问题。
- [ ] 实现单航班发送、成功后按事件 ID 删除、失败保留、批次上限和指数退避上限。
- [ ] 接入 `App.onError`、`App.onUnhandledRejection`，过滤 token/openId/手机号等敏感字段。
- [ ] 接入 `wx.getPerformance()`，采集 appLaunch、firstRender、evaluateScript、downloadPackage；低版本用 `wx.canIUse` 降级。
- [ ] 定义采样率和开发/体验/正式环境策略，禁止把全部日志无上限写入 Storage。
- [ ] 增加诊断关联 ID，使前端错误、请求错误和后端日志可串联。
- [ ] 验证离线积压、恢复网络、重复 flush、队列满和 Storage 写失败。
- [ ] 对比 Phase 0 基线，形成改造前后数据表。
- [ ] 提交：`feat: 建立小程序质量反馈闭环`

**验收：** 埋点不因并发丢批次；生产错误可定位到版本/页面/请求；启动优化有实际指标而非主观判断。

---

## Phase 6：依据真实数据调整分包

**确认门：** 分包结构属于 Ask first；必须先展示 Phase 0/5 的包体和启动数据并获得确认。

**目标：** 主包只保留 Tab 和首屏必需实现，低频业务按旅程加载。

**候选分组：**

- 主包：首页、广场、模板、达人、个人中心及其首屏必需模块。
- `subpackagePublish`：发布主题、活动、模板、标签和编辑器资源。
- `subpackageMerchant`：商户/俱乐部申请、资料、管理。
- `subpackageTrade`：报名、结算、订单、优惠券、提现。
- 保留并评估：`subpackageA`、`subpackageB`、`subpackageP3`。

- [ ] 使用开发者工具依赖分析确认候选页面是否引用跨分包资源或 JS。
- [ ] 统计每个候选分包的独立资源，优先迁移图片和大 JS，不为追求目录整齐迁移小页面。
- [ ] 检查 tabBar 页面始终留在主包。
- [ ] 一次只迁移一个业务旅程，更新所有 `navigateTo/redirectTo/reLaunch` 路径。
- [ ] 每次迁移运行路由完整性脚本，确认声明路由文件存在、无孤立页面。
- [ ] 检查 `preloadRule`：只有数据证明收益大于网络成本时才保留首页预下载资产包。
- [ ] 在 Wi-Fi、4G、弱网分别测冷启动和首次进入分包耗时。
- [ ] 检查分享卡片、扫码参数和历史收藏路径是否仍可到达。
- [ ] 提交：`perf: 按业务旅程优化小程序分包`

**验收：** 主包真实构建体积显著下降；首页 appLaunch/firstRender 不退化；首次进入分包有可接受 loading；所有分享和深链可用。

---

## 三、建议排期

| 周期 | 内容 | 风险 |
|---|---|---|
| 第 1 周 | Phase 0 基线 + Phase 1 会话存储 | 低 |
| 第 2 周 | Phase 1 登录、请求、上传迁移 | 中高 |
| 第 3 周 | Phase 2 身份能力 | 高，需确认 |
| 第 4—5 周 | Phase 3 发布与结算 | 中高 |
| 第 6 周 | Phase 4 位置 + Phase 5 可观测性 | 中 |
| 第 7 周 | Phase 6 分包 | 中，需确认 |

人员不足时不要并行 Phase 1、2、3；它们会同时修改 `app.js` 和核心页面。Phase 4 与 Phase 5 可在 Phase 1 稳定后并行。

## 四、明确不做

- 不迁移到 uni-app/Taro，不升级成 TypeScript 全量重写。
- 不改变后端认证协议或 RBAC 语义，除非另立后端任务并确认。
- 不引入 Redux/MobX 等全局状态库。
- 不为了缩行数机械拆函数；只有能集中不变量、提高局部性和测试杠杆率的模块才拆。
- 不删除 `useDevUser()`、`debug_user_view` 或项目明确保留的测试资源。
- 不在架构改造中顺带重做 UI、主题或文案。

## 五、每阶段发布检查表

- [ ] 相关单元测试 PASS。
- [ ] 微信开发者工具编译无新增 warning/error。
- [ ] 模拟器关键路径截图对比通过。
- [ ] 登录/权限/发布/支付相关阶段完成真机测试。
- [ ] 网络面板确认请求次数、认证头和 401 重试符合预期。
- [ ] 隐私接口与提审版隐私保护指引一致。
- [ ] 检查 `git diff`，无无关格式化、配置或用户改动。
- [ ] 每个提交只对应一个可描述、可回滚的行为变化。

