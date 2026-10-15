package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.MerchantChapterApplication;
import com.chengyinhub.business.domain.vo.MerchantChapterRecruitmentStats;

import java.util.List;
import java.util.Map;

/** 章节招商申请：商家提交意向，主题原发布者审核。 */
public interface IMerchantChapterApplicationService {

    MerchantChapterApplication apply(Long chapterId, Long merchantId, String message);

    /** 商家撤回自己的待审申请；重复撤回幂等，已通过或已拒绝不可撤。 */
    MerchantChapterApplication withdraw(Long applicationId, Long merchantId);

    MerchantChapterApplication respondInvite(Long applicationId, Long merchantMemberId, boolean accepted);

    MerchantChapterApplication revokeInvite(Long applicationId, Long publisherMemberId);

    /**
     * 主办方邀请某商家承接本章节。
     *
     * <p>★邀请<b>不另建一套链路</b>:它就是「预先批准的申请」—— 直接建一条
     * {@code status=已通过 / source=邀请} 的申请,商家在「我的申请」里看到后
     * 直接填供给 + 点位。申请/供给/点位三段全部复用。
     * (为章节另开一套 coop_invite 是重复建设 —— 那张表连 chapter_id 都没有。)
     *
     * <p>邀请来的点位<b>免审</b>:人是主办方主动邀的,再让他审自己邀的人没有意义。
     *
     * @param publisherMemberId 当前登录用户,必须是该章节所属主题的发布者
     * @param merchantMemberId  被邀商家
     */
    /**
     * 本章节可邀请的商家(给主办方选人用)。
     *
     * <p><b>按主题一次列全,不让主办方先选章节</b> —— 承接标的虽然是章节,但章节是
     * <b>按商家品类推导出来的</b>({@code assertMatchingActiveMerchant} 要求品类一致),
     * 让人先选一遍等于把系统能算出来的事推给用户。所以这里返回「商家 × 匹配到的章节」,
     * 章节只作为信息显示。同一商家匹配到多个章节时按对出多行,每行的邀请都是明确的。
     *
     * <p>筛选口径与 {@code assertMatchingActiveMerchant} <b>完全一致</b>:同品类 + 审核通过 + 启用。
     * 两处若各写一套,会出现「列表里选得到、点邀请被拒」这种自相矛盾的体验。
     * 已有申请/邀请的组合排除掉 —— 列出来点下去也只会撞「已有申请」。
     *
     * @return 每项含 memberId / name / logo / address / chapterId / chapterName / distance
     */
    java.util.List<java.util.Map<String, Object>> invitableMerchants(Long topicId, Long publisherMemberId);

    /**
     * 同上,但带主办方当前坐标 ⇒ 整份名单按直线距离从近到远排(无坐标商家垫底)。
     * 主办方找人本来就是先看「谁离得近」,按章节分段的名单没法回答这个问题。
     * 距离复用商家发现(E5)那条 {@code selectMerchantListByDistance},口径一致。
     */
    java.util.List<java.util.Map<String, Object>> invitableMerchants(Long topicId, Long publisherMemberId,
                                                                     Double originLat, Double originLng);

    MerchantChapterApplication invite(Long chapterId, Long publisherMemberId, Long merchantMemberId);

    MerchantChapterApplication invite(Long chapterId, Long publisherMemberId, Long merchantMemberId, String message);
    MerchantChapterApplication invite(Long chapterId, Long publisherMemberId, Long merchantMemberId,
                                      Long replacementOfOfferId);

    /** 已批准补位邀请的原供给，仅供服务端入驻确认读取。 */
    Long approvedReplacementOfferId(Long chapterId, Long merchantId);

    MerchantChapterApplication invite(Long chapterId, Long publisherMemberId, Long merchantMemberId,
                                      String message, Long replacementOfOfferId);

    MerchantChapterApplication audit(Long applicationId, Long publisherMemberId, boolean approved, String auditRemark);

    /** 新招商章节的入驻授权源：必须有本章已通过申请。 */
    boolean hasApprovedApplication(Long chapterId, Long merchantId);

    /** 已通过申请不是永久资格；入驻时须按当前商家状态和品类重新校验。 */
    void assertCurrentMerchantEligibility(CmsTopicChapter chapter, Long merchantId);

    /** 模板/发现页的匿名统计，不下发申请商家身份或谈判字段。 */
    MerchantChapterRecruitmentStats getRecruitmentStats(Long chapterId, Integer maxMerchant);

    /**
     * 批量版 {@link #getRecruitmentStats}:整页主题的章节一次算完,<b>固定两次查询</b>
     * (一次申请、一次生效供给),与章节数、主题数无关。
     *
     * <p>列表页必须用这个 —— 逐章调用单章版会变成 N×3 次查询。
     * 占用口径(已通过申请 ∪ 已生效供给,按商家去重)与单章版复用同一份实现,不另写一遍。
     *
     * @param maxMerchantByChapterId 章节 id → 该章容量(null/0 表示不限)
     * @return 章节 id → 统计;入参为空时返回空 Map
     */
    Map<Long, MerchantChapterRecruitmentStats> getRecruitmentStatsByChapters(
            Map<Long, Integer> maxMerchantByChapterId);

    /** 谁发布谁审核：在服务层校验 topic.memberId 后返回。 */
    List<MerchantChapterApplication> selectForTopic(Long topicId, Long publisherMemberId);

    List<MerchantChapterApplication> selectForPublisher(Long publisherMemberId);

    List<MerchantChapterApplication> selectMine(Long merchantId);
}
