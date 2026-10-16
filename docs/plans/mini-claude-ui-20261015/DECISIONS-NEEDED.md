# 待产品决定汇总（BL-128，2026-10-16 00:30Z 快照）

> 只写文档，不改代码。来源：标题含 BLOCKED 的 open PR（`claude/bl-*`、`claude/cl*`）正文，外加部分完成、剩余部分等决定的 #84、#110、#138、#35。共 63 个 PR，分类结果：需产品决定 14 · 已定/已作废 3 · 已等价/已完成 22 · 等其他 PR 10 · Codex 宿主 7 · 需后端合同 7（计数与下文各表一致）。每个 PR 只按「解锁条件取决于谁」归一类。
> 用法：在每行「选择」一栏写下字母，交回开发线即可执行。⭐ = PR 里的推荐项。

## 一、需要用户拍板（14 项）

| PR | BL | 问题 | 选项与影响 | 涉及文件 | 选择 |
|---|---|---|---|---|---|
| #75 | BL-34 | 俱乐部详情要不要第二个举报入口？ | **A⭐** 保留现有治理入口（`pages/club/detail/index.wxml:394-401`），不改，关闭 PR **B** 改用共用 `cy/report-sheet`（⚠️ 改现有 UI，与用户决定「不改现有 UI」冲突，需另行授权） | B：`pages/club/detail/index.{wxml,js}`。⚠️ #75 分支带 #73 代码，无论选哪个都不要合 #75 | |
| #84 | BL-42 | 「收藏的据点」页入口放哪？（页面已做好） | **A⭐** 在漫游 `entry-map__actions` 照「官方线路」加一个「收藏」按钮 **B** 不加入口 / 放别处 | `pages/roam/index.{wxml,js}`（`openNodeFavorites`）；两种都需 `app.json` subpackageRoam 加 `node-favorites/index` | |
| #106 | BL-63 | 是否把发帖迁到 v1 社区流程？ | **A** 不迁，保持 BLOCKED **B** 发帖改走 `/api/v1/community/posts`，再加社区规范确认 `cy-sheet`（428 后重试） | B：发帖页（未逐一列出）；`CommunityPostServiceImpl` 属 Codex | |
| #110 | BL-67 | 后台没有审核页，现在就开放「信息纠错」写入口吗？ | **A** 现在开放：提交后一直停在「待审核」**B** 等 `chengyinhub-ui` 有审核页再开 | 已在 PR：`components/cy/merchant-correction-sheet/*`、`subpackageMember/components/cy/profile/index.{js,json,wxml}`；协调者补丁 action-ledger、get-auth-matrix 33→34 | |
| #111 | BL-68 | 俱乐部事后被驳回（status=2 + 原因）在哪里提示？主理人有多个俱乐部时显示哪个？ | **A** `pages/club/apply` **B** 我的俱乐部 **C** 俱乐部详情。另需后端提供按俱乐部 id 读 status/rejectReason 的接口 | 视选项而定 | |
| #127 | BL-84 | 「全部揭晓（revealAll）」偏好是否对用户开放？ | **A** 不开放 **B** play 页先消费节点 `revealed/spoilerProtected`（`pages/play`，Codex），再在设置里加开关调 `POST /api/spoiler/preference`；需先定义语义与付费揭晓绕过风险 | B：`pages/play/**`（Codex）+ 设置页 | |
| #129 | BL-86 | 是否恢复 UI-09 删掉的营销页「经营事件」区块和 7/30 切换？ | **A** 保持固定 30 天，关闭 **B** 恢复，直接切 marketing-home 的 `windowDays`（不另调 `/funnel`） | B：`pages/merchant/marketing/index.{js,wxml}` | |
| #131 | BL-88 | 协议正文与版本以谁为准？ | **A** 以后端正文为准（替换 `utils/agreement-docs.js`）**B** 本地正文 + 本地版本 **C** 本地正文 + 后端版本（有不一致风险）。另：`AgreementDocumentServiceImpl.activeVersions()` 多版本时返回最旧版本，需 Codex 修 | A：`utils/agreement-docs.js` 及协议页 | |
| #138 | BL-95 | 碎片/彩蛋要不要新开展示位？（里程已补进现有块） | **A** 只合里程，碎片/彩蛋不做 **B** 批准新块，大概率在个人页组件 | B：`subpackageMember/components/cy/profile/**` | |
| #146 | BL-102 | 个人页「推文」分区要不要加统计行？ | **A** 加一行 3 格（仅本人可见，读 `/api/creativesquare/summary`）**B** 不加 | A：`subpackageMember/components/cy/profile/index.{js,wxml}`（推文 tab，wxml:229-262） | |
| #153 | BL-109 | 是否开放创作者申请 / 创作者信息 / 收益展示？ | **A** 保持关闭 **B** 开放 `/api/creator/apply` 并展示创作者中心与收益块（位置待定，大概率个人页） | B：个人页组件（未定） | |
| #156 | BL-112 | 是否新开「IP 世界」入口？ | **A** 不开 **B** 新建 IP 世界页 + 入口，读 `/api/world/home`、`/api/world/quests` | B：新页面 + `app.json` | |
| #162 | BL-118 | 模板货架点卡后是否先进详情？ | **A** 点卡 → 详情 → 配置（⚠️ 改变 2026-08-30 定下的点卡行为，与用户决定「不改现有 UI」冲突，需另行授权）**B** 另给一个「详情」入口 **C** 不做（BL-119 #163 随之保持 BLOCKED） | A：`pages/template/index.js` `jumpDetail`（:631） | |
| #165 | BL-121 | 俱乐部「开一场」走哪条流程？ | **A** 保持现有 event-ops 排期（Codex），不改 **B** 改用 `/api/club/open-session(-topics)`（替换 Codex 入口或出现两套流程） | B：`pages/club/detail/index.wxml:464/762`、`index.js:2432` | |

附带隐私口径（来自 #168 BL-124，非 BLOCKED 主因）：公开商家页是否展示电话 / 官网 / 评分汇总？现有公开白名单刻意排除电话和官网，`GET /api/merchant/ops/public-detail` 却匿名返回它们。**A** 维持不展示 **B** 展示，并确认匿名暴露的范围。

## 二、Codex 宿主类（交协调者，不需用户拍板）

| PR | BL | Codex 文件 | 是否附补丁 |
|---|---|---|---|
| #135 | BL-92 剧情案卷 | `pages/play/index.js` | 无 |
| #136 | BL-93 剧情背包 | `pages/play/index.js` | 无 |
| #137 | BL-94 主题游玩历史 | participation-detail / `pages/play/index.js`（另需 `createTime` 字段） | 无 |
| #139 | BL-96 收藏活动 | `pages/mylike/mylike.js` | 正文有补丁描述（未应用） |
| #140 | BL-97 商家云端草稿 | `pages/publish/fabu/index.js`、`pro-editor-draft.js` | 无 |
| #145 | BL-101 奖励总览 | `pages/publish/fabu/index.js` | 有 JS + wxml 片段 |
| #161 | BL-117 主题游玩记录卡 | `pages/play/index.js` | 无 |

## 三、需后端补合同（交 Codex / 后端）

| PR | BL | 缺什么 |
|---|---|---|
| #35 | CL6 | `GET /api/official/events/{id}` 返回 `parties[]` + `viewerCanManage`（挂载另等 CL4 #27） |
| #41 | BL-07 | 勋章墙 `BadgeWallItemVO` 加 `clubId`/`clubName` |
| #79 | BL-38 | V2 草稿读合同，或只写 parties 的接口（现在草稿保存是全量覆盖） |
| #82 | BL-40 | `GET /api/official/v2/events/{id}/parties` |
| #87 | BL-46 | `POST /api/club/badge/holders`（某勋章发给了谁） |
| #92 | BL-50 | party-inbox 分页（pageNum/pageSize/total） |
| #115 | BL-72 | `/api/official/invites` 返回 `topicName` + 商家名 |

## 四、等其他 PR（不需要决定，合入上游后自动解锁）
- 等 CL2 #25：#71 BL-31、#94 BL-52、#105 BL-62、#107 BL-64。
- 等 CL4 #27：#76 BL-35、#85 BL-44、#108 BL-65、#99 BL-56（另等 CL6 #35）。
- 等 CL3 #23：#97 BL-55。
- 等 BL-118 #162：#163 BL-119（随 #162 的决定）。

## 五（甲）、已定 / 已作废（可直接关闭）
- #53 BL-16、#66 BL-30：用户决定「不改现有 UI」，两项作废（`BACKLOG.md:5`），监督结论 CLOSE；#53 不再等 CL4 #27。
- #61 BL-25：用户已定「现有四态为准」，A/B 都不做。

## 五（乙）、已等价 / 已完成（可直接关闭）
#48、#63、#78、#80、#86、#89、#90、#96、#102、#116、#121、#124、#133、#142、#148、#149、#154、#155、#157、#160、#168、#169。

## 六、PR 里顺带报出的后端 / 安全问题（均未修，交 Codex 判断）
- #145：`/api/topic/xp-budget` 没有归属校验。
- #169：`/api/template/groupByCategory` 返回模板答案字段（`question_answer`、`correct_answer` 等），且只过滤 `publish_status=1`、不过滤 `status=1`。
- #149：`/api/topic/list-to-merchant` 仍按 `user_type` 判权，未走 RBAC。
- #115：invites 的 GET 读取时会写库。
- #168：ops public-detail 的可见性判定用 `delFlag != 2`，其余接口用 `== 0`；并匿名返回电话/官网。
- #147：`/api/coop/credit` 允许任意登录用户按 memberId 查他人履约信用（控制器注释称属设计意图）。
