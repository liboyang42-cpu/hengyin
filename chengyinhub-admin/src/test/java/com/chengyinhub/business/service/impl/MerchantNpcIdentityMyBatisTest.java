package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.vo.ai.NpcProfileVO;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.support.MerchantNpcIdentityDirectory;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** Real existing MyBatis XML on isolated H2; not a MySQL production/isolation claim. */
class MerchantNpcIdentityMyBatisTest {
    private JdbcTemplate jdbc;
    private SqlSession session;
    private NpcProfileService profiles;
    private CmsTopicServiceImpl publication;
    private PlayEncounterServiceImpl encounter;
    private CmsTopicNode node;
    private MerchantNpcIdentityDirectory directory;
    private NpcProfileMapper npcs;

    @BeforeEach void setUp() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:live-npc-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("create table npc_profile(id bigint primary key,scope_type int,scope_id bigint,name varchar(32),"
                + "enabled int,audit_status int,del_flag varchar(1),sort int,persona varchar(128),knowledge varchar(128))");
        String xml = resource("mapper/business/MmsMerchantMapper.xml");
        String fragment = xml.substring(xml.indexOf("<sql id=\"selectMmsMerchantVo\">"));
        String columns = fragment.substring(fragment.indexOf("select ") + 7, fragment.indexOf(" from mms_merchant"));
        StringBuilder ddl = new StringBuilder("create table mms_merchant(");
        for (String raw : columns.split(",")) {
            String column = raw.trim();
            if (ddl.charAt(ddl.length() - 1) != '(') ddl.append(',');
            ddl.append(column).append(' ');
            if (Arrays.asList("id", "member_id", "status", "account_status", "del_flag").contains(column)) ddl.append("bigint");
            else if (column.equals("create_time") || column.equals("update_time")) ddl.append("timestamp");
            else ddl.append("varchar(2048)");
        }
        jdbc.execute(ddl.append(", primary key(id))").toString());
        String nodeXml = resource("mapper/business/CmsTopicNodeMapper.xml");
        String nodeFragment = nodeXml.substring(nodeXml.indexOf("<sql id=\"selectCmsTopicNodeVo\">"));
        String nodeColumns = nodeFragment.substring(nodeFragment.indexOf("select ") + 7, nodeFragment.indexOf(" from cms_topic_node"));
        StringJoiner nodeDdl = new StringJoiner(",", "create table cms_topic_node(", ")");
        for (String raw : nodeColumns.split(",")) {
            String column = raw.trim();
            String type = column.equals("id") || column.endsWith("_id") || column.equals("status")
                    || column.equals("node_audit_status") || column.equals("xp_value") ? "bigint" : "varchar(2048)";
            if (column.endsWith("_time") && !column.equals("business_time") && !column.equals("node_time")) type = "timestamp";
            nodeDdl.add(column + " " + type);
        }
        jdbc.execute(nodeDdl.toString());
        // The real node mapper also excludes explicitly withdrawn merchant offers.
        // Reuse the repository's existing test schema; the current migration adds this field.
        jdbc.execute(resource("it-schema/merchant-chapter-offer-it-schema.sql"));
        jdbc.execute("ALTER TABLE merchant_chapter_offer ADD COLUMN withdraw_reason VARCHAR(500) NULL");
        jdbc.update("insert into cms_topic_node(id,topic_id,status,merchant_member_id,node_audit_status) values(31,10,1,70,1)");
        Configuration configuration = new Configuration(new Environment("live-npc-h2", new JdbcTransactionFactory(), source));
        configuration.getTypeAliasRegistry().registerAlias("MmsMerchant", MmsMerchant.class);
        configuration.getTypeAliasRegistry().registerAlias("CmsTopicNode", CmsTopicNode.class);
        for (String path : Arrays.asList("mapper/business/CmsTopicNodeMapper.xml", "mapper/business/MmsMerchantMapper.xml", "mapper/business/NpcProfileMapper.xml")) {
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
                new XMLMapperBuilder(input, configuration, path, configuration.getSqlFragments()).parse();
            }
        }
        session = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
        npcs = session.getMapper(NpcProfileMapper.class);
        directory = new MerchantNpcIdentityDirectory(session.getMapper(MmsMerchantMapper.class));
        profiles = new NpcProfileService();
        ReflectionTestUtils.setField(profiles, "merchantNpcIdentityDirectory", directory);
        ReflectionTestUtils.setField(profiles, "npcProfileMapper", npcs);
        publication = new CmsTopicServiceImpl();
        ReflectionTestUtils.setField(publication, "merchantNpcIdentityDirectory", directory);
        ReflectionTestUtils.setField(publication, "npcProfileMapper", npcs);
        node = new CmsTopicNode(); node.setId(31L); node.setTopicId(10L); node.setStatus(1L); node.setMerchantMemberId(70L);
        CmsTopicNodeMapper nodes = session.getMapper(CmsTopicNodeMapper.class);
        ReflectionTestUtils.setField(publication, "cmsTopicNodeMapper", nodes);
        ICmsTopicNodeService nodeService = mock(ICmsTopicNodeService.class);
        when(nodeService.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenAnswer(i -> nodes.selectCmsTopicNodeList(i.getArgument(0)));
        encounter = new PlayEncounterServiceImpl();
        ReflectionTestUtils.setField(encounter, "cmsTopicNodeService", nodeService);
        ReflectionTestUtils.setField(encounter, "npcProfileService", profiles);
    }

    @org.junit.jupiter.api.AfterEach void close() { if (session != null) session.close(); }

    @Test void differingIdsResolveOwnedMerchantNeverNumericMemberAlias() {
        merchant(700, 70, 1, 1, 0); merchant(70, 71, 1, 1, 0);
        npc(901, 2, 700, 1, 1, "0", 0); npc(900, 2, 70, 1, 1, "0", 0);
        assertEquals(901L, profiles.resolveForNodeOrMerchant(31L, 70L).getProfileId());
        assertScopes(901L);
    }

    @Test void equalIdsStillNeedOwnedMerchantDirectoryEvidence() {
        merchant(70, 70, 1, 1, 0); npc(900, 2, 70, 1, 1, "0", 0);
        assertEquals(900L, profiles.resolveForNodeOrMerchant(31L, 70L).getProfileId());
        jdbc.update("update mms_merchant set member_id=71 where id=70"); session.clearCache();
        assertNull(profiles.resolveForNodeOrMerchant(31L, 70L)); assertScopes();
    }

    @Test void missingMappingCannotBorrowForeignNumericScope() {
        merchant(70, 71, 1, 1, 0); npc(900, 2, 70, 1, 1, "0", 0);
        assertNull(profiles.resolveForNodeOrMerchant(31L, 70L)); assertScopes();
    }

    @Test void ambiguousMemberRowsFailClosedEvenIfOnlyOneIsAvailable() {
        merchant(700, 70, 1, 1, 0); merchant(701, 70, 1, 0, 0);
        npc(901, 2, 700, 1, 1, "0", 0); npc(900, 2, 70, 1, 1, "0", 0);
        assertNull(profiles.resolveForNodeOrMerchant(31L, 70L)); assertScopes();
    }

    @Test void unavailableOrUnknownMerchantNeverAddsNpcAuthority() {
        merchant(700, 70, 1, 1, 0); npc(901, 2, 700, 1, 1, "0", 0); npc(900, 2, 70, 1, 1, "0", 0);
        for (String values : Arrays.asList("status=0", "status=null", "status=1, account_status=0", "account_status=null",
                "account_status=1, del_flag=2", "del_flag=null")) {
            jdbc.update("update mms_merchant set " + values + " where id=700"); session.clearCache();
            assertNull(profiles.resolveForNodeOrMerchant(31L, 70L), values); assertScopes();
        }
    }

    @Test void nullMemberMappingKeepsNodeScopeAndDoesNotGuess() {
        node.setMerchantMemberId(null); jdbc.update("update cms_topic_node set merchant_member_id=null where id=31"); npc(902, 4, 31, 1, 1, "0", 0); npc(900, 2, 70, 1, 1, "0", 0);
        assertEquals(902L, profiles.resolveForNodeOrMerchant(31L, null).getProfileId()); assertScopes(902L);
    }

    @Test void scopeFourHasPresentationPriorityButExplicitNarrativeCanUseAllOwnedProfiles() {
        merchant(700, 70, 1, 1, 0);
        npc(902, 4, 31, 1, 1, "0", 9); npc(901, 2, 700, 1, 1, "0", 2); npc(903, 2, 700, 1, 1, "0", 1);
        assertEquals(902L, profiles.resolveForNodeOrMerchant(31L, 70L).getProfileId()); assertScopes(901L, 902L, 903L);
    }

    @Test void realNpcReviewDeletionAndOrderingRemainInForce() {
        merchant(700, 70, 1, 1, 0);
        npc(901, 2, 700, 1, 1, "0", 2); npc(902, 2, 700, 1, 1, "0", 1);
        npc(903, 2, 700, 0, 1, "0", 0); npc(904, 2, 700, 1, 0, "0", 0);
        npc(905, 2, 700, 1, 1, "2", 0); npc(906, 4, 31, 1, 0, "0", 0);
        assertEquals(902L, profiles.resolveForNodeOrMerchant(31L, 70L).getProfileId()); assertScopes(901L, 902L);
    }

    @Test void disabledRetiredUnapprovedAndForeignTopicNodesContributeNoNpcAuthority() {
        merchant(700, 70, 1, 1, 0); npc(901, 2, 700, 1, 1, "0", 0); npc(902, 4, 31, 1, 1, "0", 0);
        for (String condition : Arrays.asList("status=0", "status=-1", "status=1,node_audit_status=0",
                "node_audit_status=2", "node_audit_status=1,topic_id=11")) {
            jdbc.update("update cms_topic_node set " + condition + " where id=31"); session.clearCache();
            assertScopes();
        }
    }

    @Test void withdrawnOfferRemovesNpcAuthorityOnlyForItsOwnMerchantChapter() {
        merchant(700, 70, 1, 1, 0); npc(901, 2, 700, 1, 1, "0", 0);
        jdbc.update("update cms_topic_node set chapter_id=23 where id=31");
        jdbc.update("insert into merchant_chapter_offer(id,topic_id,chapter_id,merchant_id,perk_id,status,withdraw_reason)"
                + " values(1,10,23,71,1,0,'Synthetic withdrawn offer')");
        session.clearCache(); assertScopes(901L); // Another merchant cannot hide this node.
        jdbc.update("update merchant_chapter_offer set merchant_id=70 where id=1");
        session.clearCache(); assertScopes();
        jdbc.update("update merchant_chapter_offer set status=1 where id=1");
        session.clearCache(); assertScopes(901L);
        for (String reason : Arrays.asList(null, "", " ")) {
            jdbc.update("update merchant_chapter_offer set status=0,withdraw_reason=? where id=1", reason);
            session.clearCache(); assertScopes(901L);
        }
    }

    /** Real HTTP binding/controller/service/identity/XML; authentication identity, billing and model are test doubles. */
    @Test void shopHttpUsesServerNodeOwnerAndPreservesParticipantAndFlagGates() throws Exception {
        merchant(700, 70, 1, 1, 0); merchant(70, 71, 1, 1, 0);
        npc(901, 2, 700, 1, 1, "0", 0); npc(900, 2, 70, 1, 1, "0", 0);
        jdbc.update("update npc_profile set persona='OWNED' where id=901");
        jdbc.update("update npc_profile set persona='FOREIGN' where id=900");
        com.chengyinhub.business.service.ai.AiGatewayService gateway = mock(com.chengyinhub.business.service.ai.AiGatewayService.class);
        when(gateway.generate(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new com.chengyinhub.business.service.ai.AiGatewayResult("trace", "Hello", "test", 0));
        NpcChatRequestMapper requests = mock(NpcChatRequestMapper.class);
        when(requests.finishIfLeaseOwned(any())).thenReturn(1);
        java.util.concurrent.ScheduledExecutorService scheduler = mock(java.util.concurrent.ScheduledExecutorService.class);
        doReturn(mock(java.util.concurrent.ScheduledFuture.class)).when(scheduler)
                .scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(java.util.concurrent.TimeUnit.class));
        NpcChatService chat = new NpcChatService();
        ReflectionTestUtils.setField(chat, "merchantNpcIdentityDirectory", directory);
        ReflectionTestUtils.setField(chat, "npcProfileMapper", npcs);
        ReflectionTestUtils.setField(chat, "npcChatRequestMapper", requests);
        ReflectionTestUtils.setField(chat, "npcChatLogMapper", mock(NpcChatLogMapper.class));
        ReflectionTestUtils.setField(chat, "scheduledExecutorService", scheduler);
        ReflectionTestUtils.setField(chat, "aiGatewayService", gateway);
        ReflectionTestUtils.setField(chat, "merchantFactsBuilder", mock(MerchantNpcFactsBuilder.class));
        com.chengyinhub.web.controller.api.ApiAiNpcController controller = spy(new com.chengyinhub.web.controller.api.ApiAiNpcController());
        doReturn(2002L).when(controller).getAppUserId();
        ICmsTopicNodeService nodes = mock(ICmsTopicNodeService.class);
        when(nodes.selectCmsTopicNodeById(31L)).thenAnswer(i -> session.getMapper(CmsTopicNodeMapper.class).selectCmsTopicNodeById(31L));
        PlayTeamMapper participants = mock(PlayTeamMapper.class);
        when(participants.hasPaidRegistration(2002L, 1, 10L)).thenReturn(1);
        com.chengyinhub.system.service.ISysConfigService config = mock(com.chengyinhub.system.service.ISysConfigService.class);
        com.chengyinhub.web.controller.api.NpcFeatureFlags flags = new com.chengyinhub.web.controller.api.NpcFeatureFlags();
        ReflectionTestUtils.setField(flags, "configService", config);
        ReflectionTestUtils.setField(controller, "npcFeatureFlags", flags);
        ReflectionTestUtils.setField(controller, "merchantNpcIdentityDirectory", directory);
        ReflectionTestUtils.setField(controller, "npcChatService", chat);
        ReflectionTestUtils.setField(controller, "cmsTopicNodeService", nodes);
        ReflectionTestUtils.setField(controller, "playTeamMapper", participants);
        org.springframework.test.web.servlet.MockMvc mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        String body = "{\"requestId\":\"" + UUID.randomUUID() + "\",\"nodeId\":31,\"message\":\"Hi\"}";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/ai/npc/shop-chat").contentType("application/json").content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(403));
        verifyNoInteractions(nodes, participants, gateway, requests);
        when(config.selectConfigByKey("feature.flag.shopNpcChat")).thenReturn("true");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/ai/npc/shop-chat").contentType("application/json").content(body.replace("\"nodeId\":31", "\"nodeId\":999")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(500));
        doReturn(null).when(controller).getAppUserId();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/ai/npc/shop-chat").contentType("application/json").content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(401));
        doReturn(2002L).when(controller).getAppUserId();
        com.chengyinhub.business.service.ai.SpeechToTextService speech = mock(com.chengyinhub.business.service.ai.SpeechToTextService.class);
        ReflectionTestUtils.setField(controller, "speechToTextService", speech);
        for (String condition : Arrays.asList("status=null", "status=0", "status=-1", "status=1,node_audit_status=null",
                "node_audit_status=0", "node_audit_status=2", "node_audit_status=1,merchant_member_id=null", "merchant_member_id=70,topic_id=null")) {
            jdbc.update("update cms_topic_node set " + condition + " where id=31"); session.clearCache();
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/ai/npc/shop-chat").contentType("application/json").content(body))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(500));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/ai/npc/voice-chat")
                    .file(new org.springframework.mock.web.MockMultipartFile("file", "question.wav", "audio/wav", new byte[]{1,2,3}))
                    .param("nodeId", "31").param("requestId", UUID.randomUUID().toString()))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(500));
        }
        verifyNoInteractions(gateway, requests, speech, participants);
        jdbc.update("update cms_topic_node set status=1,node_audit_status=1,merchant_member_id=70,topic_id=11 where id=31"); session.clearCache();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/ai/npc/shop-chat").contentType("application/json").content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(403));
        verify(participants).hasPaidRegistration(2002L, 1, 11L);
        jdbc.update("update cms_topic_node set topic_id=10 where id=31"); session.clearCache();
        when(participants.hasPaidRegistration(2002L, 1, 10L)).thenReturn(0);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/ai/npc/shop-chat").contentType("application/json").content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(403));
        verifyNoInteractions(gateway, requests);
        when(participants.hasPaidRegistration(2002L, 1, 10L)).thenReturn(1);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/ai/npc/shop-chat").contentType("application/json").content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.outcomeStatus").value("SUCCEEDED"));
        org.mockito.ArgumentCaptor<String> prompt = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(gateway).generate(eq(2002L), eq("player"), eq("npc_shop_chat"), prompt.capture(), eq("Hi"));
        assertTrue(prompt.getValue().startsWith("OWNED")); assertFalse(prompt.getValue().contains("FOREIGN"));
        org.mockito.ArgumentCaptor<NpcChatRequest> saved = org.mockito.ArgumentCaptor.forClass(NpcChatRequest.class);
        verify(requests).insertNpcChatRequest(saved.capture());
        assertEquals(2002L, saved.getValue().getUserId()); assertEquals(-31L, saved.getValue().getSessionId());
        clearInvocations(gateway, requests, speech);
        when(config.selectConfigByKey("feature.flag.merchantNpcChat")).thenReturn("true");
        String directBody = "{\"requestId\":\"" + UUID.randomUUID() + "\",\"bizId\":700,\"message\":\"Hi\"}";
        for (String condition : Arrays.asList("status=0,account_status=1,del_flag=0", "status=null",
                "status=1,account_status=0", "account_status=null", "account_status=1,del_flag=2", "del_flag=null")) {
            jdbc.update("update mms_merchant set " + condition + " where id=700"); session.clearCache();
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/ai/npc/shop-chat").contentType("application/json").content(body))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(500));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/ai/npc/voice-chat")
                    .file(new org.springframework.mock.web.MockMultipartFile("file", "question.wav", "audio/wav", new byte[]{1,2,3}))
                    .param("nodeId", "31").param("requestId", UUID.randomUUID().toString()))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(500));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/ai/npc/merchant-chat").contentType("application/json").content(directBody))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.errorCode").value("NPC_PROFILE_UNAVAILABLE"));
        }
        verifyNoInteractions(gateway, speech);
        jdbc.update("delete from mms_merchant where id=700"); session.clearCache();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/ai/npc/shop-chat").contentType("application/json").content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(500));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/ai/npc/merchant-chat").contentType("application/json").content(directBody))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.errorCode").value("NPC_PROFILE_UNAVAILABLE"));
        verifyNoInteractions(gateway, speech);
        merchant(700, 70, 1, 1, 0); merchant(701, 70, 1, 1, 0); session.clearCache();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/ai/npc/shop-chat").contentType("application/json").content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(500));
        verifyNoInteractions(gateway);
        // Direct row callers remain unambiguous even when the owner has several rows.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/ai/npc/merchant-chat").contentType("application/json").content(directBody))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.outcomeStatus").value("SUCCEEDED"));
        verify(gateway).generate(eq(2002L), eq("player"), eq("merchant_npc_chat"), startsWith("OWNED"), eq("Hi"));
    }

    @Test void directMerchantRowLookupDoesNotReinterpretOwnerOrShareAmbiguity() {
        merchant(700, 70, 1, 1, 0); merchant(701, 70, 1, 1, 0); merchant(70, 71, 1, 1, 0);
        assertEquals(700L, directory.availableMerchantId(700L));
        assertEquals(70L, directory.availableMerchantId(70L));
        assertNull(directory.availableMerchantIdForMember(70L));
        for (String condition : Arrays.asList("status=null", "status=0", "status=1,account_status=0",
                "account_status=null", "account_status=1,del_flag=2", "del_flag=null")) {
            jdbc.update("update mms_merchant set " + condition + " where id=700"); session.clearCache();
            assertNull(directory.availableMerchantId(700L));
        }
        assertNull(directory.availableMerchantId(999L));
    }

    private void assertScopes(Long... ids) {
        Set<Long> expected = new HashSet<>(Arrays.asList(ids));
        Set<Long> published = ReflectionTestUtils.invokeMethod(publication, "journeyStoryAvailableNpcIds", 10L);
        assertEquals(expected, published, "ordinary publish identity set");
        Map<Long, NpcProfileVO> served = ReflectionTestUtils.invokeMethod(encounter, "servedNpcs", 10L);
        assertEquals(expected, served.keySet(), "runtime explicit narrative identity set");
    }
    private void merchant(long id, long member, int status, int account, int deleted) {
        jdbc.update("insert into mms_merchant(id,member_id,status,account_status,del_flag) values(?,?,?,?,?)", id, member, status, account, deleted);
    }
    private void npc(long id, int scope, long scopeId, int enabled, int audit, String deleted, int sort) {
        jdbc.update("insert into npc_profile values(?,?,?,?,?,?,?,?,?,?)", id, scope, scopeId, "NPC", enabled, audit, deleted, sort, "private persona", "private knowledge");
    }
    private String resource(String path) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] bytes = new byte[4096]; int count;
            while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
