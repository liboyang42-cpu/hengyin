package com.chengyinhub.business.service;

import java.util.List;
import com.chengyinhub.business.domain.CoopInvite;

/**
 * 协作邀请 Service 接口(P1.5)
 */
public interface ICoopInviteService {

    public CoopInvite selectCoopInviteById(Long id);

    public List<CoopInvite> selectCoopInviteList(CoopInvite coopInvite);

    /** 合作列表专用只读投影：过期待确认映射为5，不持久化终态。 */
    public List<CoopInvite> selectCoopInviteListReadOnly(CoopInvite coopInvite);

    /** 当前商家收到的官方邀约，补齐活动名与承接状态后返回。 */
    public java.util.List<java.util.Map<String, Object>> listOfficialMerchantInvites(Long merchantMemberId);

    /** 公开主页展示：仅返回该商家已中标的官方邀约活动。 */
    public java.util.List<java.util.Map<String, Object>> listAwardedOfficialActivities(Long merchantMemberId);

    public int insertCoopInvite(CoopInvite coopInvite);

    /** Keyed creation only: the callback contains first-create API/content checks, never replay checks. */
    com.chengyinhub.business.domain.dto.CoopInviteRequestResult createOrReplayInvite(
            Long principalMemberId, CoopInvite invite, Runnable beforeCreate);
    com.chengyinhub.business.domain.dto.CoopInviteRequestResult findInviteRequest(Long principalMemberId, String requestId);
    java.util.Map<String, Object> listInviteRequests(Long principalMemberId, Long before, int limit);

    /** 官方白名单账号批量向商家发承接邀约，返回新邀约 ID。 */
    public List<Long> createOfficialMerchantInvites(Long officialMemberId,
            com.chengyinhub.business.domain.dto.OfficialMerchantInviteRequest request);

    /** 锁价成功后的保证金副作用:已接受且未缴的合作转应缴,并 best-effort 通知实际应缴方。 */
    public void markTopicDepositsDue(Long topicId);

    public int updateCoopInvite(CoopInvite coopInvite);

    /**
     * 双死线①:扫描并过期挂起超招募截止的邀约,返回过期条数。已接受邀约不在此过期(阻断#8)。
     * 由 CoopInviteExpireTask 定时调用。
     */
    public int expireStaleInvites();

    /**
     * B1-4 同意路径:行锁 + 容量复核 + 生成合作单 + 顶替同槽其他挂起(status=4)。
     * 经典定向(带 game_id)每游戏已接受=1;自由定向每主题已接受≤5,不写 config_deadline(阻断#8)。
     * 容量/状态不满足时抛 IllegalStateException(message 面向用户)。
     * @param nodeId type2 节点意向(可空)
     * @return 已接受的邀约
     */
    public CoopInvite acceptInviteWithLock(Long inviteId, Long nodeId);

    /** Explicit confirmation of the original persisted supply station; no replacement on accept. */
    CoopInvite acceptInviteWithLock(Long inviteId, Long nodeId, Long confirmedSupplyStationId);
    /** Creator-scoped classic supplier picker; caller passes only its server-resolved contract owner. */
    List<com.chengyinhub.business.domain.CmsRegistrationMerchant> listSupplyStations(
            Long topicId, Long merchantMemberId, Long creatorMemberId);


    /** 官方邀约接受：锁邀约并幂等创建/复用商家承接档案，返回档案 ID。 */
    public Long acceptOfficialInviteWithLock(Long inviteId, Long merchantMemberId);

    /** 官方邀约拒绝：锁邀约并校验受邀商家，避免与接受并发互相覆盖。 */
    public void rejectOfficialInviteWithLock(Long inviteId, Long merchantMemberId, String reason);

    /** 普通邀约拒绝：行锁内复核待确认状态与受邀方主体。 */
    public void rejectPendingInviteWithLock(Long inviteId, Long operatorMemberId, String reason);

    /** 普通待确认邀约取消：行锁内复核发起方主体，避免覆盖并发接受。reason 落 handle_reason,双方详情可读回。 */
    public void cancelPendingInviteWithLock(Long inviteId, Long operatorMemberId, String reason);

    /**
     * B1-5 取消已接受的合作:锁价前行锁置 status=3 + 写信用记录;锁价后拒绝并转客服协商。
     * @param creditEvent 当前取消信用事件
     */
    public void cancelAcceptedInvite(Long inviteId, Long operatorMemberId, String reason, int creditEvent);

    /**
     * B4 近90天信用摘要:各事件计数 + 履约率(无记录默认 100%)。
     * @return map: days/fulfil/timeout/cancelOutside/violateInside/violationCount/fulfillmentRate
     */
    public java.util.Map<String, Object> creditSummary(Long memberId);

    /** B3:候选池确认报名占槽时落地合作单(coop_order),返回主键 */
    public Long createCoopOrder(com.chengyinhub.business.domain.CoopOrder order);

    /** F4:某商家的结算记录(工作台) */
    public java.util.List<com.chengyinhub.business.domain.CoopSettlement> mySettlements(Long merchantId);

    /** T7:俱乐部/发布者财务视图(按主题聚合我的分润/商家应收/打款状态/到账时间) */
    public java.util.List<java.util.Map<String, Object>> clubFinance(Long memberId);

    /** F1-3:商家常备权益模板 */
    public java.util.List<com.chengyinhub.business.domain.CoopPerkTemplate> listPerkTemplates(Long merchantId);
    public Long savePerkTemplate(com.chengyinhub.business.domain.CoopPerkTemplate t);
    public int deletePerkTemplate(Long id, Long merchantId);

    /** F4-2:双向互评。已评过抛 IllegalStateException */
    public void saveReview(com.chengyinhub.business.domain.CoopReview r);
    public java.util.Map<String, Object> reviewSummary(Long toId);
    public java.util.List<com.chengyinhub.business.domain.CoopReview> listReviews(Long toId);

    /** 供给勾选:受邀方(=inv.toId)从常备权益模板申报本次合作供给,复制进 coop_perk;返回申报条数 */
    public int attachPerks(Long inviteId, Long callerMemberId, java.util.List<Long> templateIds);
    public java.util.List<com.chengyinhub.business.domain.CoopPerk> listPerks(Long inviteId);

    /**
     * 开放招商章节:从当前商家自己的常备权益模板生成一份独立供给快照。
     * 返回的 {@code coop_perk.id} 由 merchant_chapter_offer 引用；它不挂 coop_invite，避免把模板 ID
     * 错当成邀约权益 ID，也避免后续修改模板追溯改变已生效供给。
     */
    public Long materializeStandalonePerkFromTemplate(Long templateId, Long merchantId, Integer quota);

    /**
     * 该 coop_perk 的申报方 memberId。独立供给读 merchant_id；邀约供给则商家承接=inv.toId、
     * 俱乐部承接=主理人。查不到返 null。
     *
     * <p>入驻章节时必须用它做 IDOR 校验:否则商家 A 能拿商家 B 那份高零售价权益去过章节门槛,
     * 自己端便宜货。
     * ⚠️ 受邀方解析(club 要落到主理人)是既有口径,别在调用方复制一份 —— 两份必分叉。
     */
    public Long resolvePerkOwner(Long perkId);

    // —— 保证金(锁价后应缴:type0/type2 与无溯源 type1 由受邀方缴,有溯源 type1 由发起方缴;完成退,欠缴从应收抵扣)——

    /** ¥50 固定保证金额 */
    public static final java.math.BigDecimal DEPOSIT_AMOUNT = new java.math.BigDecimal("50.00");

    /**
     * 锁价后的缴纳前置:校验调用者=应缴方 + lifecycle≥3 + deposit_status=5,生成 DEP 商户订单号并置支付中。
     * @return {outTradeNo, amount, payerMemberId};未锁价/已缴/无权抛 IllegalStateException。
     */
    public java.util.Map<String, Object> prepareDeposit(Long inviteId, Long callerMemberId);

    /** 退款重试前置授权：依次锁主题、邀约并返回退款资格的权威快照；无权或状态不符时抛异常。 */
    public CoopInvite authorizeDepositRefundRetry(Long inviteId, Long operatorMemberId);

    /** 应缴方:type0/type2=受邀方,type1 无溯源=受邀方,type1 有开放报名溯源=发起方。 */
    public Long depositOwedParty(CoopInvite invite);

    /** 兼容查单确认：先校验原单号/币种/金额/交易号，再将保证金置为已缴冻结(2) */
    public boolean markDepositPaid(String outTradeNo);

    /** 客服仲裁等显式扣款路径:仅已缴冻结保证金可置已扣(3)。正常取消不调用。 */
    public int deductDeposit(Long inviteId);

    /** 兼容旧调用：只查既有退款号的可信渠道 SUCCESS；不得凭受理直接置4。 */
    public int settleDepositRefunded(Long inviteId, String refundNo);

    /** Exact verified SUCCESS reference; original paid CAS and any recovery ticket complete in one transaction. */
    boolean markDepositPaid(String outTradeNo, Long paidCents, String transactionId,
            com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference reference);
    void requestDepositRefund(Long inviteId, Long operatorMemberId);
    void requestTopicDepositRefunds(Long topicId);
    boolean handleDepositRefundResult(java.util.Map<String,String> facts);
    void reconcileDepositRefunds();
}
