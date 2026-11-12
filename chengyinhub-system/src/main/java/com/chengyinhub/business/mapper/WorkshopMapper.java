package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTemplateLibrary;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface WorkshopMapper {
    int inheritDraft(Map<String,Object> row);
    List<Map<String,Object>> topicReferences(long topicId);

    int sourceExposure(long id);
    List<Long> paidSource(long id);
    Map<String,Object> lockVersionById(long id);
    List<Map<String,Object>> shelf(@Param("before") long before);
    List<Map<String,Object>> appleProducts(String versionId);
    int submitVersion(Map<String,Object> row);
    int reviewVersion(Map<String,Object> row);
    int withdrawVersion(@Param("versionId") String versionId, @Param("owner") long owner);
    Map<String,Object> lockBoundAppleOrder(@Param("bundle") String bundle,@Param("environment") String environment,@Param("originalId") String originalId);
    int revokeBoundApple(Map<String,Object> row);

    Map<String,Object> lockAppleOrder(@Param("token") String token, @Param("originalId") String originalId);
    int activateApple(Map<String,Object> row);
    int revokeApple(Map<String,Object> row);

    Long sourceCanonicalId(long id);
    CmsTemplateLibrary lockSource(long id);
    CmsMemberTemplate lockDraft(long id);
    List<CmsMemberTemplate> targets(@Param("owner") long owner, @Param("before") long before);
    int insertVersion(Map<String,Object> row);
    Map<String,Object> version(String versionId);
    Map<String,Object> versionRequest(@Param("owner") long owner, @Param("requestId") String requestId);
    int reserveOrder(Map<String,Object> row);
    Map<String,Object> lockOrder(String orderNo);

    /** Locks only the draft's license order row (the alias-scoped lock clause is rejected by the app's Druid wall). */
    Map<String,Object> lockLicenseOrderForDraft(long draftId);
    Map<String,Object> licenseInstallationForDraft(long draftId);

    /** Locked {@code workshop_order} row plus installation_owner, draft_owner and installation_command; null without a license. */
    default Map<String,Object> lockLicenseForDraft(long draftId) {
        Map<String,Object> order=lockLicenseOrderForDraft(draftId);
        if(order==null)return null;
        Map<String,Object> installation=licenseInstallationForDraft(draftId);
        if(installation==null || !java.util.Objects.equals(order.get("order_no"),installation.get("order_no")))
            throw new IllegalStateException("WORKSHOP_LICENSE_LOCK_MISMATCH");
        Map<String,Object> row=new java.util.HashMap<>(order);
        row.put("installation_owner",installation.get("installation_owner"));
        row.put("draft_owner",installation.get("draft_owner"));
        row.put("installation_command",installation.get("installation_command"));
        return row;
    }
    Map<String,Object> orderRequest(@Param("owner") long owner, @Param("requestId") String requestId);
    List<Map<String,Object>> licenses(@Param("owner") long owner, @Param("before") long before);
    int activate(Map<String,Object> row);
    int suspendRefund(Map<String,Object> row);
    int refundDispatch(Map<String,Object> row);
    int refunded(Map<String,Object> row);
    int revoke(Map<String,Object> row);
    int reserveInstall(Map<String,Object> row);
    Map<String,Object> install(@Param("owner") long owner, @Param("requestId") String requestId);
    int finishInstall(Map<String,Object> row);
    int installedCount(String orderNo);
    List<Map<String,Object>> history(@Param("owner") long owner, @Param("orderNo") String orderNo,
                                     @Param("before") long before);
}
