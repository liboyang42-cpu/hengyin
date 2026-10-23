# DOC2 · O5b-4 审核留痕与状态位真源收敛设计（只读设计，未实施）

> 分支 `feature/doc2-o5b-audit-20261022` · 2026-10-22 · 本文件**只给设计与迁移路径**，不改任何写路径；
> 收敛实施等 owner 拍板。配套一致性探针：`chengyinhub-admin/src/test/java/com/chengyinhub/business/service/AuditStatusTruthConsistencyTest.java`。

## 0. 结论先行

| 状态事实 | 建议唯一真源 | 现状里的其它编码（收敛后降为投影/退役） |
|---|---|---|
| 主题「审核结论」 | `audit_task`（内容审核槽 `open_guard=1` 那条线上最新终态单）+ `audit_record` 时间线 | `cms_topic.check_status`（0/1/2） |
| 主题「是否公开」 | `cms_topic.status` + `user_status` + `del_flag`（公开闸本来就只看这三列） | — |
| 主题「机审留置」 | `cms_topic.content_safety_hold` | `check_status=2 AND status≠2`（`TopicContentSafetyHold.isHeld`） |
| 主题「抽检池」 | `cms_topic.review_flag` | — |
| 主题「Beta 期」 | `cms_topic.beta_flag` | — |
| 主题商业立项/开售 | `cms_topic.lifecycle`（由 `TOPIC_PROJECT_REVIEW` / `TOPIC_SALE_REVIEW` 单驱动） | — |
| 个人玩法审核结论 | `audit_task`（biz_type=TEMPLATE）+ `cms_member_template.status` 作为投影 | `cms_member_template.check_status`（与 status 恒等拷贝） |
| 玩法草稿/已提交 | `cms_member_template.draft_status` | `status=0` 的两种含义 |
| 模板广场在架 | `cms_template_library.publish_status` | — |
| 模板广场审核结论 | `audit_task`（biz_type=TEMPLATE_LIBRARY） | `cms_template_library.status=2`（被四种原因复用，见 D-7） |
| 审核留痕（谁、何时、什么结论、为什么） | `audit_record` | `business_audit_event`（保留为幂等重放库 + 跨域审计）、`sys_oper_log`（HTTP 操作日志，不算留痕）、各领域私有日志 |

## 1. 「四套并行留痕」是哪四套

审计报告原文在仓库里找不到逐条清单，以下是按代码复盘的两种读法，**两种读法的收敛方向一致**：

**读法 A：一次主题/模板人工裁决同时写四处**（`AuditTaskServiceImpl.review()`）

1. `audit_task` 状态 CAS（`updateAuditTaskReviewCas`）
2. `audit_record` 一行（APPROVE / REJECT / ESCALATE …）
3. `business_audit_event` 一行（`AUDIT_DECISION_*`，before/after 只有 `{status, version}`；同表还承担 review/revert 的幂等重放）
4. `sys_oper_log`（工作台控制器 `@Log`，只记标题/URL/操作人）

另有驳回原因被抄写进内容行 `reason` 列。

**读法 B：四个领域各有一套审核/处置留痕**

| 系统 | 表 | 写入方 |
|---|---|---|
| 统一审核 | `audit_task` / `audit_record` / `audit_appeal` | `AuditTaskServiceImpl` |
| 社区帖处置 | `community_post_enforcement` / `community_post_appeal` | `CommunityPostServiceImpl`（审核驳回会再写一条 `AUDIT_REVIEW_REJECTED`，与 audit_record 双写） |
| 社区举报 | `community_report_case` / `community_report_timeline` / `community_report_appeal` | `CommunityTrustServiceImpl`（与 audit_task 举报槽是两条独立管线） |
| 官方活动 | `official_event_audit_log` | `OfficialEventV2ServiceImpl`（与 audit_task 双写；`REVIEW_REJECTED` 行的 operator_id 记的是提交人而非审核员） |

**留痕缺口**（与「并行」同样会让真相分叉）：

- `auditTaskService.submit()` 建单不写 `SUBMIT` 记录（主题 user_publish / republish / post_review_flag / content_safety_relist、官方活动走这条）。
- `cancelOpenTemplateTasks`（status=4）不写 `audit_record`，于是出现「任务=4、内容=2、时间线无记录」。
- `cancelTopicPublicationTaskCas` 把取消原因塞进 `risk_level='SUPPLY_REPLACEMENT'`。
- `audit_record.action` 实际写入 `CANCEL` / `ESCALATE`，但常量只定义了 APPROVE/REJECT/REVERT/SUBMIT/REPORT。

## 2. 状态位写入口清单（与探针测试 `REGISTERED_WRITERS` 一一对应）

只统计 MyBatis XML 中 `UPDATE <表> SET` 写到状态列的语句（INSERT 不计；Java 侧通过 `updateCmsTopic` 等通用语句写入的调用点见 §2.1）。

| 表#语句 | 写到的状态列 |
|---|---|
| cms_topic#updateCmsTopic | beta_flag, check_status, status, user_status |
| cms_topic#casUserStatus | user_status |
| cms_topic#updateReviewFlag | review_flag |
| cms_topic#graduateBetaIfBeta | beta_flag |
| cms_topic#reopenAuditDecision | check_status, status, user_status |
| cms_topic#markContentSafetyHold | content_safety_hold |
| cms_topic#clearContentSafetyHold | content_safety_hold |
| cms_member_template#approveBundledDraft | check_status, draft_status, publish_status, status |
| cms_member_template#applyAuditDecision | check_status, publish_status, status |
| cms_member_template#takeDownReported | check_status, publish_status, status |
| cms_member_template#updateCmsMemberTemplate | check_status, draft_status, publish_status, status |
| cms_member_template#reopenAuditDecision | check_status, publish_status, status |
| cms_member_template#restoreReportTakedown | check_status, publish_status, status |
| cms_template_library#applyAuditDecision | publish_status, status |
| cms_template_library#takeDownReported | publish_status, status |
| cms_template_library#takeDownReportedByMemberTemplate | publish_status, status |
| cms_template_library#updateCmsTemplateLibrary | publish_status, status |
| cms_template_library#reopenAuditDecision | publish_status, status |
| cms_template_library#restoreReportTakedown | publish_status, status |

**规则**：新增/改动上表任何一行，探针测试会失败；先在本节登记并说明对 §0 真源的影响，再同步测试里的清单。

### 2.1 通用语句的主要 Java 调用点（不完整，供实施时逐个改造）

- 主题：`CmsTopicServiceImpl` 建题/复制/后台建题/简版发布/先发后审（:4217、:646、:2022、:2985、:4552）；`TopicEditionReopenServiceImpl:253`；`AuditTaskServiceImpl.applyModeration`（驳回 :1585 起、过审 :1602 起）；`WxContentSecurityServiceImpl:615-640`（机审留置：user_status=0、check_status=2）。
- 玩法：`TemplatePublishServiceImpl` 发布/草稿/提审/后台增改/删除；商家节点模板自审直接 1/1/1 且不建单。
- 广场：`TemplatePublishServiceImpl.copyCanonicalToProjection` / `withdrawProjections` / `updateTemplateLibraryStatus`；`WxContentSecurityServiceImpl:836-840`。

## 3. 已知分叉清单（D-n 编号供测试与后续 PR 引用）

| 编号 | 分叉 | 触发路径 | 探针 |
|---|---|---|---|
| D-1 | 机审留置两处编码：`content_safety_hold=1` 与 `check_status=2 AND status≠2`。过审两者都清；**撤销/申诉重开（`reopenAuditDecision`）只回写 check_status，不动 content_safety_hold**；`selectCmsTopicVo` 不查 content_safety_hold/review_flag | 撤销、申诉重开 | `knownDivergenceReopenDoesNotTouchContentSafetyHoldIsPinned`（钉现状）、`topicApprovalLandsIn…ClearsBothHoldEncodings` |
| D-2 | 先发后审下 `status=1, check_status=0` 是设计内状态，`status` 单列不等于「已过审」；公开闸只看 status/user_status/del_flag | 先发后审 | 矩阵允许组合 |
| D-3 | 旧版 best-effort 提审 try/catch 只打日志：主题可停在 `status=0` 却无开放审核单 | `CmsTopicServiceImpl:4296`、`:4876` | 只读 SQL Q1 |
| D-4 | 过审但配额/供给失败：任务=1、status/check=1、user_status=0 | 配额满 | 矩阵允许组合（user_status∈{0,1}） |
| D-5 | 举报单「不成立」直接 return，不动内容（设计内） | 举报槽 | — |
| D-6 | `cms_member_template.status` 与 `check_status` 恒等拷贝；草稿与待审靠 `draft_status` 区分 | 全部玩法写入口 | `memberTemplateStatusAndCheckStatusAreAlwaysWrittenTogether` |
| D-7 | `cms_template_library.status=2` 同时表示驳回 / 作者撤回 / 编辑失效 / 机审下架；对应 audit_task 常是 CANCELLED(4) 且无 audit_record | 撤回、编辑、机审 | 只读 SQL Q3 |
| D-8 | 玩法机审下架只改广场行，`cms_member_template` 仍 1/1/1 | `WxContentSecurityServiceImpl:810-845` | 只读 SQL Q4 |
| D-9 | 主题 `lifecycle=7(PENDING_REVIEW)` 由商业审核单驱动，与 TOPIC 内容单驱动的 status/check_status 互相独立 | 商业立项/开售 | — |

## 4. 迁移路径（建议分五步，每步可单独回滚；P2 起每步都要 owner 确认）

**P0 探测（本 PR 已做）** 一致性探针测试：写入口清单棘轮 + 成对写入不变式 + 审核结论→内容行组合，外加 D-1 钉子。CI 上持续跑，新增分叉立刻红。

**P1 只读对账（不改数据）** 提供下面的只读 SQL 给 DBA 在只读副本或经 `chengyin-db-migrate` skill 只读预检时跑，输出分叉计数与样本 id；不建表、不写库。

```sql
-- Q1 (D-3) 待审却没有开放内容审核单的主题
SELECT t.id FROM cms_topic t
WHERE t.del_flag = 0 AND t.is_draft = 0 AND t.status = 0
  AND NOT EXISTS (SELECT 1 FROM audit_task a WHERE a.biz_type = 'TOPIC' AND a.biz_id = t.id
                  AND a.status IN (0, 3) AND a.open_guard = 1)
LIMIT 50;

-- Q2 (D-1) 两处留置编码不一致
SELECT id, status, check_status, content_safety_hold FROM cms_topic
WHERE del_flag = 0
  AND (content_safety_hold = 1) <> (check_status = 2 AND status <> 2)
LIMIT 50;

-- Q3 (D-7) 广场行 status=2 但最新审核单是 CANCELLED 且无任何 audit_record
SELECT l.id FROM cms_template_library l
JOIN audit_task a ON a.biz_type = 'TEMPLATE_LIBRARY' AND a.biz_id = l.id
WHERE l.del_flag = 0 AND l.status = 2 AND a.status = 4
  AND a.id = (SELECT MAX(a2.id) FROM audit_task a2 WHERE a2.biz_type = 'TEMPLATE_LIBRARY' AND a2.biz_id = l.id)
  AND NOT EXISTS (SELECT 1 FROM audit_record r WHERE r.task_id = a.id)
LIMIT 50;

-- Q4 (D-8) 广场已被下架、个人玩法仍 1/1/1
SELECT m.id FROM cms_member_template m
JOIN cms_template_library l ON l.member_template_id = m.id AND l.del_flag = 0
WHERE m.del_flag = 0 AND m.status = 1 AND m.check_status = 1 AND l.status = 2 AND l.publish_status = 0
LIMIT 50;

-- Q5 (D-6) status 与 check_status 已经分叉的个人玩法
SELECT id, status, check_status, draft_status FROM cms_member_template
WHERE del_flag = 0 AND status <> check_status LIMIT 50;

-- Q6 内容审核槽最新终态与主题列不符(排除留置/先发后审的设计内组合)
SELECT t.id, a.status AS task_status, t.status, t.check_status FROM cms_topic t
JOIN audit_task a ON a.id = (SELECT MAX(x.id) FROM audit_task x WHERE x.biz_type = 'TOPIC' AND x.biz_id = t.id
                             AND x.source_type NOT IN ('report') AND x.status IN (1, 2))
WHERE t.del_flag = 0
  AND ((a.status = 1 AND NOT (t.status = 1 AND t.check_status = 1))
    OR (a.status = 2 AND NOT (t.status = 2 AND t.check_status = 2)))
LIMIT 50;
```

（Q6 会命中「过审后作者再次编辑重新进入待审」这类合法后续状态，样本需人工看；它是对账线索，不是判错。）

**P2 写路径收口（开关保护，先双写后切读）**

1. 留痕：所有建单入口补 `SUBMIT` 记录；`cancelOpenTemplateTasks`/`cancelTopicPublicationTaskCas` 补 `CANCEL` 记录并把原因从 `risk_level` 挪进 `reason_code`；`AuditRecord` 补 `CANCEL`/`ESCALATE` 常量。`audit_record` 成为唯一裁决时间线。
2. `business_audit_event` 保留为幂等重放与跨域审计，不再被当作内容审核时间线读取；`sys_oper_log` 明确为 HTTP 操作日志。
3. 社区帖处置、社区举报、官方活动：保留各自领域表作为领域状态机，审核裁决的「谁/何时/结论」只认 audit_record，领域表通过 `audit_task_id` 回指（需要新增列的走 P3 迁移）。
4. 主题机审留置：`reopenAuditDecision` 等写入口与 `content_safety_hold` 对齐（D-1）；读侧 `TopicContentSafetyHold.isHeld` 改为读 `content_safety_hold`。
5. 玩法：`check_status` 只读投影（写入口统一由 status 派生），D-8 补齐个人玩法侧状态。

**P3 回填与结构（新增幂等 migration，`sql/migration_*.sql`，只提交不执行）**

- 用 P1 的对账结果回填 D-1/D-5/D-8 存量；
- `cms_template_library` 增加下架原因编码列（拆开 D-7 的四种 status=2）；
- 领域表增加 `audit_task_id`（如采纳 P2-3）。
- 每个 migration 走 `chengyin-db-migrate` skill 只读预检 + owner 确认后由 owner 执行。

**P4 退役镜像列（Ask first）** `cms_topic.check_status` 的「留置」语义、`cms_member_template.check_status` 在所有读侧切换完成后才考虑退役；DDL 必须单独确认。

## 5. 待 owner 拍板

1. §0 真源选择是否认可（特别是：审核结论以 audit_task 为准、内容列只做投影）。
2. 读法 A/B 中，审计报告指的是哪一种（影响 P2-3 的范围）。
3. P2 的开关粒度：按表一把，还是按 D 编号一把。
4. D-7 下架原因编码列的取值表。

## 6. 本 PR 对应的其它 O5b 条目（简表）

| 条目 | 状态 | 开关（默认关） |
|---|---|---|
| 1 轻改/实质改动分级 | 已实现（活动编辑） | `chengyin.audit.o5b.light-edit.enabled` |
| 2 申诉证据附件 | 已实现 | `chengyin.audit.o5b.appeal-attachments.enabled` |
| 3 举报首响时钟带进处置单 | 已实现 | `chengyin.audit.o5b.report-sla-inherit.enabled` |
| 4 留痕/状态位真源 | 本设计 + 探针测试；实施待拍板 | — |
| 5 模板标题相似度 WARN | 已实现 | `chengyin.audit.o5b.template-title-similarity.enabled` |
| 6 AI 安全预检接入发布检查 | **停在选项**（见 PR 描述） | — |
| 7 系列 CLOSED 终态 | 已实现 | `chengyin.club.o5b.series-close.enabled` |
