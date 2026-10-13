package com.chengyinhub.business.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.RegistrationEntitlement;

/**
 * 每票每必选章权益 Mapper 接口(施工图 §3.2 · 容量预占真源)
 */
public interface RegistrationEntitlementMapper {

    List<Long> selectAffectedPaidMembers(@Param("topicId") Long topicId, @Param("chapterId") Long chapterId);

    public int insertRegistrationEntitlement(RegistrationEntitlement e);

    /** 某票的全部章节权益(含已核销/已失效):玩家侧「我的权益」列表要看得见全貌 */
    public List<RegistrationEntitlement> selectByRegistration(@Param("registrationId") Long registrationId);

    /** 售后冻结采用当前读，与核销共用报名行锁。 */
    List<RegistrationEntitlement> selectByRegistrationForUpdate(@Param("registrationId") Long registrationId);

    /** 取某票某章的权益行;靠 uk_entitlement(registration_id, chapter_id) 保证至多一行 */
    public RegistrationEntitlement selectOne(@Param("registrationId") Long registrationId,
                                            @Param("chapterId") Long chapterId);

    /**
     * 某章节「已售未核销」条数(status=0)。
     * 这是容量口径的核心:票一卖出平台就已欠玩家这份权益 ⇒ **未核销也占容量**,
     * 判断章节还能不能再卖必须减掉它,只看已核销数会把已售未核销的份数重复卖出去。
     */
    public int countUnredeemedByChapter(@Param("chapterId") Long chapterId);

    /**
     * 同上,但走**当前读**(FOR SHARE)—— 购票容量校验专用,理由见
     * {@link MerchantChapterOfferMapper#sumRemainingQuotaByChapterForUpdate}。
     * 简言之:RR 下普通读吃快照,行锁挡不住「锁后读不到对手已提交的写」⇒ 超卖。
     */
    public int countUnredeemedByChapterForUpdate(@Param("chapterId") Long chapterId);

    /**
     * 核销时条件置位为已得,返回 0 = 这张票这一章本就不是「应得」态(没买/已核销/已退款)。
     * 条件带 status=0 而不是无条件 update:并发/重放下只有第一次能拿到 1,后来者拿 0 被挡,
     * 调用方据此决定是否真的发权益。
     */
    public int markRedeemed(@Param("registrationId") Long registrationId,
                            @Param("chapterId") Long chapterId);

    /**
     * 退款/取消:把该票未核销的权益全部失效以释放容量。
     * 只动 status=0 的 —— 已核销(status=1)的权益玩家已经拿到手,改成失效会让容量凭空多出来一份。
     */
    public int invalidateByRegistration(@Param("registrationId") Long registrationId,
                                 @Param("reason") String reason);

    /**
     * 撤销核销后把权益退回「应得」态,玩家可重新核销。
     *
     * ⚠️ 只能 UPDATE 不能 INSERT:uk_entitlement 是 (registration_id, chapter_id) 且**不含 status**,
     * 行永久占坑,重插必 1062。这与 redemption 恰好相反(那边撤销后生成列变 NULL、键释放、可重插)——
     * 两张表的重发语义相反,是这一组表最容易写错的地方。
     *
     * @return 0 行 = 不是已核销态(并发下已被别人退款/撤销),调用方须当竞态处理
     */
    public int restoreToPending(@Param("registrationId") Long registrationId,
                                @Param("chapterId") Long chapterId);
}
