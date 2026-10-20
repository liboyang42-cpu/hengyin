package com.chengyinhub.business.service.ai.npc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import com.chengyinhub.business.service.impl.NpcChatService;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MerchantNpcDirectoryTest
{
    MmsMerchantMapper merchants;
    NpcProfileMapper profiles;
    MmsMerchant merchant;
    NpcProfile profile;
    MerchantNpcDirectory directory;

    @BeforeEach void setUp()
    {
        merchants = mock(MmsMerchantMapper.class); profiles = mock(NpcProfileMapper.class);
        merchant = spy(new MmsMerchant()); merchant.setId(10L); merchant.setMemberId(900L);
        merchant.setStatus(1L); merchant.setAccountStatus(1L); merchant.setDelFlag(0);
        profile = spy(new NpcProfile()); profile.setId(20L); profile.setScopeType(2); profile.setScopeId(10L);
        profile.setEnabled(1); profile.setAuditStatus(1); profile.setDelFlag("0");
        profile.setKnowledge("UNREVIEWED_KNOWLEDGE_CANARY"); profile.setPersona("PERSONA_INJECTION");
        when(merchants.selectNpcScopeOwnerById(10L)).thenReturn(merchant);
        when(profiles.selectMerchantNpcScopeIdentity(20L)).thenReturn(profile);
        directory = new MerchantNpcDirectory(merchants, profiles);
    }

    @Test void canonicalMerchantAndOwnerMemberAreSeparateIdsAndContentIsNotRead()
    {
        assertTrue(directory.matches(10, 900, 20));
        assertFalse(directory.matches(900, 900, 20));
        verify(merchant, never()).getDescription(); verify(merchant, never()).getPhone();
        verify(profile, never()).getKnowledge(); verify(profile, never()).getPersona();
    }

    @Test void changedOwnerCannotReuseOldReview()
    {
        merchant.setMemberId(901L); assertFalse(directory.matches(10, 900, 20));
        verify(profiles, never()).selectMerchantNpcScopeIdentity(20L);
    }

    @Test void disabledDeletedOrUnapprovedMerchantFailsClosed()
    {
        merchant.setAccountStatus(2L); assertFalse(directory.matches(10, 900, 20));
        merchant.setAccountStatus(1L); merchant.setStatus(0L); assertFalse(directory.matches(10, 900, 20));
        merchant.setStatus(1L); merchant.setDelFlag(2); assertFalse(directory.matches(10, 900, 20));
        merchant.setDelFlag(null); assertFalse(directory.matches(10, 900, 20));
    }

    @Test void mismatchedProfileOrUnapprovedStatusFailsClosed()
    {
        profile.setScopeId(99L); assertFalse(directory.matches(10, 900, 20)); profile.setScopeId(10L);
        profile.setScopeType(1); assertFalse(directory.matches(10, 900, 20)); profile.setScopeType(2);
        profile.setAuditStatus(0); assertFalse(directory.matches(10, 900, 20)); profile.setAuditStatus(1);
        profile.setEnabled(0); assertFalse(directory.matches(10, 900, 20)); profile.setEnabled(1);
        profile.setDelFlag("2"); assertFalse(directory.matches(10, 900, 20));
    }

    @Test void directorySqlReadsOnlyIdentityColumns() throws Exception
    {
        assertEquals("SELECT id, member_id, status, account_status, del_flag FROM mms_merchant WHERE id = #{id}",
                selectSql("MmsMerchantMapper.xml", "selectNpcScopeOwnerById"));
        assertEquals("SELECT id, scope_type, scope_id, enabled, audit_status, del_flag FROM npc_profile WHERE id = #{id} AND scope_type = 2",
                selectSql("NpcProfileMapper.xml", "selectMerchantNpcScopeIdentity"));
    }

    private String selectSql(String resource, String id) throws Exception
    {
        javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        try (java.io.InputStream input = getClass().getClassLoader().getResourceAsStream("mapper/business/" + resource))
        {
            org.w3c.dom.Document document = factory.newDocumentBuilder().parse(input);
            String query = javax.xml.xpath.XPathFactory.newInstance().newXPath().evaluate(
                    "/mapper/select[@id='" + id + "']", document);
            return query.trim().replaceAll("\\s+", " ");
        }
    }

    @Test void explicitLegacyServiceSeamDelegatesOnlyToScopedOrchestrator()
    {
        ScopedNpcChatService scoped = mock(ScopedNpcChatService.class);
        NpcChatService legacy = new NpcChatService(); ReflectionTestUtils.setField(legacy, "scopedNpcChatService", scoped);
        ScopedNpcRequest request = ScopedNpcRequest.publicStore(10, ScopedNpcChatServiceTest.SESSION,
                ScopedNpcChatServiceTest.REQUEST, "menu");
        ScopedNpcReply reply = new ScopedNpcReply(request.requestId, "safe", "DISABLED", "trace", null,
                "prompt", null, null, null, false);
        when(scoped.chat(7L, request)).thenReturn(reply);
        assertSame(reply, legacy.chatScoped(request, 7L)); verify(scoped).chat(7L, request);
    }
}
