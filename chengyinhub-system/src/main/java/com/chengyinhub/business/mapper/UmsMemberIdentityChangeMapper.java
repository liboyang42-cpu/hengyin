package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.UmsMemberIdentityChange;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 发布者实名改绑申请单(X036)。加解密全部在 XML 里靠 typeHandler 接线,与 UmsMemberIdentityMapper 同一套;
 * 读列表/进度的查询刻意不选 real_name / id_card 两列,明文出不了这条调用栈。
 */
public interface UmsMemberIdentityChangeMapper {

    /** PENDING 单落库;撞 uk_identity_change_pending = 已有待审单。 */
    int insertChange(UmsMemberIdentityChange change);

    /** 审核时锁定申请单并解出新证明文,只给 Service 写回 ums_member_identity 用。 */
    UmsMemberIdentityChange selectByIdForUpdate(@Param("id") Long id);

    /** 申请人看进度:最近一张单,不含任何 PII 列。 */
    UmsMemberIdentityChange selectLatestByMember(@Param("memberId") Long memberId);

    /**
     * PENDING → 终态 CAS,同时清空密文、释放 pending_member_id。
     * memberId 非空时额外要求是本人的单(撤回用),审核时传 null。
     */
    int finishPending(@Param("id") Long id, @Param("memberId") Long memberId,
                      @Param("status") String status, @Param("reviewNote") String reviewNote,
                      @Param("reviewerUserId") Long reviewerUserId);

    /** 后台审核列表:不含姓名与号码,只给「新证是否已绑其他账号」的盲索引核对结果。 */
    List<Map<String, Object>> selectAdminList(@Param("status") String status);
}
