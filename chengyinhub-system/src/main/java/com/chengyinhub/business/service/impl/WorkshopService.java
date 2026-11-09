package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.domain.dto.CmsTemplatePublishRequest;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference;
import com.chengyinhub.business.mapper.WorkshopMapper;
import com.chengyinhub.business.service.ITemplatePublishService;
import com.chengyinhub.business.service.IWechatPayService;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Money comes only from the existing verified provider facts; an order row also owns its license. */
@Service
public class WorkshopService {
    public record Terms(String version, String text, boolean commercialUse, List<String> allowedRegions, int installLimit) {}
    public record Freeze(String requestId,long sourceId,String contentHash,long priceFen,Terms terms,String sourceKind) {
        public Freeze(String requestId,long sourceId,String contentHash,long priceFen,Terms terms) {
            this(requestId,sourceId,contentHash,priceFen,terms,"PRIVATE_TEMPLATE");
        }
    }
    public record Purchase(String requestId,String purchasedVersionId,String contentHash,String termsHash,String provider,String reviewHash) {
        public Purchase(String requestId,String purchasedVersionId,String contentHash,String termsHash,String provider) {
            this(requestId,purchasedVersionId,contentHash,termsHash,provider,null);
        }
    }
    public record Install(String schema, String requestId, String licenseId, long moduleId, String purchasedVersionId,
                          String contentHash, String termsHash, long targetDraftId, long targetRevision,
                          String businessType, String targetPayloadHash, String planningRegion, boolean commercialUse) {}

    @org.springframework.beans.factory.annotation.Autowired
    private com.chengyinhub.business.service.support.WorkshopVersionReview versionReview;
    @org.springframework.beans.factory.annotation.Autowired
    private com.chengyinhub.business.appleiap.AppleSignedData appleSignedData;
    @org.springframework.beans.factory.annotation.Autowired
    private com.chengyinhub.business.appleiap.AppleIapProperties appleProperties;
    public record ReviewSubmission(String purchasedVersionId,String contentHash,String termsHash,long priceFen) {}

    private final WorkshopMapper mapper;
    private final ITemplatePublishService drafts;
    private final IWechatPayService payments;
    private final WechatVerifiedPaymentFactService verified;
    private final TransactionTemplate tx;
    private final ObjectMapper json = new ObjectMapper();
    @Value("${workshop.wechat-app-enabled:false}")
    private boolean appEnabled;

    public WorkshopService(WorkshopMapper mapper, ITemplatePublishService drafts, @org.springframework.context.annotation.Lazy IWechatPayService payments,
                           WechatVerifiedPaymentFactService verified, PlatformTransactionManager transactions) {
        this.mapper = mapper; this.drafts = drafts; this.payments = payments; this.verified = verified;
        this.tx = new TransactionTemplate(transactions);
    }

    public Map<String,Object> capabilities() {
        boolean ready = appEnabled && payments.isAppReady();
        return Map.of("schema", "workshop-capabilities-v2", "contractVersion", 2,
                "privateDraftInstall", true, "executionGranted", false,
                "wechatApp", Map.of("available", ready, "reason", ready ? "READY" : "CHANNEL_NOT_CONFIGURED"),
                "appleIap", Map.of("available", appleSignedData!=null && appleSignedData.isReady(), "reason", appleSignedData!=null && appleSignedData.isReady() ? "PRODUCT_MAPPING_REQUIRED" : "CHANNEL_NOT_CONFIGURED"),
                "legacyAction", "UPGRADE_REQUIRED");
    }

    public Map<String,Object> authorSource(long owner, long sourceId) {
        member(owner);
        return tx.execute(s -> {
            var snapshot = sourceSnapshot(owner, sourceId);
            return Map.of("moduleId", sourceId, "sourceKind","PRIVATE_TEMPLATE", "contentHash", hash(encode(snapshot)), "title", snapshot.getTitle());
        });
    }

    public Map<String,Object> freeze(long owner, Freeze command) {
        member(owner);
        if (command == null) throw failure(400,"COMMAND_REQUIRED");
        if(!"PRIVATE_TEMPLATE".equals(command.sourceKind()))throw failure(409,"PRIVATE_SOURCE_CONTRACT_REQUIRED");
        key(command.requestId()); digest(command.contentHash()); validateTerms(command.terms());
        if (command.priceFen() <= 0 || command.priceFen() > Integer.MAX_VALUE) throw failure(400,"INVALID_PRICE");
        return tx.execute(s -> {
            var prior=mapper.versionRequest(owner,command.requestId());
            if(prior!=null){same(prior,"request_hash",hash(encode(command)));return offerView(prior);}
            CmsTemplatePublishRequest snapshot = sourceSnapshot(owner, command.sourceId());
            String content = encode(snapshot), contentHash = hash(content), terms = encode(command.terms());
            if (!contentHash.equals(command.contentHash())) throw failure(409,"SOURCE_VERSION_CONFLICT");
            Map<String,Object> row = new HashMap<>();
            row.put("owner",owner); row.put("requestId",command.requestId()); row.put("requestHash",hash(encode(command)));
            row.put("versionId","WKV_"+random()); row.put("moduleId",command.sourceId());
            row.put("sourceCanonicalId",command.sourceId());
            row.put("contentJson",content); row.put("contentHash",contentHash);
            row.put("termsJson",terms); row.put("termsHash",hash(terms)); row.put("priceFen",command.priceFen());
            mapper.insertVersion(row);
            var stored = mapper.versionRequest(owner,command.requestId());
            same(stored,"request_hash",row.get("requestHash"));
            return offerView(stored);
        });
    }

    public Map<String,Object> offer(String versionId) {
        return tx.execute(s->{ var v=version(versionId); com.chengyinhub.business.service.support.WorkshopVersionReview.requireApproved(v);
            return offerView(v); });
    }

    public Map<String,Object> submitVersion(long owner,ReviewSubmission command) {
        member(owner);
        if(command==null) throw failure(400,"COMMAND_REQUIRED");
        return tx.execute(s->{
            var v=version(command.purchasedVersionId());same(v,"seller_id",owner);
            same(v,"content_hash",command.contentHash());same(v,"terms_hash",command.termsHash());same(v,"price_fen",command.priceFen());
            versionReview.submit(v); return offerView(version(command.purchasedVersionId()));
        });
    }

    public Map<String,Object> authorVersion(long owner,String versionId) {
        member(owner);return tx.execute(s->{var v=version(versionId);same(v,"seller_id",owner);return offerView(v);});
    }

    public Map<String,Object> withdrawVersion(long owner,String versionId) {
        member(owner);return tx.execute(s->{
            var v=version(versionId);same(v,"seller_id",owner);
            if(!"WITHDRAWN".equals(v.get("review_state"))) changed(mapper.withdrawVersion(versionId,owner));
            return offerView(version(versionId));
        });
    }

    public Map<String,Object> shelf(Long before) {
        return tx.execute(s->{
            var rows=mapper.shelf(cursor(before));boolean more=rows.size()>50;
            var result=envelope("workshop-paid-shelf-v1");
            result.put("items",rows.stream().limit(50).map(v->{
                com.chengyinhub.business.service.support.WorkshopVersionReview.requireApproved(v);return offerView(v);
            }).toList());result.put("hasMore",more);
            if(more)result.put("nextBeforeVersionId",number(rows.get(49),"id"));
            return result;
        });
    }

    public Map<String,Object> purchase(long owner, Purchase command) {
        member(owner);
        if(command==null)throw failure(400,"COMMAND_REQUIRED");
        key(command.requestId());digest(command.contentHash());digest(command.termsHash());
        boolean apple="APPLE_IAP".equals(command.provider());
        if(!apple && !"WECHAT_APP".equals(command.provider()))throw failure(409,"PROVIDER_UNAVAILABLE");
        if(apple) { if(appleSignedData==null || !appleSignedData.isReady())throw failure(409,"CHANNEL_NOT_CONFIGURED"); }
        else requireChannel();
        return tx.execute(s->{
            // A lost create acknowledgement returns the original order even after shelf withdrawal.
            var original=mapper.orderRequest(owner,command.requestId());
            if(original!=null){same(original,"request_hash",hash(encode(command)));return orderView(original);}
            var v=version(command.purchasedVersionId());
            com.chengyinhub.business.service.support.WorkshopVersionReview.requireApproved(v);
            same(v,"content_hash",command.contentHash());same(v,"terms_hash",command.termsHash());
            if(apple || command.reviewHash()!=null)same(v,"review_hash",command.reviewHash());
            if(number(v,"seller_id")==owner)throw failure(409,"CANNOT_PURCHASE_OWN_VERSION");
            Map<String,Object> row=new HashMap<>();
            row.put("orderNo","WKO_"+random());row.put("owner",owner);row.put("requestId",command.requestId());
            row.put("versionId",command.purchasedVersionId());row.put("provider",command.provider());
            row.put("reviewHash",v.get("review_hash"));row.put("currency","CNY");
            row.put("amountFen",number(v,"price_fen"));
            if(apple){
                var p=versionReview.appleProduct(v,appleProperties.getBundleId(),appleProperties.getEnvironment());
                String sku=text(p,"product_id"), currency=text(p,"currency");long milli=number(p,"price_milliunits");
                if(!Objects.equals(appleProperties.getSkuPrices().getOrDefault(sku,Map.of()).get(currency),milli))
                    throw failure(409,"APPLE_PRODUCT_NOT_CONFIGURED");
                row.put("amountFen",null);row.put("amountMilliunits",milli);row.put("currency",currency);
                row.put("accountToken",UUID.randomUUID().toString());row.put("bundleId",p.get("bundle_id"));
                row.put("environment",p.get("environment"));row.put("productId",sku);
            }
            row.put("requestHash",hash(encode(command)));mapper.reserveOrder(row);
            var stored=mapper.orderRequest(owner,command.requestId());
            same(stored,"request_hash",row.get("requestHash"));return orderView(stored);
        });
    }

    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER)
    public Map<String,Object> preparePayment(long owner, String orderNo) {
        member(owner); requireChannel();
        var order = tx.execute(s -> {
            var row = owned(owner,orderNo);
            same(row,"provider","WECHAT_APP");
            com.chengyinhub.business.service.support.WorkshopVersionReview.requireApproved(version(text(row,"version_id")));
            if (!"PENDING".equals(row.get("state"))) throw failure(409,"ORDER_NOT_PAYABLE");
            return row;
        });
        // The provider call freezes and rechecks the same business row and payment attempt itself.
        var params = payments.createGenericAppOrder(orderNo, yuan(order), "城瘾玩法模板");
        var result = orderView(order); result.put("payParams",params); return result;
    }

    public Map<String,Object> order(long owner, String orderNo) {
        member(owner); return tx.execute(s -> orderView(owned(owner,orderNo)));
    }

    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER)
    public Map<String,Object> reconcilePayment(long owner, String orderNo) {
        member(owner); tx.execute(s -> {var row=owned(owner,orderNo);same(row,"provider","WECHAT_APP");return row;});
        var observation = payments.queryOrderStatus(orderNo);
        if (observation != null && "SUCCESS".equals(observation.get("trade_state"))) {
            if (!orderNo.equals(observation.get("out_trade_no")) || !handlePaidOrder(observation))
                throw failure(409,"PAYMENT_FACT_CONFLICT");
        }
        return order(owner,orderNo);
    }

    /** Called by the production verified callback router and by the owner's original-order query. */
    public boolean handlePaidOrder(Map<String,String> observation) {
        if (observation == null || !"SUCCESS".equals(observation.get("trade_state"))) return false;
        String no = observation.get("out_trade_no");
        if (no == null || !no.matches("WKO_[a-f0-9]{32}")) return false;
        Reference reference = reference(observation);
        return Boolean.TRUE.equals(tx.execute(s -> {
            var order = mapper.lockOrder(no);
            if (order == null) return false;
            var fact = verified.requireConsumablePayment(reference).facts();
            requirePayment(order,fact);
            if (order.get("paid_at") != null) {
                same(order,"payment_fact_id",reference.id()); same(order,"payment_fact_hash",reference.hash());
                return true; // Refunded/revoked licenses are never reactivated by an old SUCCESS.
            }
            if (!"PENDING".equals(order.get("state"))) return false;
            var update = mutation(order); update.put("factId",reference.id()); update.put("factHash",reference.hash());
            update.put("paidAt",Date.from(fact.successAt())); changed(mapper.activate(update));
            verified.completePaymentRecovery(reference,number(order,"id"));
            return true;
        }));
    }

    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER)
    public Map<String,Object> refund(long owner, String orderNo) {
        member(owner);
        var original = tx.execute(s -> {
            var order = owned(owner,orderNo);
            same(order,"provider","WECHAT_APP");
            if ("REFUNDED".equals(order.get("state"))) return order;
            if (order.get("paid_at") == null) throw failure(409,"ORDER_NOT_PAID");
            if ("PAID".equals(order.get("state"))) {
                var update = mutation(order); update.put("refundNo","R"+orderNo);
                changed(mapper.suspendRefund(update));
            }
            return owned(owner,orderNo);
        });
        if ("REFUNDED".equals(original.get("state"))) return orderView(original);
        // A stable refund number survives a crash between provider acceptance and this diagnostic write.
        var result = payments.submitRefund(paymentReference(original), orderNo, text(original,"refund_no"),
                yuan(original),yuan(original),"工坊购买退款");
        tx.execute(s -> {
            var row = owned(owner,orderNo); var update = mutation(row); update.put("result",result.name());
            mapper.refundDispatch(update); return null;
        });
        return order(owner,orderNo);
    }

    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER)
    public Map<String,Object> reconcileRefund(long owner, String orderNo) {
        member(owner);
        var original = tx.execute(s -> {var row=owned(owner,orderNo);same(row,"provider","WECHAT_APP");return row;});
        if (original.get("refund_no") == null) throw failure(409,"REFUND_NOT_REQUESTED");
        var observation = payments.queryRefundFacts(paymentReference(original),text(original,"refund_no"));
        if (observation != null && "SUCCESS".equals(observation.get("status")) && !handleRefund(observation))
            throw failure(409,"REFUND_FACT_CONFLICT");
        return order(owner,orderNo);
    }

    public boolean handleRefund(Map<String,String> observation) {
        if (observation == null || !"SUCCESS".equals(observation.get("status"))) return false;
        String no = observation.get("outTradeNo");
        if (no == null || !no.matches("WKO_[a-f0-9]{32}")) return false;
        Reference reference = reference(observation);
        return Boolean.TRUE.equals(tx.execute(s -> {
            var order = mapper.lockOrder(no);
            if (order == null || order.get("paid_at") == null) return false;
            var original = verified.requireVerified(paymentReference(order)).facts(); requirePayment(order,original);
            var fact = verified.requireVerified(reference).facts();
            if (fact.kind()!=WechatVerifiedPaymentFact.Kind.REFUND || !no.equals(fact.businessOutTradeNo())
                    || !Objects.equals(order.get("refund_no"),fact.merchantEventNo())
                    || !Objects.equals(observation.get("outRefundNo"),fact.merchantEventNo())
                    || fact.amountFen()!=number(order,"amount_fen") || fact.totalFen()!=original.totalFen()
                    || !fact.transactionId().equals(original.transactionId()) || !fact.mchid().equals(original.mchid())
                    || !fact.appid().equals(original.appid()) || !fact.tradeType().equals(original.tradeType())
                    || !fact.wxOutTradeNo().equals(original.wxOutTradeNo()) || !"CNY".equals(fact.currency()))
                throw failure(409,"REFUND_FACT_CONFLICT");
            if ("REFUNDED".equals(order.get("state"))) {
                same(order,"refund_fact_id",reference.id()); same(order,"refund_fact_hash",reference.hash()); return true;
            }
            var update = mutation(order); update.put("factId",reference.id()); update.put("factHash",reference.hash());
            update.put("refundedAt",Date.from(fact.successAt())); changed(mapper.refunded(update)); return true;
        }));
    }

    public Map<String,Object> revoke(long owner, String licenseId) {
        member(owner);
        return tx.execute(s -> {
            var order = owned(owner,licenseId);
            if (order.get("paid_at") == null) throw failure(409,"ORDER_NOT_PAID");
            if (!"REVOKED".equals(order.get("license_status"))) changed(mapper.revoke(mutation(order)));
            return licenseView(owned(owner,licenseId));
        });
    }

    public Map<String,Object> licenses(long owner, Long before) {
        member(owner); long cursor = cursor(before);
        var rows = mapper.licenses(owner,cursor); boolean more = rows.size()>50;
        var items = rows.stream().limit(50).map(this::licenseView).toList();
        Map<String,Object> result = envelope("workshop-purchased-v2"); result.put("items",items); result.put("hasMore",more);
        if (more) result.put("nextBeforeOrderLineId",number(rows.get(49),"id"));
        return result;
    }

    public Map<String,Object> license(long owner,String licenseId) {
        member(owner); return tx.execute(s -> licenseView(paidOwned(owner,licenseId,false)));
    }

    public Map<String,Object> targets(long owner,String licenseId,Long before) {
        member(owner);
        return tx.execute(s -> {
            paidOwned(owner,licenseId,true);
            var rows = mapper.targets(owner,cursor(before)); boolean more = rows.size()>50;
            Map<String,Object> result = envelope("workshop-targets-v2");
            result.put("items",rows.stream().limit(50).map(this::targetView).toList()); result.put("hasMore",more);
            if (more) result.put("nextBeforeDraftId",rows.get(49).getId()); return result;
        });
    }

    /** Raw command bytes are retained; changing whitespace or any field under the same ID is a conflict. */
    public Map<String,Object> install(long owner,String raw,String username) {
        member(owner);
        if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length>16384) throw failure(400,"INVALID_COMMAND_SIZE");
        Install command = decode(raw,Install.class);
        if (command == null || !"workshop-private-draft-install-command-v2".equals(command.schema())) throw failure(409,"UPGRADE_REQUIRED");
        key(command.requestId()); digest(command.contentHash()); digest(command.termsHash()); digest(command.targetPayloadHash());
        if (!"MEMBER_TEMPLATE".equals(command.businessType()) || command.targetDraftId()<=0 || command.targetRevision()<=0)
            throw failure(400,"INVALID_TARGET");
        return tx.execute(s -> {
            var order = paidOwned(owner,command.licenseId(),false);
            var reservation = mutation(order); reservation.put("requestId",command.requestId());
            reservation.put("commandHash",hash(raw)); reservation.put("commandJson",raw);
            mapper.reserveInstall(reservation);
            var receipt = mapper.install(owner,command.requestId());
            same(receipt,"order_no",command.licenseId()); same(receipt,"command_hash",hash(raw)); same(receipt,"command_json",raw);
            if (receipt.get("outcome_json") != null) return object(text(receipt,"outcome_json"));
            active(order);
            var v = version(text(order,"version_id"));
            same(v,"version_id",command.purchasedVersionId()); same(v,"module_id",command.moduleId());
            same(v,"content_hash",command.contentHash()); same(v,"terms_hash",command.termsHash());
            Terms terms = decode(text(v,"terms_json"),Terms.class);
            if (command.commercialUse() && !terms.commercialUse()) throw failure(403,"COMMERCIAL_USE_PROHIBITED");
            if (command.planningRegion()==null || !terms.allowedRegions().contains(command.planningRegion()))
                throw failure(403,"REGION_NOT_LICENSED");
            if (mapper.installedCount(command.licenseId())>=terms.installLimit()) throw failure(409,"INSTALL_LIMIT_REACHED");
            var target = mapper.lockDraft(command.targetDraftId());
            privateOwned(target,owner);
            if (revision(target)!=command.targetRevision() || !targetHash(target).equals(command.targetPayloadHash()))
                throw failure(409,"TARGET_REVISION_CONFLICT");
            String content = text(v,"content_json"); same(v,"content_hash",hash(content));
            CmsTemplatePublishRequest snapshot = decode(content,CmsTemplatePublishRequest.class);
            Long id = drafts.installWorkshopSnapshot(snapshot,owner,username);
            var created = id == null ? null : mapper.lockDraft(id);
            privateOwned(created,owner);
            if (id.equals(target.getId())) throw new IllegalStateException("Workshop installation must create a new draft");
            Instant now = Instant.now(); Map<String,Object> result = new LinkedHashMap<>(object(encode(command)));
            result.put("schema","workshop-private-draft-install-outcome-v2"); result.put("state","INSTALLED_PRIVATE_DRAFT");
            result.put("ownedDraftId",id); result.put("installedAt",now.toString()); result.put("holdDeadline",now.toString());
            result.put("mode","PRIVATE_COPY"); result.put("professionalTemplateStatus","MATERIALIZATION_REQUIRED");
            result.put("published",false); result.put("executable",false);
            String outcome = encode(result);
            reservation.put("outcomeJson",outcome); reservation.put("draftId",id); reservation.put("installedAt",Date.from(now));
            changed(mapper.finishInstall(reservation)); return object(outcome);
        });
    }

    public Map<String,Object> installStatus(long owner,String requestId) {
        member(owner); key(requestId);
        return tx.execute(s -> {
            var receipt = mapper.install(owner,requestId);
            return receipt == null ? Map.of("schema","workshop-private-draft-install-outcome-v2","requestId",requestId,"state","NOT_FOUND")
                    : object(text(receipt,"outcome_json"));
        });
    }

    public Map<String,Object> history(long owner,String licenseId,Long before) {
        member(owner);
        return tx.execute(s -> {
            paidOwned(owner,licenseId,false);
            var rows = mapper.history(owner,licenseId,cursor(before)); boolean more = rows.size()>50;
            var result = envelope("workshop-install-history-v2");
            result.put("items",rows.stream().limit(50).map(r -> object(text(r,"outcome_json"))).toList());
            result.put("hasMore",more); if (more) result.put("nextBeforeCommandKey",number(rows.get(49),"id")); return result;
        });
    }

    public Map<String,Object> installedText(long owner,String licenseId,String requestId) {
        member(owner); key(requestId);
        return tx.execute(s -> {
            var order = paidOwned(owner,licenseId,true);
            var receipt = mapper.install(owner,requestId);
            if (receipt == null || receipt.get("draft_id")==null) throw failure(404,"INSTALL_NOT_FOUND");
            same(receipt,"order_no",licenseId);
            var snapshot = decode(text(version(text(order,"version_id")),"content_json"),CmsTemplatePublishRequest.class);
            Map<String,Object> result = new LinkedHashMap<>(); result.put("schema","w18-member-text-v1"); result.put("bindings",Map.of());
            result.put("title",snapshot.getTitle()); result.put("description",snapshot.getDescription());
            result.put("ruleInstructions",snapshot.getRuleInstructions()); result.put("merchantGuide",snapshot.getMerchantGuide());
            result.put("questionName",snapshot.getQuestionName()); result.put("questionAnswer",snapshot.getQuestionAnswer());
            result.put("hint1",snapshot.getHint1()); result.put("hint2",snapshot.getHint2()); result.put("answerReveal",snapshot.getAnswerReveal());
            if (encode(result).getBytes(StandardCharsets.UTF_8).length>262144) throw failure(409,"TEXT_PAYLOAD_TOO_LARGE");
            return result;
        });
    }

    private CmsTemplatePublishRequest sourceSnapshot(long owner,long sourceId) {
        if(mapper.lockLicenseForDraft(sourceId)!=null) throw failure(409,"PAID_SOURCE_MUST_BE_INDEPENDENT_PRIVATE_DRAFT");
        CmsMemberTemplate source=mapper.lockDraft(sourceId);
        privateOwned(source,owner);
        if ((source.getOriginalTemplateId()!=null && source.getOriginalTemplateId()>0)
                || mapper.sourceExposure(sourceId)>0)
            throw failure(409,"PAID_SOURCE_MUST_BE_INDEPENDENT_PRIVATE_DRAFT");
        if (source.getCouponId()!=null && source.getCouponId()>0) throw failure(409,"SOURCE_HAS_FOREIGN_ASSETS");
        if (source.getRewardRulesJson()!=null) {
            for (JsonNode rule:tree(source.getRewardRulesJson()))
                if (rule.path("couponId").asLong()>0) throw failure(409,"SOURCE_HAS_FOREIGN_ASSETS");
        }
        CmsTemplatePublishRequest snapshot=new CmsTemplatePublishRequest();
        BeanUtils.copyProperties(source,snapshot,"id","duration");
        snapshot.setDuration(source.getDuration()==null?null:Math.toIntExact(source.getDuration()));
        snapshot.setOriginalTemplateId(0L);
        snapshot.setScope("personal");snapshot.setIsSync(0);snapshot.setAdoptSourceOnly(false);snapshot.setClearFields(null);
        if (snapshot.getTitle()==null || snapshot.getTitle().isBlank()) throw failure(409,"SOURCE_TITLE_MISSING");
        drafts.validateWorkshopSnapshot(snapshot,owner);
        return snapshot;
    }

    private Map<String,Object> version(String id) {
        if (id==null || !id.matches("WKV_[a-f0-9]{32}")) throw failure(400,"INVALID_VERSION_ID");
        var v = mapper.version(id); if (v==null) throw failure(404,"VERSION_NOT_FOUND");
        same(v,"content_hash",hash(text(v,"content_json"))); same(v,"terms_hash",hash(text(v,"terms_json"))); return v;
    }
    private Map<String,Object> offerView(Map<String,Object> v) {
        var result = new LinkedHashMap<String,Object>();
        result.put("purchasedVersionId",v.get("version_id")); result.put("moduleId",v.get("module_id"));
        result.put("contentHash",v.get("content_hash")); result.put("termsHash",v.get("terms_hash"));
        result.put("priceFen",v.get("price_fen")); result.put("currency","CNY");
        result.put("terms",decode(text(v,"terms_json"),Terms.class)); result.put("capabilities",capabilities());
        result.put("title",tree(text(v,"content_json")).path("title").asText());
        result.put("reviewState",Objects.toString(v.get("review_state"),"LEGACY_UNREVIEWED"));
        if(v.get("review_hash")!=null)result.put("reviewHash",v.get("review_hash"));
        if(v.get("audit_task_id")!=null)result.put("auditTaskId",v.get("audit_task_id"));
        result.put("sourceKind",Objects.toString(v.get("source_kind"),"LEGACY_PUBLIC"));
        var channelPrices=new ArrayList<Map<String,Object>>();
        channelPrices.add(Map.of("provider","WECHAT_APP","currency","CNY","amountFen",v.get("price_fen")));
        if(v.get("review_snapshot")!=null)for(var product:tree(text(v,"review_snapshot")).path("appleProducts"))
            channelPrices.add(Map.of("provider","APPLE_IAP","productId",product.path("product_id").asText(),
                "environment",product.path("environment").asText(),"currency",product.path("currency").asText(),
                "amountMilliunits",product.path("price_milliunits").asLong()));
        result.put("channelPrices",channelPrices);return result;
    }
    private Map<String,Object> orderView(Map<String,Object> row) {
        var result = new LinkedHashMap<String,Object>();
        result.put("orderNo",row.get("order_no")); result.put("orderLineId",row.get("id"));
        result.put("purchasedVersionId",row.get("version_id"));
        if("APPLE_IAP".equals(row.get("provider"))) {
            result.put("amountMilliunits",row.get("amount_milliunits"));
            result.put("productId",row.get("apple_product_id"));result.put("appAccountToken",row.get("app_account_token"));
        } else result.put("amountFen",row.get("amount_fen"));
        result.put("currency",Objects.toString(row.get("currency"),"CNY")); result.put("provider",row.get("provider")); result.put("state",row.get("state"));
        result.put("licenseStatus",row.get("license_status"));
        if (row.get("paid_at")!=null) result.put("licenseId",row.get("order_no"));
        if (row.get("refund_no")!=null) result.put("refundNo",row.get("refund_no"));
        return result;
    }
    private Map<String,Object> licenseView(Map<String,Object> row) {
        var v = version(text(row,"version_id")); Terms terms = decode(text(v,"terms_json"),Terms.class);
        var result = new LinkedHashMap<String,Object>();
        result.put("licenseId",row.get("order_no")); result.put("moduleId",v.get("module_id"));
        result.put("purchasedVersionId",v.get("version_id")); result.put("contentHash",v.get("content_hash"));
        result.put("termsVersion",terms.version()); result.put("termsHash",v.get("terms_hash"));
        result.put("acquiredAt",instant(row.get("paid_at"))); result.put("status",row.get("license_status"));
        result.put("commercialUse",terms.commercialUse()?"ALLOWED":"PROHIBITED"); result.put("adaptation","LOCAL_ADAPTATION");
        result.put("translation","PROHIBITED"); result.put("allowedRegions",terms.allowedRegions());
        result.put("themeLimit",Map.of("unlimited",false,"maximum",terms.installLimit()));
        result.put("merchantLimit",Map.of("unlimited",false,"maximum",0)); result.put("runLimit",Map.of("unlimited",false,"maximum",0));
        result.put("acquisition","PAID"); result.put("buyerKind","INDIVIDUAL"); result.put("useDuration","PERPETUAL_PURCHASED_VERSION");
        result.put("updates","EXACT_PURCHASED_VERSION"); result.put("redistribution","PROHIBITED");
        result.put("contentUseStatus","ACTIVE".equals(row.get("license_status"))?"PRIVATE_DRAFT_INSTALL_AVAILABLE":"LICENSE_DISABLED");
        result.put("purchaseActionStatus","EXACT_VERSION_PURCHASED"); return result;
    }
    private Map<String,Object> targetView(CmsMemberTemplate target) {
        return Map.of("targetDraftId",target.getId(),"targetRevision",revision(target),"targetPayloadHash",targetHash(target),
                "businessType","MEMBER_TEMPLATE","title",Objects.toString(target.getTitle(),""));
    }
    private String targetHash(CmsMemberTemplate target) { return hash(encode(target)); }
    private long revision(CmsMemberTemplate target) {
        Date date = target.getUpdateTime()!=null ? target.getUpdateTime() : target.getCreateTime();
        if (date==null || date.getTime()<=0) throw failure(409,"TARGET_REVISION_MISSING"); return date.getTime();
    }
    private static void privateOwned(CmsMemberTemplate draft,long owner) {
        if (draft==null || !Objects.equals(draft.getMemberId(),owner) || !Integer.valueOf(0).equals(draft.getDelFlag())
                || !Integer.valueOf(0).equals(draft.getDraftStatus()) || !Long.valueOf(0).equals(draft.getStatus())
                || !Long.valueOf(0).equals(draft.getPublishStatus()))
            throw failure(403,"TARGET_NOT_OWNED_PRIVATE_DRAFT");
    }
    private Map<String,Object> owned(long owner,String no) {
        if (no==null || !no.matches("WKO_[a-f0-9]{32}")) throw failure(400,"INVALID_ORDER_ID");
        var row = mapper.lockOrder(no);
        if (row==null || number(row,"buyer_id")!=owner) throw failure(404,"ORDER_NOT_FOUND"); return row;
    }
    private Map<String,Object> paidOwned(long owner,String no,boolean requireActive) {
        var row = owned(owner,no); if (row.get("paid_at")==null) throw failure(404,"LICENSE_NOT_FOUND");
        if (requireActive) active(row); return row;
    }
    private static void active(Map<String,Object> row) {
        if (!"PAID".equals(row.get("state")) || !"ACTIVE".equals(row.get("license_status"))) throw failure(403,"LICENSE_DISABLED");
    }
    private static void requirePayment(Map<String,Object> order,WechatVerifiedPaymentFact fact) {
        if (!"WECHAT_APP".equals(order.get("provider")) || fact.kind()!=WechatVerifiedPaymentFact.Kind.PAYMENT || !Objects.equals(order.get("order_no"),fact.businessOutTradeNo())
                || fact.totalFen()!=number(order,"amount_fen") || fact.amountFen()!=fact.totalFen()
                || !"APP".equals(fact.tradeType()) || !"CNY".equals(fact.currency())) throw failure(409,"PAYMENT_FACT_CONFLICT");
    }
    private static Map<String,Object> mutation(Map<String,Object> order) {
        Map<String,Object> row = new HashMap<>(); row.put("orderNo",order.get("order_no")); row.put("owner",order.get("buyer_id")); return row;
    }
    private void requireChannel() { if (!appEnabled || !payments.isAppReady()) throw failure(409,"CHANNEL_NOT_CONFIGURED"); }
    private static void validateTerms(Terms terms) {
        if (terms==null || terms.version()==null || terms.version().isBlank() || terms.version().length()>80
                || terms.text()==null || terms.text().isBlank() || terms.text().length()>12000 || terms.installLimit()<1
                || terms.allowedRegions()==null || terms.allowedRegions().isEmpty() || terms.allowedRegions().size()>100
                || terms.allowedRegions().stream().anyMatch(r -> r==null || !r.matches("[0-9]{6}"))) throw failure(400,"INVALID_TERMS");
    }
    private static Reference reference(Map<String,String> observed) {
        try { return new Reference(Long.parseLong(observed.get("verified_fact_id")),observed.get("verified_fact_hash")); }
        catch (RuntimeException invalid) { throw failure(409,"VERIFIED_FACT_REQUIRED"); }
    }
    private static Reference paymentReference(Map<String,Object> row) { return new Reference(number(row,"payment_fact_id"),text(row,"payment_fact_hash")); }
    private static long cursor(Long before) { if (before!=null && before<=0) throw failure(400,"INVALID_CURSOR"); return before==null?Long.MAX_VALUE:before; }
    private static BigDecimal yuan(Map<String,Object> row) { return BigDecimal.valueOf(number(row,"amount_fen"),2); }
    private static void same(Map<String,Object> row,String field,Object expected) {
        Object actual = row==null ? null : row.get(field);
        if (actual instanceof Number && expected instanceof Number) {
            if (((Number)actual).longValue()==((Number)expected).longValue()) return;
        } else if (actual!=null && actual.equals(expected)) return;
        throw failure(409,"FACT_OR_IDEMPOTENCY_CONFLICT");
    }
    private static String instant(Object date) { return date instanceof Date d ? d.toInstant().toString() : Objects.toString(date,""); }
    private static long number(Map<String,Object> row,String field) {
        if (row==null || !(row.get(field) instanceof Number n)) throw failure(409,"PERSISTED_FACT_MISSING"); return n.longValue();
    }
    private static String text(Map<String,Object> row,String field) {
        if (row==null || !(row.get(field) instanceof String s)) throw failure(409,"PERSISTED_FACT_MISSING"); return s;
    }
    private static void member(long member) { if (member<=0) throw failure(401,"LOGIN_REQUIRED"); }
    private static void key(String key) { if (key==null || !key.matches("[A-Za-z0-9_-]{1,80}")) throw failure(400,"INVALID_REQUEST_ID"); }
    private static void digest(String value) { if (value==null || !value.matches("[a-f0-9]{64}")) throw failure(400,"INVALID_HASH"); }
    private static void changed(int count) { if (count!=1) throw new IllegalStateException("Workshop durable mutation lost ownership"); }
    private static String random() { return UUID.randomUUID().toString().replace("-",""); }
    private static String hash(String value) { return WechatVerifiedPaymentFact.sha256(value); }
    private static ServiceException failure(int code,String reason) { return new ServiceException(reason,code); }
    private Map<String,Object> envelope(String schema) {
        Map<String,Object> result = new LinkedHashMap<>(); result.put("schema",schema); result.put("scope","INDIVIDUAL");
        result.put("checkedAt",Instant.now().toString()); return result;
    }
    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            var sorted = json.createObjectNode(); List<String> keys = new ArrayList<>(); node.fieldNames().forEachRemaining(keys::add);
            Collections.sort(keys); for (String key:keys) sorted.set(key,canonical(node.get(key))); return sorted;
        }
        if (node.isArray()) { var array = json.createArrayNode(); node.forEach(n -> array.add(canonical(n))); return array; }
        return node;
    }
    private String encode(Object value) { return canonical(json.valueToTree(value)).toString(); }
    private JsonNode tree(String value) {
        try { return json.readTree(value); } catch (Exception invalid) { throw failure(400,"INVALID_JSON"); }
    }
    private <T> T decode(String value,Class<T> type) {
        try { return json.readValue(value,type); } catch (Exception invalid) { throw failure(400,"INVALID_JSON"); }
    }
    private Map<String,Object> object(String value) {
        try { return json.readValue(value,new TypeReference<LinkedHashMap<String,Object>>(){}); }
        catch (Exception invalid) { throw failure(409,"PERSISTED_RECEIPT_INVALID"); }
    }
}
