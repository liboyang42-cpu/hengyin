package com.chengyinhub.business.marketplace.reservation.store;

import org.apache.ibatis.annotations.Param;
import java.sql.Timestamp;

/** Not in application mapper-scan namespace; requires explicit reviewed wiring. */
public interface ModuleInstallationStore {
    int insertOwnedDraft(ModuleOwnedDraftRow row);
    int insertTextComponent(ModuleOwnedDraftRow row);
    int insertUsage(@Param("key") String key, @Param("draftId") long draftId, @Param("now") Timestamp now);
    int consume(@Param("key") String key, @Param("buyerKind") String buyerKind,
                @Param("buyerId") long buyerId, @Param("now") Timestamp now);
    ModuleOwnedDraftRow findReceipt(@Param("key") String key, @Param("buyerKind") String buyerKind,
                                   @Param("buyerId") long buyerId);
}
