# 工坊「已购 / 付费安装」客户端→后端合同表（BE-11 前置）

> **2026-11-09 用户确认 2A：** 本轮已授权建立付费玩法模板的真实购买、许可、私有草稿安装与退款撤销后端，App 团队负责支付接入。下文“不得新增订单/许可/安装表”是权威事实缺失时禁止用 facade 伪造权益的历史限制，不再表示本轮禁止实现真实权威；仍须优先复用现有订单、支付与退款机制，只有经核实缺失的持久事实才增补。客户端旧枚举和状态硬断言需要在 UI 唯一清单登记兼容升级，不能直接改旧接口语义或伪造已付款。所有历史 UNKNOWN 均保留为历史证据，新增实现必须另给源码、事务与渠道验证证据。

状态：**服务端权威 UNKNOWN（未在本公开仓、也不在只读参考快照里找到实现）**。
本文件只登记客户端已经写死的线上形状，用来告诉后续实现方「必须接住什么」；它不授权、
不新建订单/权益，也不代表任何接口已经可用。

## 1. 结论（先看这段）

| 项 | 结论 | 证据 |
|---|---|---|
| 客户端合同 | 存在，且默认关闭（需要单独审核的 approval + 显式注入） | `ios-main-integration/Core/WorkshopPurchasedService.swift`、`WorkshopPaidInstallService.swift`、`WorkshopPaidInstalledTextService.swift` |
| 公开仓后端实现 | **不存在** | 全仓 `*.java` 搜 `workshop` 0 命中 |
| 只读参考快照后端 | **不存在** | `/Users/arlan/城瘾app/outputs/latest-source-reconciliation-20261106/complete-latest/backend` 下 `*.java` 搜 `workshop` 0 命中 |
| 小程序端调用 | **不存在** | `chengyinhub-xcx` 搜 `api/workshop` 0 命中 |
| 客户端自述的运行期状态 | `contentUseStatus=PAID_INSTALL_AUTHORITY_UNAVAILABLE`、`purchaseActionStatus=CHANNEL_APPROVAL_REQUIRED` | `WorkshopPurchasedContracts.swift` 解码硬断言 |
| 可动服务端现状 | **无**。已有的真实后端能力只有模板采用链：`ApiTemplateController.adoptDraft`（`/api/template/adopt` 族）→ `TemplatePublishServiceImpl.backfillAdoptedSecrets`，以及主题 `/use` | 公开仓源码 |

因此：**不得**新增工坊订单表、许可表、安装表、支付回调或 `paid=true` 假权益来"补齐"这批接口。
真正缺的是原始后台权威（订单行 / 许可行 / 安装单 / 渠道审批），不是本仓的又一个 facade。

## 2. 客户端已经写死的接口（必须原样满足，不得改名）

所有请求都是 `AuthRequestBuilder.makeFormRequest`，响应统一信封 `{code:Int, data:<payload>}`；
`data` 之外的键由客户端严格校验（多键 = malformed）。

### 2.1 已购许可只读

| 方法 | 路径 | 请求体（form） | 响应 `data` |
|---|---|---|---|
| 列表 | `api/workshop/purchased/list` | 空，或 `before_order_line_id=<int64>`（>0） | `schema, scope, items[], nextBeforeOrderLineId?, hasMore, checkedAt` |
| 详情 | `api/workshop/purchased/detail` | `license_id=<string>` | `schema, scope, item, checkedAt` |

`items` 最多 50 条；`hasMore == (nextBeforeOrderLineId != null)`；`nextBeforeOrderLineId` 必须小于请求值。
`item` 的 22 个键全必填（`WorkshopPurchasedItem`）：`licenseId, moduleId, purchasedVersionId, contentHash,
acquiredAt, termsVersion, termsHash, status, commercialUse, adaptation, translation, allowedRegions,
themeLimit, merchantLimit, runLimit, acquisition, buyerKind, useDuration, updates, redistribution,
contentUseStatus, purchaseActionStatus`。

固定取值（客户端硬断言，后端不得自行发明）：

- `status` ∈ `ACTIVE | SUSPENDED | REVOKED`
- `commercialUse` / `translation` ∈ `ALLOWED | PROHIBITED`
- `adaptation` ∈ `BIND_RESOURCES_ONLY | LOCAL_ADAPTATION`
- `acquisition = "PAID"`（这批是**已购**语义，免费领取是另一条链）
- `buyerKind = "INDIVIDUAL"`（受益人 = 购买人；团体/赠送走别的合同）
- `useDuration = "PERPETUAL_PURCHASED_VERSION"`、`updates = "EXACT_PURCHASED_VERSION"`、`redistribution = "PROHIBITED"`
- `contentUseStatus = "PAID_INSTALL_AUTHORITY_UNAVAILABLE"`（当前权威不存在，这正是本文件的状态）
- `purchaseActionStatus = "CHANNEL_APPROVAL_REQUIRED"`（下单/支付需渠道审批，客户端不做）
- `Limit = {unlimited:Bool, maximum:Int64}`，`maximum >= 0`，且 `unlimited` 时 `maximum == 0`
- `contentHash` / `termsHash` 必须是十六进制摘要形状；`acquiredAt` / `checkedAt` 必须是时间戳串

### 2.2 付费安装（六态状态机）

| 方法 | 路径 | 请求体（form / JSON） |
|---|---|---|
| 可安装目标 | `api/workshop/purchased/install/targets` | `license_id`, 可选 `before_draft_id` |
| 安装历史 | `api/workshop/purchased/install/history` | `license_id`, 可选 `before_command_key` |
| 安装状态 | `api/workshop/purchased/install/status` | `request_id`（**仅**此一个参数） |
| 提交安装 | `api/workshop/purchased/install/submit` | `WorkshopPaidInstallCommand` JSON（`w18-paid-private-text-install-command-v1`） |

`WorkshopPaidInstallCommand` 字段：`schema, requestId, licenseId, moduleId, purchasedVersionId, contentHash,
termsHash, targetDraftId, targetRevision, businessType, targetPayloadHash, planningRegion, commercialUse`。
提交响应 / 状态的 `schema = "w18-paid-install-outcome-v1"`，字段：
`schema, requestId, state, licenseId, moduleId, purchasedVersionId, contentHash, termsHash, targetDraftId,
targetRevision, targetPayloadHash, businessType, planningRegion, commercialUse, mode, holdDeadline,
ownedDraftId?, installedAt?, professionalTemplateStatus, published, executable`。

`state ∈ NOT_FOUND | PREPARED | RESERVED | EXPIRED | CANCELLED | FAILED | INSTALLED_PRIVATE_DRAFT`；
`NOT_FOUND` 只允许 `schema/requestId/state` 三个键。

固定取值（客户端硬断言）：

- `mode = "UNRESOLVED"`、`professionalTemplateStatus = "MATERIALIZATION_REQUIRED"`
- `published = false`、`executable = false`（安装 ≠ 发布，也不产生可执行副本）
- 只有 `INSTALLED_PRIVATE_DRAFT` 才允许带 `ownedDraftId>0` + `installedAt`；其余状态两者必须缺省
- 同 `requestId` 重放必须返回**命令字节等价**的同一结果（幂等），客户端逐字节比对

### 2.3 已安装私有文本（受保护正文读）

| 方法 | 路径 | 请求体 |
|---|---|---|
| 正文详情 | `api/workshop/purchased/installed-text/detail` | `license_id` + 安装回执引用 |

正文只允许九个字段：`title（必填非空）, description, ruleInstructions, merchantGuide,
questionName, questionAnswer, hint1, hint2, answerReveal`，外加 `schema = "w18-member-text-v1"` 与
`bindings = {}`（必须为空对象）；组件态额外允许 `validationMethod = 1`。正文上限 262144 字节
（组件态 266240）。客户端**不**把它当作者草稿，也不因此获得发布权。

## 3. 需要原始权威才能实现的部分（全部 UNKNOWN）

| 需要的事实 | 为什么本仓给不出 | 需要的原始权威 |
|---|---|---|
| 许可行（谁付了钱、买到哪个 `purchasedVersionId`、`contentHash`、条款哈希） | 无工坊订单/许可表，也无对应支付回调 | 原后台的订单行 + 许可行（含条款快照与退款字段） |
| 购买受益人 vs 付款人 | 现有营销合同只有 gift 受益人语义，没有工坊许可归属 | 订单行的 `buyer` 与 `beneficiary` 两列 |
| 安装单六态与 `holdDeadline` 预留 | 无安装单表、无预留/过期状态机 | 原后台安装单（含 `requestId` 唯一键与 CAS 版本） |
| 提交幂等（同 `requestId` 返回字节等价命令） | 无命令回执存储 | 安装单上的 `commandPayload` + `commandKey`（哈希）唯一约束 |
| 退款/撤销对安装的回收 | 没有许可→安装的可逆事实 | 许可状态变更（`REVOKED`）与退款冲正流水 |
| 目标草稿归属（`targetDraftId/targetRevision/targetPayloadHash`） | 本地只有模板采用链，不是工坊安装目标 | 原后台的"可安装目标"合同（业务类型 + 草稿版本 + 载荷哈希） |

## 4. 允许的最小接入方式（等原始权威到位后）

1. 先只做只读：`list/detail` 由原后台许可行投影，字段逐个对齐第 2.1 节；`contentUseStatus` 保持
   `PAID_INSTALL_AUTHORITY_UNAVAILABLE` 直到安装权威也落地（不得提前改成可用）。
2. `targets/history/status` 只读投影安装单；`submit` 必须在安装单表上做唯一键 + CAS，且先校验
   许可 `ACTIVE`、条款哈希、目标载荷哈希一致。
3. 安装成功只允许写**私有草稿**（`ownedDraftId`）；发布仍走既有 `TemplatePublishServiceImpl` /
   主题 `/use`，不得由安装结果直接置 `published/executable=true`。
4. 任何情况下不得用客户端传入的 `paid`、`commercialUse` 或价格自证权益；钱的事实只认订单/许可行。

## 5. 本文件的边界

- 只是合同登记，不是运行时校验器，也没有接入任何调用方。
- 未运行 iOS/Mini、未连库、未做任何支付或线下审批；因此第 2 节是"客户端期望"，不是"服务端已验证"。
- 私有仓库路径只在此处作为**只读证据**引用，未复制其历史、原始数据或密钥。
