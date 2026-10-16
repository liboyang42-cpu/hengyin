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
