package com.chengyinhub.business.mapper;

import java.util.Date;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.chengyinhub.business.domain.VideoBountySubmission;

/**
 * 视频悬赏投稿 Mapper。
 *
 * <p>去重靠唯一键 {@code uk_video_canonical}:同一活动内同一规范化视频键只能有一条;
 * 调用方必须容忍 {@link org.springframework.dao.DuplicateKeyException} = "已投稿"。
 *
 * @author chengyinhub
 */
public interface VideoBountySubmissionMapper
{
    VideoBountySubmission selectById(Long id);

    VideoBountySubmission selectByIdForUpdate(Long id);

    /** 同一活动内按规范化视频键查(唯一键保证至多一条)。 */
    VideoBountySubmission selectByCanonicalKey(@Param("campaignId") Long campaignId,
                                               @Param("canonicalVideoKey") String canonicalVideoKey);

    /**
     * 同一作者在<b>任意活动</b>里是否已投过这条视频。
     *
     * <p>唯一键只挡同活动重复;跨活动"换活动再投一次"必须另查,否则封顶按组计算
     * 会被拆成多个活动绕开。返回首条命中行(没有则 null)。
     */
    VideoBountySubmission selectByMemberAndCanonical(@Param("memberId") Long memberId,
                                                     @Param("canonicalVideoKey") String canonicalVideoKey);

    List<VideoBountySubmission> selectByMember(@Param("memberId") Long memberId,
                                               @Param("campaignId") Long campaignId);

    /** 同组投稿(封顶按组合计)。只取仍有效(未撤销/未取消/未拒绝)的行。 */
    List<VideoBountySubmission> selectActiveByGroup(@Param("campaignId") Long campaignId,
                                                    @Param("originGroupKey") String originGroupKey);

    /**
     * 该活动下是否存在这个主题的<b>已通过</b>投稿。目标 scope 判定用。
     *
     * <p>为什么不用分页列表判断:分页有上限,活动投稿多时会把合法的主题判成越界,
     * 那是"因为数据多而拒绝"的隐蔽错误。这里用一次 COUNT 精确回答。
     */
    int countApprovedByCampaignAndTopic(@Param("campaignId") Long campaignId,
                                        @Param("topicId") Long topicId);

    /**
     * 按<b>运营核验过的</b>原视频组取有效投稿（封顶/应计的唯一分组来源）。
     *
     * <p>{@code verified_group_key IS NULL} 的行不属于任何组 —— 未核验就不生成确定应计，
     * 作者自报的 {@code origin_group_key} 不再参与计算。
     */
    List<VideoBountySubmission> selectActiveByVerifiedGroup(@Param("campaignId") Long campaignId,
                                                            @Param("verifiedGroupKey") String verifiedGroupKey);

    /**
     * CAS 写入核验结论：只有 {@code verified_group_version = expectedVersion} 才生效。
     *
     * @return 1=本次写入；0=版本已变（并发核验或重投），由服务层读回判定是否幂等
     */
    int markGroupVerified(@Param("id") Long id,
                          @Param("verifiedGroupKey") String verifiedGroupKey,
                          @Param("verifiedBy") Long verifiedBy,
                          @Param("verifiedAt") Date verifiedAt,
                          @Param("verifiedSourceRef") String verifiedSourceRef,
                          @Param("expectedVersion") Integer expectedVersion);

    List<VideoBountySubmission> selectAdminPage(@Param("campaignId") Long campaignId,
                                                @Param("status") Integer status,
                                                @Param("offset") int offset,
                                                @Param("limit") int limit);

    int insertSubmission(VideoBountySubmission submission);

    /**
     * CAS 审核通过:仅 status=SUBMITTED 或 UNDER_REVIEW 可置 APPROVED。
     *
     * @return 1=本次由我置位;0=已被别人改过
     */
    int markApprovedIfPending(@Param("id") Long id,
                              @Param("publishedAt") Date publishedAt,
                              @Param("windowEndsAt") Date windowEndsAt,
                              @Param("reviewedBy") String reviewedBy,
                              @Param("reviewedAt") Date reviewedAt,
                              @Param("reviewNote") String reviewNote);

    /** CAS 拒绝:仅 SUBMITTED/UNDER_REVIEW 可置 REJECTED。 */
    int markRejectedIfPending(@Param("id") Long id,
                              @Param("reviewedBy") String reviewedBy,
                              @Param("reviewedAt") Date reviewedAt,
                              @Param("reviewNote") String reviewNote);

    /** CAS 提出申诉:仅 REJECTED 可置 APPEALING。 */
    int markAppealingIfRejected(@Param("id") Long id,
                                @Param("memberId") Long memberId,
                                @Param("reviewNote") String note);

    /** CAS 申诉改判:仅 APPEALING 可置 APPROVED。 */
    int markApprovedIfAppealing(@Param("id") Long id,
                                @Param("publishedAt") Date publishedAt,
                                @Param("windowEndsAt") Date windowEndsAt,
                                @Param("reviewedBy") String reviewedBy,
                                @Param("reviewedAt") Date reviewedAt,
                                @Param("reviewNote") String reviewNote);

    /** CAS 撤销:仅 APPROVED 可置 REVOKED(应计随后作废)。 */
    int markRevokedIfApproved(@Param("id") Long id,
                              @Param("reviewedBy") String reviewedBy,
                              @Param("reviewedAt") Date reviewedAt,
                              @Param("reviewNote") String reviewNote);

    /** CAS 作者取消:仅 SUBMITTED/UNDER_REVIEW/REJECTED/APPEALING 可置 CANCELLED。 */
    int markCancelledByOwner(@Param("id") Long id, @Param("memberId") Long memberId);
}
