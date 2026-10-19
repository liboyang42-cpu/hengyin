# Claude 小程序长期任务清单（30 项）

> 2026-10-15 用户指示：三条开发线持续领取，做完一项开草稿 PR 即领下一项；**本阶段不做验证**（不等 CI、不做 VPS/开发者工具/真机），用户回来后统一验证。
> 规则仍适用：`TASKS.md`（含末节「用户决定」）、CY-COORD-20261015-v1、Codex `owned_files`（`docs/plans/mini-codex-cloud-20261015/tasks.json`）不可改；无迁移/新依赖/生产配置/鉴权与资金政策改变；会费、群聊、群投票不做；官方路线跟随暂缓。
> **2026-10-15 晚用户决定：不改现有主标题与现有 UI；新功能按现有 UI 做。第一批里改现有样式的项（08–11、13–15、24、29、30）作废。**
> 领取方式：分支 `claude/bl-NN-<短名>` 存在即视为已领取。按编号从小到大领第一个未领取项。先核实代码现状：已经做完、属于 Codex 文件、需要新接口/新表、或需要产品决定 ⇒ 在 PR #21 评论 `BL-NN BLOCKED: 原因`，推一个只含说明的空提交分支占位（防止重复领取），然后领下一项。

| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 01 | 漫游 POI 筛选 | 漫游地图加筛选半屏：半径（1/3/5 km）、只看未到过；本地持久化 `roam_filter_v1`（按账号）；半径通过现有 `/api/roam/pois` 的 radius 参数传，不改后端 | `pages/roam/**` 薄接线 + 新组件 |
| 02 | 漫游静止提示 | 站着不动（位移 < 3m 持续 60s）时 HUD 显示「原地停留中」，不改计时规则 | roam 新 util + HUD |
| 03 | 漫游离线提示 | 位置上报连续失败/断网时显示「网络断开，位置暂不同步」，恢复后消失 | roam |
| 04 | 积分明细筛选 | 积分明细页加「全部/获得/使用」切换（前端对一次全量列表筛选），月份分组 | `subpackageMember/points-history/**` |
| 05 | 收到的印章详情 | 印章册「收到的」点开详情半屏（来源、时间、照片），只用接口已有字段 | `components/cy/scene-roam-stamp-album/**` |
| 06 | 协办邀请卡片 | 官方活动收件箱里协办/履约邀请显示活动名、时间、角色说明，接受/拒绝后回读 | `pages/activity/official-inbox/**` |
| 07 | 勋章墙俱乐部分组 | 个人勋章墙把已获得的俱乐部勋章单独分组（仅当接口字段能区分；否则 BLOCKED 并写读合同请求） | `subpackageP3/**` 勋章墙 |
| 08 | 二级页导航统一·会员分包 | `subpackageMember` 里仍用左对齐大标题/自绘返回的二级页改为 `cy-nav-bar`（返回+居中标题+胶囊） | 不碰 Codex 订单/参与详情组件 |
| 09 | 二级页导航统一·俱乐部 | 同上，`pages/club/**` | |
| 10 | 二级页导航统一·商家 | 同上，`pages/merchant/**` | |
| 11 | 二级页导航统一·漫游分包 | 同上，`subpackageRoam/**`、`subpackageP3/**` | |
| 12 | 弹层统一·俱乐部 | 自绘弹层改用 `cy-sheet`（标题+关闭+底部按钮），`pages/club/**` | |
| 13 | 弹层统一·商家 | 同上，`pages/merchant/**` | |
| 14 | 弹层统一·发布 | 同上，`pages/publish/**`（不含 Codex 文件） | |
| 15 | 商家页无描边 | 商家/浅色页卡片、输入框去描边，灰底白卡，按 token；`pages/merchant/**` | |
| 16 | 官方活动页无描边 | 同上，`pages/activity/official-*` | 等 CL4 合并后再改 detail |
| 17 | 返回守卫·权益编辑 | `pages/merchant/decor/perks` 有未保存修改时用 `page-container` 拦截返回（照 decor 主页写法） | |
| 18 | 返回守卫·俱乐部编辑 | 俱乐部资料/主题编辑页同上 | |
| 19 | 返回守卫·券设置 | `subpackageMember/couponInfo` 等券编辑页同上 | |
| 20 | 图片预览收口·俱乐部 | `pages/club/**` 的 `wx.previewImage` 改用 `utils/media-preview.js` | |
| 21 | 图片预览收口·其余非 Codex 文件 | 其余页面同上（跳过 Codex 文件） | |
| 22 | 列表 key 修正 | 剩余可能重复的图片/标签列表 `wx:key="*this"` 改为稳定 key（跳过 Codex 文件） | |
| 23 | 定位兜底·其余页面 | 其余写死上海坐标的地图页改用 `utils/location/last-location.js` | |
| 24 | 四态统一·商家 | 商家页加载/空/失败/无权限统一用 `cy-state-shell`，状态名统一 | |
| 25 | 四态统一·俱乐部 | 同上，俱乐部页 | |
| 26 | 骨架屏补齐 | 无骨架的列表页（消息、勋章、积分明细、官方活动）补 `cy-skeleton` | |
| 27 | 空态文案诚实化 | 空态里写承诺/假引导的文案改为如实说明（先列清单再改，逐条说明依据） | |
| 28 | 漫游历史筛选药丸收编 | 漫游历史 `.hs-fp` 自绘筛选改用 `cy-tabs` chip 变体（组件注释已建议） | |
| 29 | 设计 token 回收·俱乐部 | `pages/club/**` wxss 硬编码颜色/字号/圆角改 token（DS 门基线内的项） | |
| 30 | 设计 token 回收·漫游 | 同上，`pages/roam/**`、`subpackageRoam/**` | |

每项 PR：草稿，合回 `claude/mini-ui-features-20261015`；正文首行 `CY-HANDOFF: CLAUDE`，写明 BL 编号、改了哪些文件、协调者统筹文件补丁（app.json/台账/基线/冻结计数）、未验证说明。

## 第二批（31–60）：新功能与现有行为补齐（不改现有样式）

| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 31 | 举报进度标记已读 | 打开举报详情时调用现有 `POST /api/v1/community/notifications/{id}/read`（带 requestId），回读未读状态；失败不阻塞查看 | CL2 系统 tab 与详情 |
| 32 | 举报入口·用户主页 | 他人主页「…」里加举报，用 `/api/community-trust/reports/submit` targetType=MEMBER，原因取 taxonomy，纯文字 | 照现有菜单样式 |
| 33 | 举报入口·商家页 | 商家公开页加举报 MERCHANT | |
| 34 | 举报入口·俱乐部页 | 俱乐部详情加举报 CLUB | |
| 35 | 举报入口·官方活动 | 官方活动详情加举报 ACTIVITY（等 CL4 合并后再改 detail；先做可复用的举报半屏组件） | |
| 36 | 举报入口·私信 | 私信会话菜单加举报对方（MEMBER） | `subpackageB/pages/im/chat/**` |
| 37 | 共用举报半屏组件 | 把 32–36 用的原因选择+说明+提交+回执做成一个组件，复用 CL2 的阶段中文名 | 新组件 |
| 38 | 草稿阶段添加协办方 | 主办方在草稿里增删协办方（俱乐部/商家/官方账号），走现有 `/api/official/v2/drafts` 的 parties 全量提交，提交后回读 | 草稿只读接口先核实 |
| 39 | 发布前检查清单 | 主办面板「发布活动」前展示 `/v2/events/{id}/publish-check` 的 blockers/risks，逐条可读 | CL4 页面 |
| 40 | 协办方状态列表 | 主办面板列出各参与方 INVITED/ACCEPTED/DECLINED 状态（数据只用现有接口；没有字段就 BLOCKED 并写读合同请求） | |
| 41 | 漫游地图显示进行中的官方活动 | 用 `/api/official/active-for-roam` 在漫游页显示入口卡（照漫游现有卡片样式） | roam |
| 42 | 收藏的城市节点 | 用 `/api/city/nodes/favorites` 做「我收藏的点位」列表 | `subpackageRoam` |
| 43 | 商家分身语音重置 | 商家 NPC 设置里接 `/api/merchant/npc/voice/reset`，二次确认后回读 | `pages/merchant/**` |
| 44 | 对话保留说明 | NPC 对话面板显示导出接口返回的保留天数与政策文案（`retentionDays`/`retentionPolicy`） | npc-chat |
| 45 | 俱乐部勋章墙（成员只读） | 俱乐部详情给成员展示本俱乐部勋章（`/api/club/badge/list`），沿用现有勋章展示样式 | |
| 46 | 勋章发放记录 | 主理人查看某勋章已发给谁（只用现有接口；没有就 BLOCKED） | |
| 47 | 主题状态键校验提示 | 状态键编辑的命名/重复校验与错误回显（按后端规则） | publish 组件 |
| 48 | 积分明细空态与加载失败重试 | 明细页四态与重试（沿用现有组件） | points-history |
| 49 | 收到的邮票空态与分页 | 邮票「收到的」分页加载与到底提示 | stamp album |
| 50 | 官方活动收件箱分页/空态 | 收件箱分页、空态与失败重试 | official-inbox |
| 51 | 举报原因说明 | 举报半屏里显示 taxonomy 自带的原因说明与优先级时效文案 | |
| 52 | 申诉回执 | 申诉成功后展示后端回执（`CommunityReportAppealReceiptVO`）字段 | CL2 |
| 53 | 漫游筛选记忆按账号隔离 | BL-01 的筛选存储按账号作用域，切账号不串 | roam |
| 54 | 单测补齐·举报 | 举报相关纯函数/页面处理器单测（不改 UI） | tests |
| 55 | 单测补齐·勋章与昵称 | CL3 相关单测 | tests |
| 56 | 单测补齐·官方活动主办 | CL4/CL6 相关单测 | tests |
| 57 | 接口合同测试·新页面 | 为本批新调用的接口加合同测试（路径/方法/字段对照 Java DTO 源码） | tests |
| 58 | 无障碍·新页面 | 本批新增页面/组件补 aria-label/role、44px 命中区（不改视觉） | |
| 59 | 本地验收清单汇总 | 把所有已开 PR 的本地验收清单汇成一份 `docs/plans/mini-claude-ui-20261015/ACCEPTANCE.md` | docs |
| 60 | 读合同请求整理 | 把 CL6/BL-07/BL-40 等需要后端补字段的请求整理成一份给 Codex 的合同申请（只写文档） | docs |

清单耗尽后：不要停。由监督线按同样规则从后端已有、小程序零调用的接口中补充新任务（写入本文件末尾），开发线继续领取；如果暂时没有，就处理评审意见与 CI 失败。

## 第三批（61–68）：监督线补充（后端已有、小程序零调用；不改现有样式、无迁移/新接口）

| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 61 | 私信解除拉黑 | 会话处于「你已拉黑对方」（`/api/im/...` 回读 `blockedByMe=true`）时，在现有会话菜单加「解除拉黑」，调 `POST /api/im/unblock`（`target_member_id`），二次确认后回读会话状态恢复可发送；失败如实提示 | `subpackageB/pages/im/chat/**`；与 BL-36 同文件，先看 #77 |
| 62 | 我的处罚记录（只读） | `GET /api/v1/community/me/enforcements`（cursor/limit）只读列表；入口只从系统消息里的处罚类通知进入（与举报详情同一路径），无对应通知类型就 BLOCKED，不加独立入口 | 照 CL2 举报详情页结构 |
| 63 | 社区规范确认 | 发帖时后端若要求先确认规范：用 `GET /api/v1/community/guidelines/active` 在 `cy-sheet` 里展示，确认调 `POST /api/v1/community/guidelines/ack` 后重试原操作；先核实后端是否强制，不强制就 BLOCKED | 不碰 `CommunityPostServiceImpl`（Codex） |
| 64 | 社区通知偏好 | 设置页现有列表里加一行进入偏好页：`GET/PATCH /api/v1/community/notification-preferences`，字段只用 DTO 已有项，保存后回读 | 照现有设置二级页 |
| 65 | 官方活动集体进度 | 官方活动详情读 `GET /api/official/events/{id}/progress`，展示集体进度（字段以 `officialEventService.progress` 返回为准），无数据诚实空态 | 等 CL4（#27）合并后再改 detail |
| 66 | 商家安全评价摘要（只读） | 商家公开页读 `GET /api/community-trust/safety/merchants/{merchantId}`，样本不足/无数据时不展示；不做评分提交 | 照商家页现有信息块 |
| 67 | 商家信息纠错 | 商家公开页「…」加「信息纠错」：`GET /api/community-trust/contributions/taxonomy` 取类型，`POST /contributions/submit` 纯文字提交，成功展示回执；「我的纠错」从系统消息进入，`GET /contributions/{id}` 详情 + `/contributions/supplement` 补充 | 复用举报半屏的结构；请求体按 `MerchantContributionSubmitRequest` |
| 68 | 俱乐部申请状态回读 | 现有俱乐部申请页提交后/再次进入时用 `POST /api/club/my-apply` 回读申请状态（审核中/已通过/被拒，字段以 `Club` 实体真值为准），不改现有表单样式 | `pages/club/**` |

## 第四批（69–75）：监督线补充（后端已有、小程序零调用；不改现有样式、无迁移/新接口）

| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 69 | 商家主页收藏据点 | 商家公开页读 `GET /api/city/nodes/merchant/{merchantId}`（无在架节点返回空，则不展示）；有节点时在现有操作区照现有收藏按钮样式加「收藏据点」，切换走已在用的 `POST /api/city/nodes/{id}/favorite`，以返回值回读收藏态；失败如实提示 | 与 BL-42 收藏列表配套；不改现有按钮样式 |
| 70 | 商家卡打卡头像堆 | 漫游现有商家/据点卡读 `POST /api/roam/shop/visitors`（`sourceType`=1/2，`sourceIds` 逗号分隔，最多按后端上限），展示头像堆+人数（照现有头像堆写法）；`total=0` 不展示，失败不阻塞卡片 | `pages/roam/**`；sceneCurrent 单宿主不变 |
| 71 | 我的个人小程序码 | 个人主页现有分享/二维码位置读 `POST /api/user/player-code`（返回 `qr`），可预览/保存；生成失败显示后端文案并可重试；没有现成位置就 BLOCKED，不新增入口样式 | `subpackageMember/components/cy/profile` |
| 72 | 官方邀约跟踪（只读） | 官方发布者在「我发布的」页按现有分段/列表结构查看 `GET /api/official/invites?status=` 的商家承接邀约及状态，字段以 `CoopInvite` 实体为准；不做发起邀约（含分成/费用字段，属资金政策） | `pages/activity/official-mine` |
| 73 | 单测补齐·第三批 | BL-61–72 新增纯函数/页面处理器单测（不改 UI） | tests |
| 74 | 接口合同测试·第三批 | BL-61–72 新调用接口的合同测试（路径/方法/字段对照 Java 源码） | tests |
| 75 | 私信菜单合并预整合 | BL-36（#77 举报对方）与 BL-61（#104 解除拉黑）同改 `subpackageB/pages/im/chat/**`：在一个分支上预演两者合并，交出冲突解法与合并后测试计数（只写 PR 说明与必要的合并提交，不扩功能） | 等 #77/#104 评审 OK 后 |

## 第五批（76–83）：监督线补充（后端已有、小程序零调用；不改现有样式、无迁移/新接口）

| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 76 | 商家核销台账（只读） | 商家工作台现有入口区加一行进入核销记录列表：`POST /api/merchant/verification-records`（`merchantAccessService` 鉴权，字段以返回 VO 为准），四态照现有商家二级页 | `pages/merchant/**`；不碰积分/券/应付服务（Codex） |
| 77 | 商家券领取/核销统计 | 现有券管理页读 `POST /api/merchant/coupon-summary` 显示领取数/核销数（只读，无数据时不展示） | 不碰 `SmsCouponHistoryServiceImpl`（Codex） |
| 78 | 商家客户名册（只读） | `POST /api/merchant/customers`（body 按 `MerchantCustomerQuery`）只读列表，含到店/退款状态；不展示手机号等接口未返回的字段 | 照现有商家列表页 |
| 79 | 藏品卡改名 | 藏品卡页（`subpackageP3/pages/object-cards`）卡片菜单加「改名」：`cy-sheet` 输入，`POST /api/object-card/rename`（`id`,`title`），成功后回读列表；空名/超长按后端报错如实回显 | 照现有菜单样式 |
| 80 | 官方通知复盘（只读） | 官方发布者查看自己发出的通知触达/点击：`GET /api/official/broadcast/{id}/stats`（仅发布者本人）；先核实小程序是否已有「我发出的通知」列表可挂入口，没有就 BLOCKED，不新造入口 | `pages/activity/official-*` |
| 81 | 单测补齐·第四批 | BL-76–80 新增纯函数/页面处理器单测（不改 UI） | tests |
| 82 | 接口合同测试·第四批 | BL-76–80 新调用接口的合同测试（路径/方法/字段对照 Java 源码） | tests |
| 83 | 验收清单更新·第三四批 | 把 BL-61–80 已开 PR 的本地验收清单补进 `ACCEPTANCE.md`（依赖 BL-59 #101） | docs |

## 第六批（84–91）：监督线补充（后端已有、小程序零调用；不改现有样式、无迁移/新接口）

| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 84 | 剧透保护偏好 | 设置页现有列表加一行开关「全部揭晓剧情」：`POST /api/spoiler/state` 读 `revealAll`，切换调 `POST /api/spoiler/preference`（body `{revealAll: boolean}`），成功后再读 state 回读；失败回滚开关并如实提示。不做单节点 `reveal` | 照现有设置开关行；不碰 `pages/play/index.js`（Codex） |
| 85 | 商家开通前置检查（只读） | 商家工作台现有入口区读 `GET /api/merchant/ops/prerequisites`（`IMerchantPrerequisiteService.calculate` 返回为准），列出未完成项及去处；全部完成或无数据时不展示；没有现成位置就 BLOCKED | `pages/merchant/**`；照现有工作台信息块 |
| 86 | 商家转化漏斗（只读） | 商家现有数据/经营页读 `POST /api/merchant/funnel`（`windowDays` 仅 7/30，需 `MARKETING_READ`），照现有分段切 7/30 天；无权限/无数据诚实空态，不展示金额 | 不碰积分/券/应付服务（Codex） |
| 87 | 审核结果详情 | 系统消息里带审核任务 id 的通知可点进 `GET /api/audit/{taskId}/detail`（仅提交人本人，403/404 如实提示），展示审核结论与原因；先核实通知 payload 是否带 `taskId`，没有就 BLOCKED，不加独立入口 | 照 CL2 举报详情页结构 |
| 88 | 协议版本显示（只读） | 现有「关于/协议」页读 `GET /api/agreement/versions`，在现有文字行展示当前生效版本；接口失败时不展示。**不改** `agreement-sheet` 的同意流程、不调 `/consent` | `pages/shezhi/about/**`、`pages/agreement/**` |
| 89 | 到访安全感受评价 | 只在已有「核销/到访完成」结果处照现有按钮样式加「评价安全感受」：`GET /api/community-trust/safety/taxonomy` 取选项，`cy-sheet` 内单选，`POST /safety/ratings` 提交，资格以后端报错为准如实回显；没有现成位置就 BLOCKED | 与 BL-66 配套；不展示他人评价 |
| 90 | 单测补齐·第五批 | BL-84–89 新增纯函数/页面处理器单测（不改 UI） | tests |
| 91 | 接口合同测试·第五批 | BL-84–89 新调用接口的合同测试（路径/方法/字段对照 Java 源码） | tests |

## 第七批（92–99）：监督线补充（后端已有、小程序零调用；不改现有样式、无迁移/新接口）

| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 92 | 剧情案卷（只读） | 在主题游玩已有的「线索/案卷」类入口读 `GET /api/play/journey/casebook?topicId=&activityId=`（`PlayJourneyReadService.casebook` 返回为准），`cy-sheet` 内列表展示；只能挂在非 Codex 文件的现有入口，入口在 `pages/play/index.js` 或没有现成位置就 BLOCKED | 不碰 `pages/play/index.js`（Codex）；照现有半屏列表 |
| 93 | 剧情背包（只读） | 同上入口读 `GET /api/play/journey/backpack`，展示本主题各节点奖励与领取状态（状态值取自 service 真值，不编造「可领取」按钮）；没有现成位置就 BLOCKED | 与 BL-92 同一宿主；不做领取写操作 |
| 94 | 主题游玩历史（只读） | 主题详情/我的游玩现有记录区读 `GET /api/play/theme/history?topicId=&beforeSessionId=&limit=`，按 `beforeSessionId` 游标「加载更多」，四态照现有列表；先核实是否已有同类历史列表，已有则 BLOCKED | 不改会话运行时服务（Codex）；只读 |
| 95 | 成就概览（只读） | 个人页现有成长/等级信息块读 `POST /api/play/achievements`（`level`、`totalCheckins`、碎片/彩蛋列表，字段以控制器 `data.put` 为准），只补现有块里缺的数字；没有现成块就 BLOCKED，不新造入口 | 照现有个人页信息块；不改主标题 |
| 96 | 收藏的活动（只读） | 现有「我的收藏」类页面读 `POST /api/activity/like_list` 展示已收藏活动；若唯一宿主是 `pages/mylike/mylike.js`（Codex）则只交补丁并 BLOCKED；不做 `/api/activity/like` 写入口以外的新按钮 | 照现有收藏列表样式 |
| 97 | 商家项目草稿箱 | 商家工作台现有项目入口读 `POST /api/content-draft/list`（`business_type`、`scope`，需 `PROJECT_MANAGE`），支持「恢复」(`/restore`) 与「删除」(`/delete`，二次确认，成功后回读列表)；**不做** `/publish`；没有现成位置就 BLOCKED | `pages/merchant/**`；四态照现有商家二级页 |
| 98 | 报名按日趋势（只读） | 主题/活动主办方现有报名管理页读 `POST /api/registration/count_by_date`（`owner_type` 1 主题/2 活动、`owner_id`），按日列出报名数；403「无权查看该项目趋势」如实提示；不画新图表样式，沿用现有列表/数字行 | 主办方现有页面；不碰支付/报名写接口 |
| 99 | 单测与合同测试·第七批 | BL-92–98 新增纯函数/页面处理器单测 + 新调用接口合同测试（路径/方法/字段对照 Java 源码） | tests |

## 第八批（100–107）：监督线补充（后端已有、小程序零调用；不改现有样式、无迁移/新接口）

| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 100 | 商家触达任务记录（只读） | 商家现有客户/营销页加一行进入触达记录：`GET /api/merchant/crm/campaigns` 列表 + `GET /api/merchant/crm/campaigns/{campaignId}` 逐人脱敏回执（字段以 `merchantCrmCampaignService.list/detail` 返回为准）；**不做** 创建/投递/重试（`POST /campaigns`、`/dispatch`、`/retry`）；无权限/无数据诚实空态，没有现成位置就 BLOCKED | `pages/merchant/**`；四态照现有商家二级页 |
| 101 | 发布检查 XP 预算总览（只读） | 主题发布检查已有的奖励/预算区读 `POST /api/topic/xp-budget`（`topic_id`，`xpBudgetService.computeBudget` 返回为准），只补现有块缺的数字；宿主若是 `pages/publish/fabu/index.js`（Codex）则只交补丁并 BLOCKED | 不碰 fabu/pro-editor（Codex）；不改现有样式 |
| 102 | 创作广场头部统计（只读） | 个人页现有广场分区读 `POST /api/creativesquare/summary`（参与活动/通关数/本周打卡节点，字段以控制器 `data.put` 为准），只填现有头部块缺的数字；没有现成块就 BLOCKED，不新造入口 | `subpackageMember/components/cy/profile`；不改主标题 |
| 103 | 合作方履约信用摘要（只读） | 现有合作邀约详情页读 `POST /api/coop/credit`（body `{memberId}` 为对方，近 90 天履约率/违约计数，字段以 `creditSummary` 返回为准），照现有键值行展示；样本为 0 时不展示，不做评分/排序 | `pages/coop/invite-detail/**`；不碰分成/费用字段（资金政策） |
| 104 | 商家订单异常列表（只读） | 先核实商家工作台是否已有订单/异常列表（`ledger`、`aftercare` 等），已有则 BLOCKED；否则在现有入口区加一行读 `POST /api/merchant/orders`（`ORDER_READ`，联系方式按后端脱敏），只读，不做退款/核销写操作 | `pages/merchant/**`；不碰积分/券/应付服务（Codex） |
| 105 | 商家可报名主题（只读） | 商家现有合作/找主题入口读 `POST /api/topic/list-to-merchant` 列出可报名主题，点进沿用现有主题详情；先核实是否已有同类列表（`coop/nearby` 等），已有则 BLOCKED；不新增报名写接口 | 照现有主题列表卡片 |
| 106 | 单测与合同测试·第八批 | BL-100–105 新增纯函数/页面处理器单测 + 新调用接口合同测试（路径/方法/字段对照 Java 源码） | tests |
| 107 | 验收清单更新·第五至八批 | 把 BL-84–105 已开 PR 的本地验收清单补进 `ACCEPTANCE.md`（依赖 BL-59 #101、BL-83 #126） | docs |

## 第九批（108–115）：监督线补充（后端已有、小程序零调用；不改现有样式、无迁移/新接口）

| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 108 | 商家已生效权益（只读） | 商家工作台现有信息块读 `POST /api/merchant/subscription`（`BASIC_READ`，返回 `merchant_subscription` 中 `is_active=1` 的行，字段以 `MerchantSubscription` 实体为准），列出权益名与有效期；**不展示价格、不做购买/续费入口**；空列表不展示；没有现成位置就 BLOCKED | `pages/merchant/**`；资金政策不变 |
| 109 | 创作者中心（只读） | 个人页现有创作/发布相关信息块读 `POST /api/creator/center`（`ICreatorCenterService.getCenter` 返回为准），只补现有块缺的数字/状态；**不做** `/api/creator/apply` 申请入口（需产品决定）；没有现成块就 BLOCKED | 照现有个人页信息块；不改主标题 |
| 110 | 为你推荐（只读） | 现有首页/发现页「推荐」类列表若是本地或其他接口拼的，核实能否改读 `POST /api/recommendation/list`（`candidate_type` 默认 topic，`limit`）；卡片沿用现有主题卡；没有现成推荐位或已有等价数据源就 BLOCKED | 不碰 `pages/play/index.js`（Codex） |
| 111 | 发现页筛选项对齐后端 | 对照 `POST /api/discovery-filter/schema`（`DiscoveryFilterContract.schema(resource)`）核实小程序现有筛选项的取值/文案是否与后端一致；不一致处改前端常量或改为运行时读 schema（失败回退现有常量），**不改筛选 UI 样式**；完全一致则 BLOCKED 并附对照表 | 现有发现/搜索页 |
| 112 | IP 世界首页与任务（只读） | 若小程序已有 IP 世界/城市世界类入口，读 `POST /api/world/home`（`city_code`、`world_id`）与 `POST /api/world/quests`（`world_id`，只回已发布任务）补齐现有展示；没有现成入口就 BLOCKED，不新造页面 | 字段以 `IIpWorldService` 返回为准 |
| 113 | 商家经营台账汇总核对 | 核实 `pages/merchant/ledger` 现用接口；若页面缺少分页/汇总数字而 `POST /api/merchant/orders/ledger`（`MerchantLedgerQuery`：status pending/completed/aftersale、period、pageNum/pageSize，`ORDER_READ`）能提供，则只补现有数字行与分页；已等价则 BLOCKED | 不碰积分/券/应付服务（Codex）；只读 |
| 114 | 单测与合同测试·第九批 | BL-108–113 新增纯函数/页面处理器单测 + 新调用接口合同测试（路径/方法/字段对照 Java 源码） | tests |
| 115 | 验收清单更新·第九批 | 把 BL-108–113 已开 PR 的本地验收清单补进 `ACCEPTANCE.md`（依赖 BL-59 #101、BL-107 #151） | docs |

## 第十批（116–123）：监督线补充（后端已有、小程序零调用；不改现有样式、无迁移/新接口）

| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 116 | 圈层探索记录卡（只读） | 现有圈层探索流程（已调 `/api/circle-theme/session/*`）结束处读 `GET /api/circle-theme/session/{sessionId}/card`（`circleExploreService.card` 返回为准，仅本人），照现有结果卡/半屏展示；错误按后端文案如实提示；宿主若是 Codex 文件或没有现成结束位置就 BLOCKED | 不改会话运行时服务（Codex）；只读 |
| 117 | 主题游玩记录卡（只读） | 主题游玩已有的结束/历史入口读 `GET /api/play/theme/card?sessionId=`（`runtime.themeCard` 返回为准），照现有卡片样式展示；**不做** `/api/play/theme/replay`；宿主在 `pages/play/index.js`（Codex）或无现成位置就 BLOCKED（与 BL-94 同宿主时合并说明） | 只读 |
| 118 | 主题模板详情（只读） | 现有模板货架（已调 `/api/template/topic-template/list`、`/use`）点开时读 `POST /api/template/topic-template/info`（`id`；`TopicTemplateDetailVO`，商家可见 `recruitStatus`），照现有主题详情/半屏结构展示章节→节点；先核实是否已有等价详情，已有则 BLOCKED | 不碰 fabu/pro-editor（Codex） |
| 119 | 俱乐部承接模板意向 | BL-118 详情里对俱乐部主理人加「我想承接」：`POST /api/template/topic-template/club-interest`（`id`），后端只给创建者发 best-effort 订阅消息、不落状态——成功文案只能写「已提交意向」，**不得写「对方已收到」**；非主理人按后端报错如实提示；不做申请状态回读 | 依赖 BL-118；照现有按钮样式 |
| 120 | 另存为模板 | 「我发布的」主题现有更多菜单加「另存为模板」：`POST /api/template/topic-template/save-as`（`id`、`scope`，取值以控制器/Service 校验为准），二次确认，成功后按返回 `topicId` 回读或跳现有模板页；失败如实提示；没有现成菜单就 BLOCKED | 不碰 fabu/pro-editor（Codex）；照现有菜单项 |
| 121 | 俱乐部按合作主题开一场 | 俱乐部主理人现有活动/开团入口：`POST /api/club/open-session-topics`（`{clubId}`）列可开主题，选主题+日期后 `POST /api/club/open-session`（`ClubSessionOpenRequest` 字段为准），成功按返回 `activityId` 跳现有活动详情回读；二次确认；非主理人/无主题诚实空态；没有现成入口或需产品决定就 BLOCKED | `pages/club/**`；不碰排期/`ApiClubEventOpsController`（Codex）；不涉及费用 |
| 122 | 单测与合同测试·第十批 | BL-116–121 新增纯函数/页面处理器单测 + 新调用接口合同测试（路径/方法/字段对照 Java 源码） | tests |
| 123 | 验收清单更新·第九至十批 | 把 BL-108–121 已开 PR 的本地验收清单补进 `ACCEPTANCE.md`（依赖 BL-59 #101、BL-107 #151、BL-115） | docs |

## 第十一批（124–131）：监督线补充（零调用接口已基本核完；以口径核对与协调者整合文档为主；不改现有样式、无迁移/新接口）

| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 124 | 商家公开详情口径核对 | 对照 `GET /api/merchant/ops/public-detail`（`@Anonymous`，`merchantId`，`detailService.getPublicDetail` 返回为准）与小程序现用 `POST /api/merchant/public-detail` / `public-home` 的字段；现有商家主页/漫游 POI 详情已有的信息行若缺数据而 ops 接口能提供，只补数据不改样式；等价则 BLOCKED 并附字段对照表 | `pages/merchant/profile/**`、`scene-roam-poi-detail`；只读 |
| 125 | 模板库分类分组口径核对 | 对照 `POST /api/template/groupByCategory`（`CategoryTemplatesVO`）与现用 `/api/template/homeData`；现有模板库若已有等价分类分区则 BLOCKED 并附对照表；否则只在现有分区内改用后端分组数据，**不改货架 UI 与卡片样式** | `pages/template/**`；不碰 fabu/pro-editor（Codex） |
| 126 | 协调者补丁汇总 | 新建 `COORDINATOR-PATCHES.md`：逐个列出所有 open 的 `claude/bl-*`/`claude/cl*` PR 正文里交协调者的补丁（app.json 页面、route-matrix、action-ledger `controlCount`、get-auth-matrix 直接 GET 计数、silentError 白名单、`nav-page-title` 计数、ui-integrity-baseline 键），给出**全部合入后的累计期望值**与需要按合并后树重算的项；只写文档，不改这些文件 | docs |
| 127 | PR 依赖与建议合并顺序 | 新建 `MERGE-ORDER.md`：列出 PR 间依赖（如 #166←#164、#158←#152、#74/#77/#95←#73、BL-31/52/62←CL2 #25、BL-35/56/65←CL4 #27），重复认领与可关闭项，给出建议合并顺序；只写文档 | docs |
| 128 | 待产品决定汇总 | 新建 `DECISIONS-NEEDED.md`：把所有标题含 BLOCKED 且原因为「需产品决定」的 PR（如 #61、#75、#111、#129、#153、#162、#165）各写成一行问题 + 可选项 + 各选项影响的文件，供用户一次性拍板；Codex 宿主类 BLOCKED 另列一表交协调者 | docs |
| 129 | 监督未决意见汇总 | 新建 `REVIEW-OPEN.md`：列出最新一条 `supervisor sha=` 结论为 CHANGES NEEDED、或 head 已变但仍有未处理 nit 的 PR，逐条写 file:line 与状态；只汇总不改代码 | docs |
| 130 | 单测与合同测试·第十一批 | BL-124–125 若有代码改动，补纯函数单测 + 接口合同测试（路径/方法/字段对照 Java 源码）；均 BLOCKED 则 BLOCKED | tests |
| 131 | 验收清单更新·第十一批 | 把 BL-124–129 已开 PR 的本地验收清单补进 `ACCEPTANCE-BATCH11.md`（依赖 BL-123 #167） | docs |

## 第十二批（132–139）：监督线补充（剩余零调用接口均已被 CL PR 覆盖、属用户排除项（会费/群聊/投票/资金/官方路线）或无现成宿主；本批以整合与核对文档为主，只写 `docs/plans/mini-claude-ui-20261015/` 下新文件，不改代码与统筹文件）

| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 132 | 后端风险待办汇总 | 新建 `BACKEND-FINDINGS.md`：汇总评审中发现、本线不改的后端问题，每条写接口、源码 file:line、风险、建议修法、归属（协调者/Codex）。至少含：`/api/template/groupByCategory` 未剥离答案字段且未过滤 `status=1`（登录用户可见，见 #169 评审）；`GET /api/merchant/ops/public-detail` 匿名放出原始电话、可见性用 `delFlag != 2`（见 #168）；以及 #127/#131/#157 等 BLOCKED 说明里提到的后端口径问题 | docs；只描述不改后端 |
| 133 | 零调用接口终表 | 新建 `ZERO-CALL.md`：扫描 `controller/api/*.java` 全部映射，列出 base 分支小程序（排除 tests/scripts）从未调用的接口，每条归类：已由某 open PR 接线（写 PR 号）/ 用户决定排除 / 资金或支付回调 / Codex 宿主 / 无现成宿主需产品决定；附扫描命令便于复跑 | docs |
| 134 | 可关闭 PR 建议清单 | 新建 `CLOSE-CANDIDATES.md`：列出 diff 为空的 BLOCKED 说明型 PR 和重复认领分支（如 `claude/bl-107-acceptance-batch58`、`bl-22-list-key-fix`、`bl-27-honest-empty`），每条一行结论 + 是否已写入 `DECISIONS-NEEDED.md`（BL-128），供用户拍板后批量关闭；**本卡不关 PR、不删分支** | docs |
| 135 | 合并冲突预检 | 新建 `CONFLICTS.md`：对每个有代码改动的 open `claude/bl-*`/`claude/cl*` PR，用 `git merge-tree` 对当前 base 及两两之间预检，列出冲突文件与建议处理顺序（与 BL-127 `MERGE-ORDER.md` 互相引用）；不推送任何分支 | docs |
| 136 | 单测缺口核对 | 新建 `TEST-GAPS.md`：逐个有代码改动的 open PR，列出新增纯函数/页面处理器及是否有对应 `tests/unit/*` 覆盖，缺口写函数名与建议用例；只写文档 | docs |
| 137 | 评审规则静态复核 | 新建 `LINT-AUDIT.md`：对 open PR 的新增代码复核监督规则——新可点元素是否有 `hover-class`、传给 `app.sendRequest` 的回调是否在方法简写里依赖 `this`、宿主页场景半屏是否经单一 `sceneCurrent`、是否有裸错误文案/假成功文案；逐条写 PR 号 + file:line；只汇总不改代码 | docs |
| 138 | 验收总索引 | 新建 `ACCEPTANCE-INDEX.md`：把 `ACCEPTANCE*.md` 与各 PR 正文「本地验收清单」按页面/入口归并成一张总表（入口路径、需要的身份、依赖的协调者补丁、对应 PR），供用户统一验证日按页走查 | docs；依赖 BL-123 #167、BL-131 |
| 139 | 协调者补丁累计校验 | 依据 BL-126 `COORDINATOR-PATCHES.md`，在本地临时 worktree 把各 PR 交协调者的补丁按 BL-127 顺序叠加，跑 TASKS.md 公共规则里的 node 检查（**不跑 `ci/xcx-check.sh`**），把每步计数与失败项记入 `COORDINATOR-PATCHES-CHECK.md`；只提交该文档，不提交叠加后的统筹文件 | docs；依赖 BL-126、BL-127 |

## 第十三批（140–147）：监督线补充（BL-133 #177 复核出有现成宿主的零调用接口 + BL-136 #180 单测缺口；不改现有样式、无迁移/新接口）

| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 140 | 俱乐部解散阻断明细回读 | 现有俱乐部编辑场景的解散区（`components/cy/scene-club-edit`，已有 `dissolutionBlockerItems`/`dissolutionBlockerSection` 状态，目前只从 `/api/club/dissolve` 报错里取）在主理人进入解散区或处理完一项返回时读 `POST /api/club/dissolution-blockers`（`{id}`；`clubService.selectDissolutionBlockers(id, ownerId)` 返回为准），用现有阻断列表渲染；字段对不上就只做回读校验并在 PR 写清；错误按后端文案如实提示；非主理人按后端报错 | 只读；照现有阻断区结构，不加新样式；不涉及资金写 |
| 141 | 商家营业状态写后回读 | `pages/merchant/index` 与 `pages/merchant/decor` 现有营业中/已打烊切换在 `/business-status/update` 成功后读 `POST /api/merchant/business-status`（返回 `businessStatus`/`businessStatusText`），以回读值为准刷新开关，不一致时如实提示「状态未同步」；不得在回读前宣称成功；若现有页面已从 info 回读且等价则 BLOCKED 并附对照 | 不改开关样式；只加回读 |
| 142 | 合作候选池（发布者视角，只读） | `pages/coop/list` 已用 `candidates/received`、`candidates/confirm`；核实发布者是否有按主题查看候选的现成位置，有则读 `POST /api/coop/candidates`（`topicId`、`scope`；仅主题发布者，返回邀约候选+报名候选，字段以控制器为准）照现有候选卡展示；没有现成宿主或与现有列表等价就 BLOCKED 并附对照 | 只读；不做确认/拒绝以外的新写操作 |
| 143 | 单测补齐·漫游筛选 | 依 #180 `TEST-GAPS.md`：为 #37 `utils/roam-poi-filter` 的 `storageKey`（按账号隔离）、`isVisitedPoi`、`radiusLabel` 补单测；从 #37 分支切出，PR 正文注明依赖 #37、须在其后合并 | tests；不改被测代码 |
| 144 | 单测补齐·官方主办规则 | 依 #180：为 #27（CL4）`organizer-rules` 的 `composePauseField`（预设+补充文字拼接、空值）、`verifyReadBack`（回读不一致必须失败）补单测；从 #27 分支切出并注明依赖 | tests；不改被测代码 |
| 145 | 单测补齐·积分月份分组与商家 ID 校验 | 依 #180：#40 `rows.js` 的 `monthKey`/`monthLabel`（跨年、非法日期）与 #110 `validMerchantId`（0/负数/字符串）补单测；各从对应分支切出、分别开 PR 或在正文写清依赖 | tests；不改被测代码 |
| 146 | 单测与合同测试·第十三批 | BL-140–142 新增纯函数/页面处理器单测 + 新调用接口合同测试（路径/方法/字段对照 Java 源码）；均 BLOCKED 则 BLOCKED | tests |
| 147 | 验收清单更新·第十三批 | 把 BL-132–146 已开 PR 的本地验收清单补进 `ACCEPTANCE-BATCH13.md`，并在 BL-138 `ACCEPTANCE-INDEX.md`（#182）对应页面行注明 | docs |

## 第十四批（148–157）：监督线补充（零调用接口池已核完，见 #177 `ZERO-CALL.md`，剩余均为用户排除/资金/Codex 宿主/需产品决定；本批按 #180 `TEST-GAPS.md` §2–§3 剩余缺口补单测；只加 `tests/unit/*`，不改被测代码与现有 UI，无迁移/新接口）

| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 148 | 单测补齐·俱乐部勋章 | 为 #23（CL3）`pages/club/utils/club-badge.js` 的 `normalizeBadges`、`normalizeMembers`（`null`/非数组/缺 id 条目）、`grantReceipt`（成功/业务拒绝/结果未知，未知不得当成功）、`charLength`（emoji/代理对）、`positiveId`（`'0'`/负数/`'abc'`）补单测；从 #23 分支切出，正文注明依赖 #23、须在其后合并 | tests；不改被测代码 |
| 149 | 单测补齐·昵称校验 | 为 #23 `utils/nickname-check.js` 的 `normalize`（首尾空白、全角空格）、`codePointLength`、`localIssue`（长度边界与 `MIN_LENGTH`/`MAX_LENGTH`、口径对照 `POST /api/user/nickname/check` 源码）补单测；从 #23 切出并注明依赖；已有测试已等价覆盖则 BLOCKED 附对照 | tests；不改被测代码 |
| 150 | 单测补齐·举报详情视图 | 为 #25（CL2）`subpackageMember/report-common/view.js` 的 `appealEventCount`（时间线重复 APPEAL 事件）、`positiveId`（字符串数字/非法值）补单测；从 #25 切出并注明依赖 | tests；不改被测代码 |
| 151 | 单测补齐·NPC 导出时间与积分规则值 | 为 #27（CL4）`components/cy/npc-chat/chat-export.js` 的 `timeText`（秒/毫秒时间戳、ISO 字符串、空值）与 `subpackageMember/components/cy/profile/points-rules.js` 的 `pointValue`（正负、0、缺字段兜底）补单测；从 #27 切出并注明依赖（与 BL-144 #187 同源，注意文件名不冲突） | tests；不改被测代码 |
| 152 | 单测补齐·漫游商家访客来源键 | 为 #113（BL-70）`utils/roam-shop-visitors.js` 的 `sourceKey`（同商家不同来源不冲突、缺 id 行为）补单测；从 #113 切出并注明依赖 | tests；不改被测代码 |
| 153 | 单测补齐·核销记录与触达任务单条映射 | 为 #119（BL-76）`pages/merchant/verify-records/view-model.js` 的 `shapeRecord`、#144（BL-100）`pages/merchant/crm-campaigns/view-model.js` 的 `shapeCampaign`（未知 status 兜底、计数缺失 `|| 0`、缺字段）补单条用例；各从对应分支切出，分别开 PR 或在正文写清依赖 | tests；不改被测代码 |
| 154 | 处理器单测·私信解除拉黑回读 | 为 #104（BL-61）`subpackageB/pages/im/chat/index.js` 的 `_confirmUnblock` 补页面处理器单测：回读确认之前不得显示「已解除拉黑」、失败如实提示；若断言暴露现有缺陷，用 `todo` 标注并在 PR 正文与 #104 写清，不改被测代码；从 #104 切出并注明依赖 | tests；不改被测代码 |
| 155 | 单测补齐·工作台前置检查跳转白名单 | 为 #128（BL-85）`pages/merchant/utils/merchant-prerequisites.js` 的 `pendingPrerequisites` 补用例：`actionTarget` 不在 `ALLOWED_PATHS` 时 `path` 为空、带 query 的白名单路径、空/非数组输入；现有 `merchant-prerequisites.test.js` 已等价覆盖则 BLOCKED 附对照；从 #128 切出并注明依赖 | tests；不改被测代码 |
| 156 | 处理器单测·另存为模板结果未知 | 为 #164（BL-120）`subpackageA/pages/myproject/index.js` 的 `_postSaveAsTemplate` 补处理器单测：结果未知/失败时不提示成功、重复点击不重复发请求（若无防抖用 `todo` 标注并报 #164）；从 #164 切出并注明依赖（注意与 #166 BL-122 已有用例不重复） | tests；不改被测代码 |
| 157 | 验收清单更新·第十四批 | 把 BL-148–156 已开 PR 的本地验收命令（`node --test` 文件、期望 pass/fail/todo、须在哪个 PR 之后合并）补进 `ACCEPTANCE-BATCH14.md`，并在 BL-138 `ACCEPTANCE-INDEX.md`（#182）§3 测试行注明 | docs |

## 第十五批（158–167）：监督线补充（零调用接口池已核完；本批来自 #194 `COORDINATOR-PATCHES-CHECK.md` §3 与 #192/#201 单测 todo 的未决评审意见。都是修自己线上新代码的文案/门禁登记/判定，不改现有 UI 与标题，无迁移/新接口/新依赖；各从被修 PR 分支切出，正文注明依赖、须在其后合并）

| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 158 | 修 F13 禁用词·触达任务页 | #144（BL-100）新页 `pages/merchant/crm-campaigns/index.{js,wxml}` 仍有 5 处「回执」（`index.wxml:27,44,48,72`、`index.js:121`），触发 `audit-f01-f17-contract.test.js:79` F13；改为用户能懂的说法（如「触达明细」「逐人结果」），只改这页新文案与对应单测；从 #144 切出 | xcx；只改 #144 新页文案 |
| 159 | 修 U1 误判·安全感受摘要请求路径 | #109（BL-66）`subpackageMember/components/cy/profile/index.js:720` `'/api/community-trust/safety/merchants/' + id` 被 `ui-validity-gates` U1 判为无后端映射（后端 `ApiCommunityTrustController` 有 `@GetMapping("/safety/merchants/{merchantId}")`）；改为模板字符串（与 #130 写法一致），不改门禁；从 #109 切出 | xcx；一行 |
| 160 | 补 U5 白名单同步 | #194 X-5：#122/#123/#128/#141/#114/#73 只登记了 `ci/silent-error-allowlist.txt`，未同步 `scripts/ui-silent-error-allowlist.js`（#141/#114 两份都没登记）；逐 PR 从原分支切出补登记（一 PR 一个 follow-up，或在一个 PR 正文按 PR 分节给补丁），跑 `node scripts/ui-validity-gates.js` 给前后对照 | xcx scripts 白名单；不改业务代码 |
| 161 | 修超长/开发者口径提示·新页 | #194 X-11：#84（BL-42）`subpackageRoam/node-favorites/index.js:12,42` 错误副文案「网络请求失败，重试会重新拉取一次」被判开发者口径；#43（BL-06）`official-inbox/index.js:202` 提示 21 字 > 16；#130 `im/chat` 新增提示 18 字 > 16（以 `node tests/lint/ui-integrity.js --warn-as-error` 在该分支实测行号为准）；改成 ≤16 字用户口径，不改现有页面既有文案 | xcx；只改这三个 PR 新增文案 |
| 162 | 成功 toast 棘轮·#88/#112 | `success-toast-ratchet.test.js:86` 上限 101：#88（BL-43）`ai-npc/index.js:785` `toast.success('角色声音已清除')`、#112（BL-69）收藏据点 `cyToast.success` 会推到 102/103；改为界面状态体现（如声音行回到「未录音」、收藏按钮变已收藏）+ 普通 toast 或无提示，不抬上限；做不到不改 UI 时 BLOCKED 写明需协调者抬上限 | xcx；只改这两个 PR 新增行 |
| 163 | 修结果未知当成功·勋章颁发回执 | #192（BL-148）todo：#23（CL3）`pages/club/utils/club-badge.js` `grantReceipt(null/{})` 返回成功文案；改为未知时返回未知态（不给成功文案），页面已有 `isSuccess` 守卫保持不变；把 #192 的 todo 转正；从 #23 切出 | xcx；纯函数 + 单测 |
| 164 | 修回读前提示成功·私信解除拉黑 | #201（BL-154）三条 todo：#104（BL-61）`subpackageB/pages/im/chat/index.js` `_confirmUnblock` 在 `/api/im/unblock` 200 后、回读前就 `dc.done()`「已解除拉黑」；改为 `/api/im/messages` 回读确认 `blockedByMe === false` 后再提示，回读不一致时如实提示；断网改为「结果未确认」并回读一次；把 #201 todo 转正；从 #104 切出 | xcx；只改 #104 新增处理器 |
| 165 | 冻结计数·项目操作面板 | #194 X-3：#164（BL-120）加「另存为模板」后 `merchant-card-project-actions-contract.test.js:50` 期望 5 / 实际 6；在 #164 线上把期望改为 6 并更新断言说明（列出新增项），确认 44px 热区断言仍覆盖新行 | tests；一处计数 |
| 166 | 门禁登记·安全感受半屏 U5 理由 | #194 X-7：#132（BL-89）`safety-rating-sheet/index.js:115` silentError 未在 `ui-integrity` U5 逐项理由登记；`ui-integrity-baseline.js` 归协调者，只在 PR 正文给最小补丁 + 本地验证输出，不提交该文件 | docs/补丁；不改协调者文件 |
| 167 | 验收清单更新·第十五批 + 索引 | 把 BL-158–166 已开 PR 的验收命令（门禁/单测命令、期望前后对照、依赖顺序）写进 `ACCEPTANCE-BATCH15.md`；并按 BL-157 #204 要求，在 PR 正文给 BL-138 `ACCEPTANCE-INDEX.md`（#182）§3 追加第十四、十五批行的补丁（BL-154 只列 #201，#200 已按重复关闭） | docs |

## 第十六批（168–174）：监督线补充（零调用池已核完；本批来自 #205/#206/#207 及 #120 复审的未决意见与合并前核对。不改现有 UI 与标题，无迁移/新接口/新依赖；协调者文件只交补丁）

| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 168 | 补 U5 白名单·安全感受摘要 | #206 评审：#109（BL-66）`subpackageMember/components/cy/profile/index.js` 新增 `silentError`（`:724`）后 `ui-validity-gates` U5 FAIL（白名单 1，实际 2）；从 #109（或 #206）分支切出，在 `scripts/ui-silent-error-allowlist.js` 计数 1→2 并写真实理由（失败不展示、不挡主页），核对 `ci/silent-error-allowlist.txt` 是否需同步；给前后 U5 对照。若 #207 已补 #109 一节则 BLOCKED 指向 #207 | xcx scripts 白名单；不改业务代码 |
| 169 | 去残留「回执」·触达任务注释与单测名 | #205 评审：`pages/merchant/crm-campaigns/view-model.js:3` 注释与 `tests/unit/merchant-crm-campaigns.test.js:41` 用例名仍写「回执」，与页面改后「触达明细/逐人结果」不一致；从 #205 切出只改这两处文字，跑该单测与 `audit-f01-f17-contract` | xcx；注释/用例名 |
| 170 | U5 名额核对（u5Silent 29/29） | #120 复审：#120 用掉 `ui-integrity` U5 最后一个名额（29/29）。列出所有 open `claude/bl-*`/`claude/cl*` PR 中改 `tests/lint/ui-integrity-baseline.js` `u5Silent`/`ratchet.u5Silent` 的 PR、各自 +N、合并后累计值与上限差，写进 `U5-QUOTA.md`，给 C00 是否抬上限的数据；不改协调者文件 | docs |
| 171 | 冻结计数累计表 | 汇总所有 open PR 对冻结计数的改动（`route-matrix` 条目数、`nav-page-title` 页数、`action-ledger` 控件数、`get-auth-matrix` 直接 GET 数、`success-toast-ratchet` 上限、`app.json` 新页）：每 PR 一行「改前→改后、实际增量」，按 #171 MERGE-ORDER 顺序给合并后应有值，写进 `FROZEN-COUNTS.md`；不改计数文件 | docs |
| 172 | 基线漂移复跑 | 对监督结论 OK 的 xcx 代码 PR，把当前 `claude/mini-ui-features-20261015` 合进临时 worktree（不推送），跑该 PR 相关单测 + `node scripts/ui-validity-gates.js` + `node tests/lint/ui-integrity.js --warn-as-error`，列出因基线前进而转红/冲突的 PR 与失败项，写进 `DRIFT-CHECK.md`；不推送到任何 worker 分支 | docs；只读复跑 |
| 173 | 零调用接口增量复扫 | 以 #177 `ZERO-CALL.md`（基线 `d0307a78`）为底，复扫 `controller/api/*.java` 与当前基线及 Codex 控制分支，列出新增或变更的映射及其小程序调用情况；只登记，不立功能卡（新卡由监督线按用户决定筛选） | docs |
| 174 | 验收清单更新·第十六批 + 索引 | 把 BL-168–173 已开 PR 的验收命令与期望（前后对照、依赖顺序）写进 `ACCEPTANCE-BATCH16.md`，并在 PR 正文给 BL-138 `ACCEPTANCE-INDEX.md`（#182）§3 追加第十六批行的补丁 | docs |

## 第十七批（175–182）：监督线补充（来自 #104/#213/#219 复审与 `ui-integrity` 实测的未决项；不改现有 UI 与标题，无迁移/新接口/新依赖；协调者文件只交补丁；各从被修 PR 分支切出，正文注明依赖）

| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 175 | 修 U4·私信 blockedByMe 零消费 | #104 评审：`node tests/lint/ui-integrity.js --warn-as-error` 报 `[U4] subpackageB/pages/im/chat/index.js:191 setData 顶层字段 blockedByMe 在同页 WXML 零消费`（旧 head 已有）。把只给逻辑用的 `blockedByMe` 移出 `data`（如 `this._blockedByMe`），同步 #104 自带单测；不改 wxml/样式；从 #104 当前 head 切出，给前后 U4 对照 | xcx；只改 #104 新增逻辑 |
| 176 | 解除拉黑回读在视图切换时落定 | #213 评审 nit 在 #104 新 head（`1799039b`）同样存在：`loadMessages` 在 `!current()` 时直接 return，`_loadSettle` 不落定，确认框可能停在进行中。在视图失效路径调用 `_takeLoadSettle(null)`（或确认框随视图销毁则 BLOCKED 附证据）；补处理器单测；从 #104 切出 | xcx；只改 #104 新增处理器 |
| 177 | BL-154 todo 转正（基于 #104 新 head） | #104 新 head 已让 #201（BL-154）3 个 todo 断言通过（监督实测）。从 #201 分支合入 #104 当前 head，去掉 3 个 `todo`，测试文案按 #104 实际文案对齐；跑 `im-*` 单测。完成后在 #213 留言说明可关闭或只保留差异部分 | tests；不改被测代码 |
| 178 | 漫游 u5Silent 行号漂移补丁 | #219 `U5-QUOTA.md` §4：#38/#83/#113 改 `pages/roam/index.js` 后 11 条既有 `u5Silent` key 行号位移。照 #37 的等量换 key 做法，逐 PR 给 `tests/lint/ui-integrity-baseline.js` 最小补丁（换 key + 重算 digest，不改条数），在各分支 `git apply` 后实跑 `ui-integrity` 给前后对照；不提交该文件 | docs/补丁；不改协调者文件 |
| 179 | 官方收件箱 U1-VARIABLE 基线漂移补丁 | #209 评审：#43（BL-06）使 `pages/activity/official-inbox/index.js:103 → :109` 的 U1-VARIABLE 基线键漂移（`[BASELINE] … 基线已未命中`）。给 `ui-integrity-baseline.js` 最小补丁并在 #43/#209 上 `git apply` 实测 `UI-GATE-0: PASS`；不提交该文件 | docs/补丁；不改协调者文件 |
| 180 | ui-integrity 逐 PR 实测表 | 监督此前多只跑 `ui-validity-gates`；对所有 open xcx 代码 PR（各自 head，不合基线）跑 `node tests/lint/ui-integrity.js --warn-as-error`，列 UI-GATE-0 结果与每条 violation（U1/U4/U5/WARN/BASELINE），标注「本 PR 引入 / 基线既有 / 归 C00」，写进 `UI-INTEGRITY-BY-PR.md`；与 BL-172（合基线复跑）互补，不重复 | docs；只读复跑 |
| 181 | U5 名额消化评估 | 依 #219 §6 选项 2：逐条评估 #73（report-sheet:46/:89）、#110、#123、#83、#113 新增的 `silentError` 调用能否在**本 PR 新增代码内**改为门禁认可的可见反馈（不改现有 UI、不加新样式），能改的给补丁草案与前后 `ui-integrity` 对照，不能改的写原因；只交文档，等 C00 裁定后再动代码 | docs |
| 182 | 验收清单更新·第十七批 + 索引 | 把 BL-175–181 已开 PR 的验收命令与期望写进 `ACCEPTANCE-BATCH17.md`，并在 PR 正文给 BL-138 `ACCEPTANCE-INDEX.md`（#182）§3 追加第十七批行的补丁 | docs |

> **监督更正（2026-10-16 03:50Z，依 TASKS.md「监督规则修正（2026-10-16）」，该规则 `ceade3d8` 早于第十七批 `04814fec` 提交）**：
> - BL-177（只含单测）、BL-178–182（补丁文档/复跑表/评估/验收清单）属于规则 1 禁止新增的类别，**作废，不要领取**；已领取的也停止。
> - BL-175 已由 #104 `e966caa2` 在原分支完成（监督实测 `ui-integrity` UI-GATE-0 PASS），**不要再领取**。
> - BL-176 按规则 2 **直接提交到 #104 分支 `claude/bl-61-im-unblock`**，不开新分支、不开「依赖 #104」的新 PR。
> - 规则 3：符合条件（用户可见新功能或真实缺陷、已有接口、不改现有 UI）的新卡本轮没有找到，因此不补。开发线转去处理监督结论为 CHANGES NEEDED 的 PR，修正直接推到原分支。

## 第十八批（183）：监督线补充（10-16 规则 3：真实缺陷 + 已有接口 + 不改现有 UI；本轮零调用复扫只找到这一项）

| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 183 | 修圈层城市实例无法启用供给角色 | 真实缺陷：模板复制时圈层候选全部置 `circleEnabled=0`（`CmsTopicServiceImpl.java:698-702`「模板副本必须由城市代理重新选择」），唯一能重新启用的 `POST /api/circle-theme/instance/configure`（`ApiCircleThemeController.java:128-149`，body `{topicId, candidateCodes[], scope}`，返回 `changed`；服务端已做归属/`PROJECT_MANAGE`/3–4 个覆盖全部角色/`assertPublishable` 校验，改选会清 `circleReviewedAt`）小程序零调用，导致「确认本实例供给已复核」无可复核内容。在现有「30天供给复核」区（`pages/topic/components/project-host/index.wxml:87-94`，仅 `hostTopic.circleThemeCode` 时出现）加一行「选择供给角色」→ 现有 `cy-sheet` 列出该主题候选（复用 `pages/play/utils/circle-theme.js` 的 `THEMES`/`themeOf`/`validateSelection`），提交后重载项目首页并以复核文案回到「尚未完成城市供给复核」作为回读。**无读取当前已启用候选的接口**：半屏不预选、不编造已选状态，PR 写明；后端错误原文展示，不给假成功。先核实圈层主题确有城市实例在用、发布编辑器（`pages/publish/fabu`）没有同类选择，否则 BLOCKED | `pages/topic/components/project-host/**` + `pages/topic/merchantinfo/merchantinfo.js`（`reviewCircleSupplies` 旁薄接线）；不碰 `pages/play/index.js` |

## 已确认阻塞（2026-10-17 监督，不要再领取，也不要为阻塞单独开 PR）
- BL-35 举报官方活动：举报 ACTIVITY 目标是 cms_activity.id，官方活动只有 official_event.id；需要后端新增目标类型（交 Codex）。
- BL-44 NPC 保留天数：已在 CL4 retentionPolicy 文案里展示，无剩余工作，视为完成。
- BL-62 我的处罚记录：处罚记录属于小程序未接入的 v1 社区发帖体系，接入前不做。
- BL-65 官方活动集体进度：详情页已展示集体进度与参与人数，progress 只多一个完成人数，不值得单做，视为完成。

## 第十八批（184–186）：主监督补充（2026-10-17；后端已有接口、小程序零调用，已用 git grep 核实；不改现有 UI 与标题，无迁移/新接口/新依赖）
| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 184 | 我的商家纠错记录与补证 | 纠错半屏 `components/cy/merchant-correction-sheet` 提交成功后，目前看不到进度。接 `GET /api/community-trust/contributions/mine`（列表）、`GET /api/community-trust/contributions/{id}`（详情）、`POST /api/community-trust/contributions/supplement`（补证，DTO 见 `MerchantContributionSupplementRequest`）。新增「我的纠错」列表和详情两个页面，照搬现有「举报进度」`subpackageMember/report-list`、`report-detail` 的结构、组件和样式；入口放在纠错半屏的成功态里，只有一个次按钮「查看我的纠错」。状态文案只用 VO 里已有的字段，不要编造。新页面的 app.json 补丁交给协调者。 | 新页面（照搬现有页） |
| 185 | 主题发布前检查 | 创作者发布主题（`/api/topic/publish-draft`、现有发布路径）前，先调 `GET /api/topic/publish-check?topicId=&scope=`，它只读，一次列出所有 error 和 warn。在现有发布确认处展示：有 error 时列出并且不发布，只有 warn 时列出后允许继续。展示结构照搬官方活动主办控制台 `pages/activity/official-organizer` 已有的「发布检查」区块。先 grep 现有主题发布入口（fabu / myproject 等），只接在入口里，不改页面样式。检查接口失败时不能挡住原发布流程，要用 cy-inline-error 说明「检查没有完成」。 | 现有页内补齐 |
| 186 | 章节阶段结算 | `GET /api/play/journey/stage-end?topicId=&activityId=&chapterId=` 返回本章完成后的阶段结算。先在 `pages/play/index.js` 找到章节全部完成的时刻；如果现有 UI 已有章末或章节完成的展示位，就把服务端结算接进去，复用 `/ending` 的取数与降级方式（见 `utils/life-archive.js`）。如果没有现成展示位，只在 PR 正文写明「需要产品决定展示位置」后停止，不要新造 UI，也不要开空 PR，在本条后面追加一行说明即可。 | 现有页内补齐 |

待用户决定、不要领取：`/api/im/poll/*`（群投票）、`/api/roam/hangout/*`（漫游约伴，漫游上线时已裁掉）、`/api/spoiler/*`（剧透偏好）、`/api/content-draft/*`（通用草稿）、`/api/club/chat`（俱乐部群聊入口）。涉及资金的接口（会员发票/续费/退款审核、钱包提现、余额）按边界规则一律不做。

## 第十九批（187）：监督补充（2026-10-17 04:4xZ；10-16 规则 3：用户可见新功能 + 已有接口 + 不改现有 UI；零调用复扫本轮只找到这一项）
| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 187 | 商家客户定向广播 | 客户名单页已有筛选与多选，但后端定向广播 `POST /api/merchant/crm/broadcast/preview` 与 `POST /api/merchant/crm/broadcast`（`ApiMerchantCrmController.java:137/146`，`MerchantCrmBroadcastServiceImpl` 要 MARKETING_WRITE）小程序零调用。仅当 `normalizeMerchantAccess(...).canWriteMarketing` 为真时，在现有工具区（照 `goCampaigns` 那一项的写法）加一个「定向广播」入口，打开现有 `cy-sheet`：请求体字段只用 `MerchantCrmBroadcastRequest` 已有的 keyword/segment/tagId/sourceType/sourceStart/sourceEnd（与当前 `_customerQuery` 同源）+ scope（多选模式下 MEMBERS + memberIds，否则 ALL）+ content + requestId（`makeRequestId`，重试复用同一个）。先 preview，展示 `MerchantCrmBroadcastPreviewVO` 的 deliverableCount / noConsentCount / frequencyLimitedCount / merchantDailyRemaining；deliverableCount=0 或 remaining=0 时不让发。发送成功后用返回的 `MerchantCrmBroadcastVO` 的 deliveredCount/failedCount/frequencySkippedCount 如实提示，并可跳 `crm-campaigns` 查看；失败如实展示服务端文案（429/400/409），不假成功。不改名单页现有布局与样式，新可点元素带 hover-class，回调不依赖方法简写里的 `this`。单测随 PR 写 | `pages/merchant/customer/**` + 可选新 view-model；不碰后端 |

## 第十九批（188）：主监督补充（2026-10-17，对照《小程序改动清单》B11 的剩余项；已过补卡前置检查：`pages/index/index.wxml` 不在 Codex 文件清单里，测试里也没有相反的决定）
| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 188 | 首页动态图片与标签列表 key | `pages/index/index.wxml:220,224,263,267` 的 `feedTags` 和 `feedImages` 用了 `wx:key="*this"`。同一条动态里两张相同的图片 URL（或两个相同标签）会造成 key 重复，渲染时可能错位。改为 `wx:key="index"`（`feedImages` 已有 `wx:for-index="gi"`，用 `wx:key="gi"` 即可）。只改 key，不改结构和样式。补一条单测，断言这四处不再是 `*this`。 | 现有页内修复 |

## 交 Codex（文件归 Codex，Claude 线不做）
- BL-185 主题发布前检查：只能接在 `pages/publish/fabu/index.js`「编辑既有主题 → 发布」之前，调 `GET /api/topic/publish-check?topicId=&scope=`（`ApiTopicStateKeyController.java:164`），有 error 就不发布，只有 warn 时提示后允许继续。（2026-10-18 核控制分支 `ab75531d`：Codex tasks/BACKEND_CHANGES 尚未登记，仍待 Codex 接）
- BL-186 章节阶段结算：`pages/play/index.wxml:1184` 已有「本章完成」半屏 `finsheet__result`。需要在 `pages/play/index.js` 里接 `GET /api/play/journey/stage-end`（`ApiPlayJourneyController.java:27`），取数与降级方式照 `utils/life-archive.js` 的 `/ending`。（2026-10-18 同上，尚未登记）
- BL-187 客户页定向广播：与 D-001/RUN-001 冲突，作废。如需恢复由用户决定。
- 改动清单 B9 退款被拒订阅：`utils/subscribe.js` 只定义了 `refundRejected` 模板，全仓没有一处请求它。报名时已经申请了 3 个模板（`pages/activity/baoming/baoming.js:1644`，一次最多 3 个），所以应改在取消报名或退款发起处请求 `['refund','refundRejected']`。`subscribe.js` 归 Codex B01。（2026-10-18：Codex 已接，`tasks.json` B01 与 `BACKEND_CHANGES.md:298` BK-XCX(B01) 写明三个退款 handler 在用户点击链请求 refund/refundRejected；待其源码 PR 合入后复核）
- 改动清单 P1-033（已复核，确认是缺陷）：`pages/mylike/mylike.js:257-260`。取消点赞成功后用点击时的 `index` 执行 `splice`。如果两条不同记录的取消先后返回，第二条会删错行。应改为按 `id` 过滤。`mylike.js` 归 Codex B01。（2026-10-18：Codex 已接，`BACKEND_CHANGES.md:298` BK-XCX(B01)「点赞按稳定对象ID」；待其源码 PR 合入后复核）

## 第二十批（189）：监督补充（2026-10-17；真实缺陷，同 BL-188 类。已过补卡前置检查：涉及文件均不在 Codex `tasks.json` files/owned_files 与 TASKS.md Codex 独占清单内；`tests/unit` 无断言要求这些位置保留 `*this`（`list-render-perf-contract` 只说 `*this` 合法）；纯模板修正、不接接口）
| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 189 | 主题详情与商家申请图片/标签列表 key | 已合并的 BL-22（`96675007`）改了 16 个 wxml，漏了这几处可能重复的展示列表，仍是 `wx:key="*this"`：`pages/topic/index/index.wxml:83`（主题图 `img.list(info.imgArr)` 轮播，同图可重复）、`:241`（`info.contentIncludes` 活动内容）、`:585`（评价 `uploadImages`，删除按 `index`）；`pages/topic/merchantapply/index.wxml:113`（`picUrl.split(',')` 场地照片，删除按 `data-imgindex`）；`pages/topic/components/project-join/index.wxml:217,483`（`play.chips` / `nodeSheet.play.chips`）。全部改为 `wx:key="index"`。只改 key，不改结构、样式和文案；不动静态选项/刻度/星级（`starList` 已是下标数组）；补一条静态单测断言这 6 处不再是 `*this` | `pages/topic/**` wxml；不碰 Codex 文件 |

## 第二十一批（190–192）：主监督补充（2026-10-17；真实缺陷，与已交 Codex 的 P1-033 同类：异步成功回调里用点击时的 `index` 执行 `splice`，列表在请求期间变化（另一条操作先返回、下拉刷新、重新加载）就会删错行。已过补卡前置检查：涉及文件均不在 Codex `tasks.json` files/owned_files 与 TASKS.md Codex 独占清单内；`tests/unit` 无断言要求保留按下标删除；接口均已有调用，只改回调里的本地移除，不新增接口）
| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 190 | 路线内容取消点赞删错行 | `components/cy/scene-route-content/index.js:346-366` `topicUnlike` 在确认弹窗后发 `/api/topic/like`（type 0），成功回调用点击时的 `index` 执行 `items.splice(index, 1)`。连续取消两条时，先返回的那条删掉后，后返回的那条下标已偏移，会删掉别的行。改为在成功回调里按 `item.id` 从 `this.data.items` 过滤（找不到就不动），`state` 仍按剩余条数算。只改回调里的移除逻辑，不改结构、样式和文案；补一条单测：两条先后返回时删掉的是正确的两条 | `components/cy/scene-route-content/index.js` |
| 191 | 广场动态删除/举报与评论举报删错行 | `pages/square/list/index.js:371-388`（`deleteCurrentSquarePost`，`/api/creativesquare/delete`）、`:418-444`（`reportSquare`，`/api/creativesquare/report`）、`pages/square/detail/index.js:826-851`（`reportComment`，`/api/comment/report`）都在成功回调里用发起时的 `index` 执行 `list.splice(index, 1)`；请求期间下拉刷新或另一条先返回就会摘掉别人的行。三处统一改为按发起时记下的 `item.id` 过滤（找不到就不动），`nodata` 按剩余条数算。不改弹窗、文案、样式；补单测覆盖「请求期间列表被替换」与「两条先后返回」 | `pages/square/list/index.js`、`pages/square/detail/index.js` |
| 192 | 我的模板删除删错行 | `subpackageMember/mytemplate/mytemplate.js:92-100` `deleteItem` 把点击时的 `index` 传给 `deleteTemplate`，`:309-320` 成功后直接对 `that.data.list` 原地 `splice(index, 1)`。下拉刷新/翻页重载后返回会删错行，而且原地改 `this.data` 不安全。改为成功后按 `id` 生成新数组再 `setData`（找不到就不动），保留现有空态/错误态逻辑。不改结构、样式和文案；补一条单测 | `subpackageMember/mytemplate/mytemplate.js` |

## 第二十二批（193–195）：主监督补充（2026-10-17；真实缺陷，与 BL-190–192 同类：异步成功回调按点击时的 `index` 回写 `list[index]`，请求期间下拉刷新/翻页/另一条先返回，就会把点赞、收藏、关注状态写到别的行上。已过补卡前置检查：涉及文件不在 Codex `tasks.json` files/owned_files 与 TASKS.md Codex 独占清单内（`cy/profile` 只有 index.json/wxss 登记给 Claude CL4）；`tests/unit` 无断言要求按下标回写（`square-follow-target-state`、`square-reliability-state-contract`、`square-comment-like-server-state` 只断言意图态与开关终态，下标 0 不变）；接口均已有调用，只改回调里的本地回写，不新增接口）
| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 193 | 广场列表点赞/关注回写错行 | `pages/square/list/index.js:182-210` `lickClick` 成功后取 `that.data.list[index]` 原地改 `isLiked/likeCount`，没核对 id；`:283-286` `followClick` 用点击时抓的 `topicList` 快照 `slice()` 后整表 `setData`，请求期间列表刷新过会把旧列表整个写回。两处都照同文件 `favoriteClick`（`:252-258`）已有写法：成功后按 `item.id`（关注按发起时的 `item.id` 定位该行）在当前 `this.data.list` 里找，找不到就不回写，生成新数组/新对象再 `setData`，不原地改。意图态、开关语义、toast、埋点不变；不改样式和文案。与 #246 改的是同文件不同函数。补单测：请求期间列表被替换时不写错行、不回滚整表 | `pages/square/list/index.js` |
| 194 | 广场详情评论点赞回写错行 | `pages/square/detail/index.js:677-686` `commentLikeClick` 成功后取 `that.data.list[index]` 原地改 `isLiked/likeCount` 再整表 `setData`，没核对 id；评论区加载更多/重新拉取后返回会点亮别人的评论。改为按 `item.id` 在当前列表里定位（找不到就不动），生成新对象写回；仍以回包 `data.isLiked` 为终态、缺省按开关取反（逻辑不变）。不改样式和文案。与 #246 改的是同文件不同函数。补单测 | `pages/square/detail/index.js` |
| 195 | 个人主页动态点赞/收藏回写错行 | `subpackageMember/components/cy/profile/index.js:1766-1788` `lickClick` 成功后取 `that.data.list[index]` 原地改并写 `list[index]`；`:1790-1812` `favoriteClick` 成功后直接写 `list[index].isBookmarked`。两处都没核对 id，`onFeedReachBottom` 翻页或切 tab 重载后返回会写到别的动态上。改为按发起时的 `item.id` 在当前 `that.data.list` 里定位（找不到就不回写），生成新对象再 `setData`，不原地改。不改结构、样式和文案。补单测 | `subpackageMember/components/cy/profile/index.js` |

## 第二十三批（196–197）：主监督补充（2026-10-17；真实缺陷，俱乐部详情动态区。已过补卡前置检查：`pages/club/detail/**` 不在 Codex `tasks.json` files/owned_files 与 TASKS.md Codex 独占清单内，当前 open PR 也没有改这个文件；`tests/unit` 无相反断言（`club-post-reference-contract` 只断言 `onToggleComments` 回退，不受影响）；接口均已有调用，已读 `ApiClubController`（集成分支 `:1639` `/post/like` 切换式返回 `{liked}`，`:1830` `/post/comment/list` 失败返回「缺少动态ID / 动态不存在」，`:1903/:1920` 举报成功 msg 为「已举报,平台将尽快处理」、服务端幂等），不新增接口、不改后端）
| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 196 | 俱乐部动态评论加载失败显示成「还没有评论」 | `pages/club/detail/index.js:2832` `loadComments` 把非 200 和网络失败都当成 `rows=[]`（且无 `fail`），评论区渲染 `index.wxml:171` 的「还没有评论，来抢沙发」，把「没加载到」说成「没有」（同页帖子列表 E-09 已修过同类问题）。另外 `:2825` `onToggleComments` 快速切换两条动态时，先发的那次返回会把 A 的评论写到正在展开的 B 下面。改为：发起时记下 `postId`，回调里 `this.data.openCommentPost !== postId` 就丢弃；失败（业务失败用 `bizFailureMessage(res, '评论没能加载出来')`，网络失败同样处理）写 `commentsError`，评论区用本页已有的 `cy-error size="sm"`（照 `:180` 帖子错误态写法，`retry` 重新拉当前动态的评论）代替空态；空态只在成功且为空时出现。`onCommentSubmit`/删除评论后的重载沿用同一函数。不改现有布局、样式和文案；补单测：失败不出空态、切换动态时旧响应不覆盖 | `pages/club/detail/index.js` + `index.wxml` 评论区一行 |
| 197 | 俱乐部动态点赞/举报失败无反馈 | `pages/club/detail/index.js:2814` `onLikePost` 非 200（「请先登录」「动态不存在」）时什么都不发生，网络失败只给裸文案「操作失败」；接口是切换式，连点两下会先赞后取消。`:1567` `onReportPost`、`:2543` `onReportComment` 没有 `fail`，网络失败时用户点了「举报」毫无反应。改为：点赞按 `postId` 记进行中，返回前忽略同一条的重复点击；业务失败提示 `bizFailureMessage(res, '点赞没成功')`，网络失败提示「网络异常，请重试」（与同页 `onCommentSubmit` 一致）；成功仍只 `loadPosts()` 回读，不做本地乐观改数。两处举报补 `fail` 提示「网络异常，请重试」，成功/业务失败仍用服务端 msg。不改弹窗、样式和文案；补单测覆盖业务失败提示、重复点击只发一次、举报网络失败有提示 | `pages/club/detail/index.js` |

## 第二十四批（198–199）：主监督补充（2026-10-17；真实缺陷：写接口无在途保护，快速连点会重复提交。已过补卡前置检查：`pages/topic/index/index.js`、`pages/activity/official-detail/index.js` 不在 Codex `tasks.json` files/owned_files 与 TASKS.md Codex 独占清单内，当前 open PR 与各 `claude/bl-*` 分支都没改这两个 js；`tests/unit` 无断言把重复提交当作预期（`primary-action-form-state-contract` 只断言 wxml `disabled="{{!canSubmitComment}}"` 与 `refreshCommentState()`，`remaining-scene-state-contract:542-558` 反而要求活动版评论单飞）；接口均已有调用，已读后端：`ApiCommentController` `/api/comment/add` 只限流 30 次/60s、无去重，`CommentPublicationService` 每次都插入；`OfficialEventServiceImpl.completeTask:127-156` V1 每调一次 progress+1（封顶 taskCount）并发一次 `instantXp`，`memberId` 取自 token。传输层 `request-client.js` 不加载遮罩，挡不住连点。不新增接口、不改后端、不改 wxml）
| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 198 | 路线评价连点发两条 | `pages/topic/index/index.js:694-733` `submitComment` 只有 `if (!that.data.canSubmitComment) return;`，请求期间半屏不关、按钮不禁用，连点「发布评价」会发两次 `/api/comment/add`，生成两条相同评价、弹两次「评价成功」、`getData()` 跑两遍。照 `subpackageMember/components/cy/scene-play-activity-detail/index.js:532-567` 同一接口已有的单飞写法：用实例标记 `this._commentSubmitting`，开头判断、发请求前置真，`success`/`fail`/`successStatusAbnormal`（或 `complete`）里清掉。toast、`voteClose()`、`getData()`、`app.tips(res.msg)` 不变；**不改 `index.wxml`**（#244 正在改该页 wxml）。补单测：连调两次只发 1 个请求；回包后可再次提交 | `pages/topic/index/index.js` |
| 199 | 官方活动「完成任务」连点记两次 | `pages/activity/official-detail/index.js:491-506` `doComplete`（底部 CTA `去探索 · 完成任务`，经 `onCta` :392）无在途保护，V1 活动连点会让服务端 progress 记两次、即时 XP 发两次，弹两次「完成 +1」。`doSignup`（:470-489）同样无保护（服务端 `insertIgnore` 幂等，但会弹两次订阅与「报名成功」、`fetch()` 两遍）。两处共用实例标记 `this._ctaBusy`：开头判断、发请求前置真、`complete` 里清掉。toast、`motion.haptic`（`p1-feedback-wiring-contract:66` 要求保留在「报名成功」附近）、`fetch()`、V2 的「任务完成需现场验证」分支不变；不改 wxml 与样式。补单测：连调两次 `onCta()` 只发 1 个请求；回包后可再次触发 | `pages/activity/official-detail/index.js` |

## 第二十五批（200）：主监督补充（2026-10-17；真实缺陷，同 BL-198/199 类：写接口无在途保护。已过补卡前置检查：`pages/topic/merchantinfo/merchantinfo.js` 不在 Codex `tasks.json` files/owned_files 与 TASKS.md Codex 独占清单内，当前 open PR 与各 `claude/bl-*` 分支都没改这个文件；`tests/unit` 里没有断言把 `cancelRegistration` 的重复提交当作预期（无测试引用该函数）；接口已有调用，已读后端：`ApiRegistrationMerchantController.java:83-117` `/api/registration/merchant/cancel` 要 `PROJECT_MANAGE`，`CmsRegistrationMerchantServiceImpl.cancelByMerchant` 校验归属、拦已中标，只把 `del_flag` 置 2，不涉及资金；`selectCmsRegistrationMerchantById` 不过滤 `del_flag`，第二次取消同样返回「报名已取消」。本轮其余候选（俱乐部公开团加入连点、商家营业状态切换、据点扫码核销）核实后影响不足或已有保护，不建卡）
| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 200 | 商家取消参与连点后连退两页 | `pages/topic/merchantinfo/merchantinfo.js:3638-3660` `cancelRegistration`（经 `onPrimaryTap` :2745 与管理菜单 :3131 进入）确认后直接发 `/api/registration/merchant/cancel`，没有在途保护；成功后 `setTimeout(() => wx.navigateBack(), 1500)`。请求期间或「已取消」后的 1.5 秒内再点一次并确认，会再发一次取消（服务端同样成功），弹两次「已取消」，并排两个 `navigateBack`，第二个会把上一页也退掉。照同文件 `_chapterWithdrawing`（:1133-1147）的写法：用实例标记 `this._cancelingRegistration`，在打开确认弹窗前判断，确认后、发请求前置真；业务失败与网络失败（补一个 `fail`，文案照同文件现有写法，不裸文案）时清掉；成功后保持置真，直到页面返回，防止 1.5 秒窗口内重复取消。确认弹窗的标题与正文、`cyToast.success('已取消')`、`app.tips(res.msg || '取消失败')` 都不变；不改 wxml 和样式。补单测：确认后连调两次只发 1 个请求、只排 1 个 `navigateBack`；业务失败后可以再次取消 | `pages/topic/merchantinfo/merchantinfo.js` |

## 第二十六批（201）：主监督补充（2026-10-18；真实缺陷，同 BL-198–200 类：不可逆写接口无在途保护，成功后入口仍在。已过补卡前置检查：`pages/topic/index/index.js` 不在 Codex `tasks.json` files/owned_files 与 TASKS.md Codex 独占清单内（`ApiTopicController` 归 Codex，本卡只读不改）；`tests/unit` 无任何测试引用 `transfer-to-club` / `onClubSheetSelect`，没有相反决定；接口已有调用，已读后端：`ApiTopicController.java:1033-1060` `/api/topic/transfer-to-club` 校验登录、`OwnershipUtils.notOwner` 归属与承接俱乐部合法性，`CmsTopicServiceImpl.transferToClub:460-486` 每次调用都 `copyCmsTopic` 复制一份新主题（含章节、节点）并把原主题 `userStatus` 置 0，**不检查原主题是否已经下架或已移交**，所以重复调用会再复制出一份草稿。本轮其余候选（俱乐部加入、勋章创建、据点认领/撤回、治理转让/举报、候补加入/取消、标签移除、补位邀请、点位提交）核实后已有在途保护或服务端幂等，不建卡）
| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 201 | 主题移交俱乐部重复复制 | `pages/topic/index/index.js:845-882` `onClubSheetSelect` 在确认弹窗「确认移交」后直接发 `/api/topic/transfer-to-club`，没有在途保护；成功后弹「已移交」，选「稍后」会留在原页面，而 `index.wxml:106-108` 的「移交给俱乐部承接」入口（`info.isOwner==1`）仍在，页面也不回读。请求期间或成功后再走一遍「移交 → 选俱乐部 → 确认移交」，服务端会再复制一份新主题草稿。照 BL-200（`merchantinfo.js` `_cancelingRegistration`）的写法：用实例标记 `this._transferringToClub`，在 `onTransferToClub`（:820）开头和确认回调里、发请求前都判断，发请求前置真；业务失败（`app.tips(r.msg \|\| '移交失败')`）与网络失败时清掉；成功后保持置真，直到页面重新加载，之后再点入口提示「已移交，请到新主题继续配置」（用现有 `app.tips`，不改 wxml）。确认弹窗的标题与正文、「已移交」弹窗与「去配置」跳转都不变；不改 wxml 和样式，不改后端。与 #253 改的是同文件不同函数，合并后台账交协调者重新生成。补单测：确认后连调两次只发 1 个请求；成功后再调 `onTransferToClub` 不再请求 `/api/club/my`；业务失败后可以再次移交 | `pages/topic/index/index.js` |

## 第二十七批（202–205）：协调者补充——新页优化（2026-10-18；对本线新建的 9 个页面逐页复查后的真实缺陷。官方主办、俱乐部勋章、触达记录详情、收藏据点已有在途/过期回包保护，不建卡。已过补卡前置检查：涉及文件均不在 Codex `tasks.json` files/owned_files（控制分支 `ab75531d`）与 TASKS.md Codex 独占清单内；`tests/unit` 无断言要求保留现状（`merchant-correction-records-bl184`、`report-transparency-n1`、`merchant-verify-records*`、`merchant-crm-campaigns*` 都不涉及分页重置、`goMine` 跳转方式或下拉刷新）；接口均已有调用，不新增接口、不改后端；不改结构、样式、标题和文案，只改行为）
| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 202 | 我的纠错/我的举报返回后列表缩回、下拉转圈不收 | `subpackageMember/correction-mine/index.js:38-49` 与 `subpackageMember/report-mine/index.js:44-56` 的 `_settle` 每次成功都 `this._shown = view.PAGE_SIZE`。`onShow` 的静默回读（从详情返回）也会把已展开到 40/60 条的列表缩回 20 条，用户刚看的那条可能不在了、滚动位置丢失。另外 `_settle` 先判 `st.seq !== this._seq` 再 `wx.stopPullDownRefresh()`：下拉期间若有另一次 load（如 `retry`、`onShow`）后发先至，下拉那次被丢弃，转圈不会收。改为：只有非 quiet（首屏/下拉/重试）才重置 `_shown`，quiet 回读保留当前展开条数（`_sync` 里已有 `Math.min` 截断）；`if (st.fromPull) wx.stopPullDownRefresh()` 移到 seq 判断之前。两页同改，各补单测（quiet 回读后 `rows.length` 保持 40；下拉被后发请求取代时仍调用 `stopPullDownRefresh`） | `subpackageMember/correction-mine/index.js`、`subpackageMember/report-mine/index.js` |
| 203 | 纠错进度/举报进度「查看我的全部」叠页 | `subpackageMember/correction-detail/index.js:142` 与 `subpackageMember/report-detail/index.js:164` 的 `goMine` 一律 `wx.navigateTo` 列表页。从列表点进详情再点「查看我的全部…」，页面栈变成 列表 → 详情 → 列表，返回要按两次，多点几次还会叠到 10 层上限。改为：上一页（`getCurrentPages()` 倒数第二个）的 `route` 就是对应列表页时 `wx.navigateBack()`，否则照旧 `navigateTo`（从系统消息/商家纠错成功态进来时）。同时两页 `onPullDownRefresh` 在缺参数（`!this._id`）时 `load` 直接返回，转圈不收；`_settle` 也是先判 seq 再收转圈。改为缺参时直接 `wx.stopPullDownRefresh()`，`fromPull` 的收转圈移到 seq 判断之前。各补单测 | `subpackageMember/correction-detail/index.js`、`subpackageMember/report-detail/index.js` |
| 204 | 商家核销记录/触达记录不能下拉刷新 | `pages/merchant/verify-records`、`pages/merchant/crm-campaigns` 只在 `onLoad` 读一次，核销员在别的页核销完回来看不到新记录，只能退出重进；`loadList` 也没有过期回包保护，`retry` 与首读并发时旧回包会覆盖新回包。照 `pages/merchant/relation`（`index.json:24` `enablePullDownRefresh`、`index.js:259` `onPullDownRefresh`）的写法：两页 `index.json` 加 `"enablePullDownRefresh": true`；`onPullDownRefresh` 有权限时重读列表，无权限/失败态时走 `retry`；已有列表时下拉不切回骨架屏，失败只轻提示「刷新失败，稍后再试」并保留列表；`loadList` 加 `_listSeq`，过期回包丢弃；任何路径都 `wx.stopPullDownRefresh()`。`verify-records` 下拉后保留当前分段 `bucket`。不改结构、样式和文案。补单测 | `pages/merchant/verify-records/index.{js,json}`、`pages/merchant/crm-campaigns/index.{js,json}` |
| 205 | 官方主办页检查项列表 key | `pages/activity/official-organizer/index.wxml:45,47` 的 `check.blockers`、`check.risks` 是服务端文案数组，两条相同文案时 `wx:key="*this"` 重复。改为 `wx:key="index"`（同 BL-188/189）。`:67` `sec.presets` 是本地常量且互不相同，不动。补静态单测 | `pages/activity/official-organizer/index.wxml` |

## 第二十八批（206–207）：主监督补充（2026-10-18；真实缺陷：写接口无在途保护、刷新失败无反馈。已过补卡前置检查：`subpackageMember/coupon/coupon.js`、`pages/activity/official-inbox/index.js` 不在 Codex `tasks.json` files/owned_files 与 TASKS.md Codex 独占清单内（券服务/mapper 归 Codex，本批只改页面不改后端；`official-inbox` 在 OWNERSHIP 登记给 Claude CL4），当前 open PR（#35、#257）与各 `claude/bl-*` 分支都没改这两个文件，`ui-integrity-baseline.js` 里也没有这两个文件的锚点；`tests/unit` 无相反断言（`coupon-decisions-0917-contract` 要求取消后可再次停发、确认后只发 1 个 `/api/coupon/stop`、成功回读列表，并对 `if (!item \|\| !item.id \|\| !item.canStop) return;` 这一行做负控，必须原样保留；`official-pages-state-contract:59-111` 要求写操作单飞、回读完成前保留旧快照，不受影响）；接口均已有调用，已读后端：`ApiCouponController.java:203-252` `/api/coupon/stop` 校验登录、`COUPON_MANAGE`、`OwnershipUtils.notOwner` 与商家 scope，已停发时返回「该优惠券已停发,无需重复操作」，CAS 只改 status；`ApiOfficialEventController.java:173-178` `/v2/party-inbox` 只按 token 的 `memberId` 读本人邀约。不新增接口、不改后端、不改 wxml/样式/文案）
| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 206 | 停发优惠券连点报错、列表旧回包覆盖 | `subpackageMember/coupon/coupon.js:185-217` `stopCoupon` 确认后直接发 `/api/coupon/stop`，没有在途保护；详情半屏在回包前不关，「停发该券」仍可点。请求期间再点一次并确认，第一次弹「已停发「xx」」，第二次弹服务端的「该优惠券已停发,无需重复操作」错误提示，`getList()` 也跑两遍。另外 `getList`（:117-148）没有过期回包保护，`onShow`、`retryLoad`、停发成功/网络失败的回读互相并发时，先发后到的旧列表会把「已停发」状态签覆盖回「进行中」。改为：用实例标记 `this._stopping`，在 `if (!item \|\| !item.id \|\| !item.canStop) return;`（**这一行原样保留**，有负控单测）之后、打开确认弹窗前判断，确认后、发请求前置真，`success`/`fail` 里清掉；取消确认不置真。`getList` 加 `this._listSeq`，回调里不是最新一次就丢弃。toast 文案、危险确认 `merchant.coupon.stop`、成功关半屏并回读、网络失败也回读都不变；不改 wxml 和样式。补单测：确认后连调两次 `stopCoupon` 只发 1 个停发请求；取消后仍可再次停发；两次 `getList` 后发先至时旧回包不覆盖 | `subpackageMember/coupon/coupon.js` |
| 207 | 官方邀约下拉刷新失败无反馈 | `pages/activity/official-inbox/index.js:229-234` `setInboxFetchFailure` 在已有列表（`hasSnapshot`）时只收起 `refreshing`，不给任何提示：下拉刷新失败后转圈收起、列表原样，用户以为已是最新。更要紧的是接受/拒绝成功后（:115-120）的回读如果失败，卡片仍显示「待你确认」和操作按钮，`_expectReadback` 也没有消费，用户会再点一次，得到「操作未完成」。改为：`fetch` 记下本次来源（下拉 `onPullDownRefresh`、动作后回读、`onShow` 静默回读），已有列表时失败：动作后回读失败提示同文件已有文案「已提交，状态未同步，稍后下拉刷新」并清掉 `_expectReadback`；下拉失败轻提示「刷新失败，稍后再试」（照 `subpackageMember/correction-mine/index.js:44`）；`onShow` 静默回读失败仍不提示。首屏失败的错误态、过期回包丢弃、`complete` 里收转圈都不变；不改 wxml、样式和其他文案。补单测：下拉失败有轻提示且保留列表；动作成功后回读失败提示「已提交，状态未同步」；`onShow` 回读失败不提示 | `pages/activity/official-inbox/index.js` |

## 第二十九批（208）：协调者补充——新组件移出主包（2026-10-18；本分支主包已超 3 MiB 棘轮 49 KB，见 TASKS.md「主包体积」。已过补卡前置检查：涉及文件不在 Codex `tasks.json` files/owned_files 与独占清单内（`cy/profile` 的 json/wxml 登记给 Claude CL4）；`tests/unit` 只是用路径读文件，无断言要求组件留在根目录；不涉及接口。`report-sheet`（被 subpackageB 与 subpackageMember 同时使用）和 `npc-chat`（主包 `pages/roam` 使用）不能移，不建卡）
| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 208 | 商家纠错半屏移进会员分包 | `components/cy/merchant-correction-sheet/`（4 个文件约 11.5 KB，BL-67 新建）只被 `subpackageMember/components/cy/profile/index.json` 使用，却放在主包根目录。用 `git mv` 整体移到 `subpackageMember/components/cy/merchant-correction-sheet/`，改 `profile/index.json` 的 usingComponents 路径；组件内部 `require`/`usingComponents` 的相对路径按新位置改对（被它引用的根目录组件、utils 保持原位，用绝对路径 `/components/...`、`/utils/...` 或正确的相对层级）。同步改 `subpackageMember/correction-common/view.js` 里如有的路径注释、三个单测（`merchant-correction-bl67`、`merchant-correction-merchant-id`、`merchant-correction-records-bl184`）读文件的路径，重建 `action-ledger.json`。不改组件内容、样式和文案。PR 正文写明移动前后按 `scripts/preflight-release.js` 4.5 口径的主包字节数 | `components/cy/merchant-correction-sheet/**` → `subpackageMember/components/cy/merchant-correction-sheet/**`、`subpackageMember/components/cy/profile/index.json`、上述 3 个单测 |

## 第三十批（209–210）：主监督补充（2026-10-18；对本线新建页面复查后的真实缺陷：下拉转圈不收、静默回读把空态换成错误态。已过补卡前置检查：`pages/club/badges/**`（OWNERSHIP 登记给 Claude CL3）、`pages/activity/official-organizer/**`、`subpackageRoam/node-favorites/**` 不在 Codex `tasks.json` files/owned_files 与 TASKS.md Codex 独占清单内，当前 open PR（#35）与各 `claude/bl-*` 分支都没改这些文件，`ui-integrity-baseline.js` 里没有这三个文件的锚点；`tests/unit` 无相反断言（`club-badge-manage.test.js:77` 只要求缺 clubId 时进入 `missing-param` 且不发请求、`official-organizer-console.test.js:172` 只要求缺 id 时 `phase='missing'`，`roam-node-favorites` 不涉及 onShow 回读失败）；接口均已有调用，已读后端：`ApiClubController.java:1174-1180` `/api/club/badge/list` 要登录、缺 id 报「缺少俱乐部ID」；`ApiOfficialEventController.java:272-276` `/v2/events/{id}/publish-check` 校验 `canPublish` 与 `canReadControlledEvent`；`ApiCityNodeController.java:104-111` `/api/city/nodes/favorites` 只按 token `memberId` 读本人收藏、未登录报「请先登录」。不新增接口、不改后端、不改 wxml/样式/文案；**两卡都不出现 `wx.navigateBack` 改动**）
| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 209 | 俱乐部勋章/官方主办页缺参时下拉转圈不收 | 两页 `index.json` 都开了 `enablePullDownRefresh`，但缺参数时下拉的转圈永远不收（同 BL-203 修过的纠错/举报详情）：`pages/club/badges/index.js:118` `onPullDownRefresh() { this.loadAll() }` → `:129` `loadAll` 在 `!this._clubId` 时直接 `return`，`finish`/`stopPullDownRefresh` 都不会走到（缺 clubId 时 `onLoad` :51-54 已置 `state:'missing-param'`）；`pages/activity/official-organizer/index.js:82` `onPullDownRefresh() { this.load(); }` → `:92` `load` 在 `!this._id` 时直接 `return`（`onLoad` :66-68 已置 `phase:'missing'`），`stopPullDownRefresh` 只挂在请求的 `complete` 上。改为照 BL-203 的写法：两处 `onPullDownRefresh` 缺参时直接 `wx.stopPullDownRefresh()`，有参时照旧走 `loadAll()`/`load()`；`loadAll`/`load` 本身的缺参早返回不动（单测要求缺参不发请求）。不改 wxml、样式和文案，各页净行数尽量不变。补单测：缺参时调用 `onPullDownRefresh` 会调用一次 `stopPullDownRefresh` 且不发请求；有参时行为不变 | `pages/club/badges/index.js`、`pages/activity/official-organizer/index.js` |
| 210 | 收藏据点回到页面网络抖动时空列表变成错误页 | `subpackageRoam/node-favorites/index.js:16` `onShow` 的静默回读（从据点详情返回）失败时，`:32`（业务失败）与 `:41`（网络失败）只在 `state === 'ready'` 时保留现状，`state === 'empty'` 时会把正确的「还没有收藏」空态换成整页「收藏数据暂时不可用」错误态。同类页 `subpackageMember/correction-mine/index.js:27-28,42-43` 是 `ready` 和 `empty` 都算「已有数据」、静默失败不打扰。改为：两处保留条件改成 `quiet && (state === 'ready' \|\| state === 'empty')`，静默回读失败不改页面、不提示；首屏失败的错误态、`seq` 过期回包丢弃、成功时以回读为准（包括从有到空）都不变。不改 wxml、样式和文案。补单测：empty 状态下 `onShow` 静默回读业务失败/网络失败都保持 empty；首屏失败仍进 error | `subpackageRoam/node-favorites/index.js` |

## 第三十一批（211–212）：主监督补充（2026-10-18；对本线新建页面复查后的真实缺陷：列表行连点叠两层详情页。已过补卡前置检查：`subpackageMember/report-mine/index.js`、`subpackageMember/correction-mine/index.js`、`subpackageRoam/node-favorites/index.js`、`subpackageB/pages/im/list/index.js` 不在 Codex `tasks.json` files/owned_files（控制分支 `88130a18`）与 TASKS.md Codex 独占清单内，当前 open PR（#35）不改这些文件；前三个文件在 `ui-integrity-baseline.js` 里没有锚点，`im/list/index.js` 有 `:229` 锚点（见 BL-212 行数要求）；`tests/unit` 无相反断言（`report-transparency-n1.test.js:322-369` 只要求点通知先跳转、已读的不再发、失败不挡查看，三次点击之间没有返回列表，靠桩补 `complete` 即可保持；三张列表页的单测都不连点打开详情）；接口均已有调用，不新增接口、不改后端，已读后端：`ApiCommunityPostController.java:231-236` `/api/v1/community/notifications/{id}/read` 要登录，`CommunityPostServiceImpl.readNotification:824-830` 按 `recipient_member_id` 归属更新、`COALESCE(read_at)` 幂等；列表/详情读接口按 token 读本人记录。现有写法参照 `pages/coop/list/index.js:614-618` `_navigateTo`（`_navigating` 标记，`complete` 里清掉）。不改 wxml、样式、标题和文案；改动里不出现 `wx.navigateBack`。新增接口候选本轮核对：小程序零调用的 `/api/*` 端点已在 BL-62–125/185–186 建过卡或属会费/群聊/投票/约伴/剧透/草稿/资金/客户群发/Codex 文件范围，不再建卡）
| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 211 | 我的举报/我的纠错/收藏据点连点叠两层详情 | `subpackageMember/report-mine/index.js:81-85` `openDetail`、`subpackageMember/correction-mine/index.js:73-77` `openDetail`、`subpackageRoam/node-favorites/index.js:46-49` `openNode` 都直接 `wx.navigateTo`，没有跳转中保护：手快连点同一行（或先后点两行），页面栈会叠两层详情，返回要按两次，还可能打开两个不同的详情。照 `pages/coop/list/index.js:614-618` 的写法：三页各加实例标记 `this._navigating`，开头判断，跳转前置真，`wx.navigateTo` 的 `complete` 里清掉；`onShow` 里也清一次（防止个别机型 `complete` 不回调时永久锁住）。跳转地址、参数校验（`view.positiveId`、`if (id)`）不变，`url` 仍写成字面量拼接以便 U1 路由门禁核对。不改 wxml、样式和文案。各补单测：桩里 `navigateTo` 不回调 `complete` 时连调两次只跳 1 次；回调 `complete` 后可再次打开；`onShow` 后可再次打开 | `subpackageMember/report-mine/index.js`、`subpackageMember/correction-mine/index.js`、`subpackageRoam/node-favorites/index.js` |
| 212 | 系统消息举报进度连点叠两层举报详情 | `subpackageB/pages/im/list/index.js:144-147` `openReportNotice` 每次点击都 `wx.navigateTo` 举报详情并调 `markReportNoticeRead`（:454），没有跳转中保护：连点同一条通知会叠两层举报详情，并发两次已读请求（后端幂等，但多一次回读 `loadReportNotices`）。照 BL-211 同一写法：实例标记 `this._navigating`，开头判断、跳转前置真、`navigateTo` 的 `complete` 与本页 `onShow` 里清掉；只有真正发起跳转时才调 `markReportNoticeRead`，「先跳转、不等已读结果」「已读的不再发」「失败不挡查看」都不变。**行号锚点**：本文件 `ui-integrity-baseline.js` 有 `index.js:229` 锚点，在 `:229` 之上的净增减行数必须为 0（`openReportNotice` 的改动写在原 3 行内，或在同一函数内用等量行替换；`onShow` 在 :45 也在锚点之上，清标记写进现有某一行），提交前按 TASKS.md「集成说明（2026-10-18）」对比 `node tests/lint/ui-integrity.js` 输出。`report-transparency-n1.test.js` 的 `loadPage` 桩 `navigateTo: (o) => nav.push(o.url)`（:193）改为同时回调 `o.complete`（模拟真实 wx 行为），现有断言一条不改、不删。补单测：桩不回调 `complete` 时连调两次只跳 1 次、只发 1 个已读请求；`complete` 后可再次打开 | `subpackageB/pages/im/list/index.js`、`tests/unit/report-transparency-n1.test.js` 桩一行 |

## 第三十二批（213–214）：主监督补充（2026-10-18；对本线新建页面/组件复查后的真实缺陷：跳转入口连点叠页或连退两页，同 BL-211/212 类。已过补卡前置检查：`subpackageMember/components/cy/merchant-correction-sheet/index.js`、`subpackageMember/correction-detail/index.js`、`subpackageMember/report-detail/index.js` 不在 Codex `tasks.json` files/owned_files（控制分支 `88130a18`，会员分包只登记了订单/参与详情组件）与 TASKS.md Codex 独占清单内，当前 open PR（#35）不改这些文件，`ui-integrity-baseline.js` 里没有这三个文件的锚点；`tests/unit` 无相反断言（`merchant-correction-records-bl184.test.js:151-153` 只要求 wxml 绑定 `openMine` 且 js 含字面量 `/subpackageMember/correction-mine/index`；`detail-go-mine-bl203.test.js:45-62` 每个桩只调一次 `goMine`，加跳转中保护后仍成立）；不新增接口、不改后端，目标页读接口已读：`ApiCommunityTrustController.java:119` `GET /contributions/mine` 按 token 读本人记录。本线其余新建页/组件（举报半屏、安全感评价半屏、核销/触达记录、勋章、官方主办/邀约、收藏据点）本轮复查已有在途/过期回包保护，不建卡）
| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 213 | 纠错成功态「查看我的纠错」连点叠两层列表 | `subpackageMember/components/cy/merchant-correction-sheet/index.js:109-112` `openMine` 先 `triggerEvent('close')` 再直接 `wx.navigateTo` 我的纠错列表，没有跳转中保护；半屏要等父页 setData 才收起，手快连点「查看我的纠错」会叠两层列表，返回要按两次。照 BL-211 的写法：实例标记 `this._navigating`，开头判断、跳转前置真、`navigateTo` 的 `complete` 里清掉；`reset()`（每次打开半屏）里也清一次兜底。`url` 保持字面量 `'/subpackageMember/correction-mine/index'`（BL-184 单测按字面量核对）。不改 wxml、样式和文案；改动里不出现 `wx.navigateBack`。补单测：桩里 `navigateTo` 不回调 `complete` 时连调两次 `openMine` 只跳 1 次、`close` 事件照常触发；回调 `complete` 后可再次打开；重新 `reset()` 后可再次打开 | `subpackageMember/components/cy/merchant-correction-sheet/index.js` |
| 214 | 纠错/举报进度「查看我的全部」连点连退两页 | BL-203 让 `subpackageMember/correction-detail/index.js:143-148` 与 `subpackageMember/report-detail/index.js:166-171` 的 `goMine` 在上一页是列表时 `wx.navigateBack()`，但没有跳转中保护：从列表进详情后连点「查看我的全部…」，会连发两次 `navigateBack`，第二次把列表也退掉，回到列表之前的页面（如「我的」）；从系统消息进来时走 `navigateTo` 分支，连点会叠两层列表。照 BL-211 的写法：两页各加实例标记 `this._navigating`，`goMine` 开头判断，两个分支跳转前都置真，`navigateBack`/`navigateTo` 的 `complete` 里清掉（`navigateBack` 的 `fail` 兜底 `redirectTo` 保持不变）；两页没有 `onShow`，新增 `onShow() { this._navigating = false }` 兜底。`goBack`、跳转地址与判断条件不变；不改 wxml、样式和文案。**改动涉及 `wx.navigateBack`：提交前必须跑全量 `node --test "tests/unit/**/*.test.js"`，与集成分支对比失败名单**（TASKS.md「集成说明（2026-10-18）」）。补单测（可加在 `detail-go-mine-bl203.test.js` 同风格新文件里）：桩不回调 `complete` 时连调两次 `goMine`，上一页是列表时只有 1 次 `back`、否则只有 1 次 `navigateTo`；`complete` 或 `onShow` 后可再次跳转；BL-203 现有断言一条不改 | `subpackageMember/correction-detail/index.js`、`subpackageMember/report-detail/index.js` |

## 第三十三批（215）：主监督补充（2026-10-18；对本线新建页面复查后的真实缺陷：申诉成功但回读失败时页面仍是旧阶段、还能再点「提交申诉」。已过补卡前置检查：`subpackageMember/report-detail/index.js` 不在 Codex `tasks.json` files/owned_files（控制分支 `88130a18`，会员分包只登记了订单/参与详情组件）与 TASKS.md Codex 独占清单内，当前 open PR（#35）与各 `claude/bl-*` 分支都不改这个文件，`ui-integrity-baseline.js` 里没有它的锚点；`tests/unit` 无相反断言（`report-transparency-n1.test.js:280-297` 只要求 fail 后关闭回读）；接口已有调用，读过 `ApiCommunityTrustController.java:87-98` 与 `CommunityTrustServiceImpl#appealReport`（:176-206，按举报人归属 `selectReportForOwnerForUpdate`，只有 RESOLVED/CLOSED 可申诉，其他阶段 409「当前举报阶段不允许申诉」）；不新增接口与字段）
| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 215 | 申诉成功后回读失败仍显示「提交申诉」 | `subpackageMember/report-detail/index.js:140-156` `_afterAppeal`：服务端已返回申诉回执（`accepted`）但回读 `GET /reports/{id}` 失败时走 `:151` 只把 sheet 置 `ok`，`report` 仍是申诉前的投影（阶段「已处理」、`appealable: true`）；`:157-163` `closeSheet` 只在 `fail`/`unknown` 后回读，`ok` 不回读（同类页 `subpackageMember/correction-detail/index.js:138-139` 在 `ok` 后也回读）。用户点「知道了」后页面仍显示旧阶段和「提交申诉」按钮，再点会换新 requestId 提交，后端按 CAS 拒绝，弹「当前举报阶段不允许申诉」，像是申诉失败了。改为：`closeSheet` 在 `ok` 后也 `this.load(true)`（照 correction-detail）；`:151` 无回读结果的分支同时把 `'report.appealable': false` 写回（后端合同：受理后阶段为 APPEALED，不再可申诉），避免回读前按钮仍可点。保持 `:161-162` 所在区域净行数不变。补单测：申诉成功 + 回读失败 → `report.appealable === false`，关闭后发起一次回读；原 409 用例不变 | `subpackageMember/report-detail/index.js`、对应单测 |

## 第三十四批（216）：主监督补充（2026-10-18；对本线新建页面复查后的真实缺陷：补证成功但回读失败时页面仍可再点「补充说明」，与 BL-215 同类。已过补卡前置检查：`subpackageMember/correction-detail/index.js` 不在 Codex `tasks.json` files/owned_files（控制分支 `88130a18`，全文无 `correction-detail`）与 TASKS.md Codex 独占清单内，当前 open PR（#35、#271）都不改这个文件，`ui-integrity-baseline.js` 里没有它的锚点；`tests/unit` 无相反断言（`merchant-correction-records-bl184.test.js:52-69,106-125` 只要求 PENDING 时 `canSupplement` 为 false、版本号变化算已确认，回读成功用例不变）；接口已有调用，读过 `ApiCommunityTrustController.java:130-142` 与 `CommunityTrustServiceImpl#supplementContribution`（:438-497，按本人归属 `selectContributionForOwnerForUpdate`，只有 `canSupplement` 且 NEEDS_EVIDENCE/REJECTED 可补，CAS `supplementContributionCas` 把状态改为 PENDING、`can_supplement=false`、`version+1`；再用新 requestId 按旧 `expectedVersion` 提交会 409「当前贡献状态不可补证」/「贡献状态已变化，请刷新」）；不新增接口与字段）
| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 216 | 补证成功后回读失败仍显示「补充说明」 | `subpackageMember/correction-detail/index.js:123-133` `_afterSupplement`：服务端已返回成功（`accepted`）但回读 `GET /contributions/{id}` 失败时走 `:129` 只把 sheet 置 `ok`，`item` 仍是补证前的投影（`canSupplement: true`、旧 `version`），wxml `:37` 的「补充说明」按钮仍在。`closeSheet`（:134-140）会静默回读，但回读再失败（`load(true)` 静默不提示）时按钮一直可点，再提交会换新 requestId、按旧 `expectedVersion` 提交，后端 409「贡献状态已变化，请刷新」，像是补证失败了。改为：`:129` 无回读结果的分支同时写回 `'item.canSupplement': false`（后端合同：补证成功即 PENDING、`can_supplement=false`），照 BL-215 对 `report-detail/index.js:151` 的写法，写在原一行内、净行数不变。不改 wxml、样式、文案和其他分支（回读成功仍以服务端为准）。补单测：补证成功 + 回读失败 → `item.canSupplement === false` 且 `sheet === 'ok'`；关闭后发起一次回读；BL-184 现有断言一条不改 | `subpackageMember/correction-detail/index.js`、对应单测 |

## 第三十五批（217）：主监督补充（2026-10-18；对本线新建页面复查后的真实缺陷：主办控制台两路状态读取都断网时被当成「读到了」，把回读失败报成「状态与预期不一致」，下拉刷新还会把已显示的活动名和状态冲成「未命名官方活动 / 状态待确认」。已过补卡前置检查：`pages/activity/official-organizer/index.js` 不在 Codex `tasks.json` files/owned_files（控制分支全文无 `official-organizer`）与 TASKS.md Codex 独占清单内，当前 open PR（#35 只加 `components/cy/official-coorganizers/*`，#271、#272）都不改这个文件，`ui-integrity-baseline.js` 里没有它的锚点；`tests/unit` 无相反断言（`official-organizer-console.test.js:78-92` 的 `finishLoad` 只让 `my-published` 走 success，`:299-311` 断言的是 publish-check 断网 → 「结果未确认」，与本卡同向；`get-auth-matrix-contract.test.js:46-48` 只登记 URL）；接口已有调用，读了 `ApiOfficialEventController.java:90-95`（`GET /events/{id}`，不存在返回 error）与 `:376-385`（`GET /my-published`，非白名单返回 error「无官方发布权限」），本卡不改接口、不改业务拒绝（非 200）分支）
| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 217 | 主办控制台状态读取断网被当成已读到 | `pages/activity/official-organizer/index.js:172-175` `_loadStateFromMine` 的 `fail`（断网/超时）直接 `_applyState(null)`；`_applyState`（:179-193）随即 `phase: 'ready'`、用空状态覆盖 `view`（标题「未命名官方活动」、「状态待确认」、按钮全灰），并 `_readBackDone(true)`（:192）把它当成「读到最新状态」。后果：①发布/暂停/恢复受理后回读时两路都断网，`:325` 判 `mismatch`，页内回执写「回读到的活动状态与预期不一致……仍不一致请联系平台处理」（:339）或「当前显示的是刚读到的平台记录」（:337），实际什么都没读到，应走 `unread` 文案；②已有快照时下拉刷新遇断网，已显示的活动名和状态被冲掉。同文件 publish-check 断网分支（:127-136）已有正确写法：`_readBackDone(null)`；有快照（进入 `load` 时 `phase === 'ready'`）就只收 `refreshing` 并轻提示「网络没连上，显示上次读到的状态」，无快照落 `phase: 'error'`、`loadErrKind: 'network'`。只改 `_loadStateFromMine` 的 `fail` 分支照此处理（需要的话把 `hasSnapshot` 经参数从 `load` → `_loadEventState` → `_loadStateFromMine` 传下去）；`success` 分支（含非 200 / 找不到行时 `_applyState(row || null)`）、`_loadEventState`、wxml、样式、文案都不改。补单测：①有快照时 publish-check 成功、详情 fail、my-published fail → `view.title` 保持原值、`phase === 'ready'`、`refreshing === false`；②受理后回读同样两路断网 → `result.title` 为「…已受理，但状态未刷新」且 `sub` 含「回读最新状态失败」；③首读两路断网 → `phase === 'error'`。`official-organizer-console.test.js` 现有断言一条不改 | `pages/activity/official-organizer/index.js`、对应单测 |

## 第三十六批（218–219）：主监督补充（2026-10-18；对本线新建组件复查后的真实缺陷：举报/纠错半屏读原因（taxonomy）的回包没有过期保护，关掉重开后旧回包把正在填的表单冲回「选原因」或「读取失败」。同线的 `pages/play/merchant/components/safety-rating-sheet/index.js:28,44,54` 已用 `_epoch` 丢弃过期回包，两张卡照它写。已过补卡前置检查：`components/cy/report-sheet/index.js`、`subpackageMember/components/cy/merchant-correction-sheet/index.js` 不在 Codex `tasks.json` files/owned_files（控制分支 `88130a18` 全文无这两个路径）与 TASKS.md Codex 独占清单内，OWNERSHIP 只登记了 `pages/team/report-sheet`（Claude）；当前 open PR（#35 只加 `components/cy/official-coorganizers/*`）与各 `claude/bl-*` 分支都不改这两个文件，`ui-integrity-baseline.js` 里没有它们的锚点；`tests/unit` 无相反断言（`report-sheet-bl32/bl51`、`merchant-correction-bl67`、`bl213-correction-sheet-nav-guard` 都是 reset 后立即回包，加序号后仍成立）；接口已有调用，读过 `ApiCommunityTrustController.java:49-52` `GET /reports/taxonomy`、`:115-118` `GET /contributions/taxonomy`（只读、无归属、直接返回字典）。不新增接口、不改后端、不改 wxml/样式/文案；改动里不出现 `wx.navigateBack`）
| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 218 | 举报半屏关掉重开后旧原因回包冲掉正在填的表单 | `components/cy/report-sheet/index.js:40-61` `loadTaxonomy` 的 `success`/`fail`/`successStatusAbnormal` 不核对是不是本次打开发出的请求。弱网下：打开半屏 → 原因还在读时点「取消」（`close` :109 在非 busy 时放行）→ 再打开（`show` 观察者 :29 → `reset` → 第二次 `loadTaxonomy`）→ 第二次先回来，用户选了原因、正在写说明（`stage: 'form'`）→ 第一次的回包后到：成功则 `:56` 把 `stage` 冲回 `'pick'`，超时/断网则 `:58-59` 冲成 `'loadfail'`「网络不太稳定，举报原因没有读取成功」，写到一半的说明从界面上消失；若已点提交（`stage: 'busy'`、`_busy` 为真），旧回包也会把 `stage` 改成 `pick`/`loadfail`，等提交回包才被 `ok`/`unknown` 覆盖。另：`loadfail` 态点「重试」（wxml :3 `bind:primary="loadTaxonomy"`）与旧请求并发时同理。改为照 `safety-rating-sheet/index.js:28,36,44` 的写法：`loadTaxonomy` 每次发请求前 `this._taxSeq = (this._taxSeq \|\| 0) + 1` 并记下本次序号，三个回调开头不是最新序号就直接 `return`；`close()` 在放行关闭时也把 `_taxSeq` 加一，关掉后的回包一律丢弃。`reset`、`submit`、`_requestId` 规则、文案和各阶段不变；不改 wxml、样式。补单测：reset → 不回包 → close → reset → 第二次回包成功并选原因进 `form` → 第一次回包 success 或 fail，`stage` 仍为 `'form'`、`reasonLabel` 不变；`loadfail` 重试后旧回包不覆盖新结果；`report-sheet-bl32/bl51`、`team-report-sheet-handlers` 现有断言一条不改 | `components/cy/report-sheet/index.js`、对应单测 |
| 219 | 纠错半屏关掉重开后旧类型回包冲掉正在填的表单 | 与 BL-218 同一缺陷：`subpackageMember/components/cy/merchant-correction-sheet/index.js:37-57` `loadTaxonomy` 的三个回调不核对请求序号，关掉重开（`close` :107、`show` 观察者 :26 → `reset` :29-36）或 `loadfail` 态点「重试」后，先发后到的 `GET /contributions/taxonomy` 回包会把 `stage: 'form'`/`'busy'` 冲回 `'pick'`（:52）或 `'loadfail'`（:54-55）。改法同 BL-218：`loadTaxonomy` 发请求前递增 `this._taxSeq` 并记下序号，回调开头不是最新就 `return`；`close()` 放行时也递增。`reset` 里的 `_busy`/`_navigating` 兜底、`openMine`（BL-213）、`submit`、幂等键规则、文案都不变；不改 wxml、样式。补单测：同 BL-218 的关掉重开场景，旧回包 success/fail 都不改 `stage: 'form'` 和 `issueLabel`；`merchant-correction-bl67`、`merchant-correction-records-bl184`、`bl213-correction-sheet-nav-guard` 现有断言一条不改 | `subpackageMember/components/cy/merchant-correction-sheet/index.js`、对应单测 |

## 第三十七批（220）：主监督补充（2026-10-18；对本线新建组件复查后的真实缺陷：安全感受评价半屏提交中可以关掉，服务端已受理但用户看不到回执，重开后再交被拒「本自然月已评价」。同线的举报/纠错半屏 `close` 都有 `if (!this._busy)` 保护，本卡照它写。已过补卡前置检查：`pages/play/merchant/components/safety-rating-sheet/index.js` 不在 Codex `tasks.json` files/owned_files（控制分支 `88130a18`，全文无 `pages/play/merchant`）与 TASKS.md Codex 独占清单内；`tests/unit/safety-rating-sheet.test.js` 无断言要求提交中可关；接口 `POST /api/community-trust/safety/ratings` 已有调用，已读 `ApiCommunityTrustController.java:155-167` 与 `CommunityTrustServiceImpl.submitSafetyRating`：同幂等键重放回原记录，换键且本月已有记录则抛 `conflict("本自然月已评价该商家，请勿重复提交")`）
| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 220 | 安全感受评价提交中关掉半屏，已受理的评价看不到回执 | `pages/play/merchant/components/safety-rating-sheet/index.js:130-133` `onClose` 无条件 `_epoch += 1` 并 `triggerEvent('close')`，没有提交中保护（wxml `:3` 的 `cy-sheet bind:close="onClose"`，点遮罩/关闭即触发）。弱网下点「提交评价」（`submit` :77-107，先 `public-home` 换商家 ID 再 `POST /safety/ratings`）后关掉半屏：`:99`/`:82` 因 epoch 变了丢弃回包，服务端其实已写入评价，用户既没看到回执也没看到失败；重开（`open` :27-33 清 `_requestId`）再交会换新幂等键，后端按本月已评价返回冲突，用户只看到「本自然月已评价该商家」。改法照 `components/cy/report-sheet/index.js:112`、`subpackageMember/components/cy/merchant-correction-sheet/index.js:110`：`onClose` 开头 `if (this.data.submitting) return`（只挡 `stage === 'form'` 的提交中；`done` 态「知道了」照常关）。 | 只改 `safety-rating-sheet/index.js` 的 `onClose` 一处；不改 wxml/样式/文案，不改父页 `pages/play/merchant/index.*`；单测加在 `tests/unit/safety-rating-sheet.test.js`：提交请求挂起时调 `onClose` 不触发 `close`、回包后正常显示回执；`done` 态 `onClose` 仍触发 `close`。 |

## 第三十八批（221）：主监督补充（2026-10-19；对本线 CL3 改动复查后的真实缺陷：昵称推荐旧一轮作废时把新一轮的「推荐中」标记清掉，在途保护失效。已过补卡前置检查：`pages/gerenziliao/gerenziliao.js` 不在 Codex `tasks.json` files/owned_files（控制分支 `88130a18`，全文只在一条跳过用例名里出现 gerenziliao）与 TASKS.md Codex 独占清单内，OWNERSHIP.md:377 登记为 Claude CL3；当前 open 的 `claude/*` PR（#35）不改这个文件；`ui-integrity-baseline.js` 对它只有 `#函数名` 键、没有行号锚点；`tests/unit` 无相反断言（`profile-nickname-check.test.js:179-187` 只要求改输入后旧推荐作废、不覆盖、不续取，加轮次号后仍成立）；接口已有调用，读过 `ApiUmsMemberController.java:112-128`（`/api/user/nickname/check`、`/nickname/suggest` 只读、可匿名、按 `optionalMemberId` 排除本人，候选耗尽回 400 + `errorCode`）。不新增接口、不改后端、不改 wxml/样式/文案；改动里不出现 `wx.navigateBack`）
| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 221 | 昵称推荐旧一轮作废时清掉新一轮的「推荐中」 | `pages/gerenziliao/gerenziliao.js:269` `loadNicknameSuggestions` 以 `nicknameSuggesting` 做在途保护（:270），作废判断只看 `_nicknameSeq`（:283）。弱网下：输入撞名 A → 自动拉推荐（第一轮请求在途）→ 改成同样撞名的 B：`scheduleNicknameCheck`（:211）先把 `nicknameSuggesting` 清掉，B 查重回 `taken` 后（:247）开第二轮、`nicknameSuggesting: true`（:316）→ 第一轮回包后到，`:303` 判作废走 `finish()`，`:286` 无条件 `setData({ nicknameSuggesting: false })`，把第二轮的在途标记清掉：标题从「正在找可用的昵称…」变成「可以用这些昵称」却没有候选（wxml :176），「换一批」恢复可点（wxml :182-184）；再点会并发第三轮，两轮共用 `_suggestCursor`（第二轮刚置 null），发出重复的 `baseName/cursor` 请求，先后 `finish` 互相覆盖候选列表。改为：每次真正发起一轮时记轮次 `const run = (this._suggestRun = (this._suggestRun \|\| 0) + 1)`，`:286` 作废分支只在 `run === that._suggestRun`（没有更新的一轮接手）时才清 `nicknameSuggesting`；点选推荐（:324）、`retryNicknameCheck`（:254）作废当前轮时没有新一轮，仍会清掉，行为不变。`_nicknameSeq` 规则、`collected` 去重、批量与 cursor 规则、文案都不变；不改 wxml、样式。补单测（加在 `profile-nickname-check.test.js` 同风格）：撞名 A 推荐在途 → 改成 B 并查重 taken → 第二轮在途 → 第一轮回包成功/失败，`nicknameSuggesting` 仍为 true、`nicknameSuggestions` 仍为空；第二轮回包后正常落候选并清标记；现有断言一条不改 | `pages/gerenziliao/gerenziliao.js`、`tests/unit/profile-nickname-check.test.js` |

## 第三十九批（222–223）：主监督补充（2026-10-19；对本线新建页面复查后的真实缺陷：提交前已发出的刷新回包在提交成功之后才到，把页面冲回提交前的旧状态，与 BL-215/216 同页、同类于 BL-218/219 的过期回包。已过补卡前置检查：`subpackageMember/report-detail/index.js`、`subpackageMember/correction-detail/index.js` 不在 Codex `tasks.json` files/owned_files 与 TASKS.md Codex 独占清单内，当前 open PR（#35）不改这两个文件，`ui-integrity-baseline.js` 里没有这两个文件的锚点；`tests/unit` 无相反断言（`bl215-report-appeal-readback`、`bl216-correction-supplement-readback`、`report-transparency-n1`、`merchant-correction-records-bl184` 均不涉及 `_seq`，也没有把这类保护列为退役）；接口均已有调用，已读后端：`ApiCommunityTrustController.java:66-75` `GET /reports/{id}` 与 `:145-154` `GET /contributions/{id}` 只按登录 principal 读本人记录；`:88-99` `/reports/appeal` 只允许 RESOLVED/CLOSED，否则 conflict；`:131-143` `/contributions/supplement` 只允许 NEEDS_EVIDENCE/REJECTED 且校验版本，否则 409「当前贡献状态不可补证」/「贡献状态已变化，请刷新」。不新增接口、不改后端、不改 wxml/样式/文案）
| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 222 | 申诉成功后旧刷新回包把「提交申诉」又放出来 | `subpackageMember/report-detail/index.js:93-128` `submitAppeal` 发申诉前没有作废在途的 `load`（序号 `this._seq` 只在 `load` :40 里递增，`_settle` :66 靠它丢旧包）。弱网下：下拉刷新（:34）或关掉失败/未知半屏触发的回读（:162）还没回来，就打开表单提交申诉；申诉成功、`_afterAppeal`（:140-156）已把页面切到「申诉中」并收起「提交申诉」，随后那次提交前发出的 `load` 才回来，`seq` 仍是最新，`_settle`（:75）用申诉前的快照覆盖 `report`/`nodes`、并把 `_beforeAppeals`（:74）改回旧计数：阶段退回「已处理」、「提交申诉」重新可点，再点被服务端以 conflict 拒绝。改为：在 `submitAppeal` 置 `this._appealing = true`（:105）的同一处把 `this._seq = (this._seq \|\| 0) + 1`，作废之前所有在途 `load`（`_settle` 在序号判断前已 `stopPullDownRefresh`，下拉转圈照常收起）；其余流程、文案、幂等键规则不变，不改 wxml。补单测：先发一次 `load` 不回包 → 提交申诉成功并回读 → 再让旧 `load` 回包（`appealable: true` 的旧阶段），页面仍是回读后的阶段、`report.appealable` 为 false | `subpackageMember/report-detail/index.js` |
| 223 | 补证成功后旧刷新回包把「补充说明」又放出来 | 与 BL-222 同一缺陷：`subpackageMember/correction-detail/index.js:72-112` `submitSupplement` 发请求前不作废在途 `load`（序号只在 :37 递增，`_settle` :52 判断）。下拉刷新（:31）或关半屏回读（:139）在补证之前发出、在 `_afterSupplement`（:123-133）之后才回来时，`_settle`（:60）用旧 `item`（旧 `version`、`canSupplement: true`）覆盖，:129 注释要防的「旧版本号再提交被 409」照样发生。改为：在置 `this._submitting = true`（:86）的同一处 `this._seq = (this._seq \|\| 0) + 1`；其余不变，不改 wxml。补单测：同 BL-222，旧 `load` 后到不覆盖 `item`，`item.canSupplement` 为 false、`version` 为回读后的值 | `subpackageMember/correction-detail/index.js` |

## 第四十批（224）：主监督补充（2026-10-19；对本线 CL3 勋章页复查后的真实缺陷：颁发用的成员名单整页只读一次，下拉刷新/重试都不重读，新入团成员选不到、已退团成员还能选。已过补卡前置检查：`pages/club/badges/index.js` 不在 Codex `tasks.json` files/owned_files（控制分支全文无 `club/badges`）与 TASKS.md Codex 独占清单内，OWNERSHIP.md:366 登记为 Claude CL3；当前无 open 的 `claude/bl-*` PR，#35 不改这个文件；`ui-integrity-baseline.js` 里没有它的锚点；`tests/unit` 无相反断言（`club-badge-manage.test.js:123` 只要求非主理人/未打开颁发时不请求 `/api/club/members`，`:380-383` 只要求失败后重试会再发一次）；接口均已有调用，已读后端：`ApiClubController.java:624-660` `/api/club/members` 要登录、仅主理人或本团成员可读、只回 `status=1` 的在团成员；`:1183-1211` `/api/club/badge/grant` 校验主理人归属、勋章属本团，对象不在团时报「对方不是本俱乐部成员」。本轮其余新建页（核销/触达记录、我的举报/纠错、收藏据点、系统消息举报进度、官方邀约）复查后已有过期回包与在途保护，不建卡）
| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 224 | 勋章颁发成员名单下拉刷新后仍是旧名单 | `pages/club/badges/index.js:325` `openGrant` 只在 `membersState` 不是 `ready`/`loading` 时才 `loadMembers`；`loadAll`（:128-142，下拉 :118、重试 :125、建章后回读都走它）只刷新身份与勋章列表，从不作废成员名单。主理人在本页停留期间：有人新入团 → 下拉刷新 → 点「颁发」，选人列表里仍没有新成员，只能退出页面重进；有人已退团 → 列表里仍有他，选中确认后服务端报「对方不是本俱乐部成员」。另外 `loadMembers`（:333）没有请求序号，重试与旧请求并发时先发后到的回包会覆盖新名单。改为：`loadMembers` 发请求前递增 `this._membersSeq` 并在 `success`/`fail`/`successStatusAbnormal` 开头比对，不是最新就 `return`；`loadAll` 里在选人半屏未打开且不在颁发中（`!this.data.memberPickerVisible && !this.data.granting`）时递增 `this._membersSeq` 并 `setData({ membersState: 'idle', members: [] })`，下次 `openGrant` 自然重读；`switchAccount` 已清名单，不动。不改 wxml、样式和文案，不新增接口。补单测：成员名单 ready 后调用 `onPullDownRefresh`，再 `openGrant` 会再发一次 `/api/club/members` 且列表换成新回包；选人半屏打开时下拉不清名单；两次 `loadMembers` 并发时先发的回包后到不覆盖 | `pages/club/badges/index.js` |

## 第四十一批（225–226）：主监督补充（2026-10-19；对本线 CL4 主办控制台入口复查后的真实缺陷：两个「主办控制台」入口没有跳转中保护，连点叠两层控制台，返回要按两次，同 BL-211/213 类。已过补卡前置检查：`pages/activity/official-detail/index.js`、`pages/activity/official-mine/index.js` 不在 Codex `tasks.json` files/owned_files（控制分支 `88130a18`，全文无 `official`）与 TASKS.md Codex 独占清单内，OWNERSHIP.md:326 把 `official-mine/index.js` 登记为 Claude CL4、`official-detail` 只登记了 json/wxss（无 Codex 归属）；当前无 open 的 `claude/bl-*` PR，#35 不改这两个文件；`ui-integrity-baseline.js` 里没有这两个文件的锚点；`tests/unit` 无相反断言（`official-organizer-console.test.js:338-356` 只断言 `_probeOrganizer` 的 `organizerEntry` 与 wxml 绑定 `catchtap="openOrganizer"`，不连点）；目标页接口已有调用，已读 `ApiOfficialEventController.java:271-276` `GET /v2/events/{id}/publish-check` 要 `canPublish` 且 `canReadControlledEvent`，否则 error「无权查看该活动发布检查」。注意 Codex #69（→ master）也改 `official-detail/index.js`，同步 master 时一并解冲突。不新增接口、不改后端、不改 wxml/样式/文案；改动里不出现 `wx.navigateBack`）
| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 225 | 活动详情「打开主办控制台」连点叠两层控制台 | `pages/activity/official-detail/index.js:275-278` `openOrganizer` 只判 `organizerEntry` 就直接 `wx.navigateTo` 主办控制台，没有跳转中保护（wxml `:167` `bindtap="openOrganizer"`）。手快连点会叠两层 `official-organizer`，各自再读一遍状态，返回要按两次。照 BL-211（`subpackageMember/report-mine/index.js:83-84`）的写法：实例标记 `this._navigating`，开头判断、跳转前置真、`navigateTo` 的 `complete` 里清掉；`onShow`（:157）里也清一次兜底（保留原有 `merchantPageShow()`）。`url` 保持字面量 `'/pages/activity/official-organizer/index?id=' + this.data.id`。补单测：桩 `wx.navigateTo` 不立即回调 `complete` 时连调两次 `openOrganizer` 只跳一次；`complete` 后再调可再跳；`organizerEntry` 为 false 时不跳 | `pages/activity/official-detail/index.js` |
| 226 | 我发布的活动「主办控制台」连点叠两层控制台 | 与 BL-225 同一缺陷：`pages/activity/official-mine/index.js:172-174` `openOrganizer` 直接 `wx.navigateTo`（wxml `:17` `catchtap="openOrganizer"`），连点叠两层控制台。改法同 BL-225：实例标记 `this._navigating`，开头判断、跳转前置真、`complete` 里清掉，`onShow`（:52）里兜底清一次（保留原有 `syncViewerTheme()` 与 `_loaded` 回读）；缺 `dataset.id` 时不跳。只改 `openOrganizer`，既有 `openEvent`（:167）属基线代码不动。补单测：连调两次只跳一次、`complete` 后可再跳、缺 id 不跳 | `pages/activity/official-mine/index.js` |

## 第四十二批（227–229）：主监督补充（2026-10-19；对本线新增入口复查后的真实缺陷：本线在既有页面上新加的「俱乐部勋章」「核销记录」「开店前置项」「触达记录」入口没有跳转中保护，连点叠两层同一页，返回要按两次，同 BL-211/213/225/226 类。已过补卡前置检查：`pages/club/detail/index.js`、`pages/merchant/index/index.js`、`pages/merchant/customer/index.js` 不在 Codex `tasks.json` files/owned_files（控制分支 `88130a18`）与 TASKS.md Codex 独占清单内，OWNERSHIP.md:374 把 `club/detail/index.js` 登记为 Claude CL3，两个商家页无 Codex 归属；当前 open 的 `claude/bl-*` PR（#283/#284）与 #35 不改这三个文件；`tests/unit` 无把这些入口列为退役/禁止的断言，只有结构断言 `merchant-verify-records.test.js:62-63`、`merchant-crm-campaigns.test.js:86-88`、`club-badge-manage.test.js:406-407`（本线自己的接线断言，改法里同步收紧即可，不是产品决定）；目标页接口已有调用，已读控制器：`ApiClubController.java:1174-1180` `POST /api/club/badge/list` 要登录且俱乐部存在、`:1184-1195` `badge/grant` 校验主理人归属；`ApiMerchantController.java:1661-1669` `POST /api/merchant/verification-records` 要 `VERIFY_RECORD_READ` 且按 `ownerMemberId` 取数；`ApiMerchantCrmController.java:119-125` `GET /api/merchant/crm/campaigns` 要登录、服务层按 principal 取本店。不新增接口、不改后端、不改 wxml/样式/文案；改动里不出现 `wx.navigateBack`）
| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 227 | 俱乐部详情「俱乐部勋章」连点叠两层勋章页 | `pages/club/detail/index.js:2310-2315` `goClubBadges`（本线 CL3 新增；wxml `:301` 概览入口与 `:847` 设置行都 `catchtap="goClubBadges"`）判完身份就直接 `wx.navigateTo('/pages/club/badges/index?clubId=')`，没有跳转中保护。连点会叠两层勋章页，各自再读 `club/detail`、`badge/list`、成员名单，返回要按两次。照 BL-225 写法：实例标记 `this._navigating`，开头判断、跳转前置真、`navigateTo` 的 `complete` 里清掉；`onShow`（:697）里兜底清一次（保留原有 `refreshCoopPublisherAccess()` 等）。只改 `goClubBadges`，同文件其余基线跳转不动。补单测：桩 `wx.navigateTo` 不立即回调 `complete` 时连调两次只跳一次；`complete` 或 `onShow` 后可再跳；非成员/缺 clubId 时不跳也不上锁 | `pages/club/detail/index.js` |
| 228 | 商家工作台「核销记录」「开店前置项」连点叠两层 | `pages/merchant/index/index.js:313-316` `goVerifyRecords`（BL-76 新增）与 `:788-794` `goPrerequisite`（本线新增，跳 `item.path`）都直接 `wx.navigateTo`，连点叠两层核销记录页/前置项页。改法同 BL-225：共用实例标记 `this._navigating`，两个方法开头判断、跳转前置真、`complete` 里清掉；`onShow`（:600）兜底清一次（保留既有身份链路，不改其顺序）。同步收紧 `tests/unit/merchant-verify-records.test.js:63` 的结构正则以匹配新写法（url 仍为字面量 `'/pages/merchant/verify-records/index'`）。补单测：两个入口各自连调两次只跳一次、`complete` 后可再跳、无权限/缺 path 不跳也不上锁 | `pages/merchant/index/index.js` |
| 229 | 客户页「触达记录」连点叠两层 | `pages/merchant/customer/index.js:898-902` `goCampaigns`（BL-100 新增，wxml `:113` `bindtap="goCampaigns"`）先 `setData({ toolsOpen: false })` 再直接 `wx.navigateTo('/pages/merchant/crm-campaigns/index')`，工具半屏收起前的第二下仍会再跳一次，叠两层触达记录页。改法同 BL-225（`this._navigating` + `complete` 清掉）；`onShow`（:128）兜底清一次时**写在已有行上**（如 `merchantTheme.merchantPageShow(); this._navigating = false;`），因为 `ui-integrity-baseline.js:1210` 有 `customer/index.js:491` 行号锚点，锚点上方净增减行数必须为 0。同步收紧 `merchant-crm-campaigns.test.js:88` 的结构正则。补单测：连调两次只跳一次、`complete`/`onShow` 后可再跳、`canCampaigns` 为 false 不跳也不上锁；提交前跑 `node tests/lint/ui-integrity.js` 与集成分支去行号对比 | `pages/merchant/customer/index.js` |

## 第四十三批（230）：主监督补充（2026-10-19；对本线新增跳转复查后的真实缺陷：漫游护照「官方活动」大卡（BL-41 新增分支）连点叠两层官方活动详情，同 BL-225–229 类。本轮复查的其余新建页/组件（核销记录、触达记录、主办控制台、协办收件箱、勋章页、收藏据点、我的举报、AI 对话导出、报名趋势、履约信用、券统计）已有过期回包、在途与跳转中保护，不建卡。已过补卡前置检查：`pages/roam/index.js` 不在 Codex `tasks.json` files/owned_files（控制分支 `88130a18`，files/owned_files 全部条目无 `roam`）与 TASKS.md Codex 独占清单内；OWNERSHIP.md:332 记 PR20 曾占用该文件，但本线已多次改它（#38/#83/#113、BL-41），本卡只改一行分支加一行兜底；当前无 open 的 `claude/bl-*` PR，#35 不改这个文件；`tests/unit` 无把这一跳转列为退役/禁止的断言，只有本线自己的接线断言 `roam-official-active-bl41.test.js:36`（逐字匹配旧写法，改法里同步收紧即可）；接口已有调用，已读 `ApiOfficialEventController.java:129-132` `GET /api/official/active-for-roam`（只读、可匿名、按城市与 uid 取可点亮活动）、`:90-95` `GET /api/official/events/{id}`（只读，不存在回「活动不存在」）。不新增接口、不改后端、不改 wxml/样式/文案；改动里不出现 `wx.navigateBack`）
| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 230 | 漫游护照「官方活动」大卡连点叠两层活动详情 | `pages/roam/index.js:777` `onPassportPlayTap` 的 `key === 'event' && this._activeRoamEventId` 分支（BL-41 新增；wxml `:180` `bindtap="onPassportPlayTap"`）直接 `wx.navigateTo('/pages/activity/official-detail/index?id=')`，没有跳转中保护。手快连点会叠两层官方活动详情，各自再读详情与主办身份，返回要按两次。照 BL-225 写法：实例标记 `this._navigating`，该分支开头判断、跳转前置真、`navigateTo` 的 `complete` 里清掉；`onShow`（:1001）里兜底清一次。**行号锚点**：`ui-integrity-baseline.js` 在本文件 `:1373` 起有锚点，两处改动都在其上方，净增减行数必须为 0——:777 那行改写为同一行内的 `if (...) { if (this._navigating) return; this._navigating = true; wx.navigateTo({ url: '/pages/activity/official-detail/index?id=' + this._activeRoamEventId, complete: () => { this._navigating = false; } }); }`，`onShow` 的兜底写在已有行上（如 `this._hidden = false; this._navigating = false;`）。url 保持字面量前缀；`openRoamTasks`/`goDiscover`/`openOfficialRoutes` 等基线跳转不动。同步收紧 `roam-official-active-bl41.test.js:36` 的结构正则。补单测：桩 `wx.navigateTo` 不立即回调 `complete` 时连调两次 `onPassportPlayTap({ currentTarget: { dataset: { key: 'event' } } })` 只跳一次；`complete` 或 `onShow` 后可再跳；`_activeRoamEventId` 为空时走 `openRoamTasks` 且不上锁。提交前跑 `node tests/lint/ui-integrity.js` 与集成分支去行号对比 | `pages/roam/index.js`、`tests/unit/roam-official-active-bl41.test.js` |
