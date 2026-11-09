package com.chengyinhub.business.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 第十二轮 k9(j5 N5):机审拒绝提示里不给作者看内部键。 */
class JourneyReviewTextsPlainFieldTest {

    @Test
    void namedFieldDropsInternalKey() {
        assertEquals("节点玩法「桥边问答 kxjwvb」的标题", JourneyReviewTexts.plainField("节点玩法「桥边问答 kxjwvb」.title"));
        assertEquals("节点玩法「桥边问答」的选项", JourneyReviewTexts.plainField("节点玩法「桥边问答」.blockConfig.options[]"));
        assertEquals("节点玩法「桥边问答」的题目", JourneyReviewTexts.plainField("节点玩法「桥边问答」.questionName"));
        assertEquals("节点玩法「桥边问答」的二级提示", JourneyReviewTexts.plainField("节点玩法「桥边问答」.hint2"));
        assertEquals("节点玩法「桥边问答」", JourneyReviewTexts.plainField("节点玩法「桥边问答」.blockConfig.weird"));
    }

    @Test
    void fieldWithoutReadableNameStaysForLocating() {
        assertEquals("journeyStory.ending.endings[].title", JourneyReviewTexts.plainField("journeyStory.ending.endings[].title"));
        assertEquals("主题名称", JourneyReviewTexts.plainField("主题名称"));
        assertEquals("", JourneyReviewTexts.plainField(null));
    }
}
