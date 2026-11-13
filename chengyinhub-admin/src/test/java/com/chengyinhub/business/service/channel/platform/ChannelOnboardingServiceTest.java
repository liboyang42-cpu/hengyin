package com.chengyinhub.business.service.channel.platform;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.mapper.channel.platform.ChannelOnboardingMapper;
import com.chengyinhub.business.mapper.channel.platform.ChannelPlatformMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChannelOnboardingServiceTest
{
    static final long OWNER = 11, MEMBER = 22, STRANGER = 33, CLUB = 42;
    static final String REQ = "0f8fad5b-d9cb-469f-a165-70867728950e";

    ChannelOnboardingMapper mapper;
    ChannelPlatformMapper platform;
    ChannelOnboardingService service;
    ChannelAdmissionService admission;
    final List<Map<String, Object>> prompts = new ArrayList<>();
    final List<Map<String, Object>> options = new ArrayList<>();
    final Map<Long, Map<String, Object>> answers = new HashMap<>();

    @BeforeEach void setUp()
    {
        mapper = mock(ChannelOnboardingMapper.class);
        platform = mock(ChannelPlatformMapper.class);
        ReceiptFake.install(platform);
        when(platform.selectClubGuild(CLUB)).thenReturn(Map.of("id", CLUB, "ownerId", OWNER, "status", 1, "delFlag", 0));
        when(platform.countClubMember(CLUB, MEMBER)).thenReturn(1);
        when(mapper.selectPrompts(1, CLUB)).thenAnswer(i -> new ArrayList<>(prompts));
        when(mapper.selectOptions(1, CLUB)).thenAnswer(i -> new ArrayList<>(options));
        when(mapper.insertPromptIgnore(anyMap())).thenAnswer(i -> {
            Map<String, Object> r = i.getArgument(0);
            prompts.add(new HashMap<>(Map.<String, Object>of("id", 501L, "title", r.get("title"), "singleSelect", r.get("singleSelect"),
                    "required", r.get("required"), "enabled", 1, "sortNo", r.get("sortNo"))));
            return 1;
        });
        when(mapper.selectPromptId(1, CLUB, 1)).thenReturn(501L);
        when(mapper.insertOptionIgnore(anyLong(), anyString(), anyString(), anyString(), anyInt())).thenAnswer(i -> {
            Object[] a = i.getArguments();
            options.add(new HashMap<>(Map.of("id", 600L + (Integer) a[4], "promptId", a[0], "label", a[1],
                    "description", a[2], "emoji", a[3], "sortNo", a[4])));
            return 1;
        });
        when(mapper.selectAnswer(eq(1), eq(CLUB), anyLong())).thenAnswer(i -> answers.get((Long) i.getArgument(2)));
        when(mapper.upsertAnswer(eq(1), eq(CLUB), anyLong(), anyString(), anyString(), anyString())).thenAnswer(i -> {
            Object[] a = i.getArguments();
            answers.put((Long) a[2], Map.of("configurationHash", a[3], "answers", a[4], "requestId", a[5]));
            return 1;
        });
        ChannelGuildAccess access = new ChannelGuildAccess(platform);
        service = new ChannelOnboardingService();
        ReflectionTestUtils.setField(service, "mapper", mapper);
        ReflectionTestUtils.setField(service, "access", access);
        ReflectionTestUtils.setField(service, "receipts", platform);
        admission = new ChannelAdmissionService();
        ReflectionTestUtils.setField(admission, "channels", platform);
    }

    static Map<String, Object> body(Object... kv)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static String code(Runnable r)
    {
        try { r.run(); return null; }
        catch (ChannelRejection e) { return e.getStatus() + " " + e.getErrorCode(); }
    }

    @Test void emptySpaceGetsTheDefaultPromptAndAValidSnapshot()
    {
        Map<String, Object> snap = service.read(MEMBER, 1, CLUB);
        assertEquals(1, prompts.size());
        assertEquals(3, options.size());
        assertEquals("NONE", snap.get("answer_state"));
        assertEquals(0, snap.get("submitted"));
        assertEquals("ALL_REQUIRED_INTERSECTION", snap.get("completion_policy"));
        assertEquals(true, snap.get("can_skip"));
        assertEquals("welcome_screen", snap.get("next"));
        assertTrue(((String) snap.get("configuration_hash")).matches("[0-9a-f]{64}"));
        Map<?, ?> prompt = (Map<?, ?>) ((List<?>) snap.get("prompts")).get(0);
        assertEquals("501", prompt.get("prompt_id"));
        assertEquals(List.of(), prompt.get("selected_option_ids"));
        assertEquals(false, prompt.get("previous_answer_exists"));
        assertEquals(List.of(), ((Map<?, ?>) ((List<?>) prompt.get("options")).get(0)).get("role_ids"));
        assertEquals("403 GUILD_FORBIDDEN", code(() -> service.read(STRANGER, 1, CLUB)));
    }

    @Test void submitRecordsAnswersReplaysAndIsReadableThroughTheAdmissionReceipt()
    {
        String hash = (String) service.read(MEMBER, 1, CLUB).get("configuration_hash");
        Map<String, Object> req = body("guild_kind", 1, "guild_id", "42", "request_id", REQ, "configuration_hash", hash,
                "answers", List.of(body("prompt_id", "501", "option_ids", List.of("603", "601"))));
        Map<String, Object> out = service.submit(MEMBER, req);
        assertEquals("APPLIED", out.get("state"));
        assertEquals("NONE", out.get("effects_state"));
        assertEquals(List.of(body("prompt_id", "501", "option_ids", List.of("601", "603"))), out.get("answers"));
        assertEquals(out, service.submit(MEMBER, req));
        verify(mapper, times(1)).upsertAnswer(anyInt(), anyLong(), anyLong(), anyString(), anyString(), anyString());

        Map<String, Object> snap = service.read(MEMBER, 1, CLUB);
        assertEquals("CURRENT", snap.get("answer_state"));
        assertEquals(1, snap.get("submitted"));
        Map<?, ?> prompt = (Map<?, ?>) ((List<?>) snap.get("prompts")).get(0);
        assertEquals(List.of("601", "603"), prompt.get("selected_option_ids"));
        assertEquals(hash, prompt.get("answer_configuration_hash"));

        Map<String, Object> receipt = admission.receipt(MEMBER, REQ);
        assertEquals("APPLIED", receipt.get("state"));
        assertEquals("ONBOARDING_SUBMIT", receipt.get("operation"));
        assertEquals(0, receipt.get("object_id"));
        assertEquals("42", receipt.get("guild_id"));
        assertTrue(((String) receipt.get("request_hash")).matches("[0-9a-f]{64}"));

        // 配置变化：旧答案降为 SOURCE_CHANGED，submitted 归 0。
        options.add(new HashMap<>(Map.of("id", 699L, "promptId", 501L, "label", "新选项", "description", "", "emoji", "", "sortNo", 9)));
        Map<String, Object> changed = service.read(MEMBER, 1, CLUB);
        assertEquals("SOURCE_CHANGED", changed.get("answer_state"));
        assertEquals(0, changed.get("submitted"));
    }

    @Test void invalidAnswersAreRejectedWithoutWriting()
    {
        String hash = (String) service.read(MEMBER, 1, CLUB).get("configuration_hash");
        assertEquals("409 ONBOARDING_CONFIGURATION_CHANGED", code(() -> service.submit(MEMBER, body("guild_kind", 1, "guild_id", "42",
                "request_id", REQ, "configuration_hash", "0".repeat(64), "answers", List.of(body("prompt_id", "501", "option_ids", List.of("601")))))));
        assertEquals("409 ONBOARDING_ANSWER_INVALID", code(() -> service.submit(MEMBER, body("guild_kind", 1, "guild_id", "42",
                "request_id", "r-2", "configuration_hash", hash, "answers", List.of(body("prompt_id", "501", "option_ids", List.of("777")))))));
        assertEquals("409 ONBOARDING_ANSWER_INVALID", code(() -> service.submit(MEMBER, body("guild_kind", 1, "guild_id", "42",
                "request_id", "r-3", "configuration_hash", hash, "answers", List.of(body("prompt_id", "999", "option_ids", List.of("601")))))));
        assertEquals("400 INVALID_FIELD", code(() -> service.submit(MEMBER, body("guild_kind", 1, "guild_id", "42",
                "request_id", "r-4", "configuration_hash", hash, "answers", List.of()))));
        verify(mapper, never()).upsertAnswer(anyInt(), anyLong(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test void disabledConfigurationIsNotConfigured()
    {
        prompts.add(new HashMap<>(Map.of("id", 501L, "title", "t", "singleSelect", 0, "required", 1, "enabled", 0, "sortNo", 1)));
        assertEquals("404 ONBOARDING_NOT_CONFIGURED", code(() -> service.read(MEMBER, 1, CLUB)));
        verify(mapper, never()).insertPromptIgnore(anyMap());
    }
}
