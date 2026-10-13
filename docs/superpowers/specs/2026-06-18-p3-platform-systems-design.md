# 城瘾 Questify P3 平台化系统设计

日期：2026-06-18  
状态：待评审  
范围：P3 八个系统的完整设计，不包含首页改版，不改变主题和活动发布流程及业务规则。

## 1. 目标

P3 的目标不是增加八个孤立页面，而是把现有积分、内容、模板、商家、订单、优惠券、余额、提现和审核能力升级为可运营、可追踪、可扩展的平台系统。

完成标准：

1. 每个系统具有明确的数据模型、领域服务、API、权限和用户入口。
2. 涉及状态变化的操作具有事务、幂等、操作记录和异常恢复能力。
3. 管理后台可以配置规则、处理任务、查询结果和导出数据。
4. 小程序只展示当前用户有权访问的数据，不依赖前端判断实现权限。
5. 关键服务具有自动化测试，关键用户流程具有可重复验证步骤。
6. 数据库变更全部通过增量迁移脚本完成，不直接修改历史备份。

## 2. 现状判断

### 2.1 可复用基础

- 用户：`ums_member` 已有角色、等级、积分、余额和邀请关系。
- 积分：已有积分规则、积分明细和积分商城。
- 内容：已有主题、活动、模板、广场贴文和评论。
- 商业：已有订单、报名、优惠券、核销、余额明细和提现申请。
- 创作者：已有个人模板、公共模板、模板评分和引用次数字段。
- 推荐：主题已有后台推荐标记，系统已有浏览记录。
- 审核：主题、活动、模板、商家、俱乐部等对象各自具有审核状态。
- 后台：已有 Spring Boot + RuoYi Vue 管理体系和 RBAC 权限。

### 2.2 核心缺口

- 行为数据没有统一事件语义，无法可靠计算增长漏斗。
- 积分发放散落在控制器和业务代码中，没有统一成长规则执行器。
- 推荐主要依赖人工推荐，缺少用户兴趣、位置和行为评分。
- 审核状态分散，没有统一队列、审核记录、风险等级和处理时效。
- 提现审核直接修改通用实体，缺少严格状态机、驳回返还和幂等保护。
- 商家只能看到汇总数字，不能形成客户、转化、复购和优惠券效果视图。
- 创作者评分依赖后台人工字段，缺少可解释的质量指标和收益账本。
- IP 世界观没有独立内容模型，无法组织城市、角色、章节、任务和合作方。

## 3. 总体架构

采用模块化单体，不拆分微服务。保留现有 Maven 多模块、Spring Boot、MyBatis、Vue 2 和微信小程序技术栈。

新增领域模块仍位于 `chengyinhub-system`：

- `analytics`：事件、漏斗、指标。
- `audit`：统一审核任务和记录。
- `growth`：成长账户、规则、任务、徽章。
- `recommendation`：画像、特征和推荐评分。
- `creator`：创作者画像、质量指标、排行和收益。
- `crm`：商家客户聚合、标签和转化指标。
- `settlement`：结算批次、结算明细和提现状态机。
- `world`：IP 世界、城市章节、角色和世界任务。

`chengyinhub-admin` 提供：

- `/api/*`：小程序接口。
- `/business/*`：管理后台接口。
- 定时任务：漏斗聚合、推荐评分、创作者指标、CRM 指标和结算对账。

`chengyinhub-ui` 提供运营管理页面。  
`chengyinhub-xcx` 提供用户可见入口，首页保持不变。

## 4. 统一事件与数据流

### 4.1 领域事件

所有关键业务成功后写入统一事件：

- `search_submit`
- `content_view`
- `content_like`
- `content_favorite`
- `content_share`
- `signup_start`
- `signup_submit`
- `payment_success`
- `coupon_receive`
- `coupon_verify`
- `topic_publish_success`
- `activity_publish_success`
- `template_reuse`
- `mission_complete`
- `withdrawal_submit`

事件只记录已完成事实，不以事件代替业务事务。业务表是事实源，事件表用于分析、成长、推荐和运营。

### 4.2 幂等

事件唯一键：

`event_name + member_id + biz_type + biz_id + idempotency_key`

支付、核销、结算、提现等事件必须由服务端生成幂等键。普通浏览事件允许重复，但按会话和最短间隔去重。

### 4.3 隐私

- 不保存精确定位轨迹。
- 推荐仅保存城市、分类偏好和聚合行为分值。
- 设备标识使用随机会话 ID，不保存硬件永久标识。
- 管理后台导出用户数据需要独立权限。

## 5. 数据模型

### 5.1 埋点与漏斗

#### `biz_event_log`

- `id`
- `event_name`
- `member_id`
- `anonymous_id`
- `session_id`
- `source`
- `page_path`
- `biz_type`
- `biz_id`
- `city_code`
- `properties_json`
- `idempotency_key`
- `occurred_at`
- `create_time`

索引：

- `(event_name, occurred_at)`
- `(member_id, occurred_at)`
- `(biz_type, biz_id, occurred_at)`
- 唯一索引 `(event_name, member_id, idempotency_key)`

#### `biz_funnel_daily`

- `stat_date`
- `funnel_code`
- `step_code`
- `source`
- `city_code`
- `user_count`
- `event_count`
- `conversion_rate`

### 5.2 统一审核

#### `audit_task`

- `id`
- `biz_type`
- `biz_id`
- `submitter_id`
- `source_type`
- `risk_level`
- `status`
- `current_assignee`
- `submitted_at`
- `deadline_at`
- `version`

状态：

- `0 PENDING`
- `1 APPROVED`
- `2 REJECTED`
- `3 ESCALATED`
- `4 CANCELLED`

唯一索引 `(biz_type, biz_id, version)`。

#### `audit_record`

- `task_id`
- `action`
- `operator_id`
- `reason_code`
- `reason_text`
- `snapshot_json`
- `create_time`

现有对象的 `status` 继续作为业务展示状态，统一审核服务负责同步，不直接删除旧字段。

### 5.3 玩家成长

#### `member_growth`

- `member_id`
- `experience`
- `level_code`
- `mission_points`
- `consecutive_days`
- `last_active_date`
- `version`

#### `growth_rule`

- `rule_code`
- `event_name`
- `reward_experience`
- `reward_points`
- `daily_limit`
- `total_limit`
- `cooldown_seconds`
- `status`
- `effective_from`
- `effective_to`

#### `growth_mission`

- `mission_code`
- `name`
- `description`
- `mission_type`
- `target_event`
- `target_count`
- `period_type`
- `reward_json`
- `status`

#### `growth_mission_progress`

- `member_id`
- `mission_code`
- `period_key`
- `progress`
- `completed`
- `claimed`
- `version`

#### `growth_badge` / `member_badge`

徽章定义与用户获得记录分离，获得记录保存来源事件和获得时间。

### 5.4 IP 世界观

#### `ip_world`

- `id`
- `name`
- `subtitle`
- `description`
- `cover_url`
- `partner_name`
- `partner_logo`
- `status`
- `sort_order`

#### `ip_city_chapter`

- `world_id`
- `city_code`
- `name`
- `story`
- `cover_url`
- `unlock_rule_json`
- `status`

#### `ip_character`

- `world_id`
- `name`
- `title`
- `description`
- `avatar_url`
- `attributes_json`
- `status`

#### `ip_quest`

- `world_id`
- `chapter_id`
- `character_id`
- `owner_type`
- `owner_id`
- `story_intro`
- `reward_json`
- `sort_order`
- `status`

`owner_type/owner_id` 关联现有主题、活动或模板，不复制原业务内容。

### 5.5 创作者工具

#### `creator_profile`

- `member_id`
- `creator_level`
- `quality_score`
- `reuse_count`
- `completion_rate`
- `positive_rate`
- `income_total`
- `ranking_score`
- `updated_at`

#### `creator_metric_daily`

- `stat_date`
- `member_id`
- `publish_count`
- `approved_count`
- `reuse_count`
- `signup_count`
- `completion_count`
- `favorite_count`
- `report_count`
- `quality_score`

#### `creator_income_detail`

- `member_id`
- `source_type`
- `source_id`
- `amount`
- `status`
- `settlement_detail_id`
- `create_time`

质量评分使用可解释指标，不允许后台直接覆盖最终分数；后台只能配置权重和人工风险扣分。

### 5.6 商家 CRM

#### `merchant_customer_profile`

- `merchant_id`
- `member_id`
- `first_interaction_at`
- `last_interaction_at`
- `signup_count`
- `paid_order_count`
- `paid_amount`
- `coupon_receive_count`
- `coupon_verify_count`
- `last_activity_id`
- `lifecycle_stage`
- `updated_at`

#### `merchant_customer_tag`

- `merchant_id`
- `member_id`
- `tag_code`
- `tag_source`
- `create_time`

只聚合与当前商家实际发生报名、订单、核销或关注关系的用户。

### 5.7 财务结算

#### `settlement_batch`

- `batch_no`
- `period_start`
- `period_end`
- `status`
- `total_amount`
- `detail_count`
- `started_at`
- `completed_at`
- `error_message`

#### `settlement_detail`

- `batch_id`
- `beneficiary_id`
- `biz_type`
- `biz_id`
- `income_type`
- `gross_amount`
- `platform_fee`
- `net_amount`
- `status`
- `idempotency_key`
- `settled_at`

唯一索引 `idempotency_key`。

#### 提现状态机

- `0 PENDING`
- `1 APPROVED`
- `2 REJECTED_REFUNDED`
- `3 PAYING`
- `4 PAID`
- `5 PAY_FAILED`
- `6 CANCELLED_REFUNDED`

规则：

1. 申请时冻结余额，不再直接视为最终支出。
2. 审核通过进入支付中。
3. 驳回或取消必须在同一事务中返还冻结金额并写余额明细。
4. 支付回调必须幂等。
5. 后台不能通过通用编辑接口任意跳转状态。

### 5.8 推荐

#### `member_interest_profile`

- `member_id`
- `city_code`
- `category_weights_json`
- `creator_weights_json`
- `updated_at`

#### `recommendation_score_daily`

- `stat_date`
- `member_id`
- `candidate_type`
- `candidate_id`
- `score`
- `reason_codes`
- `rank_position`

评分组成：

- 城市匹配 25%
- 分类兴趣 25%
- 新鲜度 15%
- 质量与完成率 15%
- 社交/创作者偏好 10%
- 运营权重 10%

冷启动使用城市、热门分类、质量分和运营推荐。推荐接口必须有通用列表降级。

## 6. API 设计

### 6.1 小程序 API

- `POST /api/analytics/events/batch`
- `POST /api/growth/profile`
- `POST /api/growth/missions`
- `POST /api/growth/claim`
- `POST /api/growth/badges`
- `POST /api/world/list`
- `POST /api/world/info`
- `POST /api/world/quests`
- `POST /api/creator/dashboard`
- `POST /api/creator/ranking`
- `POST /api/merchant/crm/summary`
- `POST /api/merchant/crm/customers`
- `POST /api/merchant/crm/customer-info`
- `POST /api/settlement/summary`
- `POST /api/settlement/details`
- `POST /api/recommendation/list`

### 6.2 管理后台 API

- `/business/analytics/*`
- `/business/audit/*`
- `/business/growth/rules/*`
- `/business/growth/missions/*`
- `/business/growth/badges/*`
- `/business/world/*`
- `/business/creator/*`
- `/business/crm/*`
- `/business/settlement/*`
- `/business/recommendation/*`

所有写接口使用独立权限，例如：

- `business:audit:review`
- `business:growth:rule:edit`
- `business:world:edit`
- `business:settlement:approve`
- `business:recommendation:config`

## 7. 页面与入口

首页 `pages/index/*` 保持不变。

新增小程序分包 `subpackageP3`：

- `pages/growth/index`
- `pages/growth/missions`
- `pages/growth/badges`
- `pages/world/index`
- `pages/world/detail`
- `pages/creator/dashboard`
- `pages/creator/ranking`
- `pages/merchant-crm/index`
- `pages/merchant-crm/customers`
- `pages/settlement/index`

入口：

- 会员中心增加成长、徽章和世界任务入口。
- 任务玩法库增加创作者榜单入口。
- 商家中心增加客户运营入口。
- 资产中心增加结算批次和明细入口。

不增加新的主 Tab，不改变主题和活动发布步骤。

## 8. 管理后台

新增页面：

- 增长数据：漏斗、渠道、城市和趋势。
- 审核工作台：待审、风险、超时、历史和批量分配。
- 成长规则：规则、任务、徽章和等级阈值。
- IP 世界：世界、城市章节、角色、任务绑定。
- 创作者运营：质量指标、排行、风险和收益。
- 商家 CRM：客户阶段、复购、优惠券转化和导出。
- 财务结算：批次、明细、异常、提现审核和对账。
- 推荐配置：权重、冷启动和人工运营位。

## 9. 安全与权限

1. 所有小程序 API 从登录令牌获取 `memberId`，不接受客户端指定当前用户。
2. 商家 CRM 接口先校验当前用户对应的审核通过商家。
3. 创作者收益只能查询本人。
4. 提现审核、结算重试和审核处理使用后台 RBAC 权限。
5. 审核任务保存提交快照，避免内容被修改后无法追溯。
6. JSON 扩展字段限制长度并使用结构化 DTO 校验。
7. 事件批量接口限制批次数量、单条大小和允许事件白名单。
8. 导出接口记录操作日志并进行数据脱敏。

## 10. 错误处理

- 数据采集失败不能阻断用户主流程；客户端进入短队列并有限重试。
- 成长奖励失败由幂等事件重放，不在客户端重复发奖。
- 推荐服务失败回退到已审核、已上架的通用列表。
- 审核同步失败保留任务为异常状态，不直接放行内容。
- 结算单条失败不回滚整个批次，失败明细可安全重试。
- 提现状态异常必须拒绝操作并记录审计日志。

## 11. 实施顺序

### 阶段 A：数据与安全基础

1. 统一事件表、采集 API、客户端队列。
2. 漏斗聚合任务和后台数据看板。
3. 统一审核任务、审核记录和现有内容适配器。
4. 推荐画像和评分表。

### 阶段 B：玩家与内容

1. 成长账户、规则执行器、任务和徽章。
2. IP 世界、章节、角色和任务绑定。
3. 创作者指标、质量评分、排行和收益视图。

### 阶段 C：商业闭环

1. 商家客户聚合、分群、复购和优惠券转化。
2. 结算批次与明细。
3. 提现状态机、驳回返还、支付结果和对账。

### 阶段 D：运营与验收

1. 完成管理后台页面和 RBAC 菜单迁移。
2. 完成小程序入口和各角色权限验证。
3. 完成单元、集成、前端构建和关键流程测试。
4. 更新审计报告、路线图和上线迁移手册。

## 12. 测试策略

### 后端

- 事件白名单、批量限制和幂等。
- 成长规则日限额、总限额、并发和重复事件。
- 审核状态转换和越权。
- 推荐过滤未审核、下架和已删除内容。
- CRM 商家数据隔离。
- 结算幂等、失败重试和金额守恒。
- 提现申请、审核通过、驳回返还、重复回调和越权。

### 小程序

- 未登录、普通玩家、俱乐部和商家入口差异。
- 网络失败、空数据和重复点击。
- 埋点失败不影响搜索、报名、支付、发布和分享。
- 成长奖励展示与服务端结果一致。

### 管理后台

- 权限菜单可见性和接口权限。
- 审核、规则、IP、结算等关键表单校验。
- 数据看板空状态、筛选和导出。

## 13. P3 完成判定

以下八项必须全部达到“数据库 + 服务 + API + 页面 + 权限 + 测试”后才能标记完成：

1. IP 世界观专区。
2. 玩家成长体系。
3. 创作者工具。
4. 商家 CRM。
5. 财务、结算和提现闭环。
6. 埋点和增长漏斗。
7. 推荐系统。
8. 统一审核后台。

只新增表、只新增页面、只返回静态数据或只通过编译均不构成完成。

