# 报名查询簇归位

代码候选已完成：六个 HTTP 查询与九个专用读 helper 归入 `com.chengyinhub.web.service.registration.RegistrationQueryApplication`（644 行）；Controller 2096→1562 行。用途是隔离报名展示/只读授权与支付、核销、退款执行的变更传播，不以减行数作为验收。相关 Node 77/77，0 fail/skip；Java 编译、JUnit、MySQL、HTTP/JWT、Spring 启动和 CI **均未运行**。

基线/父提交 `c7e0ff743f834345098651abe90ef4f55c2c8923`。工作树 `/workspace/hengyin-c-seat-verification`，分支 `codex/architecture-day-c-seat-verification`。此片不重交 322→c7；root 按差异串行合成共享 Controller，不整份覆盖其他 owner。

## 六条入口与真实调用

|原方法/路径|新 owner 同名入口|保留的次序、响应与 caller|
|---|---|---|
|getRegistrationDetail `/info`|`getRegistrationDetail(Long memberId,String id)`|真实 actor→原本人归属→主题/活动与分类→原 scan/template（TemplateSecrets.strip）→原 RefundPolicy 窗→已有权益/票面→未满足使用条件时清除旧二维码展示→公开组织者昵称→人工退款投影→本人最新退款单→RegistrationDetailDisplay.enrich 手机脱敏。orderinfo、scene-route-content、支付结果 verifier 等调用参数/响应不变。**没有生成二维码**。原 Exception→Ajax 500 映射不变。|
|exploreCompletion `/explore-completion`|`exploreCompletion(Long memberId,String id)`|真实 actor/有效 id/本人报名/原 EXPLORE_PASS 判据→既有 activity/topic→原 `IExploreCompletionReadService.read(reg,member,topic,activity)`。orderinfo 完局面 caller 不变；原未捕获 reader 异常仍向外传播。|
|getJoinRegistrationDetail `/join_info`|`getJoinRegistrationDetail(Long memberId,String member_id)`|始终使用真实 actor，忽略请求 member_id；原已报名查询→本人检查→scan/模板去秘密→主题或活动展示。无记录仍 200 且无 data。profile 等 caller 不变；原 catch/错误文字保留。|
|countByDate `/count_by_date`|`countByDate(Long principalMemberId,String owner_type,String owner_id)`|actor/参数→原发布者或 club owner 判据→需要时实际 merchant VERIFY_RECORD_READ→已授予/生效 merchant owner scope 或活动 owner→原 `countByParticipateDate`。两个商家分支保留，403/ServiceException code/msg 保留。club/topic-detail registration-trend caller 不变。|
|myRegistrationList `/list`|`myRegistrationList(Long member_id,String owner_type,String status,Integer is_online)`|actor→原类型/status/null-status/online→本人查询→原首次扫码/核销完成过滤→本人退款一次批读→主题模式回落/活动/原退款投影。不套七天隐藏。应用内部返回原 List，Controller 在**同一 HTTP 线程、原最后位置**调用 getDataTable；未启用原注释 startPage，PageInfo/total/内嵌 TableDataInfo code/msg/外层 Ajax 不变。orderlist、投诉 picker、scene-route-content、profile 预览等 caller 不变。|
|myJoined `/my-joined`|`myJoined(Long memberId)`|actor→本人主题+活动两次原查询→本人退款一次批读→原展示→仅此链的七天隐藏→原创建时间倒序→原 Ajax List。profile/club/detail 等 caller 不变，不冒称列表账本也有七天限制。|

`caller-inventory.json` 记录真实小程序/UI 源的 20 处路径引用（排除 tests/scripts），附文件/行号/原语句；生产 caller 均未改。

六个入口均由 Controller 原 getAppUserId 获取 actor，保留原各自 getter 失败处理及 null 检查层级，不把请求 member_id/owner_id 当 actor。应用不访问 HTTP、SecurityContext/Controller，不反调 Controller，也没有业务 lambda。没有新增 SQL、schema、依赖、runtime、接口路径/参数、金额规则、退款决定或授权政策。

实际读链逐项核对：CmsRegistrationService 的 selectById/selectList/count 直接原 Mapper；topic/activity/ticket/scan/template/merchant/category/member 的选中方法均原 SELECT；Perk.listEntitlements 原 entitlement SELECT；Refund.selectRefundList 原退款单 SELECT；CancellationRead.attachManualRefundInfo 只读原本人冻结案件并改返回对象；ExploreCompletionRead.read 原已有权益/奖项读面；MerchantAccess.require 原当前身份/权限解析。未把 ensure/assign/刷新/CAS/支付/退款执行或外呼包装成 Query。RefundPolicy/TemplateSecrets/RegistrationDetailDisplay 等原实现继续复用，内存展示加工和原转人工 warn 保留；warn logger owner 改到真实应用，对应旧测试 appender 同步迁移。

## helper 与共享接缝

九个专用 helper 原算法各只留一份：readTopicProductType、latestRegistrationRefund、latestRegistrationRefunds、attachOrganizer、attachRefundInfoForList、attachRefundVerdict、attachManualRefundInfo、filterEndedOverWeek、resolveOwnerEndDate。七天常量原值 7；纯内存过滤 helper 改为 public static 供原直接断言访问，不增加 HTTP 入口。

**必须由 root 串行纳入的两个 review.patch（本候选未擅改该共享接缝）：**

1. `shared-coop-read.review.patch`：`hasEffectiveMerchantCoopOrder` 被 countByDate 与原核销簇共用，不属专用读 helper。新应用保留原判据静态实现并显式接收原 CoopOrderMapper；patch 只把 Controller 旧 helper 变成同名纯委托桥，不改任何核销正文/次序/SQL/事务。显式 mapper 参数保证旧核销 fixture 不需装配新查询服务才能执行原判据。**root 应合此 patch 后才认为该共享算法单源**；待合阶段 Controller 仍保留原判据，这是所有权限制的已登记重复，不能漏合或把它当额外新规则。
2. `refund-snapshot-owner.review.patch`：共享 `RefundSnapshotWiringTest` 只有两处源码路径从旧 Controller 指向实际查询 owner；原资金快照/实参个数/零元规则断言完全不变。未直接改 A/G 共有测试，以免空 Controller 上计数 0=0 假绿。

两 patch 已实际 `git apply --check`。共享 imports/原字段未清理，交 root 串行处理；查询测试已不再依赖从 Controller 复制字段，root 清理字段时仍应检查其余命令 fixture 的旧无用 setter。A/B/G 实现、Mapper、支付/取消/退款/核销/回调方法均未改。

## 表征、实际检查与限制

- `check_move.py` 实际运行通过：六 query 经明确 actor 获取移交、Ajax.error 限定和 HTTP 分页包装适配后方法体逐字等价；九专用 helper 与共享读谓词算法逐字等价（仅可见性/显式 mapper 参数适配）；**41 个非查询方法体逐字未变**，全部原 HTTP mapping 顺序/文本保留。该脚本只使用 git/文件/静态比较，不执行 Java。
- 13 个旧测试文件，75 处叶子依赖直接迁到实际应用，41 个真实 Controller 调用重新装配；新增 test-only helper 不读取/复制旧 Controller 业务字段、不生成成功 mock/default，不影响生产。中途替换字段直接更新同一应用，不捕获旧叶子。允许的装配/静态 helper import/log appender owner 变化归一后，**13 个旧文件逐字相同，90 个原 @Test/@ParameterizedTest 声明及全部原场景、断言、SQL、闩锁、清理保留**。这不是 90 个用例实跑证据，参数化实际次数待 root Surefire。
- 新 `RegistrationQueryApplicationTest` 六条已编写未运行：六入口匿名零读、外人报名拒绝且零 enrichment、join 参数不能替换 actor、原 completion scope/call 顺序、原 detail 与 completion 不同异常边界、真实 Controller PageInfo 37 总量/同线程/无新增事务或 PageHelper context。
- `node-results.json` 为四文件独立实际 Node：新 source/负控 8、原 trend 4、orderinfo 状态 46、原支付过期合同 19，共 **77 pass，0 fail/skip**。负控覆盖改传 actor、删除本人 guard、任一当前 merchant 权限检查删除；不会只因另一分支还含权限 enum 而假绿。原 UI 生产代码未改。
- `move-evidence.json`、`fixture-inventory.json`、`static-checks.json` 保存源码身份/每方法摘要/旧 fixture 声明与断言摘要；17 Java 文件只做词法括号检查，**不是编译或运行**。JS syntax 与 git diff --check 通过；完整差异提交前秘密扫描。没有新增 CI/runner/回归批次。

待 root：应用上述共享 patch、统一编译/原相关 Java 与真库 complaint picker/HTTP actor 与分页/真实 Spring 启动。新应用引用原 18 个读依赖，无 @Lazy、optional dependency、事务注解、外部 I/O 或运行时解析框架；新增类型已在开工更新中报供 root 转 F。无迁移/生产 DDL。回退时按本机械片的 Controller 委托、应用、fixtures 和已应用共享桥一起回退，不触动 c7 审计/席位/UNKNOWN 或其他 owner 的源码。
