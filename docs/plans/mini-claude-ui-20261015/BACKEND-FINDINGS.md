# 后端风险待办汇总（BL-132，2026-10-16 快照）

> 只描述，不改后端。来源：本线各 PR 的 BLOCKED 说明与监督评审；每条的 file:line 已在基线 `claude/mini-ui-features-20261015` 上重新核对（行号以该基线为准，可能与当时评审写的行号不同，已注明）。
> 归属：**Codex** = 后端/控制分支（`ApiTopicController` 等已在 Codex `owned_files`）；**协调者** = 需 C00 排期或决定由谁改。后端改动一律走 PR #21 / `BACKEND_CHANGES.md` 流程，本线不写后端代码。
> 风险分级：高 = 可被登录/匿名用户直接利用、泄露秘密或个人信息；中 = 数据口径错误或权限模型不一致；低 = 一致性/维护问题。

## 1. 安全 / 隐私

| # | 接口 | 源码 | 风险 | 建议修法 | 归属 | 来源 |
|---|---|---|---|---|---|---|
| F1 | `POST /api/template/groupByCategory` | Controller `ApiTemplateController.java:512-521`；Service `TemplatePublishServiceImpl.java:1106-1145`（`getTemplatesGroupByCategory`，不调用 `TemplateSecrets::strip`，对比同文件 `getHomeData` 的 `:1175`）；SQL `CmsTemplateLibraryMapper.xml:135-160`（`selectTemplatesByCategoryIds`） | **高**。SQL 直接选出 `question_answer`、`correct_answer`、`answer_reveal`、`hint1/hint2`、`sensor_config` 等答案/判定字段，且 WHERE 只有 `mt.publish_status = 1`，**没有 `mt.status = 1`**（未审核/驳回模板也会出）。该路径不在 `SecurityConfig.java:142-145` 的 permitAll 白名单里，所以是**任意登录用户**可调。小程序目前零调用，但接口本身可直接请求 | Service 在返回前 `templates.forEach(TemplateSecrets::strip)`（与 homeData 一致）；SQL 加 `AND mt.status = 1`；若无调用方，也可考虑下线该端点 | Codex（模板服务） | #169 BL-125 及其监督评审 |
| F2 | `GET /api/merchant/ops/public-detail` | Controller `ApiMerchantOpsController.java:35-42`（`@Anonymous`）；Service `MerchantDetailAggregationServiceImpl.java:49`（`CALL` 快捷动作 value = `merchant.getPhone()` 原值）、`:55-58`（WEBSITE 原值）、`:33-35`（可见性判定） | **高（隐私）**。匿名可按 `merchantId` 枚举拿到商家原始电话；而现用 `POST /api/merchant/public-detail` / `public-home` 的 `PublicMerchantVO` 白名单是**刻意排除**电话/官网的，两个公开口径矛盾。另：可见性用 `delFlag != 2`（`:35`），现用公开接口是 `delFlag == 0`，口径不一致——`delFlag` 为其它非 0 值（如 1）的商家在 ops 口径下仍可见 | 先产品决定公开商家页是否展示电话/官网；决定前 ops 接口对匿名调用去掉 `CALL`/`WEBSITE` 的 value（或整体加登录）；可见性改为与现用接口同口径 `delFlag == 0` | 协调者（产品决定）→ Codex | #168 BL-124 及其监督评审 |
| F3 | `POST /api/topic/xp-budget` | `ApiTopicController.java:1734-1742`；`XpBudgetServiceImpl.java:35-40` | **中**。只按 `topic_id` 计算并返回该主题的 XP 预算与节点奖励分布，**没有创建者/归属校验**（AGENTS.md「写操作必须校验 owner/scope」虽针对写接口，但这是发布编辑器的内部数据，草稿/未公开主题的结构也能被任意登录用户探测） | 校验调用者是主题创建者（或协作者）后再计算；不满足返回 403/404 | Codex（`ApiTopicController` 在 owned_files） | #145 BL-101 监督评审 |
| F4 | `POST /api/spoiler/preference`（`revealAll`） | `ApiSpoilerController.java:39`；影响面见 `ApiPlayProgressController.java:674-690` 注释 | **低（已缓解）**。端点只校验登录、无报名关系即可设置。2026-09-02 修复后 `/api/play/nodes` 已不再随 revealAll 放出答案正文（`:686-690` 移除 `answerReveal`），剩余影响只是节点 `revealed` 标记。风险已转为「语义」问题：若将来有调用方按 `revealed` 展示剧情文本，需重新评估 | 维持现状；若产品要开放「全部揭晓」开关，先限定到已报名会话，并在 `BACKEND_CHANGES.md` 记录 | 协调者（产品决定） | #127 BL-84 |
| F5 | `POST /api/club/badge/list` | `ApiClubController.java:1174-1180` | **低**。只校验登录与俱乐部存在，不校验调用者是否成员/主理人；返回该俱乐部自建勋章定义列表（非个人数据） | 若勋章定义被视为成员内可见，加成员校验；否则在接口注释里写明「公开」 | Codex / 协调者 | #23 CL3 监督评审（「/badge/list 不校验成员身份，交 C00」） |
| F9 | `POST /api/coop/credit` | `ApiCoopController.java:1051-1060`（`creditSummary(target)`） | **低（需产品决定）**。默认查本人；body 传任意 `memberId` 就能查他人近 90 天履约率/违约计数，只校验登录、不校验双方是否有合作关系。控制器注释写明「主页展示合作方履约率」属设计意图，但可被任意登录用户按 ID 枚举 | 产品确认履约信用是否公开；若只给合作方看，按是否存在邀约/合作关系校验后再返回他人数据，否则在接口注释与隐私口径里写明「公开」 | 协调者（产品决定）→ Codex | #147 BL-103 及其监督评审；BL-132 #176 监督 nit |

## 2. 数据口径 / 权限模型

| # | 接口 | 源码 | 风险 | 建议修法 | 归属 | 来源 |
|---|---|---|---|---|---|---|
| F6 | `GET /api/agreement/versions`（`activeVersions`） | `AgreementDocumentServiceImpl.java:24-34`；`AgreementDocumentMapper.xml:26-30` | **中**。SQL 按 `doc_type asc, publish_time desc, id desc` 排序，循环 `versions.put(docType, version)`——同类型有多条 `status=1` 时**最后写入的是最旧版本**，与 `/get`、`/consent` 用的 `selectActiveByType ... limit 1`（最新）不一致 | 改为 `putIfAbsent`，或直接按类型调 `selectActiveByType`；可加「每类型唯一生效」约束 | Codex | #131 BL-88 |
| F7 | `POST /api/topic/list-to-merchant`（商家可报名主题） | `ApiTopicController.java:2104-2106`（评审时写的是 `:2086`，基线行号已变） | **中**。仍以 `umsMember.getUserType() != 2` 判定「商家才能报名」，与 AGENTS.md「user_type → RBAC role 迁移收尾、RBAC role 为单一真源」不一致：已迁到 RBAC 商家角色但 `user_type` 未同步的账号会被拒 | 改用 RBAC 商家角色判定（与其它商家接口同一工具方法） | Codex（`ApiTopicController` 在 owned_files） | #149 BL-105 监督评审 |
| F8 | `POST /api/template/groupByCategory`（口径） | `TemplatePublishServiceImpl.java:1125-1129` 按 `TemplateLibraryVO::getCategoryId` 分组 | **低**。按 `mt.category_id` 单值分组，而小程序模板库按 `activity_categoryids`（多值）归类，且 `pages/template/index.js` 注释明确两列不同步；只含互动模板，不含主题模板。与 F1 一并处理或下线 | 若保留，改按 `activity_categoryids` 归类并说明不含主题模板 | Codex | #169 BL-125 |

## 3. 读合同缺口（不是缺陷，是本线 BLOCKED 的后端前提）

已在 #103 BL-60 `READ_CONTRACT_REQUESTS.md` 整理交 Codex，这里只列索引，不重复：

| 缺口 | 阻塞的 PR |
|---|---|
| 勋章发放记录（某勋章已发给谁）读接口 | #87 BL-46 |
| 官方邀约跟踪只回原始 ID，缺主题名/商家名 | #115 BL-72 |
| 协办方状态 / 只读 parties（含 displayName 来源） | #82 BL-40、#35 CL6 |
| 草稿读合同（含 parties） | #79 BL-38 |
| 勋章墙接口无俱乐部区分字段（clubId/clubName） | #41 BL-07 |
| official-inbox 分页 | #92 BL-50 |

## 4. 非后端但相关的提醒（不在本表处理）

- 商家核销台账只拉第 1 页（20 条），补分页应走 `finance/redemptions` 自带的 `pageNum`，不是 `orders/ledger`（#157 BL-113）——小程序侧待另开卡。
- #37 的 `silent-error-gate`、`Migration safety` CI 红属基线问题，需协调者把 master 合入基线。

## 复核方法

```bash
# 在基线分支上核对行号
git show origin/claude/mini-ui-features-20261015:chengyinhub-system/src/main/resources/mapper/business/CmsTemplateLibraryMapper.xml | sed -n 135,160p
grep -n "@Anonymous" -A2 chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiMerchantOpsController.java
grep -n "getUserType() != 2" chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiTopicController.java
```
