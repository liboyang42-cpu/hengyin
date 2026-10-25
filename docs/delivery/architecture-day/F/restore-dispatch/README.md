# 恢复进程非资金派发围栏 SOURCE

实施 parent：`fa5c263440573992701263d6bee60830844a49b8`。公共独立分支 `codex/architecture-day-f-restore-dispatch`，工作目录 `/workspace/hengyin-f-restore-dispatch`。共同类先独立冻结/推送于 `f874583cab3a05026078ef51a5609644587bd2d9`，后续提交只补本 F 非资金消费者/测试源码，不覆盖 A/C/E/H/G 并行源码。不改资金 Java、SQL/migration、生产配置、CI/registry/technical policy。

## 固定合同

唯一共同类 `com.chengyinhub.common.config.RestoreDispatchGuard`：

```java
public static boolean isRestoreMode();
public static void requireDispatchAllowed();
public static final class Blocked extends RuntimeException;
```

`CHENGYIN_RESTORE_MODE` 只从服务器进程环境读取一次；缺省和精确 `false` 正常，精确 `true` 恢复，其他值（包括空/空白/大小写不同/非法值）同样 fail-closed。没有 request/header/body/System property 覆盖、setter、动态 RPC 或数据库开关。非法值不回退 normal，不打印原 flag。Blocked 固定安全消息 `RECOVERY_DISPATCH_BLOCKED`、无 provider cause；不继承 AiServiceException/HttpRequestException，不表示 NOT_SENT、未计费、已取消或可重试。

类本身不引 Spring/新依赖；所有者仍由原各生产服务承担。正常模式原方法体、锁/事务、授权、并发、provider 分类、计费规则和清理策略保留。入口暂停不证明旧执行者或备份后外部动作未发生；开关不能作为退款/回填/编号复用的证据。恢复完成后是否启动正常进程须原人工操作手册决定，不在程序里改 false。

## 已落真实入口与出站口

| 生产链/owner | 本片具体位置与保存边界 |
|---|---|
| AiCreator / AiGateway | merchantPlan/themeDraft/planThemeDraft/themeDraftPrepared/node/template/chapter/safety/task/club/insight 的请求准入；Gateway generate/generateThemeDraftPrepared/generateNpcAsrHandoff、consumeQuota/consumePlannedQuota；在新预占前拒绝。Creator finally 对下游 Blocked 不释放原预占，Gateway 不转为 provider 未发送/未知，也不退额度 |
| 草稿持久意图 | AiThemeDraftRequestService.prepare/execute 在原 insert/claim/读取缺正文后的拒绝终态写之前关闭；read/readIntent/readPrepared/readRecovery 保留。Blocked 不被 generic catch 写成 FAILED/NOT_SENT |
| Photo | PhotoCheckIntentService.prepare/executeOnce 在新意图/claim 前关闭；PhotoCheckVisionService.reviewOnce 在编码、额度/快照/阶段前关闭；Blocked 不退款、不降级成“未发/已审”，也不 markUnknown 覆盖旧事实；read/markApplied 原事实合同保留 |
| NPC ASR/text | NpcAsrIntentService.recognize/beginHandoff、NpcChatWriteService.admit/admitShop、NpcChatService 三个 chat 入口关闭；保留 read/readOutcome/readOriginalRequest。下游 Blocked 不经 ASR finish(NOT_SENT)、NPC terminalize(NOT_RUN) 或额度 handoff。原 UNKNOWN/reservation 不改 |
| 音色与模型 | NodeNpcService.voiceEnroll 和 NpcVoiceEnrollmentService.enroll 在预处理/claim 前关闭；OpenAiCompatibleLlmClient.chat/chatVision/post；DashScopeSpeechToTextService.transcribe；DashScopeVoiceClient.enroll/synthesize 及 private post（只原 query_voice 例外）最后防漏。原 voice status/history 与同原 voiceId 的 isReady 查询可继续 |
| AI quota recovery | AiQuotaRecoveryService.runDue 在枚举/领取前暂停；prepare/requireNpcAsrHandoff/requireRefund/refundNpcAsrNotSent/reconcile 直接入口关闭；旧 Redis receipt 缺失不触发释放。实际已核事实的保留/阶段写并不被伪装成新派发许可 |
| 地图 | TencentMapClient.get、最终 request 在准入/URL I/O 前关闭；MapGeocode 在 cache miss 进入 request slot 前拒绝，TopicRoute 不把 Blocked 吞成普通 provider null。原并发/重试/总预算不改。此处是实际地图 API，不是禁止所有普通 HTTP GET |
| 认物/抠图/生成媒体 | ObjectCardServiceImpl.mint、ObjectNamer.name、ImageSegClient.segment、OssCardCutoutStorage.transfer/stageGeneratedAudio；AppMediaAssetService.stage/retry。写前/排队前拒绝，不把 provider 围栏异常当 null fallback 成功 |
| OSS | FileOssUploadUtils 的公开上传、最终 upload/putObject 前、deleteOwnedObjectIfPresent；已有对象的授权只读/签名/metadata 读取未全局关闭。CommunityPostServiceImpl.runMaintenance 在 outbox/erasure claim、删除通知、擦除/外部删对象前拒绝，原身份/队列不被改成 RETRY 或完成 |
| 广播/CRM/俱乐部/召回 | OfficialBroadcast.publish/dispatch、MerchantCrmCampaign.create/dispatch/retry、Recipient.deliver、ClubEventNotification.send/retryFailed 均在保存/claim/授权消耗前关闭；各原 recovery/runDue/retryDue 在枚举前暂停；RoamRecall.runDue 不 claimRecallAttempt/消耗次数。recordFailure 不把 Blocked 当可重试业务失败 |
| 持久系统通知 | CommittedOfficialNoticeService 仍允许 enqueueOfficial 与原业务同事务保存；wake/deliver/reconcilePending 暂停、不 claimUnknown、不丢行；beforeStart 恢复态只沿 readBeforeStart 读原身份，缺失不创建；ImNotificationDeliveryTask/BeforeStartNotifyTask 领取前暂停；ImService.deliverSystemNotification 拒绝而 enqueue/findIntentId 保留 |
| 订阅通道 | NotifyServiceImpl.fanout、WxSubscribeChannel.send、WxSubscribeMsgService 最终 send/post；不调用 token/发送口，不落普通 false/成功。原 best-effort catch 对其它异常一字保留，Blocked 明确穿透，见下方 callback 接缝 |
| 媒体送检 | WxContentSecurityService.checkText/checkMediaAsync/submitMediaCheck/submitMediaCheckOne 及实际送检 sendPost（只匹配原两个固定送检 URL）；原 token 获取与验签/解密/回调/inbox mutation 不全局禁止。Blocked 不被转 MSG_BUSY/null/送检成功 |
| 其它实际出站 | SmsVerification.send 写计数前关闭，AliSmsUtil.sendCode SDK 前关闭；PlayerWxacode 已缓存结果可读，cache miss 在锁/频控/生成前拒绝，最后 HttpWxCodeGateway.generate 再围住；HttpWebhookTransport.post 阻止告警/webhook 真实出站，不伪造送达 |

## 共同调用者接缝，不能宣称全 T20

1. A 独占所有付款/退款/提现 SDK、准入及自动恢复；本 F 包不触碰其源，也不批准任何资金补记/回填。C `a7eee8e5b790938a89bc19276c6edf68e9bdfeff` 的 delivery/runner 合同已读，其 probe 仍须 BLOCKED/allDispatchDomainsFenced=false；本片不能自行改成 true。
2. `RefundServiceImpl.notifyRefundAfterCommit`（原约541）捕获通知异常于原提交后，不会因本 Blocked 回滚已完成退款；但其旧 best-effort WX 通知没有本 F 可确认的独立持久投递身份，不能凭跳过 runnable 宣称已经交付或自动恢复。原退款事实/ID保留，须 A/root 串行决定原持久通知合同或保留人工待核，禁止制造新 WX 重投许可。
3. `CmsRegistrationServiceImpl.registerPaymentAfterCommit`（原约2692/2699/2709）直接唤醒 registration-qrcode/signup；本片 CommittedBusinessNotice 的实际 dispatch/deliver 明确抛 Blocked，绝不在内部假返回 true/false。该类没有通用持久 runnable 队列，F 没有造新平台；root/C 必须保留原 payment callback/inbox/consumption 提交与已有通知意图，暂停 wake 不影响实际已核事实 ACK。不能把丢弃内存任务当关闭。已有原 `ISystemNotificationIntentService.enqueue`、CommittedOfficialNotice.enqueue 的持久身份仍可保存。
4. `ApiLoginController.getPhoneNumber` 为共享登录文件内独立付费手机号取回，不能被 broad HTTP guard 误当 token/read query。精确 `shared-phone.review.patch` 单独给 root 串行应用，含必要直接测试源码，不直接编辑认证/共享 Controller。未应用前不能称该出站已闭合；匿名原拒绝与正常成功/失败测试保留。
5. 原声音生成/媒体业务其它并行源码由 E/H/root 合并本片 guard hunk；不可整文件用 fa5c 覆盖其后续实现。新技术 type 只有 common guard/Blocked，无 Mapper/新表/业务 owner 写权。需要技术 policy 登记时 root 审定，不扩大旧 baseline/public scope/private port。

Callback 证据边界：本片不改 WxContentCallbackInbox/Claim/Mutation 生产源码、验签/解密入口和 A 资金 callback。新 callback unit SOURCE 在原 owner/fence 下真实调用 MutationService，保留一次完成/迟到重复拒绝；不冒充签名 HTTP、事务或 MySQL 已通过。

## SOURCE、测试与交付边界

新4个测试类，共26个普通 `@Test` 声明（消费者22、启动配置2、callback1、SMS1），无 skip/disable。另共享手机号 review.patch 有1个直接 `@Test` 源码。启动测试以真实新子进程环境初始化 common 类，缺省/false、true/非法/空、System property 不可重开；只写 SOURCE，未启动任何子进程。消费者测试调用真实生产类，替身仅用于 Redis/SQL/provider/事务/定时执行器边界，不把 mock 正常返回写成真实外部/存储证据。包含 normal 原公开委托、恢复原 UNKNOWN/原 key 可读、原 voiceId 只读查询、深层 Blocked 不退款/不翻 NOT_SENT、队列与 mapper 不变化的源码。

原所有测试文件/场景/断言均保留，未为测试修改原资金、权限、UNKNOWN、迁移或清理规则。未增加新依赖、服务、HTTP 参数/权限、业务状态、schema 或 CI 入口。

本会话无 Node/Python/Java 测试、无语法编译、Java/DB/HTTP/容器/故障/CI 全部 NOT_RUN。仅源码阅读/编辑、git diff 与逐文件摘要；不能宣称 compile、runtime、全域派发、安全恢复、全 T20 或 WP14 完成。旧 state-key/容量/owner 包均冻结未重做，旧绿不借给本片。

交付静态检查仅 `git diff --check`、共享 patch 的 `git apply --check`、新增行固定高风险密钥模式（私钥头/AWS/GitHub/Slack token）未命中；当前环境无原 gitleaks 二进制，未下载/安装，完整 gitleaks NOT_RUN 由 root 最后统一扫描。此定点模式检查不等于全仓秘密审计或行为测试。

Nextcloud/server `4c7fac86556afa9b5adf6bd3ee556220f70924e6` 的共同 maintenance HTTP/cron 入口仅作为 root 已读的模式参考；本片没有复制其全站503/退出语义或引入依赖。
