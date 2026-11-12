# 俱乐部个人收款绑定页（承接 PR #809 遗留项）

## 做了什么

### 后端（沿用 #809 的 `ApiClubController` 与 `EcommercePersonalReceiverService`，未新增服务类）
- `GET /api/club/personal-receiver/status`：不再因平台开关关闭而拒绝。返回 `receiverState`（UNBOUND / CONSENTED / VERIFIED）、`payoutEnabled`（平台开关）、`paused` 与 `pauseReason`（开关已开但绑定未经微信确认时为 `PERSONAL_BINDING_REQUIRED`）、`canUnbind`。仅俱乐部主可读，只认登录人。
- `POST /api/club/personal-receiver/bind`：开关关闭时也可绑定——只记录同意（状态 `CONSENTED`），不调用微信；开关开启后再次绑定时经微信确认升级为 `VERIFIED`。OpenID 仍只取登录会话与一次性 `jsCode` 换得的身份，客户端无法提交；必须带 `consent=true`。
- `POST /api/club/personal-receiver/unbind`（新增）：仅俱乐部主；存在处理中（PLANNED / PROCESSING）的个人分账行时拒绝，提示“还有收款正在处理，处理完成后才能解除绑定”；删除与在途检查在同一条 SQL 内完成，避免检查后并发写入。
- 分账规划只认 `VERIFIED`，所以 `CONSENTED` 在开关开启后会按原规则暂停（`PERSONAL_BINDING_REQUIRED`），不会改道或转余额。
- Mapper：`EcomPayoutMapper` 只新增语句（`insertConsented`、`promoteConsentedToVerified`、`countPersonalPayoutsInFlight`、`deleteReceiverIfIdle`），既有语句 SQL 文本未改。
- 无新增迁移（沿用 `ecom_personal_receiver`，`state` 列为 VARCHAR(16)，`CONSENTED` 在范围内）。
- Java 测试：`EcommercePersonalReceiverServiceTest` 将“开关关闭一律拒绝”改为新契约并新增开关关闭绑定、CONSENTED 升级、暂停状态、解除（含在途阻断与归属）用例；`ApiClubPersonalReceiverRoutesTest` 新增 unbind 用例。按仓库规定未在本机构建，交 CI。

### 小程序
- 新页 `pages/club/payout-receiver/index`（`pages/club` 分包，深色玩家域；结构参照商家收款账户页，不进主包、不进 pages/play）。
- 三种状态：未绑定 / 已绑定 / 已暂停（带原因）。开关未开时以“收款：平台开通后开始”状态行表达，无说明性文字。
- 绑定：同意确认后取一次性 `wx.login` code 提交；解除：走现有 `cy-danger-confirm`（新增 `club.personal-receiver.unbind`，可逆档）。
- 入口：俱乐部结算页（主理人可见的“个人收款”行）；我的资产 > 更多 > “个人收款”（`mine=1`，取本人首个俱乐部）。
- 单测：`tests/unit/club-payout-receiver-page-contract.test.js`。

## 验证
- `node --test tests/unit/club-payout-receiver-page-contract.test.js`：5 项通过。
- `build-route-matrix.js --json` 后 `--check`：PASS（172 路由）。
- `build-action-ledger.js --write` 后 `--check`：PASS（6035 控件）。
- danger-confirm 门禁：PASS（53 个调用点）。`unbind` 不含门禁识别的危险动词，故不进登记表（登记会被判“源码里找不到”）。

STATUS: PARTIAL - 全量小程序单测与 UI-GATE-0 本机仍在运行，结果待 CI 核对；PR 检查待跟进
