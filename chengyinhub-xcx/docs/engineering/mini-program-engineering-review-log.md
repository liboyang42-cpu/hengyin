# 小程序工程问题日志 (mini-program-engineering-review-log)

> 单一真源:仅主 Agent 写入(文档 §6.2)。审计子 Agent 返回发现,主 Agent 去重定级后录入。
> 状态:Fixed / Open / Deferred / Blocked / Won't-fix / Cross-system。Deferred/Blocked/Won't-fix 均视为未关闭(§12)。
> 维度:A=架构可维护 · C=正确稳定 · P=性能资源 · S=安全隐私合规 · T=测试发布就绪

## Loop 1 已修复(4 项 · 证据见文末验证段)
| ID | 维度 | 描述 | 等级 | 修复 | 状态 | 验证 |
|---|---|---|---|---|---|---|
| ENG-001 | C | infomationdetail 分享 path 指向未注册 `/pages/infoDetail/infoDetail` | P1 | 改为 `/subpackageA/pages/infomationdetail/infomationdetail` | **Fixed** | automator callMethod onShareAppMessage 返回正确注册路径(带负控);lint 0 error |
| ENG-004 | C | project 话题搜索 `bindconfirm="onSearchTopic"` 无方法→回车不搜(无搜索按钮,input 仅存词) | P2 | 改为 `bindconfirm="onSearch"`(已存在处理器,读 topicKeyword+getList) | **Fixed** | lint warn 消除;页面 smoke 0 console error |
| A3-2 | S/P | play onHide 未拆定位监听→离页 GPS 持续+arrive 可能后台触发 | P2 | onHide 补 `offLocationChange+stopLocationUpdate+_watching=false`(镜像 onUnload);onShow→loadData→initLocation 守卫重注册 | **Fixed(代码)** | onHide runtime 不抛;**GPS 停/恢复需真机验证** |
| ENG-003b | A | userinfo 头像 `goUserInfo` 死绑定 | P3 | 去除死绑定(无视觉变化) | **Fixed** | lint warn 消除;页面 smoke 0 error |

## Blocked — 高风险,需用户授权后才能改(文档 §5/§15)
| ID | 维度 | 描述 | 等级 | 决策点 |
|---|---|---|---|---|
| ENG-008 | C | 冷启动 firstLogin 走 login() 而非 ensureSession():①每次冷启动都网络登录、恢复的缓存会话形同虚设 ②弱网/瞬时失败时 clearSession() 清掉仍有效的会话→有效用户被登出本次会话(自愈但影响体验)。ensureSession 成带单测的生产死代码(A1-1∪A2-2 同根) | P2 | **缓存短路(honor 缓存 token)vs 每次刷新**?若保留每次刷新→至少改成仅真正鉴权拒绝(noData/服务端401)才 clearSession,networkFail/wxLoginFail 保留会话 |
| A2-1 | C | 401 静默重登时 param.complete 触发两次(首次带 401 body、早于重试完成),~20+ 页面用 complete 复位 loading/分页→重登窗口 spinner 提前关/闪空态 | P2 | 是否改网络封装:重登分支吞掉本次 complete(标 `_suppressComplete`)或 complete 仅最终一跳触发 |
| A2-3 | C | 重登失败 complete/fail 顺序反常;`_retried` 挂调用方 param 对象(复用同一 param 多次请求时残留→后续真 401 不再重登) | P3 | 是否改网络封装:用 send 内部局部标志替代 param._retried |
| ENG-009 | A | `isAuthenticated` 只写不读:Phase1.2 引入的独立认证信号全仓零消费,页面仍靠 getAuthorization() 判身份(A1-3,A2 交叉确认) | P3 | 让 auth-gated 页面真读它,或删字段消除悬空契约 |
| A3-5 | S | 后台定位代码层合规(主动触发+用途弹窗+同意留痕);剩余在提审侧:需账号具备后台定位资质+审核说明 | — | 用户侧动作(非代码);提审前确认资质 |

## Deferred — 预存债/需更大改动或测量(本轮外科范围外)
| ID | 维度 | 描述 | 等级 | 原因 |
|---|---|---|---|---|
| A1-2 | A | fabu/index.js god 页 3703 行、data 独占~950 行 | P2 | 重构需计划+授权;非本轮外科改 |
| A3-1 | P | 主包源码 apparent ~2.19MB(images 1.01MB)逼近 2MB 硬上限 | P2 | 需 DevTools「详情→本地代码」编译体积权威测量;超限则升 P1 发版阻断 |
| A2-4 | C | activity-picker 预览图上传绕过 upload-client、无 401 处理 | P3 | 非核心增强;已有降级守卫 |
| A1-4 | A | utils/lottie-loader.js 整模块无人引用 | P3 | 预存死代码,§3 不擅清 |
| A3-4 | A | 3 注册页无入站导航(topic/pricing、topic/settlement、subP3/growth) | P3 | 预存死页,§3 不擅删 |
| A1-5 | A | globalData 静态块(app.js:178)被 onLaunch 整体覆盖,userInfo 死字段 | P3 | 预存债 |
| A1-6 | A | onLaunch 内 wx.hideTabBar() 冗余裸调(app.js:29) | P3 | 预存债(极小) |
| ENG-006 | A | app.js:50 注释掉的 localhost siteBaseUrl 死行 | P3 | 预存债 |
| ENG-007 | P | app.js:82-84 `logs` 数组每次启动 unshift 无上限 | P3 | wx 模板遗留,预存债 |
| A1-7 | T | getRequestErrorMessage/normalizeTemplateDetail 无单测;门禁未接 CI | P3 | 补测可做(加测);CI 属④控制面 |
| A3-3 | T | 3 处裸用废弃 getSystemInfoSync(square/list:510、fabu:750、topic/index:821) | P3 | 需 context7 核实 getWindowInfo 字段覆盖(safeAreaInsets)后再改→L2 |

## Won't-fix — 死代码/不可达/按设计(无用户影响)
| ID | 维度 | 描述 | 等级 | 理由 |
|---|---|---|---|---|
| ENG-002 | A | merchantapply2 多选类目 modal 3 绑定无方法 | P3 | `showMultiSelectModal` **本页(merchantapply2)js 无赋值**→modal 永不渲染,不可达死代码(注:data 按页隔离,该 var 在 fabu 页另有用,不影响本页判定) |
| ENG-003a | A | userinfo `bmClose` 弹窗遮罩无方法 | P3 | `bmShow` **本页(userinfo)js 无赋值**→弹窗永不渲染,不可达死代码(注:data 按页隔离,该 var 在 searchmap/detail 等页另有用) |
| ENG-005 | A | 3 占位入口(mytemplate/merchant-marketing/talent-list)"即将开放" | P3 | 产品刻意占位,非缺陷 |

## 五维评分(§12 公式:100 − P0×25 − P1×10 − P2×3 − P3×1,计未关闭)
> 主 Agent 自评基线,待独立 Review Agent 复核(§6.3)。每问题只计一维,同根因不重复计。

| 维度 | 未关闭构成 | 分 | ≥95? |
|---|---|---|---|
| A 架构可维护 | P2×1(A1-2)+ P3×9(ENG-002/003a/005/009/A1-4/A3-4/A1-5/A1-6/ENG-006) | 100−3−9=**88** | ❌ |
| C 正确稳定 | P2×2(ENG-008/A2-1)+ P3×2(A2-3/A2-4) | 100−6−2=**92** | ❌ |
| P 性能资源 | P2×1(A3-1)+ P3×1(ENG-007) | 100−3−1=**96** | ✅* |
| S 安全隐私合规 | 提审资质软项(A3-5,非代码缺陷) | ~**95** | ✅ |
| T 测试发布就绪 | P3×2(A1-7/A3-3) | 100−2=**98** | ✅ |

\* P 维 96 依赖 A3-1 主包体积经 DevTools 测量确未超限;超限则 A3-1 升 P1、P 维直接失败。

> **独立 Review Agent 已复核(§6.3)**:4 项 Fixed 全部确认真关闭、外科级、无业务语义变化、无新风险(A3-2 定位恢复链完整,核心玩法不坏);Won't-fix 3 项判定成立;五维评分独立复算一致(A88/C92/P96/S95/T98,无重复计/错维/漏计);无 P0/P1 残留;**一条待定 P1 风险:A3-1 主包体积须 DevTools「详情→本地代码」实测,>2MB 则升 P1 阻断发版并拖垮 P 维**。

**结论:无已确认 P0/P1(ENG-001 已修)→ 本轮不自动失败;但 A(88)、C(92)未达 95 → Loop 未完成。**
A/C 的欠分主要来自 **Blocked 高风险项(需用户授权)** 与 **预存债(外科原则默认不擅清)** —— 这正是文档 §15「需要用户授权」情形,须用户拍板后方能在 L2 收口。

## Loop 2 更新(用户授权后:①登录缓存短路 ②改网络封装 ③清低风险死代码)

### Loop 2 新增 Fixed(10 项)
| ID | 维 | 修复 | 状态 | 验证 |
|---|---|---|---|---|
| ENG-008 | C | firstLogin 改走 `ensureSession()`(honor 缓存 token,免每次冷启动网络登录);clearSession 仅在 `reason==='noData'`(真鉴权拒绝)触发,networkFail/wxLoginFail 保留缓存会话 | **Fixed** | ensureSession 缓存短路已有单测(session-manager.test.js:58);node --check;独立复审 |
| A2-1 | C | request-client:401 静默重登窗口内 `param.complete` 只触发一次(`suppressComplete`),最终响应而非 401 body | **Fixed** | 新单测 `A2-1 401 重登成功…`(TDD RED→GREEN) |
| A2-3 | C | `_retried` 改为 send 内部 `_state`(不污染调用方 param);重登失败分支 fail 先于 complete、各一次 | **Fixed** | 新单测 `A2-3 …异步/_retried 不污染`(TDD RED→GREEN) |
| ENG-009 | A | 删除 set-never-read 的 `isAuthenticated` 死字段(app.js decl+firstLogin+reLogin) | **Fixed** | grep 全仓 0 读取;node --check |
| A1-4 | A | 删除 `utils/lottie-loader.js` 死模块 | **Fixed** | grep 全仓 0 引用 |
| A1-5 | A | 删除 globalData 静态块死字段 `userInfo` | **Fixed** | grep 全仓 0 读取 |
| A1-6 | A | 删除 app.js onLaunch 冗余裸 `wx.hideTabBar()` | **Fixed** | node --check |
| ENG-006 | A | 删除 app.js 注释掉的 localhost siteBaseUrl 死行 | **Fixed** | preflight INFO 4→3 |
| ENG-002 | A | 删除 merchantapply2 不可达多选 modal 块(fabu 同名 modal 独立、不受影响) | **Fixed** | grep 确认 modal 方法仅 fabu 页有;lint warn 消除 |
| ENG-003a | A | 删除 userinfo 不可达 bmShow 弹窗块(预览脚本 target 是 topic 非 userinfo) | **Fixed** | lint warn 消除;smoke 0 error |

### Loop 2 重要复核:A3-4「死页」**驳回删除**(改判 Won't-fix / 非缺陷)
- 删前 grep 复核发现 3 页**均不可安全删**:`topic/settlement/settlement` 是**支付结算页**,有契约单测(payment-readiness-contract.test.js)+ 共享 checkout-workflow 不变量(§4 禁改支付,删会挂测试);`topic/pricing/index`、`subP3/growth/index` 被 dev 预览脚本引用(WIP 特性,非死代码)。审计 Agent「无入站导航=死」为误判(无运行时 nav ≠ 可删)。**保留三页,A3-4 改判为非缺陷**——这正是文档"删除前必先核验目标"的价值。

### Loop 2 后五维评分(主 Agent 自评,待独立复审)
| 维 | 未关闭构成 | 分 | ≥95? |
|---|---|---|---|
| A 架构可维护 | P2×1(A1-2 god页,Deferred)+ P3×1(ENG-005 占位,按设计) | 100−3−1=**96** | ✅ |
| C 正确稳定 | P3×1(A2-4 activity-picker 上传绕过,Deferred) | 100−1=**99** | ✅ |
| P 性能资源 | P2×1(A3-1 主包体积,待测)+ P3×1(ENG-007 logs) | 100−3−1=**96** | ✅* |
| S 安全隐私合规 | A3-5 提审资质(非代码软项) | ~**95–100** | ✅ |
| T 测试发布就绪 | P3×2(A1-7 补测/CI、A3-3 废弃 API) | 100−2=**98** | ✅ |

\* **A3-1 是唯一 P1 风险**:主包编译体积须 DevTools「详情→本地代码」实测,>2MB 则 A3-1 升 P1、P 维直接失败。**发版前必测,尚未测。**

**结论(自评):五维全部 ≥95。但发版就绪仍卡在 A3-1 主包体积实测(用户侧 DevTools 一步)。**
若五维经独立复审确认达标,按文档 §14 还需连续 3 轮独立验证(静态门禁/运行恢复/安全兼容)方可宣告 Completed。

## Loop 1 验证证据
- **自动化**:preflight-release ✅ 0 阻断;test:unit ✅ 176/0;lint:ui ✅ **0 error**(基线 1)+ warn 9→7(余 7 均 Won't-fix 记录);`git diff --check` clean;`node --check` 改动 JS 通过。
- **运行时**(automator connect 到本 worktree 自动化端口,身份门控 + 负控):ENG-001 callMethod onShareAppMessage=正确注册路径;4 受影响页 reLaunch smoke 各 0 console error;A3-2 onHide 运行不抛。
- **真机缺口**:A3-2 的 GPS 实际停采/恢复、ENG-004 端到端(输入+回车→列表刷新)需真机/独立 automator 复验。
- **范围**:仅改 4 源文件(4 增 4 删);DevTools 触碰的 project.config.json 已还原;automator 装为已声明 devDep(node_modules 已 gitignore,未入提交)。
