# C09-backend · 通知 / 附件 / Webhook 原结果恢复独占 producer

Issue：https://github.com/liboyang42-cpu/chengyin-remote-ops/issues/5 领取组 **C09**  
仓库：`github.com/liboyang42-cpu/hengyin` 仅写本仓（不写 `chengyin`）  
Base：`origin/master` `2ec68899099a1e250df0949a8383bf85b5d3ffe0`  
分支：`cursor/c09-notify-webhook-producers-266c`  
Head：`a7be7a65a`（本文件若再提交会前进）。本文件不替代生产凭据、真实外发 Webhook 或公共 IM 协议。

## 1. 白名单（实际写入）

生产（仅新目录 `chengyinhub-system/.../service/notify/`）：

- `ImmutableReceiptProducer.java`
- `AttachmentReviewProducer.java`
- `DevicePushRegistrationLifecycle.java`
- `WebhookDeliveryLogProducer.java`
- `WebhookSameEventRetry.java`
- `WebhookSsrfHopGuard.java`

测试（`chengyinhub-admin/src/test/java/.../service/notify/`）：

- `C09FirstJourneyIsolationTest.java`
- `C09AccountSwitchRestartIsolationTest.java`（缺 requestId 不落空串回执；撤权设备不改写另一台；UNKNOWN 恢复不是 APPLIED）
- `ImmutableReceiptProducerTest.java`
- `AttachmentReviewProducerTest.java`
- `DevicePushRegistrationLifecycleTest.java`
- `WebhookDeliveryLogProducerTest.java`
- `WebhookSameEventRetryTest.java`
- `WebhookSsrfHopGuardTest.java`
- `InMemoryNotifyFixtures.java`（隔离夹具：内存原回执 / 投递日志 / 设备登记 + 不触网 `WebhookTransport`）

未改：`WebhookTransport` / `HttpWebhookTransport` / `TransportResponse`（只包装）、`NativeWebhook*`、7/8/27 原 writer、IM send/receipt 公共协议、journal、`AppSession`、公共 Controller / Mapper、请求格式、生产配置、小程序、旧 `chengyin` 仓。无生产 DDL。无共享文件 hunk。无生产凭据。无真实外发 HTTP。

## 2. 复用的既有真源（不克隆第二套 writer / journal）

| 既有真源 | 本 producer 如何消费 |
|---|---|
| `ImServiceImpl` clientMessageId + requestHash 幂等 | `ImmutableReceiptProducer` 同 requestId 同指纹返回原回执，冲突拒绝覆盖 |
| `IImService#readSentMessage`「只读原发送收据，不重新发」 | 断网重开 / UNKNOWN 恢复只读原回执，不造新 request |
| `WebhookTransport` 投递缝 | `WebhookDeliveryLogProducer` 构造注入包装；测试用 RecordingTransport |
| `TransportResponse.is2xx()` / statusCode=-1 | 投递日志 DELIVERED / FAILED / UNKNOWN |
| `OssCardCutoutStorage.publicMediaAddress` 口径 | `WebhookSsrfHopGuard.publicUnicast`：每跳独立，不 DNS |
| `ImMessage` 现有 4 种 msgType | 不改；16 类附件是独占审核表，不是公共协议扩枚举 |

## 3. 首个可跑旅程（隔离测试）

`C09FirstJourneyIsolationTest`：

1. 夹具 webhook 配置（`https://hooks.example.test/c09-e` + `fixture-` HMAC，非生产凭据）。
2. 事件 `evt-E` 过每跳 SSRF 闸后写签名投递日志（原 deliveryId / nonce / signature）。
3. 同 event 重试保留原 eventId / deliveryId / nonce / signature，只增加 attempt。
4. disable 后不再调用 transport；revoke 后同样停止。
5. `APPLIED+CURRENT` 投影不能把 PIN 原回执 UNKNOWN 变成成功，也不能凭投影补投不存在的事件。

命令（只跑本组新类）：

```bash
mvn -pl chengyinhub-admin -am test \
  -Dtest=com.chengyinhub.business.service.notify.*Test \
  -Dsurefire.failIfNoSpecifiedTests=false
```

环境：OpenJDK 21.0.12.1 · Maven 3.8.7 · 隔离内存夹具 · 无 MySQL / HTTP / 生产库。  
真实结果（2026-10-23T23:09:39Z，`BUILD SUCCESS`）：

```
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- C09FirstJourneyIsolationTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- DevicePushRegistrationLifecycleTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- AttachmentReviewProducerTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- WebhookSameEventRetryTest
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- WebhookSsrfHopGuardTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- ImmutableReceiptProducerTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- WebhookDeliveryLogProducerTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- C09AccountSwitchRestartIsolationTest
Tests run: 16, Failures: 0, Errors: 0, Skipped: 0
chengyinhub-admin .................................. SUCCESS
BUILD SUCCESS
```

SHA256（生产 6 类；回执/重试 `a7be7a65a`）：

- `ImmutableReceiptProducer.java` `ebe48523b15bb4ebda275697a9d8d52d20c17dad6f060591d7812ce4053a7955`
- `AttachmentReviewProducer.java` `58d30b728c6ac3605aba83a1919f249ff80b82a04b6e029498a125ccb9a20a75`
- `DevicePushRegistrationLifecycle.java` `595e7a432dd12a4865f6264da7b3441adcda20e24f306fa99e3872f264960b92`
- `WebhookDeliveryLogProducer.java` `549fcf0e3be7bf7810073229ab085620906f3eed863a34c92e9a2ff34b7b3f74`
- `WebhookSameEventRetry.java` `7af475aed5000fcc1ad8367b7d50da39ac2ee52515cc77f118681e120c2e62af`
- `WebhookSsrfHopGuard.java` `ca7e6aa925f330d24280b71048c32b99fa4760e3c1c23c02251f791a9bec645d`

## 4. 门禁分栏（文档不得冒充通过）

| 门禁 | 状态 | 说明 |
|---|---|---|
| 静态检查 / 本组单测 | 11 通过 | 仅 `service.notify` 隔离测试 |
| 全模块回归 / 真库 MySQL / HTTP | NOT_RUN | 本组无授权跑生产式环境 |
| 真实外发 Webhook | NOT_RUN | 硬边界，未做 |
| CI（dot 21/22 专线） | NOT_RUN | 不重复触发 |
| 生产凭据 / APNs / FCM | NOT_RUN | 只接受 fixture- 前缀 |
| 公共 IM 协议接线 | NOT_RUN | 归 D5 / dot，本组不改请求格式 |
| 生产 DDL | NOT_RUN | 硬边界，未做 |

## 5. 未解决

- 生产接线：`OriginalReceiptStore` / `DeliveryLogStore` / `RegistrationStore` 尚未挂到 Mapper（禁止本组改 Mapper）。
- 公共 IM send/receipt、journal、`NativeWebhook*` 仍归 D5，本组只交独占 producer。
- 真实 App 推送 / 外发 Webhook / C12 旅程本组未跑。

## 6. Parent addendum 2026-10-23 17:10 America/Los_Angeles

DevicePush / AttachmentReview / DeliveryLog `recoverOriginal` of never-seen `dev-recover-never-c09` / `att-recover-never-c09` / `evt-dlv-never-c09` does not occupy; later unique register / submit / deliver is the first write.

父进程独立 surefire（`/tmp/hy-c09`，HEAD `84c8bf246`，2026-10-24T00:10:50Z）：

```
Tests run: 2 -- DevicePushRegistrationLifecycleTest
Tests run: 2 -- AttachmentReviewProducerTest
Tests run: 2 -- WebhookDeliveryLogProducerTest
Tests run: 19, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

App / HTTP / MySQL / CI = NOT_RUN。不是 100%。

## 7. Parent addendum 2026-10-23 17:18 America/Los_Angeles

`WebhookSameEventRetryTest.recoverOriginalOfNeverSeenIdDoesNotOccupyThenUniqueDeliverApplies`：未见 `evt-retry-never-c09` 的 `recoverOriginal` 保持 `UNKNOWN_NOT_RECOVERED` 且 store 仍空；后续唯一 `deliver` 才是第一笔 DELIVERED。同 event 重试保留原 ids。

父进程独立 surefire（`/tmp/hy-c09`，HEAD `db67cf8b1`，2026-10-24T00:18:51Z）：

```
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- WebhookSameEventRetryTest
Tests run: 20, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

App / HTTP / MySQL / CI = NOT_RUN。不是 100%。

## 8. Parent addendum 2026-10-23 18:31 America/Los_Angeles

`ImmutableReceiptProducerTest.recoverOriginalOfNeverSeenIdDoesNotOccupyThenUniqueAcceptApplies`：未见 `c09-rcpt-never` 的 `recoverOriginal` 保持 UNKNOWN 且 store 仍空；后续唯一 `accept` 才是第一笔 ACCEPTED。缺 requestId 保持 Java null。C09 首次 UNKNOWN `accept` 仍是唯一原回执（有意保留）。

父进程独立 surefire（`/tmp/hy-c09`，HEAD `ec5e6586c`，2026-10-24T01:31:02Z）：

```
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- ImmutableReceiptProducerTest
Tests run: 21, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

App / HTTP / MySQL / CI = NOT_RUN。不是 100%。

## 9. Parent addendum 2026-10-23 19:08 America/Los_Angeles

HEAD `ed76d7eeb` tests. Did not invent recoverOriginal.

`DevicePushRegistrationLifecycleTest.unusedRevokeOfNeverSeenDoesNotOccupyThenUniqueRegisterApplies`：未见 `dev-revoke-never-c09` 的 `revoke` 抛原注册不存在且 store 仍空；`recoverOriginal` 保持 UNKNOWN；后续唯一 `register` 才是 ACTIVE。

父进程独立 surefire（`/tmp/hy-c09`，2026-10-24T02:07:55Z）：

```
Tests run: 3 -- DevicePushRegistrationLifecycleTest
Tests run: 4 -- C09AccountSwitchRestartIsolationTest
Tests run: 22, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

App / HTTP / MySQL / CI = NOT_RUN。不是 100%。

## 10. Parent addendum 2026-10-23 19:13 America/Los_Angeles

HEAD `0388ad83e` tests. Did not invent recoverOriginal.

`AttachmentReviewProducerTest.unusedDecideOfNeverSeenDoesNotOccupyThenUniqueSubmitApplies`：未见 `att-decide-never-c09` 的 `decide` 抛原附件回执不存在且 store 仍空；`recoverOriginal` 保持 UNKNOWN；后续唯一 `submit` 才是 PENDING。

父进程独立 surefire（`/tmp/hy-c09`，2026-10-24T02:13:16Z）：

```
Tests run: 3 -- AttachmentReviewProducerTest
Tests run: 4 -- C09AccountSwitchRestartIsolationTest
Tests run: 23, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

App / HTTP / MySQL / CI = NOT_RUN。不是 100%。

## 11. Parent addendum 2026-10-23 19:15 America/Los_Angeles

HEAD `5cb517e16` tests. Did not invent recoverOriginal.

`ImmutableReceiptProducerTest.unusedOfflineReopenOfNeverSeenDoesNotOccupyThenUniqueAcceptApplies`：未见 `c09-reopen-never` 的 `offlineReopen` 抛原回执不存在且 store 仍空；`recoverOriginal` 保持 UNKNOWN；后续唯一 `accept` 才是 ACCEPTED。

父进程独立 surefire（`/tmp/hy-c09`，2026-10-24T02:15:53Z）：

```
Tests run: 3 -- ImmutableReceiptProducerTest
Tests run: 4 -- C09AccountSwitchRestartIsolationTest
Tests run: 24, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

App / HTTP / MySQL / CI = NOT_RUN。不是 100%。

## 12. Parent addendum 2026-10-23 19:18 America/Los_Angeles

HEAD `a6d993d26` tests. Did not invent recoverOriginal.

`WebhookDeliveryLogProducerTest.unusedRequireOriginalOfNeverSeenDoesNotOccupyThenUniqueDeliverApplies`：未见 `evt-req-never-c09` 的 `requireOriginal` 抛原投递日志不存在且 store 仍空；`recoverOriginal` 保持 UNKNOWN；后续唯一 `deliver` 才是 DELIVERED。

父进程独立 surefire（`/tmp/hy-c09`，2026-10-24T02:18:28Z）：

```
Tests run: 3 -- WebhookDeliveryLogProducerTest
Tests run: 4 -- C09AccountSwitchRestartIsolationTest
Tests run: 25, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

App / HTTP / MySQL / CI = NOT_RUN。不是 100%。

## 13. Parent addendum 2026-10-23 19:20 America/Los_Angeles

HEAD `0f5e7cf3b` tests. Did not invent recoverOriginal.

`WebhookSameEventRetryTest.unusedRetrySameEventOfNeverSeenDoesNotOccupyThenUniqueDeliverApplies`：未见 `evt-retry-unused-c09` 的 `retrySameEvent` 抛原投递日志不存在且 store 仍空；`recoverOriginal` 保持 UNKNOWN_NOT_RECOVERED；后续唯一 `deliver` 才是 DELIVERED。

父进程独立 surefire（`/tmp/hy-c09`，2026-10-24T02:20:46Z）：

```
Tests run: 3 -- WebhookSameEventRetryTest
Tests run: 4 -- C09AccountSwitchRestartIsolationTest
Tests run: 26, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

App / HTTP / MySQL / CI = NOT_RUN。不是 100%。

## 14. Parent addendum 2026-10-23 20:40 America/Los_Angeles

生产接线：`ImmutableReceiptProducer` 只读既有 `IImService.readSentMessage`（`ImServiceOriginalReceiptJournal`）。`persistOriginal` 缺 `im.send` 为 WAITING_SOURCE，不从本组发 IM。`recoverFromJournal` 未见 clientMessageId 保持 UNKNOWN，不占内存 store；不把 IM msgType 翻译成本组 Kind。公共 send/receipt/journal 仍归 D5。

Did not invent occupancy tests. App / HTTP / MySQL / CI = NOT_RUN。不是 100%。
