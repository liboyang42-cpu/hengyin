# 商家与俱乐部补全功能：GitHub 代码复用研究（2026-08-23；实现回读至 2026-08-26）

> 本文只负责“复用边界、许可证边界和本轮实际采用结果”。当前代码合同与发布 TODO 的唯一真源是 [功能补全说明与施工 TODO](../design/商家与俱乐部功能补全说明与施工TODO_20260823.md)；若表述冲突，以施工文档为准。

## 结论

城瘾不需要再引入一套活动平台。当前仓库已经有 JWT/RBAC、活动/票务、微信支付与退款、结算、IM、Quartz、POI、ZXing、扫码核销和轻量 CRM 数据底座。最合适的路线是：

1. **直接复用仓内能力**：Excel 导出、Quartz 通知任务、二维码生成/扫码、现有支付/退款/核销、RuoYi 权限和操作日志。
2. **本轮不新增第三方依赖**：jCasbin 仅保留为规则动态化候选；iCal4j 未获批，因此当前不支持任意 RRULE/ICS。
3. **只参考领域边界与状态机，不复制源码/数据库脚本/测试**：CRM 参考 Krayin，治理与审计参考 Flarum，会费边界参考 Kill Bill；实际代码均使用城瘾现有架构内实现。

### 本轮实施回读

- 没有新增第三方依赖或复制外部项目源码；实际采用的是许可证安全的“参考边界 + 城瘾现有服务内实现”。
- jCasbin 仍是未来规则动态化候选；当前固定角色用显式 service guard 更小、更可审计。
- iCal4j 未获依赖授权，因此没有伪称支持任意 RRULE/ICS；当前只实现 `ONCE/WEEKLY/CUSTOM_DATES`。
- Krayin、Flarum、Kill Bill 只影响领域边界与状态机拆分，不成为运行时组件；没有复制上游源码、SQL 或测试。
- 候补队列为城瘾仓内自行实现的小型事务状态机；可观察的 AGPL/GPL 项目只用于功能分层对照。
- 场次取消没有引入第三方活动系统：复用现有真实 activity 取消/退款、候补、角色和通知表，在一个本地事务中写 durable 状态，提交后再投递/重试。
- CRM 导出继续复用仓内 POI/私有文件能力；售后证据继续复用现有 OSS 上传与预签名 URL 能力，并把 `versionId` 固定到凭证记录、指定版本 GET/预签名与 SHA 回读，没有新增对象存储 SDK 或公共 URL 数据模型。上线前提是 bucket 已开启版本化；无 `versionId` 时入口失败关闭。
- 商家 Owner、俱乐部治理/通知/场次写入口采用仓内 MyBatis `FOR UPDATE` + 锁后新鲜鉴权；固定规则仍不需要 jCasbin。

最值得用的 5 个官方仓库：

| 优先级 | 仓库 | 最适合城瘾的部分 | 许可与兼容性 | 采用方式 |
|---|---|---|---|---|
| 1 | [apache/casbin-jcasbin](https://github.com/apache/casbin-jcasbin) | domain/tenant RBAC；把 `merchant:{id}`、`club:{id}`、`event:{id}` 作为授权域 | Apache-2.0；Java；当前源码编译 target 1.8；2026-08-12 仍活跃 | **未引入**；只有显式 service guard 无法维护时才另行评估 |
| 2 | [ical4j/ical4j](https://github.com/ical4j/ical4j) | RRULE 解析、重复活动展开、ICS 订阅/导出 | BSD-3-Clause；Java；4.x 需 Java 11，Java 8 只能评估 3.x | **未获批、未引入**；任意 RRULE/ICS 延后 |
| 3 | [flarum/framework](https://github.com/flarum/framework) | 审计日志、举报、封禁原因/期限、停权、通知 | MIT；PHP；2026-08-23 活跃 | **只参考领域边界**；城瘾自行实现，未引包/未复制 |
| 4 | [krayin/laravel-crm](https://github.com/krayin/laravel-crm) | 标签、联系人活动记录、保存筛选、数据表导出 | MIT；PHP/Laravel；2026-08-20 活跃 | **只参考模型分层**；城瘾自行实现，未引包/未复制 |
| 5 | [killbill/killbill](https://github.com/killbill/killbill) | 周期、订阅状态转换、账单/权益解耦 | Apache-2.0；Java；2026-08-22 活跃；当前体系以 Java 11 为主且体量巨大 | **仅参考状态机**；不要把平台或模块直接接进城瘾 |

## 研究口径与当前仓库去重

城瘾去重基线为 `github/master` `133c62f0d4e78db496efba071574550d5cc88f67`。本报告只使用官方 GitHub 仓库、仓库内 LICENSE、README、源码和官方 GitHub 元数据；未使用博客、聚合站或二手代码片段。GitHub `pushed_at` 是 2026-08-23 查询快照，只表示仓库最近有 push，不等于某个具体模块刚维护。

城瘾当前可直接复用的代码：

- Excel：`chengyinhub-common/src/main/java/com/chengyinhub/common/utils/poi/ExcelUtil.java`，根 POM 已固定 Apache POI `4.1.2`；已有大量 `exportExcel(response, ...)` 示例。
- 权限：`PermissionService`、`SecurityUtils.hasPermi`、`DataScopeAspect`、`sys_role/sys_role_menu`；俱乐部侧已有 `ClubMember.role` 和 Owner/Admin/Member 判断。
- CRM：已有 `MerchantCustomerProfile`、`MerchantCustomerTag`、`tagsJson`、分群计算和 `MerchantCrmServiceImpl`，缺的是商家可操作的标签绑定、备注、保存分群和触达链路，不是从零建 CRM。
- 通知：已有 `NotifyEvent`、`NotifyMessage`、`NotifyChannel`、`WxSubscribeChannel`、`BeforeStartNotifyTask` 和 Quartz。
- 二维码/现场：根 POM 已有 ZXing `3.5.2`，小程序已有扫码、重复/失败提示和后端核销；无需再引二维码库或重建签到台账。
- 审计：RuoYi 已有 `@Log`、`SysOperLog` 和导出；但面向成员治理的“案件—处罚—申诉—复核”仍需独立业务表，不能只靠后台操作日志。

## 1. 商家/俱乐部细粒度 RBAC

### 推荐

优先扩展现有权限服务，只有在动态规则增多时接入 [jCasbin](https://github.com/apache/casbin-jcasbin)。其 [domain RBAC 模型](https://github.com/apache/casbin-jcasbin/blob/master/examples/rbac_with_domains_model.conf)把请求表达为 `subject + domain + object + action`；[Enforcer](https://github.com/apache/casbin-jcasbin/blob/master/src/main/java/org/casbin/jcasbin/main/Enforcer.java)和 [Adapter](https://github.com/apache/casbin-jcasbin/blob/master/src/main/java/org/casbin/jcasbin/persist/Adapter.java)可嵌入 Java 服务。

城瘾冻结为分域模型，不建统一 assignment 表：

- 商家：`merchant_operator` 只存 `MERCHANT_MANAGER / MERCHANT_CHECKIN / MERCHANT_MARKETING / MERCHANT_FINANCE`；`MERCHANT_OWNER` 从商家主体关系运行时派生，禁止持久化。
- 俱乐部：`club_role_assignment` 只存 `CLUB_CO_OWNER / CLUB_OPERATOR / EVENT_LEAD / EVENT_CHECKIN`；`CLUB_OWNER` 从 `Club.memberId` 派生，`CLUB_MEMBER` 从正常成员关系派生，禁止持久化这两个派生角色。
- 两域共享权限决策接口和命名规则，但生命周期、Owner 真源、迁移方式不同，不塞进一张多态大表。
- 权限对象使用稳定字符串，例如 `member:list`、`event:update`、`checkin:write`、`finance:read`、`refund:review`。
- 每次鉴权仍必须先验证资源归属；Casbin/RBAC 不能代替 SQL 的 owner/scope 条件。
- 高风险写还必须按权威主体锁序后重验：商家 `merchant → current Owner/member → child rows`，俱乐部 `club → current assignment → domain rows`。加锁前的 access snapshot 只作快速预检，不能让已撤权 Owner/Co-owner/operator 继续写。

### 复用级别

- jCasbin：Apache-2.0、Java 8 兼容的**未来候选**；本轮未引入。
- [Indico EventRole](https://github.com/indico/indico/blob/master/indico/modules/events/models/roles.py)：MIT/Python，只用于对照“事件角色 + ACL”的分层；本轮未复制源码或测试。
- [Keycloak AdminEvent](https://github.com/keycloak/keycloak/blob/main/server-spi-private/src/main/java/org/keycloak/events/admin/AdminEvent.java)：Apache-2.0/Java 17，适合作授权变更的审计字段参考，**不要引入 Keycloak**。

### TODO

- [x] P0 权限矩阵、越权负控和角色分配表已实现。
- [x] P0 商家名单、财务、退款和核销已拆为最小权限。
- [x] P0 俱乐部按活动授权 `EVENT_LEAD/EVENT_CHECKIN`，活动结束/过期授权失效。
- [x] 退出、移除、封禁和 Owner 转让都撤销全部有效 assignment；新场次默认负责人落真实 `EVENT_LEAD`，Owner 转让受待审治理案件阻断。
- [x] 平台 Web 后台改岗与移除复用 canonical `CLUB_OPERATOR`/会员访问结束语义，不留平行权限真源。
- [x] 商家 Owner 写与资料更新、俱乐部 create/update/cancel、ban/unban、notification send/retry 都已补权威主体锁后重验；转让后的 stale actor 无写权限。
- [x] 当前继续用显式 service guard；没有为少量固定角色引入 jCasbin。

## 2. 候补队列与重复/系列活动

### 重复/系列活动

[iCal4j](https://github.com/ical4j/ical4j)实现 RFC 5545，核心复用点是 [RRule](https://github.com/ical4j/ical4j/blob/develop/src/main/java/net/fortuna/ical4j/model/property/RRule.java)、[RecurrenceSet](https://github.com/ical4j/ical4j/blob/develop/src/main/java/net/fortuna/ical4j/model/RecurrenceSet.java)和 recurrence transform 包。官方 README 明确：4.x 需 Java 11+，3.x 支持 Java 8+，因此城瘾不能跟随最新 4.x。

**这是未来候选，不是本轮已采用实现。** 本轮没有获批/引入 iCal4j，当前只支持 `ONCE/WEEKLY/CUSTOM_DATES`；`CUSTOM_RRULE`、ICS 导出/订阅和系列精细编辑延后。

建议表：

- `event_series(id, owner_type, owner_id, rrule, timezone, start_local, duration_min, until_at, status)`。
- 每次活动仍落现有活动/场次表，并记录 `series_id + occurrence_key`；不要查询时临时展开无限 RRULE。
- 只物化未来 60–90 天，通过现有 Quartz 滚动补齐；单次修改用 exception/override，不修改历史场次。

### 候补队列

未找到适合直接复制的宽松许可 Java 8 模块。建议自行实现：

- 状态：`WAITING → OFFERED → CONVERTED`，旁路为 `CANCELLED / EXPIRED`。
- 唯一约束：同一场次、同一用户只能有一条活动中的候补记录。
- 排序：`priority DESC, created_at ASC, id ASC`；默认所有用户 priority 相同即 FIFO。
- 容量释放后在事务中锁场次容量与首条候补，发放有截止时间的 offer；超时任务必须幂等。
- `OFFERED` 在支付窗口内占一个临时 hold，始终满足 `confirmed_count + active_offer_hold_count ≤ capacity`；支付成功转最终名额，过期/取消在事务内释放 hold。offer 不是票，不能被核销。

可观察但不可复制的成熟实现见 [Pretix waiting list model/service](https://github.com/pretix/pretix/tree/master/src/pretix/base/services) 和 [Hi.Events waitlist jobs/listeners](https://github.com/HiEventsDev/Hi.Events/tree/develop/backend/app/Jobs/Waitlist)。两者均为 AGPL 路线，详见许可证红线。

### TODO

- [x] P1 单场候补、取消释放、offer 到期、原子晋级和并发负控已实现。
- [x] P1 单次/每周/指定日期系列与未来场次物化已实现。
- [x] 晋级会跳过已离会的 stale `WAITING` 且不占库存；`OFFERED` quote/claim 都重新校验 active member。
- [x] P1 真实 occurrence 取消已复用既有 activity 取消/退款：取消前快照 REGISTERED/WAITLIST，随后同事务取消 activity/occurrence、撤销 EVENT assignment、终止 `WAITING/OFFERED/CLAIMED` 并释放 hold，再写两类 durable notification campaign/delivery/outbox。
- [x] 取消权限只给锁后仍具 exact `ACTIVITY_MANAGE` 的 Owner/Co-owner；EVENT-only 不可取消。相同 requestId 精确重放，已取消场次换新 requestId 也不能重复触发外部退款/通知。
- [x] 小程序入口位于既有 club event-ops，以真实 activityId、原因确认和独立 occurrence status 回读闭环；退款 `REFUND_REQUESTED` 与通知任务分别表达，不把受理回执当已退款。
- [x] 已核销父单按父单全量锁定并严格核对唯一付款人，恰好一次创建 durable manual refund case；后台具备受控凭证上传、服务端 SHA-256、登记/复核分离、退回重做/重开评估、版本 CAS 和详情终态回读，文案明确凭证复核不等于渠道到账。
- [ ] 任意 RRULE、ICS 及“仅本场/以后场次/整组”完整编辑语义仍待 iCal4j 依赖授权和产品合同。

## 3. CSV/Excel 导出

### 推荐

**不新增 Excel 库。** 直接复用仓内 `ExcelUtil<T>` 和 Apache POI `4.1.2`。名单导出使用专用 Export VO，只标注允许外发的字段，不要直接给 `MerchantCustomerProfile`/成员实体加全量 `@Excel`。

如果必须提供流式 CSV，可选 [apache/commons-csv](https://github.com/apache/commons-csv) 的 `CSVPrinter`，Apache-2.0、Java，2026-08-22 活跃；但它不是完成名单导出的前置条件。

安全要求：

- 手机默认脱敏；明文导出需单独权限、用途确认、限量和审计。
- 防 CSV/Excel 公式注入：以 `= + - @` 开头的用户输入按文本转义。
- 导出必须复用 merchant/club scope 校验，不能只靠前端隐藏按钮。
- 大名单异步生成、短时下载 URL、到期删除；下载动作也写审计。
- 授权与完成下载必须拆开：授权只校验当前 Owner/permission、task/token/file；HTTP 文件流写出成功后才在短事务中 CAS 首次下载并追加恰好一条审计。任一写入失败都回滚，不用 `authorize` 回执冒充已下载。
- 身份/门店 lineage 漂移时先失效数据库文件引用，私有文件只能在提交后删除；空读回、scope/count/status 矛盾均 fail-closed。

### TODO

- [x] P1 商家客户名单使用专用私有 Excel Export VO；没有复用公开 DTO。
- [x] P1 越权商家、敏感字段、公式注入、超量/过期下载负控已实现。
- [x] P1 文件流成功前不计下载；首次下载 CAS 与不可变审计同事务，审计失败负控可回滚；UI 对空任务/未知终态不乐观显示完成。
- [ ] 俱乐部成员/活动报名名单独立导出与 CSV 仍按真实运营需求立项；未引 Commons CSV。

## 4. CRM 标签、备注、分群

### 推荐

不要引完整 CRM。城瘾已有 profile/tag 表，补以下小模型：

- `merchant_customer_tag_assignment(merchant_id, member_id, tag_id, created_by, created_at)`。
- `merchant_customer_note(merchant_id, member_id, content, created_by, created_at, corrects_note_id, deleted_by, deleted_at)`；不原地编辑，纠错追加，删除仅隐藏。
- `merchant_customer_activity(type, ref_type, ref_id, occurred_at, amount, metadata_json)`，活动/报名/支付/核销/退款写入或聚合。
- `merchant_customer_segment(name, filter_json, owner_id)`，保存筛选而不是把动态分群结果静态复制一份。

`merchant_customer_tag_assignment` 是手工标签绑定真源；`merchant_customer_profile.tags_json` 只做可重建读缓存，禁止两边独立写入。

[Krayin Tag](https://github.com/krayin/laravel-crm/tree/2.2/packages/Webkul/Tag/src)、[Activity](https://github.com/krayin/laravel-crm/blob/2.2/packages/Webkul/Activity/src/Models/Activity.php)和 [SavedFilter](https://github.com/krayin/laravel-crm/blob/2.2/packages/Webkul/DataGrid/src/Models/SavedFilter.php)分别提供标签、时间线记录、保存分群的清晰边界。MIT 允许移植，但 PHP/Eloquent 不能直接接入 Java/MyBatis。

Krayin 曾公开修复 notes 存储型 XSS，因此城瘾备注必须作为纯文本或统一消毒后渲染，不能信任富文本。

### TODO

- [x] P1 标签定义/绑定与 owner scope 已接通。
- [x] P1 纯文本追加式备注、纠错/隐藏和审计已接通。
- [x] P1 保存分群覆盖复购、沉睡、未核销、退款、来源、时间窗和标签。
- [x] P1 分群触达经过同意/退订、数据库日频控和逐人回执，不复用 IM 群发绕过用户选择。

### 售后凭证边界（仓内复用）

- 不引新的文件系统或第三方 SDK；复用 `ApiCommonController`、`AppUploadService`、`UploadImagePolicy` 与现有 OSS 预签名能力。人工退款凭证要求上传返回非空 `versionId`，后续哈希、预览和复核只访问该指定版本；bucket 未开启版本化时不降级到 latest object。
- 小程序售后详情使用专用 `bizType=merchant_aftercare_evidence`，支持上传、预览、移除；提交只带 `evidenceKeys`。服务端拒绝客户端 `evidenceUrl`，只持久化符合专用路径合同的 object key，详情再按权限返回 5 分钟预览 URL。
- 证据与售后回应、不可变审计同事务；Finance 可补证但不能决定退款。该入口放在财务→售后详情，不进入私有 CRM 或公开评价。

## 5. 会费与续费模型

### 推荐

只参考 [Kill Bill](https://github.com/killbill/killbill) 的订阅边界，不引入其平台。关键源码包括 [SubscriptionBase](https://github.com/killbill/killbill/blob/master/api/src/main/java/org/killbill/billing/subscription/api/SubscriptionBase.java)、[Subscription events](https://github.com/killbill/killbill/tree/master/subscription/src/main/java/org/killbill/billing/subscription/api/user)和 subscription DAO/model。它把“计划、订阅、状态变化、账单、支付”分开，适合城瘾避免把会费直接塞进 `club_member`。

最小模型：

- `club_membership_plan(club_id, name, period_unit, period_count, amount, benefits_json, refund_policy, status)`。
- `club_membership_term(club_id, member_id, plan_id, start_at, end_at, status, source_order_id)`。
- `club_membership_invoice(term_id, amount, due_at, paid_at, order_id, refund_id, status)`。
- 状态：`PENDING / ACTIVE / GRACE / PAST_DUE / EXPIRED / CANCELLED / REFUNDED`；每次变化追加 event/audit，不覆盖历史。

继续复用城瘾现有微信支付、退款和订单，不新建支付网关。没有微信委托扣款/自动续费正式授权前，只做“到期提醒 + 用户主动续费”，不要把定时创建订单冒充自动续费。

### TODO

- [x] P1 周期会费、主动续费、到期/宽限、退款审核和权益校验已实现，默认 feature flag 关闭。
- [x] P1 权益只认有效 term，不以历史支付永久放行。
- [x] 续付恢复按稳定 `outTradeNo` query-first；微信 `SUCCESS` 严格验证后走回调状态机并读回 `PAID`，CAS 冲突与外部异常分开表达。
- [x] 新续费先提交稳定 `PAYING` 意图、冻结政策快照与审计，再调用微信；`resume/close/callback` 均以稳定 `outTradeNo` query-first 恢复，不再存在“微信已受理但本地账单意图尚未提交”的窗口。
- [x] 访问结束对 PAYING invoice 写 `resume_blocked_at` 并清空 pay params；第二事务附着 prepay、resume 与迟到 callback 都受该围栏约束，退会/移除/封禁/解散后不会恢复权益。
- [ ] `migration_club_role_membership_resume_fence_20260826.sql` 尚未生产执行/新连接回读；在此之前会费 feature flag 与收费发布继续 No-Go。
- [ ] P2 自动续费须另行完成微信产品、协议、解约和失败重试合规设计。

## 6. 通知与日历 RRULE

### 推荐

- RRULE/ICS：iCal4j 3.x 只是未来候选；本轮未获批、未引入。
- 定时调度：复用现有 Quartz 和 `BeforeStartNotifyTask`。
- 通道：复用 `NotifyEvent/NotifyMessage/WxSubscribeChannel`，不要引入 Novu 等另一套通知平台。
- 新增 `notification_preference`、`notification_delivery` 和幂等键 `event + recipient + occurrence + template + channel`。

系列活动的 RRULE 是“生成场次的规则”，提醒是“针对已生成场次的投递”；二者不要由同一个 Quartz cron 字符串兼任。

### TODO

- [x] P1 活动变更、候补 offer/过期、报名/出勤受众已进入独立站内通知活动与 delivery。
- [x] P1 每条站内通知保存 delivery/outbox、CAS 与失败重试，不重复轰炸。
- [x] P1 取消通知使用取消前 REGISTERED/WAITLIST durable 快照；内部 `cancel-r-`/`cancel-w-` requestId 前缀由公共通知 API 保留，普通 actor 不能伪造快照资格绕过受众复核。
- [x] send/retry 在短事务中先锁 club、锁后重验当前 club/event assignment 后才 create/claim；dispatch 只在汇总更新恰好一行且权威回读 id/scope/count/status 精确一致时完成，不对 null readback 本地合成终态。
- [ ] 标准 ICS/订阅地址与微信订阅消息未实现；站内回执不冒充第三方送达。

## 7. 治理：封禁、申诉、审计日志

### 推荐

[Flarum Audit](https://github.com/flarum/framework/tree/2.x/extensions/audit)是本次用于对照的 permissive 领域模型；下述只是研究结论，本轮未复制其源码或 migration：

- [audit_log migration](https://github.com/flarum/framework/blob/2.x/extensions/audit/migrations/2026_06_09_000001_create_audit_log_table.php)记录 actor、client、IP、action、payload、created_at，故意不对 actor 建外键以保留已删除账号的责任链，down migration 也不直接删除日志。
- [AuditLogger](https://github.com/flarum/framework/blob/2.x/extensions/audit/src/AuditLogger.php)统一注册 action 并追加日志。
- [Suspend migration](https://github.com/flarum/framework/blob/2.x/extensions/suspend/migrations/2021_10_27_000000_add_suspend_reason_and_message.php)区分内部原因与用户可见消息；[RevokeAccessFromSuspendedUsers](https://github.com/flarum/framework/blob/2.x/extensions/suspend/src/RevokeAccessFromSuspendedUsers.php)在封禁期撤销权限。
- [Flags](https://github.com/flarum/framework/tree/2.x/extensions/flags/src)把举报记录、可见范围和删除动作拆开。

城瘾建议表：

- `governance_case(target_type, target_id, reporter_id, category, description, evidence_json, status, assigned_to)`。
- `governance_sanction(case_id, subject_id, scope_type, scope_id, action, start_at, end_at, reason_internal, message_public)`。
- `governance_appeal(case_id, appellant_id, reason, evidence_json, status, reviewer_id, decision)`。
- `business_audit_event(actor_id, actor_role, client, ip, action, target_type, target_id, before_json, after_json, created_at)`，追加写、受控保留。

主理人只能做俱乐部范围的移除/临时封禁；举报俱乐部、主理人或平台处罚的申诉必须进入平台复核，不能由被举报主理人自审。

### TODO

- [x] P0 成员封禁、再申请策略、举报、平台复核、申诉和处罚到期已实现。
- [x] P0 处罚/解封/角色变更/导出/售后意见进入业务审计。
- [x] P0 审计写入只追加，查询受平台/领域权限约束，不提供普通删除。

## 8. 现场签到与二维码

### 推荐

**不引任何新库。** 城瘾已有 [zxing/zxing](https://github.com/zxing/zxing) `3.5.2`（Apache-2.0，Java，2026-08-21 活跃）和小程序扫码/后端核销。需要补的是运营和权限，不是二维码编码：

- 按活动授权签到员，只能看必要名单字段，不能看财务或全量 CRM。
- 支持扫码 + 手工搜索兜底、撤销/更正、未到/no-show 和操作原因。
- 核销记录包含 operator、device/session、入口、时间、结果、原记录/更正记录；禁止覆盖原行。
- 离线签到如以后需要，应使用预授权短时凭据与增量同步；不能把长期有效票数据整库下发到设备。

参考 [Indico registrations](https://github.com/indico/indico/blob/master/indico/modules/events/registration/models/registrations.py) 的报名/签到字段边界即可；Indico 为 MIT/Python，不能直接接包。

### TODO

- [x] P0 现有核销/名册增加活动级负责人/签到员和有限时更正审计。
- [x] P1 增加 no-show、候补转正与报名状态联动。
- [ ] P2 真实业务证明需要真机扫码、重复码负控、断网/重试和生产回读；模拟器截图不算。

## 候选仓库分级总表

### 未来可评估的依赖（本轮均未引入）

| 仓库 | 模块 | License | 兼容性与决定 |
|---|---|---|---|
| [apache/casbin-jcasbin](https://github.com/apache/casbin-jcasbin) | `Enforcer`、domain RBAC model、`Adapter` | Apache-2.0 | Java 8 可用；只在显式 guard 失控时另行评估，本轮未引入 |
| [ical4j/ical4j](https://github.com/ical4j/ical4j) | 3.x `RRule`/recurrence/ICS | BSD-3-Clause | 未获批；若未来立项只能评估 3.x，4.x 不兼容 Java 8 |
| [apache/commons-csv](https://github.com/apache/commons-csv) | `CSVPrinter` | Apache-2.0 | Excel 已复用 POI；本轮无 CSV 必要性、未引入 |

### 可参考的小模型/状态边界（本轮无源码复制）

| 仓库 | 可参考点 | License | 限制 |
|---|---|---|---|
| [flarum/framework](https://github.com/flarum/framework) | audit schema/logger、suspend reason/message、flag workflow | MIT | PHP；本轮只参考边界，未复制表/状态机/代码 |
| [krayin/laravel-crm](https://github.com/krayin/laravel-crm) | Tag、Activity、SavedFilter、DataGrid export | MIT | PHP/Laravel；本轮只参考业务边界，备注按纯文本防 XSS |
| [indico/indico](https://github.com/indico/indico) | EventRole/ACL、registration/check-in 模型 | MIT | Python/SQLAlchemy；本轮只参考模型与用例边界 |
| [keycloak/keycloak](https://github.com/keycloak/keycloak) | AdminEvent 字段与事件存储/过期策略 | Apache-2.0 | Java 17/完整 IAM；本轮只参考审计字段思路 |

### 仅参考架构

| 仓库 | 参考点 | 原因 |
|---|---|---|
| [killbill/killbill](https://github.com/killbill/killbill) | subscription event、entitlement、invoice/payment 解耦 | Apache-2.0 但体量大、当前 Java 11 体系，与 MyBatis/现有微信支付不匹配 |
| [pretix/pretix](https://github.com/pretix/pretix) | waiting list、membership、check-in、export、team permission | **AGPL-3.0 + Additional Terms**；只看公开状态机，不复制实现 |
| [HiEventsDev/Hi.Events](https://github.com/HiEventsDev/Hi.Events) | waitlist offer/expiry、recurring event、check-in list、role/export | **AGPL-3.0 + attribution additional terms**；只看功能分层，不复制实现 |
| [alfio-event/alf.io](https://github.com/alfio-event/alf.io) | reservation/subscription/check-in | **GPL-3.0**；且 Java 17/PostgreSQL，与城瘾不匹配 |

### 不建议

- 不建议把 Keycloak、Kill Bill、Indico、Krayin 或 Flarum作为新服务部署，只为补几个表和状态机会制造双账号、双权限、双支付或数据同步问题。
- 不建议引 Hi.Events/Pretix/Alf.io 的源码、SQL、测试或翻译后端算法；“改写语言”不会自动消除著作权与 copyleft 义务。
- 不建议再引 POI、ZXing、Quartz 的另一版本；仓库已有可用依赖，升级应是独立兼容/安全任务。
- 不建议以 GitHub star 数或 README 的功能清单替代代码审计、许可证审查和城瘾负控。

## 许可证边界

- **Apache-2.0**：可商用、修改、分发；分发时保留许可证、版权/归属声明，若仓库有 NOTICE 也要一并处理，修改文件应标明改动。
- **MIT**：可商用和移植，但必须保留版权声明和许可文本。
- **BSD-3-Clause**：可商用；保留版权、条件与免责声明，不能用作者/贡献者名义为产品背书。iCal4j 官方 README 还明确建议随 JAR 分发其 LICENSE。
- **GPL-3.0 / AGPL-3.0**：本报告对城瘾采取保守边界，**不直接复制、不翻译复制、不链接/嵌入后端实现**。AGPL 对网络服务还有源码提供义务；Pretix 和 Hi.Events 另有 additional terms，应视为红线候选，若未来真要采用必须先做正式法务评估或购买商业许可。

## 当前实现映射与仍延后边界

1. **已落代码候选**：显式商家/俱乐部 guard 与锁后重验、统一的 member identity mutex→club→fresh access 锁序、角色/商家团队/会费计划/帖子置顶/签到更正的稳定意图与 exact replay、CRM/严格下载审计、售后凭证 object-key 上传、自建候补事务状态机、真实场次取消主事务与两类 durable 通知快照、已核销父单 durable 人工退款责任单及双人复核、主动续费/权益期及恢复围栏、活动级签到角色与更正/no-show。
2. **未引入**：jCasbin、iCal4j、Commons CSV、Krayin、Flarum、Kill Bill 都不是当前运行时组件。
3. **明确延后**：任意 `CUSTOM_RRULE`/ICS、微信订阅消息、自动续费、真实预约、俱乐部文件/投票/留存分析和外部现场硬件。
4. **UI 放置**：售后凭证留在商家财务→售后详情，CRM 动作留在客户列表/详情，取消本场留在俱乐部管理→项目工具→既有 event-ops；现成 `components/cy/club-card/**` 继续复用，和 `pages/talent/**` 均为零修改，不另设计俱乐部卡片。
5. **迁移候选**：截至当前快照为 18 个 pre-deploy schema、3 个生产已执行但台账缺失的 immutable job history、1 个 Quartz pause repair，共 22 份 SQL；第 18 个 schema 是人工退款责任表，数量、依赖和状态以施工文档的最终工作树 manifest 为准。

任何第三方真正进入 POM 前，都要在施工文档约定的 adoption 清单中冻结 Maven coordinate、精确版本、上游 tag/commit、Java 8 构建证据、传递依赖许可证和 LICENSE/NOTICE 归档；本文的分支链接只用于研究，不能直接充当上线证据。

每项完成的共同验收线：UI 入口、后端 owner/scope 校验、状态回读、失败路径、并发/重复请求和独立负控同时存在；按钮可见或接口 200 均不算闭环完成。

本研究只说明“为什么复用这些仓内能力以及为什么不引外部整套系统”，不构成发布证明。人工退款责任表等数据库 migration、OSS bucket 版本化与真实 `versionId` 回读、Quartz 止血、PR/CI/merge、后端/Web 部署、微信上传、真机支付/退款/扫码/通知、真实角色账号矩阵和最终稳定树真实数据验收仍全部是 release TODO / No-Go；本文不声称任何新能力已经线上。

## 官方来源索引

- [jCasbin repository / Apache-2.0](https://github.com/apache/casbin-jcasbin)
- [iCal4j repository / BSD-3-Clause / Java version matrix](https://github.com/ical4j/ical4j)
- [Apache Commons CSV / Apache-2.0](https://github.com/apache/commons-csv)
- [Apache POI source](https://github.com/apache/poi)
- [ZXing / Apache-2.0](https://github.com/zxing/zxing)
- [Flarum framework / MIT](https://github.com/flarum/framework)
- [Krayin CRM / MIT](https://github.com/krayin/laravel-crm)
- [Indico / MIT](https://github.com/indico/indico)
- [Keycloak / Apache-2.0](https://github.com/keycloak/keycloak)
- [Kill Bill / Apache-2.0](https://github.com/killbill/killbill)
- [Pretix LICENSE / AGPL-3.0 with Additional Terms](https://github.com/pretix/pretix/blob/master/LICENSE)
- [Hi.Events repository / AGPL-3.0 with additional terms](https://github.com/HiEventsDev/Hi.Events)
- [Alf.io / GPL-3.0](https://github.com/alfio-event/alf.io)
