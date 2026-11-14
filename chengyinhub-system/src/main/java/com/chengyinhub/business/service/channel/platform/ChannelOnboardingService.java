package com.chengyinhub.business.service.channel.platform;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.mapper.channel.platform.ChannelOnboardingMapper;
import com.chengyinhub.business.mapper.channel.platform.ChannelPlatformMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 入会引导（App NativeChannelOnboardingFlow，docs/channels/contract.md §6.11）：配置读自 im_onboarding_prompt/option，
 * 空间没有配置时按默认一题三选项补种（空安全默认），答案只记录、不授角色、不加频道（effects_state=NONE），
 * 不替代规则同意。提交走准入原回执族 ADMIT（operation=ONBOARDING_SUBMIT），由 /api/channel-platform/admission/requests/{id} 回读。
 */
@Service
public class ChannelOnboardingService
{
    static final String OPERATION = "ONBOARDING_SUBMIT";
    static final String DEFAULT_TITLE = "你最想在这里做什么？";
    static final String[][] DEFAULT_OPTIONS = {
            { "认识同好", "和兴趣相近的人聊聊", "👋" },
            { "参加活动", "看看最近有什么活动可以一起玩", "🎯" },
            { "先随便看看", "先逛逛，之后再决定", "👀" } };
    static final int MAX_PROMPTS = 3, MAX_OPTIONS = 8;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private ChannelOnboardingMapper mapper;
    @Autowired
    private ChannelGuildAccess access;
    @Autowired
    private ChannelPlatformMapper receipts;

    record Option(long id, String label, String description, String emoji) { }

    record Prompt(long id, String title, boolean singleSelect, boolean required, List<Option> options) { }

    record Config(List<Prompt> prompts, String hash) { }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> read(long viewer, int guildKind, long guildId)
    {
        ChannelGuildAccess.requireSupportedKind(guildKind);
        access.requireMember(guildKind, guildId, viewer);
        Config config = config(guildKind, guildId);
        return snapshot(viewer, guildKind, guildId, config, mapper.selectAnswer(guildKind, guildId, viewer));
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> submit(long viewer, Map<String, Object> body)
    {
        Map<String, Object> b = ChannelGovernanceService.body(body,
                Set.of("guild_kind", "guild_id", "request_id", "configuration_hash", "answers"));
        String requestId = ChannelAdmissionService.requestId(b.get("request_id"));
        int kind = (int) ChannelGovernanceService.positive(b.get("guild_kind"), "guild_kind");
        long guild = ChannelGovernanceService.positive(b.get("guild_id"), "guild_id");
        if (!(b.get("configuration_hash") instanceof String hash) || !hash.matches("[0-9a-f]{64}"))
            throw ChannelRejection.badField("configuration_hash");
        Map<Long, List<Long>> answers = answers(b.get("answers"));
        Map<String, Object> out = ChannelReceipts.once(receipts, viewer, "ADMIT", requestId,
                ChannelReceipts.fingerprint(OPERATION, viewer, b), OPERATION, 0L, () ->
                {
                    ChannelGuildAccess.requireSupportedKind(kind);
                    access.requireMember(kind, guild, viewer);
                    Config config = config(kind, guild);
                    if (!config.hash().equals(hash))
                        throw new ChannelRejection(409, "ONBOARDING_CONFIGURATION_CHANGED", "引导配置已更新，请重新读取");
                    List<Map<String, Object>> normalized = validate(config, answers);
                    mapper.upsertAnswer(kind, guild, viewer, hash, encode(normalized), requestId);
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("request_id", requestId);
                    result.put("state", "APPLIED");
                    result.put("viewer_id", String.valueOf(viewer));
                    result.put("guild_id", String.valueOf(guild));
                    result.put("guild_kind", kind);
                    result.put("submitted", 1);
                    result.put("configuration_hash", hash);
                    result.put("effects_state", "NONE");
                    result.put("granted_role_ids", List.of());
                    result.put("joined_channel_ids", List.of());
                    result.put("answers", normalized);
                    return result;
                });
        out.remove("replayed");
        return out;
    }

    // ---------------------------------------------------------------- configuration

    /** 读配置；空间一题都没有时补种默认（INSERT IGNORE，并发安全），再读一次。 */
    Config config(int kind, long guild)
    {
        List<Map<String, Object>> prompts = mapper.selectPrompts(kind, guild);
        if (prompts.isEmpty())
        {
            seedDefault(kind, guild);
            prompts = mapper.selectPrompts(kind, guild);
        }
        Map<Long, List<Option>> byPrompt = new HashMap<>();
        for (Map<String, Object> o : mapper.selectOptions(kind, guild))
            byPrompt.computeIfAbsent(ChannelGuildAccess.toLong(o.get("promptId")), k -> new ArrayList<>())
                    .add(new Option(ChannelGuildAccess.toLong(o.get("id")), str(o.get("label")), str(o.get("description")), str(o.get("emoji"))));
        List<Prompt> out = new ArrayList<>();
        for (Map<String, Object> p : prompts)
        {
            if (ChannelGuildAccess.toLong(p.get("enabled")) != 1) continue;
            long id = ChannelGuildAccess.toLong(p.get("id"));
            List<Option> options = byPrompt.getOrDefault(id, List.of());
            if (options.isEmpty()) continue;
            if (options.size() > MAX_OPTIONS) options = options.subList(0, MAX_OPTIONS);
            out.add(new Prompt(id, str(p.get("title")), ChannelGuildAccess.toLong(p.get("singleSelect")) == 1,
                    ChannelGuildAccess.toLong(p.get("required")) == 1, List.copyOf(options)));
            if (out.size() == MAX_PROMPTS) break;
        }
        if (out.isEmpty()) throw new ChannelRejection(404, "ONBOARDING_NOT_CONFIGURED", "本空间尚未配置入会引导");
        return new Config(out, hash(out));
    }

    void seedDefault(int kind, long guild)
    {
        Map<String, Object> row = new HashMap<>();
        row.put("guildKind", kind);
        row.put("guildId", guild);
        row.put("title", DEFAULT_TITLE);
        row.put("singleSelect", 0);
        row.put("required", 1);
        row.put("sortNo", 1);
        mapper.insertPromptIgnore(row);
        Long prompt = mapper.selectPromptId(kind, guild, 1);
        if (prompt == null) throw new IllegalStateException("default onboarding prompt missing");
        for (int i = 0; i < DEFAULT_OPTIONS.length; i++)
            mapper.insertOptionIgnore(prompt, DEFAULT_OPTIONS[i][0], DEFAULT_OPTIONS[i][1], DEFAULT_OPTIONS[i][2], i + 1);
    }

    static String hash(List<Prompt> prompts)
    {
        List<Object> canonical = new ArrayList<>();
        for (Prompt p : prompts)
        {
            List<Object> options = new ArrayList<>();
            for (Option o : p.options()) options.add(List.of(o.id(), o.label(), o.description(), o.emoji()));
            canonical.add(List.of(p.id(), p.title(), p.singleSelect(), p.required(), options));
        }
        try
        {
            byte[] bytes = JSON.writeValueAsBytes(List.of("onboarding-config-v1", canonical));
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch (Exception e)
        {
            throw new IllegalStateException("Cannot hash onboarding configuration", e);
        }
    }

    // ---------------------------------------------------------------- snapshot

    static Map<String, Object> snapshot(long viewer, int kind, long guild, Config config, Map<String, Object> answer)
    {
        String answerHash = answer == null ? null : str(answer.get("configurationHash"));
        Map<Long, List<Long>> stored = answer == null ? Map.of() : parseStored(answer.get("answers"));
        String state = answer == null ? "NONE" : config.hash().equals(answerHash) ? "CURRENT" : "SOURCE_CHANGED";
        boolean current = "CURRENT".equals(state);
        boolean allRequired = config.prompts().stream().allMatch(Prompt::required);
        List<Map<String, Object>> prompts = new ArrayList<>();
        for (Prompt p : config.prompts())
        {
            Set<Long> valid = new LinkedHashSet<>();
            for (Option o : p.options()) valid.add(o.id());
            List<String> selected = new ArrayList<>();
            for (Long id : stored.getOrDefault(p.id(), List.of()))
                if (valid.contains(id) && (!p.singleSelect() || selected.isEmpty())) selected.add(String.valueOf(id));
            boolean previous = stored.containsKey(p.id());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("prompt_id", String.valueOf(p.id()));
            row.put("title", p.title());
            row.put("single_select", p.singleSelect());
            row.put("required", p.required());
            row.put("enabled", true);
            List<Map<String, Object>> options = new ArrayList<>();
            for (Option o : p.options())
            {
                Map<String, Object> opt = new LinkedHashMap<>();
                opt.put("option_id", String.valueOf(o.id()));
                opt.put("label", o.label());
                opt.put("description", o.description());
                opt.put("emoji", o.emoji());
                opt.put("channel_ids", List.of());
                opt.put("role_ids", List.of());
                options.add(opt);
            }
            row.put("options", options);
            row.put("selected_option_ids", selected);
            row.put("previous_answer_exists", previous);
            row.put("answer_source_current", current && previous);
            row.put("answer_configuration_hash", previous ? answerHash : null);
            prompts.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("viewer_id", String.valueOf(viewer));
        out.put("guild_id", String.valueOf(guild));
        out.put("guild_kind", kind);
        out.put("configuration_hash", config.hash());
        out.put("completion_policy", allRequired ? "ALL_REQUIRED_INTERSECTION" : "UNRESOLVED_OPTIONAL_POLICY");
        out.put("answer_state", state);
        out.put("submitted", current ? 1 : 0);
        out.put("can_skip", true);
        out.put("next", "welcome_screen");
        out.put("prompts", prompts);
        return out;
    }

    // ---------------------------------------------------------------- answers

    static Map<Long, List<Long>> answers(Object raw)
    {
        if (!(raw instanceof List<?> list) || list.isEmpty() || list.size() > MAX_PROMPTS) throw ChannelRejection.badField("answers");
        Map<Long, List<Long>> out = new TreeMap<>();
        for (Object item : list)
        {
            if (!(item instanceof Map<?, ?> row) || !Set.of("prompt_id", "option_ids").equals(row.keySet())) throw ChannelRejection.badField("answers");
            long prompt = positiveId(row.get("prompt_id"));
            if (!(row.get("option_ids") instanceof List<?> options) || options.isEmpty() || options.size() > MAX_OPTIONS)
                throw ChannelRejection.badField("option_ids");
            TreeSet<Long> ids = new TreeSet<>();
            for (Object o : options) if (!ids.add(positiveId(o))) throw ChannelRejection.badField("option_ids");
            if (out.put(prompt, new ArrayList<>(ids)) != null) throw ChannelRejection.badField("answers");
        }
        return out;
    }

    static List<Map<String, Object>> validate(Config config, Map<Long, List<Long>> answers)
    {
        Map<Long, Prompt> prompts = new HashMap<>();
        for (Prompt p : config.prompts()) prompts.put(p.id(), p);
        for (Map.Entry<Long, List<Long>> e : answers.entrySet())
        {
            Prompt p = prompts.get(e.getKey());
            if (p == null) throw new ChannelRejection(409, "ONBOARDING_ANSWER_INVALID", "问题不存在或已下线");
            Set<Long> valid = new LinkedHashSet<>();
            for (Option o : p.options()) valid.add(o.id());
            if (!valid.containsAll(e.getValue()) || (p.singleSelect() && e.getValue().size() > 1))
                throw new ChannelRejection(409, "ONBOARDING_ANSWER_INVALID", "选项不符合问题要求");
        }
        for (Prompt p : config.prompts())
            if (p.required() && !answers.containsKey(p.id()))
                throw new ChannelRejection(409, "ONBOARDING_ANSWER_INCOMPLETE", "请回答全部必答问题");
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map.Entry<Long, List<Long>> e : answers.entrySet())
        {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("prompt_id", String.valueOf(e.getKey()));
            List<String> ids = new ArrayList<>();
            for (Long id : e.getValue()) ids.add(String.valueOf(id));
            row.put("option_ids", ids);
            normalized.add(row);
        }
        return normalized;
    }

    static Map<Long, List<Long>> parseStored(Object raw)
    {
        Map<Long, List<Long>> out = new HashMap<>();
        if (raw == null) return out;
        try
        {
            List<Map<String, Object>> rows = JSON.readValue(raw.toString(), new TypeReference<List<Map<String, Object>>>() { });
            for (Map<String, Object> row : rows)
            {
                List<Long> ids = new ArrayList<>();
                for (Object o : (List<?>) row.get("option_ids")) ids.add(Long.parseLong(o.toString()));
                out.put(Long.parseLong(row.get("prompt_id").toString()), ids);
            }
        }
        catch (Exception e)
        {
            return new HashMap<>();
        }
        return out;
    }

    private static long positiveId(Object raw)
    {
        String s = raw instanceof String v ? v : raw instanceof Number n && !(raw instanceof Double) && !(raw instanceof Float) ? String.valueOf(n.longValue()) : null;
        if (s == null || !s.matches("[1-9][0-9]{0,18}")) throw ChannelRejection.badField("id");
        return Long.parseLong(s);
    }

    private static String encode(Object value)
    {
        try { return JSON.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Cannot store onboarding answers", e); }
    }

    private static String str(Object value) { return value == null ? "" : value.toString(); }
}
