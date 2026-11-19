package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.ChapterBlockDTO;
import com.chengyinhub.business.domain.dto.ChapterDTO;
import com.chengyinhub.business.domain.dto.NodeDTO;
import com.chengyinhub.business.exception.ChapterFlowValidationException;
import com.chengyinhub.business.service.support.CastConfigSupport;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 角色 Cast v1:台词块 line、分支选项 trust、结局章 endingFor 的写入校验与落库形状。 */
class ChapterLineBlockFlowTest
{
    private static final ObjectMapper JSON = new ObjectMapper();

    static ChapterBlockDTO line(String speaker, String content, String audioUrl)
    {
        ChapterBlockDTO block = new ChapterBlockDTO();
        block.setType("line");
        block.setKey("l1");
        block.setSpeaker(speaker);
        block.setContent(content);
        block.setAudioUrl(audioUrl);
        return block;
    }

    static ChapterDTO chapter(ChapterBlockDTO... extra)
    {
        ChapterDTO chapter = new ChapterDTO();
        chapter.setSchemaVersion(1);
        chapter.setRequired(1);
        chapter.setNodes(Collections.singletonList(new NodeDTO()));
        ChapterBlockDTO text = new ChapterBlockDTO();
        text.setType("text");
        text.setContent("正文一");
        ChapterBlockDTO node = new ChapterBlockDTO();
        node.setType("node");
        node.setNodeIndex(0);
        List<ChapterBlockDTO> blocks = new ArrayList<>();
        blocks.add(text);
        blocks.addAll(Arrays.asList(extra));
        blocks.add(node);
        chapter.setBlocks(blocks);
        return chapter;
    }

    private static String compile(ChapterDTO chapter)
    {
        return ChapterFlowCompiler.compileChapterFlow(Collections.singletonList(chapter))
                .getChapters().get(0).materializeBlocks(Collections.singletonList(99L));
    }

    private static void rejected(ChapterBlockDTO block)
    {
        assertThrows(ChapterFlowValidationException.class, () -> compile(chapter(block)));
    }

    @Test
    void 台词块能存_不进description() throws Exception
    {
        ChapterBlockDTO line = line("c_abc123", "  {玩家},你终于来了。 ", "media-asset:7");
        line.setCond(JSON.readTree("{\"op\":\"HAS_TAG\",\"value\":\"tag.met\"}"));
        String rows = compile(chapter(line));
        JsonNode stored = JSON.readTree(rows).get(1);
        assertEquals("line", stored.get("type").asText());
        assertEquals("c_abc123", stored.get("speaker").asText());
        assertEquals("{玩家},你终于来了。", stored.get("content").asText());
        assertEquals("media-asset:7", stored.get("audioUrl").asText());
        assertEquals("tag.met", stored.get("cond").get("value").asText());
        assertEquals("正文一", ChapterFlowCompiler.compileChapterFlow(Collections.singletonList(chapter(line(
                "player", "我来了", "")))).getChapters().get(0).getDescription());
        assertEquals("", JSON.readTree(compile(chapter(line("player", "我来了", null)))).get(1).get("audioUrl").asText());
    }

    @Test
    void 负控_说话人_长度_配音地址_条件_多余字段()
    {
        rejected(line("narrator", "好", ""));
        rejected(line("", "好", ""));
        rejected(line("c_AB", "好", ""));
        rejected(line("player", "   ", ""));
        rejected(line("player", String.join("", Collections.nCopies(301, "字")), ""));
        compile(chapter(line("player", String.join("", Collections.nCopies(300, "字")), "")));
        rejected(line("player", "好", "http://a.example.cn/x.mp3"));
        rejected(line("player", "好", "media-asset:abc"));
        ChapterBlockDTO badCond = line("player", "好", "");
        badCond.setCond(JSON.createObjectNode().put("op", "VAR_GTE").put("value", "x"));
        rejected(badCond);
        ChapterBlockDTO withWho = line("player", "好", "");
        withWho.setWho("规矩");
        rejected(withWho);
        ChapterBlockDTO textWithSpeaker = new ChapterBlockDTO();
        textWithSpeaker.setType("text");
        textWithSpeaker.setContent("x");
        textWithSpeaker.setSpeaker("player");
        rejected(textWithSpeaker);
    }

    static ChapterBlockDTO branch(ChapterBlockDTO.TrustDeltaDTO... trust)
    {
        ChapterBlockDTO block = new ChapterBlockDTO();
        block.setType("branch");
        block.setKey("b1");
        block.setContent("跟谁走?");
        ChapterBlockDTO.BranchOptionDTO a = new ChapterBlockDTO.BranchOptionDTO();
        a.setText("跟林姐");
        a.setTo("continue");
        a.setTrust(Arrays.asList(trust));
        ChapterBlockDTO.BranchOptionDTO b = new ChapterBlockDTO.BranchOptionDTO();
        b.setText("自己走");
        b.setTo("end");
        block.setOptions(Arrays.asList(a, b));
        return block;
    }

    static ChapterBlockDTO.TrustDeltaDTO trust(String castId, Integer delta)
    {
        ChapterBlockDTO.TrustDeltaDTO t = new ChapterBlockDTO.TrustDeltaDTO();
        t.setCastId(castId);
        t.setDelta(delta);
        return t;
    }

    @Test
    void 分支选项带信任变化_落库_越界被拒() throws Exception
    {
        JsonNode stored = JSON.readTree(compile(chapter(branch(trust("c_abc123", 2), trust("c_xyz789", -1))))).get(1);
        JsonNode t = stored.get("options").get(0).get("trust");
        assertEquals(2, t.size());
        assertEquals(2, t.get(0).get("delta").asInt());
        assertTrue(!stored.get("options").get(1).has("trust"));
        rejected(branch(trust("c_abc123", 3)));
        rejected(branch(trust("c_abc123", -3)));
        rejected(branch(trust("c_abc123", 0)));
        rejected(branch(trust("c_abc123", null)));
        rejected(branch(trust("bad", 1)));
        rejected(branch(trust("c_abc123", 1), trust("c_abc123", 1)));
    }

    @Test
    void 结局章endingFor格式()
    {
        ChapterDTO ending = new ChapterDTO();
        ending.setSchemaVersion(1);
        ending.setRequired(1);
        ending.setBlocks(Collections.emptyList());
        ending.setEnding(JSON.createObjectNode().put("fallback", true).put("endingFor", "c_abc123"));
        ChapterFlowCompiler.compileChapterFlow(Arrays.asList(chapter(), ending));
        ending.setEnding(JSON.createObjectNode().put("fallback", true).put("endingFor", "林姐"));
        assertThrows(ChapterFlowValidationException.class,
                () -> ChapterFlowCompiler.compileChapterFlow(Arrays.asList(chapter(), ending)));
    }

    @Test
    void 保存时对照castConfig检查引用()
    {
        JsonNode cfg = CastConfigSupport.parse("{\"enabled\":true,\"cast\":[{\"id\":\"c_abc123\",\"name\":\"林姐\"}]}");
        CastConfigSupport.checkStoryRefs(Collections.singletonList(chapter(line("c_abc123", "好", ""), line2("player"))), cfg);
        assertThrows(ServiceException.class, () -> CastConfigSupport.checkStoryRefs(
                Collections.singletonList(chapter(line("c_zzz999", "好", ""))), cfg));
        // 没有角色配置时只能是 player
        CastConfigSupport.checkStoryRefs(Collections.singletonList(chapter(line("player", "好", ""))), null);
        assertThrows(ServiceException.class, () -> CastConfigSupport.checkStoryRefs(
                Collections.singletonList(chapter(line("c_abc123", "好", ""))), null));
        assertThrows(ServiceException.class, () -> CastConfigSupport.checkStoryRefs(
                Collections.singletonList(chapter(branch(trust("c_zzz999", 1)))), cfg));
        CastConfigSupport.checkStoryRefs(Collections.singletonList(chapter(branch(trust("c_abc123", 1)))), cfg);
        ChapterDTO ending = new ChapterDTO();
        ending.setEnding(JSON.createObjectNode().put("fallback", true).put("endingFor", "c_zzz999"));
        assertThrows(ServiceException.class, () -> CastConfigSupport.checkStoryRefs(Collections.singletonList(ending), cfg));
        ending.setEnding(JSON.createObjectNode().put("fallback", true).put("endingFor", "c_abc123"));
        CastConfigSupport.checkStoryRefs(Collections.singletonList(ending), cfg);
    }

    private static ChapterBlockDTO line2(String speaker)
    {
        ChapterBlockDTO block = line(speaker, "嗯", "");
        block.setKey("l2");
        return block;
    }
}
