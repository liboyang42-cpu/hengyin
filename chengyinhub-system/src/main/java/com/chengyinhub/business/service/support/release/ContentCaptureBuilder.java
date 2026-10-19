package com.chengyinhub.business.service.support.release;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.snapshot.TopicSaleCanonicalV1;
import com.chengyinhub.business.service.support.JourneyContentSnapshotBuilder;
import com.chengyinhub.business.service.support.RewardRulesValidator;
import com.chengyinhub.business.service.support.TopicCompletionRulePolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Builds additional provenance components without changing legacy runtime JSON or its v1 hash contract. */
final class ContentCaptureBuilder {
    private final JourneyContentSnapshotBuilder legacy = new JourneyContentSnapshotBuilder();

    ContentCaptureCandidate build(ContentCaptureRows r) {
        validateScope(r);
        ContentCaptureNpcEvidence.validate(r);
        long topicId = r.topic.getId();
        ObjectNode root = CanonicalContentJson.JSON.createObjectNode();
        root.put("contract", "questify.content-capture-candidate.v1"); root.put("schemaVersion", 1);
        root.put("topicId", topicId); root.put("draftConfigVersion", r.topic.getConfigVersion());
        root.put("sourceMode", "AUTHOR_DRAFT"); root.put("completeRelease", false);
        ObjectNode components = root.putObject("components");
        Map<Long,String> configs = new LinkedHashMap<>();
        for (CmsMemberTemplate t : r.templates.values()) {
            strictObjectIfPresent(t.getAdvancedConfigJson(), "advancedConfigJson");
            strictObjectIfPresent(t.getPreferenceJson(), "preferenceJson");
            strictObjectIfPresent(t.getSensorConfig(), "sensorConfig");
            rewardRules(t.getRewardRulesJson());
            configs.put(t.getId(), t.getAdvancedConfigJson());
        }
        strictObjectIfPresent(r.topic.getJourneyRulesJson(), "journeyRulesJson");
        strictObjectIfPresent(r.topic.getJourneyStoryJson(), "journeyStoryJson");
        for (CmsTopicChapter c : r.chapters) strictArrayIfPresent(c.getBlocksJson(), "blocksJson");
        String route;
        if ("BRANCH_GRAPH".equals(r.topic.getRouteMode())) {
            route = CanonicalContentJson.write(CanonicalContentJson.object(r.topic.getRouteGraphJson(), "draft route"));
        } else if ("LINEAR".equals(r.topic.getRouteMode())) {
            route = legacy.linearSnapshot(r.nodes);
        } else throw invalid("Explicit draft route mode is required");
        String frozen = legacy.freezeJourney(route, r.topic, r.nodes, configs, r.templates, r.keys, r.chapters);
        component(components,"legacyRouteSnapshot", "FROZEN", CanonicalContentJson.object(frozen,"legacy snapshot"));
        ObjectNode order = CanonicalContentJson.JSON.createObjectNode();
        ArrayNode ids = order.putArray("orderedNodeIds"); ArrayNode nodes = order.putArray("nodes");
        for (CmsTopicNode n : r.nodes) {
            ids.add(n.getId()); ObjectNode node = nodes.addObject();
            put(node,"id",n.getId()); put(node,"topicId",n.getTopicId()); put(node,"chapterId",n.getChapterId());
            put(node,"templateId",n.getTemplateId()); put(node,"sortId",n.getSortId());
            put(node,"unlockAfterNodeId",n.getUnlockAfterNodeId()); put(node,"name",n.getName());
            put(node,"description",n.getDescription()); put(node,"address",n.getAddress());
            put(node,"longitude",n.getLongitude()); put(node,"latitude",n.getLatitude()); put(node,"imgUrl",n.getImgUrl());
            put(node,"merchantMemberId",n.getMerchantMemberId()); put(node,"nodeAuditStatus",n.getNodeAuditStatus());
        }
        component(components,"orderedNodes", "FROZEN", order);
        ArrayNode chapterSources=CanonicalContentJson.JSON.createArrayNode();
        List<CmsTopicChapter> orderedChapters=new ArrayList<>(r.chapters);
        orderedChapters.sort(Comparator.comparing(CmsTopicChapter::getId));
        for(CmsTopicChapter c:orderedChapters) {
            ObjectNode chapter=chapterSources.addObject(); put(chapter,"id",c.getId()); put(chapter,"topicId",c.getTopicId());
            put(chapter,"sortId",c.getSortId()); put(chapter,"required",c.getRequired()); put(chapter,"name",c.getName());
            put(chapter,"description",c.getDescription()); put(chapter,"blocksJson",c.getBlocksJson());
            put(chapter,"imgArr",c.getImgArr()); put(chapter,"audioUrl",c.getAudioUrl()); put(chapter,"atmospherePreset",c.getAtmospherePreset());
        }
        component(components,"chapterSources","FROZEN",chapterSources);
        ArrayNode keys = CanonicalContentJson.JSON.createArrayNode();
        List<ThemeStateKey> sortedKeys = new ArrayList<>(r.keys);
        sortedKeys.sort(Comparator.comparing(ThemeStateKey::getStateKey));
        for (ThemeStateKey k : sortedKeys) {
            ObjectNode key=keys.addObject(); put(key,"id",k.getId()); put(key,"stateKey",k.getStateKey());
            put(key,"keyType",k.getKeyType()); put(key,"scope",k.getScope()); put(key,"label",k.getLabel());
            put(key,"initialValue",k.getInitialValue());
        }
        component(components,"stateKeyDeclarations","FROZEN",keys);
        component(components,"rewardInputs","FROZEN", rewards(r));
        component(components,"sourceProvenance","FROZEN", sources(r));
        component(components,"commercialRightsEvidence", r.sale == null ? "MISSING" : "FROZEN", rights(r));
        component(components,"npcScopeEvidence",r.npcScopesCaptured ? "FROZEN" : "MISSING",ContentCaptureNpcEvidence.project(r));
        ObjectNode publicationChecks = new TopicPublicationValidationContext(r).validate();
        component(components,"publicationReferenceChecks","FROZEN",publicationChecks);
        ArrayNode gaps=root.putArray("unresolvedDependencies");
        if ("BLOCKED".equals(publicationChecks.path("status").asText())) gaps.add("PUBLICATION_REFERENCE_BLOCKERS");
        for(String gap: Arrays.asList("REGISTERED_RULE_REFERENCES","REWARD_POLICY_APPROVAL","RANDOM_DROP_RULES",
                "FULL_XP_BUDGET_INPUTS","COUPON_OWNER_AUTHORITY","LIVE_COUPON_ISSUANCE","ASSET_RIGHTS","ASSET_INVENTORY_COMPLETE",
                "TEMPLATE_LICENSE_AUTHORITY","ACTIVITY_MODULE_REFERENCES","AI_KNOWLEDGE_APPLICABILITY",
                "RELEASE_ID_AND_APPROVAL")) gaps.add(gap);
        if (r.sale == null) gaps.add("COMMERCIAL_RIGHTS_EVIDENCE");
        for (Map.Entry<Long,SmsCoupon> c:r.coupons.entrySet()) if(c.getValue()==null) gaps.add("MISSING_COUPON:"+c.getKey());
        ObjectNode dependencies=root.putObject("dependencyStatus");
        for(JsonNode gap:gaps) dependencies.put(gap.asText(),"MISSING");
        dependencies.put("REFERENCED_COUPON_TERMS", r.coupons.isEmpty()?"NOT_APPLICABLE"
                : r.coupons.containsValue(null)?"MISSING":"FROZEN");
        return new ContentCaptureCandidate(topicId,r.topic.getConfigVersion(),CanonicalContentJson.write(root));
    }

    private ObjectNode rewards(ContentCaptureRows r) {
        ObjectNode value=CanonicalContentJson.JSON.createObjectNode();
        value.put("meaning","CAPTURED_INPUTS_ONLY_NOT_ISSUANCE_AUTHORITY");
        ArrayNode nodes=value.putArray("nodes");
        for(CmsTopicNode n:r.nodes) {
            ObjectNode row=nodes.addObject(); put(row,"nodeId",n.getId()); put(row,"nodeXpValue",n.getXpValue());
            CmsMemberTemplate t=r.templates.get(n.getTemplateId());
            ObjectNode template=row.putObject("template");
            if(t!=null) {
                put(template,"sourceReference","cms_member_template:"+t.getId()); put(template,"ownerMemberId",t.getMemberId());
                put(template,"xpValue",t.getXpValue()); put(template,"couponId",t.getCouponId());
                put(template,"rewardRulesJson",t.getRewardRulesJson()); put(template,"feedbackText",t.getFeedbackText());
                put(template,"medalName",t.getMedalName()); put(template,"medalImg",t.getMedalImg()); put(template,"medalStyle",t.getMedalStyle());
            }
            CmsRegistrationMerchant m=r.merchants.get(n.getId()); ObjectNode merchant=row.putObject("merchantOverride");
            if(m!=null) {
                put(merchant,"sourceReference","cms_registration_merchant:"+m.getId()); put(merchant,"memberId",m.getMemberId());
                put(merchant,"couponId",m.getCouponId()); put(merchant,"ruleInstructions",m.getRuleInstructions()); put(merchant,"feedbackText",m.getFeedbackText());
                put(merchant,"auditStatus",m.getAuditStatus()); put(merchant,"delFlag",m.getDelFlag());
            }
            // Record input precedence, do not resolve eligibility or invoke a reward service.
            row.put("couponPrecedence", "POSITIVE_MERCHANT_OVERRIDE_THEN_TEMPLATE");
        }
        ObjectNode completion=value.putObject("completion"); put(completion,"xpBudget",r.topic.getXpBudget());
        put(completion,"finishMedalName",r.topic.getFinishMedalName()); put(completion,"finishMedalImg",r.topic.getFinishMedalImg());
        put(completion,"completeRewardCouponId",r.topic.getCompleteRewardCouponId()); put(completion,"completeRuleJson",r.topic.getCompleteRuleJson());
        ArrayNode coupons=value.putArray("couponTerms");
        for(Map.Entry<Long,SmsCoupon> e:new TreeMap<>(r.coupons).entrySet()) {
            ObjectNode c=coupons.addObject(); c.put("sourceReference","sms_coupon:"+e.getKey());
            SmsCoupon q=e.getValue(); c.put("status",q==null?"MISSING":"FROZEN"); if(q==null) continue;
            put(c,"memberId",q.getMemberId()); put(c,"merchantId",q.getMerchantId()); put(c,"scopeType",q.getScopeType());
            put(c,"couponType",q.getCouponType()); put(c,"name",q.getName()); put(c,"description",q.getDescription());
            put(c,"amount",q.getAmount()); put(c,"minPoint",q.getMinPoint()); put(c,"useType",q.getUseType()); put(c,"platform",q.getPlatform());
            put(c,"enableTime",q.getEnableTime()); put(c,"startTime",q.getStartTime()); put(c,"endTime",q.getEndTime());
            c.put("liveEligibilityRequired",true); // Deliberately omit counts, remaining inventory and current issuance status.
        }
        return value;
    }

    private ObjectNode sources(ContentCaptureRows r) {
        ObjectNode source=CanonicalContentJson.JSON.createObjectNode();
        put(source,"topicSourceReference",r.topic.getSourceTemplateTopicId()==null || r.topic.getSourceTemplateTopicId()<=0?null:"cms_topic:"+r.topic.getSourceTemplateTopicId());
        ArrayNode templates=source.putArray("templates");
        for(CmsTopicNode n:r.nodes) {
            CmsMemberTemplate t=r.templates.get(n.getTemplateId()); if(t==null) continue;
            ObjectNode row=templates.addObject(); put(row,"nodeId",n.getId()); put(row,"sourceReference","cms_member_template:"+t.getId());
            put(row,"originalTemplateReference",t.getOriginalTemplateId()==null || t.getOriginalTemplateId()<=0?null:"cms_template_library:"+t.getOriginalTemplateId());
            row.putNull("immutableSourceVersion"); row.put("licenseStatus","MISSING");
            ObjectNode selected=row.putObject("capturedProjection");
            put(selected,"advancedConfigJson",t.getAdvancedConfigJson()); put(selected,"questionAnswer",t.getQuestionAnswer());
            put(selected,"correctAnswer",t.getCorrectAnswer()); put(selected,"rewardRulesJson",t.getRewardRulesJson());
            put(selected,"couponId",t.getCouponId()); put(selected,"xpValue",t.getXpValue());
            put(selected,"imgUrl",t.getImgUrl()); put(selected,"audioUrl",t.getAudioUrl()); put(selected,"questionImg",t.getQuestionImg());
            put(selected,"questionAudio",t.getQuestionAudio()); put(selected,"storyImg",t.getStoryImg());
            row.put("capturedProjectionHash",CanonicalContentJson.sha256(CanonicalContentJson.write(selected)));
        }
        return source;
    }

    private ObjectNode rights(ContentCaptureRows r) {
        ObjectNode result=CanonicalContentJson.JSON.createObjectNode();
        result.put("rightsApprovalInferred",false); result.put("assetMappingStatus","MISSING");
        if(r.sale==null) return result;
        TopicSaleSnapshot s=r.sale;
        if(!r.topic.getId().equals(s.getTopicId()) || !TopicSaleCanonicalV1.SCHEMA_VERSION.equals(s.getSchemaVersion())
                || s.getId()==null || s.getSourceReviewTaskId()==null) throw invalid("Commercial evidence source mismatch");
        JsonNode sale=CanonicalContentJson.object(s.getCanonicalJson(),"sale canonical evidence");
        JsonNode rights=sale.get("contentRightsPackage");
        if(rights==null || !rights.isObject()) throw invalid("Commercial rights package missing");
        // Existing canonical-15 is evidence, not a new authorization inferred from a hash.
        result.put("sourceReference","topic_sale_snapshot:"+s.getId()); put(result,"schemaVersion",s.getSchemaVersion());
        put(result,"parameterVersion",s.getParameterVersion()); put(result,"sourceReviewTaskId",s.getSourceReviewTaskId());
        put(result,"frozenAt",s.getFrozenAt()); result.set("contentRightsPackage",rights.deepCopy());
        return result;
    }

    void validateScope(ContentCaptureRows r) {
        if(r==null || r.topic==null || r.topic.getId()==null || r.topic.getId()<=0 || r.topic.getConfigVersion()==null
                || r.topic.getConfigVersion()<0 || r.nodes==null || r.chapters==null || r.keys==null) throw invalid("Incomplete source rows");
        Long topic=r.topic.getId(); Set<Long> nodeIds=new HashSet<>(), chapterIds=new HashSet<>(), sorts=new HashSet<>();
        for(CmsTopicChapter c:r.chapters) if(c==null || c.getId()==null || !topic.equals(c.getTopicId()) || !chapterIds.add(c.getId())) throw invalid("Chapter source mismatch");
        for(CmsTopicNode n:r.nodes) {
            if(n==null || n.getId()==null || n.getId()<=0 || !topic.equals(n.getTopicId()) || !Long.valueOf(1).equals(n.getStatus()) || !nodeIds.add(n.getId())) throw invalid("Node source mismatch");
            if(n.getChapterId()!=null && !chapterIds.contains(n.getChapterId())) throw invalid("Node chapter missing");
            long sort=n.getSortId()==null?0:n.getSortId();
            if(!sorts.add(sort)) throw invalid("Ambiguous legacy node order");
            if(n.getTemplateId()!=null && n.getTemplateId()>0 && !r.templates.containsKey(n.getTemplateId())) throw invalid("Template source missing");
        }
        for(CmsTopicNode n:r.nodes) if(n.getUnlockAfterNodeId()!=null && !nodeIds.contains(n.getUnlockAfterNodeId())) throw invalid("Unlock node reference missing");
        // Same stable sort as runtime; equal effective sort values were rejected, not silently ID-sorted.
        r.nodes=new ArrayList<>(r.nodes); r.nodes.sort(Comparator.comparingLong(n->n.getSortId()==null?0L:n.getSortId()));
        Set<String> keys=new HashSet<>();
        for(ThemeStateKey k:r.keys) if(k==null || !topic.equals(k.getTopicId()) || k.getStateKey()==null
                || !ThemeStateKey.KEY_NAME.matcher(k.getStateKey()).matches() || !keys.add(k.getStateKey())
                || k.getKeyType()==null || k.getScope()==null) throw invalid("State declaration source mismatch");
        for(Map.Entry<Long,CmsMemberTemplate> e:r.templates.entrySet()) {
            CmsMemberTemplate t=e.getValue();
            if(t==null || !e.getKey().equals(t.getId()) || r.nodes.stream().noneMatch(n->e.getKey().equals(n.getTemplateId()))) throw invalid("Template reference mismatch");
            // CmsMemberTemplate.topicId is a query filter, not a persisted ownership field.
        }
        for(Map.Entry<Long,CmsRegistrationMerchant> e:r.merchants.entrySet()) {
            CmsRegistrationMerchant m=e.getValue();
            if(m==null || m.getId()==null || !nodeIds.contains(e.getKey()) || !e.getKey().equals(m.getNodeId())
                    || !topic.equals(m.getTopicId()) || !Long.valueOf(1).equals(m.getAuditStatus())
                    || !Integer.valueOf(0).equals(m.getDelFlag())) throw invalid("Merchant source mismatch");
        }
        Set<Long> couponRefs=new TreeSet<>(); addCoupon(couponRefs,r.topic.getCompleteRewardCouponId());
        couponRefs.addAll(completionCouponIds(r.topic,r.nodes.size()));
        for(CmsMemberTemplate t:r.templates.values()) {
            addCoupon(couponRefs,t.getCouponId()); JsonNode rules=rewardRules(t.getRewardRulesJson());
            if(rules!=null) for(JsonNode rule:rules) if(rule.has("couponId")) addCoupon(couponRefs,rule.get("couponId").longValue());
        }
        for(CmsRegistrationMerchant m:r.merchants.values()) addCoupon(couponRefs,m.getCouponId());
        if(!couponRefs.equals(r.coupons.keySet())) throw invalid("Coupon reference set incomplete or unrelated");
        for(Map.Entry<Long,SmsCoupon> e:r.coupons.entrySet()) if(e.getValue()!=null && !e.getKey().equals(e.getValue().getId())) throw invalid("Coupon source mismatch");
    }

    private static void addCoupon(Set<Long> ids,Long id) { if(id!=null && id>0) ids.add(id); }

    /** Reuse the existing completion policy, with strict parsing before its legacy numeric readers. */
    static List<Long> completionCouponIds(CmsTopic topic,int nodeCount) {
        String raw=topic.getCompleteRuleJson();
        if(raw==null || raw.trim().isEmpty()) return Collections.emptyList();
        JsonNode root=CanonicalContentJson.object(raw,"completeRuleJson");
        for(JsonNode cell:root.path("bingo").path("cells")) {
            JsonNode id=cell.get("couponId");
            if(id!=null && (!id.isIntegralNumber() || !id.canConvertToLong())) throw invalid("Completion coupon integral ID required");
        }
        JsonNode required=root.path("nodeCompletion").get("requiredCount");
        if(required!=null && (!required.isIntegralNumber() || !required.canConvertToInt())) throw invalid("Completion count integral value required");
        TopicCompletionRulePolicy policy=new TopicCompletionRulePolicy();
        policy.validateAndNormalize(raw,nodeCount,topic.getProductType());
        return policy.bingoCouponIds(raw);
    }

    static JsonNode rewardRules(String raw) {
        JsonNode array=strictArrayIfPresent(raw,"rewardRulesJson");
        if(array!=null) for(JsonNode row:array) {
            for(String field:Arrays.asList("couponId","xp")) if(row.has(field) && (!row.get(field).isIntegralNumber()
                    || !row.get(field).canConvertToLong())) throw invalid("Reward integral value required");
            if(row.has("xp") && !row.get("xp").canConvertToInt()) throw invalid("Reward XP is out of range");
        }
        new RewardRulesValidator().validateAndNormalize(raw);
        return array;
    }
    static JsonNode strictArrayIfPresent(String raw,String label) {
        if(raw==null || raw.trim().isEmpty()) return null;
        JsonNode wrapper=CanonicalContentJson.object("{\"value\":"+raw+"}",label);
        if(wrapper.size()!=1 || !wrapper.path("value").isArray()) throw invalid(label+" must be an array");
        return wrapper.get("value");
    }
    private static void strictObjectIfPresent(String raw,String label) { if(raw!=null && !raw.trim().isEmpty()) CanonicalContentJson.object(raw,label); }
    private static void component(ObjectNode root,String name,String status,JsonNode value) {
        ObjectNode c=root.putObject(name); c.put("status",status); c.set("content",value);
        c.put("capturedContentHash",CanonicalContentJson.sha256(CanonicalContentJson.write(value)));
    }
    private static void put(ObjectNode node,String name,Object value) {
        node.set(name,CanonicalContentJson.JSON.valueToTree(value instanceof Date?((Date)value).getTime():value));
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
