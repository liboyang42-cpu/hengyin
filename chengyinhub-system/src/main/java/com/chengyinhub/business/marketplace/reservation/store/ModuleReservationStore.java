package com.chengyinhub.business.marketplace.reservation.store;

import org.apache.ibatis.annotations.Param;
import java.sql.Timestamp;
import java.util.List;

/** Deliberately outside the application's **.mapper scan; explicit test/manual registration only. */
public interface ModuleReservationStore {
    int ensureMutex(@Param("key") String key);
    String lockMutex(@Param("key") String key);
    ModuleReservationRow find(@Param("key") String key);
    ModuleReservationRow findForUpdate(@Param("key") String key);
    int insertReservation(ModuleReservationRow row);
    int insertScope(@Param("key") String key, @Param("kind") String kind, @Param("id") long id);
    int insertBinding(@Param("key") String key, @Param("placeholderKey") String placeholderKey,
                      @Param("placeholder") String placeholder, @Param("kind") String kind,
                      @Param("resourceId") long resourceId, @Param("merchantId") Long merchantId,
                      @Param("authorityReference") String authorityReference, @Param("region") String region,
                      @Param("validUntil") Timestamp validUntil);
    List<Long> activeScopes(@Param("licenseKey") String licenseKey, @Param("kind") String kind,
                            @Param("now") Timestamp now);
    int expireLicense(@Param("licenseKey") String licenseKey, @Param("now") Timestamp now);
    int release(@Param("key") String key, @Param("buyerKind") String buyerKind, @Param("buyerId") long buyerId,
                @Param("state") String state, @Param("now") Timestamp now);
}
