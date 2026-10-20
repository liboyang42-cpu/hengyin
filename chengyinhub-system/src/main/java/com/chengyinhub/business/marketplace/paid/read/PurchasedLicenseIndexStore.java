package com.chengyinhub.business.marketplace.paid.read;
import org.apache.ibatis.annotations.Param;
import java.util.List;
public interface PurchasedLicenseIndexStore {
    List<Row> listOwned(@Param("actor")long actor,@Param("before")Long before);
    final class Row {
        private Long orderLineId;private String licenseId;
        public Long getOrderLineId(){return orderLineId;}public void setOrderLineId(Long value){orderLineId=value;}
        public String getLicenseId(){return licenseId;}public void setLicenseId(String value){licenseId=value;}
    }
}
