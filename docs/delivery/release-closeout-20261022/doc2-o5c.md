# DOC2 · O-05 Lane O5c:审核遗留最后 3 项

> 分支 `feature/doc2-o5c-audit-20261023` · 基于 master `a8504c39bdcfcf7432e94e674ca21f1a1d957706` · 2026-10-23
> 只做后端。所有新行为都在开关后面,**默认关**;开关关时行为与 master 一致。
> 没有新 DDL / migration、没有新依赖、没有改 auth/RBAC/JWT;管理端认领/队列拆分(已暂停)没碰。
> 与 O5a(#532)、O5b(#531)以及它们的集成 PR #536 不重叠:先读了这两条分支的 PR 描述和
> `docs/delivery/doc2-o5b-audit-truth.md`。本 PR 没有改 `AuditTaskServiceImpl`,所以不会撞上
> #531×#532 的 `@Value` 冲突。

| # | 条目 | 状态 | 开关(默认 false) |
|---|---|---|---|
| a | 四套留痕 → 一处读全 | ✅ 只读统一投影 + 后台查询接口 | `chengyin.audit.o5c.unified-trail.enabled` |
| b | `beta_flag` 从不置 1 | ⏸ **意图有歧义**:只出只读报告,不写入 | — |
| c | AI 安全预检没接入发布 | ✅ 接入主题发布前试检查,fail-open | `chengyin.audit.o5c.ai-precheck.enabled`(超时 `...ai-precheck.timeout-ms`,默认 8000) |

---

## (a) 审核留痕:清点 + 统一读投影

### a.1 只读清点(按代码复盘,与 O5b 设计文档的「读法 B」一致)

| # | 机制 | 表 | 怎么挂到一个内容上 | 主要写入方 | 记了什么 |
|---|---|---|---|---|---|
| 1 | 统一审核 | `audit_task` + `audit_record` | `audit_task.(biz_type, biz_id)` → `audit_record.task_id` | `AuditTaskServiceImpl` | 动作(SUBMIT/APPROVE/REJECT/REVERT/ESCALATE/CANCEL…)、审核员、原因码/原因、快照 |
| 1′ | 审核裁决事件(与 1 双写) | `business_audit_event`(`domain_type='PLATFORM'`, `target_type='AUDIT_TASK'`) | `target_id` = 审核单 id | `AuditTaskServiceImpl.decisionAuditEvent` | `AUDIT_DECISION_*` / `AUDIT_DECISION_REVERTED`,before/after 只有 `{status, version}`;同表也是 review/revert 的幂等重放库 |
| 2 | 社区帖处置 | `community_post_enforcement` | `post_id` | `CommunityPostServiceImpl` | 处置类型、规则/原因码、操作人、生效区间、ACTIVE/EXPIRED/REVERSED |
| 3 | 社区举报 | `community_report_case` + `community_report_timeline` | `case.(target_type, target_id)`(CONTENT=帖子 id,ACTIVITY=活动 id …)→ `timeline.report_id` | `CommunityTrustServiceImpl` | 阶段流转、actor_type、对外说明 `public_note`、内部备注 `internal_note` |
| 4 | 官方活动 V2 | `official_event_audit_log` | `event_id` | `OfficialEventV2ServiceImpl` | 动作、操作人、摘要、before/after |

不算留痕的:`sys_oper_log` 只记 HTTP 操作(标题/URL/操作人);`business_audit_event` 的其它域(MERCHANT/CLUB)
是业务动作审计,不属于内容审核。

### a.2 统一读投影(本 PR)

- **不迁移、不删表、不加列、不加 DDL。** 新增只读 mapper `AuditTrailMapper.xml`,五条 SELECT,每个来源一条
  (各表 collation 不一样,`audit_record`/`audit_task`/`official_event_audit_log` 是 `utf8mb4_0900_ai_ci`,
  其余是库默认值;所以不用 `UNION`,免得撞 `Illegal mix of collations`)。合并排序放在
  `AuditTrailProjectionService` 里做。
- 路由:所有类型都读 1 + 1′;`COMMUNITY_POST` 再读 2,以及 3 里 target_type=CONTENT 的举报;
  `ACTIVITY` 再读 3 里 target_type=ACTIVITY 的举报;`OFFICIAL_EVENT` 再读 4。
- 排序:按事件时间升序;时间相同按来源固定顺序(AUDIT_RECORD → BUSINESS_AUDIT_EVENT → POST_ENFORCEMENT →
  REPORT_TIMELINE → OFFICIAL_EVENT_AUDIT_LOG),再按源表 id;时间为空的排最后。每个来源最多 200 条,总数最多 500 条。
- 投影字段:`source, sourceId, refId(审核单/举报单 id), action, operatorType, operatorId, reasonCode, reasonText, eventTime`。
  **不投影**:快照 JSON、before/after JSON、举报人身份(`reporter_member_id`)、举报 `internal_note`。
- 接口:`GET /business/audit-trail?bizType=TOPIC&bizId=123`,权限复用 `business:audit:list`(没有新权限点,
  RBAC 未改)。开关关时返回 404 + `errorCode=AUDIT_TRAIL_DISABLED`;参数不合法返回 400 + `AUDIT_TRAIL_INVALID`。
- 后台页面没做:需要的话在审核详情里加一个「完整留痕」抽屉,调上面的接口即可(下一步交给 owner 定)。

### a.3 已知局限(照实写)

- O5b 列出的留痕缺口这里不补(只读投影补不了写侧):`submit()` 建单不写 SUBMIT、`cancelOpenTemplateTasks`
  不写记录、取消原因塞在 `risk_level` 里。投影看到的就是表里有的。
- 帖文处置行有 `report_case_id`(`migration_community_enforcement_links_20261020.sql`),但不确定生产库是否已经执行,
  所以投影里帖文处置的 `refId` 留空,不依赖这一列。
- 举报对象是 MEMBER / MERCHANT / CLUB 的,`audit_task` 里没有对应的 bizType,这次不接。

---

## (b) `beta_flag` 从不置 1 —— 只出只读报告

### b.1 现状(master `a8504c39`)

- 唯一写 1 的地方是 `AuditTaskServiceImpl` 人工过审:`if (!alreadyPublished) t.setBetaFlag(1)`。
- 2026-10-20 起专业版主题改成**先发后审**:`CmsTopicServiceImpl.publishBeforeReview` 先置 `status=1`
  并发布路线快照(`published_config_version > 0`)。所以到人工过审时 `alreadyPublished` 恒为 true,
  `beta_flag` 实际上永远不会被置 1。
- 清 0 的只有 `POST /api/topic/beta/graduate`(作者本人,`WHERE beta_flag=1`)。**转正不留任何痕迹**
  (不写 audit_record / business_audit_event),所以从数据上分不出「转过正」和「从没进过 Beta」。
- 意图出处:迁移 `migration_20260821_topic_beta_zone.sql` 的注释是「pro 主题过审上架后先进 Beta,作者自决转正」;
  调研 `docs/research/questo-app-research-20260820.md` 写的是「审核通过后先进灰度可见的 beta 区收反馈,再转正式」。
  两处都以「过审」为触发点,但写这两处时还是先审后发。

### b.2 为什么停下

在先发后审下,「过审」和「上架」已经是两个不同的时刻。代码和文档只能说明意图是「pro 主题上架后有一段 Beta 期」,
却定不了从哪个时刻开始算。两种读法都说得通,而且对玩家可见的效果不同(Beta 标识出现在主题刚公开时,还是
过审之后),所以按任务要求,只出只读报告,不写入。

- **选项 A:首次上架即进 Beta**。在 `publishBeforeReview` 首次把 `status` 置 1 时(此前 `published_config_version`
  为空或 0)同时置 `beta_flag=1`。Beta = 后审期 + 试玩期,作者自己转正。好处是和「先发后审」的心智一致,
  玩家在审核员看过之前就能看到 Beta 标识。
- **选项 B:首次人工过审才进 Beta**。把过审处的条件 `!alreadyPublished` 换成「此前从未过审过」,
  例如审核单历史里没有 APPROVE。需要先定:转正后的主题再审一次,要不要重新进 Beta。又因为转正不留痕,
  落地 B 之前得先给 graduate 补一条留痕(`business_audit_event`),否则「从未进过 Beta」和「已转正」分不开,
  已转正的主题会被拉回 Beta。
- (O5a 里还列过选项 C「退役 Beta」:要小程序和后台一起改,这里不展开。)

### b.3 只读报告(在只读副本上跑,或走 chengyin-db-migrate 只读预检;不写库)

```sql
-- R1 现在的 Beta 主题(预期:先发后审上线后不再有新增)
SELECT COUNT(*) AS beta_now FROM cms_topic WHERE del_flag = 0 AND beta_flag = 1;

-- R2 选项 A 会覆盖的范围:已上架的专业版主题,按审核结论分组
SELECT check_status, COUNT(*) AS n
FROM cms_topic
WHERE del_flag = 0 AND status = 1 AND beta_flag = 0
  AND (publish_mode IS NULL OR publish_mode NOT IN ('simple', 'ai_simple'))
GROUP BY check_status;

-- R3 选项 B 会覆盖的范围:已人工过审(内容审核单有 APPROVE)但 beta_flag=0 的专业版主题。
--    无法剔除「已转正」的主题,因为 graduate 不留痕;这正是选 B 前要先补留痕的原因。
SELECT COUNT(DISTINCT t.id) AS approved_not_beta
FROM cms_topic t
JOIN audit_task a ON a.biz_type = 'TOPIC' AND a.biz_id = t.id
JOIN audit_record r ON r.task_id = a.id AND r.action = 'APPROVE'
WHERE t.del_flag = 0 AND t.beta_flag = 0
  AND (t.publish_mode IS NULL OR t.publish_mode NOT IN ('simple', 'ai_simple'));
```

两个选项都只影响新事件,不建议回填存量。等 owner 选定后另开改动,并加上开关。

---

## (c) AI 安全预检接入发布检查

### c.1 接在哪

- 发布检查路径是 `GET /api/topic/publish-check` → `TopicPublishCheckService.check`(作者本人或商家 Owner
  代管才能调,归属校验沿用原有的)。新增 `TopicAiPrecheckService`,通过可选 setter 注入。开关关时
  `check` 不追加任何项,也不调外部服务;老测试直接 `new` 出来时根本不注入,等同于关。
- 复用既有的 `IAiCreatorService.safetyPrecheck`(先过微信文本内容安全,再走 `AiGatewayService` 的 `safety_precheck` 场景)。
- **模板 / 帖子**:master 上没有对应的「发布前试检查」路径。模板发布的 WARN 通道(标题相似度)是 O5b 加的,还在 #536 里;
  社区帖发布是同步发帖,只过内容安全闸。这次只接主题,模板等 #536 合入后可以照同样的方式接到 `/api/template/publish`
  的 `warnings` 上。

### c.2 结果怎么映射(开关打开时)

| 情况 | 输出 | 是否影响正式发布 |
|---|---|---|
| 超时(默认 8s)、`RATE_LIMITED` 额度用尽、`UPSTREAM_UNAVAILABLE`、`OUTPUT_BLOCKED`、结果待确认、AI 返回解析失败、微信内容安全忙/不可用、线程池满 | WARN `AI_PRECHECK_SKIPPED` | 否(fail-open) |
| 主题没有归属创作者(官方/存量) | WARN `AI_PRECHECK_SKIPPED`,不调用(不替别人扣额度) | 否 |
| 微信文本内容安全命中(`INPUT_BLOCKED` 或 `content_security` 项) | error `AI_PRECHECK_CONTENT_BLOCKED` | 正式发布的文本内容安全闸本来就会拦同一份文字,和原有行为一致 |
| AI 模型给出的风险/缺口意见(任意 level) | WARN `AI_PRECHECK_RISK` | 否:正式发布闸不执行 AI 意见,所以试检查不能报成「发布会被拦」 |

- 超时用一个有界小线程池(1~4 线程,队列 16)做;超时后不 cancel,让网关按自己的路径处理额度预占和恢复。
- **不送 GPS / PII**:送给 provider 的只有主题名称、副标题、简介,以及启用节点的名称、简介(作为 task)。
  不送地址、经纬度、会员信息;文字里看起来像手机号(`1[3-9]` 开头的 11 位)或经纬度小数(4 位以上小数)的片段
  先替换成 `***` 再送出;每段截断到 500 字,节点最多 50 个。

### c.3 等 owner 拍板

1. **额度**:目前和独立接口一样,扣主题作者 gen 池里的 `safety_precheck` 次数。是不是应该给发布检查单开一个池,
   还是不计费,需要定(规则在 `AiGatewayService`)。
2. 是否把 AI 意见里 level=error 的项升级成 BLOCK(并且同时接进正式发布闸)。这次刻意没做。
3. 小程序端的 UI 改动,见下一节,本 PR 不改小程序。

### c.4 小程序端需要的 UI 改动(交给 owner)

- 主题编辑器里调用 `/api/topic/publish-check` 的发布前检查面板,需要认三个新 code:
  - `AI_PRECHECK_CONTENT_BLOCKED`(error):和其它 error 一样阻止「去发布」,提示修改文字;
  - `AI_PRECHECK_RISK`(warn):在 warn 区列出 AI 提示,可以忽略继续发布;
  - `AI_PRECHECK_SKIPPED`(warn):显示一行灰色小字「AI 预检暂不可用,已跳过」,不要做成弹窗。
- 开启后,发布检查最长可能多等 `timeout-ms`(默认 8 秒),加载态要能覆盖这段时间。
- 既有的独立按钮 `/api/ai/safety/precheck` 可以保留,也可以合并,由 owner 定。

---

## 新增 / 改动文件

- `chengyinhub-system/.../domain/vo/AuditTrailEntryVO.java`(新)
- `chengyinhub-system/.../mapper/AuditTrailMapper.java` + `resources/mapper/business/AuditTrailMapper.xml`(新,只有 SELECT)
- `chengyinhub-system/.../service/support/AuditTrailProjectionService.java`(新)
- `chengyinhub-admin/.../web/controller/business/AuditTrailController.java`(新,`@PreAuthorize business:audit:list`)
- `chengyinhub-system/.../service/support/TopicAiPrecheckService.java`(新)
- `chengyinhub-system/.../service/support/TopicPublishCheckService.java`(加了可选 setter,末尾追加 AI 预检结果)
- `ci/backend-test.sh`:`MYBATIS_ITS` 登记 `AuditTrailProjectionMySqlIT`

## 测试

- `AuditTrailProjectionServiceTest`(7):开关关时不查任何表;参数不合法;按类型选来源;合并顺序;总数上限。
- `AuditTrailProjectionMySqlIT`(4,MySQL 8 真 SQL,各表 collation 故意不一致):主题跨两张审核单的时间线;
  非审核域事件不混入;别的内容不串;帖子处置 + CONTENT 举报;只读 `public_note`、不出举报人;活动和官方活动各取自己的来源。
- `TopicAiPrecheckServiceTest`(9):默认关时零调用;超时 / 额度 / 上游 / 结果未知 / 异常都 fail-open 成 WARN;
  INPUT_BLOCKED 和内容安全命中给 error;AI 意见只给 WARN;内容安全不可用、解析失败给 WARN;
  无归属主题不调用;送出的内容里没有地址、经纬度、手机号。
- `TopicPublishCheckAiPrecheckWiringTest`(2):没注入或开关关时输出不变;开关开时追加。
- 本地 Maven 结果和 CI 结果见 PR 描述。
