package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.mapper.WorkshopMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Uses the original audit task and decision transaction without publishing the paid content. */
@Component
public class WorkshopVersionReview {
    public static final String BIZ_TYPE = "WORKSHOP_VERSION";
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final WorkshopMapper mapper;
    @Autowired @Lazy private IAuditTaskService audits;
    @Autowired @Lazy private com.chengyinhub.business.service.ITemplatePublishService drafts;
    public WorkshopVersionReview(WorkshopMapper mapper) { this.mapper=mapper; }

    @Transactional(propagation=Propagation.MANDATORY)
    public void submit(Map<String,Object> version) {
        if (!"PRIVATE_TEMPLATE".equals(version.get("source_kind"))) throw error("PRIVATE_SOURCE_REQUIRED");
        if (!"DRAFT".equals(version.get("review_state"))) {
            if (version.get("audit_task_id")!=null) return;
            throw error("VERSION_NOT_SUBMITTABLE");
        }
        Map<String,Object> snapshot=new TreeMap<>();
        snapshot.put("schema","workshop-paid-review-v1");
        for (String field:List.of("version_id","seller_id","module_id","content_hash","terms_hash","price_fen"))
            snapshot.put(field,version.get(field));
        snapshot.put("currency","CNY");
        snapshot.put("content",tree(version.get("content_json")));
        snapshot.put("terms",tree(version.get("terms_json")));
        snapshot.put("appleProducts",mapper.appleProducts(version.get("version_id").toString()));
        String frozen=encode(snapshot);
        Long task=audits.submit(BIZ_TYPE,number(version,"id"),number(version,"seller_id"),"auto_gate",frozen);
        Map<String,Object> row=new HashMap<>();
        row.put("versionId",version.get("version_id"));row.put("owner",version.get("seller_id"));
        row.put("contentHash",version.get("content_hash"));row.put("termsHash",version.get("terms_hash"));
        row.put("priceFen",version.get("price_fen"));row.put("auditTaskId",task);
        row.put("reviewSnapshot",frozen);row.put("reviewHash",hash(frozen));
        if (task==null || mapper.submitVersion(row)!=1) throw error("VERSION_SUBMISSION_CONFLICT");
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void lock(AuditTask task) {
        var version=mapper.lockVersionById(task.getBizId());
        requireSnapshot(version);
        if (!Objects.equals(version.get("audit_task_id"),task.getId())
                || number(version,"seller_id")!=task.getSubmitterId()
                || !Objects.equals(version.get("review_snapshot"),task.getSnapshotJson()))
            throw error("PAID_REVIEW_SNAPSHOT_CONFLICT");
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void apply(AuditTask task,Integer decision) {
        if (!AuditTask.STATUS_APPROVED.equals(decision) && !AuditTask.STATUS_REJECTED.equals(decision)) return;
        lock(task);
        if(AuditTask.STATUS_APPROVED.equals(decision)) {
            var version=mapper.lockVersionById(task.getBizId());
            try { drafts.validateWorkshopSnapshot(JSON.readValue(version.get("content_json").toString(),
                    com.chengyinhub.business.domain.dto.CmsTemplatePublishRequest.class),task.getSubmitterId()); }
            catch(com.fasterxml.jackson.core.JsonProcessingException e){throw error("PAID_REVIEW_SNAPSHOT_CONFLICT");}
        }
        var row=new HashMap<String,Object>();
        row.put("id",task.getBizId());row.put("auditTaskId",task.getId());row.put("reviewHash",hash(task.getSnapshotJson()));
        row.put("reviewState",AuditTask.STATUS_APPROVED.equals(decision)?"APPROVED":"REJECTED");
        if (mapper.reviewVersion(row)!=1) throw error("PAID_REVIEW_STATE_CONFLICT");
    }

    public static void requireApproved(Map<String,Object> version) {
        if(version==null || !"PRIVATE_TEMPLATE".equals(version.get("source_kind")) || !"APPROVED".equals(version.get("review_state")))
            throw error("PAID_VERSION_NOT_APPROVED");
        requireSnapshot(version);
    }

    public static void requireSnapshot(Map<String,Object> version) {
        if (version==null || version.get("review_snapshot")==null || version.get("review_hash")==null
                || !hash(version.get("review_snapshot").toString()).equals(version.get("review_hash")))
            throw error("PAID_REVIEW_SNAPSHOT_CONFLICT");
        JsonNode snapshot=tree(version.get("review_snapshot"));
        for (String field:List.of("version_id","seller_id","module_id","content_hash","terms_hash","price_fen"))
            if (!Objects.toString(version.get(field),"").equals(snapshot.path(field).asText()))
                throw error("PAID_REVIEW_SNAPSHOT_CONFLICT");
        if (!tree(version.get("content_json")).equals(snapshot.path("content"))
                || !tree(version.get("terms_json")).equals(snapshot.path("terms")))
            throw error("PAID_REVIEW_SNAPSHOT_CONFLICT");
    }

    public Map<String,Object> appleProduct(Map<String,Object> version,String bundle,String environment) {
        requireApproved(version);
        var current=mapper.appleProducts(version.get("version_id").toString());
        if (!tree(encode(current)).equals(tree(version.get("review_snapshot")).path("appleProducts")))
            throw error("APPLE_PRODUCT_REVIEW_CONFLICT");
        var matches=current.stream().filter(p->Objects.equals(bundle,p.get("bundle_id"))
                && Objects.equals(environment,p.get("environment"))).toList();
        if (matches.size()!=1) throw error("APPLE_PRODUCT_NOT_CONFIGURED");
        var product=matches.get(0);
        if (number(product,"price_milliunits")<=0 || !Objects.toString(product.get("currency"),"").matches("[A-Z]{3}"))
            throw error("APPLE_PRODUCT_NOT_CONFIGURED");
        return product;
    }

    public static String hash(String value) { return WechatVerifiedPaymentFact.sha256(value); }
    private static String encode(Object value) {
        try { return JSON.writeValueAsString(value); } catch (Exception e) { throw error("PAID_REVIEW_SERIALIZATION_FAILED"); }
    }
    private static JsonNode tree(Object value) {
        try { return JSON.readTree(Objects.toString(value,"")); } catch (Exception e) { throw error("PAID_REVIEW_SNAPSHOT_CONFLICT"); }
    }
    private static long number(Map<String,Object> row,String field) { return ((Number)row.get(field)).longValue(); }
    private static ServiceException error(String reason) { return new ServiceException(reason,409); }
}
