package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MemberDataCopyRequest;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/** ONB-10: member data-copy requests ({@code member_data_copy_request}). */
public interface MemberDataCopyRequestMapper {

    /** Serialises concurrent creates for one member (row lock on ums_member, inside the caller's transaction). */
    Long lockMember(@Param("memberId") Long memberId);

    /** The member's newest PENDING or PROCESSING request, or null. */
    MemberDataCopyRequest selectOpenByMember(@Param("memberId") Long memberId);

    /** The member's newest request in any status, or null. */
    MemberDataCopyRequest selectLatestByMember(@Param("memberId") Long memberId);

    MemberDataCopyRequest selectById(@Param("id") Long id);

    /** Admin list; {@code status} filters when not blank. Newest first. */
    List<MemberDataCopyRequest> selectList(@Param("status") String status);

    /** Inserts a PENDING request; fills {@code id}. */
    int insert(MemberDataCopyRequest request);

    /** Compare-and-set status change: only applies while the row is still in {@code fromStatus}. */
    int updateStatus(@Param("id") Long id, @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus,
                     @Param("note") String note, @Param("handledBy") Long handledBy, @Param("now") Date now);
}
