package com.chengyinhub.business.domain;

import com.chengyinhub.business.service.support.JourneyStoryValidator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 主题级剧情配置 journeyStory(R8-STORY,主题级一份)。
 *
 * <p>形状见共享契约 v4-B:{@code schemaVersion/call/stages/ending}。空 = 未配置,
 * 老主题行为完全不变。它只描述「剧情怎么说」,不含任何求值逻辑:条件一律走
 * {@link com.chengyinhub.business.service.support.TopicRouteEngine#conditionsPass},
 * 读接口在 PlayJourneyStoryServiceImpl。
 *
 * <p>{@link #parse} 先过 {@link JourneyStoryValidator} 再反序列化:能解析出来的必定是
 * 已通过保存期校验的那份(读侧不再重复注册表核对)。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class JourneyStory {

    public static final int SCHEMA_VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private int schemaVersion = SCHEMA_VERSION;
    private Long openingChapterId;
    private Call call;
    private List<Stage> stages;
    private Ending ending;

    /** 空/缺省返回 null = 未配置;非法配置抛 {@link IllegalArgumentException}。 */
    public static JourneyStory parse(String rawJson) {
        String checked = new JourneyStoryValidator().validate(rawJson, JourneyStoryValidator.Context.none());
        if (checked == null) {
            return null;
        }
        try {
            return MAPPER.readValue(checked, JourneyStory.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("主题剧情配置无法解析");
        }
    }

    /**
     * 仅重映射既有剧情条件中的节点引用；使用原始树保留未知字段与作者顺序。
     * 调用方须先处理来源剧情，再重建结局章；不得把新旧 ID 混合后重复映射。
     */
    public static String remapNodeIds(String rawJson, java.util.Map<Long, Long> nodeOldToNew) {
        if (rawJson == null || rawJson.trim().isEmpty()) return rawJson;
        try {
            JsonNode root = MAPPER.readTree(rawJson);
            if (root == null || !root.isObject()) throw new IllegalArgumentException("主题剧情必须是对象");
            boolean changed = false;
            JsonNode stages = root.path("stages");
            if (stages.isArray()) for (JsonNode stage : stages) {
                JsonNode changes = stage.path("end").path("changed");
                if (changes.isArray()) for (JsonNode change : changes)
                    changed |= remapNodeCondition(change.path("when"), nodeOldToNew);
            }
            JsonNode ending = root.path("ending");
            if (ending.isObject()) {
                JsonNode disposition = ending.get("dispositionNodeId");
                if (disposition != null && !disposition.isNull()) {
                    if (!disposition.isIntegralNumber() || !disposition.canConvertToLong() || disposition.asLong() <= 0L)
                        throw new IllegalArgumentException("结局配置 ending.dispositionNodeId 必须是正整数");
                    ((com.fasterxml.jackson.databind.node.ObjectNode) ending).put("dispositionNodeId",
                            mappedNodeId(disposition.asLong(), nodeOldToNew));
                    changed = true;
                }
                for (String field : new String[]{"endings", "badges"}) {
                    JsonNode rows = ending.path(field);
                    if (rows.isArray()) for (JsonNode row : rows) {
                        JsonNode conditions = row.path("when");
                        if (conditions.isArray()) for (JsonNode condition : conditions)
                            changed |= remapNodeCondition(condition, nodeOldToNew);
                    }
                }
                JsonNode exhibits = ending.path("exhibits");
                if (exhibits.isArray()) for (JsonNode exhibit : exhibits)
                    changed |= remapNodeCondition(exhibit.path("when"), nodeOldToNew);
            }
            return changed ? MAPPER.writeValueAsString(root) : rawJson;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("主题剧情节点引用无法重映射", e);
        }
    }

    private static boolean remapNodeCondition(JsonNode condition, java.util.Map<Long, Long> nodeOldToNew) {
        if (condition == null || !condition.isObject()) return false;
        com.fasterxml.jackson.databind.node.ObjectNode object = (com.fasterxml.jackson.databind.node.ObjectNode) condition;
        if ("NODE_COMPLETED".equals(condition.path("op").asText())) {
            // 沿用现有 validator/结局章 helper 的 asLong 正值语义，不在此收紧旧输入类型。
            object.put("nodeId", mappedNodeId(condition.path("nodeId").asLong(0L), nodeOldToNew));
            return true;
        }
        JsonNode variable = condition.get("var");
        if (variable == null || !variable.isTextual() || !variable.asText().startsWith("sys.outcome.")) return false;
        String name = variable.asText();
        if (!name.matches("sys\\.outcome\\.[1-9][0-9]*"))
            throw new IllegalArgumentException("剧情条件 sys.outcome 节点引用不合法");
        final long source;
        try { source = Long.parseLong(name.substring("sys.outcome.".length())); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("剧情条件 sys.outcome 节点引用不合法", invalid); }
        object.put("var", "sys.outcome." + mappedNodeId(source, nodeOldToNew));
        return true;
    }

    private static long mappedNodeId(long source, java.util.Map<Long, Long> nodeOldToNew) {
        Long mapped = nodeOldToNew == null ? null : nodeOldToNew.get(source);
        if (source <= 0L || mapped == null || mapped <= 0L)
            throw new IllegalArgumentException("剧情引用的站点不在本次提交里或映射不合法:nodeId=" + source);
        return mapped;
    }

    /**
     * 章节 id 重映射:章节在编辑重建 / 复制 / 重开一期后都会换新 id,剧情 stages[].chapterId
     * 必须跟着 old→new 走。映射缺项 = 剧情引用了本次提交里不存在的章节,fail-closed。
     */
    public static String remapChapterIds(String rawJson, java.util.Map<Long, Long> chapterIdMap) {
        if (rawJson == null || rawJson.trim().isEmpty()) {
            return rawJson;
        }
        try {
            JsonNode root = MAPPER.readTree(rawJson);
            JsonNode stages = root == null ? null : root.get("stages");
            JsonNode opening = root == null ? null : root.get("openingChapterId");
            JsonNode endingCases = root == null ? null : root.path("ending").get("endings");
            boolean hasStages = stages != null && stages.isArray() && stages.size() > 0;
            boolean hasEndingChapters = false;
            if (endingCases != null && endingCases.isArray()) {
                for (JsonNode item : endingCases) if (item != null && item.hasNonNull("chapterId")) hasEndingChapters = true;
            }
            if (!hasStages && !hasEndingChapters && (opening == null || opening.isNull())) return rawJson;
            // 结局章引用:映射不到(那一章已删)就摘掉 chapterId,这条结局退回只出标题 + 一句话,不指向旧行
            if (hasEndingChapters) {
                for (JsonNode item : endingCases) {
                    if (item == null || !item.isObject() || !item.hasNonNull("chapterId")) continue;
                    Long mapped = chapterIdMap == null ? null : chapterIdMap.get(item.get("chapterId").asLong());
                    if (mapped == null) ((com.fasterxml.jackson.databind.node.ObjectNode) item).remove("chapterId");
                    else ((com.fasterxml.jackson.databind.node.ObjectNode) item).put("chapterId", mapped);
                }
            }
            if (opening != null && !opening.isNull()) {
                Long mapped = chapterIdMap == null ? null : chapterIdMap.get(opening.asLong());
                if (mapped == null) throw new IllegalArgumentException("开局引用的章节不在本次提交中");
                ((com.fasterxml.jackson.databind.node.ObjectNode) root).put("openingChapterId", mapped);
            }
            for (JsonNode stage : hasStages ? stages : MAPPER.createArrayNode()) {
                if (stage == null || !stage.isObject()) {
                    continue;
                }
                long chapterId = stage.path("chapterId").asLong(0L);
                Long mapped = chapterIdMap == null ? null : chapterIdMap.get(Long.valueOf(chapterId));
                if (mapped == null) {
                    throw new IllegalArgumentException("剧情阶段引用的章节不在本次提交中:chapterId=" + chapterId);
                }
                ((com.fasterxml.jackson.databind.node.ObjectNode) stage).put("chapterId", mapped.longValue());
            }
            return MAPPER.writeValueAsString(root);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("主题剧情配置无法重映射");
        }
    }

    /** 编辑回显只读取开局引用，允许作者继续补齐尚未通过发布校验的剧情草稿。 */
    public static Long openingChapterId(String rawJson) {
        if (rawJson == null || rawJson.trim().isEmpty()) return null;
        try {
            JsonNode root = MAPPER.readTree(rawJson);
            JsonNode id = root == null ? null : root.get("openingChapterId");
            if (id == null || id.isNull()) return null;
            if (!id.isIntegralNumber() || !id.canConvertToLong() || id.asLong() <= 0)
                throw new IllegalArgumentException("开局章节必须是正整数");
            return id.asLong();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("开局配置无法读取", e);
        }
    }

    /** 开局由章节编排指定；替换引用时保留已有剧情配置。 */
    public static String withOpeningChapterId(String rawJson, Long chapterId) {
        if ((rawJson == null || rawJson.trim().isEmpty()) && chapterId == null) return rawJson;
        try {
            com.fasterxml.jackson.databind.node.ObjectNode root = rawJson == null || rawJson.trim().isEmpty()
                    ? MAPPER.createObjectNode().put("schemaVersion", SCHEMA_VERSION)
                    : (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(rawJson);
            if (chapterId == null) root.remove("openingChapterId");
            else root.put("openingChapterId", chapterId);
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalArgumentException("开局配置无法保存", e);
        }
    }

    /** 哪些章是结局章(ending.endings[].chapterId):玩家的章节列表里不出现它们,读结局时才播。读不出返回空集。 */
    public static java.util.Set<Long> endingChapterIds(String rawJson) {
        java.util.Set<Long> ids = new java.util.LinkedHashSet<Long>();
        if (rawJson == null || rawJson.trim().isEmpty()) return ids;
        try {
            for (JsonNode item : MAPPER.readTree(rawJson).path("ending").path("endings")) {
                if (item != null && item.path("chapterId").canConvertToLong() && item.path("chapterId").asLong() > 0L)
                    ids.add(item.path("chapterId").asLong());
            }
        } catch (Exception unreadable) {
            return ids;
        }
        return ids;
    }

    /** 一章结局章:落库后的新章节 id、章名、简介,以及作者配的 {fallback, when[]}。 */
    public static final class EndingChapter {
        private final Long chapterId;
        private final String title;
        private final String summary;
        private final JsonNode ending;

        public EndingChapter(Long chapterId, String title, String summary, JsonNode ending) {
            this.chapterId = chapterId;
            this.title = title;
            this.summary = summary;
            this.ending = ending;
        }
    }

    /**
     * 结局 = 结局章(2026-09-24):按章节重建 ending.endings —— 标题取章名、一句话取章节简介、
     * 条件取作者在章节上配的 when,chapterId 指向新章节。ending 的其它段(尾声 / 展品 / 勋章等)原样保留。
     * 「到过某站」(NODE_COMPLETED)里的站点 id 按本次重建的 old→new 换;映射不到 = 那一站不在本次提交里,fail-closed。
     */
    public static String withEndingChapters(String rawJson, java.util.List<EndingChapter> chapters,
                                            java.util.Map<Long, Long> nodeOldToNew) {
        if (chapters == null || chapters.isEmpty()) return rawJson;
        try {
            com.fasterxml.jackson.databind.node.ObjectNode root = rawJson == null || rawJson.trim().isEmpty()
                    ? MAPPER.createObjectNode().put("schemaVersion", SCHEMA_VERSION)
                    : (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(rawJson);
            com.fasterxml.jackson.databind.node.ObjectNode ending = root.get("ending") instanceof com.fasterxml.jackson.databind.node.ObjectNode
                    ? (com.fasterxml.jackson.databind.node.ObjectNode) root.get("ending") : root.putObject("ending");
            com.fasterxml.jackson.databind.node.ArrayNode list = ending.putArray("endings");
            for (int i = 0; i < chapters.size(); i++) {
                EndingChapter chapter = chapters.get(i);
                com.fasterxml.jackson.databind.node.ObjectNode item = list.addObject();
                item.put("code", "ending" + (i + 1));
                String title = chapter.title == null ? "" : chapter.title.trim();
                item.put("title", title.isEmpty() ? "结局 " + (i + 1) : title);
                String summary = chapter.summary == null ? "" : chapter.summary.trim();
                if (!summary.isEmpty()) item.put("summary", summary.length() > 80 ? summary.substring(0, 80) : summary);
                item.put("chapterId", chapter.chapterId);
                if (chapter.ending.path("fallback").asBoolean(false)) {
                    item.put("fallback", true);
                    continue;
                }
                com.fasterxml.jackson.databind.node.ArrayNode when = item.putArray("when");
                for (JsonNode cond : chapter.ending.path("when")) {
                    JsonNode copy = cond.deepCopy();
                    remapNodeCondition(copy, nodeOldToNew);
                    when.add(copy);
                }
            }
            return MAPPER.writeValueAsString(root);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("结局章配置无法保存", e);
        }
    }

    /**
     * NPC id 重映射:节点 NPC 与门店分身不随主题复制,复制 / 重开一期后副本 NPC 换新 id,
     * 剧情 {@code call.npcId} 与 {@code ending.epilogues[].npcId} 必须跟着 old→new 走。
     *
     * <p>映射缺项 = 该 NPC 没有对应副本(门店分身完全不随复制)= fail-closed:
     * 该 npcId 置空并往 {@code warnings} 追加一句人话提示,绝不静默指向来源期的 NPC
     * (置空后 call / epilogues 过不了保存期校验,发布前必须先重新指定)。
     */
    public static String remapNpcIds(String rawJson, java.util.Map<Long, Long> npcIdMap,
                                     java.util.List<String> warnings) {
        if (rawJson == null || rawJson.trim().isEmpty()) {
            return rawJson;
        }
        try {
            JsonNode root = MAPPER.readTree(rawJson);
            boolean touched = false;
            JsonNode call = root == null ? null : root.get("call");
            if (call != null && call.isObject()) {
                touched |= remapNpcField((com.fasterxml.jackson.databind.node.ObjectNode) call, "npcId",
                        "剧情开场 call.npcId", npcIdMap, warnings);
            }
            JsonNode epilogues = root == null ? null : root.path("ending").path("epilogues");
            if (epilogues != null && epilogues.isArray()) {
                for (int i = 0; i < epilogues.size(); i++) {
                    JsonNode item = epilogues.get(i);
                    if (item != null && item.isObject()) {
                        touched |= remapNpcField((com.fasterxml.jackson.databind.node.ObjectNode) item, "npcId",
                                "结局配置 ending.epilogues[" + i + "].npcId", npcIdMap, warnings);
                    }
                }
            }
            return touched ? MAPPER.writeValueAsString(root) : rawJson;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("主题剧情配置无法重映射 NPC");
        }
    }

    /** 单条 npcId:映射命中换新 id,缺项置空并提示;字段缺省 / 非整数不动。 */
    private static boolean remapNpcField(com.fasterxml.jackson.databind.node.ObjectNode owner, String field,
                                         String label, java.util.Map<Long, Long> npcIdMap,
                                         java.util.List<String> warnings) {
        JsonNode node = owner.get(field);
        if (node == null || node.isNull() || !node.canConvertToLong()) {
            return false;
        }
        long oldId = node.asLong();
        Long mapped = npcIdMap == null ? null : npcIdMap.get(Long.valueOf(oldId));
        if (mapped != null) {
            owner.put(field, mapped.longValue());
        } else {
            owner.putNull(field);
            if (warnings != null) {
                warnings.add(label + "=" + oldId + " 没有对应的新 NPC(节点 NPC 未随复制 / 门店分身不随复制),已置空");
            }
        }
        return true;
    }

    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }

    public Long getOpeningChapterId() { return openingChapterId; }
    public void setOpeningChapterId(Long openingChapterId) { this.openingChapterId = openingChapterId; }

    public Call getCall() { return call; }
    public void setCall(Call call) { this.call = call; }

    public List<Stage> getStages() { return stages; }
    public void setStages(List<Stage> stages) { this.stages = stages; }

    public Ending getEnding() { return ending; }
    public void setEnding(Ending ending) { this.ending = ending; }

    /** 开场通话:一个 NPC、一段开场白、2–4 个选项。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Call {
        private Long npcId;
        private String opener;
        private List<Option> options;
        private String brief;

        public Long getNpcId() { return npcId; }
        public void setNpcId(Long npcId) { this.npcId = npcId; }
        public String getOpener() { return opener; }
        public void setOpener(String opener) { this.opener = opener; }
        public List<Option> getOptions() { return options; }
        public void setOptions(List<Option> options) { this.options = options; }
        public String getBrief() { return brief; }
        public void setBrief(String brief) { this.brief = brief; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Option {
        private String id;
        private String line;
        private String reply;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getLine() { return line; }
        public void setLine(String line) { this.line = line; }
        public String getReply() { return reply; }
        public void setReply(String reply) { this.reply = reply; }
    }

    /** 阶段 = 现有章节;{@code end.changed} 按 cond 取句。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Stage {
        private Long chapterId;
        private String question;
        private StageEnd end;

        public Long getChapterId() { return chapterId; }
        public void setChapterId(Long chapterId) { this.chapterId = chapterId; }
        public String getQuestion() { return question; }
        public void setQuestion(String question) { this.question = question; }
        public StageEnd getEnd() { return end; }
        public void setEnd(StageEnd end) { this.end = end; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StageEnd {
        private List<Change> changed;
        private String open;

        public List<Change> getChanged() { return changed; }
        public void setChanged(List<Change> changed) { this.changed = changed; }
        public String getOpen() { return open; }
        public void setOpen(String open) { this.open = open; }
    }

    /** 一条变化句:{@code when} 为 cond 或 null(无条件成立)。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Change {
        private JsonNode when;
        private String text;

        public JsonNode getWhen() { return when; }
        public void setWhen(JsonNode when) { this.when = when; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Ending {
        private Long dispositionNodeId;
        private List<EndingCase> endings;
        private List<Epilogue> epilogues;
        private List<Exhibit> exhibits;
        private List<Badge> badges;
        private List<String> optionalClues;

        public Long getDispositionNodeId() { return dispositionNodeId; }
        public void setDispositionNodeId(Long dispositionNodeId) { this.dispositionNodeId = dispositionNodeId; }
        public List<EndingCase> getEndings() { return endings; }
        public void setEndings(List<EndingCase> endings) { this.endings = endings; }
        public List<Epilogue> getEpilogues() { return epilogues; }
        public void setEpilogues(List<Epilogue> epilogues) { this.epilogues = epilogues; }
        public List<Exhibit> getExhibits() { return exhibits; }
        public void setExhibits(List<Exhibit> exhibits) { this.exhibits = exhibits; }
        public List<Badge> getBadges() { return badges; }
        public void setBadges(List<Badge> badges) { this.badges = badges; }
        public List<String> getOptionalClues() { return optionalClues; }
        public void setOptionalClues(List<String> optionalClues) { this.optionalClues = optionalClues; }
    }

    /** 结局分支:{@code when} 全通过取第一个,都不中取 {@code fallback=true} 那条。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EndingCase {
        private String code;
        private String title;
        private String summary;
        private Boolean fallback;
        private JsonNode when;
        /** 结局章(2026-09-24):命中这条就把这一章当故事流播;老数据没有它时只出标题 + 一句话。 */
        private Long chapterId;

        public Long getChapterId() { return chapterId; }
        public void setChapterId(Long chapterId) { this.chapterId = chapterId; }
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }
        public Boolean getFallback() { return fallback; }
        public void setFallback(Boolean fallback) { this.fallback = fallback; }
        public JsonNode getWhen() { return when; }
        public void setWhen(JsonNode when) { this.when = when; }
    }

    /**
     * 尾声三态:{@code relationKey} 指向的已注册 relation.* 键,≥1 = ally、=0 或缺 = neutral、
     * ≤-1 = guarded。relationKey 是显式口径(必填、已注册、类型 relation),不再由 npcId 推隐含键。
     *
     * <p>npcId 仍是该尾声对应的 NPC,案件册按 relationKey → npcId 取展示名。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Epilogue {
        private Long npcId;
        private String relationKey;
        private String ally;
        private String neutral;
        private String guarded;

        public Long getNpcId() { return npcId; }
        public void setNpcId(Long npcId) { this.npcId = npcId; }
        public String getRelationKey() { return relationKey; }
        public void setRelationKey(String relationKey) { this.relationKey = relationKey; }
        public String getAlly() { return ally; }
        public void setAlly(String ally) { this.ally = ally; }
        public String getNeutral() { return neutral; }
        public void setNeutral(String neutral) { this.neutral = neutral; }
        public String getGuarded() { return guarded; }
        public void setGuarded(String guarded) { this.guarded = guarded; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Exhibit {
        private JsonNode when;
        private String text;

        public JsonNode getWhen() { return when; }
        public void setWhen(JsonNode when) { this.when = when; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Badge {
        private JsonNode when;
        private String title;
        private Boolean fallback;

        public JsonNode getWhen() { return when; }
        public void setWhen(JsonNode when) { this.when = when; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public Boolean getFallback() { return fallback; }
        public void setFallback(Boolean fallback) { this.fallback = fallback; }
    }
}
