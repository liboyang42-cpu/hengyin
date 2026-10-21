# W05 商家 AI 草稿 → 生成 → 人审发布 → 公开读取

基准 `debae9efca58a68544176da49f3bfd17029caec5`。本文件是本包后端合同。iOS 接线不在本包。

## 真源（复用，不绕过）

- 生成只调用 `AiGatewayService.generate`。成本与安全在网关内完成，`cost_ref` 记 `AiGatewayResult.traceId`（`AiGenerationLog` 的追踪键）。本包不建用量表，不直连模型。
- 本店权限只认 `IMerchantAccessService.require(principal, merchant:project:manage)` 的当前上下文。`merchantId` 取上下文，不取客户端。
- 商品只读 `IPmsProductService` / `IPmsSkuService`。发布不改价格、库存、订单、券、结算。
- 主题归属只读 `ICmsTopicService.selectCmsTopicById` 的 `memberId`。主题负责人不能确认本店事实。
- 审核只调用 `IAuditTaskService.submit` 与 `review`。客户端字段 `approved` 一律拒绝。
- 公开事实从本包 release 投影进 `MerchantNpcFactsBuilder` 与店铺现场记忆。不另建 NPC 档案表。

## 身份

- 本店事实确认：当前本店 `PROJECT_MANAGE`。主题负责人身份不够。
- 主题内容批准：该 `themeId` 的 `CmsTopic.memberId`。本店员工身份不够。
- 两项都要有 AuditTask，主体是服务端解析出的操作者。

## 持久

- `w05_authoring_job` 唯一 `(owner_id, request_id)`。同键不同 payload fingerprint 冲突，不覆盖。
- 先提交 `CLAIMED`，事务成功后才调用网关。已存在的 job 只回读。`CLAIMED` 对调用方的 outcome 是 `UNKNOWN`，不得再生成。
- 网关结果不明或本地落库失败：job 改为 `UNKNOWN` 并可按原键查回，不换 requestId。
- `w05_authoring_revision` 只插入。保存使用 `expectedVersion`，CAS 失败回读最新版本。已写过的版本行不更新正文。
- 新版本使该草稿上仍有效的批准失效。已发布 release 的快照不改。
- 发布在一个事务内核对权限、商品归属、hash、审核单状态与可见性策略，然后插入不可变快照。带主题的发布必须同时有本店事实确认和主题内容批准。没有主题时只能发布不含剧情字段的本店事实，公开绑定是 `STORE`。旧 release 行不改写快照。撤回只改状态。
- 列表里出现草稿或 `CLAIMED` 发布意图，不是本次发布成功。

## 公开与环境

- 公开读取必须同时命中 merchant、theme、release、run、binding，且状态为 `PUBLISHED`。撤回后公开读取拒绝。
- 投影只含已批准白名单字段。订单、客户、成本、价格、库存不进提示词。
- `w05_environment_snapshot` 记录 provider、source、observed_at、expires_at、timezone、accuracy、branch。未批准的环境 provider 不产生天气。时段分支只来自已批准分支、策略时区、商家 `businessTime` / `businessStatus` 与时钟。闭店或无法解析营业时间时不编造时段。上午分支到了下午不再作为可执行分支。危险天气且没有已批准安全分支则退出。环境或生成失败不删除已发布主题主线。

## 配置

`MerchantAuthoringPolicy` 无生产 Bean。模型、费用批准、模板、两类审核开关、公开可见性、时区、环境 provider、天气安全分支未设置时，对应动作拒绝。测试只注入 fixture 策略。

## 接口

`/api/merchant/authoring`：job 生成与按 requestId 回读、取消、草稿列表与保存、事实确认、内容批准、审核回读、发布、撤回、公开读取、环境、主题主线。私有响应 `Cache-Control: no-store`。

候选表：`sql/migration_merchant_authoring_w05_candidate.sql`。不在本包执行。
