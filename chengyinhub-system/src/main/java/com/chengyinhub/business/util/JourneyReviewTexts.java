package com.chengyinhub.business.util;

import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.domain.dto.ChapterBlockDTO;
import com.chengyinhub.business.domain.dto.ChapterDTO;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 旅程新叙事的「可审核文本」收集器(R12)。
 *
 * <p>收集三处创作者可写文本,供两个用途共用:
 * <ul>
 *   <li>保存 / 发布期的内容安全送检(与服务端既有 checkText 同一套检测,见 {@link #check});</li>
 *   <li>后台审核详情里的可读文本清单(审核员不必自己解 JSON)。</li>
 * </ul>
 *
 * <p>覆盖范围:
 * <ul>
 *   <li>{@code journey_story_json}:call.opener / options[].line / options[].reply / call.brief、
 *       stages[].question、stages[].end.changed[].text、stages[].end.open、
 *       ending.endings[].title / summary、ending.epilogues[].ally / neutral / guarded、
 *       ending.exhibits[].text、ending.badges[].title;</li>
 *   <li>节点块配置(advancedConfigJson)叙事段:enter.opener / aside / questions[].q / a、
 *       outcome.title / line / lineIfFail / open / facts[].text、deliver.reward / line、
 *       unlock.hook、revisit.changes[].text、brief.question / body / carry[].text、
 *       route.walk / todo、check.skill / successText / failText / check.mods[].label;</li>
 *   <li>节点块配置玩法题面段(R14-PLAY 并入):sort.prompt / items[].label、
 *       match.prompt / left[].label / right[].label、classify.prompt / bins[].label / items[].label、
 *       compare.prompt / 左右 label / items[].time / items[].text;</li>
 *   <li>theme_state_key.label;</li>
 *   <li>R19 章节故事流 blocks 的 beat 块正文(叙事搬进故事流后的真源:
 *       写路径收 DTO、审核详情收存量 JSON)。</li>
 * </ul>
 *
 * <p>配置解析不了时返回空清单(不抛):坏 JSON 由各自的保存期校验器报错,
 * 这里不能抢在它前面把「配置不合法」变成「内容不合规」。
 * R14-PLAY 的独立收集器并入本类时,这条统一以本类契约为准。
 */
public final class JourneyReviewTexts {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 单批送检的最大字符数。微信 msgSecCheck v2 对 content 有长度上限(2500),
     * 超过会走 fail-close 被当成「审核不可用」而拒绝合法长配置,所以按字段逐个切片分批。
     */
    static final int MAX_BATCH_CHARS = 2000;

    private JourneyReviewTexts() {
    }

    /** 一条可审核文本:field 是给作者/审核员看的字段路径,text 是正文。 */
    public static final class Entry {
        private final String field;
        private final String text;

        public Entry(String field, String text) {
            this.field = field;
            this.text = text;
        }

        public String getField() {
            return field;
        }

        public String getText() {
            return text;
        }
    }

    public static List<Entry> collectChapters(List<ChapterDTO> chapters) {
        List<Entry> entries = new ArrayList<>();
        if (chapters == null) return entries;
        for (int i = 0; i < chapters.size(); i++) {
            ChapterDTO chapter = chapters.get(i);
            if (chapter == null) continue;
            String path = "chapters[" + i + "]";
            add(entries, path + ".name", chapter.getName());
            add(entries, path + ".description", chapter.getDescription());
            add(entries, path + ".category", chapter.getCategory());
            if (chapter.getNodes() == null) continue;
            for (int j = 0; j < chapter.getNodes().size(); j++) {
                com.chengyinhub.business.domain.dto.NodeDTO node = chapter.getNodes().get(j);
                if (node == null) continue;
                String nodePath = path + ".nodes[" + j + "]";
                add(entries, nodePath + ".name", node.getName());
                add(entries, nodePath + ".description", node.getDescription());
                add(entries, nodePath + ".address", node.getAddress());
                add(entries, nodePath + ".businessTime", node.getBusinessTime());
                add(entries, nodePath + ".hookText", node.getHookText());
                add(entries, nodePath + ".cardHookLong", node.getCardHookLong());
                add(entries, nodePath + ".fragmentText", node.getFragmentText());
            }
        }
        return entries;
    }

    public static List<Entry> collectThemeRuntime(String rawJson) {
        List<Entry> entries=new ArrayList<>();collectThemeRuntime(parse(rawJson),"themeRuntimeConfig",entries);return entries;
    }
    private static void collectThemeRuntime(JsonNode node,String path,List<Entry> entries) {
        if(node==null)return;
        if(node.isObject())node.fields().forEachRemaining(row->{String key=row.getKey();if(java.util.Arrays.asList("name","hpName","luckName","content","title","summary","body","label","prompt","fb","text","ally","neutral","guarded","opener","brief","line","reply","question","q","a","open","zeroMessage").contains(key))add(entries,path+"."+key,row.getValue());collectThemeRuntime(row.getValue(),path+"."+key,entries);});
        else if(node.isArray())for(JsonNode child:node)collectThemeRuntime(child,path+"[]",entries);
    }

    /** 主题级剧情配置 journey_story_json 的全部可读文本。 */
    public static List<Entry> collectJourneyStory(String rawJson) {
        List<Entry> entries = new ArrayList<Entry>();
        JsonNode root = parse(rawJson);
        if (root == null || !root.isObject()) {
            return entries;
        }
        JsonNode call = root.get("call");
        if (call != null && call.isObject()) {
            add(entries, "journeyStory.call.opener", call.get("opener"));
            add(entries, "journeyStory.call.brief", call.get("brief"));
            for (JsonNode option : array(call.get("options"))) {
                add(entries, "journeyStory.call.options[].line", option.get("line"));
                add(entries, "journeyStory.call.options[].reply", option.get("reply"));
            }
        }
        for (JsonNode stage : array(root.get("stages"))) {
            add(entries, "journeyStory.stages[].question", stage.get("question"));
            JsonNode end = stage.get("end");
            if (end != null && end.isObject()) {
                add(entries, "journeyStory.stages[].end.open", end.get("open"));
                for (JsonNode change : array(end.get("changed"))) {
                    add(entries, "journeyStory.stages[].end.changed[].text", change.get("text"));
                }
            }
        }
        JsonNode ending = root.get("ending");
        if (ending != null && ending.isObject()) {
            for (JsonNode endingCase : array(ending.get("endings"))) {
                add(entries, "journeyStory.ending.endings[].title", endingCase.get("title"));
                add(entries, "journeyStory.ending.endings[].summary", endingCase.get("summary"));
            }
            for (JsonNode epilogue : array(ending.get("epilogues"))) {
                add(entries, "journeyStory.ending.epilogues[].ally", epilogue.get("ally"));
                add(entries, "journeyStory.ending.epilogues[].neutral", epilogue.get("neutral"));
                add(entries, "journeyStory.ending.epilogues[].guarded", epilogue.get("guarded"));
            }
            for (JsonNode exhibit : array(ending.get("exhibits"))) {
                add(entries, "journeyStory.ending.exhibits[].text", exhibit.get("text"));
            }
            for (JsonNode badge : array(ending.get("badges"))) {
                add(entries, "journeyStory.ending.badges[].title", badge.get("title"));
            }
        }
        return entries;
    }

    /** 节点块配置 advancedConfigJson 叙事段的全部可读文本。 */
    public static List<Entry> collectBlocks(String rawJson) {
        List<Entry> entries = new ArrayList<Entry>();
        JsonNode root = parse(rawJson);
        if (root == null || !root.isObject()) {
            return entries;
        }
        JsonNode enter = root.get("enter");
        if (enter != null && enter.isObject()) {
            add(entries, "blockConfig.enter.opener", enter.get("opener"));
            add(entries, "blockConfig.enter.aside", enter.get("aside"));
            for (JsonNode question : array(enter.get("questions"))) {
                add(entries, "blockConfig.enter.questions[].q", question.get("q"));
                add(entries, "blockConfig.enter.questions[].a", question.get("a"));
            }
        }
        JsonNode outcome = root.get("outcome");
        if (outcome != null && outcome.isObject()) {
            add(entries, "blockConfig.outcome.title", outcome.get("title"));
            add(entries, "blockConfig.outcome.line", outcome.get("line"));
            add(entries, "blockConfig.outcome.lineIfFail", outcome.get("lineIfFail"));
            add(entries, "blockConfig.outcome.open", outcome.get("open"));
            for (JsonNode fact : array(outcome.get("facts"))) {
                add(entries, "blockConfig.outcome.facts[].text", fact.get("text"));
            }
        }
        JsonNode deliver = root.get("deliver");
        if (deliver != null && deliver.isObject()) {
            add(entries, "blockConfig.deliver.reward", deliver.get("reward"));
            add(entries, "blockConfig.deliver.line", deliver.get("line"));
        }
        JsonNode unlock = root.get("unlock");
        if (unlock != null && unlock.isObject()) {
            add(entries, "blockConfig.unlock.hook", unlock.get("hook"));
        }
        JsonNode revisit = root.get("revisit");
        if (revisit != null && revisit.isObject()) {
            for (JsonNode change : array(revisit.get("changes"))) {
                add(entries, "blockConfig.revisit.changes[].text", change.get("text"));
            }
        }
        JsonNode brief = root.get("brief");
        if (brief != null && brief.isObject()) {
            add(entries, "blockConfig.brief.question", brief.get("question"));
            add(entries, "blockConfig.brief.body", brief.get("body"));
            for (JsonNode item : array(brief.get("carry"))) {
                add(entries, "blockConfig.brief.carry[].text", item.get("text"));
            }
        }
        JsonNode route = root.get("route");
        if (route != null && route.isObject()) {
            add(entries, "blockConfig.route.walk", route.get("walk"));
            add(entries, "blockConfig.route.todo", route.get("todo"));
        }
        JsonNode check = root.get("check");
        if (check != null && check.isObject()) {
            add(entries, "blockConfig.check.skill", check.get("skill"));
            add(entries, "blockConfig.check.successText", check.get("successText"));
            add(entries, "blockConfig.check.failText", check.get("failText"));
            for (JsonNode mod : array(check.get("mods"))) {
                add(entries, "blockConfig.check.mods[].label", mod.get("label"));
            }
        }
        addPlaySections(entries, root);
        com.chengyinhub.business.service.support.GameplayOutcomeRules.visit(root,
                (path, row) -> add(entries, "blockConfig." + path + ".reply", row.get("reply")));
        return entries;
    }

    /**
     * R14-PLAY 并入:玩法题面段的玩家可见文本 —— 排序 / 连线 / 分类 / 时间轴对照的题干与标签。
     * 它们与叙事段一样是创作者可写、玩家必看的文本,同一道审核闸。
     */
    private static void addPlaySections(List<Entry> entries, JsonNode root) {
        JsonNode qa = root.path("qa");
        for (String field : java.util.Arrays.asList("title", "lead", "answerText", "shotNote")) {
            add(entries, "blockConfig.qa." + field, qa.get(field));
        }
        for (JsonNode option : array(qa.get("options"))) {
            add(entries, "blockConfig.qa.options[].label", option.get("label"));
            add(entries, "blockConfig.qa.options[].fb", option.get("fb"));
        }
        add(entries, "blockConfig.scan.reply", root.path("scan").get("reply"));
        for (JsonNode image : array(root.path("album").get("images"))) {
            add(entries, "blockConfig.album.images[].line", image.get("line"));
        }
        JsonNode photo = root.path("photoCheck");
        for (String field : java.util.Arrays.asList("title", "shotNote", "requirement", "cardTitle")) {
            add(entries, "blockConfig.photoCheck." + field, photo.get(field));
        }
        JsonNode note = root.path("note");
        add(entries, "blockConfig.note.title", note.get("title"));
        add(entries, "blockConfig.note.prompt", note.get("prompt"));
        JsonNode presets = note.get("presets");
        if (presets != null && presets.isArray()) for (JsonNode preset : presets) {
            if (preset.isTextual()) add(entries, "blockConfig.note.presets[]", preset);
            else if (preset.isObject()) {
                add(entries, "blockConfig.note.presets[].text", preset.get("text"));
                add(entries, "blockConfig.note.presets[].label", preset.get("label"));
            }
        }
        add(entries, "blockConfig.typeIn.title", root.path("typeIn").get("title"));
        add(entries, "blockConfig.typeIn.target", root.path("typeIn").get("target"));
        JsonNode sort = root.get("sort");
        if (sort != null && sort.isObject()) {
            add(entries, "blockConfig.sort.prompt", sort.get("prompt"));
            addLabels(entries, "blockConfig.sort.items[].label", sort.get("items"));
        }
        JsonNode match = root.get("match");
        if (match != null && match.isObject()) {
            add(entries, "blockConfig.match.prompt", match.get("prompt"));
            addLabels(entries, "blockConfig.match.left[].label", match.get("left"));
            addLabels(entries, "blockConfig.match.right[].label", match.get("right"));
        }
        JsonNode classify = root.get("classify");
        if (classify != null && classify.isObject()) {
            add(entries, "blockConfig.classify.prompt", classify.get("prompt"));
            addLabels(entries, "blockConfig.classify.bins[].label", classify.get("bins"));
            addLabels(entries, "blockConfig.classify.items[].label", classify.get("items"));
        }
        addCompare(entries, root.get("compare"));
    }

    /** 标签数组(排序卡片 / 连线两侧 / 分类两箱):每行的 label 各收一条。 */
    private static void addLabels(List<Entry> entries, String field, JsonNode rows) {
        for (JsonNode row : array(rows)) {
            add(entries, field, row.get("label"));
        }
    }

    /** 对照题:题干、左右两版标题与每条的 time / text;answer(该标哪几条)不在收集范围。 */
    private static void addCompare(List<Entry> entries, JsonNode compare) {
        if (compare == null || !compare.isObject()) {
            return;
        }
        add(entries, "blockConfig.compare.prompt", compare.get("prompt"));
        addCompareSide(entries, "left", compare.get("left"));
        addCompareSide(entries, "right", compare.get("right"));
    }

    private static void addCompareSide(List<Entry> entries, String side, JsonNode sideConfig) {
        if (sideConfig == null || !sideConfig.isObject()) {
            return;
        }
        String prefix = "blockConfig.compare." + side;
        add(entries, prefix + ".label", sideConfig.get("label"));
        for (JsonNode item : array(sideConfig.get("items"))) {
            add(entries, prefix + ".items[].time", item.get("time"));
            add(entries, prefix + ".items[].text", item.get("text"));
        }
    }

    /**
     * R19:章节故事流写接口的 beat 块正文(叙事搬进故事流后的真源)。
     *
     * <p>写路径拿的是 DTO(还没有 nodeId / 物化),字段名带章序号,便于审核员与作者定位;
     * 非 beat 的 text 块(章节旁白)不在本轮口径内,与既有 V2 保存路径一致。
     */
    public static List<Entry> collectChapterNarrativeBlocks(List<ChapterDTO> chapters) {
        List<Entry> entries = new ArrayList<Entry>();
        if (chapters == null) {
            return entries;
        }
        for (int index = 0; index < chapters.size(); index++) {
            ChapterDTO chapter = chapters.get(index);
            if (chapter == null || chapter.getBlocks() == null) {
                continue;
            }
            for (ChapterBlockDTO block : chapter.getBlocks()) {
                if (block == null) {
                    continue;
                }
                String field = storyFlowField(index + 1, block.getBeat(), block.getField(), block.getQid());
                if (field != null) {
                    add(entries, field, block.getContent());
                }
            }
        }
        return entries;
    }

    /**
     * R19:已物化章节 blocks 里 beat 块的正文(审核详情只读用)。
     * 坏 JSON / 非数组按「没收」处理 —— 与读取侧对脏块只跳过同一口径。
     */
    public static List<Entry> collectChapterBlocks(String blocksJson, int chapterNumber) {
        List<Entry> entries = new ArrayList<Entry>();
        JsonNode root = parse(blocksJson);
        if (root == null || !root.isArray()) {
            return entries;
        }
        for (JsonNode block : root) {
            if (block == null || !block.isObject()) {
                continue;
            }
            String field = storyFlowField(chapterNumber, block.path("beat").asText(""),
                    block.path("field").asText(""), block.path("qid").asText(""));
            if (field != null) {
                add(entries, field, block.get("content"));
            }
        }
        return entries;
    }

    /** beat 块正文的字段路径:storyFlow.chapter2.enter.q(night)。非 beat 块返回 null = 不收。 */
    private static String storyFlowField(int chapterNumber, String beat, String field, String qid) {
        if (beat == null || beat.trim().isEmpty()) {
            return null;
        }
        String name = "storyFlow.chapter" + chapterNumber + "." + beat.trim() + "."
                + (field == null ? "" : field.trim());
        return qid == null || qid.trim().isEmpty() ? name : name + "(" + qid.trim() + ")";
    }

    /** theme_state_key.label(注册表里给玩家看的名字)。 */
    public static List<Entry> collectLabels(List<ThemeStateKey> keys) {
        List<Entry> entries = new ArrayList<Entry>();
        if (keys == null) {
            return entries;
        }
        for (ThemeStateKey key : keys) {
            add(entries, "themeStateKey.label", key == null ? null : key.getLabel());
        }
        return entries;
    }

    /**
     * 分批送检收集到的文本。任一批命中 → 拒绝,并在该批内逐条复核,把违规字段名带进提示。
     *
     * <p>正常路径只按批调用(与既有 joinText 一次送检同一口径);只有命中时才多几次定位调用 ——
     * 违规是终态,作者必须改,多花几次调用换「知道改哪」是值得的。
     * 机审「审不了」(MSG_BUSY / MSG_UNAVAILABLE)不做定位:那不是某条内容违规。
     *
     * @return null = 全部放行;非 null = 拒绝提示(可能带字段名)
     */
    public static String check(IWxContentSecurityService securityService, String openid, List<Entry> entries) {
        if (securityService == null || entries == null || entries.isEmpty()) {
            return null;
        }
        List<Entry> batch = new ArrayList<Entry>();
        int chars = 0;
        for (Entry entry : entries) {
            if (entry == null || StringUtils.isEmpty(entry.getText())) {
                continue;
            }
            if (!batch.isEmpty() && chars + entry.getText().length() > MAX_BATCH_CHARS) {
                String msg = checkBatch(securityService, openid, batch);
                if (msg != null) {
                    return msg;
                }
                batch = new ArrayList<Entry>();
                chars = 0;
            }
            batch.add(entry);
            chars += entry.getText().length();
        }
        return batch.isEmpty() ? null : checkBatch(securityService, openid, batch);
    }

    private static String checkBatch(IWxContentSecurityService securityService, String openid, List<Entry> batch) {
        String[] parts = new String[batch.size()];
        for (int i = 0; i < batch.size(); i++) {
            parts[i] = batch.get(i).getText();
        }
        String msg = securityService.checkText(IWxContentSecurityService.joinText(parts), openid);
        if (msg == null || IWxContentSecurityService.isDetectionUnavailable(msg)) {
            return msg;
        }
        for (Entry entry : batch) {
            String one = securityService.checkText(entry.getText(), openid);
            if (one != null && !IWxContentSecurityService.isDetectionUnavailable(one)) {
                return one + "（字段：" + entry.getField() + "）";
            }
        }
        return msg;
    }

    private static JsonNode parse(String rawJson) {
        if (StringUtils.isBlank(rawJson)) {
            return null;
        }
        try {
            return MAPPER.readTree(rawJson);
        } catch (Exception notValidJson) {
            return null;
        }
    }

    private static Iterable<JsonNode> array(JsonNode node) {
        List<JsonNode> items = new ArrayList<JsonNode>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                if (item != null && item.isObject()) {
                    items.add(item);
                }
            }
        }
        return items;
    }

    private static void add(List<Entry> entries, String field, JsonNode value) {
        add(entries, field, value == null || !value.isTextual() ? null : value.asText());
    }

    private static void add(List<Entry> entries, String field, String value) {
        if (!StringUtils.isBlank(value)) {
            entries.add(new Entry(field, value.trim()));
        }
    }
}
