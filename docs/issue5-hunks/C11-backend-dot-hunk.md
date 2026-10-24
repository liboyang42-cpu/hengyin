# C11-backend · 审核 / 运维支持 / 可观测 / 中美展示独占适配器

Issue：https://github.com/liboyang42-cpu/chengyin-remote-ops/issues/5 领取组 **C11**  
仓库：`github.com/liboyang42-cpu/hengyin` 仅写本仓  
Base：`origin/master` `2ec68899099a1e250df0949a8383bf85b5d3ffe0`  
分支：`cursor/c11-ops-support-adapters-d6bd`  
Head：`3056968323b66526c26742da3ba705fdd35947ae`（本文件若再提交会前进）。本文件不替代商店 / 备案门禁。

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
- `C11AccountSwitchRestartIsolationTest.java`（换 request 不改写原 UNKNOWN；缺 requestId 保持 Java null；recoverOriginal 未见 id 不占账再唯一写入）
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
真实结果（2026-10-23T22:29:10Z，`BUILD SUCCESS`）：

```
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- ReviewResultVisibilityAdapterTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- VersionedLocaleDisplayTest
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- SupportEventLocatorTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- CompensationEntryProjectionTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- C11FirstJourneyIsolationTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- C11AccountSwitchRestartIsolationTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- DataDeletionEvidenceTableTest
Tests run: 21, Failures: 0, Errors: 0, Skipped: 0
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

- 生产接线：`MapperOriginalEventSource` 已挂 `selectByTarget`（run/order 定位）。全局 `selectByRequestId` 继续拒绝，那不是缺口。授权多事件页见第 11 节，共享 XML 仍未改。`FrozenTermsSource` 仍未挂。
- 支持台 UI / Controller 未做（禁止本组加公共 Controller）。
- 真实 App / 商店 / 备案旅程属 C12 与运营门禁，本组未跑。

## 7. Parent addendum 2026-10-23 16:58 America/Los_Angeles

`C11AccountSwitchRestartIsolationTest.recoverOriginalOfNeverSeenIdDoesNotOccupyThenUniqueWriteApplies`：未见 `c11-never-seen` 抛 `IllegalStateException` 且 `findByRequestId` 仍空；后续唯一 `add` 才是第一笔写入。保留 throw。

父进程独立 surefire（`/tmp/hengyin-c11`，HEAD `f893e3e80`，2026-10-23T23:58:38Z）：

```
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- C11AccountSwitchRestartIsolationTest
Tests run: 22, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

App / HTTP / MySQL / CI = NOT_RUN。不是 100%。

## 8. Parent addendum 2026-10-23 17:13 America/Los_Angeles

`CompensationEntryProjectionTest.recoverOriginalOfNeverSeenIdDoesNotOccupyThenUniqueAuditApplies`：未见 `c11-comp-never` 的 `recoverOriginal` 保持 `originalAudit` 空且不写入 audit map；后续唯一 `COMPENSATION_FREEZE` 才是第一笔审计。`recoverOriginal` 只读 FrozenTerms + CompensationAuditSource，不 insert。

父进程独立 surefire（`/tmp/hengyin-c11`，HEAD `305696832`，2026-10-24T00:13:40Z）：

```
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- CompensationEntryProjectionTest
Tests run: 23, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

App / HTTP / MySQL / CI = NOT_RUN。不是 100%。

## 9. Parent addendum 2026-10-23 18:18 America/Los_Angeles

`SupportEventLocatorTest.recoverOriginalOfNeverSeenIdDoesNotOccupyThenUniqueWriteApplies`：未见 `c11-loc-class` 抛 `IllegalStateException` 且 `findByRequestId` 仍空；后续唯一 `add` 才是第一笔写入。保留 throw。Did not invent a second recoverOriginal. Isolation occupancy remains.

父进程独立 surefire（`/tmp/hengyin-c11`，HEAD `52bd5a443`，2026-10-24T01:18:01Z）：

```
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- SupportEventLocatorTest
Tests run: 24, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

App / HTTP / MySQL / CI = NOT_RUN。不是 100%。

## 10. Parent addendum 2026-10-23 20:24 America/Los_Angeles

Dot 20:20 PDT：只并行 READY 组，衡量闭环吞吐，不再刷 unused-action 测试。

`SupportEventLocator.MapperOriginalEventSource` 消费既有 `BusinessAuditEventMapper.selectByTarget`。按 requestId 单独查询仍 WAITING_SOURCE。locateByRunId 在 WAITING_SOURCE 时回退到 target 原行，不另造审计表。

父进程独立 surefire（`/tmp/hengyin-c11`，HEAD `2de93c338`，2026-10-24T03:27:22Z）：

```
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- SupportEventLocatorTest
Tests run: 25, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

MySQL / HTTP / App / CI = NOT_RUN。不是 100%。

## 11. Parent addendum 2026-10-24 03:30 America/Los_Angeles

Dot 02:39 PDT：原 `selectByDomainActionRequestForUpdate` 是单回执。缺的是已授权 typed 资源上的多事件分页，不是 `selectByRequestId`，也不是第二套审计写入。

`AuthorizedTypedResourceEventPage` 只认 domainType、domainId 和 `VIEW_AUDIT`。`platformAdmin` 与员工 memberId 不能当授权。同一 request 跨域、同一数字不同 targetType、来源多返回的行都不进入结果。撤权不查询。空白、带空格和超过 64 的 requestId 在查询前拒绝。`9007199254740993` 保持 Long，不经 Double。缺行是未确认 UNKNOWN，投影 SUCCESS 不回退。手机号和 token 脱敏。

共享 hunk 未应用到本分支。补丁 3615 字节，SHA256 `19f4ddc2c39649c5f2fad39f50e3b595311447066ad70258a9e466c5bdec5bfd`。本树 `git apply --check` 为 0。副本 apply 后查询带 requestId 等值。工作区里 DTO、XML、QueryService 无 diff。

父进程 surefire（`/tmp/hengyin-c11`，2026-10-24T10:30:58Z）：

```
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0 -- AuthorizedTypedResourceEventPageTest
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- SupportEventLocatorTest
```

MySQL / HTTP / App / CI = NOT_RUN。这不是完整 C11 闭环。
