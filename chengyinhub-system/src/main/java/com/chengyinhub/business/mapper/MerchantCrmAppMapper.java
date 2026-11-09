package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MerchantCrmCustomerSummary;
import com.chengyinhub.business.domain.MerchantCrmInteraction;
import com.chengyinhub.business.domain.MerchantCrmNote;
import com.chengyinhub.business.domain.MerchantCrmTagAssignment;
import com.chengyinhub.business.domain.MerchantCrmCampaignCandidate;
import com.chengyinhub.business.domain.MerchantCrmBroadcastCandidate;
import com.chengyinhub.business.domain.dto.MerchantCrmCustomerQuery;
import com.chengyinhub.business.domain.vo.MerchantCrmCustomerRowVO;
import com.chengyinhub.business.domain.vo.MerchantCrmSegmentCountsVO;
import com.chengyinhub.business.domain.vo.MerchantCrmTagVO;
import com.chengyinhub.business.domain.vo.MerchantCrmTimelineItemVO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface MerchantCrmAppMapper {
    List<MerchantCrmCampaignCandidate> selectOwnedCampaignRecipientCandidates(
            @Param("ownerMemberId") Long ownerMemberId,
            @Param("query") MerchantCrmCustomerQuery query,
            @Param("limit") Integer limit);

    List<MerchantCrmCustomerRowVO> selectOwnedCustomerPage(
            @Param("ownerMemberId") Long ownerMemberId,
            @Param("query") MerchantCrmCustomerQuery query,
            @Param("offset") Integer offset,
            @Param("limit") Integer limit);

    /** 定向广播候选:与触达名单同源,多带队伍/角色;scope/groups 收窄在服务层做。 */
    List<MerchantCrmBroadcastCandidate> selectOwnedBroadcastCandidates(
            @Param("ownerMemberId") Long ownerMemberId,
            @Param("query") MerchantCrmCustomerQuery query,
            @Param("limit") Integer limit);

    /** F15 单客联系号码读取:归属/负责判定与列表同源,只收窄到一位客户。 */
    MerchantCrmCustomerRowVO selectOwnedCustomerContact(
            @Param("ownerMemberId") Long ownerMemberId,
            @Param("customerMemberId") Long customerMemberId);

    long selectOwnedCustomerCount(@Param("ownerMemberId") Long ownerMemberId,
                                  @Param("query") MerchantCrmCustomerQuery query);

    MerchantCrmSegmentCountsVO selectOwnedSegmentCounts(
            @Param("ownerMemberId") Long ownerMemberId,
            @Param("query") MerchantCrmCustomerQuery query);

    int upsertOwnedCustomerProfile(@Param("merchantId") Long merchantId,
                                   @Param("ownerMemberId") Long ownerMemberId,
                                   @Param("customerMemberId") Long customerMemberId);

    int upsertOwnedCustomerProfiles(@Param("merchantId") Long merchantId,
                                    @Param("ownerMemberId") Long ownerMemberId);

    Long selectCustomerProfileForUpdate(@Param("merchantId") Long merchantId,
                                        @Param("customerMemberId") Long customerMemberId);

    int markStaleCustomerProfiles(@Param("merchantId") Long merchantId,
                                  @Param("ownerMemberId") Long ownerMemberId);

    long countOwnedRegistrationCustomers(@Param("ownerMemberId") Long ownerMemberId);

    long countActiveCustomerProfiles(@Param("merchantId") Long merchantId);
    MerchantCrmCustomerSummary selectOwnedCustomerSummary(
            @Param("ownerMemberId") Long ownerMemberId,
            @Param("customerMemberId") Long customerMemberId);

    List<MerchantCrmInteraction> selectOwnedInteractions(
            @Param("ownerMemberId") Long ownerMemberId,
            @Param("customerMemberId") Long customerMemberId,
            @Param("limit") Integer limit);

    List<MerchantCrmTagVO> selectCustomerTags(@Param("merchantId") Long merchantId,
                                               @Param("customerMemberId") Long customerMemberId);

    List<MerchantCrmTimelineItemVO> selectCustomerCampaignTimeline(
            @Param("merchantId") Long merchantId,
            @Param("customerMemberId") Long customerMemberId,
            @Param("limit") Integer limit);

    int countCustomerTags(@Param("merchantId") Long merchantId,
                          @Param("customerMemberId") Long customerMemberId);

    int countCustomerTag(@Param("merchantId") Long merchantId,
                         @Param("customerMemberId") Long customerMemberId,
                         @Param("tagId") Long tagId);

    MerchantCrmTagAssignment selectTagAssignmentByRequestId(
            @Param("merchantId") Long merchantId,
            @Param("requestId") String requestId);

    int insertTagAssignment(MerchantCrmTagAssignment assignment);

    List<MerchantCrmNote> selectCustomerNotes(@Param("merchantId") Long merchantId,
                                               @Param("customerMemberId") Long customerMemberId,
                                               @Param("limit") Integer limit);

    MerchantCrmNote selectNoteByRequestId(@Param("merchantId") Long merchantId,
                                          @Param("requestId") String requestId);

    MerchantCrmNote selectNoteById(@Param("merchantId") Long merchantId,
                                    @Param("customerMemberId") Long customerMemberId,
                                    @Param("noteId") Long noteId);

    int insertNote(MerchantCrmNote note);

    int hideNoteCas(@Param("merchantId") Long merchantId,
                    @Param("customerMemberId") Long customerMemberId,
                    @Param("noteId") Long noteId,
                    @Param("expectedVersion") Integer expectedVersion,
                    @Param("actorMemberId") Long actorMemberId,
                    @Param("requestId") String requestId);
}
