本片已实现完整扫码核销应用归位；未执行 Java/数据库/HTTP/启动/CI，等待 root 同源集中验收。

基线/父提交：`bdb6be21035f2d26810850c411b665e37343b82e`；独立分支 `codex/architecture-day-c-registration-redemption`。新类型仅 `com.chengyinhub.web.service.registration.RegistrationRedemptionApplication`，其嵌套 `Actor` 只冻结认证读取值及失败事实，无 Controller 引用、业务 lambda 或重新认证。getter 失败仍由原位置的 try/catch 处理，避免把失败误作数值 0 后进入团票席位 service；Controller 实际 SecurityContext 读取仅一次。

| 原 Controller 方法 | 新应用方法（首参数为冻结 Actor） |
|---|---|
| scanQrCodeChapter(code, chapterId) | scanQrCodeChapter(actor, code, chapterId) |
| scanQrCodeStation(code, registrationMerchantId) | scanQrCodeStation(actor, code, registrationMerchantId) |
| scanDynamicCode(code) | scanDynamicCode(actor, code) |
| scanGroupMemberTicket(code, activityId) | scanGroupMemberTicket(actor, code, activityId) |
| scanQrCode(type, code, activityId) | scanQrCode(actor, type, code, activityId) |
| scanQrCode(type, code) | 保留 Java 兼容适配；应用内部也保留对应重载 |

19 个专用 helper 同归新 owner：resolveRegistrationByScanCode、clubSessionVerificationTooEarly、assertScanTypeMatchesOwner、isExplorePass、redeemExploreChapter、chapterDisplayName、describeChapters、requireStationChoice、resolveScanContext、resolveExploreScanContext、doRedeemExploreChapter、replayOriginalSeat、duplicateVerificationResult、applyOriginalSeatRewards、applyStoreVerifyGrowth、hasEffectiveMerchantCoopOrder、isTopicOrganizer、topicHasOrganizer、isActiveOrganizerActivity；ScanContext 原样迁移。共享合作单判据仍仅委托 RegistrationQueryApplication 的既有静态算法。

依赖原样迁入：registrationService、merchantAccessService、coopOrderMapper、clubMemberService、topicService、activityService、registrationMerchantService、perkLedgerService、exploreCollectibleService、topicChapterService、cmsActivityService、pointsAwardService、growthService、registrationSeatService，以及同名 dynSecret 配置。复用原签名工具与现有报名/权益/席位端口；RegistrationTicketApplication 继续独占签发/买家回读，不复制票码系统。

五条 HTTP 映射/参数/原响应与 Java 兼容签名保持；原 actor/owner/type/payment/window/章节/据点/团范围检查及原回执优先顺序保持。Controller 与本应用均无外层事务，现有服务的锁、CAS、事务提交后积分/成长顺序不变；不增加 self-invocation 事务。支付/取消/退款/回调/六条查询及其它未迁方法共 22 个方法体逐字保留。

全部真实 Java 扫码调用仅在 12 个旧夹具，完整路径见 registration-redemption-fixtures.json。只迁原 leaf 注入及两个 getField 观察点，私有 helper 反射改指真实应用；原内部路由 spy 观察点随 owner 迁移。没有 mock 应用成功。旧 122 个测试声明、正常/拒绝/重复/奖励/回滚/UNKNOWN 断言及清理反向归一后逐字相同。两条源码合同接缝同时迁移：DeadlockRetryEntryPointContractTest 的原 1/2 调用数量和非事务断言改查真实应用，保留 Controller 检查并增应用类级非事务检查；RegistrationEquityAtomicityContractTest 仅将旧优惠券拒绝分支源码路径改到新 owner，原四条断言不改。另新增 RegistrationRedemptionApplicationTest 4 例：冻结 actor 原回执/零奖励、认证异常拒绝层级（含团票席位零调用）、原结算→积分→成长及重复响应、无外层事务声明，均未跑。

小程序实际调用链：pages/merchant/index/index.js、subpackageMember/components/cy/profile/index.js、pages/club/detail/index.js 与 scene-member-participation-detail 经现有 verification-scan/merchant-verification/registration-seat/readback 工具路由；pages/play/index.js 的 leadVerifyMemberTicket 直接调用原 scan_group_member_ticket，并保留席位 UNKNOWN workflow/readback。生产 JS/请求参数/原 UNKNOWN pending 不改。只适配两个源合同测试到真实应用 owner；台账/CI/baseline 仍由 root 持有。

实际执行：`python3 docs/delivery/architecture-day/C/registration-redemption-check.py`：25 个迁移方法含多行签名精确对照、22 个未迁体、全部 HTTP 元数据/公开签名及 12 夹具/122 原测试声明保持；`git diff --check` 通过。Node 两个定点文件最终单次结果：registration-seat-replay-source 9/9、verification-unknown-adapters 257/257，均 fail=0/skip=0。Java 的 122+4 是源码用例数，不是实跑通过数。

无迁移/DDL。回退须同撤 Controller/新应用和夹具装配，无数据回退动作。未验证：编译、122+4 Java、原真实存储/并发事务、HTTP/JWT、Spring 启动、台账重建；不借用 bdb 的绿色结果。本片不改变金额、票码 TTL、权限、产品或退款政策。

补充静态引用审计：已扫全仓 class.field / class.method / Javadoc / 源码反射。ApiMerchantController 的 HIDE_AFTER_END_DAYS 消费者由 root 修正到查询 owner，本候选不包含/覆盖该文件或 RegistrationQueryApplication；其它旧 controller 指向仅历史注释，仍存在的 HTTP/Java 兼容方法链接保持有效。未执行 e2e_regression/DevTools。

7c8 后清理：删去 Controller 中只有声明的 redemptionMapper、topicCompletionAwardService、qrcodeService、umsMemberService、umsMemberBalanceDetailService；删除已迁真实应用的 CompletionCoupon 夹具两条旧无用字段 setter，其余 setup/断言仍保留。检查器只登记这两条精确空接线删除，25 个迁移体/22 个未迁体及原 132 个测试声明继续精确对照。退款夹具仍依赖的 refundService 不删；RegistrationQueryApplication/public 常量与 ApiMerchant 外部 caller 不改。只做静态验证，未运行 Java。
