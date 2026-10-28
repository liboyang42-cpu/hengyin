package com.chengyinhub.business.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.vo.ExploreChapterRedemptionResult;
import org.apache.ibatis.annotations.Param;

/**
 * 活动/主题报名Service接口
 * 
 * @author webxh
 * @date 2025-09-04
 */
public interface ICmsRegistrationService
{
    public BigDecimal selectTotalSalesByTopicId(Long topicId);

    public BigDecimal selectTotalSalesByActivityId(Long activityId);


    /**
     * 查询活动/主题报名
     * 
     * @param id 活动/主题报名主键
     * @return 活动/主题报名
     */
    public CmsRegistration selectCmsRegistrationById(Long id);

    /** 待办扫码的报名归属及项目结束时间；只读传入ID，空集合不查询。 */
    Map<Long, com.chengyinhub.business.domain.vo.MerchantTodoRegistrationVO> selectTodoRegistrationOwners(java.util.Collection<Long> registrationIds);

    /**
     * 查询活动/主题报名列表
     * 
     * @param cmsRegistration 活动/主题报名
     * @return 活动/主题报名集合
     */
    public List<CmsRegistration> selectCmsRegistrationList(CmsRegistration cmsRegistration);

    /**
     * 已支付报名的待核销/已核销数,按项目一次分组查完。
     * @return ownerId → {待核销(verification_status=0), 已核销(=1)};没有报名的项目不出现
     */
    Map<Long, long[]> countPaidVerificationByOwners(Long ownerType, java.util.Collection<Long> ownerIds);

    /** 历史 request_id 的 NULL 指纹只允许 CAS 绑定；并发已绑定相同值也算成功。 */
    public boolean bindLegacyRequestFingerprint(Long registrationId, String requestId, String requestFingerprint);

    /**
     * 新增活动/主题报名
     * 
     * @param cmsRegistration 活动/主题报名
     * @return 结果
     */
    public int insertCmsRegistration(CmsRegistration cmsRegistration);

    /**
     * 修改活动/主题报名
     * 
     * @param cmsRegistration 活动/主题报名
     * @return 结果
     */
    public int updateCmsRegistration(CmsRegistration cmsRegistration);

    /**
     * 批量删除活动/主题报名
     * 
     * @param ids 需要删除的活动/主题报名主键集合
     * @return 结果
     */
    public int deleteCmsRegistrationByIds(Long[] ids);

    /**
     * 删除活动/主题报名信息
     * 
     * @param id 活动/主题报名主键
     * @return 结果
     */
    public int deleteCmsRegistrationById(Long id);

    /**
     * 创建活动报名
     * @param userId 用户ID
     * @param ownerType 类型(1-主题 2-活动)
     * @param ownerId 活动/主题ID
     * @param ticketId 票种ID
     * @param isUsePoint 是否使用积分抵扣(0不使用 1使用)
     * @param realName 姓名
     * @param phone 电话
     * @param address 地址
     * @param email 邮箱
     * @return 报名记录
     */
    public CmsRegistration createRegistration(
            Long userId,
            Integer ownerType,
            Long ownerId,
            Long ticketId,
            Integer isUsePoint,
            String realName,
            String phone,
            String address,
            String email
    );

    /**
     * 多票报名扩展：单条 registration 支持 count 张同票 + 参与日期
     * @param count 张数（默认 1）
     * @param participateDate 参与日期 yyyy-MM-dd（自由定向票）
     * @param parentOrderNo 父订单号（多张票共享一个支付时使用，null=独立支付）
     * @param overridePayable 覆盖应付金额（多票主单上承担合计金额时用，null=按本张票自动算）
     */
    public CmsRegistration createRegistration(
            Long userId,
            Integer ownerType,
            Long ownerId,
            Long ticketId,
            Integer isUsePoint,
            String realName,
            String phone,
            String address,
            String email,
            Integer count,
            String participateDate,
            String parentOrderNo,
            java.math.BigDecimal overridePayable
    );

    /**
     * BE-09 多票原子下单:把「循环建多条 registration + 首单汇总应付」整体包进一个事务。
     * 任一票创建失败则全部回滚,杜绝「第2票失败但第1票已落库成可付款孤儿单」。
     * 返回全部已创建 registration,首条(index 0)为支付主单(payableAmount 已汇总)。
     */
    public java.util.List<CmsRegistration> createMultiTicketRegistrations(
            Long memberId,
            Integer ownerType,
            Long ownerId,
            Integer isUsePoint,
            String realName,
            String phone,
            String address,
            String email,
            java.util.List<com.chengyinhub.business.domain.dto.RegistrationTicketItem> tickets
    );

    /**
     * 报名报价（M0-1 实扣报价一致）：与 createRegistration 共用同一套金额计算，
     * 不落库、不扣减，仅返回当前应付金额 + 抵扣明细 + 无状态签名 quoteSign。
     * 下单时回传 quoteSign，后端用相同入参重算比对，不一致返回 409。
     *
     * @param tickets 多票数组（为空时用 ticketId 单票）
     */
    public com.chengyinhub.business.domain.dto.RegistrationQuoteResult quoteRegistration(
            Long memberId,
            Integer ownerType,
            Long ownerId,
            Long ticketId,
            java.util.List<com.chengyinhub.business.domain.dto.RegistrationTicketItem> tickets,
            Integer isUsePoint
    );

    /** 有效 OFFERED hold 的单票报价；只验凭证，不推进状态、不再次占库存。 */
    public com.chengyinhub.business.domain.dto.RegistrationQuoteResult quoteRegistrationForOffer(
            Long memberId,
            com.chengyinhub.business.domain.dto.RegistrationRequest request
    );

    /**
     * M2 自玩票:购买主题通行证(owner_type=1 + topic_id,不走 ticket,用 topic.self_play_price)。
     * 复用现有 pay/回调/退款通道;expires_at = 购后 90 天。返回待支付 registration。
     */
    public CmsRegistration createSelfPlayPass(Long memberId, Long topicId, String realName, String phone);

    /**
     * 按日统计某主题/活动的报名总人数（按 participate_date 聚合）
     * @return [{participateDate, totalCount}]
     */
    public java.util.List<java.util.Map<String, Object>> countByParticipateDate(Long ownerType, Long ownerId);

    /**
     * 根据报名编号查询
     * @param registrationNo 报名编号
     * @return 报名记录
     */
    public CmsRegistration getByRegistrationNo(String registrationNo);


    public CmsRegistration selectByverificationCode(@Param("verificationCode") String verificationCode);

    /**
     * 更新支付状态
     * @param registration 报名记录
     * @return 是否成功
     */
    public boolean updatePaymentStatus(CmsRegistration registration);

    /**
     * 生成核销二维码
     * @param registrationId 报名ID
     * @return 二维码URL
     */
    public String generateVerificationQrcode(Long registrationId);

    /**
     * 处理微信支付回调
     * @param resultMap 回调参数
     * @return 处理结果
     */
    public String handleWechatPayCallback(Map<String, String> resultMap);

    /**
     * 调用方已在自己的事务内向微信查单确认 SUCCESS 时的补记(不走回调 inbox,必须已有事务)。
     * @param resultMap 查单结果(out_trade_no/transaction_id/trade_state/total)
     * @return SUCCESS 或 FAIL
     */
    public String applyConfirmedWechatPaymentWithinTransaction(Map<String, String> resultMap);

    /** 原范围事务内重复支付准入校验；不写支付状态、消费回执、积分或库存。 */
    String checkConfirmedWechatPaymentWithinTransaction(Map<String, String> resultMap);

    /** 零现金报名专用完成入口；不得伪造微信 SUCCESS 回调。 */
    public String completeZeroAmountPayment(String registrationNo);

    /**
     * 重新支付前的唯一业务 Gate：校验归属、待支付状态、支付时效、当前可售/可收款与费率快照。
     * 合法重试必须返回原报名及稳定 registrationNo，禁止换单号复活旧单。
     * 过期时抛 {@code RegistrationOrderExpiredException}；经 {@link #beginPaymentAttempt}
     * 发起支付命中过期会同时关单（释放库存/积分/权益），直接调用本方法只做校验不关单。
     */
    public CmsRegistration preparePaymentRetry(Long registrationId, Long memberId);

    /**
     * 微信预下单前取得 durable PAYING 状态；方法返回时事务已经提交，取消路径不得再本地关单。
     * 已处于 PAYING 的稳定业务单允许幂等重取支付参数。
     * 过期的待支付单会先即时关单（回补库存/退还积分/释放权益）再抛
     * {@code RegistrationOrderExpiredException} 拒绝支付，不允许「只拒不管」。
     */
    public CmsRegistration beginPaymentAttempt(Long registrationId, Long memberId);

    /** 仅确定未向微信发出请求时，允许把本次 PAYING 尝试释放回待支付。 */
    public void releasePaymentAttempt(Long registrationId);

    /**
     * 取消报名
     * @param registrationId 报名ID
     * @param memberId 用户ID
     * @param userName 用户名
     * @return 是否成功
     */
    public boolean cancelRegistration(Long registrationId, Long memberId,String userName);

    /**
     * P0-10 弃单关单:过期未支付订单 id 列表(供定时任务扫描)
     */
    public List<Long> listExpiredUnpaidIds();

    /** 固定本轮时间及最大ID，失败订单不阻塞后页。 */
    public Long getExpiredUnpaidScanUpperBound(java.util.Date now);
    public List<Long> listExpiredUnpaidIdsAfter(java.util.Date now, long afterId, long maxId, int limit);

    /** 在调用方事务内按 activity→registration 当前读，拒绝旧归属/退款/到期快照。 */
    public CmsRegistration requireCurrentUsableRegistration(CmsRegistration snapshot);

    /**
     * §5-3 PAYING 超时对账:过支付窗口仍处支付中(payment_status=1)的订单 id 列表(供定时任务扫描)。
     * 这些单既不能被用户再支付(/pay 拒过期),也不会被 {@link #listExpiredUnpaidIds()} 扫到。
     * 按 id 升序、只取 id 大于 afterId 的一页,调用方以游标翻页扫完。
     */
    public List<Long> listExpiredPayingIds(long afterId);

    /**
     * PAYING 单确认未支付后的收口:条件关单(仍 payment_status=1 才关)+ 回补库存 + 退还预扣积分
     * + 释放 ③ 权益;多票父单整单关(从单一并关、一并回补)。
     *
     * <p><b>唯一实现</b>:对账任务({@code OrderCloseTask.reconcileExpiredPaying})与主办方取消活动
     * ({@code TeamGroupServiceImpl.cancelActivityByOwner})都走这里。这两条路以前各写一份,
     * 积分回补(读改写 vs CAS)与权益失效原因已经漂移过;差异只允许通过下面三个参数表达。
     *
     * <p><b>调用前提</b>:原关单观察通过 settleClosedPayingOne 的原订单锁、原 attempt 与持久成功事实守卫后
     * 才执行此资源释放实现；NOT_FOUND、查单失败或未知态均不证明未执行。
     *
     * @param releaseReason {@code registration_entitlement.invalid_reason},取 EntitlementInvalidReason 常量
     * @param updateBy      积分回补 CAS 写进 update_by 的来源标识
     * @param finalStatus   落库的 {@code registration_status}:3=已取消(主办取消活动导致的关单,
     *                      玩家看到「已取消」)、4=已过期(对账任务确认真超时未支付,玩家看到「已过期」)
     * @return true=本次关闭;false=竞态下已支付/已变化,跳过
     */
    public boolean settlePayingOne(Long registrationId, String releaseReason, String updateBy, int finalStatus);

    /** Original CLOSED observation: topic/order current locks, exact attempt and durable-success guard
     * must precede the existing close CAS and resource release in the same transaction. */
    boolean settleClosedPayingOne(CmsRegistration observed, String observedWxOutTradeNo,
                                 String releaseReason, String updateBy, int finalStatus);

    /**
     * 核销码补偿:已支付但核销码为空的报名单 id 列表(供定时任务扫描,近7天限批)。
     * 场景=支付回调期 generateVerificationQrcode 失败被吞:钱已扣、码为空,微信不再重推回调。
     */
    public List<Long> listPaidMissingQrcodeIds();
    public Long getPaidMissingQrcodeScanUpperBound();
    public List<Long> listPaidMissingQrcodeIdsAfter(long afterId, long maxId, int limit);

    /**
     * P0-10 弃单关单:关闭单笔过期未支付订单(回补库存+退还预扣积分)
     * @param registrationId 报名ID
     * @return true=本次关闭;false=竞态下已支付/已取消,跳过
     */
    public boolean closeExpiredOne(Long registrationId);

    /**
     * RUN-01 拍板纪要#5:已支付未核销、所属活动已结束满安全缓冲的场次票 id 列表(供定时任务扫描)。
     * 只收口活动单(owner_type=2);主题单(①②③)的结算写入在核销动作里,不允许无台账自动置位。
     */
    public List<Long> listExpiredUnverifiedIds();

    /** 同一到期判据，按 id 升序扫描严格大于 afterId 的下一页，每页最多 200 单。 */
    public List<Long> listExpiredUnverifiedIdsAfter(long afterId);

    /**
     * RUN-01 到期自动核销:把一笔已确认到期的场次票置成已核销并记 {@code update_by}=自动任务来源。
     *
     * <p><b>这是状态收口,不是一次核销</b>:不写 verifier_id、不发积分/勋章、不写核销台账、不做结算 ——
     * 场次活动的钱在支付成功时已入账,这里只关掉悬着的核销态并让商家待核销名册与售后列表收敛。
     * 全部判据(仍已支付未核销、活动 end_date+缓冲成立、无在途退款)下推进条件 UPDATE 的当前读。
     *
     * @return true=本次收口;false=竞态下已被真核销/进入退款流/活动改期,跳过
     */
    public boolean autoVerifyExpiredOne(Long registrationId);

    /**
     * P0-21 核销收口:**条件置位 + 入账 + 台账在同一事务内**完成。
     *
     * <p>为什么必须是一个事务:核销是主题结算**唯一**被写入的时刻(SettlementEntryServiceImpl
     * .recordOnPayment 对 ownerType=1 直接返 0 —— 主题的执行商家在核销时才由商家池确定,
     * 支付阶段不入账)。若条件置位先提交、入账再抛异常,商家看到「核销失败」去重扫,置位返 0 行 →
     * 「该订单已核销」⇒ **这笔钱商家永远收不到,且没有任何重试路径能补回来**
     * (对账任务只比总额、发现不了「一条明细都没有」)。
     *
     * <p>⚠️ 积分/勋章**故意留在事务外**由调用方发:它们幂等、且不是资金 —— 发积分失败不该让
     * 已经成立的核销回滚。
     *
     * @param registration     报名单
     * @param verifierId       核销人(商家)memberId
     * @param payeeId          入账收款方 memberId(主题=核销商家本人;活动=主办方)
     * @param validationMethod 台账行的核销方式,可空
     * @return true=本次核销成功;false=已被(可能是另一个中标商家)核销,调用方须拒
     */
    public boolean verifyAndSettle(CmsRegistration registration, Long verifierId, Long payeeId,
                                   Integer validationMethod);

    /** owner/payee 与实际操作人分离的核销收口。 */
    boolean verifyAndSettle(CmsRegistration registration, Long verifierId, Long payeeId,
                            Integer validationMethod, Long actorMemberId, String actorRoleCode);

    /**
     * 多据点核销收口:事务内复核所选中标记录属于当前商家和票据主题,再按该行验证方式核销。
     *
     * @param registration 报名单
     * @param verifierId 当前登录商家 memberId
     * @param registrationMerchantId 商家明确选择的 cms_registration_merchant.id
     * @return true=本次核销成功;false=已被并发或重复核销
     */
    boolean verifyAndSettleForStation(CmsRegistration registration, Long verifierId,
                                      Long registrationMerchantId);

    boolean verifyAndSettleForStation(CmsRegistration registration, Long verifierId,
                                      Long registrationMerchantId, Long actorMemberId,
                                      String actorRoleCode);

    /**
     * 唯一中标时的核销:station 是服务端查出的候选快照。
     * 核销事务按 topic→registration 锁序重新读取原报名和该据点当前归属/中标态，
     * 将同一个实际 station 写入流水与核销审计；不从快照推断原合作单计酬归属。
     *
     * @param station 服务端查出的唯一中标候选，使用其 ID 在原事务内重新核对
     */
    boolean verifyAndSettleForOwnStation(CmsRegistration registration, Long verifierId, Long payeeId,
                                         CmsRegistrationMerchant station);

    boolean verifyAndSettleForOwnStation(CmsRegistration registration, Long verifierId, Long payeeId,
                                         CmsRegistrationMerchant station, Long actorMemberId,
                                         String actorRoleCode);

    /**
     * 带队场次核销：在普通核销事务内额外记下报名单属于哪一个 activity。
     *
     * <p>仅组织槽玩家票调用。场次事实与普通扫码台账分表，避免用核销人反推场次。</p>
     */
    public boolean verifyAndSettleForGroupActivity(CmsRegistration registration, Long verifierId,
                                                   Long activityId);

    boolean verifyAndSettleForGroupActivity(CmsRegistration registration, Long verifierId,
                                            Long activityId, Long actorMemberId,
                                            String actorRoleCode);

    /**
     * 平台后台撤销一张活动票(owner_type=2)的整单核销,用于店员扫错/核错等现场事故。
     *
     * <p>只清核销态并留痕(操作人/原因/时间);活动票款在支付时已按活动入账,本动作不改账本。
     * ③ 探索票走按章撤销;主题整单票的核销会计入执行商家结算,不在此入口内;
     * 活动已结算时拒绝。返回 true = 本次已撤销。</p>
     */
    boolean reverseWholeOrderVerification(Long registrationId, String reason, Long operatorUserId);

    /**
     * 同上;撤销同时把核销台账行标为已撤销、作废尚未发放的到店成长待办。
     * clawbackRewards=true 时与按章撤销同口径冲回这次到店核销已发的积分和经验。
     */
    boolean reverseWholeOrderVerification(Long registrationId, String reason, Long operatorUserId,
                                          boolean clawbackRewards);

    /**
     * [#22 二清闸] 对已存在的单再判一次「这笔钱平台能不能收」,不能收就抛。
     *
     * <p>建单路径的闸在 createRegistration/createSelfPlayPass 内部;这个重载是给**不走建单**的
     * 路径用的 —— 目前唯一的调用方是 ApiRegistrationController 的 requestId 幂等分支
     * (命中已有单直接重给支付参数)。
     *
     * @param registration 已存在的报名单;null / 免费单 / 独立活动单 均放行
     * @throws RuntimeException 票款归第三方且要真实收款时抛(收付通 P0-43 落地前)
     */
    void assertCollectible(CmsRegistration registration);

    /**
     * [P0-17] 该玩家全部 ③ 报名单(已支付),按 activityId 索引。**一次查询,与活动数无关**。
     *
     * <p>三个读侧判定点(/my-completed、广场 summary、俱乐部看板)都要它 —— 提到 service 层
     * 是为了口径单一真源,别在三个 controller 里各抄一份探针。
     *
     * <p>③ 定死是 {@code owner_type=2 / owner_id=activityId} 的活动单:createRegistration 全程
     * 不 setTopicId,且 ApiRegistrationController 把 ownerType==1 无条件 early-return 进
     * createSelfPlayPass ⇒「owner_type=1 + ticketId」那条路从 API 根本不可达。故探针固定 ownerType=2。
     *
     * <p>⚠️ 同一 (member, activity) 可能有**多行**(跨订单重复购买;整单限购闸只压住「同一单多张」)
     * ⇒ 口径定死:**任一 ③ 单通关即算通关**,本方法保留先到的那张。
     *
     * @return activityId → ③ 报名单;不会返 null
     */
    java.util.Map<Long, CmsRegistration> selectExplorePassesByActivity(Long memberId);

    /**
     * [P0-14] ③ 按章核销收口 —— <b>反推校验 + redeem + 台账 + 置位,四步同一事务</b>。
     *
     * <p><b>为什么必须有这个方法而不是在 controller 里顺序调</b>:{@code scanQrCode} 是**无事务的
     * controller 方法**,顺序调多个 {@code @Transactional} 服务 = 多个独立事务 ——
     * redeem 提交后台账再抛,就会出现「配额扣了、entitlement 用尽了、台账没有」且再也重扫不了。
     * 这与 {@link #verifyAndSettle} 存在的理由完全同型。
     *
     * <p><b>与 verifyAndSettle 的分工</b>:那个方法做三件事(置位 + 入账 + 台账),
     * 本方法做的是**另一套三件**(入账那件走的路完全不同,见第三条) ——
     * <ul>
     *   <li>✅ 每笔核销写 {@code cms_registration_scan} 台账:商家侧要能查到「我核销过这张票」;</li>
     *   <li>✅ <b>首次核销就</b> CAS 置 {@code verification_status}(拍板:玩家只要核销一个就不能退款)。
     *       它是三个玩家侧退款入口共用的唯一判据({@code RefundPolicy.verifiedVerdict})。
     *       ⚠️ 语义是**报名级布尔「这张票被核销过」**,不是「核销完了」——
     *       任何拿它当「这票没用了」的地方对 ③ 都会判错,判据一律走
     *       {@code IPerkLedgerService.isTicketFullyConsumed};</li>
     *   <li>✅ <b>计酬档逐笔计提「平台应付台账」</b>({@code merchant_payable},与上面那个
     *       {@code cms_registration_scan} 台账是两张表、两个维度,别混)。D21:③ 票款全额归平台,
     *       给商家的人头费是平台的**采购成本** ⇒ 应付台账 + 对公打款 + 商家开票,
     *       **不是分账、不碰二清、不经 {@code settlement_detail}**。
     *       ⚠️ <b>刻意不调 {@code recordPaidRegistration}</b>:它是「整单票款给单一 payee」的口径,
     *       而 ③ 一张票跨 N 家 ⇒ 照搬 = 最后扫的那家白拿全额 = 资损。**别为了「接上入账」去动它。**</li>
     * </ul>
     *
     * <p>⚠️ 每章的流水真源是 {@code redemption} 表(redeemChapter 插的),不是台账 ——
     * 台账是**商家维度**的「我核销过谁」,redemption 是**章节维度**的「这票这章被谁核销了」。两者都要。
     *
     * <p><b>chapterId 必须在事务内重新校验属于候选集</b>:交集是在事务外算的(controller 的反推),
     * 而商家可以直接把 chapterId 打进 {@code /scan_qr_code_chapter} —— 不在这里复核就等于
     * <b>商家能指定核销任意一章</b>(挑最便宜的那章交差,玩家在贵的那章白跑)。
     *
     * @param topicId 主题真源,必须来自 {@code activity.getTopicId()};③ 的 registration.ownerId 是
     *                activityId,拿它当 topicId 会让候选集校验恒空、核销全被拒
     * @return 核销记录与首次通关探索值回执
     * @throws IllegalStateException chapterId 不在该商家对该票的可核销候选集里
     */
    ExploreChapterRedemptionResult redeemExploreChapterAndRecord(CmsRegistration registration, Long chapterId,
                                       Long topicId, Long merchantOwnerMemberId,
                                       Long operatorMemberId, Integer validationMethod);

    ExploreChapterRedemptionResult redeemExploreChapterAndRecord(CmsRegistration registration, Long chapterId,
                                        Long topicId, Long merchantOwnerMemberId,
                                        Long operatorMemberId, String actorRoleCode,
                                        Integer validationMethod);

    /**
     * 该玩家「可以投诉哪些主题」—— <b>投诉页的选择源与受理口的准入,共用这一份判据</b>。
     *
     * <p><b>为什么必须提到 service 层</b>(2026-08-18):此前选择源是
     * {@code /api/registration/list?owner_type=3},受理口是 {@code ApiCoopController.complaintReport}
     * 里另写的一段循环。两份判据分散在两个 controller 里,已经漂出两个真库实测坐实的缺陷:
     * <ol>
     *   <li>选择源套了「我参与的」列表的视觉收纳规则
     *       ({@code ApiRegistrationController.filterEndedOverWeek},HIDE_AFTER_END_DAYS=7)
     *       ⇒ 结束超过 7 天的主题选不到,而受理口根本没有时间窗。<b>投诉本就是事后行为</b>,
     *       一个给列表做视觉收纳的规则不该管它;</li>
     *   <li>选择源靠 {@code registration.topicId} / 挂载的 {@code cmsTopic} 取主题 id,
     *       而活动单({@code owner_type=2})两者都取不到 ⇒ ③ 自由探索玩家整条选不到 ——
     *       而那是代码自述「今天在用的售票路径」。</li>
     * </ol>
     *
     * <p><b>判据</b>:该玩家 {@code payment_status=2}(已支付,退款会置 4 故天然排除)的报名单,
     * 逐张按 {@code (ownerType, ownerId)} 反查所属主题
     * —— ownerType=1 → ownerId 即 topicId;ownerType=2 → 顺 {@code cms_activity.topic_id} 溯源。
     * 这与 {@code createRegistration} 判「票款归谁」用的是同一个 resolver。
     *
     * <p>⚠️ <b>fail-closed</b>:反查不出主题的单(独立活动 topic_id=null = 平台自办)
     * <b>不进结果集</b>。绝不能塞一个兜底 id 让它「看起来能选」—— 那会让玩家投诉到错误的对象,
     * 而投诉一旦受理就会冻结该主题的结算放款。
     *
     * <p>⚠️ 不用 {@code registration.getTopicId()} 那一列:它全类只有 createSelfPlayPass 一处写,
     * 活动单恒 null(见 {@code CmsRegistrationServiceImpl} 里 resolveSellerTopic 的注释)。
     *
     * <p><b>已知天花板</b>:实现是「1 次报名单查询 + 每张单最多 2 次主键查」,即 N+1。
     * N = 该玩家自己的已支付单数(个位数量级),没有做批量化;真出现大 N 再改成 in 查询。
     *
     * @return 按主题去重后的可投诉项;不会返 null。同一主题多单时保留先到的那张报名单。
     */
    java.util.List<com.chengyinhub.business.domain.vo.ComplainableTopicVO> selectComplainableTopics(Long memberId);
}
