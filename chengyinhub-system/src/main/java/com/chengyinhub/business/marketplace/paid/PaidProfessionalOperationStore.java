package com.chengyinhub.business.marketplace.paid;
import org.apache.ibatis.annotations.Param;
/** NEW immutable command registry with monotonic operation status. No CMS/license mutation. */
public interface PaidProfessionalOperationStore {
 int insertIfAbsent(PaidProfessionalOperationRow row);
 PaidProfessionalOperationRow lock(@Param("actor")long actor,@Param("key")String key);
 java.util.List<PaidProfessionalOperationRow> history(@Param("actor")long actor,@Param("before")String before);
 int finish(@Param("actor")long actor,@Param("key")String key,@Param("hash")String hash,@Param("state")String state,@Param("reason")String reason,@Param("at")java.sql.Timestamp at);
}
