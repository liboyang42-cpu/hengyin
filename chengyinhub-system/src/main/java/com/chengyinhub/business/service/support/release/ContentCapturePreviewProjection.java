package com.chengyinhub.business.service.support.release;

import com.chengyinhub.business.domain.vo.ContentCapturePreviewVO.TextEntry;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Fixed-schema, bounded prose projection. Never recursively walk arbitrary fields or expose raw configuration. */
final class ContentCapturePreviewProjection {
    static final int MAX_BYTES=1024*1024, MAX_ENTRIES=256, MAX_TEXT_CHARS=4096, MAX_TOTAL_CHARS=32768;
    private static final Pattern REDACT=Pattern.compile("(?is)(?:[a-z][a-z0-9+.-]*\\s*:|//|www\\.|(?:[a-z0-9-]+\\.)+(?:com|net|org|io|cn|co|dev|app|site)\\b|\\b(?:password|passwd|secret|token|api[_ -]?key|authorization|bearer|credential)\\b|-----BEGIN)");
    // Freeze this API whitelist independently of future additions to the general narrative collector.
    private static final Set<String> ALLOWED_TEXT_PATHS=new LinkedHashSet<>(Arrays.asList(
            "blockConfig.brief.body",
            "blockConfig.brief.carry[].text",
            "blockConfig.brief.question",
            "blockConfig.check.failText",
            "blockConfig.check.mods[].label",
            "blockConfig.check.skill",
            "blockConfig.check.successText",
            "blockConfig.classify.bins[].label",
            "blockConfig.classify.items[].label",
            "blockConfig.classify.prompt",
            "blockConfig.compare.left.items[].text",
            "blockConfig.compare.left.items[].time",
            "blockConfig.compare.left.label",
            "blockConfig.compare.prompt",
            "blockConfig.compare.right.items[].text",
            "blockConfig.compare.right.items[].time",
            "blockConfig.compare.right.label",
            "blockConfig.deliver.line",
            "blockConfig.deliver.reward",
            "blockConfig.enter.aside",
            "blockConfig.enter.opener",
            "blockConfig.enter.questions[].a",
            "blockConfig.enter.questions[].q",
            "blockConfig.match.left[].label",
            "blockConfig.match.prompt",
            "blockConfig.match.right[].label",
            "blockConfig.outcome.facts[].text",
            "blockConfig.outcome.line",
            "blockConfig.outcome.lineIfFail",
            "blockConfig.outcome.open",
            "blockConfig.outcome.title",
            "blockConfig.revisit.changes[].text",
            "blockConfig.route.todo",
            "blockConfig.route.walk",
            "blockConfig.sort.items[].label",
            "blockConfig.sort.prompt",
            "blockConfig.unlock.hook",
            "journeyStory.call.brief",
            "journeyStory.call.opener",
            "journeyStory.call.options[].line",
            "journeyStory.call.options[].reply",
            "journeyStory.ending.badges[].title",
            "journeyStory.ending.endings[].summary",
            "journeyStory.ending.endings[].title",
            "journeyStory.ending.epilogues[].ally",
            "journeyStory.ending.epilogues[].guarded",
            "journeyStory.ending.epilogues[].neutral",
            "journeyStory.ending.exhibits[].text",
            "journeyStory.stages[].end.changed[].text",
            "journeyStory.stages[].end.open",
            "journeyStory.stages[].question"));
    private final List<TextEntry> entries=new ArrayList<>();
    private int total;
    static List<TextEntry> project(ContentCaptureReceipt candidate) {
        String raw=candidate.serverOnlyJson();
        if (raw==null || raw.length()>MAX_BYTES || raw.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES) throw unavailable();
        JsonNode root=CanonicalContentJson.object(raw,"preview");
        ContentCapturePreviewProjection result=new ContentCapturePreviewProjection();
        JsonNode nodes=result.component(root,"orderedNodes").path("nodes");
        if (!nodes.isArray()) throw unavailable();
        for (int i=0;i<nodes.size();i++) {
            JsonNode node=nodes.get(i);
            if (!node.isObject()) throw unavailable();
            result.add("nodes["+i+"].name",node.get("name"));
            result.add("nodes["+i+"].description",node.get("description"));
        }
        JsonNode chapters=result.component(root,"chapterSources");
        if (!chapters.isArray()) throw unavailable();
        for (int i=0;i<chapters.size();i++) {
            JsonNode chapter=chapters.get(i);
            if (!chapter.isObject()) throw unavailable();
            String path="chapters["+i+"]";
            result.add(path+".name",chapter.get("name")); result.add(path+".description",chapter.get("description"));
            JsonNode rawBlocks=chapter.get("blocksJson");
            if (rawBlocks!=null && !rawBlocks.isNull()) {
                if (!rawBlocks.isTextual()) throw unavailable();
                if (rawBlocks.textValue().trim().isEmpty()) continue;
                JsonNode blocks=parseEmbedded(rawBlocks.textValue(),false);
                for (int j=0;j<blocks.size();j++) {
                    JsonNode block=blocks.get(j);
                    if (!block.isObject()) throw unavailable();
                    if ("text".equals(block.path("type").asText())) result.add(path+".blocks["+j+"].content",block.get("content"));
                }
            }
        }
        JsonNode route=result.component(root,"legacyRouteSnapshot");
        if (!route.isObject()) throw unavailable();
        JsonNode story=route.get("journeyStory");
        if (story!=null && !story.isNull()) {
            if (!story.isTextual()) throw unavailable();
            if (!story.textValue().trim().isEmpty())
                result.collect("story.","journeyStory.",parseEmbedded(story.textValue(),true));
        }
        JsonNode journey=route.get("nodeJourney");
        if (journey!=null) {
            if (!journey.isObject()) throw unavailable();
            Iterator<Map.Entry<String,JsonNode>> it=journey.fields();
            while (it.hasNext()) {
                Map.Entry<String,JsonNode> field=it.next();
                if (!field.getKey().matches("[1-9][0-9]{0,18}") || !field.getValue().isObject()) throw unavailable();
                result.collect("nodeJourney["+field.getKey()+"].","blockConfig.",field.getValue());
            }
        }
        return result.entries;
    }
    private JsonNode component(JsonNode root,String name) {
        JsonNode component=root.path("components").path(name), content=component.get("content");
        if (!"FROZEN".equals(component.path("status").asText()) || content==null) throw unavailable();
        if (!CanonicalContentJson.sha256(CanonicalContentJson.write(content)).equals(component.path("capturedContentHash").asText()))
            throw new ContentCapturePreviewService.ConflictException();
        return content;
    }
    private static JsonNode parseEmbedded(String raw,boolean object) {
        try {
            CanonicalContentJson.assertWithinLimits(raw);
            JsonNode parsed=CanonicalContentJson.JSON.readTree(raw);
            if (parsed==null || (object?!parsed.isObject():!parsed.isArray())) throw unavailable();
            return parsed;
        } catch (Exception failure) { throw unavailable(); }
    }
    private void collect(String prefix,String namespace,JsonNode root) {
        // Read fixed paths directly: generic collectors may trim, coerce, or silently skip malformed values.
        for (String field:ALLOWED_TEXT_PATHS) if (field.startsWith(namespace))
            collectPath(prefix+field,root,field.substring(namespace.length()).split("\\."),0);
    }
    private void collectPath(String path,JsonNode node,String[] fields,int index) {
        if (node==null || node.isNull()) return;
        if (index==fields.length) { add(path,node); return; }
        if (!node.isObject()) throw unavailable();
        String field=fields[index]; boolean array=field.endsWith("[]");
        JsonNode child=node.get(array?field.substring(0,field.length()-2):field);
        if (child==null || child.isNull()) return;
        if (array) {
            if (!child.isArray()) throw unavailable();
            for (JsonNode item:child) {
                if (!item.isObject()) throw unavailable();
                collectPath(path,item,fields,index+1);
            }
        } else collectPath(path,child,fields,index+1);
    }
    private void add(String path,JsonNode value) {
        if (value==null || value.isNull()) return;
        if (!value.isTextual()) throw unavailable();
        addText(path,value.textValue());
    }
    private void addText(String path,String text) {
        if (text==null || text.isEmpty()) return;
        if (text.length()>MAX_TEXT_CHARS || entries.size()>=MAX_ENTRIES) throw unavailable();
        // Suppress link/credential-looking prose as an entire field, without returning its value or destination.
        String safe=REDACT.matcher(text).find()?"[REDACTED_LINK_OR_CREDENTIAL]":escape(text);
        total+=safe.length(); if (total>MAX_TOTAL_CHARS) throw unavailable();
        entries.add(new TextEntry(path,safe));
    }
    private static String escape(String text) {
        StringBuilder out=new StringBuilder();
        for (int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            switch(c) {
                case '&':out.append("&amp;");break;case '<':out.append("&lt;");break;case '>':out.append("&gt;");break;
                case '"':out.append("&quot;");break;case '\'':out.append("&#39;");break;
                default:
                    if ((Character.isISOControl(c) && c!='\n' && c!='\t') || Character.getType(c)==Character.FORMAT)
                        out.append("[CONTROL]");
                    else out.append(c);
            }
        }
        return out.toString();
    }
    private static ContentCaptureReviewService.UnavailableException unavailable() { return new ContentCaptureReviewService.UnavailableException(); }
}
