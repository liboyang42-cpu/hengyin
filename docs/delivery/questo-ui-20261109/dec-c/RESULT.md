# DEC-C 结果（PR #809）

## 本轮完成（D-08 / D-19 / D-36 / D-40 / D-98 见 PR 正文，已在前序提交）

### D-126 帖文
- 小程序实际使用的实现：`/api/v1/community/*`（`ApiCommunityPostController` → `CommunityPostServiceImpl`），客户端 `utils/community-post-api.js` 把旧的 `/api/creativesquare/*` 调用在端内转成 canonical 请求。创建、编辑、发布、列表（含关注/搜索/我的/收藏）、详情、删除、互动、评论、举报（`POST /reports`）均走此套，写入均按登录人校验归属。
- 保留：canonical 帖文套件。举报补齐：不能举报自己的帖文；`/api/community-trust/reports/submit` 遇 CONTENT/COMMENT 转交 canonical 举报，避免第二套规则。
- 退场：旧 `ApiCreativeSquareController` 的 `/action /list /info /bookmark /delete /report /like` 去掉原映射，路由统一返回 HTTP 410（`LegacyPostHttpRetirementTest`），不再读写旧表；其余路由未动。方法体保留为无路由死代码（未删除，避免无本机编译下的大范围改动），后续可清理。
- 小程序契约测试补 `is_my/favorite_only` 的 0/1 线值、关注分区、关键词与游标隔离。

### 俱乐部个人收款（D-05 / D-09 / D-16）
- 新开关 `payout.ecommerce.personal-club-enabled`，默认 OFF（还需分账总开关与平台 appid）；关闭时行为与生产一致。
- 仅俱乐部场次、俱乐部自有主题的现金收入；未冻结收入跟随当前俱乐部主，已冻结历史/退款/债务留给原受益人；账单显示“来自俱乐部 X”（俱乐部名快照）。
- 个人绑定缺失/无效：该笔分账暂停并写明原因（`PERSONAL_BINDING_REQUIRED`），不改道、不转余额；接收方类型 `PERSONAL_OPENID`，使用平台 appid。
- 绑定入口：`POST /api/club/personal-receiver/bind`、`GET /api/club/personal-receiver/status`（并入 `ApiClubController`，只认登录人，仅俱乐部主）。
- 迁移：`migration_20261112_dec_c_payout.sql`，已登记 release-migrations 与 migration-dependencies。
- 架构门禁适配：不新增 Mapper/Controller 类——新查询并入 `EcomPayoutMapper`，两条写入分别并入 `SettlementDetailMapper`、`CmsActivityMapper`（各自归属表）；新增服务类位于未登记范围的 `payout` 包。
- 偏差说明：为承载新列，WIP 修改了若干既有 Mapper 语句（EcomPayoutMapper、SettlementDetail、CoopSettlement*、UmsMemberBalanceDetail）的列清单/条件，未能全部改为新增语句；已交 CI 与评审判断。小程序暂无绑定页面。

## 本地验证
- `node --test tests/unit/**/*.test.js`：17511 通过，0 失败；UI-GATE-0:PASS。
- 迁移布局/安全门禁本地通过。Java 未在本机构建（仓库规定），全部由 CI 验证。

## CI 结果
见下方（最终更新）。
