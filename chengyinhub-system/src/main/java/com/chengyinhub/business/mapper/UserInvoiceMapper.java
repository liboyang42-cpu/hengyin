package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.UserInvoiceApplication;
import com.chengyinhub.business.domain.UserInvoiceOrderLine;
import com.chengyinhub.business.domain.UserInvoiceTitle;
import com.chengyinhub.business.domain.CmsRegistration;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;
import java.util.Map;

/** DOC2 F7 用户发票。所有会员侧读写都带 member_id，越权读写在 SQL 层就命中 0 行。 */
public interface UserInvoiceMapper {
    CmsRegistration selectInvoiceRegistrationById(@Param("id") Long id);
    List<Long> resolveInvoiceIssuers(@Param("orderIds") List<Long> orderIds);
    List<Map<String,Object>> selectInvoiceIssuerPreviews(@Param("orderIds") List<Long> orderIds);
    List<Map<String, Object>> selectMemberReconciliation(@Param("memberId") Long memberId,
                                                        @Param("ids") List<Long> ids);
    int insertProcessing(@Param("id") Long id, @Param("issuerId") Long issuerId);

    List<CmsRegistration> selectInvoiceRegistrationSeeds(@Param("memberId") Long memberId, @Param("orderIds") List<Long> orderIds);
    CmsRegistration lockInvoiceRegistration(@Param("id") Long id);
    List<CmsRegistration> lockInvoiceParentOrder(@Param("parentOrderNo") String parentOrderNo);
    List<Long> selectActiveInvoiceOrderIdsForUpdate(@Param("orderIds") List<Long> orderIds);
    int reserveTitleReceipt(@Param("memberId") Long memberId, @Param("requestId") String requestId,
                            @Param("fingerprint") String fingerprint);
    Map<String, Object> selectTitleReceipt(@Param("memberId") Long memberId, @Param("requestId") String requestId);
    Map<String, Object> lockTitleReceipt(@Param("memberId") Long memberId, @Param("requestId") String requestId);
    int finishTitleReceipt(@Param("memberId") Long memberId, @Param("requestId") String requestId,
                           @Param("titleId") Long titleId);

    List<UserInvoiceTitle> selectTitlesByMember(@Param("memberId") Long memberId);

    UserInvoiceTitle selectTitle(@Param("memberId") Long memberId, @Param("id") Long id);

    int countTitles(@Param("memberId") Long memberId);

    int insertTitle(UserInvoiceTitle title);

    int updateTitle(UserInvoiceTitle title);

    int deleteTitle(@Param("memberId") Long memberId, @Param("id") Long id);

    /** 设新默认前清掉同一会员其它抬头的默认标记。 */
    int clearDefaultTitles(@Param("memberId") Long memberId, @Param("exceptId") Long exceptId);

    /**
     * 本人可开票的订单：已支付、报名成功、有微信实付、无待审/已通过退款、未被任何申请占用。
     * orderIds 为空时列出全部（最多 limit 条）。
     */
    List<UserInvoiceOrderLine> selectInvoiceableRegistrations(@Param("memberId") Long memberId,
                                                              @Param("orderIds") List<Long> orderIds,
                                                              @Param("limit") int limit);

    /** 同口径，申请事务内锁住订单行，防并发退款/并发申请穿插。 */
    List<UserInvoiceOrderLine> selectInvoiceableRegistrationsForUpdate(@Param("memberId") Long memberId,
                                                                       @Param("orderIds") List<Long> orderIds);

    /** 本人订单里已被占用（active_key=1）的那些，用于区分「已开过票」与「不可开票」。 */
    List<Long> selectOccupiedOrderIds(@Param("memberId") Long memberId,
                                      @Param("orderSource") String orderSource,
                                      @Param("orderIds") List<Long> orderIds);

    UserInvoiceApplication selectApplicationByRequest(@Param("memberId") Long memberId,
                                                      @Param("requestId") String requestId);

    UserInvoiceApplication selectApplication(@Param("memberId") Long memberId, @Param("id") Long id);

    /** 人工开票/驳回路径用（无会员上下文），只按主键读。 */
    UserInvoiceApplication selectApplicationById(@Param("id") Long id);

    List<UserInvoiceApplication> selectApplicationsByMember(@Param("memberId") Long memberId);

    List<UserInvoiceOrderLine> selectOrderLines(@Param("applicationId") Long applicationId);

    int insertApplication(UserInvoiceApplication application);

    int insertOrderLines(@Param("lines") List<UserInvoiceOrderLine> lines);

    /** 会员撤回：只有本人 + SUBMITTED 才能 CAS 成 CANCELLED。 */
    int cancelApplication(@Param("memberId") Long memberId, @Param("id") Long id, @Param("now") Date now);

    int markIssued(@Param("id") Long id, @Param("invoiceNo") String invoiceNo, @Param("now") Date now);

    int markRejected(@Param("id") Long id, @Param("reason") String reason, @Param("now") Date now);

    /** 驳回/撤回后释放订单占用，订单可再次申请。 */
    int releaseOrderLines(@Param("applicationId") Long applicationId);
}
