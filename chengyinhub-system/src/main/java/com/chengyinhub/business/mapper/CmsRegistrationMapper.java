package com.chengyinhub.business.mapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import com.chengyinhub.business.domain.CmsRegistration;
import org.apache.ibatis.annotations.Param;

/**
 * 活动/主题报名Mapper接口
 * 
 * @author webxh
 * @date 2025-09-04
 */
public interface CmsRegistrationMapper 
{
    /** 按现有报名筛选条件计数，不读取个人隐私。 */
    int countCmsRegistrationList(CmsRegistration registration);

    List<com.chengyinhub.business.domain.vo.MerchantTodoRegistrationVO> selectTodoRegistrationOwners(
            @Param("registrationIds") java.util.Collection<Long> registrationIds);

    /** 主题票与活动壳复制票按稳定售卖字段投影后的已支付人数。 */
    List<Map<String, Object>> selectPaidCountsByTopicTicketIncludingActivityShell(Long topicId);

    public BigDecimal selectTotalSalesByTopicId(Long topicId);

    /** 只读证据口径：主题直挂票 + 未删除活动壳票；不得作为旧现金分润基数。 */
    BigDecimal selectTotalSalesEvidenceByTopicIdIncludingActivityShell(Long topicId);

    /** B4:某主题已核销销售额(付款+核销) */
    public BigDecimal selectVerifiedSalesByTopicId(Long topicId);

    BigDecimal selectVerifiedSalesEvidenceByTopicIdIncludingActivityShell(Long topicId);

    /** B4:某主题已核销人头数(distinct 玩家) */
    public int selectVerifiedHeadsByTopicId(Long topicId);

    int selectVerifiedHeadsEvidenceByTopicIdIncludingActivityShell(Long topicId);

    /**
     * 某主题下【被指定商家亲自核销】的人头数。
     * 与 selectVerifiedHeadsByTopicId 的区别:后者是报名级布尔(任一中标商家扫一次就置 1),
     * 分不出谁接待了谁;本方法认 cms_registration_scan 里 registration_type=1 的那批
     * ——它记的是真正扫码的那个商家(ApiRegistrationController 核销分支)。
     *
     * @param topicId    主题ID
     * @param merchantId 商家 member_id(= coop_order.to_id,toType=merchant 时)
     */
    public int selectVerifiedHeadsByTopicAndMerchant(@Param("topicId") Long topicId,
                                                     @Param("merchantId") Long merchantId);

    int selectVerifiedHeadsEvidenceByTopicAndMerchantIncludingActivityShell(
            @Param("topicId") Long topicId, @Param("merchantId") Long merchantId);

    /** 某主题下【被指定商家亲自核销】的销售额,口径同 selectVerifiedHeadsByTopicAndMerchant。 */
    public BigDecimal selectVerifiedSalesByTopicAndMerchant(@Param("topicId") Long topicId,
                                                            @Param("merchantId") Long merchantId);

    BigDecimal selectVerifiedSalesEvidenceByTopicAndMerchantIncludingActivityShell(
            @Param("topicId") Long topicId, @Param("merchantId") Long merchantId);

    /** T9:某主题被计入结算的已核销报名记录 id(结算审计快照,供对账/纠纷回溯) */
    public List<Long> selectVerifiedRegistrationIdsByTopicId(Long topicId);

    List<Long> selectVerifiedRegistrationIdsEvidenceByTopicIdIncludingActivityShell(Long topicId);

    public BigDecimal selectTotalSalesByActivityId(Long activityId);


    /**
     * 查询活动/主题报名
     * 
     * @param id 活动/主题报名主键
     * @return 活动/主题报名
     */
    public CmsRegistration selectCmsRegistrationById(Long id);

    /** 事务内当前读；CAS 失败后必须用它看见竞争事务刚提交的终态，不能用 RR 快照普通读。 */
    public CmsRegistration selectCmsRegistrationByIdForUpdate(Long id);

    /** 只记录这张本人有效订单的首次合法扫码，不覆盖已有事实或核销字段。 */
    int markFirstSuccessfulScan(@Param("id") Long id, @Param("memberId") Long memberId,
                                @Param("ownerType") Long ownerType, @Param("ownerId") Long ownerId,
                                @Param("scanTime") java.util.Date scanTime);

    /** 同一源主题的一账号有效探索票；调用方先锁 cms_topic 行，再做当前读。 */
    public List<Long> selectLiveExploreTicketIdsForUpdate(@Param("topicId") Long topicId,
                                                          @Param("memberId") Long memberId);

    /**
     * 活动(owner_type=2)上仍活着的报名所引用的票 id:payment_status 0待支付/1支付中/2已支付。
     * 已售锁(2026-09-15 裁决 15)的判据:这些票的时间/集合点/价格/退款规则被锁且不许删。
     */
    public List<Long> selectOpenSaleTicketIdsByActivity(@Param("activityId") Long activityId);

    /**
     * 当前会员是否持有该主题的有效剧情访问权益。
     * ③ 自由探索票还必须有未失效的章节权益行；①/② 继续以报名主状态为真源。
     */
    public boolean hasActiveTopicStoryAccess(@Param("topicId") Long topicId,
                                             @Param("memberId") Long memberId);

    /**
     * 本人对某个主题(ownerType=1)/活动(ownerType=2)是否有报名成功的记录。
     * 判据与「带上一次游玩记录」选择器的数据源(/api/registration/my-joined:registration_status=2)同口径,
     * 用来拒绝伪造他人游玩记录的帖子引用。
     */
    public int countSucceededJoin(@Param("memberId") Long memberId,
                                  @Param("ownerType") Long ownerType,
                                  @Param("ownerId") Long ownerId);

    /** 一账号一票的全入口串行点；返回空即源 topic 不存在。 */
    public Long lockExploreTopicForUpdate(@Param("topicId") Long topicId);

    /**
     * 查询活动/主题报名列表
     * 
     * @param cmsRegistration 活动/主题报名
     * @return 活动/主题报名集合
     */
    public List<CmsRegistration> selectCmsRegistrationList(CmsRegistration cmsRegistration);

    /** 已支付报名按 (owner_id, verification_status∈{0,1}) 分组计数;ownerIds 不可为空。 */
    List<Map<String, Object>> countPaidVerificationByOwners(@Param("ownerType") Long ownerType,
                                                            @Param("ownerIds") java.util.Collection<Long> ownerIds);

    List<java.util.Map<String, Object>> selectPureContentExploreCompletion(@Param("topicId") Long topicId);

    /**
     * 新增活动/主题报名
     * 
     * @param cmsRegistration 活动/主题报名
     * @return 结果
     */
    public int insertCmsRegistration(CmsRegistration cmsRegistration);

    /** 建单事务内绑定服务端请求指纹；只允许绑定本次 requestId 对应的新行。 */
    public int bindRequestFingerprint(@Param("id") Long id,
                                      @Param("requestId") String requestId,
                                      @Param("requestFingerprint") String requestFingerprint);

    /** 已收款但报名状态未完成的历史半态，只按交易号和冻结微信金额单向修复。 */
    public int repairHistoricalPaidHalfState(@Param("id") Long id,
                                             @Param("transactionId") String transactionId,
                                             @Param("wechatPaymentAmount") java.math.BigDecimal wechatPaymentAmount,
                                             @Param("paymentTime") java.util.Date paymentTime);

    /** 退款事实唯一 claim；只有 paid 且未核销(③ 除外)的主状态可单向进入 refunded。 */
    public int claimRegistrationRefunded(@Param("id") Long id,
                                         @Param("updateBy") String updateBy,
                                         @Param("updateTime") java.util.Date updateTime);

    /** 微信支付回调「转入退款」专用:钱已退出,不看核销态。 */
    public int claimRegistrationRefundedByGateway(@Param("id") Long id,
                                                  @Param("updateBy") String updateBy,
                                                  @Param("updateTime") java.util.Date updateTime);

    /** 先 CAS 资金 marker，再在同一事务返积分；后续失败会连 marker 一起回滚。 */
    public int claimPointsReturn(@Param("id") Long id,
                                 @Param("updateBy") String updateBy,
                                 @Param("updateTime") java.util.Date updateTime);

    /**
     * 修改活动/主题报名
     * 
     * @param cmsRegistration 活动/主题报名
     * @return 结果
     */
    public int updateCmsRegistration(CmsRegistration cmsRegistration);

    /**
     * 删除活动/主题报名
     * 
     * @param id 活动/主题报名主键
     * @return 结果
     */
    public int deleteCmsRegistrationById(Long id);

    /**
     * 批量删除活动/主题报名
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteCmsRegistrationByIds(Long[] ids);


    /**
     * 查询活动/主题报名
     *
     * @param registrationNo 活动/主题报名编号
     * @return 活动/主题报名
     */
    public CmsRegistration selectByRegistrationNo(@Param("registrationNo") String registrationNo);

    public CmsRegistration selectByverificationCode(@Param("verificationCode") String verificationCode);

    /**
     * 支付成功唯一处理权：仅待支付/支付中的有效报名可原子进入已支付。
     * 返回 1 表示当前请求取得处理权；返回 0 时调用方必须回读并按终态幂等处理。
     */
    public int claimPaymentSuccess(@Param("id") Long id,
                                   @Param("transactionId") String transactionId,
                                   @Param("paymentTime") java.util.Date paymentTime);

    /** 探店日支付成功后冻结单票来源；重复回调不得覆盖已冻结事实。 */
    public int freezeTicketSourceOnPayment(@Param("id") Long id,
                                           @Param("paymentTime") java.util.Date paymentTime);

    /** 只回读支付冻结事实，避免普通报名查询无条件依赖本期新增列。 */
    public CmsRegistration selectTicketSourceFactById(@Param("id") Long id);

    /** 微信预下单前把待支付单原子推进到 PAYING；0 行必须锁定回读。 */
    public int claimPaymentProcessing(@Param("id") Long id,
                                      @Param("memberId") Long memberId,
                                      @Param("updateTime") java.util.Date updateTime);

    /** 支付失败/关单专用 CAS；只允许从待支付状态推进，禁止覆盖已支付。 */
    public int claimPaymentFailure(@Param("id") Long id,
                                   @Param("updateTime") java.util.Date updateTime);

    /** 确定未发出微信请求时释放 PAYING；0 行表示已有并发终态。 */
    public int releasePaymentProcessing(@Param("id") Long id,
                                        @Param("updateTime") java.util.Date updateTime);

    /**
     * 同一笔订单（parent_order_no）下的全部 registration 记录
     */
    public List<CmsRegistration> selectByParentOrderNo(@Param("parentOrderNo") String parentOrderNo);

    /**
     * 活动取消冻结同一父订单当前全量报名状态，避免核销/支付并发改变人工退款资格快照。
     * SQL 不预过滤 owner/activity；调用方必须在锁后逐行校验范围，避免异常行被过滤成假绿。
     */
    public List<CmsRegistration> selectByParentOrderNoForUpdate(
            @Param("parentOrderNo") String parentOrderNo,
            @Param("activityId") Long activityId);


    /** 成团退款:某票种全部已支付报名(用于未成团逐单退款) */
    public List<CmsRegistration> selectPaidByTicket(@Param("ticketId") Long ticketId);

    /**
     * 按 participate_date 统计某主题/活动每天的报名人数
     * 返回 [{participateDate, totalCount}]
     */
    public List<Map<String, Object>> selectCountGroupByDate(@Param("ownerType") Long ownerType,
                                                            @Param("ownerId") Long ownerId);

    /**
     * 自玩通行证当前占用的配额：待支付和已支付均占位，已关闭的取消/过期单不占位。
     * 调用方必须先锁定对应 cms_topic 行，以该行作为同主题建单的串行点。
     */
    public int countActiveSelfPlayPasses(@Param("topicId") Long topicId);

    /**
     * 该会员在该主题上仍"在世"的自玩通行证：已支付且未过有效期，或待支付且付款窗口未过。
     * 会员级幂等的判据 —— 没有它，连点两下就是两张待支付通行证、两次扣款、两份 90 天权益。
     * 已支付的排在前面，调用方据此区分"已持有权益"(拒)与"未付款订单"(复用)。
     */
    public List<CmsRegistration> selectLiveSelfPlayPasses(@Param("topicId") Long topicId,
                                                          @Param("memberId") Long memberId);

    /** P0-10 弃单关单:pay_expire_time 已过且仍待支付的报名单 id(限批 200/轮) */
    public List<Long> selectExpiredUnpaidIds(@Param("now") java.util.Date now);
    public Long selectExpiredUnpaidMaxId(@Param("now") java.util.Date now);
    public List<Long> selectExpiredUnpaidIdsAfter(@Param("now") java.util.Date now,
            @Param("afterId") long afterId, @Param("maxId") long maxId, @Param("limit") int limit);

    /** §5-3 PAYING 超时对账:预下单已发起(payment_status=1)且过了付款窗口的单 id(限批 200/轮) */
    public List<Long> selectExpiredPayingIds(@Param("now") java.util.Date now, @Param("afterId") long afterId);

    /** 核销码补偿:已支付但核销码为空的报名单 id(近7天,限批 100/轮) */
    public List<Long> selectPaidMissingQrcodeIds();
    public Long selectPaidMissingQrcodeMaxId();
    public List<Long> selectPaidMissingQrcodeIdsAfter(@Param("afterId") long afterId,
            @Param("maxId") long maxId, @Param("limit") int limit);

    /**
     * RUN-01 到期自动核销:已支付未核销、所属活动结束满 bufferDays 的场次票(owner_type=2)id,每页限批 200。
     * 退款在途(status 0/1)与缺 end_date 锚点的单不扫(fail-closed 转人工),口径详见 XML 注释。
     */
    public List<Long> selectExpiredUnverifiedIds(@Param("now") java.util.Date now,
                                                 @Param("bufferDays") int bufferDays);

    /** 保持相同到期判据，按 id 升序读取 afterId 之后的下一页(最多 200 单)。 */
    public List<Long> selectExpiredUnverifiedIdsAfter(@Param("now") java.util.Date now,
            @Param("bufferDays") int bufferDays, @Param("afterId") long afterId);

    /**
     * RUN-01 到期自动核销:条件置位(0 行 = 竞态下已被商家真核销/进入退款流/活动被改期,调用方跳过)。
     * 全部判据下推进本语句当前读,调用方**不许**先 select 再判(RR 陷阱见 XML 注释)。
     * updateBy 必须是任务来源标识 —— 自动收口与人工核销要在库里可区分。
     */
    public int autoVerifyExpiredSession(@Param("id") Long id, @Param("updateBy") String updateBy,
                                        @Param("bufferDays") int bufferDays);

    /** P0-10 弃单关单:条件关单(仍待支付才关;0 行 = 竞态下已支付/已取消,调用方跳过)。
     *  updateBy 由调用方带入 —— 关单来源(弃单任务 / 对账任务 / 主办方取消活动)必须在库里可区分。
     *  finalStatus:落库的 registration_status(3=已取消,4=已过期),真实原因由调用方决定。 */
    public int closeExpired(@Param("id") Long id, @Param("updateBy") String updateBy,
                            @Param("finalStatus") int finalStatus);

    /** §5-3 PAYING 超时对账:条件关单(仍处支付中才关;0 行 = 竞态下已支付/已变化,调用方跳过)。
     *  ⚠️ 调用前必须已向微信确认未支付,见 OrderCloseTask.reconcileExpiredPaying。
     *  finalStatus:落库的 registration_status(3=已取消,4=已过期),真实原因由调用方决定。 */
    public int closeExpiredPaying(@Param("id") Long id, @Param("updateBy") String updateBy,
                                  @Param("finalStatus") int finalStatus);

    /** 取消未支付报名唯一处理权；同一 SQL 同时固化积分已返标记。 */
    public int cancelUnpaidIfActive(@Param("id") Long id,
                                    @Param("memberId") Long memberId,
                                    @Param("updateBy") String updateBy);

    /**
     * P0-21 核销条件置位:仅未核销才置核销态(0 行 = 已被他人核销,调用方须拒)。
     * 同一 topic 的 (topic, node) 竞争位允许多个商家同时中标,他们都能过核销入口的中标闸,
     * 靠读判会在并发双扫下漏过,故防线必须在这条 where 上。
     */
    public int verifyIfNotVerified(@Param("id") Long id, @Param("verifierId") Long verifierId);

    /** 锁定报名行并当前读其活跃人工退款工单，与工单创建共用并发串行点。 */
    public boolean hasActiveManualRefundCaseForUpdate(@Param("id") Long id);

    /**
     * 撤销核销后清回未核销态({@code verifyIfNotVerified} 的反向)。
     *
     * <p><b>为什么需要它</b>:③ 首次核销就置 {@code verification_status=1}(退款闸的前提)。
     * 若撤销核销(店员误扫)不清位,这张票会永久停在「已核销」:退款被
     * {@code RefundPolicy.verifiedVerdict} 锁死、注销时被当成已消费而隐形 ——
     * 为一笔**已经不存在**的核销。置位与清位必须成对。
     *
     * <p>⚠️ 调用方须先确认该票**已无任何 ACTIVE 核销**(撤销第 1 章但第 2/3 章仍核销着时,
     * verification_status 必须保持 1)。判据在 {@code PerkLedgerServiceImpl.reverseRedemption}。
     */
    public int clearVerification(@Param("id") Long id);

    /** PII 加密历史回填:未迁移(phone_hash 为空且有手机号)的报名单 id,限批。 */
    public List<Long> selectLegacyPiiIds(@Param("limit") int limit);

    /** PII 加密历史回填:按 id 用密文覆盖 PII 列 + 写盲索引(值由调用方解密后传入,typeHandler 负责加密)。 */
    public int backfillRegistrationPii(@Param("id") Long id,
                                       @Param("realName") String realName,
                                       @Param("phone") String phone,
                                       @Param("email") String email,
                                       @Param("address") String address);

}
