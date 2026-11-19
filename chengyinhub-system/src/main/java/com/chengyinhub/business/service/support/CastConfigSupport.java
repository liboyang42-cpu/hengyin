package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.dto.ChapterBlockDTO;
import com.chengyinhub.business.domain.dto.ChapterDTO;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 角色(Cast)配置 v1(2026-11-19 业主批准):cms_topic.cast_config 的唯一校验与规范化入口。
 *
 * <pre>
 * {enabled, playerName, playerIdentity,
 *  identities:[{key,title,sub}] ≤4,
 *  cast:[{id,name,identity,avatarUrl,color,voice:{mode,preset}}] ≤12}
 * </pre>
 *
 * 列为 null = 功能关闭,接口原样回 null。保存时只留契约字段(未知字段丢弃),校验失败抛 400 的 {@link ServiceException}。
 * 故事块里的引用(line.speaker、branch 选项 trust.castId、结局章 ending.endingFor)由 {@link #checkStoryRefs} 对照本配置校验。
 */
public final class CastConfigSupport
{
    public static final int MAX_IDENTITIES = 4;
    public static final int MAX_CAST = 12;
    public static final String PLAYER = "player";
    public static final Set<String> COLORS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "#E8604C", "#3D8BFD", "#2FB57A", "#B98AF0", "#F2B33D", "#8E8E93")));
    public static final Set<String> VOICE_MODES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "preset", "record", "none")));
    public static final Set<String> VOICE_PRESETS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "bright_female", "warm_female", "deep_male", "youth", "elder")));
    public static final Pattern CAST_ID = Pattern.compile("^c_[a-z0-9]{6,12}$");
    public static final Pattern IDENTITY_KEY = Pattern.compile("^[A-Za-z0-9_-]{1,32}$");
    public static final Pattern MEDIA_ASSET = Pattern.compile("^media-asset:[1-9][0-9]{0,18}$");
    public static final int MAX_URL_LENGTH = 500;

    private static final ObjectMapper JSON = new ObjectMapper();

    private CastConfigSupport()
    {
    }

    /** 空串 / https 地址 / media-asset:N;其它一律不认。 */
    public static boolean isMediaRef(String url, boolean allowEmpty)
    {
        if (url == null || url.isEmpty()) return allowEmpty;
        if (url.length() > MAX_URL_LENGTH) return false;
        if (MEDIA_ASSET.matcher(url).matches()) return true;
        return url.startsWith("https://") && url.length() > "https://".length() && !url.matches(".*\\s.*");
    }

    /** 读库:null / 空 / 坏 JSON → null(功能关闭)。 */
    public static JsonNode parse(String stored)
    {
        if (stored == null || stored.trim().isEmpty()) return null;
        try
        {
            JsonNode node = JSON.readTree(stored);
            return node == null || node.isNull() || !node.isObject() ? null : node;
        }
        catch (JsonProcessingException e)
        {
            return null;
        }
    }

    /**
     * 写库前校验 + 规范化。raw 为 null / JSON null 时返回 null(调用方据此不改列)。
     * 客户端可以发对象,也可以发 JSON 字符串。
     */
    public static String normalize(JsonNode raw)
    {
        if (raw == null || raw.isNull()) return null;
        JsonNode cfg = raw;
        if (raw.isTextual())
        {
            String text = raw.asText().trim();
            if (text.isEmpty()) return null;
            try { cfg = JSON.readTree(text); }
            catch (JsonProcessingException e) { throw invalid("角色配置不是合法 JSON"); }
        }
        if (cfg == null || !cfg.isObject()) throw invalid("角色配置必须是对象");

        ObjectNode out = JSON.createObjectNode();
        out.put("enabled", bool(cfg, "enabled"));
        out.put("playerName", bool(cfg, "playerName"));
        boolean playerIdentity = bool(cfg, "playerIdentity");
        out.put("playerIdentity", playerIdentity);

        ArrayNode identities = out.putArray("identities");
        JsonNode rawIdentities = cfg.get("identities");
        if (rawIdentities != null && !rawIdentities.isNull())
        {
            if (!rawIdentities.isArray()) throw invalid("玩家身份选项必须是数组");
            if (rawIdentities.size() > MAX_IDENTITIES) throw invalid("玩家身份选项最多 " + MAX_IDENTITIES + " 个");
            Set<String> keys = new HashSet<>();
            for (JsonNode one : rawIdentities)
            {
                if (one == null || !one.isObject()) throw invalid("玩家身份选项格式不对");
                String key = text(one, "key");
                if (!IDENTITY_KEY.matcher(key).matches()) throw invalid("玩家身份标识无效:" + key);
                if (!keys.add(key)) throw invalid("玩家身份标识重复:" + key);
                String title = text(one, "title").trim();
                if (len(title) < 1 || len(title) > 8) throw invalid("玩家身份名称要 1 至 8 字");
                String sub = text(one, "sub").trim();
                if (len(sub) > 24) throw invalid("玩家身份说明最多 24 字");
                ObjectNode row = identities.addObject();
                row.put("key", key);
                row.put("title", title);
                row.put("sub", sub);
            }
        }
        if (playerIdentity && identities.size() == 0) throw invalid("开启玩家身份时至少要给一个身份选项");

        ArrayNode cast = out.putArray("cast");
        JsonNode rawCast = cfg.get("cast");
        if (rawCast != null && !rawCast.isNull())
        {
            if (!rawCast.isArray()) throw invalid("角色列表必须是数组");
            if (rawCast.size() > MAX_CAST) throw invalid("角色最多 " + MAX_CAST + " 个");
            Set<String> ids = new HashSet<>();
            Set<String> names = new HashSet<>();
            for (JsonNode one : rawCast)
            {
                if (one == null || !one.isObject()) throw invalid("角色格式不对");
                String id = text(one, "id");
                if (!CAST_ID.matcher(id).matches()) throw invalid("角色标识无效:" + id);
                if (!ids.add(id)) throw invalid("角色标识重复:" + id);
                String name = text(one, "name").trim();
                if (len(name) < 1 || len(name) > 8) throw invalid("角色名要 1 至 8 字");
                if (!names.add(name)) throw invalid("角色名重复:" + name);
                String identity = text(one, "identity").trim();
                if (len(identity) > 30) throw invalid("角色身份最多 30 字");
                String avatarUrl = text(one, "avatarUrl").trim();
                if (!isMediaRef(avatarUrl, true)) throw invalid("角色头像只能是 https 地址或已上传的素材");
                String color = text(one, "color");
                if (!COLORS.contains(color)) throw invalid("角色颜色只能从预设里选:" + color);
                JsonNode voice = one.get("voice");
                String mode = "none";
                String preset = null;
                if (voice != null && !voice.isNull())
                {
                    if (!voice.isObject()) throw invalid("角色声音格式不对");
                    mode = text(voice, "mode");
                    if (!VOICE_MODES.contains(mode)) throw invalid("角色声音方式只能是 preset / record / none");
                    String p = text(voice, "preset");
                    if (!p.isEmpty())
                    {
                        if (!VOICE_PRESETS.contains(p)) throw invalid("角色预设声音无效:" + p);
                        preset = p;
                    }
                    if ("preset".equals(mode) && preset == null) throw invalid("选了预设声音就要指定是哪一种");
                }
                ObjectNode row = cast.addObject();
                row.put("id", id);
                row.put("name", name);
                row.put("identity", identity);
                row.put("avatarUrl", avatarUrl);
                row.put("color", color);
                ObjectNode v = row.putObject("voice");
                v.put("mode", mode);
                if (preset != null) v.put("preset", preset);
            }
        }
        try
        {
            return JSON.writeValueAsString(out);
        }
        catch (JsonProcessingException e)
        {
            throw new IllegalStateException("角色配置序列化失败", e);
        }
    }

    public static Set<String> castIds(JsonNode cfg)
    {
        Set<String> ids = new LinkedHashSet<>();
        if (cfg == null) return ids;
        for (JsonNode one : cfg.path("cast"))
        {
            String id = one.path("id").asText("");
            if (!id.isEmpty()) ids.add(id);
        }
        return ids;
    }

    public static JsonNode findCast(JsonNode cfg, String castId)
    {
        if (cfg == null || castId == null) return null;
        for (JsonNode one : cfg.path("cast"))
        {
            if (castId.equals(one.path("id").asText())) return one;
        }
        return null;
    }

    public static JsonNode findIdentity(JsonNode cfg, String key)
    {
        if (cfg == null || key == null) return null;
        for (JsonNode one : cfg.path("identities"))
        {
            if (key.equals(one.path("key").asText())) return one;
        }
        return null;
    }

    /**
     * 保存时对照角色配置检查故事里的引用:line.speaker 必须是 player 或配置里的角色;
     * branch 选项 trust.castId、结局章 ending.endingFor 必须是配置里的角色。cfg 为 null 时没有任何角色可引用。
     */
    public static void checkStoryRefs(List<ChapterDTO> chapters, JsonNode cfg)
    {
        if (chapters == null) return;
        Set<String> ids = castIds(cfg);
        for (int i = 0; i < chapters.size(); i++)
        {
            ChapterDTO chapter = chapters.get(i);
            if (chapter == null) continue;
            int no = i + 1;
            JsonNode ending = chapter.getEnding();
            if (ending != null && ending.isObject() && ending.hasNonNull("endingFor"))
            {
                String endingFor = ending.get("endingFor").asText("");
                if (!ids.contains(endingFor)) throw invalid("第 " + no + " 章:结局指向的角色不存在:" + endingFor);
            }
            if (chapter.getBlocks() == null) continue;
            for (ChapterBlockDTO block : chapter.getBlocks())
            {
                if (block == null) continue;
                if ("line".equals(block.getType()))
                {
                    String speaker = block.getSpeaker() == null ? "" : block.getSpeaker().trim();
                    if (!PLAYER.equals(speaker) && !ids.contains(speaker))
                        throw invalid("第 " + no + " 章:台词的说话人不在角色表里:" + speaker);
                }
                else if ("branch".equals(block.getType()) && block.getOptions() != null)
                {
                    for (ChapterBlockDTO.BranchOptionDTO option : block.getOptions())
                    {
                        if (option == null || option.getTrust() == null) continue;
                        for (ChapterBlockDTO.TrustDeltaDTO delta : option.getTrust())
                        {
                            String castId = delta == null ? null : delta.getCastId();
                            if (castId == null || !ids.contains(castId))
                                throw invalid("第 " + no + " 章:分支信任变化指向的角色不存在:" + castId);
                        }
                    }
                }
            }
        }
    }

    private static boolean bool(JsonNode cfg, String field)
    {
        JsonNode v = cfg.get(field);
        if (v == null || v.isNull()) return false;
        if (!v.isBoolean()) throw invalid("角色配置 " + field + " 必须是布尔值");
        return v.booleanValue();
    }

    private static String text(JsonNode node, String field)
    {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return "";
        if (!v.isTextual()) throw invalid("角色配置 " + field + " 必须是文字");
        return v.asText();
    }

    private static int len(String s)
    {
        return s.codePointCount(0, s.length());
    }

    private static ServiceException invalid(String message)
    {
        return new ServiceException(message, 400);
    }
}
