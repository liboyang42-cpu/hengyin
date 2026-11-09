package com.chengyinhub.business.service.support;

import com.chengyinhub.business.mapper.WorkshopMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Locks the original license until the existing publication/review transaction commits. */
@Component
public class WorkshopLicenseGuard {
    private final WorkshopMapper mapper;
    public WorkshopLicenseGuard(WorkshopMapper mapper) { this.mapper=mapper; }

    @Transactional(propagation=Propagation.MANDATORY)
    public void requirePublication(Long draftId,boolean redistribution) {
        if(draftId==null || draftId<=0)return;
        var row=mapper.lockLicenseForDraft(draftId);
        // The source row fence serializes freezing against every publication/reference writer.
        mapper.lockDraft(draftId);
        if(!mapper.paidSource(draftId).isEmpty())throw new ServiceException("WORKSHOP_PAID_SOURCE_PRIVATE",403);
        if(row==null)return;
        boolean verified="WECHAT_APP".equals(row.get("provider"))?row.get("payment_fact_id")!=null:
                "APPLE_IAP".equals(row.get("provider")) && row.get("apple_transaction_id")!=null && row.get("apple_fact_hash")!=null;
        if(!"PAID".equals(row.get("state")) || !"ACTIVE".equals(row.get("license_status"))
                || row.get("paid_at")==null || !verified
                || !row.get("buyer_id").equals(row.get("installation_owner"))
                || !row.get("buyer_id").equals(row.get("draft_owner")))
            throw new ServiceException("WORKSHOP_LICENSE_DISABLED",403);
        if(redistribution)throw new ServiceException("WORKSHOP_REDISTRIBUTION_PROHIBITED",403);
    }

    @org.springframework.beans.factory.annotation.Autowired
    private CommercialTopicPolicy commercialTopicPolicy;
    private final com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();

    @Transactional(propagation=Propagation.MANDATORY)
    public void requireCopy(Long draftId,Long owner) {
        requirePublication(draftId,false);
        var row=draftId==null || draftId<=0?null:mapper.lockLicenseForDraft(draftId);
        if(row!=null && !java.util.Objects.equals(row.get("buyer_id"),owner))
            throw new ServiceException("WORKSHOP_REDISTRIBUTION_PROHIBITED",403);
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void inheritCopy(Long parent,Long draft,Long owner) {
        requireCopy(parent,owner);
        var row=mapper.lockLicenseForDraft(parent);
        if(row==null)return;
        var terms=terms(row);
        if(mapper.installedCount(row.get("order_no").toString())>=terms.path("installLimit").asInt())
            throw new ServiceException("INSTALL_LIMIT_REACHED",409);
        var update=new java.util.HashMap<String,Object>();
        update.put("draftId",draft);update.put("parentDraftId",parent);update.put("owner",owner);
        update.put("orderNo",row.get("order_no"));update.put("commandJson",row.get("installation_command"));
        if(mapper.inheritDraft(update)!=1)throw new ServiceException("WORKSHOP_LINEAGE_CONFLICT",409);
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void requireTheme(com.chengyinhub.business.domain.CmsTopic topic) {
        for(var reference:mapper.topicReferences(topic.getId())) {
            long id=((Number)reference.get("template_id")).longValue();
            requirePublication(id,false);
            var row=mapper.lockLicenseForDraft(id);
            if(row==null)continue;
            Long merchant=reference.get("merchant_member_id") instanceof Number n?n.longValue():null;
            Long beneficiary=merchant!=null && merchant>0?merchant:topic.getMemberId();
            if(!java.util.Objects.equals(row.get("buyer_id"),beneficiary))
                throw new ServiceException("WORKSHOP_REDISTRIBUTION_PROHIBITED",403);
            var terms=terms(row);
            boolean region=false;
            for(var allowed:terms.path("allowedRegions"))if(allowed.asText().equals(topic.getCityCode()))region=true;
            if(!region)throw new ServiceException("REGION_NOT_LICENSED",403);
            if(!terms.path("commercialUse").asBoolean() && commercialTopicPolicy.evaluate(topic.getId()).isCommercial())
                throw new ServiceException("COMMERCIAL_USE_PROHIBITED",403);
        }
    }

    private com.fasterxml.jackson.databind.JsonNode terms(java.util.Map<String,Object> row) {
        var version=mapper.version(row.get("version_id").toString());
        try { return json.readTree(version.get("terms_json").toString()); }
        catch(Exception e){throw new ServiceException("WORKSHOP_TERMS_INVALID",409);}
    }
}
