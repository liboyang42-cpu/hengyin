package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.vo.ClubCustomerVO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 俱乐部客户(按人聚合)查询。
 *
 * ⚠️ 这里返回的 Raw* 类型带 ums_member.phone 明文,只允许 Service 消费,不得直接下发。
 * 手机号脱敏统一在 ClubCrmAppServiceImpl 做(PiiCryptoService.maskPhone),不在 SQL 里各写一份。
 * cms_registration.phone 是 AES-GCM 密文列,取它会得到一串密文,所以一律读 ums_member.phone。
 */
public interface ClubCrmMapper {

    List<ClubCustomerVO.RawCustomer> selectClubCustomers(@Param("clubId") Long clubId,
                                                         @Param("filter") String filter,
                                                         @Param("keyword") String keyword,
                                                         @Param("limit") Integer limit);

    int countClubCustomers(@Param("clubId") Long clubId);

    /** 本月新增:首次在本俱乐部下单落在本月的人数。 */
    int countClubCustomersNewThisMonth(@Param("clubId") Long clubId);

    ClubCustomerVO.RawCustomer selectClubCustomerSummary(@Param("clubId") Long clubId,
                                                         @Param("memberId") Long memberId);

    List<ClubCustomerVO.Record> selectClubCustomerRecords(@Param("clubId") Long clubId,
                                                          @Param("memberId") Long memberId,
                                                          @Param("limit") Integer limit);

    List<ClubCustomerVO.RawTopicRow> selectTopicCustomers(@Param("clubId") Long clubId,
                                                          @Param("topicId") Long topicId);

    ClubCustomerVO.Profile selectProfile(@Param("clubId") Long clubId,
                                         @Param("memberId") Long memberId);

    List<ClubCustomerVO.Profile> selectProfiles(@Param("clubId") Long clubId);

    int upsertProfile(@Param("clubId") Long clubId,
                      @Param("memberId") Long memberId,
                      @Param("tagsJson") String tagsJson,
                      @Param("remark") String remark,
                      @Param("updatedBy") Long updatedBy,
                      @Param("requestId") String requestId);

    /**
     * 第二轮拍板第 1 条:俱乐部主题管理向统计(站数/本场人数/待核销/我核销的)。
     * clubId 进 where:主题不属于本俱乐部时返回 null。activityId 为空 = 整个主题口径。
     */
    java.util.Map<String, Object> selectTopicManageStats(@Param("clubId") Long clubId,
                                                         @Param("topicId") Long topicId,
                                                         @Param("activityId") Long activityId,
                                                         @Param("viewerId") Long viewerId);

    /** Figma K3 核销详情:一条报名单的凭证快照。clubId 进 where,不是查完再比归属。 */
    ClubCustomerVO.RawCheckinRow selectCheckinDetail(@Param("clubId") Long clubId,
                                                     @Param("registrationId") Long registrationId);
}
