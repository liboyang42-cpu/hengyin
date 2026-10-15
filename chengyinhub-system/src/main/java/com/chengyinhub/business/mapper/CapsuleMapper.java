package com.chengyinhub.business.mapper;
import com.chengyinhub.business.domain.CapsuleRecord;
import org.apache.ibatis.annotations.Param;
import java.util.List;
public interface CapsuleMapper {
    int insert(CapsuleRecord record);
    CapsuleRecord byRequest(@Param("memberId") Long memberId, @Param("requestId") String requestId);
    CapsuleRecord byId(@Param("memberId") Long memberId, @Param("id") Long id);
    List<CapsuleRecord> page(@Param("memberId") Long memberId, @Param("beforeId") Long beforeId, @Param("limit") int limit);
    int delete(@Param("memberId") Long memberId, @Param("id") Long id);
}
