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
