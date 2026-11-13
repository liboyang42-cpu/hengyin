# fe-perk-entry 结果

分支 `fix/fe-perk-entry-20261113`。自由探索（mode 2）组合权益主题：一张票每店一项权益，店可无游戏。只改现有页面。

## 改动
1. `pages/play/index.js` `heroEnter`：自由探索 + 合作店 + 无玩法（`!hasGame && !hasTemplate && !hasAdvanced`，新 `_isPerkOnlyNode`）时跳 `pages/play/merchant/index`，不进玩法面板；有玩法的节点流程不变。`index.wxml` 主 CTA 与故事流底栏 CTA 对该类节点显示「使用本店权益」。
2. `pages/play/merchant/index.{js,wxml}`：`perkOnly`（mode 2、合作店、无玩法、无 `photoDesc` 拍照要求）且未核销时，主按钮直接是「出示核销码」（`openVerifyCode`），不经扫码到店与拍照。`openVerifyCode` 内 regId / merchantId / 授权同意检查与后端均未改；有拍照要求或有玩法的节点保持原链路。
3. `pages/play/index.js` `openRightsFromTools`：mode 2 且有 `regId`（非预览/mock）时打开现有「各站奖励」半屏，逐店列出权益名与 待使用 / 已使用（取自页内已载入节点的 `done` 与 `perk.name`，不虚构状态）；其他情况仍走 `goCouponWallet`。

## 后端核实（按章核销不要求到店照片）
- `CmsRegistrationServiceImpl.redeemExploreChapterAndRecord`（约 4189 行）：只校验支付/订单状态、活动归属、探索票类型与有效期、人工退款，未读 `selfReported` / 照片。
- `PerkLedgerServiceImpl.redeemChapter`（约 389 行）：按（票、章、商家）幂等与 entitlement CAS，无照片条件。
- `CmsRegistrationServiceImpl.recordImpliedShopArrival`（约 4396 行）：核销时补记到店，所以跳过扫码/拍照不会缺到店记录。

## 验证
- 单测：`node --test "tests/unit/**/*.test.js"` 全量 17548 例，改动前 3 例失败（2 例台账漂移、1 例 g21 CTA 变异负控），已修；`play-free-explore-shop-card-g21.test.js` 5/5 通过（新增 perkOnly 文案用例）。
- 台账：`build-action-ledger.js --write` 后 `--check` 通过（6035 controls）。
- `tests/lint/ui-integrity.js --warn-as-error`：UI-GATE-0 PASS。基线仅 U5 `pages/play/index.js:8918→8937` 行号位移，`u5Silent` keyDigest 按 `baselineKeyDigest` 重算，条数不变。
- `js-scope-lint`、`js-orphan-handler-lint`、`setdata-dead-field-lint` 通过。
- 未运行 `ci/xcx-check.sh` 与会写夹具的 selftest；未做模拟器截图（本环境无微信开发者工具）。

业主 2026-11-13 在 PR #817 评论：自由探索卡包页（卡包 → 逐张卡片 → 店铺与本店权益 → 到店三步）是已确认设计，不改；三处改动不合入，PR 已关闭，分支保留备查。

STATUS: PARTIAL - 业主否决，PR #817 已关闭未合并；分支仅供备查
