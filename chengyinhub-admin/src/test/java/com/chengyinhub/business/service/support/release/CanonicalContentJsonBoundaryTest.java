package com.chengyinhub.business.service.support.release;

import com.chengyinhub.business.domain.*;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CanonicalContentJsonBoundaryTest {
    @Test void exactlyOneHundredThousandTokensRoundTripButOneMoreFailsBothDirections() throws Exception {
        ObjectNode root=CanonicalContentJson.JSON.createObjectNode();
        com.fasterxml.jackson.databind.node.ArrayNode values=root.putArray("values");
        for(int i=0;i<99995;i++)values.add(0); // object, field, array, scalars, array end, object end
        String canonical=CanonicalContentJson.write(root);assertEquals(100000,tokens(canonical));
        assertEquals(canonical,CanonicalContentJson.write(CanonicalContentJson.object(canonical,"boundary")));
        values.add(0);String over=CanonicalContentJson.JSON.writeValueAsString(root);assertEquals(100001,tokens(over));
        assertThrows(IllegalArgumentException.class,()->CanonicalContentJson.write(root));
        assertThrows(IllegalArgumentException.class,()->CanonicalContentJson.object(over,"boundary"));
    }
    @Test void aggregateDepthBoundaryMatchesStoredParserInsteadOfOffByOneOutput() throws Exception {
        ObjectNode root=nested(100);String canonical=CanonicalContentJson.write(root);
        assertEquals(canonical,CanonicalContentJson.write(CanonicalContentJson.object(canonical,"depth")));
        ObjectNode over=nested(101);String raw=CanonicalContentJson.JSON.writeValueAsString(over);
        assertThrows(IllegalArgumentException.class,()->CanonicalContentJson.write(over));
        assertThrows(IllegalArgumentException.class,()->CanonicalContentJson.object(raw,"depth"));
    }
    @Test void aggregateCharacterBoundaryIsTheSameForWriterAndReader() throws Exception {
        ObjectNode root=CanonicalContentJson.JSON.createObjectNode();char[] chars=new char[1048568];Arrays.fill(chars,'a');
        root.put("v",new String(chars));String exact=CanonicalContentJson.write(root);assertEquals(1048576,exact.length());
        assertEquals(exact,CanonicalContentJson.write(CanonicalContentJson.object(exact,"characters")));
        root.put("v",new String(chars)+"a");String over=CanonicalContentJson.JSON.writeValueAsString(root);
        assertThrows(IllegalArgumentException.class,()->CanonicalContentJson.write(root));
        assertThrows(IllegalArgumentException.class,()->CanonicalContentJson.object(over,"characters"));
    }
    @Test void largeUnderLimitNpcCandidateRoundTripsThroughReceiptUnchanged() throws Exception {
        ContentCaptureCandidate candidate=new ContentCaptureBuilder().build(npcRows(2400));
        assertTrue(candidate.serverOnlyJson().length()<1048576);assertTrue(tokens(candidate.serverOnlyJson())<100000);
        ContentCaptureSnapshot row=new ContentCaptureSnapshot();row.setId(1L);row.setTopicId(candidate.topicId());row.setCapturedByMemberId(7L);
        row.setRequestId("boundary");row.setSourceConfigVersion(candidate.draftConfigVersion());row.setContentHash(candidate.contentHash());
        row.setCanonicalJson(candidate.serverOnlyJson());row.setState("FROZEN_INCOMPLETE");row.setContractVersion("questify.content-capture-candidate.v1");
        row.setCanonicalizationVersion("questify.canonical-content-json.v1");
        ContentCaptureReceipt receipt=ContentCaptureReceipt.verify(row,10,7,"boundary",candidate.draftConfigVersion(),candidate.contentHash());
        assertEquals(candidate.serverOnlyJson(),receipt.serverOnlyJson());assertEquals(candidate.contentHash(),receipt.contentHash());
    }
    @Test void reviewerNpcAggregateReproductionIsRejectedAtPreview() {
        assertThrows(IllegalArgumentException.class,()->new ContentCaptureBuilder().build(npcRows(2450)));
    }
    static ContentCaptureRows npcRows(int count) {
        ContentCaptureRows rows=TopicPublicationValidationContextTest.clean();rows.npcScopesCaptured=true;
        for(CmsTopicNode node:rows.nodes)rows.nodeNpcs.put(node.getId(),Collections.emptyList());
        rows.nodes.get(0).setMerchantMemberId(70L);rows.npcMerchants.put(70L,ConsistentContentCaptureTest.merchant(700,70));
        List<NpcProfile> profiles=new ArrayList<>();for(int i=0;i<count;i++)profiles.add(ConsistentContentCaptureTest.npc(i+1000,2,700));
        rows.merchantNpcs.put(700L,profiles);return rows;
    }
    private static ObjectNode nested(int depth){ObjectNode root=CanonicalContentJson.JSON.createObjectNode(),current=root;for(int i=1;i<depth;i++)current=current.putObject("nested");current.put("v",0);return root;}
    private static int tokens(String raw)throws Exception {int count=0;try(JsonParser p=CanonicalContentJson.JSON.getFactory().createParser(raw)){while(p.nextToken()!=null)count++;}return count;}
}
