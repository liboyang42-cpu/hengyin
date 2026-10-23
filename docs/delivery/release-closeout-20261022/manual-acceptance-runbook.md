# 发布人工验收 Runbook（Lane MR1，实施文档一 §9）

> 用途：owner 一次坐下来，按顺序跑完城瘾小程序本次发布的 **WeChat DevTools + 真机** 人工验收。
> 本文件只是步骤说明，**本 lane 没有执行其中任何一步**，没有连任何环境，没有改代码。
> 公开仓卫生：本文件不写任何真实手机号、账号 id、主机地址、appid、密钥。下文的 `<…>` 都是占位符，回填版本放在私有位置。

## 0 来源与口径

| 简称 | 来源（分支 → 文件） | 用到的内容 |
|---|---|---|
| AC1 | `feature/lane-ac1-p0-chains-20261022` → `acceptance-p0-backend.md`（PR #497） | P0 场景 → 真库 IT → CI；§5 缺口；§7 每卡真机项 |
| AC2 | `feature/lane-ac2-p1-chains-20261022` → `acceptance-p1-backend.md`（PR #499） | P1 36 卡 → 测试 → CI；BUG-AC2-01；「仅真机」 |
| AC3 | `feature/lane-ac3-w0-p2-20261022` → `acceptance-w0-p2.md`（PR #500） | W0 + P2 卡 → 测试 → CI；BUG-AC3-1；未决 |
| R1 | `feature/lane-r1-release-prep-20261022` → `release-steps.md`、`migration-ledger.md`（PR #493） | 冻结 SHA、manifest、导出、体验版 → 提审 → 发布三份回执 |
| D | master → `lane-d.md` §4 / §7 | 自动云草稿 DevTools 14 步、F-1 / O-2 / #6 模板绑定 |
| 各 lane | master：`lane-a/b/b2/c/e/f/h2/h4/h5.md`；`integrate/closeout-batch2-20261022`：`lane-w3/w4/w6/w10b.md`、`integration-batch2.md`；`feature/lane-l21-leftovers-20261022`：`lane-l21.md`；`feature/lane-u1-verify-20261022`：`lane-u1.md`；以及 W1/W2/W5/W7/W8/W9/W10a/W10c/W11 各 PR 描述 | 「需要人工 / DevTools / 真机」清单（见第 3 节） |

**卡片清单口径（重要）**：Doc 1 §9.1 的 78 张卡（W0 7、P0 25、P1 36、P2 7、收尾 3）原文是**私有**清单，仓库里没有。本 runbook 的卡号按下面规则落地，owner 跑之前请拿私有清单对一遍号：
- **P1（36）**：直接用 AC2 的卡号（C1–C7、D1–D9、M1–M6、P1–P10、PR02–PR05），一一对应。
- **W0 + P2（14）**：直接用 AC3 的卡号（13 张 + CX-19）。AC3 没有区分哪 7 张属于 W0、哪 7 张属于 P2，本文把 14 张放在最前面一起跑（W0 优先级最高，宁可提前）。
- **P0（25）**：AC1 按 22 个场景行组织，没有私有卡号。本文给出 `P0-01…P0-22`（= AC1 场景行）+ `P0-23`（需补供给恢复，AC1 §7 售后卡里单列的「需补」）+ `P0-24/25`（**未对号占位**，请 owner 从私有清单补）。
- **收尾（3）**：按 R1 §7 推断为「体验版 / 提审 / 发布」三份独立回执，`END-1…END-3`，待 owner 确认。

**CI 缩写**（沿用 AC1–AC3）：BT = `ci.yml` Backend tests（MySQL 8.0.45 真库 IT，`assert_it_ran.py` 回读 skipped=0）；FG/FUNDS = `funds-mysql-it.yml`（50/9/30 fail-closed）；RS = `real-storage-it.yml` 手动批次；CA = `chain-acceptance.yml` 手动；GP = `gameplay-http-e2e.yml` 手动；MU = 小程序单测分片；UT = Mockito/H2 单测；Fast gates = 静态门禁。

---

## 1 前置条件（全部打勾后才开始）

### 1.1 冻结候选

- [ ] 候选 SHA：`<CANDIDATE_SHA>`（公开仓最终提交），`git rev-parse <CANDIDATE_SHA>:chengyinhub-xcx` = `<XCX_TREE_HASH>`。
- [ ] 该 SHA 上 CI 全绿（R1 §1 manifest `--lookup-ci`；`LOOKUP_FAILED` 不等于绿）。
- [ ] RS 批次对冻结 SHA 手动复跑：至少 `p0-acceptance-ac1`（UTC + Asia/Shanghai 两档），以及 AC2/AC3 用到的 RS 批次（`port-f3-evidence`、`extra-storage`、`port-prelaunch-69` 等）。记 run id。
- [ ] 测试后端运行的 jar 内嵌 `git.commit.id` = `<CANDIDATE_SHA>`（R1 §4 同口径）。读不到构建号就**写明读不到**，不要当作一致。
- [ ] 本轮验收中发现的问题只记录，不在候选上改；要改就换新 SHA，从头再跑受影响的卡。

### 1.2 测试后端：只用回环，绝不连生产

- [ ] 测试后端（测试 VPS 上的迁移预览构建）经 SSH 端口转发到本机：`http://127.0.0.1:<PORT>`。**不使用生产 API，不连生产库**（AGENTS.md「Never」）。
- [ ] 回读后端横幅：`GET http://127.0.0.1:<PORT>/` 返回 RuoYi 欢迎页；测试库是测试库（库名 / 数据特征只读核对，回执里不写主机）。
- [ ] 测试库已执行候选所需的迁移（R1 `migration-ledger.md` 的执行序，尤其 `member_profile_version_20261022`、`roam_share_version_20261022`）。这是**测试库**，生产库迁移另走 R1 §2/§5 与 chengyin-db-migrate 只读预检。

### 1.3 DevTools 副本：必须写死回环 API

背景：`chengyinhub-xcx/utils/config.js` 的 `resolveRuntimeConfig` 在 develop / trial 下读 `ext.json` 的 `apiEnvironments`，**没配就回落生产 API**（2026-08-25 裁决，写请求会真落生产库）；而 `normalizeApiBaseUrl` 只接受 `https://`，所以 `http://127.0.0.1` 不能靠 ext.json 配进去。做法沿用 lane-d §7.1：

1. `git archive <CANDIDATE_SHA> chengyinhub-xcx | tar -x -C <临时目录>`。**不要**用 DevTools 打开仓库 worktree 或任何旧导出目录。
2. 只在临时副本里把 `utils/config.js` 的 `API_BASE_URL` 和 develop 分支返回的 `apiBaseUrl` 都写死为 `http://127.0.0.1:<PORT>`；`project.config.json` / `project.private.config.json` 设 `urlCheck:false`。副本改动**不得**带回仓库。
3. 每次编译后、每张卡开始前在 Console 断言：
   ```js
   getApp().globalData.siteBaseUrl === 'http://127.0.0.1:<PORT>'   // 必须 true，否则立即停
   ```
4. 推荐：在 app service 里包一层 `wx.request`，记录 url / method / 回包码，并**拦截任何非 `127.0.0.1:<PORT>` 的请求**（lane-d §7.1 做法；不要用 `mockWxMethod('request')`，它会丢 `res.data.data`）。每张卡的证据里附「非回环请求 = 0」。
5. 需要假定时器 / 模拟丢包的步骤，在证据里标 `SIMULATED`，与真实复现分开计。

### 1.4 真机：体验版也会回落生产

- 真机连不到本机回环。真机项只能用**候选 SHA 干净导出的体验版**（R1 §6），且 owner 必须在私有 ext 配置里给 `apiEnvironments.staging` 配上测试后端的 HTTPS 地址。
- [ ] 每台真机首次进入时，打开调试（vConsole）读 `getApp().globalData.siteBaseUrl`，确认是测试后端而**不是生产**。读不到或是生产，就停止所有写操作类真机步骤（下单、核销、退款、注销）。
- 例外：真实微信支付 / 退款对账、`getPhoneNumber` 计费等只能在正式环境做的项，不在本 runbook 的卡里跑，归入第 4 节 owner 外部任务，单独授权、单独留回执。

### 1.5 身份（全部在测试库；回执里只写代号）

| 代号 | 身份 | 用于 |
|---|---|---|
| U1 | 玩家主账号（真实微信号 A） | 绝大多数玩家卡 |
| U2 | **第二个真实微信身份**（微信号 B，不是改 `getUserID` 的模拟） | 跨账号、换号、双账号同意台账、队伍隔离 |
| CL | 俱乐部主理人（可与 U1 同号，但需已过主理人审核） | 俱乐部、导演台、广播 |
| MA | 商家主账号 | 商家节点、核销、装修、AI 形象 |
| MS | 同一商家下的店员账号（第二个真实微信号） | 双店员同时核销、店员越权 |
| MB | 另一家商家主账号 | 跨商家越权（可选，没有就标 NOT_RUN） |
| DA | **可删除的测试账号**（专门用来注销，跑完即弃） | 注销、重新注册隔离 |
| AD | 测试后台（RuoYi 管理端）账号 | 驳回 / 审核 / 举报处置 / 撤销核销 |

### 1.6 设备与渠道

- 设备：**iOS 真机 1 台 + Android 真机 1 台**（双端各跑一次标「双端」的卡）；双店员 / 双终端卡需要第三台设备或 DevTools + 真机组合。
- 渠道：WeChat DevTools（稳定版，副本见 1.3）；体验版二维码（1.4）；DevTools 真机调试（只在测试 HTTPS 后端可达时）；测试后台网页；测试库**只读** SQL 回读；订阅消息 / IM 到达。
- 网络：DevTools Network throttling（Slow 3G / Offline）；真机弱网用飞行模式切换。

### 1.7 证据约定

- 每张卡一个目录 `<私有证据目录>/<卡号>/`：截图（双端分开）、DevTools Network 导出或请求日志、必要时测试库只读 SQL 回读结果。
- 结果只用：`PASS` / `SIMULATED-PASS` / `FAIL` / `NOT_RUN（原因）` / `BLOCKED（决策）`。不能用后一步结果冒充前一步。
- 证据里出现的手机号、openid、真实 id 在提交任何公开文档前一律打码。

---

## 2 逐卡清单（按优先级）

列说明：
- **已自动证明**：AC1–AC3 记录的测试与 CI job（只列主证据，完整引用见对应 AC 文档）。
- **人工步骤**：只做自动化证明不了的部分（UI、真机、真实外部通道）。
- **身份/设备**：见 1.5 代号；`DT` = DevTools 副本，`iOS` / `And` = 真机，`双端` = iOS + Android 各一次。
- **类**：`A` = auto-only, spot check（自动化已完整覆盖，人工只扫一眼页面）；`T` = 需 DevTools；`R` = 需真机；`2` = 需第二个真实身份（U2 / MS / MB）；`X` = 被产品 / owner 决定阻塞。

### 2.1 W0 + P2 组（14 张，AC3；先跑）

| 卡 | 已自动证明 | 人工步骤 | 身份/设备 | 通过标准 | 证据 | 类 |
|---|---|---|---|---|---|---|
| CX-11 我的模板编辑保存 | `v6b-template-edit-save-singleflight` 等 4 个 MU；后端 `TemplateEditOwnershipTest`（BT） | 1 我的模板 → 详情 → 编辑 → 改一处 → 保存；2 返回详情；3 Slow 3G 下连点保存 3 次 | U1 · DT + 真机 1 端 | 详情显示新内容；连点只出现一次结果面板、一次写请求 | 截图 + Network 写请求计数 | T R |
| CX-12 我的玩法草稿删除 / 主题详情刷新 | MU 3 个；**新增** `TemplateDraftDeleteOwnerAcceptanceTest`（BT） | 1 我的玩法 → 删除 → 取消（无请求）→ 再删 → 确认；2 Offline 下拉刷新；3 俱乐部主题详情 Offline 刷新 | U1 · DT | 弹窗文案 / 视觉正确；取消 0 请求、确认 1 次；刷新失败 toast 可见且保留旧内容 | 截图 + Network | T |
| CX-16 | **未映射**：编号在仓库与 GitHub 均无定义 | owner 先从私有清单给出卡义 | — | — | — | X |
| CX-19 两套等级体系 | 无（按指令跳过） | 等产品决定后再定步骤 | — | — | — | X |
| CX-31 路由清单 | `nav-route-exists`、`backend-miniprogram-route-exists`、**新增** `ac3-cx31-backend-slash-route-exists`（MU） | 1 真机点开一条订阅消息、一张分享卡、一张 IM 卡的落地页（含 IM 列表页、商家入驻页）；2 每个落地页用系统返回手势返回 | U1、MA · 双端 | 所有落地页能打开，返回不白屏 / 不卡死 | 双端截图 | R |
| SC-CL-APPLY 主理人资质申请 | `club-apply-state-contract`（MU）；`ApiClubBecomeLeaderRequiredFieldsTest`、**新增** `ApiClubBecomeLeaderCertImagesAcceptanceTest`（BT，DEFECT 记录） | 1 第 4 步上传资质图时断网 → 失败 → 删除 → 按钮恢复；2 两张失败删一张仍禁用；3 上传中删除前面的失败图（lane-e 6–8） | U2（未申请过的号）· DT + 真机 1 端 | 失败标记位置正确，按钮可用态正确；提交后 `certImages` 为空 | 截图 + 请求体 | T R · **BUG-AC3-1 待 owner 定** |
| SC-CL-APPROVE 驳回重提 / 同意补传 | `club-create-state-contract`、`usage-consent-app`（MU）；`ClubRejectedResubmitTest` 等（BT） | 1 AD 驳回 → 小程序「我的申请」看驳回原因 → 重新提交；2 lane-e 步骤 1–4：DevTools Network 数 `/consents` 次数与 requestId | U1、U2、AD · DT | 回执「已重新提交，等待审核」；同号重登 0 次 `/consents`；补传 requestId 不变；U2 的 requestId 不同 | 截图 + Network + Storage 截图 | T 2 |
| SC-CL-ENTRY 俱乐部入口 | `v4-club-entry`、`talent-club-list-state-contract`（MU） | 1 冷启动点俱乐部 tab；2 Offline 冷启动首载 | U1 · DT | 首载失败显示错误态而不是空态 | 截图 | T |
| SC-DEREG-01 注销状态 | `v4-dereg-status-sheet`、`deregister-consent-gate`（MU）；后端 8 类 + `AccountDeregistrationRaceMySqlIT`（BT） | 与 P0-20/21/22 合跑（见 2.2） | DA · 真机 | 同 P0-20 | 同 P0-20 | R |
| SC-PRIV-01 首启同意闸（**卡义为推断**） | `usage-consent-app`（MU）；`ConsentRecordServiceImplTest`（BT） | 1 清缓存冷启动 → 微信原生 `onNeedPrivacyAuthorization` 弹窗；2 拒绝 → 逐个点首页、俱乐部、我的 | U1 · 双端 | 未同意不发登录、不发非公开读请求；拒绝后各入口降级不崩 | 双端截图 + DevTools Network | R · 卡义待 owner 确认 |
| SC-TPL-OLD-BACKEND 新小程序对旧后端 | `v4-tpl-old-backend`（MU）；Fast gates | 只有在保留旧后端实例时才跑：对旧后端真连，触发 adopt-draft | U1 · DT | 明确拒绝文案 + 「去我的玩法核对」跳转；不新建普通草稿 | 截图 | A · 无旧后端时标 NOT_RUN |
| SC-TPL-OLD-SAVE-ROLLBACK | `LegacyChoiceCompletionRollbackAcceptanceTest`（UT）；`AdvancedGameRuntimeMySqlIT#legacyFrozenBranchCommitsOnceAndInvalidOutcomeRollsBack`（BT） | 无人工项（AC3：「无」） | — | — | CI run id | A |
| SC-UI-DATE-COUNT 站数与日期一致 | `sc-ui-date-count-cross-page`（3 时区）等（MU）；`PublicTopicStationCountMySqlIT`（BT） | 1 同一主题在主题卡 / 向导 / 统计 / 场次页各看一次站数与日期；2 真机切到非东八区时区再看 | U1 · DT + 真机 1 端 | 各页站数一致，日期按中国日历日一致 | 截图并排 | T R |
| SC-UI-PROJECT-CARDS 项目卡按身份路由 | `v4-club-project-cards`（MU） | 访客 / 成员 / 主理人三种身份各点一次俱乐部详情项目卡 | U2（访客）、U1（成员）、CL · DT | 访客进不了管理面 | 截图 | T 2 |

### 2.2 P0 组（25 张，AC1）

通用：P0 全部「后端半边」已在真 MySQL 上证明（AC1 §2/§3：5 个新类 13 条进 BT + RS `p0-acceptance-ac1` 两档时区；PR #497 BT job / RS run 均绿）。人工只看 UI、真机与外部通道（AC1 §7）。

| 卡 | 已自动证明 | 人工步骤 | 身份/设备 | 通过标准 | 证据 | 类 |
|---|---|---|---|---|---|---|
| P0-01 合法码核销 | **新增** `P0RedemptionChainMySqlIT`（BT, RS）；`GameSessionScanLegalMySqlIT`（BT）；`ExploreAllianceRedeemRealDbIT`（FG） | 1 U1 打开票码；2 MA 真机摄像头扫码；3 看动态码刷新节奏 | U1、MA · 双端 | 商家端「成功」提示；玩家端票状态刷新为已核销 | 双端截图 + 测试库 `registration` 只读回读 | R |
| P0-02 错码 / 伪造 / 过期 | 同上新增用例 + `groupCodeLegalWrongCancelled…`；`ApiVerifyGroupCodeTest`（UT） | 1 扫过期动态码（等过期）；2 扫非本商家码 | MA · 真机 | 过期 / 无效提示文案正确，不出现成功态 | 截图 | R |
| P0-03 已取消订单 / 场次不可核销 | **新增**整单 + 团码已取消用例（BT, RS）；`pendingManualRefundCaseFreezes…`（FG） | 扫一张已取消订单的码；扫一个已取消场次的团码 | U1、MA · 真机 | 显示「已取消」类提示；不落核销 | 截图 | R |
| P0-04 撤销核销 | **新增** `reversedTicketClearsExactlyOnce…`（BT, RS）；`ExploreScanReplayRealDbIT`（FG） | 1 AD 后台撤销 → 二次确认 → 填原因；2 U1 刷新票 | AD、U1 · DT + 真机 | 二次确认与原因必填；玩家端回到「未核销」 | 截图 + 刷新时机记录 | T R |
| P0-05 两名店员同时核销 | **新增** 20 轮屏障并发（BT, RS）；团码双线程（BT） | MA、MS 两台真机同时扫同一码；弱网下连点确认 | MA、MS · 2 台真机 | 恰好一台成功，另一台「已核销」；连点不重复提交 | 两台截图同框 / 时间戳 | R 2 |
| P0-06 B 读改 A 的订单 / 票 | **新增** `P0CrossAccountMySqlIT.userBCannot…OrderAndTicket`（BT, RS） | U1 把订单详情 / 分享链接发给 U2，U2 打开 | U1、U2 · 真机 | U2 看到空态或无权文案，**不闪现** U1 数据 | 录屏 | R 2 |
| P0-07 B 读 A 的核销记录 | **新增** `merchantBCannotReadMerchantARedemptionRecord`（BT, RS） | MB 打开 MA 的核销记录链接（如可构造） | MA、MB · DT | 无权文案；无数据闪现 | 截图 | T 2（无 MB 标 NOT_RUN） |
| P0-08 B 操作 A 的草稿 | **新增** `userBCannotRestore…Draft`（BT, RS） | U1 建草稿 → 退出 → U2 登录同设备 → 打开草稿箱 / 编辑页 | U1、U2 · 真机 | U2 看不到 U1 草稿、状态胶囊、恢复提示 | 录屏 | R 2 |
| P0-09 B 操作 A 的俱乐部数据 | `ClubRoleHttpMySqlIT`（BT）；CRM / 治理单测（UT） | U2 打开 CL 的俱乐部管理页链接 | CL、U2 · DT | 管理面不可达，无闪现 | 截图 | T 2 |
| P0-10 活动删除 / 发布状态 IDOR | `ActivityPublishRoleGateTest` 等（UT，仅单测） | MS 尝试编辑 / 下架 MA 名下俱乐部活动 | MA、MS · DT | 被拒文案，活动不变 | 截图 + 测试库只读回读 | T 2 |
| P0-11 城市跑恢复竞态 | **新增** `P0CityRunChainMySqlIT.twoClubTerminals…`（BT, RS） | 两台终端同时点「恢复」 | CL、MA · 2 台真机 | 后到一方看到「站点已在接待」提示 | 两台截图 | R 2 |
| P0-12 NPC 节点 | `NodeNpcMapperMyBatisIT`（BT）；`NodeNpcStationScopeAcceptanceTest`（UT） | 真机进入有 NPC 的节点 | U1 · 双端 | NPC 语音 / 形象加载正常；未审核 NPC 不出现 | 双端截图 | R |
| P0-13 冻结后无状态变化 | **新增** `finishedSessionIsFrozen…`（BT, RS） | 导演台结束 → 玩家端刷新 | CL、U1 · 真机 | 玩家按钮置灰、提交入口消失 | 截图 | R |
| P0-14 锁价 / 需补供给 | **新增** `P0FreeExploreSupplyGateMySqlIT`（BT, RS）；`chain_merchant_club.py`（CA） | 对缺供给主题走锁价页 | CL · DT | 「需补供给」阻断提示正确 | 截图 | T |
| P0-15 结账（到 prepay） | `RegistrationFundsMybatisIntegrationTest`（FG，微信 mock）；`chain_player.py`（CA，桩支付） | 结账页看金额 / 积分展示，停在支付弹窗前。**真实支付与回调见第 4 节 EXT-03** | U1 · 真机 | 金额、积分与后端一致 | 截图 | R · 真实支付归外部 |
| P0-16 退出 / 0 元 | `ActivityCancelFreeOrderMybatisIT`（FG，主理人侧）；玩家侧只有单测（AC1 §5-2） | 玩家侧完整走一遍 0 元单退出 | U1 · 真机 | 不出现支付或退款入口；名额与资格恢复 | 截图 + 测试库只读回读 | R |
| P0-17 取消通知 | `RefundNotificationMybatisIntegrationTest`（FG, RS）；`ClubEventTransferCancellationMySqlIT`（FG） | CL 取消活动 → U1 真机看订阅消息与站内信 | CL、U1 · 真机 | 消息到达且点击跳转正确 | 截图 | R |
| P0-18 部分章节售后 | `PartialChapterAftercareTest`（UT）；`RefundRetryAndRemainderMySqlIT`（FG, RS）；建单本身无真库（AC1 §5-1） | 人工完整走一遍部分章节售后建单 | U1、CL · DT | 文案正确；无打款 / 进度变化 | 截图 + 测试库只读回读 | T |
| P0-19 替换锁定 | `LockedSupplyReplacement*`（UT，仅单测，AC1 §5-1） | 已锁价主题，商家提交加价替换 | MA、CL · DT | 被拒文案；通知 / 页面一致 | 截图 | T |
| P0-20 注销只清本人 | **新增** `P0DeregistrationMySqlIT`（BT, RS）；`AccountDeregistrationRaceMySqlIT`（BT） | 1 DA 发起注销 → 二次确认（短信 / 微信）；2 看 7 天冷静期展示并撤回一次；3 再次发起，测试库把到期时间调到过去（测试库，owner 操作）→ 等任务执行 | DA · 真机 | 冷静期展示与撤回可用；到期后旧登录态被踢下线（Redis 会话撤销只有 Mockito 证明） | 录屏 + 测试库只读回读 | R |
| P0-21 财务记录保留 | 同上用例（BT, RS） | 接 P0-20：后台 / 只读 SQL 看 DA 的订单、退款行仍在且 PII 已抹 | AD · 测试库 | 行数不变，PII 已抹 | 只读 SQL 结果（打码） | A |
| P0-22 重新注册隔离 | 同上用例（BT, RS） | 接 P0-20：同一微信号重新进入 | DA · 真机 | 新账号，看不到历史订单 / 报名 | 截图 | R |
| P0-23 需补供给恢复 | 仅 Mockito（AC1 §5-1 `topicSupplyAutoOnlineBlockReason`） | 补齐供给后看主题是否恢复上线 / 可锁价 | MA、CL · DT | 恢复路径文案与状态正确 | 截图 | T |
| P0-24 （未对号） | — | owner 从私有 §9.1 补卡义 | — | — | — | X |
| P0-25 （未对号） | — | 同上 | — | — | — | X |

### 2.3 P1 组（36 张，AC2）

通用：27 张 COVERED-NEW（PR #499 BT job 5 个新类 30 条全绿）、6 张 COVERED、2 张 PARTIAL、1 张 BUG。标 `A` 的卡人工只在 DevTools 里打开对应页面扫一眼渲染、按钮可用态和文案。

| 卡 | 已自动证明 | 人工步骤 | 身份/设备 | 通过标准 | 证据 | 类 |
|---|---|---|---|---|---|---|
| C1 入会申请通过 | `ClubJoinGovernanceMySqlIT.c1_*`（BT） | 抽查：CL 通过一条申请，成员数 +1 | CL · DT | 页面计数正确 | 截图 | A |
| C2 入会申请驳回 | `c2_*`（BT）；`PublicClubResponsePrivacyTest`（UT） | 抽查：驳回原因只对申请人可见 | CL、U2 · DT | 他人看不到原因 | 截图 | A |
| C3 封禁 / 移出 | `c3_*` 4 条（BT） | 抽查：封禁后被封号的俱乐部页表现 | CL、U2 · DT | 只影响本俱乐部 | 截图 | A |
| C4 客户详情可见性 | `ClubCrmCrossClubIsolationTest`（UT）；`ClubCrmCheckinDetailMyBatisTest`（真库，仅到店明细） | 另一俱乐部主理人打开本俱乐部客户详情 | CL、第二主理人（U2 申请后） · DT | 不可见；**PARTIAL**：跨俱乐部真库隔离缺 | 截图 | T 2 |
| C5 分享 | `ApiClubLifecycleTest` 等（UT）；`v4-club-share`（MU） | 1 真机分享俱乐部卡给 U2；2 U2 点开落地页 | CL、U2 · 双端 | 卡片唤起、路径、落地页渲染正确；公开关时非成员看不到 | 双端截图 | R 2 |
| C6 举报 | `c6_*`（BT） | 抽查：举报入口与回执 | U2 · DT | 回执文案 | 截图 | A |
| C7 自由探索收件箱 | `CoopClubApplyInboxMySqlIT`（RS）等 | 收件箱接受 / 拒绝一条申请（`/api/coop/handle` 真库 CAS 缺） | CL · DT | 状态更新正确、不重复 | 截图 | T |
| D1 准备 | `CityDirectorConsoleMySqlIT.prepare…`（BT） | 抽查导演台准备页 | CL · DT | 渲染 / 按钮态 | 截图 | A |
| D2 开局 | 同上（BT） | 抽查开局按钮态 | CL · DT | 同上 | 截图 | A |
| D3 名单 | 同上（BT） | 抽查名单无隐私字段 | CL · DT | 无手机号等字段 | 截图 | A |
| D4 全员广播 | `broadcastReachesOnlyPaidActiveMembersInScope`（BT） | 发一条广播，U1 / U2 真机看 IM 与订阅消息到达（IM 在 IT 中是 mock） | CL、U1、U2 · 真机 | 已付费在队成员收到，未付费不收到 | 截图 | R 2 |
| D5 定向广播 | 同上 + `sameRequestRetryResends…`（BT） | 按队伍发一条，另一队成员不应收到 | CL、U1、U2（分两队） · 真机 | 只有目标队收到 | 截图 | R 2 |
| D6 现场事件 | `pauseAndResume…`（BT） | 暂停时展示暂停原因、预计恢复时间 | CL、U1 · DT + 真机 | 文案与时间正确 | 截图 | T R |
| D7 结束 | `GameSessionCityEndMySqlIT`（BT） | 抽查结束后回顾页 | CL · DT | 渲染 | 截图 | A |
| D8 冲突规则 | `CityDirectorConsoleMySqlIT`、`FreezeGameHttpE2eIT`（BT / GP） | 抽查；「同一玩家不得同时在两场城市局」**待产品确认**（AC2） | CL · DT | 已有规则行为正确 | 截图 | A X |
| D9 存活范围 | `broadcastReaches…`（BT） | 抽查：退队后不再收广播 | CL · DT | 同自动化 | 截图 | A |
| M1 自由探索节点编辑 | `FreeExploreNodeSupplyMySqlIT.m1_*`（BT） | 抽查节点编辑页 | MA · DT | 渲染 / 审核重置提示 | 截图 | A |
| M2 节点取消 | `m2_*`（BT）；`v4-free-node-cancel`（MU） | 编辑节点后点取消 | MA · 真机 | 前端取消不提交（0 写请求） | 截图 + 请求日志 | R |
| M3 供给 | `m3_*` 4 条（BT）；V1 招募 PERK 路径只有 mock | 抽查入驻 / 撤回 | MA · DT | 渲染 | 截图 | A |
| M4 条款 | `m4_*`（BT） | 抽查双方条款一致 | MA、CL · DT | 条款一致 | 截图 | A |
| M5 城市局节点就绪 | `prepareReadyRosterStart…`（BT） | 抽查商家台就绪页 | MA · DT | 渲染 | 截图 | A |
| M6 城市局节点暂停 | `pauseAndResume…`（BT） | 抽查商家台暂停 | MA · DT | 渲染 | 截图 | A |
| P1 城市局进度 | `playerViewIsScopedToOwnTeamAndOwnAccount`（BT） | 抽查玩家进度页 | U1 · DT | 渲染 | 截图 | A |
| P2 队伍间隔离 | 同上（BT） | 抽查 | U1 · DT | — | 截图 | A |
| P3 账号间隔离 | 同上（BT） | 抽查 | U1 · DT | — | 截图 | A |
| P4 NPC 玩家流程 | `NodeNpcMapperMyBatisIT`、`NpcChatUserDataMyBatisIT`（BT）；租约生命周期只有 mock | 真机与 NPC 对话（V1 `chatOn()` 写死关闭时只核对入口隐藏） | U1 · 真机 | AI 回复与内容安全正常，或入口按开关隐藏 | 截图 | R · V1 开关 owner 定 |
| P5 念头领取 | `JourneyThoughtProgressMySqlIT.claim…`（BT） | 真机完成节点后领取念头 | U1 · 真机 | 只领一次 | 截图 | R |
| P6 念头步数基线 | `sameDayLowerReading…`（BT） | 真机微信运动授权；跨午夜再读一次 | U1 · 真机 | 同日基线不回退，次日全额计 | 截图 | R |
| P7 念头步数达标 | `reachingNeedMarksDone…`（BT） | 真机步数达标后看完成态 | U1 · 真机 | 完成一次，幸运上限只加一次 | 截图 | R |
| P8 漫游结束 | `RoamSessionWriteMybatisIntegrationTest`（FG） | 真机漫游结束：定位授权、后台定位停止 | U1 · 双端 | 结束后状态栏定位图标消失 | 双端截图 | R |
| P9 暂停 / 恢复后读回 | `pauseAndResume…`（BT）；漫游恢复（FG） | 抽查刷新后状态 | U1 · DT | 与库一致 | 截图 | A |
| P10 断线续玩 / 结局 | `JourneyFullFlowMySqlIT` 等（BT / FG / RS） | 抽查：杀进程重进续玩 | U1 · DT | 续到原进度 | 截图 | A |
| PR02 按用途撤回 | `PrivacyVisibilityExportMySqlIT.revokingRoamLocation…`（BT） | 抽查隐私页撤回某一用途 | U1 · DT | 其他用途不变 | 截图 | A |
| PR03 位置可见性 | `RoamPresenceRevokeDurabilityMySqlIT` 等（FG） | 真机打开 / 关闭「在场可见」，撤回定位授权 | U1、U2 · 真机 | 关闭 / 撤回后 U2 看不到 U1 在场 | 截图 | R 2 |
| PR04 历史可见性 | `shopVisitorAvatarsHonour…`（BT）；**BUG-AC2-01**：撤回 `roam_location` 后到访头像仍露出 | 修复落地前：真机复现并记录；修复后：U1 撤回 → U2 看店铺「谁在这儿打过卡」 | U1、U2 · 真机 | 修复后撤回者不出现；「关闭在场可见是否也隐藏」**待产品定口径** | 截图 | R 2 X |
| PR05 导出 / 删除申请 | `exportNeverCrossesMembers…`、`deregistrationApplyIsIdempotent…`（BT）；`PersonalExplorationExportMySqlIT`（RS） | 真机导出文件下载 / 保存；注销申请短信 / 客服环节（与 P0-20 合跑） | U1、DA · 双端 | 文件可保存且只含本人数据 | 截图 | R |

### 2.4 收尾组（3 张，按 R1 §7 推断，待 owner 对号）

| 卡 | 已自动证明 | 人工步骤 | 身份/设备 | 通过标准 | 证据 | 类 |
|---|---|---|---|---|---|---|
| END-1 体验版 | R1 manifest、`wechat-release-contract.js` 回读主包字节 | 1 干净导出（R1 §6）；2 上传体验版；3 双端真机冒烟（第 3 节 S 组冒烟单） | owner · 双端 | 版本号 / SHA / tree hash 一致；主包 ≤ 2 MB（以上传结果为准）；冒烟全 PASS | 版本号、二维码截图、冒烟结果 | R |
| END-2 提审 | — | 提交审核 | owner | 提审版本号 = 体验版 | 提审单号与时间 | R |
| END-3 发布 | — | 审核通过 → 发布 → 线上版本号回读 | owner · 真机 | 线上版本号正确 | 审核记录、发布时间 | R |

---

## 3 DevTools / 真机专项（来自各 lane 报告，不属于 78 卡但发布前要跑）

按「同一页面 / 同一身份」合并，能和第 2 节同一时段跑的已注明。

### 3.1 包 D 自动云草稿（lane-d §4 / §7，PR #463）

lane-d §7 已在 DevTools 跑过 14 + 1 项（13 PASS / SIMULATED-PASS，F-2 已修复复验）。候选上只需：

| # | 步骤 | 身份/设备 | 通过标准 |
|---|---|---|---|
| D-a | 候选副本上回归 lane-d §4 第 1、2、3、5、8、12 步（真实复现项） | U1 · DT | 同 lane-d §4 原文 |
| D-b | **#6 模板绑定不回退**（NEEDS_USER_REAL_DEVICE）：用至少有一个玩法模板的作者号；新建主题 → 一章一站 → 绑模板 X → 等「已存云端」→ Slow 3G → 换绑 Y，保存中再换回 Z | U1（有模板）· DT 或真机弱网 | 站点最终 Z；最后一次 update 的 `chapters[].nodes[].templateId` = Z；重开仍 Z |
| D-c | **#11 真实双账号**：A 编辑后 1 秒内退出、B 登录 | U1、U2 · 真机 | A 的保存没发出；B 看不到 A 的稿 / 状态 |
| D-d | **#7 真杀进程**：编辑 → 飞行模式 → 划掉小程序 → 关飞行模式重开 | U1 · 真机 | 同一草稿、同一 requestId、「我的项目」只有一份 |
| D-e | **#14 真并发**：两台设备同时编辑同一草稿 | U1 · 2 台设备 | 后保存端只提示一次「自动保存未成功」，之后不再自动发请求，手动保存可用 |
| D-f | **F-2 复验**：保存成功 → 关闭 → 重开 | U1 · 真机 | 10 秒内 0 次 create/update；胶囊直接「已存云端」；不提示「已恢复上次本地草稿」 |
| D-g | **F-1（后续项，仅记录）**：发布在途时改标题（真机一般被遮罩挡住） | U1 · DT | 记录现状；口径与 §5.1 第 4 步不一致，留后续 |
| D-h | **O-2（后续项，仅记录）**：发布被拒时错误条「这次还没有保存」与胶囊「已存云端」冲突 | U1 · DT | 记录现状，文案待定 |

### 3.2 其他 lane 的人工清单

| 来源 | 项 | 身份/设备 |
|---|---|---|
| lane-a（俱乐部会费开关） | 测试环境关 `feature.flag.clubMembership`：PAYING 账单「继续支付」提示暂未开放且不调起支付；可关单；账单详情 / 财务列表可开。打开开关后「继续支付」正常调起（真机）。owner 决定：开关关时复用已存 `payParamsJson` 是否可接受 | CL · DT + 真机 · X |
| lane-b（社区先发后审） | 切到 POST_REVIEW 后真机发带图帖，确认微信回调命中（`POST_MEDIA_RISKY_HIDDEN` 或 PASS；inbox state = DONE 而非 IGNORED_STALE）——依赖第 4 节 EXT-02 回调 token | U1 · 真机 |
| lane-b2（评论删除占位） | 5 步：有回复的根评论删除占位不可交互；无回复不占位不占分页；回复对象被删提示并保留草稿；屏蔽 / 静音用户回复不可见；弱网连点只生成一条 | U1、U2 · DT |
| lane-e（同意台账 / 资质图） | 8 步（1–4 已并入 SC-CL-APPROVE，6–8 已并入 SC-CL-APPLY）；第 5 步：退出后 `roam_memory_v1:<id>:*` 仍在 | U1、U2 · DT |
| lane-f（同行者榜 / IM 未读） | 两名玩家先后到达结局，同行者榜先通关 / 用时短者在前；A 给 B 发消息，B 未读 +1，后台判违规后 B 角标立即 -1，判不成立恢复 | U1、U2、AD · DT |
| lane-h2 | 1 退款取消三处各一次只发一次 cancel-refund，确认框开着换号不发，弱网 UNKNOWN 刷新（真机，测试订单）；2 退款订阅（B9 已由 L21 接入，见下）；3 我的收藏取消与换号（DT）；4 play / roam 锁屏持续定位、onUnload 后定位图标消失、拒权后再授权（真机）；5 屏幕常亮（真机）；6 活动下架后帖子不显示活动卡（DT） | U1、U2 · 双端 |
| lane-h4 | 1 首页城市定位用途说明与拒绝（真机）；2 `scope.userLocation` 说明文案；3 到点打卡精度 > 80m 提示（户外 / 室内各一次）；4 IM 发送中杀进程 / 断网不重复发、换号无残留；5 路线改私密后 IM 卡显示「路线已不可用」；6 广场分页不重复、换号无残留；7 徽章墙；8 测试库注销到期只清本人地址；9 `im_notification_intent` 迁移与 `sys_job` 启用由 owner 操作 | U1、U2 · 双端 |
| lane-h5 | 1 searchmap 无定位兜底提示与 cover-view 深浅色；2 官方线路入口（落地后）；3 #15 回看项（继续漫游条、集邮册分页、NPC 清除对话、队伍举报半屏、积分明细、主题状态键面板）；4 #14 回看项（装修页返回层级、认领 sheet、无图打卡码复制、俱乐部置顶公告）；5 #8 资金链只观察：日票结束后不展示核销码、免费报名不进付款 | U1、MA、CL · DT + 真机 |
| lane-l21 | B9：退款确认手势内同步弹 `refund` / `refundRejected` 订阅，**不挡退款**（真机）；fabu「创建玩法」连点只跳一次；发布前 `publish-check`；本章完成半屏「这一章」 | U1 · 真机 + DT · B9 与 H2/H5 的 NEEDS_OWNER 结论冲突，owner 留意 |
| lane-w3（资金加固） | 领队改集合时间 / 地点：已售时被拒；成功面板文案按 `organizerNotified=false` / `LOGGED_ONLY` 显示（前端文案交接给 club topic-detail owner，未改前记录现状） | CL · DT |
| lane-w6（可见性） | 引用不可用灰标；IM 路线卡不可用提示；编辑活动合作者增删与深浅色提示文案 | U1、CL · DT |
| lane-w10b（回读与审核回执） | 「和 TA 聊聊」断网后回读、无第二个 `merchant-chat` POST；简易创建断网 →「查看结果」回读；换头像后审核回执文案；AI 形象「已提交审核」→ 过审后可见文案 | U1、MA · DT |
| batch2 集成 | W7、W9、W10a、W11 均未做模拟器 / 真机截图，需在 DevTools 复核（见 3.3）；W8 传感器挑战后端与小程序必须同发，奖励封顶待 owner 定，需真机 | 见 3.3 |

### 3.3 W 系 PR 描述中的人工项

W1 #470、TF1 #503 没有人工项；W10c #481 只有 fabu altText 输入框的后续项（归 Lane D）。

| PR / lane | 人工项 | 身份/设备 | owner 决定 |
|---|---|---|---|
| #476 W7 play 运行时 | 未做模拟器 / 真机截图：play 页暂停 / 中断后玩家文案、归零视图倒计时（暂停期间客户端倒计时照走，恢复后跳回顺延值，记录现状） | U1、CL · DT + 真机 | NEEDS_OWNER_APPROVAL：暂停顺延归零 / 挑战截止、取消或退队不写 HP_ZERO（改变比赛结果） |
| #478 W8 传感器挑战 | 真机看 quiethold 校准文案与 photocheck 兜底文案；后端 jar 与小程序必须同发 | U1 · 双端 | 奖励封顶对存量主题的影响；主题通关奖是否一并封顶 |
| #480 W9 前端收尾 | DevTools 目视：新的设置半屏、台账「联系客服」按钮、漫游文案；搜索价格上限 ¥0 | U1 · DT | — |
| #482 W11 接管组 | DevTools 目视：收藏页说明行、隐私页状态行、漫游清空确认弹层；结果未知时按原请求回读 | U1 · DT | 「仅清显示」选项（产品） |
| #484 W10a 俱乐部月历 / 口碑 | 真机或模拟器复核 `club/detail` 月历场次、商家口碑服务端筛选、期次空态原因 | CL、MA · DT + 真机 | 预检时确认 `cms_activity.club_id` 索引 |
| #477 W10b | 见 3.2 lane-w10b 清单 | — | 两个迁移必须先于 jar 落库；个人头像送检口径问题待核实 |
| #473 W2 内容安全 | 无单独清单（随 3.2 lane-b / w10b 的审核回执项一起看） | — | 收款二维码是否加上传归属校验 |
| #472 W4 同意记录 | 无单独清单（随 SC-CL-APPROVE / lane-e 一起看） | — | R2（退出时是否丢弃未同步漫游进度） |
| #471 W5 审核正确性 | 无小程序清单 | — | 落种迁移的生产执行（只读预检 + 确认） |
| #479 W3 资金加固 | 见 3.2 lane-w3 | — | 资金路径，合并前 owner 复核 |
| #485 U1 | 无小程序清单 | — | savepoint 内死锁被吞（资金路径）是否修 |
| #501 L21 | 见 3.2 lane-l21 | — | B9 接受或整体 revert；#35 CL6 |

---

## 4 外部 / owner 任务（Doc 1 §9.2；不在 DevTools 里跑）

Doc 1 §9.2 原文是私有清单；下列按任务书与各 lane 报告整理，owner 按私有清单补全。每项单独授权、单独留**私有**回执，不写进公开仓。

| # | 项 | 为什么不能自动化 | owner 动作 | 回执 |
|---|---|---|---|---|
| EXT-01 | **ACTION-01 支付证书轮换** | 涉及商户证书 / 密钥 | 在商户平台轮换，更新私有配置，旧证书失效后验证回调验签仍通过 | 轮换时间、生效确认（不含证书内容） |
| EXT-02 | **内容审核回调 token** | 微信后台配置 + 线上回调 | 配置 / 核对消息推送 token 与加解密方式；发一条测试内容确认回调命中（lane-b） | 回调命中记录 |
| EXT-03 | **真实支付 / 退款对账** | CI 只到 prepay 意图边界（AC1）；不得用假支付 | 小额真实下单 → 支付回调 → 核销 / 退款 → 与商户平台账单对账 | 订单号（打码）、对账结果 |
| EXT-04 | **`getPhoneNumber` 真机** | 付费接口、只能真机（AGENTS.md；错误码 80058） | 真机绑定一次手机号，验证空号保护与稳定版 token | 截图（号码打码） |
| EXT-05 | 订阅消息模板 / 到达 | 依赖微信后台模板 | 核对模板 id 与字段；真机收到退款、取消、驳回通知 | 截图 |
| EXT-06 | 生产迁移 | 生产 DDL 只能 owner 执行 | R1 §2/§3/§5：只读预检 → 逐个批准 → 备份 → 按序执行；`20261013` checksum 先对账 | R1 回执 |
| EXT-07 | jar 部署与回读 | 生产部署是 owner 操作 | R1 §4/§5：jar sha256 与内嵌 commit = 候选 SHA；健康检查 | R1 回执 |
| EXT-08 | 暂停中的定时任务 | 启用即改线上行为 | 决定是否启用 `aiQuotaRecoveryTask`、`roamRewardDeliveryTask`、`im_notification_intent` 相关 `sys_job`（lane-h4 / h5） | 决定记录 |
| EXT-09 | 内容安全 provider / 腾讯地图 key | 真实外部服务 | 核对生产配置已开（lane-h3 CL-39；L10 地图配置提示） | 配置确认 |

### 4.1 待 owner / 产品决定（会阻塞对应卡）

| 决定 | 阻塞 |
|---|---|
| CX-16 卡义 | CX-16 |
| CX-19 两套等级体系 | CX-19 |
| SC-PRIV-01 卡义确认 | SC-PRIV-01（已按推断写步骤） |
| BUG-AC3-1：`/become-leader` certImages 加服务端媒体校验？ | SC-CL-APPLY 的服务端兜底 |
| BUG-AC2-01 修复 + 「关闭在场可见是否隐藏头像」口径 | PR04 |
| D8：同一玩家能否同时在两场城市局 | D8 |
| P0-24 / P0-25 卡义 | P0-24、P0-25 |
| 收尾 3 张是否 = 体验版 / 提审 / 发布 | END-1…3 |
| lane-a 开关关时复用已存预支付参数 | lane-a 项 |
| B9 退款订阅（L21 已接入 vs H2/H5 NEEDS_OWNER） | lane-l21 / h2 项 |

---

## 5 汇总

统计口径：78 张卡；一张卡可同时属于多个「需要」列，所以后四列之和不等于 78。`auto-only` 与「需 DevTools / 需真机」互斥。

| 组 | 卡数 | auto-only, spot check | 需 DevTools | 需真机 | 需第二身份 | 被决定阻塞 |
|---|---|---|---|---|---|---|
| W0 + P2（AC3） | 14 | 2 | 7 | 6 | 2 | 2（CX-16、CX-19） |
| P0（AC1） | 25 | 1 | 8 | 15 | 6 | 2（P0-24、P0-25 未对号） |
| P1（AC2） | 36 | 21 | 3 | 13 | 6 | 2（D8、PR04） |
| 收尾（R1 §7） | 3 | 0 | 0 | 3 | 0 | 0 |
| **合计** | **78** | **24** | **18** | **37** | **14** | **6** |

- 完全没有可跑步骤、只能等决定的卡：4 张（CX-16、CX-19、P0-24、P0-25）。D8、PR04 有可跑部分，另带待定口径。
- 需第二身份的 14 张里，需要两台真机同时操作的：P0-05（MA + MS）、P0-11（双终端）。
- 第 3 节（lane 专项）与第 4 节（外部任务）不计入上表。

### 建议的单次执行顺序

1. 1.1–1.7 前置全部打勾（尤其 1.3 断言和 1.4 体验版 `siteBaseUrl` 读回）。
2. DevTools 段（只用 U1 / U2 / CL / MA / AD）：W0+P2 组的 T 卡 → P0 的 T 卡 → P1 的 A 卡抽查与 T 卡 → 3.1 D-a → 3.2 / 3.3 的 DevTools 项。
3. 真机段（iOS + Android，体验版指向测试后端）：单身份 R 卡 → 双身份 R 卡（U1+U2、MA+MS、CL+MA 双终端）→ 3.1 D-b…D-f → 3.2 真机项。
4. 注销段最后跑（DA，跑完即弃）：P0-20/21/22、SC-DEREG-01、PR05。
5. 汇总 FAIL / BLOCKED，交 owner 决定是否换 SHA；然后 END-1…3。
