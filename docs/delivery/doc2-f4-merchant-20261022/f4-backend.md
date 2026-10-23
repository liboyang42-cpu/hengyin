# Doc2 F4 商家支撑（仅后端）· 2026-10-22

- 分支：`feature/doc2-f4-merchant-20261022`（基于 master）。页面由 owner 自己建，本 PR 不改小程序。
- 总开关：sys_config 里的 `merchant.desk.f4.enabled`，**默认 `false`**。
  - 关闭时，所有 `/api/merchant/desk/*` 端点都返回 `404 + errorCode=F4_DISABLED`，不会读写任何新表。
  - 所以新 jar 可以比迁移先上线。打开开关之前，必须先执行本 PR 的 4 个建表迁移。
- 新迁移共 5 个（**一个都没执行**）。每个都带列指纹守卫和回读断言，依赖已在 `ci/migration-dependencies.json` 声明：

| 迁移 | 内容 |
|---|---|
| `migration_merchant_desk_receipt_20261022.sql` | 写操作回执：`(owner_member_id, request_id)` 唯一 |
| `migration_merchant_redemption_anchor_20261022.sql` | 核销锚点：地图点 + 半径 30..500 m + BSSID 的 SHA-256。每家门店一行，带乐观锁 |
| `migration_pms_sku_booking_20261022.sql` | `pms_sku_booking_rule`（商家可写）和 `pms_sku_capacity_slot`（由 App/后台生成，小程序只读） |
| `migration_verification_review_evidence_20261022.sql` | 核销复核的商家补充材料 |
| `migration_20261022_merchant_desk_switch_seed.sql` | 落种开关，值为 `false`。只在键缺失时插入 |

## 统一约定

- **鉴权顺序**：先校验登录，再看开关，最后调 `IMerchantAccessService.require(principal, 权限码)`。
  - 权限码全部复用现有的商家权限码，**没有新增 RBAC**。
- **归属**：只从服务端解析出的访问上下文里取，客户端传来的归属一律不认。
  - 门店、商品、SKU、锚点按 `mms_merchant.id` 归属。
  - 核销、复核、对账、回执按 Owner 的 `memberId` 归属。
  - `MerchantDeskMapperSqlTest` 逐条检查每条 SQL 的 WHERE 是否带了归属，并禁止写资金表和履约表。负控已实测。
- **写操作幂等**：请求体必须带 `requestId`（8–64 位，字母、数字、`_`、`-`）。
  - 同一个键、同样的内容：返回原回执，`replayed=true`。
  - 同一个键、不同的内容：返回 `409 F4_IDEMPOTENCY_CONFLICT`。
  - 同一个键并发提交：返回 `409 F4_IN_FLIGHT`。
- **结果不明（UNKNOWN）时**：调 `POST /api/merchant/desk/receipt {requestId}` 读回。
  - 返回 `APPLIED`：已落地，带 `targetType` 和 `targetId`。
  - 返回 `NOT_FOUND`：没有落地。写入和回执在同一个事务里，所以用同一个键重试是安全的。
- **错误返回**：用 `AjaxResult(code, msg)`，再附加一个 `errorCode` 字段。
  - 常见错误码：`F4_DISABLED`、`F4_BAD_REQUEST`、`F4_NOT_OWNER`（不存在和不属于本商家返回同一个码）、`F4_VERSION_CONFLICT`、`F4_PRODUCT_LIVE_LOCKED`、`F4_REVIEW_CLOSED`、`F4_EVIDENCE_LIMIT`、`F4_STORE_NOT_ACTIVE`、`F4_ANCHOR_TOO_FAR`、`F4_PRODUCT_NOT_LIVE`。

## 端点（全部 POST，前缀 `/api/merchant/desk`）

| 页面锚点 | 端点 | 权限 | 说明 |
|---|---|---|---|
| 门店列表 | `/stores` | BASIC_READ | 当前一个账号对应一家门店，数组里只有 1 项。带营业时间解析结果和 `editEndpoints`：地图选点走 `/api/merchant/decor/save`，营业时间走 `/api/merchant/update`（复用现有写入口，不另做一套） |
| 锚点 | `/anchor`、`/anchor/save`、`/anchor/check` | BASIC_READ / PROFILE_WRITE / VERIFY | save 需要 `anchorLat`、`anchorLng`、`radiusM`、`wifiBssids[]`、`expectedVersion`（首次保存不传），锚点必须在门店位置 1 km 以内。check 只返回判定结果，`enforced=false` |
| 商品/SKU | `/products`、`/products/detail`、`/products/save`、`/products/delist`、`/products/sku/save` | PROJECT_MANAGE | 新建或修改后状态都回到 0（待审核），由后台审核上架。上架中的商品和 SKU 不能改，要先下架。sku/save 包含 `bookable`、`slotMinutes`（15 的倍数，范围 15..480）、`capacityPerSlot`（0..1000）、`ruleVersion` |
| 容量 | `/products/sku/slots` | PROJECT_MANAGE | 只读，参数 `skuId`、`fromDate`、`days`（不超过 14） |
| 今日到店 | `/arrivals/today` | VERIFY_RECORD_READ | 两部分：今日已核销列表，以及各生效供给章节上还没核销的权益数（只到章节级，不到人） |
| 核销台 | `/redeem/resolve` `{code, mode: SCAN\|MANUAL\|OCR}`、`/redeem/ocr-candidates` `{text}` | VERIFY | 只归类码并返回表单，**不做核销**。表单里的 `submitEndpoint` 指向现有的在线核销端点，`offline=false` |
| 复核结论 | `/verdicts`、`/verdicts/detail`、`/verdicts/evidence` | VERIFY_RECORD_READ / VERIFY | 只能给本商家 `status=0` 的复核单补材料，每单最多 10 次。补材料不改变结论 |
| 对账 | `/reconciliation`、`/reconciliation/claims` | FINANCE_READ | 只读：四个口径加上口径外的路由 |
| 冻结 | `/frozen` | FINANCE_READ | 只读：冻结中的批次，以及运营填写的冻结原因 |
| 指标字典 | `/metrics/dictionary` | BASIC_READ | 只解释 `/api/merchant/dashboard` 已经下发的字段口径 |
| 回执 | `/receipt` | BASIC_READ | 结果不明时读回 |
| 供给申报 | 不新增端点 | — | 已有接口：`/api/coop/perks/attach`（供给申报）、`/api/coop/offer/circle-supply/*` |

核销台的路由规则和 `chengyinhub-xcx/utils/verification-scan.js` 一一对应：

- `v1.*.group_*`：走 groupcode/redeem。
- `v1.*.activity|topic`：走 scan_dynamic_code。
- `v1.*.citynode_*`：返回 `F4_WRONG_DESK`，并给出据点页地址。
- `cq1.*`：走 coupon/verification，`scope=MERCHANT`。
- JSON 格式的旧码：走 scan_qr_code，`type` 原样透传。

新增的规则有三条：

- **手输或 OCR 得到的 `VER…` 旧码**：表单里有一个必填的 `type` 下拉框。服务端不会去查单子替商家补 type，因为 scan_qr_code 的 type 闸要核对的正是“声称的类型”。
- **`poi1.*` 据点张贴码**：这是节点任务码，不是玩家的核销凭证。一律返回 `F4_NODE_TASK_CODE_NOT_VOUCHER`；OCR 时排除，只给出被排除的个数。
- **OCR 结果**：最多返回 5 个候选，并给出打码后的显示值。

## 没做的（按计划划给别处，或尚未批准）

- 批量生成时段、上传发票、导出 CSV：属于 App/后台，小程序只读结果。
- 预约占用和释放（`capacity_reserved`）：预约模型尚未批准，见 survey C5-1。
- payout 和 `merchant_payable` 计提：属于 phase 2，未批准，一行都没碰。
- 自由探索售卖：仍由 F3 管（phase 1，默认关）。本 PR 只读 `merchant_chapter_offer`、`registration_entitlement`、`redemption`，不写。
- 锚点接入核销拦截：没有接。现有核销端点一行未改。

## 与现状的冲突（需要 owner 选）

**C-F4-1｜“商家名下多门店”在现有代码里不存在。** 现在一个商家账号就是一行 `mms_merchant`，`MerchantAccessContext` 也只有一个 `merchantId`。

- A（已实现）：`/stores` 返回只有一项的数组，契约已经是列表形状，以后扩展不用改端上。
- B：新建 `mms_store` 表并改访问上下文。这属于跨模块的 RBAC 和归属改动，需要先问。

**C-F4-2｜`pms_product` 是积分商城商品表。** 售价是 int 积分，经 `UmsMemberCartServiceImpl` 扣积分下单。用它承载“可预约服务商品”，口径不一致。

- A（已实现，按任务卡复用）：商家侧可以增改，但一律落为待审核 0，由后台审核把关。预约规则和容量放在独立的新表里，不改 `pms_product` / `pms_sku`。
- B：另建服务商品表，现金或人头费口径。会牵扯到定价和资金，需要先批。

**C-F4-3｜“今日到店”拿不到人。** 权益只到章节维度，一个章节可能有多家商家承接，没有预约或到店时间。

- A（已实现）：今日已核销列表，加上章节级的未核销权益计数。
- B：等预约模型（C5-1）落地后，再按时段列到人。

**C-F4-4｜“四个对账口径”和代码对不上。** DDL 注释里 `settlement_route` 只列了 `NONE / CHAPTER_OFFER / COOP_ORDER / REGISTRATION_SPLIT` 四个，但 `ClubDirectMerchantReceivableService` 已经在写 `CLUB_DIRECT`。

- A（已实现）：四个口径固定展示；`CLUB_DIRECT` 等口径外的路由放进 `outsideFourBases` 单列，不吞掉，也不并入四个口径。
- B：把 `CLUB_DIRECT` 定为第五个口径，或并入某一个口径。需要 owner 定口径。

**C-F4-5｜CHAPTER_OFFER 口径的金额只在 `merchant_payable` 里。** 存量里有 `claim_id IS NULL` 的行（survey C5-3）。

- 现状：对账总览把这类行单独列为 `payableRowsWithoutClaim` / `payableNetWithoutClaim`，只读展示，不做任何修复。

**C-F4-6｜核销锚点只是配置，没有拦截。**

- A（已实现）：只做配置和自检。
- B：接入核销端点拦截（shadow 或 enforce）。这会改变现有核销行为和准入规则，需要先问。

## 验证

- 新增 44 条单测，本地全部通过：`MerchantDeskCodePolicyTest`、`RedemptionAnchorPolicyTest`、`MerchantMetricDictionaryTest`、`MerchantDeskMapperSqlTest`、`MerchantDeskServiceImplTest`、`ApiMerchantDeskControllerTest`。
- 负控：从 `selectReview` 里删掉 `verifier_member_id` 条件后，`MerchantDeskMapperSqlTest.everyStatementIsOwnerScoped` 变红。
- 本地门禁全部通过：`ci/migration-layout-check.sh`、`ci/migration-safety-check.sh`、`ci/dup_create_table_gate.py`、`ci/role_permission_contract.py`。
- 全量 Java 测试由 GitHub CI 跑。
