package com.chengyinhub.business.service.channel.platform;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.mapper.channel.platform.ChannelGovernanceMapper;
import com.chengyinhub.business.mapper.channel.platform.ChannelPlatformMapper;
import com.chengyinhub.business.service.IAuditTaskService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChannelGovernanceServiceTest
{
    ChannelGovernanceMapper gov;
    ChannelPlatformMapper channels;
    ChannelGovernanceService service;

    @BeforeEach void setUp()
    {
        gov = mock(ChannelGovernanceMapper.class);
        channels = mock(ChannelPlatformMapper.class);
        ReceiptFake.install(channels);
        service = new ChannelGovernanceService();
        ReflectionTestUtils.setField(service, "gov", gov);
        ReflectionTestUtils.setField(service, "channels", channels);
        ReflectionTestUtils.setField(service, "access", mock(ChannelGuildAccess.class));
        ReflectionTestUtils.setField(service, "platform", mock(ChannelPlatformService.class));
    }

    static Map<String, Object> body(Object... kv)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    Map<String, Object> moderation(String scopeType, String scopeId, int seconds, int deleteSeconds)
    {
        return body("guild_kind", 1, "guild_id", "10", "target_member_id", "2", "scope_type", scopeType, "scope_id", scopeId,
                "duration_sec", seconds, "reason_code", "SPAM", "reason_text", "x", "request_id", UUID.randomUUID().toString(),
                "delete_message_sec", deleteSeconds);
    }

    @Test void moderationShapeIsCheckedBeforeAnyRead()
    {
        assertEquals("INVALID_FIELD", code(() -> service.moderate(1, "timeout", moderation("GUILD", "10", 600, 0))), "timeout is channel-scoped");
        assertEquals("INVALID_FIELD", code(() -> service.moderate(1, "ban", moderation("GUILD", "11", 600, 0))), "guild scope must be the guild");
        assertEquals("INVALID_FIELD", code(() -> service.moderate(1, "timeout", moderation("CHANNEL", "5", 30, 0))), "timeout min 60s");
        assertEquals("INVALID_FIELD", code(() -> service.moderate(1, "kick", moderation("GUILD", "10", 0, 60))), "only ban deletes history");
        assertEquals("INVALID_FIELD", code(() -> service.moderate(1, "ban", moderation("GUILD", "10", 7776001, 0))));
        Map<String, Object> extra = moderation("GUILD", "10", 0, 0);
        extra.put("note", "x");
        assertEquals("INVALID_FIELD", code(() -> service.moderate(1, "kick", extra)));
        verify(gov, never()).insertAction(anyMap());
    }

    @Test void receiptsDistinguishMissingFromApplied()
    {
        String id = UUID.randomUUID().toString();
        Map<String, Object> missing = service.moderationReceipt(1, id);
        assertEquals("NOT_FOUND", missing.get("state"));
        assertEquals(true, missing.get("retry_with_original_request"));
        Map<String, Object> report = service.reportReceipt(1, id);
        assertEquals("NOT_FOUND", report.get("state"));
        assertEquals(1, service.memberStateReceipt(1, id).get("resume_with_same_request"));
    }

    @Test void reportCreatesOneAuditTaskPerOriginalRequest()
    {
        ChannelPlatformService platform = mock(ChannelPlatformService.class);
        ReflectionTestUtils.setField(service, "platform", platform);
        IAuditTaskService audit = mock(IAuditTaskService.class);
        when(audit.reportContent(eq("IM_MESSAGE"), eq(901L), eq(2L), anyString())).thenReturn(55L);
        ReflectionTestUtils.setField(service, "auditTasks", audit);
        when(gov.selectMessageForReport(901)).thenReturn(new HashMap<>(Map.of("id", 901L, "conversationId", 1001L, "senderId", 1L, "status", 0)));
        when(gov.insertReport(anyMap())).thenAnswer(i -> { ((Map<String, Object>) i.getArgument(0)).put("id", 7L); return 1; });
        String request = UUID.randomUUID().toString().toUpperCase();
        Map<String, Object> b = body("target_type", "MESSAGE", "target_id", "901", "reason_code", "SPAM", "submit_request_id", request);
        Map<String, Object> first = service.report(2, b, "d".repeat(64));
        Map<String, Object> replay = service.report(2, b, "d".repeat(64));
        assertEquals("55", first.get("audit_task_id"));
        assertEquals("7", first.get("report_id"));
        assertEquals("1001", first.get("channel_id"));
        assertEquals(true, replay.get("replayed"));
        verify(audit, times(1)).reportContent(anyString(), anyLong(), anyLong(), anyString());
        when(gov.selectMessageForReport(902)).thenReturn(new HashMap<>(Map.of("id", 902L, "conversationId", 1001L, "senderId", 2L, "status", 0)));
        assertEquals("REPORT_SELF", code(() -> service.report(2, body("target_type", "MESSAGE", "target_id", "902", "reason_code", "SPAM",
                "submit_request_id", UUID.randomUUID().toString()), "e".repeat(64))));
    }

    @Test void roleViewCarriesTheFieldsTheRoleFlowParses()
    {
        Map<String, Object> view = ChannelGovernanceService.roleView(body("id", 3L, "guildKind", 1, "guildId", 10L, "name", "主持",
                "version", 2L, "permissions", 0x300L, "position", 1, "status", 1, "isSystem", 0, "hoist", 0, "mentionable", 1));
        for (String key : List.of("id", "guild_kind", "guild_id", "name", "version", "permissions", "position", "status", "is_system", "managed",
                "is_everyone", "hoist", "mentionable")) assertTrue(view.containsKey(key), key);
        assertEquals("0x300", view.get("permissions"));
        assertEquals("2", view.get("version"));
    }

    static String code(Runnable r) { return assertThrows(ChannelRejection.class, r::run).getErrorCode(); }
}
