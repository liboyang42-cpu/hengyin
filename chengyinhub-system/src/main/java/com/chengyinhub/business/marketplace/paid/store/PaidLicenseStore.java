package com.chengyinhub.business.marketplace.paid.store;
import org.apache.ibatis.annotations.Param;
import java.util.List;
public interface PaidLicenseStore {
    PaidOrderRow lockOrder(@Param("number") String number);
    List<PaidLineRow> lockLines(@Param("orderId") long orderId);
    PaidSnapshotRow lockSnapshot(@Param("lineId") long lineId);
    PaidLicenseRow lockLicense(@Param("lineId") long lineId);
    String ownedOrderNumber(@Param("actor") long actor,@Param("licenseId") String licenseId);
    Long findActiveBuyer(@Param("actor") long actor);
    Long activeBuyer(@Param("actor") long actor);
    int markPaid(@Param("orderId") long orderId,@Param("transactionId") String transactionId,@Param("paidAt") java.sql.Timestamp paidAt);
    int insertLicense(PaidLicenseRow row);
}
