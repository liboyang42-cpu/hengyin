package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.appleiap.WorkshopAppleOrderPort;
import com.chengyinhub.business.mapper.WorkshopMapper;
import com.chengyinhub.business.service.support.WorkshopVersionReview;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Original workshop orders own Apple licenses; Apple verifier owns signature and inbox authority. */
@Service
@Transactional(propagation=Propagation.MANDATORY)
public class WorkshopAppleOrderAdapter implements WorkshopAppleOrderPort {
    private final WorkshopMapper mapper;
    public WorkshopAppleOrderAdapter(WorkshopMapper mapper) { this.mapper=mapper; }

    @Override public Expectation requireOrder(Long buyer,String reference) {
        if(buyer==null || buyer<=0)throw error("LOGIN_REQUIRED");
        var row=mapper.lockOrder(reference);
        if(row==null || number(row,"buyer_id")!=buyer)throw error("ORDER_NOT_FOUND");
        return expectation(row);
    }

    @Override public Optional<Expectation> findOrder(VerifiedTransaction fact) {
        if(fact==null || fact.appAccountToken()==null)throw error("APPLE_ACCOUNT_REQUIRED");
        var row=mapper.lockAppleOrder(fact.appAccountToken().toString(),fact.originalTransactionId());
        if(row==null)return Optional.empty();
        var expected=expectation(row);validate(expected,fact);
        return Optional.of(expected);
    }

    @Override public void activate(Expectation expected,VerifiedTransaction fact) {
        var row=locked(expected,fact);
        if(fact.revocationDate()!=null)throw error("APPLE_REVOKED_TRANSACTION");
        if(row.get("apple_transaction_id")!=null) {
            equal(row.get("apple_transaction_id"),fact.transactionId());return;
        }
        if(!"PENDING".equals(row.get("state")) || !"UNPAID".equals(row.get("license_status")))
            throw error("APPLE_ORDER_NOT_ACTIVATABLE");
        if(mapper.activateApple(mutation(row,fact))!=1)throw error("APPLE_ORDER_STATE_CONFLICT");
    }

    @Override public void revoke(Expectation expected,VerifiedTransaction fact) {
        var row=locked(expected,fact);
        if(fact.revocationDate()==null || fact.revocationDate()<=0)throw error("APPLE_REVOCATION_REQUIRED");
        if(mapper.revokeApple(mutation(row,fact))!=1)throw error("APPLE_REVOCATION_CONFLICT");
    }

    @Override public void revokeBoundOriginal(String bundle,String environment,String originalId,Long revokedAt) {
        if(bundle==null || bundle.isBlank() || !("Sandbox".equals(environment) || "Production".equals(environment))
                || originalId==null || originalId.isBlank() || revokedAt==null || revokedAt<=0)
            throw error("APPLE_REVOCATION_REQUIRED");
        var row=mapper.lockBoundAppleOrder(bundle,environment,originalId);
        if(row==null)return;
        equal(row.get("provider"),"APPLE_IAP");equal(row.get("apple_bundle_id"),bundle);
        equal(row.get("apple_environment"),environment);equal(row.get("apple_original_transaction_id"),originalId);
        if(row.get("paid_at")==null)throw error("APPLE_ORIGINAL_LICENSE_REQUIRED");
        if("REVOKED".equals(row.get("license_status")) && "REFUNDED".equals(row.get("state")))return;
        var update=new HashMap<String,Object>();
        update.put("orderNo",row.get("order_no"));update.put("owner",row.get("buyer_id"));
        update.put("originalId",originalId);update.put("revokedAt",new Date(revokedAt));
        if(mapper.revokeBoundApple(update)!=1)throw error("APPLE_REVOCATION_CONFLICT");
    }

    @Override public Object restore(Expectation expected,VerifiedTransaction fact) {
        var row=locked(expected,fact);
        if(row.get("apple_original_transaction_id")==null || row.get("paid_at")==null)
            throw error("APPLE_ORIGINAL_LICENSE_REQUIRED");
        return Map.of("orderNo",row.get("order_no"),"licenseId",row.get("order_no"),
                "purchasedVersionId",row.get("version_id"),"state",row.get("state"),
                "licenseStatus",row.get("license_status"),"provider","APPLE_IAP");
    }

    private Map<String,Object> locked(Expectation expected,VerifiedTransaction fact) {
        var row=mapper.lockOrder(expected.orderReference());
        if(row==null)throw error("ORDER_NOT_FOUND");
        var current=expectation(row);
        if(!current.equals(expected))throw error("APPLE_ORDER_EXPECTATION_CONFLICT");
        validate(current,fact);
        if(row.get("apple_transaction_id")!=null)equal(row.get("apple_transaction_id"),fact.transactionId());
        return row;
    }

    private Expectation expectation(Map<String,Object> row) {
        equal(row.get("provider"),"APPLE_IAP");
        // Approval and channel money were frozen at creation; shelf withdrawal and new SKU prices are unrelated to this order.
        if(!text(row,"version_review_hash").matches("[a-f0-9]{64}"))throw error("APPLE_ORDER_BINDING_MISSING");
        return new Expectation(text(row,"order_no"),number(row,"buyer_id"),text(row,"apple_bundle_id"),
                text(row,"apple_environment"),text(row,"apple_product_id"),UUID.fromString(text(row,"app_account_token")),
                row.get("apple_original_transaction_id")==null?null:text(row,"apple_original_transaction_id"),
                number(row,"amount_milliunits"),text(row,"currency"));
    }

    private static void validate(Expectation expected,VerifiedTransaction fact) {
        if(fact==null || fact.transactionId()==null || fact.transactionId().isBlank()
                || fact.originalTransactionId()==null || fact.originalTransactionId().isBlank()
                || fact.purchaseDate()==null || fact.purchaseDate()<=0)throw error("APPLE_TRANSACTION_REQUIRED");
        equal(expected.bundleId(),fact.bundleId());equal(expected.environment(),fact.environment());
        equal(expected.productId(),fact.productId());equal(expected.appAccountToken(),fact.appAccountToken());
        equal(expected.priceMilliunits(),fact.priceMilliunits());equal(expected.currency(),fact.currency());
        equal("Non-Consumable",fact.type());
        if(expected.originalTransactionId()!=null)equal(expected.originalTransactionId(),fact.originalTransactionId());
    }

    private static Map<String,Object> mutation(Map<String,Object> row,VerifiedTransaction fact) {
        var update=new HashMap<String,Object>();
        update.put("orderNo",row.get("order_no"));update.put("owner",row.get("buyer_id"));
        update.put("transactionId",fact.transactionId());update.put("originalId",fact.originalTransactionId());
        update.put("paidAt",new Date(fact.purchaseDate()));
        update.put("revokedAt",fact.revocationDate()==null?null:new Date(fact.revocationDate()));
        try { update.put("factHash",WorkshopVersionReview.hash(new ObjectMapper().writeValueAsString(fact))); }
        catch(Exception e){throw error("APPLE_FACT_SERIALIZATION_FAILED");}
        return update;
    }
    private static String text(Map<String,Object> row,String field) {
        if(row.get(field)==null)throw error("APPLE_ORDER_BINDING_MISSING");return row.get(field).toString();
    }
    private static long number(Map<String,Object> row,String field){return ((Number)row.get(field)).longValue();}
    private static void equal(Object left,Object right){if(left==null || !left.equals(right))throw error("APPLE_ORDER_BINDING_CONFLICT");}
    private static ServiceException error(String reason){return new ServiceException(reason,409);}
}
