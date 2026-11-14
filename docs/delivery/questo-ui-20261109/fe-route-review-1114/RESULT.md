# 前端路线评审 2026-11-14（只读，未改产品代码）

范围：D 自由探索模板（货架/预览/发布/玩家购买游玩/结算）；E 城市定向平台自玩路线（LINEAR，¥39.90）。
基线：origin/master。方法：静态读码，行号已二次核对；标「需验证」的只做了静态推断，未真机/真库确认。
路径简写：XCX=chengyinhub-xcx，PLAY=chengyinhub-xcx/pages/play，SVC=chengyinhub-system/src/main/java/com/chengyinhub/business/service。
严重度：blocker / quality / polish。本轮未发现已确认的 E 线 blocker；D 线 D-03、D-19 为 blocker（重复写入、款项滞留）。

## 优先修复顺序（一波）
1. D-03 模板「使用」缺 requestId；D-19 跨俱乐部/四店探店日款项滞留
2. E-B1/B2/B3 弱网写入后按钮静默失效且无恢复入口
3. E-B7/B9 支付页超时与报价失败回退标价
4. E-A1~A3 答案归一（含全角逗号分隔符缺失）
5. E-C1/C2 通关页重播与重玩状态串局；E-D4 时段门时区
6. D-16/17/21/22 定价页；D-28/29 分账一致性

---
## D 组：自由探索模板（双端）

### D-A 模板货架 / 预览 / 详情
- **D-01 [quality]** `pages/templatedetail/shelf/index.js:131-137, 148-151`：attached 与 show 各拉一次 loadTopics/loadGames，第一遍响应因 seq 变化被丢弃（:143）。首屏多发请求，回到模板页每次全量重拉。建议只在 show 拉取，加短缓存、回前台静默刷新。（需验证生命周期顺序）
- **D-02 [quality]** `shelf/index.js:209-218`：玩法列表 50 条串行分页，列表越长首屏越慢。建议拿到 total 后并行取余页，或用精简接口。
- **D-03 [blocker]** `templatedetail/utils/topic-adoption.js:87-95` 对照 `chengyinhub-admin/.../ApiTopicTemplateController.java:182, 208-241`：后端 /use 已支持 requestId 幂等，前端 usePayload 未传。`topic-detail/index.js:234-239` 网络失败后重试，若服务端已落库会再生成一份草稿；`:223` 页面已卸载时直接 return 且不记 _copiedId，产生孤儿草稿。建议 _submitUse 生成并在重试中复用 requestId。（落库实况需验证）
- **D-04 [quality]** `shelf/index.js:39(pickGames)`：玩法必须在前端硬编码表 art.GAMES 内才显示，表外模板被静默过滤，后台新上架不发版不出现。建议通用封面兜底并告警。
- **D-05 [quality]** `shelf/index.js:344-350(onCoverError)`：每张封面失败都重跑 buildTopics 并多次 setData，弱网下货架卡顿闪动。建议按项路径 setData 或防抖。
- **D-06 [polish]** `shelf/art.js:175` 与 `shelf/index.js:264`：FEATURED_TOPICS 配 7 个，`slice(0,6)` 只取 6，「高温末日逃生」永远进不了精选。
- **D-07 [polish]** `shelf/index.js:354-364`：openTopic/openGame 无 _navigating 防连点，连点压入重复页面（templatedetail.js goPrimary 已有该守卫）。
- **D-08 [polish]** `shelf/index.js:356, ~366`：previewOnly 提示「保存后才能查看」，货架上无保存入口，是死胡同。建议改成「暂未开放」或隐藏。
- **D-09 [polish]** `shelf/index.wxml:19, 100`：接口成功但返回空时，空态只有标题，无副文案、无刷新/返回。
- **D-10 [quality]** `pages/template/index.js:98` 与 `index.wxml:20-23`：/api/merchant/access/me 网络失败即 applyMerchantPublishAccess(false)，店长弱网被告知「仅店主、店长可发布」，FAB 隐藏，无重试。建议失败单独设 error 态。
- **D-11 [polish]** `pages/template/index.js:116-156`（:154）：/api/publish/home 失败被吞，pub 保持默认 player，发布弹窗锁态错误。建议保留上次值或弹窗内重试。
- **D-12 [quality]** `topic-preview/index.wxml:56`、`topic-preview/index.js:216-222`：预览页只有「查看详情」，应用在下一层，从心动到使用多一步。建议预览页直接给「用这个模板」。
- **D-13 [polish]** `topic-detail/index.wxml:43`：关键 CTA「应用」用 ghost 样式，文案不说明会复制一份草稿。建议主按钮「用这个模板」。
- **D-14 [quality]** `topic-detail/index.wxml:139`：「能力」tab 无 stops 时渲染一行「—」，像坏了。换成空态说明。
- **D-15 [quality]** `pages/templatedetail/templatedetail.js:493-514(goPrimary)`、`shelf/index.js:364-370`：玩法模板「应用」不检查登录（topic-detail 有 ensureLoginFor），openGame 不带 readonly，无权限者走到编辑器才被拒。（需验证编辑器入口是否自带拦截）

### D-B 主办方定价 / 发布
- **D-16 [quality]** `pages/topic/pricing/index.js:40, 221-229`、`index.wxml:16`：对自由探索仍给「有人带/无人带」tab 并索要带队成本、成团人数；后端 `PricingServiceImpl.java:294-296` 对 self 档返回「自由探索主题不能设置收费自玩票」，ticketRows(:331起)只对城市定向返回。前端不判 productType，点「无人带」撞红字死胡同。建议预览接口返回 productType/allowedSubTypes，自由探索只显示门票价。
- **D-17 [quality]** `pricing/index.wxml` 全文：确认终价时看不到平台 3% 抽成与预计到手。收款前不知实得金额，信任/投诉风险。建议接口返回 platformRate，页面展示「平台服务费约 ¥x，预计到手 ¥y」。
- **D-18 [quality]** `pricing/index.js:240(onCostInput)`：改成本不使地板价失效，须手点「重新计算地板」，确认按钮仍可点，带过期地板价提交（服务端兜底拒绝，体验是点了才报错）。建议标记过期并禁用确认，或去抖重算。
- **D-19 [blocker]** `CmsActivityServiceImpl.java:290-318(strandedFreeExploreSales)`、`:340-370 附近 strandedReason`：自由探索场次俱乐部与主题俱乐部不一致（跨俱乐部）、或四店探店日，无结算路径，仅日志告警，钱不入账（注释「分钱规则待业主拍板」）。主办方看不到也提不了。建议主办方端至少展示「结算待处理」及原因，并尽快拍板规则。
- **D-20 [polish]** `pricing/index.js:335`：成功后 setTimeout 2500ms 才 goBack，onUnload 未清理，期间手动返回会多退一层。
- **D-21 [polish]** `pricing/index.wxml:61`：slider min 取小数地板价（如 12.34）、step=1，初值 `Math.ceil(floor)`(js:125) 不在网格上，真机起点可能与显示价不一致。建议 min 也取 ceil，或改输入框。（需验证）
- **D-22 [quality]** `chengyinhub-admin/.../ApiPricingController.java:125-130(requireOwner)、43-70`：定价只允许 topic.memberId 本人；同模板 /use 对商家岗位用 `merchantAccessService.require(PROJECT_MANAGE).getOwnerMemberId()`（ApiTopicTemplateController.java:204-207）让店长以店主名义建主题。店长建的主题到定价被拒「仅主题发布者可定价」。（需验证是否有别的定价入口）建议定价接口同样按岗位解析 owner，前端透传 scope。
- **D-23 [quality]** `ApiPricingController.java:105-112(saleReviewMessage)`：自由探索确认价后提示「请等待开售审核」，但代码注释（:103-104）说明此时并未提交审核，要运营先保存草稿。主办方以为在队列，实际无人处理，无进度可查。建议改「已锁价，等待运营准备开售合同」并给状态入口。

### D-C 玩家购买 / 游玩 / 权益
- **D-24 [quality]** `pages/topic/index/index.js:1819-1820(_rebuildUsed)`：只置 true 从不复位，「每次购买意图一次」实为整个页面生命周期一次，第二次订单过期不再自动重建。（与 E-B10 同点）
- **D-25 [quality]** `pages/play/index.js:3585-3592(_explainMissingEntryQr)`：无 regId 时用 wx.reLaunch 去票夹，清空页面栈，玩家被踢出游戏页。建议 navigateTo。
- **D-26 [polish]** `pages/play/index.js:1440-1470(openPack)`：卡包开启蓄力 1000ms+撕包+逐张 180ms 发牌，6 张约 3 秒以上，无跳过（仅 reducedMotion 直接落定）。建议点击落定或仅首次播放。
- **D-27 [quality]** `pages/topic/catalog/index.js:73, 91-97`：reload 不重置 loadingMore；loadMore 在途时切城市/筛选，旧响应因 seq 不等直接 return，loadingMore 永远 true，分页永久失效且无提示。建议 reload 置 false。
- **D-28 [quality]** `subpackageMember/free-explore/index.wxml:36-45` 及 index.js 接口调用：(a) 权益页只列「权益 N · 状态 · 有效期」，无章节名/店铺名/内容，玩家不知对应哪家店、能换什么；(b) 后端 POST /api/free-explore/bundle/reservation 小程序无调用；(c) 商家侧 /merchant/reservations 与 no-show 小程序也无调用。套票核心「选店预约」「商家看预约/记爽约」走不通。（b、c 需验证是否由其他入口承接）建议权益展示章节与店名，补预约入口与商家预约列表。

### D-D 3% 平台费 / 结算
- **D-29 [quality]** `CmsActivityServiceImpl.java:582-585, 639起(calculateActivityCommissions)` + `SettlementEntryServiceImpl.java:195-216, 262, 398-402`：场次现金按整场销售额一次算平台费；分账行 recordClubFreeExploreSessionSettlement 逐张 HALF_UP。例：3 张 ¥9.50，逐单 0.285→0.29×3=0.87，整场 28.50×3%=0.855→0.86，差 1 分。且现金用活动 live 费率，分账行用报名冻结的 feePlatformRate，费率调整后两边不一致，退款冲回对不平。建议统一取冻结费率，逐单行用余额法分摊。（需验证真库差额）
- **D-30 [quality]** `SettlementEntryServiceImpl.java:208-210` 与 `CmsActivityServiceImpl.recordFreeExploreSessionLedger(~565-585)`：任一已付报名缺 feePlatformRate 即抛 IllegalStateException，同事务整场回滚，每轮重试都失败，主办方款项无限期卡住。建议缺费率单独告警隔离。（需验证库内是否有旧数据）

---
## E 组：城市定向（平台自玩 LINEAR，¥39.90）

### E-A 答案比对（结论）
后端现有 4 套互不一致的归一规则，**客户端无任何归一**（也无 NFKC、标点剥离、繁简）：
- 普通文字题（validationMethod=1）：`BasicTextAnswer.normalize` ≈ `AnswerPolicy.normalize`，仅「去 ASCII 空白 + 全角 ASCII 转半角 + 忽略大小写」。
- 高级问答 qa.TYPE 无 answerPolicy：仅 trim + toLowerCase。
- hangman：仅保留 letter/digit + 大小写折叠。
- 定向备选任务 QUIZ：NFKC + 折叠空白，是唯一用 NFKC 的。
- typeIn：判定原样 equals/equalsIgnoreCase，玩家输入 trim，目标不 trim。

- **E-A1 [quality]** `SVC/support/AnswerPolicy.java:194-205`、`SVC/support/BasicTextAnswer.java:26-36`：`\s+` 只去 ASCII 空白，NBSP、零宽字符、U+3000 不处理（U+3000 转半角空格后保留，:190-192 注释明说）；「。」「、」「·」「《》」等 FF01-FF5E 外标点不折叠；繁简不处理。粘贴答案带 NBSP/全角空格即判错；「故宫博物院。」「圣·约翰」判错。建议共享 AnswerNormalizer：NFKC→去 \p{Z}\p{Cf}→去首尾标点→小写；繁简只走显式别名；AnswerPolicy、BasicTextAnswer、OrientTaskServiceImpl.java:560-565 统一调用。
- **E-A2 [quality]** `AnswerPolicy.java:225-233(matchesLegacyAdvancedType)`、`SVC/impl/AdvancedActionJudgement.java:745-749`：qa.TYPE 无 answerPolicy 只 trim+toLowerCase（注释 215-218 称刻意保留）。同一答案「３号线」/「3 号线」在 /api/play/answer 能过，在高级问答都错，玩家与作者无法预期。建议按 schemaVersion 让新发布配置默认走共享归一器，存量快照保持旧行为。
- **E-A3 [quality，偏高]** `AnswerPolicy.java:69`、`PLAY/components/playkit-qa/index.js:36`：分隔符正则写成 `[、,,]`（两个 ASCII 逗号，已按字节核对），缺全角「，」；同包 `BasicTextAnswer.java:18` 是 `[、,，]`，作者编辑器 `XCX/pages/publish/temp/questo-author.js:288` 也按「，」拆。作者在 qa.TYPE 写「苹果，梨」，预览按两个答案，服务端按整串，答「苹果」永远判错。建议两处补「，」「；」，共享常量并加单测。
- **E-A4 [polish]** `SVC/support/HangmanGameplay.java:467-473`：canon 不做全角转半角，「ＡＢＣ」≠「ABC」。建议 canon 前 NFKC。
- **E-A5 [polish]** `AdvancedActionJudgement.java:1154-1155`、`AdvancedGameConfigValidator.java:2073`：typeIn 目标 `cfg.target` 不 trim，判定无归一；目标末尾有空格则怎么答都不过。建议保存时规范化，判定双方同一归一器。（需验证存量）
- **E-A6 [quality]** `PLAY/components/playkit-typein/index.js:145`：终端皮肤键盘只接 `/^[a-zA-Z ]$/`，校验器仅限长度 1-40。目标含数字/标点/中文时打不出来，玩家卡到限时用完。建议校验器限制 terminalSkin 字符集或键盘按 target 出键。（需验证存量主题）
- **E-A7 [polish]** `playkit-qa/index.js:30-40, 235`：`matchesAnswer` 仅导出给单测，生产不调用，且与服务端规则已不一致，单测给虚假安全感。建议删除或改读服务端共享向量。
- **E-A8 [polish]** `PLAY/index.js:6848`：`if (!answer)` 后不 trim，纯空格被当有效答案发出，服务端判错，客户端 wrongCount++（~6890），连交两次就解锁「揭示答案」。建议先 trim，空则提示。

### E-B 网络错误 / UNKNOWN / 重试 / 双击 / 权限 / 慢上传
- **E-B1 [quality，偏高]** `PLAY/index.js:289-298(settle)、310(fail)、6207-6208、~6215`：basic 写接口（answer/checkin/arrive/photo/puzzle/sensor/preference）的 req() 把无 intentTerminal 标记的响应都改成 `{code:520, httpFail:true, intentUnknown:true}`，包括 310 行构造的 netFail，导致 `r.httpFail` 分支永远先于 `r.netFail` 命中，「没送到可重试」成为死代码。隧道/电梯断一下就变「结果待确认，请重新进入后查看」。建议 fail 里把连接层失败单独标 not-executed/不确定，并走 B3 回读。
- **E-B2 [quality，偏高]** `PLAY/index.js:6058, 6068`（onArriveTap/onScanArrive 静默 return）、`6288, 6299`（置位）、`7137`（唯一清除处，仅 generic-proof）：`_arrivalWriteUnknown` 置位后到达/扫码按钮静默 return 无提示，平台 LINEAR 路线无清除路径。toast 消失后反复点毫无反应，只能退出重进。建议被拦截时重复提示并给「核对一次」按钮。
- **E-B3 [quality]** `PLAY/index.js:883-887(_readBasicRouteIntent 无调用方)`、`258`（receiptOnly 仅 lead 解锁使用，见 5686-5721）、`PLAY/index.wxml:1257, 1285, 1286`：basic 写入回执回读已写好但没接 UI，gWriteUnknown 只改按钮文案，无「再核对」（对比 advanced-game/index.wxml:9 有）。普通答题 UNKNOWN 后无法自助恢复。建议加「核对结果」按钮：已落库→onComplete，未执行→清闩锁。
- **E-B4 [quality]** `PLAY/index.js:8001-8008(_notifyPhotoFail)、8010起、8134`、`playkit-photocheck/index.js:66-70`：photoCheck 上传成功但提交失败（4xx/409/确认中）统一提示「上传失败，请重拍」并丢弃已传 URL。慢网下重传大图。建议区分上传/提交失败，提交失败保留 URL 改「重新提交」。
- **E-B5 [quality]** `PLAY/index.js:8106, 8115-8141`、`playkit-photocheck/index.js:187-189`（_emitShot 立刻 busy:false）、wxml:34-36：全屏 loading 只覆盖上传，上传完 settle 收掉后视觉判分（可能 5-20 秒）无任何忙态；再点「拍一张」被 `_kitPhotoUploading` 静默忽略（8088）。建议判分阶段显示「正在审核…」，把 acting 透传给 busy。（需验证 CARD 模式）
- **E-B6 [quality]** `playkit-qa/index.js:298-308(fail: () => {})`、`playkit-photocheck/index.js:156`：chooseMedia fail 不区分取消与权限被拒，qa 拍照完全静默，相机/相册被拒后点按钮无反应无出路。建议判 errMsg 含 auth/deny 时弹「去设置」，参考 play-location-once.js 的 GUIDE。
- **E-B7 [quality，偏高]** `XCX/utils/checkout/checkout-workflow.js:84, 145-148, 186`：requestPayment 发起前即启 20 秒定时器，到点按「回调丢失」处理 paymentSettled=true，之后真回调被丢；校验器也最长 20 秒。慢网/输密码久时付款成功却弹「支付结果待确认，暂不要重复支付」，状态黏在 unknown，再点只弹同提示。建议定时器只启动旁路核账，不丢真回调；unknown 提供「刷新订单状态」并轮询，确认已付直接进 `_onSelfPlayPaid`。（需验证慢支付回调时机）
- **E-B8 [quality]** `pages/topic/index/index.js:1635-1672(_loadSignupProfile)、1721-1736`：资料请求 fail 与「资料为空」都得 null，弱网下资料齐全也被弹「还差姓名和手机号」并跳资料页。建议区分网络失败，只 toast 重试。
- **E-B9 [quality]** `pages/topic/utils/checkout-view.js:79`、`index.js:1590-1625(onSelfPlayConfirm)`：报价失败（quoteState='failed'）时 pay 回退 `info.selfPlayPrice`，loading/refused 为假，支付按钮可用。有会员减免/通行证覆盖（应 ¥0）的玩家弱网时屏上显示 ¥39.90 并可支付，与实际结算价不一致，无失败提示。建议 failed 时按钮置灰并「重新获取价格」。
- **E-B10 [polish]** `pages/topic/index/index.js:1819-1820`（_rebuildUsed 从未复位）、`1827-1851`（`_retrySelfPlayPayment` 仅定义无调用，且只处理 payParams 不处理虚拟支付）：同页面第二次遇过期单不再自动重建；死代码。
- **E-B11 [polish]** `pages/topic/components/cy/pass-reveal/index.js:35-48`、`index.wxml:37-38`：通行证到账动画 6.3 秒后才出「放进票夹/开始游戏」，无点击跳过（仅减少动效偏好立即出）。付完款最想玩的人干等 6 秒。建议点击任意处落终态。
- **E-B12 [polish]** `XCX/subpackageMember/signup/index.js:332`：减动效偏好下 `_openTicket` 无 `_navigating`/`entering` 锁，快速双击「开始游戏」叠出两个 play 页（两套定位/计时/音频）。另：动画路径下状态不符（232-247 行附近，如「票已过期」）要先看完 900ms 黑场才被告知失败。
- **E-B13 [polish]** `XCX/subpackageJourney/utils/journey-api.js:43`、`subpackageJourney/pages/settle/index.js:168-173`：评价走 /api/comment/add 无 requestId，网络失败提示「已保留，请重试」，服务端 `ApiCommentController.addComment(104-153)` 本身不去重，响应丢失后重试可能产生两条评价。（需验证 commentPublicationService 是否去重）

### E-C 续玩 / 重进 / 重玩 / 定位权限
- **E-C1 [quality]** `PLAY/index.js:801(onShow 在 _playReloadOnShow 时重载)、1137(onHide 恒置真)、4544(allDone→openFinish)、8640起、9462、9514`：已通关主题每次从别页返回（结算页、足迹卡页、分享后）都触发 loadData→openFinish，通关动画/勋章页/finale 重播，finishSnap 被清空，足迹卡图被丢弃。建议用 routeSessionId 为 key 的「本局已展示」标记，重入只刷数据。（需真机验证）
- **E-C2 [quality]** `PLAY/utils/story-resume.js:15-18`、`XCX/utils/play-run-session.js:20-28`、`PLAY/index.js:7261-7263, 3977, 4313, 1992`：续玩状态（pos/gates/intro/openNodeId/packOpened/trails/finished/草稿）按 member+topic 存，不含路线局 id，也无清除接口。重玩第二局会跳过开场与章节门、`finished=true` 让第二局通关不弹通关卡、旧草稿回填。建议 key 加 routeSessionId，或新局/通关时清理。（需验证产品是否允许重玩）
- **E-C3 [quality]** `PLAY/index.js:1169-1174(_runElapsedSeconds)、9468起、8640(finishDateLabel)`：用时取「本页时钟」与「首站到末站 doneAt 跨度」较大值；重进时钟从 0，跨度不含开局到首站；日期标签取此刻而非完成日。同一次通关首次「52:10」，隔天变「47:30」，足迹卡日期变成当天。建议用服务端战报 startedAt/endedAt/completedAt（/api/play/player-journey/report）。
- **E-C4 [quality，合规]** `PLAY/index.js:5158`（文案）、`5292-5370(watchLocation)`、`XCX/pages/play/utils/play-trail.js:7, 12, 44`：弹窗写「仅在开局时检查……之后各站不再要求定位」，但开局后仍持续 `wx.onLocationChange` 用于自动点亮 50 米内站点、画轨迹并每 30 秒本机落盘。隐私文案与行为不完全一致，审核/投诉风险。建议文案补充用途与「仅本页前台」。
- **E-C5 [quality]** `PLAY/index.js:6061`（`_locAsked` 为真只 toast）、`play-location-once.js:21, 29-42`：GUIDE 教玩家走「···→设置→位置信息」，但只有点弹窗「去设置」才会静默重定位；菜单授权回来点「到达」仍提示未开启，onShow 不重查权限。建议 gpsOk 为假时先 wx.getSetting，已授权就 `_relocateSilently`。（需验证）
- **E-C6 [polish]** `PLAY/index.js:6850-6860(submitGame located)`、`SVC/.../PlayBasicCommandApplication.java:364-372`：客户端按「本地无已完成站」判断是否带开局定位，服务端按 runStarted。不同步时（如本地有已完成序章/剧情站）不带坐标被拒「开局前请先开启定位」，客户端只写 gNetErr 不去定位。建议收到定位类拒绝时自动 requestPlayLocation 并保留答案重试。（需验证）
- **E-E1 [polish]** `PLAY/index.js:7445-7449 与 7490-7494`：`openCityStamp` 在 Page 对象定义两次，内容相同。删一份。
- **E-E2 [polish]** `PLAY/index.js:9295`：保存足迹卡到相册被拒（errMsg 含 auth）直接 `wx.openSetting({})`，无说明，返回后不自动重试保存。
- **E-E3 [polish]** `PLAY/index.js:260-262, 6061, 7346-7355`、`ArrivalPolicy.java:185-190`：开局 300 米检查用 `this._loc`（最近一次定位事件），发请求前不重新取点；服务端 locationTooOld 仅在策略配 maxAgeSeconds 时校验，拒绝原因 ARRIVE_LOCATION_STALE 客户端无专门处理（grep 无命中），落通用文案。站着不动的玩家被拒且无引导。建议 `_loc.capturedAt` 超 30-60 秒先 wx.getLocation；STALE 走与 TOO_FAR 同一「重新定位」弹窗。（需验证线上策略是否配 maxAgeSeconds）

### E-D 时区 / 日期
- **E-D1 [polish]** `XCX/pages/topic/utils/topic-detail-facts.js:43`、`XCX/utils/ticket-expiry.js:10`、`XCX/utils/datetime.js` 头注释：用 `new Date(str.replace(/-/g,'/'))` 按设备时区解析，未用已有 `toTimestamp`。非 UTC+8 设备剩余天数/过期偏 8 小时。建议改 toTimestamp + chinaDateKey。
- **E-D2 [polish]** `PLAY/utils/play-hud-overlays.js:84-88, 118-120`：stamp() 本地解析再与 Date.now() 相减，进行中用时、`disputeWindowDeadline > now` 在非 UTC+8 设备偏差数小时。
- **E-D3 [polish]** `PLAY/utils/play-route-state.js:62-67(asTimestamp), 78, 112`：直接 `Date.parse('yyyy-MM-dd HH:mm:ss')`，iOS 返回 NaN→0，分支图路线完成顺序可能错乱（LINEAR 影响小）。（需验证 iOS）
- **E-D4 [quality]** `SVC/impl/AdvancedGameRuntimeServiceImpl.java:2036`：时段门用 `Calendar.getInstance()`（JVM 默认时区），同类其余用 BUSINESS_ZONE（116, 2251, 2333, 2343）；生产靠 ry.sh:8、run-chengyinhub-admin.sh:51 的 `-Duser.timezone`，代码本身不保证。测试环境漏参数则时段门偏 8 小时。建议用 `clock.now().toInstant().atZone(BUSINESS_ZONE)` 并加非 +8 默认时区单测。
- **E-D5 [polish]** `PLAY/utils/playkit-view.js:120-127`、`play-open-state.js:36-37`、`PLAY/index.js:5091-5100`、`pages/topic/index/index.js:876-880, 1566`：倒计时、营业中标签、可玩期限取设备时钟/时区；到账页「可玩至」由本机 +90 天推算而非服务端 expiresAt，可能与订单不一致。建议以服务端 expiresAt/开放时刻为准，用服务端 now 校准偏移。

---
## 说明
- 本文件由两路只读审计汇总（D 27 条前端/后端项 + 3 条结算项，E 33 条）。条目编号为本文新编。
- 未做真机、真库、CI 验证；「需验证」条目请在修复波中先复现再改。
- 修复波时遵守：自由探索/答题沿用既有引擎，不新增判题分支；答案归一改动属 AGENTS「Ask first」（鉴权无关，但影响存量判定），需用户确认。

STATUS: DONE
