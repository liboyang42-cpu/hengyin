# C11-backend · 审核 / 运维支持 / 可观测 / 中美展示独占适配器

Issue：https://github.com/liboyang42-cpu/chengyin-remote-ops/issues/5 领取组 **C11**  
仓库：`github.com/liboyang42-cpu/hengyin` 仅写本仓  
Base：`origin/master` `2ec68899099a1e250df0949a8383bf85b5d3ffe0`  
分支：`cursor/c11-ops-support-adapters-d6bd`  
Head：`628996ae99e921c81285b2e144956f25042e3aa7`（本文件若再提交会前进）。本文件不替代商店 / 备案门禁。

## 1. 白名单（实际写入）

生产（仅新目录 `chengyinhub-system/.../support/ops/`）：

- `SupportEventLocator.java`
- `CompensationEntryProjection.java`
- `DataDeletionEvidenceTable.java`
- `VersionedLocaleDisplay.java`
- `ReviewResultVisibilityAdapter.java`

测试（`chengyinhub-admin/src/test/java/.../support/ops/`）：

- `SupportEventLocatorTest.java`
- `CompensationEntryProjectionTest.java`
- `DataDeletionEvidenceTableTest.java`
- `VersionedLocaleDisplayTest.java`
- `ReviewResultVisibilityAdapterTest.java`
- `C11FirstJourneyIsolationTest.java`
- `C11AccountSwitchRestartIsolationTest.java`（换 request 不改写原 UNKNOWN；缺 requestId 保持 Java null）
- `InMemoryOriginalEventSource.java`（隔离夹具，只装原 `BusinessAuditEvent`）

未改：JWT / RBAC / 政策、生产配置、Controller / Mapper / migration、`TopicRouteRuntimeServiceImpl`、`CmsTopicServiceImpl`、`RouteEffects`、小程序、旧 `chengyin` 仓。无生产 DDL。无共享文件 hunk。

## 2. 复用的既有真源（不克隆第二套审计）

| 既有真源 | 本适配器如何消费 |
|---|---|
| `BusinessAuditEvent`（只追加） | `SupportEventLocator` / 补偿审计回查 |
| `AuditTrailProjectionService` / `AuditTrailEntryVO` | 补偿 `trailForTopic` 接口，不新建留痕表 |
| `AuditTask` / `AuditRecord` 状态与 ACTION | `ReviewResultVisibilityAdapter` 只投影可见性 |
| `ClubEditionCompensationTerms` + `IClubEditionCompensationService#requireFrozenTerms` | 原补偿入口 = 开售冻结条款 |
| `ExtraApproverReachability.APPROVE_PERMISSION` | 补偿只认这一既有权限串，不查菜单 |
| `FinalCompensationFacts` | 仅对照；不得覆盖冻结条款 |
| `AccountDeregistrationServiceImpl#executeOne` | 删除证据表 REMOVED / ANONYMIZED_RETAINED |
| `DataRetentionCleanupTask` 默认窗口 + `RoamLocationRetention.THIRTY_DAYS_MILLIS` | 留存行，不执行清理 |
| `AuditBacklogMonitorTask` | 未复制；积压 SLA 仍归原任务 |

## 3. 首个可跑旅程（隔离测试）

`C11FirstJourneyIsolationTest`：

1. 支持台按 requestId 定位同一 request / run / order。
2. 原 `afterJson=UNKNOWN`，当前投影 `APPLIED+SUCCESS` **不能**变成原成功。
3. 持有既有 `business:clubextra:approve` 的查看者读到冻结补偿 ¥200，不是当前 FINAL ¥999；无权限抛 `COMPENSATION_VIEW_DENIED`。
4. 删除证据表：`member_identity` REMOVED，`oms_order` ANONYMIZED_RETAINED，`business_audit_event` RETAINED_LEGAL。
5. 审核 submit → reject → revise → approve 后批准结果对 PUBLIC 可见。
6. CN 日期 `Asia/Shanghai` 版本化展示；商店 / 备案门禁 `NOT_RUN`。

命令（只跑本组新类）：

```bash
mvn -pl chengyinhub-admin -am test \
  -Dtest=com.chengyinhub.business.service.support.ops.*Test \
  -Dsurefire.failIfNoSpecifiedTests=false
```

环境：OpenJDK 21.0.12.1 · Maven 3.8.7 · 隔离内存夹具 · 无 MySQL / HTTP / 生产库。  
真实结果（2026-10-23T19:53:51Z，`BUILD SUCCESS`）：

```
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- ReviewResultVisibilityAdapterTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- VersionedLocaleDisplayTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- SupportEventLocatorTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- CompensationEntryProjectionTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- C11FirstJourneyIsolationTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- DataDeletionEvidenceTableTest
Tests run: 16, Failures: 0, Errors: 0, Skipped: 0
chengyinhub-admin .................................. SUCCESS
BUILD SUCCESS
```

SHA256（生产 5 类）：

- `SupportEventLocator.java` `50ba7223d64992a201d000db0478e4339f00e24a36509c70ef3939cf49754d14`
- `CompensationEntryProjection.java` `b13959c8cd130cfbb04c68e6b4f996e3194385d040126ea2700cff737bc2f218`
- `DataDeletionEvidenceTable.java` `23ff2480afea59da48b2a85bb82dffce2c3c3635e8fa81c10b674045a504de97`
- `VersionedLocaleDisplay.java` `4476d80b0f0a4e3bfa28004f54f251d002ba97853388d236fd41914138ccb98c`
- `ReviewResultVisibilityAdapter.java` `7764ded15db376a87ab9d8f01ff1a1265dfac1b788243e3510e18e1b05d20e57`

## 4. 中美差异（v1 锁定）

- CN：`zh-CN` · `Asia/Shanghai` · `yyyy-MM-dd HH:mm:ss` · 线下消费/核销微信支付
- US：`en-US` · `America/New_York` · `MMM d, yyyy h:mm a` · 虚拟道具 Apple IAP

改展示必须升 `VersionedLocaleDisplay.PROFILE_VERSION`。本类不宣称备案或上架已通过。

## 5. 门禁分栏（文档不得冒充通过）

| 门禁 | 状态 | 说明 |
|---|---|---|
| 静态检查 / 本组单测 | 见 PR 测试输出 | 仅 `support.ops` 隔离测试 |
| 全模块回归 / 真库 MySQL / HTTP | NOT_RUN | 本组无授权跑生产式环境 |
| CI（dot 21/22 专线） | NOT_RUN | 不重复触发 |
| ICP / 小程序备案 | NOT_RUN | 代码不能代替管局审核 |
| 隐私协议 / 用户协议签署 | NOT_RUN | 法务流程 |
| App Store / Play / 微信小程序商店审核 | NOT_RUN | 商店门禁 |
| 真实生产容量 / 发布审批 | NOT_RUN | 另一获批阶段 |
| 生产 DDL / 真实支付退款发奖 | NOT_RUN | 硬边界，未做 |

## 6. 未解决

- 生产接线：`OriginalEventSource` / `FrozenTermsSource` 尚未挂到 Mapper（禁止本组改 Mapper）。
- 支持台 UI / Controller 未做（禁止本组加公共 Controller）。
- 真实 App / 商店 / 备案旅程属 C12 与运营门禁，本组未跑。
