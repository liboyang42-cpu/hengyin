package com.chengyinhub.business.marketplace.free.store;
import org.apache.ibatis.annotations.Param;
import java.util.List;
/** Unregistered same-datasource store. Always lock the real draft before its scope sidecar. */
public interface FreeInstallTargetStore {
    FreeInstallTargetRow lockDraft(@Param("id") long id, @Param("buyer") long buyer);
    FreeInstallTargetRow lockScope(@Param("id") long id, @Param("version") long version);
    int insertScope(FreeInstallTargetRow row);
    List<Long> consumedThemes(@Param("claimId") String claimId, @Param("buyer") long buyer);
}
