# 俱乐部管理页 — 设计文档

日期:2026-06-23
分支:feature/app-backend-support

## 1. 背景与目标

俱乐部(club)目前在系统里已是一个独立角色,有 `club` 表、`club_member` 表、申请→后台审核流程,以及小程序端的申请页 `pages/club/apply/index`。但**俱乐部账号审核通过后没有自己的管理页** —— 商家有 `pages/merchant/index/index`,玩家有 `pages/index/index`,俱乐部缺一个并列的管理首页。

本设计为俱乐部新增一个独立管理首页,让俱乐部主理人能维护资料、管理成员、管理自己的主题/活动、发券核销、查看财务与数据。

## 2. 身份模型决策(已确认)

**三者完全分开、互斥。** 账号的 `ums_member.role` 只能是 `player` / `club` / `merchant` 之一:

- 俱乐部账号登录后**只看俱乐部管理页**,看不到玩家那一套,也看不到商家那一套。
- 想以普通玩家身份逛/报名别人的活动,需另开账号。
- **不新增多角色支持、不加表、不动现有 RBAC 架构。**

取舍:简单、改动最小、与现有单 `role` 字段模型完全吻合;代价是俱乐部主理人无法用同一账号以玩家身份参与社区(用户已接受)。

## 3. 角色能力(权限表为准)

依据 `user_permission`(migration_rbac_role.sql),**俱乐部是权限最全的角色** —— 既能像商家发券,又能像玩家建主题/活动,外加独有的成员管理:

| 角色 | 主题 | 活动 | 优惠券 | 建主题 | 建活动 | 发券 |
|---|---|---|---|---|---|---|
| player | 1 | 2 | 1 | ✓ | ✓ | ✓ |
| **club** | **5** | **3** | **3** | ✓ | ✓ | ✓ |
| merchant | 0 | 0 | 3 | ✗ | ✗ | ✓ |

因此俱乐部管理页的功能是 **商家功能(发券/核销/财务)+ 内容创建(主题/活动)+ 成员管理** 的并集,而非"商家页删功能"。

## 4. 路由与入口

- 首页 `pages/index/index.js`(当前 line 6-8 的 `isMerchantView()` 只判断商家)新增并列分支:
  - `role == 'club'` 且俱乐部审核通过 → `wx.redirectTo('/pages/club/index/index')`
  - 逻辑照搬现有商家分支(含 `debug_user_view` 调试覆盖处理)。
- 新建页面 `pages/club/index/index`,与 `pages/merchant/index/index` 平级,**以商家首页为模板克隆**,保留同一套设计语言(`topBar`、信息行、数据卡、`cy-section-header`、九宫格、列表卡、wxss)。
- `app.json` 注册新页面。`app.js` 已有 `role` 存取,无需改动。

## 5. UI 与功能板块

俱乐部首页 = 克隆商家首页样式 + 调整数据源/入口。逐块对照:

| 商家首页 | → 俱乐部首页 | 跳转/接口 | 复用度 |
|---|---|---|---|
| 信息行(logo/名/位置/积分) | 信息行(俱乐部 logo/名/地址) | `club` 表 | 样式复用,换数据 |
| 数据卡:收入/曝光/待处理单 | **统计卡:成员数 / 主题数 / 活动数 / 曝光量** | 新增 `/api/club/dashboard` | 新增,见 §6.4 |
| 扫码验券 | 扫码验券 | `/api/coupon/verification` | ⚠️ 需确认接受 club(见 §7) |
| 优惠券发布 | 优惠券发布 | `/pages/member/coupon/coupon`(member 通用页) | ✅ 基本直接复用 |
| 财务结算 | 财务结算 | `/pages/role/center/index`(member 通用) | ✅ 复用 |
| 品牌中心 | **俱乐部资料维护** | 新增编辑页 + `/api/club/update-mine` | 新增,见 §6.1 |
| —(商家无) | **成员管理** | `/api/club/members`+`/api/club/remove-member` | ✅ 后端已有 |
| —(商家无) | **主题/活动管理** | topic/activity by memberId | ✅ 复用,见 §6.3 |
| 我的参与 + 招募广场 | 我的参与 + 招募广场 | ⚠️ 需 club 版接口(见 §7) | 后端新增 |
| 实时动态 | 实时动态(可选,后做) | — | 可后置 |

## 6. 功能板块明细与后端

### 6.1 资料维护
- 编辑俱乐部名称/logo/封面/简介/类型/地址等(`club` 表字段)。
- **新增 `/api/club/update-mine`:** 现有 `/api/club/apply` 仅在被驳回(status==2)时才更新,审核通过后改不了。新接口校验 `当前 memberId == club.memberId`,只允许更新展示类字段,不允许改 status/member_count。
- 小程序端可复用 `pages/club/apply/index` 表单 UI,或新建 `pages/club/edit/index`(实现时定)。

### 6.2 成员管理
- **查看 + 移除**(成员即时加入,`/api/club/join` 直接 insert 无审核,**没有"审批加入申请"**)。
- 后端已有 `/api/club/members`、`/api/club/remove-member`(已校验权限、禁移除创建者)。✅ 无需新增。
- 新增小程序端 `pages/club/members/index`。

### 6.3 主题/活动管理
- 俱乐部主题/活动 = **该俱乐部账号(memberId)自己创建的** `CmsTopic`/`CmsActivity`(均挂 `memberId`,无 `club_id`,无需关联表)。
- 复用现有"我的主题/我的活动"列表(`ApiTopicController`/`ApiActivityController`)按 memberId 过滤,以及现有创建流。
- 受 `user_permission` 限:club 最多 5 主题 / 3 活动。

### 6.4 优惠券(发布 + 核销)
- 发布:`/pages/member/coupon/coupon` 是 member 通用页,基本可直接复用。
- 核销:`goScanQR` → `/api/coupon/verification`。**实现时确认该接口按券归属(memberId)校验、接受 club 账号**;若硬编码 merchant 校验则放开。
- 受 `user_permission` 限:club 最多 3 张券。

### 6.5 财务结算
- `goFinance` → `/pages/role/center/index`(角色中心,member 通用,按 `UmsMemberBalanceDetail` 的 memberId 查),复用。俱乐部跑付费主题/活动产生的收入走同一余额体系。

### 6.6 我的参与 / 招募广场
- 商家版走 `/api/topic/list-to-merchant`、`/api/registration/merchant/list`(商家专属)。
- **需做 club 版接口**(如 `/api/topic/list-to-club`、`/api/registration/club/list`),口径=俱乐部可参与/已参与的主题与报名记录;或将现有接口泛化到按 role 区分。实现时二选一。

### 6.7 统计卡(带曝光量)
- 新增 `/api/club/dashboard`,参照 `ApiMerchantController#getDashboard`,返回:
  - 成员数:`clubMemberService.countByClub`
  - 主题数/活动数:`CmsTopic`/`CmsActivity` by memberId 计数
  - **曝光量**:复用 `TmsBrowseRecord`(商家口径 `targetId = memberId` 的浏览记录数);俱乐部口径=俱乐部账号及其主题/活动的浏览记录数,`targetId`/`targetType` 取值实现时按字段语义确认。

## 7. 后端改动汇总

**确定新增:**
- `/api/club/update-mine` —— 编辑已通过俱乐部资料。
- `/api/club/dashboard` —— 统计卡(成员/主题/活动数 + 曝光量)。
- club 版"招募广场/我的参与"接口(`list-to-club` / `registration/club/list` 或泛化现有)。

**需核对(实现时):**
- `/api/coupon/verification` 是否接受 club 账号(应按券归属校验,而非硬编码 merchant)。
- `/pages/member/coupon/coupon`、`/pages/role/center/index` 在 club 身份下是否正常取数。

**复用、无需改:**
- `/api/club/members`、`/api/club/remove-member`、topic/activity 列表与创建接口。

**数据库:无 schema 改动。**

## 8. 改动清单(小程序端 chengyinhub-xcx)
- `pages/index/index.js`:首页路由新增 club 分支。
- `app.json`:注册新页面。
- 新增 `pages/club/index/index`(克隆商家首页样式,九宫格换成:扫码验券·优惠券发布·财务结算·俱乐部资料·成员管理·主题管理·活动管理)。
- 新增 `pages/club/members/index`(成员管理)。
- 资料编辑页(复用 apply 表单或新建 edit 页)。

## 9. 明确不做(YAGNI)
- 不做多角色 / 角色叠加(账号只能一个 role)。
- 不做加入审批流(成员即时加入,只支持移除)。
- 不新增 topic/activity 与 club 的关联表(主题/活动按 memberId 归属)。
- 实时动态板块可后置(非首版必需)。
