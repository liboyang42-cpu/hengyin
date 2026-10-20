package com.chengyinhub.business.service.support.release;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import com.fasterxml.jackson.databind.node.*;
import org.mybatis.spring.SqlSessionTemplate;
import java.util.*;

/** Exact captured reference scope, not AI enablement, knowledge review or rights authority. */
final class ContentCaptureNpcEvidence {
    private ContentCaptureNpcEvidence() { }

    static void capture(SqlSessionTemplate session, ContentCaptureRows rows) {
        if (rows == null || rows.topic == null || rows.topic.getId() == null || rows.nodes == null) throw invalid();
        Set<Long> sourceIds = new HashSet<>();
        for (CmsTopicNode node : rows.nodes) {
            if (node == null || node.getId() == null || node.getId() <= 0 || !sourceIds.add(node.getId())
                    || !rows.topic.getId().equals(node.getTopicId()) || !Long.valueOf(1).equals(node.getStatus())
                    || (node.getMerchantMemberId() != null && node.getMerchantMemberId() <= 0)) throw invalid();
        }
        NpcProfileMapper profiles = session.getMapper(NpcProfileMapper.class);
        MmsMerchantMapper merchants = session.getMapper(MmsMerchantMapper.class);
        for (CmsTopicNode node : rows.nodes) {
            if (node.getId() == null || node.getId() <= 0) throw invalid();
            if (rows.nodeNpcs.put(node.getId(), nonnull(profiles.selectEnabledByScope(4, node.getId()))) != null) throw invalid();
            Long memberId = node.getMerchantMemberId();
            if (memberId == null || rows.npcMerchants.containsKey(memberId)) continue;
            if (memberId <= 0) throw invalid();
            MmsMerchant probe = new MmsMerchant(); probe.setMemberId(memberId);
            List<MmsMerchant> matches = nonnull(merchants.selectMmsMerchantList(probe));
            if (matches.size() > 1) throw invalid(); // Never choose an arbitrary merchant for a member.
            MmsMerchant merchant = matches.isEmpty() ? null : matches.get(0);
            if (merchant != null && (merchant.getId() == null || merchant.getId() <= 0
                    || !memberId.equals(merchant.getMemberId()))) throw invalid();
            if (!matches.isEmpty() && merchant == null) throw invalid();
            rows.npcMerchants.put(memberId, merchant);
            if (available(merchant)) {
                // scope 2 is a merchant row ID, NOT CmsTopicNode.merchantMemberId.
                if (rows.merchantNpcs.put(merchant.getId(), nonnull(profiles.selectEnabledByScope(2, merchant.getId()))) != null) throw invalid();
            }
        }
        rows.npcScopesCaptured = true;
        validate(rows);
    }

    static void validate(ContentCaptureRows rows) {
        if (!rows.npcScopesCaptured) {
            if (!rows.nodeNpcs.isEmpty() || !rows.npcMerchants.isEmpty() || !rows.merchantNpcs.isEmpty()) throw invalid();
            return;
        }
        Set<Long> nodeIds = new HashSet<>(), memberIds = new HashSet<>(), merchantIds = new HashSet<>(), npcIds = new HashSet<>();
        for (CmsTopicNode node : rows.nodes) {
            if (node.getId() == null || node.getId() <= 0 || !rows.topic.getId().equals(node.getTopicId())
                    || !Long.valueOf(1).equals(node.getStatus()) || !nodeIds.add(node.getId())) throw invalid();
            if (node.getMerchantMemberId() != null) {
                if (node.getMerchantMemberId() <= 0) throw invalid();
                memberIds.add(node.getMerchantMemberId());
            }
        }
        if (!nodeIds.equals(rows.nodeNpcs.keySet()) || !memberIds.equals(rows.npcMerchants.keySet())) throw invalid();
        for (Map.Entry<Long, MmsMerchant> entry : rows.npcMerchants.entrySet()) {
            MmsMerchant merchant = entry.getValue();
            if (merchant == null) continue;
            if (merchant.getId() == null || merchant.getId() <= 0 || !entry.getKey().equals(merchant.getMemberId())) throw invalid();
            if (available(merchant) && !merchantIds.add(merchant.getId())) throw invalid();
        }
        if (!merchantIds.equals(rows.merchantNpcs.keySet())) throw invalid();
        validateProfiles(rows.nodeNpcs, 4, npcIds);
        validateProfiles(rows.merchantNpcs, 2, npcIds);
    }

    private static void validateProfiles(Map<Long,List<NpcProfile>> scopes, int type, Set<Long> ids) {
        for (Map.Entry<Long,List<NpcProfile>> scope : scopes.entrySet()) {
            if (scope.getValue() == null || (type == 4 && scope.getValue().size() > 1)) throw invalid();
            for (NpcProfile profile : scope.getValue()) {
                if (profile == null || profile.getId() == null || profile.getId() <= 0 || !ids.add(profile.getId())
                        || !Integer.valueOf(type).equals(profile.getScopeType()) || !scope.getKey().equals(profile.getScopeId())
                        || !Integer.valueOf(1).equals(profile.getEnabled()) || !Integer.valueOf(1).equals(profile.getAuditStatus())
                        || !"0".equals(profile.getDelFlag())) throw invalid();
            }
        }
    }

    static Set<Long> availableIds(ContentCaptureRows rows) {
        Set<Long> ids = new LinkedHashSet<>();
        if (rows.npcScopesCaptured) {
            for (List<NpcProfile> values : rows.nodeNpcs.values()) for (NpcProfile p : values) ids.add(p.getId());
            for (List<NpcProfile> values : rows.merchantNpcs.values()) for (NpcProfile p : values) ids.add(p.getId());
        }
        return ids;
    }

    static ObjectNode project(ContentCaptureRows rows) {
        ObjectNode root = CanonicalContentJson.JSON.createObjectNode();
        root.put("contract", "questify.npc-scope-capture.v1");
        root.put("status", rows.npcScopesCaptured ? "CAPTURED_SCOPE_ONLY" : "NOT_CAPTURED");
        root.put("aiEnablementInferred", false); root.put("knowledgeReviewStatus", "UNRESOLVED");
        root.put("mediaAndVoiceRightsStatus", "UNRESOLVED");
        ArrayNode bindings = root.putArray("nodeBindings");
        List<CmsTopicNode> nodes = new ArrayList<>(rows.nodes); nodes.sort(Comparator.comparing(CmsTopicNode::getId));
        if (rows.npcScopesCaptured) for (CmsTopicNode node : nodes) {
            ObjectNode binding = bindings.addObject(); binding.put("nodeId", node.getId());
            binding.put("nodeSourceReference", "cms_topic_node:" + node.getId());
            put(binding,"merchantMemberId",node.getMerchantMemberId());
            MmsMerchant merchant = rows.npcMerchants.get(node.getMerchantMemberId());
            put(binding,"merchantSourceReference",merchant == null ? null : "mms_merchant:" + merchant.getId());
            binding.put("merchantAvailability",node.getMerchantMemberId() == null ? "NOT_APPLICABLE"
                    : merchant == null ? "MISSING" : available(merchant) ? "CAPTURED_AVAILABLE" : "UNAVAILABLE");
            if (merchant != null) {
                put(binding,"merchantStatus",merchant.getStatus()); put(binding,"merchantAccountStatus",merchant.getAccountStatus());
                put(binding,"merchantDelFlag",merchant.getDelFlag());
            }
            ArrayNode ids = binding.putArray("nodeNpcIds"); for (NpcProfile p : sorted(rows.nodeNpcs.get(node.getId()))) ids.add(p.getId());
            ids = binding.putArray("merchantNpcIds");
            if (available(merchant)) for (NpcProfile p : sorted(rows.merchantNpcs.get(merchant.getId()))) ids.add(p.getId());
        }
        ArrayNode profiles = root.putArray("profiles");
        Map<Long,NpcProfile> ordered = new TreeMap<>();
        for (List<NpcProfile> values : rows.nodeNpcs.values()) for (NpcProfile p : values) ordered.put(p.getId(),p);
        for (List<NpcProfile> values : rows.merchantNpcs.values()) for (NpcProfile p : values) ordered.put(p.getId(),p);
        for (NpcProfile p : ordered.values()) {
            ObjectNode item = profiles.addObject(); item.put("sourceReference","npc_profile:" + p.getId());
            put(item,"id",p.getId()); put(item,"scopeType",p.getScopeType()); put(item,"scopeId",p.getScopeId());
            put(item,"enabled",p.getEnabled()); put(item,"auditStatus",p.getAuditStatus()); put(item,"delFlag",p.getDelFlag());
            put(item,"sort",p.getSort()); put(item,"name",p.getName()); put(item,"avatar",p.getAvatar());
            put(item,"persona",p.getPersona()); put(item,"greeting",p.getGreeting()); put(item,"knowledge",p.getKnowledge());
            put(item,"voiceId",p.getVoiceId()); put(item,"voiceStatus",p.getVoiceStatus()); put(item,"voiceSample",p.getVoiceSample());
            item.putNull("reviewedKnowledgeVersion"); item.put("knowledgeAuthority", "NOT_ESTABLISHED");
            item.put("voiceRightsAuthority", "NOT_ESTABLISHED");
        }
        return root;
    }

    private static boolean available(MmsMerchant m) { return m != null && Long.valueOf(1).equals(m.getStatus())
            && Long.valueOf(1).equals(m.getAccountStatus()) && Integer.valueOf(0).equals(m.getDelFlag()); }
    private static List<NpcProfile> sorted(List<NpcProfile> values) { List<NpcProfile> result=new ArrayList<>(values); result.sort(Comparator.comparing(NpcProfile::getId)); return result; }
    private static <T> List<T> nonnull(List<T> values) { return values == null ? Collections.emptyList() : values; }
    private static void put(ObjectNode target,String key,Object value) { target.set(key,CanonicalContentJson.JSON.valueToTree(value)); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("NPC or merchant scope source mismatch"); }
}
