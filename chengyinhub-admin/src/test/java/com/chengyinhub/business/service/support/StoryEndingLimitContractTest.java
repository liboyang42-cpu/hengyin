package com.chengyinhub.business.service.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * C:U-057:结局条数上限两条链路不同是有意的 —— 旅程故事 16 条、玩法运行时 storyEndings 100 条,
 * 互不导入、各自在保存时校验。任何一侧改动都会让本用例红,提醒先确认产品口径并同步两处注释。
 */
class StoryEndingLimitContractTest {
    @Test
    void endingLimitsAreDocumentedPerChain() {
        assertEquals(16, JourneyStoryValidator.MAX_ITEMS);
        assertEquals(100, GameplayRuntimeConfig.MAX_STORY_ENDINGS);
    }
}
