# C10-backend · 工坊 / 模组 / H5 独占适配器

Issue：https://github.com/liboyang42-cpu/chengyin-remote-ops/issues/5 领取组 **C10**  
仓库：`github.com/liboyang42-cpu/hengyin` 仅写本仓（不写旧 `chengyin`、不改小程序）  
Base：`origin/master` `2ec68899099a1e250df0949a8383bf85b5d3ffe0`  
分支：`cursor/c10-workshop-h5-adapters-e504`  
隔离测试，不真实 IAP / 支付 / 发布登记。qmod / H5 SDK 原文未到 → `WAITING_SOURCE`，不是假成功。

## 1. 白名单（实际写入）

生产（仅新目录 `chengyinhub-system/.../service/workshop/`）：

- `WorkshopOriginalRequestBinder.java` `ccc0d447bbc143feebd684af3644ef38bec0c4dfad7186b34eefc31866b0e0c3`
- `WorkshopModuleImportGate.java` `6d2f9aa265f0be3465b4f9a107ef857e96601cc4a775724aa6be29640c385e1d`
- `WorkshopPackageVersionPublish.java` `b2bba3df5de1292b307e09297c62160c95686d4b245963c97869fca79a2b2701`
- `WorkshopInstallUpgradeRollback.java` `d838164d2c58bccfcd33e899e7af4319b299b1f8947ec67263e613c5bcd979ac`
- `WorkshopRunServerVerification.java` `ac76adecede399a5657f31f945bdecd6b787388a365dc0109597fe37ec1e7849`
- `H5SessionCapabilityGate.java` `a6919ad779e87cb2eff943caed2d6a9dbb2c29ecc4ba18790091f65977d5a944`
- `H5AnswerProtection.java` `1c467e222a862670a6e87f2600d5083a46951a5e83dd048dfefa3f81e4b3806d`

测试（`chengyinhub-admin/src/test/java/.../service/workshop/`）：

- `WorkshopModuleImportGateTest.java`
- `WorkshopPackageVersionPublishTest.java`
- `WorkshopInstallUpgradeRollbackTest.java`
- `WorkshopRunServerVerificationTest.java`
- `H5SessionCapabilityGateTest.java`
- `H5AnswerProtectionTest.java`
- `C10FirstJourneyIsolationTest.java`

未改：公共作者发布 / 安装登记 / Runtime、Controller / Mapper / migration、资金 writer、小程序、旧 `chengyin` 仓、`AppSession`、pbx。无生产 DDL。无共享文件覆写。

## 2. 复用的既有真源（只读）

| 既有 | 本适配器如何消费 |
|---|---|
| iOS `Core/Workshop*` 30 文件（owned/purchased/paid-install 私有草稿） | 不改；本包是后端独占门禁，不是第二套 studio |
| W18 workshop paid marketplace DO_NOT_PORT | 不恢复 IAP / 微信支付虚拟道具 |
| C05 `OfflinePackOperation.inspect` 禁答案/隐藏坐标 | `WorkshopModuleImportGate.inspect` 同样拒绝 |
| Discovery 禁任意 banner H5 | `H5SessionCapabilityGate` 同样拒绝任意 URL |

## 3. 首个可跑旅程（隔离测试）

`C10FirstJourneyIsolationTest#importPublishInstallRunRecoverOriginal`：

1. 导入 qmod 目录事实 → `WAITING_SOURCE`（`QMOD_ORIGINAL_PRESENT=false`），不执行 JS、不写 Runtime。
2. 同一 `originalRequestId` 再导入 → 无二次效果。
3. `WorkshopPackageVersionPublish` 冻结 contentHash+termsHash，不写公共登记。
4. install 缺 qmod 原文 → `WAITING_SOURCE`，不是安装成功。
5. JS 自报通关 → `REJECTED`。
6. 服务端 receipt 缺 passed/hash → `UNKNOWN`；recover 保留原 id，不 mint 新请求，不以当前投影当成功。
7. H5 session 缺 SDK 原文 → `WAITING_SOURCE`，`openedArbitraryH5=false`。
8. 客户端包带 `answer` → 拒绝。缺 `originalRequestId` 保持 **JSON null**，不改成空串。

命令：

```bash
mvn -pl chengyinhub-admin -am test \
  -Dtest=com.chengyinhub.business.service.workshop.*Test \
  -Dsurefire.failIfNoSpecifiedTests=false
```

环境：OpenJDK 21.0.12.1 · Maven 3.8.7 · 隔离内存夹具 · 无 MySQL / HTTP / 生产库。  
真实结果（2026-10-23 13:24 America/Los_Angeles，`BUILD SUCCESS`）：

```
Tests run: 5, Failures: 0 -- WorkshopModuleImportGateTest
Tests run: 3, Failures: 0 -- WorkshopInstallUpgradeRollbackTest
Tests run: 3, Failures: 0 -- H5SessionCapabilityGateTest
Tests run: 4, Failures: 0 -- H5AnswerProtectionTest
Tests run: 4, Failures: 0 -- WorkshopRunServerVerificationTest
Tests run: 1, Failures: 0 -- C10FirstJourneyIsolationTest
Tests run: 3, Failures: 0 -- WorkshopPackageVersionPublishTest
Tests run: 23, Failures: 0, Errors: 0, Skipped: 0
chengyinhub-admin .................................. SUCCESS
BUILD SUCCESS
```

## 4. WAITING_SOURCE / 未宣称完成

- qmod 工坊候选二进制 / 232 pack / v12 DOCX / H5·SDK·模组包原文：本仓无 Git 对象。`QMOD_ORIGINAL_PRESENT=false`，`H5_SDK_ORIGINAL_PRESENT=false`。
- 数字/实体/IAP 规则未批准，不自行上线收费。
- 公共作者发布 / 安装登记 / Runtime 仍归 dot，本 PR 未接线。
- 本组 **不是** 工坊全流程 100%。

## 5. 门禁分栏

| 门禁 | 状态 | 说明 |
|---|---|---|
| 静态检查 / 本组单测 | 23 tests, BUILD SUCCESS | 仅 `service.workshop` 隔离测试 |
| 全模块回归 / 真库 MySQL / HTTP | NOT_RUN | 无授权 |
| CI（dot 21/22 专线） | NOT_RUN | `[skip ci]`，不重复触发 |
| 真实支付 / IAP / 发布 | NOT_RUN | 硬边界 |
| 生产 DDL | NOT_RUN | 未做 |
| App / XCTest | NOT_RUN | 后端线 |

## 6. 未解决

- 生产接线：import / freeze / install / verify 尚未挂 Mapper（禁止本组改 Mapper）。
- qmod / H5 SDK 原文未签收前，导入与安装不得变绿。
- iOS 独占投影另开 C10-ios 云端线，不在本 PR。

## 7. Needed hook（do not apply here）

共享 writer 仍归 dot。若日后接线，只把本目录适配器当 collaborator，不要从 Cursor 分支整文件覆盖：

- 公共作者发布 / 安装登记：freeze 后问 `WorkshopPackageVersionPublish`，缺原文保持 `WAITING_SOURCE`。
- Runtime 运行：通关只认 `WorkshopRunServerVerification` 的服务端 receipt；JS 自报必须拒绝。
- H5 容器：域名/SDK 能力问 `H5SessionCapabilityGate`；任意 H5 不得打开。
- 答案：客户端包不得携带；判定走 `H5AnswerProtection` 的冻结作者版本。

WAITING_SOURCE：未 push 的 iOS `e2d86c4`、后端 `e16c2c5` `5b92889`、232 pack、v12 DOCX、H5/SDK/qmod 原文。
